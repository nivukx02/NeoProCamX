package com.neoproc.neoproccamx.bridge

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarPolicy
import com.neoproc.neoproccamx.recording.Npv10MasterStore
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Read-only, ADB-authenticated bridge for CamX Studio.
 *
 * It exposes only two authoritative sources: app-owned, fully published MediaStore rows and
 * private NPV10 masters that passed [Npv10MasterStore]'s verified-publication gate. No arbitrary
 * path is accepted from the desktop; every opaque artifact id is resolved from a fresh catalogue.
 */
class CamxDesktopBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        MATCH_CATALOG, MATCH_DIGEST -> "application/json"
        MATCH_ARTIFACT -> resolveSnapshot().artifacts[uri.lastPathSegment]?.descriptor?.mimeType
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        requireReadRoute(uri)
        val snapshot = resolveSnapshot()
        return MatrixCursor(arrayOf("schema", "protocol_version", "item_count", "catalog_fingerprint"), 1).apply {
            addRow(arrayOf<Any?>(
                CamxDesktopBridgeContract.SCHEMA,
                CamxDesktopBridgeContract.PROTOCOL_VERSION,
                snapshot.items.size,
                CamxDesktopBridgeContract.catalogFingerprint(snapshot.items)
            ))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("CamX desktop bridge is read-only")
        return when (MATCHER.match(uri)) {
            MATCH_CATALOG -> pipeUtf8(buildCatalogPayload(resolveSnapshot()))
            MATCH_ARTIFACT -> {
                val artifact = resolveArtifact(uri)
                openArtifact(artifact.source)
            }
            MATCH_DIGEST -> {
                val artifact = resolveArtifact(uri)
                pipeUtf8(buildDigestPayload(artifact))
            }
            else -> throw FileNotFoundException("Unknown CamX bridge route")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("CamX desktop bridge is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("CamX desktop bridge is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("CamX desktop bridge is read-only")

    private fun requireReadRoute(uri: Uri) {
        if (MATCHER.match(uri) !in setOf(MATCH_CATALOG, MATCH_ARTIFACT, MATCH_DIGEST)) {
            throw IllegalArgumentException("Unknown CamX bridge route")
        }
    }

    private fun resolveArtifact(uri: Uri): ResolvedArtifact {
        val id = uri.lastPathSegment.orEmpty()
        if (!ARTIFACT_ID.matches(id)) throw FileNotFoundException("Invalid CamX artifact id")
        return resolveSnapshot().artifacts[id]
            ?: throw FileNotFoundException("CamX artifact is unavailable or no longer published")
    }

    private fun resolveSnapshot(): BridgeSnapshot {
        val appContext = requireNotNull(context).applicationContext
        val fileRows = queryOwnedRows(
            appContext,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        )
        val videoRows = queryOwnedRows(
            appContext,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        )
        val primaryRows = (videoRows + fileRows.filter { row ->
            CamxDesktopBridgeContract.normalizedExtension(row.name) in FILE_COLLECTION_MEDIA_EXTENSIONS
        }).distinctBy { it.uri.toString() }

        val artifacts = linkedMapOf<String, ResolvedArtifact>()
        val media = mutableListOf<CamxBridgeMediaDescriptor>()
        primaryRows.forEach { row ->
            if (!CamxDesktopBridgeContract.isSupportedMediaName(row.name) || row.bytes <= 0L) return@forEach
            val extension = CamxDesktopBridgeContract.normalizedExtension(row.name)
            val rawMeta = if (extension == ".npraw") {
                fileRows.firstOrNull { candidate ->
                    candidate.name.equals("develop.meta", ignoreCase = true) &&
                        candidate.relativePath.equals(row.relativePath, ignoreCase = true) &&
                        candidate.bytes > 0L
                } ?: return@forEach
            } else {
                null
            }
            val primary = resolvedMediaStoreArtifact(row, role = "media")
            val companions = buildList {
                rawMeta?.let { add(resolvedMediaStoreArtifact(it, role = "raw-metadata")) }
                if (extension == ".npv10") {
                    findCompanion(fileRows, row, "${row.name}.audio.npv10a")
                        ?.let { add(resolvedMediaStoreArtifact(it, role = "audio")) }
                }
                if (extension in STANDARD_VIDEO_EXTENSIONS) {
                    val base = row.name.substringBeforeLast('.', row.name)
                    findCompanion(fileRows, row, "${row.name}.devcolor")
                        ?.let { add(resolvedMediaStoreArtifact(it, role = "develop-color")) }
                    findCompanion(fileRows, row, "${row.name}.metadata.json")
                        ?.let { add(resolvedMediaStoreArtifact(it, role = "camera-metadata")) }
                    findCompanion(fileRows, row, "${base}_NeoLog_to_Rec709.cube")
                        ?.let { add(resolvedMediaStoreArtifact(it, role = "preview-lut")) }
                    findCompanion(fileRows, row, "${base}.elite-conversion-certificate.txt")
                        ?.let { add(resolvedMediaStoreArtifact(it, role = "conversion-certificate")) }
                }
            }
            (listOf(primary) + companions).forEach { artifacts[it.descriptor.id] = it }
            media += CamxBridgeMediaDescriptor(
                artifact = primary.descriptor,
                category = CamxDesktopBridgeContract.categoryFor(row.name),
                origin = "camx-owned-mediastore",
                companions = companions.map(ResolvedArtifact::descriptor)
            )
        }

        val publicNamesAndSizes = media.mapTo(hashSetOf()) { it.artifact.name.lowercase(Locale.US) to it.artifact.bytes }
        val privateMasters = runCatching { Npv10MasterStore(appContext.filesDir).listMasters() }
            .onFailure { error -> Log.w(TAG, "Verified private NPV10 discovery failed", error) }
            .getOrDefault(emptyList())
        privateMasters.forEach { clip ->
            if ((clip.file.name.lowercase(Locale.US) to clip.bytes) in publicNamesAndSizes) return@forEach
            val primary = resolvedPrivateArtifact(clip.file, role = "media")
            val companions = buildList {
                Npv10AudioSidecarPolicy.sidecarFor(clip.file)
                    .takeIf { it.isFile && it.length() > 0L }
                    ?.let { add(resolvedPrivateArtifact(it, role = "audio")) }
            }
            (listOf(primary) + companions).forEach { artifacts[it.descriptor.id] = it }
            media += CamxBridgeMediaDescriptor(
                artifact = primary.descriptor,
                category = "raw",
                origin = "camx-private-verified-npv10",
                companions = companions.map(ResolvedArtifact::descriptor)
            )
        }

        // A URI can appear through both MediaStore.Video and MediaStore.Files. Keep the descriptor
        // backed by the authoritative unique URI and never emit duplicate desktop rows.
        val uniqueMedia = media.distinctBy { it.artifact.id }
        val usedIds = uniqueMedia.flatMap { item -> listOf(item.artifact.id) + item.companions.map { it.id } }.toSet()
        return BridgeSnapshot(
            items = uniqueMedia,
            artifacts = artifacts.filterKeys(usedIds::contains)
        )
    }

    private fun queryOwnedRows(context: Context, collection: Uri): List<OwnedMediaRow> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        return runCatching {
            buildList {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    arrayOf(context.packageName),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        add(
                            OwnedMediaRow(
                                uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                                name = name,
                                mimeType = cursor.getString(mimeColumn) ?: mimeTypeFor(name),
                                bytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                                modifiedAtEpochMs = if (cursor.isNull(dateColumn)) 0L else cursor.getLong(dateColumn) * 1000L,
                                relativePath = cursor.getString(pathColumn).orEmpty()
                            )
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "CamX-owned MediaStore query failed for $collection", error)
        }.getOrDefault(emptyList())
    }

    private fun findCompanion(rows: List<OwnedMediaRow>, media: OwnedMediaRow, expectedName: String): OwnedMediaRow? {
        val sameFolder = rows.firstOrNull { candidate ->
            candidate.name.equals(expectedName, ignoreCase = true) &&
                candidate.relativePath.equals(media.relativePath, ignoreCase = true) &&
                candidate.bytes > 0L
        }
        if (sameFolder != null) return sameFolder
        return rows.firstOrNull { candidate ->
            candidate.name.equals(expectedName, ignoreCase = true) &&
                candidate.relativePath.trim('/').equals("Documents/CamX/Metadata", ignoreCase = true) &&
                candidate.bytes > 0L
        }
    }

    private fun resolvedMediaStoreArtifact(row: OwnedMediaRow, role: String): ResolvedArtifact {
        val id = CamxDesktopBridgeContract.stableArtifactId("mediastore", row.uri.toString())
        return ResolvedArtifact(
            descriptor = CamxBridgeArtifactDescriptor(
                id = id,
                name = row.name,
                extension = CamxDesktopBridgeContract.normalizedExtension(row.name),
                bytes = row.bytes,
                modifiedAtEpochMs = row.modifiedAtEpochMs,
                mimeType = row.mimeType,
                role = role
            ),
            source = ArtifactSource.ContentUri(row.uri)
        )
    }

    private fun resolvedPrivateArtifact(file: File, role: String): ResolvedArtifact {
        val canonical = file.canonicalFile
        val root = requireNotNull(context).filesDir.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Private bridge artifact escaped app storage" }
        val id = CamxDesktopBridgeContract.stableArtifactId("private", canonical.absolutePath)
        return ResolvedArtifact(
            descriptor = CamxBridgeArtifactDescriptor(
                id = id,
                name = canonical.name,
                extension = CamxDesktopBridgeContract.normalizedExtension(canonical.name),
                bytes = canonical.length(),
                modifiedAtEpochMs = canonical.lastModified(),
                mimeType = mimeTypeFor(canonical.name),
                role = role
            ),
            source = ArtifactSource.PrivateFile(canonical)
        )
    }

    private fun buildCatalogPayload(snapshot: BridgeSnapshot): String {
        val appContext = requireNotNull(context).applicationContext
        val packageInfo = packageInfo(appContext)
        val installationId = bridgeInstallationId(appContext)
        return CamxDesktopBridgeContract.buildCatalogJson(
            packageName = appContext.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
            signingCertificateSha256 = signingCertificateSha256(appContext),
            deviceIdSha256 = CamxDesktopBridgeContract.sha256Hex(
                "${appContext.packageName}\u0000$installationId".toByteArray(Charsets.UTF_8)
            ),
            generatedAtEpochMs = System.currentTimeMillis(),
            items = snapshot.items
        )
    }

    private fun buildDigestPayload(artifact: ResolvedArtifact): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        openArtifactInput(artifact.source).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        if (bytes != artifact.descriptor.bytes) {
            throw FileNotFoundException("CamX artifact changed while its digest was calculated")
        }
        val hash = digest.digest().joinToString(separator = "") { value ->
            "%02x".format(Locale.US, value.toInt() and 0xff)
        }
        return CamxDesktopBridgeContract.buildDigestJson(artifact.descriptor.id, bytes, hash)
    }

    private fun openArtifact(source: ArtifactSource): ParcelFileDescriptor = when (source) {
        is ArtifactSource.PrivateFile -> ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
        is ArtifactSource.ContentUri -> requireNotNull(context).contentResolver.openFileDescriptor(source.uri, "r")
            ?: throw FileNotFoundException("CamX MediaStore artifact is unavailable")
    }

    private fun openArtifactInput(source: ArtifactSource): InputStream = when (source) {
        is ArtifactSource.PrivateFile -> source.file.inputStream()
        is ArtifactSource.ContentUri -> requireNotNull(context).contentResolver.openInputStream(source.uri)
            ?: throw FileNotFoundException("CamX MediaStore artifact is unavailable")
    }

    private fun pipeUtf8(payload: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }.onFailure { error -> Log.w(TAG, "CamX bridge pipe failed", error) }
        }, "CamX-Desktop-Bridge").apply { isDaemon = true }.start()
        return pipe[0]
    }

    private fun packageInfo(context: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun signingCertificateSha256(context: Context): String {
        val signature = packageInfo(context).signingInfo?.apkContentsSigners?.firstOrNull()
            ?: return ""
        return CamxDesktopBridgeContract.sha256Hex(signature.toByteArray())
    }

    private fun bridgeInstallationId(context: Context): String = synchronized(INSTALLATION_ID_LOCK) {
        val root = context.noBackupFilesDir.apply { mkdirs() }
        val identityFile = File(root, INSTALLATION_ID_FILE)
        readInstallationId(identityFile)?.let { return@synchronized it }

        val generated = UUID.randomUUID().toString().lowercase(Locale.US)
        val temporary = File.createTempFile("$INSTALLATION_ID_FILE-", ".tmp", root)
        try {
            temporary.outputStream().use { output ->
                output.write(generated.toByteArray(Charsets.US_ASCII))
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(identityFile)) {
                readInstallationId(identityFile)
                    ?: throw IllegalStateException("Unable to publish the CamX bridge installation identity")
            } else {
                generated
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readInstallationId(file: File): String? = runCatching {
        file.takeIf { it.isFile && it.length() in 1..128L }
            ?.readText(Charsets.US_ASCII)
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf(INSTALLATION_ID::matches)
    }.getOrNull()

    private data class OwnedMediaRow(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val bytes: Long,
        val modifiedAtEpochMs: Long,
        val relativePath: String
    )

    private sealed interface ArtifactSource {
        data class PrivateFile(val file: File) : ArtifactSource
        data class ContentUri(val uri: Uri) : ArtifactSource
    }

    private data class ResolvedArtifact(
        val descriptor: CamxBridgeArtifactDescriptor,
        val source: ArtifactSource
    )

    private data class BridgeSnapshot(
        val items: List<CamxBridgeMediaDescriptor>,
        val artifacts: Map<String, ResolvedArtifact>
    )

    private companion object {
        const val TAG = "CamxDesktopBridge"
        const val INSTALLATION_ID_FILE = "camx-desktop-bridge-installation-id-v1"
        const val MATCH_CATALOG = 1
        const val MATCH_ARTIFACT = 2
        const val MATCH_DIGEST = 3
        const val DIGEST_BUFFER_BYTES = 1024 * 1024

        val ARTIFACT_ID = Regex("^[a-f0-9]{64}$")
        val INSTALLATION_ID = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")
        val INSTALLATION_ID_LOCK = Any()
        val STANDARD_VIDEO_EXTENSIONS = setOf(".mov", ".mp4", ".mkv", ".mxf", ".avi", ".webm", ".m4v", ".hevc")
        val FILE_COLLECTION_MEDIA_EXTENSIONS = setOf(".npraw", ".npv10", ".dng", ".raw", ".nivukx")
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(CamxDesktopBridgeContract.AUTHORITY, "catalog", MATCH_CATALOG)
            addURI(CamxDesktopBridgeContract.AUTHORITY, "artifact/*", MATCH_ARTIFACT)
            addURI(CamxDesktopBridgeContract.AUTHORITY, "digest/*", MATCH_DIGEST)
        }

        fun mimeTypeFor(name: String): String = when (CamxDesktopBridgeContract.normalizedExtension(name)) {
            ".mov" -> "video/quicktime"
            ".mp4", ".m4v" -> "video/mp4"
            ".mkv" -> "video/x-matroska"
            ".webm" -> "video/webm"
            ".hevc" -> "video/hevc"
            ".dng" -> "image/x-adobe-dng"
            ".npv10" -> "application/vnd.neoproc.npv10"
            ".npraw" -> "application/vnd.neoproc.npraw"
            ".nivukx" -> "video/vnd.neoproc.nivukx"
            else -> "application/octet-stream"
        }
    }
}
