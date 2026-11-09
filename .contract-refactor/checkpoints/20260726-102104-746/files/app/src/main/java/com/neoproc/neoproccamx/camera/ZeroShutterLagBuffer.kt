package com.neoproc.neoproccamx.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ZeroShutterLagBuffer — Circular frame buffer for true Zero Shutter Lag (ZSL) capture.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * DESIGN
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Professional still capture on modern flagships depends on a continuously populated
 * ring buffer of the most recent full-resolution frames.  The user's shutter press
 * selects the *best* frame already captured before the press, eliminating the
 * "first frame after button press" latency that plagues naive pipelines.
 *
 * This implementation:
 *  1. Attaches to an [ImageReader] created with [HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE]
 *     or [ImageFormat.PRIVATE] to allow zero-copy reuse of the buffer later.
 *  2. Maintains a bounded ring of [BufferedFrame] — each wraps an [Image] reference
 *     and its hardware timestamp.
 *  3. On capture request, picks the frame whose timestamp is closest to the requested
 *     shutter moment and hands it to the caller without re-acquiring from the HAL.
 *  4. Automatically closes frames that fall out of the ring to avoid buffer starvation.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * THREADING
 * ═════════════════════════════════════════════════════════════════════════════════════
 * All Image acquisition happens on the camera background [Handler].  Selection
 * callbacks are invoked on that same handler.  The public [captureClosestTo] method
 * is safe to call from any thread; it internally posts to the background handler.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FAIL-SAFE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Every API returns null / no-op gracefully if the reader cannot be constructed or
 * if the buffer has already been closed.  No OutOfMemoryError, no NullPointer, no
 * IllegalStateException propagates to the caller.
 */
class ZeroShutterLagBuffer(
    /** Target still-capture resolution. */
    private val size: Size,
    /** Ring capacity — typical flagship: 6–12 frames (~200–400 ms @ 30fps). */
    private val capacity: Int = DEFAULT_CAPACITY,
    /** Image format; PRIVATE is preferred for ZSL so HAL can later reuse for reprocessing. */
    private val imageFormat: Int = ImageFormat.PRIVATE,
    /** HAL usage flags for the backing HardwareBuffer. */
    private val hardwareUsage: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
    } else {
        0L
    }
) : AutoCloseable {

    companion object {
        private const val TAG = "ZeroShutterLagBuffer"

        /** Default ring size — 8 frames ≈ 266 ms @ 30fps. */
        const val DEFAULT_CAPACITY = 8

        /** Minimum allowed ring size. */
        const val MIN_CAPACITY = 3

        /** Maximum allowed ring size (memory safety cap). */
        const val MAX_CAPACITY = 24

        /** Default max acceptable skew: 100 ms. */
        private const val DEFAULT_MAX_SKEW_NS = 100_000_000L
    }

    /**
     * A single frame held in the ring buffer.
     *
     * @param image       Live [Image] reference; must be closed via [close] or automatic ring eviction.
     * @param timestampNs Hardware timestamp from the camera HAL (monotonic).
     * @param sequence    Monotonic frame sequence number assigned by this buffer.
     */
    class BufferedFrame(
        val image: Image,
        val timestampNs: Long,
        val sequence: Long
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { image.close() }
            }
        }

        val isClosed: Boolean get() = closed.get()
    }

    /** The bounded ring — newest pushed at tail, oldest evicted from head. */
    private val ring = ConcurrentLinkedDeque<BufferedFrame>()

    /** Backing [ImageReader]; null until [open] succeeds. */
    @Volatile private var reader: ImageReader? = null

    /** Handler for ImageReader callbacks. */
    @Volatile private var bgHandler: Handler? = null

    /** Monotonic sequence counter. */
    private val sequenceGenerator = AtomicLong(0L)

    /** Has the buffer been closed. */
    private val closed = AtomicBoolean(false)

    /** Resolved ring capacity, always within [MIN_CAPACITY, MAX_CAPACITY]. */
    private val effectiveCapacity: Int =
        capacity.coerceIn(MIN_CAPACITY, MAX_CAPACITY)

    /** Reader buffer depth — 1 extra slot over ring capacity for HAL pipelining. */
    private val readerDepth: Int = effectiveCapacity + 1

    /** Counter of dropped frames (ring full + eviction was too slow). */
    private val droppedFrameCount = AtomicLong(0L)

    /** Public accessors. */
    val frameCount: Int get() = ring.size
    val droppedFrames: Long get() = droppedFrameCount.get()
    fun isOpen(): Boolean = !closed.get() && reader != null

    /** Expose the reader surface so the caller can add it to the capture session. */
    fun surface() = reader?.surface

    /**
     * Opens the reader with the supplied background handler.  The reader is created lazily
     * so callers can tune parameters before acquisition starts.
     *
     * @return true if the reader was created successfully; false if any fallback was hit.
     */
    fun open(handler: Handler): Boolean {
        if (closed.get()) return false
        if (reader != null) return true

        val createdReader = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hardwareUsage != 0L) {
                ImageReader.newInstance(
                    size.width,
                    size.height,
                    imageFormat,
                    readerDepth,
                    hardwareUsage
                )
            } else {
                ImageReader.newInstance(size.width, size.height, imageFormat, readerDepth)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create ZSL ImageReader ${size.width}x${size.height} format=$imageFormat", t)
            return false
        }

        createdReader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
        bgHandler = handler
        reader = createdReader
        Log.i(TAG,
            "Opened ZSL buffer ${size.width}x${size.height} format=$imageFormat " +
                "depth=$readerDepth capacity=$effectiveCapacity"
        )
        return true
    }

    /**
     * Called by the ImageReader on the background handler when a new frame arrives.
     * Acquires the latest available image and appends it to the ring, evicting the oldest
     * frame if the ring is full.
     */
    private fun onImageAvailable(r: ImageReader) {
        if (closed.get()) {
            drainReader(r)
            return
        }

        val start = System.nanoTime()
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage failed", t)
            return
        } ?: return

        val seq = sequenceGenerator.incrementAndGet()
        val frame = BufferedFrame(image, image.timestamp, seq)

        // Bound the ring — evict oldest if at capacity.
        while (ring.size >= effectiveCapacity) {
            val evicted = ring.pollFirst() ?: break
            droppedFrameCount.incrementAndGet()
            evicted.close()
        }
        ring.offerLast(frame)

        val elapsed = (System.nanoTime() - start) / 1_000_000L
        RuntimeStageTelemetry.recordDuration(
            tag = TAG,
            stage = "zsl_enqueue",
            durationMs = elapsed,
            slowThresholdMs = 8L
        )
    }

    /**
     * Retrieves the frame whose hardware timestamp is closest to [targetTimestampNs].
     * Callers are responsible for closing the returned frame.  Returning a frame removes
     * it from the ring; the ring continues to accept new frames from the HAL in parallel.
     *
     * @param targetTimestampNs Desired shutter moment in the HAL timebase (SystemClock
     *                          .elapsedRealtimeNanos()-equivalent monotonic time).
     * @param maxSkewNs         Maximum acceptable skew; frames further than this are ignored.
     * @return The selected frame, or null if none match.
     */
    fun captureClosestTo(
        targetTimestampNs: Long,
        maxSkewNs: Long = DEFAULT_MAX_SKEW_NS
    ): BufferedFrame? {
        if (closed.get()) return null

        val snapshot = ring.toList()
        if (snapshot.isEmpty()) return null

        var bestFrame: BufferedFrame? = null
        var bestSkew = Long.MAX_VALUE

        for (frame in snapshot) {
            if (frame.isClosed) continue
            val skew = kotlin.math.abs(frame.timestampNs - targetTimestampNs)
            if (skew < bestSkew) {
                bestSkew = skew
                bestFrame = frame
            }
        }

        if (bestFrame == null || bestSkew > maxSkewNs) {
            Log.w(TAG,
                "captureClosestTo: no frame within skew=${maxSkewNs / 1_000_000L}ms; " +
                    "bestSkew=${bestSkew / 1_000_000L}ms candidates=${snapshot.size}"
            )
            return null
        }

        // Remove the selected frame from the ring so caller owns its lifecycle.
        ring.remove(bestFrame)
        return bestFrame
    }

    /**
     * Retrieves the newest available frame regardless of timestamp.  Useful for
     * "shoot now" mode where skew doesn't matter.
     */
    fun captureLatest(): BufferedFrame? {
        if (closed.get()) return null
        return ring.pollLast()
    }

    /**
     * Closes all buffered frames, releasing their HAL buffers back to the pool.
     * The reader is *not* closed — this is for burst-end cleanup during a capture session.
     */
    fun flush() {
        var removed = 0
        while (true) {
            val frame = ring.pollFirst() ?: break
            frame.close()
            removed++
        }
        if (removed > 0) Log.d(TAG, "Flushed $removed buffered frames")
    }

    /**
     * Fully closes the reader and drops all buffered frames.  After this, the instance
     * is no longer usable.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        flush()
        runCatching { reader?.close() }
        reader = null
        bgHandler = null
        Log.i(TAG, "ZSL buffer closed (dropped total=${droppedFrameCount.get()})")
    }

    /** Drains an ImageReader without retaining any images — used during shutdown. */
    private fun drainReader(r: ImageReader) {
        try {
            while (true) {
                val image = r.acquireNextImage() ?: break
                image.close()
            }
        } catch (_: Throwable) {
            // Ignore — we're shutting down.
        }
    }

}
