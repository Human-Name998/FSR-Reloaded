#!/usr/bin/env python3
"""Quantize FSRCNN-x2 to INT8 for NPU inference."""
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(SCRIPT_DIR, "..", "third_party", "onnxruntime", "models")
INPUT_PATH = os.path.join(MODEL_DIR, "FSRCNN-x2.onnx")
OUTPUT_PATH = os.path.join(MODEL_DIR, "fsrcnn-x2-int8.onnx")

if __name__ == "__main__":
    if not os.path.exists(INPUT_PATH):
        print(f"ERROR: {INPUT_PATH} not found. Run download_fsrcnn.py first.")
        exit(1)
    
    print(f"Quantizing {INPUT_PATH} to INT8...")
    quantize_dynamic(INPUT_PATH, OUTPUT_PATH, weight_type=QuantType.QInt8)
    size = os.path.getsize(OUTPUT_PATH)
    print(f"Saved INT8 model to {OUTPUT_PATH} ({size} bytes)")
