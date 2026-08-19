package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import org.lwjgl.opengl.GL43C;

/**
 * FSR1 spatial upscaler — bilinear blit upscale + RCAS sharpen.
 *
 * Optimized for iGPUs (Radeon 860M, Intel Xe, etc.) where memory bandwidth
 * is the bottleneck. Uses:
 *   - glBlitFramebuffer with GL_LINEAR for the upscale (fixed-function, zero ALU)
 *   - Single-pass RCAS compute shader with shared-memory tile (1 texelFetch/pixel)
 *   - No EASU — the 12-tap lanczos variant was 2.4x more memory traffic with
 *     no quality benefit on Minecraft's blocky textures
 *
 * Total cost per output pixel: ~1 texelFetch (RCAS reads from shared memory tile)
 * vs the EASU variant's 12 texelFetch. On the 860M at 1080p output, this is
 * ~10M fetches/frame vs ~25M — a 2.5x reduction in memory bandwidth.
 */
public class FSR1Pipeline {

    private static final String RCAS_CS = """
        #version 430 core
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform sampler2D uInput;
        layout(binding = 1, rgba8) writeonly uniform image2D uOutput;
        uniform float uSharpness;
        uniform ivec2 uSize;

        // 18x18 tile: 16x16 workgroup + 1-pixel halo on all sides
        shared vec3 tile[18][18];

        void main() {
            // Flat-loop load: 256 threads fill 324 pixels (18x18 tile)
            uint idx = gl_LocalInvocationIndex;
            while (idx < 324u) {
                uint tx = idx % 18u;
                uint ty = idx / 18u;
                ivec2 tp = ivec2(int(gl_WorkGroupID.x * 16u + tx) - 1,
                                 int(gl_WorkGroupID.y * 16u + ty) - 1);
                tile[ty][tx] = texelFetch(uInput, clamp(tp, ivec2(0), uSize - 1), 0).rgb;
                idx += 256u;
            }
            barrier();
            memoryBarrierShared();

            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uSize.x || p.y >= uSize.y) return;

            int lx = int(gl_LocalInvocationID.x);
            int ly = int(gl_LocalInvocationID.y);
            vec3 c = tile[ly + 1][lx + 1];

            if (uSharpness < 0.001) {
                imageStore(uOutput, p, vec4(c, 1.0));
                return;
            }

            // FSR1 RCAS PORT (2026-08-08, "the grass is a blurry mess"): same real

            // FidelityFX RCAS as FSR2/3 - 5-tap cross, solved negative lobe that can

            // never clip (no halo ring), noise detector; sharpens local-max blades.

            float sharp = float(int(uSharpness * 7.0 + 0.5)) / 8.0;

            if (sharp < 0.001) {

                imageStore(uOutput, p, vec4(c, 1.0));

                return;

            }

            vec3 up = tile[ly][lx + 1];

            vec3 dn = tile[ly + 2][lx + 1];

            vec3 lt = tile[ly + 1][lx];

            vec3 rt = tile[ly + 1][lx + 2];

            float bL = up.b * 0.5 + (up.r * 0.5 + up.g);

            float dL = lt.b * 0.5 + (lt.r * 0.5 + lt.g);

            float eL = c.b * 0.5 + (c.r * 0.5 + c.g);

            float fL = rt.b * 0.5 + (rt.r * 0.5 + rt.g);

            float hL = dn.b * 0.5 + (dn.r * 0.5 + dn.g);

            float nz = 0.25 * (bL + dL + fL + hL) - eL;

            float lumaMax = max(max(max(bL, dL), max(eL, fL)), hL);

            float lumaMin = min(min(min(bL, dL), min(eL, fL)), hL);

            nz = clamp(abs(nz) / max(lumaMax - lumaMin, 1e-7), 0.0, 1.0);

            nz = -0.5 * nz + 1.0;

            vec3 mn4 = min(min(min(up, dn), lt), rt);

            vec3 mx4 = max(max(max(up, dn), lt), rt);

            float lowerLimiter = clamp(eL / max(min(min(bL, dL), min(fL, hL)), 1e-7), 0.0, 1.0);

            vec3 hitMin = mn4 * (1.0 / max(4.0 * mx4, 1e-7)) * lowerLimiter;

            vec3 hitMax = (vec3(1.0) - mx4) * (1.0 / min(4.0 * mn4 - 4.0, -1e-7));

            vec3 lobeV = max(-hitMin, hitMax);

            float lobe = max(-0.1875, min(max(lobeV.r, max(lobeV.g, lobeV.b)), 0.0)) * sharp;

            lobe *= nz;

            vec3 result = (lobe * (up + dn + lt + rt) + c) / max(4.0 * lobe + 1.0, 1e-7);

            imageStore(uOutput, p, vec4(clamp(result, 0.0, 1.0), 1.0));
        }
        """;

    // Fullscreen-triangle vertex shader (no attributes; gl_VertexID driven).
    private static final String EASU_VS = """
        #version 430 core
        void main() {
            vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    // EASU — Edge Adaptive Spatial Upsampling (FSR1). Ported from the reference
    // purescale_easu.fsh. Uses texelFetch (point samples at integer texels) so the
    // result is independent of the source texture's min/mag filter state and does
    // not disturb Minecraft's render-target sampling. This is the edge-directional
    // 12-tap kernel that smooths diagonal edges at low render scales; the plain
    // bilinear blit it replaces leaves visible block structure that RCAS then
    // sharpens into jaggies ("pixelated").
    private static final String EASU_FS = """
        #version 430 core
        uniform sampler2D uInput;
        uniform ivec2 uInSize;
        uniform ivec2 uOutSize;
        layout(location = 0) out vec4 fragColor;

        vec3 fetch(ivec2 idx) {
            idx = clamp(idx, ivec2(0), uInSize - 1);
            return texelFetch(uInput, idx, 0).rgb;
        }

        void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
            vec2 v;
            v.x = dot(off, dir);
            v.y = dot(off, vec2(-dir.y, dir.x));
            v *= len;
            float d2 = min(dot(v, v), clp);
            float wB = 2.0 / 5.0 * d2 - 1.0;
            float wA = lob * d2 - 1.0;
            wB *= wB;
            wA *= wA;
            wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
            float w = wB * wA;
            aC += c * w;
            aW += w;
        }

        #define LUMA(col) dot(col, vec3(0.5, 1.0, 0.5))

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            if (p.x >= uOutSize.x || p.y >= uOutSize.y) { discard; }
            vec2 ip = gl_FragCoord.xy * (vec2(uInSize) / vec2(uOutSize));
            vec2 base = floor(ip - 0.5) + 0.5;
            vec2 f = ip - base;
            // fetch(base + o) samples texel (floor(ip-0.5) + 1 + o); see port notes.
            ivec2 a = ivec2(floor(ip - 0.5)) + 1;

            vec3 b  = fetch(a + ivec2( 0, -1));
            vec3 c  = fetch(a + ivec2( 1, -1));
            vec3 e  = fetch(a + ivec2(-1,  0));
            vec3 f0 = fetch(a + ivec2( 0,  0));
            vec3 g  = fetch(a + ivec2( 1,  0));
            vec3 h  = fetch(a + ivec2( 2,  0));
            vec3 i  = fetch(a + ivec2(-1,  1));
            vec3 j  = fetch(a + ivec2( 0,  1));
            vec3 k  = fetch(a + ivec2( 1,  1));
            vec3 l  = fetch(a + ivec2( 2,  1));
            vec3 n  = fetch(a + ivec2( 0,  2));
            vec3 o  = fetch(a + ivec2( 1,  2));

            float bL = LUMA(b), cL = LUMA(c), eL = LUMA(e), fL = LUMA(f0);
            float gL = LUMA(g), hL = LUMA(h), iL = LUMA(i), jL = LUMA(j);
            float kL = LUMA(k), lL = LUMA(l), nL = LUMA(n), oL = LUMA(o);

            vec2 dir = vec2(0.0);
            float len = 0.0;
            {
                float lenX = max(abs(fL - eL), abs(fL - gL)); float dirX = eL - gL;
                float lenY = max(abs(fL - bL), abs(fL - jL)); float dirY = bL - jL;
                float w = (1.0 - f.x) * (1.0 - f.y);
                dir += vec2(dirX, dirY) * w; len += max(lenX, lenY) * w;
            }
            {
                float lenX = max(abs(gL - fL), abs(gL - hL)); float dirX = fL - hL;
                float lenY = max(abs(gL - cL), abs(gL - kL)); float dirY = cL - kL;
                float w = f.x * (1.0 - f.y);
                dir += vec2(dirX, dirY) * w; len += max(lenX, lenY) * w;
            }
            {
                float lenX = max(abs(jL - iL), abs(jL - kL)); float dirX = iL - kL;
                float lenY = max(abs(jL - fL), abs(jL - nL)); float dirY = fL - nL;
                float w = (1.0 - f.x) * f.y;
                dir += vec2(dirX, dirY) * w; len += max(lenX, lenY) * w;
            }
            {
                float lenX = max(abs(kL - jL), abs(kL - lL)); float dirX = jL - lL;
                float lenY = max(abs(kL - gL), abs(kL - oL)); float dirY = gL - oL;
                float w = f.x * f.y;
                dir += vec2(dirX, dirY) * w; len += max(lenX, lenY) * w;
            }

            float dirR = max(abs(dir.x), abs(dir.y));
            if (dirR < 1.0 / 32768.0) dir = vec2(1.0, 0.0); else dir /= dirR;
            float dirLen = length(dir);
            dir = (dirLen > 1e-5) ? dir / dirLen : vec2(1.0, 0.0);

            len = clamp(len * 2.0, 0.0, 1.0);
            len = len * len;

            vec2 len2 = vec2(1.0 + len, 1.0 - 0.5 * len);
            float lob = 0.5 - 0.29 * len;
            float clp = 1.0 / lob;

            vec3 aC = vec3(0.0);
            float aW = 0.0;
            tap(aC, aW, vec2( 0.0, -1.0) - f, dir, len2, lob, clp, b);
            tap(aC, aW, vec2( 1.0, -1.0) - f, dir, len2, lob, clp, c);
            tap(aC, aW, vec2(-1.0,  0.0) - f, dir, len2, lob, clp, e);
            tap(aC, aW, vec2( 0.0,  0.0) - f, dir, len2, lob, clp, f0);
            tap(aC, aW, vec2( 1.0,  0.0) - f, dir, len2, lob, clp, g);
            tap(aC, aW, vec2( 2.0,  0.0) - f, dir, len2, lob, clp, h);
            tap(aC, aW, vec2(-1.0,  1.0) - f, dir, len2, lob, clp, i);
            tap(aC, aW, vec2( 0.0,  1.0) - f, dir, len2, lob, clp, j);
            tap(aC, aW, vec2( 1.0,  1.0) - f, dir, len2, lob, clp, k);
            tap(aC, aW, vec2( 2.0,  1.0) - f, dir, len2, lob, clp, l);
            tap(aC, aW, vec2( 0.0,  2.0) - f, dir, len2, lob, clp, n);
            tap(aC, aW, vec2( 1.0,  2.0) - f, dir, len2, lob, clp, o);

            vec3 color = (aW > 1e-5) ? aC / aW : f0;
            vec3 mn = min(min(f0, g), min(j, k));
            vec3 mx = max(max(f0, g), max(j, k));
            color = clamp(color, mn, mx);

            fragColor = vec4(color, 1.0);
        }
        """;

    // GL error diagnostics. AMD reports "No detailed debug message due to a
    // non-debug context" for every API error (debug ids 1197/1198), so the debug
    // callback gives us nothing. glGetError() DOES return the real code — track
    // the first error per stage and log it once so the exact failing call is
    // identifiable. Errors are drained silently before each stage so stale errors
    // left by other mods / FSR4 interop are NOT misattributed to FSR1.
    private boolean easuErrorsDetected;
    private boolean[] stageLogged = new boolean[3]; // 0=blitTex 1=drawEasu 2=rcas-dispatch

    /** Drain any pending errors WITHOUT logging (clears stale state from other pipelines). */
    private void clearGlErrors() {
        while (GL43C.glGetError() != GL43C.GL_NO_ERROR) { /* drain */ }
    }

    /**
     * Drains pending errors for a stage; logs the first error per stage with the
     * correct hex formatting. Returns true if an error occurred.
     */
    private boolean drainGlErrors(String stage, int stageIndex) {
        boolean hadError = false;
        int err;
        while ((err = GL43C.glGetError()) != GL43C.GL_NO_ERROR) {
            hadError = true;
            if (stageIndex >= 0 && stageIndex < stageLogged.length && !stageLogged[stageIndex]) {
                stageLogged[stageIndex] = true;
                FSRMod.LOGGER.error("FSR1: GL error 0x{} in stage '{}' — first occurrence logged, further silenced",
                    Integer.toHexString(err).toUpperCase(), stage);
            }
        }
        return hadError;
    }

    /**
     * Marks EASU as permanently broken for this session (external mod GL-state
     * conflict) so subsequent dispatches fall back to bilinear blit + RCAS.
     */
    private void disableEasu() {
        if (!easuErrorsDetected) {
            easuErrorsDetected = true;
            FSRMod.LOGGER.error("FSR1: EASU pipeline failed (GL error — likely external mod state/feedback conflict) — "
                + "permanently falling back to bilinear blit + RCAS for this session");
        }
    }

    private int rcasProg;
    private boolean compiled;
    private int uSharpness, uSize;

    // EASU graphics-program state
    private int easuProg;
    private boolean easuCompiled;
    private int easuVao;
    private int easuUInput, easuUInSize, easuUOutSize;

    private int readFbo, drawFbo;
    private int intermediateTex;
    // RCAS output landing pad: RGBA8 texture WE own. RCAS writes here via
    // imageStore (format-safe binding), then a fixed-function 1:1 blit copies
    // to the caller's output texture. Writing the output directly via
    // glBindImageTexture was fragile — a format mismatch (RGBA8 vs RGBA16F)
    // or a feedback-loop class conflict (output attached to a bound FBO)
    // produced GL_INVALID_OPERATION (0x0502) at dispatch time on strict
    // drivers (AMD Radeon 860M), permanently disabling EASU. The blit
    // converts to ANY output format, so FSR1 never depends on it.
    private int rcasOutTex;
    private int texW, texH;

    public void setup(int rw, int rh, int dw, int dh) {
        // No-op — dimensions are passed per-dispatch. Exists for API compat
        // with FSRProcessor which calls setup() on resize/markDirty.
    }

    private boolean ensureShaders() {
        if (compiled) return true;
        rcasProg = ShaderBinaryCache.compileCompute(RCAS_CS);
        if (rcasProg == 0) return false;
        uSharpness = GL43C.glGetUniformLocation(rcasProg, "uSharpness");
        uSize = GL43C.glGetUniformLocation(rcasProg, "uSize");
        compiled = true;
        FSRMod.LOGGER.info("FSR1: blit + RCAS pipeline compiled (iGPU-optimized)");
        return true;
    }

    private void ensureTextures(int w, int h) {
        if (intermediateTex != 0 && rcasOutTex != 0 && texW == w && texH == h) return;
        if (intermediateTex != 0) { GL43C.glDeleteTextures(intermediateTex); intermediateTex = 0; }
        if (rcasOutTex != 0) { GL43C.glDeleteTextures(rcasOutTex); rcasOutTex = 0; }
        texW = w; texH = h;
        intermediateTex = createTex(w, h);
        rcasOutTex = createTex(w, h);
    }

    private int createTex(int w, int h) {
        int tex = GL43C.glGenTextures();
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, tex);
        GL43C.glTexStorage2D(GL43C.GL_TEXTURE_2D, 1, GL43C.GL_RGBA8, w, h);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private void dispatchRcas(int inputTex, int outputTex, int dstW, int dstH, float sharpness) {
        GL43C.glUseProgram(rcasProg);
        GL43C.glUniform1f(uSharpness, Math.clamp(sharpness, 0.0f, 1.0f));
        GL43C.glUniform2i(uSize, dstW, dstH);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, inputTex);
        // RCAS uses texelFetch — sampler filter state is irrelevant, no need to set LINEAR
        GL43C.glBindImageTexture(1, outputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
        clearGlErrors();
        GL43C.glDispatchCompute((dstW + 15) / 16, (dstH + 15) / 16, 1);
        // FRAMEBUFFER_BARRIER_BIT: the caller may follow this dispatch with a
        // fixed-function glBlitFramebuffer READ of the imageStore target — a
        // framebuffer-read operation, which is that barrier bit's class.
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT
                            | GL43C.GL_FRAMEBUFFER_BARRIER_BIT);
        // RCAS is used by BOTH the bilinear path (safe) and the EASU path. If the
        // EASU path errored at RCAS (e.g. feedback-loop class errors), disable
        // EASU — but only when a GL error actually occurred.
        if (drainGlErrors("rcas-dispatch", 2)) disableEasu();
        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
    }

    private void blitTex(int srcTex, int dstTex, int srcW, int srcH, int dstW, int dstH, int filter) {
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, readFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
            GL43C.GL_TEXTURE_2D, srcTex, 0);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
            GL43C.GL_TEXTURE_2D, dstTex, 0);
        GL43C.glViewport(0, 0, dstW, dstH);
        clearGlErrors();
        GL43C.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH,
            GL43C.GL_COLOR_BUFFER_BIT, filter);
        drainGlErrors("blitTex", 0);
    }

    private boolean ensureEasuShader() {
        if (easuCompiled) return true;
        int vs = GL43C.glCreateShader(GL43C.GL_VERTEX_SHADER);
        GL43C.glShaderSource(vs, EASU_VS);
        GL43C.glCompileShader(vs);
        if (GL43C.glGetShaderi(vs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            FSRMod.LOGGER.error("FSR1 EASU vertex shader compile failed: {}", GL43C.glGetShaderInfoLog(vs));
            GL43C.glDeleteShader(vs);
            return false;
        }
        int fs = GL43C.glCreateShader(GL43C.GL_FRAGMENT_SHADER);
        GL43C.glShaderSource(fs, EASU_FS);
        GL43C.glCompileShader(fs);
        if (GL43C.glGetShaderi(fs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            FSRMod.LOGGER.error("FSR1 EASU fragment shader compile failed: {}", GL43C.glGetShaderInfoLog(fs));
            GL43C.glDeleteShader(vs);
            GL43C.glDeleteShader(fs);
            return false;
        }
        easuProg = GL43C.glCreateProgram();
        GL43C.glAttachShader(easuProg, vs);
        GL43C.glAttachShader(easuProg, fs);
        GL43C.glLinkProgram(easuProg);
        GL43C.glDeleteShader(vs);
        GL43C.glDeleteShader(fs);
        if (GL43C.glGetProgrami(easuProg, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
            FSRMod.LOGGER.error("FSR1 EASU program link failed: {}", GL43C.glGetProgramInfoLog(easuProg));
            GL43C.glDeleteProgram(easuProg);
            easuProg = 0;
            return false;
        }
        easuUInput = GL43C.glGetUniformLocation(easuProg, "uInput");
        easuUInSize = GL43C.glGetUniformLocation(easuProg, "uInSize");
        easuUOutSize = GL43C.glGetUniformLocation(easuProg, "uOutSize");
        if (easuVao == 0) easuVao = GL43C.glGenVertexArrays();
        easuCompiled = true;
        FSRMod.LOGGER.info("FSR1: EASU shader compiled (edge-adaptive upscale)");
        return true;
    }

    private void drawEasu(int srcTex, int srcW, int srcH, int dstTex, int dstW, int dstH) {
        // CRITICAL: this is a GRAPHICS pass (glDrawArrays), unlike the FSR2/3
        // compute dispatches. It runs with whatever GL state Minecraft left
        // behind — blend/scissor/cull/depth are commonly ENABLED during the world
        // pass (GUI, particles, weather). If blend is on, the fullscreen triangle
        // accumulates over the previous frame's content in the destination
        // texture every frame → the classic "decaying/smearing" artifact (the
        // upscale blends with stale pixels instead of overwriting them). Scissor
        // clips the triangle to a stale sub-rect. Force a clean fullscreen-pass
        // state and restore afterwards.
        boolean prevBlend = GL43C.glIsEnabled(GL43C.GL_BLEND);
        boolean prevScissor = GL43C.glIsEnabled(GL43C.GL_SCISSOR_TEST);
        boolean prevCull = GL43C.glIsEnabled(GL43C.GL_CULL_FACE);
        boolean prevDepth = GL43C.glIsEnabled(GL43C.GL_DEPTH_TEST);
        boolean prevDepthMask = GL43C.glGetBoolean(GL43C.GL_DEPTH_WRITEMASK);
        // GL_COLOR_WRITEMASK is a 4-value query — glGetBoolean(int) in LWJGL uses a
        // 1-element buffer and would let glGetBooleanv write 4 bytes past it (heap
        // corruption risk). glGetInteger(GL_COLOR_WRITEMASK) returns a SINGLE packed
        // value (bit0=R, bit1=G, bit2=B, bit3=A) which is LWJGL-safe. Decode the 4
        // bits for the restore so a non-uniform mask (e.g. depth-only passes with
        // colorMask(false,false,false,false)) is restored exactly.
        int prevColorMaskBits = GL43C.glGetInteger(GL43C.GL_COLOR_WRITEMASK);
        if (prevBlend) GL43C.glDisable(GL43C.GL_BLEND);
        if (prevScissor) GL43C.glDisable(GL43C.GL_SCISSOR_TEST);
        if (prevCull) GL43C.glDisable(GL43C.GL_CULL_FACE);
        if (prevDepth) GL43C.glDisable(GL43C.GL_DEPTH_TEST);
        if (prevDepthMask) GL43C.glDepthMask(false);
        GL43C.glColorMask(true, true, true, true);
        try {
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                GL43C.GL_TEXTURE_2D, dstTex, 0);
            GL43C.glViewport(0, 0, dstW, dstH);
            GL43C.glUseProgram(easuProg);
            GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, srcTex);
            GL43C.glUniform1i(easuUInput, 0);
            GL43C.glUniform2i(easuUInSize, srcW, srcH);
            GL43C.glUniform2i(easuUOutSize, dstW, dstH);
            GL43C.glBindVertexArray(easuVao);
            clearGlErrors();
            GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, 3);
            boolean easuErrored = drainGlErrors("drawEasu", 1);
            if (easuErrored) disableEasu();
            GL43C.glBindVertexArray(0);
            GL43C.glUseProgram(0);
        } finally {
            if (prevBlend) GL43C.glEnable(GL43C.GL_BLEND);
            if (prevScissor) GL43C.glEnable(GL43C.GL_SCISSOR_TEST);
            if (prevCull) GL43C.glEnable(GL43C.GL_CULL_FACE);
            if (prevDepth) GL43C.glEnable(GL43C.GL_DEPTH_TEST);
            if (prevDepthMask) GL43C.glDepthMask(true);
            GL43C.glColorMask((prevColorMaskBits & 1) != 0, (prevColorMaskBits & 2) != 0,
                (prevColorMaskBits & 4) != 0, (prevColorMaskBits & 8) != 0);
        }
    }

    /**
     * 8-arg overload used by FSRProcessor. When {@code useEasu} is true (the
     * "EASU" toggle / config.fsr1Easu), the upscale is done with the edge-adaptive
     * EASU kernel — this is what smooths diagonal edges at low render scales.
     * When false, falls back to the cheaper fixed-function bilinear blit + RCAS.
     */
    public boolean dispatch(int colorTex, int srcW, int srcH, int dstW, int dstH,
                           float sharpness, int outputTex, boolean useEasu) {
        if (!useEasu || easuErrorsDetected) {
            // EASU disabled by config OR permanently disabled after a GL error
            // (external mod GL-state conflict) — fall back to bilinear + RCAS.
            return dispatch(colorTex, srcW, srcH, dstW, dstH, sharpness, outputTex);
        }
        if (dstW <= 0 || dstH <= 0) return false;
        if (!ensureEasuShader()) {
            // EASU unavailable (shader compile failure) — fall back to bilinear + RCAS.
            return dispatch(colorTex, srcW, srcH, dstW, dstH, sharpness, outputTex);
        }

        int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawFbo == 0) drawFbo = GL43C.glGenFramebuffers();

        boolean rcasAvailable = sharpness >= 0.001f;
        if (rcasAvailable) {
            ensureTextures(dstW, dstH);
            // Pass 1: EASU edge-adaptive upscale into the intermediate texture.
            drawEasu(colorTex, srcW, srcH, intermediateTex, dstW, dstH);
            // CRITICAL: detach intermediateTex from the draw framebuffer BEFORE
            // RCAS samples it. intermediateTex is still attached to drawFbo from
            // drawEasu — sampling a texture that is attached to the CURRENT draw
            // framebuffer is a feedback loop → GL_INVALID_OPERATION (0x0502) on
            // strict drivers (AMD). This was the per-frame GL error in the modded
            // client (Sodium etc. leave stricter state; bare gradle passed).
            GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, 0);
            // Make the FBO color write visible to RCAS's texelFetch read.
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
            // Pass 2: RCAS sharpen into OUR RGBA8 texture, then a 1:1 fixed-
            // function blit to the caller's output texture (format-agnostic,
            // feedback-proof — see rcasOutTex doc).
            dispatchRcas(intermediateTex, rcasOutTex, dstW, dstH, sharpness);
            blitTex(rcasOutTex, outputTex, dstW, dstH, dstW, dstH, GL43C.GL_NEAREST);
        } else {
            // No sharpen — EASU straight to the output texture.
            drawEasu(colorTex, srcW, srcH, outputTex, dstW, dstH);
        }

        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        return true;
    }

    /**
     * Dispatch FSR1 upscale: colorTex (srcW×srcH) → outputTex (dstW×dstH).
     *
     * Uses glBlitFramebuffer(GL_LINEAR) for the upscale (fixed-function bilinear,
     * zero ALU cost) and RCAS compute for sharpening. The EASU variant has been
     * removed — it was 2.4x more memory traffic with no quality benefit on
     * Minecraft's blocky textures.
     *
     * @param colorTex   source GL texture ID (low-res render target)
     * @param srcW       source width
     * @param srcH       source height
     * @param dstW       destination width (display res)
     * @param dstH       destination height
     * @param sharpness  RCAS sharpness [0..1], 0 = no sharpen
     * @param outputTex  destination GL texture ID (main render target)
     * @return true on success
     */
    public boolean dispatch(int colorTex, int srcW, int srcH, int dstW, int dstH,
                           float sharpness, int outputTex) {
        if (dstW <= 0 || dstH <= 0) return false;
        if (!ensureShaders()) return false;

        int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);

        if (readFbo == 0) readFbo = GL43C.glGenFramebuffers();
        if (drawFbo == 0) drawFbo = GL43C.glGenFramebuffers();

        boolean rcasAvailable = sharpness >= 0.001f;

        if (rcasAvailable) {
            ensureTextures(dstW, dstH);

            // Pass 1: bilinear blit upscale (fixed-function, zero ALU)
            blitTex(colorTex, intermediateTex, srcW, srcH, dstW, dstH, GL43C.GL_LINEAR);

            // CRITICAL: detach intermediateTex from the draw framebuffer BEFORE
            // RCAS samples it (feedback-loop GL_INVALID_OPERATION on AMD).
            GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, 0);

            // Pass 2: RCAS sharpen (compute, 1 texelFetch/pixel via shared tile)
            // into OUR RGBA8 texture, then a 1:1 fixed-function blit to the
            // caller's output texture (format-agnostic, feedback-proof).
            dispatchRcas(intermediateTex, rcasOutTex, dstW, dstH, sharpness);
            blitTex(rcasOutTex, outputTex, dstW, dstH, dstW, dstH, GL43C.GL_NEAREST);
        } else {
            // No sharpen — single bilinear blit directly to output
            blitTex(colorTex, outputTex, srcW, srcH, dstW, dstH, GL43C.GL_LINEAR);
        }

        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);

        return true;
    }

    public void destroy() {
        if (rcasProg != 0) { GL43C.glDeleteProgram(rcasProg); rcasProg = 0; }
        if (easuProg != 0) { GL43C.glDeleteProgram(easuProg); easuProg = 0; }
        if (easuVao != 0) { GL43C.glDeleteVertexArrays(easuVao); easuVao = 0; }
        easuCompiled = false;
        if (readFbo != 0) { GL43C.glDeleteFramebuffers(readFbo); readFbo = 0; }
        if (drawFbo != 0) { GL43C.glDeleteFramebuffers(drawFbo); drawFbo = 0; }
        if (intermediateTex != 0) { GL43C.glDeleteTextures(intermediateTex); intermediateTex = 0; }
        if (rcasOutTex != 0) { GL43C.glDeleteTextures(rcasOutTex); rcasOutTex = 0; }
        compiled = false;
    }

    public boolean isReady() {
        return true;
    }
}
