package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingResolutionSelectionAuthorityTest {

    @Test
    fun `direct resolution remains authoritative for later prearm resolution`() {
        val authority = RecordingResolutionSelectionAuthority()

        authority.claim(1920, 1080)

        assertTrue(authority.owns(1920, 1080))
        assertTrue(authority.owns(1080, 1920))
        assertFalse(authority.owns(1632, 1224))
    }

    @Test
    fun `newer direct resolution atomically replaces the previous authority`() {
        val authority = RecordingResolutionSelectionAuthority()
        val first = authority.claim(1920, 1080)
        val second = authority.claim(4096, 3072)

        assertTrue(second.generation > first.generation)
        assertFalse(authority.owns(1920, 1080))
        assertTrue(authority.owns(4096, 3072))
        assertEquals(second, authority.snapshot())
    }

    @Test
    fun `cold start rehydrates persisted resolution authority`() {
        val authority = RecordingResolutionSelectionAuthority()

        val restored = authority.restorePersistedIfUnclaimed(3840, 2160)

        assertTrue(authority.owns(3840, 2160))
        assertEquals(restored, authority.snapshot())
    }

    @Test
    fun `persisted restore cannot replace a newer direct selection`() {
        val authority = RecordingResolutionSelectionAuthority()
        val direct = authority.claim(4096, 3072)

        val restored = authority.restorePersistedIfUnclaimed(3840, 2160)

        assertEquals(direct, restored)
        assertTrue(authority.owns(4096, 3072))
        assertFalse(authority.owns(3840, 2160))
    }

    @Test
    fun `stale asynchronous prearm cannot commit after newer direct resolution`() {
        val authority = RecordingResolutionSelectionAuthority()
        val stalePrearmClaim = authority.claim(1920, 1080)
        var publishedWidth = stalePrearmClaim.width
        var publishedHeight = stalePrearmClaim.height

        val direct = authority.claim(4096, 3072)
        publishedWidth = direct.width
        publishedHeight = direct.height

        val staleCommitted = authority.commitIfCurrent(stalePrearmClaim) {
            publishedWidth = stalePrearmClaim.width
            publishedHeight = stalePrearmClaim.height
        }

        assertFalse(staleCommitted)
        assertFalse(authority.isCurrent(stalePrearmClaim))
        assertTrue(authority.isCurrent(direct))
        assertEquals(4096, publishedWidth)
        assertEquals(3072, publishedHeight)
    }

    @Test
    fun `current prearm claim commits exactly once`() {
        val authority = RecordingResolutionSelectionAuthority()
        val current = authority.claim(4096, 3072)
        var commits = 0

        assertTrue(authority.commitIfCurrent(current) { commits += 1 })

        assertEquals(1, commits)
        assertTrue(authority.isCurrent(current))
    }
}
