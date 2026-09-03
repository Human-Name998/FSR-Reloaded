#!/usr/bin/env python3
# Patch FSRProcessor.java: normalize per-FRAME camera deltas (dPosFrame) to
# per-TICK equivalents before feeding the camReactive floor and the
# fast-jitter stage. The thresholds were calibrated in tick space (walking
# 0.216 blocks/tick) but dPosFrame is per-render-frame (~0.072 at 60fps), so
# the floor read 0.04 instead of 0.30 and the fast stage's >0.12 gate never
# fired. Also un-gates the [FAST-JITTER] and [DIAG_FAST] logs (debugMode=0
# silenced them).

PATH = r"src/client/java/com/fsr2mod/fsr/FSRProcessor.java"

with open(PATH, "rb") as f:
    raw = f.read()

crlf = b"\r\n" in raw
buf = raw.decode("utf-8")
if crlf:
    buf = buf.replace("\r\n", "\n")
text = buf

changed = 0

def rep(old, new, must=True):
    global changed, text
    n = text.count(old)
    if n == 0:
        if must:
            raise SystemExit(f"ANCHOR NOT FOUND ({n}): {old[:100]!r}")
        return
    if n > 1:
        raise SystemExit(f"ANCHOR AMBIGUOUS ({n}): {old[:100]!r}")
    text = text.replace(old, new)
    changed += 1

S4 = "    "
S16 = S4 * 4
S20 = S4 * 5
S24 = S4 * 6
S28 = S4 * 7

# --- 1. New field for frame-time measurement (field area uses tab+4sp) -------
rep(
    "\t    private float fsr23CamReactive;\n",
    "\t    private float fsr23CamReactive;\n"
    "\t    private long fsr23LastMotionNanos;   // frame-time source for per-tick normalization\n",
)

# --- 2. Per-tick normalization block, right after prev-pos update -----------
rep(
    S16 + "fsr23PrevCamPosX = camPosNow.x; fsr23PrevCamPosY = camPosNow.y; fsr23PrevCamPosZ = camPosNow.z;\n"
    + S16 + "fsr23PrevCamPosInit = true;\n",
    S16 + "fsr23PrevCamPosX = camPosNow.x; fsr23PrevCamPosY = camPosNow.y; fsr23PrevCamPosZ = camPosNow.z;\n"
    + S16 + "fsr23PrevCamPosInit = true;\n"
    + S16 + "// PER-TICK NORMALIZATION (2026-08-07): dPosFrame is the PER-RENDER-FRAME\n"
    + S16 + "// camera delta (~0.072 blocks/frame at 60fps walking), but the camReactive\n"
    + S16 + "// floor and fast-jitter thresholds below were calibrated in PER-TICK space\n"
    + S16 + "// (walking 0.216 blocks/tick = 4.3 m/s; sprint 0.28). Feeding frame-space\n"
    + S16 + "// deltas into tick-space thresholds made the floor read 0.04 instead of\n"
    + S16 + "// 0.30 and the fast stage's >0.12 gate never fire at 60fps (log 2026-08-07:\n"
    + S16 + "// camReactive=0.000 on 100/130 samples during M=5-18px motion bursts; zero\n"
    + S16 + "// DIAG_FAST lines all session). Convert with the measured frame duration so\n"
    + S16 + "// the behavior is FPS-independent.\n"
    + S16 + "long fsr23NowNanos = System.nanoTime();\n"
    + S16 + "double fsr23DtSec = (fsr23LastMotionNanos == 0L) ? 1.0 / 60.0\n"
    + S20 + ": Math.min(0.25, Math.max(0.001, (fsr23NowNanos - fsr23LastMotionNanos) / 1e9));\n"
    + S16 + "fsr23LastMotionNanos = fsr23NowNanos;\n"
    + S16 + "double fps = (fsr23DtSec > 0.0) ? Math.min(240.0, Math.max(10.0, 1.0 / fsr23DtSec)) : 60.0;\n"
    + S16 + "double tickNorm = fps / 20.0; // frames per tick\n"
    + S16 + "double dPosTick = dPosFrame * tickNorm;\n",
)

# --- 3. camReactive floor: translation term uses per-tick value -------------
rep(
    S20 + "camReactiveVal = Math.min(0.9, Math.max(\n"
    + S24 + "(dPosFrame - 0.05) / 0.55,   // walking 0.3 -> 0.45; sprint 0.62 -> 0.9 (capped)\n"
    + S24 + "(dRotFrame - 0.04) / 0.05)); // extreme turns only (>=2.3 deg/frame); pans are depth-exact, MV ramp + fast-jitter cover them\n",
    S20 + "camReactiveVal = Math.min(0.9, Math.max(\n"
    + S24 + "(dPosTick - 0.05) / 0.55,   // per-TICK: walking 0.216 -> 0.30; sprint 0.28 -> 0.42; elytra 1.0 -> 0.9 cap\n"
    + S24 + "(dRotFrame - 0.04) / 0.05)); // extreme turns only (>=2.3 deg/frame); pans are depth-exact, MV ramp + fast-jitter cover them\n",
)

# --- 4. Fast-jitter stage: per-tick translation term, re-thresholded so
#        walking (0.216/tick) clearly engages ---------------------------------
rep(
    S20 + "fastFactor = (float) Math.min(1.0, Math.max(0.0,\n"
    + S24 + "Math.max((dPosFrame - 0.12) / 0.5, (dRotFrame - 0.01) / 0.03)));\n",
    S20 + "fastFactor = (float) Math.min(1.0, Math.max(0.0,\n"
    + S24 + "Math.max((dPosTick - 0.06) / 0.15, (dRotFrame - 0.01) / 0.03)));\n",
)

# --- 5. Un-gate the two fast-jitter diagnostics ------------------------------
rep(
    S20 + "if (fastFactor > 0.5f && config.debugMode > 0\n"
    + S28 + "&& timingFrameCount - lastFastMotionDiagFrame > 120) {\n",
    S20 + "if (fastFactor > 0.5f\n"
    + S28 + "&& timingFrameCount - lastFastMotionDiagFrame > 120) {\n",
)
rep(
    S16 + "if (config.debugMode > 0 && timingFrameCount % 60 == 0 && fastFactor > 0.01f) {\n",
    S16 + "if (timingFrameCount % 60 == 0 && fastFactor > 0.01f) {\n",
)

# --- 6. DIAG_FAST: log the per-tick value so the next session is verifiable --
rep(
    "\"[DIAG_FAST] Frame {}: fastFactor={} jitterAmpMotion={} dPosFrame={} dRotFrame={}\",\n",
    "\"[DIAG_FAST] Frame {}: fastFactor={} jitterAmpMotion={} dPosFrame={} dRotFrame={} dPosTick={}\",\n",
)
rep(
    S24 + "String.format(\"%.4f\", dPosFrame),\n"
    + S24 + "String.format(\"%.4f\", dRotFrame));\n",
    S24 + "String.format(\"%.4f\", dPosFrame),\n"
    + S24 + "String.format(\"%.4f\", dRotFrame),\n"
    + S24 + "String.format(\"%.4f\", dPosTick));\n",
)

out = text
if crlf:
    out = out.replace("\n", "\r\n")
with open(PATH, "wb") as f:
    f.write(out.encode("utf-8"))

print(f"OK: {changed} replacements applied")
