package com.neoproc.neoproccamx.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the asynchronous MediaStore query and rendering lifecycle for the Home gallery thumbnail. */
internal class GalleryThumbnailController(
    private val contentResolver: ContentResolver,
    private val galleryButton: ImageView,
    private val scope: CoroutineScope,
    private val logTag: String
) {
    private data class LatestGalleryMedia(
        val uri: Uri,
        val dateAddedSeconds: Long
    )

    private var thumbnailJob: Job? = null
    private var latestThumbnailUri: Uri? = null

    fun refresh(preferredPath: String? = null, forceReload: Boolean = false) {
        val explicitRefresh = forceReload || !preferredPath.isNullOrBlank()
        val targetSizePx =
            galleryButton.width
                .coerceAtLeast(galleryButton.height)
                .coerceAtLeast(160)
        thumbnailJob?.cancel()
        thumbnailJob = scope.launch(Dispatchers.IO) {
            try {
                val latest = queryLatestGalleryMedia()
                if (latest == null) {
                    withContext(Dispatchers.Main.immediate) {
                        latestThumbnailUri = null
                        galleryButton.setImageResource(android.R.drawable.ic_menu_gallery)
                        galleryButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }
                    return@launch
                }
                val alreadyApplied = !explicitRefresh && latestThumbnailUri == latest.uri
                val thumbnail = if (alreadyApplied) {
                    null
                } else {
                    loadThumbnailBitmap(latest, targetSizePx)
                }
                if (!isActive) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if (!isActive) return@withContext
                    latestThumbnailUri = latest.uri
                    if (thumbnail != null) {
                        galleryButton.animate().cancel()
                        galleryButton.alpha = 0f
                        galleryButton.setImageBitmap(thumbnail)
                        galleryButton.scaleType = ImageView.ScaleType.CENTER_CROP
                        galleryButton.animate()
                            .alpha(1f)
                            .setDuration(140L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            } catch (e: Exception) {
                Log.e(logTag, "Failed to refresh gallery thumbnail", e)
            }
        }
    }

    fun cancel() {
        thumbnailJob?.cancel()
    }

    private fun queryLatestGalleryMedia(): LatestGalleryMedia? {
        val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val image = queryLatestFromCollection(collection = imageCollection)
        val video = queryLatestFromCollection(collection = videoCollection)
        return listOfNotNull(image, video).maxByOrNull { it.dateAddedSeconds }
    }

    private fun queryLatestFromCollection(collection: Uri): LatestGalleryMedia? {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val appQuery = CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection(
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        fun runQuery(selection: String?, selectionArgs: Array<String>?): LatestGalleryMedia? {
            return contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val dateAdded =
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                LatestGalleryMedia(
                    uri = ContentUris.withAppendedId(collection, id),
                    dateAddedSeconds = dateAdded
                )
            }
        }
        return runCatching {
            runQuery(appQuery.selection, appQuery.selectionArgs) ?: runQuery(null, null)
        }.getOrNull()
    }

    private fun loadThumbnailBitmap(media: LatestGalleryMedia, targetSizePx: Int): Bitmap? {
        return runCatching {
            contentResolver.loadThumbnail(
                media.uri,
                Size(targetSizePx, targetSizePx),
                null
            )
        }.getOrNull()
    }
}
