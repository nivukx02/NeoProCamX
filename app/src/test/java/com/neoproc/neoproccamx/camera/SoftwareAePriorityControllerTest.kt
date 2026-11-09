package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAePriorityControllerTest {

    @Test
    fun `fixed ISO initializes its complementary shutter from the live AE exposure product`() {
        val decision = SoftwareAePriorityController().resolve(
            request = request(
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200
            ),
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )

        assertTrue(decision.ready)
        assertTrue(decision.changed)
        assertEquals(200, decision.sensorSensitivityIso)
        assertEquals(20_000_000L, decision.exposureTimeNs)
        assertEquals("INITIALIZED_FROM_LIVE_AE", decision.reason)
    }

    @Test
    fun `fixed shutter initializes its complementary ISO from the live AE exposure product`() {
        val decision = SoftwareAePriorityController().resolve(
            request = request(
                axis = SoftwareAePriorityAxis.SHUTTER,
                fixedExposureTimeNs = 5_000_000L
            ),
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )

        assertTrue(decision.ready)
        assertTrue(decision.changed)
        assertEquals(800, decision.sensorSensitivityIso)
        assertEquals(5_000_000L, decision.exposureTimeNs)
        assertEquals("INITIALIZED_FROM_LIVE_AE", decision.reason)
    }

    @Test
    fun `cadence guard preserves brightness when the platform meter already includes EV`() {
        val decision = SoftwareAePriorityController().resolve(
            request = request(
                axis = SoftwareAePriorityAxis.SHUTTER,
                fixedExposureTimeNs = 5_000_000L,
                requestedEvStops = 1f,
                meteredIso = 400,
                meteredExposureTimeNs = 10_000_000L,
                meteredExposureIncludesRequestedEv = true
            ),
            sample = sample(luma = 0.50f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )

        assertTrue(decision.ready)
        assertEquals(800, decision.sensorSensitivityIso)
        assertEquals(5_000_000L, decision.exposureTimeNs)
        assertEquals(0.50f, decision.targetLuma, 0.0001f)
    }

    @Test
    fun `cadence guard holds its initialized exposure pair across later meter changes`() {
        val controller = SoftwareAePriorityController()
        val request = request(
            axis = SoftwareAePriorityAxis.SHUTTER,
            fixedExposureTimeNs = 25_000_000L,
            lockComplementaryAxisAfterInitialization = true,
            meteredIso = 1_000,
            meteredExposureTimeNs = 20_000_000L
        )
        val initialized = controller.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val darkFrame = controller.resolve(
            request = request,
            sample = sample(luma = 0.03f, timestampMs = 1_200L),
            nowElapsedRealtimeMs = 1_200L
        )
        val brightFrame = controller.resolve(
            request = request,
            sample = sample(luma = 0.95f, timestampMs = 1_400L),
            nowElapsedRealtimeMs = 1_400L
        )

        assertTrue(initialized.changed)
        assertEquals(800, initialized.sensorSensitivityIso)
        assertFalse(darkFrame.changed)
        assertFalse(brightFrame.changed)
        assertEquals(initialized.sensorSensitivityIso, darkFrame.sensorSensitivityIso)
        assertEquals(initialized.sensorSensitivityIso, brightFrame.sensorSensitivityIso)
        assertEquals(initialized.exposureTimeNs, darkFrame.exposureTimeNs)
        assertEquals(initialized.exposureTimeNs, brightFrame.exposureTimeNs)
        assertEquals("FIXED_CADENCE_HOLD", brightFrame.reason)
    }

    @Test
    fun `stale meter relinquishes software authority and a fresh frame can reacquire it`() {
        val controller = SoftwareAePriorityController()
        val request = request(axis = SoftwareAePriorityAxis.ISO, fixedIso = 200)
        val first = controller.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val stale = controller.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L + SoftwareAePriorityController.MAX_SAMPLE_AGE_MS + 1L
        )
        val fresh = controller.resolve(
            request = request,
            sample = sample(luma = 0.20f, timestampMs = 2_000L),
            nowElapsedRealtimeMs = 2_000L
        )

        assertTrue(first.ready)
        assertFalse(stale.ready)
        assertTrue(stale.changed)
        assertEquals("METER_UNAVAILABLE_OR_STALE", stale.reason)
        assertTrue(fresh.ready)
        assertTrue(fresh.changed)
    }

    @Test
    fun `one meter frame cannot be reused to initialize a new camera session generation`() {
        val controller = SoftwareAePriorityController()
        val frame = sample(luma = 0.25f, timestampMs = 1_000L)
        val first = controller.resolve(
            request = request(
                generation = 7L,
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200
            ),
            sample = frame,
            nowElapsedRealtimeMs = 1_000L
        )
        val nextSession = controller.resolve(
            request = request(
                generation = 8L,
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200
            ),
            sample = frame,
            nowElapsedRealtimeMs = 1_050L
        )

        assertTrue(first.ready)
        assertFalse(
            "A prior-session frame must not acquire exposure authority in a new session",
            nextSession.ready
        )
    }

    @Test
    fun `first fresh frame of a new session establishes a new neutral exposure anchor`() {
        val controller = SoftwareAePriorityController()
        val first = controller.resolve(
            request = request(
                generation = 7L,
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200,
                meteredIso = 400,
                meteredExposureTimeNs = 10_000_000L
            ),
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val nextSession = controller.resolve(
            request = request(
                generation = 8L,
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200,
                meteredIso = 800,
                meteredExposureTimeNs = 10_000_000L
            ),
            sample = sample(luma = 0.30f, timestampMs = 2_000L),
            nowElapsedRealtimeMs = 2_000L
        )

        assertEquals(20_000_000L, first.exposureTimeNs)
        assertTrue(nextSession.ready)
        assertEquals(
            "A new session must meter a new neutral product instead of carrying the old camera context",
            40_000_000L,
            nextSession.exposureTimeNs
        )
        assertEquals(0.30f, nextSession.targetLuma, 0.0001f)
    }

    @Test
    fun `all detail profiles keep deterministic bounded response ordering`() {
        val corrections = VideoDetailProfile.values().associateWith { profile ->
            val controller = SoftwareAePriorityController()
            val request = request(
                axis = SoftwareAePriorityAxis.ISO,
                fixedIso = 200,
                profile = profile
            )
            controller.resolve(
                request = request,
                sample = sample(luma = 0.25f, timestampMs = 1_000L),
                nowElapsedRealtimeMs = 1_000L
            )
            controller.resolve(
                request = request,
                sample = sample(luma = 0.05f, timestampMs = 1_200L),
                nowElapsedRealtimeMs = 1_200L
            ).correctionStops
        }

        assertEquals(0.32f, corrections.getValue(VideoDetailProfile.NATURAL_DETAIL), 0.0001f)
        assertEquals(0.44f, corrections.getValue(VideoDetailProfile.FOCUSED_DETAIL), 0.0001f)
        assertEquals(0.56f, corrections.getValue(VideoDetailProfile.EXTREME_DETAIL), 0.0001f)
        assertTrue(
            corrections.getValue(VideoDetailProfile.NATURAL_DETAIL) <
                corrections.getValue(VideoDetailProfile.FOCUSED_DETAIL)
        )
        assertTrue(
            corrections.getValue(VideoDetailProfile.FOCUSED_DETAIL) <
                corrections.getValue(VideoDetailProfile.EXTREME_DETAIL)
        )
    }

    @Test
    fun `large scene transitions may update at two frames while fine corrections keep three-frame settling`() {
        val request = request(
            axis = SoftwareAePriorityAxis.ISO,
            fixedIso = 200,
            profile = VideoDetailProfile.NATURAL_DETAIL
        )
        val largeController = SoftwareAePriorityController()
        largeController.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val accelerated = largeController.resolve(
            request = request,
            sample = sample(luma = 0.05f, timestampMs = 1_080L),
            nowElapsedRealtimeMs = 1_080L
        )

        val fineController = SoftwareAePriorityController()
        fineController.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val held = fineController.resolve(
            request = request,
            sample = sample(luma = 0.20f, timestampMs = 1_080L),
            nowElapsedRealtimeMs = 1_080L
        )

        assertTrue(accelerated.changed)
        assertEquals(0.32f, accelerated.correctionStops, 0.0001f)
        assertFalse(held.changed)
        assertEquals("SETTLING", held.reason)
    }

    @Test
    fun `EV round trip returns to its neutral baseline without compounding AE-off feedback`() {
        val controller = SoftwareAePriorityController()
        val neutralRequest = request(
            axis = SoftwareAePriorityAxis.ISO,
            fixedIso = 200,
            requestedEvStops = 0f,
            meteredIso = 400,
            meteredExposureTimeNs = 10_000_000L
        )
        val neutral = controller.resolve(
            request = neutralRequest,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val plusOne = controller.resolve(
            request = neutralRequest.copy(
                requestedEvStops = 1f,
                meteredIso = requireNotNull(neutral.sensorSensitivityIso),
                meteredExposureTimeNs = requireNotNull(neutral.exposureTimeNs)
            ),
            sample = sample(luma = 0.25f, timestampMs = 1_200L),
            nowElapsedRealtimeMs = 1_200L
        )
        val restored = controller.resolve(
            request = neutralRequest.copy(
                meteredIso = requireNotNull(plusOne.sensorSensitivityIso),
                meteredExposureTimeNs = requireNotNull(plusOne.exposureTimeNs)
            ),
            sample = sample(luma = 0.50f, timestampMs = 1_400L),
            nowElapsedRealtimeMs = 1_400L
        )

        assertEquals(20_000_000L, neutral.exposureTimeNs)
        assertEquals(40_000_000L, plusOne.exposureTimeNs)
        assertEquals(
            "Returning EV to zero must restore the neutral product instead of retaining compounded gain",
            neutral.exposureTimeNs,
            restored.exposureTimeNs
        )
        assertEquals(neutral.targetLuma, restored.targetLuma, 0.0001f)
    }

    @Test
    fun `highlight clipping attacks exposure before lifting a dark average`() {
        val controller = SoftwareAePriorityController()
        val request = request(axis = SoftwareAePriorityAxis.ISO, fixedIso = 200)
        controller.resolve(
            request = request,
            sample = sample(luma = 0.25f, timestampMs = 1_000L),
            nowElapsedRealtimeMs = 1_000L
        )
        val clipped = controller.resolve(
            request = request,
            sample = sample(
                luma = 0.10f,
                timestampMs = 1_200L,
                highlightClipFraction = 0.12f
            ),
            nowElapsedRealtimeMs = 1_200L
        )

        assertTrue(clipped.ready)
        assertTrue(clipped.changed)
        assertTrue(clipped.correctionStops < 0f)
        assertTrue(requireNotNull(clipped.exposureTimeNs) < 20_000_000L)
    }

    private fun request(
        generation: Long = 7L,
        axis: SoftwareAePriorityAxis,
        fixedIso: Int? = null,
        fixedExposureTimeNs: Long? = null,
        requestedEvStops: Float = 0f,
        meteredExposureIncludesRequestedEv: Boolean = false,
        lockComplementaryAxisAfterInitialization: Boolean = false,
        meteredIso: Int = 400,
        meteredExposureTimeNs: Long = 10_000_000L,
        profile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
    ) = SoftwareAePriorityRequest(
        sessionGeneration = generation,
        mode = UnifiedVideoControlMode.NDR,
        detailProfile = profile,
        axis = axis,
        fixedIso = fixedIso,
        fixedExposureTimeNs = fixedExposureTimeNs,
        requestedEvStops = requestedEvStops,
        meteredExposureIncludesRequestedEv = meteredExposureIncludesRequestedEv,
        lockComplementaryAxisAfterInitialization = lockComplementaryAxisAfterInitialization,
        meteredIso = meteredIso,
        meteredExposureTimeNs = meteredExposureTimeNs,
        isoRange = CameraIntRange(50, 3_200),
        exposureTimeRangeNs = CameraLongRange(125_000L, 100_000_000L),
        frameExposureBudgetNs = 40_000_000L
    )

    private fun sample(
        luma: Float,
        timestampMs: Long,
        highlightClipFraction: Float = 0f,
        redHighlightClipFraction: Float = 0f,
        domain: SoftwareAeSignalDomain = SoftwareAeSignalDomain.PROCESSED_YUV
    ) = SoftwareAeSceneSample(
        domain = domain,
        averageLuma = luma,
        highlightClipFraction = highlightClipFraction,
        redHighlightClipFraction = redHighlightClipFraction,
        measuredAtElapsedRealtimeMs = timestampMs
    )
}
