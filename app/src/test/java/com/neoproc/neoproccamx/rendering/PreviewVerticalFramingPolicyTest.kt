package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewVerticalFramingPolicyTest {

    @Test
    fun `four three fit preview reduces top sensor placeholder gap`() {
        val centered = PreviewTransformMath.RectBounds(
            left = 0f,
            top = 555f,
            right = 1080f,
            bottom = 1365f
        )

        val shifted = PreviewVerticalFramingPolicy.apply(
            bounds = centered,
            viewHeight = 1920f,
            targetAspect = 4f / 3f,
            fillViewport = false
        )

        assertEquals(310.8f, shifted.top, 0.001f)
        assertEquals(1120.8f, shifted.bottom, 0.001f)
        assertEquals(centered.height, shifted.height, 0.001f)
    }

    @Test
    fun `sixteen nine preview remains centered`() {
        val centered = PreviewTransformMath.RectBounds(0f, 656.25f, 1080f, 1263.75f)

        val shifted = PreviewVerticalFramingPolicy.apply(
            bounds = centered,
            viewHeight = 1920f,
            targetAspect = 16f / 9f,
            fillViewport = false
        )

        assertEquals(centered, shifted)
    }

    @Test
    fun `portrait three four preview reduces top sensor placeholder gap`() {
        val centered = PreviewTransformMath.RectBounds(
            left = 0f,
            top = 480f,
            right = 1080f,
            bottom = 1920f
        )

        val shifted = PreviewVerticalFramingPolicy.apply(
            bounds = centered,
            viewHeight = 2400f,
            targetAspect = 3f / 4f,
            fillViewport = false
        )

        assertEquals(268.8f, shifted.top, 0.001f)
        assertEquals(1708.8f, shifted.bottom, 0.001f)
    }
}
