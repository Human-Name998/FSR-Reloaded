#pragma once
#include <ffx_api.h>
#include <ffx_upscale.h>
#include <ffx_api_dx12.h>
#include <d3d12.h>
#include <cstdint>

#include <string>

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
    // When true, dispatch() skips the FSR4 SDK and does a simple
    // CopyResource from color → output. Used as a pipeline test to
    // validate D3D12 shared memory + GPU compute paths independently
    // of the SDK (e.g. when the SDK crashes after FG context creation).
    static bool g_bypassFsr4;
    // FSR4 SDK internal sharpening (2026-08-13): follows the same config.sharpness
    // slider as FSR1/2/3's GLSL RCAS. Written by Java_com_fsr2mod_vulkan_Fsr4Native_setSharpness
    // (jni_bridge.cpp) every frame; 0.0 = sharpening off. RECOVERED 2026-08-18 (the
    // post-Aug-13 native source was lost; this statically replicates the documented
    // wiring from AGENTS.md).
    static float g_fsr4Sharpness;

    bool initialize(uint32_t renderW, uint32_t renderH,
                    uint32_t displayW, uint32_t displayH);
    bool dispatch(const Fsr4DispatchParams& params);
    void finish();
    void destroy();
    bool isInitialized() const { return ffxContext_ != nullptr; }

private:
    ffxContext ffxContext_ = nullptr;
    uint64_t lastFenceValue_ = 0;
    uint32_t cachedRenderW_ = 0;
    uint32_t cachedRenderH_ = 0;
    uint32_t cachedDisplayW_ = 0;
    uint32_t cachedDisplayH_ = 0;
};

} // namespace fsr
