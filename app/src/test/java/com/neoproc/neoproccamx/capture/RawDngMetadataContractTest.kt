package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.capture.RawDngMetadataContract.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDngMetadataContractTest {

    @Test
    fun `a fully characterised result is complete`() {
        val present = RawDngMetadataContract.REQUIRED
        val completeness = RawDngMetadataContract.evaluate(present)
        assertTrue(completeness.isComplete)
        assertEquals(1.0f, completeness.ratio, 0.0001f)
        assertTrue(completeness.summary.contains("complete"))
    }

    @Test
    fun `a missing forward matrix is reported, not silently shipped`() {
        val present = RawDngMetadataContract.REQUIRED - Tag.FORWARD_MATRIX
        val completeness = RawDngMetadataContract.evaluate(present)
        assertFalse(completeness.isComplete)
        assertTrue(completeness.missing.contains(Tag.FORWARD_MATRIX))
        assertTrue(completeness.summary.contains("FORWARD_MATRIX"))
    }

    @Test
    fun `ratio reflects how many required tags are present`() {
        val present = setOf(Tag.BLACK_LEVEL, Tag.WHITE_LEVEL, Tag.NOISE_PROFILE)
        val completeness = RawDngMetadataContract.evaluate(present)
        assertEquals(3, completeness.present.intersect(completeness.required).size)
        assertEquals(3f / RawDngMetadataContract.REQUIRED.size.toFloat(), completeness.ratio, 0.0001f)
    }

    @Test
    fun `extra non required tags do not break completeness`() {
        val present = RawDngMetadataContract.REQUIRED + Tag.NEUTRAL_WHITE
        val completeness = RawDngMetadataContract.evaluate(present)
        assertTrue(completeness.isComplete)
    }
}
