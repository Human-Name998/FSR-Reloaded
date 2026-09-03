# FSR4 Phase 2 — FSRProcessor Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `mainRenderTarget`'s color texture with VK-shared memory, eliminating `glCopyImageSubData` per frame.

**Architecture:** Keep original RenderTarget instance alive. Use `@Accessor` mixins to inject shared `GlTexture` + `GlTextureView` into its `colorTexture`/`colorTextureView` fields. FSR pipelines accept an external output texture ID — when set, RCAS writes directly to shared memory. `SharedTextureManager` handles VK allocation, GL import, and lifecycle.

**Tech Stack:** Java 25, Fabric Loom 1.16-SNAPSHOT, LWJGL 3, GL43C, C++20 JNI

## Global Constraints

- Access widener file uses `v1 named` format
- GlTexture constructor must be widened: `(ILjava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;IIIII)V`
- GlTextureView constructor must be widened: `(Lcom/mojang/blaze3d/opengl/GlTexture;II)V`
- GlTexture field `id I` must be widened (accessible for ID overwrite safeguard)
- All platform-specific C++ code guarded by `#ifdef _WIN32`
- JNI methods use `Java_com_fsr2mod_vulkan_VulkanInterop_` prefix
- `getAllocationSize` returns VkDeviceSize cast to `jlong`
- Native DLL build is optional (graceful skip when CMake/Vulkan SDK absent)
- No new config options — shared memory is auto-selected based on capability
- FSR1Pipeline already accepts `outputTex` as dispatch parameter — when mainRT's color texture is shared, FSR1 writes directly to shared memory with no pipeline change

---
### Task 1: Access Widener + Mixin Accessors

**Files:**
- Create: `src/main/resources/fsr-reloaded.accesswidener`
- Modify: `src/main/resources/fabric.mod.json`
- Create: `src/client/java/com/fsr2mod/mixin/RenderTargetAccessor.java`
- Create: `src/client/java/com/fsr2mod/mixin/GlTextureIdAccessor.java`

**Interfaces:**
- Produces: `RenderTargetAccessor` (`setColorTexture(GpuTexture)`, `setColorTextureView(GpuTextureView)`) — consumed by Task 3
- Produces: `GlTextureIdAccessor` (`setId(int)`) — consumed by Task 3

- [ ] **Step 1: Create access widener file**

Write `src/main/resources/fsr-reloaded.accesswidener`:

```
accessWidener v1 named
accessible method com/mojang/blaze3d/opengl/GlTexture <init> (ILjava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;IIIII)V
accessible method com/mojang/blaze3d/opengl/GlTextureView <init> (Lcom/mojang/blaze3d/opengl/GlTexture;II)V
accessible field  com/mojang/blaze3d/opengl/GlTexture id I
```

- [ ] **Step 2: Update fabric.mod.json**

Read the file, add `"accessWidener": "fsr-reloaded.accesswidener"` after the `"mixins"` entry.

- [ ] **Step 3: Create RenderTargetAccessor mixin**

Write `src/client/java/com/fsr2mod/mixin/RenderTargetAccessor.java`:

```java
package com.fsr2mod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    void setColorTexture(GpuTexture tex);

    @Accessor("colorTextureView")
    void setColorTextureView(GpuTextureView view);
}
```

- [ ] **Step 4: Create GlTextureIdAccessor mixin**

Write `src/client/java/com/fsr2mod/mixin/GlTextureIdAccessor.java`:

```java
package com.fsr2mod.mixin;

import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlTexture.class)
public interface GlTextureIdAccessor {
    @Accessor("id")
    void setId(int id);
}
```

- [ ] **Step 5: Build to verify Java compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/fsr-reloaded.accesswidener src/main/resources/fabric.mod.json src/client/java/com/fsr2mod/mixin/RenderTargetAccessor.java src/client/java/com/fsr2mod/mixin/GlTextureIdAccessor.java
git commit -m "Phase 2 Task 1: Access widener + mixin accessors for shared texture injection"
```

---
### Task 2: Vulkan Memory Size Tracking + JNI Enhancements

**Files:**
- Modify: `src/native/fsrvk/vulkan_memory.h`
- Modify: `src/native/fsrvk/vulkan_memory.cpp`
- Modify: `src/native/fsrvk/jni_bridge.cpp`
- Modify: `src/client/java/com/fsr2mod/vulkan/VulkanInterop.java`

**Interfaces:**
- Consumes: `FsrSharedAllocation` struct (existing)
- Produces: `VulkanInterop.getWin32Handle(long) → long`, `VulkanInterop.getAllocationSize(long) → long` — consumed by Task 3

- [ ] **Step 1: Add allocationSize to FsrSharedAllocation**

In `src/native/fsrvk/vulkan_memory.h`, add `VkDeviceSize allocationSize;` to the struct.

```cpp
struct FsrSharedAllocation {
    VkImage image;
    VkDeviceMemory memory;
    HANDLE win32Handle;
    uint32_t width;
    uint32_t height;
    VkDeviceSize allocationSize;
};
```

- [ ] **Step 2: Store allocationSize in vulkan_memory.cpp**

In `src/native/fsrvk/vulkan_memory.cpp`, find the line after `vkGetImageMemoryRequirements(...)` and before `int memType = findMemoryType(...)`. Add:

```cpp
alloc->allocationSize = memReqs.size;
```

- [ ] **Step 3: Add new JNI functions to jni_bridge.cpp**

In `src/native/fsrvk/jni_bridge.cpp`, before the closing `} // extern "C"`, add:

```cpp
JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getWin32Handle(
    JNIEnv* env, jclass /*clazz*/, jlong allocPtr)
{
    if (!allocPtr) return 0;
    auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(allocPtr);
    return reinterpret_cast<jlong>(alloc->win32Handle);
}

JNIEXPORT jlong JNICALL
Java_com_fsr2mod_vulkan_VulkanInterop_getAllocationSize(
    JNIEnv* env, jclass /*clazz*/, jlong allocPtr)
{
    if (!allocPtr) return 0;
    auto* alloc = reinterpret_cast<fsr::FsrSharedAllocation*>(allocPtr);
    return static_cast<jlong>(alloc->allocationSize);
}
```

- [ ] **Step 4: Add native method declarations to VulkanInterop.java**

In `src/client/java/com/fsr2mod/vulkan/VulkanInterop.java`, after `terminate()`:

```java
public static native long getWin32Handle(long allocPtr);
public static native long getAllocationSize(long allocPtr);
```

- [ ] **Step 5: Build to verify compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/native/fsrvk/vulkan_memory.h src/native/fsrvk/vulkan_memory.cpp src/native/fsrvk/jni_bridge.cpp src/client/java/com/fsr2mod/vulkan/VulkanInterop.java
git commit -m "Phase 2 Task 2: VK memory size tracking + getWin32Handle/getAllocationSize JNI"
```

---
### Task 3: SharedTextureManager

**Files:**
- Create: `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`

**Interfaces:**
- Consumes: `VulkanInterop` (Task 2), `RenderTargetAccessor` (Task 1), `GlTextureIdAccessor` (Task 1), `GlTexture`/`GlTextureView` (access widened Task 1)
- Produces: `SharedTextureManager` — consumed by Task 5

- [ ] **Step 1: Write SharedTextureManager.java**

Create `src/client/java/com/fsr2mod/fsr/SharedTextureManager.java`:

```java
package com.fsr2mod.fsr;

import com.fsr2mod.mixin.GlTextureIdAccessor;
import com.fsr2mod.mixin.RenderTargetAccessor;
import com.fsr2mod.vulkan.VulkanInterop;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.GL43C;
import java.nio.LongBuffer;

public class SharedTextureManager {

    private long allocPtr;
    private long memoryObject;
    private int sharedGlTexId;
    private GlTexture glTexture;
    private GlTextureView glView;
    private int width;
    private int height;

    public void create(int w, int h) {
        if (isValid()) destroy();

        allocPtr = VulkanInterop.getSharedHandle(w, h);
        if (allocPtr == 0) throw new RuntimeException("getSharedHandle returned 0");

        long win32Handle = VulkanInterop.getWin32Handle(allocPtr);
        if (win32Handle == 0) {
            VulkanInterop.releaseSharedHandle(allocPtr);
            allocPtr = 0;
            throw new RuntimeException("getWin32Handle returned 0");
        }

        long allocSize = VulkanInterop.getAllocationSize(allocPtr);
        long expectedMin = (long) w * h * 4;
        if (allocSize < expectedMin) {
            VulkanInterop.releaseSharedHandle(allocPtr);
            allocPtr = 0;
            throw new RuntimeException("VK allocation size " + allocSize + " < expected " + expectedMin);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer buf = stack.mallocLong(1);
            GL43C.glCreateMemoryObjectsEXT(buf);
            memoryObject = buf.get(0);
        }
        if (memoryObject == 0) {
            VulkanInterop.releaseSharedHandle(allocPtr);
            allocPtr = 0;
            throw new RuntimeException("glCreateMemoryObjectsEXT failed");
        }

        GL43C.glImportMemoryWin32HandleEXT((int) memoryObject, allocSize,
                GL43C.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle);

        sharedGlTexId = GL43C.glGenTextures();
        GL43C.glTextureStorageMem2DEXT(sharedGlTexId, 1, GL43C.GL_RGBA8, w, h, memoryObject, 0);

        int usage = GpuTexture.USAGE_COLOR_ATTACHMENT | GpuTexture.USAGE_TEXTURE_SAMPLER;
        glTexture = new GlTexture(usage, "fsr-shared", TextureFormat.RGBA8, w, h, 1, 1, sharedGlTexId);

        if (glTexture.id != sharedGlTexId) {
            ((GlTextureIdAccessor) glTexture).setId(sharedGlTexId);
        }

        glView = new GlTextureView(glTexture, 0, 1);
        this.width = w;
        this.height = h;
    }

    public void attachToMainRT(RenderTarget mainRT, RenderTargetAccessor accessor) {
        accessor.setColorTexture(glTexture);
        accessor.setColorTextureView(glView);
    }

    public void attachToPipeline(FSR2Pipeline p) {
        p.setExternalOutputTexture(sharedGlTexId);
    }

    public void attachToPipeline(FSR3Pipeline p) {
        p.setExternalOutputTexture(sharedGlTexId);
    }

    // FSR1Pipeline is handled automatically: dispatch() takes outputTex as a parameter,
    // and when mainRT's color texture IS the shared texture, mainTex IS sharedGlTexId.

    public void destroy() {
        if (glView != null && !glView.isClosed()) glView.close();
        if (glTexture != null && !glTexture.isClosed()) glTexture.close();
        if (memoryObject != 0) GL43C.glDeleteMemoryObjectsEXT((int) memoryObject);
        if (allocPtr != 0) VulkanInterop.releaseSharedHandle(allocPtr);
        allocPtr = 0;
        memoryObject = 0;
        sharedGlTexId = 0;
        glTexture = null;
        glView = null;
        width = 0;
        height = 0;
    }

    public int getSharedGlTexId() { return sharedGlTexId; }
    public boolean isValid() { return allocPtr != 0; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
```

- [ ] **Step 2: Build to verify Java compilation**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/SharedTextureManager.java
git commit -m "Phase 2 Task 3: SharedTextureManager — VK-shared GL texture lifecycle"
```

---
### Task 4: FSR Pipeline External Output Support

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java`
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Produces: `FSR2Pipeline.setExternalOutputTexture(int)`, `FSR3Pipeline.setExternalOutputTexture(int)` — consumed by Task 5

FSR1Pipeline writes to the `outputTex` parameter passed to `dispatch()`. When mainRT is injected with the shared texture, the `mainTex` parameter IS the shared ID. No FSR1 change needed.

- [ ] **Step 1: Modify FSR2Pipeline**

Read `FSR2Pipeline.java`. Make these 4 edits:

1. Add field declaration (near other private fields):
```java
private int externalOutputTex = -1;
```

2. Add setter method:
```java
public void setExternalOutputTexture(int glTexId) {
    this.externalOutputTex = glTexId;
}
```

3. Replace `GL43C.glBindImageTexture(14, upscaledOutputTex, ...)` (line ~1154):
```java
GL43C.glBindImageTexture(14, externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
```

4. Replace `GL43C.glBindImageTexture(0, upscaledOutputTex, ...)` in RCAS pass (line ~1166):
```java
GL43C.glBindImageTexture(0, externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex, 0, false, 0, GL43C.GL_READ_WRITE, GL43C.GL_RGBA8);
```

5. Replace `lastOutputTex = upscaledOutputTex;` (line ~1173):
```java
lastOutputTex = externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex;
```

- [ ] **Step 2: Modify FSR3Pipeline**

Same 5 edits pattern as Step 1 in `FSR3Pipeline.java`:

1. Add `private int externalOutputTex = -1;`
2. Add `setExternalOutputTexture(int glTexId)` method
3. Replace `glBindImageTexture(14, upscaledOutputTex, ...)` (line ~1779)
4. Replace `glBindImageTexture(0, upscaledOutputTex, ...)` in RCAS (line ~1791)
5. Replace `lastOutputTex = upscaledOutputTex;` (line ~1798)

- [ ] **Step 3: Build**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java
git commit -m "Phase 2 Task 4: FSR2/3 pipelines accept external output texture"
```

---
### Task 5: FSRProcessor Integration

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

**Interfaces:**
- Consumes: `SharedTextureManager` (Task 3), `RenderTargetAccessor` (Task 1), `VulkanInterop` (Phase 1)

- [ ] **Step 1: Read and understand current FSRProcessor.java**

Read the full file (~972 lines). Pay attention to:
- `onRenderBegin()` — render target initialization flow
- `onSceneRendered()` — FSR dispatch + copy to mainRT
- `onRenderEnd()` — fallback copy path
- `onResize()` — resize handling
- `destroy()` — cleanup

- [ ] **Step 2: Add new fields**

Add after `private boolean jitterAppliedThisFrame;` (~line 87):
```java
private SharedTextureManager sharedTexMgr;
private boolean sharedMemoryActive;
```

- [ ] **Step 3: Add checkVulkanAvailable helper**

Add before `public void setProjection(...)` (~line 146):
```java
private static boolean checkVulkanAvailable() {
    try {
        String info = VulkanInterop.getDeviceInfo();
        return info != null && !info.isEmpty();
    } catch (UnsatisfiedLinkError | Exception e) {
        return false;
    }
}
```

- [ ] **Step 4: Setup shared texture in onRenderBegin**

In `onRenderBegin()`, after the render target setup and proxy target creation (after line ~280 where `proxyTarget` is set), add:

```java
if (checkVulkanAvailable()) {
    boolean needsRecreate = sharedTexMgr == null || !sharedTexMgr.isValid()
        || sharedTexMgr.getWidth() != displayWidth || sharedTexMgr.getHeight() != displayHeight;
    if (needsRecreate) {
        if (sharedTexMgr != null) sharedTexMgr.destroy();
        try {
            if (sharedTexMgr == null) sharedTexMgr = new SharedTextureManager();
            sharedTexMgr.create(displayWidth, displayHeight);
            sharedTexMgr.attachToMainRT(mainRenderTarget,
                (RenderTargetAccessor) mainRenderTarget);
            sharedMemoryActive = true;
        } catch (Exception e) {
            FSRMod.LOGGER.warn("Shared memory unavailable, falling back: {}", e.getMessage());
            if (sharedTexMgr != null) sharedTexMgr.destroy();
            sharedMemoryActive = false;
        }
    }
} else {
    sharedMemoryActive = false;
}
```

- [ ] **Step 5: Attach shared texture to pipeline before dispatch in onSceneRendered**

In `onSceneRendered`, before each pipeline dispatch call, add shared texture binding.

FSR1Pipeline is handled automatically — `dispatch()` receives `mainTex` which is the shared texture ID (from the injected mainRT).

Before FSR2 dispatch (after line ~406 where the FSR2 dispatch begins):
```java
if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
    sharedTexMgr.attachToPipeline(fsr2Pipeline);
}
```

Before FSR3 dispatch (after line ~456 where the FSR3 dispatch begins):
```java
if (sharedMemoryActive && sharedTexMgr != null && sharedTexMgr.isValid()) {
    sharedTexMgr.attachToPipeline(fsr3Pipeline);
}
```

- [ ] **Step 6: Skip glCopyImageSubData when shared memory active**

In `onSceneRendered`, replace the copy block (`if (fsr2OutputTexId != 0 && mainRenderTarget != null)` starting ~line 506):

```java
// Copy upscaled output to mainRenderTarget
if (sharedMemoryActive) {
    // FSR pipeline wrote directly to shared mainRT via externalOutputTex
    // No copy needed. Still track FG state for alternating frames.
    if (isFrameGen() && fsr3Pipeline != null) {
        fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
    }
    fgFlipState = !fgFlipState;
} else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
    // ... existing copy code (glMemoryBarrier + glCopyImageSubData) unchanged ...
}
```

In `onRenderEnd`, replace the copy block (`if (fsr2OutputTexId != 0 && mainRenderTarget != null)` starting ~line 722):

```java
if (sharedMemoryActive) {
    if (isFrameGen() && fsr3Pipeline != null) {
        fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
    }
    fgFlipState = !fgFlipState;
} else if (fsr2OutputTexId != 0 && mainRenderTarget != null) {
    // ... existing onRenderEnd copy code unchanged ...
}
```

- [ ] **Step 7: Add resize cleanup in onResize**

In `onResize()` (line ~788), after `pipelineReady = false;` or at the end of the method, add:

```java
if (sharedTexMgr != null) {
    sharedTexMgr.destroy();
    sharedMemoryActive = false;
}
```

- [ ] **Step 8: Add cleanup in destroy()**

In `destroy()` (line ~853), at the end, add:

```java
if (sharedTexMgr != null) {
    sharedTexMgr.destroy();
    sharedTexMgr = null;
}
sharedMemoryActive = false;
```

- [ ] **Step 9: Build to verify**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "Phase 2 Task 5: FSRProcessor shared memory integration — skip glCopyImageSubData when VK-shared mainRT active"
```
