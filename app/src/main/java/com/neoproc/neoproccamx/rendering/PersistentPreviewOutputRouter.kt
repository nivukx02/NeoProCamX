package com.neoproc.neoproccamx.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.core.HotPathScheduling
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps the camera's SDR and HDR monitor inputs configured so a mode switch changes only the
 * downstream visible source. Strict output setup failure is reported to the controller; this router
 * never creates or authorizes a direct, untagged, or 8-bit replacement surface.
 */
internal object PersistentPreviewPipelineFlag {
    @Volatile var enabled: Boolean = true
}

/**
 * Stable visible-output owner for the persistent SDR/HDR preview pair.
 *
 * The SDR and HDR compositors remain permanently attached to the two private input surfaces exposed
 * here. This router alone owns the SurfaceView EGL window and selects the latest complete child frame
 * with a same-size identity blit. Mode changes therefore never disconnect or recreate the visible
 * BufferQueue. Both child pipelines keep their existing shaders, precision, resolution and camera
 * dynamic-range profiles; this class changes only which already-rendered monitor frame is presented.
 */
internal class PersistentPreviewOutputRouter {

    /**
     * Stable, machine-readable reason that the strict visible-output contract could not be built.
     * None of these failures authorize an untagged, 8-bit, or direct-Camera2 replacement surface.
     */
    enum class StartFailure {
        RELEASE_IN_PROGRESS,
        INITIALIZATION_TIMEOUT,
        EGL_DISPLAY_UNAVAILABLE,
        EGL_INITIALIZATION_FAILED,
        TEN_BIT_CONFIG_UNAVAILABLE,
        NON_TEN_BIT_CONFIG,
        EGL_CONTEXT_CREATION_FAILED,
        PQ_WINDOW_SURFACE_FAILED,
        OUTPUT_GEOMETRY_MISMATCH,
        EGL_MAKE_CURRENT_FAILED,
        SHADER_PROGRAM_FAILED,
        RUNTIME_EGL_FAILURE,
        UNKNOWN
    }

    class StartResult private constructor(
        val started: Boolean,
        val failure: StartFailure?,
        val detail: String
    ) {
        init {
            require(started == (failure == null)) {
                "A successful strict-preview start cannot carry a failure reason"
            }
        }

        companion object {
            fun success(detail: String = "Strict RGB10_A2 BT.2020-PQ output is active"): StartResult =
                StartResult(started = true, failure = null, detail = detail)

            fun failure(reason: StartFailure, detail: String): StartResult =
                StartResult(started = false, failure = reason, detail = detail)
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false
    @Volatile private var releaseInProgress = false
    private var releaseLatch: CountDownLatch? = null
    @Volatile private var selectedSource = SOURCE_SDR
    @Volatile private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0
    @Volatile private var lastStartOutcome = StartResult.failure(
        StartFailure.UNKNOWN,
        "No strict preview start has completed"
    )

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var parkingSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var recordingWindowParkingAvailable = false
    private val recordingWindowGate = RecordingPreviewWindowGate()
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uTexelSizeLoc = 0
    private var uSdrShadowChromaCleanupLoc = 0

    private val textureIds = IntArray(SOURCE_COUNT)
    private val surfaceTextures = arrayOfNulls<SurfaceTexture>(SOURCE_COUNT)
    private val childOutputSurfaces = arrayOfNulls<Surface>(SOURCE_COUNT)
    private val frameAvailable = BooleanArray(SOURCE_COUNT)
    private val frameReady = BooleanArray(SOURCE_COUNT)
    private val textureMatrices = Array(SOURCE_COUNT) { FloatArray(16) }
    private val latchedTimestampNs = LongArray(SOURCE_COUNT)
    private val lastSuccessfulSwapTimestampNs = LongArray(SOURCE_COUNT)
    private val successfulSwapElapsedByTimestamp =
        Array(SOURCE_COUNT) { LinkedHashMap<Long, Long>() }
    private val lastEmittedTimestampNs = LongArray(SOURCE_COUNT)
    private val pendingLineage = Array(SOURCE_COUNT) { LinkedHashSet<Long>() }
    private var selectionDirty = false
    @Volatile private var framePresentedListener: ((Long, Surface, Long) -> Unit)? = null
    @Volatile private var runtimeFailureListener: ((PreviewRuntimeFailure) -> Unit)? = null
    @Volatile private var lastRuntimeFailure: PreviewRuntimeFailure? = null
    private val runtimeFailureGate = PreviewRuntimeFailureGate()

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * java.lang.Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }

    fun start(output: Surface, width: Int, height: Int, initialSource: Int): Boolean =
        startWithResult(output, width, height, initialSource).started

    fun startWithResult(
        output: Surface,
        width: Int,
        height: Int,
        initialSource: Int
    ): StartResult {
        if (releaseInProgress) {
            return publishStartResult(
                StartResult.failure(
                    StartFailure.RELEASE_IN_PROGRESS,
                    "The previous strict preview output is still retiring"
                )
            )
        }
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val source = normalizeSource(initialSource)
        if (running && boundOutput === output && boundWidth == w && boundHeight == h) {
            selectSource(source)
            return publishStartResult(StartResult.success("Existing strict output reused"))
        }
        release()
        if (releaseInProgress) {
            return publishStartResult(
                StartResult.failure(
                    StartFailure.RELEASE_IN_PROGRESS,
                    "Strict preview teardown did not complete before restart"
                )
            )
        }
        selectedSource = source
        val renderThread = HandlerThread(
            "PersistentPreviewOutput",
            HotPathScheduling.urgentDisplayPriority()
        ).also { it.start() }
        val renderHandler = Handler(renderThread.looper)
        thread = renderThread
        handler = renderHandler
        val latch = CountDownLatch(1)
        var initialization = StartResult.failure(
            StartFailure.UNKNOWN,
            "Strict preview initialization did not report a result"
        )
        renderHandler.post {
            initialization = runCatching { initGl(output, w, h) }
                .onFailure { Log.w(TAG, "Stable preview output init failed", it) }
                .getOrElse {
                    StartResult.failure(
                        StartFailure.UNKNOWN,
                        "Unexpected strict preview initialization failure: ${it.javaClass.simpleName}"
                    )
                }
            latch.countDown()
        }
        val completed = runCatching { latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !initialization.started) {
            val failure = if (!completed) {
                StartResult.failure(
                    StartFailure.INITIALIZATION_TIMEOUT,
                    "Strict preview initialization exceeded ${START_TIMEOUT_MS}ms"
                )
            } else {
                initialization
            }
            Log.w(
                TAG,
                "Stable preview output start rejected failure=${failure.failure} " +
                    "detail=${failure.detail}"
            )
            release()
            return publishStartResult(failure)
        }
        synchronized(this) {
            lastRuntimeFailure = null
            runtimeFailureGate.armAfterSuccessfulStart()
            running = true
            boundOutput = output
            boundWidth = w
            boundHeight = h
            recordingWindowGate.startVisible()
        }
        Log.i(TAG, "Stable preview output started ${w}x${h} source=$source")
        return publishStartResult(
            StartResult.success("Strict RGB10_A2 BT.2020-PQ output started ${w}x${h}")
        )
    }

    fun lastStartResult(): StartResult = lastStartOutcome

    private fun publishStartResult(result: StartResult): StartResult {
        lastStartOutcome = result
        return result
    }

    fun sdrOutputSurface(): Surface? = childOutputSurfaces[SOURCE_SDR].takeIf { running }

    fun hdrOutputSurface(): Surface? = childOutputSurfaces[SOURCE_HDR].takeIf { running }

    /**
     * Selects a warm child without waiting on EGL. If that child has not produced a complete frame
     * yet, SurfaceFlinger keeps the outgoing frame and the first incoming callback performs the swap.
     */
    fun selectSource(source: Int): Boolean {
        if (!running) return false
        selectedSource = normalizeSource(source)
        handler?.post {
            selectionDirty = true
            drawFrame()
        }
        return true
    }

    fun isRunning(): Boolean = running

    fun isReleasing(): Boolean = releaseInProgress

    /** Keep camera inputs draining when SystemUI occludes the recording window without onPause. */
    fun setRecordingWindowOccluded(occluded: Boolean) {
        if (!running || releaseInProgress) return
        if (occluded && !recordingWindowParkingAvailable) {
            Log.w(TAG, "Recording window occlusion cannot park: strict parking context unavailable")
            return
        }
        val renderHandler = handler ?: return
        // Publish the no-swap decision immediately, before queued GL work can wait on SurfaceFlinger.
        if (!recordingWindowGate.setPresentationOccluded(occluded)) return
        renderHandler.post {
            if (!running || releaseInProgress) return@post
            clearVisibleWindowLineage()
            selectionDirty = true
            drawFrame()
            Log.i(TAG, "RECORDING_WINDOW_OCCLUSION occluded=$occluded " +
                "cameraInputsRetained=true eglWindowRetained=true qualityUnchanged=true")
        }
    }

    /**
     * Suspend only Android's visible window. Camera-facing SurfaceTextures, child compositors and
     * their render-profile ownership remain alive. The tiny parking surface is only a GL context
     * anchor for draining input queues; it never receives or publishes a substitute preview frame.
     */
    fun detachVisibleOutputForRecording(onComplete: (StartResult) -> Unit): Boolean {
        if (!running || releaseInProgress || !recordingWindowParkingAvailable) return false
        val renderHandler = handler ?: return false
        val ticket = recordingWindowGate.requestDetach() ?: return false
        return renderHandler.post {
            val result = when {
                !running || !recordingWindowGate.isCurrent(ticket) -> StartResult.failure(
                    StartFailure.RELEASE_IN_PROGRESS, "Visible-window detach was superseded"
                )
                parkVisibleOutput() -> StartResult.success(
                    "Visible window detached; Camera2 inputs and recording profile retained"
                )
                else -> StartResult.failure(
                    StartFailure.RUNTIME_EGL_FAILURE, "Visible-window parking failed"
                )
            }
            onComplete(result)
        }
    }

    /** Recreate only the same-size strict 10-bit PQ window, without replacing camera inputs. */
    fun attachVisibleOutputForRecording(
        output: Surface,
        width: Int,
        height: Int,
        onComplete: (StartResult) -> Unit
    ): Boolean {
        if (!running || releaseInProgress || !recordingWindowParkingAvailable ||
            width != boundWidth || height != boundHeight || !output.isValid
        ) return false
        val renderHandler = handler ?: return false
        val ticket = recordingWindowGate.requestAttach() ?: return false
        return renderHandler.post {
            if (!running || !recordingWindowGate.isCurrent(ticket)) {
                onComplete(StartResult.failure(
                    StartFailure.RELEASE_IN_PROGRESS, "Visible-window replacement was superseded"
                ))
                return@post
            }
            if (!parkVisibleOutput()) {
                onComplete(StartResult.failure(
                    StartFailure.RUNTIME_EGL_FAILURE, "Unable to park before visible-window replacement"
                ))
                return@post
            }
            val config = eglConfig
            if (config == null) {
                onComplete(StartResult.failure(
                    StartFailure.TEN_BIT_CONFIG_UNAVAILABLE, "Retained strict EGL config is unavailable"
                ))
                return@post
            }
            val result = createSizedWindowSurface(config, output, width, height)
            val replacement = result.surface
            if (replacement == null) {
                onComplete(StartResult.failure(
                    result.failure ?: StartFailure.UNKNOWN, result.detail
                ))
                return@post
            }
            if (!running || !recordingWindowGate.commitAttached(ticket)) {
                EGL14.eglMakeCurrent(eglDisplay, parkingSurface, parkingSurface, eglContext)
                EGL14.eglDestroySurface(eglDisplay, replacement)
                onComplete(StartResult.failure(
                    StartFailure.RELEASE_IN_PROGRESS, "Strict replacement became stale before commit"
                ))
                return@post
            }
            eglSurface = replacement
            boundOutput = output
            // A previous visible swap cannot certify a new Android window. Require new child data.
            clearVisibleWindowLineage()
            frameReady.fill(false)
            selectionDirty = true
            Log.i(TAG, "Recording preview window reattached ${width}x${height}; camera inputs unchanged")
            onComplete(StartResult.success("Strict recording preview window reattached"))
        }
    }

    private fun parkVisibleOutput(): Boolean {
        if (parkingSurface === EGL14.EGL_NO_SURFACE || eglContext === EGL14.EGL_NO_CONTEXT) {
            reportRuntimeFailure(
                PreviewRuntimeOperation.EGL_MAKE_CURRENT, EGL14.EGL_BAD_SURFACE,
                "Recording preview has no valid parking context"
            )
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, parkingSurface, parkingSurface, eglContext)) {
            val error = EGL14.eglGetError()
            reportRuntimeFailure(
                PreviewRuntimeOperation.EGL_MAKE_CURRENT, error,
                "Recording preview parking failed error=0x${Integer.toHexString(error)}"
            )
            return false
        }
        if (eglSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        boundOutput = null
        clearVisibleWindowLineage()
        return true
    }

    private fun clearVisibleWindowLineage() {
        for (source in 0 until SOURCE_COUNT) {
            successfulSwapElapsedByTimestamp[source].clear()
            pendingLineage[source].clear()
            lastSuccessfulSwapTimestampNs[source] = 0L
            lastEmittedTimestampNs[source] = 0L
        }
    }

    fun setOnFramePresentedListener(listener: ((Long, Surface, Long) -> Unit)?) {
        framePresentedListener = listener
    }

    /**
     * Receives at most one runtime failure for each successful [startWithResult]. The callback runs
     * on the router render thread after the router has failed closed. Consumers must marshal their
     * state changes and Camera2 detachment to the appropriate owner thread before releasing surfaces.
     */
    fun setOnRuntimeFailureListener(listener: ((PreviewRuntimeFailure) -> Unit)?) {
        runtimeFailureListener = listener
    }

    fun lastRuntimeFailure(): PreviewRuntimeFailure? = lastRuntimeFailure

    /**
     * Registers a child compositor swap that carried genuine sensor lineage. The child EGL surface
     * propagates this timestamp through SurfaceTexture; acknowledgement is emitted only after this
     * router also succeeds in swapping that exact buffer to the visible window.
     */
    fun registerChildFramePresented(source: Int, sensorTimestampNs: Long) {
        if (sensorTimestampNs <= 0L || !running) return
        val normalizedSource = normalizeSource(source)
        handler?.post {
            if (!running) return@post
            val lineage = pendingLineage[normalizedSource]
            lineage += sensorTimestampNs
            while (lineage.size > MAX_PENDING_LINEAGE) {
                val oldest = lineage.firstOrNull() ?: break
                lineage.remove(oldest)
            }
            emitIfComplete(normalizedSource, sensorTimestampNs)
        }
    }

    fun releaseAsync() {
        val state = synchronized(this) {
            if (releaseInProgress) return
            releaseInProgress = true
            running = false
            recordingWindowGate.retire()
            recordingWindowParkingAvailable = false
            boundOutput = null
            boundWidth = 0
            boundHeight = 0
            ReleaseState(handler, thread, CountDownLatch(1)).also { releaseLatch = it.latch }
        }
        val teardown = Runnable {
            try {
                runCatching { destroyGl() }
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

    private data class WindowSurfaceResult(
        val surface: EGLSurface?,
        val failure: StartFailure?,
        val detail: String
    )

    private fun initGl(output: Surface, width: Int, height: Int): StartResult {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) {
            return StartResult.failure(
                StartFailure.EGL_DISPLAY_UNAVAILABLE,
                "EGL_DEFAULT_DISPLAY is unavailable"
            )
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            return StartResult.failure(
                StartFailure.EGL_INITIALIZATION_FAILED,
                "eglInitialize failed error=0x${Integer.toHexString(EGL14.eglGetError())}"
            )
        }
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay,
                configAttributes,
                0,
                configs,
                0,
                1,
                configCount,
                0
            ) || configCount[0] <= 0 || configs[0] == null
        ) {
            return StartResult.failure(
                StartFailure.TEN_BIT_CONFIG_UNAVAILABLE,
                "No RGB10_A2 window EGL config is available"
            )
        }
        val config = configs[0] ?: return StartResult.failure(
            StartFailure.TEN_BIT_CONFIG_UNAVAILABLE,
            "EGL returned a null RGB10_A2 config"
        )
        if (!isStrictTenBitConfig(config)) {
            Log.e(TAG, "Rejected non-10-bit EGL config for stable visible output")
            return StartResult.failure(
                StartFailure.NON_TEN_BIT_CONFIG,
                "Selected EGL config does not provide at least 10/10/10/2 channels"
            )
        }
        eglConfig = config
        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )
        if (eglContext === EGL14.EGL_NO_CONTEXT) {
            return StartResult.failure(
                StartFailure.EGL_CONTEXT_CREATION_FAILED,
                "GLES2 context creation failed error=0x${Integer.toHexString(EGL14.eglGetError())}"
            )
        }
        val windowResult = createSizedWindowSurface(
            config = config,
            output = output,
            expectedWidth = width,
            expectedHeight = height
        )
        eglSurface = windowResult.surface ?: return StartResult.failure(
            windowResult.failure ?: StartFailure.UNKNOWN,
            windowResult.detail
        )

        // This is optional lifecycle capability, not a replacement output or a lower-bit config.
        // Devices whose existing strict config cannot park keep their established teardown path.
        val surfaceTypes = IntArray(1)
        if (EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_SURFACE_TYPE, surfaceTypes, 0) &&
            (surfaceTypes[0] and EGL14.EGL_PBUFFER_BIT) != 0
        ) {
            parkingSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            recordingWindowParkingAvailable = parkingSurface !== EGL14.EGL_NO_SURFACE
            if (!recordingWindowParkingAvailable) EGL14.eglGetError()
        }

        program = buildProgram() ?: return StartResult.failure(
            StartFailure.SHADER_PROGRAM_FAILED,
            "Strict output shader compilation or linking failed"
        )
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        uSdrShadowChromaCleanupLoc = GLES20.glGetUniformLocation(
            program,
            "uSdrShadowChromaCleanup"
        )

        GLES20.glGenTextures(SOURCE_COUNT, textureIds, 0)
        for (source in 0 until SOURCE_COUNT) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[source])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            val surfaceTexture = SurfaceTexture(textureIds[source]).also { texture ->
                texture.setDefaultBufferSize(width, height)
                texture.setOnFrameAvailableListener({
                    frameAvailable[source] = true
                    drawFrame()
                }, handler)
            }
            surfaceTextures[source] = surfaceTexture
            childOutputSurfaces[source] = Surface(surfaceTexture)
        }

        // Do not publish a synthetic startup buffer. SurfaceView fixed-size geometry reaches BLAST in
        // a separate transaction, so even a timed placeholder can carry the previous layout's size.
        // drawFrame() publishes only after the selected child has supplied a correctly-sized frame.
        return StartResult.success()
    }

    /**
     * SurfaceView applies setFixedSize through a BLAST transaction. Its callback can expose the new
     * logical size one frame before the native window does. Making EGL current in that interval
     * dequeues a layout-sized back buffer which is held until the first camera frame, then rejected by
     * BLAST. Verify the dequeued EGL surface itself and cancel, rather than swap, every stale buffer.
     */
    private fun createSizedWindowSurface(
        config: EGLConfig,
        output: Surface,
        expectedWidth: Int,
        expectedHeight: Int
    ): WindowSurfaceResult {
        var lastWidth = 0
        var lastHeight = 0
        var lastFailure = StartFailure.OUTPUT_GEOMETRY_MISMATCH
        var lastDetail = "PQ output geometry was not queried"
        repeat(WINDOW_SIZE_SETTLE_ATTEMPTS) { attempt ->
            val candidate = createPqWindowSurface(config, output)
            if (candidate === EGL14.EGL_NO_SURFACE) {
                return WindowSurfaceResult(
                    surface = null,
                    failure = StartFailure.PQ_WINDOW_SURFACE_FAILED,
                    detail = "BT.2020-PQ window creation failed error=0x" +
                        Integer.toHexString(EGL14.eglGetError())
                )
            }

            val current = EGL14.eglMakeCurrent(eglDisplay, candidate, candidate, eglContext)
            val width = IntArray(1)
            val height = IntArray(1)
            val widthKnown = current && EGL14.eglQuerySurface(
                eglDisplay,
                candidate,
                EGL14.EGL_WIDTH,
                width,
                0
            )
            val heightKnown = current && EGL14.eglQuerySurface(
                eglDisplay,
                candidate,
                EGL14.EGL_HEIGHT,
                height,
                0
            )
            lastWidth = width[0]
            lastHeight = height[0]
            if (widthKnown && heightKnown &&
                lastWidth == expectedWidth && lastHeight == expectedHeight
            ) {
                if (attempt > 0) {
                    Log.i(
                        TAG,
                        "Stable preview output geometry settled ${lastWidth}x${lastHeight} " +
                            "after ${attempt + 1} attempts"
                    )
                }
                return WindowSurfaceResult(
                    surface = candidate,
                    failure = null,
                    detail = "PQ output geometry verified ${lastWidth}x${lastHeight}"
                )
            }

            if (!current) {
                lastFailure = StartFailure.EGL_MAKE_CURRENT_FAILED
                lastDetail = "eglMakeCurrent failed error=0x" +
                    Integer.toHexString(EGL14.eglGetError())
            } else {
                lastFailure = StartFailure.OUTPUT_GEOMETRY_MISMATCH
                lastDetail = "Expected ${expectedWidth}x${expectedHeight}, got " +
                    "${lastWidth}x${lastHeight}"
            }

            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, candidate)
            if (!runCatching { output.isValid }.getOrDefault(false)) {
                return WindowSurfaceResult(
                    surface = null,
                    failure = StartFailure.PQ_WINDOW_SURFACE_FAILED,
                    detail = "Visible output became invalid while verifying PQ geometry"
                )
            }
            if (attempt + 1 < WINDOW_SIZE_SETTLE_ATTEMPTS) {
                SystemClock.sleep(WINDOW_SIZE_SETTLE_RETRY_MS)
            }
        }
        Log.w(
            TAG,
            "Stable preview output geometry did not settle: expected=" +
                "${expectedWidth}x${expectedHeight} actual=${lastWidth}x${lastHeight}"
        )
        return WindowSurfaceResult(surface = null, failure = lastFailure, detail = lastDetail)
    }

    /** Native 10-bit BT.2020-PQ is mandatory; an untagged/8-bit retry would be a silent downgrade. */
    private fun createPqWindowSurface(config: EGLConfig, output: Surface): EGLSurface =
        runCatching {
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                output,
                intArrayOf(
                    EGL_GL_COLORSPACE_KHR,
                    EGL_GL_COLORSPACE_BT2020_PQ_EXT,
                    EGL14.EGL_NONE
                ),
                0
            )
        }.onFailure {
            Log.e(TAG, "Native BT.2020-PQ visible surface creation failed", it)
        }.getOrDefault(EGL14.EGL_NO_SURFACE)

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

    private fun drawFrame() {
        if (!running) return
        if (eglContext === EGL14.EGL_NO_CONTEXT) return
        if (recordingWindowGate.isWindowSuspended() && eglSurface !== EGL14.EGL_NO_SURFACE) {
            if (!parkVisibleOutput()) return
        }
        val drawSurface = if (recordingWindowGate.mayPresent()) eglSurface else parkingSurface
        if (drawSurface === EGL14.EGL_NO_SURFACE) return
        val selected = normalizeSource(selectedSource)
        if (!EGL14.eglMakeCurrent(eglDisplay, drawSurface, drawSurface, eglContext)) {
            val eglError = EGL14.eglGetError()
            if (recoverExpectedWindowRetirement(eglError)) return
            reportRuntimeFailure(
                PreviewRuntimeOperation.EGL_MAKE_CURRENT,
                eglError,
                "Strict visible output eglMakeCurrent failed source=$selected " +
                    "error=0x${Integer.toHexString(eglError)}"
            )
            return
        }
        var selectedUpdated = false
        for (source in 0 until SOURCE_COUNT) {
            if (!frameAvailable[source]) continue
            val texture = surfaceTextures[source] ?: continue
            val updated = runCatching {
                texture.updateTexImage()
                texture.getTransformMatrix(textureMatrices[source])
                latchedTimestampNs[source] = texture.timestamp
                true
            }.getOrDefault(false)
            frameAvailable[source] = false
            if (!updated) continue
            frameReady[source] = true
            if (source == selected) selectedUpdated = true
        }
        if (!recordingWindowGate.mayPresent()) return
        // Focus can return on the main thread while child buffers drain on the parking surface.
        // Never draw there and then swap a different, undrawn window. The queued focus callback
        // will request a fresh draw bound to the retained visible surface.
        if (drawSurface !== eglSurface) return
        if (!frameReady[selected] || (!selectedUpdated && !selectionDirty)) return
        selectionDirty = false

        GLES20.glViewport(0, 0, boundWidth.coerceAtLeast(1), boundHeight.coerceAtLeast(1))
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        quadVertices.position(2)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, textureMatrices[selected], 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[selected])
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform2f(
            uTexelSizeLoc,
            1f / boundWidth.coerceAtLeast(1).toFloat(),
            1f / boundHeight.coerceAtLeast(1).toFloat()
        )
        // Only the SDR monitor needs near-black chroma cleanup. HDR/NDR remains byte-for-byte on
        // its own child compositor result, avoiding any gamut, transfer or bit-depth reinterpretation.
        GLES20.glUniform1f(
            uSdrShadowChromaCleanupLoc,
            if (selected == SOURCE_SDR) 1f else 0f
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        // Focus may have changed while this same-size blit was queued. Do not enter a new
        // potentially blocking visible-window swap after the main thread reports occlusion.
        if (!recordingWindowGate.mayPresent()) return
        val presented = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!presented) {
            val eglError = EGL14.eglGetError()
            if (recoverExpectedWindowRetirement(eglError)) return
            val failedTimestampNs = latchedTimestampNs[selected]
            reportRuntimeFailure(
                PreviewRuntimeOperation.EGL_SWAP_BUFFERS,
                eglError,
                "Strict visible output eglSwapBuffers failed source=$selected " +
                    "timestampNs=$failedTimestampNs " +
                    "error=0x${Integer.toHexString(eglError)}"
            )
            return
        }
        val presentedTimestampNs = latchedTimestampNs[selected]
        if (presentedTimestampNs > 0L) {
            val presentationElapsedNs = SystemClock.elapsedRealtimeNanos()
            lastSuccessfulSwapTimestampNs[selected] = presentedTimestampNs
            val successfulSwaps = successfulSwapElapsedByTimestamp[selected]
            successfulSwaps[presentedTimestampNs] = presentationElapsedNs
            while (successfulSwaps.size > MAX_PENDING_LINEAGE) {
                val oldest = successfulSwaps.entries.firstOrNull()?.key ?: break
                successfulSwaps.remove(oldest)
            }
            emitIfComplete(selected, presentedTimestampNs)
        }
    }

    private fun recoverExpectedWindowRetirement(eglError: Int): Boolean {
        // Only an explicitly requested display retirement can explain a lost native window.
        // EGL_CONTEXT_LOST and every unsolicited render error still fail the original strict gate.
        if (!recordingWindowGate.isWindowSuspended() ||
            (eglError != EGL14.EGL_BAD_SURFACE && eglError != EGL14.EGL_BAD_NATIVE_WINDOW)
        ) return false
        return parkVisibleOutput()
    }

    private fun reportRuntimeFailure(
        operation: PreviewRuntimeOperation,
        eglError: Int,
        detail: String
    ) {
        val failure = PreviewRuntimeFailure(
            component = PreviewRuntimeComponent.OUTPUT_ROUTER,
            operation = operation,
            eglError = eglError,
            detail = detail
        )
        val claimed = synchronized(this) {
            if (!running || !runtimeFailureGate.tryClaimActiveFailure()) {
                false
            } else {
                // Fail closed before notifying the controller: no child surface can be returned and
                // no later source selection can continue presenting an unproven strict route.
                running = false
                recordingWindowGate.retire()
                lastRuntimeFailure = failure
                lastStartOutcome = StartResult.failure(StartFailure.RUNTIME_EGL_FAILURE, detail)
                true
            }
        }
        if (!claimed) return

        Log.e(TAG, detail)
        val listener = runtimeFailureListener ?: return
        runCatching { listener(failure) }
            .onFailure { Log.e(TAG, "Strict preview runtime-failure listener failed", it) }
    }

    private fun emitIfComplete(source: Int, sensorTimestampNs: Long) {
        if (!recordingWindowGate.mayPresent()) return
        if (sensorTimestampNs <= 0L || sensorTimestampNs == lastEmittedTimestampNs[source]) return
        val presentationElapsedNs = successfulSwapElapsedByTimestamp[source][sensorTimestampNs] ?: return
        val lineage = pendingLineage[source]
        if (!lineage.remove(sensorTimestampNs)) return
        val output = boundOutput ?: return
        successfulSwapElapsedByTimestamp[source].remove(sensorTimestampNs)
        lastEmittedTimestampNs[source] = sensorTimestampNs
        framePresentedListener?.invoke(sensorTimestampNs, output, presentationElapsedNs)
    }

    private fun destroyGl() {
        for (source in 0 until SOURCE_COUNT) {
            runCatching { childOutputSurfaces[source]?.release() }
            childOutputSurfaces[source] = null
            surfaceTextures[source]?.let { texture ->
                runCatching { texture.setOnFrameAvailableListener(null) }
                runCatching { texture.release() }
            }
            surfaceTextures[source] = null
            frameAvailable[source] = false
            frameReady[source] = false
            textureMatrices[source].fill(0f)
            latchedTimestampNs[source] = 0L
            lastSuccessfulSwapTimestampNs[source] = 0L
            successfulSwapElapsedByTimestamp[source].clear()
            lastEmittedTimestampNs[source] = 0L
            pendingLineage[source].clear()
        }
        selectionDirty = false
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            val cleanupSurface = if (eglSurface !== EGL14.EGL_NO_SURFACE) eglSurface else parkingSurface
            if (eglContext !== EGL14.EGL_NO_CONTEXT && cleanupSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, cleanupSurface, cleanupSurface, eglContext)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureIds.any { it != 0 }) {
                    GLES20.glDeleteTextures(SOURCE_COUNT, textureIds, 0)
                }
            }
            program = 0
            textureIds.fill(0)
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (parkingSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, parkingSurface)
                parkingSurface = EGL14.EGL_NO_SURFACE
            }
            eglConfig = null
            recordingWindowParkingAvailable = false
            if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun buildProgram(): Int? {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: run {
            GLES20.glDeleteShader(vertex)
            return null
        }
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertex)
        GLES20.glAttachShader(linked, fragment)
        GLES20.glLinkProgram(linked)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Stable preview output link failed: ${GLES20.glGetProgramInfoLog(linked)}")
            GLES20.glDeleteProgram(linked)
            return null
        }
        return linked
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "Stable preview output shader failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun normalizeSource(source: Int): Int =
        if (source == SOURCE_HDR) SOURCE_HDR else SOURCE_SDR

    companion object {
        private const val TAG = "PersistentPreviewOutput"
        private const val SOURCE_COUNT = 2
        private const val STRIDE_BYTES = 4 * java.lang.Float.BYTES
        // SurfaceView fixed-size changes are committed asynchronously by BLAST. On a thermally
        // throttled device that transaction has been observed to take longer than the former
        // 16 x 16 ms window, leaving the strict 10-bit route stuck on the previous 1920x1440
        // buffer while the selected stream is 1280x960. Keep this wait bounded and off-main, but
        // allow one full second for the real window geometry to become authoritative. No
        // mismatched buffer is ever swapped and no lower-precision fallback is permitted.
        private const val START_TIMEOUT_MS = 3_000L
        private const val RELEASE_TIMEOUT_MS = 800L
        private const val WINDOW_SIZE_SETTLE_ATTEMPTS = 64
        private const val WINDOW_SIZE_SETTLE_RETRY_MS = 16L
        private const val MAX_PENDING_LINEAGE = 16
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val SOURCE_SDR = 0
        const val SOURCE_HDR = 1

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uSdrShadowChromaCleanup;
            varying vec2 vTexCoord;

            float srgbToLinear(float c) {
                c = clamp(c, 0.0, 1.0);
                return c <= 0.04045
                    ? c / 12.92
                    : pow((c + 0.055) / 1.055, 2.4);
            }

            vec3 rec709ToBt2020(vec3 linear) {
                return mat3(
                    0.6274040, 0.0690970, 0.0163916,
                    0.3292820, 0.9195400, 0.0880132,
                    0.0433136, 0.0113612, 0.8955950
                ) * linear;
            }

            float pqEncodeNits(float nits) {
                const float m1 = 0.1593017578125;
                const float m2 = 78.84375;
                const float c1 = 0.8359375;
                const float c2 = 18.8515625;
                const float c3 = 18.6875;
                float p = pow(clamp(nits / 10000.0, 0.0, 1.0), m1);
                return pow((c1 + c2 * p) / (1.0 + c3 * p), m2);
            }

            vec4 prepareForOutput(vec3 encoded, float alpha) {
                // HDR child frames are already encoded BT.2020 PQ and pass through unchanged.
                if (uSdrShadowChromaCleanup < 0.5) {
                    return vec4(clamp(encoded, 0.0, 1.0), alpha);
                }
                vec3 linear = vec3(
                    srgbToLinear(encoded.r),
                    srgbToLinear(encoded.g),
                    srgbToLinear(encoded.b)
                );
                vec3 bt2020 = max(rec709ToBt2020(linear), vec3(0.0));
                vec3 pq = vec3(
                    pqEncodeNits(bt2020.r * 100.0),
                    pqEncodeNits(bt2020.g * 100.0),
                    pqEncodeNits(bt2020.b * 100.0)
                );
                return vec4(pq, alpha);
            }

            vec3 toYCoCg(vec3 rgb) {
                float y = dot(rgb, vec3(0.25, 0.50, 0.25));
                float co = rgb.r - rgb.b;
                float cg = rgb.g - 0.5 * (rgb.r + rgb.b);
                return vec3(y, co, cg);
            }

            vec3 fromYCoCg(vec3 ycocg) {
                float r = ycocg.x + 0.5 * ycocg.y - 0.5 * ycocg.z;
                float g = ycocg.x + 0.5 * ycocg.z;
                float b = ycocg.x - 0.5 * ycocg.y - 0.5 * ycocg.z;
                return vec3(r, g, b);
            }

            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                if (uSdrShadowChromaCleanup < 0.5) {
                    gl_FragColor = prepareForOutput(source.rgb, source.a);
                    return;
                }

                // Cross-neighbour YCoCg filter: preserve the centre luma exactly and modify only
                // chroma in flat shadows. Luma edges, fine texture and every mid/highlight remain
                // untouched, so suppressing dark-room colour speckle cannot soften visible detail.
                vec3 centre = toYCoCg(source.rgb);
                vec3 left = toYCoCg(texture2D(uTexture, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb);
                vec3 right = toYCoCg(texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb);
                vec3 up = toYCoCg(texture2D(uTexture, vTexCoord - vec2(0.0, uTexelSize.y)).rgb);
                vec3 down = toYCoCg(texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y)).rgb);
                float lumaSpan = max(
                    max(abs(left.x - centre.x), abs(right.x - centre.x)),
                    max(abs(up.x - centre.x), abs(down.x - centre.x))
                );
                float flatShadow =
                    (1.0 - smoothstep(0.008, 0.055, lumaSpan)) *
                    (1.0 - smoothstep(0.10, 0.42, centre.x));
                vec2 localChroma = (left.yz + right.yz + up.yz + down.yz) * 0.25;
                float isolatedColour = length(centre.yz - localChroma);
                float speckle = smoothstep(0.003, 0.100, isolatedColour);
                float blendWeight = flatShadow * mix(0.34, 0.92, speckle);
                vec2 cleanChroma = mix(centre.yz, localChroma, blendWeight);

                // A near-black neutral deadband removes coherent sub-noise-floor green/magenta bias.
                // Coherent saturated shadow colour is explicitly protected by the true-colour gate.
                float nearBlack = 1.0 - smoothstep(0.015, 0.110, centre.x);
                float trueColour = smoothstep(0.080, 0.220, length(localChroma));
                float neutralDeadband = nearBlack * flatShadow * (1.0 - trueColour) * 0.82;
                cleanChroma *= 1.0 - neutralDeadband;

                vec3 cleaned = fromYCoCg(vec3(centre.x, cleanChroma));
                gl_FragColor = prepareForOutput(clamp(cleaned, 0.0, 1.0), source.a);
            }
        """
    }
}
