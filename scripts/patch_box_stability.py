#!/usr/bin/env python3
"""Patch the ACCUMULATE_CS clamp-box anchor in all 4 copies (embedded FSR2/FSR3
Java text blocks + on-disk PrismLauncher instance .glsl files).

WHY (2026-08-08, "still shaking when standing still"):
The OUTPROBE burst showed the presented center texel oscillating ~±16 counts in
B, correlated with the Halton jitter phase, ONLY when camStillExact=0 (the
camStillExact=1 window was bit-identical). Root cause: the ellipsoid clamp box
was anchored at `lrUvClamped` — the DE-JITTERED CURRENT position, which moves
+/-0.5 render px with the jitter. At a high-contrast edge (horizon), floor()
snaps between two adjacent render texels, so boxMin/boxMax jump between two 3x3
neighborhoods and the box-clamped history toggles with the jitter phase.

Fix: anchor the box at `reprojUv` (hrUv + jitter-cancelled mv) — the position
where the history is READ. reprojUv is frame-to-frame stable when the camera is
still (hrUv fixed, mv jitter-free) and tracks real motion when it moves. This
matches FSR2 reference semantics (clamp box around the reprojected position).

Must run AFTER patch_jagged_vibration.py / patch_edge_stability.py (they touch
the same file). Re-runnable: only patches the exact old anchor line.
"""

import sys
from pathlib import Path

# ---- Files (existing path resolution from previous scripts) ----
APPDATA = Path.home() / "AppData" / "Roaming"
INSTANCE = APPDATA / "PrismLauncher" / "instances" / "26.1" / "minecraft" / "config" / "fsr-reloaded" / "shaders"

FILES = [
    Path("src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java"),
    Path("src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java"),
    INSTANCE / "fsr2" / "ACCUMULATE_CS.glsl",
    INSTANCE / "fsr3" / "ACCUMULATE_CS.glsl",
]

OLD = "ivec2 boxBase = ivec2(floor(lrUvClamped * vec2(uRenderSize)));"

NEW = (
    "// BOX-STABILITY FIX (2026-08-08, \"still shaking when standing still\"): the\n"
    "// clamp box used to be anchored at lrUvClamped (the de-jittered CURRENT\n"
    "// position), which moves +/-0.5 render px with the Halton jitter - at an edge\n"
    "// floor() snapped between two adjacent render texels, so boxMin/boxMax jumped\n"
    "// between two 3x3 neighborhoods and the box-clamped history toggled with the\n"
    "// jitter phase (OUTPROBE: center texel oscillating +/-16 B counts while\n"
    "// camStillExact=0). Anchor at reprojUv (hrUv + jitter-cancelled mv) - the\n"
    "// position where the history is READ - which is frame-to-frame stable when\n"
    "// the camera is still and tracks real motion when it moves (FSR2 reference:\n"
    "// clamp box around the reprojected position).\n"
    "ivec2 boxBase = ivec2(floor(reprojUv * vec2(uRenderSize)));"
)

# ---- Patch ----
patched = 0
for f in FILES:
    if not f.exists():
        print(f"MISSING: {f}")
        continue
    s = f.read_text(encoding="utf-8", errors="replace")
    n = s.count(OLD)
    if n == 0:
        print(f"SKIP (anchor not found): {f}")
        continue
    if n > 1:
        print(f"WARN multiple anchors ({n}) in {f} — patching all")
    s = s.replace(OLD, NEW)
    f.write_text(s, encoding="utf-8")
    patched += n
    print(f"PATCHED {n}x: {f}")

print(f"\nTotal anchors patched: {patched}")
sys.exit(0 if patched >= 4 else 1)
