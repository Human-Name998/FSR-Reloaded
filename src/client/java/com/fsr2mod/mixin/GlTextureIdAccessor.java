package com.fsr2mod.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlTexture.class)
public interface GlTextureIdAccessor {
    @Accessor("id")
    void setId(int id);
}
