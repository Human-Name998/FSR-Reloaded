package com.fsr2mod.fsr;

/**
 * BROKEN-COMPOSITE VOTE PREDICATE v2 (2026-08-25) — pure, Minecraft-free
 * classifier for the TERRAIN-PROBE's "did the pack's composite lose its
 * terrain?" vote. Lives in the shared (main) source set so it can be unit
 * tested without a game on the classpath; FSRProcessor calls it from
 * {@code evaluateProxyContentAndVote}.
 *
 * <p>Background (see AGENTS.md): the original rule ({@code sky>110 &&
 * terrain<45 && terrainVar<250}) only matched ONE missing-terrain signature —
 * bright NOON sky over a black void — and the 14:26 session showed the
 * composite losing terrain in two shapes it could not see:
 * <ul>
 *   <li>(a) dusk/overcast sky (luma 79-86, well under 110) over the same
 *       black void (frames 34-35), and</li>
 *   <li>(b) the whole frame being a flat SKY GRADIENT painted down over the
 *       terrain region — terrain block reads bright horizon-blue (luma
 *       104-122, var~1) while the sky above is dark navy (frames 13-19,
 *       ~11s of terrain missing with zero votes).</li>
 * </ul>
 * Every healthy probe in that session had TEXTURED terrain (variance 34-340);
 * every broken one was flat (var &lt;= 4.4).</p>
 *
 * <p>Signature 1 ({@code void}) = a visible sky over a dark flat void — no
 * absolute brightness ceiling (55 still separates day/dusk from night's
 * 26-27). Signature 2 ({@code skygrad}) = a flat, blue-dominant terrain block
 * with a smooth sky above and the horizon not darker than the zenith — the
 * sky gradient covers the whole sampled frame (day or dusk: in a real
 * gradient the horizon is the BRIGHTEST part, so {@code terrain >= sky}
 * holds for a sky-only composite in either light, while real ground is
 * textured, var 34+, in every healthy probe).</p>
 */
public final class BrokenCompositePredicate {

    private BrokenCompositePredicate() {
    }

    /**
     * Classify a probe as a broken (terrain-missing) composite.
     *
     * @param skyLuma     mean luma of the sky (top-center) block
     * @param skyVar      luma variance of the sky block
     * @param terrainLuma mean luma of the terrain (bottom-center) block
     * @param terrainVar  luma variance of the terrain block
     * @param terrainR    mean red of the terrain block, or -1 if unavailable
     * @param terrainG    mean green of the terrain block, or -1 if unavailable
     * @param terrainB    mean blue of the terrain block, or -1 if unavailable
     * @return {@code "void"}, {@code "skygrad"}, or {@code null} when the
     *         composite looks healthy (fail-open: bad/absent inputs never
     *         classify as broken)
     */
    public static String classify(float skyLuma, float skyVar,
                                  float terrainLuma, float terrainVar,
                                  float terrainR, float terrainG, float terrainB) {
        // Signature 1: a visible sky (any brightness above night) over a dark
        // flat void below. terrain<45 + sky>terrain+30 pins the "bright above,
        // black below" shape; var<250 keeps flat voids (real void reads var<=4)
        // while a textured dark forest floor (var 34-340) would still pass —
        // that is acceptable: a dark textured floor under a bright sky is
        // indistinguishable from the void signature's luminance pattern.
        boolean skyOverVoid = skyLuma > 55f && terrainLuma < 45f
            && skyLuma > terrainLuma + 30f && terrainVar < 250f;
        if (skyOverVoid) {
            return "void";
        }
        // Signature 2: a flat, blue-dominant terrain block under a smooth sky,
        // with the horizon not darker than the zenith. Real ground is textured
        // (var 34+) and never a flat blue patch; a real sky gradient is
        // brightest at the horizon, so terrain >= sky-20 holds for a sky-only
        // composite in day or dusk light. terrainLuma > 60 (added 2026-08-25
        // after a unit test caught a BSL dusk false-positive): the validated
        // broken skygrad frames all read BRIGHT horizon-blue (luma 104-122),
        // and a genuine sky-gradient horizon is the brightest part of the
        // frame — a flat dim (<=60) blue-ish bottom is indistinguishable from
        // a dark textured floor and must not latch native pass-through. Keeps
        // every session-validated verdict identical.
        boolean skyGradientOverTerrain = terrainLuma > 60f
            && skyVar < 25f && terrainVar < 15f
            && terrainLuma > skyLuma - 20f
            && terrainB > terrainR && terrainB > terrainG; // blue-dominant
        return skyGradientOverTerrain ? "skygrad" : null;
    }
}
