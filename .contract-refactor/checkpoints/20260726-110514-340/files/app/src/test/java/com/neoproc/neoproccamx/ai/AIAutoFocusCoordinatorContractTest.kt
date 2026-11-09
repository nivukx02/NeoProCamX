package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AIAutoFocusCoordinatorContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ai/AIAutoFocusCoordinator.kt").readText()
    }

    @Test
    fun `coordinator smooths focus point before building af metering region`() {
        assertTrue(source.contains("private var smoothedFocusPoint"))
        assertTrue(source.contains("val stableFocusPoint = AIFocusMeteringMath.smoothFocusPoint("))
        assertTrue(source.contains("focusX = stableFocusPoint.first"))
        assertTrue(source.contains("focusY = stableFocusPoint.second"))
        assertTrue(source.contains("smoothedFocusPoint = stableFocusPoint"))
        assertTrue(source.contains("smoothedFocusPoint = null"))
    }
}
