package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * RawCompandingCurve — monotone, invertible bit-depth companding for NPRAW serialisation.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS (CP16)
 * ══════════════════════════════════════════════════════════════════════════════
 * When a >12-bit sensor word is forced into a smaller container (the RAW-video
 * master path is contractually packed 12-bit), the legacy code performed a *linear
 * right-shift / rescale* — `code = round(sample * targetMax / sourceMax)`. That is a
 * naive truncation: it discards the low bits uniformly and, in the deepest stops,
 * collapses distinct sensor values to a single code with no way to recover them.
 *
 * Cinema RAW (ARRIRAW / REDCODE / X-OCN) never throws bits away like that — it keeps
 * the sensor word with *reversible* coding. This curve is the reversible substitute:
 * a documented perceptual (µ-law) companding LUT that maps the full [0, sourceWhite]
 * sensor range into [0, targetMax] **monotonically and invertibly**, so a decoder can
 * reconstruct the sensor code within a bounded error instead of guessing.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * NO-DOWNGRADE IDENTITY CONTRACT
 * ══════════════════════════════════════════════════════════════════════════════
 * When the target container can already hold the sensor word — `targetBits >= 16`
 * (16-bit container), or `sourceWhiteLevel <= targetMax` (sensor already fits) —
 * the curve is the **identity** (`encode`/`decode` are pure clamps). On every device
 * already at native depth this is byte-for-byte the pre-companding behaviour, so
 * existing output is unchanged. Companding only activates when bits would otherwise
 * be discarded, and only on the device that actually reports the higher depth.
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * CURVE
 * ══════════════════════════════════════════════════════════════════════════════
 * µ-law:  y = ln(1 + µ·x) / ln(1 + µ),   x = sample/sourceWhite,  y = code/targetMax.
 * µ is solved so the slope at black is exactly one target code per source step
 * (`µ / ln(1+µ) = sourceWhite / targetMax`). That is the steepest shadow allocation
 * that still maps **every** target code to a non-empty source interval — i.e. no
 * skipped codes (the curve is a true bijection onto [0, targetMax]) and the deepest
 * shadows are essentially 1:1 (near-lossless), while the highlights remain hundreds
 * of codes per stop (reconstruction error far under half a stop).
 *
 * The forward LUT is `sample -> code`; the inverse LUT maps each code back to the
 * **midpoint** of its source preimage, which minimises the worst-case round-trip
 * error. Both LUTs are built once per `(sourceWhiteLevel, targetBitDepth)` pair and
 * cached, so per-sample `encode`/`decode` are O(1) array reads.
 */
class RawCompandingCurve private constructor(
    val sourceWhiteLevel: Int,
    val targetBitDepth: Int,
    /** [COMPANDING_NONE] (identity) or [COMPANDING_PERCEPTUAL_LOG]. */
    val type: Int,
    /** size sourceWhiteLevel+1, indexed by clamped sensor sample; null when identity. */
    private val forwardLut: IntArray?,
    /** size targetMax+1, indexed by clamped target code; null when identity. */
    private val inverseLut: IntArray?
) {
    /** Largest value representable in the target container. */
    val targetMax: Int = (1 shl targetBitDepth.coerceIn(1, 16)) - 1

    /** True when no bits are discarded — `encode`/`decode` are clamps (no-downgrade). */
    val isIdentity: Boolean get() = type == COMPANDING_NONE

    /** Map a sensor sample (sensor domain, 0..sourceWhiteLevel) to a target code. */
    fun encode(sample: Int): Int {
        val lut = forwardLut ?: return sample.coerceIn(0, targetMax)
        return lut[sample.coerceIn(0, sourceWhiteLevel)]
    }

    /** Map a target code back to a reconstructed sensor sample (sensor domain). */
    fun decode(code: Int): Int {
        val lut = inverseLut ?: return code.coerceIn(0, targetMax)
        return lut[code.coerceIn(0, targetMax)]
    }

    fun encodeAll(samples: IntArray): IntArray {
        if (samples.isEmpty()) return IntArray(0)
        val lut = forwardLut
        return if (lut == null) {
            IntArray(samples.size) { samples[it].coerceIn(0, targetMax) }
        } else {
            IntArray(samples.size) { lut[samples[it].coerceIn(0, sourceWhiteLevel)] }
        }
    }

    fun decodeAll(codes: IntArray): IntArray {
        if (codes.isEmpty()) return IntArray(0)
        val lut = inverseLut
        return if (lut == null) {
            IntArray(codes.size) { codes[it].coerceIn(0, targetMax) }
        } else {
            IntArray(codes.size) { lut[codes[it].coerceIn(0, targetMax)] }
        }
    }

    companion object {
        /** Identity — the container already holds the sensor word; nothing discarded. */
        const val COMPANDING_NONE = 0

        /** Perceptual µ-law companding applied to fit a wider word into a smaller word. */
        const val COMPANDING_PERCEPTUAL_LOG = 1

        private val cache = ConcurrentHashMap<Long, RawCompandingCurve>()

        /**
         * Resolve (and cache) the companding curve for a sensor white level and a target
         * container bit depth. Stable inputs return the same cached instance.
         */
        fun of(sourceWhiteLevel: Int, targetBitDepth: Int): RawCompandingCurve {
            val white = sourceWhiteLevel.coerceIn(1, 0xFFFF)
            val bits = targetBitDepth.coerceIn(1, 16)
            val key = (white.toLong() shl 8) or bits.toLong()
            return cache[key] ?: build(white, bits).also { cache.putIfAbsent(key, it) }
        }

        private fun build(white: Int, bits: Int): RawCompandingCurve {
            val targetMax = (1 shl bits) - 1
            // No-downgrade identity: the container already holds the sensor word.
            if (bits >= 16 || white <= targetMax) {
                return RawCompandingCurve(white, bits, COMPANDING_NONE, null, null)
            }

            // µ chosen so the slope at black is exactly one target code per source step.
            val ratio = white.toDouble() / targetMax.toDouble()
            val mu = solveMuForRatio(ratio)
            val denom = ln(1.0 + mu)

            val forward = IntArray(white + 1)
            for (s in 0..white) {
                val x = s.toDouble() / white.toDouble()
                val y = ln(1.0 + mu * x) / denom
                forward[s] = (y * targetMax).roundToInt().coerceIn(0, targetMax)
            }
            // Pin the endpoints exactly (defends against rounding at the extremes).
            forward[0] = 0
            forward[white] = targetMax

            // Inverse LUT: each code -> midpoint of the source interval that maps to it.
            // Slope <= 1 guarantees every code has a non-empty preimage (no gaps).
            val firstSource = IntArray(targetMax + 1) { -1 }
            val lastSource = IntArray(targetMax + 1) { -1 }
            for (s in 0..white) {
                val c = forward[s]
                if (firstSource[c] < 0) firstSource[c] = s
                lastSource[c] = s
            }
            val inverse = IntArray(targetMax + 1)
            for (c in 0..targetMax) {
                inverse[c] = if (firstSource[c] >= 0) {
                    (firstSource[c] + lastSource[c]) / 2
                } else {
                    // Defensive: should not occur (no gaps), reconstruct by proportion.
                    ((c.toDouble() / targetMax.toDouble()) * white).roundToInt().coerceIn(0, white)
                }
            }

            return RawCompandingCurve(white, bits, COMPANDING_PERCEPTUAL_LOG, forward, inverse)
        }

        /**
         * Solve `µ / ln(1 + µ) = ratio` for µ > 0. The left side is 1 at µ→0 and grows
         * monotonically, so a simple bracket-and-bisect converges quickly.
         */
        private fun solveMuForRatio(ratio: Double): Double {
            if (ratio <= 1.0) return 1e-3
            var lo = 1e-3
            var hi = 1.0
            while (muRatio(hi) < ratio && hi < 1e9) hi *= 2.0
            repeat(100) {
                val mid = (lo + hi) * 0.5
                if (muRatio(mid) < ratio) lo = mid else hi = mid
            }
            return (lo + hi) * 0.5
        }

        private fun muRatio(mu: Double): Double = mu / ln(1.0 + mu)

        /** Test/diagnostic visibility for the resolved companding type without LUT exposure. */
        fun resolveType(sourceWhiteLevel: Int, targetBitDepth: Int): Int =
            of(sourceWhiteLevel, targetBitDepth).type
    }
}
