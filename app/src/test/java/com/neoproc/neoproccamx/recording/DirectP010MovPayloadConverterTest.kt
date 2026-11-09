package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectP010MovPayloadConverterTest {
    @Test
    fun directV210ConversionMatchesHeapPathIncludingAlignedPadding() {
        listOf(6 to 4, 47 to 3, 49 to 5).forEach { (width, height) ->
            val p010 = frame(width, height, seed = width + height)
            val expected = Npv10MovConverter.p010ToV210Payload(p010, width, height)
            val actual = convertDirect(
                p010,
                width,
                height,
                Npv10MovConverter.OutputFormat.V210_422,
                QuickTimeColorAtomConfig.rec709SdrLimited()
            )

            assertArrayEquals("v210 parity ${width}x$height", expected, actual)
        }
    }

    @Test
    fun directR210ConversionMatchesHeapPathForRec709AndBt2020Ranges() {
        val width = 19
        val height = 7
        val p010 = frame(width, height, seed = 91)
        val configs = listOf(
            QuickTimeColorAtomConfig.rec709SdrLimited(),
            QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = true
            )
        )

        configs.forEach { config ->
            val expected = Npv10MovConverter.p010ToR210Payload(p010, width, height, config)
            val actual = convertDirect(
                p010,
                width,
                height,
                Npv10MovConverter.OutputFormat.R210_444,
                config
            )
            assertArrayEquals(expected, actual)
        }
    }

    @Test
    fun vectorizedLayoutValidationRejectsLowBitsInVectorAndTailSamples() {
        val width = 2
        val height = 2
        val valid = frame(width, height, seed = 11)
        listOf(0, valid.size - Short.SIZE_BYTES).forEach { corruptOffset ->
            val corrupt = valid.copyOf().also { bytes -> bytes[corruptOffset] = 1 }
            val source = ByteBuffer.allocateDirect(corrupt.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put(corrupt)
                    flip()
                }
            try {
                DirectP010MovPayloadConverter.validateP010PayloadLayout(source, width, height)
                fail("Expected low-bit P010 corruption at byte $corruptOffset to be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: validation remains strict after four-sample vectorization.
            }
        }
    }

    private fun convertDirect(
        p010: ByteArray,
        width: Int,
        height: Int,
        format: Npv10MovConverter.OutputFormat,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray {
        val source = ByteBuffer.allocateDirect(p010.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(p010)
            flip()
        }
        val outputBytes = DirectP010MovPayloadConverter.requiredOutputBytes(width, height, format)
        val output = ByteBuffer.allocateDirect(outputBytes).apply {
            while (hasRemaining()) put(0x5A.toByte())
            clear()
        }
        val converted = DirectP010MovPayloadConverter.convert(
            p010Payload = source,
            width = width,
            height = height,
            outputFormat = format,
            colorConfig = colorConfig,
            target = output
        )
        return ByteArray(converted.remaining()).also(converted::get)
    }

    private fun frame(width: Int, height: Int, seed: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val samples = width * height + chromaWidth * chromaHeight * 2
        return ByteBuffer.allocate(samples * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(samples) { index ->
                    val code = (index * 73 + seed * 31 + (index / width) * 17) and 0x3FF
                    putShort((code shl 6).toShort())
                }
            }
            .array()
    }
}
