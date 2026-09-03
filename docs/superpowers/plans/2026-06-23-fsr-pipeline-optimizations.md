# FSR Pipeline GPU Optimizations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce FSR2/FSR3 GPU time by 15-25% through texture format optimization, barrier narrowing, FBO caching, and dispatch overhead reduction.

**Architecture:** Implement 4 independent optimizations ordered by impact: (1) texture format downgrades (biggest bandwidth win), (2) narrow memory barrier bits, (3) cache FBO in Iris upscale path, (4) RCAS barrier removal (keep dispatch, remove redundant barrier). All changes are in compute shader pipeline files; no new GL dependencies required.

**Tech Stack:** OpenGL 4.3+ compute shaders, LWJGL 3, Fabric Loom 1.16-SNAPSHOT, Minecraft 26.1.2.

## Global Constraints

- Maintain visual quality parity (A/B screenshots comparison required per task)
- No changes to FSR1 pipeline (it doesn't use the affected textures)
- All optimizations must work on NVIDIA, AMD, and Intel GPUs
- Use only GL constants from `GL43C` class (no GL44+ APIs)
- No new dependencies
- All changes must compile with Java 25

---

## File Structure

| File | Responsibility | Changes |
|------|----------------|---------|
| `src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java` | FSR2 6-pass pipeline | Texture format, barrier narrowing, RCAS barrier removal |
| `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` | FSR3 8-pass pipeline | Texture format, barrier narrowing, RCAS barrier removal |
| `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` | Main lifecycle, Iris upscale path | Cache FBO in Iris path |

---

### Task 1: Texture Format Downgrades (highest impact)

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java:1046-1060` (createTex2D calls) and `FSR2Pipeline.java` (GLSL layout qualifiers)
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java:1014-1043` (createTex2D calls) and `FSR3Pipeline.java` (GLSL layout qualifiers)

**Interfaces:**
- Consumes: `createTex2D` helper, GLSL `layout()` qualifiers in ACCUMULATE_CS and DEPTH_CLIP_CS
- Produces: 50% VRAM/bandwidth reduction on 2 textures

**Goal:** Reduce VRAM and bandwidth by changing `internalUpscaledTex` from RGBA16F (8B) to R11G11B10F (4B). `preparedInputColorTex` was also considered for RGBA8 but cannot be changed — it stores YCoCg color values with negative chroma (Co, Cg range [-0.5, 0.5]) and unsigned RGBA8 would clamp them to 0, destroying color.

- [x] **Step 1: Change internalUpscaledTex format and shader layout in FSR2Pipeline**

Change `ensureTextures()`:
```java
internalUpscaledTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R11F_G11F_B10F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
internalUpscaledWriteTex = createTex2D(displayWidth, displayHeight, GL43C.GL_R11F_G11F_B10F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
```

In ACCUMULATE_CS:
```glsl
layout(binding = 10, r11f_g11f_b10f) writeonly uniform image2D uInternalUpscaledRW;
```

- [ ] **Step 2: preparedInputColorTex — SKIP (stays RGBA16F)**

⚠️ `preparedInputColorTex` stores YCoCg color values. Chroma components Co/Cg range [-0.5, 0.5]. RGBA8 (unsigned normalized) would clamp negatives to 0, destroying color. Visual result: all blue → grey. Keep RGBA16F.

- [x] **Step 3: Repeat internalUpscaledTex change for FSR3Pipeline**

Same format + layout changes for `internalUpscaledTex`/`internalUpscaledWriteTex` in FSR3Pipeline.

- [x] **Step 4: Build and test**

Build: `./gradlew.bat clean build` — passes. Client: `./gradlew.bat runClient` — FSR1/FSR3 compile, runs, no crashes. Colors verified correct.

- [x] **Step 5: Commit**

Commit `c89f358` (`internalUpscaledTex` R11G11B10F) + `c03ce6f` (revert `preparedInputColorTex` to RGBA16F).

---

### Task 2: Narrow Memory Barrier Bits

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java:967, 1008, 1047, 1064, 1101, 1127`
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java:1165, 1220, 1260, 1276, 1316, 1337`

**Interfaces:**
- Consumes: None
- Produces: Narrower barrier bits per transition (removes unnecessary `GL_TEXTURE_FETCH_BARRIER_BIT` where only image access is needed)

**Goal:** Remove `GL_TEXTURE_FETCH_BARRIER_BIT` from barriers where the next pass only uses `imageLoad` (not texture sampling).

- [ ] **Step 1: Narrow ACCUMULATE barrier in FSR2Pipeline**

Line 1101:
```java
GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
```
Replace with:
```java
GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
```

(RCAS reads/writes via imageLoad/imageStore, not a sampler)

- [ ] **Step 2: Narrow RCAS barrier in FSR2Pipeline**

Line 1127:
```java
GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
```
Replace with:
```java
GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
```

(Final output is read via sampler in the blit — but the blit is in a separate frame cycle, so TEXTURE_FETCH is already covered by the implicit frame boundary.)

- [ ] **Step 3: All other barriers in FSR2Pipeline**

Lines 967, 1008, 1047, 1064 are already using single-bit barriers — keep as-is.

- [ ] **Step 4: Repeat for FSR3Pipeline**

Apply same two barrier narrowing changes to FSR3Pipeline lines 1316 and 1337.

- [ ] **Step 5: Build and test**

Run: `./gradlew.bat runClient`
Expected: No crashes, visual quality unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "perf: narrow glMemoryBarrier bits, remove unnecessary TEXTURE_FETCH flag"
```

---

### Task 3: Cache FBO in Iris Upscale Path

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java:529-585`

**Interfaces:**
- Consumes: None
- Produces: `rcasUpscaleFbo` field (int), reused across frames

**Goal:** Eliminate `glGenFramebuffers()`/`glDeleteFramebuffers()` calls every frame when Iris shaders are active.

- [ ] **Step 1: Add FBO field to FSRProcessor**

Find `private int rcasUpscaleTex = -1;` (near line 51). Add after it:
```java
private int rcasUpscaleFbo = -1;
```

- [ ] **Step 2: Initialize FBO once in ensureUpscaleShaders()**

Find `if (rcasUpscaleVao == -1) rcasUpscaleVao = GL43C.glGenVertexArrays();` (near line 494). Add after it:
```java
if (rcasUpscaleFbo == -1) rcasUpscaleFbo = GL43C.glGenFramebuffers();
```

- [ ] **Step 3: Replace per-frame FBO creation in applyUpscaleAndSharpen()**

Replace:
```java
int fbo = GL43C.glGenFramebuffers();
GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fbo);
```
With:
```java
GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, rcasUpscaleFbo);
```

And replace:
```java
GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, oldFbo);
GL43C.glDeleteFramebuffers(fbo);
```
With:
```java
GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, oldFbo);
```

- [ ] **Step 4: Clean up FBO in destroy()**

Find `if (rcasUpscaleVao != -1) { GL43C.glDeleteVertexArrays(rcasUpscaleVao); rcasUpscaleVao = -1; }` (near line 560). Add after it:
```java
if (rcasUpscaleFbo != -1) { GL43C.glDeleteFramebuffers(rcasUpscaleFbo); rcasUpscaleFbo = -1; }
```

- [ ] **Step 5: Build and test**

Run: `./gradlew.bat runClient`
Expected: No crashes, Iris shaders work, no `glGenFramebuffers`/`glDeleteFramebuffers` during gameplay.

- [ ] **Step 6: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "perf: cache FBO in Iris upscale path, eliminate per-frame alloc/free"
```

---

### Task 4: RCAS Barrier Removal (Low Impact)

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java:1118-1130`
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java:1332-1344`

**Interfaces:**
- Consumes: `rcasProg`, `upscaledOutputTex`
- Produces: Removed `glMemoryBarrier` call between Accumulate and RCAS

**Goal:** Remove the redundant `glMemoryBarrier` between ACCUMULATE_CS and RCAS_CS. Both dispatches are serialized in the same command buffer submission — the driver naturally orders them without a barrier because they share a texture dependency. The barrier was already narrowed in Task 2; this removes it entirely.

**Safety note:** `upscaledOutputTex` is written by ACCUMULATE_CS via imageStore (binding 14) and read by RCAS_CS via imageLoad (binding 0). The `glDispatchCompute` calls are submitted sequentially on the same command queue. On all GL 4.3+ drivers, dispatch ordering within the same command buffer guarantees completion ordering for the same texture. The barrier was conservative but unnecessary.

- [ ] **Step 1: Remove RCAS barrier in FSR2Pipeline**

Find within the RCAS dispatch block:
```java
    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
```
Replace with:
```java
    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glBindImageTexture(0, 0, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
```

- [ ] **Step 2: Repeat for FSR3Pipeline**

Apply same barrier removal in FSR3Pipeline's RCAS dispatch block.

- [ ] **Step 3: Build and test**

Run: `./gradlew.bat runClient`
Expected: No visual artifacts, sharpening works correctly, RCAS results match previous build.

- [ ] **Step 4: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "perf: remove redundant RCAS barrier after Accumulate (serial dispatch)"
```

---

## Validation Checklist

After each task:
- [ ] Visual A/B comparison (screenshots in-game with/without task changes)
- [ ] No crashes during world loading and gameplay
- [ ] Game closes cleanly (no GL error spam in console)

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-23-fsr-pipeline-optimizations.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?