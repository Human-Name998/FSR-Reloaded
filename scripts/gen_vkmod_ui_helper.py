#!/usr/bin/env python3
"""Extract the FSR4 scaled-copy recording block from VulkanModMainPassMixin.java
verbatim and generate VulkanModUiHelper.java around it. The helper is a plain
(non-mixin) class so both the end() hook and the new GUI hook can share the
copy recording logic."""
import re, sys

SRC = "src/client/java/com/fsr2mod/mixin/vulkanmod/VulkanModMainPassMixin.java"
OUT = "src/client/java/com/fsr2mod/mixin/vulkanmod/VulkanModUiHelper.java"

lines = open(SRC, encoding="utf-8", newline="").read().split("\n")

# Find the start of the FSR4-active block and its matching close brace.
start = None
for i, ln in enumerate(lines):
    if ln.strip() == "if (VulkanModCompat.fsr4VkModActive) {":
        start = i
        break
assert start is not None, "FSR4 block start not found"

depth = 0
end = None
for i in range(start, len(lines)):
    ln = lines[i]
    depth += ln.count("{") - ln.count("}")
    if depth <= 0:
        end = i
        break
assert end is not None, "matching close brace not found"

block = "\n".join(lines[start:end + 1])

# The block references locals only available in the end() hook. Provide them
# in the helper: cmdBuf (param), renderer (param), w/h (params), swapchainImage
# (computed), vkModDepthSourceLogged (helper field), storeFrameFence (helper fn).
# Indent the block one extra level inside the helper method.
indented = "\n".join(("        " + ln) if ln.strip() else "" for ln in block.split("\n"))
# The helper method body indentation is 8 spaces; the block is already at 4 in
# the mixin (nested in renderScaleSwapped->fsr4VkModActive). Re-indent to 12.
lines_in = block.split("\n")
reindented = []
for ln in lines_in:
    if ln.strip():
        reindented.append("            " + ln.lstrip())
    else:
        reindented.append("")
body = "\n".join(reindented)

helper = '''package com.fsr2mod.mixin.vulkanmod;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.VulkanModCompat;
import com.fsr2mod.fsr.FSRProcessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * NATIVE-RES UI (2026-08-10): shared VulkanMod interop helpers for drawing the
 * GUI at native resolution on top of the FSR4 upscale.
 *
 * Plain class (NOT a mixin — mixin classes are merged into their targets at
 * runtime, so their static methods are not callable from elsewhere). Only ever
 * loaded from the VulkanMod mixins, so direct net.vulkanmod references are
 * safe here.
 *
 * Frame flow with this active (render scale + FSR4):
 *   1. World renders into the scaled framebuffer (main render pass).
 *   2. {@link #beginNativeResGui} (GuiRenderer.render HEAD): ends the main
 *      render pass, records the FSR4 copies (Copy1 world-only scaled-fb ->
 *      pool input, MV-gen, Copy2 pool output -> swapchain), then swaps
 *      DefaultMainPass.mainFramebuffer to the swapchain so the GUI's render
 *      passes bind the NATIVE-res swapchain image (aux render pass, LOAD_OP_LOAD
 *      preserves the upscaled world underneath).
 *   3. GUI draws at native res into the swapchain on top of the upscaled world.
 *   4. {@link #endNativeResGui} (GuiRenderer.render RETURN): ends the GUI pass,
 *      swaps the framebuffer back to the scaled one, and records the
 *      COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC transition for the swapchain.
 *   5. DefaultMainPass.end: our beforeVkEndCommandBuffer hook sees
 *      {@link #copiesRecordedThisFrame} and skips re-recording the copies.
 *
 * Fail-safe: any exception in beginNativeResGui restores the scaled framebuffer
 * and clears copiesRecordedThisFrame so the end() hook records as before (the
 * GUI renders into the scaled fb and gets upscaled — old behavior, never a
 * blank frame).
 */
public final class VulkanModUiHelper {

    /** Set when the GUI-head hook recorded the FSR4 copies this frame. The
     *  MainPass.end hook skips its own copy recording when this is true. */
    public static volatile boolean copiesRecordedThisFrame = false;

    /** True between the GUI-head and GUI-return hooks (the main pass
     *  framebuffer is swapped to the swapchain). */
    public static volatile boolean swapActive = false;

    /** One-shot diagnostic gate (mirrors the mixin's). */
    private static boolean vkModDepthSourceLogged;

    /** One-shot fail-safe log gate. */
    private static boolean uiHelperLogged;

    // ---- saved state for the framebuffer swap (restored in endNativeResGui) ----
    private static Framebuffer savedMainFramebuffer;
    private static GpuTexture[] savedColorTextures;
    private static GpuTextureView[] savedColorViews;
    private static IntSupplier savedImageIdxSupplier;

    // ---- cached swapchain-wrapping textures/views (rebuilt on resize) ----
    private static long[] cachedSwapChainImageIds;
    private static GpuTexture[] cachedSwapChainTextures;
    private static GpuTextureView[] cachedSwapChainViews;

    private VulkanModUiHelper() {
    }

    /**
     * GUI-HEAD hook (GuiRenderer.render HEAD). Returns true if the GUI should
     * draw at native res this frame (framebuffer swapped, swapchain holds the
     * upscaled world).
     */
    public static boolean beginNativeResGui(Renderer renderer, long cmdBuf) {
        if (swapActive) return false;
        if (!VulkanModCompat.renderScaleSwapped()) return false;
        if (!VulkanModCompat.fsr4VkModActive) return false;
        try {
            SwapChain sc = renderer.getSwapChain();
            if (sc == null || !sc.hasImages()) return false;
            int w = sc.getWidth();
            int h = sc.getHeight();
            if (w <= 0 || h <= 0) return false;

            // The world has fully rendered by now (GuiRenderer.render runs after
            // the world + hand). End the main render pass so the copies below
            // record transfer ops outside any render pass.
            renderer.endRenderPass();

            // Record Copy1 (world-only scaled fb -> pool input) + MV-gen + Copy2
            // (pool output -> swapchain) BEFORE the GUI draws, so the swapchain
            // holds the FSR4 upscaled world and the GUI composites over it at
            // native res (GL parity — the GUI is never part of the FSR4 input).
            recordFsr4ScaledCopies(cmdBuf, renderer, w, h);
            if (!copiesRecordedThisFrame) return false;   // nothing wrote the swapchain

            if (!swapMainPassToSwapChain(renderer, sc)) return false;

            // The native Copy2 left the swapchain in PRESENT_SRC_KHR but native
            // barriers don't update VulkanMod's Java-side layout tracking. Force
            // the tracked layout so the GUI aux pass's beginDynamicRendering
            // transitions PRESENT -> COLOR_ATTACHMENT_OPTIMAL correctly.
            try {
                sc.getColorAttachment().setCurrentLayout(1000001002); // PRESENT_SRC_KHR
            } catch (Exception ignored) {
            }
            swapActive = true;
            return true;
        } catch (Exception e) {
            // Fail-safe: restore the scaled framebuffer and let the end() hook
            // record the copies as before (upscaled GUI, old behavior).
            restoreMainPass(renderer);
            copiesRecordedThisFrame = false;
            swapActive = false;
            if (!uiHelperLogged) {
                uiHelperLogged = true;
                FSRMod.LOGGER.warn("[VKMod] native-res UI head hook failed ({}), falling back to upscaled UI", e.toString());
            }
            return false;
        }
    }

    /**
     * GUI-RETURN hook (GuiRenderer.render RETURN): end the GUI pass, swap the
     * main pass framebuffer back to the scaled one, and transition the
     * swapchain COLOR_ATTACHMENT_OPTIMAL -> PRESENT_SRC_KHR (the GUI aux pass
     * left it COLOR; vanilla's MainPass.end won't transition it because
     * mainFramebuffer is the scaled fb again).
     */
    public static void endNativeResGui(Renderer renderer, long cmdBuf) {
        if (!swapActive) return;
        try {
            renderer.endRenderPass();   // close the GUI aux pass if still open (no-op if closed)
            restoreMainPass(renderer);
            SwapChain sc = renderer.getSwapChain();
            if (sc != null && sc.hasImages()) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    sc.getColorAttachment().transitionImageLayout(
                        stack, Renderer.getCommandBuffer(), 1000001002); // PRESENT_SRC_KHR
                }
            }
        } catch (Exception e) {
            if (!uiHelperLogged) {
                uiHelperLogged = true;
                FSRMod.LOGGER.warn("[VKMod] native-res UI end hook failed: {}", e.toString());
            }
        } finally {
            swapActive = false;
            // copiesRecordedThisFrame intentionally stays true — the copies are
            // already recorded in the command buffer; the end() hook must skip.
        }
    }

    /**
     * The FSR4 scaled-copy recording block (extracted verbatim from
     * VulkanModMainPassMixin.beforeVkEndCommandBuffer): binds the real depth +
     * scene-color sources for the MV-gen, then records Copy1/MV-gen/Copy2 (or
     * the safe blit fallbacks) into the current command buffer, and stores the
     * frame fence. Sets {@link #copiesRecordedThisFrame} when the swapchain was
     * actually written.
     */
    public static void recordFsr4ScaledCopies(long cmdBuf, Renderer renderer, int w, int h) {
        long swapchainImage = 0;
        try {
            SwapChain sc = renderer.getSwapChain();
            if (sc == null || !sc.hasImages()) return;
            int imageIdx = Renderer.getCurrentImage();
            if (imageIdx < 0) return;
            swapchainImage = sc.getImageId(imageIdx);
            if (swapchainImage == 0) return;
        } catch (Exception e) {
            return;
        }
''' + body + '''
    }

    /**
     * Swap DefaultMainPass's framebuffer + color attachment views to the
     * swapchain (native res) so the GUI's render passes bind the current
     * swapchain image. Saves the scaled-fb state for restoreMainPass.
     */
    private static boolean swapMainPassToSwapChain(Renderer renderer, SwapChain sc) {
        try {
            Object mpObj = renderer.getMainPass();
            if (!(mpObj instanceof DefaultMainPass dmp)) return false;
            Class<?> cls = DefaultMainPass.class;
            Field fFb = cls.getDeclaredField("mainFramebuffer");
            Field fTex = cls.getDeclaredField("colorAttachmentTextures");
            Field fView = cls.getDeclaredField("colorAttachmentTextureViews");
            Field fSup = cls.getDeclaredField("imageIdxSupplier");
            fFb.setAccessible(true);
            fTex.setAccessible(true);
            fView.setAccessible(true);
            fSup.setAccessible(true);

            Framebuffer cur = (Framebuffer) fFb.get(dmp);
            if (cur == sc) return false;   // already swapchain — not scaled mode

            savedMainFramebuffer = cur;
            savedColorTextures = (GpuTexture[]) fTex.get(dmp);
            savedColorViews = (GpuTextureView[]) fView.get(dmp);
            savedImageIdxSupplier = (IntSupplier) fSup.get(dmp);

            List<VulkanImage> imgs = sc.getImages();
            long[] ids = new long[imgs.size()];
            for (int i = 0; i < imgs.size(); i++) ids[i] = imgs.get(i).getId();
            if (cachedSwapChainTextures == null
                    || !Arrays.equals(cachedSwapChainImageIds, ids)) {
                VkGpuDevice dev = (VkGpuDevice) RenderSystem.getDevice().backend;
                GpuTexture[] tex = new GpuTexture[imgs.size()];
                GpuTextureView[] views = new GpuTextureView[imgs.size()];
                for (int i = 0; i < imgs.size(); i++) {
                    GpuTexture t = dev.gpuTextureFromVulkanImage(imgs.get(i));
                    tex[i] = t;
                    views[i] = dev.createTextureView(t);
                }
                cachedSwapChainImageIds = ids;
                cachedSwapChainTextures = tex;
                cachedSwapChainViews = views;
            }

            fFb.set(dmp, sc);
            fTex.set(dmp, cachedSwapChainTextures);
            fView.set(dmp, cachedSwapChainViews);
            fSup.set(dmp, (IntSupplier) Renderer::getCurrentImage);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Restore the scaled-framebuffer state saved by swapMainPassToSwapChain. */
    private static void restoreMainPass(Renderer renderer) {
        if (savedMainFramebuffer == null) return;
        try {
            Object mpObj = renderer.getMainPass();
            if (mpObj instanceof DefaultMainPass dmp) {
                Class<?> cls = DefaultMainPass.class;
                Field fFb = cls.getDeclaredField("mainFramebuffer");
                Field fTex = cls.getDeclaredField("colorAttachmentTextures");
                Field fView = cls.getDeclaredField("colorAttachmentTextureViews");
                Field fSup = cls.getDeclaredField("imageIdxSupplier");
                fFb.setAccessible(true);
                fTex.setAccessible(true);
                fView.setAccessible(true);
                fSup.setAccessible(true);
                fFb.set(dmp, savedMainFramebuffer);
                fTex.set(dmp, savedColorTextures);
                fView.set(dmp, savedColorViews);
                fSup.set(dmp, savedImageIdxSupplier);
            }
        } catch (Exception ignored) {
        } finally {
            savedMainFramebuffer = null;
            savedColorTextures = null;
            savedColorViews = null;
            savedImageIdxSupplier = null;
        }
    }

    /**
     * Store THIS frame's submission fence so the next frame's waitForFrameCopy()
     * can wait on it (bounded) instead of draining the whole graphics queue with
     * vkQueueWaitIdle. Mirrors VulkanModMainPassMixin.storeFrameFence.
     */
    private static void storeFrameFence(Renderer renderer) {
        try {
            java.lang.reflect.Field fenceField = Renderer.class.getDeclaredField("inFlightFences");
            fenceField.setAccessible(true);
            Object fencesObj = fenceField.get(renderer);
            if (fencesObj instanceof java.util.List<?> fences) {
                int frame = Renderer.getCurrentFrame();
                if (frame >= 0 && frame < fences.size()) {
                    Object fence = fences.get(frame);
                    if (fence instanceof Long) {
                        VulkanModCompat.storeFrameFence((Long) fence);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback: native waitForFrameCopyBounded() uses vkQueueWaitIdle.
        }
    }
}
'''

open(OUT, "w", encoding="utf-8", newline="\r\n").write(helper)
print("wrote", OUT, "block lines", start + 1, "-", end + 1)
