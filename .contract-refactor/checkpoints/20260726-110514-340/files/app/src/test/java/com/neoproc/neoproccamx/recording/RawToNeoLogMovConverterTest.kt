package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the RAW→NeoLog MOV conversion pipeline (I/O-free core). Verifies it develops+packs
 * every frame from an injected source, emits one correctly-sized 10-bit sample per frame for both
 * output formats, and reports accurate per-clip counts — proving the sidecar → develop → pack chain
 * composes correctly before it is wired to the file reader / MOV muxer on-device.
 */
class RawToNeoLogMovConverterTest {

    private val converter = RawToNeoLogMovConverter()

    private fun meta(w: Int, h: Int) = RawDevelopMetadata(
        width = w, height = h,
        storageBitDepth = 12, sensorWhiteLevel = 1000,
        blackLevel = 0, whiteLevel = 1000,
        bayerPatternValue = 0,
        whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
        exposureTimeNs = 0L, analogGain = 1f, digitalGain = 1f, noiseS = 0f, noiseO = 0f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
        fps = 30, colorProfileTag = "N-LOG-BASE"
    )

    private fun grayFrames(count: Int, w: Int, h: Int, value: Int): Iterator<IntArray> =
        (0 until count).map { IntArray(w * h) { value } }.iterator()

    @Test
    fun convertsEveryFrameToOneCorrectlySizedMasterSample() {
        val w = 6; val h = 2
        val m = meta(w, h)
        val opts = RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.MASTER_444)
        val sizes = mutableListOf<Int>()
        val result = converter.convert(grayFrames(3, w, h, 180), m, opts) { sample, _ -> sizes.add(sample.size) }

        assertEquals(3, result.framesConverted)
        assertEquals(3, sizes.size)
        val expected = converter.sampleSize(m, opts)
        sizes.forEach { assertEquals(expected, it) }
        assertEquals(3L * expected, result.bytesWritten)
    }

    @Test
    fun convertsToV210CompatSampleSize() {
        val w = 6; val h = 2
        val m = meta(w, h)
        val opts = RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.COMPAT_V210)
        var got = -1
        converter.convert(grayFrames(1, w, h, 180), m, opts) { sample, _ -> got = sample.size }
        assertEquals(RawTenBitPacker.payloadSize(RawTenBitPacker.Format.V210_422, w, h), got)
    }

    @Test
    fun sampleIndicesAreSequentialAndSinkReceivesEveryFrame() {
        val seen = mutableListOf<Int>()
        val result = converter.convert(
            grayFrames(5, 6, 2, 500),
            meta(6, 2),
            RawToNeoLogMovConverter.Options()
        ) { _, index -> seen.add(index) }
        assertEquals(listOf(0, 1, 2, 3, 4), seen)
        assertEquals(5, result.framesConverted)
        assertTrue(result.bytesWritten > 0)
    }

    @Test
    fun convertStreamDevelopsEveryFrameFromAnNprawStream() {
        val w = 6; val h = 2
        val m = meta(w, h)
        val enc = { s: IntArray, ts: Long ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.encodeFrame(
                mode = RawCompressionMode.HIGH_COMPRESSION,
                rawSamples = s, width = w, height = h, rowStride = w * 2, pixelStride = 2,
                timestamp = ts, originalSize = w * h * 2, sensorWhiteLevel = 1000
            ).bytes
        }
        val stream = java.io.ByteArrayOutputStream().apply {
            write(enc(IntArray(w * h) { 180 }, 1L))
            write(enc(IntArray(w * h) { 180 }, 2L))
            write(enc(IntArray(w * h) { 180 }, 3L))
        }.toByteArray()

        val opts = RawToNeoLogMovConverter.Options()
        val sizes = mutableListOf<Int>()
        val result = converter.convertStream(java.io.ByteArrayInputStream(stream), m, opts) { s, _ -> sizes.add(s.size) }

        assertEquals(3, result.framesConverted)
        val expected = converter.sampleSize(m, opts)
        sizes.forEach { assertEquals(expected, it) }
    }

    @Test
    fun parallelStreamConvertIsByteIdenticalToSequentialAndInOrder() {
        // DISTINCT content per frame so any reorder/race would change the per-index bytes and be caught.
        val w = 16; val h = 8; val frames = 12
        val m = meta(w, h)
        val opts = RawToNeoLogMovConverter.Options(format = RawToNeoLogMovConverter.OutputFormat.MASTER_444)
        val enc = { s: IntArray, ts: Long ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.encodeFrame(
                mode = RawCompressionMode.HIGH_COMPRESSION,
                rawSamples = s, width = w, height = h, rowStride = w * 2, pixelStride = 2,
                timestamp = ts, originalSize = w * h * 2, sensorWhiteLevel = 1000
            ).bytes
        }
        val streamBytes = java.io.ByteArrayOutputStream().apply {
            for (f in 0 until frames) {
                write(enc(IntArray(w * h) { (it + f * 37) % 1000 }, (f + 1).toLong()))
            }
        }.toByteArray()

        val sequential = LinkedHashMap<Int, ByteArray>()
        converter.convertStream(java.io.ByteArrayInputStream(streamBytes), m, opts) { s, i -> sequential[i] = s }

        val parallel = LinkedHashMap<Int, ByteArray>()
        val progressCounts = mutableListOf<Int>()
        val result = converter.convertStreamParallel(
            java.io.ByteArrayInputStream(streamBytes), m, opts, parallelism = 4,
            onFrame = { done -> progressCounts.add(done) }
        ) { s, i -> parallel[i] = s }

        assertEquals(frames, result.framesConverted)
        // Sink must receive every frame exactly once, in ascending index order (reorder buffer works).
        assertEquals((0 until frames).toList(), parallel.keys.toList())
        // Progress fires once per frame, monotonically 1..frames.
        assertEquals((1..frames).toList(), progressCounts)
        // Every frame's developed bytes must be identical to the single-threaded reference.
        sequential.forEach { (i, expected) ->
            assertArrayEquals("frame $i must match sequential develop", expected, parallel[i])
        }
    }

    @Test
    fun parallelStreamConvertWithSingleWorkerMatchesSequential() {
        val w = 6; val h = 2
        val m = meta(w, h)
        val opts = RawToNeoLogMovConverter.Options()
        val enc = { s: IntArray, ts: Long ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.encodeFrame(
                mode = RawCompressionMode.HIGH_COMPRESSION,
                rawSamples = s, width = w, height = h, rowStride = w * 2, pixelStride = 2,
                timestamp = ts, originalSize = w * h * 2, sensorWhiteLevel = 1000
            ).bytes
        }
        val streamBytes = java.io.ByteArrayOutputStream().apply {
            write(enc(IntArray(w * h) { 200 }, 1L))
            write(enc(IntArray(w * h) { 400 }, 2L))
        }.toByteArray()
        val counts = mutableListOf<Int>()
        val result = converter.convertStreamParallel(
            java.io.ByteArrayInputStream(streamBytes), m, opts, parallelism = 1,
            onFrame = { counts.add(it) }
        ) { _, _ -> }
        assertEquals(2, result.framesConverted)
        assertEquals(listOf(1, 2), counts)
    }

    @Test
    fun midGrayDevelopsToNonZeroCodesThroughTheFullChain() {
        // 18% gray through develop+pack must produce non-trivial (non-black) sample bytes.
        val sample = converter.developAndPack(
            rawSamples = IntArray(6 * 2) { 180 },
            meta = meta(6, 2),
            options = RawToNeoLogMovConverter.Options()
        )
        assertTrue("developed master sample must not be all-zero", sample.any { it.toInt() != 0 })
    }

    @Test
    fun directAcescgPackingMatchesFinishThenPackForBothMovFormats() {
        val w = 8
        val h = 4
        val acescg = FloatArray(w * h * 3) { i ->
            when (i % 3) {
                0 -> 0.02f + (i % 11) * 0.015f
                1 -> 0.03f + (i % 7) * 0.012f
                else -> 0.01f + (i % 5) * 0.018f
            }
        }
        listOf(RawTenBitPacker.Format.R210_444, RawTenBitPacker.Format.V210_422).forEach { format ->
            val expected = RawTenBitPacker.pack(
                RawNeoLogDevelop.finishFromAcescg(
                    acescg = acescg.copyOf(),
                    width = w,
                    height = h,
                    variant = NeoLogCurve.Variant.BASE,
                    exposureStops = 0f,
                    outputBitDepth = 10
                ),
                format
            )
            val direct = RawAcescgTenBitPacker.pack(
                acescg = acescg.copyOf(),
                width = w,
                height = h,
                variant = NeoLogCurve.Variant.BASE,
                exposureStops = 0f,
                outputBitDepth = 10,
                format = format
            )
            assertArrayEquals("direct ACEScg pack must match $format", expected, direct)
        }
    }
}
