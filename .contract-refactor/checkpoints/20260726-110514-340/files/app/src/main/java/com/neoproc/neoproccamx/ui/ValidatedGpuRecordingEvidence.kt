package com.neoproc.neoproccamx.ui

internal data class ValidatedGpuRecordingEvidence(
    val frameImportEnabled: Boolean,
    val encoderSurfaceBound: Boolean,
    val importedFrames: Long,
    val validatedAtElapsedMs: Long,
    val recordingSignature: String,
    val encoderSurfaceBoundAtValidation: Boolean,
    val encoderSurfaceFormat: String,
    val encoderSurfaceBitDepth: Int,
    val encoderSurfacePrecision: String,
    val encoderSurfacePrecisionFallback: Boolean,
    val encoderSurfaceColorSpace: String,
    val encoderSurfaceDeliveryMode: String,
    val encoderSurfaceDeliveryCompatible: Boolean,
    val encoderSurfacePrecisionProven: Boolean,
    val encoderSourceFormat: String,
    val encoderSourceBitDepth: Int,
    val encoderSourcePrecision: String,
    val encoderSourcePrecisionProven: Boolean
) {
    companion object {
        val EMPTY = ValidatedGpuRecordingEvidence(
            frameImportEnabled = false,
            encoderSurfaceBound = false,
            importedFrames = 0L,
            validatedAtElapsedMs = 0L,
            recordingSignature = "",
            encoderSurfaceBoundAtValidation = false,
            encoderSurfaceFormat = "UNDEFINED",
            encoderSurfaceBitDepth = 0,
            encoderSurfacePrecision = "UNKNOWN",
            encoderSurfacePrecisionFallback = false,
            encoderSurfaceColorSpace = "UNDEFINED",
            encoderSurfaceDeliveryMode = "UNKNOWN",
            encoderSurfaceDeliveryCompatible = false,
            encoderSurfacePrecisionProven = false,
            encoderSourceFormat = "UNDEFINED",
            encoderSourceBitDepth = 0,
            encoderSourcePrecision = "UNKNOWN",
            encoderSourcePrecisionProven = false
        )
    }
}
