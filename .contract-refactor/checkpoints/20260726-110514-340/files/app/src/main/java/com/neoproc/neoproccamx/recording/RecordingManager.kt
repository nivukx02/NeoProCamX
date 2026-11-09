package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.pipeline.VideoDetailSharpeningPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * RecordingManager manages GPU-backed video capture through `MediaCodec` and a
 * recording container backend.
 *
 * Call `prepare()` to create the codecs and receive the encoder input `Surface`.
 * The caller attaches that surface to the active renderer or frame pipeline, then
 * calls `start()` once frames are flowing. `stop()` drains the codecs, finalizes
 * the active container, and returns the saved output path when available.
 *
 * `prepare()`, `start()`, and `stop()` are safe to call from background threads.
 * Video draining is handled by MediaCodec, audio encoding runs on a dedicated IO
 * coroutine, and observable state is exposed through `StateFlow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "RecordingManager"
        private const val RUNTIME_BITRATE_HINT_INTERVAL_MS = 4_000L
        private const val SYNC_FRAME_REQUEST_INTERVAL_MS = 12_000L
        private const val SYNC_FRAME_BITRATE_DELTA_RATIO = 0.35f
        private const val QUALITY_EVIDENCE_CADENCE_TOLERANCE_FRAMES = 2L
        private const val MICROSECONDS_PER_SECOND = 1_000_000L
        private const val P010_TRANSIENT_STALL_BUDGET_US = 1_100_000L
        private const val P010_MIN_IN_FLIGHT_IMAGES = 2
        private const val P010_MAX_IN_FLIGHT_IMAGES = 32
        private const val P010_IMAGE_INPUT_DRAIN_TIMEOUT_MS = 2_000L

        // Preview protection: the P010 readout ImageReader shares its camera buffer pool
        // (~12-16 buffers) with the preview leg of ONE camera repeating request. Holding a buffer
        // through the slow (~331ms) readout copy must never drain that pool, or the camera stalls
        // and PREVIEW freezes too. Cap admitted in-flight readout images safely below the smallest
        // pool so >=4 buffers always remain free for preview; excess frames are rejected and their
        // camera buffer is freed in <1ms. (This keeps preview smooth; it does NOT raise the
        // copy-bound recording fps — that is limited by the per-frame readout copy.)
        private const val P010_PREVIEW_SAFE_MAX_IN_FLIGHT = 8

        internal fun resolveP010ImageInFlightCapacity(fps: Int): Int {
            val safeFps = fps.coerceIn(1, 240).toLong()
            val framesDuringTransientStall =
                (P010_TRANSIENT_STALL_BUDGET_US * safeFps +
                    MICROSECONDS_PER_SECOND - 1L) / MICROSECONDS_PER_SECOND
            return framesDuringTransientStall.toInt().coerceIn(
                P010_MIN_IN_FLIGHT_IMAGES,
                P010_MAX_IN_FLIGHT_IMAGES
            )
        }

        internal fun shouldUseP010LinearBlockAdmission(
            sdkInt: Int,
            p010Input: Boolean,
            codecCopyFreeCompatible: Boolean,
            codecName: String
        ): Boolean =
            sdkInt >= Build.VERSION_CODES.R &&
                p010Input &&
                codecCopyFreeCompatible &&
                !isKnownP010LinearBlockIncompatible(codecName)

        private fun isKnownP010LinearBlockIncompatible(codecName: String): Boolean {
            val normalized = codecName.trim().lowercase(Locale.US)
            return normalized == "c2.qti.hevc.encoder" ||
                normalized.startsWith("c2.qti.hevc.encoder.")
        }

        internal fun activeRecordingDurationUsForEvidence(
            recordingStartNs: Long,
            stoppedAtNs: Long,
            accumulatedPausedUs: Long
        ): Long {
            val elapsedUs = (stoppedAtNs - recordingStartNs).coerceAtLeast(0L) / 1_000L
            return (elapsedUs - accumulatedPausedUs.coerceAtLeast(0L)).coerceAtLeast(0L)
        }

        internal fun clipTimingDurationUsForEvidence(
            recordingStartNs: Long,
            clipTimingStartNs: Long,
            stoppedAtNs: Long,
            accumulatedPausedUs: Long,
            accumulatedPausedAtClipTimingStartUs: Long
        ): Long {
            val useClipTimingStart =
                clipTimingStartNs > recordingStartNs && clipTimingStartNs <= stoppedAtNs
            val evidenceStartNs = if (useClipTimingStart) clipTimingStartNs else recordingStartNs
            val pausedUsForWindow =
                if (useClipTimingStart) {
                    (accumulatedPausedUs - accumulatedPausedAtClipTimingStartUs).coerceAtLeast(0L)
                } else {
                    accumulatedPausedUs.coerceAtLeast(0L)
                }
            return activeRecordingDurationUsForEvidence(
                recordingStartNs = evidenceStartNs,
                stoppedAtNs = stoppedAtNs,
                accumulatedPausedUs = pausedUsForWindow
            )
        }

        internal fun expectedFrameCountForEvidence(
            activeDurationUs: Long,
            fps: Int,
            cadenceToleranceFrames: Long = QUALITY_EVIDENCE_CADENCE_TOLERANCE_FRAMES
        ): Long {
            val safeDurationUs = activeDurationUs.coerceAtLeast(0L)
            val safeFps = fps.coerceAtLeast(1).toLong()
            val wholeSeconds = safeDurationUs / MICROSECONDS_PER_SECOND
            val remainderUs = safeDurationUs % MICROSECONDS_PER_SECOND
            val nominalFrameCount =
                wholeSeconds * safeFps + remainderUs * safeFps / MICROSECONDS_PER_SECOND
            return (nominalFrameCount - cadenceToleranceFrames.coerceAtLeast(0L))
                .coerceAtLeast(1L)
        }

        internal fun encodedVideoTimelineDurationUsForEvidence(
            firstVideoPtsUs: Long,
            lastVideoPtsUs: Long,
            encodedVideoSampleCount: Long,
            fps: Int
        ): Long {
            if (encodedVideoSampleCount <= 0L) return 0L
            val frameDurationUs = MICROSECONDS_PER_SECOND / fps.coerceAtLeast(1)
            if (firstVideoPtsUs < 0L || lastVideoPtsUs < firstVideoPtsUs) {
                return frameDurationUs
            }
            return (lastVideoPtsUs - firstVideoPtsUs + frameDurationUs)
                .coerceAtLeast(frameDurationUs)
        }

        internal fun p010DroppedFramesForEvidence(
            ingressSnapshot: P010ImageIngressSnapshot
        ): Long =
            ingressSnapshot.admissionRejectedFrames.coerceAtLeast(0L) +
                ingressSnapshot.processingDroppedFrames.coerceAtLeast(0L)

        internal fun expectedP010FrameCountForEvidence(
            nominalExpectedFrameCount: Long,
            ingressSnapshot: P010ImageIngressSnapshot
        ): Long {
            val safeNominalExpectedFrameCount = nominalExpectedFrameCount.coerceAtLeast(1L)
            val sourceCadenceCloseToNominal =
                ingressSnapshot.callbackFrames.coerceAtLeast(0L) * 100L >=
                    safeNominalExpectedFrameCount * 90L
            return if (
                p010DroppedFramesForEvidence(ingressSnapshot) == 0L &&
                sourceCadenceCloseToNominal &&
                ingressSnapshot.queuedFrames > 0L
            ) {
                safeNominalExpectedFrameCount.coerceAtMost(ingressSnapshot.queuedFrames)
            } else {
                safeNominalExpectedFrameCount
            }
        }

        internal fun effectiveP010EvidenceDurationUs(
            wallActiveDurationUs: Long,
            fps: Int,
            ingressSnapshot: P010ImageIngressSnapshot
        ): Long {
            val safeWallDurationUs = wallActiveDurationUs.coerceAtLeast(0L)
            val framePeriodUs = MICROSECONDS_PER_SECOND / fps.coerceAtLeast(1)
            val firstTimestampNs = ingressSnapshot.firstSourceTimestampNs
            val lastTimestampNs = ingressSnapshot.lastSourceTimestampNs
            if (ingressSnapshot.callbackFrames < 2L ||
                firstTimestampNs <= 0L ||
                lastTimestampNs <= firstTimestampNs
            ) {
                return safeWallDurationUs
            }
            val sourceDurationUs =
                (lastTimestampNs - firstTimestampNs) / 1_000L + framePeriodUs
            val plausibleUpperBoundUs = safeWallDurationUs + framePeriodUs * 2L
            return sourceDurationUs
                .takeIf { it in framePeriodUs..plausibleUpperBoundUs }
                ?: safeWallDurationUs
        }

        internal data class EncoderSurfaceBitDepthEvidence(
            val applicable: Boolean,
            val precisionProven: Boolean,
            val actualBitDepth: Int,
            val effectiveSourceBitDepth: Int,
            val effectiveStorageBitDepth: Int,
            val format: String,
            val precision: String,
            val precisionFallback: Boolean,
            val proofStatus: String
        )

        internal fun resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth: Int,
            plannedStorageBitDepth: Int,
            runtimeMetadata: Map<String, String>
        ): EncoderSurfaceBitDepthEvidence {
            val applicable = runtimeMetadata["vulkanEncoderSurfaceEvidenceApplicable"]
                ?.toBooleanStrictOrNull() == true
            if (!applicable) {
                return EncoderSurfaceBitDepthEvidence(
                    applicable = false,
                    precisionProven = false,
                    actualBitDepth = 0,
                    effectiveSourceBitDepth = plannedSourceBitDepth,
                    effectiveStorageBitDepth = plannedStorageBitDepth,
                    format = "NOT_APPLICABLE",
                    precision = "NOT_APPLICABLE",
                    precisionFallback = false,
                    proofStatus = "NOT_APPLICABLE"
                )
            }

            val bound = runtimeMetadata["vulkanEncoderSurfaceBoundAtValidation"]
                ?.toBooleanStrictOrNull() == true
            val claimedProven = runtimeMetadata["vulkanEncoderSurfacePrecisionProven"]
                ?.toBooleanStrictOrNull() == true
            val format = runtimeMetadata["vulkanEncoderSurfaceFormat"].orEmpty()
            val bitDepth = runtimeMetadata["vulkanEncoderSurfaceBitDepth"]
                ?.toIntOrNull() ?: 0
            val precision = runtimeMetadata["vulkanEncoderSurfacePrecision"].orEmpty()
            val precisionFallback = runtimeMetadata["vulkanEncoderSurfacePrecisionFallback"]
                ?.toBooleanStrictOrNull() == true
            val expectedBitDepth = when (precision) {
                "EIGHT_BIT" -> 8
                "PACKED_10_BIT" -> 10
                "FP16" -> 16
                else -> 0
            }
            val measurementCoherent =
                bound &&
                    format.isNotBlank() &&
                    format != "UNDEFINED" &&
                    format != "OTHER" &&
                    bitDepth == expectedBitDepth &&
                    precisionFallback == (precision == "EIGHT_BIT")
            val precisionProven = claimedProven && measurementCoherent
            if (!precisionProven) {
                return EncoderSurfaceBitDepthEvidence(
                    applicable = true,
                    precisionProven = false,
                    actualBitDepth = if (measurementCoherent) bitDepth else 0,
                    effectiveSourceBitDepth = 0,
                    effectiveStorageBitDepth = 0,
                    format = format.ifBlank { "UNKNOWN" },
                    precision = if (measurementCoherent) precision else "UNKNOWN",
                    precisionFallback =
                        if (measurementCoherent) precisionFallback else false,
                    proofStatus = "NOT_PROVEN"
                )
            }

            return EncoderSurfaceBitDepthEvidence(
                applicable = true,
                precisionProven = true,
                actualBitDepth = bitDepth,
                effectiveSourceBitDepth = minOf(plannedSourceBitDepth, bitDepth),
                effectiveStorageBitDepth = minOf(plannedStorageBitDepth, bitDepth),
                format = format,
                precision = precision,
                precisionFallback = precisionFallback,
                proofStatus = if (bitDepth >= 10) "PASS" else "FAIL"
            )
        }

        private const val MIME_AAC = "audio/mp4a-latm"

        // Audio constants
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val AUDIO_BITRATE = 256_000
        private const val AUDIO_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC

        // ULTRA HIGH-END OPTIMIZATION: Encoder drain timeout (UPGRADED for efficiency)
        // OLD: 10_000L (10ms base with exponential backoff = wasteful polling)
        // NEW: 5_000L (5ms base = less polling overhead)
        private const val DRAIN_TIMEOUT_US = 5_000L  // Reduced polling frequency
        private const val DRAIN_MAX_TIMEOUT_US = 50_000L  // Cap at 50ms max wait
        private const val BYTEBUFFER_INPUT_TIMEOUT_US = 500_000L
        private const val MAX_PENDING_MUXER_SAMPLES = 4096
        private const val MAX_PENDING_MUXER_BUFFER_BYTES = 96L * 1024L * 1024L
        private const val STRICT_MUXER_PREWARM_TARGET_BYTES = 96 * 1024 * 1024
        private const val STANDARD_MUXER_PREWARM_TARGET_BYTES = 24 * 1024 * 1024
        private const val STRICT_MUXER_PREWARM_MIN_BUFFERS = 24
        private const val STRICT_MUXER_PREWARM_MAX_BUFFERS = 96
        private const val STANDARD_MUXER_PREWARM_MIN_BUFFERS = 8
        private const val STANDARD_MUXER_PREWARM_MAX_BUFFERS = 24
        private const val MUXER_PREWARM_MIN_BUFFER_BYTES = 256 * 1024
        private const val MUXER_PREWARM_MAX_BUFFER_BYTES = 4 * 1024 * 1024
        
        // PRIORITY 1 BUG FIX: Audio/video sync validation and error recovery
        private const val AV_SYNC_TOLERANCE_MS = 100L  // ±100ms sync tolerance
        private const val DRAIN_ERROR_RECOVERY_ATTEMPTS = 3
        private const val DRAIN_ERROR_BACKOFF_MS = 10L // ARCHITECTURAL FIX: 10ms cap for sub-second termination
        private const val SLOW_PREPARE_STAGE_MS = 260L
        private const val SLOW_START_STAGE_MS = 120L
        private const val SLOW_STOP_STAGE_MS = 900L
        private const val ENCODER_SURFACE_FRAME_RATE_HINT_ENABLED = false

        // Bitrate policy helpers
        fun bitrateFor(w: Int, h: Int, fps: Int): Int =
            recommendedBitrateFor(w, h, fps)

        fun recommendedBitrateFor(
            w: Int,
            h: Int,
            fps: Int,
            codec: GpuVideoCodec = GpuVideoCodec.HEVC,
            hdrMode: String = "OFF",
            logEnabled: Boolean = false,
            tenBit: Boolean = false,
            colorSpace: String = "Rec.709",
            colorProfile: String = "Natural",
            videoDetailProfile: VideoDetailProfile? = null
        ): Int {
            val pixels = w.toLong() * h
            // FLAGSHIP: Sensor-native open-gate bitrate tiers for maximum-detail recording
            val base = when {
                pixels >= 40_000_000L -> if (fps >= 60) 800_000_000 else 600_000_000  // sensor-native open gate
                pixels >= 7680L * 4320 -> if (fps >= 60) 600_000_000 else 450_000_000  // 8K
                pixels >= 5120L * 2880 -> if (fps >= 60) 420_000_000 else 320_000_000  // 5K
                pixels >= 4096L * 2160 -> if (fps >= 60) 380_000_000 else 280_000_000  // DCI 4K
                pixels >= 3840L * 2160 -> if (fps >= 60) 340_000_000 else 260_000_000  // 4K UHD
                pixels >= 2560L * 1440 -> if (fps >= 60) 180_000_000 else 120_000_000  // QHD
                pixels >= 1920L * 1080 -> if (fps >= 60) 120_000_000 else 90_000_000   // FHD
                pixels >= 1280L * 720  -> if (fps >= 60) 50_000_000  else 32_000_000   // HD
                else -> 20_000_000
            }

            var multiplier = RecordingCodecCatalog.bitrateMultiplier(codec)
            val logLook = NeoLogProfilePolicy.resolve(colorProfile, logEnabled)
            multiplier += RecordingHdrVideoLookPolicy.resolveBitrateLookMultiplier(
                hdrMode = hdrMode,
                logEnabled = logEnabled,
                tenBit = tenBit,
                colorSpace = colorSpace
            ) - 1f
            multiplier += logLook.bitrateBonus
            if (HighEndQualityPolicy.enabled) {
                multiplier += if (HighEndQualityPolicy.strictHighEndOnly) 0.35f else 0.18f
            }

            val qualityBudgetFloor = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
                RecordingVideoQualityBudgetRequest(
                    width = w,
                    height = h,
                    fps = fps,
                    codec = codec,
                    hdrMode = hdrMode,
                    logEnabled = logEnabled,
                    logProfile = colorProfile,
                    tenBit = tenBit,
                    colorSpace = colorSpace,
                    videoDetailProfile = videoDetailProfile
                )
            ).toLong()
            val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(codec).toLong() * 1_000_000L

            return (base * multiplier)
                .roundToLong()
                .coerceAtLeast(qualityBudgetFloor)
                .coerceAtMost(codecCeiling)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

        fun minimumBitrateFloorFor(w: Int, h: Int, fps: Int = 25): Int {
            if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
                return HighEndQualityPolicy.minimumBitrateBps(w, h, fps = fps)
            }
            val pixels = w.toLong() * h
            return when {
                pixels >= 7680L * 4320 -> 90_000_000
                pixels >= 5120L * 2880 -> 60_000_000
                pixels >= 3840L * 2160 -> 45_000_000
                pixels >= 1920L * 1080 -> 18_000_000
                else -> 8_000_000
            }
        }
    }

    internal enum class P010IngressHealth {
        IDLE,
        STARTING,
        FLOWING,
        BACKPRESSURE_VISIBLE,
        PROCESSING_DROPS_VISIBLE,
        QUEUE_STALLED
    }

    internal class P010BlockModelFailureState {
        private val failed = AtomicBoolean(false)

        fun canQueue(): Boolean = !failed.get()

        fun markFailed(): Boolean = failed.compareAndSet(false, true)

        fun reset() {
            failed.set(false)
        }
    }

    internal data class P010ImageIngressSnapshot(
        val callbackFrames: Long,
        val acceptedFrames: Long,
        val queuedFrames: Long,
        val encodedFrames: Long,
        val admissionRejectedFrames: Long,
        val processingDroppedFrames: Long,
        val inFlightFrames: Int,
        val firstSourceTimestampNs: Long = 0L,
        val lastSourceTimestampNs: Long = 0L,
        val nativeDirectCopies: Long = 0L,
        val directBufferCopies: Long = 0L,
        val copyFailures: Long = 0L,
        val averageCopyUs: Long = 0L,
        val maxCopyUs: Long = 0L,
        val averageCodecInputWaitUs: Long = 0L,
        val maxCodecInputWaitUs: Long = 0L,
        val admissionCapacity: Int = 0,
        val peakInFlightFrames: Int = 0
    ) {
        val callbackAccountingGap: Long
            get() = (
                callbackFrames - acceptedFrames - admissionRejectedFrames
                ).coerceAtLeast(0L)

        val acceptedAccountingGap: Long
            get() = (
                acceptedFrames - queuedFrames - processingDroppedFrames - inFlightFrames
                ).coerceAtLeast(0L)

        fun health(minSustainedCallbacks: Long): P010IngressHealth = when {
            callbackFrames <= 0L -> P010IngressHealth.IDLE
            callbackFrames >= minSustainedCallbacks &&
                queuedFrames <= 1L &&
                encodedFrames <= 1L &&
                inFlightFrames == 0 &&
                admissionRejectedFrames == 0L &&
                processingDroppedFrames == 0L -> P010IngressHealth.QUEUE_STALLED
            callbackAccountingGap > 0L || acceptedAccountingGap > 0L ->
                P010IngressHealth.QUEUE_STALLED
            admissionRejectedFrames > 0L -> P010IngressHealth.BACKPRESSURE_VISIBLE
            processingDroppedFrames > 0L -> P010IngressHealth.PROCESSING_DROPS_VISIBLE
            queuedFrames > 0L -> P010IngressHealth.FLOWING
            else -> P010IngressHealth.STARTING
        }
    }

    internal class P010ImageIngressTelemetry(
        private val maxInFlightProvider: () -> Int
    ) {
        private val callbackFrames = AtomicLong(0L)
        private val acceptedFrames = AtomicLong(0L)
        private val queuedFrames = AtomicLong(0L)
        private val admissionRejectedFrames = AtomicLong(0L)
        private val processingDroppedFrames = AtomicLong(0L)
        private val inFlightFrames = AtomicInteger(0)
        private val peakInFlightFrames = AtomicInteger(0)
        private val firstSourceTimestampNs = AtomicLong(0L)
        private val lastSourceTimestampNs = AtomicLong(0L)
        private val nativeDirectCopies = AtomicLong(0L)
        private val directBufferCopies = AtomicLong(0L)
        private val copyFailures = AtomicLong(0L)
        private val copyDurationCount = AtomicLong(0L)
        private val totalCopyDurationNs = AtomicLong(0L)
        private val maxCopyDurationNs = AtomicLong(0L)
        private val codecInputWaitCount = AtomicLong(0L)
        private val totalCodecInputWaitNs = AtomicLong(0L)
        private val maxCodecInputWaitNs = AtomicLong(0L)

        fun tryAcceptCallback(sourceTimestampNs: Long = 0L): Boolean {
            callbackFrames.incrementAndGet()
            if (sourceTimestampNs > 0L) {
                firstSourceTimestampNs.compareAndSet(0L, sourceTimestampNs)
                updateMax(lastSourceTimestampNs, sourceTimestampNs)
            }
            while (true) {
                val current = inFlightFrames.get()
                val maxInFlight = maxInFlightProvider().coerceAtLeast(1)
                if (current >= maxInFlight) {
                    admissionRejectedFrames.incrementAndGet()
                    return false
                }
                if (inFlightFrames.compareAndSet(current, current + 1)) {
                    updateMax(peakInFlightFrames, current + 1)
                    acceptedFrames.incrementAndGet()
                    return true
                }
            }
        }

        fun recordQueued() {
            queuedFrames.incrementAndGet()
        }

        fun recordProcessingDrop() {
            processingDroppedFrames.incrementAndGet()
        }

        fun recordCopy(
            path: Npv10FramePacker.P010CopyPath,
            durationNs: Long
        ) {
            when (path) {
                Npv10FramePacker.P010CopyPath.NATIVE_DIRECT ->
                    nativeDirectCopies.incrementAndGet()
                Npv10FramePacker.P010CopyPath.DIRECT_BUFFER ->
                    directBufferCopies.incrementAndGet()
                Npv10FramePacker.P010CopyPath.FAILED ->
                    copyFailures.incrementAndGet()
            }
            copyDurationCount.incrementAndGet()
            totalCopyDurationNs.addAndGet(durationNs.coerceAtLeast(0L))
            updateMax(maxCopyDurationNs, durationNs.coerceAtLeast(0L))
        }

        fun recordCodecInputWait(durationNs: Long) {
            codecInputWaitCount.incrementAndGet()
            totalCodecInputWaitNs.addAndGet(durationNs.coerceAtLeast(0L))
            updateMax(maxCodecInputWaitNs, durationNs.coerceAtLeast(0L))
        }

        fun release() {
            while (true) {
                val current = inFlightFrames.get()
                if (current <= 0) return
                if (inFlightFrames.compareAndSet(current, current - 1)) return
            }
        }

        fun snapshot(encodedFrames: Long): P010ImageIngressSnapshot {
            val copyCount = copyDurationCount.get()
            val waitCount = codecInputWaitCount.get()
            return P010ImageIngressSnapshot(
                callbackFrames = callbackFrames.get(),
                acceptedFrames = acceptedFrames.get(),
                queuedFrames = queuedFrames.get(),
                encodedFrames = encodedFrames.coerceAtLeast(0L),
                admissionRejectedFrames = admissionRejectedFrames.get(),
                processingDroppedFrames = processingDroppedFrames.get(),
                inFlightFrames = inFlightFrames.get(),
                firstSourceTimestampNs = firstSourceTimestampNs.get(),
                lastSourceTimestampNs = lastSourceTimestampNs.get(),
                nativeDirectCopies = nativeDirectCopies.get(),
                directBufferCopies = directBufferCopies.get(),
                copyFailures = copyFailures.get(),
                averageCopyUs = if (copyCount > 0L) {
                    totalCopyDurationNs.get() / copyCount / 1_000L
                } else {
                    0L
                },
                maxCopyUs = maxCopyDurationNs.get() / 1_000L,
                averageCodecInputWaitUs = if (waitCount > 0L) {
                    totalCodecInputWaitNs.get() / waitCount / 1_000L
                } else {
                    0L
                },
                maxCodecInputWaitUs = maxCodecInputWaitNs.get() / 1_000L,
                admissionCapacity = maxInFlightProvider().coerceAtLeast(1),
                peakInFlightFrames = peakInFlightFrames.get()
            )
        }

        fun reset(): Boolean {
            if (inFlightFrames.get() != 0) return false
            callbackFrames.set(0L)
            acceptedFrames.set(0L)
            queuedFrames.set(0L)
            admissionRejectedFrames.set(0L)
            processingDroppedFrames.set(0L)
            peakInFlightFrames.set(0)
            firstSourceTimestampNs.set(0L)
            lastSourceTimestampNs.set(0L)
            nativeDirectCopies.set(0L)
            directBufferCopies.set(0L)
            copyFailures.set(0L)
            copyDurationCount.set(0L)
            totalCopyDurationNs.set(0L)
            maxCopyDurationNs.set(0L)
            codecInputWaitCount.set(0L)
            totalCodecInputWaitNs.set(0L)
            maxCodecInputWaitNs.set(0L)
            return true
        }

        private fun updateMax(target: AtomicLong, candidate: Long) {
            while (true) {
                val current = target.get()
                if (candidate <= current || target.compareAndSet(current, candidate)) return
            }
        }

        private fun updateMax(target: AtomicInteger, candidate: Int) {
            while (true) {
                val current = target.get()
                if (candidate <= current || target.compareAndSet(current, candidate)) return
            }
        }
    }

    // Configuration

    var videoWidth = 1920
    var videoHeight = 1080
    var videoFps = 25
    var videoBitrate = bitrateFor(1920, 1080, 25)
    var videoCodec = GpuVideoCodec.HEVC
    var audioEnabled = true
    var audioSource = AudioSrc.CAMCORDER
    var tenBitEnabled = false
    var videoColorSpace = "Rec.709"
    var videoColorProfile = "Natural"
    var videoLogEnabled = false
    var videoDetailProfile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
    var cinemaMasteringEnabled = false
    var manualBitrateSelectionEnabled = false
    var videoHdrMode = "OFF"
    var cinemaMode = CinemaVideoMode.PRO
    var rawVideoEnabled = false
    var runtimeEvidenceMetadataProvider: (() -> Map<String, String>)? = null
    var iFrameInterval = 1  // keyframe every N seconds
    var orientationHintDegrees = 0
    var sceneMetadataDescription: String = ""
    var outputLabelSuffix: String? = null
    /**
     * One-shot user-typed recording name (already sanitized by RecordingFileNamePolicy). When non-null
     * it becomes the base file name for the next recording; null => default "CamX ddMMyyyy". Set by
     * CameraViewModel before start() and cleared by it after a successful save.
     */
    var pendingCustomRecordingName: String? = null
    @Volatile private var videoInputMode: RecordingVideoInputMode = RecordingVideoInputMode.SURFACE
    var strictRequestedResolution = false
    var strictCameraResolution = false
    var strictEncoderResolution = false
    private var cameraSupportedRecordingSizes: List<Size> = emptyList()
    @Volatile var simulatedTenBitFallbackActive = false
        private set
    private var activeBitDepthLabel = "8-bit"
    private var activeDynamicRangeLabel = "SDR"
    private var activeLogCurveLabel = "OFF"
    private var activeQualityTierLabel = RecordingCodecCatalog.QualityTier.STANDARD.name
    private var activeIFrameIntervalSeconds = iFrameInterval.toFloat().coerceAtLeast(0.25f)
    private var requestedOutputBitrateForEvidence = videoBitrate
    private var policyTargetBitrateForEvidence = videoBitrate
    private var activeOutputBitrate = videoBitrate
    // s6-f01: additive encoder transport-truth read-back evidence (read-only; never alters config).
    // -1 == not captured / not read back (encoder-output read-back is vendor- and device-gated).
    private var requestedVideoProfileForEvidence = -1
    private var requestedVideoLevelForEvidence = -1
    private var requestedVideoBitrateForEvidence = -1
    private var requestedColorStandardForEvidence = -1
    private var requestedColorTransferForEvidence = -1
    private var requestedColorRangeForEvidence = -1
    @Volatile private var muxedVideoProfileForEvidence = -1
    private var muxedVideoLevelForEvidence = -1
    private var muxedVideoBitrateForEvidence = -1
    @Volatile private var encoderTransportReadBackAvailable = false
    private var activeColorEncodingConfig = RecordingHdrVideoLookPolicy.resolveLookProfile(
        mime = RecordingCodecCatalog.mime(videoCodec),
        videoHdrMode = videoHdrMode,
        videoColorSpace = videoColorSpace,
        logEnabled = videoLogEnabled
    ).colorEncoding
    private var activeEncoderQualityTelemetry = RecordingEncoderQualityTelemetry.EMPTY
    /**
     * Honest record of whether the active encode is **actually** all-intra
     * HEVC (every frame is an I-frame, hardware-honoured) versus only being
     * requested via [cinemaMasteringEnabled]. Set inside `prepare()` from
     * [AllIntraHevcPolicy.probe] + [AllIntraHevcPolicy.applyToFormat]; reset
     * by [releaseInternal]. Surfaced through [allIntraActive] for UI/telemetry.
     */
    @Volatile private var allIntraActiveInternal: Boolean = false
    /**
     * Sliding-window meter computing the **actually observed** encoded output
     * bitrate from dequeued MediaCodec buffers. Surfaced to the UI so the
     * displayed Mbps reflects the file growth rate rather than the requested
     * target. See [EncodedOutputBitrateMeter] for the integration math.
     */
    private val encodedOutputBitrateMeter = EncodedOutputBitrateMeter()
    private var lastBitrateHintUptimeMs = 0L
    private var lastSyncFrameRequestUptimeMs = 0L
    private var lastHintedBitrate = videoBitrate

    // Adaptive polling optimization (Tier 2.1)
    private var currentFrameRate: Float = 25f
    private var lastEncoderPtsUs: Long = 0L
    private var avDriftMs: Long = 0L
    private var framePacingJitterMs: Long = 0L
    private var drainTimeoutUsScaled: Long = DRAIN_TIMEOUT_US

    // State

    private val _state = MutableStateFlow<GpuRecordingState>(GpuRecordingState.Idle)
    val state: StateFlow<GpuRecordingState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    var lastOutputPath: String? = null
        private set

    /** Maximum allowed encoder complexity, actively modulated by the Predictive Thermal Kalman Filter. */
    @Volatile
    var thermalEncoderComplexity: Int = 10

    // Internal codec objects

    private var videoCodecObj: MediaCodec? = null
    private var audioCodecObj: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private val audioCaptureStopRequested = AtomicBoolean(false)
    private var primedAudioPcmFrames = 0L
    private val outputStore = RecordingMediaStoreOutputStore(context)
    // New masters prefer app-private external storage, while discovery scans both external and internal
    // roots. A clip recorded while external storage was unavailable must not disappear when it returns.
    private val recordDevelopPreferredRoot: java.io.File =
        context.getExternalFilesDir(null) ?: context.filesDir
    private val recordDevelopSdrStore =
        RecordDevelopMasterStore(recordDevelopPreferredRoot, RecordDevelopMasterKind.SDR)
    private val recordDevelopNdrStore =
        RecordDevelopMasterStore(recordDevelopPreferredRoot, RecordDevelopMasterKind.NDR)
    /** The private develop-master File the current/last recording was routed into, or null (normal lane). */
    var lastDevelopMasterFile: java.io.File? = null
        private set
    private var movSessionController: RecordingMovSessionController? = null
    private var encoderSurface: Surface? = null
    private var persistentEncoderSurface: Surface? = null
    private var encoderSurfaceUsesPersistentInput = false
    // s6-f10: additive zero-shutter-lag startup accounting. Write-only fields consumed solely by the
    // quality-certificate evidence map; they drive NO live selection branch. -1 == honest NOT_MEASURED.
    private var lastRecordStartToFirstFrameUs = -1L
    private var recordStartHeadFrameGap = -1
    private val videoInputLock = Any()
    private val byteBufferVideoEosQueued = AtomicBoolean(false)
    private val blockModelInputIndices = LinkedBlockingQueue<Int>()
    private val blockModelVideoEvents = LinkedBlockingQueue<BlockModelVideoEvent>()
    private val p010BlockModelFailureState = P010BlockModelFailureState()
    private var videoCodecCallbackThread: HandlerThread? = null
    @Volatile private var p010LinearBlockAdmissionEnabled = false
    @Volatile private var p010LinearBlockCopyFreeCompatible = false
    private val p010LinearBlockFramesQueued = AtomicLong(0L)
    private val byteBufferImageInputExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            // This thread dequeues + copies the P010 frame into the encoder input under
            // videoInputLock, so its scheduling latency directly gates the fallback record cadence.
            // The JVM MAX_PRIORITY below is largely ignored by CFS; apply a real URGENT_DISPLAY
            // Process nice from inside the run-body. Scheduling only — identical bytes muxed.
            Thread({ HotPathScheduling.applyUrgentDisplay(); runnable.run() }, "Recording-P010Input").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
        }
    private val byteBufferImageIngressTelemetry =
        P010ImageIngressTelemetry(
            maxInFlightProvider = {
                resolveP010ImageInFlightCapacity(videoFps)
                    .coerceAtMost(P010_PREVIEW_SAFE_MAX_IN_FLIGHT)
            }
        )
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val byteBufferImageInputIdleLock = java.lang.Object()
    private val byteBufferP010FramesQueued = AtomicLong(0L)
    private val byteBufferP010FrameDrops = AtomicLong(0L)
    // Observe-only one-shot diagnostic markers (no behavior change). They make the legacy P010
    // fallback's frame flow visible on devices that suppress android.util.Log (e.g. vivo I2405),
    // so a single recording reveals whether frames were accepted and whether any were encoded.
    private val p010FirstAcceptLogged = AtomicBoolean(false)
    private val p010FirstEncodeLogged = AtomicBoolean(false)
    // Set when the saturated P010 encoder cannot accept an end-of-stream input buffer at stop.
    // Signals the drain to finish cleanly so already-encoded frames are finalized, not discarded.
    @Volatile private var byteBufferEosInputUnavailable = false
    @Volatile private var byteBufferP010EncoderName = ""
    @Volatile private var byteBufferLastQueuedPtsUs = -1L
    private var persistentInputSurfaceRejected = false
    @Volatile
    private var persistentInputSurfaceAllowed = true
    private val muxerLock = Any()
    private var outputTarget: RecordingOutputTarget? = null
    private val muxerSampleQueue = RecordingMuxerSampleQueue(
        maxPendingSamples = MAX_PENDING_MUXER_SAMPLES,
        maxPendingBufferBytes = MAX_PENDING_MUXER_BUFFER_BYTES
    )
    private var muxerWriterJob: Job? = null
    private val codecSupport by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RecordingCodecSupportProbe.probe()
    }
    private val encoderCandidatesCacheLock = Any()
    private val encoderCandidatesCache = mutableMapOf<String, List<EncoderCandidate>>()

    // Track indices from the active recording container backend
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // Flag: container writes may begin once the required tracks are registered
    private val muxerStarted = AtomicBoolean(false)

    // Recording timing
    private var recordingStartNs = 0L
    private var audioTrackDeadlineNs = 0L
    private val isRecording = AtomicBoolean(false)
    private var firstVideoPtsUs = -1L
    private var firstOutputVideoPtsUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private val recordingPtsSynchronizer = RecordingPtsSynchronizer()
    private val encoderPresentationQueue =
        Channel<RecordingFramePresentation>(capacity = Channel.CONFLATED)
    @Volatile private var realtimeSurfaceCadenceEnabled = false
    private var encodedVideoSampleCount = 0L
    private var recordingStartEpochMs = 0L
    private var recordingStartProcessCpuMs = 0L
    @Volatile private var finalRecordingCpuUsagePercentForEvidence: Float? = null
    private var finalActiveRecordingDurationUsForEvidence = 0L
    private var clipTimingStartNsForEvidence = 0L
    private var clipTimingStartPausedUsForEvidence = 0L
    private var stopRequestedNsForEvidence = 0L
    private var finalStrictFrameGateDurationUsForEvidence = 0L
    private var maxEncodedFrameIntervalMs = 0f
    private val pauseTimelineLock = Any()
    @Volatile private var pauseTimeline = RecordingPauseTimeline()
    private val videoDrainComplete = AtomicBoolean(false)
    private val audioDrainComplete = AtomicBoolean(false)
    private val audioCodecTerminalShutdown = AtomicBoolean(false)
    
    // PRIORITY 1 BUG FIX: MediaCodec error recovery and A/V sync tracking
    private var videoDrainErrorCount = 0
    private var audioDrainErrorCount = 0
    private var maxAvSyncDriftMs = 0L
    private var avSyncWarningLogged = false
    private var muxerBackpressureDropCount = 0
    private var abrNominalBitrate = videoBitrate
    @Volatile private var abrShaderEntropyScale = 1f
    @Volatile private var abrDetailGainScale = 1f
    @Volatile private var abrDitherAmplitudeScale = 1f

    // Audio thread reference for clean shutdown.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val videoEncoderDrainDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val audioEncoderDrainDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val muxerWriterDispatcher = Dispatchers.IO.limitedParallelism(1)

    private data class EncoderCandidate(
        val codecInfo: MediaCodecInfo,
        val mimeType: String,
        val codecCapabilities: MediaCodecInfo.CodecCapabilities?,
        val videoCapabilities: MediaCodecInfo.VideoCapabilities?,
        val selectionCandidate: RecordingCodecSelectionCandidate
    )

    private data class SelectedEncoderCandidate(
        val candidate: EncoderCandidate,
        val selection: RecordingCodecSelectionCandidate
    )

    private data class ResolvedVideoConfig(
        val size: Size,
        val fps: Int,
        val encoderExactMatch: Boolean,
        val cameraExactMatch: Boolean
    ) {
        val exactMatch: Boolean get() = encoderExactMatch && cameraExactMatch
    }

    private data class StrictStopFrameGateEvidence(
        val required: Boolean,
        val expectedVideoSamples: Long,
        val evidenceDurationUs: Long,
        val gpuProcessingPath: String,
        val evidenceMode: EliteVideoPipelineMode
    ) {
        fun failureMessage(actualVideoSamples: Long): String =
            "Strict offscreen AHB/EGL recording did not sustain target FPS " +
                "(actualSamples=$actualVideoSamples expectedSamples=$expectedVideoSamples " +
                "durationUs=$evidenceDurationUs path=$gpuProcessingPath mode=$evidenceMode)"
    }

    private sealed interface BlockModelVideoEvent {
        data class OutputBuffer(
            val index: Int,
            val info: MediaCodec.BufferInfo
        ) : BlockModelVideoEvent

        data class OutputFormat(
            val format: MediaFormat
        ) : BlockModelVideoEvent

        data class CodecError(
            val exception: MediaCodec.CodecException
        ) : BlockModelVideoEvent
    }

    fun setVideoInputMode(mode: RecordingVideoInputMode) {
        if (_state.value is GpuRecordingState.Recording ||
            _state.value is GpuRecordingState.Paused ||
            _state.value is GpuRecordingState.Stopping
        ) {
            Log.w(TAG, "Ignoring video input mode change while recording: $mode")
            return
        }
        videoInputMode = mode
        if (mode == RecordingVideoInputMode.BYTEBUFFER_P010) {
            realtimeSurfaceCadenceEnabled = false
            encoderSurface = null
            encoderSurfaceUsesPersistentInput = false
        }
    }

    fun setRealtimeSurfaceCadenceEnabled(enabled: Boolean) {
        realtimeSurfaceCadenceEnabled =
            enabled && videoInputMode == RecordingVideoInputMode.SURFACE
    }

    fun markClipTimingStartForEvidence() {
        synchronized(pauseTimelineLock) {
            if (clipTimingStartNsForEvidence > 0L) return
            clipTimingStartNsForEvidence = System.nanoTime()
            clipTimingStartPausedUsForEvidence = pauseTimeline.accumulatedPausedUs.coerceAtLeast(0L)
        }
        Log.i(TAG, "Recording clip timing evidence window started")
    }

    fun markStopRequestedForEvidence() {
        var cpuUsagePercent: Float? = null
        synchronized(pauseTimelineLock) {
            if (stopRequestedNsForEvidence > 0L) return
            val stopNs = System.nanoTime()
            stopRequestedNsForEvidence = stopNs
            cpuUsagePercent = RecordingRuntimeEvidencePolicy.processCpuUsagePercent(
                startProcessCpuMs = recordingStartProcessCpuMs,
                startElapsedNs = recordingStartNs,
                endProcessCpuMs = Process.getElapsedCpuTime(),
                endElapsedNs = stopNs
            )
            finalRecordingCpuUsagePercentForEvidence = cpuUsagePercent
        }
        Log.i(
            TAG,
            "Recording stop timing evidence window ended cpuUsagePercent=${cpuUsagePercent ?: "unavailable"}"
        )
    }

    fun preparePersistentInputSurface(): Surface? {
        if (!persistentInputSurfaceAllowed) {
            Log.i(TAG, "Persistent encoder input surface disabled for this recording route")
            return null
        }
        if (!RecordingPersistentInputSurfacePolicy.shouldPreparePersistentInputSurface()) return null
        persistentEncoderSurface
            ?.takeIf { surface -> runCatching { surface.isValid }.getOrDefault(false) }
            ?.let { return it }
        return runCatching {
            MediaCodec.createPersistentInputSurface().also { surface ->
                persistentEncoderSurface = surface
                persistentInputSurfaceRejected = false
                Log.i(TAG, "Persistent encoder input surface prepared for pre-armed Camera2 recording graph")
            }
        }.onFailure { throwable ->
            persistentEncoderSurface = null
            persistentInputSurfaceRejected = true
            Log.w(TAG, "Persistent encoder input surface unavailable; recording will use codec-owned surface", throwable)
        }.getOrNull()
    }

    fun setPersistentInputSurfaceAllowed(allowed: Boolean) {
        persistentInputSurfaceAllowed = allowed
        if (!allowed) {
            releasePersistentInputSurface()
        }
    }

    fun releasePersistentInputSurface() {
        val persistent = persistentEncoderSurface ?: return
        if (encoderSurface === persistent) {
            encoderSurface = null
            encoderSurfaceUsesPersistentInput = false
        }
        runCatching { persistent.release() }
            .onFailure { Log.w(TAG, "persistentEncoderSurface.release() failed", it) }
        persistentEncoderSurface = null
        persistentInputSurfaceRejected = false
    }

    private fun attachVideoInputSurface(codec: MediaCodec): Surface {
        val persistent = if (persistentInputSurfaceAllowed) persistentEncoderSurface else null
        if (persistent != null &&
            RecordingPersistentInputSurfacePolicy.shouldUsePersistentInputSurface(
                persistentSurfaceAvailable = true,
                persistentSurfaceRejected = persistentInputSurfaceRejected
            )
        ) {
            try {
                codec.setInputSurface(persistent)
                encoderSurfaceUsesPersistentInput = true
                configureEncoderSurfaceFrameRate(persistent)
                Log.i(TAG, "Video encoder using persistent pre-armed input surface")
                return persistent
            } catch (throwable: Throwable) {
                persistentInputSurfaceRejected = true
                releasePersistentInputSurface()
                Log.w(TAG, "Persistent encoder input surface rejected by codec", throwable)
                throw throwable
            }
        }

        encoderSurfaceUsesPersistentInput = false
        if (!persistentInputSurfaceAllowed) {
            Log.i(TAG, "Video encoder using codec-owned input surface for hardware-import recording")
        }
        return codec.createInputSurface().also { surface ->
            configureEncoderSurfaceFrameRate(surface)
        }
    }

    private fun configureVideoCodecForInputMode(
        codec: MediaCodec,
        format: MediaFormat,
        colorEncoding: RecordingColorEncodingConfig
    ) {
        val p010Input = videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010
        val copyFreeCompatible =
            p010Input &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                isLinearBlockCopyFreeCompatible(codec)
        val useLinearBlockAdmission = shouldUseP010LinearBlockAdmission(
            sdkInt = Build.VERSION.SDK_INT,
            p010Input = p010Input,
            codecCopyFreeCompatible = copyFreeCompatible,
            codecName = codec.name
        )
        resetVideoCodecBlockModelSession()
        p010LinearBlockCopyFreeCompatible = copyFreeCompatible
        p010LinearBlockAdmissionEnabled = useLinearBlockAdmission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && useLinearBlockAdmission) {
            installVideoCodecBlockModelCallback(codec)
        }
        val configureFlags =
            MediaCodec.CONFIGURE_FLAG_ENCODE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    useLinearBlockAdmission
                ) {
                    blockModelConfigureFlag()
                } else {
                    0
                }
        codec.configure(format, null, null, configureFlags)
        logVideoTransferRequestSupport(codec, colorEncoding.colorTransfer)
        if (p010Input) {
            encoderSurface = null
            encoderSurfaceUsesPersistentInput = false
            Log.i(
                TAG,
                "Video encoder using P010 bytebuffer input " +
                    "colorFormat=COLOR_FormatYUVP010 " +
                    "linearBlockCopyFree=$copyFreeCompatible " +
                    "linearBlockAdmission=$useLinearBlockAdmission"
            )
        } else {
            encoderSurface = attachVideoInputSurface(codec)
        }
        videoCodecObj = codec
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isLinearBlockCopyFreeCompatible(codec: MediaCodec): Boolean =
        runCatching {
            MediaCodec.LinearBlock.isCodecCopyFreeCompatible(arrayOf(codec.name))
        }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun blockModelConfigureFlag(): Int =
        MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL

    private fun installVideoCodecBlockModelCallback(codec: MediaCodec) {
        val callbackThread = HandlerThread(
            "Recording-VideoCodecCallback",
            Process.THREAD_PRIORITY_DISPLAY
        ).also { it.start() }
        videoCodecCallbackThread = callbackThread
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (p010BlockModelFailureState.canQueue()) {
                        blockModelInputIndices.offer(index)
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val copiedInfo = MediaCodec.BufferInfo().apply {
                        set(info.offset, info.size, info.presentationTimeUs, info.flags)
                    }
                    blockModelVideoEvents.offer(
                        BlockModelVideoEvent.OutputBuffer(index, copiedInfo)
                    )
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    blockModelVideoEvents.offer(BlockModelVideoEvent.OutputFormat(format))
                }

                override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
                    p010BlockModelFailureState.markFailed()
                    blockModelInputIndices.clear()
                    blockModelVideoEvents.offer(BlockModelVideoEvent.CodecError(exception))
                }
            },
            Handler(callbackThread.looper)
        )
    }

    private fun resetVideoCodecBlockModelSession() {
        p010LinearBlockAdmissionEnabled = false
        p010LinearBlockCopyFreeCompatible = false
        p010BlockModelFailureState.reset()
        blockModelInputIndices.clear()
        blockModelVideoEvents.clear()
        videoCodecCallbackThread?.quitSafely()
        videoCodecCallbackThread = null
    }

    private fun configureEncoderSurfaceFrameRate(surface: Surface) {
        // MediaCodec input surfaces are producer/encoder queues, not display
        // presentation surfaces. On the tested Vulkan hardware-import path,
        // applying a fixed-source frame-rate hint causes vkQueuePresentKHR to
        // pace around 200ms per frame even though the codec is configured for
        // 25fps. Camera2 AE FPS and MediaFormat.KEY_FRAME_RATE remain the
        // authoritative cadence controls for recording.
        if (!ENCODER_SURFACE_FRAME_RATE_HINT_ENABLED) {
            Log.i(TAG, "Encoder input surface frame-rate hint skipped for MediaCodec recording surface")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            surface.setFrameRate(
                videoFps.toFloat().coerceAtLeast(1f),
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }.onSuccess {
            Log.i(TAG, "Encoder input surface fixed frame-rate hint applied: ${videoFps}fps")
        }.onFailure { throwable ->
            Log.w(TAG, "Encoder input surface frame-rate hint failed", throwable)
        }
    }

    // PREPARE - create codecs and return the encoder input surface.
    //
    // Callers should bind the returned surface to the active GPU renderer or
    // frame pipeline before calling start().

    fun prepare(): Surface? {
        when (val currentState = _state.value) {
            GpuRecordingState.Idle -> Unit
            GpuRecordingState.Recording,
            GpuRecordingState.Paused,
            GpuRecordingState.Stopping -> {
                Log.w(TAG, "prepare() called in non-idle state: $currentState")
                return null
            }
            else -> {
                Log.w(TAG, "prepare() recovering from stale state: $currentState")
                discardPreparedSession()
            }
        }
        val prepareStartElapsedMs = SystemClock.elapsedRealtime()
        return try {
            releaseInternal()
            resetByteBufferVideoInputTelemetry()
            muxerBackpressureDropCount = 0
            encodedVideoSampleCount = 0L
            recordingStartEpochMs = 0L
            recordingStartProcessCpuMs = 0L
            finalRecordingCpuUsagePercentForEvidence = null
            clipTimingStartNsForEvidence = 0L
            clipTimingStartPausedUsForEvidence = 0L
            stopRequestedNsForEvidence = 0L
            finalStrictFrameGateDurationUsForEvidence = 0L
            maxEncodedFrameIntervalMs = 0f

            // Video encoder
            val mime = RecordingCodecCatalog.mime(videoCodec)

            val resolvedConfig = resolveVideoConfig(mime, videoWidth, videoHeight, videoFps)
            val strictCameraRequired = strictRequestedResolution || strictCameraResolution
            val strictEncoderRequired = strictRequestedResolution || strictEncoderResolution
            if ((strictCameraRequired && !resolvedConfig.cameraExactMatch) ||
                (strictEncoderRequired && !resolvedConfig.encoderExactMatch)
            ) {
                val requestedLabel = "${videoWidth}x${videoHeight}"
                val resolvedLabel = "${resolvedConfig.size.width}x${resolvedConfig.size.height}"
                _state.value = GpuRecordingState.Error(
                    "Exact $requestedLabel is unavailable for $mime on this device " +
                        "(cameraExact=${resolvedConfig.cameraExactMatch}, " +
                        "encoderExact=${resolvedConfig.encoderExactMatch}, best=$resolvedLabel)"
                )
                Log.e(TAG, "Strict resolution failure requested=$requestedLabel resolved=$resolvedLabel mime=$mime")
                return null
            }
            videoWidth = resolvedConfig.size.width
            videoHeight = resolvedConfig.size.height
            videoFps = resolvedConfig.fps
            val recordingLookProfile = RecordingHdrVideoLookPolicy.resolveLookProfile(
                mime = mime,
                videoHdrMode = videoHdrMode,
                videoColorSpace = videoColorSpace,
                logEnabled = videoLogEnabled
            )
            val sceneQualityHints = RecordingEncoderSceneQualityHints.fromMetadata(sceneMetadataDescription)
            val eliteQualityTierForPlan = activeEliteVideoQualityTierForPlan()
            val videoPlan = RecordingVideoEncoderPlanResolver.resolvePlan(
                mime = mime,
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                requestedBitrate = videoBitrate,
                tenBitEnabled = tenBitEnabled,
                colorEncoding = recordingLookProfile.colorEncoding,
                hardwareTenBitSupported = supports10Bit(videoCodec),
                av1Main10Profile = codecSupport.av1Main10Profile,
                hevcMain12Profile = codecSupport.hevcMain12Profile,
                minimumBitrateFloorFor = { w, h -> minimumBitrateFloorFor(w, h, fps = videoFps) },
                clampBitrate = { requested ->
                    clampBitrateToEncoderCapabilities(
                        mime = mime,
                        size = Size(videoWidth, videoHeight),
                        requestedBitrate = requested
                    )
                },
                logEnabled = videoLogEnabled,
                cinemaMasteringEnabled = cinemaMasteringEnabled,
                manualBitrateSelectionEnabled = manualBitrateSelectionEnabled,
                requestedDynamicRangeLabel = recordingLookProfile.requestedDynamicRangeLabel,
                eliteQualityTier = eliteQualityTierForPlan,
                sceneQualityHints = sceneQualityHints,
                videoDetailProfile = videoDetailProfile
            )
            if (
                videoPlan.simulatedTenBitFallback &&
                eliteQualityTierForPlan.requiresRealTenBitTransport()
            ) {
                _state.value = GpuRecordingState.Error(
                    "Strict 10-bit video requires real Main10/P010 transport. " +
                        "8-bit fallback is blocked for Normal, Pro, NDR, NIVUKX, and LOG modes."
                )
                Log.e(TAG, "Strict 10-bit Main10 unavailable; refusing 8-bit fallback for tier=$eliteQualityTierForPlan")
                return null
            }
            simulatedTenBitFallbackActive = videoPlan.simulatedTenBitFallback
            activeBitDepthLabel = videoPlan.activeBitDepthLabel
            activeDynamicRangeLabel = videoPlan.dynamicRangeLabel
            activeLogCurveLabel = resolveActiveLogCurveLabel()
            activeQualityTierLabel = videoPlan.qualityTier.name
            activeColorEncodingConfig = videoPlan.colorEncoding
            activeEncoderQualityTelemetry = videoPlan.encoderQualityTelemetry
            videoPlan.encoderQualityTelemetry.logLines.forEach { line -> Log.i(TAG, line) }
            activeIFrameIntervalSeconds = minOf(
                iFrameInterval.toFloat().coerceAtLeast(0.25f),
                videoPlan.iFrameIntervalSeconds
            )
            requestedOutputBitrateForEvidence = videoBitrate
            policyTargetBitrateForEvidence = videoPlan.effectiveBitrate
            activeOutputBitrate = videoPlan.effectiveBitrate
            abrNominalBitrate = activeOutputBitrate
            abrShaderEntropyScale = 1f
            abrDetailGainScale = 1f
            abrDitherAmplitudeScale = 1f
            audioCaptureStopRequested.set(false)
            val selectedVideoEncoder = selectVideoEncoderCandidate(
                mime = mime,
                size = Size(videoWidth, videoHeight),
                fps = videoFps,
                profile = videoPlan.profile,
                level = videoPlan.level,
                targetBitrate = videoPlan.effectiveBitrate
            )
            val inputColorFormat = when (videoInputMode) {
                RecordingVideoInputMode.SURFACE -> null
                RecordingVideoInputMode.BYTEBUFFER_P010 -> {
                    val p010ColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                    val selectedEncoder = selectedVideoEncoder
                    val p010Supported = selectedEncoder
                        ?.candidate
                        ?.codecCapabilities
                        ?.colorFormats
                        ?.contains(p010ColorFormat) == true
                    val main10Profile =
                        videoPlan.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        !mime.equals("video/hevc", ignoreCase = true) ||
                        !main10Profile ||
                        !p010Supported
                    ) {
                        val codecName = selectedEncoder?.candidate?.codecInfo?.name ?: "missing"
                        val supportedFormats = selectedEncoder
                            ?.candidate
                            ?.codecCapabilities
                            ?.colorFormats
                            ?.joinToString(separator = ",")
                            .orEmpty()
                        val message =
                            "P010 bytebuffer encoder unavailable: " +
                                "sdk=${Build.VERSION.SDK_INT} mime=$mime main10=$main10Profile " +
                                "codec=$codecName colorFormats=[$supportedFormats]"
                        _state.value = GpuRecordingState.Error(message)
                        Log.e(TAG, message)
                        return null
                    }
                    byteBufferP010EncoderName = requireNotNull(selectedEncoder).candidate.codecInfo.name
                    p010ColorFormat
                }
            }
            val encoderComplexity = resolveEncoderComplexity(
                codecInfo = selectedVideoEncoder?.candidate?.codecInfo,
                mime = mime,
                requestedComplexity = thermalEncoderComplexity
            )
            val bitrateMode = resolveBitrateMode(
                codecInfo = selectedVideoEncoder?.candidate?.codecInfo,
                mime = mime,
                preferredModes = videoPlan.preferredBitrateModes
            )
            val encoderLevel = resolveSupportedEncoderLevel(
                profileLevels = selectedVideoEncoder?.candidate?.codecCapabilities?.profileLevels,
                profile = videoPlan.profile,
                targetLevel = videoPlan.level
            )
            val normalizedRecordingHdrMode =
                if (videoLogEnabled || rawVideoEnabled) {
                    "OFF"
                } else {
                    RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(videoHdrMode)
                }
            val codecInputColorEncoding = RecordingCodecInputColorPolicy.resolve(
                mime = mime,
                outputColorEncoding = videoPlan.colorEncoding
            )

            var videoFormatRequest = RecordingEncoderFormatRequest(
                mime = mime,
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                bitrate = videoPlan.effectiveBitrate,
                iFrameIntervalSeconds = activeIFrameIntervalSeconds,
                colorEncoding = codecInputColorEncoding,
                bitrateMode = bitrateMode,
                encoderComplexity = encoderComplexity,
                operatingRate = videoPlan.operatingRate,
                profile = videoPlan.profile,
                level = encoderLevel,
                qualityTier = videoPlan.qualityTier,
                advancedQualityTuningEnabled = true,
                outputColorEncoding = videoPlan.colorEncoding,
                hdr10PlusRequested = false,
                certifiedVendorFallbackRequested = false,
                inputColorFormat = inputColorFormat,
                inputMaxSizeBytes = inputColorFormat?.let {
                    Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(videoWidth, videoHeight)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                },
                hdrSceneMetadata = HdrStaticMetadataPolicy.SceneMetadata.fromDescription(sceneMetadataDescription)
            )
            var videoFormatResult = RecordingEncoderFormatPolicy.buildVideoFormat(videoFormatRequest)
            var videoFormat = videoFormatResult.format
            if (videoFormatResult.staticMetadataAttached) {
                Log.i(
                    TAG,
                    "HDR static metadata attached: mode=${videoFormatResult.staticMetadataMode} " +
                        "master=${videoFormatResult.masteringDisplay?.maxLuminanceNits} " +
                        "maxCLL=${videoFormatResult.masteringDisplay?.maxContentLightLevelNits} " +
                        "maxFALL=${videoFormatResult.masteringDisplay?.maxFrameAverageLightLevelNits} " +
                        "transfer=${videoPlan.colorEncoding.colorTransfer} " +
                        "standard=${videoPlan.colorEncoding.colorStandard}"
                )
            }

            // ── ALL-INTRA HEVC WIRING ────────────────────────────────────────
            // Per the directive, when cinema-mastering is enabled and the codec
            // is HEVC, attempt to engage true all-intra encoding (every frame
            // is an I-frame). The policy probes hardware capabilities and
            // returns null when the device cannot support it; in that case we
            // leave the standard format alone and record the honest fallback.
            // Bitrate from the policy is funnelled through the same encoder
            // capability clamp as the regular path so we never request a rate
            // the hardware cannot actually deliver.
            allIntraActiveInternal = false
            var allIntraRetryConfig: AllIntraHevcPolicy.AllIntraConfig? = null
            if ((cinemaMasteringEnabled || videoPlan.allIntraMastering) && mime.equals("video/hevc", ignoreCase = true)) {
                val tenBitForAllIntra =
                    videoPlan.useTenBit && videoPlan.colorEncoding.main10Requested
                val probedAllIntra = AllIntraHevcPolicy.probe(
                    width = videoWidth,
                    height = videoHeight,
                    frameRate = videoFps,
                    tenBit = tenBitForAllIntra,
                    preferredBitrate = videoPlan.effectiveBitrate,
                    codecInfo = selectedVideoEncoder?.candidate?.codecInfo
                )
                if (probedAllIntra != null) {
                    val clampedAllIntraBitrate = clampBitrateToSelectedEncoderCapabilities(
                        selectedEncoder = selectedVideoEncoder,
                        requestedBitrate = probedAllIntra.bitrate
                    )
                    val finalAllIntra = probedAllIntra.copy(bitrate = clampedAllIntraBitrate)
                    val applied = AllIntraHevcPolicy.applyToFormat(
                        videoFormat,
                        finalAllIntra,
                        codecInputColorEncoding
                    )
                    val allIntraResult = RecordingEncoderFormatPolicy.resolveAllIntraResult(
                        requested = true,
                        mime = mime,
                        fps = videoFps,
                        videoPlanUseTenBit = tenBitForAllIntra,
                        probed = probedAllIntra,
                        clampedBitrate = clampedAllIntraBitrate,
                        applyResult = applied
                    )
                    if (allIntraResult.active) {
                        val appliedConfig = allIntraResult.appliedConfig ?: finalAllIntra
                        allIntraActiveInternal = true
                        allIntraRetryConfig = appliedConfig
                        // All frames are keyframes → effective interval is 1 frame.
                        activeIFrameIntervalSeconds = allIntraResult.iFrameIntervalSeconds
                        activeOutputBitrate = allIntraResult.outputBitrate
                        activeQualityTierLabel = allIntraResult.qualityTierLabel ?: "ALL_INTRA"
                        Log.i(
                            TAG,
                            "All-Intra HEVC engaged: ${appliedConfig.bitrate / 1_000_000}Mbps " +
                                "profile=${appliedConfig.profile} level=${appliedConfig.level} " +
                                "tenBit=${allIntraResult.tenBit} ${videoWidth}x${videoHeight}@${videoFps}fps"
                        )
                    } else {
                        Log.w(TAG, "AllIntraHevcPolicy.applyToFormat returned false; keeping standard IPB")
                    }
                } else {
                    Log.i(
                        TAG,
                        "All-Intra HEVC unsupported on this device for " +
                            "${videoWidth}x${videoHeight}@${videoFps}fps tenBit=$tenBitForAllIntra; " +
                            "falling back to standard CBR/Main(10) IPB"
                    )
                }
            }

            // s6-f01: capture the requested transport-truth values for the start-time read-back
            // evidence (read-only snapshot; configures nothing). Requested color is the encoder input
            // color (codecInputColorEncoding); muxed color is read at finalize from the MOV atom source.
            requestedVideoProfileForEvidence = videoPlan.profile ?: -1
            requestedVideoLevelForEvidence = videoPlan.level ?: -1
            requestedVideoBitrateForEvidence = videoPlan.effectiveBitrate
            requestedColorStandardForEvidence = codecInputColorEncoding.colorStandard
            requestedColorTransferForEvidence = codecInputColorEncoding.colorTransfer
            requestedColorRangeForEvidence = codecInputColorEncoding.colorRange
            encoderTransportReadBackAvailable = false

            var videoCodecInstance = createVideoEncoder(mime, selectedVideoEncoder)
            try {
                configureVideoCodecForInputMode(videoCodecInstance, videoFormat, codecInputColorEncoding)
            } catch (throwable: Throwable) {
                runCatching { videoCodecInstance.release() }
                val advancedQualityRetryAllowed =
                    !(cinemaMasteringEnabled || videoPlan.allIntraMastering || videoPlan.qpTuningEnabled)
                val retryRequest = if (advancedQualityRetryAllowed) {
                    RecordingEncoderFormatPolicy.compatibilityRetryRequest(videoFormatRequest)
                } else {
                    null
                }
                if (retryRequest != null && videoFormatResult.spec.qualityTuning != null) {
                    Log.w(
                        TAG,
                        "Video encoder rejected advanced quality tuning; retrying without QP/B-frame hints",
                        throwable
                    )
                    videoFormatRequest = retryRequest
                    videoFormatResult = RecordingEncoderFormatPolicy.buildVideoFormat(videoFormatRequest)
                    videoFormat = videoFormatResult.format
                    allIntraRetryConfig?.let { retryConfig ->
                        val reapplied = AllIntraHevcPolicy.applyToFormat(
                            videoFormat,
                            retryConfig,
                            codecInputColorEncoding
                        )
                        if (!reapplied) {
                            allIntraActiveInternal = false
                            activeQualityTierLabel = videoPlan.qualityTier.name
                        }
                    }
                    videoCodecInstance = createVideoEncoder(mime, selectedVideoEncoder)
                    try {
                        configureVideoCodecForInputMode(videoCodecInstance, videoFormat, codecInputColorEncoding)
                    } catch (retryThrowable: Throwable) {
                        runCatching { videoCodecInstance.release() }
                        retryThrowable.addSuppressed(throwable)
                        if (selectedVideoEncoder != null) {
                            Log.w(
                                TAG,
                                "Named video encoder ${selectedVideoEncoder.candidate.codecInfo.name} failed after " +
                                    "quality-tuning fallback; retrying framework default for $mime",
                                retryThrowable
                            )
                            videoCodecInstance = MediaCodec.createEncoderByType(mime)
                            try {
                                configureVideoCodecForInputMode(
                                    videoCodecInstance,
                                    videoFormat,
                                    codecInputColorEncoding
                                )
                            } catch (fallbackThrowable: Throwable) {
                                runCatching { videoCodecInstance.release() }
                                fallbackThrowable.addSuppressed(retryThrowable)
                                throw fallbackThrowable
                            }
                        } else {
                            throw retryThrowable
                        }
                    }
                } else if (selectedVideoEncoder != null) {
                    Log.w(
                        TAG,
                        "Named video encoder ${selectedVideoEncoder.candidate.codecInfo.name} failed; " +
                            "retrying framework default for $mime",
                        throwable
                    )
                    videoCodecInstance = MediaCodec.createEncoderByType(mime)
                    try {
                        configureVideoCodecForInputMode(videoCodecInstance, videoFormat, codecInputColorEncoding)
                    } catch (fallbackThrowable: Throwable) {
                        runCatching { videoCodecInstance.release() }
                        fallbackThrowable.addSuppressed(throwable)
                        throw fallbackThrowable
                    }
                } else {
                    throw throwable
                }
            }

            // ── Audio encoder ─────────────────────────────────────────────────
            if (audioEnabled) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MIME_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, AUDIO_PROFILE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                // Assign the field BEFORE configure() so that if configure() throws, the
                // just-created native codec is still reachable by releaseInternal() (which
                // stop()/release()s audioCodecObj) instead of being orphaned/leaked.
                val audioCodec = MediaCodec.createEncoderByType(MIME_AAC)
                audioCodecObj = audioCodec
                audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            // Output creation is intentionally deferred until start(). Idle prearm prepares the
            // codec graph without publishing a MediaStore pending entry that could be discarded.
            lastOutputPath = null

            _state.value = GpuRecordingState.Ready
            Log.i(TAG, "Prepared: ${videoWidth}x${videoHeight}@${videoFps}fps " +
                "${videoPlan.effectiveBitrate/1_000_000}Mbps $videoCodec audio=$audioEnabled " +
                "dynamicRange=$activeDynamicRangeLabel simulated10bit=$simulatedTenBitFallbackActive " +
                "qualityTier=$activeQualityTierLabel gop=${String.format(Locale.US, "%.2f", activeIFrameIntervalSeconds)}s")

            encoderSurface
        } catch (e: Exception) {
            Log.e(TAG, "prepare() failed", e)
            releaseInternal()
            cleanupFailedOutputTarget()
            _state.value = GpuRecordingState.Error("Prepare failed: ${e.message}")
            null
        } finally {
            RuntimeStageTelemetry.recordDuration(
                tag = TAG,
                stage = "prepare",
                durationMs = SystemClock.elapsedRealtime() - prepareStartElapsedMs,
                slowThresholdMs = SLOW_PREPARE_STAGE_MS
            )
        }
    }

    private fun logVideoTransferRequestSupport(
        codec: MediaCodec,
        requestedTransfer: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val supportedTransfer = try {
            val inputFormat = codec.inputFormat
            if (inputFormat.containsKey(MEDIA_FORMAT_KEY_COLOR_TRANSFER_REQUEST)) {
                inputFormat.getInteger(MEDIA_FORMAT_KEY_COLOR_TRANSFER_REQUEST)
            } else {
                0
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Unable to query video color-transfer-request support", e)
            return
        }
        if (supportedTransfer == requestedTransfer) {
            Log.i(TAG, "Video color-transfer-request accepted: $requestedTransfer")
        } else {
            Log.i(
                TAG,
                "Video color-transfer-request ignored by encoder; MOV/SPS metadata will be used " +
                    "requested=$requestedTransfer accepted=$supportedTransfer"
            )
        }
    }

    /**
     * Optional hook for GPU renderers that can provide the camera sensor
     * timestamp before presenting into the encoder input surface.
     *
     * Surface-input MediaCodec timestamps are only deterministic when the
     * renderer attaches the sensor-clock PTS to the submitted frame. The
     * Vulkan path can call this before presenting an encoder frame; the drain
     * loop then prefers this queue over vendor-generated surface timestamps.
     */
    fun submitEncoderPresentationTimestamp(
        sensorTimestampNs: Long,
        eglDisplay: EGLDisplay? = null,
        eglSurface: EGLSurface? = null
    ): Long {
        val encoderPtsUs = recordingPtsSynchronizer.ptsUsForSensorTimestamp(sensorTimestampNs)
        applyEncoderPresentationTime(eglDisplay, eglSurface, encoderPtsUs)
        encoderPresentationQueue.trySend(
            RecordingFramePresentation(
                sensorTimestampNs = sensorTimestampNs,
                encoderPresentationTimeUs = encoderPtsUs,
                submittedAtNs = System.nanoTime()
            )
        )
        recordingPtsSynchronizer.recordDispatchLatency(
            sensorTimestampNs = sensorTimestampNs,
            completionNs = System.nanoTime()
        )
        return encoderPtsUs
    }

    fun queueExternalVideoFrame(frame: RawYuvFrame): Boolean {
        if (videoInputMode != RecordingVideoInputMode.BYTEBUFFER_P010) return false
        if (!isRecording.get() || _state.value !is GpuRecordingState.Recording) return false
        if (byteBufferVideoEosQueued.get()) return false
        val codec = videoCodecObj ?: return false
        val packedP010 = runCatching {
            Npv10FramePacker.packIntoDirectP010(frame)
        }.getOrElse { throwable ->
            recordByteBufferP010Drop("pack_failed", throwable)
            return false
        }
        val payloadBytes = packedP010.remaining()
        if (payloadBytes <= 0) {
            recordByteBufferP010Drop("empty_payload")
            return false
        }
        return synchronized(videoInputLock) {
            if (!isRecording.get() || byteBufferVideoEosQueued.get()) {
                return@synchronized false
            }
            val inputIndex = try {
                codec.dequeueInputBuffer(BYTEBUFFER_INPUT_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop("dequeue_stopped", e)
                return@synchronized false
            } catch (e: Exception) {
                recordByteBufferP010Drop("dequeue_failed", e)
                return@synchronized false
            }
            if (inputIndex < 0) {
                recordByteBufferP010Drop("input_unavailable")
                return@synchronized false
            }
            val inputBuffer = try {
                codec.getInputBuffer(inputIndex)
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop("input_buffer_unavailable", e)
                return@synchronized false
            }
            if (inputBuffer == null || inputBuffer.capacity() < payloadBytes) {
                recordByteBufferP010Drop(
                    "payload_too_large payload=$payloadBytes capacity=${inputBuffer?.capacity() ?: 0}"
                )
                runCatching { codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0) }
                Log.e(
                    TAG,
                    "P010 bytebuffer payload does not fit encoder input buffer: " +
                        "payload=$payloadBytes capacity=${inputBuffer?.capacity() ?: 0}"
                )
                return@synchronized false
            }
            val payload = packedP010.duplicate()
            inputBuffer.clear()
            inputBuffer.put(payload)
            val ptsUs = recordingPtsSynchronizer.ptsUsForSensorTimestamp(frame.timestamp)
            val queued = try {
                codec.queueInputBuffer(inputIndex, 0, payloadBytes, ptsUs, 0)
                true
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop("queue_stopped", e)
                false
            } catch (e: Exception) {
                recordByteBufferP010Drop("queue_failed", e)
                false
            }
            if (queued) {
                val queuedCount = byteBufferP010FramesQueued.incrementAndGet()
                byteBufferLastQueuedPtsUs = ptsUs
                recordingPtsSynchronizer.recordDispatchLatency(
                    sensorTimestampNs = frame.timestamp,
                    completionNs = System.nanoTime()
                )
                requestInitialP010SyncFrameIfNeeded(queuedCount)
            } else {
                // Drop already accounted for by the queue exception branches.
            }
            queued
        }
    }

    fun queueExternalVideoImage(image: Image): Boolean {
        if (videoInputMode != RecordingVideoInputMode.BYTEBUFFER_P010) return false
        if (!isRecording.get() || _state.value !is GpuRecordingState.Recording) return false
        if (byteBufferVideoEosQueued.get()) return false
        val sourceTimestampNs = runCatching { image.timestamp }.getOrDefault(0L)
        if (!byteBufferImageIngressTelemetry.tryAcceptCallback(sourceTimestampNs)) {
            recordByteBufferP010Drop("image_backpressure_capacity")
            runCatching { image.close() }
            return true
        }
        if (p010FirstAcceptLogged.compareAndSet(false, true)) {
            runCatching {
                NeoLogger.i(
                    "RecordingManager",
                    "P010_FIRST_ACCEPT inputMode=$videoInputMode isRecording=${isRecording.get()} " +
                        "state=${_state.value} size=${image.width}x${image.height}"
                )
            }
        }
        return try {
            byteBufferImageInputExecutor.execute {
                try {
                    queueExternalVideoImageOnInputThread(image)
                } catch (throwable: Exception) {
                    recordByteBufferP010Drop(
                        reason = "image_input_worker_failed",
                        throwable = throwable,
                        acceptedImageInput = true
                    )
                } finally {
                    runCatching { image.close() }
                    finishByteBufferImageInputTask()
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            recordByteBufferP010Drop(
                reason = "image_executor_rejected",
                throwable = e,
                acceptedImageInput = true
            )
            runCatching { image.close() }
            finishByteBufferImageInputTask()
            true
        }
    }

    private fun queueExternalVideoImageOnInputThread(image: Image) {
        if (videoInputMode != RecordingVideoInputMode.BYTEBUFFER_P010) {
            recordByteBufferP010Drop("image_mode_changed", acceptedImageInput = true)
            return
        }
        if (!isRecording.get() && _state.value !is GpuRecordingState.Stopping) {
            recordByteBufferP010Drop("image_recording_stopped", acceptedImageInput = true)
            return
        }
        if (byteBufferVideoEosQueued.get()) {
            recordByteBufferP010Drop("image_eos_already_queued", acceptedImageInput = true)
            return
        }
        val codec = videoCodecObj ?: run {
            recordByteBufferP010Drop("image_codec_unavailable", acceptedImageInput = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            p010LinearBlockAdmissionEnabled
        ) {
            queueExternalVideoImageWithLinearBlock(image, codec)
            return
        }
        synchronized(videoInputLock) {
            if (byteBufferVideoEosQueued.get()) {
                recordByteBufferP010Drop("image_eos_before_dequeue", acceptedImageInput = true)
                return@synchronized
            }
            val dequeueStartedNs = System.nanoTime()
            val inputIndex = try {
                codec.dequeueInputBuffer(BYTEBUFFER_INPUT_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop(
                    "image_dequeue_stopped",
                    e,
                    acceptedImageInput = true
                )
                return@synchronized
            } catch (e: Exception) {
                recordByteBufferP010Drop(
                    "image_dequeue_failed",
                    e,
                    acceptedImageInput = true
                )
                return@synchronized
            } finally {
                byteBufferImageIngressTelemetry.recordCodecInputWait(
                    durationNs = System.nanoTime() - dequeueStartedNs
                )
            }
            if (inputIndex < 0) {
                recordByteBufferP010Drop(
                    "image_input_unavailable",
                    acceptedImageInput = true
                )
                return@synchronized
            }
            val inputBuffer = try {
                codec.getInputBuffer(inputIndex)
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop(
                    "image_input_buffer_unavailable",
                    e,
                    acceptedImageInput = true
                )
                return@synchronized
            }
            val copyStartedNs = System.nanoTime()
            val copyResult = if (inputBuffer != null) {
                copyP010ImageIntoInputBuffer(image, inputBuffer)
            } else {
                Npv10FramePacker.P010CopyResult(
                    bytesCopied = 0,
                    path = Npv10FramePacker.P010CopyPath.FAILED
                )
            }
            byteBufferImageIngressTelemetry.recordCopy(
                path = copyResult.path,
                durationNs = System.nanoTime() - copyStartedNs
            )
            val payloadBytes = copyResult.bytesCopied
            if (payloadBytes <= 0) {
                recordByteBufferP010Drop(
                    "image_payload_unavailable capacity=${inputBuffer?.capacity() ?: 0} " +
                        "needPayloadBytes=${Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(image.width, image.height)} " +
                        "size=${image.width}x${image.height} copyPath=${copyResult.path}",
                    acceptedImageInput = true
                )
                runCatching { codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0) }
                return@synchronized
            }
            val ptsUs = recordingPtsSynchronizer.ptsUsForSensorTimestamp(
                runCatching { image.timestamp }.getOrDefault(0L)
            )
            val queued = try {
                codec.queueInputBuffer(inputIndex, 0, payloadBytes, ptsUs, 0)
                true
            } catch (e: IllegalStateException) {
                recordByteBufferP010Drop(
                    "image_queue_stopped",
                    e,
                    acceptedImageInput = true
                )
                false
            } catch (e: Exception) {
                recordByteBufferP010Drop(
                    "image_queue_failed",
                    e,
                    acceptedImageInput = true
                )
                false
            }
            if (queued) {
                byteBufferImageIngressTelemetry.recordQueued()
                val queuedCount = byteBufferP010FramesQueued.incrementAndGet()
                byteBufferLastQueuedPtsUs = ptsUs
                recordingPtsSynchronizer.recordDispatchLatency(
                    sensorTimestampNs = runCatching { image.timestamp }.getOrDefault(0L),
                    completionNs = System.nanoTime()
                )
                requestInitialP010SyncFrameIfNeeded(queuedCount)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queueExternalVideoImageWithLinearBlock(
        image: Image,
        codec: MediaCodec
    ) {
        synchronized(videoInputLock) {
            if (!p010BlockModelFailureState.canQueue()) {
                recordByteBufferP010Drop(
                    "linear_block_codec_failed",
                    acceptedImageInput = true
                )
                return@synchronized
            }
            if (byteBufferVideoEosQueued.get()) {
                recordByteBufferP010Drop(
                    "image_eos_before_linear_block",
                    acceptedImageInput = true
                )
                return@synchronized
            }
            val payloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(image.width, image.height)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val linearBlock = try {
                MediaCodec.LinearBlock.obtain(payloadBytes, arrayOf(codec.name))
            } catch (e: Exception) {
                recordByteBufferP010Drop(
                    "linear_block_obtain_failed",
                    e,
                    acceptedImageInput = true
                )
                return@synchronized
            } ?: run {
                recordByteBufferP010Drop(
                    "linear_block_obtain_returned_null",
                    acceptedImageInput = true
                )
                return@synchronized
            }
            try {
                val mappedBuffer = try {
                    linearBlock.map()
                } catch (e: Exception) {
                    recordByteBufferP010Drop(
                        "linear_block_map_failed",
                        e,
                        acceptedImageInput = true
                    )
                    return@synchronized
                }
                val copyStartedNs = System.nanoTime()
                val copyResult = copyP010ImageIntoInputBuffer(image, mappedBuffer)
                byteBufferImageIngressTelemetry.recordCopy(
                    path = copyResult.path,
                    durationNs = System.nanoTime() - copyStartedNs
                )
                if (copyResult.bytesCopied != payloadBytes) {
                    recordByteBufferP010Drop(
                        "linear_block_payload_unavailable " +
                            "expected=$payloadBytes actual=${copyResult.bytesCopied}",
                        acceptedImageInput = true
                    )
                    return@synchronized
                }

                val waitStartedNs = System.nanoTime()
                val inputIndex = try {
                    blockModelInputIndices.poll(
                        BYTEBUFFER_INPUT_TIMEOUT_US,
                        TimeUnit.MICROSECONDS
                    )
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    recordByteBufferP010Drop(
                        "linear_block_input_wait_interrupted",
                        e,
                        acceptedImageInput = true
                    )
                    return@synchronized
                } finally {
                    byteBufferImageIngressTelemetry.recordCodecInputWait(
                        durationNs = System.nanoTime() - waitStartedNs
                    )
                }
                if (inputIndex == null) {
                    recordByteBufferP010Drop(
                        "linear_block_input_unavailable",
                        acceptedImageInput = true
                    )
                    return@synchronized
                }
                val sensorTimestampNs = runCatching { image.timestamp }.getOrDefault(0L)
                val ptsUs = recordingPtsSynchronizer.ptsUsForSensorTimestamp(sensorTimestampNs)
                if (!p010BlockModelFailureState.canQueue()) {
                    recordByteBufferP010Drop(
                        "linear_block_codec_failed_before_queue",
                        acceptedImageInput = true
                    )
                    return@synchronized
                }
                val queued = try {
                    codec.getQueueRequest(inputIndex)
                        .setLinearBlock(linearBlock, 0, payloadBytes)
                        .setPresentationTimeUs(ptsUs)
                        .queue()
                    true
                } catch (e: IllegalStateException) {
                    recordByteBufferP010Drop(
                        "linear_block_queue_stopped",
                        e,
                        acceptedImageInput = true
                    )
                    p010BlockModelFailureState.markFailed()
                    blockModelInputIndices.clear()
                    false
                } catch (e: Exception) {
                    recordByteBufferP010Drop(
                        "linear_block_queue_failed",
                        e,
                        acceptedImageInput = true
                    )
                    p010BlockModelFailureState.markFailed()
                    blockModelInputIndices.clear()
                    false
                }
                if (queued) {
                    p010LinearBlockFramesQueued.incrementAndGet()
                    byteBufferImageIngressTelemetry.recordQueued()
                    val queuedCount = byteBufferP010FramesQueued.incrementAndGet()
                    byteBufferLastQueuedPtsUs = ptsUs
                    recordingPtsSynchronizer.recordDispatchLatency(
                        sensorTimestampNs = sensorTimestampNs,
                        completionNs = System.nanoTime()
                    )
                    requestInitialP010SyncFrameIfNeeded(queuedCount)
                }
            } finally {
                runCatching { linearBlock.recycle() }
            }
        }
    }

    private fun requestInitialP010SyncFrameIfNeeded(queuedCount: Long) {
        if (queuedCount != 1L) return
        requestVideoSyncFrame(reason = "p010-first-input", force = true)
        runCatching {
            NeoLogger.i(
                "RecordingManager",
                "P010_FIRST_INPUT_SYNC_FRAME_REQUESTED queued=$queuedCount " +
                    "codec=${videoCodecObj?.name ?: "unknown"}"
            )
        }
    }

    @Volatile
    private var liveEnhanceUniformsFloats: FloatArray? = null

    /**
     * Pushed from the preview path so recorded P010 pixels receive the SAME GPU enhancement as the
     * live preview (WYSIWYG). Null (default) disables live enhancement. [uniforms] is
     * GpuImageEnhancementUniforms.toFloatArray().
     */
    fun setLiveEnhanceUniforms(uniforms: FloatArray?) {
        liveEnhanceUniformsFloats = uniforms
    }

    private fun copyP010ImageIntoInputBuffer(
        image: Image,
        inputBuffer: ByteBuffer
    ): Npv10FramePacker.P010CopyResult {
        val copyResult = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(image, inputBuffer)
        val uniforms = liveEnhanceUniformsFloats
        if (uniforms != null &&
            copyResult.path != Npv10FramePacker.P010CopyPath.FAILED &&
            copyResult.bytesCopied > 0
        ) {
            // HIGH-END: best-effort in-place GPU enhancement of the recorded P010 so recorded pixels
            // match the preview look. On ANY failure the raw copy already in inputBuffer stands, so
            // recording can never break — worst case the frame is simply un-enhanced.
            runCatching {
                Npv10ExportGpuAccelerator.enhanceP010InPlace(
                    buffer = inputBuffer,
                    previous = null,
                    width = image.width,
                    height = image.height,
                    uniforms = uniforms
                )
                // The codec queues by explicit [0, bytesCopied); restore the buffer window defensively.
                inputBuffer.position(0)
                inputBuffer.limit(copyResult.bytesCopied)
            }
        }
        return copyResult
    }

    private fun finishByteBufferImageInputTask() {
        byteBufferImageIngressTelemetry.release()
        val pending = byteBufferImageIngressTelemetry
            .snapshot(encodedFrames = encodedVideoSampleCount)
            .inFlightFrames
        if (pending == 0) {
            synchronized(byteBufferImageInputIdleLock) {
                byteBufferImageInputIdleLock.notifyAll()
            }
        }
    }

    private fun awaitByteBufferImageInputIdle(
        timeoutMs: Long = P010_IMAGE_INPUT_DRAIN_TIMEOUT_MS
    ): Boolean {
        val startMs = SystemClock.elapsedRealtime()
        synchronized(byteBufferImageInputIdleLock) {
            while (
                byteBufferImageIngressTelemetry
                    .snapshot(encodedFrames = encodedVideoSampleCount)
                    .inFlightFrames > 0 &&
                SystemClock.elapsedRealtime() - startMs < timeoutMs &&
                !Thread.currentThread().isInterrupted
            ) {
                byteBufferImageInputIdleLock.wait(8L)
            }
        }
        val pending = byteBufferImageIngressTelemetry
            .snapshot(encodedFrames = encodedVideoSampleCount)
            .inFlightFrames
        if (pending > 0) {
            Log.w(TAG, "Timed out waiting for P010 bytebuffer image input idle pending=$pending")
        }
        return pending == 0
    }

    private fun recordByteBufferP010Drop(
        reason: String,
        throwable: Throwable? = null,
        acceptedImageInput: Boolean = false
    ) {
        if (acceptedImageInput) {
            byteBufferImageIngressTelemetry.recordProcessingDrop()
        }
        val drops = byteBufferP010FrameDrops.incrementAndGet()
        if (drops <= 5L || drops % 30L == 0L) {
            val message =
                "P010 bytebuffer frame drop reason=$reason drops=$drops " +
                    "queued=${byteBufferP010FramesQueued.get()}"
            if (throwable == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, throwable)
            }
            // Mirror to NeoLogger so the reason is pullable on devices that suppress third-party
            // android.util.Log. Backpressure drops are NORMAL preview-protection flow control (not
            // failures) so they log at info level; only genuine failures log at critical.
            runCatching {
                if (reason.startsWith("image_backpressure")) {
                    NeoLogger.i("RecordingManager", message)
                } else {
                    NeoLogger.critical("RecordingManager", message)
                }
            }
        }
    }

    private fun applyEncoderPresentationTime(
        eglDisplay: EGLDisplay?,
        eglSurface: EGLSurface?,
        encoderPtsUs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return
        if (eglDisplay == null || eglSurface == null) return
        runCatching {
            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                encoderPtsUs.coerceAtLeast(0L) * 1_000L
            )
        }.onFailure {
            Log.w(TAG, "eglPresentationTimeANDROID failed for encoder frame", it)
        }
    }

    // START - begin encoding after the renderer is attached to the input surface.

    fun start() {
        if (_state.value != GpuRecordingState.Ready) {
            Log.w(TAG, "start() called when not Ready: ${_state.value}")
            return
        }
        val startElapsedMs = SystemClock.elapsedRealtime()
        try {
            ensureOutputTargetForRecordingStart()
            recordingStartNs = System.nanoTime()
            recordingPtsSynchronizer.reset(initialLatencyCompensationUs = 0L)
            clearEncoderPresentationQueue()
            recordingStartEpochMs = System.currentTimeMillis()
            recordingStartProcessCpuMs = Process.getElapsedCpuTime()
            finalRecordingCpuUsagePercentForEvidence = null
            finalActiveRecordingDurationUsForEvidence = 0L
            clipTimingStartNsForEvidence = 0L
            clipTimingStartPausedUsForEvidence = 0L
            stopRequestedNsForEvidence = 0L
            finalStrictFrameGateDurationUsForEvidence = 0L
            audioTrackDeadlineNs = RecordingAudioStartupPolicy.audioTrackDeadlineNs(
                recordingStartNs = recordingStartNs,
                audioEnabled = audioEnabled
            )
            isRecording.set(true)
            muxerStarted.set(false)
            startMuxerWriter()
            videoTrackIndex = -1
            audioTrackIndex = -1
            firstVideoPtsUs = -1L
            firstOutputVideoPtsUs = -1L
            lastVideoPtsUs = -1L
            lastAudioPtsUs = -1L
            primedAudioPcmFrames = 0L
            synchronized(pauseTimelineLock) {
                pauseTimeline = RecordingPauseTimeline()
            }
            videoDrainComplete.set(false)
            audioDrainComplete.set(!audioEnabled)
            audioCodecTerminalShutdown.set(false)

            // Start both codecs; they now accept input.
            videoCodecObj?.start()
            applyRuntimeBitrateHints()
            audioCodecObj?.start()
            if (audioEnabled) {
                primedAudioPcmFrames =
                    audioCodecObj?.let(::primeAudioEncoderTrack) ?: 0L
            }

            // Video drain loop: runs on IO thread, drains encoded video buffers
            // into the muxer. Audio gets a short grace period to publish its
            // format, then we fall back to a video-only file instead of
            // discarding early video samples for short recordings.
            scope.launch(videoEncoderDrainDispatcher) { drainVideoEncoder() }

            if (audioEnabled) {
                scope.launch(audioEncoderDrainDispatcher) { recordAndEncodeAudio() }
            }

            // Duration ticker
            scope.launch { tickDuration() }

            _state.value = GpuRecordingState.Recording
            Log.i(TAG, "Recording started -> $lastOutputPath")
        } catch (e: Exception) {
            Log.e(TAG, "start() failed", e)
            isRecording.set(false)
            releaseInternal()
            cleanupFailedOutputTarget()
            _state.value = GpuRecordingState.Error("Start failed: ${e.message}")
        } finally {
            RuntimeStageTelemetry.recordDuration(
                tag = TAG,
                stage = "start",
                durationMs = SystemClock.elapsedRealtime() - startElapsedMs,
                slowThresholdMs = SLOW_START_STAGE_MS
            )
        }
    }

    private fun ensureOutputTargetForRecordingStart() {
        if (outputTarget != null && movSessionController != null) return
        if (outputTarget != null || movSessionController != null) {
            cleanupFailedOutputTarget()
            movSessionController = null
        }
        outputTarget = createOutputTarget()
        val target = outputTarget ?: throw IOException("Output target is null after creation")
        lastOutputPath = target.uri?.toString() ?: target.file?.absolutePath ?: target.writablePath()
        val availableStorageBytes = resolveOutputAvailableStorageBytes(target)
        val preallocationPlan = RecordingPreallocationPolicy.processed(availableStorageBytes)
        Log.i(
            TAG,
            "Processed MOV preallocation plan available=$availableStorageBytes " +
                "requested=${preallocationPlan.requestedBytes} " +
                "reserved=${preallocationPlan.reservationBytes} reason=${preallocationPlan.reason}"
        )
        movSessionController = RecordingMovSessionController(
            createContainerBackend(target.selection, preallocationPlan)
        ).also { controller ->
            controller.open(
                outputPath = target.writablePath(),
                containerLabel = target.descriptor.containerLabel,
                orientationHintDegrees = orientationHintDegrees
            )
        }
    }

    private fun applyRuntimeBitrateHints() {
        val codec = videoCodecObj ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val nowMs = SystemClock.uptimeMillis()
        val decision = RecordingRuntimeHintPolicy.decide(
            width = videoWidth,
            height = videoHeight,
            hdrMode = videoHdrMode,
            tenBitEnabled = tenBitEnabled,
            sceneMetadataDescription = sceneMetadataDescription,
            activeOutputBitrate = activeOutputBitrate,
            lastHintedBitrate = lastHintedBitrate,
            nowMs = nowMs,
            lastSyncFrameRequestUptimeMs = lastSyncFrameRequestUptimeMs,
            syncFrameBitrateDeltaRatio = SYNC_FRAME_BITRATE_DELTA_RATIO,
            syncFrameRequestIntervalMs = SYNC_FRAME_REQUEST_INTERVAL_MS
        )
        if (!decision.shouldPushBitrate) return
        runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, activeOutputBitrate)
                }
            )
            lastBitrateHintUptimeMs = nowMs
            lastHintedBitrate = activeOutputBitrate
            }.onFailure { Log.w(TAG, "Failed to push runtime video bitrate", it) }
        if (decision.shouldRequestSyncFrame) {
            requestVideoSyncFrame(reason = "runtime-bitrate", nowMs = nowMs)
        }
    }

    fun pause(): Boolean {
        if (_state.value != GpuRecordingState.Recording) {
            Log.w(TAG, "pause() called when not recording: ${_state.value}")
            return false
        }
        synchronized(pauseTimelineLock) {
            pauseTimeline = RecordingPauseTimelinePolicy.pause(
                timeline = pauseTimeline,
                nowNs = System.nanoTime()
            )
        }
        _state.value = GpuRecordingState.Paused
        Log.i(TAG, "Recording paused")
        return true
    }

    fun resume(): Boolean {
        if (_state.value != GpuRecordingState.Paused) {
            Log.w(TAG, "resume() called when not paused: ${_state.value}")
            return false
        }
        finishPauseWindowIfNeeded()
        requestVideoSyncFrame(reason = "resume", force = true)
        _state.value = GpuRecordingState.Recording
        Log.i(TAG, "Recording resumed")
        return true
    }

    private fun finishPauseWindowIfNeeded(nowNs: Long = System.nanoTime()) {
        synchronized(pauseTimelineLock) {
            pauseTimeline = RecordingPauseTimelinePolicy.resume(
                timeline = pauseTimeline,
                nowNs = nowNs
            )
        }
    }

    private fun shouldWriteEncoderSample(): Boolean =
        RecordingPauseTimelinePolicy.shouldWriteSample(pauseTimeline)

    private fun outputPresentationTimeUs(normalizedPresentationTimeUs: Long): Long =
        RecordingPauseTimelinePolicy.outputPresentationTimeUs(
            normalizedPresentationTimeUs = normalizedPresentationTimeUs,
            timeline = pauseTimeline
        )

    private fun clearEncoderPresentationQueue() {
        while (encoderPresentationQueue.tryReceive().isSuccess) {
            // Drain stale frame PTS events from the previous recording session.
        }
    }

    private fun nextEncoderPresentationTimeUs(fallbackCodecPtsUs: Long): Long =
        encoderPresentationQueue.tryReceive()
            .getOrNull()
            ?.encoderPresentationTimeUs
            ?: fallbackCodecPtsUs

    private fun shouldUseConstantMuxCadence(): Boolean =
        // Realtime internal hardware-import Surface input is already frame-paced by
        // Camera2/Vulkan/MediaCodec. Write that route as CFR so startup/stop jitter does not become
        // MOV DTS gaps or false frame-drop evidence. BYTEBUFFER_P010 and offscreen AHB/EGL keep real
        // sensor/eglPresentationTime PTS so genuinely slow readout or GPU processing is not time-compressed.
        realtimeSurfaceCadenceEnabled && videoInputMode == RecordingVideoInputMode.SURFACE

    private fun requestVideoSyncFrame(
        reason: String,
        nowMs: Long = SystemClock.uptimeMillis(),
        force: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        if (!force && nowMs - lastSyncFrameRequestUptimeMs < SYNC_FRAME_REQUEST_INTERVAL_MS) return
        val codec = videoCodecObj ?: return
        runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
            lastSyncFrameRequestUptimeMs = nowMs
        }.onFailure {
            Log.w(TAG, "Failed to request sync frame for $reason", it)
        }
    }

    private fun signalVideoEndOfStream() {
        val codec = videoCodecObj ?: return
        if (videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010) {
            synchronized(videoInputLock) {
                if (byteBufferVideoEosQueued.getAndSet(true)) return
                // Capture the full P010 ingress breakdown at stop BEFORE the EOS enqueue attempt,
                // so it survives even if the EOS input-buffer dequeue times out (saturated encoder).
                runCatching {
                    val eosIngress =
                        byteBufferImageIngressTelemetry.snapshot(encodedFrames = encodedVideoSampleCount)
                    NeoLogger.critical(
                        "RecordingManager",
                        "P010_EOS_INGRESS callbacks=${eosIngress.callbackFrames} " +
                            "accepted=${eosIngress.acceptedFrames} queued=${byteBufferP010FramesQueued.get()} " +
                            "encoded=${eosIngress.encodedFrames} admissionRejected=${eosIngress.admissionRejectedFrames} " +
                            "processingDropped=${eosIngress.processingDroppedFrames} inFlight=${eosIngress.inFlightFrames} " +
                            "peakInFlight=${eosIngress.peakInFlightFrames} admissionCapacity=${eosIngress.admissionCapacity} " +
                            "avgCopyUs=${eosIngress.averageCopyUs} maxCopyUs=${eosIngress.maxCopyUs} " +
                            "nativeCopies=${eosIngress.nativeDirectCopies} " +
                            "directCopies=${eosIngress.directBufferCopies} " +
                            "avgCodecWaitUs=${eosIngress.averageCodecInputWaitUs} " +
                            "maxCodecWaitUs=${eosIngress.maxCodecInputWaitUs} copyFailures=${eosIngress.copyFailures} " +
                            "videoTrackIndex=$videoTrackIndex muxerStarted=${muxerStarted.get()}"
                    )
                }
                val ptsUs = maxOf(
                    byteBufferLastQueuedPtsUs + 1L,
                    ((System.nanoTime() - recordingStartNs) / 1_000L).coerceAtLeast(0L)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    p010LinearBlockAdmissionEnabled
                ) {
                    queueLinearBlockEndOfStream(codec, ptsUs)
                } else {
                    var inputIndex = -1
                    var waitedUs = 0L
                    val timeoutUs = DRAIN_TIMEOUT_US * 4
                    while (inputIndex < 0 &&
                        waitedUs < 500_000L &&
                        !Thread.currentThread().isInterrupted
                    ) {
                        inputIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputIndex < 0) {
                            Thread.sleep(4L)
                            waitedUs += timeoutUs + 4_000L
                        }
                    }
                    if (inputIndex < 0) {
                        // Saturated encoder can't take the EOS marker. Do NOT discard the clip:
                        // flag the drain to finish on its own and finalize the frames already
                        // encoded and muxed (no-downgrade: saves real frames, never an empty file).
                        byteBufferEosInputUnavailable = true
                        runCatching {
                            NeoLogger.critical(
                                "RecordingManager",
                                "P010_EOS_INPUT_UNAVAILABLE finalizing already-encoded frames " +
                                    "encoded=$encodedVideoSampleCount queued=${byteBufferP010FramesQueued.get()} " +
                                    "muxerStarted=${muxerStarted.get()} videoTrackIndex=$videoTrackIndex"
                            )
                        }
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
                val ingressSnapshot =
                    byteBufferImageIngressTelemetry.snapshot(encodedFrames = encodedVideoSampleCount)
                Log.i(
                    TAG,
                    "P010 bytebuffer EOS callbacks=${ingressSnapshot.callbackFrames} " +
                        "accepted=${ingressSnapshot.acceptedFrames} " +
                        "queued=${byteBufferP010FramesQueued.get()} " +
                        "encoded=${ingressSnapshot.encodedFrames} " +
                        "admissionCapacity=${ingressSnapshot.admissionCapacity} " +
                        "peakInFlight=${ingressSnapshot.peakInFlightFrames} " +
                        "admissionRejected=${ingressSnapshot.admissionRejectedFrames} " +
                        "processingDropped=${ingressSnapshot.processingDroppedFrames} " +
                        "nativeCopies=${ingressSnapshot.nativeDirectCopies} " +
                        "directCopies=${ingressSnapshot.directBufferCopies} " +
                        "copyFailures=${ingressSnapshot.copyFailures} " +
                        "avgCopyUs=${ingressSnapshot.averageCopyUs} " +
                        "maxCopyUs=${ingressSnapshot.maxCopyUs} " +
                        "avgCodecWaitUs=${ingressSnapshot.averageCodecInputWaitUs} " +
                        "maxCodecWaitUs=${ingressSnapshot.maxCodecInputWaitUs} " +
                        "linearBlockCopyFree=$p010LinearBlockCopyFreeCompatible " +
                        "linearBlockQueued=${p010LinearBlockFramesQueued.get()} " +
                        "drops=${byteBufferP010FrameDrops.get()} ptsUs=$ptsUs"
                )
            }
            return
        }
        codec.signalEndOfInputStream()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queueLinearBlockEndOfStream(codec: MediaCodec, ptsUs: Long) {
        check(p010BlockModelFailureState.canQueue()) {
            "P010 LinearBlock codec failed before EOS"
        }
        val inputIndex = blockModelInputIndices.poll(
            BYTEBUFFER_INPUT_TIMEOUT_US,
            TimeUnit.MICROSECONDS
        ) ?: throw IllegalStateException(
            "Timed out waiting for P010 LinearBlock EOS input slot"
        )
        check(p010BlockModelFailureState.canQueue()) {
            "P010 LinearBlock codec failed before EOS queue"
        }
        val eosBlock = MediaCodec.LinearBlock.obtain(1, arrayOf(codec.name))
            ?: throw IllegalStateException(
                "P010 LinearBlock EOS allocation returned null"
            )
        try {
            codec.getQueueRequest(inputIndex)
                .setLinearBlock(eosBlock, 0, 0)
                .setPresentationTimeUs(ptsUs)
                .setFlags(MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                .queue()
        } finally {
            runCatching { eosBlock.recycle() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STOP — drain, finalize the recording container, save to MediaStore
    //
    // Call after the active renderer detaches the encoder surface so no more
    // frames are submitted while EOS is being signalled.
    //
    // Returns the output file path, or null on error.
    // ─────────────────────────────────────────────────────────────────────────

    // Stop recording after the active renderer detaches the encoder surface.
    // The method drains the codecs, finalizes the recording container, and publishes the result.
    fun stop(): String? {
        if (_state.value != GpuRecordingState.Recording && _state.value != GpuRecordingState.Paused) {
            Log.w(TAG, "stop() called when not recording: ${_state.value}")
            return null
        }

        val stopStartElapsedMs = SystemClock.elapsedRealtime()
        val stopMethodEnteredNs = System.nanoTime()
        val stoppedAtNsForEvidence = synchronized(pauseTimelineLock) {
            stopRequestedNsForEvidence
                .takeIf { it >= recordingStartNs && it <= stopMethodEnteredNs }
                ?: stopMethodEnteredNs
        }
        finishPauseWindowIfNeeded(nowNs = stoppedAtNsForEvidence)
        synchronized(pauseTimelineLock) {
            finalActiveRecordingDurationUsForEvidence = activeRecordingDurationUsForEvidence(
                recordingStartNs = recordingStartNs,
                stoppedAtNs = stoppedAtNsForEvidence,
                accumulatedPausedUs = pauseTimeline.accumulatedPausedUs
            )
            finalStrictFrameGateDurationUsForEvidence = clipTimingDurationUsForEvidence(
                recordingStartNs = recordingStartNs,
                clipTimingStartNs = clipTimingStartNsForEvidence,
                stoppedAtNs = stoppedAtNsForEvidence,
                accumulatedPausedUs = pauseTimeline.accumulatedPausedUs,
                accumulatedPausedAtClipTimingStartUs = clipTimingStartPausedUsForEvidence
            )
        }
        val byteBufferP010InputActive = videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010
        if (!byteBufferP010InputActive) {
            isRecording.set(false)
        }
        _state.value = GpuRecordingState.Stopping
        tryStartMuxer()

        return try {
            // Signal EOS after the encoder input surface has been detached upstream.
            try {
                if (byteBufferP010InputActive) {
                    if (!awaitByteBufferImageInputIdle()) {
                        // Saturated encoder left input copy tasks in flight. Do NOT abort the clip:
                        // signalVideoEndOfStream() below fences further input (sets the EOS flag),
                        // and we finalize the frames already encoded. Late copies are dropped after
                        // the EOS fence, which is correct (you cannot add frames past end-of-stream).
                        runCatching {
                            NeoLogger.critical(
                                "RecordingManager",
                                "P010 image input not idle before EOS; proceeding to finalize already-encoded frames"
                            )
                        }
                    }
                    isRecording.set(false)
                }
                signalVideoEndOfStream()
            } catch (e: Exception) {
                Log.w(TAG, "Video EOS signal failed", e)
                if (videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010) {
                    // #8 fix: fence input and terminate the block-model drain UNCONDITIONALLY — even when the
                    // clip is NOT salvageable. Otherwise drainBlockModelVideoEncoder never sees EOS /
                    // isRecording=false, polls forever, and holds the single slot of videoEncoderDrainDispatcher,
                    // permanently stalling the drain of EVERY subsequent BYTEBUFFER_P010 recording for the whole
                    // process. Set the drain-termination flags first, then still rethrow below to discard an
                    // empty clip. (Mirrors the non-block EOS-input-unavailable path.)
                    byteBufferEosInputUnavailable = true
                    byteBufferVideoEosQueued.set(true)
                    isRecording.set(false)
                    // No-downgrade salvage: if real frames were already encoded and the muxer is
                    // live, finalize them instead of discarding the clip. Only abort when nothing
                    // usable was produced (so an empty file is never published).
                    val salvageable = encodedVideoSampleCount > 0L &&
                        muxerStarted.get() && videoTrackIndex >= 0
                    if (!salvageable) {
                        throw e
                    }
                    runCatching {
                        NeoLogger.critical(
                            "RecordingManager",
                            "P010_EOS_SALVAGE finalizing $encodedVideoSampleCount encoded frames after " +
                                "EOS error=${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }

            // Stop audio capture and wait for AAC encoder to drain
            stopAudioCapture(logFailure = true)

            // ════════════════════════════════════════════════════════════════════
            // OPTIMIZED: Use exponential backoff instead of fixed polling
            // This reduces CPU wake-ups while waiting for tracks to initialize
            // ════════════════════════════════════════════════════════════════════
            // ARCHITECTURAL FIX: Aggressive sub-second termination with 10ms cap
            var trackWaitMs = 0L
            var trackBackoff = 2L
            while (
                trackWaitMs < RecordingStopFinalizationPolicy.TRACK_WAIT_BUDGET_MS &&
                videoTrackIndex < 0 &&
                !Thread.currentThread().isInterrupted
            ) {
                val sleepMs = trackBackoff.coerceAtMost(
                    RecordingStopFinalizationPolicy.TRACK_WAIT_BACKOFF_CAP_MS
                )
                Thread.sleep(sleepMs)
                trackWaitMs += sleepMs
                trackBackoff = (trackBackoff * 3 / 2 + 1).coerceAtMost(
                    RecordingStopFinalizationPolicy.TRACK_WAIT_BACKOFF_CAP_MS
                )
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Stop interrupted")
            if (videoTrackIndex >= 0 && !muxerStarted.get()) {
                tryStartMuxer(forceVideoOnly = true)
            }

            // ════════════════════════════════════════════════════════════════════
            // OPTIMIZED: Exponential backoff for drain completion
            // Starts with short waits (fast response) and backs off (less CPU)
            // ════════════════════════════════════════════════════════════════════
            // FIX BUG7: Use proper exponential backoff cap of 100ms (not stuck at 10ms)
            var drainWaitMs = 0L
            var drainBackoff = 2L
            while (
                drainWaitMs < RecordingStopFinalizationPolicy.CODEC_DRAIN_BUDGET_MS &&
                (!videoDrainComplete.get() || !audioDrainComplete.get()) &&
                !Thread.currentThread().isInterrupted
            ) {
                val sleepMs = drainBackoff.coerceAtMost(
                    RecordingStopFinalizationPolicy.DRAIN_WAIT_BACKOFF_CAP_MS
                )
                Thread.sleep(sleepMs)
                drainWaitMs += sleepMs
                drainBackoff = (drainBackoff * 3 / 2 + 1).coerceAtMost(
                    RecordingStopFinalizationPolicy.DRAIN_WAIT_BACKOFF_CAP_MS
                )
            }

            // Wait for muxer queue to drain
            // FIX BUG7: Allow proper exponential backoff with 100ms cap (not stuck at 10ms)
            var muxerWaitMs = 0L
            var muxerBackoff = 2L
            while (
                muxerWaitMs < RecordingStopFinalizationPolicy.MUXER_QUEUE_DRAIN_BUDGET_MS &&
                hasPendingMuxerSamples() &&
                !Thread.currentThread().isInterrupted
            ) {
                val sleepMs = muxerBackoff.coerceAtMost(
                    RecordingStopFinalizationPolicy.DRAIN_WAIT_BACKOFF_CAP_MS
                )
                Thread.sleep(sleepMs)
                muxerWaitMs += sleepMs
                muxerBackoff = (muxerBackoff * 3 / 2 + 1).coerceAtMost(
                    RecordingStopFinalizationPolicy.DRAIN_WAIT_BACKOFF_CAP_MS
                )
            }

            if (!muxerStarted.get()) {
                tryStartMuxer()
            }

            val strictStopFrameGateEvidence = resolveStrictStopFrameGateEvidence()

            // Wait for muxer writer job to complete before closing container
            // This ensures all queued samples are written to the file.
            // The job will exit when isRecording=false AND there are no pending samples.
            var closeDecision = RecordingStopFinalizationPolicy.resolveCloseDecision(
                muxerStarted = muxerStarted.get(),
                videoTrackIndex = videoTrackIndex,
                pendingSamples = pendingMuxerSamples(),
                writerActive = muxerWriterJob?.isActive == true,
                encodedVideoSamples = encodedVideoSampleCount,
                // Stop finalization keeps real encoded clips and reports strict frame-count shortfalls
                // as evidence instead of deleting the gallery output. Broken clips still abort when
                // encodedVideoSamples<=0 or the muxer track never exists.
                strictFrameCountRequired = strictStopFrameGateEvidence.required,
                strictMinimumVideoSamples = strictStopFrameGateEvidence.expectedVideoSamples
            )
            if (closeDecision.waitForWriter) {
                val muxerWaitStartMs = SystemClock.uptimeMillis()
                var jobWaitBackoff = 2L
                while (
                    (muxerWriterJob?.isActive == true || hasPendingMuxerSamples()) &&
                    SystemClock.uptimeMillis() - muxerWaitStartMs <
                    RecordingStopFinalizationPolicy.WRITER_COMPLETION_BUDGET_MS
                ) {
                    val sleepMs = jobWaitBackoff.coerceAtMost(
                        RecordingStopFinalizationPolicy.WRITER_WAIT_BACKOFF_CAP_MS
                    )
                    Thread.sleep(sleepMs)
                    jobWaitBackoff = (jobWaitBackoff * 3 / 2 + 1).coerceAtMost(
                        RecordingStopFinalizationPolicy.WRITER_WAIT_BACKOFF_CAP_MS
                    )
                }
            }
            closeDecision = RecordingStopFinalizationPolicy.resolveCloseDecision(
                muxerStarted = muxerStarted.get(),
                videoTrackIndex = videoTrackIndex,
                pendingSamples = pendingMuxerSamples(),
                writerActive = muxerWriterJob?.isActive == true,
                encodedVideoSamples = encodedVideoSampleCount,
                // Stop finalization keeps real encoded clips and reports strict frame-count shortfalls
                // as evidence instead of deleting the gallery output. Broken clips still abort when
                // encodedVideoSamples<=0 or the muxer track never exists.
                strictFrameCountRequired = strictStopFrameGateEvidence.required,
                strictMinimumVideoSamples = strictStopFrameGateEvidence.expectedVideoSamples
            )
            // Decisive observe-only verdict (eventually-flushed via NeoLogger, not synchronous):
            // separates "frames never arrived" (callbackFrames==0) from "arrived but rejected"
            // (accepted==0/queued==0) from "encoded nothing" (queued>0, encoded==0) so the next
            // on-device run names the exact failing stage behind a cleanupFailure.
            runCatching {
                val stopIngress =
                    byteBufferImageIngressTelemetry.snapshot(encodedFrames = encodedVideoSampleCount)
                NeoLogger.critical(
                    "RecordingManager",
                    "RECORDING_STOP_VERDICT inputMode=$videoInputMode muxerStarted=${muxerStarted.get()} " +
                        "videoTrackIndex=$videoTrackIndex pendingSamples=${pendingMuxerSamples()} " +
                        "encodedVideoSampleCount=$encodedVideoSampleCount " +
                        "callbackFrames=${stopIngress.callbackFrames} " +
                        "acceptedFrames=${stopIngress.acceptedFrames} " +
                        "queuedFrames=${stopIngress.queuedFrames} " +
                        "encodedFrames=${stopIngress.encodedFrames} " +
                        "admissionRejectedFrames=${stopIngress.admissionRejectedFrames} " +
                        "processingDroppedFrames=${stopIngress.processingDroppedFrames} " +
                        "inFlightFrames=${stopIngress.inFlightFrames} " +
                        "wireProfile=$muxedVideoProfileForEvidence wireTenBit=${encoderOutputTenBitOrNull()} " +
                        "abortBackend=${closeDecision.abortBackend} " +
                        "closeBackend=${closeDecision.closeBackend} " +
                        "waitForWriter=${closeDecision.waitForWriter} " +
                        "strictFrameGate=${strictStopFrameGateEvidence.required} " +
                        "strictExpectedSamples=${strictStopFrameGateEvidence.expectedVideoSamples} " +
                        "strictEvidenceDurationUs=${strictStopFrameGateEvidence.evidenceDurationUs} " +
                        "strictClipWindowDurationUs=$finalStrictFrameGateDurationUsForEvidence " +
                        "strictRecordingActiveDurationUs=$finalActiveRecordingDurationUsForEvidence " +
                        "strictGpuProcessingPath=${strictStopFrameGateEvidence.gpuProcessingPath} " +
                        "strictEvidenceMode=${strictStopFrameGateEvidence.evidenceMode}"
                )
            }
            if (closeDecision.waitForWriter) {
                Log.w(
                    TAG,
                    "Muxer writer still active or pending after " +
                        "${RecordingStopFinalizationPolicy.WRITER_COMPLETION_BUDGET_MS}ms; aborting partial MOV"
                )
            }

            // SALVAGE-ON-STOP-FAILURE (record-then-develop): the private develop master is the SOLE
            // copy of this recording (never published to MediaStore). If the stop decision would ABORT
            // it (writer did not drain within WRITER_COMPLETION_BUDGET_MS) but a real, finalisable
            // video stream already exists, finalise the container with close() (writes the moov)
            // instead of aborting — a short valid clip is retained and enumerates in the converter
            // list, far better than losing the recording. close() runs under muxerLock and
            // RecordingMovSessionController.writeSample() is guarded by `opened`, so a late writer
            // packet after close is a safe no-op. Per resolveCloseDecision this can only be true on the
            // waitForWriter timeout (a trackless/empty container still aborts). Reversible / default-ON.
            val salvageDevelopMaster =
                RecordDevelopSalvageOnStopFailureFlag.enabled &&
                    (closeDecision.abortBackend || closeDecision.waitForWriter) &&
                    outputTarget?.storageKind == RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER &&
                    muxerStarted.get() &&
                    videoTrackIndex >= 0 &&
                    encodedVideoSampleCount > 0L
            if (salvageDevelopMaster) {
                runCatching {
                    NeoLogger.critical(
                        "RecordingManager",
                        "RECORD_DEVELOP_SALVAGE finalizing develop master instead of aborting " +
                            "(encodedSamples=$encodedVideoSampleCount videoTrack=$videoTrackIndex " +
                            "abort=${closeDecision.abortBackend} waitForWriter=${closeDecision.waitForWriter})"
                    )
                }
            }

            // Finalize the active recording container.
            synchronized(muxerLock) {
                when {
                    closeDecision.closeBackend || salvageDevelopMaster -> movSessionController?.close()
                    closeDecision.abortBackend -> {
                        movSessionController?.abort()
                        val strictFailure = strictStopFrameGateEvidence
                            .takeIf {
                                it.required &&
                                    encodedVideoSampleCount < it.expectedVideoSamples.coerceAtLeast(1L)
                            }
                            ?.failureMessage(encodedVideoSampleCount)
                        throw IOException(
                            strictFailure
                                ?: "Recording backend did not finalize a valid video stream " +
                                    "(encodedSamples=$encodedVideoSampleCount, videoTrack=$videoTrackIndex)"
                        )
                    }
                    closeDecision.waitForWriter -> {
                        movSessionController?.abort()
                        throw IOException("Recording backend did not finish writing before timeout")
                    }
                }
            }

            val path = lastOutputPath
            val finalOutputDescription = buildOutputDescription()
            val finalizedOutput = finalizeOutputTarget(finalOutputDescription)
            writeQualityCertificateForFinalizedRecording(path, finalizedOutput)
            releaseInternal(
                closeOutputTargetDescriptor = false,
                retirePersistentInputSurface = true
            )
            _state.value = GpuRecordingState.Idle
            _duration.value = 0L

            // Only claim "saved" when finalize actually produced evidence. A null here means the
            // MediaStore publish failed and the (now cleaned-up) pending entry was removed — reporting
            // success then would be a phantom "saved" for a clip the gallery never received (#3).
            if (finalizedOutput != null) {
                Log.i(TAG, "Recording saved -> ${finalizedOutput.outputPath}")
            } else {
                Log.w(TAG, "Recording finalize produced no output evidence -> $path (publish may have failed; entry cleaned up)")
                runCatching {
                    NeoLogger.critical(
                        "RecordingManager",
                        "RECORDING_FINALIZE_NO_EVIDENCE path=$path — clip may not be visible in the gallery"
                    )
                }
            }
            // #3 phantom-saved fix: a null finalizedOutput on the success path means outputStore.finalize()
            // failed unrecoverably and already deleted the pending MediaStore entry (finalize -> cleanupFailure).
            // Return null so the caller reports a genuine failure instead of a gallery path the clip never
            // reached. When finalizedOutput != null the returned value is byte-identical to before.
            if (finalizedOutput != null) path else null
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
            // Capture WHY stop() threw (the verdict line above is skipped when the throw is in the
            // EOS/drain phase). Names the exact exception + how many frames were actually encoded,
            // so we know whether a salvageable clip is being discarded.
            runCatching {
                NeoLogger.critical(
                    "RecordingManager",
                    "RECORDING_STOP_THREW inputMode=$videoInputMode error=${e.javaClass.simpleName}: ${e.message} " +
                        "encodedVideoSampleCount=$encodedVideoSampleCount muxerStarted=${muxerStarted.get()} " +
                        "videoTrackIndex=$videoTrackIndex pendingSamples=${pendingMuxerSamples()}"
                )
            }
            releaseInternal(
                closeOutputTargetDescriptor = false,
                retirePersistentInputSurface = true
            )
            cleanupFailedOutputTarget()
            _state.value = GpuRecordingState.Idle
            _duration.value = 0L
            null
        } finally {
            RuntimeStageTelemetry.recordDuration(
                tag = TAG,
                stage = "stop",
                durationMs = SystemClock.elapsedRealtime() - stopStartElapsedMs,
                slowThresholdMs = SLOW_STOP_STAGE_MS
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIDEO DRAIN LOOP
    //
    // Continuously polls the video encoder for encoded output buffers.
    // Each encoded chunk (NAL unit) is written to the MediaMuxer.
    // Runs until EOS is received from the encoder.
    // ─────────────────────────────────────────────────────────────────────────

    private fun drainVideoEncoder() {
        HotPathScheduling.applyUrgentDisplay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            p010LinearBlockAdmissionEnabled
        ) {
            drainBlockModelVideoEncoder()
            return
        }
        val codec = videoCodecObj ?: return
        val info  = MediaCodec.BufferInfo()
        var eos   = false
        videoDrainErrorCount = 0

        while (!eos && !Thread.currentThread().isInterrupted) {
            // FPS-aware drain timeout scaling (Tier 2.1 adaptive polling)
            drainTimeoutUsScaled = if (currentFrameRate > 0) {
                minOf((1_000_000 / (currentFrameRate * 2)).toLong(), DRAIN_MAX_TIMEOUT_US)
            } else {
                DRAIN_TIMEOUT_US
            }

            val index = try {
                codec.dequeueOutputBuffer(info, drainTimeoutUsScaled)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Video drain interrupted during dequeue", e)
                Thread.currentThread().interrupt()
                break
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Video drain cancelled during shutdown", e)
                videoDrainErrorCount++
                if (videoDrainErrorCount >= DRAIN_ERROR_RECOVERY_ATTEMPTS) break
                try {
                    Thread.sleep(DRAIN_ERROR_BACKOFF_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            } catch (e: Exception) {
                Log.e(TAG, "Video drain failed: ${e.message}", e)
                videoDrainErrorCount++
                if (videoDrainErrorCount >= DRAIN_ERROR_RECOVERY_ATTEMPTS) break
                try {
                    Thread.sleep(DRAIN_ERROR_BACKOFF_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            
            // Reset error count on successful dequeue
            if (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoDrainErrorCount = 0
            }
            
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Video format is now known — add track to muxer
                    val format = codec.outputFormat
                    synchronized(muxerLock) {
                        videoTrackIndex = addVideoTrack(format)
                    }
                    captureEncoderTransportReadBack(format)
                    Log.i(TAG, "Video format: $format trackIdx=$videoTrackIndex")
                    tryStartMuxer()
                    if (!muxerStarted.get() && allowVideoOnlyMuxerStart()) {
                        tryStartMuxer(forceVideoOnly = true)
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet — yield and retry
                    if (!isRecording.get() && byteBufferEosInputUnavailable) {
                        // Saturated P010 encoder could not accept an EOS marker. All currently
                        // available output has been drained and muxed; finish cleanly so stop()
                        // finalizes the real encoded frames instead of discarding the whole clip.
                        Log.i(TAG, "P010 drain finishing without EOS marker (encoder saturated)")
                        eos = true
                    }
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Codec-config buffer (SPS/PPS for AVC, VPS/SPS/PPS for HEVC)
                        // The muxer handles this internally when added via addTrack()
                        codec.releaseOutputBuffer(index, false)
                    } else {
                        if (!muxerStarted.get() && allowVideoOnlyMuxerStart()) {
                            tryStartMuxer(forceVideoOnly = true)
                        }
                        if (muxerStarted.get() && info.size > 0 && shouldWriteEncoderSample()) {
                            val buf = try {
                                codec.getOutputBuffer(index)
                            } catch (e: IllegalStateException) {
                                Log.w(TAG, "Video output buffer unavailable during shutdown", e)
                                null
                            }
                            if (buf == null) {
                                try { codec.releaseOutputBuffer(index, false) } catch (e: Exception) {
                                    Log.w(TAG, "Video output buffer release failed during shutdown", e)
                                }
                                break
                            }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val encoderSourcePtsUs =
                                nextEncoderPresentationTimeUs(info.presentationTimeUs)
                            if (firstVideoPtsUs < 0L) {
                                firstVideoPtsUs = encoderSourcePtsUs
                                // s6-f10: record-start -> first-encoded-frame latency (device-measured).
                                lastRecordStartToFirstFrameUs =
                                    ((System.nanoTime() - recordingStartNs) / 1_000L).coerceAtLeast(0L)
                            }

                            val normalizedPtsUs =
                                (encoderSourcePtsUs - firstVideoPtsUs).coerceAtLeast(0L)
                            val outputPtsUs = outputPresentationTimeUs(normalizedPtsUs)
                            info.presentationTimeUs =
                                RecordingVideoCadencePolicy.resolvePresentationTimeUs(
                                    candidateOutputPtsUs = outputPtsUs,
                                    previousOutputPtsUs = lastVideoPtsUs,
                                    frameRate = videoFps,
                                    constantCadence = shouldUseConstantMuxCadence()
                                )
                            if (firstOutputVideoPtsUs < 0L) {
                                firstOutputVideoPtsUs = info.presentationTimeUs
                            }
                            if (lastVideoPtsUs >= 0L) {
                                val intervalMs =
                                    ((info.presentationTimeUs - lastVideoPtsUs).coerceAtLeast(0L) / 1_000f)
                                if (intervalMs > maxEncodedFrameIntervalMs) {
                                    maxEncodedFrameIntervalMs = intervalMs
                                }
                            }
                            RecordingTimelineTelemetryPolicy.framePacingJitterMs(
                                previousVideoPtsUs = lastEncoderPtsUs,
                                currentVideoPtsUs = info.presentationTimeUs,
                                frameRate = currentFrameRate
                            )?.let { jitterMs ->
                                if (jitterMs > AV_SYNC_TOLERANCE_MS) {
                                    framePacingJitterMs = jitterMs
                                    maxAvSyncDriftMs = maxOf(maxAvSyncDriftMs, jitterMs)
                                    if (!avSyncWarningLogged && jitterMs > 200L) {
                                        Log.w(
                                            TAG,
                                            "Frame pacing jitter detected: $jitterMs ms " +
                                                "(max so far: $maxAvSyncDriftMs ms)"
                                        )
                                        avSyncWarningLogged = true
                                    } else if (jitterMs > 100L) {
                                        Log.d(TAG, "Frame pacing jitter: $jitterMs ms")
                                    }
                                }
                            }
                            RecordingTimelineTelemetryPolicy.avSyncDriftMs(
                                latestVideoPtsUs = info.presentationTimeUs,
                                latestAudioPtsUs = lastAudioPtsUs
                            )?.let { driftMs ->
                                if (driftMs > AV_SYNC_TOLERANCE_MS) {
                                    avDriftMs = driftMs
                                    maxAvSyncDriftMs = maxOf(maxAvSyncDriftMs, driftMs)
                                    if (!avSyncWarningLogged && driftMs > 200L) {
                                        Log.w(
                                            TAG,
                                            "A/V sync drift detected: $driftMs ms " +
                                                "(max so far: $maxAvSyncDriftMs ms)"
                                        )
                                        avSyncWarningLogged = true
                                    } else if (driftMs > 100L) {
                                        Log.d(TAG, "A/V sync drift: $driftMs ms")
                                    }
                                }
                            }
                            lastEncoderPtsUs = info.presentationTimeUs
                            lastVideoPtsUs = info.presentationTimeUs
                            encodedVideoSampleCount += 1L
                            if (videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010 &&
                                p010FirstEncodeLogged.compareAndSet(false, true)
                            ) {
                                runCatching {
                                    NeoLogger.i(
                                        "RecordingManager",
                                        "P010_FIRST_ENCODE encodedVideoSampleCount=$encodedVideoSampleCount " +
                                            "ptsUs=${info.presentationTimeUs} videoTrackIndex=$videoTrackIndex " +
                                            "muxerStarted=${muxerStarted.get()}"
                                    )
                                }
                            }
                            encodedOutputBitrateMeter.record(
                                timestampUs = info.presentationTimeUs,
                                sizeBytes = info.size
                            )
                            queueMuxerSample(videoTrackIndex, buf, info)
                        }
                        eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        try {
                            codec.releaseOutputBuffer(index, false)
                        } catch (e: IllegalStateException) {
                            // stop() raced in on another thread after we dequeued this buffer; the
                            // codec has left the Executing state, so draining is finished. Ending the
                            // drain cleanly here avoids crashing the process and losing the recording.
                            Log.w(TAG, "Video releaseOutputBuffer raced with stop(); ending drain", e)
                            break
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Video encoder drained (EOS)")
        videoDrainComplete.set(true)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun drainBlockModelVideoEncoder() {
        HotPathScheduling.applyUrgentDisplay()
        val codec = videoCodecObj ?: return
        var eos = false
        videoDrainErrorCount = 0

        while (!eos && !Thread.currentThread().isInterrupted) {
            val event = try {
                blockModelVideoEvents.poll(DRAIN_MAX_TIMEOUT_US, TimeUnit.MICROSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (event == null) {
                if (!isRecording.get() && byteBufferEosInputUnavailable) {
                    // Saturated P010 encoder could not accept an EOS marker. All currently
                    // available output has been drained and muxed; finish cleanly so stop()
                    // finalizes the real encoded frames instead of discarding the whole clip.
                    Log.i(TAG, "P010 drain finishing without EOS marker (encoder saturated)")
                    eos = true
                }
                continue
            }

            when (event) {
                is BlockModelVideoEvent.OutputFormat -> {
                    synchronized(muxerLock) {
                        videoTrackIndex = addVideoTrack(event.format)
                    }
                    captureEncoderTransportReadBack(event.format)
                    Log.i(
                        TAG,
                        "Video block-model format: ${event.format} trackIdx=$videoTrackIndex"
                    )
                    tryStartMuxer()
                    if (!muxerStarted.get() && allowVideoOnlyMuxerStart()) {
                        tryStartMuxer(forceVideoOnly = true)
                    }
                }

                is BlockModelVideoEvent.CodecError -> {
                    videoDrainErrorCount += 1
                    Log.e(TAG, "Video block-model codec failed", event.exception)
                    break
                }

                is BlockModelVideoEvent.OutputBuffer -> {
                    val info = event.info
                    val outputFrame = try {
                        codec.getOutputFrame(event.index)
                    } catch (e: Exception) {
                        Log.w(TAG, "Video block-model output frame unavailable", e)
                        runCatching { codec.releaseOutputBuffer(event.index, false) }
                        break
                    }
                    val linearBlock = outputFrame.linearBlock
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (!muxerStarted.get() && allowVideoOnlyMuxerStart()) {
                                tryStartMuxer(forceVideoOnly = true)
                            }
                            if (muxerStarted.get() && info.size > 0 && shouldWriteEncoderSample()) {
                                val buffer = try {
                                    linearBlock?.map()
                                } catch (e: Exception) {
                                    Log.w(TAG, "Video block-model output map failed", e)
                                    null
                                }
                                if (buffer == null) {
                                    break
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val encoderSourcePtsUs =
                                    nextEncoderPresentationTimeUs(info.presentationTimeUs)
                                if (firstVideoPtsUs < 0L) {
                                    firstVideoPtsUs = encoderSourcePtsUs
                                    // s6-f10: record-start -> first-encoded-frame latency (device-measured).
                                    lastRecordStartToFirstFrameUs =
                                        ((System.nanoTime() - recordingStartNs) / 1_000L).coerceAtLeast(0L)
                                }

                                val normalizedPtsUs =
                                    (encoderSourcePtsUs - firstVideoPtsUs).coerceAtLeast(0L)
                                val outputPtsUs = outputPresentationTimeUs(normalizedPtsUs)
                                info.presentationTimeUs =
                                    RecordingVideoCadencePolicy.resolvePresentationTimeUs(
                                        candidateOutputPtsUs = outputPtsUs,
                                        previousOutputPtsUs = lastVideoPtsUs,
                                        frameRate = videoFps,
                                        constantCadence = shouldUseConstantMuxCadence()
                                    )
                                if (firstOutputVideoPtsUs < 0L) {
                                    firstOutputVideoPtsUs = info.presentationTimeUs
                                }
                                if (lastVideoPtsUs >= 0L) {
                                    val intervalMs =
                                        (
                                            (info.presentationTimeUs - lastVideoPtsUs)
                                                .coerceAtLeast(0L) / 1_000f
                                            )
                                    if (intervalMs > maxEncodedFrameIntervalMs) {
                                        maxEncodedFrameIntervalMs = intervalMs
                                    }
                                }
                                RecordingTimelineTelemetryPolicy.framePacingJitterMs(
                                    previousVideoPtsUs = lastEncoderPtsUs,
                                    currentVideoPtsUs = info.presentationTimeUs,
                                    frameRate = currentFrameRate
                                )?.let { jitterMs ->
                                    if (jitterMs > AV_SYNC_TOLERANCE_MS) {
                                        framePacingJitterMs = jitterMs
                                        maxAvSyncDriftMs = maxOf(maxAvSyncDriftMs, jitterMs)
                                        if (!avSyncWarningLogged && jitterMs > 200L) {
                                            Log.w(
                                                TAG,
                                                "Frame pacing jitter detected: $jitterMs ms " +
                                                    "(max so far: $maxAvSyncDriftMs ms)"
                                            )
                                            avSyncWarningLogged = true
                                        } else if (jitterMs > 100L) {
                                            Log.d(TAG, "Frame pacing jitter: $jitterMs ms")
                                        }
                                    }
                                }
                                RecordingTimelineTelemetryPolicy.avSyncDriftMs(
                                    latestVideoPtsUs = info.presentationTimeUs,
                                    latestAudioPtsUs = lastAudioPtsUs
                                )?.let { driftMs ->
                                    if (driftMs > AV_SYNC_TOLERANCE_MS) {
                                        avDriftMs = driftMs
                                        maxAvSyncDriftMs = maxOf(maxAvSyncDriftMs, driftMs)
                                        if (!avSyncWarningLogged && driftMs > 200L) {
                                            Log.w(
                                                TAG,
                                                "A/V sync drift detected: $driftMs ms " +
                                                    "(max so far: $maxAvSyncDriftMs ms)"
                                            )
                                            avSyncWarningLogged = true
                                        } else if (driftMs > 100L) {
                                            Log.d(TAG, "A/V sync drift: $driftMs ms")
                                        }
                                    }
                                }
                                lastEncoderPtsUs = info.presentationTimeUs
                                lastVideoPtsUs = info.presentationTimeUs
                                encodedVideoSampleCount += 1L
                                if (videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010 &&
                                    p010FirstEncodeLogged.compareAndSet(false, true)
                                ) {
                                    runCatching {
                                        NeoLogger.i(
                                            "RecordingManager",
                                            "P010_FIRST_ENCODE encodedVideoSampleCount=$encodedVideoSampleCount " +
                                                "ptsUs=${info.presentationTimeUs} videoTrackIndex=$videoTrackIndex " +
                                                "muxerStarted=${muxerStarted.get()}"
                                        )
                                    }
                                }
                                encodedOutputBitrateMeter.record(
                                    timestampUs = info.presentationTimeUs,
                                    sizeBytes = info.size
                                )
                                queueMuxerSample(videoTrackIndex, buffer, info)
                            }
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        runCatching { codec.releaseOutputBuffer(event.index, false) }
                        runCatching { linearBlock?.recycle() }
                    }
                }
            }
        }

        Log.i(TAG, "Video block-model encoder drained (EOS)")
        videoDrainComplete.set(true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUDIO — CAPTURE AND ENCODE
    //
    // Reads PCM from AudioRecord, feeds into MediaCodec AAC encoder,
    // drains encoded AAC into MediaMuxer — all on one coroutine.
    //
    // Audio PTS is anchored to recordingStartNs, then advances by successfully
    // queued PCM frames. It is never snapped to delayed video drain output.
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun recordAndEncodeAudio() {
        val codec = audioCodecObj ?: return
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { Log.w(TAG, "Unable to set urgent audio thread priority", it) }

        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK, AUDIO_FORMAT
        ).coerceAtLeast(8192)

        val ar = try {
            AudioRecord(
                when (audioSource) {
                    AudioSrc.MIC -> MediaRecorder.AudioSource.MIC
                    AudioSrc.UNPROCESSED ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            MediaRecorder.AudioSource.UNPROCESSED
                        else MediaRecorder.AudioSource.MIC
                    AudioSrc.NONE -> MediaRecorder.AudioSource.DEFAULT
                    AudioSrc.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
                },
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_MASK,
                AUDIO_FORMAT,
                minBufSize * 4
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            audioDrainComplete.set(true)
            return
        }
        audioRecord = ar
        audioCaptureStopRequested.set(false)

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            ar.release()
            audioDrainComplete.set(true)
            return
        }

        ar.startRecording()
        Log.i(TAG, "Audio capture started (${AUDIO_SAMPLE_RATE}Hz stereo)")

        val pcmBuf = ByteBuffer.allocateDirect(minBufSize).order(ByteOrder.LITTLE_ENDIAN)
        val info   = MediaCodec.BufferInfo()
        val audioTimeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = recordingStartNs,
            sampleRate = AUDIO_SAMPLE_RATE,
            channelCount = AUDIO_CHANNELS,
            bytesPerSample = AUDIO_BYTES_PER_SAMPLE,
            initialSubmittedFrames = primedAudioPcmFrames
        )

        try {
            // The audio coroutine is the exclusive owner of codec output dequeue.
            runCatching { drainAudioEncoder(codec, info, eos = false) }
            while (isRecording.get()) {
                // ── Feed PCM into encoder ──────────────────────────────────────
                pcmBuf.clear()
                val bytesRead = ar.read(
                    pcmBuf,
                    pcmBuf.capacity(),
                    AudioRecord.READ_BLOCKING
                )
                val readCompletedNs = System.nanoTime()
                when {
                    bytesRead > 0 -> {
                        if (!shouldWriteEncoderSample()) {
                            runCatching { drainAudioEncoder(codec, info, eos = false) }
                            continue
                        }
                        val inputIdx = try {
                            codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                        } catch (_: IllegalStateException) {
                            Log.i(TAG, "Audio codec no longer accepts input; ending audio loop")
                            break
                        }
                        if (inputIdx < 0) {
                            runCatching { drainAudioEncoder(codec, info, eos = false) }
                            continue
                        }
                        val inputBuffer = try {
                            codec.getInputBuffer(inputIdx)
                        } catch (_: IllegalStateException) {
                            Log.i(TAG, "Audio codec input buffer became unavailable; ending audio loop")
                            break
                        }
                        if (inputBuffer == null) {
                            runCatching { drainAudioEncoder(codec, info, eos = false) }
                            continue
                        }
                        inputBuffer.clear()
                        pcmBuf.flip()
                        pcmBuf.limit(bytesRead)
                        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        inputBuffer.put(pcmBuf)
                        val ptsUs = audioTimeline.presentationTimeUs(
                            readCompletedNs = readCompletedNs,
                            bytesRead = bytesRead
                        )
                        val queued = try {
                            codec.queueInputBuffer(
                                inputIdx, 0, bytesRead, ptsUs, 0
                            )
                            true
                        } catch (_: IllegalStateException) {
                            false
                        }
                        if (!queued) {
                            Log.i(TAG, "Audio codec released during queueInputBuffer; ending audio loop")
                            break
                        }
                        audioTimeline.commitQueuedBytes(bytesRead)
                    }
                    bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "AudioRecord became unavailable during recording")
                        break
                    }
                    bytesRead < 0 -> {
                        if (isRecording.get()) {
                            Log.w(TAG, "AudioRecord read failed: $bytesRead")
                        }
                    }
                }

                // ── Drain encoded AAC ──────────────────────────────────────────
                val drained = runCatching { drainAudioEncoder(codec, info, eos = false) }.isSuccess
                if (!drained) {
                    Log.i(TAG, "Audio codec released during drain; ending audio loop")
                    break
                }
            }

            // ── Signal audio EOS ───────────────────────────────────────────────
            val eosQueued = queueAudioEndOfStream(
                codec = codec,
                info = info,
                audioTimeline = audioTimeline
            )
            if (eosQueued) {
                val eosSeen = runCatching {
                    drainAudioEncoder(codec, info, eos = true)
                }.getOrDefault(false)
                if (!eosSeen) {
                    Log.w(
                        TAG,
                        "Audio EOS was queued but not observed within " +
                            "${RecordingAudioSyncPolicy.EOS_DRAIN_TIMEOUT_US}us"
                    )
                }
            } else {
                Log.w(TAG, "Audio EOS input buffer unavailable before bounded shutdown deadline")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio encode loop failed", e)
        } finally {
            stopAudioCapture(audioRecordOverride = ar, logFailure = false)
            ar.release()
            audioRecord = null
            audioDrainComplete.set(true)
            Log.i(TAG, "Audio capture stopped")
        }
    }

    private fun queueAudioEndOfStream(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        audioTimeline: RecordingAudioSyncPolicy.Timeline
    ): Boolean {
        val deadlineNs = System.nanoTime() + 250_000_000L
        while (System.nanoTime() < deadlineNs && !Thread.currentThread().isInterrupted) {
            val inputIdx = try {
                codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                if (RecordingAudioCodecErrorPolicy.shouldEndDrainLoop(e.message)) {
                    audioCodecTerminalShutdown.set(true)
                    Log.i(TAG, "Audio EOS queue skipped: codec already stopping/released")
                    return false
                }
                -1
            }
            if (inputIdx >= 0) {
                return runCatching {
                    codec.queueInputBuffer(
                        inputIdx,
                        0,
                        0,
                        audioTimeline.endPresentationTimeUs(),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    true
                }.getOrDefault(false)
            }
            drainAudioEncoder(codec, info, eos = false)
            if (audioCodecTerminalShutdown.get()) return false
        }
        return false
    }

    private fun drainAudioEncoder(
        codec: MediaCodec,
        info:  MediaCodec.BufferInfo,
        eos:   Boolean
    ): Boolean {
        var done = false
        var eosSeen = false
        val eosDrainStartedNs = if (eos) System.nanoTime() else 0L

        // A blocking stereo PCM read can contain multiple AAC access units.
        // Drain every available output now so shutdown never receives a session-sized backlog.
        while (!done && !Thread.currentThread().isInterrupted) {
            val index = try {
                codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Audio drain interrupted during dequeue", e)
                Thread.currentThread().interrupt()
                done = true
                continue
            } catch (e: Exception) {
                if (RecordingAudioCodecErrorPolicy.shouldEndDrainLoop(e.message)) {
                    audioCodecTerminalShutdown.set(true)
                    Log.i(TAG, "Audio codec entered terminal drain state; ending audio drain loop")
                    done = true
                    continue
                }
                Log.e(TAG, "Audio dequeue failed: ${e.message}", e)
                audioDrainErrorCount++
                if (audioDrainErrorCount >= DRAIN_ERROR_RECOVERY_ATTEMPTS) {
                    done = true
                    continue
                }
                try {
                    Thread.sleep(DRAIN_ERROR_BACKOFF_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    done = true
                }
                continue
            }
            
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (muxerStarted.get()) {
                        Log.w(TAG, "Audio format arrived after muxer start; skipping audio track")
                        done = true
                        continue
                    }
                    synchronized(muxerLock) {
                        audioTrackIndex = addAudioTrack(format)
                    }
                    Log.i(TAG, "Audio format: $format trackIdx=$audioTrackIndex")
                    tryStartMuxer()
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) {
                        done = true
                    } else {
                        val elapsedUs =
                            (System.nanoTime() - eosDrainStartedNs).coerceAtLeast(0L) / 1_000L
                        done = !RecordingAudioSyncPolicy.shouldContinueEosDrain(
                            eosSeen = eosSeen,
                            elapsedUs = elapsedUs
                        )
                    }
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        && muxerStarted.get() && audioTrackIndex >= 0 && info.size > 0 &&
                        shouldWriteEncoderSample()) {
                        try {
                            val buf = codec.getOutputBuffer(index) ?: run {
                                codec.releaseOutputBuffer(index, false)
                                done = true
                                return eosSeen
                            }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            info.presentationTimeUs =
                                RecordingAudioSyncPolicy.monotonicAacPresentationTimeUs(
                                    candidatePtsUs = info.presentationTimeUs,
                                    previousPtsUs = lastAudioPtsUs,
                                    sampleRate = AUDIO_SAMPLE_RATE
                                )
                            lastAudioPtsUs = info.presentationTimeUs
                            queueMuxerSample(audioTrackIndex, buf, info)
                        } catch (e: Exception) {
                            Log.e(TAG, "Audio mux write failed", e)
                            try { codec.releaseOutputBuffer(index, false) } catch (e: Exception) { 
                                Log.w(TAG, "releaseOutputBuffer failed in exception handler", e) 
                            }
                            done = true
                            return eosSeen
                        }
                    }
                    try { codec.releaseOutputBuffer(index, false) } catch (e: Exception) { 
                        Log.w(TAG, "releaseOutputBuffer failed", e) 
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        eosSeen = true
                        done = true
                    }
                }
            }
        }
        return eosSeen
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MUXER START
    //
    // The MediaMuxer cannot start until ALL tracks have been added.
    // Video format arrives first (from INFO_OUTPUT_FORMAT_CHANGED).
    // Audio format arrives shortly after codec.start().
    // We use synchronized access on muxer to ensure exactly one start.
    // ─────────────────────────────────────────────────────────────────────────

    private fun allowVideoOnlyMuxerStart(): Boolean {
        return RecordingAudioStartupPolicy.allowVideoOnlyMuxerStart(
            audioEnabled = audioEnabled,
            audioTrackIndex = audioTrackIndex,
            isRecording = isRecording.get(),
            nowNs = System.nanoTime(),
            audioTrackDeadlineNs = audioTrackDeadlineNs
        )
    }

    private fun primeAudioEncoderTrack(codec: MediaCodec): Long {
        if (!audioEnabled || audioTrackIndex >= 0) return 0L

        val inputIndex = runCatching {
            codec.dequeueInputBuffer(DRAIN_TIMEOUT_US * 4)
        }.getOrDefault(-1)
        if (inputIndex < 0) return 0L

        val inputBuffer = runCatching { codec.getInputBuffer(inputIndex) }.getOrNull()
        if (inputBuffer == null) {
            runCatching { codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0) }
            return 0L
        }

        val primingBytes = minOf(
            inputBuffer.capacity(),
            RecordingAudioStartupPolicy.primingPcmBytes()
        )
        inputBuffer.clear()
        repeat(primingBytes) {
            inputBuffer.put(0.toByte())
        }
        return runCatching {
            codec.queueInputBuffer(inputIndex, 0, primingBytes, 0L, 0)
            primingBytes.toLong() / (AUDIO_CHANNELS * AUDIO_BYTES_PER_SAMPLE)
        }.onFailure {
            Log.w(TAG, "Audio track priming skipped", it)
        }.getOrDefault(0L)
    }

    private fun tryStartMuxer(forceVideoOnly: Boolean = false) {
        synchronized(muxerLock) {
            val videoReady = videoTrackIndex >= 0
            val audioReady = !audioEnabled || audioTrackIndex >= 0 || !isRecording.get() || forceVideoOnly || allowVideoOnlyMuxerStart()
            if (videoReady && audioReady && !muxerStarted.get()) {
                muxerStarted.set(true)
                Log.i(TAG, "Recording backend ready (video=$videoTrackIndex audio=$audioTrackIndex)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DURATION TICKER
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tickDuration() {
        while (isRecording.get()) {
            _duration.value = RecordingDurationPolicy.resolveDurationMs(
                recordingStartNs = recordingStartNs,
                nowNs = System.nanoTime(),
                latestVideoPtsUs = lastVideoPtsUs,
                latestAudioPtsUs = lastAudioPtsUs
            )
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (nowMs - lastBitrateHintUptimeMs >= RUNTIME_BITRATE_HINT_INTERVAL_MS) {
                applyRuntimeBitrateHints()
            }
            delay(100)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESOURCE MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    private fun releaseInternal(
        closeOutputTargetDescriptor: Boolean = true,
        retirePersistentInputSurface: Boolean = false
    ) {
        isRecording.set(false)
        byteBufferVideoEosQueued.set(true)
        awaitByteBufferImageInputIdle()
        muxerWriterJob?.cancel()
        muxerWriterJob = null
        muxerSampleQueue.reset()
        val sessionUsedPersistentInputSurface = encoderSurfaceUsesPersistentInput

        // The input surface is detached upstream before stop(). Codec-owned input surfaces
        // are released with the recording session; persistent surfaces stay alive so Camera2
        // can keep the same pre-armed preview+encoder graph between recordings.
        if (RecordingPersistentInputSurfacePolicy.shouldReleaseSessionSurface(encoderSurfaceUsesPersistentInput)) {
            try { encoderSurface?.release() } catch (e: Exception) { Log.w(TAG, "encoderSurface.release() failed", e) }
        }
        encoderSurface = null
        encoderSurfaceUsesPersistentInput = false

        // 2. Codecs (after surface is released, they can drain and stop cleanly)
        try { videoCodecObj?.stop()    } catch (e: Exception) { Log.w(TAG, "videoCodec.stop() failed", e) }
        try { videoCodecObj?.release() } catch (e: Exception) { Log.w(TAG, "videoCodec.release() failed", e) }
        videoCodecObj = null
        resetVideoCodecBlockModelSession()

        if (
            retirePersistentInputSurface &&
            RecordingPersistentInputSurfacePolicy.shouldRetirePersistentInputSurfaceAfterCodecSession(
                sessionUsesPersistentSurface = sessionUsedPersistentInputSurface
            )
        ) {
            releasePersistentInputSurface()
            Log.i(TAG, "Persistent encoder input surface retired after codec session")
        }

        try { audioCodecObj?.stop()    } catch (e: Exception) { Log.w(TAG, "audioCodec.stop() failed", e) }
        try { audioCodecObj?.release() } catch (e: Exception) { Log.w(TAG, "audioCodec.release() failed", e) }
        audioCodecObj = null

        // 3. Container backend (only after codecs are done writing)
        try { movSessionController?.abort() } catch (e: Exception) {
            Log.w(TAG, "MOV session abort failed during release", e)
        }
        movSessionController = null

        videoTrackIndex = -1
        audioTrackIndex = -1
        firstVideoPtsUs = -1L
        firstOutputVideoPtsUs = -1L
        lastVideoPtsUs = -1L
        lastAudioPtsUs = -1L
        primedAudioPcmFrames = 0L
        encodedVideoSampleCount = 0L
        lastRecordStartToFirstFrameUs = -1L
        recordStartHeadFrameGap = -1
        requestedVideoProfileForEvidence = -1
        requestedVideoLevelForEvidence = -1
        requestedVideoBitrateForEvidence = -1
        requestedColorStandardForEvidence = -1
        requestedColorTransferForEvidence = -1
        requestedColorRangeForEvidence = -1
        muxedVideoProfileForEvidence = -1
        muxedVideoLevelForEvidence = -1
        muxedVideoBitrateForEvidence = -1
        encoderTransportReadBackAvailable = false
        recordingStartEpochMs = 0L
        recordingStartProcessCpuMs = 0L
        finalRecordingCpuUsagePercentForEvidence = null
        finalActiveRecordingDurationUsForEvidence = 0L
        clipTimingStartNsForEvidence = 0L
        clipTimingStartPausedUsForEvidence = 0L
        stopRequestedNsForEvidence = 0L
        finalStrictFrameGateDurationUsForEvidence = 0L
        maxEncodedFrameIntervalMs = 0f
        videoDrainComplete.set(false)
        audioDrainComplete.set(false)
        audioCodecTerminalShutdown.set(false)
        muxerStarted.set(false)
        simulatedTenBitFallbackActive = false
        activeBitDepthLabel = if (tenBitEnabled) "10-bit" else "8-bit"
        activeDynamicRangeLabel = RecordingHdrVideoLookPolicy.defaultDynamicRangeLabel(
            videoHdrMode = videoHdrMode,
            videoColorSpace = videoColorSpace
        )
        activeLogCurveLabel = resolveActiveLogCurveLabel()
        activeQualityTierLabel = RecordingCodecCatalog.QualityTier.STANDARD.name
        activeColorEncodingConfig = resolveColorEncodingConfig(RecordingCodecCatalog.mime(videoCodec))
        activeEncoderQualityTelemetry = RecordingEncoderQualityTelemetry.EMPTY
        activeIFrameIntervalSeconds = iFrameInterval.toFloat().coerceAtLeast(0.25f)
        requestedOutputBitrateForEvidence = videoBitrate
        policyTargetBitrateForEvidence = videoBitrate
        activeOutputBitrate = videoBitrate
        abrNominalBitrate = videoBitrate
        abrShaderEntropyScale = 1f
        abrDetailGainScale = 1f
        abrDitherAmplitudeScale = 1f
        recordingPtsSynchronizer.reset(initialLatencyCompensationUs = 0L)
        clearEncoderPresentationQueue()
        allIntraActiveInternal = false
        encodedOutputBitrateMeter.reset()
        framePacingJitterMs = 0L
        avDriftMs = 0L
        lastEncoderPtsUs = 0L
        maxAvSyncDriftMs = 0L
        avSyncWarningLogged = false
        audioCaptureStopRequested.set(false)
        lastBitrateHintUptimeMs = 0L
        lastSyncFrameRequestUptimeMs = 0L
        lastHintedBitrate = videoBitrate
        synchronized(pauseTimelineLock) {
            pauseTimeline = RecordingPauseTimeline()
        }
        if (closeOutputTargetDescriptor) {
            try { outputTarget?.parcelFileDescriptor?.close() } catch (e: Exception) {
                Log.w(TAG, "Output target descriptor close failed", e)
            }
        }
    }

    fun destroy() {
        if (_state.value == GpuRecordingState.Recording || _state.value == GpuRecordingState.Paused) {
            isRecording.set(false)
        }
        releaseInternal()
        releasePersistentInputSurface()
        cleanupFailedOutputTarget()
        _state.value = GpuRecordingState.Idle
        _duration.value = 0L
        byteBufferImageInputExecutor.shutdownNow()
        scope.cancel()
    }

    fun reportExternalError(message: String) {
        releaseInternal()
        cleanupFailedOutputTarget()
        _state.value = GpuRecordingState.Error(message)
    }

    fun discardPreparedSession() {
        releaseInternal()
        cleanupFailedOutputTarget()
        _state.value = GpuRecordingState.Idle
        _duration.value = 0L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE OUTPUT
    // ─────────────────────────────────────────────────────────────────────────

    private fun createOutputTarget(): RecordingOutputTarget {
        // Base file name: the user's one-shot custom name when set, otherwise the default "CamX ddMMyyyy"
        // (date-only; MediaStore auto-uniquifies same-day collisions so nothing is overwritten).
        val baseName = RecordingFileNamePolicy.resolveBaseName(
            customName = pendingCustomRecordingName,
            dateLabel = RecordingFileNamePolicy.dateLabel(Date())
        )
        val contract = RecordingMovOutputContractPolicy.resolve(
            baseName = baseName,
            outputLabelSuffix = sanitizedOutputSuffix(),
            cinemaMode = cinemaMode,
            rawVideoEnabled = rawVideoEnabled,
            logVideoEnabled = videoLogEnabled,
            hdrMode = videoHdrMode,
            descriptionSnapshot = currentOutputDescriptionSnapshot()
        )
        // Record-then-develop LIGHT lane: route the flat intermediate into the private develop-master dir
        // (kept out of the gallery, discovered by the SDR/NDR develop cards) instead of MediaStore. The
        // muxer writes straight to this File via writablePath()->absolutePath (same path the legacy target
        // uses). Reversible / default-OFF: with the flag off, developMasterFile stays null and the proven
        // MediaStore/legacy publish flow is byte-identical.
        lastDevelopMasterFile = null
        val developMasterFile = if (com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled) {
            val kind = if (videoHdrMode.equals("OFF", ignoreCase = true)) {
                RecordDevelopMasterKind.SDR
            } else {
                RecordDevelopMasterKind.NDR
            }
            val config = RecordDevelopSessionConfig(
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                mode = cinemaMode.name.lowercase(Locale.US),
                colorProfile = videoColorProfile,
                startedAtEpochMs = System.currentTimeMillis()
            )
            recordDevelopStore(kind).createMasterFile(config).also { lastDevelopMasterFile = it }
        } else {
            null
        }
        return outputStore.create(contract, developMasterFile)
    }

    fun listRecordDevelopMasters(kind: RecordDevelopMasterKind): List<RecordDevelopMasterClip> {
        val candidates = buildList {
            recordDevelopDiscoveryStores(RecordDevelopMasterKind.SDR)
                .forEach { store -> addAll(store.listMasters()) }
            recordDevelopDiscoveryStores(RecordDevelopMasterKind.NDR)
                .forEach { store -> addAll(store.listMasters()) }
        }
        val byPath = LinkedHashMap<String, RecordDevelopMasterClip>()
        candidates.forEach { clip ->
            val resolved = resolveRecordDevelopMasterKind(clip)
            if (resolved.kind == kind) {
                val pathKey = runCatching { clip.file.canonicalPath }.getOrDefault(clip.file.absolutePath)
                val resolvedClip = if (clip.kind == resolved.kind) clip else clip.copy(kind = resolved.kind)
                byPath[pathKey] = resolvedClip
            }
        }
        return byPath.values.sortedByDescending { clip -> clip.createdAtEpochMs }
    }

    /**
     * Side-effect-free check for whether ANY light intermediate exists across both develop lanes. Used to
     * keep the SDR/NDR develop cards reachable for develop/delete even after [RecordDevelopModeFlag] is
     * turned back off, so recorded intermediates are never stranded. Creates no directories.
     */
    fun hasAnyRecordDevelopMasters(): Boolean =
        recordDevelopDiscoveryStores(RecordDevelopMasterKind.SDR).any { store -> store.hasMasters() } ||
            recordDevelopDiscoveryStores(RecordDevelopMasterKind.NDR).any { store -> store.hasMasters() }

    fun deleteRecordDevelopMaster(clip: RecordDevelopMasterClip): Boolean =
        recordDevelopStore(clip.kind).delete(clip)

    private fun recordDevelopStore(kind: RecordDevelopMasterKind): RecordDevelopMasterStore =
        if (kind == RecordDevelopMasterKind.SDR) recordDevelopSdrStore else recordDevelopNdrStore

    private fun recordDevelopDiscoveryStores(
        kind: RecordDevelopMasterKind
    ): List<RecordDevelopMasterStore> =
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir, recordDevelopPreferredRoot)
            .distinctBy { root ->
                runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            }
            .map { root -> RecordDevelopMasterStore(root, kind) }

    private data class ResolvedRecordDevelopMasterKind(
        val kind: RecordDevelopMasterKind,
        val source: String
    )

    private fun resolveRecordDevelopMasterKind(
        clip: RecordDevelopMasterClip
    ): ResolvedRecordDevelopMasterKind {
        VideoDevelopColorSidecar.read(clip.file)?.let { sidecar ->
            return when {
                sidecar.colorTransfer == VideoDevelopColorSidecar.DevelopColorTransfer.HLG ||
                    sidecar.colorTransfer == VideoDevelopColorSidecar.DevelopColorTransfer.PQ ->
                    ResolvedRecordDevelopMasterKind(RecordDevelopMasterKind.NDR, "sidecar-transfer")
                else -> ResolvedRecordDevelopMasterKind(RecordDevelopMasterKind.SDR, "sidecar-transfer")
            }
        }

        val name = clip.file.nameWithoutExtension.lowercase(Locale.US)
        return when {
            name.startsWith(RecordDevelopMasterKind.NDR.filePrefix.lowercase(Locale.US)) ||
                name.contains("ndr") ||
                name.contains("nivukx") ||
                name.contains("hdr") ||
                name.contains("hlg") ||
                name.contains("pq") ->
                ResolvedRecordDevelopMasterKind(RecordDevelopMasterKind.NDR, "filename")
            name.startsWith(RecordDevelopMasterKind.SDR.filePrefix.lowercase(Locale.US)) ||
                name.contains("sdr") ->
                ResolvedRecordDevelopMasterKind(RecordDevelopMasterKind.SDR, "filename")
            else -> ResolvedRecordDevelopMasterKind(clip.kind, "directory")
        }
    }

    private fun finalizeOutputTarget(finalDescription: String): RecordingFinalizedOutputEvidence? {
        val target = outputTarget ?: return null
        try {
            return outputStore.finalize(target, finalDescription)
        } catch (e: Exception) {
            Log.e(TAG, "finalizeOutputTarget failed", e)
            return null
        } finally {
            outputTarget = null
        }
    }

    private fun cleanupFailedOutputTarget() {
        val target = outputTarget ?: return
        // A record-then-develop master is the only copy of the recording. If the stop
        // path reached the muxer and encoded real video samples, retain that 10-bit master
        // even when a late drain/finalization exception is reported. Deleting it here makes
        // the recording disappear from both storage and the SDR conversion card. This is
        // preservation only: it does not alter the encoder, pixel format, bitrate, or any
        // processing stage. Trackless/empty targets still follow the normal cleanup path.
        val targetFile = target.file
        val preserveEncodedPrivateMaster =
            target.storageKind == RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER &&
                targetFile?.isFile == true &&
                targetFile.length() > 0L &&
                muxerStarted.get() &&
                videoTrackIndex >= 0 &&
                encodedVideoSampleCount > 0L
        try {
            if (preserveEncodedPrivateMaster) {
                Log.w(
                    TAG,
                    "Preserving encoded private master after stop failure for conversion: " +
                        "${targetFile.absolutePath} bytes=${targetFile.length()} " +
                        "samples=$encodedVideoSampleCount"
                )
            } else {
                outputStore.cleanupFailure(target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupFailedOutputTarget failed", e)
        } finally {
            outputTarget = null
            if (!preserveEncodedPrivateMaster) {
                // A failed record-then-develop recording must not surface a stale/partial intermediate to the
                // develop card: the private file was just deleted by cleanupFailure, and clearing this pointer
                // stops the stop-site from writing a colour sidecar for a clip that never saved.
                lastDevelopMasterFile = null
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVENIENCE
    // ─────────────────────────────────────────────────────────────────────────

    fun configure(width: Int, height: Int, fps: Int = videoFps) {
        videoWidth   = width
        videoHeight  = height
        videoFps     = fps
        if (!manualBitrateSelectionEnabled) {
            videoBitrate = bitrateFor(width, height, fps)
        }
        // Update currentFrameRate for adaptive polling (Tier 2.1)
        currentFrameRate = fps.toFloat()
    }

    fun updatePreviewFrameRate(previewFps: Float) {
        // Update frame rate for FPS-aware drain timeout scaling
        currentFrameRate = previewFps.coerceAtLeast(1f)
    }

    private fun resolveColorEncodingConfig(mime: String): RecordingColorEncodingConfig =
        RecordingVideoEncoderPlanResolver.resolveColorEncoding(
            mime = mime,
            videoHdrMode = videoHdrMode,
            videoColorSpace = videoColorSpace,
            logEnabled = videoLogEnabled
        )

    private fun activeEliteVideoQualityTierForPlan(): EliteVideoQualityTier? =
        when {
            rawVideoEnabled -> EliteVideoQualityTier.ELITE_RAW_12BIT
            videoLogEnabled -> activeLogEliteTierForPlan()
            effectiveHdrIntentLabel() != "OFF" -> activeHdrEliteTierForPlan()
            cinemaMasteringEnabled -> EliteVideoQualityTier.ELITE_PRO_SDR_10BIT
            else -> EliteVideoQualityTier.ELITE_NORMAL_SDR
        }

    private fun activeHdrEliteTierForPlan(): EliteVideoQualityTier? =
        when (EliteVideoQualityContract.normalizeHdrVersion(videoHdrMode)) {
            "NIVUKXVISION",
            "APEX_SHARP",
            "APEX_ZERO",
            "APEX_CINEMA" -> EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT
            "NDR10+" -> EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT
            else -> null
        }

    private fun activeLogEliteTierForPlan(): EliteVideoQualityTier =
        when (EliteVideoQualityContract.normalizeLogVersion(videoColorProfile)) {
            "N-Log Shadow" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT
            "N-Log Highlight" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT
            "N-Log Cinema" -> EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT
            else -> EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT
        }

    private fun EliteVideoQualityTier?.requiresRealTenBitTransport(): Boolean =
        when (this) {
            EliteVideoQualityTier.ELITE_NORMAL_SDR,
            EliteVideoQualityTier.ELITE_PRO_SDR_10BIT,
            EliteVideoQualityTier.ELITE_HDR_NDR10_PLUS_10BIT,
            EliteVideoQualityTier.ELITE_HDR_NIVUKX_VISION_10BIT,
            EliteVideoQualityTier.ELITE_LOG_N_LOG_10BIT,
            EliteVideoQualityTier.ELITE_LOG_N_LOG_BASE_10BIT,
            EliteVideoQualityTier.ELITE_LOG_N_LOG_SHADOW_10BIT,
            EliteVideoQualityTier.ELITE_LOG_N_LOG_HIGHLIGHT_10BIT,
            EliteVideoQualityTier.ELITE_LOG_N_LOG_CINEMA_10BIT -> true
            else -> false
        }

    private fun buildOutputDescription(): String =
        RecordingOutputDescriptionPolicy.build(currentOutputDescriptionSnapshot())

    /**
     * s6-f01: once-only, read-only capture of the encoder's actual output format keys for the
     * transport-truth evidence. containsKey-guarded so absent (vendor-dependent) keys stay at the
     * -1 sentinel (-> NOT_PROVEN) instead of a false 0. Fires at INFO_OUTPUT_FORMAT_CHANGED
     * (start-of-stream), never per frame; writes nothing back to the codec.
     */
    private fun captureEncoderTransportReadBack(format: MediaFormat) {
        if (encoderTransportReadBackAvailable) return
        if (format.containsKey(MediaFormat.KEY_PROFILE)) {
            muxedVideoProfileForEvidence = format.getInteger(MediaFormat.KEY_PROFILE)
        }
        if (format.containsKey(MediaFormat.KEY_LEVEL)) {
            muxedVideoLevelForEvidence = format.getInteger(MediaFormat.KEY_LEVEL)
        }
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            muxedVideoBitrateForEvidence = format.getInteger(MediaFormat.KEY_BIT_RATE)
        }
        encoderTransportReadBackAvailable = true
    }

    /**
     * Wire-truth 10-bit verdict from the ACTUAL encoder output format (not the requested profile or
     * the camera-side API acceptance): null until the encoder reports its output format, then true
     * only if the muxed profile is a real 10/12-bit profile. Lets callers prove a clip is genuinely
     * 10-bit and never silently ship 8-bit while claiming HDR/10-bit.
     */
    fun encoderOutputTenBitOrNull(): Boolean? {
        if (!encoderTransportReadBackAvailable || muxedVideoProfileForEvidence < 0) return null
        return muxedVideoProfileForEvidence == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
            muxedVideoProfileForEvidence == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            muxedVideoProfileForEvidence == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus ||
            (codecSupport.hevcMain12Profile?.let { muxedVideoProfileForEvidence == it } ?: false) ||
            (codecSupport.av1Main10Profile?.let { muxedVideoProfileForEvidence == it } ?: false)
    }

    private fun writeQualityCertificateForFinalizedRecording(
        outputPath: String?,
        finalizedOutput: RecordingFinalizedOutputEvidence?
    ) {
        val target = outputTarget
        val runtimeMetadata = linkedMapOf<String, String>().apply {
            putAll(runtimeEvidenceMetadataProvider?.invoke().orEmpty())
            putAll(byteBufferVideoInputEvidenceMetadata())
        }
        val evidenceMode = resolveQualityEvidenceMode(runtimeMetadata)
        val plannedSourceBitDepth = resolveSourceBitDepthForQualityEvidence(evidenceMode)
        val plannedStorageBitDepth = resolveStorageBitDepthForQualityEvidence(evidenceMode)
        val encoderSurfaceBitDepthEvidence = resolveEncoderSurfaceBitDepthEvidence(
            plannedSourceBitDepth = plannedSourceBitDepth,
            plannedStorageBitDepth = plannedStorageBitDepth,
            runtimeMetadata = runtimeMetadata
        )
        val sourceBitDepth = encoderSurfaceBitDepthEvidence.effectiveSourceBitDepth
        val storageBitDepth = encoderSurfaceBitDepthEvidence.effectiveStorageBitDepth
        val encoderSurfacePrecisionMetadata = mapOf(
            "vulkanEncoderSurfacePrecisionProofStatus" to
                encoderSurfaceBitDepthEvidence.proofStatus,
            "vulkanEncoderSurfaceActualBitDepth" to
                encoderSurfaceBitDepthEvidence.actualBitDepth.toString(),
            "vulkanEncoderSurfaceEffectiveSourceBitDepth" to sourceBitDepth.toString(),
            "vulkanEncoderSurfaceEffectiveStorageBitDepth" to storageBitDepth.toString(),
            "vulkanEncoderSurfaceFormat" to encoderSurfaceBitDepthEvidence.format,
            "vulkanEncoderSurfacePrecision" to encoderSurfaceBitDepthEvidence.precision,
            "vulkanEncoderSurfacePrecisionFallback" to
                encoderSurfaceBitDepthEvidence.precisionFallback.toString(),
            "vulkanEncoderSurfacePrecisionProven" to
                encoderSurfaceBitDepthEvidence.precisionProven.toString()
        )
        // s6-f10: additive zero-shutter-lag / persistent-input-surface evidence. Appended (never
        // overwriting) to the certificate detailMetadata; honest NOT_MEASURED until device-measured.
        val persistentInputSurfaceMetadata = mapOf(
            "persistentInputSurfaceAllowed" to persistentInputSurfaceAllowed.toString(),
            "persistentSurfaceRejected" to persistentInputSurfaceRejected.toString(),
            "encoderUsedPersistentInputSurface" to encoderSurfaceUsesPersistentInput.toString(),
            "recordStartToFirstFrameUs" to
                (if (lastRecordStartToFirstFrameUs < 0L) {
                    RecordingPersistentInputSurfaceSelection.NOT_MEASURED
                } else {
                    lastRecordStartToFirstFrameUs.toString()
                }),
            "recordStartLatencyWithinFrameBudget" to
                RecordingPersistentInputSurfaceSelection.recordStartLatencyEvidence(
                    lastRecordStartToFirstFrameUs,
                    videoFps
                ),
            "persistentInputSurfaceHeadFrameGap" to
                RecordingPersistentInputSurfaceSelection.headFrameGapEvidence(recordStartHeadFrameGap)
        )
        // s6-f01: additive encoder transport-truth evidence (read-only). Color parity is always
        // evaluable (atom source); encoder-output profile/level/bitrate are NOT_PROVEN unless the
        // device read them back. Surface-mode input-frame count is unobservable -> NOT_PROVEN.
        val encoderTransportMetadata = EncoderTransportEvidencePolicy.toMetadata(
            EncoderTransportEvidencePolicy.build(
                requestedProfile = requestedVideoProfileForEvidence,
                requestedLevel = requestedVideoLevelForEvidence,
                requestedBitrateBps = requestedVideoBitrateForEvidence,
                requestedColorStandard = requestedColorStandardForEvidence,
                requestedColorTransfer = requestedColorTransferForEvidence,
                requestedColorRange = requestedColorRangeForEvidence,
                muxedProfile = muxedVideoProfileForEvidence.takeIf { it >= 0 },
                muxedLevel = muxedVideoLevelForEvidence.takeIf { it >= 0 },
                muxedBitrateBps = muxedVideoBitrateForEvidence.takeIf { it >= 0 },
                muxedColorStandard = activeColorEncodingConfig.colorStandard,
                muxedColorTransfer = activeColorEncodingConfig.colorTransfer,
                muxedColorRange = activeColorEncodingConfig.colorRange,
                width = videoWidth,
                height = videoHeight,
                inputFrameCount = null,
                muxedFrameCount = encodedVideoSampleCount,
                droppedMuxerSamples = droppedMuxerSamples(),
                maxAvSyncDriftMs = maxAvSyncDriftMs,
                avSyncToleranceMs = AV_SYNC_TOLERANCE_MS
            )
        )
        val runtimeMetadataLookup: (String) -> String? = { key ->
            runtimeMetadata[key]?.takeIf { it.isNotBlank() } ?: sceneMetadataValue(key)
        }
        val normalizedHdr = if (evidenceMode == EliteVideoPipelineMode.HDR) {
            EliteVideoQualityContract.normalizeHdrVersion(videoHdrMode)
        } else {
            "OFF"
        }
        val selectedColorSpace =
            runtimeMetadataLookup("selectedColorSpace") ?: videoColorSpace
        val selectedColorScience =
            runtimeMetadataLookup("selectedColorScience") ?: videoColorProfile
        val colorSelectionSource =
            runtimeMetadataLookup("colorSelectionSource") ?: ManualVideoColorSelectionPolicy.USER_MANUAL
        val normalizedLog = EliteVideoQualityContract.normalizeLogVersion(selectedColorScience)
        val shouldWriteNeoHdrSidecar =
            evidenceMode == EliteVideoPipelineMode.HDR &&
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdr)
        var truthMetadata = NeoProfileTruthMetadataPolicy.resolve(
            request = EliteVideoQualityRequest(
                mode = evidenceMode,
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                codec = videoCodec,
                hdrMode = videoHdrMode,
                logProfile = selectedColorScience,
                rawBitDepth = if (evidenceMode == EliteVideoPipelineMode.RAW) storageBitDepth else 0,
                sourceBitDepth = sourceBitDepth,
                storageBitDepth = storageBitDepth,
                tenBitTransport = storageBitDepth >= 10 && !simulatedTenBitFallbackActive,
                colorSpace = selectedColorSpace,
                colorScience = selectedColorScience,
                colorSpaceSelectedByUser = colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                colorScienceSelectedByUser = colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                savedColorSpacePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(colorSelectionSource),
                savedColorSciencePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(colorSelectionSource),
                exactSelectedResolution = true
            ),
            normalizedHdrVersion = normalizedHdr,
            normalizedLogVersion = normalizedLog,
            neoDynamicMetadataSidecarWritten = false
        )
        if (encoderSurfaceBitDepthEvidence.applicable &&
            !encoderSurfaceBitDepthEvidence.precisionProven
        ) {
            truthMetadata = truthMetadata.copy(
                activeTransport = "VULKAN_ENCODER_SURFACE_PRECISION_NOT_PROVEN",
                encodedBitDepth = 0,
                internalMathBitDepth = 0,
                transportFallbackReason = "ENCODER_SURFACE_PRECISION_NOT_PROVEN"
            )
        }
        val neoHdrSidecarWritten = if (shouldWriteNeoHdrSidecar) {
            val scene = HdrStaticMetadataPolicy.SceneMetadata.fromDescription(sceneMetadataDescription)
            val recordingId = finalizedOutput?.fileName?.substringBeforeLast('.')
                ?: target?.descriptor?.fileName?.substringBeforeLast('.')
                ?: outputPath.orEmpty()
            val sidecar = NeoHdrDynamicMetadataSidecar.build(
                selectedProfile = truthMetadata.selectedProfile,
                averageLuma = scene.averageLuma,
                highlightRatio = scene.highlightRatio,
                dynamicRangeScore = scene.dynamicRangeScore,
                inlineDynamicMetadataWritten = truthMetadata.dynamicMetadataWritten,
                activeTransport = truthMetadata.activeTransport,
                transportFallbackReason = truthMetadata.transportFallbackReason,
                transfer = truthMetadata.transfer,
                primaries = truthMetadata.primaries,
                matrix = truthMetadata.matrix,
                encodedBitDepth = truthMetadata.encodedBitDepth
            )
            runCatching {
                NeoHdrDynamicMetadataSidecarStore.write(context.filesDir, recordingId, sidecar)
            }.onSuccess { neoSidecar ->
                Log.i(TAG, "Neo HDR dynamic metadata sidecar -> ${neoSidecar.absolutePath}")
            }.onFailure { throwable ->
                Log.w(TAG, "Neo HDR dynamic metadata sidecar write failed", throwable)
            }.getOrNull()?.isFile == true
        } else {
            false
        }
        truthMetadata = truthMetadata.copy(
            neoDynamicMetadataSidecarWritten = neoHdrSidecarWritten
        )
        val selectedResolution = "${videoWidth}x${videoHeight}"
        val qualitySignature = buildQualitySignature(evidenceMode)
        val previewQualitySignature =
            runtimeMetadataLookup("previewQualitySignature") ?: qualitySignature
        val recordingQualitySignature =
            runtimeMetadataLookup("recordingQualitySignature") ?: qualitySignature
        val captureTruthSignature =
            runtimeMetadataLookup("parityCertificateSignature") ?: currentOutputDescriptionSnapshot().toString()
        val runtimeEvidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = runtimeMetadataLookup,
            cpuUsagePercent = recordingCpuUsagePercent(),
            maxFrameTimeMs = maxObservedFrameTimeMs()
        )
        val encoderCapabilityUpperBoundForEvidence =
            encoderCapabilityUpperBound(RecordingCodecCatalog.mime(videoCodec), Size(videoWidth, videoHeight))
        val noiseMetrics = VideoNoiseMetrics.fromMetadata(::sceneMetadataValue)
        val detailMetadata = VideoDetailSharpeningPolicy.recordingEvidenceMetadata(
            previewMetadata = VideoDetailSharpeningPolicy.metadataFrom(::sceneMetadataValue),
            recordingGpuFrameImportValidated = runtimeEvidence.processedRecordingFramesValidated
        )
        val measuredSessionOutputBitrate =
            encodedOutputBitrateMeter.measuredSessionBitrateBitsPerSecond()
        val ingressSnapshot =
            byteBufferImageIngressTelemetry.snapshot(encodedFrames = encodedVideoSampleCount)
        val effectiveFpsForEvidence = if (activeEncoderQualityTelemetry.thermalDowngradeActive && videoFps > 25) {
            25
        } else {
            videoFps
        }
        val encodedTimelineDurationUsForEvidence =
            encodedVideoTimelineDurationUsForEvidence(
                firstVideoPtsUs = firstOutputVideoPtsUs,
                lastVideoPtsUs = lastVideoPtsUs,
                encodedVideoSampleCount = encodedVideoSampleCount,
                fps = effectiveFpsForEvidence
            )
        val surfaceTimelineDurationUsForEvidence =
            encodedTimelineDurationUsForEvidence.takeIf { it > 0L }
                ?: finalActiveRecordingDurationUsForEvidence
        val cadenceEvidenceDurationUs =
            if (videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010) {
                effectiveP010EvidenceDurationUs(
                    wallActiveDurationUs = finalActiveRecordingDurationUsForEvidence,
                    fps = effectiveFpsForEvidence,
                    ingressSnapshot = ingressSnapshot
                )
            } else {
                surfaceTimelineDurationUsForEvidence
            }
        val nominalExpectedFrameCount = expectedFrameCountForEvidence(
            activeDurationUs = cadenceEvidenceDurationUs,
            fps = effectiveFpsForEvidence,
            cadenceToleranceFrames = QUALITY_EVIDENCE_CADENCE_TOLERANCE_FRAMES
        )
        val byteBufferP010Input = videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010
        val strictRuntimeHotPathDroppedFrames =
            strictRuntimeHotPathDroppedFramesForEvidence(runtimeMetadata, runtimeEvidence)
        val expectedFrameCount =
            if (byteBufferP010Input) {
                expectedP010FrameCountForEvidence(
                    nominalExpectedFrameCount = nominalExpectedFrameCount,
                    ingressSnapshot = ingressSnapshot
                )
            } else {
                nominalExpectedFrameCount
            }
        val droppedFramesForEvidence =
            droppedMuxerSamples() +
                strictRuntimeHotPathDroppedFrames +
                if (byteBufferP010Input) {
                    p010DroppedFramesForEvidence(ingressSnapshot)
                } else {
                    0L
                }
        val droppedFrameCountForEvidence =
            droppedFramesForEvidence.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val recordingIdForEvidence = finalizedOutput?.fileName?.substringBeforeLast('.')
            ?: target?.descriptor?.fileName?.substringBeforeLast('.')
            ?: outputPath.orEmpty()
        val recordedFileNameForEvidence = finalizedOutput?.fileName
            ?: target?.descriptor?.fileName
            ?: outputPath.orEmpty()
        val recordedFileBytesForEvidence = finalizedOutput?.fileBytes?.takeIf { it > 0L }
            ?: target?.file?.takeIf { it.isFile }?.length()?.coerceAtLeast(1L)
            ?: 0L
        val mediaStoreValidatedForEvidence = finalizedOutput?.mediaStoreValidated == true
        val deviceRuntimeProof = RecordingDeviceRuntimeProofPolicy.evaluate(
            runtimeEvidence = runtimeEvidence,
            expectedFrameCount = expectedFrameCount,
            actualFrameCount = encodedVideoSampleCount,
            droppedFrames = droppedFrameCountForEvidence,
            recordedFileName = recordedFileNameForEvidence,
            recordedFileBytes = recordedFileBytesForEvidence,
            mediaStoreValidated = mediaStoreValidatedForEvidence,
            runtimeMetadata = runtimeMetadata
        )
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            RecordingQualityEvidenceRequest(
                mode = evidenceMode,
                selectedProfile = truthMetadata.selectedProfile,
                activeTransport = truthMetadata.activeTransport,
                transfer = truthMetadata.transfer,
                primaries = truthMetadata.primaries,
                matrix = truthMetadata.matrix,
                codec = RecordingCodecCatalog.label(videoCodec).lowercase(Locale.US),
                encodedBitDepth = truthMetadata.encodedBitDepth,
                internalMathBitDepth = truthMetadata.internalMathBitDepth,
                sourceBitDepth = sourceBitDepth,
                storageBitDepth = storageBitDepth,
                width = videoWidth,
                height = videoHeight,
                fps = videoFps,
                bitrateBps = measuredSessionOutputBitrate,
                requestedBitrateBps = requestedOutputBitrateForEvidence,
                policyTargetBitrateBps = policyTargetBitrateForEvidence,
                appliedBitrateBps = activeOutputBitrate,
                measuredOutputBitrateBps = measuredSessionOutputBitrate,
                encoderCapabilityUpperBoundBps = encoderCapabilityUpperBoundForEvidence,
                manualBitrateSelectionEnabled = manualBitrateSelectionEnabled,
                selectedResolution = selectedResolution,
                actualResolution = selectedResolution,
                normalizedHdrVersion = normalizedHdr,
                normalizedLogVersion = normalizedLog,
                rawSensorOriginal = evidenceMode == EliteVideoPipelineMode.RAW,
                captureTruthSignature = captureTruthSignature,
                previewQualitySignature = previewQualitySignature,
                recordingQualitySignature = recordingQualitySignature,
                expectedFrameCount = expectedFrameCount,
                actualFrameCount = encodedVideoSampleCount,
                droppedFrames = droppedFrameCountForEvidence,
                maxAllowedDroppedFrames = 0,
                probe = null,
                ffprobeValidated = false,
                mediaInfoValidated = false,
                deviceLogValidated = deviceRuntimeProof.deviceLogValidated,
                recordingId = recordingIdForEvidence,
                recordedFileName = recordedFileNameForEvidence,
                recordedFileBytes = recordedFileBytesForEvidence,
                adbLogEvidencePath = deviceRuntimeProof.adbLogEvidencePath,
                mediaStoreValidated = mediaStoreValidatedForEvidence,
                noiseMetrics = noiseMetrics,
                detailMetadata =
                    detailMetadata +
                        runtimeMetadata +
                        deviceRuntimeProof.metadata +
                        mapOf(
                            "wallActiveRecordingDurationUs" to
                                finalActiveRecordingDurationUsForEvidence.toString(),
                            "encodedTimelineDurationUs" to
                                encodedTimelineDurationUsForEvidence.toString(),
                            "cadenceEvidenceDurationUs" to
                                cadenceEvidenceDurationUs.toString(),
                            "strictRuntimeHotPathDroppedFrames" to
                                strictRuntimeHotPathDroppedFrames.toString()
                        ) +
                        truthMetadata.asMap() +
                        encoderSurfacePrecisionMetadata +
                        persistentInputSurfaceMetadata +
                        encoderTransportMetadata +
                        EliteVisionProductionMeasurement.measuredTierOmegaMetadata(
                            EliteVisionDecodedFrameProvider.Unavailable
                        ),
                encoderCapabilityCheck = activeEncoderQualityTelemetry.capabilityCheck,
                encoderSceneComplexity = activeEncoderQualityTelemetry.sceneComplexity,
                encoderEstimatedLux = activeEncoderQualityTelemetry.estimatedLux,
                encoderBitrateMultiplier = activeEncoderQualityTelemetry.bitrateMultiplier,
                encoderSceneComplexityBoostApplied = activeEncoderQualityTelemetry.sceneComplexityBoostApplied,
                encoderLowLightBoostApplied = activeEncoderQualityTelemetry.lowLightBoostApplied,
                encoderHdrOrLogBoostApplied = activeEncoderQualityTelemetry.hdrOrLogBoostApplied,
                encoderThermalDowngradeActive = activeEncoderQualityTelemetry.thermalDowngradeActive,
                allGpuProcessingClaimed = runtimeEvidence.allGpuProcessingClaimed,
                zeroCopyProcessingClaimed = runtimeEvidence.zeroCopyProcessingClaimed,
                gpuProcessingValidated = runtimeEvidence.gpuProcessingValidated,
                processedRecordingFramesValidated = runtimeEvidence.processedRecordingFramesValidated,
                zeroCopyValidated = runtimeEvidence.zeroCopyValidated,
                performanceTelemetryValidated = runtimeEvidence.performanceTelemetryValidated,
                gpuProcessingPath = runtimeEvidence.gpuProcessingPath,
                zeroCopyEvidencePath = runtimeEvidence.zeroCopyEvidencePath,
                enhancementUniformSignature = runtimeEvidence.enhancementUniformSignature,
                previewEnhancementUniformSignature = runtimeEvidence.previewEnhancementUniformSignature,
                recordingEnhancementUniformSignature = runtimeEvidence.recordingEnhancementUniformSignature,
                enhancementTemporalBlend = runtimeEvidence.enhancementTemporalBlend,
                enhancementSpatialDenoise = runtimeEvidence.enhancementSpatialDenoise,
                enhancementChromaDenoise = runtimeEvidence.enhancementChromaDenoise,
                enhancementSharpening = runtimeEvidence.enhancementSharpening,
                enhancementSubjectDetailGain = runtimeEvidence.enhancementSubjectDetailGain,
                enhancementDetailProfile = runtimeEvidence.enhancementDetailProfile,
                cpuUsagePercent = runtimeEvidence.cpuUsagePercent,
                maxFrameTimeMs = runtimeEvidence.maxFrameTimeMs,
                targetCpuUsagePercent = runtimeEvidence.targetCpuUsagePercent,
                generatedAtEpochMs = System.currentTimeMillis().coerceAtLeast(recordingStartEpochMs)
            )
        )
        runCatching {
            RecordingQualityCertificateStore.write(context.filesDir, certificate)
        }.onSuccess { sidecar ->
            val byteBufferEvidence = byteBufferVideoInputEvidenceMetadata()
            Log.i(
                TAG,
                "Quality certificate -> ${sidecar.absolutePath} " +
                    "status=${certificate.status} failures=${certificate.metadata["failureCodes"]} " +
                    "requestedBitrate=${certificate.metadata["requestedBitrateBps"]} " +
                    "appliedBitrate=${certificate.metadata["appliedBitrateBps"]} " +
                    "encoderCapabilityUpperBound=${certificate.metadata["encoderCapabilityUpperBoundBps"]} " +
                    "manualBitrateCapped=${certificate.metadata["manualBitrateCapped"]} " +
                    "previewRecordingEnhancementMatch=${certificate.metadata["previewRecordingEnhancementMatch"]} " +
                    "enhancementUniformValidated=${certificate.metadata["recordingEnhancementUniformValidated"]} " +
                    "enhancementIdentity=${certificate.metadata["recordingEnhancementIdentity"]} " +
                    "gpuProcessingPath=${certificate.metadata["gpuProcessingPath"]} " +
                    "zeroCopyValidated=${certificate.metadata["zeroCopyValidated"]} " +
                    "performanceTelemetryValidated=${certificate.metadata["performanceTelemetryValidated"]} " +
                    "vendorHdrCertification=${certificate.metadata["vendorHdrCertification"]} " +
                    "officialHdr10PlusMetadataWritten=${certificate.metadata["officialHdr10PlusMetadataWritten"]} " +
                    "vendorCertifiedHdr10Plus=${certificate.metadata["vendorCertifiedHdr10Plus"]} " +
                    "vendorCertifiedDolbyVision=${certificate.metadata["vendorCertifiedDolbyVision"]} " +
                    "dolbyRpuWritten=${certificate.metadata["dolbyRpuWritten"]} " +
                    "externalHdrStandardSubstitution=${certificate.metadata["externalHdrStandardSubstitution"]} " +
                    "proprietaryProfilePreserved=${certificate.metadata["proprietaryProfilePreserved"]} " +
                    "androidHdrTransportOnly=${certificate.metadata["androidHdrTransportOnly"]} " +
                    "neoHdrNoVendorDependency=${certificate.metadata["neoHdrNoVendorDependency"]} " +
                    "nLogVendorDependency=${certificate.metadata["nLogVendorDependency"]} " +
                    "cpuUsagePercent=${certificate.metadata["cpuUsagePercent"]} " +
                    "maxFrameTimeMs=${certificate.metadata["maxFrameTimeMs"]}"
            )
            if (byteBufferEvidence.isNotEmpty()) {
                Log.i(
                    TAG,
                    "P010 bytebuffer evidence -> " +
                        byteBufferEvidence.entries.joinToString(separator = " ") { (key, value) ->
                            "$key=$value"
                        }
                )
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Quality certificate write failed", throwable)
        }
    }

    private fun resolveStrictStopFrameGateEvidence(): StrictStopFrameGateEvidence {
        val runtimeMetadata = linkedMapOf<String, String>().apply {
            putAll(runtimeEvidenceMetadataProvider?.invoke().orEmpty())
            putAll(byteBufferVideoInputEvidenceMetadata())
        }
        val evidenceMode = resolveQualityEvidenceMode(runtimeMetadata)
        val runtimeEvidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = { key -> runtimeMetadata[key]?.takeIf { it.isNotBlank() } },
            cpuUsagePercent = recordingCpuUsagePercent(),
            maxFrameTimeMs = maxObservedFrameTimeMs()
        )
        val processedEncodedMode =
            evidenceMode != EliteVideoPipelineMode.RAW &&
                evidenceMode != EliteVideoPipelineMode.NPV10
        val strictOffscreenPath =
            isStrictOffscreenRecordingPath(runtimeMetadata, runtimeEvidence.gpuProcessingPath)
        if (!processedEncodedMode || !strictOffscreenPath) {
            return StrictStopFrameGateEvidence(
                required = false,
                expectedVideoSamples = 1L,
                evidenceDurationUs = 0L,
                gpuProcessingPath = runtimeEvidence.gpuProcessingPath,
                evidenceMode = evidenceMode
            )
        }

        val encodedTimelineDurationUs = encodedVideoTimelineDurationUsForEvidence(
            firstVideoPtsUs = firstOutputVideoPtsUs,
            lastVideoPtsUs = lastVideoPtsUs,
            encodedVideoSampleCount = encodedVideoSampleCount,
            fps = videoFps
        )
        val evidenceDurationUs =
            finalStrictFrameGateDurationUsForEvidence
                .takeIf { it > 0L }
                ?: encodedTimelineDurationUs.takeIf { it > 0L }
                ?: finalActiveRecordingDurationUsForEvidence
        return StrictStopFrameGateEvidence(
            required = true,
            expectedVideoSamples = expectedFrameCountForEvidence(
                activeDurationUs = evidenceDurationUs,
                fps = videoFps,
                cadenceToleranceFrames = QUALITY_EVIDENCE_CADENCE_TOLERANCE_FRAMES
            ),
            evidenceDurationUs = evidenceDurationUs,
            gpuProcessingPath = runtimeEvidence.gpuProcessingPath,
            evidenceMode = evidenceMode
        )
    }

    private fun isStrictOffscreenRecordingPath(
        runtimeMetadata: Map<String, String>,
        gpuProcessingPath: String
    ): Boolean {
        val normalizedGpuPath = gpuProcessingPath.trim().uppercase(Locale.US)
        val normalizedFeedPath = runtimeMetadata["parityFeedPath"]
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()
        return normalizedGpuPath == "VULKAN_OFFSCREEN_AHB_EGL_ENCODER" ||
            normalizedGpuPath.contains("OFFSCREEN_AHB_EGL_ENCODER") ||
            normalizedFeedPath == "OFFSCREEN_AHB_EGL_ENCODER" ||
            runtimeMetadata.booleanValue("offscreenAhbEglEncoderActive") ||
            runtimeMetadata.longValue("offscreenEglSubmittedFrames") > 0L
    }

    private fun strictRuntimeHotPathDroppedFramesForEvidence(
        runtimeMetadata: Map<String, String>,
        runtimeEvidence: RecordingRuntimeEvidence
    ): Long {
        if (!isStrictOffscreenRecordingPath(runtimeMetadata, runtimeEvidence.gpuProcessingPath)) {
            return 0L
        }
        val importFailures = maxOf(
            runtimeMetadata.longValue("offscreenEglFailedFrames"),
            runtimeMetadata.longValue("vulkanSessionFailedImports")
        )
        val dispatchFailures =
            runtimeMetadata.longValue("realtimeDispatchDroppedFrames") +
                runtimeMetadata.longValue("realtimeDispatchRejectedFrames")
        return (importFailures + dispatchFailures).coerceAtLeast(0L)
    }

    private fun Map<String, String>.booleanValue(key: String): Boolean =
        when (this[key]?.trim()?.lowercase(Locale.US)) {
            "true", "1", "yes", "supported", "enabled", "pass" -> true
            else -> false
        }

    private fun Map<String, String>.longValue(key: String): Long =
        this[key]?.trim()?.toLongOrNull() ?: 0L

    private fun resolveQualityEvidenceMode(
        runtimeMetadata: Map<String, String> = emptyMap()
    ): EliteVideoPipelineMode =
        when {
            rawVideoEnabled || cinemaMode == CinemaVideoMode.RAW -> EliteVideoPipelineMode.RAW
            videoLogEnabled || cinemaMode == CinemaVideoMode.LOG -> EliteVideoPipelineMode.LOG
            effectiveHdrIntentLabel() != "OFF" -> EliteVideoPipelineMode.HDR
            runtimeSdrQualityIntent(runtimeMetadata).equals("PRO", ignoreCase = true) ->
                EliteVideoPipelineMode.PRO
            runtimeSdrQualityIntent(runtimeMetadata).equals("NORMAL", ignoreCase = true) ->
                EliteVideoPipelineMode.NORMAL
            cinemaMasteringEnabled -> EliteVideoPipelineMode.PRO
            else -> EliteVideoPipelineMode.NORMAL
        }

    private fun runtimeSdrQualityIntent(runtimeMetadata: Map<String, String>): String? {
        val direct = runtimeMetadata["sdrQualityIntent"]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        return listOf(
            runtimeMetadata["recordingEnhancementUniformSignature"],
            runtimeMetadata["previewEnhancementUniformSignature"],
            runtimeMetadata["enhancementUniformSignature"],
            sceneMetadataValue("recordingEnhancementUniformSignature"),
            sceneMetadataValue("previewEnhancementUniformSignature"),
            sceneMetadataValue("enhancementUniformSignature")
        ).firstNotNullOfOrNull { signature ->
            when {
                signature.isNullOrBlank() -> null
                signature.contains("sdrQualityIntent=PRO", ignoreCase = true) -> "PRO"
                signature.contains("sdrQualityIntent=NORMAL", ignoreCase = true) -> "NORMAL"
                else -> null
            }
        }
    }

    private fun resolveSourceBitDepthForQualityEvidence(mode: EliteVideoPipelineMode): Int =
        when (mode) {
            EliteVideoPipelineMode.RAW -> 12
            EliteVideoPipelineMode.HDR,
            EliteVideoPipelineMode.LOG,
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.NORMAL -> if (!simulatedTenBitFallbackActive && activeColorEncodingConfig.main10Requested) 10 else 8
            EliteVideoPipelineMode.NPV10 -> 10
        }

    private fun resolveStorageBitDepthForQualityEvidence(mode: EliteVideoPipelineMode): Int =
        when {
            mode == EliteVideoPipelineMode.RAW -> 12
            activeBitDepthLabel.contains("10") && !simulatedTenBitFallbackActive -> 10
            else -> resolveSourceBitDepthForQualityEvidence(mode)
        }

    private fun buildQualitySignature(mode: EliteVideoPipelineMode): String =
        listOf(
            mode.name,
            "${videoWidth}x${videoHeight}",
            "${videoFps}fps",
            RecordingCodecCatalog.label(videoCodec),
            activeBitDepthLabel,
            activeDynamicRangeLabel,
            activeLogCurveLabel,
            videoColorSpace,
            activeQualityTierLabel,
            "bitrate=$activeOutputBitrate"
        ).joinToString(separator = "|")

    private fun sceneMetadataValue(key: String): String? =
        sceneMetadataDescription
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("$key=") }
            .map { it.substringAfter('=') }
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun recordingCpuUsagePercent(): Float? {
        finalRecordingCpuUsagePercentForEvidence?.let { return it }
        return RecordingRuntimeEvidencePolicy.processCpuUsagePercent(
            startProcessCpuMs = recordingStartProcessCpuMs,
            startElapsedNs = recordingStartNs,
            endProcessCpuMs = Process.getElapsedCpuTime(),
            endElapsedNs = System.nanoTime()
        )
    }

    private fun maxObservedFrameTimeMs(): Float? =
        maxOf(maxEncodedFrameIntervalMs, framePacingJitterMs.toFloat())
            .takeIf { it > 0f }

    private fun byteBufferVideoInputEvidenceMetadata(): Map<String, String> {
        val ingressSnapshot =
            byteBufferImageIngressTelemetry.snapshot(encodedFrames = encodedVideoSampleCount)
        val active = videoInputMode == RecordingVideoInputMode.BYTEBUFFER_P010 ||
            byteBufferP010FramesQueued.get() > 0L ||
            ingressSnapshot.callbackFrames > 0L
        if (!active) return emptyMap()
        val throughput = RecordingThroughputBudgetPolicy.evaluate(ingressSnapshot, videoFps)
        return linkedMapOf(
            "byteBufferReadoutEncoderActive" to "true",
            "byteBufferP010EncoderActive" to "true",
            "byteBufferInputColorFormat" to "COLOR_FormatYUVP010",
            "byteBufferP010ColorFormat" to "COLOR_FormatYUVP010",
            "byteBufferP010CallbackFrames" to ingressSnapshot.callbackFrames.toString(),
            "byteBufferP010AcceptedFrames" to ingressSnapshot.acceptedFrames.toString(),
            "byteBufferP010AdmissionRejectedFrames" to
                ingressSnapshot.admissionRejectedFrames.toString(),
            "byteBufferP010ProcessingDroppedFrames" to
                ingressSnapshot.processingDroppedFrames.toString(),
            "byteBufferP010AdmissionCapacity" to
                ingressSnapshot.admissionCapacity.toString(),
            "byteBufferP010PeakInFlightFrames" to
                ingressSnapshot.peakInFlightFrames.toString(),
            "byteBufferP010InFlightFrames" to ingressSnapshot.inFlightFrames.toString(),
            "byteBufferP010CallbackAccountingGap" to
                ingressSnapshot.callbackAccountingGap.toString(),
            "byteBufferP010AcceptedAccountingGap" to
                ingressSnapshot.acceptedAccountingGap.toString(),
            "byteBufferP010IngressHealth" to
                ingressSnapshot.health(minSustainedCallbacks = videoFps.toLong()).name,
            "byteBufferP010FirstSourceTimestampNs" to
                ingressSnapshot.firstSourceTimestampNs.toString(),
            "byteBufferP010LastSourceTimestampNs" to
                ingressSnapshot.lastSourceTimestampNs.toString(),
            "byteBufferP010NativeDirectCopies" to
                ingressSnapshot.nativeDirectCopies.toString(),
            "byteBufferP010DirectBufferCopies" to
                ingressSnapshot.directBufferCopies.toString(),
            "byteBufferP010CopyFailures" to ingressSnapshot.copyFailures.toString(),
            "byteBufferP010AverageCopyUs" to ingressSnapshot.averageCopyUs.toString(),
            "byteBufferP010MaxCopyUs" to ingressSnapshot.maxCopyUs.toString(),
            "byteBufferP010AverageCodecInputWaitUs" to
                ingressSnapshot.averageCodecInputWaitUs.toString(),
            "byteBufferP010MaxCodecInputWaitUs" to
                ingressSnapshot.maxCodecInputWaitUs.toString(),
            "byteBufferP010FramesQueued" to byteBufferP010FramesQueued.get().toString(),
            "byteBufferSubmittedFrames" to ingressSnapshot.acceptedFrames.toString(),
            "byteBufferP010FramesEncoded" to encodedVideoSampleCount.toString(),
            "byteBufferEncodedFrames" to encodedVideoSampleCount.toString(),
            "byteBufferP010FrameDrops" to byteBufferP010FrameDrops.get().toString(),
            "byteBufferFrameDrops" to byteBufferP010FrameDrops.get().toString(),
            "byteBufferP010EncoderName" to byteBufferP010EncoderName,
            "byteBufferP010LinearBlockCopyFree" to
                p010LinearBlockCopyFreeCompatible.toString(),
            "byteBufferP010LinearBlockAdmissionEnabled" to
                p010LinearBlockAdmissionEnabled.toString(),
            "byteBufferP010LinearBlockFramesQueued" to
                p010LinearBlockFramesQueued.get().toString(),
            "processedRecordingPath" to "APP_P010_BYTEBUFFER_ENCODER",
            "gpuProcessingPath" to "APP_P010_BYTEBUFFER_ENCODER",
            "gpuProcessingValidated" to "false",
            "zeroCopyValidated" to "false",
            "allGpuProcessingClaimed" to "false",
            "zeroCopyProcessingClaimed" to "false"
        ) + throughput.toEvidenceMetadata()
    }

    private fun resetByteBufferVideoInputTelemetry() {
        if (!byteBufferImageIngressTelemetry.reset()) {
            val beforeDrain = byteBufferImageIngressTelemetry.snapshot(
                encodedFrames = encodedVideoSampleCount
            )
            Log.w(
                TAG,
                "P010 ingress telemetry reset deferred while inputs are still in flight " +
                    "pending=${beforeDrain.inFlightFrames} queued=${beforeDrain.queuedFrames} " +
                    "accepted=${beforeDrain.acceptedFrames} encoded=$encodedVideoSampleCount"
            )
            runCatching {
                NeoLogger.critical(
                    TAG,
                    "P010_INGRESS_RESET_DEFERRED pending=${beforeDrain.inFlightFrames} " +
                        "queued=${beforeDrain.queuedFrames} accepted=${beforeDrain.acceptedFrames} " +
                        "drops=${beforeDrain.processingDroppedFrames} encoded=$encodedVideoSampleCount"
                )
            }
            awaitByteBufferImageInputIdle(timeoutMs = P010_IMAGE_INPUT_DRAIN_TIMEOUT_MS)
            if (!byteBufferImageIngressTelemetry.reset()) {
                val afterDrain = byteBufferImageIngressTelemetry.snapshot(
                    encodedFrames = encodedVideoSampleCount
                )
                val message =
                    "P010 bytebuffer input is still draining from the previous session " +
                        "pending=${afterDrain.inFlightFrames} queued=${afterDrain.queuedFrames} " +
                        "accepted=${afterDrain.acceptedFrames} encoded=$encodedVideoSampleCount"
                Log.e(TAG, message)
                runCatching { NeoLogger.critical(TAG, "P010_INGRESS_RESET_BLOCKED $message") }
                throw IllegalStateException(message)
            }
        }
        byteBufferVideoEosQueued.set(false)
        byteBufferP010FramesQueued.set(0L)
        byteBufferP010FrameDrops.set(0L)
        byteBufferP010EncoderName = ""
        p010LinearBlockFramesQueued.set(0L)
        byteBufferLastQueuedPtsUs = -1L
        p010FirstAcceptLogged.set(false)
        p010FirstEncodeLogged.set(false)
        byteBufferEosInputUnavailable = false
    }

    private fun currentOutputDescriptionSnapshot(): RecordingOutputDescriptionSnapshot =
        RecordingOutputDescriptionSnapshot(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoFps = videoFps,
            codecLabel = RecordingCodecCatalog.label(videoCodec),
            bitDepthLabel = activeBitDepthLabel,
            colorSpace = videoColorSpace,
            hdrIntentLabel = effectiveHdrIntentLabel(),
            dynamicRangeLabel = activeDynamicRangeLabel,
            logCurveLabel = activeLogCurveLabel,
            qualityTierLabel = activeQualityTierLabel,
            cinemaMasteringEnabled = cinemaMasteringEnabled,
            allIntraEnabled = allIntraActiveInternal,
            gopSeconds = activeIFrameIntervalSeconds,
            simulatedTenBitFallbackActive = simulatedTenBitFallbackActive,
            bitrateBitsPerSecond = activeOutputBitrate,
            sceneMetadataDescription = sceneMetadataDescription
        )

    private fun effectiveHdrIntentLabel(): String =
        if (videoLogEnabled || rawVideoEnabled) {
            "OFF"
        } else {
            RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(videoHdrMode)
        }

    private fun resolveActiveLogCurveLabel(): String =
        NeoLogProfilePolicy.resolve(
            profileName = videoColorProfile,
            logEnabled = videoLogEnabled
        ).label

    private fun sanitizedOutputSuffix(): String {
        val raw = outputLabelSuffix?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return if (sanitized.isEmpty()) "" else "_$sanitized"
    }

    fun resolveSupportedSize(requested: Size, codec: GpuVideoCodec = videoCodec, fps: Int = videoFps): Size {
        val mime = RecordingCodecCatalog.mime(codec)
        return resolveVideoConfig(mime, requested.width, requested.height, fps).size
    }

    fun resolveSupportedBitrate(
        requestedBitrate: Int,
        size: Size = Size(videoWidth, videoHeight),
        codec: GpuVideoCodec = videoCodec,
        videoDetailProfile: VideoDetailProfile? = this.videoDetailProfile,
        fps: Int = videoFps
    ): Int {
        val mime = RecordingCodecCatalog.mime(codec)
        val automaticQualityBudgetFloor = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            RecordingVideoQualityBudgetRequest(
                width = size.width,
                height = size.height,
                fps = fps,
                codec = codec,
                hdrMode = videoHdrMode,
                logEnabled = videoLogEnabled,
                logProfile = videoColorProfile,
                tenBit = tenBitEnabled,
                colorSpace = videoColorSpace,
                videoDetailProfile = videoDetailProfile
            )
        )
        val encoderLowerBound = encoderCapabilityLowerBound(mime, size)
        val minimumBitrateFloor = if (manualBitrateSelectionEnabled) {
            encoderLowerBound ?: 1
        } else {
            maxOf(
                minimumBitrateFloorFor(size.width, size.height, fps = fps),
                encoderLowerBound ?: Int.MIN_VALUE
            )
        }
        val qualityBudgetFloor = if (manualBitrateSelectionEnabled) {
            minimumBitrateFloor
        } else {
            automaticQualityBudgetFloor
        }
        val encoderUpperBound = encoderCapabilityUpperBound(mime, size)
            ?: (RecordingCodecCatalog.maxBitrateMbps(codec).toLong() * 1_000_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return RecordingUserBitratePolicy.resolveRequestedBitrate(
            requestedBitrate = requestedBitrate,
            minimumBitrateFloor = minimumBitrateFloor,
            qualityBudgetFloor = qualityBudgetFloor,
            encoderCapabilityUpperBound = encoderUpperBound,
            width = size.width,
            height = size.height,
            fps = fps,
            cinemaMasteringEnabled = cinemaMasteringEnabled,
            manualSelectionEnabled = manualBitrateSelectionEnabled
        )
    }

    fun setCameraSupportedRecordingSizes(sizes: List<Size>) {
        cameraSupportedRecordingSizes = sizes
            .map(::normalizeSize)
            .distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
    }

    fun supports10Bit(codec: GpuVideoCodec = videoCodec): Boolean {
        return codecSupport.supportsTenBit(codec)
    }

    private fun startMuxerWriter() {
        muxerWriterJob?.cancel()
        muxerSampleQueue.reset()
        val prewarmBufferBytes = muxerPrewarmBufferBytes()
        val prewarmBufferCount = muxerPrewarmBufferCount(prewarmBufferBytes)
        muxerSampleQueue.prewarmBuffers(
            count = prewarmBufferCount,
            bufferCapacity = prewarmBufferBytes
        )
        Log.i(
            TAG,
            "Muxer writer prewarmed buffers=$prewarmBufferCount bufferBytes=$prewarmBufferBytes " +
                "targetBytes=${prewarmBufferCount.toLong() * prewarmBufferBytes.toLong()}"
        )
        muxerWriterJob = scope.launch(muxerWriterDispatcher) {
            HotPathScheduling.applyUrgentDisplay()
            while (isRecording.get() || hasPendingMuxerSamples() || !videoDrainComplete.get() || !audioDrainComplete.get()) {
                val packet = muxerSampleQueue.awaitPacket(waitMs = 8L) ?: continue

                try {
                    while (!muxerStarted.get() && (isRecording.get() || videoTrackIndex >= 0 || audioTrackIndex >= 0)) {
                        delay(2L)
                    }
                    if (muxerStarted.get()) {
                        synchronized(muxerLock) {
                            packet.data.position(0)
                            packet.data.limit(packet.size)
                            movSessionController?.writeSample(
                                trackIndex = packet.trackIndex,
                                buffer = packet.data,
                                presentationTimeUs = packet.presentationTimeUs,
                                flags = packet.flags
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Container packet write failed", e)
                } finally {
                    muxerSampleQueue.recycle(packet.data)
                }
            }
        }
    }

    private fun muxerPrewarmBufferCount(bufferCapacityBytes: Int): Int =
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            (STRICT_MUXER_PREWARM_TARGET_BYTES / bufferCapacityBytes.coerceAtLeast(1))
                .coerceIn(STRICT_MUXER_PREWARM_MIN_BUFFERS, STRICT_MUXER_PREWARM_MAX_BUFFERS)
        } else {
            (STANDARD_MUXER_PREWARM_TARGET_BYTES / bufferCapacityBytes.coerceAtLeast(1))
                .coerceIn(STANDARD_MUXER_PREWARM_MIN_BUFFERS, STANDARD_MUXER_PREWARM_MAX_BUFFERS)
        }

    private fun muxerPrewarmBufferBytes(): Int {
        val safeFps = videoFps.coerceAtLeast(1)
        val bitrate = activeOutputBitrate
            .takeIf { it > 0 }
            ?: minimumBitrateFloorFor(videoWidth, videoHeight, fps = safeFps)
        val averageFrameBytes = (bitrate.toLong() / 8L / safeFps.toLong()).coerceAtLeast(1L)
        return (averageFrameBytes * 3L)
            .coerceIn(
                MUXER_PREWARM_MIN_BUFFER_BYTES.toLong(),
                MUXER_PREWARM_MAX_BUFFER_BYTES.toLong()
            )
            .toInt()
    }

    private fun queueMuxerSample(trackIndex: Int, source: ByteBuffer, info: MediaCodec.BufferInfo) {
        val enqueueResult = muxerSampleQueue.enqueue(trackIndex, source, info)
        if (!enqueueResult.enqueued) {
            Log.e(
                TAG,
                "Muxer sample enqueue failed without dropping queued samples " +
                    "(track=$trackIndex size=${info.size} ptsUs=${info.presentationTimeUs})"
            )
        }
        if (enqueueResult.droppedSamples > 0) {
            muxerBackpressureDropCount += enqueueResult.droppedSamples
            if (muxerBackpressureDropCount % 12 == 0 || enqueueResult.droppedSamples >= 2) {
                Log.w(
                    TAG,
                    "Dropping oldest queued muxer sample to avoid encoder backpressure " +
                        "(burst=${enqueueResult.droppedSamples}, total=$muxerBackpressureDropCount)"
                )
            }
        }
        if (enqueueResult.allocatedBuffer) {
            val allocations = muxerSampleQueue.getRuntimeBufferAllocationCount()
            if (allocations <= 4 || allocations % 16 == 0) {
                Log.w(
                    TAG,
                    "Muxer packet buffer allocated after recording start " +
                        "(allocations=$allocations size=${info.size} pending=${pendingMuxerSamples()})"
                )
            }
        }
        if (enqueueResult.strictBackpressureWaits > 0) {
            val waits = muxerSampleQueue.getStrictBackpressureWaitCount()
            if (waits <= 4 || waits % 16 == 0 || enqueueResult.strictBackpressureWaits > 1) {
                Log.w(
                    TAG,
                    "Strict muxer backpressure wait " +
                        "(burstWaits=${enqueueResult.strictBackpressureWaits}, totalWaits=$waits, " +
                        "pending=${pendingMuxerSamples()}, pendingBytes=${pendingMuxerBufferBytes()}, " +
                        "size=${info.size}, track=$trackIndex)"
                )
            }
        }
        if (trackIndex == videoTrackIndex && info.size > 0) {
            maybeApplyAbrBackpressure(
                frameIndex = encodedVideoSampleCount,
                encoderQueueLatencyMs = estimateEncoderQueueLatencyMs(),
                queueOccupancy = maxOf(
                    pendingMuxerSamples().toFloat() / MAX_PENDING_MUXER_SAMPLES.toFloat(),
                    pendingMuxerBufferBytes().toFloat() / MAX_PENDING_MUXER_BUFFER_BYTES.toFloat()
                ).coerceIn(0f, 1f)
            )
        }
    }

    private fun estimateEncoderQueueLatencyMs(): Long {
        val frameIntervalMs = (1_000L / videoFps.coerceAtLeast(1)).coerceAtLeast(1L)
        return maxOf(
            pendingMuxerSamples().toLong() * frameIntervalMs,
            framePacingJitterMs
        )
    }

    private fun maybeApplyAbrBackpressure(
        frameIndex: Long,
        encoderQueueLatencyMs: Long,
        queueOccupancy: Float
    ) {
        val activeTier = runCatching {
            RecordingCodecCatalog.QualityTier.valueOf(activeQualityTierLabel)
        }.getOrDefault(RecordingCodecCatalog.QualityTier.STANDARD)
        val runtimeCeiling = RecordingCodecCatalog.maxRuntimeBitrateBps(videoCodec, activeTier)
        val targetBitrate = abrNominalBitrate
            .coerceAtLeast(activeOutputBitrate)
            .coerceAtMost(runtimeCeiling)
            .coerceAtLeast(1)
        val minimumBitrate = minimumBitrateFloorFor(videoWidth, videoHeight, fps = videoFps)
        val thermalPressure = ((10 - thermalEncoderComplexity).coerceAtLeast(0) / 10f).coerceIn(0f, 1f)
        val eliteRuntimeQualityLocked =
            cinemaMasteringEnabled || allIntraActiveInternal ||
                activeTier == RecordingCodecCatalog.QualityTier.CINEMA ||
                // Extend the no-reduction lock to the ordinary VIDEO/PRO tiers (reversible, default ON):
                // the vivo encoder has ample headroom (~0.66ms submit), so never trade recorded bitrate
                // + shader detail for pacing here — that would be a downgrade. Manual bitrate still wins.
                AbrQualityLockFlag.enabled
        val decision = RecordingAbrPolicy.resolve(
            frameIndex = frameIndex,
            encoderQueueLatencyMs = encoderQueueLatencyMs,
            targetBitrateBps = targetBitrate,
            minimumBitrateBps = minimumBitrate,
            queueOccupancy = queueOccupancy,
            thermalPressure = thermalPressure,
            allowBitrateReduction = !manualBitrateSelectionEnabled && !eliteRuntimeQualityLocked
        )
        abrShaderEntropyScale = decision.shaderEntropyScale
        abrDetailGainScale = decision.detailGainScale
        abrDitherAmplitudeScale = decision.ditherAmplitudeScale
        if (!decision.shouldSetCodecParameters || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return

        val codec = videoCodecObj ?: return
        runCatching {
            codec.setParameters(RecordingAbrPolicy.toCodecParameters(decision))
            activeOutputBitrate = decision.targetBitrateBps
            lastHintedBitrate = decision.targetBitrateBps
        }.onSuccess {
            Log.i(
                TAG,
                "ABR backpressure active latency=${encoderQueueLatencyMs}ms " +
                    "queue=${String.format(Locale.US, "%.2f", queueOccupancy)} " +
                    "bitrate=${decision.targetBitrateBps / 1_000_000}Mbps " +
                    "shaderEntropy=${String.format(Locale.US, "%.2f", abrShaderEntropyScale)} " +
                    "detail=${String.format(Locale.US, "%.2f", abrDetailGainScale)} " +
                    "dither=${String.format(Locale.US, "%.2f", abrDitherAmplitudeScale)}"
            )
        }.onFailure {
            Log.w(TAG, "Failed to apply ABR encoder backpressure parameters", it)
        }
    }

    private fun hasPendingMuxerSamples(): Boolean =
        muxerSampleQueue.hasPending()

    fun activeOutputBitrateBitsPerSecond(): Int = activeOutputBitrate.coerceAtLeast(1)

    @Synchronized
    fun updateActiveEncoderBitrate(requestedBitrate: Int): Boolean {
        if (requestedBitrate <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return false
        }
        val codec = videoCodecObj ?: return false
        requestedOutputBitrateForEvidence = requestedBitrate
        val appliedBitrate = resolveSupportedBitrate(
            requestedBitrate = requestedBitrate,
            size = Size(videoWidth, videoHeight),
            codec = videoCodec,
            videoDetailProfile = videoDetailProfile,
            fps = videoFps
        )
        policyTargetBitrateForEvidence = appliedBitrate
        return try {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, appliedBitrate)
                }
            )
            activeOutputBitrate = appliedBitrate
            abrNominalBitrate = appliedBitrate
            lastHintedBitrate = appliedBitrate
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to update active encoder bitrate to $appliedBitrate", throwable)
            false
        }
    }

    /**
     * Whether the active encode is **actually** all-intra HEVC (every frame is
     * an I-frame, signalled via `KEY_I_FRAME_INTERVAL = 0` and accepted by the
     * hardware encoder). Returns false when the user requested cinema-master
     * but the device could not honour it; UI overlays should distinguish a
     * "requested" from a "delivered" all-intra state via this getter rather
     * than mirror [cinemaMasteringEnabled] blindly.
     */
    fun isAllIntraActive(): Boolean = allIntraActiveInternal

    /**
     * The truthful encoder-emitted bitrate, integrated over the meter's
     * window. Returns 0 while the window is still warming up (no buffers or
     * single buffer dequeued); callers should fall back to
     * [activeOutputBitrateBitsPerSecond] in that case.
     */
    fun measuredOutputBitrateBitsPerSecond(): Int =
        encodedOutputBitrateMeter.measuredBitrateBitsPerSecond()

    fun droppedMuxerSamples(): Int = muxerSampleQueue.getDroppedCount()

    fun pendingMuxerSamples(): Int = muxerSampleQueue.pendingCount()

    fun pendingMuxerBufferBytes(): Long = muxerSampleQueue.pendingBufferByteCount()

    private fun createContainerBackend(selection: RecordingContainerSelection): RecordingContainerBackend =
        createContainerBackend(selection, RecordingPreallocationPolicy.processed(0L))

    private fun createContainerBackend(
        selection: RecordingContainerSelection,
        preallocationPlan: RecordingPreallocationPolicy.Plan
    ): RecordingContainerBackend =
        when (selection.container) {
            RecordingContainer.MOV -> QuickTimeMovBackend(
                bridge = KotlinQuickTimeMovBridge(),
                preallocationPlan = preallocationPlan
            )
            RecordingContainer.MP4 -> error("Legacy MP4 backend is not enabled in this MOV-only build")
        }

    @SuppressLint("UsableSpace")
    private fun resolveOutputAvailableStorageBytes(target: RecordingOutputTarget): Long {
        val candidates = listOfNotNull(
            runCatching { java.io.File(target.writablePath()).usableSpace }.getOrNull(),
            target.file?.parentFile?.let { runCatching { it.usableSpace }.getOrNull() },
            context.getExternalFilesDir(null)?.let { runCatching { it.usableSpace }.getOrNull() },
            runCatching { context.filesDir.usableSpace }.getOrNull()
        )
        return candidates.firstOrNull { it > 0L } ?: 0L
    }

    private fun addVideoTrack(format: MediaFormat): Int {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: RecordingCodecCatalog.mime(videoCodec)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            videoFps
        }
        val codecConfig = extractCodecConfigBytes(format, normalizeToAnnexB = true)
        return movSessionController?.addVideoTrack(
            mime = mime,
            width = width,
            height = height,
            fps = fps,
            codecConfig = codecConfig,
            colorConfig = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(activeColorEncodingConfig)
        ) ?: -1
    }

    private fun addAudioTrack(format: MediaFormat): Int {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: MIME_AAC
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            AUDIO_SAMPLE_RATE
        }
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            AUDIO_CHANNELS
        }
        val codecConfig = extractCodecConfigBytes(format, normalizeToAnnexB = false)
        return movSessionController?.addAudioTrack(
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            codecConfig = codecConfig
        ) ?: -1
    }

    private fun extractCodecConfigBytes(
        format: MediaFormat,
        normalizeToAnnexB: Boolean
    ): ByteArray {
        val configChunks = ArrayList<ByteArray>()
        var index = 0
        while (true) {
            val key = "csd-$index"
            if (!format.containsKey(key)) break
            val buffer = format.getByteBuffer(key)
            val bytes = buffer?.toByteArray() ?: ByteArray(0)
            if (bytes.isNotEmpty()) {
                configChunks += if (normalizeToAnnexB && !hasAnnexBStartCode(bytes)) {
                    byteArrayOf(0, 0, 0, 1) + bytes
                } else {
                    bytes
                }
            }
            index += 1
        }
        val totalSize = configChunks.sumOf { it.size }
        if (totalSize == 0) return ByteArray(0)
        val combined = ByteArray(totalSize)
        var offset = 0
        configChunks.forEach { chunk ->
            chunk.copyInto(combined, destinationOffset = offset)
            offset += chunk.size
        }
        return combined
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun hasAnnexBStartCode(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        val threeByteStartCode =
            bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                bytes[2] == 1.toByte()
        val fourByteStartCode =
            bytes.size >= 4 &&
                bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() &&
                bytes[3] == 1.toByte()
        return threeByteStartCode || fourByteStartCode
    }

    fun supportsAv1(): Boolean {
        return codecSupport.av1Supported
    }

    fun warmupCapabilityCaches(): Boolean {
        val av1Supported = codecSupport.av1Supported
        val codecsToWarm = buildList {
            add(GpuVideoCodec.HEVC)
            add(GpuVideoCodec.AVC)
            if (av1Supported) {
                add(GpuVideoCodec.AV1)
            }
        }
        codecsToWarm.forEach { codec ->
            videoEncoderCandidates(RecordingCodecCatalog.mime(codec))
        }
        return av1Supported
    }

    private fun resolveVideoConfig(
        mime: String,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): ResolvedVideoConfig {
        val sanitizedFps = VideoRecordingContractPolicy.sanitizeRequestedFps(requestedFps)
        val safeRequested = RecordingResolutionPlanner.sanitizeRequestedSize(
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight
        )
        val candidates = videoEncoderCandidates(mime)
        if (candidates.isEmpty()) {
            return ResolvedVideoConfig(
                size = safeRequested,
                fps = sanitizedFps.coerceAtLeast(24),
                encoderExactMatch = false,
                cameraExactMatch = cameraSupportedRecordingSizes.any {
                    it.width == safeRequested.width && it.height == safeRequested.height
                }
            )
        }

        val requestedSupportedByCamera =
            cameraSupportedRecordingSizes.any {
                it.width == safeRequested.width && it.height == safeRequested.height
            }
        val standardCandidates = buildSizeCandidates(safeRequested, cameraSupportedRecordingSizes)
        standardCandidates.forEach { size ->
            candidates.firstOrNull { candidate ->
                val caps = candidate.videoCapabilities ?: return@firstOrNull false
                // Device-truth cap (EncoderSizeDeviceTruthCapFlag, default ON): isSizeSupported only
                // validates spatial bounds and ignores the encoder's level/throughput ceiling, so it
                // accepts unencodable sizes (e.g. 8192×6144 full-sensor) that later fail MediaCodec
                // .configure mid-session-rebuild. Also require the honest areSizeAndRateSupported at the
                // ALREADY-CLAMPED fps (not the raw request), so sizes that merely need fps-clamping are
                // not over-rejected. Flag OFF ⇒ the device-truth lambda is never evaluated ⇒ byte-identical
                // to the legacy spatial-only predicate.
                EncoderSizeAcceptancePolicy.accepts(
                    spatiallySupported = caps.isSizeSupported(size.width, size.height)
                ) {
                    caps.areSizeAndRateSupported(
                        size.width,
                        size.height,
                        clampFrameRate(caps, size, sanitizedFps).toDouble()
                    )
                }
            }?.let { candidate ->
                val exactSize = size.width == safeRequested.width && size.height == safeRequested.height
                return ResolvedVideoConfig(
                    size = size,
                    fps = clampFrameRate(candidate.videoCapabilities ?: return@let, size, sanitizedFps),
                    encoderExactMatch = exactSize,
                    cameraExactMatch = exactSize && requestedSupportedByCamera
                )
            }
        }

        val fallback = candidates
            .mapNotNull { candidate ->
                val videoCaps = candidate.videoCapabilities ?: return@mapNotNull null
                val size = clampSizeToCapabilities(videoCaps, safeRequested)
                val fps = clampFrameRate(videoCaps, size, sanitizedFps)
                Triple(candidate.codecInfo.name, size, fps)
            }
            .maxWithOrNull(
                compareBy<Triple<String, Size, Int>> { it.second.width.toLong() * it.second.height.toLong() }
                    .thenBy {
                        -abs(
                            RecordingResolutionPlanner.aspectRatio(it.second) -
                                RecordingResolutionPlanner.aspectRatio(safeRequested)
                        )
                    }
            )

        return fallback?.let {
            ResolvedVideoConfig(
                size = it.second,
                fps = it.third,
                encoderExactMatch = it.second.width == safeRequested.width && it.second.height == safeRequested.height,
                cameraExactMatch = requestedSupportedByCamera
            )
        } ?: ResolvedVideoConfig(
            size = safeRequested,
            fps = sanitizedFps.coerceAtLeast(24),
            encoderExactMatch = false,
            cameraExactMatch = requestedSupportedByCamera
        )
    }

    private fun videoEncoderCandidates(mime: String): List<EncoderCandidate> {
        val normalizedMime = mime.lowercase(Locale.US)
        synchronized(encoderCandidatesCacheLock) {
            encoderCandidatesCache[normalizedMime]?.let { return it }
        }
        val resolvedCandidates = runCatching {
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.mapNotNull { info ->
                if (!info.isEncoder) {
                    return@mapNotNull null
                }
                val matchedMime = info.supportedTypes.firstOrNull {
                    it.equals(normalizedMime, ignoreCase = true)
                } ?: return@mapNotNull null
                runCatching {
                    val codecCapabilities = info.getCapabilitiesForType(matchedMime)
                    EncoderCandidate(
                        codecInfo = info,
                        mimeType = matchedMime,
                        codecCapabilities = codecCapabilities,
                        videoCapabilities = codecCapabilities?.videoCapabilities,
                        selectionCandidate = RecordingCodecSelectionCandidate(
                            name = info.name,
                            hardwareAccelerated = info.safeHardwareAccelerated(),
                            softwareOnly = info.safeSoftwareOnly(),
                            vendor = info.safeVendor()
                        )
                    )
                }.getOrNull()
            }
            val rankedNames = RecordingCodecSelectionPolicy.rank(
                candidates.map { it.selectionCandidate }
            ).mapIndexed { index, candidate -> candidate.name to index }.toMap()
            candidates.sortedBy { candidate ->
                rankedNames[candidate.codecInfo.name] ?: Int.MAX_VALUE
            }
        }.getOrDefault(emptyList())
        synchronized(encoderCandidatesCacheLock) {
            encoderCandidatesCache[normalizedMime] = resolvedCandidates
        }
        return resolvedCandidates
    }

    private fun selectVideoEncoderCandidate(
        mime: String,
        size: Size,
        fps: Int,
        profile: Int?,
        level: Int?,
        targetBitrate: Int?
    ): SelectedEncoderCandidate? {
        val candidates = videoEncoderCandidates(mime)
        if (candidates.isEmpty()) {
            Log.w(TAG, "No video encoder candidate reported for $mime; using framework default")
            return null
        }
        val rankedPairs = candidates.map { candidate ->
            candidate to candidate.selectionCandidate.copy(
                supportsRequestedFormat = candidate.supportsRequestedFormat(
                    size = size,
                    fps = fps,
                    profile = profile,
                    level = level,
                    targetBitrate = targetBitrate
                )
            )
        }
        val byName = rankedPairs.associateBy { it.second.name }
        val rankedSelection = RecordingCodecSelectionPolicy.rank(rankedPairs.map { it.second })
        val selectedSelection = rankedSelection.firstOrNull { it.supportsRequestedFormat }
            ?: rankedSelection.firstOrNull()
            ?: return null
        val selected = byName[selectedSelection.name]?.first ?: return null
        Log.i(
            TAG,
            "Selected video encoder ${selected.codecInfo.name} for $mime " +
                "${size.width}x${size.height}@${fps}fps profile=$profile " +
                "level=$level " +
                "targetBitrate=${targetBitrate?.div(1_000_000) ?: 0}Mbps " +
                "(${RecordingCodecSelectionPolicy.describe(selectedSelection)}) " +
                "candidates=${candidates.size}"
        )
        return SelectedEncoderCandidate(selected, selectedSelection)
    }

    private fun EncoderCandidate.supportsRequestedFormat(
        size: Size,
        fps: Int,
        profile: Int?,
        level: Int?,
        targetBitrate: Int?
    ): Boolean {
        val profileLevels = codecCapabilities?.profileLevels
        val profileSupported = profile == null ||
            profileLevels.isNullOrEmpty() ||
            profileLevels.any { it.profile == profile }
        if (!profileSupported) return false
        val levelSupported = level == null ||
            profileLevels.isNullOrEmpty() ||
            profileLevels.any { profileLevel ->
                (profile == null || profileLevel.profile == profile) &&
                    profileLevel.level >= level
            }
        if (!levelSupported) return false

        val capabilities = videoCapabilities ?: return true
        return runCatching {
            if (!capabilities.isSizeSupported(size.width, size.height)) {
                false
            } else {
                val frameRates = capabilities.getSupportedFrameRatesFor(size.width, size.height)
                val rateSupported = fps.toDouble() in frameRates.lower..frameRates.upper ||
                    capabilities.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                val bitrateSupported = targetBitrate == null ||
                    RecordingEncoderBitrateCapabilityPolicy.isBitrateSupported(
                        lower = capabilities.bitrateRange.lower,
                        upper = capabilities.bitrateRange.upper,
                        requestedBitrate = targetBitrate
                    )
                rateSupported && bitrateSupported
            }
        }.getOrDefault(false)
    }

    private fun createVideoEncoder(
        mime: String,
        selected: SelectedEncoderCandidate?
    ): MediaCodec {
        val selectedCodecName = selected?.candidate?.codecInfo?.name
        val namedCodecAttempts = RecordingCodecCreationPlanPolicy.orderedCodecNames(
            selectedCodecName = selectedCodecName,
            selectedCreateByName = selected?.selection?.createByName == true,
            rankedCandidateNames = videoEncoderCandidates(mime).map { it.codecInfo.name }
        )
        val creationFailures = mutableListOf<Throwable>()
        for (codecName in namedCodecAttempts) {
            val created = runCatching {
                MediaCodec.createByCodecName(codecName).also {
                    val selectionDescription = selected
                        ?.takeIf { codecName == selectedCodecName }
                        ?.let { " (${RecordingCodecSelectionPolicy.describe(it.selection)})" }
                        .orEmpty()
                    Log.i(TAG, "Created named video encoder $codecName for $mime$selectionDescription")
                }
            }
            created.getOrNull()?.let { return it }
            val failure = created.exceptionOrNull() ?: continue
            creationFailures += failure
            Log.w(TAG, "createByCodecName($codecName) failed; trying next encoder for $mime", failure)
        }
        Log.i(TAG, "Using framework default video encoder for $mime")
        return runCatching {
            MediaCodec.createEncoderByType(mime)
        }.getOrElse { throwable ->
            creationFailures.forEach { throwable.addSuppressed(it) }
            throw throwable
        }
    }

    private fun MediaCodecInfo.safeHardwareAccelerated(): Boolean =
        runCatching { isHardwareAccelerated }.getOrDefault(!safeSoftwareOnly())

    private fun MediaCodecInfo.safeSoftwareOnly(): Boolean =
        runCatching { isSoftwareOnly }.getOrDefault(
            name.startsWith("OMX.google.", ignoreCase = true) ||
                name.startsWith("c2.android.", ignoreCase = true)
        )

    private fun MediaCodecInfo.safeVendor(): Boolean =
        runCatching { isVendor }.getOrDefault(
            name.contains("qcom", ignoreCase = true) ||
                name.contains("qti", ignoreCase = true) ||
                name.contains("exynos", ignoreCase = true) ||
                name.contains("mtk", ignoreCase = true) ||
                name.contains("mediatek", ignoreCase = true) ||
                name.contains("vivo", ignoreCase = true)
        )

    private fun resolveSupportedEncoderLevel(
        profileLevels: Array<MediaCodecInfo.CodecProfileLevel>?,
        profile: Int?,
        targetLevel: Int?
    ): Int? {
        if (targetLevel == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val levels = profileLevels?.takeIf { it.isNotEmpty() } ?: return null
        val supported = levels.any { profileLevel ->
            (profile == null || profileLevel.profile == profile) &&
                profileLevel.level >= targetLevel
        }
        return if (supported) targetLevel else null
    }

    private fun buildSizeCandidates(requested: Size, supportedCameraSizes: List<Size>): List<Size> {
        return RecordingResolutionPlanner.buildSizeCandidates(requested, supportedCameraSizes)
    }

    private fun clampSizeToCapabilities(
        capabilities: MediaCodecInfo.VideoCapabilities,
        requested: Size
    ): Size {
        val normalized = normalizeSize(requested)
        val widthAlignment = capabilities.widthAlignment.coerceAtLeast(1)
        val heightAlignment = capabilities.heightAlignment.coerceAtLeast(1)
        var width = alignDown(
            normalized.width.coerceIn(
                capabilities.supportedWidths.lower,
                capabilities.supportedWidths.upper
            ),
            widthAlignment
        ).coerceAtLeast(widthAlignment)

        var bestSize = Size(width, heightAlignment)
        var bestScore = Long.MIN_VALUE
        val targetAspect = aspectRatio(normalized)

        repeat(4) {
            val heightRange = capabilities.getSupportedHeightsFor(width)
            val targetHeight = (width / targetAspect).roundToInt()
            val height = alignDown(
                targetHeight.coerceIn(heightRange.lower, heightRange.upper),
                heightAlignment
            ).coerceAtLeast(heightAlignment)

            if (capabilities.isSizeSupported(width, height)) {
                val areaScore = width.toLong() * height.toLong()
                val aspectPenalty = (abs((width.toFloat() / height.toFloat()) - targetAspect) * 1_000_000f).toLong()
                val score = areaScore - aspectPenalty
                if (score > bestScore) {
                    bestScore = score
                    bestSize = Size(width, height)
                }
            }

            width = alignDown((width - widthAlignment).coerceAtLeast(widthAlignment), widthAlignment)
        }

        return bestSize
    }

    private fun clampFrameRate(
        capabilities: MediaCodecInfo.VideoCapabilities,
        size: Size,
        requestedFps: Int
    ): Int {
        return runCatching {
            val range = capabilities.getSupportedFrameRatesFor(size.width, size.height)
            val clamped = requestedFps.toDouble()
                .coerceIn(range.lower, range.upper)
                .roundToInt()
                .coerceAtLeast(24)
            RecordingPreviewFrameRatePolicy.snapToSupportedCinemaFps(
                requestedFps = clamped,
                minFps = range.lower.toInt(),
                maxFps = range.upper.toInt()
            )
        }.getOrDefault(requestedFps.coerceAtLeast(24))
    }

    private fun clampBitrateToEncoderCapabilities(
        mime: String,
        size: Size,
        requestedBitrate: Int
    ): Int {
        return resolveSupportedBitrate(
            requestedBitrate = requestedBitrate,
            size = size,
            codec = when (mime.uppercase(Locale.US)) {
                "VIDEO/AVC", "AVC", "H264" -> GpuVideoCodec.AVC
                "VIDEO/AV01", "VIDEO/AV1", "AV1", "AV01" -> GpuVideoCodec.AV1
                else -> GpuVideoCodec.HEVC
            },
            videoDetailProfile = videoDetailProfile
        )
    }

    private fun clampBitrateToSelectedEncoderCapabilities(
        selectedEncoder: SelectedEncoderCandidate?,
        requestedBitrate: Int
    ): Int {
        val bitrateRange = selectedEncoder
            ?.candidate
            ?.videoCapabilities
            ?.bitrateRange
            ?: return requestedBitrate
        val lower = bitrateRange.lower.coerceAtLeast(1)
        val upper = bitrateRange.upper.coerceAtLeast(lower)
        return requestedBitrate.coerceIn(lower, upper)
    }

    private fun encoderCapabilityUpperBound(mime: String, size: Size): Int? {
        return RecordingEncoderBitrateCapabilityPolicy.resolveUpperBound(
            encoderBitrateCapabilities(mime, size)
        )
    }

    private fun encoderCapabilityLowerBound(mime: String, size: Size): Int? {
        return RecordingEncoderBitrateCapabilityPolicy.resolveLowerBound(
            encoderBitrateCapabilities(mime, size)
        )
    }

    private fun encoderBitrateCapabilities(
        mime: String,
        size: Size
    ): List<RecordingEncoderBitrateCapability> {
        return videoEncoderCandidates(mime)
            .mapNotNull { candidate ->
                runCatching {
                    val caps = candidate.videoCapabilities ?: return@mapNotNull null
                    if (caps.isSizeSupported(size.width, size.height)) {
                        RecordingEncoderBitrateCapability(
                            lower = caps.bitrateRange.lower,
                            upper = caps.bitrateRange.upper,
                            hardwareAccelerated = candidate.selectionCandidate.hardwareAccelerated,
                            softwareOnly = candidate.selectionCandidate.softwareOnly
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            }
    }

    private fun alignDown(value: Int, alignment: Int): Int =
        RecordingResolutionPlanner.alignDown(value, alignment)

    private fun aspectRatio(size: Size): Float =
        RecordingResolutionPlanner.aspectRatio(size)

    private fun normalizeSize(size: Size): Size =
        RecordingResolutionPlanner.normalizeSize(size)

    fun resolutionLabel() = RecordingResolutionPlanner.resolutionLabel(videoWidth, videoHeight)

    private fun resolveEncoderComplexity(
        codecInfo: MediaCodecInfo?,
        mime: String,
        requestedComplexity: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        if (codecInfo == null) return null
        return runCatching {
            val range = codecInfo
                .getCapabilitiesForType(mime)
                ?.encoderCapabilities
                ?.complexityRange
            requestedComplexity.coerceIn((range?.lower ?: 0), (range?.upper ?: 0))
        }.getOrNull()
    }

    private fun resolveBitrateMode(
        codecInfo: MediaCodecInfo?,
        mime: String,
        preferredModes: List<Int>
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        if (codecInfo == null) return null
        return runCatching {
            val encoderCapabilities = codecInfo
                .getCapabilitiesForType(mime)
                ?.encoderCapabilities
            (preferredModes + listOf(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            ))
                .distinct()
                .firstOrNull { mode -> encoderCapabilities?.isBitrateModeSupported(mode) ?: false }
        }.getOrNull()
    }

    private fun stopAudioCapture(
        audioRecordOverride: AudioRecord? = audioRecord,
        logFailure: Boolean
    ) {
        val audio = audioRecordOverride ?: return
        val alreadyRequested = audioCaptureStopRequested.getAndSet(true)
        if (!RecordingAudioStopPolicy.shouldStopAudioCapture(audio.recordingState, alreadyRequested)) {
            return
        }
        try {
            audio.stop()
        } catch (e: Exception) {
            if (logFailure) {
                Log.w(TAG, "audioRecord.stop() failed", e)
            }
        }
    }

}

// Public types

enum class GpuVideoCodec { HEVC, AVC, AV1 }

enum class RecordingVideoInputMode {
    SURFACE,
    BYTEBUFFER_P010
}

sealed class GpuRecordingState {
    object Idle : GpuRecordingState()
    object Ready : GpuRecordingState()
    object Starting : GpuRecordingState()
    object Recording : GpuRecordingState()
    object Paused : GpuRecordingState()
    object Stopping : GpuRecordingState()
    object Finalizing : GpuRecordingState()
    data class Error(val message: String) : GpuRecordingState()
}

fun GpuRecordingState.isRecordingSessionActive(): Boolean =
    this is GpuRecordingState.Starting ||
        this is GpuRecordingState.Recording ||
        this is GpuRecordingState.Paused ||
        this is GpuRecordingState.Stopping

fun GpuRecordingState.isWritingRecording(): Boolean =
    this is GpuRecordingState.Recording
