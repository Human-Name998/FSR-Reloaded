# FSR Reloaded

**FSR Reloaded** is a Fabric mod that adds AMD FidelityFX Super Resolution (FSR) upscaling to Minecraft.

It provides multiple upscaling paths, including **FSR 1, FSR 2, FSR 3/UPS, and FSR 4**, with additional integration for rendering mods such as **Iris, Sodium, Distant Horizons, and VulkanMod**.

> **Platform:** Windows
> **Loader:** Fabric
> **Minecraft:** 26.1.2

---

## Features

* **FSR 1** spatial upscaling
* **FSR 2** temporal upscaling
* **FSR 3 / UPS** temporal upscaling
* **FSR 4** support through the native AMD FSR 4 path
* Configurable render scale / quality level
* Adjustable temporal sharpening
* Camera jitter and motion-vector handling for temporal FSR
* Iris compatibility
* Sodium compatibility
* Distant Horizons compatibility
* VulkanMod integration
* Native Vulkan/D3D12 interoperability for supported FSR 4 configurations
* In-game keybinds for quickly changing FSR settings

---

## How it works

Normally, Minecraft renders the game at your monitor's full resolution.

With FSR Reloaded, the 3D scene can instead be rendered at a lower resolution and reconstructed to the display resolution.

For example:

```text
Native resolution
1920 × 1080
       │
       ▼
Render at lower resolution
1280 × 720
       │
       ▼
FSR
       │
       ▼
1920 × 1080 output
```

This reduces the amount of work required to render the scene while attempting to preserve image quality.

### FSR 1

FSR 1 is a spatial upscaler. It does not require temporal history or motion vectors.

It is generally the simplest and most compatible FSR mode.

### FSR 2 / FSR 3

The temporal modes use information from previous frames in addition to the current frame.

They make use of:

* Motion vectors
* Depth
* Camera jitter
* Previous-frame history
* Reactive information where available

This allows substantially better reconstruction than a purely spatial upscale, but also makes compatibility more complicated.

### FSR 4

FSR 4 uses a separate native implementation and has additional platform and hardware requirements.

The FSR 4 path can also integrate with VulkanMod through the native interoperability layer.

Note: FSR 4 will only work for Windows and will not getting support for Linux or MacOS
---

## Installation

1. Install the required version of **Minecraft** and **Fabric Loader**.
2. Install the required Fabric API version.
3. Download the FSR Reloaded `.jar`.
4. Place the `.jar` in your Minecraft `mods` folder.
5. Start Minecraft.

FSR Reloaded is primarily designed for **Windows**.

---

## Configuration

FSR Reloaded provides an in-game settings screen.

The default keybinds are:

| Key   | Action                     |
| ----- | -------------------------- |
| `F7`  | Open FSR Reloaded settings |
| `F8`  | Toggle FSR                 |
| `F10` | Cycle FSR version          |
| `J`   | Decrease sharpening        |
| `K`   | Increase sharpening        |

The exact available options depend on the active FSR implementation and installed rendering mods.

---

## Rendering Mod Compatibility

### Iris

FSR 2/3 can operate with Iris using a generic temporal integration path.

The mod attempts to work without requiring individual shader-pack-specific configuration.

However, shader packs can implement their rendering pipelines differently, so compatibility is not guaranteed.

### Sodium

Sodium is supported through the mod's compatibility layer.

The exact behavior depends on the Minecraft and Sodium versions being used.

### VulkanMod

VulkanMod has a separate integration path.

FSR Reloaded can use VulkanMod's Vulkan rendering backend for supported configurations, including the native FSR 4 interoperability path.

Some FSR modes may not be available when VulkanMod is active.

### Distant Horizons

Distant Horizons can work alongside FSR Reloaded, but temporal upscaling and LOD rendering can interact in ways that produce:

* Stuttering
* Ghosting
* Temporal artifacts
* LOD instability
* Unusual motion-vector behavior

Distant Horizons compatibility is therefore considered experimental.

### Voxy

Voxy may also interact poorly with temporal upscaling and external render targets.

Expect possible stuttering or visual artifacts.

---

## FSR Quality

The render scale determines how much of the original resolution is rendered before upscaling.

For example:

```text
100% ── Native resolution
 90%
 80%
 77%
 67%
 59%
 50% ── Much lower render resolution
```

Lower render scales generally increase performance but reduce the amount of information available to the upscaler.

The best setting depends heavily on the GPU, Minecraft scene, shader pack, and desired image quality.

---

## Troubleshooting

### FSR does not appear to increase FPS

FSR primarily reduces the cost of rendering the 3D scene.

If your performance is limited by something else, such as:

* CPU simulation
* Chunk generation
* Entity processing
* Distant Horizons
* Voxy
* World generation
* Java overhead

then lowering the render resolution may have little effect.

### FSR 2/3 looks worse than FSR 1

Temporal upscaling requires good motion vectors, depth information, camera jitter, and frame history.

Mods that change Minecraft's rendering pipeline can interfere with these inputs.

Try:

1. Disabling shader packs.
2. Disabling Distant Horizons/Voxy.
3. Testing a higher render scale.
4. Resetting the temporal history.
5. Comparing against FSR 1.

### VulkanMod causes problems

VulkanMod replaces Minecraft's normal OpenGL rendering path.

Some OpenGL-specific functionality therefore cannot safely be used while VulkanMod is active.

FSR Reloaded detects VulkanMod and uses its Vulkan-specific compatibility path where supported.

### FSR 4 is unavailable

FSR 4 has stricter platform/runtime requirements than the GLSL-based FSR paths.

Make sure:

* You are running Windows.
* The required native components are present.
* Your GPU/driver supports the required FSR 4 path.
* VulkanMod is using a supported configuration if VulkanMod integration is being used.

---

## Building from Source

### Requirements

* JDK 25
* Git
* Gradle wrapper included with the project
* A supported Windows development environment for the native components

Clone the repository:

```bash
git clone https://github.com/Human-Name998/FSR-Reloaded.git
cd FSR-Reloaded
```

Build the Java/Fabric mod with:

```bash
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

The resulting JAR will be placed in:

```text
build/libs/
```

### Native components

FSR Reloaded also contains native code used by the Vulkan/D3D12 integration.

The native component must be built separately using the appropriate Windows C++ toolchain and project configuration.

Do not commit generated native build directories or binaries to the repository.

---

## Project Structure

```text
FSR-Reloaded/
├── src/
│   ├── client/
│   │   └── java/
│   │       └── com/fsr2mod/
│   │           ├── compat/
│   │           ├── config/
│   │           ├── fsr/
│   │           ├── gui/
│   │           ├── mixin/
│   │           ├── sodium/
│   │           └── vulkan/
│   │
│   ├── main/
│   │   └── resources/
│   │
│   └── native/
│       └── fsrvk/
│
├── gradle/
├── build.gradle
├── gradle.properties
└── README.md
```

The Java code handles the Minecraft/Fabric integration and rendering orchestration, while the native component provides functionality that cannot be implemented entirely through Java/OpenGL.

---

## Known Limitations

FSR Reloaded is still under active development.

Known areas where compatibility can vary include:

* Shader packs
* Iris
* Distant Horizons
* Voxy
* VulkanMod
* Temporal motion vectors
* Render-target replacements
* Native FSR 4 interoperability
* GPU/driver combinations

If you encounter visual corruption, crashes, severe stuttering, or incorrect rendering, please include your:

* Minecraft version
* Fabric Loader version
* FSR Reloaded version
* GPU
* GPU driver version
* Sodium/Iris/VulkanMod version
* Shader pack
* Distant Horizons/Voxy version
* `latest.log`
* Crash report, if applicable

---

## Reporting Issues

Before opening an issue, test whether the problem still occurs with unnecessary rendering mods disabled.

A useful bug report should include:

```text
Minecraft version:
Fabric Loader:
FSR Reloaded:
GPU:
GPU driver:
Sodium:
Iris:
VulkanMod:
Distant Horizons:
Voxy:
Shader pack:
FSR version:
Render scale:
```

Attach the relevant log or crash report when possible.

---

## Development

The project is primarily written in **Java**, with native C/C++ components for the Vulkan/D3D12 interoperability layer.

The main rendering pipeline is coordinated by `FSRProcessor`, while individual FSR implementations and compatibility layers are separated into their respective packages.

The project is experimental and APIs or implementation details may change as Minecraft, Fabric, AMD's FSR implementations, and supported rendering mods evolve.

---

## License

See the repository's license files for the licensing terms of FSR Reloaded and its bundled/native components.

Third-party components retain their respective licenses and attribution requirements.

---

## Credits

FSR Reloaded builds upon technologies and APIs from:

* AMD FidelityFX
* Fabric
* Minecraft
* Vulkan
* OpenGL
* D3D12

See the relevant source files and third-party license notices for additional attribution.

---

## Status

FSR Reloaded is an actively developed project.

The primary goal is to provide practical FSR upscaling for Minecraft while maintaining compatibility with modern rendering mods and experimenting with native FSR 4/Vulkan integration.
