# Task 8 Report: Wire MV Generation to Shared Texture

## Status: ✅ Complete

## Changes

### 1. `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`
- Added `MV_GEN_CS` compute shader source (same as FSR2/3 pipelines) for MV generation
- Added fields: `mvGenProg`, `mvGenCompiled`, uniform locations, `mvClearFbo`, `mvMatBuf`, `mvTmpMat`
- Added `ensureMvGenShader()` — compiles and caches the MV gen shader
- Added `generateFSR4MotionVectors()` — computes VP matrices from camera, runs MV gen dispatch with output to the shared MV texture, saves prev VP for next frame
- Called `generateFSR4MotionVectors()` in the FSR4 path (before `gpuSyncMgr.flushGL()`) so motion vectors are available to the native FSR4 dispatch

### 2. `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`
- Changed the MV texture (index 2) internal format from `GL_RGBA8` to `GL_RG16F` to match the MV shader's `rg16f` image format

### 3. `src/native/fsrvk/fsr4_shared_resources.cpp`
- Changed MV texture `VkImageCreateInfo.format` from `VK_FORMAT_R8G8B8A8_UNORM` to `VK_FORMAT_R16G16_SFLOAT`
- Changed MV texture `VkImageViewCreateInfo.format` to match

## Design Notes
- The native FSR4 dispatch (`Fsr4Context::dispatch()`) currently doesn't populate `FfxFsr3UpscaleRenderDesc` with the passed-in resources, so MV data isn't consumed yet — this change ensures it will be available when plumbed.
- Format consistency: GL_RG16F = VK_FORMAT_R16G16_SFLOAT = 2×16-bit half-float = 4 bytes/pixel (same allocation size as RGBA8).
- First-frame MV are computed with identity prevVP (produces non-zero MVs but acceptable for one-frame initialization).
- The `generateFSR4MotionVectors` method manages `prevViewProj`/`hasPrevFrame` lifecycle independently from the FSR2/3 paths.

## Commits
- `e69088f` — "Phase 3 Task 8: Route MV generation to shared texture for FSR4"
  - 3 files changed, 126 insertions(+), 4 deletions(-)

## Test Summary
- `gradlew.bat build` — PASS (5s)
