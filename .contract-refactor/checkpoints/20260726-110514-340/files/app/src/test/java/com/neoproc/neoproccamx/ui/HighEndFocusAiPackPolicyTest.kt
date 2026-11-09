package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighEndFocusAiPackPolicyTest {

    @Test
    fun `focus ai pack keeps hidden ai and face eye defaults off while preserving noise features`() {
        val pack = HighEndFocusAiPackPolicy.resolve()

        assertFalse(pack.aiEnhancementEnabled)
        assertTrue(pack.noiseReductionEnabled)
        assertFalse(pack.faceDetectionEnabled)
        assertFalse(pack.eyeAutofocusEnabled)
    }
}
