package com.neoproc.neoproccamx.camera

/** The physical tier the RAW recorder will actually engage this session. */
internal enum class RawCaptureTier { MAXIMUM_SENSOR, FULL_ACTIVE_ARRAY }

/**
 * The resolved RAW capture plan for the current session.
 *
 * @property tier                 which physical tier to engage.
 * @property rawSize              the RAW_SENSOR size to open the reader/stream at (null = no RAW).
 * @property engageMaxResPixelMode set CaptureRequest.SENSOR_PIXEL_MODE = MAXIMUM_RESOLUTION.
 * @property burstCapture         true for the max-res burst; false for the streaming active-array path.
 * @property requested            the mode originally requested (AUTO / MAXIMUM_SENSOR / FULL_ACTIVE_ARRAY).
 * @property fellBack             true when we could not honour a max-res intent and degraded.
 * @property reason               human-readable explanation for logs/UI.
 */
internal data class ResolvedRawCaptureTier(
    val tier: RawCaptureTier,
    val rawSize: CameraStreamSize?,
    val engageMaxResPixelMode: Boolean,
    val burstCapture: Boolean,
    val requested: RawCaptureResolutionMode,
    val fellBack: Boolean,
    val reason: String
)

/**
 * Resolves the effective RAW capture tier from the requested mode, the device capability, and the
 * current runtime viability.
 *
 * Policy (as chosen by the user):
 *  - Default ([RawCaptureResolutionMode.AUTO]) favours MAXIMUM_SENSOR.
 *  - Falls back to FULL_ACTIVE_ARRAY when max-res is not exposed OR (for AUTO) not currently viable
 *    — smart viability covering thermal / storage / memory.
 *  - An explicit [RawCaptureResolutionMode.MAXIMUM_SENSOR] request overrides *soft* viability blocks
 *    (power-user intent) but still falls back on hard blocks (physically impossible / thermal critical).
 *  - An explicit [RawCaptureResolutionMode.FULL_ACTIVE_ARRAY] always uses the streaming path.
 *
 * Pure and Context-free.
 */
internal object RawCaptureResolutionModePolicy {

    fun resolve(
        requested: RawCaptureResolutionMode,
        capability: RawSensorMaxResCapability,
        viability: RawMaxResViability
    ): ResolvedRawCaptureTier {
        if (requested == RawCaptureResolutionMode.FULL_ACTIVE_ARRAY) {
            return active(capability, requested, fellBack = false, reason = "Full active pixel array selected")
        }

        // AUTO and MAXIMUM_SENSOR both aim for the un-binned maximum.
        if (!capability.maxResAvailable || capability.maxResRawSize == null) {
            return active(
                capability, requested, fellBack = true,
                reason = "Maximum-resolution RAW not exposed by this device"
            )
        }

        val forcedMax = requested == RawCaptureResolutionMode.MAXIMUM_SENSOR
        // Forced request honours only hard blocks; AUTO honours soft blocks too.
        val blocked = if (forcedMax) viability.hardBlocked else !viability.viable
        if (blocked) {
            return active(
                capability, requested, fellBack = true,
                reason = "Falling back to full active array: ${viability.reason}"
            )
        }

        return ResolvedRawCaptureTier(
            tier = RawCaptureTier.MAXIMUM_SENSOR,
            rawSize = capability.maxResRawSize,
            engageMaxResPixelMode = true,
            burstCapture = true,
            requested = requested,
            fellBack = false,
            reason = if (forcedMax && !viability.viable) {
                "Maximum sensor resolution (forced over soft limit: ${viability.reason})"
            } else {
                "Maximum sensor resolution via max-res burst"
            }
        )
    }

    private fun active(
        capability: RawSensorMaxResCapability,
        requested: RawCaptureResolutionMode,
        fellBack: Boolean,
        reason: String
    ) = ResolvedRawCaptureTier(
        tier = RawCaptureTier.FULL_ACTIVE_ARRAY,
        rawSize = capability.activeArrayRawSize,
        engageMaxResPixelMode = false,
        burstCapture = false,
        requested = requested,
        fellBack = fellBack,
        reason = reason
    )
}
