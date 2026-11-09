package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopPreviewRebindPolicyTest {

    @Test
    fun `prearmed encoder stop rebinds visible camera preview before request retarget`() {
        assertTrue(
            RecordingStopPreviewPolicy.shouldRebindVisiblePreviewTarget(
                keepPrearmedRecordSurface = true,
                previewSurfaceAvailable = true,
                nativeVisiblePreviewActive = false
            )
        )
    }

    @Test
    fun `native visible preview does not steal surface back to camera`() {
        assertFalse(
            RecordingStopPreviewPolicy.shouldRebindVisiblePreviewTarget(
                keepPrearmedRecordSurface = true,
                previewSurfaceAvailable = true,
                nativeVisiblePreviewActive = true
            )
        )
    }

    @Test
    fun `normal stop without prearmed record surface does not need rebind shortcut`() {
        assertFalse(
            RecordingStopPreviewPolicy.shouldRebindVisiblePreviewTarget(
                keepPrearmedRecordSurface = false,
                previewSurfaceAvailable = true,
                nativeVisiblePreviewActive = false
            )
        )
    }
}
