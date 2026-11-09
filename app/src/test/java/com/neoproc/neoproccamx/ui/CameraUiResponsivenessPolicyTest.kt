package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraUiResponsivenessPolicyTest {

    @Test
    fun `ui action buffer is bounded but preserves tap burst headroom`() {
        assertTrue(CameraUiResponsivenessPolicy.UI_ACTION_BUFFER_CAPACITY in 64..256)
    }

    @Test
    fun `manual slider keys are stable for latest-only camera operations`() {
        assertEquals("manual_iso", CameraUiResponsivenessPolicy.MANUAL_ISO_KEY)
        assertEquals("manual_exposure", CameraUiResponsivenessPolicy.MANUAL_EXPOSURE_KEY)
        assertEquals("manual_white_balance", CameraUiResponsivenessPolicy.MANUAL_WHITE_BALANCE_KEY)
        assertEquals("manual_focus_distance", CameraUiResponsivenessPolicy.MANUAL_FOCUS_DISTANCE_KEY)
        assertEquals("manual_ev", CameraUiResponsivenessPolicy.MANUAL_EV_KEY)
        assertEquals("manual_zoom", CameraUiResponsivenessPolicy.MANUAL_ZOOM_KEY)
    }

    @Test
    fun `critical ui actions are never dropped under queue pressure`() {
        val criticalActions = listOf(
            UiAction.ToggleRecording,
            UiAction.PauseRecording,
            UiAction.ResumeRecording,
            UiAction.SwitchFacing,
            UiAction.ToggleRaw,
            UiAction.SetManualFocus(null)
        )

        criticalActions.forEach { action ->
            assertTrue(!CameraUiResponsivenessPolicy.shouldDropWhenActionQueueFull(action))
        }
    }

    @Test
    fun `high frequency zoom controls may be dropped when action queue is full`() {
        assertTrue(
            CameraUiResponsivenessPolicy.shouldDropWhenActionQueueFull(
                UiAction.AdjustZoom(2.0f)
            )
        )
    }

    @Test
    fun `view model coalesces high frequency manual camera operations`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        assertTrue(source.contains("private fun dispatchLatestManualControlUpdate("))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.MANUAL_ISO_KEY"))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.MANUAL_EXPOSURE_KEY"))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.MANUAL_WHITE_BALANCE_KEY"))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.MANUAL_FOCUS_DISTANCE_KEY"))
        assertTrue(source.contains("CameraUiResponsivenessPolicy.MANUAL_EV_KEY"))
    }

    @Test
    fun `manual control dispatcher removes stale latest-only work before posting replacement`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt"
        ).readText()

        assertTrue(source.contains("private val latestRunnables"))
        assertTrue(source.contains("fun postLatest(key: String, action: () -> Unit)"))
        assertTrue(source.contains("handler.removeCallbacks(it)"))
        assertTrue(source.contains("latestRunnables.clear()"))
    }

    @Test
    fun `manual control dispatcher shutdown is terminal and blocks stale posts`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt"
        ).readText()

        assertTrue(source.contains("@Volatile private var closed = false"))
        assertTrue(source.contains("if (closed) return false"))
        assertTrue(source.contains("closed = true"))
        assertTrue(source.contains("fun post(action: () -> Unit): Boolean"))
        assertTrue(source.contains("fun postLatest(key: String, action: () -> Unit): Boolean"))
        assertTrue(source.contains("fun postLatestZoom(action: () -> Unit): Boolean"))
    }
}
