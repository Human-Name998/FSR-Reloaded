#pragma once
#include <ffx_api.h>
#include <ffx_framegeneration.h>
#include <ffx_api_dx12.h>
#include <d3d12.h>
#include <wrl.h>
#include <cstdint>
#include <string>

namespace fsr {

class D3D12SharedResources;

struct Fg4PrepareParams {
    uint64_t frameID;                 // monotonically increasing frame counter
    float frameTimeDeltaMs;           // ms since last frame
    float jitterX;
    float jitterY;
    float cameraNear;
    float cameraFar;
    float cameraFovAngleVertical;     // radians
    float viewSpaceToMetersFactor;

    // Camera world-space vectors (normalized)
    float cameraPosition[3];
    float cameraUp[3];
    float cameraRight[3];
    float cameraForward[3];

    bool reset;
};

class Fg4Context {
public:
    bool initialize(uint32_t renderW, uint32_t renderH,
                    uint32_t displayW, uint32_t displayH,
                    D3D12SharedResources* colorPool, int colorIndex,
                    int depthIndex, int mvIndex, int outputIndex,
                    int hudlessColorIndex,
                    const wchar_t* fgDllDir);
    bool prepare(const Fg4PrepareParams& params);
    bool generate(uint32_t numGeneratedFrames = 1);
    // Wait for the last submitted FG command list to complete. Returns false
    // if the dedicated fence did not signal within the timeout (the GL side
    // must NOT blit the output texture then — it may never be written).
    bool finish();
    void destroy();
    bool isInitialized() const { return ffxContext_ != nullptr; }
    bool isPermanentlyDisabled() const { return permanentlyDisabled_; }

    uint64_t lastFenceValue() const { return lastFenceValue_; }

private:
    ffxContext ffxContext_ = nullptr;
    uint64_t lastFenceValue_ = 0;
    uint64_t currentFrameID_ = 0;  // latest frame ID from prepare
    bool reset_ = true;            // latest reset flag from prepare
    bool forceReset_ = false;      // force reset on next prepare after crash recovery

    // Dedicated D3D12 command recording resources for FG.
    // The FG SDK must NOT share the D3D12Device's command list/allocator/fence
    // with the FSR4 upscaler context. Both contexts record into the same
    // round-robin allocator pair and signal the same fence, which causes
    // subtle state corruption: the SDK caches internal references to the
    // command list/allocator across dispatches, and the FSR4 dispatch
    // resetting the shared allocator between FG prepare and FG generate
    // invalidates those cached references → access violation (0xc0000005)
    // on the second generate call.
    Microsoft::WRL::ComPtr<ID3D12CommandAllocator> fgCmdAlloc_;
    Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList> fgCmdList_;
    Microsoft::WRL::ComPtr<ID3D12Fence> fgFence_;
    HANDLE fgFenceEvent_ = nullptr;
    uint64_t fgFenceValue_ = 0;

    // Pool resource indices
    const D3D12SharedResources* colorPool_ = nullptr;
    int colorIndex_ = -1;          // presentColor at generate (real frame + GUI)
    int depthIndex_ = -1;
    int mvIndex_ = -1;
    int outputIndex_ = -1;          // primary FG output (pool slot 5)
    int hudlessColorIndex_ = -1;   // HUDLessColor at configure (real frame, no GUI)

    // Multi-frame FG: additional output pool indices for numGeneratedFrames > 1.
    // outputIndex_ is always outputs[0]; these are outputs[1..3].
    // Pool layout: 5=FG_OUTPUT (slot 0), 7=FG_OUTPUT_1, 8=FG_OUTPUT_2, 9=FG_OUTPUT_3.
    static constexpr int FG_OUTPUT_SLOTS[4] = {5, 7, 8, 9};

    // Cached dimensions
    uint32_t cachedRenderW_ = 0;
    uint32_t cachedRenderH_ = 0;
    uint32_t cachedDisplayW_ = 0;
    uint32_t cachedDisplayH_ = 0;

    // Directory containing FG SDK DLLs (for LoadLibraryExW)
    std::wstring fgDllDir_;

    // Crash recovery: after MAX_CONSECUTIVE_CRASHES consecutive SDK crashes,
    // permanently disable FG for this session to prevent infinite crash loops.
    int consecutiveCrashes_ = 0;
    bool permanentlyDisabled_ = false;
    static constexpr int MAX_CONSECUTIVE_CRASHES = 5;

    // Internal: re-create the SDK context after a crash destroyed it.
    bool reinitialize();

    // Create/destroy dedicated D3D12 command recording resources.
    bool createDedicatedCommandResources();
    void destroyDedicatedCommandResources();

    // Signal the dedicated fence and return the new value.
    uint64_t signalFgFence();
    // Wait for the dedicated fence to reach a value.
    bool waitForFgFence(uint64_t value, DWORD timeoutMs = 2000);
};

} // namespace fsr
