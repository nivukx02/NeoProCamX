package com.neoproc.neoproccamx.recording

import java.util.Locale

internal object RecordingContainerSelectionPolicy {
    fun resolve(
        cinemaMode: CinemaVideoMode,
        rawVideoEnabled: Boolean,
        logVideoEnabled: Boolean,
        hdrMode: String
    ): RecordingContainerSelection {
        val rawActive = rawVideoEnabled || cinemaMode == CinemaVideoMode.RAW
        val normalizedHdrMode = hdrMode
            .filter(Char::isLetterOrDigit)
            .uppercase(Locale.ROOT)
        // The custom container is deliberately scoped to the user's explicit NIVUKX Vision mode.
        // Every other mode retains the proven QuickTime path and external editor compatibility.
        if (!rawActive && normalizedHdrMode.contains("NIVUKX")) {
            return RecordingContainerSelection(
                container = RecordingContainer.NIVUKX,
                fileExtension = NivukxContainerFormat.FILE_EXTENSION,
                mediaStoreMime = NivukxContainerFormat.MEDIASTORE_MIME_TYPE,
                userVisibleLabel = NivukxContainerFormat.USER_VISIBLE_LABEL
            )
        }
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
