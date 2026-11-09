package com.neoproc.neoproccamx.camera

/**
 * Stateful cinema focus-pull controller — the engine a director-grade follow-focus UI drives.
 *
 * It holds two named focus marks (A and B, in **diopters**, matching
 * [android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE] and the controller's
 * `manualFocusDist`), an easing curve and a duration, and steps a smooth, monotonic, endpoint-exact
 * focus pull between them when triggered. Per-frame stepping is O(1) and time-parameterised, so it
 * self-advances on each preview rebuild; there is no per-frame model.
 *
 * The focus-pull MATH is delegated to [CameraFocusControl] (read-only), which keeps this class a
 * thin, fully unit-testable state machine. The per-frame easing step is routed through a pluggable
 * [CinematicFocusStepper] seam so s5-f08 can swap in the
 * [com.neoproc.neoproccamx.ai.AIFocusDistanceController] pull engine without changing this class;
 * the default stepper uses [CameraFocusControl.calculateEasedFocusTransition].
 *
 * No-downgrade: this control reaches the lens only when [CinematicFocusPullFlag] is ON. With the
 * flag OFF (the default) [CameraController] never queries it and autofocus is byte-for-byte today.
 */
internal class CinematicFocusPullControl(
    private val stepper: CinematicFocusStepper = DefaultEasedStepper
) {
    /** Easing curve for the pull (mirrors the read-only [CameraFocusControl] primitives). */
    enum class EasingCurve {
        /** Smooth ease-in-out (cinema standard) — [CameraFocusControl.calculateEasedFocusTransition]. */
        EASE_IN_OUT,
        /** Constant-velocity rack capped by [CameraFocusControl.calculateRackFocusStep]. */
        LINEAR_RACK
    }

    /** Immutable snapshot of the active pull for the operator readout. */
    internal data class PullStatus(
        val active: Boolean,
        val startDiopters: Float,
        val endDiopters: Float,
        val progress: Float,
        val currentDiopters: Float
    )

    /** Depth-of-field + focus-assist readout surfaced to the operator when cine mode is ON. */
    internal data class FocusReadout(
        val focusDistanceMeters: Float,
        val hyperfocalMeters: Float,
        val nearLimitMeters: Float,
        val farLimitMeters: Float,
        val peakingIntensity: Float,
        val hunting: Boolean
    )

    private var markADiopters: Float = -1f
    private var markBDiopters: Float = -1f
    private var curve: EasingCurve = EasingCurve.EASE_IN_OUT
    private var durationMs: Long = DEFAULT_PULL_DURATION_MS
    private var speedProfile: FocusSpeedProfile = FocusSpeedProfile.CINEMATIC

    private var pullActive: Boolean = false
    private var pullStartElapsedMs: Long = 0L
    private var pullStartDiopters: Float = -1f
    private var pullEndDiopters: Float = -1f
    private var currentDiopters: Float = -1f

    // Rolling stepped-distance history for hunting detection (newest = lastApplied).
    private var lastApplied: Float = -1f
    private var prevApplied: Float = -1f
    private var olderApplied: Float = -1f
    private var lastStepElapsedMs: Long = 0L

    val isPulling: Boolean get() = pullActive

    /** Set focus mark A (diopters). A negative value clears the mark. */
    fun setMarkA(diopters: Float) { markADiopters = diopters }

    /** Set focus mark B (diopters). A negative value clears the mark. */
    fun setMarkB(diopters: Float) { markBDiopters = diopters }

    fun setEasingCurve(value: EasingCurve) { curve = value }

    fun setSpeedProfile(value: FocusSpeedProfile) { speedProfile = value }

    /** Pull duration in milliseconds, coerced to a sane floor. */
    fun setDurationMs(value: Long) { durationMs = value.coerceAtLeast(MIN_PULL_DURATION_MS) }

    /**
     * Trigger a pull from [fromDiopters] (defaults to mark A) to [toDiopters] (defaults to mark B)
     * at [nowElapsedMs]. Returns true if a valid pull started; false when either endpoint is
     * unset/invalid (no-op, dormant-safe).
     */
    fun triggerPull(
        nowElapsedMs: Long,
        fromDiopters: Float = markADiopters,
        toDiopters: Float = markBDiopters
    ): Boolean {
        if (!fromDiopters.isFinite() || !toDiopters.isFinite() ||
            fromDiopters < 0f || toDiopters < 0f
        ) {
            return false
        }
        pullActive = true
        pullStartElapsedMs = nowElapsedMs
        pullStartDiopters = fromDiopters
        pullEndDiopters = toDiopters
        currentDiopters = fromDiopters
        lastApplied = fromDiopters
        prevApplied = fromDiopters
        olderApplied = fromDiopters
        lastStepElapsedMs = nowElapsedMs
        return true
    }

    /** Cancel any active pull; the lens holds the last stepped distance. */
    fun cancel() {
        pullActive = false
    }

    fun reset() {
        pullActive = false
        markADiopters = -1f
        markBDiopters = -1f
        pullStartDiopters = -1f
        pullEndDiopters = -1f
        currentDiopters = -1f
        lastApplied = -1f
        prevApplied = -1f
        olderApplied = -1f
        durationMs = DEFAULT_PULL_DURATION_MS
        curve = EasingCurve.EASE_IN_OUT
        speedProfile = FocusSpeedProfile.CINEMATIC
    }

    /**
     * Step the active pull to [nowElapsedMs] and return the lens diopter to apply, clamped to
     * [maxFocusDiopters]. Returns null when no pull is active (caller keeps today's manual/AI/AF
     * path untouched). Endpoint-exact: at/after the configured duration the pull lands on B exactly
     * and deactivates.
     */
    fun stepDiopters(nowElapsedMs: Long, maxFocusDiopters: Float): Float? {
        if (!pullActive) return null
        val dt = (nowElapsedMs - lastStepElapsedMs).coerceAtLeast(0L).toFloat() / 1000f
        lastStepElapsedMs = nowElapsedMs

        val elapsed = (nowElapsedMs - pullStartElapsedMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

        val stepped = stepper.step(
            startDiopters = pullStartDiopters,
            endDiopters = pullEndDiopters,
            currentDiopters = currentDiopters,
            progress = progress,
            deltaTimeSeconds = dt,
            curve = curve,
            speedProfile = speedProfile
        )
        val upperBound = if (maxFocusDiopters > 0f) maxFocusDiopters else Float.MAX_VALUE
        val clamped = stepped.coerceIn(0f, upperBound)

        currentDiopters = clamped
        olderApplied = prevApplied
        prevApplied = lastApplied
        lastApplied = clamped

        val endClamped = pullEndDiopters.coerceIn(0f, upperBound)
        val reachedEnd = progress >= 1f ||
            (curve == EasingCurve.LINEAR_RACK && clamped == endClamped)
        if (reachedEnd) {
            currentDiopters = endClamped
            lastApplied = endClamped
            pullActive = false
            return endClamped
        }
        return clamped
    }

    fun status(): PullStatus {
        val elapsed = if (pullActive) {
            (lastStepElapsedMs - pullStartElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val progress = when {
            !pullActive -> 0f
            durationMs > 0L -> (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            else -> 1f
        }
        return PullStatus(
            active = pullActive,
            startDiopters = pullStartDiopters,
            endDiopters = pullEndDiopters,
            progress = progress,
            currentDiopters = currentDiopters
        )
    }

    /**
     * Build the operator DoF / peaking / hunting readout for [focusDiopters], delegating every
     * computation to the read-only [CameraFocusControl] math. [laplacianVariance] is the live edge
     * energy that feeds peaking; pass a non-positive value when no edge metric is available (peaking
     * reads 0). Hunting is evaluated from the rolling stepped-distance history.
     */
    fun buildReadout(
        focusDiopters: Float,
        focalLengthMm: Float,
        aperture: Float,
        circleOfConfusionMm: Float = DEFAULT_CIRCLE_OF_CONFUSION_MM,
        laplacianVariance: Float = -1f,
        peakingThreshold: Float = DEFAULT_PEAKING_THRESHOLD
    ): FocusReadout {
        val focusMeters = dioptersToMeters(focusDiopters)
        val (nearMeters, farMeters) = CameraFocusControl.calculateDepthOfField(
            focusDistanceMeters = focusMeters,
            focalLengthMm = focalLengthMm,
            aperture = aperture,
            circleOfConfusionMm = circleOfConfusionMm
        )
        val hyperfocal = hyperfocalMeters(focalLengthMm, aperture, circleOfConfusionMm)
        val peaking = if (laplacianVariance > 0f) {
            CameraFocusControl.calculateFocusPeakingIntensity(
                laplacianVariance = laplacianVariance,
                threshold = peakingThreshold
            )
        } else {
            0f
        }
        val hunting = if (lastApplied >= 0f && prevApplied >= 0f && olderApplied >= 0f) {
            CameraFocusControl.isFocusHunting(
                currentDiopters = lastApplied,
                previousDiopters = prevApplied,
                olderDiopters = olderApplied
            )
        } else {
            false
        }
        return FocusReadout(
            focusDistanceMeters = focusMeters,
            hyperfocalMeters = hyperfocal,
            nearLimitMeters = nearMeters,
            farLimitMeters = farMeters,
            peakingIntensity = peaking,
            hunting = hunting
        )
    }

    /** Pluggable per-frame easing step (the seam s5-f08 wires `applyFocusPull` into). */
    internal fun interface CinematicFocusStepper {
        fun step(
            startDiopters: Float,
            endDiopters: Float,
            currentDiopters: Float,
            progress: Float,
            deltaTimeSeconds: Float,
            curve: EasingCurve,
            speedProfile: FocusSpeedProfile
        ): Float
    }

    /** Default stepper — pure [CameraFocusControl] easing/rack math, READ-ONLY. */
    internal object DefaultEasedStepper : CinematicFocusStepper {
        override fun step(
            startDiopters: Float,
            endDiopters: Float,
            currentDiopters: Float,
            progress: Float,
            deltaTimeSeconds: Float,
            curve: EasingCurve,
            speedProfile: FocusSpeedProfile
        ): Float = when (curve) {
            EasingCurve.EASE_IN_OUT -> CameraFocusControl.calculateEasedFocusTransition(
                startDiopters = startDiopters,
                endDiopters = endDiopters,
                progress = progress
            )
            EasingCurve.LINEAR_RACK -> CameraFocusControl.calculateRackFocusStep(
                currentDiopters = currentDiopters,
                targetDiopters = endDiopters,
                speedProfile = speedProfile,
                deltaTimeSeconds = deltaTimeSeconds
            )
        }
    }

    internal companion object {
        const val DEFAULT_PULL_DURATION_MS = 1000L
        const val MIN_PULL_DURATION_MS = 1L
        const val DEFAULT_CIRCLE_OF_CONFUSION_MM = 0.005f
        const val DEFAULT_PEAKING_THRESHOLD = 100f

        /** Camera2 diopters (1/m) -> metres; 0 or negative diopters focus at infinity. */
        fun dioptersToMeters(diopters: Float): Float =
            if (diopters <= 0f) Float.POSITIVE_INFINITY else 1f / diopters

        /** Textbook hyperfocal H = f^2/(N*c) in metres (the [CameraFocusControl] DoF form). */
        fun hyperfocalMeters(
            focalLengthMm: Float,
            aperture: Float,
            circleOfConfusionMm: Float
        ): Float {
            if (aperture <= 0f || circleOfConfusionMm <= 0f) return Float.POSITIVE_INFINITY
            val hyperfocalMm = (focalLengthMm * focalLengthMm) / (aperture * circleOfConfusionMm)
            return hyperfocalMm / 1000f
        }
    }
}
