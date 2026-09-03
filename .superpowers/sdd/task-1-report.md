# Task 1 Report: NPU adapter detection + D3D12 device

## Status: DONE

## Files Created
- `src/native/fsrvk/npu_device.h` — Header declaring `npuInit`, `npuShutdown`, `npuOpenSharedTexture`, `npuIsAvailable`
- `src/native/fsrvk/npu_device.cpp` — Implementation with DXGI adapter enumeration, MCDM D3D12 device creation, DirectML device creation, and shared texture opening

## Commit
- Hash: `0c728e9`
- Message: `npu: NPU adapter detection + D3D12 device (MCDM)`

## Implementation Details
- `npuInit` enumerates DXGI adapters, skips the GPU adapter (by LUID match), software adapters, and null-description adapters. For each remaining candidate, it attempts `D3D12CreateDevice` with `D3D_FEATURE_LEVEL_1_0_CORE` (MCDM) and then `DMLCreateDevice`. The first successful combination is cached and returned.
- `npuShutdown` releases the NPU D3D12 device, DirectML device, and resets cached state.
- `npuOpenSharedTexture` opens a shared Win32 handle on the NPU D3D12 device via `OpenSharedHandle`.
- File-scoped statics (`g_npuDevice`, `g_dmlDevice`, `g_npuAttempted`, `g_npuAvailable`) manage cached state.

## Self-Review
- Content matches the brief exactly — no deviations from the specified code.
- The header introduces `using Microsoft::WRL::ComPtr;` at global scope, which is the only namespace-level declaration outside the existing `namespace fsr` convention. This is consistent with the brief's specification.
- No modifications were made to CMakeLists.txt or any other files (CMake updates are deferred to Task 6).
- No issues encountered during file creation.
- CRLF warnings from Git are expected on Windows and do not affect correctness.

## Fixes Applied

Three fixes were applied to `src/native/fsrvk/npu_device.cpp` on branch `experimental/npu` (commit `cc92add`):

1. **Replace `ID3D12Device::GetDesc` with `GetAdapterLuid()` (compile fix):** `ID3D12Device` does not have `GetDesc`; `DXGI_ADAPTER_DESC` was replaced with a `LUID` via `GetAdapterLuid()`. The LUID comparison in the adapter enumeration loop now references `gpuLuid` instead of `gpuDesc.AdapterLuid`.

2. **Null safety for `npuInit()`:** Added null pointer checks for `gpuDevice`, `outNpuDevice`, and `outDmlDevice` at the start of `npuInit()`, printing an error and returning `false` if any is null.

3. **Null safety for `npuOpenSharedTexture()`:** Already present — checks `!npuDevice || !sharedHandle` and returns `false` if either is null.
