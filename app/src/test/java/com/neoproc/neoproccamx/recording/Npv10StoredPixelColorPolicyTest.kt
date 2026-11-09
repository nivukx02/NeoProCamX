package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10StoredPixelColorPolicyTest {
    @Test
    fun verifiedBt709InputOverridesRequestedPqOutputTagWhenNoTransformExists() {
        val header = header(
            completePacket(
                transfer = "BT.709",
                primaries = "BT.709",
                matrix = "BT.709"
            ) + mapOf(
                "transfer" to "ST.2084",
                "transport" to Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10,
                "selectedcolorspace" to "Rec.2020-NDR10+",
                "selectedcolorscience" to "NDR10+",
                "recordingmode" to "HDR"
            )
        )

        val stored = Npv10StoredPixelColorPolicy.resolve(header)
        val output = Npv10MovColorTagPolicy.resolve(header)

        assertTrue(stored.schemaPresent)
        assertTrue(stored.verified)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, output.colorConfig.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, output.colorConfig.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, output.colorConfig.matrixCoefficients)
        assertEquals("REC709_SDR", output.activeTransport)
    }

    @Test
    fun verifiedBt2020SdrInputIsRepresentedExactly() {
        val header = header(
            completePacket(
                transfer = "BT.709",
                primaries = "BT.2020",
                matrix = "BT.2020-NCL"
            )
        )

        val output = Npv10MovColorTagPolicy.resolve(header)

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, output.colorConfig.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, output.colorConfig.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, output.colorConfig.matrixCoefficients)
        assertEquals("BT2020_SDR", output.activeTransport)
    }

    @Test
    fun verifiedPqInputRetainsPqOutputTags() {
        val header = header(
            completePacket(
                transfer = "ST.2084",
                primaries = "BT.2020",
                matrix = "BT.2020-NCL"
            )
        )

        val output = Npv10MovColorTagPolicy.resolve(header)

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, output.colorConfig.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, output.colorConfig.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, output.colorConfig.matrixCoefficients)
        assertEquals(Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10, output.activeTransport)
    }

    @Test
    fun invalidCompletePacketFailsClosedForInputAndOutput() {
        val metadata = completePacket(
            transfer = "BT.709",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL"
        ).toMutableMap().apply {
            remove("pixelcolorrange")
        }
        val header = header(metadata)

        val stored = Npv10StoredPixelColorPolicy.resolve(header)
        val output = Npv10MovColorTagPolicy.resolve(header)

        assertTrue(stored.schemaPresent)
        assertFalse(stored.verified)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, output.colorConfig.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, output.colorConfig.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, output.colorConfig.matrixCoefficients)
        assertEquals("REC709_SDR", output.activeTransport)
    }

    @Test
    fun legacyMasterWithoutPixelColorSchemaRetainsLegacyResolver() {
        val header = header(
            mapOf(
                "transfer" to "ST.2084",
                "transport" to Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10,
                "selectedcolorspace" to "Rec.2020",
                "recordingmode" to "HDR"
            )
        )

        val stored = Npv10StoredPixelColorPolicy.resolve(header)
        val output = Npv10MovColorTagPolicy.resolve(header)

        assertFalse(stored.schemaPresent)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, output.colorConfig.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, output.colorConfig.transferCharacteristics)
    }

    @Test
    fun fullRangeP010CannotBeRelabelledLimitedForV210OrHevc() {
        val fullRange = QuickTimeColorAtomConfig.rec709SdrLimited().copy(fullRange = true)

        listOf(
            Npv10MovConverter.OutputFormat.V210_422,
            Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
        ).forEach { format ->
            val failure = runCatching {
                Npv10MovConverter.colorConfigForOutput(fullRange, format)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("refusing metadata-only range downgrade"))
        }
    }

    @Test
    fun r210PerformsItsExistingRgbExpansionAndDeclaresFullRange() {
        val limited = QuickTimeColorAtomConfig.rec709SdrLimited()
        val output = Npv10MovConverter.colorConfigForOutput(
            limited,
            Npv10MovConverter.OutputFormat.R210_444
        )

        assertTrue(output.fullRange)
        assertEquals(limited.colorPrimaries, output.colorPrimaries)
        assertEquals(limited.transferCharacteristics, output.transferCharacteristics)
    }

    private fun completePacket(
        transfer: String,
        primaries: String,
        matrix: String
    ): Map<String, String> = mapOf(
        "pixeltransferencoding" to transfer,
        "pixeltransferapplied" to "true",
        "pixeltransferauthority" to "IMAGE_DATASPACE",
        "pixelcolorprimaries" to primaries,
        "pixelcolormatrix" to matrix,
        "pixelcolorrange" to "LIMITED",
        "pixelcolorapplied" to "true",
        "pixelcolorauthority" to "IMAGE_DATASPACE"
    )

    private fun header(metadata: Map<String, String>) = Npv10FileHeader(
        version = Npv10ContainerPolicy.VERSION,
        width = 2,
        height = 2,
        fps = 30,
        bitDepth = 10,
        metadata = metadata.entries.joinToString("\n") { (key, value) -> "$key=$value" },
        metadataValues = metadata,
        payloadFormat = Npv10PayloadFormat.P010_LE,
        sourceFormat = "ycbcr_p010",
        sourceBitDepth = 10,
        storageBitDepth = 10,
        exactSelectedResolution = true
    )
}
