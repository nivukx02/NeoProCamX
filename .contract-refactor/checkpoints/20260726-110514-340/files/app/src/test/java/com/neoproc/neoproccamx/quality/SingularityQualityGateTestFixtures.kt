package com.neoproc.neoproccamx.quality

/**
 * s8-f09 — test-only home for the all-PASS Singularity fixture builder. This logic used to live in
 * production (`SingularityThresholdAssetContract.allPassingResults`) where it synthesized 16 PASS
 * verdicts with zero measurement — the exact anti-pattern the honest-gate contract (§0.6) forbids.
 * It was a TEST fixture in practice (only test callers), so it is relocated here verbatim and removed
 * from production: no synthesized-PASS factory ships in app code. The real production verdict path is
 * [SingularityStage4QualityGateAutomation], which earns every verdict from a measurement.
 */
internal object SingularityQualityGateTestFixtures {
    fun allPassingResults(
        evidencePrefix: String = "singularity-quality-gate"
    ): List<SingularityQualityGateResult> =
        SingularityQualityGateId.entries.mapIndexed { index, gateId ->
            SingularityQualityGateResult.pass(
                gateId = gateId,
                evidenceReference = "$evidencePrefix/${gateId.name}",
                responsibleStageId = (247 + index.coerceAtMost(8)).coerceAtMost(255)
            )
        }
}
