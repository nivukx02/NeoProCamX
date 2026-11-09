package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceAdjustBarPolicyTest {

    @Test
    fun progressFraction_clampsAndNormalizesProgress() {
        assertEquals(0f, ReferenceAdjustBarPolicy.progressFraction(-2, 10), 0.0001f)
        assertEquals(0.5f, ReferenceAdjustBarPolicy.progressFraction(5, 10), 0.0001f)
        assertEquals(1f, ReferenceAdjustBarPolicy.progressFraction(50, 10), 0.0001f)
    }

    @Test
    fun tickFractions_areEvenlySpacedIncludingEdges() {
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), ReferenceAdjustBarPolicy.tickFractions(5))
    }

    @Test
    fun tickFractions_singleTickStaysCentered() {
        assertEquals(listOf(0.5f), ReferenceAdjustBarPolicy.tickFractions(1))
    }

    @Test
    fun lensRingAngle_mapsProgressAcrossLensArc() {
        assertEquals(205f, ReferenceAdjustBarPolicy.lensRingAngleDegrees(-4, 10), 0.0001f)
        assertEquals(270f, ReferenceAdjustBarPolicy.lensRingAngleDegrees(5, 10), 0.0001f)
        assertEquals(335f, ReferenceAdjustBarPolicy.lensRingAngleDegrees(30, 10), 0.0001f)
    }

    @Test
    fun progressForTouchX_mapsFullRingWidthIntoProgress() {
        assertEquals(0, ReferenceAdjustBarPolicy.progressForTouchX(0f, 100, 10, 10, 20))
        assertEquals(10, ReferenceAdjustBarPolicy.progressForTouchX(50f, 100, 10, 10, 20))
        assertEquals(20, ReferenceAdjustBarPolicy.progressForTouchX(100f, 100, 10, 10, 20))
    }

    @Test
    fun progressForTouchX_handlesCollapsedWidthAndDisabledRange() {
        assertEquals(0, ReferenceAdjustBarPolicy.progressForTouchX(40f, 0, 10, 10, 20))
        assertEquals(0, ReferenceAdjustBarPolicy.progressForTouchX(40f, 100, 10, 10, 0))
    }

    @Test
    fun progressForDragDelta_keepsSelectorFixedAndRotatesValuesUnderIt() {
        assertEquals(12, ReferenceAdjustBarPolicy.progressForDragDelta(10, -20f, 10f, 20))
        assertEquals(8, ReferenceAdjustBarPolicy.progressForDragDelta(10, 20f, 10f, 20))
        assertEquals(20, ReferenceAdjustBarPolicy.progressForDragDelta(19, -100f, 10f, 20))
        assertEquals(0, ReferenceAdjustBarPolicy.progressForDragDelta(1, 100f, 10f, 20))
    }

    @Test
    fun continuousTickOffset_preservesSubTickMotionForFrameSmoothness() {
        assertEquals(-4f, ReferenceAdjustBarPolicy.continuousTickOffsetPx(10, 10, -4f, 10f), 0.0001f)
        assertEquals(4f, ReferenceAdjustBarPolicy.continuousTickOffsetPx(10, 11, -6f, 10f), 0.0001f)
        assertEquals(5f, ReferenceAdjustBarPolicy.continuousTickOffsetPx(0, 0, 50f, 10f), 0.0001f)
    }
}
