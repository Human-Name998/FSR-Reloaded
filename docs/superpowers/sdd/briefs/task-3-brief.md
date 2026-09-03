# Task 3: Modify FSRProcessor for frame gen lifecycle

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`
- Modify: `src/client/java/com/fsr2mod/mixin/WindowMixin.java`

**Overview:** Wire up the frame generation life cycle: save previous frame data after FSR3 dispatch, add double-blit+swap in doPresent, and suppress the render loop's extra swap.

---

### A. FSR3Pipeline.java: Add `savePrevFrame`

Add after `resetFrameGen()`:

```java
public void savePrevFrame(int colorTex) {
    GL43C.glCopyImageSubData(colorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        displayWidth, displayHeight, 1);
    // dilatedDataTex is the current frame's dilated data (always set after dispatch)
    GL43C.glCopyImageSubData(dilatedDataTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevDilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        renderWidth, renderHeight, 1);
    hasPrevFrame = true;
}
```

### B. FSRProcessor.java: Add FG lifecycle fields

Add near the top fields:

```java
private long windowHandle;
private float prevHandSwingVal;
private boolean suppressSwap;
```

### C. FSRProcessor.java: Lazy-init window handle

In `onRenderBegin()`, add near the top:

```java
if (windowHandle == 0) {
    this.windowHandle = Minecraft.getInstance().getWindow().getHandle();
}
suppressSwap = false;
```

### D. FSRProcessor.java: Save previous frame after FSR3 dispatch

In `onRenderEnd()` inside the `isFsr3()` block, after `fsr2FrameCount++` and the success path, add:

```java
if (fsr3Pipeline != null) {
    fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
}
```

Place it right after `fsr2FrameCount++;` inside the `if (success)` block.

### E. FSRProcessor.java: Modify `doPresent()` for double-blit+swap

Replace the `doPresent()` method. Keep the existing FSR1 path unchanged. For FSR2/FSR3:
- Blit the real frame to default framebuffer (existing code)
- If isFsr3() and pipeline has previous frame:
  1. `GLFW.glfwSwapBuffers(windowHandle)` → show real frame
  2. Get hand swing from `Minecraft.getInstance().player.getAttackAnim(1.0f)` (or 0 if no player)
  3. Get depth texture from renderTarget
  4. Call `fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId, depthTex, handSwing, prevHandSwingVal)`
  5. Store `prevHandSwingVal = handSwing`
  6. Bind FG output texture to read FBO
  7. Blit FG output to default framebuffer
  8. `GLFW.glfwSwapBuffers(windowHandle)` → show FG frame
  9. Set `suppressSwap = true` to prevent the render loop's extra swap
- If NOT doing FG: let normal path run (single blit, no swap — render loop swap handles it)

```java
public void doPresent() {
    int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
    int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);

    if ((isFsr2() || isFsr3()) && fsr2OutputTexId != 0) {
        if (fsr1ReadFbo == -1) fsr1ReadFbo = GL43C.glGenFramebuffers();
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fsr1ReadFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, fsr2OutputTexId, 0);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        GL43C.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight,
                                GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);

        // Frame generation: present real frame, generate and present interpolated frame
        if (isFsr3() && fsr3Pipeline != null && fsr3Pipeline.hasPreviousFrame()) {
            // Show real frame
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(windowHandle);

            // Get hand swing data
            Minecraft client = Minecraft.getInstance();
            float handSwing = client.player != null ? client.player.getAttackAnim(1.0f) : 0.0f;

            // Get depth texture
            int depthTex = renderTarget != null && renderTarget.getDepthTexture() != null
                ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

            if (depthTex != -1) {
                fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId, depthTex,
                    handSwing, prevHandSwingVal);

                // Blit FG output to screen
                int fgTex = fsr3Pipeline.getFgOutputTexture();
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fsr1ReadFbo);
                GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, fgTex, 0);
                GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
                GL43C.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight,
                                        GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);

                // Show interpolated frame
                org.lwjgl.glfw.GLFW.glfwSwapBuffers(windowHandle);

                // Suppress render loop's swap
                suppressSwap = true;
            }

            prevHandSwingVal = handSwing;
        }
    } else if (config.version == FSRConfig.FSRVersion.FSR1) {
        // ... existing FSR1 code unchanged ...
    }

    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
    GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);

    pendingPresent = false;
}
```

### F. FSRProcessor.java: Add getter

```java
public boolean shouldSuppressSwap() {
    return suppressSwap;
}
```

### G. WindowMixin.java: Suppress swap when flag is set

Add a new injection that cancels swapBuffers when the processor says to suppress:

```java
package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "onFramebufferResize", at = @At("TAIL"))
    private void onFramebufferResize(long window, int width, int height, CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null) {
            processor.onResize(width, height);
        }
    }

    @Inject(method = "swapBuffers", at = @At("HEAD"), cancellable = true)
    private void onSwapBuffers(CallbackInfo ci) {
        FSRProcessor processor = FSRMod.getInstance().getProcessor();
        if (processor != null && processor.shouldSuppressSwap()) {
            ci.cancel();
        }
    }
}
```
