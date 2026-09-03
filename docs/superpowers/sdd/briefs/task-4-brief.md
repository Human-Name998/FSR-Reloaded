# Task 4: Add FG config option

**Files:**
- Modify: `src/client/java/com/fsr2mod/config/FSRConfig.java` - Add FSRVersion enum entry
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java` - Update FSR3 check to include FG

### A. FSRConfig.java: Add enum entry

In the `FSRVersion` enum, add `FSR3_FG` after `FSR3_UPS`:

```java
FSR3_FG("FSR 3 (frame gen)");
```

### B. FSRProcessor.java: Update checks

Modify `isFsr3()` to return true for both FSR3_UPS and FSR3_FG:

```java
private boolean isFsr3() {
    return config.version != null && (config.version == FSRConfig.FSRVersion.FSR3_UPS || config.version == FSRConfig.FSRVersion.FSR3_FG);
}
```

Add a helper to check if FG is specifically enabled:

```java
private boolean isFrameGen() {
    return config.version != null && config.version == FSRConfig.FSRVersion.FSR3_FG;
}
```

### C. FSRProcessor.java: Guard FG path

In `doPresent()`, change the FG condition from:
```java
if (isFsr3() && fsr3Pipeline != null && fsr3Pipeline.hasPreviousFrame()) {
```
to:
```java
if (isFrameGen() && fsr3Pipeline != null && fsr3Pipeline.hasPreviousFrame()) {
```

This ensures FG only runs when the user explicitly selects FSR3_FG, not just FSR3_UPS.

### D. FSRProcessor.java: Guard savePrevFrame

In `onRenderEnd()`, change the savePrevFrame call to only run when FG is enabled:

```java
if (isFrameGen() && fsr3Pipeline != null) {
    fsr3Pipeline.savePrevFrame(fsr2OutputTexId);
}
```
