package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuColorScience
import com.neoproc.neoproccamx.rendering.GpuColorSpace
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@LargeTest
@RunWith(AndroidJUnit4::class)
class Niv99CanonicalP010NativeInstrumentedTest {
    @Test
    fun canonicalP010_identityIsBitExactForEverySupportedTransfer() {
        assertTrue(Npv10FramePacker.isNiv99P010NativeProcessingAvailable())
        val bytes = p010Bytes(WIDTH, HEIGHT)
        Npv10NativeFrameBuffer.allocate(bytes).use { sourceLease ->
            Npv10NativeFrameBuffer.allocate(bytes).use { outputLease ->
                fillPattern(sourceLease.buffer, WIDTH, HEIGHT, lumaOffset = 0)
                val sourceBefore = bytes(sourceLease.buffer, bytes)
                supportedEncodings().forEach { encoding ->
                    val output = prepare(outputLease.buffer, bytes)
                    val token = Npv10FramePacker.processNiv99CanonicalP010ToP010(
                        canonicalP010 = view(sourceLease.buffer, bytes),
                        previousCanonicalP010 = null,
                        width = WIDTH,
                        height = HEIGHT,
                        target = output,
                        encoding = encoding,
                        uniforms = identityUniforms(),
                        residualWhiteBalanceAllowed = true
                    )
                    assertTrue("NIV99 rejected $encoding", token >= 0L)
                    assertEquals(false, Npv10FramePacker.niv99P010TemporalUsed(token))
                    assertByteExact(sourceBefore, output, bytes, "identity $encoding")
                }
                assertByteExact(sourceBefore, sourceLease.buffer, bytes, "immutable source")
            }
        }
    }

    @Test
    fun temporalAdmissionUsesDistinctSourceHistoryAndRejectsSceneCuts() {
        assertTrue(Npv10FramePacker.isNiv99P010NativeProcessingAvailable())
        val bytes = p010Bytes(WIDTH, HEIGHT)
        Npv10NativeFrameBuffer.allocate(bytes).use { currentLease ->
            Npv10NativeFrameBuffer.allocate(bytes).use { previousLease ->
                Npv10NativeFrameBuffer.allocate(bytes).use { outputLease ->
                    fillPattern(currentLease.buffer, WIDTH, HEIGHT, lumaOffset = 2)
                    fillPattern(previousLease.buffer, WIDTH, HEIGHT, lumaOffset = 0)
                    val accepted = process(
                        currentLease.buffer,
                        previousLease.buffer,
                        outputLease.buffer,
                        bytes,
                        temporalUniforms()
                    )
                    assertTrue("Motion-safe temporal history was not admitted", accepted >= 0L)
                    assertTrue(Npv10FramePacker.niv99P010TemporalUsed(accepted))

                    fillConstant(previousLease.buffer, WIDTH, HEIGHT, lumaCode = 64)
                    fillConstant(currentLease.buffer, WIDTH, HEIGHT, lumaCode = 900)
                    val sceneCut = process(
                        currentLease.buffer,
                        previousLease.buffer,
                        outputLease.buffer,
                        bytes,
                        temporalUniforms()
                    )
                    assertTrue("Scene-cut frame was rejected instead of processed", sceneCut >= 0L)
                    assertFalse(Npv10FramePacker.niv99P010TemporalUsed(sceneCut))

                    val aliased = Npv10FramePacker.processNiv99CanonicalP010ToP010(
                        canonicalP010 = view(currentLease.buffer, bytes),
                        previousCanonicalP010 = view(previousLease.buffer, bytes),
                        width = WIDTH,
                        height = HEIGHT,
                        target = view(currentLease.buffer, bytes),
                        encoding = rec709Encoding(),
                        uniforms = temporalUniforms(),
                        residualWhiteBalanceAllowed = false
                    )
                    assertTrue("Native output/source alias must fail closed", aliased < 0L)
                }
            }
        }
    }

    private fun process(
        current: ByteBuffer,
        previous: ByteBuffer,
        output: ByteBuffer,
        bytes: Int,
        uniforms: GpuImageEnhancementUniforms
    ): Long = Npv10FramePacker.processNiv99CanonicalP010ToP010(
        canonicalP010 = view(current, bytes),
        previousCanonicalP010 = view(previous, bytes),
        width = WIDTH,
        height = HEIGHT,
        target = prepare(output, bytes),
        encoding = rec709Encoding(),
        uniforms = uniforms,
        residualWhiteBalanceAllowed = false
    )

    private fun identityUniforms(): GpuImageEnhancementUniforms =
        temporalUniforms().copy(
            spatialDenoise = 0f,
            chromaDenoise = 0f,
            sharpening = 0f,
            sharpeningFloor = 0f,
            sharpeningCeiling = 0f,
            temporalLumaBlend = 0f,
            temporalChromaBlend = 0f,
            sensorShotNoiseCoefficient = 0f,
            sensorReadNoiseCoefficient = 0f,
            signature = "niv99-native-identity"
        )

    private fun temporalUniforms(): GpuImageEnhancementUniforms = GpuImageEnhancementUniforms(
        signalFamily = GpuSignalFamily.SDR,
        colorSpace = GpuColorSpace.REC709,
        colorScience = GpuColorScience.NATURAL,
        activeTransport = GpuActiveTransport.REC709_SDR,
        temporalBlend = 0.3f,
        spatialDenoise = 0.42f,
        chromaDenoise = 0.64f,
        shadowBoost = 0f,
        sharpening = 0.38f,
        warmth = 0f,
        contrast = 1f,
        saturation = 1f,
        highlightCompression = 0f,
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
        signature = "niv99-native-temporal"
    )

    private fun supportedEncodings(): List<Npv10Niv99ColorEncoding> = listOf(
        rec709Encoding(),
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.SRGB),
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT2020_NCL, Npv10Niv99Transfer.HLG),
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT2020_NCL, Npv10Niv99Transfer.PQ)
    )

    private fun rec709Encoding() =
        Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709)

    private fun fillPattern(buffer: ByteBuffer, width: Int, height: Int, lumaOffset: Int) {
        val lumaSamples = width * height
        val sampleCount = lumaSamples + lumaSamples / 2
        val words = prepare(buffer, sampleCount * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(lumaSamples) { index ->
            val code = 64 + ((index * 29 + lumaOffset) % 877)
            words.putShort(index * Short.SIZE_BYTES, (code shl 6).toShort())
        }
        repeat(lumaSamples / 4) { cell ->
            val offset = (lumaSamples + cell * 2) * Short.SIZE_BYTES
            val cb = 64 + ((cell * 17) % 897)
            val cr = 64 + ((cell * 31) % 897)
            words.putShort(offset, (cb shl 6).toShort())
            words.putShort(offset + Short.SIZE_BYTES, (cr shl 6).toShort())
        }
    }

    private fun fillConstant(buffer: ByteBuffer, width: Int, height: Int, lumaCode: Int) {
        val lumaSamples = width * height
        val sampleCount = lumaSamples + lumaSamples / 2
        val words = prepare(buffer, sampleCount * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(lumaSamples) { words.putShort(it * Short.SIZE_BYTES, (lumaCode shl 6).toShort()) }
        repeat(lumaSamples / 2) { index ->
            words.putShort((lumaSamples + index) * Short.SIZE_BYTES, (512 shl 6).toShort())
        }
    }

    private fun assertByteExact(
        expected: ByteArray,
        actual: ByteBuffer,
        byteCount: Int,
        label: String
    ) {
        val view = view(actual, byteCount)
        for (index in 0 until byteCount) {
            assertEquals("$label differs at byte $index", expected[index], view.get(index))
        }
    }

    private fun bytes(buffer: ByteBuffer, byteCount: Int): ByteArray =
        ByteArray(byteCount).also { view(buffer, byteCount).get(it) }

    private fun prepare(buffer: ByteBuffer, byteCount: Int): ByteBuffer = buffer.apply {
        position(0)
        limit(byteCount)
    }

    private fun view(buffer: ByteBuffer, byteCount: Int): ByteBuffer =
        buffer.duplicate().apply {
            position(0)
            limit(byteCount)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)

    private fun p010Bytes(width: Int, height: Int): Int =
        Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        ).toInt()

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 32
    }
}
