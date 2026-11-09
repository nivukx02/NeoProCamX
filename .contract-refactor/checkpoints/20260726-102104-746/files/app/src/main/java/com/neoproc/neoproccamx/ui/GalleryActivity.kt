package com.neoproc.neoproccamx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import java.util.Locale

/**
 * GalleryActivity — Shows recent CamX captures.
 */
class GalleryActivity : AppCompatActivity(), CameraHapticPerformer {

    private lateinit var recyclerView: RecyclerView
    private lateinit var galleryAdapter: GalleryAdapter
    private var mediaObserverJob: Job? = null
    private var lastLoadedMaxDateAddedSeconds: Long = 0L
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    private val hapticExecutor: CameraHapticFeedbackExecutor by lazy {
        CameraHapticFeedbackExecutor(recyclerView) { vibrator }
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasGalleryAccess()) {
            loadMedia()
        } else {
            Toast.makeText(this, "Gallery access permission is required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requiredGalleryPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasGalleryAccess(): Boolean =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                (hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                    hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) ||
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                    hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
            else ->
                hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun ensureGalleryAccessAndLoad() {
        if (hasGalleryAccess()) {
            loadMedia()
            return
        }
        val missingPermissions = requiredGalleryPermissions()
            .filterNot(::hasPermission)
            .toTypedArray()
        if (missingPermissions.isEmpty()) {
            loadMedia()
            return
        }
        galleryPermissionLauncher.launch(missingPermissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Gallery"
        }

        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, GalleryLayoutPolicy.spanCount(context, strictHighEndEnabled()))
            setHasFixedSize(true)
            itemAnimator?.changeDuration = 120L
        }
        galleryAdapter = GalleryAdapter(emptyList(), contentResolver) { uri ->
            openMedia(uri)
        }.also { adapter ->
            adapter.onHaptic = ::performCameraHaptic
        }
        recyclerView.adapter = galleryAdapter
        setContentView(recyclerView)

        ensureGalleryAccessAndLoad()
        startMediaObserver()
    }

    private fun loadMedia() {
        scope.launch {
            val items = withContext(Dispatchers.IO) { queryMedia() }
            lastLoadedMaxDateAddedSeconds = items.maxOfOrNull { it.dateAddedSeconds } ?: 0L
            galleryAdapter.submitItems(items)
            startMediaObserver()
        }
    }

    private fun queryMedia(minDateAddedExclusive: Long? = null): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()

        fun queryCollection(
            collection: Uri,
            displayNameColumn: String,
            mimeType: String
        ) {
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(displayNameColumn)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.DATE_ADDED)
                add(MediaStore.MediaColumns.MIME_TYPE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    add(MediaStore.MediaColumns.DATA)
                }
            }.toTypedArray()

            val baseQuery =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    CameraMediaLibraryQueryPolicy.mediaStoreRelativePathSelection(
                        MediaStore.MediaColumns.RELATIVE_PATH
                    )
                } else {
                    CameraMediaLibraryQueryPolicy.legacyFileSelection(
                        dataColumn = MediaStore.MediaColumns.DATA,
                        displayNameColumn = displayNameColumn
                    )
                }
            val query = minDateAddedExclusive
                ?.takeIf { it > 0L }
                ?.let {
                    CameraMediaLibraryQueryPolicy.withDateAddedAfter(
                        base = baseQuery,
                        dateAddedColumn = MediaStore.MediaColumns.DATE_ADDED,
                        minDateAddedSeconds = it
                    )
                }
                ?: baseQuery
            val selection = query.selection
            val selectionArgs = query.selectionArgs
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

            try {
                contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(displayNameColumn)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val mimeTypeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol)
                        val size = cursor.getLong(sizeCol)
                        val dateAddedSeconds = cursor.getLong(dateAddedCol)
                        val resolvedMimeType = if (mimeTypeCol >= 0) {
                            cursor.getString(mimeTypeCol)
                        } else {
                            null
                        } ?: mimeType
                        val uri = ContentUris.withAppendedId(collection, id)
                        val lowercaseName = name.lowercase(Locale.US)
                        val isRawAsset =
                            lowercaseName.endsWith(".dng") ||
                                lowercaseName.endsWith(".npraw") ||
                                lowercaseName.endsWith(".npv10") ||
                                resolvedMimeType.contains("dng", ignoreCase = true)
                        val isLogAsset =
                            lowercaseName.contains("n-log") ||
                                lowercaseName.contains("nlog") ||
                                lowercaseName.contains("log")
                        val isHdrAsset =
                            lowercaseName.contains("ndr10") ||
                                lowercaseName.contains("nivukx") ||
                                lowercaseName.contains("hdr")
                        val isTenBitAsset =
                            lowercaseName.contains("10bit") ||
                                lowercaseName.contains("10-bit") ||
                                lowercaseName.contains("p010")
                        items.add(
                            GalleryItem(
                                uri = uri,
                                name = name,
                                size = size,
                                dateAddedSeconds = dateAddedSeconds,
                                isRaw = isRawAsset,
                                isVideo = resolvedMimeType.startsWith("video/"),
                                isLog = isLogAsset,
                                isHdr = isHdrAsset,
                                isTenBit = isTenBitAsset,
                                mimeType = resolvedMimeType
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Permission can be revoked while querying; keep gallery responsive.
            }
        }

        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        queryCollection(imageCollection, MediaStore.Images.Media.DISPLAY_NAME, "image/*")
        queryCollection(videoCollection, MediaStore.Video.Media.DISPLAY_NAME, "video/*")

        return items
            .sortedWith(
                compareByDescending<GalleryItem> { it.dateAddedSeconds }
                    .thenByDescending { if (strictHighEndEnabled()) (it.isRaw || it.isVideo) else false }
                    .thenByDescending { it.name }
            )
    }

    private fun openMedia(uri: Uri) {
        val item = recyclerView.adapter
            ?.let { adapter -> (adapter as? GalleryAdapter)?.items?.firstOrNull { it.uri == uri } }
        startActivity(
            Intent(this, MediaPreviewActivity::class.java).apply {
                putExtra(MediaPreviewActivity.EXTRA_URI, uri.toString())
                putExtra(MediaPreviewActivity.EXTRA_MIME_TYPE, item?.mimeType ?: contentResolver.getType(uri))
            }
        )
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startMediaObserver() {
        if (!hasGalleryAccess()) return
        mediaObserverJob?.cancel()
        mediaObserverJob = mediaStoreChanges()
            .debounce(CameraMediaLibraryQueryPolicy.MEDIA_OBSERVER_DEBOUNCE_MS)
            .let { flow ->
                scope.launch {
                    flow.collect {
                        refreshIncrementalMedia()
                    }
                }
            }
    }

    private fun mediaStoreChanges() = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    private suspend fun refreshIncrementalMedia() {
        val newItems = withContext(Dispatchers.IO) {
            queryMedia(minDateAddedExclusive = lastLoadedMaxDateAddedSeconds)
        }
        if (newItems.isEmpty()) return
        val merged = (newItems + galleryAdapter.items)
            .distinctBy { it.uri }
            .sortedWith(
                compareByDescending<GalleryItem> { it.dateAddedSeconds }
                    .thenByDescending { if (strictHighEndEnabled()) (it.isRaw || it.isVideo) else false }
                    .thenByDescending { it.name }
            )
        lastLoadedMaxDateAddedSeconds = merged.maxOfOrNull { it.dateAddedSeconds } ?: lastLoadedMaxDateAddedSeconds
        galleryAdapter.submitItems(merged)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaObserverJob?.cancel()
        scope.cancel()
    }

    override fun performCameraHaptic(action: CameraHapticAction) {
        hapticExecutor.perform(action)
    }
}

data class GalleryItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAddedSeconds: Long,
    val isRaw: Boolean,
    val isVideo: Boolean,
    val isLog: Boolean = false,
    val isHdr: Boolean = false,
    val isTenBit: Boolean = false,
    val mimeType: String
)

internal object GalleryLayoutPolicy {
    const val ASPECT_RATIO_16_9 = 16f / 9f

    fun spanCount(context: Context, strictHighEnd: Boolean): Int {
        val widthDp = context.resources.configuration.screenWidthDp.takeIf { it > 0 }
            ?: (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
        return when {
            widthDp >= 840 -> if (strictHighEnd) 5 else 4
            widthDp >= 600 -> 4
            else -> 3
        }
    }

    fun cardHeight(parentWidth: Int, spanCount: Int): Int {
        val width = (parentWidth / spanCount.coerceAtLeast(1)).coerceAtLeast(1)
        return (width / ASPECT_RATIO_16_9).toInt().coerceAtLeast(148)
    }
}

class GalleryAdapter(
    initialItems: List<GalleryItem>,
    private val contentResolver: ContentResolver,
    private val onClick: (Uri) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {
    internal var items: List<GalleryItem> = initialItems
        private set
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val thumbnailSize = Size(360, 360)
    private val thumbnailCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    var onHaptic: ((CameraHapticAction) -> Unit)? = null

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewWithTag("img")
        val badge: TextView = itemView.findViewWithTag("badge")
        var thumbnailJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val spanCount = (parent as? RecyclerView)
            ?.layoutManager
            ?.let { it as? GridLayoutManager }
            ?.spanCount
            ?: GalleryLayoutPolicy.spanCount(ctx, HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly)
        val height = GalleryLayoutPolicy.cardHeight(
            parentWidth = parent.measuredWidth.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels,
            spanCount = spanCount
        )
        val frame = android.widget.FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
            foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(1, 0x3300FF88)
                cornerRadius = 8f
            }
            clipToOutline = true
        }

        val img = ImageView(ctx).apply {
            tag = "img"
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF050707.toInt())
        }

        val badge = TextView(ctx).apply {
            tag = "badge"
            text = ctx.getString(R.string.control_raw)
            textSize = 9f
            setTextColor(0xFF00FF88.toInt())
            background = GradientDrawable().apply {
                setColor(0x99000404.toInt())
                setStroke(1, 0xCC00FF88.toInt())
                cornerRadius = 6f
            }
            setPadding(8, 3, 8, 3)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 8, 8, 0)
            }
        }

        frame.addView(img)
        frame.addView(badge)
        return VH(frame)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.thumbnailJob?.cancel()
        val cacheKey = item.uri.toString()
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null) {
            holder.image.setImageBitmap(cached)
        } else {
            holder.image.setImageDrawable(null)
            holder.thumbnailJob = adapterScope.launch {
                val thumbnail = loadThumbnail(item.uri)
                if (!isActive || holder.bindingAdapterPosition != position) return@launch
                if (thumbnail != null) {
                    thumbnailCache.put(cacheKey, thumbnail)
                    holder.image.setImageBitmap(thumbnail)
                } else {
                    holder.image.setImageURI(item.uri)
                }
            }
        }
        prefetchAround(position)
        val badgeText = when {
            item.isRaw -> "RAW"
            item.isLog -> "N-LOG"
            item.isHdr -> "HDR"
            item.isTenBit -> "10-BIT"
            item.isVideo -> "MOV"
            else -> ""
        }
        holder.badge.visibility = if (badgeText.isNotEmpty()) View.VISIBLE else View.GONE
        holder.badge.text = badgeText
        holder.itemView.setOnHoverListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> animatePreviewLift(view)
                MotionEvent.ACTION_HOVER_EXIT -> animateScale(view, 1.0f)
            }
            false
        }
        holder.itemView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animatePreviewLift(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(view, 1.0f)
            }
            false
        }
        holder.itemView.setOnClickListener {
            onHaptic?.invoke(CameraHapticAction.BUTTON_CLICK)
            onClick(item.uri)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        holder.image.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        adapterScope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private suspend fun loadThumbnail(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, thumbnailSize, null)
            } else {
                decodeLegacyThumbnail(uri)
            }
        }.getOrNull()
    }

    private fun decodeLegacyThumbnail(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > thumbnailSize.width * 2 ||
            height / sampleSize > thumbnailSize.height * 2
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun prefetchAround(position: Int) {
        for (offset in 1..2) {
            val item = items.getOrNull(position + offset) ?: continue
            val key = item.uri.toString()
            if (thumbnailCache.get(key) != null) continue
            adapterScope.launch {
                val thumbnail = loadThumbnail(item.uri)
                if (thumbnail != null) thumbnailCache.put(key, thumbnail)
            }
        }
    }

    private fun animateScale(view: View, scale: Float) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animatePreviewLift(view: View) {
        view.animate().scaleX(1.03f).scaleY(1.03f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun submitItems(nextItems: List<GalleryItem>) {
        val previous = items
        if (previous === nextItems) return
        adapterScope.launch {
            val diff = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = previous.size
                    override fun getNewListSize(): Int = nextItems.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        previous[oldItemPosition].uri == nextItems[newItemPosition].uri

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        previous[oldItemPosition] == nextItems[newItemPosition]
                })
            }
            items = nextItems
            diff.dispatchUpdatesTo(this@GalleryAdapter)
        }
    }

    override fun getItemCount() = items.size
}
