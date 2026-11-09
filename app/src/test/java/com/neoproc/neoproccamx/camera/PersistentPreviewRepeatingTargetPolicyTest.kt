package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PersistentPreviewRepeatingTargetPolicyTest {
    private class Target

    @Test
    fun `persistent pair remains identical when visible ownership changes`() {
        val sdr = Target()
        val hdr = Target()

        val sdrOwnerTargets = PersistentPreviewRepeatingTargetPolicy.resolve(sdr, sdr, hdr)
        val hdrOwnerTargets = PersistentPreviewRepeatingTargetPolicy.resolve(hdr, sdr, hdr)

        assertEquals(2, sdrOwnerTargets.size)
        assertSame(sdr, sdrOwnerTargets[0])
        assertSame(hdr, sdrOwnerTargets[1])
        assertSame(sdrOwnerTargets[0], hdrOwnerTargets[0])
        assertSame(sdrOwnerTargets[1], hdrOwnerTargets[1])
    }

    @Test
    fun `single output fallback targets only the active preview`() {
        val active = Target()

        val targets = PersistentPreviewRepeatingTargetPolicy.resolve(
            activePreview = active,
            persistentSdrPreview = null,
            persistentHdrPreview = null
        )

        assertEquals(1, targets.size)
        assertSame(active, targets.single())
    }

    @Test
    fun `incompatible standard and hdr profiles target only the active warm surface`() {
        val sdr = Target()
        val hdr = Target()

        val targets = PersistentPreviewRepeatingTargetPolicy.resolve(
            activePreview = hdr,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            profilesCanShareRequest = false
        )

        assertEquals(1, targets.size)
        assertSame(hdr, targets.single())
    }

    @Test
    fun `recording targets only the visible preview while retaining the configured pair`() {
        val sdr = Target()
        val hdr = Target()

        val sdrTargets = PersistentPreviewRepeatingTargetPolicy.resolve(
            activePreview = sdr,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            profilesCanShareRequest = true,
            recordingActive = true
        )
        val hdrTargets = PersistentPreviewRepeatingTargetPolicy.resolve(
            activePreview = hdr,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            profilesCanShareRequest = true,
            recordingActive = true
        )

        assertEquals(1, sdrTargets.size)
        assertSame(sdr, sdrTargets.single())
        assertEquals(1, hdrTargets.size)
        assertSame(hdr, hdrTargets.single())
    }

    @Test
    fun `visible handoff retargets during recording or when profiles cannot coexist`() {
        assertEquals(
            false,
            PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget(
                profilesCanShareRequest = true,
                recordingActive = false
            )
        )
        assertEquals(
            true,
            PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget(
                profilesCanShareRequest = true,
                recordingActive = true
            )
        )
        assertEquals(
            true,
            PersistentPreviewRepeatingTargetPolicy.requiresHandoffRetarget(
                profilesCanShareRequest = false,
                recordingActive = false
            )
        )
    }

    @Test
    fun `incomplete or aliased persistent pair cannot replace active preview`() {
        val active = Target()
        val aliased = Target()

        val incomplete = PersistentPreviewRepeatingTargetPolicy.resolve(active, aliased, null)
        val invalidAliased = PersistentPreviewRepeatingTargetPolicy.resolve(active, aliased, aliased)

        assertEquals(1, incomplete.size)
        assertSame(active, incomplete.single())
        assertEquals(1, invalidAliased.size)
        assertSame(active, invalidAliased.single())
    }

    @Test
    fun `idle session keeps both persistent preview outputs for immediate profile handoff`() {
        val sdr = Target()
        val hdr = Target()

        val outputs = PersistentPreviewSessionOutputPolicy.resolve(
            activePreview = sdr,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            constrainToVisiblePreview = false
        )

        assertEquals(2, outputs.size)
        assertSame(sdr, outputs[0])
        assertSame(hdr, outputs[1])
    }

    @Test
    fun `direct recording session attaches only visible preview output`() {
        val sdr = Target()
        val hdr = Target()

        val outputs = PersistentPreviewSessionOutputPolicy.resolve(
            activePreview = hdr,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            constrainToVisiblePreview = true
        )

        assertEquals(1, outputs.size)
        assertSame(hdr, outputs.single())
    }

    @Test
    fun `constrained session falls back to one persistent output during ownership handoff`() {
        val sdr = Target()
        val hdr = Target()

        val outputs = PersistentPreviewSessionOutputPolicy.resolve(
            activePreview = null,
            persistentSdrPreview = sdr,
            persistentHdrPreview = hdr,
            constrainToVisiblePreview = true
        )

        assertEquals(1, outputs.size)
        assertSame(sdr, outputs.single())
    }

    @Test
    fun `dynamic range coexistence requires both constrained profiles to admit the pair`() {
        val standard = HdrDynamicRangePolicy.PROFILE_STANDARD
        val pq = HdrDynamicRangePolicy.PROFILE_HDR10

        assertEquals(
            true,
            PersistentPreviewDynamicRangeCoexistencePolicy.canShareRequest(
                firstProfile = standard,
                firstConstraints = emptySet(),
                secondProfile = pq,
                secondConstraints = emptySet()
            )
        )
        assertEquals(
            true,
            PersistentPreviewDynamicRangeCoexistencePolicy.canShareRequest(
                firstProfile = standard,
                firstConstraints = setOf(standard, pq),
                secondProfile = pq,
                secondConstraints = setOf(standard, pq)
            )
        )
        assertEquals(
            false,
            PersistentPreviewDynamicRangeCoexistencePolicy.canShareRequest(
                firstProfile = standard,
                firstConstraints = setOf(standard),
                secondProfile = pq,
                secondConstraints = setOf(standard, pq)
            )
        )
        assertEquals(
            false,
            PersistentPreviewDynamicRangeCoexistencePolicy.canShareRequest(
                firstProfile = standard,
                firstConstraints = setOf(standard, pq),
                secondProfile = pq,
                secondConstraints = setOf(pq)
            )
        )
    }
}
