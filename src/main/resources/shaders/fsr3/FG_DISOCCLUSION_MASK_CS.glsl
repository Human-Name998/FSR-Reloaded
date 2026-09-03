#version 430 core
// FFX-style type aliases for HLSL→GLSL port
#define FFX_UNROLL
#define FFX_STATIC
#define FFX_PARAMETER_IN
#define FFX_PARAMETER_OUT out
#define FFX_MIN16_I2 ivec2
#define FFX_MIN16_F2 vec2
#define FFX_MIN16_F float
#define FFX_HALF 0

const float FFX_FRAMEINTERPOLATION_EPSILON = 1e-3;
const float FFX_FRAMEINTERPOLATION_FLT_MAX = 3.402823466e+38;
const float FFX_FRAMEINTERPOLATION_FLT_MIN = 1.175494351e-38;
const float FFX_EPSILON = 1e-6;

float ffxSaturate(float v) { return clamp(v, 0.0, 1.0); }
vec2 ffxSaturate(vec2 v) { return clamp(v, vec2(0.0), vec2(1.0)); }
vec3 ffxSaturate(vec3 v) { return clamp(v, vec3(0.0), vec3(1.0)); }
float ffxPow(float b, float e) { return pow(b, e); }
float ffxRound(float v) { return floor(v + 0.5); }
vec2 ffxFract(vec2 v) { return fract(v); }
float ffxMin(float a, float b) { return min(a, b); }
float ffxMax(float a, float b) { return max(a, b); }
vec3 ffxLerp(vec3 a, vec3 b, float t) { return mix(a, b, t); }
vec3 ffxBroadcast3(float v) { return vec3(v); }
vec2 ffxBroadcast2(float v) { return vec2(v); }
float ffxLinearFromSrgb(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
vec3 ffxLinearFromSrgb(vec3 c) { return vec3(ffxLinearFromSrgb(c.r), ffxLinearFromSrgb(c.g), ffxLinearFromSrgb(c.b)); }

bvec2 FFX_GREATER_THAN_EQUAL(vec2 a, vec2 b) { return greaterThanEqual(a, b); }
bvec2 FFX_EQUAL(vec2 a, vec2 b) { return equal(a, b); }
bvec2 FFX_LESS_THAN(uvec2 a, uvec2 b) { return lessThan(a, b); }

// FrameInterpolationConstants — matches AMD SDK constant buffer
uniform ivec2 uRenderSize;
uniform ivec2 uDisplaySize;
uniform ivec2 uInterpolationRectBase;
uniform ivec2 uInterpolationRectSize;
uniform vec2 uJitter;
uniform uint uFrameIndex;
uniform bool uReset;
uniform bool uInvertedDepth;
uniform vec4 uDeviceToViewDepth;
uniform float uTanHalfFovY;
uniform ivec2 uMaxRenderSize;

float convertFromDeviceDepthToViewSpace(float d) {
    return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0]);
}

vec2 computeNdc(vec2 px, ivec2 size) {
    return px / vec2(size) * vec2(2.0, -2.0) + vec2(-1.0, 1.0);
}

vec3 getViewSpacePosition(ivec2 vp, ivec2 vpSize, float devDepth) {
    float Z = convertFromDeviceDepthToViewSpace(devDepth);
    vec2 ndc = computeNdc(vec2(vp), vpSize);
    float X = uDeviceToViewDepth[2] * ndc.x * Z;
    float Y = uDeviceToViewDepth[3] * ndc.y * Z;
    return vec3(X, Y, Z);
}

vec2 renderSize() { return vec2(uRenderSize); }
vec2 displaySize() { return vec2(uDisplaySize); }
ivec2 interpRectBase() { return uInterpolationRectBase; }
ivec2 interpRectSize() { return uInterpolationRectSize; }

bool isOnScreen(ivec2 pos, ivec2 size) {
    return all(lessThan(uvec2(pos), uvec2(size)));
}

bool isUvInside(vec2 uv) {
    return uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0;
}

float rgbToLuma(vec3 linearRgb) {
    return dot(linearRgb, vec3(0.2126, 0.7152, 0.0722));
}

float rawRgbToLuminance(vec3 rawRgb) {
    return rgbToLuma(ffxLinearFromSrgb(rawRgb));
}

struct BilinearData {
    ivec2 iOffsets[4];
    float fWeights[4];
    ivec2 iBasePos;
};

BilinearData getBilinearSamplingData(vec2 uv, ivec2 size) {
    BilinearData d;
    vec2 px = uv * vec2(size) - vec2(0.5);
    d.iBasePos = ivec2(floor(px));
    vec2 frac = fract(px);
    d.iOffsets[0] = ivec2(0,0); d.iOffsets[1] = ivec2(1,0);
    d.iOffsets[2] = ivec2(0,1); d.iOffsets[3] = ivec2(1,1);
    d.fWeights[0] = (1-frac.x)*(1-frac.y);
    d.fWeights[1] = frac.x*(1-frac.y);
    d.fWeights[2] = (1-frac.x)*frac.y;
    d.fWeights[3] = frac.x*frac.y;
    return d;
}

float minDividedByMax(float a, float b) {
    float m = max(a, b);
    return m != 0.0 ? min(a, b) / m : 0.0;
}
struct VectorFieldEntry {
    vec2 fMotionVector;
    float uHighPriorityFactor;
    float uLowPriorityFactor;
    bool bValid;
    bool bPrimary;
    bool bSecondary;
    bool bInPainted;
    float fVelocity;
    bool bNegOutside;
    bool bPosOutside;
};

VectorFieldEntry newVectorFieldEntry() {
    VectorFieldEntry v;
    v.fMotionVector = vec2(0.0); v.uHighPriorityFactor = 0.0;
    v.uLowPriorityFactor = 0.0; v.bValid = false; v.bPrimary = false;
    v.bSecondary = false; v.bInPainted = false; v.fVelocity = 0.0;
    v.bNegOutside = false; v.bPosOutside = false;
    return v;
}

// Bit packing: each 32-bit field slot = [DepthPriority:16 | MVComponent:16]
const uint PRIORITY_MAX = (1u << 16) - 1;
const uint PRIORITY_SHIFT = 16;

uvec2 packVectorFieldEntries(uint depthPriority, vec2 mv) {
    uint priority = (depthPriority & PRIORITY_MAX) << PRIORITY_SHIFT;
    return uvec2(priority | (packHalf2x16(mv.xy) & 0xFFFFu),
                 priority | ((packHalf2x16(mv.xy) >> 16) & 0xFFFFu));
}

VectorFieldEntry unpackVectorFieldEntries(uvec2 packed) {
    VectorFieldEntry v = newVectorFieldEntry();
    v.uHighPriorityFactor = float((packed.x >> PRIORITY_SHIFT) & PRIORITY_MAX) / float(PRIORITY_MAX);
    v.uLowPriorityFactor = 0.0;
    v.bValid = v.uHighPriorityFactor > 0.0;
    v.bPrimary = true;
    v.bSecondary = false;
    uint vecPacked = (packed.x & 0xFFFFu) | ((packed.y & 0xFFFFu) << 16);
    v.fMotionVector = unpackHalf2x16(vecPacked);
    return v;
}
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uDilatedData;
layout(binding = 1, r32ui) uniform uimage2D uReconstructedPrevDepth;
layout(binding = 2, r32ui) uniform uimage2D uGameMvFieldX;
layout(binding = 3, r32ui) uniform uimage2D uGameMvFieldY;
layout(binding = 4, rg8) uniform image2D uDisocclusionMask;

uniform float uDepthClipConst;
uniform float uDepthClipPower;

float loadPrevDepth(int estimatedIdx, ivec2 pos) {
    if (estimatedIdx == 0)
        return uintBitsToFloat(imageLoad(uReconstructedPrevDepth, pos).r);
    else
        return texelFetch(uDilatedData, pos, 0).b;
}

float computeDepthClip(int estimatedIdx, vec2 uv, float curDepth) {
    float curViewZ = convertFromDeviceDepthToViewSpace(curDepth);
    BilinearData bd = getBilinearSamplingData(uv, uRenderSize);
    float depthSum = 0.0, weightSum = 0.0;

    for (int i = 0; i < 4; i++) {
        if (bd.fWeights[i] > FFX_FRAMEINTERPOLATION_EPSILON) {
            ivec2 sp = bd.iBasePos + bd.iOffsets[i];
            if (isOnScreen(sp, uRenderSize)) {
                float prevD = loadPrevDepth(estimatedIdx, sp);
                float prevViewZ = convertFromDeviceDepthToViewSpace(prevD);
                float diff = curViewZ - prevViewZ;
                if (diff > 0.0) {
                    float depthThresh = max(curViewZ, prevViewZ);
                    float d = pow(clamp(uDepthClipConst * depthThresh / max(diff, 1e-7), 0.0, 1.0), uDepthClipPower) * bd.fWeights[i];
                    depthSum += d;
                    weightSum += bd.fWeights[i];
                }
            }
        }
    }
    return weightSum > 0.0 ? clamp(1.0 - depthSum / weightSum, 0.0, 1.0) : 0.0;
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (!isOnScreen(p, uRenderSize)) return;

    vec2 uv = (vec2(p) + 0.5) / renderSize();
    float curDepth = textureLod(uDilatedData, uv, 0.0).b;

    uvec2 packed;
    packed.x = imageLoad(uGameMvFieldX, p).r;
    packed.y = imageLoad(uGameMvFieldY, p).r;
    VectorFieldEntry mv = unpackVectorFieldEntries(packed);

       float clipToPrev = 1.0 - computeDepthClip(0, uv + mv.fMotionVector, curDepth);
       float clipToCurr = 1.0 - computeDepthClip(1, uv - mv.fMotionVector, curDepth);

      vec2 disocclusion = vec2(clipToPrev, clipToCurr);
      disocclusion = vec2(
          greaterThanEqual(disocclusion, vec2(FFX_FRAMEINTERPOLATION_EPSILON)));

      vec2 srcMv = mv.fMotionVector;
      ivec2 prevPos = ivec2((uv + srcMv) * renderSize());
      disocclusion.x = clamp(disocclusion.x + float(!isOnScreen(prevPos, uRenderSize)), 0.0, 1.0);
      ivec2 currPos = ivec2((uv - srcMv) * renderSize());
     disocclusion.y = clamp(disocclusion.y + float(!isOnScreen(currPos, uRenderSize)), 0.0, 1.0);

    imageStore(uDisocclusionMask, p, vec4(disocclusion, 0, 0));
}
