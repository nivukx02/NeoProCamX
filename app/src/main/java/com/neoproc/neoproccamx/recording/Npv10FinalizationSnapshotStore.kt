package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * A crash-surviving stop snapshot, NOT a recording certificate or a publication decision.
 *
 * Full-resolution master verification can outlive the Activity. Previously all structured
 * failure/timing evidence was written only after that audit, so an OS process kill erased the
 * reason the recording stopped. Persist the already-joined writer's measurements first, without
 * reading/copying any pixels and without replacing the mandatory complete CRC/SHA audit.
 */
internal object Npv10FinalizationSnapshotStore {
    private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_.-]*")
    private val reservedKeys = setOf(
        "schema", "auditStatus", "publicationAllowed", "masterName", "masterBytes",
        "masterModifiedEpochMs", "generatedAtEpochMs"
    )

    fun writePending(
        privateRoot: File,
        master: File,
        generatedAtEpochMs: Long,
        measurements: Map<String, String>
    ): File {
        val directory = Npv10ContainerPolicy.masterDirectory(privateRoot).canonicalFile
        val canonicalMaster = master.canonicalFile
        require(canonicalMaster.parentFile == directory && canonicalMaster.isFile) {
            "Finalization snapshot requires an existing app-private NPV10 master"
        }
        require(canonicalMaster.name.endsWith(".npv10") && generatedAtEpochMs >= 0L)
        require(measurements.keys.all { keyPattern.matches(it) && it !in reservedKeys }) {
            "Stop measurements must not override audit/publication identity"
        }
        val text = buildString {
            append("schema=CAMX_NPV10_PENDING_FINALIZATION_V1\n")
            append("auditStatus=NOT_PROVEN\npublicationAllowed=false\n")
            append("masterName=${escape(canonicalMaster.name)}\n")
            append("masterBytes=${canonicalMaster.length()}\n")
            append("masterModifiedEpochMs=${canonicalMaster.lastModified()}\n")
            append("generatedAtEpochMs=$generatedAtEpochMs\n")
            measurements.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(escape(value)).append('\n')
            }
        }
        // Unique snapshots retain failed and repeated finalization attempts. Neither the temporary
        // nor final suffix can be discovered as a master, device-evidence file, or quality certificate.
        // The original QA/custom recording name can already approach NAME_MAX. Keep the sidecar
        // basename bounded; its contents bind the exact master without extending that filename.
        val sidecar = File(directory, "npv10-stop-${UUID.randomUUID()}.finalizing.txt")
        val temporary = File.createTempFile(".npv10-stop-", ".pending", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory atomic publication: a process kill exposes either no snapshot or its
            // complete fsynced contents, never a partial file that resembles successful evidence.
            Files.move(temporary.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return sidecar
        } finally {
            // This method owns only its freshly created temporary file, never a master or old proof.
            if (temporary.exists()) check(temporary.delete()) { "Unable to retire stop snapshot temporary" }
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0")
}
