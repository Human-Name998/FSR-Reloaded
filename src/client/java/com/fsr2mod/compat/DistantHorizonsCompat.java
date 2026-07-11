package com.fsr2mod.compat;

import com.fsr2mod.FSRMod;
import net.fabricmc.loader.api.FabricLoader;

public class DistantHorizonsCompat {
    private static Boolean dhLoaded;

    public static boolean isLoaded() {
        if (dhLoaded == null) {
            dhLoaded = FabricLoader.getInstance().isModLoaded("distanthorizons");
        }
        return dhLoaded;
    }

    public static void logStatus() {
        if (isLoaded()) {
            FSRMod.LOGGER.info("Distant Horizons detected — LOD terrain renders in separate framebuffers with own depth textures");
            FSRMod.LOGGER.info("  DH depth not available to FSR2/3 temporal pipeline — fallback to FSR1 recommended");
            if (IrisCompat.isShaderPackInUse()) {
                FSRMod.LOGGER.info("  DH + Iris active: DH uses dh_terrain/dh_water Iris programs");
            } else {
                FSRMod.LOGGER.info("  DH (non-Iris): LOD compositing into mainRT may occur after scene upscale");
            }
        }
    }
}
