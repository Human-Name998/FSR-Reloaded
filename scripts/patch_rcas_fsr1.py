#!/usr/bin/env python3
"""Replace the custom RCAS sharpener with the REAL FidelityFX FSR1 RCAS.

Why (2026-08-08, "the grass is just a blurry mess"): the custom unsharp-mask
+ local-min/max-clamp sharpener failed twice:
  1. The sign fix amplified overshoot -> the dark border around the sun.
  2. The halo clamp froze bright thin features: with the CENTER included in
     the min/max range, a grass blade IS the local maximum, so
     clamp(sharpened, mn3, mx3) == c for the blade itself -> ZERO sharpening
     on grass (and stars, leaves, held items). It looked "smooth" but all
     fine detail stayed at its soft temporally-accumulated value.

The real FSR1 RCAS (FidelityFX SDK ffx_fsr1.h, FsrRcasF) instead uses a 5-tap
CROSS filter with a negative lobe whose weight is SOLVED (hitMin/hitMax) so
the output mathematically cannot clip out of [0,1] - anti-overshoot by
construction, not by post-clamp:
  * sun edge (ring contains a saturated 1.0 pixel) -> solve drives the lobe
    toward 0 -> no dark border (the user's previous complaint).
  * thin bright features (grass blade = local max, ring below 1.0) -> lobe is
    nonzero -> the blade is genuinely sharpened (pulled up, bounded by the
    clip solve) instead of being frozen by a min/max clamp.
  * noise detector (FSR_RCAS_DENOISE): a luma highpass normalized against the
    local range reduces sharpening on grain, focusing the pull on real edges.

The port keeps the mod's existing sharpness plumbing (uRcasConfig.x / 8.0,
uSharpness*7) and FSR_RCAS_LIMIT = 0.25 - 1/16 = 0.1875.

Copies: FSR2Pipeline.java (embedded), FSR3Pipeline.java (embedded),
FSR1Pipeline.java (embedded, tile-based), and the on-disk fsr2/fsr3
RCAS_CS.glsl in the PrismLauncher instance config. Supersedes
patch_rcas_halo.py (the halo clamp is deleted; the solve replaces it).
"""
import os
import re
import sys
from pathlib import Path

APPDATA = Path(os.environ.get("APPDATA", ""))
ROOT = Path(__file__).resolve().parent.parent

DISK_DIR = (
    APPDATA / "PrismLauncher" / "instances" / "26.1" / "minecraft"
    / "config" / "fsr-reloaded" / "shaders"
)

# ---------------------------------------------------------------------------
# FSR2/FSR3 body (embedded Java text blocks + on-disk .glsl): replace from the
# center load through the imageStore. The current text has irregular blank
# lines from the earlier patches; match by anchors with DOTALL.
# ---------------------------------------------------------------------------

FSR23_RE = re.compile(
    r"(\n[ \t]*)vec3 c = imageLoad\(uImg, ip\)\.rgb;.*?imageStore\(uImg, ip, vec4\(sharpend, 1\.0\)\);",
    re.DOTALL,
)

FSR23_NEW = """{indent}// FSR1 RCAS PORT (2026-08-08, "the grass is a blurry mess"): the custom
{indent}// unsharp mask + min/max clamp failed twice - the sign fix overshot (dark
{indent}// border around the sun), then the clamp froze bright thin features: with
{indent}// the center inside the range, a grass blade IS the local max, so clamp()
{indent}// returned it unchanged = zero sharpening on grass. This is the actual
{indent}// FidelityFX FSR1 RCAS (ffx_fsr1.h FsrRcasF): a 5-tap cross filter whose
{indent}// negative-lobe weight is SOLVED (hitMin/hitMax from the ring min/max) so
{indent}// the output can never clip out of [0,1] - anti-overshoot by construction,
{indent}// capped at FSR_RCAS_LIMIT (0.1875), with a noise detector. Sun edges
{indent}// (ring contains a saturated 1.0) drive the lobe to ~0 (no dark border);
{indent}// grass blades (local max, ring below 1.0) get a real pull, bounded.
{indent}vec3 c = imageLoad(uImg, ip).rgb;
{indent}vec3 b = imageLoad(uImg, ip + ivec2(0, -1)).rgb;  // up
{indent}vec3 d = imageLoad(uImg, ip + ivec2(-1, 0)).rgb;  // left
{indent}vec3 f = imageLoad(uImg, ip + ivec2(1, 0)).rgb;   // right
{indent}vec3 h = imageLoad(uImg, ip + ivec2(0, 1)).rgb;   // down
{indent}// Luma*2 (FSR's cheap approx: 0.5*B + (0.5*R + G)).
{indent}float bL = b.b * 0.5 + (b.r * 0.5 + b.g);
{indent}float dL = d.b * 0.5 + (d.r * 0.5 + d.g);
{indent}float eL = c.b * 0.5 + (c.r * 0.5 + c.g);
{indent}float fL = f.b * 0.5 + (f.r * 0.5 + f.g);
{indent}float hL = h.b * 0.5 + (h.r * 0.5 + h.g);
{indent}// Noise detection (FSR_RCAS_DENOISE): luma highpass vs the local range;
{indent}// reduces sharpening on grain, focuses it on real edges.
{indent}float nz = 0.25 * (bL + dL + fL + hL) - eL;
{indent}float lumaMax = max(max(max(bL, dL), max(eL, fL)), hL);
{indent}float lumaMin = min(min(min(bL, dL), min(eL, fL)), hL);
{indent}nz = clamp(abs(nz) / max(lumaMax - lumaMin, 1e-7), 0.0, 1.0);
{indent}nz = -0.5 * nz + 1.0;
{indent}// Ring min/max (the 4 cross taps, center NOT included - that is what lets
{indent}// a local-max blade still sharpen; a post-clamp including the center was
{indent}// the grass-freeze bug).
{indent}vec3 mn4 = min(min(min(b, d), f), h);
{indent}vec3 mx4 = max(max(max(b, d), f), h);
{indent}// Solve for the maximum negative lobe that never clips out of [0,1]:
{indent}//   0 == (w*(ring) + m)/(4w + 1)  ->  w = -m/ring        (hitMin side)
{indent}//   1 == (w*(ring) + m)/(4w + 1)  ->  w = (1-m)/(ring-4) (hitMax side)
{indent}// using ring = 4*mx4 / 4*mn4 (worst case) and m = mn4 / mx4.
{indent}float lowerLimiter = clamp(eL / max(min(min(bL, dL), min(fL, hL)), 1e-7), 0.0, 1.0);
{indent}vec3 hitMin = mn4 * (1.0 / max(4.0 * mx4, 1e-7)) * lowerLimiter;
{indent}vec3 hitMax = (vec3(1.0) - mx4) * (1.0 / min(4.0 * mn4 - 4.0, -1e-7));
{indent}vec3 lobeV = max(-hitMin, hitMax);
{indent}float lobe = max(-0.1875, min(max(lobeV.r, max(lobeV.g, lobeV.b)), 0.0)) * sharp;
{indent}lobe *= nz;
{indent}// Resolve: (w*(b+d+f+h) + c) / (4w + 1).
{indent}vec3 sharpend = (lobe * (b + d + f + h) + c) / max(4.0 * lobe + 1.0, 1e-7);
{indent}sharpend = clamp(sharpend, 0.0, 1.0);
{indent}imageStore(uImg, ip, vec4(sharpend, 1.0));"""


# ---------------------------------------------------------------------------
# FSR1 tile-based body (shared memory, uOutput/uSharpness names).
# ---------------------------------------------------------------------------

# Match from the corner-tap block (the halo patch consumed the original
# cross-tap declarations `vec3 t/b/l/r` without re-declaring them - the new
# body below declares its own cross taps from the tile, so the match just
# needs to start inside the stale region and run to the imageStore).
FSR1_RE = re.compile(
    r"(\n[ \t]*)vec3 tl = tile\[ly\]\[lx\];.*?imageStore\(uOutput, p, vec4\(clamp\(result, 0\.0, 1\.0\), 1\.0\)\);",
    re.DOTALL,
)

FSR1_NEW = """{indent}// FSR1 RCAS PORT (2026-08-08, "the grass is a blurry mess"): same real
{indent}// FidelityFX RCAS as FSR2/3 - 5-tap cross, solved negative lobe that can
{indent}// never clip (no halo ring), noise detector; sharpens local-max blades.
{indent}float sharp = float(int(uSharpness * 7.0 + 0.5)) / 8.0;
{indent}if (sharp < 0.001) {
{indent}    imageStore(uOutput, p, vec4(c, 1.0));
{indent}    return;
{indent}}
{indent}vec3 up = tile[ly][lx + 1];
{indent}vec3 dn = tile[ly + 2][lx + 1];
{indent}vec3 lt = tile[ly + 1][lx];
{indent}vec3 rt = tile[ly + 1][lx + 2];
{indent}float bL = up.b * 0.5 + (up.r * 0.5 + up.g);
{indent}float dL = lt.b * 0.5 + (lt.r * 0.5 + lt.g);
{indent}float eL = c.b * 0.5 + (c.r * 0.5 + c.g);
{indent}float fL = rt.b * 0.5 + (rt.r * 0.5 + rt.g);
{indent}float hL = dn.b * 0.5 + (dn.r * 0.5 + dn.g);
{indent}float nz = 0.25 * (bL + dL + fL + hL) - eL;
{indent}float lumaMax = max(max(max(bL, dL), max(eL, fL)), hL);
{indent}float lumaMin = min(min(min(bL, dL), min(eL, fL)), hL);
{indent}nz = clamp(abs(nz) / max(lumaMax - lumaMin, 1e-7), 0.0, 1.0);
{indent}nz = -0.5 * nz + 1.0;
{indent}vec3 mn4 = min(min(min(up, dn), lt), rt);
{indent}vec3 mx4 = max(max(max(up, dn), lt), rt);
{indent}float lowerLimiter = clamp(eL / max(min(min(bL, dL), min(fL, hL)), 1e-7), 0.0, 1.0);
{indent}vec3 hitMin = mn4 * (1.0 / max(4.0 * mx4, 1e-7)) * lowerLimiter;
{indent}vec3 hitMax = (vec3(1.0) - mx4) * (1.0 / min(4.0 * mn4 - 4.0, -1e-7));
{indent}vec3 lobeV = max(-hitMin, hitMax);
{indent}float lobe = max(-0.1875, min(max(lobeV.r, max(lobeV.g, lobeV.b)), 0.0)) * sharp;
{indent}lobe *= nz;
{indent}vec3 result = (lobe * (up + dn + lt + rt) + c) / max(4.0 * lobe + 1.0, 1e-7);
{indent}imageStore(uOutput, p, vec4(clamp(result, 0.0, 1.0), 1.0));"""


def patch(path: Path, regex: re.Pattern, new_body: str, label: str) -> bool:
    if not path.exists():
        print(f"SKIP (missing): {path}")
        return False
    text = path.read_text(encoding="utf-8")
    new_text, n = regex.subn(
        lambda m: new_body.replace("{indent}", m.group(1)), text, count=1
    )
    if n == 0:
        print(f"SKIP (pattern not found): {path}")
        return False
    path.write_text(new_text, encoding="utf-8")
    print(f"PATCHED: {path}")
    return True


ok = True
for java in (
    ROOT / "src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java",
    ROOT / "src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java",
):
    ok &= patch(java, FSR23_RE, FSR23_NEW, "fsr2/3 embedded")
ok &= patch(ROOT / "src/client/java/com/fsr2mod/fsr/FSR1Pipeline.java", FSR1_RE, FSR1_NEW, "fsr1 embedded")

for name in ("fsr2", "fsr3"):
    glsl = DISK_DIR / name / "RCAS_CS.glsl"
    if not glsl.exists():
        print(f"SKIP (missing): {glsl}")
        ok = False
        continue
    text = glsl.read_text(encoding="utf-8")
    new_text, n = FSR23_RE.subn(
        lambda m: FSR23_NEW.replace("{indent}", m.group(1)), text, count=1
    )
    if n == 0:
        print(f"SKIP (pattern not found): {glsl}")
        ok = False
        continue
    glsl.write_text(new_text.replace("\n", "\r\n"), encoding="utf-8")
    print(f"PATCHED: {glsl}")

sys.exit(0 if ok else 1)
