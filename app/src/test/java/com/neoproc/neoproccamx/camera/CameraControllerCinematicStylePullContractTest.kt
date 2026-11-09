package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f08 / CP-F9 — source contract for wiring the AIFocusDistanceController pull styles into the
 * live manual-lens path via f07's control, gated OFF by default (no-downgrade).
 *
 * The Windows working tree is LF (.gitattributes eol=lf) but normalise defensively before asserts.
 */
class CameraControllerCinematicStylePullContractTest {
    private fun read(path: String): String = File(path).readText().replace("\r\n", "\n")

    private val controller: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    }
    private val stepper: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/camera/AICinematicPullStepper.kt")
    }

    @Test
    fun `style pull entry point is gated off by default`() {
        val fn = controller.substringAfter("internal fun beginCinematicStylePull(")
            .substringBefore("\n    override fun setFocusDist(")
        assertTrue(
            "must fail closed when the cine flag is OFF or the lens has no variable focus",
            fn.contains("if (!CinematicFocusPullFlag.enabled || minFocusDist <= 0f) return false")
        )
        assertTrue(
            "must build the f07 control with the AI pull stepper for the requested style",
            fn.contains("CinematicFocusPullControl(AICinematicPullStepper(style))")
        )
        assertTrue(
            "must align the control duration with the style's applyFocusPull duration",
            fn.contains("AICinematicPullStepper.styleDurationMs(style)")
        )
        assertTrue("must trigger the pull A->B", fn.contains("triggerPull("))
        assertTrue(
            "a named style pull must seed a real repeating request",
            fn.contains("updatePreview()")
        )
    }

    @Test
    fun `the cine control field is reconstructable for a named style`() {
        assertTrue(
            "cinematicFocusPull must be a var so a style pull can swap in the AI stepper",
            controller.contains("private var cinematicFocusPull = CinematicFocusPullControl()")
        )
    }

    @Test
    fun `the stepper owns no easing math and delegates to applyFocusPull`() {
        assertTrue(
            "stepper must delegate every diopter to the read-only AIFocusDistanceController API",
            stepper.contains("AIFocusDistanceController.applyFocusPull(")
        )
        assertTrue(
            "stepper is gated/dormant by construction (only built by the gated entry point)",
            stepper.contains("CinematicFocusPullControl.CinematicFocusStepper")
        )
    }
}
