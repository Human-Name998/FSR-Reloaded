package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import java.nio.ByteBuffer;

public class FSR2Pipeline {

    // -------------------------------------------------------------------------
    // Pass 0: Motion vector generation from depth + prev/curr VP matrices
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Pass 1: Reconstruct previous depth + dilate motion vectors
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Pass 2: Depth clip + reactive mask preprocessing
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Pass 3: Lock detection (thin feature confidence)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Pass 4: Accumulate (main temporal upscale with Lanczos)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Pass 5: RCAS sharpen (from FSR1)
    // -------------------------------------------------------------------------

    // Program IDs
    private int mvGenProg;
    private int reconstructProg;
    private int depthClipProg;
    private int lockProg;
    private int accumulateProg;
    private int rcasProg;

    // Internal texture IDs
    private int motionVectorTex;
    private int reconstructedPrevDepthTex;
    private int dilatedDataTex;
    private int dilatedReactiveMasksTex;
    private int previousDilatedDataTex;

    private int preparedInputColorTex;
    private int lockStatusHistoryTex;
    private int lockStatusCurrTex;
    private int newLocksTex;
    private int internalUpscaledTex;
    private int internalUpscaledWriteTex;
    private int upscaledOutputTex;
    private int externalOutputTex = -1;
    private int clearMvFbo;

    private int renderWidth, renderHeight, displayWidth, displayHeight;
    private boolean compiled;
    private int lastOutputTex;

		    // Uniform locations per pass
		    private int mv_uRenderSize, mv_uInvCurrVP, mv_uPrevVP, mv_uJitter, mv_uPrevJitter, mv_uFarDepth, mv_uDeviceToViewDepth, mv_uFarViewZ, mv_uUseDepth;
		    private int mv_uCameraDelta, mv_uZZeroToOne, mv_uFlipY;

    private int rec_uRenderSize, rec_uDisplaySize, rec_uMaxRenderSize;
    private int rec_uDeviceToViewDepth, rec_uJitter, rec_uMotionVectorScale;
    private int rec_uDownscaleFactor, rec_uMotionVectorJitterCancellation;
    private int rec_uPreExposure, rec_uPreviousFramePreExposure;
    private int rec_uViewSpaceToMetersFactor, rec_uFrameIndex, rec_uFarDepth;

    private int dc_uRenderSize, dc_uDisplaySize, dc_uMaxRenderSize;
    private int dc_uDeviceToViewDepth, dc_uJitter, dc_uMotionVectorScale;
    private int dc_uDownscaleFactor, dc_uMotionVectorJitterCancellation;
    private int dc_uPreExposure, dc_uPreviousFramePreExposure;
    private int dc_uViewSpaceToMetersFactor, dc_uExposureVal, dc_uFrameIndex, dc_uFarDepth, dc_uFarViewZ, dc_uUseDepth;

    private int lk_uRenderSize, lk_uDisplaySize, lk_uDownscaleFactor, lk_uJitter;

    private int ac_uRenderSize, ac_uDisplaySize, ac_uMaxRenderSize;
    private int ac_uDeviceToViewDepth, ac_uJitter, ac_uMotionVectorScale;
    private int ac_uDownscaleFactor, ac_uMotionVectorJitterCancellation;
    private int ac_uPreExposure, ac_uPreviousFramePreExposure;
	    private int ac_uViewSpaceToMetersFactor, ac_uExposureVal, ac_uFarDepth, ac_uFarViewZ, ac_uFrameIndex, ac_uUseDepth;
	    private int ac_uCamStill;
	    private int ac_uCamReactive;
	    private int ac_uCamStillExact;
	    private int ac_uDebugMode;

    private int rc_uRcasConfig, rc_uOutputSize;

    // UBO data (precomputed per frame)
    private float[] deviceToViewDepth = new float[4];

    // Reusable matrix uniform buffers (avoid allocation)
    private final float[] matBuf = new float[16];
    private final Matrix4f tmpMat = new Matrix4f();

	    private float farDepth = 1.0f;
	    private float farViewZ;
	    private int currentFrameIndex;	    private int debugMode;
	    private int diagCounter;
	    private int diagFbo = -1;
	    private java.nio.ByteBuffer diagBuf;

	    /**
	     * Log the temporal accumulation weight every 60 frames.
	     * The accumulate pass stores newAccumWeight/15.0 in the lock-status B
	     * channel (see ACCUMULATE_CS). Reading B back and multiplying by 15 gives
	     * the number of temporal frames currently blended into the output:
	     *   0.0       = history discarded every frame (the broken mode — no
	     *               temporal accumulation, looks like a spatial upscale)
	     *   climbing  = healthy accumulation (up to 15 = long temporal filter)
	     * Runs unconditionally (log-only, independent of the debug overlay).
	     */
	    private void logAccumWeight(int lockStatusTex, int historyTex) {
	        try {
	            if (diagFbo == -1) diagFbo = GL43C.glGenFramebuffers();
	            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
	            int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
	            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
	            // Make the accumulate imageStore writes visible to the FBO read.
	            GL43C.glMemoryBarrier(GL43C.GL_ALL_BARRIER_BITS);

	            GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, diagFbo);
	            GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
	                GL43C.GL_TEXTURE_2D, 0, 0);

	            // NATIVE byte order required — GL writes little-endian floats on x86;
	            // a big-endian buffer turns non-zero floats into denormals (~0).
	            if (diagBuf == null || diagBuf.capacity() < 16) {
	                diagBuf = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder());
	            }

	            int cx = displayWidth / 2, cy = displayHeight / 2;
            int[][] pts = {
                {cx, cy},
                {displayWidth / 4, cy}, {3 * displayWidth / 4, cy},
                {cx, displayHeight / 4}, {cx, 3 * displayHeight / 4},
                {cx, (int) (displayHeight * 0.87f)},   // near-bottom — almost certainly terrain
                // Sky-region points added 2026-08-07 for the "blocky clouds while
                // standing still" bug: upper-left/center/right (displayHeight/8
                // and displayHeight/8+... ). Top half when looking up.
                {displayWidth / 4, displayHeight / 8},
                {cx, displayHeight / 8},
                {3 * displayWidth / 4, displayHeight / 8}
            };

	            // Pass 1: lock status RGBA (R=lock life, G=lock luma, B=accumWeight/15, A=throttle: 1.0=isNewSample else max(reactive,depthClip))
	            int[] lockBytes = new int[pts.length * 4];
	            boolean lockOk = false;
	            GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
	                GL43C.GL_TEXTURE_2D, lockStatusTex, 0);
	            GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
	            lockOk = GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE;
	            if (lockOk) {
	                for (int i = 0; i < pts.length; i++) {
	                    diagBuf.clear(); diagBuf.limit(16);
	                    GL43C.glReadPixels(pts[i][0], pts[i][1], 1, 1, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, diagBuf);
	                    diagBuf.rewind();
	                    for (int c = 0; c < 4; c++) lockBytes[i * 4 + c] = diagBuf.get(c) & 0xFF;
	                }
	            }

	            // Pass 2: history color (R11G11B10F) — sky (bright blue) vs terrain disambiguation.
	            // If history reads the scene but lock B is 0 at a terrain pixel, the readback is
	            // broken; if history is all-sky/black, the camera was looking at sky/void.
	            float[] histRgb = new float[pts.length * 3];
	            boolean histOk = false;
	            GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
	                GL43C.GL_TEXTURE_2D, historyTex, 0);
	            GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
	            histOk = GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE;
	            if (histOk) {
	                for (int i = 0; i < pts.length; i++) {
	                    diagBuf.clear(); diagBuf.limit(12);
	                    GL43C.glReadPixels(pts[i][0], pts[i][1], 1, 1, GL43C.GL_RGB, GL43C.GL_FLOAT, diagBuf);
	                    diagBuf.rewind();
	                    for (int c = 0; c < 3; c++) histRgb[i * 3 + c] = diagBuf.getFloat(c * 4);
	                }
	            }
	            // Pass 3: raw MV (RG16F, render px) + reactive mask (RG8) — pin the 0.5
	            // throttle source. Both are RENDER-res; scale the display-res points.
	            float[] mvLen = new float[pts.length];
	            float[] react = new float[pts.length];
	            float rx = renderWidth > 0 ? (float) renderWidth / displayWidth : 0.25f;
	            float ry = renderHeight > 0 ? (float) renderHeight / displayHeight : 0.25f;
	            GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
	                GL43C.GL_TEXTURE_2D, motionVectorTex, 0);
	            GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
	            boolean mvOk = GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE;
	            if (mvOk) {
	                for (int i = 0; i < pts.length; i++) {
	                    diagBuf.clear(); diagBuf.limit(8);
	                    GL43C.glReadPixels((int)(pts[i][0] * rx), (int)(pts[i][1] * ry), 1, 1,
	                        GL43C.GL_RG, GL43C.GL_FLOAT, diagBuf);
	                    diagBuf.rewind();
	                    float mx = diagBuf.getFloat(0);
	                    float my = diagBuf.getFloat(4);
	                    mvLen[i] = (float) Math.sqrt(mx * mx + my * my);
	                }
	            }
	            GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
	                GL43C.GL_TEXTURE_2D, dilatedReactiveMasksTex, 0);
	            GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
	            boolean rOk = GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE;
	            if (rOk) {
	                for (int i = 0; i < pts.length; i++) {
	                    diagBuf.clear(); diagBuf.limit(2);
	                    GL43C.glReadPixels((int)(pts[i][0] * rx), (int)(pts[i][1] * ry), 1, 1,
	                        GL43C.GL_RG, GL43C.GL_UNSIGNED_BYTE, diagBuf);
	                    diagBuf.rewind();
	                    react[i] = (diagBuf.get(0) & 0xFF) / 255.0f;
	                }
	            }
	            GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);

	            StringBuilder sb = new StringBuilder("[ACCUM_WEIGHT] frame=" + currentFrameIndex);
	            for (int i = 0; i < pts.length; i++) {
	                float b = (lockBytes[i * 4 + 2]) / 255.0f;   // B = newAccumWeight/15
	                sb.append(String.format(" (%d,%d) L=(%d,%d,%.2f,%d) H=(%.2f,%.2f,%.2f) M=(%.2f,%.2f)",
	                    pts[i][0], pts[i][1],
	                    lockBytes[i * 4], lockBytes[i * 4 + 1], b * 15.0f, lockBytes[i * 4 + 3],
	                    histRgb[i * 3], histRgb[i * 3 + 1], histRgb[i * 3 + 2],
	                    mvOk ? mvLen[i] : -1.0f, rOk ? react[i] : -1.0f));
	            }
	            FSRMod.LOGGER.info(sb.toString());
	            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
	        } catch (Throwable t) {
	            FSRMod.LOGGER.warn("[ACCUM_WEIGHT] readback failed: {}", t.getMessage());
	        }
	    }

	    public void setDebugMode(int mode) {
	        this.debugMode = mode;
	    }

	    public FSR2Pipeline() {
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
    }

    public void setProjectionMatrix(Matrix4f proj) {
        float p22 = proj.m22();
        float p23 = proj.m23();
        deviceToViewDepth[0] = (1.0f - p22) * 0.5f;
        deviceToViewDepth[1] = -p23 * 0.5f;
        deviceToViewDepth[2] = -1.0f / proj.m00();
        deviceToViewDepth[3] = -1.0f / proj.m11();
        // Detect Reversed-Z depth: standard OpenGL has m22 < 0
        farDepth = (p22 > 0) ? 0.0f : 1.0f;
        // View-space Z at the far plane (used for sky detection)
        farViewZ = deviceToViewDepth[1] / (farDepth - deviceToViewDepth[0] + 1e-10f);
    }

    private int getUniformLoc(int prog, String name) {
        int loc = GL43C.glGetUniformLocation(prog, name);
        if (loc < 0) {
            FSRMod.LOGGER.warn("FSR2 uniform '{}' not found in program {}", name, prog);
        }
        return loc;
    }

    private boolean ensureShaders() {
        if (compiled) return true;

        mvGenProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "MV_GEN_CS"));
        reconstructProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "RECONSTRUCT_CS"));
        depthClipProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "DEPTH_CLIP_CS"));
        lockProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "LOCK_CS"));
        accumulateProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "ACCUMULATE_CS"));
        rcasProg = ShaderBinaryCache.compileCompute(ShaderSourceManager.get("fsr2", "RCAS_CS"));

        if (mvGenProg == 0 || reconstructProg == 0 || depthClipProg == 0 ||
            lockProg == 0 || accumulateProg == 0 || rcasProg == 0) {
            FSRMod.LOGGER.error("FSR2: shader compilation failed");
            return false;
        }

        // MV Gen uniforms
        mv_uRenderSize = getUniformLoc(mvGenProg, "uRenderSize");
        mv_uInvCurrVP = getUniformLoc(mvGenProg, "uInvCurrVP");
        mv_uPrevVP = getUniformLoc(mvGenProg, "uPrevVP");
        mv_uJitter = getUniformLoc(mvGenProg, "uJitter");
        mv_uPrevJitter = getUniformLoc(mvGenProg, "uPrevJitter");
	        mv_uFarDepth = getUniformLoc(mvGenProg, "uFarDepth");
	        mv_uDeviceToViewDepth = getUniformLoc(mvGenProg, "uDeviceToViewDepth");
	        mv_uFarViewZ = getUniformLoc(mvGenProg, "uFarViewZ");
		        mv_uUseDepth = getUniformLoc(mvGenProg, "uUseDepth");
		        mv_uCameraDelta = getUniformLoc(mvGenProg, "uCameraDelta");
		        mv_uZZeroToOne = getUniformLoc(mvGenProg, "uZZeroToOne");
		        mv_uFlipY = getUniformLoc(mvGenProg, "uFlipY");

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
        dc_uFarViewZ = getUniformLoc(depthClipProg, "uFarViewZ");
        dc_uUseDepth = getUniformLoc(depthClipProg, "uUseDepth");

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
        ac_uUseDepth = getUniformLoc(accumulateProg, "uUseDepth");
	        ac_uCamStill = getUniformLoc(accumulateProg, "uCamStill");
	        ac_uCamReactive = getUniformLoc(accumulateProg, "uCamReactive");
	        ac_uCamStillExact = getUniformLoc(accumulateProg, "uCamStillExact");
	        ac_uDebugMode = getUniformLoc(accumulateProg, "uDebugMode");

        // RCAS uniforms
        rc_uRcasConfig = getUniformLoc(rcasProg, "uRcasConfig");
        rc_uOutputSize = getUniformLoc(rcasProg, "uOutputSize");

        compiled = true;
        FSRMod.LOGGER.info("FSR2: All 6 shaders compiled (MV gen + 5 passes)");
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
            preparedInputColorTex, lockStatusCurrTex, lockStatusHistoryTex,
            newLocksTex, internalUpscaledTex, internalUpscaledWriteTex,
            upscaledOutputTex
        };
        for (int t : texs) {
            if (t != 0) GL43C.glDeleteTextures(t);
        }
        motionVectorTex = 0; reconstructedPrevDepthTex = 0; dilatedDataTex = 0;
        dilatedReactiveMasksTex = 0; previousDilatedDataTex = 0;
        preparedInputColorTex = 0; lockStatusCurrTex = 0; lockStatusHistoryTex = 0;
        newLocksTex = 0; internalUpscaledTex = 0; internalUpscaledWriteTex = 0;
        upscaledOutputTex = 0;
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
    // Depth-trust gate (2026-08-06, "everything shaking" fix): set from the
    // shared depth probe result. 0 = RT depth unwritten/cleared (26.1.2 GL) →
    // DEPTH_CLIP + ACCUMULATE skip depth-based sky/disocclusion and rely on
    // MV + color-based temporal accumulation (constant-depth policy).
    private boolean useDepth;
    // Sky/cloud still-camera accumulation gate (2026-08-06, "sky is blocky"
    // fix): sky/cloud pixels are normally forced isNewSample (fresh render every
    // frame -> 4x upscale with zero sub-pixel reconstruction -> blocky clouds/
    // sun at low render scales). When the camera is GENUINELY still, constant-
    // depth MVs are exact, so history aligns and the sky/cloud/sun can accumulate
    // like terrain (smoothing their edges); the box clamp + luma lock protect
    // cloud drift. Set from FSRProcessor's jitter-block camera stillness signal.
    private boolean camStill;
    private float camReactive;
    private boolean camStillExact;

    public void setCamStill(boolean camStill) {
        this.camStill = camStill;
    }

    public void setCamReactive(float camReactive) {
        this.camReactive = camReactive;
    }

    public void setCamStillExact(boolean camStillExact) {
        this.camStillExact = camStillExact;
    }

    public void setUseDepth(boolean useDepth) {
        this.useDepth = useDepth;
    }

    public boolean dispatch(int colorTex, int depthTex,
                            float jitterX, float jitterY,
	                            float prevJitterX, float prevJitterY,
	                            float downscaleX, float downscaleY,
	                            float motionVecScaleX, float motionVecScaleY,
	                            Matrix4f projection,
	                            int frameIndex,
	                            float sharpness,
	                            Matrix4f prevViewProj,
	                            Matrix4f currViewProj,
	                            float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ) {
        if (!ensureShaders()) return false;
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return false;
        RenderSystem.assertOnRenderThread();

        setProjectionMatrix(projection);
        currentFrameIndex = frameIndex;

        ensureTextures();

        // Save minimal GL state: only what we actually touch
        int prevProg = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM);
        int prevActive = GL43C.glGetInteger(GL43C.GL_ACTIVE_TEXTURE);
        int[] prevTex = new int[6];
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
	            GL43C.glUniform4fv(mv_uDeviceToViewDepth, deviceToViewDepth);
	            GL43C.glUniform1f(mv_uFarViewZ, farViewZ);
	            // 0 = constant ndcZ (mid-depth). The Minecraft 26.1.2 RenderTarget depth
	            // texture reads 0/garbage everywhere (confirmed by [DEBUG_DEPTH]/
	            // [SHARE_VERIFY] in latest.log), so per-pixel depth would map every
	            // pixel to ndcZ=-1 (near plane) → exaggerated MVs while walking →
	            // reprojUv leaves the screen → history discarded every frame (no
	            // temporal accumulation). Constant mid-depth ndcZ=0 is the proven
	            // working setting: rotation MVs are exact, translation MVs use the
	            // scene-average parallax.
	            GL43C.glUniform1i(mv_uUseDepth, 0);
	            GL43C.glUniform3f(mv_uCameraDelta, cameraDeltaX, cameraDeltaY, cameraDeltaZ);
	            GL43C.glUniform1i(mv_uZZeroToOne, 0);  // 0 = OpenGL NDC [-1,1]
	            GL43C.glUniform1i(mv_uFlipY, 0);       // 0 = OpenGL (no Y flip)

	            GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
            GL43C.glBindImageTexture(1, motionVectorTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);

            GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        } else {
            // Fallback: clear motion vectors to zero
            if (clearMvFbo == 0) {
                clearMvFbo = GL43C.glGenFramebuffers();
            }
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, clearMvFbo);
            GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, motionVectorTex, 0);
            float[] mvZero = {0f, 0f, 0f, 0f};
            GL43C.glClearBufferfv(GL43C.GL_COLOR, 0, mvZero);
            GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        }

	        // Jitter delta cancellation (2026-08-06, "everything shaking" fix):
	        // MV-gen uses the JITTERED curr/prev matrices (lastProjJittered), so the
	        // computed MVs contain the per-frame jitter delta (J_prev - J_curr) in
	        // render px. ACCUMULATE de-jitters the current frame separately, so MVs
	        // must carry pure content motion — subtract the delta (render-UV units,
	        // matching uDilatedData / uMotionVectorScale). Uncorrected, the delta is
	        // up to ~2.2 display px of per-frame reprojection wobble at 45% scale →
	        // the ellipsoid clamp rejects misaligned history → raw jittered input
	        // shows through as standing-still shake.
	        float jitterCancelX = (prevJitterX - jitterX) * motionVecScaleX;
	        float jitterCancelY = (prevJitterY - jitterY) * motionVecScaleY;

        // FIX: Clear reconstructedPrevDepthTex (R32UI) before Reconstruct pass.
        // Reconstruct uses imageAtomicMax — without clearing, stale depth values
        // from previous frames persist and only grow, corrupting the depth-clip
        // test in Pass 2. Single glClearTexImage (GL 4.4) replaces the FBO
        // bind/attach/clear/unbind dance — fewer driver calls, no FBO state churn.
        GL44C.glClearTexImage(reconstructedPrevDepthTex, 0, GL43C.GL_RED_INTEGER, GL43C.GL_UNSIGNED_INT, (ByteBuffer) null);

        // ---- PASS 1: Reconstruct & Dilate ----
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

        // ---- PASS 2: Depth Clip ----
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
        GL43C.glUniform1f(dc_uFarViewZ, farViewZ);
        GL43C.glUniform1i(dc_uUseDepth, useDepth ? 1 : 0);

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
        GL43C.glBindImageTexture(10, preparedInputColorTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA16F);
        GL43C.glBindImageTexture(11, dilatedReactiveMasksTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

        GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // Clear newLocksTex before LOCK pass
        GL43C.glUseProgram(0);
        GL44C.glClearTexImage(newLocksTex, 0, GL43C.GL_RED, GL43C.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        // ---- PASS 3: Lock ----
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

        // ---- PASS 4: Accumulate ----
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
	        GL43C.glUniform1i(ac_uDebugMode, debugMode);
	        GL43C.glUniform1i(ac_uUseDepth, useDepth ? 1 : 0);
	        GL43C.glUniform1i(ac_uCamStill, camStill ? 1 : 0);
	        GL43C.glUniform1f(ac_uCamReactive, camReactive);
	        GL43C.glUniform1i(ac_uCamStillExact, camStillExact ? 1 : 0);

	        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, colorTex);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
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
        GL43C.glActiveTexture(GL43C.GL_TEXTURE9);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, newLocksTex);

        GL43C.glBindImageTexture(10, internalUpscaledWriteTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R11F_G11F_B10F);
        GL43C.glBindImageTexture(11, lockStatusCurrTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
        GL43C.glBindImageTexture(14, externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);

        GL43C.glDispatchCompute((displayWidth + 31) / 32, displayHeight, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // ---- PASS 5: RCAS in-place (writes directly to upscaledOutputTex) ----
        int rcasSharpness = Math.round(Math.clamp(sharpness, 0.0f, 1.0f) * 7.0f);
        if (rcasSharpness > 0) {
            GL43C.glUseProgram(rcasProg);
            GL43C.glUniform4ui(rc_uRcasConfig, rcasSharpness, 0, 0, 0);
            GL43C.glUniform2i(rc_uOutputSize, displayWidth, displayHeight);

            GL43C.glBindImageTexture(0, externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);

            GL43C.glDispatchCompute((displayWidth + 31) / 32, displayHeight, 1);

            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
            GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
        }
        lastOutputTex = externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex;

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

        // Debug: log the temporal accumulation weight (lock B channel) every 60 frames.
        // GATED on debugMode — the readback is a full glMemoryBarrier(GL_ALL_BARRIER_BITS)
        // + 27 glReadPixels across 3 FBO passes, each a render-thread stall until the GPU
        // drains. Running it unconditionally caused a visible hitch every second on every
        // FSR2/3 session (the "anything above FSR1 loses frames" report at 3440x1440).
        if (debugMode > 0 && diagCounter++ % 60 == 0) {
            logAccumWeight(lockStatusHistoryTex, internalUpscaledTex);  // after ping-pong = the texture accumulate just wrote
        }

        // Restore minimal GL state
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

    public void setExternalOutputTexture(int glTexId) {
        this.externalOutputTex = glTexId;
    }

    public int getOutputTexture() {
        return lastOutputTex;
    }

    public void destroy() {
        if (mvGenProg != 0) { GL43C.glDeleteProgram(mvGenProg); mvGenProg = 0; }
        if (reconstructProg != 0) { GL43C.glDeleteProgram(reconstructProg); reconstructProg = 0; }
        if (depthClipProg != 0) { GL43C.glDeleteProgram(depthClipProg); depthClipProg = 0; }
        if (lockProg != 0) { GL43C.glDeleteProgram(lockProg); lockProg = 0; }
        if (accumulateProg != 0) { GL43C.glDeleteProgram(accumulateProg); accumulateProg = 0; }
        if (rcasProg != 0) { GL43C.glDeleteProgram(rcasProg); rcasProg = 0; }
        deleteTextures();
        if (clearMvFbo != 0) { GL43C.glDeleteFramebuffers(clearMvFbo); clearMvFbo = 0; }
        compiled = false;
    }

    public boolean isReady() {
        return compiled;
    }
}
