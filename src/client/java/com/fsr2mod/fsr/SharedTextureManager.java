package com.fsr2mod.fsr;

import com.fsr2mod.FSRMod;
import com.fsr2mod.mixin.GlTextureIdAccessor;
import com.fsr2mod.mixin.RenderTargetAccessor;
import com.fsr2mod.vulkan.VulkanInterop;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL43C;

public class SharedTextureManager {

    // Single-texture fields (FSR1/2/3 shared output)
    private long allocPtr;
    private int memoryObject;
    private int sharedGlTexId;
    private GlTexture glTexture;
    private GlTextureView glView;
    private int width;
    private int height;

    // FSR4 pool — 5 shared textures allocated as one batch
    private long fsr4PoolPtr;
    private int[] fsr4TexIds;
    private long[] fsr4Win32Handles;
    private long[] fsr4ResourcePtrs;
    private int[] fsr4MemoryObjects;

    private final Object lock = new Object();

    public void create(int w, int h) {
        synchronized (lock) {
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

            memoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT();
            if (memoryObject == 0) {
                VulkanInterop.releaseSharedHandle(allocPtr);
                allocPtr = 0;
                throw new RuntimeException("glCreateMemoryObjectsEXT failed");
            }

            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObject, allocSize,
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle);

            // Check for GL error after import
            int glErr = GL43C.glGetError();
            if (glErr != GL43C.GL_NO_ERROR) {
                EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObject);
                VulkanInterop.releaseSharedHandle(allocPtr);
                allocPtr = 0;
                memoryObject = 0;
                throw new RuntimeException("glImportMemoryWin32HandleEXT failed with GL error 0x" + Integer.toHexString(glErr));
            }

            sharedGlTexId = GL43C.glGenTextures();
            GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, sharedGlTexId);
            EXTMemoryObject.glTextureStorageMem2DEXT(sharedGlTexId, 1, GL43C.GL_RGBA8, w, h, memoryObject, 0);

            glErr = GL43C.glGetError();
            if (glErr != GL43C.GL_NO_ERROR) {
                GL43C.glDeleteTextures(sharedGlTexId);
                EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObject);
                VulkanInterop.releaseSharedHandle(allocPtr);
                allocPtr = 0;
                memoryObject = 0;
                sharedGlTexId = 0;
                throw new RuntimeException("glTextureStorageMem2DEXT failed with GL error 0x" + Integer.toHexString(glErr));
            }

            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
            glTexture = new GlTexture(usage, "fsr-shared", TextureFormat.RGBA8, w, h, 1, 1, sharedGlTexId);

            // If GlTexture constructor allocated its own internal ID, delete it and override
            if (glTexture.id != sharedGlTexId) {
                GL43C.glDeleteTextures(glTexture.id);
                ((GlTextureIdAccessor) glTexture).setId(sharedGlTexId);
            }

            glView = new GlTextureView(glTexture, 0, 1);
            this.width = w;
            this.height = h;
        }
    }

    public void attachToMainRT(RenderTarget mainRT) {
        synchronized (lock) {
            RenderTargetAccessor acc = (RenderTargetAccessor) mainRT;
            acc._fsrSetColorTexture(glTexture);
            acc._fsrSetColorTextureView(glView);
        }
    }

    public void attachToPipeline(FSR2Pipeline p) {
        synchronized (lock) {
            if (sharedGlTexId == 0) {
                FSRMod.LOGGER.warn("attachToPipeline(FSR2Pipeline): no shared texture available");
                return;
            }
            p.setExternalOutputTexture(sharedGlTexId);
        }
    }

    public void attachToPipeline(FSR3Pipeline p) {
        synchronized (lock) {
            if (sharedGlTexId == 0) {
                FSRMod.LOGGER.warn("attachToPipeline(FSR3Pipeline): no shared texture available");
                return;
            }
            p.setExternalOutputTexture(sharedGlTexId);
        }
    }

    public void destroy() {
        synchronized (lock) {
            // Only do GL work if we actually created GL resources.
            // With VulkanMod, sharedTexMgr is created but never initialized
            // with GL textures — skip GL operations to avoid crash.
            boolean hasGlResources = sharedGlTexId != 0 || memoryObject != 0
                || glTexture != null || glView != null;
            if (hasGlResources) {
                GL43C.glFinish();
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);

                if (glView != null && !glView.isClosed()) glView.close();
                if (glTexture != null && !glTexture.isClosed()) glTexture.close();
                if (memoryObject != 0) EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObject);
            }

            // Always release native Vulkan allocation (no GL needed)
            if (allocPtr != 0) VulkanInterop.releaseSharedHandle(allocPtr);
            allocPtr = 0;
            memoryObject = 0;
            sharedGlTexId = 0;
            glTexture = null;
            glView = null;
            width = 0;
            height = 0;
        }
    }

    public int getSharedGlTexId() { return sharedGlTexId; }
    public boolean isValid() { return allocPtr != 0; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // ----------------------------------------------------------
    // FSR4 Pool — 5 shared textures allocated as one batch
    // ----------------------------------------------------------

    public boolean allocateFsr4Pool(int renderW, int renderH, int displayW, int displayH) {
        synchronized (lock) {
            if (fsr4PoolPtr != 0) destroyFsr4Pool();

            fsr4PoolPtr = VulkanInterop.allocateFsr4Pool(renderW, renderH, displayW, displayH);
            if (fsr4PoolPtr == 0) return false;

            fsr4TexIds = new int[5];
            fsr4Win32Handles = new long[5];
            fsr4ResourcePtrs = new long[5];
            fsr4MemoryObjects = new int[5];

            // Per-texture GL internalFormat: Color=RGBA8, Depth=R32F, MV=RG16F, Reactive=R8, Output=RGBA8
            int[] internalFormats = {
                GL43C.GL_RGBA8,
                GL43C.GL_R32F,
                GL43C.GL_RG16F,
                GL43C.GL_R8,
                GL43C.GL_RGBA8
            };
            // D3D12 shared resource handle type for glImportMemoryWin32HandleEXT
            int d3d12HandleType = 0x958B; // GL_HANDLE_TYPE_D3D12_RESOURCE_EXT

            for (int i = 0; i < 5; i++) {
                long win32Handle = VulkanInterop.getFsr4Win32Handle(fsr4PoolPtr, i);
                if (win32Handle == 0) {
                    destroyFsr4Pool();
                    return false;
                }
                fsr4Win32Handles[i] = win32Handle;

                int texW = (i == 4) ? displayW : renderW;
                int texH = (i == 4) ? displayH : renderH;
                long allocSize = VulkanInterop.getFsr4AllocationSize(fsr4PoolPtr, i);
                long expectedMin = (long) texW * texH * (i == 3 ? 1 : 4);
                if (allocSize < expectedMin) {
                    FSRMod.LOGGER.warn("FSR4: allocSize {} < expectedMin {} for index {} ({}x{})",
                        allocSize, expectedMin, i, texW, texH);
                    destroyFsr4Pool();
                    return false;
                }

                fsr4ResourcePtrs[i] = VulkanInterop.getFsr4ResourcePtr(fsr4PoolPtr, i);
                if (fsr4ResourcePtrs[i] == 0) {
                    destroyFsr4Pool();
                    return false;
                }

                int memObj = EXTMemoryObject.glCreateMemoryObjectsEXT();
                if (memObj == 0) {
                    destroyFsr4Pool();
                    return false;
                }
                fsr4MemoryObjects[i] = memObj;

                EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memObj, allocSize,
                        d3d12HandleType, win32Handle);

                int glErr = GL43C.glGetError();
                if (glErr != GL43C.GL_NO_ERROR) {
                    FSRMod.LOGGER.warn("FSR4: glImportMemoryWin32HandleEXT failed with GL error 0x{:04X} for index {}", glErr, i);
                    destroyFsr4Pool();
                    return false;
                }

                int texId = GL43C.glGenTextures();
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texId);
                EXTMemoryObject.glTextureStorageMem2DEXT(texId, 1, internalFormats[i], texW, texH, memObj, 0);

                glErr = GL43C.glGetError();
                if (glErr != GL43C.GL_NO_ERROR) {
                    FSRMod.LOGGER.warn("FSR4: glTextureStorageMem2DEXT failed with GL error 0x{:04X} for index {}", glErr, i);
                    GL43C.glDeleteTextures(texId);
                    destroyFsr4Pool();
                    return false;
                }

                fsr4TexIds[i] = texId;
            }

            return true;
        }
    }

    public int getColorTexId() {
        return fsr4TexIds != null ? fsr4TexIds[0] : 0;
    }

    public int getDepthTexId() {
        return fsr4TexIds != null ? fsr4TexIds[1] : 0;
    }

    public int getMvTexId() {
        return fsr4TexIds != null ? fsr4TexIds[2] : 0;
    }

    public int getReactiveTexId() {
        return fsr4TexIds != null ? fsr4TexIds[3] : 0;
    }

    public int getOutputTexId() {
        return fsr4TexIds != null ? fsr4TexIds[4] : 0;
    }

    public long getColorAllocPtr() {
        return fsr4PoolPtr;
    }

    public long getDepthAllocPtr() {
        return fsr4PoolPtr;
    }

    public long getMvAllocPtr() {
        return fsr4PoolPtr;
    }

    public long getReactiveAllocPtr() {
        return fsr4PoolPtr;
    }

    public long getOutputAllocPtr() {
        return fsr4PoolPtr;
    }

    public void destroyFsr4Pool() {
        synchronized (lock) {
            // GL operations are only safe when we actually created GL resources.
            // If this was a D3D12-only allocation (VulkanMod path), skip GL entirely.
            boolean hasGlResources = fsr4TexIds != null || fsr4MemoryObjects != null;
            if (hasGlResources) {
                GL43C.glFinish();
                GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);

                if (fsr4TexIds != null) {
                    for (int i = 0; i < fsr4TexIds.length; i++) {
                        if (fsr4TexIds[i] != 0) {
                            GL43C.glDeleteTextures(fsr4TexIds[i]);
                        }
                    }
                    fsr4TexIds = null;
                }

                if (fsr4MemoryObjects != null) {
                    for (int i = 0; i < fsr4MemoryObjects.length; i++) {
                        if (fsr4MemoryObjects[i] != 0) {
                            EXTMemoryObject.glDeleteMemoryObjectsEXT(fsr4MemoryObjects[i]);
                        }
                    }
                    fsr4MemoryObjects = null;
                }
            }

            // Always release the native pool (no GL needed)
            if (fsr4PoolPtr != 0) {
                VulkanInterop.releaseFsr4Pool(fsr4PoolPtr);
                fsr4PoolPtr = 0;
            }
            fsr4Win32Handles = null;
            fsr4ResourcePtrs = null;
        }
    }

    /**
     * D3D12-only pool allocation — no GL texture binding.
     * Safe to call when no GL context is current (e.g. VulkanMod path).
     * The pool pointer is stored and accessible via getColorAllocPtr() etc.
     */
    public boolean allocateFsr4PoolD3D12(int renderW, int renderH, int displayW, int displayH) {
        synchronized (lock) {
            if (fsr4PoolPtr != 0) destroyFsr4PoolD3D12();
            fsr4PoolPtr = VulkanInterop.allocateFsr4Pool(renderW, renderH, displayW, displayH);
            if (fsr4PoolPtr == 0) return false;
            // Store resource pointers for dispatch — no GL binding
            fsr4ResourcePtrs = new long[5];
            for (int i = 0; i < 5; i++) {
                fsr4ResourcePtrs[i] = VulkanInterop.getFsr4ResourcePtr(fsr4PoolPtr, i);
                if (fsr4ResourcePtrs[i] == 0) {
                    destroyFsr4PoolD3D12();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * D3D12-only pool destroy — no GL calls. Safe when no GL context is current.
     */
    public void destroyFsr4PoolD3D12() {
        synchronized (lock) {
            if (fsr4PoolPtr != 0) {
                VulkanInterop.releaseFsr4Pool(fsr4PoolPtr);
                fsr4PoolPtr = 0;
            }
            fsr4ResourcePtrs = null;
            fsr4Win32Handles = null;
        }
    }
}