# Task 7: FSRProcessor Integration — Dual-Path Orchestration

## Files
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

## Context
Integrate FSR4 dispatch into FSRProcessor's main render loop. This is the orchestrator that ties together Fsr4Native (Task 5), GLSemaphoreManager (Task 6), and SharedTextureManager.allocateFsr4Pool() (Task 4).

## Steps
1. **Read full FSRProcessor.java** — understand existing fields, `onRenderBegin()`, `onSceneRendered()`, `onResize()`, `destroy()`, and MV gen dispatch location.

2. **Add FSR4 fields** after existing `sharedMemoryActive`:
   ```java
   private Fsr4Native fsr4Native;          // JNI bridge (static methods, or null)
   private GLSemaphoreManager gpuSyncMgr;  // null if unavailable
   private boolean fsr4Available;
   private boolean fsr4Initialized;
   private int fsr4ContextRenderW, fsr4ContextRenderH;
   private int fsr4ContextDisplayW, fsr4ContextDisplayH;
   private long fsr4Fence;                  // Path B fence (0 = none)
   private long lastFsr4ContextChange;      // debounce timestamp
   private static final long FSR4_DEBOUNCE_MS = 500;
   private int fsr4FramesSkipped;           // consecutive timeout count
   ```

3. **Add FSR4 init/resize in onRenderBegin()** — after existing `if (checkVulkanAvailable()) { ... }` shared memory block:
   - Check Fsr4Native.isAvailable()
   - Debounce recreation to 500ms
   - On recreation: shutdown old context, destroy GLSemaphoreManager, allocateFsr4Pool(), initFsr4(), create GLSemaphoreManager with semaphore handles

4. **Add FSR4 dispatch path in onSceneRendered()** — before existing FSR2/3 dispatch:
   - Frame time budgeting: skip if scene > 10ms, fallback after 10 consecutive skips
   - Path A (hardware semaphores): signalRenderDone → dispatchFsr4 → waitIdle → waitForComputeDone
   - Path B (fence fallback): fenceRenderDone → glFlush → glClientWaitSync → dispatchFsr4 → waitIdle
   - Copy FSR4 output to mainRT for GUI rendering
   - Fallback to FSR2/3 if FSR4 unavailable

5. **Add FSR4 cleanup in onResize()** — shutdownFsr4() + destroy GLSemaphoreManager

6. **Add FSR4 cleanup in destroy()** — same as onResize

7. **Handle early-return guards** — ensure no dangling fence objects in early-return paths

8. Build: `.\gradlew.bat build`
9. Commit

## Important
- Use static methods: `Fsr4Native.initFsr4()`, `Fsr4Native.dispatchFsr4()`, etc.
- Debounce context recreation to 500ms (context creation loads neural net weights, ~50-100ms)
- Frame time budgeting: skip FSR4 if currentFrameTimeMs > 10ms
- After 10 consecutive skips, fall back to FSR2/3
- Path A (semaphores) preferred; Path B (fence) fallback
- glFlush() after signalRenderDone() is mandatory
