# FSR4 DX12 Integration Implementation Plan (v2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the Vulkan backend in `fsrvk.dll` with a Direct3D 12 backend so FSR4 can use the AMD FSR SDK's DX12 upscaler via the unified `ffxCreateContext`/`ffxDispatch` API.

**Architecture:** The AMD FSR SDK 2.3 supports only DX12 (no Vulkan). Our current code calls the old per-effect API (`ffxFsr3CreateContext`, `ffxFsr3UpscaleRenderSubmit`) which doesn't exist in the unified SDK. We replace the native C++ layer with D3D12 and use the unified API (`ffxCreateContext` + `ffxDispatch` with type-tagged descriptor structs). The Java side keeps the same JNI signatures. GL shares textures with D3D12 via Win32 handle export/import. GL sync uses `glFenceSync` + `glClientWaitSync` (not raw `glFlush()`). D3D12 sync uses a ring of command allocators + fence polling. Keyed mutex provides GPU-side sync.

**Tech Stack:** C++20, Direct3D 12 (system d3d12.dll, Shader Model 6.6), AMD FSR SDK 2.3 loader DLL (`amd_fidelityfx_loader_dx12.dll`), LWJGL 3.4.1, GL43C

## Global Constraints

- DLL files stay the same (`amd_fidelityfx_loader_dx12.dll` + `amd_fsr4_api.dll`) — these already export the unified API (`ffxCreateContext`, `ffxDispatch`, `ffxConfigure`, `ffxQuery`, `ffxDestroyContext`)
- `Fsr4Native.java` loads ONLY `amd_fidelityfx_loader_dx12.dll` — the loader internally loads `amd_fsr4_api.dll` from the DLL search path
- All 5 shared textures use D3D12 `ID3D12Resource` with `D3D12_RESOURCE_MISC_FLAG_SHARED_KEYEDMUTEX` for GPU-side GL↔D3D12 sync
- Depth texture format: `DXGI_FORMAT_R32_FLOAT`
- Reactive texture format: `DXGI_FORMAT_R8_UNORM`
- MV texture format: `DXGI_FORMAT_R16G16_FLOAT`
- Color/Output: `DXGI_FORMAT_R8G8B8A8_UNORM`
- GL sync: `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)` + `glClientWaitSync()` with 16ms timeout. If timeout → skip FSR4 this frame.
- D3D12 uses `D3D12_COMMAND_LIST_TYPE_DIRECT` (not COMPUTE — FSR4 internals may need graphics operations)
- 2-deep output ringbuffer for pipelining (no full stall)
- D3D12 queue fence is CPU-polled (not waited with INFINITE) — 100ms timeout, 3 retries, then fallback
- GPU selection: enumerate DXGI adapters, filter `VendorId == 0x1002` (AMD), pick highest-performance AMD adapter
- Variant detection: RDNA 4 (DeviceId range) → FP8 model variant, RDNA 3.5 iGPU → INT8 model variant
- All `ID3D12Resource*` pointers stay inside C++ — Java passes `(poolPtr, index)` pairs, JNI resolves to raw pointers safely
- `ALLOW_SIMULTANEOUS_ACCESS` flag NOT set — resources are accessed sequentially (GL then D3D12), not concurrently
- `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS` set on all textures (needed for FSR4 compute shader writes)
- No jitter when `reset == true` (world load, camera teleport)
- Remove old `amd_fidelityfx_loader_dx12.dll` and rename it properly — the current file IS `amd_fidelityfx_loader_dx12.dll` and stays
- `amd_fsr4_api.dll` stays as the renamed `amd_fidelityfx_upscaler_dx12.dll` — loader finds it by name
- `VulkanDevice` stays for non-FSR4 shared memory (`VulkanInterop`/`SharedTextureManager`) — only FSR4 switches to D3D12
- No `D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS` — not needed for sequential access, and the D3D12 debug layer warns

---

### Task 0: Update `Fsr4Native.java` — load only the loader DLL

**Files:**
- Modify: `src/client/java/com/fsr2mod/vulkan/Fsr4Native.java`

**Why:** The loader DLL (`amd_fidelityfx_loader_dx12.dll`) exports `ffxCreateContext`, `ffxDispatch`, etc. The FSR4 effect DLL (`amd_fsr4_api.dll`) is loaded internally by the loader when a context is created. We must NOT `System.load()` the effect DLL ourselves — the loader manages this. The effect DLL must be in the same directory as the loader so Windows DLL search can find it.

- [ ] **Step 1: Rewrite `Fsr4Native.java` to extract and load only the loader DLL**

```java
package com.fsr2mod.vulkan;

public final class Fsr4Native {

    private static boolean available = false;

    static {
        try {
            String dir = extractToTemp("/natives/");
            // Load the loader DLL — it internally loads amd_fsr4_api.dll
            // from the same temp directory (Windows DLL search path includes
            // the directory of the loaded module).
            System.load(dir + "\\amd_fidelityfx_loader_dx12.dll");
            available = true;
        } catch (UnsatisfiedLinkError | Exception e) {
            available = false;
        }
    }

    public static boolean isAvailable() { return available; }

    public static native boolean initFsr4(int renderW, int renderH, int displayW, int displayH);
    public static native boolean dispatchFsr4(
        float jitterX, float jitterY,
        float cameraFar, float cameraNear, float cameraFovYRad,
        float deltaTime, int frameIndex, boolean reset,
        long colorPoolPtr, int colorIndex,
        long depthPoolPtr, int depthIndex,
        long mvPoolPtr, int mvIndex,
        long reactivePoolPtr, int reactiveIndex,
        long outputPoolPtr, int outputIndex);
    public static native void waitIdle();
    public static native void shutdownFsr4();

    /** Extract all DLLs from JAR /natives/ to a temp dir, return dir path. */
    private static String extractToTemp(String jarDir) {
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("fsr4_");
            tempDir.toFile().deleteOnExit();
            String[] deps = {"amd_fidelityfx_loader_dx12.dll",
                             "amd_fsr4_api.dll"};
            for (String dll : deps) {
                java.io.InputStream in = Fsr4Native.class.getResourceAsStream(jarDir + dll);
                if (in == null) throw new UnsatisfiedLinkError("Not found in JAR: " + jarDir + dll);
                try (in) {
                    java.nio.file.Path target = tempDir.resolve(dll);
                    java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                }
            }
            return tempDir.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            throw new UnsatisfiedLinkError("Extraction failed: " + e.getMessage());
        }
    }
}
```

The JNI `dispatchFsr4` signature changes: instead of passing raw `jlong` pointers, it passes `(poolPtr, index)` pairs. This keeps all `ID3D12Resource*` inside C++ and avoids dangling pointer risks.

- [ ] **Step 2: Add `getResourcePtrFromPool` JNI in native code** (see Task 4)

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/vulkan/Fsr4Native.java
git commit -m "feat: Fsr4Native loads only loader DLL, use (poolPtr,index) pairs for dispatch"
```

---

### Task 1: D3D12Device singleton

**Files:**
- Create: `src/native/fsrvk/d3d12_device.h`
- Create: `src/native/fsrvk/d3d12_device.cpp`
- Modify: `src/native/fsrvk/CMakeLists.txt`

**Interfaces:**
- Consumes: D3D12 runtime headers (Windows SDK), AMD FSR SDK headers
- Produces: `fsr::D3D12Device::get()` singleton

**Key design decisions:**
- Uses `D3D12_COMMAND_LIST_TYPE_DIRECT` (not COMPUTE) — FSR4 internals may issue graphics commands
- 2 command allocators for ring-buffered dispatch (frame N, N+1)
- 1 graphics command list
- 1 fence (no `WaitForSingleObject` with INFINITE — use polling with timeout)
- `ensureInitialized()` uses a state machine (not `std::call_once` + exception capture) — allows retry after transient failure
- GPU selection: enumerate adapters, filter AMD vendor (0x1002), pick highest VRAM
- Exposes the selected adapter's DeviceId for RDNA generation detection

- [ ] **Step 1: Create `d3d12_device.h`**

```cpp
#pragma once
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl.h>
#include <array>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>

namespace fsr {

/// State machine for device initialization (allows retry after transient failures).
enum class D3D12DeviceState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED
};

class D3D12Device {
public:
    static D3D12Device& get();

    ID3D12Device* device() const { return device_.Get(); }
    ID3D12CommandQueue* commandQueue() const { return queue_.Get(); }
    ID3D12GraphicsCommandList* commandList() const { return cmdList_.Get(); }
    ID3D12CommandAllocator* commandAllocator(int slot) const { return cmdAllocs_[slot].Get(); }
    ID3D12Fence* fence() const { return fence_.Get(); }
    HANDLE fenceEvent() const { return fenceEvent_; }
    uint64_t lastCompletedFenceValue() const;

    uint32_t adapterDeviceId() const { return adapterDeviceId_; }

    enum { NUM_ALLOCATORS = 2 };

    /// Returns READY or FAILED. On FAILED, caller may retry after cooldown.
    D3D12DeviceState ensureInitialized();
    void resetForRetry();  // transition FAILED → UNINITIALIZED
    void dispose();        // full teardown, call from terminate()

    const std::string& deviceName() const { return deviceName_; }

private:
    D3D12Device() = default;
    ~D3D12Device();
    D3D12Device(const D3D12Device&) = delete;
    D3D12Device& operator=(const D3D12Device&) = delete;

    bool createDevice();
    bool createCommandQueue();
    bool createCommandAllocators();
    bool createCommandList();
    bool createFence();

    Microsoft::WRL::ComPtr<ID3D12Device> device_;
    Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter_;
    Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue_;
    std::array<Microsoft::WRL::ComPtr<ID3D12CommandAllocator>, NUM_ALLOCATORS> cmdAllocs_;
    Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList> cmdList_;
    Microsoft::WRL::ComPtr<ID3D12Fence> fence_;
    HANDLE fenceEvent_ = nullptr;

    uint64_t fenceValue_ = 0;
    uint32_t adapterDeviceId_ = 0;
    std::string deviceName_;

    D3D12DeviceState state_ = D3D12DeviceState::UNINITIALIZED;
    std::mutex mutex_;
};

} // namespace fsr
```

- [ ] **Step 2: Create `d3d12_device.cpp`**

```cpp
#include "d3d12_device.h"
#include <cstdio>
#include <cstring>
#include <stdexcept>

namespace fsr {

namespace {
    // Enable D3D12 debug layer in debug builds
    void enableDebugLayer() {
#ifdef _DEBUG
        Microsoft::WRL::ComPtr<ID3D12Debug> debug;
        if (SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&debug)))) {
            debug->EnableDebugLayer();
        }
#endif
    }

    // AMD PCI vendor ID
    constexpr uint32_t AMD_VENDOR_ID = 0x1002;
}

D3D12Device& D3D12Device::get() {
    static D3D12Device instance;
    return instance;
}

// ----------------------------------------------------------------
D3D12DeviceState D3D12Device::ensureInitialized() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == D3D12DeviceState::READY) return state_;
    if (state_ == D3D12DeviceState::INITIALIZING) return state_;
    state_ = D3D12DeviceState::INITIALIZING;

    if (!createDevice())      { state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandQueue()) { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandAllocators()) { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandList())  { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createFence())        { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }

    state_ = D3D12DeviceState::READY;
    return state_;
}

// ----------------------------------------------------------------
void D3D12Device::resetForRetry() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == D3D12DeviceState::FAILED) {
        dispose();
        state_ = D3D12DeviceState::UNINITIALIZED;
    }
}

// ----------------------------------------------------------------
bool D3D12Device::createDevice() {
    enableDebugLayer();

    Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
    HRESULT hr = CreateDXGIFactory1(IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return false;

    Microsoft::WRL::ComPtr<IDXGIAdapter1> current;
    SIZE_T maxMemory = 0;

    for (UINT i = 0; factory->EnumAdapters1(i, &current) != DXGI_ERROR_NOT_FOUND; i++) {
        DXGI_ADAPTER_DESC1 desc;
        if (FAILED(current->GetDesc1(&desc))) continue;

        // Only consider AMD adapters
        if (desc.VendorId != AMD_VENDOR_ID) continue;

        if (desc.DedicatedVideoMemory > maxMemory) {
            maxMemory = desc.DedicatedVideoMemory;
            adapter_ = current;
            adapterDeviceId_ = desc.DeviceId;
            char name[128] = {};
            size_t converted = 0;
            wcstombs_s(&converted, name, sizeof(name), desc.Description, _TRUNCATE);
            deviceName_ = name;
        }
    }

    if (!adapter_) {
        std::fprintf(stderr, "[D3D12Device] No AMD adapter found\n");
        return false;
    }

    hr = D3D12CreateDevice(adapter_.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device_));
    if (FAILED(hr)) {
        std::fprintf(stderr, "[D3D12Device] D3D12CreateDevice failed: 0x%08lx\n", hr);
        return false;
    }

    return true;
}

// ----------------------------------------------------------------
bool D3D12Device::createCommandQueue() {
    D3D12_COMMAND_QUEUE_DESC qDesc{};
    qDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    qDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    HRESULT hr = device_->CreateCommandQueue(&qDesc, IID_PPV_ARGS(&queue_));
    if (FAILED(hr)) return false;
    return true;
}

// ----------------------------------------------------------------
bool D3D12Device::createCommandAllocators() {
    for (int i = 0; i < NUM_ALLOCATORS; i++) {
        HRESULT hr = device_->CreateCommandAllocator(
            D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&cmdAllocs_[i]));
        if (FAILED(hr)) return false;
    }
    return true;
}

// ----------------------------------------------------------------
bool D3D12Device::createCommandList() {
    HRESULT hr = device_->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
        cmdAllocs_[0].Get(), nullptr, IID_PPV_ARGS(&cmdList_));
    if (FAILED(hr)) return false;
    cmdList_->Close();
    return true;
}

// ----------------------------------------------------------------
bool D3D12Device::createFence() {
    HRESULT hr = device_->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence_));
    if (FAILED(hr)) return false;
    fenceEvent_ = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (!fenceEvent_) return false;
    fenceValue_ = 0;
    return true;
}

// ----------------------------------------------------------------
uint64_t D3D12Device::lastCompletedFenceValue() const {
    if (!fence_) return UINT64_MAX;
    return fence_->GetCompletedValue();
}

// ----------------------------------------------------------------
void D3D12Device::dispose() {
    if (fenceEvent_) { CloseHandle(fenceEvent_); fenceEvent_ = nullptr; }
    cmdList_.Reset();
    for (auto& a : cmdAllocs_) a.Reset();
    fence_.Reset();
    queue_.Reset();
    device_.Reset();
    adapter_.Reset();
    deviceName_.clear();
    adapterDeviceId_ = 0;
    fenceValue_ = 0;
}

// ----------------------------------------------------------------
D3D12Device::~D3D12Device() { dispose(); }

} // namespace fsr
```

- [ ] **Step 3: Update `CMakeLists.txt`**

Add `d3d12_device.cpp` to `add_library`:
```
    d3d12_device.cpp
```

Add D3D12 link:
```cmake
target_link_libraries(fsrvk PRIVATE d3d12.lib dxgi.lib)
```

- [ ] **Step 4: Build and verify**

Run: `.\gradlew.bat buildNative`
Expected: Compiles and links successfully.

- [ ] **Step 5: Commit**

```bash
git add src/native/fsrvk/d3d12_device.h src/native/fsrvk/d3d12_device.cpp src/native/fsrvk/CMakeLists.txt
git commit -m "feat: D3D12Device singleton with AMD-only adapter, 2 allocator ring, state machine"
```

---

### Task 2: D3D12 shared resources (5-texture pool with correct formats + keyed mutex)

**Files:**
- Create: `src/native/fsrvk/d3d12_shared_resources.h`
- Create: `src/native/fsrvk/d3d12_shared_resources.cpp`
- Modify: `src/native/fsrvk/CMakeLists.txt`

**Interfaces:**
- Consumes: `D3D12Device::get()` for device, `ffxGetResourceDX12` for resource descriptors
- Produces: Pool of 5 `ID3D12Resource*` with shared keyed-mutex Win32 handles

**Texture formats (corrected):**
| Index | Name | DXGI Format | GL internalFormat |
|-------|------|-------------|-------------------|
| 0 | Color | `R8G8B8A8_UNORM` | `GL_RGBA8` |
| 1 | Depth | `R32_FLOAT` | `GL_R32F` |
| 2 | MV | `R16G16_FLOAT` | `GL_RG16F` |
| 3 | Reactive | `R8_UNORM` | `GL_R8` |
| 4 | Output | `R8G8B8A8_UNORM` | `GL_RGBA8` |

- [ ] **Step 1: Create `d3d12_shared_resources.h`**

```cpp
#pragma once
#include <d3d12.h>
#include <windows.h>
#include <wrl.h>
#include <array>
#include <cstdint>

namespace fsr {

/// Pool of 5 D3D12 shared textures for FSR4, with keyed mutex for GL↔D3D12 sync.
struct D3D12SharedResources {
    ID3D12Resource* resources[5] = {};
    HANDLE win32Handles[5] = {};
    uint64_t allocationSizes[5] = {};

    enum TexIdx : int {
        COLOR = 0,
        DEPTH = 1,
        MV = 2,
        REACTIVE = 3,
        OUTPUT = 4,
        COUNT = 5
    };

    /// Create all 5 textures. Input textures (0-3) use renderSize, output (4) uses displaySize.
    /// Resources created with D3D12_HEAP_FLAG_SHARED | D3D12_HEAP_FLAG_SHARED_KEYEDMUTEX.
    /// Returns false on any failure (calls destroy() for partial cleanup).
    bool create(ID3D12Device* device, uint32_t renderW, uint32_t renderH,
                uint32_t displayW, uint32_t displayH);

    void destroy();
};

} // namespace fsr
```

- [ ] **Step 2: Create `d3d12_shared_resources.cpp`**

```cpp
#include "d3d12_shared_resources.h"
#include <cstdio>

namespace fsr {

static DXGI_FORMAT texFormat(int idx) {
    switch (idx) {
        case D3D12SharedResources::COLOR:    return DXGI_FORMAT_R8G8B8A8_UNORM;
        case D3D12SharedResources::DEPTH:    return DXGI_FORMAT_R32_FLOAT;
        case D3D12SharedResources::MV:       return DXGI_FORMAT_R16G16_FLOAT;
        case D3D12SharedResources::REACTIVE: return DXGI_FORMAT_R8_UNORM;
        case D3D12SharedResources::OUTPUT:   return DXGI_FORMAT_R8G8B8A8_UNORM;
        default:                             return DXGI_FORMAT_UNKNOWN;
    }
}

static int bytesPerPixel(int idx) {
    switch (idx) {
        case D3D12SharedResources::COLOR:    return 4;
        case D3D12SharedResources::DEPTH:    return 4;
        case D3D12SharedResources::MV:       return 4;  // 2×16-bit = 4 bytes
        case D3D12SharedResources::REACTIVE: return 1;
        case D3D12SharedResources::OUTPUT:   return 4;
        default:                             return 4;
    }
}

// ----------------------------------------------------------------
bool D3D12SharedResources::create(ID3D12Device* device,
    uint32_t renderW, uint32_t renderH,
    uint32_t displayW, uint32_t displayH) {

    for (int i = 0; i < COUNT; i++) {
        bool isOutput = (i == OUTPUT);
        uint32_t w = isOutput ? displayW : renderW;
        uint32_t h = isOutput ? displayH : renderH;
        DXGI_FORMAT fmt = texFormat(i);
        if (fmt == DXGI_FORMAT_UNKNOWN) { destroy(); return false; }

        D3D12_RESOURCE_DESC desc{};
        desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        desc.Alignment = D3D12_DEFAULT_RESOURCE_PLACEMENT_ALIGNMENT;
        desc.Width = w;
        desc.Height = h;
        desc.DepthOrArraySize = 1;
        desc.MipLevels = 1;
        desc.Format = fmt;
        desc.SampleDesc.Count = 1;
        desc.SampleDesc.Quality = 0;
        desc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
        desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

        D3D12_HEAP_PROPERTIES heapProps{};
        heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;

        // Keyed mutex enables GPU-side sync between GL and D3D12
        HRESULT hr = device->CreateCommittedResource(
            &heapProps,
            D3D12_HEAP_FLAG_SHARED | D3D12_HEAP_FLAG_SHARED_KEYED_MUTEX,
            &desc,
            D3D12_RESOURCE_STATE_UNORDERED_ACCESS,
            nullptr,
            IID_PPV_ARGS(&resources[i]));

        if (FAILED(hr)) {
            std::fprintf(stderr, "[D3D12SharedResources] CreateCommittedResource[%d] failed: 0x%08lx\n", i, hr);
            destroy();
            return false;
        }

        // Export Win32 handle for GL import
        hr = device->CreateSharedHandle(
            resources[i],
            nullptr,
            GENERIC_ALL,
            nullptr,
            &win32Handles[i]);

        if (FAILED(hr)) {
            std::fprintf(stderr, "[D3D12SharedResources] CreateSharedHandle[%d] failed: 0x%08lx\n", i, hr);
            destroy();
            return false;
        }

        allocationSizes[i] = static_cast<uint64_t>(w) * h * bytesPerPixel(i);
    }

    return true;
}

// ----------------------------------------------------------------
void D3D12SharedResources::destroy() {
    for (auto& h : win32Handles) {
        if (h) { CloseHandle(h); h = nullptr; }
    }
    for (auto& r : resources) {
        if (r) { r->Release(); r = nullptr; }
    }
    for (auto& s : allocationSizes) s = 0;
}

} // namespace fsr
```

- [ ] **Step 3: Update `CMakeLists.txt`**

Add `d3d12_shared_resources.cpp` to `add_library`.

- [ ] **Step 4: Build and verify**

Run: `.\gradlew.bat buildNative`
Expected: Compiles successfully.

- [ ] **Step 5: Commit**

```bash
git add src/native/fsrvk/d3d12_shared_resources.h src/native/fsrvk/d3d12_shared_resources.cpp src/native/fsrvk/CMakeLists.txt
git commit -m "feat: D3D12 shared resources — correct formats, keyed mutex"
```

---

### Task 3: Fsr4Context using unified SDK API + DX12 backend

**Files:**
- Rewrite: `src/native/fsrvk/fsr4_context.h`
- Rewrite: `src/native/fsrvk/fsr4_context.cpp`

**API reference (from actual SDK headers):**
- `ffxGetInterfaceDX12(&backend, device, scratchBuffer, scratchBufferSize, maxContexts)` — creates DX12 backend interface
- `ffxGetDeviceDX12(d3dDevice)` — converts ID3D12Device to FfxDevice
- `ffxGetScratchMemorySizeDX12(maxContexts)` — returns required scratch buffer size
- `ffxCreateContext(&context, &desc, nullptr)` — creates context (desc type = `FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE`)
- `ffxCreateContextDescUpscale` — upscale context creation with chained `ffxCreateContextDescUpscaleVersion`
- `ffxCreateContextDescUpscaleVersion` — variant selection (FSR4 version = `FFX_UPSCALER_MAKE_VERSION(4,1,1)` = `0x00401001`)
- `ffxDispatch(&context, &dispatchDesc)` — frame dispatch (desc type = `FFX_API_DISPATCH_DESC_TYPE_UPSCALE`)
- `ffxDispatchDescUpscale` — dispatch parameters including all resource + camera + jitter fields
- `ffxGetResourceDX12(&dx12Resource, &resDesc, name, state)` — wraps ID3D12Resource in FfxApiResource
- `ffxDestroyContext(&context, nullptr)` — cleanup

- [ ] **Step 1: Rewrite `fsr4_context.h`**

```cpp
#pragma once
#include <ffx_fsr3.h>
#include <ffx_dx12.h>
#include <d3d12.h>
#include <cstdint>

namespace fsr {

/// Lightweight struct carrying per-frame FSR4 dispatch params.
/// Resources are passed as (pool, index) pairs resolved inside JNI bridge.
struct Fsr4DispatchParams {
    ID3D12Resource* color;
    ID3D12Resource* depth;
    ID3D12Resource* motionVectors;
    ID3D12Resource* reactive;
    ID3D12Resource* output;
    uint32_t renderW;
    uint32_t renderH;
    uint32_t displayW;
    uint32_t displayH;
    float jitterX;
    float jitterY;
    float cameraNear;
    float cameraFar;
    float fovYRad;
    float deltaTimeMs;
    uint32_t frameIndex;
    bool reset;
};

class Fsr4Context {
public:
    bool initialize(uint32_t renderW, uint32_t renderH,
                    uint32_t displayW, uint32_t displayH);
    bool dispatch(const Fsr4DispatchParams& params);
    bool isReady() const;     // fence check for ringbuffer
    void finish();            // CPU wait for completion
    void destroy();
    bool isInitialized() const { return isInitialized_; }

private:
    FfxContext ffxContext_{};
    bool isInitialized_ = false;
    int currentSlot_ = 0;    // ringbuffer slot
};

} // namespace fsr
```

- [ ] **Step 2: Rewrite `fsr4_context.cpp`**

```cpp
#include "fsr4_context.h"
#include "d3d12_device.h"
#include <cstdio>
#include <cstring>

namespace fsr {

// FSR4 upscaler version (from SDK: FFX_UPSCALER_MAKE_VERSION(4,1,1) = 0x00401001)
constexpr uint32_t FSR4_VERSION = 0x00401001;

// ----------------------------------------------------------------
bool Fsr4Context::initialize(uint32_t renderW, uint32_t renderH,
                             uint32_t displayW, uint32_t displayH) {
    if (isInitialized_) return true;

    auto& dev = D3D12Device::get();
    if (dev.ensureInitialized() != D3D12DeviceState::READY) return false;

    // --- Step 1: Compute scratch buffer size and allocate ---
    size_t scratchSize = ffxGetScratchMemorySizeDX12(1);
    void* scratchBuffer = std::malloc(scratchSize);
    if (!scratchBuffer) return false;

    // --- Step 2: Get FfxDevice from D3D12 device ---
    FfxDevice ffxDevice = ffxGetDeviceDX12(dev.device());

    // --- Step 3: Create DX12 backend interface ---
    FfxInterface backendInterface{};
    FfxErrorCode ec = ffxGetInterfaceDX12(
        &backendInterface,
        ffxDevice,
        scratchBuffer,
        scratchSize,
        1);  // maxContexts
    if (ec != FFX_ERROR_CODE_OK) {
        std::fprintf(stderr, "[Fsr4Context] ffxGetInterfaceDX12 failed: %d\n", ec);
        std::free(scratchBuffer);
        return false;
    }

    // --- Step 4: Build context creation descriptor chain ---
    // Base: upscale context
    ffxCreateContextDescUpscale upscaleDesc{};
    upscaleDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    upscaleDesc.header.size = sizeof(ffxCreateContextDescUpscale);
    upscaleDesc.flags = FFX_UPSCALE_ENABLE_DEPTH_INVERTED;
    upscaleDesc.maxRenderSize.width = static_cast<int>(renderW);
    upscaleDesc.maxRenderSize.height = static_cast<int>(renderH);
    upscaleDesc.maxUpscaleSize.width = static_cast<int>(displayW);
    upscaleDesc.maxUpscaleSize.height = static_cast<int>(displayH);
    upscaleDesc.fpMessage = nullptr;

    // Chained: version override → selects FSR4 variant
    ffxCreateContextDescUpscaleVersion versionDesc{};
    versionDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE_VERSION;
    versionDesc.header.size = sizeof(ffxCreateContextDescUpscaleVersion);
    versionDesc.header.pNext = nullptr;
    versionDesc.version = FSR4_VERSION;

    upscaleDesc.header.pNext = &versionDesc;

    // --- Step 5: Create context ---
    FfxErrorCode rc = ffxCreateContext(&ffxContext_,
        reinterpret_cast<ffxCreateContextDescHeader*>(&upscaleDesc),
        nullptr);
    if (rc != FFX_ERROR_CODE_OK) {
        std::fprintf(stderr, "[Fsr4Context] ffxCreateContext failed: %d\n", rc);
        std::free(scratchBuffer);
        ffxContext_ = {};
        return false;
    }

    isInitialized_ = true;
    currentSlot_ = 0;
    return true;
}

// ----------------------------------------------------------------
bool Fsr4Context::dispatch(const Fsr4DispatchParams& params) {
    if (!isInitialized_) return false;

    auto& dev = D3D12Device::get();
    int slot = currentSlot_;

    // Wait for this slot's previous execution to complete (non-blocking poll)
    uint64_t completed = dev.lastCompletedFenceValue();
    constexpr uint64_t TIMEOUT_NS = 100 * 1000 * 1000;  // 100ms per retry
    constexpr int MAX_RETRIES = 3;

    for (int retry = 0; retry < MAX_RETRIES; retry++) {
        if (completed >= slot + 1) break;

        // __fenceValue for this slot is slot + 1 (1-indexed per-slot)
        HANDLE event = dev.fenceEvent();
        if (dev.fence()) {
            HRESULT hr = dev.fence()->SetEventOnCompletion(slot + 1, event);
            if (SUCCEEDED(hr)) {
                DWORD waitResult = WaitForSingleObject(event, TIMEOUT_NS / 1000000);
                if (waitResult == WAIT_OBJECT_0) {
                    completed = dev.lastCompletedFenceValue();
                    continue;
                }
            }
        }
        // Timeout — fall back
        std::fprintf(stderr, "[Fsr4Context] dispatch timeout on slot %d (retry %d)\n", slot, retry);
        return false;
    }

    // Reset allocator for this slot
    ID3D12CommandAllocator* alloc = dev.commandAllocator(slot);
    alloc->Reset();

    // Get command list and reset
    ID3D12GraphicsCommandList* cmdList = dev.commandList();
    cmdList->Reset(alloc, nullptr);

    // Build FfxApiResourceDescriptions + get resources
    auto makeResource = [&](ID3D12Resource* res, DXGI_FORMAT fmt, bool uav) -> FfxApiResource {
        FfxApiResourceDescription resDesc{};
        resDesc.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
        resDesc.width = params.displayW;
        resDesc.height = params.displayH;
        resDesc.depth = 1;
        resDesc.mipLevels = 1;
        resDesc.format = fmt;
        resDesc.textureFlags = uav ? FFX_API_RESOURCE_FLAGS_TEXTURE_WRITABLE
                                   : FFX_API_RESOURCE_FLAGS_NONE;

        return ffxGetResourceDX12(res, &resDesc,
            uav ? L"output"
                : (res == params.depth ? L"depth" :
                   res == params.motionVectors ? L"motionVectors" :
                   res == params.reactive ? L"reactive" : L"color"),
            uav ? FFX_API_RESOURCE_STATE_UNORDERED_ACCESS
                : FFX_API_RESOURCE_STATE_COMPUTE_READ);
    };

    DXGI_FORMAT rgba8Fmt = DXGI_FORMAT_R8G8B8A8_UNORM;
    DXGI_FORMAT r32Fmt   = DXGI_FORMAT_R32_FLOAT;
    DXGI_FORMAT rg16Fmt  = DXGI_FORMAT_R16G16_FLOAT;
    DXGI_FORMAT r8Fmt    = DXGI_FORMAT_R8_UNORM;

    FfxApiResource colorRes    = makeResource(params.color,         rgba8Fmt, false);
    FfxApiResource depthRes    = makeResource(params.depth,         r32Fmt,   false);
    FfxApiResource mvRes       = makeResource(params.motionVectors, rg16Fmt,  false);
    FfxApiResource reactiveRes = makeResource(params.reactive,      r8Fmt,    false);
    FfxApiResource outputRes   = makeResource(params.output,        rgba8Fmt, true);

    // Build dispatch descriptor
    ffxDispatchDescUpscale dispatchDesc{};
    dispatchDesc.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatchDesc.header.size = sizeof(ffxDispatchDescUpscale);
    dispatchDesc.commandList = cmdList;
    dispatchDesc.color = colorRes;
    dispatchDesc.depth = depthRes;
    dispatchDesc.motionVectors = mvRes;
    dispatchDesc.reactive = reactiveRes;
    dispatchDesc.output = outputRes;
    dispatchDesc.jitterOffset.x = params.jitterX;
    dispatchDesc.jitterOffset.y = params.jitterY;
    dispatchDesc.motionVectorScale.x = 1.0f;
    dispatchDesc.motionVectorScale.y = 1.0f;
    dispatchDesc.renderSize.width = static_cast<int>(params.renderW);
    dispatchDesc.renderSize.height = static_cast<int>(params.renderH);
    dispatchDesc.upscaleSize.width = static_cast<int>(params.displayW);
    dispatchDesc.upscaleSize.height = static_cast<int>(params.displayH);
    dispatchDesc.enableSharpening = false;
    dispatchDesc.sharpness = 0.0f;
    dispatchDesc.frameTimeDelta = params.deltaTimeMs;
    dispatchDesc.preExposure = 1.0f;
    dispatchDesc.reset = params.reset;
    dispatchDesc.cameraNear = params.cameraNear;
    dispatchDesc.cameraFar = params.cameraFar;
    dispatchDesc.cameraFovAngleVertical = params.fovYRad;
    dispatchDesc.viewSpaceToMetersFactor = 1.0f;
    dispatchDesc.flags = 0;

    // Close command list
    cmdList->Close();

    // Execute
    ID3D12CommandList* lists[] = { cmdList };
    dev.commandQueue()->ExecuteCommandLists(1, lists);

    // Signal fence for this slot
    dev.fence()->Signal(slot + 1);

    currentSlot_ = (slot + 1) % D3D12Device::NUM_ALLOCATORS;
    return true;
}

// ----------------------------------------------------------------
bool Fsr4Context::isReady() const {
    auto& dev = D3D12Device::get();
    uint64_t completed = dev.lastCompletedFenceValue();
    return completed >= currentSlot_;
}

// ----------------------------------------------------------------
void Fsr4Context::finish() {
    auto& dev = D3D12Device::get();
    int slot = currentSlot_;
    uint64_t completed = dev.lastCompletedFenceValue();

    constexpr uint64_t TIMEOUT_NS = 100 * 1000 * 1000;
    constexpr int MAX_RETRIES = 3;

    for (int retry = 0; retry < MAX_RETRIES; retry++) {
        if (completed >= slot + 1) return;
        HANDLE event = dev.fenceEvent();
        if (dev.fence()) {
            HRESULT hr = dev.fence()->SetEventOnCompletion(slot + 1, event);
            if (SUCCEEDED(hr)) {
                DWORD waitResult = WaitForSingleObject(event, TIMEOUT_NS / 1000000);
                if (waitResult == WAIT_OBJECT_0) {
                    completed = dev.lastCompletedFenceValue();
                    continue;
                }
            }
        }
        break;
    }
    std::fprintf(stderr, "[Fsr4Context] finish timeout on slot %d\n", slot);
}

// ----------------------------------------------------------------
void Fsr4Context::destroy() {
    if (!isInitialized_) return;
    finish();
    ffxDestroyContext(&ffxContext_, nullptr);
    ffxContext_ = {};
    isInitialized_ = false;
}

} // namespace fsr
```

- [ ] **Step 3: Build and verify**

Run: `.\gradlew.bat buildNative`
Expected: Compiles with real SDK headers.

- [ ] **Step 4: Commit**

```bash
git add src/native/fsrvk/fsr4_context.h src/native/fsrvk/fsr4_context.cpp
git commit -m "feat: Fsr4Context unified API — ffxCreateContext+ffxDispatch, DX12 backend, ringbuffer"
```

---

### Task 4: Update JNI bridge for D3D12

**Files:**
- Modify: `src/native/fsrvk/jni_bridge.cpp`

**Changes:**
- Add `D3D12Device`, `D3D12SharedResources` includes
- Replace VK-based `allocateFsr4Pool` / `releaseFsr4Pool` / `getFsr4Win32Handle` / `getFsr4AllocationSize` with D3D12 versions
- Add `getFsr4ResourcePtr` — resolves `ID3D12Resource*` from `(poolPtr, index)`
- Update `initFsr4` / `dispatchFsr4` / `shutdownFsr4` for D3D12
- Remove `#include <ffx_vulkan.h>` — no longer needed

- [ ] **Step 1: Add D3D12 includes at top**

```cpp
#include "d3d12_device.h"
#include "d3d12_shared_resources.h"
#include <ffx_dx12.h>
```

- [ ] **Step 2: Replace `allocateFsr4Pool`**

```cpp
JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_allocateFsr4Pool(
    JNIEnv* env, jclass /*clazz*/,
    jint renderW, jint renderH, jint displayW, jint displayH)
{
    try {
        auto* pool = new fsr::D3D12SharedResources();
        auto& dev = fsr::D3D12Device::get();
        if (dev.ensureInitialized() != fsr::D3D12DeviceState::READY) {
            delete pool;
            return 0;
        }
        bool ok = pool->create(dev.device(),
            static_cast<uint32_t>(renderW), static_cast<uint32_t>(renderH),
            static_cast<uint32_t>(displayW), static_cast<uint32_t>(displayH));
        if (!ok) { delete pool; return 0; }
        return reinterpret_cast<jlong>(pool);
    } catch (const std::exception& e) {
        throwJavaException(env, e);
        return 0;
    }
}
```

- [ ] **Step 3: Replace `getFsr4Win32Handle`**

```cpp
JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4Win32Handle(
    JNIEnv* env, jclass /*clazz*/, jlong poolPtr, jint index)
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
```

- [ ] **Step 4: Add `getFsr4ResourcePtr`**

```cpp
JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4ResourcePtr(
    JNIEnv* env, jclass /*clazz*/, jlong poolPtr, jint index)
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
```

- [ ] **Step 5: Replace `getFsr4AllocationSize`**

```cpp
JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getFsr4AllocationSize(
    JNIEnv* env, jclass /*clazz*/, jlong poolPtr, jint index)
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
```

- [ ] **Step 6: Replace `releaseFsr4Pool`**

```cpp
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_releaseFsr4Pool(
    JNIEnv* env, jclass /*clazz*/, jlong poolPtr)
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
```

- [ ] **Step 7: Replace `initFsr4` (uses D3D12 dimensions, no Vulkan)**

```cpp
JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_initFsr4(
    JNIEnv* env, jclass /*clazz*/,
    jint renderW, jint renderH, jint displayW, jint displayH)
{
    try {
        if (g_fsr4Context) { g_fsr4Context->destroy(); delete g_fsr4Context; g_fsr4Context = nullptr; }
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
```

- [ ] **Step 8: Replace `dispatchFsr4` (uses pool+index pairs)**

```cpp
JNIEXPORT jboolean JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_dispatchFsr4(
    JNIEnv* env, jclass /*clazz*/,
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
```

- [ ] **Step 9: Update `waitIdle` and `shutdownFsr4`**

```cpp
JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_waitIdle(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    if (g_fsr4Context) g_fsr4Context->finish();
}

JNIEXPORT void JNICALL
Java_com_fsr2mod_vulkan_Fsr4Native_shutdownFsr4(
    JNIEnv* env, jclass /*clazz*/)
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
}
```

- [ ] **Step 10: Remove `#include <ffx_vulkan.h>`** from jni_bridge.cpp

- [ ] **Step 11: Build and verify**

Run: `.\gradlew.bat buildNative`
Expected: Compiles with D3D12 JNI functions.

- [ ] **Step 12: Commit**

```bash
git add src/native/fsrvk/jni_bridge.cpp
git commit -m "feat: JNI bridge — D3D12 pool allocation, (poolPtr,index) dispatch, remove ffx_vulkan.h"
```

---

### Task 5: Update Java SharedTextureManager for D3D12 pool + glTextureStorageMem2DEXT

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`

**Changes:**
- Add `fsr4ResourcePtrs` array (stores `ID3D12Resource*` as `jlong`, obtained via `getFsr4ResourcePtr`)
- `allocateFsr4Pool` additionally stores resource pointers after win32 handles
- `getColorAllocPtr()` (and friends) return resource pointer from pool, not Win32 handle
- `destroyFsr4Pool` clears resource pointers array
- The GL import path stays the same (`glCreateMemoryObjectsEXT` → `glImportMemoryWin32HandleEXT` → `glTextureStorageMem2DEXT`)

- [ ] **Step 1: Add `getFsr4ResourcePtr` declaration to `VulkanInterop.java`**

```java
public static native long getFsr4ResourcePtr(long poolPtr, int index);
```

- [ ] **Step 2: Update `SharedTextureManager.java` — add `fsr4ResourcePtrs`**

```java
private long[] fsr4ResourcePtrs;
```

In `allocateFsr4Pool`, after `fsr4Win32Handles[i] = win32Handle;`:
```java
fsr4ResourcePtrs[i] = VulkanInterop.getFsr4ResourcePtr(fsr4PoolPtr, i);
if (fsr4ResourcePtrs[i] == 0) {
    destroyFsr4Pool();
    return false;
}
```

Update `getColorAllocPtr()` (and friends) to return resource pointers:
```java
public long getColorAllocPtr() {
    return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[0] : 0;
}
public long getDepthAllocPtr() {
    return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[1] : 0;
}
public long getMvAllocPtr() {
    return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[2] : 0;
}
public long getReactiveAllocPtr() {
    return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[3] : 0;
}
public long getOutputAllocPtr() {
    return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[4] : 0;
}
```

In `destroyFsr4Pool`:
```java
fsr4ResourcePtrs = null;
```

- [ ] **Step 3: Verify the GL import path uses `glTextureStorageMem2DEXT`**

The existing code already does this correctly at `SharedTextureManager.java:216`:
```java
EXTMemoryObject.glTextureStorageMem2DEXT(texId, 1, internalFormat, texW, texH, memObj, 0);
```

No change needed — this was already correct. But verify the per-texture internalFormat:

| Texture | internalFormat | Code |
|---------|---------------|------|
| Color (0) | `GL_RGBA8` | ✓ existing |
| Depth (1) | `GL_R32F` | fix: was `GL_RGBA8` |
| MV (2) | `GL_RG16F` | ✓ existing |
| Reactive (3) | `GL_R8` | fix: was `GL_RGBA8` |
| Output (4) | `GL_RGBA8` | ✓ existing |

Update the internal format selection in `allocateFsr4Pool`:
```java
int internalFormat;
if (i == 2) { // MV
    internalFormat = GL43C.GL_RG16F;
} else if (i == 3) { // Reactive
    internalFormat = GL43C.GL_R8;
} else if (i == 1) { // Depth
    internalFormat = GL43C.GL_R32F;
} else {
    internalFormat = GL43C.GL_RGBA8;
}
```

- [ ] **Step 4: Build and verify**

Run: `.\gradlew.bat build`
Expected: Java compiles.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/SharedTextureManager.java src/client/java/com/fsr2mod/vulkan/VulkanInterop.java
git commit -m "feat: SharedTextureManager — D3D12 resource ptrs, correct Depth/R32F Reactive/R8 formats"
```

---

### Task 6: Update FSRProcessor — glFenceSync sync, GPU capability guard, ring buffer integration

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Changes:**
- Replace `gpuSyncMgr.flushGL()` with proper `glFenceSync` + `glClientWaitSync` (16ms timeout, skip if timeout)
- Add AMD GPU vendor check via `VulkanInterop.getDeviceInfo()` (keep existing VK path for non-FSR4 shared memory)
- Add RDNA generation detection via device ID parsing from native
- Pass `renderW`/`renderH`/`displayW`/`displayH` to FSR4 dispatch for DX12 desc construction
- Use new `(poolPtr, index)` dispatch signature from `Fsr4Native.dispatchFsr4`
- Fallback: if FSR4 init fails or dispatch times out, switch to FSR1 (spatial fallback)
- Reset flag: pass `true` on `fsr2FrameCount == 0` or camera teleport

- [ ] **Step 1: Replace GL sync in FSR4 dispatch block**

Replace:
```java
gpuSyncMgr.flushGL();
Fsr4Native.dispatchFsr4(...);
Fsr4Native.waitIdle();
```

With:
```java
// GL sync: fence + client wait (16ms timeout)
long sync = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
GL43C.glFlush();
int waitStatus = GL43C.glClientWaitSync(sync, 0, 16_666_667L); // 16ms
GL43C.glDeleteSync(sync);

if (waitStatus != GL43C.GL_ALREADY_SIGNALED && waitStatus != GL43C.GL_CONDITION_SATISFIED) {
    FSRMod.LOGGER.warn("FSR4: GL sync timeout, skipping frame");
    sceneUpscaled = true;
    return;
}

// D3D12 FSR4 dispatch with (poolPtr, index) pairs
boolean dispatched = Fsr4Native.dispatchFsr4(
    jitterX, jitterY,
    cameraFar, cameraNear, cameraFovY,
    deltaTimeMs, frameIndex, reset,
    sharedTexMgr.getColorAllocPtr(), 0,
    sharedTexMgr.getDepthAllocPtr(), 1,
    sharedTexMgr.getMvAllocPtr(), 2,
    sharedTexMgr.getReactiveAllocPtr(), 3,
    sharedTexMgr.getOutputAllocPtr(), 4
);

if (!dispatched) {
    FSRMod.LOGGER.warn("FSR4: dispatch returned false, falling back to FSR1");
    config.version = FSRConfig.FSRVersion.FSR1;
    pipelineReady = false;
    sceneUpscaled = true;
    return;
}

// Wait for the output to be ready before copying to mainRT
Fsr4Native.waitIdle();
```

- [ ] **Step 2: Add GPU capability detection**

After the existing `checkVulkanAvailable()` check, add an AMD GPU check using native device info:
```java
private boolean isAmdGpu() {
    if (!checkVulkanAvailable()) return false;
    try {
        String info = VulkanInterop.getDeviceInfo();
        return info != null && info.toLowerCase().contains("amd");
    } catch (Exception e) {
        return false;
    }
}
```

Use this to gate FSR4 init:
```java
if (Fsr4Native.isAvailable() && isAmdGpu()) {
    // FSR4 init...
}
```

- [ ] **Step 3: Pass render/display dimensions to FSR4 dispatch**

The native code now needs the render and display dimensions to build the `FfxApiResourceDescription`. Ensure `scaledWidth`, `scaledHeight`, `displayWidth`, `displayHeight` are available in the dispatch block (they already are).

- [ ] **Step 4: Build and verify**

Run: `.\gradlew.bat build`
Expected: Compiles.

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "feat: FSR4 — glFenceSync sync, AMD GPU check, (poolPtr,index) dispatch, timeout fallback"
```

---

### Task 7: Update build system — SDK import library link + correct DLL bundle

**Files:**
- Modify: `src/native/fsrvk/CMakeLists.txt`
- Modify: `build.gradle`

- [ ] **Step 1: Link SDK import library**

Add to `CMakeLists.txt`:
```cmake
# FSR4 SDK loader import library
target_link_libraries(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fidelityfx_loader_dx12.lib
)
```

Add POST_BUILD copy for both DLLs:
```cmake
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fidelityfx_loader_dx12.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
```

- [ ] **Step 2: Update `build.gradle` — bundle both DLLs into JAR**

```groovy
from("${project.rootDir}/third_party/AMD-FSR-API/lib") {
    include 'amd_fidelityfx_loader_dx12.dll'
    include 'amd_fsr4_api.dll'
    into 'natives'
}
```

Remove the old `amd_fidelityfx_loader_dx12.dll` include if it was duplicated.

- [ ] **Step 3: Build and verify**

Run: `.\gradlew.bat buildNative`
Expected: Links against SDK import library.

Run: `.\gradlew.bat build`
Expected: JAR contains both DLLs.

Verify JAR contents:
```bash
7z l build/libs/fsr-reloaded-*.jar | Select-String "natives/"
```

Expected:
- `natives/fsrvk.dll`
- `natives/amd_fidelityfx_loader_dx12.dll`
- `natives/amd_fsr4_api.dll`

- [ ] **Step 4: Commit**

```bash
git add src/native/fsrvk/CMakeLists.txt build.gradle
git commit -m "feat: link SDK import library, bundle loader+fsr4 DLLs"
```

---

### Task 8: Verification and runtime test

- [ ] **Step 1: Full clean build**

```bash
.\gradlew.bat clean
.\gradlew.bat buildNative
if ($?) { .\gradlew.bat build }
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run client**

```bash
.\gradlew.bat runClient
```

- [ ] **Step 3: Check logs for FSR4**

Wait 30s, then check:
```bash
Get-Content -Path "run/logs/latest.log" | Select-String -Pattern "FSR4|D3D12|ffx|fence" -CaseSensitive
```

Expected sequence:
- `D3D12Device: initializing on [GPU name]`
- `Fsr4Context: init OK (1092x683 -> 1680x1051)`
- `onSceneRendered (FSR4 total)` timing lines (~8-20ms)

- [ ] **Step 4: Verify scene is visible (not black)**

Switch to FSR4 mode, verify the 3D scene renders with FSR4 upscaling.

- [ ] **Step 5: Test mode switching**

Toggle through FSR1 → FSR2 → FSR3 → FSR4. All should show visible output.

- [ ] **Step 6: Test error recovery**

If FSR4 fails:
- Log should show appropriate error message
- Should fall back to FSR1 gracefully (the `isFsr4() && !fsr4Initialized` path)
- Subsequent attempts to toggle to FSR4 retry init

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: FSR4 DX12 integration — final build verification"
```
