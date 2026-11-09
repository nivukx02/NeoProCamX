package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCinematicExposurePolicyTest {

    @Test
    fun `cinema auto shutter defaults to 180 degree timing and retains metered ISO`() {
        val plan = CameraCinematicExposurePolicy.resolve(
            cinematicActive = true,
            supportsManualSensor = true,
            requestedIso = -1,
            requestedExposureNs = -1L,
            latestIso = 640,
            targetFps = 24
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(20_833_333L, plan.exposureNs)
        assertEquals(640, plan.iso)
    }

    @Test
    fun `cinema explicit manual exposure and ISO remain user controlled`() {
        val plan = CameraCinematicExposurePolicy.resolve(
            cinematicActive = true,
            supportsManualSensor = true,
            requestedIso = 400,
            requestedExposureNs = 10_000_000L,
            latestIso = 800,
            targetFps = 30
        )

        assertTrue(plan.activateManualExposure)
        assertEquals(10_000_000L, plan.exposureNs)
        assertEquals(400, plan.iso)
    }

    @Test
    fun `consumer auto mode does not force manual timing`() {
        val plan = CameraCinematicExposurePolicy.resolve(
            cinematicActive = false,
            supportsManualSensor = true,
            requestedIso = -1,
            requestedExposureNs = -1L,
            latestIso = 500,
            targetFps = 30
        )

        assertFalse(plan.activateManualExposure)
    }

    @Test
    fun `cinema automatic timing waits for metered ISO instead of forcing ISO 100`() {
        val plan = CameraCinematicExposurePolicy.resolve(
            cinematicActive = true,
            supportsManualSensor = true,
            requestedIso = -1,
            requestedExposureNs = -1L,
            latestIso = null,
            targetFps = 24
        )

        assertFalse(plan.activateManualExposure)
    }

    @Test
    fun `cinema exposure compensation changes ISO without drifting locked shutter`() {
        val adjusted = CameraCinematicExposurePolicy.applyLockedShutterEv(
            baseIso = 400,
            lockedExposureNs = 20_833_333L,
            evIndex = 2,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(125_000L, 33_333_333L),
            frameExposureBudgetNs = 41_666_666L
        )

        // +1 stop realized entirely on ISO (400 -> 800), shutter stays at the 180° lock.
        assertEquals(800, adjusted.iso)
        assertEquals(20_833_333L, adjusted.exposureNs)
        assertEquals(1f, adjusted.evStops, 0.001f)
    }

    @Test
    fun `EV zero restores the neutral baseline exactly`() {
        val neutral = CameraCinematicExposurePolicy.applyLockedShutterEv(
            baseIso = 640,
            lockedExposureNs = 20_833_333L,
            evIndex = 0,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(125_000L, 33_333_333L),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(640, neutral.iso)
        assertEquals(20_833_333L, neutral.exposureNs)
        assertEquals(0f, neutral.evStops, 0.001f)
    }

    @Test
    fun `bright scene at ISO floor spills negative EV into a faster shutter`() {
        // Base ISO already at the sensor floor: EV down cannot darken via ISO, so it must shorten
        // the shutter instead of silently doing nothing.
        val adjusted = CameraCinematicExposurePolicy.applyLockedShutterEv(
            baseIso = 50,
            lockedExposureNs = 20_833_333L,
            evIndex = -2,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(125_000L, 33_333_333L),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(50, adjusted.iso)
        // -1 stop: ISO cannot move, so the shutter halves (20.83ms -> ~10.42ms).
        assertEquals(10_416_667L, adjusted.exposureNs)
        assertEquals(-1f, adjusted.evStops, 0.001f)
    }

    @Test
    fun `dim scene at ISO ceiling spills positive EV into a longer shutter within budget`() {
        val adjusted = CameraCinematicExposurePolicy.applyLockedShutterEv(
            baseIso = 3200,
            lockedExposureNs = 20_833_333L,
            evIndex = 2,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(125_000L, 100_000_000L),
            frameExposureBudgetNs = 41_666_666L
        )

        assertEquals(3200, adjusted.iso)
        // +1 stop: ISO is railed at the ceiling, so the shutter doubles (still within the frame budget).
        assertEquals(41_666_666L, adjusted.exposureNs)
        assertEquals(1f, adjusted.evStops, 0.001f)
    }
}
