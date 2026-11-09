package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordGradeBakerTest {

    private fun assertIdentity(m: FloatArray) {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(9, m.size)
        for (i in 0..8) assertEquals("index $i", id[i], m[i], 1e-6f)
    }

    @Test
    fun `neutral look bakes exact identity`() {
        val spec = RecordGradeBaker.bake(saturation = 1.7f, contrast = 1.4f, warmth = 0.5f, isNeutral = true)
        assertIdentity(spec.matrix)
        assertEquals(1f, spec.contrast, 0f)
    }

    @Test
    fun `unit saturation and contrast with no warmth is identity`() {
        val spec = RecordGradeBaker.bake(saturation = 1f, contrast = 1f, warmth = 0f, isNeutral = false)
        assertIdentity(spec.matrix)
        assertEquals(1f, spec.contrast, 1e-6f)
    }

    @Test
    fun `zero saturation collapses every row to luma weights (grayscale)`() {
        val spec = RecordGradeBaker.bake(saturation = 0f, contrast = 1f, warmth = 0f, isNeutral = false)
        val lw = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        for (row in 0..2) {
            for (j in 0..2) {
                assertEquals("row $row col $j", lw[j], spec.matrix[row * 3 + j], 1e-6f)
            }
        }
    }

    @Test
    fun `saturation matrix preserves luma of a gray pixel`() {
        val spec = RecordGradeBaker.bake(saturation = 1.6f, contrast = 1f, warmth = 0f, isNeutral = false)
        val g = 0.5f
        // For a gray input each output channel = sum of its row * 0.5; row sums are 1.0 for a
        // luma-preserving saturation matrix, so gray stays gray.
        for (row in 0..2) {
            val out = (spec.matrix[row * 3] + spec.matrix[row * 3 + 1] + spec.matrix[row * 3 + 2]) * g
            assertEquals("row $row gray preserved", g, out, 1e-5f)
        }
    }

    @Test
    fun `warmth lifts red row and lowers blue row`() {
        val warm = RecordGradeBaker.bake(saturation = 1.2f, contrast = 1f, warmth = 0.8f, isNeutral = false)
        val neutralWarm = RecordGradeBaker.bake(saturation = 1.2f, contrast = 1f, warmth = 0f, isNeutral = false)
        val warmRedSum = warm.matrix[0] + warm.matrix[1] + warm.matrix[2]
        val neutralRedSum = neutralWarm.matrix[0] + neutralWarm.matrix[1] + neutralWarm.matrix[2]
        val warmBlueSum = warm.matrix[6] + warm.matrix[7] + warm.matrix[8]
        val neutralBlueSum = neutralWarm.matrix[6] + neutralWarm.matrix[7] + neutralWarm.matrix[8]
        assertTrue("warm red row raised", warmRedSum > neutralRedSum)
        assertTrue("warm blue row lowered", warmBlueSum < neutralBlueSum)
    }

    @Test
    fun `contrast passthrough is clamped to safe range`() {
        assertEquals(2f, RecordGradeBaker.bake(1.2f, 5f, 0f, isNeutral = false).contrast, 0f)
        assertEquals(0.5f, RecordGradeBaker.bake(1.2f, 0.1f, 0f, isNeutral = false).contrast, 0f)
    }
}
