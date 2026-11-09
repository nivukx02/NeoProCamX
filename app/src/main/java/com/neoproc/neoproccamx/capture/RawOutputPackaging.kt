package com.neoproc.neoproccamx.capture

import android.graphics.Bitmap
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class RawPackageMetadata(
    val sourceType: String,
    val workflow: String,
    val captureTag: String,
    val nativeRawSupported: Boolean,
    val nativeDngEmbedded: Boolean,
    val includesSourceJpeg: Boolean,
    val includesReferenceJpeg: Boolean,
    val lensFacing: String,
    val sensorOrientation: Int,
    val jpegOrientation: Int,
    val outputAspect: Float,
    val sceneMetadata: String,
    val colorProfile: String,
    val logEnabled: Boolean,
    val lutEnabled: Boolean,
    val lutLabel: String,
    val iso: Int,
    val exposureNs: Long,
    val focusDistance: Float,
    val timestampMs: Long,
    // HIGH-END ENHANCEMENTS
    val bitDepth: Int = 12,                          // 10, 12, 14, or 16-bit
    val compressionMode: String = "LOSSLESS",       // Compression algorithm used
    val compressionRatio: Float = 1.0f,              // Actual compression ratio achieved
    val colorSpace: String = "Rec.709",              // Color space (Rec.709, Rec.2020, ACES, etc.)
    val transferFunction: String = "sRGB",           // Transfer function (sRGB, PQ, HLG, Log, etc.)
    val whiteBalance: Int = 5500,                    // White balance in Kelvin
    val dynamicRange: String = "SDR",                // SDR, NDR10+, NIVUKX Vision, LOG
    val hdrMetadata: String? = null,                 // Neo dynamic metadata sidecar summary if available
    val sensorModel: String = "Unknown",             // Sensor identifier
    val cameraModel: String = "NeoProCamX",          // Camera model
    val softwareVersion: String = "1.0",             // Software version
    val shootingMode: String = "PHOTO",              // PHOTO, VIDEO, BURST, etc.
    val frameRate: Int? = null,                      // For video sequences
    val totalFrames: Int? = null,                    // For video sequences
    val audioCaptured: Boolean = false,              // Audio included
    val gpsLatitude: Double? = null,                 // GPS coordinates
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null,
    val processingPipeline: String = "NATIVE",       // NATIVE, AI_ENHANCED, HDR_MERGED, etc.
    // CP17: recorded distortion-correction geometry policy so downstream/review knows whether the
    // master was captured with sensor-native lens geometry (DISTORTION_CORRECTION OFF) or corrected.
    val distortionPolicy: String = "UNKNOWN",        // SENSOR_NATIVE, CORRECTED, or UNKNOWN
    // CP17: the honest ISP-bypass read-back verdict travelling with the master, not just the logs.
    val ispBypassVerified: String = "NOT_APPLICABLE" // PROVEN, NOT_PROVEN, or NOT_APPLICABLE
)

internal object RawOutputPackaging {

    private const val FORMAT_VERSION = "CAMX_RAW_PACKAGE_V4_CINEMA"  // Upgraded version

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun buildMetadata(metadata: RawPackageMetadata): String = buildString {
        appendMetadataLine("format", FORMAT_VERSION)
        appendMetadataLine("sourceType", metadata.sourceType)
        appendMetadataLine("workflow", metadata.workflow)
        appendMetadataLine("captureTag", metadata.captureTag)
        appendMetadataLine("nativeRawSupported", metadata.nativeRawSupported)
        appendMetadataLine("nativeDngEmbedded", metadata.nativeDngEmbedded)
        appendMetadataLine("lensFacing", metadata.lensFacing)
        appendMetadataLine("sensorOrientation", metadata.sensorOrientation)
        appendMetadataLine("jpegOrientation", metadata.jpegOrientation)
        appendMetadataLine("outputAspect", metadata.outputAspect)
        appendMetadataLine("sceneMetadata", metadata.sceneMetadata)
        appendMetadataLine("colorProfile", metadata.colorProfile)
        appendMetadataLine("logEnabled", metadata.logEnabled)
        appendMetadataLine("lutEnabled", metadata.lutEnabled)
        appendMetadataLine("lutLabel", metadata.lutLabel)
        appendMetadataLine("includesSourceJpeg", metadata.includesSourceJpeg)
        appendMetadataLine("includesReferenceJpeg", metadata.includesReferenceJpeg)
        appendMetadataLine("iso", metadata.iso)
        appendMetadataLine("exposureNs", metadata.exposureNs)
        appendMetadataLine("focusDistance", metadata.focusDistance)
        appendMetadataLine("timestampMs", metadata.timestampMs)
        
        // HIGH-END METADATA
        appendMetadataLine("bitDepth", metadata.bitDepth)
        appendMetadataLine("compressionMode", metadata.compressionMode)
        appendMetadataLine("compressionRatio", metadata.compressionRatio)
        appendMetadataLine("colorSpace", metadata.colorSpace)
        appendMetadataLine("transferFunction", metadata.transferFunction)
        appendMetadataLine("whiteBalance", metadata.whiteBalance)
        appendMetadataLine("dynamicRange", metadata.dynamicRange)
        appendMetadataLine("hdrMetadata", metadata.hdrMetadata)
        appendMetadataLine("sensorModel", metadata.sensorModel)
        appendMetadataLine("cameraModel", metadata.cameraModel)
        appendMetadataLine("softwareVersion", metadata.softwareVersion)
        appendMetadataLine("shootingMode", metadata.shootingMode)
        appendMetadataLine("frameRate", metadata.frameRate)
        appendMetadataLine("totalFrames", metadata.totalFrames)
        appendMetadataLine("audioCaptured", metadata.audioCaptured)
        appendMetadataLine("gpsLatitude", metadata.gpsLatitude)
        appendMetadataLine("gpsLongitude", metadata.gpsLongitude)
        appendMetadataLine("gpsAltitude", metadata.gpsAltitude)
        appendMetadataLine("processingPipeline", metadata.processingPipeline)
        appendMetadataLine("distortionPolicy", metadata.distortionPolicy)
        appendMetadataLine("ispBypassVerified", metadata.ispBypassVerified)
        appendMetadataLine("strictHighEnd", strictHighEndEnabled())
    }

    fun writePackage(
        output: OutputStream,
        metadata: String,
        sourceJpegBytes: ByteArray? = null,
        renderedReference: Bitmap? = null,
        nativeDngWriter: ((OutputStream) -> Unit)? = null
    ) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            writeByteArrayEntry(zip, "metadata.txt", metadata.toByteArray(Charsets.UTF_8))
            sourceJpegBytes?.let { writeByteArrayEntry(zip, "source.jpg", it) }
            renderedReference?.let { writeBitmapEntry(zip, "reference.jpg", it) }
            nativeDngWriter?.let { writer ->
                writeStreamEntry(zip, "capture.dng", writer)
            }
        }
    }

    private fun writeByteArrayEntry(
        zip: ZipOutputStream,
        entryName: String,
        bytes: ByteArray
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeBitmapEntry(
        zip: ZipOutputStream,
        entryName: String,
        bitmap: Bitmap
    ) {
        writeStreamEntry(zip, entryName) { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        }
    }

    private fun writeStreamEntry(
        zip: ZipOutputStream,
        entryName: String,
        writer: (OutputStream) -> Unit
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        val zipStream = object : OutputStream() {
            override fun write(oneByte: Int) {
                zip.write(oneByte)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                zip.write(buffer, offset, length)
            }

            override fun flush() {
                zip.flush()
            }
        }
        writer(zipStream)
        zip.closeEntry()
    }

    private fun StringBuilder.appendMetadataLine(key: String, value: Any?) {
        if (value == null) return
        val encodedValue = value.toString()
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("=", "\\=")
        append(key)
            .append("=")
            .append(encodedValue)
            .appendLine()
    }
}
