package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
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
    private val codecLease: RecordDevelopHevcEncoderLease
    private val elitePlan: RecordDevelopEncoderElitePlan
    private val rateControl: RecordDevelopEncoderRateControl
    private val advancedQualityAccepted: Boolean
    private var trackIndex = -1
    private var outputDone = false
    private var started = false
    private var encodedFrames = 0L
    private var inputFrames = 0L
    private var lastInputPtsUs: Long? = null
    private var pendingFrame: PendingInputFrame? = null
    private var directInputMode = false
    private var directInputSealed = false
    private var closed = false

    /** Device-independent ideal quality target, retained even on capability-bound hardware. */
    internal val policyTargetBitrateBps: Long
        get() = elitePlan.policyTargetBitrate.toLong()

    /** Geometry-scaled quality floor consumed by the fail-closed publication gate. */
    internal val minimumViableBitrateBps: Long
        get() = elitePlan.minimumViableBitrate.toLong()

    internal val configuredBitrateBps: Long
        get() = elitePlan.configuredBitrate.toLong()

    internal val advertisedBitrateCeilingBps: Long
        get() = elitePlan.advertisedBitrateCeiling.toLong()

    internal val capabilityBound: Boolean
        get() = elitePlan.capabilityBound

    /** True only when the codec accepted every requested elite quality-control key. */
    internal val advancedQualityControlsAccepted: Boolean
        get() = advancedQualityAccepted

    init {
        require(deliveryFormat == RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV) {
            "HEVC 10-bit 4:2:2 MOV requires a real P210/Y210 MediaCodec input path; " +
                "this device build exposes only strict Main10 P010 4:2:0 delivery. " +
                "Use V210_422_UNCOMPRESSED_MOV for true 10-bit 4:2:2 without chroma downgrade."
        }
        require(width > 0 && height > 0 && fps > 0) {
            "Invalid HEVC record-develop geometry ${width}x${height}@${fps}"
        }
        // Read-only admission happens before MediaCodec.create/configure/start. A capability-bound codec
        // is admitted only when its exact advertised ceiling still clears the geometry-scaled quality
        // floor. The larger ideal target remains explicit in telemetry; no transport or bit-depth fallback
        // is silently substituted here.
        val scan = scanHevcEncoders(width, height, fps)
        val capabilityReport = strictCapabilityReport(width, height, fps, scan).requireAccepted()
        val selectedName = requireNotNull(capabilityReport.selectedCandidateName)
        val candidate = scan.firstOrNull { it.name == selectedName && it.capabilities != null }
            ?: error("Strict HEVC admission selected unavailable codec $selectedName")
        rateControl = requireNotNull(capabilityReport.selectedRateControl)
        elitePlan = resolveElitePlan(candidate)
        check(elitePlan.configuredBitrate == capabilityReport.selectedConfiguredBitrateBps) {
            "Strict HEVC admission/plan mismatch admission=${capabilityReport.selectedConfiguredBitrateBps} " +
                "plan=${elitePlan.configuredBitrate}"
        }
        val eliteFormat = buildFormat(candidate, elitePlan, rateControl)
        codecLease = try {
            createStartedCodec(candidate.name, eliteFormat)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "Strict HEVC encoder ${candidate.name} rejected elite controls for " +
                    "${width}x${height}@${fps}: policyTargetBitrateBps=${elitePlan.policyTargetBitrate}, " +
                    "minimumViableBitrateBps=${elitePlan.minimumViableBitrate}, " +
                    "configuredBitrateBps=${elitePlan.configuredBitrate}, " +
                    "advertisedBitrateCeilingBps=${elitePlan.advertisedBitrateCeiling}, " +
                    "rateControl=$rateControl. HEVC was refused; compatibility controls and " +
                    "output transport were not substituted.",
                failure
            )
        }
        codec = codecLease.codec
        advancedQualityAccepted = true
        started = true
        NeoLogger.i(
            TAG,
            "Record-develop HEVC encoder started codec=${candidate.name} " +
                "geometry=${width}x${height}@${fps} policyTargetBitrate=${elitePlan.policyTargetBitrate} " +
                "minimumViableBitrate=${elitePlan.minimumViableBitrate} " +
                "configuredBitrate=${elitePlan.configuredBitrate} " +
                "advertisedCeiling=${elitePlan.advertisedBitrateCeiling} " +
                "capabilityBound=${elitePlan.capabilityBound} rateControl=$rateControl " +
                "quality=${elitePlan.quality} " +
                "complexity=${elitePlan.complexity} qpMax=${elitePlan.maximumQp} " +
                "advancedAccepted=$advancedQualityAccepted"
        )
    }

    fun encodeFrame(p010: ByteArray, presentationTimeUs: Long) {
        check(!outputDone) { "HEVC encoder already ended" }
        check(!directInputMode) { "Cannot mix buffered-array and direct HEVC input modes" }
        RecordDevelopDecoderQualityPolicy.requireMonotonicPts(lastInputPtsUs, presentationTimeUs)
        val expectedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        require(p010.size == expectedBytes) {
            "Developed P010 payload size mismatch size=${p010.size} expected=$expectedBytes"
        }

        // Retain one frame of look-ahead so finish() can submit the final picture before a distinct
        // empty EOS buffer. Qualcomm encoders can discard a delayed Main10 picture when EOS is attached
        // to that real input buffer; keeping picture and stream termination separate preserves the exact
        // input-frame count without changing pixel data, codec controls, bitrate, or timestamps.
        pendingFrame?.let(::queueFrame)
        pendingFrame = PendingInputFrame(p010, presentationTimeUs)
        lastInputPtsUs = presentationTimeUs
    }

    /**
     * Queues caller-owned direct P010 without a frame-sized heap copy. [endOfStream] seals the input
     * sequence, but is deliberately not attached to the final picture. Some Qualcomm encoders report
     * EOS while discarding delayed Main10 pictures when the last real input carries the EOS flag.
     * [finish] therefore submits a distinct empty EOS buffer after every real frame has entered the
     * codec. MediaCodec copies the bytes into its input buffer before this method returns, so the
     * caller may immediately reuse [p010].
     */
    fun encodeFrameDirect(
        p010: ByteBuffer,
        presentationTimeUs: Long,
        endOfStream: Boolean
    ) {
        check(!outputDone) { "HEVC encoder already ended" }
        check(pendingFrame == null) { "Cannot mix buffered-array and direct HEVC input modes" }
        check(!directInputSealed) { "HEVC direct input was already sealed" }
        RecordDevelopDecoderQualityPolicy.requireMonotonicPts(lastInputPtsUs, presentationTimeUs)
        val expectedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        val source = p010.slice()
        require(source.isDirect) { "Developed P010 direct input must use a direct ByteBuffer" }
        require(source.remaining() == expectedBytes) {
            "Developed P010 payload size mismatch size=${source.remaining()} expected=$expectedBytes"
        }
        directInputMode = true
        queueDirectFrame(source, presentationTimeUs)
        lastInputPtsUs = presentationTimeUs
        if (endOfStream) {
            directInputSealed = true
        }
    }

    /**
     * Seals an unknown-length direct stream after its final frame has been synchronously submitted.
     * The real picture remains free of EOS; [finish] still queues one distinct empty EOS buffer so delayed
     * Qualcomm Main10 output cannot be discarded. This is the streaming counterpart to passing
     * `endOfStream=true` when a caller knows the final frame in advance.
     */
    fun sealDirectInput() {
        check(directInputMode) { "Cannot seal direct HEVC input before a direct frame is submitted" }
        check(!outputDone) { "HEVC encoder already ended" }
        directInputSealed = true
    }

    private fun queueDirectFrame(
        p010: ByteBuffer,
        presentationTimeUs: Long
    ) {
        val payloadBytes = p010.remaining()
        val inputSlot = awaitInputSlot(
            purpose = "direct P010 frame",
            payloadBytes = payloadBytes,
            presentationTimeUs = presentationTimeUs
        )
        inputSlot.buffer.put(p010.duplicate())
        codec.queueInputBuffer(
            inputSlot.index,
            0,
            payloadBytes,
            presentationTimeUs,
            0
        )
        inputFrames += 1L
        drain(endOfStream = false)
    }

    private fun queueFrame(frame: PendingInputFrame) {
        val inputSlot = awaitInputSlot(
            purpose = "buffered P010 frame",
            payloadBytes = frame.p010.size,
            presentationTimeUs = frame.presentationTimeUs
        )
        inputSlot.buffer.put(frame.p010)
        codec.queueInputBuffer(
            inputSlot.index,
            0,
            frame.p010.size,
            frame.presentationTimeUs,
            0
        )
        inputFrames += 1L
        drain(endOfStream = false)
    }

    /**
     * Waits for one synchronous byte-buffer input slot without allowing a vendor codec to freeze the
     * conversion forever. A timeout is a strict transport refusal: the caller keeps the original master
     * and receives a precise failure instead of an incomplete publication. Geometry, P010, Main10 and
     * bitrate policy are deliberately not changed here.
     */
    private fun awaitInputSlot(
        purpose: String,
        payloadBytes: Int,
        presentationTimeUs: Long
    ): CodecInputSlot {
        require(payloadBytes > 0) { "HEVC $purpose payload must be non-empty" }
        val startedAtMs = SystemClock.elapsedRealtime()
        var attempts = 0
        var largestObservedCapacity = -1
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                throw InterruptedException(
                    strictInputTransportFailure(
                        reason = "conversion thread was interrupted while waiting for encoder input",
                        purpose = purpose,
                        payloadBytes = payloadBytes,
                        presentationTimeUs = presentationTimeUs,
                        attempts = attempts,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                        largestObservedCapacity = largestObservedCapacity,
                        capacitySnapshot = emptyList()
                    )
                )
            }
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            attempts += 1
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex)
                    ?: error(
                        strictInputTransportFailure(
                            reason = "dequeued encoder input buffer is unavailable",
                            purpose = purpose,
                            payloadBytes = payloadBytes,
                            presentationTimeUs = presentationTimeUs,
                            attempts = attempts,
                            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                            largestObservedCapacity = largestObservedCapacity,
                            capacitySnapshot = emptyList()
                        )
                    )
                input.clear()
                largestObservedCapacity = maxOf(largestObservedCapacity, input.capacity())
                if (input.capacity() < payloadBytes) {
                    error(
                        strictInputTransportFailure(
                            reason = "dequeued synchronous input buffer is smaller than the full P010 frame",
                            purpose = purpose,
                            payloadBytes = payloadBytes,
                            presentationTimeUs = presentationTimeUs,
                            attempts = attempts,
                            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                            largestObservedCapacity = largestObservedCapacity,
                            capacitySnapshot = listOf(input.capacity())
                        )
                    )
                }
                if (inputFrames == 0L) {
                    NeoLogger.i(
                        TAG,
                        "HEVC_INPUT_ADMITTED purpose=$purpose geometry=${width}x${height}@$fps " +
                            "payloadBytes=$payloadBytes capacity=${input.capacity()} attempts=$attempts " +
                            "waitMs=${SystemClock.elapsedRealtime() - startedAtMs}"
                    )
                }
                return CodecInputSlot(index = inputIndex, buffer = input)
            }

            drain(endOfStream = false)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            if (elapsedMs >= INPUT_ADMISSION_TIMEOUT_MS) {
                error(
                    strictInputTransportFailure(
                        reason = "encoder supplied no synchronous input slot within the bounded admission window",
                        purpose = purpose,
                        payloadBytes = payloadBytes,
                        presentationTimeUs = presentationTimeUs,
                        attempts = attempts,
                        elapsedMs = elapsedMs,
                        largestObservedCapacity = largestObservedCapacity,
                        capacitySnapshot = emptyList()
                    )
                )
            }
        }
    }

    private fun strictInputTransportFailure(
        reason: String,
        purpose: String,
        payloadBytes: Int,
        presentationTimeUs: Long,
        attempts: Int,
        elapsedMs: Long,
        largestObservedCapacity: Int,
        capacitySnapshot: List<Int>
    ): String =
        "Strict HEVC Main10/P010 byte-buffer transport refused $purpose: $reason; " +
            "codec=${codec.name} geometry=${width}x${height}@$fps payloadBytes=$payloadBytes " +
            "ptsUs=$presentationTimeUs attempts=$attempts elapsedMs=$elapsedMs " +
            "largestObservedCapacity=$largestObservedCapacity capacities=$capacitySnapshot " +
            "inputFrames=$inputFrames encodedFrames=$encodedFrames. The source master remains intact; " +
            "resolution, bit depth, colour metadata, bitrate policy, and output transport were not reduced."

    fun finish(endPresentationTimeUs: Long): Long {
        if (outputDone) {
            setTrackEndPresentationTime(endPresentationTimeUs)
            return encodedFrames
        }
        if (directInputMode) {
            check(directInputSealed) {
                "Direct HEVC input must mark the final frame before finish"
            }
            queueEmptyEndOfStream(endPresentationTimeUs)
        } else {
            val finalFrame = pendingFrame
            pendingFrame = null
            if (finalFrame != null) {
                queueFrame(finalFrame)
            }
            // Empty streams are rejected by all production callers, but the same bounded EOS path is
            // retained for defensive direct use. A real picture never carries the EOS flag.
            queueEmptyEndOfStream(endPresentationTimeUs)
        }
        outputDone = true
        check(encodedFrames == inputFrames) {
            "HEVC encoder frame-count mismatch input=$inputFrames output=$encodedFrames"
        }
        setTrackEndPresentationTime(endPresentationTimeUs)
        NeoLogger.i(
            TAG,
            "Record-develop HEVC encoder complete inputFrames=$inputFrames " +
                "encodedFrames=$encodedFrames advancedAccepted=$advancedQualityAccepted"
        )
        return encodedFrames
    }

    /**
     * Signals EOS only after all real inputs have been submitted. Draining while waiting for an input
     * buffer is essential for encoders whose input pool remains occupied until delayed pictures leave
     * the output queue.
     */
    private fun queueEmptyEndOfStream(endPresentationTimeUs: Long) {
        var idleInputs = 0
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
                drain(endOfStream = true)
                return
            }
            drain(endOfStream = false)
            idleInputs += 1
            if (idleInputs > EOS_IDLE_DRAIN_LIMIT) {
                error(
                    "HEVC encoder stalled before separate EOS " +
                        "inputFrames=$inputFrames encodedFrames=$encodedFrames"
                )
            }
        }
    }

    private fun setTrackEndPresentationTime(endPresentationTimeUs: Long) {
        if (trackIndex >= 0) {
            writer.setTrackEndPresentationTimeUs(trackIndex, endPresentationTimeUs)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        RecordDevelopHevcEncoderPool.recycle(codecLease)
        started = false
    }

    @SuppressLint("InlinedApi")
    private fun buildFormat(
        candidate: EncoderCandidate,
        plan: RecordDevelopEncoderElitePlan,
        rateControl: RecordDevelopEncoderRateControl
    ): MediaFormat =
        MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.configuredBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)
            } else {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setStrictBitrateMode(candidate, rateControl)
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
                if (colorConfig.transferCharacteristics == QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084) {
                    HdrStaticMetadataPolicy.applyToFormat(
                        format = this,
                        mode = HdrStaticMetadataPolicy.StaticMetadataMode.HDR10,
                        masteringDisplay = colorConfig.masteringDisplay
                            ?: HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_1000_NITS
                    )
                }
            }
            setInteger("max-bitrate", plan.configuredBitrate)
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
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        }

    private fun createStartedCodec(
        codecName: String,
        format: MediaFormat
    ): RecordDevelopHevcEncoderLease =
        RecordDevelopHevcEncoderPool.acquireStarted(codecName, format)

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
        val capabilities = requireNotNull(candidate.capabilities) {
            "Selected HEVC encoder ${candidate.name} has no capabilities"
        }
        val bitrateRange = capabilities.videoCapabilities?.bitrateRange
            ?: error("Selected HEVC encoder ${candidate.name} has no bitrate range")
        val complexityRange = capabilities.encoderCapabilities?.complexityRange
        return RecordDevelopEncoderQualityPolicy.resolve(
            width = width,
            height = height,
            fps = fps,
            bitrateLower = bitrateRange.lower,
            bitrateUpper = bitrateRange.upper,
            complexityLower = complexityRange?.lower,
            complexityUpper = complexityRange?.upper
        )
    }

    private data class EncoderCandidate(
        val name: String,
        val codecInfo: MediaCodecInfo,
        val capabilities: MediaCodecInfo.CodecCapabilities?,
        val level: Int?,
        val policyCandidate: RecordDevelopEncoderCapabilityCandidate
    )

    private data class PendingInputFrame(
        val p010: ByteArray,
        val presentationTimeUs: Long
    )

    private data class CodecInputSlot(
        val index: Int,
        val buffer: ByteBuffer
    )

    internal companion object {
        const val TAG = "RecordDevelopHevcMov"
        const val MIME_HEVC = "video/hevc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val EOS_IDLE_DRAIN_LIMIT = 300
        const val INPUT_ADMISSION_TIMEOUT_MS = 5_000L
        /**
         * Read-only device report used by callers to reject an impossible HEVC conversion before they
         * launch decode/develop workers. It enumerates advertised codec capabilities only; it does not
         * create, configure, start, or mutate a MediaCodec instance.
         */
        @SuppressLint("InlinedApi")
        internal fun strictCapabilityReport(
            width: Int,
            height: Int,
            fps: Int
        ): RecordDevelopEncoderStrictCapabilityReport = strictCapabilityReport(
            width = width,
            height = height,
            fps = fps,
            scan = scanHevcEncoders(width, height, fps)
        )

        internal fun requireStrictCapability(
            width: Int,
            height: Int,
            fps: Int
        ): RecordDevelopEncoderStrictCapabilityReport =
            strictCapabilityReport(width, height, fps).requireAccepted()

        private fun strictCapabilityReport(
            width: Int,
            height: Int,
            fps: Int,
            scan: List<EncoderCandidate>
        ): RecordDevelopEncoderStrictCapabilityReport =
            RecordDevelopEncoderQualityPolicy.strictCapabilityReport(
                width = width,
                height = height,
                fps = fps,
                candidates = scan.map { it.policyCandidate }
            )

        @SuppressLint("InlinedApi")
        private fun scanHevcEncoders(width: Int, height: Int, fps: Int): List<EncoderCandidate> {
            val codecs = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
            }.getOrElse { emptyList() }
            return codecs.mapNotNull { info ->
                if (!info.isEncoder) return@mapNotNull null
                if (!info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) }) {
                    return@mapNotNull null
                }
                val capabilities = runCatching { info.getCapabilitiesForType(MIME_HEVC) }.getOrNull()
                val main10Levels = capabilities?.profileLevels
                    ?.filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                    .orEmpty()
                val videoCapabilities = capabilities?.videoCapabilities
                val bitrateRange = videoCapabilities?.bitrateRange
                val encoderCapabilities = capabilities?.encoderCapabilities
                val rateControlModes = buildSet {
                    if (encoderCapabilities.supports(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                        add(RecordDevelopEncoderRateControl.CBR)
                    }
                    if (encoderCapabilities.supports(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                        add(RecordDevelopEncoderRateControl.VBR)
                    }
                    if (encoderCapabilities.supports(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                        add(RecordDevelopEncoderRateControl.CQ)
                    }
                }
                val policyCandidate = RecordDevelopEncoderCapabilityCandidate(
                    name = info.name,
                    hardwareAccelerated = info.safeHardwareAccelerated(),
                    vendor = info.safeVendor(),
                    supportsMain10 = main10Levels.isNotEmpty(),
                    supportsP010 = capabilities?.colorFormats?.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                    ) == true,
                    supportsRequestedGeometryAndRate = videoCapabilities?.let { video ->
                        runCatching {
                            video.areSizeAndRateSupported(width, height, fps.toDouble())
                        }.getOrDefault(false)
                    } == true,
                    bitrateLower = bitrateRange?.lower ?: 0,
                    bitrateUpper = bitrateRange?.upper ?: 0,
                    rateControlModes = rateControlModes
                )
                EncoderCandidate(
                    name = info.name,
                    codecInfo = info,
                    capabilities = capabilities,
                    level = main10Levels.maxByOrNull { it.level }?.level,
                    policyCandidate = policyCandidate
                )
            }
        }

        private fun MediaCodecInfo.EncoderCapabilities?.supports(mode: Int): Boolean =
            this != null && runCatching { isBitrateModeSupported(mode) }.getOrDefault(false)

        private fun MediaFormat.setStrictBitrateMode(
            candidate: EncoderCandidate,
            rateControl: RecordDevelopEncoderRateControl
        ) {
            val encoderCaps = requireNotNull(candidate.capabilities?.encoderCapabilities) {
                "Strict HEVC encoder ${candidate.name} has no encoder capabilities"
            }
            val mediaCodecMode = when (rateControl) {
                RecordDevelopEncoderRateControl.CBR ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                RecordDevelopEncoderRateControl.VBR ->
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                RecordDevelopEncoderRateControl.CQ -> error(
                    "Strict HEVC encoder ${candidate.name} cannot use CQ because CQ does not " +
                        "prove the required bitrate floor"
                )
            }
            check(encoderCaps.supports(mediaCodecMode)) {
                "Strict HEVC encoder ${candidate.name} no longer advertises $rateControl rate control"
            }
            setInteger(MediaFormat.KEY_BITRATE_MODE, mediaCodecMode)
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
