package com.neoproc.neoproccamx.ui.overlays

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.neoproc.neoproccamx.ai.DetectedFace
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.pipeline.FocusPeakingMask
import com.neoproc.neoproccamx.pipeline.ZebraMask
import com.neoproc.neoproccamx.ui.CameraUiRotationPolicy
import com.neoproc.neoproccamx.ui.FocusAssistVisual
import com.neoproc.neoproccamx.utils.OrientationManager
import java.nio.ByteBuffer as NioByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.core.content.ContextCompat
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * Preview-aware cinema overlay.
 *
 * The restored backup had a stripped-down placeholder that drew everything full-screen.
 * This version maps grid, focus UI, detections, and diagnostic overlays into the fitted
 * preview content rect again so aspect changes and touch interactions behave correctly.
 */
class UIOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    def: Int = 0
) : View(ctx, attrs, def), SensorEventListener {

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun effectiveGridMode(): GridMode = gridMode

    var gridMode = GridMode.NONE
    var showHistogram = false
    var showFaces = true
    var showCropDebug = false
    var cleanHudEnabled = true

    private var histogram: IntArray? = null
    private var faces: List<DetectedFace> = emptyList()
    private var focusPeakingMask: FocusPeakingMask? = null
    private var zebraMask: ZebraMask? = null
    private var currentSubjectMask: SubjectMask? = null
    private var subjectMaskLevel = 0f
    private var recording = false
    private var durationMs = 0L
    private var subjectAssistFaceDetectionEnabled = true
    private var subjectAssistEyeAutofocusEnabled = false

    var hardwareProfile: String? = null
        private set

    // Safe Area overlay state (Open Gate composition guides)
    private var safeAreaOverlaySet: SafeAreaOverlaySet? = null

    private var focusState = FocusOverlayState()
    private var focusUiGeneration = 0
    private var focusRevealAnimator: ValueAnimator? = null
    private var focusAnimationProgress = 1f

    private var previewAspectRatio: Float? = null
    private var previewRotationDegrees = 0
    private var deviceUiRotationDegrees = 0
    private var previewMirrored = false
    private var previewFillViewport = false
    private var previewSourceAspectRatio: Float? = null
    private var focusAssistVisual = FocusAssistVisual.TAP
    private val diagnosticMaskMatrix = Matrix()
    private val diagnosticMaskSource = FloatArray(8)
    private val reticleIconBounds = Rect()
    private val reticleDrawables = mutableMapOf<FocusAssistVisual, Drawable?>()
    private val subjectCueIconBounds = Rect()
    private val subjectCueDrawables = mutableMapOf<SubjectAssistCueType, Drawable?>()

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x50FFFFFF
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x90FFFFFF.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7AF1D0.toInt()
        strokeWidth = 1.35f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA05CC8FF.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val focusAmber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFCC00.toInt()
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val focusGreen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF88.toInt()
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }
    private val tapFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEFFFFFFF.toInt()
        strokeWidth = 1.8f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val sliderTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val sliderTrackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val sliderActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD24A.toInt()
        strokeWidth = 3.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val sliderThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val sliderThumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD24A.toInt()
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val referenceEvTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDFFFFFFF.toInt()
        strokeWidth = 1.65f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val referenceEvSunFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF4FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val referenceEvSunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF4FFFFFF.toInt()
        strokeWidth = 1.55f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11f)
    }
    private val detectionBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val detectionBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }
    private val subjectCueFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x7A000000
        style = Paint.Style.FILL
    }
    private val subjectCueRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD24A.toInt()
        strokeWidth = 1.8f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val peakingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xFFFFD24A.toInt(), PorterDuff.Mode.SRC_IN)
        isFilterBitmap = true
    }
    private val zebraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0xB8FF5A52.toInt(), PorterDuff.Mode.SRC_IN)
        isFilterBitmap = true
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(0x66FFFFFF, PorterDuff.Mode.SRC_IN)
        alpha = 0
    }

    private var peakingBitmap: Bitmap? = null
    private var zebraBitmap: Bitmap? = null
    private var subjectMaskBitmap: Bitmap? = null
    private val safeAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val safeAreaLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = sp(9f)
        textAlign = Paint.Align.LEFT
    }

    init {
        setWillNotDraw(false)
    }

    fun setHistogram(hist: IntArray) {
        if (histogram?.contentEquals(hist) == true) return
        histogram = hist.copyOf()
        postInvalidateOnAnimation()
    }

    fun setFaces(f: List<DetectedFace>) {
        if (faces == f) return
        faces = f
        postInvalidateOnAnimation()
    }

    fun setSubjectAssistModes(faceDetectionEnabled: Boolean, eyeAutofocusEnabled: Boolean) {
        if (
            subjectAssistFaceDetectionEnabled == faceDetectionEnabled &&
            subjectAssistEyeAutofocusEnabled == eyeAutofocusEnabled
        ) {
            return
        }
        subjectAssistFaceDetectionEnabled = faceDetectionEnabled
        subjectAssistEyeAutofocusEnabled = eyeAutofocusEnabled
        postInvalidateOnAnimation()
    }

    fun setFocusPeakingMask(mask: FocusPeakingMask?) {
        if (focusPeakingMask === mask) return
        focusPeakingMask = mask
        if (mask == null) {
            peakingBitmap = null
            postInvalidateOnAnimation()
            return
        }
        val bitmap = ensureBitmap(mask.maskWidth, mask.maskHeight, 0)
        bitmap.copyPixelsFromBuffer(NioByteBuffer.wrap(mask.alphaMask))
        postInvalidateOnAnimation()
    }

    fun setZebraMask(mask: ZebraMask?) {
        if (zebraMask === mask) return
        zebraMask = mask
        if (mask == null) {
            zebraBitmap = null
            postInvalidateOnAnimation()
            return
        }
        val bitmap = ensureBitmap(mask.maskWidth, mask.maskHeight, 1)
        bitmap.copyPixelsFromBuffer(NioByteBuffer.wrap(mask.alphaMask))
        postInvalidateOnAnimation()
    }

    fun setRecording(isRec: Boolean) {
        if (recording == isRec) return
        recording = isRec
        postInvalidateOnAnimation()
    }

    fun setRecordingDuration(ms: Long) {
        if (durationMs == ms) return
        durationMs = ms
        if (!cleanHudEnabled) postInvalidateOnAnimation()
    }

    fun setHardwareProfile(profile: String?) {
        if (hardwareProfile == profile) return
        hardwareProfile = profile
        postInvalidateOnAnimation()
    }

    /**
     * Set safe area overlay guides for Open Gate composition.
     * Pass null to hide all safe area guides.
     */
    fun setSafeAreaOverlays(overlays: SafeAreaOverlaySet?) {
        if (safeAreaOverlaySet == overlays) return
        safeAreaOverlaySet = overlays
        postInvalidateOnAnimation()
    }

    fun setAFFocused(f: Boolean) {
        if (focusState.afFocused == f) return
        focusState = focusState.copy(afFocused = f)
        postInvalidateOnAnimation()
    }

    fun setPreviewArea(aspect: Float?, fillViewport: Boolean, sourceAspectRatio: Float?) {
        if (
            previewAspectRatio == aspect &&
            previewFillViewport == fillViewport &&
            previewSourceAspectRatio == sourceAspectRatio
        ) {
            return
        }
        previewAspectRatio = aspect
        previewFillViewport = fillViewport
        previewSourceAspectRatio = sourceAspectRatio
        postInvalidateOnAnimation()
    }

    fun setPreviewTransform(rotationDegrees: Int, mirrored: Boolean) {
        if (previewRotationDegrees == rotationDegrees && previewMirrored == mirrored) return
        previewRotationDegrees = rotationDegrees
        previewMirrored = mirrored
        postInvalidateOnAnimation()
    }

    fun setDeviceUiRotationDegrees(degrees: Int) {
        val normalizedDegrees = OrientationManager.normalizeDegrees(degrees)
        if (deviceUiRotationDegrees == normalizedDegrees) return
        deviceUiRotationDegrees = normalizedDegrees
        postInvalidateOnAnimation()
    }

    fun setFocusAssistVisual(visual: FocusAssistVisual) {
        if (focusAssistVisual == visual) return
        focusAssistVisual = visual
        postInvalidateOnAnimation()
    }

    fun setSubjectMask(mask: SubjectMask?) {
        if (currentSubjectMask === mask) return
        currentSubjectMask = mask
        if (mask == null) {
            subjectMaskBitmap = null
        } else {
            val bitmap = ensureBitmap(mask.maskWidth, mask.maskHeight, 2)
            bitmap.copyPixelsFromBuffer(NioByteBuffer.wrap(mask.alphaMask))
        }
        postInvalidateOnAnimation()
    }

    fun setSubjectMaskLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val resolved = if (strictHighEndEnabled() && clamped > 0f) {
            clamped.coerceAtLeast(0.35f)
        } else {
            clamped
        }
        if (subjectMaskLevel == resolved) return
        subjectMaskLevel = resolved
        postInvalidateOnAnimation()
    }

    fun updateFocusExposure(value: Int, min: Int, max: Int) {
        val updatedState = focusState.withExposureRange(value, min, max)
        if (updatedState == focusState) return
        focusState = updatedState
        postInvalidateOnAnimation()
    }

    fun showFocusAt(nx: Float, ny: Float, locked: Boolean = false, showAdjustment: Boolean = false) {
        focusState = focusState.showAt(
            normalizedX = nx,
            normalizedY = ny,
            locked = locked,
            showAdjustment = showAdjustment
        )
        startFocusRevealAnimation()
        val generation = ++focusUiGeneration
        postInvalidateOnAnimation()
        FocusOverlayVisibilityPolicy.autoHideDelayMs(
            locked = locked,
            showAdjustment = showAdjustment
        )?.let { hideDelayMs ->
            postDelayed({
                if (focusUiGeneration != generation || focusState.locked) return@postDelayed
                focusState = focusState.hide()
                postInvalidateOnAnimation()
            }, hideDelayMs)
        }
    }

    fun hideFocusUi() {
        focusUiGeneration += 1
        focusRevealAnimator?.cancel()
        focusAnimationProgress = 1f
        focusState = focusState.hide()
        postInvalidateOnAnimation()
    }

    fun hasFocusUi() = focusState.visible
    fun hasLockedFocusUi() = focusState.visible && focusState.locked

    fun isFocusUiHit(x: Float, y: Float): Boolean {
        val point = rotatedFocusUiPoint(x, y)
        return FocusOverlayGeometry.isFocusUiHit(
            x = point.x,
            y = point.y,
            layout = focusUiLayout(),
            state = focusState
        )
    }

    fun isFocusLockBadgeHit(x: Float, y: Float): Boolean {
        val point = rotatedFocusUiPoint(x, y)
        val badge = focusUiLayout().lockBadgeRect ?: return false
        return badge.contains(point.x, point.y)
    }

    fun focusEvValueForTouch(x: Float, y: Float): Int? {
        val point = rotatedFocusUiPoint(x, y)
        return FocusOverlayGeometry.focusEvValueForTouch(
            x = point.x,
            y = point.y,
            layout = focusUiLayout(),
            state = focusState,
            density = resources.displayMetrics.density
        )
    }

    fun normalizeTouchPoint(x: Float, y: Float): PointF {
        return FocusOverlayGeometry.normalizeTouchPoint(x, y, previewRect())
    }

    fun normalizeTouchPointOrNull(x: Float, y: Float): PointF? {
        return FocusOverlayGeometry.normalizeTouchPointOrNull(x, y, previewRect())
    }

    fun startSensors() = Unit
    fun stopSensors() { sm.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 2) return
        val updatedRoll = Math.toDegrees(kotlin.math.atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
        if (abs(updatedRoll) < 0.1f) return
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        val rect = previewRect()
        drawGrid(canvas, rect)
        drawSafeAreaOverlays(canvas)
        drawDiagnosticOverlays(canvas, rect)
        drawDetectionBoxes(canvas, rect)
        drawSubjectAssistCues(canvas, rect)
        drawHistogram(canvas, rect)
        drawHardwareProfile(canvas, rect)
        drawFocusUi(canvas, rect)
    }

    private fun drawHardwareProfile(canvas: Canvas, rect: RectF) {
        val profile = hardwareProfile ?: return
        if (!UIOverlayChromePolicy.shouldDrawHardwareProfileBadge(cleanHudEnabled, profile)) return
        val text = "HAL Profile: $profile"
        val textWidth = infoPaint.measureText(text)
        val textPaddingX = dp(8f)
        val textPaddingY = dp(4f)
        val badgeHeight = infoPaint.textSize + textPaddingY * 2
        val badgeWidth = textWidth + textPaddingX * 2
        
        val badgeRect = RectF(
            rect.right - badgeWidth - dp(16f),
            rect.top + dp(16f),
            rect.right - dp(16f),
            rect.top + dp(16f) + badgeHeight
        )
        
        objectPaint.style = Paint.Style.FILL
        objectPaint.color = 0x66000000
        canvas.drawRoundRect(badgeRect, dp(4f), dp(4f), objectPaint)
        
        canvas.drawText(text, badgeRect.left + textPaddingX, badgeRect.centerY() - (infoPaint.descent() + infoPaint.ascent()) * 0.5f, infoPaint)
        
        objectPaint.style = Paint.Style.STROKE
        objectPaint.color = 0xA05CC8FF.toInt()
    }

    private fun drawGrid(canvas: Canvas, rect: RectF) {
        OverlayDiagnosticsGeometry.gridLines(effectiveGridMode(), rect).forEach { line ->
            canvas.drawLine(line.startX, line.startY, line.endX, line.endY, gridPaint)
        }
    }

    /**
     * Draw Safe Area composition guides (Open Gate overlays).
     * These are geometrically aligned with FrameGeometry for zero parallax error.
     */
    private fun drawSafeAreaOverlays(canvas: Canvas) {
        val overlays = safeAreaOverlaySet ?: return
        if (!overlays.active) return

        // Anchor guides to the REAL on-screen preview rectangle (the same rect the grid / focus /
        // face boxes use), inscribing each guide's aspect against the preview's true aspect. This
        // makes the 2.39:1 scope hug the actual 16:9 / 4:3 preview image instead of floating in dead
        // space, and it follows portrait/landscape for free (previewRect already encodes orientation).
        val preview = previewRect()
        val previewW = preview.width()
        val previewH = preview.height()
        if (previewW <= 0f || previewH <= 0f) return
        val previewAspect = previewW / previewH

        overlays.guides.forEach { guide ->
            safeAreaPaint.color = guide.tintColor
            safeAreaPaint.strokeWidth = guide.strokeWidthDp * resources.displayMetrics.density

            val crop = SafeAreaOverlayEngine.scopeCropFractions(guide.aspectRatio, previewAspect)
            val left = preview.left + crop[0] * previewW
            val top = preview.top + crop[1] * previewH
            val right = preview.left + crop[2] * previewW
            val bottom = preview.top + crop[3] * previewH
            canvas.drawRect(left, top, right, bottom, safeAreaPaint)

            // Draw label near the top-left corner of the guide
            val labelX = left + dp(4f)
            val labelY = top - dp(3f)
            if (labelY > dp(10f)) {
                safeAreaLabelPaint.color = guide.tintColor or 0xFF000000.toInt()
                canvas.drawText(guide.label, labelX, labelY, safeAreaLabelPaint)
            }
        }
    }

    private fun drawDiagnosticOverlays(canvas: Canvas, rect: RectF) {
        peakingBitmap?.let { drawDiagnosticMask(canvas, it, rect, peakingPaint) }
        zebraBitmap?.let { drawDiagnosticMask(canvas, it, rect, zebraPaint) }
        if (subjectMaskLevel > 0f) {
            subjectMaskBitmap?.let {
                val alphaScale = if (strictHighEndEnabled()) 168f else 132f
                val maxAlpha = if (strictHighEndEnabled()) 180 else 160
                maskPaint.alpha = (subjectMaskLevel * alphaScale).roundToInt().coerceIn(0, maxAlpha)
                drawDiagnosticMask(canvas, it, rect, maskPaint)
            }
        }
    }

    private fun drawDiagnosticMask(
        canvas: Canvas,
        bitmap: Bitmap,
        rect: RectF,
        paint: Paint
    ) {
        diagnosticMaskSource[0] = 0f
        diagnosticMaskSource[1] = 0f
        diagnosticMaskSource[2] = bitmap.width.toFloat()
        diagnosticMaskSource[3] = 0f
        diagnosticMaskSource[4] = bitmap.width.toFloat()
        diagnosticMaskSource[5] = bitmap.height.toFloat()
        diagnosticMaskSource[6] = 0f
        diagnosticMaskSource[7] = bitmap.height.toFloat()
        val destination = OverlayDiagnosticsGeometry.diagnosticMaskCorners(
            container = rect,
            rotationDegrees = previewRotationDegrees,
            mirrored = previewMirrored
        )
        diagnosticMaskMatrix.reset()
        diagnosticMaskMatrix.setPolyToPoly(diagnosticMaskSource, 0, destination, 0, 4)
        canvas.withClip(rect) {
            drawBitmap(bitmap, diagnosticMaskMatrix, paint)
        }
    }

    private fun drawDetectionBoxes(canvas: Canvas, rect: RectF) {
        if (showFaces) {
            faces.forEach { face ->
                val mappedBounds = OverlayDiagnosticsGeometry.mapNormalizedRect(face.bounds, rect)
                canvas.drawRect(mappedBounds, facePaint)
                val eyeRadius = (minOf(mappedBounds.width(), mappedBounds.height()) * 0.05f)
                    .coerceIn(dp(2.25f), dp(8f))
                val leftEye = face.leftEye?.let { eye ->
                    PointF(
                        rect.left + eye.x.coerceIn(0f, 1f) * rect.width(),
                        rect.top + eye.y.coerceIn(0f, 1f) * rect.height()
                    )
                }
                val rightEye = face.rightEye?.let { eye ->
                    PointF(
                        rect.left + eye.x.coerceIn(0f, 1f) * rect.width(),
                        rect.top + eye.y.coerceIn(0f, 1f) * rect.height()
                    )
                }
                leftEye?.let { canvas.drawCircle(it.x, it.y, eyeRadius, eyePaint) }
                rightEye?.let { canvas.drawCircle(it.x, it.y, eyeRadius, eyePaint) }
                if (leftEye != null && rightEye != null) {
                    canvas.drawLine(leftEye.x, leftEye.y, rightEye.x, rightEye.y, eyePaint)
                }
            }
        }
    }

    private fun drawHistogram(canvas: Canvas, rect: RectF) {
        val hist = histogram ?: return
        val layout = OverlayDiagnosticsGeometry.histogramLayout(
            rect = rect,
            histogram = hist,
            showHistogram = showHistogram,
            density = resources.displayMetrics.density
        ) ?: return
        val paint = if (strictHighEndEnabled()) facePaint else gridPaint
        for (index in hist.indices) {
            val x = layout.left + (index * layout.step)
            val barHeight = (hist[index].toFloat() / layout.maxValue.toFloat()) * layout.height
            canvas.drawLine(x, layout.top + layout.height, x, layout.top + layout.height - barHeight, paint)
        }
    }

    private fun drawRecordingHud(canvas: Canvas, rect: RectF) {
        if (!recording) return
        val text = OverlayDiagnosticsGeometry.formatDuration(durationMs)
        val y = rect.top + dp(18f)
        canvas.drawCircle(rect.left + dp(12f), y - dp(4f), dp(4f), objectPaint.apply { style = Paint.Style.FILL })
        canvas.drawText(text, rect.left + dp(24f), y, infoPaint)
        objectPaint.style = Paint.Style.STROKE
    }

    private fun drawFocusUi(canvas: Canvas, rect: RectF) {
        if (!focusState.visible) return
        val layout = focusUiLayout()
        val center = layout.focusCenter
        val uiRotation = CameraUiRotationPolicy.controlRotationDegrees(deviceUiRotationDegrees)
        val uiSaveCount = if (uiRotation != 0f) {
            canvas.save().also {
                canvas.rotate(uiRotation, center.x, center.y)
            }
        } else {
            -1
        }
        val paint = when {
            focusAssistVisual == FocusAssistVisual.TAP -> tapFocusPaint
            focusState.afFocused -> focusGreen
            else -> focusAmber
        }
        val radius = if (strictHighEndEnabled()) layout.focusRadiusPx * 1.06f else layout.focusRadiusPx
        val box = RectF(
            center.x - radius,
            center.y - radius,
            center.x + radius,
            center.y + radius
        )
        if (focusAssistVisual != FocusAssistVisual.TAP) {
            canvas.drawRoundRect(box, dp(12f), dp(12f), focusFill)
        }
        val originalAlpha = paint.alpha
        val saveCount = if (focusAssistVisual == FocusAssistVisual.TAP) {
            val motion = FocusOverlayAnimationPolicy.resolve(focusAnimationProgress)
            paint.alpha = (originalAlpha * motion.alpha).roundToInt().coerceIn(0, 255)
            canvas.save().also {
                canvas.scale(motion.scale, motion.scale, center.x, center.y)
            }
        } else {
            -1
        }
        drawFocusReticle(canvas, box, paint)
        if (saveCount >= 0) {
            canvas.restoreToCount(saveCount)
            paint.alpha = originalAlpha
        }

        if (focusState.showsExposureControls) {
            val slider = layout.sliderRect
            if (slider != null) {
                val thumbY = FocusOverlayGeometry.focusThumbCenterY(layout, focusState) ?: slider.bottom
                if (focusAssistVisual == FocusAssistVisual.TAP) {
                    drawReferenceEvSlider(canvas, slider, thumbY)
                } else {
                    canvas.drawRoundRect(slider, dp(8f), dp(8f), sliderTrackFillPaint)
                    canvas.drawRoundRect(slider, dp(8f), dp(8f), sliderTrackPaint)
                    canvas.drawLine(slider.centerX(), slider.centerY(), slider.centerX(), thumbY, sliderActivePaint)
                    canvas.drawCircle(slider.centerX(), thumbY, dp(6f), sliderThumbPaint)
                    canvas.drawCircle(slider.centerX(), thumbY, dp(6f), sliderThumbStrokePaint)
                    canvas.drawText("+", slider.centerX(), slider.top - dp(7f), labelPaint)
                    canvas.drawText("-", slider.centerX(), slider.bottom + dp(16f), labelPaint)
                    val valueText = if (focusState.exposureValue > 0) {
                        "+${focusState.exposureValue}"
                    } else {
                        focusState.exposureValue.toString()
                    }
                    canvas.drawText("EV $valueText", box.centerX(), box.top - dp(8f), labelPaint)
                }
            }
        }

        if (focusState.locked) {
            val badge = layout.lockBadgeRect
            if (badge != null) {
                canvas.drawRoundRect(badge, dp(10f), dp(10f), focusFill)
                canvas.drawRoundRect(badge, dp(10f), dp(10f), paint)
                canvas.drawText("AF/AE", badge.centerX(), badge.centerY() + dp(4f), labelPaint)
            }
        }
        if (uiSaveCount >= 0) {
            canvas.restoreToCount(uiSaveCount)
        }
    }

    private fun rotatedFocusUiPoint(x: Float, y: Float): PointF {
        if (!focusState.visible) return PointF(x, y)
        val rotation = CameraUiRotationPolicy.controlRotationDegrees(deviceUiRotationDegrees)
        if (rotation == 0f) return PointF(x, y)
        val center = focusUiLayout().focusCenter
        val radians = Math.toRadians((-rotation).toDouble())
        val translatedX = x - center.x
        val translatedY = y - center.y
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        return PointF(
            center.x + translatedX * cosValue - translatedY * sinValue,
            center.y + translatedX * sinValue + translatedY * cosValue
        )
    }

    private fun drawDetectionBadge(
        canvas: Canvas,
        anchorRect: RectF,
        text: String,
        accentColor: Int
    ) {
        val safeText = text.trim().ifEmpty { return }
        val textPaddingX = dp(6f)
        val textPaddingY = dp(3f)
        val textWidth = detectionBadgeTextPaint.measureText(safeText)
        val badgeWidth = textWidth + textPaddingX * 2f
        val badgeHeight = detectionBadgeTextPaint.textSize + textPaddingY * 2f
        val left = anchorRect.left.coerceAtLeast(dp(4f))
        val top = (anchorRect.top - badgeHeight - dp(2f)).coerceAtLeast(dp(4f))
        val badgeRect = RectF(left, top, left + badgeWidth, top + badgeHeight)
        detectionBadgeFillPaint.color = (accentColor and 0x00FFFFFF) or 0x66000000
        canvas.drawRoundRect(badgeRect, dp(6f), dp(6f), detectionBadgeFillPaint)
        val baseline = badgeRect.centerY() - (detectionBadgeTextPaint.descent() + detectionBadgeTextPaint.ascent()) * 0.5f
        canvas.drawText(safeText, badgeRect.left + textPaddingX, baseline, detectionBadgeTextPaint)
    }

    private fun drawSubjectAssistCues(canvas: Canvas, rect: RectF) {
        val cues = SubjectAssistOverlayPolicy.resolve(
            faces = faces,
            faceDetectionEnabled = subjectAssistFaceDetectionEnabled,
            eyeAutofocusEnabled = subjectAssistEyeAutofocusEnabled
        )
        cues.forEach { cue ->
            val cx = rect.left + cue.normalizedX * rect.width()
            val cy = rect.top + cue.normalizedY * rect.height()
            val radius = if (cue.type == SubjectAssistCueType.EYE) dp(18f) else dp(16f)
            subjectCueRingPaint.color = if (cue.type == SubjectAssistCueType.EYE) {
                0xFFFFD24A.toInt()
            } else {
                0xFF64D8FF.toInt()
            }
            canvas.drawCircle(cx, cy, radius, subjectCueFillPaint)
            canvas.drawCircle(cx, cy, radius, subjectCueRingPaint)
            val inset = radius * 0.44f
            subjectCueIconBounds.set(
                (cx - radius + inset).roundToInt(),
                (cy - radius + inset).roundToInt(),
                (cx + radius - inset).roundToInt(),
                (cy + radius - inset).roundToInt()
            )
            subjectCueDrawable(cue.type)?.let { drawable ->
                drawable.setBounds(subjectCueIconBounds)
                drawable.setTint(subjectCueRingPaint.color)
                drawable.alpha = 235
                drawable.draw(canvas)
            }
        }
    }

    private fun startFocusRevealAnimation() {
        focusRevealAnimator?.cancel()
        focusAnimationProgress = 0f
        focusRevealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FocusOverlayAnimationPolicy.DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                focusAnimationProgress = animator.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        focusRevealAnimator?.cancel()
        focusRevealAnimator = null
        super.onDetachedFromWindow()
    }

    private fun drawFocusReticle(canvas: Canvas, box: RectF, paint: Paint) {
        if (focusAssistVisual == FocusAssistVisual.TAP) {
            drawTapFocusReticle(canvas, box, paint)
            return
        }
        val drawable = focusReticleDrawable() ?: return
        val iconBounds = FocusReticleIconPolicy.iconBounds(box)
        reticleIconBounds.set(
            iconBounds.left.roundToInt(),
            iconBounds.top.roundToInt(),
            iconBounds.right.roundToInt(),
            iconBounds.bottom.roundToInt()
        )
        drawable.setBounds(reticleIconBounds)
        drawable.setTint(paint.color)
        drawable.alpha = paint.alpha
        drawable.draw(canvas)
    }

    private fun drawTapFocusReticle(canvas: Canvas, box: RectF, paint: Paint) {
        val cornerLength = dp(15.5f)
        val inset = dp(1.5f)
        val left = box.left + inset
        val top = box.top + inset
        val right = box.right - inset
        val bottom = box.bottom - inset

        canvas.drawLine(left, top + cornerLength, left, top, paint)
        canvas.drawLine(left, top, left + cornerLength, top, paint)
        canvas.drawLine(right - cornerLength, top, right, top, paint)
        canvas.drawLine(right, top, right, top + cornerLength, paint)
        canvas.drawLine(left, bottom - cornerLength, left, bottom, paint)
        canvas.drawLine(left, bottom, left + cornerLength, bottom, paint)
        canvas.drawLine(right - cornerLength, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - cornerLength, right, bottom, paint)
    }

    private fun drawReferenceEvSlider(canvas: Canvas, slider: RectF, thumbY: Float) {
        val x = slider.centerX()
        val top = slider.top
        val bottom = slider.bottom
        val tick = dp(5f)
        val sunY = thumbY.coerceIn(top + dp(10f), bottom - dp(10f))

        canvas.drawLine(x, top, x, bottom, referenceEvTrackPaint)
        canvas.drawLine(x - tick, top, x + tick, top, referenceEvTrackPaint)
        canvas.drawLine(x - tick, bottom, x + tick, bottom, referenceEvTrackPaint)
        drawEvSun(canvas, x, sunY)
    }

    private fun drawEvSun(canvas: Canvas, centerX: Float, centerY: Float) {
        val coreRadius = dp(4.2f)
        val rayInner = dp(7.2f)
        val rayOuter = dp(10.2f)
        canvas.drawCircle(centerX, centerY, coreRadius, referenceEvSunFillPaint)
        for (index in 0 until 8) {
            val angle = (PI * 2.0 * index / 8.0).toFloat()
            val startX = centerX + cos(angle) * rayInner
            val startY = centerY + sin(angle) * rayInner
            val endX = centerX + cos(angle) * rayOuter
            val endY = centerY + sin(angle) * rayOuter
            canvas.drawLine(startX, startY, endX, endY, referenceEvSunRayPaint)
        }
    }

    private fun focusReticleDrawable(): Drawable? {
        return reticleDrawables.getOrPut(focusAssistVisual) {
            ContextCompat.getDrawable(context, FocusReticleIconPolicy.drawableRes(focusAssistVisual))
                ?.mutate()
        }
    }

    private fun subjectCueDrawable(type: SubjectAssistCueType): Drawable? {
        return subjectCueDrawables.getOrPut(type) {
            val visual = when (type) {
                SubjectAssistCueType.FACE -> FocusAssistVisual.FACE
                SubjectAssistCueType.EYE -> FocusAssistVisual.EYE
            }
            ContextCompat.getDrawable(context, FocusReticleIconPolicy.drawableRes(visual))?.mutate()
        }
    }

    private fun formatExposureValue(value: Int): String =
        if (value > 0) "+$value" else value.toString()

    private fun previewRect(): RectF {
        return FocusOverlayGeometry.resolvePreviewRect(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            previewFillViewport = previewFillViewport,
            previewAspectRatio = previewAspectRatio,
            previewSourceAspectRatio = previewSourceAspectRatio
        )
    }

    private fun focusUiLayout(): FocusOverlayLayout {
        return FocusOverlayGeometry.resolveLayout(
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
            previewFillViewport = previewFillViewport,
            previewAspectRatio = previewAspectRatio,
            previewSourceAspectRatio = previewSourceAspectRatio,
            state = focusState,
            density = resources.displayMetrics.density
        )
    }

    private fun ensureBitmap(w: Int, h: Int, type: Int): Bitmap {
        val existing = when (type) {
            0 -> peakingBitmap
            1 -> zebraBitmap
            else -> subjectMaskBitmap
        }
        if (existing != null && existing.width == w && existing.height == h) return existing
        val created = createBitmap(w, h, Bitmap.Config.ALPHA_8)
        when (type) {
            0 -> peakingBitmap = created
            1 -> zebraBitmap = created
            else -> subjectMaskBitmap = created
        }
        return created
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
