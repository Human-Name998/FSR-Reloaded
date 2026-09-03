package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FSR3_FG_DX12 step 2 (2026-08-15): the extra frame-generation present under
 * VulkanMod.
 *
 * Renderer.endFrame() submits the main command buffer (scene + FSR4 copies +
 * the swapchain -> pool 6 GUI capture) and presents the real frame via
 * submitFrame(). This TAIL hook runs AFTER that present: the main submission's
 * fence is the stored per-frame fence, so FSRProcessor.presentVkModFgFrame()
 * can wait it (making pool 6 / pool 4 valid), run the FG generate, copy the
 * generated frame (pool 5) into a second acquired swapchain image, and present
 * it — doubling the display frame rate (real frame first, generated frame
 * second, matching the GL path's double-present semantics).
 *
 * Everything is failure-tolerant: any exception or fence timeout skips the FG
 * present and the real frame still presents normally (single-present).
 */
@Mixin(Renderer.class)
public class VulkanModRendererMixin {

    @Inject(method = "endFrame", at = @At("TAIL"), remap = false)
    private void fsrFgAfterEndFrame(CallbackInfo ci) {
        try {
            FSRProcessor proc = FSRMod.getInstance().getProcessor();
            if (proc != null) proc.presentVkModFgFrame();
        } catch (Throwable t) {
            // Never break VulkanMod's frame loop.
        }
    }
}
