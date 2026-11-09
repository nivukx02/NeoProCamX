package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger

/**
 * Serializes only MediaCodec create/configure/start transitions and enforces a short release-to-open
 * quiescence window. Qualcomm/Vivo codec services can return from release before the vendor session is
 * reusable; immediately opening the next Main10 decoder then blocks inside configure/start forever.
 *
 * Active encoders and decoders remain concurrent after admission. Pixel data, geometry, frame cadence,
 * profile, bitrate, colour metadata, and output transport are untouched.
 */
internal object RecordDevelopCodecLifecycleCoordinator {
    private const val TAG = "RecordDevelopCodecLife"
    private val creationMonitor = Any()

    @Volatile
    private var lastReleaseElapsedMs: Long? = null

    fun <T> createAfterReleaseQuiescence(
        role: String,
        codecName: String,
        createAndStart: () -> T
    ): T = synchronized(creationMonitor) {
        val admissionStartedMs = SystemClock.elapsedRealtime()
        val waitMs = RecordDevelopCodecLifecyclePolicy.requiredWaitMs(
            lastReleaseElapsedMs = lastReleaseElapsedMs,
            nowElapsedMs = admissionStartedMs
        )
        if (waitMs > 0L) {
            NeoLogger.i(
                TAG,
                "MEDIA_CODEC_REOPEN_QUIESCENCE role=$role codec=$codecName waitMs=$waitMs"
            )
            SystemClock.sleep(waitMs)
        }
        val created = createAndStart()
        NeoLogger.i(
            TAG,
            "MEDIA_CODEC_CREATE_ADMITTED role=$role codec=$codecName " +
                "waitMs=$waitMs elapsedMs=${SystemClock.elapsedRealtime() - admissionStartedMs}"
        )
        created
    }

    fun markReleased(role: String, codecName: String) {
        synchronized(creationMonitor) {
            lastReleaseElapsedMs = SystemClock.elapsedRealtime()
        }
        NeoLogger.i(TAG, "MEDIA_CODEC_RELEASED role=$role codec=$codecName")
    }
}

/** Pure timing policy kept separate so the anti-race contract is host-testable. */
internal object RecordDevelopCodecLifecyclePolicy {
    /**
     * Device evidence on Vivo/Qualcomm showed permanent decoder configure/start stalls at 104 ms and
     * 750 ms reopen intervals, while the naturally settled transition above 1.5 seconds succeeded.
     * Two seconds is a conservative one-time session handoff boundary, not a per-frame cost.
     */
    const val MINIMUM_RELEASE_QUIESCENCE_MS = 2_000L

    fun requiredWaitMs(
        lastReleaseElapsedMs: Long?,
        nowElapsedMs: Long,
        minimumQuiescenceMs: Long = MINIMUM_RELEASE_QUIESCENCE_MS
    ): Long {
        require(nowElapsedMs >= 0L) { "Elapsed realtime must be non-negative" }
        require(minimumQuiescenceMs >= 0L) { "Codec quiescence must be non-negative" }
        val releaseMs = lastReleaseElapsedMs ?: return 0L
        val elapsedSinceRelease = (nowElapsedMs - releaseMs).coerceAtLeast(0L)
        return (minimumQuiescenceMs - elapsedSinceRelease).coerceAtLeast(0L)
    }
}

internal data class RecordDevelopP010DecoderLease(
    val codec: MediaCodec,
    val codecName: String,
    val reused: Boolean
)

/**
 * Retains one stopped/reset Main10 decoder for the next sequential conversion. Vivo's Codec2 bridge can
 * permanently block a second `MediaCodec.createByCodecName()` in the same process even after release has
 * completed. `reset()` explicitly returns the existing codec to the uninitialized state, allowing the
 * documented configure/start lifecycle to be reused without retaining any source, output, or pixel data.
 */
internal object RecordDevelopP010DecoderPool {
    private const val TAG = "RecordDevelopP010Pool"
    private val monitor = Any()
    private var cachedCodec: MediaCodec? = null
    private var cachedCodecName: String? = null

    fun acquireStarted(codecName: String, format: MediaFormat): RecordDevelopP010DecoderLease =
        synchronized(monitor) {
            val reusable = if (cachedCodecName == codecName) cachedCodec else null
            if (reusable != null) {
                cachedCodec = null
                cachedCodecName = null
            } else if (cachedCodec != null) {
                releasePermanently(
                    codec = requireNotNull(cachedCodec),
                    codecName = cachedCodecName ?: "unknown-decoder",
                    role = "decoder-pool-codec-mismatch"
                )
                cachedCodec = null
                cachedCodecName = null
            }

            val reused = reusable != null
            val codec = reusable ?: RecordDevelopCodecLifecycleCoordinator
                .createAfterReleaseQuiescence(
                    role = "hevc-main10-p010-decoder-create",
                    codecName = codecName
                ) {
                    MediaCodec.createByCodecName(codecName)
                }
            try {
                NeoLogger.i(
                    TAG,
                    "MEDIA_CODEC_DECODER_PREPARE codec=$codecName reused=$reused"
                )
                codec.configure(format, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
                NeoLogger.i(
                    TAG,
                    "MEDIA_CODEC_DECODER_CONFIGURED codec=$codecName reused=$reused"
                )
                codec.start()
                NeoLogger.i(
                    TAG,
                    "MEDIA_CODEC_DECODER_STARTED codec=$codecName reused=$reused"
                )
                RecordDevelopP010DecoderLease(codec, codecName, reused)
            } catch (failure: Throwable) {
                releasePermanently(codec, codecName, "decoder-pool-start-failure")
                throw failure
            }
        }

    fun recycle(lease: RecordDevelopP010DecoderLease, healthy: Boolean) {
        synchronized(monitor) {
            val stopped = runCatching { lease.codec.stop() }.isSuccess
            val reset = if (healthy && stopped) {
                runCatching { lease.codec.reset() }.isSuccess
            } else {
                false
            }
            if (reset && cachedCodec == null) {
                cachedCodec = lease.codec
                cachedCodecName = lease.codecName
                NeoLogger.i(
                    TAG,
                    "MEDIA_CODEC_DECODER_RECYCLED codec=${lease.codecName} reused=${lease.reused}"
                )
            } else {
                releasePermanently(
                    codec = lease.codec,
                    codecName = lease.codecName,
                    role = if (healthy) "decoder-pool-overflow" else "decoder-pool-unhealthy"
                )
            }
        }
    }

    fun discard(codec: MediaCodec, codecName: String, role: String) {
        synchronized(monitor) {
            releasePermanently(codec, codecName, role)
        }
    }

    private fun releasePermanently(codec: MediaCodec, codecName: String, role: String) {
        runCatching { codec.release() }
        RecordDevelopCodecLifecycleCoordinator.markReleased(role, codecName)
    }
}

internal data class RecordDevelopHevcEncoderLease(
    val codec: MediaCodec,
    val codecName: String,
    val reused: Boolean
)

/** Encoder counterpart to [RecordDevelopP010DecoderPool], preserving the configured Main10 contract. */
internal object RecordDevelopHevcEncoderPool {
    private const val TAG = "RecordDevelopHevcPool"
    private val monitor = Any()
    private var cachedCodec: MediaCodec? = null
    private var cachedCodecName: String? = null

    fun acquireStarted(codecName: String, format: MediaFormat): RecordDevelopHevcEncoderLease =
        synchronized(monitor) {
            val reusable = if (cachedCodecName == codecName) cachedCodec else null
            if (reusable != null) {
                cachedCodec = null
                cachedCodecName = null
            } else if (cachedCodec != null) {
                releasePermanently(
                    codec = requireNotNull(cachedCodec),
                    codecName = cachedCodecName ?: "unknown-encoder",
                    role = "encoder-pool-codec-mismatch"
                )
                cachedCodec = null
                cachedCodecName = null
            }

            val reused = reusable != null
            val codec = reusable ?: RecordDevelopCodecLifecycleCoordinator
                .createAfterReleaseQuiescence(
                    role = "hevc-main10-encoder-create",
                    codecName = codecName
                ) {
                    MediaCodec.createByCodecName(codecName)
                }
            try {
                NeoLogger.i(TAG, "MEDIA_CODEC_ENCODER_PREPARE codec=$codecName reused=$reused")
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                NeoLogger.i(TAG, "MEDIA_CODEC_ENCODER_CONFIGURED codec=$codecName reused=$reused")
                codec.start()
                NeoLogger.i(TAG, "MEDIA_CODEC_ENCODER_STARTED codec=$codecName reused=$reused")
                RecordDevelopHevcEncoderLease(codec, codecName, reused)
            } catch (failure: Throwable) {
                releasePermanently(codec, codecName, "encoder-pool-start-failure")
                throw failure
            }
        }

    fun recycle(lease: RecordDevelopHevcEncoderLease) {
        synchronized(monitor) {
            val stopped = runCatching { lease.codec.stop() }.isSuccess
            val reset = if (stopped) runCatching { lease.codec.reset() }.isSuccess else false
            if (reset && cachedCodec == null) {
                cachedCodec = lease.codec
                cachedCodecName = lease.codecName
                NeoLogger.i(
                    TAG,
                    "MEDIA_CODEC_ENCODER_RECYCLED codec=${lease.codecName} reused=${lease.reused}"
                )
            } else {
                releasePermanently(
                    codec = lease.codec,
                    codecName = lease.codecName,
                    role = "encoder-pool-reset-or-capacity-failure"
                )
            }
        }
    }

    private fun releasePermanently(codec: MediaCodec, codecName: String, role: String) {
        runCatching { codec.release() }
        RecordDevelopCodecLifecycleCoordinator.markReleased(role, codecName)
    }
}
