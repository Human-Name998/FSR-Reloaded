#pragma once
#include "ffx_backend.h"

FfxBackendInterface ffxGetInterfaceVulkanVk(void* instance, void* physDevice, void* device, void* getProcAddr);
FfxResource ffxGetResourceVulkan(void* image, void* view, uint64_t state, const wchar_t* name);
