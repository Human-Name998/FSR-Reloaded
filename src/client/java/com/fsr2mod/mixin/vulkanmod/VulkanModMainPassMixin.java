package com.fsr2mod.mixin.vulkanmod;		import com.fsr2mod.compat.VulkanModCompat;
		import com.fsr2mod.FSRMod;
		import com.fsr2mod.fsr.FSRProcessor;
import com.fsr2mod.vulkan.VulkanModUiHelper;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks VulkanMod's DefaultMainPass.end() to inject vkCmdCopyImage operations
 * into the SAME command buffer, BEFORE vkEndCommandBuffer is called.
 *
 * At this injection point the render pass has already ended but the
 * command buffer is still recording, so we can safely record additional
 * transfer commands.
 */
@Mixin(DefaultMainPass.class)
public class VulkanModMainPassMixin {

    @Shadow
    private Framebuffer mainFramebuffer;

    /** One-shot diagnostic gate (2026-08-06) — depth-source binding trace. */
    private static boolean vkModDepthSourceLogged;

    /**
     * Render-scale hook (POC, 2026-08-05): right after createResources assigns
     * mainFramebuffer (either the swapchain or the 10x10 stub), replace it with a
     * scaled-size framebuffer when config.vulkanModRenderScale + qualityScale
     * are active. This runs BEFORE createRenderPasses()/createAttachmentTextures(),
     * so the render passes, attachments, and viewport all auto-derive from the
     * scaled framebuffer (the else-branch of createAttachmentTextures already
     * handles a non-swapchain main framebuffer — it builds 1 color + depth
     * GpuTexture from the framebuffer's own images).
     */
    @Inject(
        method = "createResources",
        at = @At(
            value = "FIELD",
            target = "Lnet/vulkanmod/vulkan/pass/DefaultMainPass;mainFramebuffer:Lnet/vulkanmod/vulkan/framebuffer/Framebuffer;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
        ),
        remap = false
    )
    private void afterMainFramebufferAssigned(CallbackInfo ci) {
        if (!VulkanModCompat.renderScaleActive()) return;
        try {
            Renderer renderer = Renderer.getInstance();
            SwapChain swapChain = renderer.getSwapChain();
            // Only swap once the swapchain has real images (the first
            // createResources call uses a 10x10 stub before the swapchain exists).
            if (swapChain == null || !swapChain.hasImages()) return;
            int w = swapChain.getWidth();
            int h = swapChain.getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = VulkanModCompat.getRenderScale();
            int scaledW = Math.max(1, Math.round(w * scale));
            int scaledH = Math.max(1, Math.round(h * scale));
            if (scaledW == w && scaledH == h) return;  // scale == 1.0 — keep swapchain
            // Build a scaled framebuffer with the SAME color format as the
            // swapchain (vkCmdBlitImage requires identical src/dst formats).
            // builder(w, h, 1, true) → hasColorAttachment + hasDepthAttachment,
            // creating its own color (usage 23 incl. TRANSFER_SRC) + depth images.
            // NOTE on resize cleanup: NO manual cleanUp() of the previous scaled
            // framebuffer here — vanilla createResources() ALREADY frees it at
            // the top of the method (bytecode: mainFramebuffer != swapChain →
            // cleanUp(true)), because the swapchain's images are owned by the
            // swapchain while our scaled fb is a plain non-swapchain framebuffer.
            // Adding our own cleanUp() would DOUBLE-FREE (VkImages aren't GC'd
            // but they're also not ref-counted — two cleanUp calls on the same
            // VkImage is UB/driver crash).
            Framebuffer scaled = Framebuffer.builder(scaledW, scaledH, 1, true)
                .setFormat(swapChain.getFormat())
                .build();
            this.mainFramebuffer = scaled;
        } catch (Exception e) {
            FSRMod.LOGGER.warn("VulkanMod render-scale framebuffer swap failed: {}", e.getMessage());
        }
    }

    @Inject(
        method = "end",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkEndCommandBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;)I",
            shift = At.Shift.BEFORE
        ),
        remap = false
    )
    private static void beforeVkEndCommandBuffer(CallbackInfo ci) {
        // Render-scale mode only needs VulkanMod loaded (pure VK blit — no
        // external memory, no D3D12). The D3D12 FSR4 path additionally requires
        // isActive() (external memory + native DLL).
        if (!VulkanModCompat.isLoaded()) return;

        try {
            // Get command buffer address from Renderer (same one passed to MainPass.end)
            Object cmdBufObj;
            try {
                cmdBufObj = Renderer.class.getMethod("getCommandBuffer").invoke(null);
            } catch (Exception e) {
                return;
            }
            if (cmdBufObj == null) return;
            long cmdBuf = (long) cmdBufObj.getClass().getMethod("address").invoke(cmdBufObj);
            if (cmdBuf == 0) return;

            // Get current swapchain image
            Renderer renderer = Renderer.getInstance();
            SwapChain swapChain = renderer.getSwapChain();
            if (swapChain == null || !swapChain.hasImages()) return;

            int imageIdx = Renderer.getCurrentImage();
            if (imageIdx < 0) return;

            long swapchainImage = swapChain.getImageId(imageIdx);
            int w = swapChain.getWidth();
            int h = swapChain.getHeight();

            if (swapchainImage == 0 || w <= 0 || h <= 0) return;

            // Render-scale mode (POC): the scene rendered into the scaled main
            // framebuffer — the swapchain holds nothing useful. Record a bilinear
            // upscale blit scaled-color → swapchain instead of the D3D12 copies.
            // renderScaleSwapped() (enabled-INDEPENDENT — see its javadoc) is the
            // gate here: the scaled framebuffer must be presented whenever it is
            // live, including the FSR-disable transition frame before the
            // recreate back to native lands. The branches below self-guard:
            // when the framebuffer IS the native swapchain (title screen, or
            // after the disable-time recreate) the src==dst guard skips, the
            // vanilla end() PRESENT transition handles it, and we never fall
            // through to the D3D12 FSR4 copy path (FSR4 isn't initialized there;
            // the swapchain presents natively, which is exactly what we want).
            if (VulkanModCompat.renderScaleSwapped()) {
                // NATIVE-RES UI (2026-08-10): the GUI-head hook
                // (VulkanModUiHelper.beginNativeResGui, GuiRenderer.render
                // HEAD) already recorded the FSR4 copies (Copy1 world-only
                // scaled-fb -> pool input, MV-gen, Copy2 pool output ->
                // swapchain) BEFORE the GUI drew at native res into the
                // swapchain. The swapchain already holds the upscaled world
                // + native GUI — re-recording here would double-write.
                if (VulkanModUiHelper.copiesRecordedThisFrame) {
                    // CONSUME the flag (2026-08-11): it must suppress ONLY
                    // this frame's re-recording. Leaving it true would let a
                    // LATER frame (e.g. the first menu/title frame after
                    // leaving a world, where GuiRenderer.render never runs and
                    // the GUI-head hook can't clear it) skip the scaled-fb ->
                    // swapchain presentation entirely — the freeze this
                    // renderScaleSwapped() branch exists to prevent.
                    VulkanModUiHelper.copiesRecordedThisFrame = false;
                    return;
                }

                {
                    // RENDER SCALE + FSR4 COMBINED (2026-08-05): when the real FSR4
                    // D3D12 path is initialized (fsr4VkModActive), the scene rendered
                    // into the scaled framebuffer must be upscaled by FSR4, not a
                    // bilinear blit — the blit was the "FSR4 looks like FSR1" bug
                    // (it literally WAS a LINEAR upscale, worse than FSR1). Record
                    // the FSR4 pipeline: Copy1 scaled-fb → pool input (render-size)
                    // + Copy2 pool output (display-size) → swapchain, then the
                    // MV-gen compute (real inputs for the next frame's dispatch),
                    // all covered by the frame fence stored below.
                    // REVIEW FIX (2026-08-05): gate on fsr4VkModActive ONLY — NOT
                    // fsr4InputPopulated. gating on the flag here is a BOOTSTRAP
                    // DEADLOCK: the flag is set to true only INSIDE this FSR4
                    // branch, so on the first frame it is false → blit branch → flag
                    // never set → bilinear blit forever ("FSR4 quality did not change
                    // one bit" — it never engaged). The flag correctly gates the
                    // DISPATCH in onRenderBegin (don't feed the SDK an unpopulated
                    // pool), not this copy recording. The first frame's Copy2 reads
                    // undefined pool[4] only when the pool was JUST recreated, which
                    // the includeCopy2=false + blit-present fallback below handles.
                    // NATIVE-RES UI (2026-08-10): shared helper — binds the
                    // real depth + scene-color sources for the MV-gen, then
                    // records Copy1/MV-gen/Copy2 (or the safe blit fallbacks)
                    // and stores the frame fence. Extracted verbatim from this
                    // hook so the GUI-head hook (VulkanModUiHelper) can record
                    // the same pipeline BEFORE the GUI draws (native-res UI).
                    VulkanModUiHelper.recordFsr4ScaledCopies(cmdBuf, renderer, w, h);

                }
                return;  // configured → skip the D3D12 copy path entirely
            }

            if (!VulkanModCompat.isActive()) return;

            // STALE-FRAMEBUFFER GUARD (review fix 2026-08-05): when the user
            // disables render scale (slider to 100% or toggle off),
            // renderScaleConfigured() flips false IMMEDIATELY but the swapchain
            // recreate lands at the NEXT frame's beginFrame at the earliest
            // (debounce can stretch that to 500ms — dozens of frames). During
            // that window the scene rendered into the STILL-scaled framebuffer,
            // NOT the swapchain image, and FSR4 may have never been initialized
            // (it was gated off while render scale was configured) — the D3D12
            // copies would then read stale swapchain content and operate on null
            // external VkImages (potential native crash). The D3D12 copy path
            // fundamentally requires the scene to have rendered into the
            // swapchain image, so gate it on mainFramebuffer == swapChain — the
            // same condition vanilla's own end() uses for its PRESENT transition
            // (mirrors the self-blit guard above). While the fb is still scaled,
            // skip the D3D12 copies; the frame presents the scaled fb via the
            // vanilla path (one native-scale frame, then the recreate lands).
            try {
                Object mpObj = Renderer.getInstance().getMainPass();
                if (mpObj instanceof DefaultMainPass dmp) {
                    Framebuffer fb = dmp.getMainFramebuffer();
                    if (fb != swapChain) return;
                }
            } catch (Exception e) {
                return;  // can't verify the framebuffer — skip rather than risk it
            }

            // Sync fix (2026-08-05): wait for the D3D12 FSR4 dispatch (submitted
            // in onRenderBegin) before recording Copy2 (output -> swapchain). The
            // dispatch ran on the GPU while the scene rendered, so this wait is
            // ~0ms — it replaces the old mid-frame Fsr4Native.waitIdle() CPU
            // stall, letting the D3D12 upscale overlap the scene render (the
            // VKMod equivalent of the GL path's fsr4Async overlap).
            //
            // REVIEW FIX (2026-08-05): on fence TIMEOUT (GPU saturated — e.g.
            // Voxy meshing queued ahead on the same queue) the D3D12 output is
            // NOT ready. Recording Copy2 would copy a half-written texture, and
            // recording Copy1/MV-gen over pool inputs the in-flight dispatch
            // still reads = data race. Skip ALL copies this frame: the swapchain
            // image holds the raw scene (mainFramebuffer == swapChain in this
            // path) and presents natively — the VKMod equivalent of the GL
            // path's graceful single-present on sync timeout.
            if (!VulkanModCompat.waitForD3D12Output()) {
                return;
            }

            // Record copy operations into VulkanMod's command buffer.
            // The command buffer is still recording at this point.
            VulkanModCompat.recordFrameCopyOps(cmdBuf, swapchainImage, w, h);

            // REAL FSR4 INPUTS (2026-08-05): record the VK compute MV-gen
            // dispatch into the SAME command buffer. It writes the pool's
            // depth (0.5 constant), motion vectors and reactive mask (computed
            // from the matrices set by FSRProcessor.onSceneRendered via
            // setVkModMvGenMatrices) into the imported pool textures[1..3].
            // This runs BEFORE the frame fence is stored below, so the next
            // frame's waitForFrameCopy() + D3D12 FSR4 dispatch sees valid
            // inputs — without this, pool[1..3] stayed empty and "FSR4" was
            // a 1:1 CopyResource passthrough (the pipeline-test bypass) that
            // looked like FSR1.
            VulkanModCompat.recordVkModMvGen(cmdBuf);

            // REVIEW FIX (2026-08-05): mark the pool as having valid inputs
            // NOW (after Copy1 + MV-gen were recorded). FSRProcessor's VKMod
            // dispatch block gates on this flag so the very first frame (pool
            // still undefined — Copy1 hadn't run yet) does not dispatch FSR4
            // on garbage input and present the garbage result.
            VulkanModCompat.fsr4InputPopulated = true;

            // Store THIS frame's submission fence so the next frame's
            // waitForFrameCopy() can wait on it (bounded) instead of draining the
            // whole graphics queue with vkQueueWaitIdle.
            storeFrameFence(renderer);

        } catch (Exception e) {
            FSRMod.LOGGER.warn("VulkanMod copy: {}", e.getMessage());
        }
    }

    /**
     * Store THIS frame's submission fence so the next frame's waitForFrameCopy()
     * can wait on it (bounded) instead of draining the whole graphics queue with
     * vkQueueWaitIdle. Grabbed from Renderer.inFlightFences[getCurrentFrame()]
     * via reflection; on any failure the native side falls back to vkQueueWaitIdle.
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
