package com.neoproc.neoproccamx.niv99

import kotlin.math.max

/** Processing domains are explicit so RAW storage can never be mistaken for a writable render target. */
enum class Niv99SignalDomain {
    SENSOR_LINEAR_RAW,
    SENSOR_LINEAR_DEMOSAICED,
    LINEAR_RGB,
    P010_YUV,
    DISPLAY_RGB
}

enum class Niv99OperatingMode {
    VIDEO,
    PRO,
    RAW_PREVIEW,
    PHOTO,
    CONVERSION_RAW_TO_LOG,
    CONVERSION_RAW_TO_RAW_DEVELOPED,
    CONVERSION_RAW_TO_XDR10,
    CONVERSION_RAW_TO_NIVUKX
}

enum class Niv99DetailProfile {
    FOCUSED,
    NATURAL,
    EXTREME
}

enum class Niv99CfaPattern {
    RGGB,
    GRBG,
    GBRG,
    BGGR,
    MONO,
    UNKNOWN
}

enum class Niv99IlluminantClass {
    TUNGSTEN,
    WARM_LED,
    FLUORESCENT,
    DAYLIGHT,
    OVERCAST,
    SHADE,
    MIXED,
    UNKNOWN
}

enum class Niv99WhiteBalanceAuthority {
    MANUAL,
    AWB_LOCK,
    NIV99_AUTO,
    HAL_AUTO,
    UNSUPPORTED
}

enum class Niv99ResetReason {
    NONE,
    FIRST_FRAME,
    CAMERA_CHANGED,
    SESSION_CHANGED,
    MODE_CHANGED,
    DIMENSIONS_CHANGED,
    FPS_CHANGED,
    COLOR_PROFILE_CHANGED,
    SURFACE_CHANGED,
    CODEC_CHANGED,
    TIMESTAMP_DISCONTINUITY,
    SCENE_CUT,
    FLASH_TRANSITION,
    CORRUPT_METADATA,
    EXPLICIT_RESET
}

enum class Niv99CapabilityStatus {
    SUPPORTED_ZERO_COPY,
    SUPPORTED_BOUNDED_COPY,
    MONITOR_ONLY,
    UNSUPPORTED
}

/** Exact provenance of the matrix carried with a white-balance solution. */
enum class Niv99CalibrationMatrixSource {
    MANUAL_USER_MATRIX,
    SAME_FRAME_HAL_MATRIX,
    DUAL_ILLUMINANT_FORWARD_MATRIX,
    INVERSE_DUAL_ILLUMINANT_COLOR_MATRIX,
    NONE
}

/** Units used by heteroscedastic noise coefficients. */
enum class Niv99NoiseCoefficientDomain {
    /** Variance and signal are both expressed in normalized sensor-linear units. */
    NORMALIZED_SENSOR_SIGNAL,

    /** Variance and signal are expressed in the sensor's integer code-value units. */
    SENSOR_CODE_VALUE
}

/** Truthful origin of the fallback model used when a frame does not publish a noise profile. */
enum class Niv99NoiseCalibrationSource {
    CAPTURE_RESULT_NOISE_PROFILE,
    PROJECT_SENSOR_CALIBRATION,
    SENSITIVITY_RANGE_ESTIMATE,
    VALIDATED_CONSERVATIVE_FALLBACK,
    UNAVAILABLE
}

/** Mutable identity copied into every published packet without allocating on the frame hot path. */
class Niv99FrameIdentity {
    var cameraId: String = ""
        private set
    var sessionGeneration: Long = -1L
        private set
    var frameNumber: Long = -1L
        private set
    var sensorTimestampNs: Long = -1L
        private set

    fun set(
        cameraId: String,
        sessionGeneration: Long,
        frameNumber: Long,
        sensorTimestampNs: Long
    ): Niv99FrameIdentity {
        this.cameraId = cameraId
        this.sessionGeneration = sessionGeneration
        this.frameNumber = frameNumber
        this.sensorTimestampNs = sensorTimestampNs
        return this
    }

    fun copyFrom(other: Niv99FrameIdentity): Niv99FrameIdentity = set(
        cameraId = other.cameraId,
        sessionGeneration = other.sessionGeneration,
        frameNumber = other.frameNumber,
        sensorTimestampNs = other.sensorTimestampNs
    )

    fun isComplete(): Boolean =
        cameraId.isNotBlank() && sessionGeneration >= 0L && frameNumber >= 0L && sensorTimestampNs > 0L

    fun sameTimeline(other: Niv99FrameIdentity): Boolean =
        cameraId == other.cameraId && sessionGeneration == other.sessionGeneration

    fun strictlyFollows(other: Niv99FrameIdentity): Boolean =
        sameTimeline(other) && frameNumber > other.frameNumber && sensorTimestampNs > other.sensorTimestampNs
}

/** User/HAL authority is input, never inferred from image statistics. */
class Niv99WhiteBalanceIntent {
    var manualKelvin: Int = 0
    var manualTint: Int = 0
    var manualRedGain: Float = 1f
    var manualGreenGain: Float = 1f
    var manualBlueGain: Float = 1f
    var manualMatrixValid: Boolean = false
    val manualColorMatrix: FloatArray = FloatArray(9)
    /** Explicit operator lock request, never inferred from a Camera2 result. */
    var awbLockRequested: Boolean = false
    /** True only when an explicit operator lock is confirmed active by the HAL. */
    var awbLockActive: Boolean = false
    /** Camera2 may hold AWB internally for shot stability; this is not user authority. */
    var halAwbHeld: Boolean = false
    var halAwbConverged: Boolean = false
    var halGainsAvailable: Boolean = false
    var halRedGain: Float = 1f
    var halGreenEvenGain: Float = 1f
    var halGreenOddGain: Float = 1f
    var halBlueGain: Float = 1f
    var halMatrixValid: Boolean = false
    val halColorMatrix: FloatArray = FloatArray(9)

    init {
        Niv99Math.setIdentity3(manualColorMatrix)
        Niv99Math.setIdentity3(halColorMatrix)
    }

    fun hasManualAuthority(): Boolean = manualKelvin > 0

    fun hasValidManualGains(): Boolean =
        manualRedGain.isFinite() && manualRedGain > 0f &&
            manualGreenGain.isFinite() && manualGreenGain > 0f &&
            manualBlueGain.isFinite() && manualBlueGain > 0f

    fun hasValidHalGains(): Boolean =
        halGainsAvailable &&
            halRedGain.isFinite() && halRedGain > 0f &&
            halGreenEvenGain.isFinite() && halGreenEvenGain > 0f &&
            halGreenOddGain.isFinite() && halGreenOddGain > 0f &&
            halBlueGain.isFinite() && halBlueGain > 0f

    fun clear(): Niv99WhiteBalanceIntent {
        manualKelvin = 0
        manualTint = 0
        manualRedGain = 1f
        manualGreenGain = 1f
        manualBlueGain = 1f
        manualMatrixValid = false
        Niv99Math.setIdentity3(manualColorMatrix)
        awbLockRequested = false
        awbLockActive = false
        halAwbHeld = false
        halAwbConverged = false
        halGainsAvailable = false
        halRedGain = 1f
        halGreenEvenGain = 1f
        halGreenOddGain = 1f
        halBlueGain = 1f
        halMatrixValid = false
        Niv99Math.setIdentity3(halColorMatrix)
        return this
    }
}

/** Frame-scoped sensor and capture metadata. The caller reuses one instance. */
class Niv99FrameContext {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    var operatingMode: Niv99OperatingMode = Niv99OperatingMode.VIDEO
    var signalDomain: Niv99SignalDomain = Niv99SignalDomain.LINEAR_RGB
    var detailProfile: Niv99DetailProfile = Niv99DetailProfile.NATURAL
    var width: Int = 0
    var height: Int = 0
    var targetFps: Int = 30
    var colorProfileId: Long = 0L
    var surfaceGeneration: Long = 0L
    var codecGeneration: Long = 0L
    var iso: Int = 100
    var exposureTimeNs: Long = 0L
    var frameDurationNs: Long = 0L
    var rollingShutterSkewNs: Long = 0L
    var sensorTemperatureCelsius: Float = Float.NaN
    var flashFired: Boolean = false
    var sceneCutHint: Boolean = false
    var motionMagnitude: Float = 0f
    var gyroMagnitudeRadiansPerSecond: Float = 0f
    var opticalFlowConfidence: Float = 0f
    var clippingFraction: Float = 0f
    var meanLinearSignal: Float = 0f
    var memoryPressure: Float = 0f
    var thermalPressure: Float = 0f
    val whiteBalanceIntent: Niv99WhiteBalanceIntent = Niv99WhiteBalanceIntent()

    fun validate(): Niv99ValidationResult {
        if (!identity.isComplete()) return Niv99ValidationResult.INVALID_IDENTITY
        if (width <= 0 || height <= 0) return Niv99ValidationResult.INVALID_DIMENSIONS
        if (targetFps <= 0 || targetFps > 480) return Niv99ValidationResult.INVALID_FPS
        if (iso <= 0 || exposureTimeNs < 0L || frameDurationNs < 0L) {
            return Niv99ValidationResult.INVALID_EXPOSURE
        }
        if (!motionMagnitude.isFinite() || !gyroMagnitudeRadiansPerSecond.isFinite() ||
            !opticalFlowConfidence.isFinite() || !clippingFraction.isFinite() ||
            !meanLinearSignal.isFinite() || !memoryPressure.isFinite() || !thermalPressure.isFinite()
        ) {
            return Niv99ValidationResult.NON_FINITE_METADATA
        }
        return Niv99ValidationResult.VALID
    }
}

enum class Niv99ValidationResult {
    VALID,
    INVALID_IDENTITY,
    INVALID_DIMENSIONS,
    INVALID_FPS,
    INVALID_EXPOSURE,
    NON_FINITE_METADATA,
    STALE_METADATA,
    INVALID_PLANE,
    INVALID_CALIBRATION
}

/**
 * Bounded provenance admission gate. It accepts a new camera/timeline, but rejects duplicate,
 * reordered, or retired-generation metadata before any temporal state can observe it.
 */
class Niv99FrameProvenanceGate {
    private val latest = Niv99FrameIdentity()
    private var initialized = false

    @Synchronized
    fun admit(identity: Niv99FrameIdentity): Niv99ValidationResult {
        val validation = validateLocked(identity)
        if (validation != Niv99ValidationResult.VALID) return validation
        latest.copyFrom(identity)
        initialized = true
        return Niv99ValidationResult.VALID
    }

    @Synchronized
    fun validate(identity: Niv99FrameIdentity): Niv99ValidationResult = validateLocked(identity)

    @Synchronized
    fun commit(identity: Niv99FrameIdentity): Boolean {
        if (validateLocked(identity) != Niv99ValidationResult.VALID) return false
        latest.copyFrom(identity)
        initialized = true
        return true
    }

    private fun validateLocked(identity: Niv99FrameIdentity): Niv99ValidationResult {
        if (!identity.isComplete()) return Niv99ValidationResult.INVALID_IDENTITY
        if (!initialized) return Niv99ValidationResult.VALID
        if (identity.cameraId == latest.cameraId) {
            if (identity.sessionGeneration < latest.sessionGeneration) {
                return Niv99ValidationResult.STALE_METADATA
            }
            if (identity.sessionGeneration == latest.sessionGeneration &&
                (identity.frameNumber <= latest.frameNumber ||
                    identity.sensorTimestampNs <= latest.sensorTimestampNs)
            ) {
                return Niv99ValidationResult.STALE_METADATA
            }
        }
        return Niv99ValidationResult.VALID
    }

    @Synchronized
    fun reset() {
        initialized = false
    }

    @Synchronized
    fun copyLatest(output: Niv99FrameIdentity): Boolean {
        if (!initialized) return false
        output.copyFrom(latest)
        return true
    }
}

/** Read-only linear plane. RAW data is never modified by NIV99. */
class Niv99LinearPlane {
    var samples: FloatArray = EMPTY_FLOATS
    var width: Int = 0
    var height: Int = 0
    var rowStride: Int = 0
    var offset: Int = 0
    var whiteLevel: Float = 1f
    var cfaPattern: Niv99CfaPattern = Niv99CfaPattern.UNKNOWN

    fun set(
        samples: FloatArray,
        width: Int,
        height: Int,
        rowStride: Int,
        offset: Int = 0,
        whiteLevel: Float = 1f,
        cfaPattern: Niv99CfaPattern = Niv99CfaPattern.UNKNOWN
    ): Niv99LinearPlane {
        this.samples = samples
        this.width = width
        this.height = height
        this.rowStride = rowStride
        this.offset = offset
        this.whiteLevel = whiteLevel
        this.cfaPattern = cfaPattern
        return this
    }

    fun isValid(): Boolean {
        if (width <= 0 || height <= 0 || rowStride < width || offset < 0) return false
        if (!whiteLevel.isFinite() || whiteLevel <= 0f) return false
        val last = offset.toLong() + (height - 1L) * rowStride + width
        return last <= samples.size.toLong()
    }

    fun sample(x: Int, y: Int): Float = samples[offset + y * rowStride + x]

    companion object {
        private val EMPTY_FLOATS = FloatArray(0)
    }
}

/** Optional semantic confidences are bounded and must be spatially evidenced by the caller. */
class Niv99SemanticTileMap(capacity: Int) {
    val skin: FloatArray = FloatArray(capacity)
    val hair: FloatArray = FloatArray(capacity)
    val foliage: FloatArray = FloatArray(capacity)
    val text: FloatArray = FloatArray(capacity)
    val sky: FloatArray = FloatArray(capacity)
    val repeatedPattern: FloatArray = FloatArray(capacity)
    var count: Int = 0

    fun clear(activeCount: Int) {
        require(activeCount in 0..skin.size)
        count = activeCount
        skin.fill(0f, 0, activeCount)
        hair.fill(0f, 0, activeCount)
        foliage.fill(0f, 0, activeCount)
        text.fill(0f, 0, activeCount)
        sky.fill(0f, 0, activeCount)
        repeatedPattern.fill(0f, 0, activeCount)
    }

    fun sanitize() {
        for (index in 0 until count) {
            skin[index] = Niv99Math.saturateFinite(skin[index])
            hair[index] = Niv99Math.saturateFinite(hair[index])
            foliage[index] = Niv99Math.saturateFinite(foliage[index])
            text[index] = Niv99Math.saturateFinite(text[index])
            sky[index] = Niv99Math.saturateFinite(sky[index])
            repeatedPattern[index] = Niv99Math.saturateFinite(repeatedPattern[index])
        }
    }
}

class Niv99WhiteBalanceSolution {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    /** Frame that supplied the estimate; differs from [identity] only while an explicit lock is held. */
    val sourceIdentity: Niv99FrameIdentity = Niv99FrameIdentity()
    var authority: Niv99WhiteBalanceAuthority = Niv99WhiteBalanceAuthority.UNSUPPORTED
    var redGain: Float = 1f
    var greenGain: Float = 1f
    var blueGain: Float = 1f
    val colorMatrix: FloatArray = FloatArray(9)
    var colorMatrixValid: Boolean = false
    var calibrationMatrixSource: Niv99CalibrationMatrixSource = Niv99CalibrationMatrixSource.NONE
    var calibrationTransformIdentityFallback: Boolean = false
    var cctKelvin: Float = 0f
    var duv: Float = 0f
    var confidence: Float = 0f
    var illuminantClass: Niv99IlluminantClass = Niv99IlluminantClass.UNKNOWN
    var mixedLightConfidence: Float = 0f
    var localFieldRevision: Long = 0L
    var clippingRisk: Float = 0f
    var resetReason: Niv99ResetReason = Niv99ResetReason.NONE

    init {
        Niv99Math.setIdentity3(colorMatrix)
    }

    fun copyFrom(other: Niv99WhiteBalanceSolution): Niv99WhiteBalanceSolution {
        identity.copyFrom(other.identity)
        sourceIdentity.copyFrom(other.sourceIdentity)
        authority = other.authority
        redGain = other.redGain
        greenGain = other.greenGain
        blueGain = other.blueGain
        other.colorMatrix.copyInto(colorMatrix)
        colorMatrixValid = other.colorMatrixValid
        calibrationMatrixSource = other.calibrationMatrixSource
        calibrationTransformIdentityFallback = other.calibrationTransformIdentityFallback
        cctKelvin = other.cctKelvin
        duv = other.duv
        confidence = other.confidence
        illuminantClass = other.illuminantClass
        mixedLightConfidence = other.mixedLightConfidence
        localFieldRevision = other.localFieldRevision
        clippingRisk = other.clippingRisk
        resetReason = other.resetReason
        return this
    }
}

class Niv99DenoisePlan {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    var rawSpatialStrength: Float = 0f
    var lumaSpatialStrength: Float = 0f
    var chromaSpatialStrength: Float = 0f
    var temporalStrength: Float = 0f
    var temporalHistoryFrames: Int = 0
    var motionConfidence: Float = 0f
    var occlusionRejection: Float = 1f
    var textureProtection: Float = 1f
    var edgeProtection: Float = 1f
    var skinProtection: Float = 1f
    var reconstructionSharpening: Float = 0f
    var sharpeningCeiling: Float = 0f
    var resetReason: Niv99ResetReason = Niv99ResetReason.NONE
    var realtimeDeadlineNs: Long = 40_000_000L

    fun copyFrom(other: Niv99DenoisePlan): Niv99DenoisePlan {
        identity.copyFrom(other.identity)
        rawSpatialStrength = other.rawSpatialStrength
        lumaSpatialStrength = other.lumaSpatialStrength
        chromaSpatialStrength = other.chromaSpatialStrength
        temporalStrength = other.temporalStrength
        temporalHistoryFrames = other.temporalHistoryFrames
        motionConfidence = other.motionConfidence
        occlusionRejection = other.occlusionRejection
        textureProtection = other.textureProtection
        edgeProtection = other.edgeProtection
        skinProtection = other.skinProtection
        reconstructionSharpening = other.reconstructionSharpening
        sharpeningCeiling = other.sharpeningCeiling
        resetReason = other.resetReason
        realtimeDeadlineNs = other.realtimeDeadlineNs
        return this
    }
}

/** Atomic logical packet: WB and denoise state always share one complete provenance identity. */
class Niv99StatePacket {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    val whiteBalance: Niv99WhiteBalanceSolution = Niv99WhiteBalanceSolution()
    val denoise: Niv99DenoisePlan = Niv99DenoisePlan()
    var capabilityStatus: Niv99CapabilityStatus = Niv99CapabilityStatus.UNSUPPORTED
    var accepted: Boolean = false
    var rejectionReason: Niv99ValidationResult = Niv99ValidationResult.VALID
    var processingTimeNs: Long = 0L

    fun copyFrom(other: Niv99StatePacket): Niv99StatePacket {
        identity.copyFrom(other.identity)
        whiteBalance.copyFrom(other.whiteBalance)
        denoise.copyFrom(other.denoise)
        capabilityStatus = other.capabilityStatus
        accepted = other.accepted
        rejectionReason = other.rejectionReason
        processingTimeNs = other.processingTimeNs
        return this
    }

    fun hasCoherentIdentity(): Boolean =
        identity.isComplete() &&
            identity.cameraId == whiteBalance.identity.cameraId &&
            identity.sessionGeneration == whiteBalance.identity.sessionGeneration &&
            identity.frameNumber == whiteBalance.identity.frameNumber &&
            identity.sensorTimestampNs == whiteBalance.identity.sensorTimestampNs &&
            identity.cameraId == denoise.identity.cameraId &&
            identity.sessionGeneration == denoise.identity.sessionGeneration &&
            identity.frameNumber == denoise.identity.frameNumber &&
            identity.sensorTimestampNs == denoise.identity.sensorTimestampNs
}

class Niv99TimelineSignature {
    var cameraId: String = ""
    var sessionGeneration: Long = -1L
    var operatingMode: Niv99OperatingMode = Niv99OperatingMode.VIDEO
    var width: Int = 0
    var height: Int = 0
    var targetFps: Int = 0
    var colorProfileId: Long = 0L
    var surfaceGeneration: Long = 0L
    var codecGeneration: Long = 0L
    var lastFrameNumber: Long = -1L
    var lastTimestampNs: Long = -1L
    var flashFired: Boolean = false
    var initialized: Boolean = false

    fun update(context: Niv99FrameContext) {
        cameraId = context.identity.cameraId
        sessionGeneration = context.identity.sessionGeneration
        operatingMode = context.operatingMode
        width = context.width
        height = context.height
        targetFps = context.targetFps
        colorProfileId = context.colorProfileId
        surfaceGeneration = context.surfaceGeneration
        codecGeneration = context.codecGeneration
        lastFrameNumber = context.identity.frameNumber
        lastTimestampNs = context.identity.sensorTimestampNs
        flashFired = context.flashFired
        initialized = true
    }

    fun resetReason(context: Niv99FrameContext): Niv99ResetReason {
        if (!initialized) return Niv99ResetReason.FIRST_FRAME
        if (cameraId != context.identity.cameraId) return Niv99ResetReason.CAMERA_CHANGED
        if (sessionGeneration != context.identity.sessionGeneration) return Niv99ResetReason.SESSION_CHANGED
        if (operatingMode != context.operatingMode) return Niv99ResetReason.MODE_CHANGED
        if (width != context.width || height != context.height) return Niv99ResetReason.DIMENSIONS_CHANGED
        if (targetFps != context.targetFps) return Niv99ResetReason.FPS_CHANGED
        if (colorProfileId != context.colorProfileId) return Niv99ResetReason.COLOR_PROFILE_CHANGED
        if (surfaceGeneration != context.surfaceGeneration) return Niv99ResetReason.SURFACE_CHANGED
        if (codecGeneration != context.codecGeneration) return Niv99ResetReason.CODEC_CHANGED
        if (context.sceneCutHint) return Niv99ResetReason.SCENE_CUT
        if (flashFired != context.flashFired) return Niv99ResetReason.FLASH_TRANSITION
        if (context.identity.frameNumber <= lastFrameNumber ||
            context.identity.sensorTimestampNs <= lastTimestampNs
        ) {
            return Niv99ResetReason.TIMESTAMP_DISCONTINUITY
        }
        val expectedFrameNs = 1_000_000_000L / max(context.targetFps, 1)
        val gap = context.identity.sensorTimestampNs - lastTimestampNs
        if (gap > expectedFrameNs * MAX_TIMESTAMP_GAP_FRAMES) {
            return Niv99ResetReason.TIMESTAMP_DISCONTINUITY
        }
        return Niv99ResetReason.NONE
    }

    companion object {
        private const val MAX_TIMESTAMP_GAP_FRAMES = 4L
    }
}
