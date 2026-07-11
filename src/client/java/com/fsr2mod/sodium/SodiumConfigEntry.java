package com.fsr2mod.sodium;

import com.fsr2mod.FSRMod;
import com.fsr2mod.config.FSRConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.Set;

public class SodiumConfigEntry implements ConfigEntryPoint {
    private static final StorageEventHandler NOOP_STORAGE = () -> {};

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        FSRConfig config = FSRMod.getInstance().getConfig();

        builder.registerOwnModOptions()
            .addPage(builder.createOptionPage()
                .setName(Component.literal("FSR Reloaded"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createBooleanOption(
                            Identifier.parse("fsr-reloaded:enabled"))
                        .setName(Component.literal("Enable FSR"))
                        .setTooltip(Component.literal("Toggles FSR Reloaded upscaling. Requires renderer reload."))
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(NOOP_STORAGE)
                        .setBinding(v -> { config.enabled = v; config.save(); }, () -> config.enabled)
                        .setDefaultValue(true))
                    .addOption(builder.createEnumOption(
                            Identifier.parse("fsr-reloaded:version"),
                            FSRConfig.FSRVersion.class)
                        .setName(Component.literal("FSR Version"))
                        .setTooltip(Component.literal("Selects the FSR algorithm version. FSR1 is fastest; FSR2 and FSR3 offer higher quality."))
                        .setAllowedValues(Set.of(FSRConfig.FSRVersion.values()))
                        .setElementNameProvider(v -> Component.literal(v.getLabel()))
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(NOOP_STORAGE)
                        .setBinding(v -> {
                            config.version = v;
                            config.save();
                            FSRMod.getInstance().getProcessor().markDirty();
                        }, () -> config.version)
                        .setDefaultValue(FSRConfig.FSRVersion.FSR1))
                    .addOption(builder.createIntegerOption(
                            Identifier.parse("fsr-reloaded:quality"))
                        .setName(Component.literal("Render Quality"))
                        .setTooltip(Component.literal("Internal render scale as a percentage of display resolution. Lower values improve performance."))
                        .setRange(25, 100, 5)
                        .setValueFormatter(v -> Component.literal(v + "%"))
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(NOOP_STORAGE)
                        .setBinding(v -> {
                            config.qualityScale = v / 100f;
                            config.save();
                            FSRMod.getInstance().getProcessor().markDirty();
                        }, () -> Math.round(config.qualityScale * 100f))
                        .setDefaultValue(75))
                    .addOption(builder.createIntegerOption(
                            Identifier.parse("fsr-reloaded:sharpness"))
                        .setName(Component.literal("Sharpness"))
                        .setTooltip(Component.literal("RCAS adaptive sharpening strength. Set to 0 to disable sharpening."))
                        .setRange(0, 100, 5)
                        .setValueFormatter(v -> v == 0 ? Component.literal("Off") : Component.literal(v + "%"))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(NOOP_STORAGE)
                        .setBinding(v -> {
                            config.sharpness = v / 100f;
                            config.save();
                        }, () -> Math.round(config.sharpness * 100f))
                        .setDefaultValue(70))));
    }
}
