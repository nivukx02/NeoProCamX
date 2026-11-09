package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraSessionRefreshOwnershipContractTest {
    private val source by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun `profile-only refresh is queued behind an in-flight session instead of dropped`() {
        val pendingBranch = source
            .substringAfter("if (!sessionRefreshPending.compareAndSet(false, true))")
            .substringBefore("if (rebuildReaders)")

        assertTrue(pendingBranch.contains("queueTrailingSessionRefresh("))
        assertTrue(pendingBranch.contains("rebuildReaders = rebuildReaders"))
        assertTrue(pendingBranch.contains("forceRebuild = forceRebuild"))
        assertTrue(pendingBranch.contains("onPreviousSessionClosed = onPreviousSessionClosed"))
        assertFalse(pendingBranch.contains("if (forceRebuild || rebuildReaders)"))
    }

    @Test
    fun `device loss between refresh admission and execution settles the refresh owner`() {
        val createStart = source
            .substringAfter("private fun createSession(")
            .substringBefore("if (stateFlow.value is CameraState.Closing")
        val clearOwnership = source
            .substringAfter("private fun clearSessionRefreshOwnership()")
            .substringBefore("private fun retireStaleSessionRefreshOwnershipIfNeeded")

        assertTrue(createStart.contains("val dev = cameraDevice ?: run"))
        assertTrue(createStart.contains("clearSessionRefreshOwnership()"))
        assertTrue(clearOwnership.contains("sessionRefreshPending.set(false)"))
        assertTrue(clearOwnership.contains("pendingSessionRefreshStartElapsedMs = 0L"))
        assertTrue(createStart.contains("recordFrameDeliveryLedger.markReconfigureSettled()"))
        assertTrue(createStart.contains("runTrailingSessionRefreshIfNeeded()"))
    }

    @Test
    fun `every hot swap waits for actual previous session close even without an external callback`() {
        val onConfigured = source
            .substringAfter("override fun onConfigured(s: CameraCaptureSession) {")
            .substringBefore("override fun onClosed(s: CameraCaptureSession)")

        assertTrue(
            onConfigured.contains(
                "previousSessionForHotSwap != null && previousSessionForHotSwap !== s"
            )
        )
        assertFalse(onConfigured.contains("onPreviousSessionClosed != null"))
        assertTrue(onConfigured.contains("registerPreviousSessionReleaseBarrier("))
        assertTrue(onConfigured.contains("schedulePreviousSessionReleaseFallback(previous)"))
        assertFalse(onConfigured.contains("delay(100L)"))
    }

    @Test
    fun `hot swap barrier handles onClosed arriving immediately before onConfigured`() {
        val onClosed = source
            .substringAfter("override fun onClosed(s: CameraCaptureSession) {")
            .substringBefore("override fun onConfigureFailed(s: CameraCaptureSession)")

        assertTrue(source.contains("sessionsClosedBeforeReleaseBarrier"))
        assertTrue(onClosed.contains("sessionsClosedBeforeReleaseBarrier.add(s)"))
        assertTrue(source.contains("sessionsClosedBeforeReleaseBarrier.remove(session)"))
        assertTrue(source.contains("HOT_SWAP_SESSION_CLOSE_CALLBACK_TIMEOUT_MS = 2_500L"))
    }
}
