package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow
    private GameRenderState gameRenderState;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            var window = Minecraft.getInstance().getWindow();
            processor.onRenderBegin(window.getWidth(), window.getHeight());
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onRenderEnd();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            Matrix4f proj = gameRenderState.levelRenderState.cameraRenderState.projectionMatrix;
            processor.applyJitterToMatrix(proj);
            processor.setProjection(new Matrix4f(proj));
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderLevelTail(DeltaTracker deltaTracker, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onSceneRendered();
        }
    }
}
