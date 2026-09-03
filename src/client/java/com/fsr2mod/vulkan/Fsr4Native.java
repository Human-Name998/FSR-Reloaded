package com.fsr2mod.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class Fsr4Native {

    private static boolean available = false;

    static {
        try {
            // fsrvk.dll holds ALL the JNI natives for this class (setDiagnosticsEnabled,
            // setSharpness, initFsr4, dispatchFsr4, shutdownFsr4, ...). It is loaded by
            // VulkanInterop's static init, NOT by this class. If FSR was disabled at
            // launch, nothing else touches VulkanInterop until the first FSR4/FG use,
            // so the first native call here (e.g. syncNativeDiagnostics during a
            // toggle's markDirty) would throw UnsatisfiedLinkError mid-toggle and
            // crash the game (2026-08-22 "turned it off then it crashed" fix).
            // Force VulkanInterop's init so fsrvk.dll is guaranteed present; treat
            // failure as FSR4 unavailable so callers fall back gracefully.
            if (!VulkanInterop.isAvailable()) {
                throw new UnsatisfiedLinkError("fsrvk.dll not loaded (VulkanInterop init failed)");
            }

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

    /** Returns the ID3D12Device pointer for the GPU device shared with FSR4. */
    public static native long getD3D12DevicePtr();

    public static native boolean initFsr4(int renderW, int renderH, int displayW, int displayH);

    public static native boolean dispatchFsr4(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovYRad,
        float deltaTime, int frameIndex, boolean reset,
        long colorPoolPtr, int colorIndex,
        long depthPoolPtr, int depthIndex,
        long mvPoolPtr, int mvIndex,
        long reactivePoolPtr, int reactiveIndex,
        long outputPoolPtr, int outputIndex);    public static native void waitIdle();
    /** Gates all native fsr4_diag.log + stderr diagnostics. Pass config.debugMode > 0. */
    public static native void setDiagnosticsEnabled(boolean on);
    /**
     * True once the FSR4 SDK's g_ffxDispatch threw (MSVC C++ exception) and the
     * native layer switched dispatch to the bilinear fallback upscale. On this
     * GPU the SDK upscale cannot coexist with an FG ffxCreateContext, so with
     * FSR3_FG_DX12 the upscale runs bilinear while FG stays active. Cleared on
     * shutdownFsr4() so a deliberate settings change re-tries the real SDK.
     */
    public static native boolean isBypassActive();
    /**
     * PRE-ARMS the bilinear bypass BEFORE the first dispatch so g_ffxDispatch
     * is never called. On AMD iGPUs the FSR4 SDK upscale deterministically
     * throws AND removes the D3D12 device when an FG context exists (so the
     * post-crash arm came too late — the device was already gone). Java calls
     * this right after FSR4 init when FG is active on an AMD iGPU, keeping the
     * device healthy so the bilinear upscale + FG both work. Cleared by
     * shutdownFsr4() so a settings change re-tries the real SDK.
     */
    public static native void armBypass();
    /** True if the selected D3D12 adapter is an AMD integrated GPU (iGPU). */
    public static native boolean isAmdIntegratedGpu();
    /**
     * Sets the FSR4 SDK upscale sharpening amount [0..1] (its internal
     * RCAS-style sharpener, wired to the same config.sharpness slider that
     * drives FSR1/2/3's GLSL RCAS). 0 disables sharpening. Native: stored in
     * fsr::g_fsr4Sharpness, consumed by Fsr4Context::dispatch.
     */
    public static native void setSharpness(float sharpness);
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
