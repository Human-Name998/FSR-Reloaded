package net.minecraft.client.render;

import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

@Environment(EnvType.CLIENT)
public class OutputTarget {
	private final String name;
	private final Supplier<Framebuffer> framebuffer;
	public static final OutputTarget MAIN_TARGET = new OutputTarget("main_target", () -> MinecraftClient.getInstance().getFramebuffer());
	public static final OutputTarget OUTLINE_TARGET = new OutputTarget(
		"outline_target", () -> MinecraftClient.getInstance().worldRenderer.getEntityOutlinesFramebuffer()
	);
	public static final OutputTarget WEATHER_TARGET = new OutputTarget("weather_target", () -> MinecraftClient.getInstance().worldRenderer.getWeatherFramebuffer());
	public static final OutputTarget ITEM_ENTITY_TARGET = new OutputTarget(
		"item_entity_target", () -> MinecraftClient.getInstance().worldRenderer.getEntityFramebuffer()
	);

	public OutputTarget(String name, Supplier<Framebuffer> framebuffer) {
		this.name = name;
		this.framebuffer = framebuffer;
	}

	public Framebuffer getFramebuffer() {
		Framebuffer framebuffer = (Framebuffer)this.framebuffer.get();
		return framebuffer != null ? framebuffer : MinecraftClient.getInstance().getFramebuffer();
	}

	public String toString() {
		return "OutputTarget[" + this.name + "]";
	}
}
