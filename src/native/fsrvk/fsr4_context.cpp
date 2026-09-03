#include "fsr4_context.h"
#include "d3d12_device.h"
#include <cstdio>
#include <cstring>
#include <cwchar>

#ifdef _WIN32
#include <windows.h>
#include <excpt.h>
#include <d3dcompiler.h>
#endif

namespace fsr {

// ----------------------------------------------------------------
// Diagnostics — written to fsr4_diag.log and stderr
// ----------------------------------------------------------------
namespace {
    void diagLog(const char* fmt, ...) {
        FILE* f = fopen("C:\\Users\\arshjot\\Downloads\\fsr2mod\\fsr4_diag.log", "a");
        va_list args;
        va_start(args, fmt);
        if (f) {
            vfprintf(f, fmt, args);
            fputc('\n', f);
            fclose(f);
        }
        va_end(args);
        va_start(args, fmt);
        vfprintf(stderr, fmt, args);
        fputc('\n', stderr);
        fflush(stderr);
        va_end(args);
    }
}

// ----------------------------------------------------------------
// Dynamic loading of SDK functions from the provider DLL directly.
//
// We skip amd_fidelityfx_loader_dx12.dll entirely — the loader
// v2.3.0 returns rc=2 (unknown descriptor type) for upscale
// descriptors. The provider (amd_fsr4_api.dll / internal name
// amd_fidelityfx_upscaler_dx12.dll) handles them correctly.
//
// NOTE on typedef names: the canonical FSR API typedefs in
// <ffx_api.h> are FfnCreateContext / FfnDispatch /
// FfnDestroyContext. Earlier drafts of this file used the
// invented names "FfnCreateContext" / "FfnDispatch" /
// "FfnDestroyContext" — those are not real types and would not
// compile against the actual SDK headers. The correct names are
// used below. If your build fails because your SDK version
// doesn't expose these typedefs, define them locally:
//
//   using FfnCreateContext  = ffxReturnCode_t (*)(ffxContext*,
//         ffxCreateContextDescHeader*, ffxAllocationCallbacks*);
//   using FfnDispatch       = ffxReturnCode_t (*)(ffxContext*,
//         ffxDispatchDescHeader*);
//   using FfnDestroyContext = ffxReturnCode_t (*)(ffxContext*,
//         ffxAllocationCallbacks*);
// ----------------------------------------------------------------
namespace {

    FfnCreateContext   g_ffxCreateContext  = nullptr;
    FfnDispatch        g_ffxDispatch       = nullptr;
    FfnDestroyContext  g_ffxDestroyContext = nullptr;

    bool loadSdkFunctions() {
        if (g_ffxCreateContext) return true;

        // amd_fsr4_api.dll is the actual provider (FSR4 / RDNA 3.5).
        // amd_fidelityfx_upscaler_dx12.dll is an alternate internal
        // name used by some SDK builds; check it first as a fallback.
        HMODULE providerMod = GetModuleHandleW(L"amd_fidelityfx_upscaler_dx12.dll");
        if (!providerMod) {
            providerMod = GetModuleHandleW(L"amd_fsr4_api.dll");
        }
        diagLog("[Fsr4Context] GetModuleHandle(provider): %p", (void*)providerMod);
        if (!providerMod) {
            diagLog("[Fsr4Context] FAIL: provider NOT FOUND in process — "
                    "ensure amd_fsr4_api.dll is loaded (e.g. via LoadLibrary "
                    "in JVM bootstrap or as a JNI dependency)");
            return false;
        }

        g_ffxCreateContext = reinterpret_cast<FfnCreateContext>(
            GetProcAddress(providerMod, "ffxCreateContext"));
        g_ffxDispatch = reinterpret_cast<FfnDispatch>(
            GetProcAddress(providerMod, "ffxDispatch"));
        g_ffxDestroyContext = reinterpret_cast<FfnDestroyContext>(
            GetProcAddress(providerMod, "ffxDestroyContext"));

        if (!g_ffxCreateContext)  diagLog("[Fsr4Context] GetProcAddress(ffxCreateContext) FAILED");
        if (!g_ffxDispatch)       diagLog("[Fsr4Context] GetProcAddress(ffxDispatch) FAILED");
        if (!g_ffxDestroyContext) diagLog("[Fsr4Context] GetProcAddress(ffxDestroyContext) FAILED");

        bool ok = g_ffxCreateContext && g_ffxDispatch && g_ffxDestroyContext;
        if (ok) {
            diagLog("[Fsr4Context] using provider directly (handle=%p)", (void*)providerMod);
        } else {
            diagLog("[Fsr4Context] FAIL: one or more SDK exports missing from provider");
        }
        return ok;
    }

    // ------------------------------------------------------------
    // Build an FfxApiResource with the CORRECT state field.
    //
    // The FSR4 DX12 backend reads FfxApiResource.state to know the
    // CURRENT D3D12 resource state at dispatch time, then inserts
    // its own internal barriers from that state.
    //
    // CRITICAL: state MUST match actual D3D12 resource state.
    // Shared resources (D3D12_HEAP_FLAG_SHARED) have NO implicit
    // state promotion or decay. We always report COMMON because
    // our resources start in COMMON (at creation) and the SDK is
    // responsible for returning them to COMMON after dispatch.
    //
    // Do NOT add manual ResourceBarrier calls — the SDK handles
    // all transitions internally. Manual barriers with a wrong
    // StateBefore on shared resources corrupt the GPU state
    // tracker and cause an AMD driver hang.
    // ------------------------------------------------------------
    FfxApiResource makeResource(ID3D12Resource* res,
                                uint32_t w, uint32_t h,
                                uint32_t fmt, uint32_t usage,
                                uint32_t state,
                                const wchar_t* name) {
        FfxApiResource r{};
        r.resource = res;
        r.description.type     = FFX_API_RESOURCE_TYPE_TEXTURE2D;
        r.description.width    = w;
        r.description.height   = h;
        r.description.depth    = 1;
        r.description.mipCount = 1;
        r.description.format   = fmt;
        r.description.flags    = FFX_API_RESOURCE_FLAGS_NONE;
        r.description.usage    = usage;
        r.state                = state;   // <-- matches current D3D12 resource state (COMMON)
        (void)name;
        return r;
    }

    // ------------------------------------------------------------
    // Bilinear upscale compute shader (HLSL). Used as pipeline test
    // bypass when the FSR4 SDK crashes (e.g. after FG context creation).
    //
    // Reads input at render size (via SRV), writes output at display
    // size (via UAV), with bilinear interpolation.
    // ------------------------------------------------------------
    const char* g_bilinearUpscaleCS = R"(
        RWTexture2D<float4> g_output : register(u0);
        Texture2D<float4> g_input   : register(t0);

        [numthreads(16, 16, 1)]
        void main(uint3 dtid : SV_DispatchThreadID) {
            uint2 outSize;
            g_output.GetDimensions(outSize.x, outSize.y);
            uint2 inSize;
            g_input.GetDimensions(inSize.x, inSize.y);

            // Map output pixel to source UV, then to source pixel coords
            float2 uv = (dtid.xy + 0.5) / float2(outSize);
            float2 srcPos = uv * float2(inSize) - 0.5;

            // Clamp to valid range
            int2 maxCoord = int2(inSize) - 1;
            int2 srcA = int2(clamp((int)floor(srcPos.x), 0, maxCoord.x),
                             clamp((int)floor(srcPos.y), 0, maxCoord.y));
            int2 srcB = int2(min(srcA.x + 1, maxCoord.x), srcA.y);
            int2 srcC = int2(srcA.x, min(srcA.y + 1, maxCoord.y));
            int2 srcD = int2(min(srcA.x + 1, maxCoord.x), min(srcA.y + 1, maxCoord.y));
            float2 frac = srcPos - floor(srcPos);

            float4 a = g_input[srcA];
            float4 b = g_input[srcB];
            float4 c = g_input[srcC];
            float4 d = g_input[srcD];

            float4 top = lerp(a, b, frac.x);
            float4 bot = lerp(c, d, frac.x);
            g_output[dtid.xy] = lerp(top, bot, frac.y);
        }
    )";

    // Pipeline test bypass resources (lazily created)
    ID3D12RootSignature*   g_bypassRootSignature = nullptr;
    ID3D12PipelineState*   g_bypassPSO           = nullptr;
    ID3D12DescriptorHeap*  g_bypassDescHeap      = nullptr;
    uint32_t               g_bypassDescSize      = 0;

    // Cached resource pointers for descriptor reuse detection
    ID3D12Resource*        g_bypassLastInput     = nullptr;
    ID3D12Resource*        g_bypassLastOutput    = nullptr;

    bool createBypassResources(ID3D12Device* device) {
        if (g_bypassRootSignature) return true;  // already created

        // ---------- Compile compute shader ----------
        ID3DBlob* shaderBlob = nullptr;
        ID3DBlob* errorBlob = nullptr;
        HRESULT hr = D3DCompile(g_bilinearUpscaleCS, strlen(g_bilinearUpscaleCS),
                                nullptr, nullptr, nullptr, "main", "cs_5_0",
                                D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &shaderBlob, &errorBlob);
        if (FAILED(hr)) {
            const char* errMsg = errorBlob ? (const char*)errorBlob->GetBufferPointer() : "unknown";
            diagLog("[Fsr4Context] bypass: D3DCompile FAILED hr=0x%08lx: %s", hr, errMsg);
            if (errorBlob) errorBlob->Release();
            return false;
        }
        if (errorBlob) errorBlob->Release();

        // ---------- Root signature ----------
        // Root table with 2 descriptor ranges: SRV (t0) and UAV (u0)
        D3D12_DESCRIPTOR_RANGE ranges[2] = {};
        ranges[0].RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
        ranges[0].NumDescriptors = 1;
        ranges[0].BaseShaderRegister = 0;
        ranges[0].RegisterSpace = 0;
        ranges[0].OffsetInDescriptorsFromTableStart = 0;

        ranges[1].RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_UAV;
        ranges[1].NumDescriptors = 1;
        ranges[1].BaseShaderRegister = 0;
        ranges[1].RegisterSpace = 0;
        ranges[1].OffsetInDescriptorsFromTableStart = 1;

        D3D12_ROOT_PARAMETER params[2] = {};
        params[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        params[0].DescriptorTable.pDescriptorRanges = &ranges[0];
        params[0].DescriptorTable.NumDescriptorRanges = 1;
        params[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;

        params[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        params[1].DescriptorTable.pDescriptorRanges = &ranges[1];
        params[1].DescriptorTable.NumDescriptorRanges = 1;
        params[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;

        D3D12_ROOT_SIGNATURE_DESC rsDesc = {};
        rsDesc.NumParameters = 2;
        rsDesc.pParameters = params;
        rsDesc.Flags = D3D12_ROOT_SIGNATURE_FLAG_NONE;

        ID3DBlob* rsBlob = nullptr;
        hr = D3D12SerializeRootSignature(&rsDesc, D3D_ROOT_SIGNATURE_VERSION_1,
                                         &rsBlob, &errorBlob);
        if (FAILED(hr)) {
            diagLog("[Fsr4Context] bypass: D3D12SerializeRootSignature FAILED hr=0x%08lx", hr);
            if (errorBlob) errorBlob->Release();
            shaderBlob->Release();
            return false;
        }
        if (errorBlob) errorBlob->Release();

        hr = device->CreateRootSignature(0, rsBlob->GetBufferPointer(), rsBlob->GetBufferSize(),
                                         IID_PPV_ARGS(&g_bypassRootSignature));
        rsBlob->Release();
        if (FAILED(hr)) {
            diagLog("[Fsr4Context] bypass: CreateRootSignature FAILED hr=0x%08lx", hr);
            shaderBlob->Release();
            return false;
        }

        // ---------- Pipeline state ----------
        D3D12_COMPUTE_PIPELINE_STATE_DESC psoDesc = {};
        psoDesc.pRootSignature = g_bypassRootSignature;
        psoDesc.CS.pShaderBytecode = shaderBlob->GetBufferPointer();
        psoDesc.CS.BytecodeLength = shaderBlob->GetBufferSize();

        hr = device->CreateComputePipelineState(&psoDesc, IID_PPV_ARGS(&g_bypassPSO));
        shaderBlob->Release();
        if (FAILED(hr)) {
            diagLog("[Fsr4Context] bypass: CreateComputePipelineState FAILED hr=0x%08lx", hr);
            g_bypassRootSignature->Release();
            g_bypassRootSignature = nullptr;
            return false;
        }

        // ---------- Descriptor heap (2 descriptors: SRV + UAV) ----------
        D3D12_DESCRIPTOR_HEAP_DESC heapDesc = {};
        heapDesc.NumDescriptors = 2;
        heapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
        heapDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
        hr = device->CreateDescriptorHeap(&heapDesc, IID_PPV_ARGS(&g_bypassDescHeap));
        if (FAILED(hr)) {
            diagLog("[Fsr4Context] bypass: CreateDescriptorHeap FAILED hr=0x%08lx", hr);
            g_bypassPSO->Release();
            g_bypassPSO = nullptr;
            g_bypassRootSignature->Release();
            g_bypassRootSignature = nullptr;
            return false;
        }

        g_bypassDescSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
        g_bypassLastInput = nullptr;
        g_bypassLastOutput = nullptr;

        diagLog("[Fsr4Context] bypass: resources created OK");
        return true;
    }

    void destroyBypassResources() {
        if (g_bypassDescHeap) { g_bypassDescHeap->Release(); g_bypassDescHeap = nullptr; }
        if (g_bypassPSO)      { g_bypassPSO->Release();      g_bypassPSO = nullptr; }
        if (g_bypassRootSignature) { g_bypassRootSignature->Release(); g_bypassRootSignature = nullptr; }
        g_bypassLastInput = nullptr;
        g_bypassLastOutput = nullptr;
        g_bypassDescSize = 0;
        diagLog("[Fsr4Context] bypass: resources destroyed");
    }

    // Update descriptor heap with SRV for input and UAV for output.
    // Creates new descriptors each time the resource pointers change.
    void updateBypassDescriptors(ID3D12Device* device,
                                  ID3D12Resource* input, ID3D12Resource* output,
                                  uint32_t inW, uint32_t inH,
                                  uint32_t outW, uint32_t outH) {
	    if (!g_bypassDescHeap || !device) return;
	    if (input == g_bypassLastInput && output == g_bypassLastOutput) return;  // no change

	    D3D12_CPU_DESCRIPTOR_HANDLE srvHandle = g_bypassDescHeap->GetCPUDescriptorHandleForHeapStart();
	    D3D12_CPU_DESCRIPTOR_HANDLE uavHandle = { srvHandle.ptr + 1 * static_cast<SIZE_T>(g_bypassDescSize) };

	    // SRV for input
	    D3D12_SHADER_RESOURCE_VIEW_DESC srvDesc = {};
	    srvDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	    srvDesc.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
	    srvDesc.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
	    srvDesc.Texture2D.MipLevels = 1;
	    srvDesc.Texture2D.MostDetailedMip = 0;
	    srvDesc.Texture2D.ResourceMinLODClamp = 0;
	    device->CreateShaderResourceView(input, &srvDesc, srvHandle);

	    // UAV for output
	    D3D12_UNORDERED_ACCESS_VIEW_DESC uavDesc = {};
	    uavDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	    uavDesc.ViewDimension = D3D12_UAV_DIMENSION_TEXTURE2D;
	    uavDesc.Texture2D.MipSlice = 0;
	    device->CreateUnorderedAccessView(output, nullptr, &uavDesc, uavHandle);

        g_bypassLastInput = input;
        g_bypassLastOutput = output;
        diagLog("[Fsr4Context] bypass: descriptors updated (input=%p %ux%u, output=%p %ux%u)",
	            (void*)input, inW, inH, (void*)output, outW, outH);
	    }
	}

// Static bypass flag — when true, dispatch() uses a bilinear upscale
// compute shader instead of calling the FSR4 SDK (pipeline test mode).
bool Fsr4Context::g_bypassFsr4 = false;

// SDK internal sharpening strength (0..1, 0 = off). See fsr4_context.h.
float Fsr4Context::g_fsr4Sharpness = 0.0f;

// ----------------------------------------------------------------
bool Fsr4Context::initialize(uint32_t renderW, uint32_t renderH,
                             uint32_t displayW, uint32_t displayH) {
    if (ffxContext_) return true;

    // ------------------------------------------------------------
    // Safety guard: refuse zero/negative dimensions. FSR4 cannot
    // create a context with zero-sized targets — and even if it
    // could, every subsequent dispatch would hang the GPU.
    // ------------------------------------------------------------
    if (renderW == 0 || renderH == 0 || displayW == 0 || displayH == 0) {
        diagLog("[Fsr4Context] FAIL: zero dimensions (render=%ux%u display=%ux%u)",
            renderW, renderH, displayW, displayH);
        return false;
    }

    if (!loadSdkFunctions()) return false;

    auto& dev = D3D12Device::get();
    if (dev.ensureInitialized() != D3D12DeviceState::READY) return false;

    // Query for the highest D3D12 interface the provider supports
    ID3D12Device5* device5 = nullptr;
    ID3D12Device8* device8 = nullptr;
    ID3D12Device* fsrDevice = dev.device();

    if (SUCCEEDED(dev.device()->QueryInterface(IID_PPV_ARGS(&device8)))) {
        diagLog("[Fsr4Context] using ID3D12Device8");
        fsrDevice = device8;
    } else if (SUCCEEDED(dev.device()->QueryInterface(IID_PPV_ARGS(&device5)))) {
        diagLog("[Fsr4Context] using ID3D12Device5");
        fsrDevice = device5;
    } else {
        diagLog("[Fsr4Context] using base ID3D12Device");
    }

    // Build descriptor chain: upscale -> backend(DX12) -> version
    ffxCreateContextDescUpscale upscaleDesc{};
    upscaleDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    // NOTE: FFX_UPSCALE_ENABLE_DEPTH_INVERTED MUST NOT be set (2026-08-30,
    // "fsr 4 edges are jagged when diagonal" fix — regression restored from
    // the validated f7a8682 baseline). Minecraft's depth, as written into the
    // shared pool by DEPTH_COPY_CS / the VK MV-gen, is NOT reversed-Z
    // (near -> depth 0, far -> depth 1). Telling the SDK the depth is inverted
    // makes it linearize depth backwards: every pixel lands on the far plane,
    // the whole image is treated as sky/disoccluded, and the SDK discards all
    // history each frame -> no temporal accumulation -> the output is a pure
    // spatial upscale -> jagged diagonal edges at ANY render scale. This flag
    // was added back during the 1.21.11 port; it was removed in the
    // validated-smooth Aug 6 baseline for exactly this reason. With constant
    // depth 0.5 the flag was a harmless no-op, which is why it only became
    // visible after the Iris depth-version fix (2026-08-29) started writing
    // REAL per-pixel depth into the proxy -> probeFsr4Depth enables
    // uUseDepth=1 -> non-reversed depth hits an inverted context.
    upscaleDesc.flags = FFX_UPSCALE_ENABLE_AUTO_EXPOSURE;
    upscaleDesc.maxRenderSize.width   = static_cast<int32_t>(renderW);
    upscaleDesc.maxRenderSize.height  = static_cast<int32_t>(renderH);
    upscaleDesc.maxUpscaleSize.width  = static_cast<int32_t>(displayW);
    upscaleDesc.maxUpscaleSize.height = static_cast<int32_t>(displayH);
    upscaleDesc.fpMessage = nullptr;

    ffxCreateBackendDX12Desc backendDesc{};
    backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_DX12;
    backendDesc.device = fsrDevice;

    ffxCreateContextDescUpscaleVersion versionDesc{};
    versionDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE_VERSION;
    versionDesc.version = FFX_UPSCALER_VERSION;

    upscaleDesc.header.pNext = reinterpret_cast<ffxApiHeader*>(&backendDesc);
    backendDesc.header.pNext = reinterpret_cast<ffxApiHeader*>(&versionDesc);

    ffxReturnCode_t rc = FFX_API_RETURN_ERROR;
    __try {
        rc = g_ffxCreateContext(&ffxContext_,
            reinterpret_cast<ffxCreateContextDescHeader*>(&upscaleDesc), nullptr);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fsr4Context] CRASH in ffxCreateContext (code=0x%08lx)", GetExceptionCode());
        ffxContext_ = nullptr;
    }

    if (device8) device8->Release();
    if (device5) device5->Release();

    if (rc != FFX_API_RETURN_OK) {
        diagLog("[Fsr4Context] ffxCreateContext FAILED: rc=%u (render=%ux%u display=%ux%u)",
            rc, renderW, renderH, displayW, displayH);
        ffxContext_ = nullptr;
        return false;
    }

    // Cache the dimensions so dispatch() doesn't have to rely on
    // the JNI layer passing them through (which it currently doesn't).
    cachedRenderW_  = renderW;
    cachedRenderH_  = renderH;
    cachedDisplayW_ = displayW;
    cachedDisplayH_ = displayH;

    lastFenceValue_ = 0;
    diagLog("[Fsr4Context] init OK (%ux%u -> %ux%u, cached)",
        renderW, renderH, displayW, displayH);
    return true;
}

// ----------------------------------------------------------------
bool Fsr4Context::dispatch(const Fsr4DispatchParams& params) {
    if (!ffxContext_) return false;

    // ------------------------------------------------------------
    // Resolve effective dimensions. The JNI bridge currently never
    // populates params.renderW/H/displayW/H — they default to 0.
    // Use the cached values from initialize() as the source of
    // truth, and let non-zero params values override (for dynamic
    // resolution scenarios where the caller does populate them).
    // ------------------------------------------------------------
    uint32_t renderW  = (params.renderW  > 0) ? params.renderW  : cachedRenderW_;
    uint32_t renderH  = (params.renderH  > 0) ? params.renderH  : cachedRenderH_;
    uint32_t displayW = (params.displayW > 0) ? params.displayW : cachedDisplayW_;
    uint32_t displayH = (params.displayH > 0) ? params.displayH : cachedDisplayH_;

    if (renderW == 0 || renderH == 0 || displayW == 0 || displayH == 0) {
        diagLog("[Fsr4Context] dispatch: ABORT — zero dimensions "
                "(params=%ux%u/%ux%u cached=%ux%u/%ux%u). Refusing to dispatch "
                "to prevent GPU hang.",
            params.renderW, params.renderH, params.displayW, params.displayH,
            cachedRenderW_, cachedRenderH_, cachedDisplayW_, cachedDisplayH_);
        return false;
    }

	auto& dev = D3D12Device::get();

	// Check device health. The D3D12 device can be removed between frames
	// (e.g. from a GPU timeout in the OpenGL scene render, or from the FG
	// SDK's internal GPU operations). ensureInitialized() detects this via
	// GetDeviceRemovedReason() and attempts recovery.
	if (dev.ensureInitialized() != D3D12DeviceState::READY) {
	    diagLog("[Fsr4Context] dispatch: device not READY — skipping frame");
	    return false;
	}

	    // Wait only for the OTHER allocator's GPU work to complete.
	    // With dual allocators (round-robin), the current allocator was
	    // used two frames ago and is guaranteed safe to reset once the
	    // other allocator's work is done. This lets the CPU start recording
	    // the next frame while the current frame is still executing on GPU.
	    uint64_t waitFence = dev.otherAllocatorFenceValue();
	    if (waitFence > 0) {
	        if (dev.fence()->GetCompletedValue() < waitFence) {
	            if (!dev.waitForFenceValue(waitFence, 2000)) {
	                diagLog("[Fsr4Context] dispatch: allocator fence wait timed out (val=%llu) — "
	                        "GPU may be hung. Skipping.", (unsigned long long)waitFence);
	                return false;
	            }
	        }
	    }

	    // GUARD (2026-08-30, freeze after the first-frame crash): if a prior
	    // SDK-crash recovery left the command resources missing (a failed
	    // recreateCommandResources after the crash), dereferencing the null
	    // allocator would fault — and the cascade into Java's teardown +
	    // needsRecreate rebuild is the known render-thread hang on this iGPU.
	    // Fail cleanly instead: Java's dispatch-failure streak hard-disables
	    // to FSR1 (D3D12-free) after a few frames, and FG is gated off too.
    if (!dev.commandAllocator() || !dev.commandList()) {
        diagLog("[Fsr4Context] dispatch: command resources missing — device "
                "presumed broken; skipping frame");
        return false;
    }

	    // Reset the current round-robin allocator + rebind the command list.
	    // Since the other allocator's GPU work is done (waited above), this
	    // allocator is idle and safe to reset.
    HRESULT hr = dev.commandAllocator()->Reset();
    if (FAILED(hr)) {
        diagLog("[Fsr4Context] dispatch: allocator Reset FAILED hr=0x%08lx "
                "(GPU still in flight?) — skipping frame", hr);
        return false;
    }
    hr = dev.commandList()->Reset(dev.commandAllocator(), nullptr);
    if (FAILED(hr)) {
        diagLog("[Fsr4Context] dispatch: command list Reset FAILED hr=0x%08lx", hr);
        return false;
    }

    // ------------------------------------------------------------
    // Resource state management for shared D3D12 textures.
    //
    // Shared resources (D3D12_HEAP_FLAG_SHARED) DO NOT support
    // implicit state promotion or decay. However, the FSR4 SDK
    // (falling back to FSR 3.1.x on RDNA 3.5) handles ALL state
    // transitions internally via its DX12 backend. We provide
    // the current state via FfxApiResource.state and the SDK
    // transitions from that state to what it needs and back.
    //
    // CRITICAL: Do NOT add manual ResourceBarrier calls around
    // ffxDispatch. The SDK's backend inserts its OWN barriers,
    // and manual barriers with a wrong StateBefore on shared
    // resources will corrupt the GPU state tracker and cause a
    // GPU hang (especially on AMD RDNA drivers).
    //
    // The resources start in D3D12_RESOURCE_STATE_COMMON (set at
    // creation time in D3D12SharedResources::create). We report
    // that state as FFX_API_RESOURCE_STATE_COMMON. The SDK
    // transitions from COMMON to the states it needs, does its
    // work, and transitions back. No additional barriers needed.
    // ------------------------------------------------------------
    FfxApiResource colorRes    = makeResource(params.color,        renderW,  renderH,
        FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, FFX_API_RESOURCE_USAGE_READ_ONLY,
        FFX_API_RESOURCE_STATE_COMMON, L"color");
    FfxApiResource depthRes    = makeResource(params.depth,        renderW,  renderH,
        FFX_API_SURFACE_FORMAT_R32_FLOAT,      FFX_API_RESOURCE_USAGE_READ_ONLY,
        FFX_API_RESOURCE_STATE_COMMON, L"depth");
    FfxApiResource mvRes       = makeResource(params.motionVectors, renderW,  renderH,
        FFX_API_SURFACE_FORMAT_R16G16_FLOAT,   FFX_API_RESOURCE_USAGE_READ_ONLY,
        FFX_API_RESOURCE_STATE_COMMON, L"motionVectors");
    FfxApiResource reactiveRes = makeResource(params.reactive,     renderW,  renderH,
        FFX_API_SURFACE_FORMAT_R8_UNORM,       FFX_API_RESOURCE_USAGE_READ_ONLY,
        FFX_API_RESOURCE_STATE_COMMON, L"reactive");
    FfxApiResource outputRes   = makeResource(params.output,       displayW, displayH,
        FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, FFX_API_RESOURCE_USAGE_UAV,
        FFX_API_RESOURCE_STATE_COMMON, L"output");

    ffxDispatchDescUpscale dispatchDesc{};
    dispatchDesc.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatchDesc.commandList = dev.commandList();
    dispatchDesc.color         = colorRes;
    dispatchDesc.depth         = depthRes;
    dispatchDesc.motionVectors = mvRes;
    dispatchDesc.reactive      = reactiveRes;
    dispatchDesc.output        = outputRes;
    dispatchDesc.jitterOffset.x = params.jitterX;
    // Y-SIGN CONVENTION (2026-08-08 fix, restored 2026-08-30): the FSR4/FSR3.1
    // SDK composites projection jitter in DirectX NDC (+Y down) — the docs'
    // example is (2*jX/W, -2*jY/H) with positive jitterOffset.y = "render
    // shifted DOWN". Our render applies +jitterY*2/h in OpenGL clip space
    // (+Y up), so the raw +jitterY would make the SDK de-jitter history by the
    // wrong per-frame vertical delta -> misaligned history -> history rejected
    // -> spatial rendering -> jagged diagonal edges. Negate at the SDK
    // boundary so its internal de-jitter matches the render that actually
    // happened. The Java render-side +2*jY/H stays untouched. Both dispatch
    // sites (GL current-frame jitter, VKMod prev-frame jitter) feed this same
    // function, so the sign fix covers both.
    dispatchDesc.jitterOffset.y = -params.jitterY;
	    // FSR4/FSR3.1 SDK contract (2026-08-08 fix): the MV-gen outputs ALREADY
	    // pixel-space MVs (mv = (prevUv - uv) * renderSize), and the FSR4 docs are
	    // explicit that motionVectorScale is a MULTIPLIER for converting OTHER
	    // spaces into that pixel range — passing 1/renderSize DIVIDED the pixel
	    // MVs down to ~0, so the SDK treated everything as static (standing-still
	    // smear + motion shake). Identity scale = the docs' expected input.
	    // RECOVERED 2026-08-18 (the post-fix native source was lost; this
	    // replicates the documented fix).
	    dispatchDesc.motionVectorScale.x = 1.0f;
	    dispatchDesc.motionVectorScale.y = 1.0f;
    dispatchDesc.renderSize.width   = static_cast<int32_t>(renderW);
    dispatchDesc.renderSize.height  = static_cast<int32_t>(renderH);
    dispatchDesc.upscaleSize.width  = static_cast<int32_t>(displayW);
    dispatchDesc.upscaleSize.height = static_cast<int32_t>(displayH);
    // SDK internal sharpening wired to config.sharpness (2026-08-13). The
    // 0.7 default is now real FSR4 sharpening, not just GLSL RCAS on FSR2/3.
    dispatchDesc.enableSharpening = g_fsr4Sharpness > 0.0f;
    dispatchDesc.sharpness        = g_fsr4Sharpness;
    dispatchDesc.frameTimeDelta   = params.deltaTimeMs;
    dispatchDesc.preExposure      = 1.0f;
    dispatchDesc.reset            = params.reset;
    dispatchDesc.cameraNear       = params.cameraNear;
    dispatchDesc.cameraFar        = params.cameraFar;
    dispatchDesc.cameraFovAngleVertical = params.fovYRad;
    dispatchDesc.viewSpaceToMetersFactor = 1.0f;
    dispatchDesc.flags = 0;

    // ------------------------------------------------------------
    // Pipeline test bypass: skip the FSR4 SDK and use a bilinear
    // upscale compute shader instead. This produces a valid display-
    // sized output from the render-sized input, avoiding the FSR4 SDK
    // crash that occurs after FG context creation on some GPUs.
    //
    // NOTE: CopyResource cannot be used because the source (render
    // size) and destination (display size) have different dimensions.
    // A compute shader with bilinear interpolation is the simplest
    // D3D12 approach that handles the size mismatch correctly.
    // ------------------------------------------------------------
    if (g_bypassFsr4) {
        // Lazy-create bypass compute shader resources
        if (!g_bypassRootSignature) {
            if (!createBypassResources(dev.device())) {
                diagLog("[Fsr4Context] bypass: FAILED to create resources — falling back");
                return false;
            }
        }

        ID3D12Resource* colorRes  = params.color;
        ID3D12Resource* outputRes = params.output;

        // RATE-LIMITED (2026-08-30): the bypass is now the steady-state path
        // on AMD iGPU + FG sessions (pre-armed), so this fires every frame —
        // log the first two then every 256th to avoid flooding fsr4_diag.log.
        static int bypassDispatchCounter = 0;
        if (bypassDispatchCounter < 2 || (bypassDispatchCounter++ & 255) == 0) {
            diagLog("[Fsr4Context] dispatch: PIPELINE TEST — bilinear upscale "
                    "color=%p (%ux%u) → output=%p (%ux%u)",
                (void*)colorRes, renderW, renderH,
                (void*)outputRes, displayW, displayH);
        }

        // Transition source: COMMON → NON_PIXEL_SHADER_RESOURCE (for SRV)
        D3D12_RESOURCE_BARRIER srvBarrier{};
        srvBarrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        srvBarrier.Transition.pResource = colorRes;
        srvBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
        srvBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
        srvBarrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;

        // Transition dest: COMMON → UNORDERED_ACCESS (for UAV)
        D3D12_RESOURCE_BARRIER uavBarrier{};
        uavBarrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        uavBarrier.Transition.pResource = outputRes;
        uavBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COMMON;
        uavBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_UNORDERED_ACCESS;
        uavBarrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;

        D3D12_RESOURCE_BARRIER barriers[2] = { srvBarrier, uavBarrier };
        dev.commandList()->ResourceBarrier(2, barriers);

        // Update SRV/UAV descriptors if resources changed
        updateBypassDescriptors(dev.device(), colorRes, outputRes,
                                renderW, renderH, displayW, displayH);

	        // Bind resources and dispatch
	        ID3D12DescriptorHeap* heaps[] = { g_bypassDescHeap };
	        dev.commandList()->SetDescriptorHeaps(1, heaps);
	        dev.commandList()->SetComputeRootSignature(g_bypassRootSignature);
	        dev.commandList()->SetPipelineState(g_bypassPSO);

	        D3D12_GPU_DESCRIPTOR_HANDLE srvGpu = g_bypassDescHeap->GetGPUDescriptorHandleForHeapStart();
	        D3D12_GPU_DESCRIPTOR_HANDLE uavGpu = { srvGpu.ptr + 1 * static_cast<SIZE_T>(g_bypassDescSize) };
	        dev.commandList()->SetComputeRootDescriptorTable(0, srvGpu);  // SRV (t0)
	        dev.commandList()->SetComputeRootDescriptorTable(1, uavGpu);  // UAV (u0)

        uint32_t groupsX = (displayW + 15) / 16;
        uint32_t groupsY = (displayH + 15) / 16;
        dev.commandList()->Dispatch(groupsX, groupsY, 1);

        // Restore both to COMMON
        srvBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE;
        srvBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
        uavBarrier.Transition.StateBefore = D3D12_RESOURCE_STATE_UNORDERED_ACCESS;
        uavBarrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COMMON;
        barriers[0] = srvBarrier;
        barriers[1] = uavBarrier;
        dev.commandList()->ResourceBarrier(2, barriers);

        dev.commandList()->Close();
        ID3D12CommandList* lists[] = { dev.commandList() };
        dev.commandQueue()->ExecuteCommandLists(1, lists);
        lastFenceValue_ = dev.signalFence();
        dev.advanceAllocator(lastFenceValue_);
        if (bypassDispatchCounter > 0 && (bypassDispatchCounter & 255) == 0) {
            diagLog("[Fsr4Context] dispatch: PIPELINE TEST — submitted (fence=%llu, %ux%u → %ux%u)",
                (unsigned long long)lastFenceValue_, renderW, renderH, displayW, displayH);
        }
        return true;
    }

    // ------------------------------------------------------------
    // Wrap dispatch in SEH. If FSR4 internally faults (e.g. from
    // a future SDK regression or a bad descriptor), we want to
    // fail this frame and log it, not crash the JVM.
    // ------------------------------------------------------------
    ffxReturnCode_t rc = FFX_API_RETURN_ERROR;
    __try {
        rc = g_ffxDispatch(&ffxContext_, &dispatchDesc.header);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fsr4Context] dispatch: CRASH in g_ffxDispatch (code=0x%08lx) — "
                "GPU may need a TDR reset", GetExceptionCode());
        // ARM THE BYPASS (2026-08-30, "make FSR3 FG DX12 work with shaders").
        // 0xe06d7363 is an MSVC C++ exception THROWN by the SDK — and on this
        // GPU (Radeon 860M) it does so deterministically once an FG
        // ffxCreateContext exists on the device, even after a full FSR4
        // context re-create (diag log: crash → destroy → pool recreate →
        // init OK → crash, repeating). The SDK upscale cannot coexist with
        // the FG context here, but the FG pipeline itself only needs a valid
        // display-sized upscale in pool 4 to interpolate from. The bilinear
        // bypass above (written for exactly this, but previously never
        // enabled — g_bypassFsr4 had no writer) provides that, so the NEXT
        // dispatch succeeds and FG stays alive instead of the whole mode
        // collapsing to the FSR1 spatial fallback with FG dead.
        // Cleared in destroy() so a deliberate settings change (markDirty)
        // re-tries the real SDK.
        if (!g_bypassFsr4) {
            diagLog("[Fsr4Context] arming bilinear bypass — FSR4 SDK upscale is "
                    "unusable with the FG context on this GPU; FG stays active");
        }
        g_bypassFsr4 = true;
        // CRITICAL FIX (2026-08-30, "allocator Reset FAILED storm → heap
        // corruption 0xC0000374" crash): NEVER submit the partially-recorded
        // command list. g_ffxDispatch threw MID-RECORD, so the list holds
        // partial/corrupted GPU commands. The old code Close()+Execute'd it,
        // which made the AMD driver TDR the GPU → DXGI_ERROR_DEVICE_REMOVED
        // (hr=0x887a0005 in the diag log) → every subsequent D3D12 call
        // failed (the allocator Reset hr=0x80004005 E_FAIL storm) → the
        // process died with STATUS_HEAP_CORRUPTION (0xC0000374). The FG
        // prepare path learned this lesson first ("Do NOT close/execute the
        // partially-recorded command list… Submitting them causes a GPU
        // timeout (TDR) which removes the entire D3D12 device"). The FSR4
        // round-robin allocator pair is owned by D3D12Device, so discard +
        // recreate the command resources wholesale: no GPU submit and no
        // fence signal (nothing was submitted — advancing without executing
        // would store a never-signaled fence value the next pre-dispatch
        // wait would block on for 2s). The bilinear bypass (armed above)
        // takes over next frame; if the recreate itself fails the device is
        // wedged and Java's dispatch-failure escalation hard-disables to FSR1.
        if (!dev.recreateCommandResources()) {
            diagLog("[Fsr4Context] dispatch: post-crash command resource "
                    "recreate FAILED — Java escalation will hard-disable");
        }
        return false;
    }
	    if (rc != FFX_API_RETURN_OK) {
	        diagLog("[Fsr4Context] dispatch: g_ffxDispatch FAILED rc=%u", rc);
	        dev.commandList()->Close();
	        // Drain the (already-recorded) barriers so the next dispatch
	        // can reset the allocator cleanly.
	        ID3D12CommandList* lists[] = { dev.commandList() };
	        dev.commandQueue()->ExecuteCommandLists(1, lists);
	        lastFenceValue_ = dev.signalFence();
	        dev.advanceAllocator(lastFenceValue_);
	        dev.waitForFenceValue(lastFenceValue_, 1000);
        return false;
    }

    // ------------------------------------------------------------
    // The FSR4 SDK's DX12 backend handles ALL state transitions
    // internally. After dispatch, resources are in the state the
    // SDK left them — typically COMMON, or the state we passed
    // in FfxApiResource.state. We DO NOT add manual back-barriers
    // here because we do not know the post-dispatch state, and
    // barrier StateBefore mismatch on shared resources causes
    // AMD GPU hangs.
    //
    // On the next dispatch, the SDK will see state=COMMON and
    // transition from whatever the actual state is (if it's not
    // COMMON, the SDK handles the mismatch internally via its
    // own deferred barrier logic).
    // ------------------------------------------------------------

    dev.commandList()->Close();
    ID3D12CommandList* lists[] = { dev.commandList() };
    dev.commandQueue()->ExecuteCommandLists(1, lists);

	    lastFenceValue_ = dev.signalFence();

	    // Advance the round-robin allocator: stores the signaled fence
	    // value for the current allocator and switches to the next one.
	    // On the next frame, the pre-dispatch wait will wait for THIS
	    // allocator's fence value before resetting it (2 frames later),
	    // enabling CPU-GPU overlap.
	    dev.advanceAllocator(lastFenceValue_);

        // NOTE: Post-dispatch fence wait REMOVED to avoid redundant sync.
	    // The caller (Java) calls waitIdle()/finish() after dispatch, which
	    // waits for lastFenceValue_. The pre-dispatch wait (above) still
	    // guarantees allocator safety for the next frame. This reduces the
	    // pipeline bubble by one blocking sync per frame.
	    return true;
	}

	// ----------------------------------------------------------------
	void Fsr4Context::finish() {
    if (!ffxContext_ || lastFenceValue_ == 0) return;
    D3D12Device::get().waitForFenceValue(lastFenceValue_, 5000);
}

// ----------------------------------------------------------------
void Fsr4Context::destroy() {
    if (!ffxContext_) return;
    finish();
    if (g_ffxDestroyContext) {
        __try {
            g_ffxDestroyContext(&ffxContext_, nullptr);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            diagLog("[Fsr4Context] CRASH in g_ffxDestroyContext (code=0x%08lx)",
                GetExceptionCode());
        }
    }
    ffxContext_ = nullptr;
    lastFenceValue_ = 0;
    cachedRenderW_  = 0;
    cachedRenderH_  = 0;
    cachedDisplayW_ = 0;
    cachedDisplayH_ = 0;
    // Clear the bilinear bypass so a DELIBERATE rebuild (markDirty /
    // applyAutoScale — the user retrying) re-attempts the real FSR4 SDK
    // upscale. A session that merely crash-loops re-arms it on the first
    // dispatch crash, so the escalation stays self-limiting.
    g_bypassFsr4 = false;
    // Clean up pipeline test bypass resources
    destroyBypassResources();
}

} // namespace fsr
