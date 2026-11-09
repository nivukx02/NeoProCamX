package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99VisiblePreviewHandoffContractTest {
    private val viewModelSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText().replace("\r\n", "\n")

    private val preparationSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/PersistentPreviewPreparationCoordinator.kt"
    ).readText().replace("\r\n", "\n")

    @Test
    fun `visible NIV99 tuning remains detached from every live mode`() {
        assertTrue(viewModelSource.contains("previewGlCompositor.setNiv99FrameProcessor(null)"))
        assertFalse(viewModelSource.contains(
            "previewGlCompositor.setNiv99FrameProcessor(object : Niv99PreviewFrameProcessor"
        ))
    }

    @Test
    fun `persistent preview owner is detached before the Camera2 handoff rebuild`() {
        val beginBlock = viewModelSource
            .substringAfter("private fun beginNativePreviewHandoffFromPersistent(")
            .substringBefore("private fun completeNativePreviewHandoffAfterCameraRelease(")
        val bindingBlock = viewModelSource
            .substringAfter("private fun applyPreviewPipelineBinding(")
            .substringBefore("private fun bindPreviewSurface(")
        val refreshBlock = viewModelSource
            .substringAfter("internal fun refreshPreviewPipelineIfNeeded(")
            .substringBefore("private fun preferredVideoSourceSize()")

        assertTrue(beginBlock.contains("nativePreviewPersistentRetirementPending = true"))
        assertTrue(beginBlock.contains("nativePreviewHandoffPending = true"))
        assertTrue(beginBlock.contains("disablePersistentPreviewPipeline(releaseInputs = false)"))
        val activationBlock = viewModelSource
            .substringAfter("private fun shouldAttemptNativeVulkanPreview(")
            .substringBefore("private fun cameraOwnsVisiblePreviewSurface(")
        assertTrue(activationBlock.contains("!nativePreviewHandoffPending"))
        assertTrue(bindingBlock.contains("persistentPreviewOwnerMustRetire(surface)"))
        assertTrue(bindingBlock.contains("beginNativePreviewHandoffFromPersistent(surface)"))
        assertTrue(refreshBlock.contains("appliedHandoffPending"))
        assertTrue(refreshBlock.contains("onPreviousSessionClosed = {"))
        assertTrue(refreshBlock.contains("completeNativePreviewHandoffAfterCameraRelease("))
    }

    @Test
    fun `vulkan acquires the SurfaceView only after bounded persistent EGL retirement`() {
        val completionBlock = viewModelSource
            .substringAfter("private fun completeNativePreviewHandoffAfterCameraRelease(")
            .substringBefore("private fun completeGlPreviewCompositorHandoffAfterCameraRelease(")
        val retire = completionBlock.indexOf("disablePersistentPreviewPipeline(releaseInputs = true)")
        val await = completionBlock.indexOf("persistentRetirementJob?.join()")
        val proof = completionBlock.indexOf("persistentPreviewPreparation.resourcesFullyRetired()")
        val releaseProof = completionBlock.indexOf("nativePreviewCameraReleaseConfirmed = true")
        val bind = completionBlock.indexOf("bindPreviewSurface(")

        assertTrue(retire >= 0)
        assertTrue(await > retire)
        assertTrue(proof > await)
        assertTrue(releaseProof > proof)
        assertTrue(bind > releaseProof)
        assertTrue(completionBlock.contains("Visible NIV99 Vulkan handoff rejected"))
    }

    @Test
    fun `persistent retirement exposes a completion job and verifies every producer is gone`() {
        val detachBlock = preparationSource
            .substringAfter("fun detach(releaseInputs: Boolean): Job?")
            .substringBefore("fun resourcesFullyRetired()")
        val proofBlock = preparationSource
            .substringAfter("fun resourcesFullyRetired(): Boolean")
            .substringBefore("private fun scheduleResourceRelease(")
        val releaseBlock = preparationSource
            .substringAfter("private suspend fun releaseResources()")
            .substringBefore("private companion object")

        assertTrue(detachBlock.contains("return scheduleResourceRelease(predecessor)"))
        assertTrue(detachBlock.contains("return scheduleResourceRelease(predecessor = null)"))
        assertTrue(proofBlock.contains("!sdr.isRunning()"))
        assertTrue(proofBlock.contains("!hdr.isRunning()"))
        assertTrue(proofBlock.contains("!router.isRunning()"))
        assertTrue(releaseBlock.indexOf("sdr.releaseAsync()") < releaseBlock.indexOf("router.release()"))
        assertTrue(releaseBlock.indexOf("hdr.releaseAsync()") < releaseBlock.indexOf("router.release()"))
    }
}
