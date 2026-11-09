package com.neoproc.neoproccamx.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.neoproc.neoproccamx.camera.HdrDynamicRangeState
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import com.neoproc.neoproccamx.rendering.NdrInputTransportPolicy
import com.neoproc.neoproccamx.rendering.NdrPeakCapabilityPolicy

/** Resolves NDR monitor delivery and luminance without coupling it to CameraViewModel. */
internal object NdrPreviewRenderPolicy {
    fun persistentRenderState(
        context: Context,
        hdrState: HdrDynamicRangeState,
        owner: PersistentPreviewOwner,
        gradeCode: Int,
        requestedPqMode: Int
    ): PersistentPreviewPreparationCoordinator.RenderState {
        val hdrProfile = NdrInputTransportPolicy.effectivePreviewProfile(
            appliedPreviewProfile = hdrState.previewProfile,
            resolvedProfileForCurrentMode = hdrState.resolvedProfileForCurrentMode,
            sessionConfigured = hdrState.sessionConfigured,
            appliedProfileTruthCurrent = hdrState.appliedProfileTruthCurrent
        )
        val pqMode = requestedPqMode.coerceAtLeast(Hdr10PqPreviewCompositor.MODE_NDR10_PLUS)
        return PersistentPreviewPreparationCoordinator.RenderState(
            owner = owner,
            gradeCode = gradeCode,
            hdrProfile = hdrProfile,
            pqMode = pqMode,
            peakPlan = peakPlan(context, pqMode, hdrProfile)
        )
    }

    fun deliveryMode(
        uiMode: AppMode,
        logEnabled: Boolean,
        rawVideoActive: Boolean,
        videoHdrMode: String
    ): Int {
        if (uiMode != AppMode.VIDEO || logEnabled || rawVideoActive) return -1
        return when (RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(videoHdrMode)) {
            "NDR10+" -> Hdr10PqPreviewCompositor.MODE_NDR10_PLUS
            "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" ->
                Hdr10PqPreviewCompositor.MODE_NIVUKX_VISION
            else -> -1
        }
    }

    fun peakPlan(
        context: Context,
        modeCode: Int,
        appliedProfile: Long?
    ): NdrPeakCapabilityPolicy.Plan {
        val displayPeak = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.hdrCapabilities
                    ?.desiredMaxLuminance
                    ?.takeIf { it.isFinite() && it > 0f }
            }.getOrNull()
        } else {
            null
        }
        return NdrPeakCapabilityPolicy.resolve(
            mode = NdrPeakCapabilityPolicy.modeForCode(modeCode),
            displayMaxLuminanceNits = displayPeak,
            requestedContentPeakNits = displayPeak,
            encoderPeakCapabilityNits = if (
                NdrInputTransportPolicy.isSupportedNdrInputProfile(appliedProfile)
            ) {
                NdrPeakCapabilityPolicy.MAX_NDR_PEAK_NITS
            } else {
                null
            }
        )
    }
}
