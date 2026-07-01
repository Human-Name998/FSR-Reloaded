package net.minecraft.client.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class SimpleFramebuffer extends Framebuffer {
	public SimpleFramebuffer(@Nullable String name, int width, int height, boolean useDepthAttachment) {
		super(name, useDepthAttachment);
		RenderSystem.assertOnRenderThread();
		this.resize(width, height);
	}
}
