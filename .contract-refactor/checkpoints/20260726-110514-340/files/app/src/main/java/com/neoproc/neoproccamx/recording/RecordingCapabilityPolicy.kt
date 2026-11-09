package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal object RecordingCapabilityPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    data class SessionPlan(
        val codec: GpuVideoCodec,
        val cinemaMode: CinemaVideoMode,
        val tenBitPipelineEnabled: Boolean
    )

    fun resolvePersistedCodecName(value: String?, av1Supported: Boolean): GpuVideoCodec {
        val persisted = RecordingCodecCatalog.resolvePersistedCodecName(value, av1Supported)
        if (strictHighEndEnabled() && persisted == GpuVideoCodec.AVC) {
            return GpuVideoCodec.HEVC
        }
        return if (persisted == GpuVideoCodec.AV1) {
            GpuVideoCodec.HEVC
        } else {
            persisted
        }
    }

    fun resolveRequestedCodec(requested: VideoCodecType, av1Supported: Boolean): GpuVideoCodec =
        RecordingCodecCatalog.resolveRequestedCodec(requested, av1Supported)

    fun toVideoCodecType(codec: GpuVideoCodec): VideoCodecType =
        RecordingCodecCatalog.toVideoCodecType(codec)

    fun resolveTenBitPipelineEnabled(requested: Boolean, codec: GpuVideoCodec): Boolean =
        requested && codec != GpuVideoCodec.AVC

    fun normalizeCinemaMode(
        requestedMode: CinemaVideoMode,
        rawSupported: Boolean
    ): CinemaVideoMode =
        when {
            requestedMode == CinemaVideoMode.RAW && !rawSupported -> CinemaVideoMode.PRO
            else -> requestedMode
        }

    fun deriveCinemaMode(
        rawRequested: Boolean,
        rawSupported: Boolean,
        logEnabled: Boolean
    ): CinemaVideoMode =
        when {
            rawRequested && rawSupported -> CinemaVideoMode.RAW
            logEnabled -> CinemaVideoMode.LOG
            else -> CinemaVideoMode.PRO
        }

    fun resolveRecordingCodec(
        requestedCodec: VideoCodecType,
        av1Supported: Boolean,
        hdrRequested: Boolean,
        rawVideoRequested: Boolean,
        tenBitRequested: Boolean
    ): GpuVideoCodec {
        val resolvedRequestedCodec = resolveRequestedCodec(requestedCodec, av1Supported)
        return when {
            rawVideoRequested -> GpuVideoCodec.HEVC
            hdrRequested || tenBitRequested -> GpuVideoCodec.HEVC
            else -> resolvedRequestedCodec
        }
    }

    fun resolveSessionPlan(
        requestedCodec: VideoCodecType,
        av1Supported: Boolean,
        hdrRequested: Boolean,
        rawRequested: Boolean,
        rawSupported: Boolean,
        logEnabled: Boolean,
        tenBitRequested: Boolean
    ): SessionPlan {
        val resolvedCodec = resolveRecordingCodec(
            requestedCodec = requestedCodec,
            av1Supported = av1Supported,
            hdrRequested = hdrRequested,
            rawVideoRequested = rawRequested && rawSupported,
            tenBitRequested = tenBitRequested
        )
        val strictHighEnd = strictHighEndEnabled()
        val strictForceHevc = strictHighEnd && (
            hdrRequested || tenBitRequested || logEnabled || (rawRequested && rawSupported)
        )
        val codec = if (strictForceHevc) GpuVideoCodec.HEVC else resolvedCodec
        return SessionPlan(
            codec = codec,
            cinemaMode = deriveCinemaMode(
                rawRequested = rawRequested,
                rawSupported = rawSupported,
                logEnabled = logEnabled
            ),
            tenBitPipelineEnabled = resolveTenBitPipelineEnabled(
                requested = tenBitRequested,
                codec = codec
            )
        )
    }
}
