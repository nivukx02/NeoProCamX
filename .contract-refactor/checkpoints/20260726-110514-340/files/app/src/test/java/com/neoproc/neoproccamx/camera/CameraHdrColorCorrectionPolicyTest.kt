package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraHdrColorCorrectionPolicyTest {

    @Test
    fun `hdr requests high quality color correction when available`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            )
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY, mode)
    }

    @Test
    fun `hdr falls back to fast color correction rather than transform matrix`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_FAST, mode)
    }

    @Test
    fun `sdr does not force color correction mode`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            )
        )

        assertNull(mode)
    }

    @Test
    fun `strict processed sdr video requests high quality color correction`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = false
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY, mode)
    }

    @Test
    fun `strict processed sdr video falls back to fast color correction when high quality is unavailable`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = false
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_FAST, mode)
    }

    @Test
    fun `strict processed color correction does not override raw or manual transform paths`() {
        val rawMode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = true,
            manualColorTransformActive = false
        )
        val manualMode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = true
        )

        assertNull(rawMode)
        assertNull(manualMode)
    }

    @Test
    fun `hdr prefers fast color correction when the warm-red fix is enabled`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10_PLUS,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            preferFastForHdr = true
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_FAST, mode)
    }

    @Test
    fun `hdr fast preference selects fast even when available modes are empty`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
            availableColorCorrectionModes = IntArray(0),
            preferFastForHdr = true
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_FAST, mode)
    }

    @Test
    fun `hdr fast preference falls back to high quality when fast is unavailable`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HLG,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            ),
            preferFastForHdr = true
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY, mode)
    }

    @Test
    fun `strict processed sdr video keeps high quality even when hdr fast preference is enabled`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.SDR,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_FAST,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = false,
            preferFastForHdr = true
        )

        assertEquals(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY, mode)
    }

    @Test
    fun `hdr manual white balance retains its transform instead of being overwritten`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10_PLUS,
            availableColorCorrectionModes = intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            ),
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = true
        )

        assertNull(mode)
    }
}
