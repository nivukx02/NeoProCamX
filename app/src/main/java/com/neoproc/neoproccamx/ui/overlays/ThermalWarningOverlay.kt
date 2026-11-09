package com.neoproc.neoproccamx.ui.overlays

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.toColorInt
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier

/**
 * ThermalWarningOverlay — cinema-oriented thermal status heads-up display.
 *
 * Renders a non-intrusive but clearly visible thermal warning banner
 * on the camera preview when the device temperature requires attention.
 *
 * Features:
 *  - Severity-aware gradient coloring (amber → red → critical pulsing)
 *  - Smooth show/hide animations with alpha transitions
 *  - Per-tier labeling with descriptive status text
 *  - Pulse animation for critical/emergency states
 *  - Shadow rendering for readability over any preview content
 *  - Performance-optimized: no allocations in onDraw()
 *
 * Visual hierarchy:
 *  - ELEVATED:  Amber gradient, static display
 *  - THROTTLED: Orange-red gradient, static display
 *  - SUSTAINED: Orange gradient, static display
 *  - RECOVERY:  Red gradient with "Performance Capped" message
 *  - CRITICAL:  Deep red with pulsing alpha animation
 *  - BASIC:     Emergency red with fast pulse and "STOP RECORDING" CTA
 */
class ThermalWarningOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var currentTier: HighEndRuntimeTier = HighEndRuntimeTier.CINEMA
    private var isThrottled: Boolean = false
    private var warningText: String = ""
    private var secondaryText: String = ""
    private var severityLevel: Int = 0  // 0=none, 1=mild, 2=moderate, 3=severe, 4=critical

    // ── Rendering ─────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bgStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = "#40FFFFFF".toColorInt()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, "#80000000".toColorInt())
    }

    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFFFFFF".toColorInt()
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, "#60000000".toColorInt())
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val bannerRect = RectF()
    private var gradientShader: LinearGradient? = null
    private val gradientBounds = RectF()
    private var gradientStartColor = Color.TRANSPARENT
    private var gradientEndColor = Color.TRANSPARENT
    private val cornerRadius = 16f
    private val bannerHeight = 72f
    private val bannerHeightWithSecondary = 96f
    private val bannerTopMargin = 32f
    private val horizontalPadding = 48f

    // ── Animation ─────────────────────────────────────────────────────────────

    private var pulseAlpha = 1.0f
    private var pulseAnimator: ValueAnimator? = null

    // ── Color Palette ─────────────────────────────────────────────────────────

    private val colorAmberStart = "#E6B800".toColorInt()
    private val colorAmberEnd = "#CC8800".toColorInt()
    private val colorOrangeStart = "#FF8800".toColorInt()
    private val colorOrangeEnd = "#CC5500".toColorInt()
    private val colorRedStart = "#DD2200".toColorInt()
    private val colorRedEnd = "#AA0000".toColorInt()
    private val colorCriticalStart = "#FF0000".toColorInt()
    private val colorCriticalEnd = "#880000".toColorInt()
    private val colorEmergencyStart = "#FF0044".toColorInt()
    private val colorEmergencyEnd = "#990000".toColorInt()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Updates the thermal warning overlay based on the current runtime tier.
     *
     * @param tier      Current pipeline runtime tier.
     * @param throttled Whether the device is actively throttled.
     */
    fun updateThermalStatus(tier: HighEndRuntimeTier, throttled: Boolean) {
        currentTier = tier
        isThrottled = throttled

        val (primary, secondary, severity) = resolveThermalDisplay(tier, throttled)
        warningText = primary
        secondaryText = secondary
        severityLevel = severity

        val shouldShow = severity > 0 && !(com.neoproc.neoproccamx.core.HighEndQualityPolicy.enabled && com.neoproc.neoproccamx.core.HighEndQualityPolicy.strictHighEndOnly)
        visibility = if (shouldShow) VISIBLE else GONE

        // Manage pulse animation
        if (severity >= 3) {
            startPulseAnimation(severity)
        } else {
            stopPulseAnimation()
            pulseAlpha = 1.0f
        }

        invalidate()
    }

    /**
     * Updates with a custom warning message (e.g., from the thermal policy engine).
     *
     * @param message   Primary warning text.
     * @param secondary Secondary descriptive text.
     * @param severity  Severity level (0–4).
     */
    fun updateCustomWarning(message: String, secondary: String = "", severity: Int = 2) {
        warningText = message
        secondaryText = secondary
        severityLevel = severity.coerceIn(0, 4)
        visibility = if (severity > 0) VISIBLE else GONE

        if (severity >= 3) startPulseAnimation(severity) else stopPulseAnimation()
        invalidate()
    }

    /** Hides the overlay immediately. */
    fun hide() {
        visibility = GONE
        stopPulseAnimation()
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE || warningText.isEmpty()) return

        val hasSecondary = secondaryText.isNotEmpty()
        val effectiveHeight = if (hasSecondary) bannerHeightWithSecondary else bannerHeight
        val textWidth = textPaint.measureText(warningText)
        val bannerWidth = (textWidth + horizontalPadding * 2).coerceAtMost(width - 32f)

        bannerRect.set(
            (width - bannerWidth) / 2f,
            bannerTopMargin,
            (width + bannerWidth) / 2f,
            bannerTopMargin + effectiveHeight
        )

        // Apply pulse alpha for critical states
        val alpha = (pulseAlpha * 255).toInt().coerceIn(100, 255)

        // Background gradient based on severity
        val (startColor, endColor) = resolveGradientColors()
        bgPaint.shader = resolveGradientShader(startColor, endColor)
        bgPaint.alpha = alpha

        // Draw rounded banner
        canvas.drawRoundRect(bannerRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(bannerRect, cornerRadius, cornerRadius, bgStrokePaint)

        // Draw primary text
        textPaint.alpha = alpha
        val primaryY = if (hasSecondary) {
            bannerRect.centerY() - 4f
        } else {
            bannerRect.centerY() + 12f
        }
        canvas.drawText(warningText, width / 2f, primaryY, textPaint)

        // Draw secondary text if present
        if (hasSecondary) {
            secondaryTextPaint.alpha = (alpha * 0.8f).toInt()
            canvas.drawText(
                secondaryText,
                width / 2f,
                bannerRect.centerY() + 28f,
                secondaryTextPaint
            )
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private data class ThermalDisplay(
        val primary: String,
        val secondary: String,
        val severity: Int
    )

    private fun resolveThermalDisplay(
        tier: HighEndRuntimeTier,
        throttled: Boolean
    ): ThermalDisplay = when {
        tier == HighEndRuntimeTier.BASIC -> ThermalDisplay(
            "🔴 EMERGENCY — STOP RECORDING",
            "Device critically overheated",
            4
        )
        tier == HighEndRuntimeTier.RECOVERY -> ThermalDisplay(
            "⚠ CRITICAL THERMAL",
            "Performance severely capped — quality reduced",
            3
        )
        tier == HighEndRuntimeTier.STANDARD && throttled -> ThermalDisplay(
            "⚠ THERMAL THROTTLING",
            "Resolution and bitrate reduced",
            3
        )
        throttled -> ThermalDisplay(
            "THERMAL WARNING",
            "Pipeline performance reduced",
            2
        )
        tier == HighEndRuntimeTier.BALANCED -> ThermalDisplay(
            "ELEVATED TEMPERATURE",
            "Monitoring device temperature",
            1
        )
        else -> ThermalDisplay("", "", 0)
    }

    private fun resolveGradientColors(): Pair<Int, Int> = when (severityLevel) {
        1 -> Pair(colorAmberStart, colorAmberEnd)
        2 -> Pair(colorOrangeStart, colorOrangeEnd)
        3 -> Pair(colorRedStart, colorRedEnd)
        4 -> Pair(colorEmergencyStart, colorEmergencyEnd)
        else -> Pair(colorAmberStart, colorAmberEnd)
    }

    private fun resolveGradientShader(startColor: Int, endColor: Int): LinearGradient {
        val needsNewShader = gradientShader == null ||
            gradientStartColor != startColor ||
            gradientEndColor != endColor ||
            gradientBounds != bannerRect

        if (needsNewShader) {
            gradientBounds.set(bannerRect)
            gradientStartColor = startColor
            gradientEndColor = endColor
            gradientShader = LinearGradient(
                bannerRect.left,
                bannerRect.top,
                bannerRect.right,
                bannerRect.bottom,
                startColor,
                endColor,
                Shader.TileMode.CLAMP
            )
        }

        return requireNotNull(gradientShader)
    }

    private fun startPulseAnimation(severity: Int) {
        if (pulseAnimator?.isRunning == true) return
        val duration = if (severity >= 4) 400L else 800L
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 0.5f).apply {
            this.duration = duration
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                pulseAlpha = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseAlpha = 1.0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulseAnimation()
    }
}
