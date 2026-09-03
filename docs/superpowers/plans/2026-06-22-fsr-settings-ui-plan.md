# FSR Settings Tabbed UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace vanilla-Minecraft-styled settings screen with a custom FSR-themed tabbed UI and replace the FSRQuality enum with a direct float scale.

**Architecture:** Three custom widgets (ToggleSwitch, FSRSlider) in `gui/` package built on `AbstractWidget`; `SettingsScreen` rewritten with tab state machine; `FSRConfig` loses enum, gains `qualityScale` float.

**Tech Stack:** Minecraft 26.1.2, Fabric Loom, Java 25, LWJGL

## Global Constraints

- All custom widgets extend `AbstractWidget` and override `extractWidgetRenderState()`, `updateWidgetNarration()`, `onClick()`
- FSR accent color: `#00C8E0` → packed as `0xFF00C8E0`
- Dark overlay background: `0xAA000000`
- Slider values stored as double `0.0–1.0`, converted to/from percentage for display
- `config.qualityScale` clamped to `[0.25f, 1.0f]`, rounded to nearest `0.05f`
- Tab switching calls `init(width, height)` to clear children and rebuild
- `extractRenderState` draws custom dark overlay background FIRST, then iterates renderables (does NOT call `super.extractRenderState()` which would draw the Minecraft menu background)
- Input events use Minecraft 26.1.2 types: `MouseButtonEvent`, `InputWithModifiers`

---
### Task 1: Config — Remove FSRQuality enum, add qualityScale

**Files:**
- Modify: `src/client/java/com/fsr2mod/config/FSRConfig.java`

**Interfaces:**
- Consumes: nothing
- Produces: `FSRConfig.qualityScale` field (float), `FSRQuality` enum removed

- [ ] **Remove the `FSRQuality` enum from `FSRConfig.java`**

Delete this entirely (lines ~15–37 in original):
```java
public enum FSRQuality {
    ULTRA_QUALITY(0.77f, "Ultra Quality"),
    QUALITY(0.67f, "Quality"),
    BALANCED(0.59f, "Balanced"),
    PERFORMANCE(0.50f, "Performance"),
    ULTRA_PERFORMANCE(0.33f, "Ultra Performance"),
    SUPER_PERFORMANCE(0.25f, "Super Performance");

    private final float scale;
    private final String label;

    FSRQuality(float scale, String label) { this.scale = scale; this.label = label; }
    public float getScale() { return scale; }
    public String getLabel() { return label; }
}
```

- [ ] **Add `qualityScale` field**

Add to field declarations after `private float sharpness`:
```java
private float qualityScale = 0.77f;
```

- [ ] **Update `load()` method**

Replace `qualityMode` deserialization with:
```java
qualityScale = clampToValidScale(obj.get("qualityScale").getAsFloat());
```

Add helper:
```java
private float clampToValidScale(float v) {
    float clamped = Math.max(0.25f, Math.min(1.0f, v));
    return Math.round(clamped / 0.05f) * 0.05f;
}
```

Remove `qualityMode` from the save object in `save()` and add:
```java
jsonObj.addProperty("qualityScale", qualityScale);
```

- [ ] **Add `getScale()` convenience method**

```java
public float getScale() { return qualityScale; }
```

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 2: Update consumers — FSRProcessor, FSRMod

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`
- Modify: `src/client/java/com/fsr2mod/FSRMod.java`

**Interfaces:**
- Consumes: `config.qualityScale` (float), `config.getScale()` from Task 1
- Produces: all `config.qualityMode.getScale()` → `config.qualityScale`; F9 quality-cycling removed

- [ ] **Replace all `config.qualityMode.getScale()` with `config.qualityScale` in `FSRProcessor.java`**

Search and replace (3 occurrences — in `onRenderBegin()` line 153, `onResize()` line 392, `markDirty()` line 418):
```java
// Old:
float scale = config.qualityMode.getScale();
// New:
float scale = config.qualityScale;
```

- [ ] **Remove F9 quality cycle key + quality references in `FSRMod.java`**

Remove `cycleQualityKey` field declaration:
```java
private KeyMapping cycleQualityKey; // find exact field name
```

In `onInitializeClient()` registration block, remove the `cycleQualityKey` registration.

In tick handler, remove the entire `if (cycleQualityKey.consumeClick()) { ... }` block (~7 lines).

Update the on-ready chat message (line ~108) — remove the `config.qualityMode.getLabel()` part:
```java
// Old:
return config.version.getLabel() + " " + config.qualityMode.getLabel();
// New:
return config.version.getLabel();
```

Update the resize log message (lines ~124-125):
```java
// Old:
Math.round(window.getWidth() * config.qualityMode.getScale()),
Math.round(window.getHeight() * config.qualityMode.getScale()));
// New:
Math.round(window.getWidth() * config.qualityScale),
Math.round(window.getHeight() * config.qualityScale));
```

Remove `import com.fsr2mod.config.FSRConfig.FSRQuality;` if it becomes unused.

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 3: Custom Toggle Switch widget

**Files:**
- Create: `src/client/java/com/fsr2mod/gui/ToggleSwitch.java`

**Interfaces:**
- Consumes: nothing (standalone widget)
- Produces: `ToggleSwitch` class — `isOn()`, `setOn(boolean)`, `setOnChange(Consumer<Boolean>)`

- [ ] **Create `ToggleSwitch.java`**

```java
package com.fsr2mod.gui;

import com.fsr2mod.config.FSRConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public class ToggleSwitch extends AbstractWidget {
    private static final int TRACK_WIDTH = 40;
    private static final int TRACK_HEIGHT = 16;
    private static final int KNOB_SIZE = 12;
    private static final int KNOB_MARGIN = 2;
    private static final int KNOB_OFFSET = TRACK_WIDTH - KNOB_SIZE - KNOB_MARGIN;

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
        // Track
        int trackColor = on ? 0xFF00C8E0 : 0xFF555555;
        int knobX = on ? getX() + KNOB_MARGIN + KNOB_OFFSET : getX() + KNOB_MARGIN;

        context.fill(getX(), getY(), getX() + TRACK_WIDTH, getY() + TRACK_HEIGHT, trackColor);
        // Knob circle
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
```

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 4: Custom Slider widget with tick support

**Files:**
- Create: `src/client/java/com/fsr2mod/gui/FSRSlider.java`

**Interfaces:**
- Consumes: nothing (standalone widget)
- Produces: `FSRSlider` class — `getValue()`, `setOnChange(Consumer<Double>)`, tick rendering

- [ ] **Create `FSRSlider.java`**

```java
package com.fsr2mod.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

public class FSRSlider extends AbstractWidget {
    private static final int TRACK_HEIGHT = 4;
    private static final int KNOB_RADIUS = 6;
    private static final int TICK_HEIGHT = 6;

    private double value; // 0.0 to 1.0
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
    public void setValue(double v) { value = Math.max(0.0, Math.min(1.0, v)); }

    private double snap(double v) {
        if (step <= 0) return v;
        return Math.round(v / step) * step;
    }

    private int trackLeft() { return getX() + KNOB_RADIUS; }
    private int trackRight() { return getRight() - KNOB_RADIUS; }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        int cy = getY() + getHeight() / 2;

        // Track background
        context.fill(trackLeft(), cy - TRACK_HEIGHT / 2, trackRight(), cy + TRACK_HEIGHT / 2 + 1, 0xFF444444);
        // Filled portion
        int fillEnd = trackLeft() + (int) ((trackRight() - trackLeft()) * value);
        context.fill(trackLeft(), cy - TRACK_HEIGHT / 2, fillEnd, cy + TRACK_HEIGHT / 2 + 1, 0xFF00C8E0);

        // Tick marks
        if (showTicks && step > 0) {
            int numTicks = (int) Math.round(1.0 / step);
            for (int i = 0; i <= numTicks; i++) {
                int tx = trackLeft() + (trackRight() - trackLeft()) * i / numTicks;
                context.verticalLine(tx, cy - TICK_HEIGHT / 2, cy + TICK_HEIGHT / 2, 0xFF666666);
            }
        }

        // Knob
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
```

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 5: Rewrite SettingsScreen with tabbed UI

**Files:**
- Modify: `src/client/java/com/fsr2mod/gui/SettingsScreen.java`

**Interfaces:**
- Consumes: `FSRConfig` (with `qualityScale`), `ToggleSwitch`, `FSRSlider`
- Produces: Full tabbed settings screen

- [ ] **Rewrite `SettingsScreen.java`**

```java
package com.fsr2mod.gui;

import com.fsr2mod.FSRMod;
import com.fsr2mod.config.FSRConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.narration.NarrationElementOutput;

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

    public SettingsScreen(Screen parent) {
        super(Component.literal("FSR Reloaded Settings"));
        this.parent = parent;
        this.config = FSRMod.getInstance().getConfig();
        this.mod = FSRMod.getInstance();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int tabStartX = cx - (3 * TAB_WIDTH) / 2;

        // Tab buttons — use init(width, height) on click to clear + rebuild
        String[] tabLabels = {"FSR Version", "Quality", "Sharpness"};
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            addRenderableWidget(new TabButton(tabStartX + i * TAB_WIDTH, 60, TAB_WIDTH, TAB_HEIGHT, Component.literal(tabLabels[i]), i == activeTab, btn -> {
                activeTab = ti;
                SettingsScreen.this.init(width, height); // clears children + rebuilds
            }));
        }

        // Master toggle — top right
        addRenderableWidget(new ToggleSwitch(cx + 180, 15, config.enabled, enabled -> {
            config.enabled = enabled;
            config.save();
        }));

        // Content widgets
        int contentX = cx - 150;
        int contentW = 300;
        int y = CONTENT_Y;

        if (activeTab == 0) {
            // FSR Version — radio pills
            FSRConfig.FSRVersion[] versions = FSRConfig.FSRVersion.values();
            for (FSRConfig.FSRVersion v : versions) {
                final FSRConfig.FSRVersion fv = v;
                boolean selected = v == config.version;
                addRenderableWidget(new VersionPill(contentX, y, contentW, 24, Component.literal(v.getLabel()), selected, btn -> {
                    config.version = fv;
                    config.save();
                    mod.getProcessor().markDirty();
                    SettingsScreen.this.init(width, height);
                }));
                y += 30;
            }
        } else if (activeTab == 1) {
            // Quality — slider with 5% steps, percentage readout
            int pct = Math.round(config.qualityScale * 100f);
            currentValueLabel = Component.literal("Quality: " + pct + "%");
            addRenderableWidget(new FSRSlider(contentX + 20, y, contentW - 40, config.qualityScale, 0.05, true, val -> {
                config.qualityScale = Math.round((float)(double)val * 100f) / 100f;
                config.save();
                mod.getProcessor().markDirty();
                currentValueLabel = Component.literal("Quality: " + Math.round(config.qualityScale * 100f) + "%");
            }));
        } else if (activeTab == 2) {
            // Sharpness — slider with 1% steps
            int pct = Math.round(config.sharpness * 100f);
            currentValueLabel = Component.literal(pct == 0 ? "Sharpness: Off" : "Sharpness: " + pct + "%");
            addRenderableWidget(new FSRSlider(contentX + 20, y, contentW - 40, config.sharpness, 0.01, false, val -> {
                config.sharpness = Math.round((float)(double)val * 100f) / 100f;
                config.save();
                int p = Math.round(config.sharpness * 100f);
                currentValueLabel = Component.literal(p == 0 ? "Sharpness: Off" : "Sharpness: " + p + "%");
            }));
        }

        // Done button — bottom right
        addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
            .bounds(cx + 50, height - 40, 80, 20).build());
    }

    private Component currentValueLabel = Component.empty();

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Dark overlay background first (replaces Minecraft's menu bg)
        context.fill(0, 0, width, height, BG_DARK);

        // Title
        context.centeredText(font, getTitle(), width / 2, 20, 0xFFFFFF);

        // Status text next to toggle
        String status = config.enabled ? "Enabled" : "Disabled";
        context.text(font, Component.literal("FSR " + status).withStyle(ChatFormatting.GRAY), width / 2 + 110, 18, TEXT_DIM);

        // Slider value readout
        if (activeTab == 1 || activeTab == 2) {
            context.centeredText(font, currentValueLabel, width / 2, CONTENT_Y + 30, ACCENT);
        }

        // Render widgets (renderables list)
        for (Renderable r : renderables) {
            r.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreen(parent);
    }

    // --- Inner widget classes ---

    private static class TabButton extends AbstractWidget {
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
                // Teal underline
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

    private static class VersionPill extends AbstractWidget {
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
            context.outline(getX(), getY(), getRight(), getBottom(), border);
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
```

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 6: Clean up stale lang keys

**Files:**
- Modify: `src/main/resources/assets/fsr2mod/lang/en_us.json`

- [ ] **Remove unused keys**

Keep only keys actually referenced by Java code:
- `key.fsr-reloaded.*` (keybind names)
- `category.fsr-reloaded` (keybind category)

Remove:
- `option.fsr-reloaded.*`
- `message.fsr-reloaded.*`
- `modmenu.*` (no ModMenu integration)

- [ ] **Build to verify**

Run: `./gradlew.bat build`

---
### Task 7: Run client and verify

- [ ] **Launch client**

```bash
./gradlew.bat runClient
```

- [ ] **Verify in-game**
  1. Press F7 — settings screen opens with FSR-themed appearance
  2. Toggle master on/off — responds immediately
  3. Click "Quality" tab — slider at correct position, 5% tick marks visible
  4. Slide quality — percentage readout updates, screen reflects change
  5. Click "FSR Version" tab — radio pills, clicking changes version
  6. Click "Sharpness" tab — slider, shows "Off" at 0%
  7. Click "Done" — returns to game, settings saved
  8. Press F9 — should do nothing (key removed)
  9. All text renders fine, no glyphs missing

- [ ] **Commit**

```bash
git add -A
git commit -m "feat: custom FSR-themed tabbed settings UI

- Replace vanilla Button/Slider widgets with custom TabButton, VersionPill,
  ToggleSwitch, and FSRSlider widgets
- Replace FSRQuality enum with direct qualityScale float (5% steps)
- Remove F9 quality cycling keybind
- Remove stale lang entries
"
```
