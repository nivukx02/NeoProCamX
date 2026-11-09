package com.neoproc.neoproccamx.rendering

/**
 * Decodes a canonical decimal C/C++ `uint64_t` while retaining its exact 64-bit pattern in a
 * Kotlin [Long]. Values with bit 63 set are intentionally represented as negative signed longs;
 * bitwise usage-flag checks therefore see every native bit without changing the JNI/Kotlin ABI.
 */
internal fun parseNativeUint64DecimalBitsOrZero(value: String?): Long {
    if (value.isNullOrEmpty() || value.any { it !in '0'..'9' }) return 0L
    return value.toULongOrNull()?.toLong() ?: 0L
}
