package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Npv10AudioSidecarTest {
    @Test
    fun npv10ConversionPreservesRequiredAudioAndSealsEvidence() {
        val root = Files.createTempDirectory("npv10-audio-convert").toFile()
        val master = File(root, "clip.npv10")
        val mov = File(root, "clip.mov")
        try {
            Npv10FileWriter(
                outputFile = master,
                config = Npv10SessionConfig(
                    width = 2,
                    height = 2,
                    fps = 25,
                    mode = "PRO",
                    colorProfile = "Rec.709",
                    sourceBitDepth = 10,
                    sourceFormat = "YCBCR_P010",
                    payloadFormat = Npv10PayloadFormat.P010_LE,
                    featureMetadata = mapOf("audioStoredInNpv10Bundle" to "true"),
                    startedAtEpochMs = 1L
                )
            ).use { writer ->
                writer.writeFrame(
                    RawYuvFrame(
                        width = 2,
                        height = 2,
                        yBytes = tenBitPlane(64, 96, 128, 160),
                        uBytes = tenBitPlane(512),
                        vBytes = tenBitPlane(512),
                        yRowStride = 4,
                        yPixelStride = 2,
                        uRowStride = 2,
                        vRowStride = 2,
                        uPixelStride = 2,
                        vPixelStride = 2,
                        timestamp = 1_000_000_000L,
                        bitDepth = 10
                    )
                )
            }
            Npv10AudioSidecarWriter(master, header()).apply {
                writePacket(Npv10AudioPacket(20_000L, 0, byteArrayOf(1, 2, 3, 4)))
                writePacket(Npv10AudioPacket(41_333L, 0, byteArrayOf(5, 6, 7, 8)))
                finish()
            }

            val audit = Npv10MovConverter.convertWithAudit(
                input = master,
                output = mov,
                outputFormat = Npv10MovConverter.OutputFormat.R210_444
            )

            assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
            assertTrue(audit.audioExpected)
            assertTrue(audit.audioPresent)
            assertEquals(2L, audit.audioPacketCount)
            assertTrue(audit.audioMonotonicPts)
            assertTrue(audit.audioCrcValidated)
            assertTrue(audit.conversionCertificate.contains("audioPresent=true"))
            assertTrue(findMarker(mov.readBytes(), "mp4a".toByteArray(Charsets.US_ASCII)) >= 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roundTripRequiresMonotonicPtsAndValidatesEveryPacketCrc() {
        val root = Files.createTempDirectory("npv10-audio-roundtrip").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        try {
            val writer = Npv10AudioSidecarWriter(master, header())
            writer.writePacket(Npv10AudioPacket(40_000L, 0, byteArrayOf(1, 2, 3)))
            writer.writePacket(Npv10AudioPacket(61_333L, 0, byteArrayOf(4, 5, 6, 7)))
            val written = writer.finish()

            assertEquals(2L, written.packetCount)
            assertTrue(written.monotonicPts)
            assertTrue(written.crcValidated)
            Npv10AudioSidecarReader(master).use { reader ->
                assertEquals(48_000, reader.header.sampleRate)
                assertArrayEquals(byteArrayOf(1, 2, 3), reader.readNextPacket()!!.payload)
                assertArrayEquals(byteArrayOf(4, 5, 6, 7), reader.readNextPacket()!!.payload)
                assertEquals(null, reader.readNextPacket())
                val audit = reader.requireAudit()
                assertEquals(2L, audit.packetCount)
                assertEquals(7L, audit.payloadBytes)
                assertEquals(40_000L, audit.firstPresentationTimeUs)
                assertTrue(audit.finalPresentationTimeUs > 61_333L)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalAlignmentRetainsNearestCompleteRealAacPacketWithoutReencoding() {
        val root = Files.createTempDirectory("npv10-audio-terminal-align").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        val payloads = (1..6).map { value -> byteArrayOf(value.toByte(), (value + 20).toByte()) }
        try {
            val writer = Npv10AudioSidecarWriter(master, header())
            listOf(0L, 21_333L, 42_666L, 64_000L, 85_333L, 106_666L)
                .forEachIndexed { index, ptsUs ->
                    writer.writePacket(Npv10AudioPacket(ptsUs, 0, payloads[index]))
                }
            val audit = writer.finishAlignedToDuration(
                targetDurationUs = 100_000L,
                maximumAllowedDeltaUs = 20_000L
            )

            assertEquals(5L, audit.packetCount)
            assertEquals(106_666L, audit.finalPresentationTimeUs)
            assertEquals(10L, audit.payloadBytes)
            Npv10AudioSidecarReader(master).use { reader ->
                val retained = mutableListOf<ByteArray>()
                while (true) {
                    val packet = reader.readNextPacket() ?: break
                    retained += packet.payload
                }
                assertEquals(5, retained.size)
                retained.forEachIndexed { index, payload ->
                    assertArrayEquals(payloads[index], payload)
                }
                assertEquals(audit, reader.requireAudit())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminalAlignmentFailsClosedWhenRealAacCoverageCannotMeetExistingLimit() {
        val root = Files.createTempDirectory("npv10-audio-terminal-short").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        val writer = Npv10AudioSidecarWriter(master, header())
        try {
            writer.writePacket(Npv10AudioPacket(0L, 0, byteArrayOf(1, 2, 3)))
            val failure = runCatching {
                writer.finishAlignedToDuration(
                    targetDurationUs = 100_000L,
                    maximumAllowedDeltaUs = 20_000L
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("cannot align"))
            writer.abort()
            assertFalse(Npv10AudioSidecarPolicy.sidecarFor(master).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedAacPayloadFailsClosed() {
        val root = Files.createTempDirectory("npv10-audio-crc").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        try {
            Npv10AudioSidecarWriter(master, header()).apply {
                writePacket(Npv10AudioPacket(0L, 0, byteArrayOf(10, 20, 30, 40)))
                finish()
            }
            val sidecar = Npv10AudioSidecarPolicy.sidecarFor(master)
            val bytes = sidecar.readBytes()
            val marker = findMarker(bytes, byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '0'.code.toByte()))
            bytes[marker + 24] = (bytes[marker + 24].toInt() xor 0x01).toByte()
            sidecar.writeBytes(bytes)

            val failure = runCatching {
                Npv10AudioSidecarReader(master).use { reader -> reader.readNextPacket() }
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("CRC mismatch"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requiredAudioCannotSilentlyBecomeVideoOnly() {
        val root = Files.createTempDirectory("npv10-audio-required").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        val mov = File(root, "clip.mov")
        val writer = QuickTimeMovFileWriter(mov.absolutePath, "test")
        try {
            val failure = runCatching {
                Npv10AudioSidecarMuxer.muxInto(master, writer, audioExpected = true)
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("audio sidecar is missing"))
        } finally {
            writer.abort()
            root.deleteRecursively()
        }
    }

    @Test
    fun muxedMovFillsStartupGapWithCodecValidSilenceAndPreservesSourcePackets() {
        val root = Files.createTempDirectory("npv10-audio-mux").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        val mov = File(root, "clip.mov")
        val firstSourcePayload = byteArrayOf(1, 2, 3, 4)
        val secondSourcePayload = byteArrayOf(5, 6, 7, 8)
        try {
            Npv10AudioSidecarWriter(master, header()).apply {
                writePacket(Npv10AudioPacket(40_000L, 0, firstSourcePayload))
                writePacket(Npv10AudioPacket(61_333L, 0, secondSourcePayload))
                finish()
            }
            val writer = QuickTimeMovFileWriter(
                outputPath = mov.absolutePath,
                containerLabel = "test",
                preserveTrackPresentationOffsets = true
            )
            val videoTrack = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 16,
                    height = 16,
                    fps = 25,
                    codecConfig = byteArrayOf()
                )
            )
            writer.writeSample(
                videoTrack,
                QuickTimeSampleBuffer(byteArrayOf(0, 0, 0, 1, 0x40), presentationTimeUs = 0L, flags = 1)
            )
            writer.setTrackEndPresentationTimeUs(videoTrack, 40_000L)
            val audit = Npv10AudioSidecarMuxer.muxInto(master, writer, audioExpected = true)
            writer.close()

            val bytes = mov.readBytes()
            assertTrue(audit.present)
            assertEquals(2L, audit.packetCount)
            assertEquals(3L, audit.muxedPacketCount)
            assertEquals(0L, audit.firstPresentationTimeUs)
            assertEquals(40_000L, audit.sourceFirstPresentationTimeUs)
            assertEquals(1, audit.leadingSilencePacketCount)
            assertEquals(21_333L, audit.leadingSilenceCoveredDurationUs)
            assertEquals(18_667L, audit.leadingSilenceResidualGapUs)
            assertTrue(audit.originalPacketsPreserved)
            assertTrue(audit.zeroOrigin)
            assertTrue(findMarker(bytes, "mp4a".toByteArray(Charsets.US_ASCII)) >= 0)
            assertTrue(findMarker(bytes, byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8C.toByte(), 0x1C)) >= 0)
            assertTrue("First source AAC payload changed", findMarker(bytes, firstSourcePayload) >= 0)
            assertTrue("Second source AAC payload changed", findMarker(bytes, secondSourcePayload) >= 0)
            assertEquals(
                "Zero-origin tracks must not require an empty startup edit",
                -1,
                findMarker(bytes, "elst".toByteArray(Charsets.US_ASCII))
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun realDeviceStartupGapPlansOnlyCompleteAacFramesFromZero() {
        val plan = Npv10AacTimelineOriginPolicy.plan(
            firstSourcePresentationTimeUs = 149_784L,
            sampleRate = 48_000,
            channelCount = 2,
            codecConfig = byteArrayOf(0x11, 0x90.toByte())
        )

        assertEquals(7, plan.packetCount)
        assertEquals(
            listOf(0L, 21_333L, 42_666L, 64_000L, 85_333L, 106_666L, 128_000L),
            plan.packetPresentationTimesUs
        )
        assertEquals(149_333L, plan.coveredDurationUs)
        assertEquals(451L, plan.residualGapUs)
        assertEquals(0L, plan.outputFirstPresentationTimeUs)
        assertArrayEquals(
            byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8C.toByte(), 0x1C),
            plan.newSilenceAccessUnit()
        )
    }

    @Test
    fun zeroOriginSourceNeedsNoSyntheticPacketAndRetainsItsTimeline() {
        val plan = Npv10AacTimelineOriginPolicy.plan(
            firstSourcePresentationTimeUs = 0L,
            sampleRate = 48_000,
            channelCount = 2,
            codecConfig = byteArrayOf(0x11, 0x90.toByte())
        )

        assertFalse(plan.applied)
        assertEquals(0, plan.packetCount)
        assertEquals(0L, plan.outputFirstPresentationTimeUs)
        assertEquals("SOURCE_ALREADY_ZERO_ORIGIN", plan.codecContract)
    }

    @Test
    fun delayedUnsupportedAacContractFailsClosedInsteadOfWritingInvalidSilence() {
        val failure = runCatching {
            Npv10AacTimelineOriginPolicy.plan(
                firstSourcePresentationTimeUs = 149_784L,
                sampleRate = 44_100,
                channelCount = 2,
                codecConfig = byteArrayOf(0x12, 0x10)
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("sample rate is unsupported"))
    }

    @Test
    fun deletingMasterBundleAlsoDeletesAudioAndTemporarySidecars() {
        val root = Files.createTempDirectory("npv10-audio-delete").toFile()
        val master = File(root, "clip.npv10").apply { writeBytes(byteArrayOf(1)) }
        try {
            Npv10AudioSidecarPolicy.sidecarFor(master).writeBytes(byteArrayOf(1))
            Npv10AudioSidecarPolicy.temporarySidecarFor(master).writeBytes(byteArrayOf(2))
            assertTrue(Npv10AudioSidecarPolicy.deleteBundleAudio(master))
            assertFalse(Npv10AudioSidecarPolicy.sidecarFor(master).exists())
            assertFalse(Npv10AudioSidecarPolicy.temporarySidecarFor(master).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun header() = Npv10AudioSidecarHeader(
        sampleRate = 48_000,
        channelCount = 2,
        bitRate = 256_000,
        videoOriginSensorTimestampNs = 1_000_000_000L,
        codecConfig = byteArrayOf(0x11, 0x90.toByte())
    )

    private fun tenBitPlane(vararg values: Int): ByteArray =
        ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, value ->
                val p010 = (value.coerceIn(0, 1023) shl 6)
                bytes[index * 2] = (p010 and 0xFF).toByte()
                bytes[index * 2 + 1] = ((p010 ushr 8) and 0xFF).toByte()
            }
        }

    private fun findMarker(bytes: ByteArray, marker: ByteArray): Int {
        for (index in 0..bytes.size - marker.size) {
            if (marker.indices.all { offset -> bytes[index + offset] == marker[offset] }) return index
        }
        return -1
    }
}
