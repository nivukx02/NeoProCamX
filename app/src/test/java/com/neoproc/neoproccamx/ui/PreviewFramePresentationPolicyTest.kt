package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.LiveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFramePresentationPolicyTest {
    @Test
    fun `positive capture timestamp is forwarded to preview pacing`() {
        val result = LiveResult(
            iso = 100,
            exposureNs = 10_000_000L,
            afState = 1,
            aeState = 2,
            awbState = 3,
            focusDist = 0.5f,
            timestampNs = 123_456_789L
        )

        assertEquals(123_456_789L, PreviewFramePresentationPolicy.timestampForPreviewFrame(result))
    }

    @Test
    fun `missing capture timestamp does not emit fake preview frame`() {
        val missing = LiveResult(
            iso = 100,
            exposureNs = 10_000_000L,
            afState = 1,
            aeState = 2,
            awbState = 3,
            focusDist = 0.5f,
            timestampNs = 0L
        )
        val negative = missing.copy(timestampNs = -1L)

        assertNull(PreviewFramePresentationPolicy.timestampForPreviewFrame(null))
        assertNull(PreviewFramePresentationPolicy.timestampForPreviewFrame(missing))
        assertNull(PreviewFramePresentationPolicy.timestampForPreviewFrame(negative))
    }
}
