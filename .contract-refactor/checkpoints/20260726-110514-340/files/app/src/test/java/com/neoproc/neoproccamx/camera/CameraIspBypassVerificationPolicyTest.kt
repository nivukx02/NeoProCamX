package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP17 — pure-JVM verification of [CameraIspBypassVerificationPolicy].
 *
 * Synthetic requested/reported ISP-block snapshots model the three honesty outcomes the device can
 * produce — honored, silently re-enabled, and unavailable (key absent) — without any Android
 * instance dependency. Only the compile-time CaptureRequest.* mode constants are referenced.
 * Test-only: no production code is changed.
 */
class CameraIspBypassVerificationPolicyTest {

    // Canonical strict-bypass request: every public block OFF + identity-curve tonemap + native geometry.
    private fun bypassRequest(
        edgeMode: Int? = CaptureRequest.EDGE_MODE_OFF,
        noiseReductionMode: Int? = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
        hotPixelMode: Int? = CaptureRequest.HOT_PIXEL_MODE_OFF,
        shadingMode: Int? = CaptureRequest.SHADING_MODE_OFF,
        colorAberrationMode: Int? = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
        tonemapMode: Int? = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
        distortionCorrectionMode: Int? = CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
        expectsLinearTonemapCurve: Boolean = true
    ) = IspBypassRequestedModes(
        edgeMode = edgeMode,
        noiseReductionMode = noiseReductionMode,
        hotPixelMode = hotPixelMode,
        shadingMode = shadingMode,
        colorAberrationMode = colorAberrationMode,
        tonemapMode = tonemapMode,
        distortionCorrectionMode = distortionCorrectionMode,
        expectsLinearTonemapCurve = expectsLinearTonemapCurve
    )

    // Reported snapshot that honors the canonical bypass request exactly.
    private fun honoredReport(
        edgeMode: Int? = CaptureRequest.EDGE_MODE_OFF,
        noiseReductionMode: Int? = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
        hotPixelMode: Int? = CaptureRequest.HOT_PIXEL_MODE_OFF,
        shadingMode: Int? = CaptureRequest.SHADING_MODE_OFF,
        colorAberrationMode: Int? = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
        tonemapMode: Int? = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
        distortionCorrectionMode: Int? = CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
        tonemapCurveLinear: Boolean? = true
    ) = IspBypassReportedModes(
        edgeMode = edgeMode,
        noiseReductionMode = noiseReductionMode,
        hotPixelMode = hotPixelMode,
        shadingMode = shadingMode,
        colorAberrationMode = colorAberrationMode,
        tonemapMode = tonemapMode,
        distortionCorrectionMode = distortionCorrectionMode,
        tonemapCurveLinear = tonemapCurveLinear
    )

    @Test
    fun `fully honored bypass is proven with sensor-native geometry`() {
        val report = CameraIspBypassVerificationPolicy.verify(bypassRequest(), honoredReport())

        assertEquals(IspBypassHonestyVerdict.PROVEN, report.aggregate)
        assertTrue(report.proven)
        assertTrue(report.linearTonemapProven)
        assertEquals(IspBypassGeometry.SENSOR_NATIVE, report.geometry)
        assertNull(report.warning)
        IspBypassBlock.entries.forEach { block ->
            assertEquals("block $block", IspBypassBlockVerdict.HONORED, report.verdictOf(block))
        }
    }

    @Test
    fun `silently re-enabled noise reduction is not honored and downgrades the aggregate`() {
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertFalse(report.proven)
        assertEquals(IspBypassBlockVerdict.NOT_HONORED, report.verdictOf(IspBypassBlock.NOISE_REDUCTION))
        assertEquals(IspBypassBlockVerdict.HONORED, report.verdictOf(IspBypassBlock.EDGE))
        val warning = report.warning!!
        assertTrue(warning.contains("NOISE_REDUCTION"))
        assertTrue(warning.contains("NOT PROVEN"))
    }

    @Test
    fun `block absent from the result lands unavailable and cannot be proven`() {
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(shadingMode = null)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertEquals(IspBypassBlockVerdict.UNAVAILABLE, report.verdictOf(IspBypassBlock.SHADING))
        val warning = report.warning!!
        assertTrue(warning.contains("SHADING"))
        assertTrue(warning.contains("UNAVAILABLE"))
    }

    @Test
    fun `contrast-curve mode honored but non-identity reported curve is not honored`() {
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(tonemapCurveLinear = false)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertFalse(report.linearTonemapProven)
        assertEquals(IspBypassBlockVerdict.NOT_HONORED, report.verdictOf(IspBypassBlock.TONEMAP))
        assertTrue(report.warning!!.contains("TONEMAP"))
    }

    @Test
    fun `unverifiable tonemap curve cannot be claimed as linear`() {
        // Mode honored, but the device did not report a curve we could check (null).
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(tonemapCurveLinear = null)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertFalse(report.linearTonemapProven)
        // The mode itself still matched, so the block verdict stays HONORED…
        assertEquals(IspBypassBlockVerdict.HONORED, report.verdictOf(IspBypassBlock.TONEMAP))
        // …but the aggregate is still NOT_PROVEN and the warning explains why.
        assertTrue(report.warning!!.contains("TONEMAP_CURVE=NOT_LINEAR"))
    }

    @Test
    fun `tonemap without a requested linear curve is proven on mode match alone`() {
        val request = bypassRequest(expectsLinearTonemapCurve = false)
        val report = CameraIspBypassVerificationPolicy.verify(
            request,
            honoredReport(tonemapCurveLinear = null)
        )

        assertEquals(IspBypassHonestyVerdict.PROVEN, report.aggregate)
        assertTrue(report.linearTonemapProven)
        assertEquals(IspBypassBlockVerdict.HONORED, report.verdictOf(IspBypassBlock.TONEMAP))
        assertNull(report.warning)
    }

    @Test
    fun `processed high-quality request is never reported as a bypass`() {
        val processed = IspBypassRequestedModes(
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            colorAberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            expectsLinearTonemapCurve = false
        )
        val report = CameraIspBypassVerificationPolicy.verify(
            processed,
            honoredReport(
                edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
                shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
                colorAberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                tonemapCurveLinear = null
            )
        )

        assertEquals(IspBypassHonestyVerdict.NOT_APPLICABLE, report.aggregate)
        assertFalse(report.proven)
        assertNull(report.warning)
        assertEquals(IspBypassGeometry.CORRECTED, report.geometry)
        IspBypassBlock.entries.forEach { block ->
            assertEquals("block $block", IspBypassBlockVerdict.NOT_REQUESTED, report.verdictOf(block))
        }
    }

    @Test
    fun `corrected geometry bypass is proven when the device honors the on mode`() {
        val request = bypassRequest(
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        )
        val report = CameraIspBypassVerificationPolicy.verify(
            request,
            honoredReport(distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        )

        assertEquals(IspBypassHonestyVerdict.PROVEN, report.aggregate)
        assertEquals(IspBypassGeometry.CORRECTED, report.geometry)
        assertEquals(IspBypassBlockVerdict.HONORED, report.verdictOf(IspBypassBlock.DISTORTION_CORRECTION))
    }

    @Test
    fun `a block the request never set is not counted against the proof`() {
        // Device did not advertise the aberration OFF mode, so the request skipped it (null), and the
        // result likewise carries nothing — that must not block PROVEN.
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(colorAberrationMode = null),
            honoredReport(colorAberrationMode = null)
        )

        assertEquals(IspBypassHonestyVerdict.PROVEN, report.aggregate)
        assertEquals(IspBypassBlockVerdict.NOT_REQUESTED, report.verdictOf(IspBypassBlock.COLOR_ABERRATION))
    }

    @Test
    fun `geometry mapping from mode is exact`() {
        assertEquals(
            IspBypassGeometry.SENSOR_NATIVE,
            CameraIspBypassVerificationPolicy.geometryOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        )
        assertEquals(
            IspBypassGeometry.CORRECTED,
            CameraIspBypassVerificationPolicy.geometryOf(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
        )
        assertEquals(
            IspBypassGeometry.CORRECTED,
            CameraIspBypassVerificationPolicy.geometryOf(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        )
        assertEquals(IspBypassGeometry.UNKNOWN, CameraIspBypassVerificationPolicy.geometryOf(null as Int?))
    }

    @Test
    fun `geometry is reported-derived - a silently corrected frame is never stamped sensor-native`() {
        // We requested DISTORTION_CORRECTION OFF, but the HAL re-enabled it (reported FAST). The
        // recorded geometry must reflect what the device actually did, not what we asked for.
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertEquals(IspBypassBlockVerdict.NOT_HONORED, report.verdictOf(IspBypassBlock.DISTORTION_CORRECTION))
        assertEquals(IspBypassGeometry.CORRECTED, report.geometry)
    }

    @Test
    fun `geometry is unknown when the device reports no distortion value`() {
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(),
            honoredReport(distortionCorrectionMode = null)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertEquals(IspBypassBlockVerdict.UNAVAILABLE, report.verdictOf(IspBypassBlock.DISTORTION_CORRECTION))
        assertEquals(IspBypassGeometry.UNKNOWN, report.geometry)
    }

    @Test
    fun `reported contrast curve that is not the identity ramp is never a proven no-op`() {
        // Even when no linear curve was *requested*, a device that reports CONTRAST_CURVE with a
        // non-identity curve is doing active tone shaping and must not read PROVEN.
        val report = CameraIspBypassVerificationPolicy.verify(
            bypassRequest(expectsLinearTonemapCurve = false),
            honoredReport(tonemapCurveLinear = false)
        )

        assertEquals(IspBypassHonestyVerdict.NOT_PROVEN, report.aggregate)
        assertEquals(IspBypassBlockVerdict.NOT_HONORED, report.verdictOf(IspBypassBlock.TONEMAP))
    }
}
