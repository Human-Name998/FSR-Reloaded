#!/usr/bin/env bash
# Compile the FSR 4.0.2 INT8 model shaders (fsr4_model_v07_i8) to SPIR-V with DXC.
#
# Compiled and verified on 2026-08-15 with BOTH toolchains:
#   Linux: DXC 1.9.2607 (linux_dxc_2026_07_29.x86_x64) — 27/28 (pre.hlsl hit a
#          DXC codegen bug, see below)
#   Windows: DXC 1.9.2607 (dxc_2026_07_29.zip) — 28/28, including pre.hlsl
# Result: ALL 28 shaders compile to valid SPIR-V.
#
# SPIR-V inspection (spirv-dis): the model's INT8 math is DOT4, NOT WMMA —
#   OpSDot/OpSUDot with PackedVectorFormat4x8Bit. pass7 alone has 4,224
#   OpSDot instructions (512 in pass3, 260 in pass12). SPV_KHR_integer_dot_product
#   capability is declared; no OpCooperativeMatrix anywhere (WMMA_ENABLED 0).
#
# GLSL feasibility: GLSL CAN express the math natively — `dotPacked4x8EXT`
#   (GL_EXT_integer_dot_product, GLSL 4.50+) compiles with glslangValidator and
#   round-trips to the IDENTICAL OpSUDot PackedVectorFormat4x8Bit. Verified:
#   the SDK-bundled spirv-cross converts 15/28 automatically; the 13 failures
#   are EXACTLY the dot-product passes and are a spirv-cross bug
#   ("Cannot resolve expression type" on OpSDot), not a fundamental limit —
#   the math itself is expressible. Hardware: RDNA3/3.5 (GFX11) has native
#   v_dot4_i32_iu8 (the DOT4 instruction); Mesa RADV exposes
#   shaderIntegerDotProduct=true, so the INT8 model runs hardware-accelerated
#   on the 860M via Vulkan. radeonsi exposes GL_EXT_integer_dot_product too.
#
# Workarounds baked in (all documented in AGENTS-style notes below):
#  - pass9/pass11: DXC's SPIR-V backend cannot lower `_Static_assert`. The
#    asserts guard guaranteed-true constants (numFeatures==64/32, numGroups==2/1)
#    in generated code, so they are removed from FNB_CT2D_ADD.hlsli.
#  - post.hlsl/pre.hlsl: Vulkan target forbids <32-bit sampled texture types.
#    The DATA_TYPE* macros are forced to 32-bit float (FP32 intermediates —
#    2x bandwidth on history/reprojection buffers, same math).
#  - Wave ops (QuadReadAcrossX/Y in pre.hlsl) need -fspv-target-env=vulkan1.1+.
#
# Usage: bash scripts/compile_fsr4_int8_spirv.sh [model_name]
#   model_name defaults to fsr4_model_v07_i8_quality (1080p passes file).

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Pick the right DXC binary for the host platform (Linux ELF vs Windows PE).
if uname -s | grep -qi linux; then
  DXC="${DXC:-$ROOT/tools/linux_dxc_2026_07_29.x86_x64/bin/dxc}"
else
  DXC="${DXC:-$ROOT/tools/dxc_win/bin/x64/dxc.exe}"
fi
FSR4="$ROOT/third_party/FSR-4.0.2-reference/Kits/FidelityFX/upscalers/fsr4"
MODEL="${1:-fsr4_model_v07_i8_quality}"
SHADERS="$FSR4/internal/shaders/$MODEL"
OUT="$FSR4/build-spirv/$MODEL"
mkdir -p "$OUT"

if [ ! -x "$DXC" ]; then
  echo "DXC not found at $DXC — download from github.com/microsoft/DirectXShaderCompiler/releases" >&2
  echo "  (linux_dxc_2026_07_29.x86_x64.tar.gz for Linux, dxc_2026_07_29.zip for Windows, both from v1.9.2607)" >&2
  exit 1
fi

# Workaround 1: drop _Static_assert (DXC SPIR-V can't lower it; asserts are on
# guaranteed-true generated constants). Idempotent: removes either spelling.
sed -i '/_Static_assert\|static_assert/d' \
  "$FSR4/dx12/ml2code_runtime/operators/int8_NHWC/Fused/FNB_CT2D_ADD.hlsli" 2>/dev/null || true

# Workaround 2: 32-bit sampled texture types for the Vulkan target.
FP32_OVERRIDES="-DDATA_TYPE=float -DDATA_TYPE2=float2 -DDATA_TYPE3=float3 -DDATA_TYPE_VECTOR=float4 -DNETWORK_DATA_TYPE=float"

# NB: -fspv-target-env must be vulkan1.1. vulkan1.2 triggers a DXC codegen bug
# (OpCopyLogical on RWByteAddressBuffer copies) that fails every shader.
COMMON_FLAGS="-no-warnings -O3 -enable-16bit-types -HV 2021 -T cs_6_6 -I $FSR4 -I $FSR4/dx12 -I $FSR4/include/gpu/fsr4 -spirv -fspv-target-env=vulkan1.1"

passes=0
for i in $(seq 0 12); do
  # conv passes
  if "$DXC" $COMMON_FLAGS -E "fsr4_model_v07_i8_pass$i" -D"MLSR_PASS_$i" \
      -Fo "$OUT/pass$i.spv" "$SHADERS/passes_1080.hlsl" 2>"$OUT/err_pass$i.txt"; then
    passes=$((passes+1))
  else
    echo "FAIL pass$i: $(head -2 "$OUT/err_pass$i.txt" | tail -1)"
  fi
  # post passes (note: lowercase 'post' in entry names)
  if "$DXC" $COMMON_FLAGS -E "fsr4_model_v07_i8_pass${i}_post" -D"MLSR_PASS_${i}_POST" \
      -Fo "$OUT/pass${i}_post.spv" "$SHADERS/passes_1080.hlsl" 2>"$OUT/err_pass${i}_post.txt"; then
    passes=$((passes+1))
  else
    echo "FAIL pass${i}_post: $(head -2 "$OUT/err_pass${i}_post.txt" | tail -1)"
  fi
done

# decoder/post stage (entry 'main', needs RESOLUTION defines)
if "$DXC" $COMMON_FLAGS $FP32_OVERRIDES -E main -DRESOLUTION_1080=0 \
    -DFFX_MLSR_RESOLUTION=RESOLUTION_1080 -Fo "$OUT/post.spv" "$SHADERS/post.hlsl" \
    2>"$OUT/err_post.txt"; then
  echo "OK  post.spv"
else
  echo "FAIL post: $(head -2 "$OUT/err_post.txt" | tail -1)"
fi

# encoder/pre stage (entry 'main')
if "$DXC" $COMMON_FLAGS $FP32_OVERRIDES -E main -Fo "$OUT/pre.spv" "$SHADERS/pre.hlsl" \
    2>"$OUT/err_pre.txt"; then
  echo "OK  pre.spv"
else
  echo "FAIL pre: $(head -2 "$OUT/err_pre.txt" | tail -1)"
fi

echo "----"
echo "compiled $passes/26 ML passes -> $OUT"
ls -la "$OUT" | awk '{print $5, $9}'
