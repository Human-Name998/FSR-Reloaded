#include "fsr4_context.h"
#include "d3d12_device.h"
#include <cstdio>
#include <cstring>
#include <cwchar>

#ifdef _WIN32
#include <windows.h>
#include <excpt.h>
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
}

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
    upscaleDesc.flags = FFX_UPSCALE_ENABLE_DEPTH_INVERTED | FFX_UPSCALE_ENABLE_AUTO_EXPOSURE;
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
    dispatchDesc.jitterOffset.y = params.jitterY;
	    // MV is generated in pixel-space by MV_GEN_CS: mv = (prevUv - uv) * renderSize.
	    // The FSR4 SDK expects UV-space motion vectors internally. Convert via
	    // motionVectorScale = 1/renderSize (same convention as FSR2/3, which do
	    // rawMv * uMotionVectorScale with uMotionVectorScale = 1/renderSize).
	    // Without this conversion, a 50-pixel motion is interpreted as 50 UV units
	    // (50x screen width) — the SDK's temporal accumulator discards history
	    // every frame, causing smearing from zero temporal accumulation.
	    dispatchDesc.motionVectorScale.x = 1.0f / static_cast<float>(renderW);
	    dispatchDesc.motionVectorScale.y = 1.0f / static_cast<float>(renderH);
    dispatchDesc.renderSize.width   = static_cast<int32_t>(renderW);
    dispatchDesc.renderSize.height  = static_cast<int32_t>(renderH);
    dispatchDesc.upscaleSize.width  = static_cast<int32_t>(displayW);
    dispatchDesc.upscaleSize.height = static_cast<int32_t>(displayH);
    dispatchDesc.enableSharpening = false;
    dispatchDesc.sharpness        = 0.0f;
    dispatchDesc.frameTimeDelta   = params.deltaTimeMs;
    dispatchDesc.preExposure      = 1.0f;
    dispatchDesc.reset            = params.reset;
    dispatchDesc.cameraNear       = params.cameraNear;
    dispatchDesc.cameraFar        = params.cameraFar;
    dispatchDesc.cameraFovAngleVertical = params.fovYRad;
    dispatchDesc.viewSpaceToMetersFactor = 1.0f;
    dispatchDesc.flags = 0;

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
        // Close + execute the (partial) list so the barriers at least
        // get a chance to flush, then bail.
	        dev.commandList()->Close();
	        ID3D12CommandList* lists[] = { dev.commandList() };
	        dev.commandQueue()->ExecuteCommandLists(1, lists);
	        lastFenceValue_ = dev.signalFence();
	        dev.advanceAllocator(lastFenceValue_);
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
}

} // namespace fsr
