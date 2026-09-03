# VulkanMod FSR4 — Findings (2026-08-05)

This file documents **newly discovered facts** about how VulkanMod 0.6.8+26.1.2
renders its world projection, and why FSR4 under VulkanMod shook without any
temporal edge smoothing. These facts were verified by decompiling the actual
`VulkanMod-0.6.8+26.1.2.jar` and the vanilla 26.1.2 client jar (from
`~/.gradle/caches/fabric-loom/26.1.2/minecraft-client.jar`).

## Symptom progression (the bug we chased)

1. jitter forced to (0,0) → **no shake**, but "no quality change / looks like FSR1".
2. jitter fed to SDK (`prevJitter`) → **shake appears**, still pixelated.
3. VK MV-gen staging fix (depth/MV/reactive now persist) → **shake worse when moving**, still pixelated.

Steps 2→3 are the smoking gun: with valid inputs the SDK *actively reprojects
history*, so a phantom jitter now shows up as motion-dependent shake.

## Root cause — the jitter NEVER reaches VulkanMod's world render

The vanilla `GameRenderer.renderLevel` does:

```
aload 10            // local var 10 = projection Matrix4f (original)
levelProjectionMatrixBuffer.getBuffer(local10)   // our @ModifyArg replaces the ARG with a jittered COPY
RenderSystem.setProjectionMatrix(slice, PERSPECTIVE)   // slice content = jittered, but ...
```

- Our `GameRendererMixin.jitterLevelProjection` is `@ModifyArg(index=0)` on
  `ProjectionMatrixBuffer.getBuffer(Matrix4f)`. It returns a **new jittered
  Matrix4f object** — the buffer slice gets the jittered matrix, but **local
  var 10 still points to the ORIGINAL unjittered matrix**.

- VulkanMod's own `net.vulkanmod.mixin.render.GameRendererMixin` is an
  `@Inject(method="renderLevel", at=@At(INVOKE, target=RenderSystem.setProjectionMatrix))`
  that captures a **`@Local Matrix4f projectionMatrix`** and stores it in
  `VRenderSystem.projection` (a static `org.joml.Matrix4f` field).
  The `@Local` resolves to **local var 10 = the ORIGINAL matrix** — so
  `VRenderSystem.projection` holds the **UNJITTERED** projection.

- VulkanMod's chunk renderer then renders with that field:
  `net.vulkanmod.mixin.chunk.LevelRendererMixin` →
  `WorldRenderer.renderSectionLayer(type, camX, camY, camZ,
   VRenderSystem.modelView, VRenderSystem.projection)` →
  `VRenderSystem.applyMVP(modelView, projection)` →
  `calculateMVP()` → `MVP` MappedBuffer → terrain shader.

**Consequence:** the world renders UNJITTERED while the FSR4 SDK is told
`jitter=(prevJitterX, prevJitterY)` (non-zero Halton). The SDK de-jitters a
frame that was never jittered → output shifts by the phantom jitter every
frame (shake, amplified by motion) and history reprojection misaligns every
frame → the SDK rejects history → spatial-only output (pixelated, no temporal
edge smoothing). Exactly what the user saw.

### Why the GL path works

Under OpenGL the same `@ModifyArg` returns the jittered matrix and the vanilla
renderer uploads THAT to its projection UBO — the GL shaders consume the
jittered matrix. VulkanMod bypasses the vanilla buffer for world rendering and
uses its own captured copy, which is pre-jitter.

## Fix (implemented)

Add a second `@Inject` to OUR `GameRendererMixin` at the same
`RenderSystem.setProjectionMatrix(GpuBufferSlice, ProjectionType)` INVOKE in
`renderLevel`, targeting **ordinal 0** (the LEVEL projection upload; ordinal 1 is
the hand projection and is intentionally untouched), with a mixin priority
**lower** than VulkanMod's (default 1000) so our handler runs **after**
VulkanMod's (`@Mixin(value=GameRenderer.class, priority=100)`).

`FSRProcessor.applyVulkanModProjectionOverride()` then:

1. Returns unless `VulkanModCompat.isLoaded()` — `isLoaded()`, NOT `isActive()`:
   VulkanMod replaces the GL pipeline whenever it is loaded, so its chunk
   renderer reads `VRenderSystem.projection` regardless of whether OUR D3D12
   external-memory interop is live (`isActive()` additionally requires
   VulkanInterop + external memory; if those failed we'd be in the FSR1 spatial
   fallback anyway).
2. Returns unless `jitterAppliedThisFrame` — when jitter is zeroed (FSR1
   spatial fallback / suspended / not initialized) VulkanMod's own captured
   matrix is correct (unjittered == what the SDK expects); do NOT touch it.
3. Reflectively reads `net.vulkanmod.vulkan.VRenderSystem.projection` and
   jitters **VulkanMod's OWN matrix in place** — `translation(jitter).mul(cur)`,
   the identical clip-space translation `captureAndJitter` applies. We do NOT
   substitute our vanilla `lastProjJittered`: VulkanMod builds its projection
   with an infinite far plane (`CameraMixin.infiniteZFar`), and preserving its
   exact matrix avoids any far-plane/fog divergence. Only the translation must
   match the SDK's jitter — the near/far content is irrelevant to output-grid
   alignment.
4. The reflection `Field` is cached in a static (no per-frame class scan); a
   failure (VulkanMod rename) degrades to a no-op, logged ONCE via
   `vkModProjectionOverrideFailedLogged` so a silent regression is detectable.

## Known limitations of the override (NOT regressions)

- **Terrain jittered, entities/HUD unjittered**: `VRenderSystem.projection`
  feeds only the terrain path (`applyMVP`). Entity and HUD passes go through
  `calculateMVP()` reading the `projectionMatrix` MappedBuffer, which our
  write doesn't touch. The SDK sees a partially-jittered frame — strictly
  better than the fully-unjittered pixelated state, and a fix for the entity
  path (overriding the MappedBuffer too) is future work if entity regions
  show temporal artifacts.
- **`priority=100` applies to ALL handlers in `GameRendererMixin`** (render
  HEAD/TAIL, `getBuffer` @ModifyArg, hand @Redirect, level TAIL), not just the
  new one. Low risk — no other mod targets those same injection points — but
  noted for future edits.
- **Hand projection** under VKMod stays unjittered (ordinal 1 untouched) —
  minor cosmetic deviation in the first-person hand region only.

## Other verified VulkanMod internals (new knowledge)

- **Infinite far plane**: `net.vulkanmod.mixin.render.depth.CameraMixin` replaces
  the camera far plane with `Float.POSITIVE_INFINITY` (`infiniteZFar()`). Our
  `updateProjectionFromCamera()` (near=0.05, far=renderDist*16*4) is NOT what
  VulkanMod renders with — but the FSR4 SDK only needs sane near/far for depth
  disocclusion math; constant-depth 0.5 makes it non-critical.
- **Projection routing**: `VRenderSystem.projectionMatrix` (MappedBuffer) is
  filled by `applyProjectionMatrix(GpuBufferSlice)` (reads the vanilla buffer
  slice back — that WOULD be jittered) — but the terrain path uses
  `VRenderSystem.projection` (the Matrix4f field) via `applyMVP`, NOT the
  slice-based path. The slice path appears to serve other passes.
- **`@Local` capture timing**: MixinExtras `@Local` on a method-parameter
  resolves the local variable at the injection point — it reads the CURRENT
  object in that local slot, which our `@ModifyArg` does NOT overwrite.
- **VulkanMod mixins** (confirmed): `matrix.Matrix4fM` overrides
  `perspective/ortho/setPerspective/setOrtho` (used to inject the infinite far
  plane into every perspective matrix); `render.GameRendererMixin` is the ONLY
  class referencing `RenderSystem.setProjectionMatrix`.

## Diagnostic log signature (for future sessions)

`fsr4_diag.log`:
- `dispatchFsr4VkMod: calling dispatch ... jitter=(±0.4,±0.3) ... frame=N reset=0`
  — SDK runs every frame with real jitter (good).
- If the image shakes while moving and stays pixelated, check whether
  `VRenderSystem.projection` is being overridden — add a diag log in
  `FSRProcessor.applyVulkanModProjectionOverride` printing `proj.m00/m02` to
  confirm the jittered matrix reaches the field.

## Confirmation checklist (did the fix land?)

- `GameRendererMixin` declares `@Mixin(value = GameRenderer.class, priority = 100)`
  (lower than VulkanMod's default 1000 → our handler applies AFTER theirs).
- The `@Inject` targets `RenderSystem.setProjectionMatrix(GpuBufferSlice,
  ProjectionType)` INVOKE in `renderLevel`, `ordinal = 0`, and calls
  `processor.applyVulkanModProjectionOverride()`.
- `applyVulkanModProjectionOverride` gates on `isLoaded()` + `jitterAppliedThisFrame`
  and writes `VRenderSystem.projection` via a cached static `Field`.
- Build: `./gradlew.bat build` (Java-only change — no DLL rebuild needed), then
  verify `unzip -l build/libs/fsr-reloaded-1.1.0.jar | grep -i 'GameRendererMixin'`
  and inspect the class bytecode for the `afterLevelProjectionUploaded` handler
  calling `applyVulkanModProjectionOverride`.

## NEW: MV projection mismatch = 10x parallax error → history rejected everywhere (2026-08-06)

**Symptom**: after the jitter override fix, "shaking but not edge smoothening. the sun is
still pixelated." The FSR4 SDK was fed correct jitter and dispatched every frame (diag log
healthy: `jitter=(±0.4,±0.3) frame=N reset=0`, Copy1/Copy2 + waitForD3D12Output all OK),
yet the output stayed spatial — the shake IS the jittered input showing through a
history-rejected upscale.

**Root cause**: two different projections feeding two consumers of `lastProj`:

- The FSR4 **dispatch** derives near/far from `lastProj` at `onRenderBegin` — which was
  set by `captureAndJitter` (the `@ModifyArg` DOES fire under VulkanMod) during the
  previous frame's `renderLevel` → the REAL render projection (`near=0.50 far=40920.04`
  in the diag log — not the rebuilt values).
- The VK MV-gen matrices are set in `onSceneRendered`, which unconditionally called
  `updateProjectionFromCamera()` FIRST — OVERWRITING `lastProj` with a rebuilt matrix
  (`near=0.05, far=renderDist*64 ≈ 768`).

The constant-depth MV-gen maps `ndcZ=0` to a view-space mid-depth of z ≈ 2·n·f/(n+f):
≈ **1.0** with the real projection vs ≈ **0.1** with the rebuilt one — a **10× parallax
assumption difference**. During translation the MVs came out ~10× too large, the reactive
mask (`smoothstep(0.5,16,mvLen)`) saturated to ≈1 on every moving pixel, and the SDK
discarded ALL history → spatial-only output → shake (jittered input) + pixelated sun
(no temporal AA). The GL path never hit this because its MV-gen uses the SAME real
`lastProj` the dispatch reads.

**Fix** (FSRProcessor.java): `isSaneProjection()` (near ∈ (0,1000], far > near, no NaN —
far deliberately unbounded: VulkanMod's infiniteZFar can produce far → +Inf, which barely
affects ndcZ=0 reprojection) gates `updateProjectionFromCamera()`: the VKMod MV-gen now
uses the REAL captured projection, rebuilding only when the capture is garbage (historical
`far=-986895.06`). Dispatch + MV-gen now read the same matrix. One-time INFO logs added:
`VKMod MV-gen projection: near=... far=... (captured real render capture | camera rebuild
fallback)` and `VKMod projection override: applied jitter=(...) render-px to
VRenderSystem.projection` (confirms the jitter override fires).

**Known limitation (documented, NOT fixed)**: the sun/sky region still gets constant-depth
MVs (infinite depth ≈ 0 translation parallax, but ndcZ=0 assumes mid-depth), so during
translation the reactive mask can still fire on the sky → the sun may remain soft/pixelated
even after this fix. GL's cloud detection (DEPTH_CLIP_CS) uses REAL depth to suppress this;
the VK path has no real depth. Candidate next fix: sample the pooled color in the VK MV-gen
and zero MV+reactive on bright-desaturated sky/sun pixels (color-only heuristic — risk of
misclassifying white terrain; needs care).

**Confirmation**: `./gradlew.bat build` (Java-only), then the two one-shot INFO lines above
in `latest.log`; the MV-gen line must print `captured real render capture` with near≈0.5.

## NEW: GL vs VK side-by-side — the upscaler is now identical; the difference is render scale (2026-08-06)

User: "still pixelated. see what the opengl side is doing right that vulkan is doing wrong."

**Verified identical between GL and VK paths** (after the MV-projection fix):
- MV math: same reprojection (`invCurrVP * ndc(ndcZ=0) -> prevVP -> prevUv; mv = (prevUv-uv)*renderSize`),
  same clean (unjittered) `lastProj` for MV-gen, same view construction (conjugate rotation + translate -pos).
- Depth: constant 0.5 both paths (GL FBO-clear + copy; VK staging imageStore + vkCmdCopyImage).
- Reactive: same `max(smoothstep(0.5,16,mvLen), smoothstep(0.4,2.5,mvLen)*0.15)` (GL additionally maxes
  in the tile shading-histogram term — but that term only RAISES reactive, so its absence in VK can
  never cause MORE pixelation).
- Jitter: both apply `translation(jit).mul(proj)` in clip space; the VK override FIRES (log: `VKMod
  projection override: applied jitter=...`) and the dispatch passes prevJitter — the contract matches.
- Dispatch: healthy every frame (`dispatch → OK`, real Halton jitter, near=0.50 far=40920.04 real
  capture, frame counter advancing, reset=0).
- Copy flow: Copy1 input + MV-gen staging copies + Copy2 output->swapchain every frame, waits OK.

**The one real difference — render scale**:
- GL "working" sessions: **1092x683 -> 1680x1050 = qualityScale 0.65 = 1.54x upscale** (dominant pool
  size in diag log, 76 creates).
- Current VK sessions: **480x300 -> 1920x1201 = qualityScale 0.25 = 4x upscale** (config has
  qualityScale 0.25 + vulkanModRenderScale true).
- FSR4's design envelope is ~1.5-3x. At 4x even perfect temporal accumulation leaves edges
  soft/blocky, and the sun disc (10 render px -> 40 display px) cannot be reconstructed from 25%
  resolution. The pixelated sun behaves IDENTICALLY in GL at the same 0.25 scale (same constant-depth
  MV, same reactive) — GL only looked better because it ran at 0.65.

**Action**: raised nothing in code — added a one-shot WARN at scale < 0.4 (`FSR4 render scale 0.25 =
4.0x upscale — beyond FSR4's ~1.5-3x design envelope`). To match GL quality under VulkanMod, set
qualityScale to 0.5-0.65. A/B test to confirm scale is the cause: toggle VulkanMod render scale OFF
(runs GL path) at the same 0.25 — it looks equally soft, proving it's the input resolution, not the
code path.

## NEW: FSR-disable freeze + REAL per-pixel depth for the VK MV-gen (2026-08-06)

**Symptom**: (a) the game freezes when FSR is turned off; (b) "the water is still jagged even
at 65%".

**(a) Disable-freeze root cause**: `VulkanModCompat.renderScaleConfigured()` never checked
`config.enabled`, so with FSR off the 480x300 scaled framebuffer stayed live while the FSR4
pipeline stopped dispatching — the end() hook kept recording Copy2, which presented the STALE
last FSR4 output every frame → the display froze on the last upscaled frame. Fix:
- Disabled path in `onRenderBegin` (VKMod-FSR4 only, `isActive()`): `shutdownFsr4()` +
  `fsr4VkModActive=false`, and schedule the swapchain recreate back to native (gated on
  `renderScaleSwapped()` = render-scale users only). GL-path FSR4 users keep the pool (instant
  resume on re-enable — pre-existing behavior preserved).
- `renderScaleConfigured()` now includes `cfg.enabled` (the createResources hook won't re-swap
  the scaled framebuffer when FSR is off).
- New `renderScaleSwapped()` (enabled-INDEPENDENT) is the end() hook's outer gate; the inner
  `renderScaleActive()` gate was removed — the blit/copy branches self-guard via src!=dst, so:
  title screen (fb==swapchain → skip → vanilla present), disable transition (fb scaled +
  `fsr4VkModActive=false` → safe blit presents), enabled in-game (FSR4 copies).
- The state tracker adds `enabledChanged` to `flipNeedsRecreate` so RE-ENABLE schedules the
  recreate back to scaled.

**(b) Jagged water root cause — constant-depth MVs**: the VK MV-gen used `ndcZ=0` (≈ 1-block
depth) for EVERY pixel. During camera translation, distant water/terrain got ~100x wrong
parallax MVs → the SDK rejected history there → aliased water. GL had the same limitation (no
depth available from the GpuDevice abstraction) — but **VulkanMod keeps a REAL depth buffer**
(Framebuffer.getDepthAttachment(); usage=39 = SAMPLED|TRANSFER_DST|TRANSFER_SRC|DEPTH_ATTACHMENT;
render pass leaves it in DEPTH_ATTACHMENT_OPTIMAL, DEPTH.defaultLayout=3 — both verified by
bytecode). Fix:
- Shader: samples the framebuffer depth (binding 3, combined image sampler, nearest+clamp).
  Non-reversed (0=near,1=far) — matches the SDK's non-inverted expectation
  (`FFX_UPSCALE_ENABLE_DEPTH_INVERTED` NOT set in fsr4_context.cpp). `ndcZ = depth*2-1` (exact:
  the depth buffer stores NDC z post perspective-divide; same math as the GL shader's uUseDepth=1
  path). Real depth written to the pool R32F. `depth >= 0.999` → sky: mv=0 + reactive=1 (parity
  with GL MV_GEN_CS sky handling; the threshold is safe — Minecraft's non-reversed depth maps all
  world geometry (768-40920 blocks) to values ≤ ~0.001, only the depth-clear/sky reaches 1.0).
- Native: descriptor layout/pool/writes extended to 4 bindings; nearest sampler; 1x1 R32F dummy
  (0.5) as the deterministic fallback (unbound-descriptor UB otherwise); `setVkModDepthSource`
  JNI (re)creates a DEPTH-aspect view on image change + rewrites binding 3; recordVkModMvGen
  adds DEPTH_ATTACHMENT_OPTIMAL→SHADER_READ_ONLY_OPTIMAL before the dispatch and back after.
- Mixin binds the framebuffer depth each frame (cheap native no-op unless the image changed).
- **REVIEW FIX (bound-image tracking)**: setVkModDepthSource no-ops only when the image is
  already BOUND to binding 3 (`g_vkModDepthSrcBoundImage`, reset to NULL in
  destroyVkModMvGenPipeline); ensureVkModMvGenPipeline rebinds the real depth (if a view exists)
  after every descriptor-set rebuild. Without this, a pool rebuild (version switch /
  disable→enable) stranded the binding on the dummy forever (image handle unchanged → early-return
  skipped the rebind) → constant depth silently returned.

**Confirm on next run**: diag log `setVkModDepthSource` works silently (no new log lines added —
check water smoothness while WALKING, which is what the constant-depth fix targets); `recordVkModMvGen: dispatch WxH (real FSR4 inputs)` still every frame. Toggle FSR off → world renders
native, no freeze; toggle on → render scale re-engages.

## 2026-08-06 — SPIR-V byte-count bug broke the real-depth MV-gen entirely

**Symptom**: after shipping the real per-pixel depth MV-gen, water/thin structures stayed jagged — worse, the fix had NO effect at all.

**Root cause (log-proven)**: `vkCreateShaderModule → -3` (VK_ERROR_INVALID_SHADER_NV) on every pipeline init, then `recordVkModMvGen: pipeline not ready — skipping` every frame. The MV-gen never ran; the pool got NO MV/reactive updates → SDK rejects history → spatial-only output. The SPIR-V itself was valid (glslc-compiled, SPIR-V 1.0, disassembly clean) — the bug was in the EMBEDDED header: `kFsr4VkMvGenSpvSize` was generated as the WORD count (1197) instead of BYTES (= words × 4 = 4788). `codeSize=1197` isn't word-aligned → shader-module creation fails. The previous (working, constant-depth) shader had been embedded correctly, so the failure was invisible until the shader was regenerated with the depth feature.

**Fix**: regenerated `fsr4_vk_mvgen_spv.h` with the byte count (4788). Header now carries a comment stating the BYTE-count contract so a future regeneration can't repeat this. Also: the .spv generation script writes `words*4` — the fixed header at 4788 bytes loads clean.

**Lesson**: any time a compute shader is (re)compiled and embedded, verify BOTH that the SPIR-V is valid AND that the embedded size field is bytes (not words) — a shader-module creation failure is silent in production (just "pipeline not ready — skipping" every frame, which reads as a quality problem, not a crash).

## 2026-08-06 — REAL-DEPTH readback probe (verification instrumentation)

**Why**: the real-depth path (framebuffer depth sampled in MV-gen, ndcZ = depth*2-1, non-reversed) was implemented blind. If the depth convention is wrong (reversed, or misaligned with the render), the SDK rejects ALL history → spatial output → "still jagged water" — indistinguishable from the shader-never-ran failure. Added a one-shot probe that turns this failure mode into a one-line log.

**What it does**: on the first `recordVkModMvGen` after pipeline init, allocates a 1KB HOST_VISIBLE|HOST_COHERENT buffer, records a `vkCmdCopyImageToBuffer` of the CENTER 16×16 block of the STAGING depth (still TRANSFER_SRC_OPTIMAL — same barrier coverage as the 3 staging→imported copies, legal: one memory dependency with dstAccess=TRANSFER_READ covers all subsequent transfer reads in submission order) into it. The NEXT `recordVkModMvGen` call (before recording) consumes it: waits `g_vkModFrameFence` (100ms — it's the PREVIOUS frame's fence since the mixin stores the fence AFTER recordVkModMvGen, and fence_N covers frame N's submission incl. the copy; even if VulkanMod resets the fence mid-frame, frame N+1 completion implies frame N completion — GPU serialization keeps it correct), maps, logs 9 float samples + expected-convention hint, frees. Latch fixes: all failure branches set `g_vkModDepthReadbackDone=true` (no per-frame retry/log spam), `vkBindBufferMemory` result checked (unbound allocation would map GARBAGE as if real depth).

**How to read the output** (debugMode > 0 required — diagLog gated):
```
DEPTH READBACK: recorded 16x16 copy at offset (X,Y)
DEPTH READBACK (staging, real-depth path):
  [0,0]=0.002 [8,0]=0.450 [15,0]=0.998
  ...
  expected: terrain≈0.001-0.1, mid≈0.5, sky≈1.0 (non-reversed)
```
Terrain center ≈ small, sky ≈ 1.0 → convention correct → jagged water is then a reactive/MV-magnitude issue, not a depth issue. All values ≈ 0.5 (dummy) → real depth never bound. Terrain ≈ 1.0 / sky ≈ 0 → depth is REVERSED (or the framebuffer's depth is stored reversed-Z) → need `ndcZ = 1.0 - depth*2.0` — the GL path historically had exactly this reversed-Z support (uFarDepth sign auto-detect), the VK path may need the equivalent.

## 2026-08-06 — REAL-DEPTH readback result: DUMMY IS BOUND (all 0.5) — root cause of "still jagged"

**The readback probe paid off**: after the SPIR-V byte-count fix, the log showed:
```
ensureVkModMvGenPipeline: OK — real FSR4 VK input path ready
DEPTH READBACK: recorded 16x16 copy at offset (616,382)
DEPTH READBACK (staging, real-depth path):
  [0,0]=0.500000 [8,0]=0.500000 [15,0]=0.500000 ... (ALL 0.5)
```
All 9 samples exactly 0.5 = the **1×1 dummy** is bound to MV-gen binding 3, NOT the framebuffer's real depth. `ndcZ = 0.5*2−1 = 0` → constant-depth behavior → the real per-pixel depth feature silently NEVER engaged → distant water/terrain still gets ~100× wrong parallax → history rejected → jagged edges. This is why "still jagged" persisted even after the shader-compile fix.

**Why it's silent**: the native no-op early-return `if (newImage == g_vkModDepthSrcBoundImage && (newImage == VK_NULL_HANDLE || newFmt == g_vkModDepthSrcFmt)) return;` — when Java passes `(0L, 0)` (depthImg null, getId()==0, or the mixin's catch fired), the first call hits `newImage(0) == boundImage(0)` → returns with NO log. Every subsequent frame the same. No "undefined depth format", no view-creation error — pure silence.

**Diagnostics added** (one-shot, next session will pin it exactly):
- Java mixin: `[VKMod] depthImg id=... fmt=...` / `depthImg is NULL` / `getDepthAttachment threw: ...` — tells us which branch fired.
- Native: `setVkModDepthSource: image=%p fmt=%d bound=%p (first call)` + `rebinding to image=%p fmt=%d` — tells us what Java actually passed and whether the view was created.

**Hypotheses ranked** (in order of likelihood, to be confirmed by the next run):
1. `fb.getDepthAttachment()` returns null on the SCALED framebuffer → mixin passes 0L. (The scaled fb is built with `Framebuffer.builder(w,h,1,true)` — the `true` is `hasDepthAttachment`, and createImages() DOES create the depth image when hasDepthAttachment=true — bytecode-verified. But getId() on that depth image may return 0, or the mixin's `fb` is a different framebuffer than the one with depth.)
2. The mixin's `catch (Exception e)` fired (some reflection/API mismatch on the depth getter) → setVkModDepthSource(0L,0) → silent dummy.
3. `VulkanImage.getId()` returns the image VIEW or a wrapped handle, not the raw VkImage — the view created from it would be invalid → createVkModDepthView returns NULL → silent fallback (that path also has no log).

**Fix candidates after the diagnostic** (not yet applied — wait for the log):
- If depthImg is null: bind depth from `dmp.getDepthAttachment()` (DefaultMainPass has its own GpuTexture depthAttachmentTexture — a different accessor than the framebuffer's).
- If getId() is a view handle: use `VulkanImage.createImageView`-style raw id lookup, or import the depth VkImage via the same path the framebuffer color uses (the mixin already gets a working `srcId` from `src.getId()` for Copy1 — if the depth's getId() is 0 but color's is fine, the difference is a clue).

## 2026-08-06 — Real-depth: staged readback vs DIRECT readback probe

**New findings this round:**
1. **Stale-SPV hypothesis ELIMINATED**: disassembled the embedded SPIR-V
   (`spirv-dis fsr4_vk_mvgen.spv`) — it contains `OpTypeSampledImage` +
   `OpImageSampleExplicitLod` (line 172) + 3 `OpImageWrite` ops. The shader in
   the DLL IS the real-depth version; the "0.5 everywhere" is NOT an old
   constant-depth shader.
2. **The descriptor chain is verified-correct in code**: `setVkModDepthSource`
   runs BEFORE `ensureVkModMvGenPipeline` on the first frame (log order:
   `rebinding` at 251986 → `ensure...OK` at 251988), so the pipeline-build
   review-fix DOES write binding 3 = real view. Yet the frame-0 dispatch still
   output 0.5. The descriptor is not (provably) the culprit.
3. **So the remaining suspects are**: (a) the real depth attachment genuinely
   holds 0.5 (wrong framebuffer / scene depth written elsewhere / unused
   attachment cleared to 0.5), or (b) the sampler reads the attachment in the
   wrong layout (depthIn barrier oldLayout mismatch with VulkanMod's actual
   post-pass layout) and AMD returns the compressed/HiZ stale value (which
   happens to be 0.5 — suspiciously the same value we'd see for a cleared
   unused attachment).
4. **New DIRECT probe** (in the 10:14 jar): copies the framebuffer's REAL depth
   attachment (g_vkModDepthSrcImage) straight to a host buffer — no shader, no
   descriptor. Two extra barriers (DEPTH_ATTACHMENT_OPTIMAL → TRANSFER_SRC →
   back). Logs as `REAL-DEPTH DIRECT READBACK (framebuffer attachment)`.
   - Real values (terrain≈0.001-0.1, sky≈1.0) ⇒ depth image has content ⇒ bug
     is in the shader sampling path (descriptor/layout) ⇒ next fix there.
   - 0.5 everywhere ⇒ the attachment genuinely holds 0.5 ⇒ the scene never
     writes THIS depth (wrong framebuffer OR the attachment is a cleared
     placeholder) ⇒ fix the depth SOURCE, not the shader.
5. **Also confirmed**: the mixin passes `dmp.getMainFramebuffer()`'s depth
   attachment, and Copy1 proves that same framebuffer's COLOR has the scene —
   so the framebuffer is the right one. If the direct probe shows 0.5, the
   framebuffer's depth attachment must not be receiving the scene's depth
   writes (separate depth image / render-pass setup inside VulkanMod).

## 2026-08-06 — ROOT CAUSE FOUND: pipeline build destroys the depth view

**The direct probe split the problem definitively:**
- `REAL-DEPTH DIRECT READBACK (framebuffer attachment)` = **1.0** (far/sky) —
  the depth image has REAL content.
- Staging readback (shader OUTPUT) = **0.5** — the dummy.
- The attachment ≠ dummy ⇒ the shader sampled the DUMMY ⇒ descriptor path broken.
- Frame 0 logged `skipped (no real depth bound)`; frame 1 recorded ⇒ the
  framebuffer-depth VIEW was NULL at frame 0's dispatch, present at frame 1's.

**Root cause:** `ensureVkModMvGenPipeline()` calls `destroyVkModMvGenPipeline()`
at its start (leak-prevention for pool rebuilds, line ~2487), and that destroy
wipes the REAL-DEPTH source view AND `g_vkModDepthSrcImage/Fmt/BoundImage`
(lines ~2415-2444). The mixin calls `setVkModDepthSource` BEFORE
`recordVkModMvGen` (which triggers the FIRST pipeline build), so:
1. setVkModDepthSource creates the framebuffer-depth view.
2. recordVkModMvGen → ensureVkModMvGenPipeline → destroyVkModMvGenPipeline
   **destroys the just-created view** + wipes the source tracking.
3. The review-fix (runs after the destroy, at the end of the build) sees
   `g_vkModDepthSrcView == NULL` → keeps binding 3 on the dummy.
4. Every dispatch samples 0.5 → constant-depth MVs → SDK rejects history at
   distance → **jagged water** (the user-visible symptom).
On every LATER frame ensure() short-circuits (g_vkModMvGenReady), the view
survives, and setVkModDepthSource re-arms normally — which is why the bug only
hit the build frame and only showed up as persistent constant-depth.

**Fix (10:29 jar):** capture `depthSrcAtBuild`/`depthSrcFmtAtBuild` BEFORE the
destroy; in the review-fix section, recreate the view from the captured source
when `g_vkModDepthSrcView == NULL` (logs `recreated depth view (build wiped
it)`). Also added a forward declaration for `createVkModDepthView` (it is
defined after ensureVkModMvGenPipeline in the TU).

**Direct probe v2 (periodic, 3-row):** samples the framebuffer depth at 3 rows
(top quarter / center / bottom quarter, 16x4 blocks) every 60th frame, 5
recordings — one launch now shows sky-vs-terrain depth variation AND verifies
the staging readback matches (real values = fix worked).

## 2026-08-06 — The DMP-fb depth is a DECOY: bind the mc main-target depth

**The 3-row periodic direct probe was decisive:** even while the user walked
around in the world (frames 728-908), ALL THREE rows (top/center/bottom) of the
DefaultMainPass framebuffer's depth attachment read **1.0** — cleared, never
written. Yet its COLOR has the scene (Copy1 proves it). So the world renders
color into the DMP fb but depth into a DIFFERENT image.

**Bytecode root cause:** Minecraft 26.1.2 renders through the GpuDevice
abstraction. VulkanMod's `VkCommandEncoder.createRenderPass(colorView, ...,
depthView, ...)` binds the pass's OWN depth view into a `VkFbo` — and the pass
depth view comes from the **mc main render target** (`Minecraft.getMainRenderTarget()`),
NOT the DMP framebuffer's attachment. The DMP framebuffer's depth attachment
(`Framebuffer.getDepthAttachment()`) is a decoy created by the framebuffer
builder that no render pass ever writes. (VulkanMod uses dynamic rendering —
`RenderPass.beginDynamicRendering` — and its layout tracking is Java-side
`VulkanImage.currentLayout`, so our native barriers must restore the layout to
what Java expects.)

**Fix (10:55 jar):** the mixin now resolves the depth source as
`Minecraft.getInstance().getMainRenderTarget().getDepthTexture()` →
`VkGpuTexture.getVulkanImage()` (the real world-depth image), falling back to
the old DMP-fb attachment only if unavailable. One-shot probe logs both image
ids + sizes. `setVkModDepthSource` JNI grew (width, height) params so the
direct probe samples correct rows on the native-res (1920x1201) depth image vs
the 1248x781 pool. **Next log tells us the mc depth CONTENT: real values =
fix complete (jagged water gone); still 1.0 = the world's depth is elsewhere
(prepass texture) and we keep hunting.**

## THE DMP DECOY IS BOTH PATHS — real depth is the mc target's VIEW (2026-08-06)

**Symptom**: "nothing much changed" — after switching the depth source from
`fb.getDepthAttachment()` (DMP decoy, reads 1.0) to
`Minecraft.getMainRenderTarget().getDepthTexture()`, the direct probe STILL read
1.0 everywhere and the new "depth probe (one-shot): DMP... | mc target..." log
line NEVER printed.

**Two bugs found**:

1. **Diagnostic suppression**: the OLD one-shot block
   (`if (!vkModDepthSourceLogged) { ... "depthImg id=... (one-shot)" }`) runs
   BEFORE the mc-depth probe and sets `vkModDepthSourceLogged = true`, so the new
   probe AND its catch handler were permanently suppressed. The log showed the
   OLD message (`depthImg id=207ef917120 fmt=126 (one-shot)`) and the native
   received `207ef917120` = the DMP fallback — with zero explanation of why the
   mc path failed. Fix: restructured so the probe ALWAYS logs once with an
   explicit `mcFailReason` ("getDepthTextureView().texture() returned null" /
   "not VkGpuTexture" / "getVulkanImage() null" / "id==0" / "probe threw: ...").

2. **THE REAL ROOT CAUSE — the getter lies**: `net.vulkanmod.mixin.render.target.MainTargetMixin`
   (decompiled, bytecode-verified) does TWO things:
   - `createBuffers(int,int)` → `this.depthTexture = GpuDevice.createTexture(...)`
     (a FRESH VulkanImage — the image the world's render pass ACTUALLY writes
     depth into, via `depthTextureView`) and
     `this.depthTextureView = createTextureView(depthTexture)`.
   - OVERRIDES the `getDepthTexture()` GETTER to return
     `MainPass.getDepthAttachment()` → `DefaultMainPass.depthAttachmentTexture`
     → `gpuTextureFromVulkanImage(mainFramebuffer.getDepthAttachment())` — the
     DMP framebuffer's depth attachment, which `createAttachmentTextures()`
     builds from the SAME scaled framebuffer... whose depth the direct probe
     proved reads 1.0 (cleared, never written).
   So `getDepthTexture()` and `getDepthTextureView().texture()` are DIFFERENT
   images on the mc main target under VulkanMod. The world's render pass
   (`VkCommandEncoder.createRenderPass(colorView, depthView, ...)` → `VkFbo`
   constructor) attaches the DEPTH VIEW (the field) — the getter is only used
   for readback/UI paths. **Calling the getter silently binds the decoy even
   when the "mc path works"** — which is exactly what the readback showed (the
   mc path DID work, but `getDepthTexture()` returned the decoy).

**Fix**: the mixin now resolves the depth source via
`Minecraft.getInstance().getMainRenderTarget().getDepthTextureView().texture()`
(→ `VkGpuTexture` → `getVulkanImage()`), NOT `getDepthTexture()`. Fallback to
DMP-fb depth remains when the view path is unavailable.

**Confirm on next run**:
- Log line `[VKMod] depth probe (one-shot): DMP fb depth id=... | mc target VIEW depth id=...` —
  the mc id should now DIFFER from the DMP id (different image).
- `REAL-DEPTH DIRECT READBACK (attachment)` rows should show REAL variation
  (sky ≈1.0 top, terrain ≈0.001–0.1 bottom when looking at land) — if so, the
  FSR4 SDK finally gets per-pixel depth and the jagged water/thin structures
  should smooth out.

---

## FSR 4.0.2 INT8 source → SPIR-V → GLSL (2026-08-15)

Full feasibility test of compiling the leaked FSR 4.0.2 INT8 model source to
SPIR-V and GLSL. Script: `scripts/compile_fsr4_int8_spirv.sh`. Repos:
`third_party/FSR-4.0.2-reference` (source, MIT) and
`third_party/RE-of-FSR-4.1.0-Upscaling` (static RE).

**Results — 28/28 shaders compile to SPIR-V** with DXC 1.9.2607 on Windows
(the same version verified on Linux, where pre.hlsl hit a codegen bug that this
Windows build does not). Output:
`third_party/FSR-4.0.2-reference/Kits/FidelityFX/upscalers/fsr4/build-spirv/fsr4_model_v07_i8_quality/`
(26 ML passes + pre + post).

Flags that matter:
- `-fspv-target-env=vulkan1.1` — MUST be 1.1. The vulkan1.2 env triggers a DXC
  codegen bug (`OpCopyLogical` on RWByteAddressBuffer) that fails every shader.
- `-enable-16bit-types -HV 2021 -T cs_6_6` (AMD's own header comment).
- `_Static_assert` deleted from `FNB_CT2D_ADD.hlsli` (DXC SPIR-V can't lower it;
  guards guaranteed-true generated constants).
- `-DDATA_TYPE=float ...` FP32 overrides for pre/post (Vulkan forbids <32-bit
  sampled texture types).
- POST entries are lowercase (`fsr4_model_v07_i8_pass0_post`), not uppercase.

**The INT8 math is DOT4, not WMMA.** `WMMA_ENABLED 0`, `DOT4_ENABLED 1` in the
source; the SPIR-V is OpSDot/OpSUDot with `PackedVectorFormat4x8Bit` and
`SPV_KHR_integer_dot_product` capability. Counts: pass7 = 4,224 OpSDot, pass3 =
512, pass12 = 260. No OpCooperativeMatrix anywhere. So the performance on
RDNA 3.5 comes from the hardware `v_dot4_i32_iu8` instruction (GFX11 native),
exactly as vkd3d-proton's `wmma_rdna3_workaround` implies.

**GLSL is feasible.** `dotPacked4x8EXT` (GL_EXT_integer_dot_product, GLSL 4.50+)
compiles clean with glslangValidator and round-trips to the IDENTICAL
`OpSUDot PackedVectorFormat4x8Bit`. spirv-cross converts 15/28 automatically;
the 13 failures are EXACTLY the dot-product passes and are a spirv-cross bug
("Cannot resolve expression type" on OpSDot) — the math is expressible, just
not auto-emitted by the bundled spirv-cross. Mesa RADV exposes
`shaderIntegerDotProduct = true`; radeonsi exposes the GL extension, so the
INT8 model is hardware-accelerated on the 860M via either Vulkan or GL.

**Implications for the mod:**
1. A from-source FSR4 no longer needs `amd_fsr4_api.dll` — the shaders compile,
   the weights are extractable (`third_party/RE-of-FSR-4.1.0-Upscaling` scripts),
   and the DOT4 ops land on the 860M's native hardware.
2. Vulkan route is the clean one (SPIR-V as-is, no GLSL round-trip): fits the
   existing `fsr4_vk_mvgen.comp` / VulkanInterop plumbing.
3. GLSL route is possible (the 13 OpSDot passes would need hand-written
   `dotPacked4x8EXT` or a patched spirv-cross) — the perf would still be
   hardware-DOT4, not scalar emulation. The earlier "GLSL can't express the
   matrix ops" concern is RETRACTED: the extension exists and Mesa implements it.
4. Remaining unknowns unchanged: pass ordering + per-pass CBV semantics are
   static-inferred by the RE project, never runtime-verified. The mod's working
   native D3D12 FSR4 is the oracle that could validate them.
