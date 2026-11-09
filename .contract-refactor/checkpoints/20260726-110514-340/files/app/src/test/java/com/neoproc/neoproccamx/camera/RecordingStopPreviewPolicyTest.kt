package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopPreviewPolicyTest {

    @Test
    fun `preview restart waits until record surface is detached`() {
        assertFalse(
            RecordingStopPreviewPolicy.shouldRestartPreviewImmediately(
                recordSurfaceAttached = true
            )
        )
    }

    @Test
    fun `preview restart happens immediately when no record surface is attached`() {
        assertTrue(
            RecordingStopPreviewPolicy.shouldRestartPreviewImmediately(
                recordSurfaceAttached = false
            )
        )
    }
}
