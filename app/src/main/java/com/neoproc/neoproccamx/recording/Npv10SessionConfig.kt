package com.neoproc.neoproccamx.recording

data class Npv10SessionConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val mode: String,
    val colorProfile: String,
    val startedAtEpochMs: Long,
    val orientationHintDegrees: Int = 0,
    val sourceBitDepth: Int = 8,
    val sourceFormat: String = if (sourceBitDepth >= 10) "YCBCR_P010" else "YUV_420_888",
    val storageBitDepth: Int = Npv10ContainerPolicy.BIT_DEPTH,
    val payloadFormat: Npv10PayloadFormat =
        if (sourceBitDepth >= 10) Npv10PayloadFormat.P010_LE else Npv10PayloadFormat.YUV420_8BIT_EXPANDED,
    val packetCodec: Npv10PacketCodec =
        if (sourceBitDepth >= 10 && payloadFormat == Npv10PayloadFormat.P010_LE) {
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        } else {
            Npv10PacketCodec.LEGACY_UNCOMPRESSED
        },
    val exactSelectedResolution: Boolean = true,
    val selectedColorSpace: String = "",
    val selectedColorScience: String = "",
    val colorSelectionSource: String = "USER_MANUAL",
    val manualColorSelectionRequired: Boolean = true,
    val rawSensorCapture: Boolean = false,
    val sensorPixelModeMaximumResolution: Boolean = false,
    val gpuDemosaicEnabled: Boolean = false,
    val darkFrameCalibrationEnabled: Boolean = false,
    val zeroCopyNativeAcquisition: Boolean = false,
    /** Production processed-Video sessions set this explicitly; fixtures/offline writers do not. */
    val niv99ProcessingRequired: Boolean = false,
    /** Production NPV10 sessions fail closed unless NIV99 can pack into final file-backed slots. */
    val directMappedCommitRequired: Boolean = false,
    val bayerPattern: String = "",
    val rawSidecarMetadata: Map<String, String> = emptyMap(),
    val validationLabel: String = "",
    val featureMetadata: Map<String, String> = emptyMap()
) {
    fun resolvedSelectedColorSpace(): String = selectedColorSpace.ifBlank { colorProfile }

    fun resolvedSelectedColorScience(): String = selectedColorScience.ifBlank { colorProfile }
}

data class Npv10MasterClip(
    val file: java.io.File,
    val createdAtEpochMs: Long,
    val bytes: Long
)
