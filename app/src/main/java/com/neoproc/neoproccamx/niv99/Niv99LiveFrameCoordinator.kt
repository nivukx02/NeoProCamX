package com.neoproc.neoproccamx.niv99

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class Niv99MetadataLookupStatus {
    MATCHED,
    NOT_CONFIGURED,
    INVALID_IDENTITY,
    WRONG_TIMELINE,
    NOT_FOUND
}

class Niv99LiveFrameConfiguration {
    var mode: Niv99OperatingMode = Niv99OperatingMode.VIDEO
    var domain: Niv99SignalDomain = Niv99SignalDomain.LINEAR_RGB
    var detailProfile: Niv99DetailProfile = Niv99DetailProfile.NATURAL
    var width: Int = 0
    var height: Int = 0
    var targetFps: Int = 0
    var colorProfileId: Long = 0L
    var surfaceGeneration: Long = 0L
    var codecGeneration: Long = 0L
    val userControls = Niv99UserControlMetadata()

    fun copyFrom(other: Niv99LiveFrameConfiguration): Niv99LiveFrameConfiguration {
        mode = other.mode
        domain = other.domain
        detailProfile = other.detailProfile
        width = other.width
        height = other.height
        targetFps = other.targetFps
        colorProfileId = other.colorProfileId
        surfaceGeneration = other.surfaceGeneration
        codecGeneration = other.codecGeneration
        val source = other.userControls
        val target = userControls
        target.manualKelvin = source.manualKelvin
        target.manualTint = source.manualTint
        target.manualRedGain = source.manualRedGain
        target.manualGreenGain = source.manualGreenGain
        target.manualBlueGain = source.manualBlueGain
        target.manualMatrixAvailable = source.manualMatrixAvailable
        source.manualColorMatrix.copyInto(target.manualColorMatrix)
        target.awbLockRequested = source.awbLockRequested
        return this
    }
}

/** Caller-owned copy used to move exact-frame Camera2 metadata off the callback thread. */
class Niv99LiveFrameMetadata(
    lensShadingCapacity: Int = Niv99DynamicSensorMetadata.DEFAULT_LENS_SHADING_CAPACITY
) {
    val capture = Niv99CaptureMetadata(lensShadingCapacity)
    val calibration = Niv99SensorCalibration(lensShadingCapacity)
    val measuredNoise = Niv99MeasuredNoiseProfile()
    val configuration = Niv99LiveFrameConfiguration()
    var sequence: Long = 0L
        internal set

    fun copyFrom(
        capture: Niv99CaptureMetadata,
        calibration: Niv99SensorCalibration,
        measuredNoise: Niv99MeasuredNoiseProfile,
        configuration: Niv99LiveFrameConfiguration,
        sequence: Long
    ): Niv99LiveFrameMetadata {
        this.capture.copyFrom(capture)
        this.calibration.copyFrom(calibration)
        this.measuredNoise.copyFrom(measuredNoise)
        this.configuration.copyFrom(configuration)
        this.sequence = sequence
        return this
    }

    fun copyFrom(other: Niv99LiveFrameMetadata): Niv99LiveFrameMetadata = copyFrom(
        capture = other.capture,
        calibration = other.calibration,
        measuredNoise = other.measuredNoise,
        configuration = other.configuration,
        sequence = other.sequence
    )
}

class Niv99MetadataRingSnapshot {
    var publishedFrames: Long = 0L
    var exactMatches: Long = 0L
    var misses: Long = 0L
    var rejectedFrames: Long = 0L
    var activeGeneration: Long = -1L
    var latestSensorTimestampNs: Long = -1L
}

/**
 * Fixed-capacity timestamp join. It never retains CaptureResult or Image objects and never allocates
 * in publish/copyExact, so delayed or reordered Camera2 callbacks cannot leak into another frame.
 */
class Niv99FrameMetadataRing(
    capacity: Int = DEFAULT_CAPACITY,
    lensShadingCapacity: Int = Niv99DynamicSensorMetadata.DEFAULT_LENS_SHADING_CAPACITY
) {
    private val slots = Array(capacity.coerceIn(2, MAXIMUM_CAPACITY)) {
        Niv99LiveFrameMetadata(lensShadingCapacity)
    }
    private var cameraId: String = ""
    private var generation: Long = -1L
    private var nextSequence: Long = 1L
    private var publishedFrames: Long = 0L
    private var exactMatches: Long = 0L
    private var misses: Long = 0L
    private var rejectedFrames: Long = 0L
    private var latestSensorTimestampNs: Long = -1L
    private val publicationVersion = AtomicLong(0L)
    private val publicationLock = ReentrantLock()
    private val publicationReady = publicationLock.newCondition()

    @Synchronized
    fun beginTimeline(cameraId: String, sessionGeneration: Long): Boolean {
        if (cameraId.isBlank() || sessionGeneration < 0L) return false
        this.cameraId = cameraId
        generation = sessionGeneration
        nextSequence = 1L
        latestSensorTimestampNs = -1L
        for (slot in slots) slot.sequence = 0L
        signalPublicationChanged()
        return true
    }

    @Synchronized
    fun publish(
        capture: Niv99CaptureMetadata,
        calibration: Niv99SensorCalibration,
        measuredNoise: Niv99MeasuredNoiseProfile,
        configuration: Niv99LiveFrameConfiguration
    ): Boolean {
        val identity = capture.identity
        if (!identity.isComplete() || identity.cameraId != cameraId ||
            identity.sessionGeneration != generation ||
            calibration.cameraId != identity.cameraId ||
            calibration.validate() != Niv99ValidationResult.VALID
        ) {
            rejectedFrames += 1L
            return false
        }
        if (identity.sensorTimestampNs <= latestSensorTimestampNs) {
            rejectedFrames += 1L
            return false
        }
        if (nextSequence == Long.MAX_VALUE) {
            clearLocked()
            rejectedFrames += 1L
            return false
        }
        val sequence = nextSequence++
        val slot = slots[((sequence - 1L) % slots.size).toInt()]
        slot.copyFrom(capture, calibration, measuredNoise, configuration, sequence)
        latestSensorTimestampNs = identity.sensorTimestampNs
        publishedFrames += 1L
        signalPublicationChanged()
        return true
    }

    @Synchronized
    fun copyExact(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameMetadata
    ): Niv99MetadataLookupStatus = copyExactLocked(
        expectedCameraId = expectedCameraId,
        expectedGeneration = expectedGeneration,
        sensorTimestampNs = sensorTimestampNs,
        output = output,
        accountMiss = true
    )

    /**
     * Waits for an Image-first Camera2 result without retaining any ImageReader resource. The caller
     * must supply a heap-owned frame copy. Timeline changes wake the waiter immediately and exact
     * matching remains mandatory, so neither an older session nor a neighbouring frame can leak in.
     */
    fun awaitExact(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        timeoutNs: Long,
        output: Niv99LiveFrameMetadata
    ): Niv99MetadataLookupStatus {
        val waitBudgetNs = timeoutNs.coerceIn(0L, MAXIMUM_METADATA_WAIT_NS)
        val startedNs = System.nanoTime()
        val deadlineNs = if (Long.MAX_VALUE - startedNs < waitBudgetNs) {
            Long.MAX_VALUE
        } else {
            startedNs + waitBudgetNs
        }
        while (true) {
            val observedVersion = publicationVersion.get()
            val lookup = synchronized(this) {
                copyExactLocked(
                    expectedCameraId = expectedCameraId,
                    expectedGeneration = expectedGeneration,
                    sensorTimestampNs = sensorTimestampNs,
                    output = output,
                    accountMiss = false
                )
            }
            if (lookup != Niv99MetadataLookupStatus.NOT_FOUND) return lookup

            val canStillArrive = synchronized(this) {
                sensorTimestampNs > latestSensorTimestampNs
            }
            val remainingNs = (deadlineNs - System.nanoTime()).coerceAtLeast(0L)
            if (!canStillArrive || remainingNs == 0L) {
                synchronized(this) { misses += 1L }
                return Niv99MetadataLookupStatus.NOT_FOUND
            }

            publicationLock.lock()
            try {
                // Close the check-to-sleep race: a publication between the probe and this lock bumps
                // the version, so this iteration retries without sleeping through the notification.
                if (publicationVersion.get() == observedVersion) {
                    try {
                        publicationReady.awaitNanos(remainingNs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        synchronized(this) { misses += 1L }
                        return Niv99MetadataLookupStatus.NOT_FOUND
                    }
                }
            } finally {
                publicationLock.unlock()
            }
        }
    }

    private fun copyExactLocked(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameMetadata,
        accountMiss: Boolean
    ): Niv99MetadataLookupStatus {
        if (cameraId.isBlank() || generation < 0L) return Niv99MetadataLookupStatus.NOT_CONFIGURED
        if (expectedCameraId.isBlank() || expectedGeneration < 0L || sensorTimestampNs <= 0L) {
            return Niv99MetadataLookupStatus.INVALID_IDENTITY
        }
        if (expectedCameraId != cameraId || expectedGeneration != generation) {
            return Niv99MetadataLookupStatus.WRONG_TIMELINE
        }
        var best: Niv99LiveFrameMetadata? = null
        for (slot in slots) {
            val identity = slot.capture.identity
            if (slot.sequence > 0L && identity.cameraId == expectedCameraId &&
                identity.sessionGeneration == expectedGeneration &&
                identity.sensorTimestampNs == sensorTimestampNs &&
                (best == null || slot.sequence > best.sequence)
            ) {
                best = slot
            }
        }
        if (best == null) {
            if (accountMiss) misses += 1L
            return Niv99MetadataLookupStatus.NOT_FOUND
        }
        output.copyFrom(best)
        exactMatches += 1L
        return Niv99MetadataLookupStatus.MATCHED
    }

    @Synchronized
    fun snapshot(output: Niv99MetadataRingSnapshot) {
        output.publishedFrames = publishedFrames
        output.exactMatches = exactMatches
        output.misses = misses
        output.rejectedFrames = rejectedFrames
        output.activeGeneration = generation
        output.latestSensorTimestampNs = latestSensorTimestampNs
    }

    @Synchronized
    fun clear() {
        clearLocked()
        signalPublicationChanged()
    }

    private fun clearLocked() {
        cameraId = ""
        generation = -1L
        nextSequence = 1L
        latestSensorTimestampNs = -1L
        for (slot in slots) slot.sequence = 0L
    }

    private fun signalPublicationChanged() {
        publicationVersion.incrementAndGet()
        publicationLock.lock()
        try {
            publicationReady.signalAll()
        } finally {
            publicationLock.unlock()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 16
        private const val MAXIMUM_CAPACITY = 128
        private const val MAXIMUM_METADATA_WAIT_NS =
            Niv99DeferredMetadataJoinPolicy.MAXIMUM_WAIT_NS
    }
}

/** Fixed, reusable payload passed atomically with its exact Camera2 image. */
class Niv99LiveFrameTuning {
    val identity = Niv99FrameIdentity()
    /** Exact, collision-free configuration identity used only by bounded preview continuity reuse. */
    val previewContinuity = Niv99PreviewTuningContinuity()
    /** Analysis frame that supplied the bounded residual WB estimate. */
    val colorEstimateSourceIdentity = Niv99FrameIdentity()
    var valid: Boolean = false
    var pixelApplicationApproved: Boolean = false
        private set
    var pixelApplicationSucceeded: Boolean = false
        private set
    var authority: Niv99WhiteBalanceAuthority = Niv99WhiteBalanceAuthority.UNSUPPORTED
    val rgbCorrection = FloatArray(3) { 1f }
    var spatialDenoise: Float = 0f
    var chromaDenoise: Float = 0f
    var temporalDenoise: Float = 0f
    var sharpeningCeiling: Float = 0f
    var confidence: Float = 0f
    var calibrationRevision: Long = 0L
    var colorEstimateApplied: Boolean = false
    var whiteBalanceEffectMagnitude: Float = 0f
    var denoiseEffectMagnitude: Float = 0f
    var reconstructionEffectMagnitude: Float = 0f

    fun copyFrom(other: Niv99LiveFrameTuning): Niv99LiveFrameTuning {
        identity.copyFrom(other.identity)
        previewContinuity.copyFrom(other.previewContinuity)
        colorEstimateSourceIdentity.copyFrom(other.colorEstimateSourceIdentity)
        valid = other.valid
        pixelApplicationApproved = other.pixelApplicationApproved
        pixelApplicationSucceeded = other.pixelApplicationSucceeded
        authority = other.authority
        other.rgbCorrection.copyInto(rgbCorrection)
        spatialDenoise = other.spatialDenoise
        chromaDenoise = other.chromaDenoise
        temporalDenoise = other.temporalDenoise
        sharpeningCeiling = other.sharpeningCeiling
        confidence = other.confidence
        calibrationRevision = other.calibrationRevision
        colorEstimateApplied = other.colorEstimateApplied
        whiteBalanceEffectMagnitude = other.whiteBalanceEffectMagnitude
        denoiseEffectMagnitude = other.denoiseEffectMagnitude
        reconstructionEffectMagnitude = other.reconstructionEffectMagnitude
        return this
    }

    fun clear(): Niv99LiveFrameTuning {
        identity.set("", -1L, -1L, -1L)
        previewContinuity.clear()
        colorEstimateSourceIdentity.set("", -1L, -1L, -1L)
        valid = false
        pixelApplicationApproved = false
        pixelApplicationSucceeded = false
        authority = Niv99WhiteBalanceAuthority.UNSUPPORTED
        rgbCorrection.fill(1f)
        spatialDenoise = 0f
        chromaDenoise = 0f
        temporalDenoise = 0f
        sharpeningCeiling = 0f
        confidence = 0f
        calibrationRevision = 0L
        colorEstimateApplied = false
        whiteBalanceEffectMagnitude = 0f
        denoiseEffectMagnitude = 0f
        reconstructionEffectMagnitude = 0f
        return this
    }

    internal fun approvePixelApplication(): Boolean {
        if (!valid || !identity.isComplete()) return false
        pixelApplicationApproved = true
        return true
    }

    internal fun markPixelApplicationSucceeded(): Boolean {
        if (!valid || !pixelApplicationApproved || !identity.isComplete()) return false
        pixelApplicationSucceeded = true
        return true
    }
}

/**
 * Fixed-capacity cache of fully resolved tuning packets.
 *
 * Preview and recording can consume the same Camera2 frame on different threads. Resolving twice
 * would advance the stateful white-balance filter twice and could make the two outputs disagree.
 * This ring publishes one immutable-by-convention snapshot per exact frame identity and gives each
 * consumer its own copy. A previously unseen older frame is rejected instead of rewinding temporal
 * state. No Image or CaptureResult is retained and no steady-state allocation occurs.
 */
class Niv99ResolvedTuningRing(capacity: Int = DEFAULT_CAPACITY) {
    private class Slot {
        val tuning = Niv99LiveFrameTuning()
        var sequence: Long = 0L
    }

    private val slots = Array(capacity.coerceIn(2, MAXIMUM_CAPACITY)) { Slot() }
    private var cameraId: String = ""
    private var generation: Long = -1L
    private var nextSequence: Long = 1L
    private var latestSensorTimestampNs: Long = -1L

    @Synchronized
    fun beginTimeline(cameraId: String, sessionGeneration: Long): Boolean {
        if (cameraId.isBlank() || sessionGeneration < 0L) return false
        this.cameraId = cameraId
        generation = sessionGeneration
        nextSequence = 1L
        latestSensorTimestampNs = -1L
        slots.forEach { it.sequence = 0L }
        return true
    }

    @Synchronized
    fun publish(tuning: Niv99LiveFrameTuning): Boolean {
        val identity = tuning.identity
        if (!tuning.valid || !identity.isComplete() || identity.cameraId != cameraId ||
            identity.sessionGeneration != generation ||
            identity.sensorTimestampNs <= latestSensorTimestampNs ||
            nextSequence == Long.MAX_VALUE
        ) {
            return false
        }
        val sequence = nextSequence++
        val slot = slots[((sequence - 1L) % slots.size).toInt()]
        slot.tuning.copyFrom(tuning)
        slot.sequence = sequence
        latestSensorTimestampNs = identity.sensorTimestampNs
        return true
    }

    @Synchronized
    fun copyExact(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        if (cameraId.isBlank() || generation < 0L) return Niv99MetadataLookupStatus.NOT_CONFIGURED
        if (expectedCameraId.isBlank() || expectedGeneration < 0L || sensorTimestampNs <= 0L) {
            return Niv99MetadataLookupStatus.INVALID_IDENTITY
        }
        if (expectedCameraId != cameraId || expectedGeneration != generation) {
            return Niv99MetadataLookupStatus.WRONG_TIMELINE
        }
        var best: Slot? = null
        for (slot in slots) {
            val identity = slot.tuning.identity
            if (slot.sequence > 0L && identity.cameraId == expectedCameraId &&
                identity.sessionGeneration == expectedGeneration &&
                identity.sensorTimestampNs == sensorTimestampNs &&
                (best == null || slot.sequence > best.sequence)
            ) {
                best = slot
            }
        }
        val resolved = best ?: return Niv99MetadataLookupStatus.NOT_FOUND
        output.copyFrom(resolved.tuning)
        return Niv99MetadataLookupStatus.MATCHED
    }

    /**
     * Finds the unique packet for the same physical frame when Image and result timestamps differ
     * only by bounded Android clock-domain translation skew. This never reuses an adjacent frame:
     * the nearest candidate must be inside [maximumDeltaNs] and ties are rejected as ambiguous.
     */
    @Synchronized
    fun copyTimestampAligned(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        maximumDeltaNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        if (cameraId.isBlank() || generation < 0L) return Niv99MetadataLookupStatus.NOT_CONFIGURED
        if (expectedCameraId.isBlank() || expectedGeneration < 0L || sensorTimestampNs <= 0L ||
            maximumDeltaNs < 0L
        ) return Niv99MetadataLookupStatus.INVALID_IDENTITY
        if (expectedCameraId != cameraId || expectedGeneration != generation) {
            return Niv99MetadataLookupStatus.WRONG_TIMELINE
        }
        var best: Slot? = null
        var bestDeltaNs = Long.MAX_VALUE
        var ambiguous = false
        for (slot in slots) {
            if (slot.sequence <= 0L) continue
            val identity = slot.tuning.identity
            if (identity.cameraId != expectedCameraId ||
                identity.sessionGeneration != expectedGeneration
            ) continue
            val deltaNs = timestampDistanceNs(identity.sensorTimestampNs, sensorTimestampNs)
            if (deltaNs > maximumDeltaNs) continue
            when {
                deltaNs < bestDeltaNs -> {
                    best = slot
                    bestDeltaNs = deltaNs
                    ambiguous = false
                }
                deltaNs == bestDeltaNs && best?.tuning?.identity?.sensorTimestampNs !=
                    identity.sensorTimestampNs -> ambiguous = true
                deltaNs == bestDeltaNs && best != null && slot.sequence > best.sequence -> best = slot
            }
        }
        val resolved = best?.takeUnless { ambiguous }
            ?: return Niv99MetadataLookupStatus.NOT_FOUND
        output.copyFrom(resolved.tuning)
        return Niv99MetadataLookupStatus.MATCHED
    }

    /**
     * Returns the newest older packet that is safe for preview-only cadence continuity. Exact
     * recording consumers never call this API. Every configuration field is compared explicitly,
     * and the source packet must remain inside the caller's bounded sensor-time window.
     */
    @Synchronized
    fun copyNewestBefore(
        expectedCameraId: String,
        expectedGeneration: Long,
        targetSensorTimestampNs: Long,
        maximumAgeNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity,
        output: Niv99LiveFrameTuning
    ): Niv99PreviewTuningFallbackStatus {
        if (cameraId.isBlank() || generation < 0L) {
            return Niv99PreviewTuningFallbackStatus.NOT_CONFIGURED
        }
        if (expectedCameraId.isBlank() || expectedGeneration < 0L ||
            targetSensorTimestampNs <= 0L || maximumAgeNs <= 0L ||
            !expectedContinuity.isComplete()
        ) {
            return Niv99PreviewTuningFallbackStatus.INVALID_REQUEST
        }
        if (expectedCameraId != cameraId || expectedGeneration != generation ||
            expectedContinuity.cameraId != cameraId ||
            expectedContinuity.sessionGeneration != generation
        ) {
            return Niv99PreviewTuningFallbackStatus.WRONG_TIMELINE
        }
        var newest: Slot? = null
        for (slot in slots) {
            if (slot.sequence <= 0L) continue
            val candidate = slot.tuning
            if (Niv99PreviewTuningContinuityPolicy.canReuse(
                    expected = expectedContinuity,
                    candidate = candidate,
                    targetSensorTimestampNs = targetSensorTimestampNs,
                    maximumAgeNs = maximumAgeNs
                ) && (newest == null ||
                    candidate.identity.sensorTimestampNs >
                    newest.tuning.identity.sensorTimestampNs ||
                    candidate.identity.sensorTimestampNs ==
                    newest.tuning.identity.sensorTimestampNs && slot.sequence > newest.sequence)
            ) {
                newest = slot
            }
        }
        val resolved = newest ?: return Niv99PreviewTuningFallbackStatus.NOT_FOUND
        output.copyFrom(resolved.tuning)
        return Niv99PreviewTuningFallbackStatus.MATCHED_RECENT
    }

    @Synchronized
    fun canResolveNewFrame(sensorTimestampNs: Long): Boolean =
        sensorTimestampNs > latestSensorTimestampNs

    @Synchronized
    fun canResolveTimestampAlignedFrame(sensorTimestampNs: Long, maximumDeltaNs: Long): Boolean {
        if (sensorTimestampNs <= 0L || maximumDeltaNs < 0L) return false
        val latestPossibleTimestampNs = if (sensorTimestampNs > Long.MAX_VALUE - maximumDeltaNs) {
            Long.MAX_VALUE
        } else {
            sensorTimestampNs + maximumDeltaNs
        }
        return latestSensorTimestampNs <= latestPossibleTimestampNs
    }

    @Synchronized
    fun clear() {
        cameraId = ""
        generation = -1L
        nextSequence = 1L
        latestSensorTimestampNs = -1L
        slots.forEach { it.sequence = 0L }
    }

    private fun timestampDistanceNs(first: Long, second: Long): Long {
        if (first == second) return 0L
        val larger = maxOf(first, second)
        val smaller = minOf(first, second)
        return if (smaller < 0L && larger > Long.MAX_VALUE + smaller) {
            Long.MAX_VALUE
        } else {
            larger - smaller
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 24
        private const val MAXIMUM_CAPACITY = 128
    }
}

/**
 * Resolves stateful live tuning exactly once, in Camera2 capture-result order, and then exposes only
 * immutable-by-convention exact-frame copies to asynchronous preview and recording consumers.
 *
 * Keeping temporal policy advancement on the publisher thread prevents a later NPV10 worker (or
 * preview submission) from advancing the filter before an earlier worker has looked up its frame.
 * The cache is fixed-capacity and all scratch objects are preallocated.
 */
class Niv99SequentialTuningCache(capacity: Int = Niv99ResolvedTuningRing.DEFAULT_CAPACITY) {
    private val resolvedRing = Niv99ResolvedTuningRing(capacity)
    private val policy = Niv99LiveTuningPolicy()
    private val resolvedScratch = Niv99LiveFrameTuning()
    private val publicationLock = ReentrantLock()
    private val publicationReady = publicationLock.newCondition()
    private var cameraId: String = ""
    private var sessionGeneration: Long = -1L

    @Synchronized
    fun beginTimeline(cameraId: String, sessionGeneration: Long): Boolean {
        if (!resolvedRing.beginTimeline(cameraId, sessionGeneration)) return false
        this.cameraId = cameraId
        this.sessionGeneration = sessionGeneration
        resolvedScratch.clear()
        policy.reset(Niv99ResetReason.SESSION_CHANGED)
        signalTimelineChanged()
        return true
    }

    @Synchronized
    fun resolveAndPublish(
        metadata: Niv99LiveFrameMetadata,
        colorEstimate: Niv99YuvColorEstimate?
    ): Niv99ValidationResult {
        val identity = metadata.capture.identity
        if (!identity.isComplete()) return Niv99ValidationResult.INVALID_IDENTITY
        if (identity.cameraId != cameraId || identity.sessionGeneration != sessionGeneration) {
            return Niv99ValidationResult.STALE_METADATA
        }
        val resolution = policy.resolve(metadata, colorEstimate, resolvedScratch)
        if (resolution != Niv99ValidationResult.VALID) {
            resolvedScratch.clear()
            return resolution
        }
        publicationLock.lock()
        try {
            if (!resolvedRing.publish(resolvedScratch)) {
                resolvedScratch.clear()
                return Niv99ValidationResult.STALE_METADATA
            }
            publicationReady.signalAll()
        } finally {
            publicationLock.unlock()
        }
        return Niv99ValidationResult.VALID
    }

    fun copyExact(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus = resolvedRing.copyExact(
        expectedCameraId = expectedCameraId,
        expectedGeneration = expectedGeneration,
        sensorTimestampNs = sensorTimestampNs,
        output = output
    )

    fun copyTimestampAligned(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        maximumDeltaNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus = resolvedRing.copyTimestampAligned(
        expectedCameraId = expectedCameraId,
        expectedGeneration = expectedGeneration,
        sensorTimestampNs = sensorTimestampNs,
        maximumDeltaNs = maximumDeltaNs,
        output = output
    )

    fun copyNewestBefore(
        expectedCameraId: String,
        expectedGeneration: Long,
        targetSensorTimestampNs: Long,
        maximumAgeNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity,
        output: Niv99LiveFrameTuning
    ): Niv99PreviewTuningFallbackStatus = resolvedRing.copyNewestBefore(
        expectedCameraId = expectedCameraId,
        expectedGeneration = expectedGeneration,
        targetSensorTimestampNs = targetSensorTimestampNs,
        maximumAgeNs = maximumAgeNs,
        expectedContinuity = expectedContinuity,
        output = output
    )

    /**
     * Waits only for the short Camera2 image-before-result skew. A later cached timestamp proves
     * that the requested packet can no longer arrive, so that case fails immediately.
     */
    fun awaitExact(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        timeoutNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        var remainingNs = timeoutNs.coerceIn(0L, MAXIMUM_EXACT_LOOKUP_WAIT_NS)
        publicationLock.lock()
        try {
            while (true) {
                val lookup = resolvedRing.copyExact(
                    expectedCameraId = expectedCameraId,
                    expectedGeneration = expectedGeneration,
                    sensorTimestampNs = sensorTimestampNs,
                    output = output
                )
                if (lookup != Niv99MetadataLookupStatus.NOT_FOUND || remainingNs <= 0L ||
                    !resolvedRing.canResolveNewFrame(sensorTimestampNs)
                ) {
                    return lookup
                }
                remainingNs = try {
                    publicationReady.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Niv99MetadataLookupStatus.NOT_FOUND
                }
            }
        } finally {
            publicationLock.unlock()
        }
    }

    /** Bounded same-physical-frame join for Image/result clock-domain translation skew. */
    fun awaitTimestampAligned(
        expectedCameraId: String,
        expectedGeneration: Long,
        sensorTimestampNs: Long,
        maximumDeltaNs: Long,
        timeoutNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        var remainingNs = timeoutNs.coerceIn(0L, MAXIMUM_EXACT_LOOKUP_WAIT_NS)
        publicationLock.lock()
        try {
            while (true) {
                val lookup = resolvedRing.copyTimestampAligned(
                    expectedCameraId = expectedCameraId,
                    expectedGeneration = expectedGeneration,
                    sensorTimestampNs = sensorTimestampNs,
                    maximumDeltaNs = maximumDeltaNs,
                    output = output
                )
                if (lookup != Niv99MetadataLookupStatus.NOT_FOUND || remainingNs <= 0L ||
                    !resolvedRing.canResolveTimestampAlignedFrame(sensorTimestampNs, maximumDeltaNs)
                ) {
                    return lookup
                }
                remainingNs = try {
                    publicationReady.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Niv99MetadataLookupStatus.NOT_FOUND
                }
            }
        } finally {
            publicationLock.unlock()
        }
    }

    @Synchronized
    fun clear(reason: Niv99ResetReason) {
        cameraId = ""
        sessionGeneration = -1L
        resolvedScratch.clear()
        resolvedRing.clear()
        policy.reset(reason)
        signalTimelineChanged()
    }

    private fun signalTimelineChanged() {
        publicationLock.lock()
        try {
            publicationReady.signalAll()
        } finally {
            publicationLock.unlock()
        }
    }

    private companion object {
        const val MAXIMUM_EXACT_LOOKUP_WAIT_NS = Niv99DeferredMetadataJoinPolicy.MAXIMUM_WAIT_NS
    }
}

/**
 * Live Video policy. It derives a conservative calibrated noise envelope and a HAL-relative WB
 * stabilization correction. Manual and locked WB always resolve to identity correction, so this
 * layer cannot override user or shot-lock intent.
 */
class Niv99LiveTuningPolicy {
    private val context = Niv99FrameContext()
    private val illuminant = Niv99IlluminantEstimate()
    private val whiteBalanceController = Niv99WhiteBalanceController()
    private val whiteBalance = Niv99WhiteBalanceSolution()
    private val noiseModel = Niv99NoiseModel()
    private val noise = Niv99NoiseModelSnapshot()

    fun resolve(
        metadata: Niv99LiveFrameMetadata,
        output: Niv99LiveFrameTuning
    ): Niv99ValidationResult = resolve(metadata, null, output)

    @Synchronized
    fun resolve(
        metadata: Niv99LiveFrameMetadata,
        colorEstimate: Niv99YuvColorEstimate?,
        output: Niv99LiveFrameTuning
    ): Niv99ValidationResult {
        output.clear()
        val status = metadata.capture.applyTo(
            context = context,
            userControls = metadata.configuration.userControls,
            mode = metadata.configuration.mode,
            signalDomain = metadata.configuration.domain,
            detailProfile = metadata.configuration.detailProfile,
            width = metadata.configuration.width,
            height = metadata.configuration.height,
            targetFps = metadata.configuration.targetFps,
            colorProfileId = metadata.configuration.colorProfileId,
            surfaceGeneration = metadata.configuration.surfaceGeneration,
            codecGeneration = metadata.configuration.codecGeneration
        )
        if (status != Niv99ValidationResult.VALID) return status
        val colorEstimateUsable = seedFullFrameIlluminant(context, colorEstimate)
        val wbStatus = whiteBalanceController.resolve(
            context = context,
            calibration = metadata.calibration,
            estimate = illuminant.takeIf { colorEstimateUsable },
            mixedLight = null,
            output = whiteBalance
        )
        if (wbStatus != Niv99ValidationResult.VALID) return wbStatus
        val noiseStatus = noiseModel.resolve(
            context = context,
            calibration = metadata.calibration,
            measured = metadata.measuredNoise.takeIf { it.validate() },
            output = noise
        )
        if (noiseStatus != Niv99ValidationResult.VALID) return noiseStatus

        output.identity.copyFrom(context.identity)
        val continuityValid = Niv99PreviewTuningContinuityPolicy.populate(
            identity = metadata.capture.identity,
            configuration = metadata.configuration,
            output = output.previewContinuity
        )
        output.authority = whiteBalance.authority
        resolveRelativeWhiteBalance(context, output)
        output.colorEstimateApplied = colorEstimateUsable &&
            whiteBalance.authority == Niv99WhiteBalanceAuthority.NIV99_AUTO
        if (output.colorEstimateApplied) {
            output.colorEstimateSourceIdentity.copyFrom(checkNotNull(colorEstimate).sourceIdentity)
        }
        val referenceSignal = 0.18f
        val sigma = sqrt(max(noise.meanVariance(referenceSignal, referenceSignal, referenceSignal), 0f))
        val normalizedNoise = (sigma / referenceSignal).coerceIn(0f, 1f)
        val modeledPressure = Niv99Math.smoothstep(0.015f, 0.24f, normalizedNoise)
        val measuredLumaPressure = if (output.colorEstimateApplied) {
            Niv99Math.smoothstep(0.0045f, 0.045f, colorEstimate!!.lumaNoiseSigma)
        } else {
            0f
        }
        val measuredChromaPressure = if (output.colorEstimateApplied) {
            Niv99Math.smoothstep(0.0035f, 0.040f, colorEstimate!!.chromaNoiseSigma)
        } else {
            0f
        }
        val lumaPressure = max(modeledPressure, measuredLumaPressure * 0.82f)
        val chromaPressure = max(modeledPressure, measuredChromaPressure)
        val profileScale = when (metadata.configuration.detailProfile) {
            Niv99DetailProfile.FOCUSED -> 0.90f
            Niv99DetailProfile.NATURAL -> 0.78f
            Niv99DetailProfile.EXTREME -> 0.70f
        }
        output.spatialDenoise = (lumaPressure * 0.62f * profileScale).coerceIn(0f, 0.62f)
        output.chromaDenoise = (chromaPressure * 0.86f * profileScale).coerceIn(0f, 0.86f)
        // Metadata alone cannot prove motion alignment. Temporal blending remains disabled until the
        // Vulkan forward/backward-flow consistency result is wired for the exact frame.
        output.temporalDenoise = 0f
        output.sharpeningCeiling = (0.72f - lumaPressure * 0.34f).coerceIn(0.28f, 0.72f)
        output.confidence = minOf(whiteBalance.confidence, if (noise.measuredProfile) 1f else 0.55f)
        output.calibrationRevision = metadata.calibration.revision
        output.whiteBalanceEffectMagnitude = max(
            abs(output.rgbCorrection[0] - 1f),
            abs(output.rgbCorrection[2] - 1f)
        ) * output.confidence
        output.denoiseEffectMagnitude = max(output.spatialDenoise, output.chromaDenoise) *
            output.confidence
        output.reconstructionEffectMagnitude = output.sharpeningCeiling * output.confidence
        output.valid = output.identity.isComplete() && continuityValid &&
            output.rgbCorrection.all { it.isFinite() }
        if (output.valid && Niv99VideoPixelApplicationPolicy.isEligible(metadata.configuration)) {
            output.approvePixelApplication()
        }
        return if (output.valid) Niv99ValidationResult.VALID else Niv99ValidationResult.NON_FINITE_METADATA
    }

    @Synchronized
    fun reset(reason: Niv99ResetReason) {
        whiteBalanceController.reset(reason)
    }

    private fun seedFullFrameIlluminant(
        context: Niv99FrameContext,
        estimate: Niv99YuvColorEstimate?
    ): Boolean {
        val intent = context.whiteBalanceIntent
        if (estimate == null || !estimate.isValid() || !intent.hasValidHalGains()) return false
        val source = estimate.sourceIdentity
        if (!source.sameTimeline(context.identity) ||
            source.frameNumber > context.identity.frameNumber ||
            source.sensorTimestampNs > context.identity.sensorTimestampNs
        ) return false
        illuminant.identity.copyFrom(context.identity)
        val green = (intent.halGreenEvenGain + intent.halGreenOddGain) * 0.5f
        illuminant.redGain = intent.halRedGain * estimate.redCorrection
        illuminant.greenGain = green * estimate.greenCorrection
        illuminant.blueGain = intent.halBlueGain * estimate.blueCorrection
        illuminant.redResponseOverGreen = illuminant.greenGain / max(illuminant.redGain, MIN_GAIN)
        illuminant.blueResponseOverGreen = illuminant.greenGain / max(illuminant.blueGain, MIN_GAIN)
        illuminant.cctKelvin = (
            5_500f + (illuminant.blueResponseOverGreen - illuminant.redResponseOverGreen) * 1_500f
            ).coerceIn(2_000f, 12_000f)
        illuminant.duv = 0f
        illuminant.confidence = estimate.confidence
        illuminant.consensusFraction = estimate.consensusFraction
        illuminant.neutralTileFraction = estimate.neutralTileFraction
        illuminant.clippingRisk = estimate.clippingFraction
        illuminant.illuminantClass = Niv99IlluminantClass.UNKNOWN
        illuminant.candidateCount = estimate.totalTileCount
        illuminant.acceptedCandidateCount = estimate.acceptedTileCount
        return true
    }

    private fun resolveRelativeWhiteBalance(
        context: Niv99FrameContext,
        output: Niv99LiveFrameTuning
    ) {
        val intent = context.whiteBalanceIntent
        if (intent.hasManualAuthority() || intent.awbLockRequested || intent.awbLockActive ||
            !intent.hasValidHalGains() || whiteBalance.authority != Niv99WhiteBalanceAuthority.NIV99_AUTO
        ) {
            output.rgbCorrection.fill(1f)
            return
        }
        val halGreen = (intent.halGreenEvenGain + intent.halGreenOddGain) * 0.5f
        val redRatio = whiteBalance.redGain / max(intent.halRedGain, MIN_GAIN)
        val greenRatio = whiteBalance.greenGain / max(halGreen, MIN_GAIN)
        val blueRatio = whiteBalance.blueGain / max(intent.halBlueGain, MIN_GAIN)
        val normalization = max(greenRatio, MIN_GAIN)
        output.rgbCorrection[0] = (redRatio / normalization).coerceIn(MIN_CORRECTION, MAX_CORRECTION)
        output.rgbCorrection[1] = 1f
        output.rgbCorrection[2] = (blueRatio / normalization).coerceIn(MIN_CORRECTION, MAX_CORRECTION)
    }

    companion object {
        private const val MIN_GAIN = 1e-5f
        private const val MIN_CORRECTION = 0.92f
        private const val MAX_CORRECTION = 1.08f
    }
}
