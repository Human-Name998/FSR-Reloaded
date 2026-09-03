import os

def patch(path, replacements):
    with open(path, 'rb') as f:
        data = f.read()
    crlf = b'\r\n' in data
    text = data.decode('utf-8')
    if crlf:
        text = text.replace('\r\n', '\n')
    for old, new in replacements:
        if old not in text:
            print('MISS in %s: %r' % (path, old[:70]))
            continue
        text = text.replace(old, new, 1)
    if crlf:
        text = text.replace('\n', '\r\n')
    with open(path, 'wb') as f:
        f.write(text.encode('utf-8'))
    print('PATCHED %s' % path)

ROOT = 'src/client/java/com/fsr2mod/fsr'
BASE = os.environ['APPDATA'] + '/PrismLauncher/instances/26.1/minecraft/config/fsr-reloaded/shaders'

# ---------- FSR2Pipeline.java ----------
patch(ROOT + '/FSR2Pipeline.java', [
    ("\t        uniform int uCamStill;\n\t        const float FSR2_EPSILON",
     "\t        uniform int uCamStill;\n\t        uniform float uCamReactive;\n\t        const float FSR2_EPSILON"),
    ("\t            float reactiveFactor = max(dilatedReactive, 0.0);\n",
     "\t            float reactiveFactor = max(dilatedReactive, 0.0);\n"
     "\t            // Camera-motion reactive floor (2026-08-07, \"fix the shaking\" fix): constant-\n"
     "\t            // depth MVs underestimate near content's true screen motion, so the MV-derived\n"
     "\t            // reactive mask only reaches 0.04-0.15 while walking (log frame 1824) -> ~90%\n"
     "\t            // misaligned history stayed blended = smear + edge wobble. The CPU knows the\n"
     "\t            // camera is moving exactly; floor the mask so the blend shifts toward the\n"
     "\t            // de-jittered (stable) current frame. Zero standing still -> bit-identical path.\n"
     "\t            reactiveFactor = max(reactiveFactor, uCamReactive);\n"),
    ("    private boolean camStill;\n",
     "    private boolean camStill;\n    private float camReactive;\n"),
    ("    public void setCamStill(boolean camStill) {\n        this.camStill = camStill;\n    }\n",
     "    public void setCamStill(boolean camStill) {\n        this.camStill = camStill;\n    }\n\n"
     "    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }\n"),
    ("\t    private int ac_uCamStill;\n",
     "\t    private int ac_uCamStill;\n\t    private int ac_uCamReactive;\n"),
    ("\t        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n",
     "\t        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n"
     "\t        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");\n"),
    ("\t        GL43C.glUniform1i(ac_uCamStill, camStill ? 1 : 0);\n",
     "\t        GL43C.glUniform1i(ac_uCamStill, camStill ? 1 : 0);\n"
     "\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);\n"),
])

# ---------- FSR3Pipeline.java ----------
patch(ROOT + '/FSR3Pipeline.java', [
    ("\t        uniform int uCamStill;\n\n\t        const float FSR3_EPSILON",
     "\t        uniform int uCamStill;\n\t        uniform float uCamReactive;\n\n\t        const float FSR3_EPSILON"),
    ("\t            float reactiveFactor = max(dilatedReactive, temporalReactive);\n",
     "\t            float reactiveFactor = max(dilatedReactive, temporalReactive);\n"
     "\t            // Camera-motion reactive floor (2026-08-07, \"fix the shaking\" fix): see FSR2.\n"
     "\t            reactiveFactor = max(reactiveFactor, uCamReactive);\n"),
    ("    private boolean camStill;\n",
     "    private boolean camStill;\n    private float camReactive;\n"),
    ("    public void setCamStill(boolean camStill) {\n        this.camStill = camStill;\n    }\n",
     "    public void setCamStill(boolean camStill) {\n        this.camStill = camStill;\n    }\n\n"
     "    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }\n"),
    ("\t    private int ac_uCamStill;\n",
     "\t    private int ac_uCamStill;\n\t    private int ac_uCamReactive;\n"),
    ("\t        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n",
     "\t        ac_uCamStill = getUniformLoc(accumulateProg, \"uCamStill\");\n"
     "\t        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");\n"),
    ("\t        GL43C.glUniform1i(ac_uCamStill, camStill ? 1 : 0);\n",
     "\t        GL43C.glUniform1i(ac_uCamStill, camStill ? 1 : 0);\n"
     "\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);\n"),
])

# ---------- fsr2 disk ACCUMULATE_CS.glsl ----------
patch(BASE + '/fsr2/ACCUMULATE_CS.glsl', [
    ("\t        uniform int uCamStill;\n",
     "\t        uniform int uCamStill;\n\t        uniform float uCamReactive;\n"),
    ("\t            float reactiveFactor = max(dilatedReactive, 0.0);\n",
     "\t            float reactiveFactor = max(dilatedReactive, 0.0);\n"
     "\t            // Camera-motion reactive floor (2026-08-07): constant-depth MVs underestimate\n"
     "\t            // near content -> MV-derived reactive under-fires during motion (0.04-0.15\n"
     "\t            // walking) -> misaligned history dominated the blend = smear + shake. Floor\n"
     "\t            // on CPU camera motion; zero standing still = identical path.\n"
     "\t            reactiveFactor = max(reactiveFactor, uCamReactive);\n"),
])

# ---------- fsr3 disk ACCUMULATE_CS.glsl ----------
patch(BASE + '/fsr3/ACCUMULATE_CS.glsl', [
    (" uniform int uCamStill;\n",
     " uniform int uCamStill;\n uniform float uCamReactive;\n"),
    ("     float reactiveFactor = max(dilatedReactive, temporalReactive);\n",
     "     float reactiveFactor = max(dilatedReactive, temporalReactive);\n"
     "     // Camera-motion reactive floor (2026-08-07): see FSR2.\n"
     "     reactiveFactor = max(reactiveFactor, uCamReactive);\n"),
])

# ---------- FSRProcessor.java ----------
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
