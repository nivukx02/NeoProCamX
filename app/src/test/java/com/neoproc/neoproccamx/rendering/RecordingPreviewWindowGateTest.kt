package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingPreviewWindowGateTest {
    @Test fun `notification occlusion stops swaps without detaching or invalidating the window`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        assertTrue(gate.setPresentationOccluded(true))
        assertFalse(gate.mayPresent())
        assertFalse(gate.isWindowSuspended())
        assertFalse(gate.setPresentationOccluded(true))
        assertTrue(gate.setPresentationOccluded(false))
        assertTrue(gate.mayPresent())
        assertFalse(gate.isWindowSuspended())
    }

    @Test fun `focus return cannot resurrect a detached or uncommitted surface`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        gate.setPresentationOccluded(true)
        gate.requestDetach()
        gate.setPresentationOccluded(false)
        assertFalse(gate.mayPresent())
        val ticket = requireNotNull(gate.requestAttach())
        gate.setPresentationOccluded(true)
        assertTrue(gate.commitAttached(ticket))
        assertFalse(gate.mayPresent())
        gate.setPresentationOccluded(false)
        assertTrue(gate.mayPresent())
    }

    @Test fun `retirement and new start cannot inherit old occlusion`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        gate.setPresentationOccluded(true)
        gate.retire()
        assertFalse(gate.setPresentationOccluded(false))
        assertFalse(gate.mayPresent())
        gate.startVisible()
        assertTrue(gate.mayPresent())
    }

    @Test fun `rapid focus toggles never revoke an owning attachment ticket`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        gate.requestDetach()
        val ticket = requireNotNull(gate.requestAttach())
        repeat(10_000) {
            gate.setPresentationOccluded(true)
            assertTrue(gate.isCurrent(ticket))
            assertFalse(gate.mayPresent())
            gate.setPresentationOccluded(false)
            assertTrue(gate.isCurrent(ticket))
        }
        assertTrue(gate.commitAttached(ticket))
    }

    @Test fun `occluded router drains children before refusing visible publication`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt").readText()
        val draw = source.substringAfter("private fun drawFrame()").substringBefore("private fun recoverExpectedWindowRetirement(")
        val drain = draw.indexOf("texture.updateTexImage()")
        val presentationGate = draw.indexOf("if (!recordingWindowGate.mayPresent()) return")
        val surfaceGate = draw.indexOf("if (drawSurface !== eglSurface) return")
        val visibleDraw = draw.indexOf("GLES20.glViewport")
        val swap = draw.indexOf("EGL14.eglSwapBuffers")
        assertTrue(drain >= 0 && presentationGate > drain && swap > presentationGate)
        assertTrue(surfaceGate > presentationGate && visibleDraw > surfaceGate && swap > visibleDraw)
        assertTrue(draw.contains("if (recordingWindowGate.mayPresent()) eglSurface else parkingSurface"))
        val vm = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val focus = vm.substringAfter("fun handleRecordingWindowFocusChanged(").substringBefore("fun handleAppBackgrounded()")
        assertTrue(focus.contains("CameraPreviewLifecycleDispatcher.recordingWindowFocus("))
        assertTrue(focus.contains("hasFocus, ::isRecordingOrStopping, persistentPreviewOutputRouter::setRecordingWindowOccluded"))
        val dispatcher = File("src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewLifecycleDispatcher.kt").readText()
        assertTrue(dispatcher.contains("setOccluded(!hasFocus && recordingActive())"))
        assertFalse(focus.contains("camera."))
        assertFalse(focus.contains("requestRecordingStop("))
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        assertTrue(activity.substringAfter("override fun onWindowFocusChanged(")
            .substringBefore("override fun dispatchTouchEvent(")
            .contains("vm.handleRecordingWindowFocusChanged(hasFocus)"))
    }

    @Test fun `inactive owner rejects window work`() {
        val gate = RecordingPreviewWindowGate()
        assertNull(gate.requestAttach())
        assertNull(gate.requestDetach())
        assertFalse(gate.mayPresent())
        assertFalse(gate.isWindowSuspended())
    }

    @Test fun `detach blocks presentation before asynchronous GL completion`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        assertTrue(gate.mayPresent())
        val ticket = requireNotNull(gate.requestDetach())
        assertTrue(gate.isCurrent(ticket))
        assertTrue(gate.isWindowSuspended())
        assertFalse(gate.mayPresent())
        assertFalse(gate.commitAttached(ticket))
    }

    @Test fun `replacement stays hidden until strict EGL window commits`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        gate.requestDetach()
        val ticket = requireNotNull(gate.requestAttach())
        assertFalse(gate.mayPresent())
        assertTrue(gate.commitAttached(ticket))
        assertTrue(gate.mayPresent())
    }

    @Test fun `late detach cannot destroy newer replacement`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        val detach = requireNotNull(gate.requestDetach())
        val attach = requireNotNull(gate.requestAttach())
        assertFalse(gate.isCurrent(detach))
        assertTrue(gate.commitAttached(attach))
        assertTrue(gate.mayPresent())
    }

    @Test fun `destroyed replacement cannot return from a late successful callback`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        val attach = requireNotNull(gate.requestAttach())
        val detach = requireNotNull(gate.requestDetach())
        assertFalse(gate.commitAttached(attach))
        assertTrue(gate.isCurrent(detach))
        assertFalse(gate.mayPresent())
    }

    @Test fun `only latest replacement may commit`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        val first = requireNotNull(gate.requestAttach())
        val second = requireNotNull(gate.requestAttach())
        assertFalse(gate.commitAttached(first))
        assertTrue(gate.commitAttached(second))
    }

    @Test fun `release invalidates queued work and cannot be undone by old callback`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        val old = requireNotNull(gate.requestAttach())
        gate.retire()
        assertFalse(gate.commitAttached(old))
        assertNull(gate.requestAttach())
        gate.startVisible()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.mayPresent())
    }

    @Test fun `ten thousand rapid transitions retain exclusive latest owner`() {
        val gate = RecordingPreviewWindowGate().apply { startVisible() }
        repeat(10_000) {
            val detached = requireNotNull(gate.requestDetach())
            val stale = requireNotNull(gate.requestAttach())
            val latest = requireNotNull(gate.requestAttach())
            assertNotNull(latest)
            assertFalse(gate.isCurrent(detached))
            assertFalse(gate.commitAttached(stale))
            assertTrue(gate.commitAttached(latest))
        }
        assertTrue(gate.mayPresent())
    }
}
