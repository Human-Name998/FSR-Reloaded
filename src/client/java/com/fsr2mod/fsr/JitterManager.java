package com.fsr2mod.fsr;

public class JitterManager {
    private int index = 0;
    private float prevJitterX = 0;
    private float prevJitterY = 0;
    private int cachedPhaseCount = -1;
    private int cachedRenderWidth;
    private int cachedDisplayWidth;

    public void next(int renderWidth, int displayWidth, float[] result) {
        int phaseCount;
        if (renderWidth == cachedRenderWidth && displayWidth == cachedDisplayWidth) {
            phaseCount = cachedPhaseCount;
        } else {
            phaseCount = (int) Math.ceil(8.0 * Math.pow((double) displayWidth / renderWidth, 2));
            if (phaseCount < 1) phaseCount = 1;
            cachedPhaseCount = phaseCount;
            cachedRenderWidth = renderWidth;
            cachedDisplayWidth = displayWidth;
        }
        float x = halton(index + 1, 2);
        float y = halton(index + 1, 3);
        index = (index + 1) % phaseCount;
        result[0] = x - 0.5f;
        result[1] = y - 0.5f;
    }

    public void reset() {
        index = 0;
    }

    private float halton(int index, int base) {
        float result = 0.0f;
        float f = 1.0f / base;
        int i = index;
        while (i > 0) {
            result += f * (i % base);
            i /= base;
            f /= base;
        }
        return result;
    }
}
