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
