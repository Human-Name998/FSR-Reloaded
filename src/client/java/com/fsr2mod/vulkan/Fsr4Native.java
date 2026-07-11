package com.fsr2mod.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class Fsr4Native {

    private static boolean available = false;

    static {
        try {
            Path nativesDir = Paths.get(System.getProperty("java.io.tmpdir"), "fsr2mod-natives");
            Files.createDirectories(nativesDir);

            String apiDllPath = extractTo("/natives/amd_fsr4_api.dll", nativesDir, "amd_fsr4_api.dll");
            System.load(apiDllPath);

            available = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static native boolean initFsr4(int renderW, int renderH, int displayW, int displayH);

    public static native boolean dispatchFsr4(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovYRad,
        float deltaTime, int frameIndex, boolean reset,
        long colorPoolPtr, int colorIndex,
        long depthPoolPtr, int depthIndex,
        long mvPoolPtr, int mvIndex,
        long reactivePoolPtr, int reactiveIndex,
        long outputPoolPtr, int outputIndex);

    public static native void waitIdle();

    public static native void shutdownFsr4();

    public static native void disposeD3D12();

    private static String extractTo(String resourcePath, Path dir, String fileName) {
        java.io.InputStream in = Fsr4Native.class.getResourceAsStream(resourcePath);
        if (in == null) throw new UnsatisfiedLinkError("Not found in JAR: " + resourcePath);
        try (in) {
            Path target = dir.resolve(fileName);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Extraction failed: " + e.getMessage());
        }
    }
}
