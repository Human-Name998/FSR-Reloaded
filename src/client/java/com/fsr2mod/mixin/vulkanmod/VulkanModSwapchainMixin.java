package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.compat.VulkanModCompat;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds VK_IMAGE_USAGE_TRANSFER_SRC_BIT to VulkanMod's swapchain images
 * and captures the swapchain pixel format for external image creation.
 * Without TRANSFER_SRC_BIT we cannot vkCmdCopyImage FROM the swapchain
 * image to our external-memory image for FSR4 interop.
 */
@Mixin(SwapChain.class)
public class VulkanModSwapchainMixin {

    @Shadow
    public boolean isBGRAformat;

    @ModifyArg(
        method = "createSwapChain",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageUsage(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"
        ),
        index = 0,
        remap = false
    )
    private int addTransferSrcUsage(int usage) {
        // OR in VK_IMAGE_USAGE_TRANSFER_SRC_BIT (0x1) for copy-from-swapchain support
        return usage | 0x1;
    }

    /**
     * After the swapchain is created, store the pixel format for use when
     * creating external-memory images (must match swapchain format for
     * vkCmdCopyImage to work).
     */
    @Inject(
        method = "createSwapChain",
        at = @At("TAIL"),
        remap = false
    )
    private void afterCreateSwapChain(CallbackInfo ci) {
        VulkanModCompat.setSwapchainFormat(this.isBGRAformat);
    }
}
