package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * Identifies the two Android monotonic clock domains that camera vendors may use for frame
 * timestamps. BOOTTIME matches [android.os.SystemClock.elapsedRealtimeNanos] and includes suspend;
 * MONOTONIC matches [System.nanoTime] and excludes suspend.
 */
internal enum class SensorTimestampClockDomain {
    BOOTTIME,
    MONOTONIC,
    SHARED_OR_AMBIGUOUS,
    UNKNOWN
}

internal object SensorTimestampClockDomainPolicy {
    private const val MAX_CALLBACK_AGE_NS = 10_000_000_000L
    private const val MINIMUM_DOMAIN_SEPARATION_NS = 250_000_000L

    fun classify(
        timestampNs: Long,
        monotonicNowNs: Long,
        boottimeNowNs: Long
    ): SensorTimestampClockDomain {
        if (timestampNs <= 0L || monotonicNowNs <= 0L || boottimeNowNs <= 0L) {
            return SensorTimestampClockDomain.UNKNOWN
        }
        val clockSeparationNs = absoluteDifference(monotonicNowNs, boottimeNowNs)
        if (clockSeparationNs < MINIMUM_DOMAIN_SEPARATION_NS) {
            return SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS
        }
        val monotonicDistanceNs = absoluteDifference(timestampNs, monotonicNowNs)
        val boottimeDistanceNs = absoluteDifference(timestampNs, boottimeNowNs)
        val closeToMonotonic = monotonicDistanceNs <= MAX_CALLBACK_AGE_NS
        val closeToBoottime = boottimeDistanceNs <= MAX_CALLBACK_AGE_NS
        // A camera frame can spend hundreds of milliseconds in a bounded ImageReader/writer
        // queue. When both clocks are plausible, choosing the *closer* clock reclassifies a
        // delayed BOOTTIME frame as MONOTONIC and invents a suspend offset. Clock proximity
        // can exclude a domain, but cannot identify one when both explain the arrival time.
        return when {
            closeToMonotonic && !closeToBoottime ->
                SensorTimestampClockDomain.MONOTONIC
            closeToBoottime && !closeToMonotonic ->
                SensorTimestampClockDomain.BOOTTIME
            closeToMonotonic || closeToBoottime -> SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS
            else -> SensorTimestampClockDomain.UNKNOWN
        }
    }

    fun translate(
        timestampNs: Long,
        source: SensorTimestampClockDomain,
        target: SensorTimestampClockDomain,
        monotonicNowNs: Long,
        boottimeNowNs: Long
    ): Long {
        if (timestampNs <= 0L || source == target ||
            source == SensorTimestampClockDomain.UNKNOWN ||
            target == SensorTimestampClockDomain.UNKNOWN ||
            source == SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS ||
            target == SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS
        ) {
            return timestampNs
        }
        val offsetNs = when {
            source == SensorTimestampClockDomain.MONOTONIC &&
                target == SensorTimestampClockDomain.BOOTTIME ->
                saturatingSubtract(boottimeNowNs, monotonicNowNs)
            source == SensorTimestampClockDomain.BOOTTIME &&
                target == SensorTimestampClockDomain.MONOTONIC ->
                saturatingSubtract(monotonicNowNs, boottimeNowNs)
            else -> return timestampNs
        }
        return saturatingAdd(timestampNs, offsetNs)
    }

    private fun absoluteDifference(first: Long, second: Long): Long {
        if (first == second) return 0L
        val larger = maxOf(first, second)
        val smaller = minOf(first, second)
        return if (smaller < 0L && larger > Long.MAX_VALUE + smaller) {
            Long.MAX_VALUE
        } else {
            larger - smaller
        }
    }

    private fun saturatingAdd(value: Long, delta: Long): Long = when {
        delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
        delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
        else -> value + delta
    }

    private fun saturatingSubtract(left: Long, right: Long): Long = when {
        right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
        right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
        else -> left - right
    }
}

internal data class SensorTimestampTimebaseSnapshot(
    val referenceDomain: SensorTimestampClockDomain,
    val lastSourceDomain: SensorTimestampClockDomain,
    val normalizedCount: Long,
    val referenceSnapCount: Long,
    val passthroughCount: Long,
    val unresolvedCount: Long,
    val lastRawTimestampNs: Long,
    val lastCanonicalTimestampNs: Long,
    val lastAppliedOffsetNs: Long
) {
    fun toMetadata(prefix: String): Map<String, String> = linkedMapOf(
        "${prefix}ReferenceClockDomain" to referenceDomain.name,
        "${prefix}LastSourceClockDomain" to lastSourceDomain.name,
        "${prefix}NormalizedTimestampCount" to normalizedCount.toString(),
        "${prefix}ReferenceSnapCount" to referenceSnapCount.toString(),
        "${prefix}PassthroughTimestampCount" to passthroughCount.toString(),
        "${prefix}UnresolvedTimestampCount" to unresolvedCount.toString(),
        "${prefix}LastRawTimestampNs" to lastRawTimestampNs.toString(),
        "${prefix}LastCanonicalTimestampNs" to lastCanonicalTimestampNs.toString(),
        "${prefix}LastAppliedClockOffsetNs" to lastAppliedOffsetNs.toString()
    )
}

/**
 * Lock-free session-scoped reconciler. Camera2 capture-result timestamps establish the reference
 * domain; ImageReader timestamps are translated only when both domains are classified unambiguously.
 * Unknown/ambiguous inputs remain unchanged instead of guessing an identity.
 */
internal class SensorTimestampTimebaseReconciler {
    private val referenceDomain = AtomicReference(SensorTimestampClockDomain.UNKNOWN)
    private val lastSourceDomain = AtomicReference(SensorTimestampClockDomain.UNKNOWN)
    private val normalizedCount = AtomicLong(0L)
    private val referenceSnapCount = AtomicLong(0L)
    private val passthroughCount = AtomicLong(0L)
    private val unresolvedCount = AtomicLong(0L)
    private val lastRawTimestampNs = AtomicLong(0L)
    private val lastCanonicalTimestampNs = AtomicLong(0L)
    private val lastAppliedOffsetNs = AtomicLong(0L)
    private val referenceTimestampSequence = AtomicLong(0L)
    private val referenceTimestamps = AtomicLongArray(REFERENCE_TIMESTAMP_CAPACITY)

    fun reset(referenceHint: SensorTimestampClockDomain = SensorTimestampClockDomain.UNKNOWN) {
        referenceDomain.set(referenceHint.validReferenceHint())
        lastSourceDomain.set(SensorTimestampClockDomain.UNKNOWN)
        normalizedCount.set(0L)
        referenceSnapCount.set(0L)
        passthroughCount.set(0L)
        unresolvedCount.set(0L)
        lastRawTimestampNs.set(0L)
        lastCanonicalTimestampNs.set(0L)
        lastAppliedOffsetNs.set(0L)
        referenceTimestampSequence.set(0L)
        for (index in 0 until REFERENCE_TIMESTAMP_CAPACITY) {
            referenceTimestamps.set(index, 0L)
        }
    }

    fun observeReference(
        timestampNs: Long,
        monotonicNowNs: Long,
        boottimeNowNs: Long
    ): SensorTimestampClockDomain {
        val observed = SensorTimestampClockDomainPolicy.classify(
            timestampNs = timestampNs,
            monotonicNowNs = monotonicNowNs,
            boottimeNowNs = boottimeNowNs
        )
        if (observed != SensorTimestampClockDomain.UNKNOWN) {
            while (true) {
                val current = referenceDomain.get()
                if (current == observed || current.isConcrete()) break
                if (referenceDomain.compareAndSet(current, observed)) break
            }
        }
        if (timestampNs > 0L) {
            val sequence = referenceTimestampSequence.getAndIncrement()
            referenceTimestamps.set(
                (sequence % REFERENCE_TIMESTAMP_CAPACITY.toLong()).toInt(),
                timestampNs
            )
        }
        return referenceDomain.get()
    }

    fun normalizeToReference(
        timestampNs: Long,
        monotonicNowNs: Long,
        boottimeNowNs: Long
    ): Long {
        val target = referenceDomain.get()
        val exactReference = findObservedReference(timestampNs, toleranceNs = 0L)
        var source = if (exactReference != null) target else SensorTimestampClockDomainPolicy.classify(
            timestampNs = timestampNs,
            monotonicNowNs = monotonicNowNs,
            boottimeNowNs = boottimeNowNs
        )
        var translated = SensorTimestampClockDomainPolicy.translate(
            timestampNs = timestampNs,
            source = source,
            target = target,
            monotonicNowNs = monotonicNowNs,
            boottimeNowNs = boottimeNowNs
        )
        if (exactReference == null && source == SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS &&
            target.isConcrete()
        ) {
            // Some output surfaces genuinely use the opposite clock even with a small suspend
            // offset. Preserve that route only when the translated value identifies an observed
            // capture result. This decision is per frame: hardware-import and P010 surfaces share
            // this reconciler and must not inherit a clock guessed for the other surface.
            val oppositeSource = if (target == SensorTimestampClockDomain.BOOTTIME) {
                SensorTimestampClockDomain.MONOTONIC
            } else {
                SensorTimestampClockDomain.BOOTTIME
            }
            val candidate = SensorTimestampClockDomainPolicy.translate(
                timestampNs, oppositeSource, target, monotonicNowNs, boottimeNowNs
            )
            if (candidate != timestampNs && findObservedReference(candidate) != null) {
                source = oppositeSource
                translated = candidate
            }
        }
        // Do not snap an untranslated sensor timestamp merely because a different frame is
        // nearby. Exact source identity wins over all callback-age or clock-offset heuristics.
        val canonical = if (translated != timestampNs) snapToObservedReference(translated) else timestampNs
        lastSourceDomain.set(source)
        lastRawTimestampNs.set(timestampNs)
        lastCanonicalTimestampNs.set(canonical)
        lastAppliedOffsetNs.set(saturatingSubtract(canonical, timestampNs))
        if (canonical != translated) referenceSnapCount.incrementAndGet()
        when {
            source == SensorTimestampClockDomain.UNKNOWN ||
                target == SensorTimestampClockDomain.UNKNOWN -> unresolvedCount.incrementAndGet()
            canonical != timestampNs -> normalizedCount.incrementAndGet()
            else -> passthroughCount.incrementAndGet()
        }
        return canonical
    }

    fun snapshot(): SensorTimestampTimebaseSnapshot = SensorTimestampTimebaseSnapshot(
        referenceDomain = referenceDomain.get(),
        lastSourceDomain = lastSourceDomain.get(),
        normalizedCount = normalizedCount.get(),
        referenceSnapCount = referenceSnapCount.get(),
        passthroughCount = passthroughCount.get(),
        unresolvedCount = unresolvedCount.get(),
        lastRawTimestampNs = lastRawTimestampNs.get(),
        lastCanonicalTimestampNs = lastCanonicalTimestampNs.get(),
        lastAppliedOffsetNs = lastAppliedOffsetNs.get()
    )

    private fun snapToObservedReference(translatedTimestampNs: Long): Long {
        return findObservedReference(translatedTimestampNs) ?: translatedTimestampNs
    }

    private fun findObservedReference(
        timestampNs: Long,
        toleranceNs: Long = REFERENCE_SNAP_TOLERANCE_NS
    ): Long? {
        if (timestampNs <= 0L) return null
        var matchedTimestampNs: Long? = null
        var ambiguous = false
        for (index in 0 until REFERENCE_TIMESTAMP_CAPACITY) {
            val candidate = referenceTimestamps.get(index)
            if (candidate <= 0L) continue
            if (candidate == timestampNs) return candidate
            if (absoluteDifference(candidate, timestampNs) <= toleranceNs) {
                if (matchedTimestampNs != null && matchedTimestampNs != candidate) ambiguous = true
                matchedTimestampNs = candidate
            }
        }
        return if (ambiguous) null else matchedTimestampNs
    }

    private fun SensorTimestampClockDomain.validReferenceHint(): SensorTimestampClockDomain =
        if (isConcrete()) this else SensorTimestampClockDomain.UNKNOWN

    private fun SensorTimestampClockDomain.isConcrete(): Boolean =
        this == SensorTimestampClockDomain.BOOTTIME ||
            this == SensorTimestampClockDomain.MONOTONIC

    private fun absoluteDifference(first: Long, second: Long): Long {
        if (first == second) return 0L
        val larger = maxOf(first, second)
        val smaller = minOf(first, second)
        return if (smaller < 0L && larger > Long.MAX_VALUE + smaller) {
            Long.MAX_VALUE
        } else {
            larger - smaller
        }
    }

    private fun saturatingSubtract(left: Long, right: Long): Long = when {
        right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
        right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
        else -> left - right
    }

    private companion object {
        // Metadata only (2 KiB), not additional Images or pixel buffers. Keep identities longer
        // than the 18-image P010 and 55-image hardware-import queues during bounded backpressure.
        const val REFERENCE_TIMESTAMP_CAPACITY = 256
        const val REFERENCE_SNAP_TOLERANCE_NS = 2_000_000L
    }
}
