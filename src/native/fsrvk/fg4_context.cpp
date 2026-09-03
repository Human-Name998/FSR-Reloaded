#include "fg4_context.h"
#include "fsr4_context.h"
#include "d3d12_device.h"
#include "d3d12_shared_resources.h"
#include "diag.h"
#include <cstdio>
#include <cstring>
#include <cwchar>

#ifdef _WIN32
#include <windows.h>
#include <excpt.h>
#endif

namespace fsr {

// ----------------------------------------------------------------
// Diagnostics
// ----------------------------------------------------------------
namespace {
    void diagLog(const char* fmt, ...) {
        if (!fsr::g_diagEnabled) return;
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

    // Millisecond timestamp
    static long diagTimeMs() {
        static LARGE_INTEGER freq = { 0 };
        if (freq.QuadPart == 0) QueryPerformanceFrequency(&freq);
        LARGE_INTEGER now;
        QueryPerformanceCounter(&now);
        return (now.QuadPart * 1000LL) / freq.QuadPart;
    }
}

// ----------------------------------------------------------------
// Dynamic loading of FG SDK functions from the FG provider DLL.
// ----------------------------------------------------------------
namespace {

    FfnCreateContext   g_fgCreateContext   = nullptr;
    FfnDispatch        g_fgDispatch        = nullptr;
    FfnConfigure       g_fgConfigure       = nullptr;
    FfnDestroyContext  g_fgDestroyContext  = nullptr;

    bool loadSdkFunctions(const std::wstring& dllDir) {
        if (g_fgCreateContext) return true;
        if (dllDir.empty()) {
            diagLog("[Fg4Context] loadSdkFunctions: no DLL directory configured");
            return false;
        }

        // Helper: construct full path to a DLL in dllDir and try to load it
        auto tryLoadDll = [&](const wchar_t* dllName) -> HMODULE {
            wchar_t fullPath[MAX_PATH];
            wcscpy_s(fullPath, MAX_PATH, dllDir.c_str());
            // Ensure trailing backslash
            size_t len = wcslen(fullPath);
            if (len > 0 && fullPath[len - 1] != L'\\') {
                wcscat_s(fullPath, MAX_PATH, L"\\");
            }
            wcscat_s(fullPath, MAX_PATH, dllName);
            diagLog("[Fg4Context] LoadLibraryExW(%ls)", fullPath);
            return LoadLibraryExW(fullPath, nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
        };

        // NOTE: Skip the loader DLL (amd_fidelityfx_loader_dx12.dll) entirely.
        // The loader returns FFX_API_RETURN_ERROR_UNKNOWN_DESCTYPE (rc=2) for
        // both upscale and FG descriptors — it doesn't know how to route them
        // to the provider with our FFX_API_VERSION. The FSR4 path already
        // confirmed this (it loads the provider directly too).
        //
        // Load the FG provider DLL directly.
        HMODULE fgMod = tryLoadDll(L"amd_fidelityfx_framegeneration_dx12.dll");
        diagLog("[Fg4Context] LoadLibraryExW(FG DLL): %p", (void*)fgMod);
        if (!fgMod) {
            diagLog("[Fg4Context] FAIL: amd_fidelityfx_framegeneration_dx12.dll not loaded");
            return false;
        }

        g_fgCreateContext = reinterpret_cast<FfnCreateContext>(
            GetProcAddress(fgMod, "ffxCreateContext"));
        g_fgDispatch = reinterpret_cast<FfnDispatch>(
            GetProcAddress(fgMod, "ffxDispatch"));
        g_fgConfigure = reinterpret_cast<FfnConfigure>(
            GetProcAddress(fgMod, "ffxConfigure"));
        g_fgDestroyContext = reinterpret_cast<FfnDestroyContext>(
            GetProcAddress(fgMod, "ffxDestroyContext"));

        if (!g_fgCreateContext) diagLog("[Fg4Context] GetProcAddress(ffxCreateContext) FAILED");
        if (!g_fgDispatch)      diagLog("[Fg4Context] GetProcAddress(ffxDispatch) FAILED");
        if (!g_fgConfigure)     diagLog("[Fg4Context] GetProcAddress(ffxConfigure) FAILED");
        if (!g_fgDestroyContext) diagLog("[Fg4Context] GetProcAddress(ffxDestroyContext) FAILED");

        bool ok = g_fgCreateContext && g_fgDispatch && g_fgConfigure && g_fgDestroyContext;
        if (ok) {
            diagLog("[Fg4Context] using FG provider directly (handle=%p)", (void*)fgMod);
        } else {
            diagLog("[Fg4Context] FAIL: one or more FG SDK exports missing");
        }
        return ok;
    }

    // Build an FfxApiResource from a pool resource.
    //
    // All resources are passed with state = FFX_API_RESOURCE_STATE_COMMON.
    // The FG SDK issues its own D3D12 barriers based on this field.
    // DO NOT add manual D3D12 barriers before/after SDK dispatch.
    FfxApiResource makeResource(ID3D12Resource* res,
                                uint32_t w, uint32_t h,
                                uint32_t fmt, uint32_t usage,
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
        r.state                = FFX_API_RESOURCE_STATE_COMMON;
        (void)name;
        return r;
    }
}

// ----------------------------------------------------------------
// Dedicated D3D12 command recording resources
// ----------------------------------------------------------------
bool Fg4Context::createDedicatedCommandResources() {
    auto& dev = D3D12Device::get();
    ID3D12Device* d3dDev = dev.device();
    if (!d3dDev) return false;

    // Command allocator
    HRESULT hr = d3dDev->CreateCommandAllocator(
        D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&fgCmdAlloc_));
    if (FAILED(hr)) {
        diagLog("[Fg4Context] CreateCommandAllocator FAILED hr=0x%08lx", hr);
        return false;
    }

    // Command list (created open, close immediately)
    hr = d3dDev->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
        fgCmdAlloc_.Get(), nullptr, IID_PPV_ARGS(&fgCmdList_));
    if (FAILED(hr)) {
        diagLog("[Fg4Context] CreateCommandList FAILED hr=0x%08lx", hr);
        fgCmdAlloc_.Reset();
        return false;
    }
    fgCmdList_->Close();

    // Fence
    hr = d3dDev->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fgFence_));
    if (FAILED(hr)) {
        diagLog("[Fg4Context] CreateFence FAILED hr=0x%08lx", hr);
        fgCmdList_.Reset();
        fgCmdAlloc_.Reset();
        return false;
    }
    fgFenceEvent_ = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (!fgFenceEvent_) {
        diagLog("[Fg4Context] CreateEvent FAILED");
        fgFence_.Reset();
        fgCmdList_.Reset();
        fgCmdAlloc_.Reset();
        return false;
    }
    fgFenceValue_ = 0;

    diagLog("[Fg4Context] dedicated command resources created: alloc=%p list=%p fence=%p",
        (void*)fgCmdAlloc_.Get(), (void*)fgCmdList_.Get(), (void*)fgFence_.Get());
    return true;
}

void Fg4Context::destroyDedicatedCommandResources() {
    if (fgFenceEvent_) { CloseHandle(fgFenceEvent_); fgFenceEvent_ = nullptr; }
    // Use Detach() to avoid AMD driver teardown crash (same pattern as D3D12Device::dispose)
    fgCmdList_.Detach();
    fgCmdAlloc_.Detach();
    fgFence_.Detach();
    fgFenceValue_ = 0;
}

uint64_t Fg4Context::signalFgFence() {
    if (!fgFence_) return 0;
    ++fgFenceValue_;
    D3D12Device::get().commandQueue()->Signal(fgFence_.Get(), fgFenceValue_);
    return fgFenceValue_;
}

bool Fg4Context::waitForFgFence(uint64_t value, DWORD timeoutMs) {
    if (!fgFence_) return false;
    if (fgFence_->GetCompletedValue() >= value) return true;
    HRESULT hr = fgFence_->SetEventOnCompletion(value, fgFenceEvent_);
    if (FAILED(hr)) return false;
    return WaitForSingleObject(fgFenceEvent_, timeoutMs) == WAIT_OBJECT_0;
}

// ----------------------------------------------------------------
bool Fg4Context::initialize(uint32_t renderW, uint32_t renderH,
                             uint32_t displayW, uint32_t displayH,
                             D3D12SharedResources* colorPool, int colorIndex,
                             int depthIndex, int mvIndex, int outputIndex,
                             int hudlessColorIndex,
                             const wchar_t* fgDllDir) {
    if (ffxContext_) return true;

    if (renderW == 0 || renderH == 0 || displayW == 0 || displayH == 0) {
        diagLog("[Fg4Context] FAIL: zero dimensions (render=%ux%u display=%ux%u)",
            renderW, renderH, displayW, displayH);
        return false;
    }

    // Store DLL directory for loadSdkFunctions
    if (fgDllDir) {
        fgDllDir_ = fgDllDir;
        diagLog("[Fg4Context] using FG DLL directory: %ls", fgDllDir);
    } else {
        fgDllDir_.clear();
        diagLog("[Fg4Context] WARNING: no FG DLL directory provided");
    }

    if (!loadSdkFunctions(fgDllDir_)) return false;

    auto& dev = D3D12Device::get();
    if (dev.ensureInitialized() != D3D12DeviceState::READY) return false;

    // Store pool references
    colorPool_ = colorPool;
    colorIndex_ = colorIndex;
    depthIndex_ = depthIndex;
    mvIndex_ = mvIndex;
    outputIndex_ = outputIndex;
    hudlessColorIndex_ = hudlessColorIndex;

    // Create dedicated command recording resources BEFORE creating the SDK
    // context. The SDK may cache the command list pointer during create/configure.
    if (!createDedicatedCommandResources()) {
        diagLog("[Fg4Context] FAIL: could not create dedicated command resources");
        return false;
    }

    // Query highest D3D12 interface
    ID3D12Device5* device5 = nullptr;
    ID3D12Device8* device8 = nullptr;
    ID3D12Device* fsrDevice = dev.device();

    if (SUCCEEDED(dev.device()->QueryInterface(IID_PPV_ARGS(&device8)))) {
        diagLog("[Fg4Context] using ID3D12Device8");
        fsrDevice = device8;
    } else if (SUCCEEDED(dev.device()->QueryInterface(IID_PPV_ARGS(&device5)))) {
        diagLog("[Fg4Context] using ID3D12Device5");
        fsrDevice = device5;
    } else {
        diagLog("[Fg4Context] using base ID3D12Device");
    }

    // Build descriptor chain: frame generation -> backend(DX12) -> version
    ffxCreateContextDescFrameGeneration fgDesc{};
    fgDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    fgDesc.flags = 0;
    fgDesc.displaySize.width   = static_cast<int32_t>(displayW);
    fgDesc.displaySize.height  = static_cast<int32_t>(displayH);
    fgDesc.maxRenderSize.width  = static_cast<int32_t>(renderW);
    fgDesc.maxRenderSize.height = static_cast<int32_t>(renderH);
    fgDesc.backBufferFormat = FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM;

    ffxCreateBackendDX12Desc backendDesc{};
    backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_DX12;
    backendDesc.device = fsrDevice;

    ffxCreateContextDescFrameGenerationVersion versionDesc{};
    versionDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION_VERSION;
    versionDesc.version = FFX_FRAMEGENERATION_VERSION;

    fgDesc.header.pNext = reinterpret_cast<ffxApiHeader*>(&backendDesc);
    backendDesc.header.pNext = reinterpret_cast<ffxApiHeader*>(&versionDesc);

    ffxReturnCode_t rc = FFX_API_RETURN_ERROR;
    __try {
        rc = g_fgCreateContext(&ffxContext_,
            reinterpret_cast<ffxCreateContextDescHeader*>(&fgDesc), nullptr);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fg4Context] CRASH in ffxCreateContext (code=0x%08lx)", GetExceptionCode());
        ffxContext_ = nullptr;
    }

    if (device8) device8->Release();
    if (device5) device5->Release();

    if (rc != FFX_API_RETURN_OK || !ffxContext_) {
        diagLog("[Fg4Context] ffxCreateContext FAILED: rc=%u (render=%ux%u display=%ux%u)",
            rc, renderW, renderH, displayW, displayH);
        ffxContext_ = nullptr;
        destroyDedicatedCommandResources();
        return false;
    }

    // Configure: no-swapchain mode
    ffxConfigureDescFrameGeneration configDesc{};
    configDesc.header.type = FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION;
    configDesc.swapChain = nullptr;
    configDesc.presentCallback = nullptr;
    configDesc.presentCallbackUserContext = nullptr;
    configDesc.frameGenerationCallback = nullptr;
    configDesc.frameGenerationCallbackUserContext = nullptr;
    configDesc.frameGenerationEnabled = true;
    configDesc.allowAsyncWorkloads = false;
    configDesc.flags = FFX_FRAMEGENERATION_FLAG_NO_SWAPCHAIN_CONTEXT_NOTIFY;
    configDesc.onlyPresentGenerated = false;
    configDesc.frameID = 0;
    configDesc.generationRect.x      = 0;
    configDesc.generationRect.y      = 0;
    configDesc.generationRect.width  = static_cast<int32_t>(displayW);
    configDesc.generationRect.height = static_cast<int32_t>(displayH);
    // HUDLessColor: the real frame WITHOUT the HUD (FSR4 upscaled output, pool 4).
    // The SDK diffs presentColor (real + GUI, pool 6) against this to extract the
    // HUD and re-composites it onto every generated frame — so BOTH presents of
    // the double-present carry the GUI instead of only the FG one.
    if (hudlessColorIndex_ >= 0 && colorPool
            && colorPool->resources[hudlessColorIndex_]) {
        configDesc.HUDLessColor = makeResource(
            colorPool->resources[hudlessColorIndex_], displayW, displayH,
            FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, FFX_API_RESOURCE_USAGE_READ_ONLY,
            L"fg_hudless");
    } else {
        configDesc.HUDLessColor = {};
        configDesc.HUDLessColor.state = FFX_API_RESOURCE_STATE_COMMON;
    }

    __try {
        rc = g_fgConfigure(&ffxContext_,
            reinterpret_cast<ffxConfigureDescHeader*>(&configDesc));
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        diagLog("[Fg4Context] CRASH in ffxConfigure (code=0x%08lx)", GetExceptionCode());
    }

    if (rc != FFX_API_RETURN_OK) {
        diagLog("[Fg4Context] ffxConfigure returned rc=%u — FG may not work as expected", rc);
    }
    if (configDesc.HUDLessColor.resource) {
        diagLog("[Fg4Context] HUDLessColor set: pool[%d]=%p (HUD composited onto generated frames)",
            hudlessColorIndex_, configDesc.HUDLessColor.resource);
    } else {
        diagLog("[Fg4Context] HUDLessColor NOT set — generated frames will not carry the GUI");
    }

    // Cache dimensions
    cachedRenderW_  = renderW;
    cachedRenderH_  = renderH;
    cachedDisplayW_ = displayW;
    cachedDisplayH_ = displayH;
    lastFenceValue_ = 0;
    consecutiveCrashes_ = 0;
    permanentlyDisabled_ = false;
    forceReset_ = false;

    diagLog("[Fg4Context] init OK (%ux%u->%ux%u, pool=%p colorIdx=%d depthIdx=%d mvIdx=%d outIdx=%d hudlessIdx=%d, genRect=%dx%d)",
        renderW, renderH, displayW, displayH,
        (void*)colorPool, colorIndex, depthIndex, mvIndex, outputIndex,
        hudlessColorIndex, displayW, displayH);

    // NOTE: The original code forced FSR4 into a CopyResource "bypass" mode
    // here because the FSR4 SDK crashed on some AMD drivers once the FG SDK
    // context existed. That bypass was invalid for real use (CopyResource
    // requires identical dimensions, but color is render-size and output is
    // display-size) and has been removed entirely. FSR4's real temporal
    // upscale is required to produce the display-size presentColor that FG
    // interpolates from. If a driver-specific crash appears, handle it in
    // FSRProcessor (e.g. with a scale-aware copy fallback).

    // WARMUP REMOVED: The FG SDK allocates internal interpolation buffers
    // during ffxCreateContext, not during dispatch. reset=true on the first
    // prepare+generate was causing access violations because it directed the
    // SDK to skip buffer allocation. The fix is to let the first real frame
    // run prepare(reset=true) + generate(reset=false), which tells prepare to
    // reset camera state while generate uses the already-allocated buffers
    // (just copies presentColor→output on the very first frame since there's
    // no history yet). Subsequent frames accumulate history and interpolate.
    //
    // See generate(): genDesc.reset is always false to protect the buffer
    // lifecycle.

    return true;
}

// ----------------------------------------------------------------
bool Fg4Context::reinitialize() {
    diagLog("[Fg4Context] reinitialize: destroying crashed context...");
    if (ffxContext_ && g_fgDestroyContext) {
        __try {
            g_fgDestroyContext(&ffxContext_, nullptr);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            diagLog("[Fg4Context] reinitialize: CRASH in destroy (code=0x%08lx)", GetExceptionCode());
        }
    }
    ffxContext_ = nullptr;
    lastFenceValue_ = 0;

    // Destroy and recreate dedicated command resources
    destroyDedicatedCommandResources();

    bool ok = initialize(cachedRenderW_, cachedRenderH_,
                         cachedDisplayW_, cachedDisplayH_,
                         const_cast<D3D12SharedResources*>(colorPool_),
                         colorIndex_, depthIndex_, mvIndex_, outputIndex_,
                         hudlessColorIndex_,
                         fgDllDir_.c_str());
    if (ok) {
        forceReset_ = true;
        diagLog("[Fg4Context] reinitialize: SUCCESS (forceReset=true)");
    } else {
        diagLog("[Fg4Context] reinitialize: FAILED");
    }
    return ok;
}

// ----------------------------------------------------------------
// SEH-safe dispatch wrapper: no C++ objects requiring unwinding.
// Returns 0 for OK, 1 for error, 2 for crash.
static int dispatchPrepareSeh(ffxContext* ctx, ffxDispatchDescFrameGenerationPrepareV2* desc) {
    __try {
        ffxReturnCode_t rc = g_fgDispatch(ctx,
            reinterpret_cast<ffxDispatchDescHeader*>(desc));
        return (rc == FFX_API_RETURN_OK) ? 0 : 1;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        DWORD code = GetExceptionCode();
        diagLog("[Fg4Context] dispatchPrepareSeh: CRASH (code=0x%08lx)", code);
        return 2;
    }
}

// ----------------------------------------------------------------
bool Fg4Context::prepare(const Fg4PrepareParams& params) {
    if (permanentlyDisabled_) return false;
    if (!ffxContext_) {
        if (!reinitialize()) return false;
    }
    if (!colorPool_) return false;
    if (!fgCmdAlloc_ || !fgCmdList_) return false;

    auto& dev = D3D12Device::get();

    // Wait for the previous FG command list to complete before resetting
    if (lastFenceValue_ > 0) {
        if (!waitForFgFence(lastFenceValue_, 2000)) {
            diagLog("[Fg4Context] prepare: fence wait timed out (val=%llu)",
                (unsigned long long)lastFenceValue_);
            return false;
        }
    }

    HRESULT hr = fgCmdAlloc_->Reset();
    if (FAILED(hr)) {
        diagLog("[Fg4Context] prepare: allocator Reset FAILED hr=0x%08lx", hr);
        return false;
    }
    hr = fgCmdList_->Reset(fgCmdAlloc_.Get(), nullptr);
    if (FAILED(hr)) {
        diagLog("[Fg4Context] prepare: command list Reset FAILED hr=0x%08lx", hr);
        return false;
    }

    uint32_t renderW  = cachedRenderW_;
    uint32_t renderH  = cachedRenderH_;

    // Resolve pool resources
    ID3D12Resource* depthRes = (depthIndex_ >= 0) ? colorPool_->resources[depthIndex_] : nullptr;
    ID3D12Resource* mvRes    = (mvIndex_ >= 0)    ? colorPool_->resources[mvIndex_]    : nullptr;

    FfxApiResource depthResInfo = depthRes ? makeResource(depthRes, renderW, renderH,
        FFX_API_SURFACE_FORMAT_R32_FLOAT, FFX_API_RESOURCE_USAGE_READ_ONLY,
        L"fg_depth") : FfxApiResource{};

    FfxApiResource mvResInfo = mvRes ? makeResource(mvRes, renderW, renderH,
        FFX_API_SURFACE_FORMAT_R16G16_FLOAT, FFX_API_RESOURCE_USAGE_READ_ONLY,
        L"fg_mv") : FfxApiResource{};

    float mvScaleX = 1.0f / static_cast<float>(renderW);
    float mvScaleY = 1.0f / static_cast<float>(renderH);

    bool doReset = params.reset || forceReset_;
    forceReset_ = false;

    ffxDispatchDescFrameGenerationPrepareV2 prepareDesc{};
    prepareDesc.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_V2;
    prepareDesc.frameID = params.frameID;
    currentFrameID_ = params.frameID;
    reset_ = doReset;
    // DEPTH CONVENTION (2026-08-30, same fix as fsr4_context.cpp): FG's prepare
    // consumes the SAME shared pool depth as the FSR4 upscale — non-reversed
    // (0=near, 1=far), written by DEPTH_COPY_CS / the VK MV-gen. The
    // DEPTH_INVERTED flag (added with the DX12 FG port) tells the SDK the
    // opposite, so its disocclusion reads the depth backwards. Cleared to
    // match the FSR4 upscale contract.
    prepareDesc.flags = 0;
    prepareDesc.commandList = fgCmdList_.Get();
    prepareDesc.renderSize.width   = static_cast<int32_t>(renderW);
    prepareDesc.renderSize.height  = static_cast<int32_t>(renderH);
    prepareDesc.jitterOffset.x = params.jitterX;
    // Y-SIGN CONVENTION (2026-08-08 fix, restored 2026-08-30): same DirectX
    // +Y-down jitter convention as the FSR4 upscale dispatch — negate the
    // OpenGL +Y-up render jitter so FG's internal accumulation aligns with the
    // frames that were actually rendered.
    prepareDesc.jitterOffset.y = -params.jitterY;
    prepareDesc.motionVectorScale.x = mvScaleX;
    prepareDesc.motionVectorScale.y = mvScaleY;
    prepareDesc.frameTimeDelta = params.frameTimeDeltaMs;
    prepareDesc.reset = doReset;
    prepareDesc.cameraNear = params.cameraNear;
    prepareDesc.cameraFar = params.cameraFar;
    prepareDesc.cameraFovAngleVertical = params.cameraFovAngleVertical;
    prepareDesc.viewSpaceToMetersFactor = params.viewSpaceToMetersFactor;
    prepareDesc.depth = depthResInfo;
    prepareDesc.motionVectors = mvResInfo;
    memcpy(prepareDesc.cameraPosition, params.cameraPosition, sizeof(params.cameraPosition));
    memcpy(prepareDesc.cameraUp,       params.cameraUp,       sizeof(params.cameraUp));
    memcpy(prepareDesc.cameraRight,    params.cameraRight,    sizeof(params.cameraRight));
    memcpy(prepareDesc.cameraForward,  params.cameraForward,  sizeof(params.cameraForward));

    int dispatchResult = dispatchPrepareSeh(&ffxContext_, &prepareDesc);

    if (dispatchResult == 2) {
        // Crash — clean up
        consecutiveCrashes_++;
        diagLog("[Fg4Context] prepare: CRASH (consecutive=%d/%d)",
            consecutiveCrashes_, MAX_CONSECUTIVE_CRASHES);

        // CRITICAL: Do NOT close/execute the partially-recorded command list.
        // The FG SDK may have recorded partial or corrupted GPU commands before
        // the crash. Submitting them to the GPU causes a GPU timeout (TDR) which
        // removes the entire D3D12 device (DXGI_ERROR_DEVICE_RESET 0x887a0020).
        // Instead, discard the corrupted allocator+list entirely.
        // Use Detach() not Reset() to avoid AMD Vulkan-on-12 teardown crash.
        // They will be recreated by reinitialize() → createDedicatedCommandResources().
        fgCmdAlloc_.Detach();
        fgCmdList_.Detach();

        if (ffxContext_ && g_fgDestroyContext) {
            __try { g_fgDestroyContext(&ffxContext_, nullptr); }
            __except (EXCEPTION_EXECUTE_HANDLER) {}
        }
        ffxContext_ = nullptr;

        if (consecutiveCrashes_ >= MAX_CONSECUTIVE_CRASHES) {
            permanentlyDisabled_ = true;
            diagLog("[Fg4Context] PERMANENTLY DISABLED after %d consecutive crashes", consecutiveCrashes_);
        }
        return false;
    }

    if (dispatchResult == 1) {
        diagLog("[Fg4Context] prepare: g_fgDispatch FAILED rc=error");
        fgCmdList_->Close();
        ID3D12CommandList* lists[] = { fgCmdList_.Get() };
        dev.commandQueue()->ExecuteCommandLists(1, lists);
        lastFenceValue_ = signalFgFence();
        return false;
    }

    // Success — reset crash counter
    consecutiveCrashes_ = 0;

    fgCmdList_->Close();
    ID3D12CommandList* lists[] = { fgCmdList_.Get() };
    dev.commandQueue()->ExecuteCommandLists(1, lists);
    lastFenceValue_ = signalFgFence();

    return true;
}

// ----------------------------------------------------------------
// SEH-safe generate dispatch wrapper.
// Returns: 0 = OK, 1 = error, 2 = crash. Populates *out_rc on OK/error.
static int dispatchGenerateSeh(ffxContext* ctx, ffxDispatchDescFrameGeneration* desc) {
    __try {
        ffxReturnCode_t rc = g_fgDispatch(ctx,
            reinterpret_cast<ffxDispatchDescHeader*>(desc));
        return (rc == FFX_API_RETURN_OK) ? 0 : 1;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        DWORD code = GetExceptionCode();
        diagLog("[Fg4Context] dispatchGenerateSeh: CRASH (code=0x%08lx)", code);
        return 2;
    }
}

// ----------------------------------------------------------------
bool Fg4Context::generate(uint32_t numGeneratedFrames) {
    if (permanentlyDisabled_) return false;
    if (!ffxContext_) return false;
    if (!colorPool_) return false;
    if (!fgCmdAlloc_ || !fgCmdList_) return false;

    auto& dev = D3D12Device::get();

    // Wait for previous prepare command list to complete
    if (lastFenceValue_ > 0) {
        if (!waitForFgFence(lastFenceValue_, 2000)) {
            diagLog("[Fg4Context] generate: fence wait timed out (val=%llu)",
                (unsigned long long)lastFenceValue_);
            return false;
        }
    }

    HRESULT hr = fgCmdAlloc_->Reset();
    if (FAILED(hr)) {
        diagLog("[Fg4Context] generate: allocator Reset FAILED hr=0x%08lx", hr);
        return false;
    }
    hr = fgCmdList_->Reset(fgCmdAlloc_.Get(), nullptr);
    if (FAILED(hr)) {
        diagLog("[Fg4Context] generate: command list Reset FAILED hr=0x%08lx", hr);
        return false;
    }

    uint32_t displayW = cachedDisplayW_;
    uint32_t displayH = cachedDisplayH_;

    // Clamp to [1, 4] — the SDK struct has outputs[4].
    if (numGeneratedFrames == 0) numGeneratedFrames = 1;
    if (numGeneratedFrames > 4) numGeneratedFrames = 4;

    // Resolve pool resources
    ID3D12Resource* colorRes  = (colorIndex_  >= 0) ? colorPool_->resources[colorIndex_]  : nullptr;

    if (!colorRes) {
        diagLog("[Fg4Context] generate: color resource not available (color=%p)",
            (void*)colorRes);
        return false;
    }

    // Fill outputs[0..N-1] from the pool using the FG_OUTPUT_SLOTS mapping.
    // Slot 0 = outputIndex_ (pool 5), slot 1 = pool 7, slot 2 = pool 8, slot 3 = pool 9.
    FfxApiResource outputInfos[4] = {};
    for (uint32_t i = 0; i < numGeneratedFrames; i++) {
        int poolIdx = (i == 0) ? outputIndex_ : FG_OUTPUT_SLOTS[i];
        ID3D12Resource* outRes = (poolIdx >= 0 && poolIdx < (int)D3D12SharedResources::COUNT)
            ? colorPool_->resources[poolIdx] : nullptr;
        if (!outRes) {
            diagLog("[Fg4Context] generate: output slot %u pool index %d not available",
                i, poolIdx);
            return false;
        }
        wchar_t name[32];
        swprintf_s(name, 32, L"fg_output_%u", i);
        outputInfos[i] = makeResource(outRes, displayW, displayH,
            FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, FFX_API_RESOURCE_USAGE_UAV, name);
    }

    FfxApiResource colorInfo = makeResource(colorRes, displayW, displayH,
        FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM, FFX_API_RESOURCE_USAGE_READ_ONLY,
        L"fg_color");

    ffxDispatchDescFrameGeneration genDesc{};
    genDesc.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION;
    genDesc.frameID = currentFrameID_;
	    // CRITICAL FIX: Always pass reset=false to generate().
	    //
	    // The FG SDK lazily allocates internal interpolation/history buffers.
	    // When reset=true, the SDK discards history and SKIPS buffer allocation.
	    // The next generate with reset=false then dereferences null internal
	    // pointers → 0xc0000005 access violation.
	    //
	    // By always passing reset=false, the SDK uses its interpolation path
	    // from the very first generate. On the first frame (no previous data),
	    // the SDK gracefully falls back to copying presentColor to output.
	    //
	    // The prepare's reset flag (reset_) correctly resets the SDK's
	    // camera/MV accumulation state — only the generate's reset is forced
	    // to false to protect the internal buffer lifecycle.
	    genDesc.reset = false;
    genDesc.commandList = fgCmdList_.Get();
    genDesc.presentColor = colorInfo;
    for (uint32_t i = 0; i < numGeneratedFrames; i++) {
        genDesc.outputs[i] = outputInfos[i];
    }
    genDesc.numGeneratedFrames = numGeneratedFrames;
    genDesc.backbufferTransferFunction = FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
    genDesc.minMaxLuminance[0] = 0.0f;
    genDesc.minMaxLuminance[1] = 1000.0f;
    genDesc.generationRect.x      = 0;
    genDesc.generationRect.y      = 0;
    genDesc.generationRect.width  = static_cast<int32_t>(displayW);
    genDesc.generationRect.height = static_cast<int32_t>(displayH);

    long t0 = diagTimeMs();
    int dispatchResult = dispatchGenerateSeh(&ffxContext_, &genDesc);
    long genMs = diagTimeMs() - t0;

    if (dispatchResult == 2) {
        // Crash — clean up
        consecutiveCrashes_++;
	        diagLog("[Fg4Context] generate: CRASH (consecutive=%d/%d, frameID=%llu)",
	            consecutiveCrashes_, MAX_CONSECUTIVE_CRASHES,
	            (unsigned long long)currentFrameID_);

        // CRITICAL FIX (2026-08-30, same policy as the prepare crash path
        // above): do NOT close/execute the partially-recorded command list.
        // The FG SDK may have recorded partial or corrupted GPU commands
        // before the crash, and submitting them causes a GPU timeout (TDR)
        // that removes the entire D3D12 device (DXGI_ERROR_DEVICE_REMOVED
        // 0x887a0005 → allocator Reset E_FAIL storm → heap corruption, the
        // exact crash fixed on the FSR4 side the same day). Discard the
        // corrupted allocator+list entirely — the next prepare's
        // reinitialize() recreates them via createDedicatedCommandResources()
        // (which also resets lastFenceValue_). No fence signal: nothing was
        // submitted.
        fgCmdAlloc_.Detach();
        fgCmdList_.Detach();

        if (ffxContext_ && g_fgDestroyContext) {
            __try { g_fgDestroyContext(&ffxContext_, nullptr); }
            __except (EXCEPTION_EXECUTE_HANDLER) {}
        }
        ffxContext_ = nullptr;

        if (consecutiveCrashes_ >= MAX_CONSECUTIVE_CRASHES) {
            permanentlyDisabled_ = true;
            diagLog("[Fg4Context] PERMANENTLY DISABLED after %d consecutive crashes", consecutiveCrashes_);
        }
        return false;
    }

    if (dispatchResult == 1) {
        diagLog("[Fg4Context] generate: g_fgDispatch FAILED (rc=error, took=%ldms color=%p genRect=%dx%d)",
            genMs, (void*)colorRes, displayW, displayH);
        fgCmdList_->Close();
        ID3D12CommandList* lists[] = { fgCmdList_.Get() };
        dev.commandQueue()->ExecuteCommandLists(1, lists);
        lastFenceValue_ = signalFgFence();
        waitForFgFence(lastFenceValue_, 1000);
        return false;
    }

    // Success — reset crash counter
    consecutiveCrashes_ = 0;

	    // OPTIMIZATION: rate-limit the per-frame success log — diagLog does
	    // fopen/fclose + stderr flush per call, measurable waste at 60fps (the
	    // log previously accumulated 4k+ generate lines). Log only slow frames
	    // (>= 6ms) or every 128th frame.
	    static int generateCounter = 0;
	    if (genMs >= 6 || (++generateCounter & 127) == 0) {
	        diagLog("[Fg4Context] generate: frameID=%llu OK took=%ldms genRect=%dx%d",
	            (unsigned long long)currentFrameID_, genMs, displayW, displayH);
	    }

    fgCmdList_->Close();
    ID3D12CommandList* lists[] = { fgCmdList_.Get() };
    dev.commandQueue()->ExecuteCommandLists(1, lists);
    lastFenceValue_ = signalFgFence();

    return true;
}

// ----------------------------------------------------------------
bool Fg4Context::finish() {
    if (lastFenceValue_ == 0) return true;
    return waitForFgFence(lastFenceValue_, 5000);
}

// ----------------------------------------------------------------
void Fg4Context::destroy() {
    if (ffxContext_) {
        finish();
        if (g_fgDestroyContext) {
            __try {
                g_fgDestroyContext(&ffxContext_, nullptr);
            } __except (EXCEPTION_EXECUTE_HANDLER) {
                diagLog("[Fg4Context] CRASH in g_fgDestroyContext (code=0x%08lx)",
                    GetExceptionCode());
            }
        }
    }
    ffxContext_ = nullptr;
    lastFenceValue_ = 0;
    colorPool_ = nullptr;
    cachedRenderW_ = 0;
    cachedRenderH_ = 0;
    cachedDisplayW_ = 0;
    cachedDisplayH_ = 0;
    consecutiveCrashes_ = 0;
    permanentlyDisabled_ = false;
    forceReset_ = false;
    destroyDedicatedCommandResources();
}

} // namespace fsr
