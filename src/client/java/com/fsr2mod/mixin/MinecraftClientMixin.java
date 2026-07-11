package com.fsr2mod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Shadow @Final @Mutable
    public RenderTarget mainRenderTarget;
}
