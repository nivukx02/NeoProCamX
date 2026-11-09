package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class QuickTimeMovFileWriterTest {

    @Test
    fun preallocatedTailIsTrimmedToExactCommittedMovLengthAndSynced() {
        val output = Files.createTempFile("camx-mov-preallocation", ".mov")
        try {
            val reservationBytes = 512L * 1024L * 1024L
            val plan = RecordingPreallocationPolicy.resolve(
                availableBytes = 3L * 1024L * 1024L * 1024L,
                requestedBytes = reservationBytes
            )
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV",
                preallocationPlan = plan,
                fileReservation = QuickTimeFileReservation { file, bytes ->
                    file.setLength(bytes)
                    RecordingPreallocationMethod.SPARSE_LENGTH_FALLBACK
                }
            )
            assertEquals(reservationBytes, Files.size(output))
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    codecConfig = byteArrayOf(),
                    colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
                )
            )
            val audioTrack = writer.addAudioTrack(
                QuickTimeAudioTrackConfig(
                    mime = "audio/mp4a-latm",
                    sampleRate = 48_000,
                    channelCount = 2,
                    codecConfig = byteArrayOf(0x12, 0x10)
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.writeSample(
                trackIndex = audioTrack,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(1, 2, 3, 4),
                    presentationTimeUs = 0L,
                    flags = 0
                )
            )

            writer.close()

            assertTrue(Files.size(output) in 1 until reservationBytes)
            assertEquals(Files.size(output), writer.preallocationResult.committedBytes)
            assertTrue(writer.preallocationResult.reservationTrimmed)
            assertTrue(writer.preallocationResult.synced)
            assertEquals(
                RecordingPreallocationMethod.SPARSE_LENGTH_FALLBACK,
                writer.preallocationResult.method
            )
            assertTrue(findBoxOffset(Files.readAllBytes(output), "moov") >= 0)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun close_writesEsdsDescriptorLengthBeforeDescriptorBody() {
        val output = Files.createTempFile("camx-mov-writer", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val audioTrack = writer.addAudioTrack(
                QuickTimeAudioTrackConfig(
                    mime = "audio/mp4a-latm",
                    sampleRate = 48_000,
                    channelCount = 2,
                    codecConfig = byteArrayOf(0x12, 0x10)
                )
            )

            writer.writeSample(
                trackIndex = audioTrack,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(1, 2, 3, 4),
                    presentationTimeUs = 0L,
                    flags = 0
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)
            assertTrue(findBoxOffset(bytes, "ftyp") >= 0)
            assertTrue(findBoxOffset(bytes, "mdat") >= 0)
            assertTrue(findBoxOffset(bytes, "moov") >= 0)

            val esdsOffset = findBoxOffset(bytes, "esds")
            assertTrue("esds box missing", esdsOffset >= 0)

            val descriptorStart = esdsOffset + 12
            assertEquals(0x03, bytes[descriptorStart].toInt() and 0xFF)
            assertTrue(
                "descriptor length must be encoded immediately after the descriptor tag",
                (bytes[descriptorStart + 1].toInt() and 0x7F) > 0
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /**
     * mdhd "language" field is documented in ISO/IEC 14496-12 §8.4.2 as a
     * packed 5-bit-per-letter ISO 639-2/T code. A literal 0 is not a valid
     * ISO 639-2 code; strict inspectors and some players warn or reject. The
     * truthful default is "und" (undetermined) = 0x55C4.
     */
    @Test
    fun mdhd_writesUndeterminedLanguageCode_notZero() {
        val output = Files.createTempFile("camx-mov-mdhd", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    codecConfig = byteArrayOf()
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)
            val mdhdOffset = findBoxOffset(bytes, "mdhd")
            assertTrue("mdhd missing", mdhdOffset >= 0)
            // mdhd v0 layout (§8.4.2):
            // [0..3]   size, [4..7]   "mdhd"
            // [8]      version (0), [9..11] flags
            // [12..15] creation_time, [16..19] modification_time
            // [20..23] timescale,     [24..27] duration
            // [28..29] language (15 bits), [30..31] pre_defined
            val version = bytes[mdhdOffset + 8].toInt() and 0xFF
            assertEquals("mdhd version 0 expected for short clip", 0, version)
            val languageHi = bytes[mdhdOffset + 28].toInt() and 0xFF
            val languageLo = bytes[mdhdOffset + 29].toInt() and 0xFF
            val language = (languageHi shl 8) or languageLo
            // Top bit (pad) is always 0; the lower 15 bits are the language.
            val languageCode = language and 0x7FFF
            assertEquals(
                "mdhd language must be ISO 639-2 'und' (0x55C4), not 0",
                0x55C4, languageCode
            )
            assertNotEquals("mdhd language must not be 0", 0, languageCode)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun videoTrack_writesExplicitRec709SdrNclxColorAtom() {
        val output = Files.createTempFile("camx-mov-colr", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    codecConfig = byteArrayOf(),
                    colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)
            val colrOffset = findBoxOffset(bytes, "colr")
            assertTrue("colr box missing", colrOffset >= 0)
            assertEquals("nclx", String(bytes, colrOffset + 8, 4, Charsets.US_ASCII))
            assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, readU16(bytes, colrOffset + 12))
            assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, readU16(bytes, colrOffset + 14))
            assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, readU16(bytes, colrOffset + 16))
            assertEquals(0, bytes[colrOffset + 18].toInt() and 0x80)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun videoTrack_writesRec2020SdrToNclxAndHevcSps() {
        val output = Files.createTempFile("camx-mov-rec2020-sdr", ".mov")
        try {
            val colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = true
            )
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 3264,
                    height = 2448,
                    fps = 30,
                    codecConfig = annexB(VPS, SPS_PQ_MAIN10, PPS),
                    colorConfig = colorConfig
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)
            val colrOffset = findBoxOffset(bytes, "colr")
            assertTrue("colr box missing", colrOffset >= 0)
            assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, readU16(bytes, colrOffset + 12))
            assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, readU16(bytes, colrOffset + 14))
            assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, readU16(bytes, colrOffset + 16))
            assertEquals(0x80, bytes[colrOffset + 18].toInt() and 0x80)

            val sps = extractHevcParameterSet(bytes, nalType = 33)
            val metadata = HevcColorMetadataRewriter.firstAnnexBSpsColorMetadata(annexB(sps))
            assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, metadata?.colorPrimaries)
            assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, metadata?.transferCharacteristics)
            assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, metadata?.matrixCoefficients)
            assertEquals(true, metadata?.fullRange)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun videoTrack_derivesHevcMain10HvcCFieldsFromSps() {
        val output = Files.createTempFile("camx-mov-main10-hvcc", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 4096,
                    height = 2304,
                    fps = 60,
                    codecConfig = annexB(VPS, SPS_PQ_MAIN10, PPS),
                    colorConfig = QuickTimeColorAtomConfig(
                        colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                        transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                        matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                        fullRange = false
                    )
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)
            val hvcCOffset = findBoxOffset(bytes, "hvcC")
            assertTrue("hvcC box missing", hvcCOffset >= 0)

            val payloadOffset = hvcCOffset + 8
            assertEquals(
                "hvcC must advertise HEVC Main10 when the SPS is Main10",
                2,
                bytes[payloadOffset + 1].toInt() and 0x1F
            )
            assertEquals(
                "hvcC luma bit depth must follow SPS bit_depth_luma_minus8",
                2,
                bytes[payloadOffset + 17].toInt() and 0x07
            )
            assertEquals(
                "hvcC chroma bit depth must follow SPS bit_depth_chroma_minus8",
                2,
                bytes[payloadOffset + 18].toInt() and 0x07
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /**
     * Recordings whose total duration exceeds Int.MAX_VALUE microseconds
     * (~35 min 47 s at 1 µs timescale) previously had their mvhd/tkhd/mdhd
     * duration silently clamped, producing a wrong-duration MOV. The fix
     * promotes those box headers to version=1 with 64-bit duration fields.
     */
    @Test
    fun longDurationClip_promotesHeaderBoxesToVersion1WithSixtyFourBitDuration() {
        val output = Files.createTempFile("camx-mov-long", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 30,
                    codecConfig = byteArrayOf()
                )
            )
            // Two samples spaced ~41 min 40 s apart. Total track duration =
            // 2 × delta ≈ 5_000_000_000 us, which exceeds Int.MAX_VALUE us
            // (~2.147e9) and therefore must use version=1 with u64 duration.
            val delta = 2_500_000_000L
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                    presentationTimeUs = 0L,
                    flags = 1
                )
            )
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x42),
                    presentationTimeUs = delta,
                    flags = 0
                )
            )
            writer.close()

            val bytes = Files.readAllBytes(output)

            val mvhdOffset = findBoxOffset(bytes, "mvhd")
            val tkhdOffset = findBoxOffset(bytes, "tkhd")
            val mdhdOffset = findBoxOffset(bytes, "mdhd")
            assertTrue(mvhdOffset >= 0)
            assertTrue(tkhdOffset >= 0)
            assertTrue(mdhdOffset >= 0)

            assertEquals("mvhd version must be 1 for long durations", 1, bytes[mvhdOffset + 8].toInt() and 0xFF)
            assertEquals("tkhd version must be 1 for long durations", 1, bytes[tkhdOffset + 8].toInt() and 0xFF)
            assertEquals("mdhd version must be 1 for long durations", 1, bytes[mdhdOffset + 8].toInt() and 0xFF)

            // mvhd v1 duration is at offset +28 (after creation_time u64 +
            // modification_time u64 + timescale u32), 8 bytes.
            val mvhdDuration = readU64(bytes, mvhdOffset + 8 + 4 + 8 + 8 + 4)
            assertTrue(
                "mvhd duration $mvhdDuration must be > Int.MAX_VALUE",
                mvhdDuration > Int.MAX_VALUE.toLong()
            )

            // mdhd v1 duration is at the same relative offset.
            val mdhdDuration = readU64(bytes, mdhdOffset + 8 + 4 + 8 + 8 + 4)
            assertTrue(
                "mdhd duration $mdhdDuration must be > Int.MAX_VALUE",
                mdhdDuration > Int.MAX_VALUE.toLong()
            )

            // Sanity: short-clip path still uses version 0 (no premature promotion).
            // We verify this with a separate writer.
            val shortOutput = Files.createTempFile("camx-mov-short", ".mov")
            try {
                val shortWriter = QuickTimeMovFileWriter(
                    outputPath = shortOutput.toFile().absolutePath,
                    containerLabel = "QuickTime MOV"
                )
                val shortTrack = shortWriter.addVideoTrack(
                    QuickTimeVideoTrackConfig(
                        mime = "video/hevc",
                        width = 1920,
                        height = 1080,
                        fps = 30,
                        codecConfig = byteArrayOf()
                    )
                )
                shortWriter.writeSample(
                    trackIndex = shortTrack,
                    sample = QuickTimeSampleBuffer(
                        bytes = byteArrayOf(0, 0, 0, 1, 0x40),
                        presentationTimeUs = 0L,
                        flags = 1
                    )
                )
                shortWriter.close()
                val shortBytes = Files.readAllBytes(shortOutput)
                val shortMvhd = findBoxOffset(shortBytes, "mvhd")
                assertEquals(
                    "Short clip mvhd must remain version=0 to keep MP4 toolchain compatibility",
                    0, shortBytes[shortMvhd + 8].toInt() and 0xFF
                )
            } finally {
                Files.deleteIfExists(shortOutput)
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun audioTrackUsesExactAacAccessUnitDurationInsteadOfIrregularPtsGaps() {
        val output = Files.createTempFile("camx-mov-aac-timing", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addAudioTrack(
                QuickTimeAudioTrackConfig(
                    mime = "audio/mp4a-latm",
                    sampleRate = 48_000,
                    channelCount = 2,
                    codecConfig = byteArrayOf(0x12, 0x10)
                )
            )
            val irregularPtsUs = longArrayOf(0L, 90_000L, 91_000L, 250_000L, 400_000L, 900_000L)
            irregularPtsUs.forEachIndexed { index, ptsUs ->
                writer.writeSample(
                    trackIndex = track,
                    sample = QuickTimeSampleBuffer(
                        bytes = byteArrayOf(index.toByte(), 1, 2, 3),
                        presentationTimeUs = ptsUs,
                        flags = 0
                    )
                )
            }
            writer.close()

            val bytes = Files.readAllBytes(output)
            val sttsOffset = findBoxOffset(bytes, "stts")
            val mdhdOffset = findBoxOffset(bytes, "mdhd")
            assertTrue(sttsOffset >= 0)
            assertTrue(mdhdOffset >= 0)

            val entryCount = readU32(bytes, sttsOffset + 12)
            val durations = mutableListOf<Long>()
            var entryOffset = sttsOffset + 16
            repeat(entryCount.toInt()) {
                val sampleCount = readU32(bytes, entryOffset)
                val sampleDelta = readU32(bytes, entryOffset + 4)
                repeat(sampleCount.toInt()) { durations += sampleDelta }
                entryOffset += 8
            }

            assertEquals(
                listOf(21_333L, 21_333L, 21_334L, 21_333L, 21_333L, 21_334L),
                durations
            )
            assertEquals(128_000L, readU32(bytes, mdhdOffset + 24))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun videoTrackUsesNominalLastFrameDurationInsteadOfDuplicatingStopGap() {
        val output = Files.createTempFile("camx-mov-video-tail", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "QuickTime MOV"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 24,
                    codecConfig = byteArrayOf()
                )
            )
            listOf(0L, 41_666L, 83_332L, 650_426L).forEachIndexed { index, ptsUs ->
                writer.writeSample(
                    trackIndex = track,
                    sample = QuickTimeSampleBuffer(
                        bytes = byteArrayOf(0, 0, 0, 1, (0x40 + index).toByte()),
                        presentationTimeUs = ptsUs,
                        flags = if (index == 0) 1 else 0
                    )
                )
            }
            writer.close()

            val bytes = Files.readAllBytes(output)
            val sttsOffset = findBoxOffset(bytes, "stts")
            assertTrue(sttsOffset >= 0)

            val durations = mutableListOf<Long>()
            var entryOffset = sttsOffset + 16
            repeat(readU32(bytes, sttsOffset + 12).toInt()) {
                val sampleCount = readU32(bytes, entryOffset)
                val sampleDelta = readU32(bytes, entryOffset + 4)
                repeat(sampleCount.toInt()) { durations += sampleDelta }
                entryOffset += 8
            }

            assertEquals(listOf(41_666L, 41_666L, 567_094L, 41_666L), durations)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun extractHevcParameterSet(bytes: ByteArray, nalType: Int): ByteArray {
        val hvcCOffset = findBoxOffset(bytes, "hvcC")
        require(hvcCOffset >= 0) { "hvcC box missing" }
        var offset = hvcCOffset + 8 + 23
        val arrayCount = bytes[hvcCOffset + 8 + 22].toInt() and 0xFF
        repeat(arrayCount) {
            val arrayNalType = bytes[offset].toInt() and 0x3F
            offset += 1
            val nalCount = readU16(bytes, offset)
            offset += 2
            repeat(nalCount) {
                val length = readU16(bytes, offset)
                offset += 2
                val nal = bytes.copyOfRange(offset, offset + length)
                offset += length
                if (arrayNalType == nalType) return nal
            }
        }
        error("HEVC parameter set type $nalType missing")
    }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal ->
            acc + byteArrayOf(0, 0, 0, 1) + nal
        }

    private fun findBoxOffset(bytes: ByteArray, boxType: String): Int {
        val target = boxType.toByteArray(Charsets.US_ASCII)
        for (index in 4..bytes.size - 4) {
            if (
                bytes[index] == target[0] &&
                bytes[index + 1] == target[1] &&
                bytes[index + 2] == target[2] &&
                bytes[index + 3] == target[3]
            ) {
                return index - 4
            }
        }
        return -1
    }

    private companion object {
        fun hex(value: String): ByteArray =
            value
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()

        val VPS: ByteArray = hex(
            "40 01 0c 01 ff ff 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 ac 09"
        )
        val SPS_PQ_MAIN10: ByteArray = hex(
            "42 01 01 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 a0 00 " +
                "80 08 00 90 13 65 ae e4 c9 2e a6 a1 22 01 20 5d a1 42 50"
        )
        val PPS: ByteArray = hex(
            "44 01 c0 e3 0f 08 d0 60 30 18 0c 06 3d 84 20"
        )
    }
}
