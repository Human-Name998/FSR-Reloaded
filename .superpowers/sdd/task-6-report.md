# Task 6: Build system + model download scripts

## Summary
Updated the build system to support NPU upscaling with ONNX Runtime integration.

## Changes

### CMakeLists.txt (`src/native/fsrvk/CMakeLists.txt`)
- Added `ONNXRUNTIME_DIR` cache variable (defaults to `third_party/onnxruntime`)
- Added `npu_device.cpp` and `npu_upscale.cpp` to the `fsrvk` shared library
- Added `target_include_directories` for ONNX Runtime headers (`${ONNXRUNTIME_DIR}/include`)
- Added `target_link_directories` for ONNX Runtime lib (`${ONNXRUNTIME_DIR}/lib`)
- Added `onnxruntime` to the Windows `target_link_libraries` list

### build.gradle
- Added ONNX Runtime DLLs (`onnxruntime.dll`, `onnxruntime_providers_dml.dll`) to the second `processResources` block
- Added NPU model file (`fsrcnn-x2-int8.onnx`) to the second `processResources` block

### New files
- `scripts/download_fsrcnn.py` — Downloads FSRCNN-x2 model from ONNX Model Zoo
- `scripts/quantize_fsrcnn.py` — Quantizes the model to INT8 for NPU inference

## Verification
- `./gradlew.bat build` completed successfully (BUILD SUCCESSFUL in 14s)

## Commit
`4c22394` — `npu: build system + model download scripts`
