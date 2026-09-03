# Task 2: Write the frame gen compute shader

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java` (already has FG textures from Task 1)

**Interfaces:**
- Consumes: `uCurrentColor` (sampler, binding 0), `uPrevColor` (sampler, binding 1), `uCurrentDepth` (sampler, binding 2), `uPrevDepth` (sampler, binding 3, reads from prevDilatedTex.b), `uCurrentMV` (sampler, binding 4), `uPrevMV` (sampler, binding 5), `uOutput` (image, binding 6)
- Produces: GLSL compute shader string `FRAME_GEN_CS`, program ID `fgProg`

### Step 1: Write the FG compute shader GLSL

Add after `RCAS_CS`. The prev depth comes from prevDilatedTex's B channel (dilated data stores RG=MVs, B=depth, A=luma).

```java
private static final String FRAME_GEN_CS = """
    #version 430 core
    layout(local_size_x = 8, local_size_y = 8) in;
    layout(binding = 0) uniform sampler2D uCurrentColor;
    layout(binding = 1) uniform sampler2D uPrevColor;
    layout(binding = 2) uniform sampler2D uCurrentDepth;
    layout(binding = 3) uniform sampler2D uPrevDepth;   // reads prevDilatedTex.b (depth in B channel)
    layout(binding = 4) uniform sampler2D uCurrentMV;
    layout(binding = 5) uniform sampler2D uPrevMV;
    layout(binding = 6, rgba8) writeonly uniform image2D uOutput;

    uniform ivec2 uRenderSize;
    uniform ivec2 uDisplaySize;
    uniform float uHandSwing;       // Current hand swing (0-1)
    uniform float uPrevHandSwing;   // Previous hand swing (0-1)

    void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (p.x >= uDisplaySize.x || p.y >= uDisplaySize.y) return;

        vec2 hrUv = (vec2(p) + 0.5) / vec2(uDisplaySize);
        vec2 renderUv = hrUv;

        // Sample MV at current position (in render-pixel space)
        vec2 mv = textureLod(uCurrentMV, renderUv, 0.0).rg;
        float mvLen = length(mv);

        // If very little motion, just reuse current frame
        if (mvLen < 0.5) {
            vec3 c = textureLod(uCurrentColor, hrUv, 0.0).rgb;
            imageStore(uOutput, p, vec4(c, 1.0));
            return;
        }

        // Interpolated UV at t=0.5
        vec2 midUv = hrUv + mv * 0.5 / vec2(uRenderSize);

        // Sample colors at the interpolated position
        vec3 currColor = textureLod(uCurrentColor, midUv, 0.0).rgb;
        vec3 prevColor = textureLod(uPrevColor, midUv, 0.0).rgb;

        // Sample depths: use .r for current depth, .b for prev depth (B channel of dilated data)
        float currDepth = textureLod(uCurrentDepth, midUv, 0.0).r;
        float prevDepth = textureLod(uPrevDepth, midUv, 0.0).b;

        // Depth-based occlusion test
        float depthDiff = abs(prevDepth - currDepth);
        float occlusion = smoothstep(0.02, 0.1, depthDiff);

        // Default: 50/50 blend
        vec3 blended = (prevColor + currColor) * 0.5;

        // Favor current frame where occlusion is detected
        vec3 result = mix(blended, currColor, occlusion);

        // Hand tracking: apply extra offset for hand pixels
        // Hand depth is typically 0.3-0.5 in the depth buffer
        if (currDepth > 0.25 && currDepth < 0.55) {
            float swingDelta = uHandSwing - uPrevHandSwing;
            vec2 handOffset = vec2(swingDelta * 8.0 / uDisplaySize.x, 0.0);
            vec2 handUv = midUv - handOffset * 0.5;
            vec3 handCurr = textureLod(uCurrentColor, handUv, 0.0).rgb;
            result = mix(result, handCurr, 0.7);
        }

        imageStore(uOutput, p, vec4(result, 1.0));
    }
    """;
```

### Step 2: Add program ID and uniform fields

```java
private int fgProg;
private int fg_uRenderSize, fg_uDisplaySize, fg_uHandSwing, fg_uPrevHandSwing;
```

### Step 3: Compile program in `ensureShaders()`

Add in the compilation section (after RCAS compile):

```java
fgProg = compileCompute(FRAME_GEN_CS);
```

Add `fgProg == 0` to the error check after all compiles.

### Step 4: Get uniform locations

After RCAS uniform block:

```java
fg_uRenderSize = getUniformLoc(fgProg, "uRenderSize");
fg_uDisplaySize = getUniformLoc(fgProg, "uDisplaySize");
fg_uHandSwing = getUniformLoc(fgProg, "uHandSwing");
fg_uPrevHandSwing = getUniformLoc(fgProg, "uPrevHandSwing");
```

### Step 5: Add dispatch method

```java
public boolean dispatchFrameGen(int currentColorTex, int currentDepthTex,
        float handSwing, float prevHandSwing) {
    GL43C.glUseProgram(fgProg);
    GL43C.glUniform2i(fg_uRenderSize, renderWidth, renderHeight);
    GL43C.glUniform2i(fg_uDisplaySize, displayWidth, displayHeight);
    GL43C.glUniform1f(fg_uHandSwing, handSwing);
    GL43C.glUniform1f(fg_uPrevHandSwing, prevHandSwing);

    // Bind textures
    GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentColorTex);
    GL43C.glUniform1i(0, 0);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevColorTex);
    GL43C.glUniform1i(1, 1);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, currentDepthTex);
    GL43C.glUniform1i(2, 2);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
    // prevDilatedTex stores B channel = depth, used as uPrevDepth (reads .b)
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevDilatedTex);
    GL43C.glUniform1i(3, 3);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, dilatedDataTex);  // current MV
    GL43C.glUniform1i(4, 4);

    GL43C.glActiveTexture(GL43C.GL_TEXTURE5);
    GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, prevDilatedTex);  // prev MV (reads .rg)
    GL43C.glUniform1i(5, 5);

    GL43C.glBindImageTexture(6, fgOutputTex, 0, false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA8);

    GL43C.glDispatchCompute((displayWidth + 7) / 8, (displayHeight + 7) / 8, 1);
    GL43C.glMemoryBarrier(GL43C.GL_FRAMEBUFFER_BARRIER_BIT);
    return true;
}
```

### Step 6: Destroy program

Add in `destroy()`:

```java
if (fgProg != 0) { GL43C.glDeleteProgram(fgProg); fgProg = 0; }
```

## Important Notes

- **prev depth source**: The plan's original shader used `textureLod(uPrevDepth, midUv, 0.0).r` but `prevDilatedTex` stores depth in the **B channel** (not R). Use `.b` in the shader instead of `.r` for prev depth.
- **Binding 3**: binds `prevDilatedTex` (same as binding 5) since it carries both MV (.rg) and depth (.b).
- **Binding 5**: the shader doesn't currently use uPrevMV (the mvLen check uses current MV). It's reserved for future improved occlusion testing.
