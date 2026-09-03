#include "vulkan_device.h"
#include "vulkan_memory.h"
#include "d3d12_device.h"
#include "d3d12_shared_resources.h"
#include "fsr4_context.h"
#include "fg4_context.h"
#include "diag.h"
#include "fsr4_vk_mvgen_spv.h"  // VK compute shader for FSR4 MV/reactive/depth generation (VulkanMod path)
#include <jni.h>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cstdlib>
#include <malloc.h>
#include <stdexcept>
#include <string>
#include <vector>
#include <algorithm>  // std::min (recordVkModFsr4ScaledCopy extent clamps)

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

namespace fsr { bool g_diagEnabled = false; }

// Diagnostics — writes to both file and stderr (appears in Minecraft console).
// Gated by fsr::g_diagEnabled: when diagnostics are off (default), diagLog is a
// single compare-and-return, so per-frame fopen/fprintf/fflush costs ZERO CPU.
// Java enables it via Fsr4Native.setDiagnosticsEnabled when debug mode is on.
static void diagLog(const char* fmt, ...) {
    if (!fsr::g_diagEnabled) return;
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

        // CRITICAL FIX (2026-08-05): drain the D3D12 queue BEFORE releasing the
        // pool's resources. A prior frame's dispatch (FSR4 SDK or the VulkanMod
        // pipeline-test CopyResource) may still be executing against these
        // textures; D3D12SharedResources::destroy() calls Release() on the
        // resources unconditionally, so releasing while in flight is a
        // use-after-free — the GPU writes into freed memory, and ntdll's heap
        // manager detects the corruption (STATUS_HEAP_CORRUPTION c0000374) at
        // the next big allocation (e.g. opening the settings screen triggers a
        // pool recreate).
        auto& dev = fsr::D3D12Device::get();
        // Do NOT call ensureInitialized() here: dispose() resets state_ to
        // UNINITIALIZED, so ensureInitialized() would RE-CREATE a disposed
        // device at teardown. Instead gate on the fence: lastCompletedFenceValue()
        // returns UINT64_MAX when the device/fence was never created (or was
        // disposed), which makes the wait condition false.
        uint64_t w1 = dev.currentAllocatorFenceValue();
        uint64_t w2 = dev.otherAllocatorFenceValue();
        uint64_t waitVal = w1 > w2 ? w1 : w2;
        uint64_t completed = dev.lastCompletedFenceValue();
        if (waitVal > 0 && completed < waitVal) {
            if (dev.waitForFenceValue(waitVal, 5000)) {
                diagLog("releaseFsr4Pool: drained D3D12 queue (fence=%llu)",
                    (unsigned long long)waitVal);
            } else {
                diagLog("releaseFsr4Pool: WARNING — D3D12 fence wait timed out "
                        "(val=%llu), destroying pool while GPU may still be in flight",
                    (unsigned long long)waitVal);
            }
        }

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

// Helper for dispatchFsr4 — contains ALL C++ objects (lambda, params struct)
// and the D3D12 pool pointer dereferences. Wrapped in SEH by the JNI entry
// below: an access violation (0xC0000005) inside the FSR4 SDK, the D3D12
// runtime, or the AMD driver during the pool dereference / allocator reset /
// ExecuteCommandLists now returns JNI_FALSE (Java's dispatch-failure suspension
// takes over) instead of killing the process. This is the crash class seen when
// the GPU is saturated (elytra + chunk streaming) and FSR4 re-dispatches with
// reset=true mid-episode: the old code only caught std::exception, which does
// NOT catch access violations.
static jboolean dispatchFsr4Helper(JNIEnv* env,
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
    // SEH around the WHOLE dispatch (not just g_ffxDispatch inside
    // Fsr4Context::dispatch): the pool deref, allocator Reset, and
    // ExecuteCommandLists can also fault when the GPU queue is deep.
    __try {
        return dispatchFsr4Helper(env,
            jitterX, jitterY, cameraFar, cameraNear, cameraFovY,
            deltaTime, frameIndex, reset,
            colorPoolPtr, colorIndex,
            depthPoolPtr, depthIndex,
            mvPoolPtr, mvIndex,
            reactivePoolPtr, reactiveIndex,
            outputPoolPtr, outputIndex);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fsr4Native] dispatchFsr4: SEH crash (code=0x%08lx) — "
                "FSR4 suspended for recovery (Java backoff + resume probe)",
                GetExceptionCode());
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_setDiagnosticsEnabled(
    JNIEnv*, jclass, jboolean on)
{
    fsr::g_diagEnabled = (on == JNI_TRUE);
}

// Bypass-state query (2026-08-30, "make FSR3 FG DX12 work with shaders").
// True once the FSR4 SDK's g_ffxDispatch threw with the FG context alive and
// the native layer switched to the bilinear fallback upscale. Java uses this
// (a) to NOT suspend FSR4 on that failure — the next dispatch succeeds via
// the bypass — and (b) to notify the user once that upscale quality is
// bilinear while FG stays active.
JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_isBypassActive(
    JNIEnv*, jclass)
{
    return fsr::Fsr4Context::g_bypassFsr4 ? JNI_TRUE : JNI_FALSE;
}

// PRE-ARMED BILINEAR BYPASS (2026-08-30, "still just frozen"): on an AMD
// iGPU the FSR4 SDK's ffxDispatch deterministically throws 0xe06d7363 AND
// removes the D3D12 device (DXGI_ERROR_DEVICE_REMOVED 0x887a0005 — diag:
// "createCommandAllocator[0] FAILED hr=0x887a0005" on the first crash) once
// an FG ffxCreateContext exists. By the time the post-crash "arm bypass" ran,
// the device was already gone, so every FSR4+FG session collapsed to FSR1.
// Java pre-arms this flag BEFORE the first dispatch when FG is active on an
// AMD iGPU, so g_ffxDispatch is never called — dispatch goes straight to the
// bilinear upscale on the healthy device and FG interpolates normally.
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_armBypass(
    JNIEnv*, jclass)
{
    fsr::Fsr4Context::g_bypassFsr4 = true;
    diagLog("[Fsr4Context] PRE-ARMED bilinear bypass — FG active on AMD iGPU: "
            "the FSR4 SDK upscale cannot coexist with an FG context on this "
            "GPU (deterministic dispatch crash + device removal). Dispatch uses "
            "the bilinear upscale; Frame Generation stays ACTIVE.");
}

// Queries whether the selected D3D12 adapter is an AMD integrated GPU (iGPU).
// Safe to call after FSR4 init (the device is created by then); returns false
// before device creation or off-device. Java gates the FG bilinear pre-arm on
// this so discrete AMD GPUs keep the real FSR4 SDK upscale.
JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_isAmdIntegratedGpu(
    JNIEnv*, jclass)
{
    return fsr::D3D12Device::get().isIntegratedAmd() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_waitIdle(
    JNIEnv*, jclass)
{
    // SEH around finish(): the fence wait is the only cross-API sync point
    // after dispatch, and a corrupted D3D12 fence/queue (from a prior
    // mid-saturation fault) would crash here otherwise.
    __try {
        if (g_fsr4Context) {
            long t0 = diagTimeMs();
            g_fsr4Context->finish();
            long dt = diagTimeMs() - t0;
            // OPTIMIZATION: rate-limit the per-frame diagnostic — diagLog does
            // fopen/fclose + stderr flush on EVERY call, which is measurable CPU
            // waste when called once per frame (the log grew 53k+ lines of
            // "waitIdle" spam). Log only when the wait is unusually slow (>= 6ms)
            // or every 128th frame (keeps a heartbeat for diagnostics).
            static int waitIdleCounter = 0;
            if (dt >= 6 || (++waitIdleCounter & 127) == 0) {
                diagLog("waitIdle: FSR4 finish OK (took %ldms)", dt);
            }
        } else {
            diagLog("waitIdle: no FSR4 context");
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fsr4Native] waitIdle: SEH crash (code=0x%08lx)", GetExceptionCode());
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
	
		// Forward declaration — destroyVkModMvGenPipeline is defined LATER in this
		// translation unit at GLOBAL scope (the unnamed namespace for the VKMod
		// globals closes before the MV-gen pipeline section; its members remain
		// visible there via the implicit using-directive). destroyVkModImages
		// runs at teardown and must free the MV-gen pipeline + staging images
		// too, so it calls it before the definition is reached — hence the
		// declaration here, OUTSIDE the unnamed namespace, matching the global
		// definition (a declaration inside the namespace would name a different
		// function and MSVC would report C2129 "declared but not defined").
		static void destroyVkModMvGenPipeline();
		// Forward declaration — createVkModDepthView is defined AFTER
		// ensureVkModMvGenPipeline in this translation unit, but the pipeline
		// builder needs it to recreate the framebuffer-depth view that
		// destroyVkModMvGenPipeline wipes during the first build (root-cause fix
		// 2026-08-06). Same global-scope placement as destroyVkModMvGenPipeline.
		static VkImageView createVkModDepthView(VkImage image, VkFormat fmt);
		// Same pattern for the COLOR source view (binding 4, 2026-08-06).
		static VkImageView createVkModColorView(VkImage image, VkFormat fmt);

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
		
		    // Imported D3D12 pool textures (DEPTH=1, MV=2, REACTIVE=3) aliased as
		    // VkImages. The FSR4 VK MV-gen compute shader (fsr4_vk_mvgen.comp)
		    // writes depth=0.5 constant + motion vectors + reactive mask into these
		    // storage images, making the REAL FSR4 SDK dispatch usable under
		    // VulkanMod (previously the pool[1..3] inputs were never filled and the
		    // pipeline-test CopyResource bypass was left on permanently).
		    VkImage g_vkModDepthImage = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModDepthMemory = VK_NULL_HANDLE;
		    VkImage g_vkModMvImage = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvMemory = VK_NULL_HANDLE;
		    VkImage g_vkModReactiveImage = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModReactiveMemory = VK_NULL_HANDLE;
		    ID3D12Resource* g_vkModD3D12Depth = nullptr;
		    ID3D12Resource* g_vkModD3D12Mv = nullptr;
		    ID3D12Resource* g_vkModD3D12Reactive = nullptr;
		    bool g_vkModDepthOpened = false;
		    bool g_vkModMvOpened = false;
		    bool g_vkModReactiveOpened = false;
		    VkImageView g_vkModDepthView = VK_NULL_HANDLE;
		    VkImageView g_vkModMvView = VK_NULL_HANDLE;
		    VkImageView g_vkModReactiveView = VK_NULL_HANDLE;

		    // STAGING images for the MV-gen compute (2026-08-05 — the actual
		    // "no edge smoothing" root cause): the AMD driver does NOT persist
		    // compute imageStore writes into D3D12-imported external-memory
		    // images (the same documented GL bug — AGENTS.md). The VK MV-gen
		    // wrote depth=0.5 / MV / reactive via raw imageStore into the
		    // imported pool images; those writes never reached the D3D12-visible
		    // memory, so the FSR4 SDK read depth≈0 / MV≈0 / reactive≈0,
		    // classified every pixel as sky/disoccluded, and discarded ALL
		    // history every frame → zero temporal accumulation → the output was
		    // a pure spatial upscale (jittered input = shake, no edge smoothing
		    // — and identical results with jitter=0,0, proving it was never a
		    // jitter issue). Fix mirrors the GL path: MV-gen writes into these
		    // VK-OWNED staging images (reliable), then vkCmdCopyImage staging →
		    // imported (transfer copies DO persist — Copy1's vkCmdCopyImage into
		    // the imported color image is how the scene reaches the SDK).
		    VkImage g_vkModMvGenStageDepth = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvGenStageDepthMem = VK_NULL_HANDLE;
		    VkImageView g_vkModMvGenStageDepthView = VK_NULL_HANDLE;
		    VkImage g_vkModMvGenStageMv = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvGenStageMvMem = VK_NULL_HANDLE;
		    VkImageView g_vkModMvGenStageMvView = VK_NULL_HANDLE;
		    VkImage g_vkModMvGenStageReactive = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvGenStageReactiveMem = VK_NULL_HANDLE;
		    VkImageView g_vkModMvGenStageReactiveView = VK_NULL_HANDLE;

		    // REAL-DEPTH source (2026-08-06): the scaled framebuffer's depth
		    // attachment, bound to MV-gen binding 3 (combined image sampler).
		    // The mixin calls setVkModDepthSource each frame (cheap); the view is
		    // (re)created only when the image changes (framebuffer recreated on
		    // resize/scale change). image==0 → bind the 1x1 dummy (value 0.5) →
		    // the legacy constant-depth (ndcZ=0) behavior.
		    VkSampler g_vkModMvGenDepthSampler = VK_NULL_HANDLE;
		    VkImage g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvGenDummyDepthMem = VK_NULL_HANDLE;
		    VkImageView g_vkModMvGenDummyDepthView = VK_NULL_HANDLE;
		    VkImage g_vkModDepthSrcImage = VK_NULL_HANDLE;      // framebuffer depth VkImage
		    VkImageView g_vkModDepthSrcView = VK_NULL_HANDLE;   // its view (re)created on change
		    VkFormat g_vkModDepthSrcFmt = VK_FORMAT_UNDEFINED; // its format (from getDepthFormat())
		    // The image ACTUALLY written to binding 3 (review fix 2026-08-06):
		    // ensureVkModMvGenPipeline rebinds the descriptor set on pool rebuild
		    // and resets this to NULL — setVkModDepthSource compares against it
		    // (NOT against g_vkModDepthSrcImage) so a rebuild can't silently
		    // strand the real-depth binding on the dummy (the "constant depth
		    // returns after disable→enable" regression).
		    VkImage g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;
		    // Depth image dimensions (2026-08-06): set by setVkModDepthSource so
		    // the DIRECT probe samples correct rows/offsets even when the depth
		    // image size differs from the pool dims (the mc main-target depth is
		    // NATIVE-res 1920x1201 while the pool/scene is 1248x781).
		    int g_vkModDepthSrcW = 0;
		    int g_vkModDepthSrcH = 0;

		    // COLOR source for MV-gen binding 4 (thin-detail/cutout reactive,
		    // 2026-08-06): the scene color VkImage (the scaled framebuffer's
		    // color attachment in render-scale mode, else the swapchain image),
		    // bound by the mixin via setVkModColorSource. Mirrors the depth
		    // source exactly: view (re)created only on image change, bound-image
		    // tracking so a descriptor-set rebuild can't strand it on the dummy.
		    VkImage g_vkModColorSrcImage = VK_NULL_HANDLE;
		    VkImageView g_vkModColorSrcView = VK_NULL_HANDLE;
		    VkFormat g_vkModColorSrcFmt = VK_FORMAT_UNDEFINED;
		    VkImage g_vkModColorSrcBoundImage = VK_NULL_HANDLE;
		    int g_vkModColorSrcW = 0;
		    int g_vkModColorSrcH = 0;
		    // 1x1 mid-gray (0.5,0.5,0.5,1.0) RGBA8 dummy for binding 4 when no
		    // scene color is available — the detector reads zero contrast and
		    // never fires (graceful degradation instead of an unbound descriptor).
		    VkImage g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModMvGenDummyColorMem = VK_NULL_HANDLE;
		    VkImageView g_vkModMvGenDummyColorView = VK_NULL_HANDLE;

		    // One-shot REAL-DEPTH readback probe (2026-08-06): copies a 16x16
		    // block of the STAGING depth from the PREVIOUS frame's MV-gen into a
		    // host-visible buffer and logs 9 sample values — proves the depth
		    // convention (non-reversed: terrain≈0.001, sky≈1.0) actually reaches
		    // the SDK. A reversed/misaligned depth makes the SDK reject ALL
		    // history (spatial output = "still jagged water") — this turns that
		    // failure mode into a one-line log instead of another blind cycle.
		    VkBuffer g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModDepthReadbackMem = VK_NULL_HANDLE;
		    bool g_vkModDepthReadbackDone = false;
		    // Set when the 16x16 copy is recorded; consumed (map + log) on the
		    // NEXT recordVkModMvGen call once the previous frame's fence signals.
		    bool g_vkModDepthReadbackPending = false;

		    // DIRECT real-depth probe (2026-08-06): copies the FRAMEBUFFER's REAL
		    // depth attachment (g_vkModDepthSrcImage — the very image the mixin
		    // binds to binding 3), BYPASSING the shader. v1 (one-shot, center
		    // 16x16) proved the attachment holds 1.0 (far/sky) while the shader
		    // output 0.5 (dummy) — i.e. the depth image has content; the shader's
		    // sampling was broken. v2 (2026-08-06): samples 3 rows (top quarter,
		    // center, bottom quarter) every 60th frame, 5 recordings total, so one
		    // launch shows sky-vs-terrain depth variation AND verifies the staging
		    // probe now matches (real values = fix worked). Each recording = 3
		    // copies of a 16x4 block (DEPTH aspect) into one host buffer (offsets
		    // 0/256/512), consumed next frame after the frame fence.
		    VkBuffer g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		    VkDeviceMemory g_vkModDepthDirectMem = VK_NULL_HANDLE;
		    bool g_vkModDepthDirectDone = false;
		    bool g_vkModDepthDirectPending = false;
		    int g_vkModDepthDirectCount = 0;

		    // MV-gen compute pipeline (created lazily on first recordVkModMvGen).
		    VkShaderModule g_vkModMvGenShader = VK_NULL_HANDLE;
		    VkDescriptorSetLayout g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		    VkPipelineLayout g_vkModMvGenPipeLayout = VK_NULL_HANDLE;
		    VkPipeline g_vkModMvGenPipeline = VK_NULL_HANDLE;
		    VkDescriptorPool g_vkModMvGenDescPool = VK_NULL_HANDLE;
		    VkDescriptorSet g_vkModMvGenDescSet = VK_NULL_HANDLE;
		    bool g_vkModMvGenReady = false;
		    // Matrices pushed per-frame (invCurrVP + prevVP) — set from Java before
		    // recordVkModMvGen is called by the main-pass mixin. Single float[32]
		    // (NOT separate globals): vkCmdPushConstants reads CONTIGUOUS bytes,
		    // and C++ does not guarantee that two separate globals are adjacent in
		    // memory (review fix 2026-08-05 — the two-array version silently
		    // pushed garbage prevVP if the compiler placed any padding or
		    // reordered the globals, corrupting MVs → ghosting). ONE array:
		    //   [0..15]  invCurrVP (offset 0)
		    //   [16..31] prevVP    (offset 64)
		    //   [32]     uCamMotion (offset 128) — added by the CAM-MOTION PLUMB
		    //   [33]     uCamRotDelta (offset 132)   (2026-08-08 14:24; RECOVERED
		    //             2026-08-18 from AGENTS.md — the post-plumb source was
		    //             lost in a working-tree deletion; push range grew 128→136).
		    // Matches the shader's push-constant block (2 mat4 + 2 floats).
		    float g_vkModMvGenPush[34];
		    bool g_vkModMvGenMatricesSet = false;
		
		    // INPUT (render-size) dims: used for the pool[0] COLOR / [1] DEPTH /
		    // [2] MV / [3] REACTIVE VkImage imports + the MV-gen dispatch. In
		    // render-scale mode these are the SCALED framebuffer dims (e.g.
		    // 480x300 at qualityScale 0.25) — the FSR4 SDK upscales render→display.
		    uint32_t g_vkModImageWidth = 0;
		    uint32_t g_vkModImageHeight = 0;
		    // OUTPUT (display-size) dims: used ONLY for the pool[4] OUTPUT VkImage
		    // import (and the FSR4 output copies). Split from the input dims so
		    // render-scale mode (render 480x300 → display 1920x1201) imports the
		    // output at its REAL display-size allocation — importing a display-size
		    // D3D12 resource with render-size extents would alias out of bounds
		    // (2026-08-05, render-scale + FSR4 combination).
		    uint32_t g_vkModOutputWidth = 0;
		    uint32_t g_vkModOutputHeight = 0;
		
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
		
		    // Frame fence from VulkanMod's Renderer (per-frame submission fence).
		    // The main-pass mixin stores the CURRENT frame's fence; the next frame's
		    // onRenderBegin waits on it (bounded) instead of draining the whole
		    // graphics queue with vkQueueWaitIdle.
		    VkFence g_vkModFrameFence = VK_NULL_HANDLE;
		    // One-shot 33ms extended re-wait throttle per timeout episode.
		    bool g_vkModFrameFenceTimeoutLogged = false;
		
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
		        // CRITICAL FIX (2026-08-05): drain VulkanMod's graphics queue BEFORE
		        // destroying the external VkImages / releasing the D3D12 keep-alive
		        // refs. The previous frame's recordFrameCopyOps (Copy1/Copy2) were
		        // submitted to VulkanMod's queue and may still reference these
		        // images; destroying them while in flight is a VK use-after-free that
		        // (on this AMD driver's fragile external-memory path) can corrupt
		        // process memory — the same c0000374 heap-corruption class seen when
		        // opening the settings screen triggers a pool recreate.
		        if (g_vkModGraphicsQueue != VK_NULL_HANDLE) {
		            vkQueueWaitIdle(g_vkModGraphicsQueue);
		        }
		        // Destroy VK images + free memory FIRST (they reference the imported
		        // external memory), THEN drop the D3D12 keep-alive refs — releasing
		        // the D3D12 refs first can free the backing heap while the VkImage
		        // objects are still bound to it.
		        g_vkModInputOpened = false;
		        g_vkModOutputOpened = false;
		
		        // Tear down the MV-gen compute pipeline + staging images (its
		        // descriptor set references the image views below; the
		        // pipeline/layouts reference the shader). destroyVkModMvGenPipeline
		        // also frees the VK-owned staging images — do this BEFORE the
		        // imported external images are destroyed below (the descriptor set
		        // no longer references imported views, but the pipeline must be
		        // gone before the shader module it references).
		        destroyVkModMvGenPipeline();
		        g_vkModMvGenMatricesSet = false;

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
		        // MV-gen imported pool textures (depth/MV/reactive)
		        if (g_vkModDepthImage != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModDepthImage, nullptr);
		            g_vkModDepthImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModDepthMemory != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModDepthMemory, nullptr);
		            g_vkModDepthMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModMvImage != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModMvImage, nullptr);
		            g_vkModMvImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModMvMemory != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModMvMemory, nullptr);
		            g_vkModMvMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModReactiveImage != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModReactiveImage, nullptr);
		            g_vkModReactiveImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModReactiveMemory != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModReactiveMemory, nullptr);
		            g_vkModReactiveMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModInputHandle) { CloseHandle(g_vkModInputHandle); g_vkModInputHandle = nullptr; }
		        if (g_vkModOutputHandle) { CloseHandle(g_vkModOutputHandle); g_vkModOutputHandle = nullptr; }
		
		        // Now drop the D3D12 keep-alive refs (after the VK objects above).
		        if (g_vkModD3D12Input) { g_vkModD3D12Input->Release(); g_vkModD3D12Input = nullptr; }
		        if (g_vkModD3D12Output) { g_vkModD3D12Output->Release(); g_vkModD3D12Output = nullptr; }
		        if (g_vkModD3D12Depth) { g_vkModD3D12Depth->Release(); g_vkModD3D12Depth = nullptr; }
		        if (g_vkModD3D12Mv) { g_vkModD3D12Mv->Release(); g_vkModD3D12Mv = nullptr; }
		        if (g_vkModD3D12Reactive) { g_vkModD3D12Reactive->Release(); g_vkModD3D12Reactive = nullptr; }
		        g_vkModDepthOpened = false;
		        g_vkModMvOpened = false;
		        g_vkModReactiveOpened = false;
		
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
		    JNIEnv*, jclass, jint renderW, jint renderH, jint dispW, jint dispH)
		{
		    __try {
		        if (!g_vkModDeviceSet || !g_vkModGetMemWin32Handle) {
		            diagLog("initExternalImages: VulkanMod device not configured");
		            return JNI_FALSE;
		        }
		
		        // Destroy existing images if any
		        destroyVkModImages();
		
		        // RENDER dims = FSR4 input size (render-scale mode: the scaled
		        // framebuffer dims; otherwise display). DISPLAY dims = FSR4 output
		        // size (the swapchain). Split so render-scale + FSR4 works: the SDK
		        // upscales render→display (2026-08-05).
		        g_vkModImageWidth = static_cast<uint32_t>(renderW);
		        g_vkModImageHeight = static_cast<uint32_t>(renderH);
		        g_vkModOutputWidth = static_cast<uint32_t>(dispW);
		        g_vkModOutputHeight = static_cast<uint32_t>(dispH);
		
		        diagLog("initExternalImages: render %dx%d -> display %dx%d",
		            (int)g_vkModImageWidth, (int)g_vkModImageHeight,
		            (int)g_vkModOutputWidth, (int)g_vkModOutputHeight);
		
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
		
		        diagLog("initExternalImages: SUCCESS (render %dx%d -> display %dx%d)",
		            (int)g_vkModImageWidth, (int)g_vkModImageHeight,
		            (int)g_vkModOutputWidth, (int)g_vkModOutputHeight);
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

	        // OPTIMIZATION: rate-limit per-frame diagnostic — diagLog does
	        // fopen/fclose + stderr flush per call. Log only every 128th frame
	        // (keeps a heartbeat) or when diagnostics are disabled (no-op).
	        static int copyOpsCounter = 0;
	        if ((++copyOpsCounter & 127) == 1) {
	            diagLog("recordFrameCopyOps: Copy1 swapchain=%p->input=%p (%dx%d)",
	                (void*)swapImg, (void*)g_vkModInputImage, width, height);
	        }
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
		        // preservation of image contents."
		        //
		        // For subsequent frames (GENERAL → TRANSFER_SRC), the image was left in GENERAL
		        // by the previous frame's barrier 7, and D3D12 has written new data to it since.
		        // We use VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT for BOTH
		        // src and dst access masks with VK_PIPELINE_STAGE_ALL_COMMANDS_BIT for both
		        // src and dst stages — a full memory barrier. This ensures cross-API writes
		        // (D3D12 → shared memory → VK) are made visible before the TRANSFER_READ.
		        //
		        // Note: on the first frame, oldLayout=UNDEFINED discards prior content. But
		        // the D3D12 dispatch (pipeline test) runs BEFORE recordFrameCopyOps each frame,
		        // so on frame 2+ the output has valid D3D12-written data and oldLayout=GENERAL.
		        barrier.image = g_vkModOutputImage;
		        barrier.oldLayout = g_vkModImagesLayoutInitialized
		            ? VK_IMAGE_LAYOUT_GENERAL
		            : VK_IMAGE_LAYOUT_UNDEFINED;
		        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        // Full memory barrier: all prior writes (including external/D3D12) must be
		        // visible before the TRANSFER_READ. Using ALL_COMMANDS_BIT ensures the driver
		        // waits for ALL pending work including cross-API synchronization points.
		        barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
		        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        if ((copyOpsCounter & 127) == 1) {
	            diagLog("recordFrameCopyOps: Copy2 output=%p->swapchain=%p",
	            (void*)g_vkModOutputImage, (void*)swapImg);
	        }
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

	// ====================================================================
	// VulkanMod render-scale blit (POC, 2026-08-05)
	// ====================================================================
	// When config.vulkanModRenderScale is active, the main-pass mixin swaps
	// VulkanMod's main framebuffer for a scaled-size one (the scene renders at
	// qualityScale). This function records a vkCmdBlitImage (LINEAR = bilinear
	// upscale, the FSR1-quality fallback) from the scaled color attachment to
	// the swapchain image, plus all layout transitions. It replaces the D3D12
	// copy path entirely for that mode (CopyResource can't resize, and the
	// swapchain no longer holds the scene at native res anyway).
	//
	// Layout flow (recorded into VulkanMod's still-recording command buffer):
	//   src: COLOR_ATTACHMENT_OPTIMAL → TRANSFER_SRC_OPTIMAL
	//   dst: UNDEFINED → TRANSFER_DST_OPTIMAL        (full overwrite, discard OK)
	//   blit LINEAR
	//   dst: TRANSFER_DST_OPTIMAL → PRESENT_SRC_KHR  (required before present)
	//   src: TRANSFER_SRC_OPTIMAL → COLOR_ATTACHMENT_OPTIMAL (restore for next frame)
	JNIEXPORT void JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_recordRenderScaleBlit(
	    JNIEnv*, jclass,
	    jlong cmdBuf, jlong srcImage, jint srcW, jint srcH,
	    jlong dstImage, jint dstW, jint dstH)
	{
	    __try {
	        if (!g_vkModDeviceSet) {
	            diagLog("recordRenderScaleBlit: VulkanMod device not set — skipping");
	            return;
	        }
	        if (cmdBuf == 0 || srcImage == 0 || dstImage == 0) return;
	        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;

	        VkCommandBuffer cb = reinterpret_cast<VkCommandBuffer>(cmdBuf);
	        VkImage src = reinterpret_cast<VkImage>(srcImage);
	        VkImage dst = reinterpret_cast<VkImage>(dstImage);

	        VkImageMemoryBarrier barrier{};
	        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
	        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
	        barrier.subresourceRange.baseMipLevel = 0;
	        barrier.subresourceRange.levelCount = 1;
	        barrier.subresourceRange.baseArrayLayer = 0;
	        barrier.subresourceRange.layerCount = 1;
	        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
	        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;

	        // 1. src: COLOR_ATTACHMENT_OPTIMAL → TRANSFER_SRC_OPTIMAL
	        barrier.image = src;
	        barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
	        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
	        barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
	        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            VK_PIPELINE_STAGE_TRANSFER_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // 2. dst: UNDEFINED → TRANSFER_DST_OPTIMAL (full overwrite — discard OK)
	        barrier.image = dst;
	        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
	        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
	        barrier.srcAccessMask = 0;
	        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            VK_PIPELINE_STAGE_TRANSFER_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // 3. bilinear upscale blit
	        VkImageBlit blit{};
	        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
	        blit.srcSubresource.mipLevel = 0;
	        blit.srcSubresource.baseArrayLayer = 0;
	        blit.srcSubresource.layerCount = 1;
	        blit.srcOffsets[0] = {0, 0, 0};
	        blit.srcOffsets[1] = {srcW, srcH, 1};
	        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
	        blit.dstSubresource.mipLevel = 0;
	        blit.dstSubresource.baseArrayLayer = 0;
	        blit.dstSubresource.layerCount = 1;
	        blit.dstOffsets[0] = {0, 0, 0};
	        blit.dstOffsets[1] = {dstW, dstH, 1};
	        vkCmdBlitImage(cb,
	            src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
	            dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
	            1, &blit, VK_FILTER_LINEAR);

	        // 4. dst: TRANSFER_DST_OPTIMAL → PRESENT_SRC_KHR (present requirement)
	        barrier.image = dst;
	        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
	        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
	        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
	        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_TRANSFER_BIT,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        // 5. src: TRANSFER_SRC_OPTIMAL → COLOR_ATTACHMENT_OPTIMAL (next frame's render pass)
	        barrier.image = src;
	        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
	        barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
	        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
	        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
	        vkCmdPipelineBarrier(cb,
	            VK_PIPELINE_STAGE_TRANSFER_BIT,
	            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
	            0, 0, nullptr, 0, nullptr, 1, &barrier);

	        static int renderScaleBlitCounter = 0;
	        if ((++renderScaleBlitCounter & 127) == 1) {
	            diagLog("recordRenderScaleBlit: %dx%d -> %dx%d (LINEAR)",
	                srcW, srcH, dstW, dstH);
	        }
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("recordRenderScaleBlit: SEH exception");
	    }
	}

		// ====================================================================
		// Render-scale + FSR4 combined path (2026-08-05)
		// ====================================================================
		// When BOTH config.vulkanModRenderScale AND FSR4 are active, the scene
		// renders into the SCALED main framebuffer (e.g. 480x300 at 0.25), NOT the
		// swapchain. Instead of the bilinear blit (which "looked like FSR1" — it
		// literally was a LINEAR blit, worse than FSR1), record the real FSR4
		// pipeline: Copy1 scaled-fb → pool[0] input (render-size), then the FSR4
		// SDK upscales render→display (dispatch runs async in onRenderBegin), then
		// Copy2 pool[4] output (display-size) → swapchain. This is exactly what
		// FSR4 is FOR — low-res render, high-quality temporal upscale.
		//
		// Barriers mirror recordFrameCopyOps (input/output pool transitions) with
		// the src = scaled fb color attachment (COLOR_ATTACHMENT_OPTIMAL, like
		// recordRenderScaleBlit's src) and SEPARATE render/display extents.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_recordVkModFsr4ScaledCopy(
		    JNIEnv*, jclass,
		    jlong cmdBuf, jlong srcImage, jint srcW, jint srcH,
		    jlong dstImage, jint dstW, jint dstH,
		    jboolean includeCopy2)
		{
		    __try {
		        if (!g_vkModDeviceSet) {
		            diagLog("recordVkModFsr4ScaledCopy: VulkanMod device not set — skipping");
		            return;
		        }
		        if (g_vkModInputImage == VK_NULL_HANDLE || g_vkModOutputImage == VK_NULL_HANDLE) {
		            diagLog("recordVkModFsr4ScaledCopy: external images not initialized");
		            return;
		        }
		        // includeCopy2=false: POOL-JUST-RECREATED frame. The dispatch gate in
		        // onRenderBegin is blocked for one frame (fsr4InputPopulated cleared),
		        // so pool[4] output has NEVER been written by a dispatch — Copy2 would
		        // present undefined content. Record Copy1 (scene → pool input) + the
		        // input barrier ONLY so the NEXT frame's dispatch has a fresh scene,
		        // and leave the output/swapchain untouched (the mixin presents via the
		        // bilinear blit instead, then sets fsr4InputPopulated=true).
		        if (cmdBuf == 0 || srcImage == 0 || dstImage == 0) return;
		        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;

		        VkCommandBuffer cb = reinterpret_cast<VkCommandBuffer>(cmdBuf);
		        VkImage src = reinterpret_cast<VkImage>(srcImage);
		        VkImage dst = reinterpret_cast<VkImage>(dstImage);

		        VkImageMemoryBarrier barrier{};
		        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		        barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		        barrier.subresourceRange.baseMipLevel = 0;
		        barrier.subresourceRange.levelCount = 1;
		        barrier.subresourceRange.baseArrayLayer = 0;
		        barrier.subresourceRange.layerCount = 1;
		        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;

		        // 1. src (scaled fb): COLOR_ATTACHMENT_OPTIMAL → TRANSFER_SRC
		        barrier.image = src;
		        barrier.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
		        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        barrier.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
		        barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        // 2. input (pool[0], render-size): [UNDEFINED|GENERAL] → TRANSFER_DST
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

		        // Copy1: src (scaled fb, render-size) → input (render-size)
		        // REVIEW FIX (2026-08-05): CLAMP the extent to the actual pool input
		        // image dims — during the scale-change recreate window the framebuffer
		        // can land at the new scale BEFORE the FSR4 pool recreates (they run on
		        // separate debounced schedules), so srcW/srcH can transiently exceed
		        // g_vkModImageWidth/Height → an out-of-bounds vkCmdCopyImage
		        // destination. Clamping per-axis to the input image size keeps the copy
		        // valid (the FSR4 SDK reads whatever region is present; the pool
		        // recreates to match within the debounce window).
		        VkImageCopy copy1{};
		        copy1.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		        copy1.srcSubresource.mipLevel = 0;
		        copy1.srcSubresource.baseArrayLayer = 0;
		        copy1.srcSubresource.layerCount = 1;
		        copy1.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		        copy1.dstSubresource.mipLevel = 0;
		        copy1.dstSubresource.baseArrayLayer = 0;
		        copy1.dstSubresource.layerCount = 1;
		        copy1.extent.width = std::min<uint32_t>((uint32_t)srcW, g_vkModImageWidth);
		        copy1.extent.height = std::min<uint32_t>((uint32_t)srcH, g_vkModImageHeight);
		        copy1.extent.depth = 1;
		        // NOTE: no 0-extent early return here — a zero-extent
		        // vkCmdCopyImage is a valid no-op, and returning after the
		        // barriers above would leave input stuck in TRANSFER_DST and src
		        // in TRANSFER_SRC (broken layout tracking for the rest of the
		        // frame). The clamp makes the extent exactly 0 only if the image
		        // dims are 0, which is already guarded (images null → returned
		        // earlier). Keep the copy recorded even at 0 extent.
		        vkCmdCopyImage(cb,
		            src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		            g_vkModInputImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		            1, &copy1);

		        // 3. input: TRANSFER_DST → GENERAL (make visible to D3D12 FSR4 read)
		        barrier.image = g_vkModInputImage;
		        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
		        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_MEMORY_READ_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        if (includeCopy2) {
		        // 4. output (pool[4], display-size): [UNDEFINED|GENERAL] → TRANSFER_SRC
		        // Full memory barrier (MEMORY_READ|WRITE, ALL_COMMANDS both stages) so
		        // the D3D12 FSR4 output (written via shared memory) is visible before
		        // the VK TRANSFER_READ — same pattern as recordFrameCopyOps barrier 4.
		        barrier.image = g_vkModOutputImage;
		        barrier.oldLayout = g_vkModImagesLayoutInitialized
		            ? VK_IMAGE_LAYOUT_GENERAL
		            : VK_IMAGE_LAYOUT_UNDEFINED;
		        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
		        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        // Copy2: output (display-size) → dst (swapchain, display-size)
		        // Clamped to the output image dims (same rationale as Copy1).
		        VkImageCopy copy2{};
		        copy2.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		        copy2.srcSubresource.mipLevel = 0;
		        copy2.srcSubresource.baseArrayLayer = 0;
		        copy2.srcSubresource.layerCount = 1;
		        copy2.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		        copy2.dstSubresource.mipLevel = 0;
		        copy2.dstSubresource.baseArrayLayer = 0;
		        copy2.dstSubresource.layerCount = 1;
		        copy2.extent.width = std::min<uint32_t>((uint32_t)dstW, g_vkModOutputWidth);
		        copy2.extent.height = std::min<uint32_t>((uint32_t)dstH, g_vkModOutputHeight);
		        copy2.extent.depth = 1;
		        // NOTE: no 0-extent early return (see Copy1 comment above).
		        vkCmdCopyImage(cb,
		            g_vkModOutputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		            dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		            1, &copy2);

		        // 5. dst (swapchain): TRANSFER_DST → PRESENT_SRC
		        barrier.image = dst;
		        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
		        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        // 6. output: TRANSFER_SRC → GENERAL
		        barrier.image = g_vkModOutputImage;
		        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
		        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);
		        }

		        // 7. src (scaled fb): TRANSFER_SRC → COLOR_ATTACHMENT_OPTIMAL (next
		        //    frame's render pass)
		        barrier.image = src;
		        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		        barrier.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
		        barrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		        barrier.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &barrier);

		        g_vkModImagesLayoutInitialized = true;

		        static int scaledCopyCounter = 0;
		        if ((++scaledCopyCounter & 127) == 1) {
		            diagLog("recordVkModFsr4ScaledCopy: Copy1 %dx%d -> input, Copy2 output -> %dx%d (FSR4 upscale)",
		                srcW, srcH, dstW, dstH);
		        }
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("recordVkModFsr4ScaledCopy: SEH exception");
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
		            // OPTIMIZATION: rate-limit per-frame diagnostic — log only on
		            // slow waits (>= 6ms) or every 128th frame.
		            static int waitCopyCounter = 0;
		            if (res != VK_SUCCESS || dt >= 6 || (++waitCopyCounter & 127) == 0) {
		                diagLog("waitForFrameCopy: vkQueueWaitIdle %s (took %ldms)",
		                    res == VK_SUCCESS ? "OK" : "failed", dt);
		            }
		        } else {
		            diagLog("waitForFrameCopy: no graphics queue — skipping wait");
		        }
	
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("waitForFrameCopy: SEH exception");
	    }
	}
	
	JNIEXPORT void JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_storeFrameFence(
	    JNIEnv*, jclass, jlong fencePtr)
	{
	    __try {
	        g_vkModFrameFence = reinterpret_cast<VkFence>(fencePtr);
	        g_vkModFrameFenceTimeoutLogged = false;
	        if (fencePtr != 0) {
	            diagLog("storeFrameFence: fence=%p", (void*)g_vkModFrameFence);
	        }
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	    }
	}
	
	// Bounded wait for the previous frame's VK copy ops. Replaces the full
	// vkQueueWaitIdle drain with a targeted wait on VulkanMod's per-frame fence
	// (stored by the main-pass mixin): only THIS frame's submission (scene render
	// + our Copy1/Copy2) must complete before the D3D12 dispatch reads the input
	// via shared memory. Returns JNI_FALSE on timeout so the caller can skip the
	// dispatch instead of stalling the CPU on an unbounded queue drain.
	JNIEXPORT jboolean JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_waitForFrameCopyBounded(
	    JNIEnv*, jclass)
	{
	    __try {
	        if (g_vkModGraphicsQueue == VK_NULL_HANDLE) return JNI_FALSE;
	        long t0 = diagTimeMs();
	
	        if (g_vkModFrameFence != VK_NULL_HANDLE && g_vkModDevice != VK_NULL_HANDLE) {
	            // TARGETED wait: 8ms cap; glClientWait-style — vkWaitForFences returns
	            // EARLY on a short queue, so this is NOT a fixed 8ms block. Only the
	            // frame whose copies feed the D3D12 dispatch needs to finish.
	            VkResult r = vkWaitForFences(g_vkModDevice, 1, &g_vkModFrameFence,
	                VK_TRUE, 8000000ull);
	            if (r == VK_TIMEOUT && !g_vkModFrameFenceTimeoutLogged) {
	                // FIRST timeout of an episode: one 33ms extended re-wait absorbs
	                // a deep queue (world join / Voxy meshing) without stalling every
	                // frame. Later timeouts skip cheaply (caller skips the dispatch).
	                g_vkModFrameFenceTimeoutLogged = true;
	                r = vkWaitForFences(g_vkModDevice, 1, &g_vkModFrameFence,
	                    VK_TRUE, 33000000ull);
	            } else if (r == VK_SUCCESS) {
	                g_vkModFrameFenceTimeoutLogged = false;  // episode over
	            }
	            bool ready = (r == VK_SUCCESS);
	            long dt = diagTimeMs() - t0;
	            static int waitCopyCounter = 0;
	            if (!ready || dt >= 6 || (++waitCopyCounter & 127) == 0) {
	                diagLog("waitForFrameCopy: fence wait %s (took %ldms)",
	                    ready ? "OK" : "TIMEOUT", dt);
	            }
	            return ready ? JNI_TRUE : JNI_FALSE;
	        }
	
	        // Fallback: no fence stored (mixin reflection failed) — old full drain.
	        VkResult res = vkQueueWaitIdle(g_vkModGraphicsQueue);
	        long dt = diagTimeMs() - t0;
	        static int waitCopyCounter2 = 0;
	        if (res != VK_SUCCESS || dt >= 6 || (++waitCopyCounter2 & 127) == 0) {
	            diagLog("waitForFrameCopy: vkQueueWaitIdle fallback %s (took %ldms)",
	                res == VK_SUCCESS ? "OK" : "failed", dt);
	        }
	        return (res == VK_SUCCESS) ? JNI_TRUE : JNI_FALSE;
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("waitForFrameCopyBounded: SEH exception");
	        return JNI_FALSE;
	    }
	}
	
	// Waits for the D3D12 FSR4 dispatch (submitted earlier in onRenderBegin) to
	// finish. Called from the main-pass mixin right before recordFrameCopyOps
	// records Copy2 (output -> swapchain) — by then the dispatch has overlapped
	// the scene render, so this wait is ~0ms instead of a mid-frame stall. The
	// wait targets the highest fence value ever signaled on either round-robin
	// allocator (all submitted D3D12 work), NOT a full device drain.
	JNIEXPORT jboolean JNICALL
	Java_com_fsr2mod_vulkan_VulkanInterop_waitForD3D12Output(
	    JNIEnv*, jclass)
	{
	    __try {
	        auto& dev = fsr::D3D12Device::get();
	        uint64_t w1 = dev.currentAllocatorFenceValue();
	        uint64_t w2 = dev.otherAllocatorFenceValue();
	        uint64_t waitVal = w1 > w2 ? w1 : w2;
	        if (waitVal > 0 && dev.lastCompletedFenceValue() < waitVal) {
	            long t0 = diagTimeMs();
	            bool ok = dev.waitForFenceValue(waitVal, 33);  // 33ms cap (codebase philosophy: never a long CPU stall)
	            long dt = diagTimeMs() - t0;
	            static int d3dWaitCounter = 0;
	            if (!ok || dt >= 6 || (++d3dWaitCounter & 127) == 0) {
	                diagLog("waitForD3D12Output: %s (fence=%llu, %ldms)",
	                    ok ? "OK" : "TIMEOUT", (unsigned long long)waitVal, dt);
	            }
	            // REVIEW FIX (2026-08-05): on timeout the FSR4 output is NOT
	            // ready — the mixin must NOT record Copy2 (output -> swapchain)
	            // against a half-written texture. Return false so the mixin skips
	            // the D3D12 copies entirely this frame; the swapchain image still
	            // holds the raw scene (mainFramebuffer == swapChain in this path),
	            // so the frame presents natively — the VKMod equivalent of the GL
	            // path's graceful single-present on sync timeout.
	            return ok ? JNI_TRUE : JNI_FALSE;
	        }
	        return JNI_TRUE;
	    } __except (EXCEPTION_EXECUTE_HANDLER) {
	        diagLog("waitForD3D12Output: SEH exception");
	        // Conservative: treat as NOT ready — skip the copies, present raw scene.
	        return JNI_FALSE;
	    }	}

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
		Java_com_fsr2mod_vulkan_VulkanInterop_importInputFromD3D12Pool(
		    JNIEnv*, jclass, jlong poolPtr, jint colorIndex)
		{
		    __try {
		        if (!g_vkModDeviceSet) {
		            diagLog("importInputFromD3D12Pool: VulkanMod device not configured");
		            return JNI_FALSE;
		        }
		        if (!g_vkModGetMemWin32HandleProps) {
		            diagLog("importInputFromD3D12Pool: vkGetMemoryWin32HandlePropertiesKHR not available");
		            return JNI_FALSE;
		        }
		        if (!poolPtr) {
		            diagLog("importInputFromD3D12Pool: null pool pointer");
		            return JNI_FALSE;
		        }
		        if (colorIndex < 0 || colorIndex >= fsr::D3D12SharedResources::COUNT) {
		            diagLog("importInputFromD3D12Pool: invalid index %d", (int)colorIndex);
		            return JNI_FALSE;
		        }

		        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
		        if (!pool->resources[colorIndex] || !pool->win32Handles[colorIndex]) {
		            diagLog("importInputFromD3D12Pool: pool resource/handle not ready for index %d",
		                (int)colorIndex);
		            return JNI_FALSE;
		        }

		        HANDLE d3d12Handle = pool->win32Handles[colorIndex];
		        uint64_t allocSize = pool->allocationSizes[colorIndex];

		        diagLog("importInputFromD3D12Pool: importing pool[%d] handle=%p size=%llu",
		            (int)colorIndex, (void*)d3d12Handle, (unsigned long long)allocSize);

		        // Destroy any existing input VkImage (from a previous import)
		        if (g_vkModInputImage != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		        }
		        if (g_vkModInputMemory != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, g_vkModInputMemory, nullptr);
		            g_vkModInputMemory = VK_NULL_HANDLE;
		        }
		        if (g_vkModD3D12Input) {
		            g_vkModD3D12Input->Release();
		            g_vkModD3D12Input = nullptr;
		        }
		        g_vkModInputOpened = false;

		        diagLog("  Creating INPUT VkImage backed by D3D12 pool memory...");

		        // Create VkImage matching the pool's color texture format
		        VkExternalMemoryImageCreateInfo extImgInfo{};
		        extImgInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
		        extImgInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

		        VkImageCreateInfo imgInfo{};
		        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		        imgInfo.imageType = VK_IMAGE_TYPE_2D;
		        imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;  // pool format
		        imgInfo.extent = { g_vkModImageWidth, g_vkModImageHeight, 1 };
		        imgInfo.mipLevels = 1;
		        imgInfo.arrayLayers = 1;
		        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		        imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		        imgInfo.pNext = &extImgInfo;

		        VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, &g_vkModInputImage);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkCreateImage(input, import) → %d", res);
		            g_vkModInputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        // Query memory handle properties to find compatible VK memory type
		        VkMemoryWin32HandlePropertiesKHR handleProps{};
		        handleProps.sType = VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR;
		        res = g_vkModGetMemWin32HandleProps(g_vkModDevice,
		            VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT, d3d12Handle, &handleProps);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkGetMemoryWin32HandlePropertiesKHR → %d", res);
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        // Find a DEVICE_LOCAL memory type compatible with the handle
		        int memType = findVkModMemoryType(handleProps.memoryTypeBits,
		            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		        if (memType < 0) {
		            diagLog("  FAIL: no compatible DEVICE_LOCAL memory type from handle properties");
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }
		        diagLog("  handleProps memoryTypeBits=0x%x → selected type=%d",
		            (unsigned)handleProps.memoryTypeBits, memType);

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

		        diagLog("  Allocating imported INPUT memory: size=%llu type=%d",
		            (unsigned long long)allocSize, memType);
		        res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, &g_vkModInputMemory);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkAllocateMemory(input, import) → %d", res);
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		            g_vkModInputMemory = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        // Bind VkImage to imported memory
		        res = vkBindImageMemory(g_vkModDevice, g_vkModInputImage, g_vkModInputMemory, 0);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkBindImageMemory(input, import) → %d", res);
		            vkFreeMemory(g_vkModDevice, g_vkModInputMemory, nullptr);
		            g_vkModInputMemory = VK_NULL_HANDLE;
		            vkDestroyImage(g_vkModDevice, g_vkModInputImage, nullptr);
		            g_vkModInputImage = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        // Store the D3D12 resource for FSR4 dispatch input.
		        // AddRef ensures our later Release() in destroyVkModImages
		        // doesn't double-free the pool's resource.
		        pool->resources[colorIndex]->AddRef();
		        g_vkModD3D12Input = pool->resources[colorIndex];
		        g_vkModInputOpened = true;

		        diagLog("importInputFromD3D12Pool: SUCCESS — input image=%p memory=%p d3d12=%p",
		            (void*)g_vkModInputImage, (void*)g_vkModInputMemory,
		            (void*)g_vkModD3D12Input);
		        return JNI_TRUE;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("importInputFromD3D12Pool: SEH exception code=0x%08lx", GetExceptionCode());
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
		// Default is false so the real FSR4 SDK dispatch runs by default.
		static bool g_debugBypassFsr4 = false;
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
		            // Balance the AddRef() taken by the previous importOutputFromD3D12Pool
		            // call. The resource itself is owned by the pool; our reference is only
		            // a keep-alive AddRef, so the matching Release() here is required —
		            // dropping the pointer without releasing leaked one refcount per
		            // recreate (resource never freed -> D3D12 memory leak).
		            g_vkModD3D12Output->Release();
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
		        // DISPLAY dims — the pool[4] OUTPUT texture is display-sized (the
		        // swapchain is display-sized). In render-scale mode the render dims
		        // differ (g_vkModImageWidth/Height = scaled), so using them here
		        // would import a display-size D3D12 allocation with render-size
		        // extents → out-of-bounds aliasing on Copy2 (2026-08-05).
		        imgInfo.extent = { g_vkModOutputWidth, g_vkModOutputHeight, 1 };
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

		        // NOTE: the pipeline-test bypass (g_debugBypassFsr4) is NO LONGER
		        // auto-enabled here (2026-08-05). It was a debug scaffold that
		        // replaced the real FSR4 SDK dispatch with a 1:1 CopyResource —
		        // which made "FSR4" under VulkanMod look like FSR1 (it was a plain
		        // passthrough; the user's exact report). The real SDK dispatch now
		        // has valid inputs (the VK MV-gen compute shader fills pool[1..3]
		        // depth/MV/reactive via recordVkModMvGen), so the bypass must stay
		        // OFF by default. setDebugPipelineTest(true) is only for debugging.
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
		
		// ====================================================================
		// FSR4 REAL INPUT PATH (2026-08-05): VK compute MV-gen + depth fill
		// ====================================================================
		// Under VulkanMod there is NO GL context, so the GL-path compute shaders
		// (MV_GEN_CS / copyDepthToShared) that fill the FSR4 pool's depth/MV/
		// reactive textures cannot run — which is why the pipeline-test bypass
		// (CopyResource) was left on and "FSR4" under VulkanMod looked like FSR1:
		// it was a 1:1 passthrough, the real SDK never ran.
		//
		// This section imports pool[1] DEPTH (R32F), pool[2] MV (RG16F) and
		// pool[3] REACTIVE (R8) as VkImages aliasing the D3D12 shared memory, and
		// records a compute dispatch (fsr4_vk_mvgen.comp, compiled to SPIR-V at
		// build time) that writes depth=0.5 + motion vectors + reactive mask into
		// them — the same data the GL path generates, computed on the VK queue so
		// it runs inside VulkanMod's command buffer. The real FSR4 SDK dispatch
		// (dispatchFsr4VkMod bypass OFF) then reads valid inputs.

		// Create an image view for a storage image (used by the compute shader).
		static VkImageView createVkModImageView(VkImage image, VkFormat format) {
		    VkImageViewCreateInfo viewInfo{};
		    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		    viewInfo.image = image;
		    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
		    viewInfo.format = format;
		    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		    viewInfo.subresourceRange.baseMipLevel = 0;
		    viewInfo.subresourceRange.levelCount = 1;
		    viewInfo.subresourceRange.baseArrayLayer = 0;
		    viewInfo.subresourceRange.layerCount = 1;
		    VkImageView view = VK_NULL_HANDLE;
		    VkResult res = vkCreateImageView(g_vkModDevice, &viewInfo, nullptr, &view);
		    return (res == VK_SUCCESS) ? view : VK_NULL_HANDLE;
		}

		// Creates a VK-OWNED staging image (device-local, STORAGE | TRANSFER_SRC)
		// at render dims. Used as the MV-gen write target (compute imageStore into
		// VK-owned memory is reliable — see the staging globals comment: raw
		// imageStore into the D3D12-imported images does NOT persist on this
		// AMD driver, which is the actual "no edge smoothing" root cause). The
		// content is then vkCmdCopyImage'd into the imported image (transfer
		// copies DO persist).
		static bool createVkModMvGenStageImage(VkFormat fmt,
		    VkImage* outImg, VkDeviceMemory* outMem, VkImageView* outView) {
		    if (g_vkModImageWidth == 0 || g_vkModImageHeight == 0) {
		        diagLog("createVkModMvGenStageImage: zero dims");
		        return false;
		    }
		    VkImageCreateInfo imgInfo{};
		    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		    imgInfo.imageType = VK_IMAGE_TYPE_2D;
		    imgInfo.format = fmt;
		    imgInfo.extent = { g_vkModImageWidth, g_vkModImageHeight, 1 };
		    imgInfo.mipLevels = 1;
		    imgInfo.arrayLayers = 1;
		    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		    imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
		    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		    VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, outImg);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenStageImage: vkCreateImage → %d", res);
		        return false;
		    }
		    VkMemoryRequirements reqs;
		    vkGetImageMemoryRequirements(g_vkModDevice, *outImg, &reqs);
		    int memType = findVkModMemoryType(reqs.memoryTypeBits,
		        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		    if (memType < 0) {
		        diagLog("createVkModMvGenStageImage: no device-local memory type");
		        vkDestroyImage(g_vkModDevice, *outImg, nullptr);
		        *outImg = VK_NULL_HANDLE;
		        return false;
		    }
		    VkMemoryAllocateInfo allocInfo{};
		    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		    allocInfo.allocationSize = reqs.size;
		    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
		    res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, outMem);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenStageImage: vkAllocateMemory → %d", res);
		        vkDestroyImage(g_vkModDevice, *outImg, nullptr);
		        *outImg = VK_NULL_HANDLE;
		        return false;
		    }
		    res = vkBindImageMemory(g_vkModDevice, *outImg, *outMem, 0);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenStageImage: vkBindImageMemory → %d", res);
		        vkFreeMemory(g_vkModDevice, *outMem, nullptr);
		        *outMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, *outImg, nullptr);
		        *outImg = VK_NULL_HANDLE;
		        return false;
		    }
		    *outView = createVkModImageView(*outImg, fmt);
		    if (*outView == VK_NULL_HANDLE) {
		        diagLog("createVkModMvGenStageImage: image view failed");
		        vkFreeMemory(g_vkModDevice, *outMem, nullptr);
		        *outMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, *outImg, nullptr);
		        *outImg = VK_NULL_HANDLE;
		        return false;
		    }
		    return true;
		}

		// 1x1 R32F device image filled with 0.5 — the FALLBACK for MV-gen binding 3
		// (real depth sampler) when no framebuffer depth is available. Sampling it
		// anywhere yields 0.5 → ndcZ=0 → legacy constant-depth behavior, so the
		// real-depth shader path degrades gracefully (no unbound-descriptor UB).
		// Self-contained: creates its own temp command pool + fence for the clear.
		static bool createVkModMvGenDummyDepth() {
		    if (g_vkModDevice == VK_NULL_HANDLE) return false;
		    VkImageCreateInfo imgInfo{};
		    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		    imgInfo.imageType = VK_IMAGE_TYPE_2D;
		    imgInfo.format = VK_FORMAT_R32_SFLOAT;
		    imgInfo.extent = { 1, 1, 1 };
		    imgInfo.mipLevels = 1;
		    imgInfo.arrayLayers = 1;
		    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		    VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, &g_vkModMvGenDummyDepth);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyDepth: vkCreateImage → %d", res);
		        return false;
		    }
		    VkMemoryRequirements reqs;
		    vkGetImageMemoryRequirements(g_vkModDevice, g_vkModMvGenDummyDepth, &reqs);
		    int memType = findVkModMemoryType(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		    if (memType < 0) {
		        diagLog("createVkModMvGenDummyDepth: no device-local memory type");
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyDepth, nullptr);
		        g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		        return false;
		    }
		    VkMemoryAllocateInfo allocInfo{};
		    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		    allocInfo.allocationSize = reqs.size;
		    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
		    res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, &g_vkModMvGenDummyDepthMem);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyDepth: vkAllocateMemory → %d", res);
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyDepth, nullptr);
		        g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		        return false;
		    }
		    res = vkBindImageMemory(g_vkModDevice, g_vkModMvGenDummyDepth, g_vkModMvGenDummyDepthMem, 0);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyDepth: vkBindImageMemory → %d", res);
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyDepthMem, nullptr);
		        g_vkModMvGenDummyDepthMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyDepth, nullptr);
		        g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		        return false;
		    }
		    g_vkModMvGenDummyDepthView = createVkModImageView(g_vkModMvGenDummyDepth, VK_FORMAT_R32_SFLOAT);
		    if (g_vkModMvGenDummyDepthView == VK_NULL_HANDLE) {
		        diagLog("createVkModMvGenDummyDepth: image view failed");
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyDepthMem, nullptr);
		        g_vkModMvGenDummyDepthMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyDepth, nullptr);
		        g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		        return false;
		    }
		    // One-shot clear: UNDEFINED → TRANSFER_DST, clear 0.5, → SHADER_READ_ONLY.
		    VkCommandPool tmpPool = VK_NULL_HANDLE;
		    VkCommandBuffer tmpCb = VK_NULL_HANDLE;
		    VkFence tmpFence = VK_NULL_HANDLE;
		    VkCommandPoolCreateInfo poolInfo{};
		    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
		    poolInfo.queueFamilyIndex = g_vkModQueueFamilyIndex;
		    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
		    bool ok = false;
		    do {
		        if (vkCreateCommandPool(g_vkModDevice, &poolInfo, nullptr, &tmpPool) != VK_SUCCESS) break;
		        VkCommandBufferAllocateInfo cbInfo{};
		        cbInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
		        cbInfo.commandPool = tmpPool;
		        cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
		        cbInfo.commandBufferCount = 1;
		        if (vkAllocateCommandBuffers(g_vkModDevice, &cbInfo, &tmpCb) != VK_SUCCESS) break;
		        VkCommandBufferBeginInfo beginInfo{};
		        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
		        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
		        if (vkBeginCommandBuffer(tmpCb, &beginInfo) != VK_SUCCESS) break;
		        VkImageMemoryBarrier bar[2]{};
		        bar[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		        bar[0].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		        bar[0].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        bar[0].srcAccessMask = 0;
		        bar[0].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        bar[0].image = g_vkModMvGenDummyDepth;
		        bar[0].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        bar[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		        bar[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        bar[1].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		        bar[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        bar[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
		        bar[1].image = g_vkModMvGenDummyDepth;
		        bar[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        vkCmdPipelineBarrier(tmpCb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &bar[0]);
		        VkClearColorValue clear{};
		        clear.float32[0] = 0.5f;
		        VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        vkCmdClearColorImage(tmpCb, g_vkModMvGenDummyDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
		        vkCmdPipelineBarrier(tmpCb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &bar[1]);
		        if (vkEndCommandBuffer(tmpCb) != VK_SUCCESS) break;
		        VkFenceCreateInfo fenceInfo{};
		        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
		        if (vkCreateFence(g_vkModDevice, &fenceInfo, nullptr, &tmpFence) != VK_SUCCESS) break;
		        VkSubmitInfo submitInfo{};
		        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		        submitInfo.commandBufferCount = 1;
		        submitInfo.pCommandBuffers = &tmpCb;
		        if (vkQueueSubmit(g_vkModGraphicsQueue, 1, &submitInfo, tmpFence) != VK_SUCCESS) break;
		        if (vkWaitForFences(g_vkModDevice, 1, &tmpFence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) break;
		        ok = true;
		    } while (false);
		    if (tmpFence != VK_NULL_HANDLE) vkDestroyFence(g_vkModDevice, tmpFence, nullptr);
		    if (tmpPool != VK_NULL_HANDLE) vkDestroyCommandPool(g_vkModDevice, tmpPool, nullptr);
		    if (!ok) {
		        diagLog("createVkModMvGenDummyDepth: clear failed");
		        return false;
		    }
		    return true;
		}

		// 1x1 RGBA8 mid-gray (0.5,0.5,0.5,1.0) dummy for MV-gen binding 4
		// (scene color, 2026-08-06). When no real scene color is bound the
		// thin-detail detector reads a constant color → zero alpha/contrast →
		// never fires, degrading gracefully instead of reading an unbound
		// descriptor. Mirrors createVkModMvGenDummyDepth (one-shot clear via a
		// transient command buffer).
		static bool createVkModMvGenDummyColor() {
		    if (g_vkModDevice == VK_NULL_HANDLE) return false;
		    VkImageCreateInfo imgInfo{};
		    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		    imgInfo.imageType = VK_IMAGE_TYPE_2D;
		    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
		    imgInfo.extent = { 1, 1, 1 };
		    imgInfo.mipLevels = 1;
		    imgInfo.arrayLayers = 1;
		    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		    VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, &g_vkModMvGenDummyColor);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyColor: vkCreateImage → %d", res);
		        return false;
		    }
		    VkMemoryRequirements reqs;
		    vkGetImageMemoryRequirements(g_vkModDevice, g_vkModMvGenDummyColor, &reqs);
		    int memType = findVkModMemoryType(reqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		    if (memType < 0) {
		        diagLog("createVkModMvGenDummyColor: no device-local memory type");
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyColor, nullptr);
		        g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		        return false;
		    }
		    VkMemoryAllocateInfo allocInfo{};
		    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		    allocInfo.allocationSize = reqs.size;
		    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
		    res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, &g_vkModMvGenDummyColorMem);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyColor: vkAllocateMemory → %d", res);
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyColor, nullptr);
		        g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		        return false;
		    }
		    res = vkBindImageMemory(g_vkModDevice, g_vkModMvGenDummyColor, g_vkModMvGenDummyColorMem, 0);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModMvGenDummyColor: vkBindImageMemory → %d", res);
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyColorMem, nullptr);
		        g_vkModMvGenDummyColorMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyColor, nullptr);
		        g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		        return false;
		    }
		    g_vkModMvGenDummyColorView = createVkModImageView(g_vkModMvGenDummyColor, VK_FORMAT_R8G8B8A8_UNORM);
		    if (g_vkModMvGenDummyColorView == VK_NULL_HANDLE) {
		        diagLog("createVkModMvGenDummyColor: image view failed");
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyColorMem, nullptr);
		        g_vkModMvGenDummyColorMem = VK_NULL_HANDLE;
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyColor, nullptr);
		        g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		        return false;
		    }
		    // One-shot clear: UNDEFINED → TRANSFER_DST, clear (0.5,0.5,0.5,1.0),
		    // → SHADER_READ_ONLY. Mirrors the dummy-depth clear exactly.
		    VkCommandPool tmpPool = VK_NULL_HANDLE;
		    VkCommandBuffer tmpCb = VK_NULL_HANDLE;
		    VkFence tmpFence = VK_NULL_HANDLE;
		    VkCommandPoolCreateInfo poolInfo{};
		    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
		    poolInfo.queueFamilyIndex = g_vkModQueueFamilyIndex;
		    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
		    bool ok = false;
		    do {
		        if (vkCreateCommandPool(g_vkModDevice, &poolInfo, nullptr, &tmpPool) != VK_SUCCESS) break;
		        VkCommandBufferAllocateInfo cbInfo{};
		        cbInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
		        cbInfo.commandPool = tmpPool;
		        cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
		        cbInfo.commandBufferCount = 1;
		        if (vkAllocateCommandBuffers(g_vkModDevice, &cbInfo, &tmpCb) != VK_SUCCESS) break;
		        VkCommandBufferBeginInfo beginInfo{};
		        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
		        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
		        if (vkBeginCommandBuffer(tmpCb, &beginInfo) != VK_SUCCESS) break;
		        VkImageMemoryBarrier bar[2]{};
		        bar[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		        bar[0].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		        bar[0].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        bar[0].srcAccessMask = 0;
		        bar[0].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        bar[0].image = g_vkModMvGenDummyColor;
		        bar[0].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        bar[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		        bar[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		        bar[1].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		        bar[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        bar[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
		        bar[1].image = g_vkModMvGenDummyColor;
		        bar[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        vkCmdPipelineBarrier(tmpCb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &bar[0]);
		        VkClearColorValue clear{};
		        clear.float32[0] = 0.5f;
		        clear.float32[1] = 0.5f;
		        clear.float32[2] = 0.5f;
		        clear.float32[3] = 1.0f;
		        VkImageSubresourceRange range = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		        vkCmdClearColorImage(tmpCb, g_vkModMvGenDummyColor, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
		        vkCmdPipelineBarrier(tmpCb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		            0, 0, nullptr, 0, nullptr, 1, &bar[1]);
		        if (vkEndCommandBuffer(tmpCb) != VK_SUCCESS) break;
		        VkFenceCreateInfo fenceInfo{};
		        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
		        if (vkCreateFence(g_vkModDevice, &fenceInfo, nullptr, &tmpFence) != VK_SUCCESS) break;
		        VkSubmitInfo submitInfo{};
		        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
		        submitInfo.commandBufferCount = 1;
		        submitInfo.pCommandBuffers = &tmpCb;
		        if (vkQueueSubmit(g_vkModGraphicsQueue, 1, &submitInfo, tmpFence) != VK_SUCCESS) break;
		        if (vkWaitForFences(g_vkModDevice, 1, &tmpFence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) break;
		        ok = true;
		    } while (false);
		    if (tmpFence != VK_NULL_HANDLE) vkDestroyFence(g_vkModDevice, tmpFence, nullptr);
		    if (tmpPool != VK_NULL_HANDLE) vkDestroyCommandPool(g_vkModDevice, tmpPool, nullptr);
		    if (!ok) {
		        diagLog("createVkModMvGenDummyColor: clear failed");
		        return false;
		    }
		    return true;
		}

		// Lazily create the MV-gen compute pipeline + descriptor set. Called from
		// recordVkModMvGen on first use (after all three pool textures are
		// imported). Recreated on pool recreate (Java re-imports → images change).
		static void destroyVkModMvGenPipeline() {
		    if (g_vkModDevice == VK_NULL_HANDLE) return;
		    if (g_vkModMvGenDescPool != VK_NULL_HANDLE) {
		        vkDestroyDescriptorPool(g_vkModDevice, g_vkModMvGenDescPool, nullptr);
		        g_vkModMvGenDescPool = VK_NULL_HANDLE;
		        g_vkModMvGenDescSet = VK_NULL_HANDLE;  // freed with its pool
		    }
		    if (g_vkModMvGenPipeline != VK_NULL_HANDLE) {
		        vkDestroyPipeline(g_vkModDevice, g_vkModMvGenPipeline, nullptr);
		        g_vkModMvGenPipeline = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenPipeLayout != VK_NULL_HANDLE) {
		        vkDestroyPipelineLayout(g_vkModDevice, g_vkModMvGenPipeLayout, nullptr);
		        g_vkModMvGenPipeLayout = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDescLayout != VK_NULL_HANDLE) {
		        vkDestroyDescriptorSetLayout(g_vkModDevice, g_vkModMvGenDescLayout, nullptr);
		        g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenShader != VK_NULL_HANDLE) {
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		    }
		    // STAGING images (VK-owned MV-gen write targets) — views first, then
		    // images, then memory (safe order, matches the teardown convention).
		    VkImageView stageViews[3] = { g_vkModMvGenStageDepthView, g_vkModMvGenStageMvView,
		        g_vkModMvGenStageReactiveView };
		    VkImage stageImgs[3] = { g_vkModMvGenStageDepth, g_vkModMvGenStageMv,
		        g_vkModMvGenStageReactive };
		    VkDeviceMemory stageMems[3] = { g_vkModMvGenStageDepthMem, g_vkModMvGenStageMvMem,
		        g_vkModMvGenStageReactiveMem };
		    for (int i = 0; i < 3; i++) {
		        if (stageViews[i] != VK_NULL_HANDLE) {
		            vkDestroyImageView(g_vkModDevice, stageViews[i], nullptr);
		        }
		        if (stageImgs[i] != VK_NULL_HANDLE) {
		            vkDestroyImage(g_vkModDevice, stageImgs[i], nullptr);
		        }
		        if (stageMems[i] != VK_NULL_HANDLE) {
		            vkFreeMemory(g_vkModDevice, stageMems[i], nullptr);
		        }
		    }
		    g_vkModMvGenStageDepthView = g_vkModMvGenStageMvView = g_vkModMvGenStageReactiveView = VK_NULL_HANDLE;
		    g_vkModMvGenStageDepth = g_vkModMvGenStageMv = g_vkModMvGenStageReactive = VK_NULL_HANDLE;
		    g_vkModMvGenStageDepthMem = g_vkModMvGenStageMvMem = g_vkModMvGenStageReactiveMem = VK_NULL_HANDLE;
		    // REAL-DEPTH resources (2026-08-06): sampler, 1x1 dummy, framebuffer
		    // depth view. The depth SOURCE image itself is owned by VulkanMod's
		    // Framebuffer — we only destroy the view we created for it.
		    if (g_vkModMvGenDepthSampler != VK_NULL_HANDLE) {
		        vkDestroySampler(g_vkModDevice, g_vkModMvGenDepthSampler, nullptr);
		        g_vkModMvGenDepthSampler = VK_NULL_HANDLE;
		    }
		    if (g_vkModDepthSrcView != VK_NULL_HANDLE) {
		        vkDestroyImageView(g_vkModDevice, g_vkModDepthSrcView, nullptr);
		        g_vkModDepthSrcView = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDummyDepthView != VK_NULL_HANDLE) {
		        vkDestroyImageView(g_vkModDevice, g_vkModMvGenDummyDepthView, nullptr);
		        g_vkModMvGenDummyDepthView = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDummyDepth != VK_NULL_HANDLE) {
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyDepth, nullptr);
		        g_vkModMvGenDummyDepth = VK_NULL_HANDLE;
		    }
		    // COLOR-source resources (binding 4, 2026-08-06): the scene color
		    // view we created (the image itself is owned by VulkanMod's
		    // Framebuffer/swapchain) + the 1x1 mid-gray dummy (view → image →
		    // memory, matching the staging teardown order).
		    if (g_vkModColorSrcView != VK_NULL_HANDLE) {
		        vkDestroyImageView(g_vkModDevice, g_vkModColorSrcView, nullptr);
		        g_vkModColorSrcView = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDummyColorView != VK_NULL_HANDLE) {
		        vkDestroyImageView(g_vkModDevice, g_vkModMvGenDummyColorView, nullptr);
		        g_vkModMvGenDummyColorView = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDummyColor != VK_NULL_HANDLE) {
		        vkDestroyImage(g_vkModDevice, g_vkModMvGenDummyColor, nullptr);
		        g_vkModMvGenDummyColor = VK_NULL_HANDLE;
		    }
		    if (g_vkModMvGenDummyColorMem != VK_NULL_HANDLE) {
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyColorMem, nullptr);
		        g_vkModMvGenDummyColorMem = VK_NULL_HANDLE;
		    }
		    g_vkModColorSrcImage = VK_NULL_HANDLE;
		    g_vkModColorSrcFmt = VK_FORMAT_UNDEFINED;
		    g_vkModColorSrcBoundImage = VK_NULL_HANDLE;
		    g_vkModColorSrcW = 0;
		    g_vkModColorSrcH = 0;
		    // One-shot depth-readback probe resources (2026-08-06).
		    if (g_vkModDepthReadbackBuf != VK_NULL_HANDLE) {
		        vkDestroyBuffer(g_vkModDevice, g_vkModDepthReadbackBuf, nullptr);
		        g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		    }
		    if (g_vkModDepthReadbackMem != VK_NULL_HANDLE) {
		        vkFreeMemory(g_vkModDevice, g_vkModDepthReadbackMem, nullptr);
		        g_vkModDepthReadbackMem = VK_NULL_HANDLE;
		    }
		    g_vkModDepthReadbackDone = false;
		    g_vkModDepthReadbackPending = false;
		    // Direct real-depth probe resources (2026-08-06).
		    if (g_vkModDepthDirectBuf != VK_NULL_HANDLE) {
		        vkDestroyBuffer(g_vkModDevice, g_vkModDepthDirectBuf, nullptr);
		        g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		    }
		    if (g_vkModDepthDirectMem != VK_NULL_HANDLE) {
		        vkFreeMemory(g_vkModDevice, g_vkModDepthDirectMem, nullptr);
		        g_vkModDepthDirectMem = VK_NULL_HANDLE;
		    }
		    g_vkModDepthDirectDone = false;
		    g_vkModDepthDirectPending = false;
		    g_vkModDepthDirectCount = 0;
		    if (g_vkModMvGenDummyDepthMem != VK_NULL_HANDLE) {
		        vkFreeMemory(g_vkModDevice, g_vkModMvGenDummyDepthMem, nullptr);
		        g_vkModMvGenDummyDepthMem = VK_NULL_HANDLE;
		    }
		    g_vkModDepthSrcImage = VK_NULL_HANDLE;
		    g_vkModDepthSrcFmt = VK_FORMAT_UNDEFINED;
		    g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;
		    g_vkModDepthSrcW = 0;
		    g_vkModDepthSrcH = 0;
		    g_vkModMvGenReady = false;
		}

		static bool ensureVkModMvGenPipeline() {
		    if (g_vkModMvGenReady) return true;
		    if (g_vkModDevice == VK_NULL_HANDLE) return false;
		    if (g_vkModMvImage == VK_NULL_HANDLE || g_vkModReactiveImage == VK_NULL_HANDLE
		            || g_vkModDepthImage == VK_NULL_HANDLE) {
		        diagLog("ensureVkModMvGenPipeline: depth/MV/reactive images not imported yet");
		        return false;
		    }

		    // REVIEW FIX (2026-08-05): if this is a REBUILD (pool re-imported on
		    // resize, g_vkModMvGenReady was cleared but the pipeline objects
		    // survived — importPoolTextureAsVkImage only destroys images/views),
		    // destroy any stale pipeline objects BEFORE recreating below, or
		    // every resize leaks a shader module + pipeline layout + pipeline +
		    // descriptor pool (Vulkan objects are not GC'd). Idempotent.
		    //
		    // ROOT-CAUSE FIX (2026-08-06, "constant 0.5"): destroyVkModMvGenPipeline
		    // ALSO destroys the REAL-DEPTH source view AND wipes
		    // g_vkModDepthSrcImage/Fmt/BoundImage. On the FIRST build that wipes the
		    // view setVkModDepthSource created moments earlier (setVkModDepthSource
		    // runs before recordVkModMvGen in the mixin), so the review-fix below
		    // saw a NULL view, kept binding 3 on the dummy, and every dispatch
		    // sampled 0.5 (constant depth) — the "real depth silently disabled on
		    // the very first frame, forever". Capture the source BEFORE the
		    // destroy so the review-fix can recreate the view.
		    VkImage depthSrcAtBuild = g_vkModDepthSrcImage;
		    VkFormat depthSrcFmtAtBuild = g_vkModDepthSrcFmt;
		    // Same capture for the COLOR source (binding 4) — the destroy below
		    // wipes g_vkModColorSrcImage/Fmt too, so the recreate-view fix below
		    // needs the values captured BEFORE it runs (2026-08-06).
		    VkImage colorSrcAtBuild = g_vkModColorSrcImage;
		    VkFormat colorSrcFmtAtBuild = g_vkModColorSrcFmt;
		    destroyVkModMvGenPipeline();

		    VkResult res;

		    // Shader module
		    VkShaderModuleCreateInfo modInfo{};
		    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
		    modInfo.codeSize = kFsr4VkMvGenSpvSize;
		    modInfo.pCode = reinterpret_cast<const uint32_t*>(kFsr4VkMvGenSpv);
		    res = vkCreateShaderModule(g_vkModDevice, &modInfo, nullptr, &g_vkModMvGenShader);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreateShaderModule → %d", res);
		        return false;
		    }

		    // Descriptor set layout: 3 storage images (depth, MV, reactive) + 2
		    // combined image samplers (real depth binding 3, scene color binding 4
		    // for the thin-detail reactive term, both 2026-08-06).
		    VkDescriptorSetLayoutBinding bindings[5]{};
		    bindings[0].binding = 0;
		    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		    bindings[0].descriptorCount = 1;
		    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    bindings[1].binding = 1;
		    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		    bindings[1].descriptorCount = 1;
		    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    bindings[2].binding = 2;
		    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		    bindings[2].descriptorCount = 1;
		    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    bindings[3].binding = 3;
		    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		    bindings[3].descriptorCount = 1;
		    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    bindings[4].binding = 4;
		    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		    bindings[4].descriptorCount = 1;
		    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    VkDescriptorSetLayoutCreateInfo descLayoutInfo{};
		    descLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
		    descLayoutInfo.bindingCount = 5;
		    descLayoutInfo.pBindings = bindings;
		    res = vkCreateDescriptorSetLayout(g_vkModDevice, &descLayoutInfo, nullptr, &g_vkModMvGenDescLayout);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreateDescriptorSetLayout → %d", res);
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		        return false;
		    }

		    // Pipeline layout: descriptor set + 136-byte push constant range
		    // (2 mat4 + uCamMotion + uCamRotDelta — the CAM-MOTION PLUMB grew the
		    // old 128-byte "exactly the guaranteed minimum" range; every
		    // FSR4-capable GPU exposes maxPushConstantsSize 256, verified by the
		    // one-shot diag below).
		    VkPushConstantRange pcRange{};
		    pcRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
		    pcRange.offset = 0;
		    pcRange.size = 136;
		    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
		    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
		    pipeLayoutInfo.setLayoutCount = 1;
		    pipeLayoutInfo.pSetLayouts = &g_vkModMvGenDescLayout;
		    pipeLayoutInfo.pushConstantRangeCount = 1;
		    pipeLayoutInfo.pPushConstantRanges = &pcRange;
		    // CAM-MOTION PLUMB diag (2026-08-08): prove the 136-byte range fits the
		    // device's maxPushConstantsSize before creating the layout (the old
		    // "exactly 128 = guaranteed minimum" was conservative; the shader needs
		    // 2 floats more). One-shot per pipeline creation.
		    {
		        static bool maxPcLogged = false;
		        if (!maxPcLogged) {
		            maxPcLogged = true;
		            VkPhysicalDeviceProperties props{};
		            vkGetPhysicalDeviceProperties(g_vkModPhysDevice, &props);
		            diagLog("maxPushConstantsSize=%u (need 136)", props.limits.maxPushConstantsSize);
		        }
		    }
		    res = vkCreatePipelineLayout(g_vkModDevice, &pipeLayoutInfo, nullptr, &g_vkModMvGenPipeLayout);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreatePipelineLayout → %d", res);
		        vkDestroyDescriptorSetLayout(g_vkModDevice, g_vkModMvGenDescLayout, nullptr);
		        g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		        return false;
		    }

		    // Compute pipeline
		    VkPipelineShaderStageCreateInfo stageInfo{};
		    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
		    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
		    stageInfo.module = g_vkModMvGenShader;
		    stageInfo.pName = "main";
		    VkComputePipelineCreateInfo pipeInfo{};
		    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
		    pipeInfo.stage = stageInfo;
		    pipeInfo.layout = g_vkModMvGenPipeLayout;
		    res = vkCreateComputePipelines(g_vkModDevice, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &g_vkModMvGenPipeline);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreateComputePipelines → %d", res);
		        vkDestroyPipelineLayout(g_vkModDevice, g_vkModMvGenPipeLayout, nullptr);
		        g_vkModMvGenPipeLayout = VK_NULL_HANDLE;
		        vkDestroyDescriptorSetLayout(g_vkModDevice, g_vkModMvGenDescLayout, nullptr);
		        g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		        return false;
		    }

		    // Descriptor pool + set (storage images + depth/color combined samplers)
		    VkDescriptorPoolSize poolSizes[2]{};
		    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		    poolSizes[0].descriptorCount = 3;
		    poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		    poolSizes[1].descriptorCount = 2;   // depth (3) + color (4)
		    VkDescriptorPoolCreateInfo poolInfo{};
		    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
		    poolInfo.maxSets = 1;
		    poolInfo.poolSizeCount = 2;
		    poolInfo.pPoolSizes = poolSizes;
		    res = vkCreateDescriptorPool(g_vkModDevice, &poolInfo, nullptr, &g_vkModMvGenDescPool);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreateDescriptorPool → %d", res);
		        vkDestroyPipeline(g_vkModDevice, g_vkModMvGenPipeline, nullptr);
		        g_vkModMvGenPipeline = VK_NULL_HANDLE;
		        vkDestroyPipelineLayout(g_vkModDevice, g_vkModMvGenPipeLayout, nullptr);
		        g_vkModMvGenPipeLayout = VK_NULL_HANDLE;
		        vkDestroyDescriptorSetLayout(g_vkModDevice, g_vkModMvGenDescLayout, nullptr);
		        g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		        return false;
		    }
		    VkDescriptorSetAllocateInfo setAlloc{};
		    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
		    setAlloc.descriptorPool = g_vkModMvGenDescPool;
		    setAlloc.descriptorSetCount = 1;
		    setAlloc.pSetLayouts = &g_vkModMvGenDescLayout;
		    res = vkAllocateDescriptorSets(g_vkModDevice, &setAlloc, &g_vkModMvGenDescSet);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkAllocateDescriptorSets → %d", res);
		        vkDestroyDescriptorPool(g_vkModDevice, g_vkModMvGenDescPool, nullptr);
		        g_vkModMvGenDescPool = VK_NULL_HANDLE;
		        vkDestroyPipeline(g_vkModDevice, g_vkModMvGenPipeline, nullptr);
		        g_vkModMvGenPipeline = VK_NULL_HANDLE;
		        vkDestroyPipelineLayout(g_vkModDevice, g_vkModMvGenPipeLayout, nullptr);
		        g_vkModMvGenPipeLayout = VK_NULL_HANDLE;
		        vkDestroyDescriptorSetLayout(g_vkModDevice, g_vkModMvGenDescLayout, nullptr);
		        g_vkModMvGenDescLayout = VK_NULL_HANDLE;
		        vkDestroyShaderModule(g_vkModDevice, g_vkModMvGenShader, nullptr);
		        g_vkModMvGenShader = VK_NULL_HANDLE;
		        return false;
		    }

		    // STAGING images (VK-owned): MV-gen compute writes into these (reliable
		    // imageStore into VK-owned memory), then recordVkModMvGen
		    // vkCmdCopyImage's them into the imported D3D12-visible images. The
		    // imported views are intentionally NOT in the descriptor set anymore
		    // (2026-08-05): raw imageStore into the imported external-memory
		    // images doesn't persist on this AMD driver → the FSR4 SDK read
		    // depth≈0/MV≈0/reactive≈0 → discarded ALL history → spatial-only
		    // output ("no edge smoothing", jittered input shaking through).
		    if (!createVkModMvGenStageImage(VK_FORMAT_R32_SFLOAT,
		            &g_vkModMvGenStageDepth, &g_vkModMvGenStageDepthMem,
		            &g_vkModMvGenStageDepthView)
		        || !createVkModMvGenStageImage(VK_FORMAT_R16G16_SFLOAT,
		            &g_vkModMvGenStageMv, &g_vkModMvGenStageMvMem,
		            &g_vkModMvGenStageMvView)
		        || !createVkModMvGenStageImage(VK_FORMAT_R8_UNORM,
		            &g_vkModMvGenStageReactive, &g_vkModMvGenStageReactiveMem,
		            &g_vkModMvGenStageReactiveView)) {
		        diagLog("ensureVkModMvGenPipeline: staging image creation failed");
		        destroyVkModMvGenPipeline();
		        return false;
		    }

		    // NEAREST sampler for the real-depth sampling: depth values must be
		    // read EXACTLY (no filtering — neighbouring depth is meaningless),
		    // clamped at edges (uv wraps to border pixels on the boundary).
		    VkSamplerCreateInfo samplerInfo{};
		    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
		    samplerInfo.magFilter = VK_FILTER_NEAREST;
		    samplerInfo.minFilter = VK_FILTER_NEAREST;
		    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
		    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
		    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
		    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
		    samplerInfo.mipLodBias = 0.0f;
		    samplerInfo.minLod = 0.0f;
		    samplerInfo.maxLod = 0.0f;
		    res = vkCreateSampler(g_vkModDevice, &samplerInfo, nullptr, &g_vkModMvGenDepthSampler);
		    if (res != VK_SUCCESS) {
		        diagLog("ensureVkModMvGenPipeline: vkCreateSampler → %d", res);
		        destroyVkModMvGenPipeline();
		        return false;
		    }

		    // 1x1 dummy depth (R32F, value 0.5): the FALLBACK for binding 3 when
		    // no real framebuffer depth is available (setVkModDepthSource(0)).
		    // Sampling it anywhere yields 0.5 → ndcZ=0 → the legacy constant-depth
		    // behavior, so the shader's real-depth path degrades gracefully
		    // instead of reading an unbound descriptor (UB).
		    if (!createVkModMvGenDummyDepth()) {
		        diagLog("ensureVkModMvGenPipeline: dummy depth creation failed");
		        destroyVkModMvGenPipeline();
		        return false;
		    }
		    if (!createVkModMvGenDummyColor()) {
		        diagLog("ensureVkModMvGenPipeline: dummy color creation failed");
		        destroyVkModMvGenPipeline();
		        return false;
		    }

		    // Update descriptor set: 3 STAGING storage images + binding 3 = dummy
		    // depth sampler (replaced by the real framebuffer depth when
		    // setVkModDepthSource is called) + binding 4 = dummy color sampler
		    // (replaced by the real scene color via setVkModColorSource). The
		    // depth sampler is REUSED for the color binding — both are nearest /
		    // clamp-to-edge, and the color reads are explicit texelFetch-style
		    // (the sampler parameters are irrelevant for texelFetch in GLSL 450).
		    VkDescriptorImageInfo imgInfos[5]{};
		    imgInfos[0].imageView = g_vkModMvGenStageDepthView;
		    imgInfos[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
		    imgInfos[1].imageView = g_vkModMvGenStageMvView;
		    imgInfos[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
		    imgInfos[2].imageView = g_vkModMvGenStageReactiveView;
		    imgInfos[2].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
		    imgInfos[3].imageView = g_vkModMvGenDummyDepthView;
		    imgInfos[3].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		    imgInfos[3].sampler = g_vkModMvGenDepthSampler;
		    imgInfos[4].imageView = g_vkModMvGenDummyColorView;
		    imgInfos[4].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		    imgInfos[4].sampler = g_vkModMvGenDepthSampler;
		    VkWriteDescriptorSet writes[5]{};
		    for (int i = 0; i < 3; i++) {
		        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		        writes[i].dstSet = g_vkModMvGenDescSet;
		        writes[i].dstBinding = static_cast<uint32_t>(i);
		        writes[i].descriptorCount = 1;
		        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		        writes[i].pImageInfo = &imgInfos[i];
		    }
		    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		    writes[3].dstSet = g_vkModMvGenDescSet;
		    writes[3].dstBinding = 3;
		    writes[3].descriptorCount = 1;
		    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		    writes[3].pImageInfo = &imgInfos[3];
		    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		    writes[4].dstSet = g_vkModMvGenDescSet;
		    writes[4].dstBinding = 4;
		    writes[4].descriptorCount = 1;
		    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		    writes[4].pImageInfo = &imgInfos[4];
		    vkUpdateDescriptorSets(g_vkModDevice, 5, writes, 0, nullptr);

		    // Review fix (2026-08-06): if a REAL depth source was set before this
		    // pipeline rebuild (setVkModDepthSource runs earlier in the same frame
		    // and its descriptor write was skipped — the set didn't exist yet),
		    // bind it now instead of leaving the dummy. Prevents the real-depth
		    // binding from being stranded after every pool rebuild (version switch
		    // / disable→enable).
		    //
		    // ROOT-CAUSE FIX (2026-08-06): the destroyVkModMvGenPipeline call above
		    // wiped the view setVkModDepthSource created this frame (and the
		    // g_vkModDepthSrcImage tracking) — recreate the view from the captured
		    // source so the real-depth binding survives the FIRST build (the
		    // "constant 0.5" — dummy sampled every frame).
		    if (g_vkModDepthSrcView == VK_NULL_HANDLE
		            && depthSrcAtBuild != VK_NULL_HANDLE
		            && depthSrcFmtAtBuild != VK_FORMAT_UNDEFINED) {
		        g_vkModDepthSrcImage = depthSrcAtBuild;
		        g_vkModDepthSrcFmt = depthSrcFmtAtBuild;
		        g_vkModDepthSrcView = createVkModDepthView(depthSrcAtBuild, depthSrcFmtAtBuild);
		        if (g_vkModDepthSrcView != VK_NULL_HANDLE) {
		            diagLog("ensureVkModMvGenPipeline: recreated depth view (build wiped it)");
		        }
		    }
		    if (g_vkModDepthSrcView != VK_NULL_HANDLE
		            && g_vkModDepthSrcImage != VK_NULL_HANDLE) {
		        VkDescriptorImageInfo realInfo{};
		        realInfo.imageView = g_vkModDepthSrcView;
		        realInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		        realInfo.sampler = g_vkModMvGenDepthSampler;
		        VkWriteDescriptorSet w3{};
		        w3.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		        w3.dstSet = g_vkModMvGenDescSet;
		        w3.dstBinding = 3;
		        w3.descriptorCount = 1;
		        w3.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		        w3.pImageInfo = &realInfo;
		        vkUpdateDescriptorSets(g_vkModDevice, 1, &w3, 0, nullptr);
		        g_vkModDepthSrcBoundImage = g_vkModDepthSrcImage;
		    } else {
		        g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;  // dummy bound
		    }

		    // COLOR-source rebuild fix (2026-08-06, mirrors the depth block):
		    // if a real color source was set before this pipeline rebuild, recreate
		    // its view (the destroy above wiped it) and bind it instead of the
		    // dummy — otherwise the thin-detail reactive term stays dead after
		    // every pool rebuild (version switch / disable→enable).
		    if (g_vkModColorSrcView == VK_NULL_HANDLE
		            && colorSrcAtBuild != VK_NULL_HANDLE
		            && colorSrcFmtAtBuild != VK_FORMAT_UNDEFINED) {
		        g_vkModColorSrcImage = colorSrcAtBuild;
		        g_vkModColorSrcFmt = colorSrcFmtAtBuild;
		        g_vkModColorSrcView = createVkModColorView(colorSrcAtBuild, colorSrcFmtAtBuild);
		        if (g_vkModColorSrcView != VK_NULL_HANDLE) {
		            diagLog("ensureVkModMvGenPipeline: recreated color view (build wiped it)");
		        }
		    }
		    if (g_vkModColorSrcView != VK_NULL_HANDLE
		            && g_vkModColorSrcImage != VK_NULL_HANDLE) {
		        VkDescriptorImageInfo colorInfo{};
		        colorInfo.imageView = g_vkModColorSrcView;
		        colorInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		        colorInfo.sampler = g_vkModMvGenDepthSampler;
		        VkWriteDescriptorSet w4{};
		        w4.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		        w4.dstSet = g_vkModMvGenDescSet;
		        w4.dstBinding = 4;
		        w4.descriptorCount = 1;
		        w4.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		        w4.pImageInfo = &colorInfo;
		        vkUpdateDescriptorSets(g_vkModDevice, 1, &w4, 0, nullptr);
		        g_vkModColorSrcBoundImage = g_vkModColorSrcImage;
		    } else {
		        g_vkModColorSrcBoundImage = VK_NULL_HANDLE;  // dummy bound
		    }

		    g_vkModMvGenReady = true;
		    diagLog("ensureVkModMvGenPipeline: OK — real FSR4 VK input path ready");
		    return true;
		}

		// Creates a DEPTH-aspect image view for the framebuffer's depth attachment
		// (the generic createVkModImageView uses COLOR aspect — sampling depth
		// needs DEPTH aspect, otherwise the view is invalid).
		static VkImageView createVkModDepthView(VkImage image, VkFormat fmt) {
		    VkImageViewCreateInfo info{};
		    info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		    info.image = image;
		    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
		    info.format = fmt;
		    info.components = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
		        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY };
		    info.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
		    VkImageView view = VK_NULL_HANDLE;
		    VkResult res = vkCreateImageView(g_vkModDevice, &info, nullptr, &view);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModDepthView: vkCreateImageView → %d (fmt=%d)", res, (int)fmt);
		        return VK_NULL_HANDLE;
		    }
		    return view;
		}

		// Creates a COLOR-aspect image view for the scene color source (binding 4,
		// thin-detail reactive, 2026-08-06). Mirrors createVkModDepthView but with
		// COLOR aspect — the generic createVkModImageView is only used for the
		// storage/staging images and does not handle the swapchain/framebuffer
		// color formats.
		static VkImageView createVkModColorView(VkImage image, VkFormat fmt) {
		    if (image == VK_NULL_HANDLE) return VK_NULL_HANDLE;
		    VkImageViewCreateInfo info{};
		    info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
		    info.image = image;
		    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
		    info.format = fmt;
		    info.components = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
		        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY };
		    info.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		    VkImageView view = VK_NULL_HANDLE;
		    VkResult res = vkCreateImageView(g_vkModDevice, &info, nullptr, &view);
		    if (res != VK_SUCCESS) {
		        diagLog("createVkModColorView: vkCreateImageView → %d (fmt=%d)", res, (int)fmt);
		        return VK_NULL_HANDLE;
		    }
		    return view;
		}

		// Binds (or clears) the REAL-depth source for MV-gen binding 3. Called
		// by the main-pass mixin EVERY frame with the current framebuffer's depth
		// attachment (cheap: a no-op unless the image handle changed — the
		// framebuffer is recreated on resize/scale change). image==0 → rebind the
		// 1x1 dummy (constant-depth fallback). The view is created lazily and
		// recreated only when the image changes.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_setVkModDepthSource(
		    JNIEnv*, jclass, jlong image, jint format, jint width, jint height)
		{
		    __try {
		        VkImage newImage = reinterpret_cast<VkImage>(image);
		        VkFormat newFmt = static_cast<VkFormat>(format);
		        g_vkModDepthSrcW = width;
		        g_vkModDepthSrcH = height;
		        // One-shot diagnostic (2026-08-06): log what Java ACTUALLY passes on
		        // the first call. The readback probe proved the dummy (0.5) stays
		        // bound while no error fires — the silent no-op below is the prime
		        // suspect (newImage==0 == boundImage==0 → return with no log).
		        // This line distinguishes "Java passed 0L" from "view creation
		        // failed" from "image changed but format mismatched" in one run.
		        static bool vkModDepthSrcLogged = false;
		        if (!vkModDepthSrcLogged) {
		            vkModDepthSrcLogged = true;
		            diagLog("setVkModDepthSource: image=%p fmt=%d bound=%p (first call)",
		                (void*)newImage, (int)newFmt, (void*)g_vkModDepthSrcBoundImage);
		        }
		        // No-op ONLY when the image is already bound to binding 3 (review
		        // fix 2026-08-06 — comparing against g_vkModDepthSrcImage instead
		        // let a descriptor-set rebuild strand the binding on the dummy
		        // forever, because the image handle itself was unchanged).
		        if (newImage == g_vkModDepthSrcBoundImage
		                && (newImage == VK_NULL_HANDLE || newFmt == g_vkModDepthSrcFmt)) {
		            return;
		        }
		        // (Re)build the view for the new image.
		        if (g_vkModDepthSrcView != VK_NULL_HANDLE) {
		            vkDestroyImageView(g_vkModDevice, g_vkModDepthSrcView, nullptr);
		            g_vkModDepthSrcView = VK_NULL_HANDLE;
		        }
		        g_vkModDepthSrcImage = newImage;
		        g_vkModDepthSrcFmt = newFmt;
		        diagLog("setVkModDepthSource: rebinding to image=%p fmt=%d",
		            (void*)newImage, (int)newFmt);
		        if (newImage == VK_NULL_HANDLE) {
		            // Fallback: rebind the dummy (constant-depth path).
		            if (g_vkModMvGenDescSet != VK_NULL_HANDLE) {
		                VkDescriptorImageInfo info{};
		                info.imageView = g_vkModMvGenDummyDepthView;
		                info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		                info.sampler = g_vkModMvGenDepthSampler;
		                VkWriteDescriptorSet w{};
		                w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		                w.dstSet = g_vkModMvGenDescSet;
		                w.dstBinding = 3;
		                w.descriptorCount = 1;
		                w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		                w.pImageInfo = &info;
		                vkUpdateDescriptorSets(g_vkModDevice, 1, &w, 0, nullptr);
		            }
		            g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        if (newFmt == VK_FORMAT_UNDEFINED) {
		            diagLog("setVkModDepthSource: undefined depth format — falling back to constant depth");
		            g_vkModDepthSrcImage = VK_NULL_HANDLE;
		            g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        g_vkModDepthSrcView = createVkModDepthView(newImage, newFmt);
		        if (g_vkModDepthSrcView == VK_NULL_HANDLE) {
		            g_vkModDepthSrcImage = VK_NULL_HANDLE;
		            g_vkModDepthSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        // Point binding 3 at the real depth. When the descriptor set does
		        // NOT exist yet (pipeline not built — setVkModDepthSource runs
		        // before ensureVkModMvGenPipeline in the mixin), ensureVkModMvGenPipeline
		        // binds it after creating the set (it checks g_vkModDepthSrcView).
		        // boundImage is set either way so the next frame no-ops.
		        if (g_vkModMvGenDescSet != VK_NULL_HANDLE) {
		            VkDescriptorImageInfo info{};
		            info.imageView = g_vkModDepthSrcView;
		            info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		            info.sampler = g_vkModMvGenDepthSampler;
		            VkWriteDescriptorSet w{};
		            w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		            w.dstSet = g_vkModMvGenDescSet;
		            w.dstBinding = 3;
		            w.descriptorCount = 1;
		            w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		            w.pImageInfo = &info;
		            vkUpdateDescriptorSets(g_vkModDevice, 1, &w, 0, nullptr);
		        }
		        g_vkModDepthSrcBoundImage = newImage;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("setVkModDepthSource: SEH exception");
		    }
		}

		// Binds (or clears) the SCENE-COLOR source for MV-gen binding 4 (the
		// thin-detail/cutout reactive term, 2026-08-06). Called by the main-pass
		// mixin EVERY frame with the current framebuffer's color attachment
		// (cheap: a no-op unless the image handle changed). image==0 → rebind the
		// 1x1 mid-gray dummy (detector disabled). format==0 → resolved from the
		// swapchain format: in render-scale mode the scaled framebuffer is built
		// with setFormat(swapChain.getFormat()) so its color format ALWAYS equals
		// the swapchain format, and in the non-scaled path the scene IS the
		// swapchain image — exact either way. Mirrors setVkModDepthSource.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_setVkModColorSource(
		    JNIEnv*, jclass, jlong image, jint format, jint width, jint height)
		{
		    __try {
		        VkImage newImage = reinterpret_cast<VkImage>(image);
		        VkFormat newFmt = static_cast<VkFormat>(format);
		        g_vkModColorSrcW = width;
		        g_vkModColorSrcH = height;
		        if (newFmt == VK_FORMAT_UNDEFINED) {
		            // Resolve from the swapchain format (see the comment above).
		            newFmt = g_vkModSwapchainBGRA ? VK_FORMAT_B8G8R8A8_UNORM
		                : VK_FORMAT_R8G8B8A8_UNORM;
		        }
		        // One-shot diagnostic: log what Java ACTUALLY passes on the first
		        // call (same pattern as the depth source's first-call trace).
		        static bool vkModColorSrcLogged = false;
		        if (!vkModColorSrcLogged) {
		            vkModColorSrcLogged = true;
		            diagLog("setVkModColorSource: image=%p fmt=%d bound=%p (first call)",
		                (void*)newImage, (int)newFmt, (void*)g_vkModColorSrcBoundImage);
		        }
		        // No-op ONLY when the image is already bound to binding 4.
		        if (newImage == g_vkModColorSrcBoundImage
		                && (newImage == VK_NULL_HANDLE || newFmt == g_vkModColorSrcFmt)) {
		            return;
		        }
		        if (g_vkModColorSrcView != VK_NULL_HANDLE) {
		            vkDestroyImageView(g_vkModDevice, g_vkModColorSrcView, nullptr);
		            g_vkModColorSrcView = VK_NULL_HANDLE;
		        }
		        g_vkModColorSrcImage = newImage;
		        g_vkModColorSrcFmt = newFmt;
		        diagLog("setVkModColorSource: rebinding to image=%p fmt=%d",
		            (void*)newImage, (int)newFmt);
		        if (newImage == VK_NULL_HANDLE) {
		            // Fallback: rebind the dummy (detector disabled).
		            if (g_vkModMvGenDescSet != VK_NULL_HANDLE) {
		                VkDescriptorImageInfo info{};
		                info.imageView = g_vkModMvGenDummyColorView;
		                info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		                info.sampler = g_vkModMvGenDepthSampler;
		                VkWriteDescriptorSet w{};
		                w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		                w.dstSet = g_vkModMvGenDescSet;
		                w.dstBinding = 4;
		                w.descriptorCount = 1;
		                w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		                w.pImageInfo = &info;
		                vkUpdateDescriptorSets(g_vkModDevice, 1, &w, 0, nullptr);
		            }
		            g_vkModColorSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        if (newFmt == VK_FORMAT_UNDEFINED) {
		            g_vkModColorSrcImage = VK_NULL_HANDLE;
		            g_vkModColorSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        g_vkModColorSrcView = createVkModColorView(newImage, newFmt);
		        if (g_vkModColorSrcView == VK_NULL_HANDLE) {
		            g_vkModColorSrcImage = VK_NULL_HANDLE;
		            g_vkModColorSrcBoundImage = VK_NULL_HANDLE;
		            return;
		        }
		        // Point binding 4 at the real scene color. When the descriptor set
		        // does NOT exist yet (pipeline not built — setVkModColorSource runs
		        // before ensureVkModMvGenPipeline in the mixin), the pipeline
		        // builder binds it after creating the set (checks g_vkModColorSrcView).
		        if (g_vkModMvGenDescSet != VK_NULL_HANDLE) {
		            VkDescriptorImageInfo info{};
		            info.imageView = g_vkModColorSrcView;
		            info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		            info.sampler = g_vkModMvGenDepthSampler;
		            VkWriteDescriptorSet w{};
		            w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
		            w.dstSet = g_vkModMvGenDescSet;
		            w.dstBinding = 4;
		            w.descriptorCount = 1;
		            w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		            w.pImageInfo = &info;
		            vkUpdateDescriptorSets(g_vkModDevice, 1, &w, 0, nullptr);
		        }
		        g_vkModColorSrcBoundImage = newImage;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("setVkModColorSource: SEH exception");
		    }
		}

		// Records the MV-gen compute dispatch into VulkanMod's still-recording
		// command buffer (called from the main-pass end() mixin right after
		// recordFrameCopyOps, BEFORE the frame fence is stored — so the fence
		// covers the compute and the next frame's D3D12 dispatch sees valid
		// depth/MV/reactive). The scene-render → Copy1 ordering inside the same
		// command buffer is guaranteed by Vulkan's submission order.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_recordVkModMvGen(
		    JNIEnv*, jclass, jlong cmdBuf)
		{
		    __try {
		        if (!g_vkModDeviceSet) {
		            diagLog("recordVkModMvGen: VulkanMod device not set — skipping");
		            return;
		        }

		        // One-shot REAL-DEPTH readback probe consumption (2026-08-06):
		        // last frame recorded a 16x16 copy of the STAGING depth into
		        // g_vkModDepthReadbackBuf. Wait the (already-signaled) frame fence
		        // that covered that submission, map, and log 9 samples — proving
		        // the depth convention (non-reversed: terrain≈0.001, sky≈1.0)
		        // actually reaches the pool. Then free the resources forever.
		        if (g_vkModDepthReadbackPending) {
		            g_vkModDepthReadbackPending = false;
		            if (g_vkModFrameFence != VK_NULL_HANDLE) {
		                vkWaitForFences(g_vkModDevice, 1, &g_vkModFrameFence,
		                    VK_TRUE, 100000000ULL);  // 100ms — should be instant
		            }
		            void* mapped = nullptr;
		            VkResult mr = vkMapMemory(g_vkModDevice, g_vkModDepthReadbackMem,
		                0, VK_WHOLE_SIZE, 0, &mapped);
		            if (mr == VK_SUCCESS && mapped) {
		                const float* f = static_cast<const float*>(mapped);
		                // 9 samples across the 16x16 block (4x4 stride + center).
		                diagLog("DEPTH READBACK (staging, real-depth path): ");
		                diagLog("  [0,0]=%f [8,0]=%f [15,0]=%f", f[0], f[8], f[15]);
		                diagLog("  [0,8]=%f [8,8]=%f [15,8]=%f", f[8*16], f[8*16+8], f[8*16+15]);
		                diagLog("  [0,15]=%f [8,15]=%f [15,15]=%f", f[15*16], f[15*16+8], f[15*16+15]);
		                diagLog("  expected: terrain≈0.001-0.1, mid≈0.5, sky≈1.0 (non-reversed)");
		                vkUnmapMemory(g_vkModDevice, g_vkModDepthReadbackMem);
		            } else {
		                diagLog("DEPTH READBACK: vkMapMemory → %d", (int)mr);
		            }
		            g_vkModDepthReadbackDone = true;
		            vkDestroyBuffer(g_vkModDevice, g_vkModDepthReadbackBuf, nullptr);
		            vkFreeMemory(g_vkModDevice, g_vkModDepthReadbackMem, nullptr);
		            g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		            g_vkModDepthReadbackMem = VK_NULL_HANDLE;
		        }

		        // Direct real-depth probe consumption (2026-08-06): the source is
		        // the framebuffer's REAL depth attachment image, copied straight to
		        // the host buffer (no shader in between). v2: 3 rows (top/mid/
		        // bottom) x 5 samples, periodic. Compare the FIRST sample against
		        // the same-frame staging readback — matching real values = the
		        // root-cause fix worked.
		        if (g_vkModDepthDirectPending) {
		            g_vkModDepthDirectPending = false;
		            if (g_vkModFrameFence != VK_NULL_HANDLE) {
		                vkWaitForFences(g_vkModDevice, 1, &g_vkModFrameFence,
		                    VK_TRUE, 100000000ULL);
		            }
		            void* mapped = nullptr;
		            VkResult mr = vkMapMemory(g_vkModDevice, g_vkModDepthDirectMem,
		                0, VK_WHOLE_SIZE, 0, &mapped);
		            if (mr == VK_SUCCESS && mapped) {
		                const float* f = static_cast<const float*>(mapped);
		                // Each 16x4 block = 64 floats; rows at offsets 0/64/128.
		                diagLog("REAL-DEPTH DIRECT READBACK (attachment) #%d:",
		                    g_vkModDepthDirectCount);
		                for (int r = 0; r < 3; r++) {
		                    const float* row = f + r * 64;
		                    diagLog("  row%d [x0]=%.3f [x4]=%.3f [x8]=%.3f [x12]=%.3f [x15]=%.3f",
		                        r, row[0], row[4], row[8], row[12], row[15]);
		                }
		                diagLog("  expect: sky/top≈1.0, terrain/bottom≈0.001-0.1; matches staging probe = fix OK");
		                vkUnmapMemory(g_vkModDevice, g_vkModDepthDirectMem);
		            } else {
		                diagLog("REAL-DEPTH DIRECT READBACK: vkMapMemory → %d", (int)mr);
		            }
		            if (g_vkModDepthDirectCount >= 5) {
		                g_vkModDepthDirectDone = true;
		                vkDestroyBuffer(g_vkModDevice, g_vkModDepthDirectBuf, nullptr);
		                vkFreeMemory(g_vkModDevice, g_vkModDepthDirectMem, nullptr);
		                g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		                g_vkModDepthDirectMem = VK_NULL_HANDLE;
		            }
		        }
		        if (!g_vkModMvGenMatricesSet) {
		            diagLog("recordVkModMvGen: matrices not set — skipping");
		            return;
		        }
		        if (!ensureVkModMvGenPipeline()) {
		            diagLog("recordVkModMvGen: pipeline not ready — skipping");
		            return;
		        }

		        VkCommandBuffer cb = reinterpret_cast<VkCommandBuffer>(cmdBuf);

		        // STAGING write targets: the MV-gen compute writes depth/MV/reactive
		        // into VK-OWNED staging images (imageStore into VK-owned memory is
		        // reliable). oldLayout=UNDEFINED each frame is fine — the dispatch
		        // fully overwrites every texel, so discarding is safe.
		        VkImage stageImgs[3] = { g_vkModMvGenStageDepth, g_vkModMvGenStageMv,
		            g_vkModMvGenStageReactive };
		        VkImageMemoryBarrier barriers[3]{};
		        for (int i = 0; i < 3; i++) {
		            barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		            barriers[i].image = stageImgs[i];
		            barriers[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		            barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
		            barriers[i].srcAccessMask = 0;
		            barriers[i].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
		            barriers[i].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
		            barriers[i].subresourceRange.baseMipLevel = 0;
		            barriers[i].subresourceRange.levelCount = 1;
		            barriers[i].subresourceRange.baseArrayLayer = 0;
		            barriers[i].subresourceRange.layerCount = 1;
		            barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		            barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		        }
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		            0, 0, nullptr, 0, nullptr, 3, barriers);

	        // REAL-DEPTH input barrier (2026-08-06): when the framebuffer's
	        // depth attachment is bound to binding 3 (setVkModDepthSource),
	        // transition it DEPTH_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL
	        // so the MV-gen can sample it. VulkanMod's render pass leaves depth
	        // in DEPTH_ATTACHMENT_OPTIMAL (DEPTH.defaultLayout=3 — verified by
	        // bytecode). The out-barrier below restores it for the next render
	        // pass. Skipped entirely when only the dummy is bound.
	        bool realDepthBound = (g_vkModDepthSrcView != VK_NULL_HANDLE);
	        if (realDepthBound) {
	            VkImageMemoryBarrier depthIn{};
	            depthIn.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
	            depthIn.image = g_vkModDepthSrcImage;
	            depthIn.oldLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
	            depthIn.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	            depthIn.srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
	            depthIn.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
	            depthIn.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
	            depthIn.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
	            depthIn.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
	            vkCmdPipelineBarrier(cb,
	                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
	                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
	                0, 0, nullptr, 0, nullptr, 1, &depthIn);
	        }

		        // SCENE-COLOR input barrier (2026-08-06, thin-detail reactive): when
		        // the framebuffer's color attachment is bound to binding 4
		        // (setVkModColorSource), transition it COLOR_ATTACHMENT_OPTIMAL →
		        // SHADER_READ_ONLY_OPTIMAL so the MV-gen can sample it. The color's
		        // most recent write is the scene render pass (the Copy1/blit only
		        // READ it as TRANSFER_SRC and restored it to COLOR_ATTACHMENT_OPTIMAL
		        // via barrier 7). The out-barrier below restores it for the next
		        // render pass (and, in the non-scaled path, for vanilla's PRESENT
		        // transition). Skipped entirely when only the dummy is bound.
		        bool realColorBound = (g_vkModColorSrcView != VK_NULL_HANDLE);
		        if (realColorBound) {
		            VkImageMemoryBarrier colorIn{};
		            colorIn.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		            colorIn.image = g_vkModColorSrcImage;
		            colorIn.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
		            colorIn.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		            colorIn.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
		                | VK_ACCESS_TRANSFER_READ_BIT;
		            colorIn.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
		            colorIn.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		            colorIn.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		            colorIn.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		            vkCmdPipelineBarrier(cb,
		                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		                0, 0, nullptr, 0, nullptr, 1, &colorIn);
		        }

		        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_vkModMvGenPipeline);

		        // Push constants: 2 mat4 = 128 bytes (exactly the guaranteed
		        // maxPushConstantsSize; the shader reads imageSize() for dims).
		        // Push constants: 2 mat4 + uCamMotion + uCamRotDelta = 136 bytes
		        // (the CAM-MOTION PLUMB extended the old 128-byte minimum; every
		        // FSR4-capable GPU exposes maxPushConstantsSize 256 — verified by
		        // the one-shot diag in ensureVkModMvGenPipeline).
		        vkCmdPushConstants(cb, g_vkModMvGenPipeLayout,
		            VK_SHADER_STAGE_COMPUTE_BIT, 0, 136,
		            g_vkModMvGenPush);

		        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
		            g_vkModMvGenPipeLayout, 0, 1, &g_vkModMvGenDescSet, 0, nullptr);

		        // Dispatch: imageSize() is the pool dims — dispatch over those.
		        uint32_t w = g_vkModImageWidth;
		        uint32_t h = g_vkModImageHeight;
		        if (w == 0 || h == 0) {
		            diagLog("recordVkModMvGen: zero image dims — skipping");
		            return;
		        }
		        vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);

	        // REAL-DEPTH out-barrier: SHADER_READ_ONLY_OPTIMAL →
	        // DEPTH_ATTACHMENT_OPTIMAL so VulkanMod's NEXT render pass can
	        // attach the depth again. The next frame's scene render happens
	        // AFTER this command buffer, so the transition is ordered.
	        if (realDepthBound) {
	            VkImageMemoryBarrier depthOut{};
	            depthOut.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
	            depthOut.image = g_vkModDepthSrcImage;
	            depthOut.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
	            depthOut.newLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
	            depthOut.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
	            depthOut.dstAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
	                | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
	            depthOut.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
	            depthOut.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
	            depthOut.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
	            vkCmdPipelineBarrier(cb,
	                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
	                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
	                0, 0, nullptr, 0, nullptr, 1, &depthOut);
	        }

		        // SCENE-COLOR out-barrier: SHADER_READ_ONLY_OPTIMAL →
		        // COLOR_ATTACHMENT_OPTIMAL so the NEXT render pass can draw into the
		        // color again (and vanilla's PRESENT transition in the non-scaled
		        // path sees the layout it expects).
		        if (realColorBound) {
		            VkImageMemoryBarrier colorOut{};
		            colorOut.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		            colorOut.image = g_vkModColorSrcImage;
		            colorOut.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
		            colorOut.newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
		            colorOut.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
		            colorOut.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
		            colorOut.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
		            colorOut.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		            colorOut.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		            vkCmdPipelineBarrier(cb,
		                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		                0, 0, nullptr, 0, nullptr, 1, &colorOut);
		        }

		        // 1) staging: GENERAL (compute write) → TRANSFER_SRC_OPTIMAL (copy read)
		        VkImageMemoryBarrier stageOut[3]{};
		        for (int i = 0; i < 3; i++) {
		            stageOut[i] = barriers[i];
		            stageOut[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
		            stageOut[i].newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		            stageOut[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
		            stageOut[i].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		        }
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            0, 0, nullptr, 0, nullptr, 3, stageOut);

		        // 2) imported (D3D12-visible): → TRANSFER_DST_OPTIMAL. oldLayout=UNDEFINED
		        // each frame is fine — the copy fully overwrites, so discard is safe.
		        VkImage importedImgs[3] = { g_vkModDepthImage, g_vkModMvImage,
		            g_vkModReactiveImage };
		        VkImageMemoryBarrier importedBar[3]{};
		        for (int i = 0; i < 3; i++) {
		            importedBar[i] = barriers[i];
		            importedBar[i].image = importedImgs[i];
		            importedBar[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		            importedBar[i].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		            importedBar[i].srcAccessMask = 0;
		            importedBar[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		        }
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            0, 0, nullptr, 0, nullptr, 3, importedBar);

		        // 3) staging → imported (transfer copies DO persist into the
		        // D3D12-visible shared memory — Copy1 proves the mechanism).
		        VkImageCopy copyRegion{};
		        copyRegion.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		        copyRegion.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		        copyRegion.extent = { w, h, 1 };
		        vkCmdCopyImage(cb,
		            g_vkModMvGenStageDepth, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		            g_vkModDepthImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		            1, &copyRegion);
		        vkCmdCopyImage(cb,
		            g_vkModMvGenStageMv, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		            g_vkModMvImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		            1, &copyRegion);
		        vkCmdCopyImage(cb,
		            g_vkModMvGenStageReactive, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		            g_vkModReactiveImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		            1, &copyRegion);

		        // One-shot REAL-DEPTH readback probe (2026-08-06): copy a 16x16
		        // block of the STAGING depth (still TRANSFER_SRC_OPTIMAL here —
		        // the compute wrote it, layout is unambiguously copyable) into a
		        // HOST-VISIBLE buffer. Consumed (mapped + logged) on the next
		        // recordVkModMvGen call after the frame fence signals. 16x16
		        // R32F = 1KB; 9 sample points prove the convention.
		        if (!g_vkModDepthReadbackDone && g_vkModDepthReadbackBuf == VK_NULL_HANDLE) {
		            VkBufferCreateInfo rbi{};
		            rbi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
		            rbi.size = 16 * 16 * 4;
		            rbi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
		            rbi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
		            VkResult rres = vkCreateBuffer(g_vkModDevice, &rbi, nullptr, &g_vkModDepthReadbackBuf);
		            if (rres == VK_SUCCESS) {
		                VkMemoryRequirements rreqs;
		                vkGetBufferMemoryRequirements(g_vkModDevice, g_vkModDepthReadbackBuf, &rreqs);
		                int rmt = findVkModMemoryType(rreqs.memoryTypeBits,
		                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		                if (rmt >= 0) {
		                    VkMemoryAllocateInfo ralloc{};
		                    ralloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		                    ralloc.allocationSize = rreqs.size;
		                    ralloc.memoryTypeIndex = static_cast<uint32_t>(rmt);
		                    rres = vkAllocateMemory(g_vkModDevice, &ralloc, nullptr, &g_vkModDepthReadbackMem);
		                    if (rres == VK_SUCCESS) {
		                        rres = vkBindBufferMemory(g_vkModDevice, g_vkModDepthReadbackBuf,
		                            g_vkModDepthReadbackMem, 0);
		                        if (rres != VK_SUCCESS) {
		                            // Checked (review fix 2026-08-06): an unbound
		                            // allocation would map and log GARBAGE as if it
		                            // were real depth — worse than no probe at all.
		                            diagLog("DEPTH READBACK: vkBindBufferMemory → %d", (int)rres);
		                            vkFreeMemory(g_vkModDevice, g_vkModDepthReadbackMem, nullptr);
		                            g_vkModDepthReadbackMem = VK_NULL_HANDLE;
		                            vkDestroyBuffer(g_vkModDevice, g_vkModDepthReadbackBuf, nullptr);
		                            g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		                            // Latch the failure — do NOT retry every frame.
		                            g_vkModDepthReadbackDone = true;
		                        } else {
		                            // Copy the CENTER 16x16 of the depth (terrain usually
		                        // fills center view — center values are most
		                        // representative for the convention check).
		                        VkBufferImageCopy bcopy{};
		                        bcopy.bufferOffset = 0;
		                        bcopy.bufferRowLength = 0;
		                        bcopy.bufferImageHeight = 0;
		                        bcopy.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
		                        bcopy.imageOffset = {
		                            (int32_t)(w >= 16 ? (w - 16) / 2 : 0),
		                            (int32_t)(h >= 16 ? (h - 16) / 2 : 0), 0 };
		                        bcopy.imageExtent = { w >= 16 ? 16u : w, h >= 16 ? 16u : h, 1 };
		                        vkCmdCopyImageToBuffer(cb,
		                            g_vkModMvGenStageDepth, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		                            g_vkModDepthReadbackBuf, 1, &bcopy);
		                        g_vkModDepthReadbackPending = true;
		                        diagLog("DEPTH READBACK: recorded 16x16 copy at offset (%d,%d)",
		                            bcopy.imageOffset.x, bcopy.imageOffset.y);
		                        }
		                    } else {
		                        diagLog("DEPTH READBACK: vkAllocateMemory → %d", (int)rres);
		                        vkDestroyBuffer(g_vkModDevice, g_vkModDepthReadbackBuf, nullptr);
		                        g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		                        // Latch the failure — do NOT retry every frame.
		                        g_vkModDepthReadbackDone = true;
		                    }
		                } else {
		                    diagLog("DEPTH READBACK: no host-visible memory type");
		                    vkDestroyBuffer(g_vkModDevice, g_vkModDepthReadbackBuf, nullptr);
		                    g_vkModDepthReadbackBuf = VK_NULL_HANDLE;
		                    // Latch the failure — do NOT retry every frame.
		                    g_vkModDepthReadbackDone = true;
		                }
		            } else {
		                diagLog("DEPTH READBACK: vkCreateBuffer → %d", (int)rres);
		                // Latch the failure — do NOT retry every frame.
		                g_vkModDepthReadbackDone = true;
		            }
		        }

		        // DIRECT real-depth probe (2026-08-06) v2: copy the framebuffer's
		        // REAL depth attachment (g_vkModDepthSrcImage) straight to a host
		        // buffer — no shader in between. Periodic: every 60th frame, 5
		        // recordings, 3 rows each (top quarter / center / bottom quarter —
		        // 16x4 blocks so one launch shows sky-vs-terrain depth variation
		        // and verifies the staging readback now matches (fix verification).
		        // The depth image is in DEPTH_ATTACHMENT_OPTIMAL here (depthOut
		        // barrier above): barrier → TRANSFER_SRC_OPTIMAL, 3 copies (DEPTH
		        // aspect), barrier back before the next frame's render pass.
		        static bool vkModDepthDirectSkipLogged = false;
		        static int vkModDepthDirectFrame = 0;
		        if (!realDepthBound && !g_vkModDepthDirectDone
		                && !vkModDepthDirectSkipLogged) {
		            vkModDepthDirectSkipLogged = true;
		            diagLog("REAL-DEPTH DIRECT READBACK: skipped (no real depth bound — dummy/constant-depth path)");
		        }
		        bool directProbeDue = realDepthBound && !g_vkModDepthDirectDone
		            && !g_vkModDepthDirectPending
		            && (g_vkModDepthDirectCount == 0
		                || (++vkModDepthDirectFrame % 60) == 0);
		        if (directProbeDue) {
		            if (g_vkModDepthDirectBuf == VK_NULL_HANDLE) {
		                // 3 rows x 16 wide x 4 tall x 4 bytes = 768 bytes.
		                VkBufferCreateInfo dbi{};
		                dbi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
		                dbi.size = 768;
		                dbi.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
		                dbi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
		                VkResult rres = vkCreateBuffer(g_vkModDevice, &dbi, nullptr, &g_vkModDepthDirectBuf);
		                if (rres == VK_SUCCESS) {
		                    VkMemoryRequirements rreqs;
		                    vkGetBufferMemoryRequirements(g_vkModDevice, g_vkModDepthDirectBuf, &rreqs);
		                    int rmt = findVkModMemoryType(rreqs.memoryTypeBits,
		                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
		                    if (rmt >= 0) {
		                        VkMemoryAllocateInfo ralloc{};
		                        ralloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		                        ralloc.allocationSize = rreqs.size;
		                        ralloc.memoryTypeIndex = static_cast<uint32_t>(rmt);
		                        rres = vkAllocateMemory(g_vkModDevice, &ralloc, nullptr, &g_vkModDepthDirectMem);
		                        if (rres == VK_SUCCESS) {
		                            rres = vkBindBufferMemory(g_vkModDevice, g_vkModDepthDirectBuf,
		                                g_vkModDepthDirectMem, 0);
		                            if (rres != VK_SUCCESS) {
		                                diagLog("REAL-DEPTH DIRECT READBACK: vkBindBufferMemory → %d", (int)rres);
		                                vkFreeMemory(g_vkModDevice, g_vkModDepthDirectMem, nullptr);
		                                g_vkModDepthDirectMem = VK_NULL_HANDLE;
		                                vkDestroyBuffer(g_vkModDevice, g_vkModDepthDirectBuf, nullptr);
		                                g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		                                g_vkModDepthDirectDone = true;
		                            }
		                        } else {
		                            diagLog("REAL-DEPTH DIRECT READBACK: vkAllocateMemory → %d", (int)rres);
		                            vkDestroyBuffer(g_vkModDevice, g_vkModDepthDirectBuf, nullptr);
		                            g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		                            g_vkModDepthDirectDone = true;
		                        }
		                    } else {
		                        diagLog("REAL-DEPTH DIRECT READBACK: no host-visible memory type");
		                        vkDestroyBuffer(g_vkModDevice, g_vkModDepthDirectBuf, nullptr);
		                        g_vkModDepthDirectBuf = VK_NULL_HANDLE;
		                        g_vkModDepthDirectDone = true;
		                    }
		                } else {
		                    diagLog("REAL-DEPTH DIRECT READBACK: vkCreateBuffer → %d", (int)rres);
		                    g_vkModDepthDirectDone = true;
		                }
		            }
		            if (g_vkModDepthDirectBuf != VK_NULL_HANDLE) {
		                VkImageMemoryBarrier dIn{};
		                dIn.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		                dIn.image = g_vkModDepthSrcImage;
		                dIn.oldLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
		                dIn.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		                dIn.srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
		                dIn.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		                dIn.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
		                dIn.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		                dIn.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		                vkCmdPipelineBarrier(cb,
		                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
		                        | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
		                    VK_PIPELINE_STAGE_TRANSFER_BIT,
		                    0, 0, nullptr, 0, nullptr, 1, &dIn);

		                // 3 rows: top quarter, center, bottom quarter. Each block
		                // is 16 wide x 4 tall at buffer offsets 0/256/512 bytes.
		                // Use the DEPTH IMAGE's own dims (mc main-target depth is
		                // native-res 1920x1201, not the pool's 1248x781).
		                int dw = g_vkModDepthSrcW > 0 ? g_vkModDepthSrcW : (int)w;
		                int dh = g_vkModDepthSrcH > 0 ? g_vkModDepthSrcH : (int)h;
		                int32_t rows[3] = { (int32_t)(dh >= 8 ? dh / 4 - 2 : 0),
		                    (int32_t)(dh >= 4 ? dh / 2 - 2 : 0),
		                    (int32_t)(dh >= 8 ? 3 * dh / 4 - 2 : 0) };
		                for (int i = 0; i < 3; i++) {
		                    VkBufferImageCopy bcopy{};
		                    bcopy.bufferOffset = (VkDeviceSize)(i * 256);
		                    bcopy.bufferRowLength = 0;
		                    bcopy.bufferImageHeight = 0;
		                    bcopy.imageSubresource = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1 };
		                    bcopy.imageOffset = { (int32_t)(dw >= 16 ? (dw - 16) / 2 : 0),
		                        rows[i], 0 };
		                    bcopy.imageExtent = { dw >= 16 ? 16u : (uint32_t)dw, dh >= 4 ? 4u : (uint32_t)dh, 1 };
		                    vkCmdCopyImageToBuffer(cb,
		                        g_vkModDepthSrcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		                        g_vkModDepthDirectBuf, 1, &bcopy);
		                }

		                VkImageMemoryBarrier dOut{};
		                dOut.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
		                dOut.image = g_vkModDepthSrcImage;
		                dOut.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
		                dOut.newLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
		                dOut.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
		                dOut.dstAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
		                dOut.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
		                dOut.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		                dOut.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
		                vkCmdPipelineBarrier(cb,
		                    VK_PIPELINE_STAGE_TRANSFER_BIT,
		                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
		                    0, 0, nullptr, 0, nullptr, 1, &dOut);

		                g_vkModDepthDirectPending = true;
		                g_vkModDepthDirectCount++;
		                diagLog("REAL-DEPTH DIRECT READBACK: recorded #%d (rows %d/%d/%d)",
		                    g_vkModDepthDirectCount, rows[0], rows[1], rows[2]);
		            }
		        }

		        // 4) imported: TRANSFER_DST_OPTIMAL → GENERAL, made visible to the
		        // D3D12 side via shared memory (MEMORY_READ_BIT, ALL_COMMANDS dst —
		        // same pattern as recordFrameCopyOps barrier 6/7).
		        VkImageMemoryBarrier importedOut[3]{};
		        for (int i = 0; i < 3; i++) {
		            importedOut[i] = importedBar[i];
		            importedOut[i].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
		            importedOut[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
		            importedOut[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
		            importedOut[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_MEMORY_READ_BIT;
		        }
		        vkCmdPipelineBarrier(cb,
		            VK_PIPELINE_STAGE_TRANSFER_BIT,
		            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		            0, 0, nullptr, 0, nullptr, 3, importedOut);

		        static int mvGenCounter = 0;
		        if ((++mvGenCounter & 127) == 1) {
		            diagLog("recordVkModMvGen: dispatch %dx%d (real FSR4 inputs)",
		                (int)w, (int)h);
		        }
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("recordVkModMvGen: SEH exception");
		    }
		}

		// Sets the per-frame MV-gen matrices (invCurrVP + prevVP) from Java.
		// Called in onRenderBegin before the dispatch block; the main-pass mixin
		// consumes them in recordVkModMvGen later the same frame.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_setVkModMvGenMatrices(
		    JNIEnv* env, jclass, jfloatArray invCurrVP, jfloatArray prevVP)
		{
		    __try {
		        if (!invCurrVP || !prevVP) return;
		        jsize n1 = env->GetArrayLength(invCurrVP);
		        jsize n2 = env->GetArrayLength(prevVP);
		        if (n1 < 16 || n2 < 16) return;
		        env->GetFloatArrayRegion(invCurrVP, 0, 16, g_vkModMvGenPush);       // offset 0
		        env->GetFloatArrayRegion(prevVP, 0, 16, g_vkModMvGenPush + 16);   // offset 64
		        g_vkModMvGenMatricesSet = true;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        g_vkModMvGenMatricesSet = false;
		    }
		}

		// CAM-MOTION PLUMB (2026-08-08 14:24, RECOVERED 2026-08-18): the
		// no-real-depth shader mode gets a CPU translation signal instead of the
		// FOV/near-plane-fragile parallax heuristic. Writes eye speed (blocks/sec,
		// dt-normalized — walk ≈ 4.3, sprint ≈ 5.6, idle bob ≈ 0.13) + rotation
		// delta (rad/frame) into g_vkModMvGenPush[32]/[33] (offsets 128/132),
		// consumed by the shader's translation reactive ramp
		// (smoothstep(0.3, 1.5, uCamMotion)) and the cutout motion gate. Called
		// every frame from FSRProcessor's VKMod block (after the matrices).
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_setVkModCamMotion(
		    JNIEnv*, jclass, jfloat eyeSpeed, jfloat rotDelta)
		{
		    __try {
		        g_vkModMvGenPush[32] = eyeSpeed;   // offset 128 — uCamMotion
		        g_vkModMvGenPush[33] = rotDelta;   // offset 132 — uCamRotDelta
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        // Never fatal; the shader reads 0 (accumulation-friendly) on failure.
		    }
		}

		// FSR4 SDK internal sharpening (2026-08-13, RECOVERED 2026-08-18): trivial
		// float store into Fsr4Context::g_fsr4Sharpness, applied to the dispatch
		// desc in fsr4_context.cpp (enableSharpening + sharpness). Called every
		// frame from FSRProcessor.syncNativeDiagnostics so in-game slider changes
		// propagate.
		JNIEXPORT void JNICALL
		Java_com_fsr2mod_vulkan_Fsr4Native_setSharpness(JNIEnv*, jclass, jfloat sharpness)
		{
		    __try {
		        fsr::Fsr4Context::g_fsr4Sharpness = sharpness;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		    }
		}

		// Generic importer: aliases any D3D12 pool texture as a VkImage + memory
		// (mirror of importInputFromD3D12Pool, parameterized by format/usage/globals
		// via the slot argument). slot: 0=depth(pool idx 1), 1=mv(idx 2), 2=reactive(idx 3).
		JNIEXPORT jboolean JNICALL
		Java_com_fsr2mod_vulkan_VulkanInterop_importPoolTextureAsVkImage(
		    JNIEnv*, jclass, jlong poolPtr, jint poolIndex, jint slot, jint format)
		{
		    __try {
		        if (!g_vkModDeviceSet) {
		            diagLog("importPoolTextureAsVkImage: VulkanMod device not configured");
		            return JNI_FALSE;
		        }
		        if (!g_vkModGetMemWin32HandleProps) {
		            diagLog("importPoolTextureAsVkImage: vkGetMemoryWin32HandlePropertiesKHR not available");
		            return JNI_FALSE;
		        }
		        if (!poolPtr) {
		            diagLog("importPoolTextureAsVkImage: null pool pointer");
		            return JNI_FALSE;
		        }
		        if (poolIndex < 0 || poolIndex >= fsr::D3D12SharedResources::COUNT) {
		            diagLog("importPoolTextureAsVkImage: invalid pool index %d", (int)poolIndex);
		            return JNI_FALSE;
		        }
		        if (slot < 0 || slot > 2) {
		            diagLog("importPoolTextureAsVkImage: invalid slot %d", (int)slot);
		            return JNI_FALSE;
		        }
		        if (format <= 0) {
		            diagLog("importPoolTextureAsVkImage: invalid VkFormat %d", (int)format);
		            return JNI_FALSE;
		        }

		        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
		        HANDLE d3d12Handle = pool->win32Handles[poolIndex];
		        uint64_t allocSize = pool->allocationSizes[poolIndex];
		        ID3D12Resource* d3d12Res = pool->resources[poolIndex];
		        if (!d3d12Handle || !d3d12Res) {
		            diagLog("importPoolTextureAsVkImage: pool resource/handle not ready for index %d", poolIndex);
		            return JNI_FALSE;
		        }

		        VkImage* imgPtr = nullptr;
		        VkDeviceMemory* memPtr = nullptr;
		        ID3D12Resource** d3dPtr = nullptr;
		        bool* openedPtr = nullptr;
		        VkImageView* viewPtr = nullptr;
		        const char* name = "?";
		        switch (slot) {
		            case 0: imgPtr = &g_vkModDepthImage; memPtr = &g_vkModDepthMemory;
		                    d3dPtr = &g_vkModD3D12Depth; openedPtr = &g_vkModDepthOpened;
		                    viewPtr = &g_vkModDepthView; name = "DEPTH"; break;
		            case 1: imgPtr = &g_vkModMvImage; memPtr = &g_vkModMvMemory;
		                    d3dPtr = &g_vkModD3D12Mv; openedPtr = &g_vkModMvOpened;
		                    viewPtr = &g_vkModMvView; name = "MV"; break;
		            case 2: imgPtr = &g_vkModReactiveImage; memPtr = &g_vkModReactiveMemory;
		                    d3dPtr = &g_vkModD3D12Reactive; openedPtr = &g_vkModReactiveOpened;
		                    viewPtr = &g_vkModReactiveView; name = "REACTIVE"; break;
		        }

		        diagLog("importPoolTextureAsVkImage: importing pool[%d] %s handle=%p size=%llu fmt=%d",
		            (int)poolIndex, name, (void*)d3d12Handle, (unsigned long long)allocSize, (int)format);

		        // Destroy existing import for this slot
		        if (*imgPtr != VK_NULL_HANDLE) { vkDestroyImage(g_vkModDevice, *imgPtr, nullptr); *imgPtr = VK_NULL_HANDLE; }
		        if (*memPtr != VK_NULL_HANDLE) { vkFreeMemory(g_vkModDevice, *memPtr, nullptr); *memPtr = VK_NULL_HANDLE; }
		        if (*viewPtr != VK_NULL_HANDLE) { vkDestroyImageView(g_vkModDevice, *viewPtr, nullptr); *viewPtr = VK_NULL_HANDLE; }
		        if (*d3dPtr) { (*d3dPtr)->Release(); *d3dPtr = nullptr; }
		        *openedPtr = false;
		        // Pipeline must be rebuilt after any import change
		        g_vkModMvGenReady = false;

		        VkExternalMemoryImageCreateInfo extImgInfo{};
		        extImgInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
		        extImgInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

		        VkImageCreateInfo imgInfo{};
		        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
		        imgInfo.imageType = VK_IMAGE_TYPE_2D;
		        imgInfo.format = static_cast<VkFormat>(format);
		        imgInfo.extent = { g_vkModImageWidth, g_vkModImageHeight, 1 };
		        imgInfo.mipLevels = 1;
		        imgInfo.arrayLayers = 1;
		        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
		        imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
		        imgInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
		        imgInfo.pNext = &extImgInfo;

		        VkResult res = vkCreateImage(g_vkModDevice, &imgInfo, nullptr, imgPtr);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkCreateImage(%s) → %d", name, res);
		            *imgPtr = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        VkMemoryWin32HandlePropertiesKHR handleProps{};
		        handleProps.sType = VK_STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR;
		        res = g_vkModGetMemWin32HandleProps(g_vkModDevice,
		            VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT, d3d12Handle, &handleProps);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkGetMemoryWin32HandlePropertiesKHR(%s) → %d", name, res);
		            vkDestroyImage(g_vkModDevice, *imgPtr, nullptr);
		            *imgPtr = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        int memType = findVkModMemoryType(handleProps.memoryTypeBits,
		            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		        if (memType < 0) {
		            diagLog("  FAIL: no compatible DEVICE_LOCAL memory type (%s)", name);
		            vkDestroyImage(g_vkModDevice, *imgPtr, nullptr);
		            *imgPtr = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        VkImportMemoryWin32HandleInfoKHR importInfo{};
		        importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR;
		        importInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
		        importInfo.handle = d3d12Handle;

		        VkMemoryAllocateInfo allocInfo{};
		        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
		        allocInfo.allocationSize = allocSize;
		        allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
		        allocInfo.pNext = &importInfo;

		        res = vkAllocateMemory(g_vkModDevice, &allocInfo, nullptr, memPtr);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkAllocateMemory(%s) → %d", name, res);
		            vkDestroyImage(g_vkModDevice, *imgPtr, nullptr);
		            *imgPtr = VK_NULL_HANDLE;
		            *memPtr = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        res = vkBindImageMemory(g_vkModDevice, *imgPtr, *memPtr, 0);
		        if (res != VK_SUCCESS) {
		            diagLog("  FAIL: vkBindImageMemory(%s) → %d", name, res);
		            vkFreeMemory(g_vkModDevice, *memPtr, nullptr);
		            *memPtr = VK_NULL_HANDLE;
		            vkDestroyImage(g_vkModDevice, *imgPtr, nullptr);
		            *imgPtr = VK_NULL_HANDLE;
		            return JNI_FALSE;
		        }

		        // Keep-alive AddRef for the D3D12 resource (released in destroyVkModImages)
		        d3d12Res->AddRef();
		        *d3dPtr = d3d12Res;
		        *openedPtr = true;

		        diagLog("importPoolTextureAsVkImage: SUCCESS — %s image=%p memory=%p d3d12=%p",
		            name, (void*)*imgPtr, (void*)*memPtr, (void*)*d3dPtr);
		        return JNI_TRUE;
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("importPoolTextureAsVkImage: SEH exception code=0x%08lx", GetExceptionCode());
		        return JNI_FALSE;
		    }
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
	
		static jboolean dispatchFsr4VkModHelper(
		    JNIEnv* env,
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
			
				        // NOTE: No copy from VK input → pool color is needed here.
				        // The INPUT VkImage (g_vkModInputImage) is backed by the pool's
				        // color texture memory (imported D3D12→VK), so g_vkModD3D12Input
				        // IS the pool color resource. The scene was already copied into
				        // it by recordFrameCopyOps (swapchain → g_vkModInputImage).
				        // The pool color texture has D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS,
				        // satisfying the FSR4 SDK's requirement for UAV-capable input textures.

				        fsr::Fsr4DispatchParams params{};
				        params.color         = g_vkModD3D12Input;
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

				        if (g_debugBypassFsr4) {  // DEBUG: bypass FSR4 SDK � use CopyResource
				            // PIPELINE TEST: Skip FSR4 SDK, just CopyResource from pool
				            // color → pool output. This validates the D3D12→VK shared
				            // memory + VK copy → swapchain path. If the user sees the
				            // scene with this test, the pipeline is functional and the
				            // bug is in the FSR4 SDK processing.
				            diagLog("dispatchFsr4VkMod: PIPELINE TEST — bypassing FSR4, "
				                "CopyResource color=%p → output=%p",
				                (void*)params.color, (void*)params.output);
				
				                // CRITICAL FIX (2026-08-05): the pipeline-test bypass recorded into
				                // the D3D12Device round-robin command list WITHOUT the allocator
				                // protocol that Fsr4Context::dispatch uses. It never waited for the
				                // OTHER allocator's fence, never Reset() the current allocator, and
				                // never re-bound the command list after advanceAllocator() flipped
				                // allocators. Close() on an already-closed list then ExecuteCommandLists
				                // re-submitted the PREVIOUS frame's command buffer — including pool
				                // resource pointers that were freed on recreate — while the allocator
				                // memory was still in flight: D3D12 use-after-free -> ntdll heap
				                // corruption (c0000374) detected at the next big allocation (opening
				                // the settings screen). Replicate the dispatch protocol exactly:
				                uint64_t bypassWaitFence = dev.otherAllocatorFenceValue();
				                if (bypassWaitFence > 0
				                        && dev.fence()->GetCompletedValue() < bypassWaitFence) {
				                    if (!dev.waitForFenceValue(bypassWaitFence, 2000)) {
				                        diagLog("dispatchFsr4VkMod: PIPELINE TEST allocator fence wait "
				                                "timed out (val=%llu) — skipping frame",
				                                (unsigned long long)bypassWaitFence);
				                        return JNI_FALSE;
				                    }
				                }
				                HRESULT bypassResetHr = dev.commandAllocator()->Reset();
				                if (FAILED(bypassResetHr)) {
				                    diagLog("dispatchFsr4VkMod: PIPELINE TEST allocator Reset FAILED "
				                            "hr=0x%08lx (GPU still in flight?) — skipping frame", bypassResetHr);
				                    return JNI_FALSE;
				                }
				                bypassResetHr = dev.commandList()->Reset(dev.commandAllocator(), nullptr);
				                if (FAILED(bypassResetHr)) {
				                    diagLog("dispatchFsr4VkMod: PIPELINE TEST command list Reset FAILED "
				                            "hr=0x%08lx — skipping frame", bypassResetHr);
				                    return JNI_FALSE;
				                }

					            // Barrier 1: source (pool color) COMMON → COPY_SOURCE
					            // Forces the D3D12 driver to invalidate read cache for the
					            // shared memory region that was written by VK (Copy1).
					            D3D12_RESOURCE_BARRIER srcBarrier{};
					            srcBarrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
					            srcBarrier.Transition.pResource = params.color;
					            srcBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
					            srcBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_SOURCE;
					            srcBarrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;

					            // Barrier 2: dest (pool output) COMMON → COPY_DEST
					            D3D12_RESOURCE_BARRIER dstBarrier{};
					            dstBarrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
					            dstBarrier.Transition.pResource = params.output;
					            dstBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
					            dstBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_DEST;
					            dstBarrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;

					            D3D12_RESOURCE_BARRIER barriers[2] = { srcBarrier, dstBarrier };
					            dev.commandList()->ResourceBarrier(2, barriers);

					            dev.commandList()->CopyResource(params.output, params.color);

					            // Restore both to COMMON
					            srcBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_SOURCE;
					            srcBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
					            dstBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_DEST;
					            dstBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
					            barriers[0] = srcBarrier;
					            barriers[1] = dstBarrier;
					            dev.commandList()->ResourceBarrier(2, barriers);

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
				        diagLog("dispatchFsr4VkMod: dispatch → %s", ok ? "OK" : "FAILED");		        return ok ? JNI_TRUE : JNI_FALSE;
	    } catch (const std::exception& e) {
	        diagLog("dispatchFsr4VkMod: exception: %s", e.what());
	        return JNI_FALSE;
	    }
		}

		// JNI entry — SEH-wrapped (parity with Fsr4Native.dispatchFsr4): an access
		// violation anywhere in the D3D12 pool deref / allocator wait / Reset /
		// barrier record / ExecuteCommandLists during GPU saturation returns
		// JNI_FALSE (Java's dispatch-failure suspension takes over) instead of
		// killing the process. The helper holds all C++ objects (lambda, params
		// struct) — MSVC __try cannot contain code requiring C++ unwinding.
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
		    __try {
		        return dispatchFsr4VkModHelper(env,
		            jitterX, jitterY,
		            cameraFar, cameraNear, cameraFovY,
		            deltaTime, frameIndex, reset,
		            depthPoolPtr, depthIndex,
		            mvPoolPtr, mvIndex,
		            reactivePoolPtr, reactiveIndex,
		            outputPoolPtr, outputIndex);
		    } __except (EXCEPTION_EXECUTE_HANDLER) {
		        diagLog("[VulkanInterop] dispatchFsr4VkMod: SEH crash (code=0x%08lx) — "
		                "returning JNI_FALSE (FSR4 suspension takes over)", GetExceptionCode());
		        return JNI_FALSE;
		    }
		}

// ---- FSR3 Frame Generation (Fg4Native) ----

namespace {
    fsr::Fg4Context* g_fg4Context = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fg4Native_nativeInit(
    JNIEnv* env, jclass,
    jint renderW, jint renderH, jint displayW, jint displayH,
    jlong poolPtr, jint colorIndex, jint depthIndex, jint mvIndex, jint outputIndex,
    jint hudlessColorIndex,
    jstring fgDllDir)
{
    try {
        if (g_fg4Context) {
            g_fg4Context->destroy();
            delete g_fg4Context;
            g_fg4Context = nullptr;
        }

        // Convert Java String -> UTF-16 wide string for LoadLibraryExW
        std::wstring dllDir;
        if (fgDllDir) {
            const jchar* chars = env->GetStringChars(fgDllDir, nullptr);
            if (chars) {
                dllDir.assign(chars, chars + env->GetStringLength(fgDllDir));
                env->ReleaseStringChars(fgDllDir, chars);
            }
        }

        auto* pool = reinterpret_cast<fsr::D3D12SharedResources*>(poolPtr);
        g_fg4Context = new fsr::Fg4Context();
        bool ok = g_fg4Context->initialize(
            static_cast<uint32_t>(renderW), static_cast<uint32_t>(renderH),
            static_cast<uint32_t>(displayW), static_cast<uint32_t>(displayH),
            pool, colorIndex, depthIndex, mvIndex, outputIndex,
            hudlessColorIndex,
            dllDir.empty() ? nullptr : dllDir.c_str());
        if (!ok) {
            delete g_fg4Context;
            g_fg4Context = nullptr;
        }
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fg4Native_nativePrepare(
    JNIEnv* env, jclass,
    jlong frameID, jlong frameTimeDeltaNs,
    jfloat jitterX, jfloat jitterY,
    jfloat cameraNear, jfloat cameraFar, jfloat cameraFovYRad,
    jfloat viewSpaceToMetersFactor,
    jfloat camPosX, jfloat camPosY, jfloat camPosZ,
    jfloat camUpX, jfloat camUpY, jfloat camUpZ,
    jfloat camRightX, jfloat camRightY, jfloat camRightZ,
    jfloat camForwardX, jfloat camForwardY, jfloat camForwardZ,
    jboolean reset)
{
    try {
        if (!g_fg4Context || !g_fg4Context->isInitialized()) return JNI_FALSE;

        fsr::Fg4PrepareParams params{};
        params.frameID = static_cast<uint64_t>(frameID);
        // Convert ns -> ms (SDK expects milliseconds)
        params.frameTimeDeltaMs = static_cast<float>(frameTimeDeltaNs) / 1e6f;
        params.jitterX = jitterX;
        params.jitterY = jitterY;
        params.cameraNear = cameraNear;
        params.cameraFar = cameraFar;
        params.cameraFovAngleVertical = cameraFovYRad;
        params.viewSpaceToMetersFactor = viewSpaceToMetersFactor;
        params.cameraPosition[0] = camPosX;  params.cameraPosition[1] = camPosY;  params.cameraPosition[2] = camPosZ;
        params.cameraUp[0]       = camUpX;   params.cameraUp[1]       = camUpY;   params.cameraUp[2]       = camUpZ;
        params.cameraRight[0]    = camRightX; params.cameraRight[1]    = camRightY; params.cameraRight[2]    = camRightZ;
        params.cameraForward[0]  = camForwardX; params.cameraForward[1] = camForwardY; params.cameraForward[2] = camForwardZ;
        params.reset = reset == JNI_TRUE;

        bool ok = g_fg4Context->prepare(params);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fg4Native_nativeGenerate(
    JNIEnv* env, jclass, jint numGeneratedFrames)
{
    try {
        if (!g_fg4Context || !g_fg4Context->isInitialized()) return JNI_FALSE;
        return g_fg4Context->generate(static_cast<uint32_t>(numGeneratedFrames)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fg4Native_nativeFinish(
    JNIEnv*, jclass)
{
    if (g_fg4Context) {
        return g_fg4Context->finish() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;  // nothing to wait for — success
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fg4Native_nativeShutdown(
    JNIEnv* env, jclass)
{
    try {
        if (g_fg4Context) {
            g_fg4Context->destroy();
            delete g_fg4Context;
            g_fg4Context = nullptr;
        }
    } catch (const std::exception& e) {
        throwJavaException(env, e);
    }
}

} // extern "C"
