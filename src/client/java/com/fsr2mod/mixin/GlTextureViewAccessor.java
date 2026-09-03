package com.fsr2mod.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.21.11 port: GlTextureView's (texture,baseLevel,levels) constructor is
 * package-protected — see GlTextureAccessor for the AW-removal background.
 */
@Mixin(GlTextureView.class)
public interface GlTextureViewAccessor {
    @Invoker("<init>")
    static GlTextureView fsrNewGlTextureView(GlTexture texture, int baseLevel, int ipLevel) {
        throw new AssertionError();
    }
}