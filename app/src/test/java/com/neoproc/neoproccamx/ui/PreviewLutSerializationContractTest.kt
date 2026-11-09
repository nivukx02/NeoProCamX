package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLutSerializationContractTest {
    @Test
    fun `pro mode preserves user LUT intent across transport changes`() {
        val source = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val modeSelection = source.substringAfter("private fun applyCinemaModeSelection(")
            .substringBefore("private fun deriveCinemaModeFromFlags")
        val proBranch = modeSelection.substringAfter("CinemaVideoMode.PRO -> {")
            .substringBefore("CinemaVideoMode.LOG -> {")

        assertFalse(
            "Selecting Pro must preserve the user's LUT preference; HDR transport already " +
                "suppresses LUT pixel influence when it is incompatible.",
            proBranch.contains("setLutEnabled(false)")
        )
        assertFalse(proBranch.contains("_lutEnabled.value = false"))
    }

    @Test
    fun `camera view model serializes cpu bound preview LUT resolution`() {
        val source = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val syncBlock = source.substringAfter("private fun syncGpuLutState()")
            .substringBefore("private fun applyResolvedPreviewLut")

        assertTrue(source.contains("LatestWinsSerialResultCoordinator<PreviewLutResolutionWork"))
        assertTrue(syncBlock.contains("pendingPreviewLutRequest.getAndSet(request)"))
        assertTrue(syncBlock.contains("previewLutCoordinator.submit("))
        assertTrue(source.contains("ResolvedPreviewAndRecordLuts"))
        assertTrue(source.contains("applyResolvedSdrRecordLut(work.recordRequest, result.recordLut)"))
        assertFalse(
            "The 65-cube recorder LUT must not be generated synchronously by a Settings callback.",
            syncBlock.contains("colorScience.getPreviewLutFloatArray(recording = true)")
        )
        assertFalse(syncBlock.contains("previewLutSyncJob?.cancel()"))
    }

    @Test
    fun `resolved preview LUT cannot mutate render identity during recording`() {
        val source = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val admission = source.substringAfter("private fun applyResolvedPreviewLut(")
            .substringBefore("private fun replayDeferredPreviewLutPublicationIfSafe")
        val replay = source.substringAfter("private fun replayDeferredPreviewLutPublicationIfSafe")
            .substringBefore("private fun applyResolvedPreviewLutNow")
        val lifecycleReplay = source
            .substringAfter("private fun replayDeferredLifecyclePreferenceRefreshIfSafe()")
            .substringBefore("fun refreshPreferences()")

        assertTrue(admission.contains("previewLutPublicationBlockedByRecording()"))
        assertTrue(admission.contains("recordingSafePreviewLutPublicationGate.deferLatest("))
        assertFalse(admission.contains("advanceVulkanRenderProfileGeneration("))
        assertTrue(replay.contains("claimLatestIfSafe("))
        assertTrue(replay.contains("applyResolvedPreviewLut(publication.sequence, publication.copyForApply())"))
        assertTrue(lifecycleReplay.contains("replayDeferredPreviewLutPublicationIfSafe()"))
        assertTrue(lifecycleReplay.contains("runOnMainIfNeeded"))
        assertTrue(source.contains("recordingSafePreviewLutPublicationGate.close()"))
    }

    private fun repoFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            File(directory, relativePath).takeIf(File::exists)?.let { return it }
            directory = directory.parentFile ?: return File(relativePath)
        }
        return File(relativePath)
    }
}
