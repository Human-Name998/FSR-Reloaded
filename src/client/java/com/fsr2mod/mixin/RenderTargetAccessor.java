package com.fsr2mod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    void _fsrSetColorTexture(GpuTexture tex);

    @Accessor("colorTexture")
    GpuTexture _fsrGetColorTexture();

    @Accessor("colorTextureView")
    void _fsrSetColorTextureView(GpuTextureView view);
}
