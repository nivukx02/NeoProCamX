package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraVideoSensorTimingPolicyTest {

    @Test
    fun `auto exposure recording pins requested frame duration for strict high end cadence`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = true,
            strictHighEndEnabled = true,
            supportsManualSensor = true,
            manualSensorTimingActive = false,
            fpsRequest = fpsRequest(frameDurationNs = 16_666_666L),
            maxFrameDurationNs = 100_000_000L
        )

        assertEquals(16_666_666L, plan.sensorFrameDurationNs)
    }

    @Test
    fun `auto exposure recording pins frame duration outside strict mode`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = true,
            strictHighEndEnabled = false,
            supportsManualSensor = true,
            manualSensorTimingActive = false,
            fpsRequest = fpsRequest(frameDurationNs = 40_000_000L),
            maxFrameDurationNs = 100_000_000L
        )

        assertEquals(40_000_000L, plan.sensorFrameDurationNs)
    }

    @Test
    fun `manual exposure path owns sensor timing`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = true,
            strictHighEndEnabled = true,
            supportsManualSensor = true,
            manualSensorTimingActive = true,
            fpsRequest = fpsRequest(frameDurationNs = 16_666_666L),
            maxFrameDurationNs = 100_000_000L
        )

        assertNull(plan.sensorFrameDurationNs)
    }

    @Test
    fun `preview does not force frame duration outside recording`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = false,
            strictHighEndEnabled = true,
            supportsManualSensor = true,
            manualSensorTimingActive = false,
            fpsRequest = fpsRequest(frameDurationNs = 16_666_666L),
            maxFrameDurationNs = 100_000_000L
        )

        assertNull(plan.sensorFrameDurationNs)
    }

    @Test
    fun `unsupported manual sensor capability keeps auto timing HAL managed`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = true,
            strictHighEndEnabled = true,
            supportsManualSensor = false,
            manualSensorTimingActive = false,
            fpsRequest = fpsRequest(frameDurationNs = 16_666_666L),
            maxFrameDurationNs = 100_000_000L
        )

        assertNull(plan.sensorFrameDurationNs)
    }

    @Test
    fun `auto exposure recording bounds requested frame duration to sensor maximum`() {
        val plan = CameraVideoSensorTimingPolicy.resolve(
            recordingActive = true,
            strictHighEndEnabled = true,
            supportsManualSensor = true,
            manualSensorTimingActive = false,
            fpsRequest = fpsRequest(frameDurationNs = 41_666_666L),
            maxFrameDurationNs = 33_333_333L
        )

        assertEquals(33_333_333L, plan.sensorFrameDurationNs)
    }

    private fun fpsRequest(frameDurationNs: Long): CameraFpsRequestPlan =
        CameraFpsRequestPlan(
            requestedFps = 60,
            aeTargetFpsRange = CameraIntRange(60, 60),
            appliedFps = 60,
            exposureNs = frameDurationNs / 2L,
            frameDurationNs = frameDurationNs
        )
}
