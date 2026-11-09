package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawVideoManagerReaderCloseContractTest {
    @Test
    fun `RAW reader close fails active recording and proves every asynchronous image owner`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).readText()
        val method = source
            .substringAfter("internal fun awaitImageReaderOwnershipIdle(timeoutMs: Long): Boolean {")
            .substringBefore("private fun remainingReaderCloseMs")

        val activeGate = method.indexOf("captureLifecycleActive.get()")
        val clearIdle = method.indexOf("clearIdleRawPreviewFrames()")
        val previewWait = method.indexOf("awaitIdleRawPreviewWorkerIdleForTeardown")
        val tapWait = method.indexOf("awaitPreviewTapIdle")
        val finalProof = method.indexOf("sensorLinearNdrRecordingLifecycleLock.withLock")

        assertTrue(activeGate >= 0)
        assertTrue(clearIdle > activeGate)
        assertTrue(previewWait > clearIdle)
        assertTrue(tapWait > previewWait)
        assertTrue(finalProof > tapWait)
        assertTrue(method.contains("rawCaptureIngressAdmission.get() != null"))
        assertTrue(method.contains("rawCaptureResultIngressIdentity.get() != null"))
        assertTrue(method.contains("ownedWriterImages.get() != 0"))
        assertTrue(method.contains("activeWrites.get() != 0"))
        assertTrue(method.contains("writeQueue.isNotEmpty()"))
        assertTrue(method.contains("pendingRawCaptureResultsByTimestamp.isEmpty()"))
        assertTrue(method.contains("unmatchedIdleRawImages.isEmpty()"))
        assertTrue(method.contains("sensorLinearNdrPendingImages.isEmpty()"))
        assertTrue(method.contains("sensorLinearNdrReadyBySequence.isEmpty()"))
        assertTrue(method.contains("isSensorLinearNdrRecordingWorkerQuiescentForTeardown()"))
    }
}
