package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s5-f01 / CP-F2: pins the afMode-selection contract in CameraController.
 *
 * The hardware afMode preference table and the single-shot AUTO/MACRO fallback are owned by the
 * pure functions in [CameraFocusControl] (golden-locked in CameraFocusControlTest). This contract
 * test guarantees the extraction stays real: CameraController.singleShotAutoFocusMode() must
 * DELEGATE to CameraFocusControl.selectSingleShotAutoFocusMode rather than re-inlining the
 * AUTO -> MACRO -> continuous branch, so the decision lives in one testable place and cannot drift.
 */
class CameraControllerAfModeContractTest {
    // Windows working tree is CRLF; normalize before substring/contains asserts.
    private fun read(relativePath: String): String =
        File(relativePath).readText().replace("\r\n", "\n").replace("\r", "\n")

    private val source: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    }

    @Test
    fun `single shot af mode delegates to the pure focus control selector`() {
        val body = source.substringAfter("private fun singleShotAutoFocusMode(): Int {")
            .substringBefore("private fun updatePreview(")

        assertTrue(
            "singleShotAutoFocusMode must delegate the AUTO/MACRO/continuous preference to the pure " +
                "CameraFocusControl.selectSingleShotAutoFocusMode function (s5-f01/CP-F2 extraction).",
            body.contains("CameraFocusControl.selectSingleShotAutoFocusMode(")
        )
        // The inline AUTO/MACRO branch must be gone from the controller body (lives in the pure fun).
        assertTrue(
            "The inline AUTO->MACRO when-branch must be extracted out of CameraController.",
            !body.contains("modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)")
        )
    }

    @Test
    fun `single shot af mode keeps the null-characteristics fallback to continuous`() {
        val body = source.substringAfter("private fun singleShotAutoFocusMode(): Int {")
            .substringBefore("private fun updatePreview(")

        // When characteristics are unavailable the controller still falls back to continuous AF
        // (unchanged from the pre-extraction behavior).
        assertTrue(
            "null-characteristics path must still fall back to continuousAutoFocusMode().",
            body.contains("?: return continuousAutoFocusMode()")
        )
    }

    @Test
    fun `continuous af mode delegates to the pure focus control selector`() {
        val body = source.substringAfter("private fun continuousAutoFocusMode(): Int {")
            .substringBefore("private fun singleShotAutoFocusMode(): Int {")

        assertTrue(
            "continuousAutoFocusMode must delegate the preference table to " +
                "CameraFocusControl.selectAvailableContinuousAutoFocusMode.",
            body.contains("CameraFocusControl.selectAvailableContinuousAutoFocusMode(")
        )
    }
}
