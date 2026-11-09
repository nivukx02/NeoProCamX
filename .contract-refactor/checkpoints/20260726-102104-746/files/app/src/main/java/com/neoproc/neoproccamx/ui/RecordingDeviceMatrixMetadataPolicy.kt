package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import kotlin.math.abs

internal enum class RecordingDeviceMatrixValidationCode {
    MISSING_SAVED_CLIP,
    DISPLAY_NAME_MISMATCH,
    CONTAINER_MIME_MISMATCH,
    CONTAINER_EXTENSION_MISMATCH,
    DELIVERY_KIND_MISMATCH,
    LOG_SAVED_AS_HDR,
    SDR_SAVED_AS_HDR,
    RAW_SAVED_AS_PROCESSED,
    COLOR_METADATA_MISMATCH,
    HARDWARE_METADATA_FALLBACK,
    MEDIASTORE_MIME_FALLBACK,
    RECORDING_FPS_FALLBACK,
    BLOCKING_LOG_ISSUE
}

internal data class RecordingDeviceMatrixValidationIssue(
    val code: RecordingDeviceMatrixValidationCode,
    val message: String
)

internal data class RecordingDeviceMatrixObservedMetadata(
    val validationLabel: String,
    val displayName: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val captureFrameRate: Float?,
    val colorStandard: Int?,
    val colorTransfer: Int?,
    val colorRange: Int?,
    val durationMs: Long?,
    val bitrateBitsPerSecond: Int?,
    val relativePath: String?,
    val savedUri: String?
)

internal data class RecordingDeviceMatrixValidationResult(
    val rowId: String,
    val validationLabel: String,
    val blockingIssues: List<RecordingDeviceMatrixValidationIssue>,
    val warnings: List<RecordingDeviceMatrixValidationIssue>
) {
    val passed: Boolean = blockingIssues.isEmpty() && warnings.isEmpty()
    val recordable: Boolean = blockingIssues.isEmpty()
}

internal object RecordingDeviceMatrixMetadataPolicy {
    fun validate(
        row: RecordingDeviceMatrixRow,
        observed: RecordingDeviceMatrixObservedMetadata,
        logClassification: RecordingDeviceMatrixLogClassification
    ): RecordingDeviceMatrixValidationResult {
        val blocking = mutableListOf<RecordingDeviceMatrixValidationIssue>()
        val warnings = mutableListOf<RecordingDeviceMatrixValidationIssue>()

        logClassification.blockingIssues.forEach { line ->
            blocking += RecordingDeviceMatrixValidationIssue(
                code = RecordingDeviceMatrixValidationCode.BLOCKING_LOG_ISSUE,
                message = line
            )
        }

        if (observed.savedUri.isNullOrBlank()) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.MISSING_SAVED_CLIP,
                "No saved MediaStore URI was observed for ${row.validationLabel}"
            )
        }
        if (observed.displayName?.contains(row.validationLabel) != true) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.DISPLAY_NAME_MISMATCH,
                "Display name ${observed.displayName} does not include ${row.validationLabel}"
            )
        }
        if (observed.displayName?.endsWith(".${row.expectedContainerExtension}", ignoreCase = true) != true) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.CONTAINER_EXTENSION_MISMATCH,
                "Display name ${observed.displayName} is not .${row.expectedContainerExtension}"
            )
        }
        if (!isAcceptedContainerMime(row, observed.mimeType)) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.CONTAINER_MIME_MISMATCH,
                "MIME ${observed.mimeType} does not match ${row.expectedMediaStoreMime}"
            )
        } else if (observed.mimeType != row.expectedMediaStoreMime) {
            warnings += issue(
                RecordingDeviceMatrixValidationCode.MEDIASTORE_MIME_FALLBACK,
                "MediaStore reported ${observed.mimeType} for .${row.expectedContainerExtension}; encoder target is ${row.expectedMediaStoreMime}"
            )
        }

        if (observed.width != null && observed.height != null &&
            (observed.width != row.target.width || observed.height != row.target.height)
        ) {
            warnings += issue(
                RecordingDeviceMatrixValidationCode.HARDWARE_METADATA_FALLBACK,
                "Saved ${observed.width}x${observed.height}, requested ${row.target.dimensionLabel}"
            )
        }
        if (observed.captureFrameRate != null &&
            abs(observed.captureFrameRate - row.target.fps.toFloat()) > 0.5f
        ) {
            warnings += issue(
                RecordingDeviceMatrixValidationCode.HARDWARE_METADATA_FALLBACK,
                "Saved ${observed.captureFrameRate}fps, requested ${row.target.fps}fps"
            )
        }
        resolveAppliedRecordingFps(logClassification.lines)?.let { appliedFps ->
            if (appliedFps != row.target.fps) {
                warnings += issue(
                    RecordingDeviceMatrixValidationCode.RECORDING_FPS_FALLBACK,
                    "Applied ${appliedFps}fps, requested ${row.target.fps}fps"
                )
            }
        }

        val observedDelivery = resolveObservedDelivery(logClassification.lines)
        if (observedDelivery != null && observedDelivery != row.expectedDeliveryKind) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.DELIVERY_KIND_MISMATCH,
                "Log delivery=$observedDelivery, expected ${row.expectedDeliveryKind}"
            )
        }

        when (row.expectedDeliveryKind) {
            VideoDeliveryKind.LOG -> {
                if (observedDelivery == VideoDeliveryKind.HDR || isHdrTransfer(observed.colorTransfer)) {
                    blocking += issue(
                        RecordingDeviceMatrixValidationCode.LOG_SAVED_AS_HDR,
                        "LOG row ${row.id} carried HDR delivery or transfer"
                    )
                }
            }
            VideoDeliveryKind.SDR -> {
                if (observedDelivery == VideoDeliveryKind.HDR || isHdrTransfer(observed.colorTransfer)) {
                    blocking += issue(
                        RecordingDeviceMatrixValidationCode.SDR_SAVED_AS_HDR,
                        "SDR row ${row.id} carried HDR delivery or transfer"
                    )
                }
            }
            VideoDeliveryKind.HDR -> validateHdrColor(row, observed, blocking)
            VideoDeliveryKind.RAW -> {
                if (observedDelivery != null && observedDelivery != VideoDeliveryKind.RAW) {
                    blocking += issue(
                        RecordingDeviceMatrixValidationCode.RAW_SAVED_AS_PROCESSED,
                        "RAW row ${row.id} logged processed delivery=$observedDelivery"
                    )
                }
            }
        }

        return RecordingDeviceMatrixValidationResult(
            rowId = row.id,
            validationLabel = row.validationLabel,
            blockingIssues = blocking,
            warnings = warnings
        )
    }

    private fun validateHdrColor(
        row: RecordingDeviceMatrixRow,
        observed: RecordingDeviceMatrixObservedMetadata,
        blocking: MutableList<RecordingDeviceMatrixValidationIssue>
    ) {
        if (observed.colorStandard != null &&
            observed.colorStandard != MediaFormat.COLOR_STANDARD_BT2020
        ) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.COLOR_METADATA_MISMATCH,
                "HDR row ${row.id} saved colorStandard=${observed.colorStandard}"
            )
        }
        if (observed.colorTransfer != null &&
            observed.colorTransfer != row.graphContract.colorTransfer
        ) {
            blocking += issue(
                RecordingDeviceMatrixValidationCode.COLOR_METADATA_MISMATCH,
                "HDR row ${row.id} saved colorTransfer=${observed.colorTransfer}, expected ${row.graphContract.colorTransfer}"
            )
        }
    }

    private fun resolveObservedDelivery(lines: List<String>): VideoDeliveryKind? =
        lines.asSequence()
            .mapNotNull { line ->
                Regex("\\bdelivery=(SDR|HDR|LOG|RAW)\\b").find(line)?.groupValues?.get(1)
            }
            .lastOrNull()
            ?.let { runCatching { VideoDeliveryKind.valueOf(it) }.getOrNull() }

    private fun isHdrTransfer(colorTransfer: Int?): Boolean =
        colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084

    private fun resolveAppliedRecordingFps(lines: List<String>): Int? =
        lines.asSequence()
            .mapNotNull { line ->
                appliedDebugFpsRegex.find(line)?.groupValues?.getOrNull(1)
                    ?: preparedFpsRegex.find(line)?.groupValues?.getOrNull(1)
            }
            .lastOrNull()
            ?.toIntOrNull()

    private fun isAcceptedContainerMime(
        row: RecordingDeviceMatrixRow,
        observedMimeType: String?
    ): Boolean {
        if (observedMimeType == row.expectedMediaStoreMime) return true
        return row.expectedContainerExtension.equals("mov", ignoreCase = true) &&
            row.expectedMediaStoreMime == "video/quicktime" &&
            observedMimeType == "video/mp4"
    }

    private fun issue(
        code: RecordingDeviceMatrixValidationCode,
        message: String
    ): RecordingDeviceMatrixValidationIssue =
        RecordingDeviceMatrixValidationIssue(code = code, message = message)

    private val appliedDebugFpsRegex = Regex("\\bappliedDebugConfig\\b.*\\bfps=(\\d+)\\b")
    private val preparedFpsRegex = Regex("\\bPrepared:\\s+\\d+x\\d+@(\\d+)fps\\b")
}
