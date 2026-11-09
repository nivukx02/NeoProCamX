package com.neoproc.neoproccamx.rendering

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.ui.SensorCropEngine
import com.neoproc.neoproccamx.utils.OrientationManager
import kotlin.math.abs

class CameraVulkanPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var previewSurface: Surface? = null
    private var streamWidth = 1920
    private var streamHeight = 1440
    private var sensorOrientation = 0
    private var deviceRotationDegrees = 0
    private var previewLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var aspectRatioOverride: Float? = null
    private var previewFillViewport = true
    private var displayContentAspectRatioOverride: Float? = null
    private var encoderContentAspectRatioOverride: Float? = null
    private var previousTransitionContentAspectRatioOverride: Float? = null
    private var aspectTransitionStartedUptimeMs = 0L
    private var lastResolvedDisplayContentAspectRatioOverride: Float? = null
    private var unifiedFrameGeometry: FrameGeometry? = null
    private var currentPreviewRotationDegrees = 0
    private var currentPreviewMirrored = false
    private var encoderWidth = 0
    private var encoderHeight = 0
    private var encoderFrameRate = 30
    private var encoderFeedEnabled = false
    private var encoderSurface: Surface? = null
    private var stabilizationStrength = 0f
    private var stabilizationAnchorX = 0.5f
    private var stabilizationAnchorY = 0.5f
    private var currentSceneRenderProfile = SceneRenderProfile(
        temporalBlend = 0f,
        spatialDenoise = 0f,
        chromaDenoise = 0f,
        shadowBoost = 0f,
        sharpening = 0f,
        warmth = 0f,
        contrast = 1f,
        saturation = 1f,
        highlightCompression = 0f,
        logStrength = 0f,
        ditherStrength = 0f,
        videoHdrFusionStrength = 0f
    )
    private var highLoadPreviewMode = false
    private var lutEnabled = false
    private var lutCubeSize = 0
    private var lutPackedValues: FloatArray? = null
    private var lutSummary: PreviewRenderTuning.LutSummary? = null
    private var yuvInputBound = false
    private var yuvInputWidth = 0
    private var yuvInputHeight = 0
    private var nativeCompositorActive = false
    // True while a transient native→native preview rebind is holding the last native presentation
    // (see setNativeCompositorActive) so the preview does not shrink to the direct-fit and snap back.
    private var nativePreviewHandoffHoldActive = false
    // Safety cap on the handoff hold: if native does not re-attach within this window, resolve to the
    // direct presentation for real so a failed rebind can never strand a stale frozen frame on screen.
    private val NATIVE_HANDOFF_MAX_HOLD_MS = 500L
    private val previewLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastPaintSignature: PreviewLayerPaintSignature? = null
    private var lastPresentationState: PreviewSurfacePresentationState? = null
    private var lastClipBounds: Rect? = null
    private val fpsTracker = PreviewFpsTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderFrameCallback = Choreographer.FrameCallback { onRenderFrameDispatched() }
    private val renderScheduler = PreviewRenderScheduler(
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        postToMain = { block -> mainHandler.post(block) },
        scheduleFrame = { Choreographer.getInstance().postFrameCallback(renderFrameCallback) }
    )
    
    // Optimized frame callback for maximum performance
    private fun onRenderFrameDispatched() {
        renderScheduler.onFrameDispatched()
        val transformChanged = applySurfaceTransform()
        val paintChanged = updatePreviewLayerPaint()
        if (transformChanged || paintChanged) {
            renderInvalidationListener?.invoke()
            // Use postInvalidateOnAnimation for smooth 60fps rendering
            postInvalidateOnAnimation()
        }
    }

    private var surfaceReadyListener: ((Surface?) -> Unit)? = null
    private var previewFpsListener: ((Float) -> Unit)? = null
    private var renderInvalidationListener: (() -> Unit)? = null
    private val surfaceCallbackGenerationGate = PreviewSurfaceCallbackGenerationGate<Surface>()
    init {
        holder.addCallback(this)
        previewLayerPaint.isDither = true
        previewLayerPaint.isFilterBitmap = true
        setLayerType(LAYER_TYPE_HARDWARE, previewLayerPaint)
        setLayerPaint(previewLayerPaint)
    }

    fun setOnSurfaceReadyListener(listener: ((Surface?) -> Unit)?) {
        surfaceReadyListener = listener
        val event = surfaceCallbackGenerationGate.snapshot()
        if (listener != null && event?.surface != null) {
            post {
                if (surfaceReadyListener === listener &&
                    surfaceCallbackGenerationGate.isCurrent(event) &&
                    runCatching { event.surface.isValid }.getOrDefault(false)
                ) {
                    listener(event.surface)
                }
            }
        }
    }

    fun setOnPreviewFpsListener(listener: ((Float) -> Unit)?) {
        previewFpsListener = listener
    }

    fun setOnRenderInvalidationListener(listener: (() -> Unit)?) {
        renderInvalidationListener = listener
    }

    fun getPreviewSurface(): Surface? {
        previewSurface
            ?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
            ?.let { return it }
        val current = runCatching { holder.surface }
            .getOrNull()
            ?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
        previewSurface = current
        if (current != null) surfaceCallbackGenerationGate.publish(current)
        return current
    }

    fun getCurrentPreviewRotationDegrees(): Int = currentPreviewRotationDegrees

    fun getEffectivePreviewRotationDegrees(): Int =
        effectiveDisplayRotationDegrees(currentPreviewRotationDegrees)

    fun isCurrentPreviewFrontFacing(): Boolean =
        previewLensFacing == CameraCharacteristics.LENS_FACING_FRONT

    fun isCurrentPreviewMirrored(): Boolean = currentPreviewMirrored

    fun setNativeCompositorActive(active: Boolean, transientHandoff: Boolean = false): Boolean {
        // PREVIEW SHRINK FIX (mode-switch / lens-crossing zoom): a native→native rebind first detaches
        // BOTH producers (camera + Vulkan swapchain) from this surface, so nothing is drawing to it —
        // the last native frame is frozen on screen. If we eagerly flip to the direct-preview path here
        // (buffer → smaller stream size, transform → letterbox fit) the preview visibly SHRINKS and then
        // snaps back to full size once native re-attaches. During a transient handoff we instead HOLD the
        // current native presentation (display-sized buffer + identity transform) so the frozen frame
        // stays full-size and the re-attach is seamless. A genuine direct fallback calls this again
        // WITHOUT the handoff flag (applying the direct presentation for real), and a safety timeout does
        // the same if native never returns — so this can never strand the preview in a stale state.
        if (!active && transientHandoff && nativeCompositorActive) {
            nativePreviewHandoffHoldActive = true
            mainHandler.removeCallbacks(nativeHandoffExpiryRunnable)
            mainHandler.postDelayed(nativeHandoffExpiryRunnable, NATIVE_HANDOFF_MAX_HOLD_MS)
            return previewSurface != null
        }
        mainHandler.removeCallbacks(nativeHandoffExpiryRunnable)
        nativePreviewHandoffHoldActive = false
        if (nativeCompositorActive == active) return false
        nativeCompositorActive = active
        // Buffer size must change: native compositor uses view dimensions,
        // direct preview uses stream dimensions. Update immediately.
        updateSurfaceBufferSize()
        updateTransformState()
        requestRender()
        return previewSurface != null
    }

    // Expires a transient native-preview handoff hold: if native did not re-attach in time, resolve to
    // the direct presentation for real (no producer can be mid-write once this fires, so it is safe).
    private val nativeHandoffExpiryRunnable = Runnable {
        if (nativePreviewHandoffHoldActive) {
            nativePreviewHandoffHoldActive = false
            if (nativeCompositorActive) {
                nativeCompositorActive = false
                updateSurfaceBufferSize()
                updateTransformState()
                requestRender()
            }
        }
    }

    fun requestRender() {
        renderScheduler.requestRender()
    }

    fun notifyFramePresented(timestampNs: Long) {
        fpsTracker.recordFrame(timestampNs)?.let(previewFpsListener ?: return)
    }

    /**
     * Returns the unsmoothed cadence from the most recently completed presentation window.
     *
     * The listener value remains exponentially smoothed for user-facing stability and health
     * policy. Device QA needs the exact final-display window, otherwise a short startup burst can
     * bias several later windows after the physical presentation route has already settled.
     */
    fun latestMeasuredPreviewFps(): Float? = fpsTracker.latestStableMeasuredFps()

    fun resetPreviewFpsTracking() {
        fpsTracker.reset()
    }

    fun setLensFacing(lensFacing: Int) {
        previewLensFacing = lensFacing
        updateTransformState()
    }

    fun setFrameGeometry(geometry: FrameGeometry?) {
        unifiedFrameGeometry = geometry
        geometry?.let {
            previewLensFacing = it.lensFacing
            sensorOrientation = OrientationManager.normalizeDegrees(it.sensorOrientationDegrees)
            deviceRotationDegrees = OrientationManager.normalizeDegrees(it.deviceRotationDegrees)
        }
        updateTransformState()
    }

    fun setStreamGeometry(width: Int, height: Int, sensorOrientation: Int) {
        streamWidth = width.coerceAtLeast(1)
        streamHeight = height.coerceAtLeast(1)
        this.sensorOrientation = OrientationManager.normalizeDegrees(sensorOrientation)
        this.previewLensFacing = previewLensFacing
        updateSurfaceBufferSize()
        updateTransformState()
    }

    fun setDeviceRotationDegrees(degrees: Int) {
        deviceRotationDegrees = OrientationManager.normalizeDegrees(degrees)
        updateTransformState()
    }

    fun setEncoderSize(width: Int, height: Int) {
        encoderWidth = width.coerceAtLeast(0)
        encoderHeight = height.coerceAtLeast(0)
    }

    fun setEncoderFrameRate(fps: Int) {
        encoderFrameRate = fps.coerceAtLeast(1)
    }

    fun setEncoderFeedEnabled(enabled: Boolean) {
        encoderFeedEnabled = enabled
    }

    fun setEncoderSurface(surface: Surface?) {
        encoderSurface = surface
    }

    fun setStabilizationStrength(strength: Float) {
        stabilizationStrength = strength.coerceIn(0f, 1f)
        updateTransformState()
    }

    fun setStabilizationAnchor(normalizedX: Float, normalizedY: Float) {
        stabilizationAnchorX = normalizedX.coerceIn(0f, 1f)
        stabilizationAnchorY = normalizedY.coerceIn(0f, 1f)
        updateTransformState()
    }

    fun setAspectRatioOverride(aspectRatio: Float?, fillViewport: Boolean) {
        setPresentationAspectRatios(
            displayContentAspectRatio = displayContentAspectRatioOverride,
            viewportAspectRatio = aspectRatio,
            fillViewport = fillViewport
        )
    }

    fun setContentAspectRatioOverride(aspectRatio: Float?) {
        val normalized = normalizeContentAspect(aspectRatio)
        setPresentationAspectRatios(
            displayContentAspectRatio = normalized,
            viewportAspectRatio = aspectRatioOverride,
            fillViewport = previewFillViewport
        )
        encoderContentAspectRatioOverride = normalized
    }

    fun setDisplayContentAspectRatioOverride(aspectRatio: Float?) {
        setPresentationAspectRatios(
            displayContentAspectRatio = aspectRatio,
            viewportAspectRatio = aspectRatioOverride,
            fillViewport = previewFillViewport
        )
    }

    /**
     * Commits display framing as one UI transaction. Aspect changes never alter this View's measured
     * dimensions, so requesting a complete hierarchy layout here only stalls the next frame. The
     * SurfaceView transform and clip are updated atomically while its producer and buffer size stay
     * untouched.
     */
    fun setPresentationAspectRatios(
        displayContentAspectRatio: Float?,
        viewportAspectRatio: Float?,
        fillViewport: Boolean
    ) {
        val normalizedContent = normalizeContentAspect(displayContentAspectRatio)
        val normalizedViewport = normalizeContentAspect(viewportAspectRatio)
        val contentChanged = !contentAspectsMatch(
            displayContentAspectRatioOverride,
            normalizedContent
        )
        val viewportChanged = !contentAspectsMatch(aspectRatioOverride, normalizedViewport) ||
            previewFillViewport != fillViewport
        if (!contentChanged && !viewportChanged) return

        if (contentChanged) {
            previousTransitionContentAspectRatioOverride =
                lastResolvedDisplayContentAspectRatioOverride ?: currentContentAspectRatioFallback()
            aspectTransitionStartedUptimeMs = SystemClock.uptimeMillis()
        }
        displayContentAspectRatioOverride = normalizedContent
        aspectRatioOverride = normalizedViewport
        previewFillViewport = fillViewport
        updateTransformState()
    }

    fun setEncoderContentAspectRatioOverride(aspectRatio: Float?) {
        encoderContentAspectRatioOverride = aspectRatio?.takeIf { it > 0f }
    }

    fun setSceneRenderProfile(profile: SceneRenderProfile) {
        currentSceneRenderProfile = profile
        requestRender()
    }

    fun setHighLoadPreviewMode(enabled: Boolean) {
        highLoadPreviewMode = enabled
        requestRender()
    }

    fun setLutTexture(packedRgb: FloatArray?, size: Int) {
        lutPackedValues = packedRgb
        lutCubeSize = size.coerceAtLeast(0)
        lutSummary = PreviewRenderTuning.summarizeLut(packedRgb, lutCubeSize)
        requestRender()
    }

    fun enableLUT(on: Boolean) {
        lutEnabled = on
        requestRender()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setYuvInputTextures(yTexId: Int, uTexId: Int, vTexId: Int, width: Int, height: Int) {
        yuvInputBound = yTexId != 0 && uTexId != 0 && vTexId != 0
        yuvInputWidth = width.coerceAtLeast(0)
        yuvInputHeight = height.coerceAtLeast(0)
        requestRender()
    }

    fun clearYuvInputTextures() {
        yuvInputBound = false
        yuvInputWidth = 0
        yuvInputHeight = 0
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        updateSurfaceBufferSize(holder)
        previewSurface = holder.surface.takeIf { it.isValid }
        val event = surfaceCallbackGenerationGate.publish(previewSurface)
        updateTransformState()
        // Immediate callback for faster camera connection
        dispatchSurfaceCallbackIfCurrent(event)
        // Request immediate render to show preview ASAP
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateSurfaceBufferSize(holder)
        previewSurface = holder.surface.takeIf { it.isValid }
        val event = surfaceCallbackGenerationGate.publish(previewSurface)
        updateTransformState()
        // Immediate callback for faster preview updates
        dispatchSurfaceCallbackIfCurrent(event)
        // Force immediate render for smooth preview
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        previewSurface = null
        val event = surfaceCallbackGenerationGate.publish(null)
        fpsTracker.reset()
        if (renderScheduler.cancelScheduledFrame()) {
            Choreographer.getInstance().removeFrameCallback(renderFrameCallback)
        }
        post { dispatchSurfaceCallbackIfCurrent(event) }
    }

    private fun dispatchSurfaceCallbackIfCurrent(event: PreviewSurfaceCallbackEvent<Surface>) {
        if (!surfaceCallbackGenerationGate.isCurrent(event)) return
        val surface = event.surface
        if (surface != null && !runCatching { surface.isValid }.getOrDefault(false)) return
        surfaceReadyListener?.invoke(surface)
    }

    override fun onDetachedFromWindow() {
        if (renderScheduler.cancelScheduledFrame()) {
            Choreographer.getInstance().removeFrameCallback(renderFrameCallback)
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requestedWidth = MeasureSpec.getSize(widthMeasureSpec)
        val requestedHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(requestedWidth, requestedHeight)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateTransformState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateTransformState() }
            return
        }
        if (width <= 0 || height <= 0) return
        val state = PreviewTransformStateResolver.resolve(
            geometry = unifiedFrameGeometry,
            sensorOrientation = sensorOrientation,
            deviceRotationDegrees = deviceRotationDegrees,
            lensFacing = previewLensFacing
        )
        currentPreviewRotationDegrees = state.rotationDegrees
        currentPreviewMirrored = state.mirrored
        applySurfaceTransform()
        updatePreviewLayerPaint()
    }

    private fun updateSurfaceBufferSize(surfaceHolder: SurfaceHolder = holder) {
        // PREVIEW STRETCH FIX: When the Vulkan native compositor is active,
        // it renders aspect-correct framing internally via the swapchain.
        // The SurfaceView buffer must match the VIEW dimensions (display pixels)
        // so SurfaceFlinger performs a 1:1 pixel mapping. Setting it to the
        // camera stream size (e.g. 1920x1440 for 4:3) causes SurfaceFlinger
        // to stretch the buffer to the phone display, distorting the preview.
        if (nativeCompositorActive && width > 0 && height > 0) {
            surfaceHolder.setFixedSize(width, height)
        } else {
            surfaceHolder.setFixedSize(
                streamWidth.coerceAtLeast(1),
                streamHeight.coerceAtLeast(1)
            )
        }
    }

    private fun applySurfaceTransform(): Boolean {
        if (width <= 0 || height <= 0) return false
        val resolvedDisplayContentAspectRatioOverride =
            resolveDisplayContentAspectRatioOverride()

        val framing = PreviewFramingResolver.resolve(
            PreviewFramingRequest(
                viewWidth = width,
                viewHeight = height,
                streamWidth = streamWidth,
                streamHeight = streamHeight,
                displayContentAspectRatioOverride = resolvedDisplayContentAspectRatioOverride,
                aspectRatioOverride = aspectRatioOverride,
                previewFillViewport = previewFillViewport,
                rotationDegrees = effectiveDisplayRotationDegrees(currentPreviewRotationDegrees),
                mirrored = currentPreviewMirrored,
                nativeCompositorActive = nativeCompositorActive,
                sourceCropRegion = unifiedFrameGeometry?.normalizedCropRegion,
                stabilizationStrength = stabilizationStrength,
                stabilizationAnchorX = stabilizationAnchorX,
                stabilizationAnchorY = stabilizationAnchorY
                )
        )
        val clipChanged = applyPreviewClipBounds(framing.presentation.localClipBounds)

        // Surface presentation is the single aspect-framing owner for direct, GL and Vulkan paths.
        // Vulkan retains only orientation/mirroring, preventing double aspect compensation.
        val effectivePresentation = framing.presentation

        if (!PreviewPresentationChangeDetector.hasMeaningfulChange(
                previous = lastPresentationState,
                current = effectivePresentation
            )
            && !clipChanged
        ) {
            return false
        }
        pivotX = 0f
        pivotY = 0f
        scaleX = effectivePresentation.scaleX
        scaleY = effectivePresentation.scaleY
        translationX = effectivePresentation.translationX
        translationY = effectivePresentation.translationY
        rotation = effectivePresentation.rotation
        lastPresentationState = effectivePresentation
        return true
    }

    private fun applyPreviewClipBounds(
        localBounds: PreviewTransformMath.RectBounds?
    ): Boolean {
        val nextClipBounds = localBounds?.let { bounds ->
            Rect(
                kotlin.math.floor(bounds.left.toDouble()).toInt().coerceIn(0, width),
                kotlin.math.floor(bounds.top.toDouble()).toInt().coerceIn(0, height),
                kotlin.math.ceil(bounds.right.toDouble()).toInt().coerceIn(0, width),
                kotlin.math.ceil(bounds.bottom.toDouble()).toInt().coerceIn(0, height)
            )
        }
        if (lastClipBounds == nextClipBounds) return false
        clipBounds = nextClipBounds
        lastClipBounds = nextClipBounds?.let { Rect(it) }
        return true
    }

    private fun updatePreviewLayerPaint(): Boolean {
        val update = PreviewLayerPaintController.update(
            paint = previewLayerPaint,
            lastSignature = lastPaintSignature,
            profile = currentSceneRenderProfile,
            highLoadPreviewMode = highLoadPreviewMode,
            lutEnabled = lutEnabled,
            lutSummary = lutSummary,
            yuvInputBound = yuvInputBound,
            nativeCompositorActive = nativeCompositorActive,
            applyPaint = ::setLayerPaint
        )
        lastPaintSignature = update.signature
        return update.changed
    }

    fun getResolvedDisplayAspect(): Float {
        return PreviewFramingResolver.resolveTargetAspect(
            PreviewFramingRequest(
                viewWidth = width,
                viewHeight = height,
                streamWidth = streamWidth,
                streamHeight = streamHeight,
                displayContentAspectRatioOverride = resolveDisplayContentAspectRatioOverride(),
                aspectRatioOverride = aspectRatioOverride,
                previewFillViewport = previewFillViewport,
                rotationDegrees = effectiveDisplayRotationDegrees(currentPreviewRotationDegrees),
                mirrored = currentPreviewMirrored,
                nativeCompositorActive = nativeCompositorActive,
                sourceCropRegion = unifiedFrameGeometry?.normalizedCropRegion,
                stabilizationStrength = stabilizationStrength,
                stabilizationAnchorX = stabilizationAnchorX,
                stabilizationAnchorY = stabilizationAnchorY
            )
        )
    }

    private fun resolveDisplayContentAspectRatioOverride(): Float? =
        PreviewAspectTransitionGuard.resolveDisplayContentAspectOverride(
            requestedContentAspect = displayContentAspectRatioOverride,
            previousPresentedContentAspect = previousTransitionContentAspectRatioOverride,
            transitionElapsedMs = transitionElapsedMs(),
            geometryOutputAspect = unifiedFrameGeometry?.outputAspectRatio,
            geometrySensorAspect = unifiedFrameGeometry?.sensorAspectRatio,
            streamWidth = streamWidth,
            streamHeight = streamHeight,
            nativeCompositorActive = nativeCompositorActive
        ).also { resolvedAspect ->
            lastResolvedDisplayContentAspectRatioOverride = resolvedAspect
            if (contentAspectsMatch(resolvedAspect, displayContentAspectRatioOverride)) {
                previousTransitionContentAspectRatioOverride = null
                aspectTransitionStartedUptimeMs = 0L
            }
        }

    private fun transitionElapsedMs(): Long? =
        aspectTransitionStartedUptimeMs.takeIf { it > 0L }
            ?.let { SystemClock.uptimeMillis() - it }

    private fun currentContentAspectRatioFallback(): Float =
        unifiedFrameGeometry?.outputAspectRatio?.takeIf { it.isFinite() && it > 0f }
            ?: unifiedFrameGeometry?.sensorAspectRatio?.takeIf { it.isFinite() && it > 0f }
            ?: SensorCropEngine.normalizeLongShortRatio(
                streamWidth.coerceAtLeast(1).toFloat() / streamHeight.coerceAtLeast(1).toFloat()
            )

    private fun contentAspectsMatch(first: Float?, second: Float?): Boolean {
        if (first == null || second == null) return first == second
        val firstNormalized = SensorCropEngine.normalizeLongShortRatio(first)
        val secondNormalized = SensorCropEngine.normalizeLongShortRatio(second)
        return abs(firstNormalized - secondNormalized) <= 0.001f
    }

    private fun normalizeContentAspect(aspectRatio: Float?): Float? =
        aspectRatio?.takeIf { it.isFinite() && it > 0f }

    private fun effectiveDisplayRotationDegrees(rotationDegrees: Int): Int {
        return PreviewRotationPolicy.resolve(
            rotationDegrees = rotationDegrees,
            sensorOrientation = sensorOrientation,
            lensFacing = previewLensFacing
        )
    }
}
