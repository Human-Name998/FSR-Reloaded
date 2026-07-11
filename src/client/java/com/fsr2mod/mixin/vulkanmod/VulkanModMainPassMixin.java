package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.compat.VulkanModCompat;
import com.fsr2mod.FSRMod;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks VulkanMod's DefaultMainPass.end() to inject vkCmdCopyImage operations
 * into the SAME command buffer, BEFORE vkEndCommandBuffer is called.
 *
 * At this injection point the render pass has already ended but the
 * command buffer is still recording, so we can safely record additional
 * transfer commands.
 */
@Mixin(DefaultMainPass.class)
public class VulkanModMainPassMixin {

    @Inject(
        method = "end",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkEndCommandBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;)I",
            shift = At.Shift.BEFORE
        ),
        remap = false
    )
    private static void beforeVkEndCommandBuffer(CallbackInfo ci) {
        if (!VulkanModCompat.isActive()) return;

        try {
            // Get command buffer address from Renderer (same one passed to MainPass.end)
            Object cmdBufObj;
            try {
                cmdBufObj = Renderer.class.getMethod("getCommandBuffer").invoke(null);
            } catch (Exception e) {
                return;
            }
            if (cmdBufObj == null) return;
            long cmdBuf = (long) cmdBufObj.getClass().getMethod("address").invoke(cmdBufObj);
            if (cmdBuf == 0) return;

            // Get current swapchain image
            Renderer renderer = Renderer.getInstance();
            SwapChain swapChain = renderer.getSwapChain();
            if (swapChain == null || !swapChain.hasImages()) return;

            int imageIdx = Renderer.getCurrentImage();
            if (imageIdx < 0) return;

            long swapchainImage = swapChain.getImageId(imageIdx);
            int w = swapChain.getWidth();
            int h = swapChain.getHeight();

            if (swapchainImage == 0 || w <= 0 || h <= 0) return;

            // Record copy operations into VulkanMod's command buffer.
            // The command buffer is still recording at this point.
            VulkanModCompat.recordFrameCopyOps(cmdBuf, swapchainImage, w, h);

        } catch (Exception e) {
            FSRMod.LOGGER.warn("VulkanMod copy: {}", e.getMessage());
        }
    }
}
