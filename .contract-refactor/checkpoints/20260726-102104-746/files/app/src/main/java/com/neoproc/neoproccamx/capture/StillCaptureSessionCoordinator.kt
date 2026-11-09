package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.concurrent.atomic.AtomicBoolean

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
    private val pendingJpegFrames = mutableListOf<ByteArray>()

    private var activeCapture: ActiveStillCapture? = null
    private var primaryProcessing = false
    private var primarySaved = false
    private var rawProcessing = false
    private var rawCompleted = false

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun begin(capture: ActiveStillCapture): Boolean {
        if (!busy.compareAndSet(false, true)) return false

        val normalizedCapture = normalizeCaptureForStrictMode(capture)
        synchronized(lock) {
            pendingJpegFrames.clear()
            activeCapture = normalizedCapture
            primaryProcessing = false
            primarySaved = false
            rawProcessing = false
            rawCompleted = !normalizedCapture.rawRequested
        }
        return true
    }

    fun activeCaptureId(): Int? = synchronized(lock) { activeCapture?.id }

    fun activeCaptureMode(): StillCaptureMode? = synchronized(lock) { activeCapture?.mode }

    fun routeJpegFrame(bytes: ByteArray): ReadyStillCaptureBatch? =
        synchronized(lock) {
            val current = activeCapture ?: return null
            if (current.expectedJpegs <= 0 || current.mode == StillCaptureMode.HDR_RAW_COMPANION) {
                return null
            }
            if (primaryProcessing) return null

            pendingJpegFrames += bytes
            val progress = pendingJpegFrames.size to current.expectedJpegs
            if (pendingJpegFrames.size < current.expectedJpegs) {
                return null
            }

            primaryProcessing = true
            val framesToProcess = pendingJpegFrames.toList()
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

    fun beginRawProcessing(): ActiveStillCapture? =
        synchronized(lock) {
            val current = activeCapture
            if (current == null ||
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
}
