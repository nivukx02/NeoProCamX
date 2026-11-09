package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class Npv10Range10CodecTest {
    @Test
    fun allTenBitCodesAndBoundaryTailsRoundTripWithoutExpansion() {
        for (samples in listOf(1, 2, 3, 4, 31, 32, 33, 63, 64, 65, 127, 128, 129,
            1024, 262143, 262144, 262145, 524289)) {
            for (pattern in 0..3) {
                val random = Random(samples + pattern)
                val decoded = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(samples) { sample ->
                    val code = when (pattern) {
                        0 -> if (sample and 1 == 0) 100 else 900
                        1 -> sample and 1023
                        2 -> random.nextInt(1024)
                        else -> (if (sample and 1 == 0) 64 else 600) + random.nextInt(16)
                    }
                    decoded.putShort((code shl 6).toShort())
                }
                val source = decoded.array()
                val encoded = Npv10ChunkedLosslessCodec.encodeP010(source, rangePacking = true)
                assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
                assertTrue(encoded.size <= Npv10ChunkedLosslessCodec.maximumStoredBytes(source.size.toLong()))
                assertArrayEquals("samples=$samples pattern=$pattern", source,
                    Npv10ChunkedLosslessCodec.decodeP010(encoded, source.size))
            }
        }
    }

    @Test
    fun goldenLaneHeadersAreIndependentOfLz4AndPreserveChromaSeparation() {
        val source = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(64) { putShort(((if (it and 1 == 0) 100 else 900) shl 6).toShort()) }
        }.array()
        val encoded = Npv10ChunkedLosslessCodec.encodeP010(source, rangePacking = true)
        assertEquals(2, ByteBuffer.wrap(encoded).getInt(32))
        assertArrayEquals(byteArrayOf(100, 0, 0x84.toByte(), 3), encoded.copyOfRange(44, encoded.size))
        assertArrayEquals(source, Npv10ChunkedLosslessCodec.decodeP010(encoded, source.size))
    }

    @Test
    fun legacyLz4AndRawPacketsRemainReadable() {
        val source = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(1024) { putShort(((it and 1023) shl 6).toShort()) }
        }.array()
        val legacy = Npv10ChunkedLosslessCodec.encodeP010(source)
        assertEquals(1, ByteBuffer.wrap(legacy).getInt(4))
        assertArrayEquals(source, Npv10ChunkedLosslessCodec.decodeP010(legacy, source.size))
    }

    @Test
    fun everyTruncationAndCorruptRangeHeaderFailsClosed() {
        val source = ByteArray(128)
        val packet = Npv10ChunkedLosslessCodec.encodeP010(source, rangePacking = true)
        for (size in packet.indices) {
            assertFalse("truncation=$size", Npv10ChunkedLosslessCodec.hasValidEnvelope(packet.copyOf(size), 128))
        }
        fun rejected(mutate: (ByteArray) -> Unit) {
            val changed = packet.copyOf().also(mutate)
            assertFalse(Npv10ChunkedLosslessCodec.hasValidEnvelope(changed, source.size))
        }
        rejected { it[7] = 1 } // new flags cannot masquerade as v1
        rejected { it[7] = 3 }
        rejected { it[45] = 0x40 } // reserved bit
        rejected { it[45] = 0x2c } // eleven-bit residual
        rejected { it[44] = 1 } // valid code change still fails original CRC
        rejected { it[35] = 3 } // unknown transform
        assertFalse(Npv10ChunkedLosslessCodec.hasValidEnvelope(packet + byteArrayOf(0), source.size))
    }

    @Test
    fun overflowAndNonzeroTailPaddingAndEmptyLaneAreRejected() {
        fun rejected(input: ByteArray, samples: Int) {
            assertTrue(runCatching {
                Npv10Range10Codec.decode(input, 0, input.size, samples, ByteArray((samples * 10 + 7) / 8), 0)
            }.isFailure)
        }
        rejected(byteArrayOf(0xff.toByte(), 7, 1, 0, 0), 2) // 1023 + 1 overflows precision
        rejected(byteArrayOf(0, 4, 0x80.toByte(), 0, 0), 2) // one residual bit, seven padding bits
        rejected(byteArrayOf(0, 0, 1, 0), 1) // absent odd lane cannot have a minimum
    }
}
