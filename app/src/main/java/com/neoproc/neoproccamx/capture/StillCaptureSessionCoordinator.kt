package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.camera.StillCaptureRequestPhase
import com.neoproc.neoproccamx.camera.StillCaptureRequestToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class StillCaptureMode {
    SINGLE,
    HDR,
    HDR_RAW_COMPANION;

    fun timeoutLabel(): String =
        when (this) {
            SINGLE -> "Photo"
            HDR -> "HDR"
            HDR_RAW_COMPANION -> "RAW companion"
        }
}

internal data class ActiveStillCapture(
    val id: Int,
    var mode: StillCaptureMode,
    val primaryTag: String,
    val rawCompanionTag: String?,
    val debugLabel: String?,
    val failureLabel: String,
    val hdrMode: String,
    val expectedJpegs: Int,
    val hdrBracketEvs: List<Int> = listOf(0),
    val rawRequested: Boolean,
    val singleCaptureRawMode: StillCaptureCapturePolicy.SingleCaptureRawMode,
    val processSingleBitmap: Boolean,
    val rawCompanionAfterPrimary: Boolean
)

internal data class ReadyStillCaptureBatch(
    val capture: ActiveStillCapture,
    val frames: List<ByteArray>,
    val progress: Pair<Int, Int>?
)

internal enum class PrimaryStillCaptureTransition {
    NONE,
    CLEARED,
    LAUNCH_RAW_COMPANION
}

internal class StillCaptureSessionCoordinator(
    private val busy: AtomicBoolean = AtomicBoolean(false)
) {
    companion object {
        private const val STRICT_MIN_HDR_FRAMES = 3
        private const val STRICT_MAX_HDR_FRAMES = 9
    }

    private val lock = Any()
    // Physical Image ownership outlives logical capture failure/timeout. Never clear this counter from
    // clearLocked(): recording/session reconfiguration remains blocked until every acquired Image closes.
    private val acquiredCameraBufferCount = AtomicInteger(0)
    private val pendingJpegFrames = mutableMapOf<Int, ByteArray>()
    private val releasedJpegFrameIndices = mutableSetOf<Int>()

    private var activeCapture: ActiveStillCapture? = null
    private var releasedJpegBufferCount = 0
    private var primaryProcessing = false
    private var primarySaved = false
    private var rawProcessing = false
    private var rawCompleted = false

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun begin(capture: ActiveStillCapture): Boolean = synchronized(lock) {
        if (acquiredCameraBufferCount.get() > 0) return@synchronized false
        if (!busy.compareAndSet(false, true)) return@synchronized false
        val normalizedCapture = normalizeCaptureForStrictMode(capture)
        pendingJpegFrames.clear()
        releasedJpegFrameIndices.clear()
        activeCapture = normalizedCapture
        releasedJpegBufferCount = 0
        primaryProcessing = false
        primarySaved = false
        rawProcessing = false
        // A deferred HDR RAW companion is still part of this capture's Camera2 ownership.
        // Keep recording admission blocked while the JPEG is processed and until that later RAW
        // buffer has been acquired, consumed, and released by finishRawProcessing().
        rawCompleted = !normalizedCapture.rawRequested &&
            !normalizedCapture.rawCompanionAfterPrimary
        true
    }

    fun activeCaptureId(): Int? = synchronized(lock) { activeCapture?.id }

    fun activeCaptureMode(): StillCaptureMode? = synchronized(lock) { activeCapture?.mode }

    /**
     * True only while this capture still owns, or is waiting for, a Camera2 output buffer.
     *
     * This is intentionally narrower than [activeCaptureId]. The active capture remains retained
     * while JPEG/HDR processing and MediaStore writes finish so a second still cannot reuse its
     * coordinator state. Recording may proceed once every expected JPEG buffer has been copied and
     * closed and reported through [markJpegBufferReleased], and every requested RAW buffer has been consumed,
     * closed, and reported through [finishRawProcessing].
     */
    fun blocksRecordingAdmission(): Boolean = synchronized(lock) {
        if (acquiredCameraBufferCount.get() > 0) return@synchronized true
        val current = activeCapture ?: return@synchronized false
        val jpegBuffersReleased = releasedJpegFrameIndices.size >= current.expectedJpegs
        !jpegBuffersReleased || !rawCompleted
    }

    fun hasAcquiredCameraBuffers(): Boolean = acquiredCameraBufferCount.get() > 0

    fun expectsRawFrame(): Boolean = synchronized(lock) {
        val current = activeCapture ?: return@synchronized false
        !rawCompleted &&
            (current.rawRequested || current.mode == StillCaptureMode.HDR_RAW_COMPANION)
    }

    fun markCameraBufferAcquired() = synchronized(lock) {
        acquiredCameraBufferCount.incrementAndGet()
    }

    fun markCameraBufferReleased(): Boolean = synchronized(lock) {
        val current = acquiredCameraBufferCount.get()
        if (current <= 0) return@synchronized false
        acquiredCameraBufferCount.set(current - 1)
        true
    }

    /**
     * Records release of one Camera2 JPEG buffer independently from the slower processing path.
     *
     * The capture id is mandatory: a timed-out capture can finish copying after a newer capture has
     * acquired the coordinator, and that stale release must never unblock the newer request.
     */
    fun markJpegBufferReleased(token: StillCaptureRequestToken): Boolean = synchronized(lock) {
        val current = activeCapture ?: return false
        if (!tokenMatchesJpegCapture(current, token) ||
            current.expectedJpegs <= 0 ||
            current.mode == StillCaptureMode.HDR_RAW_COMPANION ||
            releasedJpegFrameIndices.size >= current.expectedJpegs
        ) {
            return false
        }
        if (!releasedJpegFrameIndices.add(token.frameIndex)) return false
        releasedJpegBufferCount += 1
        true
    }

    fun routeJpegFrame(token: StillCaptureRequestToken, bytes: ByteArray): ReadyStillCaptureBatch? =
        synchronized(lock) {
            val current = activeCapture ?: return null
            if (!tokenMatchesJpegCapture(current, token) ||
                bytes.isEmpty() ||
                current.expectedJpegs <= 0 ||
                current.mode == StillCaptureMode.HDR_RAW_COMPANION
            ) {
                return null
            }
            if (primaryProcessing) return null
            // Ingress is valid only after the associated Image has been closed and its release was
            // accounted. This keeps recording admission tied to real Camera2 buffer ownership.
            if (token.frameIndex !in releasedJpegFrameIndices ||
                token.frameIndex in pendingJpegFrames
            ) {
                return null
            }

            pendingJpegFrames[token.frameIndex] = bytes
            val progress = pendingJpegFrames.size to current.expectedJpegs
            if (pendingJpegFrames.size < current.expectedJpegs) {
                return null
            }

            primaryProcessing = true
            val framesToProcess = (0 until current.expectedJpegs).map { index ->
                pendingJpegFrames[index] ?: return null
            }
            pendingJpegFrames.clear()
            ReadyStillCaptureBatch(current, framesToProcess, progress)
        }

    fun markPrimarySaved(captureId: Int): PrimaryStillCaptureTransition =
        synchronized(lock) {
            val current = activeCapture ?: return PrimaryStillCaptureTransition.NONE
            if (current.id != captureId) return PrimaryStillCaptureTransition.NONE

            primarySaved = true
            primaryProcessing = false

            when {
                current.rawCompanionAfterPrimary -> {
                    current.mode = StillCaptureMode.HDR_RAW_COMPANION
                    rawCompleted = false
                    PrimaryStillCaptureTransition.LAUNCH_RAW_COMPANION
                }

                rawCompleted -> {
                    clearLocked()
                    PrimaryStillCaptureTransition.CLEARED
                }

                else -> PrimaryStillCaptureTransition.NONE
            }
        }

    fun beginRawProcessing(token: StillCaptureRequestToken): ActiveStillCapture? =
        synchronized(lock) {
            val current = activeCapture
            if (current == null ||
                current.id != token.captureId ||
                !token.expectsRaw ||
                !tokenMatchesRawCapture(current, token) ||
                rawProcessing ||
                rawCompleted ||
                (!current.rawRequested && current.mode != StillCaptureMode.HDR_RAW_COMPANION)
            ) {
                null
            } else {
                rawProcessing = true
                current
            }
        }

    fun finishRawProcessing(captureId: Int): Boolean =
        synchronized(lock) {
            val current = activeCapture ?: return false
            if (current.id != captureId) return false

            rawProcessing = false
            rawCompleted = true
            if (primarySaved || current.mode == StillCaptureMode.HDR_RAW_COMPANION) {
                clearLocked()
                true
            } else {
                false
            }
        }

    fun clearIfActive(captureId: Int): Boolean =
        synchronized(lock) {
            if (activeCapture?.id != captureId) return false
            clearLocked()
            true
        }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    private fun clearLocked() {
        activeCapture = null
        pendingJpegFrames.clear()
        releasedJpegFrameIndices.clear()
        releasedJpegBufferCount = 0
        primaryProcessing = false
        primarySaved = false
        rawProcessing = false
        rawCompleted = false
        busy.set(false)
    }

    private fun normalizeCaptureForStrictMode(capture: ActiveStillCapture): ActiveStillCapture {
        if (!strictHighEndEnabled()) return capture
        val normalizedExpectedJpegs = when (capture.mode) {
            StillCaptureMode.SINGLE -> capture.expectedJpegs.coerceAtLeast(1)
            StillCaptureMode.HDR, StillCaptureMode.HDR_RAW_COMPANION ->
                capture.expectedJpegs.coerceIn(STRICT_MIN_HDR_FRAMES, STRICT_MAX_HDR_FRAMES)
        }
        val normalizedBracket = capture.hdrBracketEvs.ifEmpty { listOf(0) }
            .take(normalizedExpectedJpegs)
        if (normalizedExpectedJpegs == capture.expectedJpegs &&
            normalizedBracket == capture.hdrBracketEvs
        ) {
            return capture
        }
        return capture.copy(
            expectedJpegs = normalizedExpectedJpegs,
            hdrBracketEvs = normalizedBracket
        )
    }

    private fun tokenMatchesJpegCapture(
        capture: ActiveStillCapture,
        token: StillCaptureRequestToken
    ): Boolean {
        if (capture.id != token.captureId || !token.expectsJpeg ||
            token.frameCount != capture.expectedJpegs ||
            token.frameIndex !in 0 until capture.expectedJpegs
        ) {
            return false
        }
        return when (capture.mode) {
            StillCaptureMode.SINGLE -> token.phase == StillCaptureRequestPhase.SINGLE_PRIMARY
            StillCaptureMode.HDR -> token.phase == StillCaptureRequestPhase.HDR_BRACKET
            StillCaptureMode.HDR_RAW_COMPANION -> false
        }
    }

    private fun tokenMatchesRawCapture(
        capture: ActiveStillCapture,
        token: StillCaptureRequestToken
    ): Boolean = when (capture.mode) {
        StillCaptureMode.SINGLE -> token.phase == StillCaptureRequestPhase.SINGLE_PRIMARY
        StillCaptureMode.HDR_RAW_COMPANION ->
            token.phase == StillCaptureRequestPhase.HDR_RAW_COMPANION
        StillCaptureMode.HDR -> false
    }
}
