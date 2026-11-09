package com.neoproc.neoproccamx.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.recording.Npv10FileReader
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.Npv10PacketCodecEngine
import com.neoproc.neoproccamx.recording.Npv10ThumbnailRenderer
import com.neoproc.neoproccamx.recording.Npv10NativeFrameBuffer
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.Raw12ThumbnailRenderer
import com.neoproc.neoproccamx.recording.ThumbnailImage
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/** A private acquisition master that must be monitored directly, never auto-converted or published. */
internal sealed interface PrivateMasterPlaybackSource {
    data class Npv10(val file: File) : PrivateMasterPlaybackSource

    data class Npraw(
        val streamUri: Uri,
        val metadataUri: Uri
    ) : PrivateMasterPlaybackSource
}

/**
 * Read-only, deadline-driven playback for NPV10 and NPRAW acquisition masters.
 *
 * This controller is deliberately isolated from Media3: these files are frame containers, not encoded
 * Android media bitstreams. A single bounded worker performs packet I/O and the project's existing
 * colour/develop math; the UI only receives the newest complete display frame. When decode falls behind,
 * historical frames are skipped at the packet layer instead of building an unbounded bitmap queue.
 * No converter, MediaStore publisher, recording object, or camera thread is reachable from this class.
 */
internal class PrivateMasterPlaybackController(
    private val context: Context,
    private val source: PrivateMasterPlaybackSource,
    private val targetLongEdge: Int,
    initialPlayWhenReady: Boolean,
    private val evidenceSession: PlaybackEvidenceSession,
    private val onReady: () -> Unit,
    private val onError: (Throwable) -> Unit
) : Closeable {

    val view: View = AppCompatImageView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        keepScreenOn = true
    }

    private val imageView: AppCompatImageView
        get() = view as AppCompatImageView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val readerRef = AtomicReference<MasterFrameReader?>(null)
    private val retiringReader = AtomicReference<MasterFrameReader?>(null)
    private val executor = object : ScheduledThreadPoolExecutor(
        1,
        { runnable ->
            Thread(
                {
                    runCatching {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                    }
                    runnable.run()
                },
                "private-master-playback"
            ).apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
            }
        }
    ) {
        override fun terminated() {
            try {
                // Full reader/native-buffer retirement is worker-owned. shutdownNow() first stops
                // every queued frame; terminated() then runs only after an in-flight read/render has
                // returned, so direct buffers can never be freed while native code still samples them.
                retiringReader.getAndSet(null)?.let(::closeReader)
                readerRef.getAndSet(null)?.let(::closeReader)
            } finally {
                evidenceSession.releaseResource(RESOURCE_WORKER)
                super.terminated()
            }
        }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    private val stateLock = Any()
    private val generation = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<PendingDisplayFrame?>(null)
    private val uiPostPending = AtomicBoolean(false)
    private val bitmapPool = ConcurrentLinkedQueue<Bitmap>()
    private val allocatedBitmaps = AtomicInteger(0)
    private val readerEvidenceActive = AtomicBoolean(false)

    @Volatile private var ready = false
    @Volatile private var frameRate = 1
    @Volatile private var frameCount = 0
    @Volatile private var durationMsValue = 0L
    @Volatile private var durationUsValue = 0L
    @Volatile private var displayedBitmap: Bitmap? = null

    private var playWhenReady = initialPlayWhenReady
    private var playing = false
    private var playbackSpeed = 1f
    private var anchorPositionUs = 0L
    private var anchorRealtimeNs = 0L
    private var estimatedDecodeNs = 0L
    private var evidenceGeometryRecorded = false

    init {
        evidenceSession.acquireResource(RESOURCE_CONTROLLER)
        evidenceSession.acquireResource(RESOURCE_OUTPUT)
        evidenceSession.acquireResource(RESOURCE_WORKER)
        executor.execute(::initialize)
    }

    val durationMs: Long
        get() = durationMsValue

    val isPlaying: Boolean
        get() = synchronized(stateLock) { playing }

    val currentPositionMs: Long
        get() = synchronized(stateLock) {
            currentPositionUsLocked(SystemClock.elapsedRealtimeNanos()) / 1_000L
        }

    fun play() {
        val token: Long
        synchronized(stateLock) {
            playWhenReady = true
            if (!ready || playing || closed.get()) return
            playing = true
            anchorRealtimeNs = SystemClock.elapsedRealtimeNanos()
            token = generation.incrementAndGet()
        }
        scheduleFrame(token, force = true, requestedFrame = FRAME_FROM_CLOCK, delayNs = 0L)
    }

    fun pause() {
        synchronized(stateLock) {
            playWhenReady = false
            if (!playing) return
            anchorPositionUs = currentPositionUsLocked(SystemClock.elapsedRealtimeNanos())
            playing = false
            generation.incrementAndGet()
        }
        evidenceSession.recordDiscontinuity()
    }

    fun seekTo(positionMs: Long) {
        val token: Long
        synchronized(stateLock) {
            val bounded = positionMs.coerceIn(0L, durationMsValue.coerceAtLeast(0L))
            anchorPositionUs = bounded * 1_000L
            anchorRealtimeNs = SystemClock.elapsedRealtimeNanos()
            token = generation.incrementAndGet()
        }
        evidenceSession.recordDiscontinuity()
        if (ready) scheduleFrame(token, force = true, requestedFrame = FRAME_FROM_CLOCK, delayNs = 0L)
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed > 0f) { "Playback speed must be positive" }
        val token: Long
        synchronized(stateLock) {
            anchorPositionUs = currentPositionUsLocked(SystemClock.elapsedRealtimeNanos())
            anchorRealtimeNs = SystemClock.elapsedRealtimeNanos()
            playbackSpeed = speed
            token = generation.incrementAndGet()
        }
        if (ready) scheduleFrame(token, force = true, requestedFrame = FRAME_FROM_CLOCK, delayNs = 0L)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        synchronized(stateLock) {
            playing = false
            playWhenReady = false
        }
        // Cancel I/O immediately so a stalled read unblocks, but retire frame buffers on the playback
        // worker after any in-progress render returns. Releasing a direct buffer from this thread while
        // the worker samples it would be a use-after-free race.
        val activeReader = readerRef.getAndSet(null)
        if (activeReader != null) {
            check(retiringReader.compareAndSet(null, activeReader)) {
                "Private-master reader retirement already pending"
            }
            runCatching { activeReader.cancelIo() }
        }
        // Cancel every delayed decode/presentation immediately. The executor's terminated() hook owns
        // the final reader/direct-buffer close after an in-flight task has safely exited.
        executor.shutdownNow()
        val releaseDisplayResources = Runnable {
            imageView.setImageDrawable(null)
            latestFrame.getAndSet(null)?.bitmap?.let(::releaseBitmap)
            displayedBitmap?.let(::releaseBitmap)
            displayedBitmap = null
            recycleBitmapPool()
            evidenceSession.releaseResource(RESOURCE_OUTPUT)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseDisplayResources.run()
        } else {
            mainHandler.post(releaseDisplayResources)
        }
        evidenceSession.releaseResource(RESOURCE_CONTROLLER)
        evidenceSession.requestFinish("private_controller_closed")
    }

    private fun initialize() {
        if (closed.get()) return
        var opened: MasterFrameReader? = null
        try {
            opened = when (val selected = source) {
                is PrivateMasterPlaybackSource.Npv10 -> Npv10MasterFrameReader(
                    file = selected.file,
                    targetLongEdge = targetLongEdge
                )
                is PrivateMasterPlaybackSource.Npraw -> NprawMasterFrameReader(
                    context = context.applicationContext,
                    streamUri = selected.streamUri,
                    metadataUri = selected.metadataUri,
                    targetLongEdge = targetLongEdge
                )
            }
            check(readerEvidenceActive.compareAndSet(false, true)) {
                "Private-master reader evidence already active"
            }
            evidenceSession.acquireResource(RESOURCE_READER)
            require(opened.frameCount > 0) { "Private master contains no complete video frames" }
            check(!closed.get()) { "Private-master player closed during initialization" }
            check(readerRef.compareAndSet(null, opened)) { "Private-master reader already initialized" }
            val ownedReader = checkNotNull(opened)
            opened = null // ownership transferred to readerRef
            // close() can race the check above and observe readerRef before or after this CAS. If it
            // completed before publication, this worker must retire the newly published reader itself;
            // otherwise the executor is already shut down and no later task can release native buffers.
            if (closed.get()) {
                if (readerRef.compareAndSet(ownedReader, null)) {
                    runCatching { ownedReader.cancelIo() }
                    closeReader(ownedReader)
                }
                return
            }
            frameRate = ownedReader.fps.coerceAtLeast(1)
            frameCount = ownedReader.frameCount
            evidenceSession.recordFormat(
                width = 0,
                height = 0,
                nominalFps = frameRate.toFloat(),
                sampleMimeType = when (source) {
                    is PrivateMasterPlaybackSource.Npv10 -> "application/x-npv10"
                    is PrivateMasterPlaybackSource.Npraw -> "application/x-npraw"
                }
            )
            durationUsValue = ((ownedReader.frameCount.toLong() * 1_000_000L) / frameRate).coerceAtLeast(1L)
            durationMsValue = (durationUsValue / 1_000L).coerceAtLeast(1L)
            val token: Long
            synchronized(stateLock) {
                ready = true
                playing = playWhenReady
                anchorRealtimeNs = SystemClock.elapsedRealtimeNanos()
                token = generation.incrementAndGet()
            }
            mainHandler.post {
                if (!closed.get()) {
                    imageView.rotation = ownedReader.orientationDegrees.toFloat()
                    onReady()
                }
            }
            scheduleFrame(token, force = true, requestedFrame = FRAME_FROM_CLOCK, delayNs = 0L)
        } catch (t: Throwable) {
            reportError(t)
        } finally {
            opened?.let(::closeReader)
        }
    }

    private fun scheduleFrame(token: Long, force: Boolean, requestedFrame: Int, delayNs: Long) {
        if (closed.get() || executor.isShutdown) return
        runCatching {
            executor.schedule(
                { renderDeadline(token, force, requestedFrame) },
                delayNs.coerceAtLeast(0L),
                TimeUnit.NANOSECONDS
            )
        }.onFailure { error ->
            if (!closed.get()) reportError(error)
        }
    }

    private fun renderDeadline(token: Long, force: Boolean, requestedFrame: Int) {
        if (closed.get() || token != generation.get()) return
        val shouldContinue = synchronized(stateLock) { playing }
        if (!shouldContinue && !force) return
        val opened = readerRef.get() ?: return

        try {
            var positionUs = synchronized(stateLock) {
                currentPositionUsLocked(SystemClock.elapsedRealtimeNanos())
            }
            if (positionUs >= durationUsValue) {
                synchronized(stateLock) {
                    anchorPositionUs = 0L
                    anchorRealtimeNs = SystemClock.elapsedRealtimeNanos()
                }
                positionUs = 0L
                evidenceSession.recordDiscontinuity()
            }
            val clockFrame = VideoPlaybackPerformancePolicy.frameAtPosition(
                positionUs = positionUs,
                fps = frameRate,
                frameCount = frameCount
            )
            val targetFrame = if (requestedFrame == FRAME_FROM_CLOCK) {
                clockFrame
            } else {
                maxOf(requestedFrame, clockFrame).coerceAtMost(frameCount - 1)
            }
            evidenceSession.recordPrivateFrameScheduled(targetFrame)
            val decodeStartNs = SystemClock.elapsedRealtimeNanos()
            val image = opened.readFrame(targetFrame)
                ?: throw IllegalStateException("Private master ended before frame $targetFrame")
            if (closed.get() || token != generation.get()) return
            evidenceSession.recordDecodedFrame()
            if (!evidenceGeometryRecorded) {
                evidenceGeometryRecorded = true
                evidenceSession.recordFormat(
                    width = image.width,
                    height = image.height,
                    nominalFps = frameRate.toFloat(),
                    sampleMimeType = null
                )
            }
            estimatedDecodeNs = VideoPlaybackPerformancePolicy.updatedDecodeLeadNs(
                previousNs = estimatedDecodeNs,
                sampleNs = SystemClock.elapsedRealtimeNanos() - decodeStartNs
            )
            val speed = synchronized(stateLock) { playbackSpeed }
            val currentUsAfterDecode = synchronized(stateLock) {
                currentPositionUsLocked(SystemClock.elapsedRealtimeNanos())
            }
            val presentationDelayNs = if (shouldContinue) {
                VideoPlaybackPerformancePolicy.presentationDelayNs(
                    frameIndex = targetFrame,
                    currentPositionUs = currentUsAfterDecode,
                    fps = frameRate,
                    playbackSpeed = speed
                )
            } else {
                0L
            }
            schedulePresentation(
                token = token,
                image = image,
                targetFrame = targetFrame,
                delayNs = presentationDelayNs
            )
        } catch (t: Throwable) {
            reportError(t)
        }
    }

    private fun schedulePresentation(
        token: Long,
        image: ThumbnailImage,
        targetFrame: Int,
        delayNs: Long
    ) {
        if (closed.get() || executor.isShutdown) return
        runCatching {
            executor.schedule(
                { presentDecodedFrame(token, image, targetFrame) },
                delayNs.coerceAtLeast(0L),
                TimeUnit.NANOSECONDS
            )
        }.onFailure { error ->
            if (!closed.get()) reportError(error)
        }
    }

    private fun presentDecodedFrame(token: Long, image: ThumbnailImage, targetFrame: Int) {
        if (closed.get() || token != generation.get()) return
        publishLatest(image, targetFrame)

        if (!synchronized(stateLock) { playing } || token != generation.get()) return
        try {
            val speed = synchronized(stateLock) { playbackSpeed }
            val nextDeadlinePositionUs = ((targetFrame + 1L) * 1_000_000L) / frameRate
            val currentUs = synchronized(stateLock) {
                currentPositionUsLocked(SystemClock.elapsedRealtimeNanos())
            }
            val wrapping = targetFrame == frameCount - 1
            val nextRequestedFrame = if (wrapping) FRAME_FROM_CLOCK else targetFrame + 1
            val delayNs = VideoPlaybackPerformancePolicy.decodeStartDelayNs(
                remainingMediaUs = nextDeadlinePositionUs - currentUs,
                playbackSpeed = speed,
                // Do not decode frame zero before the repeat boundary; that would display it early.
                estimatedDecodeNs = if (wrapping) 0L else estimatedDecodeNs
            )
            scheduleFrame(
                token = token,
                force = false,
                requestedFrame = nextRequestedFrame,
                delayNs = delayNs
            )
        } catch (t: Throwable) {
            reportError(t)
        }
    }

    private fun currentPositionUsLocked(nowNs: Long): Long {
        if (!playing) return anchorPositionUs.coerceIn(0L, durationUsValue.coerceAtLeast(0L))
        val elapsedUs = (
            (nowNs - anchorRealtimeNs).coerceAtLeast(0L).toDouble() /
                1_000.0 * playbackSpeed.toDouble()
            ).toLong()
        return (anchorPositionUs + elapsedUs)
            .coerceIn(0L, durationUsValue.coerceAtLeast(0L))
    }

    private fun publishLatest(image: ThumbnailImage, targetFrame: Int) {
        if (closed.get()) return
        val bitmap = acquireBitmap(image.width, image.height)
        if (bitmap == null) {
            evidenceSession.recordOutputDrop()
            return
        }
        bitmap.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
        bitmap.prepareToDraw()
        val ptsUs = (targetFrame.toLong() * 1_000_000L) / frameRate.coerceAtLeast(1).toLong()
        val pending = PendingDisplayFrame(bitmap, ptsUs)
        latestFrame.getAndSet(pending)?.let { replaced ->
            evidenceSession.recordUiConflation()
            releaseBitmap(replaced.bitmap)
        }
        requestUiDrain()
    }

    private fun requestUiDrain() {
        if (!uiPostPending.compareAndSet(false, true)) return
        mainHandler.post {
            val newest = latestFrame.getAndSet(null)
            if (!closed.get() && newest != null) {
                val previous = displayedBitmap
                displayedBitmap = newest.bitmap
                imageView.setImageBitmap(newest.bitmap)
                evidenceSession.recordPresentation(
                    ptsUs = newest.ptsUs,
                    presentationNs = SystemClock.elapsedRealtimeNanos()
                )
                if (previous !== newest.bitmap) previous?.let(::releaseBitmap)
            } else {
                newest?.bitmap?.let(::releaseBitmap)
            }
            uiPostPending.set(false)
            if (latestFrame.get() != null) requestUiDrain()
        }
    }

    private fun acquireBitmap(width: Int, height: Int): Bitmap? {
        while (true) {
            val pooled = bitmapPool.poll() ?: break
            if (!pooled.isRecycled && pooled.width == width && pooled.height == height) return pooled
            if (!pooled.isRecycled) {
                pooled.recycle()
                evidenceSession.releaseResource(RESOURCE_BITMAP)
            }
            allocatedBitmaps.decrementAndGet()
        }
        while (true) {
            val allocated = allocatedBitmaps.get()
            if (allocated >= MAX_BITMAPS) return null
            if (allocatedBitmaps.compareAndSet(allocated, allocated + 1)) {
                return try {
                    createBitmap(width, height).also {
                        evidenceSession.acquireResource(RESOURCE_BITMAP)
                    }
                } catch (failure: Throwable) {
                    allocatedBitmaps.decrementAndGet()
                    throw failure
                }
            }
        }
    }

    private fun releaseBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (closed.get()) {
            bitmap.recycle()
            allocatedBitmaps.decrementAndGet()
            evidenceSession.releaseResource(RESOURCE_BITMAP)
        } else {
            bitmapPool.offer(bitmap)
        }
    }

    private fun recycleBitmapPool() {
        while (true) {
            val bitmap = bitmapPool.poll() ?: break
            if (!bitmap.isRecycled) bitmap.recycle()
            allocatedBitmaps.decrementAndGet()
            evidenceSession.releaseResource(RESOURCE_BITMAP)
        }
    }

    private fun reportError(error: Throwable) {
        if (closed.get()) return
        Log.e(TAG, "Private-master playback failed", error)
        evidenceSession.recordDecoderError(error)
        evidenceSession.recordPlayerError(error)
        synchronized(stateLock) {
            playing = false
            playWhenReady = false
        }
        generation.incrementAndGet()
        ready = false
        readerRef.getAndSet(null)?.let(::closeReader)
        mainHandler.post { if (!closed.get()) onError(error) }
    }

    private fun closeReader(reader: MasterFrameReader) {
        try {
            reader.close()
        } catch (failure: Throwable) {
            evidenceSession.recordDecoderError(failure)
            Log.e(TAG, "Private-master reader release failed", failure)
        } finally {
            if (readerEvidenceActive.compareAndSet(true, false)) {
                evidenceSession.releaseResource(RESOURCE_READER)
            }
        }
    }

    private data class PendingDisplayFrame(
        val bitmap: Bitmap,
        val ptsUs: Long
    )

    private interface MasterFrameReader : Closeable {
        val fps: Int
        val frameCount: Int
        val orientationDegrees: Int
        fun readFrame(index: Int): ThumbnailImage?
        /** Thread-safe I/O cancellation; full buffer retirement remains worker-owned via [close]. */
        fun cancelIo()
    }

    private class Npv10MasterFrameReader(
        file: File,
        private val targetLongEdge: Int
    ) : MasterFrameReader {
        private val resources = PlaybackResources.open(file, targetLongEdge)
        private val reader = resources.reader
        override val fps: Int = reader.header.fps.coerceAtLeast(1)
        override val frameCount: Int = resources.frameCount
        override val orientationDegrees: Int = reader.header.orientationHintDegrees
        private var nextFrameIndex = resources.initialFrameIndex
        private val storedPacketBuffer = resources.stored.buffer.order(ByteOrder.LITTLE_ENDIAN)
        private val decodedFrameBuffer = resources.decoded.buffer.order(ByteOrder.LITTLE_ENDIAN)
        private var reusableArgb: IntArray? = null

        override fun readFrame(index: Int): ThumbnailImage? {
            val requested = index.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
            if (requested != nextFrameIndex) {
                nextFrameIndex = reader.seekToFrameForPlayback(requested)
            }
            val frame = reader.readNextFrameDirect(storedPacketBuffer, decodedFrameBuffer) ?: return null
            nextFrameIndex += 1
            return Npv10ThumbnailRenderer.renderDirectFrameForPlayback(
                header = reader.header,
                frame = frame,
                maxLongEdge = targetLongEdge,
                reusableArgb = reusableArgb
            ).also { reusableArgb = it.argb }
        }

        override fun cancelIo() = resources.cancelIo()

        override fun close() = resources.close()

        private class PlaybackResources private constructor(
            val reader: Npv10FileReader,
            val frameCount: Int,
            val initialFrameIndex: Int,
            val stored: Npv10NativeFrameBuffer,
            val decoded: Npv10NativeFrameBuffer
        ) : Closeable {
            private val ioClosed = AtomicBoolean(false)
            private val closed = AtomicBoolean(false)

            fun cancelIo() {
                if (ioClosed.compareAndSet(false, true)) reader.close()
            }

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                cancelIo()
                decoded.close()
                stored.close()
            }

            companion object {
                fun open(file: File, targetLongEdge: Int): PlaybackResources {
                    val reader = Npv10FileReader(file)
                    var stored: Npv10NativeFrameBuffer? = null
                    var decoded: Npv10NativeFrameBuffer? = null
                    try {
                        val frameCount = reader.countFramesForPlayback()
                        val initialFrameIndex = reader.seekToFrameForPlayback(0)
                        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                            reader.header.width,
                            reader.header.height,
                            reader.header.payloadFormat
                        )
                        val storedBytes = Npv10PacketCodecEngine.storedBytes(
                            decodedBytes,
                            reader.header.packetCodec
                        )
                        require(storedBytes in 1..Int.MAX_VALUE.toLong()) {
                            "NPV10 stored frame is too large for playback: $storedBytes bytes"
                        }
                        require(decodedBytes in 1..Int.MAX_VALUE.toLong()) {
                            "NPV10 decoded frame is too large for playback: $decodedBytes bytes"
                        }
                        val admission = VideoPlaybackPerformancePolicy.playbackMemoryAdmission(
                            storedFrameBytes = storedBytes,
                            decodedFrameBytes = decodedBytes,
                            targetLongEdge = targetLongEdge,
                            maxMemoryBytes = Runtime.getRuntime().maxMemory()
                        )
                        require(admission.admitted) {
                            "NPV10 playback working set ${admission.requiredBytes} exceeds safe budget " +
                                "${admission.budgetBytes} bytes"
                        }
                        val storedBuffer = Npv10NativeFrameBuffer.allocate(storedBytes.toInt())
                        stored = storedBuffer
                        val decodedBuffer = Npv10NativeFrameBuffer.allocate(decodedBytes.toInt())
                        decoded = decodedBuffer
                        return PlaybackResources(
                            reader = reader,
                            frameCount = frameCount,
                            initialFrameIndex = initialFrameIndex,
                            stored = storedBuffer,
                            decoded = decodedBuffer
                        )
                    } catch (failure: Throwable) {
                        runCatching { decoded?.close() }
                        runCatching { stored?.close() }
                        runCatching { reader.close() }
                        throw failure
                    }
                }
            }
        }
    }

    private class NprawMasterFrameReader(
        private val context: Context,
        private val streamUri: Uri,
        metadataUri: Uri,
        private val targetLongEdge: Int
    ) : MasterFrameReader {
        private val metadata = context.contentResolver.openInputStream(metadataUri)?.use { stream ->
            RawDevelopMetadataSidecar.decode(stream.readBytes())
        } ?: error("Unable to open RAW develop metadata")
        private val inventory = inspectInventory()
        private val reductionPlan = if (inventory.fixedLinear16 || inventory.blockRangeLossless) {
            VideoPlaybackPerformancePolicy.bayerReductionPlan(
                sourceWidth = metadata.width,
                sourceHeight = metadata.height,
                targetLongEdge = Raw12ThumbnailRenderer.playbackBayerLongEdge(targetLongEdge)
            )
        } else {
            null
        }
        private val reducedSamples = reductionPlan?.let { IntArray(it.sourceSampleIndices.size) }
        private val reducedMetadata = reductionPlan?.let {
            metadata.copy(width = it.reducedWidth, height = it.reducedHeight)
        }
        private val headerScratch = if (inventory.fixedLinear16) ByteArray(NPRAW_V2_HEADER_BYTES) else null
        private val payloadScratch = if (inventory.fixedLinear16) ByteArray(NPRAW_PLAYBACK_CHUNK_BYTES) else null
        private val payloadCrc = if (inventory.fixedLinear16) CRC32() else null
        private val blockRangeDecoder = if (inventory.blockRangeLossless) {
            val plan = checkNotNull(reductionPlan)
            NprawFrameCodec.BlockRangePlaybackDecoder(
                sourceWidth = metadata.width,
                sourceHeight = metadata.height,
                reducedWidth = plan.reducedWidth,
                reducedHeight = plan.reducedHeight,
                selectedSourceSampleIndices = plan.sourceSampleIndices
            )
        } else {
            null
        }

        override val fps: Int = metadata.fps.coerceAtLeast(1)
        override val orientationDegrees: Int = metadata.orientationHintDegrees
        override val frameCount: Int = inventory.frameCount
        private val inputLock = Any()
        private var input: BufferedInputStream? = openStream()
        private var nextFrameIndex = 0
        private val frameSkipper = NprawFrameCodec.StreamFrameSkipper()
        private var reusableArgb: IntArray? = null
        private val developWorkspace = Raw12ThumbnailRenderer.ReusableWorkspace()
        private val ioClosed = AtomicBoolean(false)
        private val playbackCache: Array<ThumbnailImage?>? = createExactPlaybackCache()

        override fun readFrame(index: Int): ThumbnailImage? {
            val requested = index.coerceIn(0, (frameCount - 1).coerceAtLeast(0))
            playbackCache?.get(requested)?.let { return it }
            if (requested < nextFrameIndex) reopenAtStart()
            val activeInput = currentInput()
            while (nextFrameIndex < requested) {
                if (!frameSkipper.skipNext(activeInput)) return null
                nextFrameIndex += 1
            }
            val image = (
                if (inventory.fixedLinear16) {
                    readRealtimeLinear16Frame(activeInput)
                } else if (inventory.blockRangeLossless) {
                    readRealtimeBlockRangeFrame(activeInput)
                } else {
                    val decoded = NprawFrameCodec.readFrame(activeInput) ?: return null
                    require(decoded.width == metadata.width && decoded.height == metadata.height) {
                        "RAW frame ${decoded.width}x${decoded.height} does not match metadata " +
                            "${metadata.width}x${metadata.height}"
                    }
                    Raw12ThumbnailRenderer.renderPlayback(
                        firstFrameSamples = decoded.samples,
                        meta = metadata,
                        maxLongEdge = targetLongEdge,
                        reusableArgb = reusableArgb,
                        workspace = developWorkspace
                    )
                }
                ) ?: return null
            nextFrameIndex += 1
            reusableArgb = image.argb
            val stable = if (playbackCache != null) {
                // The develop workspace intentionally reuses its output. Cache a stable exact copy so
                // subsequent loop/seek playback performs no new demosaic and preserves every pixel.
                ThumbnailImage(image.width, image.height, image.argb.copyOf())
            } else {
                image
            }
            playbackCache?.set(requested, stable)
            return stable
        }

        override fun cancelIo() {
            val streamToClose = synchronized(inputLock) {
                if (!ioClosed.compareAndSet(false, true)) {
                    null
                } else {
                    input.also { input = null }
                }
            }
            runCatching { streamToClose?.close() }
        }

        override fun close() = cancelIo()

        private fun reopenAtStart() {
            check(!ioClosed.get()) { "NPRAW playback is closed" }
            val replacement = openStream()
            val previous = synchronized(inputLock) {
                if (ioClosed.get()) {
                    runCatching { replacement.close() }
                    throw IllegalStateException("NPRAW playback closed during rewind")
                }
                input.also {
                    input = replacement
                    nextFrameIndex = 0
                }
            }
            runCatching { previous?.close() }
        }

        private fun currentInput(): BufferedInputStream = synchronized(inputLock) {
            check(!ioClosed.get()) { "NPRAW playback is closed" }
            checkNotNull(input) { "NPRAW playback stream is unavailable" }
        }

        /**
         * The current recorder's realtime master is fixed-size linear little-endian RAW16. Read it
         * through bounded reusable storage and retain only the Bayer samples the existing develop
         * would select. This is byte-for-byte equivalent to Raw12ThumbnailRenderer's reduction, but
         * removes the full sensor-sized packet, payload, and IntArray allocations on every frame.
         */
        private fun readRealtimeLinear16Frame(input: BufferedInputStream): ThumbnailImage? {
            val header = checkNotNull(headerScratch)
            val firstByte = input.read()
            if (firstByte < 0) return null
            header[0] = firstByte.toByte()
            readFully(input, header, from = 1, length = header.size - 1, what = "NPRAW header")
            for (index in NPRAW_MAGIC.indices) {
                require(header[index] == NPRAW_MAGIC[index]) { "Not an NPRAW frame (bad magic)" }
            }
            val version = header[NPRAW_VERSION_OFFSET].toInt() and 0xFF
            val modeOrdinal = readIntBe(header, NPRAW_MODE_OFFSET)
            val width = readIntBe(header, NPRAW_WIDTH_OFFSET)
            val height = readIntBe(header, NPRAW_HEIGHT_OFFSET)
            val payloadBytes = readIntBe(header, NPRAW_PAYLOAD_SIZE_OFFSET)
            val bitDepth = readIntBe(header, NPRAW_BIT_DEPTH_OFFSET)
            val expectedCrc = readLongBe(header, NPRAW_CRC_OFFSET)
            val expectedPayloadBytes = Math.multiplyExact(
                Math.multiplyExact(metadata.width, metadata.height),
                Short.SIZE_BYTES
            )
            require(
                version == NprawFrameCodec.VERSION_LINEAR &&
                    modeOrdinal == RawCompressionMode.LOSSLESS_REALTIME.ordinal &&
                    width == metadata.width &&
                    height == metadata.height &&
                    bitDepth == 16 &&
                    payloadBytes == expectedPayloadBytes
            ) {
                "NPRAW realtime playback packet does not match its immutable RAW16 metadata"
            }

            val plan = checkNotNull(reductionPlan)
            val samples = checkNotNull(reducedSamples)
            val scratch = checkNotNull(payloadScratch)
            val crc = checkNotNull(payloadCrc).apply { reset() }
            var payloadByteOffset = 0
            var outputIndex = 0
            while (payloadByteOffset < payloadBytes) {
                val bytesThisPass = minOf(scratch.size, payloadBytes - payloadByteOffset)
                readFully(
                    input = input,
                    target = scratch,
                    from = 0,
                    length = bytesThisPass,
                    what = "NPRAW RAW16 payload"
                )
                crc.update(scratch, 0, bytesThisPass)
                val firstSample = payloadByteOffset / Short.SIZE_BYTES
                val sampleLimit = (payloadByteOffset + bytesThisPass) / Short.SIZE_BYTES
                while (outputIndex < plan.sourceSampleIndices.size) {
                    val sourceSample = plan.sourceSampleIndices[outputIndex]
                    if (sourceSample >= sampleLimit) break
                    require(sourceSample >= firstSample) { "NPRAW Bayer reduction plan is not monotonic" }
                    val byteIndex = (sourceSample - firstSample) * Short.SIZE_BYTES
                    samples[outputIndex++] =
                        (scratch[byteIndex].toInt() and 0xFF) or
                            ((scratch[byteIndex + 1].toInt() and 0xFF) shl 8)
                }
                payloadByteOffset += bytesThisPass
            }
            require(outputIndex == samples.size) { "NPRAW frame ended before the reduced Bayer raster" }
            require(crc.value == expectedCrc) { "NPRAW payload CRC mismatch" }
            return Raw12ThumbnailRenderer.renderPlayback(
                firstFrameSamples = samples,
                meta = checkNotNull(reducedMetadata),
                maxLongEdge = targetLongEdge,
                reusableArgb = reusableArgb,
                workspace = developWorkspace
            )
        }

        /**
         * Decode only the Bayer samples consumed by the monitoring proxy. The codec still streams and
         * CRC-validates the complete v4 payload, preserving bit-exact selected sensor codes while avoiding
         * the full-resolution payload copy and sensor-sized IntArray used by the conversion decoder.
         */
        private fun readRealtimeBlockRangeFrame(input: BufferedInputStream): ThumbnailImage? {
            val samples = checkNotNull(reducedSamples)
            if (!checkNotNull(blockRangeDecoder).readNext(input, samples)) return null
            return Raw12ThumbnailRenderer.renderPlayback(
                firstFrameSamples = samples,
                meta = checkNotNull(reducedMetadata),
                maxLongEdge = targetLongEdge,
                reusableArgb = reusableArgb,
                workspace = developWorkspace
            )
        }

        private fun inspectInventory(): NprawPlaybackInventory {
            val streamBytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(streamUri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                        ?: descriptor.parcelFileDescriptor.statSize.takeIf {
                            descriptor.startOffset == 0L && it >= 0L
                        }
                }
            }.getOrNull()
            if (streamBytes != null) {
                val fixedCount = openStream().use { stream ->
                    NprawFrameCodec.estimateFixedFrameCount(stream, streamBytes)
                }
                if (fixedCount != null) {
                    return NprawPlaybackInventory(
                        frameCount = fixedCount,
                        fixedLinear16 = true,
                        blockRangeLossless = false
                    )
                }
            }
            val firstVersion = openStream().use(NprawFrameCodec::firstFrameVersion)
            return NprawPlaybackInventory(
                frameCount = openStream().use(NprawFrameCodec::countFrames),
                fixedLinear16 = false,
                blockRangeLossless = firstVersion == NprawFrameCodec.VERSION_BLOCK_RANGE_LOSSLESS
            )
        }

        private fun openStream(): BufferedInputStream {
            val source: InputStream = context.contentResolver.openInputStream(streamUri)
                ?: error("Unable to open NPRAW master")
            return BufferedInputStream(source, STREAM_BUFFER_BYTES)
        }

        private fun createExactPlaybackCache(): Array<ThumbnailImage?>? {
            if (frameCount !in 1..MAX_EXACT_CACHE_FRAMES) return null
            val sourceWidth = reductionPlan?.reducedWidth ?: metadata.width
            val sourceHeight = reductionPlan?.reducedHeight ?: metadata.height
            val longEdge = maxOf(sourceWidth, sourceHeight)
            val scale = if (longEdge <= targetLongEdge) {
                1
            } else {
                (longEdge + targetLongEdge - 1) / targetLongEdge
            }
            val outputWidth = maxOf(1, sourceWidth / scale)
            val outputHeight = maxOf(1, sourceHeight / scale)
            val requiredBytes = runCatching {
                Math.multiplyExact(
                    Math.multiplyExact(
                        Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong()),
                        Int.SIZE_BYTES.toLong()
                    ),
                    frameCount.toLong()
                )
            }.getOrElse { Long.MAX_VALUE }
            val runtimeBudget = Runtime.getRuntime().maxMemory() / EXACT_CACHE_HEAP_DIVISOR
            val budget = minOf(MAX_EXACT_CACHE_BYTES, runtimeBudget)
            return if (requiredBytes in 1..budget) arrayOfNulls(frameCount) else null
        }

        private data class NprawPlaybackInventory(
            val frameCount: Int,
            val fixedLinear16: Boolean,
            val blockRangeLossless: Boolean
        )
    }

    companion object {
        private const val TAG = "PrivateMasterPlayer"
        private const val MAX_BITMAPS = 3
        private const val STREAM_BUFFER_BYTES = 1024 * 1024
        private const val MAX_EXACT_CACHE_FRAMES = 120
        private const val MAX_EXACT_CACHE_BYTES = 64L * 1024L * 1024L
        private const val EXACT_CACHE_HEAP_DIVISOR = 8L
        private const val FRAME_FROM_CLOCK = -1
        private const val RESOURCE_CONTROLLER = "private-controller"
        private const val RESOURCE_OUTPUT = "private-output"
        private const val RESOURCE_WORKER = "private-worker"
        private const val RESOURCE_READER = "private-reader"
        private const val RESOURCE_BITMAP = "private-bitmap"
        private const val NPRAW_V2_HEADER_BYTES = 70
        private const val NPRAW_PLAYBACK_CHUNK_BYTES = 256 * 1024
        private const val NPRAW_VERSION_OFFSET = 5
        private const val NPRAW_MODE_OFFSET = 6
        private const val NPRAW_WIDTH_OFFSET = 10
        private const val NPRAW_HEIGHT_OFFSET = 14
        private const val NPRAW_PAYLOAD_SIZE_OFFSET = 38
        private const val NPRAW_BIT_DEPTH_OFFSET = 42
        private const val NPRAW_CRC_OFFSET = 62
        private val NPRAW_MAGIC = byteArrayOf(
            'N'.code.toByte(),
            'P'.code.toByte(),
            'R'.code.toByte(),
            'A'.code.toByte(),
            'W'.code.toByte()
        )

        private fun readFully(
            input: InputStream,
            target: ByteArray,
            from: Int,
            length: Int,
            what: String
        ) {
            var offset = from
            val limit = Math.addExact(from, length)
            while (offset < limit) {
                val read = input.read(target, offset, limit - offset)
                require(read >= 0) { "$what is truncated at ${offset - from}/$length bytes" }
                offset += read
            }
        }

        private fun readIntBe(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun readLongBe(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until Long.SIZE_BYTES) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
            }
            return value
        }
    }
}
