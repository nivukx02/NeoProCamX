package com.neoproc.neoproccamx.recording

internal object RecordingOutputTargetPolicy {
    fun resolve(
        selection: RecordingContainerSelection,
        baseName: String,
        outputLabelSuffix: String
    ): RecordingOutputTargetDescriptor {
        // baseName is the user-facing name ("CamX 15072026" by default, or the user's custom one-shot
        // name) resolved by RecordingFileNamePolicy; outputLabelSuffix is the optional debug/device-matrix
        // validation label (empty for normal recordings). Order keeps the validation label discoverable
        // via displayName.contains(label) in RecordingDeviceMatrixMetadataPolicy.
        return RecordingOutputTargetDescriptor(
            fileName = "${baseName}${outputLabelSuffix}.${selection.fileExtension}",
            mimeType = selection.mediaStoreMime,
            containerLabel = selection.userVisibleLabel
        )
    }
}
