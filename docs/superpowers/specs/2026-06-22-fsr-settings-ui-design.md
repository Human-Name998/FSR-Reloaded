# FSR Settings UI — Custom Tabbed Interface

## Overview
Replace the existing `SettingsScreen.java` (which uses vanilla Minecraft widgets) with a fully custom FSR-themed tabbed interface. Remove the deprecated `FSRQuality` enum and replace with a direct float scale.

## Changes

### Config (`FSRConfig.java`)
- Remove `FSRQuality` enum entirely
- Add `float qualityScale = 0.77f` field (maps to old `ULTRA_QUALITY` default)
- Load/save: `qualityScale` clamped to `[0.25f, 1.0f]`, rounded to nearest 0.05f
- `version` enum stays unchanged

### Settings Screen (`gui/SettingsScreen.java`) — Full rewrite
- Extends `Screen`
- Dark overlay background (`0xAA000000`)
- FSR accent color `#00C8E0` used for highlights, active tab, borders
- **Top-right master toggle** — custom toggle switch widget (teal=on, gray=off)
- **Tab bar**: 3 horizontal tabs (steel-gray bg, teal underline when active)
  - "FSR Version" | "Quality" | "Sharpness"

#### Tab: FSR Version
- Radio-button list of FSRVersion enum values
- Each option: dark pill, teal border when selected
- Click selects, updates `config.version`, calls `markDirty()`

#### Tab: Quality
- Slider `25%` – `100%`, steps of 5% (slider `snapToTick` at every 5th percentile)
- Current value displayed as `"Quality: XX%"` readout
- Calls `config.qualityScale = val / 100f`, `markDirty()`

#### Tab: Sharpness
- Slider `0%` – `100%`, steps of 1%
- Shows `"Sharpness: Off"` at 0%, `"Sharpness: XX%"` otherwise
- Calls `config.sharpness = val / 100f`

#### Done Button
- Bottom-right, teal accent on hover
- Calls `onClose()` (saves config, pops to parent screen)

### Keybind Changes (`FSRMod.java`)
- **F9** (`cycleQualityKey`) removed — quality is now slider-only, no cycle key needed
- **F7** (`settingsKey`) unchanged — opens SettingsScreen
- **F8** (`toggleKey`) unchanged — toggles enabled/disabled
- **F10** (`cycleVersionKey`) unchanged — cycles FSRVersion (also appears in version tab)
- **J/K** sharpness keys unchanged — adjust sharpness by 0.1

### FSRProcessor and Pipeline Consumers
- `config.qualityMode.getScale()` → `config.qualityScale` everywhere
- `FSRProcessor.onResize()` and `markDirty()` already use `config.qualityMode.getScale()` — redirect to `config.qualityScale`

### Internationalization
- `en_us.json`: remove unused `option.fsr-reloaded.*`, `message.fsr-reloaded.*` keys
- Screen labels now hardcoded in Java (simple screen, small scope)

## Widgets (custom, no vanilla overrides)
All widgets are custom drawables (not extending vanilla Button/AbstractSliderButton):
- **ToggleSwitch** — rectangular, rounded corners, teal/gray fill, sliding circle indicator
- **RadioTab** — pill with text, teal border when selected
- **FSRSlider** — thin teal track, teal circular thumb, tick marks at 5% intervals for quality

## Files Changed
| File | Change |
|------|--------|
| `src/client/java/com/fsr2mod/config/FSRConfig.java` | Remove FSRQuality enum, add qualityScale field |
| `src/client/java/com/fsr2mod/gui/SettingsScreen.java` | Full rewrite — tabbed UI, custom widgets |
| `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` | `config.qualityMode.getScale()` → `config.qualityScale` |
| `src/client/java/com/fsr2mod/FSRMod.java` | Remove F9 quality cycling, remove unused FSRQuality refs |
| `src/main/resources/assets/fsr2mod/lang/en_us.json` | Remove stale keys |

## Files Removed
- None (FSRQuality enum inline-removed; no separate file)
