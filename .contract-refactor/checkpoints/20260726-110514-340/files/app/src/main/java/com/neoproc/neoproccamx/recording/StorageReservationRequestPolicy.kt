package com.neoproc.neoproccamx.recording

/** Avoids unnecessary StorageManager cache-reclamation calls when usable space already covers the plan. */
internal object StorageReservationRequestPolicy {
    fun shouldRequestAllocation(
        usableBytes: Long,
        plan: RecordingPreallocationPolicy.Plan
    ): Boolean {
        if (!plan.enabled) return false
        val required = saturatingAdd(plan.reservationBytes, plan.safetyReserveBytes)
        return usableBytes.coerceAtLeast(0L) < required
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
}
