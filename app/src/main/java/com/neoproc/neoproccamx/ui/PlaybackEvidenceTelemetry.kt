@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Playback route represented by one evidence session. */
internal enum class PlaybackEvidencePath {
    ENCODED_MASTER,
    NIVUKX_ENCODED_MASTER,
    PRIVATE_NPV10_MASTER,
    PRIVATE_NPRAW_MASTER
}

/** Declares exactly what [PlaybackEvidenceSnapshot.sourceSha256] authenticates. */
internal enum class PlaybackSourceDigestKind {
    CONTENT_BYTES_SHA256,
    URI_IDENTITY_SHA256
}

/**
 * Immutable, machine-readable state for one playback lifetime.
 *
 * `presentationCallbacks` means frames delivered to the final presentation boundary: Media3's
 * VideoFrameMetadataListener for encoded media and ImageView publication for private masters.
 * Decoder counters remain separate so a device oracle never mistakes a callback for proof that
 * SurfaceFlinger displayed a frame.
 */
internal data class PlaybackEvidenceSnapshot(
    val schemaVersion: Int,
    val sessionId: Long,
    val path: PlaybackEvidencePath,
    val sourceSha256: String,
    val sourceDigestKind: PlaybackSourceDigestKind,
    val startedNs: Long,
    val finishedNs: Long,
    val finishRequested: Boolean,
    val finalized: Boolean,
    val finishReason: String,
    val width: Int,
    val height: Int,
    val nominalFpsMilli: Int,
    val sampleMimeType: String,
    val colorSpace: Int,
    val colorTransfer: Int,
    val colorRange: Int,
    val prerollPresentationCallbacks: Long,
    val presentationCallbacks: Long,
    val decodedFrames: Long,
    val decoderRenderedFrames: Long,
    val decoderDroppedFrames: Long,
    val decoderSkippedFrames: Long,
    val droppedFrameEvents: Long,
    val schedulerSkippedFrames: Long,
    val outputDroppedFrames: Long,
    val uiConflatedFrames: Long,
    val repeatedPtsFrames: Long,
    val ptsRegressionCount: Long,
    val discontinuityCount: Long,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val ptsProgressUs: Long,
    val firstPresentationNs: Long,
    val lastPresentationNs: Long,
    val presentationSpanNs: Long,
    val minPresentationIntervalNs: Long,
    val maxPresentationIntervalNs: Long,
    val measuredPresentationFpsMilli: Long,
    val frameProcessingOffsetCount: Long,
    val totalFrameProcessingOffsetUs: Long,
    val bufferingCount: Long,
    val bufferingDurationMs: Long,
    val decoderName: String,
    val decoderInitCount: Long,
    val decoderReleaseCount: Long,
    val decoderErrorCount: Long,
    val playerErrorCount: Long,
    val lastErrorClass: String,
    val resourcesAcquired: Long,
    val resourcesReleased: Long,
    val activeResourceCount: Int,
    val activeResourceNames: String,
    val unexpectedResourceReleaseCount: Long
) {
    val effectiveDroppedFrames: Long
        get() = maxOf(decoderDroppedFrames, droppedFrameEvents) +
            decoderSkippedFrames + schedulerSkippedFrames + outputDroppedFrames + uiConflatedFrames

    val resourceReleaseComplete: Boolean
        get() = finalized && activeResourceCount == 0 &&
            unexpectedResourceReleaseCount == 0L && resourcesAcquired == resourcesReleased &&
            decoderReleaseCount >= decoderInitCount

    /** Stable single-line format designed for adb logcat capture and strict key/value parsing. */
    fun toEvidenceLine(): String = buildString(768) {
        append("schema=").append(schemaVersion)
        append(" session=").append(sessionId)
        append(" path=").append(path.name)
        append(" sourceSha256=").append(sourceSha256)
        append(" sourceDigestKind=").append(sourceDigestKind.name)
        append(" finalized=").append(finalized)
        append(" finishRequested=").append(finishRequested)
        append(" finishReason=").append(evidenceToken(finishReason))
        append(" width=").append(width)
        append(" height=").append(height)
        append(" nominalFpsMilli=").append(nominalFpsMilli)
        append(" mime=").append(evidenceToken(sampleMimeType))
        append(" colorSpace=").append(colorSpace)
        append(" colorTransfer=").append(colorTransfer)
        append(" colorRange=").append(colorRange)
        append(" prerollCallbacks=").append(prerollPresentationCallbacks)
        append(" presentationCallbacks=").append(presentationCallbacks)
        append(" decodedFrames=").append(decodedFrames)
        append(" decoderRendered=").append(decoderRenderedFrames)
        append(" decoderDropped=").append(decoderDroppedFrames)
        append(" decoderSkipped=").append(decoderSkippedFrames)
        append(" droppedEvents=").append(droppedFrameEvents)
        append(" schedulerSkipped=").append(schedulerSkippedFrames)
        append(" outputDropped=").append(outputDroppedFrames)
        append(" uiConflated=").append(uiConflatedFrames)
        append(" repeatedPts=").append(repeatedPtsFrames)
        append(" ptsRegressions=").append(ptsRegressionCount)
        append(" discontinuities=").append(discontinuityCount)
        append(" firstPtsUs=").append(firstPtsUs)
        append(" lastPtsUs=").append(lastPtsUs)
        append(" ptsProgressUs=").append(ptsProgressUs)
        append(" presentationSpanNs=").append(presentationSpanNs)
        append(" minIntervalNs=").append(minPresentationIntervalNs)
        append(" maxIntervalNs=").append(maxPresentationIntervalNs)
        append(" measuredFpsMilli=").append(measuredPresentationFpsMilli)
        append(" processingFrames=").append(frameProcessingOffsetCount)
        append(" processingOffsetUs=").append(totalFrameProcessingOffsetUs)
        append(" bufferingCount=").append(bufferingCount)
        append(" bufferingMs=").append(bufferingDurationMs)
        append(" decoderName=").append(evidenceToken(decoderName))
        append(" decoderInit=").append(decoderInitCount)
        append(" decoderRelease=").append(decoderReleaseCount)
        append(" decoderErrors=").append(decoderErrorCount)
        append(" playerErrors=").append(playerErrorCount)
        append(" lastError=").append(evidenceToken(lastErrorClass))
        append(" resourcesAcquired=").append(resourcesAcquired)
        append(" resourcesReleased=").append(resourcesReleased)
        append(" activeResources=").append(activeResourceCount)
        append(" activeResourceNames=").append(evidenceToken(activeResourceNames))
        append(" unexpectedReleases=").append(unexpectedResourceReleaseCount)
        append(" releaseComplete=").append(resourceReleaseComplete)
    }

    companion object {
        private fun evidenceToken(value: String): String = value
            .ifBlank { "none" }
            .replace(Regex("[^A-Za-z0-9_.+:-]"), "_")
            .take(160)
    }
}

/**
 * Thread-safe, allocation-stable accumulator. Per-frame paths mutate primitives under one short lock;
 * snapshots and evidence strings are allocated only when a test or lifecycle boundary requests them.
 */
internal class PlaybackEvidenceSession(
    val sessionId: Long,
    private val path: PlaybackEvidencePath,
    private val sourceSha256: String,
    private val sourceDigestKind: PlaybackSourceDigestKind = PlaybackSourceDigestKind.CONTENT_BYTES_SHA256,
    private val clockNs: () -> Long = System::nanoTime,
    private val onFinalized: (PlaybackEvidenceSnapshot) -> Unit = {}
) {
    private val lock = Any()
    private val activeResources = linkedMapOf<String, Int>()
    private val startedNs = clockNs()

    private var finishedNs = 0L
    private var finishRequested = false
    private var finalized = false
    private var finishReason = ""

    private var width = 0
    private var height = 0
    private var nominalFpsMilli = 0
    private var sampleMimeType = ""
    private var colorSpace = Format.NO_VALUE
    private var colorTransfer = Format.NO_VALUE
    private var colorRange = Format.NO_VALUE

    private var prerollPresentationCallbacks = 0L
    private var presentationCallbacks = 0L
    private var decodedFrames = 0L
    private var decoderRenderedFrames = 0L
    private var decoderDroppedFrames = 0L
    private var decoderSkippedFrames = 0L
    private var droppedFrameEvents = 0L
    private var schedulerSkippedFrames = 0L
    private var outputDroppedFrames = 0L
    private var uiConflatedFrames = 0L
    private var repeatedPtsFrames = 0L
    private var ptsRegressionCount = 0L
    private var pendingPtsRegressionCount = 0L
    private var discontinuityCount = 0L
    private var firstPtsUs = UNSET
    private var lastPtsUs = UNSET
    private var ptsEpochStartUs = UNSET
    private var ptsProgressUs = 0L
    private var firstPresentationNs = UNSET
    private var lastPresentationNs = UNSET
    private var minPresentationIntervalNs = Long.MAX_VALUE
    private var maxPresentationIntervalNs = 0L
    private var presentationIntervalCount = 0L
    private var totalPresentationIntervalNs = 0L
    private var lastScheduledFrame = -1

    private var frameProcessingOffsetCount = 0L
    private var totalFrameProcessingOffsetUs = 0L
    private var bufferingCount = 0L
    private var bufferingStartedNs = UNSET
    private var bufferingDurationNs = 0L
    private var decoderName = ""
    private var decoderInitEvents = 0L
    private var decoderReleaseEvents = 0L
    private var decoderCounterInitCount = 0L
    private var decoderCounterReleaseCount = 0L
    private var decoderErrorCount = 0L
    private var playerErrorCount = 0L
    private var lastErrorClass = ""
    private var resourcesAcquired = 0L
    private var resourcesReleased = 0L
    private var unexpectedResourceReleaseCount = 0L

    fun recordFormat(
        width: Int,
        height: Int,
        nominalFps: Float,
        sampleMimeType: String?,
        colorSpace: Int = Format.NO_VALUE,
        colorTransfer: Int = Format.NO_VALUE,
        colorRange: Int = Format.NO_VALUE
    ) = synchronized(lock) {
        if (finalized) return@synchronized
        if (width > 0) this.width = width
        if (height > 0) this.height = height
        if (nominalFps.isFinite() && nominalFps > 0f) {
            nominalFpsMilli = (nominalFps * 1_000f).toInt().coerceAtLeast(1)
        }
        if (!sampleMimeType.isNullOrBlank()) this.sampleMimeType = sampleMimeType
        if (colorSpace != Format.NO_VALUE) this.colorSpace = colorSpace
        if (colorTransfer != Format.NO_VALUE) this.colorTransfer = colorTransfer
        if (colorRange != Format.NO_VALUE) this.colorRange = colorRange
    }

    fun recordPresentation(ptsUs: Long, presentationNs: Long) = synchronized(lock) {
        if (finalized) return@synchronized
        // Media3 may publish more than one wrapped frame before its repeat-discontinuity callback.
        // Keep every regression provisional until a declared boundary arrives. A real, unexplained
        // regression remains a hard failure in every live snapshot and is committed at finalization.
        val safePresentationNs = presentationNs.coerceAtLeast(0L)
        presentationCallbacks++
        if (firstPtsUs == UNSET) firstPtsUs = ptsUs
        if (ptsEpochStartUs == UNSET) ptsEpochStartUs = ptsUs
        var crossesProvisionalDiscontinuity = false
        if (lastPtsUs != UNSET) {
            when {
                ptsUs == lastPtsUs -> repeatedPtsFrames++
                ptsUs < lastPtsUs -> {
                    pendingPtsRegressionCount = saturatedAdd(pendingPtsRegressionCount, 1L)
                    crossesProvisionalDiscontinuity = true
                }
                else -> ptsProgressUs = saturatedAdd(
                    ptsProgressUs,
                    (ptsUs - maxOf(lastPtsUs, ptsEpochStartUs)).coerceAtLeast(0L)
                )
            }
        }
        lastPtsUs = ptsUs

        if (firstPresentationNs == UNSET) firstPresentationNs = safePresentationNs
        if (!crossesProvisionalDiscontinuity &&
            lastPresentationNs != UNSET && safePresentationNs > lastPresentationNs
        ) {
            val interval = safePresentationNs - lastPresentationNs
            minPresentationIntervalNs = minOf(minPresentationIntervalNs, interval)
            maxPresentationIntervalNs = maxOf(maxPresentationIntervalNs, interval)
            presentationIntervalCount++
            totalPresentationIntervalNs = saturatedAdd(totalPresentationIntervalNs, interval)
        }
        lastPresentationNs = maxOf(lastPresentationNs, safePresentationNs)
    }

    /** Frames decoded while Media3 is still prerolling are not visible playback cadence. */
    fun recordPrerollPresentation() = synchronized(lock) {
        if (!finalized) prerollPresentationCallbacks = saturatedAdd(prerollPresentationCallbacks, 1L)
    }

    /** Reset PTS comparison at an intentional seek, repeat boundary, or source discontinuity. */
    fun recordDiscontinuity() = synchronized(lock) {
        if (finalized) return@synchronized
        discontinuityCount++
        pendingPtsRegressionCount = 0L
        lastPtsUs = UNSET
        ptsEpochStartUs = UNSET
        lastPresentationNs = UNSET
        lastScheduledFrame = -1
    }

    fun recordPrivateFrameScheduled(frameIndex: Int) = synchronized(lock) {
        if (finalized || frameIndex < 0) return@synchronized
        if (lastScheduledFrame >= 0 && frameIndex > lastScheduledFrame + 1) {
            schedulerSkippedFrames = saturatedAdd(
                schedulerSkippedFrames,
                (frameIndex - lastScheduledFrame - 1).toLong()
            )
        }
        lastScheduledFrame = frameIndex
    }

    fun recordDecodedFrame() = synchronized(lock) {
        if (!finalized) decodedFrames = saturatedAdd(decodedFrames, 1L)
    }

    fun recordSchedulerSkip(count: Long) = synchronized(lock) {
        if (!finalized && count > 0L) schedulerSkippedFrames = saturatedAdd(schedulerSkippedFrames, count)
    }

    fun recordOutputDrop(count: Long = 1L) = synchronized(lock) {
        if (!finalized && count > 0L) outputDroppedFrames = saturatedAdd(outputDroppedFrames, count)
    }

    fun recordUiConflation(count: Long = 1L) = synchronized(lock) {
        if (!finalized && count > 0L) uiConflatedFrames = saturatedAdd(uiConflatedFrames, count)
    }

    fun recordDroppedFrameEvent(count: Int) = synchronized(lock) {
        if (!finalized && count > 0) droppedFrameEvents = saturatedAdd(droppedFrameEvents, count.toLong())
    }

    fun recordFrameProcessingOffset(totalOffsetUs: Long, frameCount: Int) = synchronized(lock) {
        if (finalized || frameCount <= 0) return@synchronized
        frameProcessingOffsetCount = saturatedAdd(frameProcessingOffsetCount, frameCount.toLong())
        totalFrameProcessingOffsetUs = saturatedAdd(totalFrameProcessingOffsetUs, totalOffsetUs)
    }

    fun recordDecoderCounters(counters: DecoderCounters?) {
        if (counters == null) return
        counters.ensureUpdated()
        val completed = synchronized(lock) {
            if (finalized) return@synchronized null
            decoderRenderedFrames = maxOf(decoderRenderedFrames, counters.renderedOutputBufferCount.toLong())
            decoderDroppedFrames = maxOf(
                decoderDroppedFrames,
                counters.droppedBufferCount.toLong() + counters.droppedInputBufferCount.toLong()
            )
            decoderSkippedFrames = maxOf(
                decoderSkippedFrames,
                counters.skippedInputBufferCount.toLong() + counters.skippedOutputBufferCount.toLong()
            )
            frameProcessingOffsetCount = maxOf(
                frameProcessingOffsetCount,
                counters.videoFrameProcessingOffsetCount.toLong()
            )
            if (counters.videoFrameProcessingOffsetCount > 0) {
                totalFrameProcessingOffsetUs = counters.totalVideoFrameProcessingOffsetUs
            }
            decoderCounterInitCount = maxOf(decoderCounterInitCount, counters.decoderInitCount.toLong())
            decoderCounterReleaseCount = maxOf(
                decoderCounterReleaseCount,
                counters.decoderReleaseCount.toLong()
            )
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    /**
     * Records decoder ownership after presentation has been intentionally stopped. Release may update
     * Media3's mutable counters, but teardown-discarded output must not be relabelled as a playback drop.
     */
    fun recordDecoderLifecycleCounters(counters: DecoderCounters?) {
        if (counters == null) return
        counters.ensureUpdated()
        val completed = synchronized(lock) {
            if (finalized) return@synchronized null
            decoderCounterInitCount = maxOf(decoderCounterInitCount, counters.decoderInitCount.toLong())
            decoderCounterReleaseCount = maxOf(
                decoderCounterReleaseCount,
                counters.decoderReleaseCount.toLong()
            )
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    fun recordDecoderInitialized(decoderName: String) {
        val completed = synchronized(lock) {
            if (finalized) return@synchronized null
            this.decoderName = decoderName
            decoderInitEvents++
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    fun recordDecoderReleased(decoderName: String) {
        val completed = synchronized(lock) {
            if (finalized) return@synchronized null
            if (this.decoderName.isBlank()) this.decoderName = decoderName
            decoderReleaseEvents++
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    fun recordDecoderError(error: Throwable) = synchronized(lock) {
        if (finalized) return@synchronized
        decoderErrorCount++
        lastErrorClass = error.javaClass.name
    }

    fun recordPlayerError(error: Throwable) = synchronized(lock) {
        if (finalized) return@synchronized
        playerErrorCount++
        lastErrorClass = error.javaClass.name
    }

    fun recordPlaybackState(state: Int, eventNs: Long = clockNs()) = synchronized(lock) {
        if (finalized) return@synchronized
        if (state == Player.STATE_BUFFERING) {
            if (bufferingStartedNs == UNSET) {
                bufferingCount++
                bufferingStartedNs = eventNs
            }
        } else if (bufferingStartedNs != UNSET) {
            bufferingDurationNs = saturatedAdd(
                bufferingDurationNs,
                (eventNs - bufferingStartedNs).coerceAtLeast(0L)
            )
            bufferingStartedNs = UNSET
        }
    }

    fun acquireResource(resource: String) = synchronized(lock) {
        if (!finalized) acquireResourceLocked(resource)
    }

    fun releaseResource(resource: String) {
        val completed = synchronized(lock) {
            if (finalized) return
            releaseResourceLocked(resource)
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    fun requestFinish(reason: String) {
        val completed = synchronized(lock) {
            if (finalized) return
            finishRequested = true
            finishReason = reason
            closeOpenBufferingIntervalLocked()
            maybeFinalizeLocked()
        }
        if (completed != null) onFinalized(completed)
    }

    fun snapshot(): PlaybackEvidenceSnapshot = synchronized(lock) { snapshotLocked() }

    private fun acquireResourceLocked(resource: String) {
        val token = resource.ifBlank { "unnamed" }
        activeResources[token] = (activeResources[token] ?: 0) + 1
        resourcesAcquired++
    }

    private fun releaseResourceLocked(resource: String) {
        val token = resource.ifBlank { "unnamed" }
        val count = activeResources[token] ?: 0
        if (count <= 0) {
            unexpectedResourceReleaseCount++
            return
        }
        if (count == 1) activeResources.remove(token) else activeResources[token] = count - 1
        resourcesReleased++
    }

    private fun maybeFinalizeLocked(): PlaybackEvidenceSnapshot? {
        if (!finishRequested || finalized || activeResources.isNotEmpty()) return null
        val initializedDecoders = maxOf(decoderInitEvents, decoderCounterInitCount)
        val releasedDecoders = maxOf(decoderReleaseEvents, decoderCounterReleaseCount)
        val encodedFramesObserved = path == PlaybackEvidencePath.ENCODED_MASTER ||
            path == PlaybackEvidencePath.NIVUKX_ENCODED_MASTER
        if (encodedFramesObserved && presentationCallbacks > 0L && initializedDecoders == 0L) return null
        if (releasedDecoders < initializedDecoders) return null
        if (pendingPtsRegressionCount > 0L) {
            ptsRegressionCount = saturatedAdd(ptsRegressionCount, pendingPtsRegressionCount)
            pendingPtsRegressionCount = 0L
        }
        closeOpenBufferingIntervalLocked()
        finalized = true
        finishedNs = clockNs()
        return snapshotLocked()
    }

    private fun closeOpenBufferingIntervalLocked() {
        if (bufferingStartedNs == UNSET) return
        val nowNs = clockNs()
        bufferingDurationNs = saturatedAdd(
            bufferingDurationNs,
            (nowNs - bufferingStartedNs).coerceAtLeast(0L)
        )
        bufferingStartedNs = UNSET
    }

    private fun snapshotLocked(): PlaybackEvidenceSnapshot {
        val activeCount = activeResources.values.sum()
        val activeNames = activeResources.entries
            .sortedBy { it.key }
            .joinToString(separator = ",") { (name, count) ->
                if (count == 1) name else "$name*$count"
            }
        val presentationSpanNs = totalPresentationIntervalNs
        val measuredFpsMilli = if (presentationIntervalCount > 0L && presentationSpanNs > 0L) {
            ((presentationIntervalCount.toDouble() * 1_000_000_000_000.0) /
                presentationSpanNs.toDouble()).toLong().coerceAtLeast(0L)
        } else {
            0L
        }
        return PlaybackEvidenceSnapshot(
            schemaVersion = SCHEMA_VERSION,
            sessionId = sessionId,
            path = path,
            sourceSha256 = sourceSha256,
            sourceDigestKind = sourceDigestKind,
            startedNs = startedNs,
            finishedNs = finishedNs,
            finishRequested = finishRequested,
            finalized = finalized,
            finishReason = finishReason,
            width = width,
            height = height,
            nominalFpsMilli = nominalFpsMilli,
            sampleMimeType = sampleMimeType,
            colorSpace = colorSpace,
            colorTransfer = colorTransfer,
            colorRange = colorRange,
            prerollPresentationCallbacks = prerollPresentationCallbacks,
            presentationCallbacks = presentationCallbacks,
            decodedFrames = decodedFrames,
            decoderRenderedFrames = decoderRenderedFrames,
            decoderDroppedFrames = decoderDroppedFrames,
            decoderSkippedFrames = decoderSkippedFrames,
            droppedFrameEvents = droppedFrameEvents,
            schedulerSkippedFrames = schedulerSkippedFrames,
            outputDroppedFrames = outputDroppedFrames,
            uiConflatedFrames = uiConflatedFrames,
            repeatedPtsFrames = repeatedPtsFrames,
            ptsRegressionCount = saturatedAdd(
                ptsRegressionCount,
                pendingPtsRegressionCount
            ),
            discontinuityCount = discontinuityCount,
            firstPtsUs = firstPtsUs.takeUnless { it == UNSET } ?: 0L,
            lastPtsUs = lastPtsUs.takeUnless { it == UNSET } ?: 0L,
            ptsProgressUs = ptsProgressUs,
            firstPresentationNs = firstPresentationNs.takeUnless { it == UNSET } ?: 0L,
            lastPresentationNs = lastPresentationNs.takeUnless { it == UNSET } ?: 0L,
            presentationSpanNs = presentationSpanNs,
            minPresentationIntervalNs = minPresentationIntervalNs.takeUnless { it == Long.MAX_VALUE } ?: 0L,
            maxPresentationIntervalNs = maxPresentationIntervalNs,
            measuredPresentationFpsMilli = measuredFpsMilli,
            frameProcessingOffsetCount = frameProcessingOffsetCount,
            totalFrameProcessingOffsetUs = totalFrameProcessingOffsetUs,
            bufferingCount = bufferingCount,
            bufferingDurationMs = bufferingDurationNs / 1_000_000L,
            decoderName = decoderName,
            decoderInitCount = maxOf(decoderInitEvents, decoderCounterInitCount),
            decoderReleaseCount = maxOf(decoderReleaseEvents, decoderCounterReleaseCount),
            decoderErrorCount = decoderErrorCount,
            playerErrorCount = playerErrorCount,
            lastErrorClass = lastErrorClass,
            resourcesAcquired = resourcesAcquired,
            resourcesReleased = resourcesReleased,
            activeResourceCount = activeCount,
            activeResourceNames = activeNames,
            unexpectedResourceReleaseCount = unexpectedResourceReleaseCount
        )
    }

    companion object {
        private const val SCHEMA_VERSION = 3
        private const val UNSET = Long.MIN_VALUE

        private fun saturatedAdd(left: Long, right: Long): Long = when {
            right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
            right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
            else -> left + right
        }
    }
}

/** Bounded process-local oracle plus a stable logcat record for physical checkpoint collection. */
internal object PlaybackEvidenceRegistry {
    private const val TAG = "NeoPlaybackOracle"
    private const val MAX_COMPLETED_SESSIONS = 16
    private val nextSessionId = AtomicLong(1L)
    private val active = ConcurrentHashMap<Long, PlaybackEvidenceSession>()
    private val completedLock = Any()
    private val completed = ArrayDeque<PlaybackEvidenceSnapshot>()

    fun start(
        path: PlaybackEvidencePath,
        sourceIdentity: String,
        sourceContentSha256: String? = null
    ): PlaybackEvidenceSession {
        val canonicalContentSha256 = sourceContentSha256?.trim()?.lowercase(Locale.ROOT)
        require(canonicalContentSha256 == null || isCanonicalSha256(canonicalContentSha256)) {
            "Playback source content SHA-256 must contain exactly 64 lowercase hexadecimal characters"
        }
        val sourceDigestKind = if (canonicalContentSha256 != null) {
            PlaybackSourceDigestKind.CONTENT_BYTES_SHA256
        } else {
            PlaybackSourceDigestKind.URI_IDENTITY_SHA256
        }
        val sourceSha256 = canonicalContentSha256 ?: sha256(sourceIdentity)
        val sessionId = nextSessionId.getAndIncrement()
        val session = PlaybackEvidenceSession(
            sessionId = sessionId,
            path = path,
            sourceSha256 = sourceSha256,
            sourceDigestKind = sourceDigestKind,
            onFinalized = { snapshot ->
                active.remove(snapshot.sessionId)
                synchronized(completedLock) {
                    while (completed.size >= MAX_COMPLETED_SESSIONS) completed.removeFirst()
                    completed.addLast(snapshot)
                }
                Log.i(TAG, "NEO_PLAYBACK_EVIDENCE ${snapshot.toEvidenceLine()}")
            }
        )
        active[sessionId] = session
        Log.i(
            TAG,
            "NEO_PLAYBACK_START schema=2 session=$sessionId path=${path.name} " +
                "sourceSha256=$sourceSha256 sourceDigestKind=${sourceDigestKind.name}"
        )
        return session
    }

    fun activeSnapshots(): List<PlaybackEvidenceSnapshot> =
        active.values.map(PlaybackEvidenceSession::snapshot).sortedBy { it.sessionId }

    fun completedSnapshots(): List<PlaybackEvidenceSnapshot> =
        synchronized(completedLock) { completed.toList() }

    internal fun clearForTesting() {
        check(active.isEmpty()) { "Cannot clear playback evidence while sessions are active" }
        synchronized(completedLock) { completed.clear() }
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF) }

    fun isCanonicalSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Media3 bridge. It observes the decoder and frame-release boundary without changing player policy. */
internal class EncodedPlaybackEvidenceBridge(
    path: PlaybackEvidencePath,
    sourceIdentity: String,
    sourceContentSha256: String? = null
) : AnalyticsListener, VideoFrameMetadataListener {
    val session: PlaybackEvidenceSession = PlaybackEvidenceRegistry.start(
        path = path,
        sourceIdentity = sourceIdentity,
        sourceContentSha256 = sourceContentSha256
    )
    private var attached = false
    private var outputAttached = false
    private var releaseFenceScheduled = false
    private var applicationHandler: Handler? = null
    @Volatile private var playbackCadenceSealed = false
    @Volatile private var playbackCadenceActive = false
    @Volatile private var lastVideoFormat: Format? = null

    fun attach(player: ExoPlayer) {
        check(!attached) { "Playback evidence bridge already attached" }
        attached = true
        applicationHandler = Handler(player.applicationLooper)
        session.acquireResource(RESOURCE_PLAYER)
        session.acquireResource(RESOURCE_RELEASE_FENCE)
        player.addAnalyticsListener(this)
        player.setVideoFrameMetadataListener(this)
    }

    fun markOutputAttached() {
        if (outputAttached) return
        outputAttached = true
        session.acquireResource(RESOURCE_OUTPUT)
    }

    fun markOutputDetached() {
        if (!outputAttached) return
        outputAttached = false
        session.releaseResource(RESOURCE_OUTPUT)
    }

    fun markCadenceBoundary() {
        if (playbackCadenceActive) {
            playbackCadenceActive = false
            session.recordDiscontinuity()
        }
    }

    /** Freeze visible-playback counters before the Activity intentionally detaches its output. */
    fun beginPlaybackTeardown(counters: DecoderCounters?) {
        if (playbackCadenceSealed) return
        playbackCadenceSealed = true
        playbackCadenceActive = false
        session.recordDecoderCounters(counters)
    }

    /** Call after PlayerView detachment and ExoPlayer.release(). */
    fun finishAfterPlayerRelease(reason: String, finalCounters: DecoderCounters?) {
        session.recordDecoderLifecycleCounters(finalCounters)
        if (attached) {
            attached = false
            session.releaseResource(RESOURCE_PLAYER)
        }
        session.requestFinish(reason)
        if (releaseFenceScheduled) return
        releaseFenceScheduled = true
        val releaseFence = Runnable {
            runCatching { session.recordDecoderLifecycleCounters(finalCounters) }
                .onFailure(session::recordPlayerError)
            session.releaseResource(RESOURCE_RELEASE_FENCE)
        }
        val posted = applicationHandler?.post(releaseFence) == true
        if (!posted) {
            session.recordPlayerError(
                IllegalStateException("Unable to enqueue Media3 release-evidence fence")
            )
            releaseFence.run()
        }
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    ) {
        if (playbackCadenceSealed) return
        if (lastVideoFormat !== format) recordFormat(format)
        if (playbackCadenceActive) {
            session.recordPresentation(presentationTimeUs, releaseTimeNs)
        } else {
            session.recordPrerollPresentation()
        }
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        if (playbackCadenceSealed || playbackCadenceActive == isPlaying) return
        if (!isPlaying) session.recordDiscontinuity()
        playbackCadenceActive = isPlaying
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        session.recordPlaybackState(state, eventTime.realtimeMs * 1_000_000L)
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        session.recordDiscontinuity()
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        session.recordDecoderInitialized(decoderName)
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        session.recordDecoderReleased(decoderName)
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
    ) {
        recordFormat(format)
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        if (!playbackCadenceSealed) session.recordDroppedFrameEvent(droppedFrames)
    }

    override fun onVideoFrameProcessingOffset(
        eventTime: AnalyticsListener.EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int
    ) {
        if (!playbackCadenceSealed) {
            session.recordFrameProcessingOffset(totalProcessingOffsetUs, frameCount)
        }
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters
    ) {
        if (playbackCadenceSealed) {
            session.recordDecoderLifecycleCounters(decoderCounters)
        } else {
            session.recordDecoderCounters(decoderCounters)
        }
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        session.recordDecoderError(videoCodecError)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException
    ) {
        session.recordPlayerError(error)
    }

    private fun recordFormat(format: Format) {
        lastVideoFormat = format
        val colorInfo = format.colorInfo
        session.recordFormat(
            width = format.width,
            height = format.height,
            nominalFps = format.frameRate,
            sampleMimeType = format.sampleMimeType,
            colorSpace = colorInfo?.colorSpace ?: Format.NO_VALUE,
            colorTransfer = colorInfo?.colorTransfer ?: Format.NO_VALUE,
            colorRange = colorInfo?.colorRange ?: Format.NO_VALUE
        )
    }

    companion object {
        private const val RESOURCE_PLAYER = "media3-player"
        private const val RESOURCE_OUTPUT = "media3-output"
        private const val RESOURCE_RELEASE_FENCE = "media3-release-fence"
    }
}
