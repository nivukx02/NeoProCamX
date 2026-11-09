package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f07 / CP97 — pure N-frame scene-change debounce. Hand-verifiable. */
class SceneStateHysteresisPolicyTest {

    @Test
    fun debounceRequiresConsecutiveFrames() {
        val req = 3
        var pending: SceneType? = null
        var count = 0

        var d = SceneStateHysteresisPolicy.advance(SceneType.NIGHT, pending, count, req)
        assertFalse(d.commit)
        assertEquals(SceneType.NIGHT, d.pendingScene)
        assertEquals(1, d.pendingCount)
        pending = d.pendingScene; count = d.pendingCount

        d = SceneStateHysteresisPolicy.advance(SceneType.NIGHT, pending, count, req)
        assertFalse(d.commit)
        assertEquals(2, d.pendingCount)
        pending = d.pendingScene; count = d.pendingCount

        d = SceneStateHysteresisPolicy.advance(SceneType.NIGHT, pending, count, req)
        assertTrue("commits on the 3rd consecutive frame", d.commit)
        assertNull(d.pendingScene)
        assertEquals(0, d.pendingCount)
    }

    @Test
    fun differentCandidateRestartsTheRun() {
        val d = SceneStateHysteresisPolicy.advance(SceneType.LANDSCAPE, SceneType.NIGHT, 2, 3)
        assertFalse(d.commit)
        assertEquals(SceneType.LANDSCAPE, d.pendingScene)
        assertEquals(1, d.pendingCount)
    }

    @Test
    fun strictRequiresFewerConsecutiveFrames() {
        assertEquals(2, SceneStateHysteresisPolicy.requiredConsecutive(strictHighEnd = true))
        assertEquals(3, SceneStateHysteresisPolicy.requiredConsecutive(strictHighEnd = false))
        val first = SceneStateHysteresisPolicy.advance(SceneType.NIGHT, null, 0, 2)
        assertFalse(first.commit)
        val second = SceneStateHysteresisPolicy.advance(SceneType.NIGHT, first.pendingScene, first.pendingCount, 2)
        assertTrue(second.commit)
    }
}
