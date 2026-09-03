# Task 4: Add FG config option — Report

## Changes Made

**FSRConfig.java** (`src/client/java/com/fsr2mod/config/FSRConfig.java:26`):
- Added `FSR3_FG("FSR 3 (frame gen)")` to the `FSRVersion` enum after `FSR3_UPS`
- SettingsScreen iterates `values()` so entry appears automatically

**FSRProcessor.java** (`src/client/java/com/fsr2mod/fsr/FSRProcessor.java`):
- `isFsr3()` (line 78): now returns true for both `FSR3_UPS` and `FSR3_FG`
- Added `isFrameGen()` (line 83): returns true only for `FSR3_FG`
- `doPresent()` (line 350): changed FG guard from `isFsr3()` to `isFrameGen()` — FG only runs when explicitly selected
- `onRenderEnd()` (line 306): changed `savePrevFrame()` guard from unconditional to `isFrameGen()` — prev frame only saved for FG path

## Verification

- `./gradlew.bat build` — BUILD SUCCESSFUL (no errors)
- Committed as `1432caf` with message "Add FSR3_FG config option with isFrameGen() guard"
