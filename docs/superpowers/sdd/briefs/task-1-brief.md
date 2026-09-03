# Task 1: Add frame gen textures to FSR3Pipeline

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java`

**Interfaces:**
- Consumes: existing texture creation pattern (`createTex2D`, `ensureTextures`, `deleteAllTextures`, `recreateTextures`)
- Produces: `prevColorTex` (GL_RGBA8, displaySize), `fgOutputTex` (GL_RGBA8, displaySize), `prevDilatedTex` (GL_RGBA16F, renderSize)

### Step 1: Add texture fields

Add at the top of `FSR3Pipeline` class:

```java
// Frame generation textures
private int prevColorTex;
private int fgOutputTex;
private int prevDilatedTex;
private boolean hasPrevFrame;
```

### Step 2: Create textures in `ensureTextures()`

Add after the existing `rcasOutputTex` creation:

```java
// Frame generation textures
if (prevColorTex == 0) {
    prevColorTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
    fgOutputTex = createTex2D(displayWidth, displayHeight, GL43C.GL_RGBA8, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
    prevDilatedTex = createTex2D(renderWidth, renderHeight, GL43C.GL_RGBA16F, GL43C.GL_LINEAR, GL43C.GL_LINEAR);
}
```

### Step 3: Delete in `deleteTextures()`

After the rcasOutputTex deletion in the array and reset block:

```java
// Add to the int[] texs array
// Add reset lines: prevColorTex = 0; fgOutputTex = 0; prevDilatedTex = 0;
```

### Step 4: Add setter for prev frame data

```java
public void setPrevFrame(int colorTex, int dilatedTex) {
    GL43C.glCopyImageSubData(colorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevColorTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        displayWidth, displayHeight, 1);
    GL43C.glCopyImageSubData(dilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        prevDilatedTex, GL43C.GL_TEXTURE_2D, 0, 0, 0, 0,
        renderWidth, renderHeight, 1);
    hasPrevFrame = true;
}
```

### Step 5: Add getter for FG output

```java
public int getFgOutputTexture() {
    return fgOutputTex;
}

public boolean hasPreviousFrame() {
    return hasPrevFrame;
}

public void resetFrameGen() {
    hasPrevFrame = false;
}
```
