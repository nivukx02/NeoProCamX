package com.neoproc.neoproccamx.camera

internal data class RawStillCaptureTargetPlan(
    val includeRaw: Boolean,
    val includeJpeg: Boolean,
    val failureReason: String?
)

internal object RawStillCaptureTargetPolicy {
    fun resolve(
        captureRaw: Boolean,
        captureJpeg: Boolean,
        rawStreamActive: Boolean,
        jpegStreamActive: Boolean
    ): RawStillCaptureTargetPlan {
        if (captureRaw && !rawStreamActive) {
            return RawStillCaptureTargetPlan(
                includeRaw = false,
                includeJpeg = false,
                failureReason = "RAW_SENSOR capture requested but RAW_SENSOR stream is not active in the current camera session"
            )
        }

        val includeRaw = captureRaw && rawStreamActive
        val includeJpeg = captureJpeg && jpegStreamActive
        return RawStillCaptureTargetPlan(
            includeRaw = includeRaw,
            includeJpeg = includeJpeg,
            failureReason = if (!includeRaw && !includeJpeg) {
                "Still capture stream is not active in the current camera session"
            } else {
                null
            }
        )
    }
}
