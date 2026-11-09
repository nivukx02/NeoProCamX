package com.neoproc.neoproccamx.ui

import android.content.Intent
import android.util.Log
import com.neoproc.neoproccamx.camera.AfRecordingUnlockFlag
import com.neoproc.neoproccamx.camera.CoalesceRequestRebuildFlag
import com.neoproc.neoproccamx.camera.ColorDriftEnforcementFlag
import com.neoproc.neoproccamx.camera.DeviceTrueWhiteBalanceFlag
import com.neoproc.neoproccamx.camera.MeteringDampingFlag
import com.neoproc.neoproccamx.camera.NdrAdaptiveHighlightFlag
import com.neoproc.neoproccamx.camera.NdrAntiMotionBlurFlag
import com.neoproc.neoproccamx.camera.NdrHighlightProtectFlag
import com.neoproc.neoproccamx.camera.NdrZoomStableMeteringFlag
import com.neoproc.neoproccamx.camera.NeoLogHalTonemapPolicy
import com.neoproc.neoproccamx.camera.RawPreviewViewableTonemapFlag
import com.neoproc.neoproccamx.camera.SessionRebuildColorHoldFlag
import com.neoproc.neoproccamx.core.HotPathScheduling
import com.neoproc.neoproccamx.pipeline.RecordPipelineDepthFlag
import com.neoproc.neoproccamx.recording.AbrQualityLockFlag
import com.neoproc.neoproccamx.recording.EliteVisionMeasurementFlag
import com.neoproc.neoproccamx.recording.NeoNdrTruePqTransportPolicy
import com.neoproc.neoproccamx.recording.RawDevelopGpuAccelerator
import com.neoproc.neoproccamx.rendering.AlwaysSaveAtAchievedFpsFlag
import com.neoproc.neoproccamx.rendering.ApexPeakNitsFlag
import com.neoproc.neoproccamx.rendering.DenoiseSpatialFlag
import com.neoproc.neoproccamx.rendering.DistantDetailGatesFlag
import com.neoproc.neoproccamx.rendering.EglDecodeOnceFlag
import com.neoproc.neoproccamx.rendering.EglImageCacheFlag
import com.neoproc.neoproccamx.rendering.ExactGamutCuspFlag
import com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag
import com.neoproc.neoproccamx.rendering.FullColorEngineRecordFlag
import com.neoproc.neoproccamx.rendering.GlVisiblePreviewPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewPolicy
import com.neoproc.neoproccamx.rendering.PreviewColorParityFlag
import com.neoproc.neoproccamx.rendering.PreviewGradeWhileRecordingFlag
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.rendering.RecordOutputMirrorFlag
import com.neoproc.neoproccamx.rendering.SharpenOpticalFlag

internal object DebugRuntimeFlagApplier {
    private const val TAG = "DebugRuntimeFlagApplier"

    fun apply(intent: Intent) {
        applyBoolean(intent, DebugCommandReceiver.EXTRA_COLOR_DRIFT_ENFORCE, false) {
            ColorDriftEnforcementFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_METERING_DAMPING, true) {
            MeteringDampingFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_AF_RECORDING_UNLOCK, true) {
            AfRecordingUnlockFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_SESSION_REBUILD_COLOR_HOLD, false) {
            SessionRebuildColorHoldFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_DEVICE_TRUE_WB, true) {
            DeviceTrueWhiteBalanceFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_ELITE_VISION_MEASURE, false) {
            EliteVisionMeasurementFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_SPATIAL_DENOISE, false) {
            DenoiseSpatialFlag.enabled = it
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_OPTICAL_SHARPEN, false) {
            SharpenOpticalFlag.enabled = it
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_DISTANT_DETAIL, false) {
            DistantDetailGatesFlag.enabled = it
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_APEX_PEAK_NITS, false) {
            ApexPeakNitsFlag.enabled = it
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_EXACT_GAMUT_CUSP, false) {
            ExactGamutCuspFlag.enabled = it
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_GL_PREVIEW, false) {
            GlVisiblePreviewPolicy.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_FILM_PRINT_LOOK, false) {
            FilmPrintLookRecordFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_PREVIEW_GRADE_RECORDING, false) {
            PreviewGradeWhileRecordingFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_FULL_COLOR_RECORD, false) {
            FullColorEngineRecordFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_PREVIEW_COLOR_PARITY, false) {
            PreviewColorParityFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_ALWAYS_SAVE_ACHIEVED_FPS, false) {
            AlwaysSaveAtAchievedFpsFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RAW_PREVIEW_VIEWABLE_TONEMAP, false) {
            RawPreviewViewableTonemapFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RAW_DEBAYER_ORIENT, false) {
            com.neoproc.neoproccamx.rendering.RawDebayerPreviewOrientationParityFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_PQ_PREVIEW, false) {
            Hdr10PqPreviewPolicy.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NEOLOG_HAL, true) {
            NeoLogHalTonemapPolicy.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NDR_TRUE_PQ, true) {
            NeoNdrTruePqTransportPolicy.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NDR_HIGHLIGHT_PROTECT, true) {
            NdrHighlightProtectFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NDR_ANTI_BLUR, true) {
            NdrAntiMotionBlurFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NDR_ZOOM_STABLE_METER, true) {
            NdrZoomStableMeteringFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_NDR_ADAPTIVE_HIGHLIGHT, true) {
            NdrAdaptiveHighlightFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RAW_GPU_DEVELOP, false) {
            RawDevelopGpuAccelerator.setDevelopGpuEnabled(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_HOT_PATH_PRIORITY, true) {
            HotPathScheduling.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RECORD_PIPELINE_DEPTH, true) {
            RecordPipelineDepthFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_ABR_QUALITY_LOCK, true) {
            AbrQualityLockFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_COALESCE_REQUEST_REBUILD, true) {
            CoalesceRequestRebuildFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_EGL_IMAGE_CACHE, false) {
            EglImageCacheFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_EGL_DECODE_ONCE, false) {
            EglDecodeOnceFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RECORD_FLIP_X, false) {
            RecordOutputMirrorFlag.setFlipX(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RECORD_FLIP_Y, true) {
            RecordOutputMirrorFlag.setFlipY(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RECORD_FPS_CEILING, true) {
            RecordFpsCeilingFlag.set(it)
        }
        applyBoolean(intent, DebugCommandReceiver.EXTRA_RECORD_DEVELOP_MODE, false) {
            RecordDevelopModeFlag.set(it)
        }
    }

    private inline fun applyBoolean(
        intent: Intent,
        key: String,
        defaultValue: Boolean,
        set: (Boolean) -> Unit
    ) {
        if (!intent.hasExtra(key)) return
        val enabled = intent.getBooleanExtra(key, defaultValue)
        set(enabled)
        Log.i(TAG, "$key set to $enabled via debug intent")
    }
}
