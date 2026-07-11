package com.fsr2mod;

import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.fsr.FSRProcessor;
import com.fsr2mod.gui.SettingsScreen;
import com.fsr2mod.sodium.SodiumCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FSRMod implements ClientModInitializer {
    public static final String MOD_ID = "fsr-reloaded";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static FSRMod instance;
    private FSRConfig config;
    private FSRProcessor processor;
    private KeyMapping toggleKey;
    private KeyMapping cycleVersionKey;
    private KeyMapping increaseSharpnessKey;
    private KeyMapping decreaseSharpnessKey;
    private KeyMapping settingsKey;

    public static FSRMod getInstance() {
        return instance;
    }

    public FSRConfig getConfig() {
        return config;
    }

    public FSRProcessor getProcessor() {
        return processor;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        config = new FSRConfig();
        config.load();

        processor = new FSRProcessor(config);

        KeyMapping.Category category = new KeyMapping.Category(Identifier.withDefaultNamespace("category.fsr-reloaded"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fsr-reloaded.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category
        ));

        cycleVersionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fsr-reloaded.cycle_version",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                category
        ));

        increaseSharpnessKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fsr-reloaded.increase_sharpness",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        decreaseSharpnessKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fsr-reloaded.decrease_sharpness",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                category
        ));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fsr-reloaded.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register shutdown hook: FSRProcessor.destroy() must run before
        // the JVM unloads the native DLL (fsrvk.dll).  If it doesn't, the
        // D3D12Device singleton's destructor runs during DLL unloading and
        // tries to Release() D3D12 COM objects when the D3D12 runtime may
        // already be partially torn down — this causes
        // STATUS_STACK_BUFFER_OVERRUN (0xC0000409) / GS cookie crash.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (processor != null) {
                processor.destroy();
                processor = null;
            }
            LOGGER.info("FSR Reloaded destroyed");
        });

        if (SodiumCompat.isSodiumPresent()) {
            LOGGER.info("Sodium detected - enabling compatibility mode");
        }

        LOGGER.info("FSR Reloaded initialized");
    }

    private String versionLabel() {
        return config.version.getLabel();
    }

    private void onClientTick(Minecraft client) {
        while (settingsKey.consumeClick()) {
            client.setScreen(new SettingsScreen(client.screen));
        }

        if (client.player == null) return;

        while (toggleKey.consumeClick()) {
            config.enabled = !config.enabled;
            config.save();
            var window = client.getWindow();
            if (config.enabled) {
                LOGGER.info("FSR enabled, version={}, target res={}x{}", versionLabel(),
                        Math.round(window.getWidth() * config.qualityScale),
                        Math.round(window.getHeight() * config.qualityScale));
            } else {
                LOGGER.info("FSR disabled");
            }
            client.player.sendSystemMessage(
                    Component.literal(
                            config.enabled
                                    ? "FSR §aenabled§r (" + versionLabel() + ")"
                                    : "FSR §cdisabled§r"
                    )
            );
        }

        while (cycleVersionKey.consumeClick()) {
            FSRConfig.FSRVersion[] versions = FSRConfig.FSRVersion.values();
            int next = (config.version.ordinal() + 1) % versions.length;
            config.version = versions[next];
            config.save();
            processor.markDirty();
            LOGGER.info("FSR version switched to: {} ({})", config.version, config.version.getLabel());
            if (config.enabled) {
                // Main FBO stays at full resolution — no resize needed
            }
            client.player.sendSystemMessage(
                    Component.literal("FSR version: " + config.version.getLabel())
            );
        }

        while (increaseSharpnessKey.consumeClick()) {
            if (config.enabled && (config.version == FSRConfig.FSRVersion.FSR2 || config.version == FSRConfig.FSRVersion.FSR3_UPS)) {
                config.sharpness = Math.min(1.0f, Math.round((config.sharpness + 0.1f) * 10.0f) / 10.0f);
                config.save();
                String label = config.version == FSRConfig.FSRVersion.FSR3_UPS ? "FSR3" : "FSR2";
                client.player.sendSystemMessage(
                        Component.literal(label + " sharpness: " + (config.sharpness == 0.0f ? "Off" : Math.round(config.sharpness * 100) + "%"))
                );
            }
        }

        while (decreaseSharpnessKey.consumeClick()) {
            if (config.enabled && (config.version == FSRConfig.FSRVersion.FSR2 || config.version == FSRConfig.FSRVersion.FSR3_UPS)) {
                config.sharpness = Math.max(0.0f, Math.round((config.sharpness - 0.1f) * 10.0f) / 10.0f);
                config.save();
                String label = config.version == FSRConfig.FSRVersion.FSR3_UPS ? "FSR3" : "FSR2";
                client.player.sendSystemMessage(
                        Component.literal(label + " sharpness: " + (config.sharpness == 0.0f ? "Off" : Math.round(config.sharpness * 100) + "%"))
                );
            }
        }
    }
}
