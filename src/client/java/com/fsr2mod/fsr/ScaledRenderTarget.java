package com.fsr2mod.fsr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * A RenderTarget proxy that stores scaled resolution in width/height
 * and delegates texture access to scaled-down textures.
 *
 * Reporting scaled dimensions ensures LevelRenderer creates all internal
 * render targets (translucent, particles, weather, clouds) at the scaled
 * resolution — without this, all those targets are at full native
 * resolution, defeating render scaling's performance benefit.
 */
public class ScaledRenderTarget extends RenderTarget {

    // The REAL vanilla main render target. When Minecraft's own resize path
    // (onResolutionChanged -> mainRenderTarget.resize) lands on this proxy
    // while it is swapped into the mainRenderTarget field (window dragged to
    // another monitor / DPI change mid-session), a no-op here leaves the real
    // target at a stale size -> the GUI renders at the new window size into
    // an old-size texture and gets clipped (only bottom-left visible).
    // resize() therefore FORWARDS to the real target.
    private final RenderTarget delegate;

    // IRIS DEPTH-BUFFER VERSION (2026-08-29, "switching the FSR version makes
    // the terrain disappear until a quality change" fix).
    //
    // Iris decides whether to re-adopt the main depth texture (its depthtex0 /
    // gbuffer depth attachment) in
    // net.irisshaders.iris.targets.RenderTargets#resizeIfNeeded, called every
    // frame from IrisRenderingPipeline#beginLevelRendering with
    // ((Blaze3dRenderTargetExt) mainRenderTarget).iris$getDepthBufferVersion().
    // The freshly passed depth texture is adopted ONLY when that version
    // differs from the cached one, or the width / height / depth format
    // changed. Otherwise Iris keeps rendering against whatever it cached.
    //
    // The vanilla bump lives in Iris's MixinRenderTarget#iris$onDestroyBuffers
    // (an @Inject on RenderTarget.destroyBuffers()) — and this proxy overrides
    // destroyBuffers() as a no-op because it does not own the textures. On top
    // of that a brand-new proxy instance is built every frame, so the inherited
    // counter was permanently 0: Iris cached 0 once and never re-adopted a
    // depth texture again. Recreating the underlying TextureTarget at UNCHANGED
    // dimensions (exactly what every FSR version switch used to do through
    // markDirty()) therefore left Iris depth-testing the world against a
    // CLOSED texture: sky (no depth test / drawn at far) still rendered,
    // terrain did not. Only a quality change altered the dimensions, took
    // Iris's sizeChanged branch and rebound everything — which is precisely
    // why the terrain "comes back when I play with the quality settings".
    //
    // Reporting a version that FSRProcessor bumps every time it replaces the
    // underlying render target makes Iris re-adopt the depth texture on ANY
    // recreate, at any size. These two iris$ methods override the ones Iris's
    // mixin puts on RenderTarget (identical name + signature = a plain Java
    // override), so there is no compile-time dependency on Iris.
    private final int bufferVersion;

    /**
     * Version handed to Iris through {@code Blaze3dRenderTargetExt}; only read
     * while a shader pack is active. Not annotated {@code @Override}: the
     * parent method is contributed by an Iris mixin and does not exist at
     * compile time.
     */
    public int iris$getDepthBufferVersion() {
        return bufferVersion;
    }

    public int iris$getColorBufferVersion() {
        return bufferVersion;
    }

    public ScaledRenderTarget(int scaledW, int scaledH,
                              GpuTexture scaledColorTex, GpuTextureView scaledColorView,
                              GpuTexture scaledDepthTex, GpuTextureView scaledDepthView,
                              RenderTarget delegate) {
        this(scaledW, scaledH, scaledColorTex, scaledColorView,
             scaledDepthTex, scaledDepthView, delegate, 0);
    }

    public ScaledRenderTarget(int scaledW, int scaledH,
                              GpuTexture scaledColorTex, GpuTextureView scaledColorView,
                              GpuTexture scaledDepthTex, GpuTextureView scaledDepthView,
                              RenderTarget delegate, int bufferVersion) {
        super("fsr-proxy", scaledDepthTex != null);
        if ((scaledDepthTex == null) != (scaledDepthView == null)) {
            throw new IllegalArgumentException(
                "depthTexture and depthTextureView must both be null or both non-null");
        }
        this.delegate = delegate;
        this.bufferVersion = bufferVersion;
        this.width = scaledW;
        this.height = scaledH;
        this.colorTexture = scaledColorTex;
        this.colorTextureView = scaledColorView;
        this.depthTexture = scaledDepthTex;
        this.depthTextureView = scaledDepthView;
    }

    @Override
    public void destroyBuffers() {
        // No-op: we don't own the textures, the original TextureTarget does
    }

    @Override
    public void resize(int width, int height) {
        // Forward to the real main render target (see field comment). The
        // proxy's OWN dimensions are managed by FSRProcessor, which recreates
        // it when the underlying TextureTarget is resized.
        if (delegate != null) {
            delegate.resize(width, height);
        }
    }

    @Override
    public void createBuffers(int width, int height) {
        // No-op: textures are provided externally
    }
}