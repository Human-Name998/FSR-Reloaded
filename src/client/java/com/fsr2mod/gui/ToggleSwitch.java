package com.fsr2mod.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public class ToggleSwitch extends AbstractWidget {
    private static final int TRACK_WIDTH = 40;
    private static final int TRACK_HEIGHT = 16;
    private static final int KNOB_SIZE = 12;
    private static final int KNOB_MARGIN = 2;
    private boolean on;
    private Consumer<Boolean> onChange;

    public ToggleSwitch(int x, int y, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, TRACK_WIDTH, TRACK_HEIGHT, Component.empty());
        this.on = initial;
        this.onChange = onChange;
    }

    public boolean isOn() { return on; }
    public void setOn(boolean v) { this.on = v; }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int trackColor = on ? 0xFF00C8E0 : 0xFF555555;
        int knobX = on ? getX() + TRACK_WIDTH - KNOB_SIZE - KNOB_MARGIN : getX() + KNOB_MARGIN;

        context.fill(getX(), getY(), getX() + TRACK_WIDTH, getY() + TRACK_HEIGHT, trackColor);
        context.fill(knobX, getY() + KNOB_MARGIN, knobX + KNOB_SIZE, getY() + KNOB_MARGIN + KNOB_SIZE, 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(on ? "On" : "Off"));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        on = !on;
        if (onChange != null) onChange.accept(on);
    }
}
