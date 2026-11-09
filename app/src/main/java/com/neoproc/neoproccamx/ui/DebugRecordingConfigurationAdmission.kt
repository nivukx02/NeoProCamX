package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode

/**
 * Fail-closed proof that debug/device automation is about to record the configuration it named.
 *
 * This boundary is deliberately independent of Camera2 readiness. It catches rejected settings,
 * stale preference restoration, and mode-ordering mistakes before a recording can be mislabeled as
 * evidence for a different resolution, cadence, colour route, or pipeline.
 */
internal object DebugRecordingConfigurationAdmission {
    data class Expected(
        val appMode: AppMode?,
        val preset: CameraModePreset?,
        val width: Int?,
        val height: Int?,
        val fps: Int?,
        val cinemaMode: CinemaVideoMode?,
        val rawEnabled: Boolean?,
        val logEnabled: Boolean?,
        val colorSpace: String?,
        val hdrMode: String?,
        val detailProfile: VideoDetailProfile?,
        val rawCompression: RawCompressionMode?,
        val uncompressedCinemaPipeline: Boolean?,
        val sdrConversionPipeline: Boolean?
    )

    data class Applied(
        val appMode: AppMode,
        val preset: CameraModePreset,
        val selectedWidth: Int,
        val selectedHeight: Int,
        val encoderWidth: Int,
        val encoderHeight: Int,
        val selectedFps: Int,
        val encoderFps: Int,
        val cinemaMode: CinemaVideoMode,
        val rawEnabled: Boolean,
        val logEnabled: Boolean,
        val colorSpace: String,
        val hdrMode: String,
        val detailProfile: VideoDetailProfile,
        val rawCompression: RawCompressionMode,
        val uncompressedCinemaPipeline: Boolean,
        val sdrConversionPipeline: Boolean
    )

    data class Decision(val admitted: Boolean, val mismatches: List<String>)

    fun evaluate(expected: Expected, applied: Applied): Decision {
        val failures = buildList {
            expected.appMode?.let { if (it != applied.appMode) add("appMode expected=$it applied=${applied.appMode}") }
            expected.preset?.let { if (it != applied.preset) add("preset expected=$it applied=${applied.preset}") }
            if (expected.width != null && expected.height != null) {
                if (expected.width != applied.selectedWidth || expected.height != applied.selectedHeight) {
                    add("selectedResolution expected=${expected.width}x${expected.height} applied=${applied.selectedWidth}x${applied.selectedHeight}")
                }
                if (expected.width != applied.encoderWidth || expected.height != applied.encoderHeight) {
                    add("encoderResolution expected=${expected.width}x${expected.height} applied=${applied.encoderWidth}x${applied.encoderHeight}")
                }
            }
            expected.fps?.let {
                if (it != applied.selectedFps) add("selectedFps expected=$it applied=${applied.selectedFps}")
                if (it != applied.encoderFps) add("encoderFps expected=$it applied=${applied.encoderFps}")
            }
            expected.cinemaMode?.let { if (it != applied.cinemaMode) add("cinemaMode expected=$it applied=${applied.cinemaMode}") }
            expected.rawEnabled?.let { if (it != applied.rawEnabled) add("raw expected=$it applied=${applied.rawEnabled}") }
            expected.logEnabled?.let { if (it != applied.logEnabled) add("log expected=$it applied=${applied.logEnabled}") }
            expected.colorSpace?.let { if (!it.equals(applied.colorSpace, ignoreCase = true)) add("colorSpace expected=$it applied=${applied.colorSpace}") }
            expected.hdrMode?.let { if (!it.equals(applied.hdrMode, ignoreCase = true)) add("hdrMode expected=$it applied=${applied.hdrMode}") }
            expected.detailProfile?.let { if (it != applied.detailProfile) add("detailProfile expected=$it applied=${applied.detailProfile}") }
            expected.rawCompression?.let { if (it != applied.rawCompression) add("rawCompression expected=$it applied=${applied.rawCompression}") }
            expected.uncompressedCinemaPipeline?.let {
                if (it != applied.uncompressedCinemaPipeline) add("uncompressedCinemaPipeline expected=$it applied=${applied.uncompressedCinemaPipeline}")
            }
            expected.sdrConversionPipeline?.let {
                if (it != applied.sdrConversionPipeline) add("sdrConversionPipeline expected=$it applied=${applied.sdrConversionPipeline}")
            }
        }
        return Decision(admitted = failures.isEmpty(), mismatches = failures)
    }
}
