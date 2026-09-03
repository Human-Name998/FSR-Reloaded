#!/usr/bin/env python3
"""
Single-source shader extraction (2026-08-11, "optimise the overall architecture" A).

Extracts every FSR2/FSR3 embedded GLSL text-block constant from the pipeline
Java files into canonical .glsl files under src/main/resources/shaders/:

    shaders/fsr2/{MV_GEN_CS,RECONSTRUCT_CS,DEPTH_CLIP_CS,LOCK_CS,ACCUMULATE_CS,RCAS_CS}.glsl
    shaders/fsr3/{MV_GEN_CS,SHADING_CHANGE_CS,RECONSTRUCT_CS,DEPTH_CLIP_CS,LOCK_CS,
                  ACCUMULATE_CS,RCAS_CS,FG_GAME_MV_FIELD_CS,FG_DISOCCLUSION_MASK_CS,
                  FG_INTERPOLATION_CS,FG_DENOISE_CS}.glsl

The FG_*_CS constants are built in Java as `FG_SHARED_H + FG_PACKING_H + body`;
the canonical file contains the FULL concatenated source (header + body), which
matches the self-contained on-disk copies already bootstrapped in instances.

Extraction is byte-exact per JLS 3.10.6 text-block semantics (incidental
whitespace = min indentation across non-blank content lines and the closing
delimiter). Verified: RECONSTRUCT_CS / LOCK_CS / SHADING_CHANGE_CS /
FG_DENOISE_CS / RCAS_CS extracted this way equal the on-disk bootstrap copies
byte-for-byte.

Usage:
    python3 scripts/single_source_shaders.py          # write canonical files
    python3 scripts/single_source_shaders.py --check  # verify in-place (no writes)

After extraction, the Java pipelines load shaders via
ShaderSourceManager.get(group, name) from the classpath resource; the embedded
string constants are deleted (done manually in the pipeline files).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "shaders"

PIPELINES = [
    ("fsr2", ROOT / "src" / "client" / "java" / "com" / "fsr2mod" / "fsr" / "FSR2Pipeline.java"),
    ("fsr3", ROOT / "src" / "client" / "java" / "com" / "fsr2mod" / "fsr" / "FSR3Pipeline.java"),
]

# FG shaders whose Java constant is `FG_SHARED_H + FG_PACKING_H + """..."""`
FG_CONCAT = {
    "FG_GAME_MV_FIELD_CS",
    "FG_DISOCCLUSION_MASK_CS",
    "FG_INTERPOLATION_CS",
}


def extract_text_block(src, name):
    """Extract the effective string of `String NAME = \"\"\"...\"\"\";` per JLS 3.10.6."""
    m = re.search(r"String " + re.escape(name) + r' = """\n(.*?)\n(\s*)""";', src, re.S)
    if not m:
        return None
    body, closing_indent = m.group(1), m.group(2)
    lines = body.split("\n")
    if lines:
        lines[-1] = lines[-1].rstrip(" \t")
    indents = [len(ln) - len(ln.lstrip(" \t")) for ln in lines if ln.strip() != ""]
    indents.append(len(closing_indent))
    min_indent = min(indents)
    out = []
    for ln in lines:
        if ln.strip() != "":
            lead = len(ln) - len(ln.lstrip(" \t"))
            out.append(ln[min(lead, min_indent):])
        else:
            out.append("")
    if out and out[-1].strip() == "":
        out = out[:-1]
    return "\n".join(out)


def extract_concat(src, name):
    """Extract `FG_SHARED_H + FG_PACKING_H + \"\"\"...\"\"\";` → full concatenated source."""
    shared = extract_text_block(src, "FG_SHARED_H")
    packing = extract_text_block(src, "FG_PACKING_H")
    m = re.search(r"String " + re.escape(name) + r' = FG_SHARED_H \+ FG_PACKING_H \+ """\n(.*?)\n(\s*)""";', src, re.S)
    if shared is None or packing is None or not m:
        return None
    body, closing_indent = m.group(1), m.group(2)
    lines = body.split("\n")
    if lines:
        lines[-1] = lines[-1].rstrip(" \t")
    indents = [len(ln) - len(ln.lstrip(" \t")) for ln in lines if ln.strip() != ""]
    indents.append(len(closing_indent))
    min_indent = min(indents)
    out = []
    for ln in lines:
        if ln.strip() != "":
            lead = len(ln) - len(ln.lstrip(" \t"))
            out.append(ln[min(lead, min_indent):])
        else:
            out.append("")
    if out and out[-1].strip() == "":
        out = out[:-1]
    # The on-disk bootstrap copies (validated ground truth) separate the shared
    # header, the packing header and the body with a newline each; match them
    # byte-for-byte so the shader-cache hash stays stable.
    return shared + "\n" + packing + "\n" + "\n".join(out)


def main():
    check = "--check" in sys.argv
    files = []
    for group, jf in PIPELINES:
        src = jf.read_text(encoding="utf-8")
        names = re.findall(r"String (\w+_CS) = ", src)
        names = [n for n in names if n not in ("FG_SHARED_H", "FG_PACKING_H")]
        for name in sorted(set(names)):
            content = extract_concat(src, name) if name in FG_CONCAT else extract_text_block(src, name)
            if content is None:
                print(f"WARN: could not extract {group}/{name}")
                continue
            dest = OUT / group / (name + ".glsl")
            files.append((dest, content))
    for dest, content in files:
        if check:
            existing = dest.read_text(encoding="utf-8") if dest.exists() else None
            print(f"{'OK ' if existing == content else 'DIFF'} {dest.relative_to(ROOT)} ({len(content.splitlines())} lines)")
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content + "\n", encoding="utf-8")
            print(f"wrote {dest.relative_to(ROOT)} ({len(content.splitlines())} lines)")
    if check:
        missing = [d for d, _ in files if not d.exists()]
        if missing:
            print("MISSING:", missing)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
