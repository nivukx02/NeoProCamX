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
    private const val CONVERSION_LIBRARY_RELATIVE_PATH = "Movies/$CAMERA_LIBRARY_FOLDER"

    fun resolve(): RecordingOutputRoute =
        RecordingOutputRoute(
            relativePath = CAMERA_LIBRARY_RELATIVE_PATH,
            legacyRootDirectory = LEGACY_DCIM_ROOT,
            legacySubdirectory = CAMERA_LIBRARY_FOLDER
        )

    /**
     * Exact MediaStore homes used by app-owned QuickTime rows.
     *
     * Recording writes to DCIM, RAW/record-develop and packet-copy exports write to Movies/CamX,
     * and NPV10 direct exports use their dedicated Movies/CamXNPV10 album. Keep this list bounded:
     * startup recovery may publish or delete a structurally invalid pending row in these locations.
     */
    fun ownedPendingMovRelativePaths(): List<String> =
        listOf(
            CAMERA_LIBRARY_RELATIVE_PATH,
            CONVERSION_LIBRARY_RELATIVE_PATH,
            Npv10MovPublishPolicy.RELATIVE_PATH
        ).distinct()

    /** MediaStore providers may persist RELATIVE_PATH with or without the documented trailing '/'. */
    fun ownedPendingMovStoredPathValues(): List<String> =
        ownedPendingMovRelativePaths()
            .flatMap { path ->
                val canonical = path.trimEnd('/')
                listOf(canonical, "$canonical/")
            }
            .distinct()
}
