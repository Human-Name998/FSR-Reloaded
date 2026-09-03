#include "sync_manager.h"
#include <cstdio>

#ifdef _WIN32

namespace fsr {

VkSemaphore SyncManager::createExportableSemaphore(VkDevice device) {
    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;

    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkResult result = vkCreateSemaphore(device, &semInfo, nullptr, &semaphore);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[SyncManager] vkCreateSemaphore failed: %d\n", result);
        return VK_NULL_HANDLE;
    }
    return semaphore;
}

HANDLE SyncManager::exportSemaphoreToWin32Handle(VkDevice device, VkSemaphore semaphore) {
    auto pfn = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(
        vkGetDeviceProcAddr(device, "vkGetSemaphoreWin32HandleKHR"));
    if (!pfn) {
        fprintf(stderr, "[SyncManager] vkGetSemaphoreWin32HandleKHR not found\n");
        return nullptr;
    }

    VkSemaphoreGetWin32HandleInfoKHR getHandleInfo{};
    getHandleInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR;
    getHandleInfo.semaphore = semaphore;
    getHandleInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    HANDLE handle = nullptr;
    VkResult result = pfn(device, &getHandleInfo, &handle);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[SyncManager] vkGetSemaphoreWin32HandleKHR failed: %d\n", result);
        return nullptr;
    }
    return handle;
}

void SyncManager::destroySemaphore(VkDevice device, VkSemaphore semaphore) {
    if (semaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(device, semaphore, nullptr);
    }
}

} // namespace fsr

#endif // _WIN32
