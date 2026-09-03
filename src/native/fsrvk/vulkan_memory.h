#pragma once
#include <vulkan/vulkan.h>
#include <cstdint>

namespace fsr {

/// Per-allocation tracker — heap allocated, pointer returned to Java as jlong.
/// Do NOT use the raw HANDLE as a map key (Windows recycles handle integers).
struct FsrSharedAllocation {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* win32Handle = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    VkDeviceSize allocationSize = 0;
};

/// Allocate VkImage + exportable VkDeviceMemory, export Win32 HANDLE.
/// Returns heap-allocated FsrSharedAllocation (caller owns, must delete via releaseSharedAllocation).
FsrSharedAllocation* allocateSharedAllocation(uint32_t width, uint32_t height);

/// Destroy VkImage + vkFreeMemory + CloseHandle + delete alloc.
void releaseSharedAllocation(FsrSharedAllocation* alloc);

} // namespace fsr
