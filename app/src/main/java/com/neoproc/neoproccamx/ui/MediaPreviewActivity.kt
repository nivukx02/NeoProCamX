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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.NivukxContainerFormat
import com.neoproc.neoproccamx.recording.NivukxExtractorsFactory
import com.neoproc.neoproccamx.recording.NivukxMediaStorePacketCopyExporter
import com.neoproc.neoproccamx.recording.NivukxPublishedMov
import com.neoproc.neoproccamx.tessera.TesseraExecutionBackend
import com.neoproc.neoproccamx.tessera.TesseraPrecisionContract
import com.neoproc.neoproccamx.tessera.TesseraRuntimeProvider
import com.neoproc.neoproccamx.tessera.TesseraSubmission
import com.neoproc.neoproccamx.tessera.TesseraTaskSpec
import com.neoproc.neoproccamx.tessera.TesseraWorkClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlin.math.abs

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MediaPreviewActivity"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_PRIVATE_MASTER_TYPE = "extra_private_master_type"
        const val EXTRA_PRIVATE_MASTER_PATH = "extra_private_master_path"
        const val EXTRA_PRIVATE_MASTER_META_URI = "extra_private_master_meta_uri"
        const val EXTRA_SOURCE_CONTENT_SHA256 = "extra_source_content_sha256"
        const val PRIVATE_MASTER_NPV10 = "npv10"
        const val PRIVATE_MASTER_NPRAW = "npraw"

        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
        private const val SWIPE_MIN_DISTANCE_PX = 90f
        private const val SWIPE_MIN_VELOCITY_PX = 600f
        private const val PACKET_COPY_UI_INTERVAL_NS = 125_000_000L
    }

    private data class MediaEntry(
        val uri: Uri,
        val mimeType: String?,
        val isVideo: Boolean,
        val privateMasterSource: PrivateMasterPlaybackSource? = null,
        val sourceContentSha256: String? = null
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
    private var encodedPlaybackEvidence: EncodedPlaybackEvidenceBridge? = null
    private var privateMasterPlayer: PrivateMasterPlaybackController? = null
    private var playerView: PlayerView? = null
    private var muted = true
    private var playbackSpeedIndex = 1
    private var muteButton: android.widget.Button? = null
    private var speedButton: android.widget.Button? = null
    private var packetCopyButton: android.widget.Button? = null
    private var playbackControls: EliteVideoPlaybackControls? = null
    private val savedVideoPositionsMs: MutableMap<String, Long> = mutableMapOf()
    private var packetCopySubmission: TesseraSubmission<NivukxPublishedMov>? = null
    private var packetCopyCancellation: AtomicBoolean? = null
    private var playbackUiVisible = false
    private var playbackUiUpdatesRunning = false

    // Photo interaction state
    private var imageScale = 1f
    private var imageTranslationX = 0f
    private var imageTranslationY = 0f

    private val playbackUiUpdater = object : Runnable {
        override fun run() {
            if (!playbackUiUpdatesRunning || !playbackUiVisible) return
            updatePlaybackControls()
            if (isCurrentVideoPlaying()) {
                playbackControls?.postDelayed(
                    this,
                    VideoPlaybackPerformancePolicy.CONTROLS_UPDATE_INTERVAL_MS
                )
            } else {
                playbackUiUpdatesRunning = false
            }
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        // Section 14: Immersive edge-to-edge fullscreen (camera-style preview)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uriString = intent.getStringExtra(EXTRA_URI)
        val fallbackMimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        val privateMasterSource = privateMasterSourceFromIntent()
        val sourceContentSha256 = intent.getStringExtra(EXTRA_SOURCE_CONTENT_SHA256)
            ?.trim()
            ?.lowercase(java.util.Locale.ROOT)
            ?.takeIf(PlaybackEvidenceRegistry::isCanonicalSha256)
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
            val initialMimeType = withContext(Dispatchers.IO) {
                resolveMimeType(initialUri, fallbackMimeType)
            }
            val initialEntry = MediaEntry(
                uri = initialUri,
                mimeType = initialMimeType,
                isVideo = privateMasterSource != null || isVideoMime(initialMimeType),
                privateMasterSource = privateMasterSource,
                sourceContentSha256 = sourceContentSha256
            )

            // Put the tapped item on screen before enumerating the recent MediaStore library. A
            // large gallery can take seconds to query on vendor MediaProviders; it must never hold
            // the selected clip behind a spinner or race Activity.onResume() autoplay.
            mediaEntries = listOf(initialEntry)
            currentIndex = 0
            renderCurrent(animate = false)

            if (privateMasterSource == null) {
                val queue = withContext(Dispatchers.IO) { buildMediaQueue(initialEntry) }
                if (isFinishing || isDestroyed) return@launch
                mediaEntries = queue.entries
                currentIndex = queue.currentIndex
                updateIndexLabel()
                supportActionBar?.title = "Preview ${currentIndex + 1}/${mediaEntries.size}"
                preloadNeighbors()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscuredFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscuredFlags != 0) return false
        return super.dispatchTouchEvent(event)
    }

    @AndroidxOptIn(markerClass = [UnstableApi::class])
    override fun onPause() {
        playbackUiVisible = false
        stopPlaybackUiUpdates()
        super.onPause()
        persistCurrentVideoPosition()
        // ActivityScenario.close() and a real Back/finish transition pause the Activity before
        // onDestroy releases Media3. Snapshot visible-playback counters here, before pause asks the
        // codec to discard already queued output buffers. Those teardown-only discards were being
        // reported as playback drops even though every frame due while the surface was visible had
        // been presented. A temporary background pause remains resumable and therefore opens a
        // normal cadence boundary instead of sealing the session.
        if (isFinishing || isChangingConfigurations) {
            encodedPlaybackEvidence?.beginPlaybackTeardown(exoPlayer?.videoDecoderCounters)
        } else {
            encodedPlaybackEvidence?.markCadenceBoundary()
        }
        exoPlayer?.pause()
        privateMasterPlayer?.pause()
        // Returning to the camera must never leave storage hashing/remux work competing with preview.
        cancelPacketCopyExport()
    }

    override fun onResume() {
        super.onResume()
        playbackUiVisible = true
        privateMasterPlayer?.play()
        exoPlayer?.let { player ->
            val entry = mediaEntries.getOrNull(currentIndex)
            val saved = entry?.let { savedVideoPositionsMs[it.uri.toString()] } ?: 0L
            if (saved > 0) player.seekTo(saved)
            player.play()
        }
        startPlaybackUiUpdatesIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updatePlaybackControls()
            startPlaybackUiUpdatesIfNeeded()
        } else {
            stopPlaybackUiUpdates()
        }
    }

    override fun onDestroy() {
        stopPlaybackUiUpdates()
        cancelPacketCopyExport()
        imageLoadJob?.cancel()
        imageLoadJob = null
        teardownVideoState(reason = "activity_destroyed")
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

        cancelPacketCopyExport()
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
            indexLabel.visibility = View.GONE
            renderVideo(entry)
        } else {
            indexLabel.visibility = View.VISIBLE
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
        entry.privateMasterSource?.let { source ->
            renderPrivateMasterVideo(entry, source)
            return
        }
        // Build ExoPlayer with HDR-capable track selector and extension renderer mode
        val trackSelector = DefaultTrackSelector(this).apply {
            // Never impose an SD cap on a camera master. Media3 still rejects tracks that the active
            // decoder cannot support, while preserving the highest supported source resolution.
            parameters = buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // Keep Media3's API/device-specific codec-adapter selection. Globally forcing callback
            // queueing breaks known older/vendor codecs; decoder fallback remains enabled below.
            .setEnableDecoderFallback(true)
            // Camera masters are local and deterministic: wait for the real video renderer instead
            // of admitting Media3's startup joining window, which may discard late Main10 pictures.
            .setAllowedVideoJoiningTimeMs(
                VideoPlaybackPerformancePolicy.ALLOWED_VIDEO_JOINING_TIME_MS
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForLocalPlayback(
                VideoPlaybackPerformancePolicy.LOCAL_MIN_BUFFER_MS,
                VideoPlaybackPerformancePolicy.LOCAL_MAX_BUFFER_MS,
                VideoPlaybackPerformancePolicy.PLAYBACK_START_BUFFER_MS,
                VideoPlaybackPerformancePolicy.REBUFFER_START_BUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
            .setBackBuffer(VideoPlaybackPerformancePolicy.BACK_BUFFER_MS, true)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val evidence = EncodedPlaybackEvidenceBridge(
            path = if (entry.isNivukx()) {
                PlaybackEvidencePath.NIVUKX_ENCODED_MASTER
            } else {
                PlaybackEvidencePath.ENCODED_MASTER
            },
            sourceIdentity = entry.uri.toString(),
            sourceContentSha256 = entry.sourceContentSha256
        )
        evidence.attach(player)
        encodedPlaybackEvidence = evidence
        exoPlayer = player

        // Attach the final output surface before Media3 configures or starts the decoder. Preparing
        // first makes ExoPlayer create a dummy surface and then switch to PlayerView's surface; some
        // vendor codecs discard an output buffer during that hand-off and the first visible cadence
        // interval stalls. The surface is now immutable for the whole playback lifetime.
        val pv = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = false // we build custom controls
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            setPlayer(player)
        }
        playerView = pv
        evidence.markOutputAttached()
        contentContainer.addView(pv)

        // Gesture plane sits above the video surface but below transport controls. A confirmed single
        // tap toggles playback; a left/right double tap seeks exactly 10 seconds without accidentally
        // issuing the single-tap pause first. FIT keeps source aspect and black only letterboxes.
        contentContainer.addView(
            VideoPlaybackGestureOverlay(
                context = this,
                onSingleTap = { togglePlayback(entry) },
                onDoubleTapLeft = { seekRelativeBy(-VideoPlaybackSeekPolicy.DOUBLE_TAP_SEEK_MS) },
                onDoubleTapRight = { seekRelativeBy(VideoPlaybackSeekPolicy.DOUBLE_TAP_SEEK_MS) },
                onPrevious = { navigateBy(-1) },
                onNext = { navigateBy(+1) }
            ),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Listener to hide progress once ready and restore saved position
        val initialSavedPositionMs = savedVideoPositionsMs[entry.uri.toString()]
            ?.coerceAtLeast(0L)
            ?: 0L
        var restoredInitialPosition = false
        var initialAutoplayPending = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    progress.visibility = View.GONE
                    if (!restoredInitialPosition) {
                        restoredInitialPosition = true
                        val saved = initialSavedPositionMs
                        if (saved > 0 && saved < player.duration) {
                            player.seekTo(saved)
                        }
                    }
                    applyMuteState()
                    applyPlaybackSpeed()
                    // Start on the same READY boundary as an optional restored seek. Waiting for a
                    // second READY callback is invalid: an in-buffer seek may remain STATE_READY and
                    // would leave the first frame frozen indefinitely.
                    if (initialAutoplayPending) {
                        initialAutoplayPending = false
                        player.playWhenReady = shouldAutoplayCurrentMedia()
                    }
                    updatePlaybackControls()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
                }
                updatePlaybackControls()
                startPlaybackUiUpdatesIfNeeded()
            }

            override fun onPlayerError(error: PlaybackException) {
                progress.visibility = View.GONE
                Log.e(TAG, "Video playback failed for ${entry.uri}", error)
                Toast.makeText(
                    this@MediaPreviewActivity,
                    getString(R.string.player_playback_failed),
                    Toast.LENGTH_LONG
                ).show()
                updatePlaybackControls()
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

        val controls = EliteVideoPlaybackControls(
            context = this,
            onBack = { finish() },
            onPrevious = { navigateBy(-1) },
            onTogglePlayback = { togglePlayback(entry) },
            onNext = { navigateBy(+1) },
            onSeekTo = { requestedPositionMs ->
                player.seekTo(requestedPositionMs.coerceAtLeast(0L))
                updatePlaybackControls()
            },
            onAdvancedOptions = { showAdvancedPlaybackOptions(entry) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        playbackControls = controls
        contentContainer.addView(controls)

        // All listeners, evidence boundaries, the output surface, gesture plane, and controls are
        // installed before preparation. This prevents startup frames from escaping measurement or
        // being decoded against a temporary surface.
        player.apply {
            val mediaItem = MediaItem.fromUri(entry.uri)
            if (entry.isNivukx()) {
                // NIVUKX stores the original HEVC/AV1/AAC access units. The custom extractor only
                // demuxes and verifies them; MediaCodec still performs zero-copy hardware decode.
                val mediaSource = ProgressiveMediaSource.Factory(
                    DefaultDataSource.Factory(this@MediaPreviewActivity),
                    NivukxExtractorsFactory
                ).createMediaSource(mediaItem)
                setMediaSource(mediaSource)
            } else {
                setMediaItem(mediaItem)
            }
            repeatMode = Player.REPEAT_MODE_ONE
            // Prepare paused so the final output surface receives a decoded preroll frame before the
            // media clock starts. The READY callback above starts playback only after that boundary.
            playWhenReady = false
            prepare()
        }
        updatePlaybackControls()
        startPlaybackUiUpdatesIfNeeded()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderPrivateMasterVideo(
        entry: MediaEntry,
        source: PrivateMasterPlaybackSource
    ) {
        val display = resources.displayMetrics
        val viewportLongEdge = maxOf(display.widthPixels, display.heightPixels)
        val decodeLongEdge = VideoPlaybackPerformancePolicy.privateMasterDecodeLongEdge(
            viewportLongEdge = viewportLongEdge,
            rawMaster = source is PrivateMasterPlaybackSource.Npraw,
            strictHighEnd = strictHighEndEnabled()
        )
        val evidence = PlaybackEvidenceRegistry.start(
            path = when (source) {
                is PrivateMasterPlaybackSource.Npv10 -> PlaybackEvidencePath.PRIVATE_NPV10_MASTER
                is PrivateMasterPlaybackSource.Npraw -> PlaybackEvidencePath.PRIVATE_NPRAW_MASTER
            },
            sourceIdentity = entry.uri.toString(),
            sourceContentSha256 = entry.sourceContentSha256
        )
        lateinit var controller: PrivateMasterPlaybackController
        controller = PrivateMasterPlaybackController(
            context = this,
            source = source,
            targetLongEdge = decodeLongEdge,
            initialPlayWhenReady = shouldAutoplayCurrentMedia(),
            evidenceSession = evidence,
            onReady = {
                progress.visibility = View.GONE
                val saved = savedVideoPositionsMs[entry.uri.toString()] ?: 0L
                if (saved > 0L && saved < controller.durationMs) controller.seekTo(saved)
                // Initialization may finish after onResume(), so onResume() could not see this
                // controller. Re-evaluate lifecycle-owned autoplay at the actual ready boundary.
                if (shouldAutoplayCurrentMedia()) controller.play()
                updatePlaybackControls()
                startPlaybackUiUpdatesIfNeeded()
            },
            onError = { error ->
                progress.visibility = View.GONE
                Log.e(TAG, "Private master playback failed for ${entry.uri}", error)
                Toast.makeText(
                    this,
                    "Video master playback failed: ${error.message ?: "invalid or incomplete source"}",
                    Toast.LENGTH_LONG
                ).show()
                updatePlaybackControls()
            }
        )
        privateMasterPlayer = controller
        controller.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        contentContainer.addView(controller.view)

        contentContainer.addView(
            VideoPlaybackGestureOverlay(
                context = this,
                onSingleTap = { togglePlayback(entry) },
                onDoubleTapLeft = { seekRelativeBy(-VideoPlaybackSeekPolicy.DOUBLE_TAP_SEEK_MS) },
                onDoubleTapRight = { seekRelativeBy(VideoPlaybackSeekPolicy.DOUBLE_TAP_SEEK_MS) },
                onPrevious = { navigateBy(-1) },
                onNext = { navigateBy(+1) }
            ),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val controls = EliteVideoPlaybackControls(
            context = this,
            onBack = { finish() },
            onPrevious = { navigateBy(-1) },
            onTogglePlayback = { togglePlayback(entry) },
            onNext = { navigateBy(+1) },
            onSeekTo = { requestedPositionMs ->
                controller.seekTo(requestedPositionMs.coerceAtLeast(0L))
                updatePlaybackControls()
            },
            onAdvancedOptions = { showAdvancedPlaybackOptions(entry) }
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        playbackControls = controls
        contentContainer.addView(controls)
        updatePlaybackControls()
        startPlaybackUiUpdatesIfNeeded()
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
        privateMasterPlayer?.let { player ->
            val entry = mediaEntries.getOrNull(currentIndex) ?: return
            savedVideoPositionsMs[entry.uri.toString()] = player.currentPositionMs
            return
        }
        val player = exoPlayer ?: return
        val entry = mediaEntries.getOrNull(currentIndex) ?: return
        savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
    }

    @AndroidxOptIn(markerClass = [UnstableApi::class])
    private fun teardownVideoState(reason: String = "media_changed") {
        stopPlaybackUiUpdates()
        playbackControls = null
        val player = exoPlayer
        val encodedEvidence = encodedPlaybackEvidence
        exoPlayer = null
        encodedPlaybackEvidence = null
        // Freeze playback-quality counters while the real output is still attached. Buffers that a
        // codec discards solely because this Activity intentionally detaches/releases its surface are
        // teardown accounting, not user-visible playback drops.
        encodedEvidence?.beginPlaybackTeardown(player?.videoDecoderCounters)
        playerView?.player = null
        encodedEvidence?.markOutputDetached()
        val finalCounters = player?.videoDecoderCounters
        player?.release()
        encodedEvidence?.finishAfterPlayerRelease(reason, finalCounters)
        privateMasterPlayer?.close()
        privateMasterPlayer = null
        playerView = null
        muteButton = null
        speedButton = null
        packetCopyButton = null
    }

    private fun togglePlayback(entry: MediaEntry) {
        privateMasterPlayer?.let { player ->
            if (player.isPlaying) {
                savedVideoPositionsMs[entry.uri.toString()] = player.currentPositionMs
                player.pause()
            } else {
                player.play()
            }
            updatePlaybackControls()
            startPlaybackUiUpdatesIfNeeded()
            return
        }
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            savedVideoPositionsMs[entry.uri.toString()] = player.currentPosition
            player.pause()
        } else {
            val saved = savedVideoPositionsMs[entry.uri.toString()] ?: 0L
            if (player.currentPosition <= 0L && saved > 0L) player.seekTo(saved)
            player.play()
        }
        updatePlaybackControls()
        startPlaybackUiUpdatesIfNeeded()
    }

    private fun isCurrentVideoPlaying(): Boolean =
        privateMasterPlayer?.isPlaying ?: (exoPlayer?.isPlaying == true)

    private fun shouldAutoplayCurrentMedia(): Boolean =
        VideoPlaybackPerformancePolicy.shouldAutoplay(
            lifecycleResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            playbackUiVisible = playbackUiVisible
        )

    private fun startPlaybackUiUpdatesIfNeeded() {
        if (!isCurrentVideoPlaying()) {
            stopPlaybackUiUpdates()
            return
        }
        if (!playbackUiVisible || !hasWindowFocus() || playbackUiUpdatesRunning) return
        val controls = playbackControls ?: return
        playbackUiUpdatesRunning = true
        controls.removeCallbacks(playbackUiUpdater)
        controls.postDelayed(
            playbackUiUpdater,
            VideoPlaybackPerformancePolicy.CONTROLS_UPDATE_INTERVAL_MS
        )
    }

    private fun stopPlaybackUiUpdates() {
        playbackUiUpdatesRunning = false
        playbackControls?.removeCallbacks(playbackUiUpdater)
    }

    private fun updatePlaybackControls() {
        privateMasterPlayer?.let { player ->
            playbackControls?.bind(
                positionMs = player.currentPositionMs,
                durationMs = player.durationMs,
                isPlaying = player.isPlaying
            )
            return
        }
        val player = exoPlayer ?: return
        playbackControls?.bind(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            isPlaying = player.isPlaying
        )
    }

    private fun seekRelativeBy(deltaMs: Long) {
        privateMasterPlayer?.let { player ->
            val targetMs = VideoPlaybackSeekPolicy.relativeTarget(
                positionMs = player.currentPositionMs,
                durationMs = player.durationMs,
                deltaMs = deltaMs
            )
            player.seekTo(targetMs)
            playbackControls?.showSeekFeedback(if (deltaMs < 0L) -10 else 10)
            updatePlaybackControls()
            return
        }
        val player = exoPlayer ?: return
        val targetMs = VideoPlaybackSeekPolicy.relativeTarget(
            positionMs = player.currentPosition,
            durationMs = player.duration,
            deltaMs = deltaMs
        )
        player.seekTo(targetMs)
        playbackControls?.showSeekFeedback(if (deltaMs < 0L) -10 else 10)
        updatePlaybackControls()
    }

    private fun showAdvancedPlaybackOptions(entry: MediaEntry) {
        val actions = buildList {
            if (entry.privateMasterSource == null) {
                add(if (muted) getString(R.string.player_unmute) else getString(R.string.player_mute))
            }
            add(getString(R.string.player_speed, formatPlaybackSpeed()))
            if (entry.isNivukx()) add(getString(R.string.media_export_mov))
        }
        AlertDialog.Builder(this)
            .setItems(actions.toTypedArray()) { _, which ->
                val actionOffset = if (entry.privateMasterSource == null) 0 else 1
                when (which + actionOffset) {
                    0 -> {
                        muted = !muted
                        applyMuteState()
                    }
                    1 -> {
                        playbackSpeedIndex = (playbackSpeedIndex + 1) % PLAYBACK_SPEEDS.size
                        applyPlaybackSpeed()
                    }
                    2 -> startPacketCopyExport(entry)
                }
            }
            .show()
    }

    private fun startPacketCopyExport(entry: MediaEntry) {
        if (!entry.isNivukx() || packetCopySubmission != null) return
        val cancellation = AtomicBoolean(false)
        packetCopyCancellation = cancellation
        packetCopyButton?.apply {
            isEnabled = false
            text = getString(R.string.media_exporting_progress, 0)
        }

        var lastUiUpdateNs = 0L
        val submission = TesseraRuntimeProvider.runtime().submit(
            spec = TesseraTaskSpec(
                name = "nivukx-packet-copy-mov",
                workClass = TesseraWorkClass.BACKGROUND,
                preferredBackends = listOf(TesseraExecutionBackend.CONTROL_CPU),
                precision = TesseraPrecisionContract.INTEGER_EXACT,
                estimatedWorkingSetBytes = 64L * 1024L * 1024L
            )
        ) {
            NivukxMediaStorePacketCopyExporter(applicationContext).export(
                sourceUri = entry.uri,
                shouldContinue = { !cancellation.get() },
                onProgress = { progress ->
                    val nowNs = System.nanoTime()
                    if (progress.fraction >= 1.0 || nowNs - lastUiUpdateNs >= PACKET_COPY_UI_INTERVAL_NS) {
                        lastUiUpdateNs = nowNs
                        val percent = (progress.fraction * 100.0).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            if (packetCopyCancellation === cancellation && !isFinishing && !isDestroyed) {
                                packetCopyButton?.text = getString(R.string.media_exporting_progress, percent)
                            }
                        }
                    }
                }
            )
        }
        if (!submission.accepted) {
            packetCopyCancellation = null
            packetCopyButton?.apply {
                isEnabled = true
                text = getString(R.string.media_export_mov)
            }
            Toast.makeText(
                this,
                "Conversion engine is busy (${submission.rejectionReason})",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        packetCopySubmission = submission
        Toast.makeText(this, getString(R.string.media_export_started), Toast.LENGTH_SHORT).show()
        submission.future.whenComplete { published, failure ->
            runOnUiThread {
                if (packetCopyCancellation !== cancellation) return@runOnUiThread
                packetCopySubmission = null
                packetCopyCancellation = null
                packetCopyButton?.apply {
                    isEnabled = true
                    text = getString(R.string.media_export_mov)
                }
                if (isFinishing || isDestroyed) return@runOnUiThread
                val rootFailure = failure?.rootCause()
                when {
                    published != null -> Toast.makeText(
                        this,
                        "Saved ${published.displayName} — no transcoding",
                        Toast.LENGTH_LONG
                    ).show()
                    rootFailure !is CancellationException && !cancellation.get() -> Toast.makeText(
                        this,
                        "MOV packet-copy failed: ${rootFailure?.message ?: "unknown error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun cancelPacketCopyExport() {
        packetCopyCancellation?.set(true)
    }

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while ((current is CompletionException || current is java.util.concurrent.ExecutionException) &&
            current.cause != null
        ) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private suspend fun buildMediaQueue(initial: MediaEntry): QueueInitResult {
        // Private acquisition masters are not MediaStore gallery assets. Keep their player queue
        // isolated so next/previous can never jump from a private master into unrelated user media.
        if (initial.privateMasterSource != null) {
            return QueueInitResult(entries = listOf(initial), currentIndex = 0)
        }
        val entries = queryRecentMediaEntries()
        if (entries.isEmpty()) {
            return QueueInitResult(entries = listOf(initial), currentIndex = 0)
        }
        val initialIndex = entries.indexOfFirst { it.uri.toString() == initial.uri.toString() }
        return if (initialIndex >= 0) {
            val queue = entries.toMutableList()
            queue[initialIndex] = queue[initialIndex].copy(
                sourceContentSha256 = initial.sourceContentSha256
            )
            QueueInitResult(entries = queue, currentIndex = initialIndex)
        } else {
            QueueInitResult(entries = listOf(initial) + entries, currentIndex = 0)
        }
    }

    private fun queryRecentMediaEntries(maxCount: Int = 220): List<MediaEntry> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME
        )
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "%.${NivukxContainerFormat.FILE_EXTENSION}"
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        return runCatching {
            val out = ArrayList<MediaEntry>(maxCount)
            contentResolver.query(filesUri, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext() && out.size < maxCount) {
                    val id = cursor.getLong(idCol)
                    val mediaType = cursor.getInt(typeCol)
                    val mime = cursor.getString(mimeCol)
                    val displayName = cursor.getString(nameCol).orEmpty()
                    val isNivukx = displayName.endsWith(
                        ".${NivukxContainerFormat.FILE_EXTENSION}",
                        ignoreCase = true
                    )
                    when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            out += MediaEntry(uri = uri, mimeType = mime, isVideo = false)
                        }
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> {
                            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            out += MediaEntry(uri = uri, mimeType = mime, isVideo = true)
                        }
                        else -> if (isNivukx) {
                            val uri = ContentUris.withAppendedId(filesUri, id)
                            out += MediaEntry(
                                uri = uri,
                                mimeType = NivukxContainerFormat.MIME_TYPE,
                                isVideo = true
                            )
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
        val storedMimeType = fallback?.takeIf { it.isNotBlank() } ?: runCatching {
            contentResolver.getType(uri)
        }.getOrNull()
        if (!storedMimeType.equals(NivukxContainerFormat.MEDIASTORE_MIME_TYPE, ignoreCase = true)) {
            return storedMimeType
        }
        val displayName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
        }.getOrNull()
        return if (displayName?.endsWith(
                ".${NivukxContainerFormat.FILE_EXTENSION}",
                ignoreCase = true
            ) == true
        ) {
            NivukxContainerFormat.MIME_TYPE
        } else {
            storedMimeType
        }
    }

    private fun privateMasterSourceFromIntent(): PrivateMasterPlaybackSource? =
        when (intent.getStringExtra(EXTRA_PRIVATE_MASTER_TYPE)) {
            PRIVATE_MASTER_NPV10 -> intent.getStringExtra(EXTRA_PRIVATE_MASTER_PATH)
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isFile && it.canRead() }
                ?.let(PrivateMasterPlaybackSource::Npv10)
            PRIVATE_MASTER_NPRAW -> {
                val stream = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val metadata = intent.getStringExtra(EXTRA_PRIVATE_MASTER_META_URI)?.let(Uri::parse)
                if (stream != null && metadata != null) {
                    PrivateMasterPlaybackSource.Npraw(streamUri = stream, metadataUri = metadata)
                } else {
                    null
                }
            }
            else -> null
        }

    private fun isVideoMime(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

    private fun MediaEntry.isNivukx(): Boolean =
        mimeType.equals(NivukxContainerFormat.MIME_TYPE, ignoreCase = true) ||
            uri.lastPathSegment?.endsWith(
                ".${NivukxContainerFormat.FILE_EXTENSION}",
                ignoreCase = true
            ) == true

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
        val speed = PLAYBACK_SPEEDS[playbackSpeedIndex]
        privateMasterPlayer?.let { player ->
            runCatching {
                player.setPlaybackSpeed(speed)
                speedButton?.text = formatPlaybackSpeed()
            }.onFailure { Log.w(TAG, "Failed to apply private-master speed $speed", it) }
            return
        }
        val player = exoPlayer ?: return
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
