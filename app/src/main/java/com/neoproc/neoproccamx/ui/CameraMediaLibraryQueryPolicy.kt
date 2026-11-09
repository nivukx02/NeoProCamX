package com.neoproc.neoproccamx.ui

internal data class CameraMediaLibraryQuery(
    val selection: String,
    val selectionArgs: Array<String>
) {
    override fun equals(other: Any?): Boolean =
        other is CameraMediaLibraryQuery &&
            selection == other.selection &&
            selectionArgs.contentEquals(other.selectionArgs)

    override fun hashCode(): Int =
        31 * selection.hashCode() + selectionArgs.contentHashCode()
}

internal object CameraMediaLibraryQueryPolicy {
    const val MEDIA_OBSERVER_DEBOUNCE_MS = 500L

    private const val PRIMARY_LIBRARY_PATTERN = "%NeoProCamX/%"
    private const val LEGACY_LIBRARY_PATTERN = "%CamX/%"
    private const val NPV10_EXPORT_LIBRARY_PATTERN = "%CamXNPV10/%"
    private const val PRIMARY_LEGACY_FILE_PATTERN = "%/NeoProCamX/%"
    private const val LEGACY_FILE_PATTERN = "%/CamX/%"
    private const val NPV10_EXPORT_FILE_PATTERN = "%/CamXNPV10/%"
    private const val CAPTURE_FILENAME_PATTERN = "CAMX_%"
    private const val NPV10_FILENAME_PATTERN = "NPV10_%"

    fun mediaStoreRelativePathSelection(relativePathColumn: String): CameraMediaLibraryQuery =
        CameraMediaLibraryQuery(
            selection = "($relativePathColumn LIKE ? OR $relativePathColumn LIKE ? OR $relativePathColumn LIKE ?)",
            selectionArgs = arrayOf(
                PRIMARY_LIBRARY_PATTERN,
                LEGACY_LIBRARY_PATTERN,
                NPV10_EXPORT_LIBRARY_PATTERN
            )
        )

    fun legacyFileSelection(
        dataColumn: String,
        displayNameColumn: String
    ): CameraMediaLibraryQuery =
        CameraMediaLibraryQuery(
            selection = "($dataColumn LIKE ? OR $dataColumn LIKE ? OR $dataColumn LIKE ? OR $displayNameColumn LIKE ? OR $displayNameColumn LIKE ?)",
            selectionArgs = arrayOf(
                PRIMARY_LEGACY_FILE_PATTERN,
                LEGACY_FILE_PATTERN,
                NPV10_EXPORT_FILE_PATTERN,
                CAPTURE_FILENAME_PATTERN,
                NPV10_FILENAME_PATTERN
            )
        )

    fun withDateAddedAfter(
        base: CameraMediaLibraryQuery,
        dateAddedColumn: String,
        minDateAddedSeconds: Long
    ): CameraMediaLibraryQuery =
        CameraMediaLibraryQuery(
            selection = "(${base.selection} AND $dateAddedColumn > ?)",
            selectionArgs = base.selectionArgs + minDateAddedSeconds.toString()
        )

    fun withFileExtension(
        base: CameraMediaLibraryQuery,
        displayNameColumn: String,
        extension: String
    ): CameraMediaLibraryQuery {
        val normalizedExtension = extension.trim().removePrefix(".").lowercase()
        require(normalizedExtension.isNotEmpty()) { "File extension must not be blank" }
        return CameraMediaLibraryQuery(
            selection = "(${base.selection} AND $displayNameColumn LIKE ?)",
            selectionArgs = base.selectionArgs + "%.${normalizedExtension}"
        )
    }
}
