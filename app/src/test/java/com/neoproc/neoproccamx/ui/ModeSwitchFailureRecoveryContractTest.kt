package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModeSwitchFailureRecoveryContractTest {
    private val source: String
        get() = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun `begin and end failures abort transition state and rethrow original failure`() {
        val begin = source
            .substringAfter("fun beginModeSwitchPreviewWorkBatch()")
            .substringBefore("fun endModeSwitchPreviewWorkBatch()")
        val end = source
            .substringAfter("fun endModeSwitchPreviewWorkBatch()")
            .substringBefore("private fun abortModeSwitchPreviewWorkBatch")

        assertTrue(begin.contains("catch (failure: Throwable)"))
        assertTrue(begin.contains("abortModeSwitchPreviewWorkBatch(failure)"))
        assertTrue(begin.contains("throw failure"))
        assertTrue(end.contains("if (modeSwitchCriticalDepth.get() <= 0) return"))
        assertTrue(end.contains("catch (failure: Throwable)"))
        assertTrue(end.contains("abortModeSwitchPreviewWorkBatch(failure)"))
        assertTrue(end.contains("throw failure"))
    }

    @Test
    fun `failure cleanup clears all deferred work and stale session state`() {
        val cleanup = source
            .substringAfter("private fun abortModeSwitchPreviewWorkBatch")
            .substringBefore("private fun scheduleModeSwitchFailureRecovery")

        assertTrue(cleanup.contains("modeSwitchPreviewWorkCoalescer.abort()"))
        assertTrue(cleanup.contains("modeSwitchSessionTransaction.abort()"))
        assertTrue(cleanup.contains("modeSwitchEffectiveColorSyncPending = false"))
        assertTrue(cleanup.contains("modeSwitchCameraLookSyncPending = false"))
        assertTrue(cleanup.contains("modeSwitchAutomaticBitratePending = false"))
        assertTrue(cleanup.contains("modeSwitchVulkanConfigPending = false"))
        assertTrue(cleanup.contains("modeSwitchRuntimeStatsPending = false"))
        assertTrue(cleanup.contains("modeSwitchCriticalDepth.set(0)"))
        assertTrue(cleanup.contains("scheduleModeSwitchFailureRecovery(failure)"))
    }

    @Test
    fun `recovery is single flight bounded and forces the settled preview session`() {
        val recovery = source
            .substringAfter("private fun scheduleModeSwitchFailureRecovery")
            .substringBefore("private fun releaseAiTransitionCriticalWhenPreviewSettles")

        assertTrue(
            recovery.contains(
                "modeSwitchFailureRecoveryScheduled.compareAndSet(false, true)"
            )
        )
        assertTrue(recovery.contains("viewModelScope.launch(Dispatchers.Main)"))
        assertTrue(recovery.contains("refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)"))
        assertTrue(recovery.contains("catch (recoveryFailure: Throwable)"))
        assertTrue(recovery.contains("recoveryJob.invokeOnCompletion"))
        assertTrue(recovery.contains("modeSwitchFailureRecoveryScheduled.set(false)"))
    }
}
