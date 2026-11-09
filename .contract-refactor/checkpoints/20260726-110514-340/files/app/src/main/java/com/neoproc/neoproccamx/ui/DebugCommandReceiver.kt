package com.neoproc.neoproccamx.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.neoproc.neoproccamx.BuildConfig
import com.neoproc.neoproccamx.camera.AfRecordingUnlockFlag
import com.neoproc.neoproccamx.camera.CinematicFocusPullDebugCommands
import com.neoproc.neoproccamx.camera.CinematicFocusPullFlag
import com.neoproc.neoproccamx.camera.CoalesceRequestRebuildFlag
import com.neoproc.neoproccamx.camera.ColorDriftEnforcementFlag
import com.neoproc.neoproccamx.camera.DeviceTrueWhiteBalanceFlag
import com.neoproc.neoproccamx.camera.MeteringDampingFlag
import com.neoproc.neoproccamx.camera.NdrAdaptiveHighlightFlag
import com.neoproc.neoproccamx.camera.NdrRedClipHighlightFlag
import com.neoproc.neoproccamx.camera.NdrAntiMotionBlurFlag
import com.neoproc.neoproccamx.camera.NdrHighlightProtectFlag
import com.neoproc.neoproccamx.camera.NdrZoomStableMeteringFlag
import com.neoproc.neoproccamx.recording.NdrWarmSkinRedTameFlag
import com.neoproc.neoproccamx.camera.NeoLogHalTonemapPolicy
import com.neoproc.neoproccamx.camera.RawPreviewViewableTonemapFlag
import com.neoproc.neoproccamx.camera.SessionRebuildColorHoldFlag
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.pipeline.RecordPipelineDepthFlag
import com.neoproc.neoproccamx.recording.AbrQualityLockFlag
import com.neoproc.neoproccamx.recording.EliteVisionMeasurementFlag
import com.neoproc.neoproccamx.recording.NeoNdrTruePqTransportPolicy
import com.neoproc.neoproccamx.rendering.AlwaysSaveAtAchievedFpsFlag
import com.neoproc.neoproccamx.rendering.ApexPeakNitsFlag
import com.neoproc.neoproccamx.rendering.DenoiseSpatialFlag
import com.neoproc.neoproccamx.rendering.GlVisiblePreviewPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewPolicy
import com.neoproc.neoproccamx.rendering.ExactGamutCuspFlag
import com.neoproc.neoproccamx.rendering.DistantDetailGatesFlag
import com.neoproc.neoproccamx.rendering.EglDecodeOnceFlag
import com.neoproc.neoproccamx.rendering.EglImageCacheFlag
import com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag
import com.neoproc.neoproccamx.rendering.FullColorEngineRecordFlag
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.rendering.RecordOutputMirrorFlag
import com.neoproc.neoproccamx.rendering.PreviewColorParityFlag
import com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag
import com.neoproc.neoproccamx.rendering.SharpenOpticalFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHonoredBitrateFlag
import com.neoproc.neoproccamx.recording.RecordDevelopLiveParityFlag
import com.neoproc.neoproccamx.recording.RecordDevelopParityEnhanceGpuFlag
import com.neoproc.neoproccamx.recording.RecordDevelopEnhanceLaneGpuFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLaneFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLaneForceFlag
import com.neoproc.neoproccamx.recording.RecordDevelopHybridCpuLanePolicy
import com.neoproc.neoproccamx.recording.RecordDevelopPipelineOverlapFlag
import com.neoproc.neoproccamx.recording.RecordDevelopParityDiagnosticsFlag
import com.neoproc.neoproccamx.camera.CameraHdrColorFastModeFlag
import com.neoproc.neoproccamx.rendering.NdrPqDecodeFlag
import com.neoproc.neoproccamx.recording.RecordDevelopParityGatePolicy

class DebugCommandReceiver : BroadcastReceiver() {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.action != ACTION_DEBUG_COMMAND) return

        // Experimental: flip the active color-drift (software AWB override) enforcement at runtime.
        // The receiver runs in-process, so it sets the process-global flag directly.
        if (intent.hasExtra(EXTRA_COLOR_DRIFT_ENFORCE)) {
            val enable = intent.getBooleanExtra(EXTRA_COLOR_DRIFT_ENFORCE, false)
            ColorDriftEnforcementFlag.set(enable)
            Log.i(TAG, "ColorDriftEnforcement flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_METERING_DAMPING)) {
            val enable = intent.getBooleanExtra(EXTRA_METERING_DAMPING, true)
            MeteringDampingFlag.set(enable)
            Log.i(TAG, "MeteringDamping flag set to $enable via debug command")
        }
        // F5: restore autofocus while recording — skip the AUTO-AE SENSOR_FRAME_DURATION write that
        // freezes the vivo AF servo during recording (cadence held by CONTROL_AE_TARGET_FPS_RANGE).
        if (intent.hasExtra(EXTRA_AF_RECORDING_UNLOCK)) {
            val enable = intent.getBooleanExtra(EXTRA_AF_RECORDING_UNLOCK, true)
            AfRecordingUnlockFlag.set(enable)
            Log.i(TAG, "AfRecordingUnlock flag set to $enable via debug command")
        }
        // RC5 same-sensor rebuild AWB-washout deferral: defer the AWB lock until genuine post-rebuild
        // convergence. Default OFF; process-global gate driven in-process. Takes effect on the next
        // session rebuild (mode toggle / record start).
        if (intent.hasExtra(EXTRA_SESSION_REBUILD_COLOR_HOLD)) {
            val enable = intent.getBooleanExtra(EXTRA_SESSION_REBUILD_COLOR_HOLD, false)
            SessionRebuildColorHoldFlag.set(enable)
            Log.i(TAG, "SessionRebuildColorHold flag set to $enable via debug command")
        }
        // Device-true manual white balance (DNG dual-illuminant gains + CCM). Default ON; flip OFF
        // to A/B against the legacy Tanner-Helland approximation on-device.
        if (intent.hasExtra(EXTRA_DEVICE_TRUE_WB)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVICE_TRUE_WB, true)
            DeviceTrueWhiteBalanceFlag.set(enable)
            Log.i(TAG, "DeviceTrueWhiteBalance flag set to $enable via debug command")
        }
        // CP-QV20: cinema focus-pull. The flag gate + a route to the live camera are process-global,
        // so (like the flags above) the receiver drives them in-process. Every forwarded camera
        // method is itself gated behind CinematicFocusPullFlag, so these are no-ops when it is OFF.
        if (intent.hasExtra(EXTRA_CINE_FOCUS_PULL)) {
            val enable = intent.getBooleanExtra(EXTRA_CINE_FOCUS_PULL, false)
            CinematicFocusPullFlag.set(enable)
            Log.i(TAG, "CinematicFocusPull flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_FOCUS_PULL_MARK_A)) {
            val diopters = intent.getFloatExtra(EXTRA_FOCUS_PULL_MARK_A, -1f)
            CinematicFocusPullDebugCommands.setMark(isA = true, diopters = diopters)
            Log.i(TAG, "Focus-pull mark A set to $diopters diopters via debug command")
        }
        if (intent.hasExtra(EXTRA_FOCUS_PULL_MARK_B)) {
            val diopters = intent.getFloatExtra(EXTRA_FOCUS_PULL_MARK_B, -1f)
            CinematicFocusPullDebugCommands.setMark(isA = false, diopters = diopters)
            Log.i(TAG, "Focus-pull mark B set to $diopters diopters via debug command")
        }
        if (intent.hasExtra(EXTRA_FOCUS_PULL_CURVE) || intent.hasExtra(EXTRA_FOCUS_PULL_DURATION_MS)) {
            val curve = CinematicFocusPullDebugCommands.parseCurve(
                intent.getStringExtra(EXTRA_FOCUS_PULL_CURVE)
            )
            val durationMs = intent.getLongExtra(EXTRA_FOCUS_PULL_DURATION_MS, DEFAULT_FOCUS_PULL_DURATION_MS)
            CinematicFocusPullDebugCommands.configure(curve, durationMs)
            Log.i(TAG, "Focus-pull configured curve=$curve durationMs=$durationMs via debug command")
        }
        if (intent.hasExtra(EXTRA_FOCUS_PULL_TRIGGER) &&
            intent.getBooleanExtra(EXTRA_FOCUS_PULL_TRIGGER, false)
        ) {
            val started = CinematicFocusPullDebugCommands.trigger()
            Log.i(TAG, "Focus-pull trigger via debug command started=$started")
        }
        // s6-f08: the real-pixel Elite-Vision measurement loop. Its flag's own docstring advertises this
        // broadcast enable; wire it so the documented command actually flips the process-global gate. The
        // gate stays honest — EliteVisionProductionMeasurement still emits NOTHING when OFF and only an
        // honest NOT_PROVEN provenance key when ON without a real decoded-frame producer.
        if (intent.hasExtra(EXTRA_ELITE_VISION_MEASURE)) {
            val enable = intent.getBooleanExtra(EXTRA_ELITE_VISION_MEASURE, false)
            EliteVisionMeasurementFlag.set(enable)
            Log.i(TAG, "EliteVisionMeasurement flag set to $enable via debug command")
        }
        // CP-QV16/17: process-global spatial-denoise / optical-sharpen gates. The VulkanRuntimeManager
        // render tick forwards these to the live bridge (change-guarded), so the toggle is reversible —
        // flip false to instantly restore the byte-identical render path. Device-gated validation only.
        if (intent.hasExtra(EXTRA_SPATIAL_DENOISE)) {
            val enable = intent.getBooleanExtra(EXTRA_SPATIAL_DENOISE, false)
            DenoiseSpatialFlag.enabled = enable
            Log.i(TAG, "SpatialDenoise (CP-QV16) flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_OPTICAL_SHARPEN)) {
            val enable = intent.getBooleanExtra(EXTRA_OPTICAL_SHARPEN, false)
            SharpenOpticalFlag.enabled = enable
            Log.i(TAG, "OpticalSharpen (CP-QV17) flag set to $enable via debug command")
        }
        // CP24: distant-detail elite gates (detail_preservation noise-aware + halo-guard, guided
        // edge-tighten). Per-dispatch push constants forwarded by VulkanRuntimeManager — reversible.
        if (intent.hasExtra(EXTRA_DISTANT_DETAIL)) {
            val enable = intent.getBooleanExtra(EXTRA_DISTANT_DETAIL, false)
            DistantDetailGatesFlag.enabled = enable
            Log.i(TAG, "DistantDetailGates (CP24) flag set to $enable via debug command")
        }
        // CP30: BT.2390 EETF peak-nits highlight shoulder (knee_curve). Per-dispatch push constant
        // forwarded by VulkanRuntimeManager — reversible; identity below diffuse white.
        if (intent.hasExtra(EXTRA_APEX_PEAK_NITS)) {
            val enable = intent.getBooleanExtra(EXTRA_APEX_PEAK_NITS, false)
            ApexPeakNitsFlag.enabled = enable
            Log.i(TAG, "ApexPeakNits (CP30) flag set to $enable via debug command")
        }
        // CP-CUSP: exact Rec.2020 Oklab gamut-cusp clamp (accuracy tool, reduces oversaturation).
        // Per-frame UBO lane forwarded by VulkanRuntimeManager — reversible; default OFF = analytic guard.
        if (intent.hasExtra(EXTRA_EXACT_GAMUT_CUSP)) {
            val enable = intent.getBooleanExtra(EXTRA_EXACT_GAMUT_CUSP, false)
            ExactGamutCuspFlag.enabled = enable
            Log.i(TAG, "ExactGamutCusp (CP-CUSP) flag set to $enable via debug command")
        }
        // Additive per-mode GL preview compositor (LOG looks like LOG / RAW like a monitor image).
        // Preview-only; its onChanged re-binds the preview surface. Default OFF; device-gated A/B.
        if (intent.hasExtra(EXTRA_GL_PREVIEW)) {
            val enable = intent.getBooleanExtra(EXTRA_GL_PREVIEW, false)
            GlVisiblePreviewPolicy.set(enable)
            Log.i(TAG, "GlVisiblePreview flag set to $enable via debug command")
        }
        // Film-print show-LUT to recorded SDR pixels via the offscreen-EGL creative-LUT stage (the vivo
        // record pipeline). Default OFF; flip true to A/B the graded record look on the next recording.
        // Its onChanged re-pushes the record creative LUT.
        if (intent.hasExtra(EXTRA_FILM_PRINT_LOOK)) {
            val enable = intent.getBooleanExtra(EXTRA_FILM_PRINT_LOOK, false)
            FilmPrintLookRecordFlag.set(enable)
            Log.i(TAG, "FilmPrintLookRecord flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion bitrate: ON (default) prefers VBR so the ~286 Mbps target is honored
        // (CQ-first ignored KEY_BIT_RATE → low bitrate); OFF restores the legacy CQ-first order.
        if (intent.hasExtra(EXTRA_DEVELOP_HONORED_BITRATE)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_HONORED_BITRATE, true)
            RecordDevelopHonoredBitrateFlag.set(enable)
            Log.i(TAG, "RecordDevelopHonoredBitrate flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion look: ON (default) matches the live cinema-camera sharpness/edges/noise
        // (bilateral + halo-guarded unsharp, over-sharpen lanes disabled); OFF restores the legacy look.
        if (intent.hasExtra(EXTRA_DEVELOP_LIVE_PARITY)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_LIVE_PARITY, true)
            RecordDevelopLiveParityFlag.set(enable)
            Log.i(TAG, "RecordDevelopLiveParity flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion: run the live-parity luma enhance (bilateral + halo-unsharp) on the
        // GPU (parity-gated, auto CPU fallback) so the conversion uses GPU+CPU together; OFF forces CPU.
        if (intent.hasExtra(EXTRA_DEVELOP_PARITY_ENHANCE_GPU)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_PARITY_ENHANCE_GPU, true)
            RecordDevelopParityEnhanceGpuFlag.set(enable)
            Log.i(TAG, "RecordDevelopParityEnhanceGpu flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion: run the CPU-only CHROMA enhance lane (temporal/chroma-NR/deadband/
        // spike/dither) on the GPU (parity-gated, auto CPU fallback). DEFAULT OFF (fp-noise lane; device-
        // A/B first). ON verifies the GPU lane per frame against CPU and uses it only within tolerance.
        if (intent.hasExtra(EXTRA_DEVELOP_ENHANCE_LANE_GPU)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_ENHANCE_LANE_GPU, false)
            RecordDevelopEnhanceLaneGpuFlag.set(enable)
            Log.i(TAG, "RecordDevelopEnhanceLaneGpu flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion: concurrent CPU+GPU hybrid — run a second CPU develop lane beside the
        // GPU so both develop different frames at once (throughput lever; memory-guarded; byte-identical).
        if (intent.hasExtra(EXTRA_DEVELOP_HYBRID_CPU_LANE)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_HYBRID_CPU_LANE, false)
            RecordDevelopHybridCpuLaneFlag.set(enable)
            Log.i(TAG, "RecordDevelopHybridCpuLane flag set to $enable via debug command")
        }
        // Bypass the hybrid heap guard (A/B only) to force the two-lane hybrid for headroom measurement.
        if (intent.hasExtra(EXTRA_DEVELOP_HYBRID_CPU_FORCE)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_HYBRID_CPU_FORCE, false)
            RecordDevelopHybridCpuLaneForceFlag.set(enable)
            Log.i(TAG, "RecordDevelopHybridCpuLaneForce flag set to $enable via debug command")
        }
        // Tune the CPU lane's frame share in basis points (0..8000 = 0..80%); default 6000 (60%).
        if (intent.hasExtra(EXTRA_DEVELOP_HYBRID_CPU_SHARE)) {
            val bps = intent.getIntExtra(EXTRA_DEVELOP_HYBRID_CPU_SHARE, RecordDevelopHybridCpuLanePolicy.DEFAULT_CPU_SHARE_BPS)
            RecordDevelopHybridCpuLanePolicy.setCpuShareBps(bps)
            Log.i(TAG, "RecordDevelopHybridCpuLane cpuShareBps set to ${RecordDevelopHybridCpuLanePolicy.cpuShareBps} via debug command")
        }
        // SDR/NDR develop-conversion: pipeline overlap — keep >1 frame in flight so decode/develop/mux run
        // concurrently (throughput; guarded by real-heap headroom; byte-identical). Default OFF; A/B.
        if (intent.hasExtra(EXTRA_DEVELOP_PIPELINE_OVERLAP)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_PIPELINE_OVERLAP, false)
            RecordDevelopPipelineOverlapFlag.set(enable)
            Log.i(TAG, "RecordDevelopPipelineOverlap flag set to $enable via debug command")
        }
        // NDR10+/NIVUKX input-transfer decode: the camera captures PQ (HDR10_PLUS) but the pipeline
        // decoded it as HLG (confirmed colour bug). ON => PQ (ST.2084) decode (fix); OFF => legacy HLG.
        if (intent.hasExtra(EXTRA_NDR_PQ_DECODE)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_PQ_DECODE, true)
            NdrPqDecodeFlag.set(enable)
            Log.i(TAG, "NdrPqDecode flag set to $enable via debug command")
        }
        // NDR10+/NIVUKX shared-capture warm-red fix: prefer the lighter stock FAST vendor CCM over the
        // over-saturating HIGH_QUALITY CCM for HDR. ON => FAST (fix; reaches preview+record+convert);
        // OFF => legacy HIGH_QUALITY. HDR-only; SDR unaffected.
        if (intent.hasExtra(EXTRA_HDR_CCM_FAST)) {
            val enable = intent.getBooleanExtra(EXTRA_HDR_CCM_FAST, true)
            CameraHdrColorFastModeFlag.set(enable)
            Log.i(TAG, "CameraHdrColorFastMode flag set to $enable via debug command")
        }
        // SDR/NDR develop-conversion GPU-vs-CPU parity A/B harness (default OFF): log the full first-frame
        // residual distribution (mean/max/p99.9/... + worst pixels) per category, and live-tune the gate
        // tolerances from the measured residual (all reversible; comparison-only, never alters pixels).
        if (intent.hasExtra(EXTRA_DEVELOP_PARITY_DIAGNOSTICS)) {
            val enable = intent.getBooleanExtra(EXTRA_DEVELOP_PARITY_DIAGNOSTICS, false)
            RecordDevelopParityDiagnosticsFlag.set(enable)
            Log.i(TAG, "RecordDevelopParityDiagnostics flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_DEVELOP_PARITY_MEAN_TOL)) {
            val v = intent.getFloatExtra(
                EXTRA_DEVELOP_PARITY_MEAN_TOL,
                RecordDevelopParityGatePolicy.DEFAULT_MEAN_TOLERANCE.toFloat()
            )
            RecordDevelopParityGatePolicy.setMeanTolerance(v.toDouble())
            Log.i(TAG, "RecordDevelopParityGate meanTol set to $v via debug command")
        }
        if (intent.hasExtra(EXTRA_DEVELOP_PARITY_MAX_TOL)) {
            val v = intent.getIntExtra(EXTRA_DEVELOP_PARITY_MAX_TOL, RecordDevelopParityGatePolicy.DEFAULT_MAX_TOLERANCE)
            RecordDevelopParityGatePolicy.setMaxTolerance(v)
            Log.i(TAG, "RecordDevelopParityGate maxTol set to $v via debug command")
        }
        if (intent.hasExtra(EXTRA_DEVELOP_PARITY_P999_TOL)) {
            val v = intent.getIntExtra(EXTRA_DEVELOP_PARITY_P999_TOL, RecordDevelopParityGatePolicy.DEFAULT_P999_TOLERANCE)
            RecordDevelopParityGatePolicy.setP999Tolerance(v)
            Log.i(TAG, "RecordDevelopParityGate p99.9Tol set to $v via debug command")
        }
        // Keep the isolated GL monitor-grade compositor engaged WHILE RECORDING (fixes the gray/flat
        // preview during recording for VIDEO/PRO/LOG/RAW-monitor). Default OFF; onChanged re-binds preview.
        if (intent.hasExtra(EXTRA_PREVIEW_GRADE_RECORDING)) {
            val enable = intent.getBooleanExtra(EXTRA_PREVIEW_GRADE_RECORDING, false)
            PreviewGradeWhileRecordingFlag.set(enable)
            Log.i(TAG, "PreviewGradeWhileRecording flag set to $enable via debug command")
        }
        // 10-bit BT.2020-PQ preview compositor (WYSIWYG NDR10+/NIVUKX PQ look). Default OFF;
        // its onChanged re-binds the preview surface. Device-gated (10-bit-PQ EGL / ANR wall):
        // flip false for instant revert to the direct HLG10 preview.
        if (intent.hasExtra(EXTRA_PQ_PREVIEW)) {
            val enable = intent.getBooleanExtra(EXTRA_PQ_PREVIEW, false)
            Hdr10PqPreviewPolicy.set(enable)
            Log.i(TAG, "Hdr10PqPreview flag set to $enable via debug command")
        }
        // PQ-preview OUTPUT transfer. ON (default) => display-referred SDR on an 8-bit sRGB surface, which
        // BINDS on this device and applies the app's PQ-decode + hue-preserving operator + BT.2020→BT.709
        // gamut compression + sRGB OETF (fixes the direct-preview red). OFF => 10-bit BT.2020-PQ surface
        // (fails to bind on vivo → direct preview). onChanged rebuilds the EGL surface.
        if (intent.hasExtra(EXTRA_PQ_PREVIEW_SDR_OUT)) {
            val enable = intent.getBooleanExtra(EXTRA_PQ_PREVIEW_SDR_OUT, true)
            com.neoproc.neoproccamx.rendering.Hdr10PqPreviewSdrOutputFlag.set(enable)
            Log.i(TAG, "Hdr10PqPreviewSdrOutput flag set to $enable via debug command")
        }
        // Genuine NeoLog HAL tonemap (bakes N-LOG v5 APEX-LOG into the LOG camera request). Reversible:
        // flip false to fall back to the proven vendor tonemap. Takes effect on the next LOG request
        // rebuild (toggle the LOG mode off/on). Device-gated verification only.
        if (intent.hasExtra(EXTRA_NEOLOG_HAL)) {
            val enable = intent.getBooleanExtra(EXTRA_NEOLOG_HAL, true)
            NeoLogHalTonemapPolicy.set(enable)
            Log.i(TAG, "NeoLogHalTonemap flag set to $enable via debug command")
        }
        // NDR true-PQ transport (Fork A). Default ON: genuine ST.2084 PQ end-to-end (record shader
        // converts HLG->PQ, EGL delivery BT.2020_PQ, encoder/colr/HDR10/MediaStore all ST.2084). Flip
        // false for the coherent-HLG A/B fallback (no shader conversion, every tag HLG). Takes effect
        // on the next recording. Verify on device with ffprobe (smpte2084 vs arib-std-b67) + playback.
        if (intent.hasExtra(EXTRA_NDR_TRUE_PQ)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_TRUE_PQ, true)
            NeoNdrTruePqTransportPolicy.set(enable)
            Log.i(TAG, "NeoNdrTruePqTransport flag set to $enable via debug command")
        }
        // NDR10+/NIVUKX capture highlight protection: bias auto-exposure darker so highlights keep
        // headroom instead of clipping to white. Default ON; takes effect on the next request rebuild.
        if (intent.hasExtra(EXTRA_NDR_HIGHLIGHT_PROTECT)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_HIGHLIGHT_PROTECT, true)
            NdrHighlightProtectFlag.set(enable)
            Log.i(TAG, "NdrHighlightProtect flag set to $enable via debug command")
        }
        // NDR10+/NIVUKX closed-loop anti-motion-blur shutter cap (auto-exposure stays adaptive; the
        // exposure time is pinned at the cap only in dim scenes). Default ON; next request rebuild.
        if (intent.hasExtra(EXTRA_NDR_ANTI_BLUR)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_ANTI_BLUR, true)
            NdrAntiMotionBlurFlag.set(enable)
            Log.i(TAG, "NdrAntiMotionBlur flag set to $enable via debug command")
        }
        // Zoom-stable full-frame HDR metering (fixes zoom-out highlight blowout). Default ON.
        if (intent.hasExtra(EXTRA_NDR_ZOOM_STABLE_METER)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_ZOOM_STABLE_METER, true)
            NdrZoomStableMeteringFlag.set(enable)
            Log.i(TAG, "NdrZoomStableMetering flag set to $enable via debug command")
        }
        // Closed-loop adaptive highlight protection (fixes blown highlights + overexposure red). Default ON.
        if (intent.hasExtra(EXTRA_NDR_ADAPTIVE_HIGHLIGHT)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_ADAPTIVE_HIGHLIGHT, true)
            NdrAdaptiveHighlightFlag.set(enable)
            Log.i(TAG, "NdrAdaptiveHighlight flag set to $enable via debug command")
        }
        // RED-channel-aware highlight protection (H.Sup15): pulls exposure down when the RED channel is
        // clipping, which the luma-only meter is blind to — the NDR/NIVUKX "red skin" root. Default ON.
        if (intent.hasExtra(EXTRA_NDR_RED_CLIP_HIGHLIGHT)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_RED_CLIP_HIGHLIGHT, true)
            NdrRedClipHighlightFlag.set(enable)
            Log.i(TAG, "NdrRedClipHighlight flag set to $enable via debug command")
        }
        // Skin-gated warm-red taming: post-corrects the camera-baked warm/skin red cast that the
        // hue-preserving develop faithfully passes through (host-verified). ON => tame; OFF => byte-identical.
        if (intent.hasExtra(EXTRA_NDR_WARM_SKIN_RED_TAME)) {
            val enable = intent.getBooleanExtra(EXTRA_NDR_WARM_SKIN_RED_TAME, true)
            NdrWarmSkinRedTameFlag.set(enable)
            Log.i(TAG, "NdrWarmSkinRedTame flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_NDR_WARM_SKIN_RED_TAME_STRENGTH)) {
            val s = intent.getFloatExtra(
                EXTRA_NDR_WARM_SKIN_RED_TAME_STRENGTH,
                NdrWarmSkinRedTameFlag.DEFAULT_STRENGTH
            )
            NdrWarmSkinRedTameFlag.setStrength(s)
            Log.i(TAG, "NdrWarmSkinRedTame strength set to $s via debug command")
        }
        // Full ColorScience creative colour engine -> recorded SDR pixels (profile/curves/gamut/tonemap/
        // creative-LUT/sat-contrast) via the offscreen-EGL creative-LUT stage. Default OFF; onChanged
        // re-pushes the record LUT. SDR-only; NDR/NIVUKX PQ colour is owned by NeoNdrTruePqTransportPolicy.
        if (intent.hasExtra(EXTRA_FULL_COLOR_RECORD)) {
            val enable = intent.getBooleanExtra(EXTRA_FULL_COLOR_RECORD, false)
            FullColorEngineRecordFlag.set(enable)
            Log.i(TAG, "FullColorEngineRecord flag set to $enable via debug command")
        }
        // True preview colour parity (WYSIWYG): feed the graded preview cube into the live GL compositor's
        // SDR_ENHANCE creative-LUT stage. Default OFF; onChanged re-pushes the preview cube. SDR-only.
        if (intent.hasExtra(EXTRA_PREVIEW_COLOR_PARITY)) {
            val enable = intent.getBooleanExtra(EXTRA_PREVIEW_COLOR_PARITY, false)
            PreviewColorParityFlag.set(enable)
            Log.i(TAG, "PreviewColorParity flag set to $enable via debug command")
        }
        // FPS-gate always-save safety net: finalize a GPU-processed recording at the achieved fps instead
        // of deleting it when it misses the selected fps under thermal load. Default OFF.
        if (intent.hasExtra(EXTRA_ALWAYS_SAVE_ACHIEVED_FPS)) {
            val enable = intent.getBooleanExtra(EXTRA_ALWAYS_SAVE_ACHIEVED_FPS, false)
            AlwaysSaveAtAchievedFpsFlag.set(enable)
            Log.i(TAG, "AlwaysSaveAtAchievedFps flag set to $enable via debug command")
        }
        // RC2: keep the RAW-video preview on a viewable tonemap (recorded RAW/DNG unaffected). Default OFF.
        if (intent.hasExtra(EXTRA_RAW_PREVIEW_VIEWABLE_TONEMAP)) {
            val enable = intent.getBooleanExtra(EXTRA_RAW_PREVIEW_VIEWABLE_TONEMAP, false)
            RawPreviewViewableTonemapFlag.set(enable)
            Log.i(TAG, "RawPreviewViewableTonemap flag set to $enable via debug command")
        }
        // RAW debayer-monitor orientation parity: apply the app's display rotation/mirror to the 2D
        // debayer present so RAW is upright. Default OFF (identity); onChanged re-applies geometry live.
        if (intent.hasExtra(EXTRA_RAW_DEBAYER_ORIENT)) {
            val enable = intent.getBooleanExtra(EXTRA_RAW_DEBAYER_ORIENT, false)
            com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag.set(enable)
            Log.i(TAG, "RawDebayerPreviewOrientationParity flag set to $enable via debug command")
        }
        // Offline RAW→NeoLog GPU develop (demosaic on GPU, NeoLog finish on CPU, first-frame parity gate).
        // Default OFF (device-gated): flip on to validate GPU RAW conversion on-device; CPU fallback stays.
        if (intent.hasExtra(EXTRA_RAW_GPU_DEVELOP)) {
            val enable = intent.getBooleanExtra(EXTRA_RAW_GPU_DEVELOP, false)
            com.neoproc.neoproccamx.recording.RawDevelopGpuAccelerator.setDevelopGpuEnabled(enable)
            Log.i(TAG, "RawDevelopGpuAccelerator develop-GPU flag set to $enable via debug command")
        }
        // Hot-path real-time thread-priority elevation (record submit/drain + preview compositors +
        // RAW/P010 writer -> URGENT_DISPLAY; analysis -> DISPLAY). Default ON (pure scheduling, no
        // pixel change). Flip OFF for an A/B baseline. Takes effect on threads created after this call.
        if (intent.hasExtra(EXTRA_HOT_PATH_PRIORITY)) {
            val enable = intent.getBooleanExtra(EXTRA_HOT_PATH_PRIORITY, true)
            HotPathScheduling.set(enable)
            Log.i(TAG, "HotPathScheduling flag set to $enable via debug command")
        }
        // Deepen the hardware-import frame dispatcher (2->4) so transient GPU/GC/scheduler hitches don't
        // conflate recorded frames. Default ON; takes effect on the next pipeline build.
        if (intent.hasExtra(EXTRA_RECORD_PIPELINE_DEPTH)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_PIPELINE_DEPTH, true)
            RecordPipelineDepthFlag.set(enable)
            Log.i(TAG, "RecordPipelineDepth flag set to $enable via debug command")
        }
        // Lock ABR quality on VIDEO/PRO: never auto-reduce recorded bitrate + shader detail for pacing
        // (the vivo encoder has ample headroom). Default ON. Manual bitrate selection still wins.
        if (intent.hasExtra(EXTRA_ABR_QUALITY_LOCK)) {
            val enable = intent.getBooleanExtra(EXTRA_ABR_QUALITY_LOCK, true)
            AbrQualityLockFlag.set(enable)
            Log.i(TAG, "AbrQualityLock flag set to $enable via debug command")
        }
        // Coalesce redundant non-immediate updatePreview() rebuilds (kills pinch/EV/WB gesture lag).
        // Default ON; byte-identical final request. Takes effect immediately (read per updatePreview call).
        if (intent.hasExtra(EXTRA_COALESCE_REQUEST_REBUILD)) {
            val enable = intent.getBooleanExtra(EXTRA_COALESCE_REQUEST_REBUILD, true)
            CoalesceRequestRebuildFlag.set(enable)
            Log.i(TAG, "CoalesceRequestRebuild flag set to $enable via debug command")
        }
        // #5 device-gated (default OFF): reuse EGLImageKHR per AHB slot on the offscreen record submit
        // (kills per-frame driver import jitter). Takes effect on the next recording; ABA-validate first.
        if (intent.hasExtra(EXTRA_EGL_IMAGE_CACHE)) {
            val enable = intent.getBooleanExtra(EXTRA_EGL_IMAGE_CACHE, false)
            EglImageCacheFlag.set(enable)
            Log.i(TAG, "EglImageCache flag set to $enable via debug command")
        }
        // #2 device-gated (default OFF): decode-once record shader (decode external YUV once -> fp16,
        // then 13-tap on fp16). Raises GPU headroom to lift the record fps caps. Next recording; A/B.
        if (intent.hasExtra(EXTRA_EGL_DECODE_ONCE)) {
            val enable = intent.getBooleanExtra(EXTRA_EGL_DECODE_ONCE, false)
            EglDecodeOnceFlag.set(enable)
            Log.i(TAG, "EglDecodeOnce flag set to $enable via debug command")
        }
        // MIRRORFIX v5: per-axis record-output flip (un-mirror the saved video). Default flipY=ON
        // un-mirrors the portrait case; landscape wants flipX=ON + flipY=OFF; both OFF = raw passthrough.
        // Takes effect on the NEXT recording (pushed at setEncoderSurface).
        if (intent.hasExtra(EXTRA_RECORD_FLIP_X)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_FLIP_X, false)
            RecordOutputMirrorFlag.setFlipX(enable)
            Log.i(TAG, "RecordOutputMirror flipX set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_RECORD_FLIP_Y)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_FLIP_Y, true)
            RecordOutputMirrorFlag.setFlipY(enable)
            Log.i(TAG, "RecordOutputMirror flipY set to $enable via debug command")
        }
        // default ON (device-verified): lift the GPU-processed record fps ceiling to the device-measured
        // throughput so 4096x3072 records at 25fps (up from 24) for VIDEO/PRO/NDR. Flip OFF to A/B the
        // conservative baseline. Takes effect on the next recording.
        if (intent.hasExtra(EXTRA_RECORD_FPS_CEILING)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_FPS_CEILING, true)
            RecordFpsCeilingFlag.set(enable)
            Log.i(TAG, "RecordFpsCeiling flag set to $enable via debug command")
        }
        // Record-then-develop master switch (default OFF): when ON the live record lane runs LIGHT (flat
        // full-sensor intermediate at a lifted fps cap) and the elite grade is applied OFFLINE. Takes
        // effect on the next recording.
        if (intent.hasExtra(EXTRA_RECORD_DEVELOP_MODE)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_DEVELOP_MODE, false)
            RecordDevelopModeFlag.set(enable)
            Log.i(TAG, "RecordDevelopMode flag set to $enable via debug command")
        }
        // Salvage a genuinely-encoded record-then-develop master at stop instead of aborting+deleting
        // it when the muxer writer did not drain in time (default ON). Flip OFF to A/B the legacy
        // abort+delete behaviour. Takes effect on the next recording stop.
        if (intent.hasExtra(EXTRA_RECORD_DEVELOP_SALVAGE_ON_STOP)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_DEVELOP_SALVAGE_ON_STOP, true)
            com.neoproc.neoproccamx.recording.RecordDevelopSalvageOnStopFailureFlag.set(enable)
            Log.i(TAG, "RecordDevelopSalvageOnStopFailure flag set to $enable via debug command")
        }
        // NDR develop SNCE white-normalisation (default ON): run CAM16 at diffuse white ≈1.0 so it stops
        // over-boosting reds. Pass false here to A/B the old look against a gray-card NDR convert.
        // Takes effect on the next NDR→convert.
        if (intent.hasExtra(EXTRA_RECORD_DEVELOP_WHITE_NORMALIZE)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_DEVELOP_WHITE_NORMALIZE, true)
            com.neoproc.neoproccamx.recording.RecordDevelopWhiteNormalizeFlag.set(enable)
            Log.i(TAG, "RecordDevelopWhiteNormalize flag set to $enable via debug command")
        }
        // Full-sensor constant-fps (default ON): pin full-sensor (>=12 MP) recording to a rock-solid 25 fps
        // (caps the LIGHT-lane 30 lift) and CFR-muxes the full-sensor offscreen clip so the delivered
        // timeline is constant. Flip OFF to A/B the prior LIGHT-lane 30 / VFR behaviour. Next recording.
        if (intent.hasExtra(EXTRA_FULL_SENSOR_CONSTANT_FPS)) {
            val enable = intent.getBooleanExtra(EXTRA_FULL_SENSOR_CONSTANT_FPS, true)
            com.neoproc.neoproccamx.recording.FullSensorConstantFpsFlag.set(enable)
            Log.i(TAG, "FullSensorConstantFps flag set to $enable via debug command")
        }
        // Encoder-size device-truth cap (default ON): accept a record/encoder size only if the HW encoder
        // can actually configure it at the clamped fps (areSizeAndRateSupported), not just spatially
        // (isSizeSupported). Stops the unencodable full-sensor 8192×6144 request that otherwise hangs
        // recording-start ~24 s in a session-rebuild/"Encoder pipeline error" loop. Flip OFF to A/B the
        // prior spatial-only behaviour. Applied on the next recording-start.
        if (intent.hasExtra(EXTRA_ENCODER_SIZE_DEVICE_TRUTH_CAP)) {
            val enable = intent.getBooleanExtra(EXTRA_ENCODER_SIZE_DEVICE_TRUTH_CAP, true)
            com.neoproc.neoproccamx.recording.EncoderSizeDeviceTruthCapFlag.set(enable)
            Log.i(TAG, "EncoderSizeDeviceTruthCap flag set to $enable via debug command")
        }
        // Offline RAW→LOG grade-safe elite detail (default ON): edge-aware denoise + detail sharpen +
        // OmniClarity clarity on the linear ACEScg plane (no creative colour — the LOG stays flat/gradeable).
        // Flip OFF to A/B the plain develop. Takes effect on the next RAW→LOG conversion.
        if (intent.hasExtra(EXTRA_RAW_LOG_ELITE_DETAIL)) {
            val enable = intent.getBooleanExtra(EXTRA_RAW_LOG_ELITE_DETAIL, true)
            com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailFlag.set(enable)
            Log.i(TAG, "RawDevelopEliteDetail flag set to $enable via debug command")
        }
        // Single-worker offline RAW→LOG CPU+GPU OVERLAP (default ON): pipeline frame N+1's GPU demosaic with
        // frame N's CPU NeoLog finish on the flagship full-sensor / 4K RAW (where the worker count floors to
        // 1). Byte-identical output — pure scheduling win. Flip OFF to fall back to the sequential develop
        // (e.g. on a device that shows memory pressure). Takes effect on the next RAW→LOG conversion.
        if (intent.hasExtra(EXTRA_RAW_DEVELOP_OVERLAP)) {
            val enable = intent.getBooleanExtra(EXTRA_RAW_DEVELOP_OVERLAP, true)
            com.neoproc.neoproccamx.recording.RawDevelopPipelineOverlapPolicy.set(enable)
            Log.i(TAG, "RawDevelopPipelineOverlap flag set to $enable via debug command")
        }
        // Offline SDR/NDR develop enhance stage: fan the ~12 heavy full-frame kernel passes across all CPU
        // cores in the single-worker (full-sensor) regime. Byte-identical output — pure scheduling win.
        // Flip OFF to fall back to the sequential enhance. Takes effect on the next SDR/NDR develop.
        if (intent.hasExtra(EXTRA_RECORD_ENHANCE_PARALLEL)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_ENHANCE_PARALLEL, true)
            com.neoproc.neoproccamx.recording.RecordDevelopEnhanceParallelismPolicy.set(enable)
            Log.i(TAG, "RecordDevelopEnhanceParallelism flag set to $enable via debug command")
        }
        // Publish copy-buffer size (KiB) for the develop `.mov` → MediaStore copy. Default 8192 KiB (8 MiB)
        // replaces the JDK 8 KiB copyTo buffer that made the multi-GB gallery publish crawl over FUSE. Byte-
        // identical output; set a small value (e.g. 8) to reproduce the original slow copy. Next publish.
        if (intent.hasExtra(EXTRA_RECORD_DEVELOP_PUBLISH_COPY_KB)) {
            val kb = intent.getIntExtra(
                EXTRA_RECORD_DEVELOP_PUBLISH_COPY_KB,
                com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.DEFAULT_COPY_BUFFER_BYTES / 1024
            )
            com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.set(kb * 1024)
            Log.i(TAG, "RecordDevelopPublishCopyPolicy buffer set to " +
                "${com.neoproc.neoproccamx.recording.RecordDevelopPublishCopyPolicy.bufferBytes} bytes via debug command")
        }
        // RAW video capture-mode A/B (default LOSSLESS = MotionCam-style predictive+Deflate BEST_SPEED,
        // 16-bit bit-exact). Set HIGH_COMPRESSION to restore the legacy uncompressed packed-12-bit master,
        // LOSSLESS_ENHANCED for max ratio, OFF for open DNG-stills export, or DEFAULT/blank to clear the
        // override. Takes effect on the next RAW clip (read at startCapture()).
        if (intent.hasExtra(EXTRA_RAW_COMPRESSION_MODE)) {
            val value = intent.getStringExtra(EXTRA_RAW_COMPRESSION_MODE)
            com.neoproc.neoproccamx.recording.RawVideoContractPolicy.applyCaptureModeOverride(value)
            Log.i(TAG, "RawVideoContractPolicy capture-mode override set to " +
                "${com.neoproc.neoproccamx.recording.RawVideoContractPolicy.captureModeOverride} via debug command")
        }

        // ---- Stability / performance / boot audit (2026-07-15) reversible gates ----
        // All process-global; set in-process here so `adb ... --ez debug.<key> <bool>` takes effect.
        if (intent.hasExtra(EXTRA_SESSION_SUPPORT_PROBE)) {
            val enable = intent.getBooleanExtra(EXTRA_SESSION_SUPPORT_PROBE, false)
            com.neoproc.neoproccamx.camera.SessionSupportProbeFlag.set(enable)
            Log.i(TAG, "SessionSupportProbe flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_OFFSCREEN_ENCODER_BOOT_PROBE_SKIP)) {
            val enable = intent.getBooleanExtra(EXTRA_OFFSCREEN_ENCODER_BOOT_PROBE_SKIP, true)
            com.neoproc.neoproccamx.rendering.OffscreenEncoderBootProbeSkipFlag.set(enable)
            Log.i(TAG, "OffscreenEncoderBootProbeSkip flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_HARDWARE_DRAIN_SERIALIZE)) {
            val enable = intent.getBooleanExtra(EXTRA_HARDWARE_DRAIN_SERIALIZE, true)
            com.neoproc.neoproccamx.pipeline.HardwareDrainSerializationFlag.set(enable)
            Log.i(TAG, "HardwareDrainSerialization flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_AI_TEARDOWN_OFF_MAIN)) {
            val enable = intent.getBooleanExtra(EXTRA_AI_TEARDOWN_OFF_MAIN, false)
            com.neoproc.neoproccamx.pipeline.AiPipelineTeardownOffMainFlag.set(enable)
            Log.i(TAG, "AiPipelineTeardownOffMain flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_ANALYSIS_CHROMA_ONLY_SNAPSHOT)) {
            val enable = intent.getBooleanExtra(EXTRA_ANALYSIS_CHROMA_ONLY_SNAPSHOT, false)
            com.neoproc.neoproccamx.pipeline.AnalysisChromaOnlySnapshotFlag.set(enable)
            Log.i(TAG, "AnalysisChromaOnlySnapshot flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_AI_ANALYSIS_CONVERT_SKIP)) {
            val enable = intent.getBooleanExtra(EXTRA_AI_ANALYSIS_CONVERT_SKIP, true)
            com.neoproc.neoproccamx.pipeline.AiAnalysisConvertSkipFlag.set(enable)
            Log.i(TAG, "AiAnalysisConvertSkip flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_DEFERRED_LUMA_SNAPSHOT)) {
            val enable = intent.getBooleanExtra(EXTRA_DEFERRED_LUMA_SNAPSHOT, false)
            com.neoproc.neoproccamx.pipeline.DeferredLumaSnapshotFlag.set(enable)
            Log.i(TAG, "DeferredLumaSnapshot flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_RECORD_STOP_SINGLE_REBUILD)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_STOP_SINGLE_REBUILD, false)
            RecordStopSingleSessionRebuildFlag.set(enable)
            Log.i(TAG, "RecordStopSingleSessionRebuild flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_RECORD_STOP_SUBMIT_QUIESCE)) {
            val enable = intent.getBooleanExtra(EXTRA_RECORD_STOP_SUBMIT_QUIESCE, false)
            RecordStopSubmitQuiesceFlag.set(enable)
            Log.i(TAG, "RecordStopSubmitQuiesce flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_VIDEO_COLD_OPEN_HW_IMPORT_SESSION)) {
            val enable = intent.getBooleanExtra(EXTRA_VIDEO_COLD_OPEN_HW_IMPORT_SESSION, false)
            com.neoproc.neoproccamx.camera.VideoColdOpenHardwareImportSessionFlag.set(enable)
            Log.i(TAG, "VideoColdOpenHardwareImportSession flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_ASYNC_SECURITY_INIT)) {
            val enable = intent.getBooleanExtra(EXTRA_ASYNC_SECURITY_INIT, false)
            com.neoproc.neoproccamx.security.AsyncSecurityInitFlag.set(enable)
            Log.i(TAG, "AsyncSecurityInit flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_PREFS_TYPED_FAST_PATH)) {
            val enable = intent.getBooleanExtra(EXTRA_PREFS_TYPED_FAST_PATH, false)
            com.neoproc.neoproccamx.utils.PreferencesTypedFastPathFlag.set(enable)
            Log.i(TAG, "PreferencesTypedFastPath flag set to $enable via debug command")
        }
        if (intent.hasExtra(EXTRA_SELECT_CAMERA_CHARS_REUSE)) {
            val enable = intent.getBooleanExtra(EXTRA_SELECT_CAMERA_CHARS_REUSE, false)
            com.neoproc.neoproccamx.camera.SelectCameraCharacteristicsReuseFlag.set(enable)
            Log.i(TAG, "SelectCameraCharacteristicsReuse flag set to $enable via debug command")
        }

        val commandIntent = Intent(intent).apply {
            action = ACTION_DEBUG_COMMAND
            putExtra(EXTRA_STRICT_HIGH_END, strictHighEndEnabled())
            putExtra(EXTRA_COLOR_DRIFT_ENFORCE, ColorDriftEnforcementFlag.enabled)
            putExtra(EXTRA_METERING_DAMPING, MeteringDampingFlag.enabled)
            putExtra(EXTRA_SESSION_REBUILD_COLOR_HOLD, SessionRebuildColorHoldFlag.enabled)
            putExtra(EXTRA_DEVICE_TRUE_WB, DeviceTrueWhiteBalanceFlag.enabled)
        }
        Log.i(TAG, "Received debug command broadcast intent=$commandIntent")

        if (MainActivity.dispatchDebugCommand(commandIntent)) {
            Log.i(TAG, "Delivered debug command to active MainActivity")
            return
        }
        Log.i(TAG, "Active MainActivity unavailable, queueing debug command for next launch/resume")
        MainActivity.queueDebugCommand(commandIntent)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_DEBUG_COMMAND
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        runCatching {
            context.startActivity(launchIntent)
        }.onSuccess {
            Log.i(TAG, "Queued debug command and launched MainActivity")
        }.onFailure { throwable ->
            Log.w(
                TAG,
                "Unable to launch MainActivity for debug command; command remains queued",
                throwable
            )
        }
    }

    companion object {
        private const val TAG = "DebugCommandReceiver"
        const val ACTION_DEBUG_COMMAND = "com.neoproc.neoproccamx.DEBUG_COMMAND"
        const val EXTRA_STRICT_HIGH_END = "debug.strict_high_end"
        const val EXTRA_COLOR_DRIFT_ENFORCE = "debug.color_drift_enforce"
        const val EXTRA_METERING_DAMPING = "debug.metering_damping"
        // F5: AUTO-AE autofocus-while-recording unlock (skip the AF-servo-freezing SENSOR_FRAME_DURATION).
        const val EXTRA_AF_RECORDING_UNLOCK = "debug.af_recording_unlock"
        const val EXTRA_SESSION_REBUILD_COLOR_HOLD = "debug.session_rebuild_color_hold"
        const val EXTRA_DEVICE_TRUE_WB = "debug.device_true_wb"
        const val EXTRA_NDR_PQ_DECODE = "debug.ndr_pq_decode"
        // NDR10+/NIVUKX warm-red fix: swap HDR CCM HIGH_QUALITY -> FAST at the shared capture.
        const val EXTRA_HDR_CCM_FAST = "debug.hdr_ccm_fast"
        // CP-QV20 cinema focus-pull debug commands.
        const val EXTRA_CINE_FOCUS_PULL = "debug.cine_focus_pull"
        const val EXTRA_FOCUS_PULL_MARK_A = "debug.focus_pull_mark_a"
        const val EXTRA_FOCUS_PULL_MARK_B = "debug.focus_pull_mark_b"
        const val EXTRA_FOCUS_PULL_CURVE = "debug.focus_pull_curve"
        const val EXTRA_FOCUS_PULL_DURATION_MS = "debug.focus_pull_duration_ms"
        const val EXTRA_FOCUS_PULL_TRIGGER = "debug.focus_pull_trigger"
        // s6-f08 Elite-Vision real-pixel measurement enable (matches the flag's documented broadcast).
        const val EXTRA_ELITE_VISION_MEASURE = "debug.elite_vision_measure"
        // CP-QV16/17 device-gated spatial-denoise / optical-sharpen gates (reversible runtime toggle).
        const val EXTRA_SPATIAL_DENOISE = "debug.spatial_denoise"
        const val EXTRA_OPTICAL_SHARPEN = "debug.optical_sharpen"
        // CP24 distant-detail elite gates (reversible runtime toggle, device-gated validation).
        const val EXTRA_DISTANT_DETAIL = "debug.distant_detail"
        // CP30 BT.2390 EETF peak-nits highlight shoulder (reversible runtime toggle, device-gated).
        const val EXTRA_APEX_PEAK_NITS = "debug.apex_peak_nits"
        // CP-CUSP exact Rec.2020 Oklab gamut-cusp clamp (reversible runtime toggle, device-gated).
        const val EXTRA_EXACT_GAMUT_CUSP = "debug.exact_gamut_cusp"
        // Additive per-mode GL preview compositor gate (LOG/RAW live monitor look; reversible).
        const val EXTRA_GL_PREVIEW = "debug.gl_preview"
        // Film-print show-LUT to recorded SDR pixels (offscreen-EGL creative-LUT stage; reversible A/B).
        const val EXTRA_FILM_PRINT_LOOK = "debug.film_print_look"
        // SDR/NDR develop-conversion: honor high VBR bitrate (~286 Mbps) instead of low CQ default (A/B).
        const val EXTRA_DEVELOP_HONORED_BITRATE = "debug.develop_honored_bitrate"
        // SDR/NDR develop-conversion: match the live cinema-camera sharpness/edges/noise look (A/B).
        const val EXTRA_DEVELOP_LIVE_PARITY = "debug.develop_live_parity"
        // SDR/NDR develop-conversion: run the live-parity luma enhance on the GPU (parity-gated; A/B).
        const val EXTRA_DEVELOP_PARITY_ENHANCE_GPU = "debug.record_parity_enhance_gpu"
        // SDR/NDR develop-conversion: run the CPU-only chroma enhance lane on the GPU (parity-gated; A/B).
        const val EXTRA_DEVELOP_ENHANCE_LANE_GPU = "debug.record_enhance_lane_gpu"
        // SDR/NDR develop-conversion: concurrent CPU+GPU develop hybrid (throughput; memory-guarded; A/B).
        const val EXTRA_DEVELOP_HYBRID_CPU_LANE = "debug.record_hybrid_cpu_lane"
        const val EXTRA_DEVELOP_HYBRID_CPU_FORCE = "debug.record_hybrid_cpu_force"
        const val EXTRA_DEVELOP_HYBRID_CPU_SHARE = "debug.record_hybrid_cpu_share"
        // SDR/NDR develop-conversion: pipeline overlap (>1 frame in flight; throughput; guarded; A/B).
        const val EXTRA_DEVELOP_PIPELINE_OVERLAP = "debug.record_pipeline_overlap"
        const val EXTRA_DEVELOP_PARITY_DIAGNOSTICS = "debug.record_parity_diagnostics"
        const val EXTRA_DEVELOP_PARITY_MEAN_TOL = "debug.record_parity_mean_tol"
        const val EXTRA_DEVELOP_PARITY_MAX_TOL = "debug.record_parity_max_tol"
        const val EXTRA_DEVELOP_PARITY_P999_TOL = "debug.record_parity_p999_tol"
        // Keep the GL monitor-grade compositor engaged while recording (fixes gray preview; reversible A/B).
        const val EXTRA_PREVIEW_GRADE_RECORDING = "debug.preview_grade_recording"
        // Full ColorScience creative colour engine -> recorded SDR pixels (reversible A/B).
        const val EXTRA_FULL_COLOR_RECORD = "debug.full_color_record"
        // True preview colour parity (WYSIWYG) — graded preview cube into the GL compositor (reversible A/B).
        const val EXTRA_PREVIEW_COLOR_PARITY = "debug.preview_color_parity"
        // FPS-gate always-save: finalize at achieved fps instead of deleting on a thermal fps miss.
        const val EXTRA_ALWAYS_SAVE_ACHIEVED_FPS = "debug.always_save_achieved_fps"
        // RC2: RAW-video preview viewable tonemap (recorded RAW unaffected; reversible A/B).
        const val EXTRA_RAW_PREVIEW_VIEWABLE_TONEMAP = "debug.raw_preview_viewable_tonemap"
        // RAW debayer-monitor orientation parity (rotate/mirror the 2D debayer present; reversible A/B).
        const val EXTRA_RAW_DEBAYER_ORIENT = "debug.raw_debayer_orient"
        // 10-bit BT.2020-PQ preview compositor gate (WYSIWYG NDR/NIVUKX PQ look; reversible, device-gated).
        const val EXTRA_PQ_PREVIEW = "debug.pq_preview"
        // PQ-preview OUTPUT transfer: display-referred SDR on an 8-bit surface (binds on vivo, fixes the
        // preview red) vs 10-bit BT.2020-PQ (fails to bind on vivo). Default ON. Reversible A/B.
        const val EXTRA_PQ_PREVIEW_SDR_OUT = "debug.pq_preview_sdr_out"
        // Genuine NeoLog HAL tonemap (bakes N-LOG into the LOG camera request; reversible, device-gated).
        const val EXTRA_NEOLOG_HAL = "debug.neolog_hal"
        const val EXTRA_NDR_TRUE_PQ = "debug.ndr_true_pq"
        // NDR10+/NIVUKX capture highlight protection + closed-loop anti-motion-blur shutter cap (A/B).
        const val EXTRA_NDR_HIGHLIGHT_PROTECT = "debug.ndr_highlight_protect"
        const val EXTRA_NDR_ANTI_BLUR = "debug.ndr_anti_blur"
        const val EXTRA_NDR_ZOOM_STABLE_METER = "debug.ndr_zoom_stable_meter"
        const val EXTRA_NDR_ADAPTIVE_HIGHLIGHT = "debug.ndr_adaptive_highlight"
        // RED-channel-aware highlight protection (fixes warm/skin red from red-channel overexposure clip).
        const val EXTRA_NDR_RED_CLIP_HIGHLIGHT = "debug.ndr_red_clip_highlight"
        // Skin-gated warm-red taming (post-corrects the camera-baked warm/skin red cast) + strength.
        const val EXTRA_NDR_WARM_SKIN_RED_TAME = "debug.ndr_warm_skin_red_tame"
        const val EXTRA_NDR_WARM_SKIN_RED_TAME_STRENGTH = "debug.ndr_warm_skin_red_tame_strength"
        // Offline RAW→NeoLog GPU develop (demosaic on GPU + CPU NeoLog finish + first-frame parity gate).
        // Default OFF, device-gated: flip on to validate GPU RAW conversion; CPU fallback always stands.
        const val EXTRA_RAW_GPU_DEVELOP = "debug.raw_gpu_develop"
        const val EXTRA_HOT_PATH_PRIORITY = "debug.hot_path_priority"
        const val EXTRA_RECORD_PIPELINE_DEPTH = "debug.record_pipeline_depth"
        const val EXTRA_ABR_QUALITY_LOCK = "debug.abr_quality_lock"
        const val EXTRA_COALESCE_REQUEST_REBUILD = "debug.coalesce_request_rebuild"
        const val EXTRA_EGL_IMAGE_CACHE = "debug.egl_image_cache"
        const val EXTRA_EGL_DECODE_ONCE = "debug.egl_decode_once"
        const val EXTRA_RECORD_FLIP_X = "debug.record_flip_x"
        const val EXTRA_RECORD_FLIP_Y = "debug.record_flip_y"
        const val EXTRA_RECORD_FPS_CEILING = "debug.record_fps_ceiling"
        const val EXTRA_RECORD_DEVELOP_MODE = "debug.record_develop_mode"
        // Salvage a genuinely-encoded record-develop master at stop instead of abort+delete (default ON).
        const val EXTRA_RECORD_DEVELOP_SALVAGE_ON_STOP = "debug.record_develop_salvage_on_stop"
        // NDR develop SNCE white-normalisation — CAM16 at diffuse white ≈1.0 to tame red boost (default ON).
        const val EXTRA_RECORD_DEVELOP_WHITE_NORMALIZE = "debug.record_develop_white_normalize"
        // Full-sensor constant-25-fps + CFR pin (reversible A/B; default ON).
        const val EXTRA_FULL_SENSOR_CONSTANT_FPS = "debug.full_sensor_constant_fps"
        // Encoder-size device-truth cap: reject sizes the HW encoder can't configure at rate (reversible A/B; default ON).
        const val EXTRA_ENCODER_SIZE_DEVICE_TRUTH_CAP = "debug.encoder_size_device_truth_cap"
        // Offline RAW→LOG grade-safe elite detail (denoise + sharpen + clarity; reversible A/B; default ON).
        const val EXTRA_RAW_LOG_ELITE_DETAIL = "debug.raw_log_elite_detail"
        // Single-worker offline RAW→LOG CPU+GPU overlap (byte-identical; reversible A/B; default ON).
        const val EXTRA_RAW_DEVELOP_OVERLAP = "debug.raw_develop_overlap"
        // Offline SDR/NDR develop enhance multi-core row parallelism (byte-identical; reversible; default ON).
        const val EXTRA_RECORD_ENHANCE_PARALLEL = "debug.record_enhance_parallel"
        const val EXTRA_RECORD_DEVELOP_PUBLISH_COPY_KB = "debug.record_develop_publish_copy_kb"
        // RAW video capture-mode override (MotionCam-style compressed RAW). String enum value:
        // LOSSLESS (default) / LOSSLESS_ENHANCED / HIGH_COMPRESSION (legacy uncompressed) / OFF / DEFAULT.
        const val EXTRA_RAW_COMPRESSION_MODE = "debug.raw_compression_mode"
        // ---- Stability / performance / boot audit (2026-07-15) reversible gates ----
        const val EXTRA_SESSION_SUPPORT_PROBE = "debug.session_support_probe"
        const val EXTRA_OFFSCREEN_ENCODER_BOOT_PROBE_SKIP = "debug.offscreen_encoder_boot_probe_skip"
        const val EXTRA_HARDWARE_DRAIN_SERIALIZE = "debug.hardware_drain_serialize"
        const val EXTRA_AI_TEARDOWN_OFF_MAIN = "debug.ai_teardown_off_main"
        const val EXTRA_ANALYSIS_CHROMA_ONLY_SNAPSHOT = "debug.analysis_chroma_only_snapshot"
        const val EXTRA_AI_ANALYSIS_CONVERT_SKIP = "debug.ai_analysis_convert_skip"
        const val EXTRA_DEFERRED_LUMA_SNAPSHOT = "debug.deferred_luma_snapshot"
        const val EXTRA_RECORD_STOP_SINGLE_REBUILD = "debug.record_stop_single_rebuild"
        const val EXTRA_RECORD_STOP_SUBMIT_QUIESCE = "debug.record_stop_submit_quiesce"
        const val EXTRA_VIDEO_COLD_OPEN_HW_IMPORT_SESSION = "debug.video_cold_open_hw_import_session"
        const val EXTRA_ASYNC_SECURITY_INIT = "debug.async_security_init"
        const val EXTRA_PREFS_TYPED_FAST_PATH = "debug.prefs_typed_fast_path"
        const val EXTRA_SELECT_CAMERA_CHARS_REUSE = "debug.select_camera_chars_reuse"
        private const val DEFAULT_FOCUS_PULL_DURATION_MS = 1_000L
    }
}
