package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.FileOutputStream

internal object RecordingQualityCertificateStore {
    fun write(rootDirectory: File, certificate: RecordingQualityEvidenceCertificate): File {
        val directory = File(rootDirectory, CERTIFICATE_DIRECTORY_NAME).also { it.mkdirs() }
        val id = certificate.request.recordingId
            .ifBlank { certificate.request.recordedFileName }
            .ifBlank { "recording-${certificate.request.generatedAtEpochMs.coerceAtLeast(0L)}" }
            .sanitizeFileToken()
        val file = File(directory, "$id.quality-certificate.txt")
        return writeAtomic(file, certificate.toCertificateText())
    }

    private fun writeAtomic(file: File, text: String): File {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (!tempFile.renameTo(file)) {
            tempFile.inputStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(tempFile.delete()) {
                "Unable to remove temporary recording quality certificate: ${tempFile.absolutePath}"
            }
        }
        return file
    }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private const val CERTIFICATE_DIRECTORY_NAME = "recording_quality_certificates"
}

internal object SingularityQualityGateArtifactStore {
    fun write(
        rootDirectory: File,
        recordingId: String,
        reportJson: String
    ): File {
        val directory = File(rootDirectory, relativeRecordingDirectory(recordingId)).also { it.mkdirs() }
        val file = File(directory, REPORT_FILE_NAME)
        val tempFile = File(directory, "$REPORT_FILE_NAME.tmp")
        tempFile.writeText(reportJson, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        return file
    }

    fun relativeReportPath(recordingId: String): String =
        "${relativeRecordingDirectory(recordingId)}/$REPORT_FILE_NAME"

    private fun relativeRecordingDirectory(recordingId: String): String {
        val id = recordingId
            .ifBlank { "recording" }
            .sanitizeFileToken()
        return "$DIRECTORY_NAME/$id"
    }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private const val DIRECTORY_NAME = "singularity_quality"
    private const val REPORT_FILE_NAME = "qg-results.json"
}

internal object SingularityStage5EngineArtifactStore {
    fun write(
        rootDirectory: File,
        recordingId: String,
        reportJson: String
    ): File {
        val directory = File(rootDirectory, relativeRecordingDirectory(recordingId)).also { it.mkdirs() }
        val file = File(directory, REPORT_FILE_NAME)
        val tempFile = File(directory, "$REPORT_FILE_NAME.tmp")
        tempFile.writeText(reportJson, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        return file
    }

    fun relativeReportPath(recordingId: String): String =
        "${relativeRecordingDirectory(recordingId)}/$REPORT_FILE_NAME"

    private fun relativeRecordingDirectory(recordingId: String): String {
        val id = recordingId
            .ifBlank { "recording" }
            .sanitizeFileToken()
        return "$DIRECTORY_NAME/$id"
    }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private const val DIRECTORY_NAME = "singularity_quality"
    private const val REPORT_FILE_NAME = "stage5-engine-report.json"
}

internal object SingularityStage6DeviceMatrixArtifactStore {
    fun write(
        rootDirectory: File,
        recordingId: String,
        reportJson: String
    ): File {
        val directory = File(rootDirectory, relativeRecordingDirectory(recordingId)).also { it.mkdirs() }
        val file = File(directory, REPORT_FILE_NAME)
        val tempFile = File(directory, "$REPORT_FILE_NAME.tmp")
        tempFile.writeText(reportJson, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        return file
    }

    fun relativeReportPath(recordingId: String): String =
        "${relativeRecordingDirectory(recordingId)}/$REPORT_FILE_NAME"

    private fun relativeRecordingDirectory(recordingId: String): String {
        val id = recordingId
            .ifBlank { "recording" }
            .sanitizeFileToken()
        return "$DIRECTORY_NAME/$id"
    }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private const val DIRECTORY_NAME = "singularity_quality"
    private const val REPORT_FILE_NAME = "stage6-device-matrix-report.json"
}

internal object NeoHdrDynamicMetadataSidecarStore {
    fun write(
        rootDirectory: File,
        recordingId: String,
        sidecar: NeoHdrDynamicMetadataSidecar
    ): File {
        val directory = File(rootDirectory, SIDECAR_DIRECTORY_NAME).also { it.mkdirs() }
        val id = recordingId
            .ifBlank { "recording" }
            .sanitizeFileToken()
        val file = File(directory, "$id.neo-hdr-sidecar.txt")
        file.writeText(sidecar.text, Charsets.UTF_8)
        return file
    }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private const val SIDECAR_DIRECTORY_NAME = "neo_hdr_dynamic_metadata"
}
