package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

class Npv10PackedP010WordDecoderTest {
    @Test
    fun everyTenBitCodeAtEveryGroupPositionAndTailLengthIsExact() {
        for (code in 0..1023) {
            for (count in 1..11) {
                checkSamples(IntArray(count) { position -> (code + position * 337) and 1023 })
            }
        }
    }

    @Test
    fun deterministicRandomChunkBoundariesAndPartialGroupsPreserveEveryBit() {
        val random = Random(0x10_4096_3072L)
        for (count in intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 63, 64, 65,
                255, 256, 257, 262_143, 262_144, 262_145)) {
            checkSamples(IntArray(count) { random.nextInt(1024) })
        }
    }

    @Test
    fun malformedExtentsAndAliasingFailBeforeChangingTheDestination() {
        for ((packed, output) in listOf(
            ByteArray(2) to ByteArray(1) { 0x55 },
            ByteArray(1) to ByteArray(2) { 0x55 },
            ByteArray(3) to ByteArray(2) { 0x55 },
            ByteArray(4) to ByteArray(8) { 0x55 },
        )) {
            val before = output.copyOf()
            assertThrows(IllegalArgumentException::class.java) {
                Npv10PackedP010WordDecoder.decodeInto(packed, output)
            }
            assertArrayEquals(before, output)
        }
        val aliased = byteArrayOf(0x40, 0x10)
        val before = aliased.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            Npv10PackedP010WordDecoder.decodeInto(aliased, aliased)
        }
        assertArrayEquals(before, aliased)
    }

    @Test
    fun bothContainerVersionsStillValidateCrcAndRecoverOriginalWords() {
        val samples = IntArray(1031) { (it * 73) and 1023 }
        val expected = words(samples)
        for (range in listOf(false, true)) {
            val packet = Npv10ChunkedLosslessCodec.encodeP010(expected, rangePacking = range)
            assertArrayEquals(expected, Npv10ChunkedLosslessCodec.decodeP010(packet, expected.size))
            // This is the original-packed-sample CRC, not the stored-packet CRC.
            packet[28] = (packet[28].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) {
                Npv10ChunkedLosslessCodec.decodeP010(packet, expected.size)
            }
        }
    }

    private fun checkSamples(samples: IntArray) {
        // Deliberately independent scalar packing, not the production encoder.
        val packed = ByteArray((samples.size * 10 + 7) / 8)
        for (sample in samples.indices) {
            for (bit in 0 until 10) {
                val position = sample * 10 + bit
                packed[position ushr 3] = (packed[position ushr 3].toInt() or
                    (((samples[sample] ushr bit) and 1) shl (position and 7))).toByte()
            }
        }
        val originalPacked = packed.copyOf()
        val actual = ByteArray(samples.size * 2) { 0x7f }
        Npv10PackedP010WordDecoder.decodeInto(packed, actual)
        assertArrayEquals(words(samples), actual)
        assertArrayEquals(originalPacked, packed)
        for (offset in actual.indices step 2) assertEquals(0, actual[offset].toInt() and 63)
    }

    private fun words(samples: IntArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        for (index in samples.indices) {
            val word = samples[index] shl 6
            bytes[index * 2] = word.toByte()
            bytes[index * 2 + 1] = (word ushr 8).toByte()
        }
    }
}
