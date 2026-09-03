package com.fsr2mod.mixin;

import com.fsr2mod.FSRMod;
import com.fsr2mod.fsr.FSRProcessor;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * PHOTONICS COMPAT (2026-08-23, "zoomed into a corner + blinking only when
 * Photonics is enabled" fix):
 *
 * Photonics' ColorFramebuffer sizes its raytracing buffers from the WINDOW
 * (resolutionSupplier = Window.getWidth/Height), while the mod's Iris-scaled
 * proxy renders at irisQualityScale (0.5 -> 840x525 vs the 1680x1050 window).
 * The 2x mismatch destroys the image ("world magnified into a corner").
 *
 * This mixin redirects the Supplier.get() call inside updatePerFrame() to
 * return proxy dimensions while the scaled swap is active, so Photonics
 * allocates its GI/denoise buffers at 840x525.
 *
 * APPROACH HISTORY (v1 -> v2 -> v3):
 *   v1: @Redirect with "Supplier;get:()" - colon is invalid Mixin descriptor
 *       syntax (InvalidMemberDescriptorException).
 *   v2: @Shadow + @Inject(HEAD) field-swap on the private final
 *       resolutionSupplier field - Java 21 enforces final at runtime ->
 *       IllegalAccessError crash.
 *   v3: @Redirect, descriptor "Supplier;get()Ljava/lang/Object;" (no colon),
 *       handler param = the Supplier receiver, returns proxy dims. Applied
 *       cleanly (no transform error in the 13:40 session).
 *
 * PROBE (added 13:50): updatePerFrame TAIL logs the ACTUAL allocated
 * realWidth/realHeight after Photonics' own code ran (throttled + one-shot
 * per size) so a session proves whether the redirect took effect.
 */
@Mixin(targets = "at.redi2go.photonics.client.rendering.opengl.rendering.ColorFramebuffer")
public abstract class PhotonicsColorFramebufferMixin {

    /** Photonics' per-frame fields (realW/H = pre-scale supplier size). */
    @Shadow(remap = false)
    private int realWidth;
    @Shadow(remap = false)
    private int realHeight;
    @Shadow(remap = false)
    private int width;
    @Shadow(remap = false)
    private int height;

    private long fsr$photonicsLastProbeLogMs;
    private int fsr$photonicsProbeCount;

    /**
     * Redirect the Supplier.get() call inside updatePerFrame().
     * Handler signature for an instance-method redirect: (receiver, args...)
     * so `supplier` receives the Supplier the method is invoked on.
     */
    @Redirect(
        method = "updatePerFrame",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"
        ),
        remap = false
    )
    private Object fsr$redirectResolution(Supplier<Vector2f> supplier) {
        if (FSRProcessor.photonicsProxyActive) {
            return new Vector2f(
                (float) FSRProcessor.photonicsProxyW,
                (float) FSRProcessor.photonicsProxyH
            );
        }
        return supplier.get();
    }

    /**
     * TAIL probe: after Photonics finished updatePerFrame (which reallocated
     * attachments at the supplier size), report the REAL allocated size so
     * the session log proves whether the redirect took effect. Throttled to
     * every 5s.
     */
    @Inject(method = "updatePerFrame", at = @At("TAIL"), remap = false)
    private void fsr$logProbe(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - fsr$photonicsLastProbeLogMs < 5000L && fsr$photonicsProbeCount > 3) {
            return;
        }
        fsr$photonicsLastProbeLogMs = now;
        fsr$photonicsProbeCount++;
        boolean proxy = FSRProcessor.photonicsProxyActive;
        int wantW = proxy ? FSRProcessor.photonicsProxyW : -1;
        int wantH = proxy ? FSRProcessor.photonicsProxyH : -1;
        // SELF-HEALING VERDICT: when the swap is active, Photonics must allocate
        // at the proxy size. If it does not (the redirect failed to reach the
        // real allocation path, or Photonics sizes elsewhere), the onRenderBegin
        // swap gate disables the swap next frame and passes through native -
        // better a static (unscaled) image than the corner-zoom crop.
        boolean aligned;
        if (proxy) {
            aligned = (wantW > 0 && wantH > 0 && realWidth == wantW && realHeight == wantH);
        } else {
            aligned = true; // outside the swap any size is fine
        }
        com.fsr2mod.fsr.FSRProcessor.photonicsBuffersAligned = aligned;
        if (!aligned && !fsr$photonicsMisalignLogged) {
            fsr$photonicsMisalignLogged = true;
            FSRMod.LOGGER.warn("[PHOTONICS-SIZE] MISALIGNED: proxy active but Photonics allocated real={}x{} (want {}x{}) — swap will self-disable (native pass-through)",
                realWidth, realHeight, wantW, wantH);
        }
        FSRMod.LOGGER.info("[PHOTONICS-SIZE] proxyActive={} want={}x{} real={}x{} buf={}x{} aligned={}",
            proxy, wantW, wantH, realWidth, realHeight, width, height, aligned);
    }

    private boolean fsr$photonicsMisalignLogged;
}