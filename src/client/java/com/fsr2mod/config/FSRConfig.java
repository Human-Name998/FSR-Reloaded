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
                    this.targetFps = clampedTargetFps(loaded.targetFps);
                    this.debugMode = Math.max(0, Math.min(loaded.debugMode, 10));
                    this.fgFrameSkip = loaded.fgFrameSkip;
                    this.fgMultiplier = clampedFgMultiplier(loaded.fgMultiplier);
                    this.fsr4Async = loaded.fsr4Async;
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
