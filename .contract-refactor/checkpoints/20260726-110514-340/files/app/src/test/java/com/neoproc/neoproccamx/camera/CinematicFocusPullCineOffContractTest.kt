package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f07 / CP-F8 — cine-OFF no-downgrade contract.
 *
 * Locks two facts in source:
 *  1. [CinematicFocusPullFlag] defaults OFF.
 *  2. The cine focus-pull diopter override in [CameraController] is gated behind the flag and, when
 *     OFF, resolves to the existing `manFocusDist` so the autofocus request is byte-for-byte today.
 *
 * The Windows working tree is CRLF; normalise before contains() asserts.
 */
class CinematicFocusPullCineOffContractTest {
    private fun read(relativePath: String): String =
        File(relativePath).readText().replace("\r\n", "\n")

    private val flagSource: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/camera/CinematicFocusPullFlag.kt")
    }
    private val controllerSource: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    }

    @Test
    fun `cinematic focus pull flag defaults off`() {
        assertTrue(
            "CinematicFocusPullFlag must default OFF (no-downgrade)",
            flagSource.contains("var enabled: Boolean = false")
        )
    }

    @Test
    fun `effective manual focus distance is gated behind the flag and falls back to manFocusDist`() {
        val helper = controllerSource
            .substringAfter("private fun resolveEffectiveManualFocusDistance(")
            .substringBefore("\n    override fun setFocusDist(")
        assertTrue(
            "override must be gated by CinematicFocusPullFlag.enabled",
            helper.contains("if (!CinematicFocusPullFlag.enabled) return manFocusDist")
        )
        assertTrue(
            "helper must consult the cine pull control step",
            helper.contains("cinematicFocusPull.stepDiopters(")
        )
        assertTrue(
            "with no active pull the helper returns manFocusDist unchanged",
            helper.contains("return manFocusDist")
        )
    }

    @Test
    fun `autofocus request consumes the effective manual focus distance`() {
        val resolveCall = controllerSource
            .substringAfter("val autofocusPlan = CameraAutofocusRequestPolicy.resolve(")
            .substringBefore(")")
        assertTrue(
            "resolve() must read the gated effective distance, not manFocusDist directly",
            resolveCall.contains("manualFocusDistance = effectiveManFocusDist")
        )
        assertFalse(
            "the raw manFocusDist must no longer flow straight into resolve()",
            resolveCall.contains("manualFocusDistance = manFocusDist")
        )
    }
}
