package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewContentBoundsResolverTest {

    @Test
    fun resolve_returnsFitBoundsWhenFillViewportIsOff() {
        val bounds = PreviewContentBoundsResolver.resolve(
            viewWidth = 1000f,
            viewHeight = 2000f,
            targetAspect = 16f / 9f,
            fillViewport = false
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(718.75f, bounds.top, 0.0001f)
        assertEquals(1000f, bounds.right, 0.0001f)
        assertEquals(1281.25f, bounds.bottom, 0.0001f)
    }

    @Test
    fun resolve_returnsFillBoundsWhenFillViewportIsOn() {
        val bounds = PreviewContentBoundsResolver.resolve(
            viewWidth = 1000f,
            viewHeight = 2000f,
            targetAspect = 16f / 9f,
            fillViewport = true
        )

        assertEquals(-1277.7778f, bounds.left, 0.001f)
        assertEquals(0f, bounds.top, 0.0001f)
        assertEquals(2277.7778f, bounds.right, 0.001f)
        assertEquals(2000f, bounds.bottom, 0.0001f)
    }

    @Test
    fun resolve_biasesFourThreeFitBoundsToReduceTopSensorPlaceholderGap() {
        val bounds = PreviewContentBoundsResolver.resolve(
            viewWidth = 1000f,
            viewHeight = 2000f,
            targetAspect = 3f / 4f,
            fillViewport = false
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(186.6667f, bounds.top, 0.001f)
        assertEquals(1000f, bounds.right, 0.0001f)
        assertEquals(1520f, bounds.bottom, 0.001f)
    }
}
