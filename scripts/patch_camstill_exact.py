#!/usr/bin/env python3
# Patch: uCamStillExact — force exact reprojection (mv=0, reactiveFactor=0) for ALL
# pixels when the camera is genuinely still, fixing the standing-still shake.
# Root cause: idle bob is real translation; constant-depth MVs (z~100) underestimate
# its near-content parallax (0.5-4px), so history misaligns, box clamp rejects, and
# the jittered current frame shows through. uCamStill alone can't do this for terrain
# because its translation gate was dropped (sky-only reasoning). All files read/written
# as latin-1 (byte-preserving) so UTF-8 comments pass through untouched.

import io

FILES = {
    "src/client/java/com/fsr2mod/fsr/FSR2Pipeline.java": [
        # uniform decl
        ("\t        uniform float uCamReactive;",
         "\t        uniform float uCamReactive;\n\t        uniform int uCamStillExact;", 1),
        # mv override
        ("      if (isSky && uCamStill == 1) mv = vec2(0.0);",
         "      if (uCamStillExact == 1 || (isSky && uCamStill == 1)) mv = vec2(0.0);", 1),
        # reactiveFactor override
        ("      if (isSky && uCamStill == 1) reactiveFactor = 0.0;",
         "      if (uCamStillExact == 1 || (isSky && uCamStill == 1)) reactiveFactor = 0.0;", 1),
        # Java: field
        ("\t    private int ac_uCamReactive;",
         "\t    private int ac_uCamReactive;\n\t    private int ac_uCamStillExact;", 1),
        # Java: uniform loc
        ("\t        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");",
         "\t        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");\n\t        ac_uCamStillExact = getUniformLoc(accumulateProg, \"uCamStillExact\");", 1),
        # Java: setter field
        ("    private float camReactive;",
         "    private float camReactive;\n    private boolean camStillExact;", 1),
        # Java: setter method
        ("    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }",
         "    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }\n\n    public void setCamStillExact(boolean camStillExact) {\n        this.camStillExact = camStillExact;\n    }", 1),
        # Java: upload
        ("\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);",
         "\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);\n\t        GL43C.glUniform1i(ac_uCamStillExact, camStillExact ? 1 : 0);", 1),
    ],
    "src/client/java/com/fsr2mod/fsr/FSR3Pipeline.java": [
        ("\t        uniform float uCamReactive;",
         "\t        uniform float uCamReactive;\n\t        uniform int uCamStillExact;", 1),
        ("      if (isSky && uCamStill == 1) mv = vec2(0.0);",
         "      if (uCamStillExact == 1 || (isSky && uCamStill == 1)) mv = vec2(0.0);", 1),
        ("      if (isSky && uCamStill == 1) reactiveFactor = 0.0;",
         "      if (uCamStillExact == 1 || (isSky && uCamStill == 1)) reactiveFactor = 0.0;", 1),
        ("\t    private int ac_uCamReactive;",
         "\t    private int ac_uCamReactive;\n\t    private int ac_uCamStillExact;", 1),
        # FSR3 loc lines use 8 spaces (no tab)
        ("        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");",
         "        ac_uCamReactive = getUniformLoc(accumulateProg, \"uCamReactive\");\n        ac_uCamStillExact = getUniformLoc(accumulateProg, \"uCamStillExact\");", 1),
        ("    private float camReactive;",
         "    private float camReactive;\n    private boolean camStillExact;", 1),
        ("    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }",
         "    public void setCamReactive(float camReactive) {\n        this.camReactive = camReactive;\n    }\n\n    public void setCamStillExact(boolean camStillExact) {\n        this.camStillExact = camStillExact;\n    }", 1),
        ("\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);",
         "\t        GL43C.glUniform1f(ac_uCamReactive, camReactive);\n\t        GL43C.glUniform1i(ac_uCamStillExact, camStillExact ? 1 : 0);", 1),
    ],
}

FSRP = "src/client/java/com/fsr2mod/fsr/FSRProcessor.java"
FSRP_REPL = [
    # field
    ("\t    private boolean fsr23CamStill;",
     "\t    private boolean fsr23CamStill;\n\t    private boolean fsr23CamStillExact;", 1),
    # computation after the uCamStill line (before its trailing comment stays intact)
    ("                fsr23CamStill = dRotFrame < 0.012 && dRot < 0.025;",
     "                fsr23CamStill = dRotFrame < 0.012 && dRot < 0.025;\n"
     "                // uCamStillExact (2026-08-07, \"shaking only when standing still\" fix):\n"
     "                // uCamStill dropped its TRANSLATION gate (sky-only reasoning - sky has\n"
     "                // zero parallax, translation doesn't move it). Terrain is different: the\n"
     "                // idle bob is real translation whose near-content parallax (0.5-4px at\n"
     "                // z<10) the constant-depth (z~100) MVs underestimate (~0.05px), so the\n"
     "                // history read oscillates with the bob, the box clamp rejects it, and the\n"
     "                // jittered current frame shows through = the standing-still shake. When\n"
     "                // the camera is GENUINELY still (per-tick translation < 0.08: bob 0.03-0.06,\n"
     "                // crouch-walk 0.108, walk 0.216; per-frame rotation < 0.006 rad: bob\n"
     "                // 0.002-0.005; EMA rotation backstop < 0.02 for slow sustained pans) force\n"
     "                // mv=0 for ALL pixels -> exact history alignment, full temporal accumulation.\n"
     "                fsr23CamStillExact = dPosTick < 0.08 && dRotFrame < 0.006 && dRot < 0.02;", 1),
    # dispatch site 1 (tab+tab+16sp)
    ("\t\t                fsr2Pipeline.setCamReactive(fsr23CamReactive);",
     "\t\t                fsr2Pipeline.setCamReactive(fsr23CamReactive);\n\t\t                fsr2Pipeline.setCamStillExact(fsr23CamStillExact);", 1),
    # dispatch site 3 (12sp)
    ("            fsr2Pipeline.setCamReactive(fsr23CamReactive);",
     "            fsr2Pipeline.setCamReactive(fsr23CamReactive);\n            fsr2Pipeline.setCamStillExact(fsr23CamStillExact);", 1),
    # dispatch sites 2 + 4 (12sp, identical -> count 2)
    ("            fsr3Pipeline.setCamReactive(fsr23CamReactive);",
     "            fsr3Pipeline.setCamReactive(fsr23CamReactive);\n            fsr3Pipeline.setCamStillExact(fsr23CamStillExact);", 2),
    # DIAG format suffix (both FSR2 + FSR3)
    ("hasPrev=%s camReactive=%.3f\"",
     "hasPrev=%s camReactive=%.3f camStillExact=%d\"", 2),
    # DIAG args (both)
    ("hasPrevFrame, fsr23CamReactive,",
     "hasPrevFrame, fsr23CamReactive, (fsr23CamStillExact ? 1 : 0),", 2),
]

import os

def patch(path, repls):
    with io.open(path, "r", encoding="latin-1") as f:
        text = f.read()
    for old, new, count in repls:
        n = text.count(old)
        if n < count:
            raise SystemExit("FAIL %s: found %d of %r (need %d)" % (path, n, old[:60], count))
        text = text.replace(old, new, count)
        print("OK   %-58s x%d  %r" % (path.split("/")[-1], count, old[:55]))
    with io.open(path, "w", encoding="latin-1") as f:
        f.write(text)

for path, repls in FILES.items():
    patch(path, repls)
patch(FSRP, FSRP_REPL)
print("ALL PATCHES APPLIED")
