package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillPhotoCaptureModePolicyTest {
    @Test
    fun `raw mode always requests a native companion when supported`() {
        val plan = resolve(raw = true, rawSupported = true)

        assertEquals(StillPhotoCaptureModePolicy.Route.RAW_DNG_COMPANION, plan.route)
        assertTrue(plan.requestRawCompanion)
    }

    @Test
    fun `ndr mode uses cinema bracket while idle`() {
        val plan = resolve(hdr = "NDR10+")

        assertEquals(StillPhotoCaptureModePolicy.Route.NDR_BRACKET, plan.route)
        assertEquals("NDR10+_CINEMA", plan.hdrMode)
    }

    @Test
    fun `ndr snapshot does not run a burst during recording`() {
        val plan = resolve(hdr = "NIVUKX Vision", recording = true)

        assertEquals(StillPhotoCaptureModePolicy.Route.NDR_VIDEO_SNAPSHOT, plan.route)
    }

    @Test
    fun `log and sdr remain distinct single capture routes`() {
        assertEquals(StillPhotoCaptureModePolicy.Route.LOG_SINGLE, resolve(log = true).route)
        val sdr = resolve()
        assertEquals(StillPhotoCaptureModePolicy.Route.SDR_SINGLE, sdr.route)
        assertFalse(sdr.requestRawCompanion)
    }

    @Test
    fun `photo hdr preference is honored outside video hdr`() {
        val plan = resolve(photoHdr = "ULTRA_HDR")

        assertEquals(StillPhotoCaptureModePolicy.Route.USER_HDR_BRACKET, plan.route)
        assertEquals("ULTRA_HDR", plan.hdrMode)
    }

    private fun resolve(
        raw: Boolean = false,
        log: Boolean = false,
        hdr: String = "OFF",
        recording: Boolean = false,
        rawSupported: Boolean = true,
        photoHdr: String = "OFF"
    ) = StillPhotoCaptureModePolicy.resolve(
        rawVideoModeActive = raw,
        logVideoModeActive = log,
        effectiveHdrMode = hdr,
        recordingActive = recording,
        rawSupported = rawSupported,
        photoHdrMode = photoHdr
    )
}
