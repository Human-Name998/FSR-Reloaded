# FSR Reloaded

AMD FidelityFX Super Resolution upscaling for Minecraft: Java Edition, on Fabric.

Render the game below native resolution and upscale it back, trading a little
image quality for a lot of frames. Works standalone or alongside Sodium, Iris
shader packs, and VulkanMod.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| Loader | Fabric Loader 0.19.3 or newer |
| Dependencies | Fabric API (required), ModMenu 17+ (optional, for the in-game settings screen) |
| Java | 21 or newer |
| Side | Client only — installing it on a dedicated server does nothing |

OptiFine and OptiFabric are declared as conflicts.

## Install

1. Install Fabric Loader for 1.21.11 and put [Fabric API](https://modrinth.com/mod/fabric-api) in your `mods` folder.
2. Download FSR Reloaded from [Modrinth](https://modrinth.com/mod/fsr-reloaded) and drop the jar into `mods`.
3. Launch the game and open **Mods → FSR Reloaded → Settings** (or Options → Video Settings with Sodium).

## Upscaling modes

| Mode | Notes |
|---|---|
| FSR 1 (EASU + RCAS) | Spatial only. Cheapest and most compatible — no motion vectors or depth needed. |
| FSR 2 (temporal) | Temporal accumulation. Sharper than FSR 1 in motion, needs motion vectors and depth. |
| FSR 3 (upscaler) | FSR 3 upscaling without frame generation. |
| FSR 3 (frame gen, experimental) | Interpolates extra frames using the mod's own shaders. |
| FSR 3 (frame gen DX12) | Frame generation through the AMD Frame Generation SDK over D3D12. |
| FSR 4 (SDK) | AMD's FSR 4 model, run through the bundled AMD FidelityFX SDK. |

**Platform support.** FSR 1, 2 and 3 are the mod's own GLSL shaders and run
anywhere the mod loads. FSR 4 and Frame Generation (DX12) depend on bundled
AMD binaries and need **64-bit Windows**. On Linux, or on hardware without the
required AMD support, the mod detects this at runtime and routes FSR 4 to the
FSR 3 temporal engine instead of failing.

Frame generation is disabled on Intel GPUs and while Distant Horizons is
actively rendering. On AMD integrated GPUs it is allowed by default
(`fsr3FgAllowIgpuDx12`); because the FidelityFX SDK upscale cannot coexist with
a live frame-generation context on those parts, the mod pre-empts it and uses a
bilinear upscale instead, so frame generation keeps working.

## Known issues

- LOD mods such as Distant Horizons and Voxy can stutter or render incorrectly.
- With a shader pack enabled the first toggle of the pack may briefly drop
  terrain; toggling it off and on again clears it.

## Reporting a bug

Please use [GitHub issues](https://github.com/Human-Name998/FSR-Reloaded/issues)
and include `latest.log`. Issues almost always need to know your GPU, the
upscaling mode you selected, and whether a shader pack was active — the bug
report template asks for these.

## Building from source

The mod has a native component (`fsrvk.dll`) written in C++ that talks to the
AMD SDK and shares textures between D3D12 and OpenGL. You need:

- JDK 21 (set `org.gradle.java.home` in `gradle.properties`)
- CMake and MSVC (Visual Studio) for the native library
- The Vulkan SDK (`VULKAN_SDK`)

```
./gradlew build
```

The jar lands in `build/libs/`. The native library is built by the
`buildNative` task, which `processResources` depends on, so a plain `build`
produces a complete mod jar.

## License

FSR Reloaded is MIT licensed — see [LICENSE](LICENSE).

It redistributes third-party components under their own terms:

- AMD FidelityFX SDK headers and binaries in `third_party/AMD-FSR-API/`, under
  the AMD Software License Agreement (`AMD_FSR_API_LICENSE.txt`).
- ONNX Runtime in `third_party/onnxruntime/`.
- The FSR 2 OpenGL reference sources in `FidelityFX-FSR2-OpenGL-master/`.
