package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    /**
     * Second half of the FG_DX12 double-present. RenderSystem.flipFrame is where
     * the vanilla render loop actually presents (GpuDevice.presentFrame ->
     * glfwSwapBuffers). After it returns, the generated FG frame has been shown;
     * FSRProcessor.onAfterSwapBuffers() then blits the REAL FSR4 frame into mainRT
     * and calls flipFrame again — presenting two images per render loop and
     * doubling display FPS. The nested flipFrame re-enters this injector, but
     * FSRProcessor clears the pending flag before calling it, so the re-entry is
     * a no-op.
     */
    @Inject(method = "flipFrame", at = @At("TAIL"))
    private static void onFlipFrameTail(TracyFrameCapture capture, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onAfterSwapBuffers();
        }
    }
}
