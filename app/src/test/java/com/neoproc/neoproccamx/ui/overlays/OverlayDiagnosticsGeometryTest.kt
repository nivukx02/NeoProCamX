package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDiagnosticsGeometryTest {

    @Test
    fun gridLines_resolvesThirdsLayoutInsidePreviewRect() {
        val lines = OverlayDiagnosticsGeometry.gridLines(
            mode = GridMode.THIRDS,
            left = 100f,
            top = 200f,
            right = 400f,
            bottom = 800f
        )

        assertEquals(4, lines.size)
        assertEquals(200f, lines[0].startX, 0.0001f)
        assertEquals(300f, lines[1].startX, 0.0001f)
        assertEquals(400f, lines[2].startY, 0.0001f)
        assertEquals(600f, lines[3].startY, 0.0001f)
    }

    @Test
    fun mapNormalizedRect_mapsIntoContainerCoordinates() {
        val mapped = OverlayDiagnosticsGeometry.mapNormalizedRect(
            srcLeft = 0.25f,
            srcTop = 0.10f,
            srcRight = 0.75f,
            srcBottom = 0.60f,
            containerLeft = 100f,
            containerTop = 200f,
            containerRight = 500f,
            containerBottom = 1000f
        )

        assertEquals(200f, mapped.left, 0.0001f)
        assertEquals(280f, mapped.top, 0.0001f)
        assertEquals(400f, mapped.right, 0.0001f)
        assertEquals(680f, mapped.bottom, 0.0001f)
    }

    @Test
    fun formatDuration_handlesMinuteAndHourCases() {
        assertEquals("02:05", OverlayDiagnosticsGeometry.formatDuration(125_000L))
        assertEquals("1:01:01", OverlayDiagnosticsGeometry.formatDuration(3_661_000L))
        assertTrue(
            OverlayDiagnosticsGeometry.gridLines(
                mode = GridMode.NONE,
                left = 0f,
                top = 0f,
                right = 0f,
                bottom = 0f
            ).isEmpty()
        )
    }

    @Test
    fun gridMode_noneFallsBackToThirdsInStrictMode() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            assertEquals(GridMode.NONE, GridMode.NONE.resolveForStrictHighEnd())
            assertEquals(GridMode.THIRDS, GridMode.THIRDS.resolveForStrictHighEnd())

            HighEndQualityPolicy.strictHighEndOnly = true
            assertEquals(GridMode.THIRDS, GridMode.NONE.resolveForStrictHighEnd())
            assertEquals(GridMode.THIRDS, GridMode.THIRDS.resolveForStrictHighEnd())
            assertEquals(GridMode.CENTER, GridMode.CENTER.resolveForStrictHighEnd())
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun diagnosticMaskCorners_rotateIntoPreviewOrientation() {
        val corners = OverlayDiagnosticsGeometry.diagnosticMaskCorners(
            left = 100f,
            top = 200f,
            right = 500f,
            bottom = 1000f,
            rotationDegrees = 90,
            mirrored = false
        )

        assertEquals(500f, corners[0], 0.0001f)
        assertEquals(200f, corners[1], 0.0001f)
        assertEquals(500f, corners[2], 0.0001f)
        assertEquals(1000f, corners[3], 0.0001f)
        assertEquals(100f, corners[4], 0.0001f)
        assertEquals(1000f, corners[5], 0.0001f)
        assertEquals(100f, corners[6], 0.0001f)
        assertEquals(200f, corners[7], 0.0001f)
    }

    @Test
    fun diagnosticMaskCorners_applyMirrorAfterRotation() {
        val corners = OverlayDiagnosticsGeometry.diagnosticMaskCorners(
            left = 0f,
            top = 0f,
            right = 400f,
            bottom = 800f,
            rotationDegrees = 90,
            mirrored = true
        )

        assertEquals(0f, corners[0], 0.0001f)
        assertEquals(0f, corners[1], 0.0001f)
        assertEquals(0f, corners[2], 0.0001f)
        assertEquals(800f, corners[3], 0.0001f)
        assertEquals(400f, corners[4], 0.0001f)
        assertEquals(800f, corners[5], 0.0001f)
        assertEquals(400f, corners[6], 0.0001f)
        assertEquals(0f, corners[7], 0.0001f)
    }
}
