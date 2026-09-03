#pragma once
#include "ffx_api.h"

// Context creation flags
enum {
    FFX_UPSCALE_ENABLE_HIGH_DYNAMIC_RANGE                 = (1 << 0),
    FFX_UPSCALE_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS  = (1 << 1),
    FFX_UPSCALE_ENABLE_MOTION_VECTORS_JITTER_CANCELLATION = (1 << 2),
    FFX_UPSCALE_ENABLE_DEPTH_INVERTED                     = (1 << 3),
    FFX_UPSCALE_ENABLE_DEPTH_INFINITE                     = (1 << 4),
    FFX_UPSCALE_ENABLE_AUTO_EXPOSURE                      = (1 << 5),
    FFX_UPSCALE_ENABLE_DYNAMIC_RESOLUTION                 = (1 << 6),
    FFX_UPSCALE_ENABLE_DEBUG_CHECKING                     = (1 << 7),
    FFX_UPSCALE_ENABLE_NON_LINEAR_COLORSPACE              = (1 << 8),
    FFX_UPSCALE_ENABLE_DEBUG_VISUALIZATION                = (1 << 9),
};

// Version macro
#define FFX_UPSCALER_VERSION_MAJOR 4
#define FFX_UPSCALER_VERSION_MINOR 1
#define FFX_UPSCALER_VERSION_PATCH 1
#define FFX_UPSCALER_MAKE_VERSION(major, minor, patch) (((major) << 22) | ((minor) << 12) | (patch))
#define FFX_UPSCALER_VERSION FFX_UPSCALER_MAKE_VERSION(FFX_UPSCALER_VERSION_MAJOR, FFX_UPSCALER_VERSION_MINOR, FFX_UPSCALER_VERSION_PATCH)

typedef void (*ffxApiMessage)(uint32_t, void*);

struct ffxCreateContextDescUpscale {
    ffxCreateContextDescHeader header;
    uint32_t                   flags;
    FfxApiDimensions2D         maxRenderSize;
    FfxApiDimensions2D         maxUpscaleSize;
    ffxApiMessage              fpMessage;
};

struct ffxCreateContextDescUpscaleVersion {
    ffxCreateContextDescHeader header;
    uint32_t                   version;
};

struct ffxDispatchDescUpscale {
    ffxDispatchDescHeader      header;
    void*                      commandList;
    FfxApiResource             color;
    FfxApiResource             depth;
    FfxApiResource             motionVectors;
    FfxApiResource             exposure;
    FfxApiResource             reactive;
    FfxApiResource             transparencyAndComposition;
    FfxApiResource             output;
    FfxApiFloatCoords2D        jitterOffset;
    FfxApiFloatCoords2D        motionVectorScale;
    FfxApiDimensions2D         renderSize;
    FfxApiDimensions2D         upscaleSize;
    bool                       enableSharpening;
    float                      sharpness;
    float                      frameTimeDelta;
    float                      preExposure;
    bool                       reset;
    float                      cameraNear;
    float                      cameraFar;
    float                      cameraFovAngleVertical;
    float                      viewSpaceToMetersFactor;
    uint32_t                   flags;
};
