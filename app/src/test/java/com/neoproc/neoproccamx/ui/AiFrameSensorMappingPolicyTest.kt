package com.neoproc.neoproccamx.ui

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiFrameSensorMappingPolicyTest {

    @Test
    fun `null geometry clamps point coordinates independently`() {
        val point = AiFrameSensorMappingPolicy.pointToSensor(
            x = -0.25f,
            y = 1.25f,
            geometry = null
        )

        assertEquals(0f, point.x, FLOAT_EPSILON)
        assertEquals(1f, point.y, FLOAT_EPSILON)
    }

    @Test
    fun `null geometry clamps rect edges without reordering`() {
        val bounds = rect(
            left = 1.30f,
            top = -0.25f,
            right = -0.40f,
            bottom = 1.50f
        )

        val mapped = AiFrameSensorMappingPolicy.rectToSensor(bounds, geometry = null)

        assertEquals(1f, mapped.left, FLOAT_EPSILON)
        assertEquals(0f, mapped.top, FLOAT_EPSILON)
        assertEquals(0f, mapped.right, FLOAT_EPSILON)
        assertEquals(1f, mapped.bottom, FLOAT_EPSILON)
    }

    @Test
    fun `non null geometry delegates to frame geometry crop mapping`() {
        assertTrue(policySource.contains("geometry?.cropNormalizedPointToSensorNormalized(x, y)"))
        assertTrue(policySource.contains("geometry?.cropNormalizedRectToSensorNormalized(bounds)"))
    }

    private val policySource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/AiFrameSensorMappingPolicy.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    private companion object {
        const val FLOAT_EPSILON = 0.0001f
    }
}
