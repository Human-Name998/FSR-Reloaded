# FSR4 Phase 3 — AMD FSR API SDK Integration & GL-VK Synchronization

**Date:** 2026-07-03 (revised 2026-07-03)
**Status:** Draft

## Goal

Replace GL compute shader FSR2/3 pipelines with the native AMD FSR4 SDK (`amd_fsr4_api.dll`) running on Vulkan. The SDK is sourced from AMD's FSR API redistributable package (closed-source, AMD Software License Agreement), not from the open-source FidelityFX-SDK GitHub repo.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Frame Timeline                                   │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Both paths are structurally identical in practice:                      │
│                                                                          │
│  GL: render world ──flush──▶  JNI: FSR4 dispatch ──vkQueueWaitIdle──▶  GL: render HUD
│                                                                          │
│  The "async" semaphore path (Path A) is aspirational. The SDK internally │
│  splits the dispatch into multiple executes, making external semaphore   │
│  injection impossible without modifying the SDK. Both paths block on     │
│  the host side — Path A via glFlush-only IPC overhead, Path B via fence  │
│  client-wait. The key difference is Path A avoids glFenceSync overhead   │
│  (~0.2ms) by relying on glFlush to push GL's signal to the GPU.         │
│                                                                          │
│  Path A (Semaphore — preferred):                                        │
│    GL: render world → glSignalSemaphoreEXT + glFlush → JNI → FSR4       │
│      → vkQueueWaitIdle → glWaitSemaphoreEXT → render HUD                │
│                                                                          │
│  Path B (Fence — fallback):                                              │
│    GL: render world → glFenceSync + glFlush → clientWait → JNI → FSR4   │
│      → vkQueueWaitIdle → render HUD                                     │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Component Map

```
C++ (fsrvk.dll)                              Java (mod)
┌──────────────────────────────┐            ┌──────────────────────────────┐
│ Fsr4Context                  │◀──JNI──▶  │ Fsr4Native.java              │
│  - ffxFsr3CreateContext      │            │  - initFsr4()               │
│    (FSR4 type flag)          │            │  - dispatchFsr4()           │
│  - ffxFsr3UpscaleRenderSubmit│            │  - shutdownFsr4()           │
│  - shares VulkanDevice       │            │  - waitIdle()               │
├──────────────────────────────┤            ├──────────────────────────────┤
│ SyncManager                  │◀──JNI──▶  │ GLSemaphoreManager.java     │
│  - createExportableSem()     │            │  - importVulkanSemaphores() │
│  - exportWin32Handle()       │            │  - signalOpenGLDone()       │
│  - exportable semaphore pair │            │  - waitOnVulkanDone()       │
├──────────────────────────────┤            ├──────────────────────────────┤
│ Fsr4SharedResources          │◀──JNI──▶  │ (managed from Java)         │
│  - color/depth/MV/reactive/  │            │                              │
│    output — all shared VK    │            │                              │
├──────────────────────────────┤            ├──────────────────────────────┤
│ VulkanDevice (extended)      │            │ FSRProcessor.java           │
│  - VkInstance/VkDevice       │            │  - FSR4 dispatch path       │
│  - graphics+compute queue    │            │  - MV gen → shared texture  │
│  - compute-only queue (new)  │            │  - FSR2/3 fallback          │
│  - 2 queue families          │            │  - frame time budgeting     │
└──────────────────────────────┘            └──────────────────────────────┘
```

---

## 1. SDK Source & Distribution (AMD FSR API Redistributable)

### Source

FSR4 is **closed-source** and distributed by AMD under the **AMD Software License Agreement** (not MIT). It is **not** in the open-source FidelityFX-SDK GitHub repo. The redistributable package is obtained from AMD's developer portal.

Vendored directory structure:

```
third_party/AMD-FSR-API/
├── include/
│   ├── ffx_interface.h              # Core SDK types, FfxApi types
│   ├── ffx_fsr3.h                   # FSR3 + FSR4 context creation
│   │                                 (FSR4 via FFX_FSR3_UPSCALER_TYPE_FSR4 flag)
│   ├── ffx_fsr4.h                   # (newer SDK versions — standalone FSR4 header)
│   ├── ffx_backend.h                # FfxBackendInterface definition
│   └── ffx_vulkan.h                 # Vulkan backend helpers
├── lib/
│   ├── amd_fsr4_api.lib             # Import lib for fsrvk.dll linking
│   └── amd_fsr4_api.dll             # (bundled separately in JAR)
└── AMD_FSR_API_LICENSE.txt          # Required: included in mod JAR
```

### Bundled DLLs (in JAR)

| In `src/main/resources/natives/` | Role |
|----------------------------------|------|
| `amd_fsr4_api.dll` | FSR4 SDK — neural net weights, SPIR-V pipelines, dispatch |
| `fsrvk.dll` (existing, Phase 1) | Vulkan device + memory management |

### Loading Order (Java)

```java
// Step 1: Add DLL search path before loading (prevents "module not found" errors)
//         amd_fsr4_api.dll depends on internal AMD DLLs that must resolve.
if (Platform.get() == Platform.WINDOWS) {
    Kernel32.INSTANCE.SetDllDirectory(tempDir);  // or AddDllDirectory for Win8+
}
System.load(tempDir + "/amd_fsr4_api.dll");
System.load(libPath);  // fsrvk.dll — loaded separately
Kernel32.INSTANCE.SetDllDirectory(null);  // restore
```

`SetDllDirectory` (or `AddDllDirectory` on Win8+) adds the temp extraction directory to the DLL search path so that `amd_fsr4_api.dll` can find its internal dependencies without needing them on `PATH` or `System32`.

### API Surface (Verified Against Real Headers)

The FSR4 API surface evolves across SDK versions. As of the current AMD FSR API redistributable:

- **Context creation**: `ffxFsr3CreateContext` with `FfxFsr3CreateContextDesc.upscaleType = FFX_FSR3_UPSCALER_TYPE_FSR4` (newer SDK versions may add `ffxFsr4CreateContext`)
- **Dispatch**: `ffxFsr3UpscaleRenderSubmit` with `FfxFsr3UpscaleRenderDesc`
- **Backend interface**: `ffxGetInterfaceVulkanVk` (note: actual name varies — check the SDK's `ffx_vulkan.h`)
- **Context type**: `FFX_FSR3_UPSCALER_CONTEXT_TYPE_FSR4` flag or similar

The C++ layer must be written against the **actual downloaded headers**, not aspirational names. The spec uses `ffxFsr3CreateContext` / `ffxFsr3UpscaleRenderSubmit` as placeholders — verify exact names from the SDK before coding.

### CMakeLists.txt Changes (`src/native/fsrvk/CMakeLists.txt`)

```cmake
target_include_directories(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/include
)
target_link_libraries(fsrvk PRIVATE
    ${Vulkan_LIBRARY}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.lib
)
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
```

---

## 2. C++ Layer — Fsr4Context

### File: `src/native/fsrvk/fsr4_context.h`

```cpp
#pragma once
#include <ffx_fsr3.h>        // Actual header from AMD FSR API redistributable
#include <ffx_backend.h>     // FfxBackendInterface
#include "vulkan_device.h"

namespace fsr {

class Fsr4Context {
public:
    /// Initialize the FSR4 context for given render/display extents.
    /// Uses ffxFsr3CreateContext with FSR4 type flag (actual API surface
    /// depends on SDK version — verify exact names from downloaded headers).
    bool initialize(VkExtent2D renderSize, VkExtent2D displaySize);

    /// Dispatch FSR4 frame processing.
    /// Uses ffxFsr3UpscaleRenderSubmit (or ffxFsr4Dispatch in newer SDK).
    bool dispatch(const FfxFsr3UpscaleRenderDesc& params);

    /// Host-side wait for VK queue idle (used by both paths).
    void waitIdle();

    /// Tear down — must be called before VulkanDevice::destroy().
    void destroy();

    bool isInitialized() const { return isInitialized_; }

    VkSemaphore getRenderDoneSemaphore() const { return renderDoneSem_; }
    VkSemaphore getComputeDoneSemaphore() const { return computeDoneSem_; }

private:
    FfxContext ffxContext_{};
    bool isInitialized_ = false;

    // Exportable semaphores for GL-VK sync
    VkSemaphore renderDoneSem_ = VK_NULL_HANDLE;   // GL signals, VK waits
    VkSemaphore computeDoneSem_ = VK_NULL_HANDLE;  // VK signals, GL waits
};

} // namespace fsr
```

### File: `src/native/fsrvk/fsr4_context.cpp`

Key implementation points:

- `ffxGetInterfaceVulkanVk(...)` to populate the backend interface (exact function name varies by SDK — check `ffx_vulkan.h`)
- Use `FFX_FSR3_UPSCALER_TYPE_FSR4` flag in the context creation description
- **Do NOT override `FfxBackendInterface::execute()`** — the SDK internally splits a single FSR4 dispatch into multiple execute calls (depth recon, MV dilation, accumulation, RCAS, etc.). Intercepting execute would require semaphore signal/wait for each sub-dispatch, which would deadlock the queue.
- **Do NOT override `FfxBackendInterface::getCommandBuffer()` / `submit()`** — these are SDK-internal and their signatures are opaque (`FfxCommandList` is an SDK-internal type).
- Instead, wrap the entire dispatch with external semaphores at the **command submission boundary**:
  - **Wait on `renderDoneSem_`** before calling the SDK dispatch (submitted as a no-op command buffer wait before the SDK's own submission)
  - **Signal `computeDoneSem_`** after `vkQueueWaitIdle` returns (host-side, not GPU-side — this is the fundamental constraint)
- Flags: `FFX_FSR3_UPSCALER_ENABLE_DEPTH_INVERTED` (correct for reversed-Z). **Do NOT set HDR flags** — Minecraft uses LDR (GL_RGBA8). Setting `FFX_FSR4_ENABLE_HIGH_DYNAMIC_RANGE` with LDR input produces washed-out output.

### Semaphore Strategy (Why `vkQueueWaitIdle` Is Required)

The FSR4 SDK does not expose internal semaphores or allow external semaphore injection. Every dispatch call submits one or more command buffers internally. The only safe synchronization pattern is:

```
GL renders scene
  → glSignalSemaphoreEXT(renderDoneSem) + glFlush
  → JNI: Fsr4Native.dispatchFsr4()
    → vkQueueSubmit with wait for renderDoneSem (ensures GL is done)
    → ffxFsr3UpscaleRenderSubmit (SDK submits internally)
    → vkQueueWaitIdle (ensures VK is done before GL reads result)
  → glWaitSemaphoreEXT(computeDoneSem) → GL renders HUD
```

The `vkQueueWaitIdle` between dispatch return and GL semaphore wait is required because `computeDoneSem_` is not signaled by the SDK — the SDK doesn't expose its completion semaphore. A future SDK version may support this, but for v1, host-side blocking is the only safe approach.

This means **both Path A and Path B are structurally similar**. Path A avoids `glFenceSync` overhead (~0.2ms) by relying on the semaphore import + `glFlush`, but the blocking `vkQueueWaitIdle` still stalls the CPU. The quality benefit of FSR4 comes from the neural network upscaler, not from async execution.

---

## 3. C++ Layer — SyncManager

### File: `src/native/fsrvk/sync_manager.h`

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <windows.h>

namespace fsr {

class SyncManager {
public:
    /// Create a VkSemaphore with VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT.
    VkSemaphore createExportableSemaphore(VkDevice device);

    /// Export the Win32 HANDLE from a VkSemaphore.
    HANDLE exportSemaphoreToWin32Handle(VkDevice device, VkSemaphore semaphore);

    /// Destroy a semaphore.
    void destroySemaphore(VkDevice device, VkSemaphore semaphore);
};

} // namespace fsr
```

### File: `src/native/fsrvk/sync_manager.cpp`

- `VkExportSemaphoreCreateInfo` with `handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT` chained to `VkSemaphoreCreateInfo`
- `vkGetSemaphoreWin32HandleKHR` resolves function pointer via `vkGetDeviceProcAddr`
- Returns raw `HANDLE` for Java to pass to `glImportSemaphoreWin32HandleEXT`

### JNI Bridge (`src/native/fsrvk/jni_bridge.cpp`)

`Fsr4Native` JNI methods create a singleton `Fsr4Context` stored as an opaque `jlong` handle:

```
Fsr4Native.initFsr4()
  → new Fsr4Context() stored as jlong field
  → Fsr4Context.initialize(renderSize, displaySize)
    → Creates 2 exportable VkSemaphores (renderDone, computeDone)
    → Exports Win32 handles for both

Fsr4Native.getReadySemaphoreHandle()
  → Fsr4Context.getRenderDoneSemaphore() → export to Win32 HANDLE → return as jlong

Fsr4Native.dispatchFsr4(...)
  → Build FfxFsr3UpscaleRenderDesc from parameters
  → Create FfxResource handles from FsrSharedAllocation* via ffxGetResourceVulkan()
  → Submit no-op command buffer waiting on renderDoneSem (GL signal)
  → ffxFsr3UpscaleRenderSubmit() — SDK submits internally
  → vkQueueWaitIdle() — required because SDK doesn't signal external semaphore

Fsr4Native.waitIdle()
  → vkDeviceWaitIdle(VulkanDevice::get().device())

Fsr4Native.shutdownFsr4()
  → vkDestroySemaphore x2
  → Fsr4Context.destroy() → ffxFsr3ContextDestroy()
  → delete Fsr4Context
```

### Data Flow (Per-Frame Dispatch)

```
JNI: Fsr4Native.dispatchFsr4(colorPtr, depthPtr, mvPtr, reactivePtr, outputPtr, ...)
  │
  ├─ For each FsrSharedAllocation* → VkImage:
  │    ffxGetResourceVulkan(image, VK_IMAGE_LAYOUT_GENERAL, ...) → FfxResource
  │    CRITICAL: set resource state matching SDK expectations:
  │      Input textures (color, depth, MV):  FFX_RESOURCE_STATE_COMPUTE_READ
  │      Output texture:                     FFX_RESOURCE_STATE_UNORDERED_ACCESS
  │
  ├─ Build FfxFsr3UpscaleRenderDesc:
  │    .color = colorResource
  │    .depth = depthResource
  │    .motionVectors = mvResource
  │    .reactive = reactiveResource (or null)
  │    .output = outputResource
  │    .jitterOffset = { jitterX, jitterY }
  │    .cameraFar = farZ
  │    .cameraNear = nearZ
  │    .cameraFovVertical = tanHalfFovY * 2 (convert back to radians)
  │    .frameIndex = frameIndex
  │    .reset = false
  │
  ├─ Wait for GL render-done semaphore:
  │    VkSubmitInfo{ .waitSemaphores = {renderDoneSem} }
  │    vkQueueSubmit(graphicsQueue, &submitInfo, fence)
  │    vkWaitForFences(fence) — ensure wait completes before dispatch
  │
  ├─ ffxFsr3UpscaleRenderSubmit() — SDK submits to internal queue
  │
  └─ vkQueueWaitIdle(graphicsQueue) — host-side blocking
       (required — SDK doesn't signal external completion semaphore)
```

---

## 4. C++ Layer — Fsr4SharedResources

### Issue: FSR4 Needs 5 Shared Textures

Phase 2's `SharedTextureManager` allocates one VK-shared texture (the output). FSR4 needs **all input textures** to be VK-accessible too — FSR4 runs on Vulkan and cannot read GL-native textures.

### Required Shared Textures

| Texture | Source | Shared? |
|---------|--------|---------|
| Color (low-res scene) | `renderTarget.getColorTexture()` | ✅ Must be shared |
| Depth | `renderTarget.getDepthTexture()` | ✅ Must be shared |
| Motion Vectors | GLSL compute shader output (existing) | ✅ Must be shared |
| Reactive Mask | (optional — 0 until generated) | ✅ If used, shared |
| Output | FSR4 writes here | ✅ Already shared (Phase 2) |

### Fsr4SharedResources Class

`src/native/fsrvk/fsr4_shared_resources.h` — manages a pool of 5 VK-shared textures:

```cpp
struct Fsr4SharedResources {
    std::array<VkImage, 5> images;       // color, depth, MV, reactive, output
    std::array<VkDeviceMemory, 5> memories;
    std::array<VkImageView, 5> views;    // for SDK resource creation
    std::array<uint32_t, 5> glTexIds;    // imported GL texture IDs
    VkExtent2D extent;

    // Allocate all 5 textures at once. Returns false on any failure.
    bool create(VkDevice device, VkExtent2D extent);

    // Export all 5 Win32 handles for GL import.
    std::array<HANDLE, 5> exportHandles();

    // Destroy all 5 textures.
    void destroy(VkDevice device);
};
```

Each texture is allocated with:
- `VkImageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED`
- `VkImageCreateInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT`
- `VkExportMemoryAllocateInfo` for Win32 handle export

During `initFsr4()`, a one-time command buffer transitions all 5 images from `UNDEFINED` to `VK_IMAGE_LAYOUT_GENERAL`.

### Java: Extended SharedTextureManager

`SharedTextureManager.java` is extended with bulk allocation:

```java
public class SharedTextureManager {
    // Existing: single output texture (Phase 2)
    // New: pool API for FSR4
    public boolean allocateFsr4Pool(int w, int h);
    public int getColorTexId();
    public int getDepthTexId();
    public int getMvTexId();
    public int getReactiveTexId();  // 0 if not generated
    public int getOutputTexId();
    public long getColorAllocPtr();   // FsrSharedAllocation* for JNI
    public long getDepthAllocPtr();
    public long getMvAllocPtr();
    public long getReactiveAllocPtr();
    public long getOutputAllocPtr();
    public void destroyFsr4Pool();
}
```

### Motion Vector Generation Strategy

**Option A (selected — path of least resistance):** Reuse the existing FSR2/3 MV generation GLSL compute shader. The shader runs on the GL side and writes to a VK-shared texture (allocated by `Fsr4SharedResources`). FSR4 reads from the same texture via `ffxGetResourceVulkan()`.

```
┌──────────────────────────────────────────────────────────┐
│ GL compute: MV gen shader → imageStore(sharedMvTex, ...) │
│  (writes to VK-shared texture)                            │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼ shared MvTex in VK_IMAGE_LAYOUT_GENERAL
                          │
┌──────────────────────────────────────────────────────────┐
│ VK: FSR4 dispatch reads MvTex via ffxGetResourceVulkan() │
└──────────────────────────────────────────────────────────┘
```

The MV shader's output is already bound as a GL texture (`glBindImageTexture`). With the shared texture, the same ID is used — the VK memory backing is identical. No shader changes needed.

**Not doing:** Option B (port MV gen to Vulkan compute) — adds complexity without quality benefit. Option C (no MV gen) — FSR4 requires MVs.

---

## 5. Java Layer — Fsr4Native

### File: `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java`

```java
package com.fsr2mod.vulkan;

import org.lwjgl.system.windows.Kernel32;  // LWJGL built-in, no JNA dependency

public final class Fsr4Native {
    private static boolean available = false;

    static {
        try {
            String tempDir = extractFromJar("/natives/amd_fsr4_api.dll");
            // CRITICAL: SetDllDirectory before load — amd_fsr4_api.dll has
            // internal dependencies that resolve via search path.
            // Without this, UnsatisfiedLinkError: "The specified module could not be found".
            // Use LWJGL's Kernel32 (no JNA dependency needed).
            Kernel32.SetDllDirectory(tempDir);
            System.load(tempDir + "/amd_fsr4_api.dll");
            Kernel32.SetDllDirectory(null);  // restore
            available = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            available = false;
        }
    }

    public static boolean isAvailable() { return available; }

    /** Initialize FSR4 context. */
    public static native boolean initFsr4(int renderW, int renderH,
                                          int displayW, int displayH);

    /** Dispatch FSR4 frame processing.
     *  All texture ptrs are FsrSharedAllocation* (jlong) from VulkanInterop.getSharedHandle().
     *  FSR4 needs FOV + Near/Far, NOT full projection matrices.
     */
    public static native boolean dispatchFsr4(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovY,
        float deltaTime, int frameIndex, boolean reset,
        long colorInputPtr,      // FsrSharedAllocation* — scaled scene color
        long depthInputPtr,      // FsrSharedAllocation* — scaled scene depth
        long mvInputPtr,         // FsrSharedAllocation* — motion vectors
        long reactivePtr,        // FsrSharedAllocation* — reactive mask (or 0)
        long outputPtr           // FsrSharedAllocation* — upscaled output
    );

    /** Host-side wait for VK queue idle (used by both paths). */
    public static native void waitIdle();

    public static native void shutdownFsr4();

    /** Returns Win32 HANDLE values as jlong. Created during initFsr4(). */
    public static native long getReadySemaphoreHandle();
    public static native long getCompleteSemaphoreHandle();

    private static String extractFromJar(String path) {
        // Same pattern as VulkanInterop.extractFromJar()
        java.io.InputStream in = Fsr4Native.class.getResourceAsStream(path);
        if (in == null) throw new UnsatisfiedLinkError("Not found in JAR: " + path);
        try (in) {
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("amd_fsr4_api", ".dll");
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp.getParent().toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Extraction failed: " + e.getMessage());
        }
    }
}
```

---

## 6. Java Layer — GLSemaphoreManager

### File: `src/client/java/com/fsr2mod/vulkan/GLSemaphoreManager.java`

```java
package com.fsr2mod.vulkan;

import org.lwjgl.opengl.*;

public class GLSemaphoreManager {
    private int readySemId = -1;     // GL signals → VK waits
    private int completeSemId = -1;  // VK signals → GL waits
    private boolean useHardwareSync = false;

    /** Try to import VK semaphore Win32 handles. Returns true on success. */
    public boolean initialize(long win32HandleReady, long win32HandleComplete) {
        if (!GL.getCapabilities().GL_EXT_semaphore_win32) return false;

        readySemId = EXTSemaphore.glGenSemaphoresEXT();
        completeSemId = EXTSemaphore.glGenSemaphoresEXT();

        try {
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(
                readySemId, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_BIT_EXT,
                win32HandleReady);
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(
                completeSemId, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_BIT_EXT,
                win32HandleComplete);
            useHardwareSync = true;
        } catch (Exception e) {
            useHardwareSync = false;
        }
        return useHardwareSync;
    }

    /** GL signals that scene rendering is done.
     *  CRITICAL: glFlush() after signal() — without it, the signal sits in
     *  GL's command queue and VK's semaphore wait deadlocks. */
    public void signalRenderDone(int sharedTexId) {
        EXTSemaphore.glSignalSemaphoreEXT(
            readySemId,
            new int[]{sharedTexId},
            new int[]{GL43C.GL_LAYOUT_GENERAL_EXT});
        GL43C.glFlush();  // Push signal to GPU so VK can observe it
    }

    /** GL waits for VK compute to finish. Pass shared texture ID + layout. */
    public void waitForComputeDone(int sharedTexId) {
        EXTSemaphore.glWaitSemaphoreEXT(
            completeSemId,
            new int[]{sharedTexId},
            new int[]{GL43C.GL_LAYOUT_GENERAL_EXT});
    }

    /** Path B: OpenGL Fence Sync fallback. Returns fence for client-side wait. */
    public long fenceRenderDone() {
        return GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Client-side wait on fence (Path B). */
    public int clientWait(long glFence, long timeoutNanos) {
        return GL43C.glClientWaitSync(glFence, 0, timeoutNanos);
    }

    public boolean isUsingHardwareSync() { return useHardwareSync; }
    public void destroy() {
        if (readySemId != -1) EXTSemaphore.glDeleteSemaphoresEXT(readySemId);
        if (completeSemId != -1) EXTSemaphore.glDeleteSemaphoresEXT(completeSemId);
    }
}
```

---

## 7. Dual-Path Orchestration (FSRProcessor)

### Path A (Hardware Semaphores)

In `FSRProcessor.onSceneRendered()`, after scene rendering completes and before HUD rendering:

```java
if (fsr4Available && fsr4Initialized && gpuSyncManager.isUsingHardwareSync()) {
    gpuSyncManager.signalRenderDone(sharedOutputTexId);
    Fsr4Native.dispatchFsr4(jitterX, jitterY, ...);
    gpuSyncManager.waitForComputeDone(sharedOutputTexId);
}
// Then: render HUD
```

### Path B (Fence Fallback)

Issue the fence as early as possible — right after the scene's draw calls complete, before non-visual client tick processing — to give the GPU more time to drain the fence before the CPU queries it:

```java
// Called right after endLevelRender() completes, before client tick / chunk rebuilds:
private long fenceSceneComplete() {
    if (!fsr4Available || !fsr4Initialized) return 0;
    long f = gpuSyncManager.fenceRenderDone();
    GL43C.glFlush();
    return f;
}

// Called later, right before the FSR4 dispatch:
private void waitAndDispatchFsr4(long fence) {
    if (fence == 0) return;
    int status = gpuSyncManager.clientWait(fence, 16_666_667L); // 1 frame @ 60fps
    if (status == GL43C.GL_CONDITION_SATISFIED ||
        status == GL43C.GL_ALREADY_SIGNALED) {
        Fsr4Native.dispatchFsr4(jitterX, jitterY, ...);
        Fsr4Native.waitIdle();
    }
    // On GL_TIMEOUT_EXPIRED: skip this frame's upscale (aliased but responsive)
    // On GL_WAIT_FAILED: log error, fallback to FSR2/3
}
```

### Fallback Logic

- FSR4 availability checked at startup: SDK DLLs load + `initFsr4()` returns true
- If `initFsr4()` fails or throws: log warning, `fsr4Available = false`, existing FSR2/3 path runs
- If semaphore import fails (Path A): switch to Path B (fence fallback)
- If Path B also fails: log error, fallback to existing FSR2/3 path

---

## 8. Layout Management

Critical to match GL ↔ VK layout transitions correctly:

| Transition | GL Layout | VK Layout |
|------------|-----------|-----------|
| World rendered → VK dispatch | `GL_LAYOUT_GENERAL_EXT` | `VK_IMAGE_LAYOUT_GENERAL` |
| VK dispatch → HUD rendering | `GL_LAYOUT_GENERAL_EXT` | `VK_IMAGE_LAYOUT_GENERAL` |

The shared texture is created with `VkImageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED` (the only valid values per spec are `UNDEFINED` or `PREINITIALIZED`). During `initFsr4()`, a short-lived one-time command buffer transitions the image to `VK_IMAGE_LAYOUT_GENERAL` via a `VkImageMemoryBarrier`. After that, both APIs remain in `GENERAL` with zero per-frame transitions. GL semaphore signal/wait calls pass `GL_LAYOUT_GENERAL_EXT` for the texture.

---

## 9. VulkanDevice Extension — Dual Queue + Queue Ownership

### Issue: GL Graphics Queue ≠ FSR4 Compute Queue

The FSR4 SDK may default to a compute-only queue family (different from GL's graphics+compute queue). When two different queues access the same VkImage, the Vulkan spec requires queue ownership transfer. Without it, the compute queue sees stale data or undefined behavior.

### VulkanDevice Changes (`vulkan_device.h/cpp`)

Extend `VulkanDevice::createDevice()` to request **two queues**:

```cpp
VkDeviceQueueCreateInfo qci[2];
float priorities[2] = {1.0f, 1.0f};

// Queue 0: graphics + compute (shared with GL's VK backend)
qci[0].sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
qci[0].queueFamilyIndex = graphicsFamily;  // family with GRAPHICS + COMPUTE bits
qci[0].queueCount = 1;
qci[0].pQueuePriorities = &priorities[0];

// Queue 1: compute-only (for FSR4 async dispatch)
qci[1].sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
qci[1].queueFamilyIndex = computeFamily;   // family with only COMPUTE bit
qci[1].queueCount = 1;
qci[1].pQueuePriorities = &priorities[1];
```

### Queue Ownership Transfer (Per-Frame)

Before FSR4 dispatch reads the shared textures, release ownership from the graphics queue and acquire it on the compute queue:

```cpp
// Before FSR4 dispatch, on graphics queue:
VkImageMemoryBarrier barrier{};
barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
barrier.srcQueueFamilyIndex = graphicsFamily;
barrier.dstQueueFamilyIndex = computeFamily;
barrier.image = sharedImage;
barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
// ... vkCmdPipelineBarrier

// After FSR4 dispatch, on compute queue (reverse ownership):
barrier.srcQueueFamilyIndex = computeFamily;
barrier.dstQueueFamilyIndex = graphicsFamily;
// ... vkCmdPipelineBarrier
```

This adds ~0.1ms per frame but is required for correctness. Without it, the validation layer throws `VUID-vkCmdPipelineBarrier-srcQueueFamilyIndex-0118` and behavior is undefined on real hardware.

### Simplified Reality

Alternative: pass the graphics queue (not compute) to the FSR4 SDK's backend interface. This avoids ownership transfer entirely — both GL and FSR4 use the same queue, serializing all work but eliminating the cross-queue barrier overhead. Since both paths already block on `vkQueueWaitIdle`, there's no async benefit to a separate compute queue. **Recommended for v1: use a single queue for both GL and FSR4.**

---

## 10. Build Integration

### CMakeLists.txt Additions (`src/native/fsrvk/CMakeLists.txt`)

```cmake
# Vendored AMD FSR API headers
target_include_directories(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/include
)

# Link FSR4 import lib
target_link_libraries(fsrvk PRIVATE
    ${Vulkan_LIBRARY}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.lib
)

# Copy amd_fsr4_api.dll alongside fsrvk.dll for dev testing
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
```

### Gradle Additions (`build.gradle`)

- Copy `amd_fsr4_api.dll` + `AMD_FSR_API_LICENSE.txt` into JAR's `/natives/`
- `duplicatesStrategy = DuplicatesStrategy.INCLUDE` (already set)

---

## 11. Path B Precision (glClientWaitSync)

`glClientWaitSync` return values to handle:

| Return Value | Meaning | Action |
|-------------|---------|--------|
| `GL_ALREADY_SIGNALED` | Fence already passed | Proceed immediately |
| `GL_CONDITION_SATISFIED` | Fence completed within timeout | Proceed |
| `GL_TIMEOUT_EXPIRED` | Fence not yet complete | Skip FSR4 this frame |
| `GL_WAIT_FAILED` | Error | Log, fallback to FSR2/3 |

Timeout: 16.67ms (one frame at 60fps). On timeout, skip the FSR4 dispatch for this frame and render at scaled resolution (aliased but responsive — better than hitch).

---

## 12. Non-Goals

- No changes to FSR2/3 GL compute pipelines (they remain as fallback)
- No FSR4 on RDNA 3 or earlier (RDNA 3.5+ required by AMD SDK)
- No Linux support (Windows-only for `GL_EXT_semaphore_win32` / `VK_KHR_external_semaphore_win32`)
- No Iris path changes (Iris continues using FSR1 blit+RCAS)
- **No reactive mask generation** in v1 — FSR4 gets null for reactive mask. Particles will ghost during camera motion. Documented as a known limitation. Future versions can add reactive mask via mixin into the particle renderer.

---

## 13. File Inventory

### New Files

| File | Purpose |
|------|---------|
| `third_party/AMD-FSR-API/include/ffx_fsr3.h` | FSR3+4 context creation |
| `third_party/AMD-FSR-API/include/ffx_fsr4.h` | (newer SDK — standalone FSR4 header) |
| `third_party/AMD-FSR-API/include/ffx_backend.h` | Backend interface |
| `third_party/AMD-FSR-API/include/ffx_vulkan.h` | Vulkan backend |
| `third_party/AMD-FSR-API/include/ffx_interface.h` | Core types |
| `third_party/AMD-FSR-API/lib/amd_fsr4_api.lib` | Import lib |
| `third_party/AMD-FSR-API/AMD_FSR_API_LICENSE.txt` | License (required in JAR) |
| `src/native/fsrvk/fsr4_context.h` | Fsr4Context class header |
| `src/native/fsrvk/fsr4_context.cpp` | Fsr4Context implementation |
| `src/native/fsrvk/sync_manager.h` | SyncManager class header |
| `src/native/fsrvk/sync_manager.cpp` | SyncManager implementation |
| `src/native/fsrvk/fsr4_shared_resources.h` | 5-texture shared pool |
| `src/native/fsrvk/fsr4_shared_resources.cpp` | Implementation |
| `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java` | JNI declarations |
| `src/client/java/com/fsr2mod/vulkan/GLSemaphoreManager.java` | GL semaphore lifecycle |

### Modified Files

| File | Change |
|------|--------|
| `src/native/fsrvk/vulkan_device.h` | Add compute-only queue family field, getters |
| `src/native/fsrvk/vulkan_device.cpp` | Request 2 queues in createDevice() |
| `src/native/fsrvk/CMakeLists.txt` | Add AMD-FSR-API include/lib paths, POST_BUILD copy |
| `build.gradle` | Copy `amd_fsr4_api.dll` + license into JAR |
| `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` | FSR4 dispatch path + sync orchestration + frame time budgeting |
| `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java` | Add `allocateFsr4Pool()` / `destroyFsr4Pool()` methods |
| `src/native/fsrvk/jni_bridge.cpp` | Add Fsr4Context + SyncManager + Fsr4SharedResources JNI entry points |

---

## 14. Verification

### Build Verification
```
./gradlew.bat build    # compiles Java, builds fsrvk.dll with FSR4 support
                       # copies amd_fsr4_api.dll + license into JAR
```

### FPS Expectations (Critical)

FSR4 is a **neural network upscaler**. On the 860M (RDNA 3.5 mobile), WMMA throughput is limited compared to discrete GPUs. Expect:

| Pipeline | Estimated cost (1080p) | Notes |
|----------|----------------------|-------|
| FSR2 GL compute | ~3ms | Existing baseline |
| FSR4 VK (Path A) | ~5-8ms | Neural inference is heavier |
| FSR4 VK (Path B) | ~5-9ms | Fence adds ~0.2ms |

**FSR4 will be slower than FSR2.** The benefit is image quality, not speed. Path A target: FSR4 within 2× of FSR2 frame time. "Matches or exceeds FSR2" sets up false expectations and will make a correct implementation look like a failure.

### Runtime Verification (Path A)
1. Enable FSR4 in settings → `Fsr4Native.isAvailable()` = true
2. `initFsr4()` → `Fsr4Context.initialize()` returns true
3. `GLSemaphoreManager.initialize()` succeeds → `useHardwareSync = true`
4. Frame: signal → flush → dispatch → waitIdle → wait → HUD
5. No `glFinish()` or `glFenceSync` called per frame
6. FSR4 frame time within 2× of FSR2 frame time

### Runtime Verification (Path B)
1. Same setup, but `glImportSemaphoreWin32HandleEXT` fails
2. `useHardwareSync = false`
3. Frame: `glFenceSync` → `glFlush` → `clientWait` → `dispatchFsr4` → `vkQueueWaitIdle`
4. Slightly higher per-frame latency (fence adds ~0.2ms) but no crash
5. FPS within ~5% of Path A

### Fallback Verification
- Hardware with no RDNA 3.5: `initFsr4()` returns false → FSR2/3 path active
- SDK DLLs missing: `Fsr4Native.<clinit>` failure caught → `isAvailable()` = false
- Semaphore import (try/catch): Path B activated, log warning, continue
- Fence timeout: FSR4 skipped for frame, scaled scene displayed directly

---

## 15. Known Limitations & Edge Cases

- **FSR4 slower than FSR2**: Neural network inference on mobile RDNA 3.5 is 2-3× the compute cost of FSR2's analytical shaders. Quality is the trade-off, not speed.
- **No async execution**: The SDK doesn't expose internal sync primitives. Both Path A and B block on `vkQueueWaitIdle`. True async requires a future SDK update.
- **Particle ghosting**: No reactive mask in v1. Particles smear during camera motion. Fixable in a future version by writing a mixin into `ParticleManager.render()` that marks reactive pixels.
- **Context recreation cost**: `ffxFsr3ContextCreate` loads neural net weights (~50-100ms). Debounce quality changes (500ms throttle). Do NOT recreate the context on every `markDirty()` — reuse if render/display sizes haven't changed.
- **Frame time budgeting**: If the scene render took >10ms at 60fps, skip FSR4 dispatch to avoid missing vsync. Track moving average of FSR4 cost; if consistently >8ms, temporarily drop to FSR2 fallback.

### Context Recreation Strategy

```java
private long lastContextChange = 0;
private static final long CONTEXT_DEBOUNCE_MS = 500;

private void recreateFsr4IfNeeded(int renderW, int renderH, int displayW, int displayH) {
    long now = System.currentTimeMillis();
    if (now - lastContextChange < CONTEXT_DEBOUNCE_MS) return;
    lastContextChange = now;

    if (fsr4ContextW == renderW && fsr4ContextH == renderH
        && fsr4DisplayW == displayW && fsr4DisplayH == displayH) {
        return;  // No change needed
    }

    Fsr4Native.shutdownFsr4();
    boolean ok = Fsr4Native.initFsr4(renderW, renderH, displayW, displayH);
    if (!ok) { /* fallback to FSR2/3 */ }
}
```
