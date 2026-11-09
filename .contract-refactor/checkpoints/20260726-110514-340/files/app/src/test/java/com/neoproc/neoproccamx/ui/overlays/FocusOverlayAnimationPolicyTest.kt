package com.neoproc.neoproccamx.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusOverlayAnimationPolicyTest {

    @Test
    fun resolve_startsExpandedAndFadesIntoReferenceReticle() {
        val start = FocusOverlayAnimationPolicy.resolve(0f)
        val end = FocusOverlayAnimationPolicy.resolve(1f)

        assertTrue(start.scale > end.scale)
        assertTrue(start.alpha < end.alpha)
        assertEquals(1.0f, end.scale, 0.001f)
        assertEquals(1.0f, end.alpha, 0.001f)
    }

    @Test
    fun resolve_clampsProgressIntoAnimationRange() {
        assertEquals(
            FocusOverlayAnimationPolicy.resolve(0f),
            FocusOverlayAnimationPolicy.resolve(-5f)
        )
        assertEquals(
            FocusOverlayAnimationPolicy.resolve(1f),
            FocusOverlayAnimationPolicy.resolve(5f)
        )
    }
}
