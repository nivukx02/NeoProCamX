package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawStillCaptureTargetPolicyTest {

    @Test
    fun `raw still request fails instead of silently falling back to jpeg only`() {
        val plan = RawStillCaptureTargetPolicy.resolve(
            captureRaw = true,
            captureJpeg = true,
            rawStreamActive = false,
            jpegStreamActive = true
        )

        assertFalse(plan.includeRaw)
        assertFalse(plan.includeJpeg)
        assertEquals(
            "RAW_SENSOR capture requested but RAW_SENSOR stream is not active in the current camera session",
            plan.failureReason
        )
    }

    @Test
    fun `jpeg only capture uses active jpeg target`() {
        val plan = RawStillCaptureTargetPolicy.resolve(
            captureRaw = false,
            captureJpeg = true,
            rawStreamActive = false,
            jpegStreamActive = true
        )

        assertFalse(plan.includeRaw)
        assertTrue(plan.includeJpeg)
        assertNull(plan.failureReason)
    }

    @Test
    fun `raw and jpeg capture includes both when both streams are active`() {
        val plan = RawStillCaptureTargetPolicy.resolve(
            captureRaw = true,
            captureJpeg = true,
            rawStreamActive = true,
            jpegStreamActive = true
        )

        assertTrue(plan.includeRaw)
        assertTrue(plan.includeJpeg)
        assertNull(plan.failureReason)
    }
}
