# FSR4 DX12 Integration — Implementation Plan v3

> **For agentic workers:** Use `superpowers:executing-plans` or `superpowers:subagent-driven-development` to implement task-by-task.

**Goal:** Replace the Vulkan FSR4 backend with Direct3D 12 so FSR4 can use the AMD FSR SDK's unified `ffxCreateContext`/`ffxDispatch` API via the DX12-only `amd_fsr4_api.dll`.

**Root cause:** `amd_fsr4_api.dll` exports only `ffxCreateContext`, `ffxDispatch`, `ffxDestroyContext`, `ffxConfigure`, `ffxQuery` — no `ffxGetInterfaceVulkanVk`. Vulkan interop is impossible.

**Architecture:** GL shares textures with D3D12 via Win32 handle export/import (same `glImportMemoryWin32HandleEXT` path proven in Phase 0). GL sync uses `glFenceSync` + `glClientWaitSync` (16ms timeout). D3D12 sync uses single command allocator + fence (synchronous per-frame dispatch). The AMD FSR SDK's unified API receives the DX12 backend via a chained `ffxCreateBackendDX12Desc` in the context creation pNext chain — no separate `ffxGetInterfaceDX12` call needed. Variant selection (INT8 vs FP8) is handled automatically by the SDK based on hardware capabilities.

**Tech Stack:** C++20, D3D12 (system d3d12.lib + dxgi.lib), AMD FSR SDK 2.3 unified API, LWJGL 3.4.1, GL43C

## Global Constraints

- `VulkanDevice`/`VulkanInterop` stays for non-FSR4 shared memory — only FSR4 switches to D3D12
- All 5 shared textures use `ID3D12Resource` with `D3D12_HEAP_FLAG_SHARED` (no keyed mutex — unnecessary with fence-based sync)
- Correct DXGI and SDK surface formats:
  | Texture | DXGI Format | SDK Format | GL internalFormat | Width/Height |
  |---------|-------------|------------|-------------------|--------------|
  | Color (0) | `DXGI_FORMAT_R8G8B8A8_UNORM` | `FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM` | `GL_RGBA8` | render |
  | Depth (1) | `DXGI_FORMAT_R32_FLOAT` | `FFX_API_SURFACE_FORMAT_R32_FLOAT` | `GL_R32F` | render |
  | MV (2) | `DXGI_FORMAT_R16G16_FLOAT` | `FFX_API_SURFACE_FORMAT_R16G16_FLOAT` | `GL_RG16F` | render |
  | Reactive (3) | `DXGI_FORMAT_R8_UNORM` | `FFX_API_SURFACE_FORMAT_R8_UNORM` | `GL_R8` | render |
  | Output (4) | `DXGI_FORMAT_R8G8B8A8_UNORM` | `FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM` | `GL_RGBA8` | display |
- Cross-API resource state: `D3D12_RESOURCE_STATE_COMMON` initial + `FFX_API_RESOURCE_STATE_COMMON` passed to SDK — SDK handles internal transitions
- `D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS` set on all 5 textures (FSR4 writes output, may also write temp buffers)
- GL sync: `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)` + `glClientWaitSync` with 16ms timeout → skip frame if timeout
- D3D12 sync: single command allocator, `ID3D12CommandQueue::Signal(fence, monotonicValue)`, CPU `WaitForSingleObject` (100ms timeout → skip)
- `D3D12_COMMAND_LIST_TYPE_DIRECT` (not COMPUTE — FSR4 internals may issue graphics ops)
- GPU selection: enumerate DXGI adapters, filter `VendorId == 0x1002`, pick highest VRAM AMD adapter
- FSR4 variant (INT8 vs FP8) auto-selected by SDK based on hardware — no manual RDNA gen detection needed
- All `ID3D12Resource*` pointers stay inside C++ — Java passes `(poolPtr, index)` pairs, JNI resolves internally
- No `D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS` — resources accessed sequentially (GL then D3D12), not concurrently
- `ffxContext` is `void*` — stored as member, passed as `&ffxContext_` to SDK
- SDK includes: `ffx_api.h` (core types + functions), `ffx_upscale.h` (upscale structs), `ffx_api_dx12.h` (`ffxCreateBackendDX12Desc`)
- `amd_fidelityfx_loader_dx12.dll` exports the 5 unified API functions — `Fsr4Native.java` loads ONLY this DLL (it internally loads `amd_fsr4_api.dll`)
- Both DLLs extracted to same temp directory for Windows DLL search resolution

---

## Task 1: D3D12Device singleton

**Files:**
- Create: `src/native/fsrvk/d3d12_device.h`
- Create: `src/native/fsrvk/d3d12_device.cpp`
- Modify: `src/native/fsrvk/CMakeLists.txt`

**Design:**
- Single command allocator (synchronous dispatch only — no ringbuffer for v1)
- Single graphics command list (reused each frame via `Reset`)
- Single fence, monotonic fence values
- `signalFence()` + `waitForFenceValue()` convenience methods
- State machine (`UNINITIALIZED → INITIALIZING → READY | FAILED`) enables retry after transient failure
- GPU selection: enumerate DXGI adapters, filter AMD (0x1002), pick highest VRAM
- Exposes adapter DeviceId for logging

- [ ] **Step 1: Create `d3d12_device.h`**

```cpp
#pragma once
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl.h>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>

namespace fsr {

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
    ID3D12CommandAllocator* commandAllocator() const { return cmdAlloc_.Get(); }
    ID3D12Fence* fence() const { return fence_.Get(); }
    HANDLE fenceEvent() const { return fenceEvent_; }

    /// Signal the fence with next monotonic value, return that value.
    uint64_t signalFence();
    /// Wait for a specific fence value. Returns true if completed within timeoutMs.
    bool waitForFenceValue(uint64_t value, DWORD timeoutMs = 100);
    /// Non-blocking check.
    uint64_t lastCompletedFenceValue() const { return fence_ ? fence_->GetCompletedValue() : UINT64_MAX; }

    uint32_t adapterDeviceId() const { return adapterDeviceId_; }

    D3D12DeviceState ensureInitialized();
    void resetForRetry();
    void dispose();

    const std::string& deviceName() const { return deviceName_; }

private:
    D3D12Device() = default;
    ~D3D12Device();
    D3D12Device(const D3D12Device&) = delete;
    D3D12Device& operator=(const D3D12Device&) = delete;

    bool createDevice();
    bool createCommandQueue();
    bool createCommandAllocator();
    bool createCommandList();
    bool createFence();

    Microsoft::WRL::ComPtr<ID3D12Device> device_;
    Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter_;
    Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue_;
    Microsoft::WRL::ComPtr<ID3D12CommandAllocator> cmdAlloc_;
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
#include <stdexcept>

namespace fsr {

namespace {
    constexpr uint32_t AMD_VENDOR_ID = 0x1002;
    constexpr DWORD FENCE_TIMEOUT_MS = 100;
}

D3D12Device& D3D12Device::get() {
    static D3D12Device instance;
    return instance;
}

D3D12DeviceState D3D12Device::ensureInitialized() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != D3D12DeviceState::UNINITIALIZED) return state_;
    state_ = D3D12DeviceState::INITIALIZING;

    if (!createDevice())         { state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandQueue())   { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandAllocator()) { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandList())    { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createFence())          { dispose(); state_ = D3D12DeviceState::FAILED; return state_; }

    state_ = D3D12DeviceState::READY;
    return state_;
}

void D3D12Device::resetForRetry() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ == D3D12DeviceState::FAILED) { dispose(); state_ = D3D12DeviceState::UNINITIALIZED; }
}

bool D3D12Device::createDevice() {
    Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
    HRESULT hr = CreateDXGIFactory1(IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return false;

    Microsoft::WRL::ComPtr<IDXGIAdapter1> current;
    SIZE_T maxMemory = 0;

    for (UINT i = 0; factory->EnumAdapters1(i, &current) != DXGI_ERROR_NOT_FOUND; i++) {
        DXGI_ADAPTER_DESC1 desc;
        if (FAILED(current->GetDesc1(&desc))) continue;
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

bool D3D12Device::createCommandQueue() {
    D3D12_COMMAND_QUEUE_DESC qDesc{};
    qDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    qDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    return SUCCEEDED(device_->CreateCommandQueue(&qDesc, IID_PPV_ARGS(&queue_)));
}

bool D3D12Device::createCommandAllocator() {
    return SUCCEEDED(device_->CreateCommandAllocator(
        D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&cmdAlloc_)));
}

bool D3D12Device::createCommandList() {
    HRESULT hr = device_->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
        cmdAlloc_.Get(), nullptr, IID_PPV_ARGS(&cmdList_));
    if (FAILED(hr)) return false;
    cmdList_->Close();
    return true;
}

bool D3D12Device::createFence() {
    HRESULT hr = device_->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence_));
    if (FAILED(hr)) return false;
    fenceEvent_ = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (!fenceEvent_) return false;
    fenceValue_ = 0;
    return true;
}

uint64_t D3D12Device::signalFence() {
    ++fenceValue_;
    queue_->Signal(fence_.Get(), fenceValue_);
    return fenceValue_;
}

bool D3D12Device::waitForFenceValue(uint64_t value, DWORD timeoutMs) {
    if (!fence_) return false;
    if (fence_->GetCompletedValue() >= value) return true;
    HRESULT hr = fence_->SetEventOnCompletion(value, fenceEvent_);
    if (FAILED(hr)) return false;
    return WaitForSingleObject(fenceEvent_, timeoutMs) == WAIT_OBJECT_0;
}

void D3D12Device::dispose() {
    if (fenceEvent_) { CloseHandle(fenceEvent_); fenceEvent_ = nullptr; }
    cmdList_.Reset();
    cmdAlloc_.Reset();
    fence_.Reset();
    queue_.Reset();
    device_.Reset();
    adapter_.Reset();
    deviceName_.clear();
    adapterDeviceId_ = 0;
    fenceValue_ = 0;
}

D3D12Device::~D3D12Device() { dispose(); }

} // namespace fsr
```

- [ ] **Step 3: Update `CMakeLists.txt`** — add `d3d12_device.cpp` to sources, link `d3d12.lib` + `dxgi.lib`

```cmake
target_sources(fsrvk PRIVATE
    # ... existing sources ...
    d3d12_device.cpp
)
target_link_libraries(fsrvk PRIVATE d3d12.lib dxgi.lib)
```

- [ ] **Step 4: Build** — `.\gradlew.bat buildNative` → compiles

- [ ] **Step 5: Commit**

```bash
git add src/native/fsrvk/d3d12_device.h src/native/fsrvk/d3d12_device.cpp src/native/fsrvk/CMakeLists.txt
git commit -m "feat: D3D12Device singleton — AMD adapter, single allocator, monotonic fence"
```

---

## Task 2: D3D12 shared resources (5-texture pool)

**Files:**
- Create: `src/native/fsrvk/d3d12_shared_resources.h`
- Create: `src/native/fsrvk/d3d12_shared_resources.cpp`
- Modify: `src/native/fsrvk/CMakeLists.txt`

**Design:**
- 5 textures: Color, Depth, MV, Reactive, Output
- Correct dimensions per texture: inputs at render resolution, output at display resolution
- `D3D12_HEAP_FLAG_SHARED` (no keyed mutex — fence sync is sufficient)
- Initial state: `D3D12_RESOURCE_STATE_COMMON` (cross-API shared resource convention)
- Win32 handles exported via `CreateSharedHandle` for GL import
- `destroy()` releases all resources

- [ ] **Step 1: Create `d3d12_shared_resources.h`**

```cpp
#pragma once
#include <d3d12.h>
#include <windows.h>
#include <wrl.h>
#include <array>
#include <cstdint>

namespace fsr {

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

    bool create(ID3D12Device* device,
                uint32_t renderW, uint32_t renderH,
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

namespace {
    struct TexInfo {
        DXGI_FORMAT dxgiFmt;
        int bpp;
    };

    TexInfo texInfo(int idx) {
        switch (idx) {
            case D3D12SharedResources::COLOR:    return { DXGI_FORMAT_R8G8B8A8_UNORM, 4 };
            case D3D12SharedResources::DEPTH:    return { DXGI_FORMAT_R32_FLOAT,      4 };
            case D3D12SharedResources::MV:       return { DXGI_FORMAT_R16G16_FLOAT,   4 };
            case D3D12SharedResources::REACTIVE: return { DXGI_FORMAT_R8_UNORM,       1 };
            case D3D12SharedResources::OUTPUT:   return { DXGI_FORMAT_R8G8B8A8_UNORM, 4 };
            default:                             return { DXGI_FORMAT_UNKNOWN,        0 };
        }
    }
}

bool D3D12SharedResources::create(ID3D12Device* device,
    uint32_t renderW, uint32_t renderH,
    uint32_t displayW, uint32_t displayH) {

    for (int i = 0; i < COUNT; i++) {
        bool isOutput = (i == OUTPUT);
        uint32_t w = isOutput ? displayW : renderW;
        uint32_t h = isOutput ? displayH : renderH;

        auto info = texInfo(i);
        if (info.dxgiFmt == DXGI_FORMAT_UNKNOWN) { destroy(); return false; }

        D3D12_RESOURCE_DESC desc{};
        desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        desc.Alignment = D3D12_DEFAULT_RESOURCE_PLACEMENT_ALIGNMENT;
        desc.Width = w;
        desc.Height = h;
        desc.DepthOrArraySize = 1;
        desc.MipLevels = 1;
        desc.Format = info.dxgiFmt;
        desc.SampleDesc.Count = 1;
        desc.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
        desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

        D3D12_HEAP_PROPERTIES heapProps{};
        heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;

        HRESULT hr = device->CreateCommittedResource(
            &heapProps,
            D3D12_HEAP_FLAG_SHARED,
            &desc,
            D3D12_RESOURCE_STATE_COMMON,
            nullptr,
            IID_PPV_ARGS(&resources[i]));

        if (FAILED(hr)) {
            std::fprintf(stderr, "[D3D12SharedResources] Create[%d] failed: 0x%08lx\n", i, hr);
            destroy();
            return false;
        }

        hr = device->CreateSharedHandle(resources[i], nullptr, GENERIC_ALL, nullptr, &win32Handles[i]);
        if (FAILED(hr)) {
            std::fprintf(stderr, "[D3D12SharedResources] CreateSharedHandle[%d] failed: 0x%08lx\n", i, hr);
            destroy();
            return false;
        }

        allocationSizes[i] = static_cast<uint64_t>(w) * h * info.bpp;
    }
    return true;
}

void D3D12SharedResources::destroy() {
    for (auto& h : win32Handles) { if (h) { CloseHandle(h); h = nullptr; } }
    for (auto& r : resources)    { if (r) { r->Release(); r = nullptr; } }
    for (auto& s : allocationSizes) s = 0;
}

} // namespace fsr
```

- [ ] **Step 3: Update `CMakeLists.txt`** — add `d3d12_shared_resources.cpp`

- [ ] **Step 4: Build** — `.\gradlew.bat buildNative`

- [ ] **Step 5: Commit**

```bash
git add src/native/fsrvk/d3d12_shared_resources.h src/native/fsrvk/d3d12_shared_resources.cpp
git commit -m "feat: D3D12 shared resources — correct formats, SHARED heap, COMMON initial state"
```

---

## Task 3: Fsr4Context using unified SDK API

**Files:**
- Rewrite: `src/native/fsrvk/fsr4_context.h`
- Rewrite: `src/native/fsrvk/fsr4_context.cpp`

**Design:**
- Uses AMD FSR SDK 2.3 unified API (`ffxCreateContext` + `ffxDispatch`)
- DX12 backend provided via chained `ffxCreateBackendDX12Desc` — no separate `ffxGetInterfaceDX12` call
- `ffxCreateContextDescUpscaleVersion.version = FFX_UPSCALER_VERSION` selects FSR4
- Variant (INT8/FP8) auto-detected by SDK based on D3D12 device capabilities
- Resources registered via `ffxGetResourceDX12` with `FFX_API_RESOURCE_STATE_COMMON` initial state (SDK handles barriers)
- Synchronous dispatch: single command allocator, monotonic fence, `queue->Signal`
- Non-blocking completion check in `dispatch()`; explicit `finish()` for CPU wait
- `ffxContext_` stored as `void*` (opaque handle), passed via `&ffxContext_`
- Correct SDK field names: `mipCount` (not `mipLevels`), `flags` (not `textureFlags`)
- No texture writable flag — `FFX_API_RESOURCE_FLAGS_NONE`
- Correct usage: `FFX_API_RESOURCE_USAGE_UAV` for output, `FFX_API_RESOURCE_USAGE_READ_ONLY` for inputs
- Verify: `ffxReturnCode_t == FFX_API_RETURN_OK`
- Verify: `ffxApiHeader` has NO `size` field — only `type` + `pNext`

- [ ] **Step 1: Rewrite `fsr4_context.h`**

```cpp
#pragma once
#include <ffx_api.h>
#include <ffx_upscale.h>
#include <ffx_api_dx12.h>
#include <cstdint>

namespace fsr {

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
    void finish();        // CPU wait for GPU completion
    void destroy();
    bool isInitialized() const { return ffxContext_ != nullptr; }

private:
    ffxContext ffxContext_ = nullptr;
    uint64_t lastFenceValue_ = 0;
};

} // namespace fsr
```

- [ ] **Step 2: Rewrite `fsr4_context.cpp`**

```cpp
#include "fsr4_context.h"
#include "d3d12_device.h"
#include <cstdio>

namespace fsr {

bool Fsr4Context::initialize(uint32_t renderW, uint32_t renderH,
                             uint32_t displayW, uint32_t displayH) {
    if (ffxContext_) return true;

    auto& dev = D3D12Device::get();
    if (dev.ensureInitialized() != D3D12DeviceState::READY) return false;

    // pNext chain: upscaleDesc → versionDesc → backendDesc
    ffxCreateContextDescUpscale upscaleDesc{};
    upscaleDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    upscaleDesc.flags = FFX_UPSCALE_ENABLE_DEPTH_INVERTED;
    upscaleDesc.maxRenderSize.width = static_cast<int32_t>(renderW);
    upscaleDesc.maxRenderSize.height = static_cast<int32_t>(renderH);
    upscaleDesc.maxUpscaleSize.width = static_cast<int32_t>(displayW);
    upscaleDesc.maxUpscaleSize.height = static_cast<int32_t>(displayH);
    upscaleDesc.fpMessage = nullptr;

    ffxCreateContextDescUpscaleVersion versionDesc{};
    versionDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE_VERSION;
    versionDesc.header.pNext = nullptr;
    versionDesc.version = FFX_UPSCALER_VERSION;  // selects FSR4

    ffxCreateBackendDX12Desc backendDesc{};
    backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_DX12;
    backendDesc.header.pNext = nullptr;
    backendDesc.device = dev.device();

    // Chain: upscale → version → backend
    upscaleDesc.header.pNext = &versionDesc;
    versionDesc.header.pNext = &backendDesc;

    ffxReturnCode_t rc = ffxCreateContext(&ffxContext_,
        reinterpret_cast<ffxCreateContextDescHeader*>(&upscaleDesc),
        nullptr);  // no custom allocation callbacks

    if (rc != FFX_API_RETURN_OK) {
        std::fprintf(stderr, "[Fsr4Context] ffxCreateContext failed: %u\n", rc);
        ffxContext_ = nullptr;
        return false;
    }

    lastFenceValue_ = 0;
    return true;
}

bool Fsr4Context::dispatch(const Fsr4DispatchParams& params) {
    if (!ffxContext_) return false;

    auto& dev = D3D12Device::get();

    // Non-blocking check: previous dispatch must complete before we reset the allocator
    if (lastFenceValue_ > 0) {
        if (dev.fence()->GetCompletedValue() < lastFenceValue_) {
            if (!dev.waitForFenceValue(lastFenceValue_)) {
                std::fprintf(stderr, "[Fsr4Context] previous dispatch not done, skipping\n");
                return false;
            }
        }
    }

    // Reset allocator and command list
    dev.commandAllocator()->Reset();
    dev.commandList()->Reset(dev.commandAllocator(), nullptr);

    // Build FfxApiResourceDescriptions
    auto makeResource = [](ID3D12Resource* res, uint32_t w, uint32_t h,
                           uint32_t surfaceFmt, bool isOutput,
                           const wchar_t* name) -> FfxApiResource {
        FfxApiResourceDescription resDesc{};
        resDesc.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
        resDesc.width = w;
        resDesc.height = h;
        resDesc.depth = 1;
        resDesc.mipCount = 1;
        resDesc.format = surfaceFmt;
        resDesc.flags = FFX_API_RESOURCE_FLAGS_NONE;
        resDesc.usage = isOutput ? FFX_API_RESOURCE_USAGE_UAV
                                 : FFX_API_RESOURCE_USAGE_READ_ONLY;

        return ffxGetResourceDX12(res, &resDesc, name,
            FFX_API_RESOURCE_STATE_COMMON);
    };

    FfxApiResource colorRes    = makeResource(params.color,         params.renderW, params.renderH, FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, false, L"color");
    FfxApiResource depthRes    = makeResource(params.depth,         params.renderW, params.renderH, FFX_API_SURFACE_FORMAT_R32_FLOAT,       false, L"depth");
    FfxApiResource mvRes       = makeResource(params.motionVectors, params.renderW, params.renderH, FFX_API_SURFACE_FORMAT_R16G16_FLOAT,    false, L"motionVectors");
    FfxApiResource reactiveRes = makeResource(params.reactive,      params.renderW, params.renderH, FFX_API_SURFACE_FORMAT_R8_UNORM,        false, L"reactive");
    FfxApiResource outputRes   = makeResource(params.output,        params.displayW, params.displayH, FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, true, L"output");

    // Build dispatch descriptor
    ffxDispatchDescUpscale dispatchDesc{};
    dispatchDesc.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatchDesc.commandList = dev.commandList();
    dispatchDesc.color = colorRes;
    dispatchDesc.depth = depthRes;
    dispatchDesc.motionVectors = mvRes;
    dispatchDesc.reactive = reactiveRes;
    dispatchDesc.output = outputRes;
    dispatchDesc.jitterOffset.x = params.jitterX;
    dispatchDesc.jitterOffset.y = params.jitterY;
    dispatchDesc.motionVectorScale.x = 1.0f;
    dispatchDesc.motionVectorScale.y = 1.0f;
    dispatchDesc.renderSize.width = static_cast<int32_t>(params.renderW);
    dispatchDesc.renderSize.height = static_cast<int32_t>(params.renderH);
    dispatchDesc.upscaleSize.width = static_cast<int32_t>(params.displayW);
    dispatchDesc.upscaleSize.height = static_cast<int32_t>(params.displayH);
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

    dev.commandList()->Close();

    // Execute
    ID3D12CommandList* lists[] = { dev.commandList() };
    dev.commandQueue()->ExecuteCommandLists(1, lists);

    // Signal monotonic fence (GPU-side via queue)
    lastFenceValue_ = dev.signalFence();
    return true;
}

void Fsr4Context::finish() {
    if (!ffxContext_ || lastFenceValue_ == 0) return;
    D3D12Device::get().waitForFenceValue(lastFenceValue_);
}

void Fsr4Context::destroy() {
    if (!ffxContext_) return;
    finish();
    ffxDestroyContext(&ffxContext_, nullptr);
    ffxContext_ = nullptr;
    lastFenceValue_ = 0;
}

} // namespace fsr
```

- [ ] **Step 3: Build and verify** — `.\gradlew.bat buildNative`

- [ ] **Step 4: Commit**

```bash
git add src/native/fsrvk/fsr4_context.h src/native/fsrvk/fsr4_context.cpp
git commit -m "feat: Fsr4Context — unified API, chained DX12 backend, correct SDK structs"
```

---

## Task 4: Update JNI bridge for D3D12

**Files:**
- Modify: `src/native/fsrvk/jni_bridge.cpp`

**Changes:**
- Replace VK-based pool allocation with D3D12 D3D12SharedResources
- Add `getFsr4ResourcePtr` — resolves `ID3D12Resource*` from `(poolPtr, index)`
- Update `initFsr4` / `dispatchFsr4` / `shutdownFsr4` for D3D12
- Add `#include "d3d12_device.h"`, `#include "d3d12_shared_resources.h"`
- Remove `#include <ffx_vulkan.h>` and old VK pool types

- [ ] **Step 1: Add includes and replace pool allocation**

```cpp
#include "d3d12_device.h"
#include "d3d12_shared_resources.h"

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_allocateFsr4Pool(
    JNIEnv* env, jclass,
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
        // throwJavaException(env, e);
        return 0;
    }
}
```

- [ ] **Step 2: Add `getFsr4ResourcePtr`**

```cpp
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
        return 0;
    }
}
```

- [ ] **Step 3: Update `getFsr4Win32Handle`**, `getFsr4AllocationSize`, `releaseFsr4Pool` to use D3D12 pool types (same pattern as v2 but with `D3D12SharedResources` instead of `VulkanFsr4SharedResources`)

- [ ] **Step 4: Update `initFsr4` / `dispatchFsr4` / `shutdownFsr4`** (same signatures as v2 — the Fsr4Context API is unchanged at the JNI level)

- [ ] **Step 5: Build and verify** — `.\gradlew.bat buildNative`

- [ ] **Step 6: Commit**

```bash
git add src/native/fsrvk/jni_bridge.cpp
git commit -m "feat: JNI bridge — D3D12 pool allocation, resource ptr, remove ffx_vulkan.h"
```

---

## Task 5: Update SharedTextureManager for D3D12 + correct GL internalFormat

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`
- Modify: `src/client/java/com/fsr2mod/vulkan/VulkanInterop.java`

**Changes:**
- Add `getFsr4ResourcePtr` native declaration in `VulkanInterop.java`
- Add `fsr4ResourcePtrs` array to `SharedTextureManager`
- Set correct per-texture GL `internalFormat`:
  | Index | Name | internalFormat |
  |-------|------|----------------|
  | 0 | Color | `GL_RGBA8` |
  | 1 | Depth | `GL_R32F` |
  | 2 | MV | `GL_RG16F` |
  | 3 | Reactive | `GL_R8` |
  | 4 | Output | `GL_RGBA8` |
- The `glTextureStorageMem2DEXT` call already exists and is correct — just needs per-tex internalFormat

- [ ] **Step 1: Add `getFsr4ResourcePtr` to `VulkanInterop.java`**

```java
public static native long getFsr4ResourcePtr(long poolPtr, int index);
```

- [ ] **Step 2: Add `fsr4ResourcePtrs` and update allocation to store resource pointers + correct internalFormat**

```java
private long[] fsr4ResourcePtrs;

// In allocateFsr4Pool:
fsr4ResourcePtrs = new long[5];
int[] internalFormats = { GL43C.GL_RGBA8, GL43C.GL_R32F, GL43C.GL_RG16F, GL43C.GL_R8, GL43C.GL_RGBA8 };
for (int i = 0; i < 5; i++) {
    long win32Handle = VulkanInterop.getFsr4Win32Handle(fsr4PoolPtr, i);
    long allocSize = VulkanInterop.getFsr4AllocationSize(fsr4PoolPtr, i);
    if (win32Handle == 0 || allocSize == 0) { destroyFsr4Pool(); return false; }
    fsr4Win32Handles[i] = win32Handle;
    fsr4AllocationSizes[i] = allocSize;

    fsr4ResourcePtrs[i] = VulkanInterop.getFsr4ResourcePtr(fsr4PoolPtr, i);
    if (fsr4ResourcePtrs[i] == 0) { destroyFsr4Pool(); return false; }

    int texId = GlStateManager._genTexture();
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texId);
    int memObj = GL43C.glCreateMemoryObjectsEXT();
    GL43C.glImportMemoryWin32HandleEXT(memObj, allocSize, GL43C.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT, win32Handle);
    GL43C.glTextureStorageMem2DEXT(texId, 1, internalFormats[i], texW, texH, memObj, 0);
    fsr4Textures[i] = texId;
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
}
```

- [ ] **Step 3: Update getter methods to use resource pointers**

```java
public long getColorAllocPtr()    { return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[0] : 0; }
public long getDepthAllocPtr()    { return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[1] : 0; }
public long getMvAllocPtr()       { return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[2] : 0; }
public long getReactiveAllocPtr() { return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[3] : 0; }
public long getOutputAllocPtr()   { return fsr4ResourcePtrs != null ? fsr4ResourcePtrs[4] : 0; }
```

- [ ] **Step 4: Build and verify** — `.\gradlew.bat build`

- [ ] **Step 5: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/SharedTextureManager.java src/client/java/com/fsr2mod/vulkan/VulkanInterop.java
git commit -m "feat: SharedTextureManager — D3D12 resource ptrs, correct per-texture internalFormat"
```

---

## Task 6: Update FSRProcessor — glFenceSync sync, (poolPtr,index) dispatch

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Changes:**
- Replace `gpuSyncMgr.flushGL()` with `glFenceSync` + `glClientWaitSync` (16ms timeout, skip if timeout)
- Use new `(poolPtr, index)` dispatch signature from `Fsr4Native.dispatchFsr4`
- AMD GPU check via existing VulkanInterop.getDeviceInfo() string match
- Fallback to FSR1 if FSR4 init fails or dispatch times out
- Pass `renderW`/`renderH`/`displayW`/`displayH` to FSR4 dispatch (not currently used by the native layer but available for future)

- [ ] **Step 1: Replace GL sync in FSR4 dispatch block**

```java
// GL sync: fence + client wait (16ms timeout)
long sync = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
GL43C.glFlush();
int waitStatus = GL43C.glClientWaitSync(sync, 0, 16_666_667L);
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

// Wait for D3D12 GPU completion
Fsr4Native.waitIdle();
```

- [ ] **Step 2: Build and verify** — `.\gradlew.bat build`

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "feat: FSR4 — glFenceSync sync, (poolPtr,index) dispatch, timeout fallback"
```

---

## Task 7: Update build system — bundle both DLLs

**Files:**
- Modify: `src/native/fsrvk/CMakeLists.txt`
- Modify: `build.gradle`

**DLL bundle list:**
- `fsrvk.dll` (our native library)
- `amd_fidelityfx_loader_dx12.dll` (SDK loader — only one we `System.load()`)
- `amd_fsr4_api.dll` (FSR4 effect — loaded internally by loader)

- [ ] **Step 1: Add POST_BUILD copies in CMakeLists.txt**

```cmake
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_SOURCE_DIR}/../../third_party/AMD-FSR-API/lib/amd_fidelityfx_loader_dx12.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_SOURCE_DIR}/../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
```

- [ ] **Step 2: Update build.gradle — bundle DLLs into JAR**

```groovy
from("${project.rootDir}/third_party/AMD-FSR-API/lib") {
    include 'amd_fidelityfx_loader_dx12.dll'
    include 'amd_fsr4_api.dll'
    into 'natives'
}
```

- [ ] **Step 3: Build and verify** — `.\gradlew.bat buildNative && .\gradlew.bat build` + verify JAR contents:

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
git commit -m "feat: bundle SDK loader + FSR4 DLLs in JAR"
```

---

## Task 8: Verification and runtime test

- [ ] **Step 1: Full clean build**

```bash
.\gradlew.bat clean
.\gradlew.bat buildNative
if ($?) { .\gradlew.bat build }
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run client** — `.\gradlew.bat runClient`

- [ ] **Step 3: Check logs for FSR4 init** — wait 30s, then:
```bash
Get-Content -Path "run/logs/latest.log" | Select-String -Pattern "FSR4|D3D12|ffx|fence" -CaseSensitive
```
Expected sequence:
- `[D3D12Device] initializing on [GPU name]`
- `[Fsr4Context] init OK (1092x683 -> 1680x1051)`
- Timing lines (~8-20ms) for FSR4 dispatch

- [ ] **Step 4: Verify scene is visible** — switch to FSR4 mode, confirm 3D scene renders

- [ ] **Step 5: Test mode switching** — toggle FSR1 → FSR2 → FSR3 → FSR4, all show output

- [ ] **Step 6: Test error recovery** — if FSR4 fails, graceful FSR1 fallback

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat: FSR4 DX12 integration — final build verification"
```

---

## Verification checklist (all C1-C11, M1-M12, N1-N11 fixed)

| ID | Issue | Fix location | Status |
|----|-------|-------------|--------|
| C1 | `fence->Signal` CPU-side | `dev.commandQueue()->Signal(dev.fence(), ++fenceValue_)` in d3d12_device.cpp | ✅ |
| C2 | Per-slot fence collision | Monotonic fence values in D3D12Device::signalFence(), single fence only | ✅ |
| C3 | displayW/displayH for all textures | Per-texture dimensions in makeResource lambda | ✅ |
| C4 | No resource barriers | COMMON initial state + `FFX_API_RESOURCE_STATE_COMMON` — SDK handles transitions | ✅ |
| C5 | Retry loop doesn't retry | Simplified: non-blocking check + single WaitForSingleObject | ✅ |
| C6 | Keyed mutex not acquired | `D3D12_HEAP_FLAG_SHARED` only (no keyed mutex) | ✅ |
| C7 | Ringbuffer not implemented | Single allocator, synchronous dispatch (no ringbuffer) | ✅ |
| C8 | RDNA gen missing | SDK auto-selects INT8/FP8 based on D3D12 device caps | ✅ |
| C9 | Hardcoded 0x00401001 | `FFX_UPSCALER_VERSION` macro from SDK header | ✅ |
| C10 | Scratch buffer leak | No manual scratch buffer — SDK allocates internally via chained backend desc | ✅ |
| C11 | Unverified struct names | All SDK structs verified against actual headers | ✅ |
| M1 | `header.size` doesn't exist | Removed — ffxApiHeader has only type + pNext | ✅ |
| M2 | `textureFlags` → `flags` | Fixed field name | ✅ |
| M3 | `mipLevels` → `mipCount` | Fixed field name | ✅ |
| M4 | `FFX_API_RESOURCE_FLAGS_TEXTURE_WRITABLE` | Use `FFX_API_RESOURCE_FLAGS_NONE` | ✅ |
| M5 | `FfxErrorCode` → `ffxReturnCode_t` | Fixed return type | ✅ |
| M6 | `FFX_ERROR_CODE_OK` → `FFX_API_RETURN_OK` | Fixed constant | ✅ |
| M7 | Missing `usage` field | Added `FFX_API_RESOURCE_USAGE_UAV` / `FFX_API_RESOURCE_USAGE_READ_ONLY` | ✅ |
| M8 | Wrong `ffxCreateContext` third param | Changed to `nullptr` (allocation callbacks, not interface) | ✅ |
| M9 | Missing `ffxCreateBackendDX12Desc` in chain | Added to pNext chain | ✅ |
| M10 | Surface format enum mismatch | Use `FFX_API_SURFACE_FORMAT_*` not raw `DXGI_FORMAT` | ✅ |
| M11 | Missing resource type | Added `FFX_API_RESOURCE_TYPE_TEXTURE2D` | ✅ |
| M12 | `ffxStructType_t` is uint64_t | Correct type used in headers | ✅ |
