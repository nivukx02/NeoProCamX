package com.neoproc.neoproccamx.recording

import android.content.ContentValues
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

internal class RecordingMediaStoreOutputStore(
    private val context: Context
) {
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
            RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO -> createMediaStoreTarget(
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
                decision.publishMediaStore && target.uri != null -> publishMediaStoreTarget(
                    uri = target.uri,
                    description = decision.finalDescription,
                    colorMetadata = decision.colorMetadata,
                    sidecarUri = if (decision.writeSidecarMetadata) {
                        writeProfessionalSidecarMetadata(target, decision)
                    } else {
                        null
                    }
                )
                decision.scanLegacyFile && target.file != null -> {
                    if (decision.writeSidecarMetadata) {
                        writeProfessionalSidecarMetadata(target, decision)
                    }
                    scanLegacyTarget(target)
                }
            }
            val evidence = resolvedOutputEvidence(target)
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
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                }.isSuccess
            } ?: false
            if (recovered) {
                runCatching {
                    NeoLogger.critical(
                        "RecordingOutputStore",
                        "finalize RECOVERED: cleared IS_PENDING after publish failure uri=${target.uri}"
                    )
                }
                return resolvedOutputEvidence(target)
            }
            // Unrecoverable: remove the orphaned pending entry so it does not linger invisibly, and
            // return null so the caller reports a genuine failure instead of a phantom "saved".
            runCatching {
                NeoLogger.critical(
                    "RecordingOutputStore",
                    "finalize FAILED and unrecoverable; removing orphaned pending entry uri=${target.uri} " +
                        "error=${e.javaClass.simpleName}:${e.message}"
                )
            }
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
            put(MediaStore.Video.Media.DISPLAY_NAME, plan.displayName)
            put(MediaStore.Video.Media.MIME_TYPE, plan.mimeType)
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Video.Media.RELATIVE_PATH, plan.relativePath)
            put(MediaStore.Video.Media.DESCRIPTION, plan.description)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            putColorMetadataIfSupported(plan.colorMetadata)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Failed to open MediaStore file descriptor")
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
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DESCRIPTION, finalDescription)
            putColorMetadataIfSupported(colorMetadata)
        }
        context.contentResolver.update(uri, publishValues, null, null)
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
        val fileBytes = when {
            target.file?.isFile == true -> target.file.length()
            target.uri != null -> queryMediaStoreSize(target.uri)
            else -> 0L
        }.coerceAtLeast(0L)
        return RecordingFinalizedOutputEvidence(
            outputPath = target.uri?.toString() ?: target.file?.absolutePath ?: target.writablePath(),
            // Use the name MediaStore actually assigned (it auto-uniquifies same-day DISPLAY_NAME
            // collisions such as "CamX 15072026 (1).mov"), not the requested one, so the evidence
            // filename matches the on-disk file and the per-clip evidence key stays unique.
            fileName = resolveStoredFileName(target),
            fileBytes = fileBytes,
            mediaStoreValidated = fileBytes > 1L,
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

    private fun queryMediaStoreSize(uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeIndex >= 0) return cursor.getLong(sizeIndex)
            }
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L
        }.getOrDefault(0L)
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
        return if (target.storageKind == RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO &&
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
            context.contentResolver.update(
                insertedUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                },
                null,
                null
            )
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
    }
}
