package com.neoproc.neoproccamx.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.neoproc.neoproccamx.color.ColorScience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal sealed interface CustomLutInstallResult {
    data class Success(val file: File) : CustomLutInstallResult
    data class Failure(val userMessage: String) : CustomLutInstallResult
}

/** Stages, validates, and atomically installs one user-selected .cube LUT. */
internal object CustomLutInstaller {
    private const val TAG = "CustomLutInstaller"
    private const val TARGET_NAME = "custom_import.cube"

    suspend fun install(
        contentResolver: ContentResolver,
        uri: Uri,
        appFilesDir: File
    ): CustomLutInstallResult = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val lutDir = File(appFilesDir, "luts")
        if ((!lutDir.exists() && !lutDir.mkdirs()) || !lutDir.isDirectory) {
            return@withContext CustomLutInstallResult.Failure("LUT import failed")
        }
        val staged = runCatching {
            File.createTempFile("custom_import_", ".cube.pending", lutDir)
        }.getOrNull() ?: return@withContext CustomLutInstallResult.Failure("LUT import failed")

        val copied = runCatching {
            val input = contentResolver.openInputStream(uri)
                ?: error("Unable to open selected LUT")
            input.use { source ->
                FileOutputStream(staged).use { output ->
                    copyWithLimit(source, output)
                    output.fd.sync()
                }
            }
        }
        if (copied.isFailure) {
            staged.delete()
            Log.w(TAG, "Custom LUT staging failed", copied.exceptionOrNull())
            return@withContext CustomLutInstallResult.Failure("LUT import failed")
        }
        installValidatedStagedFile(staged, File(lutDir, TARGET_NAME))
    }

    internal fun installValidatedStagedFile(
        staged: File,
        target: File
    ): CustomLutInstallResult {
        if (!ColorScience().validateCubeLUTBlocking(staged)) {
            staged.delete()
            return CustomLutInstallResult.Failure("Invalid LUT; previous custom LUT preserved")
        }
        return runCatching {
            require(staged.canonicalFile.parentFile == target.canonicalFile.parentFile) {
                "Atomic LUT install requires one directory"
            }
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            CustomLutInstallResult.Success(target)
        }.getOrElse { error ->
            staged.delete()
            Log.e(TAG, "Atomic custom LUT install failed", error)
            CustomLutInstallResult.Failure("LUT install failed; previous custom LUT preserved")
        }
    }

    private fun copyWithLimit(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            totalBytes += read.toLong()
            require(totalBytes <= ColorScience.MAX_CUSTOM_LUT_FILE_BYTES) {
                "Custom LUT exceeds ${ColorScience.MAX_CUSTOM_LUT_FILE_BYTES} bytes"
            }
            output.write(buffer, 0, read)
        }
        require(totalBytes > 0L) { "Custom LUT is empty" }
    }
}
