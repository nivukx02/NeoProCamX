package com.neoproc.neoproccamx.recording

private const val ANDROID_R_SDK = 30
private const val ANDROID_Q_SDK = 29
private const val MEDIASTORE_METADATA_RELATIVE_PATH = "Documents/CamX/Metadata"
private const val MEDIASTORE_NIVUKX_RELATIVE_PATH = "Documents/CamX"

internal enum class RecordingOutputStorageKind {
    MEDIASTORE_PENDING_VIDEO,
    /** Custom/non-platform media type stored in MediaStore.Files, not the strict Video collection. */
    MEDIASTORE_PENDING_FILE,
    LEGACY_PUBLIC_FILE,

    /**
     * Record-then-develop LIGHT intermediate: a File-backed target in the app-private develop-master dir
     * (NOT the gallery). finalize/failure key off `==` the two public kinds, so this kind performs no
     * MediaStore publish and no MediaScanner scan automatically — the flat throwaway never clutters the
     * gallery and is discovered instead by the SDR/NDR develop cards via RecordDevelopMasterStore.
     */
    PRIVATE_DEVELOP_MASTER
}

internal enum class RecordingPublishPhase {
    PHASE_PREPARE,
    PHASE_WRITE,
    PHASE_COMMIT,
    PHASE_ROLLBACK
}

internal enum class RecordingMediaStoreColorStandard {
    BT709,
    BT2020
}

internal enum class RecordingMediaStoreColorTransfer {
    SDR_VIDEO,
    LINEAR,
    ST2084,
    HLG
}

internal enum class RecordingMediaStoreColorRange {
    LIMITED,
    FULL
}

internal data class RecordingMediaStoreColorMetadata(
    val colorStandard: RecordingMediaStoreColorStandard,
    val colorTransfer: RecordingMediaStoreColorTransfer,
    val colorRange: RecordingMediaStoreColorRange,
    val sidecarRequired: Boolean,
    val workingSpace: String,
    val masteringPeakNits: Double,
    val minimumBlackNits: Double
)

internal data class RecordingPublishTransaction(
    val phase: RecordingPublishPhase,
    val colorMetadata: RecordingMediaStoreColorMetadata,
    val description: String
)

internal data class RecordingOutputCreatePlan(
    val storageKind: RecordingOutputStorageKind,
    val pending: Boolean,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val legacyRootDirectory: String,
    val legacySubdirectory: String,
    val description: String,
    val colorMetadata: RecordingMediaStoreColorMetadata,
    val transaction: RecordingPublishTransaction
)

internal data class RecordingOutputFinalizeDecision(
    val closeDescriptorFirst: Boolean,
    val publishMediaStore: Boolean,
    val scanLegacyFile: Boolean,
    val finalDescription: String,
    val colorMetadata: RecordingMediaStoreColorMetadata,
    val writeSidecarMetadata: Boolean,
    val transaction: RecordingPublishTransaction
)

internal data class RecordingOutputFailureCleanupDecision(
    val closeDescriptorFirst: Boolean,
    val deleteMediaStoreUri: Boolean,
    val deleteLegacyFile: Boolean,
    /** Delete the private develop-master File on failure so a partial/0-byte clip is not surfaced by the develop card. */
    val deletePrivateDevelopFile: Boolean = false
)

internal object RecordingMediaStoreLifecyclePolicy {
    fun resolveMetadataSidecarRelativePath(): String = MEDIASTORE_METADATA_RELATIVE_PATH

    fun resolveColorMetadata(description: String): RecordingMediaStoreColorMetadata {
        val fields = parseDescriptionFields(description)
        val normalizedDescription = description.lowercase()
        val color = fields["color"].orEmpty().lowercase()
        val hdrIntent = fields["hdrIntent"].orEmpty().lowercase()
        val dynamicRange = fields["dynamicRange"].orEmpty().lowercase()
        val logCurve = fields["logCurve"].orEmpty().lowercase()
        val bitDepth = fields["bitDepth"].orEmpty().lowercase()
        val selectedColorSpace = fields["selectedColorSpace"].orEmpty().lowercase()
        val selectedColorScience = fields["selectedColorScience"].orEmpty().lowercase()
        val selectedColorDescription = "$selectedColorSpace $selectedColorScience"
        val explicitColorRange = fields["colorRange"].toStrictColorRangeOrNull()
        val hdrIdentity = "$color $hdrIntent $dynamicRange $selectedColorDescription"
        val ndr10Requested = hdrIdentity.contains("ndr10")
        val nivukxOrApexRequested =
            hdrIdentity.contains("nivukx") || hdrIdentity.contains("apex")
        val genericPqRequested =
            hdrIdentity.contains("st2084") ||
                hdrIdentity.contains("st.2084") ||
                hdrIdentity.contains("pq") ||
                hdrIdentity.contains("hdr10") ||
                fields["colorTransfer"] == "ST2084" ||
                normalizedDescription.contains("st2084")

        val hdrRequested = ndr10Requested || nivukxOrApexRequested || genericPqRequested
        val logRequested =
            logCurve.contains("n-log") ||
                logCurve.contains("nlog") ||
                dynamicRange.contains("log sdr") ||
                selectedColorDescription.contains("n-log") ||
                selectedColorDescription.contains("nlog") ||
                normalizedDescription.contains("n-log")
        val manualWideGamutRequested =
            selectedColorDescription.contains("2020") ||
                selectedColorDescription.contains("p3") ||
                selectedColorDescription.contains("aces") ||
                selectedColorDescription.contains("wide") ||
                selectedColorDescription.contains("neo") ||
                selectedColorDescription.contains("ndr10") ||
                selectedColorDescription.contains("nivukx") ||
                selectedColorDescription.contains("apex")
        val rec709Requested =
            color.contains("709") ||
                dynamicRange.contains("rec.709") ||
                dynamicRange.contains("bt.709")
        val wideGamutRequested =
            hdrRequested ||
                logRequested ||
                color.contains("2020") ||
                color.contains("p3") ||
                color.contains("aces") ||
                color.contains("wide") ||
                color.contains("neo") ||
                dynamicRange.contains("2020") ||
                dynamicRange.contains("p3") ||
                dynamicRange.contains("aces") ||
                dynamicRange.contains("wide") ||
                manualWideGamutRequested
        val tenBitRequested = bitDepth.contains("10") || dynamicRange.contains("10-bit")
        val wideGamutWorkingSpace = resolveManualWorkingSpace(
            selectedColorSpace = selectedColorSpace,
            selectedColorScience = selectedColorScience
        )
        val pqMasteringPeakNits = when {
            nivukxOrApexRequested -> 10_000.0
            ndr10Requested -> 5_000.0
            genericPqRequested ->
                fields["masteringPeakNits"].toStrictMasteringPeakOrNull() ?: 1_000.0
            else -> null
        }

        return when {
            hdrRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                // This metadata is resolved only from the immutable encoder-session description.
                // Mutable process-global transport flags cannot relabel an already recorded clip.
                colorTransfer = RecordingMediaStoreColorTransfer.ST2084,
                colorRange = explicitColorRange ?: RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = true,
                workingSpace = Bt2100TransportPolicy.WORKING_SPACE_PQ,
                masteringPeakNits = pqMasteringPeakNits ?: 1_000.0,
                minimumBlackNits = 0.0001
            )
            logRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                colorTransfer = RecordingMediaStoreColorTransfer.LINEAR,
                colorRange = explicitColorRange ?: RecordingMediaStoreColorRange.FULL,
                sidecarRequired = true,
                workingSpace = wideGamutWorkingSpace,
                masteringPeakNits = 1_000.0,
                minimumBlackNits = 0.0001
            )
            wideGamutRequested && (!rec709Requested || manualWideGamutRequested) -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = explicitColorRange ?: RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = wideGamutRequested,
                workingSpace = wideGamutWorkingSpace,
                masteringPeakNits = 1_000.0,
                minimumBlackNits = 0.0001
            )
            tenBitRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT709,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = explicitColorRange ?: RecordingMediaStoreColorRange.FULL,
                sidecarRequired = false,
                workingSpace = "Rec.709",
                masteringPeakNits = 100.0,
                minimumBlackNits = 0.01
            )
            else -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT709,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = explicitColorRange ?: RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = false,
                workingSpace = "Rec.709",
                masteringPeakNits = 100.0,
                minimumBlackNits = 0.01
            )
        }
    }

    private fun resolveManualWorkingSpace(
        selectedColorSpace: String,
        selectedColorScience: String
    ): String {
        val selected = "$selectedColorSpace $selectedColorScience"
        return when {
            selected.contains("ap0") -> "ACES2065-1"
            selected.contains("aces") || selected.contains("ap1") -> "ACEScg"
            selected.contains("neo wide") || selected.contains("wide gamut") -> "Neo Wide Gamut"
            else -> "ACEScg"
        }
    }

    fun colorMetadataColumnsSupported(sdkInt: Int): Boolean = sdkInt >= ANDROID_R_SDK

    fun resolveCreate(
        sdkInt: Int,
        contract: RecordingMovOutputContract
    ): RecordingOutputCreatePlan {
        val mediaStorePending = sdkInt >= ANDROID_Q_SDK
        val nivukxMediaStoreFile =
            mediaStorePending && contract.selection.container == RecordingContainer.NIVUKX
        val colorMetadata = resolveColorMetadata(contract.description)
        return RecordingOutputCreatePlan(
            storageKind = if (mediaStorePending) {
                if (nivukxMediaStoreFile) {
                    RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE
                } else {
                    RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO
                }
            } else {
                RecordingOutputStorageKind.LEGACY_PUBLIC_FILE
            },
            pending = mediaStorePending,
            displayName = contract.descriptor.fileName,
            mimeType = contract.descriptor.mimeType,
            // Generic files cannot truthfully claim a platform video MIME. Documents is the valid
            // scoped-storage home for the opaque NIVUKX master while MOV remains in DCIM/CamX.
            relativePath = if (nivukxMediaStoreFile) {
                MEDIASTORE_NIVUKX_RELATIVE_PATH
            } else {
                contract.route.relativePath
            },
            legacyRootDirectory = contract.route.legacyRootDirectory,
            legacySubdirectory = contract.route.legacySubdirectory,
            description = contract.description,
            colorMetadata = colorMetadata,
            transaction = RecordingPublishTransaction(
                phase = RecordingPublishPhase.PHASE_PREPARE,
                colorMetadata = colorMetadata,
                description = contract.description
            )
        )
    }

    fun resolveFinalize(
        storageKind: RecordingOutputStorageKind,
        pending: Boolean,
        finalDescription: String
    ): RecordingOutputFinalizeDecision {
        val colorMetadata = resolveColorMetadata(finalDescription)
        return RecordingOutputFinalizeDecision(
            closeDescriptorFirst = true,
            publishMediaStore = storageKind.isPendingMediaStore() && pending,
            scanLegacyFile = storageKind == RecordingOutputStorageKind.LEGACY_PUBLIC_FILE,
            finalDescription = finalDescription,
            colorMetadata = colorMetadata,
            writeSidecarMetadata = colorMetadata.sidecarRequired,
            transaction = RecordingPublishTransaction(
                phase = RecordingPublishPhase.PHASE_COMMIT,
                colorMetadata = colorMetadata,
                description = finalDescription
            )
        )
    }

    fun resolveFailureCleanup(
        storageKind: RecordingOutputStorageKind,
        hasUri: Boolean,
        hasFile: Boolean
    ): RecordingOutputFailureCleanupDecision =
        RecordingOutputFailureCleanupDecision(
            closeDescriptorFirst = true,
            deleteMediaStoreUri = storageKind.isPendingMediaStore() && hasUri,
            deleteLegacyFile = storageKind == RecordingOutputStorageKind.LEGACY_PUBLIC_FILE && hasFile,
            deletePrivateDevelopFile = storageKind == RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER && hasFile
        )

    private fun RecordingOutputStorageKind.isPendingMediaStore(): Boolean =
        this == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO ||
            this == RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE

    private fun String?.toStrictColorRangeOrNull(): RecordingMediaStoreColorRange? =
        when (this) {
            "FULL" -> RecordingMediaStoreColorRange.FULL
            "LIMITED" -> RecordingMediaStoreColorRange.LIMITED
            else -> null
        }

    private fun String?.toStrictMasteringPeakOrNull(): Double? {
        val parsed = this?.toDoubleOrNull() ?: return null
        return parsed.takeIf { it.isFinite() && it in 1_000.0..10_000.0 }
    }

    /**
     * The base description owns authoritative session fields and scene metadata is appended later.
     * Keep the first occurrence so a stale scene packet cannot overwrite range or mastering peak.
     */
    private fun parseDescriptionFields(description: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        description
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { field ->
                val key = field.substringBefore('=')
                if (!fields.containsKey(key)) {
                    fields[key] = field.substringAfter('=')
                }
            }
        return fields
    }
}
