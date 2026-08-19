package com.fsr2mod.compat;

import com.fsr2mod.FSRMod;
import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.vulkan.VulkanInterop;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * VulkanMod detection and interop for FSR4 same-device copy path.
 *
 * All native methods are delegated to {@link VulkanInterop} to avoid
 * duplicate native library loading.
 */
public class VulkanModCompat {
    private static Boolean vulkanModLoaded;
    private static Boolean externalMemorySupported;
    private static Boolean active;

    // Render-scale state (POC, 2026-08-05): FSRProcessor sets this from
    // config.vulkanModRenderScale + qualityScale each frame in the VKMod block;
    // the main-pass mixin reads it to decide whether to swap the main framebuffer
    // for a scaled one and record the blit instead of the D3D12 copies.
    private static volatile float renderScale = 1.0f;   // 1.0 = disabled (native res)

    public static void setRenderScale(float scale) {
        renderScale = Math.max(0.25f, Math.min(1.0f, scale));
    }

    /**
     * Whether the user has render scale ENABLED in config (regardless of whether
     * it's currently active — the mixin's end() hook must skip the D3D12 FSR4
     * copy path whenever render scale is configured, even on the title screen
     * where it's temporarily inactive because no world is loaded).
     */
    public static boolean renderScaleConfigured() {
        FSRMod mod = FSRMod.getInstance();
        if (mod != null && mod.getConfig() != null) {
            FSRConfig cfg = mod.getConfig();
            // enabled added 2026-08-06: with FSR off the scaled framebuffer must
            // NOT (re)engage — the createResources FIELD hook gates on this via
            // renderScaleActive(), so the disable-time recreate lands on native.
            return cfg.enabled && cfg.vulkanModRenderScale && cfg.qualityScale < 1.0f;
        }
        return false;
    }

    /**
     * Whether the render-scale presentation branch may be live — deliberately
     * ENABLED-INDEPENDENT (unlike {@link #renderScaleConfigured()}). The end()
     * hook uses this so it keeps presenting the scaled framebuffer (via the
     * safe bilinear blit) during the FSR-disable transition frame, before the
     * scheduled swapchain recreate back to native lands. With FSR off the
     * framebuffer may still be scaled for one frame; skipping presentation
     * entirely would leave the swapchain on stale content (the freeze).
     */
    public static boolean renderScaleSwapped() {
        FSRMod mod = FSRMod.getInstance();
        if (mod != null && mod.getConfig() != null) {
            FSRConfig cfg = mod.getConfig();
            return cfg.vulkanModRenderScale && cfg.qualityScale < 1.0f;
        }
        return false;
    }

    /**
     * Whether render scale is currently ACTIVE = configured AND in-game. The
     * scaled-framebuffer swap must apply ONLY to the 3D world — menus, the title
     * screen, and the world-select GUI render at native res (2026-08-05 fix:
     * "everything is upscaled including the title screen").
     */
    public static boolean renderScaleActive() {
        if (!renderScaleConfigured()) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null;
    }

    /**
     * Asks VulkanMod to recreate its swapchain + main pass at the next frame
     * start (Renderer.scheduleSwapChainUpdate → recreateSwapChain →
     * mainPass.onResize → createResources → our mixin FIELD hook re-evaluates the
     * scaled-framebuffer swap with the current in-game state). Called by
     * FSRProcessor.onRenderBegin when the in-game state flips (title ↔ world) so
     * the framebuffer swap engages only while in a world.
     */
    public static void scheduleRenderScaleUpdate() {
        if (!isLoaded()) return;
        try {
            Class<?> r = Class.forName("net.vulkanmod.vulkan.Renderer");
            r.getMethod("scheduleSwapChainUpdate").invoke(null);
        } catch (Exception e) {
            // Ignore — worst case the swap stays as-is until the next resize.
        }
    }

    public static float getRenderScale() {
        // Prefer the LIVE config: the main-pass mixin's createResources hook runs
        // during VulkanMod's Renderer.init() — BEFORE the first onRenderBegin ever
        // calls setRenderScale() — so the static alone is still the 1.0 default
        // there and the scaled-framebuffer swap silently never happens (the world
        // renders at native res = "identical to regular Minecraft", 2026-08-05).
        // The config is loaded in FSRMod.onInitializeClient (mod init, before any
        // rendering), so reading it directly is order-independent. NOTE the static
        // is NOT an override: whenever config is enabled it WINS and the static is
        // ignored (onRenderBegin's per-frame setRenderScale is a no-op for the
        // enabled path). The static is only consulted when config is disabled or
        // unavailable. Also note the lower-only clamp here is safe because the
        // guard already requires qualityScale < 1.0f — if that guard changes,
        // keep the two clamps in sync.
        FSRMod mod = FSRMod.getInstance();
        if (mod != null && mod.getConfig() != null) {
            FSRConfig cfg = mod.getConfig();
            if (cfg.vulkanModRenderScale && cfg.qualityScale < 1.0f) {
                return Math.max(0.25f, cfg.qualityScale);
            }
        }
        return renderScale;
    }

    public static boolean isLoaded() {
        if (vulkanModLoaded == null) {
            vulkanModLoaded = FabricLoader.getInstance().isModLoaded("vulkanmod");
        }
        return vulkanModLoaded;
    }

    /**
     * Whether FSR4 interop with VulkanMod is fully operational.
     * Requires: VulkanMod loaded + device supports external memory win32 + native DLL loaded.
     */
    public static boolean isActive() {
        if (active != null) return active;
        active = VulkanInterop.isAvailable() && isLoaded() && isExternalMemorySupported();
        return active;
    }

    /**
     * Check if VulkanMod's physical device supports the external memory extensions.
     */
    public static boolean isExternalMemorySupported() {
        if (externalMemorySupported != null) return externalMemorySupported;
        if (!isLoaded() || !VulkanInterop.isAvailable()) {
            externalMemorySupported = false;
            return false;
        }
        try {
            externalMemorySupported = VulkanInterop.checkExternalMemorySupport();
        } catch (Exception e) {
            externalMemorySupported = false;
        }
        return externalMemorySupported;
    }

    public static void logStatus() {
        if (isLoaded()) {
            FSRMod.LOGGER.info("VulkanMod detected — Vulkan rendering backend in use");
            if (isExternalMemorySupported()) {
                FSRMod.LOGGER.info("  VK_KHR_external_memory_win32: supported — FSR4 interop available");
            } else {
                FSRMod.LOGGER.info("  VK_KHR_external_memory_win32: NOT supported — FSR4 interop unavailable");
            }
        }
    }

    // ========== Delegated methods ==========

    /** @see VulkanInterop#setVulkanModDevice(long, long) */
    public static void setVulkanModDevice(long devicePtr, long physDevicePtr) {
        VulkanInterop.setVulkanModDevice(devicePtr, physDevicePtr);
    }

    /** @see VulkanInterop#setSwapchainFormat(boolean) */
    public static void setSwapchainFormat(boolean isBGRA) {
        if (VulkanInterop.isAvailable()) {
            VulkanInterop.setSwapchainFormat(isBGRA);
        }
    }

    /** @see VulkanInterop#initExternalImages(int, int, int, int) */
    public static boolean initExternalImages(int renderW, int renderH, int dispW, int dispH) {
        if (!VulkanInterop.isAvailable()) return false;
        return VulkanInterop.initExternalImages(renderW, renderH, dispW, dispH);
    }

    /** @see VulkanInterop#destroyExternalImages() */
    public static void destroyExternalImages() {
        if (VulkanInterop.isAvailable()) {
            VulkanInterop.destroyExternalImages();
        }
    }

    /** @see VulkanInterop#recordFrameCopyOps(long, long, int, int) */
    public static void recordFrameCopyOps(long cmdBuf, long swapchainImage, int width, int height) {
        VulkanInterop.recordFrameCopyOps(cmdBuf, swapchainImage, width, height);
    }

    /** @see VulkanInterop#recordVkModFsr4ScaledCopy(long, long, int, int, long, int, int, boolean) */
    public static void recordVkModFsr4ScaledCopy(long cmdBuf, long srcImage, int srcW, int srcH, long dstImage, int dstW, int dstH, boolean includeCopy2) {
        VulkanInterop.recordVkModFsr4ScaledCopy(cmdBuf, srcImage, srcW, srcH, dstImage, dstW, dstH, includeCopy2);
    }

    /**
     * True once recordFrameCopyOps + recordVkModMvGen have recorded at least
     * one frame's worth of inputs into the D3D12 pool (set by the main-pass
     * mixin end() hook, cleared on init/recreate). FSRProcessor gates the VKMod
     * FSR4 dispatch on this: on the VERY FIRST frame the pool[0] color texture
     * is undefined (Copy1 hasn't run yet — it happens later in the first end()
     * hook), so dispatching would feed the FSR4 SDK garbage input and present
     * the resulting garbage via Copy2 (review fix 2026-08-05).
     */
    public static volatile boolean fsr4InputPopulated = false;

    /**
     * True while the FSR4 D3D12 path is initialized AND active in the current
     * VKMod session (set by FSRProcessor after a successful VulkanMod FSR4
     * init, cleared on shutdown/failure). The main-pass mixin uses this to
     * choose the REAL FSR4 upscale path (recordVkModFsr4ScaledCopy) over the
     * bilinear blit when render-scale is also configured — without it,
     * render-scale mode fell back to a LINEAR blit ("FSR4 looks like FSR1" —
     * it literally WAS a bilinear upscale, worse than FSR1).
     */
    public static volatile boolean fsr4VkModActive = false;

    /** @see VulkanInterop#storeFrameFence(long) */
    public static void storeFrameFence(long fence) {
        if (VulkanInterop.isAvailable()) {
            VulkanInterop.storeFrameFence(fence);
        }
    }

    /** @see VulkanInterop#waitForFrameCopyBounded() */
    public static boolean waitForFrameCopy() {
        if (VulkanInterop.isAvailable()) {
            return VulkanInterop.waitForFrameCopyBounded();
        }
        return true;  // native unavailable — nothing to wait on
    }

    /** @see VulkanInterop#waitForD3D12Output() */
    public static boolean waitForD3D12Output() {
        if (VulkanInterop.isAvailable()) {
            return VulkanInterop.waitForD3D12Output();
        }
        return true;  // native unavailable — nothing to wait on, treat as ready
    }

	    /** @see VulkanInterop#recordRenderScaleBlit(long, long, int, int, long, int, int) */
	    public static void recordRenderScaleBlit(long cmdBuf, long srcImage, int srcW, int srcH, long dstImage, int dstW, int dstH) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.recordRenderScaleBlit(cmdBuf, srcImage, srcW, srcH, dstImage, dstW, dstH);
	        }
	    }
	
	    /** @see VulkanInterop#importInputFromD3D12Pool(long, int) */
	    public static boolean importInputFromD3D12Pool(long poolPtr, int colorIndex) {
	        return VulkanInterop.importInputFromD3D12Pool(poolPtr, colorIndex);
	    }

	    /** @see VulkanInterop#importPoolTextureAsVkImage(long, int, int, int) */
	    public static boolean importPoolTextureAsVkImage(long poolPtr, int poolIndex, int slot, int vkFormat) {
	        if (!VulkanInterop.isAvailable()) return false;
	        return VulkanInterop.importPoolTextureAsVkImage(poolPtr, poolIndex, slot, vkFormat);
	    }

	    /** @see VulkanInterop#recordVkModMvGen(long) */
	    public static void recordVkModMvGen(long cmdBuf) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.recordVkModMvGen(cmdBuf);
	        }
	    }

	    /** @see VulkanInterop#setVkModDepthSource(long, int, int, int) */
	    public static void setVkModDepthSource(long image, int format, int width, int height) {
	        if (isLoaded() && VulkanInterop.isAvailable()) {
	            VulkanInterop.setVkModDepthSource(image, format, width, height);
	        }
	    }

	    /** @see VulkanInterop#setVkModColorSource(long, int, int, int) */
	    public static void setVkModColorSource(long image, int format, int width, int height) {
	        if (isLoaded() && VulkanInterop.isAvailable()) {
	            VulkanInterop.setVkModColorSource(image, format, width, height);
	        }
	    }

	    /** @see VulkanInterop#setVkModMvGenMatrices(float[], float[]) */
	    public static void setVkModMvGenMatrices(float[] invCurrVP, float[] prevVP) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.setVkModMvGenMatrices(invCurrVP, prevVP);
	        }
	    }

	    /** @see VulkanInterop#setVkModCamMotion(float, float) */
	    public static void setVkModCamMotion(float eyeSpeed, float rotDelta) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.setVkModCamMotion(eyeSpeed, rotDelta);
	        }
	    }
	
	    /** @see VulkanInterop#importOutputFromD3D12Pool(long, int) */
    public static boolean importOutputFromD3D12Pool(long poolPtr, int outputIndex) {
        return VulkanInterop.importOutputFromD3D12Pool(poolPtr, outputIndex);
    }

	    /** @see VulkanInterop#importFgPoolTextureAsVkImage(long, int, int, int) */
	    public static boolean importFgPoolTextureAsVkImage(long poolPtr, int poolIndex, int slot, int vkFormat) {
	        if (!VulkanInterop.isAvailable()) return false;
	        return VulkanInterop.importFgPoolTextureAsVkImage(poolPtr, poolIndex, slot, vkFormat);
	    }

	    /** @see VulkanInterop#recordVkModSwapchainToFgPresentCopy(long, long, int, int) */
	    public static void recordVkModSwapchainToFgPresentCopy(long cmdBuf, long swapchainImage, int width, int height) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.recordVkModSwapchainToFgPresentCopy(cmdBuf, swapchainImage, width, height);
	        }
	    }

	    /** @see VulkanInterop#recordVkModFgOutputCopyOneShot(long, int, int) */
	    public static void recordVkModFgOutputCopyOneShot(long dstImage, int width, int height) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.recordVkModFgOutputCopyOneShot(dstImage, width, height);
	        }
	    }

	    /** @see VulkanInterop#acquireVkModFgImage(long) */
	    public static int acquireVkModFgImage(long swapchain) {
	        if (!VulkanInterop.isAvailable()) return -1;
	        return VulkanInterop.acquireVkModFgImage(swapchain);
	    }

	    /** @see VulkanInterop#presentVkModFgImage(long, long, int) */
	    public static void presentVkModFgImage(long presentQueue, long swapchain, int imageIndex) {
	        if (VulkanInterop.isAvailable()) {
	            VulkanInterop.presentVkModFgImage(presentQueue, swapchain, imageIndex);
	        }
	    }

    /** @see VulkanInterop#getImportedInputResourcePtr() */
    public static long getImportedInputResourcePtr() {
        return VulkanInterop.getImportedInputResourcePtr();
    }

    /** @see VulkanInterop#getImportedOutputResourcePtr() */
    public static long getImportedOutputResourcePtr() {
        return VulkanInterop.getImportedOutputResourcePtr();
    }

    /** @see VulkanInterop#setDebugPipelineTest(boolean) */
    public static void setDebugPipelineTest(boolean enabled) {
        VulkanInterop.setDebugPipelineTest(enabled);
    }

    /** @see VulkanInterop#dispatchFsr4VkMod(float, float, float, float, float, float, int, boolean, long, int, long, int, long, int, long, int) */
    public static boolean dispatchFsr4VkMod(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovY,
        float deltaTime, int frameIndex, boolean reset,
        long depthPoolPtr, int depthIndex,
        long mvPoolPtr, int mvIndex,
        long reactivePoolPtr, int reactiveIndex,
        long outputPoolPtr, int outputIndex
    ) {
        return VulkanInterop.dispatchFsr4VkMod(
            jitterX, jitterY,
            cameraFar, cameraNear, cameraFovY,
            deltaTime, frameIndex, reset,
            depthPoolPtr, depthIndex,
            mvPoolPtr, mvIndex,
            reactivePoolPtr, reactiveIndex,
            outputPoolPtr, outputIndex
        );
    }
}
