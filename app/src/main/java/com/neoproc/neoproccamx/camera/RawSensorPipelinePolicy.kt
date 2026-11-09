package com.neoproc.neoproccamx.camera

internal enum class RawSensorPipelineQuality {
    TRUE_ORIGINAL_RAW,
    FULL_SENSOR_RAW_LIMITED_ISP,
    SUPPORTED_RAW_LIMITED_READOUT,
    UNAVAILABLE
}

internal data class RawSensorPipelinePlan(
    val quality: RawSensorPipelineQuality,
    val ispBypassLevel: IspBypassLevel,
    val truthfulFullSensorReadout: Boolean,
    val truthfulOriginalRaw: Boolean,
    val diagnostics: List<String>
)

internal object RawSensorPipelinePolicy {
    private const val SENSOR_COVERAGE_TOLERANCE = 0.98

    fun resolve(
        capability: CameraCapabilityInventory,
        selectedRawSize: CameraStreamSize?
    ): RawSensorPipelinePlan {
        if (!capability.supportsRaw || selectedRawSize == null) {
            return RawSensorPipelinePlan(
                quality = RawSensorPipelineQuality.UNAVAILABLE,
                ispBypassLevel = IspBypassLevel.NONE,
                truthfulFullSensorReadout = false,
                truthfulOriginalRaw = false,
                diagnostics = listOf("RAW_SENSOR unavailable in the exposed Camera2 stream map")
            )
        }

        val referenceSensor = capability.activeArraySize ?: capability.pixelArraySize
        val fullSensorReadout = referenceSensor != null &&
            selectedRawSize.area.toDouble() >= referenceSensor.area.toDouble() * SENSOR_COVERAGE_TOLERANCE
        val completeOriginalControls = capability.supportsManualSensor &&
            capability.supportsTonemapContrastCurve &&
            capability.supportsCompleteIspBypassRequest
        val truthfulOriginalRaw = fullSensorReadout && completeOriginalControls
        val quality = when {
            truthfulOriginalRaw -> RawSensorPipelineQuality.TRUE_ORIGINAL_RAW
            fullSensorReadout -> RawSensorPipelineQuality.FULL_SENSOR_RAW_LIMITED_ISP
            else -> RawSensorPipelineQuality.SUPPORTED_RAW_LIMITED_READOUT
        }

        return RawSensorPipelinePlan(
            quality = quality,
            ispBypassLevel = if (truthfulOriginalRaw) {
                IspBypassLevel.TRUE_RAW_SENSOR
            } else {
                IspBypassLevel.REQUEST_KEYS_ONLY
            },
            truthfulFullSensorReadout = fullSensorReadout,
            truthfulOriginalRaw = truthfulOriginalRaw,
            diagnostics = buildDiagnostics(
                selectedRawSize = selectedRawSize,
                referenceSensor = referenceSensor,
                fullSensorReadout = fullSensorReadout,
                capability = capability,
                truthfulOriginalRaw = truthfulOriginalRaw
            )
        )
    }

    private fun buildDiagnostics(
        selectedRawSize: CameraStreamSize,
        referenceSensor: CameraStreamSize?,
        fullSensorReadout: Boolean,
        capability: CameraCapabilityInventory,
        truthfulOriginalRaw: Boolean
    ): List<String> =
        buildList {
            when {
                truthfulOriginalRaw -> add(
                    "RAW_SENSOR ${selectedRawSize.label()} covers ${sensorReferenceLabel(capability)} " +
                        "as a true original RAW_SENSOR path"
                )
                !fullSensorReadout -> add(
                    "RAW_SENSOR ${selectedRawSize.label()} is below full sensor ${referenceSensor?.label() ?: "unknown"}"
                )
                else -> add(
                    "RAW_SENSOR ${selectedRawSize.label()} covers ${sensorReferenceLabel(capability)} " +
                        "but original bypass is limited"
                )
            }
            if (fullSensorReadout && !truthfulOriginalRaw) {
                if (!capability.supportsManualSensor) add("Manual sensor control unavailable for original RAW timing")
                if (!capability.supportsTonemapContrastCurve) add("Linear tonemap contrast curve unavailable")
                if (!capability.supportsCompleteIspBypassRequest) {
                    add("ISP bypass request-key coverage is incomplete")
                }
            }
        }

    private fun sensorReferenceLabel(capability: CameraCapabilityInventory): String =
        if (capability.activeArraySize != null) "active sensor" else "sensor pixel array"

    private fun CameraStreamSize.label(): String = "${width}x${height}"
}
