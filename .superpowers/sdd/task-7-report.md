# Task 7 Report: FSRProcessor FSR4 Dispatch — Hybrid Command-Buffer Flush

## Status: ✅ Complete

## Commit
`b6c6d62` — Phase 3 Task 7: FSRProcessor FSR4 dispatch — Hybrid Command-Buffer Flush

## Changes
**File modified:** `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` (+160, -1)

### What was added:
1. **Imports** — `Fsr4Native`, `GLSemaphoreManager`
2. **Fields** — `gpuSyncMgr`, `fsr4Available`, `fsr4Initialized`, context dimensions (render/display w/h), `lastFsr4ContextChange`, `FSR4_DEBOUNCE_MS`, `fsr4FramesSkipped`
3. **FSR4 init in `onRenderBegin()`** — after shared memory block:
   - Checks `Fsr4Native.isAvailable()` 
   - Debounces context recreation at 500ms
   - Calls `sharedTexMgr.allocateFsr4Pool()` **before** `Fsr4Native.initFsr4()`
   - Creates `GLSemaphoreManager` on success
4. **FSR4 dispatch in `onSceneRendered()`** — before FSR2/3/1 block:
   - Frame time budgeting: skips if `> 10ms`, fallback after 10 consecutive skips
   - Copies scene color+depth from `renderTarget` to shared VK textures via `glCopyImageSubData`
   - Extracts `cameraNear`/`cameraFar`/`cameraFovY` from `lastProj` matrix
   - Hybrid Command-Buffer Flush: `flushGL()` → `Fsr4Native.dispatchFsr4()` → `waitIdle()`
   - `copyFsr4OutputToMainRT()` helper copies output shared texture to `mainRenderTarget`
   - Early return after FSR4 dispatch (skips FSR2/3/1)
5. **Cleanup in `onResize()` and `destroy()`** — `shutdownFsr4()`, `destroyFsr4Pool()`, null gpuSyncMgr
6. **`copyFsr4OutputToMainRT()`** helper method — barrier + `glCopyImageSubData` from output tex to mainRT

### Build
`BUILD SUCCESSFUL in 4s`

## Design Decisions
- **Hybrid Command-Buffer Flush** (not hardware semaphores) — matches AGENTS.md and CRITICAL UPDATE
- **No Path A/B branching** — unified `flushGL() → dispatch → waitIdle()` flow
- **FSR4 dispatch early-returns** — skips all existing FSR2/3/1 code when active
- **Scene copy before dispatch** — `glCopyImageSubData` transfers renderTarget color+depth to shared VK textures (required since FSR4 reads from VK memory)
- **Near/far plane extraction** — from projection matrix `m22`/`m23` with NaN guard and defaults
- **MV and reactive textures** passed as alloc pointers (textures populated by sharedTexMgr pool; MV/reactive content will need separate population in future tasks)

