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
            // CRITICAL: Save the UNJITTERED projection first — it is used to build
            // the view-projection matrix for MV generation. If jitter is included in
            // the VP matrix, MVs contain jitter which shifts the reprojection point
            // every frame (different jitter each frame = flickering). The jitter
            // correction was supposed to cancel this, but jitterCancelX/Y is zeroed.
            processor.setProjection(new Matrix4f(proj));
            // Apply jitter AFTER saving — the scene renders with jittered projection
            // for proper sub-pixel temporal sample distribution.
            processor.applyJitterToMatrix(proj);
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
