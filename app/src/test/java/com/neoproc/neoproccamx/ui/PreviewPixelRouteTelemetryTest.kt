package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPixelRouteTelemetryTest {

    @Test
    fun `each real pixel boundary advances independently`() {
        val telemetry = PreviewPixelRouteTelemetry(logEveryFrames = 120L)

        telemetry.record(PreviewPixelRouteStage.CAMERA2_CAPTURE, 1L, 10L)
        telemetry.record(PreviewPixelRouteStage.CAMERA2_CAPTURE, 2L, 20L)
        telemetry.record(PreviewPixelRouteStage.SDR_CHILD_SWAP, 1L, 30L)
        telemetry.record(PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP, 1L, 40L)

        assertEquals(2L, telemetry.snapshot(PreviewPixelRouteStage.CAMERA2_CAPTURE).count)
        assertEquals(1L, telemetry.snapshot(PreviewPixelRouteStage.SDR_CHILD_SWAP).count)
        assertEquals(
            1L,
            telemetry.snapshot(PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP).count
        )
        assertEquals(0L, telemetry.snapshot(PreviewPixelRouteStage.HDR_CHILD_SWAP).count)
    }

    @Test
    fun `observed cadence reports 25 fps without treating capture as presentation`() {
        val telemetry = PreviewPixelRouteTelemetry(logEveryFrames = 5L)
        var observation: PreviewPixelRouteObservation? = null
        repeat(5) { index ->
            observation = telemetry.record(
                stage = PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP,
                sensorTimestampNs = 1_000L + index * 40_000_000L,
                observedElapsedNs = 2_000L + index * 40_000_000L
            )
        }

        val finalObservation = checkNotNull(observation)
        assertEquals(25.0, finalObservation.snapshot.measuredFps, 0.0001)
        assertTrue(finalObservation.logMilestone)
        assertEquals(0L, telemetry.snapshot(PreviewPixelRouteStage.CAMERA2_CAPTURE).count)
    }

    @Test
    fun `timestamp defects are explicit and reset with the session epoch`() {
        val telemetry = PreviewPixelRouteTelemetry(logEveryFrames = 2L)
        telemetry.record(PreviewPixelRouteStage.SDR_CHILD_SWAP, 100L, 1_000L)
        val duplicate = telemetry.record(PreviewPixelRouteStage.SDR_CHILD_SWAP, 100L, 2_000L)!!
        val regression = telemetry.record(PreviewPixelRouteStage.SDR_CHILD_SWAP, 90L, 3_000L)!!

        assertTrue(duplicate.logMilestone)
        assertFalse(regression.logMilestone)
        assertEquals(1L, regression.snapshot.duplicateSensorTimestamps)
        assertEquals(1L, regression.snapshot.regressedSensorTimestamps)

        telemetry.reset(newEpoch = 7L)
        val metadata = telemetry.runtimeMetadata()
        assertEquals("7", metadata["previewPixelRouteEpoch"])
        assertEquals("0", metadata["previewPixelRouteSdrChildSwapCount"])
        assertEquals("0.0", metadata["previewPixelRouteSdrChildSwapMaxObservedGapMs"])
        assertEquals("0", metadata["previewPixelRouteSdrChildSwapObservedGapsOver60Ms"])
    }

    @Test
    fun `presentation and sensor gaps are measured without changing frame cadence`() {
        val telemetry = PreviewPixelRouteTelemetry(logEveryFrames = 4L)
        val stage = PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP

        telemetry.record(stage, 1_000_000_000L, 2_000_000_000L)
        telemetry.record(stage, 1_040_000_000L, 2_040_000_000L)
        val gap = telemetry.record(stage, 1_100_000_001L, 2_100_000_001L)!!
        val final = telemetry.record(stage, 1_140_000_001L, 2_140_000_001L)!!.snapshot

        assertEquals(60_000_001L, gap.sensorGapNs)
        assertEquals(60_000_001L, gap.observedGapNs)
        assertEquals(60_000_001L, final.maxSensorGapNs)
        assertEquals(1L, final.sensorGapsOver60Ms)
        assertEquals(60_000_001L, final.maxObservedGapNs)
        assertEquals(1L, final.observedGapsOver60Ms)
        assertEquals(0L, final.duplicateObservedTimestamps)
        assertEquals(0L, final.regressedObservedTimestamps)
        assertEquals(3L * 1_000_000_000.0 / 140_000_001.0, final.measuredFps, 0.0001)
    }

    @Test
    fun `exactly sixty milliseconds is accepted and observed ordering defects are explicit`() {
        val telemetry = PreviewPixelRouteTelemetry(logEveryFrames = 3L)
        val stage = PreviewPixelRouteStage.PERSISTENT_ROUTER_VISIBLE_SWAP

        telemetry.record(stage, 10L, 1_000_000_000L)
        telemetry.record(stage, 20L, 1_060_000_000L)
        telemetry.record(stage, 30L, 1_060_000_000L)
        val regression = telemetry.record(stage, 40L, 1_050_000_000L)!!.snapshot

        assertEquals(60_000_000L, regression.maxObservedGapNs)
        assertEquals(0L, regression.observedGapsOver60Ms)
        assertEquals(1L, regression.duplicateObservedTimestamps)
        assertEquals(1L, regression.regressedObservedTimestamps)
    }
}
