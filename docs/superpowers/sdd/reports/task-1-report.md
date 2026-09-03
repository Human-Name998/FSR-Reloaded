# Task 1 Report: Add FG textures to FSR3Pipeline

## What I implemented

Added frame generation textures to `FSR3Pipeline.java`:

1. **Fields** (line 768-772): `prevColorTex`, `fgOutputTex`, `prevDilatedTex`, `hasPrevFrame`
2. **Texture creation** in `ensureTextures()` (lines 1008-1013): Creates the three textures at display/render size with correct formats (GL_RGBA8 for color/FG output, GL_RGBA16F for prevDilated)
3. **Texture deletion** in `deleteTextures()` (lines 1033, 1044): Added `prevColorTex`, `fgOutputTex`, `prevDilatedTex` to the deletion array and zeroing block
4. **`setPrevFrame(int colorTex, int dilatedTex)`**: Uses `glCopyImageSubData` to copy previous frame data into prev textures, sets `hasPrevFrame = true`
5. **`getFgOutputTexture()`**: Returns `fgOutputTex`
6. **`hasPreviousFrame()`**: Returns `hasPrevFrame`
7. **`resetFrameGen()`**: Sets `hasPrevFrame = false`

## What I tested

- Gradle build (`./gradlew.bat build`) passed successfully — all source sets compiled without errors

## Files changed

- `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (+38 lines, -1 line)

## Self-review findings

- All changes follow existing code conventions (field declarations at top, texture creation in `ensureTextures()`, deletion in `deleteTextures()`)
- The `setPrevFrame` method uses `displayWidth`/`displayHeight` for color copy and `renderWidth`/`renderHeight` for dilated copy, matching spec
- The `deleteTextures()` method correctly adds the three new textures to the `int[] texs` array and adds their zeroing statements in the same style as existing code
- All three new textures are created inside a single `if (prevColorTex == 0)` block, matching the spec's approach

## Issues or concerns

- None
