package com.neoproc.neoproccamx.niv99

import kotlin.math.ceil

/**
 * Allocation-free identity for the controls that can change visible preview pixels.
 *
 * A recent tuning packet may be reused only when every field remains identical. Keeping these
 * values explicit, instead of using a hash, makes a collision unable to cross a mode, colour,
 * surface, detail-profile, or manual-white-balance transition.
 */
class Niv99PreviewTuningContinuity {
    var cameraId: String = ""
        private set
    var sessionGeneration: Long = -1L
        private set
    var mode: Niv99OperatingMode = Niv99OperatingMode.VIDEO
        private set
    var domain: Niv99SignalDomain = Niv99SignalDomain.LINEAR_RGB
        private set
    var detailProfile: Niv99DetailProfile = Niv99DetailProfile.NATURAL
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var targetFps: Int = 0
        private set
    var colorProfileId: Long = 0L
        private set
    var surfaceGeneration: Long = -1L
        private set
    var codecGeneration: Long = -1L
        private set
    var manualKelvin: Int = 0
        private set
    var manualTint: Int = 0
        private set
    var awbLockRequested: Boolean = false
        private set

    fun set(
        cameraId: String,
        sessionGeneration: Long,
        mode: Niv99OperatingMode,
        domain: Niv99SignalDomain,
        detailProfile: Niv99DetailProfile,
        width: Int,
        height: Int,
        targetFps: Int,
        colorProfileId: Long,
        surfaceGeneration: Long,
        codecGeneration: Long,
        manualKelvin: Int,
        manualTint: Int,
        awbLockRequested: Boolean
    ): Boolean {
        if (cameraId.isBlank() || sessionGeneration < 0L || width <= 0 || height <= 0 ||
            targetFps <= 0 || colorProfileId <= 0L || surfaceGeneration < 0L ||
            codecGeneration < 0L
        ) {
            clear()
            return false
        }
        this.cameraId = cameraId
        this.sessionGeneration = sessionGeneration
        this.mode = mode
        this.domain = domain
        this.detailProfile = detailProfile
        this.width = width
        this.height = height
        this.targetFps = targetFps
        this.colorProfileId = colorProfileId
        this.surfaceGeneration = surfaceGeneration
        this.codecGeneration = codecGeneration
        this.manualKelvin = manualKelvin.takeIf { it > 0 } ?: 0
        this.manualTint = if (this.manualKelvin > 0) manualTint.coerceIn(-150, 150) else 0
        this.awbLockRequested = awbLockRequested
        return true
    }

    fun copyFrom(other: Niv99PreviewTuningContinuity): Niv99PreviewTuningContinuity {
        if (!other.isComplete()) {
            clear()
            return this
        }
        set(
            cameraId = other.cameraId,
            sessionGeneration = other.sessionGeneration,
            mode = other.mode,
            domain = other.domain,
            detailProfile = other.detailProfile,
            width = other.width,
            height = other.height,
            targetFps = other.targetFps,
            colorProfileId = other.colorProfileId,
            surfaceGeneration = other.surfaceGeneration,
            codecGeneration = other.codecGeneration,
            manualKelvin = other.manualKelvin,
            manualTint = other.manualTint,
            awbLockRequested = other.awbLockRequested
        )
        return this
    }

    fun isComplete(): Boolean = cameraId.isNotBlank() && sessionGeneration >= 0L &&
        width > 0 && height > 0 && targetFps > 0 && colorProfileId > 0L &&
        surfaceGeneration >= 0L && codecGeneration >= 0L

    fun clear(): Niv99PreviewTuningContinuity {
        cameraId = ""
        sessionGeneration = -1L
        mode = Niv99OperatingMode.VIDEO
        domain = Niv99SignalDomain.LINEAR_RGB
        detailProfile = Niv99DetailProfile.NATURAL
        width = 0
        height = 0
        targetFps = 0
        colorProfileId = 0L
        surfaceGeneration = -1L
        codecGeneration = -1L
        manualKelvin = 0
        manualTint = 0
        awbLockRequested = false
        return this
    }
}

enum class Niv99PreviewTuningSource {
    EXACT_FRAME,
    RECENT_CONTINUITY
}

enum class Niv99PreviewTuningFallbackStatus {
    MATCHED_RECENT,
    NOT_CONFIGURED,
    INVALID_REQUEST,
    WRONG_TIMELINE,
    NOT_FOUND
}

/** Pure admission policy for bounded preview-only tuning reuse. */
object Niv99PreviewTuningContinuityPolicy {
    const val MAXIMUM_AGE_SENSOR_INTERVALS = 2L
    private const val MINIMUM_REUSE_AGE_NS = 16_000_000L
    private const val MAXIMUM_REUSE_AGE_NS = 96_000_000L

    fun populate(
        identity: Niv99FrameIdentity,
        configuration: Niv99LiveFrameConfiguration,
        output: Niv99PreviewTuningContinuity
    ): Boolean = output.set(
        cameraId = identity.cameraId,
        sessionGeneration = identity.sessionGeneration,
        mode = configuration.mode,
        domain = configuration.domain,
        detailProfile = configuration.detailProfile,
        width = configuration.width,
        height = configuration.height,
        targetFps = configuration.targetFps,
        colorProfileId = configuration.colorProfileId,
        surfaceGeneration = configuration.surfaceGeneration,
        codecGeneration = configuration.codecGeneration,
        manualKelvin = configuration.userControls.manualKelvin,
        manualTint = configuration.userControls.manualTint,
        awbLockRequested = configuration.userControls.awbLockRequested
    )

    fun matches(
        expected: Niv99PreviewTuningContinuity,
        candidate: Niv99PreviewTuningContinuity
    ): Boolean = expected.isComplete() && candidate.isComplete() &&
        expected.cameraId == candidate.cameraId &&
        expected.sessionGeneration == candidate.sessionGeneration &&
        expected.mode == candidate.mode &&
        expected.domain == candidate.domain &&
        expected.detailProfile == candidate.detailProfile &&
        expected.width == candidate.width &&
        expected.height == candidate.height &&
        expected.targetFps == candidate.targetFps &&
        expected.colorProfileId == candidate.colorProfileId &&
        expected.surfaceGeneration == candidate.surfaceGeneration &&
        expected.codecGeneration == candidate.codecGeneration &&
        expected.manualKelvin == candidate.manualKelvin &&
        expected.manualTint == candidate.manualTint &&
        expected.awbLockRequested == candidate.awbLockRequested

    fun maximumReuseAgeNs(targetFps: Float): Long {
        if (!targetFps.isFinite() || targetFps <= 0f) return MAXIMUM_REUSE_AGE_NS
        val sensorIntervalNs = ceil(1_000_000_000.0 / targetFps.toDouble()).toLong()
        val ageNs = if (sensorIntervalNs > Long.MAX_VALUE / MAXIMUM_AGE_SENSOR_INTERVALS) {
            Long.MAX_VALUE
        } else {
            sensorIntervalNs * MAXIMUM_AGE_SENSOR_INTERVALS
        }
        return ageNs.coerceIn(MINIMUM_REUSE_AGE_NS, MAXIMUM_REUSE_AGE_NS)
    }

    fun canReuse(
        expected: Niv99PreviewTuningContinuity,
        candidate: Niv99LiveFrameTuning,
        targetSensorTimestampNs: Long,
        maximumAgeNs: Long
    ): Boolean {
        val sourceTimestampNs = candidate.identity.sensorTimestampNs
        val policyMaximumAgeNs = maximumReuseAgeNs(expected.targetFps.toFloat())
        val admittedMaximumAgeNs = minOf(maximumAgeNs, policyMaximumAgeNs)
        if (!candidate.valid || !candidate.pixelApplicationApproved ||
            !candidate.identity.isComplete() || targetSensorTimestampNs <= sourceTimestampNs ||
            sourceTimestampNs <= 0L || admittedMaximumAgeNs <= 0L ||
            !matches(expected, candidate.previewContinuity)
        ) return false
        return targetSensorTimestampNs - sourceTimestampNs <= admittedMaximumAgeNs
    }
}
