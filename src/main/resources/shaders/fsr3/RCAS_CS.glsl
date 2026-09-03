#version 430 core
layout(local_size_x = 16, local_size_y = 4) in;
layout(binding = 0, rgba8) uniform image2D uImg;

uniform uvec4 uRcasConfig;
uniform ivec2 uOutputSize;

void main() {
    ivec2 ip = ivec2(gl_GlobalInvocationID.xy);
    if (ip.x >= uOutputSize.x || ip.y >= uOutputSize.y) return;

    float sharp = float(uRcasConfig.x) / 8.0;
    if (sharp < 0.001) return;

    // FSR1 RCAS PORT (2026-08-08, "the grass is a blurry mess"): the custom

    // unsharp mask + min/max clamp failed twice - the sign fix overshot (dark

    // border around the sun), then the clamp froze bright thin features: with

    // the center inside the range, a grass blade IS the local max, so clamp()

    // returned it unchanged = zero sharpening on grass. This is the actual

    // FidelityFX FSR1 RCAS (ffx_fsr1.h FsrRcasF): a 5-tap cross filter whose

    // negative-lobe weight is SOLVED (hitMin/hitMax from the ring min/max) so

    // the output can never clip out of [0,1] - anti-overshoot by construction,

    // capped at FSR_RCAS_LIMIT (0.1875), with a noise detector. Sun edges

    // (ring contains a saturated 1.0) drive the lobe to ~0 (no dark border);

    // grass blades (local max, ring below 1.0) get a real pull, bounded.

    // FSR1 RCAS PORT (2026-08-08, "the grass is a blurry mess"): the custom

    // unsharp mask + min/max clamp failed twice - the sign fix overshot (dark

    // border around the sun), then the clamp froze bright thin features: with

    // the center inside the range, a grass blade IS the local max, so clamp()

    // returned it unchanged = zero sharpening on grass. This is the actual

    // FidelityFX FSR1 RCAS (ffx_fsr1.h FsrRcasF): a 5-tap cross filter whose

    // negative-lobe weight is SOLVED (hitMin/hitMax from the ring min/max) so

    // the output can never clip out of [0,1] - anti-overshoot by construction,

    // capped at FSR_RCAS_LIMIT (0.1875), with a noise detector. Sun edges

    // (ring contains a saturated 1.0) drive the lobe to ~0 (no dark border);

    // grass blades (local max, ring below 1.0) get a real pull, bounded.

    vec3 c = imageLoad(uImg, ip).rgb;

    vec3 b = imageLoad(uImg, ip + ivec2(0, -1)).rgb;  // up

    vec3 d = imageLoad(uImg, ip + ivec2(-1, 0)).rgb;  // left

    vec3 f = imageLoad(uImg, ip + ivec2(1, 0)).rgb;   // right

    vec3 h = imageLoad(uImg, ip + ivec2(0, 1)).rgb;   // down

    // Luma*2 (FSR's cheap approx: 0.5*B + (0.5*R + G)).

    float bL = b.b * 0.5 + (b.r * 0.5 + b.g);

    float dL = d.b * 0.5 + (d.r * 0.5 + d.g);

    float eL = c.b * 0.5 + (c.r * 0.5 + c.g);

    float fL = f.b * 0.5 + (f.r * 0.5 + f.g);

    float hL = h.b * 0.5 + (h.r * 0.5 + h.g);

    // Noise detection (FSR_RCAS_DENOISE): luma highpass vs the local range;

    // reduces sharpening on grain, focuses it on real edges.

    float nz = 0.25 * (bL + dL + fL + hL) - eL;

    float lumaMax = max(max(max(bL, dL), max(eL, fL)), hL);

    float lumaMin = min(min(min(bL, dL), min(eL, fL)), hL);

    nz = clamp(abs(nz) / max(lumaMax - lumaMin, 1e-7), 0.0, 1.0);

    nz = -0.5 * nz + 1.0;

    // Ring min/max (the 4 cross taps, center NOT included - that is what lets

    // a local-max blade still sharpen; a post-clamp including the center was

    // the grass-freeze bug).

    vec3 mn4 = min(min(min(b, d), f), h);

    vec3 mx4 = max(max(max(b, d), f), h);

    // Solve for the maximum negative lobe that never clips out of [0,1]:

    //   0 == (w*(ring) + m)/(4w + 1)  ->  w = -m/ring        (hitMin side)

    //   1 == (w*(ring) + m)/(4w + 1)  ->  w = (1-m)/(ring-4) (hitMax side)

    // using ring = 4*mx4 / 4*mn4 (worst case) and m = mn4 / mx4.

    float lowerLimiter = clamp(eL / max(min(min(bL, dL), min(fL, hL)), 1e-7), 0.0, 1.0);

    vec3 hitMin = mn4 * (1.0 / max(4.0 * mx4, 1e-7)) * lowerLimiter;

    vec3 hitMax = (vec3(1.0) - mx4) * (1.0 / min(4.0 * mn4 - 4.0, -1e-7));

    vec3 lobeV = max(-hitMin, hitMax);

    float lobe = max(-0.1875, min(max(lobeV.r, max(lobeV.g, lobeV.b)), 0.0)) * sharp;

    lobe *= nz;

    // Resolve: (w*(b+d+f+h) + c) / (4w + 1).

    vec3 sharpend = (lobe * (b + d + f + h) + c) / max(4.0 * lobe + 1.0, 1e-7);

    sharpend = clamp(sharpend, 0.0, 1.0);

    imageStore(uImg, ip, vec4(sharpend, 1.0));
}
