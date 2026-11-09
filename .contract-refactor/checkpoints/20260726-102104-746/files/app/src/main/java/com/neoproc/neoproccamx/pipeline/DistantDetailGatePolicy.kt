package com.neoproc.neoproccamx.pipeline

import java.util.Locale

/**
 * CP-QV5 / F05 — the honest, no-downgrade decision for the distant-detail elite gates that back
 * `detail_preservation.comp` (SC_DETAIL_NOISE_AWARE / SC_DETAIL_HALO_GUARD) and
 * `guided_upsample.comp` (SC_GUIDED_EDGE_TIGHTEN).
 *
 * The three spec-constants compile to `false` today, so the shaders reproduce the current output
 * bit-for-bit. This policy is the JVM-side owner of *when* those gates should flip on and *which
 * tuning values* feed them — while GUARANTEEING that the DEFAULT operating point (low ISO, not the
 * Extreme-Detail profile, healthy SNR, no distant-detail request) leaves every gate OFF and every
 * tuning float equal to the shader spec-constant DEFAULTS. Nothing here mutates global state; the
 * function is deterministic and Android-free.
 *
 * Tuning-float provenance (must equal the shader constants, see the .comp sources):
 *  - [DistantDetailGateDecision.noiseSigma] == detail_preservation.comp `DETAIL_NOISE_SIGMA` (0.04)
 *  - [DistantDetailGateDecision.edgeHi]     == detail_preservation.comp `DETAIL_EDGE_HI`     (0.10)
 *  - [DistantDetailGateDecision.tightenK]   == guided_upsample.comp     `GUIDED_TIGHTEN_K`   (8.0)
 */
object DistantDetailGatePolicy {

    /** Mirror of the shader spec-constant DEFAULTS (compile-time values in the .comp sources). */
    const val DEFAULT_NOISE_SIGMA: Float = 0.04f
    const val DEFAULT_EDGE_HI: Float = 0.10f
    const val DEFAULT_TIGHTEN_K: Float = 8.0f

    /**
     * ISO at/above which the sensor noise floor is high enough that flat/noisy regions must not be
     * flagged as protectable "texture" (the noise-aware rolloff). Below this, keep the gate OFF so
     * clean footage is untouched.
     */
    const val NOISE_AWARE_ISO_THRESHOLD: Int = 1600

    /**
     * Luma SNR (dB) at/below which grain dominates the flat-region signal enough that the
     * noise-aware rolloff must engage even when ISO alone would not have tripped it.
     */
    const val NOISE_AWARE_SNR_DB_THRESHOLD: Float = 30f

    /**
     * The decision. All three booleans are the shader spec-constant states; the three floats are the
     * tuning references the shader would be dispatched with. For the DEFAULT input the booleans are
     * all `false` and the floats equal the shader defaults (the no-downgrade lock).
     */
    data class DistantDetailGateDecision(
        val detailNoiseAware: Boolean,
        val detailHaloGuard: Boolean,
        val guidedEdgeTighten: Boolean,
        val noiseSigma: Float,
        val edgeHi: Float,
        val tightenK: Float
    ) {
        /** True iff every gate is OFF and every tuning float equals the shader default. */
        val isBaseline: Boolean
            get() = !detailNoiseAware && !detailHaloGuard && !guidedEdgeTighten &&
                noiseSigma == DEFAULT_NOISE_SIGMA &&
                edgeHi == DEFAULT_EDGE_HI &&
                tightenK == DEFAULT_TIGHTEN_K

        fun toMetadata(prefix: String = "distantDetail"): Map<String, String> = linkedMapOf(
            key(prefix, "noiseAware") to detailNoiseAware.toString(),
            key(prefix, "haloGuard") to detailHaloGuard.toString(),
            key(prefix, "edgeTighten") to guidedEdgeTighten.toString(),
            key(prefix, "noiseSigma") to format(noiseSigma),
            key(prefix, "edgeHi") to format(edgeHi),
            key(prefix, "tightenK") to format(tightenK),
            key(prefix, "baseline") to isBaseline.toString()
        )

        companion object {
            /** The no-downgrade baseline: gates OFF, tuning == shader spec-constant defaults. */
            val BASELINE = DistantDetailGateDecision(
                detailNoiseAware = false,
                detailHaloGuard = false,
                guidedEdgeTighten = false,
                noiseSigma = DEFAULT_NOISE_SIGMA,
                edgeHi = DEFAULT_EDGE_HI,
                tightenK = DEFAULT_TIGHTEN_K
            )
        }
    }

    /**
     * Resolve which distant-detail gates to enable.
     *
     * @param iso estimated capture ISO (>= base). Non-positive is treated as base (no noise pressure).
     * @param extremeDetail whether the [VideoDetailProfile.EXTREME_DETAIL] look is selected.
     * @param snrDb measured flat-region luma SNR in dB (e.g. from ImageQualityMetrics). Non-finite is
     *   treated as "healthy" (no noise pressure) so a missing measurement never fabricates a gate.
     * @param distantDetailRequested whether the user/pipeline asked for the distant-detail
     *   reconstruction path (guided upsample of a distant, edge-rich subject).
     *
     * Rules (default input => [DistantDetailGateDecision.BASELINE]):
     *  - noise-aware ON when ISO is high OR SNR is low (grain would be over-flagged as texture).
     *  - halo-guard ON when Extreme-Detail is active AND the distant-detail path is requested
     *    (aggressive re-injection on a strong distant edge risks ringing).
     *  - edge-tighten ON under the same Extreme-Detail + distant-detail request (the guided upsample
     *    must snap harder to the real guide edge).
     * The tuning floats are always the shader defaults (the gate math itself is what changes, not the
     * reference values), so an enabled gate never silently re-tunes the baseline.
     */
    fun resolve(
        iso: Int,
        extremeDetail: Boolean,
        snrDb: Float,
        distantDetailRequested: Boolean
    ): DistantDetailGateDecision {
        val highIso = iso >= NOISE_AWARE_ISO_THRESHOLD
        val lowSnr = snrDb.isFinite() && snrDb <= NOISE_AWARE_SNR_DB_THRESHOLD
        val detailNoiseAware = highIso || lowSnr

        val distantElite = extremeDetail && distantDetailRequested
        val detailHaloGuard = distantElite
        val guidedEdgeTighten = distantElite

        return DistantDetailGateDecision(
            detailNoiseAware = detailNoiseAware,
            detailHaloGuard = detailHaloGuard,
            guidedEdgeTighten = guidedEdgeTighten,
            noiseSigma = DEFAULT_NOISE_SIGMA,
            edgeHi = DEFAULT_EDGE_HI,
            tightenK = DEFAULT_TIGHTEN_K
        )
    }

    private fun key(prefix: String, name: String): String =
        if (prefix.isBlank()) name else prefix + name.replaceFirstChar { it.uppercase(Locale.US) }

    private fun format(value: Float): String = String.format(Locale.US, "%.6f", value)
}
