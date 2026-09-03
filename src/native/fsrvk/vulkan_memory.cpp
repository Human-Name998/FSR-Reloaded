#include "vulkan_memory.h"
#include "vulkan_device.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace fsr {

namespace {

int findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags requiredFlags) {
    auto& dev = VulkanDevice::get();
    VkPhysicalDeviceMemoryProperties props;
    vkGetPhysicalDeviceMemoryProperties(dev.physicalDevice(), &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (props.memoryTypes[i].propertyFlags & requiredFlags) == requiredFlags) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

} // anonymous namespace

// ----------------------------------------------------------------
FsrSharedAllocation* allocateSharedAllocation(uint32_t width, uint32_t height) {
    auto& dev = VulkanDevice::get();
    dev.ensureInitialized();

    auto* alloc = new FsrSharedAllocation();
    alloc->width = width;
    alloc->height = height;

    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imgInfo.extent = {width, height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT
                  | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                  | VK_IMAGE_USAGE_STORAGE_BIT
                  | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkExternalMemoryImageCreateInfo extImgInfo{};
    extImgInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImgInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    imgInfo.pNext = &extImgInfo;

    VkResult result = vkCreateImage(dev.device(), &imgInfo, nullptr, &alloc->image);
    if (result != VK_SUCCESS) {
        delete alloc;
        throw std::runtime_error("vkCreateImage failed: " + std::to_string(result));
    }

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(dev.device(), alloc->image, &memReqs);
    alloc->allocationSize = memReqs.size;

    int memType = findMemoryType(memReqs.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType < 0) memType = findMemoryType(memReqs.memoryTypeBits, 0);
    if (memType < 0) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("No compatible memory type for shared image");
    }

    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.image = alloc->image;

    VkExportMemoryAllocateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    // Dedicated allocation is required when exporting Win32 handle types
    exportInfo.pNext = &dedicatedInfo;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
    allocInfo.pNext = &exportInfo;

    result = vkAllocateMemory(dev.device(), &allocInfo, nullptr, &alloc->memory);
    if (result != VK_SUCCESS) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkAllocateMemory failed: " + std::to_string(result));
    }

    result = vkBindImageMemory(dev.device(), alloc->image, alloc->memory, 0);
    if (result != VK_SUCCESS) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkBindImageMemory failed: " + std::to_string(result));
    }

    // ----------------------------------------------------------
    // Transition image layout to GENERAL so it's valid for GL access
    // ----------------------------------------------------------
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = dev.queueFamilyIndex();
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;

    VkCommandPool cmdPool = VK_NULL_HANDLE;
    result = vkCreateCommandPool(dev.device(), &poolInfo, nullptr, &cmdPool);
    if (result != VK_SUCCESS) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkCreateCommandPool failed: " + std::to_string(result));
    }

    VkCommandBufferAllocateInfo cmdAlloc{};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = cmdPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;

    VkCommandBuffer cmdBuf = VK_NULL_HANDLE;
    result = vkAllocateCommandBuffers(dev.device(), &cmdAlloc, &cmdBuf);
    if (result != VK_SUCCESS) {
        vkDestroyCommandPool(dev.device(), cmdPool, nullptr);
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkAllocateCommandBuffers failed: " + std::to_string(result));
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmdBuf, &beginInfo);

    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = alloc->image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    // No prior access, destination access for any usage
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;

    vkCmdPipelineBarrier(cmdBuf,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(cmdBuf);

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmdBuf;

    result = vkQueueSubmit(dev.queue(), 1, &submitInfo, VK_NULL_HANDLE);
    if (result != VK_SUCCESS) {
        vkDestroyCommandPool(dev.device(), cmdPool, nullptr);
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkQueueSubmit for layout transition failed: " + std::to_string(result));
    }

    vkQueueWaitIdle(dev.queue());
    vkDestroyCommandPool(dev.device(), cmdPool, nullptr);

    // ----------------------------------------------------------
    // Export Win32 HANDLE
    // ----------------------------------------------------------
    auto pfn = dev.getMemoryWin32HandleKHR();
    if (!pfn) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkGetMemoryWin32HandleKHR function not found (cached)");
    }

    VkMemoryGetWin32HandleInfoKHR getHandleInfo{};
    getHandleInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR;
    getHandleInfo.memory = alloc->memory;
    getHandleInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    result = pfn(dev.device(), &getHandleInfo, &alloc->win32Handle);
    if (result != VK_SUCCESS) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
        vkDestroyImage(dev.device(), alloc->image, nullptr);
        delete alloc;
        throw std::runtime_error("vkGetMemoryWin32HandleKHR failed: " + std::to_string(result));
    }

    return alloc;
}

// ----------------------------------------------------------------
void releaseSharedAllocation(FsrSharedAllocation* alloc) {
    if (!alloc) return;
    auto& dev = VulkanDevice::get();

    if (alloc->image != VK_NULL_HANDLE) {
        vkDestroyImage(dev.device(), alloc->image, nullptr);
    }
    if (alloc->memory != VK_NULL_HANDLE) {
        vkFreeMemory(dev.device(), alloc->memory, nullptr);
    }
    if (alloc->win32Handle) {
        CloseHandle(alloc->win32Handle);
    }

    delete alloc;
}

} // namespace fsr