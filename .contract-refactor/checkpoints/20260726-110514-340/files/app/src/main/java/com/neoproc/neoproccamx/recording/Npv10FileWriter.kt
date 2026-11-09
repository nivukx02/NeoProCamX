package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.media.Image
import android.system.Os
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.abs

data class Npv10CopiedP010Frame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val payload: ByteBuffer,
    val payloadBytes: Int,
    val copyPath: Npv10FramePacker.P010CopyPath
)

/** Monotonic stop-stage timings for the durable NPV10 master seal. */
data class Npv10FileFinalizationTimings(
    val truncateMs: Double = 0.0,
    val durableSyncMs: Double = 0.0,
    val descriptorCloseMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val committedBytes: Long = 0L,
    val reservedBytes: Long = 0L
)

class Npv10FileWriter(
    private val outputFile: File,
    private val config: Npv10SessionConfig,
    availableStorageBytes: Long = 0L,
    preparedPreallocation: RecordingPreallocationResult? = null
) : Closeable {
    private val randomAccessFile: RandomAccessFile
    private val channel: FileChannel
    var preallocationResult: RecordingPreallocationResult
        private set
    private var closed = false
    var frameCount: Long = 0L
        private set
    var firstFrameTimestampNs: Long = -1L
        private set
    var firstFrameWidth: Int = 0
        private set
    var firstFrameHeight: Int = 0
        private set
    var firstFrameBitDepth: Int = 0
        private set
    var payloadBytesWritten: Long = 0L
        private set
    var encodedPayloadBytesWritten: Long = 0L
        private set
    var committedBytes: Long = 0L
        private set
    private var totalWriteTimeNs: Long = 0L
    private var maxWriteTimeNs: Long = 0L
    private var lastFrameTimestampNs: Long = -1L
    private var totalTimestampJitterNs: Long = 0L
    private var timestampJitterSamples: Long = 0L
    var maxTimestampJitterNs: Long = 0L
        private set
    private val frameHeaderBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size + Long.SIZE_BYTES + Int.SIZE_BYTES * 4)
        .order(ByteOrder.BIG_ENDIAN)
    private var reusableDirectP010Payload: ByteBuffer? = null
    private var reusableEncodedPacket: ByteBuffer? = null
    @Volatile
    var lastFinalizationTimings: Npv10FileFinalizationTimings = Npv10FileFinalizationTimings()
        private set

    val averageWriteTimeMs: Double
        get() = if (frameCount <= 0L) 0.0 else totalWriteTimeNs.toDouble() / frameCount.toDouble() / 1_000_000.0
    val maxWriteTimeMs: Double
        get() = maxWriteTimeNs.toDouble() / 1_000_000.0
    val averageTimestampJitterNs: Long
        get() = if (timestampJitterSamples <= 0L) 0L else totalTimestampJitterNs / timestampJitterSamples

    init {
        Npv10IntegrityValidator.validateSessionConfig(config)
        outputFile.parentFile?.mkdirs()
        randomAccessFile = RandomAccessFile(outputFile, "rw")
        channel = randomAccessFile.channel
        preallocationResult = if (preparedPreallocation == null) {
            randomAccessFile.setLength(0L)
            preallocate(availableStorageBytes)
        } else {
            require(
                preparedPreallocation.plan.enabled &&
                    preparedPreallocation.method == RecordingPreallocationMethod.POSIX_FALLOCATE &&
                    outputFile.length() >= preparedPreallocation.reservedBytes
            ) { "Invalid prepared NPV10 physical reservation" }
            channel.position(0L)
            preparedPreallocation.copy(
                committedBytes = 0L,
                reservationTrimmed = false,
                synced = false
            )
        }
        try {
            writeHeader()
        } catch (t: Throwable) {
            runCatching { channel.truncate(0L) }
            runCatching { randomAccessFile.close() }
            throw t
        }
    }

    @Synchronized
    fun writeFrame(frame: RawYuvFrame) {
        check(!closed) { "NPV10 writer is closed" }
        val writeStartedNs = System.nanoTime()
        Npv10IntegrityValidator.validateRuntimeFrame(config, frame)
        val payload = Npv10FramePacker.pack(frame, config.payloadFormat)
        val encodedPacket = Npv10PacketCodecEngine.encode(payload, config.packetCodec)
        val crc = CRC32()
        crc.update(encodedPacket)
        val crcValue = crc.value.toInt()
        updateRuntimeTiming(frame.timestamp)
        writeFrameHeader(
            timestampNs = frame.timestamp,
            width = frame.width,
            height = frame.height,
            payloadSize = encodedPacket.size,
            crcValue = crcValue
        )
        writeFramePayload(encodedPacket)
        markCommittedBoundary()
        if (frameCount == 0L) {
            firstFrameTimestampNs = frame.timestamp
            firstFrameWidth = frame.width
            firstFrameHeight = frame.height
            firstFrameBitDepth = frame.bitDepth
        }
        frameCount += 1L
        payloadBytesWritten += payload.size.toLong()
        encodedPayloadBytesWritten += encodedPacket.size.toLong()
        val writeElapsedNs = System.nanoTime() - writeStartedNs
        totalWriteTimeNs += writeElapsedNs
        maxWriteTimeNs = maxOf(maxWriteTimeNs, writeElapsedNs)
        if (frameCount == 1L || frameCount % FLUSH_FRAME_INTERVAL == 0L) {
            channel.force(false)
        }
    }

    @Synchronized
    fun writeP010Image(image: Image): Npv10FramePacker.P010CopyPath {
        check(!closed) { "NPV10 writer is closed" }
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Direct Image write is only valid for P010_LE NPV10 sessions"
        }
        validateRuntimeP010Image(image)

        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height, config.payloadFormat)
            .toInt()
        val payloadBuffer = directP010PayloadBuffer(payloadBytes)
        val copyResult = Npv10FramePacker.copyP010ImageIntoDirectBufferWithResult(image, payloadBuffer)
        require(copyResult.bytesCopied == payloadBytes) {
            "P010 Image copy produced ${copyResult.bytesCopied} bytes, expected $payloadBytes"
        }
        val timestampNs = runCatching { image.timestamp }.getOrDefault(0L)
        return writeCopiedP010Frame(
            Npv10CopiedP010Frame(
                timestampNs = timestampNs,
                width = image.width,
                height = image.height,
                payload = payloadBuffer,
                payloadBytes = payloadBytes,
                copyPath = copyResult.path
            )
        )
    }

    @Synchronized
    fun writeCopiedP010Frame(frame: Npv10CopiedP010Frame): Npv10FramePacker.P010CopyPath {
        check(!closed) { "NPV10 writer is closed" }
        require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Copied P010 write is only valid for P010_LE NPV10 sessions"
        }
        validateRuntimeP010Frame(
            width = frame.width,
            height = frame.height,
            payloadBytes = frame.payloadBytes
        )

        val writeStartedNs = System.nanoTime()
        val encodedPacket = encodedPacketBuffer(frame.payloadBytes)
        val encodedBytes = Npv10PacketCodecEngine.encode(
            decoded = frame.payload,
            decodedBytes = frame.payloadBytes,
            codec = config.packetCodec,
            target = encodedPacket
        )
        val crcValue = crc32(encodedPacket, encodedBytes)
        updateRuntimeTiming(frame.timestampNs)
        writeFrameHeader(
            timestampNs = frame.timestampNs,
            width = frame.width,
            height = frame.height,
            payloadSize = encodedBytes,
            crcValue = crcValue
        )
        writeFramePayload(encodedPacket, encodedBytes)
        markCommittedBoundary()
        if (frameCount == 0L) {
            firstFrameTimestampNs = frame.timestampNs
            firstFrameWidth = frame.width
            firstFrameHeight = frame.height
            firstFrameBitDepth = config.sourceBitDepth
        }
        frameCount += 1L
        payloadBytesWritten += frame.payloadBytes.toLong()
        encodedPayloadBytesWritten += encodedBytes.toLong()
        val writeElapsedNs = System.nanoTime() - writeStartedNs
        totalWriteTimeNs += writeElapsedNs
        maxWriteTimeNs = maxOf(maxWriteTimeNs, writeElapsedNs)
        if (frameCount == 1L || frameCount % FLUSH_FRAME_INTERVAL == 0L) {
            channel.force(false)
        }
        return frame.copyPath
    }

    @Synchronized
    override fun close() {
        closeInternal(preservePhysicalReservationWhenEmpty = false)
    }

    /** Close without trimming only when no camera packet reached this writer, allowing rapid retry. */
    @Synchronized
    fun closePreservingPhysicalReservationWhenEmpty(): Boolean =
        closeInternal(preservePhysicalReservationWhenEmpty = true)

    private fun closeInternal(preservePhysicalReservationWhenEmpty: Boolean): Boolean {
        if (closed) return false
        val finalizationStartedNs = System.nanoTime()
        val preserveReservation =
            preservePhysicalReservationWhenEmpty &&
                frameCount == 0L &&
                preallocationResult.method == RecordingPreallocationMethod.POSIX_FALLOCATE
        closed = true
        var failure: Throwable? = null
        var truncateMs = 0.0
        var durableSyncMs = 0.0
        var descriptorCloseMs = 0.0
        try {
            if (!preserveReservation) {
                val truncateStartedNs = System.nanoTime()
                channel.truncate(committedBytes)
                truncateMs = elapsedMs(truncateStartedNs)
            }
            val durableSyncStartedNs = System.nanoTime()
            channel.force(true)
            durableSyncMs = elapsedMs(durableSyncStartedNs)
            preallocationResult = preallocationResult.copy(
                committedBytes = committedBytes,
                reservationTrimmed = !preserveReservation &&
                    preallocationResult.reservedBytes > committedBytes,
                synced = true
            )
        } catch (t: Throwable) {
            failure = t
        } finally {
            val descriptorCloseStartedNs = System.nanoTime()
            try {
                randomAccessFile.close()
            } catch (closeFailure: Throwable) {
                failure = failure?.also { it.addSuppressed(closeFailure) } ?: closeFailure
            } finally {
                descriptorCloseMs = elapsedMs(descriptorCloseStartedNs)
                lastFinalizationTimings = Npv10FileFinalizationTimings(
                    truncateMs = truncateMs,
                    durableSyncMs = durableSyncMs,
                    descriptorCloseMs = descriptorCloseMs,
                    totalMs = elapsedMs(finalizationStartedNs),
                    committedBytes = committedBytes,
                    reservedBytes = preallocationResult.reservedBytes
                )
            }
        }
        failure?.let { throw it }
        return preserveReservation
    }

    private fun writeHeader() {
        writeBytes(Npv10ContainerPolicy.MAGIC_BYTES)
        writeInt(Npv10ContainerPolicy.VERSION)
        writeInt(config.width)
        writeInt(config.height)
        writeInt(config.fps)
        writeInt(config.storageBitDepth)
        val metadata = buildMetadataLine(config).toByteArray(StandardCharsets.UTF_8)
        writeInt(metadata.size)
        writeBytes(metadata)
        markCommittedBoundary()
        channel.force(false)
    }

    private fun preallocate(availableStorageBytes: Long): RecordingPreallocationResult {
        val plan = RecordingPreallocationPolicy.processed(availableStorageBytes)
        if (!plan.enabled) {
            channel.position(0L)
            return RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L
            )
        }
        val result = try {
            Os.posix_fallocate(randomAccessFile.fd, 0L, plan.reservationBytes)
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                reservedBytes = plan.reservationBytes
            )
        } catch (fallocateFailure: Throwable) {
            // A sparse setLength() does not reserve physical blocks. Keep the result truthful so the
            // strict runtime admission gate can reject an unreserved master instead of pretending.
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = fallocateFailure.message
            )
        }
        channel.position(0L)
        return result
    }

    private fun markCommittedBoundary() {
        committedBytes = channel.position()
    }

    private fun writeFrameHeader(
        timestampNs: Long,
        width: Int,
        height: Int,
        payloadSize: Int,
        crcValue: Int
    ) {
        frameHeaderBuffer.clear()
        frameHeaderBuffer.put(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)
        frameHeaderBuffer.putLong(timestampNs)
        frameHeaderBuffer.putInt(width)
        frameHeaderBuffer.putInt(height)
        frameHeaderBuffer.putInt(payloadSize)
        frameHeaderBuffer.putInt(crcValue)
        frameHeaderBuffer.flip()
        writeBuffer(frameHeaderBuffer)
    }

    private fun writeFramePayload(payload: ByteArray) {
        writeBuffer(ByteBuffer.wrap(payload))
    }

    private fun writeFramePayload(payload: ByteBuffer, payloadSize: Int) {
        val duplicate = payload.duplicate()
        duplicate.position(0)
        duplicate.limit(payloadSize)
        writeBuffer(duplicate)
    }

    private fun writeInt(value: Int) {
        val buffer = ByteBuffer.allocateDirect(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(value)
        buffer.flip()
        writeBuffer(buffer)
    }

    private fun writeBytes(bytes: ByteArray) {
        writeBuffer(ByteBuffer.wrap(bytes))
    }

    private fun writeBuffer(buffer: ByteBuffer) {
        var zeroWriteRetries = 0
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written == 0) {
                zeroWriteRetries += 1
                if (zeroWriteRetries > MAX_ZERO_WRITE_RETRIES) {
                    throw IOException("NPV10 writer stalled while writing ${buffer.remaining()} bytes")
                }
                Thread.yield()
            } else {
                zeroWriteRetries = 0
            }
        }
    }

    private fun directP010PayloadBuffer(payloadBytes: Int): ByteBuffer {
        val current = reusableDirectP010Payload
        if (current != null && current.capacity() >= payloadBytes) {
            current.clear()
            current.limit(payloadBytes)
            return current
        }
        return ByteBuffer
            .allocateDirect(payloadBytes)
            .order(ByteOrder.nativeOrder())
            .also { reusableDirectP010Payload = it }
    }

    private fun encodedPacketBuffer(decodedPayloadBytes: Int): ByteBuffer {
        val encodedBytes = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadBytes.toLong(), config.packetCodec)
            .toInt()
        val current = reusableEncodedPacket
        if (current != null && current.capacity() >= encodedBytes) {
            current.clear()
            current.limit(encodedBytes)
            return current
        }
        return ByteBuffer
            .allocateDirect(encodedBytes)
            .order(ByteOrder.nativeOrder())
            .also { reusableEncodedPacket = it }
    }

    @SuppressLint("InlinedApi")
    private fun validateRuntimeP010Image(image: Image) {
        validateRuntimeP010Frame(
            width = image.width,
            height = image.height,
            payloadBytes = Npv10ContainerPolicy
                .estimatedPayloadBytesPerFrame(image.width, image.height, config.payloadFormat)
                .toInt()
        )
        require(image.format == ImageFormat.YCBCR_P010) {
            "Native P010 NPV10 session received image format ${image.format}"
        }
        require(image.planes.size >= 2) {
            "Native P010 NPV10 session received image without Y and UV planes"
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        require(yPlane.rowStride > 0 && uvPlane.rowStride > 0) {
            "Native P010 NPV10 image row strides must be positive"
        }
        require(yPlane.pixelStride >= Short.SIZE_BYTES && uvPlane.pixelStride >= Short.SIZE_BYTES * 2) {
            "Native P010 NPV10 image pixel strides are invalid"
        }
    }

    private fun validateRuntimeP010Frame(width: Int, height: Int, payloadBytes: Int) {
        require(width == config.width && height == config.height) {
            "NPV10 frame ${width}x$height does not match session ${config.width}x${config.height}"
        }
        require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
            "Native P010 NPV10 session requires 10-bit source metadata"
        }
        val expectedPayloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, config.payloadFormat)
            .toInt()
        require(payloadBytes == expectedPayloadBytes) {
            "P010 payload has $payloadBytes bytes, expected $expectedPayloadBytes"
        }
    }

    private fun buildMetadataLine(config: Npv10SessionConfig): String {
        val lines = listOf(
            "container=NPV10",
            "format=${config.payloadFormat.name}",
            "payloadFormat=${config.payloadFormat.name}",
            "packetCodec=${config.packetCodec.name}",
            "packetCodecLossless=true",
            "packetCodecMediaCodecUsed=false",
            "storedPacketsOnly=true",
            "individualFrameFiles=false",
            "mode=${config.mode}",
            "recordingMode=${config.mode}",
            "colorProfile=${config.colorProfile}",
            "selectedColorSpace=${config.resolvedSelectedColorSpace()}",
            "selectedColorScience=${config.resolvedSelectedColorScience()}",
            "colorSelectionSource=${config.colorSelectionSource}",
            "manualColorSelectionRequired=${config.manualColorSelectionRequired}",
            "sourceFormat=${config.sourceFormat}",
            "sourceBitDepth=${config.sourceBitDepth}",
            "storageBitDepth=${config.storageBitDepth}",
            "rawSensorCapture=${config.rawSensorCapture}",
            "sensorPixelModeMaximumResolution=${config.sensorPixelModeMaximumResolution}",
            "gpuDemosaicEnabled=${config.gpuDemosaicEnabled}",
            "darkFrameCalibrationEnabled=${config.darkFrameCalibrationEnabled}",
            "zeroCopyNativeAcquisition=${config.zeroCopyNativeAcquisition}",
            "bayerPattern=${config.bayerPattern}",
            "exactSelectedResolution=${config.exactSelectedResolution}",
            "orientationHintDegrees=${normalizeRightAngle(config.orientationHintDegrees)}",
            "startedAtEpochMs=${config.startedAtEpochMs}",
            "preallocationRequestedBytes=${preallocationResult.plan.requestedBytes}",
            "preallocationReservedBytes=${preallocationResult.reservedBytes}",
            "preallocationSafetyReserveBytes=${preallocationResult.plan.safetyReserveBytes}",
            "preallocationMethod=${preallocationResult.method.name}",
            "committedLengthTruncatedOnClose=true"
        ) + config.rawSidecarMetadata.entries
            .sortedBy { it.key }
            .map { entry -> "raw.${entry.key}=${entry.value}" } +
            config.featureMetadata.entries
            .sortedBy { it.key }
            .map { entry -> "${entry.key}=${entry.value}" } +
            automaticEliteMetadata(config).entries
                .sortedBy { it.key }
                .map { entry -> "${entry.key}=${entry.value}" }
        return lines.joinToString(separator = "\n", postfix = "\n")
            .replace("\r", "")
            .replace("\u0000", "")
            .lowercase(Locale.US)
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "NPV10 orientation must be a right angle: $degrees" }
        return normalized
    }

    private fun automaticEliteMetadata(config: Npv10SessionConfig): Map<String, String> =
        EliteVideoQualityContract.resolve(
            EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = config.width,
                height = config.height,
                fps = config.fps,
                codec = GpuVideoCodec.HEVC,
                logProfile = config.colorProfile,
                sourceBitDepth = config.sourceBitDepth,
                storageBitDepth = config.storageBitDepth,
                sourceFormat = config.sourceFormat,
                payloadFormat = config.payloadFormat,
                hdrMode = config.featureMetadataValue(
                    "hdrMode",
                    "videoHdrMode",
                    "requestedHdrMode",
                    "effectiveHdrMode"
                ),
                tenBitTransport = config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH,
                colorSpace = config.resolvedSelectedColorSpace(),
                colorScience = config.resolvedSelectedColorScience(),
                colorSpaceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                colorScienceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                savedColorSpacePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                ),
                savedColorSciencePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                ),
                exactSelectedResolution = config.exactSelectedResolution
            )
        ).toMetadata()

    private fun Npv10SessionConfig.featureMetadataValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            featureMetadata.entries.firstOrNull { entry ->
                entry.key.equals(key, ignoreCase = true)
            }?.value
        }?.takeIf { it.isNotBlank() } ?: "OFF"

    fun buildDeviceEvidence(
        privateRoot: File,
        droppedFrames: Int,
        generatedAtEpochMs: Long = System.currentTimeMillis()
    ): Npv10DeviceEvidence =
        Npv10IntegrityValidator.buildDeviceEvidence(
            config = config,
            outputFile = outputFile,
            privateRoot = privateRoot,
            firstFrameWidth = firstFrameWidth,
            firstFrameHeight = firstFrameHeight,
            firstFrameBitDepth = firstFrameBitDepth,
            firstFrameTimestampNs = firstFrameTimestampNs,
            frameCount = frameCount,
            droppedFrames = droppedFrames,
            payloadBytes = payloadBytesWritten,
            averageWriteTimeMs = averageWriteTimeMs,
            maxWriteTimeMs = maxWriteTimeMs,
            maxTimestampJitterNs = maxTimestampJitterNs,
            averageTimestampJitterNs = averageTimestampJitterNs,
            sessionHash = "",
            generatedAtEpochMs = generatedAtEpochMs
        )

    private fun updateRuntimeTiming(timestampNs: Long) {
        val previousTimestampNs = lastFrameTimestampNs
        if (previousTimestampNs > 0L && timestampNs > previousTimestampNs) {
            val expectedIntervalNs = 1_000_000_000L / config.fps.coerceAtLeast(1).toLong()
            val jitterNs = abs((timestampNs - previousTimestampNs) - expectedIntervalNs)
            maxTimestampJitterNs = maxOf(maxTimestampJitterNs, jitterNs)
            totalTimestampJitterNs += jitterNs
            timestampJitterSamples += 1L
        }
        if (timestampNs > 0L) {
            lastFrameTimestampNs = timestampNs
        }
    }

    private fun crc32(payload: ByteBuffer, payloadSize: Int): Int {
        val duplicate = payload.duplicate()
        duplicate.position(0)
        duplicate.limit(payloadSize)
        val crc = CRC32()
        crc.update(duplicate)
        return crc.value.toInt()
    }

    private fun elapsedMs(startedNs: Long): Double =
        (System.nanoTime() - startedNs).coerceAtLeast(0L).toDouble() / 1_000_000.0

    private companion object {
        const val FLUSH_FRAME_INTERVAL = 120L
        const val MAX_ZERO_WRITE_RETRIES = 8
    }
}
