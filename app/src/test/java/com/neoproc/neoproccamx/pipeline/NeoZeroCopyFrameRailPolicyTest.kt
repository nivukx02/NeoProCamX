package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class NeoZeroCopyFrameRailPolicyTest {

    @Test
    fun `camera master stays on immediate zero copy rail when live metadata is disabled`() {
        assertEquals(
            NeoFrameRailSubmissionRoute.IMMEDIATE_AHARDWAREBUFFER,
            NeoZeroCopyFrameRailPolicy.route(liveExactMetadataProcessingEnabled = false)
        )
    }

    @Test
    fun `exact live metadata retains bounded overlap rail`() {
        assertEquals(
            NeoFrameRailSubmissionRoute.BOUNDED_METADATA_OVERLAP,
            NeoZeroCopyFrameRailPolicy.route(liveExactMetadataProcessingEnabled = true)
        )
    }
}
