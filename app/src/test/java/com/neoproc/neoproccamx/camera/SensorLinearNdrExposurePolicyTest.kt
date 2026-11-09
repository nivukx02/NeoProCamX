package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrExposurePolicyTest {
    @Test
    fun darkSceneWithMeasuredHeadroomReceivesBoundedPositiveExposure() {
        val stops = SensorLinearNdrExposurePolicy.resolveTargetStops(
            averageLuma = 0.06f,
            medianLuma = 0.025f,
            p95Luma = 0.28f,
            p99Luma = 0.48f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f
        )

        assertEquals(0.67f, stops, 1e-6f)
    }

    @Test
    fun brightTailPreventsForegroundFromSacrificingHighlights() {
        val stops = SensorLinearNdrExposurePolicy.resolveTargetStops(
            averageLuma = 0.12f,
            medianLuma = 0.04f,
            p95Luma = 0.94f,
            p99Luma = 0.99f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f
        )

        assertTrue(stops < 0f)
    }

    @Test
    fun isolatedSpecularTailDoesNotDarkenTheWholeFrame() {
        val stops = SensorLinearNdrExposurePolicy.resolveTargetStops(
            averageLuma = 0.11f,
            medianLuma = 0.055f,
            p95Luma = 0.42f,
            p99Luma = 0.999f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f
        )

        assertEquals(0f, stops, 1e-6f)
    }

    @Test
    fun actualRawChannelClippingOverridesDarkAverage() {
        val stops = SensorLinearNdrExposurePolicy.resolveTargetStops(
            averageLuma = 0.08f,
            medianLuma = 0.03f,
            p95Luma = 0.30f,
            p99Luma = 0.70f,
            highlightClipFraction = 0.03f,
            redHighlightClipFraction = 0f
        )

        assertTrue(stops < 0f)
        assertTrue(stops > -0.35f)
    }

    @Test
    fun shadowLiftIsContinuousAcrossFormerMedianBoundary() {
        fun target(median: Float) = SensorLinearNdrExposurePolicy.resolveTargetStops(
            averageLuma = 0.19f,
            medianLuma = median,
            p95Luma = 0.67f,
            p99Luma = 0.83f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f
        )

        val below = target(0.1295f)
        val above = target(0.1305f)

        assertTrue(below >= above)
        assertTrue(kotlin.math.abs(below - above) < 0.01f)
    }

    @Test
    fun highlightAttackIsFasterThanDarkSceneLift() {
        val protect = SensorLinearNdrExposurePolicy.smoothStops(0f, -1f)
        val lift = SensorLinearNdrExposurePolicy.smoothStops(0f, 0.67f)

        assertEquals(-0.72f, protect, 1e-6f)
        assertEquals(0.1876f, lift, 1e-4f)
        assertTrue(kotlin.math.abs(protect) > kotlin.math.abs(lift))
    }

    @Test
    fun statefulControllerReducesPositiveLiftBeforeCrossingNeutral() {
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0.60f,
            averageLuma = 0.20f,
            medianLuma = 0.12f,
            p95Luma = 0.95f,
            p99Luma = 0.995f,
            p99Red = 0.998f,
            p99Green = 0.994f,
            p99Blue = 0.990f,
            highlightClipFraction = 0.025f,
            redHighlightClipFraction = 0.04f
        )

        assertTrue(next in 0f..<0.60f)
    }

    @Test
    fun statefulControllerRetiresLegacyPositiveBiasTowardNeutral() {
        val current = 0.24f
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = current,
            averageLuma = 0.19f,
            medianLuma = 0.14f,
            p95Luma = 0.84f,
            p99Luma = 0.94f,
            p99Red = 0.95f,
            p99Green = 0.93f,
            p99Blue = 0.94f,
            highlightClipFraction = 0.004f,
            redHighlightClipFraction = 0.010f
        )

        assertEquals(0f, next, 1e-6f)
    }

    @Test
    fun tinySpecularAreaCannotCreatePositiveExposure() {
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0.18f,
            averageLuma = 0.12f,
            medianLuma = 0.07f,
            p95Luma = 0.48f,
            p99Luma = 0.999f,
            p99Red = 1f,
            p99Green = 0.72f,
            p99Blue = 0.68f,
            highlightClipFraction = 0.002f,
            redHighlightClipFraction = 0.008f
        )

        assertEquals(0f, next, 1e-6f)
    }

    @Test
    fun staleRawEvidenceReleasesToNeutral() {
        assertEquals(
            0.4f,
            SensorLinearNdrExposurePolicy.resolveAppliedStops(0.4f, 1_000L),
            1e-6f
        )
        assertEquals(
            0.2f,
            SensorLinearNdrExposurePolicy.resolveAppliedStops(0.4f, 1_500L),
            1e-6f
        )
        assertEquals(
            0f,
            SensorLinearNdrExposurePolicy.resolveAppliedStops(0.4f, 2_000L),
            1e-6f
        )
    }

    @Test
    fun darkRoomAtSensorCeilingCannotRequestNoiseOnlyPositiveEv() {
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0f,
            averageLuma = 0.002f,
            medianLuma = 0.002f,
            p95Luma = 0.006f,
            p99Luma = 0.008f,
            p99Red = 0.015f,
            p99Green = 0.011f,
            p99Blue = 0.015f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f,
            sensorSensitivityIso = 3200,
            sensorSensitivityUpperIso = 3200,
            exposureTimeNs = 30_000_000L,
            frameDurationNs = 40_000_000L,
            noiseS = 0.0033f,
            noiseO = 0.0001f
        )

        assertEquals(0f, next, 1e-6f)
    }

    @Test
    fun stalePositiveLiftReleasesQuicklyAtSensorCeiling() {
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0.67f,
            averageLuma = 0.002f,
            medianLuma = 0.002f,
            p95Luma = 0.006f,
            p99Luma = 0.008f,
            p99Red = 0.015f,
            p99Green = 0.011f,
            p99Blue = 0.015f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f,
            sensorSensitivityIso = 3200,
            sensorSensitivityUpperIso = 3200,
            exposureTimeNs = 30_000_000L,
            frameDurationNs = 40_000_000L,
            noiseS = 0.0033f,
            noiseO = 0.0001f
        )

        assertEquals(0f, next, 1e-6f)
    }

    @Test
    fun cleanSensorWithCaptureHeadroomKeepsCamera2NeutralBaseline() {
        val next = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0f,
            averageLuma = 0.06f,
            medianLuma = 0.025f,
            p95Luma = 0.28f,
            p99Luma = 0.48f,
            p99Red = 0.50f,
            p99Green = 0.47f,
            p99Blue = 0.45f,
            highlightClipFraction = 0f,
            redHighlightClipFraction = 0f,
            sensorSensitivityIso = 200,
            sensorSensitivityUpperIso = 3200,
            exposureTimeNs = 5_000_000L,
            frameDurationNs = 33_333_333L,
            noiseS = 0.0002f,
            noiseO = 0.000002f
        )

        assertEquals(0f, next, 1e-6f)
    }

    @Test
    fun delayedClippedAndUnclippedFramesNeverAlternateAcrossNeutral() {
        val protected = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = 0f,
            averageLuma = 0.157f,
            medianLuma = 0.033f,
            p95Luma = 0.742f,
            p99Luma = 0.808f,
            p99Red = 0.597f,
            p99Green = 1f,
            p99Blue = 0.638f,
            highlightClipFraction = 0.041f,
            redHighlightClipFraction = 0f
        )
        assertTrue(protected < 0f)

        val released = SensorLinearNdrExposurePolicy.resolveNextStops(
            currentStops = protected,
            averageLuma = 0.140f,
            medianLuma = 0.029f,
            p95Luma = 0.658f,
            p99Luma = 0.745f,
            p99Red = 0.525f,
            p99Green = 0.959f,
            p99Blue = 0.545f,
            highlightClipFraction = 0.007f,
            redHighlightClipFraction = 0f
        )

        assertTrue(released in protected..0f)
    }
}
