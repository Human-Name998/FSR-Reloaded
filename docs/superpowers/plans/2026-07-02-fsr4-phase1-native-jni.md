# FSR4 Phase 1 — Native JNI C++ Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move VK allocation + handle export into native `fsrvk.dll` with a minimal JNI API for the Minecraft mod's Java side.

**Architecture:** CMake-based C++ project produces `fsrvk.dll`. A Gradle `buildNative` task invokes CMake and copies the DLL into the mod JAR. Java calls JNI methods (`getSharedHandle`, `releaseSharedHandle`, `getDeviceInfo`, `terminate`) to manage shared VK→GL textures. Heap-allocated `FsrSharedAllocation` structs (pointer-as-jlong) eliminate HANDLE-map collision bugs.

**Tech Stack:** C++20, CMake 3.20+, Vulkan 1.1, JNI, Java 25, Fabric Loom 1.16

## Global Constraints

- Windows only (`#ifdef _WIN32` guards on all platform-specific code)
- VK handle type: `0x00000002` (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT`)
- Java package: `com.fsr2mod.vulkan`
- C++ namespace: `fsr` (avoid global namespace pollution)
- JNI function naming: `Java_com_fsr2mod_vulkan_VulkanInterop_<method>`
- Resource tracking: `FsrSharedAllocation*` pointer-as-jlong, NOT HANDLE-keyed map
- Explicit `terminate()` from Minecraft shutdown hook — NO static destructors

---

### Task 1: CMake Project Skeleton + Vulkan Headers

**Files:**
- Create: `src/native/fsrvk/CMakeLists.txt`
- Create: `src/native/fsrvk/vulkan_device.h`
- Create: `src/native/fsrvk/vulkan_memory.h`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: CMake target `fsrvk` (SHARED), header declarations consumed by Tasks 2-4

- [ ] **Step 1: Create CMakeLists.txt**

```cmake
cmake_minimum_required(VERSION 3.20)
project(fsrvk VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(Vulkan REQUIRED)

add_library(fsrvk SHARED
    vulkan_device.cpp
    vulkan_memory.cpp
    jni_bridge.cpp
)

target_include_directories(fsrvk PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(fsrvk PRIVATE Vulkan::Vulkan)

# Export symbols for JNI
if(WIN32)
    set_target_properties(fsrvk PROPERTIES
        WINDOWS_EXPORT_ALL_SYMBOLS ON
    )
endif()
```

- [ ] **Step 2: Write vulkan_device.h**

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <string>

namespace fsr {

/// Singleton Vulkan device manager.
class VulkanDevice {
public:
    static VulkanDevice& get();

    VkInstance instance() const { return instance_; }
    VkPhysicalDevice physicalDevice() const { return physDevice_; }
    VkDevice device() const { return device_; }
    uint32_t queueFamilyIndex() const { return queueFamily_; }
    VkQueue queue() const { return queue_; }

    const std::string& deviceName() const { return deviceName_; }
    const std::string& driverVersion() const { return driverVersion_; }

    /// Verify instance/device are created. Throws std::runtime_error on failure.
    void ensureInitialized();

    /// Explicit teardown — call from JNI terminate().
    void destroy();

private:
    VulkanDevice() = default;
    ~VulkanDevice() = default;
    VulkanDevice(const VulkanDevice&) = delete;
    VulkanDevice& operator=(const VulkanDevice&) = delete;

    void createInstance();
    void pickPhysicalDevice();
    void createDevice();
    std::string decodeDriverVersion(uint32_t vendorId, uint32_t driverVersion) const;
    bool deviceSupportsExt(VkPhysicalDevice pd, const char* name) const;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = UINT32_MAX;
    VkQueue queue_ = VK_NULL_HANDLE;
    std::string deviceName_;
    std::string driverVersion_;
    bool initialized_ = false;
};

} // namespace fsr
```

- [ ] **Step 3: Write vulkan_memory.h**

```cpp
#pragma once
#include <vulkan/vulkan.h>
#include <cstdint>

namespace fsr {

/// Per-allocation tracker — heap allocated, pointer returned to Java as jlong.
/// Do NOT use the raw HANDLE as a map key (Windows recycles handle integers).
struct FsrSharedAllocation {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* win32Handle = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
};

/// Allocate VkImage + exportable VkDeviceMemory, export Win32 HANDLE.
/// Returns heap-allocated FsrSharedAllocation (caller owns, must delete via releaseSharedAllocation).
FsrSharedAllocation* allocateSharedAllocation(uint32_t width, uint32_t height);

/// Destroy VkImage + vkFreeMemory + CloseHandle + delete alloc.
void releaseSharedAllocation(FsrSharedAllocation* alloc);

} // namespace fsr
```

---

### Task 2: vulkan_device.cpp — Singleton Device Manager

**Files:**
- Create: `src/native/fsrvk/vulkan_device.cpp`

**Interfaces:**
- Consumes: `vulkan_device.h` (Task 1)
- Produces: `VulkanDevice::get()`, `ensureInitialized()`, `destroy()` — consumed by Task 4 (jni_bridge.cpp)

- [ ] **Step 1: Write vulkan_device.cpp**

```cpp
#include "vulkan_device.h"
#include <algorithm>
#include <cassert>
#include <cstring>
#include <stdexcept>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace fsr {

namespace {

constexpr const char* INSTANCE_EXTENSIONS[] = {
    "VK_KHR_external_memory_capabilities",
    "VK_KHR_external_semaphore_capabilities",
};

constexpr const char* DEVICE_EXTENSIONS[] = {
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
    "VK_KHR_external_memory_win32",
    "VK_KHR_external_semaphore_win32",
};

VKAPI_ATTR VkBool32 VKAPI_CALL debugCallback(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT /*type*/,
    const VkDebugUtilsMessengerCallbackDataEXT* data,
    void* /*userData*/)
{
    if (severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
        fprintf(stderr, "[VK] %s\n", data->pMessage);
    }
    return VK_FALSE;
}

} // anonymous namespace

// ----------------------------------------------------------------
VulkanDevice& VulkanDevice::get() {
    static VulkanDevice instance;
    return instance;
}

void VulkanDevice::ensureInitialized() {
    if (initialized_) return;
    createInstance();
    pickPhysicalDevice();
    createDevice();
    initialized_ = true;
}

void VulkanDevice::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    initialized_ = false;
}

// ----------------------------------------------------------------
void VulkanDevice::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "FSR4 Phase 1";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.ppEnabledExtensionNames = INSTANCE_EXTENSIONS;

    VkDebugUtilsMessengerCreateInfoEXT debugInfo{};
    debugInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
    debugInfo.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                              | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    debugInfo.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                          | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                          | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debugInfo.pfnUserCallback = debugCallback;

    // Check if VK_EXT_debug_utils is available; attach to instance if so
    uint32_t extCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> availableExts(extCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, availableExts.data());

    bool hasDebugUtils = false;
    for (const auto& ext : availableExts) {
        if (std::strcmp(ext.extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0) {
            hasDebugUtils = true;
            break;
        }
    }

    VkDebugUtilsMessengerCreateInfoEXT* pDebugInfo = nullptr;
    if (hasDebugUtils) {
        createInfo.enabledExtensionCount = 3;
        const char* extList[] = {
            "VK_KHR_external_memory_capabilities",
            "VK_KHR_external_semaphore_capabilities",
            VK_EXT_DEBUG_UTILS_EXTENSION_NAME,
        };
        createInfo.ppEnabledExtensionNames = extList;
        pDebugInfo = &debugInfo;
    }

    createInfo.pNext = pDebugInfo;

    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        throw std::runtime_error("vkCreateInstance failed: " + std::to_string(result));
    }

    if (pDebugInfo) {
        auto vkCreateDebugUtilsMessengerEXT =
            reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance_, "vkCreateDebugUtilsMessengerEXT"));
        if (vkCreateDebugUtilsMessengerEXT) {
            VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
            vkCreateDebugUtilsMessengerEXT(instance_, &debugInfo, nullptr, &messenger);
            // We don't store the messenger — it's fire-and-forget for debug logging
            (void)messenger;
        }
    }
}

// ----------------------------------------------------------------
void VulkanDevice::pickPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) throw std::runtime_error("No Vulkan devices found");

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    for (const auto& pd : devices) {
        if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)) continue;
        if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME)) continue;
        if (!deviceSupportsExt(pd, "VK_KHR_external_memory_win32")) continue;
        if (!deviceSupportsExt(pd, "VK_KHR_external_semaphore_win32")) continue;

        // Find a compute queue family
        uint32_t qfCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qfCount);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qProps.data());

        for (uint32_t q = 0; q < qfCount; q++) {
            if (qProps[q].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                physDevice_ = pd;
                queueFamily_ = q;
                break;
            }
        }
        if (physDevice_ != VK_NULL_HANDLE) break;
    }

    if (physDevice_ == VK_NULL_HANDLE) {
        throw std::runtime_error("No Vulkan device with required extensions + compute queue");
    }

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physDevice_, &props);
    deviceName_ = props.deviceName;
    driverVersion_ = decodeDriverVersion(props.vendorID, props.driverVersion);
}

// ----------------------------------------------------------------
void VulkanDevice::createDevice() {
    const float priority = 1.0f;

    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = queueFamily_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;

    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &qci;
    createInfo.enabledExtensionCount = 4;
    createInfo.ppEnabledExtensionNames = DEVICE_EXTENSIONS;

    VkResult result = vkCreateDevice(physDevice_, &createInfo, nullptr, &device_);
    if (result != VK_SUCCESS) {
        throw std::runtime_error("vkCreateDevice failed: " + std::to_string(result));
    }

    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
}

// ----------------------------------------------------------------
std::string VulkanDevice::decodeDriverVersion(uint32_t vendorId, uint32_t driverVersion) const {
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

// ----------------------------------------------------------------
bool VulkanDevice::deviceSupportsExt(VkPhysicalDevice pd, const char* name) const {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> props(count);
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &count, props.data());
    for (const auto& p : props) {
        if (std::strcmp(p.extensionName, name) == 0) return true;
    }
    return false;
}

} // namespace fsr
```

---

### Task 3: vulkan_memory.cpp — Allocation + Export

**Files:**
- Create: `src/native/fsrvk/vulkan_memory.cpp`

**Interfaces:**
- Consumes: `vulkan_device.h`, `vulkan_memory.h` (Task 1), `VulkanDevice::get().ensureInitialized()`, `VulkanDevice::get().device()`, etc.
- Produces: `allocateSharedAllocation()`, `releaseSharedAllocation()` — consumed by Task 4

- [ ] **Step 1: Write vulkan_memory.cpp**

```cpp
#include "vulkan_memory.h"
#include "vulkan_device.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace fsr {

namespace {

int findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags requiredFlags) {
    auto& dev = VulkanDevice::get();
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(dev.physicalDevice(), &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (props.memoryTypes[i].propertyFlags & requiredFlags) == requiredFlags) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

} // anonymous namespace

// ----------------------------------------------------------------
FsrSharedAllocation* allocateSharedAllocation(uint32_t width, uint32_t height) {
    auto& dev = VulkanDevice::get();
    dev.ensureInitialized();

    auto* alloc = new FsrSharedAllocation();
    alloc->width = width;
    alloc->height = height;

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT
                  | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                  | VK_IMAGE_USAGE_STORAGE_BIT
                  | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkExternalMemoryImageCreateInfo extImgInfo{};
    extImgInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImgInfo.handleTypes = 0x00000002; // VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
    imgInfo.pNext = &extImgInfo;

    VkResult result = vkCreateImage(dev.device(), &imgInfo, nullptr, &alloc->image);
    if (result != VK_SUCCESS) {
        delete alloc;
        throw std::runtime_error("vkCreateImage failed: " + std::to_string(result));
    }

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(dev.device(), alloc->image, &memReqs);

    int memType = findMemoryType(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) memType = findMemoryType(memReqs.memoryTypeBits, 0);
    if (memType < 0) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("No compatible memory type for shared image");
    }

    VkExportMemoryAllocateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    exportInfo.handleTypes = 0x00000002;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    allocInfo.pNext = &exportInfo;

    result = vkAllocateMemory(dev.device(), &allocInfo, nullptr, &alloc->memory);
    if (result != VK_SUCCESS) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkAllocateMemory failed: " + std::to_string(result));
    }

    result = vkBindImageMemory(dev.device(), alloc->image, alloc->memory, 0);
    if (result != VK_SUCCESS) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkBindImageMemory failed: " + std::to_string(result));
    }

    // Export Win32 HANDLE
    VkMemoryGetWin32HandleInfoKHR getHandleInfo{};
    getHandleInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR;
    getHandleInfo.memory = alloc->memory;
    getHandleInfo.handleType = 0x00000002;

    auto vkGetMemoryWin32HandleKHR =
        reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(
            vkGetDeviceProcAddr(dev.device(), "vkGetMemoryWin32HandleKHR"));
    if (!vkGetMemoryWin32HandleKHR) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkGetMemoryWin32HandleKHR function not found");
    }

    result = vkGetMemoryWin32HandleKHR(dev.device(), &getHandleInfo, &alloc->win32Handle);
    if (result != VK_SUCCESS) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkGetMemoryWin32HandleKHR failed: " + std::to_string(result));
    }

    return alloc;
}

// ----------------------------------------------------------------
void releaseSharedAllocation(FsrSharedAllocation* alloc) {
    if (!alloc) return;
    auto& dev = VulkanDevice::get();

    if (alloc->image != VK_NULL_HANDLE) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
    }
    if (alloc->memory != VK_NULL_HANDLE) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
    }
    if (alloc->win32Handle) {
        CloseHandle(alloc->win32Handle);
    }

    delete alloc;
}

} // namespace fsr
```

---

### Task 4: jni_bridge.cpp — JNI Entry Points

**Files:**
- Create: `src/native/fsrvk/jni_bridge.cpp`

**Interfaces:**
- Consumes: `vulkan_device.h`, `vulkan_memory.h`, `fsr::VulkanDevice`, `fsr::FsrSharedAllocation`, `fsr::allocateSharedAllocation()`, `fsr::releaseSharedAllocation()`
- Produces: JNI entry points callable from Java `com.fsr2mod.vulkan.VulkanInterop`

- [ ] **Step 1: Write jni_bridge.cpp**

```cpp
#include "vulkan_device.h"
#include "vulkan_memory.h"
#include <jni.h>
#include <stdexcept>
#include <string>

#ifdef _WIN32
#include <windows.h>
#endif

// ----------------------------------------------------------------
// Helper: convert std::exception to Java RuntimeException
// ----------------------------------------------------------------
static void throwJavaException(JNIEnv* env, const std::exception& e) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) {
        env->ThrowNew(exClass, e.what());
    }
}

// ----------------------------------------------------------------
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getSharedHandle(
    JNIEnv* env, jclass /*clazz*/, jint w, jint h)
{
    try {
        auto* alloc = fsr::allocateSharedAllocation(
            static_cast<uint32_t>(w),
            static_cast<uint32_t>(h));
        return reinterpret_cast<jlong>(alloc);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

// ----------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_releaseSharedHandle(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle)
{
    auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(handle);
    fsr::releaseSharedAllocation(alloc);
}

// ----------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getDeviceInfo(
    JNIEnv* env, jclass /*clazz*/)
{
    try {
        auto& dev = fsr::VulkanDevice::get();
        dev.ensureInitialized();
        std::string info = dev.deviceName() + " — driver " + dev.driverVersion();
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return env->NewStringUTF("");
    }
}

// ----------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_terminate(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    fsr::VulkanDevice::get().destroy();
}

} // extern "C"
```

---

### Task 5: VulkanInterop.java — Java JNI Declarations

**Files:**
- Create: `src/client/java/com/fsr2mod/vulkan/VulkanInterop.java`

**Interfaces:**
- Consumes: nothing from other tasks (pure JNI declarations)
- Produces: Java class consumed by `FSRProcessor.java` (integration in future Phase 2)

- [ ] **Step 1: Write VulkanInterop.java**

```java
package com.fsr2mod.vulkan;

/**
 * JNI bridge to fsrvk.dll for VK→GL shared memory interop.
 *
 * Lifecycle:
 *   init:  getDeviceInfo()  // optional logging
 *   frame: glDeleteTextures(oldTex) → releaseSharedHandle(oldHandle) →
 *          getSharedHandle(w, h) → glImportMemoryWin32HandleEXT → ... → glFinish()
 *   shutdown: glDeleteTextures(tex) → releaseSharedHandle(handle) → terminate()
 *
 * Resource tracking uses a heap-allocated C++ struct (FsrSharedAllocation).
 * The jlong returned by getSharedHandle is a pointer to this struct — NOT
 * a raw Win32 HANDLE (Windows recycles handle integers, causing collision
 * bugs during rapid resize).
 */
public final class VulkanInterop {

    static {
        try {
            System.load(extractFromJar("/natives/fsrvk.dll"));
        } catch (UnsatisfiedLinkError e) {
            // FSR4 VK interop will be unavailable — graceful fallback handled by caller
        }
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

    // ----------------------------------------------------------
    // Internal: extract native library from mod JAR to temp dir
    // ----------------------------------------------------------
    private static String extractFromJar(String path) {
        try {
            java.io.InputStream in = VulkanInterop.class.getResourceAsStream(path);
            if (in == null) throw new UnsatisfiedLinkError("Native library not found in JAR: " + path);
            java.nio.file.Path temp = java.nio.file.Files.createTempFile("fsrvk", ".dll");
            temp.toFile().deleteOnExit();
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }
}
```

---

### Task 6: Gradle Build Integration

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Provides: `buildNative` Gradle task that compiles CMake and copies fsrvk.dll into JAR

- [ ] **Step 1: Add buildNative task to build.gradle**

Add after the `publishing` block:

```groovy
// Native C++ DLL build (FSR4 Phase 1: fsrvk.dll)
// Requires: CMake 3.20+, Visual Studio 2022, Vulkan SDK
tasks.register('buildNative') {
    doLast {
        def nativeDir = file('src/native/fsrvk')
        def buildDir  = new File(nativeDir, 'build')
        buildDir.mkdirs()

        exec {
            workingDir buildDir
            commandLine 'cmake', '-B', '.', '-S', nativeDir.absolutePath,
                    '-DCMAKE_BUILD_TYPE=Release'
        }
        exec {
            workingDir buildDir
            commandLine 'cmake', '--build', '.', '--config', 'Release'
        }
    }
}

// Copy fsrvk.dll into mod JAR
processResources {
    dependsOn 'buildNative'
    from("${project.rootDir}/src/native/fsrvk/build/Release") {
        include 'fsrvk.dll'
        into 'natives'
    }
}
```

- [ ] **Step 2: Handle buildNative gracefully when CMake/Vulkan SDK is absent**

Wrap the `buildNative` task to not fail the build when the native toolchain is missing (so `gradlew build` works on CI or developer machines without Vulkan SDK):

```groovy
tasks.register('buildNative') {
    onlyIf {
        file('src/native/fsrvk/CMakeLists.txt').exists()
    }
    doLast {
        try {
            exec { commandLine 'cmake', '--version' }
        } catch (Exception e) {
            logger.warn('CMake not found — skipping native DLL build')
            return
        }
        def nativeDir = file('src/native/fsrvk')
        def buildDir  = new File(nativeDir, 'build')
        buildDir.mkdirs()

        exec {
            workingDir buildDir
            commandLine 'cmake', '-B', '.', '-S', nativeDir.absolutePath,
                    '-DCMAKE_BUILD_TYPE=Release'
            ignoreExitValue true
        }
        exec {
            workingDir buildDir
            commandLine 'cmake', '--build', '.', '--config', 'Release'
            ignoreExitValue true
        }
    }
}
```

---

### Task 7: Verify Build + Update AGENTS.md

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Run Gradle build to verify Java compilation**

```bash
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL (DLL won't be present if CMake/Vulkan SDK not installed — Java source compiles fine, `System.load` will fail gracefully at runtime)

- [ ] **Step 2: Verify AGENTS.md Phase 1 section is accurate**

Read `AGENTS.md` and confirm the Phase 1 section matches the implementation (FsrSharedAllocation pointer approach, terminate() method, CloseHandle, AMD decoder, etc.). Already updated as part of the design phase.

- [ ] **Step 3: Verify C++ compiles (if Vulkan SDK is available)**

```bash
cd src/native/fsrvk
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

Expected: fsrvk.dll produced in `build/Release/`
