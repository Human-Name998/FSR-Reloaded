# FSR3 Frame Generation Pipeline Design

> Official SDK: `GPUOpen-LibrariesAndSDKs/FidelityFX-SDK@main`, FSR 3.1.6 Frame Generation
> Source: `Kits/FidelityFX/framegeneration/fsr3/`

## Goal

Port AMD's FSR3 Frame Generation pipeline from HLSL to GLSL compute shaders, integrated into the existing FSR3 upscaling pipeline for double-framerate presentation via early-swap injection.

## Design Decisions

- **Skip optical flow pipeline** (7 shaders) — Minecraft provides game motion vectors directly via depth-buffer reprojection, eliminating the need for GPU optical flow estimation
- **Skip inpainting pipeline** (3 passes) — v1 falls back to current-frame color for disoccluded pixels instead of full pyramid-based inpainting
- **Inline GLSL strings** — matches existing mod pattern (no external shader files)
- **6 compute passes** — core FG pipeline adapted from AMD's 11-pass design

## Pipeline Passes (6 compute shaders)

```
┌──────────────────────────────────────────────────────────────────┐
│ ① SETUP ── reset counters, clear MV fields, clear disocclusion │
│ ② RECONSTRUCT_AND_DILATE ── nearest depth, dilate MVs, reproj  │
│ ③ RECONSTRUCT_PREV_DEPTH ── reproject depth to interp frame    │
│ ④ GAME_MOTION_VECTOR_FIELD ── pack MV field w/priority         │
│ ⑤ DISOCCLUSION_MASK ── depth clip → bi-directional mask        │
│ ⑥ INTERPOLATION ── forward/backward warp + blend               │
└──────────────────────────────────────────────────────────────────┘
```

### Data Flow

```
depth + MVs (render res)
  → dilated depth + dilated MVs
    → packed game MV field (R32UI, priority-encoded)
      → reconstructed previous depth + interpolated depth
        → bi-directional disocclusion mask (R8G8)
          → interpolated frame (RGBA16F, display res) + inpainting mask (R8)
```

### Pass Details

#### ① SETUP (`8×8` threads, 1×1 dispatch)
- Reset frame counter and SPD counter
- Zero out game MV field X/Y, optical flow MV field X/Y (skipped), disocclusion mask
- Resources: `rw_game_motion_vector_field_x/y` (W), `rw_disocclusion_mask` (W), `rw_counters` (W)

#### ② RECONSTRUCT_AND_DILATE (`8×8` threads, render-size dispatch)
- Find nearest depth in 3×3 neighborhood (9-tap)
- Store dilated depth and motion vector at each pixel
- Reproject current depth to previous frame using half-MV motion vector (bilinear splat)
- Resources: `r_input_depth` (R), `r_input_motion_vectors` (R), `rw_dilated_depth` (W), `rw_dilated_motion_vectors` (W), `rw_reconstructed_depth_previous_frame` (W)

#### ③ RECONSTRUCT_PREV_DEPTH (`8×8` threads, render-size dispatch)
- Read dilated depth and MVs
- Reproject GAME MV * 0.5 toward interpolated frame position
- Bilinear splat to `rw_reconstructed_depth_interpolated_frame`
- Resources: `r_dilated_depth` (R), `r_dilated_motion_vectors` (R), `rw_reconstructed_depth_interpolated_frame` (W)

#### ④ GAME_MOTION_VECTOR_FIELD (`8×8` threads, render-size dispatch)
- Read dilated MVs, apply distortion field (identity/zero for Minecraft)
- Pack MV vector into 16-bit half-float + priority into high bits
- Primary vector: bilinear splat to 4 nearest pixels
- Secondary vectors: step along MV direction, reverse depth priority
- Fields stored as R32UI with bit-packed format:
  - Bits 0–15: F16 motion vector component
  - Bits 16–20: Low priority (5 bits)
  - Bits 21–30: High priority (10 bits)
  - Bit 31: Primary/secondary flag
- Resources: `r_dilated_motion_vectors` (R), `r_dilated_depth` (R), `r_input_depth` (R), `rw_game_motion_vector_field_x/y` (W), `r_input_motion_vectors` (R)

#### ⑤ DISOCCLUSION_MASK (`8×8` threads, render-size dispatch)
- For each pixel, compute depth clip factor against previous + current frame
- Uses Ksep triangle separation formula for depth-based disocclusion
- Inpainted MV field read for reprojection
- Output: R8G8 — X=disoccluded from previous, Y=disoccluded from current
- Resources: `r_reconstructed_depth_interpolated_frame` (R), `r_reconstructed_depth_previous_frame` (R), `r_dilated_depth` (R), `rw_disocclusion_mask` (W), `r_game_motion_vector_field_x/y` (R)

#### ⑥ INTERPOLATION (`8×8` threads, display-size dispatch)
- **Forward warp**: sample previous frame at `uv + gameMv`
- **Backward warp**: sample current frame at `uv - gameMv`
- Blend weight `t` adjusts based on bi-directional disocclusion state:
  - `t = 0.5 + 0.5*(1 - disocclusionToPrev) - 0.5*(1 - disocclusionToCurr)`
  - Fallback to single direction if bilinear sum is zero
- Inpainting mask output (R8): weight for future inpainting pass (always 1.0 for v1 fallback)
- Resources: `r_game_motion_vector_field_x/y` (R), `r_disocclusion_mask` (R), `r_current_interpolation_source` (R), `r_previous_interpolation_source` (R), `rw_output` (W), `rw_inpainting_mask` (W)

## Resource Model

### Internal Textures (created in FSR3Pipeline)

| Name | Format | Size | Access | Aliased |
|------|--------|------|--------|---------|
| `reconstructedDepthInterpolatedFrame` | R32UI | renderSize | UAV | Yes |
| `gameMvFieldX` | R32UI | renderSize | UAV | Yes |
| `gameMvFieldY` | R32UI | renderSize | UAV | Yes |
| `inpaintingPyramid` | RGBA16F | displaySize/2 | UAV | Yes |
| `counterBuffer` | R32UI buffer | 8 elements | UAV | No |
| `opticalFlowMvFieldX` | R32UI | renderSize | UAV | Yes (unused, placeholder) |
| `opticalFlowMvFieldY` | R32UI | renderSize | UAV | Yes (unused, placeholder) |
| `previousInterpolationSource` | RGBA16F | displaySize | UAV | No |
| `inpaintingMask` | R8_UNORM | displaySize | UAV | Yes |
| `disocclusionMask` | R8G8_UNORM | renderSize | UAV | Yes |
| `distortionField` | R8G8_UNORM | 1×1 | SRV | No (default zeros) |

### Shared Textures (from FSRProcessor / existing pipeline)

| Name | Format | Size | Access |
|------|--------|------|--------|
| `dilatedDepth` | R32F | renderSize | SRV/UAV |
| `dilatedMotionVectors` | RG16F | renderSize | SRV/UAV |
| `reconstructedPrevNearestDepth` | R32UI | renderSize | SRV/UAV |
| `currentInterpolationSource` (realTex) | RGBA16F | displaySize | SRV |
| `previousInterpolationSource` (prevColorTex) | RGBA16F | displaySize | SRV |

### Constant Buffer: `FrameInterpolationConstants`

```
ivec2 uRenderSize          — render resolution
ivec2 uDisplaySize         — display resolution  
ivec2 uInterpolationRectBase  — always (0,0) for full-screen
ivec2 uInterpolationRectSize  — displaySize
vec2  uJitterOffset        — current frame jitter
uint  uFrameIndex          — frame counter
uint  uReset               — reset flag
bool  uInvertedDepth       — always true (Minecraft uses reversed-Z)
vec4  uDeviceToViewDepth   — projection transform factors
float uTanHalfFovY         — tan(half FOV Y)
```

### Samplers
- Point clamp (binding 0) — for depth/MV data
- Linear clamp (binding 1) — for color interpolation

## Constant Buffer: `InpaintingPyramidConstants`

```
uint  uMipLevel            — current pyramid level
ivec2 uTopLevelSize        — base level size
bool  uIsAdditive          — additive vs. max reduction
```

## Integration with Early-Swap FG

### Execution Order (in `FSRProcessor.onRenderEnd()`)

```
1. FSR3 Upscale scene → realTex (existing)
2. Update prevColorTex = realTex (from previous frame)
3. Bind internals: dilatedDepthTex, dilatedMotionVectorTex
4. Dispatch FG pipeline (6 passes with barriers between)
5. Early swap: blit fgOutput → FBO 0 → glfwSwapBuffers()
6. Copy realTex → mainRT for GUI
7. Minecraft renders GUI on mainRT
8. Game swap → real frame with GUI visible
```

### Frame Counter Management
- `uFrameIndex` incremented each FG dispatch
- `uReset` set to 1 on first frame after enable/resize
- Counter stored in `counterBuffer` (buffer R32UI, atomic operations not needed — single write per dispatch)

### Wait for vsync
- With vsync ON, intermediate swap may block — only visible if driver presents both
- Recommendation: disable vsync for true 2× FPS

## HLSL → GLSL Translation Notes

### Type Mapping

| AMD HLSL | GLSL |
|----------|------|
| `FfxFloat32x2/3/4` | `vec2/3/4` |
| `FfxInt32x2/3/4` | `ivec2/3/4` |
| `FfxUInt32` | `uint` |
| `FfxUInt32x2` | `uvec2` |
| `FfxBoolean` | `bool` |
| `SV_DispatchThreadID` | `gl_GlobalInvocationID` |
| `[numthreads(8,8,1)]` | `layout(local_size_x=8, local_size_y=8) in;` |

### Resource Binding

AMD HLSL resource declarations → GLSL `layout(binding=N, ...)`:

| HLSL | GLSL |
|------|------|
| `RWTexture2D<uint>` | `layout(binding=N, r32ui) uniform uimage2D` |
| `Texture2D<float4>` | `layout(binding=N) uniform sampler2D` |
| `RWTexture2D<float2>` | `layout(binding=N, rg16f) uniform image2D` |
| `RWTexture2D<float>` | `layout(binding=N, r32f) uniform image2D` |
| `RWTexture2D<unorm float2>` | `layout(binding=N, rg8) uniform image2D` |
| `RWByteAddressBuffer` | `layout(binding=N, r32ui) uniform uimageBuffer` |

### Key HLSL Intrinsics → GLSL
- `ffxSaturate` → `clamp(x, 0.0, 1.0)`
- `ffxPow` → `pow`
- `ffxLerp` → `mix`
- `ffxMin/Max` → `min/max`
- `ffxAbs` → `abs`
- `ffxF32ToF16` → `packHalf2x16(vec2(x, 0.0))`
- `ffxUnpackF32` → `unpackHalf2x16(v).x`
- `FFX_GREATER_THAN_EQUAL` → `greaterThanEqual`
- `FFX_EQUAL` → `equal`
- `FFX_LESS_THAN` → `lessThan`

### Adaptive Callbacks (removed from GLSL)

AMD's SDK uses callback macros (`LoadDilatedDepth()`, `StoreGameMotionVectorFieldX()`, etc.) mapped to specific SRV/UAV bindings via the C++ resource binding table. In our GLSL port, these become direct `imageLoad`/`imageStore` calls with fixed binding indices.

### Disocclusion Adaptive Changes

AMD's `FFX_FRAMEINTERPOLATION_OPTION_INVERTED_DEPTH` compile-time switch → use `#define INVERTED_DEPTH 1` (Minecraft uses reversed-Z by default).

## v1.0 Simplifications (vs. AMD SDK)

1. **No optical flow pipeline** — game MVs substitute entirely
2. **No inpainting pyramid** — disoccluded pixels fall back to current-frame color
3. **No inpainting pass** — inpainting mask written but unused in v1
4. **No debug view** pass
5. **No distortion field** — identity mapping (1×1 zero texture)
6. **Single constant buffer** — FrameInterpolationConstants only (no InpaintingPyramidConstants needed)
7. **Simplified secondary MV writes** — single step (AMD uses adaptive step loop)
8. **No SPD (single-pass downsampler)** — inpainting pyramid skipped

## Textures Removed from AMD SDK

vs. AMD's internal resource list:
- `opticalFlowVector` (no optical flow)
- `opticalFlowConfidence` (no optical flow)
- `opticalFlowGlobalMotion` (no optical flow)
- `opticalFlowSceneChangeDetection` (no optical flow)
- `inpaintingPyramidMipmap_0..12` (13 mip levels — skipped for v1)

Total texture count: 11 internal + 3 shared = 14 textures (vs. AMD's ~28)

## Error Handling

- **Pipeline not initialized**: `hasPreviousFrame` returns false → skip FG dispatch, use real frame only
- **Texture resize**: `onResize` → `resetFrameGen()`, rebuild textures
- **GL errors**: wrap texture creation with `glGetError` checks (existing pattern)
- **GPU hang prevention**: max MV magnitude clamped to `length(mv) < max(uDisplaySize)` in interpolation pass
