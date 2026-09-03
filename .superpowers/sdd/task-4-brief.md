### Task 4: FSRConfig — add UpscaleMode.NPU

**Files:**
- Modify: `src/client/java/com/fsr2mod/config/FSRConfig.java`

- [ ] **Step 1: Add NPU enum value**

Find `public enum FSRVersion` (or similar enum for upscaling modes) and add NPU:

```java
public enum Up scaleMode {
    FSR1,
    FSR2,
    FSR3_UPS,
    FSR3_FG,
    FSR4,
    NPU  // ← NEW: NPU-only spatial upscale via DirectML
}
```

If the existing config uses `FSRVersion` enum instead, create a separate top-level `UpscaleMode`:

```java
// Add alongside FSRVersion
public enum UpscaleMode {
    FSR1,
    FSR2,
    FSR3_UPS,
    FSR3_FG,
    FSR4,
    NPU
}

@ConfigOption(name = "Upscale Mode", description = "Select upscaling backend")
public UpscaleMode upscaleMode = UpscaleMode.FSR4;
```

Check the existing config structure to match its style. If it uses a `version` field of type `FSRVersion`, add NPU as a new separate field `upscaleMode` that takes precedence when set to NPU.

- [ ] **Step 2: Commit**

```bash
cd /c/Users/arshjot/Downloads/fsr2mod
git add src/client/java/com/fsr2mod/config/FSRConfig.java
git commit -m "npu: add UpscaleMode.NPU to config"
```

---

