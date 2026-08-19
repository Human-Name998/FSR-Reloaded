package com.fsr2mod.compat;

import net.fabricmc.loader.api.FabricLoader;

public class IrisCompat {
    private static Boolean irisLoaded;
    private static Object irisApi;
    private static Class<?> irisApiClass;

    public static boolean isIrisLoaded() {
        if (irisLoaded == null) {
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
            if (irisLoaded) {
                try {
                    irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = irisApiClass.getMethod("getInstance").invoke(null);
                } catch (Exception e) {
                    irisLoaded = false;
                }
            }
        }
        return irisLoaded;
    }

    public static boolean isShaderPackInUse() {
        if (!isIrisLoaded()) return false;
        try {
            return (boolean) irisApiClass.getMethod("isShaderPackInUse").invoke(irisApi);
        } catch (Exception e) {
            return false;
        }
    }
}
