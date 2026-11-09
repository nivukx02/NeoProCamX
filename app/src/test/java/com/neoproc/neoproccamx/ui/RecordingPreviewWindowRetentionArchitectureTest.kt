package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPreviewWindowRetentionArchitectureTest {
    private fun source(path: String): String {
        val candidates = listOf(
            File("src/main/java/com/neoproc/neoproccamx/$path"),
            File("app/src/main/java/com/neoproc/neoproccamx/$path")
        )
        return requireNotNull(candidates.firstOrNull { it.isFile }).readText()
    }

    @Test fun `background retirement precedes recording-facing teardown`() {
        val text = source("ui/CameraViewModel.kt")
        val background = text.substringAfter("fun handleAppBackgrounded()").substringBefore("private fun isRecordingOrStopping")
        assertTrue(background.contains("retainDisplayInputs = { recordingPreviewWindow.retain() }"))
        val detach = text.substringAfter("private fun clearPreviewSurfaceForActiveRecording()").substringBefore("private fun preferredPreviewSize")
        assertTrue(detach.indexOf("recordingPreviewWindow.retain()") >= 0)
        assertTrue(detach.indexOf("recordingPreviewWindow.retain()") < detach.indexOf("markStrictPreviewAdmissionPending()"))
        val retention = text.substringAfter("private val recordingPreviewWindow by lazy").substringBefore("@Volatile private var recordingPreviewRebindInProgress")
        assertFalse(Regex("(?m)^\\s*camera\\.(setPreviewSurface|refreshOutputsAndSession|setPersistentPreviewOutputs)\\(").containsMatchIn(retention))
        assertFalse(Regex("(?m)^\\s*(markStrictPreviewAdmissionPending|disablePersistentPreviewPipeline)\\(").containsMatchIn(retention))
        assertTrue(retention.contains("isPersistentRouteHealthy()"))
        val restore = retention.substringAfter("onRestored = { surface ->").substringBefore("onFailure =")
        assertTrue(restore.contains("previewSurface = surface"))
        assertTrue(restore.contains("persistentPreviewVisibleSurface = surface"))
        assertTrue(restore.contains("persistentPreviewInputSize = boundPreviewSize ?: preferredPreviewSize()"))
        assertTrue(retention.contains("NeoLogger.i(\"CameraViewModel\", marker)"))
    }

    @Test fun `expected window retirement does not suppress context loss`() {
        val text = source("rendering/PersistentPreviewOutputRouter.kt")
        val recovery = text.substringAfter("private fun recoverExpectedWindowRetirement(").substringBefore("private fun reportRuntimeFailure")
        assertTrue(recovery.contains("recordingWindowGate.isWindowSuspended()"))
        assertTrue(recovery.contains("eglError != EGL14.EGL_BAD_SURFACE"))
        assertTrue(recovery.contains("eglError != EGL14.EGL_BAD_NATIVE_WINDOW"))
        assertTrue(recovery.contains("return parkVisibleOutput()"))
        assertFalse(recovery.contains("eglError == EGL14.EGL_CONTEXT_LOST"))
        val failure = text.substringAfter("private fun reportRuntimeFailure(").substringBefore("private fun emitIfComplete")
        assertTrue(failure.contains("running = false"))
        assertTrue(failure.contains("recordingWindowGate.retire()"))
    }

    @Test fun `reattach requires current same-size strict output and fresh lineage`() {
        val text = source("rendering/PersistentPreviewOutputRouter.kt")
        val attach = text.substringAfter("fun attachVisibleOutputForRecording(").substringBefore("private fun parkVisibleOutput")
        assertTrue(attach.contains("width != boundWidth || height != boundHeight"))
        assertTrue(attach.contains("createSizedWindowSurface(config, output, width, height)"))
        assertTrue(attach.contains("recordingWindowGate.commitAttached(ticket)"))
        assertTrue(attach.contains("clearVisibleWindowLineage()"))
        assertTrue(attach.contains("frameReady.fill(false)"))
        val acknowledgement = text.substringAfter("private fun emitIfComplete(").substringBefore("private fun destroyGl")
        assertTrue(acknowledgement.contains("if (!recordingWindowGate.mayPresent()) return"))
    }
}
