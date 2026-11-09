package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/**
 * Contract for [RecordDevelopSidecarFactory] — the pure record-stop metadata assembler. Locks the colour-model
 * decisions the offline develop relies on: working matrix chosen by colour space, LIGHT-lane transfer (SDR off /
 * HLG or negotiated PQ for NDR), the lux→L_A convention, WB pass-through, and CCT/tint defaults. Also
 * confirms the built metadata survives the [VideoDevelopColorSidecar] binary round-trip.
 */
class RecordDevelopSidecarFactoryTest {

    @Test
    fun sdrOffUsesRec709MatrixAndSdrTransfer() {
        val meta = RecordDevelopSidecarFactory.build(
            width = 4096, height = 3072, fps = 30, storageBitDepth = 10,
            colorSpaceLabel = "Rec.709", hdrModeLabel = "OFF", colorProfile = "Natural"
        )
        assertEquals(VideoDevelopColorSidecar.DevelopColorSpace.REC709, meta.colorSpace)
        assertEquals(VideoDevelopColorSidecar.DevelopColorTransfer.SDR, meta.colorTransfer)
        assertArrayEquals(SnceIlluminantEstimator.REC709_TO_XYZ, meta.workingRgbToXyz, 0f)
        // No measured white point / lux -> develop-friendly defaults.
        assertEquals(5500f, meta.cctKelvin, 0f)
        assertEquals(0f, meta.tint, 0f)
        assertEquals(0f, meta.adaptingLuminance, 0f)
    }

    @Test
    fun ndrUsesRec2020MatrixAndHlgTransferNotPq() {
        val meta = RecordDevelopSidecarFactory.build(
            width = 4096, height = 3072, fps = 30, storageBitDepth = 10,
            colorSpaceLabel = "Rec.2020", hdrModeLabel = "NDR10+", colorProfile = "NIVUKX"
        )
        assertEquals(VideoDevelopColorSidecar.DevelopColorSpace.REC2020, meta.colorSpace)
        // Record-then-develop keeps the NDR intermediate HLG-coherent; PQ is applied OFFLINE, never here.
        assertEquals(VideoDevelopColorSidecar.DevelopColorTransfer.HLG, meta.colorTransfer)
        assertArrayEquals(SnceIlluminantEstimator.REC2020_TO_XYZ, meta.workingRgbToXyz, 0f)
    }

    @Test
    fun luxConvertsToAdaptingLuminanceWithLivePipelineConvention() {
        val lux = 6280f
        val meta = RecordDevelopSidecarFactory.build(
            width = 1920, height = 1080, fps = 30, storageBitDepth = 10,
            colorSpaceLabel = "Rec.709", hdrModeLabel = "OFF", colorProfile = "Natural",
            estimatedLux = lux
        )
        val expected = (lux / PI.toFloat()).coerceIn(1f, 1000f)
        assertEquals(expected, meta.adaptingLuminance, 1e-3f)
    }

    @Test
    fun whiteBalanceAndWhitePointArePassedThrough() {
        val meta = RecordDevelopSidecarFactory.build(
            width = 4096, height = 3072, fps = 30, storageBitDepth = 10,
            colorSpaceLabel = "Rec.709", hdrModeLabel = "OFF", colorProfile = "Natural",
            wbR = 1.85f, wbGr = 1.0f, wbGb = 1.0f, wbB = 1.62f,
            kelvin = 5200, tint = 4
        )
        assertEquals(1.85f, meta.whiteBalanceR, 0f)
        assertEquals(1.0f, meta.whiteBalanceGr, 0f)
        assertEquals(1.0f, meta.whiteBalanceGb, 0f)
        assertEquals(1.62f, meta.whiteBalanceB, 0f)
        assertEquals(5200f, meta.cctKelvin, 0f)
        assertEquals(4f, meta.tint, 0f)
    }

    @Test
    fun builtMetadataSurvivesSidecarRoundTrip() {
        val meta = RecordDevelopSidecarFactory.build(
            width = 4096, height = 3072, fps = 30, storageBitDepth = 10,
            colorSpaceLabel = "Rec.2020", hdrModeLabel = "NDR10+", colorProfile = "NIVUKX",
            detailProfile = "FOCUSED_DETAIL",
            wbR = 1.9f, wbGr = 1.0f, wbGb = 1.0f, wbB = 1.7f, kelvin = 5400, tint = -2, estimatedLux = 900f
        )
        val decoded = VideoDevelopColorSidecar.decode(VideoDevelopColorSidecar.encode(meta))
        assertEquals(meta, decoded)
        assertEquals("FOCUSED_DETAIL", decoded.detailProfileTag)
    }
}
