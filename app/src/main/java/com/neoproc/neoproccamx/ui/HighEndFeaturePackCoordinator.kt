package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.HIGH_END_DEFAULT_MANUAL_BITRATE_BPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.MAX_CINEMA_RECORD_HEIGHT
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.MAX_CINEMA_RECORD_WIDTH
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.utils.PreferencesManager
import kotlin.math.abs

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// High-End Feature Pack Coordinator
//
// Encapsulates the "cinema-oriented" feature pack logic that was
// previously inlined in CameraViewModel (lines 2741-2930).
// Applies professional presets: 10-bit, RAW quality-preserving,
// full-sensor readout, audio/stabilization, and manual controls.
//
// Extracted as Sprint 1 of the CameraViewModel decomposition.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Callback interface for actions that the coordinator needs
 * to dispatch back to the ViewModel.
 */
interface HighEndFeaturePackActions {
    // ── Focus / AI ───────────────────────────────────────────────
    fun setAiEnhancementEnabled(enabled: Boolean)
    fun setNoiseReductionEnabled(enabled: Boolean)
    fun setFaceDetectionEnabled(enabled: Boolean)
    fun setEyeAutofocusEnabled(enabled: Boolean)

    fun setGridMode(mode: GridMode)

    // ── Audio & Stabilization ────────────────────────────────────
    fun setStabilizationMode(mode: StabilizationMode)
    fun setAudioSrc(source: AudioSrc)

    // ── Video Color ──────────────────────────────────────────────
    fun setVideoResolution(size: Size)
    fun setFPS(fps: Int)
    fun setVideoBitrateMode(mode: VideoBitrateMode)
    fun setVideoBitrate(bps: Int)
    fun setCinemaVideoMode(mode: CinemaVideoMode)
    fun setVideoHdrMode(mode: String)
    fun setTenBitRecordingEnabled(enabled: Boolean)
    fun setVideoColorSpace(colorSpace: String)
    fun setColorProfile(profile: String)
    fun setRawVideoCompressionMode(mode: RawCompressionMode)
    fun setLutProfile(profile: String)
    fun setLutEnabled(enabled: Boolean)

    // ── Manual Controls ──────────────────────────────────────────
    fun setISO(iso: Int)
    fun setExposure(ns: Long)
    fun setWB(kelvin: Int)
    fun setEV(ev: Int)

    // ── Pipeline ─────────────────────────────────────────────────
    fun applyAutomaticVideoBitrateIfNeeded(force: Boolean)
    fun updateCinemaRuntimeStats(previewFps: Float)
    fun availableVideoSizes(): List<Size>
}

/**
 * Manages the application and lifecycle of High-End Feature Packs.
 *
 * Each "pack" represents a professional preset bundle:
 * - **Focus AI**: AI enhancement, noise reduction, face + eye autofocus
 * - **Audio Stabilization**: Hybrid stabilization + unprocessed audio
 * - **Video Color**: 10-bit, Neo wide-gamut, high-bitrate
 * - **Manual Controls**: ISO 100, 180° shutter, 5600K WB
 */
class HighEndFeaturePackCoordinator(
    private val prefs: PreferencesManager,
    private val camera: CameraController,
    private val recording: RecordingConfig,
    private val actions: HighEndFeaturePackActions
) {

    // ── Public API ───────────────────────────────────────────────

    fun setProfileEnabled(enabled: Boolean) {
        prefs.highEndProfileEnabled = enabled
        if (enabled) {
            prefs.enableStrictHighEndProfileDefaults()
            enableAllPackPreferences()
            applyInternal(updateProfilePreference = false)
        }
    }

    fun setVideoColorPackEnabled(enabled: Boolean) {
        prefs.highEndVideoColorEnabled = enabled
        if (enabled && prefs.highEndProfileEnabled) {
            applyVideoColorPack()
        }
    }

    fun setFocusAiPackEnabled(enabled: Boolean) {
        prefs.highEndFocusAiEnabled = enabled
        if (enabled && prefs.highEndProfileEnabled) {
            applyFocusAiPack()
        }
    }

    fun setAudioStabilizationPackEnabled(enabled: Boolean) {
        prefs.highEndAudioStabilizationEnabled = enabled
        if (enabled && prefs.highEndProfileEnabled) {
            applyAudioStabilizationPack()
        }
    }

    fun setManualControlPackEnabled(enabled: Boolean) {
        prefs.highEndManualControlsEnabled = enabled
        if (enabled && prefs.highEndProfileEnabled) {
            applyManualControlPack()
        }
    }

    fun applyFullPack() {
        applyInternal(updateProfilePreference = true)
    }

    // ── Internal Orchestration ───────────────────────────────────

    private fun applyInternal(updateProfilePreference: Boolean) {
        if (updateProfilePreference) {
            prefs.highEndProfileEnabled = true
        }
        if (!hasAnyPackEnabled()) {
            enableAllPackPreferences()
        }

        if (prefs.highEndFocusAiEnabled) applyFocusAiPack()
        if (prefs.highEndAudioStabilizationEnabled) applyAudioStabilizationPack()
        if (prefs.highEndVideoColorEnabled) applyVideoColorPack()
        if (prefs.highEndManualControlsEnabled) applyManualControlPack()

        actions.applyAutomaticVideoBitrateIfNeeded(force = true)
        actions.updateCinemaRuntimeStats(0f) // Will use lastPreviewFpsSample in ViewModel
    }

    fun hasAnyPackEnabled(): Boolean =
        prefs.highEndFocusAiEnabled ||
            prefs.highEndAudioStabilizationEnabled ||
            prefs.highEndVideoColorEnabled ||
            prefs.highEndManualControlsEnabled

    fun enableAllPackPreferences() {
        prefs.highEndFocusAiEnabled = false
        prefs.highEndAudioStabilizationEnabled = true
        prefs.highEndVideoColorEnabled = true
        prefs.highEndManualControlsEnabled = true
    }

    // ── Pack Implementations ─────────────────────────────────────

    private fun applyFocusAiPack() {
        val pack = HighEndFocusAiPackPolicy.resolve()
        actions.setAiEnhancementEnabled(pack.aiEnhancementEnabled)
        actions.setNoiseReductionEnabled(pack.noiseReductionEnabled)
        // Face/eye are user-owned runtime settings for the zero-drop recording profile. The pack may
        // enable them in a future code-level product tier, but the default pack must not overwrite a
        // user choice or silently start Camera2/AI tracking.
        if (pack.faceDetectionEnabled) actions.setFaceDetectionEnabled(true)
        if (pack.eyeAutofocusEnabled) actions.setEyeAutofocusEnabled(true)
    }

    private fun applyAudioStabilizationPack() {
        actions.setStabilizationMode(StabilizationMode.HYBRID)
        actions.setAudioSrc(AudioSrc.UNPROCESSED)
    }

    private fun applyVideoColorPack() {
        val targetResolution = resolveTargetResolution()
        actions.setVideoResolution(targetResolution)
        actions.setFPS(resolveTargetFps(targetResolution))
        // Seed the persisted target first: setVideoBitrate() intentionally marks a direct user
        // edit as CUSTOM. The pack itself is capability-aware, so restore CINEMA_MASTER after the
        // seed and let the recording layer resolve the strongest supported rate for this session.
        actions.setVideoBitrate(HIGH_END_DEFAULT_MANUAL_BITRATE_BPS)
        actions.setVideoBitrateMode(VideoBitrateMode.CINEMA_MASTER)
        actions.setCinemaVideoMode(CinemaVideoMode.PRO)
        actions.setVideoHdrMode("OFF")
        actions.setTenBitRecordingEnabled(true)
        actions.setVideoColorSpace("Neo Wide Gamut")
        actions.setColorProfile("Natural")
        actions.setRawVideoCompressionMode(RawCompressionMode.HIGH_COMPRESSION)
        actions.setLutProfile("Neutral Film")
        actions.setLutEnabled(false)
    }

    private fun applyManualControlPack() {
        if (!camera.supportsManualSensor) return
        val resolvedIso = 100.coerceIn(camera.isoRange.lower, camera.isoRange.upper)
        val shutterTargetNs = (1_000_000_000L / (recording.fps.coerceAtLeast(24) * 2L))
            .coerceIn(camera.exposureRange.lower, camera.exposureRange.upper)
        val resolvedWbKelvin = 5600.coerceIn(2000, 10000)
        actions.setISO(resolvedIso)
        actions.setExposure(shutterTargetNs)
        actions.setWB(resolvedWbKelvin)
        actions.setEV(0)
    }

    // ── Resolution Helpers ───────────────────────────────────────

    fun resolveTargetResolution(): Size {
        val available = actions.availableVideoSizes()
        if (available.isEmpty()) return Size(MAX_CINEMA_RECORD_WIDTH, MAX_CINEMA_RECORD_HEIGHT)

        val capped = available.filter { size ->
            size.width <= MAX_CINEMA_RECORD_WIDTH && size.height <= MAX_CINEMA_RECORD_HEIGHT
        }
        val candidates = if (capped.isNotEmpty()) capped else available
        val targetAspect = 16f / 9f

        return candidates.maxWithOrNull(
            compareBy<Size>(
                { it.width.toLong() * it.height.toLong() },
                {
                    -abs(
                        (it.width.toFloat() / it.height.coerceAtLeast(1).toFloat()) - targetAspect
                    )
                }
            )
        ) ?: candidates.first()
    }

    fun resolveTargetFps(targetResolution: Size): Int {
        val targetPixels = targetResolution.width.coerceAtLeast(0).toLong() *
            targetResolution.height.coerceAtLeast(0).toLong()
        val preferredRates = if (targetPixels >= HIGH_RESOLUTION_PIXEL_THRESHOLD) {
            listOf(25, 24)
        } else {
            listOf(60, 50, 48, 25, 24)
        }
        val ranges = camera.availableFpsRanges
        return preferredRates.firstOrNull { fps ->
            ranges.any { range -> fps in range.lower..range.upper }
        } ?: recording.fps.coerceAtLeast(24)
    }

    private companion object {
        const val HIGH_RESOLUTION_PIXEL_THRESHOLD = 7_000_000L
    }
}
