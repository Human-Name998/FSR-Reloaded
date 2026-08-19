package com.fsr2mod.vulkan;

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
        if (swapActive) {
            // STALE-SWAP RECOVERY (2026-08-11): swapActive true at GUI HEAD
            // means a PREVIOUS GuiRenderer.render threw between HEAD and
            // RETURN — the RETURN hook never ran, so the main pass is still
            // swapped to the swapchain and every subsequent frame bailed at
            // this check, silently rendering the world at native res without
            // FSR4. Restore the scaled framebuffer here so the swap is undone
            // and the normal path resumes.
            restoreMainPass(renderer);
            swapActive = false;
            copiesRecordedThisFrame = false;
            return false;
        }
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

            if (!swapMainPassToSwapChain(renderer, sc)) {
                // SWAP FAILED AFTER A SUCCESSFUL RECORD (2026-08-11): the
                // FSR4 copies (Copy1/MV-gen/Copy2) are already recorded in
                // the command buffer, but the framebuffer swap did not
                // happen — the GUI will draw into the SCALED fb. Clear the
                // flag so the MainPass.end hook re-records the copies WITH
                // the GUI composited (old upscaled-UI behavior). Leaving it
                // true would make end() skip, and the GUI (drawn after the
                // copies captured the world) would never reach the swapchain
                // — a frame with the HUD missing.
                copiesRecordedThisFrame = false;
                return false;
            }

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
                // FSR3_FG_DX12 step 2 (2026-08-15): AFTER the GUI painted, the
                // swapchain holds the real FSR4 frame + GUI at native res (still
                // COLOR_ATTACHMENT_OPTIMAL here). Record the capture into pool 6
                // (the FG SDK's presentColor) into the still-recording command
                // buffer, BEFORE transitionToPresent (which leaves it PRESENT_SRC
                // for the swap). The FG generate + the extra present run from the
                // Renderer.endFrame TAIL hook (FSRProcessor.presentVkModFgFrame)
                // once the main submission — this capture included — completes.
                try {
                    int imgIdx = Renderer.getCurrentImage();
                    if (imgIdx >= 0) {
                        long scImg = sc.getImageId(imgIdx);
                        FSRProcessor fgProc = FSRMod.getInstance().getProcessor();
                        if (fgProc != null) {
                            fgProc.onVkModGuiPainted(cmdBuf, scImg, sc.getWidth(), sc.getHeight());
                        }
                    }
                } catch (Exception ignored) {
                }
                transitionToPresent(sc);
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
     * Transition the swapchain's color attachment to PRESENT_SRC_KHR. LWJGL's
     * VkCommandBuffer isn't on the client compile classpath, so the transition
     * is invoked via reflection on the runtime command buffer object.
     */
    private static void transitionToPresent(SwapChain sc) {
        try {
            Object cmdBufObj = Renderer.class.getMethod("getCommandBuffer").invoke(null);
            if (cmdBufObj == null) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.lang.reflect.Method m = VulkanImage.class.getMethod(
                        "transitionImageLayout",
                        org.lwjgl.system.MemoryStack.class,
                        cmdBufObj.getClass(),
                        int.class);
                m.invoke(sc.getColorAttachment(), stack, cmdBufObj, 1000001002); // PRESENT_SRC_KHR
            }
        } catch (Exception e) {
            if (!uiHelperLogged) {
                uiHelperLogged = true;
                FSRMod.LOGGER.warn("[VKMod] native-res UI present transition failed: {}", e.toString());
            }
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
            if (VulkanModCompat.fsr4VkModActive) {
            try {
            Object mpObj = Renderer.getInstance().getMainPass();
            if (mpObj instanceof DefaultMainPass dmp) {
            Framebuffer fb = dmp.getMainFramebuffer();
            VulkanImage src = fb.getColorAttachment();
            long srcId = src.getId();
            int srcW = src.width;
            int srcH = src.height;
            // REAL-DEPTH source (2026-08-06): bind the scaled
            // framebuffer's depth attachment to the MV-gen shader
            // (binding 3) so the FSR4 SDK receives REAL per-pixel
            // depth — correct reprojection at every distance.
            // Constant-depth MVs (ndcZ=0 ≈ 1-block depth) were the
            // "jagged water" root cause: distant water got ~100x
            // wrong parallax during translation → history rejected
            // → aliased edges. Cheap per frame: native no-ops
            // unless the image handle changed (framebuffer									// recreation on resize/scale change). Falls back to
            // the constant-depth dummy when no depth exists.
            try {
            VulkanImage depthImg = fb.getDepthAttachment();
            // REAL-DEPTH SOURCE FIX (2026-08-06): the DefaultMainPass
            // framebuffer's depth attachment reads 1.0 everywhere (direct
            // probe proved it — cleared, never written) while its COLOR
            // has the scene. The world's draw passes target the Minecraft
            // main framebuffer and render through the GpuDevice/VkFbo
            // path, so the REAL depth is the mc main target's OWN depth
            // texture (a separate VulkanImage, native-res), not the
            // framebuffer-attachment decoy. Bind THAT. One-shot probe
            // logs both ids+sizes to confirm.
            VulkanImage depthSrc = null;
            int depthFmt = 0;
            boolean mcDepthUsed = false;
            String mcFailReason = null;
            // RENDER-SCALE DEPTH FIX (2026-08-08, "jagged edges" root cause): when render scale is
            // configured (vulkanModRenderScale + qualityScale < 1.0) the world's render pass writes
            // into the SCALED framebuffer (mainFramebuffer was swapped by this mixin), so ITS depth
            // attachment holds the real per-pixel depth. The mc main-target depth (native-res) is a
            // SEPARATE image that is never written in this mode (direct probe: 0.0 everywhere) —
            // binding it fed the MV-gen depth=0 → ndcZ≈-1 (near plane) → ~100x-too-large MVs → the
            // SDK rejected ALL history → spatial output → jagged sky/edges even with the
            // accumulated-sky shader (its depth>=0.999 sky path could never fire). Prefer the
            // framebuffer's own depth here; the mc main-target path below stays for non-scaled mode.
            boolean preferFbDepth = VulkanModCompat.renderScaleSwapped()
            && depthImg != null && depthImg.getId() != 0;
            try {
            RenderTarget mcRt = Minecraft.getInstance().getMainRenderTarget();
            // REAL-DEPTH ROOT CAUSE (2026-08-06): VulkanMod's
            // MainTargetMixin.createBuffers() creates a FRESH depthTexture
            // field via GpuDevice.createTexture (the image the world's
            // render pass ACTUALLY writes depth into) and sets
            // depthTextureView from it — but it OVERRIDES the
            // getDepthTexture() GETTER to return
            // MainPass.getDepthAttachment(), which wraps the DMP
            // framebuffer's depth attachment — the 1.0 DECOY (proven:
            // direct probe reads 1.0 everywhere). So calling
            // getDepthTexture() silently binds the decoy even when
            // the "mc path" works — the 1.0 readback was NOT the
            // probe's fault. The real depth is reachable via
            // getDepthTextureView().texture() — the FIELD, not the
            // overridden getter.
            GpuTexture mcDepth = null;
            if (mcRt.getDepthTextureView() == null) {
            mcFailReason = "mc main-target getDepthTextureView() returned null";
            } else {
            mcDepth = mcRt.getDepthTextureView().texture();
            if (mcDepth == null) {
            mcFailReason = "mc main-target getDepthTextureView().texture() returned null";
            }
            }
            if (mcDepth != null && !(mcDepth instanceof VkGpuTexture vkTex)) {
            mcFailReason = "mc depth texture is " + mcDepth.getClass().getName() + " (not VkGpuTexture)";
            } else if (mcDepth instanceof VkGpuTexture vkImgTex) {
            VulkanImage vkImg = vkImgTex.getVulkanImage();
            if (vkImg == null) {
            mcFailReason = "VkGpuTexture.getVulkanImage() returned null";
            } else if (vkImg.getId() == 0) {
            mcFailReason = "mc depth VulkanImage id==0";
            } else if (!preferFbDepth) {
            depthSrc = vkImg;
            depthFmt = vkImg.format;
            mcDepthUsed = true;
            }
            }
            // ALWAYS log once — do NOT reuse the outer one-shot gate:
            // the old diagnostic block sets the flag BEFORE this probe
            // runs, silently swallowing this line and the failure
            // reason (seen 2026-08-06: probe absent, native bound the
            // DMP decoy with zero explanation).
            if (!vkModDepthSourceLogged) {
            vkModDepthSourceLogged = true;
            long dmpId = depthImg != null ? depthImg.getId() : 0;
            int dmpFmt = depthImg != null ? fb.getDepthFormat() : 0;
            long mcId = depthSrc != null ? depthSrc.getId() : 0;
            int mcW = depthSrc != null ? depthSrc.width : 0;
            int mcH = depthSrc != null ? depthSrc.height : 0;
            // 2026-08-08: also log the CURRENT LAYOUTS the native
            // readback/MV-gen barriers assume (depth=3
            // DEPTH_ATTACHMENT_OPTIMAL, color=2
            // COLOR_ATTACHMENT_OPTIMAL) + the color source id, so a
            // layout mismatch (which reads garbage/1.0 in both
            // probes) is distinguishable from genuinely-empty depth.
            int dmpDepthLayout = -1;
            int dmpColorLayout = -1;
            try {
            if (depthImg != null) dmpDepthLayout = depthImg.getCurrentLayout();
            if (src != null) dmpColorLayout = src.getCurrentLayout();
            } catch (Exception ignored) {}
            FSRMod.LOGGER.info("[VKMod] depth probe (one-shot): DMP fb depth id={} fmt={} curLayout={} | DMP fb color id={} curLayout={} | mc target VIEW depth id={} w={} h={} fmt={} | USING {}{}",
            Long.toHexString(dmpId), dmpFmt, dmpDepthLayout, Long.toHexString(src != null ? src.getId() : 0), dmpColorLayout,
            Long.toHexString(mcId), mcW, mcH, depthFmt,
            mcDepthUsed ? "mc main-target VIEW depth" : (depthSrc != null ? "DMP fb depth (fallback)" : "none"),
            mcFailReason != null ? " — FAILED: " + mcFailReason : "");
            }
            } catch (Exception e) {
            mcFailReason = "probe threw: " + e;
            if (!vkModDepthSourceLogged) {
            vkModDepthSourceLogged = true;
            FSRMod.LOGGER.info("[VKMod] depth probe (one-shot): mc target depth UNREACHABLE — {}", e.toString());
            }
            }
            if (preferFbDepth) {
            depthSrc = depthImg;
            depthFmt = depthImg != null ? fb.getDepthFormat() : 0;
            }
            if (depthSrc == null) {
            // mc depth unavailable — fall back to the old DMP-fb depth.
            depthSrc = depthImg;
            depthFmt = depthImg != null ? fb.getDepthFormat() : 0;
            }
            VulkanModCompat.setVkModDepthSource(
            depthSrc != null ? depthSrc.getId() : 0L,
            depthFmt,
            depthSrc != null ? depthSrc.width : 0,
            depthSrc != null ? depthSrc.height : 0);									} catch (Exception e) {
            if (!vkModDepthSourceLogged) {
            vkModDepthSourceLogged = true;
            FSRMod.LOGGER.info("[VKMod] getDepthAttachment threw: {} — constant-depth fallback (one-shot)",
            e.toString());
            }										VulkanModCompat.setVkModDepthSource(0L, 0, 0, 0);
            }
            // SCENE-COLOR source (2026-08-06, "FSR4 can't handle
            // flowers"): bind the scaled framebuffer's COLOR
            // attachment to the MV-gen shader (binding 4) so the
            // thin-detail/cutout reactive term can see flowers /
            // tall-grass / leaves — 1-2px alpha-cutout geometry
            // that temporal accumulation smears into vertical
            // streaks at low render scale, invisible to the
            // MV-magnitude and tile-histogram reactive terms. Same
            // cheap-per-frame pattern as the depth source (native
            // no-op unless the image handle changed). format=0 →
            // native resolves the swapchain format (the scaled fb
            // is built with setFormat(swapChain.getFormat())).
            // ONLY bound in render-scale mode: in the non-scaled
            // path the scene color IS the swapchain image, which is
            // PRESENT_SRC_KHR at this hook point (recordFrameCopyOps
            // barrier 1) — sampling it would need a PRESENT→
            // SHADER_READ_ONLY round-trip, and flowers are 4-8px at
            // native render res (far less ghost-prone). The dummy
            // stays bound there → detector inert, zero risk.
            try {
            VulkanModCompat.setVkModColorSource(
            src != null ? src.getId() : 0L, 0,
            src != null ? src.width : 0,
            src != null ? src.height : 0);
            } catch (Exception e) {
            VulkanModCompat.setVkModColorSource(0L, 0, 0, 0);
            }
            // SELF-BLIT GUARD (same as the blit path below): on
            // the world-join transitional frame mainFramebuffer
            // is STILL the swapchain while renderScaleActive() is
            // already true — copying src==dst is a self-copy with
            // contradictory barriers. Only copy when src differs
            // from dst (i.e. actually scaled).
            if (srcId != 0 && srcW > 0 && srcH > 0
            && (srcW != w || srcH != h)) {
            // The swapchain is being written this frame (FSR4 Copy2 or the
            // safe blit fallback) — the GUI can draw on top at native res.
            copiesRecordedThisFrame = true;
            if (!VulkanModCompat.fsr4InputPopulated) {
            // POOL-JUST-RECREATED BOOTSTRAP (2026-08-05):
            // the dispatch gate in FSRProcessor.onRenderBegin
            // was blocked for one frame (fsr4InputPopulated
            // cleared at init/recreate), so pool[4] has NEVER
            // been written by a dispatch — Copy2 would present
            // undefined content. Record Copy1 (scene → pool
            // input) + MV-gen with includeCopy2=false so the
            // NEXT frame's dispatch has fresh inputs, and
            // present THIS frame via the bilinear blit (always
            // safe to record). Set the flag so FSR4 engages
            // next frame. NOTE: recording the blit AFTER the
            // scaled-copy in the same command buffer is valid —
            // recordVkModFsr4ScaledCopy restores src to
            // COLOR_ATTACHMENT_OPTIMAL (barrier 7) before the
            // blit's own src transition.
            VulkanModCompat.recordVkModFsr4ScaledCopy(cmdBuf, srcId, srcW, srcH, swapchainImage, w, h, false);
            VulkanModCompat.recordVkModMvGen(cmdBuf);
            VulkanModCompat.recordRenderScaleBlit(cmdBuf, srcId, srcW, srcH, swapchainImage, w, h);
            VulkanModCompat.fsr4InputPopulated = true;
            } else if (!VulkanModCompat.waitForD3D12Output()) {
            // TIMEOUT (GPU saturated): blit fallback — reads
            // the CURRENT scaled scene, always recordable.
            // fsr4InputPopulated stays true (pool inputs age
            // one frame, same as the non-scaled path's
            // skip-dispatch behavior). DO NOT record Copy1 or
            // MV-gen here — this frame's D3D12 dispatch (from
            // onRenderBegin) is STILL IN FLIGHT reading
            // pool[0..3]; overwriting them mid-read would
            // corrupt its inputs. The aging is intentional:
            // the next frame's dispatch reads stale-but-valid
            // inputs instead of racing.
            VulkanModCompat.recordRenderScaleBlit(cmdBuf, srcId, srcW, srcH, swapchainImage, w, h);		                                    } else {                                            // Steady state: real FSR4 upscale.
                                            VulkanModCompat.recordVkModFsr4ScaledCopy(cmdBuf, srcId, srcW, srcH, swapchainImage, w, h, true);
                                            VulkanModCompat.recordVkModMvGen(cmdBuf);
                                            // FSR3_FG_DX12 step 2: the FG generate + present now run in the
                                            // Renderer.endFrame TAIL hook (presentVkModFgFrame) — it needs pool 6
                                            // (the swapchain GUI capture, recorded in endNativeResGui after the
                                            // GUI paints) which does not exist at this pre-GUI point.
                                            }
            // DIAG two-point trace (2026-08-09): the world
            // has rendered by now (this is the main-pass
            // end hook). Read VRenderSystem.projection and
            // compare m30/m31 to the after-override value —
            // proves whether the field survives the render
            // (draw ignores it) or was re-captured mid-frame.
            FSRProcessor proc = FSRMod.getInstance().getProcessor();
            if (proc != null && !FSRProcessor.vkModProjectionAfterRenderLogged) {
            FSRProcessor.vkModProjectionAfterRenderLogged = true;
            proc.logVkModProjectionState("after-render");
            }
            }
            // REVIEW FIX (2026-08-05): store THIS frame's fence
            // OUTSIDE the self-blit guard — on the world-join
            // transitional frame (guard skips the copy) the scene
            // still rendered into the swapchain and the frame is
            // still a submission; not storing the fence forces the
            // next frame's waitForFrameCopy() into a full
            // vkQueueWaitIdle GPU drain (the stall the
            // bounded-fence work avoids). Stored in the FSR4
            // branch regardless of which copy path ran.
            storeFrameFence(renderer);
            }
            } catch (Exception e) {
            FSRMod.LOGGER.warn("VulkanMod FSR4 scaled copy failed: {}", e.getMessage());
            }
            } else {
            // Bilinear blit fallback (FSR4 not initialized — e.g. FSR1/2/3
            // selected under VulkanMod, or FSR4 init failed).
            try {
            Object mpObj = Renderer.getInstance().getMainPass();
            if (mpObj instanceof DefaultMainPass dmp) {
            Framebuffer fb = dmp.getMainFramebuffer();
            VulkanImage src = fb.getColorAttachment();
            long srcId = src.getId();
            int srcW = src.width;
            int srcH = src.height;
            // SELF-BLIT GUARD: on the world-join transitional frame
            // the flip was just detected but the swapchain recreate
            // (scheduleSwapChainUpdate) lands at the NEXT frame's
            // beginFrame — so mainFramebuffer is STILL the swapchain
            // while renderScaleActive() is already true. Blitting
            // src==dst==the same swapchain image is a self-blit with
            // contradictory barriers (COLOR_ATTACHMENT_OPTIMAL→
            // TRANSFER_SRC then UNDEFINED→TRANSFER_DST on the same
            // image) that discards the fresh frame → 1-frame garbage
            // at world join. Only blit when src differs from dst
            // (i.e. actually scaled). Mirrors the createResources
            // hook's scaledW==w && scaledH==h early-return.
            if (srcId != 0 && srcW > 0 && srcH > 0
            && (srcW != w || srcH != h)) {
            VulkanModCompat.recordRenderScaleBlit(cmdBuf, srcId, srcW, srcH, swapchainImage, w, h);
            }
            }
            } catch (Exception e) {
            FSRMod.LOGGER.warn("VulkanMod render-scale blit failed: {}", e.getMessage());
            }
            }
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
                // GpuDevice.backend is private (GpuDeviceBackend interface) —
                // read it via reflection.
                VkGpuDevice dev;
                try {
                    java.lang.reflect.Field backendField =
                            com.mojang.blaze3d.systems.GpuDevice.class.getDeclaredField("backend");
                    backendField.setAccessible(true);
                    dev = (VkGpuDevice) backendField.get(RenderSystem.getDevice());
                } catch (Exception e) {
                    return false;
                }
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
