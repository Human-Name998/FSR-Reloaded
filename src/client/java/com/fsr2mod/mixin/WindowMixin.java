package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "onFramebufferResize", at = @At("TAIL"))
    private void onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onResize(width, height);
        }
    }

    /**
     * IRIS COMPOSITE VIEWPORT FIX (2026-08-23, "zoomed into an outrageous
     * amount"): Iris sizes its pipeline and composite-pass viewport from
     * Window.getWidth()/getHeight(). Our ScaledRenderTarget swap changes
     * mainRT to proxy size (420x263 at 0.25 quality), but Iris still reads
     * the real window (1680x1050) → pipeline at native → composite viewport
     * = native → the fullscreen-quad UVs are computed as
     * gl_FragCoord/viewport → only the top-left quarter of the scene
     * lands in the proxy FBO → 4x zoom.
     *
     * While irisSwapActive, report proxy dimensions so Iris builds its
     * pipeline and sets composite viewports at proxy size. After the FSR
     * dispatch clears irisSwapActive, the GUI reads native dimensions.
     * The one-frame pipeline-rebuild cost at swap-engage is the price;
     * the alternative (destroyPipeline reflection) proved unreliable
     * because Iris recreates at the same native window size.
     */
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true, remap = false)
    private void overrideWidth(CallbackInfoReturnable<Integer> cir) {
        if (FSRProcessor.windowScaleActive && FSRProcessor.irisSwapActiveStatic) {
            cir.setReturnValue(FSRProcessor.scaledWidthForWindow);
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true, remap = false)
    private void overrideHeight(CallbackInfoReturnable<Integer> cir) {
        if (FSRProcessor.windowScaleActive && FSRProcessor.irisSwapActiveStatic) {
            cir.setReturnValue(FSRProcessor.scaledHeightForWindow);
        }
    }

}
