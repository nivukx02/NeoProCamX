package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI/library integration proof; no camera FPS or image-quality claim. */
@RunWith(AndroidJUnit4::class)
class Npv10NativeDecoderBoundaryInstrumentedTest {
    @Test
    fun oversizedStoredLengthIsRejectedBeforeWritingOutput() {
        for (flags in intArrayOf(0, 1)) {
            for (storedBytes in intArrayOf(44, 45, 320, Int.MAX_VALUE, -1)) {
                val packet = ByteBuffer.allocateDirect(44).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(0x4e4c4332) // NLC2
                    putInt(1)
                    putInt(320) // 256 packed ten-bit samples
                    putInt(512)
                    putInt(256)
                    putInt(262144)
                    putInt(1)
                    putInt(0) // Irrelevant CRC: payload length must be rejected first.
                    putInt(flags)
                    putInt(256)
                    putInt(storedBytes)
                    flip()
                }
                val sentinel = ByteArray(512) { 0x5a.toByte() }
                val target = ByteBuffer.allocateDirect(sentinel.size).apply { put(sentinel); flip() }
                assertFalse(
                    "Malformed flags=$flags storedBytes=$storedBytes must fail in native code",
                    Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                        packet, 44, sentinel.size, target
                    )
                )
                target.clear()
                assertArrayEquals(sentinel, ByteArray(sentinel.size).also { target.get(it) })
            }
        }
    }

    @Test
    fun vectorAndUnalignedScalarDecodingPreserveEverySensorBitAndOutputGuard() {
        val codec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        for (samples in intArrayOf(1, 2, 3, 31, 32, 33, 1024, 262143, 262144, 262145, 524289)) {
            for (pattern in 0..2) {
                val expected = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    for (index in 0 until samples) {
                        val code = when (pattern) {
                            0 -> 512
                            1 -> (index * 37 + (index ushr 5)) and 1023
                            else -> (index / 64) and 1023
                        }
                        putShort((code shl 6).toShort())
                    }
                }.array()
                val source = ByteBuffer.allocateDirect(expected.size).apply { put(expected); flip() }
                val packet = ByteBuffer.allocateDirect(
                    Npv10PacketCodecEngine.storedBytes(expected.size.toLong(), codec).toInt()
                )
                val encoded = Npv10PacketCodecEngine.encodeChunkedLosslessNativeForTest(
                    source, expected.size, packet
                )
                assertNotNull("Native encoder must be available for the JNI regression", encoded)
                val encodedBytes = requireNotNull(encoded).encodedBytes
                for (offset in 0..1) {
                    val parent = ByteBuffer.allocateDirect(expected.size + offset + 16)
                    for (index in 0 until parent.capacity()) parent.put(index, 0x5a.toByte())
                    parent.position(offset)
                    val target = parent.slice()
                    assertTrue(
                        "Native decode samples=$samples pattern=$pattern offset=$offset",
                        Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                            packet, encodedBytes, expected.size, target
                        )
                    )
                    assertArrayEquals(expected, ByteArray(expected.size).also { target.get(it) })
                    for (index in 0 until offset) {
                        assertTrue("Prefix guard overwritten", parent.get(index) == 0x5a.toByte())
                    }
                    for (index in offset + expected.size until parent.capacity()) {
                        assertTrue("Suffix guard overwritten", parent.get(index) == 0x5a.toByte())
                    }
                }
            }
        }
    }
}
