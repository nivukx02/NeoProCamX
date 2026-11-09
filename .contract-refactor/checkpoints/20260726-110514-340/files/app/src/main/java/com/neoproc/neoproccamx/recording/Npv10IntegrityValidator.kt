package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import java.io.FileOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale

enum class Npv10AuditStatus {
    PASS,
    FAIL
}

data class Npv10DeviceEvidence(
    val auditStatus: Npv10AuditStatus,
    val qualityTier: Npv10QualityTier,
    val eliteVideoQualityAuditStatus: EliteQualityAuditStatus,
    val eliteVideoQualityTier: EliteVideoQualityTier,
    val eliteNormalizedHdrVersion: String,
    val eliteNormalizedLogVersion: String,
    val eliteTruthfulNative10Bit: Boolean,
    val eliteMinimumBitrateBps: Int,
    val selectedProfile: String,
    val activeTransport: String,
    val transfer: String,
    val primaries: String,
    val matrix: String,
    val encodedBitDepth: Int,
    val internalMathBitDepth: Int,
    val dynamicMetadataWritten: Boolean,
    val npv10MasterAvailable: Boolean,
    val selectedResolution: String,
    val captureResolution: String,
    val sourceFormat: String,
    val androidImageFormat: String,
    val sourceBitDepth: Int,
    val storageBitDepth: Int,
    val payloadFormat: Npv10PayloadFormat,
    val nativeTenBitCapture: Boolean,
    val exactSelectedResolution: Boolean,
    val firstFrameWidth: Int,
    val firstFrameHeight: Int,
    val firstFrameBitDepth: Int,
    val firstFrameTimestampNs: Long,
    val frameCount: Long,
    val droppedFrames: Int,
    val dropRate: Double,
    val payloadBytes: Long,
    val fileBytes: Long,
    val averageWriteTimeMs: Double,
    val maxWriteTimeMs: Double,
    val maxSafeWriteTimeMs: Double,
    val maxTimestampJitterNs: Long,
    val averageTimestampJitterNs: Long,
    val expectedPayloadBytesPerFrame: Long,
    val expectedPayloadBytesTotal: Long,
    val payloadByteCountExact: Boolean,
    val frameHeaderBytes: Int,
    val auditedFrameRecordBytes: Long,
    val expectedFrameRecordBytes: Long,
    val frameRecordByteCountPass: Boolean,
    val sourceBitDepthPolicy: String,
    val sourceBitDepthEvidence: String,
    val sourceBitDepthCheckPass: Boolean,
    val storageBitDepthPolicy: String,
    val storageBitDepthCheckPass: Boolean,
    val sessionHash: String,
    val postRecordSessionHash: String,
    val sessionHashCheckPass: Boolean,
    val postRecordAuditStatus: Npv10AuditStatus,
    val droppedFrameCheckPass: Boolean,
    val timestampCheckPass: Boolean,
    val crcCheckPass: Boolean,
    val byteCountCheckPass: Boolean,
    val writeLatencyCheckPass: Boolean,
    val privateMasterWriterPolicy: String,
    val masterPath: String,
    val privateMaster: Boolean,
    val noMediaPresent: Boolean,
    val generatedAtEpochMs: Long
) {
    fun toEvidenceText(): String =
        listOf(
            "auditStatus=${auditStatus.name}",
            "qualityTier=${qualityTier.name}",
            "eliteVideoQualityAuditStatus=${eliteVideoQualityAuditStatus.name}",
            "eliteVideoQualityTier=${eliteVideoQualityTier.name}",
            "eliteNormalizedHdrVersion=$eliteNormalizedHdrVersion",
            "eliteNormalizedLogVersion=$eliteNormalizedLogVersion",
            "eliteTruthfulNative10Bit=$eliteTruthfulNative10Bit",
            "eliteMinimumBitrateBps=$eliteMinimumBitrateBps",
            "selectedProfile=$selectedProfile",
            "activeTransport=$activeTransport",
            "transfer=$transfer",
            "primaries=$primaries",
            "matrix=$matrix",
            "encodedBitDepth=$encodedBitDepth",
            "internalMathBitDepth=$internalMathBitDepth",
            "dynamicMetadataWritten=$dynamicMetadataWritten",
            "npv10MasterAvailable=$npv10MasterAvailable",
            "selectedResolution=$selectedResolution",
            "captureResolution=$captureResolution",
            "sourceFormat=$sourceFormat",
            "androidImageFormat=$androidImageFormat",
            "sourceBitDepth=$sourceBitDepth",
            "storageBitDepth=$storageBitDepth",
            "payloadFormat=${payloadFormat.name}",
            "nativeTenBitCapture=$nativeTenBitCapture",
            "exactSelectedResolution=$exactSelectedResolution",
            "firstFrameWidth=$firstFrameWidth",
            "firstFrameHeight=$firstFrameHeight",
            "firstFrameBitDepth=$firstFrameBitDepth",
            "firstFrameTimestampNs=$firstFrameTimestampNs",
            "frameCount=$frameCount",
            "droppedFrames=$droppedFrames",
            "dropRate=${"%.6f".format(Locale.US, dropRate)}",
            "payloadBytes=$payloadBytes",
            "fileBytes=$fileBytes",
            "averageWriteTimeMs=${"%.4f".format(Locale.US, averageWriteTimeMs)}",
            "maxWriteTimeMs=${"%.4f".format(Locale.US, maxWriteTimeMs)}",
            "maxSafeWriteTimeMs=${"%.4f".format(Locale.US, maxSafeWriteTimeMs)}",
            "maxTimestampJitterNs=$maxTimestampJitterNs",
            "averageTimestampJitterNs=$averageTimestampJitterNs",
            "expectedPayloadBytesPerFrame=$expectedPayloadBytesPerFrame",
            "expectedPayloadBytesTotal=$expectedPayloadBytesTotal",
            "payloadByteCountExact=$payloadByteCountExact",
            "frameHeaderBytes=$frameHeaderBytes",
            "auditedFrameRecordBytes=$auditedFrameRecordBytes",
            "expectedFrameRecordBytes=$expectedFrameRecordBytes",
            "frameRecordByteCountPass=$frameRecordByteCountPass",
            "sourceBitDepthPolicy=$sourceBitDepthPolicy",
            "sourceBitDepthEvidence=$sourceBitDepthEvidence",
            "sourceBitDepthCheckPass=$sourceBitDepthCheckPass",
            "storageBitDepthPolicy=$storageBitDepthPolicy",
            "storageBitDepthCheckPass=$storageBitDepthCheckPass",
            "sessionHash=$sessionHash",
            "postRecordSessionHash=$postRecordSessionHash",
            "sessionHashCheckPass=$sessionHashCheckPass",
            "postRecordAuditStatus=${postRecordAuditStatus.name}",
            "droppedFrameCheckPass=$droppedFrameCheckPass",
            "timestampCheckPass=$timestampCheckPass",
            "crcCheckPass=$crcCheckPass",
            "byteCountCheckPass=$byteCountCheckPass",
            "writeLatencyCheckPass=$writeLatencyCheckPass",
            "privateMasterWriterPolicy=$privateMasterWriterPolicy",
            "privateMaster=$privateMaster",
            "noMediaPresent=$noMediaPresent",
            "generatedAtEpochMs=$generatedAtEpochMs",
            "masterPath=$masterPath"
        ).joinToString(separator = "\n", postfix = "\n")
}

data class Npv10MovConversionAudit(
    val auditStatus: Npv10AuditStatus,
    val outputPath: String,
    val outputBytes: Long,
    val frameCount: Long,
    val inputPayloadFormat: Npv10PayloadFormat,
    val sourcePayloadFormat: Npv10PayloadFormat = inputPayloadFormat,
    val sourceFormat: String = "UNKNOWN",
    val sourceBitDepth: Int = 0,
    val storageBitDepth: Int = 0,
    val inputContainerVersion: Int = 0,
    val inputMasterHashUnchanged: Boolean = true,
    val sampleEntry: String,
    val validQuickTimeAtoms: Boolean,
    val conversionCertificate: String
) {
    fun toCertificateText(): String = conversionCertificate
}

object Npv10IntegrityValidator {
    fun validateP010PayloadAlignment(payload: ByteArray) {
        require(payload.size % Short.SIZE_BYTES == 0) {
            "P010 payload layout must be 16-bit little-endian aligned"
        }
        var offset = 0
        while (offset + 1 < payload.size) {
            val value = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8)
            require((value and P010_LOW_BITS_MASK) == 0) {
                "P010 payload layout mismatch: low padding bits are not zero"
            }
            offset += Short.SIZE_BYTES
        }
    }

    fun validateP010PayloadAlignment(payload: ByteBuffer) {
        val duplicate = payload.duplicate()
        duplicate.clear()
        require(duplicate.remaining() % Short.SIZE_BYTES == 0) {
            "P010 direct payload layout must be 16-bit aligned"
        }
        while (duplicate.remaining() >= Short.SIZE_BYTES) {
            val lo = duplicate.get().toInt() and 0xFF
            val hi = duplicate.get().toInt() and 0xFF
            val value = lo or (hi shl 8)
            require((value and P010_LOW_BITS_MASK) == 0) {
                "P010 direct payload layout mismatch: low padding bits are not zero"
            }
        }
    }

    fun validateSessionConfig(config: Npv10SessionConfig) {
        require(config.width > 0 && config.height > 0) { "NPV10 session dimensions must be positive" }
        require(config.fps > 0) { "NPV10 session fps must be positive" }
        val requiredStorageBitDepth = config.payloadFormat.rawBitDepth ?: Npv10ContainerPolicy.BIT_DEPTH
        require(config.storageBitDepth == requiredStorageBitDepth) {
            "NPV10 storage bit depth must be $requiredStorageBitDepth"
        }
        require(config.sourceFormat.isNotBlank()) { "NPV10 source format metadata is required" }
        if (config.packetCodec == Npv10PacketCodec.PACKED_P010_10_LE_V1) {
            require(config.payloadFormat == Npv10PayloadFormat.P010_LE) {
                "Packed NPV10 10-bit packets require a P010 payload contract"
            }
            require(config.sourceBitDepth >= Npv10ContainerPolicy.BIT_DEPTH) {
                "Packed NPV10 10-bit packets require a native 10-bit source"
            }
        }
        ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = config.resolvedSelectedColorSpace(),
                colorScience = config.resolvedSelectedColorScience(),
                colorSpaceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                colorScienceSelectedByUser = config.colorSelectionSource == ManualVideoColorSelectionPolicy.USER_MANUAL,
                savedColorSpacePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                ),
                savedColorSciencePreference = ManualVideoColorSelectionPolicy.isApprovedSelectionSource(
                    config.colorSelectionSource
                )
            )
        ).requireReadyForRecording()
        when (config.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> {
                require(config.sourceBitDepth >= 10) {
                    "P010_LE NPV10 payload requires native 10-bit source metadata"
                }
                require(config.sourceFormat.equals(SOURCE_FORMAT_P010, ignoreCase = true)) {
                    "P010_LE NPV10 payload requires YCBCR_P010 source metadata"
                }
            }
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> {
                require(config.sourceBitDepth <= 8) {
                    "YUV420_8BIT_EXPANDED must not be labeled as native 10-bit capture"
                }
            }
            Npv10PayloadFormat.YUV420P10_LE -> {
                require(config.sourceBitDepth >= 10) {
                    "YUV420P10_LE NPV10 payload requires native 10-bit source metadata"
                }
            }
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> {
                require(config.rawSensorCapture) { "RAW12 NPV10 payload requires RAW sensor capture metadata" }
                require(config.sourceBitDepth >= 12) {
                    "RAW12 NPV10 payload requires at least 12-bit source metadata"
                }
                require(config.sourceFormat.equals(SOURCE_FORMAT_RAW_SENSOR, ignoreCase = true)) {
                    "RAW12 NPV10 payload requires RAW_SENSOR source metadata"
                }
            }
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> {
                require(config.rawSensorCapture) { "RAW14 NPV10 payload requires RAW sensor capture metadata" }
                require(config.sourceBitDepth >= 14) {
                    "RAW14 NPV10 payload requires at least 14-bit source metadata"
                }
                require(config.sourceFormat.equals(SOURCE_FORMAT_RAW_PRIVATE, ignoreCase = true)) {
                    "RAW14 NPV10 payload requires RAW_PRIVATE source metadata"
                }
            }
        }
    }

    fun validateRuntimeFrame(config: Npv10SessionConfig, frame: RawYuvFrame) {
        require(frame.width == config.width && frame.height == config.height) {
            "NPV10 frame ${frame.width}x${frame.height} does not match session ${config.width}x${config.height}"
        }
        when (config.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> {
                require(frame.bitDepth >= 10) {
                    "Native P010 NPV10 session received ${frame.bitDepth}-bit frame"
                }
                validateYuv420PlaneLayout(frame, sampleBytes = Short.SIZE_BYTES)
            }
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> {
                require(frame.bitDepth <= 8) {
                    "8-bit-expanded NPV10 session received native ${frame.bitDepth}-bit frame"
                }
                validateYuv420PlaneLayout(frame, sampleBytes = 1)
            }
            Npv10PayloadFormat.YUV420P10_LE -> {
                require(frame.bitDepth >= 10) {
                    "Planar 10-bit NPV10 session received ${frame.bitDepth}-bit frame"
                }
                validateYuv420PlaneLayout(frame, sampleBytes = Short.SIZE_BYTES)
            }
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> {
                require(frame.bitDepth >= 12) {
                    "RAW12 NPV10 session received ${frame.bitDepth}-bit frame"
                }
                validatePlaneLayout(
                    name = "RAW12",
                    bytes = frame.yBytes,
                    rows = frame.height,
                    columns = frame.width,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    sampleBytes = Short.SIZE_BYTES
                )
            }
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> {
                require(frame.bitDepth >= 14) {
                    "RAW14 NPV10 session received ${frame.bitDepth}-bit frame"
                }
                validatePlaneLayout(
                    name = "RAW14",
                    bytes = frame.yBytes,
                    rows = frame.height,
                    columns = frame.width,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    sampleBytes = Short.SIZE_BYTES
                )
            }
        }
    }

    fun buildDeviceEvidence(
        config: Npv10SessionConfig,
        outputFile: File,
        privateRoot: File,
        firstFrameWidth: Int,
        firstFrameHeight: Int,
        firstFrameBitDepth: Int,
        firstFrameTimestampNs: Long,
        frameCount: Long,
        droppedFrames: Int,
        payloadBytes: Long,
        averageWriteTimeMs: Double,
        maxWriteTimeMs: Double,
        maxTimestampJitterNs: Long,
        averageTimestampJitterNs: Long,
        sessionHash: String,
        generatedAtEpochMs: Long
    ): Npv10DeviceEvidence {
        val masterDirectory = Npv10ContainerPolicy.masterDirectory(privateRoot)
        val privateMaster = isWithin(outputFile, masterDirectory) &&
            outputFile.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true)
        val noMediaPresent = File(masterDirectory, ".nomedia").isFile
        val requiredStorageBitDepth = config.payloadFormat.rawBitDepth ?: Npv10ContainerPolicy.BIT_DEPTH
        val expectedPayloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            config.width,
            config.height,
            config.payloadFormat
        )
        val expectedPayloadBytesTotal = expectedPayloadBytes * frameCount
        val nativeTenBitCapture = config.payloadFormat == Npv10PayloadFormat.P010_LE &&
            config.sourceBitDepth >= 10 &&
            config.sourceFormat.equals(SOURCE_FORMAT_P010, ignoreCase = true) &&
            firstFrameBitDepth >= 10
        val rawNativeCapture = config.payloadFormat.isRawPacked &&
            config.rawSensorCapture &&
            config.sourceBitDepth >= requiredStorageBitDepth &&
            firstFrameBitDepth >= requiredStorageBitDepth
        val payloadByteCountExact = frameCount > 0L && payloadBytes == expectedPayloadBytesTotal
        val payloadByteCountValid = payloadByteCountExact
        val frameShapeValid = firstFrameWidth == config.width && firstFrameHeight == config.height
        val dropRate = if (frameCount + droppedFrames <= 0L) {
            0.0
        } else {
            droppedFrames.toDouble() / (frameCount.toDouble() + droppedFrames.toDouble())
        }
        val maxSafeWriteMs = Npv10QualityGate.maxSafeWriteMs(config.fps)
        val droppedFrameCheckPass = droppedFrames == 0
        val expectedSessionHash = sessionHash.takeIf { it.isNotBlank() }
        val postRecordAudit = Npv10QualityGate.auditMasterFile(
            masterFile = outputFile,
            expectedFrameCount = frameCount,
            expectedPayloadBytes = payloadBytes,
            expectedSessionHash = expectedSessionHash
        )
        val resolvedSessionHash = sessionHash.ifBlank { postRecordAudit.sessionHash }
        val sourceBitDepthCheckPass = sourceBitDepthCheckPass(
            config = config,
            firstFrameBitDepth = firstFrameBitDepth,
            requiredStorageBitDepth = requiredStorageBitDepth
        )
        val storageBitDepthCheckPass = config.storageBitDepth == requiredStorageBitDepth
        val timestampCheckPass = postRecordAudit.timestampContinuityValid
        val crcCheckPass = postRecordAudit.crcValidated
        val byteCountCheckPass = postRecordAudit.byteCountValid
        val writeLatencyCheckPass = Npv10QualityGate.writeLatencyWithinRealtimeBudget(
            averageWriteTimeMs = averageWriteTimeMs,
            maxWriteTimeMs = maxWriteTimeMs,
            fps = config.fps,
            droppedFrames = droppedFrames
        )
        val npv10HdrMode = config.featureMetadata["hdrMode"]
            ?.takeIf { !it.equals("OFF", ignoreCase = true) }
            ?: config.featureMetadata["dynamicRange"]
                ?.takeIf { it.contains("NDR10", ignoreCase = true) || it.contains("NIVUKX", ignoreCase = true) }
            ?: "OFF"
        val eliteCertificate = EliteVideoQualityContract.resolve(
            EliteVideoQualityRequest(
                mode = EliteVideoPipelineMode.NPV10,
                width = config.width,
                height = config.height,
                fps = config.fps,
                codec = GpuVideoCodec.HEVC,
                hdrMode = npv10HdrMode,
                logProfile = config.colorProfile,
                sourceBitDepth = config.sourceBitDepth,
                storageBitDepth = config.storageBitDepth,
                sourceFormat = config.sourceFormat,
                payloadFormat = config.payloadFormat,
                tenBitTransport = nativeTenBitCapture || rawNativeCapture,
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
        )
        val truthMetadata = eliteCertificate.toMetadata()
        val truthValid = when (config.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> nativeTenBitCapture
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> config.sourceBitDepth <= 8 && firstFrameBitDepth <= 8
            Npv10PayloadFormat.YUV420P10_LE -> config.sourceBitDepth >= 10 && firstFrameBitDepth >= 10
            Npv10PayloadFormat.RAW12_NPV10_PACKED,
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> rawNativeCapture
        }
        val passed = privateMaster &&
            noMediaPresent &&
            frameCount > 0L &&
            droppedFrameCheckPass &&
            frameShapeValid &&
            payloadByteCountValid &&
            truthValid &&
            timestampCheckPass &&
            crcCheckPass &&
            byteCountCheckPass &&
            postRecordAudit.frameRecordByteCountValid &&
            writeLatencyCheckPass &&
            sourceBitDepthCheckPass &&
            storageBitDepthCheckPass &&
            postRecordAudit.sessionHashCheckPass &&
            postRecordAudit.auditStatus == Npv10AuditStatus.PASS &&
            resolvedSessionHash.length == SHA_256_HEX_LENGTH &&
            postRecordAudit.sessionHash.length == SHA_256_HEX_LENGTH
        return Npv10DeviceEvidence(
            auditStatus = if (passed) Npv10AuditStatus.PASS else Npv10AuditStatus.FAIL,
            qualityTier = if (passed) Npv10QualityGate.tierForConfig(config) else Npv10QualityTier.FAILED_UNSAFE,
            eliteVideoQualityAuditStatus = eliteCertificate.auditStatus,
            eliteVideoQualityTier = eliteCertificate.qualityTier,
            eliteNormalizedHdrVersion = eliteCertificate.normalizedHdrVersion,
            eliteNormalizedLogVersion = eliteCertificate.normalizedLogVersion,
            eliteTruthfulNative10Bit = eliteCertificate.truthfulNative10Bit && (nativeTenBitCapture || rawNativeCapture),
            eliteMinimumBitrateBps = eliteCertificate.minimumBitrateBps,
            selectedProfile = truthMetadata.getValue("selectedProfile"),
            activeTransport = truthMetadata.getValue("activeTransport"),
            transfer = truthMetadata.getValue("transfer"),
            primaries = truthMetadata.getValue("primaries"),
            matrix = truthMetadata.getValue("matrix"),
            encodedBitDepth = truthMetadata.getValue("encodedBitDepth").toInt(),
            internalMathBitDepth = truthMetadata.getValue("internalMathBitDepth").toInt(),
            dynamicMetadataWritten = truthMetadata.getValue("dynamicMetadataWritten").toBoolean(),
            npv10MasterAvailable = truthMetadata.getValue("npv10MasterAvailable").toBoolean(),
            selectedResolution = config.featureMetadata["selectedResolution"] ?: "${config.width}x${config.height}",
            captureResolution = config.featureMetadata["captureResolution"] ?: "${config.width}x${config.height}",
            sourceFormat = config.sourceFormat.lowercase(Locale.US),
            androidImageFormat = config.featureMetadata["sourceImageFormat"] ?: "unknown",
            sourceBitDepth = config.sourceBitDepth,
            storageBitDepth = config.storageBitDepth,
            payloadFormat = config.payloadFormat,
            nativeTenBitCapture = nativeTenBitCapture || rawNativeCapture,
            exactSelectedResolution = config.exactSelectedResolution,
            firstFrameWidth = firstFrameWidth,
            firstFrameHeight = firstFrameHeight,
            firstFrameBitDepth = firstFrameBitDepth,
            firstFrameTimestampNs = firstFrameTimestampNs,
            frameCount = frameCount,
            droppedFrames = droppedFrames,
            dropRate = dropRate,
            payloadBytes = payloadBytes,
            fileBytes = outputFile.length(),
            averageWriteTimeMs = averageWriteTimeMs,
            maxWriteTimeMs = maxWriteTimeMs,
            maxSafeWriteTimeMs = maxSafeWriteMs,
            maxTimestampJitterNs = maxTimestampJitterNs,
            averageTimestampJitterNs = averageTimestampJitterNs,
            expectedPayloadBytesPerFrame = expectedPayloadBytes,
            expectedPayloadBytesTotal = expectedPayloadBytesTotal,
            payloadByteCountExact = payloadByteCountExact,
            frameHeaderBytes = postRecordAudit.frameHeaderBytes,
            auditedFrameRecordBytes = postRecordAudit.auditedFrameRecordBytes,
            expectedFrameRecordBytes = postRecordAudit.expectedFrameRecordBytes,
            frameRecordByteCountPass = postRecordAudit.frameRecordByteCountValid,
            sourceBitDepthPolicy = sourceBitDepthPolicy(config),
            sourceBitDepthEvidence = sourceBitDepthEvidence(
                config = config,
                firstFrameBitDepth = firstFrameBitDepth,
                requiredStorageBitDepth = requiredStorageBitDepth
            ),
            sourceBitDepthCheckPass = sourceBitDepthCheckPass,
            storageBitDepthPolicy = "STORAGE_DEPTH_EQUALS_PAYLOAD_CONTRACT",
            storageBitDepthCheckPass = storageBitDepthCheckPass,
            sessionHash = resolvedSessionHash,
            postRecordSessionHash = postRecordAudit.sessionHash,
            sessionHashCheckPass = postRecordAudit.sessionHashCheckPass,
            postRecordAuditStatus = postRecordAudit.auditStatus,
            droppedFrameCheckPass = droppedFrameCheckPass,
            timestampCheckPass = timestampCheckPass,
            crcCheckPass = crcCheckPass,
            byteCountCheckPass = byteCountCheckPass,
            writeLatencyCheckPass = writeLatencyCheckPass,
            privateMasterWriterPolicy = PRIVATE_MASTER_WRITER_POLICY,
            masterPath = outputFile.absolutePath,
            privateMaster = privateMaster,
            noMediaPresent = noMediaPresent,
            generatedAtEpochMs = generatedAtEpochMs
        )
    }

    fun writeDeviceEvidence(evidence: Npv10DeviceEvidence): File {
        val sidecar = evidenceSidecarFile(File(evidence.masterPath))
        sidecar.parentFile?.mkdirs()
        sidecar.writeTextDurable(evidence.toEvidenceText())
        return sidecar
    }

    fun evidenceSidecarFile(masterFile: File): File =
        File(masterFile.parentFile, "${masterFile.name}.evidence.txt")

    fun auditMovFile(
        output: File,
        frameCount: Long,
        payloadFormat: Npv10PayloadFormat,
        sourceFormat: String = "UNKNOWN",
        sourceBitDepth: Int = 0,
        storageBitDepth: Int = 0,
        inputContainerVersion: Int = 0,
        inputMasterBytes: Long = 0L,
        inputMasterSha256Before: String = "",
        inputMasterSha256After: String = "",
        inputMasterHashUnchanged: Boolean = true,
        movExportTranscodePolicy: String = "DERIVED_V210_MOV_EXPORT_PRIVATE_NPV10_MASTER_UNMODIFIED",
        enhancementAppliedStage: String = "NPV10_EXPORT_ONLY",
        exportChromaSuppressionApplied: Boolean = false,
        exportEdgeAwareDetailApplied: Boolean = false,
        exportMotionCompensatedDenoiseApplied: Boolean = false,
        exportTemporalChromaSmoothingApplied: Boolean = false,
        exportTexturePreservationGuardApplied: Boolean = false,
        exportAdaptiveDetailReconstructionApplied: Boolean = false,
        exportLocalChromaSpikeSmoothingApplied: Boolean = false,
        exportAntiAliasEdgeProtectionApplied: Boolean = false,
        exportV4CinemaFidelityApplied: Boolean = false,
        exportV7EliteFidelityApplied: Boolean = false,
        exportRgbDancingNoiseLockApplied: Boolean = false,
        exportLowLightChromaFreezeApplied: Boolean = false,
        exportMotionGhostGuardApplied: Boolean = false,
        exportEdgeHaloSuppressionApplied: Boolean = false,
        exportMicroContrastDetailRestoreApplied: Boolean = false,
        exportResolutionHonestDetailBoostApplied: Boolean = false,
        exportV8MeasuredCleanDetailApplied: Boolean = false,
        exportRgbVarianceQuarantineApplied: Boolean = false,
        exportShadowChromaDeadbandApplied: Boolean = false,
        exportMotionSafeTemporalFilterApplied: Boolean = false,
        exportSkinTexturePreservationApplied: Boolean = false,
        exportMoireAliasGuardApplied: Boolean = false,
        exportDetailConfidenceGateApplied: Boolean = false,
        exportV9QualityConflictResolverApplied: Boolean = false,
        exportNoisyEdgeSharpeningSuppressorApplied: Boolean = false,
        exportTemporalMotionSmearGuardApplied: Boolean = false,
        exportCleanDetailEscalatorApplied: Boolean = false,
        exportSceneAdaptiveEncoderGuardApplied: Boolean = false,
        exportV10EvidenceCalibratedGateApplied: Boolean = false,
        exportNoisePressureMeterApplied: Boolean = false,
        exportRgbChromaInstabilityMeterApplied: Boolean = false,
        exportMotionSmearRiskMeterApplied: Boolean = false,
        exportEdgeHaloRiskMeterApplied: Boolean = false,
        exportEncoderStressMeterApplied: Boolean = false,
        exportPreviewRecordingParityEvidenceApplied: Boolean = false,
        exportPerceptualResolutionEvidenceApplied: Boolean = false,
        exportEvidenceCalibratedOutputClampApplied: Boolean = false,
        exportV11ConfidenceMatrixApplied: Boolean = false,
        exportSensorGainNoiseClampApplied: Boolean = false,
        exportTemporalStabilityConfidenceApplied: Boolean = false,
        exportChromaEdgeSeparationMapApplied: Boolean = false,
        exportDetailArtifactRiskGateApplied: Boolean = false,
        exportPerceptualUpscaleTruthGuardApplied: Boolean = false,
        exportCodecDetailPreservationGateApplied: Boolean = false,
        exportV12SensorLimitQualityGateApplied: Boolean = false,
        exportReadNoiseFloorEstimatorApplied: Boolean = false,
        exportShadowChromaNeutralityLockApplied: Boolean = false,
        exportTextureEdgeConfidenceFusionApplied: Boolean = false,
        exportMotionAdaptiveDetailHoldApplied: Boolean = false,
        exportPerceptualDetailScalerApplied: Boolean = false,
        exportFinalTruthfulQualityAuditApplied: Boolean = false,
        exportV13SharpnessDetailApplied: Boolean = false,
        exportEdgeMicroContrastLiftApplied: Boolean = false,
        exportFineTextureDetailBoostApplied: Boolean = false,
        exportHaloSafeSharpeningLiftApplied: Boolean = false,
        exportDetailReconstructionApplied: Boolean = false,
        exportResolutionTruth: String = "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED",
        movColorPrimaries: Int = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
        movTransferCharacteristics: Int = QuickTimeColorAtomPolicy.TRANSFER_BT709,
        movMatrixCoefficients: Int = QuickTimeColorAtomPolicy.MATRIX_BT709,
        movFullRange: Boolean = false,
        movSelectedProfile: String = "Rec.709 Standard",
        movSelectedColorSpace: String = "Rec.709",
        movActiveTransport: String = "REC709_SDR",
        exportNoiseMetrics: VideoNoiseMetrics = VideoNoiseMetrics.EMPTY,
        exportProcessingPath: String = "CPU_P010_FALLBACK",
        exportZeroCopy: Boolean = false,
        exportGpuFallbackReason: String = "NPV10_PRIVATE_MASTER_BYTE_PAYLOAD",
        exportGpuUniformSignature: String = "unset",
        masterEnhancementDestructive: Boolean = false,
        proprietaryProfileAuthority: String = SovereignVideoProfilePolicy.AUTHORITY_STANDARD,
        bt2100TransportCarrierOnly: Boolean = false,
        bt2100CarrierMode: String = SovereignVideoProfilePolicy.CARRIER_NONE,
        vendorDynamicRangeProfileUsed: Boolean = false,
        vendorDynamicMetadataWritten: Boolean = false,
        dolbyVisionOrHdr10PlusSubstitutionBlocked: Boolean = true,
        npv10PrivateMasterUnmodified: Boolean = true,
        rawMonitorPipeline: String = SovereignVideoProfilePolicy.NOT_APPLICABLE,
        rawMonitorDestructiveToMaster: Boolean = false,
        previewRecordingNpv10ParityPolicy: String = SovereignVideoProfilePolicy.PARITY_LOCK,
        selectedDetailProfile: String = "LEGACY_UNSPECIFIED",
        exportSubjectMaskAvailable: Boolean = false,
        exportSubjectDetailApplied: Boolean = false,
        detailProcessingPath: String = "LEGACY_GLOBAL_EDGE_DETAIL"
    ): Npv10MovConversionAudit {
        val atomsValid = auditQuickTimeStructure(output, frameCount)
        val passed = output.length() > 0L &&
            frameCount > 0L &&
            atomsValid &&
            inputMasterHashUnchanged &&
            !masterEnhancementDestructive &&
            npv10PrivateMasterUnmodified
        return Npv10MovConversionAudit(
            auditStatus = if (passed) Npv10AuditStatus.PASS else Npv10AuditStatus.FAIL,
            outputPath = output.absolutePath,
            outputBytes = output.length(),
            frameCount = frameCount,
            inputPayloadFormat = payloadFormat,
            sourceFormat = sourceFormat,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            inputContainerVersion = inputContainerVersion,
            inputMasterHashUnchanged = inputMasterHashUnchanged,
            sampleEntry = MOV_SAMPLE_ENTRY_PLAYABLE,
            validQuickTimeAtoms = atomsValid,
            conversionCertificate = buildMovConversionCertificate(
                auditStatus = if (passed) Npv10AuditStatus.PASS else Npv10AuditStatus.FAIL,
                output = output,
                frameCount = frameCount,
                inputPayloadFormat = payloadFormat,
                sourceFormat = sourceFormat,
                sourceBitDepth = sourceBitDepth,
                storageBitDepth = storageBitDepth,
                inputContainerVersion = inputContainerVersion,
                inputMasterBytes = inputMasterBytes,
                inputMasterSha256Before = inputMasterSha256Before,
                inputMasterSha256After = inputMasterSha256After,
                inputMasterHashUnchanged = inputMasterHashUnchanged,
                movExportTranscodePolicy = movExportTranscodePolicy,
                sampleEntry = MOV_SAMPLE_ENTRY_PLAYABLE,
                validQuickTimeAtoms = atomsValid,
                enhancementAppliedStage = enhancementAppliedStage,
                exportChromaSuppressionApplied = exportChromaSuppressionApplied,
                exportEdgeAwareDetailApplied = exportEdgeAwareDetailApplied,
                exportMotionCompensatedDenoiseApplied = exportMotionCompensatedDenoiseApplied,
                exportTemporalChromaSmoothingApplied = exportTemporalChromaSmoothingApplied,
                exportTexturePreservationGuardApplied = exportTexturePreservationGuardApplied,
                exportAdaptiveDetailReconstructionApplied = exportAdaptiveDetailReconstructionApplied,
                exportLocalChromaSpikeSmoothingApplied = exportLocalChromaSpikeSmoothingApplied,
                exportAntiAliasEdgeProtectionApplied = exportAntiAliasEdgeProtectionApplied,
                exportV4CinemaFidelityApplied = exportV4CinemaFidelityApplied,
                exportV7EliteFidelityApplied = exportV7EliteFidelityApplied,
                exportRgbDancingNoiseLockApplied = exportRgbDancingNoiseLockApplied,
                exportLowLightChromaFreezeApplied = exportLowLightChromaFreezeApplied,
                exportMotionGhostGuardApplied = exportMotionGhostGuardApplied,
                exportEdgeHaloSuppressionApplied = exportEdgeHaloSuppressionApplied,
                exportMicroContrastDetailRestoreApplied = exportMicroContrastDetailRestoreApplied,
                exportResolutionHonestDetailBoostApplied = exportResolutionHonestDetailBoostApplied,
                exportV8MeasuredCleanDetailApplied = exportV8MeasuredCleanDetailApplied,
                exportRgbVarianceQuarantineApplied = exportRgbVarianceQuarantineApplied,
                exportShadowChromaDeadbandApplied = exportShadowChromaDeadbandApplied,
                exportMotionSafeTemporalFilterApplied = exportMotionSafeTemporalFilterApplied,
                exportSkinTexturePreservationApplied = exportSkinTexturePreservationApplied,
                exportMoireAliasGuardApplied = exportMoireAliasGuardApplied,
                exportDetailConfidenceGateApplied = exportDetailConfidenceGateApplied,
                exportV9QualityConflictResolverApplied = exportV9QualityConflictResolverApplied,
                exportNoisyEdgeSharpeningSuppressorApplied = exportNoisyEdgeSharpeningSuppressorApplied,
                exportTemporalMotionSmearGuardApplied = exportTemporalMotionSmearGuardApplied,
                exportCleanDetailEscalatorApplied = exportCleanDetailEscalatorApplied,
                exportSceneAdaptiveEncoderGuardApplied = exportSceneAdaptiveEncoderGuardApplied,
                exportV10EvidenceCalibratedGateApplied = exportV10EvidenceCalibratedGateApplied,
                exportNoisePressureMeterApplied = exportNoisePressureMeterApplied,
                exportRgbChromaInstabilityMeterApplied = exportRgbChromaInstabilityMeterApplied,
                exportMotionSmearRiskMeterApplied = exportMotionSmearRiskMeterApplied,
                exportEdgeHaloRiskMeterApplied = exportEdgeHaloRiskMeterApplied,
                exportEncoderStressMeterApplied = exportEncoderStressMeterApplied,
                exportPreviewRecordingParityEvidenceApplied = exportPreviewRecordingParityEvidenceApplied,
                exportPerceptualResolutionEvidenceApplied = exportPerceptualResolutionEvidenceApplied,
                exportEvidenceCalibratedOutputClampApplied = exportEvidenceCalibratedOutputClampApplied,
                exportV11ConfidenceMatrixApplied = exportV11ConfidenceMatrixApplied,
                exportSensorGainNoiseClampApplied = exportSensorGainNoiseClampApplied,
                exportTemporalStabilityConfidenceApplied = exportTemporalStabilityConfidenceApplied,
                exportChromaEdgeSeparationMapApplied = exportChromaEdgeSeparationMapApplied,
                exportDetailArtifactRiskGateApplied = exportDetailArtifactRiskGateApplied,
                exportPerceptualUpscaleTruthGuardApplied = exportPerceptualUpscaleTruthGuardApplied,
                exportCodecDetailPreservationGateApplied = exportCodecDetailPreservationGateApplied,
                exportV12SensorLimitQualityGateApplied = exportV12SensorLimitQualityGateApplied,
                exportReadNoiseFloorEstimatorApplied = exportReadNoiseFloorEstimatorApplied,
                exportShadowChromaNeutralityLockApplied = exportShadowChromaNeutralityLockApplied,
                exportTextureEdgeConfidenceFusionApplied = exportTextureEdgeConfidenceFusionApplied,
                exportMotionAdaptiveDetailHoldApplied = exportMotionAdaptiveDetailHoldApplied,
                exportPerceptualDetailScalerApplied = exportPerceptualDetailScalerApplied,
                exportFinalTruthfulQualityAuditApplied = exportFinalTruthfulQualityAuditApplied,
                exportV13SharpnessDetailApplied = exportV13SharpnessDetailApplied,
                exportEdgeMicroContrastLiftApplied = exportEdgeMicroContrastLiftApplied,
                exportFineTextureDetailBoostApplied = exportFineTextureDetailBoostApplied,
                exportHaloSafeSharpeningLiftApplied = exportHaloSafeSharpeningLiftApplied,
                exportDetailReconstructionApplied = exportDetailReconstructionApplied,
                exportResolutionTruth = exportResolutionTruth,
                movColorPrimaries = movColorPrimaries,
                movTransferCharacteristics = movTransferCharacteristics,
                movMatrixCoefficients = movMatrixCoefficients,
                movFullRange = movFullRange,
                movSelectedProfile = movSelectedProfile,
                movSelectedColorSpace = movSelectedColorSpace,
                movActiveTransport = movActiveTransport,
                exportNoiseMetrics = exportNoiseMetrics,
                exportProcessingPath = exportProcessingPath,
                exportZeroCopy = exportZeroCopy,
                exportGpuFallbackReason = exportGpuFallbackReason,
                exportGpuUniformSignature = exportGpuUniformSignature,
                masterEnhancementDestructive = masterEnhancementDestructive,
                proprietaryProfileAuthority = proprietaryProfileAuthority,
                bt2100TransportCarrierOnly = bt2100TransportCarrierOnly,
                bt2100CarrierMode = bt2100CarrierMode,
                vendorDynamicRangeProfileUsed = vendorDynamicRangeProfileUsed,
                vendorDynamicMetadataWritten = vendorDynamicMetadataWritten,
                dolbyVisionOrHdr10PlusSubstitutionBlocked = dolbyVisionOrHdr10PlusSubstitutionBlocked,
                npv10PrivateMasterUnmodified = npv10PrivateMasterUnmodified,
                rawMonitorPipeline = rawMonitorPipeline,
                rawMonitorDestructiveToMaster = rawMonitorDestructiveToMaster,
                previewRecordingNpv10ParityPolicy = previewRecordingNpv10ParityPolicy,
                selectedDetailProfile = selectedDetailProfile,
                exportSubjectMaskAvailable = exportSubjectMaskAvailable,
                exportSubjectDetailApplied = exportSubjectDetailApplied,
                detailProcessingPath = detailProcessingPath
            )
        )
    }

    private fun buildMovConversionCertificate(
        auditStatus: Npv10AuditStatus,
        output: File,
        frameCount: Long,
        inputPayloadFormat: Npv10PayloadFormat,
        sourceFormat: String,
        sourceBitDepth: Int,
        storageBitDepth: Int,
        inputContainerVersion: Int,
        inputMasterBytes: Long,
        inputMasterSha256Before: String,
        inputMasterSha256After: String,
        inputMasterHashUnchanged: Boolean,
        movExportTranscodePolicy: String,
        sampleEntry: String,
        validQuickTimeAtoms: Boolean,
        enhancementAppliedStage: String,
        exportChromaSuppressionApplied: Boolean,
        exportEdgeAwareDetailApplied: Boolean,
        exportMotionCompensatedDenoiseApplied: Boolean,
        exportTemporalChromaSmoothingApplied: Boolean,
        exportTexturePreservationGuardApplied: Boolean,
        exportAdaptiveDetailReconstructionApplied: Boolean,
        exportLocalChromaSpikeSmoothingApplied: Boolean,
        exportAntiAliasEdgeProtectionApplied: Boolean,
        exportV4CinemaFidelityApplied: Boolean,
        exportV7EliteFidelityApplied: Boolean,
        exportRgbDancingNoiseLockApplied: Boolean,
        exportLowLightChromaFreezeApplied: Boolean,
        exportMotionGhostGuardApplied: Boolean,
        exportEdgeHaloSuppressionApplied: Boolean,
        exportMicroContrastDetailRestoreApplied: Boolean,
        exportResolutionHonestDetailBoostApplied: Boolean,
        exportV8MeasuredCleanDetailApplied: Boolean,
        exportRgbVarianceQuarantineApplied: Boolean,
        exportShadowChromaDeadbandApplied: Boolean,
        exportMotionSafeTemporalFilterApplied: Boolean,
        exportSkinTexturePreservationApplied: Boolean,
        exportMoireAliasGuardApplied: Boolean,
        exportDetailConfidenceGateApplied: Boolean,
        exportV9QualityConflictResolverApplied: Boolean,
        exportNoisyEdgeSharpeningSuppressorApplied: Boolean,
        exportTemporalMotionSmearGuardApplied: Boolean,
        exportCleanDetailEscalatorApplied: Boolean,
        exportSceneAdaptiveEncoderGuardApplied: Boolean,
        exportV10EvidenceCalibratedGateApplied: Boolean,
        exportNoisePressureMeterApplied: Boolean,
        exportRgbChromaInstabilityMeterApplied: Boolean,
        exportMotionSmearRiskMeterApplied: Boolean,
        exportEdgeHaloRiskMeterApplied: Boolean,
        exportEncoderStressMeterApplied: Boolean,
        exportPreviewRecordingParityEvidenceApplied: Boolean,
        exportPerceptualResolutionEvidenceApplied: Boolean,
        exportEvidenceCalibratedOutputClampApplied: Boolean,
        exportV11ConfidenceMatrixApplied: Boolean,
        exportSensorGainNoiseClampApplied: Boolean,
        exportTemporalStabilityConfidenceApplied: Boolean,
        exportChromaEdgeSeparationMapApplied: Boolean,
        exportDetailArtifactRiskGateApplied: Boolean,
        exportPerceptualUpscaleTruthGuardApplied: Boolean,
        exportCodecDetailPreservationGateApplied: Boolean,
        exportV12SensorLimitQualityGateApplied: Boolean,
        exportReadNoiseFloorEstimatorApplied: Boolean,
        exportShadowChromaNeutralityLockApplied: Boolean,
        exportTextureEdgeConfidenceFusionApplied: Boolean,
        exportMotionAdaptiveDetailHoldApplied: Boolean,
        exportPerceptualDetailScalerApplied: Boolean,
        exportFinalTruthfulQualityAuditApplied: Boolean,
        exportV13SharpnessDetailApplied: Boolean,
        exportEdgeMicroContrastLiftApplied: Boolean,
        exportFineTextureDetailBoostApplied: Boolean,
        exportHaloSafeSharpeningLiftApplied: Boolean,
        exportDetailReconstructionApplied: Boolean,
        exportResolutionTruth: String,
        movColorPrimaries: Int,
        movTransferCharacteristics: Int,
        movMatrixCoefficients: Int,
        movFullRange: Boolean,
        movSelectedProfile: String,
        movSelectedColorSpace: String,
        movActiveTransport: String,
        exportNoiseMetrics: VideoNoiseMetrics,
        exportProcessingPath: String,
        exportZeroCopy: Boolean,
        exportGpuFallbackReason: String,
        exportGpuUniformSignature: String,
        masterEnhancementDestructive: Boolean,
        proprietaryProfileAuthority: String,
        bt2100TransportCarrierOnly: Boolean,
        bt2100CarrierMode: String,
        vendorDynamicRangeProfileUsed: Boolean,
        vendorDynamicMetadataWritten: Boolean,
        dolbyVisionOrHdr10PlusSubstitutionBlocked: Boolean,
        npv10PrivateMasterUnmodified: Boolean,
        rawMonitorPipeline: String,
        rawMonitorDestructiveToMaster: Boolean,
        previewRecordingNpv10ParityPolicy: String,
        selectedDetailProfile: String,
        exportSubjectMaskAvailable: Boolean,
        exportSubjectDetailApplied: Boolean,
        detailProcessingPath: String
    ): String =
        listOf(
            "auditStatus=${auditStatus.name}",
            "inputPayloadFormat=${inputPayloadFormat.name}",
            "sourceFormat=$sourceFormat",
            "sourceBitDepth=$sourceBitDepth",
            "storageBitDepth=$storageBitDepth",
            "inputContainerVersion=$inputContainerVersion",
            "inputMasterBytes=$inputMasterBytes",
            "inputMasterSha256Before=$inputMasterSha256Before",
            "inputMasterSha256After=$inputMasterSha256After",
            "inputMasterHashUnchanged=$inputMasterHashUnchanged",
            "movExportTranscodePolicy=$movExportTranscodePolicy",
            "movExportSourceBitDepthEvidence=format=${inputPayloadFormat.name};sourceFormat=$sourceFormat;" +
                "sourceBitDepth=$sourceBitDepth;storageBitDepth=$storageBitDepth",
            "sampleEntry=$sampleEntry",
            "frameCount=$frameCount",
            "outputBytes=${output.length()}",
            "validQuickTimeAtoms=$validQuickTimeAtoms",
            "enhancementAppliedStage=$enhancementAppliedStage",
            "exportChromaSuppressionApplied=$exportChromaSuppressionApplied",
            "exportEdgeAwareDetailApplied=$exportEdgeAwareDetailApplied",
            "exportMotionCompensatedDenoiseApplied=$exportMotionCompensatedDenoiseApplied",
            "exportTemporalChromaSmoothingApplied=$exportTemporalChromaSmoothingApplied",
            "exportTexturePreservationGuardApplied=$exportTexturePreservationGuardApplied",
            "exportAdaptiveDetailReconstructionApplied=$exportAdaptiveDetailReconstructionApplied",
            "exportLocalChromaSpikeSmoothingApplied=$exportLocalChromaSpikeSmoothingApplied",
            "exportAntiAliasEdgeProtectionApplied=$exportAntiAliasEdgeProtectionApplied",
            "exportV4CinemaFidelityApplied=$exportV4CinemaFidelityApplied",
            "exportV7EliteFidelityApplied=$exportV7EliteFidelityApplied",
            "exportRgbDancingNoiseLockApplied=$exportRgbDancingNoiseLockApplied",
            "exportLowLightChromaFreezeApplied=$exportLowLightChromaFreezeApplied",
            "exportMotionGhostGuardApplied=$exportMotionGhostGuardApplied",
            "exportEdgeHaloSuppressionApplied=$exportEdgeHaloSuppressionApplied",
            "exportMicroContrastDetailRestoreApplied=$exportMicroContrastDetailRestoreApplied",
            "exportResolutionHonestDetailBoostApplied=$exportResolutionHonestDetailBoostApplied",
            "exportV8MeasuredCleanDetailApplied=$exportV8MeasuredCleanDetailApplied",
            "exportRgbVarianceQuarantineApplied=$exportRgbVarianceQuarantineApplied",
            "exportShadowChromaDeadbandApplied=$exportShadowChromaDeadbandApplied",
            "exportMotionSafeTemporalFilterApplied=$exportMotionSafeTemporalFilterApplied",
            "exportSkinTexturePreservationApplied=$exportSkinTexturePreservationApplied",
            "exportMoireAliasGuardApplied=$exportMoireAliasGuardApplied",
            "exportDetailConfidenceGateApplied=$exportDetailConfidenceGateApplied",
            "exportV9QualityConflictResolverApplied=$exportV9QualityConflictResolverApplied",
            "exportNoisyEdgeSharpeningSuppressorApplied=$exportNoisyEdgeSharpeningSuppressorApplied",
            "exportTemporalMotionSmearGuardApplied=$exportTemporalMotionSmearGuardApplied",
            "exportCleanDetailEscalatorApplied=$exportCleanDetailEscalatorApplied",
            "exportSceneAdaptiveEncoderGuardApplied=$exportSceneAdaptiveEncoderGuardApplied",
            "exportV10EvidenceCalibratedGateApplied=$exportV10EvidenceCalibratedGateApplied",
            "exportNoisePressureMeterApplied=$exportNoisePressureMeterApplied",
            "exportRgbChromaInstabilityMeterApplied=$exportRgbChromaInstabilityMeterApplied",
            "exportMotionSmearRiskMeterApplied=$exportMotionSmearRiskMeterApplied",
            "exportEdgeHaloRiskMeterApplied=$exportEdgeHaloRiskMeterApplied",
            "exportEncoderStressMeterApplied=$exportEncoderStressMeterApplied",
            "exportPreviewRecordingParityEvidenceApplied=$exportPreviewRecordingParityEvidenceApplied",
            "exportPerceptualResolutionEvidenceApplied=$exportPerceptualResolutionEvidenceApplied",
            "exportEvidenceCalibratedOutputClampApplied=$exportEvidenceCalibratedOutputClampApplied",
            "exportV11ConfidenceMatrixApplied=$exportV11ConfidenceMatrixApplied",
            "exportSensorGainNoiseClampApplied=$exportSensorGainNoiseClampApplied",
            "exportTemporalStabilityConfidenceApplied=$exportTemporalStabilityConfidenceApplied",
            "exportChromaEdgeSeparationMapApplied=$exportChromaEdgeSeparationMapApplied",
            "exportDetailArtifactRiskGateApplied=$exportDetailArtifactRiskGateApplied",
            "exportPerceptualUpscaleTruthGuardApplied=$exportPerceptualUpscaleTruthGuardApplied",
            "exportCodecDetailPreservationGateApplied=$exportCodecDetailPreservationGateApplied",
            "exportV12SensorLimitQualityGateApplied=$exportV12SensorLimitQualityGateApplied",
            "exportReadNoiseFloorEstimatorApplied=$exportReadNoiseFloorEstimatorApplied",
            "exportShadowChromaNeutralityLockApplied=$exportShadowChromaNeutralityLockApplied",
            "exportTextureEdgeConfidenceFusionApplied=$exportTextureEdgeConfidenceFusionApplied",
            "exportMotionAdaptiveDetailHoldApplied=$exportMotionAdaptiveDetailHoldApplied",
            "exportPerceptualDetailScalerApplied=$exportPerceptualDetailScalerApplied",
            "exportFinalTruthfulQualityAuditApplied=$exportFinalTruthfulQualityAuditApplied",
            "exportV13SharpnessDetailApplied=$exportV13SharpnessDetailApplied",
            "exportEdgeMicroContrastLiftApplied=$exportEdgeMicroContrastLiftApplied",
            "exportFineTextureDetailBoostApplied=$exportFineTextureDetailBoostApplied",
            "exportHaloSafeSharpeningLiftApplied=$exportHaloSafeSharpeningLiftApplied",
            "exportDetailReconstructionApplied=$exportDetailReconstructionApplied",
            "exportResolutionTruth=$exportResolutionTruth",
            "movColorPrimaries=$movColorPrimaries",
            "movTransferCharacteristics=$movTransferCharacteristics",
            "movMatrixCoefficients=$movMatrixCoefficients",
            "movFullRange=$movFullRange",
            "movSelectedProfile=$movSelectedProfile",
            "movSelectedColorSpace=$movSelectedColorSpace",
            "movActiveTransport=$movActiveTransport",
            "exportChromaNoiseMetric=${formatMetric(exportNoiseMetrics.chromaNoise)}",
            "exportLumaNoiseMetric=${formatMetric(exportNoiseMetrics.lumaNoise)}",
            "exportTemporalInstabilityMetric=${formatMetric(exportNoiseMetrics.temporalInstability)}",
            "exportFrameToFrameRgbVarianceMetric=${formatMetric(exportNoiseMetrics.frameToFrameRgbVariance)}",
            "exportProcessingPath=$exportProcessingPath",
            "exportZeroCopy=$exportZeroCopy",
            "exportGpuFallbackReason=$exportGpuFallbackReason",
            "exportGpuUniformSignature=$exportGpuUniformSignature",
            "masterEnhancementDestructive=$masterEnhancementDestructive",
            "proprietaryProfileAuthority=$proprietaryProfileAuthority",
            "bt2100TransportCarrierOnly=$bt2100TransportCarrierOnly",
            "bt2100CarrierMode=$bt2100CarrierMode",
            "vendorDynamicRangeProfileUsed=$vendorDynamicRangeProfileUsed",
            "vendorDynamicMetadataWritten=$vendorDynamicMetadataWritten",
            "dolbyVisionOrHdr10PlusSubstitutionBlocked=$dolbyVisionOrHdr10PlusSubstitutionBlocked",
            "npv10PrivateMasterUnmodified=$npv10PrivateMasterUnmodified",
            "rawMonitorPipeline=$rawMonitorPipeline",
            "rawMonitorDestructiveToMaster=$rawMonitorDestructiveToMaster",
            "previewRecordingNpv10ParityPolicy=$previewRecordingNpv10ParityPolicy",
            "selectedDetailProfile=$selectedDetailProfile",
            "exportSubjectMaskAvailable=$exportSubjectMaskAvailable",
            "exportSubjectDetailApplied=$exportSubjectDetailApplied",
            "detailProcessingPath=$detailProcessingPath",
            "outputPath=${output.absolutePath}"
        ).joinToString(separator = "\n", postfix = "\n")

    private fun sourceBitDepthPolicy(config: Npv10SessionConfig): String =
        when (config.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> "NATIVE_YCBCR_P010_SOURCE_10BIT_OR_HIGHER"
            Npv10PayloadFormat.YUV420P10_LE -> "PLANAR_YUV420P10_SOURCE_10BIT_OR_HIGHER"
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> "TRUTHFUL_8BIT_SOURCE_EXPANDED_TO_NPV10_STORAGE"
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> "RAW_SENSOR_SOURCE_12BIT_PACKED_PRIVATE_MASTER"
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> "RAW_PRIVATE_SOURCE_14BIT_PACKED_PRIVATE_MASTER"
        }

    private fun validateYuv420PlaneLayout(frame: RawYuvFrame, sampleBytes: Int) {
        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        validatePlaneLayout(
            name = "Y",
            bytes = frame.yBytes,
            rows = frame.height,
            columns = frame.width,
            rowStride = frame.yRowStride,
            pixelStride = frame.yPixelStride,
            sampleBytes = sampleBytes
        )
        validatePlaneLayout(
            name = "U",
            bytes = frame.uBytes,
            rows = chromaHeight,
            columns = chromaWidth,
            rowStride = frame.uRowStride,
            pixelStride = frame.uPixelStride,
            sampleBytes = sampleBytes
        )
        validatePlaneLayout(
            name = "V",
            bytes = frame.vBytes,
            rows = chromaHeight,
            columns = chromaWidth,
            rowStride = frame.vRowStride,
            pixelStride = frame.vPixelStride,
            sampleBytes = sampleBytes
        )
    }

    private fun validatePlaneLayout(
        name: String,
        bytes: ByteArray,
        rows: Int,
        columns: Int,
        rowStride: Int,
        pixelStride: Int,
        sampleBytes: Int
    ) {
        require(rows > 0 && columns > 0) { "NPV10 $name plane dimensions must be positive" }
        require(rowStride > 0) { "NPV10 $name plane row stride must be positive" }
        require(pixelStride > 0) { "NPV10 $name plane pixel stride must be positive" }
        val lastByteExclusive = (rows - 1).toLong() * rowStride.toLong() +
            (columns - 1).toLong() * pixelStride.toLong() +
            sampleBytes.toLong()
        require(lastByteExclusive <= bytes.size.toLong()) {
            "NPV10 $name plane is truncated: requires $lastByteExclusive bytes, has ${bytes.size}"
        }
    }

    private fun sourceBitDepthEvidence(
        config: Npv10SessionConfig,
        firstFrameBitDepth: Int,
        requiredStorageBitDepth: Int
    ): String =
        "format=${config.payloadFormat.name};sourceFormat=${config.sourceFormat};" +
            "sourceBitDepth=${config.sourceBitDepth};firstFrameBitDepth=$firstFrameBitDepth;" +
            "storageBitDepth=${config.storageBitDepth};requiredStorageBitDepth=$requiredStorageBitDepth"

    private fun sourceBitDepthCheckPass(
        config: Npv10SessionConfig,
        firstFrameBitDepth: Int,
        requiredStorageBitDepth: Int
    ): Boolean =
        when (config.payloadFormat) {
            Npv10PayloadFormat.P010_LE ->
                config.sourceFormat.equals(SOURCE_FORMAT_P010, ignoreCase = true) &&
                    config.sourceBitDepth >= 10 &&
                    firstFrameBitDepth >= 10
            Npv10PayloadFormat.YUV420P10_LE ->
                config.sourceBitDepth >= 10 &&
                    firstFrameBitDepth >= 10
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED ->
                config.sourceBitDepth <= 8 &&
                    firstFrameBitDepth <= 8
            Npv10PayloadFormat.RAW12_NPV10_PACKED ->
                config.rawSensorCapture &&
                    config.sourceFormat.equals(SOURCE_FORMAT_RAW_SENSOR, ignoreCase = true) &&
                    config.sourceBitDepth >= 12 &&
                    firstFrameBitDepth >= 12 &&
                    requiredStorageBitDepth == 12
            Npv10PayloadFormat.RAW14_NPV10_PACKED ->
                config.rawSensorCapture &&
                    config.sourceFormat.equals(SOURCE_FORMAT_RAW_PRIVATE, ignoreCase = true) &&
                    config.sourceBitDepth >= 14 &&
                    firstFrameBitDepth >= 14 &&
                    requiredStorageBitDepth == 14
        }

    private fun formatMetric(value: Float): String =
        String.format(Locale.US, "%.6f", value.coerceIn(0f, 1f))

    private fun isWithin(file: File, directory: File): Boolean {
        val filePath = file.canonicalFile.toPath()
        val directoryPath = directory.canonicalFile.toPath()
        return filePath.startsWith(directoryPath)
    }

    /**
     * Real QuickTime/ISO-BMFF atom-box walk. The old fileContainsAscii scan passed ANY file that
     * merely contained the ASCII markers anywhere; this parses the actual box tree with a seeking
     * RandomAccessFile (never slurps the whole file) and requires: top-level ftyp+moov+mdat whose
     * declared sizes stay inside the file; moov->trak->mdia->minf->stbl; a v210 sample-entry box
     * inside stsd; stsz sample_count == recorded frameCount; every stco/co64 chunk offset inside
     * the file. Handles 64-bit largesize (size==1) and size==0-to-EOF, matching QuickTimeMovFileWriter
     * (which writes mdat in largesize form and co64 chunk offsets).
     */
    private fun auditQuickTimeStructure(file: File, expectedFrameCount: Long): Boolean {
        if (!file.isFile || expectedFrameCount <= 0L) return false
        val fileLength = file.length()
        if (fileLength < MOV_MIN_BOX_HEADER_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val topLevel = readBoxChain(raf, 0L, fileLength) ?: return false
                val topTypes = topLevel.mapTo(HashSet()) { it.type }
                if (!topTypes.containsAll(setOf("ftyp", "moov", "mdat"))) return false
                val moov = topLevel.firstOrNull { it.type == "moov" } ?: return false

                val trak = firstChild(raf, moov, "trak") ?: return false
                val mdia = firstChild(raf, trak, "mdia") ?: return false
                val minf = firstChild(raf, mdia, "minf") ?: return false
                val stbl = firstChild(raf, minf, "stbl") ?: return false

                val stsd = firstChild(raf, stbl, "stsd") ?: return false
                firstChild(raf, stsd, MOV_SAMPLE_ENTRY_PLAYABLE) ?: return false

                val stsz = firstChild(raf, stbl, "stsz") ?: return false
                val sampleCount = readStszSampleCount(raf, stsz) ?: return false
                if (sampleCount != expectedFrameCount) return false

                val chunkOffsets = firstChild(raf, stbl, "co64")
                    ?: firstChild(raf, stbl, "stco")
                    ?: return false
                chunkOffsetsWithinFile(raf, chunkOffsets, fileLength)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private data class MovBox(
        val type: String,
        val start: Long,
        val size: Long,
        val headerBytes: Int
    ) {
        val payloadStart: Long get() = start + headerBytes
        val end: Long get() = start + size
    }

    /** Reads one box header at [pos], honoring 64-bit largesize (size==1) and size==0 (to EOF). */
    private fun readBox(raf: RandomAccessFile, pos: Long, limit: Long): MovBox? {
        if (pos < 0L || pos + MOV_MIN_BOX_HEADER_BYTES > limit) return null
        raf.seek(pos)
        val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val type = String(typeBytes, Charsets.US_ASCII)
        var size = size32
        var headerBytes = 8
        when (size32) {
            1L -> {
                if (pos + 16L > limit) return null
                size = raf.readLong()
                headerBytes = 16
            }
            0L -> size = limit - pos
        }
        if (size < headerBytes.toLong() || pos + size > limit) return null
        return MovBox(type = type, start = pos, size = size, headerBytes = headerBytes)
    }

    /** Walks the sibling box chain in [start, limit); returns null on any malformed box. */
    private fun readBoxChain(raf: RandomAccessFile, start: Long, limit: Long): List<MovBox>? {
        val boxes = mutableListOf<MovBox>()
        var pos = start
        while (pos + MOV_MIN_BOX_HEADER_BYTES <= limit) {
            val box = readBox(raf, pos, limit) ?: return null
            boxes += box
            if (box.size <= 0L) return null
            pos = box.end
        }
        return boxes
    }

    /**
     * First child box of [parent] with [childType]. stsd is a FullBox: its sample-entry children
     * start after the 8-byte version/flags(4) + entry_count(4) prefix.
     */
    private fun firstChild(raf: RandomAccessFile, parent: MovBox, childType: String): MovBox? {
        var pos = if (parent.type == "stsd") parent.payloadStart + 8L else parent.payloadStart
        val end = parent.end
        while (pos + MOV_MIN_BOX_HEADER_BYTES <= end) {
            val box = readBox(raf, pos, end) ?: return null
            if (box.type == childType) return box
            if (box.size <= 0L) return null
            pos = box.end
        }
        return null
    }

    /** stsz payload = version/flags(4) sample_size(4) sample_count(4); returns sample_count. */
    private fun readStszSampleCount(raf: RandomAccessFile, stsz: MovBox): Long? {
        val sampleCountPos = stsz.payloadStart + 8L
        if (sampleCountPos + 4L > stsz.end) return null
        raf.seek(sampleCountPos)
        return raf.readInt().toLong() and 0xFFFFFFFFL
    }

    /** co64/stco payload = version/flags(4) entry_count(4) then N offsets (8B co64, 4B stco). */
    private fun chunkOffsetsWithinFile(raf: RandomAccessFile, box: MovBox, fileLength: Long): Boolean {
        val entryCountPos = box.payloadStart + 4L
        if (entryCountPos + 4L > box.end) return false
        raf.seek(entryCountPos)
        val entryCount = raf.readInt()
        if (entryCount < 0) return false
        val wide = box.type == "co64"
        val entryBytes = if (wide) 8L else 4L
        val tableStart = entryCountPos + 4L
        if (tableStart + entryCount.toLong() * entryBytes > box.end) return false
        raf.seek(tableStart)
        repeat(entryCount) {
            val offset = if (wide) raf.readLong() else raf.readInt().toLong() and 0xFFFFFFFFL
            if (offset < 0L || offset >= fileLength) return false
        }
        return true
    }

    private fun File.writeTextDurable(text: String) {
        FileOutputStream(this).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private const val SOURCE_FORMAT_P010 = "YCBCR_P010"
    private const val SOURCE_FORMAT_RAW_SENSOR = "RAW_SENSOR"
    private const val SOURCE_FORMAT_RAW_PRIVATE = "RAW_PRIVATE"
    private const val P010_LOW_BITS_MASK = 0x3F
    private const val MOV_SAMPLE_ENTRY_PLAYABLE = "v210"
    private const val MOV_MIN_BOX_HEADER_BYTES = 8L
    private const val SHA_256_HEX_LENGTH = 64
    private const val PRIVATE_MASTER_WRITER_POLICY =
        "APP_PRIVATE_NPV10_MASTER_WRITER_V3_DIRECT_P010_POST_RECORD_HASHED_CLOSE_FLUSH"
}
