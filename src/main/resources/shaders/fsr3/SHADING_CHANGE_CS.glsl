#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uInputColor;
layout(binding = 1) uniform sampler2D uInputDepth;
layout(binding = 2) uniform sampler2D uPreviousShadingChange;

layout(binding = 3, rg8) writeonly uniform image2D uShadingChange;

uniform ivec2 uRenderSize;
uniform float uThreshold;
uniform float uFarDepth;
uniform vec4 uDeviceToViewDepth;
uniform float uFarViewZ;

float getViewSpaceDepth(float d) {
    return uDeviceToViewDepth[1] / (d - uDeviceToViewDepth[0] + 1e-10);
}

shared uint s_histHigh;
shared uint s_histLow;
shared uint s_skyCount;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    bool inBounds = p.x < uRenderSize.x && p.y < uRenderSize.y;

    // Initialize shared memory (all threads must reach barrier)
    if (gl_LocalInvocationIndex == 0u) {
        s_histHigh = 0u;
        s_histLow = 0u;
        s_skyCount = 0u;
    }
    barrier();

    // Accumulate into tile histogram (only in-bounds pixels)
    if (inBounds) {
        // FIX: skip sky pixels from the histogram — they cause false
        // shading-change detection when clouds drift through the tile.
        // Use view-space Z ratio (stable under jitter) not device-depth.
        float depth = texelFetch(uInputDepth, p, 0).r;
        float vz = getViewSpaceDepth(clamp(depth, 0.0, 1.0 - 1e-7));
        bool isSky = abs(vz) > abs(uFarViewZ) * 0.95;
        if (isSky) {
            atomicAdd(s_skyCount, 1u);
        } else {
            vec3 rgb = max(vec3(0.0), texelFetch(uInputColor, p, 0).rgb);
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            if (luma > uThreshold) {
                atomicAdd(s_histHigh, 1u);
            } else {
                atomicAdd(s_histLow, 1u);
            }
        }
    }
    barrier();

    // First thread classifies the tile
    if (gl_LocalInvocationIndex == 0u) {
        uint total = s_histHigh + s_histLow;
        float ratio = float(s_histHigh) / float(max(total, 1u));

        // Read previous frame's luminance ratio from R channel
        ivec2 tilePos = ivec2(gl_WorkGroupID.xy * 8);
        vec2 tileUv = (vec2(tilePos) + 4.0) / vec2(uRenderSize);
        float prevRatio = textureLod(uPreviousShadingChange, tileUv, 0.0).r;

        // Detect shading change: compare current vs previous luminance distribution
        float diff = abs(ratio - prevRatio);

        // FIX: if tile is mostly sky, suppress shading change — clouds
        // drifting through sky tiles should not trigger the shading-change
        // detector (it was designed for lighting changes, not cloud motion).
        float skyRatio = float(s_skyCount) / float(max(total + s_skyCount, 1u));
        if (skyRatio > 0.5) {
            diff = 0.0;
        }

        // Write per-tile results: R = ratio (for next frame), G = change (for accumulate)
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                ivec2 tp = tilePos + ivec2(dx, dy);
                if (tp.x < uRenderSize.x && tp.y < uRenderSize.y) {
                    imageStore(uShadingChange, tp, vec4(ratio, diff, 0, 0));
                }
            }
        }
    }
}
