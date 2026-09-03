# Task 5: Fsr4Context + Fsr4Native JNI Bridge

**Status**: ✅ Complete

**Commits**:
- `744268c` — Phase 3 Task 5: Fsr4Context + Fsr4Native JNI bridge

## Files Created
1. `src/native/fsrvk/fsr4_context.h` — C++ `Fsr4Context` class wrapping `ffxFsr3CreateContext`/`ffxFsr3UpscaleRenderSubmit` (FSR4 type). Includes `Fsr4RenderDesc` struct for per-frame parameters (FfxResource handles + camera/jitter/timing data).
2. `src/native/fsrvk/fsr4_context.cpp` — Implementation:
   - `initialize()`: Gets VulkanDevice, builds backend via `ffxGetInterfaceVulkanVk()`, creates FSR4 context with `FFX_FSR3_UPSCALER_ENABLE_DEPTH_INVERTED | FFX_FSR3_UPSCALER_ENABLE_LOW_DYNAMIC_RANGE` flags, `FFX_FSR3_UPSCALER_TYPE_FSR4`
   - `dispatch()`: Calls `ffxFsr3UpscaleRenderSubmit()` with FfxFsr3UpscaleRenderDesc (stub — populated with real fields when SDK is available)
   - `waitIdle()`: `vkDeviceWaitIdle()`
   - `destroy()`: `ffxFsr3ContextDestroy()`
3. `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java` — Java JNI bridge:
   - Static initializer extracts and loads `amd_fsr4_api.dll` from JAR
   - No `SetDllDirectory` (unavailable in LWJGL 3.3.3's Kernel32 — uses simple `System.load()`)
   - No semaphore methods (`getReadySemaphoreHandle`/`getCompleteSemaphoreHandle`) — Hybrid Command-Buffer Flush sync approach

## Files Modified
4. `src/native/fsrvk/jni_bridge.cpp` — Added 4 JNI entry points:
   - `Java_com_fsr2mod_vulkan_Fsr4Native_initFsr4` — creates `g_fsr4Context`, calls `initialize()`
   - `Java_com_fsr2mod_vulkan_Fsr4Native_dispatchFsr4` — casts jlong → VkImage, builds `FfxResource` via `ffxGetResourceVulkan()`, fills `Fsr4RenderDesc`, calls `dispatch()`
   - `Java_com_fsr2mod_vulkan_Fsr4Native_waitIdle` — delegates to `Fsr4Context::waitIdle()`
   - `Java_com_fsr2mod_vulkan_Fsr4Native_shutdownFsr4` — `destroy()` + `delete` + nullify pointer
5. `src/native/fsrvk/CMakeLists.txt` — Added `fsr4_context.cpp` to `add_library`

## Build Test
- `.\gradlew.bat build` — **BUILD SUCCESSFUL** (3s, 7 actions)
