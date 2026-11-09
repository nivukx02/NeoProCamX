package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99OfflineP010PixelProcessorTest {
    @Test
    fun `CPU processing preserves source and all ten P010 bits across the complete raster`() {
        val width = 4
        val height = 4
        val source = constantP010(width, height, lumaCode = 513, chromaCode = 511)
        val sourceBefore = source.copyOf()
        val processor = processor(width, height, expectedFrames = 2L)

        val first = processor.processCpu(
            frameIndex = 0L,
            sensorTimestampNs = 1_000_000_000L,
            sourceP010 = source,
            previousSourceP010 = null,
            profile = identityProfile(),
            liveCinemaSharpness = null,
            liveCinemaDenoise = null,
            gradeLut = null,
            gradeLutSize = 0,
            saturation = 1f,
            warmth = 0f
        )
        val second = processor.processCpu(
            frameIndex = 1L,
            sensorTimestampNs = 1_040_000_000L,
            sourceP010 = source,
            previousSourceP010 = source,
            profile = identityProfile(),
            liveCinemaSharpness = null,
            liveCinemaDenoise = null,
            gradeLut = null,
            gradeLutSize = 0,
            saturation = 1f,
            warmth = 0f
        )

        assertArrayEquals(sourceBefore, source)
        assertNotSame(source, first)
        assertNotSame(source, second)
        assertEquals(513, readP010Code(first, 0))
        assertTrue("A non-eight-bit-aligned code must survive", readP010Code(first, 0) % 4 != 0)
        for (offset in first.indices step Short.SIZE_BYTES) {
            assertEquals("P010 padding bits must stay zero", 0, readWord(first, offset) and 0x3f)
        }

        val evidence = processor.requireComplete(actualFrames = 2L)
        assertTrue(evidence.complete)
        assertEquals(2L, evidence.cpuFrames)
        assertEquals(1L, evidence.temporalFrames)
        assertEquals(0L, evidence.residualWhiteBalanceFrames)
        assertEquals(width.toLong() * height * 2L, evidence.fullFrameLumaSamples)
        assertEquals(16L, evidence.fullFrameChromaSamples)
        assertFalse(evidence.metadata()["niv99OfflinePixelProcessingProven"].isNullOrBlank())
    }

    @Test
    fun `publication rejects incomplete processing coverage`() {
        val processor = processor(width = 4, height = 4, expectedFrames = 2L)
        processor.recordGpuFrame(
            frameIndex = 0L,
            sensorTimestampNs = 1_000L,
            output = constantP010(4, 4, lumaCode = 513, chromaCode = 511),
            temporalHistoryUsed = false,
            residualWhiteBalanceApplied = false,
            processingTimeNs = 2_000_000L
        )

        assertThrows(IllegalStateException::class.java) {
            processor.requireComplete(actualFrames = 2L)
        }
    }

    @Test
    fun `publication rejects duplicate frame identities even when frame count matches`() {
        val processor = processor(width = 4, height = 4, expectedFrames = 2L)
        val output = constantP010(4, 4, lumaCode = 513, chromaCode = 511)
        repeat(2) {
            processor.recordParityGatedFrame(
                frameIndex = 0L,
                sensorTimestampNs = 1_000L,
                output = output,
                temporalHistoryUsed = false,
                residualWhiteBalanceApplied = false,
                processingTimeNs = 1_000_000L
            )
        }

        assertThrows(IllegalStateException::class.java) {
            processor.requireComplete(actualFrames = 2L)
        }
    }

    @Test
    fun `wrong P010 geometry is rejected before processing evidence can be recorded`() {
        val processor = processor(width = 4, height = 4, expectedFrames = 1L)

        assertThrows(IllegalArgumentException::class.java) {
            processor.recordGpuFrame(
                frameIndex = 0L,
                sensorTimestampNs = 1_000L,
                output = ByteArray(8),
                temporalHistoryUsed = false,
                residualWhiteBalanceApplied = false,
                processingTimeNs = 0L
            )
        }
    }

    private fun processor(width: Int, height: Int, expectedFrames: Long) =
        Niv99OfflineP010PixelProcessor(
            plan = Niv99OfflineConversionPolicy.npv10Sdr(),
            width = width,
            height = height,
            fps = 25,
            expectedFrames = expectedFrames
        )

    private fun identityProfile() = Npv10ExportEnhancementProfile(
        chromaSuppression = 0.0,
        shadowChromaDeadband = 0,
        fineTextureThreshold = Int.MAX_VALUE - 2,
        mediumEdgeThreshold = Int.MAX_VALUE - 1,
        strongEdgeThreshold = Int.MAX_VALUE,
        fineTextureDetailGain = 0.0,
        mediumDetailGain = 0.0,
        strongDetailGain = 0.0,
        aliasSafeMaxDetailDelta = 0,
        temporalLumaBlend = 1.0,
        temporalChromaBlend = 1.0,
        ditherAmplitude = 0.0,
        normalSdrCalibrationStrength = 0.0,
        cumulativeDetailBudget = 0
    )

    private fun constantP010(
        width: Int,
        height: Int,
        lumaCode: Int,
        chromaCode: Int
    ): ByteArray {
        val lumaSamples = width * height
        val chromaSamples = ((width + 1) / 2) * ((height + 1) / 2) * 2
        return ByteArray((lumaSamples + chromaSamples) * Short.SIZE_BYTES).also { payload ->
            repeat(lumaSamples) { writeP010Code(payload, it * Short.SIZE_BYTES, lumaCode) }
            repeat(chromaSamples) { index ->
                writeP010Code(payload, (lumaSamples + index) * Short.SIZE_BYTES, chromaCode)
            }
        }
    }

    private fun writeP010Code(payload: ByteArray, offset: Int, code: Int) {
        val word = code.coerceIn(0, 1023) shl 6
        payload[offset] = word.toByte()
        payload[offset + 1] = (word ushr 8).toByte()
    }

    private fun readP010Code(payload: ByteArray, offset: Int): Int = readWord(payload, offset) ushr 6

    private fun readWord(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xff) or ((payload[offset + 1].toInt() and 0xff) shl 8)
}
