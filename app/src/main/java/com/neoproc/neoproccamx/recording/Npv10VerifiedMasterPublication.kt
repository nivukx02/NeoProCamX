package com.neoproc.neoproccamx.recording

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * A strict master becomes user-listable/convertible only after this marker is durably published.
 *
 * This is deliberately an internal master-integrity gate, not an external quality-certification
 * badge. A standalone camera process can prove its bytes, cadence, lineage, and durable sidecars;
 * it cannot truthfully manufacture later ADB/lab/Tier-Omega evidence. The quality-claim status is
 * therefore bound into the marker and remains visible, but PASS is not required for preserving or
 * converting an otherwise exact private master.
 */
internal data class Npv10TerminalCadenceBoundary(
    val timestampNs: Long,
    val excludedMissingFrames: Long
)

internal object Npv10VerifiedMasterPublication {
    private const val VERSION = 4
    private const val LEGACY_VERSION = 3
    private const val MARKER_SUFFIX = ".verified"

    fun markerFor(master: File): File {
        val directory = requireNotNull(master.absoluteFile.parentFile) {
            "NPV10 master has no parent directory"
        }
        return File(directory, master.name + MARKER_SUFFIX)
    }

    fun publish(
        master: File,
        evidence: Npv10DeviceEvidence,
        captureLedger: Npv10CaptureLedgerSnapshot,
        qualityCertificate: File?,
        qualityEvidenceStatus: RecordingQualityEvidenceStatus,
        syncDirectory: (File) -> Boolean = ::syncDirectoryStrict,
        deleteMarker: (File) -> Boolean = { file -> file.delete() }
    ): File {
        require(master.isFile && master.length() > 0L) { "NPV10 master is unavailable for publication" }
        require(qualityCertificate != null || qualityEvidenceStatus != RecordingQualityEvidenceStatus.PASS) {
            "NPV10 quality evidence cannot be PASS without its durable report"
        }
        val qualityReportBinding = qualityCertificate?.let { report ->
            require(report.isFile && report.length() > 0L) {
                "NPV10 quality evidence report path is present but unreadable"
            }
            require(certificateDeclaresStatus(report, qualityEvidenceStatus)) {
                "NPV10 quality evidence report status does not match the recorded status"
            }
            require(certificateBelongsToMaster(report, master)) {
                "NPV10 quality evidence report belongs to a different master"
            }
            val reportDirectory = requireNotNull(report.absoluteFile.parentFile) {
                "NPV10 quality evidence report has no parent directory"
            }
            require(syncDirectory(reportDirectory)) {
                "NPV10 quality evidence report parent-directory fsync failed"
            }
            QualityEvidenceReportBinding(
                path = report.canonicalPath,
                bytes = report.length(),
                lastModifiedMs = report.lastModified(),
                sha256 = report.sha256Hex()
            )
        }
        require(File(evidence.masterPath).canonicalFile == master.canonicalFile) {
            "NPV10 evidence belongs to a different master"
        }
        require(evidence.auditStatus == Npv10AuditStatus.PASS) { "NPV10 structural audit did not pass" }
        require(evidence.eliteVideoQualityAuditStatus == EliteQualityAuditStatus.PASS) {
            "NPV10 elite quality audit did not pass"
        }
        val lineageFailures = captureLedger.failureReasons(evidence.expectedPayloadBytesPerFrame)
        require(lineageFailures.isEmpty()) {
            "NPV10 independent capture lineage failed: ${lineageFailures.joinToString()}"
        }
        require(captureLedger.committedFrames == evidence.frameCount) {
            "NPV10 capture lineage frame count does not match audited master"
        }
        require(captureLedger.committedPayloadBytes == evidence.payloadBytes) {
            "NPV10 capture lineage payload does not match audited master"
        }
        require(captureLedger.firstCommittedTimestampNs == evidence.firstFrameTimestampNs) {
            "NPV10 capture lineage first timestamp does not match audited master"
        }
        require(
            captureLedger.terminalCadenceBoundaryTimestampNs > 0L &&
                captureLedger.terminalCadenceBoundaryTimestampNs == captureLedger.lastCommittedTimestampNs &&
                captureLedger.terminalCadenceExcludedMissingFrames >= 0L
        ) {
            "NPV10 exact terminal cadence boundary was not durably proven"
        }

        val marker = markerFor(master)
        val markerDirectory = requireNotNull(marker.parentFile) {
            "NPV10 verified marker has no parent directory"
        }
        val temp = File.createTempFile(marker.name + ".", ".tmp", markerDirectory)
        try {
            val payload = buildString {
                appendLine("version=$VERSION")
                appendLine("masterName=${master.name}")
                appendLine("masterBytes=${master.length()}")
                appendLine("masterLastModifiedMs=${master.lastModified()}")
                appendLine("frameCount=${evidence.frameCount}")
                appendLine("payloadBytes=${evidence.payloadBytes}")
                appendLine("firstTimestampNs=${evidence.firstFrameTimestampNs}")
                appendLine("lastTimestampNs=${captureLedger.lastCommittedTimestampNs}")
                appendLine("sessionHash=${evidence.postRecordSessionHash}")
                appendLine("captureIngressFrames=${captureLedger.ingressFrames}")
                appendLine("captureAcceptedFrames=${captureLedger.acceptedFrames}")
                appendLine("captureCommittedFrames=${captureLedger.committedFrames}")
                appendLine("captureCommittedPayloadBytes=${captureLedger.committedPayloadBytes}")
                appendLine("captureTimestampHash=${captureLedger.committedTimestampHash}")
                appendLine("captureInvalidTimestamps=${captureLedger.invalidTimestamps}")
                appendLine("captureNonMonotonicIngress=${captureLedger.nonMonotonicIngress}")
                appendLine("captureMissingCadenceFrames=${captureLedger.missingCadenceFrames}")
                appendLine(
                    "terminalCadenceBoundaryTimestampNs=" +
                        captureLedger.terminalCadenceBoundaryTimestampNs
                )
                appendLine(
                    "terminalCadenceExcludedMissingFrames=" +
                        captureLedger.terminalCadenceExcludedMissingFrames
                )
                appendLine("masterCertificateStatus=PASS")
                appendLine("qualityEvidenceStatus=${qualityEvidenceStatus.name}")
                appendLine(
                    "qualityEvidenceReportState=" +
                        if (qualityReportBinding == null) "UNAVAILABLE" else "PERSISTED"
                )
                appendLine("qualityEvidenceReportPath=${qualityReportBinding?.path.orEmpty()}")
                appendLine("qualityEvidenceReportBytes=${qualityReportBinding?.bytes ?: 0L}")
                appendLine(
                    "qualityEvidenceReportLastModifiedMs=${qualityReportBinding?.lastModifiedMs ?: 0L}"
                )
                appendLine("qualityEvidenceReportSha256=${qualityReportBinding?.sha256.orEmpty()}")
                appendLine("auditStatus=${evidence.auditStatus.name}")
                appendLine("eliteVideoQualityAuditStatus=${evidence.eliteVideoQualityAuditStatus.name}")
                appendLine("droppedFrames=${evidence.droppedFrames}")
                appendLine("crcCheckPass=${evidence.crcCheckPass}")
                appendLine("byteCountCheckPass=${evidence.byteCountCheckPass}")
                appendLine("sessionHashCheckPass=${evidence.sessionHashCheckPass}")
                appendLine("timestampCheckPass=${evidence.timestampCheckPass}")
            }.toByteArray(Charsets.UTF_8)
            FileOutputStream(temp).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            java.nio.file.Files.move(
                temp.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            if (!syncDirectory(markerDirectory)) {
                val cleanup = invalidateFailedPublication(
                    master = master,
                    marker = marker,
                    syncDirectory = syncDirectory,
                    deleteMarker = deleteMarker
                )
                throw IllegalStateException(
                    "NPV10 verified marker parent-directory fsync failed; cleanup=$cleanup"
                )
            }
            if (!isVerified(master)) {
                val cleanup = invalidateFailedPublication(
                    master = master,
                    marker = marker,
                    syncDirectory = syncDirectory,
                    deleteMarker = deleteMarker
                )
                throw IllegalStateException("NPV10 verified marker read-back failed; cleanup=$cleanup")
            }
            return marker
        } finally {
            runCatching { temp.delete() }
        }
    }

    fun isVerified(master: File): Boolean {
        if (!master.isFile || master.length() <= 0L) return false
        val marker = markerFor(master)
        if (!marker.isFile || marker.length() <= 0L) return false
        val fields = readMarkerFields(marker) ?: return false
        val frameCount = fields["frameCount"]?.toLongOrNull() ?: return false
        val payloadBytes = fields["payloadBytes"]?.toLongOrNull() ?: return false
        val ingressFrames = fields["captureIngressFrames"]?.toLongOrNull() ?: return false
        val acceptedFrames = fields["captureAcceptedFrames"]?.toLongOrNull() ?: return false
        val committedFrames = fields["captureCommittedFrames"]?.toLongOrNull() ?: return false
        val committedPayloadBytes =
            fields["captureCommittedPayloadBytes"]?.toLongOrNull() ?: return false
        val markerVersion = fields["version"]?.toIntOrNull() ?: return false
        val lastTimestampNs = fields["lastTimestampNs"]?.toLongOrNull() ?: return false
        val terminalBoundaryFieldsValid = when (markerVersion) {
            VERSION -> {
                val terminalTimestampNs =
                    fields["terminalCadenceBoundaryTimestampNs"]?.toLongOrNull()
                val excludedMissingFrames =
                    fields["terminalCadenceExcludedMissingFrames"]?.toLongOrNull()
                terminalTimestampNs != null &&
                    terminalTimestampNs > 0L &&
                    terminalTimestampNs == lastTimestampNs &&
                    excludedMissingFrames != null &&
                    excludedMissingFrames >= 0L
            }
            LEGACY_VERSION -> true
            else -> false
        }
        val sessionHash = fields["sessionHash"] ?: return false
        val qualityEvidenceStatus = fields["qualityEvidenceStatus"]
            ?.let { value ->
                runCatching { RecordingQualityEvidenceStatus.valueOf(value) }.getOrNull()
            }
            ?: return false
        val qualityEvidenceReportState = fields["qualityEvidenceReportState"] ?: return false
        val reportBindingFieldsValid = when (qualityEvidenceReportState) {
            "UNAVAILABLE" ->
                qualityEvidenceStatus != RecordingQualityEvidenceStatus.PASS &&
                    fields["qualityEvidenceReportPath"].isNullOrEmpty() &&
                    fields["qualityEvidenceReportBytes"]?.toLongOrNull() == 0L &&
                    fields["qualityEvidenceReportLastModifiedMs"]?.toLongOrNull() == 0L &&
                    fields["qualityEvidenceReportSha256"].isNullOrEmpty()
            "PERSISTED" ->
                fields["qualityEvidenceReportPath"]?.isNotBlank() == true &&
                    fields["qualityEvidenceReportBytes"]?.toLongOrNull()?.let { it > 0L } == true &&
                    fields["qualityEvidenceReportLastModifiedMs"]?.toLongOrNull()?.let { it > 0L } == true &&
                    fields["qualityEvidenceReportSha256"]?.let { hash ->
                        hash.length == SHA_256_HEX_LENGTH &&
                            hash.all { character -> character in '0'..'9' || character in 'a'..'f' }
                    } == true
            else -> false
        }
        return markerVersion in LEGACY_VERSION..VERSION &&
            fields["masterName"] == master.name &&
            fields["masterBytes"]?.toLongOrNull() == master.length() &&
            fields["masterLastModifiedMs"]?.toLongOrNull() == master.lastModified() &&
            frameCount > 0L &&
            payloadBytes > 0L &&
            fields["firstTimestampNs"]?.toLongOrNull()?.let { it > 0L } == true &&
            lastTimestampNs > 0L &&
            terminalBoundaryFieldsValid &&
            ingressFrames == frameCount &&
            acceptedFrames == frameCount &&
            committedFrames == frameCount &&
            committedPayloadBytes == payloadBytes &&
            fields["captureTimestampHash"]?.toLongOrNull() != null &&
            fields["captureInvalidTimestamps"]?.toLongOrNull() == 0L &&
            fields["captureNonMonotonicIngress"]?.toLongOrNull() == 0L &&
            fields["captureMissingCadenceFrames"]?.toLongOrNull() == 0L &&
            sessionHash.length == SHA_256_HEX_LENGTH &&
            sessionHash.all { character -> character in '0'..'9' || character in 'a'..'f' } &&
            fields["masterCertificateStatus"] == "PASS" &&
            qualityEvidenceStatus.name == fields["qualityEvidenceStatus"] &&
            reportBindingFieldsValid &&
            fields["auditStatus"] == Npv10AuditStatus.PASS.name &&
            fields["eliteVideoQualityAuditStatus"] == EliteQualityAuditStatus.PASS.name &&
            fields["droppedFrames"]?.toIntOrNull() == 0 &&
            fields["crcCheckPass"] == "true" &&
            fields["byteCountCheckPass"] == "true" &&
            fields["sessionHashCheckPass"] == "true" &&
            fields["timestampCheckPass"] == "true"
    }

    /**
     * Returns the exact one-shot Camera2 stop-marker boundary for newly published v4 masters.
     * Legacy v3 masters remain convertible, but do not expose enough durable information for a
     * short-clip content-cadence measurement to remove the terminal control interval safely.
     */
    fun terminalCadenceBoundary(master: File): Npv10TerminalCadenceBoundary? {
        if (!isVerified(master)) return null
        val fields = readMarkerFields(markerFor(master)) ?: return null
        if (fields["version"]?.toIntOrNull() != VERSION) return null
        val timestampNs = fields["terminalCadenceBoundaryTimestampNs"]?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return null
        val excludedMissingFrames =
            fields["terminalCadenceExcludedMissingFrames"]?.toLongOrNull()
                ?.takeIf { it >= 0L }
                ?: return null
        if (fields["lastTimestampNs"]?.toLongOrNull() != timestampNs) return null
        return Npv10TerminalCadenceBoundary(timestampNs, excludedMissingFrames)
    }

    /** External/lab report health is observable but never substitutes for master integrity. */
    fun isQualityEvidenceReportIntact(master: File): Boolean {
        if (!isVerified(master)) return false
        val fields = readMarkerFields(markerFor(master)) ?: return false
        if (fields["qualityEvidenceReportState"] != "PERSISTED") return false
        val report = fields["qualityEvidenceReportPath"]
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return false
        val expectedStatus = fields["qualityEvidenceStatus"]
            ?.let { value -> runCatching { RecordingQualityEvidenceStatus.valueOf(value) }.getOrNull() }
            ?: return false
        return report.isFile &&
            report.length() > 0L &&
            fields["qualityEvidenceReportBytes"]?.toLongOrNull() == report.length() &&
            fields["qualityEvidenceReportLastModifiedMs"]?.toLongOrNull() == report.lastModified() &&
            fields["qualityEvidenceReportSha256"] == runCatching { report.sha256Hex() }.getOrNull() &&
            certificateDeclaresStatus(report, expectedStatus) &&
            certificateBelongsToMaster(report, master)
    }

    fun requireVerifiedForConversion(master: File) {
        require(isVerified(master)) {
            "Refusing to convert an unpublished or failed NPV10 strict master"
        }
    }

    fun invalidate(master: File) {
        runCatching { markerFor(master).delete() }
    }

    private fun certificateDeclaresStatus(
        certificate: File,
        expectedStatus: RecordingQualityEvidenceStatus
    ): Boolean = runCatching {
        certificate.useLines { lines ->
            lines.any { line -> line == "qualityEvidenceStatus=${expectedStatus.name}" }
        }
    }.getOrDefault(false)

    private fun certificateBelongsToMaster(certificate: File, master: File): Boolean = runCatching {
        val requiredFields = mapOf(
            "recordingId" to master.nameWithoutExtension,
            "recordedFileName" to master.name
        )
        val observed = certificate.useLines { lines ->
            lines.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.filter { (key, _) -> key in requiredFields }.toMap()
        }
        requiredFields.all { (key, value) -> observed[key] == value }
    }.getOrDefault(false)

    private fun readMarkerFields(marker: File): Map<String, String>? = runCatching {
        marker.useLines { lines ->
            lines.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        }
    }.getOrNull()

    private fun invalidateFailedPublication(
        master: File,
        marker: File,
        syncDirectory: (File) -> Boolean,
        deleteMarker: (File) -> Boolean
    ): String {
        val directory = marker.parentFile
        var invalidated = !marker.exists()
        if (!invalidated) {
            runCatching { deleteMarker(marker) }
            invalidated = !marker.exists()
        }
        if (!invalidated && marker.exists()) {
            val quarantine = File(
                directory,
                "${marker.name}.failed-${System.nanoTime()}"
            )
            invalidated = runCatching {
                java.nio.file.Files.move(
                    marker.toPath(),
                    quarantine.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }.getOrDefault(false)
        }
        if (!invalidated && marker.exists()) {
            invalidated = runCatching {
                FileOutputStream(marker, false).use { output ->
                    output.write("masterCertificateStatus=INVALID\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
                !isVerified(master)
            }.getOrDefault(false)
        }
        val directorySynced = directory?.let { runCatching { syncDirectory(it) }.getOrDefault(false) }
            ?: false
        val markerStillVerified = marker.exists() && isVerified(master)
        return "invalidated=$invalidated,directorySynced=$directorySynced," +
            "markerStillVerified=$markerStillVerified"
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            HEX_DIGITS[(byte.toInt() and 0xFF) ushr 4].toString() +
                HEX_DIGITS[byte.toInt() and 0x0F]
        }
    }

    private fun syncDirectoryStrict(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val vmName = System.getProperty("java.vm.name").orEmpty()
        return if (vmName.contains("Dalvik", ignoreCase = true) ||
            vmName.contains("ART", ignoreCase = true)
        ) {
            var descriptor: java.io.FileDescriptor? = null
            try {
                descriptor = Os.open(
                    directory.absolutePath,
                    // Android's public OsConstants API does not expose O_DIRECTORY. Opening the
                    // already-validated directory read-only still yields a directory descriptor
                    // that fsync(2) can use to make the atomic marker rename durable.
                    OsConstants.O_RDONLY,
                    0
                )
                Os.fsync(descriptor)
                true
            } catch (_: Throwable) {
                false
            } finally {
                descriptor?.let { runCatching { Os.close(it) } }
            }
        } else {
            runCatching {
                FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
            }.isSuccess
        }
    }

    private const val SHA_256_HEX_LENGTH = 64
    private const val HASH_BUFFER_BYTES = 16 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    private data class QualityEvidenceReportBinding(
        val path: String,
        val bytes: Long,
        val lastModifiedMs: Long,
        val sha256: String
    )
}
