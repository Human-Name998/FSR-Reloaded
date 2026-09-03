#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform sampler2D uDilatedData;

layout(binding = 1, r32ui) writeonly uniform uimage2D uReconstructedPrevDepth;
layout(binding = 2, r8) writeonly uniform image2D uNewLocks;

uniform ivec2 uRenderSize;
uniform ivec2 uDisplaySize;
uniform vec2 uDownscaleFactor;
uniform vec2 uJitter;

ivec2 clampLoad(ivec2 p, ivec2 off, ivec2 sz) {
    ivec2 r = p + off;
    if (off.x < 0) r.x = max(r.x, 0);
    if (off.x > 0) r.x = min(r.x, sz.x - 1);
    if (off.y < 0) r.y = max(r.y, 0);
    if (off.y > 0) r.y = min(r.y, sz.y - 1);
    return r;
}

ivec2 hrPosFromLrPos(ivec2 lr) {
    // Render pixel lr holds the canonical scene content from (lr+0.5+jitter)
    // because jitter shifts camera view. Map that canonical position to display space.
    vec2 srcJit = vec2(lr) + 0.5 + uJitter;
    vec2 hr = (srcJit / vec2(uRenderSize)) * vec2(uDisplaySize);
    return ivec2(floor(hr));
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uRenderSize.x || p.y >= uRenderSize.y) return;

    float nucleus = texelFetch(uDilatedData, p, 0).a;
    float similarThresh = 1.05;
    float dissimMin = 1e10;
    float dissimMax = 0.0;
    uint mask = 0u;
    uint idx = 0u;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) { idx++; continue; }
            ivec2 sp = clampLoad(p, ivec2(x,y), uRenderSize);
            float sl = texelFetch(uDilatedData, sp, 0).a;
            float diff = max(sl, nucleus) / max(min(sl, nucleus), 1e-7);
            if (diff > 0.0 && diff < similarThresh) {
                mask |= (1u << idx);
            } else {
                dissimMin = min(dissimMin, sl);
                dissimMax = max(dissimMax, sl);
            }
            idx++;
        }
    }

    bool isRidge = nucleus > dissimMax || nucleus < dissimMin;
    if (isRidge) {
        uint rej[4] = uint[](
            (1u<<0)|(1u<<1)|(1u<<3)|(1u<<4),
            (1u<<1)|(1u<<2)|(1u<<4)|(1u<<5),
            (1u<<3)|(1u<<4)|(1u<<6)|(1u<<7),
            (1u<<4)|(1u<<5)|(1u<<7)|(1u<<8));
        bool thin = true;
        for (int i = 0; i < 4; i++) {
            if ((mask & rej[i]) == rej[i]) {
                thin = false;
                break;
            }
        }
        if (thin) {
            ivec2 hrPos = hrPosFromLrPos(p);
            if (hrPos.x < uDisplaySize.x && hrPos.y < uDisplaySize.y) {
                imageStore(uNewLocks, hrPos, vec4(1.0, 0, 0, 0));
            }
        }
    }

}
