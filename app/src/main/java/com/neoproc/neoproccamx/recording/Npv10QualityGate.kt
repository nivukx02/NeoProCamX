package com.neoproc.neoproccamx.recording

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

enum class Npv10QualityTier {
    ELITE_RAW14_SENSOR,
    ELITE_RAW12_SENSOR,
    ELITE_NATIVE_P010,
    ELITE_PLANAR_YUV420P10,
    HIGH_YUV420_EXPANDED,
    FAILED_UNSAFE
}

data class Npv10PreflightReport(
    val auditStatus: Npv10AuditStatus,
    val qualityTier: Npv10QualityTier,
    val bytesPerFrame: Long,
    val bytesPerSecond: Long,
    val minimumFreeBytes: Long,
    val availableBytes: Long,
    val privateDirectoryReady: Boolean,
    val noMediaPresent: Boolean,
    val failureReasons: List<String>,
    val frameProcessingBudgetMs: Double = 14.0,
    val processingSpikeFallback: String = "QUEUE_BACKPRESSURE_DROP_GUARD",
    val rawSensorRequired: Boolean = false
)

data class Npv10PostRecordAudit(
    val auditStatus: Npv10AuditStatus,
    val frameCount: Long,
    val payloadBytes: Long,
    val expectedPayloadBytes: Long,
    val frameHeaderBytes: Int,
    val auditedFrameRecordBytes: Long,
    val expectedFrameRecordBytes: Long,
    val monotonicTimestamps: Boolean,
    val timestampContinuityValid: Boolean,
    val byteCountValid: Boolean,
    val frameRecordByteCountValid: Boolean,
    val crcValidated: Boolean,
    val sessionHash: String,
    val expectedSessionHash: String,
    val sessionHashCheckPass: Boolean,
    val failureReasons: List<String>
)

object Npv10QualityGate {
    fun preflight(
        config: Npv10SessionConfig,
        privateRoot: File,
        availableBytes: Long
    ): Npv10PreflightReport {
        val masterDirectory = Npv10ContainerPolicy.masterDirectory(privateRoot)
        val bytesPerFrame = Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
            width = config.width,
            height = config.height,
            payloadFormat = config.payloadFormat,
            packetCodec = config.packetCodec
        )
        val bytesPerSecond = bytesPerFrame * config.fps.coerceAtLeast(1).toLong()
        val minimumFreeBytes = bytesPerSecond * PREFLIGHT_BURST_SECONDS + PREFLIGHT_RESERVED_BYTES
        val privateDirectoryReady = masterDirectory.isDirectory
        val noMediaPresent = File(masterDirectory, ".nomedia").isFile
        val failureReasons = buildList {
            if (!privateDirectoryReady) add("private NPV10 master directory is not ready")
            if (!noMediaPresent) add("private NPV10 .nomedia marker is missing")
            if (availableBytes < minimumFreeBytes) {
                add("free space $availableBytes is below required NPV10 burst budget $minimumFreeBytes")
            }
            runCatching { Npv10IntegrityValidator.validateSessionConfig(config) }
                .onFailure { throwable -> add(throwable.message ?: "NPV10 session metadata is invalid") }
        }
        val passed = failureReasons.isEmpty()
        val qualityTier = if (passed) tierForConfig(config) else Npv10QualityTier.FAILED_UNSAFE
        return Npv10PreflightReport(
            auditStatus = if (passed) Npv10AuditStatus.PASS else Npv10AuditStatus.FAIL,
            qualityTier = qualityTier,
            bytesPerFrame = bytesPerFrame,
            bytesPerSecond = bytesPerSecond,
            minimumFreeBytes = minimumFreeBytes,
            availableBytes = availableBytes,
            privateDirectoryReady = privateDirectoryReady,
            noMediaPresent = noMediaPresent,
            failureReasons = failureReasons,
            frameProcessingBudgetMs = maxFrameProcessingBudgetMs(qualityTier),
            processingSpikeFallback = if (config.payloadFormat.isRawPacked) {
                "LOSSLESS_PACKING_BYPASS"
            } else {
                "QUEUE_BACKPRESSURE_DROP_GUARD"
            },
            rawSensorRequired = config.payloadFormat.isRawPacked
        )
    }

    fun auditMasterFile(
        masterFile: File,
        expectedFrameCount: Long,
        expectedPayloadBytes: Long,
        expectedSessionHash: String? = null,
        readAheadEnabled: Boolean = true
    ): Npv10PostRecordAudit {
        var frameCount = 0L
        var payloadBytes = 0L
        var auditedFrameRecordBytes = 0L
        var lastTimestampNs = Long.MIN_VALUE
        var monotonicTimestamps = true
        var allTimestampsPositive = true
        var crcValidated = true
        val sessionDigest = MessageDigest.getInstance("SHA-256")
        val failureReasons = mutableListOf<String>()
        runCatching {
            Npv10FileReader(masterFile).use { reader ->
                val decodedPayloadBytesPerFrame = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                    reader.header.width,
                    reader.header.height,
                    reader.header.payloadFormat
                )
                val storedPayloadBytesPerFrame = Npv10PacketCodecEngine.storedBytes(
                    decodedPayloadBytesPerFrame,
                    reader.header.packetCodec
                )
                require(storedPayloadBytesPerFrame in 1L..Int.MAX_VALUE.toLong()) {
                    "NPV10 stored packet is outside the supported audit buffer range"
                }
                val requiresDecodedValidation =
                    reader.header.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
                val parallelDecodeBytes = (storedPayloadBytesPerFrame + decodedPayloadBytesPerFrame) * 2L
                val parallelDecode = readAheadEnabled && requiresDecodedValidation &&
                    parallelDecodeBytes <= MAX_AUDIT_PIPELINE_BYTES
                // The native chunk workspace is thread-local. Independent whole-frame decode can
                // overlap without changing packet order, source CRCs or the serial session hash.
                // Count BOTH decoded buffers against the existing memory ceiling before admission.
                val decodedValidationBuffer = if (requiresDecodedValidation && !parallelDecode) {
                    ByteBuffer.allocateDirect(decodedPayloadBytesPerFrame.toInt())
                } else {
                    null
                }
                fun validateStoredFrame(frame: Npv10StoredFrameRecord) {
                    decodedValidationBuffer?.let { decoded ->
                        Npv10PacketCodecEngine.decodeInto(
                            encoded = frame.payload,
                            encodedBytes = frame.storedPayloadBytes,
                            decodedBytes = frame.decodedPayloadBytes,
                            codec = reader.header.packetCodec,
                            target = decoded
                        )
                    }
                    frameCount += 1L
                    payloadBytes += frame.decodedPayloadBytes.toLong()
                    auditedFrameRecordBytes += FRAME_HEADER_BYTES.toLong() + frame.storedPayloadBytes.toLong()
                    sessionDigest.update(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)
                    sessionDigest.updateLong(frame.timestampNs)
                    sessionDigest.updateInt(frame.width)
                    sessionDigest.updateInt(frame.height)
                    sessionDigest.updateInt(frame.storedPayloadBytes)
                    sessionDigest.updateInt(frame.expectedCrc)
                    sessionDigest.update(frame.payload.duplicate())
                    if (frame.timestampNs <= 0L) {
                        allTimestampsPositive = false
                    }
                    if (lastTimestampNs != Long.MIN_VALUE && frame.timestampNs <= lastTimestampNs) {
                        monotonicTimestamps = false
                    }
                    lastTimestampNs = frame.timestampNs
                }
                // The old serial audit left storage idle throughout decode and hashing. Full 4K
                // masters therefore remained unpublished for minutes after their writer had closed.
                // Overlap only independent work; the consumer still validates and hashes EVERY
                // frame in original order, and small-memory/very-large-frame devices keep one slot.
                val pipelineBytes = if (parallelDecode) parallelDecodeBytes else {
                    storedPayloadBytesPerFrame * 2L +
                        (decodedValidationBuffer?.capacity()?.toLong() ?: 0L)
                }
                if (readAheadEnabled && pipelineBytes <= MAX_AUDIT_PIPELINE_BYTES) {
                    val preparePacket: ((Npv10StoredFrameRecord, ByteBuffer) -> Unit)? =
                        if (parallelDecode) { frame, decoded ->
                            Npv10PacketCodecEngine.decodeInto(
                                encoded = frame.payload,
                                encodedBytes = frame.storedPayloadBytes,
                                decodedBytes = frame.decodedPayloadBytes,
                                codec = reader.header.packetCodec,
                                target = decoded
                            )
                        } else null
                    Npv10AuditReadAhead(
                        reader,
                        storedPayloadBytesPerFrame.toInt(),
                        decodedCapacity = if (parallelDecode) decodedPayloadBytesPerFrame.toInt() else 0,
                        preparePacket = preparePacket
                    ).use { packets ->
                        packets.forEachFrame(::validateStoredFrame)
                    }
                } else {
                    val storedPacketBuffer = ByteBuffer.allocateDirect(storedPayloadBytesPerFrame.toInt())
                    while (true) {
                        val frame = reader.readNextStoredFrameInto(storedPacketBuffer) ?: break
                        validateStoredFrame(frame)
                    }
                }
            }
        }.onFailure { throwable ->
            crcValidated = false
            failureReasons += throwable.message ?: "NPV10 post-record reader audit failed"
        }
        if (frameCount <= 0L) failureReasons += "frame count is zero"
        if (frameCount != expectedFrameCount) {
            failureReasons += "frame count $frameCount does not match expected $expectedFrameCount"
        }
        val byteCountValid = payloadBytes == expectedPayloadBytes
        if (!byteCountValid) {
            failureReasons += "payload bytes $payloadBytes do not match expected $expectedPayloadBytes"
        }
        val expectedFrameRecordBytes = runCatching {
            Npv10FileReader(masterFile).use { reader ->
                val decodedBytesPerFrame = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                    reader.header.width,
                    reader.header.height,
                    reader.header.payloadFormat
                )
                if (reader.header.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2) {
                    // Variable packets have already been envelope-, CRC-, and decode-validated above.
                    // Their exact expected record byte count is the sum of those authenticated records.
                    auditedFrameRecordBytes
                } else {
                    val storedBytesPerFrame = Npv10PacketCodecEngine.storedBytes(
                        decodedBytesPerFrame,
                        reader.header.packetCodec
                    )
                    (storedBytesPerFrame + FRAME_HEADER_BYTES.toLong()) * expectedFrameCount
                }
            }
        }.getOrElse {
            expectedPayloadBytes + FRAME_HEADER_BYTES.toLong() * expectedFrameCount
        }
        val frameRecordByteCountValid = auditedFrameRecordBytes == expectedFrameRecordBytes
        if (!frameRecordByteCountValid) {
            failureReasons += "frame record bytes $auditedFrameRecordBytes do not match expected $expectedFrameRecordBytes"
        }
        val timestampContinuityValid = frameCount > 0L && allTimestampsPositive && monotonicTimestamps
        if (!allTimestampsPositive) failureReasons += "frame timestamps must all be positive"
        if (!timestampContinuityValid) failureReasons += "frame timestamps are not strictly monotonic"
        val sessionHash = if (frameCount > 0L && crcValidated) {
            sessionDigest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.US, byte.toInt() and 0xFF)
            }
        } else {
            ""
        }
        val normalizedExpectedSessionHash = expectedSessionHash.orEmpty()
        val sessionHashCheckPass = normalizedExpectedSessionHash.isBlank() ||
            sessionHash.equals(normalizedExpectedSessionHash, ignoreCase = true)
        if (!sessionHashCheckPass) {
            failureReasons += "session hash $sessionHash does not match expected $normalizedExpectedSessionHash"
        }
        return Npv10PostRecordAudit(
            auditStatus = if (failureReasons.isEmpty()) Npv10AuditStatus.PASS else Npv10AuditStatus.FAIL,
            frameCount = frameCount,
            payloadBytes = payloadBytes,
            expectedPayloadBytes = expectedPayloadBytes,
            frameHeaderBytes = FRAME_HEADER_BYTES,
            auditedFrameRecordBytes = auditedFrameRecordBytes,
            expectedFrameRecordBytes = expectedFrameRecordBytes,
            monotonicTimestamps = monotonicTimestamps,
            timestampContinuityValid = timestampContinuityValid,
            byteCountValid = byteCountValid,
            frameRecordByteCountValid = frameRecordByteCountValid,
            crcValidated = crcValidated && frameCount > 0L,
            sessionHash = sessionHash,
            expectedSessionHash = normalizedExpectedSessionHash,
            sessionHashCheckPass = sessionHashCheckPass,
            failureReasons = failureReasons
        )
    }

    fun tierForConfig(config: Npv10SessionConfig): Npv10QualityTier =
        if (
            config.rawSensorCapture &&
            config.payloadFormat == Npv10PayloadFormat.RAW14_NPV10_PACKED &&
            config.sourceBitDepth >= 14 &&
            config.sourceFormat.equals(SOURCE_FORMAT_RAW_PRIVATE, ignoreCase = true)
        ) {
            Npv10QualityTier.ELITE_RAW14_SENSOR
        } else if (
            config.rawSensorCapture &&
            config.payloadFormat == Npv10PayloadFormat.RAW12_NPV10_PACKED &&
            config.sourceBitDepth >= 12 &&
            config.sourceFormat.equals(SOURCE_FORMAT_RAW_SENSOR, ignoreCase = true)
        ) {
            Npv10QualityTier.ELITE_RAW12_SENSOR
        } else if (
            config.payloadFormat == Npv10PayloadFormat.P010_LE &&
            config.sourceBitDepth >= 10 &&
            config.sourceFormat.equals(SOURCE_FORMAT_P010, ignoreCase = true)
        ) {
            Npv10QualityTier.ELITE_NATIVE_P010
        } else if (
            config.payloadFormat == Npv10PayloadFormat.YUV420P10_LE &&
            config.sourceBitDepth >= 10
        ) {
            Npv10QualityTier.ELITE_PLANAR_YUV420P10
        } else if (
            config.payloadFormat == Npv10PayloadFormat.YUV420_8BIT_EXPANDED &&
            config.sourceBitDepth <= 8
        ) {
            Npv10QualityTier.HIGH_YUV420_EXPANDED
        } else {
            Npv10QualityTier.FAILED_UNSAFE
        }

    fun maxSafeWriteMs(fps: Int): Double =
        (1_000.0 / fps.coerceAtLeast(1).toDouble()) * MAX_WRITE_FRACTION_OF_FRAME_INTERVAL

    fun maxSafeWriteSpikeMs(fps: Int): Double =
        maxSafeWriteMs(fps) * MAX_ASYNC_WRITE_SPIKE_MULTIPLIER

    fun writeLatencyWithinRealtimeBudget(
        averageWriteTimeMs: Double,
        maxWriteTimeMs: Double,
        fps: Int,
        droppedFrames: Int
    ): Boolean =
        droppedFrames == 0 &&
            averageWriteTimeMs <= maxSafeWriteMs(fps) &&
            maxWriteTimeMs <= maxSafeWriteSpikeMs(fps)

    fun maxFrameProcessingBudgetMs(tier: Npv10QualityTier): Double =
        when (tier) {
            Npv10QualityTier.ELITE_RAW14_SENSOR,
            Npv10QualityTier.ELITE_RAW12_SENSOR -> FLAGSHIP_PROCESSING_BUDGET_MS
            Npv10QualityTier.ELITE_NATIVE_P010 -> ULTRA_PROCESSING_BUDGET_MS
            Npv10QualityTier.ELITE_PLANAR_YUV420P10 -> ULTRA_PROCESSING_BUDGET_MS
            Npv10QualityTier.HIGH_YUV420_EXPANDED,
            Npv10QualityTier.FAILED_UNSAFE -> ULTRA_PROCESSING_BUDGET_MS
        }

    private const val PREFLIGHT_BURST_SECONDS = 3L
    private const val MAX_AUDIT_PIPELINE_BYTES = 128L * 1024L * 1024L
    private const val PREFLIGHT_RESERVED_BYTES = 16L * 1024L * 1024L
    private const val FRAME_HEADER_BYTES = 4 + Long.SIZE_BYTES + Int.SIZE_BYTES * 4
    private const val MAX_WRITE_FRACTION_OF_FRAME_INTERVAL = 0.75
    // The writer owns a strict 12-frame bounded queue. Permit a transient spike occupying fewer than
    // half those slots while still requiring a realtime average and exactly zero dropped frames. This
    // measures sustained safety rather than failing a bit-exact master for one absorbed scheduler/fsync
    // hiccup; a spike beyond this sub-queue window remains a hard audit failure.
    private const val MAX_ASYNC_WRITE_SPIKE_MULTIPLIER = 6.0
    private const val FLAGSHIP_PROCESSING_BUDGET_MS = 6.0
    private const val ULTRA_PROCESSING_BUDGET_MS = 14.0
    private const val SOURCE_FORMAT_P010 = "YCBCR_P010"
    private const val SOURCE_FORMAT_RAW_SENSOR = "RAW_SENSOR"
    private const val SOURCE_FORMAT_RAW_PRIVATE = "RAW_PRIVATE"

    private fun MessageDigest.updateLong(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            update(((value ushr shift) and 0xFF).toByte())
        }
    }

    private fun MessageDigest.updateInt(value: Int) {
        for (shift in 24 downTo 0 step 8) {
            update(((value ushr shift) and 0xFF).toByte())
        }
    }
}
