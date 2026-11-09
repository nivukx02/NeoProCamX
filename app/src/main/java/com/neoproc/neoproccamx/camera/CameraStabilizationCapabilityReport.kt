package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.recording.StabilizationMode

/**
 * Pure, Android-free formatter/decision for Camera2 stabilization capabilities.
 *
 * Turns the advertised LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION /
 * CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES arrays into:
 *  (a) a human-readable diagnostic string, so the device's real caps are finally
 *      verifiable from logcat / the pro HUD (previously the arrays were collapsed to two
 *      booleans and discarded, so we could not confirm from source what the vivo advertises);
 *  (b) an honest "is the user's requested mode's REQUIRED hardware genuinely absent?" check,
 *      used to surface a one-time warning instead of silently no-op'ing a selection.
 *
 * Raw Camera2 int values are used directly so this stays JVM-unit-testable without
 * Robolectric:
 *   LENS_OPTICAL_STABILIZATION_MODE: OFF=0, ON=1
 *   CONTROL_VIDEO_STABILIZATION_MODE: OFF=0, ON=1, PREVIEW_STABILIZATION=2
 */
internal object CameraStabilizationCapabilityReport {
    private const val OIS_OFF = 0
    private const val OIS_ON = 1
    private const val VS_OFF = 0
    private const val VS_ON = 1
    private const val VS_PREVIEW = 2

    data class Report(
        val supportsOis: Boolean,
        val supportsVideoStabilization: Boolean,
        val supportsPreviewStabilization: Boolean,
        val diagnostic: String
    )

    fun describe(oisModes: IntArray, videoStabModes: IntArray): Report {
        val supportsOis = oisModes.contains(OIS_ON)
        val supportsPreviewStabilization = videoStabModes.contains(VS_PREVIEW)
        val supportsVideoStabilization =
            videoStabModes.contains(VS_ON) || supportsPreviewStabilization
        val oisTokens = oisModes.joinToString(",") { oisToken(it) }.ifEmpty { "none" }
        val vsTokens = videoStabModes.joinToString(",") { vsToken(it) }.ifEmpty { "none" }
        val diagnostic =
            "ois=[$oisTokens] videoStab=[$vsTokens] -> ois=$supportsOis " +
                "eis=$supportsVideoStabilization previewStabilization=$supportsPreviewStabilization"
        return Report(
            supportsOis,
            supportsVideoStabilization,
            supportsPreviewStabilization,
            diagnostic
        )
    }

    /**
     * True when the requested mode's REQUIRED hardware is genuinely not advertised.
     * HYBRID means "use both OIS and EIS"; its collapse to a single family on a device
     * that has only one is honest degraded behavior, so HYBRID warns only when the device
     * cannot deliver both jointly. OFF never warns.
     */
    fun requiredHardwareMissing(
        requested: StabilizationMode,
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean
    ): Boolean = when (requested) {
        StabilizationMode.OFF -> false
        StabilizationMode.OIS -> !supportsOis
        StabilizationMode.EIS -> !supportsVideoStabilization
        StabilizationMode.HYBRID -> !(supportsOis && supportsVideoStabilization)
    }

    private fun oisToken(mode: Int): String = when (mode) {
        OIS_OFF -> "OFF"
        OIS_ON -> "ON"
        else -> "0x${mode.toString(16)}"
    }

    private fun vsToken(mode: Int): String = when (mode) {
        VS_OFF -> "OFF"
        VS_ON -> "ON"
        VS_PREVIEW -> "PREVIEW"
        else -> "0x${mode.toString(16)}"
    }
}
