package com.neoproc.neoproccamx.recording

internal object RecordingContainerSelectionPolicy {
    fun resolve(
        cinemaMode: CinemaVideoMode,
        rawVideoEnabled: Boolean,
        logVideoEnabled: Boolean,
        hdrMode: String
    ): RecordingContainerSelection {
        val rawActive = rawVideoEnabled || cinemaMode == CinemaVideoMode.RAW
        val label = when {
            rawActive -> "Original Quality-Preserving MOV"
            logVideoEnabled || hdrMode != "OFF" -> "QuickTime MOV"
            else -> "QuickTime MOV"
        }
        return RecordingContainerSelection(
            container = RecordingContainer.MOV,
            fileExtension = "mov",
            mediaStoreMime = "video/quicktime",
            userVisibleLabel = label
        )
    }
}
