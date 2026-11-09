package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraViewModelAiFocusContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }

    @Test
    fun `ai autofocus uses merged metering stream as single submit path`() {
        val frameResultBody = source.substringAfter("private fun handleAICameraFrameResult(")
            .substringBefore("private fun enqueueAICameraFrameResult(")

        assertTrue(source.contains("aiAfCoordinator.observeAI(mergedAiMetering)"))
        assertFalse(frameResultBody.contains("aiAfCoordinator.submitTarget("))
    }

    @Test
    fun `merged ai metering preserves eye and subject focus points instead of recentering bounds`() {
        val focusXBody = source.substringAfter("private fun prioritizedSubjectFocusX(")
            .substringBefore("private fun prioritizedSubjectFocusY(")
        val focusYBody = source.substringAfter("private fun prioritizedSubjectFocusY(")
            .substringBefore("private fun shouldUseSubjectLinkedMetering(")

        assertTrue(focusXBody.contains("return aiFocus?.normalizedX ?: aiSubject.focusX"))
        assertTrue(focusYBody.contains("return aiFocus?.normalizedY ?: aiSubject.focusY"))
        assertFalse(focusXBody.contains("bounds.centerX()"))
        assertFalse(focusYBody.contains("bounds.centerY()"))
    }
}
