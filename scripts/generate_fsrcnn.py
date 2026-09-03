#!/usr/bin/env python3
"""Generate a minimal FSRCNN-x2 ONNX model for NPU POC testing.

FSRCNN architecture (simplified):
  Input (1, 4, H, W)
  → Conv(4→56, 5) + PReLU → feature extraction
  → Conv(56→12, 1) + PReLU → shrinking
  → Conv(12→12, 3) + PReLU ×4 → mapping
  → Conv(12→56, 1) + PReLU → expanding
  → ConvTranspose(56→4, 9, stride=2) → 2x upscale
  Output (1, 4, H*2, W*2)
"""

import onnx
from onnx import helper, TensorProto, numpy_helper
import numpy as np
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(SCRIPT_DIR, "..", "third_party", "onnxruntime", "models")
OUT_PATH = os.path.join(OUT_DIR, "FSRCNN-x2.onnx")

np.random.seed(42)

# === Build the FSRCNN graph ===
# We chain nodes by naming outputs and feeding them as inputs to the next node.

nodes = []
initializers = []

# --- Feature extraction: Conv(4→56, 5) + PReLU ---
nodes.append(helper.make_node("Conv",
    ["input", "feat_w", "feat_b"], ["feat_conv"],
    name="feat_conv", kernel_shape=[5, 5], pads=[2, 2, 2, 2], strides=[1, 1]))
nodes.append(helper.make_node("PRelu",
    ["feat_conv", "feat_alpha"], ["feat"],
    name="feat_prelu"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56, 4, 5, 5).astype(np.float32) * 0.1, name="feat_w"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56).astype(np.float32) * 0.1, name="feat_b"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56).astype(np.float32) * 0.01, name="feat_alpha"))

# --- Shrinking: Conv(56→12, 1) + PReLU ---
nodes.append(helper.make_node("Conv",
    ["feat", "shrink_w", "shrink_b"], ["shrink_conv"],
    name="shrink_conv", kernel_shape=[1, 1], pads=[0, 0, 0, 0], strides=[1, 1]))
nodes.append(helper.make_node("PRelu",
    ["shrink_conv", "shrink_alpha"], ["shrink"],
    name="shrink_prelu"))
initializers.append(numpy_helper.from_array(
    np.random.randn(12, 56, 1, 1).astype(np.float32) * 0.1, name="shrink_w"))
initializers.append(numpy_helper.from_array(
    np.random.randn(12).astype(np.float32) * 0.1, name="shrink_b"))
initializers.append(numpy_helper.from_array(
    np.random.randn(12).astype(np.float32) * 0.01, name="shrink_alpha"))

# --- Mapping: Conv(12→12, 3) + PReLU ×4 ---
prev = "shrink"
for i in range(4):
    name = f"map{i+1}"
    nodes.append(helper.make_node("Conv",
        [prev, f"{name}_w", f"{name}_b"], [f"{name}_conv"],
        name=f"{name}_conv", kernel_shape=[3, 3], pads=[1, 1, 1, 1], strides=[1, 1]))
    nodes.append(helper.make_node("PRelu",
        [f"{name}_conv", f"{name}_alpha"], [name],
        name=f"{name}_prelu"))
    initializers.append(numpy_helper.from_array(
        np.random.randn(12, 12, 3, 3).astype(np.float32) * 0.1, name=f"{name}_w"))
    initializers.append(numpy_helper.from_array(
        np.random.randn(12).astype(np.float32) * 0.1, name=f"{name}_b"))
    initializers.append(numpy_helper.from_array(
        np.random.randn(12).astype(np.float32) * 0.01, name=f"{name}_alpha"))
    prev = name

# --- Expanding: Conv(12→56, 1) + PReLU ---
nodes.append(helper.make_node("Conv",
    [prev, "expand_w", "expand_b"], ["expand_conv"],
    name="expand_conv", kernel_shape=[1, 1], pads=[0, 0, 0, 0], strides=[1, 1]))
nodes.append(helper.make_node("PRelu",
    ["expand_conv", "expand_alpha"], ["expand"],
    name="expand_prelu"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56, 12, 1, 1).astype(np.float32) * 0.1, name="expand_w"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56).astype(np.float32) * 0.1, name="expand_b"))
initializers.append(numpy_helper.from_array(
    np.random.randn(56).astype(np.float32) * 0.01, name="expand_alpha"))

# --- Deconv(56→4, 9, stride=2) — 2x upscale ---
nodes.append(helper.make_node("ConvTranspose",
    ["expand", "deconv_w", "deconv_b"], ["output"],
    name="deconv", kernel_shape=[9, 9], pads=[4, 4, 4, 4], strides=[2, 2]))
initializers.append(numpy_helper.from_array(
    np.random.randn(4, 56, 9, 9).astype(np.float32) * 0.1, name="deconv_w"))
initializers.append(numpy_helper.from_array(
    np.random.randn(4).astype(np.float32) * 0.1, name="deconv_b"))

# === Build graph proto ===
graph_def = helper.make_graph(
    nodes=nodes,
    name="FSRCNN-x2",
    inputs=[
        helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 4, -1, -1]),
    ],
    outputs=[
        helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 4, -1, -1]),
    ],
    initializer=initializers,
)

# Set opset
opset = helper.make_operatorsetid("", 11)
model_def = helper.make_model(graph_def, opset_imports=[opset])
model_def.ir_version = onnx.IR_VERSION_2023_5_5  # IRv9

# Validate
onnx.checker.check_model(model_def)
print(f"Model opset: {model_def.opset_import[0].version}")
print(f"IR version: {model_def.ir_version}")

# Save
os.makedirs(OUT_DIR, exist_ok=True)
onnx.save(model_def, OUT_PATH)
size = os.path.getsize(OUT_PATH)
print(f"Saved FSRCNN-x2 model to {OUT_PATH}")
print(f"  Size: {size} bytes")
print(f"  Input:  (1, 4, H, W)")
print(f"  Output: (1, 4, H*2, W*2)")
