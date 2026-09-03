### Task 6: Build system — CMake + Gradle + model scripts

**Files:**
- Modify: `src/native/fsrvk/CMakeLists.txt`
- Modify: `build.gradle`
- Create: `scripts/download_fsrcnn.py`
- Create: `scripts/quantize_fsrcnn.py`

- [ ] **Step 1: Update CMakeLists.txt**

Add `npu_device.cpp` and `npu_upscale.cpp` to sources, link ONNX Runtime:

```cmake
# At the top, find ONNX Runtime
# Assume onnxruntime is installed or available as a NuGet package
# For POC: set ONNX Runtime path manually
set(ONNXRUNTIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/onnxruntime" CACHE PATH "ONNX Runtime root")

add_library(fsrvk SHARED
    jni_bridge.cpp
    vulkan_device.cpp
    vulkan_memory.cpp
    d3d12_device.cpp
    d3d12_shared_resources.cpp
    fsr4_context.cpp
    sync_manager.cpp
    npu_device.cpp          # ← NEW
    npu_upscale.cpp         # ← NEW
)

target_include_directories(fsrvk PRIVATE
    ${ONNXRUNTIME_DIR}/include
)

target_link_directories(fsrvk PRIVATE
    ${ONNXRUNTIME_DIR}/lib
)

target_link_libraries(fsrvk
    onnxruntime
    ${D3D12_LIBRARIES}
    ${DXGI_LIBRARIES}
)
```

- [ ] **Step 2: Update build.gradle**

Add ONNX Runtime DLLs and model to JAR:

```groovy
// In processResources section (alongside existing AMD FSR4 DLLs):
from("${project.rootDir}/third_party/onnxruntime/lib") {
    include 'onnxruntime.dll'
    include 'onnxruntime_providers_dml.dll'
    into 'natives'
}
from("${project.rootDir}/third_party/onnxruntime/models") {
    include 'fsrcnn-x2-int8.onnx'
    into 'natives'
}
```

- [ ] **Step 3: Write download_fsrcnn.py**

```python
#!/usr/bin/env python3
"""Download FSRCNN-x2 from ONNX Model Zoo and save to third_party/onnxruntime/models/"""
import urllib.request
import os
import sys

MODEL_URL = "https://github.com/onnx/models/raw/main/vision/super_resolution/FSRCNN/model/FSRCNN-x2.onnx"
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "third_party", "onnxruntime", "models")
OUT_PATH = os.path.join(OUT_DIR, "FSRCNN-x2.onnx")

if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"Downloading FSRCNN-x2 from {MODEL_URL}...")
    urllib.request.urlretrieve(MODEL_URL, OUT_PATH)
    print(f"Saved to {OUT_PATH} ({os.path.getsize(OUT_PATH)} bytes)")
```

- [ ] **Step 4: Write quantize_fsrcnn.py**

```python
#!/usr/bin/env python3
"""Quantize FSRCNN-x2 to INT8 for NPU inference."""
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import os

MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "third_party", "onnxruntime", "models")
INPUT_PATH = os.path.join(MODEL_DIR, "FSRCNN-x2.onnx")
OUTPUT_PATH = os.path.join(MODEL_DIR, "fsrcnn-x2-int8.onnx")

if __name__ == "__main__":
    if not os.path.exists(INPUT_PATH):
        print(f"ERROR: {INPUT_PATH} not found. Run download_fsrcnn.py first.")
        exit(1)
    
    print(f"Quantizing {INPUT_PATH} to INT8...")
    quantize_dynamic(INPUT_PATH, OUTPUT_PATH, weight_type=QuantType.QInt8)
    print(f"Saved INT8 model to {OUTPUT_PATH} ({os.path.getsize(OUTPUT_PATH)} bytes)")
```

- [ ] **Step 5: Commit**

```bash
cd /c/Users/arshjot/Downloads/fsr2mod
git add src/native/fsrvk/CMakeLists.txt build.gradle scripts/
git commit -m "npu: build system + model download scripts"
```

---

