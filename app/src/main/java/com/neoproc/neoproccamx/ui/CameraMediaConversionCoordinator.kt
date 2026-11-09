package com.neoproc.neoproccamx.ui

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.scale
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.NprawRecoveryResult
import com.neoproc.neoproccamx.pipeline.NprawStreamRecovery
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.RawVideoState
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.recording.ConversionCancellationContext
import com.neoproc.neoproccamx.recording.ConversionCancellationSignal
import com.neoproc.neoproccamx.recording.ConversionEtaEstimator
import com.neoproc.neoproccamx.recording.ConversionProgressListener
import com.neoproc.neoproccamx.recording.CinemaCamMtpPublicationScheduler
import com.neoproc.neoproccamx.recording.DmcConversionWorkload
import com.neoproc.neoproccamx.recording.NeoQualityInstructionCache
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarPolicy
import com.neoproc.neoproccamx.recording.Npv10AuditStatus
import com.neoproc.neoproccamx.recording.Npv10DirectMovWorkflow
import com.neoproc.neoproccamx.recording.Npv10ExportGpuAccelerator
import com.neoproc.neoproccamx.recording.Npv10FileReader
import com.neoproc.neoproccamx.recording.Npv10MasterClip
import com.neoproc.neoproccamx.recording.Npv10MasterLanePolicy
import com.neoproc.neoproccamx.recording.Npv10MovConverter
import com.neoproc.neoproccamx.recording.Npv10MovPublishPolicy
import com.neoproc.neoproccamx.recording.Npv10RecordingManager
import com.neoproc.neoproccamx.recording.Npv10ThumbnailRenderer
import com.neoproc.neoproccamx.recording.Npv10VerifiedMasterPublication
import com.neoproc.neoproccamx.recording.Raw12ThumbnailRenderer
import com.neoproc.neoproccamx.recording.RawCinemaDngIdentityPolicy
import com.neoproc.neoproccamx.recording.RawCinemaDngWriter
import com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery
import com.neoproc.neoproccamx.recording.RawClipAudioSource
import com.neoproc.neoproccamx.recording.RawClipMovConversionWorkflow
import com.neoproc.neoproccamx.recording.RawToNeoLogMovConverter
import com.neoproc.neoproccamx.recording.RecordDevelopConversionDiagnostics
import com.neoproc.neoproccamx.recording.RecordDevelopConversionOptions
import com.neoproc.neoproccamx.recording.RecordDevelopDeliveryFormat
import com.neoproc.neoproccamx.recording.RecordDevelopDirectMovWorkflow
import com.neoproc.neoproccamx.recording.RecordDevelopMasterClip
import com.neoproc.neoproccamx.recording.RecordDevelopMasterKind
import com.neoproc.neoproccamx.recording.RecordDevelopMediaCodecP010Reader
import com.neoproc.neoproccamx.recording.RecordDevelopMovConverter
import com.neoproc.neoproccamx.recording.RecordDevelopOutputFormat
import com.neoproc.neoproccamx.recording.RecordDevelopSdrLookPolicy
import com.neoproc.neoproccamx.recording.RecordingManager
import com.neoproc.neoproccamx.recording.RecordingPreallocationPolicy
import com.neoproc.neoproccamx.recording.ThumbnailImage
import com.neoproc.neoproccamx.recording.TransactionalMediaStoreArtifactSet
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar
import com.neoproc.neoproccamx.recording.NativeRecordDevelopGpuAccelerator
import com.neoproc.neoproccamx.tessera.NeoFusionConversionEngine
import com.neoproc.neoproccamx.tessera.TesseraPrecisionContract
import java.io.File
import java.util.Locale

/**
 * Owns Settings-media discovery, conversion, integrity verification and atomic publication.
 *
 * Camera lifecycle exclusion remains supplied by [CameraConversionIsolationCoordinator], while
 * this class keeps every media operation independent from the already oversized camera/session
 * state machine. Source masters remain immutable and retained after successful derived exports.
 */
internal class CameraMediaConversionCoordinator(
    private val application: Application,
    private val conversionIsolation: CameraConversionIsolationCoordinator,
    private val conversionEngine: NeoFusionConversionEngine,
    private val npv10Recording: Npv10RecordingManager,
    private val recordingManager: RecordingManager,
    private val rawVideoManager: RawVideoManager,
    private val colorScience: ColorScience,
    private val onRecordingSaved: (String) -> Unit,
    private val conversionPowerGuard: ConversionExecutionPowerGuard =
        ConversionExecutionPowerGuard(application),
    private val dmcConversionRuntime: AndroidDmcConversionRuntime =
        AndroidDmcConversionRuntime(application)
) {
    private val rawClipDiscovery by lazy {
        RawClipMediaStoreDiscovery(application)
    }

    private val rawClipMovConversionWorkflow by lazy {
        RawClipMovConversionWorkflow(
            application = application,
            prepareSource = { recoverRawClipPreallocatedTail(it) },
            progressReporterFactory = ::buildRawProgressReporter,
            onPublished = onRecordingSaved,
            onWorkloadResolved = { metadata, options ->
                val plan = when (options.deliveryProfile) {
                    RawToNeoLogMovConverter.DeliveryProfile.NEOLOG ->
                        Niv99OfflineConversionPolicy.rawToLog()
                    RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS ->
                        Niv99OfflineConversionPolicy.rawToNdr10Plus()
                    RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION ->
                        Niv99OfflineConversionPolicy.rawToNivukxVision()
                }
                dmcConversionRuntime.configure(
                    DmcConversionWorkload(
                        route = plan.route.name,
                        width = metadata.width,
                        height = metadata.height,
                        sourceFps = metadata.fps.coerceAtLeast(1),
                        compressedOutput =
                            options.format == RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420
                    )
                )
            }
        )
    }

    fun listNpv10Masters(): List<Npv10MasterClip> = npv10Recording.listMasters()

    fun listNpv10Masters(kind: RecordDevelopMasterKind): List<Npv10MasterClip> =
        npv10Recording.listMasters().filter { Npv10MasterLanePolicy.resolve(it.file) == kind }

    fun <T> runExclusive(
        cancellation: ConversionCancellationSignal,
        work: () -> T
    ): T = conversionIsolation.run {
        conversionPowerGuard.run {
            dmcConversionRuntime.run(cancellation) {
                conversionEngine.execute(
                    name = "settings-media-conversion",
                    precision = TesseraPrecisionContract.FP32_STRICT,
                    operation = { ConversionCancellationContext.runWith(cancellation, work) }
                )
            }
        }
    }

    fun convertNpv10MasterToMov(
        clip: Npv10MasterClip,
        outputFormat: Npv10MovConverter.OutputFormat,
        onProgress: ConversionProgressListener?
    ): String = convertNpv10MasterToMovAndPublish(clip, outputFormat, onProgress)

    fun renderNpv10ThumbnailArgb(clip: Npv10MasterClip): ThumbnailImage? =
        runCatching { Npv10ThumbnailRenderer.render(clip.file) }.getOrNull()

    fun deleteNpv10Master(clip: Npv10MasterClip): Boolean = runCatching {
        clip.file.delete().also { deleted ->
            if (deleted) {
                Npv10AudioSidecarPolicy.deleteBundleAudio(clip.file)
                NeoQualityInstructionCache.deleteFor(clip.file)
                Npv10VerifiedMasterPublication.invalidate(clip.file)
                CinemaCamMtpPublicationScheduler.remove(application, clip.file.name)
            }
        }
    }.getOrDefault(false)

    fun listRecordDevelopMasters(kind: RecordDevelopMasterKind): List<RecordDevelopMasterClip> =
        recordingManager.listRecordDevelopMasters(kind)

    fun deleteRecordDevelopMaster(clip: RecordDevelopMasterClip): Boolean =
        recordingManager.deleteRecordDevelopMaster(clip).also { deleted ->
            if (deleted) CinemaCamMtpPublicationScheduler.remove(application, clip.file.name)
        }

    fun hasAnyRecordDevelopMasters(): Boolean =
        runCatching { recordingManager.hasAnyRecordDevelopMasters() }.getOrDefault(false)

    fun convertRecordDevelopMaster(
        clip: RecordDevelopMasterClip,
        deliveryFormat: RecordDevelopDeliveryFormat,
        onProgress: ConversionProgressListener?
    ): String = convertRecordDevelopMasterToMovAndPublish(clip, deliveryFormat, onProgress)

    fun renderRecordDevelopThumbnailArgb(clip: RecordDevelopMasterClip): ThumbnailImage? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(clip.file.absolutePath)
            val frame = retriever.getFrameAtTime(
                0L,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return@runCatching null
            val maxDimension = 160
            val scale = minOf(
                maxDimension.toFloat() / frame.width,
                maxDimension.toFloat() / frame.height,
                1f
            )
            val width = maxOf(1, (frame.width * scale).toInt())
            val height = maxOf(1, (frame.height * scale).toInt())
            val scaled = frame.scale(width, height, true)
            val argb = IntArray(width * height)
            scaled.getPixels(argb, 0, width, 0, 0, width, height)
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            ThumbnailImage(width = width, height = height, argb = argb)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    fun listRawClips(): List<RawClipMediaStoreDiscovery.RawClipRef> = runCatching {
        val discovered = rawClipDiscovery.listClips()
        if (rawVideoManager.state.value is RawVideoState.Capturing) {
            return@runCatching discovered.filterNot { it.streamPending }
        }
        var recoveredPending = false
        discovered.filter { it.streamPending }.forEach { clip ->
            runCatching {
                val recovery = recoverRawClipPreallocatedTail(clip, forceInspect = true)
                if (recovery != null && recovery.recoveredFrames > 0) {
                    val published = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    check(application.contentResolver.update(clip.streamUri, published, null, null) > 0) {
                        "Recovered RAW stream was not published"
                    }
                    recoveredPending = true
                }
            }.onFailure { failure ->
                // A damaged/unaccompanied pending clip must neither publish silently nor hide good clips.
                Log.e(TAG, "RAW recovery retained pending clip ${clip.name}: ${failure.message}", failure)
            }
        }
        val current = if (recoveredPending) rawClipDiscovery.listClips() else discovered
        current.filterNot { it.streamPending }
    }.getOrDefault(emptyList())

    fun renderRawClipThumbnailArgb(clip: RawClipMediaStoreDiscovery.RawClipRef): ThumbnailImage? =
        runCatching {
            recoverRawClipPreallocatedTail(clip)
            val metadata = decodeRawClipMeta(clip)
            val firstFrame = application.contentResolver.openInputStream(clip.streamUri)
                ?.buffered()
                ?.use(NprawFrameCodec::readFrame)
                ?: return@runCatching null
            Raw12ThumbnailRenderer.render(firstFrame.samples, metadata)
        }.getOrNull()

    fun convertRawClipToMov(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        variant: NeoLogCurve.Variant,
        format: RawToNeoLogMovConverter.OutputFormat,
        onProgress: ConversionProgressListener?
    ): String = rawClipMovConversionWorkflow.convertNeoLog(clip, variant, format, onProgress)

    fun convertRawClipToNdrMov(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        format: RawToNeoLogMovConverter.OutputFormat,
        onProgress: ConversionProgressListener?
    ): String = rawClipMovConversionWorkflow.convertNdr(clip, deliveryProfile, format, onProgress)

    fun convertRawClipToCinemaDng(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        onProgress: ConversionProgressListener?
    ): String {
        val resolver = application.contentResolver
        recoverRawClipPreallocatedTail(clip)
        val metadata = decodeRawClipMeta(clip)
        dmcConversionRuntime.configure(
            DmcConversionWorkload(
                route = Niv99OfflineConversionPolicy.rawToRawDeveloped().route.name,
                width = metadata.width,
                height = metadata.height,
                sourceFps = metadata.fps.coerceAtLeast(1),
                compressedOutput = false
            )
        )
        val folderRelative =
            "${android.os.Environment.DIRECTORY_DOCUMENTS}/CamX/${clip.name}_CinemaDNG"
        val report = buildRawProgressReporter(clip, parallelism = 1, onProgress = onProgress)
        val rawIdentityPlan = Niv99OfflineConversionPolicy.rawToRawDeveloped()
        rawIdentityPlan.requireFrameContract(
            sourceWidth = metadata.width,
            sourceHeight = metadata.height,
            outputWidth = metadata.width,
            outputHeight = metadata.height,
            sourceFps = metadata.fps.coerceAtLeast(1),
            outputFps = metadata.fps.coerceAtLeast(1),
            outputBitDepth = maxOf(metadata.storageBitDepth, 16)
        )
        val cancellation = ConversionCancellationContext.currentSignal()
        val transaction = TransactionalMediaStoreArtifactSet(resolver)
        var frameCount = 0
        var firstSensorTimestampNs = 0L
        var lastSensorTimestampNs = 0L
        RawClipAudioSource.open(application, clip.audioUri, metadata.audioExpected).use { audioSource ->
        transaction.use {
            resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
                while (true) {
                    cancellation.throwIfCancellationRequested()
                    val frame = NprawFrameCodec.readFrame(input) ?: break
                    check(frame.timestamp > 0L && (frameCount == 0 || frame.timestamp > lastSensorTimestampNs)) {
                        "CinemaDNG source sensor timestamps are not monotonic"
                    }
                    if (frameCount == 0) firstSensorTimestampNs = frame.timestamp
                    lastSensorTimestampNs = frame.timestamp
                    RawCinemaDngIdentityPolicy.requirePreservedSensorFrame(
                        samples = frame.samples,
                        width = metadata.width,
                        height = metadata.height,
                        frameNumber = frameCount.toLong()
                    )
                    val displayName = RawCinemaDngWriter.frameFileName(frameCount)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, folderRelative)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val estimatedBytes = Math.addExact(
                        Math.multiplyExact(frame.samples.size.toLong(), Short.SIZE_BYTES.toLong()),
                        1L * 1024L * 1024L
                    )
                    transaction.stageGenerated(
                        collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        values = values,
                        expectedMaximumBytes = estimatedBytes,
                        label = "CinemaDNG frame ${frameCount + 1}",
                        cancellation = cancellation
                    ) { output ->
                        RawCinemaDngWriter.writeFrame(
                            out = output,
                            mosaic = frame.samples,
                            meta = metadata
                        )
                    }
                    frameCount++
                    report?.invoke(frameCount)
                }
            } ?: error("Failed to open RAW stream for CinemaDNG export")
            if (frameCount == 0) error("RAW clip had no decodable frames")
            audioSource.requireTimeline(firstSensorTimestampNs, lastSensorTimestampNs, metadata.fps)
            audioSource.master?.let { audioMaster ->
                val audioValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, RawClipMediaStoreDiscovery.AUDIO_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, folderRelative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                transaction.stageFile(
                    collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    values = audioValues,
                    source = Npv10AudioSidecarPolicy.sidecarFor(audioMaster),
                    label = "CinemaDNG captured RAW audio",
                    cancellation = cancellation
                )
            }
            cancellation.throwIfCancellationRequested()
            transaction.commit(cancellation)
        }
        }
        onRecordingSaved(folderRelative)
        return "$frameCount-frame CinemaDNG → $folderRelative"
    }

    fun deleteRawClip(clip: RawClipMediaStoreDiscovery.RawClipRef): Boolean =
        runCatching { rawClipDiscovery.deleteClip(clip) }.getOrDefault(false)

    private fun convertRecordDevelopMasterToMovAndPublish(
        clip: RecordDevelopMasterClip,
        deliveryFormat: RecordDevelopDeliveryFormat,
        onProgress: ConversionProgressListener?
    ): String {
        require(deliveryFormat != RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV) {
            "HEVC 10-bit 4:2:2 MOV requires a real P210/Y210 MediaCodec input path. " +
                "Use V210 10-bit 4:2:2 MOV for true 4:2:2 without chroma downgrade."
        }
        val outputRoot = File(
            application.externalCacheDir ?: application.cacheDir,
            "record_develop_mov_exports"
        )
        val tempOutput = File(
            outputRoot.apply { mkdirs() },
            "${clip.file.nameWithoutExtension}_${deliveryFormat.fileSuffix}." +
                RecordDevelopMovConverter.OUTPUT_EXTENSION
        )
        val sidecar = VideoDevelopColorSidecar.read(clip.file)
        dmcConversionRuntime.configure(
            DmcConversionWorkload(
                route = Niv99OfflineConversionPolicy.recordDevelop(
                    isNdr = clip.kind == RecordDevelopMasterKind.NDR
                ).route.name,
                width = sidecar?.width ?: 0,
                height = sidecar?.height ?: 0,
                sourceFps = sidecar?.fps?.coerceAtLeast(1) ?: DEFAULT_CONVERSION_FPS,
                compressedOutput = deliveryFormat.compressed
            )
        )
        val outputFormat = deliveryFormat.uncompressedFormat ?: RecordDevelopOutputFormat.R210_444
        val options = RecordDevelopConversionOptions(
            outputFormat = outputFormat,
            deliveryFormat = deliveryFormat,
            filmPrintStrength = RecordDevelopSdrLookPolicy.developFilmPrintStrength()
        )
        val directDisplayName = RecordDevelopDirectMovWorkflow.convertOrNull(
            resolver = application.contentResolver,
            clip = clip,
            deliveryFormat = deliveryFormat,
            sidecar = sidecar,
            options = options,
            outputRoot = outputRoot,
            diagnosticsExternalRoot = application.getExternalFilesDir(null) ?: outputRoot,
            onFrameFactory = { buildRecordDevelopProgressReporter(clip, onProgress) },
            onPublished = onRecordingSaved
        )
        if (directDisplayName != null) {
            Log.i(TAG, "Record-develop source preserved: ${clip.file.absolutePath}")
            return directDisplayName
        }
        RecordDevelopConversionDiagnostics.logStart(
            clip,
            tempOutput,
            options,
            sidecar != null,
            outputRoot,
            application.getExternalFilesDir(null) ?: outputRoot
        )
        return try {
            val audit = RecordDevelopMovConverter.convert(
                master = clip.file,
                output = tempOutput,
                sidecar = sidecar,
                options = options,
                sourceKind = clip.kind,
                readerFactory = { file -> RecordDevelopMediaCodecP010Reader(file) },
                onFrame = buildRecordDevelopProgressReporter(clip, onProgress),
                gpuAcceleratorFactory = { context -> NativeRecordDevelopGpuAccelerator(context) }
            )
            RecordDevelopConversionDiagnostics.logDone(audit, tempOutput)
            val developVerified = audit.frameCount > 0L &&
                audit.inputMasterHashUnchanged &&
                tempOutput.length() > 0L
            if (!developVerified) {
                Log.w(
                    TAG,
                    "Record-develop produced no verifiable master (frames=${audit.frameCount}, " +
                        "hashUnchanged=${audit.inputMasterHashUnchanged}, bytes=${tempOutput.length()}); " +
                        "source intermediate preserved: ${clip.file.absolutePath}"
                )
                throw IllegalStateException(
                    "Record-develop failed to produce a verifiable master; " +
                        "source retained: ${clip.file.absolutePath}"
                )
            }
            publishConvertedRecordDevelopMovToAlbum(tempOutput, clip, deliveryFormat)
        } catch (throwable: Throwable) {
            RecordDevelopConversionDiagnostics.logFailure(
                clip,
                tempOutput,
                options,
                outputRoot,
                throwable
            )
            throw throwable
        } finally {
            runCatching { RecordDevelopMovConverter.certificateSidecarFile(tempOutput).delete() }
            runCatching { tempOutput.delete() }
        }
    }

    private fun buildRecordDevelopProgressReporter(
        clip: RecordDevelopMasterClip,
        onProgress: ConversionProgressListener?
    ): ((Int) -> Unit)? {
        val uiReporter: ((Int) -> Unit)? = onProgress?.let { listener ->
            val estimator = ConversionEtaEstimator(
                framesTotal = RecordDevelopMovConverter.estimateFrameCount(clip.file).toLong(),
                pathLabel = "DMC sustained CPU/GPU pipeline",
                startNanos = System.nanoTime()
            )
            listener(estimator.startSnapshot())
            val callback: (Int) -> Unit = { frames ->
                listener(estimator.onFrameCompleted(System.nanoTime(), frames.toLong()))
            }
            callback
        }
        return { frames ->
            dmcConversionRuntime.onFramesCommitted(frames.toLong())
            uiReporter?.invoke(frames)
        }
    }

    private fun publishConvertedRecordDevelopMovToAlbum(
        convertedMov: File,
        clip: RecordDevelopMasterClip,
        deliveryFormat: RecordDevelopDeliveryFormat
    ): String {
        val laneTag = clip.kind.name.lowercase(Locale.US)
        val displayName =
            "${clip.file.nameWithoutExtension}_${laneTag}_${deliveryFormat.fileSuffix}_dev.mov"
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/quicktime")
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/CamX")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val cancellation = ConversionCancellationContext.currentSignal()
        return TransactionalMediaStoreArtifactSet(application.contentResolver).use { transaction ->
            val staged = transaction.stageFile(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values = values,
                source = convertedMov,
                label = "record-develop MOV",
                cancellation = cancellation
            )
            transaction.commit(cancellation)
            onRecordingSaved(staged.uri.toString())
            displayName
        }
    }

    private fun buildNpv10ProgressReporter(
        clip: Npv10MasterClip,
        onProgress: ConversionProgressListener?
    ): ((Int) -> Unit)? {
        val label = if (Npv10ExportGpuAccelerator.isAccelerationAvailable()) {
            "DMC sustained GPU (Vulkan) + CPU"
        } else {
            "DMC sustained CPU"
        }
        val uiReporter: ((Int) -> Unit)? = onProgress?.let { listener ->
            val estimator = ConversionEtaEstimator(
                framesTotal = Npv10MovConverter.estimateFrameCount(clip.file).toLong(),
                pathLabel = label,
                startNanos = System.nanoTime()
            )
            listener(estimator.startSnapshot())
            val callback: (Int) -> Unit = { frames ->
                listener(estimator.onFrameCompleted(System.nanoTime(), frames.toLong()))
            }
            callback
        }
        return { frames ->
            dmcConversionRuntime.onFramesCommitted(frames.toLong())
            uiReporter?.invoke(frames)
        }
    }

    private fun buildRawProgressReporter(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        parallelism: Int,
        onProgress: ConversionProgressListener?,
        knownFrameCount: Long? = null
    ): ((Int) -> Unit)? {
        val uiReporter: ((Int) -> Unit)? = onProgress?.let { listener ->
            val estimator = ConversionEtaEstimator(
                framesTotal = knownFrameCount ?: countRawClipFrames(clip).toLong(),
                pathLabel = "DMC sustained CPU x$parallelism",
                startNanos = System.nanoTime()
            )
            listener(estimator.startSnapshot())
            val callback: (Int) -> Unit = { frames ->
                listener(estimator.onFrameCompleted(System.nanoTime(), frames.toLong()))
            }
            callback
        }
        return { frames ->
            dmcConversionRuntime.onFramesCommitted(frames.toLong())
            uiReporter?.invoke(frames)
        }
    }

    private fun recoverRawClipPreallocatedTail(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        forceInspect: Boolean = clip.streamPending
    ): NprawRecoveryResult? {
        if (rawVideoManager.state.value is RawVideoState.Capturing) return null
        val descriptor = application.contentResolver.openFileDescriptor(clip.streamUri, "rw")
            ?: return null
        val fileDescriptor = descriptor.fileDescriptor
        val originalBytes = android.system.Os.fstat(fileDescriptor).st_size
        val looksReserved = originalBytes in
            RecordingPreallocationPolicy.MIN_RESERVATION_BYTES..
                RecordingPreallocationPolicy.HARD_MAX_BYTES &&
            originalBytes % RecordingPreallocationPolicy.BLOCK_BYTES == 0L
        if (!forceInspect && !looksReserved) {
            descriptor.close()
            return null
        }
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).buffered().use { input ->
            val result = NprawStreamRecovery.scan(input, originalBytes)
            if (result.recoveredFrames > 0) {
                val metadata = decodeRawClipMeta(clip)
                RawClipAudioSource.open(application, clip.audioUri, metadata.audioExpected).use { audio ->
                    audio.requireTimeline(result.firstSensorTimestampNs, result.lastSensorTimestampNs, metadata.fps)
                }
            }
            if (result.shouldTruncate) {
                android.system.Os.ftruncate(fileDescriptor, result.committedBytes)
                android.system.Os.fsync(fileDescriptor)
                Log.w(
                    TAG,
                    "Recovered NPRAW reservation tail: ${result.originalBytes} -> " +
                        "${result.committedBytes} bytes, frames=${result.recoveredFrames}"
                )
            }
            result
        }
    }

    private fun countRawClipFrames(clip: RawClipMediaStoreDiscovery.RawClipRef): Int = runCatching {
        val resolver = application.contentResolver
        val fixedCount = resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            NprawFrameCodec.estimateFixedFrameCount(input, clip.streamBytes)
        }
        fixedCount ?: resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            NprawFrameCodec.countFrames(input)
        } ?: 0
    }.getOrDefault(0)

    private fun decodeRawClipMeta(
        clip: RawClipMediaStoreDiscovery.RawClipRef
    ): RawDevelopMetadataSidecar.RawDevelopMetadata {
        val bytes = application.contentResolver.openInputStream(clip.metaUri)?.use { it.readBytes() }
            ?: error("Failed to open develop.meta")
        return RawDevelopMetadataSidecar.decode(bytes)
    }

    private fun convertNpv10MasterToMovAndPublish(
        clip: Npv10MasterClip,
        outputFormat: Npv10MovConverter.OutputFormat,
        onProgress: ConversionProgressListener?
    ): String {
        Npv10VerifiedMasterPublication.requireVerifiedForConversion(clip.file)
        val header = Npv10FileReader(clip.file).use { it.header }
        dmcConversionRuntime.configure(
            DmcConversionWorkload(
                route = Niv99OfflineConversionPolicy.npv10(header.metadata).route.name,
                width = header.width,
                height = header.height,
                sourceFps = header.fps.coerceAtLeast(1),
                compressedOutput = outputFormat == Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
            )
        )
        val outputRoot = File(application.cacheDir, "npv10_mov_exports")
        val tempOutput = Npv10MovConverter.defaultOutputFile(clip.file, outputRoot, outputFormat)
        val gradeLut = colorScience.getPreviewLutFloatArray(recording = false)
        val progressReporter = buildNpv10ProgressReporter(clip, onProgress)
        val directName = Npv10DirectMovWorkflow.convertOrNull(
            resolver = application.contentResolver,
            clip = clip,
            proposedOutput = tempOutput,
            outputFormat = outputFormat,
            gradeLut = gradeLut,
            onFrame = { frames -> progressReporter?.invoke(frames) },
            onPublished = onRecordingSaved
        )
        if (directName != null) return directName
        return try {
            val audit = Npv10MovConverter.convert(
                clip.file,
                tempOutput,
                gradeLut = gradeLut,
                onFrame = { frames -> progressReporter?.invoke(frames) },
                outputFormat = outputFormat
            )
            if (audit.auditStatus != Npv10AuditStatus.PASS) {
                Log.w(
                    TAG,
                    "NPV10 MOV conversion audit FAILED (status=${audit.auditStatus}); " +
                        "source master preserved: ${clip.file.absolutePath}"
                )
                throw IllegalStateException(
                    "NPV10 MOV conversion audit failed (${audit.auditStatus}); " +
                        "master retained: ${clip.file.absolutePath}"
                )
            }
            publishConvertedNpv10MovToAlbum(tempOutput, clip.file)
        } finally {
            runCatching { Npv10MovConverter.certificateSidecarFile(tempOutput).delete() }
            runCatching { Npv10MovConverter.focusMapSidecarFile(tempOutput).delete() }
            runCatching { tempOutput.delete() }
        }
    }

    private fun publishConvertedNpv10MovToAlbum(convertedMov: File, sourceMaster: File): String {
        require(!Npv10MovPublishPolicy.galleryEligibility(sourceMaster).publishable) {
            "NPV10 private master must not be published to gallery"
        }
        val plan = Npv10MovPublishPolicy.resolveConvertedMov(convertedMov)
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, plan.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, plan.mimeType)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, plan.relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val cancellation = ConversionCancellationContext.currentSignal()
        return TransactionalMediaStoreArtifactSet(application.contentResolver).use { transaction ->
            val staged = transaction.stageFile(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values = values,
                source = convertedMov,
                label = "NPV10 MOV",
                cancellation = cancellation
            )
            transaction.commit(cancellation)
            onRecordingSaved(staged.uri.toString())
            plan.displayName
        }
    }

    private companion object {
        const val TAG = "CameraMediaConversion"
        const val DEFAULT_CONVERSION_FPS = 25
    }
}
