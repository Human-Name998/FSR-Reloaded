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
    public boolean fsr1Easu = false;
    public int debugMode = 0;

    public enum FSRVersion {
        FSR1("FSR 1 (EASU + RCAS)"),
        FSR2("FSR 2 (temporal)"),
        FSR3_UPS("FSR 3 (upscaler)"),
        FSR3_FG("FSR 3 (frame gen, experimental)"),
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
                    this.sharpness = Math.clamp(loaded.sharpness, 0.0f, 1.0f);
                    this.fsr1Easu = loaded.fsr1Easu;
                    this.debugMode = Math.clamp(loaded.debugMode, 0, 10);
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

    private static float clampToValidScale(float v) {
        float clamped = Math.max(0.25f, Math.min(1.0f, v));
        return Math.round(clamped / 0.05f) * 0.05f;
    }
}
