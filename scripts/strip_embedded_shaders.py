#!/usr/bin/env python3
"""
Delete the embedded GLSL string constants from FSR2Pipeline/FSR3Pipeline and
switch the compile-time lookups to the single-source ShaderSourceManager
(classpath resource). Run AFTER scripts/single_source_shaders.py has written
the canonical .glsl files.

For each pipeline:
  1. Remove `private static final String X = QUOTE ... QUOTE;` blocks (and the
     FG_SHARED_H / FG_PACKING_H header constants + FG concat constants).
  2. Rewrite `ShaderSourceManager.get("fsrX", "Y", Y)` -> `get("fsrX", "Y")`.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PLAIN_CONSTS = {
    "fsr2": ["MV_GEN_CS", "RECONSTRUCT_CS", "DEPTH_CLIP_CS", "LOCK_CS", "ACCUMULATE_CS", "RCAS_CS"],
    "fsr3": ["MV_GEN_CS", "SHADING_CHANGE_CS", "RECONSTRUCT_CS", "DEPTH_CLIP_CS", "LOCK_CS",
             "ACCUMULATE_CS", "RCAS_CS", "FG_DENOISE_CS"],
}
CONCAT_CONSTS = ["FG_GAME_MV_FIELD_CS", "FG_DISOCCLUSION_MASK_CS", "FG_INTERPOLATION_CS"]
HEADER_CONSTS = ["FG_SHARED_H", "FG_PACKING_H"]

FILES = {
    "fsr2": ROOT / "src" / "client" / "java" / "com" / "fsr2mod" / "fsr" / "FSR2Pipeline.java",
    "fsr3": ROOT / "src" / "client" / "java" / "com" / "fsr2mod" / "fsr" / "FSR3Pipeline.java",
}

def remove_const(src, name):
    """Remove a shader constant declaration (plain or concat form). Returns (src, removed)."""
    # Concat form: NAME = FG_SHARED_H + FG_PACKING_H + """...""";
    concat = re.compile(
        r"^\s*private static final String " + re.escape(name) +
        r" = FG_SHARED_H \+ FG_PACKING_H \+ \"\"\"\n.*?\n\s*\"\"\";\n",
        re.S | re.M)
    plain = re.compile(
        r"^\s*private static final String " + re.escape(name) + r' = """\n.*?\n\s*""";\n',
        re.S | re.M)
    new = concat.sub("", src)
    if new != src:
        return new, True
    new2 = plain.sub("", new)
    return new2, new2 != new

def main():
    for group, path in FILES.items():
        src = path.read_text(encoding="utf-8")
        orig = src
        removed = []
        # Plain constants
        for name in PLAIN_CONSTS[group] + (CONCAT_CONSTS if group == "fsr3" else []) + (HEADER_CONSTS if group == "fsr3" else []):
            src, ok = remove_const(src, name)
            if ok:
                removed.append(name)
            else:
                print(f"WARN: {group}: constant {name} not found")
        # Update get() calls: get("fsrX", "NAME", NAME) -> get("fsrX", "NAME")
        src, n = re.subn(r'ShaderSourceManager\.get\("' + group + r'", "(\w+)", \1\)',
                         r'ShaderSourceManager.get("' + group + r'", "\1")', src)
        if n == 0:
            print(f"WARN: {group}: no get() calls rewritten")
        path.write_text(src, encoding="utf-8")
        print(f"{group}: removed constants {removed}; rewrote {n} get() calls; "
              f"file {len(orig.splitlines())} -> {len(src.splitlines())} lines")

if __name__ == "__main__":
    sys.exit(main())
