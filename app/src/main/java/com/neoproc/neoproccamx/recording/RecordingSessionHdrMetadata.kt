package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable colour/HDR identity owned by one configured encoder session.
 *
 * Identity is intentionally significant: two otherwise equal configurations still belong to different
 * encoder generations. This lets asynchronous codec callbacks prove that they are publishing a track for
 * the session that produced the output format, rather than a replacement session configured later.
 */
internal data class RecordingSessionColorSnapshot(
    val colorEncoding: RecordingColorEncodingConfig,
    val quickTimeColorConfig: QuickTimeColorAtomConfig
)

/**
 * Owns the static HDR metadata for exactly one live encoder session.
 *
 * MediaCodec and the MOV track must consume the same immutable [HdrStaticMetadataPolicy.MasteringDisplay]
 * value. Keeping this state separate from the mutable UI mode prevents a late settings change from
 * relabeling already encoded PQ pixels when the codec publishes its output format asynchronously.
 */
internal class RecordingSessionHdrMetadata {
    private val mutationLock = Any()
    private val activeSnapshot = AtomicReference<RecordingSessionColorSnapshot?>(null)

    /**
     * Atomically publishes the final successful MediaCodec session's colour contract.
     *
     * The candidate is fully validated and converted before publication. A malformed PQ session clears any
     * previous owner and throws, so callers cannot accidentally reuse stale mastering metadata or substitute
     * the generic conversion/export HDR preset.
     */
    fun retain(
        formatResult: RecordingEncoderFormatResult,
        colorEncoding: RecordingColorEncodingConfig
    ): RecordingSessionColorSnapshot = synchronized(mutationLock) {
        try {
            val immutableColorEncoding = colorEncoding.copy()
            validateEncoderSession(formatResult, immutableColorEncoding)
            RecordingSessionColorSnapshot(
                colorEncoding = immutableColorEncoding,
                quickTimeColorConfig = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
                    colorEncoding = immutableColorEncoding,
                    sessionMasteringDisplay = formatResult.masteringDisplay
                )
            ).also(activeSnapshot::set)
        } catch (failure: Throwable) {
            // Never leave an earlier session eligible after a new session failed validation.
            activeSnapshot.set(null)
            throw failure
        }
    }

    fun requireSnapshotForTrack(): RecordingSessionColorSnapshot =
        checkNotNull(activeSnapshot.get()) {
            "MOV track publication requires an active encoder-session colour snapshot"
        }

    fun currentSnapshotOrNull(): RecordingSessionColorSnapshot? = activeSnapshot.get()

    fun isCurrent(snapshot: RecordingSessionColorSnapshot): Boolean =
        activeSnapshot.get() === snapshot

    /**
     * Retires only the exact snapshot captured by the teardown that owns it. An older asynchronous teardown
     * therefore cannot clear a replacement session that has already been published.
     */
    fun clear(expectedSnapshot: RecordingSessionColorSnapshot?) {
        if (expectedSnapshot == null) return
        synchronized(mutationLock) {
            activeSnapshot.compareAndSet(expectedSnapshot, null)
        }
    }

    private fun validateEncoderSession(
        formatResult: RecordingEncoderFormatResult,
        colorEncoding: RecordingColorEncodingConfig
    ) {
        val pqTransport = colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
        if (!pqTransport) return

        require(colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020) {
            "Live PQ MOV track requires BT.2020 primaries"
        }
        require(colorEncoding.requiresTenBit && colorEncoding.main10Requested) {
            "Live BT.2020 PQ MOV track requires a 10-bit encoder session"
        }
        require(formatResult.staticMetadataAttached) {
            "Live BT.2020 PQ MOV track requires static metadata attached to MediaCodec"
        }
        require(
            formatResult.staticMetadataMode == HdrStaticMetadataPolicy.StaticMetadataMode.HDR10 ||
                formatResult.staticMetadataMode == HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS ||
                formatResult.staticMetadataMode ==
                HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK
        ) {
            "Live BT.2020 PQ MOV track has an incompatible encoder static-metadata mode"
        }
    }
}
