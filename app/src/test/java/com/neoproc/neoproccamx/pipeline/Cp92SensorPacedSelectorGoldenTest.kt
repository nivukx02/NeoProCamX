package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.ai.AICameraFrameResult
import com.neoproc.neoproccamx.ai.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f01 / CP92 — GOLDEN LOCK. Pins the DEFAULT (flag-off) bundle + cadence so the new
 * sensor-paced/staleness/governor paths are provably inert.
 */
class Cp92SensorPacedSelectorGoldenTest {

    @Test
    fun flagDefaultsOn() {
        assertTrue(SensorPacedFrameSelectorFlag.enabled) // activated 2026-07-04 (user-directed)
    }

    @Test
    fun fusedBundleStalenessFieldsDefaultToZero() {
        // Today's construction shape (no staleness args) must yield 0/0 — bit-for-bit.
        val r = AICameraFrameResult(
            timestampMs = 1_000L,
            faces = emptyList(),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            focusPoint = null,
            primarySubject = null,
            subjectMask = null,
            hdrSuggested = false,
            lowLightSuggested = false,
            enhancementResult = null
        )
        assertEquals(0L, r.oldestStageAgeFrames)
        assertEquals(0L, r.oldestStageAgeMs)
    }

    @Test
    fun countBasedCadenceUnchangedForStrideTwo() {
        val plan = FrameRealtimeAnalysisPolicy.resolve(previewFps = 60f, recording = false, adaptiveStrideMultiplier = 1L)
        assertEquals(2L, plan.aiFrameStride)
        // frameSeq <= 1 || frameSeq % 2 == 0  =>  frames 1..6 = [T,T,F,T,F,T]
        val cadence = (1L..6L).map { plan.shouldAnalyze(it) }
        assertEquals(listOf(true, true, false, true, false, true), cadence)
    }
}
