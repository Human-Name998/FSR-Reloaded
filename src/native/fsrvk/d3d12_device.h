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

    /// Returns the current (round-robin) command allocator.
    /// Toggled each frame to let CPU record the next frame while GPU
    /// processes the previous one.
    ID3D12CommandAllocator* commandAllocator() const {
        return cmdAllocs_[currentAllocatorIndex_].Get();
    }

    /// Returns the fence value for the OTHER (idle) allocator.
    /// Used in the pre-dispatch wait: we only need the other allocator's
    /// GPU work to finish before we can reset it.
    uint64_t otherAllocatorFenceValue() const {
        return allocatorFenceValues_[1 - currentAllocatorIndex_];
    }

    /// Returns the fence value for the CURRENT (this frame) allocator.
    uint64_t currentAllocatorFenceValue() const {
        return allocatorFenceValues_[currentAllocatorIndex_];
    }

    /// Stores the most recent fence value for the current allocator,
    /// then advances the round-robin index for the next frame.
    uint64_t advanceAllocator(uint64_t fenceValue) {
        allocatorFenceValues_[currentAllocatorIndex_] = fenceValue;
        currentAllocatorIndex_ = 1 - currentAllocatorIndex_;
        return fenceValue;
    }

    ID3D12Fence* fence() const { return fence_.Get(); }
    HANDLE fenceEvent() const { return fenceEvent_; }

    uint64_t signalFence();
    bool waitForFenceValue(uint64_t value, DWORD timeoutMs = 100);
    uint64_t lastCompletedFenceValue() const { return fence_ ? fence_->GetCompletedValue() : UINT64_MAX; }

    uint32_t adapterDeviceId() const { return adapterDeviceId_; }

    /// True when the selected adapter is an AMD integrated GPU (iGPU, e.g.
    /// Radeon 860M — shared-memory part with < 4GB dedicated VRAM). Used to
    /// pre-arm the bilinear bypass so the FSR4 SDK is never dispatched when an
    /// FG context exists: on these parts the SDK's ffxDispatch deterministically
    /// throws 0xe06d7363 AND removes the D3D12 device (0x887a0005), which kills
    /// every D3D12 path (incl. the post-crash bypass) for the session.
    bool isIntegratedAmd() const { return isIgpu_; }

    D3D12DeviceState ensureInitialized();
    void resetForRetry();
    void dispose();

    /// Releases and recreates the round-robin command allocator pair +
    /// command list, zeroing the per-allocator fence bookkeeping. Used to
    /// discard a command list an SDK call crashed MID-RECORD without
    /// submitting it (see Fsr4Context::dispatch). Plain COM calls + diagLog
    /// only (no C++ unwinding), so it is safe to call from an SEH __except
    /// handler. Caller must be on the render thread with the device READY
    /// (same concurrency contract as the per-frame dispatch path).
    bool recreateCommandResources();

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
    bool warmupOpenSharedHandle();

    Microsoft::WRL::ComPtr<ID3D12Device> device_;
    Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter_;
    Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue_;
    /// Dual command allocators for round-robin (double-buffered) submission.
    /// Each frame uses one allocator; the other is idle and safe to reset
    /// once the GPU finishes its work, without waiting for the current
    /// frame's GPU work to complete.
    Microsoft::WRL::ComPtr<ID3D12CommandAllocator> cmdAllocs_[2];
    /// Last fence value signaled per-allocator. Used to know which fence
    /// value to wait for before recycling a specific allocator.
    uint64_t allocatorFenceValues_[2] = {0, 0};
    /// Round-robin index into cmdAllocs_. Toggled each frame.
    int currentAllocatorIndex_ = 0;

    Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList> cmdList_;
    Microsoft::WRL::ComPtr<ID3D12Fence> fence_;
    HANDLE fenceEvent_ = nullptr;

    uint64_t fenceValue_ = 0;
    uint32_t adapterDeviceId_ = 0;
    bool isIgpu_ = false;
    std::string deviceName_;

    D3D12DeviceState state_ = D3D12DeviceState::UNINITIALIZED;
    bool disposed_ = false;
    std::mutex mutex_;
};

} // namespace fsr
