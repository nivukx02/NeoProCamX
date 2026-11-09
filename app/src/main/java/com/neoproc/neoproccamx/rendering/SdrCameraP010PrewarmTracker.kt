package com.neoproc.neoproccamx.rendering

/**
 * Bounded readiness state for the gate-closed Camera2 P010 -> EGL recording warm-up.
 *
 * A successful draw proves that one real Camera2 buffer passed through import, full-resolution
 * RGB10 decode, and the exact SDR grade program without being presented to MediaCodec. Requiring
 * several frames and several stable AHardwareBuffer identities avoids declaring the rotating camera
 * pool warm after exercising only one allocation.
 */
internal class SdrCameraP010PrewarmTracker(
    private val targetSuccessfulFrames: Int = TARGET_SUCCESSFUL_FRAMES,
    private val minimumDistinctBufferIdentities: Int = MINIMUM_DISTINCT_BUFFER_IDENTITIES,
    private val maximumAttempts: Int = MAXIMUM_ATTEMPTS
) {
    init {
        require(targetSuccessfulFrames > 0)
        require(minimumDistinctBufferIdentities > 0)
        require(maximumAttempts >= targetSuccessfulFrames)
        require(maximumAttempts >= minimumDistinctBufferIdentities)
    }

    private val lock = Any()
    private val bufferIdentities = LinkedHashSet<Long>(maximumAttempts)
    private var attempts = 0L
    private var successes = 0L
    private var failures = 0L
    private var firstSensorTimestampNs = 0L
    private var lastSensorTimestampNs = 0L
    private var firstAttemptElapsedRealtimeNs = 0L
    private var lastAttemptElapsedRealtimeNs = 0L
    private var bypassReason: String? = null
    private var ignoredForeignSourceFrames = 0L
    private var lastIgnoredForeignSourceReason: String? = null

    fun reset() = synchronized(lock) {
        attempts = 0L
        successes = 0L
        failures = 0L
        firstSensorTimestampNs = 0L
        lastSensorTimestampNs = 0L
        firstAttemptElapsedRealtimeNs = 0L
        lastAttemptElapsedRealtimeNs = 0L
        bufferIdentities.clear()
        bypassReason = null
        ignoredForeignSourceFrames = 0L
        lastIgnoredForeignSourceReason = null
    }

    fun shouldAttempt(): Boolean = synchronized(lock) {
        !isCompleteLocked() && bypassReason == null && attempts < maximumAttempts
    }

    fun recordSuccess(
        bufferIdentity: Long,
        sensorTimestampNs: Long,
        observedAtElapsedRealtimeNs: Long
    ): SdrCameraP010PrewarmSnapshot = synchronized(lock) {
        require(bufferIdentity > 0L) { "A successful prewarm requires a stable buffer identity" }
        recordAttemptTimeLocked(sensorTimestampNs, observedAtElapsedRealtimeNs)
        attempts++
        successes++
        bufferIdentities.add(bufferIdentity)
        markExhaustedIfIncompleteLocked()
        snapshotLocked()
    }

    fun recordFailure(
        sensorTimestampNs: Long,
        observedAtElapsedRealtimeNs: Long
    ): SdrCameraP010PrewarmSnapshot = synchronized(lock) {
        recordAttemptTimeLocked(sensorTimestampNs, observedAtElapsedRealtimeNs)
        attempts++
        failures++
        markExhaustedIfIncompleteLocked()
        snapshotLocked()
    }

    fun markBypassed(reason: String): SdrCameraP010PrewarmSnapshot = synchronized(lock) {
        if (!isCompleteLocked() && bypassReason == null) {
            bypassReason = reason.ifBlank { "unsupported" }
        }
        snapshotLocked()
    }

    /**
     * Records an image delivered by another concurrently configured Camera2 output.
     *
     * The analysis YUV reader and the full-resolution P010 reader legitimately share the Vulkan
     * importer. A YUV callback therefore says nothing about P010 capability and must not terminate
     * the P010 proof. If no valid P010 arrives, the caller's bounded timeout still fails closed.
     */
    fun recordIgnoredForeignSource(reason: String): SdrCameraP010PrewarmSnapshot = synchronized(lock) {
        ignoredForeignSourceFrames++
        lastIgnoredForeignSourceReason = reason.ifBlank { "foreign-source" }
        snapshotLocked()
    }

    fun snapshot(): SdrCameraP010PrewarmSnapshot = synchronized(lock) { snapshotLocked() }

    private fun recordAttemptTimeLocked(sensorTimestampNs: Long, observedAtElapsedRealtimeNs: Long) {
        val safeObservedNs = observedAtElapsedRealtimeNs.coerceAtLeast(0L)
        if (firstAttemptElapsedRealtimeNs == 0L) firstAttemptElapsedRealtimeNs = safeObservedNs
        lastAttemptElapsedRealtimeNs = safeObservedNs
        if (sensorTimestampNs > 0L) {
            if (firstSensorTimestampNs == 0L) firstSensorTimestampNs = sensorTimestampNs
            lastSensorTimestampNs = sensorTimestampNs
        }
    }

    private fun isCompleteLocked(): Boolean =
        successes >= targetSuccessfulFrames &&
            bufferIdentities.size >= minimumDistinctBufferIdentities

    private fun markExhaustedIfIncompleteLocked() {
        if (attempts >= maximumAttempts && !isCompleteLocked()) {
            bypassReason = "attempt-budget-exhausted"
        }
    }

    private fun snapshotLocked(): SdrCameraP010PrewarmSnapshot = SdrCameraP010PrewarmSnapshot(
        attempts = attempts,
        successes = successes,
        failures = failures,
        distinctBufferIdentities = bufferIdentities.size,
        firstSensorTimestampNs = firstSensorTimestampNs,
        lastSensorTimestampNs = lastSensorTimestampNs,
        firstAttemptElapsedRealtimeNs = firstAttemptElapsedRealtimeNs,
        lastAttemptElapsedRealtimeNs = lastAttemptElapsedRealtimeNs,
        complete = isCompleteLocked(),
        bypassReason = bypassReason,
        ignoredForeignSourceFrames = ignoredForeignSourceFrames,
        lastIgnoredForeignSourceReason = lastIgnoredForeignSourceReason
    )

    companion object {
        const val TARGET_SUCCESSFUL_FRAMES = 8
        const val MINIMUM_DISTINCT_BUFFER_IDENTITIES = 3
        const val MAXIMUM_ATTEMPTS = 24
    }
}

internal data class SdrCameraP010PrewarmSnapshot(
    val attempts: Long,
    val successes: Long,
    val failures: Long,
    val distinctBufferIdentities: Int,
    val firstSensorTimestampNs: Long,
    val lastSensorTimestampNs: Long,
    val firstAttemptElapsedRealtimeNs: Long,
    val lastAttemptElapsedRealtimeNs: Long,
    val complete: Boolean,
    val bypassReason: String?,
    val ignoredForeignSourceFrames: Long,
    val lastIgnoredForeignSourceReason: String?
) {
    val terminal: Boolean
        get() = complete || bypassReason != null

    companion object {
        val EMPTY = SdrCameraP010PrewarmSnapshot(
            attempts = 0L,
            successes = 0L,
            failures = 0L,
            distinctBufferIdentities = 0,
            firstSensorTimestampNs = 0L,
            lastSensorTimestampNs = 0L,
            firstAttemptElapsedRealtimeNs = 0L,
            lastAttemptElapsedRealtimeNs = 0L,
            complete = false,
            bypassReason = null,
            ignoredForeignSourceFrames = 0L,
            lastIgnoredForeignSourceReason = null
        )
    }
}
