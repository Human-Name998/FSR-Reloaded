import os

def patch(path, replacements, latin1=False):
    with open(path, 'rb') as f:
        data = f.read()
    crlf = b'\r\n' in data
    enc = 'latin-1' if latin1 else 'utf-8'
    text = data.decode(enc)
    if crlf:
        text = text.replace('\r\n', '\n')
    for old, new in replacements:
        if old not in text:
            print('MISS in %s: %r' % (path, old[:70]))
            continue
        text = text.replace(old, new)
    if crlf:
        text = text.replace('\n', '\r\n')
    with open(path, 'wb') as f:
        f.write(text.encode(enc))
    print('PATCHED %s' % path)

ROOT = 'src/client/java/com/fsr2mod/fsr'
BASE = os.environ['APPDATA'] + '/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders'

# FSR3Pipeline: the getUniformLoc anchor uses 8 spaces (no tab)
patch(ROOT + '/FSR3Pipeline.java', [
    ("        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n",
     "        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n"
     "        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");\n"),
])

# fsr3 disk ACCUMULATE_CS.glsl (latin-1, CRLF)
patch(BASE + '/fsr3/ACCUMULATE_CS.glsl', [
    (" uniform int uCamStill;\n",
     " uniform int uCamStill;\n uniform float uCamReactive;\n"),
    ("     float reactiveFactor = max(dilatedReactive, temporalReactive);\n",
     "     float reactiveFactor = max(dilatedReactive, temporalReactive);\n"
     "     // Camera-motion reactive floor (2026-08-07): see FSR2.\n"
     "     reactiveFactor = max(reactiveFactor, uCamReactive);\n"),
], latin1=True)

# FSRProcessor.java: field, computation, and the 4 setCamReactive call sites
patch(ROOT + '/FSRProcessor.java', [
    ("\t    private boolean fsr23CamStill;\n",
     "\t    private boolean fsr23CamStill;\n\t    private float fsr23CamReactive;\n"),
    ("                fsr23CamStill = dRotFrame < 0.012 && dRot < 0.025;  // EMA backstop: a 0.004 rad/frame sustained pan (14°/s sky survey) would otherwise keep the lag at ~0.036 < 0.04 and smear clouds through the loose clamp\n",
     "                fsr23CamStill = dRotFrame < 0.012 && dRot < 0.025;  // EMA backstop: a 0.004 rad/frame sustained pan (14°/s sky survey) would otherwise keep the lag at ~0.036 < 0.04 and smear clouds through the loose clamp\n"
     "                // Camera-motion reactive floor (2026-08-07, \"fix the shaking\" fix): the\n"
     "                // reactive mask derives from constant-depth MVs (z~100 blocks) that\n"
     "                // UNDERESTIMATE near content's true screen motion -> walking only reaches\n"
     "                // reactive 0.04-0.15 (log frame 1824) while history is misaligned by up to\n"
     "                // ~10x the computed MV -> ~90% misaligned history stayed blended = smear +\n"
     "                // edge wobble (the shake), worst at speed. The CPU knows the camera is\n"
     "                // moving exactly (per-frame deltas). Floor the reactive mask on known\n"
     "                // motion so ACCUMULATE shifts the blend toward the de-jittered (stable)\n"
     "                // current frame and the lock stops cementing misaligned history. TRANSLATION\n"
     "                // only (rotation MVs are depth-independent/exact at constant depth; fast\n"
     "                // pans are already caught by the MV ramp). Bob is ~0.01-0.02 blocks and\n"
     "                // ~0.002 rad/frame -> thresholds start above that; 0.9 cap keeps a little\n"
     "                // history for stability even at sprint. Zero standing still = bit-identical\n"
     "                // to the pre-fix blend.\n"
     "                double camReactiveVal = 0.0;\n"
     "                if (fsr23PrevCamPosInit) {\n"
     "                    camReactiveVal = Math.min(0.9, Math.max(\n"
     "                        (dPosFrame - 0.05) / 0.55,   // walking 0.3 -> 0.45; sprint 0.62 -> 0.9 (capped)\n"
     "                        (dRotFrame - 0.02) / 0.04)); // fast pan 0.04 -> 0.5 (MV ramp covers the rest)\n"
     "                    camReactiveVal = Math.max(0.0, camReactiveVal);\n"
     "                }\n"
     "                fsr23CamReactive = (float) camReactiveVal;\n"),
    ("\t\t                fsr2Pipeline.setCamStill(fsr23CamStill);\n",
     "\t\t                fsr2Pipeline.setCamStill(fsr23CamStill);\n"
     "\t\t                fsr2Pipeline.setCamReactive(fsr23CamReactive);\n"),
    ("            fsr3Pipeline.setCamStill(fsr23CamStill);\n",
     "            fsr3Pipeline.setCamStill(fsr23CamStill);\n"
     "            fsr3Pipeline.setCamReactive(fsr23CamReactive);\n"),
    ("            fsr2Pipeline.setCamStill(fsr23CamStill);\n",
     "            fsr2Pipeline.setCamStill(fsr23CamStill);\n"
     "            fsr2Pipeline.setCamReactive(fsr23CamReactive);\n"),
])

print('DONE')
