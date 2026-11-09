package com.neoproc.neoproccamx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.neoproc.neoproccamx.R
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Full-screen, allocation-stable playback controls matching the camera's minimal black monitoring UI.
 * The timeline owns scrubbing locally and commits one seek when the gesture ends, avoiding repeated
 * decoder flushes while the user's finger is moving.
 */
internal class EliteVideoPlaybackControls(
    context: Context,
    private val onBack: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val onNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onAdvancedOptions: () -> Unit
) : FrameLayout(context) {

    /** Tooling-only constructor; production creates this view with its playback callbacks. */
    constructor(context: Context) : this(
        context = context,
        onBack = {},
        onPrevious = {},
        onTogglePlayback = {},
        onNext = {},
        onSeekTo = { _ -> },
        onAdvancedOptions = {}
    )

    private val elapsedLabel = playbackTimeLabel(Gravity.START)
    private val remainingLabel = playbackTimeLabel(Gravity.END)
    private val playPauseButton: AppCompatImageButton
    private val timeline: ElitePlaybackTimelineView
    private val backwardFeedback = seekFeedbackLabel(Gravity.START)
    private val forwardFeedback = seekFeedbackLabel(Gravity.END)
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val backButton = controlButton(
            icon = R.drawable.ic_elite_player_back,
            description = context.getString(R.string.player_back),
            sizeDp = 56,
            onClick = onBack
        )
        addView(
            backButton,
            LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(16)
                marginStart = dp(18)
            }
        )

        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(
            controlButton(
                icon = R.drawable.ic_elite_player_previous,
                description = context.getString(R.string.player_previous),
                sizeDp = 56,
                onClick = onPrevious
            )
        )
        transport.addView(space(dp(58)))
        playPauseButton = controlButton(
            icon = R.drawable.ic_elite_player_play,
            description = context.getString(R.string.player_play),
            sizeDp = 68,
            onClick = onTogglePlayback
        ).apply {
            setOnLongClickListener {
                onAdvancedOptions()
                true
            }
        }
        transport.addView(playPauseButton)
        transport.addView(space(dp(58)))
        transport.addView(
            controlButton(
                icon = R.drawable.ic_elite_player_next,
                description = context.getString(R.string.player_next),
                sizeDp = 56,
                onClick = onNext
            )
        )
        addView(
            transport,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(72), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(94)
            }
        )

        addView(
            backwardFeedback,
            LayoutParams(dp(112), dp(72), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(34)
            }
        )
        addView(
            forwardFeedback,
            LayoutParams(dp(112), dp(72), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = dp(34)
            }
        )

        timeline = ElitePlaybackTimelineView(
            context = context,
            onScrubPreview = { previewPositionMs ->
                elapsedLabel.text = VideoPlaybackTimeFormatter.elapsed(previewPositionMs)
                remainingLabel.text = VideoPlaybackTimeFormatter.remaining(
                    previewPositionMs,
                    lastDurationMs
                )
            },
            onSeekCommitted = onSeekTo
        )
        val timelineRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsedLabel, LinearLayout.LayoutParams(dp(104), dp(48)))
            addView(
                timeline,
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(14)
                    marginEnd = dp(14)
                }
            )
            addView(remainingLabel, LinearLayout.LayoutParams(dp(84), dp(48)))
        }
        addView(
            timelineRow,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM).apply {
                marginStart = dp(28)
                marginEnd = dp(28)
                bottomMargin = dp(16)
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (backButton.layoutParams as LayoutParams).apply {
                topMargin = dp(16) + safe.top
                marginStart = dp(18) + safe.left
                backButton.layoutParams = this
            }
            (timelineRow.layoutParams as LayoutParams).apply {
                marginStart = dp(28) + safe.left
                marginEnd = dp(28) + safe.right
                bottomMargin = dp(16) + safe.bottom
                timelineRow.layoutParams = this
            }
            insets
        }
    }

    fun bind(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val safeDuration = durationMs.takeIf { it > 0L } ?: 0L
        val safePosition = positionMs.coerceAtLeast(0L).coerceAtMost(
            safeDuration.takeIf { it > 0L } ?: Long.MAX_VALUE
        )
        lastPositionMs = safePosition
        lastDurationMs = safeDuration
        timeline.setPlaybackPosition(safePosition, safeDuration)
        if (!timeline.isScrubbing) {
            elapsedLabel.text = VideoPlaybackTimeFormatter.elapsed(safePosition)
            remainingLabel.text = VideoPlaybackTimeFormatter.remaining(safePosition, safeDuration)
        }
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_elite_player_pause else R.drawable.ic_elite_player_play
        )
        playPauseButton.contentDescription = context.getString(
            if (isPlaying) R.string.player_pause else R.string.player_play
        )
    }

    fun showSeekFeedback(deltaSeconds: Int) {
        val target = if (deltaSeconds < 0) backwardFeedback else forwardFeedback
        target.animate().cancel()
        target.text = if (deltaSeconds < 0) {
            context.getString(R.string.player_seek_backward_feedback)
        } else {
            context.getString(R.string.player_seek_forward_feedback)
        }
        target.alpha = 1f
        target.visibility = VISIBLE
        target.animate()
            .alpha(0f)
            .setStartDelay(420L)
            .setDuration(220L)
            .withEndAction { target.visibility = GONE }
            .start()
    }

    private fun playbackTimeLabel(horizontalGravity: Int): TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL or horizontalGravity
        includeFontPadding = false
        setTextIsSelectable(false)
        text = context.getString(R.string.player_time_zero)
    }

    private fun seekFeedbackLabel(horizontalGravity: Int): TextView = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 17f
        gravity = Gravity.CENTER_VERTICAL or horizontalGravity
        includeFontPadding = false
        visibility = GONE
        alpha = 0f
        setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0xFF000000.toInt())
    }

    private fun controlButton(
        icon: Int,
        description: String,
        sizeDp: Int,
        onClick: () -> Unit
    ): AppCompatImageButton = AppCompatImageButton(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setImageResource(icon)
        contentDescription = description
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun space(widthPx: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, 1)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal object VideoPlaybackTimeFormatter {
    fun elapsed(positionMs: Long): String = format(positionMs.coerceAtLeast(0L))

    fun remaining(positionMs: Long, durationMs: Long): String {
        if (durationMs <= 0L) return "-00:00"
        return "-${format((durationMs - positionMs).coerceAtLeast(0L))}"
    }

    private fun format(timeMs: Long): String {
        val totalSeconds = timeMs / 1_000L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

internal object VideoPlaybackSeekPolicy {
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    fun relativeTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val upperBound = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return (positionMs.coerceAtLeast(0L) + deltaMs)
            .coerceAtLeast(0L)
            .coerceAtMost(upperBound)
    }
}

/** Gesture-only layer below visible controls, so timeline/buttons retain first touch priority. */
internal class VideoPlaybackGestureOverlay(
    context: Context,
    private val onSingleTap: () -> Unit,
    private val onDoubleTapLeft: () -> Unit,
    private val onDoubleTapRight: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit
) : View(context) {

    /** Tooling-only constructor; production supplies the gesture actions explicitly. */
    constructor(context: Context) : this(
        context = context,
        onSingleTap = {},
        onDoubleTapLeft = {},
        onDoubleTapRight = {},
        onPrevious = {},
        onNext = {}
    )

    private var singleTapCandidate = false
    private var pendingSingleTap: Runnable? = null

    private val detector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                singleTapCandidate = true
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelPendingSingleTap()
                if (e.x < width / 2f) onDoubleTapLeft() else onDoubleTapRight()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val deltaX = e2.x - start.x
                val deltaY = e2.y - start.y
                if (kotlin.math.abs(deltaX) < dp(90).toFloat() ||
                    kotlin.math.abs(deltaX) < kotlin.math.abs(deltaY) ||
                    kotlin.math.abs(velocityX) < dp(400).toFloat()
                ) {
                    return false
                }
                if (deltaX < 0f) onNext() else onPrevious()
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        singleTapCandidate = false
        val handled = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && singleTapCandidate) {
            cancelPendingSingleTap()
            val confirmedClick = Runnable {
                pendingSingleTap = null
                performClick()
            }
            pendingSingleTap = confirmedClick
            postDelayed(confirmedClick, ViewConfiguration.getDoubleTapTimeout().toLong())
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelPendingSingleTap()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelPendingSingleTap()
        super.onDetachedFromWindow()
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTap?.let(::removeCallbacks)
        pendingSingleTap = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

/** Lightweight cyan timeline with a generous touch target and a single decoder seek per scrub. */
private class ElitePlaybackTimelineView(
    context: Context,
    private val onScrubPreview: (Long) -> Unit,
    private val onSeekCommitted: (Long) -> Unit
) : View(context) {

    /** Tooling-only constructor; the owner supplies scrub callbacks at runtime. */
    constructor(context: Context) : this(
        context = context,
        onScrubPreview = { _ -> },
        onSeekCommitted = { _ -> }
    )

    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF29B6F6.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF29B6F6.toInt()
    }
    private val trackStrokePx = dp(3.5f)
    private val thumbRadiusPx = dp(7f)
    private var positionMs = 0L
    private var durationMs = 0L
    var isScrubbing: Boolean = false
        private set

    init {
        isClickable = true
        isFocusable = true
        unplayedPaint.strokeWidth = trackStrokePx
        playedPaint.strokeWidth = trackStrokePx
    }

    fun setPlaybackPosition(positionMs: Long, durationMs: Long) {
        if (isScrubbing) return
        this.durationMs = durationMs.coerceAtLeast(0L)
        this.positionMs = positionMs.coerceIn(0L, this.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        contentDescription = VideoPlaybackTimeFormatter.elapsed(this.positionMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        val startX = paddingLeft + thumbRadiusPx
        val endX = (width - paddingRight - thumbRadiusPx).coerceAtLeast(startX)
        canvas.drawLine(startX, centerY, endX, centerY, unplayedPaint)
        val fraction = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
        val thumbX = startX + (endX - startX) * fraction
        canvas.drawLine(startX, centerY, thumbX, centerY, playedPaint)
        canvas.drawCircle(thumbX, centerY, thumbRadiusPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                updateScrubPosition(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateScrubPosition(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateScrubPosition(event.x)
                isScrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                onSeekCommitted(positionMs)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isScrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (durationMs <= 0L) return false
        positionMs = (positionMs + direction * 5_000L).coerceIn(0L, durationMs)
        onScrubPreview(positionMs)
        onSeekCommitted(positionMs)
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateScrubPosition(x: Float) {
        val startX = paddingLeft + thumbRadiusPx
        val endX = (width - paddingRight - thumbRadiusPx).coerceAtLeast(startX + 1f)
        val fraction = ((x - startX) / (endX - startX)).coerceIn(0f, 1f)
        positionMs = (durationMs.toDouble() * fraction.toDouble()).roundToLong()
        onScrubPreview(positionMs)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
