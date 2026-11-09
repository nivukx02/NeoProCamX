package com.neoproc.neoproccamx.recording

/**
 * s6-f08 — supplies decoded golden frames to the production measurement loop. A producer returns
 * null (or declares it cannot measure all required criteria) when no real-frame source is available,
 * so the gate reports honest NOT_PROVEN — never a fixture-as-measurement PASS.
 *
 * [canMeasureAllRequiredCriteria] guards the chart-dependent criteria (slanted-edge MTF50,
 * ColorChecker ΔE2000): a generic decoded record/export frame has no chart, so a producer over such
 * frames must declare `false` and the loop emits NOT_PROVEN rather than carrying over baseline
 * fixture thresholds that the gate would (dishonestly) threshold-test.
 */
internal interface EliteVisionDecodedFrameProvider {
    /** Provenance label surfaced in the evidence (SYNTHETIC | NPV10_EXPORT_DECODE | NOT_AVAILABLE). */
    val producerLabel: String

    /** True only when this producer can honestly fill every required Tier-Omega criterion. */
    val canMeasureAllRequiredCriteria: Boolean

    fun framesOrNull(): EliteVisionMeasurementHarness.GoldenFrames?

    /**
     * The honest default for the live record/export path: no chart-bearing analysis frame is
     * available at the JVM/record boundary, so it yields no frames → NOT_PROVEN. A real device-side
     * chart-frame tap replaces this behind the device-gated flag.
     */
    object Unavailable : EliteVisionDecodedFrameProvider {
        override val producerLabel: String = "NOT_AVAILABLE"
        override val canMeasureAllRequiredCriteria: Boolean = false
        override fun framesOrNull(): EliteVisionMeasurementHarness.GoldenFrames? = null
    }

    /**
     * Deterministic synthetic chart provider (CP-Q5): fully in-repo, reproducible goldens that fill
     * every criterion. Used to exercise and regression-lock the measure→gate flow; it is a
     * determinism vehicle, not a real-pixel quality oracle (provenance == SYNTHETIC discloses this).
     */
    object DeterministicSynthetic : EliteVisionDecodedFrameProvider {
        override val producerLabel: String = "SYNTHETIC"
        override val canMeasureAllRequiredCriteria: Boolean = true

        override fun framesOrNull(): EliteVisionMeasurementHarness.GoldenFrames =
            EliteVisionMeasurementHarness.GoldenFrames(
                edgeLuma = slantedEdge(EDGE, EDGE),
                edgeWidth = EDGE,
                edgeHeight = EDGE,
                referenceRawMtf50CyPerPx = 0.38,
                baseIsoFlatLuma = flatField(0.5f, 0.0012f, FLAT),
                highIsoFlatLuma = flatField(0.5f, 0.004f, FLAT),
                colorCheckerMeasuredLab = colorChecker(),
                colorCheckerReferenceLab = colorChecker(),
                grayPatchLab = floatArrayOf(50f, 0.2f, 0.2f),
                temporalSequence = listOf(
                    FloatArray(FLAT) { 0.4f + 0.005f },
                    FloatArray(FLAT) { 0.4f - 0.005f }
                )
            )

        private const val EDGE = 32
        private const val FLAT = 1024

        /** Deterministic slanted hard edge (left 0.2 / right 0.8, slope 0.06). */
        private fun slantedEdge(width: Int, height: Int): FloatArray {
            val img = FloatArray(width * height)
            for (y in 0 until height) {
                val edge = width / 2.0 + 0.06 * y
                for (x in 0 until width) {
                    img[y * width + x] = if (x.toDouble() > edge) 0.8f else 0.2f
                }
            }
            return img
        }

        private fun flatField(mean: Float, sigma: Float, n: Int): FloatArray =
            FloatArray(n) { if (it % 2 == 0) mean + sigma else mean - sigma }

        private fun colorChecker(): List<FloatArray> = listOf(
            floatArrayOf(38f, 14f, 15f),
            floatArrayOf(66f, 13f, 17f),
            floatArrayOf(51f, -10f, -23f),
            floatArrayOf(43f, -16f, 22f)
        )
    }
}
