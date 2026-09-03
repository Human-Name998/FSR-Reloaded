# FSR3 Frame Generation Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the simple single-pass FG shader with AMD's full multi-pass FSR3 Frame Generation pipeline (6 compute passes, no optical flow, no inpainting pyramid).

**Architecture:** 6 sequential GLSL compute shaders (SETUP, RECONSTRUCT_DILATE, RECONSTRUCT_PREV_DEPTH, GAME_MV_FIELD, DISOCCLUSION_MASK, INTERPOLATION) that replace the current `FRAME_GEN_CS` single-pass approach. Shaders run after the FSR3 upscale completes, using the already-computed dilated depth/MVs from the upscale pipeline.

**Tech Stack:** OpenGL 4.3 compute shaders, inline GLSL strings in Java, LWJGL 3 / GL43C.

## Global Constraints

- GL 4.3 compute shaders only (no Vulkan/DX12)
- All shaders are inline GLSL strings in FSR3Pipeline.java
- Texture bindings must not exceed GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS (usually 32)
- Memory barriers between every pass: `GL_SHADER_IMAGE_ACCESS_BARRIER_BIT`
- Thread group size: 8×8 for all passes
- Existing pipeline textures (reconstructedPrevDepthTex, dilatedDataTex) reused where possible
- No new textures in FSRProcessor — all FG state lives in FSR3Pipeline
- RECONSTRUCT_AND_DILATE pass is SKIPPED — the upscale pipeline already computes dilated depth/MVs and reconstructed previous depth in PASS 2 (RECONSTRUCT_CS). FG pipeline reads these results directly.
- All reads from `dilatedDataTex` use sampler2D (not imageLoad) — matches existing RGBA16F format
- No counter buffer — frame index tracked in Java via `fgFrameIndex`/`fgResetRequested`

---

### Task 1: Add FG pipeline internal textures + GL state tracking

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Consumes: existing `createTex2D()`, `ensureTextures()`, `deleteTextures()` patterns
- Produces: 6 new texture fields, added to init/delete lifecycle

**New texture fields to add (near line 818):**

```java
// FG pipeline internal textures
private int gameMvFieldXTex;        // R32UI, renderSize — packed MV field X
private int gameMvFieldYTex;        // R32UI, renderSize — packed MV field Y
private int reconstructedInterpDepthTex; // R32UI, renderSize — interpolated frame depth
private int disocclusionMaskTex;    // RG8, renderSize — bi-directional disocclusion
private int inpaintingMaskTex;      // R8, displaySize — inpainting weight (v1 placeholder)
```

- [ ] **Step 1: Add texture fields after the `fg_uDisplaySize` field (line 864)**

In the field declarations section, after `private int fg_uRenderSize, fg_uDisplaySize;` (line 863), add:

```java
// FG pipeline textures
private int gameMvFieldXTex;
private int gameMvFieldYTex;
private int reconstructedInterpDepthTex;
private int disocclusionMaskTex;
private int inpaintingMaskTex;
```

- [ ] **Step 2: Create textures in `ensureTextures()`**

Add after the existing FG texture creation block (after line 1070):

```java
// FG pipeline internal textures
if (gameMvFieldXTex == 0)
    gameMvFieldXTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
if (gameMvFieldYTex == 0)
    gameMvFieldYTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
if (reconstructedInterpDepthTex == 0)
    reconstructedInterpDepthTex = createTex2D(renderWidth, renderHeight, GL43C.GL_R32UI, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
if (disocclusionMaskTex == 0)
    disocclusionMaskTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RG8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
if (inpaintingMaskTex == 0)
    inpaintingMaskTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R8, GL43C.GL_NEAREST, GL43C.GL_NEAREST);
```

- [ ] **Step 3: Add to delete in `deleteTextures()`**

Add the new textures to the existing `texs` array and zero-out list:

```java
int[] texs = new int[] {
    // ...existing entries...
    gameMvFieldXTex, gameMvFieldYTex, reconstructedInterpDepthTex,
    disocclusionMaskTex, inpaintingMaskTex
};
// ...existing loop...
// Zero-out additions:
gameMvFieldXTex = 0; gameMvFieldYTex = 0; reconstructedInterpDepthTex = 0;
disocclusionMaskTex = 0; inpaintingMaskTex = 0;
```

- [ ] **Step 4: Add FG instance tracking fields**

```java
private int fgFrameIndex;       // incremented each FG dispatch
private boolean fgResetRequested; // set to true on first-frame / after resize
```

Add `fgResetRequested = true;` at the end of `setup()` (line 886). Add `fgFrameIndex = 0;` there too.

- [ ] **Step 5: Commit**

---

### Task 2: Add GLSL FFX compatibility macros and FG constants

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Produces: `FG_SHARED_H` — a shared GLSL header string included in all FG shaders

- [ ] **Step 1: Write the shared FFX compatibility header**

Add after the existing imports block, before `MV_GEN_CS`:

```java
private static final String FG_SHARED_H = """
    // FFX-style type aliases for HLSL→GLSL port
    #define FFX_UNROLL
    #define FFX_STATIC
    #define FFX_PARAMETER_IN
    #define FFX_PARAMETER_OUT out
    #define FFX_MIN16_I2 ivec2
    #define FFX_MIN16_F2 vec2
    #define FFX_MIN16_F float
    #define FFX_HALF 0

    const float FFX_FRAMEINTERPOLATION_EPSILON = 1e-3;
    const float FFX_FRAMEINTERPOLATION_FLT_MAX = 3.402823466e+38;
    const float FFX_FRAMEINTERPOLATION_FLT_MIN = 1.175494351e-38;
    const float FFX_EPSILON = 1e-6;

    // Bit packing: each 32-bit field slot = [DepthPriority:16 | MVComponent:16]
    const uint PRIORITY_MAX = (1u << 16) - 1;
    const uint PRIORITY_SHIFT = 16;

    float ffxSaturate(float v) { return clamp(v, 0.0, 1.0); }
    vec2 ffxSaturate(vec2 v) { return clamp(v, vec2(0.0), vec2(1.0)); }
    vec3 ffxSaturate(vec3 v) { return clamp(v, vec3(0.0), vec3(1.0)); }
    float ffxPow(float b, float e) { return pow(b, e); }
    float ffxRound(float v) { return floor(v + 0.5); }
    vec2 ffxFract(vec2 v) { return fract(v); }
    float ffxMin(float a, float b) { return min(a, b); }
    float ffxMax(float a, float b) { return max(a, b); }
    vec3 ffxLerp(vec3 a, vec3 b, float t) { return mix(a, b, t); }
    vec3 ffxBroadcast3(float v) { return vec3(v); }
    vec2 ffxBroadcast2(float v) { return vec2(v); }
    float ffxLinearFromSrgb(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
    vec3 ffxLinearFromSrgb(vec3 c) { return vec3(ffxLinearFromSrgb(c.r), ffxLinearFromSrgb(c.g), ffxLinearFromSrgb(c.b)); }

    uint packHalf2x16(vec2 v) { return packHalf2x16(v); }
    vec2 unpackHalf2x16(uint v) { return unpackHalf2x16(v); }

    // FFX comparison intrinsics
    bvec2 FFX_GREATER_THAN_EQUAL(vec2 a, vec2 b) { return greaterThanEqual(a, b); }
    bvec2 FFX_EQUAL(vec2 a, vec2 b) { return equal(a, b); }
    bvec2 FFX_LESS_THAN(uvec2 a, uvec2 b) { return lessThan(a, b); }
    """;
```

- [ ] **Step 2: Add FrameInterpolationConstants struct + packing helpers**

After `FG_SHARED_H`:

```java
private static final String FG_PACKING_H = """
    struct VectorFieldEntry {
        vec2 fMotionVector;
        float uHighPriorityFactor;
        float uLowPriorityFactor;
        bool bValid;
        bool bPrimary;
        bool bSecondary;
        bool bInPainted;
        float fVelocity;
        bool bNegOutside;
        bool bPosOutside;
    };

    VectorFieldEntry newVectorFieldEntry() {
        VectorFieldEntry v;
        v.fMotionVector = vec2(0.0); v.uHighPriorityFactor = 0.0;
        v.uLowPriorityFactor = 0.0; v.bValid = false; v.bPrimary = false;
        v.bSecondary = false; v.bInPainted = false; v.fVelocity = 0.0;
        v.bNegOutside = false; v.bPosOutside = false;
        return v;
    }

    uvec2 packVectorFieldEntries(uint depthPriority, vec2 mv) {
        uint priority = (depthPriority & PRIORITY_MAX) << PRIORITY_SHIFT;
        return uvec2(priority | (packHalf2x16(mv.xy) & 0xFFFFu),
                     priority | ((packHalf2x16(mv.xy) >> 16) & 0xFFFFu));
    }

    VectorFieldEntry unpackVectorFieldEntries(uvec2 packed) {
        VectorFieldEntry v = newVectorFieldEntry();
        v.uHighPriorityFactor = float((packed.x >> PRIORITY_SHIFT) & PRIORITY_MAX) / float(PRIORITY_MAX);
        v.uLowPriorityFactor = 0.0;
        v.bValid = v.uHighPriorityFactor > 0.0;
        v.bPrimary = true;
        v.bSecondary = false;
        uint vecPacked = (packed.x & 0xFFFFu) | ((packed.y & 0xFFFFu) << 16);
        v.fMotionVector = unpackHalf2x16(vecPacked);
        return v;
    }

    // FrameInterpolationConstants — matches AMD SDK constant buffer
    uniform ivec2 uRenderSize;
    uniform ivec2 uDisplaySize;
    uniform ivec2 uInterpolationRectBase;
    uniform ivec2 uInterpolationRectSize;
    uniform vec2 uJitter;
    uniform uint uFrameIndex;
    uniform bool uReset;
    uniform bool uInvertedDepth;
    uniform vec4 uDeviceToViewDepth;
    uniform float uTanHalfFovY;
    uniform ivec2 uMaxRenderSize;

    // Callback wrappers — direct texture access
    float convertFromDeviceDepthToViewSpace(float d) {
        return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
    }

    vec2 computeNdc(vec2 px, ivec2 size) {
        return px / vec2(size) * vec2(2.0, -2.0) + vec2(-1.0, 1.0);
    }

    vec3 getViewSpacePosition(ivec2 vp, ivec2 vpSize, float devDepth) {
        float Z = convertFromDeviceDepthToViewSpace(devDepth);
        vec2 ndc = computeNdc(vec2(vp), vpSize);
        float X = uDeviceToViewDepth[2] * ndc.x * Z;
        float Y = uDeviceToViewDepth[3] * ndc.y * Z;
        return vec3(X, Y, Z);
    }

    vec2 renderSize() { return vec2(uRenderSize); }
    vec2 displaySize() { return vec2(uDisplaySize); }
    ivec2 interpRectBase() { return uInterpolationRectBase; }
    ivec2 interpRectSize() { return uInterpolationRectSize; }

    bool isOnScreen(ivec2 pos, ivec2 size) {
        return all(lessThan(uvec2(pos), uvec2(size)));
    }

    bool isUvInside(vec2 uv) {
        return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
    }

    // Tonemapping
    vec3 tonemap(vec3 rgb) {
        return rgb / (max(0.0, max(rgb.r, max(rgb.g, rgb.b))) + 1.0);
    }
    vec3 inverseTonemap(vec3 rgb) {
        return rgb / max(1e-6, 1.0 - max(rgb.r, max(rgb.g, rgb.b)));
    }

    // Color space
    float rgbToLuma(vec3 linearRgb) {
        return dot(linearRgb, vec3(0.2126, 0.7152, 0.0722));
    }

    float rawRgbToLuminance(vec3 rawRgb) {
        return rgbToLuma(ffxLinearFromSrgb(rawRgb));
    }

    // Bilinear sampling
    struct BilinearData {
        ivec2 iOffsets[4];
        float fWeights[4];
        ivec2 iBasePos;
    };

    BilinearData getBilinearSamplingData(vec2 uv, ivec2 size) {
        BilinearData d;
        vec2 px = uv * vec2(size) - vec2(0.5);
        d.iBasePos = ivec2(floor(px));
        vec2 frac = fract(px);
        d.iOffsets[0] = ivec2(0,0); d.iOffsets[1] = ivec2(1,0);
        d.iOffsets[2] = ivec2(0,1); d.iOffsets[3] = ivec2(1,1);
        d.fWeights[0] = (1-frac.x)*(1-frac.y);
        d.fWeights[1] = frac.x*(1-frac.y);
        d.fWeights[2] = (1-frac.x)*frac.y;
        d.fWeights[3] = frac.x*frac.y;
        return d;
    }

    float minDividedByMax(float a, float b) {
        float m = max(a, b);
        return m != 0.0 ? min(a, b) / m : 0.0;
    }

    float normalizedDot3(vec3 v0, vec3 v1) {
        float m = max(length(v0), length(v1));
        return m > 0.0 ? dot(v0 / m, v1 / m) : 1.0;
    }

    float normalizedDot2(vec2 v0, vec2 v1) {
        float m = max(length(v0), length(v1));
        return m > 0.0 ? dot(v0 / m, v1 / m) : 1.0;
    }
    """;
```

- [ ] **Step 3: Commit**

---

### Task 3: Write FG SETUP compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

- [ ] **Step 1: Add `FG_SETUP_CS` after `FG_PACKING_H`**

```java
// -------------------------------------------------------------------------
// FG Pass 0: SETUP — clear MV fields + disocclusion mask
// -------------------------------------------------------------------------
private static final String FG_SETUP_CS = FG_SHARED_H + FG_PACKING_H + """
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0, r32ui) uniform uimage2D uGameMvFieldX;
    layout(binding = 1, r32ui) uniform uimage2D uGameMvFieldY;
    layout(binding = 2, rg8) uniform image2D uDisocclusionMask;

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);

        if (all(lessThan(uvec2(p), uvec2(uRenderSize)))) {
            imageStore(uGameMvFieldX, p, uvec4(0));
            imageStore(uGameMvFieldY, p, uvec4(0));
            imageStore(uDisocclusionMask, p, vec4(0.0, 0.0, 0.0, 0.0));
        }
    }
    """;
```

- [ ] **Step 2: Commit**

---

### Task 4: Write FG RECONSTRUCT_PREV_DEPTH compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Note:** The RECONSTRUCT_AND_DILATE pass is SKIPPED (already done by upscale PASS 2). This pass only computes the interpolated frame depth by reprojecting current depth with half-MV offset. Reads from `dilatedDataTex` as sampler2D (`.rg` = MV, `.b` = depth).

- [ ] **Step 1: Add `FG_RECONSTRUCT_PREV_DEPTH_CS`**

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

- [ ] **Step 1: Add `FG_RECONSTRUCT_PREV_DEPTH_CS`**

```java
// -------------------------------------------------------------------------
// FG Pass 1: RECONSTRUCT_PREV_DEPTH — reproject depth to interpolated frame
// -------------------------------------------------------------------------
private static final String FG_RECONSTRUCT_PREV_DEPTH_CS = FG_SHARED_H + FG_PACKING_H + """
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0) uniform sampler2D uDilatedData;  // .rg=MV, .b=depth
    layout(binding = 1, r32ui) uniform uimage2D uReconstructedInterpDepth;

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (!isOnScreen(p, uRenderSize)) return;

        vec2 uv = (vec2(p) + 0.5) / renderSize();
        vec4 data = textureLod(uDilatedData, uv, 0.0);
        float depth = data.b;
        vec2 mv = data.rg;

        // Reproject to interpolated frame position (half MV offset)
        BilinearData bd = getBilinearSamplingData(uv + mv * 0.5, uRenderSize);

        for (int i = 0; i < 4; i++) {
            if (bd.fWeights[i] > FFX_FRAMEINTERPOLATION_EPSILON) {
                ivec2 sp = bd.iBasePos + bd.iOffsets[i];
                if (isOnScreen(sp, uRenderSize)) {
                    imageAtomicMax(uReconstructedInterpDepth, sp, floatBitsToUint(depth));
                }
            }
        }
    }
    """;
```

- [ ] **Step 2: Commit**

---

### Task 6: Write FG GAME_MOTION_VECTOR_FIELD compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Note:** This is the most complex FG shader. It creates a packed motion vector field with priority encoding. The key adaptation from AMD's HLSL:
- No distortion field (Minecraft has lens distortion) — skipped
- Simplified secondary vector writing (single step vs adaptive loop)
- Uses dilated MVs and depth

- [ ] **Step 1: Add `FG_GAME_MV_FIELD_CS`**

```java
// -------------------------------------------------------------------------
// FG Pass 2: GAME_MOTION_VECTOR_FIELD — packed MV field with priority
// -------------------------------------------------------------------------
private static final String FG_GAME_MV_FIELD_CS = FG_SHARED_H + FG_PACKING_H + """
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0) uniform sampler2D uDilatedData;  // .rg=MV, .b=depth
    layout(binding = 1) uniform sampler2D uPrevInterpolationSource;
    layout(binding = 2) uniform sampler2D uCurrInterpolationSource;
    layout(binding = 3, r32ui) uniform uimage2D uGameMvFieldX;
    layout(binding = 4, r32ui) uniform uimage2D uGameMvFieldY;

    // Inverted-Z: 1.0 = near, 0.0 = far → higher depth = closer = higher priority
    uint depthToPriority(float depth) {
        return uint(clamp(depth * float(PRIORITY_MAX), 1.0, float(PRIORITY_MAX)));
    }

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (!isOnScreen(p, uRenderSize)) return;

        vec2 uv = (vec2(p) + 0.5) / renderSize();
        vec4 data = textureLod(uDilatedData, uv, 0.0);
        float depth = data.b;
        vec2 mv = data.rg;
        vec2 halfMv = mv * 0.5;

        uint priority = depthToPriority(depth);

        // Bilinear splat to 4 nearest pixels at half-MV offset (interpolated position)
        BilinearData bd = getBilinearSamplingData(uv + halfMv, uRenderSize);
        uvec2 packed = packVectorFieldEntries(priority, halfMv);

        for (int i = 0; i < 4; i++) {
            ivec2 sp = bd.iBasePos + bd.iOffsets[i];
            if (isOnScreen(sp, uRenderSize)) {
                imageStore(uGameMvFieldX, sp, uvec4(packed.x, 0, 0, 0));
                imageStore(uGameMvFieldY, sp, uvec4(packed.y, 0, 0, 0));
            }
        }

        // Secondary vectors along MV direction with lower depth priority
        float mvLen = length(halfMv * renderSize());
        if (mvLen > FFX_FRAMEINTERPOLATION_EPSILON) {
            vec2 stepDir = normalize(mv);
            float stepScale = length(1.0 / renderSize());
            float breakDist = min(mvLen, length(vec2(0.5)));

            uint secPriority = max(1u, priority >> 1);  // lower priority for secondary
            uvec2 secPacked = packVectorFieldEntries(secPriority, halfMv);

            for (float s = stepScale; s <= breakDist; s += stepScale) {
                vec2 secUv = uv + halfMv - stepDir * s;
                ivec2 secPos = ivec2(floor(secUv * renderSize() - vec2(0.5)));
                if (isOnScreen(secPos, uRenderSize)) {
                    imageStore(uGameMvFieldX, secPos, uvec4(secPacked.x, 0, 0, 0));
                    imageStore(uGameMvFieldY, secPos, uvec4(secPacked.y, 0, 0, 0));
                }
            }
        }
    }
    """;
```

- [ ] **Step 2: Commit**

---

### Task 7: Write FG DISOCCLUSION_MASK compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Note:** This is adapted from AMD's `computeDisocclusionMask`. It uses the Ksep depth separation formula from AMD's paper. The existing `computeDepthClip()` in `DEPTH_CLIP_CS` (lines 302-349) has a similar algorithm — this is the FG-specific version.

- [ ] **Step 1: Add `FG_DISOCCLUSION_MASK_CS`**

```java
// -------------------------------------------------------------------------
// FG Pass 3: DISOCCLUSION_MASK — depth-based bi-directional disocclusion
// -------------------------------------------------------------------------
private static final String FG_DISOCCLUSION_MASK_CS = FG_SHARED_H + FG_PACKING_H + """
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0) uniform sampler2D uDilatedData;  // .b=depth
    layout(binding = 1, r32ui) uniform uimage2D uReconstructedPrevDepth;
    layout(binding = 2, r32ui) uniform uimage2D uReconstructedInterpDepth;
    layout(binding = 3, r32ui) uniform uimage2D uGameMvFieldX;
    layout(binding = 4, r32ui) uniform uimage2D uGameMvFieldY;
    layout(binding = 5, rg8) uniform image2D uDisocclusionMask;

    const float Ksep = 1.37e-5;

    float loadPrevDepth(int estimatedIdx, ivec2 pos) {
        if (estimatedIdx == 0)
            return uintBitsToFloat(imageLoad(uReconstructedPrevDepth, pos).r);
        else
            return texelFetch(uDilatedData, pos, 0).b;
    }

    float computeDepthClip(int estimatedIdx, vec2 uv, float curDepth) {
        float curViewZ = convertFromDeviceDepthToViewSpace(curDepth);
        BilinearData bd = getBilinearSamplingData(uv, uRenderSize);
        float depthSum = 0.0, weightSum = 0.0;

        for (int i = 0; i < 4; i++) {
            if (bd.fWeights[i] > FFX_FRAMEINTERPOLATION_EPSILON) {
                ivec2 sp = bd.iBasePos + bd.iOffsets[i];
                if (isOnScreen(sp, uRenderSize)) {
                    float prevD = loadPrevDepth(estimatedIdx, sp);
                    float prevViewZ = convertFromDeviceDepthToViewSpace(prevD);
                    float diff = curViewZ - prevViewZ;
                    if (diff > 0.0) {
                        float planeD = max(prevD, curDepth);
                        vec3 fCenter = getViewSpacePosition(ivec2(uRenderSize / 2), uRenderSize, planeD);
                        vec3 fCorner = getViewSpacePosition(ivec2(0), uRenderSize, planeD);
                        float halfVp = length(vec2(uRenderSize));
                        float depthThresh = max(curViewZ, prevViewZ);
                        float kfov = length(fCorner) / length(fCenter);
                        float requiredSep = Ksep * kfov * halfVp * depthThresh;
                        float resFactor = clamp(length(vec2(uRenderSize)) / length(vec2(1920, 1080)), 0.0, 1.0);
                        float power = mix(1.0, 3.0, resFactor);
                        float d = pow(clamp(requiredSep / max(diff, 1e-7), 0.0, 1.0), power) * bd.fWeights[i];
                        depthSum += d;
                        weightSum += bd.fWeights[i];
                    }
                }
            }
        }
        return weightSum > 0.0 ? clamp(1.0 - depthSum / weightSum, 0.0, 1.0) : 0.0;
    }

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (!isOnScreen(p, uRenderSize)) return;

        vec2 uv = (vec2(p) + 0.5) / renderSize();
        float curDepth = textureLod(uDilatedData, uv, 0.0).b;

        // Unpack game MV field
        uvec2 packed;
        packed.x = imageLoad(uGameMvFieldX, p).r;
        packed.y = imageLoad(uGameMvFieldY, p).r;
        VectorFieldEntry mv = unpackVectorFieldEntries(packed);

        // Depth clip: forward (to previous) and backward (to current)
        float clipToPrev = 1.0 - computeDepthClip(0, uv + mv.fMotionVector, curDepth);
        float clipToCurr = 1.0 - computeDepthClip(1, uv - mv.fMotionVector, curDepth);

        vec2 disocclusion = vec2(clipToPrev, clipToCurr);
        disocclusion = vec2(
            greaterThanEqual(disocclusion, vec2(FFX_FRAMEINTERPOLATION_EPSILON)));

        // Prevent false disocclusion when MV points off-screen
        vec2 srcMv = mv.fMotionVector * 2.0;
        ivec2 prevPos = ivec2((uv + srcMv) * renderSize());
        disocclusion.x = clamp(disocclusion.x + float(!isOnScreen(prevPos, uRenderSize)), 0.0, 1.0);
        ivec2 currPos = ivec2((uv - srcMv) * renderSize());
        disocclusion.y = clamp(disocclusion.y + float(!isOnScreen(currPos, uRenderSize)), 0.0, 1.0);

        imageStore(uDisocclusionMask, p, vec4(disocclusion, 0, 0));
    }
    """;
```

- [ ] **Step 2: Commit**

---

### Task 8: Write FG INTERPOLATION compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Note:** This is the main interpolation pass, adapted from AMD's `computeFrameinterpolation()`. Key adaptation: no optical flow MV blending, uses game MVs only. The disoccluded pixels fall back to current frame color (inpainting v1).

- [ ] **Step 1: Add `FG_INTERPOLATION_CS`**

```java
// -------------------------------------------------------------------------
// FG Pass 5: INTERPOLATION — forward/backward warp with disocclusion blend
// -------------------------------------------------------------------------
private static final String FG_INTERPOLATION_CS = FG_SHARED_H + FG_PACKING_H + """
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0, r32ui) uniform uimage2D uGameMvFieldX;
    layout(binding = 1, r32ui) uniform uimage2D uGameMvFieldY;
    layout(binding = 2, rg8) uniform image2D uDisocclusionMask;
    layout(binding = 3) uniform sampler2D uPrevInterpolationSource;
    layout(binding = 4) uniform sampler2D uCurrInterpolationSource;
    layout(binding = 5, rgba8) writeonly uniform image2D uFgOutput;
    layout(binding = 6, r8) writeonly uniform image2D uInpaintingMask;

    void updateInpaintWeight(inout float w, float factor) {
        w = clamp(max(w, factor), 0.0, 1.0);
    }

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (!isOnScreen(p, uDisplaySize)) return;

        vec2 uv = (vec2(p) + 0.5) / displaySize();
        vec2 renderUv = uv * renderSize() / displaySize();
        ivec2 rp = ivec2(floor(renderUv * vec2(uRenderSize)));

        vec3 currColor = textureLod(uCurrInterpolationSource, uv, 0.0).rgb;

        // Disocclusion mask at render resolution
        vec2 disocclusion = vec2(1.0, 1.0);
        float inpainting = 0.0;

        if (isOnScreen(rp, uRenderSize)) {
            disocclusion = imageLoad(uDisocclusionMask, rp).rg;

            // Unpack game MV field
            uvec2 packed;
            packed.x = imageLoad(uGameMvFieldX, rp).r;
            packed.y = imageLoad(uGameMvFieldY, rp).r;
            VectorFieldEntry mv = unpackVectorFieldEntries(packed);

            // Forward warp: sample prev frame at uv + mv
            vec2 fwdUv = uv + mv.fMotionVector * displaySize() / renderSize();
            // Backward warp: sample curr frame at uv - mv
            vec2 bwdUv = uv - mv.fMotionVector * displaySize() / renderSize();

            vec3 prevCol = textureLod(uPrevInterpolationSource, clamp(fwdUv, vec2(0.0), vec2(1.0)), 0.0).rgb;
            vec3 currWarp = textureLod(uCurrInterpolationSource, clamp(bwdUv, vec2(0.0), vec2(1.0)), 0.0).rgb;

            bool fwdValid = isUvInside(fwdUv);
            bool bwdValid = isUvInside(bwdUv);

            // Disocclusion-adjusted blend weight
            float t = 0.5;
            t += 0.5 * (1.0 - disocclusion.x);  // prefer prev if prev is available
            t -= 0.5 * (1.0 - disocclusion.y);  // prefer curr if curr is disoccluded

            if (!fwdValid) t = 1.0;
            if (!bwdValid) t = 0.0;

            vec3 blended = mix(prevCol, currWarp, clamp(t, 0.0, 1.0));

            // Bi-directional disocclusion: inpaint
            float biDisoccluded = float(length(disocclusion) <= FFX_FRAMEINTERPOLATION_EPSILON);
            updateInpaintWeight(inpainting, biDisoccluded);

            // Fallback: use current frame color for disoccluded pixels
            vec3 result = mix(blended, currColor, inpainting);

            imageStore(uFgOutput, p, vec4(clamp(result, 0.0, 1.0), 1.0));
        } else {
            // Outside render rect: copy current frame
            imageStore(uFgOutput, p, vec4(currColor, 1.0));
        }

        imageStore(uInpaintingMask, p, vec4(inpainting, 0, 0, 0));
    }
    """;
```

- [ ] **Step 2: Commit**

---

### Task 9: Add program IDs, uniforms, compile, and destroy

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

- [ ] **Step 1: Add program ID fields (near line 798)**

```java
// FG pipeline program IDs
private int fgSetupProg;
private int fgReconstructPrevDepthProg;
private int fgGameMvFieldProg;
private int fgDisocclusionProg;
private int fgInterpolationProg;
```

- [ ] **Step 2: Remove old fgProg and fg_u* fields**

Remove `private int fgProg;` (line 798) and the FG gen uniform fields `private int fg_uRenderSize, fg_uDisplaySize;` (line 863).

- [ ] **Step 3: Compile in `ensureShaders()`**

Replace the old `fgProg = compileCompute(FRAME_GEN_CS);` with:

```java
fgSetupProg = compileCompute(FG_SETUP_CS);
fgReconstructPrevDepthProg = compileCompute(FG_RECONSTRUCT_PREV_DEPTH_CS);
fgGameMvFieldProg = compileCompute(FG_GAME_MV_FIELD_CS);
fgDisocclusionProg = compileCompute(FG_DISOCCLUSION_MASK_CS);
fgInterpolationProg = compileCompute(FG_INTERPOLATION_CS);
```

Update the compilation check to include all new programs. Update the info log message.

- [ ] **Step 4: Remove old FG uniform resolution**

Remove these lines:
```java
fg_uRenderSize = getUniformLoc(fgProg, "uRenderSize");
fg_uDisplaySize = getUniformLoc(fgProg, "uDisplaySize");
```

FG shaders resolve their uniforms via `GL43C.glGetUniformLocation()` at dispatch time like the other passes. No need for early binding since uniforms are set via plain `glUniform*` calls.

- [ ] **Step 5: Delete programs in `destroy()`**

Replace:
```java
if (fgProg != 0) { GL43C.glDeleteProgram(fgProg); fgProg = 0; }
```
with:
```java
if (fgSetupProg != 0) { GL43C.glDeleteProgram(fgSetupProg); fgSetupProg = 0; }
if (fgReconstructPrevDepthProg != 0) { GL43C.glDeleteProgram(fgReconstructPrevDepthProg); fgReconstructPrevDepthProg = 0; }
if (fgGameMvFieldProg != 0) { GL43C.glDeleteProgram(fgGameMvFieldProg); fgGameMvFieldProg = 0; }
if (fgDisocclusionProg != 0) { GL43C.glDeleteProgram(fgDisocclusionProg); fgDisocclusionProg = 0; }
if (fgInterpolationProg != 0) { GL43C.glDeleteProgram(fgInterpolationProg); fgInterpolationProg = 0; }
```

- [ ] **Step 6: Remove old `FRAME_GEN_CS` constant**

Delete the entire `FRAME_GEN_CS` block (lines 735-788) since it's replaced by the 6 FG pipeline shaders.

- [ ] **Step 7: Commit**

---

### Task 10: Replace dispatchFrameGen() with multi-pass pipeline

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Consumes: texture bindings for all pipeline resources
- Produces: `fgOutputTex` with interpolated frame, `inpaintingMaskTex` with disocclusion map

- [ ] **Step 1: Rewrite `dispatchFrameGen()`**

Replace the existing `dispatchFrameGen(int currentColorTex)` method (lines 1437-1469) with:

```java
public boolean dispatchFrameGen(int currentColorTex) {
    int[] prevTex = new int[7];
    int[] prevImg = new int[7];
    int prevProg = GL43C.glGetInteger(GL43C.GL_CURRENT_PROGRAM);
    int prevActive = GL43C.glGetInteger(GL43C.GL_ACTIVE_TEXTURE);
    for (int i = 0; i < prevTex.length; i++) {
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
        prevTex[i] = GL43C.glGetInteger(GL43C.GL_TEXTURE_BINDING_2D);
        prevImg[i] = GL43C.glGetInteger(GL43C.GL_IMAGE_BINDING_2D);
    }

    ensureTextures();

    // ---- PASS 0: SETUP (clear MV fields + disocclusion mask) ----
    GL43C.glUseProgram(fgSetupProg);
    setFgUniforms(fgSetupProg);

    GL43C.glBindImageTexture(0, gameMvFieldXTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(1, gameMvFieldYTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(2, disocclusionMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

    GL43C.glDispatchCompute((Math.max(renderWidth, displayWidth) + 7) / 8,
                            (Math.max(renderHeight, displayHeight) + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // ---- PASS 1: RECONSTRUCT_PREV_DEPTH (reads dilatedData as sampler2D) ----
    GL43C.glUseProgram(fgReconstructPrevDepthProg);
    setFgUniforms(fgReconstructPrevDepthProg);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgReconstructPrevDepthProg, "uDilatedData"), 0);

    GL43C.glBindImageTexture(1, reconstructedInterpDepthTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);

    GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // ---- PASS 2: GAME_MOTION_VECTOR_FIELD (reads dilatedData as sampler2D) ----
    GL43C.glUseProgram(fgGameMvFieldProg);
    setFgUniforms(fgGameMvFieldProg);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgGameMvFieldProg, "uDilatedData"), 0);
    GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevColorTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgGameMvFieldProg, "uPrevInterpolationSource"), 1);
    GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgGameMvFieldProg, "uCurrInterpolationSource"), 2);

    GL43C.glBindImageTexture(3, gameMvFieldXTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(4, gameMvFieldYTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R32UI);

    GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // ---- PASS 3: DISOCCLUSION_MASK (reads dilatedData as sampler2D) ----
    GL43C.glUseProgram(fgDisocclusionProg);
    setFgUniforms(fgDisocclusionProg);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgDisocclusionProg, "uDilatedData"), 0);

    GL43C.glBindImageTexture(1, reconstructedPrevDepthTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(2, reconstructedInterpDepthTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(3, gameMvFieldXTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(4, gameMvFieldYTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(5, disocclusionMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RG8);

    GL43C.glDispatchCompute((renderWidth + 7) / 8, (renderHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // ---- PASS 4: INTERPOLATION (reads prev/curr color as sampler2D) ----
    GL43C.glUseProgram(fgInterpolationProg);
    setFgUniforms(fgInterpolationProg);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevColorTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uPrevInterpolationSource"), 3);
    GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uCurrInterpolationSource"), 4);

    GL43C.glBindImageTexture(0, gameMvFieldXTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(1, gameMvFieldYTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_R32UI);
    GL43C.glBindImageTexture(2, disocclusionMaskTex, 0, false, 0, GL43C.GL_READ_ONLY, GL43C.GL_RG8);
    GL43C.glBindImageTexture(5, fgOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
    GL43C.glBindImageTexture(6, inpaintingMaskTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_R8);

    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

    // Unbind all FG texture units
    for (int i = 0; i < 7; i++) {
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
        GL43C.glBindImageTexture(i, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_R32UI);
    }

    // Restore GL state
    GL43C.glUseProgram(prevProg);
    for (int i = 0; i < prevTex.length; i++) {
        GL43C.glActiveTexture(GL43C.GL_TEXTURE0 + i);
        GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevTex[i]);
    }
    GL43C.glActiveTexture(prevActive);

    fgFrameIndex++;
    fgResetRequested = false;
    return true;
}
```

- [ ] **Step 2: Add `setFgUniforms()` helper method**

```java
private void setFgUniforms(int prog) {
    GL43C.glUniform2i(GL43C.glGetUniformLocation(prog, "uRenderSize"), renderWidth, renderHeight);
    GL43C.glUniform2i(GL43C.glGetUniformLocation(prog, "uDisplaySize"), displayWidth, displayHeight);
    GL43C.glUniform2i(GL43C.glGetUniformLocation(prog, "uInterpolationRectBase"), 0, 0);
    GL43C.glUniform2i(GL43C.glGetUniformLocation(prog, "uInterpolationRectSize"), displayWidth, displayHeight);
    GL43C.glUniform2f(GL43C.glGetUniformLocation(prog, "uJitter"), 0.0f, 0.0f);
    GL43C.glUniform1ui(GL43C.glGetUniformLocation(prog, "uFrameIndex"), fgFrameIndex);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(prog, "uReset"), fgResetRequested ? 1 : 0);
    GL43C.glUniform1i(GL43C.glGetUniformLocation(prog, "uInvertedDepth"), 1);
    GL43C.glUniform4f(GL43C.glGetUniformLocation(prog, "uDeviceToViewDepth"),
        deviceToViewDepth[0], deviceToViewDepth[1], deviceToViewDepth[2], deviceToViewDepth[3]);
    GL43C.glUniform1f(GL43C.glGetUniformLocation(prog, "uTanHalfFovY"), 1.0f);
    GL43C.glUniform2i(GL43C.glGetUniformLocation(prog, "uMaxRenderSize"), displayWidth, displayHeight);
}
```

- [ ] **Step 3: Wire `fgResetRequested`**

In `onResize()` callback (in FSR3Pipeline, called from FSRProcessor), set `fgResetRequested = true` and `fgFrameIndex = 0`.

In `resetFrameGen()` (line 1423):
```java
public void resetFrameGen() {
    hasPrevFrame = false;
    fgResetRequested = true;
    fgFrameIndex = 0;
}
```

- [ ] **Step 4: Commit**

---

### Task 11: Adapt FSRProcessor for new FG pipeline

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Note:** The FSRProcessor already dispatches FG via `fsr3Pipeline.dispatchFrameGen(realTex)`. The interface is unchanged (same method signature). No changes needed to the FSRProcessor dispatch path.

- [ ] **Step 1: Verify the call at line 406 works with the new signature**

Current call: `fsr3Pipeline.dispatchFrameGen(realTex);`

New method returns `void` instead of `boolean` — verify the call at line 406 discards the return value. If the return type changes, update the call.

Also verify that `savePrevFrame(realTex)` on line 410 works (it copies `previousDilatedDataTex` to `prevDilatedTex`, which is our FG pipeline's previous frame dilated data).

- [ ] **Step 2: Build and fix compile errors**

Run: `./gradlew.bat build`

Expected: compilation succeeds. If there are type mismatches or missing fields, fix them.

- [ ] **Step 3: Verify FG pipeline executes without GPU crash**

The mod should launch without GPU hangs. FG may produce visual artifacts (expected — correctness tuning is iterative), but should not crash the driver.

- [ ] **Step 4: Commit**

---

### Task 12: Self-review and fix

- [ ] **Step 1: Run build**

```bash
./gradlew.bat build
```

- [ ] **Step 2: Check all texture formats match GLSL `layout()` qualifiers**

Verify every `createTex2D` format matches its corresponding `imageLoad`/`imageStore` format qualifier in the shaders:
- `GL_R32UI` → `r32ui`
- `GL_RG8` → `rg8`
- `GL_R8` → `r8`
- `GL_RGBA8` → `rgba8`
- `GL_RGBA16F` → `rgba16f`
- `GL_RG16F` → `rg16f`
- `GL_R11F_G11F_B10F` → `r11f_g11f_b10f`

- [ ] **Step 3: Check all texture unit bindings are unique per pass**

No two bindings in the same pass should use the same texture unit index. Verify each `GL_TEXTURE0 + N` and `glBindImageTexture(N, ...)` uses unique N values.

- [ ] **Step 4: Commit**

---

## Resource Summary

### FG Pipeline Textures (6 new, 6+ reused from upscale)

**Created by FG pipeline:**
| Texture | Format | Size | GLSL Qualifier |
|---------|--------|------|----------------|
| gameMvFieldXTex | GL_R32UI | renderSize | `r32ui` |
| gameMvFieldYTex | GL_R32UI | renderSize | `r32ui` |
| reconstructedInterpDepthTex | GL_R32UI | renderSize | `r32ui` |
| disocclusionMaskTex | GL_RG8 | renderSize | `rg8` |
| inpaintingMaskTex | GL_R8 | displaySize | `r8` |

**Reused from upscale pipeline:**
| Texture | Format | Size | GLSL Qualifier |
|---------|--------|------|----------------|
| dilatedDataTex | GL_RGBA16F | renderSize | `rgba16f` |
| reconstructedPrevDepthTex | GL_R32UI | renderSize | `r32ui` |
| motionVectorTex | GL_RG16F | renderSize | `rg16f` |
| prevColorTex | GL_RGBA8 | displaySize | `rgba8` |
| prevDilatedTex | GL_RGBA16F | renderSize | `rgba16f` |

### Pipeline Passes Summary

| Pass | Threads | Dispatch Size | Key Inputs | Key Outputs |
|------|---------|---------------|------------|-------------|
| SETUP | 8×8 | max(res, disp) | — (none) | gameMvFieldX/Y, disocclusionMask cleared |
| RECONSTRUCT_PREV_DEPTH | 8×8 | renderSize | dilatedData (sampler) | reconstructedInterpDepth |
| GAME_MV_FIELD | 8×8 | renderSize | dilatedData, prev/curr color (samplers) | gameMvFieldX/Y (packed) |
| DISOCCLUSION_MASK | 8×8 | renderSize | gameMvField, dilutedData (sampler), depths | disocclusionMask (RG8) |
| INTERPOLATION | 8×8 | displaySize | gameMvField, disocclusion, prev/curr color | fgOutputTex, inpaintingMask |
