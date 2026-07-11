package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.compat.VulkanModCompat;
import net.vulkanmod.vulkan.Vulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds VK_KHR_external_memory_win32 to VulkanMod's device extension list
 * before the logical device is created. This enables us to allocate
 * exportable memory on VulkanMod's device for D3D12 interop.
 *
 * The extension is harmless to add — it only enables a capability that
 * we never need to exercise through VulkanMod's own rendering paths.
 */
@Mixin(Vulkan.class)
public class VulkanModDeviceMixin {

    @Inject(method = "initVulkan", at = @At("HEAD"), remap = false)
    private static void addExternalMemoryExtensions(long window, CallbackInfo ci) {
        // REQUIRED_EXTENSION is the source set copied into VkDeviceCreateInfo.ppEnabledExtensionNames
        Vulkan.REQUIRED_EXTENSION.add("VK_KHR_external_memory");
        Vulkan.REQUIRED_EXTENSION.add("VK_KHR_external_memory_win32");
    }

    /**
     * After VulkanMod creates its logical device, store VkDevice and VkPhysicalDevice
     * pointers in native code for external memory allocation.
     */
    @Inject(method = "initVulkan", at = @At("TAIL"), remap = false)
    private static void afterInitVulkan(long window, CallbackInfo ci) {
        // Use reflection to get native handle addresses from LWJGL objects
        long devicePtr = getHandleAddress("net.vulkanmod.vulkan.device.DeviceManager", "vkDevice");
        long physDevicePtr = getHandleAddress("net.vulkanmod.vulkan.device.DeviceManager", "physicalDevice");

        if (devicePtr != 0 && physDevicePtr != 0) {
            VulkanModCompat.setVulkanModDevice(devicePtr, physDevicePtr);
        }
        VulkanModCompat.logStatus();
    }

    /**
     * Get the native address of an LWJGL dispatchable handle via reflection.
     * LWJGL handles (VkDevice, VkPhysicalDevice, etc.) have an {@code address()} method
     * that returns the native pointer value as a long.
     */
    private static long getHandleAddress(String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            Object handle = clazz.getField(fieldName).get(null);
            if (handle == null) return 0;
            return (long) handle.getClass().getMethod("address").invoke(handle);
        } catch (Exception e) {
            return 0;
        }
    }
}
