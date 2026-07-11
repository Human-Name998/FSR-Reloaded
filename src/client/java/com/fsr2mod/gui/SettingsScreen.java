package com.fsr2mod.gui;

import com.fsr2mod.FSRMod;
import com.fsr2mod.config.FSRConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SettingsScreen extends Screen {
    private static final int TAB_WIDTH = 120;
    private static final int TAB_HEIGHT = 28;
    private static final int CONTENT_Y = 100;

    private static final int ACCENT = 0xFF00C8E0;
    private static final int BG_DARK = 0xAA000000;
    private static final int BG_TAB_INACTIVE = 0xFF333333;
    private static final int BG_TAB_ACTIVE = 0xFF1A1A1A;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int TEXT_DIM = 0xFF888888;

    private final Screen parent;
    private final FSRConfig config;
    private final FSRMod mod;
    private int activeTab = 0;
    private Component currentValueLabel = Component.empty();
    private final List<Renderable> myWidgets = new ArrayList<>();
    private ToggleSwitch sharpeningToggle;
    private ToggleSwitch easuToggle;
    private FSRSlider sharpnessSlider;

    public SettingsScreen(Screen parent) {
        super(Component.literal("FSR Reloaded Settings"));
        this.parent = parent;
        this.config = FSRMod.getInstance().getConfig();
        this.mod = FSRMod.getInstance();
    }

    @Override
    protected void init() {
        myWidgets.clear();
        int cx = width / 2;
        int tabStartX = cx - (3 * TAB_WIDTH) / 2;

        String[] tabLabels = {"FSR Version", "Quality", "Sharpness"};
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            addTracked(new TabButton(tabStartX + i * TAB_WIDTH, 60, TAB_WIDTH, TAB_HEIGHT, Component.literal(tabLabels[i]), i == activeTab, btn -> {
                activeTab = ti;
                SettingsScreen.this.init(width, height);
            }));
        }

        addTracked(new ToggleSwitch(width - 50, 15, config.enabled, enabled -> {
            config.enabled = enabled;
            config.save();
        }));

        int contentX = cx - 150;
        int contentW = 300;
        int y = CONTENT_Y;

        if (activeTab == 0) {
            FSRConfig.FSRVersion[] versions = FSRConfig.FSRVersion.values();
            for (FSRConfig.FSRVersion v : versions) {
                final FSRConfig.FSRVersion fv = v;
                boolean selected = v == config.version;
                addTracked(new VersionPill(contentX, y, contentW, 24, Component.literal(v.getLabel()), selected, btn -> {
                    config.version = fv;
                    config.save();
                    mod.getProcessor().markDirty();
                    SettingsScreen.this.init(width, height);
                }));
                y += 30;
            }
        } else if (activeTab == 1) {
            int pct = Math.round(config.qualityScale * 100f);
            currentValueLabel = Component.literal("Quality: " + pct + "%");
            addTracked(new FSRSlider(contentX + 20, y, contentW - 40, config.qualityScale, 0.05, true, val -> {
                config.qualityScale = Math.round((float)(double)val * 100f) / 100f;
                config.save();
                mod.getProcessor().markDirty();
                currentValueLabel = Component.literal("Quality: " + Math.round(config.qualityScale * 100f) + "%");
            }));
        } else if (activeTab == 2) {
            boolean sharpeningOn = config.sharpness > 0.001f;
            currentValueLabel = Component.literal(sharpeningOn ? "Sharpness: " + Math.round(config.sharpness * 100f) + "%" : "Sharpening: Off");

            sharpeningToggle = new ToggleSwitch(contentX + 20, y, sharpeningOn, on -> {
                if (!on) {
                    config.sharpness = 0.0f;
                } else if (config.sharpness <= 0.001f) {
                    config.sharpness = 0.7f;
                }
                config.save();
                int p = Math.round(config.sharpness * 100f);
                currentValueLabel = Component.literal(p == 0 ? "Sharpening: Off" : "Sharpness: " + p + "%");
                SettingsScreen.this.init(width, height);
            });
            addTracked(sharpeningToggle);
            y += 24;

            if (sharpeningOn) {
                sharpnessSlider = new FSRSlider(contentX + 20, y, contentW - 40, config.sharpness, 0.01, false, val -> {
                    config.sharpness = Math.max(0.01f, Math.round((float)(double)val * 100f) / 100f);
                    config.save();
                    int p = Math.round(config.sharpness * 100f);
                    currentValueLabel = Component.literal("Sharpness: " + p + "%");
                });
                addTracked(sharpnessSlider);
                y += 34;
            }

            y += 8;
            easuToggle = new ToggleSwitch(contentX + 20, y, config.fsr1Easu, on -> {
                config.fsr1Easu = on;
                config.save();
            });
            addTracked(easuToggle);
        }

        addTracked(new DoneButton(cx - 40, height - 32, 80, 22));
    }

    private <T extends AbstractWidget> T addTracked(T widget) {
        addRenderableWidget(widget);
        myWidgets.add(widget);
        return widget;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, BG_DARK);

        context.centeredText(font, getTitle(), width / 2, 20, 0xFFFFFF);

        String status = config.enabled ? "Enabled" : "Disabled";
        context.text(font, Component.literal("FSR " + status).withStyle(ChatFormatting.GRAY), width - 115, 18, TEXT_DIM);

        if (activeTab == 1 || activeTab == 2) {
            if (activeTab == 2) {
                // rendered inline below
            } else {
                context.centeredText(font, currentValueLabel, width / 2, CONTENT_Y + 30, ACCENT);
            }
        }
        if (activeTab == 2) {
            boolean sharpeningOn = config.sharpness > 0.001f;
            int labelX = width / 2 - 150 + 20 + 40 + 10;
            if (sharpeningToggle != null) {
                context.text(font, Component.literal("Sharpening"), labelX, sharpeningToggle.getY() + 4, TEXT_COLOR);
            }
            if (sharpeningOn && sharpnessSlider != null) {
                context.centeredText(font, currentValueLabel, width / 2, sharpnessSlider.getY() + 24, ACCENT);
            }
            if (easuToggle != null) {
                context.text(font, Component.literal("EASU edge smoothing"), labelX, easuToggle.getY() + 4, TEXT_COLOR);
            }
        }

        for (Renderable r : myWidgets) {
            r.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreen(parent);
    }

    private class TabButton extends AbstractWidget {
        private final boolean active;
        private final Consumer<AbstractWidget> onClick;

        TabButton(int x, int y, int w, int h, Component label, boolean active, Consumer<AbstractWidget> onClick) {
            super(x, y, w, h, label);
            this.active = active;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int bg = active ? BG_TAB_ACTIVE : BG_TAB_INACTIVE;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            if (active) {
                context.fill(getX(), getBottom() - 3, getRight(), getBottom(), ACCENT);
            }
            int color = active ? 0xFFFFFFFF : TEXT_COLOR;
            context.centeredText(font, getMessage(), getX() + width / 2, getY() + 7, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onClick.accept(this);
        }
    }

    private class DoneButton extends AbstractWidget {
        DoneButton(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal("Done"));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int bg = isHoveredOrFocused() ? 0xFF004A55 : 0xFF00333A;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            context.fill(getX(), getY(), getRight(), getY() + 1, ACCENT);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), ACCENT);
            context.fill(getX(), getY(), getX() + 1, getBottom(), ACCENT);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), ACCENT);
            context.centeredText(font, getMessage(), getX() + width / 2, getY() + 6, 0xFFFFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onClose();
        }
    }

    private class VersionPill extends AbstractWidget {
        private final boolean selected;
        private final Consumer<AbstractWidget> onClick;

        VersionPill(int x, int y, int w, int h, Component label, boolean selected, Consumer<AbstractWidget> onClick) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int bg = selected ? 0xFF1A3A3A : 0xFF222222;
            int border = selected ? ACCENT : 0xFF444444;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            context.fill(getX(), getY(), getRight(), getY() + 1, border);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), border);
            context.fill(getX(), getY(), getX() + 1, getBottom(), border);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), border);
            context.centeredText(font, getMessage(), getX() + width / 2, getY() + 6, selected ? 0xFFFFFFFF : TEXT_COLOR);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onClick.accept(this);
        }
    }
}
