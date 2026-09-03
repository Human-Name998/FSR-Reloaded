package com.fsr2mod.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * Returns the name of the currently loaded shaderpack, or null if none.
     * Uses reflection to access the Iris internal state.
     */
    public static String getCurrentShaderPackName() {
        if (!isIrisLoaded()) return null;
        try {
            // Try the public API first: IrisApi.getCurrentPackName()
            try {
                Method m = irisApiClass.getMethod("getCurrentPackName");
                Object result = m.invoke(irisApi);
                if (result instanceof String s && !s.isEmpty()) return s;
            } catch (NoSuchMethodException ignored) {}

            // Fallback: Iris internal — net.irisshaders.iris.Iris.getCurrentPack().getName()
            try {
                Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                Object currentPack = irisClass.getMethod("getCurrentPack").invoke(null);
                if (currentPack != null) {
                    Method getName = currentPack.getClass().getMethod("getName");
                    Object name = getName.invoke(currentPack);
                    if (name instanceof String s && !s.isEmpty()) return s;
                }
            } catch (Exception ignored) {}

            // Fallback 2: Iris config stored the pack name
            try {
                Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                Object config = irisClass.getMethod("getIrisConfig").invoke(null);
                if (config != null) {
                    Method getPackName = config.getClass().getMethod("getPackName");
                    Object name = getPackName.invoke(config);
                    if (name instanceof String s && !s.isEmpty()) return s;
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            // Silently fail — pack name is optional
        }
        return null;
    }

    /**
     * Returns the path to the currently loaded shaderpack on disk, or null.
     * Handles both directory packs and zipped packs.
     */
    public static Path getCurrentShaderPackPath() {
        if (!isIrisLoaded() || !isShaderPackInUse()) return null;

        String packName = getCurrentShaderPackName();
        if (packName == null || packName.isEmpty()) return null;

        // The shaderpacks directory is at <gameDir>/shaderpacks/
        Path shaderPacksDir = getShaderPacksDir();
        if (shaderPacksDir == null) return null;

        // Try directory first, then .zip
        Path dirPack = shaderPacksDir.resolve(packName);
        if (Files.isDirectory(dirPack)) return dirPack;

        // Try with .zip extension
        if (!packName.endsWith(".zip")) {
            Path zipPack = shaderPacksDir.resolve(packName + ".zip");
            if (Files.isRegularFile(zipPack)) return zipPack;
        }

        // Try without extension (some packs are stored without .zip)
        Path barePack = shaderPacksDir.resolve(packName);
        if (Files.isRegularFile(barePack)) return barePack;

        return null;
    }

    /**
     * Returns the shaderpacks directory path, or null.
     */
    public static Path getShaderPacksDir() {
        try {
            // Standard Minecraft instance layout: <gameDir>/shaderpacks/
            Path gameDir = FabricLoader.getInstance().getGameDir();
            return gameDir.resolve("shaderpacks");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the currently loaded shaderpack as a Path (directory or zip).
     * For zipped packs, this returns the zip file path.
     * For directory packs, this returns the directory path.
     */
    public static Path getLoadedShaderPackPath() {
        if (!isIrisLoaded()) return null;
        try {
            // Try to get the loaded shaderpack path from Iris internals
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            Object currentPack = irisClass.getMethod("getCurrentPack").invoke(null);
            if (currentPack != null) {
                // ShaderPack might have a getPath() or getLocation() method
                try {
                    Method getPath = currentPack.getClass().getMethod("getPath");
                    Object path = getPath.invoke(currentPack);
                    if (path instanceof Path p) return p;
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method getLocation = currentPack.getClass().getMethod("getLocation");
                    Object loc = getLocation.invoke(currentPack);
                    if (loc instanceof Path p) return p;
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method getLocation = currentPack.getClass().getMethod("getLocation");
                    Object loc = getLocation.invoke(currentPack);
                    if (loc != null) {
                        Method toPath = loc.getClass().getMethod("toPath");
                        Object p = toPath.invoke(loc);
                        if (p instanceof Path path) return path;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Fallback: resolve from the name
        return getCurrentShaderPackPath();
    }
}
