package com.neoproc.neoproccamx.quality

internal enum class SingularityStage6ValidationStatus {
    PASS,
    WARN,
    FAIL,
    NOT_APPLICABLE,
    NOT_TESTED
}

internal enum class SingularityStage6Authority {
    DEVICE_IDENTITY,
    DEVICE_CAPABILITIES,
    FUNCTIONAL_CONTRACT,
    MODE_MATRIX,
    SCENE_MATRIX,
    CHART_MATRIX,
    ENDURANCE,
    PERFORMANCE,
    LARGE_SCREEN_REVIEW,
    UI_STATUS,
    NATIVE_PATH
}

internal data class SingularityStage6AuthorityResult(
    val authority: SingularityStage6Authority,
    val status: SingularityStage6ValidationStatus,
    val evidenceReference: String,
    val missingRequirements: List<String> = emptyList(),
    val failureCode: String = ""
) {
    init {
        require(evidenceReference.isNotBlank()) { "Stage 6 evidence reference is required" }
        if (status == SingularityStage6ValidationStatus.FAIL) {
            require(failureCode.isNotBlank()) { "Failed Stage 6 authority requires a failure code" }
        }
    }
}

internal data class SingularityStage6DeviceMatrixInput(
    val recordingId: String,
    val deviceFingerprint: String,
    val buildId: String,
    val deviceClass: String,
    val calibrationId: String,
    val cameraId: String,
    val outputMode: String,
    val modeId: String,
    val resolution: String,
    val fps: Int,
    val thresholdSetId: String,
    val thresholdSetHash: String,
    val testedCombinationId: String,
    val testedModes: Set<String>,
    val unavailableModes: Set<String>,
    val completedFunctionalChecks: Set<String>,
    val completedScenes: Set<String>,
    val completedCharts: Set<String>,
    val completedEnduranceScenarios: Set<String>,
    val completedReviewSurfaces: Set<String>,
    val deviceLabStatus: SingularityStage6ValidationStatus,
    val deviceCapabilitiesStatus: SingularityStage6ValidationStatus,
    val functionalStatus: SingularityStage6ValidationStatus,
    val modeMatrixStatus: SingularityStage6ValidationStatus,
    val sceneMatrixStatus: SingularityStage6ValidationStatus,
    val chartMatrixStatus: SingularityStage6ValidationStatus,
    val enduranceStatus: SingularityStage6ValidationStatus,
    val performanceStatus: SingularityStage6ValidationStatus,
    val largeScreenReviewStatus: SingularityStage6ValidationStatus,
    val uiStatus: SingularityStage6ValidationStatus,
    val nativePathStatus: SingularityStage6ValidationStatus,
    val artifactReferences: Map<String, String> = emptyMap()
)

internal data class SingularityStage6DeviceMatrixBundle(
    val recordingId: String,
    val currentCombinationId: String,
    val authorityResults: List<SingularityStage6AuthorityResult>,
    val releaseCap: SingularityReleaseClass,
    val deviceLabValidated: Boolean,
    val testedDeviceModeCombination: Boolean,
    val largeScreenReviewPassed: Boolean,
    val enduranceValidationPassed: Boolean,
    val nativePathValidated: Boolean,
    val missingRequirements: List<String>,
    val artifactReferences: Map<String, String>
) {
    init {
        require(authorityResults.map { it.authority }.toSet() == SingularityStage6Authority.entries.toSet()) {
            "Stage 6 must report every production device-matrix authority"
        }
        require(authorityResults.size == SingularityStage6Authority.entries.size) {
            "Stage 6 authorities must be unique"
        }
    }

    val overallStatus: SingularityStage6ValidationStatus
        get() = when {
            authorityResults.any { it.status == SingularityStage6ValidationStatus.FAIL } ->
                SingularityStage6ValidationStatus.FAIL
            authorityResults.any { it.status == SingularityStage6ValidationStatus.NOT_TESTED } ->
                SingularityStage6ValidationStatus.NOT_TESTED
            authorityResults.any { it.status == SingularityStage6ValidationStatus.NOT_APPLICABLE } ->
                SingularityStage6ValidationStatus.NOT_APPLICABLE
            authorityResults.any { it.status == SingularityStage6ValidationStatus.WARN } ->
                SingularityStage6ValidationStatus.WARN
            else -> SingularityStage6ValidationStatus.PASS
        }

    fun toMetadata(prefix: String = "singularityStage6"): Map<String, String> = buildMap {
        put("${prefix}Version", SingularityStage6DeviceMatrixPolicy.VERSION)
        put("${prefix}ReportVersion", REPORT_VERSION)
        put("${prefix}RecordingId", recordingId)
        put("${prefix}OverallStatus", overallStatus.name)
        put("${prefix}ReleaseCap", releaseCap.label)
        put("${prefix}CurrentCombinationId", currentCombinationId)
        put("${prefix}DeviceLabValidated", deviceLabValidated.toString())
        put("${prefix}TestedDeviceModeCombination", testedDeviceModeCombination.toString())
        put("${prefix}LargeScreenReviewPassed", largeScreenReviewPassed.toString())
        put("${prefix}EnduranceValidationPassed", enduranceValidationPassed.toString())
        put("${prefix}NativePathValidated", nativePathValidated.toString())
        put("${prefix}AuthorityCount", authorityResults.size.toString())
        put(
            "${prefix}AuthoritySummary",
            SingularityStage6ValidationStatus.entries.joinToString(separator = " ") { status ->
                "${status.name}=${authorityResults.count { it.status == status }}"
            }
        )
        put("${prefix}MissingRequirements", missingRequirements.joinToString(separator = ","))
        put("${prefix}ReportJson", toJsonText())
        artifactReferences.forEach { (key, value) -> put("${prefix}Artifact.$key", value) }
        authorityResults.forEach { result ->
            put(
                "${prefix}.${result.authority.name}",
                buildString {
                    append(result.status.name)
                    append("|evidence=").append(result.evidenceReference.replace('|', '/'))
                    if (result.failureCode.isNotBlank()) append("|failure=").append(result.failureCode)
                    if (result.missingRequirements.isNotEmpty()) {
                        append("|missing=").append(result.missingRequirements.joinToString(separator = ";"))
                    }
                }
            )
        }
    }

    fun toJsonText(): String = buildString {
        append("{")
        append("\"formatVersion\":\"").append(REPORT_VERSION).append("\",")
        append("\"policyVersion\":\"").append(SingularityStage6DeviceMatrixPolicy.VERSION).append("\",")
        append("\"recordingId\":\"").append(recordingId.stage6JsonEscaped()).append("\",")
        append("\"overallStatus\":\"").append(overallStatus.name).append("\",")
        append("\"releaseCap\":\"").append(releaseCap.label).append("\",")
        append("\"currentCombinationId\":\"").append(currentCombinationId.stage6JsonEscaped()).append("\",")
        append("\"deviceLabValidated\":").append(deviceLabValidated).append(",")
        append("\"testedDeviceModeCombination\":").append(testedDeviceModeCombination).append(",")
        append("\"largeScreenReviewPassed\":").append(largeScreenReviewPassed).append(",")
        append("\"enduranceValidationPassed\":").append(enduranceValidationPassed).append(",")
        append("\"nativePathValidated\":").append(nativePathValidated).append(",")
        append("\"missingRequirements\":").append(missingRequirements.stage6JsonArray()).append(",")
        append("\"artifactReferences\":").append(artifactReferences.stage6JsonObject()).append(",")
        append("\"authorities\":[")
        append(authorityResults.joinToString(separator = ",") { result ->
            buildString {
                append("{")
                append("\"authority\":\"").append(result.authority.name).append("\",")
                append("\"status\":\"").append(result.status.name).append("\",")
                append("\"evidenceReference\":\"").append(result.evidenceReference.stage6JsonEscaped()).append("\",")
                append("\"failureCode\":\"").append(result.failureCode.stage6JsonEscaped()).append("\",")
                append("\"missingRequirements\":").append(result.missingRequirements.stage6JsonArray())
                append("}")
            }
        })
        append("]}")
    }

    companion object {
        const val REPORT_VERSION = "SINGULARITY_STAGE6_DEVICE_MATRIX_REPORT_1"
    }
}

internal object SingularityStage6DeviceMatrixPolicy {
    const val VERSION = "SINGULARITY_STAGE6_PRODUCTION_DEVICE_MATRIX_V1"

    val REQUIRED_MODES: Set<String> = linkedSetOf(
        "1080p25",
        "1080p60",
        "4K24",
        "4K25",
        "4K60",
        "NDR10X-SINGULARITY",
        "NLOG-SINGULARITY",
        "NRAW-SINGULARITY",
        "NPV10-SINGULARITY-MASTER"
    )

    val REQUIRED_SCENES: Set<String> = linkedSetOf(
        "daylight face",
        "backlit face",
        "night face",
        "night street",
        "neon sign/street",
        "bright window interior",
        "black fabric",
        "white fabric",
        "hair detail",
        "printed text",
        "foliage movement",
        "architecture lines",
        "slow pan",
        "handheld walking",
        "fast crossing subject",
        "mixed-light skin",
        "sky gradient",
        "metal reflection"
    )

    val REQUIRED_CHARTS: Set<String> = linkedSetOf(
        "resolution chart",
        "slanted edge",
        "color checker",
        "neutral/gray ramp",
        "step wedge",
        "skin-tone reference",
        "noise target",
        "moving texture target"
    )

    val REQUIRED_ENDURANCE: Set<String> = linkedSetOf(
        "5-minute preview",
        "10-minute 1080p recording",
        "10-minute 4K recording where supported",
        "20-minute thermal stress",
        "20 repeated start/stop cycles",
        "background/foreground transition",
        "low-battery session",
        "storage-pressure session",
        "near-full and full-storage finalization"
    )

    val REQUIRED_REVIEW_SURFACES: Set<String> = linkedSetOf(
        "phone",
        "tablet/laptop",
        "calibrated 4K display",
        "200-percent crop",
        "normal-speed motion",
        "frame-by-frame motion"
    )

    val REQUIRED_FUNCTIONAL_CHECKS: Set<String> = linkedSetOf(
        "permission",
        "open-close",
        "disconnect-recovery",
        "background-foreground",
        "preview-start-stop",
        "manual-exposure-iso-focus-wb",
        "stream-combination",
        "logical-physical-camera",
        "unsupported-mode-hiding",
        "vulkan-ahb-import-sync-dispatch",
        "neural-load-accelerator-fallback",
        "codec-recording-cadence",
        "muxer-finalization-playback",
        "npv10-master-audit",
        "smf-atomic-finalization-recovery",
        "thermal-memory-battery-storage"
    )

    fun evaluate(input: SingularityStage6DeviceMatrixInput): SingularityStage6DeviceMatrixBundle {
        val currentCombinationId = combinationId(
            deviceFingerprint = input.deviceFingerprint,
            cameraId = input.cameraId,
            outputMode = input.outputMode,
            resolution = input.resolution,
            fps = input.fps
        )
        val identityMissing = buildList {
            if (input.deviceFingerprint.isBlank()) add("deviceFingerprint")
            if (input.buildId.isBlank()) add("buildId")
            if (input.deviceClass.isBlank()) add("deviceClass")
            if (input.calibrationId.isBlank()) add("calibrationId")
            if (input.cameraId.isBlank()) add("cameraId")
            if (input.thresholdSetId.isBlank()) add("thresholdSetId")
            if (input.thresholdSetHash.isBlank()) add("thresholdSetHash")
        }
        val accountedModes = input.testedModes.normalized() + input.unavailableModes.normalized()
        val missingModes = REQUIRED_MODES.normalized() - accountedModes
        val currentModeAccounted = input.modeId.normalizedToken() in accountedModes
        val currentCombinationTested =
            currentCombinationId.isNotBlank() &&
                input.testedCombinationId.trim() == currentCombinationId
        val missingFunctional = REQUIRED_FUNCTIONAL_CHECKS.normalized() - input.completedFunctionalChecks.normalized()
        val missingScenes = REQUIRED_SCENES.normalized() - input.completedScenes.normalized()
        val missingCharts = REQUIRED_CHARTS.normalized() - input.completedCharts.normalized()
        val missingEndurance = REQUIRED_ENDURANCE.normalized() - input.completedEnduranceScenarios.normalized()
        val missingReview = REQUIRED_REVIEW_SURFACES.normalized() - input.completedReviewSurfaces.normalized()

        val results = listOf(
            authorityResult(
                authority = SingularityStage6Authority.DEVICE_IDENTITY,
                reportedStatus = if (identityMissing.isEmpty()) {
                    SingularityStage6ValidationStatus.PASS
                } else {
                    SingularityStage6ValidationStatus.NOT_TESTED
                },
                missing = identityMissing,
                evidence = "stage6:device-identity:$currentCombinationId"
            ),
            authorityResult(
                authority = SingularityStage6Authority.DEVICE_CAPABILITIES,
                reportedStatus = input.deviceCapabilitiesStatus,
                missing = if (input.deviceCapabilitiesStatus == SingularityStage6ValidationStatus.PASS) emptyList() else listOf("validated capability snapshot"),
                evidence = input.artifactReferences["deviceCapabilities"] ?: "stage6:device-capabilities"
            ),
            coverageResult(
                authority = SingularityStage6Authority.FUNCTIONAL_CONTRACT,
                reportedStatus = input.functionalStatus,
                missing = missingFunctional.sorted(),
                evidence = input.artifactReferences["functional"] ?: "stage6:functional-contract"
            ),
            coverageResult(
                authority = SingularityStage6Authority.MODE_MATRIX,
                reportedStatus = input.modeMatrixStatus,
                missing = buildList {
                    addAll(missingModes.sorted())
                    if (!currentModeAccounted) add("currentMode:${input.modeId}")
                    if (!currentCombinationTested) add("currentCombination:$currentCombinationId")
                },
                evidence = input.artifactReferences["modeMatrix"] ?: "stage6:mode-matrix"
            ),
            coverageResult(
                authority = SingularityStage6Authority.SCENE_MATRIX,
                reportedStatus = input.sceneMatrixStatus,
                missing = missingScenes.sorted(),
                evidence = input.artifactReferences["sceneMatrix"] ?: "stage6:scene-matrix"
            ),
            coverageResult(
                authority = SingularityStage6Authority.CHART_MATRIX,
                reportedStatus = input.chartMatrixStatus,
                missing = missingCharts.sorted(),
                evidence = input.artifactReferences["chartMatrix"] ?: "stage6:chart-matrix"
            ),
            coverageResult(
                authority = SingularityStage6Authority.ENDURANCE,
                reportedStatus = input.enduranceStatus,
                missing = missingEndurance.sorted(),
                evidence = input.artifactReferences["endurance"] ?: "stage6:endurance"
            ),
            authorityResult(
                authority = SingularityStage6Authority.PERFORMANCE,
                reportedStatus = input.performanceStatus,
                missing = if (input.performanceStatus == SingularityStage6ValidationStatus.PASS) emptyList() else listOf("sustained performance report"),
                evidence = input.artifactReferences["performance"] ?: "stage6:performance"
            ),
            coverageResult(
                authority = SingularityStage6Authority.LARGE_SCREEN_REVIEW,
                reportedStatus = input.largeScreenReviewStatus,
                missing = missingReview.sorted(),
                evidence = input.artifactReferences["largeScreenReview"] ?: "stage6:large-screen-review"
            ),
            authorityResult(
                authority = SingularityStage6Authority.UI_STATUS,
                reportedStatus = input.uiStatus,
                missing = if (input.uiStatus == SingularityStage6ValidationStatus.PASS) emptyList() else listOf("quality class, transport and downgrade UI contract"),
                evidence = input.artifactReferences["uiStatus"] ?: "stage6:ui-status"
            ),
            authorityResult(
                authority = SingularityStage6Authority.NATIVE_PATH,
                reportedStatus = input.nativePathStatus,
                missing = if (input.nativePathStatus == SingularityStage6ValidationStatus.PASS) emptyList() else listOf("validated Vulkan/AHardwareBuffer/native path"),
                evidence = input.artifactReferences["nativePath"] ?: "stage6:native-path"
            )
        )

        val deviceLabValidated =
            input.deviceLabStatus == SingularityStage6ValidationStatus.PASS &&
                results.all { it.status == SingularityStage6ValidationStatus.PASS }
        val allMissing = results.flatMap { result ->
            result.missingRequirements.map { "${result.authority.name}:$it" }
        }
        val releaseCap = when {
            deviceLabValidated -> SingularityReleaseClass.SINGULARITY_100000
            results.any { it.status == SingularityStage6ValidationStatus.FAIL } ->
                SingularityReleaseClass.SINGULARITY_25
            !currentCombinationTested ||
                identityMissing.isNotEmpty() ||
                !currentModeAccounted ->
                SingularityReleaseClass.CORE_HONEST
            else -> SingularityReleaseClass.SINGULARITY_75
        }
        return SingularityStage6DeviceMatrixBundle(
            recordingId = input.recordingId,
            currentCombinationId = currentCombinationId,
            authorityResults = results,
            releaseCap = releaseCap,
            deviceLabValidated = deviceLabValidated,
            testedDeviceModeCombination = currentCombinationTested &&
                results.single { it.authority == SingularityStage6Authority.MODE_MATRIX }.status ==
                SingularityStage6ValidationStatus.PASS,
            largeScreenReviewPassed =
                results.single { it.authority == SingularityStage6Authority.LARGE_SCREEN_REVIEW }.status ==
                SingularityStage6ValidationStatus.PASS,
            enduranceValidationPassed =
                results.single { it.authority == SingularityStage6Authority.ENDURANCE }.status ==
                SingularityStage6ValidationStatus.PASS,
            nativePathValidated =
                results.single { it.authority == SingularityStage6Authority.NATIVE_PATH }.status ==
                SingularityStage6ValidationStatus.PASS,
            missingRequirements = allMissing,
            artifactReferences = input.artifactReferences
        )
    }

    fun combinationId(
        deviceFingerprint: String,
        cameraId: String,
        outputMode: String,
        resolution: String,
        fps: Int
    ): String {
        if (
            deviceFingerprint.isBlank() ||
            cameraId.isBlank() ||
            outputMode.isBlank() ||
            resolution.isBlank() ||
            fps <= 0
        ) {
            return ""
        }
        return listOf(deviceFingerprint, cameraId, outputMode, resolution, "${fps}fps")
            .joinToString(separator = "::") { it.trim() }
    }

    private fun coverageResult(
        authority: SingularityStage6Authority,
        reportedStatus: SingularityStage6ValidationStatus,
        missing: List<String>,
        evidence: String
    ): SingularityStage6AuthorityResult {
        val effectiveStatus = when {
            reportedStatus == SingularityStage6ValidationStatus.FAIL ->
                SingularityStage6ValidationStatus.FAIL
            reportedStatus == SingularityStage6ValidationStatus.PASS && missing.isNotEmpty() ->
                SingularityStage6ValidationStatus.FAIL
            reportedStatus == SingularityStage6ValidationStatus.PASS ->
                SingularityStage6ValidationStatus.PASS
            reportedStatus == SingularityStage6ValidationStatus.WARN ->
                SingularityStage6ValidationStatus.WARN
            reportedStatus == SingularityStage6ValidationStatus.NOT_APPLICABLE ->
                SingularityStage6ValidationStatus.NOT_APPLICABLE
            else -> SingularityStage6ValidationStatus.NOT_TESTED
        }
        return SingularityStage6AuthorityResult(
            authority = authority,
            status = effectiveStatus,
            evidenceReference = evidence,
            missingRequirements = missing,
            failureCode = if (effectiveStatus == SingularityStage6ValidationStatus.FAIL) {
                "${authority.name}_EVIDENCE_FAILED"
            } else {
                ""
            }
        )
    }

    private fun authorityResult(
        authority: SingularityStage6Authority,
        reportedStatus: SingularityStage6ValidationStatus,
        missing: List<String>,
        evidence: String
    ): SingularityStage6AuthorityResult =
        coverageResult(authority, reportedStatus, missing, evidence)

    private fun Set<String>.normalized(): Set<String> = mapTo(linkedSetOf()) { it.normalizedToken() }

    private fun String.normalizedToken(): String = trim().lowercase()
}

private fun List<String>.stage6JsonArray(): String =
    joinToString(prefix = "[", postfix = "]", separator = ",") {
        "\"${it.stage6JsonEscaped()}\""
    }

private fun Map<String, String>.stage6JsonObject(): String =
    entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"${key.stage6JsonEscaped()}\":\"${value.stage6JsonEscaped()}\""
    }

private fun String.stage6JsonEscaped(): String =
    buildString {
        this@stage6JsonEscaped.forEach { char ->
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
