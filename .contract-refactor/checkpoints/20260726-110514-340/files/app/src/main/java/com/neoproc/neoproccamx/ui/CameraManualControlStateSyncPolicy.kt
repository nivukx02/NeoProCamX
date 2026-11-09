package com.neoproc.neoproccamx.ui

import android.util.Range
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale

/**
 * CameraManualControlStateSyncPolicy — High-precision manual camera control state manager.
 *
 * Provides a centralized, immutable-first state management layer for all
 * manual camera parameters in the NeoProCamX cinema pipeline:
 *
 *  - ISO / Gain (analog + digital)
 *  - Shutter speed (exposure time in nanoseconds)
 *  - White balance (Kelvin temperature + Green/Magenta tint)
 *  - Focus distance (diopters)
 *  - Exposure compensation (EV steps)
 *  - ND filter factor (f-stops)
 *
 * This policy is the single source of truth for what the user has requested.
 * The CameraController layer reads from this policy and translates it into
 * Camera2 CaptureRequest parameters. No UI component should ever write directly
 * to Camera2; all changes must flow through this policy.
 *
 * Thread-safe for read access. Writes must occur on the main thread.
 */
object CameraManualControlStateSyncPolicy {

    // ── State Flows ───────────────────────────────────────────────────────────

    // NOTE: Using simple volatile state rather than StateFlow to avoid
    // dependency on coroutines in this policy object. Callers should
    // observe ViewModel state flows that are driven by this policy.

    @Volatile
    private var currentState: ManualControlSnapshot = ManualControlSnapshot()

    // ── Read API ──────────────────────────────────────────────────────────────

    /** Returns the current immutable snapshot of all manual control values. */
    fun snapshot(): ManualControlSnapshot = currentState

    /** Returns the current ISO value (Auto or Manual). */
    fun currentIso(): ManualControlValue = currentState.iso

    /** Returns the current exposure time (Auto or Manual). */
    fun currentExposureNs(): ManualControlValue = currentState.exposureNs

    /** Returns the current white balance kelvin (Auto or Manual). */
    fun currentWhiteBalanceKelvin(): ManualControlValue = currentState.whiteBalanceKelvin

    /** Returns the current white balance tint (Auto or Manual). */
    fun currentWhiteBalanceTint(): ManualControlValue = currentState.whiteBalanceTint

    /** Returns the current focus distance in diopters (Auto or Manual). */
    fun currentFocusDistance(): ManualControlValue = currentState.focusDistance

    /** Returns the current exposure compensation in EV (Auto or Manual). */
    fun currentExposureCompensation(): ManualControlValue = currentState.exposureCompensation

    // ── Update API ────────────────────────────────────────────────────────────

    /** Sets ISO to a manual value. */
    fun setIso(iso: Int, range: Range<Int>) {
        val clamped = clampIso(iso, range)
        currentState = currentState.copy(iso = ManualControlValue.Manual(clamped))
    }

    /** Sets ISO to Auto mode. */
    fun setIsoAuto() {
        currentState = currentState.copy(iso = ManualControlValue.Auto)
    }

    /** Sets exposure time to a manual value in nanoseconds. */
    fun setExposureNs(exposureNs: Long, range: Range<Long>) {
        val clamped = clampExposureNs(exposureNs, range)
        currentState = currentState.copy(exposureNs = ManualControlValue.Manual(clamped))
    }

    /** Sets exposure time to Auto mode. */
    fun setExposureAuto() {
        currentState = currentState.copy(exposureNs = ManualControlValue.Auto)
    }

    /** Sets white balance temperature in Kelvin to a manual value. */
    fun setWhiteBalanceKelvin(kelvin: Int) {
        val clamped = clampWhiteBalanceKelvin(kelvin)
        currentState = currentState.copy(whiteBalanceKelvin = ManualControlValue.Manual(clamped))
    }

    /** Sets white balance tint offset (GM axis, -150 to +150). */
    fun setWhiteBalanceTint(tint: Int) {
        val clamped = tint.coerceIn(WB_TINT_MIN, WB_TINT_MAX)
        currentState = currentState.copy(whiteBalanceTint = ManualControlValue.Manual(clamped))
    }

    /** Sets white balance to Auto mode (both temperature and tint). */
    fun setWhiteBalanceAuto() {
        currentState = currentState.copy(
            whiteBalanceKelvin = ManualControlValue.Auto,
            whiteBalanceTint = ManualControlValue.Auto
        )
    }

    /** Sets focus distance in diopters (0.0 = infinity, 1.0 = 1m, 10.0 = 10cm). */
    fun setFocusDistance(diopters: Float) {
        val clamped = diopters.coerceIn(FOCUS_DIOPTERS_MIN, FOCUS_DIOPTERS_MAX)
        currentState = currentState.copy(focusDistance = ManualControlValue.Manual(clamped))
    }

    /** Sets focus to Auto mode. */
    fun setFocusAuto() {
        currentState = currentState.copy(focusDistance = ManualControlValue.Auto)
    }

    /** Sets exposure compensation in EV steps. */
    fun setExposureCompensation(ev: Float) {
        val clamped = ev.coerceIn(EV_MIN, EV_MAX)
        currentState = currentState.copy(exposureCompensation = ManualControlValue.Manual(clamped))
    }

    /** Resets exposure compensation to 0 EV. */
    fun resetExposureCompensation() {
        currentState = currentState.copy(exposureCompensation = ManualControlValue.Manual(0.0f))
    }

    /** Resets all controls to Auto mode. */
    fun resetToDefaults() {
        currentState = ManualControlSnapshot()
    }

    // ── Restore Plan ──────────────────────────────────────────────────────────

    /**
     * Resolves a [ManualControlRestorePlan] from persisted preferences.
     * Validates all values against current hardware capability ranges.
     *
     * @param persistedIso               Previously saved ISO value, or null.
     * @param isoRange                   Hardware-reported valid ISO range.
     * @param persistedExposureNs        Previously saved shutter time, or null.
     * @param exposureRange              Hardware-reported valid shutter range.
     * @param persistedWhiteBalanceKelvin Previously saved WB temperature, or null.
     */
    fun resolveRestorePlan(
        persistedIso: Int?,
        isoRange: Range<Int>,
        persistedExposureNs: Long?,
        exposureRange: Range<Long>,
        persistedWhiteBalanceKelvin: Int?,
        whiteBalanceRange: Range<Int> = Range(WB_KELVIN_MIN, WB_KELVIN_MAX)
    ): ManualControlRestorePlan {
        val iso = if (persistedIso != null && persistedIso in isoRange.lower..isoRange.upper) {
            ManualControlValue.Manual(persistedIso)
        } else {
            ManualControlValue.Auto
        }

        val exposureNs = if (
            persistedExposureNs != null &&
            persistedExposureNs in exposureRange.lower..exposureRange.upper
        ) {
            ManualControlValue.Manual(persistedExposureNs)
        } else {
            ManualControlValue.Auto
        }

        val whiteBalanceKelvin = resolveWhiteBalanceRestoreValue(
            persistedWhiteBalanceKelvin,
            whiteBalanceRange
        )

        return ManualControlRestorePlan(iso, exposureNs, whiteBalanceKelvin)
    }

    /**
     * Resolves a white balance restore value from a persisted Kelvin integer.
     */
    fun resolveWhiteBalanceRestoreValue(
        persistedWhiteBalanceKelvin: Int?,
        range: Range<Int> = Range(WB_KELVIN_MIN, WB_KELVIN_MAX)
    ): ManualControlValue =
        if (persistedWhiteBalanceKelvin != null && persistedWhiteBalanceKelvin in whiteBalanceBounds(range)) {
            ManualControlValue.Manual(persistedWhiteBalanceKelvin)
        } else {
            ManualControlValue.Auto
        }

    /**
     * Applies a [ManualControlRestorePlan] to the current state.
     */
    fun applyRestorePlan(plan: ManualControlRestorePlan) {
        currentState = currentState.copy(
            iso = plan.iso,
            exposureNs = plan.exposureNs,
            whiteBalanceKelvin = plan.whiteBalanceKelvin
        )
    }

    // ── Clamping Utilities ────────────────────────────────────────────────────

    /** Clamps ISO to hardware-reported valid range. */
    fun clampIso(iso: Int, range: Range<Int>): Int =
        iso.coerceIn(range.lower, range.upper)

    /** Clamps exposure time to hardware-reported valid range. */
    fun clampExposureNs(exposureNs: Long, range: Range<Long>): Long =
        exposureNs.coerceIn(range.lower, range.upper)

    /** Clamps white balance temperature to standard photographic range. */
    fun clampWhiteBalanceKelvin(
        kelvin: Int,
        range: Range<Int> = Range(WB_KELVIN_MIN, WB_KELVIN_MAX)
    ): Int {
        val bounds = whiteBalanceBounds(range)
        return kelvin.coerceIn(bounds.first, bounds.last)
    }

    private fun whiteBalanceBounds(range: Range<Int>): IntRange {
        val lower = runCatching { range.lower }.getOrNull() ?: WB_KELVIN_MIN
        val upper = runCatching { range.upper }.getOrNull() ?: WB_KELVIN_MAX
        return lower.coerceAtMost(upper)..upper.coerceAtLeast(lower)
    }

    // ── Conversion Utilities ──────────────────────────────────────────────────

    /**
     * Converts a shutter angle at a given frame rate to exposure time in nanoseconds.
     *
     * @param angleDegrees Shutter angle (e.g. 180° = half the frame period).
     * @param fps          Frame rate in frames per second.
     */
    fun shutterAngleToExposureNs(angleDegrees: Double, fps: Double): Long {
        if (fps <= 0.0 || angleDegrees <= 0.0) return 0L
        return ((angleDegrees / 360.0) / fps * 1_000_000_000.0).toLong()
    }

    /**
     * Converts an exposure time in nanoseconds to a shutter angle at the given fps.
     */
    fun exposureNsToShutterAngle(exposureNs: Long, fps: Double): Double {
        if (fps <= 0.0 || exposureNs <= 0L) return 0.0
        return (exposureNs.toDouble() / 1_000_000_000.0) * fps * 360.0
    }

    /**
     * Returns a display-friendly shutter speed string from exposure nanoseconds.
     * E.g. 33,333,333 ns → "1/30"
     */
    fun formatExposureNs(exposureNs: Long): String {
        if (exposureNs <= 0L) return "Auto"
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds >= 1.0) {
            "${String.format(Locale.US, "%.1f", seconds)}s"
        } else {
            val denominator = (1.0 / seconds).toInt()
            "1/$denominator"
        }
    }

    /**
     * Returns whether any control is in Manual mode (vs. all Auto).
     */
    val hasAnyManualControl: Boolean
        get() = with(currentState) {
            iso is ManualControlValue.Manual ||
                exposureNs is ManualControlValue.Manual ||
                whiteBalanceKelvin is ManualControlValue.Manual ||
                focusDistance is ManualControlValue.Manual
        }

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val WB_KELVIN_MIN = 2000
    private const val WB_KELVIN_MAX = 15000
    private const val WB_TINT_MIN = -150
    private const val WB_TINT_MAX = 150
    private const val FOCUS_DIOPTERS_MIN = 0.0f
    private const val FOCUS_DIOPTERS_MAX = 15.0f
    private const val EV_MIN = -6.0f
    private const val EV_MAX = +6.0f
}

// ── Data Types ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of all manual control values.
 */
data class ManualControlSnapshot(
    val iso: ManualControlValue = ManualControlValue.Auto,
    val exposureNs: ManualControlValue = ManualControlValue.Auto,
    val whiteBalanceKelvin: ManualControlValue = ManualControlValue.Auto,
    val whiteBalanceTint: ManualControlValue = ManualControlValue.Auto,
    val focusDistance: ManualControlValue = ManualControlValue.Auto,
    val exposureCompensation: ManualControlValue = ManualControlValue.Manual(0.0f)
) {
    val isFullyAuto: Boolean
        get() = iso == ManualControlValue.Auto &&
            exposureNs == ManualControlValue.Auto &&
            whiteBalanceKelvin == ManualControlValue.Auto &&
            focusDistance == ManualControlValue.Auto
}

/**
 * Represents a manual camera control value — either a typed Manual value or Auto.
 * Uses a sealed class for exhaustive when-expressions.
 */
sealed class ManualControlValue {
    /** A user-specified manual value. */
    data class Manual(val value: Number) : ManualControlValue() {
        fun toInt(): Int = value.toInt()
        fun toLong(): Long = value.toLong()
        fun toFloat(): Float = value.toFloat()
        fun toDouble(): Double = value.toDouble()
    }

    /** Auto mode — let the camera system decide. */
    object Auto : ManualControlValue()

    val isAuto: Boolean get() = this is Auto
    val isManual: Boolean get() = this is Manual
}

/**
 * Restore plan for manual controls, derived from persisted preferences.
 */
data class ManualControlRestorePlan(
    val iso: ManualControlValue,
    val exposureNs: ManualControlValue,
    val whiteBalanceKelvin: ManualControlValue,
    val whiteBalanceTint: ManualControlValue = ManualControlValue.Auto,
    val focusDistance: ManualControlValue = ManualControlValue.Auto
) {
    val hasAnyManualControl: Boolean
        get() = iso.isManual || exposureNs.isManual || whiteBalanceKelvin.isManual ||
            whiteBalanceTint.isManual || focusDistance.isManual
}
