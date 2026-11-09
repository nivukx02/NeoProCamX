package com.neoproc.neoproccamx.recording

internal data class RecordingOutputRoute(
    val relativePath: String,
    val legacyRootDirectory: String,
    val legacySubdirectory: String
)

internal object RecordingOutputRouting {
    private const val LEGACY_DCIM_ROOT = "DCIM"
    private const val CAMERA_LIBRARY_FOLDER = "CamX"
    private const val CAMERA_LIBRARY_RELATIVE_PATH = "DCIM/$CAMERA_LIBRARY_FOLDER"

    fun resolve(): RecordingOutputRoute =
        RecordingOutputRoute(
            relativePath = CAMERA_LIBRARY_RELATIVE_PATH,
            legacyRootDirectory = LEGACY_DCIM_ROOT,
            legacySubdirectory = CAMERA_LIBRARY_FOLDER
        )
}
