package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import java.util.Locale
import java.util.TimeZone

internal object CameraPreviewAntibandingPolicy {
    private val SIXTY_HZ_POWER_GRID_COUNTRIES = setOf(
        "US", "CA", "MX", "JP", "KR", "TW", "PH"
    )
    private val SIXTY_HZ_POWER_GRID_TIME_ZONES = setOf(
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles",
        "America/Anchorage",
        "America/Halifax",
        "America/Toronto",
        "America/Vancouver",
        "America/Mexico_City",
        "America/Tijuana",
        "Pacific/Honolulu",
        "Asia/Tokyo",
        "Asia/Seoul",
        "Asia/Taipei",
        "Asia/Manila"
    )

    fun resolveMode(
        availableModes: IntArray,
        requestedPreviewFps: Int,
        videoMode: Boolean,
        usingManualExposure: Boolean,
        powerGridCountryCode: String? = null,
        powerGridTimeZoneId: String? = null
    ): Int? {
        if (availableModes.isEmpty()) return null
        if (usingManualExposure && !videoMode) return null

        val supported = availableModes.toSet()

        // ── LED flicker fix for 90Hz+ displays ──────────────────────────
        // LED PWM operates at 100Hz (50Hz regions) or 120Hz (60Hz regions).
        // At 90fps, frame capture timing aliases with both PWM frequencies,
        // producing visible flicker bands. Force AUTO so the HAL's electronic
        // rolling shutter compensation can dynamically adapt.
        if (requestedPreviewFps >= 90 &&
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        ) {
            return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }

        if (videoMode) {
            preferredDiscreteMode(
                requestedPreviewFps = requestedPreviewFps,
                supported = supported,
                prefers50HzPowerGrid = prefers50HzPowerGrid(
                    countryCode = powerGridCountryCode,
                    timeZoneId = powerGridTimeZoneId
                )
            )?.let { return it }
        }

        return when {
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO) ->
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ) ->
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ) ->
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF) ->
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
            else -> availableModes.firstOrNull()
        }
    }

    private fun preferredDiscreteMode(
        requestedPreviewFps: Int,
        supported: Set<Int>,
        prefers50HzPowerGrid: Boolean
    ): Int? {
        val normalizedFps = requestedPreviewFps.coerceAtLeast(1)
        // For high-framerate modes (90fps+), always prefer AUTO to let the
        // HAL adaptively compensate for LED PWM timing mismatches.
        if (normalizedFps >= 90 &&
            supported.contains(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        ) {
            return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        val prefers50Hz = normalizedFps in setOf(24, 25, 48, 50) || prefers50HzPowerGrid
        val primary = if (prefers50Hz) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        } else {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        }
        val secondary = if (prefers50Hz) {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        } else {
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        }
        return when {
            supported.contains(primary) -> primary
            supported.contains(secondary) -> secondary
            else -> null
        }
    }

    fun prefers50HzPowerGrid(countryCode: String? = null, timeZoneId: String? = null): Boolean {
        val explicitCountryCode = countryCode
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()
        if (explicitCountryCode.isNotEmpty()) {
            return explicitCountryCode !in SIXTY_HZ_POWER_GRID_COUNTRIES
        }
        val timezonePreference = infer50HzFromTimeZone(timeZoneId ?: TimeZone.getDefault().id)
        if (timezonePreference != null) return timezonePreference

        val normalizedCountryCode = Locale.getDefault().country
            .trim()
            .uppercase(Locale.US)
        if (normalizedCountryCode.isNotEmpty()) {
            return normalizedCountryCode !in SIXTY_HZ_POWER_GRID_COUNTRIES
        }
        return false
    }

    private fun infer50HzFromTimeZone(timeZoneId: String?): Boolean? {
        val normalizedTimeZoneId = timeZoneId
            .orEmpty()
            .trim()
        if (normalizedTimeZoneId.isEmpty()) return null
        if (normalizedTimeZoneId in SIXTY_HZ_POWER_GRID_TIME_ZONES) return false
        if (normalizedTimeZoneId.startsWith("Europe/") ||
            normalizedTimeZoneId.startsWith("Africa/") ||
            normalizedTimeZoneId.startsWith("Australia/") ||
            normalizedTimeZoneId.startsWith("Asia/")
        ) {
            return true
        }
        return null
    }
}
