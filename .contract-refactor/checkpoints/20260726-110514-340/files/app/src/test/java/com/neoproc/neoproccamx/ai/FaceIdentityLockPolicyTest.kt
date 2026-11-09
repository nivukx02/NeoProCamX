package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** s7-f02 / CP93 — pure identity-lock hysteresis. Hand-verifiable. */
class FaceIdentityLockPolicyTest {

    private fun state(hitStreak: Int) = MotionState(
        centerX = 0f, centerY = 0f, velocityX = 0f, velocityY = 0f,
        confidence = 0.9f, updatedAtMs = 0L, hitStreak = hitStreak
    )

    @Test
    fun emptyOrUnestablishedTracksHaveNoLock() {
        assertNull(FaceIdentityLockPolicy.selectLockedId(emptyMap()))
        // All single-hit tracks: none is established enough to lock.
        assertNull(FaceIdentityLockPolicy.selectLockedId(mapOf(1 to state(1), 2 to state(1))))
    }

    @Test
    fun mostEstablishedTrackWinsTheLock() {
        assertEquals(7, FaceIdentityLockPolicy.selectLockedId(mapOf(7 to state(4), 3 to state(2))))
    }

    @Test
    fun streakTieResolvesToLowestIdDeterministically() {
        assertEquals(3, FaceIdentityLockPolicy.selectLockedId(mapOf(9 to state(5), 3 to state(5))))
        assertEquals(3, FaceIdentityLockPolicy.selectLockedId(mapOf(3 to state(5), 9 to state(5))))
    }

    @Test
    fun lockBiasOnlyRewardsTheLockedIdentity() {
        assertEquals(0.06f, FaceIdentityLockPolicy.lockBias(7, lockedId = 7, stickiness = 0.06f), 0f)
        assertEquals(0f, FaceIdentityLockPolicy.lockBias(7, lockedId = 3, stickiness = 0.06f), 0f)
        assertEquals(0f, FaceIdentityLockPolicy.lockBias(7, lockedId = null, stickiness = 0.06f), 0f)
    }
}
