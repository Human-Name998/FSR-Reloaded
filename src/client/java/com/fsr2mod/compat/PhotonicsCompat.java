package com.fsr2mod.compat;

import com.fsr2mod.FSRMod;
import net.fabricmc.loader.api.FabricLoader;

public class PhotonicsCompat {
    private static Boolean photonicsLoaded;
    private static boolean statusLogged;

    public static boolean isLoaded() {
        if (photonicsLoaded == null) {
            photonicsLoaded = FabricLoader.getInstance().isModLoaded("photonics");
        }
        return photonicsLoaded;
    }

    /** One-shot per-process status log (the call site runs every frame). */
    public static void logStatus() {
        if (isLoaded() && !statusLogged) {
            statusLogged = true;
            FSRMod.LOGGER.info("Photonics detected — voxel raytracing via Iris (ColorFramebuffer redirected to proxy dimensions by Mixin)");
            FSRMod.LOGGER.info("  Photonics GI renders at proxy size; FSR upscales the composite");
        }
    }
}