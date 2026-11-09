package com.neoproc.neoproccamx.recording

/**
 * Exact PACKED_P010_10_LE_V1 -> P010 unpacking for the independent Kotlin decoder.
 *
 * Four ten-bit samples occupy five bytes. Loading that group once avoids 40 separate
 * bit reads, without relying on ART having JIT-compiled a large bit-at-a-time loop.
 * This is only a representation change: all ten sensor code bits survive and the
 * six P010 padding bits remain zero. Container bounds and CRCs are still verified
 * by the caller before this method runs.
 */
internal object Npv10PackedP010WordDecoder {
    fun decodeInto(packed: ByteArray, output: ByteArray) {
        require(output.size % Short.SIZE_BYTES == 0) { "P010 output must contain complete words" }
        val samples = output.size / Short.SIZE_BYTES
        val expectedPackedBytes = (samples.toLong() * 10L + 7L) / 8L
        require(packed.size.toLong() == expectedPackedBytes) { "Packed P010 sample extent mismatch" }
        require(packed !== output) { "Packed input must not alias its decoded output" }

        var sourceOffset = 0
        var outputOffset = 0
        val groupedOutputBytes = output.size - output.size % 8
        while (outputOffset < groupedOutputBytes) {
            val b0 = packed[sourceOffset].toInt() and 0xff
            val b1 = packed[sourceOffset + 1].toInt() and 0xff
            val b2 = packed[sourceOffset + 2].toInt() and 0xff
            val b3 = packed[sourceOffset + 3].toInt() and 0xff
            val b4 = packed[sourceOffset + 4].toInt() and 0xff

            // Store the P010 words directly, including their six zero low bits.
            output[outputOffset] = (b0 shl 6).toByte()
            output[outputOffset + 1] = ((b0 ushr 2) or ((b1 and 3) shl 6)).toByte()
            output[outputOffset + 2] = ((b1 and 12) shl 4).toByte()
            output[outputOffset + 3] = ((b1 ushr 4) or ((b2 and 15) shl 4)).toByte()
            output[outputOffset + 4] = ((b2 and 48) shl 2).toByte()
            output[outputOffset + 5] = ((b2 ushr 6) or ((b3 and 63) shl 2)).toByte()
            output[outputOffset + 6] = (b3 and 192).toByte()
            output[outputOffset + 7] = b4.toByte()
            sourceOffset += 5
            outputOffset += 8
        }

        // At most three samples remain. Their offsets are relative to this final
        // group, so large valid frame lengths cannot overflow a bit-position Int.
        var tailBitOffset = 0
        while (outputOffset < output.size) {
            val position = sourceOffset + (tailBitOffset ushr 3)
            val pair = (packed[position].toInt() and 0xff) or
                ((packed[position + 1].toInt() and 0xff) shl 8)
            val word = ((pair ushr (tailBitOffset and 7)) and 0x3ff) shl 6
            output[outputOffset] = word.toByte()
            output[outputOffset + 1] = (word ushr 8).toByte()
            tailBitOffset += 10
            outputOffset += 2
        }
    }
}
