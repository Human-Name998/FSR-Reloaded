# FSR3 Frame Generation Glitch Fix â€” Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the glitchy FSR3 frame generation by addressing the root causes: double-blending (FG input is the temporally-accumulated FSR3 output), MV field race conditions, fixed blend weight, and missing artifact-reduction passes. Then incrementally add PureScale-inspired improvements (neighborhood clamp, FG denoise post-pass, dynamic blend weight).

**Architecture:** The FG pipeline runs inside `FSRProcessor.onSceneRendered()` (line 1269) and has a duplicate fallback in `onRenderEnd()` (line 1507). A `fgFlipState` boolean alternates between presenting real frames and interpolated frames. **Verified: the alternation is correct** â€” `onSceneRendered` sets `sceneUpscaled = true` (line 1303), so `onRenderEnd` early-returns (line 1337) and never double-toggles. Each path toggles `fgFlipState` exactly once per frame.

**The real glitch root causes (confirmed by code reading):**
1. **Double-blending**: `dispatchFrameGen(fsr2OutputTexId)` feeds the temporally-accumulated FSR3 output (8-16 jittered samples) into the FG interpolation shader. The FG shader then blends this with the previous accumulated frame. Two layers of temporal accumulation = excess blur + ghosting. AGENTS.md:217 documents this as a known limitation.
2. **MV field race conditions**: `FG_GAME_MV_FIELD_CS` bilinear-splats each pixel's MV to 4 neighbors via plain `imageStore` â€” multiple threads overwrite the same texel, last-writer-wins = corrupted MVs at object boundaries.
3. **Fixed blend weight**: `t = 0.5` in the interpolation shader ignores actual elapsed time between real frames.
4. **No neighborhood clamp**: reprojected samples are blended unconditionally â†’ ghost trails on motion.
5. **No FG denoise post-pass**: interpolation speckle/artifacts persist on generated frames.

**Fix plan has 5 phases:** (1) confirm alternation, (2) fix double-blending via raw scene snapshot, (3) fix MV field races, (4) add neighborhood clamp + dynamic blend weight, (5) add FG denoise post-pass.

**Tech Stack:** Java 25, Fabric Loom 1.16-SNAPSHOT, Minecraft 26.1.2, OpenGL 4.3 compute shaders (GLSL 430), JOML math, LWJGL bindings.

## Global Constraints
- Java 25, Fabric Loom 1.16-SNAPSHOT, Minecraft 26.1.2
- OpenGL 4.3 compute shaders only (no compute = no FSR2/3)
- FSR shaders are inline GLSL strings in Java (no external .glsl files)
- No GLFW calls on render thread
- GL state must be saved/restored around all pipeline dispatches
- `GL43C.glMemoryBarrier` required between compute dispatches
- FG textures at display resolution (not render resolution)
- AGENTS.md is the source of truth for architecture decisions

---


## Phase 1: Confirm and Fix Alternation

### Task 1.1: Add alternation diagnostic logging

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java:1269-1299`

**Interfaces:**
- Consumes: `fgFlipState`, `isFrameGen()`, `fsr3Pipeline.hasPreviousFrame()`
- Produces: Diagnostic log output every 60 frames

- [ ] **Step 1: Add diagnostic logging around FG dispatch**

Add after line 1265 (before the FG dispatch if-block):

```java
if (isFrameGen() && timingFrameCount % 60 == 0) {
    FSRMod.LOGGER.info("[DIAG_FG] fgFlipState={} hasPrev={} sharedMem={} outputTex={}",
        fgFlipState,
        fsr3Pipeline != null ? fsr3Pipeline.hasPreviousFrame() : false,
        sharedMemoryActive,
        fsr2OutputTexId);
}
```

- [ ] **Step 2: Verify alternation**

Run: `./gradlew.bat runClient` â†’ enable FSR3_FG mode â†’ check `latest.log` for `[DIAG_FG]` lines.
Expected: `fgFlipState` alternates true/false each frame. If it's always true or always false, the toggle is broken.

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "fix: add FG alternation diagnostic logging"
```

---

## Phase 2: Fix Double-Blending (Root Cause of Blur/Ghosting)

The current FG pipeline feeds the **temporally-accumulated FSR3 output** into the interpolation shader. This output already contains 8-16 frames blended together. The FG shader then blends this with the previous accumulated frame â€” double temporal blending.

**Solution:** Save a snapshot of the **raw scene render** (before FSR3 temporal accumulation) as the FG input, instead of the accumulated output. This requires a new texture to hold the raw scene color at display resolution.

### Task 2.1: Add raw scene snapshot texture for FG input

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (add texture + copy)
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` (save raw scene before FSR3 dispatch)

**Interfaces:**
- Consumes: `renderTex` (scaled scene color, render resolution), `displayWidth`, `displayHeight`
- Produces: `fgRawSceneTex` â€” display-resolution copy of the raw scene, saved before FSR3 upscale

- [ ] **Step 1: Add `fgRawSceneTex` field and allocation in FSR3Pipeline**

In `FSR3Pipeline.java`, add near the other FG texture fields (~line 1286):

```java
private int fgRawSceneTex;  // raw scene at display resolution for FG input
```

In `ensureTextures()` (or the texture allocation section), add allocation:

```java
if (fgRawSceneTex == 0) {
    fgRawSceneTex = GL43C.glGenTextures();
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fgRawSceneTex);
    GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, displayWidth, displayHeight, 0,
        GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
    GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
    GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
    GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
}
```

Add cleanup in `deleteTextures()`:

```java
if (fgRawSceneTex != 0) { GL43C.glDeleteTextures(fgRawSceneTex); fgRawSceneTex = 0; }
```

Add getter:

```java
public int getRawSceneTex() { return fgRawSceneTex; }
```

- [ ] **Step 2: Add `saveRawScene` method to FSR3Pipeline**

```java
/**
 * Copies the raw scene render (before temporal accumulation) into fgRawSceneTex.
 * This provides clean, non-accumulated input for frame generation interpolation.
 */
public void saveRawScene(int sceneColorTex) {
    GL43C.glCopyImageSubData(sceneColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        fgRawSceneTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        displayWidth, displayHeight, 1);
}
```

- [ ] **Step 3: Save raw scene in FSRProcessor BEFORE FSR3 dispatch**

In `FSRProcessor.onSceneRendered()`, before the FSR3 dispatch block (before line 1218), add:

```java
// Save raw scene for FG input (before temporal accumulation blurs it)
if (isFrameGen() && fsr3Pipeline != null) {
    // The renderTex is at scaledWidthÃ—scaledHeight; we need to upscale to display res
    // Use glBlitFramebuffer for bilinear upscale into fgRawSceneTex
    // Actually: save at render res, let FG shader handle the scaling
    // Better: save after the render but before FSR3 pipeline runs
    fsr3Pipeline.saveRawScene(renderTex);
}
```

Wait â€” `renderTex` is at `scaledWidth Ã— scaledHeight` (render resolution), but `fgRawSceneTex` is at `displayWidth Ã— displayHeight`. We need to upscale. Use `glBlitFramebuffer`:

```java
if (isFrameGen() && fsr3Pipeline != null) {
    int rawSceneFbo = GL43C.glGenFramebuffers();
    GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, rawSceneFbo);
    GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
        GL43C.GL_TEXTURE_2D, fsr3Pipeline.getRawSceneTex(), 0);
    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, currentFbo);  // render target FBO
    GL43C.glBlitFramebuffer(0, 0, scaledWidth, scaledHeight,
        0, 0, displayWidth, displayHeight,
        GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_LINEAR);
    GL43C.glDeleteFramebuffers(rawSceneFbo);
}
```

Actually, simpler â€” use `glCopyImageSubData` if both are RGBA8. But they're different sizes. Need blit. Let me add a dedicated FBO helper.

Revised approach â€” add a method to FSR3Pipeline:

```java
public void saveRawSceneViaBlit(int srcTex, int srcW, int srcH) {
    int fbo = GL43C.glGenFramebuffers();
    try {
        GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, fbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
            GL43C.GL_TEXTURE_2D, fgRawSceneTex, 0);
        // Blit from render res (srcTex bound to read framebuffer) to display res
        int readFbo = GL43C.glGenFramebuffers();
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, readFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0,
            GL43C.GL_TEXTURE_2D, srcTex, 0);
        GL43C.glBlitFramebuffer(0, 0, srcW, srcH,
            0, 0, displayWidth, displayHeight,
            GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_LINEAR);
        GL43C.glDeleteFramebuffers(readFbo);
    } finally {
        GL43C.glDeleteFramebuffers(fbo);
        GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, 0);
    }
}
```

- [ ] **Step 4: Change FG interpolation to use raw scene instead of accumulated output**

In `FSR3Pipeline.dispatchFrameGen()`, change the texture bindings:

```java
// OLD:
GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uCurrInterpolationSource"), 4);

// NEW: use raw scene snapshot instead of accumulated output
GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, fgRawSceneTex);
GL43C.glUniform1i(GL43C.glGetUniformLocation(fgInterpolationProg, "uCurrInterpolationSource"), 4);
```

Similarly for `prevColorTex` â€” change `savePrevFrame` to save `fgRawSceneTex` instead of `fsr2OutputTexId`:

```java
// In FSRProcessor.onSceneRendered(), change:
fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
// To:
fsr3Pipeline.savePrevFrame(fsr3Pipeline.getRawSceneTex());
```

- [ ] **Step 5: Test FG with raw scene input**

Run: `./gradlew.bat runClient` â†’ FSR3_FG mode â†’ verify less blur/ghosting in motion.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "fix: use raw scene snapshot for FG input instead of accumulated output"
```

---

## Phase 3: Fix MV Field Race Conditions

The `FG_GAME_MV_FIELD_CS` shader uses `imageStore` to write to 4 surrounding texels (bilinear splatting). Multiple threads write to the same pixel from different source pixels â€” last writer wins, causing corrupted MVs at overlapping regions.

### Task 3.1: Replace bilinear splatting with atomicMax on packed MV field

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (FG_GAME_MV_FIELD_CS shader, lines 1070-1106)

**Interfaces:**
- Consumes: `uDilatedData` (dilated MV + depth from FSR3 pipeline)
- Produces: `gameMvFieldXTex`, `gameMvFieldYTex` (R32UI) â€” atomic-packed MV field

- [ ] **Step 1: Rewrite FG_GAME_MV_FIELD_CS to use atomicMax**

Replace the bilinear splat loop with single-pixel writes + atomicMax for priority:

```glsl
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (!isOnScreen(p, uRenderSize)) return;

    vec2 uv = (vec2(p) + 0.5) / renderSize();
    vec4 data = textureLod(uDilatedData, uv, 0.0);
    float depth = data.b;
    vec2 mv = data.rg;

    uint priority = depthToPriority(depth);
    uvec2 packed = packVectorFieldEntries(priority, mv);  // use full MV, not half

    // Single pixel write â€” no bilinear splatting (avoids race conditions)
    imageAtomicMax(uGameMvFieldX, p, packed.x);
    imageAtomicMax(uGameMvFieldY, p, packed.y);
}
```

Remove the bilinear splatting loop entirely. Each pixel writes only to its own position. The `atomicMax` ensures the highest-priority (closest depth) MV wins at each pixel.

- [ ] **Step 2: Verify no visual regression**

Run: `./gradlew.bat runClient` â†’ check motion vectors look correct (no ghosting at object edges).

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "fix: replace bilinear splatting with atomicMax in FG MV field"
```

---

## Phase 4: Add Neighborhood Clamp + Dynamic Blend Weight

### Task 4.1: Add 3x3 neighborhood clamp to FG interpolation shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (FG_INTERPOLATION_CS shader, lines 1185-1250)

**Interfaces:**
- Consumes: `uCurrInterpolationSource` (raw scene), `uPrevInterpolationSource` (prev raw scene), MV field, disocclusion mask
- Produces: `uFgOutput` â€” clamped interpolated color

- [ ] **Step 1: Add neighborhood clamp to FG_INTERPOLATION_CS**

Add before the final `imageStore`:

```glsl
// 3x3 neighborhood clamp on both reprojected samples
vec2 texel = 1.0 / displaySize();
vec3 boxMin = currColor;
vec3 boxMax = currColor;
for (int y = -1; y <= 1; y++) {
    for (int x = -1; x <= 1; x++) {
        vec3 s = textureLod(uCurrInterpolationSource, uv + vec2(float(x), float(y)) * texel, 0.0).rgb;
        boxMin = min(boxMin, s);
        boxMax = max(boxMax, s);
    }
}

vec3 sA = clamp(prevCol, boxMin, boxMax);
vec3 sB = clamp(currWarp, boxMin, boxMax);
vec3 clamped = mix(sA, sB, clamp(t, 0.0, 1.0));

// Artifact guard: bias toward current frame when reprojected samples disagree
vec3 dv = abs(sA - sB);
float disagree = clamp((dv.r + dv.g + dv.b) * 2.0, 0.0, 1.0);
clamped = mix(clamped, sB, disagree * 0.5);

vec3 result = mix(clamped, currColor, inpainting);
```

- [ ] **Step 2: Test artifact reduction**

Run: `./gradlew.bat runClient` â†’ FSR3_FG â†’ check for reduced ghosting and halo artifacts.

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "feat: add 3x3 neighborhood clamp to FG interpolation"
```

### Task 4.2: Add dynamic blend weight based on elapsed time

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (FG_INTERPOLATION_CS shader)
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` (compute frac, pass as uniform)

**Interfaces:**
- Consumes: `lastFrameTimeNs` (nanosecond timestamp of last real frame), `System.nanoTime()`
- Produces: `fgBlendWeight` uniform (0.0-1.0) â€” dynamic interpolation factor

- [ ] **Step 1: Add timing fields to FSRProcessor**

Add near `lastFrameTime`:

```java
private long lastRealFrameTimeNs;
```

- [ ] **Step 2: Compute dynamic blend weight**

In `onSceneRendered()`, before FG dispatch:

```java
float blendWeight = 0.5f;  // default midpoint
if (lastRealFrameTimeNs > 0) {
    float elapsedMs = (System.nanoTime() - lastRealFrameTimeNs) / 1_000_000.0f;
    float targetMs = 1000.0f / Math.max(1, client.getFps());  // target frame time
    blendWeight = Math.min(1.0f, Math.max(0.0f, elapsedMs / Math.max(targetMs, 1.0f)));
}
fsr3Pipeline.setFgBlendWeight(blendWeight);
```

- [ ] **Step 3: Add uniform to FSR3Pipeline**

Add field:

```java
private float fgBlendWeight = 0.5f;

public void setFgBlendWeight(float w) { fgBlendWeight = w; }
```

In `dispatchFrameGen()`, set the uniform:

```java
int blendLoc = GL43C.glGetUniformLocation(fgInterpolationProg, "uBlendWeight");
if (blendLoc >= 0) GL43C.glUniform1f(blendLoc, fgBlendWeight);
```

- [ ] **Step 4: Update shader to use dynamic weight**

In `FG_INTERPOLATION_CS`, add uniform:

```glsl
uniform float uBlendWeight;
```

Replace the fixed `t = 0.5`:

```glsl
float t = uBlendWeight;
```

- [ ] **Step 5: Save timestamp after FG dispatch**

```java
lastRealFrameTimeNs = System.nanoTime();
```

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "feat: dynamic FG blend weight based on frame timing"
```

---

## Phase 5: Add FG Denoise Post-Pass

PureScale runs a dedicated denoise + sharpen pass on generated frames only. This removes interpolation artifacts without blurring real frames.

### Task 5.1: Add FG denoise compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (add shader + dispatch)

**Interfaces:**
- Consumes: `fgOutputTex` (interpolated frame, RGBA8, display res)
- Produces: `fgOutputTex` (denoised in-place, or separate texture)

- [ ] **Step 1: Add FG_DENOISE_CS shader string**

Add after `FG_INTERPOLATION_CS`:

```java
private static final String FG_DENOISE_CS = """
    #version 430 core
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0, rgba8) uniform image2D uImg;
    uniform ivec2 uOutputSize;

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (p.x >= uOutputSize.x || p.y >= uOutputSize.y) return;

        vec2 uv = (vec2(p) + 0.5) / vec2(uOutputSize);
        vec2 texel = 1.0 / vec2(uOutputSize);

        vec3 c = imageLoad(uImg, p).rgb;

        // 3x3 edge-aware bilateral denoise
        float wsum = 1.0;
        vec3 sum = c;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                if (x == 0 && y == 0) continue;
                vec3 n = imageLoad(uImg, p + ivec2(x, y)).rgb;
                vec3 d = n - c;
                float w = exp(-dot(d, d) / 0.02);  // sigma=0.14
                sum += n * w;
                wsum += w;
            }
        }
        vec3 filtered = sum / wsum;

        // Artifact confidence: how far center differs from neighborhood mean
        vec3 mean = c;  // approximate with center for speed
        float artifact = clamp(length(c - filtered) * 6.0, 0.0, 1.0);

        // Only denoise artifact pixels (not clean regions)
        vec3 result = mix(c, filtered, artifact * 0.5);  // 50% denoise strength

        imageStore(uImg, p, vec4(result, 1.0));
    }
    """;
```

- [ ] **Step 2: Add denoise program and dispatch**

In `ensureFgShaders()`:

```java
fgDenoiseProg = ShaderBinaryCache.compileCompute(FG_DENOISE_CS);
```

After the interpolation dispatch in `dispatchFrameGen()`, add:

```java
// Denoise pass: remove interpolation artifacts on generated frames
if (fgDenoiseProg != 0) {
    GL43C.glUseProgram(fgDenoiseProg);
    GL43C.glBindImageTexture(0, fgOutputTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
    int loc = GL43C.glGetUniformLocation(fgDenoiseProg, "uOutputSize");
    GL43C.glUniform2i(loc, displayWidth, displayHeight);
    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
}
```

- [ ] **Step 3: Test denoise quality**

Run: `./gradlew.bat runClient` â†’ FSR3_FG â†’ verify reduced speckle/ghost artifacts on generated frames.

- [ ] **Step 4: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "feat: add FG denoise post-pass for artifact reduction"
```

---

## Verification Checklist

After all phases, verify:

- [ ] FG alternates correctly (Real, FG, Real, FG pattern)
- [ ] Generated frames show reduced blur compared to before
- [ ] Motion vectors are clean at object edges (no corruption from races)
- [ ] Ghost trails are reduced by neighborhood clamp
- [ ] Hand/weapon doesn't double on camera turns (may need future task)
- [ ] Performance: FG overhead ~4ms average (not ~8ms every frame)
- [ ] No crashes or GL errors in `latest.log`
- [ ] Debug overlay (config.debugMode 1-6) still works

## Future Work (Not in This Plan)

- **Hand depth guard**: Detect near-layer pixels (depth < 0.93) and snap to nearest real frame
- **Optical flow search**: Â±2 texel search for best-matching reprojection offset
- **Frame warp**: Predict camera motion ahead for lower perceived latency
- **Motion sharpen post-pass**: Sharpen only moving regions on generated frames
- **Live reprojection mode**: Warp newest frame to current mouse position for responsive high FPS
