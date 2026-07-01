package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL43C;

public class FSR3Pipeline {

    // -------------------------------------------------------------------------
    // Pass 0: Motion vector generation from depth + prev/curr VP matrices
    // -------------------------------------------------------------------------
    private static final String MV_GEN_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uInputDepth;
        layout(binding = 1, rg16f) writeonly uniform image2D uOutputMV;

        uniform ivec2 uRenderSize;
        uniform mat4 uInvCurrVP;
        uniform mat4 uPrevVP;
        uniform vec2 uJitter;
        uniform vec2 uPrevJitter;
        uniform float uFarDepth;

        const float FAR_EPSILON = 0.001;

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

            vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
            float depth = texelFetch(uInputDepth, p, 0).r;

            if (abs(depth - uFarDepth) < FAR_EPSILON) {
                imageStore(uOutputMV, p, vec4(0.0));
                return;
            }

            vec2 ndc = uv * 2.0 - 1.0;
            float ndcZ = depth * 2.0 - 1.0;

            vec4 worldH = uInvCurrVP * vec4(ndc, ndcZ, 1.0);
            worldH /= worldH.w;

            vec4 prevClip = uPrevVP * vec4(worldH.xyz, 1.0);
            vec2 prevNdc = prevClip.xy / prevClip.w;
            vec2 prevUv = prevNdc * 0.5 + 0.5;

            vec2 mv = (prevUv - uv) * vec2(uRenderSize);
            // FIX: jitter cancellation sign was wrong — was doubling jitter
            // instead of cancelling it. For a static scene, mv = prevJitter - currJitter
            // (the jitter-induced pixel shift). To cancel jitter from MVs, subtract
            // (prevJitter - currJitter), NOT (currJitter - prevJitter).
            mv.x -= (uPrevJitter.x - uJitter.x);
            mv.y -= (uPrevJitter.y - uJitter.y);

            imageStore(uOutputMV, p, vec4(mv, 0.0, 0.0));
        }
        """;

    // -------------------------------------------------------------------------
    // Pass 1: Shading change detection — tile-based luminance histogram
    // -------------------------------------------------------------------------
    private static final String SHADING_CHANGE_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uInputColor;
        layout(binding = 1) uniform sampler2D uInputDepth;
        layout(binding = 2) uniform sampler2D uPreviousShadingChange;

        layout(binding = 3, rg8) writeonly uniform image2D uShadingChange;

        uniform ivec2 uRenderSize;
        uniform float uThreshold;
        uniform float uFarDepth;

        shared uint s_histHigh;
        shared uint s_histLow;
        shared uint s_skyCount;

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            bool inBounds = p.x < uRenderSize.x && p.y < uRenderSize.y;

            // Initialize shared memory (all threads must reach barrier)
            if (gl_LocalInvocationIndex == 0u) {
                s_histHigh = 0u;
                s_histLow = 0u;
                s_skyCount = 0u;
            }
            barrier();

            // Accumulate into tile histogram (only in-bounds pixels)
            if (inBounds) {
                // FIX: skip sky pixels from the histogram — they cause false
                // shading-change detection when clouds drift through the tile.
                float depth = texelFetch(uInputDepth, p, 0).r;
                bool isSky = abs(depth - uFarDepth) < 0.001;
                if (isSky) {
                    atomicAdd(s_skyCount, 1u);
                } else {
                    vec3 rgb = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    if (luma > uThreshold) {
                        atomicAdd(s_histHigh, 1u);
                    } else {
                        atomicAdd(s_histLow, 1u);
                    }
                }
            }
            barrier();

            // First thread classifies the tile
            if (gl_LocalInvocationIndex == 0u) {
                uint total = s_histHigh + s_histLow;
                float ratio = float(s_histHigh) / float(max(total, 1u));

                // Read previous frame's luminance ratio from R channel
                ivec2 tilePos = ivec2(gl_WorkGroupID.xy * 8);
                vec2 tileUv = (vec2(tilePos) + 4.0) / vec2(uRenderSize);
                float prevRatio = textureLod(uPreviousShadingChange, tileUv, 0.0).r;

                // Detect shading change: compare current vs previous luminance distribution
                float diff = abs(ratio - prevRatio);

                // FIX: if tile is mostly sky, suppress shading change — clouds
                // drifting through sky tiles should not trigger the shading-change
                // detector (it was designed for lighting changes, not cloud motion).
                float skyRatio = float(s_skyCount) / float(max(total + s_skyCount, 1u));
                if (skyRatio > 0.5) {
                    diff = 0.0;
                }

                // Write per-tile results: R = ratio (for next frame), G = change (for accumulate)
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 8; dx++) {
                        ivec2 tp = tilePos + ivec2(dx, dy);
                        if (tp.x < uRenderSize.x && tp.y < uRenderSize.y) {
                            imageStore(uShadingChange, tp, vec4(ratio, diff, 0, 0));
                        }
                    }
                }
            }
        }
        """;

    // -------------------------------------------------------------------------
    // Pass 2: Reconstruct previous depth + dilate motion vectors
    // -------------------------------------------------------------------------
    private static final String RECONSTRUCT_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uInputColor;
        layout(binding = 1) uniform sampler2D uInputMotionVectors;
        layout(binding = 2) uniform sampler2D uInputDepth;
        layout(binding = 3) uniform sampler2D uExposure;

        layout(binding = 4, r32ui) uniform uimage2D uReconstructedPrevDepth;
        layout(binding = 5, rgba16f) writeonly uniform image2D uDilatedData;

        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform ivec2 uMaxRenderSize;
        uniform vec4 uDeviceToViewDepth;
        uniform vec2 uJitter;
        uniform vec2 uMotionVectorScale;
        uniform vec2 uDownscaleFactor;
        uniform vec2 uMotionVectorJitterCancellation;
        uniform float uPreExposure;
        uniform float uPreviousFramePreExposure;
        uniform float uViewSpaceToMetersFactor;
        uniform float uFarDepth;
        uniform int uFrameIndex;

        ivec2 clampLoad(ivec2 p, ivec2 off, ivec2 sz) {
            ivec2 r = p + off;
            if (off.x < 0) r.x = max(r.x, 0);
            if (off.x > 0) r.x = min(r.x, sz.x - 1);
            if (off.y < 0) r.y = max(r.y, 0);
            if (off.y > 0) r.y = min(r.y, sz.y - 1);
            return r;
        }

        bool isOnScreen(ivec2 pos, ivec2 size) {
            return all(lessThan(uvec2(pos), uvec2(size)));
        }

        float getViewSpaceDepth(float d) {
            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
        }

        void findNearestDepth(ivec2 pos, ivec2 sz, out float nearD, out ivec2 nearC) {
            nearC = pos;
            nearD = texelFetch(uInputDepth, pos, 0).r;
            float farDist = abs(nearD - uFarDepth);
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    ivec2 np = pos + ivec2(x, y);
                    if (isOnScreen(np, sz)) {
                        float d = texelFetch(uInputDepth, np, 0).r;
                        if (abs(d - uFarDepth) > farDist) {
                            nearD = d;
                            farDist = abs(d - uFarDepth);
                            nearC = np;
                        }
                    }
                }
            }
        }

        void reconstructPrevDepth(ivec2 pos, float depth, vec2 mv) {
            // FIX: threshold was 0.1 in UV space (=10% of screen), which zeroed
            // out almost all legitimate motion. Use sub-pixel threshold instead.
            float mvLen = length(mv);
            float mvThreshold = 0.5 / float(uRenderSize.x);
            mv *= float(mvLen > mvThreshold);
            vec2 uv = (vec2(pos) + 0.5) / vec2(uRenderSize);
            vec2 ruv = uv + mv;
            vec2 fpx = ruv * vec2(uRenderSize) - vec2(0.5);
            ivec2 basePos = ivec2(floor(fpx));
            vec2 frac = fract(fpx);
            vec4 w = vec4((1-frac.x)*(1-frac.y), frac.x*(1-frac.y), (1-frac.x)*frac.y, frac.x*frac.y);
            ivec2 offs[4];
            offs[0] = ivec2(0,0); offs[1] = ivec2(1,0); offs[2] = ivec2(0,1); offs[3] = ivec2(1,1);
            for (int i = 0; i < 4; i++) {
                if (w[i] > 0.01) {
                    ivec2 sp = basePos + offs[i];
                    if (isOnScreen(sp, uRenderSize)) {
                        uint ud = floatBitsToUint(depth);
                        imageAtomicMax(uReconstructedPrevDepth, sp, ud);
                    }
                }
            }
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

            float nearestDepth;
            ivec2 nearestCoord;
            findNearestDepth(p, uRenderSize, nearestDepth, nearestCoord);

            vec2 rawMv = texelFetch(uInputMotionVectors, nearestCoord, 0).xy;
            vec2 mv = rawMv * uMotionVectorScale;
            mv -= uMotionVectorJitterCancellation;

            float viewZ = getViewSpaceDepth(nearestDepth);

            vec3 rgb = max(vec3(0), texelFetch(uInputColor, p, 0).rgb);
            rgb /= uPreExposure;
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            luma = clamp(luma, 0.0, 1.0);
            float lockLuma = pow(luma, 1.0/6.0);
            imageStore(uDilatedData, p, vec4(mv, nearestDepth, lockLuma));
            reconstructPrevDepth(p, nearestDepth, mv);
        }
        """;

    // -------------------------------------------------------------------------
    // Pass 3: Depth clip + reactive mask preprocessing
    // -------------------------------------------------------------------------
    private static final String DEPTH_CLIP_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uInputColor;
        layout(binding = 1) uniform sampler2D uInputDepth;
        layout(binding = 2) uniform sampler2D uInputMotionVectors;
        layout(binding = 3) uniform usampler2D uReconstructedPrevDepth;
        layout(binding = 4) uniform sampler2D uDilatedData;
        layout(binding = 5) uniform sampler2D uPreviousDilatedMotionVectors;
        layout(binding = 6) uniform sampler2D uShadingChange;
        layout(binding = 7) uniform sampler2D uExposure;
        layout(binding = 8) uniform sampler2D uReactiveMask;
        layout(binding = 9) uniform sampler2D uTransparencyAndCompositionMask;

        layout(binding = 10, rgba16f) writeonly uniform image2D uPreparedInputColor;
        layout(binding = 11, rg8) writeonly uniform image2D uDilatedReactiveMasks;

        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform ivec2 uMaxRenderSize;
        uniform vec4 uDeviceToViewDepth;
        uniform vec2 uJitter;
        uniform vec2 uMotionVectorScale;
        uniform vec2 uDownscaleFactor;
        uniform vec2 uMotionVectorJitterCancellation;
        uniform float uPreExposure;
        uniform float uPreviousFramePreExposure;
        uniform float uViewSpaceToMetersFactor;
        uniform float uExposureVal;
        uniform float uFarDepth;
        uniform int uFrameIndex;

        ivec2 clampLoad(ivec2 p, ivec2 off, ivec2 sz) {
            ivec2 r = p + off;
            if (off.x < 0) r.x = max(r.x, 0);
            if (off.x > 0) r.x = min(r.x, sz.x - 1);
            if (off.y < 0) r.y = max(r.y, 0);
            if (off.y > 0) r.y = min(r.y, sz.y - 1);
            return r;
        }

        bool isOnScreen(ivec2 pos, ivec2 size) {
            return all(lessThan(uvec2(pos), uvec2(size)));
        }

        float getViewSpaceDepth(float d) {
            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
        }

        vec3 yCoCgToRgb(vec3 yCoCg) {
            return vec3(
                yCoCg.x + yCoCg.y - yCoCg.z,
                yCoCg.x + yCoCg.z,
                yCoCg.x - yCoCg.y - yCoCg.z);
        }

        vec3 rgbToYCoCg(vec3 rgb) {
            return vec3(
                0.25*rgb.r + 0.5*rgb.g + 0.25*rgb.b,
                0.5*rgb.r - 0.5*rgb.b,
                -0.25*rgb.r + 0.5*rgb.g - 0.25*rgb.b);
        }

        vec3 getViewSpacePos(ivec2 vp, ivec2 vpSize, float devDepth) {
            float Z = getViewSpaceDepth(devDepth);
            vec2 ndc = vec2(vp) / vec2(vpSize) * vec2(2, -2) + vec2(-1, 1);
            float X = uDeviceToViewDepth[2] * ndc.x * Z;
            float Y = uDeviceToViewDepth[3] * ndc.y * Z;
            return vec3(X, Y, Z);
        }

        float computeDepthClip(ivec2 pos) {
            vec2 uv = (vec2(pos) + 0.5) / vec2(uRenderSize);
            float curDepth = texelFetch(uInputDepth, pos, 0).r;

            // Sky/infinite depth — force full disocclusion clip to prevent NaN explosion
            if (abs(curDepth - uFarDepth) < 0.001) return 1.0;

            float curViewZ = getViewSpaceDepth(curDepth);

            vec2 mv = texelFetch(uDilatedData, pos, 0).rg;
            float mvLen = length(mv);
            mv *= float(mvLen > 0.01);
            vec2 dilatedUv = uv + mv;
            float dilatedDepth = texelFetch(uDilatedData, pos, 0).b;

            vec2 fpx = dilatedUv * vec2(uRenderSize) - vec2(0.5);
            ivec2 base = ivec2(floor(fpx));
            vec2 frac = fract(fpx);
            vec4 w = vec4((1-frac.x)*(1-frac.y), frac.x*(1-frac.y), (1-frac.x)*frac.y, frac.x*frac.y);
            ivec2 offs[4];
            offs[0] = ivec2(0,0); offs[1] = ivec2(1,0); offs[2] = ivec2(0,1); offs[3] = ivec2(1,1);

            float depthSum = 0.0;
            float weightSum = 0.0;
            for (int i = 0; i < 4; i++) {
                if (w[i] > 0.01) {
                    ivec2 sp = base + offs[i];
                    if (isOnScreen(sp, uRenderSize)) {
                        float prevD = uintBitsToFloat(texelFetch(uReconstructedPrevDepth, sp, 0).r);
                        float prevViewZ = getViewSpaceDepth(prevD);
                        float diff = curViewZ - prevViewZ;
                        if (diff > 0.0) {
                            float planeD = max(prevD, curDepth);
                            vec2 halfSz = vec2(uRenderSize) * 0.5;
                            vec3 fCenter = getViewSpacePos(ivec2(halfSz), uRenderSize, planeD);
                            vec3 fCorner = getViewSpacePos(ivec2(0), uRenderSize, planeD);
                            float halfVp = length(vec2(uRenderSize));
                            float depthThresh = max(curViewZ, prevViewZ);
                            float ksep = 1.37e-05;
                            float kfov = length(fCorner) / length(fCenter);
                            float requiredSep = ksep * kfov * halfVp * depthThresh;
                            float resFactor = clamp(length(vec2(uRenderSize)) / length(vec2(1920, 1080)), 0.0, 1.0);
                            float power = mix(1.0, 3.0, resFactor);
                            float d = pow(clamp(requiredSep / max(diff, 1e-7), 0.0, 1.0), power) * w[i];
                            depthSum += d;
                            weightSum += w[i];
                        }
                    }
                }
            }
            return weightSum > 0.0 ? clamp(1.0 - depthSum / weightSum, 0.0, 1.0) : 0.0;
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

            float depthClip = computeDepthClip(p);

            vec3 rgb = max(vec3(0), texelFetch(uInputColor, p, 0).rgb);
            rgb *= uExposureVal;
            vec3 preparedYCoCg = rgbToYCoCg(rgb);
            imageStore(uPreparedInputColor, p, vec4(preparedYCoCg, depthClip));

            vec2 nucleusMv = texelFetch(uInputMotionVectors, p, 0).xy * uMotionVectorScale;
            float nucleusVel = length(nucleusMv * vec2(uRenderSize));
            float maxVel = nucleusVel;
            float minConv = 1.0;
            if (nucleusVel > 1e-2) {
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 sp = clampLoad(p, ivec2(x,y), uRenderSize);
                        vec2 smv = texelFetch(uInputMotionVectors, sp, 0).xy * uMotionVectorScale;
                        float sv = length(smv);
                        maxVel = max(sv, maxVel);
                        sv = max(sv, 1e-7);
                        minConv = min(minConv, dot(smv/sv, nucleusMv/nucleusVel));
                    }
                }
            }
            float motionDiv = clamp(1.0 - minConv, 0.0, 1.0) * clamp(maxVel / 0.01, 0.0, 1.0);
            float velScale = clamp(nucleusVel / 3.0, 0.0, 1.0);

            vec2 reactiveMask = vec2(motionDiv * velScale, motionDiv * velScale);
            imageStore(uDilatedReactiveMasks, p, vec4(reactiveMask, 0, 0));
        }
        """;

    // -------------------------------------------------------------------------
    // Pass 4: Lock detection (thin feature confidence)
    // -------------------------------------------------------------------------
    private static final String LOCK_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uDilatedData;

        layout(binding = 1, r32ui) writeonly uniform uimage2D uReconstructedPrevDepth;
        layout(binding = 2, r8) writeonly uniform image2D uNewLocks;

        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform vec2 uDownscaleFactor;
        uniform vec2 uJitter;

        ivec2 clampLoad(ivec2 p, ivec2 off, ivec2 sz) {
            ivec2 r = p + off;
            if (off.x < 0) r.x = max(r.x, 0);
            if (off.x > 0) r.x = min(r.x, sz.x - 1);
            if (off.y < 0) r.y = max(r.y, 0);
            if (off.y > 0) r.y = min(r.y, sz.y - 1);
            return r;
        }

        ivec2 hrPosFromLrPos(ivec2 lr) {
            vec2 srcJit = vec2(lr) + 0.5 - uJitter;
            vec2 hr = (srcJit / vec2(uRenderSize)) * vec2(uDisplaySize);
            return ivec2(floor(hr));
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

            float nucleus = texelFetch(uDilatedData, p, 0).a;
            float similarThresh = 1.05;
            float dissimMin = 1e10;
            float dissimMax = 0.0;
            uint mask = 0u;
            uint idx = 0u;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0) { idx++; continue; }
                    ivec2 sp = clampLoad(p, ivec2(x,y), uRenderSize);
                    float sl = texelFetch(uDilatedData, sp, 0).a;
                    float diff = max(sl, nucleus) / max(min(sl, nucleus), 1e-7);
                    if (diff > 0.0 && diff < similarThresh) {
                        mask |= (1u << idx);
                    } else {
                        dissimMin = min(dissimMin, sl);
                        dissimMax = max(dissimMax, sl);
                    }
                    idx++;
                }
            }

            bool isRidge = nucleus > dissimMax || nucleus < dissimMin;
            if (isRidge) {
                uint rej[4] = uint[](
                    (1u<<0)|(1u<<1)|(1u<<3)|(1u<<4),
                    (1u<<1)|(1u<<2)|(1u<<4)|(1u<<5),
                    (1u<<3)|(1u<<4)|(1u<<6)|(1u<<7),
                    (1u<<4)|(1u<<5)|(1u<<7)|(1u<<8));
                bool thin = true;
                for (int i = 0; i < 4; i++) {
                    if ((mask & rej[i]) == rej[i]) {
                        thin = false;
                        break;
                    }
                }
                if (thin) {
                    ivec2 hrPos = hrPosFromLrPos(p);
                    if (hrPos.x < uDisplaySize.x && hrPos.y < uDisplaySize.y) {
                        imageStore(uNewLocks, hrPos, vec4(1.0, 0, 0, 0));
                    }
                }
            }

        }
        """;

    // -------------------------------------------------------------------------
    // Pass 5: Accumulate — temporal upscale with ellipsoid color clamp
    // -------------------------------------------------------------------------
    private static final String ACCUMULATE_CS = """
        #version 430 core
        layout(local_size_x = 32, local_size_y = 1) in;
        layout(binding = 0) uniform sampler2D uInputColor;
        layout(binding = 1) uniform sampler2D uInputDepth;
        layout(binding = 2) uniform sampler2D uExposure;
        layout(binding = 3) uniform sampler2D uPreparedInputColor;
        layout(binding = 4) uniform sampler2D uDilatedData;
        layout(binding = 5) uniform sampler2D uDilatedReactiveMasks;
        layout(binding = 6) uniform sampler2D uInternalUpscaled;
        layout(binding = 7) uniform sampler2D uLockStatus;
        layout(binding = 8) uniform sampler2D uShadingChange;
        layout(binding = 9) uniform sampler2D uNewLocks;

        layout(binding = 10, r11f_g11f_b10f) writeonly uniform image2D uInternalUpscaledRW;
        layout(binding = 11, rgba8) writeonly uniform image2D uLockStatusRW;
        layout(binding = 14, rgba8) writeonly uniform image2D uUpscaledOutput;

        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform ivec2 uMaxRenderSize;
        uniform vec4 uDeviceToViewDepth;
        uniform vec2 uJitter;
        uniform vec2 uMotionVectorScale;
        uniform vec2 uDownscaleFactor;
        uniform vec2 uMotionVectorJitterCancellation;
        uniform float uPreExposure;
        uniform float uPreviousFramePreExposure;
        uniform float uViewSpaceToMetersFactor;
        uniform float uExposureVal;
        uniform float uFarDepth;
        uniform float uFarViewZ;
        uniform int uFrameIndex;

        const float FSR3_EPSILON = 1e-6;
        const float LOCK_LIFETIME = 6.0;
        const float fUpsampleLanczosWeightScale = 1.0 / 6.0;

        vec3 yCoCgToRgb(vec3 c) {
            return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
        }

        vec3 rgbToYCoCg(vec3 c) {
            return vec3(0.25*c.r + 0.5*c.g + 0.25*c.b, 0.5*c.r - 0.5*c.b, -0.25*c.r + 0.5*c.g - 0.25*c.b);
        }

        float getViewSpaceDepth(float d) {
            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
        }

        // Lanczos2 approximation (squared distance form)
        float lanczos2ApproxSq(float x2) {
            x2 = min(x2, 4.0);
            float a = (2.0/5.0)*x2 - 1.0;
            float b = (1.0/4.0)*x2 - 1.0;
            return ((25.0/16.0)*a*a - (25.0/16.0 - 1.0)) * (b*b);
        }

        // Ellipsoid color clamp — clamp history color within ellipsoid
        // defined by neighborhood color distribution in YCoCg space
        vec3 ellipsoidColorClamp(vec3 historyYCoCg, vec3 centerYCoCg,
                                  vec3 boxMin, vec3 boxMax,
                                  float confidence) {
            vec3 mean = centerYCoCg;

            // Scale ellipsoid by confidence: low confidence = tighter clamp
            // to prevent blur accumulation in stable areas
            float ellipsoidScale = mix(2.0, 1.0, confidence);

            vec3 halfExtents = (boxMax - boxMin) * 0.5 * ellipsoidScale;
            halfExtents = max(halfExtents, vec3(0.01));

            vec3 diff = historyYCoCg - mean;
            vec3 normDiff = diff / halfExtents;
            float distSq = dot(normDiff, normDiff);

            if (distSq > 1.0) {
                float dist = sqrt(distSq);
                vec3 clampedDiff = diff / dist;
                historyYCoCg = mean + clampedDiff * halfExtents;
            }

            // Hard bound: history can never exceed the actual neighborhood
            // pixel range, preventing ghosting from loose ellipsoid
            historyYCoCg = clamp(historyYCoCg, boxMin, boxMax);

            return historyYCoCg;
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uDisplaySize.x || p.y >= uDisplaySize.y) return;

            vec2 hrUv = (vec2(p) + 0.5) / vec2(uDisplaySize);
            vec2 lrUv = hrUv + uJitter / vec2(uRenderSize);
            vec2 lrUvClamped = clamp(lrUv, vec2(0.5/vec2(uRenderSize)), 1.0 - vec2(0.5/vec2(uRenderSize)));
            float sd0 = texelFetch(uInputDepth, ivec2(clamp(hrUv, 0.0, 1.0) * vec2(uRenderSize - 1)), 0).r;
            float sd1 = texelFetch(uInputDepth, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).r;
            // FIX: sky detection was using view-space Z which is hyperbolic near
            // the far plane — small depth differences became large view-Z differences,
            // causing terrain near the far plane to be misclassified as sky.
            // Now check raw depth buffer value directly (like MV_GEN does).
            bool isSkyDepth0 = abs(sd0 - uFarDepth) < 0.001;
            bool isSkyDepth1 = abs(sd1 - uFarDepth) < 0.001;
            bool isSky = isSkyDepth0 || isSkyDepth1;
            if (isSky) {
                lrUv = hrUv;
            }
            lrUvClamped = clamp(lrUv, vec2(0.5/vec2(uRenderSize)), 1.0 - vec2(0.5/vec2(uRenderSize)));

            vec2 mv = texelFetch(uDilatedData, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).rg;
            float mvPx = length(mv * vec2(uRenderSize));

            vec2 reprojUv = hrUv + mv * vec2(uRenderSize) / vec2(uDisplaySize);
            bool isExisting = reprojUv.x >= 0.0 && reprojUv.x <= 1.0 && reprojUv.y >= 0.0 && reprojUv.y <= 1.0;
            reprojUv = clamp(reprojUv, vec2(0.0), vec2(1.0));

            // Sample history
            vec4 hist = vec4(0);
            vec3 historyRGB = vec3(0);
            float temporalReactive = 0.0;
            if (isExisting && uFrameIndex > 0 && !isSky) {
                hist = textureLod(uInternalUpscaled, reprojUv, 0.0);
                historyRGB = hist.rgb;
                temporalReactive = texelFetch(uLockStatus, ivec2(reprojUv * vec2(uDisplaySize - 1)), 0).a;
            }
            vec3 historyYCoCg = rgbToYCoCg(historyRGB);

            // Lock status
            vec4 lockData = texelFetch(uLockStatus, ivec2(reprojUv * vec2(uDisplaySize - 1)), 0);
            float reproLockLife = lockData.r * LOCK_LIFETIME;
            float lockLuma = lockData.g * LOCK_LIFETIME;
            float accumWeight = (uFrameIndex > 0 && !isSky) ? lockData.b * 15.0 : 0.0;

            // New locks
            float newLockIntensity = texelFetch(uNewLocks, ivec2(floor(hrUv * vec2(uDisplaySize))), 0).r;
            bool newLock = newLockIntensity > (127.0 / 255.0);

            // Reactive masks
            vec2 reactiveMasks = texelFetch(uDilatedReactiveMasks, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).rg;
            float dilatedReactive = reactiveMasks.x;
            float accumulationMask = reactiveMasks.y;

            // Shading change mask (G channel = luminance ratio diff between frames)
            float shadingChange = texelFetch(uShadingChange, ivec2(lrUvClamped * vec2(uRenderSize - 1)), 0).g;

            // Prepared input color (YCoCg + depthClip in alpha)
            vec4 preparedColor = textureLod(uPreparedInputColor, lrUvClamped, 0.0);
            float depthClipFactor = preparedColor.a;

            // FIX: detect clouds — bright pixels at far depth. Clouds in Minecraft
            // are alpha-blended over sky and drift independently of camera motion.
            // Their MVs are wrong (either 0 if cloud doesn't write depth, or
            // camera-only if it does). Bypass temporal accumulation for them.
            vec3 upsampledYCoCg = preparedColor.xyz;
            float inputLuma = dot(upsampledYCoCg, vec3(1.0));
            bool isCloud = isSky && inputLuma > 0.55;
            bool isNewSample = !isExisting || uFrameIndex == 0 || isSky || isCloud;
            float upsampledWeight = fUpsampleLanczosWeightScale;
            float reactiveFactor = max(dilatedReactive, temporalReactive);

            // Build neighborhood box from 3x3 prepared input
            ivec2 boxBase = ivec2(floor(lrUvClamped * vec2(uRenderSize)));
            vec3 boxMin = upsampledYCoCg;
            vec3 boxMax = upsampledYCoCg;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    ivec2 sp = clamp(boxBase + ivec2(dx, dy), ivec2(0), uRenderSize - ivec2(1));
                    vec3 s = texelFetch(uPreparedInputColor, sp, 0).xyz;
                    boxMin = min(boxMin, s);
                    boxMax = max(boxMax, s);
                }
            }

            vec3 finalColor;
            float adjAccum = isExisting ? accumWeight * (1.0 - reactiveFactor) * (1.0 - depthClipFactor) : 0.0;
            if (isNewSample) {
                finalColor = yCoCgToRgb(upsampledYCoCg);
            } else {
                vec3 boxCenter = upsampledYCoCg;
                vec3 boxVec = (boxMax - boxMin) * 0.5;
                float velFactor = clamp(mvPx / 10.0, 0.0, 1.0);

                // Combined confidence: low confidence = tighter ellipsoid = more clamping
                float confidence = max(depthClipFactor, max(accumulationMask, velFactor));
                confidence = max(confidence, shadingChange);

                // FSR3 ellipsoid color clamp
                vec3 clampedHistory = ellipsoidColorClamp(historyYCoCg, boxCenter, boxMin, boxMax, confidence);

                // Lock logic (same as FSR2)
                float lumaDiff = abs(upsampledYCoCg.x - lockLuma) / max(max(upsampledYCoCg.x, lockLuma), 1e-6);
                bool lockTrusted = reproLockLife > 0.0 && lockLuma > 0.0 && lumaDiff < 0.2;
                float lockActive = lockTrusted ? 1.0 : 0.0;
                float lockContrib = lockActive * clamp(reproLockLife / 3.0, 0.0, 1.0);
                float reactiveContrib = 1.0 - pow(reactiveFactor, 1.0 / 2.0);
                float histContribFactor = lockContrib * reactiveContrib;
                historyYCoCg = mix(clampedHistory, historyYCoCg, clamp(histContribFactor, 0.0, 1.0));

                // Temporal blend (same as FSR2)
                float newAccum = adjAccum + upsampledWeight;
                float alpha = upsampledWeight / max(newAccum, FSR3_EPSILON);
                alpha = clamp(alpha, 0.0, 0.99);
                finalColor = yCoCgToRgb(mix(historyYCoCg, upsampledYCoCg, vec3(alpha)));
            }

            // Lock lifecycle
            float newLockLife = reproLockLife;
            if (newLock) {
                newLockLife = LOCK_LIFETIME;
                lockLuma = upsampledYCoCg.x;
            } else if (newLockLife > 0.0) {
                newLockLife = max(0.0, newLockLife - 0.04);
            }

            finalColor /= max(uExposureVal, 1e-7);

            vec4 outputColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
            imageStore(uUpscaledOutput, p, outputColor);

            // FIX: sky/cloud handling completely rewritten.
            // OLD code preserved stale history (sampled from wrong position due
            // to MV=0 under camera rotation) and read lock status from pixel p
            // instead of reprojUv — causing lock lifecycle incoherence.
            // NEW: for sky/cloud, write current color to history so next frame
            // (which will also have MV=0) samples the correct previous color
            // at the same screen position. Reset lock status to zero.
            if (isSky || isCloud) {
                imageStore(uInternalUpscaledRW, p, vec4(finalColor, 1.0));
                imageStore(uLockStatusRW, p, vec4(0.0, 0.0, 0.0, 0.0));
            } else {
                float newTemporalReactive = temporalReactive * 0.5;
                newTemporalReactive = max(newTemporalReactive, max(reactiveFactor * 0.1, dilatedReactive));
                newTemporalReactive = min(newTemporalReactive, 0.99);
                if (isNewSample) newTemporalReactive = 1.0;
                imageStore(uInternalUpscaledRW, p, vec4(finalColor, 0.0));

                float newAccumWeight = min((isExisting ? adjAccum : 0.0) + upsampledWeight, 15.0);
                vec2 newLockStatus;
                newLockStatus.r = newLockLife * (1.0 - reactiveFactor * 0.5) / LOCK_LIFETIME;
                newLockStatus.g = newLockLife > 0.0 ? lockLuma / LOCK_LIFETIME : 0.0;
                imageStore(uLockStatusRW, p, vec4(newLockStatus, newAccumWeight / 15.0, newTemporalReactive));
            }
        }
        """;

    // -------------------------------------------------------------------------
    // Pass 6: RCAS sharpen (from FSR1)
    // -------------------------------------------------------------------------
    private static final String RCAS_CS = """
        #version 430 core
        layout(local_size_x = 32, local_size_y = 1) in;
        layout(binding = 0, rgba8) uniform image2D uImg;

        uniform uvec4 uRcasConfig;
        uniform ivec2 uOutputSize;

        void main() {
            ivec2 ip = ivec2(gl_GlobalInvocationID.xy);
            if (ip.x >= uOutputSize.x || ip.y >= uOutputSize.y) return;

            float sharp = float(uRcasConfig.x) / 8.0;
            if (sharp < 0.001) return;

            vec3 c = imageLoad(uImg, ip).rgb;
            vec3 t = imageLoad(uImg, ip + ivec2(0, -1)).rgb;
            vec3 b = imageLoad(uImg, ip + ivec2(0, 1)).rgb;
            vec3 l = imageLoad(uImg, ip + ivec2(-1, 0)).rgb;
            vec3 r = imageLoad(uImg, ip + ivec2(1, 0)).rgb;

            vec3 d = max(abs(t - c), abs(b - c));
            d = max(d, abs(l - c));
            d = max(d, abs(r - c));

            float dMax = max(d.r, max(d.g, d.b));
            if (dMax < 1e-7) return;

            vec3 w = clamp(1.0 - sharp * d / dMax, 0.0, 1.0);

            vec3 sharpend = (c * 4.0 + t + b + l + r) * (1.0 / 8.0);
            sharpend = c + w * (sharpend - c);
            sharpend = clamp(sharpend, 0.0, 1.0);

            imageStore(uImg, ip, vec4(sharpend, 1.0));
        }
        """;

    // -------------------------------------------------------------------------
    // FG Shared Header — included in all FG pipeline shaders
    // -------------------------------------------------------------------------
    private static final String FG_SHARED_H = """
        #version 430 core
        // FFX-style type aliases for HLSL→GLSL port
        #define FFX_UNROLL
        #define FFX_STATIC
        #define FFX_PARAMETER_IN
        #define FFX_PARAMETER_OUT out
        #define FFX_MIN16_I2 ivec2
        #define FFX_MIN16_F2 vec2
        #define FFX_MIN16_F float
        #define FFX_HALF 0

        const float FFX_FRAMEINTERPOLATION_EPSILON = 1e-3;
        const float FFX_FRAMEINTERPOLATION_FLT_MAX = 3.402823466e+38;
        const float FFX_FRAMEINTERPOLATION_FLT_MIN = 1.175494351e-38;
        const float FFX_EPSILON = 1e-6;

        float ffxSaturate(float v) { return clamp(v, 0.0, 1.0); }
        vec2 ffxSaturate(vec2 v) { return clamp(v, vec2(0.0), vec2(1.0)); }
        vec3 ffxSaturate(vec3 v) { return clamp(v, vec3(0.0), vec3(1.0)); }
        float ffxPow(float b, float e) { return pow(b, e); }
        float ffxRound(float v) { return floor(v + 0.5); }
        vec2 ffxFract(vec2 v) { return fract(v); }
        float ffxMin(float a, float b) { return min(a, b); }
        float ffxMax(float a, float b) { return max(a, b); }
        vec3 ffxLerp(vec3 a, vec3 b, float t) { return mix(a, b, t); }
        vec3 ffxBroadcast3(float v) { return vec3(v); }
        vec2 ffxBroadcast2(float v) { return vec2(v); }
        float ffxLinearFromSrgb(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
        vec3 ffxLinearFromSrgb(vec3 c) { return vec3(ffxLinearFromSrgb(c.r), ffxLinearFromSrgb(c.g), ffxLinearFromSrgb(c.b)); }

        bvec2 FFX_GREATER_THAN_EQUAL(vec2 a, vec2 b) { return greaterThanEqual(a, b); }
        bvec2 FFX_EQUAL(vec2 a, vec2 b) { return equal(a, b); }
        bvec2 FFX_LESS_THAN(uvec2 a, uvec2 b) { return lessThan(a, b); }

        // FrameInterpolationConstants — matches AMD SDK constant buffer
        uniform ivec2 uRenderSize;
        uniform ivec2 uDisplaySize;
        uniform ivec2 uInterpolationRectBase;
        uniform ivec2 uInterpolationRectSize;
        uniform vec2 uJitter;
        uniform uint uFrameIndex;
        uniform bool uReset;
        uniform bool uInvertedDepth;
        uniform vec4 uDeviceToViewDepth;
        uniform float uTanHalfFovY;
        uniform ivec2 uMaxRenderSize;

        float convertFromDeviceDepthToViewSpace(float d) {
            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
        }

        vec2 computeNdc(vec2 px, ivec2 size) {
            return px / vec2(size) * vec2(2.0, -2.0) + vec2(-1.0, 1.0);
        }

        vec3 getViewSpacePosition(ivec2 vp, ivec2 vpSize, float devDepth) {
            float Z = convertFromDeviceDepthToViewSpace(devDepth);
            vec2 ndc = computeNdc(vec2(vp), vpSize);
            float X = uDeviceToViewDepth[2] * ndc.x * Z;
            float Y = uDeviceToViewDepth[3] * ndc.y * Z;
            return vec3(X, Y, Z);
        }

        vec2 renderSize() { return vec2(uRenderSize); }
        vec2 displaySize() { return vec2(uDisplaySize); }
        ivec2 interpRectBase() { return uInterpolationRectBase; }
        ivec2 interpRectSize() { return uInterpolationRectSize; }

        bool isOnScreen(ivec2 pos, ivec2 size) {
            return all(lessThan(uvec2(pos), uvec2(size)));
        }

        bool isUvInside(vec2 uv) {
            return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
        }

        float rgbToLuma(vec3 linearRgb) {
            return dot(linearRgb, vec3(0.2126, 0.7152, 0.0722));
        }

        float rawRgbToLuminance(vec3 rawRgb) {
            return rgbToLuma(ffxLinearFromSrgb(rawRgb));
        }

        struct BilinearData {
            ivec2 iOffsets[4];
            float fWeights[4];
            ivec2 iBasePos;
        };

        BilinearData getBilinearSamplingData(vec2 uv, ivec2 size) {
            BilinearData d;
            vec2 px = uv * vec2(size) - vec2(0.5);
            d.iBasePos = ivec2(floor(px));
            vec2 frac = fract(px);
            d.iOffsets[0] = ivec2(0,0); d.iOffsets[1] = ivec2(1,0);
            d.iOffsets[2] = ivec2(0,1); d.iOffsets[3] = ivec2(1,1);
            d.fWeights[0] = (1-frac.x)*(1-frac.y);
            d.fWeights[1] = frac.x*(1-frac.y);
            d.fWeights[2] = (1-frac.x)*frac.y;
            d.fWeights[3] = frac.x*frac.y;
            return d;
        }

        float minDividedByMax(float a, float b) {
            float m = max(a, b);
            return m != 0.0 ? min(a, b) / m : 0.0;
        }
        """;

    // -------------------------------------------------------------------------
    // FG Packing Header — motion vector field bit-packing & unpacking
    // -------------------------------------------------------------------------
    private static final String FG_PACKING_H = """
        struct VectorFieldEntry {
            vec2 fMotionVector;
            float uHighPriorityFactor;
            float uLowPriorityFactor;
            bool bValid;
            bool bPrimary;
            bool bSecondary;
            bool bInPainted;
            float fVelocity;
            bool bNegOutside;
            bool bPosOutside;
        };

        VectorFieldEntry newVectorFieldEntry() {
            VectorFieldEntry v;
            v.fMotionVector = vec2(0.0); v.uHighPriorityFactor = 0.0;
            v.uLowPriorityFactor = 0.0; v.bValid = false; v.bPrimary = false;
            v.bSecondary = false; v.bInPainted = false; v.fVelocity = 0.0;
            v.bNegOutside = false; v.bPosOutside = false;
            return v;
        }

        // Bit packing: each 32-bit field slot = [DepthPriority:16 | MVComponent:16]
        const uint PRIORITY_MAX = (1u << 16) - 1;
        const uint PRIORITY_SHIFT = 16;

        uvec2 packVectorFieldEntries(uint depthPriority, vec2 mv) {
            uint priority = (depthPriority & PRIORITY_MAX) << PRIORITY_SHIFT;
            return uvec2(priority | (packHalf2x16(mv.xy) & 0xFFFFu),
                         priority | ((packHalf2x16(mv.xy) >> 16) & 0xFFFFu));
        }

        VectorFieldEntry unpackVectorFieldEntries(uvec2 packed) {
            VectorFieldEntry v = newVectorFieldEntry();
            v.uHighPriorityFactor = float((packed.x >> PRIORITY_SHIFT) & PRIORITY_MAX) / float(PRIORITY_MAX);
            v.uLowPriorityFactor = 0.0;
            v.bValid = v.uHighPriorityFactor > 0.0;
            v.bPrimary = true;
            v.bSecondary = false;
            uint vecPacked = (packed.x & 0xFFFFu) | ((packed.y & 0xFFFFu) << 16);
            v.fMotionVector = unpackHalf2x16(vecPacked);
            return v;
        }
        """;

    // -------------------------------------------------------------------------
    // FG Pass 0: SETUP — clear MV fields + disocclusion mask
    // -------------------------------------------------------------------------
    private static final String FG_SETUP_CS = FG_SHARED_H + FG_PACKING_H + """
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0, r32ui) uniform uimage2D uGameMvFieldX;
        layout(binding = 1, r32ui) uniform uimage2D uGameMvFieldY;
        layout(binding = 2, rg8) uniform image2D uDisocclusionMask;

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);

            if (all(lessThan(uvec2(p), uvec2(uRenderSize)))) {
                imageStore(uGameMvFieldX, p, uvec4(0));
                imageStore(uGameMvFieldY, p, uvec4(0));
                imageStore(uDisocclusionMask, p, vec4(0.0, 0.0, 0.0, 0.0));
            }
        }
        """;

    // -------------------------------------------------------------------------
    // FG Pass 1: GAME_MOTION_VECTOR_FIELD — packed MV field with bilinear spread
    // Uses plain imageStore (no atomics) — XY pairs stay intact per-thread,
    // avoiding the earlier bug where separate atomicMax on X/Y textures
    // produced decoupled invalid MVs. Each pixel splats to 4 surrounding texels
    // for sub-pixel coverage.
    // -------------------------------------------------------------------------
    private static final String FG_GAME_MV_FIELD_CS = FG_SHARED_H + FG_PACKING_H + """
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uDilatedData;
        layout(binding = 3, r32ui) uniform uimage2D uGameMvFieldX;
        layout(binding = 4, r32ui) uniform uimage2D uGameMvFieldY;

        uint depthToPriority(float depth) {
            return uint(clamp(depth * float(PRIORITY_MAX), 1.0, float(PRIORITY_MAX)));
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (!isOnScreen(p, uRenderSize)) return;

            vec2 uv = (vec2(p) + 0.5) / renderSize();
            vec4 data = textureLod(uDilatedData, uv, 0.0);
            float depth = data.b;
            vec2 mv = data.rg;
            vec2 halfMv = mv * 0.5;

            uint priority = depthToPriority(depth);
            uvec2 packed = packVectorFieldEntries(priority, halfMv);

            // Bilinear splat to 4 surrounding texels (sub-pixel spread)
            vec2 fpx = uv * vec2(uRenderSize) - vec2(0.5);
            ivec2 base = ivec2(floor(fpx));
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    ivec2 sp = base + ivec2(dx, dy);
                    if (isOnScreen(sp, uRenderSize)) {
                        imageStore(uGameMvFieldX, sp, uvec4(packed.x));
                        imageStore(uGameMvFieldY, sp, uvec4(packed.y));
                    }
                }
            }
        }
        """;

    // -------------------------------------------------------------------------
    // FG Pass 2: DISOCCLUSION_MASK — depth-based bi-directional disocclusion
    // -------------------------------------------------------------------------
    private static final String FG_DISOCCLUSION_MASK_CS = FG_SHARED_H + FG_PACKING_H + """
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uDilatedData;
        layout(binding = 1, r32ui) uniform uimage2D uReconstructedPrevDepth;
        layout(binding = 2, r32ui) uniform uimage2D uGameMvFieldX;
        layout(binding = 3, r32ui) uniform uimage2D uGameMvFieldY;
        layout(binding = 4, rg8) uniform image2D uDisocclusionMask;

        const float Ksep = 1.37e-5;

        float loadPrevDepth(int estimatedIdx, ivec2 pos) {
            if (estimatedIdx == 0)
                return uintBitsToFloat(imageLoad(uReconstructedPrevDepth, pos).r);
            else
                return texelFetch(uDilatedData, pos, 0).b;
        }

        float computeDepthClip(int estimatedIdx, vec2 uv, float curDepth) {
            float curViewZ = convertFromDeviceDepthToViewSpace(curDepth);
            BilinearData bd = getBilinearSamplingData(uv, uRenderSize);
            float depthSum = 0.0, weightSum = 0.0;

            for (int i = 0; i < 4; i++) {
                if (bd.fWeights[i] > FFX_FRAMEINTERPOLATION_EPSILON) {
                    ivec2 sp = bd.iBasePos + bd.iOffsets[i];
                    if (isOnScreen(sp, uRenderSize)) {
                        float prevD = loadPrevDepth(estimatedIdx, sp);
                        float prevViewZ = convertFromDeviceDepthToViewSpace(prevD);
                        float diff = curViewZ - prevViewZ;
                        if (diff > 0.0) {
                            float planeD = max(prevD, curDepth);
                            vec3 fCenter = getViewSpacePosition(ivec2(uRenderSize / 2), uRenderSize, planeD);
                            vec3 fCorner = getViewSpacePosition(ivec2(0), uRenderSize, planeD);
                            float halfVp = length(vec2(uRenderSize));
                            float depthThresh = max(curViewZ, prevViewZ);
                            float kfov = length(fCorner) / length(fCenter);
                            float requiredSep = Ksep * kfov * halfVp * depthThresh;
                            float resFactor = clamp(length(vec2(uRenderSize)) / length(vec2(1920, 1080)), 0.0, 1.0);
                            float power = mix(1.0, 3.0, resFactor);
                            float d = pow(clamp(requiredSep / max(diff, 1e-7), 0.0, 1.0), power) * bd.fWeights[i];
                            depthSum += d;
                            weightSum += bd.fWeights[i];
                        }
                    }
                }
            }
            return weightSum > 0.0 ? clamp(1.0 - depthSum / weightSum, 0.0, 1.0) : 0.0;
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (!isOnScreen(p, uRenderSize)) return;

            vec2 uv = (vec2(p) + 0.5) / renderSize();
            float curDepth = textureLod(uDilatedData, uv, 0.0).b;

            uvec2 packed;
            packed.x = imageLoad(uGameMvFieldX, p).r;
            packed.y = imageLoad(uGameMvFieldY, p).r;
            VectorFieldEntry mv = unpackVectorFieldEntries(packed);

            float clipToPrev = 1.0 - computeDepthClip(0, uv + mv.fMotionVector, curDepth);
            float clipToCurr = 1.0 - computeDepthClip(1, uv - mv.fMotionVector, curDepth);

            vec2 disocclusion = vec2(clipToPrev, clipToCurr);
            disocclusion = vec2(
                greaterThanEqual(disocclusion, vec2(FFX_FRAMEINTERPOLATION_EPSILON)));

            vec2 srcMv = mv.fMotionVector * 2.0;
            ivec2 prevPos = ivec2((uv + srcMv) * renderSize());
            disocclusion.x = clamp(disocclusion.x + float(!isOnScreen(prevPos, uRenderSize)), 0.0, 1.0);
            ivec2 currPos = ivec2((uv - srcMv) * renderSize());
            disocclusion.y = clamp(disocclusion.y + float(!isOnScreen(currPos, uRenderSize)), 0.0, 1.0);

            imageStore(uDisocclusionMask, p, vec4(disocclusion, 0, 0));
        }
        """;

    // -------------------------------------------------------------------------
    // FG Pass 3: INTERPOLATION — forward/backward warp with disocclusion blend
    // -------------------------------------------------------------------------
    private static final String FG_INTERPOLATION_CS = FG_SHARED_H + FG_PACKING_H + """
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0, r32ui) uniform uimage2D uGameMvFieldX;
        layout(binding = 1, r32ui) uniform uimage2D uGameMvFieldY;
        layout(binding = 2, rg8) uniform image2D uDisocclusionMask;
        layout(binding = 3) uniform sampler2D uPrevInterpolationSource;
        layout(binding = 4) uniform sampler2D uCurrInterpolationSource;
        layout(binding = 5, rgba8) writeonly uniform image2D uFgOutput;
        layout(binding = 6, r8) writeonly uniform image2D uInpaintingMask;

        void updateInpaintWeight(inout float w, float factor) {
            w = clamp(max(w, factor), 0.0, 1.0);
        }

        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (!isOnScreen(p, uDisplaySize)) return;

            vec2 uv = (vec2(p) + 0.5) / displaySize();
            vec2 renderUv = uv * renderSize() / displaySize();
            ivec2 rp = ivec2(floor(renderUv * vec2(uRenderSize)));

            vec3 currColor = textureLod(uCurrInterpolationSource, uv, 0.0).rgb;

            vec2 disocclusion = vec2(1.0, 1.0);
            float inpainting = 0.0;

            if (isOnScreen(rp, uRenderSize)) {
                disocclusion = imageLoad(uDisocclusionMask, rp).rg;

                uvec2 packed;
                packed.x = imageLoad(uGameMvFieldX, rp).r;
                packed.y = imageLoad(uGameMvFieldY, rp).r;
                VectorFieldEntry mv = unpackVectorFieldEntries(packed);

                vec2 fwdUv = uv + mv.fMotionVector * displaySize() / renderSize();
                vec2 bwdUv = uv - mv.fMotionVector * displaySize() / renderSize();

                vec3 prevCol = textureLod(uPrevInterpolationSource, clamp(fwdUv, vec2(0.0), vec2(1.0)), 0.0).rgb;
                vec3 currWarp = textureLod(uCurrInterpolationSource, clamp(bwdUv, vec2(0.0), vec2(1.0)), 0.0).rgb;

                bool fwdValid = isUvInside(fwdUv);
                bool bwdValid = isUvInside(bwdUv);

                float t = 0.5;
                t += 0.5 * (1.0 - disocclusion.x);
                t -= 0.5 * (1.0 - disocclusion.y);

                if (!fwdValid) t = 1.0;
                if (!bwdValid) t = 0.0;

                vec3 blended = mix(prevCol, currWarp, clamp(t, 0.0, 1.0));

                float biDisoccluded = float(length(disocclusion) <= FFX_FRAMEINTERPOLATION_EPSILON);
                updateInpaintWeight(inpainting, biDisoccluded);

                vec3 result = mix(blended, currColor, inpainting);

                imageStore(uFgOutput, p, vec4(clamp(result, 0.0, 1.0), 1.0));
            } else {
                imageStore(uFgOutput, p, vec4(currColor, 1.0));
            }

            imageStore(uInpaintingMask, p, vec4(inpainting, 0, 0, 0));
        }
        """;

    // Program IDs
    private int mvGenProg;
    private int shadingChangeProg;
    private int reconstructProg;
    private int depthClipProg;
    private int lockProg;
    private int accumulateProg;
    private int rcasProg;

    // FG pipeline program IDs
    private int fgSetupProg;
    private int fgGameMvFieldProg;
    private int fgDisocclusionProg;
    private int fgInterpolationProg;

    // Internal texture IDs
    private int motionVectorTex;
    private int reconstructedPrevDepthTex;
    private int dilatedDataTex;
    private int dilatedReactiveMasksTex;
    private int previousDilatedDataTex;

    private int shadingChangeTex;
    private int previousShadingChangeTex;
    private int preparedInputColorTex;
    private int lockStatusHistoryTex;
    private int lockStatusCurrTex;
    private int newLocksTex;
    private int internalUpscaledTex;
    private int internalUpscaledWriteTex;
    private int upscaledOutputTex;
    private int clearFbo;

    // Frame generation textures
    private int prevColorTex;
    private int fgOutputTex;
    private int prevDilatedTex;
    private boolean hasPrevFrame;

    // FG pipeline internal textures (multi-pass frame generation)
    private int gameMvFieldXTex;
    private int gameMvFieldYTex;
    private int disocclusionMaskTex;
    private int inpaintingMaskTex;

    // FG pipeline state
    private int fgFrameIndex;
    private boolean fgResetRequested;

    private int renderWidth, renderHeight, displayWidth, displayHeight;
    private boolean compiled;
    private boolean fgCompiled;

    private int lastOutputTex;

    // Uniform locations per pass
    // MV Gen
    private int mv_uRenderSize, mv_uInvCurrVP, mv_uPrevVP, mv_uJitter, mv_uPrevJitter, mv_uFarDepth;

    // Shading change
    private int sc_uRenderSize, sc_uThreshold, sc_uFarDepth;

    // Reconstruct
    private int rec_uRenderSize, rec_uDisplaySize, rec_uMaxRenderSize;
    private int rec_uDeviceToViewDepth, rec_uJitter, rec_uMotionVectorScale;
    private int rec_uDownscaleFactor, rec_uMotionVectorJitterCancellation;
    private int rec_uPreExposure, rec_uPreviousFramePreExposure;
    private int rec_uViewSpaceToMetersFactor, rec_uFrameIndex, rec_uFarDepth;

    // Depth Clip
    private int dc_uRenderSize, dc_uDisplaySize, dc_uMaxRenderSize;
    private int dc_uDeviceToViewDepth, dc_uJitter, dc_uMotionVectorScale;
    private int dc_uDownscaleFactor, dc_uMotionVectorJitterCancellation;
    private int dc_uPreExposure, dc_uPreviousFramePreExposure;
    private int dc_uViewSpaceToMetersFactor, dc_uExposureVal, dc_uFrameIndex, dc_uFarDepth;

    // Lock
    private int lk_uRenderSize, lk_uDisplaySize, lk_uDownscaleFactor, lk_uJitter;

    // Accumulate
    private int ac_uRenderSize, ac_uDisplaySize, ac_uMaxRenderSize;
    private int ac_uDeviceToViewDepth, ac_uJitter, ac_uMotionVectorScale;
    private int ac_uDownscaleFactor, ac_uMotionVectorJitterCancellation;
    private int ac_uPreExposure, ac_uPreviousFramePreExposure;
    private int ac_uViewSpaceToMetersFactor, ac_uExposureVal, ac_uFarDepth, ac_uFarViewZ, ac_uFrameIndex;

    // RCAS
    private int rc_uRcasConfig, rc_uOutputSize;

    // FG uniforms are resolved at runtime via glGetUniformLocation per-program
    // (not cached — uniform locations are program-specific and caching from one
    //  program would give wrong locations for other FG programs)

    // UBO data
    private float[] deviceToViewDepth = new float[4];

    // Reusable matrix uniform buffers
    private final float[] matBuf = new float[16];
    private final Matrix4f tmpMat = new Matrix4f();

    private float farDepth = 1.0f;
    private float farViewZ;
    private int currentFrameIndex;

    public FSR3Pipeline() {
    }

    public void setup(int rw, int rh, int dw, int dh) {
        renderWidth = rw;
        renderHeight = rh;
        displayWidth = dw;
        displayHeight = dh;
        deviceToViewDepth[0] = 0.0f;
        deviceToViewDepth[1] = 1.0f;
        deviceToViewDepth[2] = 1.0f;
        deviceToViewDepth[3] = 1.0f;
        fgResetRequested = true;
        fgFrameIndex = 0;
    }

    public void setProjectionMatrix(Matrix4f proj) {
        float p22 = proj.m22();
        float p23 = proj.m23();
        deviceToViewDepth[0] = (1.0f - p22) * 0.5f;
        deviceToViewDepth[1] = -p23 * 0.5f;
        deviceToViewDepth[2] = -1.0f / proj.m00();
        deviceToViewDepth[3] = -1.0f / proj.m11();
        farDepth = (p22 > 0) ? 0.0f : 1.0f;
        farViewZ = deviceToViewDepth[1] / (farDepth - deviceToViewDepth[0] + 1e-10f);
    }

    private int getUniformLoc(int prog, String name) {
        int loc = GL43C.glGetUniformLocation(prog, name);
        if (loc < 0) {
            FSRMod.LOGGER.warn("FSR3 uniform '{}' not found in program {}", name, prog);
        }
        return loc;
    }

    private boolean ensureShaders() {
        if (compiled) return true;

        mvGenProg = ShaderBinaryCache.compileCompute(MV_GEN_CS);
        shadingChangeProg = ShaderBinaryCache.compileCompute(SHADING_CHANGE_CS);
        reconstructProg = ShaderBinaryCache.compileCompute(RECONSTRUCT_CS);
        depthClipProg = ShaderBinaryCache.compileCompute(DEPTH_CLIP_CS);
        lockProg = ShaderBinaryCache.compileCompute(LOCK_CS);
        accumulateProg = ShaderBinaryCache.compileCompute(ACCUMULATE_CS);
        rcasProg = ShaderBinaryCache.compileCompute(RCAS_CS);

        if (mvGenProg == 0 || shadingChangeProg == 0 || reconstructProg == 0 ||
            depthClipProg == 0 || lockProg == 0 || accumulateProg == 0 || rcasProg == 0) {
            FSRMod.LOGGER.error("FSR3: upscale shader compilation failed");
            return false;
        }

        // MV Gen uniforms
        mv_uRenderSize = getUniformLoc(mvGenProg, "uRenderSize");
        mv_uInvCurrVP = getUniformLoc(mvGenProg, "uInvCurrVP");
        mv_uPrevVP = getUniformLoc(mvGenProg, "uPrevVP");
        mv_uJitter = getUniformLoc(mvGenProg, "uJitter");
        mv_uPrevJitter = getUniformLoc(mvGenProg, "uPrevJitter");
        mv_uFarDepth = getUniformLoc(mvGenProg, "uFarDepth");

        // Shading change uniforms
        sc_uRenderSize = getUniformLoc(shadingChangeProg, "uRenderSize");
        sc_uThreshold = getUniformLoc(shadingChangeProg, "uThreshold");
        sc_uFarDepth = getUniformLoc(shadingChangeProg, "uFarDepth");

        // Reconstruct uniforms
        rec_uRenderSize = getUniformLoc(reconstructProg, "uRenderSize");
        rec_uDisplaySize = getUniformLoc(reconstructProg, "uDisplaySize");
        rec_uMaxRenderSize = getUniformLoc(reconstructProg, "uMaxRenderSize");
        rec_uDeviceToViewDepth = getUniformLoc(reconstructProg, "uDeviceToViewDepth");
        rec_uJitter = getUniformLoc(reconstructProg, "uJitter");
        rec_uMotionVectorScale = getUniformLoc(reconstructProg, "uMotionVectorScale");
        rec_uDownscaleFactor = getUniformLoc(reconstructProg, "uDownscaleFactor");
        rec_uMotionVectorJitterCancellation = getUniformLoc(reconstructProg, "uMotionVectorJitterCancellation");
        rec_uPreExposure = getUniformLoc(reconstructProg, "uPreExposure");
        rec_uPreviousFramePreExposure = getUniformLoc(reconstructProg, "uPreviousFramePreExposure");
        rec_uViewSpaceToMetersFactor = getUniformLoc(reconstructProg, "uViewSpaceToMetersFactor");
        rec_uFrameIndex = getUniformLoc(reconstructProg, "uFrameIndex");
        rec_uFarDepth = getUniformLoc(reconstructProg, "uFarDepth");

        // Depth Clip uniforms
        dc_uRenderSize = getUniformLoc(depthClipProg, "uRenderSize");
        dc_uDisplaySize = getUniformLoc(depthClipProg, "uDisplaySize");
        dc_uMaxRenderSize = getUniformLoc(depthClipProg, "uMaxRenderSize");
        dc_uDeviceToViewDepth = getUniformLoc(depthClipProg, "uDeviceToViewDepth");
        dc_uJitter = getUniformLoc(depthClipProg, "uJitter");
        dc_uMotionVectorScale = getUniformLoc(depthClipProg, "uMotionVectorScale");
        dc_uDownscaleFactor = getUniformLoc(depthClipProg, "uDownscaleFactor");
        dc_uMotionVectorJitterCancellation = getUniformLoc(depthClipProg, "uMotionVectorJitterCancellation");
        dc_uPreExposure = getUniformLoc(depthClipProg, "uPreExposure");
        dc_uPreviousFramePreExposure = getUniformLoc(depthClipProg, "uPreviousFramePreExposure");
        dc_uViewSpaceToMetersFactor = getUniformLoc(depthClipProg, "uViewSpaceToMetersFactor");
        dc_uExposureVal = getUniformLoc(depthClipProg, "uExposureVal");
        dc_uFrameIndex = getUniformLoc(depthClipProg, "uFrameIndex");
        dc_uFarDepth = getUniformLoc(depthClipProg, "uFarDepth");

        // Lock uniforms
        lk_uRenderSize = getUniformLoc(lockProg, "uRenderSize");
        lk_uDisplaySize = getUniformLoc(lockProg, "uDisplaySize");
        lk_uDownscaleFactor = getUniformLoc(lockProg, "uDownscaleFactor");
        lk_uJitter = getUniformLoc(lockProg, "uJitter");

        // Accumulate uniforms
        ac_uRenderSize = getUniformLoc(accumulateProg, "uRenderSize");
        ac_uDisplaySize = getUniformLoc(accumulateProg, "uDisplaySize");
        ac_uMaxRenderSize = getUniformLoc(accumulateProg, "uMaxRenderSize");
        ac_uDeviceToViewDepth = getUniformLoc(accumulateProg, "uDeviceToViewDepth");
        ac_uJitter = getUniformLoc(accumulateProg, "uJitter");
        ac_uMotionVectorScale = getUniformLoc(accumulateProg, "uMotionVectorScale");
        ac_uDownscaleFactor = getUniformLoc(accumulateProg, "uDownscaleFactor");
        ac_uMotionVectorJitterCancellation = getUniformLoc(accumulateProg, "uMotionVectorJitterCancellation");
        ac_uPreExposure = getUniformLoc(accumulateProg, "uPreExposure");
        ac_uPreviousFramePreExposure = getUniformLoc(accumulateProg, "uPreviousFramePreExposure");
        ac_uViewSpaceToMetersFactor = getUniformLoc(accumulateProg, "uViewSpaceToMetersFactor");
        ac_uExposureVal = getUniformLoc(accumulateProg, "uExposureVal");
        ac_uFrameIndex = getUniformLoc(accumulateProg, "uFrameIndex");
        ac_uFarDepth = getUniformLoc(accumulateProg, "uFarDepth");
        ac_uFarViewZ = getUniformLoc(accumulateProg, "uFarViewZ");

        // RCAS uniforms
        rc_uRcasConfig = getUniformLoc(rcasProg, "uRcasConfig");
        rc_uOutputSize = getUniformLoc(rcasProg, "uOutputSize");

        compiled = true;
        FSRMod.LOGGER.info("FSR3: All 7 upscale shaders compiled");
        return true;
    }

    private boolean ensureFgShaders() {
        if (fgCompiled) return true;

        fgSetupProg = ShaderBinaryCache.compileCompute(FG_SETUP_CS);
        fgGameMvFieldProg = ShaderBinaryCache.compileCompute(FG_GAME_MV_FIELD_CS);
        fgDisocclusionProg = ShaderBinaryCache.compileCompute(FG_DISOCCLUSION_MASK_CS);
        fgInterpolationProg = ShaderBinaryCache.compileCompute(FG_INTERPOLATION_CS);

        if (fgSetupProg == 0 || fgGameMvFieldProg == 0 ||
            fgDisocclusionProg == 0 || fgInterpolationProg == 0) {
            FSRMod.LOGGER.error("FSR3: FG shader compilation failed — FG disabled");
            return false;
        }

        fgCompiled = true;
        FSRMod.LOGGER.info("FSR3: All 4 FG shaders compiled");
        return true;
    }

    private int createTex2D(int w, int h, int fmt, int minFilter, int magFilter) {
        int tex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, tex);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, fmt, w, h);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, minFilter);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, magFilter);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
        return tex;
    }

    private void ensureTextures() {
        if (motionVectorTex == 0)
            motionVectorTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG16F, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (reconstructedPrevDepthTex == 0)
            reconstructedPrevDepthTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (dilatedDataTex == 0)
            dilatedDataTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (dilatedReactiveMasksTex == 0)
            dilatedReactiveMasksTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (previousDilatedDataTex == 0)
            previousDilatedDataTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);

        if (shadingChangeTex == 0)
            shadingChangeTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (previousShadingChangeTex == 0)
            previousShadingChangeTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);

        if (preparedInputColorTex == 0)
            preparedInputColorTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (lockStatusCurrTex == 0)
            lockStatusCurrTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (lockStatusHistoryTex == 0)
            lockStatusHistoryTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (newLocksTex == 0)
            newLocksTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (internalUpscaledTex == 0)
            internalUpscaledTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R11F_G11F_B10F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (internalUpscaledWriteTex == 0)
            internalUpscaledWriteTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R11F_G11F_B10F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        if (upscaledOutputTex == 0)
            upscaledOutputTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);

        // Frame generation textures
        if (prevColorTex == 0) {
            prevColorTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
            fgOutputTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
            prevDilatedTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
        }

        // FG pipeline internal textures
        if (gameMvFieldXTex == 0)
            gameMvFieldXTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (gameMvFieldYTex == 0)
            gameMvFieldYTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (disocclusionMaskTex == 0)
            disocclusionMaskTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
        if (inpaintingMaskTex == 0)
            inpaintingMaskTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
    }

    public void recreateTextures() {
        deleteTextures();
        ensureTextures();
    }

    public void deleteAllTextures() {
        deleteTextures();
    }

    private void deleteTextures() {
        int[] texs = new int[] {
            motionVectorTex, reconstructedPrevDepthTex, dilatedDataTex,
            dilatedReactiveMasksTex, previousDilatedDataTex,
            shadingChangeTex, previousShadingChangeTex,
            preparedInputColorTex, lockStatusCurrTex, lockStatusHistoryTex,
            newLocksTex, internalUpscaledTex, internalUpscaledWriteTex,
            upscaledOutputTex,
            prevColorTex, fgOutputTex, prevDilatedTex,
            gameMvFieldXTex, gameMvFieldYTex,
            disocclusionMaskTex, inpaintingMaskTex
        };
        for (int t : texs) {
            if (t != 0) GL43C.glDeleteTextures(t);
        }
        motionVectorTex = 0; reconstructedPrevDepthTex = 0; dilatedDataTex = 0;
        dilatedReactiveMasksTex = 0; previousDilatedDataTex = 0;
        shadingChangeTex = 0; previousShadingChangeTex = 0;
        preparedInputColorTex = 0; lockStatusCurrTex = 0; lockStatusHistoryTex = 0;
        newLocksTex = 0; internalUpscaledTex = 0; internalUpscaledWriteTex = 0;
        upscaledOutputTex = 0;
        prevColorTex = 0; fgOutputTex = 0; prevDilatedTex = 0;
        gameMvFieldXTex = 0; gameMvFieldYTex = 0;
        disocclusionMaskTex = 0; inpaintingMaskTex = 0;
        hasPrevFrame = false;
    }

    private void setCommonUniforms(int prog, int uRS, int uDS, int uMRS,
                                    int uDTV, int uJit, int uMVS, int uDSF, int uMVJC,
                                    int uPreExp, int uPrevPreExp, int uVSMF, int uFIdx,
                                    float jitterX, float jitterY,
                                    float motionVecScaleX, float motionVecScaleY,
                                    float downscaleX, float downscaleY,
                                    float jitterCancelX, float jitterCancelY) {
        GL43C.glUniform2i(uRS, renderWidth, renderHeight);
        GL43C.glUniform2i(uDS, displayWidth, displayHeight);
        GL43C.glUniform2i(uMRS, displayWidth, displayHeight);
        GL43C.glUniform4f(uDTV, deviceToViewDepth[0], deviceToViewDepth[1], deviceToViewDepth[2], deviceToViewDepth[3]);
        GL43C.glUniform2f(uJit, jitterX, jitterY);
        GL43C.glUniform2f(uMVS, motionVecScaleX, motionVecScaleY);
        GL43C.glUniform2f(uDSF, downscaleX, downscaleY);
        GL43C.glUniform2f(uMVJC, jitterCancelX, jitterCancelY);
        GL43C.glUniform1f(uPreExp, 1.0f);
        GL43C.glUniform1f(uPrevPreExp, 1.0f);
        GL43C.glUniform1f(uVSMF, 1.0f);
        GL43C.glUniform1i(uFIdx, currentFrameIndex);
    }

    // -------------------------------------------------------------------------
    // Main dispatch
    // -------------------------------------------------------------------------
    public boolean dispatch(int colorTex, int depthTex,
                            float jitterX, float jitterY,
                            float prevJitterX, float prevJitterY,
                            float downscaleX, float downscaleY,
                            float motionVecScaleX, float motionVecScaleY,
                            Matrix4f projection,
                            int frameIndex,
                            float sharpness,
                            Matrix4f prevViewProj,
                            Matrix4f currViewProj) {
        if (!ensureShaders()) return false;
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return false;
        RenderSystem.assertOnRenderThread();

        setProjectionMatrix(projection);
        currentFrameIndex = frameIndex;

        ensureTextures();

        // Save minimal GL state
        int prevProg = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM);
        int prevActive = GL43C.glGetInteger(GL43C.GL_ACTIVE_TEXTURE);
        int[] prevTex = new int[10];
        for (int i = 0; i < prevTex.length; i++) {
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
            prevTex[i] = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        }
        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, prevViewport);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);

        // ---- PASS 0: Generate motion vectors from depth + prev/curr VP ----
        if (currViewProj != null && prevViewProj != null) {
            GL43C.glUseProgram(mvGenProg);
            GL43C.glUniform2i(mv_uRenderSize, renderWidth, renderHeight);

            currViewProj.invert(tmpMat).get(matBuf);
            GL43C.glUniformMatrix4fv(mv_uInvCurrVP, false, matBuf);

            prevViewProj.get(matBuf);
            GL43C.glUniformMatrix4fv(mv_uPrevVP, false, matBuf);

            GL43C.glUniform2f(mv_uJitter, jitterX, jitterY);
            GL43C.glUniform2f(mv_uPrevJitter, prevJitterX, prevJitterY);
            GL43C.glUniform1f(mv_uFarDepth, farDepth);

            GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
            GL43C.glBindImageTexture(1, motionVectorTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);

            GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        } else {
            if (clearFbo == 0) {
                clearFbo = GL43C.glGenFramebuffers();
            }
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, clearFbo);
            GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, motionVectorTex, 0);
            float[] mvZero = {0f, 0f, 0f, 0f};
            GL43C.glClearBufferfv(GL43C.GL_COLOR, 0, mvZero);
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        }

        // Jitter cancellation (zeroed — MV_GEN already strips jitter)
        float jitterCancelX = 0.0f;
        float jitterCancelY = 0.0f;

        // FIX: Clear reconstructedPrevDepthTex (R32UI) before Reconstruct pass.
        // Reconstruct uses imageAtomicMax — without clearing, stale depth values
        // from previous frames persist and only grow, corrupting the depth-clip
        // test. glClearTexImage is 4.4 only; use FBO + glClearBufferuiv.
        {
            if (clearFbo == 0) {
                clearFbo = GL43C.glGenFramebuffers();
            }
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, clearFbo);
            GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, reconstructedPrevDepthTex, 0);
            GL43C.glClearBufferuiv(GL43C.GL_COLOR, 0, new int[]{0, 0, 0, 0});
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        }

        // ---- PASS 1: Shading change detection ----
        GL43C.glUseProgram(shadingChangeProg);
        GL43C.glUniform2i(sc_uRenderSize, renderWidth, renderHeight);
        GL43C.glUniform1f(sc_uThreshold, 0.5f);
        GL43C.glUniform1f(sc_uFarDepth, farDepth);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, previousShadingChangeTex);

        GL43C.glBindImageTexture(3, shadingChangeTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);

        // ---- PASS 2: Reconstruct & Dilate ----
        GL43C.glUseProgram(reconstructProg);
        setCommonUniforms(reconstructProg,
            rec_uRenderSize, rec_uDisplaySize, rec_uMaxRenderSize,
            rec_uDeviceToViewDepth, rec_uJitter, rec_uMotionVectorScale,
            rec_uDownscaleFactor, rec_uMotionVectorJitterCancellation,
            rec_uPreExposure, rec_uPreviousFramePreExposure,
            rec_uViewSpaceToMetersFactor, rec_uFrameIndex,
            jitterX, jitterY, motionVecScaleX, motionVecScaleY, downscaleX, downscaleY,
            jitterCancelX, jitterCancelY);
        GL43C.glUniform1f(rec_uFarDepth, farDepth);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, motionVectorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);

        GL43C.glBindImageTexture(4, reconstructedPrevDepthTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(5, dilatedDataTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA16F);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        float exposure = 1.0f;

        // ---- PASS 3: Depth Clip ----
        GL43C.glUseProgram(depthClipProg);
        setCommonUniforms(depthClipProg,
            dc_uRenderSize, dc_uDisplaySize, dc_uMaxRenderSize,
            dc_uDeviceToViewDepth, dc_uJitter, dc_uMotionVectorScale,
            dc_uDownscaleFactor, dc_uMotionVectorJitterCancellation,
            dc_uPreExposure, dc_uPreviousFramePreExposure,
            dc_uViewSpaceToMetersFactor, dc_uFrameIndex,
            jitterX, jitterY, motionVecScaleX, motionVecScaleY, downscaleX, downscaleY,
            jitterCancelX, jitterCancelY);
        GL43C.glUniform1f(dc_uExposureVal, exposure);
        GL43C.glUniform1f(dc_uFarDepth, farDepth);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, motionVectorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, reconstructedPrevDepthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE5);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, previousDilatedDataTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE6);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, shadingChangeTex);

        GL43C.glBindImageTexture(10, preparedInputColorTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA16F);
        GL43C.glBindImageTexture(11, dilatedReactiveMasksTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Clear newLocksTex before LOCK pass so LOCK writes into a clean buffer
        GL43C.glUseProgram(0);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, clearFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, newLocksTex, 0);
        float[] clearCol = {0f, 0f, 0f, 0f};
        GL43C.glClearBufferfv(GL43C.GL_COLOR, 0, clearCol);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);

        // ---- PASS 4: Lock ----
        GL43C.glUseProgram(lockProg);
        GL43C.glUniform2i(lk_uRenderSize, renderWidth, renderHeight);
        GL43C.glUniform2i(lk_uDisplaySize, displayWidth, displayHeight);
        GL43C.glUniform2f(lk_uDownscaleFactor, downscaleX, downscaleY);
        GL43C.glUniform2f(lk_uJitter, jitterX, jitterY);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);

        GL43C.glBindImageTexture(1, reconstructedPrevDepthTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(2, newLocksTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);

        // ---- PASS 5: Accumulate (with ellipsoid color clamp) ----
        GL43C.glUseProgram(accumulateProg);
        setCommonUniforms(accumulateProg,
            ac_uRenderSize, ac_uDisplaySize, ac_uMaxRenderSize,
            ac_uDeviceToViewDepth, ac_uJitter, ac_uMotionVectorScale,
            ac_uDownscaleFactor, ac_uMotionVectorJitterCancellation,
            ac_uPreExposure, ac_uPreviousFramePreExposure,
            ac_uViewSpaceToMetersFactor, ac_uFrameIndex,
            jitterX, jitterY, motionVecScaleX, motionVecScaleY, downscaleX, downscaleY,
            jitterCancelX, jitterCancelY);
        GL43C.glUniform1f(ac_uExposureVal, exposure);
        GL43C.glUniform1f(ac_uFarDepth, farDepth);
        GL43C.glUniform1f(ac_uFarViewZ, farViewZ);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, preparedInputColorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE5);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedReactiveMasksTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE6);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, internalUpscaledTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE7);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, lockStatusHistoryTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE8);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, shadingChangeTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE9);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, newLocksTex);

        GL43C.glBindImageTexture(10, internalUpscaledWriteTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R11F_G11F_B10F);
        GL43C.glBindImageTexture(11, lockStatusCurrTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
        GL43C.glBindImageTexture(14, upscaledOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);

        GL43C.glDispatchCompute((displayWidth + 31) / 32, displayHeight, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // ---- PASS 6: RCAS in-place (writes directly to upscaledOutputTex) ----
        int rcasSharpness = Math.round(Math.clamp(sharpness, 0.0f, 1.0f) * 7.0f);
        if (rcasSharpness > 0) {
            GL43C.glUseProgram(rcasProg);
            GL43C.glUniform4ui(rc_uRcasConfig, rcasSharpness, 0, 0, 0);
            GL43C.glUniform2i(rc_uOutputSize, displayWidth, displayHeight);

            GL43C.glBindImageTexture(0, upscaledOutputTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);

            GL43C.glDispatchCompute((displayWidth + 31) / 32, displayHeight, 1);

            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
            GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
        }
        lastOutputTex = upscaledOutputTex;

        // Ping-pong internal upscaled history
        int tmp3 = internalUpscaledTex;
        internalUpscaledTex = internalUpscaledWriteTex;
        internalUpscaledWriteTex = tmp3;

        // Ping-pong lock status
        int tmp = lockStatusHistoryTex;
        lockStatusHistoryTex = lockStatusCurrTex;
        lockStatusCurrTex = tmp;

        // Ping-pong previous dilated data
        int tmp2 = previousDilatedDataTex;
        previousDilatedDataTex = dilatedDataTex;
        dilatedDataTex = tmp2;

        // Ping-pong shading change history
        int tmp4 = previousShadingChangeTex;
        previousShadingChangeTex = shadingChangeTex;
        shadingChangeTex = tmp4;

        // Restore GL state
        GL43C.glUseProgram(prevProg);
        for (int i = 0; i < prevTex.length; i++) {
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex[i]);
        }
        GL43C.glActiveTexture(prevActive);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
        GL43C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        return true;
    }

    public int getOutputTexture() {
        return lastOutputTex;
    }

    public void setPrevFrame(int colorTex, int dilatedTex) {
        GL43C.glCopyImageSubData(colorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            prevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1);
        GL43C.glCopyImageSubData(dilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            prevDilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            renderWidth, renderHeight, 1);
        hasPrevFrame = true;
    }

    public void setPrevFrame(int colorTex) {
        savePrevFrame(colorTex);
    }

    public int getFgOutputTexture() {
        return fgOutputTex;
    }

    public boolean hasPreviousFrame() {
        return hasPrevFrame;
    }

    public void resetFrameGen() {
        hasPrevFrame = false;
        fgResetRequested = true;
        fgFrameIndex = 0;
    }

    public void savePrevFrame(int colorTex) {
        GL43C.glCopyImageSubData(colorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            prevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1);
        GL43C.glCopyImageSubData(previousDilatedDataTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            prevDilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            renderWidth, renderHeight, 1);
        hasPrevFrame = true;
    }

    private void setFgUniforms(int prog) {
        int loc;
        loc = GL43C.glGetUniformLocation(prog, "uRenderSize");
        if (loc >= 0) GL43C.glUniform2i(loc, renderWidth, renderHeight);
        loc = GL43C.glGetUniformLocation(prog, "uDisplaySize");
        if (loc >= 0) GL43C.glUniform2i(loc, displayWidth, displayHeight);
        loc = GL43C.glGetUniformLocation(prog, "uInterpolationRectBase");
        if (loc >= 0) GL43C.glUniform2i(loc, 0, 0);
        loc = GL43C.glGetUniformLocation(prog, "uInterpolationRectSize");
        if (loc >= 0) GL43C.glUniform2i(loc, displayWidth, displayHeight);
        loc = GL43C.glGetUniformLocation(prog, "uJitter");
        if (loc >= 0) GL43C.glUniform2f(loc, 0.0f, 0.0f);
        loc = GL43C.glGetUniformLocation(prog, "uFrameIndex");
        if (loc >= 0) GL43C.glUniform1ui(loc, fgFrameIndex);
        loc = GL43C.glGetUniformLocation(prog, "uReset");
        if (loc >= 0) GL43C.glUniform1i(loc, fgResetRequested ? 1 : 0);
        loc = GL43C.glGetUniformLocation(prog, "uInvertedDepth");
        if (loc >= 0) GL43C.glUniform1i(loc, 1);
        loc = GL43C.glGetUniformLocation(prog, "uDeviceToViewDepth");
        if (loc >= 0) GL43C.glUniform4f(loc,
            deviceToViewDepth[0], deviceToViewDepth[1], deviceToViewDepth[2], deviceToViewDepth[3]);
        loc = GL43C.glGetUniformLocation(prog, "uTanHalfFovY");
        if (loc >= 0) GL43C.glUniform1f(loc, 1.0f);
        loc = GL43C.glGetUniformLocation(prog, "uMaxRenderSize");
        if (loc >= 0) GL43C.glUniform2i(loc, displayWidth, displayHeight);
    }

    public boolean dispatchFrameGen(int currentColorTex) {
        if (!ensureFgShaders()) return false;

        int[] prevTex = new int[7];
        int prevProg = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM);
        int prevActive = GL43C.glGetInteger(GL43C.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < prevTex.length; i++) {
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
            prevTex[i] = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        }

        ensureTextures();

        // ---- PASS 0: SETUP (clear MV fields + disocclusion mask) ----
        GL43C.glUseProgram(fgSetupProg);
        setFgUniforms(fgSetupProg);

        GL43C.glBindImageTexture(0, gameMvFieldXTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(1, gameMvFieldYTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(2, disocclusionMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

        GL43C.glDispatchCompute((Math.max(renderWidth, displayWidth) + 7) / 8,
                                (Math.max(renderHeight, displayHeight) + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Note: after main upscale dispatch's ping-pong, dilatedDataTex holds frame N-2 data
        // and previousDilatedDataTex holds frame N data. FG pipeline reads the CURRENT frame.
        int fgDilatedData = previousDilatedDataTex != 0 ? previousDilatedDataTex : dilatedDataTex;

        // ---- PASS 1: GAME_MOTION_VECTOR_FIELD (reads dilatedData, writes MV field per-pixel) ----
        GL43C.glUseProgram(fgGameMvFieldProg);
        setFgUniforms(fgGameMvFieldProg);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fgDilatedData);
        GL43C.glUniform1i(GL43C.glGetUniformLocation(fgGameMvFieldProg, "uDilatedData"), 0);

        GL43C.glBindImageTexture(3, gameMvFieldXTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(4, gameMvFieldYTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // ---- PASS 2: DISOCCLUSION_MASK (reads dilatedData as sampler2D) ----
        GL43C.glUseProgram(fgDisocclusionProg);
        setFgUniforms(fgDisocclusionProg);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fgDilatedData);
        GL43C.glUniform1i(GL43C.glGetUniformLocation(fgDisocclusionProg, "uDilatedData"), 0);

        GL43C.glBindImageTexture(1, reconstructedPrevDepthTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(2, gameMvFieldXTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(3, gameMvFieldYTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(4, disocclusionMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // ---- PASS 3: INTERPOLATION (reads prev/curr color as sampler2D) ----
        GL43C.glUseProgram(fgInterpolationProg);
        setFgUniforms(fgInterpolationProg);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevColorTex);
        GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uPrevInterpolationSource"), 3);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
        GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uCurrInterpolationSource"), 4);

        GL43C.glBindImageTexture(0, gameMvFieldXTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(1, gameMvFieldYTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
        GL43C.glBindImageTexture(2, disocclusionMaskTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_RG8);
        GL43C.glBindImageTexture(5, fgOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
        GL43C.glBindImageTexture(6, inpaintingMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

        GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Unbind all FG texture units to avoid polluting GUI/text rendering state
        for (int i = 0; i < 7; i++) {
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
            GL43C.glBindImageTexture(i, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_R32UI);
        }

        // Restore GL state
        GL43C.glUseProgram(prevProg);
        for (int i = 0; i < prevTex.length; i++) {
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex[i]);
        }
        GL43C.glActiveTexture(prevActive);

        fgFrameIndex++;
        fgResetRequested = false;
        return true;
    }

    public void destroy() {
        if (mvGenProg != 0) { GL43C.glDeleteProgram(mvGenProg); mvGenProg = 0; }
        if (shadingChangeProg != 0) { GL43C.glDeleteProgram(shadingChangeProg); shadingChangeProg = 0; }
        if (reconstructProg != 0) { GL43C.glDeleteProgram(reconstructProg); reconstructProg = 0; }
        if (depthClipProg != 0) { GL43C.glDeleteProgram(depthClipProg); depthClipProg = 0; }
        if (lockProg != 0) { GL43C.glDeleteProgram(lockProg); lockProg = 0; }
        if (accumulateProg != 0) { GL43C.glDeleteProgram(accumulateProg); accumulateProg = 0; }
        if (rcasProg != 0) { GL43C.glDeleteProgram(rcasProg); rcasProg = 0; }
        if (fgSetupProg != 0) { GL43C.glDeleteProgram(fgSetupProg); fgSetupProg = 0; }

        if (fgGameMvFieldProg != 0) { GL43C.glDeleteProgram(fgGameMvFieldProg); fgGameMvFieldProg = 0; }
        if (fgDisocclusionProg != 0) { GL43C.glDeleteProgram(fgDisocclusionProg); fgDisocclusionProg = 0; }
        if (fgInterpolationProg != 0) { GL43C.glDeleteProgram(fgInterpolationProg); fgInterpolationProg = 0; }
        deleteTextures();
        if (clearFbo != 0) { GL43C.glDeleteFramebuffers(clearFbo); clearFbo = 0; }
        compiled = false;
        fgCompiled = false;
    }

    public boolean isReady() {
        return compiled;
    }
}
