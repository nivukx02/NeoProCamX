package com.neoproc.neoproccamx.recording

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Public-storage contract shared with CamX Studio's USB File Transfer/MTP scanner.
 *
 * These are acquisition/develop masters, not finished gallery videos. They therefore live in
 * Documents/CamX/Conversion and use an application MIME type in MediaStore.Files. The file extension
 * and exact sibling names remain unchanged so the desktop can import the source without transcoding.
 */
internal object CinemaCamMtpPublicationPolicy {
    const val RELATIVE_PATH = "Documents/CamX/Conversion"
    const val MASTER_MIME_TYPE = "application/x-camx-conversion-master"
    const val COMPANION_MIME_TYPE = "application/octet-stream"

    data class Artifact(
        val displayName: String,
        val bytes: Long,
        val mimeType: String
    )

    fun npv10Artifacts(
        masterName: String,
        masterBytes: Long,
        audioName: String?,
        audioBytes: Long,
        audioRequired: Boolean
    ): List<Artifact> {
        require(masterName.startsWith("NPV10_", ignoreCase = true) &&
            masterName.endsWith(".npv10", ignoreCase = true)
        ) { "Only a Cinema Cam NPV10 master can be published" }
        require(masterBytes > 0L) { "NPV10 master is empty" }
        if (audioRequired) require(!audioName.isNullOrBlank() && audioBytes > 0L) {
            "NPV10 audio is required but its durable sidecar is missing"
        }
        return buildList {
            add(Artifact(masterName, masterBytes, MASTER_MIME_TYPE))
            if (!audioName.isNullOrBlank() && audioBytes > 0L) {
                require(audioName.equals("$masterName.audio.npv10a", ignoreCase = true)) {
                    "NPV10 audio sidecar does not belong to the selected master"
                }
                add(Artifact(audioName, audioBytes, COMPANION_MIME_TYPE))
            }
        }
    }

    fun developArtifacts(
        masterName: String,
        masterBytes: Long,
        sidecarName: String?,
        sidecarBytes: Long
    ): List<Artifact> {
        require(
            (masterName.startsWith("DEVSDR_", ignoreCase = true) ||
                masterName.startsWith("DEVNDR_", ignoreCase = true)) &&
                masterName.endsWith(".mov", ignoreCase = true)
        ) { "Only a Cinema Cam SDR/NDR develop master can be published" }
        require(masterBytes > 0L) { "Cinema Cam develop master is empty" }
        require(!sidecarName.isNullOrBlank() && sidecarBytes > 0L) {
            "Cinema Cam develop-color sidecar is required"
        }
        require(sidecarName.equals("$masterName${VideoDevelopColorSidecar.FILE_SUFFIX}", ignoreCase = true)) {
            "Develop-color sidecar does not belong to the selected master"
        }
        return listOf(
            Artifact(masterName, masterBytes, MASTER_MIME_TYPE),
            Artifact(sidecarName, sidecarBytes, COMPANION_MIME_TYPE)
        )
    }
}

internal data class CinemaCamMtpPublishedBundle(
    val primaryUri: Uri,
    val artifactUris: List<Uri>,
    val reusedExistingPublication: Boolean
)

/**
 * Publishes byte-identical Cinema Cam masters to the one MTP-visible conversion library.
 *
 * Every row stays IS_PENDING until every source has been copied and independently SHA-256 verified.
 * A failure removes the complete pending set and always preserves the private source bundle.
 */
internal class CinemaCamMtpPublisher(
    context: Context,
    private val backgroundIo: RecordingPublicationIoGate = RecordingPublicationIoGate.process
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun publishVerifiedNpv10(master: File): CinemaCamMtpPublishedBundle {
        Npv10VerifiedMasterPublication.requireVerifiedForConversion(master)
        val audioRequired = Npv10FileReader(master).use { reader ->
            reader.header.metadataValues["audiostoredinnpv10bundle"].toBooleanLenient()
        }
        val audio = Npv10AudioSidecarPolicy.sidecarFor(master).takeIf { it.isFile && it.length() > 0L }
        if (audio != null) requireValidNpv10Audio(master)
        val plan = CinemaCamMtpPublicationPolicy.npv10Artifacts(
            masterName = master.name,
            masterBytes = master.length(),
            audioName = audio?.name,
            audioBytes = audio?.length() ?: 0L,
            audioRequired = audioRequired
        )
        return publishBundle(
            sources = listOfNotNull(master, audio),
            plan = plan,
            label = "Cinema Cam NPV10"
        )
    }

    fun publishDevelopMaster(master: File): CinemaCamMtpPublishedBundle {
        require(master.isFile && master.length() > 0L) {
            "Cinema Cam develop master is missing or empty"
        }
        val sidecar = VideoDevelopColorSidecar.sidecarFileFor(master)
        require(VideoDevelopColorSidecar.read(master) != null) {
            "Cinema Cam develop-color sidecar is missing or corrupt"
        }
        val plan = CinemaCamMtpPublicationPolicy.developArtifacts(
            masterName = master.name,
            masterBytes = master.length(),
            sidecarName = sidecar.name,
            sidecarBytes = sidecar.length()
        )
        return publishBundle(
            sources = listOf(master, sidecar),
            plan = plan,
            label = "Cinema Cam develop"
        )
    }

    fun removePublishedBundle(primaryName: String): Int {
        val names = if (primaryName.endsWith(".npv10", ignoreCase = true)) {
            setOf(primaryName, "$primaryName.audio.npv10a")
        } else {
            setOf(primaryName, "$primaryName${VideoDevelopColorSidecar.FILE_SUFFIX}")
        }
        return queryOwnedRows(names)
            .map(OwnedRow::uri)
            .distinct()
            .sumOf { uri -> runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) }
    }

    fun reconcileExistingMasters(): Int {
        var published = 0
        val verifiedMasters = backgroundIo.backgroundChunk {
            Npv10MasterStore(appContext.filesDir).listVerifiedMastersForPublication()
        }
        verifiedMasters.forEach { clip ->
            runCatching { publishVerifiedNpv10(clip.file) }
                .onSuccess { published += 1 }
                .onFailure { failure ->
                    Log.w(TAG, "Unable to publish existing NPV10 master ${clip.file.name}", failure)
                }
        }

        val roots = listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir)
            .distinctBy { root -> runCatching { root.canonicalPath }.getOrDefault(root.absolutePath) }
        roots.forEach { root ->
            RecordDevelopMasterKind.values().forEach { kind ->
                RecordDevelopMasterStore(root, kind).listMasters().forEach { clip ->
                    runCatching { publishDevelopMaster(clip.file) }
                        .onSuccess { published += 1 }
                        .onFailure { failure ->
                            Log.w(TAG, "Unable to publish existing develop master ${clip.file.name}", failure)
                        }
                }
            }
        }
        return published
    }

    private fun publishBundle(
        sources: List<File>,
        plan: List<CinemaCamMtpPublicationPolicy.Artifact>,
        label: String
    ): CinemaCamMtpPublishedBundle {
        require(sources.size == plan.size) { "$label publication plan/source mismatch" }
        val expectedByName = plan.associateBy { it.displayName.lowercase(Locale.US) }
        val existing = queryOwnedRows(plan.mapTo(linkedSetOf()) { it.displayName })
        val completeExisting = plan.mapNotNull { artifact ->
            existing.singleOrNull { row ->
                !row.pending &&
                    row.name.equals(artifact.displayName, ignoreCase = true) &&
                    row.bytes == artifact.bytes
            }
        }
        if (completeExisting.size == plan.size) {
            return CinemaCamMtpPublishedBundle(
                primaryUri = completeExisting.first().uri,
                artifactUris = completeExisting.map(OwnedRow::uri),
                reusedExistingPublication = true
            )
        }

        // These rows are app-owned replicas in the exact conversion folder. A stale, incomplete or
        // size-mismatched set is safe to replace; the private source remains untouched throughout.
        existing.forEach { row ->
            runCatching { resolver.delete(row.uri, null, null) }
                .getOrElse { failure -> throw IllegalStateException("Unable to replace stale ${row.name}", failure) }
        }

        val cancellation = ConversionCancellationSignal()
        return TransactionalMediaStoreArtifactSet(resolver, backgroundIo = backgroundIo).use { transaction ->
            val staged = sources.zip(plan).mapIndexed { index, (source, artifact) ->
                require(source.name.equals(artifact.displayName, ignoreCase = true)) {
                    "$label source order does not match its publication plan"
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, artifact.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, CinemaCamMtpPublicationPolicy.RELATIVE_PATH)
                    put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1_000L)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, source.lastModified() / 1_000L)
                }
                transaction.stageFile(
                    collection = collection,
                    values = values,
                    source = source,
                    label = "$label artifact ${index + 1}/${plan.size}",
                    cancellation = cancellation
                ).also { artifactResult ->
                    val storedName = queryDisplayName(artifactResult.uri)
                    check(storedName.equals(artifact.displayName, ignoreCase = true)) {
                        "$label MediaStore changed ${artifact.displayName} to ${storedName.orEmpty()}"
                    }
                }
            }
            val committed = transaction.commit(cancellation)
            check(committed.size == plan.size) { "$label did not commit its complete artifact set" }
            check(staged.all { stagedArtifact ->
                expectedByName.containsKey(queryDisplayName(stagedArtifact.uri).orEmpty().lowercase(Locale.US))
            }) { "$label publication names changed during commit" }
            CinemaCamMtpPublishedBundle(
                primaryUri = staged.first().uri,
                artifactUris = committed,
                reusedExistingPublication = false
            )
        }
    }

    private fun queryOwnedRows(names: Set<String>): List<OwnedRow> {
        if (names.isEmpty()) return emptyList()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val placeholders = names.joinToString(separator = ",") { "?" }
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} IN ($placeholders)"
        val args = names.toTypedArray()
        return buildList {
            // Explicitly include our own interrupted pending rows. An ordinary collection query
            // can exclude them, causing repeated copies, renamed replicas and orphaned storage.
            OwnedPendingMediaStoreQuery.query(
                resolver, collection, projection, appContext.packageName, selection, args
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathColumn).orEmpty().trim('/')
                    if (!path.equals(CinemaCamMtpPublicationPolicy.RELATIVE_PATH, ignoreCase = true)) continue
                    add(
                        OwnedRow(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                            name = cursor.getString(nameColumn).orEmpty(),
                            bytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                            pending = cursor.getInt(pendingColumn) != 0
                        )
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
        }

    private fun requireValidNpv10Audio(master: File) {
        Npv10AudioSidecarReader(master).use { reader ->
            while (backgroundIo.backgroundChunk { reader.readNextPacket() } != null) Unit
            val audit = reader.requireAudit()
            require(audit.packetCount > 0L && audit.monotonicPts && audit.crcValidated) {
                "NPV10 audio sidecar failed its integrity gate"
            }
        }
    }

    private fun String?.toBooleanLenient(): Boolean = when (this?.trim()?.lowercase(Locale.US)) {
        "true", "1", "yes", "on" -> true
        else -> false
    }

    private data class OwnedRow(
        val uri: Uri,
        val name: String,
        val bytes: Long,
        val pending: Boolean
    )

    private companion object {
        const val TAG = "CinemaCamMtpPublisher"
    }
}

/** Process-wide serial lane prevents two reconciliation/finalization callers from duplicating rows. */
internal object CinemaCamMtpPublicationScheduler {
    private val queuedPaths = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamX-MTP-Publish").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    fun reconcile(context: Context) {
        enqueue(context, "__reconcile__") { publisher ->
            val count = publisher.reconcileExistingMasters()
            Log.i(TAG, "Cinema Cam MTP reconciliation completed masters=$count")
        }
    }

    fun publishNpv10(context: Context, master: File) {
        enqueue(context, master.canonicalOrAbsolutePath()) { publisher ->
            val result = publisher.publishVerifiedNpv10(master)
            Log.i(TAG, "NPV10 MTP publication ready uri=${result.primaryUri}")
        }
    }

    fun publishDevelop(context: Context, master: File) {
        enqueue(context, master.canonicalOrAbsolutePath()) { publisher ->
            val result = publisher.publishDevelopMaster(master)
            Log.i(TAG, "Develop-master MTP publication ready uri=${result.primaryUri}")
        }
    }

    fun remove(context: Context, primaryName: String) {
        enqueue(context, "delete:${primaryName.lowercase(Locale.US)}") { publisher ->
            val deleted = publisher.removePublishedBundle(primaryName)
            Log.i(TAG, "Removed $deleted MTP publication row(s) for $primaryName")
        }
    }

    private fun enqueue(
        context: Context,
        key: String,
        operation: (CinemaCamMtpPublisher) -> Unit
    ) {
        if (!queuedPaths.add(key)) return
        val appContext = context.applicationContext
        executor.execute {
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                operation(CinemaCamMtpPublisher(appContext))
            } catch (failure: Throwable) {
                Log.w(TAG, "Cinema Cam MTP publication failed key=$key", failure)
            } finally {
                queuedPaths.remove(key)
            }
        }
    }

    private fun File.canonicalOrAbsolutePath(): String =
        runCatching { canonicalPath }.getOrDefault(absolutePath)

    private const val TAG = "CinemaCamMtpPublish"
}
