package com.neoproc.neoproccamx.recording

internal data class RecordingMovOutputContract(
    val selection: RecordingContainerSelection,
    val descriptor: RecordingOutputTargetDescriptor,
    val route: RecordingOutputRoute,
    val description: String
)

internal object RecordingMovOutputContractPolicy {
    fun resolve(
        baseName: String,
        outputLabelSuffix: String,
        cinemaMode: CinemaVideoMode,
        rawVideoEnabled: Boolean,
        logVideoEnabled: Boolean,
        hdrMode: String,
        descriptionSnapshot: RecordingOutputDescriptionSnapshot
    ): RecordingMovOutputContract {
        val selection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = cinemaMode,
            rawVideoEnabled = rawVideoEnabled,
            logVideoEnabled = logVideoEnabled,
            hdrMode = hdrMode
        )
        require(selection.container != RecordingContainer.MP4) {
            "Legacy MP4 output is not enabled; use QuickTime MOV or NIVUKX"
        }

        return RecordingMovOutputContract(
            selection = selection,
            descriptor = RecordingOutputTargetPolicy.resolve(
                selection = selection,
                baseName = baseName,
                outputLabelSuffix = outputLabelSuffix
            ),
            route = RecordingOutputRouting.resolve(),
            description = RecordingOutputDescriptionPolicy.build(descriptionSnapshot)
        )
    }
}
