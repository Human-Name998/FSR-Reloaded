#include "d3d12_device.h"
#include <cstdio>
#include <cstdarg>
#include <stdexcept>

namespace fsr {

namespace {
    constexpr uint32_t AMD_VENDOR_ID = 0x1002;

    // Forward declare diagLog from jni_bridge.cpp; extern'd here
    // (or just define inline)
    void diagLog(const char* fmt, ...) {
        FILE* f = fopen("C:\\Users\\arshjot\\Downloads\\fsr2mod\\fsr4_diag.log", "a");
        if (!f) return;
        va_list args;
        va_start(args, fmt);
        vfprintf(f, fmt, args);
        va_end(args);
        fputc('\n', f);
        fclose(f);
    }
}

D3D12Device& D3D12Device::get() {
    static D3D12Device instance;
    return instance;
}

D3D12DeviceState D3D12Device::ensureInitialized() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != D3D12DeviceState::UNINITIALIZED) {
        diagLog("ensureInitialized: already state=%d", static_cast<int>(state_));
        return state_;
    }
    diagLog("ensureInitialized: initializing D3D12Device");
    state_ = D3D12DeviceState::INITIALIZING;

    if (!createDevice())             { diagLog("FAIL: createDevice"); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandQueue())       { diagLog("FAIL: createCommandQueue"); dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandAllocator())   { diagLog("FAIL: createCommandAllocator"); dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createCommandList())        { diagLog("FAIL: createCommandList"); dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!createFence())              { diagLog("FAIL: createFence"); dispose(); state_ = D3D12DeviceState::FAILED; return state_; }
    if (!warmupOpenSharedHandle())   { diagLog("FAIL: warmupOpenSharedHandle"); dispose(); state_ = D3D12DeviceState::FAILED; return state_; }

    diagLog("D3D12Device initialized: %s (DeviceId=0x%04x)",
        deviceName_.c_str(), adapterDeviceId_);
    std::fprintf(stdout, "[D3D12Device] initialized: %s (DeviceId=0x%04x)\n",
        deviceName_.c_str(), adapterDeviceId_);
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
    if (FAILED(hr)) {
        diagLog("createDevice: CreateDXGIFactory1 failed: 0x%08lx", hr);
        return false;
    }
    diagLog("createDevice: DXGI factory created");

    Microsoft::WRL::ComPtr<IDXGIAdapter1> current;
    SIZE_T maxMemory = 0;
    int adapterCount = 0;

    for (UINT i = 0; factory->EnumAdapters1(i, &current) != DXGI_ERROR_NOT_FOUND; i++) {
        adapterCount++;
        DXGI_ADAPTER_DESC1 desc;
        if (FAILED(current->GetDesc1(&desc))) continue;
        diagLog("  Adapter[%d]: VendorId=0x%04x, DeviceId=0x%04x, Mem=%zu MB",
            i, desc.VendorId, desc.DeviceId, desc.DedicatedVideoMemory / (1024*1024));
        if (desc.VendorId != AMD_VENDOR_ID) continue;
        if (desc.DedicatedVideoMemory > maxMemory) {
            maxMemory = desc.DedicatedVideoMemory;
            adapter_ = current;
            adapterDeviceId_ = desc.DeviceId;
            char name[128] = {};
            size_t converted = 0;
            wcstombs_s(&converted, name, sizeof(name), desc.Description, _TRUNCATE);
            deviceName_ = name;
            diagLog("  -> Selected AMD adapter: %s", name);
        }
    }

    diagLog("createDevice: enumerated %d adapters", adapterCount);

    if (!adapter_) {
        diagLog("createDevice: No AMD adapter found");
        std::fprintf(stderr, "[D3D12Device] No AMD adapter found\n");
        fflush(stderr);
        return false;
    }

    hr = D3D12CreateDevice(adapter_.Get(), D3D_FEATURE_LEVEL_12_0, IID_PPV_ARGS(&device_));
    if (FAILED(hr)) {
        diagLog("createDevice: D3D12CreateDevice failed: 0x%08lx, trying 11_0", hr);
        // Try lower feature level
        hr = D3D12CreateDevice(adapter_.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device_));
        if (FAILED(hr)) {
            diagLog("createDevice: D3D12CreateDevice FL11_0 also failed: 0x%08lx", hr);
            std::fprintf(stderr, "[D3D12Device] D3D12CreateDevice failed: 0x%08lx\n", hr);
            fflush(stderr);
            return false;
        }
        diagLog("createDevice: created with FL11_0");
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
    for (int i = 0; i < 2; i++) {
        if (FAILED(device_->CreateCommandAllocator(
            D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&cmdAllocs_[i])))) {
            return false;
        }
    }
    return true;
}

bool D3D12Device::createCommandList() {
    // Create on allocator[0] initially; Reset() will rebind to the
    // active allocator each frame (see Fsr4Context::dispatch).
    HRESULT hr = device_->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
        cmdAllocs_[0].Get(), nullptr, IID_PPV_ARGS(&cmdList_));
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

bool D3D12Device::warmupOpenSharedHandle() {
    // Some D3D12 drivers (AMD Radeon 860M) crash on the FIRST OpenSharedHandle
    // call after device creation (ACCESS_VIOLATION 0xC0000005). The crash only
    // happens on the very first call — subsequent calls succeed. This is likely
    // a deferred D3D12 runtime initialization issue.
    //
    // Fix: create a tiny committed resource with SHARED flag, export a Win32
    // handle, then immediately OpenSharedHandle to force the runtime to
    // initialize its OpenSharedHandle path. Release everything afterwards.
    diagLog("warmupOpenSharedHandle: creating warm-up resource...");

    D3D12_HEAP_PROPERTIES heapProps{};
    heapProps.Type = D3D12_HEAP_TYPE_DEFAULT;

    D3D12_RESOURCE_DESC resDesc{};
    resDesc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    resDesc.Width = 1;
    resDesc.Height = 1;
    resDesc.DepthOrArraySize = 1;
    resDesc.MipLevels = 1;
    resDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    resDesc.SampleDesc.Count = 1;
    resDesc.Flags = D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;

    Microsoft::WRL::ComPtr<ID3D12Resource> warmupRes;
    HRESULT hr = device_->CreateCommittedResource(
        &heapProps, D3D12_HEAP_FLAG_SHARED,
        &resDesc, D3D12_RESOURCE_STATE_COMMON,
        nullptr, IID_PPV_ARGS(&warmupRes));
    if (FAILED(hr)) {
        diagLog("warmupOpenSharedHandle: CreateCommittedResource failed: 0x%08lx", hr);
        return false;
    }

    // Need ID3D12Device5+ for CreateSharedHandle
    Microsoft::WRL::ComPtr<ID3D12Device5> device5;
    if (FAILED(device_.As(&device5))) {
        diagLog("warmupOpenSharedHandle: device does not support ID3D12Device5");
        warmupRes.Reset();
        return false;
    }

    HANDLE warmupHandle = nullptr;
    hr = device5->CreateSharedHandle(warmupRes.Get(), nullptr,
        GENERIC_ALL, nullptr, &warmupHandle);
    if (FAILED(hr)) {
        diagLog("warmupOpenSharedHandle: CreateSharedHandle failed: 0x%08lx", hr);
        warmupRes.Reset();
        return false;
    }

    // Open the handle back — this is the call that triggers the crash on
    // the first invocation if the driver has the deferred-init bug.
    Microsoft::WRL::ComPtr<ID3D12Resource> openedWarmup;
    hr = device_->OpenSharedHandle(warmupHandle, IID_PPV_ARGS(&openedWarmup));
    if (FAILED(hr)) {
        diagLog("warmupOpenSharedHandle: OpenSharedHandle failed: 0x%08lx", hr);
        CloseHandle(warmupHandle);
        warmupRes.Reset();
        return false;
    }

    // Success — release warm-up resources
    openedWarmup.Reset();
    CloseHandle(warmupHandle);
    warmupRes.Reset();
    diagLog("warmupOpenSharedHandle: OK");
    return true;
}

uint64_t D3D12Device::signalFence() {
    if (!queue_ || !fence_) return 0;
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
	    if (disposed_) return;
	    disposed_ = true;
	    if (fenceEvent_) { CloseHandle(fenceEvent_); fenceEvent_ = nullptr; }
	    cmdList_.Reset();
	    for (int i = 0; i < 2; i++) {
	        cmdAllocs_[i].Reset();
	        allocatorFenceValues_[i] = 0;
	    }
	    currentAllocatorIndex_ = 0;
	    fence_.Reset();
	    queue_.Reset();
	    device_.Reset();
	    adapter_.Reset();
	    deviceName_.clear();
	    adapterDeviceId_ = 0;
	    fenceValue_ = 0;
	    // Reset state so ensureInitialized() can re-create if needed,
	    // and the passive destructor (~D3D12Device) is a no-op when
	    // called during DLL unloading / process shutdown.
	    state_ = D3D12DeviceState::UNINITIALIZED;
	}

	D3D12Device::~D3D12Device() {
	    if (!disposed_) {
	        dispose();
	    }
	    // If disposed_ == true, all cleanup was done by the explicit
	    // dispose() call from shutdownFsr4(). The destructor must NOT
	    // call ComPtr::Reset() or CloseHandle during DLL unloading
	    // because the D3D12/DXGI runtimes may already be partially
	    // torn down, causing STATUS_STACK_BUFFER_OVERRUN (0xC0000409).
	}

} // namespace fsr
