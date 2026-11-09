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
    fun `scope guide stays a horizontal band even in a tall portrait preview`() {
        // Portrait preview aspect (9:16). Option A: the scope is still a horizontal letterbox
        // (full width, short height), never a vertical strip.
        val c = SafeAreaOverlayEngine.scopeCropFractions(scope, 9f / 16f)
        assertEquals("left", 0f, c[0], 1e-4f)
        assertEquals("right", 1f, c[2], 1e-4f)
        assertTrue("short band", (c[3] - c[1]) < 0.5f)
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
