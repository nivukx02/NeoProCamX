package com.neoproc.neoproccamx.ui

import android.util.Size
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingOutputAspectPolicyTest {

    @Test
    fun `selected aspect stays unset when encoder size is unavailable`() {
        val override = RecordingOutputAspectPolicy.resolveOverride(
            previewAspectRatio = PreviewAspectRatio.AUTO,
            encoderSize = null
        )

        assertNull(override)
    }

    @Test
    fun `explicit aspect ratios stay sticky during recording`() {
        val override = RecordingOutputAspectPolicy.resolveOverride(
            previewAspectRatio = PreviewAspectRatio.AUTO,
            encoderSize = Size(4000, 3000)
        )

        assertNull(override)
    }
}
