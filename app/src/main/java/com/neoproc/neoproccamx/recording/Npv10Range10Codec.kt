package com.neoproc.neoproccamx.recording

/** Exact NLC2-v2 range blocks. Independent Kotlin implementation of the native wire contract. */
internal object Npv10Range10Codec {
    private const val BLOCK_SAMPLES = 64
    private const val MAX_SAMPLES = 262_144

    /** Null means this chunk is not smaller; the original LZ4/raw representation remains available. */
    fun encode(packed: ByteArray, sourceOffset: Int, samples: Int): ByteArray? {
        require(samples in 1..MAX_SAMPLES)
        val packedBytes = (samples * 10 + 7) / 8
        require(sourceOffset >= 0 && sourceOffset <= packed.size && packedBytes <= packed.size - sourceOffset)
        val output = ByteArray(packedBytes)
        val codes = Array(2) { IntArray(BLOCK_SAMPLES / 2) }
        var cursor = 0
        var first = 0
        while (first < samples) {
            val count = minOf(BLOCK_SAMPLES, samples - first)
            val minima = intArrayOf(1023, 1023)
            val maxima = intArrayOf(0, 0)
            repeat(count) { i ->
                val bit = (first + i) * 10
                val byte = sourceOffset + bit / 8
                val word = (packed[byte].toInt() and 255) or ((packed[byte + 1].toInt() and 255) shl 8)
                val code = (word ushr (bit and 7)) and 1023
                val lane = i and 1
                codes[lane][i / 2] = code
                minima[lane] = minOf(minima[lane], code)
                maxima[lane] = maxOf(maxima[lane], code)
            }
            repeat(2) { lane ->
                val laneCount = (count + 1 - lane) / 2
                val minimum = if (laneCount == 0) 0 else minima[lane]
                val span = if (laneCount == 0) 0 else maxima[lane] - minimum
                val bits = if (span == 0) 0 else Int.SIZE_BITS - Integer.numberOfLeadingZeros(span)
                val bytes = (laneCount * bits + 7) / 8
                if (output.size - cursor < 2 + bytes) return null
                val header = minimum or (bits shl 10)
                output[cursor++] = header.toByte()
                output[cursor++] = (header ushr 8).toByte()
                var accumulator = 0
                var pending = 0
                repeat(laneCount) { i ->
                    accumulator = accumulator or ((codes[lane][i] - minimum) shl pending)
                    pending += bits
                    while (pending >= 8) {
                        output[cursor++] = accumulator.toByte()
                        accumulator = accumulator ushr 8
                        pending -= 8
                    }
                }
                if (pending > 0) output[cursor++] = accumulator.toByte()
            }
            first += count
        }
        return if (cursor < packedBytes) output.copyOf(cursor) else null
    }

    fun decode(
        encoded: ByteArray, inputOffset: Int, inputBytes: Int, samples: Int,
        packed: ByteArray, outputOffset: Int
    ) {
        require(samples in 1..MAX_SAMPLES)
        require(inputOffset >= 0 && inputOffset <= encoded.size && inputBytes >= 0 &&
            inputBytes <= encoded.size - inputOffset) { "NLC2 range input extent is invalid" }
        val packedBytes = (samples * 10 + 7) / 8
        require(outputOffset >= 0 && outputOffset <= packed.size && packedBytes <= packed.size - outputOffset)
        val end = inputOffset + inputBytes
        var cursor = inputOffset
        var target = outputOffset
        val codes = Array(2) { IntArray(BLOCK_SAMPLES / 2) }
        var first = 0
        while (first < samples) {
            val count = minOf(BLOCK_SAMPLES, samples - first)
            repeat(2) { lane ->
                require(end - cursor >= 2) { "NLC2 range header is truncated" }
                val header = (encoded[cursor].toInt() and 255) or
                    ((encoded[cursor + 1].toInt() and 255) shl 8)
                cursor += 2
                val minimum = header and 1023
                val bits = (header ushr 10) and 15
                val laneCount = (count + 1 - lane) / 2
                require(header and 0xc000 == 0 && bits <= 10 && (laneCount != 0 || header == 0)) {
                    "NLC2 range header is invalid"
                }
                val bytes = (laneCount * bits + 7) / 8
                require(bytes <= end - cursor) { "NLC2 range residual is truncated" }
                val laneEnd = cursor + bytes
                val mask = (1 shl bits) - 1
                var accumulator = 0
                var available = 0
                repeat(laneCount) { i ->
                    while (available < bits) {
                        require(cursor < laneEnd)
                        accumulator = accumulator or ((encoded[cursor++].toInt() and 255) shl available)
                        available += 8
                    }
                    val code = minimum + (accumulator and mask)
                    require(code <= 1023) { "NLC2 range residual exceeds ten-bit precision" }
                    codes[lane][i] = code
                    accumulator = accumulator ushr bits
                    available -= bits
                }
                require(cursor == laneEnd && accumulator == 0) { "NLC2 range tail padding is invalid" }
            }
            var accumulator = 0
            var pending = 0
            repeat(count) { i ->
                accumulator = accumulator or (codes[i and 1][i / 2] shl pending)
                pending += 10
                while (pending >= 8) {
                    packed[target++] = accumulator.toByte()
                    accumulator = accumulator ushr 8
                    pending -= 8
                }
            }
            if (pending > 0) packed[target++] = accumulator.toByte()
            first += count
        }
        require(cursor == end && target == outputOffset + packedBytes) { "NLC2 range extent mismatch" }
    }
}
