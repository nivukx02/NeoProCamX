package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FocusOverlayGeometryTest {

    @Test
    fun resolvePreviewRect_matchesRendererFourThreeVerticalFraming() {
        val bounds = FocusOverlayGeometry.resolvePreviewBounds(
            viewWidth = 1000f,
            viewHeight = 2000f,
            previewFillViewport = false,
            previewAspectRatio = 3f / 4f,
            previewSourceAspectRatio = null
        )

        assertEquals(0f, bounds.left, 0.0001f)
        assertEquals(186.6667f, bounds.top, 0.001f)
        assertEquals(1000f, bounds.right, 0.0001f)
        assertEquals(1520f, bounds.bottom, 0.001f)
    }

    @Test
    fun focusEvValueForTouch_mapsSliderTouchIntoExposureRange() {
        val state = FocusOverlayState(
            visible = true,
            locked = true,
            adjustmentVisible = true,
            exposureValue = 0,
            exposureMin = -4,
            exposureMax = 4
        )
        val layout = FocusOverlayGeometry.resolveLayout(
            viewWidth = 1000f,
            viewHeight = 2000f,
            previewFillViewport = false,
            previewAspectRatio = 9f / 16f,
            previewSourceAspectRatio = 3f / 4f,
            state = state,
            density = 1f
        )

        val slider = layout.sliderRect
        assertNotNull(slider)
        val value = FocusOverlayGeometry.focusEvValueForTouch(
            x = slider!!.centerX(),
            y = slider.top,
            layout = layout,
            state = state,
            density = 1f
        )

        assertEquals(4, value)
    }

    @Test
    fun focusEvValueForTouch_ignoresTouchesFarAboveOrBelowTheSliderBar() {
        // MIS-TOUCH GUARD: a touch aligned with the slider's X but well outside its vertical
        // extent must no longer be read as an EV adjustment (previously it clamped to min/max).
        val state = FocusOverlayState(
            visible = true,
            locked = true,
            adjustmentVisible = true,
            exposureValue = 0,
            exposureMin = -4,
            exposureMax = 4
        )
        val layout = FocusOverlayGeometry.resolveLayout(
            viewWidth = 1000f,
            viewHeight = 2000f,
            previewFillViewport = false,
            previewAspectRatio = 9f / 16f,
            previewSourceAspectRatio = 3f / 4f,
            state = state,
            density = 1f
        )
        val slider = layout.sliderRect
        assertNotNull(slider)

        // Far above and far below the bar (within its X column) → not an EV touch.
        assertNull(
            FocusOverlayGeometry.focusEvValueForTouch(
                x = slider!!.centerX(),
                y = slider.top - 500f,
                layout = layout,
                state = state,
                density = 1f
            )
        )
        assertNull(
            FocusOverlayGeometry.focusEvValueForTouch(
                x = slider.centerX(),
                y = slider.bottom + 500f,
                layout = layout,
                state = state,
                density = 1f
            )
        )
        // A touch on the bar itself still maps to a valid EV value.
        assertNotNull(
            FocusOverlayGeometry.focusEvValueForTouch(
                x = slider.centerX(),
                y = slider.centerY(),
                layout = layout,
                state = state,
                density = 1f
            )
        )
    }

    @Test
    fun sliderRect_movesLeftWhenFocusIsNearRightEdge() {
        val left = FocusOverlayGeometry.resolveSliderLeft(
            previewLeft = 0f,
            previewRight = 1000f,
            centerX = 960f,
            focusRadiusPx = 26f,
            sliderWidth = 16f,
            edgePadding = 8f,
            offset = 10f
        )

        assertTrue(left + 16f <= 992f)
        assertTrue(left + 8f < 960f)
    }

    @Test
    fun focusEvSliderUsesCompactNativeCameraSizing() {
        val sliderSize = FocusOverlayGeometry.resolveSliderSize(density = 1f)

        assertTrue("slider width=${sliderSize.widthPx}", sliderSize.widthPx <= 12f)
        assertTrue("slider height=${sliderSize.heightPx}", sliderSize.heightPx in 56f..66f)
    }

    @Test
    fun tapFocusReticleUsesReferenceCameraBoxSize() {
        val state = FocusOverlayState(
            visible = true,
            adjustmentVisible = true
        )
        val layout = FocusOverlayGeometry.resolveLayout(
            viewWidth = 1080f,
            viewHeight = 2400f,
            previewFillViewport = true,
            previewAspectRatio = null,
            previewSourceAspectRatio = null,
            state = state,
            density = 3f
        )

        val diameterPx = layout.focusRadiusPx * 2f

        assertTrue("focus diameter=$diameterPx", diameterPx in 190f..202f)
    }

    @Test
    fun isFocusUiHit_respectsResolvedLayout() {
        val state = FocusOverlayState(visible = true, normalizedX = 0.5f, normalizedY = 0.5f)
        val layout = FocusOverlayGeometry.resolveLayout(
            viewWidth = 1000f,
            viewHeight = 2000f,
            previewFillViewport = false,
            previewAspectRatio = 9f / 16f,
            previewSourceAspectRatio = 3f / 4f,
            state = state,
            density = 1f
        )

        assertTrue(
            FocusOverlayGeometry.isFocusUiHit(
                x = layout.focusCenter.x,
                y = layout.focusCenter.y,
                layout = layout,
                state = state
            )
        )
        assertFalse(
            FocusOverlayGeometry.isFocusUiHit(
                x = layout.focusCenter.x + 80f,
                y = layout.focusCenter.y + 80f,
                layout = layout,
                state = state
            )
        )
    }

    @Test
    fun normalizeTouchPointOrNull_ignoresTapsOutsidePreviewRect() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/overlays/FocusOverlayGeometry.kt").readText()

        assertTrue(source.contains("fun normalizeTouchPointOrNull"))
        assertTrue(source.contains("x < previewRect.left"))
        assertTrue(source.contains("x > previewRect.right"))
        assertTrue(source.contains("y < previewRect.top"))
        assertTrue(source.contains("y > previewRect.bottom"))
        assertTrue(source.contains("return normalizeTouchPoint(x, y, previewRect)"))
    }

    @Test
    fun isFocusUiHit_strictModeUsesTighterHitSlop() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val state = FocusOverlayState(visible = true, normalizedX = 0.5f, normalizedY = 0.5f)
            val layout = FocusOverlayGeometry.resolveLayout(
                viewWidth = 1000f,
                viewHeight = 2000f,
                previewFillViewport = false,
                previewAspectRatio = 9f / 16f,
                previewSourceAspectRatio = 3f / 4f,
                state = state,
                density = 1f
            )
            val probeX = layout.focusCenter.x + layout.focusRadiusPx * 1.32f
            val probeY = layout.focusCenter.y

            assertTrue(
                FocusOverlayGeometry.isFocusUiHit(
                    x = probeX,
                    y = probeY,
                    layout = layout,
                    state = state
                )
            )

            HighEndQualityPolicy.strictHighEndOnly = true

            assertFalse(
                FocusOverlayGeometry.isFocusUiHit(
                    x = probeX,
                    y = probeY,
                    layout = layout,
                    state = state
                )
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
