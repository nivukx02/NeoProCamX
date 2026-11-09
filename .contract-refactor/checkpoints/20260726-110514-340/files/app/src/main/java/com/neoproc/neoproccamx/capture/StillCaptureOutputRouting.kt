package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal enum class StillCaptureMediaCollection {
    IMAGES,
    FILES
}

internal enum class StillCaptureOutputKind {
    JPEG,
    DNG,
    RAW_PACKAGE
}

internal data class StillCaptureOutputSpec(
    val extension: String,
    val mimeType: String,
    val relativePath: String,
    val legacyRootDirectory: String,
    val legacySubdirectory: String,
    val collection: StillCaptureMediaCollection
)

internal object StillCaptureOutputRouting {
    private const val LEGACY_DCIM_ROOT = "DCIM"
    private const val LEGACY_DOCUMENTS_ROOT = "Documents"
    private const val LEGACY_RAW_PACKAGE_SUBDIRECTORY = "CamX/RAW"
    private const val LEGACY_STRICT_RAW_PACKAGE_SUBDIRECTORY = "CamX/RAW/CINEMA"
    private const val JPEG_RELATIVE_PATH = "DCIM/CamX"
    private const val RAW_IMAGE_RELATIVE_PATH = "DCIM/CamX/RAW"
    private const val RAW_PACKAGE_RELATIVE_PATH = "Documents/CamX/RAW"

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun resolve(kind: StillCaptureOutputKind): StillCaptureOutputSpec =
        when (kind) {
            StillCaptureOutputKind.JPEG ->
                StillCaptureOutputSpec(
                    extension = "jpg",
                    mimeType = "image/jpeg",
                    relativePath = JPEG_RELATIVE_PATH,
                    legacyRootDirectory = LEGACY_DCIM_ROOT,
                    legacySubdirectory = "CamX",
                    collection = StillCaptureMediaCollection.IMAGES
                )

            StillCaptureOutputKind.DNG ->
                StillCaptureOutputSpec(
                    extension = "dng",
                    mimeType = "image/x-adobe-dng",
                    relativePath = RAW_IMAGE_RELATIVE_PATH,
                    legacyRootDirectory = LEGACY_DCIM_ROOT,
                    legacySubdirectory = "CamX/RAW",
                    collection = StillCaptureMediaCollection.IMAGES
                )

            StillCaptureOutputKind.RAW_PACKAGE ->
                StillCaptureOutputSpec(
                    extension = "zip",
                    mimeType = "application/zip",
                    relativePath = RAW_PACKAGE_RELATIVE_PATH,
                    legacyRootDirectory = LEGACY_DOCUMENTS_ROOT,
                    legacySubdirectory = if (strictHighEndEnabled()) {
                        LEGACY_STRICT_RAW_PACKAGE_SUBDIRECTORY
                    } else {
                        LEGACY_RAW_PACKAGE_SUBDIRECTORY
                    },
                    collection = StillCaptureMediaCollection.FILES
                )
        }
}
