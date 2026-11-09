package com.neoproc.neoproccamx.camera

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal enum class CameraReadoutTier {
    FULL_SENSOR_RAW,
    SUPPORTED_RAW,
    HIGH_RES_YUV,
    ENCODER_WITH_ANALYSIS,
    ENCODER_ONLY,
    UNSUPPORTED
}

internal enum class IspBypassLevel {
    TRUE_RAW_SENSOR,
    BEST_EFFORT_YUV,
    REQUEST_KEYS_ONLY,
    NONE
}

internal data class CameraStreamSize(
    val width: Int,
    val height: Int
) {
    val area: Long = width.toLong() * height.toLong()

    fun formatStep(label: String): String = "$label ${width}x${height}"
}

internal data class CameraPhysicalLensProfile(
    val cameraId: String,
    val label: String,
    val equivalentFocalLengthMm: Float?,
    val focalLengthMm: Float?,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val lensType: LensType,
    val publicCamera2Id: Boolean = true
)

internal data class CameraCapabilityInventory(
    val cameraId: String,
    val pixelArraySize: CameraStreamSize?,
    val activeArraySize: CameraStreamSize?,
    val rawOutputSizes: List<CameraStreamSize>,
    val yuvOutputSizes: List<CameraStreamSize>,
    val jpegOutputSizes: List<CameraStreamSize>,
    val encoderOutputSizes: List<CameraStreamSize>,
    val supportsRaw: Boolean,
    val supportsTonemapContrastCurve: Boolean,
    val supportsManualSensor: Boolean,
    val supportsIspDisableRequestKeys: Boolean,
    val supportsCompleteIspBypassRequest: Boolean = supportsIspDisableRequestKeys,
    val hardwareLevel: CameraHardwareLevelTier = CameraHardwareLevelTier.UNKNOWN,
    val supportsPrivateReprocessing: Boolean = false,
    val supportsYuvReprocessing: Boolean = false,
    val supportsTenBitHdr: Boolean = false,
    val supportsOis: Boolean = false,
    val supportsVideoStabilization: Boolean = false,
    val maxPipelineDepth: Int = 0,
    val physicalLensProfiles: List<CameraPhysicalLensProfile> = emptyList(),
    val exposedVendorRequestKeys: List<String> = emptyList(),
    val exposedVendorCharacteristicKeys: List<String> = emptyList()
)

internal data class CameraStreamPlanRequest(
    val cinemaMode: CinemaVideoMode,
    val preferredEncoderSize: CameraStreamSize? = null,
    val requestedReadoutSize: CameraStreamSize? = preferredEncoderSize,
    val preferFullSensorReadout: Boolean = true
)

internal data class CameraStreamPlan(
    val readoutTier: CameraReadoutTier,
    val ispBypassLevel: IspBypassLevel,
    val primaryReadoutSize: CameraStreamSize?,
    val rawSize: CameraStreamSize? = null,
    val yuvAnalysisSize: CameraStreamSize? = null,
    val jpegSize: CameraStreamSize? = null,
    val encoderSize: CameraStreamSize? = null,
    val truthfulFullSensorReadout: Boolean = false,
    val fallbackChain: List<String> = emptyList(),
    val fallbackWarning: String? = null,
    val diagnostics: List<String> = emptyList()
)

internal object CameraStreamPlanResolver {
    private const val SENSOR_COVERAGE_TOLERANCE = 0.98

    fun resolve(
        request: CameraStreamPlanRequest,
        capability: CameraCapabilityInventory
    ): CameraStreamPlan {
        val requestedReadout = requestedSensorReadoutSize(request, capability)
        val rawSize = selectSensorReadoutSize(requestedReadout, capability.rawOutputSizes)
        val yuvSize = selectSensorReadoutSize(requestedReadout, capability.yuvOutputSizes)
        val jpegSize = largest(capability.jpegOutputSizes)
        val encoderSize = selectEncoderSize(request.preferredEncoderSize, capability.encoderOutputSizes)

        return when (request.cinemaMode) {
            CinemaVideoMode.RAW -> resolveRawRequest(
                request = request,
                capability = capability,
                rawSize = rawSize,
                yuvSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize
            )
            else -> resolveEncoderRequest(
                request = request,
                capability = capability,
                yuvSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize
            )
        }
    }

    private fun resolveRawRequest(
        request: CameraStreamPlanRequest,
        capability: CameraCapabilityInventory,
        rawSize: CameraStreamSize?,
        yuvSize: CameraStreamSize?,
        jpegSize: CameraStreamSize?,
        encoderSize: CameraStreamSize?
    ): CameraStreamPlan {
        if (capability.supportsRaw && rawSize != null) {
            val rawPipeline = RawSensorPipelinePolicy.resolve(
                capability = capability,
                selectedRawSize = rawSize
            )

            return CameraStreamPlan(
                readoutTier = if (rawPipeline.truthfulFullSensorReadout) {
                    CameraReadoutTier.FULL_SENSOR_RAW
                } else {
                    CameraReadoutTier.SUPPORTED_RAW
                },
                ispBypassLevel = rawPipeline.ispBypassLevel,
                primaryReadoutSize = rawSize,
                rawSize = rawSize,
                yuvAnalysisSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize,
                truthfulFullSensorReadout = rawPipeline.truthfulFullSensorReadout,
                fallbackChain = listOfNotNull(
                    rawSize.formatStep("RAW_SENSOR"),
                    yuvSize?.formatStep("YUV_420_888"),
                    encoderSize?.formatStep("ENCODER")
                ),
                fallbackWarning = fallbackWarningFor(request, rawSize, "stable RAW_SENSOR"),
                diagnostics = rawPipeline.diagnostics +
                    listOfNotNull(fallbackWarningFor(request, rawSize, "stable RAW_SENSOR"))
            )
        }

        if (yuvSize != null) {
            return CameraStreamPlan(
                readoutTier = CameraReadoutTier.HIGH_RES_YUV,
                ispBypassLevel = if (capability.supportsIspDisableRequestKeys) {
                    IspBypassLevel.BEST_EFFORT_YUV
                } else {
                    IspBypassLevel.NONE
                },
                primaryReadoutSize = yuvSize,
                yuvAnalysisSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize,
                truthfulFullSensorReadout = false,
                fallbackChain = listOfNotNull(
                    yuvSize.formatStep("YUV_420_888"),
                    encoderSize?.formatStep("ENCODER")
                ),
                fallbackWarning = fallbackWarningFor(request, yuvSize, "stable YUV_420_888"),
                diagnostics = listOfNotNull(
                    "RAW_SENSOR unavailable; using YUV_420_888 best-effort path",
                    fallbackWarningFor(request, yuvSize, "stable YUV_420_888")
                )
            )
        }

        if (encoderSize != null) {
            return CameraStreamPlan(
                readoutTier = CameraReadoutTier.ENCODER_ONLY,
                ispBypassLevel = IspBypassLevel.NONE,
                primaryReadoutSize = encoderSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize,
                truthfulFullSensorReadout = false,
                fallbackChain = listOf(encoderSize.formatStep("ENCODER")),
                fallbackWarning = fallbackWarningFor(request, encoderSize, "stable encoder"),
                diagnostics = listOfNotNull(
                    "RAW_SENSOR unavailable; YUV_420_888 unavailable; using encoder-only path",
                    fallbackWarningFor(request, encoderSize, "stable encoder")
                )
            )
        }

        return unsupportedPlan("RAW_SENSOR unavailable; no YUV_420_888 or encoder fallback")
    }

    private fun resolveEncoderRequest(
        request: CameraStreamPlanRequest,
        capability: CameraCapabilityInventory,
        yuvSize: CameraStreamSize?,
        jpegSize: CameraStreamSize?,
        encoderSize: CameraStreamSize?
    ): CameraStreamPlan {
        if (encoderSize != null && yuvSize != null) {
            val primaryReadoutSize = listOf(encoderSize, yuvSize)
                .maxWith(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })
            val yuvIsPrimaryReadout = primaryReadoutSize == yuvSize
            val truthfulFullSensorReadout =
                coversSensorReadout(primaryReadoutSize, capability)
            val fallbackWarning = fallbackWarningFor(
                request = request,
                actual = primaryReadoutSize,
                actualLabel = if (yuvIsPrimaryReadout) {
                    "stable YUV_420_888 readout"
                } else {
                    "stable encoder"
                }
            )
            return CameraStreamPlan(
                readoutTier = CameraReadoutTier.ENCODER_WITH_ANALYSIS,
                ispBypassLevel = if (capability.supportsIspDisableRequestKeys) {
                    IspBypassLevel.BEST_EFFORT_YUV
                } else {
                    IspBypassLevel.NONE
                },
                primaryReadoutSize = primaryReadoutSize,
                yuvAnalysisSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize,
                truthfulFullSensorReadout = truthfulFullSensorReadout,
                fallbackChain = if (yuvIsPrimaryReadout) {
                    listOf(
                        yuvSize.formatStep("YUV_420_888_READOUT"),
                        encoderSize.formatStep("ENCODER_DELIVERY")
                    )
                } else {
                    listOf(
                        encoderSize.formatStep("ENCODER_READOUT"),
                        yuvSize.formatStep("YUV_420_888_SIDECAR")
                    )
                },
                fallbackWarning = fallbackWarning,
                diagnostics = listOfNotNull(
                    if (truthfulFullSensorReadout && yuvIsPrimaryReadout) {
                        "YUV_420_888 sidecar exposes full-sensor readout while encoder records delivery stream"
                    } else if (yuvIsPrimaryReadout) {
                        "YUV_420_888 sidecar is the largest exposed sensor readout; encoder records delivery stream"
                    } else if (truthfulFullSensorReadout) {
                        "Encoder path exposes full-sensor readout; YUV remains analysis support"
                    } else {
                        "Encoder and YUV paths are below requested full-sensor readout; using largest stable exposed outputs"
                    },
                    fallbackWarning
                ),
            )
        }

        if (encoderSize != null) {
            val fullSensorEncoder = coversSensorReadout(encoderSize, capability)
            return CameraStreamPlan(
                readoutTier = CameraReadoutTier.ENCODER_ONLY,
                ispBypassLevel = IspBypassLevel.NONE,
                primaryReadoutSize = encoderSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize,
                truthfulFullSensorReadout = fullSensorEncoder,
                fallbackChain = listOf(encoderSize.formatStep("ENCODER")),
                fallbackWarning = fallbackWarningFor(request, encoderSize, "stable encoder"),
                diagnostics = listOfNotNull(
                    if (fullSensorEncoder) {
                        "Encoder-only path exposes full-sensor size"
                    } else {
                        "YUV_420_888 unavailable; using encoder-only path"
                    },
                    fallbackWarningFor(request, encoderSize, "stable encoder")
                )
            )
        }

        if (yuvSize != null) {
            val fullSensorYuv = coversSensorReadout(yuvSize, capability)
            return CameraStreamPlan(
                readoutTier = CameraReadoutTier.HIGH_RES_YUV,
                ispBypassLevel = if (capability.supportsIspDisableRequestKeys) {
                    IspBypassLevel.BEST_EFFORT_YUV
                } else {
                    IspBypassLevel.NONE
                },
                primaryReadoutSize = yuvSize,
                yuvAnalysisSize = yuvSize,
                jpegSize = jpegSize,
                truthfulFullSensorReadout = fullSensorYuv,
                fallbackChain = listOf(yuvSize.formatStep("YUV_420_888")),
                fallbackWarning = fallbackWarningFor(request, yuvSize, "stable YUV_420_888"),
                diagnostics = listOfNotNull(
                    "Encoder unavailable; using YUV_420_888 preview path",
                    fallbackWarningFor(request, yuvSize, "stable YUV_420_888")
                )
            )
        }

        return unsupportedPlan("No encoder or YUV_420_888 output is available")
    }

    private fun unsupportedPlan(diagnostic: String): CameraStreamPlan =
        CameraStreamPlan(
            readoutTier = CameraReadoutTier.UNSUPPORTED,
            ispBypassLevel = IspBypassLevel.NONE,
            primaryReadoutSize = null,
            diagnostics = listOf(diagnostic)
        )

    private fun selectEncoderSize(
        preferred: CameraStreamSize?,
        sizes: List<CameraStreamSize>
    ): CameraStreamSize? =
        when {
            preferred != null && preferred in sizes -> preferred
            else -> largest(sizes)
        }

    private fun largest(sizes: List<CameraStreamSize>): CameraStreamSize? =
        sizes.maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun requestedSensorReadoutSize(
        request: CameraStreamPlanRequest,
        capability: CameraCapabilityInventory
    ): CameraStreamSize? =
        if (request.preferFullSensorReadout) {
            capability.activeArraySize ?: capability.pixelArraySize ?: request.requestedReadoutSize
        } else {
            request.requestedReadoutSize
        }

    private fun selectSensorReadoutSize(
        requested: CameraStreamSize?,
        sizes: List<CameraStreamSize>
    ): CameraStreamSize? {
        val fallback = largest(sizes) ?: return null
        return CameraReadoutTargetSizePolicy.resolveRawReaderStreamSize(
            recordingReadoutOverrideSize = requested,
            supportedRawSizes = sizes,
            fallback = fallback
        )
    }

    private fun coversSensorReadout(
        actual: CameraStreamSize,
        capability: CameraCapabilityInventory
    ): Boolean {
        val reference = capability.activeArraySize ?: capability.pixelArraySize ?: return false
        return actual.area.toDouble() >= reference.area.toDouble() * SENSOR_COVERAGE_TOLERANCE
    }

    private fun fallbackWarningFor(
        request: CameraStreamPlanRequest,
        actual: CameraStreamSize?,
        actualLabel: String
    ): String? =
        CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = request.requestedReadoutSize,
            actual = actual,
            requestedLabel = if (request.cinemaMode == CinemaVideoMode.RAW) {
                "8K full sensor"
            } else {
                "${request.cinemaMode.name} recording readout"
            },
            actualLabel = actualLabel
        )
}

internal fun Size.toCameraStreamSize(): CameraStreamSize = CameraStreamSize(width, height)

internal fun CameraStreamSize.toAndroidSize(): Size = Size(width, height)
