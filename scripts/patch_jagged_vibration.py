#!/usr/bin/env python3
# Patch the FSR2/FSR3 ACCUMULATE_CS shaders (embedded Java + on-disk instance
# copies) to replace the per-frame lumaDiff fresh-boost with a persistent,
# time-integrated clamp-rejection weight decay.
#
# Symptom (2026-08-08, after the lock-trust softening): "the jagged edges are
# vibrating". The temporal smoothing of ACCEPTED edges is now stable, but the
# NON-accumulating (jagged) edges vibrate.
#
# Mechanism: the 2026-08-08 lumaDiff boost
#   alpha = max(alpha, (1.0 - lockTrust) * smoothstep(0.2, 0.6, lumaDiff) * 0.85)
# modulated the fresh-frame share with the PER-FRAME history-vs-current diff.
# At a non-accumulating edge (flower, held item, disocclusion, thin geometry)
# that diff oscillates with the Halton jitter phase (the history read wobbles
# across the edge, so its luma swings between the two surfaces), sweeping the
# boost 0 -> 0.85 every few frames -> the output alternated between the
# box-clamped history color and the fresh color -> the jagged edge vibrated.
#
# Fix: replace the noisy per-frame boost with a PERSISTENT clamp-rejection
# signal. When the box clamp has to clip the history (content mismatch), decay
# the accumulation weight by up to 0.85/frame. The weight is TIME-INTEGRATED,
# so the resulting fresh share settles on a stable ~85% at persistently
# mismatched pixels (within ~5 frames) instead of pulsing, and stays ~0 at
# aligned/sky pixels (lumaDiff < 0.25 -> no decay -> box-clamped accumulation
# untouched). The old boost line is removed.

import os

FILES = [
    "src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java",
    "src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java",
    os.environ["APPDATA"]
    + "/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders/fsr2/ACCUMULATE_CS.glsl",
    os.environ["APPDATA"]
    + "/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders/fsr3/ACCUMULATE_CS.glsl",
]

# (E) Declare the persistent decay before the isNewSample branch.
OLD_IF = "            if (isNewSample) {\n"
NEW_IF = (
    "            // Content-mismatch persistence (2026-08-08 v2, \"jagged edges\n"
    "            // vibrating\" report): set inside the else branch when the box clamp\n"
    "            // has to clip the history. 1.0 = aligned (full accumulation).\n"
    "            float rejectDecay = 1.0;\n"
    "\n"
    "            if (isNewSample) {\n"
)

# (A) Persistent clamp-rejection signal, right after the lumaDiff computation.
OLD_LUMA = (
    "                float lumaDiff = abs(upsampledYCoCg.x - historyYCoCg.x) / max(max(upsampledYCoCg.x, historyYCoCg.x), 1e-6);\n"
)
NEW_LUMA = (
    OLD_LUMA
    + "                // Rejected-pixel persistence (2026-08-08 v2, \"jagged edges vibrating\"\n"
    + "                // report): drive the fresh share from a PERSISTENT clamp-rejection\n"
    + "                // signal instead of the noisy per-frame diff. When the box clamp must\n"
    + "                // clip the history (content mismatch) the accumulation weight decays,\n"
    + "                // so the blend settles on ~85% fresh over a few frames - the weight is\n"
    + "                // time-integrated, hence stable where the per-frame diff pulsed. Sky /\n"
    + "                // cloud (lumaDiff ~0.1) stay below the knee -> no decay -> their\n"
    + "                // box-clamped accumulation is untouched. Telemetry: the [ACCUM_WEIGHT]\n"
    + "                // B channel now reads low at junk pixels by design (the distrust signal).\n"
    + "                float clampReject = clamp((lumaDiff - 0.25) / 0.25, 0.0, 1.0);\n"
    + "                rejectDecay = 1.0 - 0.85 * clampReject;\n"
)

# (B) Remove the oscillating boost line, replace with an explanation.
OLD_BOOST = "                alpha = max(alpha, (1.0 - lockTrust) * smoothstep(0.2, 0.6, lumaDiff) * 0.85);\n"
NEW_BOOST = (
    "                // (2026-08-08 v2) The old lumaDiff-driven fresh boost is REMOVED: its\n"
    "                // input (the history-vs-current diff at a non-accumulating edge)\n"
    "                // oscillates with the jitter phase, so the fresh share pulsed 0->0.85\n"
    "                // and the jagged edges vibrated. The rejectDecay weight decay above\n"
    "                // replaces it with a stable, time-integrated fresh share (~85% at\n"
    "                // persistently-mismatched pixels, ~0% at aligned/sky pixels).\n"
)

# (C/D) Multiply the accumulation weight write by the decay.
OLD_W2 = "            float newAccumWeight = min((isExisting ? adjAccum : 0.0) + fUpsampleLanczosWeightScale, 15.0);\n"
NEW_W2 = "            float newAccumWeight = min((isExisting ? adjAccum : 0.0) + fUpsampleLanczosWeightScale, 15.0) * rejectDecay;\n"
OLD_W3 = "            float newAccumWeight = min((isExisting ? adjAccum : 0.0) + upsampledWeight, 15.0);\n"
NEW_W3 = "            float newAccumWeight = min((isExisting ? adjAccum : 0.0) + upsampledWeight, 15.0) * rejectDecay;\n"


def patch(path):
    with open(path, "rb") as f:
        raw = f.read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8")
    if crlf:
        text = text.replace("\r\n", "\n")
    changed = 0

    def rep(old, new, must=True):
        nonlocal changed, text
        n = text.count(old)
        if n == 0:
            if must:
                raise SystemExit("ANCHOR NOT FOUND (%d) in %s: %r" % (n, path, old[:80]))
            return
        if n > 1:
            raise SystemExit("ANCHOR AMBIGUOUS (%d) in %s: %r" % (n, path, old[:80]))
        text = text.replace(old, new)
        changed += 1

    rep(OLD_IF, NEW_IF)
    rep(OLD_LUMA, NEW_LUMA)
    rep(OLD_BOOST, NEW_BOOST)
    if "fUpsampleLanczosWeightScale, 15.0" in text:
        rep(OLD_W2, NEW_W2)
    else:
        rep(OLD_W3, NEW_W3)
    if crlf:
        text = text.replace("\n", "\r\n")
    with open(path, "wb") as f:
        f.write(text.encode("utf-8"))
    print("OK: %s (%d replacements)" % (path, changed))


for p in FILES:
    patch(p)

print("DONE")
