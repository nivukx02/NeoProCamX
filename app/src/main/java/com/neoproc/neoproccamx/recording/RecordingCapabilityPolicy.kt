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

    enum class CinemaModeRejectionReason {
        NONE,
        RAW_CAPTURE_UNSUPPORTED
    }

    data class CinemaModeAdmission(
        val requestedMode: CinemaVideoMode,
        val accepted: Boolean,
        val rejectionReason: CinemaModeRejectionReason
    ) {
        fun requireAccepted(): CinemaVideoMode {
            require(accepted) {
                when (rejectionReason) {
                    CinemaModeRejectionReason.RAW_CAPTURE_UNSUPPORTED ->
                        "RAW mode requires native RAW capture support; mode identity was not downgraded"
                    CinemaModeRejectionReason.NONE ->
                        "Cinema mode admission was rejected without a reason"
                }
            }
            return requestedMode
        }
    }

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

    fun resolveCinemaModeAdmission(
        requestedMode: CinemaVideoMode,
        rawSupported: Boolean
    ): CinemaModeAdmission {
        val rawRejected = requestedMode == CinemaVideoMode.RAW && !rawSupported
        return CinemaModeAdmission(
            requestedMode = requestedMode,
            accepted = !rawRejected,
            rejectionReason = if (rawRejected) {
                CinemaModeRejectionReason.RAW_CAPTURE_UNSUPPORTED
            } else {
                CinemaModeRejectionReason.NONE
            }
        )
    }

    fun normalizeCinemaMode(
        requestedMode: CinemaVideoMode,
        rawSupported: Boolean
    ): CinemaVideoMode =
        resolveCinemaModeAdmission(
            requestedMode = requestedMode,
            rawSupported = rawSupported
        ).requireAccepted()

    fun deriveCinemaMode(
        rawRequested: Boolean,
        rawSupported: Boolean,
        logEnabled: Boolean
    ): CinemaVideoMode {
        val requestedMode = when {
            rawRequested -> CinemaVideoMode.RAW
            logEnabled -> CinemaVideoMode.LOG
            else -> CinemaVideoMode.PRO
        }
        return normalizeCinemaMode(
            requestedMode = requestedMode,
            rawSupported = rawSupported
        )
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
        val cinemaMode = deriveCinemaMode(
            rawRequested = rawRequested,
            rawSupported = rawSupported,
            logEnabled = logEnabled
        )
        val resolvedCodec = resolveRecordingCodec(
            requestedCodec = requestedCodec,
            av1Supported = av1Supported,
            hdrRequested = hdrRequested,
            rawVideoRequested = cinemaMode == CinemaVideoMode.RAW,
            tenBitRequested = tenBitRequested
        )
        val strictHighEnd = strictHighEndEnabled()
        // The strict product contract is Main10 in every selectable video mode, including normal
        // PRO/SDR. Never let a stale preference with tenBitRequested=false reopen the AVC/8-bit path.
        val strictForceHevc = strictHighEnd
        val codec = if (strictForceHevc) GpuVideoCodec.HEVC else resolvedCodec
        return SessionPlan(
            codec = codec,
            cinemaMode = cinemaMode,
            tenBitPipelineEnabled = resolveTenBitPipelineEnabled(
                requested = tenBitRequested || strictHighEnd,
                codec = codec
            )
        )
    }
}
