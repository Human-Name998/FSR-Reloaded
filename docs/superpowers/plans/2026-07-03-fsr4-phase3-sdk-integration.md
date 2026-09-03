# FSR4 Phase 3 — AMD FSR API SDK Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the closed-source AMD FSR4 SDK into the mod, replacing GL compute shader FSR2/3 with native Vulkan FSR4 dispatch, with dual-path GL-VK synchronization (hardware semaphores or fence fallback).

**Architecture:** Extend `fsrvk.dll` with `Fsr4Context` (wraps `ffxFsr3CreateContext`/`ffxFsr3UpscaleRenderSubmit` with FSR4 type flag), `SyncManager` (exportable VkSemaphore → Win32 handle), and `Fsr4SharedResources` (5 shared VK textures). Java receives `GLSemaphoreManager` (imports semaphores, `glSignalSemaphoreEXT` + `glFlush`) and `Fsr4Native` JNI bridge. FSRProcessor orchestrates the dual-path flow. Single VkQueue for v1 (avoids cross-queue ownership transfer). MV gen reuses existing GLSL compute shader writing to shared texture.

**Tech Stack:** C++20, Java 25, LWJGL 3 (no JNA), CMake 3.20+, MSVC 2022, Vulkan SDK 1.4+, AMD FSR API redistributable (closed-source), Fabric Loom 1.16-SNAPSHOT

## Global Constraints

- All C++ code guarded by `#ifdef _WIN32` (Windows-only: `GL_EXT_semaphore_win32` / `VK_KHR_external_semaphore_win32`)
- No JNA dependency — use LWJGL's `org.lwjgl.system.windows.Kernel32` for `SetDllDirectory`
- Single VkQueue for v1 (graphics+compute queue shared between GL and FSR4 — avoids cross-queue ownership transfer)
- `VK_IMAGE_LAYOUT_UNDEFINED` at creation → one-time barrier to `VK_IMAGE_LAYOUT_GENERAL` during init
- LDR flag (`FFX_FSR3_UPSCALER_ENABLE_LOW_DYNAMIC_RANGE` or omit HDR flag) — Minecraft is GL_RGBA8
- Depth-inverted flag: `FFX_FSR3_UPSCALER_ENABLE_DEPTH_INVERTED` (Minecraft uses reversed-Z)
- All 5 FSR4 textures (color, depth, MV, reactive, output) must be VK-shared — `Fsr4SharedResources`
- FfxResource state: input textures = `FFX_RESOURCE_STATE_COMPUTE_READ`, output = `FFX_RESOURCE_STATE_UNORDERED_ACCESS`
- FSR4 context recreation debounced to 500ms minimum interval
- Frame time budgeting: skip FSR4 if scene render > 10ms at 60fps

---

### Task 1: Vendor SDK Headers + Build System

**Files:**
- Create: `third_party/AMD-FSR-API/include/ffx_interface.h`
- Create: `third_party/AMD-FSR-API/include/ffx_fsr3.h`
- Create: `third_party/AMD-FSR-API/include/ffx_backend.h`
- Create: `third_party/AMD-FSR-API/include/ffx_vulkan.h`
- Create: `third_party/AMD-FSR-API/include/ffx_error.h`
- Create: `third_party/AMD-FSR-API/lib/amd_fsr4_api.lib`
- Create: `third_party/AMD-FSR-API/AMD_FSR_API_LICENSE.txt`
- Modify: `src/native/fsrvk/CMakeLists.txt`
- Modify: `build.gradle`
- Modify: `src/main/resources/fabric.mod.json`

**Interfaces:**
- Consumes: existing `vulkan_device.h/cpp` (Phase 1)
- Produces: FSR4 SDK headers + lib available at known paths for compilation

- [ ] **Step 1: Create vendor directory structure**

```bash
mkdir -p third_party/AMD-FSR-API/include
mkdir -p third_party/AMD-FSR-API/lib
```

- [ ] **Step 2: Download SDK headers from AMD developer portal**

Place the downloaded headers from the AMD FSR API redistributable package into `third_party/AMD-FSR-API/include/`. The exact filenames depend on the SDK version — expected files include `ffx_interface.h`, `ffx_fsr3.h`, `ffx_backend.h`, `ffx_vulkan.h`, `ffx_error.h`.

- [ ] **Step 3: Copy SDK import lib + license**

Place `amd_fsr4_api.lib` (import lib) into `third_party/AMD-FSR-API/lib/`. Place `AMD_FSR_API_LICENSE.txt` at `third_party/AMD-FSR-API/AMD_FSR_API_LICENSE.txt`.

- [ ] **Step 4: Update CMakeLists.txt**

Read `src/native/fsrvk/CMakeLists.txt`. Add after the existing `target_link_libraries` line:

```cmake
# AMD FSR API SDK headers
target_include_directories(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/include
)

# Link FSR4 import lib
target_link_libraries(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.lib
)

# Copy amd_fsr4_api.dll alongside fsrvk.dll for dev testing
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
```

- [ ] **Step 5: Update build.gradle**

Read `build.gradle`. Add `amd_fsr4_api.dll` and `AMD_FSR_API_LICENSE.txt` to the `processResources` block:

```groovy
processResources {
    dependsOn 'buildNative'
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from("${project.rootDir}/src/native/fsrvk/build/Release") {
        include 'fsrvk.dll'
        into 'natives'
    }
    from("${project.rootDir}/third_party/AMD-FSR-API/lib") {
        include 'amd_fsr4_api.dll'
        into 'natives'
    }
    from("${project.rootDir}/third_party/AMD-FSR-API") {
        include 'AMD_FSR_API_LICENSE.txt'
        into 'natives'
    }
}
```

- [ ] **Step 6: Add license reference to fabric.mod.json**

Read `src/main/resources/fabric.mod.json`. Add a `"license"` field or note in the mod description that this mod includes components under the AMD Software License Agreement. The exact path to `AMD_FSR_API_LICENSE.txt` in the JAR is `/natives/AMD_FSR_API_LICENSE.txt`.

- [ ] **Step 7: Build to verify includes resolve**

Run: `.\gradlew.bat build`
Expected: fsrvk.dll compiles (no code yet, but the include paths + link step succeed)

- [ ] **Step 8: Commit**

```bash
git add third_party/AMD-FSR-API/ src/native/fsrvk/CMakeLists.txt build.gradle src/main/resources/fabric.mod.json
git commit -m "Phase 3 Task 1: Vendor AMD FSR API SDK headers + configure build"
```

---

### Task 2: Extend VulkanDevice with Dual Queue

**Files:**
- Modify: `src/native/fsrvk/vulkan_device.h`
- Modify: `src/native/fsrvk/vulkan_device.cpp`

**Interfaces:**
- Produces: `VulkanDevice::computeQueueFamilyIndex() → uint32_t`, `VulkanDevice::computeQueue() → VkQueue` — consumed by Task 3, Task 4

- [ ] **Step 1: Add compute queue fields to header**

Read `src/native/fsrvk/vulkan_device.h`. Add after the `queue_` field:

```cpp
uint32_t computeQueueFamily_ = UINT32_MAX;
VkQueue computeQueue_ = VK_NULL_HANDLE;
```

Add public getters:

```cpp
uint32_t computeQueueFamilyIndex() const { return computeQueueFamily_; }
VkQueue computeQueue() const { return computeQueue_; }
```

- [ ] **Step 2: Modify createDevice() to request two queues**

Read `src/native/fsrvk/vulkan_device.cpp`. Replace the current `VkDeviceQueueCreateInfo qci{...}` block:

```cpp
float priorities[2] = {1.0f, 1.0f};

// We request 2 queues from the same family in v1:
// Queue 0: graphics+compute (shared with GL)
// Queue 1: compute (available but same family — no ownership transfer needed)
// This is a v1 simplification. In v1.5, a dedicated compute-only queue
// can be added for true async, with queue ownership transfer barriers.

VkDeviceQueueCreateInfo qci[1];  // single family, 2 queues
qci[0].sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
qci[0].queueFamilyIndex = queueFamily_;  // graphics+compute family
qci[0].queueCount = 2;                    // 2 queues from same family
qci[0].pQueuePriorities = priorities;

VkDeviceCreateInfo createInfo{};
createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
createInfo.queueCreateInfoCount = 1;
createInfo.pQueueCreateInfos = qci;
createInfo.enabledExtensionCount = static_cast<uint32_t>(allExts.size());
createInfo.ppEnabledExtensionNames = allExts.data();
```

After `vkGetDeviceQueue(device_, queueFamily_, 0, &queue_)`, add:

```cpp
vkGetDeviceQueue(device_, queueFamily_, 1, &computeQueue_);
computeQueueFamily_ = queueFamily_;  // same family, different queue index
```

- [ ] **Step 3: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/native/fsrvk/vulkan_device.h src/native/fsrvk/vulkan_device.cpp
git commit -m "Phase 3 Task 2: VulkanDevice dual queue support"
```

---

### Task 3: SyncManager — Exportable VkSemaphore

**Files:**
- Create: `src/native/fsrvk/sync_manager.h`
- Create: `src/native/fsrvk/sync_manager.cpp`

**Interfaces:**
- Produces: `SyncManager` class with `createExportableSemaphore(VkDevice) → VkSemaphore`, `exportSemaphoreToWin32Handle(VkDevice, VkSemaphore) → HANDLE`, `destroySemaphore(VkDevice, VkSemaphore)` — consumed by Task 4

- [ ] **Step 1: Create sync_manager.h**

Write `src/native/fsrvk/sync_manager.h`:

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <windows.h>

namespace fsr {

class SyncManager {
public:
    /// Create a VkSemaphore with VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT.
    /// Returns VK_NULL_HANDLE on failure.
    VkSemaphore createExportableSemaphore(VkDevice device);

    /// Export the Win32 HANDLE from a VkSemaphore.
    /// Returns nullptr on failure.
    HANDLE exportSemaphoreToWin32Handle(VkDevice device, VkSemaphore semaphore);

    /// Destroy a semaphore.
    void destroySemaphore(VkDevice device, VkSemaphore semaphore);
};

} // namespace fsr
```

- [ ] **Step 2: Create sync_manager.cpp**

Write `src/native/fsrvk/sync_manager.cpp`:

```cpp
#include "sync_manager.h"
#include <cassert>
#include <cstdio>

namespace fsr {

VkSemaphore SyncManager::createExportableSemaphore(VkDevice device) {
    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    VkSemaphoreCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    createInfo.pNext = &exportInfo;

    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkResult result = vkCreateSemaphore(device, &createInfo, nullptr, &semaphore);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[FSR4] SyncManager: vkCreateSemaphore failed: %d\n", result);
        return VK_NULL_HANDLE;
    }
    return semaphore;
}

HANDLE SyncManager::exportSemaphoreToWin32Handle(VkDevice device, VkSemaphore semaphore) {
    // Resolve function pointer — must be queried at runtime
    auto pfn = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(
        vkGetDeviceProcAddr(device, "vkGetSemaphoreWin32HandleKHR"));
    if (!pfn) {
        fprintf(stderr, "[FSR4] SyncManager: vkGetSemaphoreWin32HandleKHR not found\n");
        return nullptr;
    }

    VkSemaphoreGetWin32HandleInfoKHR getInfo{};
    getInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR;
    getInfo.semaphore = semaphore;
    getInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    HANDLE handle = nullptr;
    VkResult result = pfn(device, &getInfo, &handle);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[FSR4] SyncManager: vkGetSemaphoreWin32HandleKHR failed: %d\n", result);
        return nullptr;
    }
    return handle;
}

void SyncManager::destroySemaphore(VkDevice device, VkSemaphore semaphore) {
    if (semaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(device, semaphore, nullptr);
    }
}

} // namespace fsr
```

- [ ] **Step 3: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/native/fsrvk/sync_manager.h src/native/fsrvk/sync_manager.cpp
git commit -m "Phase 3 Task 3: SyncManager — exportable VkSemaphore + Win32 HANDLE"
```

---

### Task 4: Fsr4SharedResources — 5 Shared Texture Pool

**Files:**
- Create: `src/native/fsrvk/fsr4_shared_resources.h`
- Create: `src/native/fsrvk/fsr4_shared_resources.cpp`
- Modify: `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`

**Interfaces:**
- Consumes: `VulkanDevice` (Task 2) for VkDevice/VkPhysicalDevice
- Produces: `Fsr4SharedResources::create(VkDevice, VkExtent2D) → bool`, `exportHandles() → std::array<HANDLE, 5>`, `destroy(VkDevice)` — consumed by Task 5 (JNI)
- Produces: `SharedTextureManager.allocateFsr4Pool(w, h) → boolean`, `get*(TexId|AllocPtr)` getters — consumed by Task 6

- [ ] **Step 1: Create fsr4_shared_resources.h**

Write `src/native/fsrvk/fsr4_shared_resources.h`:

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <windows.h>
#include <array>

namespace fsr {

/// Manages a pool of 5 VK-shared textures for FSR4.
/// Color, Depth, MV, Reactive (optional), Output — all must be VK-accessible.
struct Fsr4SharedResources {
    std::array<VkImage, 5> images = {};
    std::array<VkDeviceMemory, 5> memories = {};
    std::array<VkImageView, 5> views = {};
    std::array<HANDLE, 5> win32Handles = {};
    VkExtent2D extent = {};

    // Texture index constants
    enum TexIdx : int {
        COLOR = 0,
        DEPTH = 1,
        MV = 2,
        REACTIVE = 3,
        OUTPUT = 4,
        COUNT = 5
    };

    /// Allocate all 5 textures at once. Returns false on any failure.
    bool create(VkDevice device, VkPhysicalDevice physDevice, VkExtent2D extent);

    /// Export all 5 Win32 handles for GL import.
    const std::array<HANDLE, 5>& exportHandles() const { return win32Handles; }

    /// Destroy all 5 textures (VkImage + VkDeviceMemory + VkImageView).
    void destroy(VkDevice device);

    /// One-time transition from UNDEFINED to GENERAL for all 5 images.
    bool transitionToGeneral(VkDevice device, VkQueue queue, VkCommandPool pool);
};

} // namespace fsr
```

- [ ] **Step 2: Create fsr4_shared_resources.cpp**

Write `src/native/fsrvk/fsr4_shared_resources.cpp`. For each of the 5 textures, allocate with:

```cpp
// Create VkImage with exportable memory
VkImageCreateInfo imgInfo{};
imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
imgInfo.imageType = VK_IMAGE_TYPE_2D;
imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;  // matches GL_RGBA8
imgInfo.extent = {extent.width, extent.height, 1};
imgInfo.mipLevels = 1;
imgInfo.arrayLayers = 1;
imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
              | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

// Exportable memory
VkExternalMemoryImageCreateInfo extInfo{};
extInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
extInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
imgInfo.pNext = &extInfo;

vkCreateImage(device, &imgInfo, nullptr, &images[i]);

// Allocate dedicated memory with export
VkMemoryDedicatedAllocateInfo dedicatedInfo{};
dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
dedicatedInfo.image = images[i];

VkExportMemoryAllocateInfo exportInfo{};
exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
dedicatedInfo.pNext = &exportInfo;

vkGetImageMemoryRequirements(device, images[i], &memReqs);
// ... find memory type with VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
vkAllocateMemory(device, &allocInfo, nullptr, &memories[i]);
vkBindImageMemory(device, images[i], memories[i], 0);

// Create VkImageView (needed by ffxGetResourceVulkan)
// ...

// Export Win32 handle (same pattern as Phase 1 vulkan_memory.cpp)
// ...
```

The `create()` method allocates all 5 images sequentially. If any allocation fails, call `destroy()` and return false.

The `transitionToGeneral()` method allocates a short-lived VkCommandBuffer, records VkImageMemoryBarrier for each image (srcAccessMask=0, dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT, oldLayout=UNDEFINED, newLayout=GENERAL), submits, and waits for idle.

- [ ] **Step 3: Extend SharedTextureManager.java with pool API**

Read `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`. Add:

```java
// FSR4 pool fields
private int[] fsr4TexIds;      // 5 texture IDs, or null if not allocated
private long[] fsr4AllocPtrs;  // 5 FsrSharedAllocation* pointers

/** Allocate 5 VK-shared textures for FSR4. Returns true on success. */
public boolean allocateFsr4Pool(int w, int h) {
    // Calls through JNI to Fsr4SharedResources.create(...)
    // Imports all 5 Win32 handles via glImportMemoryWin32HandleEXT
    // Creates GlTexture + GlTextureView for each
    // Stores IDs + allocPtrs in arrays
}

public int getColorTexId()     { return fsr4TexIds[0]; }
public int getDepthTexId()     { return fsr4TexIds[1]; }
public int getMvTexId()        { return fsr4TexIds[2]; }
public int getReactiveTexId()  { return fsr4TexIds[3]; }  // 0 if optional
public int getOutputTexId()    { return fsr4TexIds[4]; }
public long getColorAllocPtr()      { return fsr4AllocPtrs[0]; }
public long getDepthAllocPtr()      { return fsr4AllocPtrs[1]; }
public long getMvAllocPtr()         { return fsr4AllocPtrs[2]; }
public long getReactiveAllocPtr()   { return fsr4AllocPtrs[3]; }
public long getOutputAllocPtr()     { return fsr4AllocPtrs[4]; }

public void destroyFsr4Pool() {
    // glDeleteTextures for all 5
    // glDeleteMemoryObjectsEXT for all 5
    // VulkanInterop.releaseSharedHandle for all 5 allocPtrs
    // Null arrays
}
```

- [ ] **Step 4: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/native/fsrvk/fsr4_shared_resources.h src/native/fsrvk/fsr4_shared_resources.cpp src/client/java/com/fsr2mod/fsr/SharedTextureManager.java
git commit -m "Phase 3 Task 4: Fsr4SharedResources — 5 texture pool + SharedTextureManager extension"
```

---

### Task 5: Fsr4Context + Fsr4Native JNI Bridge

**Files:**
- Create: `src/native/fsrvk/fsr4_context.h`
- Create: `src/native/fsrvk/fsr4_context.cpp`
- Create: `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java`
- Modify: `src/native/fsrvk/jni_bridge.cpp`

**Interfaces:**
- Consumes: `VulkanDevice` (Task 2), `SyncManager` (Task 3), `Fsr4SharedResources` (Task 4)
- Produces: `Fsr4Native.initFsr4(renderW, renderH, displayW, displayH) → boolean`, `dispatchFsr4(jitterX, jitterY, cameraFar, cameraNear, cameraFovY, deltaTime, frameIndex, reset, colorPtr, depthPtr, mvPtr, reactivePtr, outputPtr) → boolean`, `waitIdle()`, `shutdownFsr4()`, `getReadySemaphoreHandle() → long`, `getCompleteSemaphoreHandle() → long` — consumed by Task 6

- [ ] **Step 1: Create fsr4_context.h**

Write `src/native/fsrvk/fsr4_context.h`:

```cpp
#pragma once
#include <ffx_fsr3.h>       // AMD FSR API SDK header (actual name from SDK)
#include <ffx_backend.h>
#include "vulkan_device.h"

namespace fsr {

class Fsr4Context {
public:
    bool initialize(VkExtent2D renderSize, VkExtent2D displaySize);
    bool dispatch(const FfxFsr3UpscaleRenderDesc& params);
    void waitIdle();
    void destroy();
    bool isInitialized() const { return isInitialized_; }

private:
    FfxContext ffxContext_{};
    bool isInitialized_ = false;
};

} // namespace fsr
```

- [ ] **Step 2: Create fsr4_context.cpp**

Write `src/native/fsrvk/fsr4_context.cpp`. Key implementation:

```cpp
#include "fsr4_context.h"
#include "sync_manager.h"
#include "fsr4_shared_resources.h"
#include <cstdio>

namespace fsr {

bool Fsr4Context::initialize(VkExtent2D renderSize, VkExtent2D displaySize) {
    VulkanDevice& dev = VulkanDevice::get();

    // Get Vulkan backend interface (function name varies by SDK version)
    // Check the actual SDK's ffx_vulkan.h for the exact function name.
    auto backendInterface = ffxGetInterfaceVulkanVk(
        dev.instance(),
        dev.physicalDevice(),
        dev.device(),
        vkGetDeviceProcAddr
    );

    // IMPORTANT: Do NOT override FfxBackendInterface::execute().
    // The SDK internally splits FSR4 dispatch into multiple execute calls.
    // Intercepting them would require per-submit semaphore wait/signal,
    // which deadlocks the queue.

    // Build context description
    // Actual struct name depends on SDK version — check ffx_fsr3.h
    FfxFsr3CreateContextDesc desc{};
    desc.flags = FFX_FSR3_UPSCALER_ENABLE_DEPTH_INVERTED;  // reversed-Z
    // Do NOT set HDR flag — Minecraft is LDR (GL_RGBA8).
    // Setting HDR with LDR input produces washed-out output.
    desc.maxRenderSize = {renderSize.width, renderSize.height, 1};
    desc.displaySize = {displaySize.width, displaySize.height, 1};
    desc.backendInterface = backendInterface;

    // FSR4 is selected via upscale type (FSR3 API dispatches FSR4)
    // Exact type enum value depends on SDK version.
    // Possible values: FFX_FSR3_UPSCALER_TYPE_FSR4, or similar.
    // Check ffx_fsr3.h for the actual constant.
    desc.upscaleType = FFX_FSR3_UPSCALER_TYPE_FSR4;

    // Create context — this loads neural net weights (~50-100ms)
    FfxReturnCode code = ffxFsr3CreateContext(&ffxContext_, &desc);
    if (code != FFX_OK) {
        fprintf(stderr, "[FSR4] ffxFsr3CreateContext failed: %d\n", code);
        return false;
    }

    isInitialized_ = true;
    return true;
}

bool Fsr4Context::dispatch(const FfxFsr3UpscaleRenderDesc& params) {
    if (!isInitialized_) return false;
    FfxReturnCode code = ffxFsr3UpscaleRenderSubmit(&ffxContext_, &params);
    return code == FFX_OK;
}

void Fsr4Context::waitIdle() {
    if (!isInitialized_) return;
    vkDeviceWaitIdle(VulkanDevice::get().device());
}

void Fsr4Context::destroy() {
    if (isInitialized_) {
        // Context destroy function name depends on SDK version
        // Check ffx_fsr3.h for the actual name.
        ffxFsr3ContextDestroy(&ffxContext_);
        isInitialized_ = false;
    }
}

} // namespace fsr
```

- [ ] **Step 3: Create Fsr4Native.java**

Write `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java`:

```java
package com.fsr2mod.vulkan;

import org.lwjgl.system.windows.Kernel32;

public final class Fsr4Native {
    private static boolean available = false;

    static {
        try {
            String tempDir = extractFromJar("/natives/amd_fsr4_api.dll");
            // SetDllDirectory so amd_fsr4_api.dll can find its internal deps.
            // Use LWJGL's Kernel32 — no JNA dependency needed.
            Kernel32.SetDllDirectory(tempDir);
            System.load(tempDir + "/amd_fsr4_api.dll");
            Kernel32.SetDllDirectory(null);
            available = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            available = false;
        }
    }

    public static boolean isAvailable() { return available; }

    public static native boolean initFsr4(int renderW, int renderH,
                                          int displayW, int displayH);

    public static native boolean dispatchFsr4(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovYRad,
        float deltaTime, int frameIndex, boolean reset,
        long colorInputPtr, long depthInputPtr, long mvInputPtr,
        long reactivePtr, long outputPtr);

    public static native void waitIdle();
    public static native void shutdownFsr4();

    public static native long getReadySemaphoreHandle();
    public static native long getCompleteSemaphoreHandle();

    private static String extractFromJar(String path) {
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

- [ ] **Step 4: Add JNI entry points to jni_bridge.cpp**

Read `src/native/fsrvk/jni_bridge.cpp`. Add before the closing `} // extern "C"`:

The JNI bridge creates a global `Fsr4Context` instance stored as a `jlong`. The `dispatchFsr4` method:

1. Builds `FfxFsr3UpscaleRenderDesc` from the JNI parameters
2. Creates `FfxResource` handles from `FsrSharedAllocation*` for each texture via `ffxGetResourceVulkan()`:
   - Input textures (color, depth, MV, reactive): state = `FFX_RESOURCE_STATE_COMPUTE_READ`
   - Output texture: state = `FFX_RESOURCE_STATE_UNORDERED_ACCESS`
3. Submits a no-op command buffer waiting on the render-done semaphore (`vkQueueSubmit` with `pWaitSemaphores` = renderDoneSem, signaled by GL)
4. Calls `ffxFsr3UpscaleRenderSubmit()` — the SDK handles its own queue submissions internally
5. Calls `vkQueueWaitIdle()` — required because the SDK doesn't signal an external completion semaphore

The `dispatchFsr4` JNI method signature:

```cpp
JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_dispatchFsr4(
    JNIEnv* env, jclass /*clazz*/,
    jfloat jitterX, jfloat jitterY,
    jfloat cameraFar, jfloat cameraNear, jfloat cameraFovY,
    jfloat deltaTime, jint frameIndex, jboolean reset,
    jlong colorPtr, jlong depthPtr, jlong mvPtr,
    jlong reactivePtr, jlong outputPtr)
```

- [ ] **Step 5: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/native/fsrvk/fsr4_context.h src/native/fsrvk/fsr4_context.cpp src/client/java/com/fsr2mod/vulkan/Fsr4Native.java src/native/fsrvk/jni_bridge.cpp
git commit -m "Phase 3 Task 5: Fsr4Context + Fsr4Native JNI bridge"
```

---

### Task 6: GLSemaphoreManager

**Files:**
- Create: `src/client/java/com/fsr2mod/vulkan/GLSemaphoreManager.java`

**Interfaces:**
- Produces: `GLSemaphoreManager.initialize(win32HandleReady, win32HandleComplete) → boolean`, `signalRenderDone(sharedTexId)`, `waitForComputeDone(sharedTexId)`, `fenceRenderDone() → long`, `clientWait(fence, timeoutNanos) → int`, `isUsingHardwareSync() → boolean`, `destroy()` — consumed by Task 7

- [ ] **Step 1: Create GLSemaphoreManager.java**

Write `src/client/java/com/fsr2mod/vulkan/GLSemaphoreManager.java`:

```java
package com.fsr2mod.vulkan;

import org.lwjgl.opengl.*;

public class GLSemaphoreManager {
    private int readySemId = -1;     // GL signals → VK waits (render done)
    private int completeSemId = -1;  // VK signals → GL waits (compute done)
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

    /** GL signals scene render complete + flushes.
     *  CRITICAL: glFlush() is required — without it the signal stays in GL's
     *  command queue and VK's semaphore wait deadlocks. */
    public void signalRenderDone(int sharedTexId) {
        EXTSemaphore.glSignalSemaphoreEXT(readySemId,
            new int[]{sharedTexId}, new int[]{GL43C.GL_LAYOUT_GENERAL_EXT});
        GL43C.glFlush();
    }

    /** GL waits for VK compute to finish before HUD rendering. */
    public void waitForComputeDone(int sharedTexId) {
        EXTSemaphore.glWaitSemaphoreEXT(completeSemId,
            new int[]{sharedTexId}, new int[]{GL43C.GL_LAYOUT_GENERAL_EXT});
    }

    /** Path B: Create GL fence. Returns fence object. */
    public long fenceRenderDone() {
        return GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Path B: Client-side wait on fence. Returns GL status code. */
    public int clientWait(long glFence, long timeoutNanos) {
        return GL43C.glClientWaitSync(glFence, 0, timeoutNanos);
    }

    public boolean isUsingHardwareSync() { return useHardwareSync; }

    public void destroy() {
        if (readySemId != -1)    EXTSemaphore.glDeleteSemaphoresEXT(readySemId);
        if (completeSemId != -1) EXTSemaphore.glDeleteSemaphoresEXT(completeSemId);
        readySemId = -1;
        completeSemId = -1;
        useHardwareSync = false;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/client/java/com/fsr2mod/vulkan/GLSemaphoreManager.java
git commit -m "Phase 3 Task 6: GLSemaphoreManager — GL semaphore lifecycle + fence fallback"
```

---

### Task 7: FSRProcessor Integration — Dual-Path Orchestration

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Interfaces:**
- Consumes: `Fsr4Native` (Task 5), `GLSemaphoreManager` (Task 6), `SharedTextureManager.allocateFsr4Pool()` (Task 4)
- Produces: Working FSR4 dispatch with Path A (semaphores) and Path B (fence fallback)

- [ ] **Step 1: Read current FSRProcessor.java**

Read the full file. Understand the existing fields, `onRenderBegin()`, `onSceneRendered()`, `onResize()`, `destroy()` methods, and the MV gen dispatch location.

- [ ] **Step 2: Add FSR4 fields**

Add after the existing `sharedMemoryActive` field:

```java
private Fsr4Native fsr4Native;          // JNI bridge (static methods, or null)
private GLSemaphoreManager gpuSyncMgr;  // null if unavailable
private boolean fsr4Available;
private boolean fsr4Initialized;
private int fsr4ContextRenderW, fsr4ContextRenderH;
private int fsr4ContextDisplayW, fsr4ContextDisplayH;
private long fsr4Fence;                  // Path B fence (0 = none)
private long lastFsr4ContextChange;      // debounce timestamp
private static final long FSR4_DEBOUNCE_MS = 500;
private int fsr4FramesSkipped;           // consecutive timeout count
```

- [ ] **Step 3: Add FSR4 init/resize logic in onRenderBegin**

In `onRenderBegin()`, after the existing `if (checkVulkanAvailable()) { ... }` shared memory block, add:

```java
// Initialize FSR4 on first frame or after resize
if (Fsr4Native.isAvailable()) {
    fsr4Available = true;
    int renderW = scaledWidth;
    int renderH = scaledHeight;
    int dispW = displayWidth;
    int dispH = displayHeight;

    // Debounce: don't recreate FSR4 context more than once per 500ms
    long now = System.currentTimeMillis();
    boolean needsRecreate = !fsr4Initialized
        || fsr4ContextRenderW != renderW || fsr4ContextRenderH != renderH
        || fsr4ContextDisplayW != dispW || fsr4ContextDisplayH != dispH;

    if (needsRecreate) {
        if (now - lastFsr4ContextChange < FSR4_DEBOUNCE_MS) {
            // Too soon — skip this frame's recreation, use existing context
        } else {
            lastFsr4ContextChange = now;

            // Destroy old context
            if (fsr4Initialized) {
                Fsr4Native.shutdownFsr4();
                fsr4Initialized = false;
            }
            if (gpuSyncMgr != null) {
                gpuSyncMgr.destroy();
                gpuSyncMgr = null;
            }

            // Allocate shared texture pool
            if (sharedTexMgr != null) {
                try {
                    sharedTexMgr.allocateFsr4Pool(dispW, dispH);

                    // Init FSR4 context
                    boolean ok = Fsr4Native.initFsr4(renderW, renderH, dispW, dispH);
                    if (ok) {
                        fsr4Initialized = true;
                        fsr4ContextRenderW = renderW;
                        fsr4ContextRenderH = renderH;
                        fsr4ContextDisplayW = dispW;
                        fsr4ContextDisplayH = dispH;

                        // Try hardware semaphores
                        long readyHandle = Fsr4Native.getReadySemaphoreHandle();
                        long completeHandle = Fsr4Native.getCompleteSemaphoreHandle();
                        gpuSyncMgr = new GLSemaphoreManager();
                        boolean semOk = gpuSyncMgr.initialize(readyHandle, completeHandle);
                        FSRMod.LOGGER.info("FSR4: init OK, semaphores={}", semOk);
                    }
                } catch (Exception e) {
                    FSRMod.LOGGER.warn("FSR4 init failed: {}", e.getMessage());
                    if (fsr4Initialized) { Fsr4Native.shutdownFsr4(); fsr4Initialized = false; }
                    if (gpuSyncMgr != null) { gpuSyncMgr.destroy(); gpuSyncMgr = null; }
                }
            }
        }
    }
} else {
    fsr4Available = false;
}
```

- [ ] **Step 4: Add FSR4 dispatch path in onSceneRendered**

In `onSceneRendered()`, after the scene rendering completes and before HUD rendering, add the FSR4 dispatch path. This goes before the existing FSR2/3 dispatch block:

```java
if (fsr4Available && fsr4Initialized) {
    // Frame time budgeting: skip FSR4 if scene render already took too long
    if (currentFrameTimeMs > 10.0f) {
        FSRMod.LOGGER.debug("FSR4 skipped: scene time {} > 10ms", currentFrameTimeMs);
        renderHUD();  // scaled scene displayed directly
        fgFlipState = !fgFlipState;
        return;
    }

    // Frame time budgeting: if consistently skipping, fallback to FSR2/3
    if (fsr4FramesSkipped > 10) {
        FSRMod.LOGGER.warn("FSR4: 10+ consecutive timeouts, falling back to FSR2/3");
        fsr4Initialized = false;
        dispatchFsr2Or3(...);
        return;
    }

    if (gpuSyncMgr != null && gpuSyncMgr.isUsingHardwareSync()) {
        // Path A: Hardware semaphores
        int sharedTex = sharedTexMgr.getOutputTexId();
        gpuSyncMgr.signalRenderDone(sharedTex);

        Fsr4Native.dispatchFsr4(
            jitterX, jitterY,
            cameraFar, cameraNear, cameraFovY,
            deltaTime, frameIndex, reset,
            sharedTexMgr.getColorAllocPtr(),
            sharedTexMgr.getDepthAllocPtr(),
            sharedTexMgr.getMvAllocPtr(),
            sharedTexMgr.getReactiveAllocPtr(),
            sharedTexMgr.getOutputAllocPtr()
        );

        Fsr4Native.waitIdle();
        gpuSyncMgr.waitForComputeDone(sharedTex);
    } else {
        // Path B: Fence fallback
        long fence = gpuSyncMgr != null
            ? gpuSyncMgr.fenceRenderDone()
            : GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL43C.glFlush();

        int status = GL43C.glClientWaitSync(fence, 0, 16_666_667L);
        if (status == GL43C.GL_CONDITION_SATISFIED
            || status == GL43C.GL_ALREADY_SIGNALED) {
            fsr4FramesSkipped = 0;

            Fsr4Native.dispatchFsr4(...);
            Fsr4Native.waitIdle();
        } else {
            fsr4FramesSkipped++;
            // Skip this frame — scaled scene displayed directly (aliased)
        }
    }

    // Copy output to mainRT for GUI rendering
    copyFsr4OutputToMainRT();

} else {
    // Fallback: existing FSR2/3 GL compute pipeline (unchanged)
    dispatchFsr2Or3(...);
}
```

- [ ] **Step 5: Add FSR4 cleanup in onResize**

In `onResize()`, after the existing cleanup:

```java
if (fsr4Initialized) {
    Fsr4Native.shutdownFsr4();
    fsr4Initialized = false;
}
if (gpuSyncMgr != null) {
    gpuSyncMgr.destroy();
    gpuSyncMgr = null;
}
```

- [ ] **Step 6: Add FSR4 cleanup in destroy()**

In `destroy()`, after the existing cleanup:

```java
if (fsr4Initialized) {
    Fsr4Native.shutdownFsr4();
    fsr4Initialized = false;
}
if (gpuSyncMgr != null) {
    gpuSyncMgr.destroy();
    gpuSyncMgr = null;
}
fsr4Available = false;
```

- [ ] **Step 7: Handle early-return guards**

In all `onRenderBegin`/`onSceneRendered` early-return paths, ensure `fsr4Fence == 0` and no dangling fence objects.

- [ ] **Step 8: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "Phase 3 Task 7: FSRProcessor FSR4 dispatch — dual-path orchestration"
```

---

### Task 8: Wire MV Generation to Shared Texture

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Note:** The MV generation shader already exists and runs on GL side. This task redirects its output from a GL-native texture to the VK-shared MV texture from `Fsr4SharedResources`.

- [ ] **Step 1: Find the MV dispatch location**

Read `FSRProcessor.java`. Search for where the motion vector compute shader is dispatched (likely in `onRenderBegin()` or a separate method, dispatching a GL compute shader and writing to a bound texture via `glBindImageTexture`).

- [ ] **Step 2: Switch MV output to shared texture when FSR4 active**

Replace the MV output binding with:

```java
if (fsr4Available && fsr4Initialized) {
    // MV writes to VK-shared texture so FSR4 can read it
    GL43C.glBindImageTexture(MV_UNIT, sharedTexMgr.getMvTexId(), 0,
        false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA16F);
} else {
    // Existing MV output texture
    GL43C.glBindImageTexture(MV_UNIT, existingMvTex, ...);
}
```

The MV shader itself needs no changes — it writes to whatever texture ID is bound. The shared texture is imported as a GL texture with the same `glTexStorage2D` format, so the shader's `imageStore` works identically.

- [ ] **Step 3: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "Phase 3 Task 8: Route MV generation to shared texture for FSR4"
```

---

### Task 9: Final Build + Integration Test

**Files:**
- All modified files

- [ ] **Step 1: Clean build**

Run: `.\gradlew.bat clean build`
Expected: BUILD SUCCESSFUL
Check that:
- `build/libs/fsr-reloaded-*.jar` contains `amd_fsr4_api.dll` in `/natives/`
- `build/libs/fsr-reloaded-*.jar` contains `AMD_FSR_API_LICENSE.txt` in `/natives/`
- `build/libs/fsr-reloaded-*.jar` contains `fsrvk.dll` in `/natives/`

- [ ] **Step 2: Verify FSR4 not breaking existing paths**

Run: `.\gradlew.bat runClient`
Expected: Client launches. FSR1/FSR2/FSR3 modes work as before. No crashes during world loading.

- [ ] **Step 3: Quick-check FSR4 init (if GPU supports it)**

When `amd_fsr4_api.dll` is present and GPU is RDNA 3.5+, FSR4 mode should:
- Log "FSR4: init OK, semaphores=true" or "FSR4: init OK, semaphores=false"
- Fall back to FSR2/3 on unsupported hardware without crash

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Phase 3 Task 9: Final build verification + integration"
```
