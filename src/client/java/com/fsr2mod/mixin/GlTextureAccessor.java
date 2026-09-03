package com.fsr2mod.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.21.11 port: GlTexture's (usage,label,format,w,h,depth,levels,id) constructor
 * is package-protected, and the project's access widener (which made it
 * accessible in 26.1.2) was removed — loom 1.16's class-tweaker pass rejected
 * its 'official' namespace in the obfuscated flow. The invoker restores
 * construction access for the shared-memory FG textures without an AW.
 */
@Mixin(GlTexture.class)
public interface GlTextureAccessor {
    @Invoker("<init>")
    static GlTexture fsrNewGlTexture(int usage, String label, TextureFormat format, int width, int height, int depth, int levels, int id) {
        throw new AssertionError();
    }
}