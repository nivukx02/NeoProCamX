package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.os.Environment
import java.io.IOException

/** Fail-fast storage admission for offline conversion outputs; it never changes output quality. */
internal object ConversionStorageAdmissionPolicy {
    const val MIN_SYSTEM_RESERVE_BYTES: Long = 512L * 1024L * 1024L
    const val MIN_OUTPUT_HEADROOM_BYTES: Long = 16L * 1024L * 1024L

    data class Decision(
        val admitted: Boolean,
        val availableBytes: Long,
        val expectedOutputBytes: Long,
        val outputHeadroomBytes: Long,
        val systemReserveBytes: Long,
        val requiredBytes: Long
    )

    fun evaluate(availableBytes: Long, expectedOutputBytes: Long): Decision {
        val available = availableBytes.coerceAtLeast(0L)
        val expected = expectedOutputBytes.coerceAtLeast(0L)
        val headroom = maxOf(MIN_OUTPUT_HEADROOM_BYTES, expected / 100L)
        val reserve = maxOf(MIN_SYSTEM_RESERVE_BYTES, available / 20L)
        val required = saturatingAdd(saturatingAdd(expected, headroom), reserve)
        return Decision(
            admitted = required != Long.MAX_VALUE && available >= required,
            availableBytes = available,
            expectedOutputBytes = expected,
            outputHeadroomBytes = headroom,
            systemReserveBytes = reserve,
            requiredBytes = required
        )
    }

    fun requireAdmission(availableBytes: Long, expectedOutputBytes: Long, label: String): Decision {
        val decision = evaluate(availableBytes, expectedOutputBytes)
        if (!decision.admitted) {
            throw IOException(
                "Insufficient storage for $label: available=${decision.availableBytes}, " +
                    "required=${decision.requiredBytes}, output=${decision.expectedOutputBytes}, " +
                    "reserve=${decision.systemReserveBytes}"
            )
        }
        return decision
    }

    @SuppressLint("UsableSpace")
    fun externalAvailableBytes(): Long = runCatching {
        Environment.getExternalStorageDirectory().usableSpace
    }.getOrDefault(0L).coerceAtLeast(0L)

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
