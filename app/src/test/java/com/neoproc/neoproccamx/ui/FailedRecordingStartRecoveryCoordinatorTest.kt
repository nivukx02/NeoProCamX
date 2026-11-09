package com.neoproc.neoproccamx.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedRecordingStartRecoveryCoordinatorTest {
    @Test
    fun `precise backend error is published before recorder ownership is released`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, backendFailure = "strict bitrate rejected")

        val failure = coordinator.terminalize("generic prepare failure", reportError = true)
        coordinator.releaseRecorder(failure)

        assertEquals("strict bitrate rejected", failure)
        assertEquals(
            listOf(
                "backend-error",
                "transition:strict bitrate rejected",
                "foreground-stop",
                "recorder-error:strict bitrate rejected",
                "persistent-release"
            ),
            events
        )
    }

    @Test
    fun `cancelled startup discards prepared session without publishing an error`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, backendFailure = "must not publish")

        val failure = coordinator.terminalize("cancelled", reportError = false)
        coordinator.releaseRecorder(failure)

        assertNull(failure)
        assertEquals(
            listOf("foreground-stop", "prepared-discard", "persistent-release"),
            events
        )
    }

    @Test
    fun `recorder release failure still discards and releases persistent surface`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events, throwRecorderFailure = true)

        coordinator.releaseRecorder("start failed")

        assertEquals("persistent-release", events.last())
        assertTrue(events.contains("cleanup-error:gpuRecording.failedStartRelease"))
        assertTrue(events.contains("prepared-discard"))
    }

    @Test
    fun `suspending cleanup never converts cancellation into a hardware error`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)
        var cancellationObserved = false

        runBlocking {
            try {
                coordinator.runSuspendingStep("cancelled-step", "must not report") {
                    throw CancellationException("test cancellation")
                }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
        }

        assertTrue(cancellationObserved)
        assertTrue(events.none { it.startsWith("cleanup-error:") })
    }

    @Test
    fun `preview recovery preserves ordered latest owned restoration`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        coordinator.schedulePreviewRecovery(refreshSession = true)

        assertEquals(
            listOf(
                "camera-refresh",
                "preview-restore",
                "look-sync",
                "auto-lock-release",
                "raw-reconcile",
                "fps-sync",
                "performance-sync"
            ),
            events
        )
    }

    @Test
    fun `superseded recovery cannot execute trailing mutations after an action returns`() {
        val events = mutableListOf<String>()
        lateinit var subject: FailedRecordingStartRecoveryCoordinator
        var superseded = false
        subject = coordinator(
            events = events,
            refreshCameraSession = {
                events += "camera-refresh"
                if (!superseded) {
                    superseded = true
                    subject.schedulePreviewRecovery(refreshSession = false)
                }
            }
        )

        subject.schedulePreviewRecovery(refreshSession = true)

        assertEquals(1, events.count { it == "preview-restore" })
        assertEquals(1, events.count { it == "look-sync" })
        assertEquals(1, events.count { it == "auto-lock-release" })
        assertEquals(1, events.count { it == "raw-reconcile" })
        assertEquals(1, events.count { it == "fps-sync" })
        assertEquals(1, events.count { it == "performance-sync" })
        assertTrue(events.none { it.startsWith("cleanup-error:") })
    }

    private fun coordinator(
        events: MutableList<String>,
        backendFailure: String? = null,
        throwRecorderFailure: Boolean = false,
        refreshCameraSession: (() -> Unit)? = null
    ): FailedRecordingStartRecoveryCoordinator = FailedRecordingStartRecoveryCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        mainDispatcher = Dispatchers.Unconfined,
        actions = FailedRecordingStartRecoveryActions(
            backendFailureMessage = { events += "backend-error"; backendFailure },
            publishTransitionFailure = { events += "transition:$it" },
            stopForegroundProtection = { events += "foreground-stop" },
            reportRecorderFailure = {
                events += "recorder-error:$it"
                if (throwRecorderFailure) error("release failed")
            },
            discardPreparedSession = { events += "prepared-discard" },
            releasePersistentInputSurface = { events += "persistent-release" },
            refreshCameraSession = refreshCameraSession ?: { events += "camera-refresh" },
            restorePreviewBinding = { events += "preview-restore" },
            syncCameraPreviewLook = { events += "look-sync" },
            releaseCinemaAutoLock = { events += "auto-lock-release" },
            reconcileRawPreview = { events += "raw-reconcile" },
            syncPreviewFrameRate = { events += "fps-sync" },
            syncPreviewPerformance = { events += "performance-sync" },
            reportCleanupError = { _, operation, _ -> events += "cleanup-error:$operation" }
        )
    )
}
