package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRenderProfileCoherencePolicyTest {
    @Test
    fun `matching NDR and NIVUKX identities are accepted`() {
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.NDR10_PLUS,
                GpuColorScience.NDR10_PLUS
            ).coherent
        )
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.NIVUKX_VISION,
                GpuColorScience.NIVUKX_VISION
            ).coherent
        )
    }

    @Test
    fun `rapid NDR to NIVUKX transition rejects both mixed identities`() {
        assertFalse(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.NDR10_PLUS,
                GpuColorScience.NIVUKX_VISION
            ).coherent
        )
        assertFalse(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.NIVUKX_VISION,
                GpuColorScience.NDR10_PLUS
            ).coherent
        )
    }

    @Test
    fun `off mode accepts SDR identity and rejects retained NDR identity`() {
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.OFF,
                GpuColorScience.REC709_STANDARD
            ).coherent
        )
        assertFalse(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.OFF,
                GpuColorScience.NDR10_PLUS
            ).coherent
        )
    }
}
