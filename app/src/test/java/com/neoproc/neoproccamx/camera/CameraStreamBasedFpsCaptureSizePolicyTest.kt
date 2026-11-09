package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStreamBasedFpsCaptureSizePolicyTest {
    private val npv10 = CameraStreamSize(3840, 2160)
    private val raw = CameraStreamSize(4096, 3072)
    private val encoder = CameraStreamSize(1920, 1080)

    @Test
    fun `raw prearm and active capture resolve the same session fps size`() {
        fun resolve(capturing: Boolean) = CameraStreamBasedFpsCaptureSizePolicy.resolve(
            npv10CaptureEnabled = false,
            npv10CaptureSize = null,
            rawPrearmEnabled = true,
            rawCaptureEnabled = capturing,
            rawReadoutSize = raw,
            encoderSize = if (capturing) null else encoder
        )

        assertEquals(raw, resolve(capturing = false))
        assertEquals(raw, resolve(capturing = true))
    }

    @Test
    fun `npv10 capture remains highest priority and encoder remains default`() {
        assertEquals(
            npv10,
            CameraStreamBasedFpsCaptureSizePolicy.resolve(
                npv10CaptureEnabled = true,
                npv10CaptureSize = npv10,
                rawPrearmEnabled = true,
                rawCaptureEnabled = true,
                rawReadoutSize = raw,
                encoderSize = encoder
            )
        )
        assertEquals(
            encoder,
            CameraStreamBasedFpsCaptureSizePolicy.resolve(
                npv10CaptureEnabled = false,
                npv10CaptureSize = null,
                rawPrearmEnabled = false,
                rawCaptureEnabled = false,
                rawReadoutSize = raw,
                encoderSize = encoder
            )
        )
    }
}
