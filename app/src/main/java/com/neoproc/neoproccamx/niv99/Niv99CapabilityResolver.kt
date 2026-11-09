package com.neoproc.neoproccamx.niv99

enum class Niv99UnsupportedReason {
    NONE,
    VULKAN_COMPUTE_UNAVAILABLE,
    STORAGE_IMAGE_PRECISION_UNAVAILABLE,
    EXTENDED_STORAGE_IMAGE_FEATURE_UNAVAILABLE,
    SENSOR_LINEAR_INPUT_UNAVAILABLE,
    P010_INPUT_UNAVAILABLE,
    FP32_CALIBRATION_UNAVAILABLE,
    EXPLICIT_SYNCHRONIZATION_UNAVAILABLE,
    REQUIRED_SHADER_UNAVAILABLE,
    HISTORY_BUDGET_UNAVAILABLE,
    ZERO_COPY_UNAVAILABLE,
    MODE_NOT_SUPPORTED
}

class Niv99HardwareCapabilities {
    var vulkanCompute: Boolean = false
    var ahardwareBufferImport: Boolean = false
    var p010Import: Boolean = false
    var sensorLinearImport: Boolean = false
    var rgba16fStorage: Boolean = false
    var rgba32fCalibration: Boolean = false
    var storageImageExtendedFormats: Boolean = false
    var timelineSemaphore: Boolean = false
    var synchronization2: Boolean = false
    var shaderStatistics: Boolean = false
    var shaderRawDenoise: Boolean = false
    var shaderMotionConfidence: Boolean = false
    var shaderTemporalFusion: Boolean = false
    var shaderReconstruction: Boolean = false
    var shaderQualityTelemetry: Boolean = false
    var maximumHistoryFrames: Int = 0
    var maximumWidth: Int = 0
    var maximumHeight: Int = 0
    var boundedCopyFallbackValidated: Boolean = false
}

class Niv99CapabilityDecision {
    var status: Niv99CapabilityStatus = Niv99CapabilityStatus.UNSUPPORTED
    var reason: Niv99UnsupportedReason = Niv99UnsupportedReason.NONE
    var maximumHistoryFrames: Int = 0
    var zeroCopy: Boolean = false
    var fp32Calibration: Boolean = false
    var precisionBits: Int = 0

    fun setUnsupported(reason: Niv99UnsupportedReason) {
        status = Niv99CapabilityStatus.UNSUPPORTED
        this.reason = reason
        maximumHistoryFrames = 0
        zeroCopy = false
        fp32Calibration = false
        precisionBits = 0
    }
}

/** Truthful capability gate: unsupported behavior is returned explicitly, never hidden. */
object Niv99CapabilityResolver {
    fun resolve(
        capabilities: Niv99HardwareCapabilities,
        context: Niv99FrameContext,
        output: Niv99CapabilityDecision
    ) = resolve(
        capabilities = capabilities,
        width = context.width,
        height = context.height,
        signalDomain = context.signalDomain,
        output = output
    )

    fun resolve(
        capabilities: Niv99HardwareCapabilities,
        width: Int,
        height: Int,
        signalDomain: Niv99SignalDomain,
        output: Niv99CapabilityDecision
    ) {
        if (!capabilities.vulkanCompute) {
            output.setUnsupported(Niv99UnsupportedReason.VULKAN_COMPUTE_UNAVAILABLE)
            return
        }
        if (!capabilities.rgba16fStorage) {
            output.setUnsupported(Niv99UnsupportedReason.STORAGE_IMAGE_PRECISION_UNAVAILABLE)
            return
        }
        if (!capabilities.storageImageExtendedFormats) {
            output.setUnsupported(Niv99UnsupportedReason.EXTENDED_STORAGE_IMAGE_FEATURE_UNAVAILABLE)
            return
        }
        if (!capabilities.rgba32fCalibration) {
            output.setUnsupported(Niv99UnsupportedReason.FP32_CALIBRATION_UNAVAILABLE)
            return
        }
        if (!capabilities.timelineSemaphore) {
            output.setUnsupported(Niv99UnsupportedReason.EXPLICIT_SYNCHRONIZATION_UNAVAILABLE)
            return
        }
        if (!capabilities.shaderStatistics || !capabilities.shaderRawDenoise ||
            !capabilities.shaderMotionConfidence || !capabilities.shaderTemporalFusion ||
            !capabilities.shaderReconstruction ||
            !capabilities.shaderQualityTelemetry
        ) {
            output.setUnsupported(Niv99UnsupportedReason.REQUIRED_SHADER_UNAVAILABLE)
            return
        }
        if (width <= 0 || height <= 0 ||
            width > capabilities.maximumWidth || height > capabilities.maximumHeight
        ) {
            output.setUnsupported(Niv99UnsupportedReason.MODE_NOT_SUPPORTED)
            return
        }
        if (capabilities.maximumHistoryFrames < 1) {
            output.setUnsupported(Niv99UnsupportedReason.HISTORY_BUDGET_UNAVAILABLE)
            return
        }
        when (signalDomain) {
            Niv99SignalDomain.SENSOR_LINEAR_RAW,
            Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED -> if (!capabilities.sensorLinearImport) {
                output.setUnsupported(Niv99UnsupportedReason.SENSOR_LINEAR_INPUT_UNAVAILABLE)
                return
            }
            Niv99SignalDomain.P010_YUV -> if (!capabilities.p010Import) {
                output.setUnsupported(Niv99UnsupportedReason.P010_INPUT_UNAVAILABLE)
                return
            }
            else -> Unit
        }
        output.maximumHistoryFrames = capabilities.maximumHistoryFrames.coerceAtMost(
            Niv99TemporalDenoisePolicy.MAX_HISTORY_CAPACITY
        )
        output.fp32Calibration = true
        output.precisionBits = 16
        output.zeroCopy = capabilities.ahardwareBufferImport
        if (capabilities.ahardwareBufferImport) {
            output.status = Niv99CapabilityStatus.SUPPORTED_ZERO_COPY
            output.reason = Niv99UnsupportedReason.NONE
        } else if (capabilities.boundedCopyFallbackValidated) {
            output.status = Niv99CapabilityStatus.SUPPORTED_BOUNDED_COPY
            output.reason = Niv99UnsupportedReason.ZERO_COPY_UNAVAILABLE
        } else {
            output.setUnsupported(Niv99UnsupportedReason.ZERO_COPY_UNAVAILABLE)
        }
    }

    fun cpuReferenceDecision(output: Niv99CapabilityDecision) {
        output.status = Niv99CapabilityStatus.MONITOR_ONLY
        output.reason = Niv99UnsupportedReason.NONE
        output.maximumHistoryFrames = Niv99TemporalDenoisePolicy.MAX_HISTORY_CAPACITY
        output.zeroCopy = false
        output.fp32Calibration = true
        output.precisionBits = 32
    }
}
