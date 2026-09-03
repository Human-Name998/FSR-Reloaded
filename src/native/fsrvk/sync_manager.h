#pragma once
#include <vulkan/vulkan.h>

#ifdef _WIN32

namespace fsr {

class SyncManager {
public:
    VkSemaphore createExportableSemaphore(VkDevice device);
    HANDLE exportSemaphoreToWin32Handle(VkDevice device, VkSemaphore semaphore);
    void destroySemaphore(VkDevice device, VkSemaphore semaphore);
};

} // namespace fsr

#endif // _WIN32
