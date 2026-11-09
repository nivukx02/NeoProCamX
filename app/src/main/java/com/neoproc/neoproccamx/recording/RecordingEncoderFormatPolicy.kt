package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build

internal data class RecordingEncoderFormatRequest(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val iFrameIntervalSeconds: Float,
    val colorEncoding: RecordingColorEncodingConfig,
    val bitrateMode: Int?,
    val encoderComplexity: Int?,
    val operatingRate: Int?,
    val profile: Int?,
    val level: Int? = null,
    val qualityTier: RecordingCodecCatalog.QualityTier = RecordingCodecCatalog.QualityTier.STANDARD,
    val advancedQualityTuningEnabled: Boolean = true,
    /**
     * Makes the requested bitrate the encoder's controlling quality constraint.
     *
     * Realtime rate-controlled delivery never carries a fixed quality value or a competing QP
     * window. The exact-profile capability probe sets the bitrate ceiling; one rate controller then
     * owns output size and cadence. Latency, frame-reordering and cadence controls stay active.
     */
    val strictBitrateTarget: Boolean = false,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val outputColorEncoding: RecordingColorEncodingConfig = colorEncoding,
    val hdr10PlusRequested: Boolean = false,
    val certifiedVendorFallbackRequested: Boolean = false,
    val inputColorFormat: Int? = null,
    val inputMaxSizeBytes: Int? = null,
    val hdrSceneMetadata: HdrStaticMetadataPolicy.SceneMetadata = HdrStaticMetadataPolicy.SceneMetadata.DEFAULT
)

internal data class RecordingEncoderFormatResult(
    val format: MediaFormat,
    val spec: RecordingEncoderFormatSpec,
    val staticMetadataMode: HdrStaticMetadataPolicy.StaticMetadataMode,
    val staticMetadataAttached: Boolean,
    val masteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay?
)

internal data class RecordingEncoderFormatSpec(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val iFrameIntervalSeconds: Float,
    val colorFormat: Int,
    val maxBitrate: Int,
    val quality: Int?,
    val bitrateMode: Int?,
    val encoderComplexity: Int?,
    val operatingRate: Int?,
    val profile: Int?,
    val level: Int?,
    val inputMaxSizeBytes: Int?,
    val priority: Int?,
    val latencyFrames: Int?,
    val allowFrameDrop: Int?,
    val colorStandard: Int?,
    val colorTransfer: Int?,
    val colorTransferRequest: Int?,
    val colorRange: Int?,
    val qualityTuning: RecordingEncoderQualityTuning?
)

internal data class RecordingEncoderQualityTuning(
    val qpMin: Int?,
    val qpMax: Int?,
    val iFrameQpMin: Int?,
    val iFrameQpMax: Int?,
    val pFrameQpMin: Int?,
    val pFrameQpMax: Int?,
    val bFrameQpMin: Int?,
    val bFrameQpMax: Int?,
    val maxBFrames: Int?,
    val lowLatency: Int?,
    val latencyFrames: Int?,
    val intraRefreshPeriodFrames: Int?
)

internal data class RecordingAllIntraApplyResult(
    val active: Boolean,
    val outputBitrate: Int,
    val iFrameIntervalSeconds: Float,
    val qualityTierLabel: String?,
    val appliedConfig: AllIntraHevcPolicy.AllIntraConfig?,
    val tenBit: Boolean
)

internal object RecordingEncoderFormatPolicy {
    /**
     * Wire key behind [MediaFormat.KEY_ALLOW_FRAME_DROP] (API 31). Keep the literal local so the
     * min-29 artifact never references a newer framework field; writes remain guarded by SDK >= S.
     */
    private const val MEDIA_FORMAT_KEY_ALLOW_FRAME_DROP = "allow-frame-drop"

    fun buildVideoFormat(request: RecordingEncoderFormatRequest): RecordingEncoderFormatResult {
        var staticMetadataMode = HdrStaticMetadataPolicy.StaticMetadataMode.SDR
        var staticMetadataAttached = false
        var masteringDisplay: HdrStaticMetadataPolicy.MasteringDisplay? = null
        val colorFormat =
            request.inputColorFormat ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val cadencePlan = RecordingEncoderCadencePolicy.resolve(
            requestedIFrameIntervalSeconds = request.iFrameIntervalSeconds,
            sdkInt = request.sdkInt,
            surfaceInput = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        val encoderComplexity =
            if (request.sdkInt >= Build.VERSION_CODES.LOLLIPOP) {
                request.encoderComplexity
            } else {
                null
            }
        val priority = if (request.sdkInt >= Build.VERSION_CODES.M) 0 else null
        val colorStandard =
            if (request.sdkInt >= Build.VERSION_CODES.N) request.colorEncoding.colorStandard else null
        val colorTransfer =
            if (request.sdkInt >= Build.VERSION_CODES.N) request.colorEncoding.colorTransfer else null
        val colorTransferRequest =
            if (request.sdkInt >= Build.VERSION_CODES.S) request.colorEncoding.colorTransfer else null
        val colorRange =
            if (request.sdkInt >= Build.VERSION_CODES.N) request.colorEncoding.colorRange else null
        val encoderQuality = if (request.strictBitrateTarget) null else 100
        val qualityTuning = resolveQualityTuning(request, cadencePlan)
        val spec = RecordingEncoderFormatSpec(
            mime = request.mime,
            width = request.width,
            height = request.height,
            fps = request.fps,
            bitrate = request.bitrate,
            iFrameIntervalSeconds = cadencePlan.iFrameIntervalSeconds,
            colorFormat = colorFormat,
            maxBitrate = request.bitrate,
            quality = encoderQuality,
            bitrateMode = if (request.sdkInt >= Build.VERSION_CODES.LOLLIPOP) request.bitrateMode else null,
            encoderComplexity = encoderComplexity,
            operatingRate = request.operatingRate,
            profile = request.profile,
            level = if (request.sdkInt >= Build.VERSION_CODES.M) request.level else null,
            inputMaxSizeBytes = request.inputMaxSizeBytes,
            priority = priority,
            latencyFrames = qualityTuning?.latencyFrames,
            allowFrameDrop = cadencePlan.allowFrameDrop,
            colorStandard = colorStandard,
            colorTransfer = colorTransfer,
            colorTransferRequest = colorTransferRequest,
            colorRange = colorRange,
            qualityTuning = qualityTuning
        )
        val format = createVideoFormat(request.mime, request.width, request.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, request.fps)
            applyIFrameInterval(
                format = this,
                intervalSeconds = cadencePlan.iFrameIntervalSeconds,
                sdkInt = request.sdkInt
            )
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger("max-bitrate", request.bitrate)
            request.inputMaxSizeBytes?.let {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, it)
            }
            encoderQuality?.let { quality ->
                if (request.sdkInt >= Build.VERSION_CODES.P) {
                    setInteger(MediaFormat.KEY_QUALITY, quality)
                } else {
                    setInteger("quality", quality)
                }
            }

            if (request.sdkInt >= Build.VERSION_CODES.LOLLIPOP) {
                request.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
                encoderComplexity?.let { setInteger(MediaFormat.KEY_COMPLEXITY, it) }
            }
            request.operatingRate?.let { setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
            request.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            if (request.sdkInt >= Build.VERSION_CODES.M) {
                request.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            }
            if (request.sdkInt >= Build.VERSION_CODES.M) {
                priority?.let { setInteger(MediaFormat.KEY_PRIORITY, it) }
            }
            if (request.sdkInt >= Build.VERSION_CODES.S) {
                cadencePlan.allowFrameDrop?.let {
                    setInteger(MEDIA_FORMAT_KEY_ALLOW_FRAME_DROP, it)
                }
            }
            if (request.sdkInt >= Build.VERSION_CODES.N) {
                colorStandard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
                colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
                if (request.sdkInt >= Build.VERSION_CODES.S) {
                    colorTransferRequest?.let {
                        setInteger(MEDIA_FORMAT_KEY_COLOR_TRANSFER_REQUEST, it)
                    }
                }
                colorRange?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
                staticMetadataMode = HdrStaticMetadataPolicy.StaticMetadataMode.fromColorEncoding(
                    colorTransfer = request.outputColorEncoding.colorTransfer,
                    colorStandard = request.outputColorEncoding.colorStandard,
                    hdr10PlusRequested = request.hdr10PlusRequested,
                    certifiedVendorFallbackRequested = request.certifiedVendorFallbackRequested
                )
                masteringDisplay = if (
                    staticMetadataMode == HdrStaticMetadataPolicy.StaticMetadataMode.HDR10 ||
                    staticMetadataMode == HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS ||
                    staticMetadataMode == HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK
                ) {
                    HdrStaticMetadataPolicy.resolveMasteringDisplay(
                        mode = staticMetadataMode,
                        sceneMetadata = request.hdrSceneMetadata
                    )
                } else {
                    null
                }
                staticMetadataAttached = HdrStaticMetadataPolicy.applyToFormat(
                    format = this,
                    mode = staticMetadataMode,
                    masteringDisplay = masteringDisplay
                        ?: HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
                )
            }
            qualityTuning?.let { applyQualityTuning(it, request.sdkInt) }
        }
        return RecordingEncoderFormatResult(
            format = format,
            spec = spec,
            staticMetadataMode = staticMetadataMode,
            staticMetadataAttached = staticMetadataAttached,
            masteringDisplay = masteringDisplay
        )
    }

    fun compatibilityRetryRequest(
        request: RecordingEncoderFormatRequest
    ): RecordingEncoderFormatRequest? =
        if (request.advancedQualityTuningEnabled) {
            request.copy(advancedQualityTuningEnabled = false)
        } else {
            null
        }

    private fun resolveQualityTuning(
        request: RecordingEncoderFormatRequest,
        cadencePlan: RecordingEncoderCadencePolicy.Plan
    ): RecordingEncoderQualityTuning? {
        if (!request.advancedQualityTuningEnabled) return null

        val qpValues = if (
            RecordingRealtimeBitrateContractPolicy.permitsIndependentQuantizerWindow(
                strictBitrateTarget = request.strictBitrateTarget
            ) &&
            request.sdkInt >= Build.VERSION_CODES.S &&
            request.supportsKnownQpScale()
        ) {
            val hdrOrTenBit =
                request.colorEncoding.requiresTenBit ||
                    request.colorEncoding.colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO ||
                    request.colorEncoding.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
            val baseMaxQp = when (request.qualityTier) {
                RecordingCodecCatalog.QualityTier.CINEMA -> 6
                RecordingCodecCatalog.QualityTier.PROFESSIONAL -> if (hdrOrTenBit) 20 else 22
                RecordingCodecCatalog.QualityTier.STANDARD -> if (hdrOrTenBit) 26 else 28
            }
            val maxQp = resolveRealtimeQpCeiling(
                request = request,
                baseMaxQp = baseMaxQp,
                hdrOrTenBit = hdrOrTenBit
            )
            val strictCinemaQp = request.qualityTier == RecordingCodecCatalog.QualityTier.CINEMA
            QuantizerWindow(
                qpMin = 0,
                qpMax = maxQp,
                iFrameQpMin = 0,
                iFrameQpMax = if (strictCinemaQp) maxQp else (maxQp - 4).coerceAtLeast(10),
                pFrameQpMin = 1,
                pFrameQpMax = if (strictCinemaQp) maxQp else (maxQp - 2).coerceAtLeast(12),
                bFrameQpMin = 2,
                bFrameQpMax = maxQp
            )
        } else {
            null
        }

        // Realtime camera input surfaces must not build a reorder queue in the
        // encoder. B-frame buffering can backpressure vkQueuePresentKHR for
        // hundreds of milliseconds on Android MediaCodec surfaces, which drops
        // actual capture cadence even when metadata still advertises 30 fps.
        val maxBFrames = if (
            request.sdkInt >= Build.VERSION_CODES.Q &&
            request.supportsFrameReordering()
        ) {
            0
        } else {
            null
        }
        val lowLatency =
            if (request.sdkInt >= Build.VERSION_CODES.R) 1 else null
        val latencyFrames =
            if (request.sdkInt >= Build.VERSION_CODES.O) 1 else null
        val intraRefreshPeriodFrames = cadencePlan.intraRefreshPeriodFrames

        if (
            qpValues == null &&
            maxBFrames == null &&
            lowLatency == null &&
            latencyFrames == null &&
            intraRefreshPeriodFrames == null
        ) return null
        return RecordingEncoderQualityTuning(
            qpMin = qpValues?.qpMin,
            qpMax = qpValues?.qpMax,
            iFrameQpMin = qpValues?.iFrameQpMin,
            iFrameQpMax = qpValues?.iFrameQpMax,
            pFrameQpMin = qpValues?.pFrameQpMin,
            pFrameQpMax = qpValues?.pFrameQpMax,
            bFrameQpMin = qpValues?.bFrameQpMin,
            bFrameQpMax = qpValues?.bFrameQpMax,
            maxBFrames = maxBFrames,
            lowLatency = lowLatency,
            latencyFrames = latencyFrames,
            intraRefreshPeriodFrames = intraRefreshPeriodFrames
        )
    }

    private fun resolveRealtimeQpCeiling(
        request: RecordingEncoderFormatRequest,
        baseMaxQp: Int,
        hdrOrTenBit: Boolean
    ): Int {
        if (request.qualityTier != RecordingCodecCatalog.QualityTier.CINEMA) return baseMaxQp
        val samplesPerSecond = request.width.toLong() *
            request.height.toLong() *
            request.fps.coerceAtLeast(1).toLong()
        if (samplesPerSecond <= 0L) return baseMaxQp

        val bitsPerSample = request.bitrate.toDouble() / samplesPerSecond.toDouble()
        val realtimeFloor = when {
            bitsPerSample >= 2.25 -> baseMaxQp
            bitsPerSample >= 1.50 -> 8
            bitsPerSample >= 1.00 -> 10
            bitsPerSample >= 0.65 -> 12
            else -> if (hdrOrTenBit) 16 else 14
        }
        return maxOf(baseMaxQp, realtimeFloor)
    }

    fun resolveAllIntraResult(
        requested: Boolean,
        mime: String,
        fps: Int,
        videoPlanUseTenBit: Boolean,
        probed: AllIntraHevcPolicy.AllIntraConfig?,
        clampedBitrate: Int,
        applyResult: Boolean
    ): RecordingAllIntraApplyResult {
        val active = requested &&
            mime.equals("video/hevc", ignoreCase = true) &&
            probed != null &&
            applyResult
        val appliedConfig = if (active) {
            probed.copy(bitrate = clampedBitrate)
        } else {
            null
        }
        return RecordingAllIntraApplyResult(
            active = active,
            outputBitrate = clampedBitrate,
            iFrameIntervalSeconds = 1f / fps.coerceAtLeast(1).toFloat(),
            qualityTierLabel = if (active) "ALL_INTRA" else null,
            appliedConfig = appliedConfig,
            tenBit = active && videoPlanUseTenBit
        )
    }

    @SuppressLint("InlinedApi")
    private fun MediaFormat.applyQualityTuning(
        tuning: RecordingEncoderQualityTuning,
        sdkInt: Int
    ) {
        if (sdkInt >= Build.VERSION_CODES.S) {
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_MIN, tuning.qpMin)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_MAX, tuning.qpMax)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_I_MIN, tuning.iFrameQpMin)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_I_MAX, tuning.iFrameQpMax)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_P_MIN, tuning.pFrameQpMin)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_P_MAX, tuning.pFrameQpMax)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_B_MIN, tuning.bFrameQpMin)
            setIntegerIfPresent(MediaFormat.KEY_VIDEO_QP_B_MAX, tuning.bFrameQpMax)
        }
        if (sdkInt >= Build.VERSION_CODES.Q) {
            setIntegerIfPresent(MediaFormat.KEY_MAX_B_FRAMES, tuning.maxBFrames)
        }
        if (sdkInt >= Build.VERSION_CODES.R) {
            setIntegerIfPresent(MediaFormat.KEY_LOW_LATENCY, tuning.lowLatency)
        }
        if (sdkInt >= Build.VERSION_CODES.O) {
            // MediaFormat.KEY_LATENCY is surfaced as "latency" in codec output logs on several
            // Qualcomm/vivo encoders. Request one-frame pipeline latency so the MediaCodec input
            // surface does not build a deep queue that blocks eglSwapBuffers for hundreds of ms.
            setIntegerIfPresent("latency", tuning.latencyFrames)
        }
        if (sdkInt >= Build.VERSION_CODES.N) {
            setIntegerIfPresent(MediaFormat.KEY_INTRA_REFRESH_PERIOD, tuning.intraRefreshPeriodFrames)
        }
    }

    private fun MediaFormat.setIntegerIfPresent(key: String, value: Int?) {
        if (value != null) setInteger(key, value)
    }

    private fun RecordingEncoderFormatRequest.supportsKnownQpScale(): Boolean =
        mime.equals("video/hevc", ignoreCase = true) ||
            mime.equals("video/avc", ignoreCase = true) ||
            mime.equals("video/av01", ignoreCase = true)

    private fun RecordingEncoderFormatRequest.supportsFrameReordering(): Boolean =
        mime.equals("video/hevc", ignoreCase = true) ||
            mime.equals("video/avc", ignoreCase = true)

    private data class QuantizerWindow(
        val qpMin: Int,
        val qpMax: Int,
        val iFrameQpMin: Int,
        val iFrameQpMax: Int,
        val pFrameQpMin: Int,
        val pFrameQpMax: Int,
        val bFrameQpMin: Int,
        val bFrameQpMax: Int
    )

    private fun applyIFrameInterval(
        format: MediaFormat,
        intervalSeconds: Float,
        sdkInt: Int
    ) {
        if (sdkInt >= Build.VERSION_CODES.N_MR1) {
            format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, intervalSeconds)
        } else {
            format.setInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
                intervalSeconds.toInt().coerceAtLeast(0)
            )
        }
    }

    @Suppress("USELESS_ELVIS")
    private fun createVideoFormat(mime: String, width: Int, height: Int): MediaFormat =
        // Host Android stubs can return null even though the platform type is non-null.
        MediaFormat.createVideoFormat(mime, width, height) ?: MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
        }
}
