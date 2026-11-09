package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopPipelineOverlapFlag — reversible A/B lever (default OFF) that lets the SDR/NDR develop
 * converter keep MORE than one frame in flight so its decode (reader) ∥ develop (worker) ∥ mux (writer)
 * coroutines actually overlap instead of running serially.
 *
 * The develop pipeline is already three concurrent coroutines, but the conservative memory budget
 * (maxMemory/3) caps the resident-frame count to 1 for a full-sensor uncompressed frame, which forces the
 * stages to serialise (the worker waits for the reader, the writer waits for the worker). Raising the cap
 * to as many frames as fit under a safe fraction of the REAL heap restores the overlap and shortens the
 * convert — WITHOUT a second develop lane and WITHOUT changing a single output byte (the writer reorders
 * by index; output is proven identical regardless of the in-flight window).
 *
 * NO-DOWNGRADE: throughput lever only; [RecordDevelopPipelineOverlapPolicy.maxResidentFrames] never lets
 * peak resident memory exceed the guard fraction of the heap, and never drops below the conservative
 * single-frame floor.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_pipeline_overlap true
 */
internal object RecordDevelopPipelineOverlapFlag {

    @Volatile
    var enabled: Boolean = false
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
    // Cap read-ahead so a straggling head-of-line frame can't grow the writer's reorder buffer unbounded.
    const val MAX_DEPTH = 4

    /**
     * Resident-frame cap. Default (flag OFF) returns [conservativeMaxFrames] (today's budget-derived value).
     * When ON, returns as many frames as fit under [HEAP_NUMERATOR]/[HEAP_DENOMINATOR] of [maxMemoryBytes],
     * clamped to [[conservativeMaxFrames], [MAX_DEPTH]] — never below the safe floor, never above the cap.
     */
    fun maxResidentFrames(
        residentBytesPerFrame: Long,
        conservativeMaxFrames: Int,
        maxMemoryBytes: Long,
        flagEnabled: Boolean = RecordDevelopPipelineOverlapFlag.enabled
    ): Int {
        if (!flagEnabled || residentBytesPerFrame <= 0L || maxMemoryBytes <= 0L) return conservativeMaxFrames
        val ceiling = (maxMemoryBytes * HEAP_NUMERATOR) / HEAP_DENOMINATOR
        val fit = (ceiling / residentBytesPerFrame).toInt()
        if (fit <= conservativeMaxFrames) return conservativeMaxFrames
        return fit.coerceAtMost(MAX_DEPTH)
    }
}
