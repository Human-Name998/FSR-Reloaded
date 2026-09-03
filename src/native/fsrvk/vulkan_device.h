#pragma once
#include <vulkan/vulkan.h>
#include <exception>
#include <mutex>
#include <string>

namespace fsr {

/// Singleton Vulkan device manager.
class VulkanDevice {
public:
    static VulkanDevice& get();

    VkInstance instance() const { return instance_; }
    VkPhysicalDevice physicalDevice() const { return physDevice_; }
    VkDevice device() const { return device_; }
    uint32_t queueFamilyIndex() const { return queueFamily_; }
    VkQueue queue() const { return queue_; }
    uint32_t computeQueueFamilyIndex() const { return computeQueueFamily_; }
    VkQueue computeQueue() const { return computeQueue_; }

    const std::string& deviceName() const { return deviceName_; }
    const std::string& driverVersion() const { return driverVersion_; }

    /// Verify instance/device are created. Throws std::runtime_error on failure.
    void ensureInitialized();

    /// Explicit teardown — call from JNI terminate().
    void destroy();

    /// Cached vkGetMemoryWin32HandleKHR function pointer.
    PFN_vkGetMemoryWin32HandleKHR getMemoryWin32HandleKHR() const { return pfnGetMemWin32Handle_; }

    /// Cached vkGetMemoryWin32HandlePropertiesKHR function pointer (for import).
    PFN_vkGetMemoryWin32HandlePropertiesKHR getMemoryWin32HandlePropertiesKHR() const { return pfnGetMemWin32HandleProps_; }

private:
    VulkanDevice() = default;
    ~VulkanDevice();
    VulkanDevice(const VulkanDevice&) = delete;
    VulkanDevice& operator=(const VulkanDevice&) = delete;

    void createInstance();
    void pickPhysicalDevice();
    void createDevice();
    std::string decodeDriverVersion(uint32_t vendorId, uint32_t driverVersion) const;
    bool deviceSupportsExt(VkPhysicalDevice pd, const char* name) const;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = UINT32_MAX;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t computeQueueFamily_ = UINT32_MAX;
    VkQueue computeQueue_ = VK_NULL_HANDLE;
    std::string deviceName_;
    std::string driverVersion_;
    VkDebugUtilsMessengerEXT debugMessenger_ = VK_NULL_HANDLE;

    std::once_flag initFlag_;
    std::exception_ptr initException_;
    bool initialized_ = false;

    PFN_vkGetMemoryWin32HandleKHR pfnGetMemWin32Handle_ = nullptr;
    PFN_vkGetMemoryWin32HandlePropertiesKHR pfnGetMemWin32HandleProps_ = nullptr;
};

} // namespace fsr
