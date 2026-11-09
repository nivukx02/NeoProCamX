package com.neoproc.neoproccamx.ui

import android.annotation.SuppressLint
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MediaPreviewActivity"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MIME_TYPE = "extra_mime_type"

        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
        private const val SWIPE_MIN_DISTANCE_PX = 90f
        private const val SWIPE_MIN_VELOCITY_PX = 600f
    }

    private data class MediaEntry(
        val uri: Uri,
        val mimeType: String?,
        val isVideo: Boolean
    )

    private data class QueueInitResult(
        val entries: List<MediaEntry>,
        val currentIndex: Int
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private lateinit var root: FrameLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var indexLabel: TextView

    private var mediaEntries: List<MediaEntry> = emptyList()
    private var currentIndex: Int = 0
    private var transitionRunning = false
    private var imageLoadJob: Job? = null

    // Section 14: Video preview state (ExoPlayer / Media3)
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var muted = true
    private var playbackSpeedIndex = 1
    private var muteButton: android.widget.Button? = null
    private var speedButton: android.widget.Button? = null
    private val savedVideoPositionsMs: MutableMap<String, Long> = mutableMapOf()

    // Photo interaction state
    private var imageScale = 1f
    private var imageTranslationX = 0f
    private var imageTranslationY = 0f

    private val swipeGestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (abs(dx) < SWIPE_MIN_DISTANCE_PX || abs(dx) < abs(dy)) return false
                    if (abs(velocityX) < SWIPE_MIN_VELOCITY_PX) return false
                    if (transitionRunning) return false
                    return if (dx < 0f) {
                        navigateBy(+1)
                    } else {
                        navigateBy(-1)
                    }
                }
            }
        )
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Section 14: Immersive edge-to-edge fullscreen (camera-style preview)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uriString = intent.getStringExtra(EXTRA_URI)
        val fallbackMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val initialUri = uriString?.let(Uri::parse) ?: run {
            finish()
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Preview"

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            setOnTouchListener { view, event ->
                swipeGestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                }
                false
            }
        }
        contentContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        progress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }
        indexLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            text = "1/1"
            setPadding(20, 8, 20, 8)
            setBackgroundColor(0x55000000)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = 22
            }
        }
        root.addView(contentContainer)
        root.addView(progress)
        root.addView(indexLabel)
        setContentView(root)

        lifecycleScope.launch {
            val initialEntry = MediaEntry(
                uri = initialUri,
                mimeType = resolveMimeType(initialUri, fallbackMimeType),
                isVideo = isVideoMime(resolveMimeType(initialUri, fallbackMimeType))
            )
            val queue = withContext(Dispatchers.IO) { buildMediaQueue(initialEntry) }
            mediaEntries = queue.entries
            currentIndex = queue.currentIndex
            renderCurrent(animate = false)
            preloadNeighbors()
        }
    }

    override fun onPause() {
        super.onPause()
        persistCurrentVideoPosition()
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        val player = exoPlayer ?: return
        val entry = mediaEntries.getOrNull(currentIndex) ?: return
        val key = entry.uri.toString()
        val saved = savedVideoPositionsMs[key] ?: 0L
        if (saved > 0) player.seekTo(saved)
        player.play()
    }

    override fun onDestroy() {
        imageLoadJob?.cancel()
        imageLoadJob = null
        exoPlayer?.run {
            stop()
            release()
        }
        exoPlayer = null
        playerView = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun navigateBy(delta: Int): Boolean {
        if (mediaEntries.isEmpty()) return false
        val newIndex = (currentIndex + delta).coerceIn(0, mediaEntries.lastIndex)
        if (newIndex == currentIndex) return false

        persistCurrentVideoPosition()
        transitionRunning = true
        contentContainer.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                currentIndex = newIndex
                renderCurrent(animate = false)
                contentContainer.alpha = 0f
                contentContainer.animate()
                    .alpha(1f)
                    .setDuration(120)
                    .withEndAction {
                        transitionRunning = false
                        preloadNeighbors()
                    }
                    .start()
            }
            .start()
        return true
    }

    @AndroidxOptIn(markerClass = [UnstableApi::class])
    private fun renderCurrent(animate: Boolean) {
        val entry = mediaEntries.getOrNull(currentIndex) ?: return
        updateIndexLabel()
        supportActionBar?.title = "Preview ${currentIndex + 1}/${mediaEntries.size}"

        imageLoadJob?.cancel()
        imageLoadJob = null
        teardownVideoState()
        contentContainer.removeAllViews()
        progress.visibility = View.VISIBLE

        if (entry.isVideo) {
            renderVideo(entry)
        } else {
            renderPhoto(entry)
        }

        if (animate) {
            contentContainer.alpha = 0f
            contentContainer.animate().alpha(1f).setDuration(140).start()
        } else {
            contentContainer.alpha = 1f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @UnstableApi
    private fun renderVideo(entry: MediaEntry) {
        // Build ExoPlayer with HDR-capable track selector and extension renderer mode
        val trackSelector = DefaultTrackSelector(this).apply {
            // Prefer the highest quality video track; ExoPlayer handles HDR10/HLG/DV
            // metadata passthrough automatically when the surface supports it.
            parameters = buildUponParameters()
                .setMaxVideoSizeSd() // overridden below for high-end
                .build()
        }
        if (strictHighEndEnabled()) {
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(entry.uri))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
        exoPlayer = player

        val pv = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = false // we build custom controls
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setPlayer(player)
            // Let swipe events pass through to the root gesture detector for navigation
            setOnTouchListener { _, event ->
                swipeGestureDetector.onTouchEvent(event)
                false
            }
        }
        playerView = pv
        contentContainer.addView(pv)

        // Toggle play/pause on tap
        pv.setOnClickListener {
            if (player.isPlaying) {
                savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
                player.pause()
            } else {
                val saved = savedVideoPositionsMs[entry.uri.toString()] ?: 0L
                if (saved > 0) player.seekTo(saved)
                player.play()
            }
        }

        // Listener to hide progress once ready and restore saved position
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    progress.visibility = View.GONE
                    val saved = savedVideoPositionsMs[entry.uri.toString()] ?: 0L
                    if (saved > 0 && saved < player.duration) player.seekTo(saved)
                    applyMuteState()
                    applyPlaybackSpeed()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Log HDR info for diagnostics
                val videoTrack = tracks.groups.find { g -> g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                videoTrack?.let { g ->
                    for (i in 0 until g.length) {
                        val format = g.getTrackFormat(i)
                        val hdr = format.colorInfo?.hdrStaticInfo
                        if (hdr != null || format.colorInfo?.colorTransfer != androidx.media3.common.C.COLOR_TRANSFER_SDR) {
                            Log.i(TAG, "HDR video track detected: ${format.codecs} " +
                                "transfer=${format.colorInfo?.colorTransfer} " +
                                "standard=${format.colorInfo?.colorSpace} " +
                                "range=${format.colorInfo?.colorRange}")
                        }
                    }
                }
            }
        })

        // Inline controls (top-right, camera-style minimal)
        val controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = 48
                marginEnd = 24
            }
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0x55000000)
        }
        muteButton = android.widget.Button(this).apply {
            text = getString(R.string.playback_muted)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x33000000)
            setOnClickListener {
                muted = !muted
                applyMuteState()
            }
        }
        speedButton = android.widget.Button(this).apply {
            text = formatPlaybackSpeed()
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x33000000)
            setOnClickListener {
                playbackSpeedIndex = (playbackSpeedIndex + 1) % PLAYBACK_SPEEDS.size
                applyPlaybackSpeed()
            }
        }
        controlsContainer.addView(muteButton)
        controlsContainer.addView(speedButton)
        contentContainer.addView(controlsContainer)
    }

    private fun renderPhoto(entry: MediaEntry) {
        imageScale = 1f
        imageTranslationX = 0f
        imageTranslationY = 0f

        val imageView = GestureImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.MATRIX
            visibility = View.INVISIBLE
        }
        setupImageInteraction(imageView)
        contentContainer.addView(imageView)

        imageLoadJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadPreviewBitmap(entry.uri) }
            if (isFinishing || isDestroyed) return@launch
            progress.visibility = View.GONE
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            applyImageMatrix(imageView)
        }
    }

    private fun setupImageInteraction(imageView: GestureImageView) {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    imageScale = (imageScale * detector.scaleFactor).coerceIn(1f, 6f)
                    clampImageTranslation(imageView)
                    applyImageMatrix(imageView)
                    return true
                }
            }
        )
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    imageScale = 1f
                    imageTranslationX = 0f
                    imageTranslationY = 0f
                    applyImageMatrix(imageView)
                    return true
                }
            }
        )

        var lastTouchX = 0f
        var lastTouchY = 0f
        imageView.setOnTouchListener { _, event ->
            swipeGestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && imageScale > 1f && event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        imageTranslationX += dx
                        imageTranslationY += dy
                        clampImageTranslation(imageView)
                        applyImageMatrix(imageView)
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (imageScale <= 1f) {
                        imageTranslationX = 0f
                        imageTranslationY = 0f
                        applyImageMatrix(imageView)
                    }
                    imageView.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (imageScale <= 1f) {
                        imageTranslationX = 0f
                        imageTranslationY = 0f
                        applyImageMatrix(imageView)
                    }
                }
            }
            true
        }

        imageView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampImageTranslation(imageView)
            applyImageMatrix(imageView)
        }
    }

    private fun clampImageTranslation(imageView: ImageView) {
        val vw = imageView.width.toFloat().coerceAtLeast(1f)
        val vh = imageView.height.toFloat().coerceAtLeast(1f)
        val maxX = ((vw * imageScale) - vw) / 2f
        val maxY = ((vh * imageScale) - vh) / 2f
        imageTranslationX = imageTranslationX.coerceIn(-maxX, maxX)
        imageTranslationY = imageTranslationY.coerceIn(-maxY, maxY)
    }

    private fun applyImageMatrix(imageView: ImageView) {
        val vw = imageView.width.toFloat().coerceAtLeast(1f)
        val vh = imageView.height.toFloat().coerceAtLeast(1f)
        val matrix = Matrix().apply {
            postScale(imageScale, imageScale, vw / 2f, vh / 2f)
            postTranslate(imageTranslationX, imageTranslationY)
        }
        imageView.imageMatrix = matrix
    }

    private fun persistCurrentVideoPosition() {
        val player = exoPlayer ?: return
        val entry = mediaEntries.getOrNull(currentIndex) ?: return
        savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
    }

    private fun teardownVideoState() {
        exoPlayer?.run {
            stop()
            release()
        }
        exoPlayer = null
        playerView = null
        muteButton = null
        speedButton = null
    }

    private suspend fun buildMediaQueue(initial: MediaEntry): QueueInitResult {
        val entries = queryRecentMediaEntries()
        if (entries.isEmpty()) {
            return QueueInitResult(entries = listOf(initial), currentIndex = 0)
        }
        val initialIndex = entries.indexOfFirst { it.uri.toString() == initial.uri.toString() }
        return if (initialIndex >= 0) {
            QueueInitResult(entries = entries, currentIndex = initialIndex)
        } else {
            QueueInitResult(entries = listOf(initial) + entries, currentIndex = 0)
        }
    }

    private fun queryRecentMediaEntries(maxCount: Int = 220): List<MediaEntry> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        return runCatching {
            val out = ArrayList<MediaEntry>(maxCount)
            contentResolver.query(filesUri, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                while (cursor.moveToNext() && out.size < maxCount) {
                    val id = cursor.getLong(idCol)
                    val mediaType = cursor.getInt(typeCol)
                    val mime = cursor.getString(mimeCol)
                    when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            out += MediaEntry(uri = uri, mimeType = mime, isVideo = false)
                        }
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> {
                            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            out += MediaEntry(uri = uri, mimeType = mime, isVideo = true)
                        }
                    }
                }
            }
            out
        }.getOrElse { error ->
            Log.w(TAG, "Unable to query media queue", error)
            emptyList()
        }
    }

    private fun preloadNeighbors() {
        if (mediaEntries.isEmpty()) return
        val neighbors = listOf(currentIndex - 1, currentIndex + 1)
            .filter { it in mediaEntries.indices }
            .map { mediaEntries[it] }
            .filter { !it.isVideo }
        if (neighbors.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            neighbors.forEach { neighbor ->
                runCatching { loadPreviewBitmap(neighbor.uri) }
            }
        }
    }

    private fun resolveMimeType(uri: Uri, fallback: String?): String? {
        return fallback?.takeIf { it.isNotBlank() } ?: runCatching {
            contentResolver.getType(uri)
        }.getOrNull()
    }

    private fun isVideoMime(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

    private fun loadPreviewBitmap(uri: Uri): Bitmap? {
        val targetSize = previewDecodeTarget()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    contentResolver.loadThumbnail(uri, targetSize, null)
                }.getOrNull()?.let { return it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val targetWidth = info.size.width.coerceAtMost(targetSize.width)
                    val targetHeight = info.size.height.coerceAtMost(targetSize.height)
                    decoder.setTargetSize(targetWidth, targetHeight)
                    decoder.isMutableRequired = false
                }
            } else {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    val sampleSize = computeSampleSize(
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        reqWidth = targetSize.width,
                        reqHeight = targetSize.height
                    )
                    contentResolver.openInputStream(uri)?.use { decodeStream ->
                        BitmapFactory.decodeStream(
                            decodeStream,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview load failed for uri=$uri", e)
            null
        }
    }

    private fun previewDecodeTarget(): Size {
        val displayMetrics = resources.displayMetrics
        val decodeMultiplier = if (strictHighEndEnabled()) 1.55f else 1.35f
        val maxDimension = if (strictHighEndEnabled()) 2600 else 2200
        val width = (displayMetrics.widthPixels * decodeMultiplier).toInt().coerceIn(720, maxDimension)
        val height = (displayMetrics.heightPixels * decodeMultiplier).toInt().coerceIn(720, maxDimension)
        return Size(width, height)
    }

    private fun applyMediaAspectFit(
        targetView: View,
        container: View,
        contentWidth: Int,
        contentHeight: Int
    ) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        val containerWidth = container.width.coerceAtLeast(1)
        val containerHeight = container.height.coerceAtLeast(1)
        val contentAspect = contentWidth.toFloat() / contentHeight.toFloat()
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        val (targetWidth, targetHeight) = if (contentAspect > containerAspect) {
            containerWidth to (containerWidth / contentAspect).toInt().coerceAtLeast(1)
        } else {
            (containerHeight * contentAspect).toInt().coerceAtLeast(1) to containerHeight
        }
        targetView.layoutParams = FrameLayout.LayoutParams(
            targetWidth,
            targetHeight,
            Gravity.CENTER
        )
    }

    private fun computeSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun applyMuteState() {
        val player = exoPlayer ?: return
        runCatching {
            player.volume = if (muted) 0f else 1f
            muteButton?.text = if (muted) "Muted" else "Unmuted"
        }.onFailure { Log.w(TAG, "Failed to apply mute state", it) }
    }

    private fun applyPlaybackSpeed() {
        val player = exoPlayer ?: return
        val speed = PLAYBACK_SPEEDS[playbackSpeedIndex]
        runCatching {
            player.setPlaybackSpeed(speed)
            speedButton?.text = formatPlaybackSpeed()
        }.onFailure { Log.w(TAG, "Failed to apply playback speed $speed", it) }
    }

    private fun formatPlaybackSpeed(): String = "${PLAYBACK_SPEEDS[playbackSpeedIndex]}x"

    private fun updateIndexLabel() {
        val total = mediaEntries.size.coerceAtLeast(1)
        val current = (currentIndex + 1).coerceIn(1, total)
        indexLabel.text = getString(R.string.media_index_format, current, total)
    }

    private class GestureImageView(context: android.content.Context) : AppCompatImageView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
