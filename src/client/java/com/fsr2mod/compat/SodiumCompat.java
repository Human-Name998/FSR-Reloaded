package com.fsr2mod.compat;

import net.fabricmc.loader.api.FabricLoader;

public class SodiumCompat {
    private static Boolean sodiumLoaded;

    public static boolean isSodiumLoaded() {
        if (sodiumLoaded == null) {
            sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium");
        }
        return sodiumLoaded;
    }
}
