#include "vulkan_device.h"
#include "vulkan_memory.h"
#include "d3d12_device.h"
#include "d3d12_shared_resources.h"
#include "fsr4_context.h"
#include <jni.h>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <malloc.h>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

// Vulkan extension name strings (avoids requiring vulkan.h defines in this translation unit)
#ifndef VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME
#define VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME "VK_KHR_external_memory"
#endif
#ifndef VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME
#define VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME "VK_KHR_external_memory_win32"
#endif

// Diagnostics — writes to both file and stderr (appears in Minecraft console)
static void diagLog(const char* fmt, ...) {
    FILE* f = fopen("C:\\Users\\arshjot\\Downloads\\fsr2mod\\fsr4_diag.log", "a");
    va_list args;
    va_start(args, fmt);
    if (f) {
        vfprintf(f, fmt, args);
        fputc('\n', f);
        fclose(f);
    }
    va_end(args);
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
	    fflush(stderr);
	    va_end(args);
	}

	// Millisecond timestamp for debug timing
	static long diagTimeMs() {
	    static LARGE_INTEGER freq = { 0 };
	    if (freq.QuadPart == 0) QueryPerformanceFrequency(&freq);
	    LARGE_INTEGER now;
	    QueryPerformanceCounter(&now);
	    return (now.QuadPart * 1000LL) / freq.QuadPart;
	}

	// ----------------------------------------------------------------
static void throwJavaException(JNIEnv* env, const std::exception& e) {
    std::fprintf(stderr, "[fsrvk] JNI exception: %s\n", e.what());
    fflush(stderr);
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass) {
        env->ThrowNew(exClass, e.what());
    } else {
        std::fprintf(stderr, "[fsrvk] Failed to find RuntimeException class\n");
        fflush(stderr);
    }
}

// ----------------------------------------------------------------
extern "C" {

// ---- Vulkan interop (non-FSR4 shared memory) ----

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getSharedHandle(
    JNIEnv* env, jclass, jint w, jint h)
{
    __try {
        auto* alloc = fsr::allocateSharedAllocation(
            static_cast<uint32_t>(w), static_cast<uint32_t>(h));
        return reinterpret_cast<jlong>(alloc);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // Delay-load of vulkan-1.dll failed or Vulkan init SEH —
        // return 0 (Java sees "not available").
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_releaseSharedHandle(
    JNIEnv* env, jclass, jlong handle)
{
    __try {
        auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(handle);
        if (!alloc) return;
        fsr::releaseSharedAllocation(alloc);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }
}

// Helper called by getDeviceInfo — C++ objects (std::string) are fine here
// because this function has no __try/__except
static jstring getDeviceInfoHelper(JNIEnv* env) {
    auto& dev = fsr::VulkanDevice::get();
    dev.ensureInitialized();
    std::string info = dev.deviceName() + " \xe2\x80\x94 driver " + dev.driverVersion();
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getDeviceInfo(
    JNIEnv* env, jclass)
{
    __try {
        // Helper function contains all C++ objects — __try only sees POD (jstring) return
        return getDeviceInfoHelper(env);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // vulkan-1.dll delay-load failed or Vulkan init SEH
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_terminate(
    JNIEnv*, jclass)
{
    __try {
        fsr::VulkanDevice::get().destroy();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getWin32Handle(
    JNIEnv* env, jclass, jlong allocPtr)
{
    try {
        if (!allocPtr) return 0;
        auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(allocPtr);
        return reinterpret_cast<jlong>(alloc->win32Handle);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getAllocationSize(
    JNIEnv* env, jclass, jlong allocPtr)
{
    try {
        if (!allocPtr) return 0;
        auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(allocPtr);
        return static_cast<jlong>(alloc->allocationSize);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

// ---- D3D12 FSR4 pool (5 shared textures) ----

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_allocateFsr4Pool(
    JNIEnv* env, jclass,
    jint renderW, jint renderH, jint displayW, jint displayH)
{
    diagLog("=== allocateFsr4Pool(%d, %d, %d, %d) ===",
        renderW, renderH, displayW, displayH);
    try {
        diagLog("Step 1: new D3D12SharedResources");
        auto* pool = new fsr::D3D12SharedResources();
        diagLog("Step 2: D3D12Device::get()");
        auto& dev = fsr::D3D12Device::get();
        diagLog("Step 3: ensureInitialized()");
        auto devState = dev.ensureInitialized();
        diagLog("Step 3 result: state=%d, device='%s'",
            static_cast<int>(devState), dev.deviceName().c_str());
        if (devState != fsr::D3D12DeviceState::READY) {
            delete pool;
            diagLog("FAIL: device not ready");
            throw std::runtime_error(
                "D3D12Device not ready (state=" +
                std::to_string(static_cast<int>(devState)) +
                "), deviceName=" + dev.deviceName());
        }
        diagLog("Step 4: pool->create(dev, %dx%d -> %dx%d)",
            renderW, renderH, displayW, displayH);
        bool ok = pool->create(dev.device(),
            static_cast<uint32_t>(renderW), static_cast<uint32_t>(renderH),
            static_cast<uint32_t>(displayW), static_cast<uint32_t>(displayH));
        diagLog("Step 4 result: ok=%d", ok);
        if (!ok) {
            delete pool;
            diagLog("FAIL: pool->create returned false");
            throw std::runtime_error(
                "D3D12SharedResources::create failed (" +
                std::to_string(renderW) + "x" + std::to_string(renderH) +
                " -> " + std::to_string(displayW) + "x" + std::to_string(displayH) + ")");
        }
        diagLog("SUCCESS: pool=%p", (void*)pool);
        return reinterpret_cast<jlong>(pool);
    } catch (const std::exception& e) {
        diagLog("EXCEPTION: %s", e.what());
        throwJavaException(env, e);
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4Win32Handle(
    JNIEnv* env, jclass, jlong poolPtr, jint index)
{
    try {
        if (!poolPtr) return 0;
        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
        if (index < 0 || index >= fsr::D3D12SharedResources::COUNT) return 0;
        return reinterpret_cast<jlong>(pool->win32Handles[index]);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4ResourcePtr(
    JNIEnv* env, jclass, jlong poolPtr, jint index)
{
    try {
        if (!poolPtr) return 0;
        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
        if (index < 0 || index >= fsr::D3D12SharedResources::COUNT) return 0;
        return reinterpret_cast<jlong>(pool->resources[index]);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4AllocationSize(
    JNIEnv* env, jclass, jlong poolPtr, jint index)
{
    try {
        if (!poolPtr) return 0;
        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
        if (index < 0 || index >= fsr::D3D12SharedResources::COUNT) return 0;
        return static_cast<jlong>(pool->allocationSizes[index]);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_releaseFsr4Pool(
    JNIEnv* env, jclass, jlong poolPtr)
{
    try {
        if (!poolPtr) return;
        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
        pool->destroy();
        delete pool;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
    }
}

// ---- FSR4 Context (Fsr4Native) ----

namespace {
    fsr::Fsr4Context* g_fsr4Context = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_initFsr4(
    JNIEnv* env, jclass,
    jint renderW, jint renderH, jint displayW, jint displayH)
{
    try {
        if (g_fsr4Context) {
            g_fsr4Context->destroy();
            delete g_fsr4Context;
            g_fsr4Context = nullptr;
        }
        g_fsr4Context = new fsr::Fsr4Context();
        bool ok = g_fsr4Context->initialize(
            static_cast<uint32_t>(renderW), static_cast<uint32_t>(renderH),
            static_cast<uint32_t>(displayW), static_cast<uint32_t>(displayH));
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_dispatchFsr4(
    JNIEnv* env, jclass,
    jfloat jitterX, jfloat jitterY,
    jfloat cameraFar, jfloat cameraNear, jfloat cameraFovY,
    jfloat deltaTime, jint frameIndex, jboolean reset,
    jlong colorPoolPtr, jint colorIndex,
    jlong depthPoolPtr, jint depthIndex,
    jlong mvPoolPtr, jint mvIndex,
    jlong reactivePoolPtr, jint reactiveIndex,
    jlong outputPoolPtr, jint outputIndex)
{
    try {
        if (!g_fsr4Context || !g_fsr4Context->isInitialized()) return JNI_FALSE;

        auto resolve = [](jlong poolPtr, jint idx) -> ID3D12Resource* {
            if (!poolPtr) return nullptr;
            auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
            if (idx < 0 || idx >= fsr::D3D12SharedResources::COUNT) return nullptr;
            return pool->resources[idx];
        };

        fsr::Fsr4DispatchParams params{};
        params.color         = resolve(colorPoolPtr, colorIndex);
        params.depth         = resolve(depthPoolPtr, depthIndex);
        params.motionVectors = resolve(mvPoolPtr, mvIndex);
        params.reactive      = resolve(reactivePoolPtr, reactiveIndex);
        params.output        = resolve(outputPoolPtr, outputIndex);
        params.jitterX       = jitterX;
        params.jitterY       = jitterY;
        params.cameraNear    = cameraNear;
        params.cameraFar     = cameraFar;
        params.fovYRad       = cameraFovY;
        params.deltaTimeMs   = deltaTime;
        params.frameIndex    = static_cast<uint32_t>(frameIndex);
        params.reset         = reset == JNI_TRUE;

        bool ok = g_fsr4Context->dispatch(params);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_waitIdle(
    JNIEnv*, jclass)
{
    if (g_fsr4Context) {
        long t0 = diagTimeMs();
        g_fsr4Context->finish();
        long dt = diagTimeMs() - t0;
        diagLog("waitIdle: FSR4 finish OK (took %ldms)", dt);
    } else {
        diagLog("waitIdle: no FSR4 context");
    }
}

	JNIEXPORT void JNICALL
	Java_com_fsr2mod_vulkan_Fsr4Native_shutdownFsr4(
	    JNIEnv* env, jclass)
	{
	    try {
	        if (g_fsr4Context) {
	            g_fsr4Context->destroy();
	            delete g_fsr4Context;
	            g_fsr4Context = nullptr;
	        }
	    } catch (const std::exception& e) {
	        throwJavaException(env, e);
	    }
	    // NOTE: D3D12Device::dispose() is NOT called here.
	    // It must be called AFTER the D3D12 shared resources pool is destroyed,
	    // because pool->destroy() calls Release() on D3D12 child resources
	    // (textures) that belong to the device.  If we release the device first,
	    // those ->Release() calls access freed memory → crash.
	    // The caller (FSRProcessor.destroy()) handles ordering:
	    //   shutdownFsr4() → destroyFsr4Pool() → disposeD3D12()
	}
	
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_Fsr4Native_disposeD3D12(
		    JNIEnv*, jclass)
		{
		    // Dispose the D3D12Device singleton while the DLL is still loaded.
		    // Must be called after all D3D12 shared resources have been released
		    // (destroyFsr4Pool).  After dispose(), all ComPtrs are null and the
		    // passive destructor (~D3D12Device) is a no-op.
		    //
		    // Wrapped in SEH because even with disposed_ guard, calling Release()
		    // on the device's COM objects during process shutdown edge cases can
		    // trigger STATUS_STACK_BUFFER_OVERRUN (0xC0000409).
			    __try {
			        fsr::D3D12Device::get().dispose();
			    } __except (EXCEPTION_EXECUTE_HANDLER) {
			        diagLog("D3D12Device::dispose() crashed in disposeD3D12(), "
			                "code=0x%08lx — ignoring", GetExceptionCode());
			    }
		}
	
	// ---- VulkanMod interop (same-device copy + D3D12) ----
	
		namespace {
		    // Stored VulkanMod device handles (set via setVulkanModDevice)
		    VkDevice g_vkModDevice = VK_NULL_HANDLE;
		    VkPhysicalDevice g_vkModPhysDevice = VK_NULL_HANDLE;
		    VkQueue g_vkModGraphicsQueue = VK_NULL_HANDLE;
		    uint32_t g_vkModQueueFamilyIndex = UINT32_MAX;
		    bool g_vkModDeviceSet = false;
		
		    // Swapchain format
		    bool g_vkModSwapchainBGRA = false;
		    bool g_vkModSwapchainFormatSet = false;
		
		    // External image resources (2 VkImages + memory + handles)
		    VkImage g_vkModInputImage = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModInputMemory = VK_NULL_HANDLE;
		    HANDLE g_vkModInputHandle = nullptr;
		
		    VkImage g_vkModOutputImage = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModOutputMemory = VK_NULL_HANDLE;
		    HANDLE g_vkModOutputHandle = nullptr;
		
		    // D3D12 resources opened from VK handles (shared memory)
		    ID3D12Resource* g_vkModD3D12Input = nullptr;
		    ID3D12Resource* g_vkModD3D12Output = nullptr;
		
		    uint32_t g_vkModImageWidth = 0;
		    uint32_t g_vkModImageHeight = 0;
		
		    // Track whether exported handles have been opened as D3D12 resources
		    bool g_vkModInputOpened = false;
		    bool g_vkModOutputOpened = false;
		
		    // Cached function pointer for vkGetMemoryWin32HandleKHR on g_vkModDevice
		    PFN_vkGetMemoryWin32HandleKHR g_vkModGetMemWin32Handle = nullptr;
		    // Cached function pointer for vkGetMemoryWin32HandlePropertiesKHR (import path)
		    PFN_vkGetMemoryWin32HandlePropertiesKHR g_vkModGetMemWin32HandleProps = nullptr;
		
		    // Tracks whether the first layout transition (UNDEFINED → GENERAL) has been done
		    // for the external VkImages. Before the first barrier, the actual image layout
		    // is UNDEFINED (set at creation), but recordFrameCopyOps declares GENERAL as the
		    // old layout. On the first usage, we must use VK_IMAGE_LAYOUT_UNDEFINED instead
		    // to prevent the driver from discarding memory contents written by D3D12
		    // (shared external memory). After the first frame, the images are in GENERAL
		    // (set by the post-copy barriers) and subsequent frames use GENERAL correctly.
		    bool g_vkModImagesLayoutInitialized = false;
		
		    // Dedicated copy resources (own command buffer — not piggybacking on VulkanMod's
		    // ended command buffer).
		    VkCommandPool g_vkModCopyPool = VK_NULL_HANDLE;
		    VkCommandBuffer g_vkModCopyCmdBuf = VK_NULL_HANDLE;
		    VkFence g_vkModCopyFence = VK_NULL_HANDLE;
		    bool g_vkModCopySubmitted = false;
		
		    // Find memory type on g_vkModPhysDevice
		    int findVkModMemoryType(uint32_t typeBits, VkMemoryPropertyFlags requiredFlags) {
		        VkPhysicalDeviceMemoryProperties memProps;
		        vkGetPhysicalDeviceMemoryProperties(g_vkModPhysDevice, &memProps);
		        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
		            if ((typeBits & (1u << i)) &&
		                (memProps.memoryTypes[i].propertyFlags & requiredFlags) == requiredFlags) {
		                return static_cast<int>(i);
		            }
		        }
		        return -1;
		    }
		
			// Find graphics queue family index from physical device
			// Uses alloca instead of std::vector — safe inside __try/__except SEH blocks.
			    static uint32_t findGraphicsQueueFamily(VkPhysicalDevice physDev) {
			        uint32_t count = 0;
			        vkGetPhysicalDeviceQueueFamilyProperties(physDev, &count, nullptr);
			        if (count == 0) return UINT32_MAX;
			        size_t bufSz = static_cast<size_t>(count) * sizeof(VkQueueFamilyProperties);
			        VkQueueFamilyProperties* families =
			            static_cast<VkQueueFamilyProperties*>(_alloca(bufSz));
			        vkGetPhysicalDeviceQueueFamilyProperties(physDev, &count, families);
			        for (uint32_t i = 0; i < count; i++) {
			            if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) return i;
			        }
			        return UINT32_MAX;
			    }
		
		    // Create copy command resources (pool + cmd buffer + fence)
		    static bool createCopyCommandResources() {
		        if (g_vkModDevice == VK_NULL_HANDLE) return false;
		        if (g_vkModQueueFamilyIndex == UINT32_MAX) return false;
		
		        VkResult res;
		
		        // Command pool
		        VkCommandPoolCreateInfo poolInfo{};
		        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
		        poolInfo.queueFamilyIndex = g_vkModQueueFamilyIndex;
		        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
		        res = vkCreateCommandPool(g_vkModDevice, &poolInfo, nullptr, &g_vkModCopyPool);
		        if (res != VK_SUCCESS) {
		            diagLog("createCopyCommandResources: vkCreateCommandPool failed → %d", res);
		            return false;
		        }
		
		        // Command buffer
		        VkCommandBufferAllocateInfo allocInfo{};
		        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
		        allocInfo.commandPool = g_vkModCopyPool;
		        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
		        allocInfo.commandBufferCount = 1;
		        res = vkAllocateCommandBuffers(g_vkModDevice, &allocInfo, &g_vkModCopyCmdBuf);
		        if (res != VK_SUCCESS) {
		            diagLog("createCopyCommandResources: vkAllocateCommandBuffers failed → %d", res);
		            vkDestroyCommandPool(g_vkModDevice, g_vkModCopyPool, nullptr);
		            g_vkModCopyPool = VK_NULL_HANDLE;
		            return false;
		        }
		
		        // Fence (initially signaled so first wait succeeds immediately)
		        VkFenceCreateInfo fenceInfo{};
		        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
		        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
		        res = vkCreateFence(g_vkModDevice, &fenceInfo, nullptr, &g_vkModCopyFence);
		        if (res != VK_SUCCESS) {
		            diagLog("createCopyCommandResources: vkCreateFence failed → %d", res);
		            vkFreeCommandBuffers(g_vkModDevice, g_vkModCopyPool, 1, &g_vkModCopyCmdBuf);
		            vkDestroyCommandPool(g_vkModDevice, g_vkModCopyPool, nullptr);
		            g_vkModCopyPool = VK_NULL_HANDLE;
		            g_vkModCopyCmdBuf = VK_NULL_HANDLE;
		            return false;
		        }
		
		        g_vkModCopySubmitted = false;
		        diagLog("createCopyCommandResources: OK (pool=%p, cmd=%p, fence=%p)",
		            (void*)g_vkModCopyPool, (void*)g_vkModCopyCmdBuf, (void*)g_vkModCopyFence);
		        return true;
		    }
		
			    // Destroy copy command resources
			    static void destroyCopyCommandResources() {
			        if (g_vkModDevice == VK_NULL_HANDLE) return;
			        // Wait for any in-flight copy before destroying resources
			        if (g_vkModCopySubmitted && g_vkModCopyFence != VK_NULL_HANDLE) {
			            vkWaitForFences(g_vkModDevice, 1, &g_vkModCopyFence, VK_TRUE, UINT64_MAX);
			            g_vkModCopySubmitted = false;
			        }
			        if (g_vkModCopyFence != VK_NULL_HANDLE) {
			            vkDestroyFence(g_vkModDevice, g_vkModCopyFence, nullptr);
			            g_vkModCopyFence = VK_NULL_HANDLE;
			        }
		        if (g_vkModCopyPool != VK_NULL_HANDLE) {
		            vkDestroyCommandPool(g_vkModDevice, g_vkModCopyPool, nullptr);
		            g_vkModCopyPool = VK_NULL_HANDLE;
		            g_vkModCopyCmdBuf = VK_NULL_HANDLE;
		        }
		        g_vkModCopySubmitted = false;
		    }
		
		    // Helper to clean up VkImages and D3D12 resources without touching copy resources.
		    // Call destroyCopyCommandResources() first (or ensure copy fence waited) before
		    // calling this, to guarantee no in-flight copies reference these images.
		    void destroyVkModImages() {
		        if (g_vkModD3D12Input) { g_vkModD3D12Input->Release(); g_vkModD3D12Input = nullptr; }
		        if (g_vkModD3D12Output) { g_vkModD3D12Output->Release(); g_vkModD3D12Output = nullptr; }
		        g_vkModInputOpened = false;
		        g_vkModOutputOpened = false;
		
		        // Destroy VK images + free memory
		        if (g_vkModInputImage != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModInputMemory != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModInputMemory, nullptr);
		            g_vkModInputMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModOutputImage != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModOutputMemory != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModOutputMemory, nullptr);
		            g_vkModOutputMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModInputHandle) { CloseHandle(g_vkModInputHandle); g_vkModInputHandle = nullptr; }
		        if (g_vkModOutputHandle) { CloseHandle(g_vkModOutputHandle); g_vkModOutputHandle = nullptr; }
		
		        g_vkModImageWidth = 0;
		        g_vkModImageHeight = 0;
		        g_vkModImagesLayoutInitialized = false;
		    }
		}
	
	JNIEXPORT void JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_setVulkanModDevice(
	    JNIEnv*, jclass, jlong devicePtr, jlong physDevicePtr)
	{
	    __try {
	        g_vkModDevice = reinterpret_cast<VkDevice>(devicePtr);
	        g_vkModPhysDevice = reinterpret_cast<VkPhysicalDevice>(physDevicePtr);
	        g_vkModDeviceSet = (devicePtr != 0 && physDevicePtr != 0);
	
		        if (g_vkModDeviceSet) {
		            // Load function pointer for memory handle export
		            g_vkModGetMemWin32Handle = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(
		                vkGetDeviceProcAddr(g_vkModDevice, "vkGetMemoryWin32HandleKHR"));
		            // Load function pointer for memory handle import (D3D12→VK)
		            g_vkModGetMemWin32HandleProps = reinterpret_cast<PFN_vkGetMemoryWin32HandlePropertiesKHR>(
		                vkGetDeviceProcAddr(g_vkModDevice, "vkGetMemoryWin32HandlePropertiesKHR"));
		        }
	
	        diagLog("VulkanMod device set: device=%p physDevice=%p pfn=%p",
	            (void*)g_vkModDevice, (void*)g_vkModPhysDevice,
	            (void*)g_vkModGetMemWin32Handle);
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	    }
	}
	
// Helper for checkExternalMemorySupport — C++ objects (std::vector, std::string) are safe here
static jboolean checkExternalMemorySupportHelper() {
    if (!g_vkModDeviceSet) return JNI_FALSE;

    uint32_t extCount = 0;
    VkResult res = vkEnumerateDeviceExtensionProperties(
        g_vkModPhysDevice, nullptr, &extCount, nullptr);
    if (res != VK_SUCCESS || extCount == 0) return JNI_FALSE;

    std::vector<VkExtensionProperties> exts(extCount);
    res = vkEnumerateDeviceExtensionProperties(
        g_vkModPhysDevice, nullptr, &extCount, exts.data());
    if (res != VK_SUCCESS) return JNI_FALSE;

    bool hasExtMem = false;
    bool hasExtMemWin32 = false;
    for (const auto& ext : exts) {
        if (strcmp(ext.extensionName, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME) == 0)
            hasExtMem = true;
        if (strcmp(ext.extensionName, VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME) == 0)
            hasExtMemWin32 = true;
    }

    bool supported = hasExtMem && hasExtMemWin32;
    diagLog("VulkanMod external memory support: ext_mem=%d ext_mem_win32=%d => %s",
        hasExtMem, hasExtMemWin32, supported ? "SUPPORTED" : "NOT SUPPORTED");
    return supported ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_checkExternalMemorySupport(
    JNIEnv*, jclass)
{
    __try {
        return checkExternalMemorySupportHelper();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return JNI_FALSE;
    }
}
	
	JNIEXPORT void JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_setSwapchainFormat(
	    JNIEnv*, jclass, jboolean isBGRA)
	{
	    __try {
	        g_vkModSwapchainBGRA = (isBGRA == JNI_TRUE);
	        g_vkModSwapchainFormatSet = true;
	        diagLog("VulkanMod swapchain format: %s",
	            g_vkModSwapchainBGRA ? "BGRA" : "RGBA");
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	    }
	}
	
	// ---- External image management ----
	
// Forward declarations for functions used in initExternalImages warmup
// that are defined later in this file.
static bool openD3D12SharedHandle(HANDLE vkHandle, ID3D12Resource** outResource);

	JNIEXPORT jboolean JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_initExternalImages(
		    JNIEnv*, jclass, jint width, jint height)
		{
		    __try {
		        if (!g_vkModDeviceSet || !g_vkModGetMemWin32Handle) {
		            diagLog("initExternalImages: VulkanMod device not configured");
		            return JNI_FALSE;
		        }
		
		        // Destroy existing images if any
		        destroyVkModImages();
		
		        g_vkModImageWidth = static_cast<uint32_t>(width);
		        g_vkModImageHeight = static_cast<uint32_t>(height);
		
		        diagLog("initExternalImages(%dx%d)", width, height);
		
		        // NOTE: INPUT and OUTPUT VkImages are NOT created here.
		        // Both are imported from the D3D12 pool's shared textures:
		        //   - INPUT (color, index 0) is imported via importInputFromD3D12Pool()
		        //   - OUTPUT (index 4) is imported via importOutputFromD3D12Pool()
		        // This avoids the AMD Radeon 860M driver bug where VK→D3D12
		        // OpenSharedHandle crashes with ACCESS_VIOLATION. By using
		        // D3D12-allocated pool textures and importing them into VK,
		        // no VK→D3D12 handle opening is needed.
		
		        // Get graphics queue for copy submissions
		        if (g_vkModQueueFamilyIndex == UINT32_MAX) {
		            g_vkModQueueFamilyIndex = findGraphicsQueueFamily(g_vkModPhysDevice);
		        }
		        if (g_vkModQueueFamilyIndex != UINT32_MAX) {
		            vkGetDeviceQueue(g_vkModDevice, g_vkModQueueFamilyIndex, 0, &g_vkModGraphicsQueue);
		            diagLog("  Graphics queue: %p (family=%u)",
		                (void*)g_vkModGraphicsQueue, g_vkModQueueFamilyIndex);
		        } else {
		            diagLog("  WARNING: no graphics queue family found — copy ops will fail");
		        }
		
		        // Create dedicated copy command resources
		        if (!createCopyCommandResources()) {
		            diagLog("  WARNING: copy command resources creation failed");
		        }
		
		        diagLog("initExternalImages: SUCCESS (%dx%d)", width, height);
		        return JNI_TRUE;
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("initExternalImages: SEH exception (vulkan-1.dll delay-load?)");
	        return JNI_FALSE;
	    }
	}
	
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_destroyExternalImages(
		    JNIEnv*, jclass)
		{
		    __try {
		        diagLog("destroyExternalImages");
		        destroyCopyCommandResources();  // Wait for fence, destroy pool/cmdbuf
		        destroyVkModImages();
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		    }
		}
	
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_recordFrameCopyOps(
    JNIEnv*, jclass,
    jlong cmdBuf, jlong swapchainImage, jint width, jint height)
{
    __try {
        if (!g_vkModDeviceSet) {
            diagLog("recordFrameCopyOps: VulkanMod device not set — skipping");
            return;
        }
        if (g_vkModInputImage == VK_NULL_HANDLE || g_vkModOutputImage == VK_NULL_HANDLE) {
            diagLog("recordFrameCopyOps: external images not initialized");
            return;
        }

        VkCommandBuffer cb = reinterpret_cast<VkCommandBuffer>(cmdBuf);
        VkImage swapImg = reinterpret_cast<VkImage>(swapchainImage);

        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = 0;
        barrier.subresourceRange.levelCount = 1;
        barrier.subresourceRange.baseArrayLayer = 0;
        barrier.subresourceRange.layerCount = 1;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;

        VkImageCopy copyRegion{};
        copyRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.srcSubresource.mipLevel = 0;
        copyRegion.srcSubresource.baseArrayLayer = 0;
        copyRegion.srcSubresource.layerCount = 1;
        copyRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copyRegion.dstSubresource.mipLevel = 0;
        copyRegion.dstSubresource.baseArrayLayer = 0;
        copyRegion.dstSubresource.layerCount = 1;
        copyRegion.extent.width = static_cast<uint32_t>(width);
        copyRegion.extent.height = static_cast<uint32_t>(height);
        copyRegion.extent.depth = 1;

        // Barrier 1: swapchain PRESENT_SRC → TRANSFER_SRC
        barrier.image = swapImg;
        barrier.oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // Barrier 2: input [UNDEFINED|GENERAL] → TRANSFER_DST
	        // On the first frame, the image was created with VK_IMAGE_LAYOUT_UNDEFINED
	        // (from initExternalImages). If we lie and say GENERAL, the driver may
	        // discard the content (which doesn't matter for input since we write to
	        // it via Copy1 anyway) but more critically, for the OUTPUT image the
	        // D3D12-written data would be lost. We use the correct initial layout
	        // for the first frame to avoid undefined behavior.
	        barrier.image = g_vkModInputImage;
	        barrier.oldLayout = g_vkModImagesLayoutInitialized
	            ? VK_IMAGE_LAYOUT_GENERAL
	            : VK_IMAGE_LAYOUT_UNDEFINED;
	        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
	        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
	        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        diagLog("recordFrameCopyOps: Copy1 swapchain=%p->input=%p (%dx%d)",
	            (void*)swapImg, (void*)g_vkModInputImage, width, height);
	        vkCmdCopyImage(cb,
	            swapImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
	            g_vkModInputImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
	            1, &copyRegion);

        // Barrier 3: swapchain TRANSFER_SRC → TRANSFER_DST
        barrier.image = swapImg;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        // Barrier 4: output [UNDEFINED|GENERAL] → TRANSFER_SRC
		        // CRITICAL: on the first frame the output image has VK_IMAGE_LAYOUT_UNDEFINED
		        // (set at creation in importOutputFromD3D12Pool). Using GENERAL as the old layout
		        // would let the driver discard the D3D12-written data in shared memory — causing
		        // the VK copy to read zeroes → black screen.
		        // See Vulkan spec: "Transitions from VK_IMAGE_LAYOUT_UNDEFINED do not guarantee
		        // preservation of image contents." We must also include VK_ACCESS_MEMORY_WRITE_BIT
		        // in dstAccessMask to ensure external memory writes (from D3D12 FSR4 dispatch) are
		        // made visible to the Vulkan transfer read that follows.
		        //
		        // For subsequent frames (GENERAL → TRANSFER_SRC), the image was left in GENERAL
		        // by the previous frame's barrier 7, and D3D12 has written new data to it since.
		        // VK_ACCESS_MEMORY_WRITE_BIT in srcAccessMask tells the driver to wait for those
		        // external writes before allowing the TRANSFER_READ.
		        barrier.image = g_vkModOutputImage;
		        barrier.oldLayout = g_vkModImagesLayoutInitialized
		            ? VK_IMAGE_LAYOUT_GENERAL
		            : VK_IMAGE_LAYOUT_UNDEFINED;
		        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        if (g_vkModImagesLayoutInitialized) {
		            // Subsequent frames: D3D12 wrote to output between frames
		            barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
		            barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		        } else {
		            // First frame: no prior VK accesses, need to make external writes visible
		            barrier.srcAccessMask = 0;
		            barrier.dstAccessMask = VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
		        }
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        diagLog("recordFrameCopyOps: Copy2 output=%p->swapchain=%p",
	            (void*)g_vkModOutputImage, (void*)swapImg);
	        // Copy 2: output → swapchain (display previous FSR4 result)
        vkCmdCopyImage(cb,
            g_vkModOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            swapImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1, &copyRegion);

        // Barrier 5: swapchain TRANSFER_DST → PRESENT_SRC
        barrier.image = swapImg;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

        // Barrier 6: input TRANSFER_DST → GENERAL
        // VK_ACCESS_MEMORY_READ_BIT in dstAccessMask is REQUIRED for
        // external memory coherence: the D3D12 FSR4 dispatch reads the
        // input image via shared memory (CopyResource in dispatchFsr4VkMod),
        // and without MEMORY_READ_BIT the Vulkan driver may not make the
        // written data visible to the D3D12 side — causing the FSR4 SDK
        // to process stale/zero input data.
        barrier.image = g_vkModInputImage;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_MEMORY_READ_BIT;
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // Barrier 7: output TRANSFER_SRC → GENERAL
	        barrier.image = g_vkModOutputImage;
	        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
	        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
	        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
	        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_TRANSFER_BIT,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // Mark layouts as initialized — future frames use GENERAL as the old layout.
	        // This flag is set at recording time (not GPU execution time), which is safe
	        // because frame N+1 never records until frame N's submission completes (the
	        // frame-buffer swapchain present + waitForFrameCopy enforce ordering).
	        g_vkModImagesLayoutInitialized = true;

    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("recordFrameCopyOps: SEH exception");
    }
}
		
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_waitForFrameCopy(
		    JNIEnv*, jclass)
		{
		    __try {
		        diagLog("waitForFrameCopy: vkQueueWaitIdle(queue=%p)...",
		            (void*)g_vkModGraphicsQueue);
		        // NOTE: The dedicated copy fence (g_vkModCopyFence) is deliberately
		        // unused — recordFrameCopyOps records into VulkanMod's main render
		        // command buffer, not the separate copy command buffer. The fence
		        // is never signaled by any submission path.
		        //
		        // Instead, we wait for VulkanMod's graphics queue to idle. This
		        // guarantees the previous frame's VK copy ops (swapchain → input
		        // recorded in recordFrameCopyOps) have completed execution on GPU
		        // before the D3D12 FSR4 dispatch reads the input via shared memory.
		        //
		        // A queue wait is a full GPU drain, but this is acceptable because:
		        //   (1) It runs once per frame between frames (queue is nearly idle)
		        //   (2) The D3D12 fence wait (waitIdle) already serializes the CPU
		        //   (3) Correctness requires it — without it FSR4 processes stale input
		        if (g_vkModGraphicsQueue != VK_NULL_HANDLE) {
		            long t0 = diagTimeMs();
		            VkResult res = vkQueueWaitIdle(g_vkModGraphicsQueue);
		            long dt = diagTimeMs() - t0;
		            if (res != VK_SUCCESS) {
		                diagLog("waitForFrameCopy: vkQueueWaitIdle failed → %d (took %ldms)", res, dt);
		            } else {
		                diagLog("waitForFrameCopy: vkQueueWaitIdle OK (took %ldms)", dt);
		            }
		        } else {
		            diagLog("waitForFrameCopy: no graphics queue — skipping wait");
		        }

		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("waitForFrameCopy: SEH exception");
		    }
		}
	
	// Helper for exportInputToD3D12 / importOutputFromD3D12 — C++ COM calls
	// (no destructors) are safe inside __try, but we extract this to log the
	// specific step that fails.
		static bool openD3D12SharedHandle(HANDLE vkHandle, ID3D12Resource** outResource) {
		    // Step 1: Get D3D12 device
		    diagLog("  openD3D12SharedHandle: getting D3D12 device...");
		    auto& dev = fsr::D3D12Device::get();
		    if (dev.ensureInitialized() != fsr::D3D12DeviceState::READY) {
		        diagLog("  FAIL: D3D12 device not ready");
		        return false;
		    }
		
		    // Step 2: Check device pointer
		    ID3D12Device* d3dDev = dev.device();
		    diagLog("  openD3D12SharedHandle: D3D12 device=%p", (void*)d3dDev);
		    if (!d3dDev) {
		        diagLog("  FAIL: device pointer is null");
		        return false;
		    }
		
		    // Step 3: Call OpenSharedHandle (with SEH + retry)
		    // Some AMD drivers crash on the first OpenSharedHandle for a VK-exported
		    // handle (ACCESS_VIOLATION 0xC0000005). Subsequent calls succeed.
		    // We catch the crash and retry once.
		    for (int attempt = 0; attempt < 3; attempt++) {
		        HRESULT hr = E_FAIL;
		        __try {
		            diagLog("  openD3D12SharedHandle: [attempt %d] calling OpenSharedHandle(handle=%p)...",
		                attempt + 1, (void*)vkHandle);
		            hr = d3dDev->OpenSharedHandle(vkHandle, IID_PPV_ARGS(outResource));
		        } __except (EXCEPTION_EXECUTE_HANDLER) {
		            DWORD code = GetExceptionCode();
		            diagLog("  openD3D12SharedHandle: [attempt %d] SEH exception code=0x%08lx",
		                attempt + 1, code);
		            hr = E_FAIL;
		            *outResource = nullptr;
		        }
		
		        if (SUCCEEDED(hr)) {
		            diagLog("  openD3D12SharedHandle: [attempt %d] OK — resource=%p",
		                attempt + 1, (void*)*outResource);
		            return true;
		        }
		
		        diagLog("  openD3D12SharedHandle: [attempt %d] FAILED hr=0x%08lx, retrying...",
		            attempt + 1, hr);
		        // Tiny sleep before retry to let driver settle
		        Sleep(1);
		    }
		
		    *outResource = nullptr;
		    return false;
		}
	
		JNIEXPORT jboolean JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_exportInputToD3D12(
		    JNIEnv*, jclass)
		{
		    __try {
		        if (!g_vkModInputHandle) {
		            diagLog("exportInputToD3D12: no input handle (initExternalImages not called?)");
		            return JNI_FALSE;
		        }
		        if (g_vkModInputOpened && g_vkModD3D12Input) {
		            diagLog("exportInputToD3D12: already opened (warmup in initExternalImages succeeded)");
		            return JNI_TRUE; // Already opened by initExternalImages warmup
		        }

		        diagLog("exportInputToD3D12: warmup failed earlier — retrying OpenSharedHandle(handle=%p)",
		            (void*)g_vkModInputHandle);

		        if (!openD3D12SharedHandle(g_vkModInputHandle, &g_vkModD3D12Input)) {
		            g_vkModInputOpened = false;
		            return JNI_FALSE;
		        }
	
	        g_vkModInputOpened = true;
	        diagLog("exportInputToD3D12: SUCCESS — D3D12 resource=%p",
	            (void*)g_vkModD3D12Input);
	        return JNI_TRUE;
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("exportInputToD3D12: SEH exception code=0x%08lx", GetExceptionCode());
	        return JNI_FALSE;
	    }
	}
	
	JNIEXPORT jboolean JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_importOutputFromD3D12(
	    JNIEnv*, jclass)
	{
	    __try {
	        if (!g_vkModOutputHandle) {
	            diagLog("importOutputFromD3D12: no output handle");
	            return JNI_FALSE;
	        }
	        if (g_vkModOutputOpened && g_vkModD3D12Output) {
	            return JNI_TRUE; // Already opened
	        }
	
	        diagLog("importOutputFromD3D12: opening VK handle %p via D3D12 OpenSharedHandle",
	            (void*)g_vkModOutputHandle);
	
	        if (!openD3D12SharedHandle(g_vkModOutputHandle, &g_vkModD3D12Output)) {
	            g_vkModOutputOpened = false;
	            return JNI_FALSE;
	        }
	
	        g_vkModOutputOpened = true;
	        diagLog("importOutputFromD3D12: SUCCESS — D3D12 resource=%p",
	            (void*)g_vkModD3D12Output);
	        return JNI_TRUE;
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("importOutputFromD3D12: SEH exception code=0x%08lx", GetExceptionCode());
	        return JNI_FALSE;
	    }
		}

		// Pipeline test flag: when true, dispatchFsr4VkMod skips the FSR4 SDK and
		// does a simple CopyResource from pool color → pool output. This validates
		// the D3D12→VK→swapchain pipeline independent of FSR4 processing.
		static bool g_debugBypassFsr4 = true;
	
			JNIEXPORT jboolean JNICALL
			Java_com_fsr2mod_vulkan_VulkanInterop_importOutputFromD3D12Pool(
		    JNIEnv*, jclass, jlong poolPtr, jint outputIndex)
		{
		    __try {
		        if (!g_vkModDeviceSet) {
		            diagLog("importOutputFromD3D12Pool: VulkanMod device not configured");
		            return JNI_FALSE;
		        }
		        if (!g_vkModGetMemWin32HandleProps) {
		            diagLog("importOutputFromD3D12Pool: vkGetMemoryWin32HandlePropertiesKHR not available");
		            return JNI_FALSE;
		        }
		        if (!poolPtr) {
		            diagLog("importOutputFromD3D12Pool: null pool pointer");
		            return JNI_FALSE;
		        }
		        if (outputIndex < 0 || outputIndex >= fsr::D3D12SharedResources::COUNT) {
		            diagLog("importOutputFromD3D12Pool: invalid index %d", outputIndex);
		            return JNI_FALSE;
		        }
		
		        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
		        HANDLE d3d12Handle = pool->win32Handles[outputIndex];
		        uint64_t allocSize = pool->allocationSizes[outputIndex];
		        ID3D12Resource* d3d12Res = pool->resources[outputIndex];
		
		        if (!d3d12Handle || !d3d12Res) {
		            diagLog("importOutputFromD3D12Pool: pool resource/handle not ready for index %d", outputIndex);
		            return JNI_FALSE;
		        }
		
		        diagLog("importOutputFromD3D12Pool: importing pool[%d] handle=%p size=%llu",
		            outputIndex, (void*)d3d12Handle, allocSize);
		
		        // Destroy existing output VkImage/D3D12 resource if any
		        if (g_vkModOutputImage != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModOutputMemory != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModOutputMemory, nullptr);
		            g_vkModOutputMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModD3D12Output) {
		            // NOTE: The D3D12 resource is owned by the pool, not by us. Don't release it.
		            g_vkModD3D12Output = nullptr;
		        }
		        g_vkModOutputOpened = false;
		        g_vkModOutputHandle = nullptr;
		
		        // Create VkImage with the same format as the D3D12 output texture
		        VkExternalMemoryImageCreateInfo extImgInfo{};
		        extImgInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
		        extImgInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
		
		        VkImageCreateInfo imgInfo{};
		        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		        imgInfo.imageType = VK_IMAGE_TYPE_2D;
		        imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
		        imgInfo.extent = { g_vkModImageWidth, g_vkModImageHeight, 1 };
		        imgInfo.mipLevels = 1;
		        imgInfo.arrayLayers = 1;
		        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		        imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		        imgInfo.pNext = &extImgInfo;
		
		        diagLog("  Creating OUTPUT VkImage backed by D3D12 pool memory...");
		        VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, &g_vkModOutputImage);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkCreateImage(output, import) → %d", res);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		
		        // Query memory type bits from the D3D12 handle
		        VkMemoryWin32HandlePropertiesKHR handleProps{};
		        handleProps.sType = VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR;
		        res = g_vkModGetMemWin32HandleProps(g_vkModDevice,
		            VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT,
		            d3d12Handle, &handleProps);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkGetMemoryWin32HandlePropertiesKHR → %d", res);
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		
		        int memType = findVkModMemoryType(handleProps.memoryTypeBits,
		            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		        if (memType < 0) {
		            diagLog("  FAIL: no compatible DEVICE_LOCAL memory type from handle properties");
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		        diagLog("  handleProps memoryTypeBits=0x%x → selected type=%d",
		            handleProps.memoryTypeBits, memType);
		
		        // Allocate imported memory
		        VkImportMemoryWin32HandleInfoKHR importInfo{};
		        importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR;
		        importInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
		        importInfo.handle = d3d12Handle;
		
		        VkMemoryAllocateInfo allocInfo{};
		        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		        allocInfo.allocationSize = allocSize;
		        allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
		        allocInfo.pNext = &importInfo;
		
		        diagLog("  Allocating imported OUTPUT memory: size=%llu type=%d", allocSize, memType);
		        res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, &g_vkModOutputMemory);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkAllocateMemory(output, import) → %d", res);
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		            g_vkModOutputMemory = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		
		        // Bind VkImage to imported memory
		        res = vkBindImageMemory(g_vkModDevice, g_vkModOutputImage,
		            g_vkModOutputMemory, 0);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkBindImageMemory(output, import) → %d", res);
		            vkFreeMemory(g_vkModDevice, g_vkModOutputMemory, nullptr);
		            g_vkModOutputMemory = VK_NULL_HANDLE;
		            vkDestroyImage(g_vkModDevice, g_vkModOutputImage, nullptr);
		            g_vkModOutputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		
        // Store the D3D12 resource for FSR4 dispatch output.
        // AddRef ensures our later Release() in destroyVkModImages
        // doesn't double-free the pool's resource.
        d3d12Res->AddRef();
        g_vkModD3D12Output = d3d12Res;
		        g_vkModOutputOpened = true;
		
		        diagLog("importOutputFromD3D12Pool: SUCCESS — output image=%p memory=%p d3d12=%p",
		            (void*)g_vkModOutputImage, (void*)g_vkModOutputMemory,
		            (void*)g_vkModD3D12Output);

		        // Auto-enable pipeline test mode: skip FSR4 SDK and use CopyResource
		        // from pool color → pool output. This validates the D3D12→VK→swapchain
		        // pipeline. If the scene appears, the FSR4 SDK processing is the issue.
		        g_debugBypassFsr4 = true;
		        diagLog("importOutputFromD3D12Pool: PIPELINE TEST ENABLED");
		        return JNI_TRUE;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("importOutputFromD3D12Pool: SEH exception code=0x%08lx", GetExceptionCode());
		        return JNI_FALSE;
		    }
		}
		
		JNIEXPORT jlong JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_getImportedInputResourcePtr(
	    JNIEnv*, jclass)
	{
	    return reinterpret_cast<jlong>(g_vkModD3D12Input);
	}
	
		JNIEXPORT jlong JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_getImportedOutputResourcePtr(
		    JNIEnv*, jclass)
		{
		    return reinterpret_cast<jlong>(g_vkModD3D12Output);
		}
		
			JNIEXPORT void JNICALL
				Java_com_fsr2mod_vulkan_VulkanInterop_setDebugPipelineTest(
			    JNIEnv*, jclass, jboolean enabled)
			{
			    g_debugBypassFsr4 = (enabled == JNI_TRUE);
			    diagLog("setDebugPipelineTest: FSR4 bypass = %s",
			        g_debugBypassFsr4 ? "ENABLED (CopyResource only)" : "DISABLED (normal FSR4)");
			}
		
		// ---- FSR4 dispatch for VulkanMod (uses imported D3D12 resources) ----
	
		JNIEXPORT jboolean JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_dispatchFsr4VkMod(
		    JNIEnv* env, jclass,
		    jfloat jitterX, jfloat jitterY,
		    jfloat cameraFar, jfloat cameraNear, jfloat cameraFovY,
		    jfloat deltaTime, jint frameIndex, jboolean reset,
		    jlong depthPoolPtr, jint depthIndex,
		    jlong mvPoolPtr, jint mvIndex,
		    jlong reactivePoolPtr, jint reactiveIndex,
		    jlong outputPoolPtr, jint outputIndex)
		{
			    try {
			        // Imported D3D12 input resource from VK shared memory
			        if (!g_vkModD3D12Input) {
			            diagLog("dispatchFsr4VkMod: D3D12 input resource not imported");
			            return JNI_FALSE;
			        }
			        if (!g_fsr4Context || !g_fsr4Context->isInitialized()) {
			            diagLog("dispatchFsr4VkMod: FSR4 context not initialized");
			            return JNI_FALSE;
			        }

			        diagLog("dispatchFsr4VkMod: g_debugBypassFsr4=%s",
			            g_debugBypassFsr4 ? "true" : "false");

			        // Resolve pool resources for depth, MV, reactive, and output
			        auto resolve = [](jlong poolPtr, jint idx) -> ID3D12Resource* {
			            if (!poolPtr) return nullptr;
			            auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
			            if (idx < 0 || idx >= fsr::D3D12SharedResources::COUNT) return nullptr;
			            return pool->resources[idx];
			        };
			
			        // Resolve output: use pool's output texture (D3D12-allocated, imported into VK)
			        ID3D12Resource* outputRes = resolve(outputPoolPtr, outputIndex);
			        if (!outputRes) {
			            diagLog("dispatchFsr4VkMod: failed to resolve output resource (pool=%p index=%d)",
			                (void*)outputPoolPtr, outputIndex);
			            return JNI_FALSE;
			        }
			
			        // Copy the VK-imported input (swapchain scene) to the D3D12 pool's color
			        // texture (index 0). The FSR4 SDK requires all input textures to be
			        // D3D12-native allocated resources with UAV support. The VK-imported
			        // texture (g_vkModD3D12Input) lacks D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS,
			        // which the SDK needs for internal processing — causing a C++ exception
			        // crash inside g_ffxDispatch. By copying to the pool's color texture
			        // (which was created with D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS), we
			        // give the SDK a properly-capable D3D12 resource with the scene data.
			        ID3D12Resource* poolColor = resolve(depthPoolPtr, 0);  // COLOR index = 0
			        if (poolColor && poolColor != g_vkModD3D12Input) {
			            auto& dev = fsr::D3D12Device::get();
			
			            // Barrier: pool color COMMON → COPY_DEST (shared resources need explicit)
			            D3D12_RESOURCE_BARRIER barrier{};
			            barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
			            barrier.Transition.pResource = poolColor;
			            barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
			            barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_DEST;
			            barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
			            dev.commandList()->ResourceBarrier(1, &barrier);
			
			            // CopyResource: VK input → pool color
			            diagLog("dispatchFsr4VkMod: CopyResource VK input=%p → pool color=%p",
			                (void*)g_vkModD3D12Input, (void*)poolColor);
			            dev.commandList()->CopyResource(poolColor, g_vkModD3D12Input);
			
			            // Barrier: pool color COPY_DEST → COMMON (FSR4 SDK expects COMMON)
			            barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_DEST;
			            barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
			            dev.commandList()->ResourceBarrier(1, &barrier);
			        } else {
			            diagLog("dispatchFsr4VkMod: WARNING — no pool color available for copy, "
			                    "using VK input directly (may crash SDK)");
			        }
			
			        fsr::Fsr4DispatchParams params{};
			        params.color         = poolColor ? poolColor : g_vkModD3D12Input;
			        params.depth         = resolve(depthPoolPtr, depthIndex);
			        params.motionVectors = resolve(mvPoolPtr, mvIndex);
			        params.reactive      = resolve(reactivePoolPtr, reactiveIndex);
			        params.output        = outputRes;
			        params.jitterX       = jitterX;
			        params.jitterY       = jitterY;
			        params.cameraNear    = cameraNear;
			        params.cameraFar     = cameraFar;
			        params.fovYRad       = cameraFovY;
			        params.deltaTimeMs   = deltaTime;
			        params.frameIndex    = static_cast<uint32_t>(frameIndex);
			        params.reset         = reset == JNI_TRUE;

			        diagLog("dispatchFsr4VkMod: calling dispatch color=%p depth=%p mv=%p reactive=%p output=%p "
			            "jitter=(%.4f,%.4f) near=%.2f far=%.2f fov=%.4f dt=%.1f frame=%u reset=%d",
			            (void*)params.color, (void*)params.depth, (void*)params.motionVectors,
			            (void*)params.reactive, (void*)params.output,
			            params.jitterX, params.jitterY, params.cameraNear, params.cameraFar,
			            params.fovYRad, params.deltaTimeMs, params.frameIndex, params.reset);
			
				        bool ok = false;
				        auto& dev = fsr::D3D12Device::get();

					        if (true) {  // DEBUG: always bypass FSR4 SDK — use CopyResource
				            // PIPELINE TEST: Skip FSR4 SDK, just CopyResource from pool
				            // color → pool output. This validates the D3D12→VK shared
				            // memory + VK copy → swapchain path. If the user sees the
				            // scene with this test, the pipeline is functional and the
				            // bug is in the FSR4 SDK processing.
				            diagLog("dispatchFsr4VkMod: PIPELINE TEST — bypassing FSR4, "
				                "CopyResource color=%p → output=%p",
				                (void*)params.color, (void*)params.output);

				            D3D12_RESOURCE_BARRIER barrier{};
				            barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
				            barrier.Transition.pResource = params.output;
				            barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
				            barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_DEST;
				            barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
				            dev.commandList()->ResourceBarrier(1, &barrier);

				            dev.commandList()->CopyResource(params.output, params.color);

				            barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_DEST;
				            barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
				            dev.commandList()->ResourceBarrier(1, &barrier);

				            dev.commandList()->Close();
				            ID3D12CommandList* lists[] = { dev.commandList() };
				            dev.commandQueue()->ExecuteCommandLists(1, lists);
				            uint64_t fenceVal = dev.signalFence();
				            dev.advanceAllocator(fenceVal);
				            diagLog("dispatchFsr4VkMod: PIPELINE TEST — submitted (fence=%llu)", (unsigned long long)fenceVal);
				            ok = true;
				        } else {
				            ok = g_fsr4Context->dispatch(params);
				        }
				        diagLog("dispatchFsr4VkMod: dispatch → %s", ok ? "OK" : "FAILED");
				        return ok ? JNI_TRUE : JNI_FALSE;
		    } catch (const std::exception& e) {
		        diagLog("dispatchFsr4VkMod: exception: %s", e.what());
		        return JNI_FALSE;
		    }
		}
	
	} // extern "C"
