package com.fsr2mod.compat;

import com.fsr2mod.FSRMod;
import com.fsr2mod.vulkan.VulkanInterop;
import net.fabricmc.loader.api.FabricLoader;

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

    /** @see VulkanInterop#initExternalImages(int, int) */
    public static boolean initExternalImages(int width, int height) {
        if (!VulkanInterop.isAvailable()) return false;
        return VulkanInterop.initExternalImages(width, height);
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

    /** @see VulkanInterop#waitForFrameCopy() */
    public static void waitForFrameCopy() {
        if (VulkanInterop.isAvailable()) {
            VulkanInterop.waitForFrameCopy();
        }
    }

    /** @see VulkanInterop#exportInputToD3D12() */
    public static boolean exportInputToD3D12() {
        return VulkanInterop.exportInputToD3D12();
    }

    /** @see VulkanInterop#importOutputFromD3D12Pool(long, int) */
    public static boolean importOutputFromD3D12Pool(long poolPtr, int outputIndex) {
        return VulkanInterop.importOutputFromD3D12Pool(poolPtr, outputIndex);
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
