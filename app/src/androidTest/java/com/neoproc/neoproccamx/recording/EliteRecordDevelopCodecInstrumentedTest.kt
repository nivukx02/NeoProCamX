package com.neoproc.neoproccamx.recording

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Real-device Main10/P010 encode → MOV → decode round trip, including physical preallocation. */
@RunWith(AndroidJUnit4::class)
class EliteRecordDevelopCodecInstrumentedTest {
    private companion object {
        /** Synthetic Camera2 sensor-clock origin; NPV10 masters never store media-relative PTS. */
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 1_000_000_000L
        const val TEST_WAKE_LOCK_TIMEOUT_MS = 15L * 60L * 1_000L
    }

    private var conversionWakeLock: PowerManager.WakeLock? = null

    @Before
    fun keepPhysicalCodecOracleRunnable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        conversionWakeLock = requireNotNull(context.getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable for the LQ-031 physical codec oracle"
        }.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NeoProCamX:Lq031CodecOracle"
        ).apply {
            setReferenceCounted(false)
            acquire(TEST_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    @After
    fun releasePhysicalCodecOracleWakeLock() {
        conversionWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
        }
        conversionWakeLock = null
    }

    @Test
    fun rawNeoLogMain10Conversion_encodesOrProvesStrictHardwareRefusal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "raw-neolog-main10-${System.nanoTime()}.mov")
        val width = 640
        val height = 360
        val fps = 30
        val frameCount = 2
        val frameDurationNs = 1_000_000_000L / fps
        val sensorTimestampOriginNs = 1_000_000_000L
        val meta = RawDevelopMetadata(
            width = width,
            height = height,
            storageBitDepth = 12,
            sensorWhiteLevel = 4095,
            blackLevel = 64,
            whiteLevel = 4095,
            bayerPatternValue = 0,
            whiteBalanceR = 1f,
            whiteBalanceGr = 1f,
            whiteBalanceGb = 1f,
            whiteBalanceB = 1f,
            exposureTimeNs = 10_000_000L,
            analogGain = 1f,
            digitalGain = 1f,
            noiseS = 0.001f,
            noiseO = 0.0001f,
            sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
            fps = fps,
            colorProfileTag = "N-LOG-CINEMA"
        )
        val stream = java.io.ByteArrayOutputStream().apply {
            repeat(frameCount) { frameIndex ->
                val samples = IntArray(width * height) { pixel ->
                    (256 + (pixel % width) * 4 + frameIndex * 13).coerceIn(64, 4095)
                }
                write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = samples,
                        width = width,
                        height = height,
                        rowStride = width * 2,
                        pixelStride = 2,
                        // RAW timestamps are sensor nanoseconds. Values such as 1 ns and 2 ns both
                        // quantize to 0 us at the MOV boundary and correctly fail strict PTS checks.
                        // Exercise the codec with a physically meaningful 30 fps sensor cadence.
                        timestamp = sensorTimestampOriginNs + frameIndex * frameDurationNs,
                        originalSize = width * height * 2,
                        sensorWhiteLevel = 4095
                    ).bytes
                )
            }
        }.toByteArray()
        try {
            val capability = strictHevcCapability(width, height, fps)
            if (!capability.accepted) {
                assertStrictHevcRefusal(capability) {
                    Raw12ClipConversionCoordinator().convert(
                        streamInput = java.io.ByteArrayInputStream(stream),
                        meta = meta,
                        options = RawToNeoLogMovConverter.Options(
                            format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
                        ),
                        outputPath = output.absolutePath,
                        parallelism = 2
                    )
                }
                assertTrue("Strict admission must reject before creating an output MOV", !output.exists())
                return
            }
            val result = Raw12ClipConversionCoordinator().convert(
                streamInput = java.io.ByteArrayInputStream(stream),
                meta = meta,
                options = RawToNeoLogMovConverter.Options(
                    format = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
                ),
                outputPath = output.absolutePath,
                parallelism = 2
            )
            assertEquals(frameCount, result.framesConverted)
            assertTrue(output.length() > 0L)

            var decodedFrames = 0
            var sampledLumaSum = 0L
            var sampledLumaCount = 0L
            var sampledLumaMax = 0
            val decodedPresentationTimesUs = mutableListOf<Long>()
            RecordDevelopMediaCodecP010Reader(output).use { reader ->
                assertEquals(10, reader.format.storageBitDepth)
                while (true) {
                    val decoded = reader.readNextFrame() ?: break
                    decodedPresentationTimesUs += decoded.presentationTimeUs
                    var pixel = 0
                    while (pixel < width * height) {
                        val byteOffset = pixel * Short.SIZE_BYTES
                        val word = (decoded.p010[byteOffset].toInt() and 0xFF) or
                            ((decoded.p010[byteOffset + 1].toInt() and 0xFF) shl 8)
                        assertEquals("Converted P010 must remain MSB-justified", 0, word and 0x3F)
                        val luma10 = word ushr 6
                        sampledLumaSum += luma10.toLong()
                        sampledLumaCount += 1L
                        sampledLumaMax = maxOf(sampledLumaMax, luma10)
                        pixel += 257
                    }
                    decodedFrames += 1
                }
            }
            assertEquals(frameCount, decodedFrames)
            assertTrue(
                "Decoded RAW-to-MOV timestamps must remain strictly monotonic: " +
                    decodedPresentationTimesUs,
                decodedPresentationTimesUs.zipWithNext().all { (previous, current) ->
                    current > previous
                }
            )
            val sampledLumaAverage = sampledLumaSum.toDouble() / sampledLumaCount.coerceAtLeast(1L)
            assertTrue(
                "Developed Main10 output collapsed to black: avg=$sampledLumaAverage max=$sampledLumaMax",
                sampledLumaAverage >= 96.0 && sampledLumaMax >= 512
            )
        } finally {
            File(output.parentFile, "${output.nameWithoutExtension}_NeoLog_to_Rec709.cube").delete()
            output.delete()
        }
    }

    @Test
    fun npv10Main10Conversion_encodesOrProvesStrictHardwareRefusal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.cacheDir, "npv10-main10-source-${System.nanoTime()}.npv10")
        val output = File(context.cacheDir, "npv10-main10-output-${System.nanoTime()}.mov")
        val repeatedOutput = File(context.cacheDir, "npv10-main10-repeat-${System.nanoTime()}.mov")
        val width = 640
        val height = 360
        val fps = 30
        val frameCount = 3
        val frameDurationUs = 1_000_000L / fps
        try {
            Npv10FileWriter(
                outputFile = master,
                config = Npv10SessionConfig(
                    width = width,
                    height = height,
                    fps = fps,
                    mode = "PRO",
                    colorProfile = "Rec.709",
                    sourceFormat = "YCBCR_P010",
                    sourceBitDepth = 10,
                    payloadFormat = Npv10PayloadFormat.P010_LE,
                    featureMetadata = mapOf("selectedDetailProfile" to "FOCUSED_DETAIL"),
                    startedAtEpochMs = 1L
                )
            ).use { writer ->
                repeat(frameCount) { frameIndex ->
                    val payload = syntheticP010(width, height, frameIndex)
                    val direct = ByteBuffer.allocateDirect(payload.size).apply {
                        put(payload)
                        flip()
                    }
                    writer.writeCopiedP010Frame(
                        Npv10CopiedP010Frame(
                            timestampNs = SENSOR_TIMESTAMP_ORIGIN_NS +
                                frameIndex * frameDurationUs * 1_000L,
                            width = width,
                            height = height,
                            payload = direct,
                            payloadBytes = payload.size,
                            copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                        )
                    )
                }
            }

            val capability = strictHevcCapability(width, height, fps)
            if (!capability.accepted) {
                assertStrictHevcRefusal(capability) {
                    Npv10MovConverter.convertWithAudit(
                        input = master,
                        output = output,
                        outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
                    )
                }
                assertTrue("Strict admission must reject before creating an output MOV", !output.exists())
                return
            }

            val audit = Npv10MovConverter.convertWithAudit(
                input = master,
                output = output,
                outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
            )
            assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
            assertEquals("hvc1", audit.sampleEntry)
            assertEquals(frameCount.toLong(), audit.frameCount)
            assertTrue(audit.inputMasterHashUnchanged)

            var decodedFrames = 0
            var sampledLumaSum = 0L
            var sampledLumaCount = 0L
            var sampledLumaMax = 0
            RecordDevelopMediaCodecP010Reader(output).use { reader ->
                assertEquals(10, reader.format.storageBitDepth)
                while (true) {
                    val decoded = reader.readNextFrame() ?: break
                    var pixel = 0
                    while (pixel < width * height) {
                        val byteOffset = pixel * Short.SIZE_BYTES
                        val word = (decoded.p010[byteOffset].toInt() and 0xFF) or
                            ((decoded.p010[byteOffset + 1].toInt() and 0xFF) shl 8)
                        assertEquals("Converted P010 must remain MSB-justified", 0, word and 0x3F)
                        val luma10 = word ushr 6
                        sampledLumaSum += luma10.toLong()
                        sampledLumaCount += 1L
                        sampledLumaMax = maxOf(sampledLumaMax, luma10)
                        pixel += 257
                    }
                    decodedFrames += 1
                }
            }
            assertEquals(frameCount, decodedFrames)
            val sampledLumaAverage = sampledLumaSum.toDouble() / sampledLumaCount.coerceAtLeast(1L)
            assertTrue(
                "NPV10 develop output collapsed to black: avg=$sampledLumaAverage max=$sampledLumaMax",
                sampledLumaAverage >= 96.0 && sampledLumaMax >= 512
            )

            // A second export exercises the persistent NQIS hit path on the real device. It must preserve
            // the same Main10/container/frame guarantees while skipping repeated scene analysis.
            val repeatedAudit = Npv10MovConverter.convertWithAudit(
                input = master,
                output = repeatedOutput,
                outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
            )
            assertEquals(Npv10AuditStatus.PASS, repeatedAudit.auditStatus)
            assertEquals("hvc1", repeatedAudit.sampleEntry)
            assertEquals(frameCount.toLong(), repeatedAudit.frameCount)
            var repeatedDecodedFrames = 0
            RecordDevelopMediaCodecP010Reader(repeatedOutput).use { reader ->
                assertEquals(10, reader.format.storageBitDepth)
                while (reader.readNextFrame() != null) repeatedDecodedFrames += 1
            }
            assertEquals(frameCount, repeatedDecodedFrames)
        } finally {
            Npv10MovConverter.certificateSidecarFile(output).delete()
            Npv10MovConverter.certificateSidecarFile(repeatedOutput).delete()
            NeoQualityInstructionCache.deleteFor(master)
            master.delete()
            output.delete()
            repeatedOutput.delete()
        }
    }

    @Test
    fun fullSensorNpv10Main10Conversion_encodesOrProvesStrictHardwareRefusal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.cacheDir, "npv10-full-sensor-source-${System.nanoTime()}.npv10")
        val output = File(context.cacheDir, "npv10-full-sensor-output-${System.nanoTime()}.mov")
        val evidenceDirectory = File(
            context.filesDir, "neo-qa/lq001-fullsensor-route/${System.currentTimeMillis()}-${System.nanoTime()}"
        ).apply { check(mkdirs()) }
        val width = 4096
        val height = 3072
        val fps = 25
        val payloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        try {
            Npv10NativeFrameBuffer.allocate(payloadBytes).use { mappedPayload ->
                fillProductionP010(mappedPayload.buffer, width, height)
                Npv10FileWriter(
                    outputFile = master,
                    config = Npv10SessionConfig(
                        width = width,
                        height = height,
                        fps = fps,
                        mode = "PRO",
                        colorProfile = "Rec.709",
                        sourceFormat = "YCBCR_P010",
                        sourceBitDepth = 10,
                        payloadFormat = Npv10PayloadFormat.P010_LE,
                        featureMetadata = mapOf("selectedDetailProfile" to "FOCUSED_DETAIL"),
                        startedAtEpochMs = 1L
                    )
                ).use { writer ->
                    writer.writeCopiedP010Frame(
                        Npv10CopiedP010Frame(
                            timestampNs = SENSOR_TIMESTAMP_ORIGIN_NS,
                            width = width,
                            height = height,
                            payload = mappedPayload.buffer,
                            payloadBytes = payloadBytes,
                            copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                        )
                    )
                }
            }

            val capability = strictHevcCapability(width, height, fps)
            if (!capability.accepted) {
                assertStrictHevcRefusal(capability) {
                    Npv10MovConverter.convertWithAudit(
                        input = master,
                        output = output,
                        outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
                    )
                }
                assertTrue("Strict admission must reject before creating an output MOV", !output.exists())
                return
            }

            // Availability is sampled before conversion, not inferred from a successful output.
            // On a GPU-capable device a silent CPU fallback must still fail this test. The former
            // CPU-SIMD-only assertion incorrectly rejected the upgraded Vulkan export route.
            val gpuExpected = Npv10ExportGpuAccelerator.isAccelerationAvailable()
            val audit = Npv10MovConverter.convertWithAudit(
                input = master,
                output = output,
                outputFormat = Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
            )
            File(evidenceDirectory, "conversion-certificate.txt").writeText(audit.conversionCertificate)
            File(evidenceDirectory, "admission.txt").writeText(
                "gpuExpected=$gpuExpected\nwidth=$width\nheight=$height\nfps=$fps\n" +
                    "fixtureOnly=true\nphysicalCameraRecording=false\n"
            )
            val certificate = audit.conversionCertificate.lineSequence()
                .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            println("LQ001_FULL_SENSOR_ROUTE evidence=${evidenceDirectory.absolutePath} " +
                "gpuExpected=$gpuExpected path=${certificate["exportProcessingPath"]} " +
                "gpuFrames=${certificate["niv99OfflinePixelGpuFrames"]} " +
                "nativeSimdFrames=${certificate["niv99OfflinePixelNativeSimdFrames"]} " +
                "cpuFrames=${certificate["niv99OfflinePixelCpuFrames"]}")
            assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
            assertEquals("hvc1", audit.sampleEntry)
            assertEquals(1L, audit.frameCount)
            assertTrue(audit.inputMasterHashUnchanged)
            assertEquals("Every output must have a proven NIV99 pixel-processing record",
                "true", certificate["niv99OfflinePixelProcessingProven"])
            assertEquals("1", certificate["niv99OfflinePixelProcessedFrames"])
            assertEquals("A reference-CPU fallback is not an accelerated full-sensor pass",
                "0", certificate["niv99OfflinePixelCpuFrames"])
            if (gpuExpected) {
                assertTrue(
                    "GPU-capable full-sensor export silently fell back: ${certificate["exportProcessingPath"]}",
                    certificate["exportProcessingPath"] in setOf(
                        Npv10ExportGpuAccelerator.ZERO_COPY_VULKAN_PATH,
                        Npv10ExportGpuAccelerator.VULKAN_STAGING_PATH
                    )
                )
                assertEquals("1", certificate["niv99OfflinePixelGpuFrames"])
                assertEquals("0", certificate["niv99OfflinePixelNativeSimdFrames"])
            } else {
                assertEquals("NIV99_NATIVE_SIMD_P010_DIRECT", certificate["exportProcessingPath"])
                assertEquals("1", certificate["niv99OfflinePixelNativeSimdFrames"])
                assertEquals("0", certificate["niv99OfflinePixelGpuFrames"])
            }

            RecordDevelopMediaCodecP010Reader(output).use { reader ->
                assertEquals(width, reader.format.width)
                assertEquals(height, reader.format.height)
                assertEquals(10, reader.format.storageBitDepth)
                val decoded = requireNotNull(reader.readNextFrame())
                assertEquals(width, decoded.width)
                assertEquals(height, decoded.height)
                var sampledLumaMax = 0
                var pixel = 0
                while (pixel < width * height) {
                    val byteOffset = pixel * Short.SIZE_BYTES
                    val word = (decoded.p010[byteOffset].toInt() and 0xFF) or
                        ((decoded.p010[byteOffset + 1].toInt() and 0xFF) shl 8)
                    assertEquals("Decoded P010 must remain MSB-justified", 0, word and 0x3F)
                    sampledLumaMax = maxOf(sampledLumaMax, word ushr 6)
                    pixel += 4093
                }
                assertTrue("Full-sensor Main10 output collapsed to black", sampledLumaMax >= 512)
                assertEquals(null, reader.readNextFrame())
            }
            output.copyTo(File(evidenceDirectory, "decoded-and-verified-output.mov"))
            File(evidenceDirectory, "result.txt").writeText("PASS\n")
        } catch (failure: Throwable) {
            // Keep failed bytes/certificates before retiring the temporary fixture. A failure must
            // remain inspectable instead of being erased by the existing exact-once cleanup.
            if (master.exists()) master.copyTo(File(evidenceDirectory, "failed-source.npv10"))
            if (output.exists()) output.copyTo(File(evidenceDirectory, "failed-output.mov"))
            File(evidenceDirectory, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            Npv10MovConverter.certificateSidecarFile(output).delete()
            NeoQualityInstructionCache.deleteFor(master)
            master.delete()
            output.delete()
        }
    }

    @Test
    fun main10EncoderDecoderAndPreallocation_roundTripOrStrictlyRefuses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "elite-codec-roundtrip-${System.nanoTime()}.mov")
        val width = 1920
        val height = 1080
        val fps = 30
        val frameCount = 8
        val frameDurationUs = 1_000_000L / fps
        val capability = strictHevcCapability(width, height, fps)
        if (!capability.accepted) {
            assertStrictHevcRefusal(capability) {
                RecordDevelopHevcMovEncoder.requireStrictCapability(width, height, fps)
            }
            assertTrue("Strict admission must not create an output MOV", !output.exists())
            return
        }
        val reservationPlan = RecordingPreallocationPolicy.resolve(
            availableBytes = context.cacheDir.usableSpace,
            requestedBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
        )
        assertTrue("Device lacks safe space for the minimum reservation", reservationPlan.enabled)

        val writer = QuickTimeMovFileWriter(
            outputPath = output.absolutePath,
            containerLabel = "Elite Main10 device round trip",
            preallocationPlan = reservationPlan
        )
        try {
            assertTrue(writer.preallocationResult.method != RecordingPreallocationMethod.NONE)
            assertEquals(reservationPlan.reservationBytes, output.length())

            RecordDevelopHevcMovEncoder(
                writer = writer,
                width = width,
                height = height,
                fps = fps,
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                deliveryFormat = RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV
            ).use { encoder ->
                repeat(frameCount) { frameIndex ->
                    encoder.encodeFrame(
                        p010 = syntheticP010(width, height, frameIndex),
                        presentationTimeUs = frameIndex * frameDurationUs
                    )
                }
                assertEquals(
                    frameCount.toLong(),
                    encoder.finish(frameCount * frameDurationUs)
                )
            }
            writer.close()

            assertTrue(writer.preallocationResult.reservationTrimmed)
            assertTrue(writer.preallocationResult.synced)
            assertEquals(output.length(), writer.preallocationResult.committedBytes)
            assertTrue(output.length() in 1 until reservationPlan.reservationBytes)

            var decodedFrames = 0
            var sampledAbsoluteError = 0L
            var sampledValues = 0L
            RecordDevelopMediaCodecP010Reader(output).use { reader ->
                assertEquals(10, reader.format.storageBitDepth)
                while (true) {
                    val frame = reader.readNextFrame() ?: break
                    assertEquals(width, frame.width)
                    assertEquals(height, frame.height)
                    assertEquals(decodedFrames * frameDurationUs, frame.presentationTimeUs)
                    val expectedFrame = decodedFrames
                    var pixel = 0
                    while (pixel < width * height) {
                        val byteOffset = pixel * 2
                        val word = (frame.p010[byteOffset].toInt() and 0xFF) or
                            ((frame.p010[byteOffset + 1].toInt() and 0xFF) shl 8)
                        assertEquals("P010 must remain MSB-justified", 0, word and 0x3F)
                        val actual10 = word ushr 6
                        val x = pixel % width
                        val y = pixel / width
                        val expected10 = syntheticLuma10(x, y, expectedFrame)
                        sampledAbsoluteError += kotlin.math.abs(actual10 - expected10).toLong()
                        sampledValues += 1L
                        pixel += 4093
                    }
                    decodedFrames += 1
                }
            }
            assertEquals(frameCount, decodedFrames)
            val meanAbsoluteError = sampledAbsoluteError.toDouble() / sampledValues.coerceAtLeast(1L)
            assertTrue(
                "Elite Main10 round-trip luma MAE too high: $meanAbsoluteError codes",
                meanAbsoluteError <= 6.0
            )
        } finally {
            runCatching { writer.abort() }
            output.delete()
        }
    }

    @Test
    fun explicitR210Conversion_preservesUncompressedTenBitWithoutHevcFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val master = File(context.cacheDir, "npv10-r210-source-${System.nanoTime()}.npv10")
        val output = File(context.cacheDir, "npv10-r210-output-${System.nanoTime()}.mov")
        val width = 640
        val height = 360
        val fps = 30
        val frameCount = 3
        val frameDurationUs = 1_000_000L / fps
        try {
            Npv10FileWriter(
                outputFile = master,
                config = Npv10SessionConfig(
                    width = width,
                    height = height,
                    fps = fps,
                    mode = "PRO",
                    colorProfile = "Rec.709",
                    sourceFormat = "YCBCR_P010",
                    sourceBitDepth = 10,
                    payloadFormat = Npv10PayloadFormat.P010_LE,
                    featureMetadata = mapOf("selectedDetailProfile" to "FOCUSED_DETAIL"),
                    startedAtEpochMs = 1L
                )
            ).use { writer ->
                repeat(frameCount) { frameIndex ->
                    val payload = syntheticP010(width, height, frameIndex)
                    val direct = ByteBuffer.allocateDirect(payload.size).apply {
                        put(payload)
                        flip()
                    }
                    writer.writeCopiedP010Frame(
                        Npv10CopiedP010Frame(
                            timestampNs = SENSOR_TIMESTAMP_ORIGIN_NS +
                                frameIndex * frameDurationUs * 1_000L,
                            width = width,
                            height = height,
                            payload = direct,
                            payloadBytes = payload.size,
                            copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                        )
                    )
                }
            }

            val audit = Npv10MovConverter.convertWithAudit(
                input = master,
                output = output,
                outputFormat = Npv10MovConverter.OutputFormat.R210_444
            )
            assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
            assertEquals("r210", audit.sampleEntry)
            assertEquals(frameCount.toLong(), audit.frameCount)
            assertTrue(audit.inputMasterHashUnchanged)
            assertTrue(
                "NPV10 SDR conversion did not prove default-on NIV99",
                audit.conversionCertificate.contains("niv99OfflineEnabled=true")
            )
            assertTrue(
                "NPV10 SDR conversion recorded the wrong NIV99 route",
                audit.conversionCertificate.contains("niv99OfflineRoute=NPV10_SDR")
            )
            assertTrue(
                "NPV10 SDR conversion did not run its full-frame detail-protection stage",
                audit.conversionCertificate.contains("exportEdgeAwareDetailApplied=true")
            )

            val inspection = QuickTimeMovArtifactInspector.inspect(
                output,
                QuickTimeMovArtifactRequirements(
                    allowedVideoSampleEntries = setOf("r210"),
                    expectedVideoSampleEntry = "r210",
                    expectedWidth = width,
                    expectedHeight = height,
                    minimumSampleCount = frameCount.toLong()
                )
            ).requirePass()
            val video = requireNotNull(inspection.videoTrack)
            assertEquals(frameCount.toLong(), video.sampleCount)
            assertEquals(null, video.hevcConfiguration)
            assertEquals(
                RawTenBitPacker.payloadSize(RawTenBitPacker.Format.R210_444, width, height)
                    .toLong() * frameCount,
                video.samplePayloadBytes
            )
        } finally {
            Npv10MovConverter.certificateSidecarFile(output).delete()
            NeoQualityInstructionCache.deleteFor(master)
            master.delete()
            output.delete()
        }
    }

    private fun strictHevcCapability(
        width: Int,
        height: Int,
        fps: Int
    ): RecordDevelopEncoderStrictCapabilityReport =
        RecordDevelopHevcMovEncoder.strictCapabilityReport(width, height, fps).also { report ->
            assertTrue(
                "The device-independent HEVC ideal target must remain explicit",
                report.requiredBitrateBps >= RecordDevelopEncoderQualityPolicy.MINIMUM_BITRATE
            )
            assertTrue(
                "The strict HEVC minimum viable floor must never be reduced",
                report.minimumViableBitrateBps >=
                    RecordDevelopEncoderQualityPolicy.MINIMUM_VIABLE_BITRATE
            )
            if (report.accepted) {
                assertTrue(
                    "Accepted HEVC hardware must meet the minimum viable quality floor: $report",
                    requireNotNull(report.selectedConfiguredBitrateBps) >=
                        report.minimumViableBitrateBps
                )
                if (report.capabilityBound) {
                    assertEquals(
                        "Capability-bound HEVC must saturate the advertised device ceiling",
                        report.bestAdvertisedEligibleBitrateBps,
                        report.selectedConfiguredBitrateBps
                    )
                }
            }
            if (!report.accepted) {
                assertEquals(null, report.selectedCandidateName)
                assertTrue(
                    "Rejected hardware must advertise less than the viable quality floor or fail " +
                        "another strict Main10/P010 admission rule: $report",
                    report.bestAdvertisedEligibleBitrateBps < report.minimumViableBitrateBps ||
                        report.candidates.all { !it.capable }
                )
            }
        }

    private fun assertStrictHevcRefusal(
        report: RecordDevelopEncoderStrictCapabilityReport,
        action: () -> Unit
    ) {
        assertTrue("Expected an incapable-device report", !report.accepted)
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Strict HEVC conversion unexpectedly succeeded on incapable hardware", failure != null)
        val messages = generateSequence(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = " | ")
        assertTrue(
            "Failure did not identify strict Main10/P010 admission: $messages",
            messages.contains("Strict HEVC Main10/P010 admission rejected")
        )
        assertTrue(
            "Failure did not prove transport preservation: $messages",
            messages.contains("output transport was not substituted")
        )
    }

    private fun syntheticP010(width: Int, height: Int, frameIndex: Int): ByteArray {
        val bytes = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                writeP010(bytes, offset, syntheticLuma10(x, y, frameIndex))
                offset += 2
            }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        repeat(chromaHeight) {
            repeat(chromaWidth) {
                writeP010(bytes, offset, 512)
                writeP010(bytes, offset + 2, 512)
                offset += 4
            }
        }
        assertEquals(bytes.size, offset)
        return bytes
    }

    private fun fillProductionP010(target: ByteBuffer, width: Int, height: Int) {
        require(width % 2 == 0 && height % 2 == 0)
        val lumaRow = ByteArray(width * Short.SIZE_BYTES)
        repeat(width) { x ->
            val luma10 = 64 + (x * 876 / (width - 1))
            writeP010(lumaRow, x * Short.SIZE_BYTES, luma10)
        }
        val chromaRow = ByteArray(width * Short.SIZE_BYTES)
        repeat(width / 2) { x ->
            val offset = x * 2 * Short.SIZE_BYTES
            writeP010(chromaRow, offset, 512)
            writeP010(chromaRow, offset + Short.SIZE_BYTES, 512)
        }
        target.clear()
        repeat(height) { target.put(lumaRow) }
        repeat(height / 2) { target.put(chromaRow) }
        target.flip()
        assertEquals(
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height),
            target.remaining().toLong()
        )
    }

    private fun syntheticLuma10(x: Int, y: Int, frameIndex: Int): Int =
        (64 + ((x * 3 + y * 2 + frameIndex * 11) % 877)).coerceIn(64, 940)

    private fun writeP010(bytes: ByteArray, offset: Int, value10: Int) {
        val word = value10.coerceIn(0, 1023) shl 6
        bytes[offset] = word.toByte()
        bytes[offset + 1] = (word ushr 8).toByte()
    }
}
