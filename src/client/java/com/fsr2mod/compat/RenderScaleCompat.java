package com.fsr2mod.compat;

import com.fsr2mod.FSRMod;
import net.fabricmc.loader.api.FabricLoader;

public class RenderScaleCompat {
    private static Boolean renderScaleLoaded;
    private static Class<?> renderScaleClass;

    public static boolean isRenderScaleLoaded() {
        if (renderScaleLoaded == null) {
            renderScaleLoaded = FabricLoader.getInstance().isModLoaded("renderscale");
            if (renderScaleLoaded) {
                try {
                    renderScaleClass = Class.forName("dev.zelo.renderscale.RenderScale");
                } catch (Exception e) {
                    renderScaleLoaded = false;
                }
            }
        }
        return renderScaleLoaded;
    }

    public static double getCurrentScaleFactor() {
        if (!isRenderScaleLoaded()) return 1.0;
        try {
            Object instance = renderScaleClass.getMethod("getInstance").invoke(null);
            if (instance == null) return 1.0;
            return (double) renderScaleClass.getMethod("getCurrentScaleFactor").invoke(instance);
        } catch (Exception e) {
            return 1.0;
        }
    }

    public static double getConfigScale() {
        if (!isRenderScaleLoaded()) return 1.0;
        try {
            Object instance = renderScaleClass.getMethod("getInstance").invoke(null);
            if (instance == null) return 1.0;
            Object config = renderScaleClass.getMethod("getConfig").invoke(instance);
            return (double) config.getClass().getMethod("getScale").invoke(config);
        } catch (Exception e) {
            return 1.0;
        }
    }

    public static void logStatus() {
        if (isRenderScaleLoaded()) {
            FSRMod.LOGGER.info("RenderScale detected — FSR Reloaded will apply FSR1 upscale + RCAS on top");
            FSRMod.LOGGER.info("  Config scale: {}, Current factor: {}", getConfigScale(), getCurrentScaleFactor());
        } else {
            FSRMod.LOGGER.info("RenderScale not detected — install RenderScale for Iris render scaling support");
        }
    }
}
