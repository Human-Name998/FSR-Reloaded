# FSR4 Phase 1 — Native JNI C++ Layer (fsrvk.dll)

## Goal

Move the VK allocation + handle export chain from Phase 0's pure-Java/LWJGL proof into a native C++ DLL (`fsrvk.dll`), exposed to the Minecraft mod via a minimal JNI API. Java retains GL-side import (`glImportMemoryWin32HandleEXT` + `glTextureStorageMem2DEXT`) and the `glFinish()` fallback for sync.

## Architecture

```
Minecraft mod (Java, LWJGL OpenGL)
  └─ VulkanInterop.java (com.fsr2mod.vulkan)
        │
        │  JNI calls
        ▼
  fsrvk.dll (C++, compiled via CMake)
  ├── vulkan_device.cpp     — VkInstance, VkPhysicalDevice, VkDevice (singleton)
  ├── vulkan_memory.cpp     — VkImage + VkDeviceMemory + export chain
  └── jni_bridge.cpp        — JNI entry points
        │
        │  returns Win32 HANDLE as jlong
        ▼
  Java: glImportMemoryWin32HandleEXT → glTextureStorageMem2DEXT
        ▼
  OpenGL texture backed by VK-shared device memory
```

## JNI API

```java
package com.fsr2mod.vulkan;

public final class VulkanInterop {

    /**
     * Allocates a VkImage + exportable VkDeviceMemory, exports the Win32 HANDLE.
     *
     * Caller MUST:
     *   1. glDeleteTextures() any GL texture previously bound to the old handle
     *   2. releaseSharedHandle() the old handle BEFORE calling getSharedHandle()
     *      with new dimensions (resize safety — prevents driver exceptions
     *      from concurrent GL/VK access to the same backing memory).
     *
     * @param texW width in pixels
     * @param texH height in pixels
     * @return Win32 HANDLE as jlong (0 on failure)
     */
    public static native long getSharedHandle(int texW, int texH);

    /**
     * Destroys the VkImage, frees VkDeviceMemory, and closes the Win32 HANDLE.
     * The HANDLE is closed via CloseHandle() after vkFreeMemory — both the
     * Vulkan object AND the OS kernel reference are released.
     */
    public static native void releaseSharedHandle(long handle);

    /**
     * Returns GPU name + driver version string for debug logging.
     * AMD driver versions are decoded from the vendor's bitfield format
     * (major<<22 | minor<<12 | patch) rather than standard Vulkan macros.
     */
    public static native String getDeviceInfo();

    /**
     * Explicitly destroys the VkDevice + VkInstance.
     * MUST be called from Minecraft's client shutdown hook (not left to
     * DLL static destructors — the JVM may tear down threads before
     * static destructors run, causing EXCEPTION_ACCESS_VIOLATION).
     */
    public static native void terminate();
}
```

### JNI C++ signatures

```cpp
extern "C" {
    JNIEXPORT jlong   JNICALL Java_com_fsr2mod_vulkan_VulkanInterop_getSharedHandle(JNIEnv*, jclass, jint w, jint h);
    JNIEXPORT void    JNICALL Java_com_fsr2mod_vulkan_VulkanInterop_releaseSharedHandle(JNIEnv*, jclass, jlong handle);
    JNIEXPORT jstring JNICALL Java_com_fsr2mod_vulkan_VulkanInterop_getDeviceInfo(JNIEnv*, jclass);
    JNIEXPORT void    JNICALL Java_com_fsr2mod_vulkan_VulkanInterop_terminate(JNIEnv*, jclass);
}
```

## Allocation flags (proven in Phase 0)

| Parameter | Value |
|-----------|-------|
| `VkExternalMemoryImageCreateInfo.handleTypes` | `0x00000002` |
| `VkExportMemoryAllocateInfo.handleTypes` | `0x00000002` |
| `vkGetMemoryWin32HandleKHR.handleType` | `0x00000002` |
| Image format | `VK_FORMAT_R8G8B8A8_UNORM` |
| Image usage | `TRANSFER_DST_BIT \| TRANSFER_SRC_BIT \| STORAGE_BIT \| SAMPLED_BIT` |
| Memory type preference | Device-local; fallback to first compatible |
| Memory property | `VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT` |

## C++ internals

### vulkan_device.cpp — singleton device manager

- Creates VkInstance with `VK_KHR_external_memory_capabilities`
- Enumerates physical devices, selects discrete GPU with `VK_KHR_external_memory_win32`
- Creates VkDevice with a dedicated compute queue + `VK_KHR_external_memory_win32` extension
- Device handle: static variable (Minecraft render thread is single-threaded; no mutex needed)
- Cached GPU name string + driver version for `getDeviceInfo()`

**AMD driver version decoding** (`vulkan_device.cpp`):

```cpp
std::string decodeDriverVersion(uint32_t vendorId, uint32_t driverVersion) {
    if (vendorId == 0x1002) { // AMD
        uint32_t major = driverVersion >> 22;
        uint32_t minor = (driverVersion >> 12) & 0x3FF;
        uint32_t patch = driverVersion & 0xFFF;
        return std::to_string(major) + "." + std::to_string(minor) + "." + std::to_string(patch);
    }
    // Standard Vulkan format (NVIDIA, Intel, etc.)
    uint32_t major = VK_API_VERSION_MAJOR(driverVersion);
    uint32_t minor = VK_API_VERSION_MINOR(driverVersion);
    uint32_t patch = VK_API_VERSION_PATCH(driverVersion);
    return std::to_string(major) + "." + std::to_string(minor) + "." + std::to_string(patch);
}
```

### vulkan_memory.cpp — per-allocation

Resource tracking uses a heap-allocated struct — NOT a map keyed on raw HANDLE (Windows recycles handle integers across allocations, causing lookup collisions during rapid resize).

```cpp
struct FsrSharedAllocation {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    HANDLE win32Handle = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
};
```

```
getSharedHandle(w, h):
  1. auto* alloc = new FsrSharedAllocation()
  2. vkCreateImage with VkExternalMemoryImageCreateInfo pNext
  3. vkGetImageMemoryRequirements → find compatible memory type
  4. vkAllocateMemory with VkExportMemoryAllocateInfo pNext
  5. vkBindImageMemory
  6. vkGetMemoryWin32HandleKHR → alloc->win32Handle
  7. alloc->width = w, alloc->height = h
  8. Return reinterpret_cast<jlong>(alloc)

releaseSharedHandle(handle):
  1. auto* alloc = reinterpret_cast<FsrSharedAllocation*>(handle)
  2. vkDestroyImage(device, alloc->image, nullptr)
  3. vkFreeMemory(device, alloc->memory, nullptr)
  4. CloseHandle(alloc->win32Handle)   // ← prevents Win32 kernel object leak
  5. delete alloc
```

**Why CloseHandle is required**: `vkGetMemoryWin32HandleKHR` creates an additional OS reference to the memory. `vkFreeMemory` only releases the Vulkan-side reference. The Win32 HANDLE itself is a separate kernel object reference that must be closed via `CloseHandle()`. Without it, each resize leaks a kernel handle — over a long play session this exhausts the process handle table.

### jni_bridge.cpp — JNI glue

- `Java_com_fsr2mod_vulkan_VulkanInterop_getSharedHandle`: ensures device initialized, delegates to `vulkan_memory.cpp`, returns `reinterpret_cast<jlong>(alloc)` (pointer to heap-allocated `FsrSharedAllocation`)
- `Java_com_fsr2mod_vulkan_VulkanInterop_releaseSharedHandle`: cast jlong to `FsrSharedAllocation*`, destroy VkImage + vkFreeMemory + CloseHandle + delete alloc
- `Java_com_fsr2mod_vulkan_VulkanInterop_getDeviceInfo`: returns cached string from `vulkan_device.cpp`
- `Java_com_fsr2mod_vulkan_VulkanInterop_terminate`: destroy VkDevice + VkInstance synchronously (called from Minecraft shutdown hook)

## Resource lifecycle

```
Minecraft Client init:
  └─ VulkanInterop.getDeviceInfo()       // log GPU info

Each resize / frame requiring new shared buffer:
  └─ Java: glDeleteTextures(glTexture)   // unbind old GL texture
  └─ Java: releaseSharedHandle(oldHandle) // destroys VK resources + closes HANDLE
  └─ Java: newHandle = getSharedHandle(w, h)
  └─ Java: glImportMemoryWin32HandleEXT + glTextureStorageMem2DEXT
  └─ ... render loop ...
  └─ glFinish()  // sync before GL-side access

Minecraft Client shutdown:
  └─ Java: glDeleteTextures(glTexture)
  └─ Java: releaseSharedHandle(handle)
  └─ Java: VulkanInterop.terminate()    // explicit teardown while render thread is alive
                                          // NOT left to DLL static destructors — the JVM
                                          // may tear down threads before static destructors
                                          // complete, causing EXCEPTION_ACCESS_VIOLATION
```

## Thread safety

- Single-threaded (Minecraft render thread only)
- Static device cache in `vulkan_device.cpp`
- Per-allocation `FsrSharedAllocation` is heap-allocated, referenced by pointer-as-jlong — no shared mutable state between allocations
- No mutex required

## File layout

```
src/native/fsrvk/
├── CMakeLists.txt
├── vulkan_device.h
├── vulkan_device.cpp
├── vulkan_memory.h
├── vulkan_memory.cpp
├── jni_bridge.cpp
└── README.md

src/client/java/com/fsr2mod/vulkan/
└── VulkanInterop.java
```

## Build integration

- CMake project is separate from Gradle (Option A)
- Gradle task `buildNative` invokes:
  ```
  cd src/native/fsrvk && cmake -B build && cmake --build build --config Release
  ```
- DLL output (`build/Release/fsrvk.dll`) copied into mod JAR via a `processResources` or custom `jar` task
- Java loads DLL at runtime:
  ```java
  static {
      try {
          System.load(extractFromJar("/natives/fsrvk.dll"));
      } catch (UnsatisfiedLinkError e) {
          FSRMod.LOGGER.warn("fsrvk.dll not available — FSR4 VK interop disabled", e);
      }
  }
  ```

## Platform scope

**Phase 1: Windows only.** Linux support with FD-based handles deferred. The CMake project targets MSVC (Windows SDK). All platform-specific code is guarded by `#ifdef _WIN32`.

## Risk

None — the allocation flags, export path, and handle types are all proven in Phase 0. This phase is pure implementation effort.
