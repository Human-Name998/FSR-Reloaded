package com.fsr2mod.config;

import com.fsr2mod.FSRMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class FSRConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fsr-reloaded.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public FSRVersion version = FSRVersion.FSR1;
    public float qualityScale = 0.77f;
    public float irisQualityScale = 0.95f;
    public float sharpness = 0.7f;
    public boolean fsr1Easu = true;
    /** Experimental FSR1 EASU/RCAS path through Iris shader packs. Disabled by default because some AMD drivers crash when the post-process runs on an Iris composite. */
    public boolean fsr1IrisExperimental = false;
    /** Target-FPS auto-quality (0 = off): while >0, FSRProcessor continuously measures render FPS and nudges qualityScale (0.05 steps, ~2s cooldown, hysteresis) to hit the target. 30-240 allowed. FG modes / Iris / menus are excluded. */
    public int targetFps = 0;
    public int debugMode = 0;
    public boolean fgFrameSkip = true;
    public int fgMultiplier = 2;
    /** Async FSR4 overlap: submit dispatch without waiting, defer the fence wait + output copy to the next frame's start (hides the 3-6ms GPU wait behind the present + next render; adds ~1 frame input latency). FSR3_FG_DX12 stays synchronous (FG needs the same-frame output). */
    public boolean fsr4Async = true;
    // Near-parity bypass: at >=90% render scale, FSR4's upscale is at most
    // 1.11x but its fixed display-res round trip (D3D12 dispatch + external-
    // memory output copy + sync) costs more than the render savings on an
    // iGPU — route through the cheap FSR1 blit+RCAS instead (default on).
    public boolean fsr4ParityBypass = true;
    /** EXPERIMENTAL near-depth motion MVs for the constant-depth (no real
     *  depth) FSR4 fallback. When true, the MV-gen reprojects at near plane
     *  (ndcZ 0, z≈1 block) to AGREE with the SDK's accumulation depth (device
     *  0.5, also z≈1) — fixing the scale-dependent-jaggies desync where MVs
     *  assumed z≈500 while the SDK reprojected history at z≈1. Walking terrain
     *  gets genuine translation parallax → history tracked → smooth diagonals
     *  during motion. Tradeoff (accepted): sky/horizon get the same near
     *  parallax, so they smear during camera translation/rotation. Default
     *  OFF, matching the validated far-reprojection baseline. Only affects the
     *  uUseDepth=0 fallback; real-depth sessions ignore it. */
    public boolean fsr4NearDepthMotion = false;
    /** Re-arm FSR3_FG_DX12 on AMD integrated GPUs. The AMD FG SDK's
     *  ffxCreateContext historically removes the D3D12 device at the first
     *  frame when it coexists with the FSR4 context on iGPUs (Radeon 860M),
     *  so the mod deliberately disabled FG there. Default ON here so the
     *  frame gen the user reports as GPU-compatible actually runs; the full
     *  fail-degradation chain (fg4PermanentlyDisabled, fgRuntimeFailCount,
     *  fence-stall → noteD3D12RuntimeFailure) still guards against a genuine
     *  device-removal by degrading to plain FSR4. */
    public boolean fsr3FgAllowIgpuDx12 = true;
    /** URL to download the FSR2/3 GLSL shader pack from at game launch (a zip with fsr2/*.glsl + fsr3/*.glsl). Empty = skip the download (embedded bootstrap copies are used instead). */
    public String fsr2ShaderUrl = "";
    /** When true (default), missing shader files are bootstrapped from the embedded copies in the jar so the mod always works offline. Set false for strict no-embed: FSR2/3 then require fsr2ShaderUrl to be configured and reachable. */
    public boolean fsr2ShaderBootstrap = true;
    /** VulkanMod render-scale (POC, 2026-08-05): when true and qualityScale < 1.0, the mod swaps VulkanMod's main pass framebuffer for a scaled-size one (scene renders at qualityScale) and bilinear-blits it up to the swapchain each frame — the VulkanMod equivalent of the GL path's render downscale + FSR1 upscale. The D3D12 FSR4 pipeline-test path is skipped while this is active (CopyResource can't resize). */
    public boolean vulkanModRenderScale = true;

    public enum FSRVersion {
        FSR1("FSR 1 (EASU + RCAS)"),
        FSR2("FSR 2 (temporal)"),
        FSR3_UPS("FSR 3 (upscaler)"),
        FSR3_FG("FSR 3 (frame gen, experimental)"),
        FSR3_FG_DX12("FSR 3 (frame gen DX12)"),
        FSR4("FSR 4 (SDK)");

        private final String label;

        FSRVersion(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public float getScale() {
        return qualityScale;
    }

    public float getIrisScale() {
        return irisQualityScale;
    }

    public void load() {
        if (CONFIG_PATH.toFile().exists()) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                FSRConfig loaded = GSON.fromJson(reader, FSRConfig.class);
                if (loaded != null) {
                    this.enabled = loaded.enabled;
                    this.version = loaded.version;
                    this.qualityScale = clampToValidScale(loaded.qualityScale);
                    this.irisQualityScale = clampToValidScale(loaded.irisQualityScale);
                    this.sharpness = Math.max(0.0f, Math.min(loaded.sharpness, 1.0f));
                    this.fsr1Easu = loaded.fsr1Easu;
                    this.fsr1IrisExperimental = loaded.fsr1IrisExperimental;
                    this.targetFps = clampedTargetFps(loaded.targetFps);
                    this.debugMode = Math.max(0, Math.min(loaded.debugMode, 10));
                    this.fgFrameSkip = loaded.fgFrameSkip;
                    this.fgMultiplier = clampedFgMultiplier(loaded.fgMultiplier);
                    this.fsr4Async = loaded.fsr4Async;
                    this.fsr4NearDepthMotion = loaded.fsr4NearDepthMotion;
                    this.fsr3FgAllowIgpuDx12 = loaded.fsr3FgAllowIgpuDx12;
                    this.fsr2ShaderUrl = loaded.fsr2ShaderUrl != null ? loaded.fsr2ShaderUrl : "";
                    this.fsr2ShaderBootstrap = loaded.fsr2ShaderBootstrap;
                    this.vulkanModRenderScale = loaded.vulkanModRenderScale;
                }
            } catch (IOException e) {
                FSRMod.LOGGER.error("Failed to load config", e);
            }
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            FSRMod.LOGGER.error("Failed to save config", e);
        }
    }

    private static int clampedFgMultiplier(int v) {
        if (v < 1) return 1;
        if (v > 4) return 4;
        return v;
    }

    private static float clampToValidScale(float v) {
        float clamped = Math.max(0.25f, Math.min(1.0f, v));
        return Math.round(clamped / 0.05f) * 0.05f;
    }

    private static int clampedTargetFps(int v) {
        if (v <= 0) return 0;
        return Math.max(30, Math.min(240, v));
    }
}
