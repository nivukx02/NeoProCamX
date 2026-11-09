package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** AF-SD (F09) — pure GROUP-cluster geometry + suppression logic (RectF-independent). */
class GroupClusterPolicyTest {

    private val eps = 1e-4f
    private fun f(id: Int, l: Float, t: Float, r: Float, b: Float, c: Float) =
        FaceBoundsLite(id, l, t, r, b, c)

    @Test
    fun twoNearEqualFaces_formCluster() {
        val c = GroupClusterPolicy.detectCluster(
            listOf(f(1, 0.2f, 0.3f, 0.4f, 0.5f, 0.8f), f(2, 0.45f, 0.3f, 0.65f, 0.5f, 0.8f))
        )
        assertNotNull(c)
        assertEquals(2, c!!.memberCount)
        assertEquals(0.2f, c.unionLeft, eps)
        assertEquals(0.65f, c.unionRight, eps)
        assertEquals(0.425f, c.centroidX, eps)
        assertEquals(0.4f, c.centroidY, eps)
        assertEquals(0.8f, c.meanConfidence, eps)
    }

    @Test
    fun twoFarFaces_returnNull() {
        assertNull(
            GroupClusterPolicy.detectCluster(
                listOf(f(1, 0.05f, 0.05f, 0.15f, 0.15f, 0.8f), f(2, 0.85f, 0.85f, 0.95f, 0.95f, 0.8f))
            )
        )
    }

    @Test
    fun oneDominantFace_returnsNull() {
        // 0.95 >= 0.45 * 1.6 (0.72) => one clear subject, not a group.
        assertNull(
            GroupClusterPolicy.detectCluster(
                listOf(f(1, 0.30f, 0.30f, 0.50f, 0.50f, 0.95f), f(2, 0.50f, 0.30f, 0.70f, 0.50f, 0.45f))
            )
        )
    }

    @Test
    fun weakFaceExcluded_returnsNull() {
        // Second face below MIN_FACE_CONFIDENCE => only one strong face => no cluster.
        assertNull(
            GroupClusterPolicy.detectCluster(
                listOf(f(1, 0.30f, 0.30f, 0.50f, 0.50f, 0.80f), f(2, 0.50f, 0.30f, 0.70f, 0.50f, 0.20f))
            )
        )
    }

    @Test
    fun threeInARow_singleCluster() {
        val c = GroupClusterPolicy.detectCluster(
            listOf(
                f(1, 0.225f, 0.3f, 0.375f, 0.5f, 0.85f),
                f(2, 0.425f, 0.3f, 0.575f, 0.5f, 0.85f),
                f(3, 0.625f, 0.3f, 0.775f, 0.5f, 0.85f)
            )
        )
        assertNotNull(c)
        assertEquals(3, c!!.memberCount)
        assertEquals(listOf(1, 2, 3), c.memberIds.sorted())
    }
}
