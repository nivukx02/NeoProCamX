package com.neoproc.neoproccamx.recording

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuColorScience
import com.neoproc.neoproccamx.rendering.GpuColorSpace
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import com.neoproc.neoproccamx.ui.ConversionExecutionPowerGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil

/**
 * Physical-device performance gate for the fused native-SIMD stage used by NPV10/record-develop
 * conversion. This measures the complete 4096x3072 raster written into caller-owned encoder-ready
 * P010 storage; requested output cadence alone is not accepted as throughput evidence.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class Niv99OfflineConversionThroughputInstrumentedTest {
    /**
     * Deterministic, bounded regression oracle for the Vulkan export shader. The non-uniform source and
     * translated previous frame exercise the complete block-search halo without making a pre-change
     * fingerprint capture depend on the multi-minute full-frame benchmark.
     */
    @Test
    fun tiledRivenRegression640x480_vulkanFingerprints() {
        assertTrue(
            "NIV99 Vulkan P010 kernel unavailable: ${Npv10ExportGpuAccelerator.unavailableReason()}",
            Npv10ExportGpuAccelerator.isAccelerationAvailable()
        )
        val width = RIVEN_GOLDEN_WIDTH
        val height = RIVEN_GOLDEN_HEIGHT
        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillPatternP010(currentLease.buffer, width, height, translatedPrevious = false)
                    fillPatternP010(previousLease.buffer, width, height, translatedPrevious = true)
                    conversionProfiles().forEach { profile ->
                        val startedNs = SystemClock.elapsedRealtimeNanos()
                        val evidence = Npv10ExportGpuAccelerator.enhanceP010Direct(
                            current = preparedDuplicate(currentLease.buffer, frameBytes),
                            previous = preparedDuplicate(previousLease.buffer, frameBytes),
                            output = prepare(outputLease.buffer, frameBytes),
                            width = width,
                            height = height,
                            uniforms = profile.uniforms
                        )
                        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
                        assertTrue("${profile.name} Vulkan dispatch failed", evidence != null)
                        val result = "profile=${profile.name} size=${width}x$height " +
                            "elapsedMs=${ms(elapsedNs)} sha256=${sha256(outputLease.buffer, frameBytes)}"
                        Log.i(TAG, "NIV99_VULKAN_RIVEN_GOLDEN $result")
                        println("NIV99_VULKAN_RIVEN_GOLDEN $result")
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            EVIDENCE_STATUS_CODE,
                            Bundle().apply { putString("niv99VulkanRivenGolden", result) }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun tiledRivenFullFrame4096x3072_singleSdrTiming() {
        assertTrue(
            "NIV99 Vulkan P010 kernel unavailable: ${Npv10ExportGpuAccelerator.unavailableReason()}",
            Npv10ExportGpuAccelerator.isAccelerationAvailable()
        )
        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillP010(currentLease.buffer, frameBytes, lumaCode = 512, chromaCode = 512)
                    fillP010(previousLease.buffer, frameBytes, lumaCode = 508, chromaCode = 512)
                    val profile = conversionProfiles().first()
                    val samplesNs = LongArray(RIVEN_4K_MEASURED_RUNS)
                    repeat(RIVEN_4K_MEASURED_RUNS) { index ->
                        val startedNs = SystemClock.elapsedRealtimeNanos()
                        val evidence = Npv10ExportGpuAccelerator.enhanceP010Direct(
                            current = preparedDuplicate(currentLease.buffer, frameBytes),
                            previous = preparedDuplicate(previousLease.buffer, frameBytes),
                            output = prepare(outputLease.buffer, frameBytes),
                            width = WIDTH,
                            height = HEIGHT,
                            uniforms = profile.uniforms
                        )
                        samplesNs[index] = SystemClock.elapsedRealtimeNanos() - startedNs
                        assertTrue("${profile.name} Vulkan dispatch failed", evidence != null)
                    }
                    val result = "profile=${profile.name} size=${WIDTH}x$HEIGHT " +
                        "frames=$RIVEN_4K_MEASURED_RUNS samplesMs=" +
                        samplesNs.joinToString(prefix = "[", postfix = "]") { ms(it) }
                    Log.i(TAG, "NIV99_VULKAN_RIVEN_4K_TIMING $result")
                    println("NIV99_VULKAN_RIVEN_4K_TIMING $result")
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        EVIDENCE_STATUS_CODE,
                        Bundle().apply { putString("niv99VulkanRiven4kTiming", result) }
                    )
                }
            }
        }
    }

    @Test
    fun fullFrame4096x3072_vulkanGoldenFingerprints() {
        assertTrue(
            "NIV99 Vulkan P010 kernel unavailable: ${Npv10ExportGpuAccelerator.unavailableReason()}",
            Npv10ExportGpuAccelerator.isAccelerationAvailable()
        )
        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillP010(currentLease.buffer, frameBytes, lumaCode = 512, chromaCode = 512)
                    fillP010(previousLease.buffer, frameBytes, lumaCode = 508, chromaCode = 512)
                    conversionProfiles().forEach { profile ->
                        val startedNs = SystemClock.elapsedRealtimeNanos()
                        val evidence = Npv10ExportGpuAccelerator.enhanceP010Direct(
                            current = preparedDuplicate(currentLease.buffer, frameBytes),
                            previous = preparedDuplicate(previousLease.buffer, frameBytes),
                            output = prepare(outputLease.buffer, frameBytes),
                            width = WIDTH,
                            height = HEIGHT,
                            uniforms = profile.uniforms
                        )
                        val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
                        assertTrue("${profile.name} Vulkan dispatch failed", evidence != null)
                        val sha256 = sha256(outputLease.buffer, frameBytes)
                        val result = "profile=${profile.name} size=${WIDTH}x$HEIGHT " +
                            "elapsedMs=${ms(elapsedNs)} sha256=$sha256"
                        Log.i(TAG, "NIV99_VULKAN_4K_GOLDEN $result")
                        println("NIV99_VULKAN_4K_GOLDEN $result")
                    }
                }
            }
        }
    }

    @Test
    fun fullFrame4096x3072_vulkanDiagnostic() {
        assertTrue(
            "NIV99 Vulkan P010 kernel unavailable: ${Npv10ExportGpuAccelerator.unavailableReason()}",
            Npv10ExportGpuAccelerator.isAccelerationAvailable()
        )
        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillP010(currentLease.buffer, frameBytes, lumaCode = 512, chromaCode = 512)
                    fillP010(previousLease.buffer, frameBytes, lumaCode = 508, chromaCode = 512)
                    conversionProfiles().forEach { profile ->
                        repeat(VULKAN_WARMUP_RUNS) {
                            checkNotNull(
                                Npv10ExportGpuAccelerator.enhanceP010Direct(
                                    current = preparedDuplicate(currentLease.buffer, frameBytes),
                                    previous = preparedDuplicate(previousLease.buffer, frameBytes),
                                    output = prepare(outputLease.buffer, frameBytes),
                                    width = WIDTH,
                                    height = HEIGHT,
                                    uniforms = profile.uniforms
                                )
                            )
                        }
                        val samplesNs = LongArray(VULKAN_MEASURED_RUNS)
                        repeat(VULKAN_MEASURED_RUNS) { index ->
                            val startedNs = SystemClock.elapsedRealtimeNanos()
                            val evidence = Npv10ExportGpuAccelerator.enhanceP010Direct(
                                current = preparedDuplicate(currentLease.buffer, frameBytes),
                                previous = preparedDuplicate(previousLease.buffer, frameBytes),
                                output = prepare(outputLease.buffer, frameBytes),
                                width = WIDTH,
                                height = HEIGHT,
                                uniforms = profile.uniforms
                            )
                            assertTrue("${profile.name} Vulkan dispatch failed", evidence != null)
                            samplesNs[index] = SystemClock.elapsedRealtimeNanos() - startedNs
                        }
                        val sorted = samplesNs.sorted()
                        val result = "profile=${profile.name} size=${WIDTH}x$HEIGHT " +
                            "frames=$VULKAN_MEASURED_RUNS medianMs=${ms(sorted[sorted.size / 2])} " +
                            "p95Ms=${ms(percentile(sorted, 0.95))} maxMs=${ms(sorted.last())}"
                        Log.i(TAG, "NIV99_VULKAN_4K_DIAGNOSTIC $result")
                        println("NIV99_VULKAN_4K_DIAGNOSTIC $result")
                    }
                }
            }
        }
    }

    @Test
    fun fullFrame4096x3072_offlineAuthorityIsNativeSimdNotVulkan() {
        assertTrue(
            "NIV99 offline native-SIMD kernel unavailable",
            Npv10FramePacker.isNiv99P010NativeProcessingAvailable()
        )

        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillP010(currentLease.buffer, frameBytes, lumaCode = 512, chromaCode = 512)
                    fillP010(previousLease.buffer, frameBytes, lumaCode = 508, chromaCode = 512)
                    val profile = conversionProfiles().first()
                    val processor = Niv99OfflineP010PixelProcessor(
                        plan = Niv99OfflineConversionPolicy.npv10(profile.name),
                        width = WIDTH,
                        height = HEIGHT,
                        fps = TARGET_FPS,
                        expectedFrames = 1L
                    )
                    assertTrue(
                        "NIV99 native-SIMD authority dispatch failed",
                        processor.processNativeDirect(
                            frameIndex = 0L,
                            sensorTimestampNs = SENSOR_TIMESTAMP_ORIGIN_NS,
                            sourceP010 = preparedDuplicate(currentLease.buffer, frameBytes),
                            previousSourceP010 = preparedDuplicate(previousLease.buffer, frameBytes),
                            outputP010 = prepare(outputLease.buffer, frameBytes),
                            encoding = profile.encoding,
                            uniforms = profile.uniforms,
                            residualWhiteBalanceAllowed = true
                        )
                    )
                    val evidence = processor.requireComplete(1L)
                    assertEquals(1L, evidence.nativeSimdFrames)
                    assertEquals(0L, evidence.gpuFrames)
                    assertEquals(0L, evidence.cpuFrames)
                }
            }
        }
    }

    @Test
    fun fullFrame4096x3072_allConversionProfilesMeetThe25FpsPixelDeadline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ConversionExecutionPowerGuard(context).run {
            verifyFullFrame4096x3072ConversionProfilesMeetThe25FpsPixelDeadline()
        }
    }

    private fun verifyFullFrame4096x3072ConversionProfilesMeetThe25FpsPixelDeadline() {
        assertTrue(
            "NIV99 offline native-SIMD kernel unavailable",
            Npv10FramePacker.isNiv99P010NativeProcessingAvailable()
        )

        val frameBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        Npv10NativeFrameBuffer.allocate(frameBytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(frameBytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(frameBytes).use { outputLease ->
                    fillP010(currentLease.buffer, frameBytes, lumaCode = 512, chromaCode = 512)
                    fillP010(previousLease.buffer, frameBytes, lumaCode = 508, chromaCode = 512)
                    val sourceFingerprint = sampledFingerprint(currentLease.buffer, frameBytes)
                    val deadlineFailures = ArrayList<String>()

                    conversionProfiles().forEach { profile ->
                        repeat(WARMUP_RUNS) {
                            assertTrue(
                                "${profile.name} NIV99 native-SIMD warmup failed",
                                runKernel(
                                    currentLease.buffer,
                                    previousLease.buffer,
                                    outputLease.buffer,
                                    frameBytes,
                                    profile.uniforms,
                                    profile.encoding
                                )
                            )
                        }

                        val processor = Niv99OfflineP010PixelProcessor(
                            plan = Niv99OfflineConversionPolicy.npv10(profile.name),
                            width = WIDTH,
                            height = HEIGHT,
                            fps = TARGET_FPS,
                            expectedFrames = MEASURED_RUNS.toLong()
                        )
                        val samplesNs = LongArray(MEASURED_RUNS)
                        repeat(MEASURED_RUNS) { index ->
                            prepare(outputLease.buffer, frameBytes)
                            val startedNs = SystemClock.elapsedRealtimeNanos()
                            val processed = processor.processNativeDirect(
                                frameIndex = index.toLong(),
                                sensorTimestampNs = SENSOR_TIMESTAMP_ORIGIN_NS +
                                    index.toLong() * FRAME_PERIOD_NS,
                                sourceP010 = preparedDuplicate(currentLease.buffer, frameBytes),
                                previousSourceP010 = preparedDuplicate(previousLease.buffer, frameBytes),
                                outputP010 = outputLease.buffer,
                                encoding = profile.encoding,
                                uniforms = profile.uniforms,
                                residualWhiteBalanceAllowed = true
                            )
                            val elapsedNs = SystemClock.elapsedRealtimeNanos() - startedNs
                            assertTrue(
                                "${profile.name} NIV99 native-SIMD dispatch failed",
                                processed
                            )
                            samplesNs[index] = elapsedNs
                        }

                        val complete = processor.requireComplete(MEASURED_RUNS.toLong())
                        assertEquals(MEASURED_RUNS.toLong(), complete.nativeSimdFrames)
                        assertEquals(0L, complete.cpuFrames)
                        assertEquals(0L, complete.gpuFrames)
                        val sorted = samplesNs.sorted()
                        val p95Ns = percentile(sorted, 0.95)
                        val p99Ns = percentile(sorted, 0.99)
                        val averageNs = samplesNs.average()
                        val maximumNs = samplesNs.max()
                        val evidence = "profile=${profile.name} size=${WIDTH}x$HEIGHT " +
                            "frames=$MEASURED_RUNS minMs=${ms(sorted.first())} " +
                            "medianMs=${ms(sorted[sorted.size / 2])} p95Ms=${ms(p95Ns)} " +
                            "p99Ms=${ms(p99Ns)} averageMs=${ms(averageNs)} maxMs=${ms(maximumNs)} " +
                            "fullFrameLumaSamples=${complete.fullFrameLumaSamples} " +
                            "fullFrameChromaSamples=${complete.fullFrameChromaSamples} " +
                            "samplesMs=${samplesNs.joinToString(prefix = "[", postfix = "]") { ms(it) }}"
                        Log.i(TAG, "NIV99_OFFLINE_4K_RESULT $evidence")
                        println("NIV99_OFFLINE_4K_RESULT $evidence")
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            EVIDENCE_STATUS_CODE,
                            Bundle().apply { putString("niv99Offline4kEvidence", evidence) }
                        )
                        if (p95Ns > FRAME_PERIOD_NS) {
                            deadlineFailures +=
                                "${profile.name} p95=${ms(p95Ns)}ms deadline=${ms(FRAME_PERIOD_NS)}ms"
                        }
                    }

                    assertEquals(
                        "NIV99 conversion mutated the source P010 buffer",
                        sourceFingerprint,
                        sampledFingerprint(currentLease.buffer, frameBytes)
                    )
                    assertNotEquals(
                        "NIV99 conversion produced no observable output payload",
                        0L,
                        sampledFingerprint(outputLease.buffer, frameBytes)
                    )
                    assertP010PaddingSamples(outputLease.buffer, frameBytes)
                    assertTrue(
                        "Full-frame NIV99 25fps failures: ${deadlineFailures.joinToString()}",
                        deadlineFailures.isEmpty()
                    )
                }
            }
        }
    }

    private fun runKernel(
        current: ByteBuffer,
        previous: ByteBuffer,
        output: ByteBuffer,
        frameBytes: Int,
        uniforms: GpuImageEnhancementUniforms,
        encoding: Npv10Niv99ColorEncoding
    ): Boolean {
        prepare(output, frameBytes)
        return Npv10FramePacker.processNiv99CanonicalP010ToP010(
            canonicalP010 = preparedDuplicate(current, frameBytes),
            previousCanonicalP010 = preparedDuplicate(previous, frameBytes),
            width = WIDTH,
            height = HEIGHT,
            target = output,
            encoding = encoding,
            uniforms = uniforms,
            residualWhiteBalanceAllowed = true
        ) >= 0L
    }

    private fun conversionProfiles(): List<Profile> {
        val base = GpuImageEnhancementUniforms(
            signalFamily = GpuSignalFamily.SDR,
            colorSpace = GpuColorSpace.REC709,
            colorScience = GpuColorScience.NATURAL,
            activeTransport = GpuActiveTransport.REC709_SDR,
            temporalBlend = 0.30f,
            spatialDenoise = 0.42f,
            chromaDenoise = 0.64f,
            shadowBoost = 0.08f,
            sharpening = 0.38f,
            warmth = 0f,
            contrast = 1f,
            saturation = 1f,
            highlightCompression = 0.24f,
            logStrength = 0f,
            videoHdrFusionStrength = 0f,
            shadowChromaDeadband = 36f / 1023f,
            aliasSafeMaxDetailDelta = 220f / 1023f,
            fineTextureThreshold = 8f / 1023f,
            mediumEdgeThreshold = 22f / 1023f,
            strongEdgeThreshold = 74f / 1023f,
            fineTextureDetailGain = 0f,
            mediumDetailGain = 0f,
            strongDetailGain = 0f,
            temporalLumaBlend = 0.70f,
            temporalChromaBlend = 0.70f,
            iso = 1600f,
            noiseMetric = 0.35f,
            localChromaSpikeSmoothing = 0.45f,
            sharpeningFloor = 0.22f,
            sharpeningCeiling = 0.48f,
            skinDetailProtection = 0.72f,
            migrationApplied = false,
            dualGainFusionEnabled = false,
            dualGainMotionMaskEnabled = false,
            dualGainKGain = 1f,
            dualGainNoiseFloor = 0f,
            dualGainSaturationThreshold = 0.94f,
            dualGainTimestampSkew = 0f,
            sensorShotNoiseCoefficient = 0.003f,
            sensorReadNoiseCoefficient = 0.002f,
            detailProfileId = 2f,
            subjectMaskAvailable = 0f,
            subjectDetailGain = 0f,
            subjectMaskFeather = 0f,
            signature = "niv99-offline-4k-sdr"
        )
        return listOf(
            Profile("SDR", base, bt709Encoding()),
            Profile(
                "SDR_DISPLAY_P3",
                base.copy(signature = "niv99-offline-4k-display-p3"),
                Npv10Niv99ColorEncoding(
                    matrix = Npv10Niv99ColorMatrix.BT709,
                    transfer = Npv10Niv99Transfer.SRGB
                )
            ),
            Profile(
                "LOG",
                base.copy(
                    signalFamily = GpuSignalFamily.LOG,
                    colorSpace = GpuColorSpace.REC2020,
                    colorScience = GpuColorScience.N_LOG_CINEMA,
                    activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
                    logStrength = 0.68f,
                    signature = "niv99-offline-4k-log"
                ),
                bt2020Encoding(Npv10Niv99Transfer.BT709)
            ),
            Profile(
                "NDR10_PLUS",
                base.copy(
                    signalFamily = GpuSignalFamily.HDR,
                    colorSpace = GpuColorSpace.REC2020,
                    colorScience = GpuColorScience.NDR10_PLUS,
                    activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10,
                    videoHdrFusionStrength = 0.82f,
                    signature = "niv99-offline-4k-ndr10-plus"
                ),
                bt2020Encoding(Npv10Niv99Transfer.PQ)
            ),
            Profile(
                "NIVUKX",
                base.copy(
                    signalFamily = GpuSignalFamily.HDR,
                    colorSpace = GpuColorSpace.REC2020,
                    colorScience = GpuColorScience.NIVUKX_VISION,
                    activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10,
                    videoHdrFusionStrength = 0.92f,
                    signature = "niv99-offline-4k-nivukx"
                ),
                bt2020Encoding(Npv10Niv99Transfer.PQ)
            )
        )
    }

    private fun bt709Encoding(): Npv10Niv99ColorEncoding =
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709)

    private fun bt2020Encoding(transfer: Npv10Niv99Transfer): Npv10Niv99ColorEncoding =
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT2020_NCL, transfer)

    private fun fillP010(buffer: ByteBuffer, frameBytes: Int, lumaCode: Int, chromaCode: Int) {
        val lumaBytes = WIDTH * HEIGHT * Short.SIZE_BYTES
        val lumaChunk = p010Chunk(lumaCode)
        val chromaChunk = p010Chunk(chromaCode)
        buffer.clear()
        putRepeated(buffer, lumaChunk, lumaBytes)
        putRepeated(buffer, chromaChunk, frameBytes - lumaBytes)
        buffer.flip()
    }

    private fun fillPatternP010(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        translatedPrevious: Boolean
    ) {
        fun lumaCodeAt(sourceX: Int, sourceY: Int): Int {
            val x = sourceX.coerceIn(0, width - 1)
            val y = sourceY.coerceIn(0, height - 1)
            val structure = ((x / 17) xor (y / 13)) * 19
            return 64 + Math.floorMod(x * 7 + y * 11 + structure, 877)
        }

        buffer.clear()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = if (translatedPrevious) x - 2 else x
                val sourceY = if (translatedPrevious) y + 1 else y
                putP010Code(buffer, lumaCodeAt(sourceX, sourceY))
            }
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                putP010Code(buffer, 384 + Math.floorMod(x * 5 + y * 3, 257))
                putP010Code(buffer, 384 + Math.floorMod(x * 3 + y * 7, 257))
            }
        }
        buffer.flip()
    }

    private fun putP010Code(buffer: ByteBuffer, code: Int) {
        val word = code.coerceIn(0, 1023) shl 6
        buffer.put(word.toByte())
        buffer.put((word ushr 8).toByte())
    }

    private fun p010Chunk(code: Int): ByteArray {
        val word = code.coerceIn(0, 1023) shl 6
        return ByteArray(FILL_CHUNK_BYTES).also { chunk ->
            var offset = 0
            while (offset < chunk.size) {
                chunk[offset] = word.toByte()
                chunk[offset + 1] = (word ushr 8).toByte()
                offset += Short.SIZE_BYTES
            }
        }
    }

    private fun putRepeated(target: ByteBuffer, chunk: ByteArray, byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            target.put(chunk, 0, count)
            remaining -= count
        }
    }

    private fun prepare(buffer: ByteBuffer, frameBytes: Int): ByteBuffer = buffer.apply {
        position(0)
        limit(frameBytes)
    }

    private fun preparedDuplicate(buffer: ByteBuffer, frameBytes: Int): ByteBuffer =
        buffer.duplicate().apply {
            position(0)
            limit(frameBytes)
        }.slice()

    private fun sampledFingerprint(buffer: ByteBuffer, frameBytes: Int): Long {
        val view = preparedDuplicate(buffer, frameBytes)
        var fingerprint = 0xcbf29ce484222325UL.toLong()
        var offset = 0
        while (offset + 1 < frameBytes) {
            fingerprint = (fingerprint xor (view.getShort(offset).toLong() and 0xffffL)) *
                0x100000001b3UL.toLong()
            offset += SAMPLE_STRIDE_BYTES
        }
        return fingerprint
    }

    private fun sha256(buffer: ByteBuffer, frameBytes: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val source = preparedDuplicate(buffer, frameBytes)
        val chunk = ByteArray(FILL_CHUNK_BYTES)
        while (source.hasRemaining()) {
            val count = minOf(source.remaining(), chunk.size)
            source.get(chunk, 0, count)
            digest.update(chunk, 0, count)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun assertP010PaddingSamples(buffer: ByteBuffer, frameBytes: Int) {
        val view = preparedDuplicate(buffer, frameBytes)
        var offset = 0
        while (offset + 1 < frameBytes) {
            // P010 is little-endian: the six padding bits are the low bits of the first byte.
            // Inspecting getShort() without forcing byte order can reverse the word in a slice.
            assertEquals("P010 padding bits at byte $offset", 0, view.get(offset).toInt() and 0x3f)
            offset += SAMPLE_STRIDE_BYTES
        }
    }

    private fun percentile(sorted: List<Long>, quantile: Double): Long {
        val rank = ceil(sorted.size * quantile).toInt().coerceAtLeast(1)
        return sorted[(rank - 1).coerceAtMost(sorted.lastIndex)]
    }

    private fun ms(nanos: Number): String =
        String.format(Locale.US, "%.3f", nanos.toDouble() / 1_000_000.0)

    private data class Profile(
        val name: String,
        val uniforms: GpuImageEnhancementUniforms,
        val encoding: Npv10Niv99ColorEncoding
    )

    private companion object {
        const val TAG = "Niv99Offline4K"
        const val WIDTH = 4096
        const val HEIGHT = 3072
        const val TARGET_FPS = 25
        const val EVIDENCE_STATUS_CODE = 2
        const val WARMUP_RUNS = 4
        const val MEASURED_RUNS = 40
        const val VULKAN_WARMUP_RUNS = 2
        const val VULKAN_MEASURED_RUNS = 10
        const val FILL_CHUNK_BYTES = 1 shl 20
        const val SAMPLE_STRIDE_BYTES = 4_096
        const val FRAME_PERIOD_NS = 1_000_000_000L / TARGET_FPS
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 2_000_000_000L
        const val RIVEN_GOLDEN_WIDTH = 640
        const val RIVEN_GOLDEN_HEIGHT = 480
        const val RIVEN_4K_MEASURED_RUNS = 3
    }
}
