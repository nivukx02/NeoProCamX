package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99PreviewTuningContinuityTest {
    @Test
    fun `reuse window is exactly two sensor intervals and remains bounded`() {
        assertEquals(80_000_000L, Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(25f))
        assertEquals(66_666_668L, Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(30f))
        assertEquals(33_333_334L, Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(60f))
        assertEquals(16_666_668L, Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(120f))
        assertEquals(96_000_000L, Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(Float.NaN))
    }

    @Test
    fun `ring selects newest compatible older packet without claiming exact match`() {
        val ring = Niv99ResolvedTuningRing(capacity = 4)
        val expected = continuity()
        val output = Niv99LiveFrameTuning()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(tuning(frameNumber = 1L, continuity = expected)))
        assertTrue(ring.publish(tuning(frameNumber = 2L, continuity = expected)))

        assertEquals(
            Niv99PreviewTuningFallbackStatus.MATCHED_RECENT,
            ring.copyNewestBefore(
                expectedCameraId = CAMERA_ID,
                expectedGeneration = GENERATION,
                targetSensorTimestampNs = timestamp(3L),
                maximumAgeNs = 80_000_000L,
                expectedContinuity = expected,
                output = output
            )
        )
        assertEquals(2L, output.identity.frameNumber)
        assertEquals(timestamp(2L), output.identity.sensorTimestampNs)
    }

    @Test
    fun `reuse rejects exact future stale cross timeline and changed render authority`() {
        val base = continuity()
        val candidate = tuning(frameNumber = 2L, continuity = base)
        assertFalse(Niv99PreviewTuningContinuityPolicy.canReuse(
            base, candidate, timestamp(2L), 80_000_000L
        ))
        assertFalse(Niv99PreviewTuningContinuityPolicy.canReuse(
            base, candidate, timestamp(1L), 80_000_000L
        ))
        assertFalse(Niv99PreviewTuningContinuityPolicy.canReuse(
            base, candidate, timestamp(5L), 80_000_000L
        ))
        assertFalse(Niv99PreviewTuningContinuityPolicy.canReuse(
            base, candidate, timestamp(5L), Long.MAX_VALUE
        ))

        val mutations = listOf(
            continuity(mode = Niv99OperatingMode.PRO),
            continuity(domain = Niv99SignalDomain.DISPLAY_RGB),
            continuity(detail = Niv99DetailProfile.EXTREME),
            continuity(colorProfileId = 12L),
            continuity(surfaceGeneration = 8L),
            continuity(manualKelvin = 4_300, manualTint = -17),
            continuity(awbLockRequested = true),
            continuity(generation = GENERATION + 1L)
        )
        for (changed in mutations) {
            assertFalse(Niv99PreviewTuningContinuityPolicy.matches(changed, base))
        }
    }

    @Test
    fun `ring never reuses a packet across a continuity transition`() {
        val ring = Niv99ResolvedTuningRing(capacity = 4)
        val sourceContinuity = continuity()
        val changedContinuity = continuity(detail = Niv99DetailProfile.FOCUSED)
        val output = Niv99LiveFrameTuning()
        assertTrue(ring.beginTimeline(CAMERA_ID, GENERATION))
        assertTrue(ring.publish(tuning(frameNumber = 1L, continuity = sourceContinuity)))

        assertEquals(
            Niv99PreviewTuningFallbackStatus.NOT_FOUND,
            ring.copyNewestBefore(
                expectedCameraId = CAMERA_ID,
                expectedGeneration = GENERATION,
                targetSensorTimestampNs = timestamp(2L),
                maximumAgeNs = 80_000_000L,
                expectedContinuity = changedContinuity,
                output = output
            )
        )
    }

    private fun tuning(
        frameNumber: Long,
        continuity: Niv99PreviewTuningContinuity
    ): Niv99LiveFrameTuning = Niv99LiveFrameTuning().apply {
        identity.set(CAMERA_ID, GENERATION, frameNumber, timestamp(frameNumber))
        previewContinuity.copyFrom(continuity)
        valid = true
        confidence = 0.8f
        check(approvePixelApplication())
    }

    private fun continuity(
        generation: Long = GENERATION,
        mode: Niv99OperatingMode = Niv99OperatingMode.VIDEO,
        domain: Niv99SignalDomain = Niv99SignalDomain.P010_YUV,
        detail: Niv99DetailProfile = Niv99DetailProfile.NATURAL,
        colorProfileId: Long = 11L,
        surfaceGeneration: Long = 7L,
        manualKelvin: Int = 0,
        manualTint: Int = 0,
        awbLockRequested: Boolean = false
    ): Niv99PreviewTuningContinuity = Niv99PreviewTuningContinuity().apply {
        check(set(
            cameraId = CAMERA_ID,
            sessionGeneration = generation,
            mode = mode,
            domain = domain,
            detailProfile = detail,
            width = 3_840,
            height = 2_160,
            targetFps = 25,
            colorProfileId = colorProfileId,
            surfaceGeneration = surfaceGeneration,
            codecGeneration = generation,
            manualKelvin = manualKelvin,
            manualTint = manualTint,
            awbLockRequested = awbLockRequested
        ))
    }

    private fun timestamp(frameNumber: Long): Long = frameNumber * 40_000_000L

    private companion object {
        const val CAMERA_ID = "0"
        const val GENERATION = 9L
    }
}
