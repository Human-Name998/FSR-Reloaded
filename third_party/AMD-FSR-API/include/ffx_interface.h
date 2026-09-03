#pragma once
#include <cstdint>
typedef struct FfxContext_* FfxContext;
struct FfxResource { void* internal; };
struct FfxResourceDescription { uint32_t width; uint32_t height; uint32_t depth; };
enum FfxResourceState : uint32_t {
    FFX_RESOURCE_STATE_COMPUTE_READ = 0,
    FFX_RESOURCE_STATE_UNORDERED_ACCESS = 1
};
