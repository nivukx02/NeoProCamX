package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerRealtimeEngineContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun controllerUsesUrgentCameraThreadAndHotSessionSwap() {
        assertTrue(source.contains("Process.THREAD_PRIORITY_URGENT_AUDIO"))
        assertTrue(source.contains("ThreadPoolManager.cameraExecutor"))
        assertTrue(source.contains("previousSessionForHotSwap"))
        assertFalse(source.contains("Close the existing session before creating a new one"))
    }

    @Test
    fun hotSessionRebuildDefersReaderCloseUntilPreviousSessionCloses() {
        val setupReadersBody = source
            .substringAfter("override fun setupReaders() {")
            .substringBefore("private fun closeReaderSafely")
        val onConfiguredBody = source
            .substringAfter("override fun onConfigured(s: CameraCaptureSession) {")
            .substringBefore("override fun onClosed(s: CameraCaptureSession)")
        val onClosedBody = source
            .substringAfter("override fun onClosed(s: CameraCaptureSession) {")
            .substringBefore("override fun onConfigureFailed(s: CameraCaptureSession)")

        assertTrue(setupReadersBody.contains("deferCurrentReadersUntilSessionClose()"))
        assertFalse(setupReadersBody.contains("closeReaderSafely(jpegReader)"))
        assertFalse(setupReadersBody.contains("closeReaderSafely(hardwareImportReader)"))
        assertTrue(onConfiguredBody.contains("previousSessionClosedCallbacks"))
        assertTrue(onClosedBody.contains("closeDeferredReadersSafely"))
        assertTrue(source.contains("awaitFramePipelineIdleBeforeReaderClose"))
        assertTrue(source.contains("awaitFramePipelineIdleForReaderClose(reason)"))
    }

    @Test
    fun hardwareImportPathUsesRealtimeDispatcherAndPresentationPts() {
        val framePipelineSource = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
        val runtimeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
        val bridgeSource = File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()

        assertTrue(framePipelineSource.contains("RealtimeFrameDispatcher<Image>"))
        assertTrue(framePipelineSource.contains("RecordPipelineDepthFlag.hardwareImportAcquireGuardNs()"))
        assertTrue(framePipelineSource.contains("presentationTimeNs"))
        assertTrue(runtimeSource.contains("SensorPresentationClock"))
        assertTrue(runtimeSource.contains("presentationTimeNs"))
        assertTrue(bridgeSource.contains("presentationTimeNs: Long"))
    }

    @Test
    fun previewFpsTelemetryUsesBufferedCaptureTimestampsInsteadOfConflatedLiveResult() {
        assertTrue(source.contains("MutableSharedFlow<Long>"))
        assertTrue(source.contains("extraBufferCapacity = 120"))
        assertTrue(source.contains("BufferOverflow.DROP_OLDEST"))
        assertTrue(source.contains("val previewFrameTimestamps: SharedFlow<Long>"))
        assertTrue(source.contains("previewFrameTimestampFlow.tryEmit(liveResult.timestampNs)"))
    }

    @Test
    fun createSessionUsesClosedDeviceRecoveryPolicy() {
        assertTrue(source.contains("CameraSessionCreateFailurePolicy.resolve("))
        assertTrue(source.contains("sessionGenerationCurrent = generation == sessionGeneration.get()"))
        assertTrue(source.contains("cameraDeviceStillCurrent = cameraDevice === device"))
        assertTrue(source.contains("openCamera(onReady, onError)"))
    }
}
