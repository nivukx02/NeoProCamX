package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.file.Files

class Npv10FileWriterTest {

    @Test
    fun writerCreatesMagicHeaderAndFrameRecords() {
        val output = Files.createTempFile("npv10-writer", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "LOG",
            colorProfile = "Neo Log",
            orientationHintDegrees = 270,
            startedAtEpochMs = 1_778_888_000_000L
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = byteArrayOf(1, 2, 3, 4),
                    uBytes = byteArrayOf(128.toByte()),
                    vBytes = byteArrayOf(128.toByte()),
                    yRowStride = 2,
                    yPixelStride = 1,
                    uRowStride = 1,
                    vRowStride = 1,
                    uPixelStride = 1,
                    vPixelStride = 1,
                    timestamp = 9_000L
                )
            )
        }

        DataInputStream(FileInputStream(output)).use { input ->
            val magic = ByteArray(Npv10ContainerPolicy.MAGIC_BYTES.size)
            input.readFully(magic)
            assertEquals("NPV10", String(magic, Charsets.US_ASCII))
            assertEquals(Npv10ContainerPolicy.VERSION, input.readInt())
            assertEquals(2, input.readInt())
            assertEquals(2, input.readInt())
            assertEquals(24, input.readInt())
            assertEquals(10, input.readInt())
        }
        assertTrue(output.length() > Npv10ContainerPolicy.HEADER_MIN_BYTES)
        Npv10FileReader(output).use { reader ->
            assertEquals(270, reader.header.orientationHintDegrees)
            assertEquals("270", reader.header.metadataValues["orientationhintdegrees"])
        }
    }

    @Test
    fun writerFlushesHeaderAndFirstFrameBeforeCloseSoActiveRecordingIsNotZeroBytes() {
        val output = Files.createTempFile("npv10-live-size", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "SDR",
            startedAtEpochMs = 1_778_888_000_000L
        )

        val writer = Npv10FileWriter(output, config)
        try {
            assertTrue(output.length() > Npv10ContainerPolicy.HEADER_MIN_BYTES)
            writer.writeFrame(
                RawYuvFrame(
                    width = 2,
                    height = 2,
                    yBytes = byteArrayOf(1, 2, 3, 4),
                    uBytes = byteArrayOf(128.toByte()),
                    vBytes = byteArrayOf(128.toByte()),
                    yRowStride = 2,
                    yPixelStride = 1,
                    uRowStride = 1,
                    vRowStride = 1,
                    uPixelStride = 1,
                    vPixelStride = 1,
                    timestamp = 9_000L
                )
            )
            assertTrue(output.length() > Npv10ContainerPolicy.HEADER_MIN_BYTES + 12)
        } finally {
            writer.close()
        }
    }

    @Test
    fun writerStoresNormalPipelineFeatureMetadataForNpv10Masters() {
        val output = Files.createTempFile("npv10-feature-metadata", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 3840,
            height = 2160,
            fps = 60,
            mode = "LOG",
            colorProfile = "Neo Log",
            startedAtEpochMs = 1_778_888_000_000L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            featureMetadata = mapOf(
                "pipeline" to "custom-packed10-npv10",
                "stabilization" to "OIS",
                "noiseReduction" to "true",
                "aiEnhancement" to "true",
                "lutProfile" to "Neutral Film"
            )
        )

        Npv10FileWriter(output, config).close()

        Npv10FileReader(output).use { reader ->
            val metadata = reader.header.metadata
            assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
            assertEquals("ycbcr_p010", reader.header.sourceFormat)
            assertEquals(10, reader.header.sourceBitDepth)
            assertTrue(metadata.contains("pipeline=custom-packed10-npv10"))
            assertTrue(metadata.contains("stabilization=ois"))
            assertTrue(metadata.contains("noisereduction=true"))
            assertTrue(metadata.contains("aienhancement=true"))
            assertTrue(metadata.contains("lutprofile=neutral film"))
        }
    }

    @Test
    fun writerStoresTruthPreservingEliteEnhancementMetadataForNpv10Masters() {
        val output = Files.createTempFile("npv10-elite-enhancement", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 3840,
            height = 2160,
            fps = 60,
            mode = "LOG",
            colorProfile = "Neo Wide Gamut / N-Log Cinema",
            selectedColorSpace = "Neo Wide Gamut",
            selectedColorScience = "N-Log Cinema",
            colorSelectionSource = "USER_MANUAL",
            startedAtEpochMs = 1_778_888_000_000L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE
        )

        Npv10FileWriter(output, config).close()

        Npv10FileReader(output).use { reader ->
            val metadata = reader.header.metadataValues
            assertEquals("strict_10x_10_10_normal_pro_sdr", metadata.getValue("enhancementlevel"))
            assertEquals("npv10_export_only", metadata.getValue("enhancementappliedstage"))
            assertEquals("4k_to_16k_perceptual", metadata.getValue("perceptualupscaletarget"))
            assertEquals("true", metadata.getValue("truthfulmasterpreserved"))
            assertEquals("false", metadata.getValue("masterenhancementdestructive"))
            assertEquals("private_master_unmodified", metadata.getValue("npv10masterpolicy"))
            assertEquals("false", metadata.getValue("nativeoutputresolutionfaked"))
            assertEquals("neo wide gamut", metadata.getValue("selectedcolorspace"))
            assertEquals("n-log cinema", metadata.getValue("selectedcolorscience"))
            assertEquals("user_manual", metadata.getValue("colorselectionsource"))
            assertEquals("true", metadata.getValue("manualcolorselectionrequired"))
        }
    }

    @Test
    fun writerAcceptsSavedManualColorPreferenceAndStoresSelectedColorMetadata() {
        val output = Files.createTempFile("npv10-saved-color", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 1920,
            height = 1080,
            fps = 24,
            mode = "PRO",
            colorProfile = "Neo Wide Gamut / N-Log Cinema",
            selectedColorSpace = "Neo Wide Gamut",
            selectedColorScience = "N-Log Cinema",
            colorSelectionSource = ManualVideoColorSelectionPolicy.SAVED_USER_PREFERENCE,
            startedAtEpochMs = 1_778_888_000_000L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE
        )

        Npv10FileWriter(output, config).close()

        Npv10FileReader(output).use { reader ->
            val metadata = reader.header.metadataValues
            assertEquals("neo wide gamut", metadata.getValue("selectedcolorspace"))
            assertEquals("n-log cinema", metadata.getValue("selectedcolorscience"))
            assertEquals("saved_user_preference", metadata.getValue("colorselectionsource"))
            assertEquals("true", metadata.getValue("manualcolorselectionready"))
        }
    }

    @Test
    fun writerCarriesFeatureHdrModeIntoNpv10NeoHdrTruthMetadata() {
        val output = Files.createTempFile("npv10-nivukx-truth", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 3840,
            height = 2160,
            fps = 60,
            mode = "PRO",
            colorProfile = "NIVUKX Vision",
            selectedColorSpace = "Rec.2020-NIVUKX",
            selectedColorScience = "NIVUKX Vision",
            colorSelectionSource = ManualVideoColorSelectionPolicy.USER_MANUAL,
            startedAtEpochMs = 1_778_888_000_000L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            featureMetadata = mapOf("hdrMode" to "NIVUKX Vision")
        )

        Npv10FileWriter(output, config).close()

        Npv10FileReader(output).use { reader ->
            val metadata = reader.header.metadataValues
            assertEquals("nivukxvision", metadata.getValue("normalizedhdrversion"))
            assertEquals("npv10_bt.2100_pq_private_master", metadata.getValue("activetransport"))
            assertEquals("st2084/pq", metadata.getValue("transfer"))
            assertEquals("bt.2020", metadata.getValue("primaries"))
            assertEquals("bt.2020-ncl", metadata.getValue("matrix"))
            assertEquals("true", metadata.getValue("neohdrnovendordependency"))
            assertEquals("false", metadata.getValue("vendorcertifieddolbyvision"))
        }
    }

    @Test
    fun readerRoundTripValidatesPayloadFormatFrameCrcAndTimestamps() {
        val output = Files.createTempFile("npv10-roundtrip", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 10_000L))
            writer.writeFrame(frame(timestamp = 20_000L))
        }

        Npv10FileReader(output).use { reader ->
            assertEquals(Npv10PayloadFormat.YUV420_8BIT_EXPANDED, reader.header.payloadFormat)
            assertEquals(8, reader.header.sourceBitDepth)
            assertEquals(10, reader.header.bitDepth)
            assertEquals(true, reader.header.exactSelectedResolution)
            val first = reader.readNextFrame()
            val second = reader.readNextFrame()
            assertEquals(10_000L, first?.timestampNs)
            assertEquals(20_000L, second?.timestampNs)
            assertEquals(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(2, 2).toInt(), first?.payload?.size)
            assertEquals(null, reader.readNextFrame())
        }
    }

    private fun frame(timestamp: Long): RawYuvFrame =
        RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = byteArrayOf(1, 2, 3, 4),
            uBytes = byteArrayOf(128.toByte()),
            vBytes = byteArrayOf(128.toByte()),
            yRowStride = 2,
            yPixelStride = 1,
            uRowStride = 1,
            vRowStride = 1,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = timestamp
        )
}
