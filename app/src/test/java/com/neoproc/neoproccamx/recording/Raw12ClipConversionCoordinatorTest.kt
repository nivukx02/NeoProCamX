package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * End-to-end contract for the RAW clip → 10-bit NeoLog .MOV conversion. QuickTimeMovFileWriter is pure
 * RandomAccessFile, so this produces a REAL .mov on the JVM whose box structure is asserted — catching
 * the structural class of the prior "undecodable" failures without a device. (Player playback + the
 * exact r210 colr tagging remain device-verified.)
 */
class Raw12ClipConversionCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val coordinator = Raw12ClipConversionCoordinator()

    private fun meta(w: Int, h: Int) = RawDevelopMetadata(
        width = w, height = h, storageBitDepth = 12, sensorWhiteLevel = 1000,
        blackLevel = 0, whiteLevel = 1000, bayerPatternValue = 0,
        whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
        exposureTimeNs = 0L, analogGain = 1f, digitalGain = 1f, noiseS = 0f, noiseO = 0f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ, fps = 30, colorProfileTag = "N-LOG-BASE"
    )

    private fun enc(samples: IntArray, w: Int, h: Int, ts: Long): ByteArray =
        NprawFrameCodec.encodeFrame(
            mode = RawCompressionMode.HIGH_COMPRESSION, rawSamples = samples, width = w, height = h,
            rowStride = w * 2, pixelStride = 2, timestamp = ts, originalSize = w * h * 2, sensorWhiteLevel = 1000
        ).bytes

    private fun streamOf(frames: Int, w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().apply {
            repeat(frames) { frame ->
                write(
                    enc(
                        IntArray(w * h) { 180 },
                        w,
                        h,
                        SOURCE_TIMESTAMP_ORIGIN_NS + frame * SOURCE_FRAME_DURATION_NS
                    )
                )
            }
        }.toByteArray()

    private fun containsFourCc(b: ByteArray, cc: String): Boolean {
        val t = cc.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - t.size) {
            for (j in t.indices) if (b[i + j] != t[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun producesAStructurallyValidMovWithOneSamplePerFrame() {
        val w = 6; val h = 2
        val out = tmp.newFile("clip_v210.mov")
        val result = coordinator.convert(
            ByteArrayInputStream(streamOf(4, w, h)), meta(w, h).copy(orientationHintDegrees = 90),
            RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.COMPAT_V210),
            out.absolutePath
        )
        assertEquals(4, result.framesConverted)
        assertTrue(out.length() > 0)
        val bytes = out.readBytes()
        assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII)) // first box is ftyp
        assertTrue("moov present", containsFourCc(bytes, "moov"))
        assertTrue("mdat present", containsFourCc(bytes, "mdat"))
        assertTrue("v210 sample entry present", containsFourCc(bytes, "v210"))
        val tkhdTypeOffset = findFourCc(bytes, "tkhd")
        assertTrue(tkhdTypeOffset >= 4)
        assertEquals(0, readSignedInt(bytes, tkhdTypeOffset + 44))
        assertEquals(0x00010000, readSignedInt(bytes, tkhdTypeOffset + 48))
        assertEquals(-0x00010000, readSignedInt(bytes, tkhdTypeOffset + 56))
    }

    @Test
    fun masterFormatEmitsAnR210SampleEntry() {
        val w = 6; val h = 2
        val out = tmp.newFile("clip_444.mov")
        coordinator.convert(
            ByteArrayInputStream(streamOf(2, w, h)), meta(w, h),
            RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.MASTER_444),
            out.absolutePath
        )
        assertTrue("r210 sample entry present", containsFourCc(out.readBytes(), "r210"))
    }

    @Test
    fun missingRequiredAudioFailsBeforeVideoReadAndBeforeOutputMutation() {
        val out = tmp.newFile("keep-existing.mov").apply { writeText("unchanged") }
        val input = object : java.io.InputStream() {
            override fun read(): Int = error("Video must not be consumed before audio admission")
        }
        assertThrows(IllegalStateException::class.java) {
            coordinator.convert(input, meta(6, 2).copy(audioExpected = true),
                RawToNeoLogMovConverter.Options(), out.absolutePath)
        }
        assertEquals("unchanged", out.readText())
    }

    @Test
    fun capturedAudioPacketsAreCopiedAndWrongMasterAudioIsRejected() {
        val anchor = tmp.newFile("stream.npraw")
        val header = Npv10AudioSidecarHeader(48_000, 2, 256_000,
            SOURCE_TIMESTAMP_ORIGIN_NS, byteArrayOf(0x11, 0x90.toByte()))
        val origin = Npv10AacTimelineOriginPolicy.plan(0, 48_000, 2, header.codecConfig)
        Npv10AudioSidecarWriter(anchor, header).use { audio ->
            repeat(12) { index ->
                audio.writePacket(Npv10AudioPacket(
                    RecordingAudioSyncPolicy.durationUsForFrames(index * 1_024L, 48_000),
                    0, origin.newSilenceAccessUnit()
                ))
            }
            audio.finish()
        }
        val before = Npv10AudioSidecarPolicy.sidecarFor(anchor).readBytes()
        val out = tmp.newFile("with-captured-audio.mov")
        coordinator.convert(ByteArrayInputStream(streamOf(8, 6, 2)),
            meta(6, 2).copy(audioExpected = true), RawToNeoLogMovConverter.Options(),
            out.absolutePath, audioSidecarMaster = anchor)
        assertTrue(containsFourCc(out.readBytes(), "mp4a"))
        assertTrue(before.contentEquals(Npv10AudioSidecarPolicy.sidecarFor(anchor).readBytes()))

        val wrongSource = enc(IntArray(12) { 180 }, 6, 2, SOURCE_TIMESTAMP_ORIGIN_NS + 1_000L)
        val rejected = File(tmp.root, "wrong-master.mov")
        assertThrows(IllegalStateException::class.java) {
            coordinator.convert(ByteArrayInputStream(wrongSource), meta(6, 2).copy(audioExpected = true),
                RawToNeoLogMovConverter.Options(), rejected.absolutePath, audioSidecarMaster = anchor)
        }
        assertTrue(!rejected.exists())
        assertTrue(before.contentEquals(Npv10AudioSidecarPolicy.sidecarFor(anchor).readBytes()))
    }

    @Test
    fun uncompressedMovUsesLastMeasuredSourceIntervalForFinalSampleDuration() {
        val w = 6
        val h = 2
        val timestamps = longArrayOf(
            SOURCE_TIMESTAMP_ORIGIN_NS,
            SOURCE_TIMESTAMP_ORIGIN_NS + 40_000_000L,
            SOURCE_TIMESTAMP_ORIGIN_NS + 85_125_000L
        )
        val stream = ByteArrayOutputStream().apply {
            timestamps.forEach { timestamp ->
                write(enc(IntArray(w * h) { 180 }, w, h, timestamp))
            }
        }.toByteArray()
        val out = tmp.newFile("variable_timing.mov")

        coordinator.convert(
            ByteArrayInputStream(stream),
            meta(w, h),
            RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.COMPAT_V210),
            out.absolutePath
        )

        val bytes = out.readBytes()
        val mdhdTypeOffset = findFourCc(bytes, "mdhd")
        assertTrue("mdhd present", mdhdTypeOffset >= 4)
        assertEquals("MOV media timescale", 1_000_000, readSignedInt(bytes, mdhdTypeOffset + 16))
        assertEquals(
            "last sample duration must reuse the final measured 45.125ms source interval",
            130_250,
            readSignedInt(bytes, mdhdTypeOffset + 20)
        )
    }

    @Test
    fun convertsFromAnOnDiskClipReadingSidecarAndStream() {
        val w = 6; val h = 2
        val session = tmp.newFolder("NEO_RADIANCE_RAW_20260706_120000")
        java.io.File(session, Raw12MasterStore.STREAM_NAME).writeBytes(streamOf(3, w, h))
        java.io.File(session, Raw12MasterStore.META_NAME)
            .writeBytes(RawDevelopMetadataSidecar.encode(meta(w, h)))

        val clip = Raw12MasterStore(requireNotNull(session.parentFile)).resolveClip(session)
        requireNotNull(clip)
        val out = tmp.newFile("from_clip.mov")
        val result = coordinator.convert(clip, RawToNeoLogMovConverter.Options(), out.absolutePath)

        assertEquals(3, result.framesConverted)
        assertTrue(out.length() > 0)
        assertEquals("ftyp", String(out.readBytes(), 4, 4, Charsets.US_ASCII))
    }

    @Test
    fun rawNdrAndNivukxOutputsAreTruthfullyTaggedBt2020Pq() {
        for (profile in listOf(
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION
        )) {
            for (format in RawToNeoLogMovConverter.OutputFormat.entries) {
                val config = coordinator.colorConfigFor(
                    RawToNeoLogMovConverter.Options(
                        format = format,
                        deliveryProfile = profile
                    )
                )
                assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, config.colorPrimaries)
                assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, config.transferCharacteristics)
                if (format == RawToNeoLogMovConverter.OutputFormat.MASTER_444) {
                    assertEquals(QuickTimeColorAtomPolicy.MATRIX_RGB_IDENTITY, config.matrixCoefficients)
                    assertTrue(config.fullRange)
                } else {
                    assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, config.matrixCoefficients)
                    assertTrue(!config.fullRange)
                }
                val expectedPeak = if (profile == RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS) {
                    5_000.0
                } else {
                    10_000.0
                }
                assertEquals(expectedPeak, requireNotNull(config.masteringDisplay).maxLuminanceNits, 0.0)
            }
        }
    }

    @Test
    fun uncompressedRawPqMovsCarryTheirExactSampleEntryColorAndMasteringMetadata() {
        val width = 6
        val height = 2
        for (profile in listOf(
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION
        )) {
            for ((format, fourCc) in listOf(
                RawToNeoLogMovConverter.OutputFormat.MASTER_444 to "r210",
                RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 to "v210"
            )) {
                val output = tmp.newFile("${profile.fileSuffix}_${format.fileSuffix}.mov")
                val result = coordinator.convert(
                    ByteArrayInputStream(streamOf(3, width, height)),
                    meta(width, height),
                    RawToNeoLogMovConverter.Options(format = format, deliveryProfile = profile),
                    output.absolutePath
                )
                val bytes = output.readBytes()
                assertEquals(3, result.framesConverted)
                assertTrue("$fourCc sample entry present", containsFourCc(bytes, fourCc))
                assertTrue("nclx color atom present", containsFourCc(bytes, "nclx"))
                assertTrue("HDR mastering display atom present", containsFourCc(bytes, "mdcv"))
                assertTrue("content-light atom present", containsFourCc(bytes, "clli"))
                assertTrue(
                    "quality certificate must be emitted",
                    File("${output.absolutePath}.elite-conversion-certificate.txt").isFile
                )
            }
        }
    }

    @Test
    fun conversionFailureDeletesThePartialMovInsteadOfLeavingAnUnplayableFile() {
        val w = 6
        val h = 2
        val out = tmp.newFile("partial.mov")
        val staleLut = NeoLogRec709LutWriter.sidecarFile(out.absolutePath).apply {
            writeText("STALE LUT")
        }
        val staleCertificate = File("${out.absolutePath}.elite-conversion-certificate.txt").apply {
            writeText("STALE CERTIFICATE")
        }
        val corrupt = streamOf(2, w, h).copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x55).toByte()
        }

        runCatching {
            coordinator.convert(
                ByteArrayInputStream(corrupt),
                meta(w, h),
                RawToNeoLogMovConverter.Options(),
                out.absolutePath
            )
        }.getOrElse {
            assertTrue("failed conversion must delete the partial MOV", !out.exists())
            assertTrue("failed conversion must delete a stale/partial LUT", !staleLut.exists())
            assertTrue(
                "failed conversion must delete a stale/partial certificate",
                !staleCertificate.exists()
            )
            return
        }
        throw AssertionError("corrupt RAW stream should fail conversion")
    }

    private fun findFourCc(bytes: ByteArray, fourCc: String): Int {
        val target = fourCc.toByteArray(Charsets.US_ASCII)
        for (index in 0..bytes.size - target.size) {
            if (bytes.copyOfRange(index, index + target.size).contentEquals(target)) return index
        }
        return -1
    }

    private fun readSignedInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val SOURCE_TIMESTAMP_ORIGIN_NS = 5_000_000_000L
        const val SOURCE_FRAME_DURATION_NS = 33_333_333L
    }
}
