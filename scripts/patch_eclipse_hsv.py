#!/usr/bin/env python3
"""Patch Eclipse-Shader-Unstable for low-preset (LPV-off) compile errors (2026-08-25).

Pack bug class: shaders/dimensions/all_translucent.fsh and composite1.fsh
include lib/hsv.glsl and lib/lpv_common.glsl ONLY inside `#ifdef IS_LPV_ENABLED`
(resp. the LPV/Photonics guard), but both include lib/specular.glsl
UNCONDITIONALLY, and specular.glsl calls RgbToHsv() (hsv.glsl) and
GetLpvPosition() (lpv_common.glsl) from unguarded functions. With LPV off
(POTATO/VERY_LOW/LOW/MEDIUM/ALTERNATE_MEDIUM presets) those functions are
undeclared -> the terrain_translucent (water) program fails -> Iris disables
ALL shaders ("Failed to create shader rendering pipeline, disabling shaders!").

Fix mirrors all_solid.fsh, which already includes both libs unconditionally:
strip the hsv/lpv_common includes from the guarded blocks and re-add them
unconditionally IMMEDIATELY BEFORE the specular.glsl include. Placing them
right before specular (rather than at the top) matters: glslang rejects
forward references, and lpv_common.glsl's GetLpvPosition() uses
`cameraPosition`, which is only declared by lib/projections.glsl and
`relativeEyePosition` (declared ~line 212/227) -- both come before the
specular include in each file. v3 put the includes at the top of the file,
which made composite1 fail with 'cameraPosition' : undeclared identifier
(line ~88 of the JCPP-merged source). v4 also unguards composite1's manual
`uniform vec3 relativeEyePosition;` declaration (Iris provides the uniform in
all programs; all_solid/all_translucent already declare it unconditionally)
and keeps lpv_render.glsl (needs LPV-only textures) guarded.

Idempotent: strips every hsv/lpv_common include and every patch comment first,
then inserts exactly one unconditional copy before the specular include.

Usage: python3 patch_eclipse_hsv.py <pack-root-or-file>
"""

import os
import shutil
import sys
import tempfile
import zipfile

PATCH_COMMENT = ("// [FSR Reloaded patch 2026-08-25] hsv.glsl + lpv_common.glsl "
                 "must be unconditional: specular.glsl calls RgbToHsv/GetLpvPosition with LPV off\n")

FILES = {
    "shaders/dimensions/all_translucent.fsh": {
        "anchor": '#include "/lib/specular.glsl"\n',
        "unguard_uniforms": None,
    },
    "shaders/dimensions/composite1.fsh": {
        "anchor": '#include "/lib/specular.glsl"\n',
        "unguard_uniforms": (
            '#if defined IS_LPV_ENABLED || defined PHOTONICS && defined PHOTONICS && !defined PH_ENABLE_HANDHELD_LIGHT\n'
            '\tuniform vec3 relativeEyePosition;\n\n',
            'uniform vec3 relativeEyePosition;\n\n'
            '#if defined IS_LPV_ENABLED || defined PHOTONICS && defined PHOTONICS && !defined PH_ENABLE_HANDHELD_LIGHT\n',
        ),
    },
}


def patch_file(path: str) -> None:
    with open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    rel = path.replace("\\", "/").split("/shaders/", 1)[-1]
    rel = "shaders/" + rel if not rel.startswith("shaders/") else rel
    spec = FILES.get(rel)
    if spec is None:
        raise SystemExit(f"{path}: not a patch target")
    # 1. Strip every hsv / lpv_common include AND patch comments (v3 or v4 layout).
    for inc in ('#include "/lib/hsv.glsl"\n', '#include "/lib/lpv_common.glsl"\n'):
        text = text.replace("\t" + inc, "")
        text = text.replace(inc, "")
    text = text.replace(PATCH_COMMENT, "")
    # 2. Unguard composite1's manual relativeEyePosition declaration.
    if spec["unguard_uniforms"]:
        old, new = spec["unguard_uniforms"]
        if old in text:
            text = text.replace(old, new, 1)
    # 3. Insert the unconditional includes immediately BEFORE the specular include.
    anchor = spec["anchor"]
    assert anchor in text, f"{path}: specular anchor not found"
    text = text.replace(
        anchor,
        PATCH_COMMENT + '\n#include "/lib/hsv.glsl"\n#include "/lib/lpv_common.glsl"\n' + anchor,
        1,
    )
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print(f"patched {path}")


def verify_file(path: str) -> None:
    with open(path, "r", encoding="utf-8", newline="") as f:
        lines = f.read().splitlines()
    spec_anchor = '#include "/lib/specular.glsl"'
    spec_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and spec_anchor in l]
    assert len(spec_idx) == 1, f"{path}: expected one specular include, got {spec_idx}"
    # both includes must be the two lines immediately before specular (cameraPosition decl comes earlier)
    hsv_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and '#include "/lib/hsv.glsl"' in l]
    lpv_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and '#include "/lib/lpv_common.glsl"' in l]
    assert len(hsv_idx) == 1 and len(lpv_idx) == 1, f"{path}: expected exactly one hsv+lpv, got hsv={hsv_idx} lpv={lpv_idx}"
    assert hsv_idx[0] == spec_idx[0] - 2 and lpv_idx[0] == spec_idx[0] - 1, \
        f"{path}: hsv@{hsv_idx[0]+1} lpv@{lpv_idx[0]+1} must be the 2 lines before specular@{spec_idx[0]+1}"
    print(f"verified {path}: hsv@{hsv_idx[0]+1} lpv@{lpv_idx[0]+1} specular@{spec_idx[0]+1}")


def patch_dir(root: str) -> None:
    for rel in FILES:
        p = os.path.join(root, rel)
        if not os.path.exists(p):
            raise SystemExit(f"{p}: not found under {root}")
        patch_file(p)
        verify_file(p)


def find_pack_root(work: str) -> str:
    """extractall creates <work>/<TopLevelDir>/... ; return the dir with shaders/."""
    for name in sorted(os.listdir(work)):
        cand = os.path.join(work, name)
        if os.path.isdir(cand) and os.path.isdir(os.path.join(cand, "shaders")):
            return cand
    return work


def patch_zip(zip_path: str) -> None:
    work = tempfile.mkdtemp(prefix="eclipse-hsv-")
    try:
        with zipfile.ZipFile(zip_path) as z:
            z.extractall(work)
        root = find_pack_root(work)
        patch_dir(root)
        bak = zip_path + ".bak-20260825-hsvfix"
        if not os.path.exists(bak):
            shutil.copy2(zip_path, bak)
            print(f"backup -> {bak}")
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
            for r, _, files in os.walk(work):
                for name in files:
                    full = os.path.join(r, name)
                    z.write(full, os.path.relpath(full, work))
        print(f"rewrote {zip_path}")
        with zipfile.ZipFile(zip_path) as z:
            for rel in FILES:
                entry = "Eclipse-Shader-Unstable/" + rel
                txt = z.read(entry).decode("utf-8")
                lines = txt.splitlines()
                spec_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and '#include "/lib/specular.glsl"' in l]
                assert len(spec_idx) == 1, f"{entry}: specular include missing: {spec_idx}"
                hsv_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and '#include "/lib/hsv.glsl"' in l]
                lpv_idx = [i for i, l in enumerate(lines) if l.strip().startswith("#include") and '#include "/lib/lpv_common.glsl"' in l]
                assert len(hsv_idx) == 1 and len(lpv_idx) == 1, f"{entry}: hsv={hsv_idx} lpv={lpv_idx}"
                assert hsv_idx[0] == spec_idx[0] - 2 and lpv_idx[0] == spec_idx[0] - 1, \
                    f"{entry}: hsv@{hsv_idx[0]+1} lpv@{lpv_idx[0]+1} must be the 2 lines before specular@{spec_idx[0]+1}"
            print("zip re-verified: unconditional includes sit right before specular.glsl in both entries")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    target = sys.argv[1]
    if os.path.isdir(target):
        patch_dir(target)
    elif target.lower().endswith(".zip"):
        patch_zip(target)
    else:
        raise SystemExit("usage: patch_eclipse_hsv.py <pack-dir|pack.zip>")
