package com.neoproc.neoproccamx.recording

/**
 * RawDevelopPipelineOverlapPolicy — enables the 2-stage CPU+GPU OVERLAP for the SINGLE-WORKER offline
 * RAW→NeoLog develop.
 *
 * WHY THIS EXISTS: the multi-worker hybrid ([RawToNeoLogMovConverter.convertStreamParallel]) already
 * overlaps the GPU demosaic with the CPU NeoLog finish across frames. But on the FLAGSHIP RAW resolution
 * (full-sensor / 4K), [ConversionParallelismPolicy.workerCount] floors the worker count to 1 to keep the
 * large resident develop buffers under the memory budget, so the converter falls back to the strictly
 * SEQUENTIAL [RawToNeoLogMovConverter.convertStream] where each frame's GPU demosaic and CPU finish run
 * one-after-another — the GPU idles while the CPU finishes and vice-versa.
 *
 * With overlap ON, the single-worker GPU path runs a two-stage pipeline: frame N+1's GPU demosaic runs
 * concurrently with frame N's CPU NeoLog finish + 10-bit pack, so both lanes stay busy — up to ~2×
 * throughput on the heaviest RAW. The output is BYTE-IDENTICAL to the sequential develop: same clip-uniform
 * GPU demosaic, same [RawNeoLogDevelop.finishFromAcescg] tail, same strict frame order — only the
 * wall-clock changes (no-downgrade; a pure scheduling win).
 *
 * DEFAULT ON. The only cost is holding ONE extra full-res ACEScg plane resident (the frame being produced
 * while another is consumed). If a memory-constrained device shows pressure during an offline convert, flip
 * it OFF via `debug.raw_develop_overlap` to fall back to the byte-identical sequential develop. It engages
 * ONLY when the clip's chosen path is the GPU demosaic (a pure-CPU clip has nothing to overlap on one
 * worker), so a CPU-only device is unaffected.
 */
internal object RawDevelopPipelineOverlapPolicy {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    /**
     * Heap headroom divisor (mirrors [RecordDevelopMovConverter]'s HEAP_FRACTION philosophy). The overlap
     * engages only when its peak resident heap fits within `maxMemory / HEAP_HEADROOM_DIVISOR` — i.e. no
     * more than half the max heap — leaving comfortable room for the rest of the process and GC/fragmentation
     * slack. Conservative on purpose: when in doubt we DON'T overlap and stay on the sequential develop.
     */
    private const val HEAP_HEADROOM_DIVISOR = 2L

    /**
     * Decide whether the single-worker GPU develop should run the CPU+GPU OVERLAP for a [width]×[height]
     * frame, or fall back to the byte-identical sequential develop.
     *
     * The overlap keeps at most TWO full-res linear-ACEScg planes resident (one produced by the GPU stage
     * while one is consumed by the CPU finish) plus the consumer's finish/pack buffers — ONE extra plane vs
     * the sequential path. Those are all Java-heap allocations (the GPU upload/readback direct buffers are
     * off-heap and unchanged by overlap), so we engage the overlap only when that peak clearly fits the heap
     * budget. This makes DEFAULT-ON safe even on a smaller-heap device: it silently uses the proven
     * sequential develop instead of risking an OutOfMemoryError — a pure scheduling choice, byte-identical
     * output either way. [maxHeapBytes] is injected so the estimate is pure + unit-testable.
     */
    fun shouldOverlap(
        width: Int,
        height: Int,
        maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    ): Boolean {
        if (!enabled) return false
        if (width <= 0 || height <= 0 || maxHeapBytes <= 0L) return false
        val pixels = width.toLong() * height.toLong()
        val acescgPlaneBytes = pixels * 3L * java.lang.Float.BYTES        // interleaved RGB FloatArray plane
        val finishBytes = pixels * 3L * java.lang.Short.BYTES + pixels * 4L // 10-bit codes + ~r210 packed
        val overlapPeakBytes = acescgPlaneBytes * 2L + finishBytes         // two planes + finish coexist
        return overlapPeakBytes <= maxHeapBytes / HEAP_HEADROOM_DIVISOR
    }
}
