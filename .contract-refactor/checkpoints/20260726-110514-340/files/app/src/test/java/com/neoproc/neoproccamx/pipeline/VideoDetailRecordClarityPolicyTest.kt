package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailRecordClarityPolicyTest {

    private val floor = 0.10f
    private val ceiling = 0.90f
    private val base = 0.50f

    @Test
    fun `focused and null are identity anchors`() {
        assertEquals(
            base,
            VideoDetailRecordClarityPolicy.resolveRecordSharpening(base, VideoDetailProfile.FOCUSED_DETAIL, floor, ceiling),
            0f
        )
        assertEquals(
            base,
            VideoDetailRecordClarityPolicy.resolveRecordSharpening(base, null, floor, ceiling),
            0f
        )
    }

    @Test
    fun `natural is softer than default but not below floor`() {
        val natural = VideoDetailRecordClarityPolicy.resolveRecordSharpening(
            base, VideoDetailProfile.NATURAL_DETAIL, floor, ceiling
        )
        assertTrue("natural=$natural should be < base", natural < base)
        assertTrue("natural=$natural should be >= floor", natural >= floor)
    }

    @Test
    fun `extreme is crisper than default but not above ceiling`() {
        val extreme = VideoDetailRecordClarityPolicy.resolveRecordSharpening(
            base, VideoDetailProfile.EXTREME_DETAIL, floor, ceiling
        )
        assertTrue("extreme=$extreme should be > base", extreme > base)
        assertTrue("extreme=$extreme should be <= ceiling", extreme <= ceiling)
    }

    @Test
    fun `profiles are monotonic natural le focused le extreme`() {
        val natural = VideoDetailRecordClarityPolicy.resolveRecordSharpening(base, VideoDetailProfile.NATURAL_DETAIL, floor, ceiling)
        val focused = VideoDetailRecordClarityPolicy.resolveRecordSharpening(base, VideoDetailProfile.FOCUSED_DETAIL, floor, ceiling)
        val extreme = VideoDetailRecordClarityPolicy.resolveRecordSharpening(base, VideoDetailProfile.EXTREME_DETAIL, floor, ceiling)
        assertTrue(natural <= focused)
        assertTrue(focused <= extreme)
    }

    @Test
    fun `ceiling unset falls back to halo-safe cap for extreme`() {
        // When the plan reports no ceiling (0f), EXTREME is capped at the absolute 0.94 halo cap.
        val extreme = VideoDetailRecordClarityPolicy.resolveRecordSharpening(
            base = 0.90f, profile = VideoDetailProfile.EXTREME_DETAIL, floor = 0f, ceiling = 0f
        )
        assertTrue("extreme=$extreme must not exceed 0.94", extreme <= 0.94f)
        assertTrue("extreme=$extreme must be >= base", extreme >= 0.90f)
    }

    @Test
    fun `result never exceeds effective ceiling for any profile`() {
        for (p in listOf(null, VideoDetailProfile.NATURAL_DETAIL, VideoDetailProfile.FOCUSED_DETAIL, VideoDetailProfile.EXTREME_DETAIL)) {
            val r = VideoDetailRecordClarityPolicy.resolveRecordSharpening(0.88f, p, 0.2f, 0.90f)
            assertTrue("profile=$p result=$r must be <= ceiling 0.90", r <= 0.90f + 1e-6f)
        }
    }
}
