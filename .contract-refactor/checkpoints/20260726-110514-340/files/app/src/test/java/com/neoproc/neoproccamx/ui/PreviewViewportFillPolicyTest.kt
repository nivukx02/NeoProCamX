package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewViewportFillPolicyTest {

    @Test
    fun shouldFillViewport_returnsFalse_forVideoModeCrops() {
        assertTrue(
            !PreviewViewportFillPolicy.shouldFillViewport(
                appMode = AppMode.VIDEO,
                previewAspectRatio = PreviewAspectRatio.AUTO
            )
        )
    }

    @Test
    fun shouldFillViewport_returnsFalse_forProModeCrops() {
        assertTrue(
            !PreviewViewportFillPolicy.shouldFillViewport(
                appMode = AppMode.PRO,
                previewAspectRatio = PreviewAspectRatio.AUTO
            )
        )
    }
}
