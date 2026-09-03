### Task 1: NPU adapter detection + D3D12 device

**Files:**
- Create: `src/native/fsrvk/npu_device.h`
- Create: `src/native/fsrvk/npu_device.cpp`

**Interfaces:**
- Produces: `bool npuInit(D3D12Device& gpuDevice, ID3D12Device** outNpuDevice, IDMLDevice** outDmlDevice)` — Initializes NPU D3D12 + DirectML devices. Returns false if no NPU found.
- Produces: `void npuShutdown()` — Releases NPU device resources.
- Produces: `bool npuOpenSharedTexture(ID3D12Device* npuDevice, HANDLE sharedHandle, ID3D12Resource** outResource)` — Opens a shared D3D12 resource on the NPU device.

- [ ] **Step 1: Write npu_device.h**

```cpp
#pragma once
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl.h>
#include <DirectML.h>

using Microsoft::WRL::ComPtr;

// Initialize NPU device subsystem. Finds the NPU MCDM adapter and creates
// D3D12 + DirectML devices on it. Must be called after GPU D3D12 device exists.
bool npuInit(ID3D12Device* gpuDevice, ID3D12Device** outNpuDevice, IDMLDevice** outDmlDevice);

// Shutdown: release NPU D3D12 device, DirectML device, and any cached resources.
void npuShutdown();

// Open a shared D3D12 resource (created on GPU device) on the NPU device.
// handle: Win32 shared handle from GPU device's CreateSharedHandle.
bool npuOpenSharedTexture(ID3D12Device* npuDevice, HANDLE sharedHandle,
    ID3D12Resource** outResource);

// Check if NPU initialization has been attempted and succeeded.
bool npuIsAvailable();
```

- [ ] **Step 2: Write npu_device.cpp — adapter enumeration**

```cpp
#include "npu_device.h"
#include <cstdio>
#include <cstring>

static ComPtr<ID3D12Device> g_npuDevice;
static ComPtr<IDMLDevice> g_dmlDevice;
static bool g_npuAttempted = false;
static bool g_npuAvailable = false;

bool npuInit(ID3D12Device* gpuDevice, ID3D12Device** outNpuDevice, IDMLDevice** outDmlDevice) {
    g_npuAttempted = true;
    
    if (g_npuAvailable) {
        *outNpuDevice = g_npuDevice.Get();
        *outDmlDevice = g_dmlDevice.Get();
        return true;
    }
    
    // Get GPU adapter LUID to exclude it
    DXGI_ADAPTER_DESC gpuDesc = {};
    gpuDevice->GetDesc(&gpuDesc);
    
    ComPtr<IDXGIFactory4> factory;
    if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)))) {
        fprintf(stderr, "[NPU] CreateDXGIFactory2 failed\n");
        return false;
    }
    
    ComPtr<IDXGIAdapter1> adapter;
    UINT i = 0;
    while (factory->EnumAdapters1(i++, &adapter) != DXGI_ERROR_NOT_FOUND) {
        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        
        // Skip the GPU (same LUID)
        if (desc.AdapterLuid.HighPart == gpuDesc.AdapterLuid.HighPart &&
            desc.AdapterLuid.LowPart == gpuDesc.AdapterLuid.LowPart) {
            continue;
        }
        // Skip software adapters
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;
        
        // Skip adapters with no description (null/invalid)
        if (desc.Description[0] == L'\0') continue;
        
        // Try creating D3D12 MCDM device (feature level 1_0_CORE)
        ComPtr<ID3D12Device> candidate;
        HRESULT hr = D3D12CreateDevice(adapter.Get(),
            D3D_FEATURE_LEVEL_1_0_CORE, IID_PPV_ARGS(&candidate));
        if (FAILED(hr)) continue;
        
        // Verify DirectML support
        ComPtr<IDMLDevice> dmlCandidate;
        hr = DMLCreateDevice(candidate.Get(), DML_CREATE_DEVICE_FLAG_NONE,
            IID_PPV_ARGS(&dmlCandidate));
        if (FAILED(hr)) continue;
        
        // Found the NPU!
        char descStr[256];
        size_t converted = 0;
        wcstombs_s(&converted, descStr, desc.Description, sizeof(descStr) - 1);
        fprintf(stderr, "[NPU] Found NPU adapter: %s (VendorId=0x%04X, DeviceId=0x%04X)\n",
            descStr, desc.VendorId, desc.DeviceId);
        
        g_npuDevice = candidate;
        g_dmlDevice = dmlCandidate;
        g_npuAvailable = true;
        
        *outNpuDevice = g_npuDevice.Get();
        *outDmlDevice = g_dmlDevice.Get();
        return true;
    }
    
    fprintf(stderr, "[NPU] No MCDM NPU adapter found\n");
    return false;
}

void npuShutdown() {
    g_dmlDevice.Reset();
    g_npuDevice.Reset();
    g_npuAvailable = false;
    g_npuAttempted = false;
}

bool npuIsAvailable() {
    return g_npuAvailable;
}

bool npuOpenSharedTexture(ID3D12Device* npuDevice, HANDLE sharedHandle,
    ID3D12Resource** outResource) {
    if (!npuDevice || !sharedHandle) return false;
    HRESULT hr = npuDevice->OpenSharedHandle(sharedHandle, IID_PPV_ARGS(outResource));
    if (FAILED(hr)) {
        fprintf(stderr, "[NPU] OpenSharedHandle failed: HRESULT=0x%08X\n", (unsigned)hr);
        return false;
    }
    return true;
}
```

- [ ] **Step 3: Commit**

```bash
cd /c/Users/arshjot/Downloads/fsr2mod
git add src/native/fsrvk/npu_device.h src/native/fsrvk/npu_device.cpp
git commit -m "npu: NPU adapter detection + D3D12 device (MCDM)"

```

---

