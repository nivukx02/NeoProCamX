package com.neoproc.neoproccamx.recording

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import java.io.File
import java.util.UUID

/**
 * Owns RAW-master to MOV conversion and MediaStore publication outside the camera state owner.
 * Source clips are opened read-only, published derivatives are transactional, and the immutable RAW
 * master is never deleted or rewritten by this workflow.
 */
class RawClipMovConversionWorkflow(
    private val application: Application,
    private val prepareSource: (RawClipMediaStoreDiscovery.RawClipRef) -> Unit,
    private val progressReporterFactory: (
        RawClipMediaStoreDiscovery.RawClipRef,
        Int,
        ConversionProgressListener?,
        Long?
    ) -> ((Int) -> Unit)?,
    private val onPublished: (String) -> Unit,
    private val coordinator: Raw12ClipConversionCoordinator = Raw12ClipConversionCoordinator(),
    private val onWorkloadResolved: (
        RawDevelopMetadataSidecar.RawDevelopMetadata,
        RawToNeoLogMovConverter.Options
    ) -> Unit = { _, _ -> }
) {
    private data class CompanionArtifact(
        val source: File,
        val displayName: String,
        val mimeType: String
    )

    private companion object {
        const val TAG = "RawMovConversion"
        const val RELATIVE_OUTPUT_PATH = "Movies/CamX"
        const val CUBE_LUT_MIME_TYPE = "application/octet-stream"
    }

    fun convertNeoLog(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        variant: NeoLogCurve.Variant,
        format: RawToNeoLogMovConverter.OutputFormat,
        onProgress: ConversionProgressListener?
    ): String = convert(
        clip = clip,
        options = RawToNeoLogMovConverter.Options(variant = variant, format = format),
        stagingDirectory = "raw_neolog_mov_exports",
        outputName = "${clip.name}_neolog_${format.fileSuffix}.mov",
        onProgress = onProgress
    )

    fun convertNdr(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        format: RawToNeoLogMovConverter.OutputFormat = RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420,
        onProgress: ConversionProgressListener?
    ): String {
        require(deliveryProfile != RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
            "RAW NDR conversion requires NDR10+ or NIVUKX Vision"
        }
        return convert(
            clip = clip,
            options = RawToNeoLogMovConverter.Options(format = format, deliveryProfile = deliveryProfile),
            stagingDirectory = "raw_ndr_mov_exports",
            outputName = "${clip.name}_${deliveryProfile.fileSuffix}_${format.fileSuffix}.mov",
            onProgress = onProgress
        )
    }

    private fun convert(
        clip: RawClipMediaStoreDiscovery.RawClipRef,
        options: RawToNeoLogMovConverter.Options,
        stagingDirectory: String,
        outputName: String,
        onProgress: ConversionProgressListener?
    ): String {
        ConversionCancellationContext.throwIfCancellationRequested()
        prepareSource(clip)
        ConversionCancellationContext.throwIfCancellationRequested()
        val resolver = application.contentResolver
        val metadataBytes = resolver.openInputStream(clip.metaUri)?.use { it.readBytes() }
            ?: error("Failed to open develop.meta")
        val metadata = RawDevelopMetadataSidecar.decode(metadataBytes)
        RawClipAudioSource.open(application, clip.audioUri, metadata.audioExpected).use { audioSource ->
        onWorkloadResolved(metadata, options)
        val expectedFrameCount = exactFrameCount(clip)
        val parallelism = ConversionParallelismPolicy.workerCount(
            RawDevelopMemoryPolicy.cpuDirectPackPeakBytes(
                width = metadata.width,
                height = metadata.height,
                format = options.format.memoryReferenceFormat
            )
        )
        // exactFrameCount already inspected the source container. Pass it through so the progress layer
        // never performs a second full master scan before expensive pixel processing begins.
        val progressReporter = progressReporterFactory(
            clip,
            parallelism,
            onProgress,
            expectedFrameCount
        )
        val onFrame: (Int) -> Unit = { framesDone ->
            ConversionCancellationContext.throwIfCancellationRequested()
            progressReporter?.invoke(framesDone)
            ConversionCancellationContext.throwIfCancellationRequested()
        }
        val stagingRoot = File(application.cacheDir, stagingDirectory)
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) {
            "Failed to create RAW conversion staging directory"
        }
        val direct = SeekableMediaStoreMovOutput.openOrNull(resolver, outputName)
        if (direct != null) {
            direct.use { target ->
                val anchor = File(
                    stagingRoot,
                    "${target.transactionId}_${outputName.take(96)}"
                ).absolutePath
                val localArtifacts = plannedLocalArtifacts(anchor, options)
                val pendingCompanionUris = mutableListOf<Uri>()
                try {
                    ConversionCancellationContext.throwIfCancellationRequested()
                    resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
                        coordinator.convert(
                            streamInput = input,
                            meta = metadata,
                            options = options,
                            outputPath = target.outputFile.absolutePath,
                            parallelism = parallelism,
                            onFrame = onFrame,
                            viewingLutAnchorPath = anchor,
                            outputOwnership = MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR,
                            expectedFrameCount = expectedFrameCount,
                            audioSidecarMaster = audioSource.master
                        )
                    } ?: error(
                        "Failed to open RAW stream for ${options.deliveryProfile.displayName} conversion"
                    )
                    ConversionCancellationContext.throwIfCancellationRequested()
                    val companions = resolveCompanionArtifacts(anchor, outputName, options)
                    stageCompanions(companions, pendingCompanionUris)
                    // Publish companions first: a visible NeoLog MOV must never precede its required LUT.
                    pendingCompanionUris.forEach(::publishPendingStrict)
                    ConversionCancellationContext.throwIfCancellationRequested()
                    val uri = target.commit()
                    cleanupLocalArtifacts(localArtifacts)
                    notifyPublishedSafely(uri)
                    return outputName
                } catch (failure: Throwable) {
                    rollbackMediaStoreRows(pendingCompanionUris, failure)
                    cleanupLocalArtifacts(localArtifacts, failure)
                    throw failure
                }
            }
        }
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val temporaryOutput = File(stagingRoot, "${transactionId}_${outputName.take(96)}")
        val localArtifacts = listOf(temporaryOutput) + plannedLocalArtifacts(
            temporaryOutput.absolutePath,
            options
        )
        return try {
            ConversionCancellationContext.throwIfCancellationRequested()
            resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
                coordinator.convert(
                    streamInput = input,
                    meta = metadata,
                    options = options,
                    outputPath = temporaryOutput.absolutePath,
                    parallelism = parallelism,
                    onFrame = onFrame,
                    expectedFrameCount = expectedFrameCount,
                    audioSidecarMaster = audioSource.master
                )
            } ?: error("Failed to open RAW stream for ${options.deliveryProfile.displayName} conversion")
            ConversionCancellationContext.throwIfCancellationRequested()
            publishVerifiedTransaction(
                convertedMov = temporaryOutput,
                displayName = outputName,
                companions = resolveCompanionArtifacts(temporaryOutput.absolutePath, outputName, options)
            )
        } finally {
            cleanupLocalArtifacts(localArtifacts)
        }
        }
    }

    private fun exactFrameCount(clip: RawClipMediaStoreDiscovery.RawClipRef): Long? {
        val resolver = application.contentResolver
        val fixed = resolver.openInputStream(clip.streamUri)?.buffered()?.use { input ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.estimateFixedFrameCount(
                input,
                clip.streamBytes
            )
        }
        val count = fixed ?: resolver.openInputStream(clip.streamUri)?.buffered()?.use(
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec::countFrames
        )
        return count?.toLong()?.takeIf { it > 0L }
    }

    private fun publishVerifiedTransaction(
        convertedMov: File,
        displayName: String,
        companions: List<CompanionArtifact>
    ): String {
        val resolver = application.contentResolver
        val createdUris = mutableListOf<Uri>()
        try {
            ConversionCancellationContext.throwIfCancellationRequested()
            ConversionStorageAdmissionPolicy.requireAdmission(
                ConversionStorageAdmissionPolicy.externalAvailableBytes(),
                convertedMov.length(),
                "RAW MOV publication"
            )
            val movUri = createPendingVideo(displayName)
            createdUris += movUri
            stageAndVerify(convertedMov, movUri, "RAW MOV")
            stageCompanions(companions, createdUris)
            // A visible MOV is the final commit point; all required companion rows become visible first.
            createdUris.filterNot { it == movUri }.forEach(::publishPendingStrict)
            ConversionCancellationContext.throwIfCancellationRequested()
            publishPendingStrict(movUri)
            notifyPublishedSafely(movUri)
            return displayName
        } catch (failure: Throwable) {
            rollbackMediaStoreRows(createdUris, failure)
            throw failure
        }
    }

    private fun plannedLocalArtifacts(
        anchorPath: String,
        options: RawToNeoLogMovConverter.Options
    ): List<File> = buildList {
        add(File("$anchorPath.elite-conversion-certificate.txt"))
        if (options.deliveryProfile == RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
            add(NeoLogRec709LutWriter.sidecarFile(anchorPath))
        }
    }

    private fun resolveCompanionArtifacts(
        anchorPath: String,
        outputName: String,
        options: RawToNeoLogMovConverter.Options
    ): List<CompanionArtifact> {
        val safeBase = outputName.substringBeforeLast('.').take(160)
        val certificate = File("$anchorPath.elite-conversion-certificate.txt")
        check(certificate.isFile && certificate.length() > 0L) {
            "RAW conversion certificate is missing: ${certificate.absolutePath}"
        }
        return buildList {
            add(
                CompanionArtifact(
                    certificate,
                    "$safeBase.elite-conversion-certificate.txt",
                    "text/plain"
                )
            )
            if (options.deliveryProfile == RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
                val lut = NeoLogRec709LutWriter.sidecarFile(anchorPath)
                check(lut.isFile && lut.length() > 0L) {
                    "Required NeoLog companion LUT is missing: ${lut.absolutePath}"
                }
                add(CompanionArtifact(lut, "${safeBase}_NeoLog_to_Rec709.cube", CUBE_LUT_MIME_TYPE))
            }
        }
    }

    private fun stageCompanions(
        companions: List<CompanionArtifact>,
        createdUris: MutableList<Uri>
    ) {
        companions.forEach { companion ->
            ConversionCancellationContext.throwIfCancellationRequested()
            ConversionStorageAdmissionPolicy.requireAdmission(
                ConversionStorageAdmissionPolicy.externalAvailableBytes(),
                companion.source.length(),
                companion.displayName
            )
            val uri = createPendingCompanion(companion)
            createdUris += uri
            stageAndVerify(companion.source, uri, companion.displayName)
        }
    }

    private fun createPendingVideo(displayName: String): Uri {
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/quicktime")
            put(MediaStore.MediaColumns.DATE_ADDED, nowMs / 1_000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowMs / 1_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_OUTPUT_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return application.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create pending MediaStore RAW MOV")
    }

    private fun createPendingCompanion(companion: CompanionArtifact): Uri {
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, companion.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, companion.mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, nowMs / 1_000L)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowMs / 1_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    RecordingMediaStoreLifecyclePolicy.resolveMetadataSidecarRelativePath()
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            "external"
        }
        return application.contentResolver.insert(MediaStore.Files.getContentUri(volume), values)
            ?: error("Failed to create pending MediaStore companion ${companion.displayName}")
    }

    private fun stageAndVerify(source: File, uri: Uri, label: String) {
        ConversionCancellationContext.throwIfCancellationRequested()
        check(source.isFile && source.length() > 0L) { "$label source is missing or empty" }
        val resolver = application.contentResolver
        val sourceEvidence = resolver.openOutputStream(uri, "w")?.use { output ->
            source.inputStream().buffered().use { input ->
                VerifiedArtifactCopy.copyAndMeasure(
                    input,
                    output,
                    ConversionCancellationContext::shouldContinue
                )
            }
        } ?: error("Failed to open pending MediaStore output for $label")
        check(sourceEvidence.bytes == source.length()) {
            "$label source length changed during copy: expected=${source.length()} copied=${sourceEvidence.bytes}"
        }
        val destinationEvidence = resolver.openInputStream(uri)?.buffered()?.use { input ->
            VerifiedArtifactCopy.measure(input, ConversionCancellationContext::shouldContinue)
        }
            ?: error("Failed to read back pending MediaStore output for $label")
        VerifiedArtifactCopy.requireExact(sourceEvidence, destinationEvidence, label)
    }

    private fun publishPendingStrict(uri: Uri) {
        ConversionCancellationContext.throwIfCancellationRequested()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val updatedRows = application.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        check(updatedRows > 0) { "MediaStore did not publish pending artifact $uri" }
    }

    private fun rollbackMediaStoreRows(uris: List<Uri>, failure: Throwable) {
        uris.asReversed().distinct().forEach { uri ->
            runCatching {
                val deletedRows = application.contentResolver.delete(uri, null, null)
                check(deletedRows > 0) { "MediaStore did not roll back artifact $uri" }
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }
    }

    private fun cleanupLocalArtifacts(files: List<File>, failure: Throwable? = null) {
        files.distinctBy { it.absoluteFile.normalize().path }.forEach { file ->
            if (file.exists() && (!file.isFile || !file.delete() || file.exists())) {
                val cleanupFailure = IllegalStateException(
                    "Failed to remove local conversion artifact ${file.absolutePath}"
                )
                if (failure != null) failure.addSuppressed(cleanupFailure)
                else NeoLogger.w(TAG, cleanupFailure.message.orEmpty(), cleanupFailure)
            }
        }
    }

    private fun notifyPublishedSafely(uri: Uri) {
        runCatching { onPublished(uri.toString()) }
            .exceptionOrNull()
            ?.let { NeoLogger.w(TAG, "RAW MOV published but notification callback failed", it) }
    }
}
