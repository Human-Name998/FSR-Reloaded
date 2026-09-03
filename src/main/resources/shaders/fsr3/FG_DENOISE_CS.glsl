#version 430 core
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0, rgba8) readonly uniform image2D uImg;
layout(binding = 1, rgba8) writeonly uniform image2D uOut;
uniform ivec2 uOutputSize;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= uOutputSize.x || p.y >= uOutputSize.y) return;

    vec3 c = imageLoad(uImg, p).rgb;

    // 3x3 edge-aware bilateral denoise
    float wsum = 1.0;
    vec3 sum = c;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) continue;
            ivec2 np = p + ivec2(x, y);
            if (np.x < 0 || np.y < 0 || np.x >= uOutputSize.x || np.y >= uOutputSize.y) continue;
            vec3 n = imageLoad(uImg, np).rgb;
            vec3 d = n - c;
            float w = exp(-dot(d, d) / 0.02);  // sigma=0.14
            sum += n * w;
            wsum += w;
        }
    }
    vec3 filtered = sum / wsum;

    // Artifact confidence: how far center differs from neighborhood mean
    float artifact = clamp(length(c - filtered) * 6.0, 0.0, 1.0);

    // Only denoise artifact pixels (not clean regions)
    vec3 result = mix(c, filtered, artifact * 0.5);

    imageStore(uOut, p, vec4(result, 1.0));
}
