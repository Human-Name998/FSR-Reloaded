#!/usr/bin/env python3
# Patch 2026-08-08:
# 1. lumaDiff content-alpha boost in all 4 ACCUMULATE copies — the standing-still
#    edge-shake fix. When the luma lock is untrusted (history != current frame),
#    historyYCoCg is 100% box-clamped; the clamp box is built at the de-jittered
#    position which SNAPS between render texels as jitter crosses half-texel
#    boundaries -> blocky edge wobble. Pull the de-jittered FRESH input instead.
# 2. FSRProcessor: remove duplicate setCamStillExact at FSR2 site 1, add the
#    missing call at the onRenderEnd fallback site.
# All files read/written latin-1 (byte-preserving).

import io, os, sys

SHADER_ANCHOR = "                alpha = max(alpha, smoothstep(0.3, 1.0, reactiveFactor) * 0.9);"
SHADER_INSERT = """                alpha = max(alpha, smoothstep(0.3, 1.0, reactiveFactor) * 0.9);
                // Standing-still edge-shake fix (2026-08-08): when history doesn't
                // match the current frame (first-person hand, held items, disocclusion
                // edges, animated foliage, cloud drift) lockTrusted=false -> history
                // is 100% box-clamped. The clamp box sits at the de-jittered
                // position, which SNAPS between adjacent render texels as the Halton
                // jitter crosses half-texel boundaries -> the clamped output toggles
                // between two 3x3 neighborhoods = the blocky edge wobble that
                // survives every weight/lock fix while standing still (mv=0 only
                // helps pixels whose history ALIGNS). Pull the de-jittered FRESH
                // input in instead - it is position-stable by construction (sampled
                // at lrUv = hrUv - uJitter/uRenderSize). Terrain with aligned
                // history has lumaDiff < 0.2 -> no boost -> bit-identical.
                alpha = max(alpha, (lockTrusted ? 0.0 : smoothstep(0.2, 0.6, lumaDiff) * 0.85));"""

def patch(path, repls, label):
    with io.open(path, "r", encoding="latin-1") as f:
        text = f.read()
    for old, new, count in repls:
        n = text.count(old)
        if n < count:
            raise SystemExit("FAIL %s: found %d of %r (need %d)" % (label, n, old[:60], count))
        text = text.replace(old, new, count)
        print("OK   %-40s x%d  %r" % (label, count, old[:50]))
    with io.open(path, "w", encoding="latin-1") as f:
        f.write(text)

M = os.path.expandvars(r"$APPDATA/PrismLauncher/instances/26.1/minecraft")

shader_files = [
    ("src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java", "FSR2 embedded"),
    ("src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java", "FSR3 embedded"),
    (os.path.join(M, "config", "fsr-reloaded", "shaders", "fsr2", "ACCUMULATE_CS.glsl"), "fsr2 disk"),
    (os.path.join(M, "config", "fsr-reloaded", "shaders", "fsr3", "ACCUMULATE_CS.glsl"), "fsr3 disk"),
]
for path, label in shader_files:
    patch(path, [(SHADER_ANCHOR, SHADER_INSERT, 1)], label)

# FSRProcessor: dedupe site 1 (keep tab-indented call) and add the missing site-3 call.
FSRP = "src/client/java/com/fsr2mod/fsr/FSRProcessor.java"
patch(FSRP, [
    ("\t\t                fsr2Pipeline.setCamReactive(fsr23CamReactive);\n            fsr2Pipeline.setCamStillExact(fsr23CamStillExact);\n\t\t                fsr2Pipeline.setCamStillExact(fsr23CamStillExact);",
     "\t\t                fsr2Pipeline.setCamReactive(fsr23CamReactive);\n\t\t                fsr2Pipeline.setCamStillExact(fsr23CamStillExact);", 1),
    ("            fsr2Pipeline.setCamStill(fsr23CamStill);\n            fsr2Pipeline.setCamReactive(fsr23CamReactive);\n            boolean success = fsr2Pipeline.dispatch(",
     "            fsr2Pipeline.setCamStill(fsr23CamStill);\n            fsr2Pipeline.setCamReactive(fsr23CamReactive);\n            fsr2Pipeline.setCamStillExact(fsr23CamStillExact);\n            boolean success = fsr2Pipeline.dispatch(", 1),
], "FSRProcessor")

print("ALL PATCHES APPLIED")
