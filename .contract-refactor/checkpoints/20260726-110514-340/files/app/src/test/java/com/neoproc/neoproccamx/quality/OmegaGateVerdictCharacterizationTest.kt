package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f08 — characterization snapshot of [OmegaCapabilityGate] over a FIXED evidence matrix. This
 * pins the per-id verdict + overall + truthful for four canonical inputs (empty/unproven, proven
 * 10-bit SDR Rec.709, proven 10-bit HDR NDR, proven NPV10 native P010). Any future change that
 * loosens (or otherwise alters) a verdict changes the snapshot and trips CI. The expected strings
 * are golden-locked from a real gate run, not hand-derived.
 */
class OmegaGateVerdictCharacterizationTest {

    @Test
    fun unprovenMatrixSnapshotIsLocked() {
        assertEquals(EXPECTED_EMPTY, snapshotOf(OmegaCapabilityGate.evaluate(emptyEvidenceInput())))
    }

    @Test
    fun provenSdrRec709MatrixSnapshotIsLocked() {
        assertEquals(EXPECTED_SDR, snapshotOf(OmegaCapabilityGate.evaluate(provenSdrInput())))
    }

    @Test
    fun provenHdrNdrMatrixSnapshotIsLocked() {
        assertEquals(EXPECTED_HDR_NDR, snapshotOf(OmegaCapabilityGate.evaluate(provenHdrNdrInput())))
    }

    @Test
    fun provenNpv10NativeMatrixSnapshotIsLocked() {
        assertEquals(EXPECTED_NPV10, snapshotOf(OmegaCapabilityGate.evaluate(provenNpv10NativeInput())))
    }

    private fun snapshotOf(report: OmegaQualityTruthReport): String {
        val ids = report.items.sortedBy { it.id.name }
            .joinToString("\n") { "${it.id.name}=${it.status.name}" }
        return "$ids\n--\noverall=${report.overallStatus.name} truthful=${report.truthful}"
    }

    private companion object {
        // GOLDEN-LOCKED from a real OmegaCapabilityGate run (see test header). Do not hand-edit;
        // re-derive by running this class and pasting the actual snapshot if the gate ever changes.
        val EXPECTED_EMPTY = """
            EXTERNAL_CERTIFIED_DYNAMIC_METADATA=NOT_AVAILABLE
            EXTERNAL_LICENSED_INTERMEDIATE_CODEC=NOT_AVAILABLE
            EXTERNAL_LICENSED_RAW_CODEC=NOT_AVAILABLE
            FFPROBE_VALIDATION=LIMITED
            MANUAL_BITRATE_TRUTH=LIMITED
            MEDIASTORE_VALIDATION=LIMITED
            NDR_NEO_TRANSPORT=NOT_AVAILABLE
            NIVUKX_NEO_TRANSPORT=NOT_AVAILABLE
            NPV10_NATIVE_P010_MASTER=NOT_AVAILABLE
            NPV10_PRIVATE_MASTER=NOT_AVAILABLE
            N_LOG_SDR_TRUTH=NOT_AVAILABLE
            RAW_SENSOR_TRUTH=NOT_AVAILABLE
            SDR_REC709_TRUTH=LIMITED
            UNPROVEN_EXTREME_BITRATE=NOT_AVAILABLE
            UNPROVEN_EXTREME_RESOLUTION=NOT_AVAILABLE
            UNPROVEN_FORENSIC_PROVENANCE=NOT_AVAILABLE
            VULKAN_RECORDING_PATH=LIMITED
            --
            overall=LIMITED truthful=true
        """.trimIndent()

        val EXPECTED_SDR = """
            EXTERNAL_CERTIFIED_DYNAMIC_METADATA=NOT_AVAILABLE
            EXTERNAL_LICENSED_INTERMEDIATE_CODEC=NOT_AVAILABLE
            EXTERNAL_LICENSED_RAW_CODEC=NOT_AVAILABLE
            FFPROBE_VALIDATION=SUPPORTED
            MANUAL_BITRATE_TRUTH=SUPPORTED
            MEDIASTORE_VALIDATION=SUPPORTED
            NDR_NEO_TRANSPORT=NOT_AVAILABLE
            NIVUKX_NEO_TRANSPORT=NOT_AVAILABLE
            NPV10_NATIVE_P010_MASTER=NOT_AVAILABLE
            NPV10_PRIVATE_MASTER=NOT_AVAILABLE
            N_LOG_SDR_TRUTH=NOT_AVAILABLE
            RAW_SENSOR_TRUTH=NOT_AVAILABLE
            SDR_REC709_TRUTH=SUPPORTED
            UNPROVEN_EXTREME_BITRATE=NOT_AVAILABLE
            UNPROVEN_EXTREME_RESOLUTION=NOT_AVAILABLE
            UNPROVEN_FORENSIC_PROVENANCE=NOT_AVAILABLE
            VULKAN_RECORDING_PATH=LIMITED
            --
            overall=LIMITED truthful=true
        """.trimIndent()

        val EXPECTED_HDR_NDR = """
            EXTERNAL_CERTIFIED_DYNAMIC_METADATA=NOT_AVAILABLE
            EXTERNAL_LICENSED_INTERMEDIATE_CODEC=NOT_AVAILABLE
            EXTERNAL_LICENSED_RAW_CODEC=NOT_AVAILABLE
            FFPROBE_VALIDATION=SUPPORTED
            MANUAL_BITRATE_TRUTH=LIMITED
            MEDIASTORE_VALIDATION=SUPPORTED
            NDR_NEO_TRANSPORT=SUPPORTED
            NIVUKX_NEO_TRANSPORT=NOT_AVAILABLE
            NPV10_NATIVE_P010_MASTER=NOT_AVAILABLE
            NPV10_PRIVATE_MASTER=NOT_AVAILABLE
            N_LOG_SDR_TRUTH=NOT_AVAILABLE
            RAW_SENSOR_TRUTH=NOT_AVAILABLE
            SDR_REC709_TRUTH=NOT_AVAILABLE
            UNPROVEN_EXTREME_BITRATE=NOT_AVAILABLE
            UNPROVEN_EXTREME_RESOLUTION=NOT_AVAILABLE
            UNPROVEN_FORENSIC_PROVENANCE=NOT_AVAILABLE
            VULKAN_RECORDING_PATH=LIMITED
            --
            overall=LIMITED truthful=true
        """.trimIndent()

        val EXPECTED_NPV10 = """
            EXTERNAL_CERTIFIED_DYNAMIC_METADATA=NOT_AVAILABLE
            EXTERNAL_LICENSED_INTERMEDIATE_CODEC=NOT_AVAILABLE
            EXTERNAL_LICENSED_RAW_CODEC=NOT_AVAILABLE
            FFPROBE_VALIDATION=SUPPORTED
            MANUAL_BITRATE_TRUTH=SUPPORTED
            MEDIASTORE_VALIDATION=SUPPORTED
            NDR_NEO_TRANSPORT=NOT_AVAILABLE
            NIVUKX_NEO_TRANSPORT=NOT_AVAILABLE
            NPV10_NATIVE_P010_MASTER=SUPPORTED
            NPV10_PRIVATE_MASTER=SUPPORTED
            N_LOG_SDR_TRUTH=NOT_AVAILABLE
            RAW_SENSOR_TRUTH=NOT_AVAILABLE
            SDR_REC709_TRUTH=NOT_AVAILABLE
            UNPROVEN_EXTREME_BITRATE=NOT_AVAILABLE
            UNPROVEN_EXTREME_RESOLUTION=NOT_AVAILABLE
            UNPROVEN_FORENSIC_PROVENANCE=NOT_AVAILABLE
            VULKAN_RECORDING_PATH=LIMITED
            --
            overall=LIMITED truthful=true
        """.trimIndent()
    }

    private fun emptyEvidenceInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.NORMAL,
        selectedProfile = "",
        activeTransport = "",
        transfer = "",
        primaries = "",
        matrix = "",
        codec = "",
        encodedBitDepth = 0,
        internalMathBitDepth = 0,
        sourceBitDepth = 0,
        storageBitDepth = 0,
        width = 0,
        height = 0,
        fps = 0,
        bitrateBps = 0,
        selectedResolution = "",
        actualResolution = "",
        rawSensorOriginal = false,
        npv10PayloadFormat = "",
        npv10NativeTenBitClaimed = false,
        npv10MasterAvailable = false,
        ffprobeValidated = false,
        mediaStoreValidated = false,
        deviceLogValidated = false,
        gpuProcessingValidated = false,
        gpuProcessingPath = "",
        encoderCapabilityCheck = "",
        claimedFeatures = emptySet()
    )

    private fun provenSdrInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.NORMAL,
        selectedProfile = "Original SDR",
        activeTransport = "SDR_REC709_MAIN10",
        transfer = "BT.709/OETF",
        primaries = "BT.709",
        matrix = "BT.709",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = 10,
        storageBitDepth = 10,
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateBps = 67_000_000,
        selectedResolution = "1920x1080",
        actualResolution = "1920x1080",
        rawSensorOriginal = false,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK",
        encoderCapabilityCheck = "appliedBitrateWithinEncoderBound=true",
        claimedFeatures = emptySet()
    )

    private fun provenHdrNdrInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.HDR,
        selectedProfile = "NDR10+",
        activeTransport = "NDR10_PLUS_PQ_MAIN10",
        transfer = "ST2084/PQ",
        primaries = "BT.2020",
        matrix = "BT.2020",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = 10,
        storageBitDepth = 10,
        width = 3840,
        height = 2160,
        fps = 30,
        bitrateBps = 120_000_000,
        selectedResolution = "3840x2160",
        actualResolution = "3840x2160",
        rawSensorOriginal = false,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        claimedFeatures = emptySet(),
        dynamicRangeSilentFallback = false
    )

    private fun provenNpv10NativeInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.NPV10,
        selectedProfile = "NPV10 Native",
        activeTransport = "NPV10_P010_PRIVATE",
        transfer = "ST2084/PQ",
        primaries = "BT.2020",
        matrix = "BT.2020",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = 10,
        storageBitDepth = 10,
        width = 3840,
        height = 2160,
        fps = 30,
        bitrateBps = 120_000_000,
        selectedResolution = "3840x2160",
        actualResolution = "3840x2160",
        rawSensorOriginal = false,
        npv10PayloadFormat = "NEO_P010_PRIVATE",
        npv10NativeTenBitClaimed = true,
        npv10MasterAvailable = true,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK",
        encoderCapabilityCheck = "appliedBitrateWithinEncoderBound=true",
        claimedFeatures = emptySet()
    )
}
