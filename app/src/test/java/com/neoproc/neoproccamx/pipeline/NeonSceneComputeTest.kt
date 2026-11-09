package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class NeonSceneComputeTest {

    @Test
    fun `histogram length is clamped to byte array size before native call`() {
        assertEquals(4, NeonSceneCompute.sanitizeHistogramLength(requestedLength = 12, availableBytes = 4))
    }

    @Test
    fun `histogram length rejects non-positive requests and empty arrays`() {
        assertEquals(0, NeonSceneCompute.sanitizeHistogramLength(requestedLength = 0, availableBytes = 4))
        assertEquals(0, NeonSceneCompute.sanitizeHistogramLength(requestedLength = -8, availableBytes = 4))
        assertEquals(0, NeonSceneCompute.sanitizeHistogramLength(requestedLength = 8, availableBytes = 0))
    }
}
