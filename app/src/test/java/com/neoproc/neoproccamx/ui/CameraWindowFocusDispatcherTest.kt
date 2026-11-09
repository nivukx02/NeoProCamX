package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.recording.GpuRecordingState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWindowFocusDispatcherTest {
    @Test fun `all live recording states retain the window and camera ownership is respected`() {
        val states = listOf(
            GpuRecordingState.Starting, GpuRecordingState.Recording,
            GpuRecordingState.Paused, GpuRecordingState.Stopping
        )
        for (state in states) {
            assertTrue(CameraPreviewLifecycleDispatcher.recordingOwnsWindow(state, CameraState.Closed))
        }
        assertTrue(CameraPreviewLifecycleDispatcher.recordingOwnsWindow(GpuRecordingState.Idle, CameraState.Recording))
        assertFalse(CameraPreviewLifecycleDispatcher.recordingOwnsWindow(GpuRecordingState.Idle, CameraState.Closed))
        assertFalse(CameraPreviewLifecycleDispatcher.recordingOwnsWindow(GpuRecordingState.Error("failed"), CameraState.Closed))
    }

    @Test fun `focus return updates display ownership before requesting orientation`() {
        val events = mutableListOf<String>()
        CameraPreviewLifecycleDispatcher.windowFocus(
            true, { events += "window" }, { events += "orientation" }
        )
        assertEquals(listOf("window", "orientation"), events)
    }

    @Test fun `focus loss does not request an orientation refresh`() {
        val events = mutableListOf<String>()
        CameraPreviewLifecycleDispatcher.windowFocus(
            false, { events += "window" }, { events += "orientation" }
        )
        assertEquals(listOf("window"), events)
    }

    @Test fun `only active recording loses presentation when unfocused`() {
        for (active in listOf(false, true)) {
            for (focused in listOf(false, true)) {
                val changes = mutableListOf<Boolean>()
                CameraPreviewLifecycleDispatcher.recordingWindowFocus(focused, { active }, changes::add)
                assertEquals(listOf(!focused && active), changes)
            }
        }
    }

    @Test fun `focus return clears occlusion even after recording ownership has retired`() {
        var checkedRecording = false
        var occluded = true
        CameraPreviewLifecycleDispatcher.recordingWindowFocus(
            true, { checkedRecording = true; false }, { occluded = it }
        )
        assertFalse(checkedRecording)
        assertFalse(occluded)
    }

    @Test fun `display failure propagates and does not trigger unrelated work`() {
        val failure = IllegalStateException("display owner rejected focus")
        var orientationRequested = false
        val observed = runCatching {
            CameraPreviewLifecycleDispatcher.windowFocus(
                true, { throw failure }, { orientationRequested = true }
            )
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertFalse(orientationRequested)
    }

    @Test fun `activity rejects early and security blocked focus before touching binding`() {
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val focus = activity.substringAfter("override fun onWindowFocusChanged(")
            .substringBefore("override fun dispatchTouchEvent(")
        val initialized = focus.indexOf("if (!::binding.isInitialized || securityStartupBlocked) return")
        assertTrue(initialized >= 0)
        assertTrue(focus.indexOf("CameraPreviewLifecycleDispatcher.windowFocus(") > initialized)
        assertTrue(focus.indexOf("vm.handleRecordingWindowFocusChanged(hasFocus)") > initialized)
        assertTrue(focus.indexOf("binding.glSurface.post") > initialized)
    }
}
