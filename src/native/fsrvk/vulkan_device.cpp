#include "vulkan_device.h"
#include <algorithm>
#include <cassert>
#include <cstring>
#include <stdexcept>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

namespace fsr {

namespace {

constexpr const char* INSTANCE_EXTENSIONS[] = {
    "VK_KHR_external_memory_capabilities",
    "VK_KHR_external_semaphore_capabilities",
};

constexpr const char* DEVICE_EXTENSIONS_BASE[] = {
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
    "VK_KHR_external_memory_win32",
    "VK_KHR_external_semaphore_win32",
};

// FSR4 compute requires cooperative matrix (WMMA on RDNA 3.5)
constexpr const char* DEVICE_EXTENSIONS_FSR4[] = {
    VK_KHR_COOPERATIVE_MATRIX_EXTENSION_NAME,
};

VKAPI_ATTR VkBool32 VKAPI_CALL debugCallback(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT /*type*/,
    const VkDebugUtilsMessengerCallbackDataEXT* data,
    void* /*userData*/)
{
    if (severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
        fprintf(stderr, "[VK] %s\n", data->pMessage);
    }
    return VK_FALSE;
}

/// Check if a layer name is in the enumerated list
static bool hasLayer(const std::vector<VkLayerProperties>& layers, const char* name) {
    for (const auto& l : layers) {
        if (std::strcmp(l.layerName, name) == 0) return true;
    }
    return false;
}

} // anonymous namespace

// ----------------------------------------------------------------
VulkanDevice& VulkanDevice::get() {
    static VulkanDevice instance;
    return instance;
}

VulkanDevice::~VulkanDevice() {
    // Best-effort cleanup during static destruction. If terminate() was already
    // called by Java, handles are already VK_NULL_HANDLE and this is a no-op.
    // If Java crashed without calling terminate(), this prevents resource leaks.
    // We do NOT call destroy() because vkDeviceWaitIdle may hang if the driver
    // is partially torn down during JVM crash. Instead, destroy handles directly
    // without waiting (process exit will reclaim GPU resources anyway).
    if (device_ != VK_NULL_HANDLE) {
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (debugMessenger_ != VK_NULL_HANDLE) {
        auto vkDestroyDebugUtilsMessengerEXT =
            reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance_, "vkDestroyDebugUtilsMessengerEXT"));
        if (vkDestroyDebugUtilsMessengerEXT) {
            vkDestroyDebugUtilsMessengerEXT(instance_, debugMessenger_, nullptr);
        }
        debugMessenger_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
}

void VulkanDevice::ensureInitialized() {
    std::call_once(initFlag_, [this]() {
        // Wrap in SEH to catch delay-load failures of vulkan-1.dll.
        // With delay-load enabled, if vulkan-1.dll is not found at runtime,
        // the first call to any Vulkan function raises an SEH exception
        // (0xC06D007E = ERROR_MOD_NOT_FOUND).  Without SEH, this would
        // crash the JVM.  We catch it here and silently fail instead;
        // D3D12-only paths (FSR4) never call ensureInitialized().
        __try {
            createInstance();
            pickPhysicalDevice();
            createDevice();
            initialized_ = true;
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            destroy();
            // Delay-load failure or any other SEH during init —
            // leave initialized_ = false so callers get a clear
            // "Vulkan not available" response.
        }
    });
    if (initException_) {
        std::rethrow_exception(initException_);
    }
}

void VulkanDevice::destroy() {
    if (device_ != VK_NULL_HANDLE) {
        // Wait for all pending work before destroying — Vulkan spec requires
        // all queues to be idle before device destruction.
        vkDeviceWaitIdle(device_);
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (debugMessenger_ != VK_NULL_HANDLE) {
        auto vkDestroyDebugUtilsMessengerEXT =
            reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance_, "vkDestroyDebugUtilsMessengerEXT"));
        if (vkDestroyDebugUtilsMessengerEXT) {
            vkDestroyDebugUtilsMessengerEXT(instance_, debugMessenger_, nullptr);
        }
        debugMessenger_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    initialized_ = false;
    pfnGetMemWin32Handle_ = nullptr;
    pfnGetMemWin32HandleProps_ = nullptr;
}

// ----------------------------------------------------------------
void VulkanDevice::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "FSR4 Phase 1";
    appInfo.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    // Enumerate available layers for conditional validation
    uint32_t layerCount = 0;
    vkEnumerateInstanceLayerProperties(&layerCount, nullptr);
    std::vector<VkLayerProperties> availableLayers(layerCount);
    vkEnumerateInstanceLayerProperties(&layerCount, availableLayers.data());

    bool hasValidation = hasLayer(availableLayers, "VK_LAYER_KHRONOS_validation");
    const char* enabledLayers[1] = {};
    uint32_t enabledLayerCount = 0;
    if (hasValidation) {
        enabledLayers[0] = "VK_LAYER_KHRONOS_validation";
        enabledLayerCount = 1;
    }

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledLayerCount = enabledLayerCount;
    createInfo.ppEnabledLayerNames = enabledLayerCount ? enabledLayers : nullptr;
    createInfo.enabledExtensionCount = 2;
    createInfo.ppEnabledExtensionNames = INSTANCE_EXTENSIONS;

    VkDebugUtilsMessengerCreateInfoEXT debugInfo{};
    debugInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
    debugInfo.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                              | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    debugInfo.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                          | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                          | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debugInfo.pfnUserCallback = debugCallback;

    // Check if VK_EXT_debug_utils is available; attach to instance if so
    uint32_t extCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> availableExts(extCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, availableExts.data());

    bool hasDebugUtils = false;
    for (const auto& ext : availableExts) {
        if (std::strcmp(ext.extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0) {
            hasDebugUtils = true;
            break;
        }
    }

    const char* extList[3] = {
        "VK_KHR_external_memory_capabilities",
        "VK_KHR_external_semaphore_capabilities",
        VK_EXT_DEBUG_UTILS_EXTENSION_NAME,
    };
    VkDebugUtilsMessengerCreateInfoEXT* pDebugInfo = nullptr;
    if (hasDebugUtils) {
        createInfo.enabledExtensionCount = 3;
        createInfo.ppEnabledExtensionNames = extList;
        pDebugInfo = &debugInfo;
    }

    createInfo.pNext = pDebugInfo;

    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        throw std::runtime_error("vkCreateInstance failed: " + std::to_string(result));
    }

    if (pDebugInfo) {
        auto vkCreateDebugUtilsMessengerEXT =
            reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance_, "vkCreateDebugUtilsMessengerEXT"));
        if (vkCreateDebugUtilsMessengerEXT) {
            vkCreateDebugUtilsMessengerEXT(instance_, &debugInfo, nullptr, &debugMessenger_);
        }
    }
}

// ----------------------------------------------------------------
void VulkanDevice::pickPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance_, &count, nullptr);
    if (count == 0) throw std::runtime_error("No Vulkan devices found");

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());

    // First pass: prefer AMD (0x1002) for FSR4, fall back to any compatible
    VkPhysicalDevice fallback = VK_NULL_HANDLE;
    uint32_t fallbackQueueFamily = UINT32_MAX;

    for (const auto& pd : devices) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(pd, &props);

        if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)) continue;
        if (!deviceSupportsExt(pd, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME)) continue;
        if (!deviceSupportsExt(pd, "VK_KHR_external_memory_win32")) continue;
        if (!deviceSupportsExt(pd, "VK_KHR_external_semaphore_win32")) continue;

        // Find a compute queue family.
        // NOTE: do NOT require queueCount >= 2. AMD APU drivers
        // (Radeon 780M / 860M / Strix Point iGPUs) typically
        // expose only queueCount=1 per family. The previous
        // queueCount >= 2 check would silently skip the AMD
        // adapter on these APUs and fall back to nothing.
        uint32_t qfCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qfCount);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qProps.data());

        uint32_t qf = UINT32_MAX;
        for (uint32_t q = 0; q < qfCount; q++) {
            if (qProps[q].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                qf = q;
                break;
            }
        }
        if (qf == UINT32_MAX) continue;

        // If this is an AMD GPU, prefer it immediately
        if (props.vendorID == 0x1002) {
            physDevice_ = pd;
            queueFamily_ = qf;
            deviceName_ = props.deviceName;
            driverVersion_ = decodeDriverVersion(props.vendorID, props.driverVersion);
            return;
        }

        // Otherwise save as fallback
        if (fallback == VK_NULL_HANDLE) {
            fallback = pd;
            fallbackQueueFamily = qf;
            deviceName_ = props.deviceName;
            driverVersion_ = decodeDriverVersion(props.vendorID, props.driverVersion);
        }
    }

    if (fallback != VK_NULL_HANDLE) {
        physDevice_ = fallback;
        queueFamily_ = fallbackQueueFamily;
        return;
    }

    throw std::runtime_error("No Vulkan device with required extensions + compute queue");
}

// ----------------------------------------------------------------
void VulkanDevice::createDevice() {
    // Probe actual queueCount for the picked family. AMD APU
    // drivers commonly expose only 1 queue per family; requesting
    // 2 would fail vkCreateDevice. Use min(2, family.queueCount).
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physDevice_, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(physDevice_, &qfCount, qProps.data());

    uint32_t requestedQueues = 1;
    if (queueFamily_ < qfCount && qProps[queueFamily_].queueCount >= 2) {
        requestedQueues = 2;
    }
    float priorities[2] = {1.0f, 1.0f};
    VkDeviceQueueCreateInfo qci[1] = {};
    qci[0].sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci[0].queueFamilyIndex = queueFamily_;
    qci[0].queueCount = requestedQueues;
    qci[0].pQueuePriorities = priorities;

    // Gather all required device extensions (base + FSR4)
    std::vector<const char*> allExts;
    for (auto* ext : DEVICE_EXTENSIONS_BASE) allExts.push_back(ext);
    // Conditionally add FSR4 extensions if supported
    if (deviceSupportsExt(physDevice_, VK_KHR_COOPERATIVE_MATRIX_EXTENSION_NAME)) {
        allExts.push_back(VK_KHR_COOPERATIVE_MATRIX_EXTENSION_NAME);
    }

    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = qci;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(allExts.size());
    createInfo.ppEnabledExtensionNames = allExts.data();

    VkResult result = vkCreateDevice(physDevice_, &createInfo, nullptr, &device_);
    if (result != VK_SUCCESS) {
        throw std::runtime_error("vkCreateDevice failed: " + std::to_string(result));
    }

    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    if (requestedQueues >= 2) {
        vkGetDeviceQueue(device_, queueFamily_, 1, &computeQueue_);
    } else {
        // Reuse the same queue — fine for serial dispatch usage.
        computeQueue_ = queue_;
    }
    computeQueueFamily_ = queueFamily_;

    // Cache function pointers for memory ops (export + import)
    pfnGetMemWin32Handle_ = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(
        vkGetDeviceProcAddr(device_, "vkGetMemoryWin32HandleKHR"));
    pfnGetMemWin32HandleProps_ = reinterpret_cast<PFN_vkGetMemoryWin32HandlePropertiesKHR>(
        vkGetDeviceProcAddr(device_, "vkGetMemoryWin32HandlePropertiesKHR"));
}

// ----------------------------------------------------------------
std::string VulkanDevice::decodeDriverVersion(uint32_t vendorId, uint32_t driverVersion) const {
    if (vendorId == 0x1002) { // AMD
        uint32_t major = driverVersion >> 22;
        uint32_t minor = (driverVersion >> 12) & 0x3FF;
        uint32_t patch = driverVersion & 0xFFF;
        return std::to_string(major) + "." + std::to_string(minor) + "." + std::to_string(patch);
    }
    // Standard Vulkan format (NVIDIA, Intel, etc.)
    uint32_t major = VK_API_VERSION_MAJOR(driverVersion);
    uint32_t minor = VK_API_VERSION_MINOR(driverVersion);
    uint32_t patch = VK_API_VERSION_PATCH(driverVersion);
    return std::to_string(major) + "." + std::to_string(minor) + "." + std::to_string(patch);
}

// ----------------------------------------------------------------
bool VulkanDevice::deviceSupportsExt(VkPhysicalDevice pd, const char* name) const {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> props(count);
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &count, props.data());
    for (const auto& p : props) {
        if (std::strcmp(p.extensionName, name) == 0) return true;
    }
    return false;
}

} // namespace fsr