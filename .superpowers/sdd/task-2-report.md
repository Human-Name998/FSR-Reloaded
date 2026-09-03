# Task 2 Report: ONNX Runtime integration + model dispatch

## Files Created

- `src/native/fsrvk/npu_upscale.h` — Header declaring:
  - `npuUpscaleInit()` — Load ONNX model and create ORT session with DirectML EP
  - `npuUpscaleDispatch()` — Per-frame FSRCNN inference
  - `npuUpscaleShutdown()` — Release ORT session resources
  - `npuUpscaleIsReady()` — Query if session is initialized

- `src/native/fsrvk/npu_upscale.cpp` — Implementation:
  - Static globals for ORT session, environment, memory info
  - Init: Creates ORT env, configures DirectML EP on NPU device, loads model from file
  - Dispatch: Uses CPU staging tensors with DML EP managing copies (POC approach). Zeros placeholder for texture read/write — actual ReadSubresource/WriteSubresource deferred to follow-up optimization.
  - Shutdown: Cleanly releases session, env, memory info

## Design Decisions

- CPU tensors with DML EP managing device copies (as specified in brief) — avoids early complexity of D3D12 tensor binding
- Input texture filled with zeros for POC; actual D3D12 texture readback deferred
- FSRCNN-x2 input/output shapes: (N, 4, H, W) → (N, 4, 2H, 2W)
- Error handling via Ort::Exception catch blocks

## Commit

`npu: ONNX Runtime session + FSRCNN dispatch skeleton`

## Fixes Applied (Jul 11 2026)

### Fix 1: Missing include (`npu_upscale.h`)
Added `#include <dml_provider_factory.h>` after `#include <onnxruntime_cxx_api.h>`. This header is required for the
`OrtSessionOptionsAppendExecutionProvider_DML()` call used in `npuUpscaleInit()`.

### Fix 2: Oversized vector element counts (`npu_upscale.cpp`)
The `inputSize` and `outputSize` variables held byte counts (Width * Height * 4), but were used as the element count
for `std::vector<float>`. Each float is 4 bytes, so the vectors were 4x too large. Fixed by dividing by `sizeof(float)`.

**Commit:** `51c93a4` — `npu: fix missing include + vector sizing`
