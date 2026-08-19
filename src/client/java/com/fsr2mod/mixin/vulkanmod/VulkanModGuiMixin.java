package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.vulkan.VulkanModUiHelper;
import net.minecraft.client.gui.render.GuiRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NATIVE-RES UI (2026-08-10): renders the GUI at native resolution instead of
 * letting FSR4 upscale it with the world.
 *
 * Under VulkanMod's render-scale path the world AND the GUI render into the
 * single scaled framebuffer, then the MainPass.end hook FSR4-upscales the whole
 * composite — so the HUD/chat/screens get upscaled (blurry) too. The GL path
 * keeps the GUI crisp by upscaling the world FIRST, then drawing the GUI at
 * native res on top.
 *
 * This mixin mirrors that: at GuiRenderer.render HEAD it
 * (a) ends the world's main render pass,
 * (b) records the FSR4 copies (world-only scaled-fb -> pool input, MV-gen,
 *     pool output -> swapchain) via {@link VulkanModUiHelper}, and
 * (c) swaps DefaultMainPass.mainFramebuffer to the SWAPCHAIN (native res) so
 *     the GUI's aux render pass binds the current swapchain image with
 *     LOAD_OP_LOAD — the upscaled world is preserved underneath and the GUI
 *     composites over it at native resolution. At render RETURN it swaps the
 *     framebuffer back to the scaled one and records the swapchain
 *     COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC transition. The MainPass.end
 *     hook sees copiesRecordedThisFrame and skips re-recording.
 *
 * Any failure fails safe to the old behavior (GUI renders into the scaled fb
 * and gets upscaled) — never a blank frame.
 */
@Mixin(GuiRenderer.class)
public class VulkanModGuiMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void fsrNativeResUiBeforeRender(CallbackInfo ci) {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer == null) return;
            long cmdBuf = getCommandBufferAddress();
            if (cmdBuf == 0) return;
            VulkanModUiHelper.beginNativeResGui(renderer, cmdBuf);
        } catch (Throwable t) {
            // Never break the GUI render.
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void fsrNativeResUiAfterRender(CallbackInfo ci) {
        try {
            if (!VulkanModUiHelper.swapActive) return;
            Renderer renderer = Renderer.getInstance();
            if (renderer == null) return;
            long cmdBuf = getCommandBufferAddress();
            if (cmdBuf == 0) return;
            VulkanModUiHelper.endNativeResGui(renderer, cmdBuf);
        } catch (Throwable t) {
            // Never break the GUI render.
        }
    }

    /** LWJGL's VkCommandBuffer isn't on the client compile classpath — get the
     *  command buffer address via reflection (mirrors
     *  VulkanModMainPassMixin.beforeVkEndCommandBuffer). */
    private static long getCommandBufferAddress() {
        try {
            Object cmdBufObj = Renderer.class.getMethod("getCommandBuffer").invoke(null);
            if (cmdBufObj == null) return 0;
            return (long) cmdBufObj.getClass().getMethod("address").invoke(cmdBufObj);
        } catch (Exception e) {
            return 0;
        }
    }
}
