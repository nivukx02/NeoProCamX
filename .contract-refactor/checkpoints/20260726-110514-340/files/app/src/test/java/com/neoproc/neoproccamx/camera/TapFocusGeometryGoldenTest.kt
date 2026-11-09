package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f02 / CP-F3 — golden-lock + harden tap-to-focus geometry, region sizing/clamping, and the
 * single-trigger contract. Pure characterization: no production code changes, so the live
 * preview/record request path is byte-for-byte unchanged (no-downgrade by construction).
 *
 * Spatial accuracy (preview→sensor exact + round-trippable) is locked across the full transform
 * matrix {orientation 0/90/180/270 × back/front mirror × crop}; region geometry is locked to the
 * documented FOCUS=0.08 / EXPOSURE=0.24 active-array fractions with clean edge clamping; the
 * single-AF-trigger-per-tap and maxAfRegions==0 fallback contracts are locked on the pure policy.
 *
 * The tap-overrides-AI-tracking precedence and the single CONTROL_AF_TRIGGER_START state machine
 * are locked separately by [CameraControllerTapFocusContractTest] (triggerTapAF clears AI/lens
 * overrides before resolving; triggerSubjectAutoFocus forces single-shot AF before the trigger).
 */
class TapFocusGeometryGoldenTest {

    private val eps = 1e-4f

    private fun geometry(
        finalRotation: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
        crop: NormalizedCropRegion = NormalizedCropRegion.FULL
    ) = FrameGeometry(
        sensorOrientationDegrees = finalRotation,
        deviceRotationDegrees = 0,
        finalRotationDegrees = finalRotation,
        lensFacing = lensFacing,
        sensorAspectRatio = 4f / 3f,
        outputAspectRatio = null,
        cropRegion = null,
        normalizedCropRegion = crop
    )

    // Local mirror of FrameGeometry.applyRotation (private) so the round-trip test documents the
    // exact inverse: forward applies rotation by -previewRotation; the inverse applies +previewRotation.
    private fun applyRotation(x: Float, y: Float, degrees: Int): Pair<Float, Float> {
        return when (((degrees % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
    }

    // ── Exact preview→sensor mapping golden across {orientation × mirror}, FULL crop ────────────

    @Test
    fun `preview to sensor mapping is exact across orientation and mirror`() {
        val px = 0.25f
        val py = 0.10f
        // Hand-derived from previewNormalizedPointToSensorNormalized: mirror (front) → x=1-x, then
        // applyRotation(x, y, -previewRotation), then crop-map (identity for FULL).
        val back = mapOf(
            0 to (0.25f to 0.10f),
            90 to (0.10f to 0.75f),
            180 to (0.75f to 0.90f),
            270 to (0.90f to 0.25f)
        )
        val front = mapOf(
            0 to (0.75f to 0.10f),
            90 to (0.10f to 0.25f),
            180 to (0.25f to 0.90f),
            270 to (0.90f to 0.75f)
        )
        for ((rot, expected) in back) {
            val (sx, sy) = geometry(rot).previewNormalizedPointToSensorNormalized(px, py)
            assertEquals("back rot=$rot sx", expected.first, sx, eps)
            assertEquals("back rot=$rot sy", expected.second, sy, eps)
        }
        for ((rot, expected) in front) {
            val (sx, sy) = geometry(rot, CameraCharacteristics.LENS_FACING_FRONT)
                .previewNormalizedPointToSensorNormalized(px, py)
            assertEquals("front rot=$rot sx", expected.first, sx, eps)
            assertEquals("front rot=$rot sy", expected.second, sy, eps)
        }
    }

    @Test
    fun `preview to sensor round trips within rounding across the full transform matrix`() {
        val points = listOf(0.20f to 0.15f, 0.5f to 0.5f, 0.80f to 0.65f)
        val crops = listOf(
            NormalizedCropRegion.FULL,
            NormalizedCropRegion(0f, 0.125f, 1f, 0.875f) // 16:9 letterbox crop of a 4:3 sensor
        )
        for (facing in listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)) {
            for (rot in listOf(0, 90, 180, 270)) {
                for (crop in crops) {
                    val g = geometry(rot, facing, crop)
                    val mirrored = facing == CameraCharacteristics.LENS_FACING_FRONT
                    for ((px, py) in points) {
                        val (sx, sy) = g.previewNormalizedPointToSensorNormalized(px, py)
                        // Inverse: un-crop, rotate by +previewRotation, un-mirror.
                        val ux = ((sx - crop.left) / crop.width).coerceIn(0f, 1f)
                        val uy = ((sy - crop.top) / crop.height).coerceIn(0f, 1f)
                        val (rx, ry) = applyRotation(ux, uy, rot)
                        val backX = if (mirrored) 1f - rx else rx
                        assertEquals("facing=$facing rot=$rot crop=$crop x", px, backX, 1e-3f)
                        assertEquals("facing=$facing rot=$rot crop=$crop y", py, ry, 1e-3f)
                    }
                }
            }
        }
    }

    // ── Deterministic region geometry: documented active-array fractions + edge clamping ────────

    @Test
    fun `tap metering box is the documented fraction of the active array`() {
        val array = CameraRect(0, 0, 4000, 3000)
        // FOCUS_WINDOW_RATIO = 0.08 → half-extents 160×120 → 320×240 box centered at the tap.
        val focus = CameraFocusControl.buildTapMeteringRegion(array, 0.5f, 0.5f, 0.08f)
        assertEquals(CameraRect(1840, 1380, 2160, 1620), focus)
        assertEquals(320, focus.width)
        assertEquals(240, focus.height)
        // EXPOSURE_WINDOW_RATIO = 0.24 → 960×720 box.
        val exposure = CameraFocusControl.buildTapMeteringRegion(array, 0.5f, 0.5f, 0.24f)
        assertEquals(CameraRect(1520, 1140, 2480, 1860), exposure)
        assertEquals(960, exposure.width)
        assertEquals(720, exposure.height)
    }

    @Test
    fun `tap metering box clamps cleanly inside the array at every corner`() {
        val array = CameraRect(0, 0, 4000, 3000)
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0.5f)
        for ((x, y) in corners) {
            val r = CameraFocusControl.buildTapMeteringRegion(array, x, y, 0.08f)
            assertTrue("left in bounds @($x,$y)", r.left >= array.left && r.left < array.right)
            assertTrue("top in bounds @($x,$y)", r.top >= array.top && r.top < array.bottom)
            assertTrue("right in bounds @($x,$y)", r.right > r.left && r.right <= array.right)
            assertTrue("bottom in bounds @($x,$y)", r.bottom > r.top && r.bottom <= array.bottom)
        }
        // Exact corner goldens (clamp behaviour locked).
        assertEquals(CameraRect(0, 0, 160, 120), CameraFocusControl.buildTapMeteringRegion(array, 0f, 0f, 0.08f))
        assertEquals(CameraRect(3839, 2879, 4000, 3000), CameraFocusControl.buildTapMeteringRegion(array, 1f, 1f, 0.08f))
    }

    @Test
    fun `out of range tap coordinates are coerced before region construction`() {
        val array = CameraRect(0, 0, 4000, 3000)
        // x=2.0/y=-1.0 coerce to 1.0/0.0 → identical to the (1,0) corner box.
        assertEquals(
            CameraFocusControl.buildTapMeteringRegion(array, 1f, 0f, 0.08f),
            CameraFocusControl.buildTapMeteringRegion(array, 2f, -1f, 0.08f)
        )
    }

    // ── Single-trigger-per-tap + maxAfRegions==0 fallback contracts (pure policy) ───────────────

    @Test
    fun `af trigger fires exactly when a focus region is built`() {
        val array = CameraRect(0, 0, 4000, 3000)
        val withAf = TapFocusRequestPolicy.resolve(array, 0.5f, 0.5f, maxAfRegions = 1, maxAeRegions = 1)
        assertTrue(withAf.shouldTriggerAf)
        assertEquals(withAf.focusRegion != null, withAf.shouldTriggerAf)

        val noAf = TapFocusRequestPolicy.resolve(array, 0.5f, 0.5f, maxAfRegions = 0, maxAeRegions = 1)
        assertFalse(noAf.shouldTriggerAf)
        assertEquals(noAf.focusRegion != null, noAf.shouldTriggerAf)
    }

    @Test
    fun `maxAfRegions zero never builds an af region but still meters exposure`() {
        val array = CameraRect(0, 0, 4000, 3000)
        val r = TapFocusRequestPolicy.resolve(array, 0.5f, 0.5f, maxAfRegions = 0, maxAeRegions = 1)
        assertNull("no AF region on region-less hardware", r.focusRegion)
        assertFalse(r.shouldTriggerAf)
        assertEquals(CameraRect(1520, 1140, 2480, 1860), r.exposureRegion)
    }

    @Test
    fun `tap resolve is deterministic for identical inputs`() {
        val array = CameraRect(0, 0, 4000, 3000)
        val a = TapFocusRequestPolicy.resolve(array, 0.42f, 0.61f, maxAfRegions = 1, maxAeRegions = 1)
        val b = TapFocusRequestPolicy.resolve(array, 0.42f, 0.61f, maxAfRegions = 1, maxAeRegions = 1)
        assertEquals(a, b)
    }
}
