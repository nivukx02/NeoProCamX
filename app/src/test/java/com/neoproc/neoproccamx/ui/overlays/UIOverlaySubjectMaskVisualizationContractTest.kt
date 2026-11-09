package com.neoproc.neoproccamx.ui.overlays

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIOverlaySubjectMaskVisualizationContractTest {
    private val source = listOf(
        File("src/main/java/com/neoproc/neoproccamx/ui/overlays/UIOverlay.kt"),
        File("app/src/main/java/com/neoproc/neoproccamx/ui/overlays/UIOverlay.kt")
    ).first { it.exists() }.readText()

    @Test
    fun `subject analysis availability never directly draws a preview bitmap`() {
        val drawBlock = source
            .substringAfter("private fun drawDiagnosticOverlays(")
            .substringBefore("private fun drawDiagnosticMask(")

        assertTrue(drawBlock.contains("SubjectMaskPreviewVisualizationPolicy.shouldDraw("))
        assertFalse(drawBlock.contains("if (subjectMaskLevel > 0f)"))
    }

    @Test
    fun `clean hud restoration and lifecycle transitions purge stale bitmap state`() {
        val cleanHudBlock = source
            .substringAfter("var cleanHudEnabled = true")
            .substringBefore("private var faces:")
        val optInBlock = source
            .substringAfter("fun setSubjectMaskDiagnosticVisualizationEnabled(")
            .substringBefore("fun setSubjectMask(")
        val detachBlock = source
            .substringAfter("override fun onDetachedFromWindow()")
            .substringBefore("private fun drawFocusReticle(")

        assertTrue(cleanHudBlock.contains("if (value)"))
        assertTrue(cleanHudBlock.contains("clearSubjectMaskVisualization()"))
        assertTrue(optInBlock.contains("if (!enabled) clearSubjectMaskVisualization()"))
        assertTrue(detachBlock.contains("clearSubjectMaskVisualization()"))
    }
}
