package com.neoproc.neoproccamx.recording

internal data class RecordingDeviceRuntimeProof(
    val deviceLogValidated: Boolean,
    val adbLogEvidencePath: String,
    val metadata: Map<String, String>
)

internal object RecordingDeviceRuntimeProofPolicy {
    fun evaluate(
        runtimeEvidence: RecordingRuntimeEvidence,
        expectedFrameCount: Long,
        actualFrameCount: Long,
        droppedFrames: Int,
        recordedFileName: String,
        recordedFileBytes: Long,
        mediaStoreValidated: Boolean,
        runtimeMetadata: Map<String, String> = emptyMap()
    ): RecordingDeviceRuntimeProof {
        val fileFinalized =
            mediaStoreValidated &&
                recordedFileName.isNotBlank() &&
                recordedFileBytes > 1L
        val frameCadenceProven =
            expectedFrameCount > 0L &&
                actualFrameCount >= expectedFrameCount &&
                droppedFrames == 0
        val runtimeHotPathDroppedFrames = runtimeHotPathDroppedFrames(runtimeMetadata)
        val runtimeHotPathZeroDrop = runtimeHotPathDroppedFrames == 0L
        val deviceLogValidated =
            runtimeEvidence.gpuProcessingValidated &&
                runtimeEvidence.processedRecordingFramesValidated &&
                runtimeEvidence.zeroCopyValidated &&
                runtimeEvidence.performanceTelemetryValidated &&
                frameCadenceProven &&
                runtimeHotPathZeroDrop &&
                fileFinalized
        val evidencePath = if (deviceLogValidated) {
            "in-process:RECORDING_STOP_VERDICT+runtimeEvidence+MediaStore"
        } else {
            ""
        }

        return RecordingDeviceRuntimeProof(
            deviceLogValidated = deviceLogValidated,
            adbLogEvidencePath = evidencePath,
            metadata = mapOf(
                "deviceRuntimeEvidenceValidated" to deviceLogValidated.toString(),
                "deviceRuntimeEvidencePath" to evidencePath,
                "deviceRuntimeFrameCadenceProven" to frameCadenceProven.toString(),
                "deviceRuntimeFinalizedFileProven" to fileFinalized.toString(),
                "deviceRuntimeHotPathZeroDrop" to runtimeHotPathZeroDrop.toString(),
                "deviceRuntimeHotPathDroppedFrames" to runtimeHotPathDroppedFrames.toString()
            )
        )
    }

    private fun runtimeHotPathDroppedFrames(metadata: Map<String, String>): Long {
        val importFailures = maxOf(
            metadata.longValue("offscreenEglFailedFrames"),
            metadata.longValue("vulkanSessionFailedImports")
        )
        val dispatchFailures =
            metadata.longValue("realtimeDispatchDroppedFrames") +
                metadata.longValue("realtimeDispatchRejectedFrames")
        return (importFailures + dispatchFailures).coerceAtLeast(0L)
    }

    private fun Map<String, String>.longValue(key: String): Long =
        this[key]?.trim()?.toLongOrNull() ?: 0L
}
