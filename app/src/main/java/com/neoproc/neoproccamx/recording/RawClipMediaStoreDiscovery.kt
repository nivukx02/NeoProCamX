package com.neoproc.neoproccamx.recording

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * RawClipMediaStoreDiscovery — enumerates the app's OWN recorded 12-bit RAW clips via MediaStore,
 * the only discovery that works under Android Q+ scoped storage.
 *
 * WHY not a File scan: [RawVideoManager] writes each clip's `stream.npraw` + `develop.meta` as
 * `MediaStore.Files` entries (application/octet-stream) under RELATIVE_PATH `<DCIM|Documents>/CamX/
 * NEO_RADIANCE_RAW_<ts>/`. On a scoped-storage device (this app: targetSdk 36, no all-files access)
 * a raw `File.listFiles()` of shared storage returns nothing, so the legacy [Raw12MasterStore] file
 * scan yields an empty clip list on-device. Querying MediaStore for the app's own entries is the
 * robust path; the artifacts are opened via [android.content.ContentResolver.openInputStream].
 *
 * A clip is listed when BOTH `stream.npraw` and `develop.meta` are present. Owner-visible pending
 * streams are marked so CameraViewModel can recover a process-death reservation tail and publish
 * only a CRC-valid clip; an actively recording pending stream is filtered out there.
 */
class RawClipMediaStoreDiscovery(private val context: Context) {

    /** A discovered RAW clip, addressed by content URIs the converter/thumbnail/DNG paths open. */
    data class RawClipRef(
        val name: String,
        val relativePath: String,
        val createdAtEpochMs: Long,
        val streamBytes: Long,
        val streamUri: Uri,
        val metaUri: Uri,
        /** True when a process death left the owner-visible stream awaiting recovery/publication. */
        val streamPending: Boolean = false,
        /** Every MediaStore entry under this clip's folder — used to delete the whole clip. */
        val memberUris: List<Uri>,
        /** Checksummed AAC captured by this exact RAW master, never an unrelated gallery track. */
        val audioUri: Uri? = null
    )

    fun listClips(): List<RawClipRef> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val base = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING
        )
        // Only this app's own, published clip artifacts under a CamX RAW session folder.
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(context.packageName, "%CamX/$SESSION_PREFIX%")

        val folders = LinkedHashMap<String, FolderAccumulator>()
        try {
            context.contentResolver.query(base, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val relativePath = cursor.getString(pathCol) ?: continue
                    val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                    val dateSec = if (cursor.isNull(dateCol)) 0L else cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(base, id)

                    val acc = folders.getOrPut(relativePath) { FolderAccumulator(relativePath) }
                    acc.members.add(uri)
                    acc.latestDateMs = maxOf(acc.latestDateMs, dateSec * 1000L)
                    when (displayName) {
                        STREAM_NAME -> {
                            acc.streamUri = uri
                            acc.streamBytes = size
                            acc.streamPending = cursor.getInt(pendingCol) != 0
                        }
                        META_NAME -> acc.metaUri = uri
                        AUDIO_NAME -> if (cursor.getInt(pendingCol) == 0) acc.audioUri = uri
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore RAW clip discovery failed: ${t.message}")
            return emptyList()
        }

        return folders.values
            .mapNotNull { acc ->
                val stream = acc.streamUri ?: return@mapNotNull null
                val meta = acc.metaUri ?: return@mapNotNull null
                RawClipRef(
                    name = folderName(acc.relativePath),
                    relativePath = acc.relativePath,
                    createdAtEpochMs = acc.latestDateMs,
                    streamBytes = acc.streamBytes,
                    streamUri = stream,
                    metaUri = meta,
                    streamPending = acc.streamPending,
                    memberUris = acc.members.toList(),
                    audioUri = acc.audioUri
                )
            }
            .sortedByDescending { it.createdAtEpochMs }
    }

    /** Delete every MediaStore entry that belongs to [clip]'s folder. Returns true if all removed. */
    fun deleteClip(clip: RawClipRef): Boolean {
        var ok = true
        for (uri in clip.memberUris) {
            try {
                val removed = context.contentResolver.delete(uri, null, null)
                if (removed <= 0) ok = false
            } catch (t: Throwable) {
                Log.w(TAG, "Delete failed for $uri: ${t.message}")
                ok = false
            }
        }
        return ok
    }

    private class FolderAccumulator(val relativePath: String) {
        var streamUri: Uri? = null
        var metaUri: Uri? = null
        var audioUri: Uri? = null
        var streamBytes: Long = 0L
        var streamPending: Boolean = false
        var latestDateMs: Long = 0L
        val members = ArrayList<Uri>()
    }

    private fun folderName(relativePath: String): String =
        relativePath.trim('/').substringAfterLast('/').ifEmpty { relativePath.trim('/') }

    companion object {
        private const val TAG = "RawClipDiscovery"
        private const val SESSION_PREFIX = "NEO_RADIANCE_RAW_"
        const val STREAM_NAME = "stream.npraw"
        const val META_NAME = "develop.meta"
        const val AUDIO_NAME = "stream.npraw.audio.npv10a"
    }
}
