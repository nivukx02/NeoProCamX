package com.neoproc.neoproccamx.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s9 / CP-QV22 — locks the pure config-builder the dormant custom-EIS consumer seam depends on. The
 * controller maths are already golden-locked (CP-QV18); this only verifies the geometry derivation and its
 * documented fallbacks, so the (device-gated) seam is never built with a degenerate/NaN grid.
 */
class StabilizationSessionConfigPolicyTest {

    @Test
    fun focalLengthPxIsPinholeScaleFromPhysicalGeometry() {
        // f_px = f_mm / sensorWidth_mm * activeWidth_px.  4mm / 6mm * 4000px = 2666.67px.
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f,
            sensorWidthMm = 6f,
            activeArrayWidthPx = 4000,
            activeArrayHeightPx = 3000,
        )
        assertEquals(4f / 6f * 4000f, cfg.focalLengthPx, 1e-3f)
    }

    @Test
    fun rowsCenterAndReferenceRowComeFromActiveArray() {
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f,
            sensorWidthMm = 6f,
            activeArrayWidthPx = 4000,
            activeArrayHeightPx = 3000,
        )
        assertEquals("rows == active-array height", 3000, cfg.rows)
        assertEquals("reference row is the centre row", 1500, cfg.referenceRow)
        assertEquals("centreX is the active-array centre", 2000f, cfg.centerX, 0f)
        assertEquals("centreY is the active-array centre", 1500f, cfg.centerY, 0f)
    }

    @Test
    fun cropMarginIsFractionOfSmallerActiveArrayDimension() {
        // min(4000,3000)=3000 * 0.08 default fraction = 240px.
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f,
            sensorWidthMm = 6f,
            activeArrayWidthPx = 4000,
            activeArrayHeightPx = 3000,
        )
        assertEquals(3000f * StabilizationSessionConfigPolicy.DEFAULT_CROP_MARGIN_FRACTION, cfg.cropMarginPixels, 1e-3f)
    }

    @Test
    fun defaultCutoffIsApplied() {
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f,
            sensorWidthMm = 6f,
            activeArrayWidthPx = 4000,
            activeArrayHeightPx = 3000,
        )
        assertEquals(StabilizationSessionConfigPolicy.DEFAULT_CUTOFF_HZ, cfg.cutoffHz, 0f)
    }

    @Test
    fun unreportedPhysicalSizeFallsBackToDefaultFocalPx() {
        // LIMITED devices leave SENSOR_INFO_PHYSICAL_SIZE / focal length null → 0f in → fallback focal px,
        // never a zero/NaN scale.
        val noSensor = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 0f, sensorWidthMm = 0f,
            activeArrayWidthPx = 1920, activeArrayHeightPx = 1080,
        )
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_FOCAL_LENGTH_PX, noSensor.focalLengthPx, 0f)

        val noFocal = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 0f, sensorWidthMm = 6f,
            activeArrayWidthPx = 1920, activeArrayHeightPx = 1080,
        )
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_FOCAL_LENGTH_PX, noFocal.focalLengthPx, 0f)

        val noWidth = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f, sensorWidthMm = 0f,
            activeArrayWidthPx = 1920, activeArrayHeightPx = 1080,
        )
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_FOCAL_LENGTH_PX, noWidth.focalLengthPx, 0f)
    }

    @Test
    fun unreportedActiveArrayFallsBackToSaneResolutionAndNonDegenerateGrid() {
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f, sensorWidthMm = 6f,
            activeArrayWidthPx = 0, activeArrayHeightPx = 0,
        )
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_ACTIVE_HEIGHT_PX, cfg.rows)
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_ACTIVE_HEIGHT_PX / 2, cfg.referenceRow)
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_ACTIVE_WIDTH_PX * 0.5f, cfg.centerX, 0f)
        assertEquals(StabilizationSessionConfigPolicy.FALLBACK_ACTIVE_HEIGHT_PX * 0.5f, cfg.centerY, 0f)
        // Focal px uses the fallback active width, still a positive finite scale.
        assertTrue("focal px must stay finite/positive", cfg.focalLengthPx > 0f && cfg.focalLengthPx.isFinite())
        assertTrue("rows must be >= 1 (never a degenerate grid)", cfg.rows >= 1)
    }

    @Test
    fun builtConfigDrivesAnIdentityGridByDefaultOffMode() {
        // The whole point of the seam: even a fully-built config produces an OFF (identity) grid until the
        // controller mode is flipped device-side. No pixel is touched by wiring alone.
        val cfg = StabilizationSessionConfigPolicy.build(
            focalLengthMm = 4f, sensorWidthMm = 6f,
            activeArrayWidthPx = 1920, activeArrayHeightPx = 1080,
        )
        val ctrl = StabilizationSessionController(cfg)
        assertEquals(StabilizationModePolicy.StabilizationMode.OFF, ctrl.mode)
        val grid = ctrl.onFrame(
            orientation = EisFrameOrientation(
                frameMidTimestampNs = 1_000_000L,
                orientationAtFrameMid = Quaternion.fromAxisAngle(0f, 1f, 0f, 0.3f),
                acrossExposureRotation = Quaternion.IDENTITY,
            ),
            samples = emptyList(),
            frameStartNs = 0L,
            rowReadoutNs = 0L,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
        )
        assertTrue("default-OFF built config must yield an identity grid", grid.isIdentity)
        assertEquals(cfg.rows, grid.rows)
    }
}
