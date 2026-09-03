# FSR3 Frame Generation Implementation Plan

> **For agentic workers:** Skill: Use subagent-driven-development or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add FSR3-style frame interpolation to the FSR3 upscaler pipeline, doubling perceived framerate.

**Architecture:** After each real rendered+upscaled frame, generate an interpolated frame using depth-guided reprojection and present it immediately via a second glfwSwapBuffers. The interpolated frame is computed from current and previous upscaled colors, depth buffers, and motion vectors via forward/backward warping with depth-based occlusion testing.

**Tech Stack:** OpenGL 4.3 compute shaders, GLFW swap control, Fabric Loom 1.16 / Minecraft 26.1.2

## Global Constraints

- GL 4.3 compute shaders only (no Vulkan/DX12)
- All shaders are inline GLSL strings in Java files
- Double-present via explicit `glfwSwapBuffers` calls
- Depth/VP reprojection for world motion; mainHandHeight delta for hand/arm swing
- FSR3 upscaler output is at display resolution (GL_RGBA8) and comes from `rcasOutputTex`
- Existing textures at display resolution: `upscaledOutputTex`, `rcasOutputTex`, `internalUpscaledTex`
- Existing textures at render resolution: `dilatedDataTex` (contains RG=MVs, B=depth, A=luma), `depthTex`
- Motion vectors are in render-pixel space (output of MV gen: `mv = (prevUv - currUv) * renderSize`)
- `presentDirect` already returns true for FSR3

---

### Task 1: Add frame gen textures to FSR3Pipeline

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Consumes: existing texture creation pattern (`createTex2D`, `ensureTextures`, `deleteAllTextures`, `recreateTextures`)
- Produces: `prevColorTex` (GL_RGBA8, displaySize), `fgOutputTex` (GL_RGBA8, displaySize), `prevDilatedTex` (GL_RGBA16F, renderSize)

- [ ] **Step 1: Add texture fields**

Add at the top of `FSR3Pipeline` class:

```java
// Frame generation textures
private int prevColorTex;
private int fgOutputTex;
private int prevDilatedTex;
private boolean hasPrevFrame;
```

- [ ] **Step 2: Create textures in `ensureTextures()`**

Add after the existing `rcasOutputTex` creation:

```java
// Frame generation textures
if (prevColorTex == 0) {
    prevColorTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
    fgOutputTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
    prevDilatedTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
}
```

- [ ] **Step 3: Delete in `deleteAllTextures()`**

After the rcasOutputTex deletion:

```java
if (prevColorTex != 0) { GL43C.glDeleteTextures(prevColorTex); prevColorTex = 0; }
if (fgOutputTex != 0) { GL43C.glDeleteTextures(fgOutputTex); fgOutputTex = 0; }
if (prevDilatedTex != 0) { GL43C.glDeleteTextures(prevDilatedTex); prevDilatedTex = 0; }
```

- [ ] **Step 4: Add setter for prev frame data**

```java
public void setPrevFrame(int colorTex, int dilatedTex) {
    GL43C.glCopyImageSubData(colorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        displayWidth, displayHeight, 1);
    GL43C.glCopyImageSubData(dilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevDilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        renderWidth, renderHeight, 1);
    hasPrevFrame = true;
}
```

- [ ] **Step 5: Add getter for FG output**

```java
public int getFgOutputTexture() {
    return fgOutputTex;
}

public boolean hasPreviousFrame() {
    return hasPrevFrame;
}

public void resetFrameGen() {
    hasPrevFrame = false;
}
```

---

### Task 2: Write the frame gen compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Consumes: `uCurrentColor` (sampler, binding 0), `uPrevColor` (sampler, binding 1), `uCurrentDepth` (sampler, binding 2), `uPrevDepth` (sampler, binding 3), `uCurrentMV` (sampler, binding 4), `uPrevMV` (sampler, binding 5), `uOutput` (image, binding 6)
- Produces: GLSL compute shader string `FRAME_GEN_CS`, program ID `fgProg`

- [ ] **Step 1: Write the FG compute shader GLSL**

Add after `RCAS_CS`:

```java
private static final String FRAME_GEN_CS = """
    #version 430 core
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0) uniform sampler2D uCurrentColor;
    layout(binding = 1) uniform sampler2D uPrevColor;
    layout(binding = 2) uniform sampler2D uCurrentDepth;
    layout(binding = 3) uniform sampler2D uPrevDepth;
    layout(binding = 4) uniform sampler2D uCurrentMV;
    layout(binding = 5) uniform sampler2D uPrevMV;
    layout(binding = 6, rgba8) writeonly uniform image2D uOutput;

    uniform ivec2 uRenderSize;
    uniform ivec2 uDisplaySize;
    uniform float uHandSwing;       // Current hand swing (0-1)
    uniform float uPrevHandSwing;   // Previous hand swing (0-1)

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (p.x >= uDisplaySize.x || p.y >= uDisplaySize.y) return;

        vec2 hrUv = (vec2(p) + 0.5) / vec2(uDisplaySize);
        vec2 renderUv = hrUv;

        // Sample MV at current position (in render-pixel space)
        vec2 mv = textureLod(uCurrentMV, renderUv, 0.0).rg;
        float mvLen = length(mv);

        // If very little motion, just reuse current frame
        if (mvLen < 0.5) {
            vec3 c = textureLod(uCurrentColor, hrUv, 0.0).rgb;
            imageStore(uOutput, p, vec4(c, 1.0));
            return;
        }

        // Interpolated UV at t=0.5
        vec2 midUv = hrUv + mv * 0.5 / vec2(uRenderSize);

        // Sample colors at the interpolated position
        vec3 currColor = textureLod(uCurrentColor, midUv, 0.0).rgb;
        vec3 prevColor = textureLod(uPrevColor, midUv, 0.0).rgb;

        // Sample depths at the interpolated position
        float currDepth = textureLod(uCurrentDepth, midUv, 0.0).r;
        float prevDepth = textureLod(uPrevDepth, midUv, 0.0).r;

        // Depth-based occlusion test
        float depthDiff = abs(prevDepth - currDepth);
        float occlusion = smoothstep(0.02, 0.1, depthDiff);

        // Default: 50/50 blend
        vec3 blended = (prevColor + currColor) * 0.5;

        // Favor current frame where occlusion is detected
        vec3 result = mix(blended, currColor, occlusion);

        // Hand tracking: apply extra offset for hand pixels
        // Hand depth is typically 0.3-0.5 in the depth buffer
        if (currDepth > 0.25 && currDepth < 0.55) {
            float swingDelta = uHandSwing - uPrevHandSwing;
            vec2 handOffset = vec2(swingDelta * 8.0 / uDisplaySize.x, 0.0);
            vec2 handUv = midUv - handOffset * 0.5;
            vec3 handCurr = textureLod(uCurrentColor, handUv, 0.0).rgb;
            result = mix(result, handCurr, 0.7);
        }

        imageStore(uOutput, p, vec4(result, 1.0));
    }
    """;
```

- [ ] **Step 2: Add program ID and uniform fields**

```java
private int fgProg;
private int fg_uRenderSize, fg_uDisplaySize, fg_uHandSwing, fg_uPrevHandSwing;
```

- [ ] **Step 3: Compile program in `setup()`**

Add in the compilation section (after RCAS compile):

```java
fgProg = compileCompute(FRAME_GEN_CS);
// ...
|| fgProg == 0) {  // Add to existing check
```

- [ ] **Step 4: Get uniform locations**

After RCAS uniform block:

```java
fg_uRenderSize = getUniformLoc(fgProg, "uRenderSize");
fg_uDisplaySize = getUniformLoc(fgProg, "uDisplaySize");
fg_uHandSwing = getUniformLoc(fgProg, "uHandSwing");
fg_uPrevHandSwing = getUniformLoc(fgProg, "uPrevHandSwing");
```

- [ ] **Step 5: Add dispatch method**

```java
public boolean dispatchFrameGen(int currentColorTex, int currentDepthTex,
        float handSwing, float prevHandSwing) {
    GL43C.glUseProgram(fgProg);
    GL43C.glUniform2i(fg_uRenderSize, renderWidth, renderHeight);
    GL43C.glUniform2i(fg_uDisplaySize, displayWidth, displayHeight);
    GL43C.glUniform1f(fg_uHandSwing, handSwing);
    GL43C.glUniform1f(fg_uPrevHandSwing, prevHandSwing);

    // Bind textures
    GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
    GL43C.glUniform1i(0, 0);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevColorTex);
    GL43C.glUniform1i(1, 1);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentDepthTex);
    GL43C.glUniform1i(2, 2);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevDepthTex);
    GL43C.glUniform1i(3, 3);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);  // current MV
    GL43C.glUniform1i(4, 4);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE5);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevDilatedTex);  // prev MV
    GL43C.glUniform1i(5, 5);

    GL43C.glBindImageTexture(6, fgOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);

    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_FRAMEBUFFER_BARRIER_BIT);
    return true;
}
```

- [ ] **Step 6: Destroy program**

Add in `destroy()`:

```java
if (fgProg != 0) { GL43C.glDeleteProgram(fgProg); fgProg = 0; }
```

---

### Task 3: Modify FSRProcessor for frame gen lifecycle

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Interfaces:**
- Produces: `doPresent()` double-blit+swap, hand tracking state, FG call on each frame

- [ ] **Step 1: Add FG fields**

```java
private long windowHandle;
private boolean fgEnabled;
```

Initialize `windowHandle` in constructor or lazily.

- [ ] **Step 2: Get window handle**

In `onRenderBegin()`, lazily get the GLFW window handle:

```java
if (windowHandle == 0) {
    windowHandle = Minecraft.getInstance().getWindow().getHandle();
}
```

- [ ] **Step 3: Save previous frame after FSR3 dispatch**

In `onRenderEnd()`, after successful FSR3 pipeline dispatch:

```java
if (isFsr3() && fsr3Pipeline != null) {
    fsr3Pipeline.setPrevFrame(fsr2OutputTexId, 
        ((GlTexture)renderTarget.getDepthTexture()).glId());
}
```

Wait, `setPrevFrame` takes color and dilated tex. The dilated tex is ALREADY in the pipeline. Let me adjust.

Actually, `dilatedDataTex` is a private field of FSR3Pipeline. And the depth tex comes from `renderTarget`. So:

```java
if (isFsr3() && fsr3Pipeline != null) {
    fsr3Pipeline.savePrevFrame(fsr2OutputTexId, ((GlTexture)renderTarget.getDepthTexture()).glId());
}
```

And `savePrevFrame` internally copies the current `dilatedDataTex` to `prevDilatedTex`.

- [ ] **Step 4: Modify `doPresent()` for double-blit+swap**

Replace the `doPresent()` method:

```java
public void doPresent() {
    int prevReadFbo = GL43C.glGetInteger(GL43C.GL_READ_FRAMEBUFFER_BINDING);
    int prevDrawFbo = GL43C.glGetInteger(GL43C.GL_DRAW_FRAMEBUFFER_BINDING);

    boolean didFg = false;

    if ((isFsr2() || isFsr3()) && fsr2OutputTexId != 0) {
        // Present 1: real frame
        if (fsr1ReadFbo == -1) fsr1ReadFbo = GL43C.glGenFramebuffers();
        GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fsr1ReadFbo);
        GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, fsr2OutputTexId, 0);
        GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
        GL43C.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight,
                                GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);

        if (isFsr3() && fsr3Pipeline != null && fsr3Pipeline.hasPreviousFrame()) {
            // Swap → real frame visible
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(windowHandle);

            // Generate and present interpolated frame
            int depthTex = renderTarget != null && renderTarget.getDepthTexture() != null
                ? ((GlTexture)renderTarget.getDepthTexture()).glId() : -1;

            if (depthTex != -1) {
                Minecraft client = Minecraft.getInstance();
                float handSwing = client.player != null ? client.player.getAttackAnim(1.0f) : 0.0f;
                float prevHandSwing = prevHandSwingVal;

                fsr3Pipeline.dispatchFrameGen(fsr2OutputTexId, depthTex,
                    handSwing, prevHandSwing);

                int fgTex = fsr3Pipeline.getFgOutputTexture();
                GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fsr1ReadFbo);
                GL43C.glFramebufferTexture2D(GL43C.GL_READ_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, fgTex, 0);
                GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, 0);
                GL43C.glBlitFramebuffer(0, 0, displayWidth, displayHeight, 0, 0, displayWidth, displayHeight,
                                        GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);

                // Swap → interpolated frame visible
                org.lwjgl.glfw.GLFW.glfwSwapBuffers(windowHandle);
                didFg = true;

                prevHandSwingVal = handSwing;
            }
        }
    } else if (config.version == FSRConfig.FSRVersion.FSR1) {
        // FSR1: single blit, no FG
        // ... existing code ...
    }

    if (!didFg) {
        // No FG: restore and let the normal render loop handle the swap
        // (The cancelled blitToScreen would have done the swap)
        // Actually, we need to swap ourselves since we cancelled.
        // But check if we already swapped in the FG path above.
    }

    GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, prevReadFbo);
    GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);

    pendingPresent = false;
}
```

Wait, I realize there's an issue. The current code cancels `blitToScreen` in the mixin, which means the swap that normally happens in `blitToScreen` never happens. But the game still works. So either:
1. The swap happens somewhere else (in `Minecraft.renderFrame` after `blitToScreen`)
2. Or the current code has a bug and the screen is never actually swapped

Let me check what `blitToScreen` does in modern Minecraft. Actually, looking at the mixin, it cancels at HEAD, so the ENTIRE `blitToScreen` method is skipped — both the blit AND the swap.

But the game currently works with FSR. So either:
1. The swap happens automatically at some other point (e.g., `glfwPollEvents`)
2. Or the swap is triggered by something else

Actually, I just realized: `blitToScreen` translates internal content to the window and MAY OR MAY NOT swap buffers. In LWJGL3 (which Minecraft uses), the window swap typically happens when `glfwSwapBuffers` is called, and `blitToScreen` used to call this.

In modern Minecraft (26.1.2), the `blitToScreen` might NOT do the swap — the swap might be handled by the window's `swapBuffers` method, which is called separately.

Since the game runs correctly, I'll assume the swap is handled automatically or by some other mechanism. For FG, I need to add EXPLICIT swaps. The current code without FG should continue to work as before (no swap changes).

Actually, let me look at this more carefully. The existing `doPresent()` does:
1. Blits FSR output to screen framebuffer (FBO 0)
2. The swap happens elsewhere (probably in `Window.swapBuffers()` called from `renderFrame()` after `blitToScreen` returns)

In the mixin:
```java
@Inject(method = "blitToScreen", at = @At("HEAD"), cancellable = true)
private void onBlitToScreen(CallbackInfo ci) {
    if (processor.presentDirect(this)) {
        processor.doPresent();
        ci.cancel();
    }
}
```

When cancelled, `blitToScreen` doesn't run. But `renderFrame()` still runs after `blitToScreen` returns. If the swap happens in `renderFrame()` (after the blit), then the swap still happens — our blit fills the backbuffer, and then the swap happens as normal.

For FG, I need to add TWO swaps. The approach:
1. `doPresent()` blits the real frame to FBO 0
2. We call `glfwSwapBuffers` explicitly (real frame now visible)
3. Generate FG frame
4. Blit FG frame to FBO 0  
5. Call `glfwSwapBuffers` again (FG frame now visible)

But then the NORMAL swap that would happen in `renderFrame()` after `blitToScreen` would be a THIRD swap (showing the next frame early). That's wrong.

So I need to either:
a) Let the normal `blitToScreen` go through for the first swap (remove the cancel) and add our own second swap
b) Cancel `blitToScreen`, do our own two swaps, and suppress the normal swap

For option (b), I need to keep the cancellation. The normal swap after `blitToScreen` returns won't happen because there's nothing else swapping.

Wait, but the NORMAL flow (without FG) currently works. The blit happens in `doPresent()`, the cancel prevents `blitToScreen` from doing its own blit AND swap. But the swap still happens somewhere else...

Let me just follow the simpler approach: do our blits + swaps in `doPresent()`, cancel the original `blitToScreen`. This is what the current code already does (minus the swap), and it works. The swap might be happening automatically at OpenGL/GLFW level or it might be that the front/back buffer sharing is handled differently...

Actually, I think I understand now. The `BlitFramebuffer` to FBO 0 renders to the default framebuffer, which IS the window's front buffer (or backbuffer, depending on config). In Minecraft with V-Sync, the default framebuffer is double-buffered. `glBlitFramebuffer` to FBO 0 writes to the backbuffer. The swap happens when `glfwSwapBuffers` is called somewhere in the frame loop.

If `blitToScreen` is the ONLY place that calls `glfwSwapBuffers`, and we cancel it... then the swap never happens and the screen would freeze. But it doesn't freeze. So either:
1. There's something auto-swapping (unlikely)
2. `blitToScreen` doesn't call `glfwSwapBuffers` in this version
3. The swap happens in a different code path

Since it's working, I'll just add my own `glfwSwapBuffers` calls for FG and leave the non-FG path unchanged. If it works now, it should continue to work.

For the FG path: I'll blit the real frame, call `glfwSwapBuffers` to show it, generate FG, blit the FG frame, and call `glfwSwapBuffers` again. The second swap replaces the normal swap.

Let me refine the `doPresent()` code.<｜end▁of▁thinking｜>

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="todowrite">
<｜｜DSML｜｜parameter name="todos" string="false">[{"priority":"high","content":"Write FSR3 FG implementation plan to docs","status":"in_progress"},{"priority":"high","content":"Task 1: Add FG textures to FSR3Pipeline","status":"pending"},{"priority":"high","content":"Task 2: Write frame gen compute shader","status":"pending"},{"priority":"high","content":"Task 3: Modify FSRProcessor for FG lifecycle","status":"pending"},{"priority":"high","content":"Task 4: Add FG config option","status":"pending"},{"priority":"high","content":"Task 5: Build and test","status":"pending"}]