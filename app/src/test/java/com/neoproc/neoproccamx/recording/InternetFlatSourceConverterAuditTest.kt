package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.VideoDevelopColorMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class InternetFlatSourceConverterAuditTest {
    private val width = 352
    private val height = 288
    private val fps = 30
    private val frames = 30

    @Test
    fun auditDownloadedFlatSourceThroughConverters() {
        val input = File(
            System.getProperty("converterAuditP010")
                ?: "_diag/converter_internet/akiyo_352x288_30f_p010le.yuv"
        ).resolveFromTestWorkingDir()
        assumeTrue("converter audit P010 input exists: ${input.absolutePath}", input.isFile)
        val outputDir = File(
            System.getProperty("converterAuditOutputDir")
                ?: "_diag/converter_internet/outputs"
        ).resolveFromTestWorkingDir().apply {
            deleteRecursively()
            mkdirs()
        }
        val p010Frames = readP010Frames(input)
        require(p010Frames.size == frames) { "expected $frames frames, got ${p010Frames.size}" }

        val summary = StringBuilder()
        listOf(
            "sdr_r210" to RecordDevelopMasterKind.SDR to RecordDevelopConversionOptions(
                deliveryFormat = RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV
            ) to null,
            "sdr_v210" to RecordDevelopMasterKind.SDR to RecordDevelopConversionOptions(
                deliveryFormat = RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV
            ) to null,
            "ndr_r210" to RecordDevelopMasterKind.NDR to RecordDevelopConversionOptions(
                deliveryFormat = RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV
            ) to sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG),
            "ndr_v210" to RecordDevelopMasterKind.NDR to RecordDevelopConversionOptions(
                deliveryFormat = RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV
            ) to sidecar(DevelopColorSpace.REC2020, DevelopColorTransfer.HLG)
        ).forEach { (triple, sidecar) ->
            val (nameAndKind, options) = triple
            val (name, kind) = nameAndKind
            val output = File(outputDir, "$name.mov")
            val audit = RecordDevelopMovConverter.convertWithAudit(
                master = File(outputDir, "$name.input_marker").apply { writeText("internet-flat-source") },
                output = output,
                sidecar = sidecar,
                options = options,
                sourceKind = kind,
                readerFactory = { InMemoryP010FrameReader(p010Frames, RecordDevelopDecodedFormat(width, height, fps)) }
            )
            summary.appendLine("$name frames=${audit.frameCount} bytes=${audit.outputBytes} target=${audit.developTarget} format=${audit.deliveryFormat.auditLabel} gpu=${audit.gpuAccelerated}")
            require(output.length() > 0L) { "$name output empty" }
        }

        val rawStream = ByteArrayOutputStream()
        p010Frames.forEach { frame ->
            rawStream.write(
                NprawFrameCodec.encodeFrame(
                    mode = RawCompressionMode.HIGH_COMPRESSION,
                    rawSamples = p010LumaToRawSamples(frame.p010),
                    width = width,
                    height = height,
                    rowStride = width * 2,
                    pixelStride = 2,
                    timestamp = RAW_TIMESTAMP_ORIGIN_NS + frame.index * 1_000_000_000L / fps,
                    originalSize = width * height * 2,
                    sensorWhiteLevel = 1000
                ).bytes
            )
        }
        val rawMeta = RawDevelopMetadata(
            width = width,
            height = height,
            storageBitDepth = 12,
            sensorWhiteLevel = 1000,
            blackLevel = 0,
            whiteLevel = 1000,
            bayerPatternValue = 0,
            whiteBalanceR = 1f,
            whiteBalanceGr = 1f,
            whiteBalanceGb = 1f,
            whiteBalanceB = 1f,
            exposureTimeNs = 0L,
            analogGain = 1f,
            digitalGain = 1f,
            noiseS = 0f,
            noiseO = 0f,
            sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
            fps = fps,
            colorProfileTag = "N-LOG-BASE"
        )
        val rawCoordinator = Raw12ClipConversionCoordinator()
        listOf(
            "raw_log_r210" to RawToNeoLogMovConverter.OutputFormat.MASTER_444,
            "raw_log_v210" to RawToNeoLogMovConverter.OutputFormat.COMPAT_V210
        ).forEach { (name, format) ->
            val output = File(outputDir, "$name.mov")
            val result = rawCoordinator.convert(
                streamInput = ByteArrayInputStream(rawStream.toByteArray()),
                meta = rawMeta,
                options = RawToNeoLogMovConverter.Options(format = format),
                outputPath = output.absolutePath
            )
            summary.appendLine("$name frames=${result.framesConverted} bytes=${result.bytesWritten} format=$format")
            require(output.length() > 0L) { "$name output empty" }
        }
        File(outputDir, "audit_summary.txt").writeText(summary.toString())
    }

    private fun readP010Frames(input: File): List<RecordDevelopP010Frame> {
        val frameBytes = width * height * 2 + ((width + 1) / 2) * ((height + 1) / 2) * 4
        val bytes = input.readBytes()
        require(bytes.size >= frameBytes * frames) {
            "P010 input too small: ${bytes.size}, need ${frameBytes * frames}"
        }
        return (0 until frames).map { index ->
            val start = index * frameBytes
            RecordDevelopP010Frame(
                index = index.toLong(),
                presentationTimeUs = index * 1_000_000L / fps,
                width = width,
                height = height,
                p010 = bytes.copyOfRange(start, start + frameBytes)
            )
        }
    }

    private fun p010LumaToRawSamples(p010: ByteArray): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val offset = i * 2
            val word = (p010[offset].toInt() and 0xFF) or ((p010[offset + 1].toInt() and 0xFF) shl 8)
            out[i] = ((word ushr 6) * 1000 / 1023).coerceIn(0, 1000)
        }
        return out
    }

    private fun sidecar(space: DevelopColorSpace, transfer: DevelopColorTransfer): VideoDevelopColorMetadata {
        val matrix = if (space == DevelopColorSpace.REC2020) {
            SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf()
        } else {
            SnceIlluminantEstimator.REC709_TO_XYZ.copyOf()
        }
        return VideoDevelopColorMetadata(
            width = width,
            height = height,
            storageBitDepth = 10,
            fps = fps,
            whiteBalanceR = 1f,
            whiteBalanceGr = 1f,
            whiteBalanceGb = 1f,
            whiteBalanceB = 1f,
            workingRgbToXyz = matrix,
            adaptingLuminance = 0f,
            cctKelvin = 5500f,
            tint = 0f,
            colorSpace = space,
            colorTransfer = transfer,
            colorProfileTag = "internet-flat-source"
        )
    }

    private companion object {
        const val RAW_TIMESTAMP_ORIGIN_NS = 8_000_000_000L
    }

    private fun File.resolveFromTestWorkingDir(): File {
        if (isAbsolute || exists()) return canonicalFile
        val appParentCandidate = File("..", path)
        if (appParentCandidate.exists()) return appParentCandidate.canonicalFile
        return canonicalFile
    }
}
