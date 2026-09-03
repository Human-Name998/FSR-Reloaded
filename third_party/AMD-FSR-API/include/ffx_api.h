#pragma once
#include <cstdint>
#include <cstddef>

#ifdef FFX_API_ENTRY
#undef FFX_API_ENTRY
#endif
#define FFX_API_ENTRY

#ifdef _WIN32
#include <windows.h>
#define FFX_CALL __stdcall
#else
#define FFX_CALL
#endif

typedef uint64_t ffxStructType_t;

struct ffxApiHeader {
    ffxStructType_t type;
    struct ffxApiHeader* pNext;
};

typedef ffxApiHeader ffxCreateContextDescHeader;
typedef ffxApiHeader ffxConfigureDescHeader;
typedef ffxApiHeader ffxQueryDescHeader;
typedef ffxApiHeader ffxDispatchDescHeader;

typedef uint32_t ffxReturnCode_t;
enum {
    FFX_API_RETURN_OK                     = 0,
    FFX_API_RETURN_ERROR                  = 1,
    FFX_API_RETURN_ERROR_UNKNOWN_DESCTYPE = 2,
    FFX_API_RETURN_ERROR_RUNTIME_ERROR    = 3,
    FFX_API_RETURN_NO_PROVIDER            = 4,
    FFX_API_RETURN_ERROR_MEMORY           = 5,
    FFX_API_RETURN_ERROR_PARAMETER        = 6,
    FFX_API_RETURN_PROVIDER_NO_SUPPORT_NEW_DESCTYPE = 7,
};

typedef void* ffxContext;
typedef void* ffxDevice;

struct FfxApiDimensions2D {
    int32_t width;
    int32_t height;
};

enum {
    FFX_API_RESOURCE_TYPE_BUFFER       = 0,
    FFX_API_RESOURCE_TYPE_TEXTURE1D    = 1,
    FFX_API_RESOURCE_TYPE_TEXTURE2D    = 2,
    FFX_API_RESOURCE_TYPE_TEXTURE_CUBE = 3,
    FFX_API_RESOURCE_TYPE_TEXTURE3D    = 4,
};

enum {
    FFX_API_SURFACE_FORMAT_UNKNOWN,
    FFX_API_SURFACE_FORMAT_R32G32B32A32_TYPELESS,
    FFX_API_SURFACE_FORMAT_R32G32B32A32_UINT,
    FFX_API_SURFACE_FORMAT_R32G32B32A32_FLOAT,
    FFX_API_SURFACE_FORMAT_R16G16B16A16_FLOAT,
    FFX_API_SURFACE_FORMAT_R32G32B32_FLOAT,
    FFX_API_SURFACE_FORMAT_R32G32_FLOAT,
    FFX_API_SURFACE_FORMAT_R8_UINT,
    FFX_API_SURFACE_FORMAT_R32_UINT,
    FFX_API_SURFACE_FORMAT_R8G8B8A8_TYPELESS,
    FFX_API_SURFACE_FORMAT_R8G8B8A8_UNORM,
    FFX_API_SURFACE_FORMAT_R8G8B8A8_SNORM,
    FFX_API_SURFACE_FORMAT_R8G8B8A8_SRGB,
    FFX_API_SURFACE_FORMAT_B8G8R8A8_TYPELESS,
    FFX_API_SURFACE_FORMAT_B8G8R8A8_UNORM,
    FFX_API_SURFACE_FORMAT_B8G8R8A8_SRGB,
    FFX_API_SURFACE_FORMAT_R11G11B10_FLOAT,
    FFX_API_SURFACE_FORMAT_R10G10B10A2_UNORM,
    FFX_API_SURFACE_FORMAT_R16G16_FLOAT,
    FFX_API_SURFACE_FORMAT_R16G16_UINT,
    FFX_API_SURFACE_FORMAT_R16G16_SINT,
    FFX_API_SURFACE_FORMAT_R16_FLOAT,
    FFX_API_SURFACE_FORMAT_R16_UINT,
    FFX_API_SURFACE_FORMAT_R16_UNORM,
    FFX_API_SURFACE_FORMAT_R16_SNORM,
    FFX_API_SURFACE_FORMAT_R8_UNORM,
    FFX_API_SURFACE_FORMAT_R8G8_UNORM,
    FFX_API_SURFACE_FORMAT_R8G8_UINT,
    FFX_API_SURFACE_FORMAT_R32_FLOAT,
    FFX_API_SURFACE_FORMAT_R9G9B9E5_SHAREDEXP,
    FFX_API_SURFACE_FORMAT_R16G16B16A16_TYPELESS,
    FFX_API_SURFACE_FORMAT_R32G32_TYPELESS,
};

enum {
    FFX_API_RESOURCE_STATE_COMMON               = (1 << 0),
    FFX_API_RESOURCE_STATE_UNORDERED_ACCESS     = (1 << 1),
    FFX_API_RESOURCE_STATE_COMPUTE_READ         = (1 << 2),
    FFX_API_RESOURCE_STATE_PIXEL_READ           = (1 << 3),
    FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ   = (1 << 3) | (1 << 2),
    FFX_API_RESOURCE_STATE_COPY_SRC             = (1 << 4),
    FFX_API_RESOURCE_STATE_COPY_DEST            = (1 << 5),
    FFX_API_RESOURCE_STATE_GENERIC_READ         = (1 << 4) | (1 << 2),
    FFX_API_RESOURCE_STATE_INDIRECT_ARGUMENT    = (1 << 6),
    FFX_API_RESOURCE_STATE_PRESENT              = (1 << 7),
    FFX_API_RESOURCE_STATE_RENDER_TARGET        = (1 << 8),
    FFX_API_RESOURCE_STATE_DEPTH_ATTACHMENT     = (1 << 9),
};

enum {
    FFX_API_RESOURCE_FLAGS_NONE      = 0,
    FFX_API_RESOURCE_FLAGS_ALIASABLE = (1 << 0),
    FFX_API_RESOURCE_FLAGS_UNDEFINED = (1 << 1),
};

enum {
    FFX_API_RESOURCE_USAGE_READ_ONLY     = 0,
    FFX_API_RESOURCE_USAGE_RENDERTARGET  = (1 << 0),
    FFX_API_RESOURCE_USAGE_UAV           = (1 << 1),
    FFX_API_RESOURCE_USAGE_DEPTHTARGET   = (1 << 2),
    FFX_API_RESOURCE_USAGE_INDIRECT      = (1 << 3),
    FFX_API_RESOURCE_USAGE_ARRAYVIEW     = (1 << 4),
    FFX_API_RESOURCE_USAGE_STENCILTARGET = (1 << 5),
    FFX_API_RESOURCE_USAGE_DCC_RENDERTARGET = (1 << 15),
};

struct FfxApiResourceDescription {
    uint32_t type;
    uint32_t format;
    union { uint32_t width;  uint32_t size; };
    union { uint32_t height; uint32_t stride; };
    union { uint32_t depth;  uint32_t alignment; };
    uint32_t mipCount;
    uint32_t flags;
    uint32_t usage;
};

struct FfxApiResource {
    void* resource;
    FfxApiResourceDescription description;
    uint32_t state;
};

struct FfxApiFloatCoords2D {
    float x;
    float y;
};

// Allocation callbacks (opaque for our use — we pass nullptr)
struct ffxAllocationCallbacks;

// Unified API function pointer types
typedef ffxReturnCode_t (FFX_CALL* FfnCreateContext)(ffxContext*, ffxCreateContextDescHeader*, const ffxAllocationCallbacks*);
typedef ffxReturnCode_t (FFX_CALL* FfnDestroyContext)(ffxContext*, const ffxAllocationCallbacks*);
typedef ffxReturnCode_t (FFX_CALL* FfnDispatch)(ffxContext*, const ffxDispatchDescHeader*);
typedef ffxReturnCode_t (FFX_CALL* FfnConfigure)(ffxContext*, const ffxConfigureDescHeader*);
typedef ffxReturnCode_t (FFX_CALL* FfnQuery)(ffxContext*, ffxQueryDescHeader*);

// Effect ID helpers
#define FFX_API_EFFECT_MASK         0x00ff0000u
#define FFX_API_BACKEND_MASK        0xff000000u
#define FFX_API_EFFECT_ID_UPSCALE   0x00010000u
#define FFX_API_MAKE_EFFECT_SUB_ID(effectId, subversion) ((effectId & FFX_API_EFFECT_MASK) | (subversion & ~FFX_API_EFFECT_MASK))
#define FFX_API_BACKEND_ID_DX12     0x00000000u
#define FFX_API_MAKE_BACKEND_SUB_ID(backendId, subversion) ((backendId & FFX_API_BACKEND_MASK) | (subversion & ~FFX_API_BACKEND_MASK))

#define FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE          FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_UPSCALE, 0x00)
#define FFX_API_DISPATCH_DESC_TYPE_UPSCALE                FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_UPSCALE, 0x01)
#define FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE_VERSION  FFX_API_MAKE_EFFECT_SUB_ID(FFX_API_EFFECT_ID_UPSCALE, 0x0b)
