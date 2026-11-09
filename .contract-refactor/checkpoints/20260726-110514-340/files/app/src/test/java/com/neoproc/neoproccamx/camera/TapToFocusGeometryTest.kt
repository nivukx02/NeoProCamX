package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f02 (CP-U2) — manual-controls tap-to-focus geometry contract: a known preview tap maps to the
 * expected sensor point, and the metering box is the documented fraction of the active array. Locks
 * the same pure-JVM goldens as TapFocusGeometryGoldenTest from the manual-controls perspective so a
 * mapper regression is caught. Uses only the CameraRect/Pair overloads (no android.graphics).
 */
class TapToFocusGeometryTest {

    private fun geometry(
        rotation: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    ) = FrameGeometry(
        sensorOrientationDegrees = rotation,
        deviceRotationDegrees = 0,
        finalRotationDegrees = rotation,
        lensFacing = lensFacing,
        sensorAspectRatio = 4f / 3f,
        outputAspectRatio = null,
        cropRegion = null,
        normalizedCropRegion = NormalizedCropRegion.FULL
    )

    @Test
    fun knownTapMapsToExpectedSensorPoint() {
        val (sx, sy) = geometry(0).previewNormalizedPointToSensorNormalized(0.25f, 0.10f)
        assertEquals(0.25f, sx, 1e-4f)
        assertEquals(0.10f, sy, 1e-4f)
    }

    @Test
    fun tapMeteringBoxIsDocumentedFractionOfActiveArray() {
        val array = CameraRect(0, 0, 4000, 3000)
        // FOCUS_WINDOW_RATIO 0.08 → 320×240 centred box.
        assertEquals(CameraRect(1840, 1380, 2160, 1620), CameraFocusControl.buildTapMeteringRegion(array, 0.5f, 0.5f, 0.08f))
        // EXPOSURE_WINDOW_RATIO 0.24 → 960×720 box.
        assertEquals(CameraRect(1520, 1140, 2480, 1860), CameraFocusControl.buildTapMeteringRegion(array, 0.5f, 0.5f, 0.24f))
    }
}
