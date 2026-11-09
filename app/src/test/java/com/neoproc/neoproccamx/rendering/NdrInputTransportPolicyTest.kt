package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrInputTransportPolicyTest {
    @Test
    fun `temporary missing preview output uses requested mode profile for egl handoff`() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = null,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                sessionConfigured = false,
                appliedProfileTruthCurrent = false
            )
        )
    }

    @Test
    fun `explicit standard preview profile is never disguised as pq`() {
        assertEquals(
            HdrDynamicRangePolicy.PROFILE_STANDARD,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = HdrDynamicRangePolicy.PROFILE_STANDARD,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
                sessionConfigured = true,
                appliedProfileTruthCurrent = true
            )
        )
    }

    @Test
    fun `current standard null binding never falls back to requested pq`() {
        assertEquals(
            null,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = null,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10,
                sessionConfigured = true,
                appliedProfileTruthCurrent = true
            )
        )
    }

    @Test
    fun `stale applied generation never becomes transport truth`() {
        assertEquals(
            null,
            NdrInputTransportPolicy.effectivePreviewProfile(
                appliedPreviewProfile = HdrDynamicRangePolicy.PROFILE_HDR10,
                resolvedProfileForCurrentMode = HdrDynamicRangePolicy.PROFILE_HDR10,
                sessionConfigured = true,
                appliedProfileTruthCurrent = false
            )
        )
    }


    @Test
    fun `buffer dataspace overrides a stale pq assumption`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_BT709,
            appliedProfile = HdrDynamicRangePolicy.PROFILE_HDR10_PLUS,
            preferPqWhenUnknown = true
        )

        assertEquals(NdrInputTransfer.SDR, decision.transfer)
        assertEquals(NdrInputPrimaries.BT709, decision.primaries)
        assertEquals(NdrInputRange.LIMITED, decision.range)
        assertTrue(decision.dataSpaceKnown)
        assertFalse(decision.isStrictNdrTransport)
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, decision.renderPath)
    }

    @Test
    fun `camera pq and hlg dataspaces remain distinct but cannot enter proprietary ndr`() {
        val pq = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )
        val hlg = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_HLG,
            appliedProfile = null,
            preferPqWhenUnknown = true
        )

        assertEquals(NdrInputTransfer.PQ, pq.transfer)
        assertEquals(NdrInputTransfer.HLG, hlg.transfer)
        assertEquals(NdrInputPrimaries.BT2020, pq.primaries)
        assertEquals(NdrInputPrimaries.BT2020, hlg.primaries)
        assertFalse(pq.isStrictNdrTransport)
        assertFalse(hlg.isStrictNdrTransport)
        assertFalse(pq.isSupportedNdrInput)
        assertFalse(hlg.isSupportedNdrInput)
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, pq.renderPath)
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, hlg.renderPath)
    }

    @Test
    fun `i2405 pq dataspace resolves bt2020 pq limited range`() {
        val decision = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ_LIMITED,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )

        assertEquals(NdrInputTransfer.PQ, decision.transfer)
        assertEquals(NdrInputPrimaries.BT2020, decision.primaries)
        assertEquals(NdrInputRange.LIMITED, decision.range)
        assertFalse(decision.isStrictNdrTransport)
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, decision.renderPath)
    }

    @Test
    fun `unknown dataspace follows the applied camera profile`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = HdrDynamicRangePolicy.PROFILE_HDR10,
            preferPqWhenUnknown = false
        )

        assertEquals(NdrInputTransfer.PQ, decision.transfer)
        assertEquals(NdrInputRange.UNKNOWN, decision.range)
        assertFalse(decision.dataSpaceKnown)
        assertEquals("CAMERA2_PROFILE", decision.source)
    }

    @Test
    fun `pq profile identity remains detectable without admission`() {
        assertTrue(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HDR10))
        assertTrue(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
        assertFalse(NdrInputTransportPolicy.isPqProfile(HdrDynamicRangePolicy.PROFILE_HLG10))
        assertFalse(NdrInputTransportPolicy.isPqProfile(null))
    }

    @Test
    fun `standard bt709 is admitted only to visible ndr monitor grade`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_BT709,
            appliedProfile = HdrDynamicRangePolicy.PROFILE_STANDARD,
            preferPqWhenUnknown = false
        )

        assertFalse(decision.isStrictNdrTransport)
        assertFalse(decision.isSupportedNdrInput)
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, decision.renderPath)
        assertTrue(NdrPreviewInputAdmissionPolicy.isSupported(decision))
        assertEquals(
            NdrInputRenderPath.NDR_GRADE,
            NdrPreviewInputAdmissionPolicy.renderPath(decision)
        )
    }

    @Test
    fun `camera hlg and pq remain excluded from visible proprietary grade`() {
        val pq = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ,
            null,
            false
        )
        val hlg = NdrInputTransportPolicy.resolve(
            NdrInputTransportPolicy.DATASPACE_BT2020_HLG,
            null,
            false
        )

        assertFalse(NdrPreviewInputAdmissionPolicy.isSupported(pq))
        assertFalse(NdrPreviewInputAdmissionPolicy.isSupported(hlg))
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, NdrPreviewInputAdmissionPolicy.renderPath(pq))
        assertEquals(NdrInputRenderPath.TRANSPORT_BYPASS, NdrPreviewInputAdmissionPolicy.renderPath(hlg))
    }

    @Test
    fun `compositor preparation accepts only standard or unresolved profile`() {
        assertTrue(NdrPreviewInputAdmissionPolicy.canPrepareFromCameraProfile(null))
        assertTrue(
            NdrPreviewInputAdmissionPolicy.canPrepareFromCameraProfile(
                HdrDynamicRangePolicy.PROFILE_STANDARD
            )
        )
        assertFalse(
            NdrPreviewInputAdmissionPolicy.canPrepareFromCameraProfile(
                HdrDynamicRangePolicy.PROFILE_HLG10
            )
        )
        assertFalse(
            NdrPreviewInputAdmissionPolicy.canPrepareFromCameraProfile(
                HdrDynamicRangePolicy.PROFILE_HDR10
            )
        )
    }

    @Test
    fun `no camera dynamic range profile is admitted as proprietary ndr input`() {
        assertFalse(NdrInputTransportPolicy.isSupportedNdrInputProfile(HdrDynamicRangePolicy.PROFILE_HLG10))
        assertFalse(NdrInputTransportPolicy.isSupportedNdrInputProfile(HdrDynamicRangePolicy.PROFILE_HDR10))
        assertFalse(NdrInputTransportPolicy.isSupportedNdrInputProfile(HdrDynamicRangePolicy.PROFILE_HDR10_PLUS))
        assertFalse(NdrInputTransportPolicy.isSupportedNdrInputProfile(HdrDynamicRangePolicy.PROFILE_STANDARD))
        assertFalse(NdrInputTransportPolicy.isSupportedNdrInputProfile(null))
    }

    @Test
    fun `only raw derived linear acescg is admitted to proprietary ndr grade`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = null,
            preferPqWhenUnknown = false,
            sensorLinearAcesCg = true
        )

        assertEquals(NdrInputTransfer.NEO_SENSOR_LINEAR, decision.transfer)
        assertEquals(NdrInputPrimaries.ACESCG, decision.primaries)
        assertTrue(decision.isStrictNdrTransport)
        assertTrue(decision.isSupportedNdrInput)
        assertEquals(NdrInputRenderPath.NDR_GRADE, decision.renderPath)
    }

    @Test
    fun `project owned scene log10 fallback is admitted without hlg or pq`() {
        val decision = NdrInputTransportPolicy.resolve(
            dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            appliedProfile = null,
            preferPqWhenUnknown = false,
            sceneLog10AcesCg = true
        )

        assertEquals(NdrInputTransfer.NEO_SCENE_LOG10, decision.transfer)
        assertEquals(NdrInputPrimaries.ACESCG, decision.primaries)
        assertEquals(NdrInputRange.FULL, decision.range)
        assertEquals("VULKAN_RAW_DEMOSAIC_NEO_SCENE_LOG10", decision.source)
        assertTrue(decision.isStrictNdrTransport)
        assertTrue(decision.isSupportedNdrInput)
        assertEquals(NdrInputRenderPath.NDR_GRADE, decision.renderPath)
    }
}
