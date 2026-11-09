package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.camera.CameraDynamicRangeCoherencePolicy
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.camera.HdrDynamicRangeState
import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s1-f05 — wires the camera's silent 8-bit fallback detection into the Omega gate's strict-10-bit
 * reporting. A requested 10-bit Neo transport that silently negotiated 8-bit can never be certified
 * as SUPPORTED. The default (no silent fallback) reproduces today's gate verdict bit-for-bit, so
 * this is a pure honesty hardening, not a downgrade.
 */
class OmegaCapabilityGateDynamicRangeFallbackTest {

    @Test
    fun `ndr ten bit is supported when there is no silent fallback (no downgrade default)`() {
        assertEquals(
            OmegaCapabilityStatus.SUPPORTED,
            OmegaCapabilityGate.evaluate(ndrInput(silentFallback = false))
                .statusOf(OmegaCapabilityId.NDR_NEO_TRANSPORT)
        )
    }

    @Test
    fun `ndr ten bit is downgraded to limited on a silent eight bit fallback`() {
        assertEquals(
            OmegaCapabilityStatus.LIMITED,
            OmegaCapabilityGate.evaluate(ndrInput(silentFallback = true))
                .statusOf(OmegaCapabilityId.NDR_NEO_TRANSPORT)
        )
    }

    @Test
    fun `nivukx ten bit is downgraded to limited on a silent eight bit fallback`() {
        assertEquals(
            OmegaCapabilityStatus.SUPPORTED,
            OmegaCapabilityGate.evaluate(nivukxInput(silentFallback = false))
                .statusOf(OmegaCapabilityId.NIVUKX_NEO_TRANSPORT)
        )
        assertEquals(
            OmegaCapabilityStatus.LIMITED,
            OmegaCapabilityGate.evaluate(nivukxInput(silentFallback = true))
                .statusOf(OmegaCapabilityId.NIVUKX_NEO_TRANSPORT)
        )
    }

    @Test
    fun `n-log ten bit is downgraded to limited on a silent eight bit fallback`() {
        assertEquals(
            OmegaCapabilityStatus.SUPPORTED,
            OmegaCapabilityGate.evaluate(nLogInput(silentFallback = false))
                .statusOf(OmegaCapabilityId.N_LOG_SDR_TRUTH)
        )
        assertEquals(
            OmegaCapabilityStatus.LIMITED,
            OmegaCapabilityGate.evaluate(nLogInput(silentFallback = true))
                .statusOf(OmegaCapabilityId.N_LOG_SDR_TRUTH)
        )
    }

    @Test
    fun `a claimed ndr transport with a silent fallback becomes a blocking failure`() {
        val report = OmegaCapabilityGate.evaluate(
            ndrInput(silentFallback = true, claimedFeatures = setOf(OmegaCapabilityId.NDR_NEO_TRANSPORT))
        )
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.overallStatus)
        assertEquals(listOf(OmegaCapabilityId.NDR_NEO_TRANSPORT), report.blockingFailures.map { it.id })
    }

    @Test
    fun `coherence policy drives the gate end to end so a faked ten bit claim is impossible`() {
        // A capable device that silently ran STANDARD preview despite a 10-bit NDR request.
        val silentState = HdrDynamicRangeState(
            requestedMode = HdrDynamicRangeMode.HDR10_PLUS,
            previewProfile = null,
            encoderProfile = null,
            resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            support = HdrDynamicRangePolicy.buildSupport(setOf(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
        )
        val silentFallback = CameraDynamicRangeCoherencePolicy.silentFallbackDetected(
            state = silentState,
            sessionConfigured = true,
            recordingEncoderActive = true
        )

        val report = OmegaCapabilityGate.evaluate(ndrInput(silentFallback = silentFallback))
        assertEquals(
            OmegaCapabilityStatus.LIMITED,
            report.statusOf(OmegaCapabilityId.NDR_NEO_TRANSPORT)
        )
    }

    private fun ndrInput(
        silentFallback: Boolean,
        claimedFeatures: Set<OmegaCapabilityId> = emptySet()
    ) = tenBitHdrInput(
        selectedProfile = "NDR10+",
        activeTransport = "NDR10_PLUS_PQ_MAIN10",
        silentFallback = silentFallback,
        claimedFeatures = claimedFeatures
    )

    private fun nivukxInput(silentFallback: Boolean) = tenBitHdrInput(
        selectedProfile = "NIVUKX Vision",
        activeTransport = "NIVUKX_PQ_MAIN10",
        silentFallback = silentFallback
    )

    private fun tenBitHdrInput(
        selectedProfile: String,
        activeTransport: String,
        silentFallback: Boolean,
        claimedFeatures: Set<OmegaCapabilityId> = emptySet()
    ) = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.HDR,
        selectedProfile = selectedProfile,
        activeTransport = activeTransport,
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
        claimedFeatures = claimedFeatures,
        dynamicRangeSilentFallback = silentFallback
    )

    private fun nLogInput(silentFallback: Boolean) = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.LOG,
        selectedProfile = "N-LOG",
        activeTransport = "N_LOG_BT2020_MAIN10",
        transfer = "N-LOG/OETF",
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
        dynamicRangeSilentFallback = silentFallback
    )
}
