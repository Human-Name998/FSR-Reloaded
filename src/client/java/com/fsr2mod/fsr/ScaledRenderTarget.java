package com.fsr2mod.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * A RenderTarget proxy that stores native (display) resolution in width/height
 * but delegates texture access to scaled-down textures.
 *
 * This keeps Voxy (and other mods that read getMainRenderTarget().width/height)
 * seeing correct native dimensions, while the actual rendering happens at the
 * scaled resolution.
 */
public class ScaledRenderTarget extends RenderTarget {

    public ScaledRenderTarget(int nativeW, int nativeH,
                              GpuTexture scaledColorTex, GpuTextureView scaledColorView,
                              GpuTexture scaledDepthTex, GpuTextureView scaledDepthView) {
        super("fsr-proxy", scaledDepthTex != null);
        this.width = nativeW;
        this.height = nativeH;
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
        // No-op: dimensions are managed by FSRProcessor
    }

    @Override
    public void createBuffers(int width, int height) {
        // No-op: textures are provided externally
    }
}
