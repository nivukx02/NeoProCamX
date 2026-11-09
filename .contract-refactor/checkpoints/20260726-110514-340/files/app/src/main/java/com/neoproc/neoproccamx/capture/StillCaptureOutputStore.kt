package com.neoproc.neoproccamx.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class StillCaptureOutputStore(
    private val context: Context,
    private val debugValidationLabelProvider: () -> String? = { null },
    private val jpegQualityProvider: () -> Int = { OUTPUT_JPEG_QUALITY },
    private val timestampProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "StillCaptureOutput"
        private const val OUTPUT_JPEG_QUALITY = 100
        private const val STRICT_MIN_JPEG_QUALITY = 96
        private const val SOFTWARE_TAG = "NeoProCamX"
        private const val LEGACY_EXIF_TAG_ISO_SPEED_RATINGS = "ISOSpeedRatings"
        private val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            LEGACY_EXIF_TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP
        )

        internal fun buildDisplayName(
            tag: String,
            extension: String,
            timestampMs: Long,
            debugLabel: String?
        ): String {
            val safeTag = tag.ifBlank { "IMG" }.uppercase(Locale.US)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(Date(timestampMs))
            val debugSuffix = debugLabel
                ?.trim()
                ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "_$it" }
                .orEmpty()
            return "CAMX_${safeTag}_${timestamp}${debugSuffix}.$extension"
        }
    }

    private data class SavedOutputHandle(
        val uri: Uri? = null,
        val file: File? = null
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun saveJpegBytes(
        jpegBytes: ByteArray,
        tag: String,
        debugLabel: String? = null,
        preserveExifMetadata: Boolean = true
    ): String? {
        val keepExif = preserveExifMetadata || strictHighEndEnabled()
        return saveBinary(
            tag = tag,
            spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.JPEG),
            debugLabel = debugLabel,
            writer = { out ->
                out.write(jpegBytes)
                out.flush()
            },
            afterWrite = if (keepExif) {
                { handle ->
                    normalizeExifAttributes(
                        sourceExifBytes = jpegBytes,
                        handle = handle,
                        forceNormalizedOrientation = false
                    )
                }
            } else {
                null
            }
        )
    }

    fun saveBitmapAsJpeg(
        bitmap: Bitmap,
        tag: String,
        sourceExifBytes: ByteArray? = null,
        debugLabel: String? = null
    ): String? {
        return saveBinary(
            tag = tag,
            spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.JPEG),
            debugLabel = debugLabel,
            writer = { out ->
                val minimumJpegQuality = if (strictHighEndEnabled()) {
                    STRICT_MIN_JPEG_QUALITY
                } else {
                    50
                }
                val outputJpegQuality = jpegQualityProvider().coerceIn(minimumJpegQuality, 100)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, outputJpegQuality, out)) {
                    error("Bitmap compression failed")
                }
                out.flush()
            },
            afterWrite = { handle ->
                normalizeExifAttributes(
                    sourceExifBytes = sourceExifBytes,
                    handle = handle,
                    forceNormalizedOrientation = true
                )
            }
        )
    }

    fun saveDng(
        tag: String,
        debugLabel: String? = null,
        writer: (OutputStream) -> Unit
    ): String? {
        return saveBinary(
            tag = tag,
            spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.DNG),
            debugLabel = debugLabel,
            writer = writer
        )
    }

    fun saveRawPackage(
        tag: String,
        debugLabel: String? = null,
        writer: (OutputStream) -> Unit
    ): String? {
        return saveBinary(
            tag = tag,
            spec = StillCaptureOutputRouting.resolve(StillCaptureOutputKind.RAW_PACKAGE),
            debugLabel = debugLabel,
            writer = writer
        )
    }

    private fun saveBinary(
        tag: String,
        spec: StillCaptureOutputSpec,
        debugLabel: String? = null,
        writer: (OutputStream) -> Unit,
        afterWrite: ((SavedOutputHandle) -> Unit)? = null
    ): String? {
        val nowMs = timestampProvider()
        val displayName = buildDisplayName(
            tag = tag,
            extension = spec.extension,
            timestampMs = nowMs,
            debugLabel = debugLabel ?: debugValidationLabelProvider()
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePendingMediaStoreBinary(displayName, spec, nowMs, writer, afterWrite)
        } else {
            saveLegacyBinary(displayName, spec, writer, afterWrite)
        }
    }

    private fun savePendingMediaStoreBinary(
        displayName: String,
        spec: StillCaptureOutputSpec,
        nowMs: Long,
        writer: (OutputStream) -> Unit,
        afterWrite: ((SavedOutputHandle) -> Unit)? = null
    ): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, nowMs / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowMs / 1000)
            if (spec.collection == StillCaptureMediaCollection.IMAGES) {
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
            }
        }
        val uri = context.contentResolver.insert(resolveCollectionUri(spec), values)
            ?: return null

        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use(writer)
                ?: error("Unable to open output stream")
            afterWrite?.invoke(SavedOutputHandle(uri = uri))
            val publish = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publish, null, null)
            uri.toString()
        }.getOrElse { error ->
            Log.e(TAG, "savePendingMediaStoreBinary failed", error)
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveLegacyBinary(
        displayName: String,
        spec: StillCaptureOutputSpec,
        writer: (OutputStream) -> Unit,
        afterWrite: ((SavedOutputHandle) -> Unit)? = null
    ): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(spec.legacyRootDirectory),
            spec.legacySubdirectory
        ).apply { mkdirs() }
        val file = File(dir, displayName)

        return runCatching {
            FileOutputStream(file).use(writer)
            afterWrite?.invoke(SavedOutputHandle(file = file))
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(spec.mimeType),
                null
            )
            file.absolutePath
        }.getOrElse { error ->
            Log.e(TAG, "saveLegacyBinary failed", error)
            file.delete()
            null
        }
    }

    private fun normalizeExifAttributes(
        sourceExifBytes: ByteArray?,
        handle: SavedOutputHandle,
        forceNormalizedOrientation: Boolean
    ) {
        val sourceExif = sourceExifBytes?.let { bytes ->
            runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        }
        runCatching {
            when {
                handle.uri != null -> {
                    context.contentResolver.openFileDescriptor(handle.uri, "rw")?.use { pfd ->
                        applyExifAttributes(
                            source = sourceExif,
                            target = ExifInterface(pfd.fileDescriptor),
                            forceNormalizedOrientation = forceNormalizedOrientation
                        )
                    } ?: error("Unable to open destination file descriptor")
                }
                handle.file != null -> {
                    applyExifAttributes(
                        source = sourceExif,
                        target = ExifInterface(handle.file.absolutePath),
                        forceNormalizedOrientation = forceNormalizedOrientation
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "normalizeExifAttributes failed", error)
        }
    }

    private fun applyExifAttributes(
        source: ExifInterface?,
        target: ExifInterface,
        forceNormalizedOrientation: Boolean
    ) {
        for (tag in COPIED_EXIF_TAGS) {
            source?.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        if (forceNormalizedOrientation) {
            target.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
        }
        target.setAttribute(
            ExifInterface.TAG_SOFTWARE,
            source?.getAttribute(ExifInterface.TAG_SOFTWARE) ?: SOFTWARE_TAG
        )
        applyFallbackExifDefaults(target)
        target.saveAttributes()
    }

    private fun applyFallbackExifDefaults(target: ExifInterface) {
        if (target.getAttribute(ExifInterface.TAG_MAKE).isNullOrBlank()) {
            target.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        }
        if (target.getAttribute(ExifInterface.TAG_MODEL).isNullOrBlank()) {
            target.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        }
        val exifTimestamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .format(Date(timestampProvider()))
        if (target.getAttribute(ExifInterface.TAG_DATETIME).isNullOrBlank()) {
            target.setAttribute(ExifInterface.TAG_DATETIME, exifTimestamp)
        }
        if (target.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()) {
            target.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTimestamp)
        }
        if (target.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED).isNullOrBlank()) {
            target.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifTimestamp)
        }
    }

    private fun resolveCollectionUri(spec: StillCaptureOutputSpec) =
        when (spec.collection) {
            StillCaptureMediaCollection.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            StillCaptureMediaCollection.FILES -> MediaStore.Files.getContentUri("external")
        }
}
