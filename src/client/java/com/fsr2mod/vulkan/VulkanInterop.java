package com.fsr2mod.vulkan;

/**
 * JNI bridge to fsrvk native library for VK-GL shared memory interop.
 *
 * Lifecycle:
 *   init:  getDeviceInfo()  // optional logging
 *   frame: glDeleteTextures(oldTex) → glFinish() → releaseSharedHandle(oldHandle) →
 *          getSharedHandle(w, h) → glImportMemoryWin32HandleEXT → ... → glFinish()
 *   shutdown: glDeleteTextures(tex) → glFinish() → releaseSharedHandle(handle) → terminate()
 *
 * Resource tracking uses a heap-allocated C++ struct (FsrSharedAllocation).
 * The jlong returned by getSharedHandle is a pointer to this struct — NOT
 * a raw Win32 HANDLE (Windows recycles handle integers, causing collision
 * bugs during rapid resize).
 */
public final class VulkanInterop {

    private static boolean loaded;
    private static boolean loadFailed;

    static {
        try {
            String libName = System.mapLibraryName("fsrvk");
            System.load(extractFromJar("/natives/" + libName));
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loadFailed = true;
        } catch (Exception e) {
            loadFailed = true;
        }
    }

    /**
     * @return true if the native library was loaded successfully
     */
    public static boolean isAvailable() {
        return loaded;
    }

    /**
     * Allocate VkImage + exportable VkDeviceMemory, export Win32 HANDLE.
     *
     * @param texW width in pixels
     * @param texH height in pixels
     * @return pointer to FsrSharedAllocation as jlong (0 on failure)
     */
    public static native long getSharedHandle(int texW, int texH);

    /**
     * Destroy VkImage + vkFreeMemory + CloseHandle + delete FsrSharedAllocation.
     *
     * Caller MUST glDeleteTextures() before calling this (resize safety).
     */
    public static native void releaseSharedHandle(long handle);

    /**
     * Returns "GPU Name — driver X.Y.Z" string.
     */
    public static native String getDeviceInfo();

    /**
     * Explicitly destroys VkDevice + VkInstance.
     *
     * Call from Minecraft client shutdown hook ONLY — NOT from DLL
     * static destructors (JVM thread teardown can cause
     * EXCEPTION_ACCESS_VIOLATION during static destruction).
     */
    public static native void terminate();

    /**
     * Returns the raw Win32 HANDLE from a FsrSharedAllocation.
     */
    public static native long getWin32Handle(long allocPtr);

    /**
     * Returns the VkMemoryRequirements.size from a FsrSharedAllocation.
     */
    public static native long getAllocationSize(long allocPtr);

    // ----------------------------------------------------------
    // FSR4 Pool — 5 shared textures allocated as one batch
    // ----------------------------------------------------------

    /**
     * Allocate 5 shared textures (color, depth, MV, reactive, output).
     *
     * @param w width in pixels
     * @param h height in pixels
     * @return pointer to Fsr4SharedResources as jlong (0 on failure)
     */
    public static native long allocateFsr4Pool(int renderW, int renderH, int displayW, int displayH);

    /**
     * Get the Win32 HANDLE for one texture in the pool.
     *
     * @param poolPtr pointer to Fsr4SharedResources
     * @param index   texture index (0=COLOR, 1=DEPTH, 2=MV, 3=REACTIVE, 4=OUTPUT)
     * @return Win32 HANDLE as jlong, or 0 on failure
     */
    public static native long getFsr4Win32Handle(long poolPtr, int index);

    /**
     * Get the D3D12 ID3D12Resource* pointer for one texture in the pool.
     *
     * @param poolPtr pointer to D3D12SharedResources
     * @param index   texture index
     * @return resource pointer as jlong, or 0 on failure
     */
    public static native long getFsr4ResourcePtr(long poolPtr, int index);

    /**
     * Get the allocation size for one texture in the pool.
     *
     * @param poolPtr pointer to Fsr4SharedResources
     * @param index   texture index
     * @return allocation size in bytes
     */
    public static native long getFsr4AllocationSize(long poolPtr, int index);

    /**
     * Destroy all 5 textures, free memory, close handles, delete the pool struct.
     * Caller MUST glFinish() and glDeleteTextures() before calling this.
     */
    public static native void releaseFsr4Pool(long poolPtr);

    // ----------------------------------------------------------
    // VulkanMod interop — external memory for same-device copy
    // ----------------------------------------------------------

    /**
     * Store VulkanMod's VkDevice and VkPhysicalDevice for native code.
     * Called from VulkanModDeviceMixin after initVulkan completes.
     */
    public static native void setVulkanModDevice(long devicePtr, long physicalDevicePtr);

    /**
     * Check if the given physical device supports VK_KHR_external_memory_win32.
     * @return true if the extension is available
     */
    public static native boolean checkExternalMemorySupport();

    /**
     * Set the swapchain pixel format detected by VulkanMod.
     * Called from VulkanModSwapchainMixin after swapchain creation.
     * @param isBGRA true if swapchain uses B8G8R8A8 format, false for R8G8B8A8
     */
    public static native void setSwapchainFormat(boolean isBGRA);

    /**
     * Initialize external-memory images for VulkanMod FSR4 interop.
     * Creates input (copy destination for scene) and output (FSR4 result) images
     * on VulkanMod's device with exportable memory.
     * @return true if successful
     */
    public static native boolean initExternalImages(int width, int height);

    /**
     * Destroy external-memory images created by initExternalImages().
     */
    public static native void destroyExternalImages();

	    /**
	     * Record frame copy operations into a GIVEN command buffer (VulkanMod's main
	     * command buffer, while still recording — before vkEndCommandBuffer).
	     *
	     * Records 7 layout-transition barriers + 2 vkCmdCopyImage operations:
	     *   1. swapchain PRESENT_SRC → TRANSFER_SRC
	     *   2. input GENERAL → TRANSFER_DST
	     *   3. Copy swapchain → input (save scene for FSR4)
	     *   4. swapchain TRANSFER_SRC → TRANSFER_DST
	     *   5. output GENERAL → TRANSFER_SRC
	     *   6. Copy output → swapchain (display previous FSR4 result)
	     *   7. swapchain TRANSFER_DST → PRESENT_SRC
	     *   8. input TRANSFER_DST → GENERAL
	     *   9. output TRANSFER_SRC → GENERAL
	     *
	     * The command buffer executes atomically on the GPU as part of VulkanMod's
	     * main render submission. No separate synchronization needed.
	     *
	     * @param cmdBuf         native VkCommandBuffer handle (still recording)
	     * @param swapchainImage native swapchain VkImage handle
	     * @param width          image width in pixels
	     * @param height         image height in pixels
	     */
	    public static native void recordFrameCopyOps(long cmdBuf, long swapchainImage, int width, int height);
	
	    /**
	     * Wait for the most recent copy (submitted by {@link #recordFrameCopyOps})
	     * to complete. Blocks the calling thread until the GPU finishes the copy.
	     * Safe to call every frame — returns immediately if no copy is pending.
	     */
	    public static native void waitForFrameCopy();

    /**
     * Open the input VkImage's Win32 handle as a D3D12 shared resource.
     * After this call, {@link #getImportedInputResourcePtr()} returns the
     * ID3D12Resource pointer that shares memory with the input VkImage.
     * @return true if successful
     */
    public static native boolean exportInputToD3D12();

    /**
     * Import the D3D12 pool's output texture as a VkImage (reverse direction).
     *
     * Instead of VK allocating OUTPUT and D3D12 opening it (which crashes on
     * AMD Radeon 860M — driver bug with second VK external memory allocation),
     * use the already-existing D3D12 pool output texture (index 4) and create
     * a VkImage backed by its memory via VK import.
     *
     * @param poolPtr     pointer to D3D12SharedResources pool
     * @param outputIndex texture index within pool (always 4 = OUTPUT)
     * @return true if successful
     */
    public static native boolean importOutputFromD3D12Pool(long poolPtr, int outputIndex);

    /**
     * @return the D3D12 ID3D12Resource* pointer for the input (scene) texture,
     *         or 0 if not yet imported
     */
    public static native long getImportedInputResourcePtr();

    /**
     * @return the D3D12 ID3D12Resource* pointer for the output (FSR4 result) texture,
     *         or 0 if not yet imported
     */
    public static native long getImportedOutputResourcePtr();

    /**
     * Debug: toggle pipeline test mode. When enabled, the FSR4 SDK dispatch is
     * replaced with a simple CopyResource from pool color → pool output, bypassing
     * all FSR4 processing. If the screen turns from black to the scene, the
     * D3D12→VK→swapchain pipeline is functional and the issue is in FSR4 itself.
     *
     * @param enabled true to bypass FSR4, false for normal operation
     */
    public static native void setDebugPipelineTest(boolean enabled);

    /**
     * Dispatch FSR4 using VulkanMod's imported D3D12 resources for color,
     * and the given pool resources for depth, motion vectors, reactive mask,
     * and output.
     *
     * @param jitterX           jitter offset X (0 for no jitter)
     * @param jitterY           jitter offset Y
     * @param cameraFar         camera far plane distance
     * @param cameraNear        camera near plane distance
     * @param cameraFovY        vertical field of view (radians)
     * @param deltaTime         frame delta time in milliseconds
     * @param frameIndex        current frame index
     * @param reset             true to reset temporal accumulation
     * @param depthPoolPtr      pointer to D3D12SharedResources for depth texture
     * @param depthIndex        texture index within pool for depth
     * @param mvPoolPtr         pointer to D3D12SharedResources for MV texture
     * @param mvIndex           texture index within pool for MV
     * @param reactivePoolPtr   pointer to D3D12SharedResources for reactive mask
     * @param reactiveIndex     texture index within pool for reactive mask
     * @param outputPoolPtr     pointer to D3D12SharedResources for output texture
     * @param outputIndex       texture index within pool for output
     * @return true if dispatch succeeded
     */
    public static native boolean dispatchFsr4VkMod(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovY,
        float deltaTime, int frameIndex, boolean reset,
        long depthPoolPtr, int depthIndex,
        long mvPoolPtr, int mvIndex,
        long reactivePoolPtr, int reactiveIndex,
        long outputPoolPtr, int outputIndex
    );

    // ----------------------------------------------------------
    // Internal: extract native library from mod JAR to temp dir
    // ----------------------------------------------------------
    private static String extractFromJar(String path) {
        java.io.InputStream in = VulkanInterop.class.getResourceAsStream(path);
        if (in == null) {
            throw new UnsatisfiedLinkError("Native library not found in JAR: " + path);
        }
        try (in) {
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("fsrvk", ".dll");
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // Mark for deletion when the JVM exits (on Windows, a loaded DLL
            // can't be deleted until it's unloaded, so this runs at shutdown).
            temp.toFile().deleteOnExit();
            return temp.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }
}