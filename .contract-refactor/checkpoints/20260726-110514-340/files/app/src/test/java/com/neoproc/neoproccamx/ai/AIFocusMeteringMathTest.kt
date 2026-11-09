package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIFocusMeteringMathTest {

    @Test
    fun smoothBounds_usesSubjectSpecificAlpha() {
        val smoothed = AIFocusMeteringMath.smoothBounds(
            current = AIFloatRect(0.10f, 0.10f, 0.30f, 0.30f),
            target = AIFloatRect(0.30f, 0.30f, 0.50f, 0.50f),
            subjectType = SubjectType.EYE
        )

        assertEquals(0.252f, smoothed.left, 0.0001f)
        assertEquals(0.252f, smoothed.top, 0.0001f)
        assertEquals(0.452f, smoothed.right, 0.0001f)
        assertEquals(0.452f, smoothed.bottom, 0.0001f)
    }

    @Test
    fun focusRegionFor_intersectsWithExpandedSubjectBounds() {
        val region = AIFocusMeteringMath.focusRegionFor(
            subjectType = SubjectType.EYE,
            focusX = 0.5f,
            focusY = 0.5f,
            smoothedBounds = AIFloatRect(0.46f, 0.46f, 0.54f, 0.54f)
        )

        assertEquals(0.428f, region.left, 0.0001f)
        assertEquals(0.428f, region.top, 0.0001f)
        assertEquals(0.572f, region.right, 0.0001f)
        assertEquals(0.572f, region.bottom, 0.0001f)
    }

    @Test
    fun clampMeteringRect_keepsRectInsideSensorBounds() {
        val clamped = AIFocusMeteringMath.clampMeteringRect(
            region = AIMeteringRect(-50, -20, 4100, 3200),
            bounds = AIMeteringRect(0, 0, 4000, 3000)
        )

        assertEquals(AIMeteringRect(0, 0, 4000, 3000), clamped)
    }

    @Test
    fun normalizedRectToSensorRect_preservesActiveArrayOffset() {
        val region = AIFocusMeteringMath.normalizedRectToSensorRect(
            region = AIFloatRect(0.25f, 0.25f, 0.50f, 0.50f),
            sensorBounds = AIMeteringRect(100, 200, 4100, 3200)
        )

        assertEquals(AIMeteringRect(1100, 950, 2100, 1700), region)
    }

    @Test
    fun regionsChanged_detectsStableRegions() {
        val focus = AIMeteringRect(10, 20, 30, 40)
        val exposure = AIMeteringRect(50, 60, 70, 80)

        assertFalse(
            AIFocusMeteringMath.regionsChanged(
                lastFocusRegion = focus,
                lastExposureRegion = exposure,
                focusRegion = focus,
                exposureRegion = exposure
            )
        )
        assertTrue(
            AIFocusMeteringMath.regionsChanged(
                lastFocusRegion = focus,
                lastExposureRegion = exposure,
                focusRegion = focus.copy(left = 40),
                exposureRegion = exposure
            )
        )
    }

    @Test
    fun lowConfidenceSmoothing_reducesMovementComparedToStrongConfidence() {
        val weak = AIFocusMeteringMath.smoothBounds(
            current = AIFloatRect(0.10f, 0.10f, 0.30f, 0.30f),
            target = AIFloatRect(0.30f, 0.30f, 0.50f, 0.50f),
            subjectType = SubjectType.EYE,
            confidence = 0.2f
        )
        val strong = AIFocusMeteringMath.smoothBounds(
            current = AIFloatRect(0.10f, 0.10f, 0.30f, 0.30f),
            target = AIFloatRect(0.30f, 0.30f, 0.50f, 0.50f),
            subjectType = SubjectType.EYE,
            confidence = 1f
        )

        assertTrue(weak.left < strong.left)
        assertTrue(weak.top < strong.top)
    }

    @Test
    fun smoothFocusPoint_dampsEyePointJitterWithSubjectSpecificAlpha() {
        val weak = AIFocusMeteringMath.smoothFocusPoint(
            current = 0.5f to 0.5f,
            targetX = 0.7f,
            targetY = 0.3f,
            subjectType = SubjectType.EYE,
            confidence = 0.2f
        )
        val strong = AIFocusMeteringMath.smoothFocusPoint(
            current = 0.5f to 0.5f,
            targetX = 0.7f,
            targetY = 0.3f,
            subjectType = SubjectType.EYE,
            confidence = 1.0f
        )

        assertTrue(strong.first > weak.first)
        assertTrue(strong.second < weak.second)
        assertEquals(0.652f, strong.first, 0.0001f)
        assertEquals(0.348f, strong.second, 0.0001f)
    }

    @Test
    fun lowConfidenceFocusRegion_expandsToPreserveTracking() {
        val strong = AIFocusMeteringMath.focusRegionFor(
            subjectType = SubjectType.EYE,
            focusX = 0.5f,
            focusY = 0.5f,
            smoothedBounds = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            confidence = 1f
        )
        val weak = AIFocusMeteringMath.focusRegionFor(
            subjectType = SubjectType.EYE,
            focusX = 0.5f,
            focusY = 0.5f,
            smoothedBounds = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            confidence = 0.2f
        )

        assertTrue(weak.area() > strong.area())
    }
}
