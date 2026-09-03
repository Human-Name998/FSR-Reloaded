#!/usr/bin/env python3
"""Surgical edit of VulkanModMainPassMixin.java:
1. Add the import for VulkanModUiHelper.
2. Add a guard at the top of the renderScaleSwapped() branch: if the GUI-head
   hook already recorded the FSR4 copies this frame, skip re-recording.
3. Replace the FSR4 if-else block (lines 167-437) with a call to
   VulkanModUiHelper.recordFsr4ScaledCopies.
"""
SRC = "src/client/java/com/fsr2mod/mixin/vulkanmod/VulkanModMainPassMixin.java"
text = open(SRC, encoding="utf-8", newline="").read()
lines = text.split("\n")

# 1. import
if "import com.fsr2mod.mixin.vulkanmod.VulkanModUiHelper;" not in text:
    for i, ln in enumerate(lines):
        if ln.startswith("import com.fsr2mod.compat.VulkanModCompat;"):
            lines.insert(i + 1, "import com.fsr2mod.mixin.vulkanmod.VulkanModUiHelper;")
            break

# 2. guard after the renderScaleSwapped branch opener
for i, ln in enumerate(lines):
    if ln.strip() == "if (VulkanModCompat.renderScaleSwapped()) {":
        guard = (
            "                // NATIVE-RES UI (2026-08-10): the GUI-head hook\n"
            "                // (VulkanModUiHelper.beginNativeResGui, GuiRenderer.render\n"
            "                // HEAD) already recorded the FSR4 copies (Copy1 world-only\n"
            "                // scaled-fb -> pool input, MV-gen, Copy2 pool output ->\n"
            "                // swapchain) BEFORE the GUI drew at native res into the\n"
            "                // swapchain. The swapchain already holds the upscaled world\n"
            "                // + native GUI — re-recording here would double-write.\n"
            "                if (VulkanModUiHelper.copiesRecordedThisFrame) {\n"
            "                    return;\n"
            "                }\n"
        )
        lines.insert(i + 1, guard)
        break

# 3. replace the FSR4 if-else block
start = None
for i, ln in enumerate(lines):
    if ln.strip() == "if (VulkanModCompat.fsr4VkModActive) {":
        start = i
        break
assert start is not None, "fsr4VkModActive start not found"

depth = 0
end = None
for i in range(start, len(lines)):
    ln = lines[i]
    depth += ln.count("{") - ln.count("}")
    if depth <= 0:
        end = i
        break
assert end is not None, "fsr4VkModActive end not found"

replacement = (
    "                    // NATIVE-RES UI (2026-08-10): shared helper — binds the\n"
    "                    // real depth + scene-color sources for the MV-gen, then\n"
    "                    // records Copy1/MV-gen/Copy2 (or the safe blit fallbacks)\n"
    "                    // and stores the frame fence. Extracted verbatim from this\n"
    "                    // hook so the GUI-head hook (VulkanModUiHelper) can record\n"
    "                    // the same pipeline BEFORE the GUI draws (native-res UI).\n"
    "                    VulkanModUiHelper.recordFsr4ScaledCopies(cmdBuf, renderer, w, h);\n"
)
lines[start:end + 1] = [replacement]

open(SRC, "w", encoding="utf-8", newline="\r\n").write("\n".join(lines))
print("replaced lines", start + 1, "-", end + 1)
