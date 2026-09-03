package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public class FramebufferBlitMixin {
    @Inject(method = "blitToScreen", at = @At("HEAD"), cancellable = true)
    private void onBlitToScreen(CallbackInfo ci) {
        FSRProcessor p = FSRMod.getInstance().getProcessor();
        if (p != null) {
            if (p.presentDirect((RenderTarget)(Object)this)) {
                ci.cancel();
            } else {
                p.ensureCorrectRenderTarget((RenderTarget)(Object)this);
            }
        }
    }
}
