package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "onFramebufferResize", at = @At("TAIL"))
    private void onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onResize(width, height);
        }
    }

}
