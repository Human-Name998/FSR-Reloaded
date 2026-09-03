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
    /**
     * Initialize the VkImage aliases for the D3D12 pool's shared textures.
     *
     * @param renderW render width (FSR4 INPUT size — the scaled framebuffer
     *                dims when render-scale is active, else display width)
     * @param renderH render height
     * @param dispW   display width (FSR4 OUTPUT size — the swapchain dims)
     * @param dispH   display height
     *
     * Render and display dims are split (2026-08-05) so render-scale + FSR4 can
     * combine: the pool's input textures (color/depth/MV/reactive) are render-
     * sized and the output is display-sized — FSR4 upscales render→display.
     */
    public static native boolean initExternalImages(int renderW, int renderH, int dispW, int dispH);

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
	     * FSR3_FG_DX12 step 2 (2026-08-15): copy the swapchain image (real frame
	     * + GUI, still COLOR_ATTACHMENT_OPTIMAL after the GUI aux pass) into the
	     * imported pool 6 (FG_PRESENT) for the FG SDK's presentColor. Leaves the
	     * swapchain back in COLOR_ATTACHMENT_OPTIMAL. Recorded from the GUI-return
	     * hook before VulkanMod's own PRESENT transition.
	     */
	    public static native void recordVkModSwapchainToFgPresentCopy(long cmdBuf, long swapchainImage, int width, int height);

	    /**
	     * FSR3_FG_DX12 step 2: copy the FG generated frame (pool 5) into the
	     * acquired second swapchain image (UNDEFINED -> TRANSFER_DST ->
	     * PRESENT_SRC) via a one-shot command buffer on the graphics queue,
	     * submitted + bounded-waited in one call. For the extra present after
	     * VulkanMod's real-frame present.
	     */
	    public static native void recordVkModFgOutputCopyOneShot(long dstImage, int width, int height);

	    /**
	     * FSR3_FG_DX12 step 2: acquire the second swapchain image for the FG
	     * present (bounded 16ms — never stalls the main thread). Returns the
	     * image index, or -1 on failure/timeout.
	     */
	    public static native int acquireVkModFgImage(long swapchain);

	    /**
	     * FSR3_FG_DX12 step 2: present the FG frame. Caller must fence-wait the
	     * main submission (containing the pool5 -> image copy) first. Consumes the
	     * acquire semaphore signal.
	     */
	    public static native void presentVkModFgImage(long presentQueue, long swapchain, int imageIndex);

	    /**
	     * FSR3_FG_DX12 step 2: import pool 5 (FG_OUTPUT) / pool 6 (FG_PRESENT) as
	     * DISPLAY-size VkImages aliasing the D3D12 shared memory (the generic
	     * {@link #importPoolTextureAsVkImage} importer uses render-size extents).
	     * slot 0 = pool 5, slot 1 = pool 6.
	     */
	    public static native boolean importFgPoolTextureAsVkImage(long poolPtr, int poolIndex, int slot, int vkFormat);

    /**
     * Render-scale + FSR4 combined path: scene rendered into the SCALED main
     * framebuffer (not the swapchain). Records Copy1 scaled-fb → pool input
     * (render-size) + Copy2 pool output (display-size) → swapchain, with all
     * layout transitions, replacing the bilinear blit when FSR4 is active
     * (the blit looked like FSR1 — it WAS a LINEAR upscale, worse than FSR1).
     *
     * @param includeCopy2 false on the POOL-JUST-RECREATED frame (dispatch was
     *        blocked for one frame, so pool[4] has never been written — Copy2
     *        would present undefined content). Records Copy1 + the input barrier
     *        only, so the NEXT frame's dispatch has a fresh scene; the caller
     *        presents via the bilinear blit instead.
     */
    public static native void recordVkModFsr4ScaledCopy(long cmdBuf, long srcImage, int srcW, int srcH, long dstImage, int dstW, int dstH, boolean includeCopy2);
	
	    /**
	     * Record a bilinear upscale blit (vkCmdBlitImage VK_FILTER_LINEAR) from a
	     * scaled render-target image to the swapchain image, plus all layout
	     * transitions (src COLOR_ATTACHMENT_OPTIMAL → TRANSFER_SRC → dst
	     * TRANSFER_DST → PRESENT_SRC_KHR). Used by the VulkanMod render-scale
	     * path (POC): the scene renders into a scaled main framebuffer and is
	     * upscaled to the swapchain here, replacing the D3D12 copy path.
	     *
	     * @param cmdBuf  native VkCommandBuffer handle (still recording)
	     * @param srcImage scaled color VkImage (src)
	     * @param srcW     src width in pixels
	     * @param srcH     src height in pixels
	     * @param dstImage swapchain VkImage (dst)
	     * @param dstW     dst width in pixels
	     * @param dstH     dst height in pixels
	     */
	    public static native void recordRenderScaleBlit(long cmdBuf, long srcImage, int srcW, int srcH, long dstImage, int dstW, int dstH);
	
	    /**
	     * Store VulkanMod's per-frame submission fence (from Renderer.inFlightFences
	     * + getCurrentFrame, grabbed in the main-pass mixin). The next frame's
	     * {@link #waitForFrameCopyBounded()} waits on THIS fence instead of draining
	     * the whole graphics queue with vkQueueWaitIdle.
	     *
	     * @param fence native VkFence handle (0 = no fence; native falls back to
	     *              vkQueueWaitIdle)
	     */
	    public static native void storeFrameFence(long fence);
	
	    /**
	     * Bounded wait for the previous frame's VK copy ops (Copy1/Copy2 recorded
	     * into VulkanMod's main command buffer by {@link #recordFrameCopyOps}).
	     * Waits on the fence stored by {@link #storeFrameFence} (8ms cap, one 33ms
	     * extended re-wait per timeout episode), falling back to a full
	     * vkQueueWaitIdle when no fence was stored.
	     *
	     * @return true when the copies completed (safe to dispatch FSR4); false on
	     *         timeout — the caller should SKIP this frame's dispatch rather than
	     *         stall the CPU on an unbounded queue drain.
	     */
	    public static native boolean waitForFrameCopyBounded();
	
    /**
     * Wait for the D3D12 FSR4 dispatch (submitted earlier in the frame) to
     * finish. Called from the main-pass mixin right before recording Copy2
     * (output -> swapchain), so the D3D12 work overlaps the scene render and
     * this wait is ~0ms instead of a mid-frame stall.
     *
     * @return true if the D3D12 output is ready to copy; false if the fence
     *         wait timed out (GPU saturated) — the caller MUST then skip the
     *         D3D12 copies (Copy1/Copy2/MV-gen) so it never reads a
     *         half-written output; the swapchain image still holds the raw
     *         scene, which presents natively.
     */
    public static native boolean waitForD3D12Output();

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
	     * Import the D3D12 pool's color texture (index 0) as a VkImage.
	     *
	     * Replaces the old VK→D3D12 exportInputToD3D12() path. Instead of VK
	     * allocating the input texture and D3D12 opening it via OpenSharedHandle
	     * (which crashes on AMD Radeon 860M), use the D3D12-allocated pool color
	     * texture and import it into VK (same pattern as importOutputFromD3D12Pool).
	     * The scene data is copied from the swapchain into this VkImage by
	     * recordFrameCopyOps, which the FSR4 SDK then reads as a D3D12 resource.
	     *
	     * @param poolPtr    pointer to D3D12SharedResources pool (color pool)
	     * @param colorIndex texture index within pool (0 = COLOR)
	     * @return true if successful
	     */
	    public static native boolean importInputFromD3D12Pool(long poolPtr, int colorIndex);

	    /**
	     * @return the D3D12 ID3D12Resource* pointer for the input (scene) texture,
	     *         or 0 if not yet imported
	     */
	    public static native long getImportedInputResourcePtr();

    /**
     * @return the D3D12 ID3D12Resource* pointer for the output (FSR4 result) texture,
     *         or 0 if not yet imported
     */
    public static native long getImportedOutputResourcePtr();	    /**
	     * Debug: toggle pipeline test mode. When enabled, the FSR4 SDK dispatch is
	     * replaced with a simple CopyResource from pool color → pool output, bypassing
	     * all FSR4 processing. If the screen turns from black to the scene, the
	     * D3D12→VK→swapchain pipeline is functional and the issue is in FSR4 itself.
	     *
	     * NOTE (2026-08-05): this must stay OFF for normal operation — when it was
	     * left permanently on, "FSR4" under VulkanMod was a 1:1 passthrough copy
	     * that looked exactly like FSR1 (the real SDK never ran; the pool's
	     * depth/MV/reactive inputs were never filled). The real path now fills them
	     * via {@link #recordVkModMvGen}.
	     *
	     * @param enabled true to bypass FSR4, false for normal operation
	     */
	    public static native void setDebugPipelineTest(boolean enabled);

	    /**
	     * Import a D3D12 pool texture (depth/MV/reactive) as a VkImage aliasing the
	     * same shared memory, so the VK MV-gen compute shader can write the FSR4
	     * SDK's inputs (2026-08-05: real FSR4 under VulkanMod). Mirrors
	     * {@link #importInputFromD3D12Pool} with storage-image usage.
	     *
	     * @param poolPtr    pointer to D3D12SharedResources pool
	     * @param poolIndex  pool texture index (1=DEPTH, 2=MV, 3=REACTIVE)
	     * @param slot       destination slot (0=depth, 1=mv, 2=reactive)
	     * @param vkFormat   VkFormat enum value (R32_SFLOAT=100, R16G16_SFLOAT=88, R8_UNORM=9)
	     * @return true if successful
	     */
	    public static native boolean importPoolTextureAsVkImage(long poolPtr, int poolIndex, int slot, int vkFormat);

	    /**
	     * Record the FSR4 MV-gen compute dispatch (fsr4_vk_mvgen.comp) into
	     * VulkanMod's still-recording command buffer: writes constant depth (0.5),
	     * motion vectors and reactive mask into the imported pool textures. Must be
	     * called AFTER {@link #recordFrameCopyOps} (same submission, so the frame
	     * fence covers the compute) and BEFORE the next frame's D3D12 FSR4 dispatch.
	     * The per-frame matrices come from {@link #setVkModMvGenMatrices}.
	     *
	     * @param cmdBuf native VkCommandBuffer handle (still recording)
	     */
	    public static native void recordVkModMvGen(long cmdBuf);

	    /**
	     * Set the per-frame matrices for the VK MV-gen shader (inverse of the
	     * current view-projection + the previous frame's view-projection, both
	     * CLEAN/unjittered — 16 floats each). Called from onRenderBegin before the
	     * FSR4 dispatch block; the main-pass mixin consumes them later the same
	     * frame via {@link #recordVkModMvGen}.
	     */
	    public static native void setVkModMvGenMatrices(float[] invCurrVP, float[] prevVP);

	    /**
	     * Set the per-frame camera-motion inputs for the VK MV-gen shader
	     * (push-constant floats 32/33): {@code eyeSpeed} = camera eye SPEED in
	     * blocks/sec (FPS-independent) and {@code rotDelta} = frame-to-frame
	     * rotation delta in radians. The no-real-depth shader mode ramps the
	     * reactive mask on eyeSpeed so walking renders spatially instead of
	     * smearing (constant-depth MVs cannot track translation).
	     */
	    public static native void setVkModCamMotion(float eyeSpeed, float rotDelta);

	    /**
	     * Binds (or clears) the REAL-depth source for the VK MV-gen shader's
	     * binding 3 (combined image sampler): the scaled framebuffer's depth
	     * attachment VkImage + its VK_FORMAT. Called by the main-pass mixin
	     * every frame (cheap no-op unless the framebuffer was recreated).
	     * image==0 / format==0 clears back to the constant-depth fallback.
	     *
	     * @see #recordVkModMvGen(long)
	     */
	    public static native void setVkModDepthSource(long image, int format, int width, int height);

	    /**
	     * Binds (or clears) the SCENE-COLOR source for the VK MV-gen shader's
	     * binding 4 (combined image sampler): the scaled framebuffer's color
	     * attachment VkImage, used by the thin-detail/cutout reactive term
	     * (flowers/grass/leaves — the "FSR4 can't handle flowers" fix,
	     * 2026-08-06). Called by the main-pass mixin every frame (cheap no-op
	     * unless the framebuffer was recreated). image==0 clears back to the
	     * 1x1 mid-gray dummy (detector disabled); format==0 is resolved
	     * natively from the swapchain format (the scaled framebuffer is built
	     * with the swapchain's format).
	     *
	     * @see #recordVkModMvGen(long)
	     */
	    public static native void setVkModColorSource(long image, int format, int width, int height);

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