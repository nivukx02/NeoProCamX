package com.neoproc.neoproccamx.recording

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService

/**
 * EnhanceRowScheduler — row-band parallel scheduler + reusable snapshot buffer for the OFFLINE SDR/NDR
 * develop enhance passes ([Npv10ExportEnhancementPolicy.enhanceP010]).
 *
 * WHY THIS EXISTS: each full-frame enhance pass (temporal / chroma-NR / edge-aware detail / local
 * contrast / …) reads an immutable snapshot of the frame and writes only its own row's samples, so the
 * per-row work is completely independent. On the flagship full-sensor RAW/record intermediate the offline
 * develop floors to a SINGLE CPU worker (memory budget), so — before this — the ~12 heavy kernel passes
 * ran one row at a time on ONE core while the other 7 cores idled. That single-threaded chain is the bulk
 * of the multi-hour offline SDR/NDR convert time.
 *
 * NO-DOWNGRADE / BYTE-IDENTICAL CONTRACT: splitting a pass's row loop into disjoint bands across threads
 * produces the EXACT same bytes as the sequential loop — every row reads the same immutable `original`
 * snapshot and writes only its own pixels; there is no cross-row dependency. The band partition
 * `[start + k·rows/bands, start + (k+1)·rows/bands)` tiles `[startRow, endRow)` exactly (no gap, no
 * overlap). [SEQUENTIAL] reproduces the original code path verbatim (a plain loop + `payload.copyOf()`),
 * so the whole feature is a pure scheduling win with identical output — locked by a determinism test.
 *
 * MEMORY: a parallel scheduler is built ONCE per clip and used by the single develop worker (so its calls
 * are strictly sequential), which lets it reuse ONE scratch snapshot buffer across every pass and every
 * frame instead of allocating a fresh `payload.copyOf()` (~tens of MB) for each of the ~12 passes per
 * frame — removing hundreds of MB/frame of short-lived garbage and the GC stalls that come with it. The
 * shared [SEQUENTIAL] singleton, by contrast, never reuses a buffer (it may be touched by concurrent
 * clips) and always returns a fresh copy — byte-identical to the original.
 */
internal class EnhanceRowScheduler private constructor(
    private val executor: ExecutorService?,
    private val parallelism: Int
) {
    /** Reused snapshot buffer for the per-clip parallel instance (single-worker ⇒ no concurrent access). */
    private var scratch: ByteArray? = null

    /**
     * Snapshot [payload] for neighbour reads during a pass. Semantically identical to `payload.copyOf()`.
     * The per-clip parallel instance reuses one scratch buffer (safe: the single develop worker calls the
     * passes strictly one after another); the shared [SEQUENTIAL] singleton always allocates a fresh copy.
     */
    fun snapshot(payload: ByteArray): ByteArray {
        if (executor == null) return payload.copyOf()
        val existing = scratch
        val buf = if (existing != null && existing.size == payload.size) {
            existing
        } else {
            ByteArray(payload.size).also { scratch = it }
        }
        System.arraycopy(payload, 0, buf, 0, payload.size)
        return buf
    }

    /**
     * Run [body] for every `row` in `[startRow, endRow)`. Sequential (byte-identical to the original loop)
     * unless a parallel executor is configured AND the range is large enough to be worth splitting, in
     * which case the rows are tiled into disjoint bands run concurrently and joined before returning.
     */
    fun forEachRow(startRow: Int, endRow: Int, body: (row: Int) -> Unit) {
        val rows = endRow - startRow
        val exec = executor
        if (exec == null || parallelism <= 1 || rows < MIN_PARALLEL_ROWS) {
            var row = startRow
            while (row < endRow) {
                body(row)
                row++
            }
            return
        }
        val bands = if (parallelism < rows) parallelism else rows
        val tasks = ArrayList<Callable<Unit>>(bands)
        for (band in 0 until bands) {
            val bandStart = startRow + band * rows / bands
            val bandEnd = startRow + (band + 1) * rows / bands
            tasks.add(
                Callable {
                    var row = bandStart
                    while (row < bandEnd) {
                        body(row)
                        row++
                    }
                }
            )
        }
        val futures = exec.invokeAll(tasks)
        for (future in futures) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    /**
     * Run [body] for every `offset` in the strided sequence `base, base+step, base+2·step, …` while
     * `offset < endExclusive` — i.e. EXACTLY the iterations of
     * `var o = base; while (o < endExclusive) { body(o); o += step }`.
     *
     * This is the flat-loop sibling of [forEachRow] for the point-wise enhance passes (temporal blend,
     * chroma nudge, SDR calibration, blue-noise dither) whose loops step over the payload by a fixed byte
     * stride rather than by image rows. Each iteration of those passes reads and writes ONLY its own
     * offset's samples, so splitting the strided index range `[0, count)` into disjoint contiguous bands
     * yields the EXACT same bytes as the sequential loop — no cross-iteration dependency, no overlap. The
     * sequential fallback below is verbatim the original `while` loop, so [SEQUENTIAL] and any sub-threshold
     * range stay byte-identical. Callers pick [endExclusive] to reproduce their original guard exactly:
     * `while (o + T <= size)` ⇒ `endExclusive = size - T + 1`; `while (o + T < size)` ⇒ `endExclusive = size - T`.
     */
    fun forEachStrided(base: Int, endExclusive: Int, step: Int, body: (offset: Int) -> Unit) {
        val span = endExclusive - base
        val count = if (span > 0) (span + step - 1) / step else 0
        val exec = executor
        if (exec == null || parallelism <= 1 || count < MIN_PARALLEL_ITEMS) {
            var offset = base
            while (offset < endExclusive) {
                body(offset)
                offset += step
            }
            return
        }
        val bands = if (parallelism < count) parallelism else count
        val tasks = ArrayList<Callable<Unit>>(bands)
        for (band in 0 until bands) {
            // Tile the iteration indices [0, count) into disjoint contiguous bands (no gap, no overlap);
            // band k owns iterations [k·count/bands, (k+1)·count/bands) ⇒ offsets base + k·step.
            val kStart = band * count / bands
            val kEnd = (band + 1) * count / bands
            tasks.add(
                Callable {
                    var k = kStart
                    while (k < kEnd) {
                        body(base + k * step)
                        k++
                    }
                }
            )
        }
        val futures = exec.invokeAll(tasks)
        for (future in futures) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    companion object {
        /**
         * The identity scheduler: a plain sequential loop + fresh `copyOf()` snapshot. Reproduces the
         * original single-threaded enhance behaviour byte-for-byte; the default for every caller that does
         * not opt into parallelism (all NPV10 export paths + unit tests).
         */
        val SEQUENTIAL = EnhanceRowScheduler(executor = null, parallelism = 1)

        /**
         * Build a per-clip parallel scheduler over [executor] with up to [parallelism] bands, or fall back
         * to [SEQUENTIAL] when parallelism is not worthwhile. The returned instance is intended to be used
         * by ONE develop worker (its scratch reuse assumes serial calls).
         */
        fun parallel(executor: ExecutorService?, parallelism: Int): EnhanceRowScheduler =
            if (executor != null && parallelism > 1) EnhanceRowScheduler(executor, parallelism) else SEQUENTIAL

        /**
         * Below this row count the fork/join overhead outweighs the win, so we stay sequential. Also keeps
         * every small-frame unit test on the exact sequential path regardless of the configured executor.
         */
        private const val MIN_PARALLEL_ROWS = 64

        /**
         * Below this iteration count the fork/join overhead outweighs the win for [forEachStrided], so it
         * stays sequential. The flat point-wise passes run one iteration per luma sample / chroma pair, so
         * even a modest frame clears this on the full-sensor develop; it only keeps tiny frames sequential.
         */
        private const val MIN_PARALLEL_ITEMS = 2048
    }
}
