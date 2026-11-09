package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFpsRequestPolicyTest {

    @Test
    fun `preview cadence selector pins to 25 idle and honors user record fps while recording`() {
        // Idle / armed preview in any mode -> 25fps regardless of the selected record fps.
        assertEquals(
            25,
            CameraFpsRequestPolicy.previewOrRecordRequestedFps(
                videoRecordingRequestActive = false,
                previewCadenceFps = 25,
                recordFps = 120
            )
        )
        // Recording -> the user-selected record fps (decoupled from preview cadence).
        assertEquals(
            120,
            CameraFpsRequestPolicy.previewOrRecordRequestedFps(
                videoRecordingRequestActive = true,
                previewCadenceFps = 25,
                recordFps = 120
            )
        )
    }

    @Test
    fun `request policy pins preview cadence to a 25fps range when not recording`() {
        val requestedFps = CameraFpsRequestPolicy.previewOrRecordRequestedFps(
            videoRecordingRequestActive = false,
            previewCadenceFps = 25,
            recordFps = 120
        )
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = requestedFps,
            availableRanges = listOf(
                CameraIntRange(25, 25),
                CameraIntRange(25, 50),
                CameraIntRange(60, 60)
            ),
            requestedExposureNs = 16_666_666L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L)
        )

        assertEquals(25, plan.appliedFps)
        assertEquals(CameraIntRange(25, 25), plan.aeTargetFpsRange)
    }

    @Test
    fun `preview cadence uses 25 inside nearest available range when device lacks an exact 25`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = CameraFpsRequestPolicy.previewOrRecordRequestedFps(
                videoRecordingRequestActive = false,
                previewCadenceFps = 25,
                recordFps = 60
            ),
            availableRanges = listOf(CameraIntRange(24, 30)),
            requestedExposureNs = 20_000_000L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L)
        )

        assertEquals(CameraIntRange(24, 30), plan.aeTargetFpsRange)
        assertEquals(25, plan.appliedFps)
    }

    @Test
    fun `request policy applies fixed 60fps range when available`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(
                CameraIntRange(30, 60),
                CameraIntRange(60, 60)
            ),
            requestedExposureNs = 33_333_333L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L)
        )

        assertEquals(CameraIntRange(60, 60), plan.aeTargetFpsRange)
        assertEquals(60, plan.appliedFps)
        assertEquals(8_333_333L, plan.exposureNs)
        assertEquals(16_666_666L, plan.frameDurationNs)
    }

    @Test
    fun `request policy falls back to device ceiling when requested fps is unavailable`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(24, 30)),
            requestedExposureNs = 20_000_000L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L)
        )

        assertEquals(CameraIntRange(24, 30), plan.aeTargetFpsRange)
        assertEquals(30, plan.appliedFps)
        assertEquals(16_666_666L, plan.exposureNs)
        assertEquals(33_333_333L, plan.frameDurationNs)
    }

    @Test
    fun `manual video exposure snaps to 50hz flicker safe period at 60fps`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(60, 60)),
            requestedExposureNs = 16_666_666L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            antibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        )

        assertEquals(60, plan.appliedFps)
        assertEquals(10_000_000L, plan.exposureNs)
        assertEquals(16_666_666L, plan.frameDurationNs)
    }

    @Test
    fun `manual video exposure snaps to 60hz flicker safe period at 25fps`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 25,
            availableRanges = listOf(CameraIntRange(25, 25)),
            requestedExposureNs = 20_000_000L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            antibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        )

        assertEquals(25, plan.appliedFps)
        assertEquals(16_666_666L, plan.exposureNs)
        assertEquals(40_000_000L, plan.frameDurationNs)
    }

    @Test
    fun `auto antibanding in a 50hz grid snaps 60fps exposure to 10ms`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(60, 60)),
            requestedExposureNs = 16_666_666L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            antibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
            autoAntibandingPrefers50Hz = true
        )

        assertEquals(60, plan.appliedFps)
        assertEquals(10_000_000L, plan.exposureNs)
        assertEquals(16_666_666L, plan.frameDurationNs)
    }

    @Test
    fun `flicker snap never expands exposure beyond shutter angle safe budget`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(60, 60)),
            requestedExposureNs = 5_000_000L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            antibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
            maximumShutterAngleDegrees = 180f
        )

        assertEquals(60, plan.appliedFps)
        assertEquals(5_000_000L, plan.exposureNs)
        assertEquals(16_666_666L, plan.frameDurationNs)
    }

    @Test
    fun `explicit manual shutter can use a full 360 degree frame budget`() {
        val plan = CameraFpsRequestPolicy.resolve(
            requestedFps = 24,
            availableRanges = listOf(CameraIntRange(24, 24)),
            requestedExposureNs = 41_666_667L,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            maximumShutterAngleDegrees = 360f
        )

        assertEquals(24, plan.appliedFps)
        assertEquals(41_666_666L, plan.exposureNs)
        assertEquals(41_666_666L, plan.frameDurationNs)
    }
}
