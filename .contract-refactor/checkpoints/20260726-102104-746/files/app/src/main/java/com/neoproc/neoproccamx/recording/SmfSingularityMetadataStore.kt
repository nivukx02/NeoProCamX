package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityReleaseDecision
import java.io.File

internal data class SmfSingularityStoreResult(
    val smfFile: File,
    val releaseReportFile: File
)

internal object SmfSingularityMetadataStore {
    fun write(
        rootDirectory: File,
        recordingId: String,
        metadata: SmfSingularityMetadata,
        decision: SingularityReleaseDecision
    ): SmfSingularityStoreResult {
        val directory = File(rootDirectory, relativeRecordingDirectory(recordingId)).also { it.mkdirs() }
        val smfFile = writeAtomic(
            file = File(directory, SMF_FILE_NAME),
            text = metadata.toJsonText()
        )
        val releaseReport = writeAtomic(
            file = File(directory, RELEASE_REPORT_FILE_NAME),
            text = decision.toReleaseReportJson(recordingId)
        )
        return SmfSingularityStoreResult(
            smfFile = smfFile,
            releaseReportFile = releaseReport
        )
    }

    fun relativeSmfPath(recordingId: String): String =
        "${relativeRecordingDirectory(recordingId)}/$SMF_FILE_NAME"

    fun relativeReleaseReportPath(recordingId: String): String =
        "${relativeRecordingDirectory(recordingId)}/$RELEASE_REPORT_FILE_NAME"

    private fun writeAtomic(file: File, text: String): File {
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(text, Charsets.UTF_8)
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        return file
    }

    private fun relativeRecordingDirectory(recordingId: String): String {
        val id = recordingId
            .ifBlank { "recording" }
            .sanitizeFileToken()
        return "$DIRECTORY_NAME/$id"
    }

    private fun SingularityReleaseDecision.toReleaseReportJson(recordingId: String): String =
        buildString {
            append("{")
            append("\"formatVersion\":\"SINGULARITY_RELEASE_REPORT_1\",")
            append("\"recordingId\":\"").append(recordingId.jsonEscaped()).append("\",")
            append("\"releaseLabel\":\"").append(releaseLabel.jsonEscaped()).append("\",")
            append("\"qualityClass\":\"").append(qualityClass.jsonEscaped()).append("\",")
            append("\"transportClassification\":\"").append(transportClassification.name).append("\",")
            append("\"stageLedgerComplete\":").append(stageLedgerComplete).append(",")
            append("\"mandatoryGateSummary\":\"").append(mandatoryGateSummary.jsonEscaped()).append("\",")
            append("\"blockingCodes\":").append(blockingCodes.toJsonArray()).append(",")
            append("\"reasons\":").append(reasons.toJsonArray())
            append("}")
        }

    private fun String.sanitizeFileToken(): String {
        val sanitized = replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return sanitized.ifBlank { "recording" }
    }

    private fun List<String>.toJsonArray(): String =
        joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${it.jsonEscaped()}\"" }

    private fun String.jsonEscaped(): String =
        buildString {
            this@jsonEscaped.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    private const val DIRECTORY_NAME = "singularity_quality"
    private const val SMF_FILE_NAME = "smf-singularity.json"
    private const val RELEASE_REPORT_FILE_NAME = "singularity-release-report.json"
}
