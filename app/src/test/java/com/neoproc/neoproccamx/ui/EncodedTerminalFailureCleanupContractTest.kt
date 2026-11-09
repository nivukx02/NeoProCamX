package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedTerminalFailureCleanupContractTest {
    private val vm = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val manager = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()

    @Test
    fun `fatal encoded failures stop through the lifetime independent recording owner`() {
        assertTrue(vm.contains("RecordingTerminalFailureCleanupObserver("))
        assertTrue(vm.contains("viewModelScope, gpuRecording, npv10Recording, viewModelTeardownStarted::get"))
        assertTrue(vm.contains("::setHardwareWarning, ::requestRecordingStop"))
        val observer = File("src/main/java/com/neoproc/neoproccamx/ui/RecordingTerminalFailureCleanupObserver.kt")
            .readText().substringAfter("private fun startEncodedTerminalErrorCleanupObserver()")
        assertTrue(observer.contains("scope.launch(Dispatchers.Main.immediate)"))
        assertTrue(observer.contains("combine(gpuRecording.fatalPipelineFailures, gpuRecording.state)"))
        assertTrue(observer.contains("teardownStarted()"))
        assertTrue(observer.contains("gpuRecording.claimFatalPipelineStop(failure)"))
        assertTrue(observer.contains("origin = RecordingStopOrigin.PIPELINE_FAILURE"))
        assertTrue(observer.contains("requestRecordingStop("))
        assertFalse(observer.contains("gpuRecording.stop()"))
        assertFalse(observer.contains("gpuRecording.destroy()"))
    }

    @Test
    fun `worker signal never releases codec ownership or clears the failed output`() {
        val report = manager.substringAfter("fun reportFatalPipelineFailure(message: String)")
            .substringBefore("fun discardPreparedSession()")
        assertTrue(report.contains("fatalPipelineFailure.recordFirst(message)"))
        assertFalse(report.contains("releaseInternal()"))
        assertFalse(report.contains("cleanupFailedOutputTarget()"))
        assertFalse(report.contains("fatalPipelineFailure.reset()"))
        assertTrue(manager.contains("fatalPipelineFailure = RecordingFatalFailureSignal()"))
        assertTrue(manager.contains("fatalPipelineFailure.reset()"))
    }
}
