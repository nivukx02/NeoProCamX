package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.nio.ByteBuffer

/**
 * Device-only HEVC Main10 MOV encoder for record-develop delivery files.
 *
 * Input is tightly-packed 10-bit P010 (Y plane followed by interleaved UV plane, 10 bits MSB-justified in
 * little-endian 16-bit words), matching the app's live P010 byte-buffer encoder contract.
 */
internal class RecordDevelopHevcMovEncoder(
    private val writer: QuickTimeMovFileWriter,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val colorConfig: QuickTimeColorAtomConfig,
    private val deliveryFormat: RecordDevelopDeliveryFormat
) : AutoCloseable {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val codec: MediaCodec
    private val elitePlan: RecordDevelopEncoderElitePlan
    private val advancedQualityAccepted: Boolean
    private var trackIndex = -1
    private var outputDone = false
    private var started = false
    private var encodedFrames = 0L
    private var inputFrames = 0L
    private var lastInputPtsUs: Long? = null

    init {
        require(deliveryFormat == RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV) {
            "HEVC 10-bit 4:2:2 MOV requires a real P210/Y210 MediaCodec input path; " +
                "this device build exposes only strict Main10 P010 4:2:0 delivery. " +
                "Use V210_422_UNCOMPRESSED_MOV for true 10-bit 4:2:2 without chroma downgrade."
        }
        require(width > 0 && height > 0 && fps > 0) {
            "Invalid HEVC record-develop geometry ${width}x${height}@${fps}"
        }
        val candidate = chooseMain10P010Encoder(width, height, fps)
            ?: error("No HEVC Main10 P010 encoder available for ${width}x${height}@${fps}")
        elitePlan = resolveElitePlan(candidate)
        val eliteFormat = buildFormat(candidate, elitePlan, advancedQuality = true)
        val eliteAttempt = runCatching { createStartedCodec(candidate.name, eliteFormat) }
        if (eliteAttempt.isSuccess) {
            codec = eliteAttempt.getOrThrow()
            advancedQualityAccepted = true
        } else {
            val eliteFailure = eliteAttempt.exceptionOrNull()
                ?: IllegalStateException("Unknown elite encoder configuration failure")
            NeoLogger.w(
                TAG,
                "Encoder rejected optional elite controls; retaining strict Main10/P010 + elite bitrate " +
                    "with compatibility format codec=${candidate.name}: ${eliteFailure.message}"
            )
            val compatibleFormat = buildFormat(candidate, elitePlan, advancedQuality = false)
            codec = try {
                createStartedCodec(candidate.name, compatibleFormat)
            } catch (fallbackFailure: Throwable) {
                fallbackFailure.addSuppressed(eliteFailure)
                throw fallbackFailure
            }
            advancedQualityAccepted = false
        }
        started = true
        NeoLogger.i(
            TAG,
            "Record-develop HEVC encoder started codec=${candidate.name} " +
                "geometry=${width}x${height}@${fps} requestedBitrate=${elitePlan.requestedBitrate} " +
                "targetBitrate=${elitePlan.targetBitrate} quality=${elitePlan.quality} " +
                "complexity=${elitePlan.complexity} qpMax=${elitePlan.maximumQp} " +
                "advancedAccepted=$advancedQualityAccepted"
        )
    }

    fun encodeFrame(p010: ByteArray, presentationTimeUs: Long) {
        check(!outputDone) { "HEVC encoder already ended" }
        RecordDevelopDecoderQualityPolicy.requireMonotonicPts(lastInputPtsUs, presentationTimeUs)
        val expectedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        require(p010.size == expectedBytes) {
            "Developed P010 payload size mismatch size=${p010.size} expected=$expectedBytes"
        }
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex)
                    ?: error("HEVC encoder input buffer unavailable")
                input.clear()
                require(input.capacity() >= p010.size) {
                    "HEVC encoder input buffer too small capacity=${input.capacity()} payload=${p010.size}"
                }
                input.put(p010)
                codec.queueInputBuffer(inputIndex, 0, p010.size, presentationTimeUs, 0)
                lastInputPtsUs = presentationTimeUs
                inputFrames += 1L
                drain(endOfStream = false)
                return
            }
            drain(endOfStream = false)
        }
    }

    fun finish(endPresentationTimeUs: Long): Long {
        if (outputDone) return encodedFrames
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    endPresentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                break
            }
            drain(endOfStream = false)
        }
        drain(endOfStream = true)
        outputDone = true
        check(encodedFrames == inputFrames) {
            "HEVC encoder frame-count mismatch input=$inputFrames output=$encodedFrames"
        }
        NeoLogger.i(
            TAG,
            "Record-develop HEVC encoder complete inputFrames=$inputFrames " +
                "encodedFrames=$encodedFrames advancedAccepted=$advancedQualityAccepted"
        )
        return encodedFrames
    }

    override fun close() {
        if (started) {
            runCatching { codec.stop() }
        }
        runCatching { codec.release() }
        started = false
    }

    @SuppressLint("InlinedApi")
    private fun buildFormat(
        candidate: EncoderCandidate,
        plan: RecordDevelopEncoderElitePlan,
        advancedQuality: Boolean
    ): MediaFormat =
        MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
            } else {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setSupportedBitrateMode(candidate)
            }
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                candidate.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                colorConfig.toMediaFormatColorStandard()?.let {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
                }
                colorConfig.toMediaFormatColorTransfer()?.let {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, it)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setInteger("color-transfer-request", it)
                    }
                }
                setInteger(MediaFormat.KEY_COLOR_RANGE, colorConfig.toMediaFormatColorRange())
            }
            if (advancedQuality) {
                setInteger("max-bitrate", plan.maximumBitrate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setInteger(MediaFormat.KEY_QUALITY, plan.quality)
                } else {
                    setInteger("quality", plan.quality)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    plan.complexity?.let { setInteger(MediaFormat.KEY_COMPLEXITY, it) }
                    setInteger(MediaFormat.KEY_OPERATING_RATE, plan.operatingRate)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInteger(MediaFormat.KEY_VIDEO_QP_MAX, plan.maximumQp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, plan.maximumQp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_P_MAX, plan.maximumQp)
                    setInteger(MediaFormat.KEY_VIDEO_QP_B_MAX, plan.maximumQp)
                }
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        }

    private fun createStartedCodec(codecName: String, format: MediaFormat): MediaCodec {
        val created = MediaCodec.createByCodecName(codecName)
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            return created
        } catch (failure: Throwable) {
            runCatching { created.release() }
            throw failure
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idleDrains = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idleDrains += 1
                    if (idleDrains > EOS_IDLE_DRAIN_LIMIT) {
                        error("HEVC encoder stalled while draining EOS frames=$encodedFrames")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    ensureTrack(codec.outputFormat)
                    idleDrains = 0
                }
                else -> {
                    idleDrains = 0
                    if (outputIndex < 0) continue
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        ensureTrack(codec.outputFormat)
                        val output = codec.getOutputBuffer(outputIndex)
                            ?: error("HEVC encoder output buffer unavailable")
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        writer.writeSampleBuffer(
                            trackIndex = trackIndex,
                            buffer = output.slice(),
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags
                        )
                        encodedFrames += 1L
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) {
                        outputDone = true
                        return
                    }
                }
            }
        }
    }

    private fun ensureTrack(format: MediaFormat) {
        if (trackIndex >= 0) return
        val codecConfig = extractHevcCodecConfig(format)
        require(codecConfig.isNotEmpty()) { "HEVC Main10 encoder emitted no codec configuration" }
        val decoderFields = HevcDecoderConfigurationRecordPolicy.fromAnnexBParameterSets(
            splitAnnexBParameterSets(codecConfig)
        )
        require(
            decoderFields.bitDepthLumaMinus8 >= 2 &&
                decoderFields.bitDepthChromaMinus8 >= 2 &&
                decoderFields.chromaFormatIdc == 1
        ) {
            "HEVC encoder silently downgraded Main10/P010 output " +
                "profile=${decoderFields.profileIdc} chroma=${decoderFields.chromaFormatIdc} " +
                "lumaBits=${decoderFields.bitDepthLumaMinus8 + 8} " +
                "chromaBits=${decoderFields.bitDepthChromaMinus8 + 8}"
        }
        trackIndex = writer.addVideoTrack(
            QuickTimeVideoTrackConfig(
                mime = MIME_HEVC,
                width = width,
                height = height,
                fps = fps,
                codecConfig = codecConfig,
                colorConfig = colorConfig
            )
        )
        NeoLogger.i(
            TAG,
            "Record-develop HEVC MOV track added csdBytes=${codecConfig.size} " +
                "profile=${decoderFields.profileIdc} lumaBits=${decoderFields.bitDepthLumaMinus8 + 8} " +
                "chromaBits=${decoderFields.bitDepthChromaMinus8 + 8} format=$format"
        )
    }

    private fun resolveElitePlan(candidate: EncoderCandidate): RecordDevelopEncoderElitePlan {
        val bitrateRange = candidate.capabilities.videoCapabilities?.bitrateRange
        val complexityRange = candidate.capabilities.encoderCapabilities?.complexityRange
        return RecordDevelopEncoderQualityPolicy.resolve(
            width = width,
            height = height,
            fps = fps,
            bitrateLower = bitrateRange?.lower ?: 1,
            bitrateUpper = bitrateRange?.upper ?: Int.MAX_VALUE,
            complexityLower = complexityRange?.lower,
            complexityUpper = complexityRange?.upper
        )
    }

    private data class EncoderCandidate(
        val name: String,
        val codecInfo: MediaCodecInfo,
        val capabilities: MediaCodecInfo.CodecCapabilities,
        val level: Int?
    )

    private companion object {
        const val TAG = "RecordDevelopHevcMov"
        const val MIME_HEVC = "video/hevc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val EOS_IDLE_DRAIN_LIMIT = 300
        // Near-max KEY_QUALITY for the CQ last-resort path (parity with the live cinema record path,
        // which pins quality=100). CQ ignores KEY_BIT_RATE, so without this it collapses to the
        // encoder's low internal default quality → the low-bitrate develop output.
        const val CQ_QUALITY = 100

        fun chooseMain10P010Encoder(width: Int, height: Int, fps: Int): EncoderCandidate? {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val candidates = codecs.mapNotNull { info ->
                if (!info.isEncoder) return@mapNotNull null
                if (!info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) }) return@mapNotNull null
                val caps = runCatching { info.getCapabilitiesForType(MIME_HEVC) }.getOrNull()
                    ?: return@mapNotNull null
                if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)) {
                    return@mapNotNull null
                }
                val main10Levels = caps.profileLevels
                    ?.filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                    .orEmpty()
                if (main10Levels.isEmpty()) return@mapNotNull null
                val videoCaps = caps.videoCapabilities ?: return@mapNotNull null
                val sizeSupported = runCatching { videoCaps.isSizeSupported(width, height) }.getOrDefault(false)
                val fpsSupported = runCatching {
                    videoCaps.getSupportedFrameRatesFor(width, height).contains(fps.toDouble())
                }.getOrDefault(true)
                if (!sizeSupported || !fpsSupported) return@mapNotNull null
                EncoderCandidate(
                    name = info.name,
                    codecInfo = info,
                    capabilities = caps,
                    level = main10Levels.maxByOrNull { it.level }?.level
                )
            }
            return candidates.sortedWith(
                compareByDescending<EncoderCandidate> { it.codecInfo.safeHardwareAccelerated() }
                    .thenByDescending { it.codecInfo.safeVendor() }
                    .thenBy { it.codecInfo.name }
            ).firstOrNull()
        }

        fun MediaFormat.setSupportedBitrateMode(candidate: EncoderCandidate) {
            val encoderCaps = candidate.capabilities.encoderCapabilities ?: return
            // Default-ON (RecordDevelopHonoredBitrateFlag): prefer VBR so the computed high KEY_BIT_RATE
            // (~286 Mbps @4K30) is HONORED; CQ is a LAST RESORT (it ignores KEY_BIT_RATE) and then carries
            // a near-max KEY_QUALITY so it does not collapse to the encoder's low internal default.
            // Flag OFF restores the exact legacy CQ-first order (byte-identical A/B).
            val honor = RecordDevelopHonoredBitrateFlag.enabled
            val mode = if (honor) {
                when {
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    else -> null
                }
            } else {
                when {
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    encoderCaps.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ->
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else -> null
                }
            }
            mode?.let {
                setInteger(MediaFormat.KEY_BITRATE_MODE, it)
                if (honor && it == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) {
                    setInteger(MediaFormat.KEY_QUALITY, CQ_QUALITY)
                }
            }
        }

        fun extractHevcCodecConfig(format: MediaFormat): ByteArray {
            val keys = listOf("csd-0", "csd-1", "csd-2")
            val buffers = keys.mapNotNull { key ->
                if (!format.containsKey(key)) return@mapNotNull null
                runCatching { format.getByteBuffer(key)?.toByteArray() }.getOrNull()
            }
            if (buffers.isEmpty()) return ByteArray(0)
            val size = buffers.sumOf { it.size }
            val out = ByteArray(size)
            var offset = 0
            buffers.forEach {
                it.copyInto(out, offset)
                offset += it.size
            }
            return out
        }

        fun splitAnnexBParameterSets(bytes: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var index = 0
            while (index + 3 < bytes.size) {
                when {
                    bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                        bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> {
                        starts += index to 4
                        index += 4
                    }
                    bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                        bytes[index + 2] == 1.toByte() -> {
                        starts += index to 3
                        index += 3
                    }
                    else -> index += 1
                }
            }
            return starts.mapIndexedNotNull { listIndex, (start, prefixBytes) ->
                val payloadStart = start + prefixBytes
                val payloadEnd = starts.getOrNull(listIndex + 1)?.first ?: bytes.size
                if (payloadEnd > payloadStart) bytes.copyOfRange(payloadStart, payloadEnd) else null
            }
        }

        fun MediaCodecInfo.safeHardwareAccelerated(): Boolean =
            runCatching { isHardwareAccelerated }.getOrDefault(!safeSoftwareOnly())

        fun MediaCodecInfo.safeSoftwareOnly(): Boolean =
            runCatching { isSoftwareOnly }.getOrDefault(
                name.startsWith("OMX.google.", ignoreCase = true) ||
                    name.startsWith("c2.android.", ignoreCase = true)
            )

        fun MediaCodecInfo.safeVendor(): Boolean =
            runCatching { isVendor }.getOrDefault(
                name.contains("qcom", ignoreCase = true) ||
                    name.contains("qti", ignoreCase = true) ||
                    name.contains("exynos", ignoreCase = true) ||
                    name.contains("mtk", ignoreCase = true) ||
                    name.contains("mediatek", ignoreCase = true) ||
                    name.contains("vivo", ignoreCase = true)
            )

        fun MediaFormat.safeInt(key: String, fallback: Int): Int =
            if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

        fun ByteBuffer.toByteArray(): ByteArray {
            val copy = duplicate()
            val out = ByteArray(copy.remaining())
            copy.get(out)
            return out
        }

        fun QuickTimeColorAtomConfig.toMediaFormatColorStandard(): Int? =
            when (colorPrimaries) {
                QuickTimeColorAtomPolicy.PRIMARIES_BT709 -> MediaFormat.COLOR_STANDARD_BT709
                QuickTimeColorAtomPolicy.PRIMARIES_BT2020 -> MediaFormat.COLOR_STANDARD_BT2020
                else -> null
            }

        fun QuickTimeColorAtomConfig.toMediaFormatColorTransfer(): Int? =
            when (transferCharacteristics) {
                QuickTimeColorAtomPolicy.TRANSFER_BT709 -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084 -> MediaFormat.COLOR_TRANSFER_ST2084
                else -> null
            }

        fun QuickTimeColorAtomConfig.toMediaFormatColorRange(): Int =
            if (fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED
    }
}
