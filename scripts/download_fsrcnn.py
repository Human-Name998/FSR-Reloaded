#!/usr/bin/env python3
"""Download or generate FSRCNN-x2 ONNX model for NPU upscaling.

The original ONNX Model Zoo URL is no longer available (repo archived/deprecated).
This script falls back to generating the model programmatically using the FSRCNN
architecture definition in generate_fsrcnn.py.

Usage:
    python scripts/download_fsrcnn.py
"""
import os
import sys
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
GENERATE_SCRIPT = os.path.join(SCRIPT_DIR, "generate_fsrcnn.py")

if __name__ == "__main__":
    if not os.path.exists(GENERATE_SCRIPT):
        print(f"ERROR: {GENERATE_SCRIPT} not found")
        sys.exit(1)
    
    print("ONNX Model Zoo URL is no longer available.")
    print("Generating FSRCNN-x2 model programmatically...")
    
    result = subprocess.run([sys.executable, GENERATE_SCRIPT], 
                          cwd=SCRIPT_DIR, capture_output=True, text=True)
    print(result.stdout)
    if result.returncode != 0:
        print(f"ERROR: Generation failed: {result.stderr}")
        sys.exit(1)
    
    # Verify output exists
    OUT_DIR = os.path.join(SCRIPT_DIR, "..", "third_party", "onnxruntime", "models")
    out_path = os.path.join(OUT_DIR, "FSRCNN-x2.onnx")
    if os.path.exists(out_path):
        print(f"Model generated successfully: {out_path} ({os.path.getsize(out_path)} bytes)")
    else:
        print(f"ERROR: Output not found at {out_path}")
        sys.exit(1)
