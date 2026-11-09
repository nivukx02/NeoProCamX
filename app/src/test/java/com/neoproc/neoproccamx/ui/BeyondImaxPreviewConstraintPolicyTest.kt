package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeyondImaxPreviewConstraintPolicyTest {

    @Test
    fun `raw mode preserves selected sixteen nine preview aspect`() {
        val constraints = BeyondImaxPreviewConstraintPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            currentAspect = PreviewAspectRatio.SIXTEEN_NINE
        )

        assertEquals(PreviewAspectRatio.SIXTEEN_NINE, constraints.previewAspect)
        assertTrue(constraints.resetZoomToOne)
    }

    @Test
    fun `non raw mode does not apply beyond imax zoom constraint`() {
        val constraints = BeyondImaxPreviewConstraintPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            currentAspect = PreviewAspectRatio.SIXTEEN_NINE
        )

        assertEquals(PreviewAspectRatio.SIXTEEN_NINE, constraints.previewAspect)
        assertFalse(constraints.resetZoomToOne)
    }
}
