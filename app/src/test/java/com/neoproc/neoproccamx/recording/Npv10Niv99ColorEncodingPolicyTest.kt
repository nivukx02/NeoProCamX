package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class Npv10Niv99ColorEncodingPolicyTest {
    @Test
    fun `resolves every supported ten bit transfer without locale dependence`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709),
                Npv10Niv99ColorEncodingPolicy.resolve(config("bt.709", "bt.709"))
            )
            assertEquals(
                Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT2020_NCL, Npv10Niv99Transfer.HLG),
                Npv10Niv99ColorEncodingPolicy.resolve(config("bt.2020-ncl", "hlg"))
            )
            assertEquals(
                Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT2020_NCL, Npv10Niv99Transfer.PQ),
                Npv10Niv99ColorEncodingPolicy.resolve(config("BT.2020", "ST.2084/PQ"))
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `fails closed for unknown matrix transfer or non limited range`() {
        assertNull(Npv10Niv99ColorEncodingPolicy.resolve(config("DISPLAY-P3", "BT.709")))
        assertNull(Npv10Niv99ColorEncodingPolicy.resolve(config("BT.709", "GAMMA-UNKNOWN")))
        assertNull(
            Npv10Niv99ColorEncodingPolicy.resolve(
                config("BT.709", "BT.709", range = "FULL")
            )
        )
    }

    @Test
    fun `explicit camera fallback resolves only the documented limited bt709 encoding`() {
        val resolved = Npv10Niv99ColorEncodingPolicy.resolve(
            sessionConfig(
                mapOf("pixelColorFallbackPolicy" to "LIMITED_BT709_FAIL_CLOSED")
            )
        )
        assertEquals(
            Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709),
            resolved
        )
        assertTrue(requireNotNull(resolved).limitedRange)
    }

    private fun config(matrix: String, transfer: String, range: String = "LIMITED") =
        sessionConfig(
            mapOf(
                "pixelColorMatrix" to matrix,
                "pixelTransferEncoding" to transfer,
                "pixelColorRange" to range,
                "pixelColorFallbackPolicy" to "NONE"
            )
        )

    private fun sessionConfig(metadata: Map<String, String>) = Npv10SessionConfig(
        width = 1_920,
        height = 1_080,
        fps = 25,
        mode = "VIDEO",
        colorProfile = "Rec.709",
        startedAtEpochMs = 1L,
        sourceBitDepth = 10,
        sourceFormat = "YCBCR_P010",
        payloadFormat = Npv10PayloadFormat.P010_LE,
        packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
        niv99ProcessingRequired = true,
        featureMetadata = metadata
    )
}
