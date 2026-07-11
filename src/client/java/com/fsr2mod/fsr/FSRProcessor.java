package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.IrisCompat;
import com.fsr2mod.compat.DistantHorizonsCompat;
import com.fsr2mod.compat.VulkanModCompat;
import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.mixin.MinecraftClientAccessor;
import com.fsr2mod.vulkan.Fsr4Native;
import com.fsr2mod.vulkan.VulkanInterop;
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
            // With VulkanMod active, there is no current GL context — calling
            // glGetString would cause a fatal JVM abort. Check GLFW first.
            if (org.lwjgl.glfw.GLFW.glfwGetCurrentContext() == 0) {
                isIntelGpu = false;
                return false;
            }
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
    private SharedTextureManager sharedTexMgr;
    private boolean sharedMemoryActive;
    private boolean fsr4Available;
    private boolean fsr4Initialized;
    private int fsr4ContextRenderW, fsr4ContextRenderH;
    private int fsr4ContextDisplayW, fsr4ContextDisplayH;
    private long lastFsr4ContextChange;
    private static final long FSR4_DEBOUNCE_MS = 500;
    private int fsr4FramesSkipped;

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

    // Depth copy compute shader: reads depth texture (any format) and writes to
    // shared R32F texture. Used instead of glCopyImageSubData because depth
    // format (GL_DEPTH_COMPONENT*) and R32F are incompatible for copy.
    private static final String DEPTH_COPY_CS = """
        #version 430 core
        layout(local_size_x = 8, local_size_y = 8) in;
        layout(binding = 0) uniform sampler2D uInputDepth;
        layout(binding = 1, r32f) writeonly uniform image2D uOutputDepth;
        uniform ivec2 uDepthSize;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            if (p.x >= uDepthSize.x || p.y >= uDepthSize.y) return;
            float d = texelFetch(uInputDepth, p, 0).r;
            imageStore(uOutputDepth, p, vec4(d, 0.0, 0.0, 0.0));
        }
        """;

    private int depthCopyProg;
    private boolean depthCopyCompiled;
    private int depthCopy_uDepthSize;

	    // MV gen for FSR4 path (duplicated MV_GEN_CS from FSR2Pipeline/FSR3Pipeline)
	    //
	    // NOTE: uUseDepth=0 workaround for Minecraft 1.21.4 depth texture issue
	    // (render target depth texture reads 0 everywhere — likely a GpuDevice
	    // abstraction layer issue where render passes use a separate internal
	    // depth buffer and don't write to the RenderTarget's depth texture).
	    // Until this is resolved, ndcZ is hardcoded to 0.0 (mid-viewport depth
	    // = ~50 units at typical Minecraft render distance) which gives non-zero
	    // reprojected MVs during camera motion. Sky detection is disabled when
	    // uUseDepth=0 since it requires valid depth.
	    private static final String MV_GEN_CS = """
	        #version 430 core
	        layout(local_size_x = 8, local_size_y = 8) in;
	        layout(binding = 0) uniform sampler2D uInputDepth;
		    layout(binding = 1, rg16f) writeonly uniform image2D uOutputMV;
		    layout(binding = 2, r8) writeonly uniform image2D uOutputReactive;

		    uniform ivec2 uRenderSize;
	        uniform mat4 uInvCurrVP;
	        uniform mat4 uPrevVP;
	        uniform vec2 uJitter;
	        uniform vec2 uPrevJitter;
	        uniform float uFarDepth;
	        uniform vec4 uDeviceToViewDepth;
	        uniform float uFarViewZ;
	        uniform int uUseDepth;  // 0=constant ndcZ, 1=read depth texture

	        float getViewSpaceDepth(float d) {
	            return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
	        }

	        void main() {
	            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

	            vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
	            float ndcZ;

	            if (uUseDepth > 0) {
	                float depth = texelFetch(uInputDepth, p, 0).r;
	                // Sky detection via view-space Z ratio (same as FSR2/3 pipelines)
	                float vz = getViewSpaceDepth(clamp(depth, 0.0, 1.0 - 1e-7));
	                    if (abs(vz) > abs(uFarViewZ) * 0.95) {
		                    imageStore(uOutputMV, p, vec4(0.0));
		                    imageStore(uOutputReactive, p, vec4(1.0, 0.0, 0.0, 0.0)); // sky = fully reactive
		                    return;
	                }
	                ndcZ = depth * 2.0 - 1.0;
	            } else {
	                // Constant mid-depth fallback: ndcZ=0 maps to z = near*far/(near+far)
	                // in reversed-Z or z = (near+far)/2 in non-reversed. This gives
	                // ~50 units depth at typical render distance — reasonable
	                // approximation for camera-motion MVs.
	                ndcZ = 0.0;
	            }

	            vec2 ndc = uv * 2.0 - 1.0;
	            vec4 worldH = uInvCurrVP * vec4(ndc, ndcZ, 1.0);
	            worldH /= worldH.w;

	            vec4 prevClip = uPrevVP * vec4(worldH.xyz, 1.0);
	            vec2 prevNdc = prevClip.xy / prevClip.w;
	            vec2 prevUv = prevNdc * 0.5 + 0.5;

	            vec2 mv = (prevUv - uv) * vec2(uRenderSize);

		            imageStore(uOutputMV, p, vec4(mv, 0.0, 0.0));

		            // Generate reactive mask from MV magnitude (merged from REACTIVE_MASK_CS)
		            // Saves one compute dispatch per frame.
		            float mvLen = length(mv);
		            float reactive = smoothstep(0.5, 8.0, mvLen);
		            imageStore(uOutputReactive, p, vec4(reactive, 0.0, 0.0, 0.0));
		        }
	        """;

	    private int mvGenProg;
	    private boolean mvGenCompiled;
	    private int mv_uRenderSize, mv_uInvCurrVP, mv_uPrevVP, mv_uJitter, mv_uPrevJitter, mv_uFarDepth, mv_uDeviceToViewDepth, mv_uFarViewZ, mv_uUseDepth;
	    private int mvClearFbo;
	    private final float[] mvMatBuf = new float[16];
	    private final Matrix4f mvTmpMat = new Matrix4f();
	    private final float[] mvDeviceToViewDepth = new float[4];
	    private float mvFarViewZ;

	    // Reactive mask compute shader for FSR4
	    private static final String REACTIVE_MASK_CS = """
	        #version 430 core
	        layout(local_size_x = 8, local_size_y = 8) in;
	        layout(binding = 0, rg16f) readonly uniform image2D uInputMV;
	        layout(binding = 1, r8) writeonly uniform image2D uOutputReactive;

	        uniform ivec2 uRenderSize;

	        void main() {
	            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	            if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

	            vec2 mv = imageLoad(uInputMV, p).xy;
	            float mvLen = length(mv);

	            // Reactive = 0 for static pixels, 1 for fast-moving pixels.
	            // Thresholds tuned for pixel-space MV magnitude:
	            //   < 0.5 px  → 0.0 (static, full temporal accumulation)
	            //   0.5-8 px  → 0.0-1.0 ramp   (partial reaction)
	            //   > 8 px    → 1.0 (fully reactive, discard history)
	            float reactive = smoothstep(0.5, 8.0, mvLen);
	            imageStore(uOutputReactive, p, vec4(reactive, 0.0, 0.0, 0.0));
	        }
	        """;

	    private int reactiveMaskProg;
	    private boolean reactiveMaskCompiled;
	    private int reactiveMask_uRenderSize;

	    // Debug logging: throttle to every 60 frames using timingFrameCount
	    private boolean shouldDebug() {
	        return timingFrameCount % 60 == 0;
	    }
	    // Reusable resources for debug texture readback
	    private int debugFbo = -1;
	    private java.nio.ByteBuffer debugPboBuf; // allocated lazily

	    // Runtime override: DH detected forces FSR1 (DH depth not available to FSR2/3)
    private boolean dhActive;

    // FG alternation: alternate between presenting real frame and FG interpolated frame
    private boolean fgFlipState;

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
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR2;
    }

    private boolean isFsr3() {
        return !isIntelGpu() && !dhActive && config.version != null && (config.version == FSRConfig.FSRVersion.FSR3_UPS || config.version == FSRConfig.FSRVersion.FSR3_FG);
    }

    private boolean isFrameGen() {
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR3_FG;
    }

    private boolean isFsr4() {
        return !isIntelGpu() && !dhActive && config.version != null && config.version == FSRConfig.FSRVersion.FSR4;
    }

    private boolean isTemporalUpscaler() {
        return isFsr2() || isFsr3() || isFsr4();
    }

    private static Boolean vulkanAvailable = null;

    private static boolean checkVulkanAvailable() {
        // Cache the result — only attempt Vulkan init once (at first frame).
        // Without caching, systems without Vulkan would trigger a failed
        // delay-load + SEH exception on every frame.
        if (vulkanAvailable != null) return vulkanAvailable;
        if (!VulkanInterop.isAvailable()) {
            vulkanAvailable = false;
            return false;
        }
        try {
            String info = VulkanInterop.getDeviceInfo();
            vulkanAvailable = info != null && !info.isEmpty();
        } catch (Exception e) {
            vulkanAvailable = false;
        }
        return vulkanAvailable;
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

        // VulkanMod path: Vulkan replaces OpenGL entirely. No render target swap,
        // no viewport change, no jitter — VulkanMod's own swapchain-based rendering
        // handles the scene. FSR4 interop is handled by VulkanMod mixins + native code.
        // Other FSR modes (FSR1/2/3) are not available when VulkanMod is active
        // since the GL compute shader pipelines won't work without an OpenGL context.
        if (VulkanModCompat.isActive()) {
            originalMainRenderTarget = client.getMainRenderTarget();
            mainRenderTarget = originalMainRenderTarget;
            jitterX = 0.0f;
            jitterY = 0.0f;

            // FSR4: initialize D3D12 pool + context + external VkImages.
            // Must happen here (not in onSceneRendered) because the FSR4 init
            // section after the Iris/VulkanMod checks is unreachable from here.
            if (isFsr4() && Fsr4Native.isAvailable()) {
                fsr4Available = true;
                int renderW = displayWidth;
                int renderH = displayHeight;
                int dispW = displayWidth;
                int dispH = displayHeight;

                long now = System.currentTimeMillis();
                boolean needsRecreate = !fsr4Initialized
                    || fsr4ContextRenderW != renderW || fsr4ContextRenderH != renderH
                    || fsr4ContextDisplayW != dispW || fsr4ContextDisplayH != dispH;

                if (needsRecreate) {
                    if (now - lastFsr4ContextChange < FSR4_DEBOUNCE_MS) {
                        // Too soon since last recreation — skip
                    } else {
                        lastFsr4ContextChange = now;
                        if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; }

                        try {
                            if (sharedTexMgr == null) sharedTexMgr = new SharedTextureManager();
                            // D3D12-only pool allocation — safe without GL context (VulkanMod)
                            boolean poolOk = sharedTexMgr.allocateFsr4PoolD3D12(renderW, renderH, dispW, dispH);
                            if (poolOk) {
                                boolean ok = Fsr4Native.initFsr4(renderW, renderH, dispW, dispH);
                                if (ok) {
                                    fsr4Initialized = true;
                                    fsr4FramesSkipped = 0;
                                    fsr4ContextRenderW = renderW; fsr4ContextRenderH = renderH;
                                    fsr4ContextDisplayW = dispW; fsr4ContextDisplayH = dispH;
                                    FSRMod.LOGGER.info("FSR4: VulkanMod init OK ({}x{} -> {}x{})", renderW, renderH, dispW, dispH);

                                    // Init external VkImages ONCE after pool/context creation.
                                    // These are only recreated when needsRecreate fires (resize).
                                    VulkanModCompat.initExternalImages(displayWidth, displayHeight);
                                    // Export input (VK→D3D12) first — this always works.
                                    // Then import output from pool (D3D12→VK) instead of creating
                                    // a second VK allocation, which triggers an AMD driver bug
                                    // on Radeon 860M (second VK external memory allocation
                                    // produces a handle that crashes D3D12 OpenSharedHandle).
                                    VulkanModCompat.exportInputToD3D12();
                                    VulkanModCompat.importOutputFromD3D12Pool(
                                        sharedTexMgr.getOutputAllocPtr(), 4);  // pool index 4 = OUTPUT

                                    // DEBUG: Enable pipeline bypass test
                                    // When enabled, FSR4 SDK dispatch is replaced with
                                    // CopyResource from pool color → pool output.
                                    // If the screen shows the scene (not black), the
                                    // D3D12→VK→swapchain pipeline works and the issue
                                    // is in FSR4 SDK processing.
                                    VulkanModCompat.setDebugPipelineTest(true);
                                    FSRMod.LOGGER.info("FSR4: PIPELINE TEST MODE ENABLED");
                                }
                            }
                        } catch (Exception e) {
                            FSRMod.LOGGER.warn("FSR4: VulkanMod pool/init failed: {}", e.getMessage());
                        }
                    }
                }
            }

            // FSR4 temporal dispatch — processes the scene captured in the input
            // VkImage by the previous frame's endFrame copy. This is a one-frame
            // delay: on frame N, we process frame N-1's scene and display the
            // FSR4 result on frame N's swapchain (copied by endFrame's
            // output → swapchain path).
            // Camera params come from lastProj which was saved by setProjection()
            // during the previous frame's renderLevel(HEAD) — matching the
            // previous frame's scene in the input VkImage.
            if (isFsr4() && fsr4Available && fsr4Initialized
                && sharedTexMgr != null && sharedTexMgr.getColorAllocPtr() != 0
                && Math.abs(lastProj.m11()) > 0.001f) {

                float a = lastProj.m22();
                float b = lastProj.m23();
                float cameraNear = b / (a - 1.0f);
                float cameraFar = b / (a + 1.0f);
                if (Float.isNaN(cameraNear) || Float.isNaN(cameraFar) || cameraNear <= 0.0f) {
                    cameraNear = 0.1f;
                    cameraFar = 1000.0f;
                }
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                float cameraFovY = (float) (2.0 * Math.atan(tanHalfFovY));
                float deltaTime = Math.max(1.0f, (System.nanoTime() - t0Frame) / 1_000_000.0f);
                int frameIndex = fsr2FrameCount;
                boolean reset = fsr2FrameCount == 0;

	                // Wait for the previous frame's swapchain copy to complete
	                // before reading the input VkImage contents via D3D12 shared memory.
	                VulkanModCompat.waitForFrameCopy();
	
	                boolean dispatched = VulkanModCompat.dispatchFsr4VkMod(
                    0.0f, 0.0f,         // jitter — none for VulkanMod (native res)
                    cameraFar, cameraNear, cameraFovY,
                    deltaTime, frameIndex, reset,
                    sharedTexMgr.getColorAllocPtr(), 1,  // DEPTH index
                    sharedTexMgr.getColorAllocPtr(), 2,  // MV index
                    sharedTexMgr.getColorAllocPtr(), 3,  // REACTIVE index
                    sharedTexMgr.getOutputAllocPtr(), 4  // OUTPUT index
                );

	                if (dispatched) {
	                    // Wait for the D3D12 FSR4 work to complete before the
	                    // current frame's recordFrameCopyOps copies the output
	                    // to the swapchain. Without this wait, the VK copy may
	                    // read stale data from the pool output texture because
	                    // the D3D12 queue and VK queue lack explicit ordering.
	                    // Same pattern as the OpenGL path (see onSceneRendered).
	                    Fsr4Native.waitIdle();
	                    fsr2FrameCount++;
	                    hasPrevFrame = true;
	                }
	            }

	            if (config.version != FSRConfig.FSRVersion.FSR4) {
                FSRMod.LOGGER.info("VulkanMod active: only FSR4 upscaling is supported (FSR{}) — falling back to pass-through",
                    config.version == FSRConfig.FSRVersion.FSR1 ? "1" :
                    config.version == FSRConfig.FSRVersion.FSR2 ? "2" : "3");
            }
            return;
        }

        // Distant Horizons: LOD terrain uses separate framebuffers with own depth
        // textures. FSR2/3 temporal reconstruction needs unified depth — DH depth
        // is not available. Auto-fallback to FSR1 (spatial upscale) for correct visuals.
        dhActive = DistantHorizonsCompat.isLoaded();
        if (dhActive) {
            DistantHorizonsCompat.logStatus();
            if (isTemporalUpscaler()) {
                FSRMod.LOGGER.warn("Distant Horizons detected with FSR2/3 temporal upscaler — DH depth not available, switching to FSR1");
            }
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
            scaledWidth, scaledHeight,
            renderTarget.getColorTexture(),
            renderTarget.getColorTextureView(),
            renderTarget.getDepthTexture(),
            renderTarget.getDepthTextureView()
        );

	        ((MinecraftClientAccessor) client).setMainRenderTarget(proxyTarget);

	        // Set viewport to scaled resolution so the 3D scene renders at lower res
	        // (proxy reports actual scaled dims, but some mods like Voxy read
	        // window dimensions — this viewport override ensures correct pixel count)
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

		        // Initialize shared texture manager for FSR output.
		        // The single-texture (Vulkan-backed) path is only for FSR1/2/3 output injection.
	        // The FSR4 5-texture pool uses direct D3D12→GL (GL_HANDLE_TYPE_D3D12_RESOURCE_EXT)
	        // and does NOT require Vulkan.
	        if (checkVulkanAvailable()) {
	            boolean needsRecreate = sharedTexMgr == null || !sharedTexMgr.isValid()
	                || sharedTexMgr.getWidth() != displayWidth || sharedTexMgr.getHeight() != displayHeight;
	            if (needsRecreate) {
	                if (sharedTexMgr != null) sharedTexMgr.destroy();
	                try {
	                    if (sharedTexMgr == null) sharedTexMgr = new SharedTextureManager();
	                    sharedTexMgr.create(displayWidth, displayHeight);
	                    sharedTexMgr.attachToMainRT(mainRenderTarget);
	                    sharedMemoryActive = true;
	                } catch (Exception e) {
	                    FSRMod.LOGGER.warn("Shared memory unavailable, falling back: {}", e.getMessage());
	                    if (sharedTexMgr != null) sharedTexMgr.destroy();
	                    sharedMemoryActive = false;
	                }
	            }
	        } else {
	            // Vulkan not available — ensure sharedTexMgr exists for FSR4 pool
	            if (sharedTexMgr == null) {
	                sharedTexMgr = new SharedTextureManager();
	            }
	            sharedMemoryActive = false;
		        }

		        // Initialize FSR4 context (D3D12 compute via AMD FSR SDK).
        // CRITICAL: Only initialize when FSR4 is selected in config — creating
        // the D3D12 device alongside an active OpenGL context destabilizes the
        // AMD driver on RDNA 3.5 iGPUs, causing TDR / driver crashes.
        if (isFsr4()) {
            if (Fsr4Native.isAvailable()) {
                fsr4Available = true;
                int renderW = scaledWidth;
                int renderH = scaledHeight;
                int dispW = displayWidth;
                int dispH = displayHeight;

                long now = System.currentTimeMillis();
                boolean needsRecreate = !fsr4Initialized
                    || fsr4ContextRenderW != renderW || fsr4ContextRenderH != renderH
                    || fsr4ContextDisplayW != dispW || fsr4ContextDisplayH != dispH;

                if (needsRecreate) {
                    if (now - lastFsr4ContextChange < FSR4_DEBOUNCE_MS) {
                        // Too soon since last recreation — skip
                    } else {
                        lastFsr4ContextChange = now;
                        if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; }

                        try {
                            if (sharedTexMgr != null) {
                                sharedTexMgr.destroyFsr4Pool();
                            }
                            boolean poolOk = sharedTexMgr != null && sharedTexMgr.allocateFsr4Pool(renderW, renderH, dispW, dispH);
                            if (!poolOk) {
                                FSRMod.LOGGER.warn("FSR4: allocateFsr4Pool failed");
                            } else {
                                boolean ok = Fsr4Native.initFsr4(renderW, renderH, dispW, dispH);
                                if (ok) {
                                    fsr4Initialized = true;
                                    fsr4FramesSkipped = 0; // reset skip counter on successful reinit
                                    fsr4ContextRenderW = renderW; fsr4ContextRenderH = renderH;
                                    fsr4ContextDisplayW = dispW; fsr4ContextDisplayH = dispH;
                                    FSRMod.LOGGER.info("FSR4: init OK ({}x{} -> {}x{})", renderW, renderH, dispW, dispH);
                                } else {
                                    FSRMod.LOGGER.warn("FSR4: initFsr4 returned false");
                                }
                            }
                        } catch (Exception e) {
                            FSRMod.LOGGER.warn("FSR4 init failed: {}", e.getMessage());
                            if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; }
                        }
                    }
                }
            } else {
                fsr4Available = false;
            }
        } else {
            // FSR4 not selected — ensure no lingering D3D12 device
            if (fsr4Initialized) {
                Fsr4Native.shutdownFsr4();
                fsr4Initialized = false;
            }
            if (sharedTexMgr != null) {
                sharedTexMgr.destroyFsr4Pool();
            }
            fsr4Available = false;
        }

        logSlow("onRenderBegin (total)", System.nanoTime() - t0Begin);
    }

    public void applyJitterToMatrix(Matrix4f proj) {
        if (!isTemporalUpscaler()) return;
        if (!insideFrame) return;
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
                        displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
                }
            } else if (config.sharpness > 0.0f && mainRenderTarget != null) {
                // RCAS sharpen only (same src/dst size = 1:1 blit + RCAS)
                int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
                if (fsr1Pipeline == null) {
                    FSRMod.LOGGER.info("FSR1: Initializing pipeline (Iris RCAS only)");
                    fsr1Pipeline = new FSR1Pipeline();
                }
                fsr1Pipeline.dispatch(mainTex, displayWidth, displayHeight,
                    displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
            }

            sceneUpscaled = true;
            logSlow("onSceneRendered (Iris total)", System.nanoTime() - t0Scene);
            return;
        }

        // VulkanMod path: scene was already captured by recordFrameCopyOps in the
        // endFrame mixin, and FSR4 dispatch runs in onRenderBegin (processing the
        // previous frame's scene). The output VkImage gets copied to the swapchain
        // in the next frame's recordFrameCopyOps. No GL operations needed here.
        if (VulkanModCompat.isActive()) {
            ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);
            sceneUpscaled = true;
            logSlow("onSceneRendered (VulkanMod)", System.nanoTime() - t0Scene);
            return;
        }

        // --- Non-Iris path (OpenGL) ---
        if (renderTarget == null) return;

        // Restore viewport to display resolution (was scaled for 3D rendering)
        GL43C.glViewport(0, 0, displayWidth, displayHeight);

        int renderTex = ((GlTexture)renderTarget.getColorTexture()).glId();
        int depthTex = renderTarget.getDepthTexture() != null
            ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

        ((MinecraftClientAccessor) client).setMainRenderTarget(mainRenderTarget);

        fsr2OutputTexId = 0;

        // FSR4 dispatch (D3D12 compute via AMD FSR SDK unified API).
        // Only runs when FSR4 version is selected — NOT for FSR2/FSR3 (they use
        // their own GL compute-shader pipelines below).
        if (fsr4Available && fsr4Initialized && sharedTexMgr != null
            && isFsr4()) {
            // NOTE: No frame-time guard here. The GL sync fence (50ms timeout) and
            // D3D12 fence wait (2000ms) handle real hangs. A pre-dispatch timeout
            // caused false fallback to FSR1 on the first frame (FSR4 init takes
            // ~500ms, exceeding any reasonable guard) and on heavy scenes.
            // The FSR4 SDK handles late frames gracefully via deltaTime.

            // Copy scene color from renderTarget to shared D3D12 texture
            // (RGBA8 → RGBA8: compatible formats, glCopyImageSubData is fastest)
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            if (renderTex != -1) {
                GL43C.glCopyImageSubData(
                    renderTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    sharedTexMgr.getColorTexId(), GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    scaledWidth, scaledHeight, 1
                );
            }

            // Copy depth via compute shader: depth texture format (GL_DEPTH_COMPONENT*)
            // is NOT compatible with R32F for glCopyImageSubData — would generate
            // GL_INVALID_OPERATION and leave garbage in the shared depth texture.
            if (depthTex != -1) {
                copyDepthToShared(depthTex, sharedTexMgr.getDepthTexId());
            }

            // Compute camera parameters from projection matrix.
            // For standard OpenGL projection:
            //   m22 = -(far + near) / (far - near)
            //   m23 = -2 * far * near / (far - near)
            // Solving: near = b / (a - 1),  far = b / (a + 1)
            float a = lastProj.m22();
            float b = lastProj.m23();
            float cameraNear = b / (a - 1.0f);   // was b/(a+1) — that gave FAR, not NEAR
            float cameraFar = b / (a + 1.0f);    // was b/(a-1) — that gave NEAR, not FAR
            if (Float.isNaN(cameraNear) || Float.isNaN(cameraFar) || cameraNear <= 0.0f) {
                cameraNear = 0.1f;
                cameraFar = 1000.0f;
            }
            float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
            float cameraFovY = (float) (2.0 * Math.atan(tanHalfFovY));
            // deltaTime in MILLISECONDS for FSR4/FSR3 SDK
            float deltaTime = Math.max(1.0f, (System.nanoTime() - t0Frame) / 1_000_000.0f);
            int frameIndex = fsr2FrameCount;
            boolean reset = !hasPrevFrame || fsr2FrameCount == 0;

            // Debug: log FSR4 camera and dispatch parameters every 60 frames
            if (shouldDebug()) {
                float mvScaleX = 1.0f / scaledWidth;
                float mvScaleY = 1.0f / scaledHeight;
                FSRMod.LOGGER.info(String.format(
                    "[DEBUG_FSR4] frame=%d reset=%s near=%.2f far=%.2f fovY=%.1fdeg tanHalf=%.4f"
                    + " proj22=%.4f proj23=%.4f dt=%.1fms render=%dx%d mvScale=(%.6f,%.6f)"
                    + " hasPrev=%s jitter=(%.3f,%.3f) prevJitter=(%.3f,%.3f)",
                    frameIndex, reset, cameraNear, cameraFar,
                    Math.toDegrees(cameraFovY), tanHalfFovY,
                    a, b, deltaTime, scaledWidth, scaledHeight,
                    mvScaleX, mvScaleY, hasPrevFrame,
                    jitterX, jitterY, prevJitterX, prevJitterY));
            }

	            // Generate motion vectors AND reactive mask into shared textures before FSR4 dispatch
	            if (depthTex != -1 && sharedTexMgr.getMvTexId() != 0 && sharedTexMgr.getReactiveTexId() != 0) {
	                generateFSR4MotionVectors(depthTex, sharedTexMgr.getMvTexId(), sharedTexMgr.getReactiveTexId());
	            }

	            // Reactive mask is now generated inside the MV gen shader — no separate dispatch needed.

	            // Consolidated memory barrier: covers all GL compute work (depth copy,
	            // MV gen, reactive mask) before D3D12 reads shared textures.
	            // Single GL_ALL_BARRIER_BITS replaces the per-dispatch barriers that
	            // were removed from individual helper methods, saving driver overhead.
	            GL43C.glMemoryBarrier(GL43C.GL_ALL_BARRIER_BITS);

	            // GL sync: fence + client wait (50ms timeout).
	            // glFlush() is REQUIRED — without it the fence sync may never
	            // complete because commands sit in the driver's internal buffer.
            GL43C.glFlush();
            long sync = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            int waitStatus = GL43C.glClientWaitSync(sync, 0, 50_000_000L);
            GL43C.glDeleteSync(sync);

            if (waitStatus != GL43C.GL_ALREADY_SIGNALED && waitStatus != GL43C.GL_CONDITION_SATISFIED) {
                FSRMod.LOGGER.warn("FSR4: GL sync timeout, skipping frame");
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 skip)", System.nanoTime() - t0Scene);
                return;
            }

            // Dispacth FSR4 via D3D12 with (poolPtr, index) pairs.
	            // The MV shader now produces raw reprojection MVs with NO jitter
	            // (jitter subtraction removed since the reprojection matrices are
	            // captured before jitter injection in the mixin). Pass (0,0) for
	            // jitterOffset so the SDK doesn't adjust the already-clean MV.
	            boolean dispatched = Fsr4Native.dispatchFsr4(
	                0.0f, 0.0f,     // jitterX, jitterY — MV is already clean
	                cameraFar, cameraNear, cameraFovY,
	                deltaTime, frameIndex, reset,
                sharedTexMgr.getColorAllocPtr(), 0,
                sharedTexMgr.getDepthAllocPtr(), 1,
                sharedTexMgr.getMvAllocPtr(), 2,
                sharedTexMgr.getReactiveAllocPtr(), 3,
                sharedTexMgr.getOutputAllocPtr(), 4
            );

            if (!dispatched) {
                FSRMod.LOGGER.warn("FSR4: dispatch returned false, falling back to FSR1");
                config.version = FSRConfig.FSRVersion.FSR1;
                pipelineReady = false;
                sceneUpscaled = true;
                logSlow("onSceneRendered (FSR4 fail)", System.nanoTime() - t0Scene);
                return;
            }

            Fsr4Native.waitIdle();
            copyFsr4OutputToMainRT();

            fsr2FrameCount++;
            hasPrevFrame = true;
            sceneUpscaled = true;
            logSlow("onSceneRendered (FSR4 total)", System.nanoTime() - t0Scene);
            return;
        }

        if (config.version == FSRConfig.FSRVersion.FSR1 || dhActive
            || (isFsr4() && !fsr4Initialized)) {
            if (renderTex == -1) return;
            if (timingFrameCount % 60 == 1) {
                long elapsed = System.nanoTime() - t0Frame;
                FSRMod.LOGGER.info("[PERF] Frame time breakdown: total={}ms scene={}x{} render={}x{} scale={}",
                    elapsed / 1_000_000.0f, displayWidth, displayHeight, scaledWidth, scaledHeight,
                    (float)scaledWidth / displayWidth);
            }
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
                    displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
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
            if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
                sharedTexMgr.attachToPipeline(fsr2Pipeline);
            }
            if (!pipelineReady || fsr2Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                // Compute view-space Z params (same as MV_GEN_CS does for FSR4)
                float p22 = lastProj.m22();
                float p23 = lastProj.m23();
                float farDepth = (p22 > 0) ? 0.0f : 1.0f;
                float d0 = (1.0f - p22) * 0.5f;
                float d1 = -p23 * 0.5f;
                float farViewZ = d1 / (farDepth - d0 + 1e-10f);
                FSRMod.LOGGER.info(String.format(
                    "[DIAG_FSR2] frameIdx=%d jitter=(%.3f,%.3f) prev=(%.3f,%.3f) hasPrev=%s"
                    + " proj22=%.4f proj23=%.4f farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)"
                    + " render=%dx%d display=%dx%d",
                    fsr2FrameCount, jitterX, jitterY, prevJitterX, prevJitterY, hasPrevFrame,
                    p22, p23, farDepth, farViewZ, d0, d1,
                    scaledWidth, scaledHeight, displayWidth, displayHeight));
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
	                fsr2Pipeline.setDebugMode(config.debugMode);
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
            if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
                sharedTexMgr.attachToPipeline(fsr3Pipeline);
            }
            if (!pipelineReady || fsr3Pipeline == null || renderTex == -1 || depthTex == -1) return;

            if (timingFrameCount % 60 == 1) {
                float p22 = lastProj.m22();
                float p23 = lastProj.m23();
                float farDepth = (p22 > 0) ? 0.0f : 1.0f;
                float d0 = (1.0f - p22) * 0.5f;
                float d1 = -p23 * 0.5f;
                float farViewZ = d1 / (farDepth - d0 + 1e-10f);
                FSRMod.LOGGER.info(String.format(
                    "[DIAG_FSR3] frameIdx=%d jitter=(%.3f,%.3f) prev=(%.3f,%.3f) hasPrev=%s"
                    + " proj22=%.4f proj23=%.4f farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)"
                    + " render=%dx%d display=%dx%d",
                    fsr2FrameCount, jitterX, jitterY, prevJitterX, prevJitterY, hasPrevFrame,
                    p22, p23, farDepth, farViewZ, d0, d1,
                    scaledWidth, scaledHeight, displayWidth, displayHeight));
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
	                fsr3Pipeline.setDebugMode(config.debugMode);
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
        if (sharedMemoryActive) {
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        } else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            long t0 = System.nanoTime();
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
            int displayW = displayWidth;
            int displayH = displayHeight;

            // FG: alternate between real frame and interpolated frame
            // On FG frames, dispatch FG and copy interpolated output to mainRT
            // so GUI renders on top of FG content (fixes UI flickering).
            // On real frames, copy the real FSR output.
            if (isFrameGen() && fsr3Pipeline != null && fgFlipState && fsr3Pipeline.hasPreviousFrame()) {
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                fsr3Pipeline.setFgJitter(jitterX, jitterY);
                fsr3Pipeline.setFgTanHalfFovY(tanHalfFovY);
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
            } else {
                GL43C.glCopyImageSubData(
                    fsr2OutputTexId, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    displayW, displayH, 1
                );
            }
            logSlow("glCopyImageSubData (to mainRT)", System.nanoTime() - t0);

            // Always save prev frame data for next FG cycle
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
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

        // FSR1: non-temporal EASU+RCAS pipeline — writes directly to mainRT.
        // Also handles fallback when FSR4 selected but Vulkan init failed.
        if (config.version == FSRConfig.FSRVersion.FSR1 || dhActive
            || (isFsr4() && !fsr4Initialized)) {
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
                boolean ok = fsr1Pipeline.dispatch(renderTex, scaledWidth, scaledHeight, displayWidth, displayHeight, config.sharpness, mainTex, config.fsr1Easu);
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

        if (sharedMemoryActive) {
            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        } else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
            GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();

            // FG fallback (onRenderEnd runs when onSceneRendered didn't dispatch)
            if (isFrameGen() && fsr3Pipeline != null && fgFlipState && fsr3Pipeline.hasPreviousFrame()) {
                float tanHalfFovY = 1.0f / Math.max(lastProj.m11(), 0.001f);
                fsr3Pipeline.setFgJitter(jitterX, jitterY);
                fsr3Pipeline.setFgTanHalfFovY(tanHalfFovY);
                long t0 = System.nanoTime();
                fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId);
                GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT | GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                int fgTex = fsr3Pipeline.getFgOutputTexture();
                if (fgTex != 0) {
                    GL43C.glCopyImageSubData(
                        fgTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
                        displayWidth, displayHeight, 1
                    );
                }
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

            if (isFrameGen() && fsr3Pipeline != null) {
                fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
            }
            fgFlipState = !fgFlipState;
        }

        long frameMs = (System.nanoTime() - t0Frame) / 1_000_000;
        if (frameMs >= 8) {
            FSRMod.LOGGER.warn("[TIMING] frame #{} total={}ms (onRenderEnd={}ms)",
                timingFrameCount, frameMs, (System.nanoTime() - t0End) / 1_000_000);
        }

        // Periodic summary: FSR4 status, frame stats (every 120 frames)
        if (timingFrameCount % 120 == 0) {
            String fsr4State = fsr4Available
                ? (fsr4Initialized ? "INIT" : "FALLBACK(fsr4FramesSkipped=" + fsr4FramesSkipped + ")")
                : "UNAVAIL";
            FSRMod.LOGGER.info(String.format(
                "[DEBUG_SUMMARY] frame=%d FSR4=%s sceneTime=%.1fms render=%dx%d version=%s jitter=(%.3f,%.3f)"
                + " hasPrev=%s frameIdx=%d",
                timingFrameCount, fsr4State, frameMs / 1.0f,
                scaledWidth, scaledHeight, config.version,
                jitterX, jitterY, hasPrevFrame, fsr2FrameCount));
        }

        insideFrame = false;
    }

    private void copyFsr4OutputToMainRT() {
        if (mainRenderTarget == null || sharedTexMgr == null) return;
        int outputTex = sharedTexMgr.getOutputTexId();
        if (outputTex == 0) return;
        int mainTex = ((GlTexture)mainRenderTarget.getColorTexture()).glId();
        if (mainTex == 0) return;
        GL43C.glMemoryBarrier(GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        GL43C.glCopyImageSubData(
            outputTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            mainTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
            displayWidth, displayHeight, 1
        );
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
        if (sharedTexMgr != null) {
            sharedTexMgr.destroy();
            sharedMemoryActive = false;
        }
        if (fsr4Initialized) {
            Fsr4Native.shutdownFsr4();
            fsr4Initialized = false;
        }
        if (sharedTexMgr != null) {
            sharedTexMgr.destroyFsr4Pool();
        }
        fsr4Available = false;
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
        prevJitterX = 0.0f;
        prevJitterY = 0.0f;
        fsr2FrameCount = 0;
        hasPrevFrame = false;
        sceneUpscaled = false;
        fgFlipState = false;
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
        // CRITICAL: Shutdown order matters for D3D12 resource lifetime:
        //   1. Destroy FSR4 SDK context (ffxDestroyContext)
        //   2. Release D3D12 shared resources (pool textures)
        //   3. Dispose D3D12 device (must be LAST — pool textures are
        //      child resources; releasing them after device destruction
        //      accesses freed memory and crashes with 0xC0000409)
        if (fsr4Initialized) {
            Fsr4Native.shutdownFsr4();
            fsr4Initialized = false;
        }
        if (sharedTexMgr != null) {
            sharedTexMgr.destroyFsr4Pool();
            sharedTexMgr.destroy();
        }
        sharedTexMgr = null;
        if (Fsr4Native.isAvailable()) {
            Fsr4Native.disposeD3D12();
        }
        sharedMemoryActive = false;
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

    private boolean ensureDepthCopyShader() {
        if (depthCopyCompiled) return true;
        if (depthCopyProg != 0) { GL43C.glDeleteProgram(depthCopyProg); depthCopyProg = 0; }

        depthCopyProg = ShaderBinaryCache.compileCompute(DEPTH_COPY_CS);
        if (depthCopyProg == 0) {
            FSRMod.LOGGER.error("FSR4 depth copy shader compilation failed");
            return false;
        }

        depthCopy_uDepthSize = GL43C.glGetUniformLocation(depthCopyProg, "uDepthSize");
        depthCopyCompiled = true;
        return true;
    }

    private void copyDepthToShared(int srcDepthTex, int dstDepthTex) {
        if (!ensureDepthCopyShader()) return;
        GL43C.glUseProgram(depthCopyProg);
        GL43C.glUniform2i(depthCopy_uDepthSize, scaledWidth, scaledHeight);

        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, srcDepthTex);
        GL43C.glBindImageTexture(1, dstDepthTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32F);

	        GL43C.glDispatchCompute((scaledWidth + 7) / 8, (scaledHeight + 7) / 8, 1);
	        // Barrier consolidated in caller (onSceneRendered) — one ALL_BARRIER_BITS before glFlush

	        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32F);
	    }

	    private boolean ensureMvGenShader() {
        if (mvGenCompiled) return true;
        if (mvGenProg != 0) { GL43C.glDeleteProgram(mvGenProg); mvGenProg = 0; }

        mvGenProg = ShaderBinaryCache.compileCompute(MV_GEN_CS);
        if (mvGenProg == 0) {
            FSRMod.LOGGER.error("FSR4 MV gen shader compilation failed");
            return false;
        }

        mv_uRenderSize = GL43C.glGetUniformLocation(mvGenProg, "uRenderSize");
        mv_uInvCurrVP = GL43C.glGetUniformLocation(mvGenProg, "uInvCurrVP");
        mv_uPrevVP = GL43C.glGetUniformLocation(mvGenProg, "uPrevVP");
        mv_uJitter = GL43C.glGetUniformLocation(mvGenProg, "uJitter");
        mv_uPrevJitter = GL43C.glGetUniformLocation(mvGenProg, "uPrevJitter");
	        mv_uFarDepth = GL43C.glGetUniformLocation(mvGenProg, "uFarDepth");
	        mv_uDeviceToViewDepth = GL43C.glGetUniformLocation(mvGenProg, "uDeviceToViewDepth");
	        mv_uFarViewZ = GL43C.glGetUniformLocation(mvGenProg, "uFarViewZ");
	        mv_uUseDepth = GL43C.glGetUniformLocation(mvGenProg, "uUseDepth");

	        mvGenCompiled = true;
        return true;
    }

	    private void generateFSR4MotionVectors(int depthTex, int mvTex, int reactiveTex) {
        if (!ensureMvGenShader()) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        tmpQuat.set(camera.rotation()).conjugate();
        tmpView.identity()
            .rotation(tmpQuat)
            .translate(-(float) camPos.x, -(float) camPos.y, -(float) camPos.z);
        currViewProj.set(lastProj);
        currViewProj.mul(tmpView);

	        float p22 = lastProj.m22();
	        float p23 = lastProj.m23();
	        float farDepth = (p22 > 0) ? 0.0f : 1.0f;
	        mvDeviceToViewDepth[0] = (1.0f - p22) * 0.5f;
	        mvDeviceToViewDepth[1] = -p23 * 0.5f;
	        mvDeviceToViewDepth[2] = -1.0f / lastProj.m00();
	        mvDeviceToViewDepth[3] = -1.0f / lastProj.m11();
	        mvFarViewZ = mvDeviceToViewDepth[1] / (farDepth - mvDeviceToViewDepth[0] + 1e-10f);

	        GL43C.glUseProgram(mvGenProg);
	        GL43C.glUniform2i(mv_uRenderSize, scaledWidth, scaledHeight);

        currViewProj.invert(mvTmpMat).get(mvMatBuf);
        GL43C.glUniformMatrix4fv(mv_uInvCurrVP, false, mvMatBuf);

        if (hasPrevFrame) {
            prevViewProj.get(mvMatBuf);
        } else {
            mvTmpMat.identity().get(mvMatBuf);
        }
        GL43C.glUniformMatrix4fv(mv_uPrevVP, false, mvMatBuf);

	        GL43C.glUniform2f(mv_uJitter, jitterX, jitterY);
	        GL43C.glUniform2f(mv_uPrevJitter, prevJitterX, prevJitterY);
	        GL43C.glUniform1f(mv_uFarDepth, farDepth);
	        GL43C.glUniform4fv(mv_uDeviceToViewDepth, mvDeviceToViewDepth);
	        GL43C.glUniform1f(mv_uFarViewZ, mvFarViewZ);
	        GL43C.glUniform1i(mv_uUseDepth, 0);  // 0 = constant ndcZ fallback (depth texture not populated in 1.21.4)

	        GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
	        GL43C.glBindImageTexture(1, mvTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);
	        GL43C.glBindImageTexture(2, reactiveTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

		        GL43C.glDispatchCompute((scaledWidth + 7) / 8, (scaledHeight + 7) / 8, 1);
		        // Barrier consolidated in caller (onSceneRendered) — one ALL_BARRIER_BITS before glFlush

	        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG16F);
	        GL43C.glBindImageTexture(2, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

	        // Debug: sample MV and depth at multiple pixels every 60 frames
	        if (shouldDebug()) {
	            // Save PBO bindings and unbind — Minecraft 1.21.4 may bind pixel
	            // buffers for texture upload/download, which corrupts our
	            // glTexSubImage2D and glReadPixels calls (they interpret the
	            // data pointer as a PBO offset instead of a direct pointer).
	            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
	            int prevUnpackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_UNPACK_BUFFER_BINDING);
	            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
	            if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, 0);
	            try {
	                // Sample 9 points: 3x3 grid
                int stepX = Math.max(1, scaledWidth / 4);
                int stepY = Math.max(1, scaledHeight / 4);
                int[][] samples = {
                    {scaledWidth / 2, scaledHeight / 2},  // center
                    {stepX, stepY}, {stepX * 3, stepY}, {stepX, stepY * 3}, {stepX * 3, stepY * 3},  // inner mid
                    {4, 4}, {scaledWidth - 5, 4}, {4, scaledHeight - 5}, {scaledWidth - 5, scaledHeight - 5}  // corners
                };
                String[] sampleNames = {"CENTER", "M1", "M2", "M3", "M4", "TL", "TR", "BL", "BR"};

                if (debugFbo == -1) debugFbo = GL43C.glGenFramebuffers();

                // ====== ORIGINAL DEPTH READBACK (separate FBO setup) ======
                StringBuilder sbDepth = new StringBuilder("[DEBUG_DEPTH]");
                {
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, debugFbo);
                    // Detach any color attachment to avoid interaction
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                        GL43C.GL_TEXTURE_2D, 0, 0);
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                        GL43C.GL_TEXTURE_2D, depthTex, 0);
                    GL43C.glReadBuffer(GL43C.GL_NONE);
                    boolean depthFboOk = (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE);
                    if (!depthFboOk) {
                        FSRMod.LOGGER.warn("[DEBUG_DEPTH] FBO not complete for depth readback");
                    }
                    if (depthFboOk && (debugPboBuf == null || debugPboBuf.capacity() < 8)) {
                        debugPboBuf = java.nio.ByteBuffer.allocateDirect(8);
                    }
                    for (int si = 0; si < samples.length; si++) {
                        int sx = samples[si][0];
                        int sy = samples[si][1];
                        if (depthFboOk && debugPboBuf != null) {
                            debugPboBuf.clear();
                            debugPboBuf.limit(4);
                            GL43C.glReadPixels(sx, sy, 1, 1, GL43C.GL_DEPTH_COMPONENT, GL43C.GL_FLOAT, debugPboBuf);
                            debugPboBuf.rewind();
                            float d = debugPboBuf.getFloat(0);
                            float vz = mvDeviceToViewDepth[1] / (d - mvDeviceToViewDepth[0] + 1e-10f);
                            sbDepth.append(String.format(" %s=(%.4f,vz=%.1f)", sampleNames[si], d, vz));
                        }
                    }
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);
                }

                // ====== MV READBACK (separate FBO) ======
                StringBuilder sbMV = new StringBuilder("[DEBUG_MV]");
                int nonZeroCount = 0;
                {
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, debugFbo);
                    // Detach depth to avoid stale depth attachment
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                        GL43C.GL_TEXTURE_2D, 0, 0);
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                        GL43C.GL_TEXTURE_2D, mvTex, 0);
                    GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
                    boolean mvFboOk = (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE);
                    if (!mvFboOk) {
                        FSRMod.LOGGER.warn("[DEBUG_MV] FBO not complete for mvTex");
                    }
                    if (mvFboOk && (debugPboBuf == null || debugPboBuf.capacity() < 8)) {
                        debugPboBuf = java.nio.ByteBuffer.allocateDirect(8);
                    }
                    for (int si = 0; si < samples.length; si++) {
                        int sx = samples[si][0];
                        int sy = samples[si][1];
                        if (mvFboOk && debugPboBuf != null) {
                            debugPboBuf.clear();
                            debugPboBuf.limit(8);
                            GL43C.glReadPixels(sx, sy, 1, 1, GL43C.GL_RG, GL43C.GL_FLOAT, debugPboBuf);
                            debugPboBuf.rewind();
                            float mvx = debugPboBuf.getFloat(0);
                            float mvy = debugPboBuf.getFloat(4);
                            sbMV.append(String.format(" %s=(%.1f,%.1f)", sampleNames[si], mvx, mvy));
                            if (Math.abs(mvx) > 0.01f || Math.abs(mvy) > 0.01f) nonZeroCount++;
                        }
                    }
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);
                }

                sbMV.append(String.format(" nonZero=%d/%d hasPrev=%s farDepth=%.0f farViewZ=%.1f dtod=(%.4f,%.4f)",
                    nonZeroCount, samples.length, hasPrevFrame, farDepth, mvFarViewZ,
                    mvDeviceToViewDepth[0], mvDeviceToViewDepth[1]));
                FSRMod.LOGGER.info(sbMV.toString());
                FSRMod.LOGGER.info(sbDepth.toString());

                // ====== SHARED DEPTH READBACK (separate FBO) ======
                float sharedDepth = -1.0f;
                if (sharedTexMgr != null && sharedTexMgr.getDepthTexId() != 0) {
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, debugFbo);
                    // Detach everything first
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT,
                        GL43C.GL_TEXTURE_2D, 0, 0);
                    GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                        GL43C.GL_TEXTURE_2D, sharedTexMgr.getDepthTexId(), 0);
                    GL43C.glReadBuffer(GL43C.GL_COLOR_ATTACHMENT0);
                    if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                        if (debugPboBuf == null || debugPboBuf.capacity() < 4) {
                            debugPboBuf = java.nio.ByteBuffer.allocateDirect(4);
                        }
                        debugPboBuf.clear();
                        debugPboBuf.limit(4);
                        GL43C.glReadPixels(scaledWidth / 2, scaledHeight / 2, 1, 1, GL43C.GL_RED, GL43C.GL_FLOAT, debugPboBuf);
                        debugPboBuf.rewind();
                        sharedDepth = debugPboBuf.getFloat(0);
                    }
                    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);
                }
                FSRMod.LOGGER.info(String.format("[DEBUG_SHARED_DEPTH] center=%.4f (R32F copy)", sharedDepth));

                // Log VP matrix diagnostics
                Matrix4f invCurrVP = currViewProj.invert(new Matrix4f());
                float[] invCurrVPArr = new float[16];
                invCurrVP.get(invCurrVPArr);
                float[] prevVPArr = new float[16];
                prevViewProj.get(prevVPArr);
                Vec3 camPos2 = Minecraft.getInstance().gameRenderer.getMainCamera().position();
                FSRMod.LOGGER.info(String.format(
                    "[DEBUG_VP] hasPrev=%s invCurrVP[0]=(%.4f,%.4f,%.4f,%.4f)"
                    + " prevVP[0]=(%.4f,%.4f,%.4f,%.4f)"
                    + " camPos=(%.1f,%.1f,%.1f) camRot=(%.2f,%.2f,%.2f)",
                    hasPrevFrame,
                    invCurrVPArr[0], invCurrVPArr[1], invCurrVPArr[2], invCurrVPArr[3],
                    prevVPArr[0], prevVPArr[1], prevVPArr[2], prevVPArr[3],
                    camPos2.x, camPos2.y, camPos2.z,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().x,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().y,
                    Minecraft.getInstance().gameRenderer.getMainCamera().rotation().z));

                // Verify GL error state
                int glErr = GL43C.glGetError();
                if (glErr != GL43C.GL_NO_ERROR) {
                    FSRMod.LOGGER.warn(String.format("[DEBUG_GL] Error after readbacks: 0x%X", glErr));
                }

                // Diagnostic: check depth texture format
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, depthTex);
                int depthFormat = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_INTERNAL_FORMAT);
                int depthWidth = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_WIDTH);
                int depthHeight = GL43C.glGetTexLevelParameteri(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_TEXTURE_HEIGHT);
                FSRMod.LOGGER.info(String.format("[DEBUG_TEX] depthTex=%d format=0x%X size=%dx%d renderTarget=%dx%d",
                    depthTex, depthFormat, depthWidth, depthHeight, scaledWidth, scaledHeight));

	
            } catch (Exception e) {
                FSRMod.LOGGER.error("[DEBUG_MV] readback failed", e);
            } finally {
                // Restore PBO bindings
                if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
                if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, prevUnpackBuf);
            }
        }

        prevViewProj.set(currViewProj);
        hasPrevFrame = true;
    }

    private boolean ensureReactiveMaskShader() {
        if (reactiveMaskCompiled) return true;
        if (reactiveMaskProg != 0) { GL43C.glDeleteProgram(reactiveMaskProg); reactiveMaskProg = 0; }

        reactiveMaskProg = ShaderBinaryCache.compileCompute(REACTIVE_MASK_CS);
        if (reactiveMaskProg == 0) {
            FSRMod.LOGGER.error("FSR4 reactive mask shader compilation failed");
            return false;
        }

        reactiveMask_uRenderSize = GL43C.glGetUniformLocation(reactiveMaskProg, "uRenderSize");
        reactiveMaskCompiled = true;
        return true;
    }

    private void generateFsr4ReactiveMask(int mvTex, int reactiveTex) {
        if (!ensureReactiveMaskShader()) return;

        GL43C.glUseProgram(reactiveMaskProg);
        GL43C.glUniform2i(reactiveMask_uRenderSize, scaledWidth, scaledHeight);

        // Read MV from the shared MV texture (already filled by generateFSR4MotionVectors)
        GL43C.glBindImageTexture(0, mvTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_RG16F);
        // Write reactive to the shared reactive texture (R8)
        GL43C.glBindImageTexture(1, reactiveTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

	        GL43C.glDispatchCompute((scaledWidth + 7) / 8, (scaledHeight + 7) / 8, 1);
	        // Barrier consolidated in caller (onSceneRendered) — one ALL_BARRIER_BITS before glFlush

	        GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_RG16F);
        GL43C.glBindImageTexture(1, 0, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

        // Debug: sample reactive mask center pixel every 60 frames
        if (shouldDebug()) {
            int prevPackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_PACK_BUFFER_BINDING);
            int prevUnpackBuf = GL43C.glGetInteger(GL43C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, 0);
            if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, 0);
            try {
                int cx = scaledWidth / 2;
                int cy = scaledHeight / 2;
                if (debugFbo == -1) debugFbo = GL43C.glGenFramebuffers();
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, debugFbo);
                GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                    GL43C.GL_TEXTURE_2D, reactiveTex, 0);
                if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                    if (debugPboBuf == null || debugPboBuf.capacity() < 4) {
                        debugPboBuf = java.nio.ByteBuffer.allocateDirect(4);
                    }
                    debugPboBuf.clear();
                    debugPboBuf.limit(4);
                    GL43C.glReadPixels(cx, cy, 1, 1, GL43C.GL_RED, GL43C.GL_UNSIGNED_BYTE, debugPboBuf);
                    debugPboBuf.rewind();
                    float reactiveVal = (debugPboBuf.get(0) & 0xFF) / 255.0f;
                    if (reactiveVal > 0.01f) {
                        FSRMod.LOGGER.info(String.format("[DEBUG_REACTIVE] center=%.3f (>0)", reactiveVal));
                    } else {
                        FSRMod.LOGGER.warn(String.format("[DEBUG_REACTIVE] center=%.3f (ZERO!) size=%dx%d", reactiveVal, scaledWidth, scaledHeight));
                        // Check MV at same pixel — need bigger buffer for RG float read
                        if (debugPboBuf == null || debugPboBuf.capacity() < 8) {
                            debugPboBuf = java.nio.ByteBuffer.allocateDirect(8);
                        }
                        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
                            GL43C.GL_TEXTURE_2D, mvTex, 0);
                        if (GL43C.glCheckFramebufferStatus(GL43C.GL_READ_FRAMEBUFFER) == GL43C.GL_FRAMEBUFFER_COMPLETE) {
                            debugPboBuf.clear();
                            debugPboBuf.limit(8);
                            GL43C.glReadPixels(cx, cy, 1, 1, GL43C.GL_RG, GL43C.GL_FLOAT, debugPboBuf);
                            debugPboBuf.rewind();
                            float mvx = debugPboBuf.getFloat(0);
                            float mvy = debugPboBuf.getFloat(4);
                            float mvMag = (float) Math.sqrt(mvx * mvx + mvy * mvy);
                            FSRMod.LOGGER.warn(String.format("[DEBUG_REACTIVE] MV at same pixel: (%.1f,%.1f) mag=%.1f",
                                mvx, mvy, mvMag));
                        }
                    }
                }
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, 0);
            } catch (Exception e) {
                FSRMod.LOGGER.error("[DEBUG_REACTIVE] readback failed", e);
            } finally {
                if (prevPackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_PACK_BUFFER, prevPackBuf);
                if (prevUnpackBuf != 0) GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, prevUnpackBuf);
            }
        }
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
