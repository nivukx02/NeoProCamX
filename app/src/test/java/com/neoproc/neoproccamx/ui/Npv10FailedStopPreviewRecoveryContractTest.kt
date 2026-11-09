package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10FailedStopPreviewRecoveryContractTest {
    private val stop = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        .readText()
        .substringAfter("private suspend fun stopNpv10RecordingSession()")
        .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")

    @Test
    fun `failed stop restores drained preview before the full master audit`() {
        val errorPath = stop.substringAfter("} catch (t: Throwable) {")
            .substringBefore("} finally {")
        val poison = errorPath.indexOf("npv10Recording.poisonStopBoundaryFailure(")
        val detach = errorPath.indexOf("pipeline.setRawYuvImageSink(null)")
        val recover = errorPath.indexOf("Npv10FailedStopPreviewRecovery.restoreBeforeAudit(")
        val audit = errorPath.indexOf("npv10Recording.stop()")

        assertTrue("Publication must remain poisoned on the error path", poison >= 0)
        assertTrue("Failed stop must detach the capture sink before preview recovery", detach > poison)
        assertTrue("Preview recovery is missing before the multi-gigabyte audit", recover > detach)
        assertTrue("Durable finalization must still run after preview recovery", audit > recover)
        assertTrue(errorPath.contains("drainAcceptedFrames = npv10Recording::awaitAcceptedFrameDrain"))
        assertTrue(errorPath.contains("ingressQuiescent = pipelineIngressQuiescent"))
        assertTrue(errorPath.contains("teardownStarted = viewModelTeardownStarted::get"))
        assertTrue(errorPath.contains("restorePreview = ::restorePreviewAfterNpv10CaptureStop"))
        assertTrue(errorPath.contains("imageIngressSeal?.close()"))
        assertTrue(errorPath.contains("t.addSuppressed(cleanupFailure)"))
    }

    @Test
    fun `error recovery cannot publish a failed master or end foreground durability protection early`() {
        val errorPath = stop.substringAfter("} catch (t: Throwable) {")
            .substringBefore("} finally {")
        assertFalse(errorPath.contains("notifyRecordingSaved("))
        assertFalse(errorPath.contains("publishNpv10("))
        assertFalse(errorPath.contains("stopRecordingForegroundService()"))
        assertTrue(stop.contains("output != null && npv10Recording.lastStopPassedDurabilityAudit()"))
        assertTrue(stop.substringAfterLast("} finally {").contains("stopRecordingForegroundService()"))
    }

    @Test
    fun `failed video preserves captured audio before audit without asserting synchronization`() {
        val errorPath = stop.substringAfter("} catch (t: Throwable) {")
            .substringBefore("} finally {")
        val preserve = errorPath.indexOf("npv10AudioRecording.preserveAfterVideoFailure(t)")
        val audit = errorPath.indexOf("npv10Recording.stop()")
        assertTrue("Video failure must not erase valid captured AAC", preserve >= 0)
        assertTrue("AAC must be retired before the potentially lengthy video audit", audit > preserve)
        assertTrue(errorPath.contains("if (npv10AudioRequiredForActiveSession)"))
        assertFalse("Exited workers still need retirement", errorPath.contains("if (npv10AudioRecording.isActive)"))
        assertFalse(errorPath.contains("stopAndFinalizeAlignedToDuration("))
        val recorder = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10AudioSidecarRecorder.kt")
            .readText()
        val recovery = recorder.substringAfter("fun stopAndPreserveForRecovery()")
            .substringBefore("/**")
        assertTrue(recovery.contains("terminalAlignmentTargetUs.set(NO_TERMINAL_ALIGNMENT)"))
        assertTrue(recovery.contains("terminalAlignmentToleranceUs.set(NO_TERMINAL_ALIGNMENT)"))
        assertTrue(recovery.contains("stopAndFinalizeInternal(captureUntilAligned = false)"))
        assertFalse(recovery.contains("abort()"))
        val cleanup = recorder.substringAfter("fun preserveAfterVideoFailure(videoFailure: Throwable)")
            .substringBefore("/**")
        assertTrue(cleanup.contains("stopAndPreserveForRecovery()"))
        assertTrue(cleanup.contains("avSyncCertified=false"))
        assertTrue(cleanup.contains("videoFailure.addSuppressed(audioFailure)"))
        assertTrue(cleanup.contains("videoFailure.addSuppressed(abortFailure)"))
        assertTrue(cleanup.indexOf("abort()") > cleanup.indexOf(".onFailure { audioFailure"))
    }
}
