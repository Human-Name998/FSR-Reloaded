# NPU-Powered Game Upscaling — Design Document

## 1. Goal

Prove that the **NPU (Neural Processing Unit)** can replace the GPU for real-time game upscaling, delivering equivalent visual quality at **significantly lower power consumption**. Target: 100fps+ with NPU upscale latency under 3ms per frame.

## 2. Architecture (Approach A)

**Pure NPU upscaling.** GPU renders at low resolution, NPU upscales via DirectML + FSRCNN, GPU presents. No temporal accumulation, no MV gen — the NPU handles all upscaling work, making the power savings argument clean.

```
┌─────────────────────────────────────────────────────┐
│ Frame N                                              │
│                                                      │
│  GPU: Scene renders at scaled res (e.g. 1080p)       │
│  GPU: Copy1 → pool_color (shared VK→D3D12, exists)  │
│  GPU: Signal copy-complete                           │
│                                                      │
│  NPU: OpenSharedHandle → import pool_color/output    │
│  NPU: Bind color → FSRCNN model → output             │
│  NPU: Signal upscale-complete                        │
│                                                      │
│  GPU: Wait for NPU fence                             │
│  GPU: Copy2 pool_output → swapchain (exists)         │
│  GPU: Present                                        │
└─────────────────────────────────────────────────────┘
```

### 2.1 Data flow detail

1. **GPU render**: Minecraft scene renders at `scaledWidth × scaledHeight` (e.g. 1920×1080 target → 1280×720 render). Scene writes to the swapchain via VulkanMod.
2. **Copy1** (existing): `recordFrameCopyOps` copies swapchain → `g_vkModInputImage` (VkImage backed by D3D12 pool_color via shared memory). Done.
3. **NPU import (init only)**: During initialization, NPU D3D12 device opens `pool_color` and `pool_output` handles via `OpenSharedHandle`. These are cached as `ID3D12Resource` bindings for DirectML tensors. **Not repeated per frame.**
4. **NPU dispatch (per frame)**: ONNX Runtime session (backed by DirectML EP on NPU) runs FSRCNN:
   - Input tensor = `pool_color` (RGBA8, low res)
   - Output tensor = `pool_output` (RGBA8, native res)
   - `session.Run()` dispatches compute to the NPU command queue
   - NPU queue signals completion fence when done
5. **GPU wait**: D3D12 GPU device waits on the NPU fence before consuming the result.
6. **Copy2** (existing): `recordFrameCopyOps` copies `g_vkModOutputImage` (pool_output) → swapchain. Done.

### 2.2 NPU adapter discovery

```cpp
// Enumerate DXGI adapters, find the NPU
// Strategy: find the adapter that is NOT the GPU, NOT software,
// and supports D3D12 with D3D_FEATURE_LEVEL_1_0_CORE (MCDM).
// AMD XDNA 2: VendorId=0x1002, DeviceId matches NPU hardware

ComPtr<IDXGIFactory4> factory;
CreateDXGIFactory2(0, IID_PPV_ARGS(&factory));

// Step 1: Find the GPU adapter first (we already have the D3D12 device)
ComPtr<ID3D12Device> gpuDevice = /* existing */;
DXGI_ADAPTER_DESC gpuDesc;
gpuDevice->GetDesc(&gpuDesc); // save GPU adapter LUID for exclusion

// Step 2: Enumerate, find NPU
ComPtr<IDXGIAdapter1> adapter;
UINT i = 0;
while (factory->EnumAdapters1(i++, &adapter) != DXGI_ERROR_NOT_FOUND) {
    DXGI_ADAPTER_DESC1 desc;
    adapter->GetDesc1(&desc);
    
    // Skip the GPU (same LUID)
    if (desc.AdapterLuid.HighPart == gpuDesc.AdapterLuid.HighPart &&
        desc.AdapterLuid.LowPart == gpuDesc.AdapterLuid.LowPart) continue;
    // Skip software adapters (WARP, etc.)
    if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;
    
    // Try creating D3D12 MCDM device (feature level 1_0_CORE)
    ComPtr<ID3D12Device> npuDevice;
    if (SUCCEEDED(D3D12CreateDevice(adapter.Get(),
            D3D_FEATURE_LEVEL_1_0_CORE, IID_PPV_ARGS(&npuDevice)))) {
        // This is the NPU — XDNA 2 with MCDM driver
        // Verify DirectML support
        IDMLDevice* dmlDevice;
        if (SUCCEEDED(DMLCreateDevice(npuDevice.Get(),
                DML_CREATE_DEVICE_FLAG_NONE, IID_PPV_ARGS(&dmlDevice)))) {
            // Found the NPU! Use for upscaling dispatch.
        }
    }
}
```

AMD's XDNA 2 NPU exposes itself as a **MCDM (Microsoft Compute Driver Model)** adapter — a D3D12 device with `D3D_FEATURE_LEVEL_1_0_CORE` (compute-only, no graphics). The NPU shows up as a separate DXGI adapter alongside the Radeon 860M GPU.

### 2.3 Shared resource opening on NPU

The pool textures (color + output) are allocated on the GPU D3D12 device with `D3D12_HEAP_FLAG_SHARED`. The NPU D3D12 device opens them via:

```cpp
// GPU device creates shared handle (already done in allocateFsr4Pool)
HANDLE sharedHandle = ...; // already created for pool textures

// NPU device opens the same resource
ComPtr<ID3D12Resource> npuColor;
npuDevice->OpenSharedHandle(sharedHandle, IID_PPV_ARGS(&npuColor));
```

Same pattern as the VK→D3D12 import, but for D3D12→D3D12 across different adapters.

### 2.4 DirectML model dispatch (ONNX Runtime path)

We use **ONNX Runtime with DirectML Execution Provider** — not raw DirectML API. ORT handles model loading, operator compilation, and resource binding. Under the hood it calls DirectML which dispatches to the NPU's MCDM driver.

```cpp
// One-time init during NPU enable
Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "NPU-Upscale");
Ort::SessionOptions sessionOpts;
Ort::ThrowOnError(OrtSessionOptionsAppendExecutionProvider_DML(
    sessionOpts, npuDevice.Get()));  // DirectML EP → NPU adapter

// Load model from embedded ONNX blob
Ort::Session session(env, modelData, modelSize, sessionOpts);

// Per-frame dispatch (after Copy1, before Copy2):
Ort::IoBinding binding(session);

// Bind D3D12 NPU-opened textures as tensors
binding.BindInput("input", Ort::Value::CreateTensor(
    npuColorMemory, colorDesc));  // pool_color
binding.BindOutput("output", Ort::Value::CreateTensor(
    npuOutputMemory, outputDesc)); // pool_output

session.Run(Ort::RunOptions{}, binding);
// Internally: DirectML dispatches to NPU command list + fence signal
```

### 2.5 Sync model

```
GPU Copy1 → GPU fence(N) 
  → NPU wait(N) → NPU run model → NPU fence(M) 
    → GPU wait(M) → GPU Copy2
```

Existing frame copy ops already use `vkQueueWaitIdle` for VK sync. The D3D12 GPU→NPU→GPU sync adds:

```
GPU signal(gpuFence, value)
NPU wait(gpuFence, value)
NPU signal(npuFence, value+1)
GPU wait(npuFence, value+1)
```

## 3. Model: FSRCNN-x2, INT8 quantized

### 3.1 Why FSRCNN

- **12 conv layers** — enough capacity for real texture reconstruction
- **Pre-trained ONNX available** — ONNX Model Zoo ships it trained on DIV2K
- **Standard ops only** — Conv2D, ReLU, Add, Concat. NPU-friendly.
- **Small**: ~150KB FP32 → ~75KB INT8 quantized

### 3.2 Quantization

FSRCNN in FP16 runs in ~4-5ms on XDNA 2. INT8 quantization brings it to ~2-3ms.
Use ONNX Runtime's quantization tool (`onnxruntime.quantization`):

```bash
python -m onnxruntime.quantization.quantize \
    --input fsrcnn-x2.onnx \
    --output fsrcnn-x2-int8.onnx \
    --quantize_mode IntegerOps \
    --per_channel
```

### 3.3 Embedding

The INT8 ONNX model (~75KB) is embedded in the mod JAR at `natives/fsrcnn-x2-int8.onnx`.
Loaded at runtime into ONNX Runtime session on the NPU.

## 4. Integration with existing code

### 4.1 New files

| File | Purpose |
|---|---|
| `src/native/fsrvk/npu_device.h/.cpp` | NPU D3D12 device + DirectML creation |
| `src/native/fsrvk/npu_upscale.cpp` | DirectML model load + dispatch |
| `src/native/fsrvk/onnx_loader.cpp` | ONNX Runtime session management |
| `src/client/java/com/fsr2mod/fsr/NpuUpscale.java` | Java JNI bridge |

### 4.2 Modified files

| File | Change |
|---|---|
| `jni_bridge.cpp` | Add NPU dispatch after Copy1, before Copy2 |
| `FSRProcessor.java` | Add NPU mode toggle in `onRenderBegin` |
| `FSRConfig.java` | Add `npuMode` config option |
| `build.gradle` | Add ONNX Runtime DLL to JAR |
| CMakeLists.txt | Add new .cpp files |

### 4.3 Dependencies

- **ONNX Runtime** (`onnxruntime.dll` + `onnxruntime_providers_dml.dll`) — ~15MB
  - NuGet: `Microsoft.ML.OnnxRuntime.DirectML` 1.18+
  - Extract DLLs, embed in JAR `natives/`
- **Existing**: D3D12, DXGI, DirectML (part of Windows 11 SDK)

### 4.4 Config toggle

```java
// FSRConfig.java
@ConfigOption(name = "NPU Upscale")
public boolean npuMode = false;
```

When `npuMode = true`:
- Skip FSR4 dispatch entirely
- Set `npuActive` runtime flag
- `onRenderBegin` → if npuActive: run NPU path instead of FSR4

## 5. Power measurement

### 5.1 Built-in timing (always-on)

```cpp
// jni_bridge.cpp
auto t0 = std::chrono::high_resolution_clock::now();
// NPU dispatch
auto dt = std::chrono::high_resolution_clock::now() - t0;
diagLog("NPU upscale: %lld us", dt.count() / 1000);
```

### 5.2 GPU power monitoring (optional, for benchmarks)

AMD GPU power can be read via:
- **ADL** (AMD Display Library) — `ADL2_Main_Control_GetPerformanceGraphData`
- **PMF** (Platform Management Framework) — Windows power telemetry
- **PresentMon** — GPU busy time + power estimation

For initial POC, timing + GPU idle detection is sufficient. Target: NPU upscale path reduces total GPU power by **20-40%** versus GPU-only upscaling at the same render resolution and framerate. Power tool integration (ADL/PresentMon) is a future step.

## 6. Quality comparison

To prove the concept, we need side-by-side comparison:

```
Config 1: FSR4 (existing GPU temporal upscale) — baseline
Config 2: NPU upscale (this project) — proof of concept
```

Test with same scene, same render scale, same camera position. Capture screenshots in both modes.

## 7. Build & deploy

```bash
# Get FSRCNN model
python scripts/download_fsrcnn.py  # downloads from ONNX Model Zoo

# Quantize to INT8
python scripts/quantize_fsrcnn.py

# Build native DLL (includes ONNX Runtime statically linked or side-by-side)
cd src/native/fsrvk && cmake -B build && cmake --build build

# Build mod
./gradlew.bat clean build

# Run
./gradlew.bat runClient
```

ONNX Runtime DLLs are copied into `natives/` by `processResources`, same pattern as `amd_fsr4_api.dll`.

## 8. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| FSRCNN doesn't run on XDNA 2 NPU (op not supported) | Medium | Test on CPU DirectML first, check op compatibility with AMD's supported opset |
| NPU adapter not detected / MCDM driver not installed | Medium | AMD XDNA driver must be installed. Fallback: skip NPU, log warning |
| ONNX Runtime DLLs too large for JAR (~15MB) | Low | 15MB is fine — existing amd_fsr4_api.dll is 28MB alone |
| FSRCNN quality unacceptable for Minecraft | Medium | Temporal accumulation can be re-added as GPU pass (Approach B evolution) |
| NPU fence sync adds latency | Low | D3D12 shared fence across adapters is ~0.01ms overhead |

## 9. Future evolution

This design is Approach A. Once proven, the natural progression:

1. **Approach A** (this): NPU-only spatial upscale — proves NPU viability
2. **Approach B**: Add GPU temporal accumulation before NPU pass — closes quality gap
3. **Custom model**: Train a Minecraft-specific lightweight model using the in-game renderer as training data generator — best possible quality

## 10. Success criteria

1. ✅ NPU upscale runs at <3ms per frame at 1080p→4K
2. ✅ Game runs at 60-100fps with NPU path active
3. ✅ Visible output is correct (not black/garbled)
4. ✅ Power: GPU power drops measurably compared to GPU-only upscaling (target: 20-40%)
5. ✅ User can toggle between FSR4 (GPU) and NPU mode in config — separate option, independent from FSR pipeline

## 11. Configuration

```java
// FSRConfig.java — new top-level option, NOT nested under FSR version
public enum UpscaleMode {
    FSR1,        // existing spatial
    FSR2,        // existing temporal
    FSR3,        // existing temporal
    FSR4,        // existing SDK-based
    NPU          // ← NEW: NPU-only spatial via DirectML
}
```

When `UpscaleMode.NPU`:
- `isTemporalUpscaler()` returns false → no jitter
- `isFsr2()`, `isFsr3()`, `isFsr4()` all return false
- NPU dispatch runs instead of FSR4 SDK dispatch
- No MV gen, no reactive mask, no temporal accumulation
- Pure rendering → Copy1 → NPU upscale → Copy2
