package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRecordingWorkloadPolicyTest {
    @Test
    fun `idle preview retains the complete monitor enhancement graph`() {
        val decision = PreviewRecordingWorkloadPolicy.resolve(recordingActive = false)

        assertTrue(decision.waitForExactMetadata)
        assertTrue(decision.applySpatialEnhancement)
        assertTrue(decision.applyTemporalFusion)
        assertTrue(decision.applyWhiteBalance)
        assertTrue(decision.applyCreativeLut)
        assertTrue(decision.presentEveryAvailableFrame)
    }

    @Test
    fun `recording priority preserves colour and cadence while isolating multipass monitor work`() {
        val decision = PreviewRecordingWorkloadPolicy.resolve(recordingActive = true)

        assertFalse(decision.waitForExactMetadata)
        assertFalse(decision.applySpatialEnhancement)
        assertFalse(decision.applyTemporalFusion)
        assertTrue(decision.applyWhiteBalance)
        assertTrue(decision.applyCreativeLut)
        assertTrue(decision.presentEveryAvailableFrame)
    }
}
