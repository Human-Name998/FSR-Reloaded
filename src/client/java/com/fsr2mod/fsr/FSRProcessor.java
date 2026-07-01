package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.IrisCompat;
import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.mixin.MinecraftClientAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL43C;

public class FSRProcessor {

    // Diagnostic timers
    private long t0Frame, t0Begin, t0Scene, t0End;
    private int timingFrameCount;
    private void logSlow(String label, long dtNs) {
        long ms = dtNs / 1_000_000;
        if (ms >= 8) {
            FSRMod.LOGGER.warn("[TIMING] {} took {}ms (frame #{})", label, ms, timingFrameCount);
        }
    }

    private static Boolean isIntelGpu;

    private static boolean isIntelGpu() {
        if (isIntelGpu == null) {
            try {
                String renderer = GL43C.glGetString(GL43C.GL_RENDERER);
                isIntelGpu = renderer != null && renderer.toLowerCase().contains("intel");
                if (isIntelGpu) {
                    FSRMod.LOGGER.info("Intel GPU detected — FSR2/FSR3 temporal pipelines disabled, using FSR1");
                }
            } catch (Exception e) {
                isIntelGpu = false;
            }
        }
        return isIntelGpu;
    }

    private static Boolean isMac;

    private static boolean isMac() {
        if (isMac == null) {
            String os = System.getProperty("os.name");
            isMac = os != null && os.toLowerCase().contains("mac");
            if (isMac) {
                FSRMod.LOGGER.warn("macOS detected — OpenGL 4.1 only, no compute shader support. FSR Reloaded disabled.");
            }
        }
        return isMac;
    }

    private final FSRConfig config;
    private final JitterManager jitterManager;
    private FSR2Pipeline fsr2Pipeline;
    private FSR3Pipeline fsr3Pipeline;

    private RenderTarget mainRenderTarget;
    private RenderTarget originalMainRenderTarget;

    private int displayWidth;
    private int displayHeight;
    private int scaledWidth;
    private int scaledHeight;
    private float jitterX;
    private float jitterY;
    private float prevJitterX;
    private float prevJitterY;

    private boolean pipelineReady;
    private int nativeFbWidth;
    private int nativeFbHeight;
    private boolean insideFrame;

    private int fsr2FrameCount;
    private int fsr2OutputTexId;
    private boolean sceneUpscaled;
    private boolean jitterAppliedThisFrame;

    private TextureTarget renderTarget;
    private ScaledRenderTarget proxyTarget;

    private FSR1Pipeline fsr1Pipeline;

    private int rcasUpscaleProg = -1;
    private int rcasUpscaleTex = -1;
    private int rcasUpscaleVao = -1;
    private int rcasUpscaleFbo = -1;
    private int rcasUpscaleTexW;
    private int rcasUpscaleTexH;
    private boolean rcasUpscaleCompiled;

    // Frame generation lifecycle (reserved for future use)

    // Jittered projection matrix (set per-frame from renderLevel projection)
    private final Matrix4f lastProj = new Matrix4f();

    // VP matrix tracking for motion vector generation
    private final Matrix4f currViewProj = new Matrix4f();
    private final Matrix4f prevViewProj = new Matrix4f();
    private boolean hasPrevFrame;

    // Reusable float arrays (avoid heap alloc every frame)
    private final float[] jitterResult = new float[2];

    // Reusable matrix/quaternion for view matrix construction (avoid alloc per frame)
    private final Matrix4f tmpView = new Matrix4f();
    private final Quaternionf tmpQuat = new Quaternionf();

    public FSRProcessor(FSRConfig config) {
        this.config = config;
        this.jitterManager = new JitterManager();
    }

    private boolean isFsr2() {
        return !isIntelGpu() && config.version != null && config.version == FSRConfig.FSRVersion.FSR2;
    }

    private boolean isFsr3() {
        return !isIntelGpu() && config.version != null && (config.version == FSRConfig.FSRVersion.FSR3_UPS || config.version == FSRConfig.FSRVersion.FSR3_FG);
    }

    private boolean isFrameGen() {
        return !isIntelGpu() && config.version != null && config.version == FSRConfig.FSRVersion.FSR3_FG;
    }

    private boolean isTemporalUpscaler() {
        return isFsr2() || isFsr3();
    }

    public void setProjection(Matrix4f proj) {
        lastProj.set(proj);
    }

    private void restoreDefaultTarget() {
        Minecraft client = Minecraft.getInstance();
        if (mainRenderTarget != null) {
            RenderTarget current = client.getMainRenderTarget();
            if (current != mainRenderTarget) {
                ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            }
        }
        insideFrame = false;
    }

    public void ensureCorrectRenderTarget(RenderTarget caller) {
        Minecraft client = Minecraft.getInstance();
        if (renderTarget == null || mainRenderTarget == null) return;
        if (caller == renderTarget && caller != mainRenderTarget) {
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
        }
    }

    public void onRenderBegin(int windowWidth, int windowHeight) {
        jitterAppliedThisFrame = false;
        t0Frame = System.nanoTime();
        timingFrameCount++;

        if (!config.enabled || isMac()) {
            restoreDefaultTarget();
            return;
        }
        if (windowWidth <= 0 || windowHeight <= 0) {
            restoreDefaultTarget();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.screen != null || client.level == null) {
            restoreDefaultTarget();
            return;
        }
        RenderSystem.assertOnRenderThread();

        if (insideFrame) {
            restoreDefaultTarget();
            return;
        }
        insideFrame = true;
        jitterAppliedThisFrame = false;
        t0Begin = System.nanoTime();

        displayWidth = windowWidth;
        displayHeight = windowHeight;

        float scale;
        boolean irisActive = IrisCompat.isShaderPackInUse();
        if (irisActive) {
            scale = config.getIrisScale();
        } else {
            scale = config.getScale();
        }
        scaledWidth = Math.max(16, Math.round(windowWidth * scale));
        scaledHeight = Math.max(16, Math.round(windowHeight * scale));

        // Iris path: no mainRT swap. Iris creates its own G-buffers and manages
        // its own render targets internally. FSR1 post-process upscale or RCAS
        // sharpen is applied in onSceneRendered if an external render-scale mod
        // (e.g. RenderScale) reduced mainRT resolution.
        if (irisActive) {
            com.fsr2mod.compat.RenderScaleCompat.logStatus();
            originalMainRenderTarget = client.getMainRenderTarget();
            mainRenderTarget = originalMainRenderTarget;
            jitterX = 0.0f;
            jitterY = 0.0f;
            return;
        }

        // Non-Iris path: use ScaledRenderTarget proxy so Voxy and other mods
        // see native dimensions while rendering goes to scaled textures.
        originalMainRenderTarget = client.getMainRenderTarget();
        mainRenderTarget = originalMainRenderTarget;

        if (renderTarget == null || renderTarget.width != scaledWidth || renderTarget.height != scaledHeight) {
            if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
            renderTarget = new TextureTarget("fsr-scene", scaledWidth, scaledHeight, true);
        }

        proxyTarget = new ScaledRenderTarget(
            displayWidth, displayHeight,
            renderTarget.getColorTexture(),
            renderTarget.getColorTextureView(),
            renderTarget.getDepthTexture(),
            renderTarget.getDepthTextureView()
        );

        ((MinecraftClientAccessor) client).setMainRenderTarget(proxyTarget);

        // Set viewport to scaled resolution so the 3D scene renders at lower res
        // (proxy reports native dims for Voxy but viewport controls actual pixel count)
        if (!irisActive && scaledWidth > 0 && scaledHeight > 0) {
            GL43C.glViewport(0, 0, scaledWidth, scaledHeight);
        }

        if (isTemporalUpscaler()) {
            prevJitterX = jitterX;
            prevJitterY = jitterY;
            jitterManager.next(scaledWidth, displayWidth, jitterResult);
            jitterX = jitterResult[0];
            jitterY = jitterResult[1];

            if (timingFrameCount % 60 == 0) {
                FSRMod.LOGGER.info("[DIAG] Frame {}: jitter=({},{}) prev=({},{}) scale={} render={}x{} display={}x{} frameIdx={}",
                    timingFrameCount, jitterX, jitterY, prevJitterX, prevJitterY, scale,
                    scaledWidth, scaledHeight, displayWidth, displayHeight, fsr2FrameCount);
            }

            if (!pipelineReady) {
                if (isFsr2()) {
                    if (fsr2Pipeline == null) fsr2Pipeline = new FSR2Pipeline();
                    fsr2Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
                } else if (isFsr3()) {
                    if (fsr3Pipeline == null) fsr3Pipeline = new FSR3Pipeline();
                    fsr3Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
                }
                pipelineReady = true;
            }
        } else {
            jitterX = 0.0f;
            jitterY = 0.0f;
        }

        logSlow("onRenderBegin (total)", System.nanoTime() - t0Begin);
    }

    public void applyJitterToMatrix(Matrix4f proj) {
        if (!isTemporalUpscaler()) return;
        if (jitterAppliedThisFrame) {
            FSRMod.LOGGER.warn("[DIAG] Double jitter prevented: jitterX={} jitterY={} frame={}", jitterX, jitterY, timingFrameCount);
            return;
        }
        jitterAppliedThisFrame = true;
        if (scaledWidth < 16 || scaledHeight < 16) {
            FSRMod.LOGGER.warn("[DIAG] Bad scaled dims {}x{} on frame {} (display {}x{})", scaledWidth, scaledHeight, timingFrameCount, displayWidth, displayHeight);
            return;
        }
        float px = jitterX * 2.0f / scaledWidth;
        float py = jitterY * 2.0f / scaledHeight;
        proj.m02(proj.m02() + px);
        proj.m12(proj.m12() + py);
    }

    /**
     * Called after 3D level rendering completes but before GUI rendering.
     * Runs the FSR upscale and switches mainRT to full resolution so GUI
     * renders at native resolution (not upscaled).
     */
    public void onSceneRendered() {
        t0Scene = System.nanoTime();
        if (!insideFrame) return;
        if (displayWidth <= 0 || displayHeight <= 0) return;
        if (sceneUpscaled) return;

        RenderSystem.assertOnRenderThread();
        Minecraft client = Minecraft.getInstance();

        boolean irisActive = IrisCompat.isShaderPackInUse();

        if (irisActive) {
            // Iris path: no mainRT swap, scene rendered at native resolution.
            // If an external render-scale mod (RenderScale, etc.) shrank mainRT,
            // FSR1 upscales it back to native. Otherwise just RCAS sharpen.
            RenderTarget actualRT = client.getMainRenderTarget();
            int actualW = actualRT != null ? actualRT.width : 0;
            int actualH = actualRT != null ? actualRT.height : 0;
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

            if (actualW > 0 && actualH > 0 && (actualW < displayWidth || actualH < displayHeight)) {
                // External render-scale mod reduced resolution — FSR1 upscale + RCAS
                int srcTex = actualRT.getColorTexture() != null
                    ? ((GlTexture)actualRT.getColorTexture()).glId() : -1;
                int mainTex = mainRenderTarget != null && mainRenderTarget.getColorTexture() != null
                    ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : -1;
                if (srcTex != -1 && mainTex != -1) {
                    if (fsr1Pipeline == null) {
                        FSRMod.LOGGER.info("FSR1: Initializing pipeline (Iris path)");
                        fsr1Pipeline = new FSR1Pipeline();
                    }
                    fsr1Pipeline.dispatch(srcTex, actualW, actualH,
                        displayWidth, displayHeight, config.sharpness, mainTex);
                }
            } else if (config.sharpness > 0.0f && mainRenderTarget != null) {
                // RCAS sharpen only (same src/dst size = 1:1 blit + RCAS)
                int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
                if (fsr1Pipeline == null) {
                    FSRMod.LOGGER.info("FSR1: Initializing pipeline (Iris RCAS only)");
                    fsr1Pipeline = new FSR1Pipeline();
                }
                fsr1Pipeline.dispatch(mainTex, displayWidth, displayHeight,
                    displayWidth, displayHeight, config.sharpness, mainTex);
            }

            sceneUpscaled = true;
            logSlow("onSceneRendered (Iris total)", System.nanoTime() - t0Scene);
            return;
        }

        // --- Non-Iris path ---
        if (renderTarget == null) return;

        // Restore viewport to display resolution (was scaled for 3D rendering)
        GL43C.glViewport(0, 0, displayWidth, displayHeight);

        int renderTex = ((GlTexture)renderTarget.getColorTexture()).glId();
        int depthTex = renderTarget.getDepthTexture() != null
            ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

        ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

        fsr2OutputTexId = 0;

        if (config.version == FSRConfig.FSRVersion.FSR1) {
            if (renderTex == -1) return;
            if (fsr1Pipeline == null) {
                FSRMod.LOGGER.info("FSR1: Initializing pipeline");
                fsr1Pipeline = new FSR1Pipeline();
            }
            int mainTex = mainRenderTarget != null
                ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
            if (mainTex == 0) return;

            try {
                long t0 = System.nanoTime();
                boolean ok = fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight,
                    displayWidth, displayHeight, config.sharpness, mainTex);
                logSlow("FSR1 dispatch", System.nanoTime() - t0);
                if (!ok) {
                    FSRMod.LOGGER.warn("FSR1 dispatch returned false");
                    return;
                }
                fsr2OutputTexId = 0;
            } catch (Exception e) {
                FSRMod.LOGGER.error("FSR1 dispatch threw exception", e);
                return;
            }
            sceneUpscaled = true;
            logSlow("onSceneRendered (FSR1 total)", System.nanoTime() - t0Scene);
            return;
        }

        if (isFsr2()) {
            if (!pipelineReady || fsr2Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                FSRMod.LOGGER.info("[DIAG] FSR2 dispatch frameIdx={} jitterX={} jitterY={} prevX={} prevY={} hasPrev={}",
                    fsr2FrameCount, Math.round(jitterX*1000)/1000f, Math.round(jitterY*1000)/1000f,
                    Math.round(prevJitterX*1000)/1000f, Math.round(prevJitterY*1000)/1000f, hasPrevFrame);
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
                .rotation(tmpQuat)
                .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProj);
            currViewProj.mul(tmpView);

            try {
                long t0 = System.nanoTime();
                boolean success = fsr2Pipeline.dispatch(
                    renderTex, depthTex,
                    jitterX, jitterY, prevJitterX, prevJitterY,
                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
                    1.0f / scaledWidth, 1.0f / scaledHeight,
                    lastProj, fsr2FrameCount,
                    config.sharpness,
                    hasPrevFrame ? prevViewProj : null,
                    currViewProj
                );
                if (success) {
                    fsr2OutputTexId = fsr2Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                    sceneUpscaled = true;
                    return;
                }
                logSlow("FSR2 dispatch", System.nanoTime() - t0);
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
                sceneUpscaled = true;
                return;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (isFsr3()) {
            if (!pipelineReady || fsr3Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                FSRMod.LOGGER.info("[DIAG] FSR3 dispatch frameIdx={} jitterX={} jitterY={} prevX={} prevY={} hasPrev={}",
                    fsr2FrameCount, Math.round(jitterX*1000)/1000f, Math.round(jitterY*1000)/1000f,
                    Math.round(prevJitterX*1000)/1000f, Math.round(prevJitterY*1000)/1000f, hasPrevFrame);
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
                .rotation(tmpQuat)
                .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProj);
            currViewProj.mul(tmpView);

            try {
                long t0 = System.nanoTime();
                boolean success = fsr3Pipeline.dispatch(
                    renderTex, depthTex,
                    jitterX, jitterY, prevJitterX, prevJitterY,
                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
                    1.0f / scaledWidth, 1.0f / scaledHeight,
                    lastProj, fsr2FrameCount,
                    config.sharpness,
                    hasPrevFrame ? prevViewProj : null,
                    currViewProj
                );
                if (success) {
                    fsr2OutputTexId = fsr3Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                    sceneUpscaled = true;
                    return;
                }
                logSlow("FSR3 dispatch", System.nanoTime() - t0);
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
                sceneUpscaled = true;
                return;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        // Copy upscaled output to mainRenderTarget
        if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            long t0 = System.nanoTime();
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
            int displayW = displayWidth;
            int displayH = displayHeight;
            GL43C.glCopyImageSubData(
                fsr2OutputTexId, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                displayW, displayH, 1
            );
            logSlow("glCopyImageSubData (to mainRT)", System.nanoTime() - t0);

            // FG: generate warped frame for smoother motion, put FG output in mainRT
            // so GUI renders on top of FG content (fixes UI flickering)
            if (isFrameGen() && fsr3Pipeline != null) {
                if (fsr3Pipeline.hasPreviousFrame()) {
                    long t0Fg = System.nanoTime();
                    fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId);
                    logSlow("FG dispatch", System.nanoTime() - t0Fg);

                    GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                    int fgTex = fsr3Pipeline.getFgOutputTexture();
                    if (fgTex != 0) {
                        GL43C.glCopyImageSubData(
                            fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                            displayW, displayH, 1
                        );
                    }
                }
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
        }

        // mainRenderTarget is already set — GUI will render to it at full res
        sceneUpscaled = true;
        logSlow("onSceneRendered (total)", System.nanoTime() - t0Scene);
    }

    public void onRenderEnd() {
        t0End = System.nanoTime();
        if (!insideFrame) {
            restoreDefaultTarget();
            return;
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            restoreDefaultTarget();
            return;
        }
        RenderSystem.assertOnRenderThread();

        Minecraft client = Minecraft.getInstance();

        // Iris path: restore mainRT
        if (IrisCompat.isShaderPackInUse()) {
            if (!sceneUpscaled && mainRenderTarget != null) {
                ((MinecraftClientAccessor) Minecraft.getInstance()).setMainRenderTarget(mainRenderTarget);
            }
            insideFrame = false;
            sceneUpscaled = false;
            return;
        }

        // Scene was already upscaled (and FG warped if active) in onSceneRendered(),
        // and GUI rendered on top at full resolution. Just clean up — Minecraft's
        // normal flipFrame handles presentation.
        if (sceneUpscaled) {
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            insideFrame = false;
            sceneUpscaled = false;
            return;
        }

        if (renderTarget == null) {
            restoreDefaultTarget();
            return;
        }

        int renderTex = ((GlTexture)renderTarget.getColorTexture()).glId();
        int depthTex = renderTarget.getDepthTexture() != null
            ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

        ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

        fsr2OutputTexId = 0;

        // FSR1: non-temporal EASU+RCAS pipeline — writes directly to mainRT
        if (config.version == FSRConfig.FSRVersion.FSR1) {
            if (renderTex == -1) {
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
            if (fsr1Pipeline == null) {
                FSRMod.LOGGER.info("FSR1: Initializing pipeline");
                fsr1Pipeline = new FSR1Pipeline();
                fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            }
            int mainTex = mainRenderTarget != null
                ? ((GlTexture)mainRenderTarget.getColorTexture()).glId() : 0;
            if (mainTex == 0) {
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
            try {
                boolean ok = fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight, displayWidth, displayHeight, config.sharpness, mainTex);
                if (!ok) {
                    FSRMod.LOGGER.warn("FSR1 dispatch returned false - falling back, restoring default target");
                    restoreDefaultTarget();
                    insideFrame = false;
                    return;
                }
                // FSR1 output is already in mainRT — skip copy below
                fsr2OutputTexId = 0;
            } catch (Exception e) {
                FSRMod.LOGGER.error("FSR1 dispatch threw exception", e);
                restoreDefaultTarget();
                insideFrame = false;
                return;
            }
        }

        if (isFsr2()) {
            if (!pipelineReady || fsr2Pipeline == null || renderTex == -1 || depthTex == -1) {
                insideFrame = false;
                return;
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
                .rotation(tmpQuat)
                .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProj);
            currViewProj.mul(tmpView);

            try {
                boolean success = fsr2Pipeline.dispatch(
                    renderTex, depthTex,
                    jitterX, jitterY, prevJitterX, prevJitterY,
                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
                    1.0f / scaledWidth, 1.0f / scaledHeight,
                    lastProj, fsr2FrameCount,
                    config.sharpness,
                    hasPrevFrame ? prevViewProj : null,
                    currViewProj
                );
                if (success) {
                    fsr2OutputTexId = fsr2Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                }
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (isFsr3()) {
            if (!pipelineReady || fsr3Pipeline == null || renderTex == -1 || depthTex == -1) {
                insideFrame = false;
                return;
            }

            Camera camera = client.gameRenderer.getMainCamera();
            Vec3 camPos = camera.position();
            tmpQuat.set(camera.rotation()).conjugate();
            tmpView.identity()
                .rotation(tmpQuat)
                .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
            currViewProj.set(lastProj);
            currViewProj.mul(tmpView);

            try {
                boolean success = fsr3Pipeline.dispatch(
                    renderTex, depthTex,
                    jitterX, jitterY, prevJitterX, prevJitterY,
                    (float) scaledWidth / displayWidth, (float) scaledHeight / displayHeight,
                    1.0f / scaledWidth, 1.0f / scaledHeight,
                    lastProj, fsr2FrameCount,
                    config.sharpness,
                    hasPrevFrame ? prevViewProj : null,
                    currViewProj
                );
                if (success) {
                    fsr2OutputTexId = fsr3Pipeline.getOutputTexture();
                    fsr2FrameCount++;
                } else {
                    config.version = FSRConfig.FSRVersion.FSR1;
                    pipelineReady = false;
                }
            } catch (Exception e) {
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
            }
            prevViewProj.set(currViewProj);
            hasPrevFrame = true;
        }

        if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            boolean fgDispatched = false;
            if (isFrameGen() && fsr3Pipeline != null) {
                if (fsr3Pipeline.hasPreviousFrame()) {
                    fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId);
                    GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                    fgDispatched = true;
                }
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }

            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();

            if (fgDispatched) {
                // Copy FG output (warped frame) to mainRT — GUI was not rendered
                // on this path (sceneUpscaled was false), but mainRT may still
                // have content; FG improves motion despite no GUI on this frame.
                long t0 = System.nanoTime();
                int fgTex = fsr3Pipeline.getFgOutputTexture();
                GL43C.glCopyImageSubData(
                    fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    displayWidth, displayHeight, 1
                );
                logSlow("glCopyImageSubData (FG -> mainRT)", System.nanoTime() - t0);
            } else {
                long t0 = System.nanoTime();
                GL43C.glCopyImageSubData(
                    fsr2OutputTexId, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    displayWidth, displayHeight, 1
                );
                logSlow("glCopyImageSubData (onRenderEnd)", System.nanoTime() - t0);
            }
        }

        long frameMs = (System.nanoTime() - t0Frame) / 1_000_000;
        if (frameMs >= 8) {
            FSRMod.LOGGER.warn("[TIMING] frame #{} total={}ms (onRenderEnd={}ms)",
                timingFrameCount, frameMs, (System.nanoTime() - t0End) / 1_000_000);
        }
        insideFrame = false;
    }

    public boolean presentDirect(RenderTarget caller) {
        return false;
    }

    public boolean isFrameGenMode() {
        return isFrameGen();
    }

    public FSR3Pipeline getFsr3Pipeline() {
        return fsr3Pipeline;
    }

    public int getDisplayWidth() {
        return displayWidth;
    }

    public int getDisplayHeight() {
        return displayHeight;
    }

    public void onResize(int newWindowWidth, int newWindowHeight) {
        // FIX: guard against no-op resizes. Without this, spurious resize events
        // (DPI changes, window manager events, GLFW framebuffer-size callbacks
        // firing on every frame) destroy all history textures and reset
        // fsr2FrameCount to 0, making FSR2/3 behave as FSR1 every frame.
        if (newWindowWidth == displayWidth && newWindowHeight == displayHeight) {
            return;
        }
        displayWidth = newWindowWidth;
        displayHeight = newWindowHeight;
        float scale = config.qualityScale;
        scaledWidth = Math.max(16, Math.round(displayWidth * scale));
        scaledHeight = Math.max(16, Math.round(displayHeight * scale));

        if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        proxyTarget = null;
        if (fsr2Pipeline != null) {
            fsr2Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            fsr2Pipeline.recreateTextures();
        }
        if (fsr3Pipeline != null) {
            fsr3Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
            fsr3Pipeline.recreateTextures();
            fsr3Pipeline.resetFrameGen();
        }
        if (fsr1Pipeline != null) {
            fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
        }
        rcasUpscaleCompiled = false;
        jitterManager.reset();
        // FIX: also reset prevJitter when resetting jitter manager, otherwise
        // MV_GEN computes a bogus jitter delta on the first post-reset frame.
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        hasPrevFrame = false;
        fsr2FrameCount = 0;
        pipelineReady = false;
    }

    public void markDirty() {
        float scale = config.qualityScale;
        scaledWidth = Math.max(16, Math.round(displayWidth * scale));
        scaledHeight = Math.max(16, Math.round(displayHeight * scale));
        if (renderTarget != null) { renderTarget.destroyBuffers(); renderTarget = null; }
        proxyTarget = null;
        if (fsr2Pipeline != null) {
            fsr2Pipeline.deleteAllTextures();
        }
        if (fsr3Pipeline != null) {
            fsr3Pipeline.deleteAllTextures();
            fsr3Pipeline.resetFrameGen();
        }
        if (fsr1Pipeline != null) {
            fsr1Pipeline.setup(scaledWidth, scaledHeight, displayWidth, displayHeight);
        }
        rcasUpscaleCompiled = false;
        pipelineReady = false;
        jitterManager.reset();
        // FIX: also reset prevJitter when resetting jitter manager.
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        fsr2FrameCount = 0;
        hasPrevFrame = false;
    }

    public void destroy() {
        if (renderTarget != null) renderTarget.destroyBuffers();
        proxyTarget = null;
        if (rcasUpscaleProg != -1) { GL43C.glDeleteProgram(rcasUpscaleProg); rcasUpscaleProg = -1; }
        if (rcasUpscaleTex != -1) { GL43C.glDeleteTextures(rcasUpscaleTex); rcasUpscaleTex = -1; }
        if (rcasUpscaleVao != -1) { GL43C.glDeleteVertexArrays(rcasUpscaleVao); rcasUpscaleVao = -1; }
        if (rcasUpscaleFbo != -1) { GL43C.glDeleteFramebuffers(rcasUpscaleFbo); rcasUpscaleFbo = -1; }
        if (fsr2Pipeline != null) fsr2Pipeline.destroy();
        if (fsr3Pipeline != null) fsr3Pipeline.destroy();
        if (fsr1Pipeline != null) fsr1Pipeline.destroy();
    }

    private boolean isIrisActive() {
        return IrisCompat.isShaderPackInUse();
    }

    private boolean ensureUpscaleShaders() {
        if (rcasUpscaleCompiled) return true;
        if (rcasUpscaleProg != -1) { GL43C.glDeleteProgram(rcasUpscaleProg); rcasUpscaleProg = -1; }

        String vertexSrc = "#version 430 core\nvoid main(){int x=gl_VertexID&1;int y=(gl_VertexID>>1)&1;gl_Position=vec4(float(x)*2.0-1.0,float(y)*2.0-1.0,0.0,1.0);}";
        String fsSrc = "#version 430 core\nuniform sampler2D uInput;uniform ivec2 uInputSize;uniform ivec2 uOutputSize;uniform float uSharpness;layout(location=0)out vec4 fragColor;void main(){ivec2 p=ivec2(gl_FragCoord.xy);if(p.x>=uOutputSize.x||p.y>=uOutputSize.y){discard;}vec2 outUv=(vec2(p)+0.5)/vec2(uOutputSize);vec2 inPx=1.0/vec2(uInputSize);vec3 c=textureLod(uInput,outUv,0.0).rgb;if(uSharpness<0.001){fragColor=vec4(c,1.0);return;}vec3 t=textureLod(uInput,outUv+vec2(0,-1)*inPx,0.0).rgb;vec3 b=textureLod(uInput,outUv+vec2(0,1)*inPx,0.0).rgb;vec3 l=textureLod(uInput,outUv+vec2(-1,0)*inPx,0.0).rgb;vec3 r=textureLod(uInput,outUv+vec2(1,0)*inPx,0.0).rgb;vec3 d=max(abs(t-c),abs(b-c));d=max(d,abs(l-c));d=max(d,abs(r-c));float dMax=max(d.r,max(d.g,d.b));if(dMax<1e-7){fragColor=vec4(c,1.0);return;}float sharp=float(int(uSharpness*7.0+0.5))/8.0;vec3 w=clamp(1.0-sharp*d/dMax,0.0,1.0);vec3 sharpend=(c*4.0+t+b+l+r)*(1.0/8.0);sharpend=c+w*(sharpend-c);fragColor=vec4(clamp(sharpend,0.0,1.0),1.0);}";

        int vs = GL43C.glCreateShader(GL43C.GL_VERTEX_SHADER);
        GL43C.glShaderSource(vs, vertexSrc);
        GL43C.glCompileShader(vs);
        if (GL43C.glGetShaderi(vs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteShader(vs);
            rcasUpscaleCompiled = false;
            return false;
        }

        int fs = GL43C.glCreateShader(GL43C.GL_FRAGMENT_SHADER);
        GL43C.glShaderSource(fs, fsSrc);
        GL43C.glCompileShader(fs);
        if (GL43C.glGetShaderi(fs, GL43C.GL_COMPILE_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteShader(vs);
            GL43C.glDeleteShader(fs);
            rcasUpscaleCompiled = false;
            return false;
        }

        rcasUpscaleProg = GL43C.glCreateProgram();
        GL43C.glAttachShader(rcasUpscaleProg, vs);
        GL43C.glAttachShader(rcasUpscaleProg, fs);
        GL43C.glLinkProgram(rcasUpscaleProg);
        if (GL43C.glGetProgrami(rcasUpscaleProg, GL43C.GL_LINK_STATUS) == GL43C.GL_FALSE) {
            GL43C.glDeleteProgram(rcasUpscaleProg);
            rcasUpscaleProg = -1;
        }
        GL43C.glDeleteShader(vs);
        GL43C.glDeleteShader(fs);

        rcasUpscaleCompiled = rcasUpscaleProg != -1;
        if (rcasUpscaleCompiled) {
            if (rcasUpscaleVao == -1) rcasUpscaleVao = GL43C.glGenVertexArrays();
            if (rcasUpscaleFbo == -1) rcasUpscaleFbo = GL43C.glGenFramebuffers();
        }
        return rcasUpscaleCompiled;
    }

    private void applyUpscaleAndSharpen(int srcTex, int srcW, int srcH, int dstW, int dstH, float sharpness) {
        if (srcTex == -1 || dstW <= 0 || dstH <= 0) return;
        if (!ensureUpscaleShaders() || rcasUpscaleProg == -1) return;
        if (sharpness < 0.001f) return;

        if (mainRenderTarget == null) return;
        int dstTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();

        // Create temp texture for upscale+sharpen output (if needed or resized)
        if (rcasUpscaleTex == -1 || rcasUpscaleTexW != dstW || rcasUpscaleTexH != dstH) {
            if (rcasUpscaleTex != -1) GL43C.glDeleteTextures(rcasUpscaleTex);
            rcasUpscaleTex = GL43C.glGenTextures();
            rcasUpscaleTexW = dstW;
            rcasUpscaleTexH = dstH;
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, rcasUpscaleTex);
            GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA8, dstW, dstH, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, 0);
            GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
            GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);
        }

        // Use a fullscreen quad + fragment shader
        int oldFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, rcasUpscaleFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_DRAW_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, rcasUpscaleTex, 0);

        GL43C.glUseProgram(rcasUpscaleProg);
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, srcTex);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
        GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);

        int uInputLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uInput");
        int uInputSizeLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uInputSize");
        int uOutputSizeLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uOutputSize");
        int uSharpnessLoc = GL43C.glGetUniformLocation(rcasUpscaleProg, "uSharpness");

        if (uInputLoc != -1) GL43C.glUniform1i(uInputLoc, 0);
        if (uInputSizeLoc != -1) GL43C.glUniform2i(uInputSizeLoc, srcW, srcH);
        if (uOutputSizeLoc != -1) GL43C.glUniform2i(uOutputSizeLoc, dstW, dstH);
        if (uSharpnessLoc != -1) GL43C.glUniform1f(uSharpnessLoc, sharpness);

        GL43C.glViewport(0, 0, dstW, dstH);
        GL43C.glBindVertexArray(rcasUpscaleVao);
        GL43C.glDrawArrays(GL43C.GL_TRIANGLE_STRIP, 0, 4);

        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, oldFbo);

        // Copy temp texture to mainRT
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            rcasUpscaleTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            dstTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            dstW, dstH, 1
        );
    }

    public boolean isInsideFrame() { return insideFrame; }
}
