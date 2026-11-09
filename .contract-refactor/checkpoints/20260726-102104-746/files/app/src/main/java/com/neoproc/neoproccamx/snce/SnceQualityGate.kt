package com.neoproc.neoproccamx.snce

/**
 * SNCE v2.0 HVC — Stage 13 (PQVG) + Part IX score.
 *
 * Runtime perceptual quality verification: turns per-frame error metrics into the
 * directive's PCQS (Stage 11.4) and the composite SNCE v2.0 Score (Part IX), applies
 * the pass/target/elite gates (Part 1.2 / Stage 13.1-13.4), and emits an adaptive
 * quality-loop directive (Stage 13.5) naming which earlier stage to strengthen.
 *
 * Reuses [SnceColorPrimitives.deltaE2000] upstream; this object only scores already
 * measured deltas, so it is allocation-free and safe to call every frame.
 */
object SnceQualityGate {

    enum class Tier { FAIL, MINIMUM, TARGET, ELITE }

    enum class AdaptiveAction {
        NONE,
        STRENGTHEN_CAM16_MEMORY, // hue error dominates -> Stage 06/07
        RECOMPUTE_SIEE,          // chroma / overall dE dominates -> Stage 01
        REDUCE_AWB_ALPHA,        // temporal flicker dominates -> Stage 09
        INCREASE_IMU_WEIGHT      // angular instability dominates -> Stage 10
    }

    /** Per-frame measured error inputs. */
    class FrameMetrics(
        val deltaEMean: Float,
        val deltaEMax: Float,
        val hueRmse: Float,
        val chromaRmse: Float,
        val skinDeltaE: Float,
        val temporalDeltaE: Float,
        val angularDeltaE: Float,
        val gamutCoverageP3: Float,
        val gamutCoverage2020: Float
    )

    class AdaptiveDirective(val action: AdaptiveAction, val reprocess: Boolean, val iteration: Int)

    class ScoreResult(
        val pcqs: Float,
        val snceScore: Float,
        val tier: Tier,
        val passDeltaE: Boolean,
        val passTemporal: Boolean,
        val passAngular: Boolean,
        val passGamut: Boolean,
        val directive: AdaptiveDirective
    )

    private fun component(value: Float, scale: Float, weight: Float): Float =
        (1f - value / scale).coerceAtLeast(0f) * weight

    /** PCQS composite (Stage 11.4), 0..100. */
    fun pcqs(m: FrameMetrics): Float =
        component(m.deltaEMean, 3.0f, 30f) +
            component(m.hueRmse, 5.0f, 20f) +
            component(m.chromaRmse, 4.0f, 15f) +
            component(m.temporalDeltaE, 0.8f, 15f) +
            component(m.angularDeltaE, 2.0f, 10f) +
            m.gamutCoverageP3.coerceIn(0f, 1f) * 10f

    /** SNCE v2.0 composite score (Part IX), 0..100. */
    fun snceScore(m: FrameMetrics): Float =
        component(m.deltaEMean, 3.0f, 30f) +
            component(m.hueRmse, 5.0f, 20f) +
            component(m.chromaRmse, 4.0f, 10f) +
            component(m.skinDeltaE, 2.0f, 10f) +
            component(m.temporalDeltaE, 0.8f, 10f) +
            component(m.angularDeltaE, 2.0f, 10f) +
            m.gamutCoverageP3.coerceIn(0f, 1f) * 10f

    fun tierOf(score: Float): Tier = when {
        score >= 98f -> Tier.ELITE
        score >= 95f -> Tier.TARGET
        score >= 90f -> Tier.MINIMUM
        else -> Tier.FAIL
    }

    fun evaluate(m: FrameMetrics, iteration: Int = 0): ScoreResult {
        val score = snceScore(m)
        val tier = tierOf(score)

        val passDeltaE = m.deltaEMean <= 2.0f && m.deltaEMax <= 3.0f
        val passTemporal = m.temporalDeltaE <= 0.5f
        val passAngular = m.angularDeltaE <= 1.5f
        val passGamut = m.gamutCoverageP3 >= 0.98f && m.gamutCoverage2020 >= 0.85f

        val action = when {
            tier != Tier.FAIL && passDeltaE && passTemporal && passAngular && passGamut -> AdaptiveAction.NONE
            // pick the dominant failure mode (directive 13.5 priority order)
            m.hueRmse / 5.0f >= maxOf(m.chromaRmse / 4.0f, m.temporalDeltaE / 0.8f, m.angularDeltaE / 2.0f) &&
                m.hueRmse > 1.0f -> AdaptiveAction.STRENGTHEN_CAM16_MEMORY
            m.temporalDeltaE > 0.5f &&
                m.temporalDeltaE / 0.8f >= m.angularDeltaE / 2.0f -> AdaptiveAction.REDUCE_AWB_ALPHA
            m.angularDeltaE > 1.5f -> AdaptiveAction.INCREASE_IMU_WEIGHT
            else -> AdaptiveAction.RECOMPUTE_SIEE
        }
        val reprocess = action != AdaptiveAction.NONE && iteration < 2

        return ScoreResult(
            pcqs = pcqs(m),
            snceScore = score,
            tier = tier,
            passDeltaE = passDeltaE,
            passTemporal = passTemporal,
            passAngular = passAngular,
            passGamut = passGamut,
            directive = AdaptiveDirective(action, reprocess, iteration)
        )
    }
}
