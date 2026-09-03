# Task 8: Wire MV Generation to Shared Texture

## Files
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

## Context
The existing motion vector compute shader writes to a GL-native texture via `glBindImageTexture`. For FSR4 to read the MV field, the output must be rerouted to the VK-shared MV texture from `Fsr4SharedResources`.

## Steps
1. **Find the MV dispatch location** in FSRProcessor.java — search for where the motion vector compute shader is dispatched (likely in onRenderBegin() or a separate method, dispatching a GL compute shader via glBindImageTexture).

2. **Switch MV output to shared texture when FSR4 active**:
   ```java
   if (fsr4Available && fsr4Initialized) {
       GL43C.glBindImageTexture(MV_UNIT, sharedTexMgr.getMvTexId(), 0,
           false, 0, GL43C.GL_WRITE_ONLY, GL43C.GL_RGBA16F);
   } else {
       // Existing MV output texture
       GL43C.glBindImageTexture(MV_UNIT, existingMvTex, ...);
   }
   ```
   The MV shader itself needs no changes — it writes to whatever texture ID is bound. The shared texture is imported as a GL texture with the same format.

3. Build: `.\gradlew.bat build`
4. Commit

## Important
- This is a small change — just redirect glBindImageTexture to the shared texture
- The shared MV texture was created by SharedTextureManager.allocateFsr4Pool() (Task 4) with format VK_FORMAT_R8G8B8A8_UNORM
- glBindImageTexture format should match — check what format the MV shader expects
