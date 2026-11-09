package com.neoproc.neoproccamx.ui.overlays

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f09 / CP-F10 — single-source-of-truth guard.
 *
 * The dead CPU `FocusPeakingOverlay` Android View was retired in favour of the
 * live overlay-mask peaking (FrameAnalysisMath.buildFocusPeakingMask). This guard
 * fails if the duplicate is resurrected anywhere under src/main, preventing the
 * bit-rot the prompt calls out.
 *
 * CWD for unit tests is the module root (app/); production source lives under
 * src/main. CRLF is normalized before matching (Windows working tree is CRLF).
 */
class FocusPeakingOverlayRemovedGuardTest {

    private fun read(file: File): String =
        file.readText().replace("\r\n", "\n")

    @Test
    fun `dead CPU FocusPeakingOverlay file is removed`() {
        val deadFile = File(
            "src/main/java/com/neoproc/neoproccamx/ui/overlays/FocusPeakingOverlay.kt"
        )
        assertFalse(
            "FocusPeakingOverlay.kt must stay deleted (live peaking is the single source of truth)",
            deadFile.exists()
        )
    }

    @Test
    fun `no production source references the removed FocusPeakingOverlay class`() {
        val mainRoot = File("src/main")
        assertTrue("src/main must exist for this guard to be meaningful", mainRoot.isDirectory)
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { read(it).contains("FocusPeakingOverlay") }
            .map { it.path }
            .toList()
        assertTrue(
            "No production source may reference the retired FocusPeakingOverlay: $offenders",
            offenders.isEmpty()
        )
    }
}
