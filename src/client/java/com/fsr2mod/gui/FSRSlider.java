package com.fsr2mod.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.MouseButtonEvent;
import java.util.function.Consumer;

public class FSRSlider extends AbstractWidget {
    private static final int TRACK_HEIGHT = 4;
    private static final int KNOB_RADIUS = 6;
    private static final int TICK_HEIGHT = 6;

    private double value;
    private double step;
    private boolean showTicks;
    private Consumer<Double> onChange;

    public FSRSlider(int x, int y, int width, double initial, double step, boolean showTicks, Consumer<Double> onChange) {
        super(x, y, width, 20, Component.empty());
        this.value = initial;
        this.step = step;
        this.showTicks = showTicks;
        this.onChange = onChange;
    }

    public double getValue() { return value; }

    private double snap(double v) {
        if (step <= 0) return v;
        return Math.round(v / step) * step;
    }

    private int trackLeft() { return getX() + KNOB_RADIUS; }
    private int trackRight() { return getRight() - KNOB_RADIUS; }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int cy = getY() + getHeight() / 2;

        context.fill(trackLeft(), cy - TRACK_HEIGHT / 2, trackRight(), cy + TRACK_HEIGHT / 2 + 1, 0xFF444444);
        int fillEnd = trackLeft() + (int) ((trackRight() - trackLeft()) * value);
        context.fill(trackLeft(), cy - TRACK_HEIGHT / 2, fillEnd, cy + TRACK_HEIGHT / 2 + 1, 0xFF00C8E0);

        if (showTicks && step > 0) {
            int numTicks = (int) Math.round(1.0 / step);
            for (int i = 0; i <= numTicks; i++) {
                int tx = trackLeft() + (trackRight() - trackLeft()) * i / numTicks;
                context.verticalLine(tx, cy - TICK_HEIGHT / 2, cy + TICK_HEIGHT / 2, 0xFF666666);
            }
        }

        int knobX = trackLeft() + (int) ((trackRight() - trackLeft()) * value);
        context.fill(knobX - KNOB_RADIUS, cy - KNOB_RADIUS, knobX + KNOB_RADIUS, cy + KNOB_RADIUS, 0xFF00C8E0);
        context.fill(knobX - KNOB_RADIUS + 2, cy - KNOB_RADIUS + 2, knobX + KNOB_RADIUS - 2, cy + KNOB_RADIUS - 2, 0xFFFFFFFF);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(String.format("%.0f%%", value * 100)));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        setValueFromMouse(event.x());
    }

    @Override
    public void onDrag(MouseButtonEvent event, double dx, double dy) {
        setValueFromMouse(event.x());
    }

    private void setValueFromMouse(double mx) {
        double raw = (mx - trackLeft()) / (trackRight() - trackLeft());
        value = snap(Math.max(0.0, Math.min(1.0, raw)));
        if (onChange != null) onChange.accept(value);
    }
}
