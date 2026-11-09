package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.ceil

/**
 * RecordDevelopParityDiagnostics — pure GPU-vs-CPU develop-code comparison for the record-develop
 * first-frame parity gate and (verbosely, behind [RecordDevelopParityDiagnosticsFlag]) the on-device
 * A/B harness.
 *
 * It computes the FULL residual distribution of the interleaved 10-bit RGB code stream (3 codes/pixel).
 * A single global mean or a lone max can hide a STRUCTURED regional error — e.g. a 100×100 block off by
 * 40 codes on a 4K frame contributes meanAbs≈0.004 and maxAbs=40, so a mean≤4 / max≤64 gate passes it
 * silently. Reporting p99.9 (and logging p99.99 + a bucket histogram) exposes such errors so the gate
 * can reject them and a human can localise them, WITHOUT loosening any tolerance.
 *
 * Pure + deterministic (no Android deps) ⇒ JVM-unit-testable. Comparison only: never alters produced
 * pixels. NO-DOWNGRADE.
 */
internal object RecordDevelopParityDiagnostics {

    private const val MAX_DELTA = 1023

    /** One diverging code, with the pixel it maps to (for on-device reproduction). */
    data class WorstCode(
        val index: Int,
        val x: Int,
        val y: Int,
        val channel: Int,
        val gpu: Int,
        val cpu: Int,
        val delta: Int
    )

    /** Full residual distribution of one GPU-vs-CPU code stream. */
    data class CodeDiffStats(
        val category: String,
        val count: Int,
        val meanAbs: Double,
        val maxAbs: Int,
        val p50: Int,
        val p99: Int,
        val p999: Int,
        val p9999: Int,
        val over1: Int,
        val over4: Int,
        val over16: Int,
        val over64: Int,
        val worst: List<WorstCode>
    )

    /**
     * Compare two interleaved RGB 10-bit code streams (3 codes/pixel). Returns null on size mismatch or
     * empty input (the caller treats that as a parity FAIL → CPU fallback). [width] annotates the worst
     * pixels with (x,y,channel) for reproduction; pass 0 if unknown. O(n) via a 1024-bin histogram.
     */
    fun compareCodes(
        gpu: ShortArray,
        cpu: ShortArray,
        category: String,
        width: Int,
        worstN: Int = 8
    ): CodeDiffStats? {
        if (gpu.size != cpu.size || gpu.isEmpty()) return null
        val hist = IntArray(MAX_DELTA + 1)
        var sumAbs = 0.0
        var maxAbs = 0
        // Top-worstN largest deltas, kept ASCENDING so slot 0 is the smallest of the retained set.
        val n = worstN.coerceAtLeast(1)
        val wDelta = IntArray(n)
        val wIndex = IntArray(n) { -1 }
        for (i in gpu.indices) {
            val d = abs((gpu[i].toInt() and 0xFFFF) - (cpu[i].toInt() and 0xFFFF))
            hist[d]++
            sumAbs += d
            if (d > maxAbs) maxAbs = d
            if (d > wDelta[0]) {
                wDelta[0] = d
                wIndex[0] = i
                var k = 0
                while (k + 1 < n && wDelta[k] > wDelta[k + 1]) {
                    val td = wDelta[k]; wDelta[k] = wDelta[k + 1]; wDelta[k + 1] = td
                    val ti = wIndex[k]; wIndex[k] = wIndex[k + 1]; wIndex[k + 1] = ti
                    k++
                }
            }
        }
        val count = gpu.size
        val total = count.toLong()

        fun percentile(q: Double): Int {
            val threshold = ceil(q * total).toLong().coerceIn(1L, total)
            var cum = 0L
            for (v in 0..MAX_DELTA) {
                cum += hist[v]
                if (cum >= threshold) return v
            }
            return MAX_DELTA
        }

        fun countOver(k: Int): Int {
            var c = 0L
            for (v in (k + 1)..MAX_DELTA) c += hist[v]
            return c.toInt()
        }

        // Emit worst list DESCENDING by delta, dropping unfilled/zero slots.
        val worst = ArrayList<WorstCode>(n)
        for (slot in n - 1 downTo 0) {
            val idx = wIndex[slot]
            val d = wDelta[slot]
            if (idx < 0 || d <= 0) continue
            val pixel = idx / 3
            val channel = idx % 3
            val x = if (width > 0) pixel % width else -1
            val y = if (width > 0) pixel / width else -1
            worst.add(
                WorstCode(
                    index = idx, x = x, y = y, channel = channel,
                    gpu = gpu[idx].toInt() and 0xFFFF,
                    cpu = cpu[idx].toInt() and 0xFFFF,
                    delta = d
                )
            )
        }

        return CodeDiffStats(
            category = category,
            count = count,
            meanAbs = sumAbs / count,
            maxAbs = maxAbs,
            p50 = percentile(0.50),
            p99 = percentile(0.99),
            p999 = percentile(0.999),
            p9999 = percentile(0.9999),
            over1 = countOver(1),
            over4 = countOver(4),
            over16 = countOver(16),
            over64 = countOver(64),
            worst = worst
        )
    }

    /** Byte-level GPU-vs-CPU packed-payload divergence (r210 / v210 / P010-4:2:0). */
    data class PackedDiffStats(
        val category: String,
        val bytes: Int,
        val differingBytes: Int,
        val maxByteDelta: Int,
        val meanByteDelta: Double
    )

    /**
     * Compare two packed payloads byte-for-byte. This catches PACKER-level divergence (v210 truncation,
     * P010-4:2:0 chroma box-average, BT.709-vs-BT.2020 matrix, even-column subsample) that a pre-pack
     * per-code compare cannot see — the GPU and CPU each pack their OWN codes, so a matching code stream
     * can still diverge in the packed bytes. r210 (the default master) is a lossless bit-pack of the RGB
     * codes, so its byte divergence tracks the code residual; v210/P010 are where trunc/round/subsample
     * boundary drift shows up. Returns null on size mismatch/empty. Diagnostic only — never gates.
     */
    fun comparePackedBytes(gpu: ByteArray, cpu: ByteArray, category: String): PackedDiffStats? {
        if (gpu.size != cpu.size || gpu.isEmpty()) return null
        var differing = 0
        var maxDelta = 0
        var sum = 0.0
        for (i in gpu.indices) {
            val d = abs((gpu[i].toInt() and 0xFF) - (cpu[i].toInt() and 0xFF))
            if (d != 0) {
                differing++
                sum += d
                if (d > maxDelta) maxDelta = d
            }
        }
        return PackedDiffStats(
            category = category,
            bytes = gpu.size,
            differingBytes = differing,
            maxByteDelta = maxDelta,
            meanByteDelta = if (gpu.isEmpty()) 0.0 else sum / gpu.size
        )
    }

    fun formatPacked(s: PackedDiffStats): String {
        val pct = if (s.bytes == 0) 0.0 else 100.0 * s.differingBytes / s.bytes
        return "parity[${s.category}] packedBytes=${s.bytes} differing=${s.differingBytes} " +
            "(${"%.4f".format(pct)}%) maxByteΔ=${s.maxByteDelta} meanByteΔ=${"%.6f".format(s.meanByteDelta)}"
    }

    /** Compact single-line summary for logcat / the on-device A/B harness. */
    fun format(s: CodeDiffStats): String {
        val worst = s.worst.joinToString(prefix = "[", postfix = "]") { w ->
            "(${w.x},${w.y})ch${w.channel} gpu=${w.gpu} cpu=${w.cpu} Δ${w.delta}"
        }
        return "parity[${s.category}] n=${s.count} mean=${"%.4f".format(s.meanAbs)} max=${s.maxAbs} " +
            "p50=${s.p50} p99=${s.p99} p99.9=${s.p999} p99.99=${s.p9999} " +
            ">1=${s.over1} >4=${s.over4} >16=${s.over16} >64=${s.over64} worst=$worst"
    }
}
