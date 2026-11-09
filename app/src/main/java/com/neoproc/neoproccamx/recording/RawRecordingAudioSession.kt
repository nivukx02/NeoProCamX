package com.neoproc.neoproccamx.recording

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The RAW master owns this independent AAC lane for its entire transaction. Sensor packets are
 * unchanged; audio never runs on the RAW packing or storage workers. The manager publishes neither
 * a required-audio master nor its companion until this lane has passed its durable timeline audit.
 * Manager lifecycle serialization owns all calls except the nonblocking fatal-failure callback.
 */
internal class RawRecordingAudioSession {
    private val recorder = Npv10AudioSidecarRecorder()
    private val failure = AtomicReference<Throwable?>(null)
    private var source = AudioSrc.NONE
    private var master: File? = null
    private var fps = 0
    private var firstSensorTimestampNs = 0L
    private var paused = false
    private var finalized: Npv10AudioSidecarAudit? = null

    val required: Boolean get() = source != AudioSrc.NONE
    val sidecar: File? get() = master?.let(Npv10AudioSidecarPolicy::sidecarFor)

    fun prepare(audioSource: AudioSrc) {
        check(!recorder.isActive) { "Previous RAW audio worker is still active" }
        recorder.abort() // Also retires a prepared microphone if startup never reached frame one.
        discardStaging()
        source = audioSource.normalizeForStrictHighEnd()
        master = null
        fps = 0
        firstSensorTimestampNs = 0L
        paused = false
        finalized = null
        failure.set(null)
        if (required) recorder.prepare(source)
    }

    fun bind(stagingMaster: File, selectedFps: Int) {
        require(selectedFps > 0)
        check(master == null) { "RAW audio is already bound to a master" }
        fps = selectedFps
        if (!required) return
        val directory = requireNotNull(stagingMaster.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create RAW audio staging" }
        check(!Npv10AudioSidecarPolicy.sidecarFor(stagingMaster).exists() &&
            !Npv10AudioSidecarPolicy.temporarySidecarFor(stagingMaster).exists()) {
            "RAW audio staging identity must be unique"
        }
        master = stagingMaster
    }

    fun start(sensorTimestampNs: Long, onFatalFailure: (Throwable) -> Unit) {
        if (!required) return
        require(sensorTimestampNs > 0L) { "RAW audio requires its first persisted sensor timestamp" }
        check(firstSensorTimestampNs == 0L) { "RAW audio timeline is already active" }
        failure.get()?.let { throw IllegalStateException("RAW audio already failed", it) }
        recorder.start(requireNotNull(master), sensorTimestampNs, source,
            preservePausedWallTime = true) { cause ->
            if (failure.compareAndSet(null, cause)) onFatalFailure(cause)
        }
        firstSensorTimestampNs = sensorTimestampNs
        Log.i(TAG, "RAW_AUDIO_START firstSensorTimestampNs=$sensorTimestampNs fps=$fps")
    }

    fun pause() {
        if (!required) return
        check(finalized == null && firstSensorTimestampNs > 0L && !paused &&
            failure.get() == null && recorder.pauseAndAwait()) {
            "RAW audio did not acknowledge pause"
        }
        paused = true
    }

    fun resume() {
        if (!required) return
        check(finalized == null && paused && failure.get() == null && recorder.resumeAndAwait()) {
            "RAW audio did not acknowledge resume"
        }
        paused = false
    }

    fun finish(persistedFrames: Int, lastSensorTimestampNs: Long): Npv10AudioSidecarAudit? {
        if (!required) return null
        finalized?.let { return it }
        failure.get()?.let { throw IllegalStateException("Required RAW audio failed", it) }
        check(firstSensorTimestampNs > 0L && persistedFrames > 0) {
            "Required RAW audio never acquired a persisted video timeline"
        }
        // NPRAW preserves physical sensor timestamps (including intentional pause gaps). Unlike the
        // NPV10 CFR programme, its audio must end on that source timeline, not frameCount / fps.
        val durationUs = RawAudioTimelinePolicy.durationUs(
            firstSensorTimestampNs, lastSensorTimestampNs, fps
        )
        val audit = requireNotNull(recorder.stopAndFinalizeAlignedToDuration(
            durationUs, Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US,
            captureUntilAligned = !paused
        )) { "Required RAW audio sidecar was not finalized" }
        check(audit.packetCount > 0L && audit.monotonicPts && audit.crcValidated) {
            "Required RAW audio failed packet integrity: $audit"
        }
        val deltaUs = kotlin.math.abs(audit.finalPresentationTimeUs - durationUs)
        check(deltaUs <= Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US) {
            "RAW A/V duration mismatch: videoUs=$durationUs audioUs=${audit.finalPresentationTimeUs}"
        }
        Npv10AudioSidecarReader(requireNotNull(master)).use { reader ->
            check(reader.header.videoOriginSensorTimestampNs == firstSensorTimestampNs) {
                "RAW audio belongs to a different sensor timeline"
            }
            while (reader.readNextPacket() != null) { /* Independently validate every packet CRC. */ }
            check(reader.requireAudit() == audit) { "RAW audio durable readback disagrees with writer" }
        }
        finalized = audit
        Log.i(TAG, "RAW_AUDIO_FINALIZED packets=${audit.packetCount} " +
            "videoDurationUs=$durationUs audioDurationUs=${audit.finalPresentationTimeUs} " +
            "deltaUs=$deltaUs crcValidated=true")
        return audit
    }

    fun abort(cause: Throwable = IllegalStateException("RAW recording was aborted")) {
        failure.compareAndSet(null, cause)
        recorder.abort()
        discardStaging()
    }

    fun markFailed(cause: Throwable) { failure.compareAndSet(null, cause) }

    /** Called only after publication or rejection; never deletes a published MediaStore companion. */
    fun discardStaging() {
        check(!recorder.isActive) { "Cannot discard RAW audio while its worker owns the file" }
        master?.let { anchor ->
            check(Npv10AudioSidecarPolicy.deleteBundleAudio(anchor)) {
                "Unable to retire private RAW audio staging"
            }
            val directory = anchor.parentFile
            if (directory != null && directory.exists()) {
                check(directory.delete()) { "RAW audio staging directory is not empty" }
            }
        }
    }

    private companion object { const val TAG = "RawRecordingAudio" }
}
