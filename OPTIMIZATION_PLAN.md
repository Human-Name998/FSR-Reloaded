# FSR Pipeline Optimization Plan v2

**Goal:** Reduce FSR2/FSR3 GPU time by 25-40% through barrier narrowing, work group tuning, shared memory, UBOs, and FG pipeline improvements.

## Summary of All Tasks

| Task | Description | Status | GPU Impact | CPU Impact | Risk |
|------|-------------|--------|-----------|-----------|------|
| 1 | Texture format downgrade (R11G11B10F) | ✅ Done | -50% BW | — | — |
| 2 | Narrow memory barrier bits | ❌ Pending | 5-10% | — | Low |
| 3 | Cache FBO in Iris upscale path | ✅ Done | — | — | — |
| 4 | RCAS barrier removal | ❌ Pending | 2-3% | — | Low |
| 5 | Uniform Buffer Object packing | ❌ Pending | — | 5-8% | Medium |
| 6 | Work group tuning (32x1 → 16x4) | ❌ Pending | 10-20% | — | Low |
| 7 | Shared memory for 3x3 sampling | ❌ Pending | 15-25% | — | Medium |
| 8 | FG pipeline SETUP merge + fix dispatch | ❌ Pending | 10% FG | — | Medium |
| 9 | Persistent texture bindings | ❌ Pending | — | 3-5% | Low |

---

## Task 2: Narrow Memory Barrier Bits

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java`

**Analysis of each barrier:**

| Transition | Current | Next Pass Reads Via | Correct |
|-----------|---------|-------------------|---------|
| RECONSTRUCT → DEPTH_CLIP | `TEXTURE_FETCH \| IMAGE_ACCESS` | `texelFetch` (sampler) + `usampler2D` on reconPrevDepth | `TEXTURE_FETCH` only |
| DEPTH_CLIP → LOCK | `TEXTURE_FETCH \| IMAGE_ACCESS` | `texelFetch` (sampler) on dilatedData | `TEXTURE_FETCH` only |
| ACCUMULATE → RCAS | `SHADER_IMAGE_ACCESS` | `imageLoad` | Keep (Task 4 removes entirely) |

**Changes:**
1. **FSR2Pipeline** — Two barriers: after RECONSTRUCT and after DEPTH_CLIP. Change `TEXTURE_FETCH | IMAGE_ACCESS` to just `TEXTURE_FETCH`
2. **FSR3Pipeline** — Same two barriers in the same positions

---

## Task 4: RCAS Barrier Removal

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java`

**What:** Remove `glMemoryBarrier(TEXTURE_FETCH)` after RCAS dispatch. The ACCUMULATE → RCAS barrier (currently `SHADER_IMAGE_ACCESS`) is also unnecessary — serial `glDispatchCompute` calls guarantee ordering on all GL 4.3+ drivers.

**What to keep:** The barrier at the very end (before `glCopyImageSubData` copies the output to mainRT) must stay — it ensures the texture copy sees completed RCAS writes.

**Changes:**
1. In both pipelines, remove the `glMemoryBarrier` call that appears after `glDispatchCompute` inside the RCAS `if (rcasSharpness > 0)` block, before `glBindImageTexture(0, 0, ...)` unbinding

---

## Task 5: Uniform Buffer Object (UBO) Packing

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java`, GLSL shaders

**Current problem:** `setCommonUniforms()` makes ~20 individual `glUniform` calls per pass, called 4-5 times per frame.

**Solution:** Pack all common uniforms into a single UBO at `binding = 15`, upload once per frame with `glBufferSubData`.

**UBO layout (std140, 112 bytes = 7 vec4s):**
```glsl
layout(std140, binding=15) uniform FSRCommonUBO {
    ivec2 uRenderSize;
    ivec2 uDisplaySize;
    ivec2 uMaxRenderSize;
    vec4 uDeviceToViewDepth;
    vec2 uJitter;
    vec2 uMotionVectorScale;
    vec2 uDownscaleFactor;
    vec2 uMotionVectorJitterCancellation;
    float uPreExposure;
    float uPreviousFramePreExposure;
    float uViewSpaceToMetersFactor;
    float uExposureVal;
    float uFarDepth;
    float uFarViewZ;
    int uFrameIndex;
};
```

**Changes:**
1. Add `commonUbo` field + `ByteBuffer` to both pipelines
2. Create UBO in `ensureShaders()` with `glGenBuffers` + `glBufferData(DYNAMIC_DRAW)`
3. Replace `setCommonUniforms()` with `uploadCommonUbo()` that packs a ByteBuffer and calls `glBufferSubData`
4. Add `layout(std140, binding=15) uniform FSRCommonUBO { ... };` to RECONSTRUCT_CS, DEPTH_CLIP_CS, LOCK_CS, ACCUMULATE_CS
5. Remove individual uniform declarations from those shaders
6. Remove all per-pass `glUniform` calls for common uniforms
7. Clean up in `destroy()`

---

## Task 6: Work Group Tuning (32x1 → 16x4)

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java` (GLSL + Java)

**Current problem:** ACCUMULATE and RCAS use `32x1` work groups — no vertical locality. For ACCUMULATE's 3x3 neighborhood sampling, adjacent threads read from different rows causing L2 cache misses.

**Solution:** Change to `16x4` (64 threads):
- 4 rows × 16 cols = spatial locality for neighborhood sampling
- 64 threads = 2 warps (NVIDIA) or 1 wavefront (AMD) — good occupancy

**Changes:**
1. GLSL: `layout(local_size_x = 16, local_size_y = 4) in;` for ACCUMULATE_CS and RCAS_CS
2. Java: `glDispatchCompute((displayWidth + 15) / 16, (displayHeight + 3) / 4, 1);`
3. Both pipelines

---

## Task 7: Shared Memory for 3x3 Neighborhood Sampling

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java` (GLSL)

**Current problem:** ACCUMULATE_CS calls `texelFetch` 9 times per thread for the ellipsoid color clamp box. With 16x4 work groups, threads in the same work group read overlapping texels — redundant L2 fetches.

**Solution:** Load a `(16+2) × (4+2) = 18×6 = 108` pixel tile into `shared` memory, then read from shared memory. Each thread loads ~2 pixels (108 / 64 threads).

**Changes:**
1. Add `shared vec4 s_preparedColor[18][6];` to ACCUMULATE_CS
2. Add cooperative tile load loop before main logic + `barrier()`
3. Replace 3x3 `texelFetch` loop with shared memory reads using `gl_LocalInvocationID + 1` offset
4. Same for DEPTH_CLIP_CS (uses 8x8 work groups → 10x10 tile = 100 pixels)
5. Both pipelines

---

## Task 8: Frame Generation Pipeline Optimizations

**Files:** `FSR3Pipeline.java`

**Problem 1:** `FG_SETUP` dispatches at `max(renderWidth, displayWidth)` — oversized. It only clears render-resolution textures.
- **Fix:** Dispatch at `(renderWidth+7)/8, (renderHeight+7)/8`

**Problem 2:** SETUP clears MV fields that GAME_MV_FIELD immediately overwrites.
- **Fix:** Move disocclusion mask clear into GAME_MV_FIELD's first line. Remove SETUP dispatch entirely.

**Problem 3:** Barrier between DISOCCLUSION_MASK and INTERPOLATION — must KEEP because dispatches have different dimensions (render vs display resolution), so the GPU may schedule them on different compute units.

---

## Task 9: Persistent Texture Bindings

**Files:** `FSR2Pipeline.java`, `FSR3Pipeline.java`

**Current problem:** Each pass re-binds 6-10 textures via `glActiveTexture` + `glBindTexture`. Many textures are used across multiple passes (colorTex, depthTex, motionVectorTex, dilatedDataTex).

**Solution:** Bind all read-only textures to fixed slots once before the first pass. Each pass only calls `glUseProgram` and binds image textures (which change due to ping-pong).

**Fixed slot assignment:**
- Slot 0: colorTex
- Slot 1: depthTex
- Slot 2: motionVectorTex
- Slot 3: preparedInputColor
- Slot 4: dilatedData
- Slot 5: dilatedReactiveMasks
- Slot 6: internalUpscaled
- Slot 7: lockStatusHistory
- Slot 8: shadingChange (FSR3 only)
- Slot 9: newLocks

---

## Execution Order

```
Task 2 (barrier narrowing) ──→ Task 4 (RCAS barrier removal)
                                         │
                                         ↓
                              Task 6 (work groups 16x4)
                                         │
                                         ↓
                    Task 5 (UBO) ──→ Task 7 (shared memory)
                                         │
                                         ↓
                              Task 8 (FG pipeline)
                                         │
                                         ↓
                              Task 9 (persistent textures)
```

- **Tasks 2 & 4** can be done in parallel (no dependencies)
- **Task 5** depends on Task 6 (UBO layout must match new work group dispatch)
- **Task 7** depends on Task 6 (shared memory tile size = work group + 2)
- **Task 8** is independent of 5-7
- **Task 9** is easiest after 2/4 stabilize the dispatch

---

## Risk Assessment

| Task | Visual Risk | Crash Risk | Rollback Complexity |
|------|------------|-----------|-------------------|
| 2 | None (same data, narrower barrier) | None | Trivial (2 lines) |
| 4 | None (serial dispatch) | None | Trivial (1 line) |
| 5 | Low (UBO alignment bugs) | Low | Medium (shader + Java) |
| 6 | None (same thread count) | None | Trivial (2 lines) |
| 7 | Low (shared memory overflow) | Low | Medium (shader rewrite) |
| 8 | Medium (FG artifacts) | Low | Medium (FG pipeline) |
| 9 | None (same bindings, just persistent) | Low | Trivial (re-add calls) |

**Total expected improvement: 25-40% GPU time reduction, 8-13% CPU overhead reduction.**
