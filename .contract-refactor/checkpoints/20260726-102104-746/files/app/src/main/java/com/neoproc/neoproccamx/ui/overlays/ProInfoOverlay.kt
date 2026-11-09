package com.neoproc.neoproccamx.ui.overlays

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.neoproc.neoproccamx.camera.CaptureResultData
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.RecordingConfig
import com.neoproc.neoproccamx.recording.RecordingState
import com.neoproc.neoproccamx.utils.CameraUtils
import java.util.Locale

/**
 * ProInfoOverlay — Cinema-camera HUD overlay.
 *
 * TOP LEFT:   Resolution · FPS · Bitrate · Codec
 * TOP CENTER: Timecode (red, when recording)
 * BOTTOM LEFT:  ISO · Shutter · WB · Focus
 * BOTTOM RIGHT: Zoom · AF mode · OIS · Clip
 * BOTTOM BAR: AF state color strip
 */
class ProInfoOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ── Data ──────────────────────────────────────────────────────────────────

    var captureResult: CaptureResultData? = null
        set(v) { field = v; invalidate() }

    var recordingState: RecordingState = RecordingState.Idle
        set(v) { field = v; invalidate() }

    var recordingConfig: RecordingConfig = RecordingConfig()
        set(v) { field = v; invalidate() }

    var recordingDurationMs: Long = 0L
        set(v) { field = v; invalidate() }

    var zoomRatio: Float = 1f
        set(v) { field = v; invalidate() }

    var afModeName: String = "CAF"
        set(v) { field = v; invalidate() }

    var oisEnabled: Boolean = true
        set(v) { field = v; invalidate() }

    var colorProfileName: String = "NATURAL"
        set(v) { field = v; invalidate() }

    var freeSpaceGb: Float = 0f
        set(v) { field = v; invalidate() }

    var clipName: String = "A001_C001"
        set(v) { field = v; invalidate() }

    // ── Flash state ───────────────────────────────────────────────────────────

    private val flashTimestamps = mutableMapOf<String, Long>()
    private val FLASH_DURATION_MS = 400L

    private fun isFlashing(key: String): Boolean {
        val ts = flashTimestamps[key] ?: return false
        return System.currentTimeMillis() - ts < FLASH_DURATION_MS
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPillPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 160, 160, 160)
        textSize = 22f
        typeface = ProOverlayTypographyPolicy.createTypeface(ProOverlayTypographyPolicy.labelSpec())
    }
    private val textValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = ProOverlayTypographyPolicy.createTypeface(ProOverlayTypographyPolicy.valueSpec())
        isFakeBoldText = false
    }
    private val textValueFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 220, 0)
        textSize = 26f
        typeface = ProOverlayTypographyPolicy.createTypeface(ProOverlayTypographyPolicy.valueSpec())
        isFakeBoldText = false
    }
    private val recTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 42f
        typeface = ProOverlayTypographyPolicy.createTypeface(ProOverlayTypographyPolicy.recordTimeSpec())
        isFakeBoldText = false
    }
    private val recDotPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val afStateBarPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val result = captureResult
        val isRecording = recordingState is RecordingState.Recording
        val cfg = recordingConfig
        val strict = strictHighEndEnabled()

        // ── TOP-LEFT: Format info ─────────────────────────────────────────────
        var y = 28f
        val tlX = 12f
        val topPillWidth = if (strict) 360f else 320f
        val topPillHeight = if (strict) 110f else 84f
        drawPill(canvas, tlX - 4f, y - 20f, topPillWidth, topPillHeight)
        drawLV(canvas, tlX, y, "RES:", "${cfg.resolutionLabel}·${cfg.fps}fps", false)
        y += 30f
        drawLV(canvas, tlX, y, "BR: ", cfg.bitrateLabel, false)
        y += 26f
        drawLV(canvas, tlX, y, "COD:", cfg.codec.name, false)
        if (strict) {
            y += 26f
            drawLV(canvas, tlX, y, "COL:", colorProfileName.uppercase(Locale.US), false)
        }

        // ── TOP-CENTER: Timecode when recording ───────────────────────────────
        if (isRecording) {
            val tc = formatTimecode(recordingDurationMs, cfg.fps)
            val tcW = recTimePaint.measureText(tc)
            val pulse = (System.currentTimeMillis() / 600) % 2 == 0L
            if (pulse) canvas.drawCircle(w / 2f - tcW / 2f - 16f, 36f, 8f, recDotPaint)
            canvas.drawText(tc, w / 2f - tcW / 2f, 52f, recTimePaint)
        }

        // ── TOP-RIGHT: Storage ────────────────────────────────────────────────
        val storageText = if (freeSpaceGb > 0f) {
            String.format(Locale.US, "%.1f GB", freeSpaceGb)
        } else {
            ""
        }
        if (storageText.isNotEmpty()) {
            val storeW = textValuePaint.measureText(storageText)
            drawLV(canvas, w - storeW - 80f, 28f, "SSD:", storageText, false)
        }

        // ── BOTTOM-LEFT: Exposure ─────────────────────────────────────────────
        var by = h - 120f
        val blX = 12f
        if (result != null) {
            drawPill(canvas, blX - 4f, by - 22f, 360f, 120f)
            drawLV(canvas, blX, by,        "ISO", result.isoDisplay,           isFlashing("iso"))
            by += 28f
            drawLV(canvas, blX, by,        "SS ", result.shutterSpeedFraction, isFlashing("ss"))
            by += 28f
            drawLV(canvas, blX, by,        "WB ", "Auto",                      false)
            by += 28f
            drawLV(canvas, blX, by,        "MFD",
                if (result.focusDistance > 0f) CameraUtils.formatFocusDistance(result.focusDistance) else "∞",
                false)
        }

        // ── BOTTOM-RIGHT: Zoom + AF ───────────────────────────────────────────
        val brY = h - 120f
        val zoomText = if (strict) {
            String.format(Locale.US, "%.2f×", zoomRatio)
        } else {
            String.format(Locale.US, "%.1f×", zoomRatio)
        }
        drawPill(canvas, w - 170f, brY - 22f, 160f, 120f)
        drawLV(canvas, w - 160f, brY,       "ZM", zoomText,                  isFlashing("zoom"))
        drawLV(canvas, w - 160f, brY + 28f, "AF", afModeName,                false)
        drawLV(canvas, w - 160f, brY + 56f, "OI", if (oisEnabled) "ON" else "OFF", false)
        drawLV(canvas, w - 160f, brY + 84f, "CL", clipName,                  false)

        // ── AF state bar ──────────────────────────────────────────────────────
        result?.let { r ->
            val afStateColor = when (r.afState) {
                android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ->
                    Color.rgb(0, 255, 100)
                android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                    Color.RED
                android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
                android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ->
                    Color.YELLOW
                else -> Color.TRANSPARENT
            }
            if (afStateColor != Color.TRANSPARENT) {
                afStateBarPaint.color = afStateColor
                canvas.drawRect(0f, h - 3f, w, h, afStateBarPaint)
            }
        }
    }

    private fun drawPill(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        canvas.drawRoundRect(x, y, x + w, y + h, 8f, 8f, bgPillPaint)
    }

    private fun drawLV(canvas: Canvas, x: Float, y: Float, label: String, value: String, flashing: Boolean) {
        val normalizedLabel = label.trim()
        val resolvedLabel = if (normalizedLabel.endsWith(":")) normalizedLabel else "$normalizedLabel:"
        canvas.drawText(resolvedLabel, x, y, textLabelPaint)
        val lw = textLabelPaint.measureText(resolvedLabel) + 6f
        canvas.drawText(value, x + lw, y, if (flashing) textValueFlashPaint else textValuePaint)
    }

    private fun formatTimecode(ms: Long, fps: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val safeFps = fps.coerceAtLeast(1)
        val frames = ((ms % 1000) * safeFps / 1000).toInt().coerceIn(0, safeFps - 1)
        return String.format(Locale.US, "%02d:%02d:%02d:%02d", h, m, s, frames)
    }
}
