# Task 4: FSRConfig — add UpscaleMode.NPU

## Status: DONE

## Changes
- **File modified:** `src/client/java/com/fsr2mod/config/FSRConfig.java`
- Added `NPU("NPU (DirectML)")` as a new enum constant to the existing `FSRVersion` enum, following the pattern of the existing entries (string label constructor).

## Approach
Used the simplest approach per the task instructions: added NPU directly to the existing `FSRVersion` enum rather than creating a separate `UpscaleMode` enum. This keeps the config structure unchanged and requires no migration logic, new fields, or precedence rules. The `version` field (`public FSRVersion version = FSRVersion.FSR1;`) will now accept NPU as a value.

## Verification
- Compiled successfully (no syntax errors)
- The enum pattern is consistent with the existing 5 entries
- No other files needed modification
