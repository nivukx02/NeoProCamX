package com.neoproc.neoproccamx.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object CameraViewModelRenderIdentityPolicy {
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    fun colorUniformSha256(values: FloatArray): String {
        val bytes = ByteBuffer
            .allocate(values.size * java.lang.Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }
            .array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        // Formatter-based hexadecimal conversion is disproportionately expensive on Android and this method is
        // called four times for every preview render-config push. Encode the identical lowercase
        // hexadecimal evidence directly so mode switches do not spend a frame formatting 128 bytes.
        return CharArray(digest.size * 2).also { encoded ->
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                encoded[index * 2] = HEX_DIGITS[value ushr 4]
                encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
            }
        }.concatToString()
    }

    fun quantizationLevelsForBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 0 -> 0
        bitDepth >= Int.SIZE_BITS - 1 -> Int.MAX_VALUE
        else -> (1 shl bitDepth) - 1
    }
}
