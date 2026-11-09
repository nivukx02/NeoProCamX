package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Locks the LQ-020 root fix: a time-varying pull must publish more than its first request. */
class CameraControllerCinematicPullCadenceContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `capture results drive subsequent coalesced focus pull requests`() {
        val processResult = source.substringAfter("private fun processResult(")
            .substringBefore("private fun maybeDampMeteringConvergence(")
        val driver = source.substringAfter("private fun maybeAdvanceCinematicFocusPullRequest(")
            .substringBefore("private fun cancelCinematicFocusPull(")

        assertTrue(processResult.contains("maybeAdvanceCinematicFocusPullRequest()"))
        assertTrue(driver.contains("CinematicFocusPullFlag.enabled"))
        assertTrue(driver.contains("cinematicFocusPull.isPulling"))
        assertTrue(driver.contains("updatePreviewFromAutonomousControlFeedback()"))
        val autonomousRefresh = source
            .substringAfter("private fun updatePreviewFromAutonomousControlFeedback(")
            .substringBefore("private fun releaseStaleHdrExposureCorrection(")
        assertTrue(autonomousRefresh.contains("updatePreview(immediate = immediate)"))
        assertTrue(
            "the frame driver must use normal coalesced repeating refresh, never priority one-shots",
            !driver.contains("updatePreview(immediate = true)")
        )
    }

    @Test
    fun `pull state is serialized across receiver and camera threads`() {
        assertTrue(source.contains("private val cinematicFocusPullLock = Any()"))
        val resolver = source.substringAfter("private fun resolveEffectiveManualFocusDistance(")
            .substringBefore("private fun maybeAdvanceCinematicFocusPullRequest(")
        val trigger = source.substringAfter("internal fun triggerCinematicFocusPull(")
            .substringBefore("override fun setFocusDist(")
        assertTrue(resolver.contains("synchronized(cinematicFocusPullLock)"))
        assertTrue(trigger.contains("synchronized(cinematicFocusPullLock)"))
    }

    @Test
    fun `competing manual auto and tap authorities cancel an active pull`() {
        val tapReset = source.substringAfter("private fun clearLensFocusOverridesForTapAf(")
            .substringBefore("private fun beginTapFocusMeteringUnlock(")
        val manual = source.substringAfter("override fun setFocusDist(")
            .substringBefore("override fun setAutoFocus(")
        val auto = source.substringAfter("override fun setAutoFocus(")
            .substringBefore("override fun setEV(")
        assertTrue(tapReset.contains("cancelCinematicFocusPull()"))
        assertTrue(manual.contains("cancelCinematicFocusPull()"))
        assertTrue(auto.contains("cancelCinematicFocusPull()"))
    }
}
