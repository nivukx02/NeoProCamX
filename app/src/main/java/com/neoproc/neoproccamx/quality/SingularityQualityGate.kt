package com.neoproc.neoproccamx.quality

internal enum class SingularityQualityGateStatus {
    PASS,
    WARN,
    FAIL,
    NOT_APPLICABLE,
    NOT_TESTED
}

internal enum class SingularityQualityGateId(val title: String) {
    QG01("Edge Overshoot"),
    QG02("Temporal Shimmer"),
    QG03("Chroma Crawl"),
    QG04("Highlight Rolloff"),
    QG05("Shadow Depth"),
    QG06("Skin Integrity"),
    QG07("Hair And Fine Texture"),
    QG08("Text Stability"),
    QG09("Neural Resolution Honesty"),
    QG10("Tone Stability"),
    QG11("Color Neutrality"),
    QG12("Encode Survival"),
    QG13("Preview Match"),
    QG14("Thermal Endurance"),
    QG15("Dropped Frame Control"),
    QG16("Metadata Honesty")
}

internal data class SingularityQualityGateResult(
    val gateId: SingularityQualityGateId,
    val status: SingularityQualityGateStatus,
    val mandatory: Boolean,
    val measuredValue: Double?,
    val threshold: String,
    val unit: String,
    val evidenceReference: String,
    val failureCode: String,
    val responsibleStageId: Int?,
    val fallbackDecision: String,
    val recordingImpact: String
) {
    init {
        require(threshold.isNotBlank()) { "threshold is required" }
        require(unit.isNotBlank()) { "unit is required" }
        responsibleStageId?.let {
            require(it in 0 until SingularityFrameGraphRegistry.STAGE_COUNT) {
                "responsibleStageId must be 0..255"
            }
        }
        if (status == SingularityQualityGateStatus.PASS || status == SingularityQualityGateStatus.WARN) {
            require(evidenceReference.isNotBlank()) { "measured gate results require evidenceReference" }
            require(measuredValue != null) { "measured gate results require measuredValue" }
        }
        if (status == SingularityQualityGateStatus.FAIL) {
            require(failureCode.isNotBlank()) { "failed gate requires failureCode" }
            require(responsibleStageId != null) { "failed gate requires responsibleStageId" }
        }
        if (status == SingularityQualityGateStatus.NOT_TESTED) {
            require(measuredValue == null) { "NOT_TESTED cannot contain a measured value" }
        }
    }

    val blocksHighestClass: Boolean
        get() = mandatory && status != SingularityQualityGateStatus.PASS

    companion object {
        fun pass(
            gateId: SingularityQualityGateId,
            measuredValue: Double = 0.0,
            threshold: String = "device-mode-specific",
            unit: String = "normalized",
            evidenceReference: String = "validated-test-evidence",
            responsibleStageId: Int? = null
        ): SingularityQualityGateResult =
            SingularityQualityGateResult(
                gateId = gateId,
                status = SingularityQualityGateStatus.PASS,
                mandatory = true,
                measuredValue = measuredValue,
                threshold = threshold,
                unit = unit,
                evidenceReference = evidenceReference,
                failureCode = "",
                responsibleStageId = responsibleStageId,
                fallbackDecision = "NONE",
                recordingImpact = "NONE"
            )
    }
}

internal data class SingularityGateThreshold(
    val gateId: SingularityQualityGateId,
    val mandatory: Boolean,
    val unit: String,
    val passCondition: String,
    val warnCondition: String,
    val minimumReleaseClass: SingularityReleaseClass
)

internal data class SingularityThresholdSet(
    val formatVersion: String,
    val gatePolicyVersion: String,
    val thresholdSetId: String,
    val deviceClass: String,
    val outputMode: String,
    val resolution: String,
    val frameRate: Int,
    val thresholds: List<SingularityGateThreshold>
) {
    init {
        require(formatVersion == SingularityThresholdAssetContract.FORMAT_VERSION)
        require(gatePolicyVersion == SingularityThresholdAssetContract.GATE_POLICY_VERSION)
        require(thresholdSetId.isNotBlank())
        require(deviceClass.isNotBlank())
        require(outputMode.isNotBlank())
        require(resolution.isNotBlank())
        require(frameRate > 0)
        require(thresholds.map { it.gateId }.toSet() == SingularityQualityGateId.entries.toSet()) {
            "Threshold set must contain QG01..QG16 exactly once"
        }
        require(thresholds.size == SingularityQualityGateId.entries.size) {
            "Threshold set cannot contain duplicate gates"
        }
    }
}

internal data class SingularityThresholdAssetValidation(
    val valid: Boolean,
    val discoveredGateIds: Set<SingularityQualityGateId>,
    val failures: List<String>
)

internal object SingularityThresholdAssetContract {
    const val ASSET_PATH = "quality/singularity_thresholds.json"
    const val FORMAT_VERSION = "SINGULARITY_THRESHOLDS_1"
    const val GATE_POLICY_VERSION = "QG_SINGULARITY_1"

    private val gateIdPattern = Regex("\"gateId\"\\s*:\\s*\"(QG\\d{2})\"")

    fun validateAssetText(jsonText: String): SingularityThresholdAssetValidation {
        val failures = buildList {
            if (jsonText.isBlank()) add("threshold asset is empty")
            if (!jsonText.contains("\"formatVersion\"") || !jsonText.contains(FORMAT_VERSION)) {
                add("formatVersion must be $FORMAT_VERSION")
            }
            if (!jsonText.contains("\"gatePolicyVersion\"") || !jsonText.contains(GATE_POLICY_VERSION)) {
                add("gatePolicyVersion must be $GATE_POLICY_VERSION")
            }
            if (!jsonText.contains("\"thresholdSetId\"")) add("thresholdSetId is required")
            if (!jsonText.contains("\"thresholdSets\"")) add("thresholdSets array is required")
        }.toMutableList()
        val gateTokens = gateIdPattern.findAll(jsonText).map { it.groupValues[1] }.toList()
        val discovered = gateTokens.mapNotNull { token ->
            SingularityQualityGateId.entries.firstOrNull { it.name == token }
        }.toSet()
        val expected = SingularityQualityGateId.entries.toSet()
        val unknownTokens = gateTokens.filter { token -> expected.none { it.name == token } }.distinct()
        if (unknownTokens.isNotEmpty()) failures += "unknown gate ids: ${unknownTokens.joinToString()}"
        if (discovered != expected) {
            failures += "threshold asset must enumerate QG01..QG16"
        }
        SingularityQualityGateId.entries.forEach { gateId ->
            if (!jsonText.contains("\"${gateId.title}\"")) {
                failures += "${gateId.name} title is missing"
            }
        }
        return SingularityThresholdAssetValidation(
            valid = failures.isEmpty(),
            discoveredGateIds = discovered,
            failures = failures
        )
    }

    // s8-f09 (CP-Q11): the synthesized all-PASS gate-result factory was retired from production —
    // manufacturing 16 PASS verdicts with zero measurement violates the honest-gate contract (§0.6).
    // The real verdict path is SingularityStage4QualityGateAutomation (every verdict earned from a
    // measurement; unmeasurable gates land NOT_TESTED/NOT_APPLICABLE, never PASS). The all-PASS
    // builder now lives only as a test fixture (SingularityQualityGateTestFixtures).
}
