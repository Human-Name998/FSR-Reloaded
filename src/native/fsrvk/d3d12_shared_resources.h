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
