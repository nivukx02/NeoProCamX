package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraDockLayoutPolicyTest {

    @Test
    fun `square preview anchors lower docks after the complete square frame`() {
        assertEquals(
            1_740f,
            CameraDockLayoutPolicy.lowerDockAnchorBottom(
                previewBottomPx = 1_740f,
                fourThreeBottomPx = 1_255f,
                targetAspect = 1f
            ),
            0.001f
        )
    }

    @Test
    fun `wider previews retain established four-three dock position`() {
        assertEquals(
            1_255f,
            CameraDockLayoutPolicy.lowerDockAnchorBottom(
                previewBottomPx = 1_504f,
                fourThreeBottomPx = 1_255f,
                targetAspect = 16f / 9f
            ),
            0.001f
        )
    }

    @Test
    fun `docks anchor to the fitted preview edges with approved spacing`() {
        val plan = CameraDockLayoutPolicy.resolveAnchoredTranslations(
            previewTopPx = 186.6667f,
            previewBottomPx = 1520f,
            statusTopPx = 16f,
            statusHeightPx = 28f,
            modeTopPx = 1770f,
            modeHeightPx = 52f,
            captureTopPx = 1838f,
            captureHeightPx = 86f,
            viewportHeightPx = 2000f,
            statusPreviewOverlapPx = 8f,
            previewModeGapPx = 24f,
            modeCaptureGapPx = 8f
        )

        assertEquals(150.6667f, plan.statusTranslationY, 0.001f)
        assertEquals(-226f, plan.modeTranslationY, 0.001f)
        assertEquals(-234f, plan.captureTranslationY, 0.001f)
    }

    @Test
    fun `lower dock stack shifts upward together when it would exceed viewport`() {
        val plan = CameraDockLayoutPolicy.resolveAnchoredTranslations(
            previewTopPx = 100f,
            previewBottomPx = 1800f,
            statusTopPx = 10f,
            statusHeightPx = 30f,
            modeTopPx = 1700f,
            modeHeightPx = 52f,
            captureTopPx = 1760f,
            captureHeightPx = 150f,
            viewportHeightPx = 2000f,
            statusPreviewOverlapPx = 8f,
            previewModeGapPx = 24f,
            modeCaptureGapPx = 8f
        )

        assertEquals(68f, plan.statusTranslationY, 0.001f)
        assertEquals(90f, plan.modeTranslationY, 0.001f)
        assertEquals(90f, plan.captureTranslationY, 0.001f)
    }

    @Test
    fun `idle controls remain inside panel below visible mode rail`() {
        assertEquals(
            0f,
            CameraDockLayoutPolicy.captureRowTranslationY(
                controlsVisible = true,
                modeRailHeightPx = 52f
            ),
            0.001f
        )
    }

    @Test
    fun `recording controls remain inside panel below visible mode rail`() {
        assertEquals(
            0f,
            CameraDockLayoutPolicy.captureRowTranslationY(
                controlsVisible = false,
                modeRailHeightPx = 52f
            ),
            0.001f
        )
    }

    @Test
    fun `shutter row does not jump between idle and recording`() {
        val idleTranslation = CameraDockLayoutPolicy.captureRowTranslationY(
            controlsVisible = true,
            modeRailHeightPx = 52f
        )
        val recordingTranslation = CameraDockLayoutPolicy.captureRowTranslationY(
            controlsVisible = false,
            modeRailHeightPx = 52f
        )

        assertEquals(idleTranslation, recordingTranslation, 0.001f)
    }

    @Test
    fun `idle mode rail occupies its reserved panel row`() {
        assertEquals(
            0f,
            CameraDockLayoutPolicy.modeRailTranslationY(
                controlsVisible = true,
                modeRailHeightPx = 52f
            ),
            0.001f
        )
    }

    @Test
    fun `recording keeps mode rail in its reserved panel row`() {
        assertEquals(
            0f,
            CameraDockLayoutPolicy.modeRailTranslationY(
                controlsVisible = false,
                modeRailHeightPx = 52f
            ),
            0.001f
        )
    }
}
