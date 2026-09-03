#!/usr/bin/env python3
"""Patch all 5 RCAS copies with the AMD CAS anti-halo fix.

Problem (2026-08-08, "the sun gets a dark border when it is diagonal"): the
sign-fixed RCAS used a 4-tap CROSS whose average straddles a diagonal edge
asymmetrically, and allowed the sharpened pixel to leave the local color range
-> a dark/light ring around bright objects (sun), worst on diagonals, so
sharpness OFF looked better than ON.

Fix (AMD CAS anti-halo):
  1. Symmetric 3x3 neighborhood: add the 4 corner taps so diagonal edges are
     treated like axis-aligned ones.
  2. Overshoot clamp: `clamp(sharpened, min3, max3)` over the 3x3 local range
     (per channel) -- a pixel can never be pulled outside the colors already
     present in its neighborhood, so halos become impossible.

Copies: FSR2Pipeline.java (embedded), FSR3Pipeline.java (embedded),
FSR1Pipeline.java (embedded, tile-based), and the on-disk fsr2/fsr3
RCAS_CS.glsl in the PrismLauncher instance config.
"""
import re
import sys
from pathlib import Path

APPDATA = Path(__import__("os").environ.get("APPDATA", ""))
ROOT = Path(__file__).resolve().parent.parent

DISK_DIR = (
    APPDATA / "PrismLauncher" / "instances" / "26.1" / "minecraft"
    / "config" / "fsr-reloaded" / "shaders"
)

# ---------------------------------------------------------------------------
# FSR2/FSR3 shared body (embedded Java text blocks + on-disk .glsl).
# The current text has irregular column-0 comment lines from the earlier
# patch; match from the d-computation through the imageStore and replace the
# whole region.
# ---------------------------------------------------------------------------

FSR23_RE = re.compile(
    r"(\n[ \t]*)vec3 d = max\(abs\(t - c\), abs\(b - c\)\);.*?imageStore\(uImg, ip, vec4\(sharpend, 1\.0\)\);",
    re.DOTALL,
)


def fsr23_body(indent: str) -> str:
    return f"""{indent}// RCAS-HALO FIX (2026-08-08, "sun gets a dark border when diagonal"):
{indent}// the old code sharpened with a 4-tap CROSS whose average straddles a
{indent}// diagonal edge asymmetrically and let the sharpened pixel leave the local
{indent}// color range -> a dark/light ring around bright objects (sun), worst on
{indent}// diagonals, making sharpness OFF look better than ON. Now a symmetric 3x3
{indent}// (corners added) + AMD CAS's overshoot clamp to the local min/max: a pixel
{indent}// can never be pulled outside the colors already present in its 3x3
{indent}// neighborhood, so the halo is impossible.
{indent}vec3 tl = imageLoad(uImg, ip + ivec2(-1, -1)).rgb;
{indent}vec3 tr = imageLoad(uImg, ip + ivec2(1, -1)).rgb;
{indent}vec3 bl = imageLoad(uImg, ip + ivec2(-1, 1)).rgb;
{indent}vec3 br = imageLoad(uImg, ip + ivec2(1, 1)).rgb;
{indent}// The center pixel is included in the min/max (reviewer 2026-08-08): an
{indent}// isolated single-pixel bright feature (night stars, particles) would
{indent}// otherwise be flattened toward its neighbors by the overshoot clamp.
{indent}vec3 mn3 = min(min(min(min(t, b), min(l, r)), min(min(tl, tr), min(bl, br))), c);
{indent}vec3 mx3 = max(max(max(max(t, b), max(l, r)), max(max(tl, tr), max(bl, br))), c);

{indent}vec3 d = max(abs(t - c), abs(b - c));
{indent}d = max(d, abs(l - c));
{indent}d = max(d, abs(r - c));
{indent}d = max(d, abs(tl - c));
{indent}d = max(d, abs(tr - c));
{indent}d = max(d, abs(bl - c));
{indent}d = max(d, abs(br - c));

{indent}float dMax = max(d.r, max(d.g, d.b));
{indent}if (dMax < 1e-7) return;

{indent}// RCAS-SIGN FIX (2026-08-08, "edges blurry"): the old weight was
{indent}// clamp(1.0 - sharp*d/dMax) -- HIGH in flat areas, LOW at edges, i.e. the
{indent}// inverse of an edge-adaptive sharpener, and it made the config slider work
{indent}// backwards. Now the pull scales WITH the edge (0 flat, `sharp` at strong
{indent}// edges) and with the sharpness slider.
{indent}vec3 w = clamp(sharp * d / dMax, 0.0, 1.0);

{indent}vec3 avg = (t + b + l + r + tl + tr + bl + br) * (1.0 / 8.0);
{indent}// RCAS-SIGN FIX: old `c + w*(sharpend - c)` (sharpend = 8-tap average)
{indent}// moved the pixel TOWARD the average = BLUR. Unsharp masking pulls AWAY:
{indent}// c + w*(c - avg). This is the whole difference between sharp and soft.
{indent}vec3 sharpend = c + w * (c - avg);
{indent}// RCAS-HALO FIX: overshoot clamp -- stay inside the local 3x3 range.
{indent}sharpend = clamp(sharpend, mn3, mx3);
{indent}sharpend = clamp(sharpend, 0.0, 1.0);

{indent}imageStore(uImg, ip, vec4(sharpend, 1.0));"""


# ---------------------------------------------------------------------------
# FSR1 tile-based body (shared memory, different names: sharpened/result).
# ---------------------------------------------------------------------------

FSR1_RE = re.compile(
    r"(\n[ \t]*)vec3 t = tile\[ly\]\[lx \+ 1\];.*?imageStore\(uOutput, p, vec4\(clamp\(result, 0\.0, 1\.0\), 1\.0\)\);",
    re.DOTALL,
)


def fsr1_body(indent: str) -> str:
    return f"""{indent}vec3 tl = tile[ly][lx];
{indent}vec3 tr = tile[ly][lx + 2];
{indent}vec3 bl = tile[ly + 2][lx];
{indent}vec3 br = tile[ly + 2][lx + 2];
{indent}// RCAS-HALO FIX (2026-08-08, "sun gets a dark border when diagonal"):
{indent}// symmetric 3x3 (corners added) + overshoot clamp to the local min/max so
{indent}// sharpening can never leave the colors already present (no halo rings).
{indent}// The center pixel is included in the min/max (reviewer 2026-08-08): an
{indent}// isolated single-pixel bright feature (night stars, particles) would
{indent}// otherwise be flattened toward its neighbors by the overshoot clamp.
{indent}vec3 mn3 = min(min(min(min(t, b), min(l, r)), min(min(tl, tr), min(bl, br))), c);
{indent}vec3 mx3 = max(max(max(max(t, b), max(l, r)), max(max(tl, tr), max(bl, br))), c);

{indent}vec3 d = max(abs(t - c), abs(b - c));
{indent}d = max(d, abs(l - c));
{indent}d = max(d, abs(r - c));
{indent}d = max(d, abs(tl - c));
{indent}d = max(d, abs(tr - c));
{indent}d = max(d, abs(bl - c));
{indent}d = max(d, abs(br - c));
{indent}float dMax = max(d.r, max(d.g, d.b));
{indent}if (dMax < 1e-7) {{
{indent}    imageStore(uOutput, p, vec4(c, 1.0));
{indent}    return;
{indent}}}
{indent}float sharp = float(int(uSharpness * 7.0 + 0.5)) / 8.0;
{indent}// RCAS-SIGN FIX (2026-08-08, "edges blurry"): the old weight was
{indent}// clamp(1.0 - sharp*d/dMax) -- HIGH in flat areas, LOW at edges, i.e. the
{indent}// inverse of an edge-adaptive sharpener, and it made the config slider work
{indent}// backwards. Now the pull scales WITH the edge (0 flat, `sharp` at strong
{indent}// edges) and with the sharpness slider.
{indent}vec3 w = clamp(sharp * d / dMax, 0.0, 1.0);
{indent}vec3 avg = (t + b + l + r + tl + tr + bl + br) * (1.0 / 8.0);
{indent}// RCAS-SIGN FIX: old `c + w*(sharpened - c)` moved the pixel TOWARD the
{indent}// average = BLUR. Unsharp masking pulls AWAY: c + w*(c - avg).
{indent}vec3 result = c + w * (c - avg);
{indent}// RCAS-HALO FIX: overshoot clamp -- stay inside the local 3x3 range.
{indent}result = clamp(result, mn3, mx3);
{indent}imageStore(uOutput, p, vec4(clamp(result, 0.0, 1.0), 1.0));"""


def patch_file(path: Path, regex: re.Pattern, body_fn, label: str) -> bool:
    if not path.exists():
        print(f"SKIP (missing): {path}")
        return False
    text = path.read_text(encoding="utf-8")
    new_text, n = regex.subn(
        lambda m: body_fn(m.group(1)), text, count=1
    )
    if n == 0:
        print(f"SKIP (pattern not found): {path}")
        return False
    path.write_text(new_text, encoding="utf-8")
    print(f"PATCHED: {path} ({n} region)")
    return True


ok = True

# FSR1 halo-fill: edge-clamp instead of 0-fill so border pixels don't get a
# black sample in the min/max (dark border ring at the screen edge).
FSR1_FILL_OLD = """                if (tp.x >= 0 && tp.x < uSize.x && tp.y >= 0 && tp.y < uSize.y) {
                    tile[ty][tx] = texelFetch(uInput, tp, 0).rgb;
                } else {
                    tile[ty][tx] = vec3(0.0);
                }"""
FSR1_FILL_NEW = """                tile[ty][tx] = texelFetch(uInput, clamp(tp, ivec2(0), uSize - 1), 0).rgb;"""

# Embedded copies: 12-space indentation inside the text blocks.
for java, regex, body_fn in (
    (ROOT / "src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java", FSR23_RE, fsr23_body),
    (ROOT / "src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java", FSR23_RE, fsr23_body),
    (ROOT / "src/client/java/com/fsr2mod/fsr/FSR1Pipeline.java", FSR1_RE, fsr1_body),
):
    ok &= patch_file(java, regex, body_fn, "java")

fsr1 = ROOT / "src/client/java/com/fsr2mod/fsr/FSR1Pipeline.java"
if fsr1.exists():
    t = fsr1.read_text(encoding="utf-8")
    if FSR1_FILL_OLD in t:
        fsr1.write_text(t.replace(FSR1_FILL_OLD, FSR1_FILL_NEW), encoding="utf-8")
        print("PATCHED: FSR1 halo-fill edge-clamp")
    else:
        print("SKIP (fill pattern not found): FSR1Pipeline.java")

# On-disk copies: 4-space indentation, CRLF line endings.
for name in ("fsr2", "fsr3"):
    glsl = DISK_DIR / name / "RCAS_CS.glsl"
    if not glsl.exists():
        print(f"SKIP (missing): {glsl}")
        ok = False
        continue
    text = glsl.read_text(encoding="utf-8")
    new_text, n = FSR23_RE.subn(lambda m: fsr23_body(m.group(1)), text, count=1)
    if n == 0:
        print(f"SKIP (pattern not found): {glsl}")
        ok = False
        continue
    # Preserve CRLF line endings (the file is CRLF; our body has \n only).
    glsl.write_text(new_text.replace("\n", "\r\n"), encoding="utf-8")
    print(f"PATCHED: {glsl}")

sys.exit(0 if ok else 1)
