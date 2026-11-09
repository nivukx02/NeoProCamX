package com.neoproc.neoproccamx.recording

internal enum class RecordingContainer {
    MP4,
    MOV,
    NIVUKX
}

internal data class RecordingContainerSelection(
    val container: RecordingContainer,
    val fileExtension: String,
    val mediaStoreMime: String,
    val userVisibleLabel: String
)

internal data class RecordingOutputTargetDescriptor(
    val fileName: String,
    val mimeType: String,
    val containerLabel: String
)
