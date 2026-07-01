package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import org.lwjgl.opengl.GL43C;

/**
 * FSR1 spatial upscaler — glBlitFramebuffer upscale + RCAS compute sharpen.
 *
 * Upscale via fixed-function glBlitFramebuffer (GL_LINEAR) avoids compute-unit
 * contention with Voxy LOD generation and eliminates shared-memory block artifacts.
 * RCAS runs as a separate 32x1 compute shader with no shared memory.
 */
public class FSR1Pipeline {

    private static final String RCAS_CS = """
        #version 430 core
        layout(local_size_x = 32, local_size_y = 1) in;
        layout(binding = 0, rgba8) uniform image2D uImg;
        uniform float uSharpness;
        uniform ivec2 uOutputSize;
        void main() {
            ivec2 ip = ivec2(gl_GlobalInvocationID.xy);
            if (ip.x >= uOutputSize.x || ip.y >= uOutputSize.y) return;
            if (uSharpness < 0.001) return;
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
            float sharp = float(int(uSharpness * 7.0 + 0.5)) / 8.0;
            vec3 w = clamp(1.0 - sharp * d / dMax, 0.0, 1.0);
            vec3 sharpened = (c * 4.0 + t + b + l + r) * (1.0 / 8.0);
            vec3 result = c + w * (sharpened - c);
            imageStore(uImg, ip, vec4(clamp(result, 0.0, 1.0), 1.0));
        }
        """;

    private int rcasProg;
    private boolean compiled;
    private int rc_uSharpness, rc_uOutputSize;

    private int readFbo;
    private int drawFbo;

    public void setup(int rw, int rh, int dw, int dh) {
    }

    private boolean ensureShaders() {
        if (compiled) return true;

        rcasProg = ShaderBinaryCache.compileCompute(RCAS_CS);
        if (rcasProg == 0) return false;

        rc_uSharpness = GL43C.glGetUniformLocation(rcasProg, "uSharpness");
        rc_uOutputSize = GL43C.glGetUniformLocation(rcasProg, "uOutputSize");

        compiled = true;
        FSRMod.LOGGER.info("FSR1: glBlitFramebuffer + RCAS compute shader compiled");
        return true;
    }

    public boolean dispatch(int colorTex, int srcW, int srcH, int dstW, int dstH, float sharpness, int outputTex) {
        if (!ensureShaders()) return false;
        if (dstW <= 0 || dstH <= 0) return false;

        int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] prevViewport = new int[4];
        GL43C.glGetIntegerv(GL43C.GL_VIEWPORT, prevViewport);

        if (readFbo == 0) readFbo = GL43C.glGenFramebuffers();
        if (drawFbo == 0) drawFbo = GL43C.glGenFramebuffers();

        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, readFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, colorTex, 0);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, outputTex, 0);

        GL43C.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH,
            GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_LINEAR);

        if (sharpness >= 0.001f) {
            int prevProg = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM);

            GL43C.glUseProgram(rcasProg);
            GL43C.glUniform1f(rc_uSharpness, Math.clamp(sharpness, 0.0f, 1.0f));
            GL43C.glUniform2i(rc_uOutputSize, dstW, dstH);
            GL43C.glBindImageTexture(0, outputTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
            GL43C.glDispatchCompute((dstW + 31) / 32, dstH, 1);
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
            GL43C.glUseProgram(prevProg);
        }

        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GL43C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        return true;
    }

    public void destroy() {
        if (rcasProg != 0) { GL43C.glDeleteProgram(rcasProg); rcasProg = 0; }
        if (readFbo != 0) { GL43C.glDeleteFramebuffers(readFbo); readFbo = 0; }
        if (drawFbo != 0) { GL43C.glDeleteFramebuffers(drawFbo); drawFbo = 0; }
        compiled = false;
    }

    public boolean isReady() {
        return compiled;
    }
}
