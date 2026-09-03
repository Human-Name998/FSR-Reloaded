---
name: Bug report
about: Report a crash, visual glitch, or performance problem
title: ''
labels: bug
assignees: ''
---

**Describe the bug**
A clear and concise description of what goes wrong.

**To reproduce**
1. Set FSR mode to ...
2. Enable shader pack ...
3. Join a world / open settings / etc.
4. See the error

**Expected behavior**
What you expected to happen instead.

**Screenshots or video**
Especially for visual problems (ghosting, flicker, shimmering, blocky UI).

**Log file**
Please attach `latest.log` from `.minecraft/logs/`, or the crash report from
`.minecraft/crash-reports/`. If it is large, use https://mclo.gs and paste the
link. A bug report without a log usually cannot be diagnosed.

**Your setup**

- FSR Reloaded version:
- Upscaling mode selected: (FSR 1 / FSR 2 / FSR 3 upscaler / FSR 3 frame gen / FSR 3 frame gen DX12 / FSR 4)
- Render scale / quality setting:
- Minecraft version:
- Fabric Loader version:
- Java version:
- OS: (e.g. Windows 11, Linux — FSR 4 and frame generation need 64-bit Windows)
- GPU and driver version: (e.g. Radeon 860M, driver 25.x — frame generation is AMD-only)
- Renderer: (OpenGL / VulkanMod)

**Shader pack**
Are you using a shader pack? If so, which one and which version? This matters —
frame generation and shader packs interact.

**Other mods**
List anything that touches rendering: Sodium, Iris, Distant Horizons, Voxy,
Photonics, etc.

**Anything else**
When it started, whether it reproduces every time, and any workaround you found.
