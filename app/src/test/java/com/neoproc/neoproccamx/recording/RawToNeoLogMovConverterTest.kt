package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailFlag
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `direct P010 packers are byte identical to array packers`() {
        val width = 10
        val height = 8
        val source = FloatArray(width * height * 3) { index ->
            0.01f + ((index * 37 + index / 11) % 997) / 997f
        }

        fun directPlane(): java.nio.FloatBuffer = ByteBuffer
            .allocateDirect(source.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(source); clear() }

        fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also {
            buffer.duplicate().get(it)
        }

        val neoExpected = RawAcescgTenBitPacker.packP010420(
            source.copyOf(), width, height, NeoLogCurve.Variant.BASE
        )
        val neoOutput = ByteBuffer.allocateDirect(neoExpected.size).order(ByteOrder.LITTLE_ENDIAN)
        val neoActual = RawAcescgTenBitPacker.packP010420Direct(
            directPlane(), width, height, NeoLogCurve.Variant.BASE, neoOutput
        )
        assertArrayEquals(neoExpected, bytes(neoActual))

        for (mode in NdrNivukxDeliveryEngine.Mode.entries) {
            val expected = RawAcescgTenBitPacker.packNdrP010420(
                source.copyOf(), width, height, mode
            )
            val output = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN)
            val actual = RawAcescgTenBitPacker.packNdrP010420Direct(
                directPlane(), width, height, mode, output
            )
            assertArrayEquals("direct $mode P010 must match array packing", expected, bytes(actual))
        }
    }

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
    fun convertsRawDevelopToCanonicalMain10P010Payload() {
        val w = 6
        val h = 4
        val m = meta(w, h)
        val opts = RawToNeoLogMovConverter.Options(
            format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
        )
        val sample = converter.developAndPack(IntArray(w * h) { 180 }, m, opts)

        assertEquals(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt(), sample.size)
        var offset = 0
        while (offset + 1 < sample.size) {
            val word = (sample[offset].toInt() and 0xFF) or
                ((sample[offset + 1].toInt() and 0xFF) shl 8)
            assertEquals("P010 low six padding bits", 0, word and 0x3F)
            offset += 2
        }
        assertTrue(sample.any { it.toInt() != 0 })
    }

    @Test
    fun `direct P010 stream route preserves bytes order timestamps and NIV99 evidence on CPU fallback`() {
        val width = 10
        val height = 8
        val metadata = meta(width, height)
        val timestamps = listOf(7_000_000_000L, 7_040_000_000L, 7_080_000_000L)
        val stream = java.io.ByteArrayOutputStream().apply {
            timestamps.forEachIndexed { frame, timestamp ->
                write(
                    com.neoproc.neoproccamx.pipeline.NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = IntArray(width * height) { index ->
                            (180 + frame * 11 + index * 7).coerceAtMost(metadata.whiteLevel)
                        },
                        width = width,
                        height = height,
                        rowStride = width * 2,
                        pixelStride = 2,
                        timestamp = timestamp,
                        originalSize = width * height * 2,
                        sensorWhiteLevel = metadata.sensorWhiteLevel
                    ).bytes
                )
            }
        }.toByteArray()
        val options = RawToNeoLogMovConverter.Options(
            format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420,
            deliveryProfile = RawToNeoLogMovConverter.DeliveryProfile.NEOLOG
        )
        val expected = mutableListOf<ByteArray>()
        converter.convertStreamTimed(java.io.ByteArrayInputStream(stream), metadata, options) { sample, _, _ ->
            expected += sample
        }
        val actual = mutableListOf<ByteArray>()
        val actualTimestamps = mutableListOf<Long>()
        val progress = mutableListOf<Int>()
        RawDevelopGpuAccelerator.setDevelopGpuEnabled(false)
        try {
            val result = converter.convertStreamDirectP010Timed(
                input = java.io.ByteArrayInputStream(stream),
                meta = metadata,
                options = options,
                telemetry = RawConversionStageTelemetry("jvm-test"),
                onFrame = { progress += it }
            ) { sample, _, timestamp ->
                actual += ByteArray(sample.remaining()).also { sample.duplicate().get(it) }
                actualTimestamps += timestamp
            }

            assertEquals(expected.size, result.framesConverted)
            assertEquals(expected.sumOf { it.size.toLong() }, result.bytesWritten)
            assertEquals(timestamps, actualTimestamps)
            assertEquals(listOf(1, 2, 3), progress)
            assertEquals(3L, result.niv99Evidence?.processedFrames)
            assertEquals(0L, result.niv99Evidence?.gpuProcessedFrames)
            assertEquals(3L, result.niv99Evidence?.cpuProcessedFrames)
            expected.indices.forEach { frame -> assertArrayEquals(expected[frame], actual[frame]) }
        } finally {
            RawDevelopGpuAccelerator.setDevelopGpuEnabled(true)
        }
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
    fun timedParallelStreamKeepsEveryOriginalTimestampInFrameOrder() {
        val w = 8
        val h = 4
        val m = meta(w, h)
        val timestamps = listOf(
            8_000_000_000L,
            8_039_000_000L,
            8_081_500_000L,
            8_120_250_000L
        )
        val streamBytes = java.io.ByteArrayOutputStream().apply {
            timestamps.forEachIndexed { frame, timestamp ->
                write(
                    com.neoproc.neoproccamx.pipeline.NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = IntArray(w * h) { (it + frame * 19) % 1000 },
                        width = w,
                        height = h,
                        rowStride = w * 2,
                        pixelStride = 2,
                        timestamp = timestamp,
                        originalSize = w * h * 2,
                        sensorWhiteLevel = 1000
                    ).bytes
                )
            }
        }.toByteArray()
        val observed = mutableListOf<Pair<Int, Long>>()

        val result = converter.convertStreamParallelTimed(
            input = java.io.ByteArrayInputStream(streamBytes),
            meta = m,
            options = RawToNeoLogMovConverter.Options(),
            parallelism = 4
        ) { _, index, sourceTimestampNs ->
            observed += index to sourceTimestampNs
        }

        assertEquals(timestamps.size, result.framesConverted)
        assertEquals(timestamps.mapIndexed { index, timestamp -> index to timestamp }, observed)
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
    fun directAcescgR210PackingMatchesFinishThenPack() {
        val w = 8
        val h = 4
        val acescg = FloatArray(w * h * 3) { i ->
            when (i % 3) {
                0 -> 0.02f + (i % 11) * 0.015f
                1 -> 0.03f + (i % 7) * 0.012f
                else -> 0.01f + (i % 5) * 0.018f
            }
        }
        val format = RawTenBitPacker.Format.R210_444
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
        assertArrayEquals("direct ACEScg pack must preserve the R210 master", expected, direct)
    }

    @Test
    fun parityCompletedAcescgIsPackedWithoutApplyingDetailTwice() {
        val width = 10
        val height = 6
        val source = FloatArray(width * height * 3) { index ->
            0.02f + ((index * 17) % 101) / 140f
        }
        val originalFlag = RawDevelopEliteDetailFlag.enabled
        RawDevelopEliteDetailFlag.set(true)
        try {
            val normal = RawAcescgTenBitPacker.pack(
                source.copyOf(),
                width,
                height,
                NeoLogCurve.Variant.CINEMA,
                format = RawTenBitPacker.Format.R210_444
            )
            val alreadyEnhanced = source.copyOf().also {
                RawDevelopEliteDetailPolicy.enhanceInPlace(it, width, height)
            }
            val reused = RawAcescgTenBitPacker.pack(
                alreadyEnhanced,
                width,
                height,
                NeoLogCurve.Variant.CINEMA,
                format = RawTenBitPacker.Format.R210_444,
                detailAlreadyApplied = true
            )
            assertArrayEquals(normal, reused)

            val normalNdr = RawAcescgTenBitPacker.packNdrP010420(
                source.copyOf(),
                width,
                height,
                NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
            )
            val enhancedNdr = source.copyOf().also {
                RawDevelopEliteDetailPolicy.enhanceInPlace(it, width, height)
            }
            val reusedNdr = RawAcescgTenBitPacker.packNdrP010420(
                enhancedNdr,
                width,
                height,
                NdrNivukxDeliveryEngine.Mode.NDR10_PLUS,
                detailAlreadyApplied = true
            )
            assertArrayEquals(normalNdr, reusedNdr)
        } finally {
            RawDevelopEliteDetailFlag.set(originalFlag)
        }
    }

    @Test
    fun rawNdrPackerAnchorsDiffuseWhiteAndWritesCanonicalP010() {
        val w = 6
        val h = 4
        val acescg = FloatArray(w * h * 3) { 0.18f }
        val payload = RawAcescgTenBitPacker.packNdrP010420(
            acescg = acescg,
            width = w,
            height = h,
            mode = NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
        )

        assertEquals(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt(), payload.size)
        val expectedPq = NdrNivukxDeliveryEngine.deliverPqCodes(
            1.0,
            1.0,
            1.0,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
        )[0] / 1023.0
        val expectedLuma = kotlin.math.round(expectedPq * 876.0 + 64.0).toInt()
        val actualLuma = RecordDevelopP010ColorIngress.readLumaCode(payload, w, h, 0, 0)
        assertTrue("diffuse white must remain at the 203-nit PQ anchor", kotlin.math.abs(actualLuma - expectedLuma) <= 1)
        assertTrue(
            "neutral ACEScg must produce neutral P010 chroma",
            kotlin.math.abs(RecordDevelopP010ColorIngress.readChromaCode(payload, w, h, 0, 0, 0) - 512) <= 1 &&
                kotlin.math.abs(RecordDevelopP010ColorIngress.readChromaCode(payload, w, h, 0, 0, 1) - 512) <= 1
        )
        payload.indices.step(2).forEach { offset ->
            val word = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8)
            assertEquals("P010 low six padding bits", 0, word and 0x3F)
        }
    }

    @Test
    fun ndrAndNivukxRawDeliveriesAreDistinctAcrossEveryTenBitCarrierAndKeepInputImmutable() {
        val w = 6
        val h = 4
        val source = IntArray(w * h) { 720 }
        val sourceBefore = source.copyOf()
        fun convert(
            profile: RawToNeoLogMovConverter.DeliveryProfile,
            format: RawToNeoLogMovConverter.OutputFormat
        ): ByteArray =
            converter.developAndPack(
                source,
                meta(w, h),
                RawToNeoLogMovConverter.Options(
                    format = format,
                    deliveryProfile = profile
                )
            )

        RawToNeoLogMovConverter.OutputFormat.entries.forEach { format ->
            val ndrPayload = convert(RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS, format)
            val nivukxPayload = convert(RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION, format)
            assertArrayEquals("RAW input samples are an immutable master", sourceBefore, source)
            assertEquals(converter.sampleSize(meta(w, h), RawToNeoLogMovConverter.Options(format = format)), ndrPayload.size)
            assertEquals(ndrPayload.size, nivukxPayload.size)
            assertTrue(
                "$format must retain distinct XDR10+ and NIVUKX tone/color character",
                !ndrPayload.contentEquals(nivukxPayload)
            )
        }
    }

    @Test
    fun capturedRawCalibrationChangesEveryDevelopedDeliveryWithoutMutatingTheMaster() {
        val w = 10
        val h = 8
        val source = IntArray(w * h) { index -> 140 + ((index * 37) % 700) }
        val sourceBefore = source.copyOf()
        val uncalibrated = meta(w, h)
        val calibrated = uncalibrated.copy(
            blackLevelRggb = floatArrayOf(24f, 48f, 72f, 96f),
            lensShadingGridRggb = FloatArray(36) { index ->
                1.15f + (index / 4) * 0.035f + (index % 4) * 0.025f
            },
            lensShadingCorrectionEnabled = true
        )

        RawToNeoLogMovConverter.DeliveryProfile.entries.forEach { delivery ->
            RawToNeoLogMovConverter.OutputFormat.entries.forEach { format ->
                val options = RawToNeoLogMovConverter.Options(
                    format = format,
                    deliveryProfile = delivery
                )
                val baseline = converter.developAndPack(source, uncalibrated, options)
                val corrected = converter.developAndPack(source, calibrated, options)

                assertEquals(converter.sampleSize(calibrated, options), corrected.size)
                assertTrue(
                    "$delivery/$format must consume captured CFA black and lens-shading calibration",
                    !baseline.contentEquals(corrected)
                )
                assertArrayEquals("RAW master samples must stay immutable", sourceBefore, source)
            }
        }
    }

    @Test
    fun rawNdrUncompressedPackersUseCanonicalPqRanges() {
        val width = 6
        val height = 2
        val acescg = FloatArray(width * height * 3) { 0.18f }
        val expectedPqCode = NdrNivukxDeliveryEngine.deliverPqCodes(
            1.0,
            1.0,
            1.0,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
        )[0]

        val r210 = RawAcescgTenBitPacker.packNdrUncompressed(
            acescg.copyOf(),
            width,
            height,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS,
            RawTenBitPacker.Format.R210_444
        )
        val r210Word = ((r210[0].toInt() and 0xFF) shl 24) or
            ((r210[1].toInt() and 0xFF) shl 16) or
            ((r210[2].toInt() and 0xFF) shl 8) or
            (r210[3].toInt() and 0xFF)
        val r = (r210Word ushr 20) and 0x3FF
        val g = (r210Word ushr 10) and 0x3FF
        val b = r210Word and 0x3FF
        assertTrue(kotlin.math.abs(r - expectedPqCode) <= 1)
        assertTrue(kotlin.math.abs(g - expectedPqCode) <= 1)
        assertTrue(kotlin.math.abs(b - expectedPqCode) <= 1)

        val v210 = RawAcescgTenBitPacker.packNdrUncompressed(
            acescg.copyOf(),
            width,
            height,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS,
            RawTenBitPacker.Format.V210_422
        )
        val firstWord = (v210[0].toInt() and 0xFF) or
            ((v210[1].toInt() and 0xFF) shl 8) or
            ((v210[2].toInt() and 0xFF) shl 16) or
            ((v210[3].toInt() and 0xFF) shl 24)
        val cb = firstWord and 0x3FF
        val y = (firstWord ushr 10) and 0x3FF
        val cr = (firstWord ushr 20) and 0x3FF
        val expectedLimitedLuma = kotlin.math.round(expectedPqCode / 1023.0 * 876.0 + 64.0).toInt()
        assertTrue(kotlin.math.abs(y - expectedLimitedLuma) <= 1)
        assertTrue(kotlin.math.abs(cb - 512) <= 1)
        assertTrue(kotlin.math.abs(cr - 512) <= 1)
        assertEquals(
            RawTenBitPacker.payloadSize(RawTenBitPacker.Format.R210_444, width, height),
            r210.size
        )
        assertEquals(
            RawTenBitPacker.payloadSize(RawTenBitPacker.Format.V210_422, width, height),
            v210.size
        )
    }

    @Test
    fun everyNdrProfileAcceptsOnlyExplicitTenBitOutputFormats() {
        listOf(
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION
        ).forEach { profile ->
            RawToNeoLogMovConverter.OutputFormat.entries.forEach { format ->
                val options = RawToNeoLogMovConverter.Options(
                    format = format,
                    deliveryProfile = profile
                )
                assertEquals(format, options.format)
                assertEquals(profile, options.deliveryProfile)
                assertTrue(
                    "Every exposed RAW PQ carrier must remain explicitly 10-bit",
                    format == RawToNeoLogMovConverter.OutputFormat.MASTER_444 ||
                        format == RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 ||
                        format == RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
                )
            }
        }
    }
}
