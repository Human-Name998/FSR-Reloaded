### Task 5: Wire NPU mode into FSRProcessor

**Files:**
- Modify: `src/client/java/com/fsr2mod/fsr/FSRProcessor.java`

- [ ] **Step 1: Add NPU state fields and init call**

Find the FSRProcessor class fields section and add:

```java
// NPU upscaling state
private boolean npuActive;
private long npuColorResourcePtr;   // D3D12 pool color resource (index 0)
private long npuOutputResourcePtr;  // D3D12 pool output resource (index 4)
```

Find `onRenderBegin` or the FSR4 init section. After FSR4 pool init succeeds, add NPU init:

```java
// NPU mode initialization (if config says NPU)
if (config.upscaleMode == FSRConfig.UpscaleMode.NPU && !npuActive) {
    if (NativeLib.isLoaded() && sharedTextureManager != null) {
        long gpuDevicePtr = Fsr4Native.getD3D12DevicePtr(); // reuse D3D12 device
        if (gpuDevicePtr != 0) {
            String modelPath = getNpuModelPath(); // path to embedded ONNX
            boolean ok = NpuUpscale.init(gpuDevicePtr, modelPath);
            if (ok) {
                // Get NPU-opened resource pointers from shared pool
                npuColorResourcePtr = VulkanModCompat.getImportedInputResourcePtr();
                npuOutputResourcePtr = VulkanModCompat.getImportedOutputResourcePtr();
                npuActive = true;
                FSRMod.LOGGER.info("[NPU] Mode activated");
            }
        }
    }
}
```

- [ ] **Step 2: Add NPU dispatch path in render loop**

In the frame rendering section (where FSR4 dispatch currently runs), add a branch:

```java
if (config.upscaleMode == FSRConfig.UpscaleMode.NPU && npuActive) {
    // NPU path: skip FSR4, run NPU upscale
    npuFrame();
    return; // skip FSR4 dispatch
}
// ... existing FSR4 / FSR2/3 dispatch ...
```

Add the `npuFrame()` method:

```java
private void npuFrame() {
    if (!npuActive || npuColorResourcePtr == 0 || npuOutputResourcePtr == 0) return;
    
    long t0 = System.nanoTime();
    
    // Ensure scene data is in pool color (Copy1 already ran via recordFrameCopyOps)
    // Run NPU upscale
    boolean ok = NpuUpscale.dispatch(
        npuColorResourcePtr,
        npuOutputResourcePtr,
        scaledWidth,
        scaledHeight
    );
    
    long dt = System.nanoTime() - t0;
    if (dt > 5_000_000) { // >5ms
        FSRMod.LOGGER.warn("[NPU] Frame took {}ms", dt / 1_000_000);
    }
    
    if (!ok) {
        FSRMod.LOGGER.warn("[NPU] Dispatch failed, falling back");
        // Optionally fall back to bilinear blit
    }
}
```

- [ ] **Step 3: Wire isTemporalUpscaler to return false for NPU**

Find `isTemporalUpscaler()` and make sure NPU mode returns false (NPU is spatial only, no jitter needed):

```java
public boolean isTemporalUpscaler() {
    if (config.upscaleMode == FSRConfig.UpscaleMode.NPU) return false;
    return isFsr2() || isFsr3();
}
```

- [ ] **Step 4: Add shutdown hook**

In the shutdown/destroy method:

```java
if (npuActive) {
    NpuUpscale.shutdown();
    npuActive = false;
}
```

- [ ] **Step 5: Implement getNpuModelPath()**

```java
private String getNpuModelPath() {
    // Model is embedded in JAR at natives/fsrcnn-x2-int8.onnx
    // Extract to temp directory for ONNX Runtime to load
    try {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("fsr-npu");
        java.nio.file.Path modelPath = tempDir.resolve("fsrcnn-x2-int8.onnx");
        
        // Copy from JAR resource
        try (var in = getClass().getResourceAsStream("/natives/fsrcnn-x2-int8.onnx")) {
            if (in != null) {
                java.nio.file.Files.copy(in, modelPath, 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return modelPath.toAbsolutePath().toString();
            }
        }
    } catch (Exception e) {
        FSRMod.LOGGER.error("[NPU] Failed to extract model: {}", e.getMessage());
    }
    return null;
}
```

- [ ] **Step 6: Commit**

```bash
cd /c/Users/arshjot/Downloads/fsr2mod
git add src/client/java/com/fsr2mod/fsr/FSRProcessor.java
git commit -m "npu: wire NPU dispatch path into FSRProcessor"
```

---

