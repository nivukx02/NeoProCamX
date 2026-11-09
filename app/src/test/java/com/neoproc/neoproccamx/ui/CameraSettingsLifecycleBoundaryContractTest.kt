package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsLifecycleBoundaryContractTest {
    @Test
    fun `settings lifecycle callback crosses a narrow host boundary`() {
        val fragment = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsSheetFragment.kt"
        ).readText()
        val activity = File(
            "src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"
        ).readText()

        assertTrue(fragment.contains("interface CameraSettingsLifecycleHost"))
        assertTrue(fragment.contains("(activity as? CameraSettingsLifecycleHost)?.onSettingsSheetStarted()"))
        assertTrue(fragment.contains("(activity as? CameraSettingsLifecycleHost)?.onSettingsSheetDismissed()"))
        assertFalse(fragment.contains("(activity as? MainActivity)?.onSettingsSheetStarted()"))
        assertFalse(fragment.contains("viewModel.suspendCameraForSettings()"))
        assertTrue(activity.contains("CameraSettingsLifecycleHost"))
        assertTrue(activity.contains("override fun onSettingsSheetStarted()"))
        assertTrue(activity.contains("override fun onSettingsSheetDismissed()"))
    }

    @Test
    fun `settings launch failure rolls every suspended owner back through dismissal transaction`() {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("fragment transaction rejected")
        var observedFailure: Throwable? = null

        val launched = CameraSettingsLaunchTransaction.launch(
            onStarted = { events += "started" },
            show = {
                events += "show"
                throw expected
            },
            onShowFailure = { failure ->
                observedFailure = failure
                events += "dismissed"
            }
        )

        assertFalse(launched)
        assertSame(expected, observedFailure)
        assertEquals(listOf("started", "show", "dismissed"), events)
    }

    @Test
    fun `successful settings launch does not invoke dismissal recovery`() {
        val events = mutableListOf<String>()

        val launched = CameraSettingsLaunchTransaction.launch(
            onStarted = { events += "started" },
            show = { events += "show" },
            onShowFailure = { events += "unexpected-dismissal" }
        )

        assertTrue(launched)
        assertEquals(listOf("started", "show"), events)
    }

    @Test
    fun `activity show failure is wired to the same dismissal host callback`() {
        val activity = File(
            "src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"
        ).readText()
        val launch = activity
            .substringAfter("private fun openSettingsSheet()")
            .substringBefore("fun launchCustomLutImport")
        val failureHandler = launch
            .substringAfter("onShowFailure = { error ->")
            .substringBefore("        )")

        assertTrue(launch.contains("CameraSettingsLaunchTransaction.launch("))
        assertTrue(launch.contains("onStarted = ::onSettingsSheetStarted"))
        assertTrue(failureHandler.contains("onSettingsSheetDismissed()"))
        assertFalse(failureHandler.contains("vm.resumeCameraAfterSettings()"))
    }
}
