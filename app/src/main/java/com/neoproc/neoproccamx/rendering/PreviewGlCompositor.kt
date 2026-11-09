package com.neoproc.neoproccamx.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameTuning
import com.neoproc.neoproccamx.niv99.Niv99MetadataLookupStatus
import com.neoproc.neoproccamx.niv99.Niv99PreviewFrameJoinPolicy
import com.neoproc.neoproccamx.niv99.Niv99PreviewMetadataWakePolicy
import com.neoproc.neoproccamx.niv99.Niv99PreviewShaderTuning
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuity
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuityPolicy
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningFallbackStatus
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Compact, device-bounded 2D representation of a 3D LUT for the GLES2 fallback renderer. */
internal data class PreviewLutAtlasLayout(
    val cubeSize: Int,
    val columns: Int,
    val rows: Int
) {
    val width: Int = cubeSize * columns
    val height: Int = cubeSize * rows
}

internal enum class PreviewLutTexturePrecision {
    RGBA16F,
    RGBA8_EXPLICIT_NON_STRICT
}

/**
 * Pure identity checks for preview state that may be republished by periodic analysis. Reapplying an
 * identical value must not reset temporal history or present the same camera texture a second time.
 */
internal object PreviewCompositorRedrawPolicy {
    fun debayerPresentationChanged(current: Boolean, requested: Boolean): Boolean =
        current != requested

    fun debayerOrientationChanged(current: FloatArray, requested: FloatArray): Boolean =
        !current.contentEquals(requested)

    fun creativeLutChanged(
        currentData: FloatArray?,
        currentSize: Int,
        requestedData: FloatArray?,
        requestedSize: Int
    ): Boolean {
        if (currentSize != requestedSize) return true
        if (currentData === requestedData) return false
        if (currentData == null || requestedData == null) return currentData !== requestedData
        return !currentData.contentEquals(requestedData)
    }
}

/** Pure policy kept outside GL so 65^3 layout, precision admission, and packing are host-testable. */
internal object PreviewLutAtlasPolicy {
    fun requiredFloatCount(cubeSize: Int): Int? {
        if (cubeSize <= 1) return null
        val count = cubeSize.toLong() * cubeSize.toLong() * cubeSize.toLong() * 3L
        return count.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    fun resolveLayout(cubeSize: Int, maximumTextureSize: Int): PreviewLutAtlasLayout? {
        if (cubeSize <= 1 || maximumTextureSize < cubeSize) return null
        val maximumTilesPerAxis = maximumTextureSize / cubeSize
        if (maximumTilesPerAxis.toLong() * maximumTilesPerAxis.toLong() < cubeSize.toLong()) {
            return null
        }
        val squareColumns = kotlin.math.ceil(kotlin.math.sqrt(cubeSize.toDouble())).toInt()
        var columns = squareColumns.coerceAtMost(maximumTilesPerAxis).coerceAtLeast(1)
        var rows = (cubeSize + columns - 1) / columns
        if (rows > maximumTilesPerAxis) {
            columns = (cubeSize + maximumTilesPerAxis - 1) / maximumTilesPerAxis
            rows = (cubeSize + columns - 1) / columns
        }
        if (columns > maximumTilesPerAxis || rows > maximumTilesPerAxis) return null
        return PreviewLutAtlasLayout(cubeSize, columns, rows)
    }

    fun resolvePrecision(
        halfFloatTextureSupported: Boolean,
        strictHighEnd: Boolean
    ): PreviewLutTexturePrecision? = when {
        halfFloatTextureSupported -> PreviewLutTexturePrecision.RGBA16F
        strictHighEnd -> null
        else -> PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT
    }

    fun sourceFloatOffset(red: Int, green: Int, blue: Int, cubeSize: Int): Int =
        (red + green * cubeSize + blue * cubeSize * cubeSize) * 3

    /** IEEE-754 binary32 -> binary16, round-to-nearest-even for finite LUT values. */
    fun floatToHalfBits(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xff
        var mantissa = bits and 0x7fffff
        if (exponent == 0xff) {
            return (sign or 0x7c00 or if (mantissa == 0) 0 else 0x0200).toShort()
        }
        var halfExponent = exponent - 127 + 15
        if (halfExponent >= 31) return (sign or 0x7c00).toShort()
        if (halfExponent <= 0) {
            if (halfExponent < -10) return sign.toShort()
            mantissa = mantissa or 0x800000
            val shift = 14 - halfExponent
            val halfway = 1 shl (shift - 1)
            val rounded = (mantissa + halfway - 1 + ((mantissa ushr shift) and 1)) ushr shift
            return (sign or rounded).toShort()
        }
        mantissa += 0x0fff + ((mantissa ushr 13) and 1)
        if ((mantissa and 0x800000) != 0) {
            mantissa = 0
            halfExponent += 1
            if (halfExponent >= 31) return (sign or 0x7c00).toShort()
        }
        return (sign or (halfExponent shl 10) or (mantissa ushr 13)).toShort()
    }
}

internal data class PreviewCreativeLutUploadEvidence(
    val accepted: Boolean,
    val cubeSize: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val precision: PreviewLutTexturePrecision?,
    val failure: String? = null
)

/** Exact-frame bridge owned by the camera pipeline and consumed only on the GL render thread. */
internal interface Niv99PreviewFrameProcessor {
    /** False for RAW/sensor-linear routes so an unrelated preview never waits for NIV99 metadata. */
    fun expectsPixelTuning(): Boolean

    /** Bounded exact-frame rendezvous derived from the configured sensor cadence. */
    fun exactFrameMetadataWaitNs(): Long

    fun copyExactTuning(sensorTimestampNs: Long, output: Niv99LiveFrameTuning): Niv99MetadataLookupStatus

    /** Snapshots current UI/camera route authority when a SurfaceTexture frame is latched. */
    fun copyCurrentContinuity(output: Niv99PreviewTuningContinuity): Boolean

    /** Preview-only lookup; implementations must never route this API into recording. */
    fun copyRecentTuning(
        targetSensorTimestampNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity,
        output: Niv99LiveFrameTuning
    ): Niv99PreviewTuningFallbackStatus

    /** Called once for the terminal exact-frame lookup; intermediate retry probes are not misses. */
    fun onTuningLookupFinalized(
        sensorTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        tuning: Niv99LiveFrameTuning
    ): Boolean

    fun onPixelsPresented(
        tuning: Niv99LiveFrameTuning,
        presentedSensorTimestampNs: Long,
        source: Niv99PreviewTuningSource,
        expectedContinuity: Niv99PreviewTuningContinuity
    ): Boolean
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  PREVIEW GL COMPOSITOR  (additive per-mode preview look)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * An isolated `SurfaceTexture → GLES2 → SurfaceView` stage that sits in front of
 * the VISIBLE preview only. The camera renders into [inputSurface] (a SurfaceTexture
 * at the stream size); this compositor samples that external texture, applies the
 * per-mode monitor grade ([GlVisiblePreviewPolicy.PreviewGrade]), and draws to the
 * SurfaceView's output surface at the SAME stream size — so the existing
 * View-level framing/rotation/overlay pipeline (which fits a stream-sized buffer
 * into the view) is byte-for-byte unchanged; only the pixels are shaped.
 *
 * Isolation guarantees (see [GlVisiblePreviewPolicy]):
 *  • Own EGL display/context, shared with EGL_NO_CONTEXT — no state shared with the
 *    encoder/Vulkan record path.
 *  • Strict RGB10_A2 window surface verified from the selected EGL config; an 8-bit visible
 *    fallback is never admitted. The context remains isolated from MediaCodec/Vulkan recording.
 *  • Runs on its own thread; frame-driven (one draw per camera frame), so it self-
 *    paces to the camera cadence and cannot spin.
 *
 * All public methods are safe to call from the main thread. GL work is confined to
 * the internal render thread.
 */
internal class PreviewGlCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var running = false
    @Volatile
    private var releaseInProgress = false
    private var releaseLatch: CountDownLatch? = null

    // A persistent Camera2 pair keeps both inputs streaming. The inactive compositor still drains
    // its SurfaceTexture, but skips shader work and output swaps until it is selected for warmup.
    @Volatile
    private var presentationEnabled = true

    @Volatile
    private var recordingPriorityActive = false

    @Volatile
    private var previewWorkload = PreviewRecordingWorkloadPolicy.resolve(recordingActive = false)

    @Volatile
    private var inputSurface: Surface? = null

    @Volatile
    private var gradeCode = GRADE_IDENTITY

    // Identity of the current output surface + size, so a re-engage with the same
    // target only swaps the grade instead of rebuilding EGL.
    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0

    // ── GL/EGL state — touched ONLY on the render thread ──────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uGradeLoc = 0
    private var uTexelSizeLoc = 0
    private var uNiv99ActiveLoc = 0
    private var uNiv99RgbCorrectionLoc = 0
    private var uNiv99SpatialDenoiseLoc = 0
    private var uNiv99ChromaDenoiseLoc = 0
    private var uNiv99SharpeningCeilingLoc = 0
    private var uNiv99ConfidenceLoc = 0
    private var uRecordingPriorityLoc = 0
    private var uHistory0Loc = 0
    private var uHistory1Loc = 0
    private var uTemporalHistoryCountLoc = 0
    private var uTemporalLumaStrengthLoc = 0
    private var uTemporalChromaStrengthLoc = 0
    private var uTemporalNoisePressureLoc = 0
    private var uOutputDitherEnabledLoc = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val surfaceWidthScratch = IntArray(1)
    private val surfaceHeightScratch = IntArray(1)
    private val niv99FrameTuning = Niv99LiveFrameTuning()
    private val niv99ShaderTuning = Niv99PreviewShaderTuning()
    private val niv99ExpectedContinuity = Niv99PreviewTuningContinuity()
    private var niv99ExpectedContinuityValid = false
    private var niv99TuningSourceForLatchedFrame: Niv99PreviewTuningSource? = null
    private var latchedSensorTimestampNs = 0L
    private var niv99PacketMatchedForLatchedFrame = false
    private var niv99PixelReportedForLatchedFrame = false
    private var niv99MissReportedForLatchedFrame = false
    private var niv99LookupReportedForLatchedFrame = false
    private var niv99LookupAcceptedForLatchedFrame = false
    private var niv99MetadataWaitDeadlineNs = 0L
    private var niv99MetadataDeadlineScheduled = false
    private val niv99AwaitedMetadataTimestampNs = AtomicLong(0L)
    private var niv99PacketsMatched = 0L
    private var niv99PacketsMissed = 0L
    private var niv99RecentPacketsReused = 0L
    private var niv99PixelsApplied = 0L
    private var recordingPriorityFramesPresented = 0L
    private val temporalFusionPolicy = PreviewTemporalFusionPolicy()
    private val temporalFusionInput = PreviewTemporalFusionInput()
    private val temporalFusionDecision = PreviewTemporalFusionDecision()
    private val temporalHistoryTextureIds = IntArray(TEMPORAL_TEXTURE_POOL_SIZE)
    private var temporalSentinelTextureId = 0
    private var temporalFramebufferId = 0
    private var temporalPresentProgram = 0
    private var temporalPresentPositionLoc = 0
    private var temporalPresentTexCoordLoc = 0
    private var temporalPresentTextureLoc = 0
    private var temporalHistoryWidth = 0
    private var temporalHistoryHeight = 0
    private var temporalHistoryWriteIndex = 0
    private var temporalHistoryValidCount = 0
    private var temporalFp16TextureSupported = false
    private var temporalFp16ColorBufferSupported = false
    private var temporalShaderAbiAvailable = false
    private var temporalHistoryAllocated = false
    private var temporalRuntimeFailed = false
    private var temporalHistoryCommits = 0L
    private var temporalFramesPresented = 0L
    private var temporalResourceFailures = 0L
    private var temporalStageExecutedForDraw = false
    private var temporalHistoryCapturedForDraw = false
    private var temporalRenderingToFp16ForDraw = false
    private var lastTemporalBypassLogged: PreviewTemporalFusionReason? = null
    private val temporalResetRequest = AtomicReference(
        PreviewTemporalFusionReason.CONTEXT_REBUILT
    )
    private var frameAvailable = false
    private var validInputFrameLatched = false
    private var latchedFramePresented = false
    @Volatile private var niv99FrameProcessor: Niv99PreviewFrameProcessor? = null
    @Volatile private var framePresentedListener: ((Long, Surface, Long) -> Unit)? = null
    @Volatile private var inputFrameListener: ((Long) -> Unit)? = null
    @Volatile private var runtimeFailureListener: ((PreviewRuntimeFailure) -> Unit)? = null
    @Volatile private var lastRuntimeFailure: PreviewRuntimeFailure? = null
    private val runtimeFailureGate = PreviewRuntimeFailureGate()
    private val renderWakeQueued = AtomicBoolean(false)
    private val niv99MetadataWakeQueued = AtomicBoolean(false)
    private val renderRunnable = Runnable {
        renderWakeQueued.set(false)
        drawFrame()
    }
    private val niv99MetadataWakeRunnable = Runnable {
        niv99MetadataWakeQueued.set(false)
        if (running && validInputFrameLatched && !niv99PacketMatchedForLatchedFrame) {
            drawFrame()
        }
    }
    private val niv99MetadataDeadlineRunnable = Runnable {
        niv99MetadataDeadlineScheduled = false
        val awaitedTimestampNs = niv99AwaitedMetadataTimestampNs.get()
        if (running && validInputFrameLatched && !niv99PacketMatchedForLatchedFrame &&
            awaitedTimestampNs > 0L && awaitedTimestampNs == latchedSensorTimestampNs
        ) {
            drawFrame()
        }
    }

    private val quadVertices: FloatBuffer = floatBuffer(
        // x, y            s, t
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    /**
     * (Re)start the compositor rendering [output] at [width]×[height] with [grade].
     * Returns true if it is running (ready to feed [inputSurface] to the camera).
     * If GL init fails on this device, returns false. Strict video callers must preserve the
     * requested mode and fail closed rather than exposing a direct 8-bit Camera2 surface.
     */
    fun start(output: Surface, width: Int, height: Int, grade: Int): Boolean {
        if (releaseInProgress) return false
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (running && boundOutput === output && boundWidth == w && boundHeight == h) {
            setGrade(grade)
            return true
        }
        // Seamless Camera2 -> GL handoff: the input SurfaceTexture may already be running on a
        // tiny pbuffer while Camera2 is still presenting directly to the visible SurfaceView.
        // Once Camera2 confirms that it released the window surface, attach that same warm EGL
        // context to the window. The camera input surface and its queued frame stay intact, so the
        // handoff needs neither a second capture-session rebuild nor a black initialization frame.
        if (running && boundOutput == null && boundWidth == w && boundHeight == h) {
            setGrade(grade)
            return attachOutput(output, w, h)
        }
        // Any change of output/size gets a clean rebuild for correctness.
        release()
        if (releaseInProgress) return false
        gradeCode = grade
        // Run the visible-preview GL render thread at URGENT_DISPLAY (real Process nice) so the
        // viewfinder is never preempted by background analysis/AI. Scheduling only — no pixel change.
        val t = HandlerThread("PreviewGL", previewThreadPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching { initGl(output, w, h) }
                .onFailure { Log.w(TAG, "PreviewGlCompositor init failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "PreviewGlCompositor start aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        lastRuntimeFailure = null
        runtimeFailureGate.armAfterSuccessfulStart()
        running = true
        boundOutput = output
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "PreviewGlCompositor started ${w}x${h} grade=$grade")
        return true
    }

    /**
     * Prepares the isolated GL context and camera input SurfaceTexture without acquiring the visible
     * SurfaceView. Camera2 can hot-swap from its direct target to [inputSurface] while its old session
     * remains live; [start] attaches the visible output only after Camera2 acknowledges release.
     */
    fun prepareInput(width: Int, height: Int, grade: Int): Boolean {
        if (releaseInProgress) return false
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (running && boundOutput == null && boundWidth == w && boundHeight == h) {
            setGrade(grade)
            return inputSurface != null
        }
        if (running) {
            release()
            if (releaseInProgress) return false
        }
        gradeCode = grade
        val t = HandlerThread("PreviewGL", previewThreadPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching { initGl(output = null, width = w, height = h) }
                .onFailure { Log.w(TAG, "PreviewGlCompositor input prewarm failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "PreviewGlCompositor input prewarm aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        lastRuntimeFailure = null
        runtimeFailureGate.armAfterSuccessfulStart()
        running = true
        boundOutput = null
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "PreviewGlCompositor input prewarmed ${w}x${h} grade=$grade")
        return inputSurface != null
    }

    fun isOutputAttached(): Boolean = running && boundOutput != null

    /**
     * Releases only the visible window producer while retaining the camera input SurfaceTexture,
     * shader program, and latest texture on a 1x1 pbuffer. SurfaceFlinger keeps the last window
     * buffer visible until the next compositor attaches, enabling a no-black owner swap.
     */
    fun detachOutput(): Boolean {
        if (!running) return false
        if (boundOutput == null) return true
        val hnd = handler ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            val config = eglConfig
            if (config == null || eglDisplay === EGL14.EGL_NO_DISPLAY ||
                eglContext === EGL14.EGL_NO_CONTEXT
            ) {
                latch.countDown()
                return@post
            }
            val attrs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            val pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, config, attrs, 0)
            if (pbuffer === EGL14.EGL_NO_SURFACE ||
                !EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
            ) {
                if (pbuffer !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, pbuffer)
                }
                latch.countDown()
                return@post
            }
            val previous = eglSurface
            eglSurface = pbuffer
            if (previous !== EGL14.EGL_NO_SURFACE && previous !== pbuffer) {
                EGL14.eglDestroySurface(eglDisplay, previous)
            }
            boundOutput = null
            resetTemporalHistory(
                reason = PreviewTemporalFusionReason.OUTPUT_DETACHED,
                resetPolicy = true
            )
            ok = true
            latch.countDown()
        }
        return runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false) && ok
    }

    /** The surface the camera should target for preview. Valid only while [running]. */
    fun inputSurface(): Surface? = inputSurface.takeIf { running }

    fun isRunning(): Boolean = running

    fun isReleasing(): Boolean = releaseInProgress

    fun setFramePresentationEnabled(enabled: Boolean) {
        val changed = presentationEnabled != enabled
        presentationEnabled = enabled
        if (changed) {
            requestTemporalHistoryReset(
                if (enabled) PreviewTemporalFusionReason.PIXEL_ROUTE_CHANGED else
                    PreviewTemporalFusionReason.PRESENTATION_DISABLED
            )
        }
        if (changed && enabled) handler?.post { drawFrame() }
    }

    /**
     * Gives the encoder/import queue strict scheduling precedence while retaining a continuous,
     * colour-managed RGB10 preview. Only preview-local multi-sample effects are suspended; they are
     * restored without rebuilding Camera2 or EGL as soon as recording ends.
     */
    fun setRecordingPriorityActive(active: Boolean) {
        if (recordingPriorityActive == active) return
        recordingPriorityActive = active
        previewWorkload = PreviewRecordingWorkloadPolicy.resolve(active)
        if (active) niv99AwaitedMetadataTimestampNs.set(0L)
        requestTemporalHistoryReset(PreviewTemporalFusionReason.RECORDING_PRIORITY_CHANGED)
        handler?.post {
            if (active && niv99MetadataDeadlineScheduled) {
                handler?.removeCallbacks(niv99MetadataDeadlineRunnable)
                niv99MetadataDeadlineScheduled = false
            }
            if (active) recordingPriorityFramesPresented = 0L
            if (active) HotPathScheduling.applyDisplay() else HotPathScheduling.applyUrgentDisplay()
            Log.i(
                TAG,
                "PREVIEW_RECORDING_PRIORITY active=$active spatial=${previewWorkload.applySpatialEnhancement} " +
                    "temporal=${previewWorkload.applyTemporalFusion} colour=true rgb10=true"
            )
            drawFrame()
        }
    }

    private fun previewThreadPriority(): Int =
        if (recordingPriorityActive) HotPathScheduling.displayPriority() else
            HotPathScheduling.urgentDisplayPriority()

    fun setOnFramePresentedListener(listener: ((Long, Surface, Long) -> Unit)?) {
        framePresentedListener = listener
    }

    /** Camera-input readiness, including while this compositor is warming on its pbuffer. */
    fun setOnInputFrameListener(listener: ((Long) -> Unit)?) {
        inputFrameListener = listener
    }

    /** Installs the allocation-free exact-frame bridge used only by the SDR compositor thread. */
    fun setNiv99FrameProcessor(processor: Niv99PreviewFrameProcessor?) {
        val changed = niv99FrameProcessor !== processor
        niv99FrameProcessor = processor
        if (changed) requestTemporalHistoryReset(PreviewTemporalFusionReason.TUNING_UNAVAILABLE)
        if (processor == null) {
            handler?.post {
                niv99FrameTuning.clear()
                niv99ShaderTuning.clear()
                niv99PacketMatchedForLatchedFrame = false
            }
        }
    }

    /**
     * Wakes a pending exact-timestamp lookup after Camera2 publishes metadata. Notifications are
     * coalesced into one reusable Runnable, so the per-frame capture callback cannot grow a queue.
     */
    fun onNiv99MetadataAvailable(sensorTimestampNs: Long) {
        if (sensorTimestampNs <= 0L || !running || niv99FrameProcessor == null ||
            !previewWorkload.waitForExactMetadata
        ) return
        if (!Niv99PreviewMetadataWakePolicy.isExactWake(
                awaitedSensorTimestampNs = niv99AwaitedMetadataTimestampNs.get(),
                publishedSensorTimestampNs = sensorTimestampNs
            )
        ) return
        val hnd = handler ?: return
        if (niv99MetadataWakeQueued.compareAndSet(false, true) &&
            !hnd.post(niv99MetadataWakeRunnable)
        ) {
            niv99MetadataWakeQueued.set(false)
        }
    }

    /**
     * Reports the first EGL presentation failure in a successfully started lifecycle. The callback
     * runs on the render thread after this compositor has failed closed; consumers must marshal
     * Camera2 detachment and teardown to their owning thread.
     */
    fun setOnRuntimeFailureListener(listener: ((PreviewRuntimeFailure) -> Unit)?) {
        runtimeFailureListener = listener
    }

    fun lastRuntimeFailure(): PreviewRuntimeFailure? = lastRuntimeFailure

    /** Change the per-mode grade with no EGL rebuild. */
    fun setGrade(grade: Int) {
        val changed = gradeCode != grade
        if (!changed) return
        gradeCode = grade
        requestTemporalHistoryReset(PreviewTemporalFusionReason.GRADE_CHANGED)
        requestRender()
    }

    // ── STAGE 2: CPU-readback debayer present ─────────────────────────────────
    // A separate plain GL_TEXTURE_2D + passthrough program: the native RAW cinema-debayer produces a
    // display-ready sRGB RGBA8 monitor (ACEScg-tonemapped on the CPU-readback path), which we upload and
    // draw directly. Used INSTEAD of the camera external-OES path while a debayer frame is pending, so
    // the visible preview is the genuine sensor debayer (not the ISP-processed camera image).
    private var program2d = 0
    private var aPos2dLoc = 0
    private var aTex2dLoc = 0
    private var uTex2dLoc = 0
    private var uOrient2dLoc = 0
    private var uTexMatrix2dLoc = 0
    private var tex2dId = 0
    private var tex2dAllocatedWidth = 0
    private var tex2dAllocatedHeight = 0
    // Column-major 2x2 orientation matrix for the RAW debayer present (identity = today's V-flip only).
    // Written on the main thread (setDebayerOrientation), read on the render thread — published atomically
    // by swapping the whole array reference so the render thread never sees a torn matrix.
    @Volatile private var debayerOrient: FloatArray = floatArrayOf(1f, 0f, 0f, 1f)
    @Volatile private var debayerActive = false
    private val debayerStateLock = Any()
    private val debayerFrameMailbox = RawPreviewFrameMailbox()
    @Volatile private var debayerPresentationEpoch = 0L
    @Volatile private var committedDebayerEpoch = NO_DEBAYER_EPOCH
    // Master arm gate for the RAW debayer-monitor present. Co-located with the RAW callback lifecycle
    // (installRawVideoCallbacks arms; restoreRawVideoCallbacks disarms) so the 2D debayer path can ONLY
    // paint while the app is actually in RAW. Prevents a stale debayer frame from leaking its
    // (differently-oriented) image into another mode's preview after leaving RAW. Default false.
    @Volatile private var debayerPresentAllowed = false

    // ── Creative-LUT (WYSIWYG) stage ──────────────────────────────────────────
    // The SAME display-referred cube the record 3D-LUT stage grades with, R-fastest packed into a
    // compact tiled 2D atlas so a 65^3 cube remains below common 4096 texture limits under GLES2
    // (no sampler3D). Applied ONLY in the SDR_ENHANCE grade. Gate: PreviewColorParityFlag (default ON).
    private var lutTexId = 0
    private var lutCurrentSize = 0
    private var lutCurrentAtlasColumns = 1
    private var lutCurrentAtlasRows = 1
    private var uLutLoc = 0
    private var uLutSizeLoc = 0
    private var uLutAtlasGridLoc = 0
    private var uLutEnabledLoc = 0
    private var halfFloatLutTextureSupported = false
    private var maximumLutTextureSize = 0
    // data + size held in ONE reference so the render thread's read is atomic (no torn data/size pair).
    private class PendingLut(val data: FloatArray, val size: Int)
    @Volatile private var pendingLut: PendingLut? = null
    @Volatile private var lutDirty = false
    @Volatile private var lutUploaded = false
    @Volatile private var lastCreativeLutUploadEvidence = PreviewCreativeLutUploadEvidence(
        accepted = true,
        cubeSize = 0,
        atlasWidth = 1,
        atlasHeight = 1,
        precision = null
    )

    /**
     * Arm/disarm the RAW debayer-monitor present. Must be armed on RAW entry and disarmed on RAW exit
     * (co-located with the RAW callback lifecycle). Disarming also clears any pending frame + repaints so
     * the compositor returns to the camera-OES path immediately (no leaked debayer frame into other modes).
     */
    fun setDebayerPresentEnabled(on: Boolean) {
        synchronized(debayerStateLock) {
            if (!PreviewCompositorRedrawPolicy.debayerPresentationChanged(
                    current = debayerPresentAllowed,
                    requested = on
                )
            ) return
            debayerPresentAllowed = on
            if (!on) {
                debayerActive = false
                debayerFrameMailbox.clear()
                committedDebayerEpoch = NO_DEBAYER_EPOCH
            }
        }
        requestTemporalHistoryReset(PreviewTemporalFusionReason.PIXEL_ROUTE_CHANGED)
        if (!on) requestRender()
    }

    /** Disarm presentation immediately and retain the direct camera path during a profile/session swap. */
    fun beginDebayerPresentationEpoch(epoch: Long) {
        synchronized(debayerStateLock) {
            debayerPresentationEpoch = epoch
            committedDebayerEpoch = NO_DEBAYER_EPOCH
            debayerFrameMailbox.clear()
            debayerActive = false
        }
        requestTemporalHistoryReset(PreviewTemporalFusionReason.PIXEL_ROUTE_CHANGED)
        requestRender()
    }

    /**
     * Marks the matching Vulkan/session configuration ready. The layer remains on the camera path until
     * [presentDebayerRgba8] supplies the first valid frame from this exact epoch.
     */
    fun commitDebayerPresentationEpoch(epoch: Long): Boolean {
        return synchronized(debayerStateLock) {
            if (!debayerPresentAllowed || epoch != debayerPresentationEpoch) return@synchronized false
            committedDebayerEpoch = epoch
            true
        }
    }

    /**
     * Atomically accept one leased CPU-readback debayered RGBA8 frame. This method consumes [frame]
     * on every path: rejected/superseded frames are closed here, while accepted frames are closed by
     * the GL thread immediately after the synchronous texture upload completes.
     */
    fun presentDebayerRgba8(frame: RawPreviewFrameLease): Boolean {
        var routeChanged = false
        val accepted = synchronized(debayerStateLock) {
            val requiredBytes = frame.requiredByteCount
            if (
                !running ||
                !debayerPresentAllowed ||
                frame.presentationEpoch != debayerPresentationEpoch ||
                frame.presentationEpoch != committedDebayerEpoch ||
                frame.width <= 0 ||
                frame.height <= 0 ||
                frame.sensorTimestampNs <= 0L ||
                requiredBytes <= 0 ||
                !frame.pixels.isDirect ||
                frame.pixels.capacity() < requiredBytes
            ) {
                false
            } else {
                debayerFrameMailbox.publish(frame)
                routeChanged = !debayerActive
                debayerActive = true
                true
            }
        }
        if (!accepted) {
            frame.close()
            return false
        }
        if (routeChanged) {
            requestTemporalHistoryReset(PreviewTemporalFusionReason.DEBAYER_PIXEL_ROUTE)
        }
        requestRender()
        return true
    }

    /** Stop presenting the debayer buffer and return to the direct camera path. */
    fun clearDebayerPresent() {
        synchronized(debayerStateLock) {
            debayerActive = false
            debayerPresentAllowed = false
            committedDebayerEpoch = NO_DEBAYER_EPOCH
            debayerFrameMailbox.clear()
        }
        requestTemporalHistoryReset(PreviewTemporalFusionReason.PIXEL_ROUTE_CHANGED)
        requestRender()
    }

    /**
     * Set the RAW debayer-monitor display orientation (rotation + front-camera mirror), derived from the
     * app's geometry seams. (0, false) => identity => byte-identical to today's fixed V-flip. The 2x2
     * matrix is published by whole-array swap so the render thread never reads a torn value.
     */
    fun setDebayerOrientation(rotationDegrees: Int, mirrored: Boolean) {
        val r = ((rotationDegrees % 360) + 360) % 360
        val c: Float
        val s: Float
        when (r) {
            90 -> { c = 0f; s = 1f }
            180 -> { c = -1f; s = 0f }
            270 -> { c = 0f; s = -1f }
            else -> { c = 1f; s = 0f }
        }
        val mx = if (mirrored) -1f else 1f
        // Rotation R=[[c,-s],[s,c]] composed with mirror-on-x M=diag(mx,1); column-major R*M:
        //   col0 = R*(mx,0) = (c*mx, s*mx);  col1 = R*(0,1) = (-s, c).
        val requestedOrientation = floatArrayOf(c * mx, s * mx, -s, c)
        if (!PreviewCompositorRedrawPolicy.debayerOrientationChanged(
                current = debayerOrient,
                requested = requestedOrientation
            )
        ) return
        debayerOrient = requestedOrientation
        // This matrix is sampled only by the sensor-debayer route. Publishing it while that route is
        // dormant is sufficient; repainting the SDR camera texture would duplicate a visible frame.
        if (debayerPresentAllowed && debayerActive) {
            handler?.post { requestRender() }
        }
    }

    /**
     * Upload (or clear) the display-referred creative preview cube (R,G,B interleaved, R-fastest —
     * the exact packing of ColorScience.getPreviewLutFloatArray and the record 3D-LUT stage). null or
     * size<=1 clears it (SDR_ENHANCE becomes byte-identical). Safe to call from any thread.
     */
    fun setCreativeLut(data: FloatArray?, size: Int) {
        if (data == null || size <= 1) {
            if (!PreviewCompositorRedrawPolicy.creativeLutChanged(
                    currentData = pendingLut?.data,
                    currentSize = pendingLut?.size ?: 0,
                    requestedData = null,
                    requestedSize = 0
                ) && !lutUploaded
            ) return
            requestTemporalHistoryReset(PreviewTemporalFusionReason.LUT_CHANGED)
            pendingLut = null
            lutDirty = true
            handler?.post { requestRender() }
            return
        }
        val requiredFloats = PreviewLutAtlasPolicy.requiredFloatCount(size)
        var ownedData: FloatArray? = null
        val rejection = when {
            requiredFloats == null -> "invalid cube size=$size"
            data.size < requiredFloats ->
                "malformed cube size=$size floats=${data.size} required=$requiredFloats"
            else -> {
                val snapshot = data.copyOf(requiredFloats)
                ownedData = snapshot
                var invalidIndex = -1
                var index = 0
                while (index < requiredFloats) {
                    if (!snapshot[index].isFinite()) {
                        invalidIndex = index
                        break
                    }
                    index += 1
                }
                invalidIndex.takeIf { it >= 0 }?.let { "non-finite cube value index=$it" }
            }
        }
        if (rejection != null || requiredFloats == null) {
            requestTemporalHistoryReset(PreviewTemporalFusionReason.LUT_CHANGED)
            // This request represents the caller's newest colour identity. Keeping the previously
            // uploaded cube enabled after rejecting it would silently display the old look under the
            // new profile. Close the sampling gate immediately, then let the GL thread retire the
            // pending identity without ever exposing an unvalidated replacement.
            lutUploaded = false
            pendingLut = null
            lutDirty = true
            val detail = "Creative LUT admission rejected: ${rejection ?: "invalid payload"}"
            lastCreativeLutUploadEvidence = PreviewCreativeLutUploadEvidence(
                accepted = false,
                cubeSize = size,
                atlasWidth = 0,
                atlasHeight = 0,
                precision = null,
                failure = detail
            )
            Log.e(TAG, detail)
            handler?.post { requestRender() }
            return
        }
        val admittedData = checkNotNull(ownedData)
        val current = pendingLut
        if (!PreviewCompositorRedrawPolicy.creativeLutChanged(
                currentData = current?.data,
                currentSize = current?.size ?: 0,
                requestedData = admittedData,
                requestedSize = size
            ) && (lutDirty || lutUploaded)
        ) return
        requestTemporalHistoryReset(PreviewTemporalFusionReason.LUT_CHANGED)
        // Admission owns an immutable snapshot. Callers can reuse/mutate their working array without
        // racing the GL thread or changing a generation after its identity was published.
        pendingLut = PendingLut(admittedData, size)
        lutDirty = true
        handler?.post { requestRender() }
    }

    /** Latest reproducible upload/admission result for strict-preview diagnostics. */
    fun creativeLutUploadEvidence(): PreviewCreativeLutUploadEvidence =
        lastCreativeLutUploadEvidence

    /** Queues EGL teardown without waiting on the caller, which may be Android's main thread. */
    fun releaseAsync() {
        val state = synchronized(this) {
            if (releaseInProgress) return
            releaseInProgress = true
            running = false
            presentationEnabled = true
            boundOutput = null
            boundWidth = 0
            boundHeight = 0
            ReleaseState(handler, thread, CountDownLatch(1)).also { releaseLatch = it.latch }
        }
        synchronized(debayerStateLock) {
            debayerActive = false
            debayerPresentAllowed = false
            committedDebayerEpoch = NO_DEBAYER_EPOCH
            debayerFrameMailbox.clear()
        }
        val teardown = Runnable {
            try {
                if (state.handler != null) {
                    runCatching { destroyGl() }
                } else {
                    runCatching { inputSurface?.release() }
                    inputSurface = null
                }
            } finally {
                runCatching { state.thread?.quitSafely() }
                synchronized(this) {
                    if (handler === state.handler) handler = null
                    if (thread === state.thread) thread = null
                    releaseInProgress = false
                    if (releaseLatch === state.latch) releaseLatch = null
                }
                state.latch.countDown()
            }
        }
        if (state.handler?.post(teardown) != true) teardown.run()
    }

    /** Blocking form used only by background initialization/replacement work. */
    fun release() {
        val renderThread = synchronized(this) { thread }
        releaseAsync()
        val completion = synchronized(this) { releaseLatch }
        if (Thread.currentThread() !== renderThread) {
            runCatching { completion?.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    private data class ReleaseState(
        val handler: Handler?,
        val thread: HandlerThread?,
        val latch: CountDownLatch
    )

    // ── Render-thread only ────────────────────────────────────────────────────

    private fun initGl(output: Surface?, width: Int, height: Int): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            return false
        }
        val selectedConfig = configs[0] ?: return false
        if (!isStrictTenBitConfig(selectedConfig)) {
            Log.e(TAG, "Rejected non-10-bit EGL config for visible preview")
            return false
        }
        eglConfig = selectedConfig

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        // Unshared context (EGL_NO_CONTEXT) — full isolation from the record/encoder GL.
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false

        eglSurface = if (output != null) {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, output, surfaceAttribs, 0)
        } else {
            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        }
        if (eglSurface === EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        val extensionTokens = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toHashSet()
            .orEmpty()
        halfFloatLutTextureSupported = "GL_OES_texture_half_float" in extensionTokens
        temporalFp16ColorBufferSupported =
            "GL_EXT_color_buffer_half_float" in extensionTokens ||
                "GL_EXT_color_buffer_float" in extensionTokens
        val maximumFragmentTextureUnits = IntArray(1)
        GLES20.glGetIntegerv(
            GLES20.GL_MAX_TEXTURE_IMAGE_UNITS,
            maximumFragmentTextureUnits,
            0
        )
        temporalFp16TextureSupported = halfFloatLutTextureSupported &&
            temporalFp16ColorBufferSupported &&
            maximumFragmentTextureUnits[0] >= REQUIRED_FRAGMENT_TEXTURE_UNITS
        val maximumTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTextureSize, 0)
        maximumLutTextureSize = maximumTextureSize[0].coerceAtLeast(0)

        program = buildProgram() ?: return false
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uGradeLoc = GLES20.glGetUniformLocation(program, "uGrade")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        uNiv99ActiveLoc = GLES20.glGetUniformLocation(program, "uNiv99Active")
        uNiv99RgbCorrectionLoc = GLES20.glGetUniformLocation(program, "uNiv99RgbCorrection")
        uNiv99SpatialDenoiseLoc = GLES20.glGetUniformLocation(program, "uNiv99SpatialDenoise")
        uNiv99ChromaDenoiseLoc = GLES20.glGetUniformLocation(program, "uNiv99ChromaDenoise")
        uNiv99SharpeningCeilingLoc = GLES20.glGetUniformLocation(program, "uNiv99SharpeningCeiling")
        uNiv99ConfidenceLoc = GLES20.glGetUniformLocation(program, "uNiv99Confidence")
        uRecordingPriorityLoc = GLES20.glGetUniformLocation(program, "uRecordingPriority")
        if (uNiv99ActiveLoc < 0 || uNiv99RgbCorrectionLoc < 0 ||
            uNiv99SpatialDenoiseLoc < 0 || uNiv99ChromaDenoiseLoc < 0 ||
            uNiv99SharpeningCeilingLoc < 0 || uNiv99ConfidenceLoc < 0 ||
            uRecordingPriorityLoc < 0
        ) {
            Log.e(TAG, "Strict NIV99 preview shader ABI is incomplete")
            return false
        }
        uHistory0Loc = GLES20.glGetUniformLocation(program, "uHistory0")
        uHistory1Loc = GLES20.glGetUniformLocation(program, "uHistory1")
        uTemporalHistoryCountLoc = GLES20.glGetUniformLocation(program, "uTemporalHistoryCount")
        uTemporalLumaStrengthLoc = GLES20.glGetUniformLocation(program, "uTemporalLumaStrength")
        uTemporalChromaStrengthLoc = GLES20.glGetUniformLocation(program, "uTemporalChromaStrength")
        uTemporalNoisePressureLoc = GLES20.glGetUniformLocation(program, "uTemporalNoisePressure")
        uOutputDitherEnabledLoc = GLES20.glGetUniformLocation(program, "uOutputDitherEnabled")
        temporalShaderAbiAvailable = uHistory0Loc >= 0 && uHistory1Loc >= 0 &&
            uTemporalHistoryCountLoc >= 0 && uTemporalLumaStrengthLoc >= 0 &&
            uTemporalChromaStrengthLoc >= 0 && uTemporalNoisePressureLoc >= 0 &&
            uOutputDitherEnabledLoc >= 0
        if (temporalFp16TextureSupported) {
            temporalPresentProgram = buildTemporalPresentProgram() ?: 0
            if (temporalPresentProgram != 0) {
                temporalPresentPositionLoc = GLES20.glGetAttribLocation(
                    temporalPresentProgram,
                    "aPosition"
                )
                temporalPresentTexCoordLoc = GLES20.glGetAttribLocation(
                    temporalPresentProgram,
                    "aTexCoord"
                )
                temporalPresentTextureLoc = GLES20.glGetUniformLocation(
                    temporalPresentProgram,
                    "uTexture"
                )
                temporalShaderAbiAvailable = temporalShaderAbiAvailable &&
                    temporalPresentPositionLoc >= 0 && temporalPresentTexCoordLoc >= 0 &&
                    temporalPresentTextureLoc >= 0
            } else {
                temporalShaderAbiAvailable = false
            }
        }
        temporalFp16TextureSupported = temporalFp16TextureSupported && temporalShaderAbiAvailable
        if (!temporalShaderAbiAvailable) {
            Log.w(TAG, "NIV99 temporal shader ABI unavailable; retaining single-frame preview")
        }
        if (!initializeTemporalSamplerSentinel()) {
            Log.e(TAG, "Unable to create complete temporal sampler sentinel")
            return false
        }
        // Creative-LUT (WYSIWYG) stage uniforms + its 2D-packed cube texture.
        uLutLoc = GLES20.glGetUniformLocation(program, "uLut")
        uLutSizeLoc = GLES20.glGetUniformLocation(program, "uLutSize")
        uLutAtlasGridLoc = GLES20.glGetUniformLocation(program, "uLutAtlasGrid")
        uLutEnabledLoc = GLES20.glGetUniformLocation(program, "uLutEnabled")
        val lutTex = IntArray(1)
        GLES20.glGenTextures(1, lutTex, 0)
        lutTexId = lutTex[0]
        // Keep unit-1's LUT texture COMPLETE from the start (mirrors the record path's default 1x1
        // sampler) so the always-bound uLut sampler is never an incomplete sampler on the default-OFF path.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // This 1x1 RGBA8 sentinel is never sampled while the LUT gate is OFF. Strict creative LUTs
        // themselves are admitted only as RGBA16F below; the sentinel merely keeps the sampler complete.
        val lutDefault = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        lutDefault.put(0).put(0).put(0).put(0xff.toByte())
        lutDefault.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lutDefault
        )
        // An EGL-context rebuild destroys every GL texture, but [pendingLut] deliberately survives so
        // the requested profile identity survives a Surface/lifecycle restart. Re-arm its upload for
        // this new context; otherwise lutDirty remains false after the first successful upload and the
        // rebuilt compositor silently presents enhancement-only SDR (the observed flat/colourless
        // preview after a mode/lifecycle transition) until an unrelated setting changes the LUT again.
        if (pendingLut != null) {
            lutDirty = true
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )

        // STAGE 2 debayer present: a plain 2D program + texture for the CPU-readback RGBA8 monitor.
        // Best-effort — if it fails to build, the debayer present is simply unavailable (camera path unaffected).
        program2d = build2dProgram() ?: 0
        if (program2d != 0) {
            aPos2dLoc = GLES20.glGetAttribLocation(program2d, "aPosition")
            aTex2dLoc = GLES20.glGetAttribLocation(program2d, "aTexCoord")
            uTex2dLoc = GLES20.glGetUniformLocation(program2d, "uTexture")
            uOrient2dLoc = GLES20.glGetUniformLocation(program2d, "uOrient2d")
            uTexMatrix2dLoc = GLES20.glGetUniformLocation(program2d, "uTexMatrix")
            val tex2 = IntArray(1)
            GLES20.glGenTextures(1, tex2, 0)
            tex2dId = tex2[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            frameAvailable = true
            requestRender()
        }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
        return true
    }

    /** EGL channel requests are minima; verify the driver did not return an 8-bit config. */
    private fun isStrictTenBitConfig(config: EGLConfig): Boolean {
        fun channelSize(attribute: Int): Int {
            val value = IntArray(1)
            return if (EGL14.eglGetConfigAttrib(eglDisplay, config, attribute, value, 0)) {
                value[0]
            } else {
                0
            }
        }
        return channelSize(EGL14.EGL_RED_SIZE) >= 10 &&
            channelSize(EGL14.EGL_GREEN_SIZE) >= 10 &&
            channelSize(EGL14.EGL_BLUE_SIZE) >= 10 &&
            channelSize(EGL14.EGL_ALPHA_SIZE) >= 2
    }

    private fun attachOutput(output: Surface, width: Int, height: Int): Boolean {
        val hnd = handler ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            val config = eglConfig
            if (config == null || eglDisplay === EGL14.EGL_NO_DISPLAY ||
                eglContext === EGL14.EGL_NO_CONTEXT
            ) {
                latch.countDown()
                return@post
            }
            val attrs = intArrayOf(EGL14.EGL_NONE)
            val window = EGL14.eglCreateWindowSurface(eglDisplay, config, output, attrs, 0)
            if (window === EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "PreviewGlCompositor warm output attach failed: eglCreateWindowSurface")
                latch.countDown()
                return@post
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)) {
                EGL14.eglDestroySurface(eglDisplay, window)
                Log.w(TAG, "PreviewGlCompositor warm output attach failed: eglMakeCurrent")
                latch.countDown()
                return@post
            }
            val previous = eglSurface
            eglSurface = window
            if (previous !== EGL14.EGL_NO_SURFACE && previous !== window) {
                EGL14.eglDestroySurface(eglDisplay, previous)
            }
            boundOutput = output
            boundWidth = width
            boundHeight = height
            resetTemporalHistory(
                reason = PreviewTemporalFusionReason.OUTPUT_ATTACHED,
                resetPolicy = true
            )
            ok = true
            drawFrame()
            latch.countDown()
        }
        val completed = runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (completed && ok) {
            Log.i(TAG, "PreviewGlCompositor warm output attached ${width}x${height}")
        }
        return completed && ok
    }

    private fun requestRender() {
        if (!running || releaseInProgress) return
        val hnd = handler ?: return
        if (renderWakeQueued.compareAndSet(false, true) && !hnd.post(renderRunnable)) {
            renderWakeQueued.set(false)
        }
    }

    private fun drawFrame() {
        if (!running || releaseInProgress) return
        val st = surfaceTexture ?: return
        if (eglSurface === EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            val eglError = EGL14.eglGetError()
            reportRuntimeFailure(
                operation = PreviewRuntimeOperation.EGL_MAKE_CURRENT,
                eglError = eglError,
                detail = "SDR child eglMakeCurrent failed error=0x${eglError.toString(16)}"
            )
            return
        }
        consumeRequestedTemporalReset()
        if (!presentationEnabled) {
            if (frameAvailable) {
                var drainedSensorTimestampNs = 0L
                runCatching {
                    st.updateTexImage()
                    st.getTransformMatrix(texMatrix)
                    drainedSensorTimestampNs = st.timestamp
                }.onFailure { return }
                frameAvailable = false
                validInputFrameLatched = drainedSensorTimestampNs > 0L
                if (drainedSensorTimestampNs > 0L) {
                    beginNiv99LatchedFrame(drainedSensorTimestampNs)
                    inputFrameListener?.invoke(drainedSensorTimestampNs)
                }
            }
            return
        }
        // STAGE 2: while a debayer frame is pending, present the genuine sensor debayer (2D RGBA8) and
        // bypass the camera external-OES path entirely.
        if (
            debayerActive &&
            debayerPresentAllowed &&
            committedDebayerEpoch == debayerPresentationEpoch &&
            program2d != 0 &&
            tex2dId != 0
        ) {
            val frame = debayerFrameMailbox.take()
            if (frame == null) {
                // The texture already contains the last fully uploaded RAW frame. Hold that proven
                // window buffer until a new immutable frame arrives; never flash the ISP/OES route.
                return
            }
            if (drawDebayer2d(frame)) return
            // Upload/epoch validation failed closed. Continue below so the newest camera-OES frame
            // can replace the potentially stale RAW window buffer immediately.
        }
        if (frameAvailable && !mustHoldLatchedFrameForNiv99()) {
            var newlyLatchedSensorTimestampNs = 0L
            runCatching {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                newlyLatchedSensorTimestampNs = st.timestamp
            }.onFailure { return }
            frameAvailable = false
            validInputFrameLatched = newlyLatchedSensorTimestampNs > 0L
            if (newlyLatchedSensorTimestampNs > 0L) {
                beginNiv99LatchedFrame(newlyLatchedSensorTimestampNs)
                inputFrameListener?.invoke(newlyLatchedSensorTimestampNs)
            }
        }
        // Setters can request a redraw during EGL preparation. Never turn the uninitialized external
        // texture into a synthetic child buffer; the visible router must receive real camera pixels.
        if (!validInputFrameLatched) return
        // Camera2 result and SurfaceTexture callbacks can arrive in either order. Hold only this exact
        // latched texture for a short, asynchronous window; never block the GL thread and never borrow
        // tuning from a neighbouring timestamp.
        if (!prepareNiv99TuningForLatchedFrame()) return

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceWidthScratch, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceHeightScratch, 0)
        val surfaceWidth = surfaceWidthScratch[0].coerceAtLeast(1)
        val surfaceHeight = surfaceHeightScratch[0].coerceAtLeast(1)
        prepareTemporalFusionForFrame(surfaceWidth, surfaceHeight)
        temporalRenderingToFp16ForDraw = bindTemporalRenderTargetForDraw()
        GLES20.glViewport(
            0,
            0,
            surfaceWidth,
            surfaceHeight
        )

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        uploadCreativeLutIfDirty()

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1i(uGradeLoc, gradeCode)
        // Source-stream texel size (SurfaceTexture default buffer size == boundWidth/Height) for the
        // SDR_ENHANCE neighbour taps. Harmless for the other grades (their fragment path ignores it).
        GLES20.glUniform2f(
            uTexelSizeLoc,
            1f / boundWidth.coerceAtLeast(1),
            1f / boundHeight.coerceAtLeast(1)
        )
        GLES20.glUniform1f(uNiv99ActiveLoc, niv99ShaderTuning.active)
        GLES20.glUniform3f(
            uNiv99RgbCorrectionLoc,
            niv99ShaderTuning.redCorrection,
            niv99ShaderTuning.greenCorrection,
            niv99ShaderTuning.blueCorrection
        )
        GLES20.glUniform1f(uNiv99SpatialDenoiseLoc, niv99ShaderTuning.spatialDenoise)
        GLES20.glUniform1f(uNiv99ChromaDenoiseLoc, niv99ShaderTuning.chromaDenoise)
        GLES20.glUniform1f(
            uNiv99SharpeningCeilingLoc,
            niv99ShaderTuning.sharpeningCeiling
        )
        GLES20.glUniform1f(uNiv99ConfidenceLoc, niv99ShaderTuning.confidence)
        GLES20.glUniform1f(uRecordingPriorityLoc, if (recordingPriorityActive) 1f else 0f)
        GLES20.glUniform1f(
            uOutputDitherEnabledLoc,
            if (temporalRenderingToFp16ForDraw) 0f else 1f
        )
        // Creative-LUT (WYSIWYG): uLut ALWAYS references texture unit 1 (a complete texture), so it can
        // never alias the external-OES uTexture on unit 0 — GLES2 §2.10.4 forbids two different-typed
        // samplers referencing one unit (undefined even when uLut is dynamically unsampled). uLutEnabled
        // gates the ACTUAL sampling; default 0 keeps the enhance-only path byte-identical to today.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uLutLoc, 1)
        GLES20.glUniform1f(uLutSizeLoc, lutCurrentSize.coerceAtLeast(1).toFloat())
        GLES20.glUniform2f(
            uLutAtlasGridLoc,
            lutCurrentAtlasColumns.coerceAtLeast(1).toFloat(),
            lutCurrentAtlasRows.coerceAtLeast(1).toFloat()
        )
        val lutOn = lutUploaded && gradeCode == GRADE_SDR_ENHANCE
        GLES20.glUniform1i(
            uLutEnabledLoc,
            if (lutOn && previewWorkload.applyCreativeLut) 1 else 0
        )
        bindTemporalHistoryForDraw()

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        temporalHistoryCapturedForDraw = false
        if (temporalRenderingToFp16ForDraw) {
            temporalHistoryCapturedForDraw = presentTemporalTargetToWindow(
                surfaceWidth,
                surfaceHeight
            )
            if (!temporalHistoryCapturedForDraw) {
                drawSingleFrameFallbackToWindow(surfaceWidth, surfaceHeight)
            }
        }

        val firstPresentationTimestampNs = if (!latchedFramePresented) {
            latchedSensorTimestampNs
        } else {
            0L
        }
        if (firstPresentationTimestampNs > 0L) {
            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                firstPresentationTimestampNs
            )
        }
        val presented = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!presented) {
            val eglError = EGL14.eglGetError()
            reportRuntimeFailure(
                operation = PreviewRuntimeOperation.EGL_SWAP_BUFFERS,
                eglError = eglError,
                detail = "SDR child eglSwapBuffers failed error=0x${eglError.toString(16)} " +
                    "timestampNs=$latchedSensorTimestampNs"
            )
            return
        }
        val presentationTimestampNs = SystemClock.elapsedRealtimeNanos()
        latchedFramePresented = true
        commitTemporalHistoryAfterSwap()
        reportNiv99PixelsPresentedAfterSwap()
        reportTemporalFusionAfterSwap()
        if (recordingPriorityActive) {
            val presentedInPriority = ++recordingPriorityFramesPresented
            if (presentedInPriority == 1L ||
                presentedInPriority % RECORDING_PRIORITY_LOG_INTERVAL_FRAMES == 0L
            ) {
                Log.i(
                    TAG,
                    "PREVIEW_RECORDING_PRIORITY_FRAME total=$presentedInPriority " +
                        "sensorTimestampNs=$latchedSensorTimestampNs spatial=false temporal=false " +
                        "colour=true rgb10=true"
                )
            }
        }
        val presentedOutput = boundOutput
        if (presentedOutput != null && firstPresentationTimestampNs > 0L) {
            framePresentedListener?.invoke(
                firstPresentationTimestampNs,
                presentedOutput,
                presentationTimestampNs
            )
        }
        if (frameAvailable) requestRender()
    }

    private fun requestTemporalHistoryReset(reason: PreviewTemporalFusionReason) {
        if (reason != PreviewTemporalFusionReason.NONE) temporalResetRequest.set(reason)
    }

    /** Applies cross-thread reset requests only after this EGL context is current. */
    private fun consumeRequestedTemporalReset() {
        val reason = temporalResetRequest.getAndSet(PreviewTemporalFusionReason.NONE)
        if (reason != PreviewTemporalFusionReason.NONE) {
            resetTemporalHistory(reason = reason, resetPolicy = true)
        }
    }

    private fun prepareTemporalFusionForFrame(surfaceWidth: Int, surfaceHeight: Int) {
        temporalStageExecutedForDraw = false
        temporalHistoryCapturedForDraw = false
        if (!previewWorkload.applyTemporalFusion) {
            temporalFusionDecision.setBypass(
                reason = PreviewTemporalFusionReason.RECORDING_PRIORITY,
                resetHistory = false,
                acceptCurrentFrameIntoHistory = false
            )
            reportTemporalBypassIfChanged(temporalFusionDecision.reason)
            return
        }
        val historyResourcesAvailable = ensureTemporalHistory(surfaceWidth, surfaceHeight)
        temporalFusionInput.sensorTimestampNs = latchedSensorTimestampNs
        temporalFusionInput.enhanceGradeActive = gradeCode == GRADE_SDR_ENHANCE
        temporalFusionInput.fp16TextureSupported = temporalFp16TextureSupported
        temporalFusionInput.historyResourcesAvailable = historyResourcesAvailable
        temporalFusionInput.outputAttached = boundOutput != null
        temporalFusionInput.presentationEnabled = presentationEnabled
        temporalFusionInput.debayerPixelRoute = debayerActive && debayerPresentAllowed
        temporalFusionInput.tuningActive = niv99PacketMatchedForLatchedFrame &&
            niv99ShaderTuning.active > 0f
        temporalFusionInput.tuningConfidence = niv99ShaderTuning.confidence
        temporalFusionInput.spatialDenoise = niv99ShaderTuning.spatialDenoise
        temporalFusionInput.chromaDenoise = niv99ShaderTuning.chromaDenoise
        temporalFusionInput.calibrationRevision = niv99FrameTuning.calibrationRevision
        temporalFusionInput.redCorrection = niv99ShaderTuning.redCorrection
        temporalFusionInput.greenCorrection = niv99ShaderTuning.greenCorrection
        temporalFusionInput.blueCorrection = niv99ShaderTuning.blueCorrection
        temporalFusionInput.availableHistoryFrames = temporalHistoryValidCount
        temporalFusionPolicy.resolve(temporalFusionInput, temporalFusionDecision)
        if (temporalFusionDecision.resetHistory) {
            // The policy already retained this frame as the new timeline seed. Reset only GPU slots;
            // resetting policy state here would incorrectly turn every frame into another first frame.
            resetTemporalHistory(
                reason = temporalFusionDecision.reason,
                resetPolicy = false
            )
        }
        temporalStageExecutedForDraw = temporalFusionDecision.enabled
        reportTemporalBypassIfChanged(temporalFusionDecision.reason)
    }

    /**
     * Binds complete 2D samplers on units 2/3 even when fusion is disabled. This avoids the GLES2
     * undefined sampler-alias case while guaranteeing that the RGBA8 sentinel is never admitted as
     * history: uTemporalHistoryCount remains zero unless the strict FP16 history pool is available.
     */
    private fun bindTemporalHistoryForDraw() {
        val latestIndex = floorMod(
            temporalHistoryWriteIndex - 1,
            TEMPORAL_TEXTURE_POOL_SIZE
        )
        val olderIndex = floorMod(
            temporalHistoryWriteIndex - 2,
            TEMPORAL_TEXTURE_POOL_SIZE
        )
        val latestTexture = if (
            temporalFusionDecision.enabled && temporalHistoryValidCount >= 1
        ) temporalHistoryTextureIds[latestIndex] else temporalSentinelTextureId
        val olderTexture = if (
            temporalFusionDecision.enabled && temporalHistoryValidCount >= 2
        ) temporalHistoryTextureIds[olderIndex] else temporalSentinelTextureId

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, latestTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, olderTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uHistory0Loc, 2)
        GLES20.glUniform1i(uHistory1Loc, 3)
        GLES20.glUniform1f(
            uTemporalHistoryCountLoc,
            if (temporalFusionDecision.enabled) {
                temporalFusionDecision.historyFrameCount.toFloat()
            } else {
                0f
            }
        )
        GLES20.glUniform1f(uTemporalLumaStrengthLoc, temporalFusionDecision.lumaStrength)
        GLES20.glUniform1f(uTemporalChromaStrengthLoc, temporalFusionDecision.chromaStrength)
        GLES20.glUniform1f(uTemporalNoisePressureLoc, temporalFusionDecision.noisePressure)
    }

    /** Selects a third FP16 texture as the current render target, distinct from both histories. */
    private fun bindTemporalRenderTargetForDraw(): Boolean {
        if (!temporalFusionDecision.acceptCurrentFrameIntoHistory ||
            !temporalHistoryAllocated || temporalRuntimeFailed || temporalFramebufferId == 0
        ) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        val targetTexture = temporalHistoryTextureIds[temporalHistoryWriteIndex]
        if (targetTexture == 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return false
        }
        clearGlErrors()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, temporalFramebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            targetTexture,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        val glError = GLES20.glGetError()
        if (status == GLES20.GL_FRAMEBUFFER_COMPLETE && glError == GLES20.GL_NO_ERROR) {
            return true
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        temporalResourceFailures += 1L
        disableTemporalHistoryAfterRuntimeFailure(
            glError = glError,
            detail = "framebuffer status=0x${status.toString(16)}"
        )
        temporalFusionDecision.setBypass(
            reason = PreviewTemporalFusionReason.RESOURCE_UNAVAILABLE,
            resetHistory = true,
            acceptCurrentFrameIntoHistory = false
        )
        temporalStageExecutedForDraw = false
        return false
    }

    /** Presents the current FP16 result through a tiny RGB10 dither pass; no CPU copy is involved. */
    private fun presentTemporalTargetToWindow(surfaceWidth: Int, surfaceHeight: Int): Boolean {
        val sourceTexture = temporalHistoryTextureIds[temporalHistoryWriteIndex]
        if (temporalPresentProgram == 0 || sourceTexture == 0) return false
        clearGlErrors()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(temporalPresentProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLES20.glUniform1i(temporalPresentTextureLoc, 0)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(
            temporalPresentPositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(temporalPresentPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(
            temporalPresentTexCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(temporalPresentTexCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(temporalPresentPositionLoc)
        GLES20.glDisableVertexAttribArray(temporalPresentTexCoordLoc)
        val glError = GLES20.glGetError()
        if (glError == GLES20.GL_NO_ERROR) return true

        temporalResourceFailures += 1L
        disableTemporalHistoryAfterRuntimeFailure(
            glError = glError,
            detail = "FP16 presentation pass failed"
        )
        temporalStageExecutedForDraw = false
        return false
    }

    /** Re-renders current OES pixels directly if the optional FP16 presentation pass fails. */
    private fun drawSingleFrameFallbackToWindow(surfaceWidth: Int, surfaceHeight: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTemporalHistoryCountLoc, 0f)
        GLES20.glUniform1f(uTemporalLumaStrengthLoc, 0f)
        GLES20.glUniform1f(uTemporalChromaStrengthLoc, 0f)
        GLES20.glUniform1f(uTemporalNoisePressureLoc, 0f)
        GLES20.glUniform1f(uOutputDitherEnabledLoc, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temporalSentinelTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temporalSentinelTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    /** Commits a history frame only after its corresponding RGB10 window buffer was swapped. */
    private fun commitTemporalHistoryAfterSwap() {
        if (!temporalHistoryCapturedForDraw || !temporalHistoryAllocated) return
        temporalHistoryWriteIndex =
            (temporalHistoryWriteIndex + 1) % TEMPORAL_TEXTURE_POOL_SIZE
        temporalHistoryValidCount =
            (temporalHistoryValidCount + 1).coerceAtMost(TEMPORAL_HISTORY_CAPACITY)
        val committed = ++temporalHistoryCommits
        if (committed == 1L || committed % TEMPORAL_LOG_INTERVAL_FRAMES == 0L) {
            Log.i(
                TAG,
                "NIV99_TEMPORAL_HISTORY_COMMITTED total=$committed " +
                    "sensorTimestampNs=$latchedSensorTimestampNs " +
                    "size=${temporalHistoryWidth}x$temporalHistoryHeight precision=RGBA16F " +
                    "cpuReadback=false history=$temporalHistoryValidCount"
            )
        }
    }

    private fun reportTemporalFusionAfterSwap() {
        if (!temporalStageExecutedForDraw) return
        val presented = ++temporalFramesPresented
        lastTemporalBypassLogged = null
        if (presented == 1L || presented % TEMPORAL_LOG_INTERVAL_FRAMES == 0L) {
            Log.i(
                TAG,
                "NIV99_TEMPORAL_STAGE_PRESENTED total=$presented " +
                    "sensorTimestampNs=$latchedSensorTimestampNs " +
                    "history=${temporalFusionDecision.historyFrameCount} precision=RGBA16F " +
                    "lumaStrength=${temporalFusionDecision.lumaStrength} " +
                    "chromaStrength=${temporalFusionDecision.chromaStrength} " +
                    "historyCaptured=$temporalHistoryCapturedForDraw " +
                    "perPixelEffectMeasured=false residualGate=enabled"
            )
        }
    }

    private fun reportTemporalBypassIfChanged(reason: PreviewTemporalFusionReason) {
        if (reason == PreviewTemporalFusionReason.NONE ||
            reason == PreviewTemporalFusionReason.SAME_FRAME_REDRAW ||
            lastTemporalBypassLogged == reason
        ) return
        lastTemporalBypassLogged = reason
        Log.i(
            TAG,
            "NIV99_TEMPORAL_STAGE_BYPASS reason=$reason " +
                "fp16Supported=$temporalFp16TextureSupported " +
                "allocated=$temporalHistoryAllocated history=$temporalHistoryValidCount"
        )
    }

    private fun ensureTemporalHistory(surfaceWidth: Int, surfaceHeight: Int): Boolean {
        if (!temporalFp16TextureSupported || temporalRuntimeFailed || boundOutput == null ||
            surfaceWidth <= 1 || surfaceHeight <= 1 ||
            surfaceWidth > maximumLutTextureSize || surfaceHeight > maximumLutTextureSize
        ) return false
        if (temporalHistoryAllocated && temporalHistoryWidth == surfaceWidth &&
            temporalHistoryHeight == surfaceHeight
        ) return true

        deleteTemporalHistoryTextures()
        clearGlErrors()
        val candidates = IntArray(TEMPORAL_TEXTURE_POOL_SIZE)
        GLES20.glGenTextures(TEMPORAL_TEXTURE_POOL_SIZE, candidates, 0)
        var allocationError = GLES20.glGetError()
        if (allocationError == GLES20.GL_NO_ERROR && candidates.all { it != 0 }) {
            for (index in candidates.indices) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, candidates[index])
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    surfaceWidth,
                    surfaceHeight,
                    0,
                    GLES20.GL_RGBA,
                    GL_HALF_FLOAT_OES,
                    null
                )
                allocationError = GLES20.glGetError()
                if (allocationError != GLES20.GL_NO_ERROR) break
            }
        }
        val framebuffer = IntArray(1)
        if (allocationError == GLES20.GL_NO_ERROR && candidates.all { it != 0 }) {
            GLES20.glGenFramebuffers(1, framebuffer, 0)
            allocationError = GLES20.glGetError()
        }
        if (allocationError == GLES20.GL_NO_ERROR && framebuffer[0] != 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
            for (texture in candidates) {
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    texture,
                    0
                )
                val framebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                allocationError = GLES20.glGetError()
                if (framebufferStatus != GLES20.GL_FRAMEBUFFER_COMPLETE ||
                    allocationError != GLES20.GL_NO_ERROR
                ) {
                    if (allocationError == GLES20.GL_NO_ERROR) {
                        allocationError = GL_FRAMEBUFFER_INCOMPLETE_SENTINEL
                    }
                    break
                }
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (allocationError != GLES20.GL_NO_ERROR || candidates.any { it == 0 } ||
            framebuffer[0] == 0
        ) {
            if (candidates.any { it != 0 }) {
                GLES20.glDeleteTextures(TEMPORAL_TEXTURE_POOL_SIZE, candidates, 0)
            }
            if (framebuffer[0] != 0) {
                GLES20.glDeleteFramebuffers(1, framebuffer, 0)
            }
            temporalRuntimeFailed = true
            Log.w(
                TAG,
                "NIV99_TEMPORAL_STAGE_BYPASS reason=FP16_RENDER_TARGET_FAILED " +
                    "glError=0x${allocationError.toString(16)} size=${surfaceWidth}x$surfaceHeight"
            )
            return false
        }

        candidates.copyInto(temporalHistoryTextureIds)
        temporalFramebufferId = framebuffer[0]
        temporalHistoryWidth = surfaceWidth
        temporalHistoryHeight = surfaceHeight
        temporalHistoryAllocated = true
        resetTemporalHistory(
            reason = PreviewTemporalFusionReason.CONTEXT_REBUILT,
            resetPolicy = true
        )
        Log.i(
            TAG,
            "NIV99_TEMPORAL_HISTORY_READY size=${surfaceWidth}x$surfaceHeight " +
                "historySlots=$TEMPORAL_HISTORY_CAPACITY pool=$TEMPORAL_TEXTURE_POOL_SIZE " +
                "precision=RGBA16F renderTarget=true cpuReadback=false"
        )
        return true
    }

    private fun initializeTemporalSamplerSentinel(): Boolean {
        clearGlErrors()
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        if (texture[0] == 0) return false
        temporalSentinelTextureId = texture[0]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temporalSentinelTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val black = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        black.put(0).put(0).put(0).put(0xff.toByte()).position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            1,
            1,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            black
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temporalSentinelTextureId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val glError = GLES20.glGetError()
        if (glError == GLES20.GL_NO_ERROR) return true
        GLES20.glDeleteTextures(1, texture, 0)
        temporalSentinelTextureId = 0
        return false
    }

    private fun disableTemporalHistoryAfterRuntimeFailure(glError: Int, detail: String) {
        deleteTemporalHistoryTextures()
        temporalRuntimeFailed = true
        temporalFusionPolicy.reset()
        lastTemporalBypassLogged = PreviewTemporalFusionReason.FP16_RENDER_TARGET_FAILED
        Log.w(
            TAG,
            "NIV99_TEMPORAL_STAGE_BYPASS reason=FP16_RENDER_TARGET_FAILED " +
                "glError=0x${glError.toString(16)} failures=$temporalResourceFailures " +
                "detail=$detail fallback=single_frame"
        )
    }

    private fun resetTemporalHistory(
        reason: PreviewTemporalFusionReason,
        resetPolicy: Boolean
    ) {
        val hadHistory = temporalHistoryValidCount > 0
        temporalHistoryWriteIndex = 0
        temporalHistoryValidCount = 0
        temporalStageExecutedForDraw = false
        temporalHistoryCapturedForDraw = false
        temporalRenderingToFp16ForDraw = false
        if (resetPolicy) temporalFusionPolicy.reset()
        if (hadHistory) {
            Log.i(TAG, "NIV99_TEMPORAL_HISTORY_RESET reason=$reason")
        }
    }

    private fun deleteTemporalHistoryTextures() {
        if (temporalHistoryTextureIds.any { it != 0 }) {
            GLES20.glDeleteTextures(
                TEMPORAL_TEXTURE_POOL_SIZE,
                temporalHistoryTextureIds,
                0
            )
        }
        if (temporalFramebufferId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(temporalFramebufferId), 0)
        }
        temporalHistoryTextureIds.fill(0)
        temporalFramebufferId = 0
        temporalHistoryAllocated = false
        temporalHistoryWidth = 0
        temporalHistoryHeight = 0
        temporalHistoryWriteIndex = 0
        temporalHistoryValidCount = 0
    }

    private fun floorMod(value: Int, modulus: Int): Int {
        val remainder = value % modulus
        return if (remainder >= 0) remainder else remainder + modulus
    }

    private fun mustHoldLatchedFrameForNiv99(): Boolean =
        validInputFrameLatched &&
            !niv99PacketMatchedForLatchedFrame &&
            previewWorkload.waitForExactMetadata &&
            niv99FrameProcessor?.expectsPixelTuning() == true

    private fun beginNiv99LatchedFrame(sensorTimestampNs: Long) {
        latchedSensorTimestampNs = sensorTimestampNs
        latchedFramePresented = false
        niv99PacketMatchedForLatchedFrame = false
        niv99PixelReportedForLatchedFrame = false
        niv99MissReportedForLatchedFrame = false
        niv99LookupReportedForLatchedFrame = false
        niv99LookupAcceptedForLatchedFrame = false
        niv99TuningSourceForLatchedFrame = null
        niv99FrameTuning.clear()
        niv99ShaderTuning.clear()
        niv99ExpectedContinuity.clear()
        val processor = niv99FrameProcessor
        val expectsPixelTuning = processor?.expectsPixelTuning() == true
        niv99ExpectedContinuityValid = if (processor != null && expectsPixelTuning) {
            processor.copyCurrentContinuity(niv99ExpectedContinuity)
        } else {
            false
        }
        val requestedWaitNs = processor
            ?.takeIf { expectsPixelTuning }
            ?.exactFrameMetadataWaitNs()
            ?.coerceIn(
                Niv99PreviewFrameJoinPolicy.MINIMUM_WAIT_NS,
                Niv99PreviewFrameJoinPolicy.MAXIMUM_WAIT_NS
            )
            ?.takeIf { previewWorkload.waitForExactMetadata }
            ?: 0L
        niv99MetadataWaitDeadlineNs = SystemClock.elapsedRealtimeNanos() + requestedWaitNs
        niv99AwaitedMetadataTimestampNs.set(
            if (expectsPixelTuning && previewWorkload.waitForExactMetadata) sensorTimestampNs else 0L
        )
        if (niv99MetadataDeadlineScheduled) {
            handler?.removeCallbacks(niv99MetadataDeadlineRunnable)
            niv99MetadataDeadlineScheduled = false
        }
    }

    /** Returns false only while the exact-frame metadata wait remains inside its bounded window. */
    private fun prepareNiv99TuningForLatchedFrame(): Boolean {
        if (niv99PacketMatchedForLatchedFrame) return true
        val processor = niv99FrameProcessor
        if (processor == null || !processor.expectsPixelTuning()) {
            niv99FrameTuning.clear()
            niv99ShaderTuning.clear()
            return true
        }
        val lookup = processor.copyExactTuning(latchedSensorTimestampNs, niv99FrameTuning)
        if (lookup == Niv99MetadataLookupStatus.MATCHED) {
            if (!finalizeNiv99Lookup(processor, lookup)) {
                recordNiv99PacketMiss("matched packet belonged to a retired preview timeline")
                discardUnprovenNiv99Frame()
                return false
            }
            val accepted = niv99ShaderTuning.update(
                tuning = niv99FrameTuning,
                sensorTimestampNs = latchedSensorTimestampNs
            )
            if (accepted) {
                niv99PacketMatchedForLatchedFrame = true
                niv99TuningSourceForLatchedFrame = Niv99PreviewTuningSource.EXACT_FRAME
                niv99AwaitedMetadataTimestampNs.compareAndSet(latchedSensorTimestampNs, 0L)
                if (niv99MetadataDeadlineScheduled) {
                    handler?.removeCallbacks(niv99MetadataDeadlineRunnable)
                    niv99MetadataDeadlineScheduled = false
                }
                val matched = ++niv99PacketsMatched
                if (matched == 1L || matched % NIV99_LOG_INTERVAL_FRAMES == 0L) {
                    val identity = niv99FrameTuning.identity
                    val colorSource = niv99FrameTuning.colorEstimateSourceIdentity
                    Log.i(
                        TAG,
                        "NIV99_PACKET_ACCEPTED_GL total=$matched cameraId=${identity.cameraId} " +
                            "source=${Niv99PreviewTuningSource.EXACT_FRAME} " +
                            "sessionGeneration=${identity.sessionGeneration} " +
                            "frameNumber=${identity.frameNumber} " +
                            "sensorTimestampNs=${identity.sensorTimestampNs} " +
                            "authority=${niv99FrameTuning.authority} " +
                            "confidence=${niv99ShaderTuning.confidence} " +
                            "colorEstimateApplied=${niv99FrameTuning.colorEstimateApplied} " +
                            "colorSourceFrame=" +
                            "${if (colorSource.isComplete()) colorSource.frameNumber else -1L}"
                    )
                }
            } else {
                recordNiv99PacketMiss("matched packet was not pixel-approved")
                discardUnprovenNiv99Frame()
                return false
            }
            return true
        }

        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (lookup == Niv99MetadataLookupStatus.NOT_FOUND && nowNs < niv99MetadataWaitDeadlineNs) {
            scheduleNiv99MetadataDeadline(nowNs)
            return false
        }
        if (!finalizeNiv99Lookup(processor, lookup)) {
            recordNiv99PacketMiss("$lookup on retired preview timeline")
            discardUnprovenNiv99Frame()
            return false
        }
        val fallbackStatus = if (
            lookup == Niv99MetadataLookupStatus.NOT_FOUND && niv99ExpectedContinuityValid
        ) {
            processor.copyRecentTuning(
                targetSensorTimestampNs = latchedSensorTimestampNs,
                expectedContinuity = niv99ExpectedContinuity,
                output = niv99FrameTuning
            )
        } else {
            Niv99PreviewTuningFallbackStatus.NOT_FOUND
        }
        if (fallbackStatus == Niv99PreviewTuningFallbackStatus.MATCHED_RECENT) {
            val maximumAgeNs = Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(
                niv99ExpectedContinuity.targetFps.toFloat()
            )
            if (niv99ShaderTuning.updateRecent(
                    tuning = niv99FrameTuning,
                    targetSensorTimestampNs = latchedSensorTimestampNs,
                    maximumAgeNs = maximumAgeNs,
                    expectedContinuity = niv99ExpectedContinuity
                )
            ) {
                niv99PacketMatchedForLatchedFrame = true
                niv99TuningSourceForLatchedFrame = Niv99PreviewTuningSource.RECENT_CONTINUITY
                niv99AwaitedMetadataTimestampNs.compareAndSet(latchedSensorTimestampNs, 0L)
                val reused = ++niv99RecentPacketsReused
                if (reused == 1L || reused % NIV99_LOG_INTERVAL_FRAMES == 0L) {
                    Log.i(
                        TAG,
                        "NIV99_PACKET_ACCEPTED_GL totalRecent=$reused " +
                            "source=${Niv99PreviewTuningSource.RECENT_CONTINUITY} " +
                            "targetSensorTimestampNs=$latchedSensorTimestampNs " +
                            "sourceSensorTimestampNs=${niv99FrameTuning.identity.sensorTimestampNs} " +
                            "ageMs=${(latchedSensorTimestampNs -
                                niv99FrameTuning.identity.sensorTimestampNs) / 1_000_000.0}"
                    )
                }
                return true
            }
        }
        recordNiv99PacketMiss("exact=$lookup fallback=$fallbackStatus")
        if (!previewWorkload.waitForExactMetadata) {
            niv99AwaitedMetadataTimestampNs.compareAndSet(latchedSensorTimestampNs, 0L)
            niv99FrameTuning.clear()
            niv99ShaderTuning.clear()
            return true
        }
        discardUnprovenNiv99Frame()
        return false
    }

    /** Keeps the last proven window buffer visible and advances without swapping untreated pixels. */
    private fun discardUnprovenNiv99Frame() {
        niv99AwaitedMetadataTimestampNs.compareAndSet(latchedSensorTimestampNs, 0L)
        if (niv99MetadataDeadlineScheduled) {
            handler?.removeCallbacks(niv99MetadataDeadlineRunnable)
            niv99MetadataDeadlineScheduled = false
        }
        validInputFrameLatched = false
        latchedSensorTimestampNs = 0L
        niv99PacketMatchedForLatchedFrame = false
        niv99PixelReportedForLatchedFrame = false
        niv99TuningSourceForLatchedFrame = null
        niv99ExpectedContinuityValid = false
        niv99ExpectedContinuity.clear()
        niv99FrameTuning.clear()
        niv99ShaderTuning.clear()
        if (frameAvailable) requestRender()
    }

    private fun scheduleNiv99MetadataDeadline(nowNs: Long) {
        if (niv99MetadataDeadlineScheduled) return
        val delayMs = Niv99PreviewMetadataWakePolicy.deadlineDelayMs(
            nowNs = nowNs,
            deadlineNs = niv99MetadataWaitDeadlineNs
        )
        niv99MetadataDeadlineScheduled = handler?.postDelayed(
            niv99MetadataDeadlineRunnable,
            delayMs
        ) == true
    }

    private fun recordNiv99PacketMiss(reason: String) {
        if (niv99MissReportedForLatchedFrame) return
        niv99MissReportedForLatchedFrame = true
        val missed = ++niv99PacketsMissed
        if (missed == 1L || missed % NIV99_LOG_INTERVAL_FRAMES == 0L) {
            Log.w(
                TAG,
                "NIV99_PACKET_MISSED_GL total=$missed sensorTimestampNs=$latchedSensorTimestampNs " +
                    "reason=$reason"
            )
        }
    }

    private fun finalizeNiv99Lookup(
        processor: Niv99PreviewFrameProcessor,
        lookup: Niv99MetadataLookupStatus
    ): Boolean {
        if (niv99LookupReportedForLatchedFrame) return niv99LookupAcceptedForLatchedFrame
        niv99LookupReportedForLatchedFrame = true
        niv99LookupAcceptedForLatchedFrame = processor.onTuningLookupFinalized(
                sensorTimestampNs = latchedSensorTimestampNs,
                lookup = lookup,
                tuning = niv99FrameTuning
            )
        if (!niv99LookupAcceptedForLatchedFrame) {
            Log.w(
                TAG,
                "NIV99_LOOKUP_ACCOUNTING_REJECTED sensorTimestampNs=$latchedSensorTimestampNs " +
                    "lookup=$lookup"
            )
        }
        return niv99LookupAcceptedForLatchedFrame
    }

    private fun reportNiv99PixelsPresentedAfterSwap() {
        val tuningSource = niv99TuningSourceForLatchedFrame ?: return
        if (!niv99PacketMatchedForLatchedFrame || niv99PixelReportedForLatchedFrame ||
            niv99ShaderTuning.active <= 0f
        ) return
        niv99PixelReportedForLatchedFrame = true
        if (niv99FrameProcessor?.onPixelsPresented(
                tuning = niv99FrameTuning,
                presentedSensorTimestampNs = latchedSensorTimestampNs,
                source = tuningSource,
                expectedContinuity = niv99ExpectedContinuity
            ) != true
        ) {
            Log.w(
                TAG,
                "NIV99_PIXEL_REJECTED_PREVIEW sensorTimestampNs=$latchedSensorTimestampNs"
            )
            return
        }
        val applied = ++niv99PixelsApplied
        if (applied == 1L || applied % NIV99_LOG_INTERVAL_FRAMES == 0L) {
            val identity = niv99FrameTuning.identity
            Log.i(
                TAG,
                "NIV99_PIXEL_APPLIED_PREVIEW total=$applied cameraId=${identity.cameraId} " +
                    "source=$tuningSource presentedSensorTimestampNs=$latchedSensorTimestampNs " +
                    "sessionGeneration=${identity.sessionGeneration} " +
                    "frameNumber=${identity.frameNumber} " +
                    "sensorTimestampNs=${identity.sensorTimestampNs} " +
                    "wb=${niv99ShaderTuning.redCorrection},${niv99ShaderTuning.greenCorrection}," +
                    "${niv99ShaderTuning.blueCorrection} spatial=${niv99ShaderTuning.spatialDenoise} " +
                    "chroma=${niv99ShaderTuning.chromaDenoise} " +
                    "reconstruction=${niv99ShaderTuning.sharpeningCeiling} " +
                    "wbEffect=${niv99FrameTuning.whiteBalanceEffectMagnitude} " +
                    "denoiseEffect=${niv99FrameTuning.denoiseEffectMagnitude} " +
                    "reconstructionEffect=${niv99FrameTuning.reconstructionEffectMagnitude}"
            )
        }
    }

    private fun reportRuntimeFailure(
        operation: PreviewRuntimeOperation,
        eglError: Int,
        detail: String
    ) {
        val failure = PreviewRuntimeFailure(
            component = PreviewRuntimeComponent.SDR_CHILD,
            operation = operation,
            eglError = eglError,
            detail = detail
        )
        val claimed = synchronized(this) {
            if (!running || !runtimeFailureGate.tryClaimActiveFailure()) {
                false
            } else {
                running = false
                lastRuntimeFailure = failure
                true
            }
        }
        if (!claimed) return

        resetTemporalHistory(
            reason = PreviewTemporalFusionReason.RESOURCE_UNAVAILABLE,
            resetPolicy = true
        )
        Log.e(TAG, detail)
        val listener = runtimeFailureListener ?: return
        runCatching { listener(failure) }
            .onFailure { Log.e(TAG, "Strict preview runtime-failure listener failed", it) }
    }

    // Render-thread only. Rasters the R-fastest cube into a compact tiled 2D texture. The candidate
    // texture is completely uploaded before the previous valid LUT is retired.
    private fun uploadCreativeLutIfDirty() {
        if (!lutDirty) return
        lutDirty = false
        val up = pendingLut
        if (up == null) {
            lutUploaded = false
            lutCurrentSize = 0
            lutCurrentAtlasColumns = 1
            lutCurrentAtlasRows = 1
            lastCreativeLutUploadEvidence = PreviewCreativeLutUploadEvidence(
                accepted = true,
                cubeSize = 0,
                atlasWidth = 1,
                atlasHeight = 1,
                precision = null
            )
            return
        }
        if (lutTexId == 0) {
            rejectCreativeLutUpload(up, null, null, "LUT texture owner is unavailable")
            return
        }
        val layout = PreviewLutAtlasPolicy.resolveLayout(up.size, maximumLutTextureSize)
        if (layout == null) {
            rejectCreativeLutUpload(
                up, null, null,
                "cube=${up.size} exceeds GL_MAX_TEXTURE_SIZE=$maximumLutTextureSize as a tiled atlas"
            )
            return
        }
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val precision = PreviewLutAtlasPolicy.resolvePrecision(
            halfFloatTextureSupported = halfFloatLutTextureSupported,
            strictHighEnd = strictHighEnd
        )
        if (precision == null) {
            rejectCreativeLutUpload(
                up, layout, null,
                "strict 10-bit preview requires GL_OES_texture_half_float; RGB8 fallback rejected"
            )
            return
        }
        val atlasBuffer = runCatching { buildCreativeLutAtlasBuffer(up, layout, precision) }
            .getOrElse { failure ->
                rejectCreativeLutUpload(
                    up, layout, precision,
                    "atlas allocation/packing failed: ${failure.message ?: failure.javaClass.simpleName}"
                )
                return
            }
        val candidateArray = IntArray(1)
        GLES20.glGenTextures(1, candidateArray, 0)
        val candidateTexture = candidateArray[0]
        if (candidateTexture == 0) {
            rejectCreativeLutUpload(up, layout, precision, "glGenTextures returned 0")
            return
        }
        clearGlErrors()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, candidateTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val pixelType = when (precision) {
            PreviewLutTexturePrecision.RGBA16F -> GL_HALF_FLOAT_OES
            PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT -> GLES20.GL_UNSIGNED_BYTE
        }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, layout.width, layout.height, 0,
            GLES20.GL_RGBA, pixelType, atlasBuffer
        )
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(1, intArrayOf(candidateTexture), 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            rejectCreativeLutUpload(
                up, layout, precision,
                "candidate upload failed glError=0x${glError.toString(16)}"
            )
            return
        }
        val retiredTexture = lutTexId
        lutTexId = candidateTexture
        if (retiredTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(retiredTexture), 0)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        lutCurrentSize = up.size
        lutCurrentAtlasColumns = layout.columns
        lutCurrentAtlasRows = layout.rows
        lutUploaded = true
        lastCreativeLutUploadEvidence = PreviewCreativeLutUploadEvidence(
            accepted = true,
            cubeSize = up.size,
            atlasWidth = layout.width,
            atlasHeight = layout.height,
            precision = precision
        )
        if (precision == PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT) {
            Log.w(
                TAG,
                "Creative LUT using explicitly non-strict RGBA8 atlas ${layout.width}x${layout.height}"
            )
        }
    }

    private fun buildCreativeLutAtlasBuffer(
        up: PendingLut,
        layout: PreviewLutAtlasLayout,
        precision: PreviewLutTexturePrecision
    ): ByteBuffer {
        val bytesPerPixel = when (precision) {
            PreviewLutTexturePrecision.RGBA16F -> 8
            PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT -> 4
        }
        val byteCount = layout.width.toLong() * layout.height.toLong() * bytesPerPixel.toLong()
        require(byteCount in 1..Int.MAX_VALUE.toLong()) { "LUT atlas allocation overflow" }
        val buffer = ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder())
        val n = up.size
        for (atlasY in 0 until layout.height) {
            val tileY = atlasY / n
            val green = atlasY % n
            for (atlasX in 0 until layout.width) {
                val tileX = atlasX / n
                val red = atlasX % n
                val blue = tileY * layout.columns + tileX
                val validSlice = blue < n
                val sourceOffset = if (validSlice) {
                    PreviewLutAtlasPolicy.sourceFloatOffset(red, green, blue, n)
                } else {
                    0
                }
                val r = if (validSlice) up.data[sourceOffset].coerceIn(0f, 1f) else 0f
                val g = if (validSlice) up.data[sourceOffset + 1].coerceIn(0f, 1f) else 0f
                val b = if (validSlice) up.data[sourceOffset + 2].coerceIn(0f, 1f) else 0f
                when (precision) {
                    PreviewLutTexturePrecision.RGBA16F -> {
                        buffer.putShort(PreviewLutAtlasPolicy.floatToHalfBits(r))
                        buffer.putShort(PreviewLutAtlasPolicy.floatToHalfBits(g))
                        buffer.putShort(PreviewLutAtlasPolicy.floatToHalfBits(b))
                        buffer.putShort(PreviewLutAtlasPolicy.floatToHalfBits(1f))
                    }
                    PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT -> {
                        buffer.put((r * 255f + 0.5f).toInt().toByte())
                        buffer.put((g * 255f + 0.5f).toInt().toByte())
                        buffer.put((b * 255f + 0.5f).toInt().toByte())
                        buffer.put(0xff.toByte())
                    }
                }
            }
        }
        buffer.position(0)
        return buffer
    }

    private fun rejectCreativeLutUpload(
        up: PendingLut,
        layout: PreviewLutAtlasLayout?,
        precision: PreviewLutTexturePrecision?,
        reason: String
    ) {
        // A rejected replacement must never keep sampling an older profile's cube. Retain the old
        // texture allocation only as an inert, complete sampler; the uniform gate is closed until a
        // later valid request commits. This is fail-closed colour identity, not an 8-bit fallback.
        lutUploaded = false
        val detail = "Creative LUT upload rejected: $reason"
        lastCreativeLutUploadEvidence = PreviewCreativeLutUploadEvidence(
            accepted = false,
            cubeSize = up.size,
            atlasWidth = layout?.width ?: 0,
            atlasHeight = layout?.height ?: 0,
            precision = precision,
            failure = detail
        )
        Log.e(TAG, detail)
    }

    private fun clearGlErrors() {
        var attempts = 0
        while (attempts < MAX_GL_ERROR_DRAIN && GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            attempts += 1
        }
    }

    private fun destroyGl() {
        handler?.removeCallbacks(renderRunnable)
        handler?.removeCallbacks(niv99MetadataWakeRunnable)
        handler?.removeCallbacks(niv99MetadataDeadlineRunnable)
        renderWakeQueued.set(false)
        niv99MetadataWakeQueued.set(false)
        niv99AwaitedMetadataTimestampNs.set(0L)
        runCatching { inputSurface?.release() }
        inputSurface = null
        surfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        surfaceTexture = null
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            val contextCurrent =
                eglContext !== EGL14.EGL_NO_CONTEXT &&
                    eglSurface !== EGL14.EGL_NO_SURFACE &&
                    runCatching {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    }.getOrDefault(false)
            if (contextCurrent) {
                if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
                if (oesTextureId != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
                }
                if (program2d != 0) runCatching { GLES20.glDeleteProgram(program2d) }
                if (tex2dId != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(tex2dId), 0) }
                }
                if (lutTexId != 0) {
                    runCatching { GLES20.glDeleteTextures(1, intArrayOf(lutTexId), 0) }
                }
                if (temporalPresentProgram != 0) {
                    runCatching { GLES20.glDeleteProgram(temporalPresentProgram) }
                }
                runCatching { deleteTemporalHistoryTextures() }
                if (temporalSentinelTextureId != 0) {
                    runCatching {
                        GLES20.glDeleteTextures(
                            1,
                            intArrayOf(temporalSentinelTextureId),
                            0
                        )
                    }
                }
            }
            program = 0
            oesTextureId = 0
            program2d = 0
            tex2dId = 0
            tex2dAllocatedWidth = 0
            tex2dAllocatedHeight = 0
            lutTexId = 0
            lutUploaded = false
            lutCurrentSize = 0
            lutCurrentAtlasColumns = 1
            lutCurrentAtlasRows = 1
            halfFloatLutTextureSupported = false
            maximumLutTextureSize = 0
            temporalHistoryTextureIds.fill(0)
            temporalSentinelTextureId = 0
            temporalFramebufferId = 0
            temporalPresentProgram = 0
            temporalHistoryAllocated = false
            temporalHistoryWidth = 0
            temporalHistoryHeight = 0
            temporalHistoryWriteIndex = 0
            temporalHistoryValidCount = 0
            temporalFp16TextureSupported = false
            temporalFp16ColorBufferSupported = false
            temporalShaderAbiAvailable = false
            temporalRuntimeFailed = false
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        eglConfig = null
        frameAvailable = false
        validInputFrameLatched = false
        latchedFramePresented = false
        latchedSensorTimestampNs = 0L
        niv99PacketMatchedForLatchedFrame = false
        niv99PixelReportedForLatchedFrame = false
        niv99MissReportedForLatchedFrame = false
        niv99MetadataWaitDeadlineNs = 0L
        niv99MetadataDeadlineScheduled = false
        niv99PacketsMatched = 0L
        niv99PacketsMissed = 0L
        niv99RecentPacketsReused = 0L
        niv99PixelsApplied = 0L
        temporalHistoryCommits = 0L
        temporalFramesPresented = 0L
        temporalResourceFailures = 0L
        temporalStageExecutedForDraw = false
        temporalHistoryCapturedForDraw = false
        temporalRenderingToFp16ForDraw = false
        lastTemporalBypassLogged = null
        temporalFusionPolicy.reset()
        temporalResetRequest.set(PreviewTemporalFusionReason.RELEASED)
        niv99FrameTuning.clear()
        niv99ShaderTuning.clear()
        niv99ExpectedContinuity.clear()
        niv99ExpectedContinuityValid = false
        niv99TuningSourceForLatchedFrame = null
        synchronized(debayerStateLock) {
            debayerActive = false
            debayerPresentAllowed = false
            committedDebayerEpoch = NO_DEBAYER_EPOCH
            debayerFrameMailbox.clear()
        }
    }

    // STAGE 2: upload one exclusively leased CPU-readback frame, then blit the complete texture.
    // The lease is returned as soon as glTex(Sub)Image2D has synchronously consumed client memory.
    private fun drawDebayer2d(frame: RawPreviewFrameLease): Boolean {
        val uploadFailure = try {
            runCatching { uploadDebayerTexture(frame) }
                .getOrElse { error ->
                    "RAW preview texture upload threw ${error.javaClass.simpleName}: ${error.message}"
                }
        } finally {
            frame.close()
        }
        if (uploadFailure != null) {
            failClosedDebayerUpload(frame, uploadFailure)
            return false
        }

        val wArr = IntArray(1)
        val hArr = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, wArr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, hArr, 0)
        var glError = GLES20.GL_NO_ERROR
        var eglError = EGL14.EGL_SUCCESS
        val presented = synchronized(debayerStateLock) {
            if (!isDebayerFrameCurrentLocked(frame)) return@synchronized false

            clearGlErrors()
            GLES20.glViewport(0, 0, wArr[0].coerceAtLeast(1), hArr[0].coerceAtLeast(1))
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program2d)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
            GLES20.glUniform1i(uTex2dLoc, 0)
            // Use the identical live Camera2 SurfaceTexture matrix used by every working preview mode.
            // It already contains the vendor buffer's crop, flip and orientation for all four device
            // positions, so RAW must not reconstruct or invert that transform independently.
            GLES20.glUniformMatrix4fv(uTexMatrix2dLoc, 1, false, texMatrix, 0)
            // Always upload the orientation matrix (an unset mat2 uniform is all-zero and would collapse
            // sampling to one texel). Camera2 owns orientation at this compatibility seam.
            GLES20.glUniformMatrix2fv(uOrient2dLoc, 1, false, IDENTITY_ORIENT_2D, 0)
            quadVertices.position(0)
            GLES20.glVertexAttribPointer(aPos2dLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
            GLES20.glEnableVertexAttribArray(aPos2dLoc)
            quadVertices.position(2)
            GLES20.glVertexAttribPointer(aTex2dLoc, 2, GLES20.GL_FLOAT, false, STRIDE, quadVertices)
            GLES20.glEnableVertexAttribArray(aTex2dLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos2dLoc)
            GLES20.glDisableVertexAttribArray(aTex2dLoc)
            glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) return@synchronized false

            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                frame.sensorTimestampNs
            )
            val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (!swapped) eglError = EGL14.eglGetError()
            swapped
        }
        if (!presented) {
            if (glError != GLES20.GL_NO_ERROR) {
                failClosedDebayerUpload(
                    frame,
                    "RAW preview draw failed glError=0x${glError.toString(16)}"
                )
            } else if (eglError != EGL14.EGL_SUCCESS) {
                failClosedDebayerUpload(
                    frame,
                    "RAW preview swap failed eglError=0x${eglError.toString(16)}"
                )
            }
            if (eglError != EGL14.EGL_SUCCESS) {
                reportRuntimeFailure(
                    operation = PreviewRuntimeOperation.EGL_SWAP_BUFFERS,
                    eglError = eglError,
                    detail = "SDR RAW-debayer child eglSwapBuffers failed " +
                        "error=0x${eglError.toString(16)} timestampNs=${frame.sensorTimestampNs}"
                )
            }
            return false
        }
        val presentationTimestampNs = SystemClock.elapsedRealtimeNanos()
        val presentedOutput = boundOutput
        if (presentedOutput != null) {
            framePresentedListener?.invoke(
                frame.sensorTimestampNs,
                presentedOutput,
                presentationTimestampNs
            )
        }
        return true
    }

    /** Returns null only after GL has copied the complete immutable RGBA8 payload. */
    private fun uploadDebayerTexture(frame: RawPreviewFrameLease): String? {
        val requiredBytes = frame.requiredByteCount
        if (requiredBytes <= 0 || !frame.pixels.isDirect || frame.pixels.capacity() < requiredBytes) {
            return "invalid RAW preview payload ${frame.width}x${frame.height} " +
                "required=$requiredBytes capacity=${frame.pixels.capacity()} direct=${frame.pixels.isDirect}"
        }
        val uploadBytes = frame.pixels.duplicate().apply {
            position(0)
            limit(requiredBytes)
        }
        clearGlErrors()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (tex2dAllocatedWidth != frame.width || tex2dAllocatedHeight != frame.height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                frame.width,
                frame.height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                uploadBytes
            )
            val allocationError = GLES20.glGetError()
            if (allocationError != GLES20.GL_NO_ERROR) {
                tex2dAllocatedWidth = 0
                tex2dAllocatedHeight = 0
                return "RAW preview texture allocation/upload failed glError=0x${allocationError.toString(16)}"
            }
            tex2dAllocatedWidth = frame.width
            tex2dAllocatedHeight = frame.height
            return null
        }
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            frame.width,
            frame.height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            uploadBytes
        )
        val uploadError = GLES20.glGetError()
        return if (uploadError == GLES20.GL_NO_ERROR) {
            null
        } else {
            "RAW preview texture update failed glError=0x${uploadError.toString(16)}"
        }
    }

    private fun isDebayerFrameCurrentLocked(frame: RawPreviewFrameLease): Boolean =
        running && debayerActive && debayerPresentAllowed &&
            frame.presentationEpoch == debayerPresentationEpoch &&
            frame.presentationEpoch == committedDebayerEpoch

    private fun failClosedDebayerUpload(frame: RawPreviewFrameLease, reason: String) {
        synchronized(debayerStateLock) {
            debayerActive = false
            debayerPresentAllowed = false
            committedDebayerEpoch = NO_DEBAYER_EPOCH
            debayerFrameMailbox.clear()
        }
        requestTemporalHistoryReset(PreviewTemporalFusionReason.PIXEL_ROUTE_CHANGED)
        Log.e(
            TAG,
            "$reason epoch=${frame.presentationEpoch} timestampNs=${frame.sensorTimestampNs}; " +
                "falling back to direct camera preview"
        )
    }

    private fun build2dProgram(): Int? {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_2D) ?: return null
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_2D) ?: return null
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "2D program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun buildTemporalPresentProgram(): Int? {
        val vs = compileShader(
            GLES20.GL_VERTEX_SHADER,
            TEMPORAL_PRESENT_VERTEX_SHADER
        ) ?: return null
        val fs = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            TEMPORAL_PRESENT_FRAGMENT_SHADER
        ) ?: run {
            GLES20.glDeleteShader(vs)
            return null
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Temporal present program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun buildProgram(): Int? {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return null
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val TAG = "PreviewGlCompositor"
        private const val NO_DEBAYER_EPOCH = -1L
        // start()/prepareInput() remain synchronous because Camera2 needs a definitive input Surface. Bound
        // cold shader compilation and the rare in-call replacement wait; lifecycle teardown itself uses
        // releaseAsync() and never waits on CameraViewModel's caller thread. A timeout leaves direct preview
        // live so the existing retry can re-engage after the driver shader cache is warm.
        private const val START_TIMEOUT_MS = 1_200L
        private const val RELEASE_TIMEOUT_MS = 800L
        private const val NIV99_LOG_INTERVAL_FRAMES = 120L
        private const val TEMPORAL_LOG_INTERVAL_FRAMES = 120L
        private const val RECORDING_PRIORITY_LOG_INTERVAL_FRAMES = 120L
        private const val TEMPORAL_HISTORY_CAPACITY = 2
        private const val TEMPORAL_TEXTURE_POOL_SIZE = TEMPORAL_HISTORY_CAPACITY + 1
        private const val REQUIRED_FRAGMENT_TEXTURE_UNITS = 4
        private const val MAX_GL_ERROR_DRAIN = 16
        private const val GL_FRAMEBUFFER_INCOMPLETE_SENTINEL = 0x8CD6
        private const val GL_HALF_FLOAT_OES = 0x8D61
        private const val STRIDE = 4 * 4 // 4 floats per vertex

        // Grade codes shared with the fragment shader's uGrade uniform.
        const val GRADE_IDENTITY = 0
        const val GRADE_NEOLOG = 1
        const val GRADE_RAW_MONITOR = 2
        const val GRADE_SDR_ENHANCE = 3
        private val IDENTITY_ORIENT_2D = floatArrayOf(1f, 0f, 0f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform vec2 uTexelSize;
            varying vec2 vTexCoord;
            varying vec2 vScreenCoord;
            varying vec2 vStepX;
            varying vec2 vStepY;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                // Previous rendered frames were produced directly in screen-space FP16 targets, so
                // they are sampled without applying the Camera2 transform a second time.
                vScreenCoord = aTexCoord.xy;
                // Neighbour texel steps carried through the SAME SurfaceTexture matrix (w=0 =>
                // direction only) so sampling is correct under the stream's flip/crop/scale.
                vStepX = (uTexMatrix * vec4(uTexelSize.x, 0.0, 0.0, 0.0)).xy;
                vStepY = (uTexMatrix * vec4(0.0, uTexelSize.y, 0.0, 0.0)).xy;
            }
        """

        private const val TEMPORAL_PRESENT_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private const val TEMPORAL_PRESENT_FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            const float RGB10_QUANTIZATION_LEVELS = 1023.0;

            float ditherHash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec3 outc = clamp(texture2D(uTexture, vTexCoord).rgb, 0.0, 1.0);
                float neutralDither =
                    ditherHash(gl_FragCoord.xy) -
                    ditherHash(gl_FragCoord.xy + vec2(41.13, 17.79));
                float blackDitherGate = smoothstep(0.003, 0.020, dot(outc, LUMA));
                outc = clamp(
                    outc + vec3(neutralDither) * blackDitherGate *
                        (1.0 / RGB10_QUANTIZATION_LEVELS),
                    0.0,
                    1.0
                );
                gl_FragColor = vec4(outc, 1.0);
            }
        """

        // STAGE 2 debayer present: plain 2D sampler, passthrough (the RGBA8 is already display-ready
        // sRGB). V is flipped because the CPU-readback rows are top-first while GL samples bottom-first.
        private const val VERTEX_SHADER_2D = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform mat2 uOrient2d;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                // Exact parity with the camera-OES preview path above. The SurfaceTexture matrix owns
                // the vendor crop, vertical origin and all four physical display orientations.
                vec2 cameraUv = (uTexMatrix * aTexCoord).xy;
                vTexCoord = uOrient2d * (cameraUv - 0.5) + 0.5;
            }
        """
        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // The NEOLOG grade shows the user's OWN log technology — NeoLogCurve (N-LOG v5 APEX-LOG,
        // core-math/.../core/NeoLogCurve.kt), NOT an invented approximation. The camera feeds a
        // display-referred (~sRGB/Rec.709) 8-bit image, so we FIRST linearize it back to approximate
        // scene-linear, THEN apply the genuine NeoLog transfer (BASE variant: quintic toe below
        // toeCut, natural-log body above). That is the mathematically faithful "this scene through
        // NeoLog" monitor image: 18% gray → 0.50, white rolls off to ~0.74, shadows lifted at the toe.
        // This LIFTS midtones (fixing the earlier crushed/dark look) while giving the authentic flat
        // low-contrast log reading. The prior grade used a made-up pow()/scale curve that capped white
        // at 0.775 and did NOT use NeoLog — that was the "looks dark / not my log" defect.
        //   NEOLOG      → linearize → NeoLogCurve.encode(BASE) → mild log desaturation.
        //   RAW_MONITOR → clean, neutral, viewable lift of the flat RAW preview (full saturation).
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTexCoord;
            varying vec2 vScreenCoord;
            varying vec2 vStepX;
            varying vec2 vStepY;
            uniform samplerExternalOES uTexture;
            uniform int uGrade;
            uniform sampler2D uLut;      // R-fastest cube in a compact tiled 2D atlas
            uniform float uLutSize;      // N
            uniform vec2 uLutAtlasGrid;  // tile columns, rows
            uniform int uLutEnabled;     // 1 = apply creative LUT (WYSIWYG), 0 = bypass (default)
            uniform float uNiv99Active;
            uniform vec3 uNiv99RgbCorrection;
            uniform float uNiv99SpatialDenoise;
            uniform float uNiv99ChromaDenoise;
            uniform float uNiv99SharpeningCeiling;
            uniform float uNiv99Confidence;
            uniform float uRecordingPriority;
            uniform sampler2D uHistory0;
            uniform sampler2D uHistory1;
            uniform float uTemporalHistoryCount;
            uniform float uTemporalLumaStrength;
            uniform float uTemporalChromaStrength;
            uniform float uTemporalNoisePressure;
            uniform float uOutputDitherEnabled;

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            // The visible EGL target is strictly RGB10_A2; its code interval is 1 / 1023.
            const float RGB10_QUANTIZATION_LEVELS = 1023.0;

            // Display-referred sRGB -> approximate scene-linear (per channel).
            float srgbToLinear(float c) {
                return (c <= 0.04045) ? (c / 12.92) : pow((c + 0.055) / 1.055, 2.4);
            }

            float linearToSrgb(float c) {
                c = max(c, 0.0);
                return (c <= 0.0031308) ? (12.92 * c) : (1.055 * pow(c, 1.0 / 2.4) - 0.055);
            }

            vec3 displayToLinear(vec3 c) {
                return vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
            }

            vec3 linearToDisplay(vec3 c) {
                return vec3(linearToSrgb(c.r), linearToSrgb(c.g), linearToSrgb(c.b));
            }

            // A history sample has influence only when luma, chroma and exposure all agree with the
            // current rendered pixel. Motion boundaries, scene changes and illumination transitions
            // therefore fall back continuously to the current frame instead of leaving a ghost trail.
            float temporalResidualWeight(vec3 currentLinear, vec3 historyLinear) {
                float currentY = dot(currentLinear, LUMA);
                float historyY = dot(historyLinear, LUMA);
                vec3 currentChroma = currentLinear - vec3(currentY);
                vec3 historyChroma = historyLinear - vec3(historyY);
                float noise = clamp(uTemporalNoisePressure, 0.0, 1.0);
                float lumaResidual = abs(currentY - historyY);
                float chromaResidual = length(currentChroma - historyChroma);
                float lumaGate = 1.0 - smoothstep(
                    mix(0.004, 0.011, noise),
                    mix(0.018, 0.052, noise),
                    lumaResidual
                );
                float chromaGate = 1.0 - smoothstep(
                    mix(0.005, 0.014, noise),
                    mix(0.025, 0.070, noise),
                    chromaResidual
                );
                float exposureDelta = abs(log(
                    (currentY + 0.006) / (historyY + 0.006)
                ));
                float exposureGate = 1.0 - smoothstep(0.10, 0.34, exposureDelta);
                float highlightGate = 1.0 - 0.70 * smoothstep(
                    0.88,
                    0.995,
                    max(currentY, historyY)
                );
                return clamp(lumaGate * chromaGate * exposureGate * highlightGate, 0.0, 1.0);
            }

            float temporalMedian3(float a, float b, float c) {
                return max(min(a, b), min(max(a, b), c));
            }

            // Tukey's biweight is evaluated from the current pixel plus both already-fetched FP16
            // histories. The MAD scale rejects an isolated stale/occluded history sample without
            // adding texture reads. A noise-dependent floor keeps valid low-light samples useful
            // when quantization makes the three-sample MAD exactly zero.
            float temporalTukeyWeight(float deviation, float robustScale) {
                const float OUTLIER_SIGMA = 2.55;
                float normalized = deviation / max(OUTLIER_SIGMA * robustScale, 1.0e-5);
                float inside = clamp(1.0 - normalized * normalized, 0.0, 1.0);
                return inside * inside;
            }

            vec3 fuseTemporalHistory(vec3 currentDisplay) {
                if (uTemporalHistoryCount < 0.5) return currentDisplay;
                vec3 currentLinear = displayToLinear(clamp(currentDisplay, 0.0, 1.0));
                vec3 history0 = displayToLinear(clamp(
                    texture2D(uHistory0, vScreenCoord).rgb,
                    0.0,
                    1.0
                ));
                float weight0 = temporalResidualWeight(currentLinear, history0);
                vec3 weightedHistory = history0 * weight0;
                float totalWeight = weight0;
                float maximumWeight = 1.0;

                if (uTemporalHistoryCount > 1.5) {
                    vec3 history1 = displayToLinear(clamp(
                        texture2D(uHistory1, vScreenCoord).rgb,
                        0.0,
                        1.0
                    ));
                    float history0Y = dot(history0, LUMA);
                    float history1Y = dot(history1, LUMA);
                    vec3 history0Chroma = history0 - vec3(history0Y);
                    vec3 history1Chroma = history1 - vec3(history1Y);
                    float currentY = dot(currentLinear, LUMA);
                    float temporalMedianY = temporalMedian3(currentY, history0Y, history1Y);
                    float currentDeviation = abs(currentY - temporalMedianY);
                    float history0Deviation = abs(history0Y - temporalMedianY);
                    float history1Deviation = abs(history1Y - temporalMedianY);
                    float temporalMad = 1.4826 * temporalMedian3(
                        currentDeviation,
                        history0Deviation,
                        history1Deviation
                    );
                    float noiseScaleFloor = mix(
                        0.0015,
                        0.012,
                        clamp(uTemporalNoisePressure, 0.0, 1.0)
                    );
                    float robustScale = max(temporalMad, noiseScaleFloor);
                    weight0 *= temporalTukeyWeight(history0Deviation, robustScale);
                    weightedHistory = history0 * weight0;
                    totalWeight = weight0;
                    float historyDisagreement = abs(history0Y - history1Y) +
                        0.55 * length(history0Chroma - history1Chroma);
                    float coherenceGate = 1.0 - smoothstep(
                        mix(0.012, 0.030, clamp(uTemporalNoisePressure, 0.0, 1.0)),
                        mix(0.050, 0.105, clamp(uTemporalNoisePressure, 0.0, 1.0)),
                        historyDisagreement
                    );
                    float weight1 = 0.62 * coherenceGate *
                        temporalResidualWeight(currentLinear, history1) *
                        temporalTukeyWeight(history1Deviation, robustScale);
                    weightedHistory += history1 * weight1;
                    totalWeight += weight1;
                    maximumWeight += 0.62;
                }
                if (totalWeight <= 1.0e-5) return currentDisplay;

                vec3 historyMean = weightedHistory / totalWeight;
                float currentY = dot(currentLinear, LUMA);
                float historyY = dot(historyMean, LUMA);
                vec3 currentChroma = currentLinear - vec3(currentY);
                vec3 historyChroma = historyMean - vec3(historyY);
                float admittedCoverage = clamp(totalWeight / maximumWeight, 0.0, 1.0);
                float lumaMix = clamp(uTemporalLumaStrength * admittedCoverage, 0.0, 0.30);
                float chromaMix = clamp(uTemporalChromaStrength * admittedCoverage, 0.0, 0.50);

                float fusedY = mix(currentY, historyY, lumaMix);
                // This bound prevents a temporally accepted low-frequency difference from becoming
                // false structure. It cannot overshoot either source and becomes tight near equality.
                float maximumLumaDelta = 0.003 + 0.20 * abs(historyY - currentY);
                fusedY = currentY + clamp(
                    fusedY - currentY,
                    -maximumLumaDelta,
                    maximumLumaDelta
                );
                vec3 fusedChroma = mix(currentChroma, historyChroma, chromaMix);
                vec3 fusedLinear = clamp(vec3(fusedY) + fusedChroma, 0.0, 1.0);
                return clamp(linearToDisplay(fusedLinear), 0.0, 1.0);
            }

            // Exact-frame, HAL-relative NIV99 white balance. The correction is confidence-weighted
            // and peak-normalized per pixel before clamping: no output channel can exceed the source
            // pixel's peak, so the render transform cannot create new highlight clipping. Manual WB
            // and AWB-lock packets arrive with identity correction from Niv99PreviewShaderTuning.
            vec3 niv99ApplyWhiteBalance(vec3 source) {
                float amount = clamp(uNiv99Active * uNiv99Confidence, 0.0, 1.0);
                vec3 gains = mix(
                    vec3(1.0),
                    clamp(uNiv99RgbCorrection, vec3(0.92), vec3(1.08)),
                    amount
                );
                vec3 corrected = max(source, vec3(0.0)) * gains;
                float sourcePeak = max(max(source.r, source.g), source.b);
                float correctedPeak = max(max(corrected.r, corrected.g), corrected.b);
                float peakSafeScale = (correctedPeak > sourcePeak && correctedPeak > 1.0e-6)
                    ? sourcePeak / correctedPeak
                    : 1.0;
                return clamp(corrected * peakSafeScale, 0.0, 1.0);
            }

            // Neutral-axis calibration for the visible monitor. This is deliberately CHROMA-ONLY:
            // luminance is reconstructed exactly, so faint real shadow texture is never clipped or
            // crushed. A five-tap consensus distinguishes incoherent coloured read-noise from a real
            // dark colour: recoverable saturated colour and luma edges are protected, while both local
            // speckle and a spatially-coherent sub-noise-floor black offset are pulled to neutral. The
            // latter matters on high analogue gain, where a sensor-wide magenta bias is coherent but is
            // still calibration error. A second spatial-outlier lane reaches bright neutral surfaces,
            // where isolated P010/YUV chroma errors previously survived. That lane uses neighbour-only
            // consensus plus luma/chroma edge protection, so real coherent colour and fine luma detail
            // are unchanged. The same function is used by SDR, Pro, NeoLog and RAW-monitor grades,
            // independent of the selected detail profile.
            vec3 calibrateNearBlackChroma(vec3 center, vec3 n, vec3 s, vec3 e, vec3 w) {
                float yc = dot(center, LUMA);
                float yn = dot(n, LUMA);
                float ys = dot(s, LUMA);
                float ye = dot(e, LUMA);
                float yw = dot(w, LUMA);
                vec3 centerChroma = center - vec3(yc);
                vec3 nc = n - vec3(yn);
                vec3 sc = s - vec3(ys);
                vec3 ec = e - vec3(ye);
                vec3 wc = w - vec3(yw);
                vec3 neighbourConsensusChroma = (nc + sc + ec + wc) * 0.25;
                vec3 consensusChroma = (
                    centerChroma * 2.0 +
                    nc + sc + ec + wc
                ) / 6.0;
                float lumaStructure = max(
                    max(abs(yn - yc), abs(ys - yc)),
                    max(abs(ye - yc), abs(yw - yc))
                );
                float chromaStructure = max(length(nc - sc), length(ec - wc));
                float deepShadow = 1.0 - smoothstep(0.018, 0.090, yc);
                float edgeProtect = max(
                    smoothstep(0.006, 0.045, lumaStructure),
                    smoothstep(0.018, 0.100, chromaStructure)
                );
                float coherentColorProtect = smoothstep(0.018, 0.085, length(consensusChroma));
                float chromaDisagreement = length(centerChroma - consensusChroma);
                float outlierConfidence = smoothstep(0.0008, 0.018, chromaDisagreement);
                float shadowConsensusBlend = clamp(
                    deepShadow * (1.0 - edgeProtect) *
                        mix(0.42, 0.96, outlierConfidence) *
                        (1.0 - coherentColorProtect * 0.82),
                    0.0,
                    0.96
                );
                vec3 calibratedChroma = mix(centerChroma, consensusChroma, shadowConsensusBlend);

                float surfaceNeutral = 1.0 - smoothstep(
                    0.020,
                    0.090,
                    length(neighbourConsensusChroma)
                );
                float visibleSurface = smoothstep(0.075, 0.240, yc);
                float surfaceOutlierConfidence = smoothstep(
                    0.0020,
                    0.0280,
                    length(centerChroma - neighbourConsensusChroma)
                );
                float surfaceOutlierBlend = clamp(
                    visibleSurface * (1.0 - edgeProtect) * surfaceNeutral *
                        surfaceOutlierConfidence * 0.92,
                    0.0,
                    0.92
                );
                calibratedChroma = mix(
                    calibratedChroma,
                    neighbourConsensusChroma,
                    surfaceOutlierBlend
                );

                // Below the measured display/sensor black uncertainty, tiny coherent channel offsets
                // are calibration error rather than recoverable colour. Coherence must not weaken this
                // correction: the vivo dark-room failure is a frame-wide R/B black offset. The wide,
                // soft confidence ramps end before recoverable shadow colour and the saturation guard
                // still protects strongly coloured low-light subjects.
                float blackUncertainty = 1.0 - smoothstep(0.012, 0.065, yc);
                float tinyChroma = 1.0 - smoothstep(0.012, 0.070, length(calibratedChroma));
                float neutralize = clamp(
                    blackUncertainty * tinyChroma * (1.0 - edgeProtect) *
                        (1.0 - coherentColorProtect) * mix(0.96, 1.0, outlierConfidence),
                    0.0,
                    0.995
                );
                calibratedChroma *= 1.0 - neutralize;
                return clamp(vec3(yc) + calibratedChroma, 0.0, 1.0);
            }

            // SDR_ENHANCE: edge-preserving BILATERAL denoise + halo-controlled unsharp + flat-gated
            // chroma-noise smoothing on the LIVE preview. NO tone/colour shift — clarity only. This now
            // mirrors the offscreen-EGL record enhance (range-weighted bilateral instead of a flat
            // Gaussian) so the live monitor is as clean AND detailed as the recorded file (WYSIWYG),
            // rather than looking softer/noisier than the recording.
            vec3 enhance(vec3 center) {
                vec3 n  = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord + vStepY).rgb);
                vec3 s  = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord - vStepY).rgb);
                vec3 e  = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord + vStepX).rgb);
                vec3 w  = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord - vStepX).rgb);
                vec3 ne = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord + vStepX + vStepY).rgb);
                vec3 nw = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord - vStepX + vStepY).rgb);
                vec3 se = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord + vStepX - vStepY).rgb);
                vec3 sw = niv99ApplyWhiteBalance(texture2D(uTexture, vTexCoord - vStepX - vStepY).rgb);
                float lc = dot(center, LUMA);
                // Range sigma matched to the record path's hard clamp (<=0.05): edges keep ~0 range
                // weight so genuine detail (pores/foliage/fabric) is never blended toward the mean.
                float sigmaR = 0.05;
                float invR2 = 1.0 / (2.0 * sigmaR * sigmaR);
                vec3 accB = center * 4.0; float wB = 4.0;   // bilateral: spatial * range
                vec3 accG = center * 4.0; float wG = 4.0;   // spatial-only low-pass: unsharp reference
                float dl, rw;
                dl = dot(n,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += n  * 2.0 * rw; wB += 2.0 * rw; accG += n  * 2.0; wG += 2.0;
                dl = dot(s,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += s  * 2.0 * rw; wB += 2.0 * rw; accG += s  * 2.0; wG += 2.0;
                dl = dot(e,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += e  * 2.0 * rw; wB += 2.0 * rw; accG += e  * 2.0; wG += 2.0;
                dl = dot(w,  LUMA) - lc; rw = exp(-dl * dl * invR2); accB += w  * 2.0 * rw; wB += 2.0 * rw; accG += w  * 2.0; wG += 2.0;
                dl = dot(ne, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += ne * rw;       wB += rw;       accG += ne;       wG += 1.0;
                dl = dot(nw, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += nw * rw;       wB += rw;       accG += nw;       wG += 1.0;
                dl = dot(se, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += se * rw;       wB += rw;       accG += se;       wG += 1.0;
                dl = dot(sw, LUMA) - lc; rw = exp(-dl * dl * invR2); accB += sw * rw;       wB += rw;       accG += sw;       wG += 1.0;
                vec3 bilateral = accB / wB;
                vec3 gauss = accG / wG;
                float ln = dot(n, LUMA); float ls = dot(s, LUMA);
                float le = dot(e, LUMA); float lw = dot(w, LUMA);
                float gradientX = 0.5 * (le - lw);
                float gradientY = 0.5 * (ln - ls);
                float edgeEnergy = sqrt(gradientX * gradientX + gradientY * gradientY);
                float laplacian = abs(lc - 0.25 * (ln + ls + le + lw));
                float edgeProtect = smoothstep(0.012, 0.095, edgeEnergy + laplacian);
                float diagonalSpread = max(length(ne - sw), length(nw - se));
                float fineTexture = smoothstep(0.010, 0.090, laplacian + 0.25 * diagonalSpread) *
                    (1.0 - smoothstep(0.10, 0.28, edgeEnergy));

                // NIV99 separates luma and chroma strength. Structure and repeated fine texture gate
                // luma smoothing aggressively, while flat-region chroma cleanup may remain stronger.
                float niv99On = clamp(uNiv99Active, 0.0, 1.0);
                float confidence = clamp(uNiv99Confidence, 0.0, 1.0);
                float lumaPolicy = clamp(uNiv99SpatialDenoise, 0.0, 0.62) * confidence;
                float chromaPolicy = clamp(uNiv99ChromaDenoise, 0.0, 0.86) *
                    mix(0.55, 1.0, confidence);
                float lumaBlend = mix(
                    0.70,
                    lumaPolicy * (1.0 - 0.90 * edgeProtect) * (1.0 - 0.70 * fineTexture),
                    niv99On
                );
                float chromaBlend = mix(
                    0.30,
                    chromaPolicy * (1.0 - 0.68 * edgeProtect) * (1.0 - 0.25 * fineTexture),
                    niv99On
                );
                float bilateralY = dot(bilateral, LUMA);
                vec3 bilateralChroma = bilateral - vec3(bilateralY);
                vec3 centerChroma = center - vec3(lc);
                float filteredY = mix(lc, bilateralY, clamp(lumaBlend, 0.0, 0.70));
                vec3 filteredChroma = mix(
                    centerChroma,
                    bilateralChroma,
                    clamp(chromaBlend, 0.0, 0.86)
                );
                vec3 denoised = vec3(filteredY) + filteredChroma;

                // Full-frame detail reconstruction reuses this shader's existing nine samples. Two
                // frequency bands prevent a single broad unsharp residual from turning small distant
                // texture into a thick edge. The live luma-denoise request raises the evidence floor,
                // so high-ISO grain cannot qualify merely because it occupies a high frequency.
                float lne = dot(ne, LUMA); float lnw = dot(nw, LUMA);
                float lse = dot(se, LUMA); float lsw = dot(sw, LUMA);
                float axialMean = 0.25 * (ln + ls + le + lw);
                float gaussianLuma = dot(gauss, LUMA);
                float microResidual = lc - axialMean;
                float mesoResidual = lc - gaussianLuma;
                float frequencyResidual = mix(mesoResidual, microResidual, 0.38);
                float detailLuma = abs(frequencyResidual);
                float noiseEvidenceFloor = 0.0025 + 0.0100 * lumaPolicy *
                    (1.0 - 0.45 * confidence);
                float noiseConfidence = smoothstep(
                    noiseEvidenceFloor,
                    noiseEvidenceFloor * 3.8 + 1.0e-6,
                    detailLuma
                );
                float measuredStructure = noiseConfidence * max(edgeProtect, fineTexture);
                float reconstructionPolicy = mix(
                    0.60,
                    0.24 + 0.66 * clamp(uNiv99SharpeningCeiling, 0.0, 1.0),
                    niv99On
                );
                float haloGuard = 1.0 - smoothstep(0.055, 0.18, detailLuma);
                float localMinY = min(min(min(lc, ln), min(ls, le)), min(lw,
                    min(min(lne, lnw), min(lse, lsw))));
                float localMaxY = max(max(max(lc, ln), max(ls, le)), max(lw,
                    max(max(lne, lnw), max(lse, lsw))));
                float localRangeY = max(localMaxY - localMinY, 0.0);
                float localContrastGuard = 1.0 - smoothstep(0.18, 0.62, localRangeY);
                float reconstructionAmount = reconstructionPolicy * confidence *
                    measuredStructure * haloGuard * mix(0.72, 1.0, localContrastGuard);
                float policyBound = mix(
                    0.06,
                    0.025 + 0.045 * clamp(uNiv99SharpeningCeiling, 0.0, 1.0),
                    niv99On
                );
                float reconstructionBound = min(policyBound, 0.22 * localRangeY + 0.002);
                float lumaBoost = clamp(
                    frequencyResidual * reconstructionAmount,
                    -reconstructionBound,
                    reconstructionBound
                );
                // A strict local-envelope projection prohibits bright/dark ringing. Add only the
                // admitted luma delta so real chroma ratios are not sharpened into colour fringes.
                float reconstructedY = clamp(filteredY + lumaBoost, localMinY, localMaxY);
                vec3 outc = denoised + vec3(reconstructedY - filteredY);
                // Final calibration is after denoise/sharpen so neither the unsharp reference nor
                // neighbouring chroma can reintroduce coloured black-level sparkle.
                return calibrateNearBlackChroma(clamp(outc, 0.0, 1.0), n, s, e, w);
            }

            // NeoLogCurve N-LOG v5 APEX-LOG, BASE variant — matches core-math NeoLogCurve.encode():
            // quintic C2 toe below toeCut, natural-log body above; 18% scene-linear -> 0.50 code.
            float neoLogEncode(float x) {
                const float toeCut     = 0.010;
                const float toeHeight  = 0.080;
                const float logScale   = 0.137099;
                const float logDensity = 120.0;
                const float xMax       = 6.843034;
                const float c1 = 0.080;
                const float c3 = -0.436788;
                const float c4 = 0.789056;
                const float c5 = -0.352269;
                float xx = clamp(x, 0.0, xMax);
                if (xx < toeCut) {
                    float t  = clamp(xx / toeCut, 0.0, 1.0);
                    float t2 = t * t;
                    float t3 = t2 * t;
                    float t4 = t3 * t;
                    float t5 = t4 * t;
                    return c1 * t + c3 * t3 + c4 * t4 + c5 * t5;
                }
                return toeHeight + logScale * log(1.0 + logDensity * (xx - toeCut));
            }

            // One LUT node (ri,gi,bi are integers-as-float). Each blue slice occupies one N x N
            // tile; the CPU packer and this address function share the same slice grid.
            vec3 lutFetch(float ri, float gi, float bi, float n) {
                float tileX = mod(bi, uLutAtlasGrid.x);
                float tileY = floor(bi / uLutAtlasGrid.x);
                float u = (tileX * n + ri + 0.5) / (n * uLutAtlasGrid.x);
                float v = (tileY * n + gi + 0.5) / (n * uLutAtlasGrid.y);
                return texture2D(uLut, vec2(u, v)).rgb;
            }
            // Manual trilinear over the 2D-packed cube — reproduces the record path's GL_TEXTURE_3D
            // GL_LINEAR sample on the identical cube. Strict nodes remain FP16 until RGB10_A2 output.
            vec3 sampleCreativeLut(vec3 rgb, float n) {
                // Match the record path's GL_TEXTURE_3D GL_LINEAR sample exactly: texture(uLut, v) places
                // the node lookup at v*N - 0.5 (texel centres at (k+0.5)/N), edge-clamped. Reproducing
                // that convention (NOT v*(N-1)) is what makes preview == recorded pixels.
                vec3 c  = clamp(clamp(rgb, 0.0, 1.0) * n - 0.5, 0.0, n - 1.0);
                vec3 c0 = floor(c);
                vec3 c1 = min(c0 + 1.0, n - 1.0);
                vec3 f  = c - c0;
                vec3 c000 = lutFetch(c0.r, c0.g, c0.b, n);
                vec3 c100 = lutFetch(c1.r, c0.g, c0.b, n);
                vec3 c010 = lutFetch(c0.r, c1.g, c0.b, n);
                vec3 c110 = lutFetch(c1.r, c1.g, c0.b, n);
                vec3 c001 = lutFetch(c0.r, c0.g, c1.b, n);
                vec3 c101 = lutFetch(c1.r, c0.g, c1.b, n);
                vec3 c011 = lutFetch(c0.r, c1.g, c1.b, n);
                vec3 c111 = lutFetch(c1.r, c1.g, c1.b, n);
                vec3 x00 = mix(c000, c100, f.r);
                vec3 x10 = mix(c010, c110, f.r);
                vec3 x01 = mix(c001, c101, f.r);
                vec3 x11 = mix(c011, c111, f.r);
                return mix(mix(x00, x10, f.g), mix(x01, x11, f.g), f.b);
            }

            // Anti-banding dither: hash value-noise for a triangular-PDF (TPDF) dither before the
            // RGB10_A2 quantize, so baked-LUT gradients (skies/skin) decorrelate into imperceptible
            // noise instead of posterizing on the live monitor.
            float ditherHash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec3 c = niv99ApplyWhiteBalance(
                    clamp(texture2D(uTexture, vTexCoord).rgb, 0.0, 1.0)
                );
                // The SDR enhancement path already reuses its 9-tap neighbourhood. NeoLog and RAW
                // use this bounded 5-tap calibration before their transfer functions, preventing a
                // log/toe lift from magnifying false sensor colour while retaining genuine luma detail.
                if (uGrade == 1 || uGrade == 2) {
                    vec3 n = clamp(texture2D(uTexture, vTexCoord + vStepY).rgb, 0.0, 1.0);
                    vec3 s = clamp(texture2D(uTexture, vTexCoord - vStepY).rgb, 0.0, 1.0);
                    vec3 e = clamp(texture2D(uTexture, vTexCoord + vStepX).rgb, 0.0, 1.0);
                    vec3 w = clamp(texture2D(uTexture, vTexCoord - vStepX).rgb, 0.0, 1.0);
                    c = calibrateNearBlackChroma(c, n, s, e, w);
                }
                vec3 outc = c;
                if (uGrade == 3) {
                    // SDR_ENHANCE: clarity only, no tone/colour shift. Kept OUT of the NEOLOG/
                    // RAW_MONITOR branches so those device-verified looks are byte-unchanged.
                    // Recording priority keeps exact 10-bit presentation, WB and colour/LUT but
                    // avoids the preview-only 9-tap spatial pass competing with the encoder GPU.
                    outc = (uRecordingPriority > 0.5) ? c : enhance(c);
                    // WYSIWYG: layer the graded creative cube on the enhanced SDR image so preview
                    // matches record. Gated by uLutEnabled (default 0 => byte-identical enhance-only).
                    if (uLutEnabled == 1) {
                        outc = clamp(sampleCreativeLut(outc, uLutSize), 0.0, 1.0);
                    }
                } else if (uGrade == 1) {
                    // NEOLOG: linearize the preview, then apply the user's genuine NeoLog transfer.
                    vec3 lin = vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
                    vec3 logc = vec3(neoLogEncode(lin.r), neoLogEncode(lin.g), neoLogEncode(lin.b));
                    float y = dot(logc, LUMA);
                    outc = mix(vec3(y), logc, 0.90);        // -10% saturation (log is mildly desaturated)
                    // LOG_ORIGINAL preview: keep the flat encoded signal visible without a display-referred
                    // monitor lift so preview and recorded LOG tone stay matched.
                } else if (uGrade == 2) {
                    // NEUTRAL RAW MONITOR: a faithful, low-contrast reading. Preview-only; sensor RAW
                    // bytes and metadata remain untouched.
                    // Decode the display transfer, preserve shadows/midtones 1:1, and compress only
                    // highlight headroom using one luminance scale. No contrast curve, saturation boost,
                    // vendor filmic look, or per-channel clipping is introduced.
                    vec3 linear = vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
                    float y = dot(linear, LUMA);
                    const float kKnee = 0.82;
                    float excess = max(y - kKnee, 0.0);
                    float shoulder = kKnee + (1.0 - kKnee) *
                        (excess / (excess + (1.0 - kKnee)));
                    float mappedY = mix(y, shoulder, step(kKnee, y));
                    linear *= (y > 1.0e-6) ? (mappedY / y) : 0.0;
                    outc = clamp(vec3(
                        linearToSrgb(linear.r),
                        linearToSrgb(linear.g),
                        linearToSrgb(linear.b)
                    ), 0.0, 1.0);
                }
                // Screen-space FP16 history is admitted only for exact-frame SDR_ENHANCE tuning.
                // It runs after all colour/detail work and before the one-code RGB10 dither, so
                // rejected pixels remain the current single-frame result byte-for-byte at this stage.
                if (uGrade == 3 && uTemporalHistoryCount > 0.5) {
                    outc = fuseTemporalHistory(outc);
                }
                // One zero-mean scalar TPDF term at <= 1 RGB10 code is shared across RGB. A neutral
                // input therefore remains exactly on the neutral axis instead of gaining coloured
                // sparkle. Fade at exact black, where adding even neutral quantization noise has no
                // useful anti-banding value.
                if (uOutputDitherEnabled > 0.5) {
                    vec2 dpx = gl_FragCoord.xy;
                    float neutralDither =
                        ditherHash(dpx) - ditherHash(dpx + vec2(41.13, 17.79));
                    float outY = dot(clamp(outc, 0.0, 1.0), LUMA);
                    float blackDitherGate = smoothstep(0.003, 0.020, outY);
                    outc = clamp(
                        clamp(outc, 0.0, 1.0) + vec3(neutralDither) * blackDitherGate *
                            (1.0 / RGB10_QUANTIZATION_LEVELS),
                        0.0,
                        1.0
                    );
                }
                gl_FragColor = vec4(outc, 1.0);
            }
        """
    }
}
