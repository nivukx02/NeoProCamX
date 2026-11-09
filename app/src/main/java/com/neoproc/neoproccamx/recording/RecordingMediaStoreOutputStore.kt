package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.io.File
import java.io.IOException
import android.os.ParcelFileDescriptor.AutoCloseInputStream

internal data class RecordingOutputTarget(
    val uri: Uri?,
    val parcelFileDescriptor: ParcelFileDescriptor?,
    val file: File?,
    val pending: Boolean,
    val selection: RecordingContainerSelection,
    val descriptor: RecordingOutputTargetDescriptor,
    val colorMetadata: RecordingMediaStoreColorMetadata,
    val storageKind: RecordingOutputStorageKind
) {
    fun writablePath(): String =
        file?.absolutePath
            ?: "/proc/self/fd/${parcelFileDescriptor?.fd ?: error("Missing output descriptor")}"
}

internal data class RecordingFinalizedOutputEvidence(
    val outputPath: String,
    val fileName: String,
    val fileBytes: Long,
    val mediaStoreValidated: Boolean,
    val storageKind: RecordingOutputStorageKind
)

internal data class PendingMovRecoverySummary(
    val examined: Int,
    val published: Int,
    val deletedInvalid: Int,
    val retainedOnError: Int
)

internal class RecordingMediaStoreOutputStore(
    private val context: Context
) {
    /** Recovers only rows that predate this process, so a newly started recording cannot be touched. */
    fun recoverPendingMovOutputsCreatedBefore(cutoffEpochSeconds: Long): PendingMovRecoverySummary {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return PendingMovRecoverySummary(0, 0, 0, 0)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE
        )
        val ownedRelativePaths = RecordingOutputRouting.ownedPendingMovStoredPathValues()
        val ownedPathPlaceholders = ownedRelativePaths.joinToString(separator = ",") { "?" }
        val selection =
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN ($ownedPathPlaceholders) AND " +
                "${MediaStore.MediaColumns.MIME_TYPE} = ? AND " +
                "${MediaStore.MediaColumns.DATE_ADDED} <= ?"
        val args = mutableListOf(context.packageName).apply {
            addAll(ownedRelativePaths)
            add(QUICKTIME_MIME_TYPE)
            add(cutoffEpochSeconds.coerceAtLeast(0L).toString())
        }.toTypedArray()
        var examined = 0
        var published = 0
        var deletedInvalid = 0
        var retainedOnError = 0
        try {
            OwnedPendingMediaStoreQuery.query(
                resolver, collection, projection, context.packageName, selection, args
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    examined += 1
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                    val recoverable = runCatching {
                        resolver.openFileDescriptor(uri, "r")?.let { descriptor ->
                            AutoCloseInputStream(descriptor).use { input ->
                                val inspection = MovRecoveryInspector.inspectPending(input.channel, size)
                                if (inspection.sizeMetadataStale) {
                                    NeoLogger.i(
                                        "RecordingOutputStore",
                                        "Pending MOV size metadata stale uri=$uri reportedBytes=$size " +
                                            "descriptorBytes=${inspection.descriptorSizeBytes}; " +
                                            "validationAuthority=READ_ONLY_FILE_DESCRIPTOR"
                                    )
                                }
                                inspection.recoverable
                            }
                        } ?: throw IOException("Unable to open pending MOV $uri")
                    }.onFailure { error ->
                        Log.w(TAG, "Pending MOV validation failed; retaining $uri", error)
                    }.getOrNull()
                    when (recoverable) {
                        true -> {
                            val updated = runCatching {
                                resolver.update(
                                    uri,
                                    ContentValues().apply {
                                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                                    },
                                    null,
                                    null
                                )
                            }.getOrDefault(0)
                            if (updated == 1) published += 1 else retainedOnError += 1
                        }
                        false -> {
                            val removed = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
                            if (removed == 1) {
                                NeoLogger.i(
                                    "RecordingOutputStore",
                                    "Removed structurally invalid pending MOV uri=$uri " +
                                        "validationAuthority=READ_ONLY_FILE_DESCRIPTOR"
                                )
                            }
                            if (removed == 1) deletedInvalid += 1 else retainedOnError += 1
                        }
                        null -> retainedOnError += 1
                    }
                }
            }
        } catch (error: Exception) {
            retainedOnError += 1
            Log.w(TAG, "Pending MOV startup recovery query failed", error)
        }
        return PendingMovRecoverySummary(examined, published, deletedInvalid, retainedOnError).also {
            NeoLogger.i(
                "RecordingOutputStore",
                "startup pending-MOV recovery examined=${it.examined} published=${it.published} " +
                    "deletedInvalid=${it.deletedInvalid} retainedOnError=${it.retainedOnError}"
            )
        }
    }

    fun create(
        contract: RecordingMovOutputContract,
        developMasterFile: File? = null
    ): RecordingOutputTarget {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = Build.VERSION.SDK_INT,
            contract = contract
        )
        // Record-then-develop LIGHT lane: write the flat intermediate straight into the app-private
        // develop-master File (never MediaStore). Additive + default-OFF: developMasterFile is null on
        // every normal recording, so the proven MediaStore/legacy publish flow below is byte-identical.
        if (developMasterFile != null) {
            return createPrivateDevelopTarget(contract = contract, plan = plan, file = developMasterFile)
        }
        return when (plan.storageKind) {
            RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE -> createMediaStoreTarget(
                contract = contract,
                plan = plan
            )
            RecordingOutputStorageKind.LEGACY_PUBLIC_FILE -> createLegacyFileTarget(
                contract = contract,
                plan = plan
            )
            // resolveCreate only ever yields the two public kinds; the private-develop target is produced
            // via the developMasterFile path above, never here.
            RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER ->
                error("PRIVATE_DEVELOP_MASTER target is created via the developMasterFile path")
        }
    }

    fun finalize(
        target: RecordingOutputTarget?,
        finalDescription: String
    ): RecordingFinalizedOutputEvidence? {
        if (target == null) return null
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = target.storageKind,
            pending = target.pending,
            finalDescription = finalDescription
        )
        var professionalSidecarUri: Uri? = null
        runCatching {
            NeoLogger.critical(
                "RecordingOutputStore",
                "finalize START storageKind=${target.storageKind} pending=${target.pending} " +
                    "publishMediaStore=${decision.publishMediaStore} scanLegacyFile=${decision.scanLegacyFile} " +
                    "uri=${target.uri} file=${target.file?.absolutePath}"
            )
        }
        try {
            if (decision.closeDescriptorFirst) {
                closeDescriptorQuietly(target)
            }
            when {
                decision.publishMediaStore && target.uri != null -> {
                    professionalSidecarUri = if (decision.writeSidecarMetadata) {
                        writeProfessionalSidecarMetadata(target, decision)
                    } else {
                        null
                    }
                    publishMediaStoreTarget(
                        uri = target.uri,
                        storageKind = target.storageKind,
                        description = decision.finalDescription,
                        colorMetadata = decision.colorMetadata,
                        sidecarUri = professionalSidecarUri
                    )
                }
                decision.scanLegacyFile && target.file != null -> {
                    if (decision.writeSidecarMetadata) {
                        professionalSidecarUri = writeProfessionalSidecarMetadata(target, decision)
                    }
                    scanLegacyTarget(target)
                }
            }
            val evidence = resolvedOutputEvidence(target)
            if (target.uri != null && !evidence.mediaStoreValidated) {
                throw IOException(
                    "MediaStore row was not durably published: uri=${target.uri} bytes=${evidence.fileBytes}"
                )
            }
            runCatching {
                NeoLogger.critical(
                    "RecordingOutputStore",
                    "finalize DONE bytes=${evidence.fileBytes} validated=${evidence.mediaStoreValidated} " +
                        "path=${evidence.outputPath}"
                )
            }
            return evidence
        } catch (e: Exception) {
            Log.e(TAG, "finalize output target failed; attempting pending-flag recovery", e)
            // Recovery: the rich publish update() threw, which previously left an invisible IS_PENDING=1
            // row (recorded bytes stranded) while the caller still reported "saved". Try the minimal
            // publish — just clear IS_PENDING — so the clip becomes visible in the gallery.
            val recovered = target.uri?.let { uri ->
                runCatching {
                    val updatedRows = context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                    updatedRows == 1
                }.getOrDefault(false)
            } ?: false
            if (recovered) {
                val recoveredEvidence = resolvedOutputEvidence(target)
                runCatching {
                    NeoLogger.critical(
                        "RecordingOutputStore",
                        "finalize RECOVERED: cleared IS_PENDING after publish failure uri=${target.uri} " +
                            "validated=${recoveredEvidence.mediaStoreValidated} bytes=${recoveredEvidence.fileBytes}"
                    )
                }
                if (recoveredEvidence.mediaStoreValidated) return recoveredEvidence
                // Clearing IS_PENDING is not success by itself. If the published row is empty,
                // unreadable, or otherwise unverifiable, delete it below instead of retaining a
                // corrupt visible item while returning null to the caller.
            }
            // Unrecoverable: the clip and its metadata are one publication transaction. Remove the
            // orphaned pending entry and any sidecar that was already committed for it.
            runCatching {
                NeoLogger.critical(
                    "RecordingOutputStore",
                    "finalize FAILED and unrecoverable; removing orphaned pending entry uri=${target.uri} " +
                        "error=${e.javaClass.simpleName}:${e.message}"
                )
            }
            cleanupProfessionalSidecarAfterFinalizationFailure(professionalSidecarUri)
            cleanupFailure(target)
            return null
        }
    }

    fun cleanupFailure(target: RecordingOutputTarget?) {
        if (target == null) return
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = target.storageKind,
            hasUri = target.uri != null,
            hasFile = target.file != null
        )
        try {
            if (decision.closeDescriptorFirst) {
                closeDescriptorQuietly(target)
            }
            when {
                decision.deleteMediaStoreUri && target.uri != null ->
                    context.contentResolver.delete(target.uri, null, null)
                decision.deleteLegacyFile ->
                    target.file?.delete()
                decision.deletePrivateDevelopFile ->
                    target.file?.delete()
            }
            runCatching {
                NeoLogger.critical(
                    "RecordingOutputStore",
                    "cleanupFailure: removed unfinalized recording target uri=${target.uri} file=${target.file?.absolutePath}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed output target failed", e)
        }
    }

    private fun createMediaStoreTarget(
        contract: RecordingMovOutputContract,
        plan: RecordingOutputCreatePlan
    ): RecordingOutputTarget {
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, plan.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, plan.mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, nowMs / 1000)
            put(MediaStore.MediaColumns.DATE_TAKEN, nowMs)
            put(MediaStore.MediaColumns.RELATIVE_PATH, plan.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (plan.storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO) {
                put(MediaStore.Video.Media.DESCRIPTION, plan.description)
                putColorMetadataIfSupported(plan.colorMetadata)
            }
        }
        val collection = if (plan.storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Failed to open MediaStore file descriptor")
        } catch (failure: Throwable) {
            // insert() has already created an app-owned pending row. Roll it back on every descriptor
            // acquisition failure so process startup never has to discover an empty orphan from a
            // create operation that never returned a target to its caller.
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { cleanupError ->
                    Log.w(TAG, "Failed to remove MediaStore row after descriptor-open failure: $uri", cleanupError)
                }
            throw failure
        }
        runCatching {
            NeoLogger.i(
                "RecordingOutputStore",
                "created pending MediaStore entry uri=$uri displayName=${plan.displayName} (IS_PENDING=1)"
            )
        }
        return RecordingOutputTarget(
            uri = uri,
            parcelFileDescriptor = pfd,
            file = null,
            pending = plan.pending,
            selection = contract.selection,
            descriptor = contract.descriptor,
            colorMetadata = plan.colorMetadata,
            storageKind = plan.storageKind
        )
    }

    private fun createLegacyFileTarget(
        contract: RecordingMovOutputContract,
        plan: RecordingOutputCreatePlan
    ): RecordingOutputTarget {
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(plan.legacyRootDirectory),
            plan.legacySubdirectory
        ).also { it.mkdirs() }
        return RecordingOutputTarget(
            uri = null,
            parcelFileDescriptor = null,
            file = File(baseDir, plan.displayName),
            pending = plan.pending,
            selection = contract.selection,
            descriptor = contract.descriptor,
            colorMetadata = plan.colorMetadata,
            storageKind = plan.storageKind
        )
    }

    private fun createPrivateDevelopTarget(
        contract: RecordingMovOutputContract,
        plan: RecordingOutputCreatePlan,
        file: File
    ): RecordingOutputTarget {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return RecordingOutputTarget(
            uri = null,
            parcelFileDescriptor = null,
            file = file,
            pending = false,
            selection = contract.selection,
            descriptor = contract.descriptor,
            colorMetadata = plan.colorMetadata,
            storageKind = RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER
        )
    }

    private fun publishMediaStoreTarget(
        uri: Uri,
        storageKind: RecordingOutputStorageKind,
        description: String,
        colorMetadata: RecordingMediaStoreColorMetadata,
        sidecarUri: Uri?
    ) {
        val finalDescription = if (sidecarUri != null) {
            "$description;sidecar=$sidecarUri"
        } else {
            description
        }
        val publishValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO) {
                put(MediaStore.Video.Media.DESCRIPTION, finalDescription)
                putColorMetadataIfSupported(colorMetadata)
            }
        }
        val updatedRows = context.contentResolver.update(uri, publishValues, null, null)
        if (updatedRows != 1) {
            throw IOException("MediaStore publish updated $updatedRows rows for $uri; expected exactly one")
        }
        Log.i(TAG, "Saved to MediaStore: $uri")
    }

    private fun scanLegacyTarget(target: RecordingOutputTarget) {
        val file = target.file ?: return
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(target.descriptor.mimeType),
            null
        )
        Log.i(TAG, "Saved to public storage: ${file.absolutePath}")
    }

    private fun resolvedOutputEvidence(target: RecordingOutputTarget): RecordingFinalizedOutputEvidence {
        val mediaStoreState = target.uri?.let(::awaitMediaStorePublicationState)
        val fileBytes = when {
            target.file?.isFile == true -> target.file.length()
            mediaStoreState != null -> mediaStoreState.sizeBytes
            else -> 0L
        }.coerceAtLeast(0L)
        return RecordingFinalizedOutputEvidence(
            outputPath = target.uri?.toString() ?: target.file?.absolutePath ?: target.writablePath(),
            // Use the name MediaStore actually assigned (it auto-uniquifies same-day DISPLAY_NAME
            // collisions such as "CamX 15072026 (1).mov"), not the requested one, so the evidence
            // filename matches the on-disk file and the per-clip evidence key stays unique.
            fileName = resolveStoredFileName(target),
            fileBytes = fileBytes,
            mediaStoreValidated = if (target.uri != null) {
                mediaStoreState != null && mediaStoreState.published && fileBytes > 1L
            } else {
                target.file?.isFile == true && fileBytes > 1L
            },
            storageKind = target.storageKind
        )
    }

    /**
     * The file name MediaStore actually stored for [target] (which may be auto-uniquified from the
     * requested DISPLAY_NAME on a same-day collision), falling back to the requested descriptor name
     * for the legacy File path or when the query fails.
     */
    private fun resolveStoredFileName(target: RecordingOutputTarget): String {
        val uri = target.uri ?: return target.descriptor.fileName
        return queryMediaStoreDisplayName(uri) ?: target.descriptor.fileName
    }

    private fun queryMediaStoreDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else {
                    null
                }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private data class MediaStorePublicationState(
        val sizeBytes: Long,
        val published: Boolean
    )

    /** A successful recording requires both durable bytes and a visible, non-pending MediaStore row. */
    private fun queryMediaStorePublicationState(uri: Uri): MediaStorePublicationState? {
        val projection = arrayOf(
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                if (sizeIndex < 0 || pendingIndex < 0) return@use null
                MediaStorePublicationState(
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    published = cursor.getInt(pendingIndex) == 0
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to validate MediaStore publication state for $uri", error)
        }.getOrNull()
    }

    private fun awaitMediaStorePublicationState(uri: Uri): MediaStorePublicationState? {
        var lastState: MediaStorePublicationState? = null
        repeat(MEDIASTORE_VALIDATION_ATTEMPTS) { attempt ->
            val currentState = queryMediaStorePublicationState(uri)
            lastState = currentState
            if (currentState?.published == true && currentState.sizeBytes > 1L) {
                return currentState
            }
            if (attempt + 1 < MEDIASTORE_VALIDATION_ATTEMPTS) {
                try {
                    Thread.sleep(MEDIASTORE_VALIDATION_RETRY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return lastState
                }
            }
        }
        return lastState
    }

    private fun closeDescriptorQuietly(target: RecordingOutputTarget) {
        try {
            target.parcelFileDescriptor?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close recording output descriptor", e)
        }
    }

    private fun writeProfessionalSidecarMetadata(
        target: RecordingOutputTarget,
        decision: RecordingOutputFinalizeDecision
    ): Uri? {
        val metadata = decision.colorMetadata
        // Name the sidecar after the file MediaStore actually stored so the sidecar's DISPLAY_NAME and
        // its self-reported "file" field match the real clip (e.g. "CamX 15072026 (1).mov") even when
        // the requested name collided with an earlier same-day recording.
        val storedFileName = resolveStoredFileName(target)
        val sidecarName = "$storedFileName.metadata.json"
        val payload = buildProfessionalSidecarPayload(
            fileName = storedFileName,
            description = decision.finalDescription,
            metadata = metadata
        )
        return if ((target.storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO ||
                target.storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_FILE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            writeMediaStoreSidecar(sidecarName, payload)
        } else {
            writeLegacySidecar(target, sidecarName, payload)
        }
    }

    private fun writeMediaStoreSidecar(sidecarName: String, payload: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sidecarName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                RecordingMediaStoreLifecyclePolicy.resolveMetadataSidecarRelativePath()
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var sidecarUri: Uri? = null
        return try {
            val insertedUri = context.contentResolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: return null
            sidecarUri = insertedUri
            context.contentResolver.openOutputStream(insertedUri, "w")?.use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw IOException("Failed to open sidecar output stream")
            val updatedRows = context.contentResolver.update(
                insertedUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                },
                null,
                null
            )
            if (updatedRows != 1) {
                throw IOException("Sidecar publish updated $updatedRows rows for $insertedUri")
            }
            insertedUri
        } catch (e: Exception) {
            sidecarUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            Log.w(TAG, "sidecar write failed", e)
            null
        }
    }

    private fun writeLegacySidecar(
        target: RecordingOutputTarget,
        sidecarName: String,
        payload: String
    ): Uri? {
        val mediaFile = target.file ?: return null
        val sidecar = File(mediaFile.parentFile, sidecarName)
        return try {
            sidecar.writeText(payload, Charsets.UTF_8)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(sidecar.absolutePath),
                arrayOf("application/json"),
                null
            )
            Uri.fromFile(sidecar)
        } catch (e: Exception) {
            sidecar.delete()
            Log.w(TAG, "legacy sidecar write failed", e)
            null
        }
    }

    private fun cleanupProfessionalSidecarAfterFinalizationFailure(sidecarUri: Uri?) {
        if (sidecarUri == null) return
        runCatching {
            if (sidecarUri.scheme.equals("file", ignoreCase = true)) {
                sidecarUri.path?.let(::File)?.delete()
            } else {
                context.contentResolver.delete(sidecarUri, null, null)
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "Failed to remove metadata sidecar after output finalization failure: $sidecarUri",
                error
            )
        }
    }

    private fun ContentValues.putColorMetadataIfSupported(metadata: RecordingMediaStoreColorMetadata) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        put(MediaStore.Video.VideoColumns.COLOR_STANDARD, metadata.colorStandard.toMediaFormatValue())
        put(MediaStore.Video.VideoColumns.COLOR_TRANSFER, metadata.colorTransfer.toMediaFormatValue())
        put(MediaStore.Video.VideoColumns.COLOR_RANGE, metadata.colorRange.toMediaFormatValue())
    }

    private fun RecordingMediaStoreColorStandard.toMediaFormatValue(): Int =
        when (this) {
            RecordingMediaStoreColorStandard.BT709 -> MediaFormat.COLOR_STANDARD_BT709
            RecordingMediaStoreColorStandard.BT2020 -> MediaFormat.COLOR_STANDARD_BT2020
        }

    private fun RecordingMediaStoreColorTransfer.toMediaFormatValue(): Int =
        when (this) {
            RecordingMediaStoreColorTransfer.SDR_VIDEO -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            RecordingMediaStoreColorTransfer.LINEAR -> MediaFormat.COLOR_TRANSFER_LINEAR
            RecordingMediaStoreColorTransfer.ST2084 -> MediaFormat.COLOR_TRANSFER_ST2084
            RecordingMediaStoreColorTransfer.HLG -> MediaFormat.COLOR_TRANSFER_HLG
        }

    private fun RecordingMediaStoreColorRange.toMediaFormatValue(): Int =
        when (this) {
            RecordingMediaStoreColorRange.LIMITED -> MediaFormat.COLOR_RANGE_LIMITED
            RecordingMediaStoreColorRange.FULL -> MediaFormat.COLOR_RANGE_FULL
        }

    private fun buildProfessionalSidecarPayload(
        fileName: String,
        description: String,
        metadata: RecordingMediaStoreColorMetadata
    ): String =
        """
        {
          "schema": "NeoProCamX.MediaSidecar.v1",
          "file": "${fileName.jsonEscape()}",
          "workingSpace": "${metadata.workingSpace}",
          "colorStandard": "${metadata.colorStandard}",
          "colorTransfer": "${metadata.colorTransfer}",
          "colorRange": "${metadata.colorRange}",
          "masteringDisplay": {
            "red": {"x": 0.7080, "y": 0.2920},
            "green": {"x": 0.1700, "y": 0.7970},
            "blue": {"x": 0.1310, "y": 0.0460},
            "white": {"x": 0.3127, "y": 0.3290},
            "peakNits": ${metadata.masteringPeakNits},
            "minimumBlackNits": ${metadata.minimumBlackNits}
          },
          "aces": {
            "gamutWorkingSpace": "ACEScg",
            "ap1": true
          },
          "description": "${description.jsonEscape()}"
        }
        """.trimIndent()

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        const val TAG = "RecordingOutputStore"
        const val QUICKTIME_MIME_TYPE = "video/quicktime"
        const val MEDIASTORE_VALIDATION_ATTEMPTS = 4
        const val MEDIASTORE_VALIDATION_RETRY_MS = 25L
    }
}
