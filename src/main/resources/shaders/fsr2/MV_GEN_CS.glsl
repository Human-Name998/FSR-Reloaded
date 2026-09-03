#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uInputDepth;
layout(binding = 1, rg16f) writeonly uniform image2D uOutputMV;

uniform ivec2 uRenderSize;
uniform mat4 uInvCurrVP;
uniform mat4 uPrevVP;
uniform vec2 uJitter;
uniform vec2 uPrevJitter;
uniform float uFarDepth;
uniform vec4 uDeviceToViewDepth;
uniform float uFarViewZ;
uniform int uUseDepth;
uniform vec3 uCameraDelta;
uniform int uZZeroToOne;
uniform int uFlipY;

float getViewSpaceDepth(float d) {
    return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

    vec2 uv = (vec2(p) + 0.5) / vec2(uRenderSize);
    float ndcZ;

    if (uUseDepth > 0) {
        float depth = texelFetch(uInputDepth, p, 0).r;
        float vz = getViewSpaceDepth(clamp(depth, 0.0, 1.0 - 1e-7));
           if (abs(vz) > abs(uFarViewZ) * 0.98) {
            imageStore(uOutputMV, p, vec4(0.0));
            return;
        }
        // Correct NDC Z mapping: [0,1] depth -> [-1,1] for OpenGL,
        // or [0,1] -> [0,1] for Vulkan/D3D (uZZeroToOne).
        ndcZ = (uZZeroToOne > 0) ? depth : depth * 2.0 - 1.0;
    } else {
        // Constant-depth assumption (2026-08-06): ndcZ=0.0 is the NEAR plane
  // (z≈0.1 blocks) → MVs ~1000× too large during translation → history
  // rejected → raw jittered input = shake. 0.995 (z≈20) fixed the mid
  // range but the eye-tracked far/horizon content was still overestimated
  // (~8px at walk speed) → shake there. 0.999 → z≈100 blocks: everything
  // ≥50 blocks is within ~4px (horizon stable); nearer content is
  // underestimated and blends as motion blur via the untrusted-depth
  // clamp softening in ACCUMULATE_CS.
  ndcZ = 0.999;
    }

    // Jitter is applied as a translation in clip space (via Matrix4f.translation),
    // which is automatically handled by the inverse view-projection matrix.
    // No manual de-jitter needed here — just use raw UV coordinates.
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 worldH = uInvCurrVP * vec4(ndc, ndcZ, 1.0);
    worldH /= worldH.w;

    // Camera-relative rendering: add explicit camera delta so reprojection
    // works correctly regardless of how translation is handled in VP matrices.
    worldH.xyz += uCameraDelta;

    vec4 prevClip = uPrevVP * vec4(worldH.xyz, 1.0);
    vec2 prevNdc = prevClip.xy / prevClip.w;
    vec2 prevUv = prevNdc * 0.5 + 0.5;

    vec2 mv = (prevUv - uv) * vec2(uRenderSize);

    // Vertical flip correction for Vulkan screen-origin convention
    if (uFlipY > 0) mv.y *= -1.0;

    imageStore(uOutputMV, p, vec4(mv, 0.0, 0.0));
}
