package com.neoproc.neoproccamx.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectMaskPreviewVisualizationPolicyTest {
    @Test
    fun `production default cannot visualize a subject mask`() {
        assertFalse(SubjectMaskPreviewVisualizationPolicy.DEFAULT_ENABLED)
        assertEquals(
            0f,
            SubjectMaskPreviewVisualizationPolicy.resolveLevel(
                visualizationEnabled = SubjectMaskPreviewVisualizationPolicy.DEFAULT_ENABLED,
                cleanHudEnabled = false,
                requestedLevel = 1f
            ),
            0f
        )
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.shouldDraw(
                visualizationEnabled = SubjectMaskPreviewVisualizationPolicy.DEFAULT_ENABLED,
                cleanHudEnabled = false,
                level = 1f,
                bitmapPresent = true
            )
        )
    }

    @Test
    fun `clean camera hud rejects bitmap allocation and drawing even after opt in`() {
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.canAcceptMask(
                visualizationEnabled = true,
                cleanHudEnabled = true,
                width = 256,
                height = 144,
                payloadBytes = 256 * 144
            )
        )
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.shouldDraw(
                visualizationEnabled = true,
                cleanHudEnabled = true,
                level = 1f,
                bitmapPresent = true
            )
        )
    }

    @Test
    fun `diagnostic visualization requires explicit opt in fresh valid mask and positive level`() {
        assertTrue(
            SubjectMaskPreviewVisualizationPolicy.canAcceptMask(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                width = 256,
                height = 144,
                payloadBytes = 256 * 144
            )
        )
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.canAcceptMask(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                width = 256,
                height = 144,
                payloadBytes = (256 * 144) - 1
            )
        )
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.shouldDraw(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                level = 0f,
                bitmapPresent = true
            )
        )
        assertFalse(
            SubjectMaskPreviewVisualizationPolicy.shouldDraw(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                level = 1f,
                bitmapPresent = false
            )
        )
        assertTrue(
            SubjectMaskPreviewVisualizationPolicy.shouldDraw(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                level = 0.5f,
                bitmapPresent = true
            )
        )
    }

    @Test
    fun `invalid and non finite diagnostic levels fail closed`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertEquals(
                0f,
                SubjectMaskPreviewVisualizationPolicy.resolveLevel(
                    visualizationEnabled = true,
                    cleanHudEnabled = false,
                    requestedLevel = invalid
                ),
                0f
            )
        }
        assertEquals(
            1f,
            SubjectMaskPreviewVisualizationPolicy.resolveLevel(
                visualizationEnabled = true,
                cleanHudEnabled = false,
                requestedLevel = 4f
            ),
            0f
        )
    }
}
