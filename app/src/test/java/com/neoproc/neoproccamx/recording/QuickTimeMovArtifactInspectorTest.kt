package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class QuickTimeMovArtifactInspectorTest {
    @Test
    fun v210Fixture_reportsStructureGeometryTimingColorOffsetsAndMeasuredBitrate() {
        withFixture(
            mime = "video/v210",
            width = 1920,
            height = 1080,
            fps = 25,
            sampleSizes = intArrayOf(100, 120, 140),
            orientation = 90,
            color = HLG_BT2020_FULL
        ) { output ->
            val report = QuickTimeMovArtifactInspector.inspect(
                output,
                QuickTimeMovArtifactRequirements(
                    expectedVideoSampleEntry = "v210",
                    expectedWidth = 1920,
                    expectedHeight = 1080,
                    expectedRotationDegrees = 90,
                    expectedColor = HLG_BT2020_FULL.toExpectation()
                )
            )

            assertTrue(report.failures.joinToString(), report.passed)
            assertEquals("qt  ", report.majorBrand)
            assertTrue(report.compatibleBrands.contains("mp42"))
            assertEquals(listOf("ftyp", "mdat", "moov"), report.topLevelBoxes.map { it.type })
            assertEquals(16, report.topLevelBoxes.single { it.type == "mdat" }.headerSize)
            assertTrue(report.ftypPresent)
            assertTrue(report.moovPresent)
            assertTrue(report.mdatPresent)

            val video = requireNotNull(report.videoTrack)
            assertEquals("v210", video.sampleEntry)
            assertEquals(1920, video.sampleEntryWidth)
            assertEquals(1080, video.sampleEntryHeight)
            assertEquals(1920.0, video.tkhdWidth, 0.0)
            assertEquals(1080.0, video.tkhdHeight, 0.0)
            assertEquals(90, video.rotationDegrees)
            assertTrue(video.rotationMatrixRecognized)
            assertEquals(1_000_000L, video.mediaTimescale)
            assertEquals(120_000L, video.mediaDurationUnits)
            assertEquals(0.12, video.durationSeconds, 0.000_001)
            assertEquals(3L, video.sampleCount)
            assertEquals(0L, video.zeroSizedSampleCount)
            assertEquals(360L, video.samplePayloadBytes)
            assertEquals(1L, video.sttsEntryCount)
            assertEquals(3L, video.sttsCoveredSampleCount)
            assertEquals(120_000L, video.sttsDurationUnits)
            assertTrue(video.timingCoversEverySample)
            assertEquals(HLG_BT2020_FULL.toEvidence(), video.nclxColor)
            assertEquals(1L, video.sampleToChunk.entryCount)
            assertEquals(3L, video.sampleToChunk.mappedChunkCount)
            assertEquals(3L, video.sampleToChunk.mappedSampleCount)
            assertEquals("co64", video.chunkOffsets.tableType)
            assertEquals(3L, video.chunkOffsets.entryCount)
            assertTrue(video.chunkOffsets.allOffsetsInsideFile)
            assertTrue(video.chunkOffsets.allOffsetsInsideMdat)
            assertTrue(video.chunkOffsets.allSampleExtentsInsideFile)
            assertTrue(video.chunkOffsets.allSampleExtentsInsideMdat)
            assertEquals(null, video.chunkOffsets.firstInvalidOffset)
            assertEquals(24_000L, video.measuredVideoBitrateBps)
            assertNotNull(report.measuredFileBitrateBps)
            assertTrue(report.measuredFileBitrateBps!! > video.measuredVideoBitrateBps!!)
        }
    }

    @Test
    fun writerFixtures_acceptR210Hvc1AndHev1SampleEntries() {
        listOf(
            "video/r210" to "r210",
            "video/hevc" to "hvc1"
        ).forEach { (mime, expectedEntry) ->
            withFixture(mime = mime, sampleSizes = intArrayOf(16)) { output ->
                val report = QuickTimeMovArtifactInspector.inspect(
                    output,
                    QuickTimeMovArtifactRequirements(expectedVideoSampleEntry = expectedEntry)
                )
                assertTrue("$expectedEntry: ${report.failures}", report.passed)
                assertEquals(expectedEntry, report.videoTrack?.sampleEntry)
            }
        }

        withFixture(mime = "video/hevc", sampleSizes = intArrayOf(16)) { output ->
            replaceFirstFourCc(output, from = "hvc1", to = "hev1")
            val report = QuickTimeMovArtifactInspector.inspect(
                output,
                QuickTimeMovArtifactRequirements(expectedVideoSampleEntry = "hev1")
            )
            assertTrue(report.failures.joinToString(), report.passed)
            assertEquals("hev1", report.videoTrack?.sampleEntry)
        }
    }

    @Test
    fun hevcSampleEntries_requireStructurallyValidMain10Configuration() {
        withFixture(mime = "video/hevc", sampleSizes = intArrayOf(32)) { output ->
            val valid = QuickTimeMovArtifactInspector.inspect(output)
            assertTrue(valid.failures.joinToString(), valid.passed)
            val configuration = requireNotNull(valid.videoTrack?.hevcConfiguration)
            assertEquals(2, configuration.profileIdc)
            assertEquals(10, configuration.bitDepthLuma)
            assertEquals(10, configuration.bitDepthChroma)
            assertEquals(4, configuration.nalLengthFieldBytes)
            assertTrue(configuration.hasVps)
            assertTrue(configuration.hasSps)
            assertTrue(configuration.hasPps)
        }

        listOf("hvc1", "hev1").forEach { sampleEntry ->
            withFixture(mime = "video/hevc", sampleSizes = intArrayOf(32)) { output ->
                if (sampleEntry == "hev1") replaceFirstFourCc(output, from = "hvc1", to = "hev1")
                replaceFirstFourCc(output, from = "hvcC", to = "free")
                val report = QuickTimeMovArtifactInspector.inspect(output)
                assertFalse(sampleEntry, report.passed)
                assertTrue(sampleEntry, report.failures.any { it.contains("exactly one hvcC") })
            }
        }

        listOf("hvc1", "hev1").forEach { sampleEntry ->
            withFixture(mime = "video/hevc", sampleSizes = intArrayOf(32)) { output ->
                if (sampleEntry == "hev1") replaceFirstFourCc(output, from = "hvc1", to = "hev1")
                val hvcCBoxOffset = findAscii(output.readBytes(), "hvcC") - 4
                RandomAccessFile(output, "rw").use { file ->
                    // hvcC payload byte 1 is general_profile_space/tier/general_profile_idc.
                    file.seek(hvcCBoxOffset.toLong() + 9L)
                    file.writeByte(1)
                }
                val report = QuickTimeMovArtifactInspector.inspect(output)
                assertFalse(sampleEntry, report.passed)
                assertTrue(sampleEntry, report.failures.any { it.contains("not HEVC Main10") })
            }
        }

        withFixture(mime = "video/hevc", sampleSizes = intArrayOf(32)) { output ->
            val hvcCBoxOffset = findAscii(output.readBytes(), "hvcC") - 4
            RandomAccessFile(output, "rw").use { file ->
                // Header 8 + fixed hvcC payload 23 + array header/count 3 = first NAL length.
                file.seek(hvcCBoxOffset.toLong() + 34L)
                file.writeShort(0x7FFF)
            }
            val report = QuickTimeMovArtifactInspector.inspect(output)
            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("exceeds box/file limit") })
        }
    }

    @Test
    fun missingOrInvalidSampleToChunkTable_failsClosed() {
        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32, 48)) { output ->
            replaceFirstFourCc(output, from = "stsc", to = "free")
            val report = QuickTimeMovArtifactInspector.inspect(output)
            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("exactly one stsc") })
        }

        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32, 48)) { output ->
            val stscBoxOffset = findAscii(output.readBytes(), "stsc") - 4
            RandomAccessFile(output, "rw").use { file ->
                // First entry's sample_description_index must reference the sole stsd entry (1).
                file.seek(stscBoxOffset.toLong() + 24L)
                file.writeInt(2)
            }
            val report = QuickTimeMovArtifactInspector.inspect(output)
            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("sample description 2") })
        }

        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32, 48)) { output ->
            val stscBoxOffset = findAscii(output.readBytes(), "stsc") - 4
            RandomAccessFile(output, "rw").use { file ->
                // Two samples per each of two chunks maps four samples onto a two-entry stsz.
                file.seek(stscBoxOffset.toLong() + 20L)
                file.writeInt(2)
            }
            val report = QuickTimeMovArtifactInspector.inspect(output)
            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("maps more samples") })
        }

        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32)) { output ->
            val stscBoxOffset = findAscii(output.readBytes(), "stsc") - 4
            RandomAccessFile(output, "rw").use { file ->
                // Unsigned 0xFFFFFFFF must fail before allocation or table iteration.
                file.seek(stscBoxOffset.toLong() + 12L)
                file.writeInt(-1)
            }
            val report = QuickTimeMovArtifactInspector.inspect(output)
            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("stsc entry count") && it.contains("safety limit") })
        }
    }

    @Test
    fun sampleExtentCrossingMdatBoundary_failsEvenWhenChunkStartIsValid() {
        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32)) { output ->
            val bytes = output.readBytes()
            val mdatOffset = findTopLevelBox(bytes, "mdat")
            val mdatSize = readU64(bytes, mdatOffset + 8)
            val mdatEnd = mdatOffset.toLong() + mdatSize
            val co64BoxOffset = findAscii(bytes, "co64") - 4
            val firstChunkOffset = readU64(bytes, co64BoxOffset + 16)
            val stszBoxOffset = findAscii(bytes, "stsz") - 4
            val forgedSize = Math.addExact(mdatEnd - firstChunkOffset, 1L)
            require(forgedSize in 1..Int.MAX_VALUE.toLong())
            RandomAccessFile(output, "rw").use { file ->
                file.seek(stszBoxOffset.toLong() + 20L)
                file.writeInt(forgedSize.toInt())
            }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            val offsets = requireNotNull(report.videoTrack).chunkOffsets
            assertTrue(offsets.allOffsetsInsideFile)
            assertTrue(offsets.allOffsetsInsideMdat)
            assertTrue(offsets.allSampleExtentsInsideFile)
            assertFalse(offsets.allSampleExtentsInsideMdat)
            assertEquals(firstChunkOffset, offsets.firstInvalidExtentStart)
            assertEquals(mdatEnd + 1L, offsets.firstInvalidExtentEndExclusive)
            assertTrue(report.failures.any { it.contains("sample extent is outside every mdat") })
        }
    }

    @Test
    fun extendedSizeMoov_isParsedWithoutChangingSampleOffsets() {
        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32, 48)) { output ->
            promoteTerminalMoovToExtendedSize(output)

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertTrue(report.failures.joinToString(), report.passed)
            val moov = report.topLevelBoxes.single { it.type == "moov" }
            assertEquals(16, moov.headerSize)
            assertTrue(report.videoTrack!!.chunkOffsets.allOffsetsInsideMdat)
        }
    }

    @Test
    fun truncatedMoov_failsClosed() {
        withFixture(mime = "video/v210", sampleSizes = intArrayOf(32)) { output ->
            RandomAccessFile(output, "rw").use { it.setLength(it.length() - 1L) }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            assertEquals(QuickTimeMovArtifactStatus.FAIL, report.status)
            assertTrue(report.failures.any { it.contains("truncated", ignoreCase = true) })
        }
    }

    @Test
    fun oversizedBoxDeclaration_failsClosed() {
        withFixture(mime = "video/v210", sampleSizes = intArrayOf(32)) { output ->
            val moovOffset = findTopLevelBox(output.readBytes(), "moov")
            RandomAccessFile(output, "rw").use { file ->
                file.seek(moovOffset.toLong())
                file.writeInt(Int.MAX_VALUE)
            }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("truncated", ignoreCase = true) })
        }
    }

    @Test
    fun chunkOffsetOutsideFileAndMdat_isReportedAsVerificationFailure() {
        withFixture(mime = "video/v210", sampleSizes = intArrayOf(32, 48)) { output ->
            val bytes = output.readBytes()
            val co64TypeOffset = findAscii(bytes, "co64")
            val co64BoxOffset = co64TypeOffset - 4
            RandomAccessFile(output, "rw").use { file ->
                file.seek(co64BoxOffset.toLong() + 16L)
                file.writeLong(output.length() + 1_024L)
            }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            val offsets = requireNotNull(report.videoTrack).chunkOffsets
            assertFalse(offsets.allOffsetsInsideFile)
            assertFalse(offsets.allOffsetsInsideMdat)
            assertEquals(output.length() + 1_024L, offsets.firstInvalidOffset)
            assertTrue(report.failures.any { it.contains("outside the file") })
        }
    }

    @Test
    fun sttsCoverageMismatch_isReportedWithoutTrustingDeclaredFps() {
        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32, 48, 64)) { output ->
            val bytes = output.readBytes()
            val sttsBoxOffset = findAscii(bytes, "stts") - 4
            RandomAccessFile(output, "rw").use { file ->
                // Header (8), full-box (4), entry_count (4), then first run's sample_count.
                file.seek(sttsBoxOffset.toLong() + 16L)
                file.writeInt(4)
            }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            val video = requireNotNull(report.videoTrack)
            assertEquals(3L, video.sampleCount)
            assertEquals(4L, video.sttsCoveredSampleCount)
            assertFalse(video.timingCoversEverySample)
            assertTrue(report.failures.any { it.contains("stts covers 4 samples") })
        }
    }

    @Test
    fun maliciousSampleCount_failsClosedBeforeAllocatingFromTheCount() {
        withFixture(mime = "video/r210", sampleSizes = intArrayOf(32)) { output ->
            val bytes = output.readBytes()
            val stszBoxOffset = findAscii(bytes, "stsz") - 4
            RandomAccessFile(output, "rw").use { file ->
                // Header (8), full-box (4), fixed sample_size (4), then sample_count.
                file.seek(stszBoxOffset.toLong() + 16L)
                file.writeInt(-1)
            }

            val report = QuickTimeMovArtifactInspector.inspect(output)

            assertFalse(report.passed)
            assertTrue(report.failures.any { it.contains("safety limit") })
        }
    }

    private inline fun withFixture(
        mime: String,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 25,
        sampleSizes: IntArray,
        orientation: Int = 0,
        color: QuickTimeColorAtomConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
        block: (File) -> Unit
    ) {
        val output = Files.createTempFile("camx-artifact-inspector", ".mov").toFile()
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.absolutePath,
                containerLabel = "NeoProCamX test"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = mime,
                    width = width,
                    height = height,
                    fps = fps,
                    codecConfig = if (mime.equals("video/hevc", ignoreCase = true)) {
                        annexB(VPS, SPS_MAIN10, PPS)
                    } else {
                        byteArrayOf()
                    },
                    colorConfig = color
                )
            )
            writer.setOrientationHint(orientation)
            val frameDurationUs = 1_000_000L / fps
            sampleSizes.forEachIndexed { index, size ->
                val sample = ByteArray(size) { byteIndex -> ((index + byteIndex) and 0x7F).toByte() }
                if (mime == "video/hevc" && sample.size >= 6) {
                    sample[0] = 0
                    sample[1] = 0
                    sample[2] = 0
                    sample[3] = 1
                    sample[4] = 0x26
                    sample[5] = 1
                }
                writer.writeSample(
                    trackIndex = track,
                    sample = QuickTimeSampleBuffer(
                        bytes = sample,
                        presentationTimeUs = index * frameDurationUs,
                        flags = if (index == 0) 1 else 0
                    )
                )
            }
            writer.close()
            block(output)
        } finally {
            Files.deleteIfExists(output.toPath())
        }
    }

    private fun replaceFirstFourCc(file: File, from: String, to: String) {
        val bytes = file.readBytes()
        val offset = findAscii(bytes, from)
        RandomAccessFile(file, "rw").use { output ->
            output.seek(offset.toLong())
            output.write(to.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun promoteTerminalMoovToExtendedSize(file: File) {
        val source = file.readBytes()
        val moovOffset = findTopLevelBox(source, "moov")
        val oldSize = readU32(source, moovOffset).toInt()
        assertEquals(source.size, moovOffset + oldSize)
        val promoted = ByteArray(source.size + 8)
        source.copyInto(promoted, endIndex = moovOffset)
        writeU32(promoted, moovOffset, 1L)
        "moov".toByteArray(Charsets.US_ASCII).copyInto(promoted, moovOffset + 4)
        writeU64(promoted, moovOffset + 8, oldSize.toLong() + 8L)
        source.copyInto(
            destination = promoted,
            destinationOffset = moovOffset + 16,
            startIndex = moovOffset + 8,
            endIndex = source.size
        )
        file.writeBytes(promoted)
    }

    private fun findTopLevelBox(bytes: ByteArray, expectedType: String): Int {
        var cursor = 0
        while (cursor + 8 <= bytes.size) {
            val size32 = readU32(bytes, cursor)
            val type = String(bytes, cursor + 4, 4, Charsets.US_ASCII)
            val size = when (size32) {
                0L -> bytes.size.toLong() - cursor
                1L -> readU64(bytes, cursor + 8)
                else -> size32
            }
            if (type == expectedType) return cursor
            require(size >= 8L && size <= Int.MAX_VALUE.toLong())
            cursor += size.toInt()
        }
        error("Missing top-level box $expectedType")
    }

    private fun findAscii(bytes: ByteArray, value: String): Int {
        val target = value.toByteArray(Charsets.US_ASCII)
        for (offset in 0..bytes.size - target.size) {
            var matches = true
            for (index in target.indices) {
                if (bytes[offset + index] != target[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        error("Missing ASCII marker $value")
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL) }
        return value
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (24 - index * 8)).toByte() }
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> bytes[offset + index] = (value ushr (56 - index * 8)).toByte() }
    }

    private fun annexB(vararg units: ByteArray): ByteArray {
        val totalBytes = units.fold(0) { total, unit -> Math.addExact(total, unit.size + 4) }
        val result = ByteArray(totalBytes)
        var cursor = 0
        units.forEach { unit ->
            result[cursor + 3] = 1
            cursor += 4
            unit.copyInto(result, destinationOffset = cursor)
            cursor += unit.size
        }
        return result
    }

    private fun QuickTimeColorAtomConfig.toExpectation() = QuickTimeMovNclxExpectation(
        colorPrimaries = colorPrimaries,
        transferCharacteristics = transferCharacteristics,
        matrixCoefficients = matrixCoefficients,
        fullRange = fullRange
    )

    private fun QuickTimeColorAtomConfig.toEvidence() = QuickTimeMovNclxEvidence(
        colorPrimaries = colorPrimaries,
        transferCharacteristics = transferCharacteristics,
        matrixCoefficients = matrixCoefficients,
        fullRange = fullRange
    )

    companion object {
        private fun hex(value: String): ByteArray =
            value
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
                .map { it.toInt(16).toByte() }
                .toByteArray()

        private val VPS = hex(
            "40 01 0c 01 ff ff 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 ac 09"
        )
        private val SPS_MAIN10 = hex(
            "42 01 01 22 20 00 00 03 00 b0 00 00 03 00 00 03 00 b4 a0 00 " +
                "80 08 00 90 13 65 ae e4 c9 2e a6 a1 22 01 20 5d a1 42 50"
        )
        private val PPS = hex(
            "44 01 c0 e3 0f 08 d0 60 30 18 0c 06 3d 84 20"
        )
        private val HLG_BT2020_FULL = QuickTimeColorAtomConfig(
            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_HLG,
            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
            fullRange = true
        )
    }
}
