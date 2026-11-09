package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class QuickTimeVideoTrackConfig(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codecConfig: ByteArray,
    val colorConfig: QuickTimeColorAtomConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
)

internal data class QuickTimeAudioTrackConfig(
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int,
    val codecConfig: ByteArray
)

internal data class QuickTimeSampleBuffer(
    val bytes: ByteArray,
    val offset: Int = 0,
    val length: Int = bytes.size,
    val presentationTimeUs: Long,
    val flags: Int
)

internal interface QuickTimeMovBridge {
    fun open(outputPath: String, containerLabel: String): Long
    fun open(
        outputPath: String,
        containerLabel: String,
        preallocationPlan: RecordingPreallocationPolicy.Plan
    ): Long = open(outputPath, containerLabel)
    fun addVideoTrack(handle: Long, config: QuickTimeVideoTrackConfig): Int
    fun addAudioTrack(handle: Long, config: QuickTimeAudioTrackConfig): Int
    fun writeSample(handle: Long, trackIndex: Int, sample: QuickTimeSampleBuffer)
    fun writeSampleBuffer(
        handle: Long,
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        writeSample(
            handle = handle,
            trackIndex = trackIndex,
            sample = QuickTimeSampleBuffer(
                bytes = bytes,
                presentationTimeUs = presentationTimeUs,
                flags = flags
            )
        )
    }
    fun setOrientationHint(handle: Long, degrees: Int)
    fun close(handle: Long)
    fun abort(handle: Long)
    /**
     * Finalize and return the writer's durable allocation result. The default preserves compatibility
     * with non-reporting test/native bridges while the Kotlin writer returns the exact finalized facts.
     */
    fun closeWithPreallocationResult(handle: Long): RecordingPreallocationResult? {
        close(handle)
        return null
    }

    /** Abort and return whatever allocation/trim evidence the writer could durably seal. */
    fun abortWithPreallocationResult(handle: Long): RecordingPreallocationResult? {
        abort(handle)
        return null
    }
}

/**
 * Pure-Kotlin implementation of [QuickTimeMovBridge].
 *
 * Despite this living next to a native shader/Vulkan stack, the QuickTime MOV
 * writer is implemented end-to-end in Kotlin via [QuickTimeMovFileWriter]
 * — there is no JNI here. The previous name `NativeQuickTimeMovBridge` was
 * misleading; this rename keeps the door open for a future JNI variant
 * without lying about the current implementation.
 *
 * The actual MOV byte stream is real QuickTime: `qt  ` major brand, 64-bit
 * `mdat` large-size header, `moov`/`trak`/`mdia` chain, with sample-entry
 * fourCCs `avc1` / `hvc1` / `av01` matched to the muxed codec mime.
 */
internal class KotlinQuickTimeMovBridge : QuickTimeMovBridge {
    private val nextHandle = AtomicLong(1L)
    private val writers = ConcurrentHashMap<Long, QuickTimeMovFileWriter>()

    override fun open(outputPath: String, containerLabel: String): Long {
        return open(
            outputPath = outputPath,
            containerLabel = containerLabel,
            preallocationPlan = RecordingPreallocationPolicy.processed(0L)
        )
    }

    override fun open(
        outputPath: String,
        containerLabel: String,
        preallocationPlan: RecordingPreallocationPolicy.Plan
    ): Long {
        val handle = nextHandle.getAndIncrement()
        writers[handle] = QuickTimeMovFileWriter(
            outputPath = outputPath,
            containerLabel = containerLabel,
            preallocationPlan = preallocationPlan
        )
        return handle
    }

    override fun addVideoTrack(handle: Long, config: QuickTimeVideoTrackConfig): Int =
        requireWriter(handle).addVideoTrack(config)

    override fun addAudioTrack(handle: Long, config: QuickTimeAudioTrackConfig): Int =
        requireWriter(handle).addAudioTrack(config)

    override fun writeSample(handle: Long, trackIndex: Int, sample: QuickTimeSampleBuffer) {
        requireWriter(handle).writeSample(trackIndex, sample)
    }

    override fun writeSampleBuffer(
        handle: Long,
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        requireWriter(handle).writeSampleBuffer(
            trackIndex = trackIndex,
            buffer = buffer,
            presentationTimeUs = presentationTimeUs,
            flags = flags
        )
    }

    override fun close(handle: Long) {
        closeWithPreallocationResult(handle)
    }

    override fun abort(handle: Long) {
        abortWithPreallocationResult(handle)
    }

    override fun closeWithPreallocationResult(handle: Long): RecordingPreallocationResult? {
        val writer = writers.remove(handle) ?: return null
        writer.close()
        return writer.preallocationResult
    }

    override fun abortWithPreallocationResult(handle: Long): RecordingPreallocationResult? {
        val writer = writers.remove(handle) ?: return null
        writer.abort()
        return writer.preallocationResult
    }

    override fun setOrientationHint(handle: Long, degrees: Int) {
        requireWriter(handle).setOrientationHint(degrees)
    }

    private fun requireWriter(handle: Long): QuickTimeMovFileWriter =
        writers[handle] ?: error("Unknown QuickTime writer handle: $handle")
}
