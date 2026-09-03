#pragma once
#include "ffx_interface.h"
#include "ffx_error.h"
#include "ffx_backend.h"

#define FFX_FSR3_UPSCALER_TYPE_FSR4 4
#define FFX_FSR3_UPSCALER_ENABLE_DEPTH_INVERTED (1 << 3)
#define FFX_FSR3_UPSCALER_ENABLE_LOW_DYNAMIC_RANGE (1 << 2)

struct FfxFsr3CreateContextDesc {
    uint32_t flags;
    int maxRenderSize[3];
    int displaySize[3];
    FfxBackendInterface backendInterface;
    int upscaleType;
};

struct FfxFsr3UpscaleRenderDesc { void* dummy; };

FfxReturnCode ffxFsr3CreateContext(FfxContext* ctx, const FfxFsr3CreateContextDesc* desc);
FfxReturnCode ffxFsr3UpscaleRenderSubmit(FfxContext* ctx, const FfxFsr3UpscaleRenderDesc* desc);
FfxReturnCode ffxFsr3ContextDestroy(FfxContext* ctx);
