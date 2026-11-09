package com.neoproc.neoproccamx.ui.overlays

import android.graphics.Rect
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeAreaOverlayEngineTest {

    // 2.39:1 anamorphic scope — the only framing guide the app draws.
    private val scope = 2.39f

    @Test
    fun `scope guide is a full-width horizontal letterbox inside a 16 by 9 preview`() {
        val c = SafeAreaOverlayEngine.scopeCropFractions(scope, 16f / 9f)
        // Full width, cropped height (bars top/bottom) => a horizontal band, not a floating box.
        assertEquals("left", 0f, c[0], 1e-4f)
        assertEquals("right", 1f, c[2], 1e-4f)
        assertTrue("top bar > 0", c[1] > 0f)
        assertTrue("bottom bar < 1", c[3] < 1f)
        assertEquals("symmetric bars", c[1], 1f - c[3], 1e-4f)
        // The inscribed band's on-screen aspect equals the 2.39 scope.
        val bandAspect = (16f / 9f) * (c[2] - c[0]) / (c[3] - c[1])
        assertEquals("band is 2.39:1", scope, bandAspect, 1e-3f)
    }

    @Test
    fun `scope crop differs per preview aspect (4 by 3 crops more height than 16 by 9)`() {
        val wide = SafeAreaOverlayEngine.scopeCropFractions(scope, 16f / 9f)
        val classic = SafeAreaOverlayEngine.scopeCropFractions(scope, 4f / 3f)
        // Both full width, but a 4:3 frame keeps a shorter 2.39 band than 16:9 — proving the crop is
        // computed per real aspect, not one shared crop reused for both.
        assertEquals(1f, wide[2] - wide[0], 1e-4f)
        assertEquals(1f, classic[2] - classic[0], 1e-4f)
        assertTrue("4:3 crops more height than 16:9", (classic[3] - classic[1]) < (wide[3] - wide[1]))
    }

    @Test
    fun `oriented scope guide becomes the exact vertical equivalent in portrait`() {
        val frameAspect = 9f / 16f
        val c = SafeAreaOverlayEngine.orientedScopeCropFractions(scope, frameAspect)
        assertEquals("top", 0f, c[1], 1e-4f)
        assertEquals("bottom", 1f, c[3], 1e-4f)
        assertTrue("centered left margin", c[0] > 0f)
        assertEquals("symmetric sides", c[0], 1f - c[2], 1e-4f)
        val portraitGuideAspect = frameAspect * (c[2] - c[0]) / (c[3] - c[1])
        assertEquals("vertical guide is 1 to 2.39", 1f / scope, portraitGuideAspect, 1e-3f)
    }

    @Test
    fun `oriented scope guide remains horizontal in landscape`() {
        val frameAspect = 16f / 9f
        val c = SafeAreaOverlayEngine.orientedScopeCropFractions(scope, frameAspect)
        assertEquals("left", 0f, c[0], 1e-4f)
        assertEquals("right", 1f, c[2], 1e-4f)
        val landscapeGuideAspect = frameAspect * (c[2] - c[0]) / (c[3] - c[1])
        assertEquals("landscape guide is 2.39 to 1", scope, landscapeGuideAspect, 1e-3f)
    }

    @Test
    fun `scope guide is aspect accurate and centered for complete preview aspect matrix`() {
        val previewAspects = linkedMapOf(
            "4:3" to (4f / 3f),
            "16:9" to (16f / 9f),
            "3:4" to (3f / 4f),
            "9:16" to (9f / 16f)
        )
        previewAspects.forEach { (name, frameAspect) ->
            val crop = SafeAreaOverlayEngine.orientedScopeCropFractions(scope, frameAspect)
            assertTrue("$name left in bounds", crop[0] in 0f..1f)
            assertTrue("$name top in bounds", crop[1] in 0f..1f)
            assertTrue("$name right in bounds", crop[2] in 0f..1f)
            assertTrue("$name bottom in bounds", crop[3] in 0f..1f)
            assertTrue("$name has positive width", crop[2] > crop[0])
            assertTrue("$name has positive height", crop[3] > crop[1])

            val guideAspect = frameAspect * (crop[2] - crop[0]) / (crop[3] - crop[1])
            if (frameAspect >= 1f) {
                assertEquals("$name horizontal scope ratio", scope, guideAspect, 1e-3f)
                assertEquals("$name spans full width", 1f, crop[2] - crop[0], 1e-4f)
                assertEquals("$name vertically centered", crop[1], 1f - crop[3], 1e-4f)
            } else {
                assertEquals("$name vertical scope ratio", 1f / scope, guideAspect, 1e-3f)
                assertEquals("$name spans full height", 1f, crop[3] - crop[1], 1e-4f)
                assertEquals("$name horizontally centered", crop[0], 1f - crop[2], 1e-4f)
            }
        }
    }

    @Test
    fun `scope guide follows actual fitted preview bounds for all supported shapes`() {
        data class PreviewCase(
            val name: String,
            val viewWidth: Float,
            val viewHeight: Float,
            val previewAspect: Float
        )
        val cases = listOf(
            PreviewCase("4:3 landscape", 2400f, 1080f, 4f / 3f),
            PreviewCase("16:9 landscape", 2400f, 1080f, 16f / 9f),
            PreviewCase("3:4 portrait", 1080f, 2400f, 3f / 4f),
            PreviewCase("9:16 portrait", 1080f, 2400f, 9f / 16f)
        )
        cases.forEach { case ->
            val preview = FocusOverlayGeometry.resolvePreviewBounds(
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
                previewFillViewport = false,
                previewAspectRatio = case.previewAspect,
                previewSourceAspectRatio = null
            )
            val previewWidth = preview.right - preview.left
            val previewHeight = preview.bottom - preview.top
            assertEquals(
                "${case.name} fitted preview aspect",
                case.previewAspect,
                previewWidth / previewHeight,
                1e-3f
            )
            val crop = SafeAreaOverlayEngine.orientedScopeCropFractions(
                scopeRatio = scope,
                frameAspect = previewWidth / previewHeight
            )
            val guideLeft = preview.left + crop[0] * previewWidth
            val guideTop = preview.top + crop[1] * previewHeight
            val guideRight = preview.left + crop[2] * previewWidth
            val guideBottom = preview.top + crop[3] * previewHeight
            assertTrue("${case.name} guide left inside preview", guideLeft >= preview.left)
            assertTrue("${case.name} guide top inside preview", guideTop >= preview.top)
            assertTrue("${case.name} guide right inside preview", guideRight <= preview.right)
            assertTrue("${case.name} guide bottom inside preview", guideBottom <= preview.bottom)

            listOf(0, 90, 180, 270).forEach { rotation ->
                val label = requireNotNull(
                    SafeAreaOverlayEngine.resolveScopeLabelLayout(
                        guideLeft = guideLeft,
                        guideTop = guideTop,
                        guideRight = guideRight,
                        guideBottom = guideBottom,
                        deviceRotationDegrees = rotation,
                        desiredWidth = 100f,
                        desiredHeight = 24f,
                        edgeInset = 10f
                    )
                )
                val quarterTurn = rotation == 90 || rotation == 270
                val halfWidth = (if (quarterTurn) label.height else label.width) / 2f
                val halfHeight = (if (quarterTurn) label.width else label.height) / 2f
                assertTrue("${case.name}/$rotation label left", label.centerX - halfWidth >= guideLeft)
                assertTrue("${case.name}/$rotation label top", label.centerY - halfHeight >= guideTop)
                assertTrue("${case.name}/$rotation label right", label.centerX + halfWidth <= guideRight)
                assertTrue("${case.name}/$rotation label bottom", label.centerY + halfHeight <= guideBottom)
            }
        }
    }

    @Test
    fun `scope indication follows all four viewer top-left corners`() {
        val expected = listOf(
            0 to (SafeAreaLabelCorner.TOP_LEFT to 0f),
            90 to (SafeAreaLabelCorner.BOTTOM_LEFT to -90f),
            180 to (SafeAreaLabelCorner.BOTTOM_RIGHT to 180f),
            270 to (SafeAreaLabelCorner.TOP_RIGHT to 90f)
        )
        expected.forEach { (deviceRotation, expectation) ->
            val layout = requireNotNull(
                SafeAreaOverlayEngine.resolveScopeLabelLayout(
                    guideLeft = 100f,
                    guideTop = 20f,
                    guideRight = 500f,
                    guideBottom = 980f,
                    deviceRotationDegrees = deviceRotation,
                    desiredWidth = 100f,
                    desiredHeight = 24f,
                    edgeInset = 10f
                )
            )
            assertEquals(expectation.first, layout.corner)
            assertEquals(expectation.second, layout.rotationDegrees, 1e-4f)
        }
    }

    @Test
    fun `scope indication remains completely inside guide for every rotation`() {
        listOf(0, 90, 180, 270).forEach { deviceRotation ->
            val layout = requireNotNull(
                SafeAreaOverlayEngine.resolveScopeLabelLayout(
                    guideLeft = 100f,
                    guideTop = 20f,
                    guideRight = 500f,
                    guideBottom = 980f,
                    deviceRotationDegrees = deviceRotation,
                    desiredWidth = 100f,
                    desiredHeight = 24f,
                    edgeInset = 10f
                )
            )
            val quarterTurn = deviceRotation == 90 || deviceRotation == 270
            val visualHalfWidth = (if (quarterTurn) layout.height else layout.width) / 2f
            val visualHalfHeight = (if (quarterTurn) layout.width else layout.height) / 2f
            assertTrue(layout.centerX - visualHalfWidth >= 100f)
            assertTrue(layout.centerX + visualHalfWidth <= 500f)
            assertTrue(layout.centerY - visualHalfHeight >= 20f)
            assertTrue(layout.centerY + visualHalfHeight <= 980f)
        }
    }

    @Test
    fun `default safe area guides do not draw hot pink center social frame`() {
        val overlays = SafeAreaOverlayEngine.computeOverlays(
            geometry = FrameGeometry(
                sensorOrientationDegrees = 90,
                deviceRotationDegrees = 0,
                finalRotationDegrees = 90,
                lensFacing = 0,
                sensorAspectRatio = 4f / 3f,
                outputAspectRatio = 4f / 3f,
                cropRegion = Rect(0, 0, 4000, 3000),
                normalizedCropRegion = NormalizedCropRegion(0f, 0f, 1f, 1f)
            ),
            displayWidth = 1080,
            displayHeight = 1920
        )

        assertFalse(overlays.guides.any { it.tintColor == SafeAreaGuide.SOCIAL_GUIDE_COLOR })
    }
}
