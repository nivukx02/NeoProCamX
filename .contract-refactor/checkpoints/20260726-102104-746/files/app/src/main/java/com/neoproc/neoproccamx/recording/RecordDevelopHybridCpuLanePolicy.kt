package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopHybridCpuLanePolicy — pure decisions for the concurrent CPU+GPU develop hybrid
 * ([RecordDevelopHybridCpuLaneFlag]). Kept free of Android deps so the routing + memory guard are
 * JVM-unit-testable and deterministic (identical bytes across runs).
 */
internal object RecordDevelopHybridCpuLanePolicy {

    const val BASIS = 10_000
    const val DEFAULT_CPU_SHARE_BPS = 0       // 0% CPU / 100% GPU colour-develop frames by default
    const val MIN_CPU_SHARE_BPS = 0
    // Cap below 100% so the GPU lane always keeps a meaningful share (the point is to run BOTH).
    const val MAX_CPU_SHARE_BPS = 8_000

    // Two develop lanes need ~1.5 resident frames (one full CPU develop + one CHEAP GPU frame: the GPU
    // develop runs in native buffers, so its Java-heap footprint is just the source + packed payload, not
    // the full RGB-code intermediates). Engage only if that fits under this fraction of the heap, leaving
    // the rest as a GC/spike margin.
    const val HYBRID_HEAP_NUMERATOR = 72
    const val HYBRID_HEAP_DENOMINATOR = 100

    @Volatile
    var cpuShareBps: Int = DEFAULT_CPU_SHARE_BPS
        private set

    fun setCpuShareBps(value: Int) {
        cpuShareBps = value.coerceIn(MIN_CPU_SHARE_BPS, MAX_CPU_SHARE_BPS)
    }

    /**
     * Deterministic Bresenham interleave: exactly [shareBps]/[BASIS] of frames route to the CPU lane,
     * spread as evenly as possible across indices (so both lanes stay continuously fed rather than the CPU
     * getting a contiguous block). Frame 0 stays on the GPU lane (warms the accelerator's first-frame gate).
     */
    fun routeToCpuLane(index: Long, shareBps: Int = cpuShareBps): Boolean {
        if (shareBps <= MIN_CPU_SHARE_BPS) return false
        if (shareBps >= BASIS) return index > 0L
        val cur = ((index + 1L) * shareBps) / BASIS
        val prev = (index * shareBps) / BASIS
        return cur > prev
    }

    /**
     * Develop concurrency: 2 (one GPU + one CPU lane) only when the flag is ON, a GPU accelerator exists,
     * AND the heap can hold the ~1.5-frame hybrid working set under [HYBRID_HEAP_NUMERATOR]/[DENOMINATOR]
     * of [maxMemoryBytes]. Otherwise 1 (byte-identical single-lane, no OOM risk).
     * [RecordDevelopHybridCpuLaneForceFlag] forces 2 for on-device headroom measurement.
     */
    fun developConcurrency(
        gpuAvailable: Boolean,
        residentBytesPerFrame: Long,
        maxMemoryBytes: Long,
        flagEnabled: Boolean = RecordDevelopHybridCpuLaneFlag.enabled,
        forceEnabled: Boolean = RecordDevelopHybridCpuLaneForceFlag.enabled
    ): Int {
        if (!flagEnabled || !gpuAvailable || residentBytesPerFrame <= 0L || cpuShareBps <= MIN_CPU_SHARE_BPS) {
            return 1
        }
        if (forceEnabled) return 2
        if (maxMemoryBytes <= 0L) return 1
        val hybridResident = residentBytesPerFrame + residentBytesPerFrame / 2
        val ceiling = (maxMemoryBytes * HYBRID_HEAP_NUMERATOR) / HYBRID_HEAP_DENOMINATOR
        return if (hybridResident <= ceiling) 2 else 1
    }
}
