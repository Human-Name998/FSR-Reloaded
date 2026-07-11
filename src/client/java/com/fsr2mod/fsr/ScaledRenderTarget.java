package com.fsr2mod.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * A RenderTarget proxy that stores scaled resolution in width/height
 * and delegates texture access to scaled-down textures.
 *
 * Reporting scaled dimensions ensures LevelRenderer creates all internal
 * render targets (translucent, particles, weather, clouds) at the scaled
 * resolution — without this, all those targets are at full native
 * resolution, defeating render scaling's performance benefit.
 */
public class ScaledRenderTarget extends RenderTarget {

    public ScaledRenderTarget(int scaledW, int scaledH,
                              GpuTexture scaledColorTex, GpuTextureView scaledColorView,
                              GpuTexture scaledDepthTex, GpuTextureView scaledDepthView) {
        super("fsr-proxy", scaledDepthTex != null);
        if ((scaledDepthTex == null) != (scaledDepthView == null)) {
            throw new IllegalArgumentException(
                "depthTexture and depthTextureView must both be null or both non-null");
        }
        this.width = scaledW;
        this.height = scaledH;
        this.colorTexture = scaledColorTex;
        this.colorTextureView = scaledColorView;
        this.depthTexture = scaledDepthTex;
        this.depthTextureView = scaledDepthView;
    }

    @Override
    public void destroyBuffers() {
        // No-op: we don't own the textures, the original TextureTarget does
    }

    @Override
    public void resize(int width, int height) {
        // No-op: dimensions are managed by FSRProcessor, which recreates
        // the proxy when the underlying TextureTarget is resized.
    }

    @Override
    public void createBuffers(int width, int height) {
        // No-op: textures are provided externally
    }
}