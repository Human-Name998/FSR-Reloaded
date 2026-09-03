package com.fsr2mod.fsr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BrokenCompositePredicate} (predicate v2, 2026-08-25).
 *
 * <p>Values are taken from the actual session recordings in AGENTS.md:
 * <ul>
 *   <li>Broken frames 13/33/34/35 (14:26 FSR4 + pack session) must all vote:
 *       dusk/overcast sky (luma 79-86) over a black void (terrain 1-4), and a
 *       flat sky gradient painted over the terrain region (terrain luma
 *       104-122, var~1, blue-dominant) under a dark-navy sky.</li>
 *   <li>Healthy frames 1/7/20/22 must never vote: textured terrain (var
 *       34-340), and the BSL session's flat-but-dim pair (sky luma ~51 — below
 *       the 55 ceiling — over terrain luma ~39, non-blue) reads healthy.</li>
 * </ul>
 *
 * <p>The BSL pair exposed a real edge case during development: under predicate
 * v2 as shipped, a flat dim-blue bottom (luma 38.7, B-dominant, var 1.2) under
 * a smooth dim sky WOULD have falsely voted {@code skygrad} (recorded BSL
 * session: brokenVote=0/3 — healthy). The {@code terrainLuma > 60} horizon
 * floor in {@link BrokenCompositePredicate} was added for it — all validated
 * broken skygrad frames read bright horizon-blue (luma 104-122), so the floor
 * changes no validated verdict while keeping BSL dusk from falsely latching
 * native pass-through.</p>
 * The classifier is fail-open: anything that does not match a signature
 * returns null (no vote), and null/bad inputs can never classify as broken.
 */
class BrokenCompositePredicateTest {

    // --- Broken signature 1: visible sky over a dark flat void -------------

    @Test
    void duskSkyOverBlackVoidVotesVoid() {
        // AGENTS.md: "dusk/overcast sky (luma 79-86) over a black void (terrain 1-4)".
        // sky (90,80,85) -> luma 83.6; terrain (3,3,5) -> luma 3.2, var ~0.
        assertEquals("void", BrokenCompositePredicate.classify(83.6f, 8.0f, 3.2f, 0.5f, 3f, 3f, 5f));
    }

    @Test
    void noonSkyOverDarkVoidVotesVoid() {
        // World-join signature from AGENTS.md: "sky luma 146 / terrain luma 37".
        assertEquals("void", BrokenCompositePredicate.classify(146.5f, 5.0f, 38.7f, 1.2f, 35f, 39f, 47f));
    }

    @Test
    void overcastSkyOverBlackVoidVotesVoid() {
        // Low end of the dusk band (luma ~79) still votes — the v1 bug was the
        // >110 ceiling excluding dim sky; v2 has no absolute brightness cap.
        assertEquals("void", BrokenCompositePredicate.classify(79.0f, 6.0f, 1.5f, 0.2f, 1f, 1f, 2f));
    }

    @Test
    void darkTexturedForestFloorUnderBrightSkyStillVotesVoid() {
        // Documented tradeoff: a textured (var 100) dark floor under a bright
        // sky matches the void luminance pattern. Acceptable — indistinguishable
        // from the terrain-missing shape by luma alone.
        assertEquals("void", BrokenCompositePredicate.classify(146.5f, 5.0f, 30.0f, 100.0f, 25f, 30f, 22f));
    }

    // --- Broken signature 2: flat sky gradient painted over the frame -------

    @Test
    void skyGradientOverFlatBlueTerrainVotesSkygrad() {
        // AGENTS.md: "terrain block reads bright horizon-blue (luma 104-122,
        // var~1) under a dark-navy sky".
        // sky (10,15,35) -> luma 15.8, flat; terrain (80,100,160) -> luma 100.9, var ~1, B-dominant.
        assertEquals("skygrad", BrokenCompositePredicate.classify(15.8f, 4.0f, 100.9f, 1.0f, 80f, 100f, 160f));
    }

    @Test
    void duskGradientOverFlatBlueTerrainVotesSkygrad() {
        // Dusk variant: dimmer sky, still a gradient over flat blue terrain.
        assertEquals("skygrad", BrokenCompositePredicate.classify(22.0f, 6.0f, 95.0f, 1.5f, 70f, 90f, 140f));
    }

    // --- Healthy: must never vote ------------------------------------------

    @Test
    void texturedTerrainUnderBrightSkyIsHealthy() {
        // Healthy frame: textured terrain (var 34-340) — void fails (terrain
        // not <45) and skygrad fails (terrainVar not <15).
        assertNull(BrokenCompositePredicate.classify(146.5f, 5.0f, 93.6f, 100.0f, 90f, 100f, 70f));
    }

    @Test
    void texturedBlueTerrainUnderBlueSkyIsHealthy() {
        // Textured (var 60) blue-ish terrain is NOT a sky gradient — skygrad
        // requires terrainVar < 15.
        assertNull(BrokenCompositePredicate.classify(80.0f, 10.0f, 70.0f, 60.0f, 60f, 80f, 140f));
    }

    @Test
    void nightSkyOverFlatVoidIsHealthy() {
        // Night: sky luma 26-27 is below the 55 ceiling — void must not fire.
        assertNull(BrokenCompositePredicate.classify(25.9f, 3.0f, 31.7f, 50.0f, 30f, 32f, 35f));
    }

    @Test
    void bslFlatDimPairIsHealthy() {
        // BSL session (2026-08-25): sky (49,51,57) luma ~51, terrain (35,39,47)
        // luma ~39, var 1.2 — logged brokenVote=0/3. Sky is under 55 (no void)
        // and the terrain is not blue-dominant (no skygrad).
        assertNull(BrokenCompositePredicate.classify(51.1f, 4.0f, 38.7f, 1.2f, 35f, 39f, 47f));
    }

    // --- Fail-open: bad inputs never classify broken -------------------------

    @Test
    void missingTerrainRgbNeverClassifiesSkygrad() {
        // terrainRGB = -1 (unavailable) means B > R && B > G is false, so the
        // skygrad signature can never fire even if every other condition holds.
        assertNull(BrokenCompositePredicate.classify(15.8f, 4.0f, 100.9f, 1.0f, -1f, -1f, -1f));
    }

    @Test
    void exactBoundaryValuesDoNotVote() {
        // skyLuma exactly 55 is NOT > 55 -> void fails; skygrad needs the full
        // gradient shape, which a luma-55 sky with a luma-60 non-blue terrain
        // block does not satisfy.
        assertNull(BrokenCompositePredicate.classify(55.0f, 3.0f, 60.0f, 1.0f, 60f, 60f, 60f));
    }
}
