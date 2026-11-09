package com.neoproc.neoproccamx.ui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object CameraViewModelRenderIdentityPolicy {
    fun colorUniformSha256(values: FloatArray): String {
        val bytes = ByteBuffer
            .allocate(values.size * java.lang.Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }
            .array()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun quantizationLevelsForBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 0 -> 0
        bitDepth >= Int.SIZE_BITS - 1 -> Int.MAX_VALUE
        else -> (1 shl bitDepth) - 1
    }
}
