package com.fsr2mod.sodium;

import net.fabricmc.loader.api.FabricLoader;

public class SodiumCompat {
    private static Boolean sodiumPresent;

    public static boolean isSodiumPresent() {
        if (sodiumPresent == null) {
            sodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        }
        return sodiumPresent;
    }
}
