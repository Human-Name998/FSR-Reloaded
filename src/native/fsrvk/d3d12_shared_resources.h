#pragma once
#include <d3d12.h>
#include <windows.h>
#include <wrl.h>
#include <array>
#include <cstdint>

namespace fsr {

struct D3D12SharedResources {
    ID3D12Resource* resources[10] = {};
    HANDLE win32Handles[10] = {};
    uint64_t allocationSizes[10] = {};

    enum TexIdx : int {
        COLOR = 0,
        DEPTH = 1,
        MV = 2,
        REACTIVE = 3,
        OUTPUT = 4,
        FG_OUTPUT = 5,    // display-size RGBA8 for frame generation output (slot 0)
        FG_PRESENT = 6,   // display-size RGBA8 = real frame + GUI (FG presentColor)
        FG_OUTPUT_1 = 7,   // display-size RGBA8 for multi-frame FG output (slot 1)
        FG_OUTPUT_2 = 8,   // display-size RGBA8 for multi-frame FG output (slot 2)
        FG_OUTPUT_3 = 9,   // display-size RGBA8 for multi-frame FG output (slot 3)
        COUNT = 10
    };

    bool create(ID3D12Device* device,
                uint32_t renderW, uint32_t renderH,
                uint32_t displayW, uint32_t displayH);
    void destroy();
};

} // namespace fsr
