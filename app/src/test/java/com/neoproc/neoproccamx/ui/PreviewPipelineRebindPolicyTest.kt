package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPipelineRebindPolicyTest {
    @Test
    fun `forced session refresh does not require an unchanged preview rebind`() {
        assertFalse(PreviewPipelineRebindPolicy.shouldRebind(false, false, false))
    }

    @Test
    fun `size owner and handoff transitions each retain the binding path`() {
        assertTrue(PreviewPipelineRebindPolicy.shouldRebind(true, false, false))
        assertTrue(PreviewPipelineRebindPolicy.shouldRebind(false, true, false))
        assertTrue(PreviewPipelineRebindPolicy.shouldRebind(false, false, true))
    }
}
