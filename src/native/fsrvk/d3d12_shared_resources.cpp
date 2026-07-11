#include "d3d12_shared_resources.h"
#include <cstdio>
#include <cstdarg>

namespace fsr {

namespace {
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

    diagLog("D3D12SharedResources::create: %dx%d -> %dx%d, device=%p",
        renderW, renderH, displayW, displayH, (void*)device);

    for (int i = 0; i < COUNT; i++) {
        bool isOutput = (i == OUTPUT);
        uint32_t w = isOutput ? displayW : renderW;
        uint32_t h = isOutput ? displayH : renderH;

        auto info = texInfo(i);
        if (info.dxgiFmt == DXGI_FORMAT_UNKNOWN) {
            diagLog("  [%d] UNKNOWN format -> destroy & fail", i);
            destroy();
            return false;
        }

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

        static const char* names[] = {"COLOR", "DEPTH", "MV", "REACTIVE", "OUTPUT"};
        const char* texName = (i >= 0 && i < 5) ? names[i] : "UNKNOWN";

        diagLog("  [%d/%s] CreateCommittedResource: %dx%d fmt=%d flags=SHARED|UAV",
            i, texName, w, h, info.dxgiFmt);

        HRESULT hr = device->CreateCommittedResource(
            &heapProps,
            D3D12_HEAP_FLAG_SHARED,
            &desc,
            D3D12_RESOURCE_STATE_COMMON,
            nullptr,
            IID_PPV_ARGS(&resources[i]));

        if (FAILED(hr)) {
            diagLog("  [%d/%s] FAILED: CreateCommittedResource hr=0x%08lx", i, texName, hr);
            std::fprintf(stderr, "[D3D12SharedResources] Create[%d] failed: 0x%08lx\n", i, hr);
            fflush(stderr);
            destroy();
            return false;
        }
        diagLog("  [%d/%s] CreateCommittedResource OK, resource=%p", i, texName, (void*)resources[i]);

        hr = device->CreateSharedHandle(resources[i], nullptr, GENERIC_ALL, nullptr, &win32Handles[i]);
        if (FAILED(hr)) {
            diagLog("  [%d/%s] FAILED: CreateSharedHandle hr=0x%08lx", i, texName, hr);
            std::fprintf(stderr, "[D3D12SharedResources] CreateSharedHandle[%d] failed: 0x%08lx\n", i, hr);
            fflush(stderr);
            destroy();
            return false;
        }
        diagLog("  [%d/%s] CreateSharedHandle OK, handle=%p", i, texName, (void*)win32Handles[i]);

        allocationSizes[i] = static_cast<uint64_t>(w) * h * info.bpp;
    }
    diagLog("D3D12SharedResources::create: SUCCESS");
    return true;
}

void D3D12SharedResources::destroy() {
    for (auto& h : win32Handles) { if (h) { CloseHandle(h); h = nullptr; } }
    for (auto& r : resources)    { if (r) { r->Release(); r = nullptr; } }
    for (auto& s : allocationSizes) s = 0;
}

} // namespace fsr
