#pragma once
#include <d3d12.h>
#include <DirectML.h>
#include <onnxruntime_cxx_api.h>
#include <dml_provider_factory.h>

// Load ONNX model and create ORT session bound to NPU DirectML device.
// modelPath: path to embedded ONNX file in JAR natives/ directory.
// Must be called once after npuInit() succeeds.
bool npuUpscaleInit(ID3D12Device* npuDevice, IDMLDevice* dmlDevice, const char* modelPath);

// Per-frame upscale dispatch. Runs FSRCNN on the NPU.
// inputColor: D3D12 texture opened on NPU device (pool color, low res RGBA8)
// outputColor: D3D12 texture opened on NPU device (pool output, native res RGBA8)
// width, height: input (scaled) dimensions.
// Returns false if dispatch fails.
bool npuUpscaleDispatch(ID3D12Resource* inputColor, ID3D12Resource* outputColor,
    int width, int height);

// Release all ORT session resources.
void npuUpscaleShutdown();

// Returns true if upscale session is ready for dispatch.
bool npuUpscaleIsReady();
