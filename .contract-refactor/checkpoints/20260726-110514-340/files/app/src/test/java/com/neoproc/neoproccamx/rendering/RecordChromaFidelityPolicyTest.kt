package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordChromaFidelityPolicyTest {

    @Test
    fun strictSdrChromaDenoiseIsCappedSoColourSurvives() {
        // The strict-high-end SDR floor is 0.9997; uncapped, the shader deadband retain would be
        // clamp(1 - 0.9997, 0.0005, 1) = 0.0005 -> grayscale. The cap must pull it down.
        val capped = RecordChromaFidelityPolicy.capRecordChromaDenoise(0.9997f)
        assertEquals(RecordChromaFidelityPolicy.RECORD_CHROMA_DENOISE_CAP, capped, 0f)
    }

    @Test
    fun v20MinimumFloorIsAlsoCapped() {
        // V20ZeroGrainClarityPolicy floors chromaDenoise at 0.90; that alone is retain 0.10 (washed).
        val capped = RecordChromaFidelityPolicy.capRecordChromaDenoise(0.90f)
        assertEquals(RecordChromaFidelityPolicy.RECORD_CHROMA_DENOISE_CAP, capped, 0f)
    }

    @Test
    fun moderateRequestPassesThroughUnchanged() {
        assertEquals(0.30f, RecordChromaFidelityPolicy.capRecordChromaDenoise(0.30f), 1e-6f)
    }

    @Test
    fun nonFiniteRequestFallsBackToCap() {
        assertEquals(RecordChromaFidelityPolicy.RECORD_CHROMA_DENOISE_CAP,
            RecordChromaFidelityPolicy.capRecordChromaDenoise(Float.NaN), 0f)
        assertEquals(RecordChromaFidelityPolicy.RECORD_CHROMA_DENOISE_CAP,
            RecordChromaFidelityPolicy.capRecordChromaDenoise(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun cappedRequestKeepsAtLeastSixtyPercentOfRealChroma() {
        // After the cap, the shader deadband retain must preserve the majority of real chroma so the
        // recording is clearly colourful (not black-and-white). retain = 1 - cap = 0.60.
        val capped = RecordChromaFidelityPolicy.capRecordChromaDenoise(0.9997f)
        val retain = RecordChromaFidelityPolicy.deadbandRetainFor(capped)
        assertTrue("retain=$retain must keep >=60% of chroma", retain >= 0.60f)
    }

    @Test
    fun uncappedStrictFloorWouldHaveCollapsedChroma() {
        // Guards the premise: prove the uncapped path really is the grayscale bug this policy fixes.
        val retainUncapped = RecordChromaFidelityPolicy.deadbandRetainFor(0.9997f)
        assertTrue("uncapped retain=$retainUncapped should be near-zero", retainUncapped <= 0.001f)
    }
}
