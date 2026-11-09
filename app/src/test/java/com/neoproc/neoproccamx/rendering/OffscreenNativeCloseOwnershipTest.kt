package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenNativeCloseOwnershipTest {
    @Test
    fun `signed arm64 pointer bit pattern remains a valid nonzero native owner`() {
        val signedPointer = 0xB4000070A20180D8uL.toLong()
        val ownership = OffscreenNativeCloseOwnership(initialHandle = signedPointer)

        assertTrue(signedPointer < 0L)
        assertEquals(signedPointer, ownership.operationHandle())
        assertEquals(signedPointer, ownership.beginClose())
        assertTrue(ownership.completeClose(signedPointer, acknowledged = true))
        assertTrue(ownership.isClosed)
    }

    @Test
    fun `zero alone represents an absent native owner`() {
        val ownership = OffscreenNativeCloseOwnership(initialHandle = 0L)

        assertTrue(ownership.isClosed)
        assertEquals(0L, ownership.operationHandle())
        assertEquals(0L, ownership.beginClose())
    }

    @Test
    fun `failed close quarantines exact owner and permanently blocks ordinary operations`() {
        val ownership = OffscreenNativeCloseOwnership(initialHandle = 41L)

        assertEquals(41L, ownership.operationHandle())
        val firstAttempt = ownership.beginClose()
        assertEquals(41L, firstAttempt)
        assertEquals(0L, ownership.operationHandle())

        assertFalse(ownership.completeClose(firstAttempt, acknowledged = false))
        assertTrue(ownership.hasQuarantinedOwnership)
        assertEquals(OffscreenNativeCloseOwnership.Phase.CLOSE_QUARANTINED, ownership.phase)
        assertEquals(0L, ownership.operationHandle())

        // Only close can recover the retained handle, and the retry owns the identical native state.
        assertEquals(firstAttempt, ownership.beginClose())
        assertEquals(0L, ownership.operationHandle())
    }

    @Test
    fun `acknowledged retry releases exactly once and subsequent closes are idempotent`() {
        val ownership = OffscreenNativeCloseOwnership(initialHandle = 73L)
        val firstAttempt = ownership.beginClose()
        ownership.completeClose(firstAttempt, acknowledged = false)

        val retryAttempt = ownership.beginClose()
        assertEquals(firstAttempt, retryAttempt)
        assertTrue(ownership.completeClose(retryAttempt, acknowledged = true))
        assertTrue(ownership.isClosed)
        assertFalse(ownership.hasQuarantinedOwnership)
        assertEquals(0L, ownership.operationHandle())
        assertEquals(0L, ownership.beginClose())
        assertEquals(OffscreenNativeCloseOwnership.Phase.CLOSED, ownership.phase)
    }

    @Test(expected = IllegalStateException::class)
    fun `mismatched native acknowledgement cannot release retained ownership`() {
        val ownership = OffscreenNativeCloseOwnership(initialHandle = 101L)
        ownership.beginClose()
        ownership.completeClose(attemptedHandle = 202L, acknowledged = true)
    }
}
