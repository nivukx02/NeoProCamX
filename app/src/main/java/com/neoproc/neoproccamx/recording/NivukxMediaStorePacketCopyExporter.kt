package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal data class NivukxPublishedMov(
    val uri: Uri,
    val displayName: String,
    val remux: NivukxPacketCopyResult
)

/** Publishes a direct NIVUKX packet-copy MOV without a temporary file or second MediaStore copy. */
internal class NivukxMediaStorePacketCopyExporter(
    context: Context
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    @SuppressLint("UsableSpace")
    fun export(
        sourceUri: Uri,
        shouldContinue: () -> Boolean = { true },
        onProgress: (NivukxPacketCopyProgress) -> Unit = {}
    ): NivukxPublishedMov {
        if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
        val source = querySource(sourceUri)
        val expectedOutputBytes = source.sizeBytes
            .takeIf { it > 0L }
            ?.let { bytes -> Math.addExact(bytes, maxOf(16L * 1024L * 1024L, bytes / 100L)) }
            ?: ConversionStorageAdmissionPolicy.MIN_OUTPUT_HEADROOM_BYTES
        ConversionStorageAdmissionPolicy.requireAdmission(
            availableBytes = ConversionStorageAdmissionPolicy.externalAvailableBytes(),
            expectedOutputBytes = expectedOutputBytes,
            label = "NIVUKX packet-copy MOV"
        )
        val nowMs = System.currentTimeMillis()
        val requestedDisplayName = outputDisplayName(source.displayName)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, requestedDisplayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1_000L)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CamX")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(
                MediaStore.Video.Media.DESCRIPTION,
                "NIVUKX packet-copy MOV; no decode; no re-encode; original coded precision and HDR metadata"
            )
        }
        val outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create pending NIVUKX MOV export")

        try {
            val remux = resolver.openFileDescriptor(sourceUri, "r")?.use { inputDescriptor ->
                resolver.openFileDescriptor(outputUri, "rw")?.use { outputDescriptor ->
                    if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
                    val inputPath = File("/proc/self/fd/${inputDescriptor.fd}")
                    val outputPath = File("/proc/self/fd/${outputDescriptor.fd}")
                    val sourceBytes = source.sizeBytes
                        .takeIf { it > 0L }
                        ?: inputDescriptor.statSize.coerceAtLeast(inputPath.length())
                    val requestedBytes = sourceBytes
                        .takeIf { it > 0L }
                        ?.let { bytes ->
                            Math.addExact(bytes, maxOf(16L * 1024L * 1024L, bytes / 100L))
                        }
                        ?: 0L
                    val availableBytes = runCatching {
                        Environment.getExternalStorageDirectory().usableSpace
                    }.getOrDefault(0L)
                    val preallocation = RecordingPreallocationPolicy.offlineConversion(
                        availableBytes = availableBytes,
                        estimatedPayloadBytes = requestedBytes
                    )
                    NivukxPacketCopyMovRemuxer.remux(
                        input = inputPath,
                        output = outputPath,
                        preallocationPlan = preallocation,
                        shouldContinue = shouldContinue,
                        onProgress = onProgress
                    )
                } ?: throw IOException("Unable to open pending MOV destination")
            } ?: throw IOException("Unable to open NIVUKX source")

            if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
            val publishedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
                put(MediaStore.Video.Media.DURATION, remux.durationUs / 1_000L)
                put(
                    MediaStore.Video.Media.DESCRIPTION,
                    "NIVUKX packet-copy MOV; integrity=SHA-256+CRC32C; transcoded=false; " +
                        "samples=${remux.sampleCount}; codecs=${remux.codecMimes.joinToString("+")}"
                )
            }
            var publishedRows = runCatching {
                resolver.update(outputUri, publishedValues, null, null)
            }.getOrDefault(0)
            if (publishedRows <= 0) {
                // Some MediaStore providers treat DURATION as scanner-owned. The MOV is already fully
                // fsynced, so retry the minimum portable publication instead of deleting valid media.
                publishedRows = runCatching {
                    resolver.update(
                        outputUri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1_000L)
                        },
                        null,
                        null
                    )
                }.getOrDefault(0)
            }
            if (publishedRows <= 0) {
                throw IOException("Unable to publish completed NIVUKX MOV export")
            }
            val storedName = queryDisplayName(outputUri) ?: requestedDisplayName
            Log.i(
                TAG,
                "Published packet-copy MOV uri=$outputUri samples=${remux.sampleCount} " +
                    "throughput=${"%.1f".format(remux.payloadThroughputMiBPerSecond)}MiB/s"
            )
            return NivukxPublishedMov(
                uri = outputUri,
                displayName = storedName,
                remux = remux
            )
        } catch (failure: Throwable) {
            runCatching {
                val deleted = resolver.delete(outputUri, null, null)
                check(deleted > 0) { "MediaStore did not roll back NIVUKX output $outputUri" }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun querySource(uri: Uri): SourceDescription {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                SourceDescription(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                )
            }
        }.getOrNull() ?: SourceDescription(displayName = null, sizeBytes = 0L)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        }
    }.getOrNull()

    private fun outputDisplayName(sourceName: String?): String {
        val base = sourceName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.trim()
            ?.take(96)
            ?.takeIf { it.isNotEmpty() }
            ?: "CamX NIVUKX"
        return "${base}_PACKET_COPY.mov"
    }

    private data class SourceDescription(
        val displayName: String?,
        val sizeBytes: Long
    )

    private companion object {
        const val TAG = "NivukxPacketCopy"
    }
}
