#pragma once
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl.h>
#include <DirectML.h>

using Microsoft::WRL::ComPtr;

// Initialize NPU device subsystem. Finds the NPU MCDM adapter and creates
// D3D12 + DirectML devices on it. Must be called after GPU D3D12 device exists.
bool npuInit(ID3D12Device* gpuDevice, ID3D12Device** outNpuDevice, IDMLDevice** outDmlDevice);

// Shutdown: release NPU D3D12 device, DirectML device, and any cached resources.
void npuShutdown();

// Open a shared D3D12 resource (created on GPU device) on the NPU device.
// handle: Win32 shared handle from GPU device's CreateSharedHandle.
bool npuOpenSharedTexture(ID3D12Device* npuDevice, HANDLE sharedHandle,
    ID3D12Resource** outResource);

// Check if NPU initialization has been attempted and succeeded.
bool npuIsAvailable();
