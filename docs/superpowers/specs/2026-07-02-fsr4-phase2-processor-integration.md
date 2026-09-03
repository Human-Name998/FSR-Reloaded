# FSR4 Phase 2 — FSRProcessor Integration

## Goal

Replace `mainRenderTarget`'s color texture backing with a VK–GL shared memory texture so FSR compute shaders write directly into the texture that Minecraft presents, eliminating the per-frame `glCopyImageSubData` from FSR output to mainRT. No semaphore sync required in Phase 2 — only GL touches the texture during rendering.

## Architecture

```
Renderer (LevelRenderer → Minecraft)
  └─ renders to renderTarget (scaled resolution)
     └─ FSR pipeline dispatch
        └─ compute shader writes to shared GL texture
           └─ this texture IS the backing for mainRenderTarget.colorTexture
              └─ GUI renders directly on top (full res)
                 └─ swapBuffers() presents
                    └─ No glCopyImageSubData
                       └─ No glFinish() (GL-only this phase)
```

The shared texture lifecycle:

```
Vulkan: VkImage → VkDeviceMemory → export Win32 HANDLE
  └─ Java: glImportMemoryWin32HandleEXT → texture backed by shared memory
     └─ Wrapped in GlTexture + GlTextureView
        └─ Injected into existing mainRenderTarget via @Accessor
           └─ FSR pipeline binds it as RCAS image store output
              └─ Minecraft uses it as color attachment 0
```

## Key Decisions (Edge Cases)

### 1. No-Go: `setMainRenderTarget` swap

Swapping the entire `mainRenderTarget` via `MinecraftClientAccessor.setMainRenderTarget()` breaks engine resize events (`mainRT.resize(w, h)` hits `SharedRenderTarget.resize` which doesn't own the original FBO/depth) and breaks mods that call `client.getFramebuffer()` and cache the reference.

**Fix**: Keep the original `RenderTarget` instance alive. Use `@Accessor` mixins to inject shared `GlTexture` + `GlTextureView` into its `colorTexture` / `colorTextureView` fields. The depth texture stays as-is.

### 2. GlTexture constructor ID overwrite risk

If `GlTexture(int usage, String label, TextureFormat format, int width, int height, int depthOrLayers, int mipLevels, int id)` internally calls `glGenTextures()` before assigning `this.id`, the passed-in `id` parameter is overwritten.

**Fix**: Add `GlTextureAccessor` mixin with `@Accessor("id")` to overwrite the field after construction with the correct shared texture ID. Verified at construction time by comparing before/after ID values.

### 3. `glTextureStorageMem2DEXT` size must match

`glTextureStorageMem2DEXT` requires the allocation size parameter to exactly match (or be less than) the VkDeviceMemory allocation. The FsrSharedAllocation does not currently expose the allocation size to Java.

**Fix**: Add `getAllocationSize(long allocPtr)` JNI method returning `alloc->allocationSize` (from `VkMemoryRequirements.size`). Java-side sanity check asserts `expectedSize >= w * h * 4` before importing.

## Components

### 1. Access Widener (`fsr-reloaded.accesswidener`)

Make `GlTexture` and `GlTextureView` constructors accessible plus `GlTexture.id` field for the ID overwrite safeguard.

```
accessWidener v1 named
accessible method com/mojang/blaze3d/opengl/GlTexture <init> (ILjava/lang/String;Lcom/mojang/blaze3d/textures/TextureFormat;IIIII)V
accessible method com/mojang/blaze3d/opengl/GlTextureView <init> (Lcom/mojang/blaze3d/opengl/GlTexture;II)V
accessible field  com/mojang/blaze3d/opengl/GlTexture id I
```

Add `"accessWidener": "fsr-reloaded.accesswidener"` to `fabric.mod.json`.

### 2. Mixin Accessors

**`RenderTargetAccessor`** — swap color texture on the existing mainRT:

```java
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    void setColorTexture(GpuTexture tex);

    @Accessor("colorTextureView")
    void setColorTextureView(GpuTextureView view);
}
```

**`GlTextureIdAccessor`** — overwrite internal ID if constructor auto-generates:

```java
@Mixin(GlTexture.class)
public interface GlTextureIdAccessor {
    @Accessor("id")
    void setId(int id);
}
```

### 3. `VulkanInterop` Enhancements

Two new JNI methods.

```java
/** Extract Win32 HANDLE from FsrSharedAllocation. Returns 0 on null/error. */
public static native long getWin32Handle(long allocPtr);

/** Extract VkMemoryRequirements.size from FsrSharedAllocation. Returns 0 on null. */
public static native long getAllocationSize(long allocPtr);
```

Implementation in `jni_bridge.cpp`:

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

**`allocationSize` field** must be added to `FsrSharedAllocation` struct in `vulkan_memory.h`:

```cpp
struct FsrSharedAllocation {
    VkImage image;
    VkDeviceMemory memory;
    HANDLE win32Handle;        // Windows handle for import
    uint32_t width;
    uint32_t height;
    VkDeviceSize allocationSize; // from VkMemoryRequirements.size
};
```

Update `vulkan_memory.cpp` to store `alloc->allocationSize = memReqs.size` after `vkGetImageMemoryRequirements`.

### 4. `SharedTextureManager` (`src/client/java/.../fsr/SharedTextureManager.java`)

A plain class (NOT extending RenderTarget) that creates VK-shared GL textures and wraps them in Minecraft-compatible objects.

```java
public class SharedTextureManager {
    private long allocPtr;         // FsrSharedAllocation*
    private long memoryObject;     // GL memory object from glCreateMemoryObjectsEXT
    private int sharedGlTexId;     // GL texture ID from glTextureStorageMem2DEXT
    private GlTexture glTexture;   // Minecraft wrapper
    private GlTextureView glView;  // Minecraft view
    private int width, height;

    /** Create shared VK texture, import into GL, wrap. Throws on failure. */
    public void create(int w, int h) { ... }

    /** Inject the shared GlTexture + GlTextureView into mainRenderTarget. */
    public void attachToMainRT(RenderTarget mainRT,
                                RenderTargetAccessor accessor) {
        accessor.setColorTexture(glTexture);
        accessor.setColorTextureView(glView);
    }

    /** Set FSR pipeline output target to our shared texture ID. */
    public void attachToPipeline(FSR2Pipeline p) { p.setExternalOutputTexture(sharedGlTexId); }
    public void attachToPipeline(FSR3Pipeline p) { p.setExternalOutputTexture(sharedGlTexId); }
    public void attachToPipeline(FSR1Pipeline p) { p.setExternalOutputTexture(sharedGlTexId); }

    /** Full teardown: GL texture → GL memory obj → VK release. +44  */
    public void destroy() { ... }

    public int getSharedGlTexId() { return sharedGlTexId; }
    public boolean isValid() { return allocPtr != 0; }
}
```

**`create(int w, int h)` details:**

```
1. VulkanInterop.getSharedHandle(w, h) → allocPtr (jlong)
2. getWin32Handle(allocPtr) → win32Handle (jlong)
3. getAllocationSize(allocPtr) → allocSize (jlong)
4. Sanity check: allocSize >= w * h * 4
5. glCreateMemoryObjectsEXT(buf) → memoryObject
6. glImportMemoryWin32HandleEXT(memoryObject, allocSize, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle)
7. glGenTextures() → sharedGlTexId
8. glTextureStorageMem2DEXT(sharedGlTexId, 1, GL_RGBA8, w, h, memoryObject, 0)
9. new GlTexture(COLOR_ATTACHMENT|SAMPLER, "fsr-shared", RGBA8, w, h, 1, 1, sharedGlTexId)
   → SAFEGUARD: read glTexture.id after construction, if != sharedGlTexId,
     use GlTextureIdAccessor.setId(sharedGlTexId) to correct it
10. new GlTextureView(glTexture, 0, 1) → glView
11. Store w, h
```

**`destroy()` details:**

```
1. If glView != null: glView.close()        // decrements ref count
2. If glTexture != null: glTexture.close()  // glDeleteTexture
3. If memoryObject != 0: glDeleteMemoryObjectsEXT(memoryObject)
4. If allocPtr != 0: VulkanInterop.releaseSharedHandle(allocPtr)
5. Reset all fields to 0/null
```

### 5. `FSRProcessor` Integration

**New fields:**
```java
private SharedTextureManager sharedTexMgr;
private boolean sharedMemoryActive;
```

**`onRenderBegin`:**
- If VK available and not yet created: try `sharedTexMgr.create(displayW, displayH)`
  - On success: `sharedTexMgr.attachToMainRT(mainRT, renderTargetAccessor)`, `sharedMemoryActive = true`
  - On failure: fall through to existing path (`sharedMemoryActive = false`)
- If existing and dimensions changed: `sharedTexMgr.destroy()`, `sharedTexMgr.create(newW, newH)`, re-inject

**`onSceneRendered`:**
- When `sharedMemoryActive`:
  - `sharedTexMgr.attachToPipeline(fsr2/3Pipeline)` before dispatch
  - FSR writes directly to shared texture (mainRT backing)
  - Skip `glCopyImageSubData` entirely
  - For FG: attach shared texture as FG output too
- When `!sharedMemoryActive`: existing `glCopyImageSubData` path

**`onResize`:**
- `sharedTexMgr.destroy()` → `sharedMemoryActive = false`
- Next frame's `onRenderBegin` recreates at new size

**`destroy`:**
- `sharedTexMgr.destroy()`
- `sharedMemoryActive = false`

### 6. FSR Pipeline Enhancement

Each pipeline (`FSR2Pipeline`, `FSR3Pipeline`) gets:

```java
private int externalOutputTex = -1;

public void setExternalOutputTexture(int glTexId) {
    this.externalOutputTex = glTexId;
}
```

The external output replaces `upscaledOutputTex` — the final RGBA8 output that RCAS writes to. The internal HDR textures (`internalUpscaledTex`, `internalUpscaledWriteTex`) remain `R11F_G11F_B10F` and are not swapped.

In dispatch, the RCAS output binding uses `externalOutputTex` instead of the internal `upscaledOutputTex`. If `externalOutputTex == -1`, use the internal one (fallback path).

The RCAS binding in FSR2Pipeline changes from:
```java
GL43C.glBindImageTexture(14, upscaledOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
```
To:
```java
int targetTex = externalOutputTex != -1 ? externalOutputTex : upscaledOutputTex;
GL43C.glBindImageTexture(14, targetTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);
```

Same pattern for FSR3. Both formats are `GL_RGBA8` so no compatibility issue.

For FG: set `fgOutputTex` to the external output too — FG interpolates directly into the shared texture.

### 7. `FSR1Pipeline` Integration

FSR1 writes to `mainTex` via blit or EASU+RCAS. When `sharedMemoryActive`:
- EASU compute: bind shared texture as output (same as FSR2/3)
- Blit path: blit directly to shared texture
- RCAS: read from shared texture, write to shared texture (in-place sharpen)

### 8. Vulkan Memory Size Tracking

Add `VkDeviceSize allocationSize` to `FsrSharedAllocation` struct. Store immediately after `vkGetImageMemoryRequirements` in `vulkan_memory.cpp`:

```cpp
VkMemoryRequirements memReqs;
vkGetImageMemoryRequirements(dev.device(), alloc->image, &memReqs);
alloc->allocationSize = memReqs.size;  // ← NEW
```

The rest of the function continues unchanged. This field feeds `getAllocationSize()` JNI and the `glTextureStorageMem2DEXT` size sanity check.

## Data Flow (Normal Frame)

```
1. onRenderBegin (first frame or resize):
   - create SharedTextureManager(w, h)
   - attachToMainRT(mainRT, accessor)
   - sharedMemoryActive = true

2. LevelRenderer renders scene:
   - writes to renderTarget (scaled TextureTarget)

3. onSceneRendered:
   - attachToPipeline(fsrPipeline)
   - FSR pipeline dispatch
   - RCAS writes to shared GL texture (= mainRT's color backing)
   - No glCopyImageSubData
   - FG writes to same shared texture

4. GUI renders:
   - directly to mainRT (which is backed by shared texture)

5. onRenderEnd:
   - No copy, no cleanup
   - insideFrame = false

6. swapBuffers:
   - Presents mainRT contents (shared texture on screen)
```

## Resize Lifecycle

```
1. glFinish()
2. SharedTextureManager.destroy():
   a. glView.close()            // decrement ref count
   b. glTexture.close()         // glDeleteTexture
   c. glDeleteMemoryObjectsEXT(memoryObject)
   d. releaseSharedHandle(allocPtr)
   e. Reset all fields
3. sharedMemoryActive = false
4. Next onRenderBegin:
   a. createSharedTextureManager(newW, newH)
   b. inject into mainRT via accessor
   c. sharedMemoryActive = true
```

## Graceful Fallback

`VulkanInterop.getDeviceInfo()` serves as a capability check (catches missing DLL, missing VK support, etc.):

```java
private boolean checkVulkanAvailable() {
    try {
        String info = VulkanInterop.getDeviceInfo();
        return info != null && !info.isEmpty();
    } catch (UnsatisfiedLinkError | Exception e) {
        return false;
    }
}
```

When `sharedMemoryActive == false`, everything works as today — `TextureTarget` + FSR pipeline internal output + `glCopyImageSubData`. The fallback is transparent; no config option needed.

The system auto-selects the best path at init time:
- VK available → SharedTextureManager path (inject into existing mainRT)
- VK unavailable → existing TextureTarget + copy path

## Files

| File | Lines | Change |
|------|-------|--------|
| `src/main/resources/fsr-reloaded.accesswidener` | **New** ~5 | Widen GlTexture + GlTextureView constructors + GlTexture.id |
| `src/main/resources/fabric.mod.json` | +1 | Add `accessWidener` entry |
| `src/native/fsrvk/vulkan_memory.h` | +1 | Add `VkDeviceSize allocationSize` to struct |
| `src/native/fsrvk/vulkan_memory.cpp` | +1 | Store `memReqs.size` after vkGetImageMemoryRequirements |
| `src/native/fsrvk/jni_bridge.cpp` | +12 | Add `getWin32Handle` + `getAllocationSize` JNI |
| `src/client/.../vulkan/VulkanInterop.java` | +2 | `native long getWin32Handle(long)` + `native long getAllocationSize(long)` |
| `src/client/.../mixin/RenderTargetAccessor.java` | **New** ~13 | `@Accessor` for `colorTexture` + `colorTextureView` |
| `src/client/.../mixin/GlTextureIdAccessor.java` | **New** ~9 | `@Accessor("id")` on `GlTexture` |
| `src/client/.../fsr/SharedTextureManager.java` | **New** ~120 | Create + inject + manage shared texture lifecycle |
| `src/client/.../fsr/FSRProcessor.java` | ~+80 | Integrate shared texture, remove copy paths |
| `src/client/.../fsr/FSR2Pipeline.java` | +8 | `setExternalOutputTexture`, use in dispatch |
| `src/client/.../fsr/FSR3Pipeline.java` | +8 | `setExternalOutputTexture`, use in dispatch |
| `src/client/.../fsr/FSR1Pipeline.java` | ~+15 | Use shared texture for blit/RCAS output |

## Non-Goals

- No semaphore sync (`glFinish()` not needed until Phase 3 adds VK compute)
- No changes to the Iris path (Iris manages its own framebuffers)
- No new config options (path is auto-selected based on capability)
- No changes to FG logic (FG still warps and writes to shared output)
