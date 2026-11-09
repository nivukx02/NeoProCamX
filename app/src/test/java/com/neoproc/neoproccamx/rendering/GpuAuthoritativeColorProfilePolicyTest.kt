package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuAuthoritativeColorProfilePolicyTest {
    @Test
    fun `ndr mode overrides stale natural rec709 identity atomically`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "NDR10+",
            requestedColorSpaceName = "Rec.709",
            requestedColorScienceName = "Natural"
        )
        val normalized = GpuColorProfileNormalizer.normalize(
            resolved.colorSpaceName,
            resolved.colorScienceName
        )

        assertTrue(resolved.modeOverrideApplied)
        assertEquals(GpuColorSpace.REC2020, normalized.colorSpace)
        assertEquals(GpuColorScience.NDR10_PLUS, normalized.colorScience)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, normalized.recommendedTransport)
    }

    @Test
    fun `nivukx mode overrides stale natural bt2020 sdr identity atomically`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "NIVUKXVISION",
            requestedColorSpaceName = "Rec.2020",
            requestedColorScienceName = "Natural"
        )
        val normalized = GpuColorProfileNormalizer.normalize(
            resolved.colorSpaceName,
            resolved.colorScienceName
        )

        assertTrue(resolved.modeOverrideApplied)
        assertEquals(GpuColorScience.NIVUKX_VISION, normalized.colorScience)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, normalized.recommendedTransport)
    }

    @Test
    fun `ordinary sdr keeps the requested profile unchanged`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "OFF",
            requestedColorSpaceName = "Rec.709",
            requestedColorScienceName = "Natural"
        )

        assertFalse(resolved.modeOverrideApplied)
        assertEquals("Rec.709", resolved.colorSpaceName)
        assertEquals("Natural", resolved.colorScienceName)
    }

    @Test
    fun `off mode removes stale ndr science without narrowing requested gamut`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "OFF",
            requestedColorSpaceName = "Rec.2020-NDR10+",
            requestedColorScienceName = "NDR10+"
        )
        val normalized = GpuColorProfileNormalizer.normalize(
            resolved.colorSpaceName,
            resolved.colorScienceName
        )

        assertTrue(resolved.modeOverrideApplied)
        assertEquals("Rec.2020", resolved.colorSpaceName)
        assertEquals("Natural", resolved.colorScienceName)
        assertEquals(GpuColorSpace.REC2020, normalized.colorSpace)
        assertEquals(GpuColorScience.NATURAL, normalized.colorScience)
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.OFF,
                normalized.colorScience
            ).coherent
        )
    }

    @Test
    fun `unspecified mode preserves legacy caller supplied ndr identity`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = null,
            requestedColorSpaceName = "Rec.2020",
            requestedColorScienceName = "NDR10+"
        )

        assertFalse(resolved.modeOverrideApplied)
        assertEquals("Rec.2020", resolved.colorSpaceName)
        assertEquals("NDR10+", resolved.colorScienceName)
    }

    @Test
    fun `mode transition cannot retain previous ndr packet identity`() {
        val activeNdr = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "NDR10+",
            requestedColorSpaceName = "Rec.2020-NDR10+",
            requestedColorScienceName = "NDR10+"
        )
        val transitionedOff = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "OFF",
            requestedColorSpaceName = activeNdr.colorSpaceName,
            requestedColorScienceName = activeNdr.colorScienceName
        )
        val activeScience = GpuColorProfileNormalizer.normalize(
            activeNdr.colorSpaceName,
            activeNdr.colorScienceName
        ).colorScience
        val transitionedScience = GpuColorProfileNormalizer.normalize(
            transitionedOff.colorSpaceName,
            transitionedOff.colorScienceName
        ).colorScience

        assertEquals(GpuColorScience.NDR10_PLUS, activeScience)
        assertEquals(GpuColorScience.NATURAL, transitionedScience)
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.NDR10_PLUS,
                activeScience
            ).coherent
        )
        assertTrue(
            NdrRenderProfileCoherencePolicy.evaluate(
                NdrNivukxMeasuredMode.OFF,
                transitionedScience
            ).coherent
        )
    }

    @Test
    fun `nivukx identity remains exact in nivukx mode`() {
        val resolved = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "NIVUKXVISION",
            requestedColorSpaceName = "Rec.709",
            requestedColorScienceName = "Natural"
        )

        assertEquals("Rec.2020", resolved.colorSpaceName)
        assertEquals("NIVUKX Vision", resolved.colorScienceName)
    }

    @Test
    fun `log and raw compatible non ndr identities remain untouched`() {
        val log = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "OFF",
            requestedColorSpaceName = "Rec.2020-N-Log",
            requestedColorScienceName = "N-Log Cinema"
        )
        val raw = GpuAuthoritativeColorProfilePolicy.resolve(
            normalizedHdrMode = "OFF",
            requestedColorSpaceName = "Neo ACES AP1",
            requestedColorScienceName = "Natural"
        )

        assertFalse(log.modeOverrideApplied)
        assertEquals("Rec.2020-N-Log", log.colorSpaceName)
        assertEquals("N-Log Cinema", log.colorScienceName)
        assertFalse(raw.modeOverrideApplied)
        assertEquals("Neo ACES AP1", raw.colorSpaceName)
        assertEquals("Natural", raw.colorScienceName)
    }
}
