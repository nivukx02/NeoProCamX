package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class StillCapturePresentation(
    val primaryTag: String,
    val rawCompanionTag: String?,
    val failureLabel: String
)

internal object StillRawWorkflowPolicy {
    private const val HDR_RAW_COMPANION_TAG_STANDARD = "HDR_RAW_DNG"
    private const val HDR_RAW_COMPANION_TAG_STRICT = "HDR_RAW_DNG_CINEMA"

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun resolveSinglePresentation(
        rawMode: StillCaptureCapturePolicy.SingleCaptureRawMode
    ): StillCapturePresentation =
        when (rawMode) {
            StillCaptureCapturePolicy.SingleCaptureRawMode.JPEG_ONLY ->
                StillCapturePresentation(
                    primaryTag = "IMG",
                    rawCompanionTag = null,
                    failureLabel = "Photo"
                )

            StillCaptureCapturePolicy.SingleCaptureRawMode.NATIVE_RAW_COMPANION ->
                StillCapturePresentation(
                    primaryTag = "IMG",
                    rawCompanionTag = "RAW_DNG",
                    failureLabel = "RAW photo"
                )

            StillCaptureCapturePolicy.SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK ->
                StillCapturePresentation(
                    primaryTag = "IMG",
                    rawCompanionTag = "SOURCE_PKG",
                    failureLabel = "Photo"
                )
        }

    fun resolveHdrPresentation(rawCompanionAfterPrimary: Boolean): StillCapturePresentation =
        StillCapturePresentation(
            primaryTag = "HDR",
            rawCompanionTag = if (rawCompanionAfterPrimary) {
                if (strictHighEndEnabled()) {
                    HDR_RAW_COMPANION_TAG_STRICT
                } else {
                    HDR_RAW_COMPANION_TAG_STANDARD
                }
            } else {
                null
            },
            failureLabel = "HDR"
        )

}
