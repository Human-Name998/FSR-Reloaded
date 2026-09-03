#!/usr/bin/env python3
"""Fix the RCAS sharpening sign + weight in all copies (FSR1/FSR2/FSR3 embedded
Java text blocks + on-disk PrismLauncher fsr2/fsr3 .glsl).

WHY (2026-08-08, "edges smoothening but very blurry"): the RCAS "sharpen" was a
BLUR. `sharpend = c + w * (sharpend - c)` with sharpend = the 8-tap local
average pulls the pixel TOWARD the average = low-pass. Real CAS/unsharp masking
pulls AWAY: `c + w * (c - avg)`. And the weight `w = clamp(1.0 - sharp*d/dMax)`
was high in flat areas / low at edges -- the inverse of an edge-adaptive
sharpen. Fix both: weight = sharp * (d/dMax) (0 in flat, `sharp` at strong
edges, monotonic in the config slider) and pull away from the average.

Re-runnable (idempotent on the exact old lines).
"""

import sys
from pathlib import Path

APPDATA = Path.home() / "AppData" / "Roaming"
INSTANCE = APPDATA / "PrismLauncher" / "instances" / "26.1" / "minecraft" / "config" / "fsr-reloaded" / "shaders"

FILES = [
    Path("src/client/java/com/fsr2mod/fsr/FSR1Pipeline.java"),
    Path("src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java"),
    Path("src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java"),
    INSTANCE / "fsr2" / "RCAS_CS.glsl",
    INSTANCE / "fsr3" / "RCAS_CS.glsl",
]

OLD_W = "vec3 w = clamp(1.0 - sharp * d / dMax, 0.0, 1.0);"
NEW_W = (
    "// RCAS-SIGN FIX (2026-08-08, \"edges blurry\"): the old weight was\n"
    "// clamp(1.0 - sharp*d/dMax) -- HIGH in flat areas, LOW at edges, i.e. the\n"
    "// inverse of an edge-adaptive sharpener, and it made the config slider work\n"
    "// backwards. Now the pull scales WITH the edge (0 flat, `sharp` at strong\n"
    "// edges) and with the sharpness slider.\n"
    "vec3 w = clamp(sharp * d / dMax, 0.0, 1.0);"
)

OLD_C = "sharpend = c + w * (sharpend - c);"
OLD_C1 = "vec3 result = c + w * (sharpened - c);"   # FSR1Pipeline uses sharpened/result names
NEW_C = (
    "// RCAS-SIGN FIX: old `c + w*(sharpend - c)` (sharpend = 8-tap average)\n"
    "// moved the pixel TOWARD the average = BLUR. Unsharp masking pulls AWAY:\n"
    "// c + w*(c - avg). This is the whole difference between sharp and soft.\n"
    "sharpend = c + w * (c - sharpend);"
)

patched = 0
for f in FILES:
    if not f.exists():
        print(f"MISSING: {f}")
        continue
    s = f.read_text(encoding="utf-8", errors="replace")
    n1 = s.count(OLD_W)
    n2 = s.count(OLD_C)
    n3 = s.count(OLD_C1)
    if n1 == 0 and n2 == 0 and n3 == 0:
        print(f"SKIP (nothing to patch): {f}")
        continue
    if n1:
        s = s.replace(OLD_W, NEW_W)
        patched += n1
    if n2:
        s = s.replace(OLD_C, NEW_C)
        patched += n2
    if n3:
        s = s.replace(OLD_C1,
            "            // RCAS-SIGN FIX: old `c + w*(sharpened - c)` moved the pixel TOWARD the\n"
            "            // average = BLUR. Unsharp masking pulls AWAY: c + w*(c - avg).\n"
            "            vec3 result = c + w * (c - sharpened);")
        patched += n3
    f.write_text(s, encoding="utf-8")
    print(f"PATCHED w:{n1} combine:{n2} fsr1combine:{n3} -> {f}")

print(f"\nTotal line-replacements: {patched}")
sys.exit(0 if patched >= 8 else 1)
