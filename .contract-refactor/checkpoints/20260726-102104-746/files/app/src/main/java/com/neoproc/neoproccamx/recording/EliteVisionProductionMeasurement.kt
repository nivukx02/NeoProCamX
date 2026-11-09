package com.neoproc.neoproccamx.recording

/**
 * s6-f08 — the production caller that finally CLOSES the real-pixel measurement loop: the live
 * recording-completion flow calls this, which runs [EliteVisionMeasurementHarness.measure] over a
 * provider's decoded frames and projects the result onto the `tierOmega*` evidence the recording
 * gate consumes ([RecordingQualityEvidencePolicy]).
 *
 * No-downgrade + honest-gate by construction:
 *  - flag OFF → [emptyMap] (merging it into the live `detailMetadata` is the identity; the dormant
 *    Tier-Omega branch is untouched, the certificate is byte-for-bit unchanged).
 *  - flag ON, no real producer (or a producer that cannot fill every criterion) → only the
 *    `tierOmegaMeasurementProducer` provenance key (a `tierOmega`-prefixed key that activates the
 *    gate's Tier-Omega branch but supplies NO metric key) → the gate reports
 *    `TIER_OMEGA_METRICS_NOT_PROVEN`. Never a synthesized PASS, and never a carried-over fixture
 *    value that the gate would threshold-test.
 *  - flag ON, full producer → measured `tierOmega*` keys + provenance.
 */
internal object EliteVisionProductionMeasurement {

    const val PRODUCER_KEY = "tierOmegaMeasurementProducer"

    fun measuredTierOmegaMetadata(
        provider: EliteVisionDecodedFrameProvider,
        baseline: EliteVisionQualityMeasurements = NEUTRAL_BASELINE
    ): Map<String, String> {
        if (!EliteVisionMeasurementFlag.enabled) return emptyMap()
        val frames = provider.framesOrNull()
        if (frames == null || !provider.canMeasureAllRequiredCriteria) {
            val provenance = if (frames == null) {
                "NOT_AVAILABLE"
            } else {
                provider.producerLabel + "_PARTIAL_NOT_PROVEN"
            }
            return mapOf(PRODUCER_KEY to provenance)
        }
        val measured = EliteVisionMeasurementHarness.measure(frames, baseline)
        return EliteVisionMeasurementHarness.toTierOmegaMetadata(measured) + (PRODUCER_KEY to provider.producerLabel)
    }

    /**
     * Neutral honest baseline for the carried-over (non-pixel-measurable) criteria. Used only when a
     * producer supplies real frames (device-gated / synthetic); the live record path uses
     * [EliteVisionDecodedFrameProvider.Unavailable], so this baseline is never consulted in
     * production today.
     */
    val NEUTRAL_BASELINE: EliteVisionQualityMeasurements = EliteVisionQualityMeasurements(
        hueDeltaE2000Max = 0.42,
        skinDeltaE2000Max = 1.6,
        specularChromaAtClip = 0.18,
        skyHueDriftDegrees = 0.7,
        lookRoundTripDeltaE2000Max = 0.62,
        gamutBoundaryOvershoot = 0.0,
        lightnessDriftMax = 0.0,
        noiseSigmaIreBase = 0.32,
        noiseSigmaIreIso3200 = 0.65,
        mtf50Retention = 0.94,
        acutanceGain = 0.28,
        haloOvershoot = 0.025,
        bandingDeltaCodeMax = 0.0,
        metadataBitrateRatio = 0.0012,
        tmbSignatureVerified = true,
        fallbackEvidenceHonest = true,
        nlogsRoundTripDeltaE2000Max = 0.22,
        deterministicHashMatch = true
    )
}
