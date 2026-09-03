package com.fsr2mod.gui;

import com.fsr2mod.FSRMod;
import com.fsr2mod.compat.IrisCompat;
import com.fsr2mod.compat.VulkanModCompat;
import com.fsr2mod.config.FSRConfig;
import com.fsr2mod.fsr.FSRProcessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final int TAB_GENERAL = 0;
    private static final int TAB_QUALITY = 1;
    private static final int TAB_IMAGE = 2;
    private static final int TAB_FRAMEGEN = 3;
    private static final int TAB_ADVANCED = 4;
    private static final String[] TAB_LABELS = {"General", "Quality", "Image", "Frame Gen", "Advanced"};

    private static final int TAB_HEIGHT = 28;
    private static final int TAB_Y = 58;
    private static final int CONTENT_Y = 100;
    private static final int CONTENT_W = 300;
    private static final int GEN_COL_GAP = 200;
    private static final int GEN_PILL_H = 24;
    private static final int GEN_ROW_STEP = 30;

    private static final int ACCENT = 0xFF00C8E0;
    private static final int BG_DARK = 0xAA000000;
    private static final int BG_TAB_INACTIVE = 0xFF333333;
    private static final int BG_TAB_ACTIVE = 0xFF1A1A1A;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int TEXT_DISABLED = 0xFF555555;
    private static final int WARN = 0xFFFFAA00;
    private static final int OK = 0xFF00CC66;

    private final Screen parent;
    private final FSRConfig config;
    private final FSRMod mod;
    private final FSRProcessor processor;
    private int activeTab = TAB_GENERAL;
    private final List<Renderable> myWidgets = new ArrayList<>();
    private final List<VersionPill> versionPills = new ArrayList<>();
    private Component valueLabel = Component.empty();
    private String hoveredReason;
    // Live auto-quality readout (Target FPS row) — text updated every render
    // frame from FSRProcessor.getAutoQualityStatus() while targetFps > 0.
    private RowLabel autoStatusLabel;
    // Per-row labels (control names + live value readouts), positioned next to
    // or under their controls by each tab builder. Rendered by renderRowLabels().
    // Rebuilt on every init() so tab switches get fresh labels. Replaces the old
    // "find the widget by type" loops, which mislabeled the master FSR toggle as
    // "Sharpening" and left the EASU toggle unlabeled.
    private final List<RowLabel> rowLabels = new ArrayList<>();

    public SettingsScreen(Screen parent) {
        super(Component.literal("FSR Reloaded"));
        this.parent = parent;
        this.mod = FSRMod.getInstance();
        this.config = mod.getConfig();
        this.processor = mod.getProcessor();
    }

    @Override
    protected void init() {
        myWidgets.clear();
        versionPills.clear();
        rowLabels.clear();
        hoveredReason = null;
        int cx = width / 2;
        int tabW = Math.min(110, (width - 40) / TAB_LABELS.length);
        int tabsTotal = tabW * TAB_LABELS.length;
        int tabStartX = cx - tabsTotal / 2;

        // Tab bar
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int ti = i;
            addTracked(new TabButton(tabStartX + i * tabW, TAB_Y, tabW, TAB_HEIGHT,
                Component.literal(TAB_LABELS[i]), i == activeTab, btn -> {
                activeTab = ti;
                SettingsScreen.this.init(width, height);
            }));
        }

        // Master enable toggle (top-right)
        addTracked(new ToggleSwitch(width - 50, 15, config.enabled, enabled -> {
            config.enabled = enabled;
            config.save();
        }));

        int contentW = Math.min(CONTENT_W, width - 60);

        // Build active tab
        switch (activeTab) {
            case TAB_GENERAL:    buildGeneralTab(cx, contentW);    break;
            case TAB_QUALITY:    buildQualityTab(cx, contentW);    break;
            case TAB_IMAGE:      buildImageTab(cx, contentW);      break;
            case TAB_FRAMEGEN:   buildFrameGenTab(cx, contentW);   break;
            case TAB_ADVANCED:   buildAdvancedTab(cx, contentW);   break;
        }

        // Done button
        addTracked(new DoneButton(cx - 40, height - 32, 80, 22));
    }

    // ---- layout helpers ----

    private int generalWidth() {
        return Math.min(CONTENT_W, width - 60);
    }

    private int generalX() {
        return width / 2 - generalWidth() / 2;
    }

    private int colWidth() {
        return (generalWidth() - GEN_COL_GAP) / 2;
    }

    private boolean currentVersionBlocked() {
        return !processor.getVersionStatus(config.version).selectable;
    }

    private int pillStartY() {
        return CONTENT_Y;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // ---- tab builders ----

    private void buildGeneralTab(int cx, int contentW) {
        FSRConfig.FSRVersion[] versions = FSRConfig.FSRVersion.values();
        // 2x3 grid: half-width pills in two columns, three rows. The full
        // descriptive labels are too long for half-width pills, so the grid
        // uses compact labels; hovering any pill shows its full label as a
        // tooltip (plus the block reason when not selectable).
        int pillGap = 8;
        int pillW = (contentW - pillGap) / 2;
        int x0 = generalX();
        int y = pillStartY();

        for (int i = 0; i < versions.length; i++) {
            FSRConfig.FSRVersion v = versions[i];
            FSRProcessor.VersionStatus vs = processor.getVersionStatus(v);
            int col = i % 2;
            int row = i / 2;
            int px = x0 + col * (pillW + pillGap);
            int py = y + (row * GEN_ROW_STEP);
            boolean selected = v == config.version;

            VersionPill pill = new VersionPill(px, py, pillW, GEN_PILL_H,
                Component.literal(shortLabel(v)), v.getLabel(), selected, vs.selectable, vs.reason, vs.notice, btn -> {
                if (vs.selectable) {
                    config.version = v;
                    config.save();
                    // DEFERRED (2026-08-23, "switching FSR versions makes
                    // terrain disappear until a quality change" fix): the version
                    // pill callback runs on the CLIENT thread, but markDirty()
                    // tears down GL/D3D12 resources and must run on the RENDER
                    // thread — calling it synchronously raced the render thread's
                    // mid-dispatch reads (deleted texture IDs reused → terrain
                    // read from a wrong texture → black until a later clean
                    // recreate). requestConfigDirty() only sets a thread-safe
                    // flag; onRenderBegin consumes it at frame start (same
                    // pattern as the toggle key + quality slider).
                    processor.requestConfigDirty();
                    SettingsScreen.this.init(width, height);
                }
            });
            addTracked(pill);
            versionPills.add(pill);
        }


    }

    // Compact labels for the 2x3 version grid (full labels are too long for
    // half-width pills).
    private static String shortLabel(FSRConfig.FSRVersion v) {
        switch (v) {
            case FSR1: return "FSR 1";
            case FSR2: return "FSR 2";
            case FSR3_UPS: return "FSR 3 Ups";
            case FSR3_FG: return "FSR 3 FG";
            case FSR3_FG_DX12: return "FSR 3 FG DX12";
            case FSR4: return "FSR 4";
            default: return v.getLabel();
        }
    }

    private void buildQualityTab(int cx, int contentW) {
        int x0 = cx - contentW / 2;
        int sliderX = x0 + 20;
        int sliderW = contentW - 40;
        int y = CONTENT_Y;

        // Quality slider — the readout below shows the EFFECTIVE render
        // resolution and updates live as the slider moves (the old "Render:"
        // text showed the static display size and did nothing). The readout is
        // computed from the NEW config in the callback (renderResFor), so it
        // responds instantly instead of lagging a frame behind the per-frame
        // processor value.
        // Hidden under Iris: onRenderBegin uses irisQualityScale there, so the
        // main slider would be INERT — the same "control does nothing" bug
        // class. The Iris slider below replaces it.
        if (!IrisCompat.isShaderPackInUse()) {
            RowLabel qualityLabel = addRowLabel(cx, y + 30,
                Component.literal("Quality: " + Math.round(config.qualityScale * 100f)
                    + "%  ·  Render: " + renderResFor(config.getScale())),
                ACCENT, true);
            addTracked(new FSRSlider(sliderX, y, sliderW,
                config.qualityScale, 0.05, true, val -> {
                config.qualityScale = Math.max(0.25f, Math.min(1.0f,
                    Math.round((float)(double)val * 100f) / 100f));
                config.save();
                // Deferred scale change (2026-08-21, "see through the ground on
                // drastic quality changes" fix): this callback runs on the
                // CLIENT thread, but applyAutoScale() tears down GL/D3D12
                // resources — calling it synchronously here raced the render
                // thread's mid-dispatch reads (a fast drastic drag fires many
                // events per frame; gradual drags mostly land between frames,
                // which is why only drastic changes showed artifacts).
                // requestScaleChange() only sets a flag; onRenderBegin consumes
                // it at frame start on the RENDER thread and runs applyAutoScale()
                // there (same safe path as the auto-quality adjuster).
                processor.requestScaleChange();
                qualityLabel.text = Component.literal(
                    "Quality: " + Math.round(config.qualityScale * 100f)
                        + "%  ·  Render: " + renderResFor(config.getScale()));
            }));
            y += 48;
        }

        // Iris quality slider — ONLY when a shader pack is active AND the
        // selected version actually scales through the pack. FSR2/FSR3-upscaler
        // and FSR4 use irisSwapActive normally (FSR4's GL dispatch upscales the
        // scaled composite through the D3D12 pool, 2026-08-20); FSR1 uses it
        // only when the experimental EASU/RCAS opt-in is enabled.
        // FSR3_FG_DX12 (2026-08-29): this list must mirror FSRProcessor's
        // `irisTemporal` gate, which has included isFrameGenDx12() since the
        // "Iris FG DX12" commit. Being absent here meant the screen told the
        // user FG DX12 was pass-through under a pack AND hid the Iris quality
        // slider, so the pack scale could not be set for it at all — while the
        // render path was in fact running the scaled swap.
        // FSR3_FG (the non-DX12 software frame-gen mode) is genuinely
        // pass-through: its alternate-frame machinery is not wired for the swap.
        boolean irisScaleVersion = config.version == FSRConfig.FSRVersion.FSR2
            || config.version == FSRConfig.FSRVersion.FSR3_UPS
            || config.version == FSRConfig.FSRVersion.FSR4
            || config.version == FSRConfig.FSRVersion.FSR3_FG_DX12
            || (config.version == FSRConfig.FSRVersion.FSR1
                && config.fsr1IrisExperimental);
        if (IrisCompat.isShaderPackInUse() && irisScaleVersion) {
            RowLabel irisLabel = addRowLabel(cx, y + 30,
                Component.literal("Iris Quality: " + Math.round(config.irisQualityScale * 100f) + "%"),
                TEXT_DIM, true);
            addTracked(new FSRSlider(sliderX, y, sliderW,
                config.irisQualityScale, 0.05, true, val -> {
                config.irisQualityScale = Math.max(0.25f, Math.min(1.0f,
                    Math.round((float)(double)val * 100f) / 100f));
                config.save();
                // Deferred to the render thread (see requestConfigDirty doc).
                processor.requestConfigDirty();
                irisLabel.text = Component.literal(
                    "Iris Quality: " + Math.round(config.irisQualityScale * 100f) + "%");
            }));
            y += 48;
        } else if (IrisCompat.isShaderPackInUse()) {
            // Pass-through versions under a pack: no scaling happens, so the
            // slider is replaced by an explanatory notice.
            addRowLabel(cx, y + 30,
                Component.literal("Iris Quality: N/A — " + shortLabel(config.version)
                    + " under a shader pack is pass-through; enable Experimental FSR1 under Iris or select FSR 2 / FSR 3 (upscaler) / FSR 4 / FSR 3 FG DX12"),
                TEXT_DIM, true);
            y += 48;
        }

        // VulkanMod render-scale toggle (POC) — ONLY when VulkanMod is
        // installed (a pure-Vulkan operation; on the GL path it's a no-op and
        // the option would be misleading).
        if (VulkanModCompat.isLoaded()) {
            addRowLabel(sliderX + 48, y + 4,
                Component.literal("VulkanMod Render Scale"), TEXT_DIM, false);
            addTracked(new ToggleSwitch(sliderX, y, config.vulkanModRenderScale, on -> {
            config.vulkanModRenderScale = on;
            config.save();
            // Deferred to the render thread (see requestConfigDirty doc).
            processor.requestConfigDirty();
            }));
            y += 34;
        }

        // Target-FPS auto-quality (2026-08-08): cycles Off / 30 / 60 / 90 /
        // 120 / 144. While ON, FSRProcessor continuously measures the render FPS
        // and nudges qualityScale (0.05 steps, ~2s cooldown, ±6% hysteresis) to
        // hit the target — the quality slider above becomes a live readout of
        // the auto value. Excluded while any screen/menu is open (the pause
        // drops FPS), in FG modes, and under Iris (render scale doesn't apply
        // there). The status line under the button shows the live measurement.
        int tfW = Math.min(contentW - 40, 180);
        RowLabel tfLabel = addRowLabel(sliderX + tfW + 8, y + 8,
            Component.literal("Target FPS — " + targetFpsLabel(config.targetFps)), ACCENT, false);
        addTracked(new TargetFpsButton(sliderX, y, tfW, 24, config.targetFps, val -> {
            config.targetFps = val;
            config.save();
            processor.resetAutoQuality();  // a new target re-engages immediately
            tfLabel.text = Component.literal("Target FPS — " + targetFpsLabel(val));
        }));
        y += 34;
        autoStatusLabel = addRowLabel(sliderX, y + 10, Component.literal(""), TEXT_DIM, false);
    }

    private static String targetFpsLabel(int v) {
        return v == 0 ? "Off" : String.valueOf(v);
    }

    private void buildImageTab(int cx, int contentW) {
        int x0 = cx - contentW / 2;
        int sliderX = x0 + 20;
        int y = CONTENT_Y;
        boolean sharpeningOn = config.sharpness > 0.001f;

        // Sharpening toggle — label to its right shows the live value.
        RowLabel sharpLabel = addRowLabel(sliderX + 48, y + 4,
            Component.literal(sharpeningOn
                ? "Sharpening — " + Math.round(config.sharpness * 100f) + "%"
                : "Sharpening — Off"),
            TEXT_COLOR, false);
        addTracked(new ToggleSwitch(sliderX, y, sharpeningOn, on -> {
            if (!on) {
                config.sharpness = 0.0f;
            } else if (config.sharpness <= 0.001f) {
                config.sharpness = 0.7f;
            }
            config.save();
            // Rebuild so the sharpness slider appears/disappears.
            SettingsScreen.this.init(width, height);
        }));
        y += 24;

        if (sharpeningOn) {
            FSRSlider slider = new FSRSlider(sliderX, y, contentW - 40,
                config.sharpness, 0.01, false, val -> {
                config.sharpness = Math.max(0.01f,
                    Math.round((float)(double)val * 100f) / 100f);
                config.save();
                sharpLabel.text = Component.literal(
                    "Sharpening — " + Math.round(config.sharpness * 100f) + "%");
            });
            addTracked(slider);
            y += 34;
        }

        y += 8;
        // EASU upscaler toggle
        addRowLabel(sliderX + 48, y + 4,
            Component.literal("FSR1 EASU Upscaler"), TEXT_COLOR, false);
        addTracked(new ToggleSwitch(sliderX, y, config.fsr1Easu, on -> {
            config.fsr1Easu = on;
            config.save();
        }));

        // Iris FSR1 opt-in. The normal default remains pass-through because the
        // EASU/RCAS post-process has reproduced an AMD driver crash on some Iris
        // composites; this setting deliberately makes that path experimental.
        y += 34;
        addRowLabel(sliderX + 48, y + 4,
            Component.literal("Experimental FSR1 under Iris"), TEXT_DIM, false);
        addTracked(new ToggleSwitch(sliderX, y, config.fsr1IrisExperimental, on -> {
            config.fsr1IrisExperimental = on;
            config.save();
            // Deferred to the render thread (see requestConfigDirty doc).
            processor.requestConfigDirty();
        }));

        // FSR4 near-parity bypass toggle (>=90% render scale → FSR1 blit+RCAS;
        // the FSR4 round trip is a net loss at near-parity on iGPUs)
        y += 34;
        addRowLabel(sliderX + 48, y + 4,
            Component.literal("FSR4 Parity Bypass"), TEXT_COLOR, false);
        addTracked(new ToggleSwitch(sliderX, y, config.fsr4ParityBypass, on -> {
            config.fsr4ParityBypass = on;
            config.save();
        }));
    }

    private void buildFrameGenTab(int cx, int contentW) {
        int x0 = cx - contentW / 2;
        int sliderX = x0 + 20;
        int y = CONTENT_Y;

        // FG multiplier selector — label to its right shows the live rate.
        // (Narrower than the old full-width button so the label fits beside it
        // instead of floating below the control.)
        int rateW = Math.min(contentW - 40, 180);
        RowLabel rateLabel = addRowLabel(sliderX + rateW + 8, y + 8,
            Component.literal("FG Rate — " + config.fgMultiplier + "x"), TEXT_COLOR, false);
        addTracked(new FgMultiplierButton(sliderX, y, rateW, 24, config.fgMultiplier, val -> {
            config.fgMultiplier = val;
            config.save();
            rateLabel.text = Component.literal("FG Rate — " + val + "x");
        }));
        y += 34;

        // FG frame-skip toggle
        addRowLabel(sliderX + 48, y + 4,
            Component.literal("Frame Skip"), TEXT_DIM, false);
        addTracked(new ToggleSwitch(sliderX, y, config.fgFrameSkip, on -> {
            config.fgFrameSkip = on;
            config.save();
        }));
        y += 24;

        // Async FSR4 toggle (hides the FSR4 GPU wait behind the next frame;
        // tradeoff: ~1 frame input latency. FSR4 mode only — FG stays sync.)
        addRowLabel(sliderX + 48, y + 4,
            Component.literal("Async FSR4"), TEXT_DIM, false);
        addTracked(new ToggleSwitch(sliderX, y, config.fsr4Async, on -> {
            config.fsr4Async = on;
            config.save();
            // Deferred to the render thread (see requestConfigDirty doc).
            processor.requestConfigDirty();
        }));
        y += 24;

        // FG availability is shown as a visible red line in renderFrameGen.
    }

    private int frameGenListEndY() {
        return CONTENT_Y + 34 + 24 + 24 + 10;   // multiplier + frame-skip + async-FSR4 rows
    }

    private void buildAdvancedTab(int cx, int contentW) {
        int y = CONTENT_Y;
        valueLabel = Component.literal("Debug: " + config.debugMode);

        addTracked(new FSRSlider(cx - contentW / 2 + 20, y, contentW - 40,
            config.debugMode / 6.0, 1.0 / 6.0, true, val -> {
            config.debugMode = Math.max(0, Math.min(6, (int)Math.round(val * 6.0)));
            config.save();
            valueLabel = Component.literal("Debug: " + config.debugMode);
        }));
    }

    // ---- tracked widget helper ----

    private <T extends AbstractWidget> T addTracked(T widget) {
        addRenderableWidget(widget);
        myWidgets.add(widget);
        return widget;
    }

    // ---- rendering ----

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        // Per-frame tooltip state: widgets (e.g. version pills) set
        // hoveredReason while rendering if hovered. Cleared every frame so a
        // tooltip disappears when the mouse moves away.
        hoveredReason = null;
        context.fill(0, 0, width, height, BG_DARK);

        // Title
        context.drawCenteredString(font, getTitle(), width / 2, 20, 0xFFFFFF);

        // Enable/disabled label
        String status = config.enabled ? "Enabled" : "Disabled";
        context.drawString(font, Component.literal("FSR " + status).withStyle(ChatFormatting.GRAY),
            width - 115, 18, TEXT_DIM);

        // Render active tab content
        switch (activeTab) {
            case TAB_GENERAL:  renderGeneral(context, mouseX, mouseY);  break;
            case TAB_QUALITY:  renderQuality(context);                  break;
            case TAB_IMAGE:    renderImage(context);                    break;
            case TAB_FRAMEGEN: renderFrameGen(context);                 break;
            case TAB_ADVANCED: renderAdvanced(context);                 break;
        }

        // Render widgets on top
        for (Renderable r : myWidgets) {
            r.render(context, mouseX, mouseY, delta);
        }

        // Hover tooltip
        if (hoveredReason != null && !hoveredReason.isEmpty()) {
            int tooltipX = Math.min(mouseX + 8, width - 200);
            int tooltipY = mouseY + 12;
            context.drawString(font, Component.literal(hoveredReason)
                .withStyle(ChatFormatting.YELLOW), tooltipX, tooltipY, WARN);
        }
    }

    private void renderGeneral(GuiGraphics context, int mouseX, int mouseY) {
        // Version pills are rendered by the widget list; we add labels above them
        int y = pillStartY() - 12;
        int x0 = generalX();
        context.drawString(font, Component.literal("Select Version").withStyle(ChatFormatting.GRAY),
            x0, y, TEXT_DIM);
        // GPU name moves BELOW the 2x3 grid (it previously sat to the right of
        // the single-column list; with two columns that spot is now occupied).
        int gridBottom = pillStartY() + 3 * GEN_ROW_STEP;
        context.drawString(font, Component.literal(environmentSummary()).withStyle(ChatFormatting.DARK_GRAY),
            x0, gridBottom + 6, TEXT_DIM);


        if (currentVersionBlocked()) {
            String blockedReason = processor.getVersionStatus(config.version).reason;
            if (blockedReason != null && !blockedReason.isEmpty()) {
                context.drawCenteredString(font,
                    Component.literal("⚠ " + blockedReason).withStyle(ChatFormatting.RED),
                    width / 2, height - 60, WARN);
            }
        } else {
            // Selectable-version notice (e.g. Linux FSR4 → FSR3-engine
            // fallback): show it under the grid so the player knows the
            // selection is running a fallback engine, not silently switching.
            String notice = processor.getVersionStatus(config.version).notice;
            if (notice != null && !notice.isEmpty()) {
                context.drawCenteredString(font,
                    Component.literal("ℹ " + notice).withStyle(ChatFormatting.YELLOW),
                    width / 2, height - 60, WARN);
            }
        }
    }

    private String environmentSummary() {
        String gpu = processor.getGpuName();
        if (gpu == null || gpu.isEmpty()) gpu = "unknown GPU";
        return gpu;
    }

    private void renderQuality(GuiGraphics context) {
        renderRowLabels(context);
        // Live auto-quality status (Target FPS row): refresh from the processor
        // every frame so the measured fps + auto scale are current.
        if (autoStatusLabel != null && config.targetFps > 0) {
            String s = processor.getAutoQualityStatus();
            if (s != null) autoStatusLabel.text = Component.literal(s);
        }
    }

    private void renderImage(GuiGraphics context) {
        renderRowLabels(context);
    }

    private void renderFrameGen(GuiGraphics context) {
        renderRowLabels(context);

        if (!processor.isFrameGenAvailable()) {
            context.drawCenteredString(font,
                Component.literal("FG DX12 not available").withStyle(ChatFormatting.RED),
                width / 2, frameGenListEndY() + 10, WARN);
        }
    }

    // ---- row-label helpers ----

    /**
     * Draws every row label registered by the active tab's builder. Each label
     * carries its own position/alignment, so controls keep their labels even
     * when rows are conditionally hidden (e.g. the Iris slider or the
     * VulkanMod toggle) — no fragile "find the widget" iteration.
     */
    private void renderRowLabels(GuiGraphics context) {
        for (RowLabel l : rowLabels) {
            if (l.centered) {
                context.drawCenteredString(font, l.text, l.x, l.y, l.color);
            } else {
                context.drawString(font, l.text, l.x, l.y, l.color);
            }
        }
    }

    private RowLabel addRowLabel(int x, int y, Component text, int color, boolean centered) {
        RowLabel l = new RowLabel(x, y, text, color, centered);
        rowLabels.add(l);
        return l;
    }

    /**
     * The render resolution for a given scale (e.g. "1248×702" at 65% of
     * 1920×1080) — same formula as onRenderBegin, computed from the CURRENT
     * config so live readouts respond to the slider instantly (reading the
     * processor's per-frame dims instead lags by one rendered frame).
     */
    private String renderResFor(float scale) {
        int rw = Math.max(16, Math.round(processor.getDisplayWidth() * scale));
        int rh = Math.max(16, Math.round(processor.getDisplayHeight() * scale));
        return rw + "×" + rh;
    }

    private void renderAdvanced(GuiGraphics context) {
        context.drawCenteredString(font, valueLabel, width / 2, CONTENT_Y + 30, ACCENT);
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.setScreen(parent);
    }

    // ---- Inner classes ----

    /** One row's label text + position (mutable so live value readouts update). */
    private static final class RowLabel {
        int x, y, color;
        boolean centered;
        Component text;
        RowLabel(int x, int y, Component text, int color, boolean centered) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
            this.centered = centered;
        }
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
        protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            int bg = active ? BG_TAB_ACTIVE : BG_TAB_INACTIVE;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            if (active) {
                context.fill(getX(), getBottom() - 3, getRight(), getBottom(), ACCENT);
            }
            context.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + 7,
                active ? 0xFFFFFFFF : TEXT_COLOR);
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

    class VersionPill extends AbstractWidget {
        private final boolean selected;
        private final boolean selectable;
        private final String reason;
        private final String notice;     // shown in the tooltip when selectable
        private final String fullLabel;   // descriptive label for the hover tooltip
        private final Consumer<AbstractWidget> onClick;

        VersionPill(int x, int y, int w, int h, Component label, String fullLabel,
                    boolean selected, boolean selectable, String reason, String notice,
                    Consumer<AbstractWidget> onClick) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.selectable = selectable;
            this.reason = reason;
            this.notice = notice;
            this.fullLabel = fullLabel;
            this.onClick = onClick;
        }

        @Override
        protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            // Hover tooltip: show the FULL descriptive label (the grid pill only
            // fits a compact one) plus the block reason when not selectable.
            if (isHoveredOrFocused()) {
                SettingsScreen.this.hoveredReason = fullLabel;
                if (!selectable && reason != null && !reason.isEmpty()) {
                    SettingsScreen.this.hoveredReason += " — " + reason;
                } else if (selectable && notice != null && !notice.isEmpty()) {
                    SettingsScreen.this.hoveredReason += " — " + notice;
                }
            }
            int bg, border;
            if (!selectable) {
                bg = 0xFF1A1A1A;
                border = 0xFF333333;
            } else if (selected) {
                bg = 0xFF1A3A3A;
                border = ACCENT;
            } else {
                bg = 0xFF222222;
                border = 0xFF444444;
            }
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            context.fill(getX(), getY(), getRight(), getY() + 1, border);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), border);
            context.fill(getX(), getY(), getX() + 1, getBottom(), border);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), border);

            int color;
            if (!selectable) color = TEXT_DISABLED;
            else if (selected) color = 0xFFFFFFFF;
            else color = TEXT_COLOR;
            context.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + 6, color);
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

    private class FgMultiplierButton extends AbstractWidget {
        private int value;
        private final Consumer<Integer> onChange;
        private static final String[] LABELS = {"1x", "2x", "3x", "4x"};

        FgMultiplierButton(int x, int y, int w, int h, int value, Consumer<Integer> onChange) {
            super(x, y, w, h, Component.literal("FG Rate"));
            this.value = Math.max(1, Math.min(4, value));
            this.onChange = onChange;
        }

        @Override
        protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            int segW = (width - 8) / LABELS.length;
            int startX = getX() + 4;

            context.fill(getX(), getY(), getRight(), getBottom(), 0xFF1A1A1A);
            context.fill(getX(), getY(), getRight(), getY() + 1, 0xFF444444);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), 0xFF444444);
            context.fill(getX(), getY(), getX() + 1, getBottom(), 0xFF444444);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), 0xFF444444);

            for (int i = 0; i < LABELS.length; i++) {
                int sx = startX + i * segW;
                boolean active = (i + 1) == value;
                if (active) {
                    context.fill(sx, getY() + 2, sx + segW, getBottom() - 2, 0xFF00333A);
                }
                context.drawCenteredString(font, Component.literal(LABELS[i]),
                    sx + segW / 2, getY() + 6,
                    active ? ACCENT : TEXT_COLOR);
                if (i < LABELS.length - 1) {
                    context.fill(sx + segW, getY() + 4, sx + segW + 1, getBottom() - 4, 0xFF444444);
                }
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int segW = (width - 8) / LABELS.length;
            int relX = (int)event.x() - getX() - 4;
            int idx = relX / segW;
            if (idx >= 0 && idx < LABELS.length) {
                value = idx + 1;
                onChange.accept(value);
            }
        }
    }

    private class TargetFpsButton extends AbstractWidget {
        private static final int[] VALUES = {0, 30, 60, 90, 120, 144, 240};
        private int value;
        private final Consumer<Integer> onChange;

        TargetFpsButton(int x, int y, int w, int h, int value, Consumer<Integer> onChange) {
            super(x, y, w, h, Component.literal("Target FPS"));
            this.value = value;
            this.onChange = onChange;
        }

        @Override
        protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            int bg = isHoveredOrFocused() ? 0xFF004A55 : 0xFF00333A;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            context.fill(getX(), getY(), getRight(), getY() + 1, ACCENT);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), ACCENT);
            context.fill(getX(), getY(), getX() + 1, getBottom(), ACCENT);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), ACCENT);
            context.drawCenteredString(font,
                Component.literal("Target FPS: " + (value == 0 ? "Off" : String.valueOf(value))),
                getX() + width / 2, getY() + 6,
                value == 0 ? TEXT_COLOR : ACCENT);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            int idx = 0;
            for (int i = 0; i < VALUES.length; i++) {
                if (VALUES[i] == value) { idx = i; break; }
            }
            value = VALUES[(idx + 1) % VALUES.length];
            onChange.accept(value);
        }
    }

    private class DoneButton extends AbstractWidget {
        DoneButton(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal("Done"));
        }

        @Override
        protected void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            int bg = isHoveredOrFocused() ? 0xFF004A55 : 0xFF00333A;
            context.fill(getX(), getY(), getRight(), getBottom(), bg);
            context.fill(getX(), getY(), getRight(), getY() + 1, ACCENT);
            context.fill(getX(), getBottom() - 1, getRight(), getBottom(), ACCENT);
            context.fill(getX(), getY(), getX() + 1, getBottom(), ACCENT);
            context.fill(getRight() - 1, getY(), getRight(), getBottom(), ACCENT);
            context.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + 6, 0xFFFFFFFF);
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

}
