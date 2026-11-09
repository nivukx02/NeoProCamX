package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun firstFrameProofIsPublishedAfterTheAsynchronousDurabilityCheckpoint() {
        val output = Files.createTempFile("npv10-frame1-durable", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "SDR",
            startedAtEpochMs = 1_778_888_000_000L
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 9_000L))
            val deadlineNs = System.nanoTime() + 5_000_000_000L
            var result = writer.firstDurableFrameResult()
            while (result == null && System.nanoTime() < deadlineNs) {
                Thread.sleep(5L)
                result = writer.firstDurableFrameResult()
            }

            val proof = assertNotNull(result).let { result!!.getOrThrow() }
            assertEquals(1L, proof.frameCount)
            assertEquals(9_000L, proof.sensorTimestampNs)
            assertTrue(proof.committedBytes > Npv10ContainerPolicy.HEADER_MIN_BYTES)
        }
    }

    @Test
    fun closeDrainsEveryCoalescedDurabilityBoundaryThroughTheFinalRequestedFrame() {
        val output = Files.createTempFile("npv10-coalesced-durability", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "SDR",
            startedAtEpochMs = 1_778_888_000_000L
        )
        val writer = Npv10FileWriter(output, config)

        repeat(241) { index ->
            writer.writeFrame(frame(timestamp = 1_000_000_000L + index * 40_000_000L))
        }
        writer.close()

        val checkpoints = writer.durabilityCheckpointTimings
        assertTrue(checkpoints.dedicatedDescriptor)
        assertEquals(241L, checkpoints.completedThroughFrame)
        assertTrue(checkpoints.checkpoints in 1L..3L)
        val stages = writer.durabilityStageTimings
        assertEquals(checkpoints.checkpoints, stages.commitWaitAttempts)
        assertEquals(checkpoints.checkpoints, stages.syncAttempts)
        assertEquals(0L, stages.commitWaitFailures)
        assertEquals(0L, stages.syncFailures)
        assertTrue(stages.averageCommitWaitMs >= 0.0 && stages.averageSyncMs >= 0.0)
        Npv10FileReader(output).use { reader ->
            assertEquals(241, reader.countFramesForPlayback())
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

    @Test
    fun playbackCountAndSeekUsePacketHeadersAndRestoreReaderPosition() {
        val output = Files.createTempFile("npv10-player-seek", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 30,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED
        )
        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 10_000L))
            writer.writeFrame(frame(timestamp = 20_000L))
            writer.writeFrame(frame(timestamp = 30_000L))
        }

        Npv10FileReader(output).use { reader ->
            assertEquals(10_000L, reader.readNextFrame()?.timestampNs)
            assertEquals(3, reader.countFramesForPlayback())
            // Counting restores the exact read position instead of restarting playback implicitly.
            assertEquals(20_000L, reader.readNextFrame()?.timestampNs)
            assertEquals(2, reader.seekToFrameForPlayback(2))
            val thirdFramePacketPosition = reader.bytePosition
            assertEquals(30_000L, reader.readNextFrame()?.timestampNs)
            assertEquals(0, reader.seekToFrameForPlayback(0))
            assertEquals(10_000L, reader.readNextFrame()?.timestampNs)
            // Repeated forward/backward seeks resolve to the cached packet boundary exactly.
            assertEquals(2, reader.seekToFrameForPlayback(2))
            assertEquals(thirdFramePacketPosition, reader.bytePosition)
            assertEquals(30_000L, reader.readNextFrame()?.timestampNs)
        }
    }

    @Test
    fun writerInfersMissingRecordedFramesFromSensorTimestampCadence() {
        val output = Files.createTempFile("npv10-cadence-gaps", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "NDR10+",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 1_000_000_000L))
            writer.writeFrame(frame(timestamp = 1_040_000_000L))
            writer.writeFrame(frame(timestamp = 1_160_000_000L))
            assertEquals(2L, writer.inferredDroppedFrameCount)
        }
    }

    @Test
    fun intentionalCadenceDiscontinuityDoesNotReportPausedTimeAsDroppedFrames() {
        val output = Files.createTempFile("npv10-cadence-pause", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "NDR10+",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 1_000_000_000L))
            writer.writeFrame(frame(timestamp = 1_040_000_000L))
            writer.markTimestampCadenceDiscontinuity()
            writer.writeFrame(frame(timestamp = 6_040_000_000L))
            writer.writeFrame(frame(timestamp = 6_080_000_000L))
            assertEquals(0L, writer.inferredDroppedFrameCount)
        }
    }

    @Test
    fun writerRejectsNonPositiveTimestampsBeforeCommittingFrameBytes() {
        listOf(0L, -1L).forEach { invalidTimestampNs ->
            val output = Files.createTempFile("npv10-invalid-time", ".npv10").toFile()
            val config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                startedAtEpochMs = 1L,
                sourceFormat = "YUV_420_888",
                sourceBitDepth = 8,
                payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED
            )
            val writer = Npv10FileWriter(output, config)
            val committedHeaderBytes = writer.committedBytes
            try {
                val failure = runCatching {
                    writer.writeFrame(frame(timestamp = invalidTimestampNs))
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("timestamp must be positive"))
                assertEquals(0L, writer.frameCount)
                assertEquals(-1L, writer.firstFrameTimestampNs)
                assertEquals(committedHeaderBytes, writer.committedBytes)
            } finally {
                writer.close()
            }
            Npv10FileReader(output).use { reader ->
                assertEquals(null, reader.readNextFrame())
            }
        }
    }

    @Test
    fun copiedP010WriterRejectsNonPositiveTimestampsBeforeEncodingOrCommit() {
        val output = Files.createTempFile("npv10-invalid-p010-time", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE
        )
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(2, 2, Npv10PayloadFormat.P010_LE)
            .toInt()
        val writer = Npv10FileWriter(output, config)
        val committedHeaderBytes = writer.committedBytes
        try {
            listOf(0L, -1L).forEach { invalidTimestampNs ->
                val failure = runCatching {
                    writer.writeCopiedP010Frame(
                        Npv10CopiedP010Frame(
                            timestampNs = invalidTimestampNs,
                            width = 2,
                            height = 2,
                            payload = ByteBuffer.allocateDirect(payloadBytes),
                            payloadBytes = payloadBytes,
                            copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                        )
                    )
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("timestamp must be positive"))
                assertEquals(0L, writer.frameCount)
                assertEquals(committedHeaderBytes, writer.committedBytes)
            }
        } finally {
            writer.close()
        }
    }

    @Test
    fun writerRejectsDuplicateAndRegressingTimestampsBeforeCommittingFrameBytes() {
        listOf(1_000_000_000L, 999_999_999L).forEach { invalidNextTimestampNs ->
            val output = Files.createTempFile("npv10-nonmonotonic-time", ".npv10").toFile()
            val config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                startedAtEpochMs = 1L,
                sourceFormat = "YUV_420_888",
                sourceBitDepth = 8,
                payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED
            )
            Npv10FileWriter(output, config).use { writer ->
                writer.writeFrame(frame(timestamp = 1_000_000_000L))
                val committedAfterFirstFrame = writer.committedBytes
                val failure = runCatching {
                    writer.writeFrame(frame(timestamp = invalidNextTimestampNs))
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("strictly increasing"))
                assertEquals(1L, writer.frameCount)
                assertEquals(committedAfterFirstFrame, writer.committedBytes)
                assertEquals(1L, writer.writerStageTimings.frames)
            }
            Npv10FileReader(output).use { reader ->
                assertEquals(1_000_000_000L, reader.readNextFrame()?.timestampNs)
                assertEquals(null, reader.readNextFrame())
            }
        }
    }

    @Test
    fun copiedP010WriterRejectsNonMonotonicLineageEvenAfterCadenceReset() {
        listOf(1_000_000_000L, 999_999_999L).forEach { invalidNextTimestampNs ->
            val output = Files.createTempFile("npv10-nonmonotonic-p010-time", ".npv10").toFile()
            val config = Npv10SessionConfig(
                width = 2,
                height = 2,
                fps = 24,
                mode = "PRO",
                colorProfile = "Rec.709",
                startedAtEpochMs = 1L,
                sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10,
                payloadFormat = Npv10PayloadFormat.P010_LE
            )
            val payloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(2, 2, Npv10PayloadFormat.P010_LE)
                .toInt()
            fun copiedFrame(timestampNs: Long) = Npv10CopiedP010Frame(
                timestampNs = timestampNs,
                width = 2,
                height = 2,
                payload = ByteBuffer.allocateDirect(payloadBytes),
                payloadBytes = payloadBytes,
                copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
            )

            Npv10FileWriter(output, config).use { writer ->
                writer.writeCopiedP010Frame(copiedFrame(1_000_000_000L))
                val committedAfterFirstFrame = writer.committedBytes
                writer.markTimestampCadenceDiscontinuity()
                val failure = runCatching {
                    writer.writeCopiedP010Frame(copiedFrame(invalidNextTimestampNs))
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("strictly increasing"))
                assertEquals(1L, writer.frameCount)
                assertEquals(committedAfterFirstFrame, writer.committedBytes)
                assertEquals(1L, writer.writerStageTimings.frames)
            }
            Npv10FileReader(output).use { reader ->
                assertEquals(1_000_000_000L, reader.readNextFrame()?.timestampNs)
                assertEquals(null, reader.readNextFrame())
            }
        }
    }

    @Test
    fun copiedP010WriterReportsPacketCrcAndFileWriteStagesForEveryCommit() {
        val output = Files.createTempFile("npv10-writer-stages", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(2, 2, Npv10PayloadFormat.P010_LE)
            .toInt()
        val payload = ByteBuffer.allocateDirect(payloadBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(payloadBytes / Short.SIZE_BYTES) { sample ->
                putShort((((sample * 137) and 0x3FF) shl 6).toShort())
            }
            flip()
        }
        val writer = Npv10FileWriter(output, config)
        try {
            writer.writeCopiedP010Frame(
                Npv10CopiedP010Frame(
                    timestampNs = 1_000_000L,
                    width = 2,
                    height = 2,
                    payload = payload,
                    payloadBytes = payloadBytes,
                    copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                )
            )

            val stages = writer.writerStageTimings
            assertEquals(1L, writer.frameCount)
            assertEquals(writer.frameCount, stages.frames)
            assertTrue(stages.averageEncodeAndCrcMs >= 0.0)
            assertTrue(stages.maxEncodeAndCrcMs >= stages.averageEncodeAndCrcMs)
            assertTrue(stages.averageFallbackCrcMs >= 0.0)
            assertTrue(stages.maxFallbackCrcMs >= stages.averageFallbackCrcMs)
            assertTrue(stages.averageFileWriteMs >= 0.0)
            assertTrue(stages.maxFileWriteMs >= stages.averageFileWriteMs)
        } finally {
            writer.close()
        }
    }

    @Test
    fun terminalBoundaryPreservesEveryGapInTheSavedMedia() {
        val output = Files.createTempFile("npv10-terminal-cadence", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 1_000_000_000L))
            writer.writeFrame(frame(timestamp = 1_040_000_000L))
            writer.writeFrame(frame(timestamp = 1_240_000_000L))
            writer.writeFrame(frame(timestamp = 1_440_000_000L))
            assertEquals(8L, writer.inferredDroppedFrameCount)

            assertEquals(0L, writer.closeTerminalCadenceSegment(1_440_000_000L))
            assertEquals(8L, writer.inferredDroppedFrameCount)
            // The earlier genuine active-capture gap remains the maximum media jitter.
            assertEquals(160_000_000L, writer.maxTimestampJitterNs)
            val lateWrite = runCatching {
                writer.writeFrame(frame(timestamp = 1_480_000_000L))
            }.exceptionOrNull()
            assertTrue(lateWrite is IllegalStateException)
            assertEquals(4L, writer.frameCount)
        }
    }

    @Test
    fun terminalBoundaryRequiresExactWriterTailAndNeverExcusesTailJitter() {
        val output = Files.createTempFile("npv10-terminal-cadence-exact", ".npv10").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 25,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YUV_420_888",
            sourceBitDepth = 8,
            payloadFormat = Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
            exactSelectedResolution = true
        )

        Npv10FileWriter(output, config).use { writer ->
            writer.writeFrame(frame(timestamp = 207_598_107_465_869L))
            writer.writeFrame(frame(timestamp = 207_598_307_340_869L))
            assertEquals(4L, writer.inferredDroppedFrameCount)
            assertEquals(null, writer.closeTerminalCadenceSegment(207_598_307_340_868L))
            assertEquals(4L, writer.inferredDroppedFrameCount)
            assertEquals(0L, writer.closeTerminalCadenceSegment(207_598_307_340_869L))
            assertEquals(4L, writer.inferredDroppedFrameCount)
            assertEquals(159_875_000L, writer.maxTimestampJitterNs)
            assertEquals(159_875_000L, writer.averageTimestampJitterNs)
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
