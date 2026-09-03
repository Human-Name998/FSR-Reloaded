### Task 3: JNI bridge + Java NpuUpscale class

**Files:**
- Create: `src/client/java/com/fsr2mod/fsr/NpuUpscale.java`
- Modify: `src/native/fsrvk/jni_bridge.cpp` (add JNI entry points)

**Interfaces:**
- Produces: `Java NpuUpscale.init()` → `native boolean npuInit(long gpuDevicePtr, long dmlDevicePtr, String modelPath)`
- Produces: `Java NpuUpscale.dispatch()` → `native boolean npuDispatch(long inputPtr, long outputPtr, int width, int height)`
- Produces: `Java NpuUpscale.shutdown()` → `native void npuShutdown()`

- [ ] **Step 1: Write NpuUpscale.java**

```java
package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import org.slf4j.Logger;

/**
 * JNI bridge to NPU upscaling (XDNA 2 DirectML + FSRCNN on ONNX Runtime).
 *
 * Separate from Fsr4Native — NPU mode is a standalone upscaling option.
 * All native methods return false / throw on failure; caller must check.
 */
public class NpuUpscale {
    private static final Logger LOGGER = FSRMod.LOGGER;
    private static boolean nativeLoaded;
    private static boolean initialized;

    // Called once from FSRProcessor when NPU mode is selected
    public static boolean init(long gpuDevicePtr, String modelPath) {
        if (initialized) return true;
        if (!nativeLoaded) {
            LOGGER.warn("[NPU] Native library not loaded");
            return false;
        }
        try {
            boolean ok = nativeInit(gpuDevicePtr, modelPath);
            if (ok) {
                initialized = true;
                LOGGER.info("[NPU] Initialized successfully");
            } else {
                LOGGER.warn("[NPU] Initialization failed — no NPU adapter?");
            }
            return ok;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[NPU] JNI link error: {}", e.getMessage());
            return false;
        }
    }

    // Per-frame: upscale input texture → output texture on NPU
    public static boolean dispatch(long inputPtr, long outputPtr, int width, int height) {
        if (!initialized) return false;
        try {
            return nativeDispatch(inputPtr, outputPtr, width, height);
        } catch (Exception e) {
            LOGGER.error("[NPU] Dispatch error: {}", e.getMessage());
            return false;
        }
    }

    // Shutdown: release ONNX session + NPU devices
    public static void shutdown() {
        if (!initialized) return;
        try {
            nativeShutdown();
        } catch (Exception e) {
            LOGGER.warn("[NPU] Shutdown error: {}", e.getMessage());
        }
        initialized = false;
    }

    public static boolean isAvailable() {
        return initialized;
    }

    // Native declarations
    private static native boolean nativeInit(long gpuDevicePtr, String modelPath);
    private static native boolean nativeDispatch(long inputPtr, long outputPtr, int width, int height);
    private static native void nativeShutdown();

    static {
        try {
            System.loadLibrary("fsrvk");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[NPU] Failed to load fsrvk.dll: {}", e.getMessage());
            nativeLoaded = false;
        }
    }
}
```

- [ ] **Step 2: Add JNI entry points in jni_bridge.cpp**

Find the end of the existing JNI functions (before the last closing brace) and add:

```cpp
// ============= NPU Upscale JNI =============

#include "npu_device.h"
#include "npu_upscale.h"
#include "d3d12_device.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_fsr_NpuUpscale_nativeInit(JNIEnv* env, jclass,
    jlong gpuDevicePtr, jstring modelPathJ) {
    if (!gpuDevicePtr) return JNI_FALSE;
    
    ID3D12Device* gpuDevice = reinterpret_cast<ID3D12Device*>(gpuDevicePtr);
    
    // Step 1: Find NPU adapter + create devices
    ID3D12Device* npuDevice = nullptr;
    IDMLDevice* dmlDevice = nullptr;
    if (!npuInit(gpuDevice, &npuDevice, &dmlDevice)) {
        diagLog("NPU init: no NPU adapter found");
        return JNI_FALSE;
    }
    diagLog("NPU init: devices created (NPU=%p, DML=%p)", 
        (void*)npuDevice, (void*)dmlDevice);
    
    // Step 2: Load ONNX model
    const char* modelPath = env->GetStringUTFChars(modelPathJ, nullptr);
    bool ok = npuUpscaleInit(npuDevice, dmlDevice, modelPath);
    env->ReleaseStringUTFChars(modelPathJ, modelPath);
    
    if (!ok) {
        diagLog("NPU init: model load failed");
        npuShutdown();
        return JNI_FALSE;
    }
    
    diagLog("NPU init: SUCCESS");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_fsr_NpuUpscale_nativeDispatch(JNIEnv* env, jclass,
    jlong inputPtr, jlong outputPtr, jint width, jint height) {
    
    ID3D12Resource* input = reinterpret_cast<ID3D12Resource*>(inputPtr);
    ID3D12Resource* output = reinterpret_cast<ID3D12Resource*>(outputPtr);
    
    bool ok = npuUpscaleDispatch(input, output, width, height);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_fsr_NpuUpscale_nativeShutdown(JNIEnv* env, jclass) {
    npuUpscaleShutdown();
    npuShutdown();
    diagLog("NPU shutdown");
}

} // extern "C"
```

- [ ] **Step 3: Commit**

```bash
cd /c/Users/arshjot/Downloads/fsr2mod
git add src/client/java/com/fsr2mod/fsr/NpuUpscale.java src/native/fsrvk/jni_bridge.cpp
git commit -m "npu: JNI bridge + NpuUpscale.java"
```

---

