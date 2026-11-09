package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import com.neoproc.neoproccamx.recording.RecordingCapabilityPolicy
import com.neoproc.neoproccamx.recording.RecordingCodecCatalog
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy
import com.neoproc.neoproccamx.recording.VideoCodecType

/** Resolves whether the visible video monitor may safely use the HDR presentation path. */
internal object SafeVideoHdrPreviewPolicy {
    fun isActive(
        eligibleVideoPreview: Boolean,
        requestedCodec: VideoCodecType,
        av1Supported: Boolean,
        parityPlan: RecordingPipelineParityPlan,
        effectiveHdrMode: String,
        simulatedTenBitFallback: Boolean
    ): Boolean = isActive(
        eligibleVideoPreview = eligibleVideoPreview,
        requestedCodec = requestedCodec,
        av1Supported = av1Supported,
        hdrRequested = parityPlan.hdrRequestedForSessionPlan,
        rawVideoRequested = parityPlan.rawRequestedForSessionPlan,
        tenBitRequested = parityPlan.tenBitRequestedForSessionPlan,
        effectiveHdrMode = effectiveHdrMode,
        encoderColorSpace = parityPlan.encoderColorSpace,
        simulatedTenBitFallback = simulatedTenBitFallback
    )

    /**
     * Request-bound overload for render work. The mode contract is already part of the immutable
     * delivery snapshot, so using it here avoids rebuilding parity from mutable live ViewModel state
     * while an older render request is still being resolved.
     */
    fun isActive(
        eligibleVideoPreview: Boolean,
        requestedCodec: VideoCodecType,
        av1Supported: Boolean,
        pipelineContract: VideoModePipelineContract,
        effectiveHdrMode: String,
        simulatedTenBitFallback: Boolean
    ): Boolean = isActive(
        eligibleVideoPreview = eligibleVideoPreview,
        requestedCodec = requestedCodec,
        av1Supported = av1Supported,
        hdrRequested = pipelineContract.hdrToneMapActive,
        rawVideoRequested = pipelineContract.rawSensorOriginal,
        tenBitRequested = pipelineContract.tenBitTransportRequested,
        effectiveHdrMode = effectiveHdrMode,
        encoderColorSpace = pipelineContract.effectiveColorSpace,
        simulatedTenBitFallback = simulatedTenBitFallback
    )

    private fun isActive(
        eligibleVideoPreview: Boolean,
        requestedCodec: VideoCodecType,
        av1Supported: Boolean,
        hdrRequested: Boolean,
        rawVideoRequested: Boolean,
        tenBitRequested: Boolean,
        effectiveHdrMode: String,
        encoderColorSpace: String,
        simulatedTenBitFallback: Boolean
    ): Boolean {
        if (!eligibleVideoPreview || simulatedTenBitFallback) return false
        val codec = RecordingCapabilityPolicy.resolveRecordingCodec(
            requestedCodec = requestedCodec,
            av1Supported = av1Supported,
            hdrRequested = hdrRequested,
            rawVideoRequested = rawVideoRequested,
            tenBitRequested = tenBitRequested
        )
        return RecordingHdrVideoLookPolicy.resolveLookProfile(
            mime = RecordingCodecCatalog.mime(codec),
            videoHdrMode = effectiveHdrMode,
            videoColorSpace = encoderColorSpace
        ).colorEncoding.colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    }
}
