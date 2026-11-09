package com.neoproc.neoproccamx.recording

private const val ANDROID_R_SDK = 30
private const val ANDROID_Q_SDK = 29
private const val MEDIASTORE_METADATA_RELATIVE_PATH = "Documents/CamX/Metadata"

internal enum class RecordingOutputStorageKind {
    MEDIASTORE_PENDING_VIDEO,
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
    val masteringPeakNits: Int,
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

        val hdrRequested =
            hdrIntent.contains("ndr10") ||
                hdrIntent.contains("nivukx") ||
                dynamicRange.contains("ndr10") ||
                dynamicRange.contains("nivukx") ||
                selectedColorDescription.contains("ndr10") ||
                selectedColorDescription.contains("nivukx") ||
                normalizedDescription.contains("st2084")
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
                selectedColorDescription.contains("nivukx")
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

        return when {
            hdrRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                // The NDR family (NDR10+/NIVUKX/APEX) transport is decided by NeoNdrTruePqTransportPolicy
                // so this MediaStore COLOR_TRANSFER column + sidecar stay coherent with the actual
                // recorded pixels. Fork A (default): ST.2084 — the record shader converts HLG->PQ so
                // ST.2084 is truthful (and carries the PQ ST2086 mastering block via HdrStaticMetadata).
                // Fork B fallback: HLG (ARIB STD-B67) — matches the un-converted HLG pixels, no PQ
                // mastering block. Tagging ST2084 over HLG pixels was the red-blown mismatch (c1b2d2e);
                // that can no longer happen because the tag follows the same flag as the pixel curve.
                // Record-develop uses the same Neo NDR transfer identity as the encoder. It must not
                // silently relabel NDR/NDR10+/NIVUKX Vision as an HLG product mode.
                colorTransfer = if (NeoNdrTruePqTransportPolicy.enabled) {
                    RecordingMediaStoreColorTransfer.ST2084
                } else {
                    RecordingMediaStoreColorTransfer.HLG
                },
                colorRange = RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = true,
                workingSpace = if (NeoNdrTruePqTransportPolicy.enabled) {
                    Bt2100TransportPolicy.WORKING_SPACE_PQ
                } else {
                    Bt2100TransportPolicy.WORKING_SPACE_HLG
                },
                masteringPeakNits = 1_000,
                minimumBlackNits = 0.0001
            )
            logRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                colorTransfer = RecordingMediaStoreColorTransfer.LINEAR,
                colorRange = RecordingMediaStoreColorRange.FULL,
                sidecarRequired = true,
                workingSpace = wideGamutWorkingSpace,
                masteringPeakNits = 1_000,
                minimumBlackNits = 0.0001
            )
            wideGamutRequested && (!rec709Requested || manualWideGamutRequested) -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT2020,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = wideGamutRequested,
                workingSpace = wideGamutWorkingSpace,
                masteringPeakNits = 1_000,
                minimumBlackNits = 0.0001
            )
            tenBitRequested -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT709,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = RecordingMediaStoreColorRange.FULL,
                sidecarRequired = false,
                workingSpace = "Rec.709",
                masteringPeakNits = 100,
                minimumBlackNits = 0.01
            )
            else -> RecordingMediaStoreColorMetadata(
                colorStandard = RecordingMediaStoreColorStandard.BT709,
                colorTransfer = RecordingMediaStoreColorTransfer.SDR_VIDEO,
                colorRange = RecordingMediaStoreColorRange.LIMITED,
                sidecarRequired = false,
                workingSpace = "Rec.709",
                masteringPeakNits = 100,
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
        val colorMetadata = resolveColorMetadata(contract.description)
        return RecordingOutputCreatePlan(
            storageKind = if (mediaStorePending) {
                RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO
            } else {
                RecordingOutputStorageKind.LEGACY_PUBLIC_FILE
            },
            pending = mediaStorePending,
            displayName = contract.descriptor.fileName,
            mimeType = contract.descriptor.mimeType,
            relativePath = contract.route.relativePath,
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
            publishMediaStore = storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO && pending,
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
            deleteMediaStoreUri = storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO && hasUri,
            deleteLegacyFile = storageKind == RecordingOutputStorageKind.LEGACY_PUBLIC_FILE && hasFile,
            deletePrivateDevelopFile = storageKind == RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER && hasFile
        )

    private fun parseDescriptionFields(description: String): Map<String, String> =
        description
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { field ->
                field.substringBefore('=') to field.substringAfter('=')
            }
            .toMap()
}
