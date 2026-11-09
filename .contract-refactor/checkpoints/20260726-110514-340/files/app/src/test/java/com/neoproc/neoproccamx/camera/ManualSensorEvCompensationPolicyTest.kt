package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualSensorEvCompensationPolicyTest {

    @Test
    fun `positive ev raises exposure first then iso when frame budget is reached`() {
        val result = ManualSensorEvCompensationPolicy.resolve(
            baseIso = 400,
            baseExposureNs = 10_000_000L,
            evIndex = 2,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            frameExposureBudgetNs = 16_666_666L
        )

        assertEquals(16_666_666L, result.exposureNs)
        assertEquals(480, result.iso)
    }

    @Test
    fun `negative ev lowers iso first to protect shutter cadence`() {
        val result = ManualSensorEvCompensationPolicy.resolve(
            baseIso = 400,
            baseExposureNs = 10_000_000L,
            evIndex = -2,
            evStepStops = 0.5f,
            isoRange = CameraIntRange(100, 3200),
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L),
            frameExposureBudgetNs = 16_666_666L
        )

        assertEquals(10_000_000L, result.exposureNs)
        assertEquals(200, result.iso)
    }

    @Test
    fun `zero ev only clamps manual inputs`() {
        val result = ManualSensorEvCompensationPolicy.resolve(
            baseIso = 25,
            baseExposureNs = 90_000L,
            evIndex = 0,
            evStepStops = 0.33333334f,
            isoRange = CameraIntRange(50, 3200),
            exposureRange = CameraLongRange(100_000L, 20_000_000L),
            frameExposureBudgetNs = 16_666_666L
        )

        assertEquals(100_000L, result.exposureNs)
        assertEquals(50, result.iso)
    }
}
