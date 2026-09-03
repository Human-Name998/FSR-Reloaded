package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
// 1.21.11 port: ProjectionMatrixBuffer/Projection were replaced by
// PerspectiveProjectionMatrixBuffer (world) + CachedPerspectiveProjectionMatrixBuffer
// (first-person hand). The world jitter hook moves to PerspectiveProjectionMatrixBuffer;
// the hand (cached projection keyed by w/h/fov) has no matrix-arg hook, so the hand
// renders un-jittered — a known minor quality delta on held-item edges, not a crash.
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PRIORITY > 1000 (VulkanMod's default): Mixin applies mixins in ASCENDING
// priority order (lowest first) and each handler is inserted closest to the
// target INVOKE, so the LAST-applied mixin's handler runs LAST at the shared
// injection point. VulkanMod's GameRendererMixin (default 1000) captures the
// ORIGINAL unjittered projection local into VRenderSystem.projection via @Local
// at the RenderSystem.setProjectionMatrix INVOKE. Our handler (priority 1100,
// applied after theirs) therefore runs AFTER their capture and overwrites
// VRenderSystem.projection with the JITTERED matrix — otherwise VulkanMod's
// world render stays unjittered while the FSR4 SDK is told jitter=(prevJitter)
// → phantom jitter → shake + no temporal accumulation (the "shaking but jagged"
// bug; probe-confirmed 2026-08-09 — see VULKANMOD_FSR4_FINDINGS.md).
@Mixin(value = GameRenderer.class, priority = 1100)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            var window = Minecraft.getInstance().getWindow();
            processor.onRenderBegin(window.getWidth(), window.getHeight());
        }
    }

    /**
     * Sub-pixel projection jitter for the temporal upscalers (FSR2/3/4).
     *
     * This targets the Mojmap method ProjectionMatrixBuffer.getBuffer(Matrix4f),
     * which is the exact invocation PureScale jitters (verified from the official
     * mod's classfile: @ModifyArg on "ProjectionMatrixBuffer;getBuffer(Lorg/joml/
     * Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;" index=0). The actual
     * world Projection matrix passed to it is what gets rendered, so shifting it
     * by 2*jitter/renderSize applies the Halton sub-pixel offset in clip space;
     * the same clip-space offset is what the FSR shaders' de-jitter expects, and
     * is what feeds the SDK's output-grid alignment.
     *
     * In earlier 26.1.2 work this hook was removed because an *obfuscated* name
     * was tried and failed with "Scanned 0 target(s)" (crash on launch). The
     * mapped muster name here is stable and proven by a working third-party mod.
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        index = 0)
    private Matrix4f jitterLevelProjection(Matrix4f projectionMatrix) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            return processor.captureAndJitter(projectionMatrix);
        }
        return projectionMatrix;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onRenderEnd();
        }
    }

    // NOTE: The sub-pixel projection jitter hook (@ModifyArg on
    // ProjectionMatrixBuffer.getBuffer) IS ACTIVE. It captures the unjittered
    // projection for MV generation (lastProj) and returns a jittered copy for
    // rendering (lastProjJittered). The temporal pipelines use lastProjJittered
    // for currViewProj so MVs carry only true camera motion (jitter cancels).
    // The accumulate shader de-jitters lrUv = hrUv - jitter/renderSize to
    // recover canonical world position for sampling the low-res input.

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderLevelTail(DeltaTracker deltaTracker, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onSceneRendered();
        }
    }

    /**
     * VulkanMod world-projection jitter fix (2026-08-05): after VulkanMod's own
     * GameRendererMixin has stored its @Local-captured (UNJITTERED) projection
     * into VRenderSystem.projection, overwrite that field with OUR jittered
     * matrix so the chunk renderer (LevelRendererMixin ->
     * WorldRenderer.renderSectionLayer -> applyMVP) renders the world jittered,
     * matching the jitter the FSR4 SDK receives. Fires at BOTH setProjectionMatrix
     * INVOKEs: ordinal 0 = the LEVEL projection (world), ordinal 1 = the HAND
     * projection (renderItemInHand reads the field right after; jittering it
     * makes the held item/fist shift with the world — GL-parity). Runs after
     * VulkanMod's handler because this mixin's priority (1100) is above
     * VulkanMod's (1000).
     */
    @Inject(method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
            ordinal = 0))
    private void afterLevelProjectionUploaded(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.applyVulkanModProjectionOverride();
        }
    }

    /**
     * Hand-projection jitter under VulkanMod (2026-08-09): the FIRST-PERSON
     * HAND renders immediately after the ordinal-1 setProjectionMatrix INVOKE
     * (GameRenderer.renderLevel -> renderItemInHand, bytecode offset 518), and
     * VulkanMod's chunk/entity render path draws it from VRenderSystem.projection
     * — which its getProjection capture at this INVOKE fills with the UNJITTERED
     * hud projection. The hand therefore stayed unjittered while the world
     * shifted ±jitter px per frame → the FSR4 SDK could not align the hand
     * region with history → jagged held-item/fist edges (the last known
     * GL-vs-VK delta). Same clip-space translation as the world (T·P shifts
     * NDC by exactly jx/jy regardless of fov/near), so the hand moves by the
     * same pixels and the SDK de-jitters it uniformly. Runs after VulkanMod's
     * capture (priority 1100 > 1000). jitterAppliedThisFrame is still true
     * here (set by the world capture earlier in renderLevel), so the override
     * applies.
     */
    @Inject(method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
            ordinal = 1))
    private void afterHandProjectionUploaded(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.applyVulkanModProjectionOverride();
        }
    }
}
