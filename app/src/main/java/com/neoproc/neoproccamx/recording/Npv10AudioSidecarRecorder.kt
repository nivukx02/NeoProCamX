package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the NPV10 master audio lane without touching its full-resolution P010 write path.
 *
 * One urgent-audio thread owns AudioRecord, AAC MediaCodec and the sidecar writer for the complete
 * session. PTS is anchored to the exact durable first-video sensor timestamp and then advances by
 * submitted PCM frame count, preventing scheduler jitter from becoming A/V drift.
 */
internal class Npv10AudioSidecarRecorder {
    private data class PreparedCapture(
        val source: AudioSrc,
        val record: AudioRecord,
        val codec: MediaCodec,
        val pcmBufferBytes: Int
    )

    private val stopRequested = AtomicBoolean(false)
    private val abortRequested = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private val completedAudit = AtomicReference<Npv10AudioSidecarAudit?>(null)
    private val latestQueuedPcmEndUs = AtomicLong(0L)
    private val terminalAlignmentTargetUs = AtomicLong(NO_TERMINAL_ALIGNMENT)
    private val terminalAlignmentToleranceUs = AtomicLong(NO_TERMINAL_ALIGNMENT)
    private val pauseGate = Npv10AudioPauseGate()

    @Volatile private var worker: Thread? = null
    @Volatile private var preparedCapture: PreparedCapture? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var activeMaster: File? = null

    val isActive: Boolean get() = worker?.isAlive == true
    val isPrepared: Boolean get() = preparedCapture != null

    /**
     * Allocates and starts the microphone/AAC resources before the certified video timeline opens.
     *
     * Several Android audio stacks synchronously create Codec2 services and negotiate the input HAL
     * when [AudioRecord.startRecording] is called. Doing that after video frame one can starve the
     * Camera2 P010 producer for multiple frame periods even though the lossless writer itself remains
     * below budget. Pre-roll stays private and is discarded at [start]; only PCM captured after the
     * exact first-video timestamp is admitted to the durable sidecar.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun prepare(audioSource: AudioSrc) {
        check(worker == null) { "Cannot prepare NPV10 audio while capture is active" }
        val source = audioSource.normalizeForStrictHighEnd()
        require(source != AudioSrc.NONE) { "NPV10 audio source must be enabled" }
        preparedCapture?.let { prepared ->
            if (prepared.source == source &&
                prepared.record.state == AudioRecord.STATE_INITIALIZED &&
                prepared.record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                return
            }
            preparedCapture = null
            releasePreparedCapture(prepared)
        }

        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(
            Npv10AudioSidecarPolicy.SAMPLE_RATE,
            channelMask,
            pcmEncoding
        )
        check(minBuffer > 0) { "Stereo 48 kHz AudioRecord is unsupported: $minBuffer" }
        val platformSource = when (source) {
            AudioSrc.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            AudioSrc.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            AudioSrc.MIC -> MediaRecorder.AudioSource.MIC
            AudioSrc.NONE -> error("NPV10 audio source was disabled")
        }

        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            record = AudioRecord.Builder()
                .setAudioSource(platformSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Npv10AudioSidecarPolicy.SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .setEncoding(pcmEncoding)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * AUDIO_RECORD_BUFFER_MULTIPLIER)
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "NPV10 high-quality AudioRecord initialization failed"
            }

            codec = MediaCodec.createEncoderByType(Npv10AudioSidecarPolicy.MIME)
            val format = MediaFormat.createAudioFormat(
                Npv10AudioSidecarPolicy.MIME,
                Npv10AudioSidecarPolicy.SAMPLE_RATE,
                Npv10AudioSidecarPolicy.CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, Npv10AudioSidecarPolicy.BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "NPV10 AudioRecord did not enter prepared recording state"
            }

            val prepared = PreparedCapture(
                source = source,
                record = record,
                codec = codec,
                pcmBufferBytes = minBuffer
            )
            preparedCapture = prepared
            audioRecord = record
            Log.i(TAG, "NPV10 audio resources prepared before video timeline source=$source")
            record = null
            codec = null
        } finally {
            record?.let { orphan ->
                runCatching {
                    if (orphan.recordingState == AudioRecord.RECORDSTATE_RECORDING) orphan.stop()
                }
                orphan.release()
            }
            codec?.let { orphan ->
                runCatching { orphan.stop() }
                orphan.release()
            }
        }
    }

    @Synchronized
    fun start(
        master: File,
        firstVideoSensorTimestampNs: Long,
        audioSource: AudioSrc,
        preservePausedWallTime: Boolean = false,
        onFatalFailure: ((Throwable) -> Unit)? = null
    ) {
        check(worker == null) { "NPV10 audio capture is already active" }
        require(firstVideoSensorTimestampNs > 0L) {
            "NPV10 audio requires a positive first-video sensor timestamp"
        }
        val source = audioSource.normalizeForStrictHighEnd()
        require(source != AudioSrc.NONE) { "NPV10 audio source must be enabled" }

        if (preparedCapture?.source != source) {
            prepare(source)
        }
        val prepared = requireNotNull(preparedCapture) {
            "NPV10 audio preparation disappeared before timeline activation"
        }
        preparedCapture = null

        Npv10AudioSidecarPolicy.deleteBundleAudio(master)
        stopRequested.set(false)
        abortRequested.set(false)
        terminalFailure.set(null)
        completedAudit.set(null)
        latestQueuedPcmEndUs.set(0L)
        terminalAlignmentTargetUs.set(NO_TERMINAL_ALIGNMENT)
        terminalAlignmentToleranceUs.set(NO_TERMINAL_ALIGNMENT)
        try {
            val discardedPreRollBytes = discardPreparedPreRoll(prepared)
            Log.i(
                TAG,
                "NPV10 audio timeline activated after prepared pre-roll discard bytes=$discardedPreRollBytes"
            )
        } catch (failure: Throwable) {
            releasePreparedCapture(prepared)
            audioRecord = null
            throw failure
        }

        audioRecord = prepared.record
        activeMaster = master
        val captureWorker = Thread(
            {
                runCaptureLoop(
                    master = master,
                    firstVideoSensorTimestampNs = firstVideoSensorTimestampNs,
                    record = prepared.record,
                    codec = prepared.codec,
                    pcmBufferBytes = prepared.pcmBufferBytes,
                    preservePausedWallTime = preservePausedWallTime,
                    onFatalFailure = onFatalFailure
                )
            },
            "NPV10-Audio"
        ).apply { isDaemon = true }
        pauseGate.activate()
        worker = captureWorker
        try {
            captureWorker.start()
        } catch (failure: Throwable) {
            pauseGate.terminate()
            worker = null
            activeMaster = null
            audioRecord = null
            releasePreparedCapture(prepared)
            throw failure
        }
    }

    /**
     * Stops AAC admission at an acknowledged PCM boundary while continuing to drain AudioRecord.
     * Already submitted AAC input is allowed to drain; no PCM captured after the acknowledgement is
     * queued, so paused wall time cannot extend the durable sidecar timeline.
     */
    fun pauseAndAwait(timeoutMs: Long = PAUSE_TRANSITION_TIMEOUT_MS): Boolean {
        val activeWorker = worker
        if (activeWorker == null || !activeWorker.isAlive || stopRequested.get()) return false
        val acknowledged = pauseGate.requestPause(timeoutMs)
        if (acknowledged) {
            Log.i(TAG, "NPV10 audio pause acknowledged at PCM admission boundary")
        } else {
            Log.e(TAG, "NPV10 audio pause acknowledgement failed")
        }
        return acknowledged
    }

    /** Resumes AAC admission only after the capture worker acknowledges the running state. */
    fun resumeAndAwait(timeoutMs: Long = PAUSE_TRANSITION_TIMEOUT_MS): Boolean {
        val activeWorker = worker
        if (activeWorker == null || !activeWorker.isAlive || stopRequested.get()) return false
        val acknowledged = pauseGate.requestResume(timeoutMs)
        if (acknowledged) {
            Log.i(TAG, "NPV10 audio resume acknowledged at PCM admission boundary")
        } else {
            Log.e(TAG, "NPV10 audio resume acknowledgement failed")
        }
        return acknowledged
    }

    fun stopAndFinalize(): Npv10AudioSidecarAudit? {
        return stopAndFinalizeInternal(captureUntilAligned = false)
    }

    /**
     * Retains complete captured AAC when the video master is being kept privately after failure.
     * A poisoned video boundary cannot supply a trustworthy trimming target. Keep the original
     * audio timestamps and payloads instead; this does not certify A/V sync or publish the video.
     * The serialized recording-stop owner must call this before releasing the retained master.
     */
    fun stopAndPreserveForRecovery(): Npv10AudioSidecarAudit? {
        terminalAlignmentTargetUs.set(NO_TERMINAL_ALIGNMENT)
        terminalAlignmentToleranceUs.set(NO_TERMINAL_ALIGNMENT)
        return stopAndFinalizeInternal(captureUntilAligned = false)
    }

    /**
     * Failure cleanup for an audio-required video session. Session ownership, rather than isActive,
     * also covers an exited worker that still needs joining and retirement before another capture.
     * No error can turn a poisoned video into a verified publication or replace its original cause.
     */
    fun preserveAfterVideoFailure(videoFailure: Throwable) {
        runCatching { stopAndPreserveForRecovery() }
            .onSuccess { audit ->
                if (audit != null) Log.i(
                    TAG,
                    "NPV10 failed-stop audio retained privately packets=${audit.packetCount} " +
                        "bytes=${audit.payloadBytes} avSyncCertified=false"
                )
            }.onFailure { audioFailure ->
                if (audioFailure !== videoFailure) videoFailure.addSuppressed(audioFailure)
                runCatching { abort() }.onFailure { abortFailure ->
                    if (abortFailure !== videoFailure) videoFailure.addSuppressed(abortFailure)
                }
            }
    }

    /**
     * Keeps real microphone capture running until it covers the immutable video duration, then
     * finalizes on the nearest complete AAC packet boundary. This closes the normal Camera2
     * stop-fence delay without padding, timestamp stretching, transcoding, or a relaxed sync gate.
     */
    fun stopAndFinalizeAlignedToDuration(
        targetDurationUs: Long,
        maximumAllowedDeltaUs: Long,
        captureUntilAligned: Boolean
    ): Npv10AudioSidecarAudit? {
        require(targetDurationUs > 0L) { "NPV10 terminal audio target must be positive" }
        require(maximumAllowedDeltaUs >= 0L) {
            "NPV10 terminal audio tolerance must be non-negative"
        }
        terminalAlignmentTargetUs.set(targetDurationUs)
        terminalAlignmentToleranceUs.set(maximumAllowedDeltaUs)
        if (captureUntilAligned) {
            awaitRealPcmCoverage(targetDurationUs)
        }
        return stopAndFinalizeInternal(captureUntilAligned = captureUntilAligned)
    }

    private fun stopAndFinalizeInternal(captureUntilAligned: Boolean): Npv10AudioSidecarAudit? {
        val activeWorker = worker ?: return null
        stopRequested.set(true)
        pauseGate.terminate()
        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { record.stop() }
            }
        }
        activeWorker.join(STOP_JOIN_TIMEOUT_MS)
        check(!activeWorker.isAlive) {
            activeWorker.interrupt()
            "NPV10 audio worker did not terminate within ${STOP_JOIN_TIMEOUT_MS}ms"
        }
        val failure = terminalFailure.get()
        val audit = completedAudit.get()
        clearRetiredSession(activeWorker)
        failure?.let { throw IllegalStateException("NPV10 audio capture failed", it) }
        return audit ?: error(
            "NPV10 audio capture ended without a durable sidecar audit" +
                if (captureUntilAligned) " after terminal alignment" else ""
        )
    }

    private fun awaitRealPcmCoverage(targetDurationUs: Long) {
        val nominalPacketUs = Npv10AudioSidecarPolicy.nominalPacketDurationUs(
            Npv10AudioSidecarPolicy.SAMPLE_RATE
        )
        val requestedCoverageUs = Math.addExact(targetDurationUs, nominalPacketUs)
        val deadlineMs = SystemClock.elapsedRealtime() + TERMINAL_ALIGNMENT_CAPTURE_TIMEOUT_MS
        while (worker?.isAlive == true &&
            terminalFailure.get() == null &&
            latestQueuedPcmEndUs.get() < requestedCoverageUs &&
            SystemClock.elapsedRealtime() < deadlineMs
        ) {
            try {
                Thread.sleep(TERMINAL_ALIGNMENT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Log.i(
            TAG,
            "NPV10 terminal real-PCM coverage targetUs=$targetDurationUs " +
                "queuedEndUs=${latestQueuedPcmEndUs.get()} requestedCoverageUs=$requestedCoverageUs"
        )
    }

    fun abort() {
        val activeWorker = worker
        val inactivePrepared = preparedCapture
        preparedCapture = null
        abortRequested.set(true)
        stopRequested.set(true)
        pauseGate.terminate()
        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { record.stop() }
            }
        }
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            activeWorker.join(STOP_JOIN_TIMEOUT_MS)
            if (activeWorker.isAlive) activeWorker.interrupt()
        }
        if (activeWorker == null) {
            inactivePrepared?.let(::releasePreparedCapture)
        }
        activeMaster?.let(Npv10AudioSidecarPolicy::deleteBundleAudio)
        clearRetiredSession(activeWorker)
    }

    fun release() = abort()

    private fun runCaptureLoop(
        master: File,
        firstVideoSensorTimestampNs: Long,
        record: AudioRecord,
        codec: MediaCodec,
        pcmBufferBytes: Int,
        preservePausedWallTime: Boolean,
        onFatalFailure: ((Throwable) -> Unit)?
    ) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        var sidecarWriter: Npv10AudioSidecarWriter? = null
        val info = MediaCodec.BufferInfo()
        val timeline = RecordingAudioSyncPolicy.Timeline(
            recordingStartNs = firstVideoSensorTimestampNs,
            sampleRate = Npv10AudioSidecarPolicy.SAMPLE_RATE,
            channelCount = Npv10AudioSidecarPolicy.CHANNEL_COUNT,
            bytesPerSample = PCM_BYTES_PER_SAMPLE
        )
        var eosQueued = false
        var eosSeen = false
        var previousOutputPtsUs = -1L
        var stopDeadlineNs = Long.MAX_VALUE
        var pausedPcmBytesDiscarded = 0L
        val pausedPcmBuffer = java.nio.ByteBuffer.allocateDirect(pcmBufferBytes)

        // RAW stores original sensor timestamps across pause; NPV10 intentionally compacts them.
        // Preserve the matching RAW audio gaps using measured discarded PCM frames, without adding
        // fake microphone samples or changing the existing NPV10 pause contract.
        fun audioTimelineUs(compactUs: Long): Long = Math.addExact(compactUs,
            if (preservePausedWallTime) RawAudioTimelinePolicy.discardedPcmDurationUs(
                pausedPcmBytesDiscarded, Npv10AudioSidecarPolicy.SAMPLE_RATE,
                Npv10AudioSidecarPolicy.CHANNEL_COUNT
            ) else 0L)

        fun ensureWriter(format: MediaFormat): Npv10AudioSidecarWriter {
            sidecarWriter?.let { return it }
            val codecConfig = format.getByteBuffer("csd-0")?.let { source ->
                val bytes = ByteArray(source.remaining())
                source.duplicate().get(bytes)
                bytes
            } ?: ByteArray(0)
            check(codecConfig.isNotEmpty()) { "AAC encoder did not publish csd-0" }
            return Npv10AudioSidecarWriter(
                master = master,
                header = Npv10AudioSidecarHeader(
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        Npv10AudioSidecarPolicy.BIT_RATE
                    },
                    videoOriginSensorTimestampNs = firstVideoSensorTimestampNs,
                    codecConfig = codecConfig
                )
            ).also { sidecarWriter = it }
        }

        fun drainOutput(timeoutUs: Long): Boolean {
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        ensureWriter(codec.outputFormat)
                    }
                    outputIndex >= 0 -> {
                        try {
                            val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !codecConfig) {
                                val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val payload = ByteArray(info.size)
                                output.get(payload)
                                val ptsUs = RecordingAudioSyncPolicy.monotonicAacPresentationTimeUs(
                                    candidatePtsUs = info.presentationTimeUs,
                                    previousPtsUs = previousOutputPtsUs,
                                    sampleRate = Npv10AudioSidecarPolicy.SAMPLE_RATE
                                )
                                ensureWriter(codec.outputFormat).writePacket(
                                    Npv10AudioPacket(
                                        presentationTimeUs = ptsUs,
                                        flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
                                        payload = payload
                                    )
                                )
                                previousOutputPtsUs = ptsUs
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                return true
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        }

        try {
            while (!eosSeen) {
                eosSeen = drainOutput(if (eosQueued) DRAIN_TIMEOUT_US else 0L)
                if (eosSeen) break

                if (stopRequested.get()) {
                    if (!eosQueued) {
                        val inputIndex = codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                audioTimelineUs(timeline.endPresentationTimeUs()),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                            stopDeadlineNs = System.nanoTime() + EOS_TIMEOUT_NS
                        }
                    } else if (System.nanoTime() >= stopDeadlineNs) {
                        error("NPV10 AAC encoder did not return EOS before timeout")
                    }
                } else if (pauseGate.shouldDiscardPcm()) {
                    // Keep the hardware source drained while excluding paused wall time from AAC.
                    // Acknowledgement occurs before the blocking read: from this boundary onward the
                    // worker cannot submit PCM until an acknowledged resume transition.
                    pauseGate.acknowledge(paused = true)
                    pausedPcmBuffer.clear()
                    val bytesRead = record.read(
                        pausedPcmBuffer,
                        pausedPcmBuffer.remaining(),
                        AudioRecord.READ_BLOCKING
                    )
                    if (bytesRead > 0) {
                        pausedPcmBytesDiscarded = Math.addExact(
                            pausedPcmBytesDiscarded,
                            bytesRead.toLong()
                        )
                    } else if (bytesRead == AudioRecord.ERROR_DEAD_OBJECT && !stopRequested.get()) {
                        error("NPV10 AudioRecord became unavailable while paused")
                    }
                } else {
                    // Resume is acknowledged immediately before the next codec-input/capture cycle.
                    pauseGate.acknowledge(paused = false)
                    val inputIndex = codec.dequeueInputBuffer(DRAIN_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex)).apply {
                            clear()
                            order(ByteOrder.LITTLE_ENDIAN)
                            limit(minOf(capacity(), pcmBufferBytes))
                        }
                        val bytesRead = record.read(input, input.remaining(), AudioRecord.READ_BLOCKING)
                        // A pause can arrive while AudioRecord.read() is blocked. Return the dequeued
                        // codec buffer empty and discard that PCM rather than leaking it past the
                        // acknowledged video/audio boundary.
                        if (pauseGate.shouldDiscardPcm()) {
                            if (bytesRead > 0) {
                                pausedPcmBytesDiscarded = Math.addExact(
                                    pausedPcmBytesDiscarded,
                                    bytesRead.toLong()
                                )
                            }
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                audioTimelineUs(timeline.endPresentationTimeUs()),
                                0
                            )
                            pauseGate.acknowledge(paused = true)
                        } else if (bytesRead > 0) {
                            val alignedBytes = bytesRead - (bytesRead % PCM_FRAME_BYTES)
                            if (alignedBytes > 0) {
                                val readCompletedNs = SystemClock.elapsedRealtimeNanos()
                                val ptsUs = audioTimelineUs(
                                    timeline.presentationTimeUs(readCompletedNs, alignedBytes)
                                )
                                codec.queueInputBuffer(inputIndex, 0, alignedBytes, ptsUs, 0)
                                timeline.commitQueuedBytes(alignedBytes)
                                latestQueuedPcmEndUs.set(audioTimelineUs(timeline.endPresentationTimeUs()))
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                            }
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                            if (bytesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                                error("NPV10 AudioRecord became unavailable")
                            }
                        }
                    }
                }
            }
            check(eosQueued && eosSeen) { "NPV10 AAC stream ended without EOS" }
            val writer = sidecarWriter ?: error("NPV10 AAC encoder produced no output format")
            if (abortRequested.get()) {
                writer.abort()
            } else {
                val alignmentTargetUs = terminalAlignmentTargetUs.get()
                val alignmentToleranceUs = terminalAlignmentToleranceUs.get()
                completedAudit.set(
                    if (alignmentTargetUs > 0L && alignmentToleranceUs >= 0L) {
                        writer.finishAlignedToDuration(
                            targetDurationUs = alignmentTargetUs,
                            maximumAllowedDeltaUs = alignmentToleranceUs
                        )
                    } else {
                        writer.finish()
                    }
                )
                Log.i(
                    TAG,
                    "NPV10 audio finalized with pausedPcmBytesDiscarded=$pausedPcmBytesDiscarded " +
                        "targetDurationUs=$alignmentTargetUs " +
                        "finalDurationUs=${completedAudit.get()?.finalPresentationTimeUs}"
                )
            }
        } catch (failure: Throwable) {
            sidecarWriter?.abort()
            terminalFailure.compareAndSet(null, failure)
            Log.e(TAG, "NPV10 synchronized audio capture failed", failure)
            if (!abortRequested.get()) {
                // Notify only; the serialized recording owner must join/retire this worker.
                // A callback must never stop or join the audio thread from the audio thread itself.
                runCatching { onFatalFailure?.invoke(failure) }.onFailure { callbackFailure ->
                    Log.e(TAG, "Audio terminal-failure notification failed", callbackFailure)
                }
            }
        } finally {
            pauseGate.terminate()
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            record.release()
            runCatching { codec.stop() }
            codec.release()
            if (audioRecord === record) audioRecord = null
        }
    }

    @Synchronized
    private fun clearRetiredSession(expectedWorker: Thread?) {
        if (worker === expectedWorker) worker = null
        preparedCapture = null
        audioRecord = null
        activeMaster = null
        stopRequested.set(false)
        abortRequested.set(false)
        latestQueuedPcmEndUs.set(0L)
        terminalAlignmentTargetUs.set(NO_TERMINAL_ALIGNMENT)
        terminalAlignmentToleranceUs.set(NO_TERMINAL_ALIGNMENT)
        pauseGate.terminate()
    }

    private fun discardPreparedPreRoll(prepared: PreparedCapture): Long {
        val scratch = java.nio.ByteBuffer.allocateDirect(prepared.pcmBufferBytes)
        var discardedBytes = 0L
        repeat(MAX_PRE_ROLL_DRAIN_READS) {
            scratch.clear()
            val read = prepared.record.read(
                scratch,
                scratch.remaining(),
                AudioRecord.READ_NON_BLOCKING
            )
            if (read <= 0) return discardedBytes
            discardedBytes = Math.addExact(discardedBytes, read.toLong())
        }
        return discardedBytes
    }

    private fun releasePreparedCapture(prepared: PreparedCapture) {
        runCatching {
            if (prepared.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                prepared.record.stop()
            }
        }
        prepared.record.release()
        runCatching { prepared.codec.stop() }
        prepared.codec.release()
    }

    private companion object {
        const val TAG = "Npv10AudioRecorder"
        const val AUDIO_RECORD_BUFFER_MULTIPLIER = 4
        const val MAX_PRE_ROLL_DRAIN_READS = 64
        const val PCM_BYTES_PER_SAMPLE = 2
        const val PCM_FRAME_BYTES = Npv10AudioSidecarPolicy.CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE
        const val DRAIN_TIMEOUT_US = 10_000L
        const val EOS_TIMEOUT_NS = 3_000_000_000L
        const val STOP_JOIN_TIMEOUT_MS = 5_000L
        const val PAUSE_TRANSITION_TIMEOUT_MS = 2_000L
        const val TERMINAL_ALIGNMENT_CAPTURE_TIMEOUT_MS = 2_000L
        const val TERMINAL_ALIGNMENT_POLL_MS = 2L
        const val NO_TERMINAL_ALIGNMENT = -1L
    }
}
