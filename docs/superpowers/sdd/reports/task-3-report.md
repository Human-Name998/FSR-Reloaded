# Task 3 Report: FSRProcessor frame generation lifecycle

## Changes made

### 1. `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`
- Added `savePrevFrame(int colorTex)` after `resetFrameGen()` — copies current frame color (display res) and `dilatedDataTex` (render res) to prev textures, sets `hasPrevFrame = true`.

### 2. `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`
- Added FG lifecycle fields: `windowHandle`, `prevHandSwingVal`, `suppressSwap`
- Added lazy init of `windowHandle` via `GLFW.glfwGetCurrentContext()` at top of `onRenderBegin()`
- Reset `suppressSwap = false` at start of each frame in `onRenderBegin()`
- Added `fsr3Pipeline.savePrevFrame(fsr2OutputTexId)` call inside `if (success)` block of FSR3 path in `onRenderEnd()`
- Modified `doPresent()`: keeps existing FSR1 path unchanged; in FSR2/FSR3 path, added FG blit that:
  1. Blits real frame to default FB (existing)
  2. If FSR3 + has previous frame: `glfwSwapBuffers` → get hand swing + depth → `dispatchFrameGen` → blit FG output → `glfwSwapBuffers` → set `suppressSwap = true`
- Added `shouldSuppressSwap()` getter
- Added `import org.lwjgl.glfw.GLFW`

### 3. `src/client/java/com/fsr2mod/mixin/WindowMixin.java`
- Added `@Inject(method = "swapBuffers", at = @At("HEAD"), cancellable = true)` that calls `ci.cancel()` when `processor.shouldSuppressSwap()` is true

## Build result
- `./gradlew.bat build` — **BUILD SUCCESSFUL**
- All source compiles, JAR packaged

## Files changed
- `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (1 method added)
- `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` (~50 lines added, 1 import added)
- `src/client/java/com/fsr2mod/mixin/WindowMixin.java` (8 lines added — 1 injection)

## Self-review notes
- **Window handle**: Used `GLFW.glfwGetCurrentContext()` instead of `Minecraft.getInstance().getWindow().getHandle()`/`getWindow()` which don't exist in this MC version. `glfwGetCurrentContext()` returns the currently active GLFW context window, which in Minecraft's render thread is always correct.
- **`savePrevFrame` vs existing `setPrevFrame`**: Both exist now. `savePrevFrame` reads from internal `dilatedDataTex` field (current frame after dispatch), while `setPrevFrame` takes both tex IDs as params. Only `savePrevFrame` is called from FSRProcessor.
- **Depth texture access**: Uses `((GlTexture)renderTarget.getDepthTexture()).glId()` matching the AGENTS.md pattern.
- **Ping-pong safety**: After `dispatch()`, `dilatedDataTex` has been swapped with `previousDilatedDataTex`, so reading `dilatedDataTex` in `savePrevFrame` gets the current frame's dilated data — correct.

## Concerns
- `safe_prevFrame` null check (`if (fsr3Pipeline != null)`) is redundant inside the `isFsr3()` block since we already check, but keeps defensive safety
- `glfwGetCurrentContext()` returns 0 if no context is current — lazy init guard `if (windowHandle == 0)` handles this, but if the first frame somehow lacks a context, it would retry each frame until one is available
