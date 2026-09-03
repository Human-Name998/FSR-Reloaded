# Task 3: JNI bridge + Java NpuUpscale class

## Status: DONE

## Files Created
- `src/client/java/com/fsr2mod/fsr/NpuUpscale.java` — Java JNI bridge class with `init()`, `dispatch()`, `shutdown()`, `isAvailable()` methods and native declarations for `nativeInit`, `nativeDispatch`, `nativeShutdown`. Loads `fsrvk` native library in static initializer.

## Files Modified
- `src/native/fsrvk/jni_bridge.cpp` — Two changes:
  1. Added `#include "npu_device.h"` and `#include "npu_upscale.h"` near the top (after existing includes, before `#ifdef _WIN32`). `d3d12_device.h` was already included.
  2. Added three JNI entry points before the closing `} // extern "C"`:
     - `Java_com_fsr2mod_fsr_NpuUpscale_nativeInit` — creates NPU/DML devices via `npuInit()`, loads ONNX model via `npuUpscaleInit()`
     - `Java_com_fsr2mod_fsr_NpuUpscale_nativeDispatch` — dispatches NPU upscale via `npuUpscaleDispatch()`
     - `Java_com_fsr2mod_fsr_NpuUpscale_nativeShutdown` — calls `npuUpscaleShutdown()` and `npuShutdown()`

## Notes
- The Java interface matches the brief: `init(long gpuDevicePtr, String modelPath)` — `dmlDevicePtr` is obtained internally on the native side via `npuInit()`.
- `diagLog()` already exists in jni_bridge.cpp (no duplicate).
- `#include "d3d12_device.h"` was already present (no duplicate).
- The NPU JNI functions were placed inside the existing `extern "C"` block (not a new one).

---

## Bugfixes (commit c1c315b)

### Bug 1: Null modelPathJ in nativeInit
Added null guard for `modelPathJ` before calling `GetStringUTFChars` to prevent JNI crash when Java passes null.

### Bug 2: Null inputPtr/outputPtr in nativeDispatch
Added null pointer guard at the start of `nativeDispatch` — returns `JNI_FALSE` with diagnostic log instead of crashing on null pointer dereference.

### Bug 3: Thread safety on Java static state
- Changed `initialized` field to `volatile` for visibility across threads.
- Added `synchronized` to `init()` and `shutdown()` method signatures to prevent concurrent initialization/shutdown races.
