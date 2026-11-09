package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
            repeat(frames) { write(enc(IntArray(w * h) { 180 }, w, h, it.toLong())) }
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
}
