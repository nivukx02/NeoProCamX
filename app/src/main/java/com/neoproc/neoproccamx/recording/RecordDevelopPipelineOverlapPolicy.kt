package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopPipelineOverlapFlag — reversible A/B lever (default ON) that lets the SDR/NDR develop
 * converter keep MORE than one frame in flight so its decode (reader) ∥ develop (worker) ∥ mux (writer)
 * coroutines actually overlap instead of running serially.
 *
 * The develop pipeline is already three concurrent coroutines. This policy lets those stages overlap only
 * when every resident frame fits inside the caller's explicit conversion-memory budget as well as the
 * process heap guard. It never obtains throughput by silently spending memory outside that contract.
 *
 * NO-DOWNGRADE: throughput lever only; frame order, precision, colour math, and encoded bytes are unchanged.
 * A budget that cannot hold one complete frame fails closed before the reader allocates that frame.
 *
 * The memory guard remains authoritative: enabling the flag never raises the resident window beyond
 * [RecordDevelopPipelineOverlapPolicy.maxResidentFrames]. Disable it only for device A/B diagnosis:
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_pipeline_overlap false
 */
internal object RecordDevelopPipelineOverlapFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}

/**
 * Pure decision (JVM-unit-testable) for how many develop frames may be resident at once — see
 * [RecordDevelopPipelineOverlapFlag].
 */
internal object RecordDevelopPipelineOverlapPolicy {

    // Peak resident frames may use up to this fraction of the real heap (leaving the rest as GC/spike +
    // native-buffer margin). Chosen so two full-sensor uncompressed frames fit while keeping headroom.
    const val HEAP_NUMERATOR = 65
    const val HEAP_DENOMINATOR = 100
    // Cap only the OPTIONAL expansion. A caller's already-safe window may legitimately be wider.
    const val MAX_DEPTH = 4

    /**
     * Effective heap budget for one record-develop transaction. The caller's configured ceiling remains
     * authoritative, while the process guard permits the already-documented 65% heap envelope. The old
     * converter-local `maxMemory / 3` clamp contradicted this policy and made a single UHD/4K quality frame
     * impossible on a normal 256 MiB ART heap even though the serial CPU path fits safely.
     */
    fun effectiveMemoryBudget(
        requestedBudgetBytes: Long,
        maxMemoryBytes: Long
    ): Long {
        require(requestedBudgetBytes > 0L) { "requestedBudgetBytes must be positive" }
        require(maxMemoryBytes > 0L) { "maxMemoryBytes must be positive" }
        return minOf(requestedBudgetBytes, safeHeapFraction(maxMemoryBytes))
    }

    /** Non-throwing admission probe used to shed an optional GPU parity lane before allocating a frame. */
    fun canFitOneFrame(
        residentBytesPerFrame: Long,
        fixedReserveBytes: Long,
        memoryBudgetBytes: Long
    ): Boolean {
        if (residentBytesPerFrame <= 0L || fixedReserveBytes < 0L || memoryBudgetBytes <= 0L) return false
        if (fixedReserveBytes > memoryBudgetBytes) return false
        return residentBytesPerFrame <= memoryBudgetBytes - fixedReserveBytes
    }

    /**
     * Exact number of complete resident frames that fit after reserving non-window pipeline storage.
     * Returning a fabricated one-frame floor would violate [memoryBudgetBytes], so undersized budgets are
     * rejected before decode starts with an actionable precondition failure.
     */
    fun frameCapacity(
        residentBytesPerFrame: Long,
        fixedReserveBytes: Long,
        memoryBudgetBytes: Long
    ): Int {
        require(residentBytesPerFrame > 0L) { "residentBytesPerFrame must be positive" }
        require(fixedReserveBytes >= 0L) { "fixedReserveBytes must not be negative" }
        require(memoryBudgetBytes > 0L) { "memoryBudgetBytes must be positive" }
        require(memoryBudgetBytes >= fixedReserveBytes) {
            "Record-develop memory budget $memoryBudgetBytes is smaller than fixed reserve $fixedReserveBytes"
        }
        val frameBudgetBytes = memoryBudgetBytes - fixedReserveBytes
        require(frameBudgetBytes >= residentBytesPerFrame) {
            "Record-develop memory budget $memoryBudgetBytes cannot hold one resident frame " +
                "($residentBytesPerFrame bytes) after fixed reserve $fixedReserveBytes"
        }
        return minOf(
            frameBudgetBytes / residentBytesPerFrame,
            Int.MAX_VALUE.toLong()
        ).toInt()
    }

    /**
     * Resident-frame cap. [memoryBudgetBytes] is authoritative and the heap fraction is an additional upper
     * bound. [conservativeMaxFrames] must already fit that hard ceiling. Enabling overlap may expand up to
     * [MAX_DEPTH], but can never shrink a wider caller-proven window.
     */
    fun maxResidentFrames(
        residentBytesPerFrame: Long,
        conservativeMaxFrames: Int,
        maxMemoryBytes: Long,
        memoryBudgetBytes: Long = maxMemoryBytes,
        flagEnabled: Boolean = RecordDevelopPipelineOverlapFlag.enabled
    ): Int {
        require(conservativeMaxFrames > 0) { "conservativeMaxFrames must be positive" }
        require(maxMemoryBytes > 0L) { "maxMemoryBytes must be positive" }
        // The converter's configured memory budget is a hard limit. The heap fraction is a second,
        // process-wide guard, never permission to exceed that explicit budget.
        val heapCeiling = safeHeapFraction(maxMemoryBytes)
        val ceiling = minOf(memoryBudgetBytes, heapCeiling)
        val fit = frameCapacity(
            residentBytesPerFrame = residentBytesPerFrame,
            fixedReserveBytes = 0L,
            memoryBudgetBytes = ceiling
        )
        require(conservativeMaxFrames <= fit) {
            "Conservative resident window $conservativeMaxFrames exceeds hard budget capacity $fit"
        }
        if (!flagEnabled || fit == conservativeMaxFrames) return conservativeMaxFrames
        // MAX_DEPTH bounds only the extra overlap. A pre-existing conservative window above four
        // must never be reduced merely because this optional optimization is enabled.
        val runtimeLimit = maxOf(
            conservativeMaxFrames,
            DmcConversionTuningRegistry.current().inFlightLimit
        )
        return minOf(fit, maxOf(conservativeMaxFrames, MAX_DEPTH), runtimeLimit)
    }

    /** Overflow-safe floor(value * HEAP_NUMERATOR / HEAP_DENOMINATOR). */
    private fun safeHeapFraction(value: Long): Long {
        val whole = value / HEAP_DENOMINATOR
        val remainder = value % HEAP_DENOMINATOR
        return Math.addExact(
            Math.multiplyExact(whole, HEAP_NUMERATOR.toLong()),
            (remainder * HEAP_NUMERATOR) / HEAP_DENOMINATOR
        )
    }
}
