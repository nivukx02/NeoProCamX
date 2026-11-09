package com.neoproc.neoproccamx.quality

import java.util.Locale

internal enum class SingularityElite100xAuthority {
    THRESHOLD_ATTESTATION,
    SOURCE_PRECISION,
    OUTPUT_PRECISION,
    TRANSPORT_PROOF,
    QUALITY_GATE_CLOSURE,
    CRITICAL_ENGINE_EVIDENCE,
    ZERO_GRAIN_CLARITY,
    MICRODETAIL_HONESTY,
    ANTI_MOTION_BLUR,
    PREVIEW_ENCODE_ARTIFACTS,
    FRAME_LINEAGE_PARITY,
    SUSTAINED_CADENCE,
    ENCODE_DENSITY,
    METADATA_INDEPENDENCE
}

internal enum class SingularityElite100xStatus {
    PASS,
    WARN,
    FAIL,
    NOT_TESTED
}

internal data class SingularityElite100xInput(
    val recordingId: String,
    val outputMode: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val measuredOutputBitrateBps: Int,
    val sourceBitDepth: Int,
    val encodedBitDepth: Int,
    val storageBitDepth: Int,
    val rawSensorOriginal: Boolean,
    val expectedFrameCount: Long,
    val actualFrameCount: Long,
    val droppedFrames: Int,
    val maxAllowedDroppedFrames: Int,
    val transportClassification: SingularityTransportClassification,
    val thresholdSetId: String,
    val thresholdSetHash: String,
    val qualityGateResults: List<SingularityQualityGateResult>,
    val stage5EngineRecords: List<SingularityStage5EngineRecord>,
    val artifactReferences: Map<String, String>,
    val metadata: Map<String, String>
)

internal data class SingularityElite100xAuthorityResult(
    val authority: SingularityElite100xAuthority,
    val status: SingularityElite100xStatus,
    val releaseCap: SingularityReleaseClass,
    val evidenceReference: String,
    val measuredValue: String = "",
    val threshold: String = "",
    val blockingCode: String = "",
    val reason: String = ""
) {
    init {
        require(evidenceReference.isNotBlank()) { "Elite 100X evidence reference is required" }
        if (status == SingularityElite100xStatus.FAIL) {
            require(blockingCode.isNotBlank()) { "Elite 100X failed authority requires blockingCode" }
        }
    }
}

internal data class SingularityElite100xBundle(
    val recordingId: String,
    val outputMode: String,
    val results: List<SingularityElite100xAuthorityResult>
) {
    init {
        require(results.map { it.authority }.toSet() == SingularityElite100xAuthority.entries.toSet()) {
            "Elite 100X must report every authority"
        }
        require(results.size == SingularityElite100xAuthority.entries.size) {
            "Elite 100X authority records must be unique"
        }
    }

    val releaseCap: SingularityReleaseClass
        get() = results.fold(SingularityReleaseClass.SINGULARITY_100000) { cap, result ->
            cap.atMost(result.releaseCap)
        }

    val qualified: Boolean
        get() = releaseCap == SingularityReleaseClass.SINGULARITY_100000 &&
            results.all { it.status == SingularityElite100xStatus.PASS }

    val blockingCodes: List<String>
        get() = results.mapNotNull { it.blockingCode.takeIf(String::isNotBlank) }

    val compatibilityProfiles: List<String>
        get() = buildList {
            if (releaseCap == SingularityReleaseClass.CORE_HONEST) add("ELITE-100X-COMPATIBILITY-CAPPED")
            results.filter { it.status != SingularityElite100xStatus.PASS }.forEach { result ->
                add("ELITE-100X-${result.authority.name}-${result.status.name}")
            }
        }.distinct()

    fun toMetadata(prefix: String = "singularityElite100x"): Map<String, String> = buildMap {
        put("${prefix}Version", SingularityElite100xPolicy.VERSION)
        put("${prefix}ReportVersion", REPORT_VERSION)
        put("${prefix}RecordingId", recordingId)
        put("${prefix}OutputMode", outputMode)
        put("${prefix}Qualified", qualified.toString())
        put("${prefix}ReleaseCap", releaseCap.label)
        put("${prefix}AuthorityCount", results.size.toString())
        put("${prefix}AuthoritySummary", results.statusSummary())
        put("${prefix}BlockingCodes", blockingCodes.joinToString(separator = ","))
        put("${prefix}CompatibilityProfiles", compatibilityProfiles.joinToString(separator = ","))
        put("${prefix}ReportJson", toJsonText())
        results.forEach { result ->
            put(
                "${prefix}.${result.authority.name}",
                buildString {
                    append(result.status.name)
                    append("|cap=").append(result.releaseCap.label)
                    append("|evidence=").append(result.evidenceReference.replace('|', '/'))
                    if (result.measuredValue.isNotBlank()) append("|measured=").append(result.measuredValue.replace('|', '/'))
                    if (result.threshold.isNotBlank()) append("|threshold=").append(result.threshold.replace('|', '/'))
                    if (result.blockingCode.isNotBlank()) append("|blocking=").append(result.blockingCode)
                    if (result.reason.isNotBlank()) append("|reason=").append(result.reason.replace('|', '/'))
                }
            )
        }
    }

    fun toJsonText(): String = buildString {
        append("{")
        append("\"formatVersion\":\"").append(REPORT_VERSION).append("\",")
        append("\"policyVersion\":\"").append(SingularityElite100xPolicy.VERSION).append("\",")
        append("\"recordingId\":\"").append(recordingId.eliteJsonEscaped()).append("\",")
        append("\"outputMode\":\"").append(outputMode.eliteJsonEscaped()).append("\",")
        append("\"qualified\":").append(qualified).append(",")
        append("\"releaseCap\":\"").append(releaseCap.label).append("\",")
        append("\"authoritySummary\":\"").append(results.statusSummary().eliteJsonEscaped()).append("\",")
        append("\"blockingCodes\":").append(blockingCodes.eliteJsonArray()).append(",")
        append("\"compatibilityProfiles\":").append(compatibilityProfiles.eliteJsonArray()).append(",")
        append("\"authorities\":[")
        append(results.joinToString(separator = ",") { it.toJsonObject() })
        append("]}")
    }

    companion object {
        const val REPORT_VERSION = "SINGULARITY_ELITE_100000X_REPORT_V20"
    }
}

internal object SingularityElite100xPolicy {
    const val VERSION = "SINGULARITY_ELITE_100000X_POLICY_V20"

    fun evaluate(input: SingularityElite100xInput): SingularityElite100xBundle {
        val context = Context(input)
        return SingularityElite100xBundle(
            recordingId = input.recordingId,
            outputMode = input.outputMode,
            results = listOf(
                context.thresholdAttestation(),
                context.sourcePrecision(),
                context.outputPrecision(),
                context.transportProof(),
                context.qualityGateClosure(),
                context.criticalEngineEvidence(),
                context.zeroGrainClarity(),
                context.microdetailHonesty(),
                context.antiMotionBlur(),
                context.previewEncodeArtifacts(),
                context.frameLineageParity(),
                context.sustainedCadence(),
                context.encodeDensity(),
                context.metadataIndependence()
            )
        )
    }

    private class Context(private val input: SingularityElite100xInput) {
        private val metadata = input.metadata
        private val gates = input.qualityGateResults.associateBy { it.gateId }
        private val stage5Records = input.stage5EngineRecords.associateBy { it.engineId }
        private val mode = input.outputMode.uppercase(Locale.US)
        private val rawMode = mode.contains("RAW")
        private val tenBitMode = rawMode ||
            mode.contains("NDR") ||
            mode.contains("HDR") ||
            mode.contains("LOG") ||
            mode.contains("NPV10") ||
            mode.contains("PRO") ||
            mode.contains("NORMAL")

        fun thresholdAttestation(): SingularityElite100xAuthorityResult {
            val hash = input.thresholdSetHash.trim()
            val hashFormatValid = SHA256_PATTERN.matches(hash)
            val defaultSet = input.thresholdSetId == SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_ID
            val defaultHashValid = !defaultSet || hash.equals(FOUNDATION_THRESHOLD_ASSET_SHA256, ignoreCase = true)
            return if (input.thresholdSetId.isNotBlank() && hashFormatValid && defaultHashValid) {
                pass(
                    SingularityElite100xAuthority.THRESHOLD_ATTESTATION,
                    "elite100x:threshold:${input.thresholdSetId}:$hash",
                    measured = "thresholdSetId=${input.thresholdSetId};sha256=$hash",
                    threshold = "device/mode threshold set with canonical SHA-256"
                )
            } else {
                notTested(
                    SingularityElite100xAuthority.THRESHOLD_ATTESTATION,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:threshold:unattested",
                    measured = "thresholdSetId=${input.thresholdSetId};hash=$hash",
                    threshold = "64-character SHA-256; canonical foundation hash when generic profile is used",
                    reason = "Elite 100X requires a cryptographically attested threshold set."
                )
            }
        }

        fun sourcePrecision(): SingularityElite100xAuthorityResult {
            val required = if (rawMode) 12 else if (tenBitMode) 10 else 10
            val sourceOk = input.sourceBitDepth >= required
            val rawOk = !rawMode || input.rawSensorOriginal
            return when {
                sourceOk && rawOk -> pass(
                    SingularityElite100xAuthority.SOURCE_PRECISION,
                    "elite100x:source-precision",
                    measured = "source=${input.sourceBitDepth};rawSensorOriginal=${input.rawSensorOriginal}",
                    threshold = "requiredSourceBitDepth>=$required"
                )
                rawMode && !input.rawSensorOriginal -> fail(
                    SingularityElite100xAuthority.SOURCE_PRECISION,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:source-precision:raw-sensor-not-original",
                    "ELITE100X_RAW_SENSOR_ORIGINAL_NOT_PROVEN",
                    measured = "source=${input.sourceBitDepth};rawSensorOriginal=${input.rawSensorOriginal}",
                    threshold = "rawSensorOriginal=true;sourceBitDepth>=$required",
                    reason = "RAW elite output cannot be claimed without original sensor RAW evidence."
                )
                else -> fail(
                    SingularityElite100xAuthority.SOURCE_PRECISION,
                    SingularityReleaseClass.SINGULARITY_25,
                    "elite100x:source-precision:insufficient-bit-depth",
                    "ELITE100X_SOURCE_BIT_DEPTH_BELOW_REQUIREMENT",
                    measured = "source=${input.sourceBitDepth}",
                    threshold = "sourceBitDepth>=$required",
                    reason = "Elite 100X requires proven source precision for every video mode."
                )
            }
        }

        fun outputPrecision(): SingularityElite100xAuthorityResult {
            val encodedRequired = if (rawMode) 0 else 10
            val storageRequired = if (rawMode) 12 else 10
            val encodedOk = rawMode || input.encodedBitDepth >= encodedRequired
            val storageOk = input.storageBitDepth >= storageRequired
            return if (encodedOk && storageOk) {
                pass(
                    SingularityElite100xAuthority.OUTPUT_PRECISION,
                    "elite100x:output-precision",
                    measured = "encoded=${input.encodedBitDepth};storage=${input.storageBitDepth}",
                    threshold = "encodedBitDepth>=$encodedRequired;storageBitDepth>=$storageRequired"
                )
            } else {
                fail(
                    SingularityElite100xAuthority.OUTPUT_PRECISION,
                    SingularityReleaseClass.SINGULARITY_25,
                    "elite100x:output-precision:below-required",
                    "ELITE100X_OUTPUT_PRECISION_BELOW_REQUIREMENT",
                    measured = "encoded=${input.encodedBitDepth};storage=${input.storageBitDepth}",
                    threshold = "encodedBitDepth>=$encodedRequired;storageBitDepth>=$storageRequired",
                    reason = "Elite 100X output precision was not proven."
                )
            }
        }

        fun transportProof(): SingularityElite100xAuthorityResult =
            when (input.transportClassification) {
                SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
                SingularityTransportClassification.ENCODER_SURFACE_DIRECT,
                SingularityTransportClassification.PRIVATE_MASTER_GPU_EXPORT,
                SingularityTransportClassification.RAW_SENSOR_PRIVATE_MASTER ->
                    pass(
                        SingularityElite100xAuthority.TRANSPORT_PROOF,
                        "elite100x:transport:${input.transportClassification.name}",
                        measured = input.transportClassification.name,
                        threshold = "singularity transport"
                    )
                SingularityTransportClassification.AHB_GPU_COMPATIBILITY ->
                    warn(
                        SingularityElite100xAuthority.TRANSPORT_PROOF,
                        SingularityReleaseClass.SINGULARITY_75,
                        "elite100x:transport:ahb-gpu-compatibility",
                        measured = input.transportClassification.name,
                        threshold = "AHB_VULKAN_ZERO_COPY or direct/private master for SINGULARITY-100",
                        reason = "AHB GPU compatibility is valid but not full Elite 100X zero-copy proof."
                    )
                SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY,
                SingularityTransportClassification.UNPROVEN ->
                    fail(
                        SingularityElite100xAuthority.TRANSPORT_PROOF,
                        SingularityReleaseClass.CORE_HONEST,
                        "elite100x:transport:${input.transportClassification.name}",
                        "ELITE100X_TRANSPORT_NOT_SINGULARITY_PROVEN",
                        measured = input.transportClassification.name,
                        threshold = "hardware-backed Singularity transport",
                        reason = "Compatibility or unproven transport cannot receive an elite Singularity claim."
                    )
            }

        fun qualityGateClosure(): SingularityElite100xAuthorityResult {
            val missing = SingularityQualityGateId.entries.filterNot { gates.containsKey(it) }
            val duplicates = input.qualityGateResults
                .groupingBy { it.gateId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (missing.isNotEmpty() || duplicates.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:qg:missing-or-duplicate",
                    measured = "missing=${missing.joinToString { it.name }};duplicates=${duplicates.joinToString { it.name }}",
                    threshold = "QG01..QG16 exactly once",
                    reason = "Elite 100X needs a complete quality-gate ledger."
                )
            }
            val failed = input.qualityGateResults.filter { it.status == SingularityQualityGateStatus.FAIL }
            if (failed.any { it.gateId == SingularityQualityGateId.QG16 }) {
                return fail(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:qg:qg16-failed",
                    "ELITE100X_METADATA_HONESTY_GATE_FAILED",
                    measured = failed.joinToString { "${it.gateId.name}=${it.status.name}" },
                    threshold = "all mandatory gates PASS",
                    reason = "Metadata honesty failure blocks release."
                )
            }
            if (failed.isNotEmpty()) {
                return fail(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    SingularityReleaseClass.SINGULARITY_25,
                    "elite100x:qg:failed",
                    "ELITE100X_QUALITY_GATE_FAILED",
                    measured = failed.joinToString { "${it.gateId.name}=${it.status.name}" },
                    threshold = "all mandatory gates PASS",
                    reason = "One or more quality gates failed."
                )
            }
            val notPass = input.qualityGateResults.filter { it.status != SingularityQualityGateStatus.PASS }
            if (notPass.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:qg:not-all-pass",
                    measured = input.qualityGateResults.qualityGateStatusSummary(),
                    threshold = "all mandatory gates PASS",
                    reason = "WARN, NOT_TESTED, or NOT_APPLICABLE gates cap Elite 100X."
                )
            }
            val requiredMeasuredGateIds = (1..13).map { "QG${it.toString().padStart(2, '0')}" }.toSet()
            val attestedMeasuredGateIds = metadata.clean("singularityEliteMeasuredGateIds")
                .orEmpty()
                .split(',', ';', ' ')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet()
            val artifactManifestHash = metadata.clean("singularityEliteArtifactManifestSha256").orEmpty()
            val artifactAttested =
                attestedMeasuredGateIds.containsAll(requiredMeasuredGateIds) &&
                    SHA256_PATTERN.matches(artifactManifestHash)
            return if (artifactAttested) {
                pass(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    "elite100x:qg:all-pass",
                    measured =
                        "${input.qualityGateResults.qualityGateStatusSummary()};" +
                            "measuredGateIds=${attestedMeasuredGateIds.sorted().joinToString()};" +
                            "artifactManifestSha256=$artifactManifestHash",
                    threshold = "PASS=16 + pixel/chart artifact attestation for QG01..QG13"
                )
            } else {
                notTested(
                    SingularityElite100xAuthority.QUALITY_GATE_CLOSURE,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:qg:artifact-attestation-missing",
                    measured =
                        "${input.qualityGateResults.qualityGateStatusSummary()};" +
                            "measuredGateIds=${attestedMeasuredGateIds.sorted().joinToString()};" +
                            "artifactManifestSha256=$artifactManifestHash",
                    threshold = "QG01..QG13 measured artifact IDs + 64-character manifest SHA-256",
                    reason = "Metadata-derived scores cannot qualify Elite 100X without pixel/chart artifact attestation."
                )
            }
        }

        fun criticalEngineEvidence(): SingularityElite100xAuthorityResult {
            val required = buildSet {
                add(SingularityStage5EngineId.EXPOSURE_DYNAMIC_RANGE)
                add(SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION)
                add(SingularityStage5EngineId.MOTION_TEMPORAL)
                add(SingularityStage5EngineId.TEXTURE_AUTHORITY)
                add(SingularityStage5EngineId.RESOLUTION_HONESTY)
                add(SingularityStage5EngineId.COLOR_AUTHORITY)
                add(SingularityStage5EngineId.TONE_STABILITY)
                add(SingularityStage5EngineId.QC_AUTOMATION)
                if (rawMode) add(SingularityStage5EngineId.RAW_RECONSTRUCTION)
            }
            val missing = required.filterNot { stage5Records.containsKey(it) }
            if (missing.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.CRITICAL_ENGINE_EVIDENCE,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:engines:missing",
                    measured = "missing=${missing.joinToString { it.name }}",
                    threshold = "all critical Stage 5 engines reported",
                    reason = "Critical engine evidence is missing."
                )
            }
            val notReady = required.mapNotNull { id ->
                stage5Records.getValue(id).takeIf {
                    it.status == SingularityStage5EngineStatus.NOT_TESTED ||
                        it.status == SingularityStage5EngineStatus.DISABLED_BY_DEVICE
                }
            }
            if (notReady.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.CRITICAL_ENGINE_EVIDENCE,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:engines:not-ready",
                    measured = notReady.joinToString { "${it.engineId.name}=${it.status.name}" },
                    threshold = "critical engines implemented and evidenced",
                    reason = "Critical image-quality engine evidence is not complete."
                )
            }
            val reduced = required.mapNotNull { id ->
                stage5Records.getValue(id).takeIf {
                    it.status == SingularityStage5EngineStatus.FALLBACK_EXECUTED ||
                        it.status == SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE
                }
            }
            return if (reduced.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.CRITICAL_ENGINE_EVIDENCE,
                    "elite100x:engines:critical-pass",
                    measured = "critical=${required.joinToString { it.name }}",
                    threshold = "IMPLEMENTED_AND_EVIDENCED"
                )
            } else {
                warn(
                    SingularityElite100xAuthority.CRITICAL_ENGINE_EVIDENCE,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:engines:reduced",
                    measured = reduced.joinToString { "${it.engineId.name}=${it.status.name}" },
                    threshold = "no critical fallback or needs-device-evidence",
                    reason = "Critical engines are visible but reduced or need additional device evidence."
                )
            }
        }

        fun zeroGrainClarity(): SingularityElite100xAuthorityResult {
            val falseFlags = trueFlags(
                "singularityStage5V20FalseClaimDetected",
                "zeroGrainClarityFalseClaimDetected",
                "v20ZeroGrainClarityFalseClaimDetected",
                "denoiseFalseClaimDetected",
                "clarityFalseClaimDetected"
            )
            if (falseFlags.isNotEmpty()) {
                return fail(
                    SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:v20:zero-grain:false-claim",
                    "ELITE100X_V20_ZERO_GRAIN_FALSE_CLAIM",
                    measured = falseFlags.joinToString(separator = ";") { "$it=true" },
                    threshold = V20_ZERO_GRAIN_THRESHOLD_TEXT,
                    reason = "Zero-grain clarity cannot be released with false denoise or clarity evidence."
                )
            }
            val record = stage5Records[SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION]
                ?: return notTested(
                    SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:zero-grain:engine-missing",
                    measured = "NOISE_TEXTURE_SEPARATION=missing",
                    threshold = V20_ZERO_GRAIN_THRESHOLD_TEXT,
                    reason = "Zero-grain clarity requires Stage 5 noise/texture separation evidence."
                )
            val readiness = requireImplementedRecord(
                SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                record,
                V20_ZERO_GRAIN_THRESHOLD_TEXT,
                "Zero-grain clarity requires implemented and evidenced denoise authority."
            )
            if (readiness != null) return readiness

            val zeroGrain = v20Metric(record, "zeroGrainClarityScore", "stage5ZeroGrainClarityScore")
            val grainResidual = v20Metric(record, "grainResidualScore", "stage5GrainResidualScore")
            val denoiseSafety = v20Metric(record, "denoiseSafetyScore", "stage5DenoiseSafetyScore")
            val missing = missingMetrics(
                "zeroGrainClarityScore" to zeroGrain,
                "grainResidualScore" to grainResidual,
                "denoiseSafetyScore" to denoiseSafety
            )
            if (missing.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:zero-grain:missing",
                    measured = "missing=${missing.joinToString(separator = ",")};status=${record.status.name}",
                    threshold = V20_ZERO_GRAIN_THRESHOLD_TEXT,
                    reason = "Denoise and clarity evidence must be measured before raising the release class."
                )
            }

            val zeroGrainValue = zeroGrain ?: 0.0
            val grainResidualValue = grainResidual ?: 1.0
            val denoiseSafetyValue = denoiseSafety ?: 0.0
            val unsafe = buildList {
                if (zeroGrainValue < V20_MIN_ZERO_GRAIN_CLARITY_SCORE) {
                    add("zeroGrainClarityScore=${zeroGrainValue.fixed6()}<${V20_MIN_ZERO_GRAIN_CLARITY_SCORE.fixed6()}")
                }
                if (grainResidualValue > V20_MAX_GRAIN_RESIDUAL) {
                    add("grainResidualScore=${grainResidualValue.fixed6()}>${V20_MAX_GRAIN_RESIDUAL.fixed6()}")
                }
                if (denoiseSafetyValue < V20_MIN_DENOISE_SAFETY_SCORE) {
                    add("denoiseSafetyScore=${denoiseSafetyValue.fixed6()}<${V20_MIN_DENOISE_SAFETY_SCORE.fixed6()}")
                }
                addAll(trueFlags(
                    "denoiseUnsafeDetected",
                    "v20DenoiseUnsafeDetected",
                    "zeroGrainClarityUnsafeDetected",
                    "v20ZeroGrainClarityUnsafeDetected"
                ).map { "$it=true" })
                addAll(stage5UnsafeReasons("grain", "denoise", "clarity"))
            }
            return if (unsafe.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                    "elite100x:v20:zero-grain:evidenced",
                    measured = metricsText(
                        "zeroGrainClarityScore" to zeroGrainValue,
                        "grainResidualScore" to grainResidualValue,
                        "denoiseSafetyScore" to denoiseSafetyValue
                    ),
                    threshold = V20_ZERO_GRAIN_THRESHOLD_TEXT
                )
            } else {
                fail(
                    SingularityElite100xAuthority.ZERO_GRAIN_CLARITY,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:v20:zero-grain:unsafe",
                    "ELITE100X_V20_ZERO_GRAIN_UNSAFE",
                    measured = unsafe.joinToString(separator = ";"),
                    threshold = V20_ZERO_GRAIN_THRESHOLD_TEXT,
                    reason = "Denoise or clarity evidence is present but unsafe for a real-quality release."
                )
            }
        }

        fun microdetailHonesty(): SingularityElite100xAuthorityResult {
            val falseFlags = trueFlags(
                "singularityStage5V20FalseClaimDetected",
                "microdetailFalseClaimDetected",
                "v20MicrodetailFalseClaimDetected",
                "sharpnessFalseClaimDetected",
                "clarityFalseClaimDetected"
            )
            if (falseFlags.isNotEmpty()) {
                return fail(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:v20:microdetail:false-claim",
                    "ELITE100X_V20_MICRODETAIL_FALSE_CLAIM",
                    measured = falseFlags.joinToString(separator = ";") { "$it=true" },
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT,
                    reason = "Microdetail honesty cannot be released with false sharpness or clarity evidence."
                )
            }
            val texture = stage5Records[SingularityStage5EngineId.TEXTURE_AUTHORITY]
                ?: return notTested(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:microdetail:texture-engine-missing",
                    measured = "TEXTURE_AUTHORITY=missing",
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT,
                    reason = "Microdetail honesty requires Stage 5 texture authority evidence."
                )
            val textureReadiness = requireImplementedRecord(
                SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                texture,
                V20_MICRODETAIL_THRESHOLD_TEXT,
                "Microdetail honesty requires implemented and evidenced texture authority."
            )
            if (textureReadiness != null) return textureReadiness

            val resolution = stage5Records[SingularityStage5EngineId.RESOLUTION_HONESTY]
                ?: return notTested(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:microdetail:resolution-engine-missing",
                    measured = "RESOLUTION_HONESTY=missing",
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT,
                    reason = "Microdetail honesty requires resolution truth evidence."
                )
            val resolutionReadiness = requireImplementedRecord(
                SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                resolution,
                V20_MICRODETAIL_THRESHOLD_TEXT,
                "Microdetail honesty requires true-resolution authority, not fallback spatial output."
            )
            if (resolutionReadiness != null) return resolutionReadiness

            val microdetail = v20Metric(texture, "microdetailHonestyScore", "stage5MicrodetailHonestyScore")
            val sharpnessSafety = v20Metric(texture, "sharpnessSafetyScore", "stage5SharpnessSafetyScore")
            val edgeOvershoot = v20Metric(texture, "edgeOvershootPercent", "stage4EdgeOvershootPercent")
            val fineTexture = v20Metric(texture, "fineTextureRetention", "stage4FineTextureRetention")
            val missing = missingMetrics(
                "microdetailHonestyScore" to microdetail,
                "sharpnessSafetyScore" to sharpnessSafety,
                "edgeOvershootPercent" to edgeOvershoot,
                "fineTextureRetention" to fineTexture
            )
            if (missing.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:microdetail:missing",
                    measured = "missing=${missing.joinToString(separator = ",")};textureStatus=${texture.status.name};resolutionStatus=${resolution.status.name}",
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT,
                    reason = "Sharpness, clarity, and retained microdetail must be measured before raising release."
                )
            }

            val microdetailValue = microdetail ?: 0.0
            val sharpnessSafetyValue = sharpnessSafety ?: 0.0
            val edgeOvershootValue = edgeOvershoot ?: 1.0
            val fineTextureValue = fineTexture ?: 0.0
            val unsafe = buildList {
                if (microdetailValue < V20_MIN_MICRODETAIL_HONESTY_SCORE) {
                    add("microdetailHonestyScore=${microdetailValue.fixed6()}<${V20_MIN_MICRODETAIL_HONESTY_SCORE.fixed6()}")
                }
                if (sharpnessSafetyValue < V20_MIN_SHARPNESS_SAFETY_SCORE) {
                    add("sharpnessSafetyScore=${sharpnessSafetyValue.fixed6()}<${V20_MIN_SHARPNESS_SAFETY_SCORE.fixed6()}")
                }
                if (edgeOvershootValue > V20_MAX_EDGE_OVERSHOOT_PERCENT) {
                    add("edgeOvershootPercent=${edgeOvershootValue.fixed6()}>${V20_MAX_EDGE_OVERSHOOT_PERCENT.fixed6()}")
                }
                if (fineTextureValue < V20_MIN_FINE_TEXTURE_RETENTION) {
                    add("fineTextureRetention=${fineTextureValue.fixed6()}<${V20_MIN_FINE_TEXTURE_RETENTION.fixed6()}")
                }
                addAll(trueFlags(
                    "sharpnessUnsafeDetected",
                    "v20SharpnessUnsafeDetected",
                    "clarityUnsafeDetected",
                    "v20ClarityUnsafeDetected",
                    "microdetailUnsafeDetected",
                    "v20MicrodetailUnsafeDetected"
                ).map { "$it=true" })
                addAll(stage5UnsafeReasons("microdetail", "sharpness", "edge", "clarity"))
            }
            return if (unsafe.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    "elite100x:v20:microdetail:evidenced",
                    measured = metricsText(
                        "microdetailHonestyScore" to microdetailValue,
                        "sharpnessSafetyScore" to sharpnessSafetyValue,
                        "edgeOvershootPercent" to edgeOvershootValue,
                        "fineTextureRetention" to fineTextureValue
                    ),
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT
                )
            } else {
                fail(
                    SingularityElite100xAuthority.MICRODETAIL_HONESTY,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:v20:microdetail:unsafe",
                    "ELITE100X_V20_MICRODETAIL_UNSAFE",
                    measured = unsafe.joinToString(separator = ";"),
                    threshold = V20_MICRODETAIL_THRESHOLD_TEXT,
                    reason = "Sharpness or clarity evidence is present but unsafe or dishonest."
                )
            }
        }

        fun antiMotionBlur(): SingularityElite100xAuthorityResult {
            val falseFlags = trueFlags(
                "singularityStage5V20FalseClaimDetected",
                "antiMotionBlurFalseClaimDetected",
                "v20AntiMotionBlurFalseClaimDetected",
                "motionBlurFalseClaimDetected"
            )
            if (falseFlags.isNotEmpty()) {
                return fail(
                    SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:v20:anti-motion-blur:false-claim",
                    "ELITE100X_V20_ANTI_MOTION_BLUR_FALSE_CLAIM",
                    measured = falseFlags.joinToString(separator = ";") { "$it=true" },
                    threshold = V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT,
                    reason = "Anti-motion-blur cannot be released with false motion evidence."
                )
            }
            val record = stage5Records[SingularityStage5EngineId.MOTION_TEMPORAL]
                ?: return notTested(
                    SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:anti-motion-blur:engine-missing",
                    measured = "MOTION_TEMPORAL=missing",
                    threshold = V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT,
                    reason = "Anti-motion-blur requires Stage 5 motion-temporal evidence."
                )
            val readiness = requireImplementedRecord(
                SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                record,
                V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT,
                "Anti-motion-blur requires implemented and evidenced motion-temporal authority."
            )
            if (readiness != null) return readiness

            val antiMotionBlur = v20Metric(record, "antiMotionBlurScore", "stage5AntiMotionBlurScore")
            val motionBlurResidual = v20Metric(record, "motionBlurResidual", "stage5MotionBlurResidual")
            val temporalShimmer = v20Metric(record, "temporalShimmerPercent", "stage4TemporalShimmerPercent")
            val missing = missingMetrics(
                "antiMotionBlurScore" to antiMotionBlur,
                "motionBlurResidual" to motionBlurResidual,
                "temporalShimmerPercent" to temporalShimmer
            )
            if (missing.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:anti-motion-blur:missing",
                    measured = "missing=${missing.joinToString(separator = ",")};status=${record.status.name}",
                    threshold = V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT,
                    reason = "Anti-motion-blur must be measured before raising the release class."
                )
            }

            val antiMotionBlurValue = antiMotionBlur ?: 0.0
            val motionBlurResidualValue = motionBlurResidual ?: 1.0
            val temporalShimmerValue = temporalShimmer ?: 1.0
            val unsafe = buildList {
                if (antiMotionBlurValue < V20_MIN_ANTI_MOTION_BLUR_SCORE) {
                    add("antiMotionBlurScore=${antiMotionBlurValue.fixed6()}<${V20_MIN_ANTI_MOTION_BLUR_SCORE.fixed6()}")
                }
                if (motionBlurResidualValue > V20_MAX_MOTION_BLUR_RESIDUAL) {
                    add("motionBlurResidual=${motionBlurResidualValue.fixed6()}>${V20_MAX_MOTION_BLUR_RESIDUAL.fixed6()}")
                }
                if (temporalShimmerValue > V20_MAX_TEMPORAL_SHIMMER_PERCENT) {
                    add("temporalShimmerPercent=${temporalShimmerValue.fixed6()}>${V20_MAX_TEMPORAL_SHIMMER_PERCENT.fixed6()}")
                }
                addAll(trueFlags(
                    "motionBlurUnsafeDetected",
                    "v20MotionBlurUnsafeDetected",
                    "antiMotionBlurUnsafeDetected",
                    "v20AntiMotionBlurUnsafeDetected"
                ).map { "$it=true" })
                addAll(stage5UnsafeReasons("motion", "temporalShimmer"))
            }
            return if (unsafe.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                    "elite100x:v20:anti-motion-blur:evidenced",
                    measured = metricsText(
                        "antiMotionBlurScore" to antiMotionBlurValue,
                        "motionBlurResidual" to motionBlurResidualValue,
                        "temporalShimmerPercent" to temporalShimmerValue
                    ),
                    threshold = V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT
                )
            } else {
                fail(
                    SingularityElite100xAuthority.ANTI_MOTION_BLUR,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:v20:anti-motion-blur:unsafe",
                    "ELITE100X_V20_ANTI_MOTION_BLUR_UNSAFE",
                    measured = unsafe.joinToString(separator = ";"),
                    threshold = V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT,
                    reason = "Motion-blur evidence is present but unsafe for real video quality."
                )
            }
        }

        fun previewEncodeArtifacts(): SingularityElite100xAuthorityResult {
            val qg12 = gates[SingularityQualityGateId.QG12]
            val qg13 = gates[SingularityQualityGateId.QG13]
            val encodeArtifact = input.artifactReferences["encodedVsPreEncode"]
                ?: metadata.clean("stage4EncodedVsPreEncodeLossArtifact")
                ?: metadata.clean("encodedVsPreEncodeLossArtifact")
            val previewArtifact = input.artifactReferences["previewFileComparison"]
                ?: metadata.clean("stage4PreviewFileComparisonArtifact")
                ?: metadata.clean("previewFileComparisonArtifact")
            val missing = buildList {
                if (qg12?.status != SingularityQualityGateStatus.PASS) add("QG12=${qg12?.status ?: "MISSING"}")
                if (qg13?.status != SingularityQualityGateStatus.PASS) add("QG13=${qg13?.status ?: "MISSING"}")
                if (encodeArtifact.isNullOrBlank()) add("encodedVsPreEncodeArtifact")
                if (previewArtifact.isNullOrBlank()) add("previewFileComparisonArtifact")
            }
            return if (missing.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.PREVIEW_ENCODE_ARTIFACTS,
                    "elite100x:artifacts:qg12-qg13",
                    measured = "encodedVsPreEncode=$encodeArtifact;previewFileComparison=$previewArtifact",
                    threshold = "QG12 PASS + QG13 PASS + artifacts"
                )
            } else {
                notTested(
                    SingularityElite100xAuthority.PREVIEW_ENCODE_ARTIFACTS,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:artifacts:missing",
                    measured = missing.joinToString(separator = ";"),
                    threshold = "QG12/QG13 artifacts present",
                    reason = "Elite 100X requires encoded-vs-pre-encode and preview/file comparison artifacts."
                )
            }
        }

        fun frameLineageParity(): SingularityElite100xAuthorityResult {
            val evidence = FrameLineageEvidence.from(metadata)
            if (evidence.falseClaimDetected) {
                return fail(
                    SingularityElite100xAuthority.FRAME_LINEAGE_PARITY,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:frame-lineage:false-claim",
                    "ELITE100X_FRAME_LINEAGE_FALSE_CLAIM",
                    measured = evidence.measuredSummary(),
                    threshold = FRAME_LINEAGE_THRESHOLD_TEXT,
                    reason = "Frame-lineage metadata reports a false claim."
                )
            }
            if (evidence.missingKeys.isNotEmpty()) {
                return notTested(
                    SingularityElite100xAuthority.FRAME_LINEAGE_PARITY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:frame-lineage:missing",
                    measured = "missing=${evidence.missingKeys.joinToString(separator = ",")}",
                    threshold = FRAME_LINEAGE_THRESHOLD_TEXT,
                    reason = "Elite 100X V19 requires runtime Camera2 frame-lineage metadata."
                )
            }
            if (evidence.invalidKeys.isNotEmpty()) {
                return fail(
                    SingularityElite100xAuthority.FRAME_LINEAGE_PARITY,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:frame-lineage:invalid-values",
                    "ELITE100X_FRAME_LINEAGE_VALUES_INVALID",
                    measured = "invalid=${evidence.invalidKeys.joinToString(separator = ",")};${evidence.measuredSummary()}",
                    threshold = FRAME_LINEAGE_THRESHOLD_TEXT,
                    reason = "Frame-lineage values must be finite, non-negative, and parseable."
                )
            }
            val failures = evidence.requirementFailures()
            return if (failures.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.FRAME_LINEAGE_PARITY,
                    "elite100x:frame-lineage:certified",
                    measured = evidence.measuredSummary(),
                    threshold = FRAME_LINEAGE_THRESHOLD_TEXT
                )
            } else {
                fail(
                    SingularityElite100xAuthority.FRAME_LINEAGE_PARITY,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:frame-lineage:failed",
                    "ELITE100X_FRAME_LINEAGE_REQUIREMENTS_FAILED",
                    measured = failures.joinToString(separator = ";"),
                    threshold = FRAME_LINEAGE_THRESHOLD_TEXT,
                    reason = "Runtime preview and recording frame-lineage parity did not meet V19 limits."
                )
            }
        }

        fun sustainedCadence(): SingularityElite100xAuthorityResult {
            val qg14 = gates[SingularityQualityGateId.QG14]
            val qg15 = gates[SingularityQualityGateId.QG15]
            if (input.expectedFrameCount <= 0L || input.actualFrameCount <= 0L) {
                return notTested(
                    SingularityElite100xAuthority.SUSTAINED_CADENCE,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:cadence:missing",
                    measured = "expected=${input.expectedFrameCount};actual=${input.actualFrameCount}",
                    threshold = "positive expected and actual frame counts",
                    reason = "Sustained frame cadence evidence is missing."
                )
            }
            val frameDeficit = (input.expectedFrameCount - input.actualFrameCount).coerceAtLeast(0L)
            val effectiveDropped = maxOf(input.droppedFrames.toLong(), frameDeficit)
            val qgPassed = qg14?.status == SingularityQualityGateStatus.PASS &&
                qg15?.status == SingularityQualityGateStatus.PASS
            val cadencePassed = effectiveDropped <= input.maxAllowedDroppedFrames.toLong().coerceAtLeast(0L)
            return if (qgPassed && cadencePassed) {
                pass(
                    SingularityElite100xAuthority.SUSTAINED_CADENCE,
                    "elite100x:cadence:qg14-qg15",
                    measured = "expected=${input.expectedFrameCount};actual=${input.actualFrameCount};effectiveDropped=$effectiveDropped",
                    threshold = "QG14 PASS + QG15 PASS + dropped<=${input.maxAllowedDroppedFrames}"
                )
            } else {
                fail(
                    SingularityElite100xAuthority.SUSTAINED_CADENCE,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:cadence:failed",
                    "ELITE100X_SUSTAINED_CADENCE_NOT_PROVEN",
                    measured = "QG14=${qg14?.status};QG15=${qg15?.status};effectiveDropped=$effectiveDropped",
                    threshold = "QG14 PASS + QG15 PASS + dropped<=${input.maxAllowedDroppedFrames}",
                    reason = "Sustained cadence or endurance evidence did not pass."
                )
            }
        }

        fun encodeDensity(): SingularityElite100xAuthorityResult {
            if (rawMode) {
                return pass(
                    SingularityElite100xAuthority.ENCODE_DENSITY,
                    "elite100x:encode-density:raw-not-applicable",
                    measured = "rawMode=true",
                    threshold = "not applicable to RAW private master"
                )
            }
            if (input.width <= 0 || input.height <= 0 || input.fps <= 0) {
                return notTested(
                    SingularityElite100xAuthority.ENCODE_DENSITY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:encode-density:geometry-missing",
                    measured = "width=${input.width};height=${input.height};fps=${input.fps}",
                    threshold = "valid geometry and cadence",
                    reason = "Encode density cannot be measured without geometry and cadence."
                )
            }
            val measuredBitrate = input.measuredOutputBitrateBps.takeIf { it > 0 }
            if (measuredBitrate == null) {
                return notTested(
                    SingularityElite100xAuthority.ENCODE_DENSITY,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:encode-density:measured-bitrate-missing",
                    measured = "planned=${input.bitrateBps}",
                    threshold = "measuredOutputBitrateBps>0",
                    reason = "Elite 100X needs measured output bitrate, not only a planned bitrate."
                )
            }
            val density = measuredBitrate.toDouble() / input.width.toDouble() / input.height.toDouble() / input.fps.toDouble()
            val threshold = minBitsPerPixelFrame()
            return if (density >= threshold) {
                pass(
                    SingularityElite100xAuthority.ENCODE_DENSITY,
                    "elite100x:encode-density:measured",
                    measured = "bpppf=${density.fixed6()};bitrate=$measuredBitrate",
                    threshold = "bpppf>=${threshold.fixed6()}"
                )
            } else {
                fail(
                    SingularityElite100xAuthority.ENCODE_DENSITY,
                    SingularityReleaseClass.SINGULARITY_50,
                    "elite100x:encode-density:below-floor",
                    "ELITE100X_ENCODE_DENSITY_BELOW_FLOOR",
                    measured = "bpppf=${density.fixed6()};bitrate=$measuredBitrate",
                    threshold = "bpppf>=${threshold.fixed6()}",
                    reason = "Measured bitrate density is below the Elite 100X floor."
                )
            }
        }

        fun metadataIndependence(): SingularityElite100xAuthorityResult {
            val failures = buildList {
                if (!metadata.boolean("proprietaryProfilePreserved", default = true)) add("proprietaryProfilePreserved=false")
                if (!metadata.boolean("singularityStage3ProprietaryProfilePreserved", default = true)) {
                    add("singularityStage3ProprietaryProfilePreserved=false")
                }
                listOf(
                    "vendorHdrCertification",
                    "officialHdr10PlusMetadataWritten",
                    "vendorCertifiedHdr10Plus",
                    "vendorCertifiedDolbyVision",
                    "dolbyRpuWritten",
                    "externalHdrStandardSubstitution",
                    "singularityStage3VendorHdrCertificationClaimed",
                    "officialImaxCertificationClaimed",
                    "officialDolbyVisionCertificationClaimed",
                    "officialHdr10PlusCertificationClaimed",
                    "officialHdr10CertificationClaimed",
                    "metadataFalseClaimDetected"
                ).forEach { key ->
                    if (metadata.boolean(key)) add("$key=true")
                }
            }
            return if (failures.isEmpty()) {
                pass(
                    SingularityElite100xAuthority.METADATA_INDEPENDENCE,
                    "elite100x:metadata:vendor-independence",
                    measured = "vendorClaims=false;proprietaryProfilePreserved=true",
                    threshold = "no external certification substitution"
                )
            } else {
                fail(
                    SingularityElite100xAuthority.METADATA_INDEPENDENCE,
                    SingularityReleaseClass.NO_RELEASE,
                    "elite100x:metadata:${failures.joinToString(separator = ";")}",
                    "ELITE100X_METADATA_INDEPENDENCE_FAILED",
                    measured = failures.joinToString(separator = ";"),
                    threshold = "vendor claims false and proprietary profile preserved",
                    reason = "Elite 100X cannot be claimed with vendor substitution or false certification metadata."
                )
            }
        }

        private fun requireImplementedRecord(
            authority: SingularityElite100xAuthority,
            record: SingularityStage5EngineRecord,
            threshold: String,
            reason: String
        ): SingularityElite100xAuthorityResult? =
            if (record.status == SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED) {
                null
            } else {
                notTested(
                    authority,
                    SingularityReleaseClass.SINGULARITY_75,
                    "elite100x:v20:${record.engineId.name.lowercase(Locale.US)}:not-evidenced",
                    measured = "${record.engineId.name}=${record.status.name};confidence=${record.confidence.fixed6()}",
                    threshold = threshold,
                    reason = reason
                )
            }

        private fun v20Metric(
            record: SingularityStage5EngineRecord,
            metricName: String,
            vararg metadataKeys: String
        ): Double? =
            record.measuredMetrics[metricName]
                ?.takeIf { it.isNormalizedMetric() }
                ?: metadataKeys.asSequence()
                    .mapNotNull { metadata.normalizedDouble(it) }
                    .firstOrNull()

        private fun missingMetrics(vararg values: Pair<String, Double?>): List<String> =
            values.mapNotNull { (name, value) -> name.takeIf { value == null } }

        private fun trueFlags(vararg keys: String): List<String> =
            keys.filter { metadata.boolean(it) }

        private fun stage5UnsafeReasons(vararg tokens: String): List<String> =
            metadata.clean("singularityStage5V20UnsafeReasons")
                .orEmpty()
                .split(';')
                .map(String::trim)
                .filter(String::isNotBlank)
                .filter { reason -> tokens.any { token -> reason.contains(token, ignoreCase = true) } }

        private fun metricsText(vararg values: Pair<String, Double>): String =
            values.joinToString(separator = ";") { (name, value) ->
                "$name=${value.fixed6()}"
            }

        private fun minBitsPerPixelFrame(): Double =
            when {
                input.codec.equals("hevc", ignoreCase = true) && input.encodedBitDepth >= 10 -> 0.180
                input.codec.equals("av1", ignoreCase = true) && input.encodedBitDepth >= 10 -> 0.120
                input.codec.equals("h264", ignoreCase = true) -> 0.250
                else -> 0.180
            }

        private fun pass(
            authority: SingularityElite100xAuthority,
            evidence: String,
            measured: String,
            threshold: String
        ): SingularityElite100xAuthorityResult =
            SingularityElite100xAuthorityResult(
                authority = authority,
                status = SingularityElite100xStatus.PASS,
                releaseCap = SingularityReleaseClass.SINGULARITY_100000,
                evidenceReference = evidence,
                measuredValue = measured,
                threshold = threshold
            )

        private fun warn(
            authority: SingularityElite100xAuthority,
            cap: SingularityReleaseClass,
            evidence: String,
            measured: String,
            threshold: String,
            reason: String
        ): SingularityElite100xAuthorityResult =
            SingularityElite100xAuthorityResult(
                authority = authority,
                status = SingularityElite100xStatus.WARN,
                releaseCap = cap,
                evidenceReference = evidence,
                measuredValue = measured,
                threshold = threshold,
                reason = reason
            )

        private fun notTested(
            authority: SingularityElite100xAuthority,
            cap: SingularityReleaseClass,
            evidence: String,
            measured: String,
            threshold: String,
            reason: String
        ): SingularityElite100xAuthorityResult =
            SingularityElite100xAuthorityResult(
                authority = authority,
                status = SingularityElite100xStatus.NOT_TESTED,
                releaseCap = cap,
                evidenceReference = evidence,
                measuredValue = measured,
                threshold = threshold,
                reason = reason
            )

        private fun fail(
            authority: SingularityElite100xAuthority,
            cap: SingularityReleaseClass,
            evidence: String,
            code: String,
            measured: String,
            threshold: String,
            reason: String
        ): SingularityElite100xAuthorityResult =
            SingularityElite100xAuthorityResult(
                authority = authority,
                status = SingularityElite100xStatus.FAIL,
                releaseCap = cap,
                evidenceReference = evidence,
                measuredValue = measured,
                threshold = threshold,
                blockingCode = code,
                reason = reason
            )
    }

    private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
    const val FOUNDATION_THRESHOLD_ASSET_SHA256 =
        "C2AF2ED33C919F4CF57E0280A0BFEF3F0A3EC4E9E1496274157BA6EFDF20A6A9"

    private const val V20_MIN_ZERO_GRAIN_CLARITY_SCORE = 0.82
    private const val V20_MAX_GRAIN_RESIDUAL = 0.08
    private const val V20_MIN_DENOISE_SAFETY_SCORE = 0.80
    private const val V20_ZERO_GRAIN_THRESHOLD_TEXT =
        "zeroGrainClarityScore>=0.820000;grainResidualScore<=0.080000;denoiseSafetyScore>=0.800000"
    private const val V20_MIN_MICRODETAIL_HONESTY_SCORE = 0.84
    private const val V20_MIN_SHARPNESS_SAFETY_SCORE = 0.82
    private const val V20_MAX_EDGE_OVERSHOOT_PERCENT = 0.035
    private const val V20_MIN_FINE_TEXTURE_RETENTION = 0.78
    private const val V20_MICRODETAIL_THRESHOLD_TEXT =
        "microdetailHonestyScore>=0.840000;sharpnessSafetyScore>=0.820000;" +
            "edgeOvershootPercent<=0.035000;fineTextureRetention>=0.780000"
    private const val V20_MIN_ANTI_MOTION_BLUR_SCORE = 0.80
    private const val V20_MAX_MOTION_BLUR_RESIDUAL = 0.10
    private const val V20_MAX_TEMPORAL_SHIMMER_PERCENT = 0.030
    private const val V20_ANTI_MOTION_BLUR_THRESHOLD_TEXT =
        "antiMotionBlurScore>=0.800000;motionBlurResidual<=0.100000;temporalShimmerPercent<=0.030000"

    private const val FRAME_LINEAGE_MIN_FRAMES = 60L
    private const val FRAME_LINEAGE_MIN_DURATION_MS = 2_000L
    private const val FRAME_LINEAGE_MIN_MATCH_RATIO = 0.995
    private const val FRAME_LINEAGE_MAX_PREVIEW_ENCODER_FRAME_DIFFERENCE = 1L
    private const val FRAME_LINEAGE_THRESHOLD_TEXT =
        "certified=true;frames>=60;durationMs>=2000;matchRatio>=0.995;" +
            "staleGeneration=0;nonMonotonic=0;duplicateEncoder=0;" +
            "endpointOmissions=0;previewEncoderFrameDifference<=1"

    private data class FrameLineageEvidence(
        val certified: Boolean?,
        val frames: Long?,
        val durationMs: Long?,
        val matchRatio: Double?,
        val staleGenerationCount: Long?,
        val nonMonotonicTimestampCount: Long?,
        val duplicateEncoderSubmissionCount: Long?,
        val endpointOmissionCount: Long?,
        val previewEncoderFrameDifference: Long?,
        val missingKeys: List<String>,
        val invalidKeys: List<String>,
        val falseClaimDetected: Boolean
    ) {
        fun measuredSummary(): String =
            "certified=$certified;" +
                "frames=$frames;" +
                "durationMs=$durationMs;" +
                "matchRatio=$matchRatio;" +
                "staleGeneration=$staleGenerationCount;" +
                "nonMonotonic=$nonMonotonicTimestampCount;" +
                "duplicateEncoder=$duplicateEncoderSubmissionCount;" +
                "endpointOmissions=$endpointOmissionCount;" +
                "previewEncoderFrameDifference=$previewEncoderFrameDifference"

        fun requirementFailures(): List<String> = buildList {
            if (certified != true) add("certified=$certified")
            if ((frames ?: Long.MIN_VALUE) < FRAME_LINEAGE_MIN_FRAMES) {
                add("frames=$frames<${FRAME_LINEAGE_MIN_FRAMES}")
            }
            if ((durationMs ?: Long.MIN_VALUE) < FRAME_LINEAGE_MIN_DURATION_MS) {
                add("durationMs=$durationMs<${FRAME_LINEAGE_MIN_DURATION_MS}")
            }
            if ((matchRatio ?: Double.NEGATIVE_INFINITY) < FRAME_LINEAGE_MIN_MATCH_RATIO) {
                add("matchRatio=$matchRatio<${FRAME_LINEAGE_MIN_MATCH_RATIO}")
            }
            if (staleGenerationCount != 0L) add("staleGeneration=$staleGenerationCount")
            if (nonMonotonicTimestampCount != 0L) add("nonMonotonic=$nonMonotonicTimestampCount")
            if (duplicateEncoderSubmissionCount != 0L) add("duplicateEncoder=$duplicateEncoderSubmissionCount")
            if (endpointOmissionCount != 0L) add("endpointOmissions=$endpointOmissionCount")
            if ((previewEncoderFrameDifference ?: Long.MAX_VALUE) > FRAME_LINEAGE_MAX_PREVIEW_ENCODER_FRAME_DIFFERENCE) {
                add("previewEncoderFrameDifference=$previewEncoderFrameDifference")
            }
        }

        companion object {
            fun from(metadata: Map<String, String>): FrameLineageEvidence {
                val missing = mutableListOf<String>()
                val invalid = mutableListOf<String>()
                val certified = metadata.booleanValue("camera2FrameLineageCertified", missing, invalid)
                val frames = metadata.longValue(
                    "camera2FrameLineageFrameCount",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageFrames")
                )
                val durationMs = metadata.longValue(
                    "camera2FrameLineageDurationMs",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageWindowDurationMs")
                )
                val matchRatio = metadata.doubleValue("camera2FrameLineageMatchRatio", missing, invalid)
                val staleGenerationCount = metadata.longValue(
                    "camera2FrameLineageStaleGenerationCount",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageStaleGenerationMatches")
                )
                val nonMonotonicTimestampCount = metadata.longValue(
                    "camera2FrameLineageNonMonotonicTimestampCount",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageNonmonotonicTimestampCount")
                )
                val duplicateEncoderSubmissionCount = metadata.longValue(
                    "camera2FrameLineageDuplicateEncoderSubmissionCount",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageDuplicateEncoderCount")
                )
                val endpointOmissionCount = metadata.longValue(
                    "camera2FrameLineageEndpointOmissionCount",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineageEndpointOmissions")
                )
                val previewEncoderFrameDifference = metadata.longValue(
                    "camera2FrameLineagePreviewEncoderFrameDifference",
                    missing,
                    invalid,
                    aliases = listOf("camera2FrameLineagePreviewRecordingFrameDifference")
                )
                return FrameLineageEvidence(
                    certified = certified,
                    frames = frames,
                    durationMs = durationMs,
                    matchRatio = matchRatio,
                    staleGenerationCount = staleGenerationCount,
                    nonMonotonicTimestampCount = nonMonotonicTimestampCount,
                    duplicateEncoderSubmissionCount = duplicateEncoderSubmissionCount,
                    endpointOmissionCount = endpointOmissionCount,
                    previewEncoderFrameDifference = previewEncoderFrameDifference,
                    missingKeys = missing.distinct(),
                    invalidKeys = invalid.distinct(),
                    falseClaimDetected =
                        metadata.boolean("camera2FrameLineageFalseClaimDetected") ||
                            metadata.boolean("metadataFalseClaimDetected")
                )
            }
        }
    }
}

private fun List<SingularityElite100xAuthorityResult>.statusSummary(): String =
    SingularityElite100xStatus.entries.joinToString(separator = " ") { status ->
        "${status.name}=${count { it.status == status }}"
    }

private fun List<SingularityQualityGateResult>.qualityGateStatusSummary(): String =
    SingularityQualityGateStatus.entries.joinToString(separator = " ") { status ->
        "${status.name}=${count { it.status == status }}"
    }

private fun SingularityElite100xAuthorityResult.toJsonObject(): String =
    buildString {
        append("{")
        append("\"authority\":\"").append(authority.name).append("\",")
        append("\"status\":\"").append(status.name).append("\",")
        append("\"releaseCap\":\"").append(releaseCap.label).append("\",")
        append("\"evidenceReference\":\"").append(evidenceReference.eliteJsonEscaped()).append("\",")
        append("\"measuredValue\":\"").append(measuredValue.eliteJsonEscaped()).append("\",")
        append("\"threshold\":\"").append(threshold.eliteJsonEscaped()).append("\",")
        append("\"blockingCode\":\"").append(blockingCode.eliteJsonEscaped()).append("\",")
        append("\"reason\":\"").append(reason.eliteJsonEscaped()).append("\"")
        append("}")
    }

private fun Map<String, String>.boolean(key: String, default: Boolean = false): Boolean =
    clean(key)?.toBooleanStrictOrNull() ?: default

private fun Map<String, String>.clean(key: String): String? =
    get(key)?.trim()?.takeIf { it.isNotBlank() }

private fun Map<String, String>.firstClean(key: String, aliases: List<String> = emptyList()): Pair<String, String>? =
    sequenceOf(key)
        .plus(aliases.asSequence())
        .mapNotNull { candidate -> clean(candidate)?.let { candidate to it } }
        .firstOrNull()

private fun Map<String, String>.booleanValue(
    key: String,
    missing: MutableList<String>,
    invalid: MutableList<String>,
    aliases: List<String> = emptyList()
): Boolean? {
    val (resolvedKey, value) = firstClean(key, aliases) ?: run {
        missing.add(key)
        return null
    }
    return value.toBooleanStrictOrNull() ?: run {
        invalid.add(resolvedKey)
        null
    }
}

private fun Map<String, String>.longValue(
    key: String,
    missing: MutableList<String>,
    invalid: MutableList<String>,
    aliases: List<String> = emptyList()
): Long? {
    val (resolvedKey, value) = firstClean(key, aliases) ?: run {
        missing.add(key)
        return null
    }
    val parsed = value.toLongOrNull()
    return if (parsed != null && parsed >= 0L) {
        parsed
    } else {
        invalid.add(resolvedKey)
        null
    }
}

private fun Map<String, String>.doubleValue(
    key: String,
    missing: MutableList<String>,
    invalid: MutableList<String>,
    aliases: List<String> = emptyList()
): Double? {
    val (resolvedKey, value) = firstClean(key, aliases) ?: run {
        missing.add(key)
        return null
    }
    val parsed = value.toDoubleOrNull()
    return if (parsed != null && !parsed.isNaN() && !parsed.isInfinite() && parsed in 0.0..1.0) {
        parsed
    } else {
        invalid.add(resolvedKey)
        null
    }
}

private fun Map<String, String>.normalizedDouble(key: String): Double? =
    clean(key)?.removeSuffix("%")?.trim()?.toDoubleOrNull()?.takeIf { it.isNormalizedMetric() }

private fun Double.isNormalizedMetric(): Boolean =
    !isNaN() && !isInfinite() && this in 0.0..1.0

private fun Double.fixed6(): String = String.format(Locale.US, "%.6f", this)

private fun List<String>.eliteJsonArray(): String =
    joinToString(prefix = "[", postfix = "]", separator = ",") {
        "\"${it.eliteJsonEscaped()}\""
    }

private fun String.eliteJsonEscaped(): String =
    buildString {
        this@eliteJsonEscaped.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
