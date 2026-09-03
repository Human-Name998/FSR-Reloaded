#!/usr/bin/env python3
# Patch the FSR2/FSR3 ACCUMULATE_CS shaders (embedded Java + on-disk instance
# copies) to remove the BINARY lock-trust switch.
#
# Symptom (2026-08-08): "the edges are smoothening but the smoothened edges are
# very unstable" - the temporal accumulation now builds smooth edges, but they
# boil/pop at the Halton jitter frequency.
#
# Mechanism: lockTrusted was a hard `lumaDiff < 0.2` boolean. At a reconstructed
# edge the accumulated history (time-averaged, soft) and the sharp current frame
# differ by the sub-pixel edge straddle, so lumaDiff oscillates around 0.2 as the
# jitter phase moves. Each crossing flipped histContribFactor between 0 and
# min(lockContrib,0.70) -> the history CONTENT toggled between 70% raw (sharp
# accumulated edge) and 100% box-clamped (soft clipped edge) while alpha stayed
# ~unchanged -> the smoothed edge popped between two states.
#
# Fix: fade the trust in/out continuously over lumaDiff 0.10-0.35 (same center
# as the old 0.2 cutoff; genuine mismatch >0.35 fully disengages, the ~0.26
# green-ghost diff is ~70% disengaged) and apply the standing-still edge-shake
# alpha boost on the complementary (1.0 - lockTrust) scale, so the blend state is
# a continuous function of the diff with no threshold discontinuity.

import os

FILES = [
    "src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java",
    "src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java",
    os.environ["APPDATA"]
    + "/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders/fsr2/ACCUMULATE_CS.glsl",
    os.environ["APPDATA"]
    + "/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders/fsr3/ACCUMULATE_CS.glsl",
]

OLD_BLOCK = """                bool lockTrusted = !isSky && reproLockLife > 0.0 && lockLuma > 0.0 && lumaDiff < 0.2;
                float lockActive = lockTrusted ? 1.0 : 0.0;
                float lockContrib = lockActive * clamp(reproLockLife / 3.0, 0.0, 1.0);"""

NEW_BLOCK = """                // Unstable-smoothed-edges fix (2026-08-08): the hard lumaDiff<0.2 boolean
                // made the lock a BINARY switch - at a reconstructed edge the accumulated
                // history and the sharp current frame differ by the sub-pixel edge
                // straddle, so lumaDiff oscillates around 0.2 with the Halton jitter
                // phase, toggling the history CONTENT between 70% raw (sharp accumulated
                // edge) and 100% box-clamped (soft clipped edge) -> the smoothed edges
                // boil/pop at the jitter frequency. Fade trust in/out continuously over
                // 0.10-0.35 (same center as the old 0.2 cutoff; genuine mismatch >0.35
                // still disengages; the green-ghost diff ~0.26 is ~70% disengaged, and
                // the box clamp still rejects the ghost while it fades).
                float lockActive = (!isSky && reproLockLife > 0.0 && lockLuma > 0.0) ? 1.0 : 0.0;
                float lockTrust = lockActive * clamp(1.0 - smoothstep(0.10, 0.35, lumaDiff), 0.0, 1.0);
                float lockContrib = lockTrust * clamp(reproLockLife / 3.0, 0.0, 1.0);"""

OLD_BOOST = "                alpha = max(alpha, (lockTrusted ? 0.0 : smoothstep(0.2, 0.6, lumaDiff) * 0.85));"
NEW_BOOST = "                alpha = max(alpha, (1.0 - lockTrust) * smoothstep(0.2, 0.6, lumaDiff) * 0.85);"

OLD_COMMENT = "lockTrusted=false -> history"
NEW_COMMENT = "lockTrust=0 -> history"

def patch(path):
    with open(path, "rb") as f:
        raw = f.read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8")
    if crlf:
        text = text.replace("\r\n", "\n")
    changed = 0
    if OLD_BLOCK in text:
        text = text.replace(OLD_BLOCK, NEW_BLOCK)
        changed += 1
    if OLD_BOOST in text:
        text = text.replace(OLD_BOOST, NEW_BOOST)
        changed += 1
    if OLD_COMMENT in text:
        text = text.replace(OLD_COMMENT, NEW_COMMENT)
        changed += 1
    if changed == 0:
        raise SystemExit("NO ANCHORS FOUND in %s" % path)
    if crlf:
        text = text.replace("\n", "\r\n")
    with open(path, "wb") as f:
        f.write(text.encode("utf-8"))
    print("OK: %s (%d replacements)" % (path, changed))

for p in FILES:
    patch(p)

print("DONE")
