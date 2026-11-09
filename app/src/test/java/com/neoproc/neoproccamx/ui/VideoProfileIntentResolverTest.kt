package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraStreamSize
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoProfileIntentResolverTest {

    @Test
    fun `pro mode chooses largest exposed recording size instead of fixed four k`() {
        val targetSize = VideoProfileTargetSizePolicy.resolveTargetStreamSize(
            availableSizes = listOf(CameraStreamSize(7680, 4320), CameraStreamSize(3840, 2160)),
            pixelArraySize = CameraStreamSize(8192, 6144),
            maxRawSize = CameraStreamSize(4096, 3072),
            fallbackSensorSize = CameraStreamSize(4096, 3072),
            rawEnabled = false,
            rawSupported = true
        )

        assertSizeEquals(7680, 4320, targetSize)
    }

    @Test
    fun `hdr request chooses largest exposed recording size`() {
        val targetSize = VideoProfileTargetSizePolicy.resolveTargetStreamSize(
            availableSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
            pixelArraySize = CameraStreamSize(4096, 3072),
            maxRawSize = CameraStreamSize(4096, 3072),
            fallbackSensorSize = CameraStreamSize(3840, 2160),
            rawEnabled = false,
            rawSupported = true
        )

        assertSizeEquals(4096, 3072, targetSize)
    }

    @Test
    fun `raw mode chooses maximum sensor backed size`() {
        val targetSize = VideoProfileTargetSizePolicy.resolveTargetStreamSize(
            availableSizes = listOf(CameraStreamSize(3840, 2160)),
            pixelArraySize = CameraStreamSize(8192, 6144),
            maxRawSize = CameraStreamSize(4096, 3072),
            fallbackSensorSize = CameraStreamSize(4096, 3072),
            rawEnabled = true,
            rawSupported = true
        )

        assertSizeEquals(8192, 6144, targetSize)
    }

    @Test
    fun `hdr request maps legacy hdr versions into neo dynamic range profiles`() {
        val resolver = VideoProfileIntentResolver()

        val hlg = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "HLG"
        )
        val hdr10Plus = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "HDR10+"
        )
        val dolby = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "Dolby Vision"
        )

        assertEquals("NDR10+", hlg.hdrMode)
        assertEquals("NDR10+", hdr10Plus.hdrMode)
        assertEquals("Rec.2020-NDR10+", hlg.colorSpace)
        assertEquals("Rec.2020-NDR10+", hdr10Plus.colorSpace)
        assertEquals("OFF", dolby.hdrMode)
        assertEquals("Rec.709", dolby.colorSpace)
    }

    @Test
    fun `normal pro and log video intents request ten bit by default`() {
        val resolver = VideoProfileIntentResolver()

        val normalSdr = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = false,
            tenBitRequested = false
        )
        val log = resolver.resolve(
            cinemaMode = CinemaVideoMode.LOG,
            hdrRequested = false,
            tenBitRequested = false
        )
        val raw = resolver.resolve(
            cinemaMode = CinemaVideoMode.RAW,
            rawEnabled = true,
            rawSupported = true,
            hdrRequested = false,
            tenBitRequested = false
        )

        assertEquals(true, normalSdr.is10Bit)
        assertEquals("Rec.709", normalSdr.colorSpace)
        assertEquals(true, log.is10Bit)
        assertEquals("Rec.2020", log.colorSpace)
        assertEquals(false, raw.is10Bit)
        assertEquals("RAW", raw.colorSpace)
    }

    @Test
    fun `apex hdr intent preserves project owned apex pq color space`() {
        val resolver = VideoProfileIntentResolver()

        val sharp = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "APEX Sharp",
            tenBitRequested = false
        )
        val zero = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "APEX Zero",
            tenBitRequested = false
        )
        val cinema = resolver.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            hdrRequested = true,
            requestedHdrMode = "APEX Cinema",
            tenBitRequested = false
        )

        assertEquals("APEX_SHARP", sharp.hdrMode)
        assertEquals("APEX_ZERO", zero.hdrMode)
        assertEquals("APEX_CINEMA", cinema.hdrMode)
        assertEquals("Rec.2020-NIVUKX-APEX", sharp.colorSpace)
        assertEquals("Rec.2020-NIVUKX-APEX", zero.colorSpace)
        assertEquals("Rec.2020-NIVUKX-APEX", cinema.colorSpace)
        assertEquals(true, sharp.is10Bit)
    }

    private fun assertSizeEquals(expectedWidth: Int, expectedHeight: Int, actual: CameraStreamSize) {
        assertEquals(expectedWidth, actual.width)
        assertEquals(expectedHeight, actual.height)
    }
}
