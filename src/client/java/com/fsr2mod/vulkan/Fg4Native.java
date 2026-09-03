package com.fsr2mod.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class Fg4Native {

    private static boolean available = false;
    private static String fgDllDir = "";

    static {
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "fsr2mod-natives");
            Files.createDirectories(dir);

            // Extract FG DLLs to temp — NOT loaded via System.load.
            // These are AMD SDK provider DLLs (not JNI libraries).
            // The C++ code loads them via LoadLibraryExW using the directory path.
            extractTo("/natives/amd_fidelityfx_framegeneration_dx12.dll", dir,
                "amd_fidelityfx_framegeneration_dx12.dll");
            try {
                extractTo("/natives/amd_fidelityfx_loader_dx12.dll", dir,
                    "amd_fidelityfx_loader_dx12.dll");
            } catch (Exception e) { /* loader DLL is optional */ }

            // The actual JNI native methods are in fsrvk.dll, already loaded
            // by VulkanInterop. No need to load any DLL here.
            if (!VulkanInterop.isAvailable()) {
                throw new UnsatisfiedLinkError("fsrvk.dll not available (VulkanInterop failed to load)");
            }

            fgDllDir = dir.toAbsolutePath().toString();
            available = true;
        } catch (Exception e) {
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * @return the directory where FG SDK DLLs were extracted
     */
    public static String getFgDllDir() {
        return fgDllDir;
    }

    /**
     * Initialize the FG context.
     * 
     * @param renderW           scaled render width
     * @param renderH           scaled render height
     * @param displayW          native display width
     * @param displayH          native display height
     * @param poolPtr           pointer to D3D12SharedResources pool (0 = will be resolved later)
     * @param colorIndex        pool index for presentColor (input to FG Generate -
     *                          the real frame WITH the GUI composited, display-size RGBA8)
     * @param depthIndex        pool index for depth (R32F)
     * @param mvIndex           pool index for motion vectors (RG16F)
     * @param outputIndex       pool index for FG output (RGBA8)
     * @param hudlessColorIndex pool index for HUDLessColor (the same real frame WITHOUT
     *                          the GUI — the SDK diffs it against presentColor to extract
     *                          the HUD and composites it onto generated frames)
     * @param fgDllDir          directory where FG SDK DLLs were extracted (for LoadLibraryExW)
     * @return true on success
     */
    public static native boolean nativeInit(
        int renderW, int renderH, int displayW, int displayH,
        long poolPtr, int colorIndex, int depthIndex, int mvIndex, int outputIndex,
        int hudlessColorIndex,
        String fgDllDir);

    /**
     * Prepare the FG engine with current frame's scene data.
     * Must be called on every real rendered frame.
     *
     * @param frameID                monotonically increasing frame counter
     * @param frameTimeDeltaNs       nanoseconds since last frame
     * @param jitterX                jitter offset X (in render pixels)
     * @param jitterY                jitter offset Y (in render pixels)
     * @param cameraNear             near plane distance
     * @param cameraFar              far plane distance
     * @param cameraFovYRad          vertical FOV in radians
     * @param viewSpaceToMetersFactor  scale factor
     * @param camPosX/Y/Z            camera world position
     * @param camUpX/Y/Z             camera up vector (normalized)
     * @param camRightX/Y/Z          camera right vector (normalized)
     * @param camForwardX/Y/Z        camera forward vector (normalized)
     * @param reset                  true to reset FG internal state
     * @return true on success
     */
    public static native boolean nativePrepare(
        long frameID,
        long frameTimeDeltaNs,
        float jitterX, float jitterY,
        float cameraNear, float cameraFar, float cameraFovYRad,
        float viewSpaceToMetersFactor,
        float camPosX, float camPosY, float camPosZ,
        float camUpX, float camUpY, float camUpZ,
        float camRightX, float camRightY, float camRightZ,
        float camForwardX, float camForwardY, float camForwardZ,
        boolean reset);

    /**
     * Generate interpolated frame(s) from previously prepared data.
     * Output is written to the pool's output texture(s).
     * @param numGeneratedFrames number of frames to generate (1-4)
     * @return true if the generated frame(s) were produced
     */
    public static native boolean nativeGenerate(int numGeneratedFrames);

    /**
     * Wait for the last FG command to complete on GPU.
     * @return true if the FG command completed (fence signaled); false if the
     *         wait timed out — the caller MUST NOT blit the output texture,
     *         since the D3D12 side may never have written it (an unguarded GL
     *         copy from it can block the render thread forever on a hung GPU).
     */
    public static native boolean nativeFinish();

    /**
     * Shutdown FG context and release resources.
     */
    public static native void nativeShutdown();

    private static String extractTo(String resourcePath, Path dir, String fileName) {
        java.io.InputStream in = Fg4Native.class.getResourceAsStream(resourcePath);
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
