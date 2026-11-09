package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmegaCapabilityGateTest {

    @Test
    fun unsupportedCodexFeaturesAreReportedAsUnavailableNotSupported() {
        val report = OmegaCapabilityGate.evaluate(baseInput())

        assertEquals(OmegaCapabilityStatus.LIMITED, report.overallStatus)
        assertTrue(report.truthful)
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA))
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.EXTERNAL_LICENSED_RAW_CODEC))
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.EXTERNAL_LICENSED_INTERMEDIATE_CODEC))
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE))
        assertTrue(report.blockingFailures.isEmpty())
        assertTrue(report.toMetadata().getValue("omegaUnavailableFeatures").contains("EXTERNAL_CERTIFIED_DYNAMIC_METADATA"))
    }

    @Test
    fun claimedUnsupportedExternalAndExtremeBitrateBecomeBlockingFailures() {
        val report = OmegaCapabilityGate.evaluate(
            baseInput(
                claimedFeatures = setOf(
                    OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
                    OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE
                )
            )
        )

        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA))
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.statusOf(OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE))
        assertFalse(report.truthful)
        assertEquals(
            listOf(OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA, OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE),
            report.blockingFailures.map { it.id }
        )
    }

    @Test
    fun rec709TenBitSdrIsSupportedOnlyWhenProbeAndMediaStoreEvidenceMatch() {
        val report = OmegaCapabilityGate.evaluate(
            baseInput(
                claimedFeatures = setOf(
                    OmegaCapabilityId.SDR_REC709_TRUTH,
                    OmegaCapabilityId.FFPROBE_VALIDATION,
                    OmegaCapabilityId.MEDIASTORE_VALIDATION
                )
            )
        )

        assertEquals(OmegaCapabilityStatus.SUPPORTED, report.statusOf(OmegaCapabilityId.SDR_REC709_TRUTH))
        assertEquals(OmegaCapabilityStatus.SUPPORTED, report.statusOf(OmegaCapabilityId.FFPROBE_VALIDATION))
        assertEquals(OmegaCapabilityStatus.SUPPORTED, report.statusOf(OmegaCapabilityId.MEDIASTORE_VALIDATION))
        assertTrue(report.truthful)
    }

    @Test
    fun npv10EightBitExpandedFallbackIsLimitedAndNotNativeP010() {
        val report = OmegaCapabilityGate.evaluate(
            baseInput(
                mode = EliteVideoPipelineMode.NPV10,
                sourceBitDepth = 8,
                storageBitDepth = 10,
                npv10PayloadFormat = "YUV420_8BIT_EXPANDED",
                npv10NativeTenBitClaimed = false,
                npv10MasterAvailable = true,
                claimedFeatures = setOf(OmegaCapabilityId.NPV10_PRIVATE_MASTER)
            )
        )

        assertEquals(OmegaCapabilityStatus.SUPPORTED, report.statusOf(OmegaCapabilityId.NPV10_PRIVATE_MASTER))
        assertEquals(OmegaCapabilityStatus.LIMITED, report.statusOf(OmegaCapabilityId.NPV10_NATIVE_P010_MASTER))
        assertTrue(report.truthful)
    }

    private fun baseInput(
        mode: EliteVideoPipelineMode = EliteVideoPipelineMode.NORMAL,
        claimedFeatures: Set<OmegaCapabilityId> = emptySet(),
        sourceBitDepth: Int = 10,
        storageBitDepth: Int = 10,
        npv10PayloadFormat: String = "",
        npv10NativeTenBitClaimed: Boolean = false,
        npv10MasterAvailable: Boolean = false
    ) = OmegaCapabilityInput(
        mode = mode,
        selectedProfile = "Original SDR",
        activeTransport = "SDR_REC709_MAIN10",
        transfer = "BT.709/OETF",
        primaries = "BT.709",
        matrix = "BT.709",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = sourceBitDepth,
        storageBitDepth = storageBitDepth,
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateBps = 67_000_000,
        selectedResolution = "1920x1080",
        actualResolution = "1920x1080",
        rawSensorOriginal = false,
        npv10PayloadFormat = npv10PayloadFormat,
        npv10NativeTenBitClaimed = npv10NativeTenBitClaimed,
        npv10MasterAvailable = npv10MasterAvailable,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK",
        encoderCapabilityCheck = "appliedBitrateWithinEncoderBound=true",
        claimedFeatures = claimedFeatures
    )
}
