package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import java.util.Locale

internal enum class OmegaCapabilityStatus {
    SUPPORTED,
    LIMITED,
    NOT_AVAILABLE
}

internal enum class OmegaCapabilityId {
    SDR_REC709_TRUTH,
    NDR_NEO_TRANSPORT,
    NIVUKX_NEO_TRANSPORT,
    N_LOG_SDR_TRUTH,
    RAW_SENSOR_TRUTH,
    NPV10_PRIVATE_MASTER,
    NPV10_NATIVE_P010_MASTER,
    VULKAN_RECORDING_PATH,
    MANUAL_BITRATE_TRUTH,
    FFPROBE_VALIDATION,
    MEDIASTORE_VALIDATION,
    EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
    EXTERNAL_LICENSED_RAW_CODEC,
    EXTERNAL_LICENSED_INTERMEDIATE_CODEC,
    UNPROVEN_EXTREME_BITRATE,
    UNPROVEN_EXTREME_RESOLUTION,
    UNPROVEN_FORENSIC_PROVENANCE
}

internal data class OmegaCapabilityInput(
    val mode: EliteVideoPipelineMode,
    val selectedProfile: String,
    val activeTransport: String,
    val transfer: String,
    val primaries: String,
    val matrix: String,
    val codec: String,
    val encodedBitDepth: Int,
    val internalMathBitDepth: Int,
    val sourceBitDepth: Int,
    val storageBitDepth: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val selectedResolution: String,
    val actualResolution: String,
    val rawSensorOriginal: Boolean,
    val npv10PayloadFormat: String = "",
    val npv10NativeTenBitClaimed: Boolean = false,
    val npv10MasterAvailable: Boolean = false,
    val ffprobeValidated: Boolean,
    val mediaStoreValidated: Boolean,
    val deviceLogValidated: Boolean,
    val gpuProcessingValidated: Boolean,
    val gpuProcessingPath: String = "",
    val encoderCapabilityCheck: String = "",
    val claimedFeatures: Set<OmegaCapabilityId> = emptySet(),
    /**
     * s1-f05: true when the camera silently negotiated an 8-bit (STANDARD) transport despite a
     * requested 10-bit Neo dynamic-range mode on capable hardware (see
     * [com.neoproc.neoproccamx.camera.CameraDynamicRangeCoherencePolicy]). A silent fallback can
     * never be certified as a SUPPORTED 10-bit transport, so it forces NDR / NIVUKX / N-LOG to
     * LIMITED. Default false reproduces today's gate verdict bit-for-bit.
     */
    val dynamicRangeSilentFallback: Boolean = false
)

internal data class OmegaCapabilityItem(
    val id: OmegaCapabilityId,
    val status: OmegaCapabilityStatus,
    val evidence: String
)

internal data class OmegaCapabilityFailure(
    val id: OmegaCapabilityId,
    val status: OmegaCapabilityStatus,
    val message: String
)

internal data class OmegaQualityTruthReport(
    val overallStatus: OmegaCapabilityStatus,
    val truthful: Boolean,
    val items: List<OmegaCapabilityItem>,
    val claimedFeatures: Set<OmegaCapabilityId>,
    val blockingFailures: List<OmegaCapabilityFailure>
) {
    fun statusOf(id: OmegaCapabilityId): OmegaCapabilityStatus =
        items.firstOrNull { it.id == id }?.status ?: OmegaCapabilityStatus.NOT_AVAILABLE

    fun toMetadata(): Map<String, String> {
        val supported = items.idsWithStatus(OmegaCapabilityStatus.SUPPORTED)
        val limited = items.idsWithStatus(OmegaCapabilityStatus.LIMITED)
        val unavailable = items.idsWithStatus(OmegaCapabilityStatus.NOT_AVAILABLE)
        return mapOf(
            "omegaCapabilityGeneration" to OmegaCapabilityGate.GENERATION,
            "omegaNeoOnlyGateApplied" to "true",
            "omegaExternalTechnologyBlocked" to "true",
            "omegaSovereignClaimed" to claimedFeatures.isNotEmpty().toString(),
            "omegaTruthful" to truthful.toString(),
            "omegaOverallStatus" to overallStatus.name,
            "omegaClaimedFeatures" to claimedFeatures.joinIds(),
            "omegaSupportedFeatures" to supported,
            "omegaLimitedFeatures" to limited,
            "omegaUnavailableFeatures" to unavailable,
            "omegaBlockingFailures" to blockingFailures.joinToString(separator = ",") { it.id.name },
            "omegaCapabilitySummary" to "supported=${supported.countCsv()} limited=${limited.countCsv()} " +
                "unavailable=${unavailable.countCsv()} blocking=${blockingFailures.size}"
        )
    }

    private fun List<OmegaCapabilityItem>.idsWithStatus(status: OmegaCapabilityStatus): String =
        filter { it.status == status }.map { it.id }.joinIds()
}

internal object OmegaCapabilityGate {
    const val GENERATION = "NEO_OMEGA_TRUTH_GATE_V1"

    fun evaluate(input: OmegaCapabilityInput): OmegaQualityTruthReport {
        val items = OmegaCapabilityId.entries.map { id ->
            OmegaCapabilityItem(
                id = id,
                status = statusFor(id, input),
                evidence = evidenceFor(id, input)
            )
        }
        val statusById = items.associate { it.id to it.status }
        val blockingFailures = input.claimedFeatures
            .filter { statusById[it] != OmegaCapabilityStatus.SUPPORTED }
            .map { id ->
                OmegaCapabilityFailure(
                    id = id,
                    status = statusById[id] ?: OmegaCapabilityStatus.NOT_AVAILABLE,
                    message = "Claimed Omega capability ${id.name} is not supported by Neo-only runtime evidence."
                )
            }
        val overall = when {
            blockingFailures.isNotEmpty() -> OmegaCapabilityStatus.NOT_AVAILABLE
            items.any { it.status != OmegaCapabilityStatus.SUPPORTED } -> OmegaCapabilityStatus.LIMITED
            else -> OmegaCapabilityStatus.SUPPORTED
        }
        return OmegaQualityTruthReport(
            overallStatus = overall,
            truthful = blockingFailures.isEmpty(),
            items = items,
            claimedFeatures = input.claimedFeatures,
            blockingFailures = blockingFailures
        )
    }

    private fun statusFor(
        id: OmegaCapabilityId,
        input: OmegaCapabilityInput
    ): OmegaCapabilityStatus =
        when (id) {
            OmegaCapabilityId.SDR_REC709_TRUTH -> sdrRec709Status(input)
            OmegaCapabilityId.NDR_NEO_TRANSPORT -> ndrStatus(input)
            OmegaCapabilityId.NIVUKX_NEO_TRANSPORT -> nivukxStatus(input)
            OmegaCapabilityId.N_LOG_SDR_TRUTH -> nLogStatus(input)
            OmegaCapabilityId.RAW_SENSOR_TRUTH -> rawStatus(input)
            OmegaCapabilityId.NPV10_PRIVATE_MASTER -> npv10MasterStatus(input)
            OmegaCapabilityId.NPV10_NATIVE_P010_MASTER -> npv10NativeP010Status(input)
            OmegaCapabilityId.VULKAN_RECORDING_PATH -> vulkanPathStatus(input)
            OmegaCapabilityId.MANUAL_BITRATE_TRUTH -> manualBitrateStatus(input)
            OmegaCapabilityId.FFPROBE_VALIDATION -> booleanProofStatus(input.ffprobeValidated)
            OmegaCapabilityId.MEDIASTORE_VALIDATION -> booleanProofStatus(input.mediaStoreValidated)
            OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
            OmegaCapabilityId.EXTERNAL_LICENSED_RAW_CODEC,
            OmegaCapabilityId.EXTERNAL_LICENSED_INTERMEDIATE_CODEC,
            OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE,
            OmegaCapabilityId.UNPROVEN_EXTREME_RESOLUTION,
            OmegaCapabilityId.UNPROVEN_FORENSIC_PROVENANCE -> OmegaCapabilityStatus.NOT_AVAILABLE
        }

    private fun evidenceFor(
        id: OmegaCapabilityId,
        input: OmegaCapabilityInput
    ): String =
        when (id) {
            OmegaCapabilityId.SDR_REC709_TRUTH ->
                "mode=${input.mode} transfer=${input.transfer} primaries=${input.primaries} matrix=${input.matrix}"
            OmegaCapabilityId.NDR_NEO_TRANSPORT,
            OmegaCapabilityId.NIVUKX_NEO_TRANSPORT ->
                "profile=${input.selectedProfile} transport=${input.activeTransport} bitDepth=${input.encodedBitDepth}"
            OmegaCapabilityId.N_LOG_SDR_TRUTH ->
                "profile=${input.selectedProfile} transfer=${input.transfer} bitDepth=${input.encodedBitDepth}"
            OmegaCapabilityId.RAW_SENSOR_TRUTH ->
                "rawSensorOriginal=${input.rawSensorOriginal} sourceBitDepth=${input.sourceBitDepth}"
            OmegaCapabilityId.NPV10_PRIVATE_MASTER,
            OmegaCapabilityId.NPV10_NATIVE_P010_MASTER ->
                "npv10Available=${input.npv10MasterAvailable} payload=${input.npv10PayloadFormat} " +
                    "native10=${input.npv10NativeTenBitClaimed} sourceBitDepth=${input.sourceBitDepth}"
            OmegaCapabilityId.VULKAN_RECORDING_PATH ->
                "gpuValidated=${input.gpuProcessingValidated} path=${input.gpuProcessingPath}"
            OmegaCapabilityId.MANUAL_BITRATE_TRUTH ->
                "bitrateBps=${input.bitrateBps} encoderCheck=${input.encoderCapabilityCheck}"
            OmegaCapabilityId.FFPROBE_VALIDATION -> "ffprobeValidated=${input.ffprobeValidated}"
            OmegaCapabilityId.MEDIASTORE_VALIDATION -> "mediaStoreValidated=${input.mediaStoreValidated}"
            OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
            OmegaCapabilityId.EXTERNAL_LICENSED_RAW_CODEC,
            OmegaCapabilityId.EXTERNAL_LICENSED_INTERMEDIATE_CODEC ->
                "blocked: external technology is not part of Neo-only Omega claims"
            OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE,
            OmegaCapabilityId.UNPROVEN_EXTREME_RESOLUTION,
            OmegaCapabilityId.UNPROVEN_FORENSIC_PROVENANCE ->
                "blocked: no verified Neo runtime proof for this extreme claim"
        }

    private fun sdrRec709Status(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.NORMAL && input.mode != EliteVideoPipelineMode.PRO) {
            return OmegaCapabilityStatus.NOT_AVAILABLE
        }
        return if (
            input.transfer.contains("709") &&
            input.primaries.equals("BT.709", ignoreCase = true) &&
            input.matrix.equals("BT.709", ignoreCase = true) &&
            !input.transfer.isHdrTransfer()
        ) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun ndrStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.HDR || !input.hasToken("NDR")) {
            return OmegaCapabilityStatus.NOT_AVAILABLE
        }
        return if (!input.dynamicRangeSilentFallback && input.isTenBitWideGamutNeoHdr()) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun nivukxStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.HDR || !input.hasToken("NIVUKX")) {
            return OmegaCapabilityStatus.NOT_AVAILABLE
        }
        return if (!input.dynamicRangeSilentFallback && input.isTenBitWideGamutNeoHdr()) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun nLogStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.LOG) return OmegaCapabilityStatus.NOT_AVAILABLE
        return if (
            !input.dynamicRangeSilentFallback &&
            input.encodedBitDepth >= 10 &&
            input.sourceBitDepth >= 10 &&
            input.storageBitDepth >= 10 &&
            input.primaries.equals("BT.2020", ignoreCase = true) &&
            input.hasToken("N-LOG") &&
            !input.transfer.isHdrTransfer()
        ) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun rawStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.RAW) return OmegaCapabilityStatus.NOT_AVAILABLE
        return if (input.rawSensorOriginal && input.sourceBitDepth >= 12 && input.storageBitDepth >= 12) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun npv10MasterStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.NPV10) return OmegaCapabilityStatus.NOT_AVAILABLE
        return if (input.npv10MasterAvailable) OmegaCapabilityStatus.SUPPORTED else OmegaCapabilityStatus.LIMITED
    }

    private fun npv10NativeP010Status(input: OmegaCapabilityInput): OmegaCapabilityStatus {
        if (input.mode != EliteVideoPipelineMode.NPV10) return OmegaCapabilityStatus.NOT_AVAILABLE
        return if (
            input.npv10MasterAvailable &&
            input.npv10NativeTenBitClaimed &&
            input.sourceBitDepth >= 10 &&
            input.storageBitDepth >= 10 &&
            input.npv10PayloadFormat.uppercase(Locale.US).contains("P010")
        ) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }
    }

    private fun vulkanPathStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus =
        if (
            input.gpuProcessingValidated &&
            input.gpuProcessingPath.contains("VULKAN", ignoreCase = true) &&
            !input.gpuProcessingPath.contains("FALLBACK", ignoreCase = true)
        ) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }

    private fun manualBitrateStatus(input: OmegaCapabilityInput): OmegaCapabilityStatus =
        if (input.bitrateBps > 0 && input.encoderCapabilityCheck.isNotBlank()) {
            OmegaCapabilityStatus.SUPPORTED
        } else {
            OmegaCapabilityStatus.LIMITED
        }

    private fun booleanProofStatus(validated: Boolean): OmegaCapabilityStatus =
        if (validated) OmegaCapabilityStatus.SUPPORTED else OmegaCapabilityStatus.LIMITED

    private fun OmegaCapabilityInput.isTenBitWideGamutNeoHdr(): Boolean =
        encodedBitDepth >= 10 &&
            sourceBitDepth >= 10 &&
            storageBitDepth >= 10 &&
            primaries.equals("BT.2020", ignoreCase = true) &&
            matrix.contains("2020", ignoreCase = true) &&
            transfer.isHdrTransfer()

    private fun OmegaCapabilityInput.hasToken(token: String): Boolean {
        val normalizedToken = token.normalizedToken()
        return selectedProfile.normalizedToken().contains(normalizedToken) ||
            activeTransport.normalizedToken().contains(normalizedToken) ||
            transfer.normalizedToken().contains(normalizedToken)
    }

    private fun String.isHdrTransfer(): Boolean =
        equals("ST2084/PQ", ignoreCase = true) ||
            equals("HLG", ignoreCase = true) ||
            contains("PQ", ignoreCase = true)

    private fun String.normalizedToken(): String =
        uppercase(Locale.US).filter { it.isLetterOrDigit() }
}

private fun Set<OmegaCapabilityId>.joinIds(): String =
    map { it.name }.sorted().joinToString(separator = ",")

private fun List<OmegaCapabilityId>.joinIds(): String =
    map { it.name }.sorted().joinToString(separator = ",")

private fun String.countCsv(): Int =
    if (isBlank()) 0 else split(',').size
