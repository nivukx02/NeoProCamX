package com.neoproc.neoproccamx.ui

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// CameraViewModel — Extracted Constants
//
// Tuning constants that were previously inside the CameraViewModel
// companion object.  Moving them here allows other coordinators
// to reference them without coupling to the ViewModel class itself.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

object CameraViewModelConstants {
    // ── LUT / Color ──────────────────────────────────────────────────
    const val CUSTOM_LUT_PROFILE = "Custom"
    const val MIN_HIGH_END_JPEG_QUALITY = 98

    // ── Recording limits ─────────────────────────────────────────────
    const val MAX_CINEMA_RECORD_WIDTH = 16384
    const val MAX_CINEMA_RECORD_HEIGHT = 16384
    const val HIGH_END_MAX_BITRATE_BPS = 2_000_000_000
    const val HIGH_END_DEFAULT_MANUAL_BITRATE_BPS = 1_968_000_000
    const val LOG_TEN_BIT_BITRATE_BOOST = 1.5f
    const val CINEMA_4K60_MIN_BITRATE_BPS = 1_968_000_000

    // ── Frame-rate defaults ──────────────────────────────────────────
    const val PHOTO_MODE_PREVIEW_FPS = 60
    const val VIDEO_IDLE_PREVIEW_FPS = 60
    const val RAW_MODE_MAX_FPS = 60
    const val RAW_MODE_MAX_LONG_EDGE = 16384

    // ── Startup timing ───────────────────────────────────────────────
    const val AI_WARMUP_DELAY_MS = 90L
    const val STARTUP_AUTO_PRIME_DELAY_MS = 220L
    const val STARTUP_NATIVE_PREVIEW_RELEASE_DELAY_MS = 300L
    const val STARTUP_FEATURE_GATE_TIMEOUT_MS = 900L
    const val STARTUP_AI_RAMP_DELAY_MS = 450L
    const val STARTUP_AI_RAMP_DELAY_STRICT_HIGH_END_MS = 300L

    // ── Focus timing ─────────────────────────────────────────────────
    const val TAP_TO_FOCUS_HOLD_MS = 2_500L
    const val CINEMA_AUTO_LOCK_STABLE_MS = 240L

    // ── Runtime stats ────────────────────────────────────────────────
    const val CINEMA_RUNTIME_SYSTEM_REFRESH_INTERVAL_MS = 750L

    // ── Thermal guard ────────────────────────────────────────────────
    const val THERMAL_GUARD_PREVIEW_MAX_LONG_EDGE = 1280
    const val THERMAL_GUARD_PREVIEW_MAX_AREA = 1280 * 720
    const val THERMAL_BITRATE_REDUCTION_FACTOR = 0.88f

    // ── Shutter rule guidance ────────────────────────────────────────
    const val SHUTTER_RULE_TARGET_FPS = 24
    const val SHUTTER_RULE_EXPOSURE_DENOMINATOR = 48
    const val SHUTTER_RULE_WARNING_TOLERANCE = 0.18f

    // ── Recording telemetry thresholds ───────────────────────────────
    const val SLOW_RECORDING_PREPARE_MS = 320L
    const val SLOW_RECORDING_START_TOTAL_MS = 1_300L
    const val SLOW_RECORDING_STOP_TOTAL_MS = 1_000L
}
