package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPreviewBindingPolicyTest {

    @Test
    fun `full sensor recording uses direct preview binding during capture`() {
        assertFalse(
            RecordingPreviewBindingPolicy.resolvePreferNativePreviewOverride(
                fullSensorReadoutForced = true
            )
        )
    }

    @Test
    fun `cropped processed recording keeps direct preview until native presentation is proven`() {
        assertFalse(
            RecordingPreviewBindingPolicy.resolvePreferNativePreviewOverride(
                fullSensorReadoutForced = false
            )
        )
    }
}
