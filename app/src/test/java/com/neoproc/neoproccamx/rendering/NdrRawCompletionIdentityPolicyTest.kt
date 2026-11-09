package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class NdrRawCompletionIdentityPolicyTest {
    @Test
    fun `equal sentinel identities are never deliverable`() {
        val invalid = NdrRenderProfileIdentity(0L, 0L)
        assertEquals(
            NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE,
            NdrRawCompletionIdentityPolicy.resolve(
                dispatchIdentity = invalid,
                currentIdentity = invalid,
                cameraSessionCurrent = true
            )
        )
    }

    @Test
    fun `exact dispatch revision is deliverable`() {
        val identity = NdrRenderProfileIdentity(8L, 4L)
        assertEquals(
            NdrRawCompletionIdentityPolicy.Decision.DELIVER_EXACT,
            NdrRawCompletionIdentityPolicy.resolve(identity, identity, cameraSessionCurrent = true)
        )
    }

    @Test
    fun `old raw output cannot be graded by a newer packet revision`() {
        assertEquals(
            NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE,
            NdrRawCompletionIdentityPolicy.resolve(
                dispatchIdentity = NdrRenderProfileIdentity(8L, 3L),
                currentIdentity = NdrRenderProfileIdentity(8L, 4L),
                cameraSessionCurrent = true
            )
        )
    }

    @Test
    fun `mode advance missing identity and retired camera session all fail closed`() {
        val dispatch = NdrRenderProfileIdentity(8L, 4L)
        listOf(
            NdrRawCompletionIdentityPolicy.resolve(
                dispatch,
                NdrRenderProfileIdentity(9L, 1L),
                cameraSessionCurrent = true
            ),
            NdrRawCompletionIdentityPolicy.resolve(
                null,
                dispatch,
                cameraSessionCurrent = true
            ),
            NdrRawCompletionIdentityPolicy.resolve(
                dispatch,
                dispatch,
                cameraSessionCurrent = false
            )
        ).forEach { decision ->
            assertEquals(NdrRawCompletionIdentityPolicy.Decision.RETIRE_STALE, decision)
        }
    }
}
