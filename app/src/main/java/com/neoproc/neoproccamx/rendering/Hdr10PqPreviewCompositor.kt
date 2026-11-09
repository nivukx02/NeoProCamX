package com.neoproc.neoproccamx.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.recording.NdrWarmSkinRedTameFlag
import android.os.Build
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Builds the project-owned NDR texture-coordinate transform with the same operation order as
 * [com.neoproc.neoproccamx.ui.VulkanPreviewTransformEngine]: inverse display rotation first, then
 * display-space X mirroring. [samplingRotationDegrees] is already the inverse rotation resolved by
 * [RawDebayerPreviewTransformPolicy].
 */
internal object ProjectOwnedNdrPreviewOrientationMatrix {
    fun resolve(samplingRotationDegrees: Int, mirrored: Boolean): FloatArray {
        val rotation = ((samplingRotationDegrees % 360) + 360) % 360
        val cosine: Float
        val sine: Float
        when (rotation) {
            90 -> { cosine = 0f; sine = 1f }
            180 -> { cosine = -1f; sine = 0f }
            270 -> { cosine = 0f; sine = -1f }
            else -> { cosine = 1f; sine = 0f }
        }
        val mirrorX = if (mirrored) -1f else 1f
        // Column-major Mx * R. This is the orientation-only portion of the working Vulkan matrix.
        return floatArrayOf(
            cosine * mirrorX,
            sine,
            -sine * mirrorX,
            cosine
        )
    }
}

internal enum class NdrVisiblePreviewSource {
    CAMERA_MONITOR,
    PROJECT_OWNED,
    HOLD_LAST_VALIDATED
}

/** Pure presentation authority: an armed RAW/NDR route can never expose its Camera2 drain queue. */
internal object NdrVisiblePreviewSourcePolicy {
    fun resolve(
        projectOwnedRequested: Boolean,
        routeCommitted: Boolean,
        projectOwnedFrameReady: Boolean,
        latchedEpoch: Long,
        currentEpoch: Long
    ): NdrVisiblePreviewSource {
        if (!projectOwnedRequested) return NdrVisiblePreviewSource.CAMERA_MONITOR
        return if (routeCommitted && projectOwnedFrameReady && latchedEpoch == currentEpoch) {
            NdrVisiblePreviewSource.PROJECT_OWNED
        } else {
            NdrVisiblePreviewSource.HOLD_LAST_VALIDATED
        }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR10 PQ PREVIEW COMPOSITOR  (WYSIWYG live NDR10+/NIVUKX look)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * An isolated `SurfaceTexture → GLES3 → SurfaceView` stage that sits in front of
 * the VISIBLE preview only, gated by [Hdr10PqPreviewPolicy]. It samples the
 * camera's applied external texture and keeps that queue draining while the project-owned
 * RAW-derived NDR pipeline warms. Once RAW/NDR ownership is requested, no Camera2 proxy buffer is
 * allowed to swap into the visible NDR child. Camera2 HLG/PQ is never admitted to the proprietary
 * grade. Validated RAW-derived frames use the canonical NDR10+/NIVUKX engine the record path bakes
 * into the file (the GLSL-ES port of
 * com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine), presenting genuine
 * nit-accurate PQ so the monitor image matches the recording (WYSIWYG).
 *
 * Isolation / safety (mirrors [PreviewGlCompositor]):
 *  • Own EGL display/context (unshared) on its own thread — never touches the
 *    encoder / Vulkan record GL state.
 *  • Strict PQ output: a verified 10-bit RGBA1010102 window surface tagged
 *    EGL_GL_COLORSPACE_BT2020_PQ. [Hdr10PqPreviewSdrOutputFlag] retains the historical SDR code for
 *    diagnostics but rejects every attempt to enable it in production.
 *  • Frame-driven (one draw per camera frame) — self-paces to the camera cadence.
 *  • Never binds the encoder surface, so it cannot perturb the 25fps record path.
 *
 * DEVICE-GATED: [start] returns false on any EGL/GL failure. That failure means native PQ preview is
 * unsupported for the current surface; it does not authorize a direct SDR/Camera2 replacement.
 */
internal class Hdr10PqPreviewCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false
    @Volatile private var releaseInProgress = false
    private var releaseLatch: CountDownLatch? = null
    // The inactive member of a persistent SDR/HDR pair drains camera buffers without executing the
    // full HDR shader or swapping an output buffer. Warmup re-enables presentation before routing.
    @Volatile private var presentationEnabled = true
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var modeCode = MODE_NDR10_PLUS
    @Volatile private var targetPeakNits = NdrNivukxDeliveryEngine.targetPeakNits(
        NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
    ).toFloat()
    @Volatile private var monitorPeakNits = 1_000f

    private var boundOutput: Surface? = null
    private var boundWidth = 0
    private var boundHeight = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uTexelLoc = 0
    private var uModeLoc = 0
    private var uInputTransferLoc = 0
    private var uInputPrimariesLoc = 0
    private var uInputRangeLoc = 0
    private var uApplyNdrGradeLoc = 0
    private var uSdrOutputLoc = 0
    private var uWarmSkinRedTameLoc = 0
    private var uTargetPeakNitsLoc = 0
    private var uMonitorPeakNitsLoc = 0
    private var uProjectOwnedInputLoc = 0
    private var uRawOrientLoc = 0
    // A second ordinary external-texture program is retained when Camera2 uses GL_EXT_YUV_target.
    // The project-owned RAW bridge publishes RGB10_A2 PQ, not YUV, so it must never be sampled by the
    // explicit-YUV program selected for an opaque Camera2 buffer.
    private var projectOwnedProgram = 0
    private var projectAPositionLoc = 0
    private var projectATexCoordLoc = 0
    private var projectUTexMatrixLoc = 0
    private var projectUTextureLoc = 0
    private var projectUTexelLoc = 0
    private var projectUModeLoc = 0
    private var projectUInputTransferLoc = 0
    private var projectUInputPrimariesLoc = 0
    private var projectUInputRangeLoc = 0
    private var projectUApplyNdrGradeLoc = 0
    private var projectUSdrOutputLoc = 0
    private var projectUWarmSkinRedTameLoc = 0
    private var projectUTargetPeakNitsLoc = 0
    private var projectUMonitorPeakNitsLoc = 0
    private var projectUProjectOwnedInputLoc = 0
    private var projectURawOrientLoc = 0
    // Snapshot of Hdr10PqPreviewSdrOutputFlag for this EGL session (read at start(), consistent for the
    // life of the surface — a mid-session flip forces a release+rebind via the flag's onChanged).
    private var sdrOutput = false
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    private var updatedFrameCount = 0
    private val renderGate = LatestFrameRenderGate()
    private val retainedFrameRedrawRequested = AtomicBoolean(false)
    private val renderRunnable = Runnable {
        // Release before drawing so a state update arriving during eglSwapBuffers can schedule one
        // follow-up without allowing a callback burst to build an unbounded Handler backlog.
        renderGate.onDispatchStarted()
        // Consume after releasing the gate: a request racing just before release is included here;
        // one racing after consumption observes an open gate and schedules the follow-up draw.
        val redrawRetainedFrame = retainedFrameRedrawRequested.getAndSet(false)
        drawFrame(redrawRetainedFrame)
    }
    // Dedicated producer queue for the project-owned RAW -> ACEScg FP16/Neo-Scene-Log10 -> NDR PQ
    // bridge. Camera2 keeps sole ownership of [inputSurface]; the RAW bridge keeps sole ownership of
    // this queue, avoiding two producers racing on one BufferQueue.
    @Volatile private var projectOwnedInputSurface: Surface? = null
    private var projectOwnedTextureId = 0
    private var projectOwnedSurfaceTexture: SurfaceTexture? = null
    private val projectOwnedTexMatrix = FloatArray(16)
    private var projectOwnedFrameAvailable = false
    @Volatile private var projectOwnedFrameReady = false
    @Volatile private var projectOwnedUpdatedFrameCount = 0
    @Volatile private var projectOwnedLatchedTimestampNs = 0L
    @Volatile private var projectOwnedLatchedEpoch = NO_PROJECT_OWNED_EPOCH
    @Volatile private var projectOwnedLatchedColorProof: ProjectOwnedNdrColorProof? = null
    private val projectOwnedProofStabilityGate = ProjectOwnedNdrProofStabilityGate(
        requiredConsecutiveFrames = MIN_STABLE_PROJECT_OWNED_FRAMES
    )
    @Volatile private var projectOwnedCompatibleProofCount = 0
    @Volatile private var projectOwnedLastAcceptedColorProof: ProjectOwnedNdrColorProof? = null
    @Volatile private var projectOwnedPresentAllowed = false
    @Volatile private var projectOwnedPresentationEpoch = 0L
    @Volatile private var surfaceHasPresentedProjectOwnedFrame = false
    private val projectOwnedAdmission = ProjectOwnedNdrFrameAdmission()
    @Volatile private var projectOwnedOrient = floatArrayOf(1f, 0f, 0f, 1f)
    @Volatile private var framePresentedListener: ((Long, Surface, Long) -> Unit)? = null
    @Volatile private var inputFrameListener: ((Long, Long?) -> Unit)? = null
    @Volatile private var projectOwnedFramePresentedListener: ((Long, Long) -> Unit)? = null
    @Volatile private var runtimeFailureListener: ((PreviewRuntimeFailure) -> Unit)? = null
    @Volatile private var lastRuntimeFailure: PreviewRuntimeFailure? = null
    private val runtimeFailureGate = PreviewRuntimeFailureGate()
    @Volatile private var appliedInputProfile: Long? = null
    private var inputTransferCode = NdrInputTransfer.SDR.shaderCode
    private var inputPrimariesCode = NdrInputPrimaries.BT709.ordinal
    private var inputRangeCode = NdrInputRange.UNKNOWN.shaderCode
    private var inputNdrGradeCode = NdrInputRenderPath.TRANSPORT_BYPASS.shaderCode
    private var explicitYuvDecodeActive = false
    private var lastInputTransportSignature = ""
    private var projectOwnedTransportLogged = false
    private var lastProjectOwnedAdmissionSignature = ""

    private val quadVertices: FloatBuffer = floatBuffer(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )

    /**
     * (Re)start rendering [output] at [width]×[height] for [mode] (0 = NDR10+, 1 = NIVUKX).
     * Returns true if running; false on any EGL/GL failure. Failure is strict and must not be
     * interpreted by callers as permission to expose a direct SDR/Camera2 preview.
     */
    fun start(
        output: Surface,
        width: Int,
        height: Int,
        mode: Int,
        appliedProfile: Long? = null,
        peakPlan: NdrPeakCapabilityPolicy.Plan? = null,
        publishInitialBlack: Boolean = false
    ): Boolean {
        if (releaseInProgress) return false
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        appliedInputProfile = appliedProfile
        val requestedSdrOutput = Hdr10PqPreviewSdrOutputFlag.enabled
        if (running && sdrOutput != requestedSdrOutput) {
            release()
        }
        // Snapshot the output-transfer mode for this session (SDR-display 8-bit vs 10-bit BT.2020-PQ).
        sdrOutput = requestedSdrOutput
        if (running && boundOutput === output && boundWidth == w && boundHeight == h) {
            setMode(mode)
            peakPlan?.let(::setPeakPlan)
            return true
        }
        if (running && boundOutput == null && boundWidth == w && boundHeight == h) {
            setMode(mode)
            peakPlan?.let(::setPeakPlan)
            return attachOutput(output, w, h)
        }
        release()
        if (releaseInProgress) return false
        modeCode = mode
        setPeakPlan(
            peakPlan ?: NdrPeakCapabilityPolicy.resolve(
                mode = NdrPeakCapabilityPolicy.modeForCode(mode),
                displayMaxLuminanceNits = null
            )
        )
        // Run the visible NDR/HDR preview GL render thread at URGENT_DISPLAY (real Process nice) so the
        // viewfinder is never preempted by background analysis/AI. Scheduling only — no pixel change.
        val t = HandlerThread("Hdr10PqPreviewGL", HotPathScheduling.urgentDisplayPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching {
                initGl(
                    output = output,
                    width = w,
                    height = h,
                    publishInitialBlack = publishInitialBlack
                )
            }
                .onFailure { Log.w(TAG, "Hdr10PqPreviewCompositor init failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "Hdr10PqPreviewCompositor start aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        lastRuntimeFailure = null
        runtimeFailureGate.armAfterSuccessfulStart()
        running = true
        boundOutput = output
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "Hdr10PqPreviewCompositor started ${w}x${h} mode=$mode")
        return true
    }

    /**
     * Builds and retains the HDR camera input on a 1x1 pbuffer. Camera2 may keep this Surface in a
     * persistent session and feed it briefly before [start] atomically acquires the visible window.
     */
    fun prepareInput(
        width: Int,
        height: Int,
        mode: Int,
        appliedProfile: Long? = null,
        peakPlan: NdrPeakCapabilityPolicy.Plan? = null
    ): Boolean {
        if (releaseInProgress) return false
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        appliedInputProfile = appliedProfile
        val requestedSdrOutput = Hdr10PqPreviewSdrOutputFlag.enabled
        if (running && sdrOutput != requestedSdrOutput) {
            release()
        }
        sdrOutput = requestedSdrOutput
        if (running && boundOutput == null && boundWidth == w && boundHeight == h) {
            setMode(mode)
            peakPlan?.let(::setPeakPlan)
            return inputSurface != null
        }
        if (running) {
            release()
            if (releaseInProgress) return false
        }
        modeCode = mode
        setPeakPlan(
            peakPlan ?: NdrPeakCapabilityPolicy.resolve(
                mode = NdrPeakCapabilityPolicy.modeForCode(mode),
                displayMaxLuminanceNits = null
            )
        )
        val t = HandlerThread("Hdr10PqPreviewGL", HotPathScheduling.urgentDisplayPriority()).also { it.start() }
        val hnd = Handler(t.looper)
        thread = t
        handler = hnd
        val latch = CountDownLatch(1)
        var ok = false
        hnd.post {
            ok = runCatching { initGl(output = null, width = w, height = h) }
                .onFailure { Log.w(TAG, "Hdr10PqPreviewCompositor input prewarm failed: ${it.message}") }
                .getOrDefault(false)
            latch.countDown()
        }
        val completed = runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!completed || !ok) {
            Log.w(TAG, "Hdr10PqPreviewCompositor input prewarm aborted (completed=$completed ok=$ok)")
            release()
            return false
        }
        lastRuntimeFailure = null
        runtimeFailureGate.armAfterSuccessfulStart()
        running = true
        boundOutput = null
        boundWidth = w
        boundHeight = h
        Log.i(TAG, "Hdr10PqPreviewCompositor input prewarmed ${w}x${h} mode=$mode")
        return inputSurface != null
    }

    fun isOutputAttached(): Boolean = running && boundOutput != null

    /** Moves the live HDR context off the SurfaceView without dropping its warm camera texture. */
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
                if (pbuffer !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                latch.countDown()
                return@post
            }
            val previous = eglSurface
            eglSurface = pbuffer
            if (previous !== EGL14.EGL_NO_SURFACE && previous !== pbuffer) {
                EGL14.eglDestroySurface(eglDisplay, previous)
            }
            boundOutput = null
            ok = true
            latch.countDown()
        }
        return runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false) && ok
    }

    /** The surface the camera should target for preview. Valid only while running. */
    fun inputSurface(): Surface? = inputSurface.takeIf { running }

    /**
     * Private RGB10_A2 producer queue for the project-owned sensor-linear NDR bridge. Camera2 never
     * targets this Surface. Keeping it separate from [inputSurface] is the ownership invariant that
     * makes the visible NDR frame genuinely RAW-derived without disconnecting the warm Camera2 input.
     */
    fun projectOwnedNdrInputSurface(): Surface? = projectOwnedInputSurface.takeIf { running }

    fun projectOwnedNdrInputWidth(): Int = boundWidth

    fun projectOwnedNdrInputHeight(): Int = boundHeight

    fun isRunning(): Boolean = running

    fun isReleasing(): Boolean = releaseInProgress

    fun setFramePresentationEnabled(enabled: Boolean) {
        val changed = presentationEnabled != enabled
        presentationEnabled = enabled
        if (changed && enabled) requestRender()
    }

    fun setOnFramePresentedListener(listener: ((Long, Surface, Long) -> Unit)?) {
        framePresentedListener = listener
    }

    /** Camera-input readiness, including while this compositor is warming on its pbuffer. */
    fun setOnInputFrameListener(listener: ((Long, Long?) -> Unit)?) {
        inputFrameListener = listener
    }

    /** Successful child-surface swap proof required before persistent routing exposes RAW-derived NDR. */
    fun setOnProjectOwnedFramePresentedListener(listener: ((Long, Long) -> Unit)?) {
        projectOwnedFramePresentedListener = listener
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

    /** Swap the delivery mode (NDR10+ ↔ NIVUKX) with no EGL rebuild. */
    fun setMode(mode: Int) {
        modeCode = mode
        val baseline = NdrNivukxDeliveryEngine.targetPeakNits(
            NdrPeakCapabilityPolicy.modeForCode(mode)
        ).toFloat()
        targetPeakNits = baseline
        monitorPeakNits = monitorPeakNits
            .coerceIn(NdrPeakCapabilityPolicy.DIFFUSE_WHITE_NITS, targetPeakNits)
        requestRender()
    }

    /** Updates content-master and physical monitor peaks without rebuilding EGL. */
    fun setPeakPlan(plan: NdrPeakCapabilityPolicy.Plan) {
        val baseline = NdrNivukxDeliveryEngine.targetPeakNits(
            NdrPeakCapabilityPolicy.modeForCode(modeCode)
        ).toFloat()
        targetPeakNits = plan.contentPeakNits.coerceIn(baseline, baseline)
        monitorPeakNits = plan.monitorPeakNits
            .coerceIn(NdrPeakCapabilityPolicy.DIFFUSE_WHITE_NITS, targetPeakNits)
        requestRender()
    }

    /** Updates the Camera2 profile fallback used only when a buffer reports DATASPACE_UNKNOWN. */
    fun setInputProfile(appliedProfile: Long?) {
        appliedInputProfile = appliedProfile
    }

    fun setProjectOwnedNdrPresentEnabled(enabled: Boolean) {
        if (enabled) {
            projectOwnedAdmission.setEnabled(true)
            projectOwnedPresentAllowed = true
        } else {
            projectOwnedPresentAllowed = false
            projectOwnedAdmission.setEnabled(false)
            projectOwnedFrameReady = false
            projectOwnedUpdatedFrameCount = 0
            projectOwnedLatchedTimestampNs = 0L
            projectOwnedLatchedEpoch = NO_PROJECT_OWNED_EPOCH
            resetProjectOwnedColorProofState()
            surfaceHasPresentedProjectOwnedFrame = false
            requestRender()
        }
    }

    /** Begins a two-phase visible RAW epoch while the outgoing Camera2 frame remains available. */
    fun beginProjectOwnedNdrPresentationEpoch(epoch: Long) {
        projectOwnedAdmission.begin(epoch)
        projectOwnedPresentationEpoch = epoch
        // Retain the last completed RAW frame until the first exact frame from this epoch arrives.
        // The timestamp gate below prevents the retained frame from being mistaken for current warmup.
        projectOwnedUpdatedFrameCount = 0
        resetProjectOwnedColorProofState()
        requestRender()
    }

    fun commitProjectOwnedNdrPresentationEpoch(epoch: Long): Boolean {
        if (!projectOwnedPresentAllowed || epoch != projectOwnedPresentationEpoch) return false
        if (!projectOwnedAdmission.commit(epoch)) return false
        requestRender()
        return true
    }

    /**
     * Registers the exact sensor timestamp before the bridge swaps its PQ buffer. The SurfaceTexture
     * callback accepts only this timestamp+epoch pair, so an in-flight frame from a previous fast mode
     * switch can never flash red/monochrome into the newly-selected NDR profile.
     */
    fun registerProjectOwnedNdrSubmission(
        epoch: Long,
        sensorTimestampNs: Long,
        colorProof: ProjectOwnedNdrColorProof
    ): Boolean {
        if (!running || !projectOwnedPresentAllowed) return false
        return projectOwnedAdmission.register(epoch, sensorTimestampNs, colorProof)
    }

    fun cancelProjectOwnedNdrSubmission(epoch: Long, sensorTimestampNs: Long) {
        projectOwnedAdmission.cancel(epoch, sensorTimestampNs)
    }

    fun clearProjectOwnedNdrPresent() {
        projectOwnedPresentAllowed = false
        projectOwnedAdmission.setEnabled(false)
        projectOwnedFrameReady = false
        projectOwnedUpdatedFrameCount = 0
        projectOwnedLatchedTimestampNs = 0L
        projectOwnedLatchedEpoch = NO_PROJECT_OWNED_EPOCH
        resetProjectOwnedColorProofState()
        surfaceHasPresentedProjectOwnedFrame = false
        requestRender()
    }

    fun setProjectOwnedNdrOrientation(rotationDegrees: Int, mirrored: Boolean) {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val nextOrientation = ProjectOwnedNdrPreviewOrientationMatrix.resolve(
            samplingRotationDegrees = normalizedRotation,
            mirrored = mirrored
        )
        if (projectOwnedOrient.contentEquals(nextOrientation)) return
        projectOwnedOrient = nextOrientation
        Log.i(
            TAG,
            "NDR project-owned orientation samplingRotation=$normalizedRotation " +
                "mirrored=$mirrored matrix=${nextOrientation.contentToString()}"
        )
        requestRender()
    }

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
        state.handler?.removeCallbacks(renderRunnable)
        renderGate.cancelPendingDispatch()
        retainedFrameRedrawRequested.set(false)
        val teardown = Runnable {
            try {
                if (state.handler != null) {
                    runCatching { destroyGl() }
                } else {
                    runCatching { inputSurface?.release() }
                    inputSurface = null
                    runCatching { projectOwnedInputSurface?.release() }
                    projectOwnedInputSurface = null
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

    fun release() {
        val renderThread = synchronized(this) { thread }
        releaseAsync()
        val completion = synchronized(this) { releaseLatch }
        if (Thread.currentThread() !== renderThread) {
            runCatching { completion?.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    private data class ReleaseState(
        val handler: Handler?,
        val thread: HandlerThread?,
        val latch: CountDownLatch
    )

    // ── Render-thread only ────────────────────────────────────────────────────

    private fun initGl(
        output: Surface?,
        width: Int,
        height: Int,
        publishInitialBlack: Boolean = false
    ): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        // The historical SDR branch remains source-compatible for diagnostic builds. Product policy
        // keeps it disabled, so NDR requests require RGB10_A2 and fail closed when unavailable.
        val configAttribs = if (sdrOutput) {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_NONE
            )
        }
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            Log.w(TAG, "No EGL config available (sdrOutput=$sdrOutput)")
            return false
        }
        val selectedConfig = configs[0] ?: return false
        if (!sdrOutput && !isStrictTenBitConfig(selectedConfig)) {
            Log.e(TAG, "Rejected non-10-bit EGL config for native NDR preview")
            return false
        }
        eglConfig = selectedConfig

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext === EGL14.EGL_NO_CONTEXT) return false

        eglSurface = runCatching {
            if (output != null) {
                createColorManagedWindowSurface(eglConfig ?: return false, output)
            } else {
                val pbufferAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
                )
                EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
            }
        }.getOrDefault(EGL14.EGL_NO_SURFACE)
        if (eglSurface === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Preview window surface unavailable (sdrOutput=$sdrOutput)")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        if (output != null && publishInitialBlack) {
            // Legacy diagnostic-only behavior. Production callers retain the last proven window buffer
            // until the first genuine camera frame is ready; publishing synthetic black is forbidden.
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) return false
        }

        program = buildProgram() ?: return false
        aPositionLoc = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        uTexelLoc = GLES30.glGetUniformLocation(program, "uTexel")
        uModeLoc = GLES30.glGetUniformLocation(program, "uMode")
        uInputTransferLoc = GLES30.glGetUniformLocation(program, "uInputTransfer")
        uInputPrimariesLoc = GLES30.glGetUniformLocation(program, "uInputPrimaries")
        uInputRangeLoc = GLES30.glGetUniformLocation(program, "uInputRange")
        uApplyNdrGradeLoc = GLES30.glGetUniformLocation(program, "uApplyNdrGrade")
        uSdrOutputLoc = GLES30.glGetUniformLocation(program, "uSdrOutput")
        uWarmSkinRedTameLoc = GLES30.glGetUniformLocation(program, "uWarmSkinRedTame")
        uTargetPeakNitsLoc = GLES30.glGetUniformLocation(program, "uTargetPeakNits")
        uMonitorPeakNitsLoc = GLES30.glGetUniformLocation(program, "uMonitorPeakNits")
        uProjectOwnedInputLoc = GLES30.glGetUniformLocation(program, "uProjectOwnedInput")
        uRawOrientLoc = GLES30.glGetUniformLocation(program, "uRawOrient")

        projectOwnedProgram = if (explicitYuvDecodeActive) {
            linkProgram(FRAGMENT_SHADER) ?: return false
        } else {
            program
        }
        projectAPositionLoc = GLES30.glGetAttribLocation(projectOwnedProgram, "aPosition")
        projectATexCoordLoc = GLES30.glGetAttribLocation(projectOwnedProgram, "aTexCoord")
        projectUTexMatrixLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uTexMatrix")
        projectUTextureLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uTexture")
        projectUTexelLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uTexel")
        projectUModeLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uMode")
        projectUInputTransferLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uInputTransfer")
        projectUInputPrimariesLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uInputPrimaries")
        projectUInputRangeLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uInputRange")
        projectUApplyNdrGradeLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uApplyNdrGrade")
        projectUSdrOutputLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uSdrOutput")
        projectUWarmSkinRedTameLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uWarmSkinRedTame")
        projectUTargetPeakNitsLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uTargetPeakNits")
        projectUMonitorPeakNitsLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uMonitorPeakNits")
        projectUProjectOwnedInputLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uProjectOwnedInput")
        projectURawOrientLoc = GLES30.glGetUniformLocation(projectOwnedProgram, "uRawOrient")

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            frameAvailable = true
            requestRender(redrawRetainedFrame = false)
        }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)

        projectOwnedTextureId = textures[1]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, projectOwnedTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val projectSt = SurfaceTexture(projectOwnedTextureId)
        projectSt.setDefaultBufferSize(width, height)
        projectSt.setOnFrameAvailableListener({
            projectOwnedFrameAvailable = true
            requestRender(redrawRetainedFrame = false)
        }, handler)
        projectOwnedSurfaceTexture = projectSt
        projectOwnedInputSurface = Surface(projectSt)
        return true
    }

    /**
     * The SDR shader emits encoded sRGB code values. Keep this child surface untagged so EGL does not
     * apply a second linear-to-sRGB conversion. In the persistent zero-stutter graph the output router
     * performs the single encoded-sRGB -> linear boundary before its explicitly tagged final surface.
     * A directly attached child is likewise treated as the platform-default sRGB monitor surface.
     */
    private fun createColorManagedWindowSurface(config: EGLConfig, output: Surface): EGLSurface {
        if (!sdrOutput) {
            return runCatching {
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
            }.getOrDefault(EGL14.EGL_NO_SURFACE)
        }

        return runCatching {
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                config,
                output,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
        }.getOrDefault(EGL14.EGL_NO_SURFACE)
    }

    /** EGL channel-size attributes are minima; strict NDR must verify the selected config itself. */
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
            val window = createColorManagedWindowSurface(config, output)
            if (window === EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "Hdr10PqPreviewCompositor warm output attach failed: eglCreateWindowSurface")
                latch.countDown()
                return@post
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)) {
                EGL14.eglDestroySurface(eglDisplay, window)
                Log.w(TAG, "Hdr10PqPreviewCompositor warm output attach failed: eglMakeCurrent")
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
            ok = true
            drawFrame()
            latch.countDown()
        }
        val completed = runCatching { latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (completed && ok) {
            Log.i(TAG, "Hdr10PqPreviewCompositor warm output attached ${width}x${height}")
        }
        return completed && ok
    }

    private fun requestRender(redrawRetainedFrame: Boolean = true) {
        if (!running || releaseInProgress) return
        val renderHandler = handler ?: return
        if (redrawRetainedFrame) retainedFrameRedrawRequested.set(true)
        if (!renderGate.requestDispatch()) return
        if (!renderHandler.post(renderRunnable)) {
            renderGate.cancelPendingDispatch()
        }
    }

    private fun drawFrame(redrawRetainedFrame: Boolean = true) {
        if (!running || releaseInProgress) return
        val cameraTexture = surfaceTexture ?: return
        val rawTexture = projectOwnedSurfaceTexture ?: return
        if (eglSurface === EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            val eglError = EGL14.eglGetError()
            reportRuntimeFailure(
                operation = PreviewRuntimeOperation.EGL_MAKE_CURRENT,
                eglError = eglError,
                detail = "HDR child eglMakeCurrent failed error=0x${eglError.toString(16)}"
            )
            return
        }

        var cameraTimestampNs = 0L
        var rawTimestampNs = 0L
        var rawPresentationEpoch: Long? = null
        if (
            !frameAvailable && !projectOwnedFrameAvailable &&
            updatedFrameCount == 0 && !projectOwnedFrameReady
        ) return

        if (frameAvailable) {
            runCatching {
                cameraTexture.updateTexImage()
                cameraTexture.getTransformMatrix(texMatrix)
                cameraTimestampNs = cameraTexture.timestamp
                val dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { cameraTexture.dataSpace }
                        .getOrDefault(NdrInputTransportPolicy.DATASPACE_UNKNOWN)
                } else {
                    NdrInputTransportPolicy.DATASPACE_UNKNOWN
                }
                val transport = NdrInputTransportPolicy.resolve(
                    dataSpace = dataSpace,
                    appliedProfile = appliedInputProfile,
                    preferPqWhenUnknown = false
                )
                val previewRenderPath = NdrPreviewInputAdmissionPolicy.renderPath(transport)
                val previewInputSupported = NdrPreviewInputAdmissionPolicy.isSupported(transport)
                inputTransferCode = transport.transfer.shaderCode
                inputPrimariesCode = transport.primaries.ordinal
                inputRangeCode = transport.range.shaderCode
                inputNdrGradeCode = previewRenderPath.shaderCode
                val signature =
                    "$dataSpace|${transport.transfer}|${transport.primaries}|${transport.range}|" +
                        "${transport.source}|$previewRenderPath|explicitYuv=$explicitYuvDecodeActive"
                if (signature != lastInputTransportSignature) {
                    lastInputTransportSignature = signature
                    val message =
                        "NDR camera monitor dataspace=$dataSpace transfer=${transport.transfer} " +
                            "primaries=${transport.primaries} range=${transport.range} " +
                            "source=${transport.source} explicitYuv=$explicitYuvDecodeActive " +
                            "renderPath=$previewRenderPath supported=$previewInputSupported"
                    if (previewInputSupported) Log.i(TAG, message) else Log.w(TAG, message)
                }
                updatedFrameCount++
            }.onFailure { return }
            frameAvailable = false
            // Camera2 remains connected and draining while RAW owns visible NDR. It is not permitted
            // to satisfy the HDR-owner warmup gate during that epoch.
            if (!projectOwnedPresentAllowed && cameraTimestampNs > 0L) {
                inputFrameListener?.invoke(cameraTimestampNs, null)
            }
        }

        if (projectOwnedFrameAvailable) {
            runCatching {
                rawTexture.updateTexImage()
                rawTexture.getTransformMatrix(projectOwnedTexMatrix)
                val timestampNs = rawTexture.timestamp
                val admittedFrame = projectOwnedAdmission.admit(timestampNs)
                val exactEpoch = admittedFrame?.epoch
                val epochMatches = projectOwnedPresentAllowed &&
                    exactEpoch != null &&
                    exactEpoch == projectOwnedPresentationEpoch
                val dataSpace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { rawTexture.dataSpace }
                        .getOrDefault(NdrInputTransportPolicy.DATASPACE_UNKNOWN)
                } else {
                    NdrInputTransportPolicy.DATASPACE_UNKNOWN
                }
                val dataSpaceAdmission = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
                    dataSpace = dataSpace,
                    proof = admittedFrame?.colorProof
                )
                val stability = if (epochMatches && dataSpaceAdmission.accepted) {
                    projectOwnedProofStabilityGate.observe(admittedFrame.colorProof)
                } else {
                    projectOwnedProofStabilityGate.reset()
                    ProjectOwnedNdrProofStabilityDecision(
                        stable = false,
                        consecutiveCompatibleFrames = 0,
                        proof = null,
                        reason = if (!epochMatches) {
                            "epoch-or-timestamp-mismatch"
                        } else {
                            dataSpaceAdmission.reason
                        }
                    )
                }
                projectOwnedCompatibleProofCount = stability.consecutiveCompatibleFrames
                projectOwnedLastAcceptedColorProof = stability.proof
                val admissionSignature =
                    "$exactEpoch|$dataSpace|${dataSpaceAdmission.source}|${dataSpaceAdmission.reason}|" +
                        "${stability.reason}|${stability.consecutiveCompatibleFrames}"
                if (admissionSignature != lastProjectOwnedAdmissionSignature) {
                    lastProjectOwnedAdmissionSignature = admissionSignature
                    val message =
                        "NDR project-owned admission epoch=$exactEpoch timestamp=$timestampNs " +
                            "dataspace=$dataSpace dataspaceSource=${dataSpaceAdmission.source} " +
                            "proofStreak=${stability.consecutiveCompatibleFrames}/" +
                            "$MIN_STABLE_PROJECT_OWNED_FRAMES result=${stability.reason} " +
                            (stability.proof?.diagnosticSummary() ?: "proof=none")
                    if (stability.stable) Log.i(TAG, message) else Log.w(TAG, message)
                }
                if (epochMatches && dataSpaceAdmission.accepted && stability.stable) {
                    val admitted = requireNotNull(admittedFrame)
                    projectOwnedFrameReady = true
                    projectOwnedLatchedEpoch = admitted.epoch
                    projectOwnedLatchedTimestampNs = timestampNs
                    projectOwnedLatchedColorProof = admitted.colorProof
                    projectOwnedUpdatedFrameCount = stability.consecutiveCompatibleFrames
                    rawTimestampNs = timestampNs
                    rawPresentationEpoch = admitted.epoch
                    if (!projectOwnedTransportLogged) {
                        projectOwnedTransportLogged = true
                        Log.i(
                            TAG,
                            "NDR visible input accepted transfer=PROJECT_OWNED_PQ " +
                                "source=RAW_DEMOSAIC_ACESCG_STRICT epoch=${admitted.epoch} " +
                                "dataspaceSource=${dataSpaceAdmission.source} " +
                                admitted.colorProof.diagnosticSummary()
                        )
                    }
                } else {
                    // updateTexImage() has already replaced the OES texture. Mark it unusable so a
                    // stale/reordered buffer can never be drawn merely because an earlier frame was
                    // valid. The output surface retains its last successfully swapped NDR frame.
                    projectOwnedFrameReady = false
                    projectOwnedUpdatedFrameCount = 0
                    projectOwnedLatchedTimestampNs = 0L
                    projectOwnedLatchedEpoch = NO_PROJECT_OWNED_EPOCH
                    projectOwnedLatchedColorProof = null
                }
            }.onFailure { return }
            projectOwnedFrameAvailable = false
            if (rawTimestampNs > 0L) {
                inputFrameListener?.invoke(rawTimestampNs, rawPresentationEpoch)
            }
        }

        if (!presentationEnabled) return
        // Camera2 remains connected as the non-recording warm monitor until the first exact RAW frame is
        // proven. After project-owned pixels present once, Camera2 becomes drain-only for that epoch and
        // a rejected/reordered buffer can only retain the last valid NDR output.
        val currentProjectEpoch = projectOwnedPresentationEpoch
        val projectRouteCommitted = projectOwnedPresentAllowed &&
            projectOwnedAdmission.isCommitted(currentProjectEpoch)
        val visibleSource = NdrVisiblePreviewSourcePolicy.resolve(
            projectOwnedRequested = projectOwnedPresentAllowed,
            routeCommitted = projectRouteCommitted,
            projectOwnedFrameReady = projectOwnedFrameReady,
            latchedEpoch = projectOwnedLatchedEpoch,
            currentEpoch = currentProjectEpoch
        )
        val useProjectOwned = visibleSource == NdrVisiblePreviewSource.PROJECT_OWNED
        if (projectRouteCommitted && !useProjectOwned) {
            if (surfaceHasPresentedProjectOwnedFrame) {
                // Once exact project-owned pixels have reached the display, a rejected/reordered buffer
                // may only retain that last valid NDR frame; Camera2 must never take ownership back.
                return
            }
            // The router or SurfaceFlinger retains the outgoing validated frame until this epoch has an
            // exact RAW result. A Camera2 proxy would create the observed SDR -> proxy -> NDR brightness
            // and colour jump, so it is never a valid substitute even on a newly-created surface.
            return
        }
        if (visibleSource == NdrVisiblePreviewSource.HOLD_LAST_VALIDATED) {
            // The project-owned route is armed but not committed. Continue draining Camera2 above, but
            // publish nothing until the epoch transaction is valid and a matching RAW frame is ready.
            return
        }
        // Camera2 must keep draining while RAW owns the display, but its 25 fps callbacks must not
        // repeatedly swap the same much-slower project-owned texture. A genuine RAW frame or a
        // deliberate state/orientation redraw remains eligible, so no visible update is suppressed.
        if (useProjectOwned && rawTimestampNs <= 0L && !redrawRetainedFrame) return
        if (useProjectOwned && projectOwnedUpdatedFrameCount < MIN_STABLE_PROJECT_OWNED_FRAMES) return
        if (!useProjectOwned && updatedFrameCount < MIN_STABLE_INPUT_FRAMES) {
            return
        }

        val activeProgram = if (useProjectOwned) projectOwnedProgram else program
        if (activeProgram == 0) return
        val positionLoc = if (useProjectOwned) projectAPositionLoc else aPositionLoc
        val texCoordLoc = if (useProjectOwned) projectATexCoordLoc else aTexCoordLoc
        val texMatrixLoc = if (useProjectOwned) projectUTexMatrixLoc else uTexMatrixLoc
        val textureLoc = if (useProjectOwned) projectUTextureLoc else uTextureLoc
        val texelLoc = if (useProjectOwned) projectUTexelLoc else uTexelLoc
        val modeLoc = if (useProjectOwned) projectUModeLoc else uModeLoc
        val transferLoc = if (useProjectOwned) projectUInputTransferLoc else uInputTransferLoc
        val primariesLoc = if (useProjectOwned) projectUInputPrimariesLoc else uInputPrimariesLoc
        val rangeLoc = if (useProjectOwned) projectUInputRangeLoc else uInputRangeLoc
        val gradeLoc = if (useProjectOwned) projectUApplyNdrGradeLoc else uApplyNdrGradeLoc
        val outputLoc = if (useProjectOwned) projectUSdrOutputLoc else uSdrOutputLoc
        val warmSkinLoc = if (useProjectOwned) projectUWarmSkinRedTameLoc else uWarmSkinRedTameLoc
        val targetPeakLoc = if (useProjectOwned) projectUTargetPeakNitsLoc else uTargetPeakNitsLoc
        val monitorPeakLoc = if (useProjectOwned) projectUMonitorPeakNitsLoc else uMonitorPeakNitsLoc
        val ownedInputLoc = if (useProjectOwned) projectUProjectOwnedInputLoc else uProjectOwnedInputLoc
        val orientLoc = if (useProjectOwned) projectURawOrientLoc else uRawOrientLoc

        // Each producer queue owns a different SurfaceTexture transform. Camera2's matrix may include
        // HAL crop/origin semantics that do not belong to the project-owned RGB10_A2 queue; reusing it
        // here causes direction-dependent resampling, stretch and rotation. Apply the RAW producer's
        // own matrix, then its separately lineage-owned display orientation.
        val sourceMatrix = if (useProjectOwned) projectOwnedTexMatrix else texMatrix
        val sourceOrientation = if (useProjectOwned) projectOwnedOrient else IDENTITY_ORIENT
        val sourceTexture = if (useProjectOwned) projectOwnedTextureId else oesTextureId
        val sourceTransfer = if (useProjectOwned) NdrInputTransfer.PQ.shaderCode else inputTransferCode
        val sourcePrimaries = if (useProjectOwned) {
            NdrInputPrimaries.BT2020.ordinal
        } else {
            inputPrimariesCode
        }
        val sourceRange = if (useProjectOwned) NdrInputRange.FULL.shaderCode else inputRangeCode
        val sourceGrade = if (useProjectOwned) {
            NdrInputRenderPath.TRANSPORT_BYPASS.shaderCode
        } else {
            inputNdrGradeCode
        }

        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
        GLES30.glViewport(0, 0, width[0].coerceAtLeast(1), height[0].coerceAtLeast(1))
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(activeProgram)

        quadVertices.position(0)
        GLES30.glVertexAttribPointer(positionLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)
        GLES30.glEnableVertexAttribArray(positionLoc)
        quadVertices.position(2)
        GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, STRIDE, quadVertices)
        GLES30.glEnableVertexAttribArray(texCoordLoc)
        GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, sourceMatrix, 0)
        GLES30.glUniformMatrix2fv(
            orientLoc,
            1,
            false,
            sourceOrientation,
            0
        )
        GLES30.glUniform1i(ownedInputLoc, if (useProjectOwned) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTexture)
        GLES30.glUniform1i(textureLoc, 0)
        GLES30.glUniform2f(
            texelLoc,
            1f / boundWidth.coerceAtLeast(1),
            1f / boundHeight.coerceAtLeast(1)
        )
        GLES30.glUniform1i(modeLoc, modeCode)
        GLES30.glUniform1i(transferLoc, sourceTransfer)
        GLES30.glUniform1i(primariesLoc, sourcePrimaries)
        GLES30.glUniform1i(rangeLoc, sourceRange)
        GLES30.glUniform1i(gradeLoc, sourceGrade)
        GLES30.glUniform1i(outputLoc, if (sdrOutput) 1 else 0)
        GLES30.glUniform1f(warmSkinLoc, NdrWarmSkinRedTameFlag.effectiveStrength.toFloat())
        GLES30.glUniform1f(targetPeakLoc, targetPeakNits)
        GLES30.glUniform1f(monitorPeakLoc, monitorPeakNits)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(positionLoc)
        GLES30.glDisableVertexAttribArray(texCoordLoc)

        val lineageTimestampNs = if (useProjectOwned) rawTimestampNs else cameraTimestampNs
        if (lineageTimestampNs > 0L) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, lineageTimestampNs)
        }
        val presented = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!presented) {
            val eglError = EGL14.eglGetError()
            reportRuntimeFailure(
                operation = PreviewRuntimeOperation.EGL_SWAP_BUFFERS,
                eglError = eglError,
                detail = "HDR child eglSwapBuffers failed error=0x${eglError.toString(16)} " +
                    "projectOwned=$useProjectOwned timestampNs=$lineageTimestampNs"
            )
            return
        }
        val presentationTimestampNs = SystemClock.elapsedRealtimeNanos()
        if (useProjectOwned) {
            surfaceHasPresentedProjectOwnedFrame = true
            projectOwnedFramePresentedListener?.invoke(
                projectOwnedLatchedEpoch,
                projectOwnedLatchedTimestampNs
            )
        }
        val presentedOutput = boundOutput
        if (presentedOutput != null && lineageTimestampNs > 0L) {
            framePresentedListener?.invoke(lineageTimestampNs, presentedOutput, presentationTimestampNs)
        }
    }

    private fun reportRuntimeFailure(
        operation: PreviewRuntimeOperation,
        eglError: Int,
        detail: String
    ) {
        val failure = PreviewRuntimeFailure(
            component = PreviewRuntimeComponent.HDR_CHILD,
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

        renderGate.cancelPendingDispatch()
        retainedFrameRedrawRequested.set(false)
        Log.e(TAG, detail)
        val listener = runtimeFailureListener ?: return
        runCatching { listener(failure) }
            .onFailure { Log.e(TAG, "Strict preview runtime-failure listener failed", it) }
    }

    private fun destroyGl() {
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { projectOwnedInputSurface?.release() }
        projectOwnedInputSurface = null
        surfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        surfaceTexture = null
        projectOwnedSurfaceTexture?.let { st ->
            runCatching { st.setOnFrameAvailableListener(null) }
            runCatching { st.release() }
        }
        projectOwnedSurfaceTexture = null
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            val contextCurrent =
                eglContext !== EGL14.EGL_NO_CONTEXT &&
                    eglSurface !== EGL14.EGL_NO_SURFACE &&
                    runCatching {
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    }.getOrDefault(false)
            if (contextCurrent) {
                if (program != 0) runCatching { GLES30.glDeleteProgram(program) }
                if (projectOwnedProgram != 0 && projectOwnedProgram != program) {
                    runCatching { GLES30.glDeleteProgram(projectOwnedProgram) }
                }
                if (oesTextureId != 0 || projectOwnedTextureId != 0) {
                    runCatching {
                        GLES30.glDeleteTextures(
                            2,
                            intArrayOf(oesTextureId, projectOwnedTextureId),
                            0
                        )
                    }
                }
            }
            program = 0
            projectOwnedProgram = 0
            oesTextureId = 0
            projectOwnedTextureId = 0
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
        updatedFrameCount = 0
        renderGate.cancelPendingDispatch()
        retainedFrameRedrawRequested.set(false)
        projectOwnedFrameAvailable = false
        projectOwnedFrameReady = false
        projectOwnedUpdatedFrameCount = 0
        projectOwnedLatchedTimestampNs = 0L
        projectOwnedLatchedEpoch = NO_PROJECT_OWNED_EPOCH
        resetProjectOwnedColorProofState()
        projectOwnedPresentAllowed = false
        surfaceHasPresentedProjectOwnedFrame = false
        projectOwnedAdmission.setEnabled(false)
        explicitYuvDecodeActive = false
        lastInputTransportSignature = ""
        projectOwnedTransportLogged = false
    }

    private fun resetProjectOwnedColorProofState() {
        projectOwnedProofStabilityGate.reset()
        projectOwnedCompatibleProofCount = 0
        projectOwnedLastAcceptedColorProof = null
        projectOwnedLatchedColorProof = null
        lastProjectOwnedAdmissionSignature = ""
    }

    private fun buildProgram(): Int? {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val explicitYuvAdvertised = extensions
            .split(' ')
            .any { it == "GL_EXT_YUV_target" }
        val explicitYuvSupported =
            explicitYuvAdvertised && NdrExplicitYuvDecodePolicy.isAllowedOnCurrentDevice()
        if (explicitYuvSupported) {
            linkProgram(EXPLICIT_YUV_FRAGMENT_SHADER)?.let { explicitProgram ->
                explicitYuvDecodeActive = true
                Log.i(TAG, "NDR preview explicit BT.2020 YUV decode active (GL_EXT_YUV_target)")
                return explicitProgram
            }
            Log.w(TAG, "GL_EXT_YUV_target advertised but explicit NDR YUV shader was unavailable")
        } else if (explicitYuvAdvertised) {
            Log.i(
                TAG,
                "NDR preview using device-validated hardware YUV conversion; " +
                    "raw GL_EXT_YUV_target import is incompatible on this device"
            )
        }
        explicitYuvDecodeActive = false
        return linkProgram(FRAGMENT_SHADER)
    }

    private fun linkProgram(fragmentShader: String): Int? {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShader) ?: run {
            GLES30.glDeleteShader(vs)
            return null
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.w(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return null
        }
        return prog
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            Log.w(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
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
        private const val TAG = "Hdr10PqPreviewCompositor"
        private const val INIT_TIMEOUT_MS = 2_000L
        private const val MIN_STABLE_INPUT_FRAMES = 2
        private const val MIN_STABLE_PROJECT_OWNED_FRAMES = 2
        private const val NO_PROJECT_OWNED_EPOCH = -1L
        private const val STRIDE = 4 * 4
        private val IDENTITY_ORIENT = floatArrayOf(1f, 0f, 0f, 1f)

        const val MODE_NDR10_PLUS = 0
        const val MODE_NIVUKX_VISION = 1

        // EGL colorspace constants (not exposed on EGL14): EGL_KHR_gl_colorspace +
        // EGL_EXT_gl_colorspace_bt2020_pq.
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340

        private const val VERTEX_SHADER = """#version 300 es
            precision highp float;
            precision highp int;
            in vec4 aPosition;
            in vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform mat2 uRawOrient;
            uniform int uProjectOwnedInput;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = (uTexMatrix * aTexCoord).xy;
                if (uProjectOwnedInput == 1) {
                    uv = uRawOrient * (uv - vec2(0.5)) + vec2(0.5);
                }
                vTexCoord = uv;
            }
        """

        // GLSL-ES port of com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine — the
        // SAME transfer the RAW-derived record shader bakes. The visible-only STANDARD monitor proxy
        // is linearized with its real BT.709 transfer, converted to the working primaries, and run through the
        // hue-preserving tone + 203-nit / master-peak shoulder, and PQ-encoded for the
        // BT.2020-PQ 10-bit surface. mode 0 = NDR10+, 1 = NIVUKX Vision.
        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            // ESSL defaults integer precision to highp in vertex shaders but mediump in fragment
            // shaders. uProjectOwnedInput is shared by both stages, so leaving this implicit makes
            // strict Adreno linkers reject the complete NDR program before the RAW route can start.
            precision highp int;
            in vec2 vTexCoord;
            // Opaque sampler types have their own default precision. GL_EXT_YUV_target specifies
            // lowp for __samplerExternal2DY2YEXT unless the uniform is explicitly qualified. That
            // truncates P010 chroma before the PQ EOTF and amplifies sub-code errors into cyan/magenta
            // false colour. Keep the ordinary and raw-YUV variants highp end-to-end.
            uniform highp samplerExternalOES uTexture;
            uniform vec2 uTexel;
            uniform int uMode;
            // Actual buffer transport, resolved from SurfaceTexture.getDataSpace() for every frame.
            // 0=SDR/BT.709 transfer, 1=PQ/ST.2084, 2=HLG. Primaries: 1=BT.709, 2=BT.2020.
            uniform int uInputTransfer;
            uniform int uInputPrimaries;
            // Android dataspace range: 0 unknown, 1 full, 2 limited, 3 extended.
            uniform int uInputRange;
            // 1 for project-owned linear input or the STANDARD BT.709 monitor proxy. HLG/PQ is 0.
            uniform int uApplyNdrGrade;
            // 1 only for PQ already produced from the strict RAW-derived ACEScg bridge. It receives
            // presentation adaptation but must never be graded a second time.
            uniform int uProjectOwnedInput;
            // 1 => output display-referred SDR (sRGB) for an 8-bit window surface (binds on this device);
            // 0 => output PQ (ST.2084) for a 10-bit BT.2020-PQ surface. See Hdr10PqPreviewSdrOutputFlag.
            uniform int uSdrOutput;
            uniform float uWarmSkinRedTame;
            // Project-owned content-master peak and actual preview-panel peak are separate.
            uniform float uTargetPeakNits;
            uniform float uMonitorPeakNits;
            out vec4 fragColor;

            const vec3 NEO_LUMA = vec3(0.2627, 0.6780, 0.0593);
            const float NEO_HLG_DIFFUSE_SCENE = 0.264967;
            const float NEO_PQ_DIFFUSE_NITS = 203.0;
            const float NDR10_TARGET_PEAK_NITS = 5000.0;
            const float NIVUKX_TARGET_PEAK_NITS = 10000.0;
            // NDR-NIVUKX-PY-CAL-v4: exact NdrToneOperator runtime packet.
            const float NDR10_SIGNAL_GAMMA = 0.859937;
            const float NDR10_TOE_WIDTH = 0.238882;
            const float NDR10_TOE_AMOUNT = 0.024780;
            const float NDR10_HIGHLIGHT_DESAT = 0.149718;
            const float NDR10_SHADOW_MIDTONE_LIFT = 0.167837;
            const float NDR10_MIDTONE_CONTRAST = 0.100943;
            const float NDR10_CHROMA_BOOST = 0.038361;
            const float NDR10_SKY_CHROMA_BONUS = 0.062392;
            const float NDR10_LOCAL_DETAIL_STRENGTH = 0.400000;
            const float NDR10_LOCAL_DETAIL_LOWER_STOPS = -0.060000;
            const float NDR10_LOCAL_DETAIL_UPPER_STOPS = 0.085000;
            const float NIVUKX_SIGNAL_GAMMA = 0.800034;
            const float NIVUKX_TOE_WIDTH = 0.289876;
            const float NIVUKX_TOE_AMOUNT = 0.031033;
            const float NIVUKX_HIGHLIGHT_DESAT = 0.128953;
            const float NIVUKX_SHADOW_MIDTONE_LIFT = 0.232156;
            const float NIVUKX_MIDTONE_CONTRAST = 0.174135;
            const float NIVUKX_CHROMA_BOOST = 0.091565;
            const float NIVUKX_SKY_CHROMA_BONUS = 0.080000;
            const float NIVUKX_LOCAL_DETAIL_STRENGTH = 0.600000;
            const float NIVUKX_LOCAL_DETAIL_LOWER_STOPS = -0.085000;
            const float NIVUKX_LOCAL_DETAIL_UPPER_STOPS = 0.125000;
            // Project NDR-NIVUKX-PY-CAL-v4: linear value for the calibrated sRGB 0.80 anchor.
            // Presentation-only; the project-owned PQ input and recording master remain unchanged.
            const float NDR_SDR_MONITOR_DIFFUSE_LINEAR = 0.60382734;

            float hlgInvOetf(float e) {
                e = clamp(e, 0.0, 1.0);
                return e <= 0.5 ? (e * e / 3.0)
                    : ((exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0);
            }
            float rec709InvOetf(float e) {
                e = clamp(e, 0.0, 1.0);
                return e < 0.081 ? e / 4.5 : pow((e + 0.099) / 1.099, 1.0 / 0.45);
            }
            // SMPTE ST.2084 (PQ) EOTF: signal -> display-linear normalized [0,1] (1.0 == 10000 nits).
            float pqEotf(float e) {
                e = clamp(e, 0.0, 1.0);
                float ep = pow(e, 1.0 / 78.84375);
                float num = max(ep - 0.8359375, 0.0);
                float den = 18.8515625 - 18.6875 * ep;
                return pow(num / den, 1.0 / 0.1593017578125);
            }
            float ss(float e0, float e1, float x) {
                if (e0 == e1) return x >= e1 ? 1.0 : 0.0;
                float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }
            float signalShape(float x, float g, float tw, float ta) {
                if (x <= 0.0) return 0.0;
                if (x <= 1.0) return pow(x, g) + ss(0.0, tw, x) * ta * (1.0 - x);
                return 1.0 + (g - ta) * (x - 1.0);
            }
            float blackPreservingMidtoneLift(float signalValue, float strength) {
                float x = max(signalValue, 0.0);
                if (x <= 0.0 || x >= 1.0) return x;
                float window = ss(0.025, 0.20, x) * (1.0 - ss(0.78, 1.20, x));
                return x + strength * sqrt(x) * (1.0 - x) * window;
            }
            float profileMidtoneContrast(float signalValue, float strength) {
                float x = max(signalValue, 0.0);
                if (x <= 0.0 || x >= 1.0) return x;
                float window = ss(0.055, 0.24, x) * (1.0 - ss(0.76, 1.0, x));
                float signedContrast = (x - 0.50) * 4.0 * x * (1.0 - x);
                return max(x + strength * signedContrast * window, 0.0);
            }
            vec3 profileChromaCharacter(
                vec3 toneMapped,
                vec3 source,
                float mappedY,
                float baseBoost,
                float skyBonusStrength
            ) {
                float peakValue = max(toneMapped.r, max(toneMapped.g, toneMapped.b));
                float floorValue = min(toneMapped.r, min(toneMapped.g, toneMapped.b));
                float relativeChroma = (peakValue - floorValue) / max(mappedY, 0.04);
                float colorConfidence = ss(0.035, 0.22, relativeChroma);
                float rg = source.r / max(source.g, 1e-8);
                float bg = source.b / max(source.g, 1e-8);
                float skin = ss(1.02, 1.16, rg) *
                    (1.0 - ss(1.58, 2.10, rg)) *
                    (1.0 - ss(0.98, 1.18, bg));
                float sky = ss(1.03, 1.22, bg) * (1.0 - ss(1.00, 1.22, rg));
                float tonalWindow = ss(0.035, 0.16, mappedY) *
                    (1.0 - ss(0.88, 1.65, mappedY));
                float skinProtection = mix(1.0, 0.45, skin);
                float gain = 1.0 + (baseBoost + skyBonusStrength * sky) *
                    colorConfidence * tonalWindow * skinProtection;
                return vec3(mappedY) + (toneMapped - vec3(mappedY)) * gain;
            }
            float sdrMonitorLuminance(float toneSignal) {
                float x = max(toneSignal, 0.0);
                if (x <= 1.0) return x * NDR_SDR_MONITOR_DIFFUSE_LINEAR;
                float headroom = 1.0 - NDR_SDR_MONITOR_DIFFUSE_LINEAR;
                float shoulderLength = headroom / NDR_SDR_MONITOR_DIFFUSE_LINEAR;
                float excess = x - 1.0;
                return clamp(
                    NDR_SDR_MONITOR_DIFFUSE_LINEAR +
                        headroom * (excess / (excess + shoulderLength)),
                    0.0,
                    1.0
                );
            }
            float signalToNits(float s, float peak) {
                s = max(s, 0.0);
                float raw = s * 203.0;
                if (raw <= 203.0) return raw;
                float r = peak - 203.0;
                return min(203.0 + r * (1.0 - exp(-(raw - 203.0) / r)), peak);
            }
            float pqFromNits(float nits) {
                float y = clamp(nits / 10000.0, 0.0, 1.0);
                float ym = pow(y, 0.1593017578125);
                return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), 78.84375);
            }
            vec3 compressToPeakColorVolume(vec3 rgbNits, float luminanceNits, float peakNits) {
                float peak = clamp(peakNits, NEO_PQ_DIFFUSE_NITS, 10000.0);
                float y = clamp(luminanceNits, 0.0, peak);
                vec3 rgb = max(rgbNits, vec3(0.0));
                float hi = max(max(rgb.r, rgb.g), rgb.b);
                if (hi <= peak || hi <= y + 1e-8) return rgb;
                float chromaScale = clamp((peak - y) / (hi - y), 0.0, 1.0);
                return clamp(vec3(y) + (rgb - vec3(y)) * chromaScale, 0.0, peak);
            }
            // Default external-texture path: the driver performs YUV -> R'G'B'. Devices advertising
            // GL_EXT_YUV_target replace this function with the explicit project-owned decode below.
            vec3 sampleCameraInput(vec2 uv) {
                return texture(uTexture, uv).rgb;
            }
            vec3 decodeCameraSignal(vec3 encoded) {
                vec3 e = clamp(encoded, 0.0, 1.0);
                vec3 decoded;
                if (uInputTransfer == 1) {
                    decoded = vec3(pqEotf(e.r), pqEotf(e.g), pqEotf(e.b)) *
                        (10000.0 / NEO_PQ_DIFFUSE_NITS);
                } else if (uInputTransfer == 2) {
                    decoded = vec3(hlgInvOetf(e.r), hlgInvOetf(e.g), hlgInvOetf(e.b)) /
                        NEO_HLG_DIFFUSE_SCENE;
                } else {
                    decoded = vec3(rec709InvOetf(e.r), rec709InvOetf(e.g), rec709InvOetf(e.b));
                }
                if (uInputPrimaries == 1) {
                    const mat3 BT709_TO_2020 = mat3(
                        0.627404, 0.069097, 0.016392,
                        0.329283, 0.919540, 0.088013,
                        0.043313, 0.011362, 0.895595
                    );
                    decoded = BT709_TO_2020 * decoded;
                }
                return max(decoded, vec3(0.0));
            }
            float profileLocalDetailStops(vec2 uv, vec3 center) {
                // Two symmetric bilinear samples provide a bounded four-neighbour proxy. Decode their
                // mean once, avoiding four extra transfer-function evaluations on every preview pixel.
                vec2 halfTexel = max(uTexel, vec2(0.00001)) * 0.5;
                vec3 positive = sampleCameraInput(clamp(uv + halfTexel, 0.0, 1.0));
                vec3 negative = sampleCameraInput(clamp(uv - halfTexel, 0.0, 1.0));
                vec3 localMean = decodeCameraSignal((positive + negative) * 0.5);
                float centerY = max(dot(center, NEO_LUMA), 0.0);
                float localY = max(dot(localMean, NEO_LUMA), 0.0);
                float detailStops = log2((centerY + 0.0020) / (localY + 0.0020));
                float edgeConfidence = ss(0.006, 0.040, abs(centerY - localY));
                float shadowNoiseGuard = ss(0.018, 0.075, centerY);
                float highlightGuard = 1.0 - ss(1.30, 3.20, centerY);
                return clamp(detailStops, -0.22, 0.22) *
                    edgeConfidence * shadowNoiseGuard * highlightGuard;
            }
            // Shared NDR/NIVUKX neutral-axis calibration. It preserves center luminance exactly and
            // removes both spatially-incoherent chroma speckle and coherent sub-noise-floor sensor bias.
            // The original calibration stopped above 0.10 linear luma, leaving the isolated cyan/magenta
            // P010 outliers visible on bright neutral walls. The visible-surface lane below compares the
            // center against a four-neighbour chroma consensus and only engages when that consensus is
            // neutral. Luma edges, chromatic edges, coherent pastel/skin colour, and saturated colour are
            // protected. A cheap center-chroma gate avoids the four extra samples for already-neutral and
            // strongly coloured visible pixels, retaining preview throughput.
            vec3 calibrateNearBlackChroma(vec2 uv, vec3 center) {
                float yc = dot(center, NEO_LUMA);
                vec3 centerChroma = center - vec3(yc);
                float centerChromaMagnitude = length(centerChroma);
                if (yc >= 0.10 &&
                    (centerChromaMagnitude <= 0.0015 || centerChromaMagnitude >= 0.22)) {
                    return center;
                }
                vec2 tx = max(uTexel, vec2(0.00001));
                vec3 n = decodeCameraSignal(sampleCameraInput(clamp(uv + vec2(0.0, tx.y), 0.0, 1.0)));
                vec3 s = decodeCameraSignal(sampleCameraInput(clamp(uv - vec2(0.0, tx.y), 0.0, 1.0)));
                vec3 e = decodeCameraSignal(sampleCameraInput(clamp(uv + vec2(tx.x, 0.0), 0.0, 1.0)));
                vec3 w = decodeCameraSignal(sampleCameraInput(clamp(uv - vec2(tx.x, 0.0), 0.0, 1.0)));
                float yn = dot(n, NEO_LUMA);
                float ys = dot(s, NEO_LUMA);
                float ye = dot(e, NEO_LUMA);
                float yw = dot(w, NEO_LUMA);
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
                float deepShadow = 1.0 - ss(0.018, 0.090, yc);
                float edgeProtect = max(
                    ss(0.006, 0.045, lumaStructure),
                    ss(0.018, 0.100, chromaStructure)
                );
                float coherentColorProtect = ss(0.018, 0.085, length(consensusChroma));
                float disagreement = length(centerChroma - consensusChroma);
                float outlierConfidence = ss(0.0008, 0.018, disagreement);
                float shadowConsensusBlend = clamp(
                    deepShadow * (1.0 - edgeProtect) * mix(0.42, 0.96, outlierConfidence) *
                        (1.0 - coherentColorProtect * 0.82),
                    0.0,
                    0.96
                );
                vec3 calibratedChroma = mix(centerChroma, consensusChroma, shadowConsensusBlend);

                // Bright-neutral P010 speckle lane. Neighbour-only consensus is intentional: an
                // isolated bad center sample must not vote for itself. Same-luma colour boundaries are
                // retained by chromaStructure, while a coherent real colour makes surfaceNeutral zero.
                float surfaceNeutral = 1.0 - ss(0.020, 0.090, length(neighbourConsensusChroma));
                float visibleSurface = ss(0.075, 0.240, yc);
                float surfaceOutlierConfidence = ss(
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
                float blackUncertainty = 1.0 - ss(0.012, 0.065, yc);
                float tinyChroma = 1.0 - ss(0.012, 0.070, length(calibratedChroma));
                float neutralize = clamp(
                    blackUncertainty * tinyChroma * (1.0 - edgeProtect) *
                        (1.0 - coherentColorProtect) * mix(0.96, 1.0, outlierConfidence),
                    0.0,
                    0.995
                );
                calibratedChroma *= 1.0 - neutralize;
                return max(vec3(yc) + calibratedChroma, vec3(0.0));
            }
            void main() {
                vec3 e = clamp(sampleCameraInput(vTexCoord), 0.0, 1.0);
                // A true PQ display can consume the already-graded project output directly. This is
                // the only byte-preserving path and avoids a needless PQ decode/re-encode round trip.
                if (uProjectOwnedInput == 1 && uSdrOutput == 0) {
                    fragColor = vec4(e, 1.0);
                    return;
                }
                // Linearize with the transfer the camera ACTUALLY sent, then normalize to engine-linear
                // (1.0 == diffuse white). A known BT.709 buffer is converted to the BT.2020 working
                // primaries before the project-owned NDR operator; this prevents a stale PQ/2020
                // assumption from turning STANDARD Camera2 YUV red and overexposed.
                vec3 lin = decodeCameraSignal(e);
                // RAW ACEScg already passed the project-owned calibrated chroma/detail operator before
                // PQ encoding. Running the Camera2 YUV cleanup again would soften and recolour it.
                if (uProjectOwnedInput == 0) {
                    lin = calibrateNearBlackChroma(vTexCoord, lin);
                }
                if (uApplyNdrGrade == 0) {
                    // HLG/PQ and any unknown transport are not proprietary NDR inputs. Preserve that
                    // frame's real transfer/primaries and bypass every NDR look operation.
                    if (uSdrOutput == 1) {
                        vec3 bypass2020 = lin;
                        if (uProjectOwnedInput == 1) {
                            // The bridge has already executed the NDR10+/NIVUKX scene operator in
                            // high-precision ACEScg. Apply only the calibrated SDR monitor shoulder.
                            float yl = max(dot(bypass2020, NEO_LUMA), 1e-8);
                            float ysdr = sdrMonitorLuminance(yl);
                            bypass2020 *= ysdr / yl;
                        } else if (uInputTransfer != 0) {
                            // Neutral HDR monitor shoulder. This is transfer conversion only: it does
                            // not apply either NDR mode's tone curve or colour treatment.
                            float yl = max(dot(bypass2020, NEO_LUMA), 1e-8);
                            float knee = 0.75;
                            float sourcePeakNits = uInputTransfer == 2 ? 1000.0 : 10000.0;
                            float hdrSpan = max(sourcePeakNits / NEO_PQ_DIFFUSE_NITS - knee, 0.25);
                            float ysdr = yl <= knee
                                ? yl
                                : knee + (1.0 - knee) *
                                    (1.0 - exp(-4.0 * (yl - knee) / hdrSpan));
                            bypass2020 *= ysdr / yl;
                        }
                        const mat3 BYPASS_BT2020_TO_709 = mat3(
                            1.660491, -0.124550, -0.018151,
                           -0.587641,  1.132900, -0.100579,
                           -0.072850, -0.008349,  1.118730
                        );
                        vec3 rgb709 = BYPASS_BT2020_TO_709 * bypass2020;
                        float y709 = max(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0);
                        float lo = min(min(rgb709.r, rgb709.g), rgb709.b);
                        if (lo < 0.0) {
                            float f = y709 / (y709 - lo);
                            rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                        }
                        y709 = clamp(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                        float hi = max(max(rgb709.r, rgb709.g), rgb709.b);
                        float upperCeiling = uProjectOwnedInput == 1
                            ? mix(0.985, 0.965, ss(0.65, 0.98, y709))
                            : 1.0;
                        float targetHi = max(y709, upperCeiling);
                        if (hi > targetHi) {
                            float f = (targetHi - y709) / max(hi - y709, 1e-8);
                            rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                        }
                        rgb709 = clamp(rgb709, 0.0, 1.0);
                        vec3 srgb = mix(
                            rgb709 * 12.92,
                            1.055 * pow(rgb709, vec3(1.0 / 2.4)) - 0.055,
                            step(vec3(0.0031308), rgb709)
                        );
                        fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
                    } else {
                        // Convert the real decoded input to the PQ output surface without an NDR grade.
                        vec3 pq = vec3(
                            pqFromNits(lin.r * NEO_PQ_DIFFUSE_NITS),
                            pqFromNits(lin.g * NEO_PQ_DIFFUSE_NITS),
                            pqFromNits(lin.b * NEO_PQ_DIFFUSE_NITS)
                        );
                        fragColor = vec4(clamp(pq, 0.0, 1.0), 1.0);
                    }
                    return;
                }
                float g   = uMode == 1 ? NIVUKX_SIGNAL_GAMMA : NDR10_SIGNAL_GAMMA;
                float tw  = uMode == 1 ? NIVUKX_TOE_WIDTH : NDR10_TOE_WIDTH;
                float ta  = uMode == 1 ? NIVUKX_TOE_AMOUNT : NDR10_TOE_AMOUNT;
                float baselinePeak = uMode == 1
                    ? NIVUKX_TARGET_PEAK_NITS
                    : NDR10_TARGET_PEAK_NITS;
                float peak = clamp(uTargetPeakNits, baselinePeak, baselinePeak);
                float dc = uMode == 1 ? NIVUKX_HIGHLIGHT_DESAT : NDR10_HIGHLIGHT_DESAT;
                float shadowMidtoneLift = uMode == 1
                    ? NIVUKX_SHADOW_MIDTONE_LIFT
                    : NDR10_SHADOW_MIDTONE_LIFT;
                float midtoneContrast = uMode == 1
                    ? NIVUKX_MIDTONE_CONTRAST
                    : NDR10_MIDTONE_CONTRAST;
                float chromaBoost = uMode == 1 ? NIVUKX_CHROMA_BOOST : NDR10_CHROMA_BOOST;
                float skyChromaBonus = uMode == 1
                    ? NIVUKX_SKY_CHROMA_BONUS
                    : NDR10_SKY_CHROMA_BONUS;
                float localDetailStrength = uMode == 1
                    ? NIVUKX_LOCAL_DETAIL_STRENGTH
                    : NDR10_LOCAL_DETAIL_STRENGTH;
                float localDetailLower = uMode == 1
                    ? NIVUKX_LOCAL_DETAIL_LOWER_STOPS
                    : NDR10_LOCAL_DETAIL_LOWER_STOPS;
                float localDetailUpper = uMode == 1
                    ? NIVUKX_LOCAL_DETAIL_UPPER_STOPS
                    : NDR10_LOCAL_DETAIL_UPPER_STOPS;
                vec3 s = max(lin, vec3(0.0));
                float y = max(dot(s, NEO_LUMA), 1e-8);
                float mappedY = signalShape(y, g, tw, ta);
                mappedY = blackPreservingMidtoneLift(mappedY, shadowMidtoneLift);
                mappedY = profileMidtoneContrast(mappedY, midtoneContrast);
                float detailGainStops = clamp(
                    profileLocalDetailStops(vTexCoord, s) * localDetailStrength,
                    localDetailLower,
                    localDetailUpper
                );
                mappedY *= exp2(detailGainStops);
                vec3 t = s * (mappedY / y);
                t = profileChromaCharacter(t, s, mappedY, chromaBoost, skyChromaBonus);
                float desat = clamp(dc * ss(0.55, 1.0, mappedY), 0.0, dc);
                t = mix(t, vec3(mappedY), desat);
                // Ratio-gated red-overflow reconstruction; exact GLSL port of NdrToneOperator.
                // Low-blue orange and extreme-red objects stay protected; corrected pixels retain luma.
                if (uWarmSkinRedTame > 0.0) {
                    float oy = max(dot(max(t, vec3(0.0)), NEO_LUMA), 1e-8);
                    float rgRatio = s.r / max(s.g, 1e-8);
                    float bgRatio = s.b / max(s.g, 1e-8);
                    float moderateRed = ss(1.18, 1.55, rgRatio) * (1.0 - ss(2.80, 4.00, rgRatio));
                    float bluePresent = ss(0.28, 0.55, bgRatio) * (1.0 - ss(1.08, 1.35, bgRatio));
                    float exposureWindow = ss(0.04, 0.16, mappedY) * (1.0 - ss(6.5, 8.0, mappedY));
                    float redOverflow = ss(1.30, 2.10, rgRatio);
                    float w = clamp(
                        uWarmSkinRedTame * moderateRed * bluePresent * exposureWindow *
                            (0.45 + 0.55 * redOverflow),
                        0.0,
                        uWarmSkinRedTame
                    );
                    vec3 corrected = t;
                    corrected.r = mix(corrected.r, min(corrected.r, corrected.g * 1.42), w);
                    corrected.b = mix(corrected.b, max(corrected.b, corrected.g * 0.64), w * 0.68);
                    float correctedY = max(dot(max(corrected, vec3(0.0)), NEO_LUMA), 1e-8);
                    t = corrected * (oy / correctedY);
                }
                if (uSdrOutput == 1) {
                    // Display-referred SDR output for an 8-bit sRGB surface (the path that binds on this
                    // device). t is engine-linear BT.2020 (1.0 == 203-nit diffuse white), already tone-mapped
                    // + hue-preserving. Map to SDR keeping BOTH exposed and highlight data:
                    //  1) luminance-only highlight shoulder (no per-channel curve => no hue rotation);
                    //  2) BT.2020 -> BT.709 (sRGB primaries) via the official ITU-R matrix;
                    //  3) hue-preserving gamut compression toward the neutral axis (tames oversaturated red);
                    //  4) sRGB OETF.
                    vec3 tp = max(t, vec3(0.0));
                    float yl = max(dot(tp, NEO_LUMA), 1e-8);
                    // This is the exact Python-calibrated RAW-monitor adaptation: signal 1.0 maps to
                    // calibrated sRGB 0.80, while values above it retain a smooth monotone shoulder.
                    // It is presentation-only; the recording path below keeps the absolute-nit PQ
                    // shoulder and every value above 1.0.
                    float ysdr = sdrMonitorLuminance(yl);
                    vec3 rgb2020 = tp * (ysdr / yl);
                    // BT.2020 -> BT.709 linear (ITU-R primaries), column-major for GLSL.
                    const mat3 BT2020_TO_709 = mat3(
                        1.660491, -0.124550, -0.018151,
                       -0.587641,  1.132900, -0.100579,
                       -0.072850, -0.008349,  1.118730
                    );
                    vec3 rgb709 = BT2020_TO_709 * rgb2020;
                    // Hue-preserving compression of out-of-BT.709-gamut colour toward its own luma.
                    float y709 = max(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0);
                    float lo = min(min(rgb709.r, rgb709.g), rgb709.b);
                    if (lo < 0.0) {
                        float f = y709 / (y709 - lo);
                        rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                    }
                    // Compress upper-gamut overflow radially with explicit channel headroom. Mapping
                    // the strongest channel to exactly 1.0 still clipped red/cyan texture even when
                    // luminance was safely below white. The luma-relative ceiling preserves hue and
                    // leaves bright neutral pixels at their correctly tone-mapped luminance.
                    y709 = clamp(dot(rgb709, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                    float hi = max(max(rgb709.r, rgb709.g), rgb709.b);
                    float upperCeiling = mix(0.985, 0.965, ss(0.65, 0.98, y709));
                    float targetHi = max(y709, upperCeiling);
                    if (hi > targetHi) {
                        float f = (targetHi - y709) / max(hi - y709, 1e-8);
                        rgb709 = mix(vec3(y709), rgb709, clamp(f, 0.0, 1.0));
                    }
                    rgb709 = clamp(rgb709, 0.0, 1.0);
                    vec3 srgb = mix(
                        rgb709 * 12.92,
                        1.055 * pow(rgb709, vec3(1.0 / 2.4)) - 0.055,
                        step(vec3(0.0031308), rgb709)
                    );
                    fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
                    return;
                }
                float sl = max(dot(max(t, vec3(0.0)), NEO_LUMA), 1e-8);
                float nits = signalToNits(sl, peak);
                float sc = nits / sl;
                vec3 rgbNits = compressToPeakColorVolume(t * sc, nits, peak);
                // PQ monitor output is mapped to the physical panel capability. The encoded master
                // keeps [peak]; only the live monitor shoulder is reduced on lower-nit displays.
                float monitorPeak = clamp(uMonitorPeakNits, NEO_PQ_DIFFUSE_NITS, peak);
                if (monitorPeak < peak && nits > NEO_PQ_DIFFUSE_NITS) {
                    float monitorRange = max(monitorPeak - NEO_PQ_DIFFUSE_NITS, 1.0);
                    float monitorY = min(
                        NEO_PQ_DIFFUSE_NITS + monitorRange *
                            (1.0 - exp(-(nits - NEO_PQ_DIFFUSE_NITS) / monitorRange)),
                        monitorPeak
                    );
                    rgbNits *= monitorY / max(nits, 1e-8);
                    rgbNits = compressToPeakColorVolume(rgbNits, monitorY, monitorPeak);
                }
                vec3 pq = vec3(
                    pqFromNits(rgbNits.r),
                    pqFromNits(rgbNits.g),
                    pqFromNits(rgbNits.b)
                );
                fragColor = vec4(clamp(pq, 0.0, 1.0), 1.0);
            }
        """

        /**
         * Raw-YUV variant for devices exposing GL_EXT_YUV_target. The camera stream on I2405 reports
         * BT.2020/PQ/LIMITED (0x11C60000). Sampling it as ordinary samplerExternalOES delegates the
         * matrix/range expansion to the vendor driver—the only color transform shared by preview,
         * record, and conversion that remained unproven. This variant samples Y'CbCr directly and
         * applies deterministic BT.2020-NCL/BT.709 math before the existing PQ/NDR operator.
         */
        private val EXPLICIT_YUV_FRAGMENT_SHADER: String = FRAGMENT_SHADER
            .replace(
                "#extension GL_OES_EGL_image_external_essl3 : require",
                "#extension GL_EXT_YUV_target : require"
            )
            .replace(
                "uniform highp samplerExternalOES uTexture;",
                "uniform highp __samplerExternal2DY2YEXT uTexture;"
            )
            .replace(
                """vec3 sampleCameraInput(vec2 uv) {
                return texture(uTexture, uv).rgb;
            }""",
                """vec3 sampleCameraInput(vec2 uv) {
                vec3 yuv = texture(uTexture, uv).rgb;
                float y;
                float cb;
                float cr;
                if (uInputRange == 2) {
                    // Exact 10-bit legal-range expansion: Y 64..940, Cb/Cr 64..960.
                    y = (yuv.x - 64.0 / 1023.0) * (1023.0 / 876.0);
                    cb = (yuv.y - 512.0 / 1023.0) * (1023.0 / 896.0);
                    cr = (yuv.z - 512.0 / 1023.0) * (1023.0 / 896.0);
                } else {
                    y = yuv.x;
                    cb = yuv.y - 0.5;
                    cr = yuv.z - 0.5;
                }
                vec3 rgb;
                if (uInputPrimaries == 1) {
                    // BT.709 non-constant-luminance Y'CbCr -> R'G'B'.
                    rgb = vec3(
                        y + 1.5748 * cr,
                        y - 0.187324 * cb - 0.468124 * cr,
                        y + 1.8556 * cb
                    );
                } else {
                    // BT.2020 non-constant-luminance Y'CbCr -> R'G'B' (NDR path).
                    rgb = vec3(
                        y + 1.4746 * cr,
                        y - 0.164553 * cb - 0.571353 * cr,
                        y + 1.8814 * cb
                    );
                }
                return rgb;
            }"""
            )
    }
}
