// This file is part of the FidelityFX SDK.
//
// Copyright (C) 2026 Advanced Micro Devices, Inc.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
#pragma once
#include "ffx_api.h"

// Minimal Frame Generation API types for the unified FFX API 2.x
// (amd_fidelityfx_framegeneration_dx12.dll v4.0.1). These definitions
// mirror the official sdk/include/ffx_framegeneration.h + ffx_api_types.h
// ABI exactly (struct member order and types), adapted to the reduced
// ffx_api.h that ships in this repo.

#define FFX_FRAMEGENERATION_VERSION_MAJOR 4
#define FFX_FRAMEGENERATION_VERSION_MINOR 0
#define FFX_FRAMEGENERATION_VERSION_PATCH 1
#define FFX_FRAMEGENERATION_MAKE_VERSION(major, minor, patch) (((major) << 22) | ((minor) << 12) | (patch))
#define FFX_FRAMEGENERATION_VERSION FFX_FRAMEGENERATION_MAKE_VERSION(FFX_FRAMEGENERATION_VERSION_MAJOR, FFX_FRAMEGENERATION_VERSION_MINOR, FFX_FRAMEGENERATION_VERSION_PATCH)

#if defined(__cplusplus)
extern "C" {
#endif

// Context creation flags (FfxApiCreateContextFramegenerationFlags)
enum FfxApiCreateContextFramegenerationFlags
{
    FFX_FRAMEGENERATION_ENABLE_ASYNC_WORKLOAD_SUPPORT = (1 << 0),
    FFX_FRAMEGENERATION_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS = (1 << 1),
    FFX_FRAMEGENERATION_ENABLE_MOTION_VECTORS_JITTER_CANCELLATION = (1 << 2),
    FFX_FRAMEGENERATION_ENABLE_DEPTH_INVERTED = (1 << 3),
    FFX_FRAMEGENERATION_ENABLE_DEPTH_INFINITE = (1 << 4),
    FFX_FRAMEGENERATION_ENABLE_HIGH_DYNAMIC_RANGE = (1 << 5),
    FFX_FRAMEGENERATION_ENABLE_DEBUG_CHECKING = (1 << 6),
};

// Dispatch flags (FfxApiDispatchFramegenerationFlags)
enum FfxApiDispatchFramegenerationFlags
{
    FFX_FRAMEGENERATION_FLAG_DRAW_DEBUG_TEAR_LINES = (1 << 0),
    FFX_FRAMEGENERATION_FLAG_DRAW_DEBUG_RESET_INDICATORS = (1 << 1),
    FFX_FRAMEGENERATION_FLAG_DRAW_DEBUG_VIEW = (1 << 2),
    FFX_FRAMEGENERATION_FLAG_NO_SWAPCHAIN_CONTEXT_NOTIFY = (1 << 3),
    FFX_FRAMEGENERATION_FLAG_DRAW_DEBUG_PACING_LINES = (1 << 4),
    FFX_FRAMEGENERATION_FLAG_RESERVED_1 = (1 << 5),
    FFX_FRAMEGENERATION_FLAG_RESERVED_2 = (1 << 6),
};

// Backbuffer transfer function (FfxApiBackbufferTransferFunction)
enum FfxApiBackbufferTransferFunction
{
    FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SRGB,
    FFX_API_BACKBUFFER_TRANSFER_FUNCTION_PQ,
    FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SCRGB
};

// 2D rect. ABI note: field names x/y/width/height are used here; the
// official SDK names them left/top/width/height — identical layout
// (four sequential int32), so ABI compatibility is preserved.
struct FfxApiRect2D
{
    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
};

// Effect memory usage (only needed by Query — kept for completeness)
typedef struct FfxApiEffectMemoryUsage
{
    uint64_t totalUsageInBytes;
    uint64_t aliasableUsageInBytes;
} FfxApiEffectMemoryUsage;

#define FFX_API_EFFECT_ID_FRAMEGENERATION 0x00020000u

#define FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION   FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x01)
#define FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION        FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x02)
#define FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION         FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x03)
#define FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x04)
#define FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_V2 FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x0c)
#define FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION_VERSION FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_FRAMEGENERATION, 0x0e)

// Context creation descriptor
struct ffxCreateContextDescFrameGeneration
{
    ffxCreateContextDescHeader header;
    uint32_t flags;                     ///< FfxApiCreateContextFramegenerationFlags
    struct FfxApiDimensions2D displaySize;
    struct FfxApiDimensions2D maxRenderSize;
    uint32_t backBufferFormat;          ///< FfxApiSurfaceFormat
};

// Version descriptor (chained via header.pNext)
struct ffxCreateContextDescFrameGenerationVersion
{
    ffxCreateContextDescHeader header;
    uint32_t version;                   ///< FFX_FRAMEGENERATION_VERSION
};

// Forward declarations for callbacks
typedef struct ffxCallbackDescFrameGenerationPresent ffxCallbackDescFrameGenerationPresent;
typedef struct ffxDispatchDescFrameGeneration ffxDispatchDescFrameGeneration;

typedef ffxReturnCode_t (FFX_CALL* FfxApiPresentCallbackFunc)(ffxCallbackDescFrameGenerationPresent* params, void* pUserCtx);
typedef ffxReturnCode_t (FFX_CALL* FfxApiFrameGenerationDispatchFunc)(ffxDispatchDescFrameGeneration* params, void* pUserCtx);

// Configuration descriptor
struct ffxConfigureDescFrameGeneration
{
    ffxConfigureDescHeader header;
    void* swapChain;                        ///< IDXGISwapChain (null in no-swapchain mode)
    FfxApiPresentCallbackFunc presentCallback;
    void* presentCallbackUserContext;
    FfxApiFrameGenerationDispatchFunc frameGenerationCallback;
    void* frameGenerationCallbackUserContext;
    bool frameGenerationEnabled;
    bool allowAsyncWorkloads;
    struct FfxApiResource HUDLessColor;
    uint32_t flags;                         ///< FfxApiDispatchFramegenerationFlags
    bool onlyPresentGenerated;
    struct FfxApiRect2D generationRect;
    uint64_t frameID;
};

// Prepare V2 dispatch descriptor (two-phase dispatch: PrepareV2 then FrameGeneration)
struct ffxDispatchDescFrameGenerationPrepareV2
{
    ffxDispatchDescHeader header;
    uint64_t frameID;
    uint32_t flags;
    void* commandList;
    struct FfxApiDimensions2D renderSize;
    struct FfxApiFloatCoords2D jitterOffset;
    struct FfxApiFloatCoords2D motionVectorScale;
    float frameTimeDelta;                   ///< ms since last frame
    bool reset;
    float cameraNear;
    float cameraFar;
    float cameraFovAngleVertical;           ///< radians
    float viewSpaceToMetersFactor;
    struct FfxApiResource depth;
    struct FfxApiResource motionVectors;
    float cameraPosition[3];
    float cameraUp[3];
    float cameraRight[3];
    float cameraForward[3];
};

// Frame generation dispatch descriptor
typedef struct ffxDispatchDescFrameGeneration
{
    ffxDispatchDescHeader header;
    void* commandList;
    struct FfxApiResource presentColor;
    struct FfxApiResource outputs[4];
    uint32_t numGeneratedFrames;
    bool reset;
    uint32_t backbufferTransferFunction;    ///< FfxApiBackbufferTransferFunction
    float minMaxLuminance[2];
    struct FfxApiRect2D generationRect;
    uint64_t frameID;
} ffxDispatchDescFrameGeneration;

#if defined(__cplusplus)
} // extern "C"
#endif
