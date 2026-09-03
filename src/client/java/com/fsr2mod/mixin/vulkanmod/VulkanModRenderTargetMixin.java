package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.VulkanModCompat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RENDER-SCALE DEPTH ALIGNMENT (2026-08-08, "now it just smears" root cause).
 *
 * VulkanMod overrides the main RenderTarget's getColorTexture() /
 * getColorTextureView() / getDepthTexture() getters to return the
 * DefaultMainPass (DMP) framebuffer attachments, but it does NOT override
 * getDepthTextureView() — that one still returns the mc main target's OWN
 * depthTextureView field, which MainTargetMixin.createBuffers() creates at
 * NATIVE resolution.
 *
 * Under render scale the DMP framebuffer is swapped to a SCALED framebuffer
 * (e.g. 480x300 at qualityScale 0.25), so the world's render passes bind a
 * SCALED color attachment (getColorTextureView → MainPass color) but a
 * NATIVE-size depth attachment (getDepthTextureView → field). Mismatched
 * attachment extents make the driver drop/ignore the depth writes — proven by
 * direct readbacks: the DMP fb depth stays at its 1.0 clear value (never
 * written) while the mc field depth reads 0.0 (writes never land), even though
 * the DMP fb COLOR holds the scene. The MV-gen then sees garbage depth:
 * all-1.0 (far plane) → ~0 MVs → the FSR4 SDK accepts history but fetches it
 * from the same screen spot → smearing; all-0.0 (near plane) → ~100x MVs →
 * history rejected → jagged spatial output.
 *
 * This mixin redirects getDepthTextureView() on the MAIN render target to a
 * view of the MainPass depth attachment (getDepthTexture() — the DMP scaled fb
 * depth under render scale). Color and depth now share the same extent, so the
 * world's depth writes land in the DMP scaled fb depth — the very image
 * VulkanModMainPassMixin already binds to the MV-gen (binding 3) — and the
 * FSR4 SDK finally receives real per-pixel depth. Only active when render
 * scale is swapped (config-enabled); all other targets and the non-scaled
 * D3D12 path fall through to vanilla.
 */
@Mixin(RenderTarget.class)
public class VulkanModRenderTargetMixin {

    /** Cache of the MainPass depth view, keyed by the depth texture identity. */
    private static GpuTexture fsrDepthViewCacheTex;
    private static GpuTextureView fsrDepthViewCacheView;
    private static boolean fsrDepthViewLogged;

    @Inject(method = "getDepthTextureView", at = @At("HEAD"), cancellable = true)
    private void fsrAlignDepthViewToMainPass(CallbackInfoReturnable<GpuTextureView> cir) {
        try {
            // MUST gate on the actual renderer, not just the config: this mixin
            // targets vanilla RenderTarget, so it applies even without VulkanMod.
            // renderScaleSwapped() is enabled-INDEPENDENT by design (the main-pass
            // presentation hook needs it that way), so on its own it fires in a
            // pure-GL instance with vulkanModRenderScale configured — redirecting
            // the depth view and creating a needless GL texture view there
            // (observed 2026-08-12, no-VulkanMod instance: the redirect log fired
            // but getDepthTexture() is the vanilla depth, not the MainPass one).
            if (!VulkanModCompat.isLoaded()) return;
            if (!VulkanModCompat.renderScaleSwapped()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            RenderTarget main = mc.getMainRenderTarget();
            if (main != (Object) this) return;
            // getDepthTexture() is overridden by VulkanMod to return the
            // MainPass depth attachment (scaled fb depth under render scale).
            GpuTexture tex = ((RenderTarget) (Object) this).getDepthTexture();
            if (tex == null) return;
            if (tex != fsrDepthViewCacheTex) {
                if (RenderSystem.getDevice() == null) return;
                GpuTextureView view = RenderSystem.getDevice().createTextureView(tex);
                if (view == null) return;
                fsrDepthViewCacheView = view;
                fsrDepthViewCacheTex = tex;
                if (!fsrDepthViewLogged) {
                    fsrDepthViewLogged = true;
                    FSRMod.LOGGER.info("[VKMod] getDepthTextureView redirected to MainPass depth (render-scale depth alignment)");
                }
            }
            cir.setReturnValue(fsrDepthViewCacheView);
        } catch (Exception e) {
            // Fall through to vanilla on any failure — never break rendering.
        }
    }
}
