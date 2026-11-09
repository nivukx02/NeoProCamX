package com.neoproc.neoproccamx.recording

import java.io.File

/**
 * Persistent store for record-then-develop LIGHT intermediates of ONE lane ([RecordDevelopMasterKind]),
 * modelled on [Npv10MasterStore]. Owns a private directory under the app filesDir with a `.nomedia`
 * marker; [createMasterFile] names the target `.mov` for a new recording (the record path writes bytes
 * to it — existence-in-dir IS registration), [listMasters] scans newest-first for the develop card, and
 * [delete] removes a developed/unwanted intermediate.
 *
 * Construct one per lane: `RecordDevelopMasterStore(filesDir, RecordDevelopMasterKind.SDR)` for the
 * SDR-Convert card and `...NDR` for the NDR-Convert card, so their lists never cross-contaminate.
 */
class RecordDevelopMasterStore(
    privateRoot: File,
    val kind: RecordDevelopMasterKind
) {
    private val masterDirectory: File = RecordDevelopContainerPolicy.masterDirectory(privateRoot, kind)

    /** Names (does NOT create) the target `.mov` for a new light recording in this lane's private dir. */
    fun createMasterFile(config: RecordDevelopSessionConfig): File {
        ensureMasterDirectory()
        return File(masterDirectory, RecordDevelopContainerPolicy.buildMasterFileName(kind, config))
    }

    /**
     * Cheap, side-effect-free existence check: does this lane hold at least one intermediate? Unlike
     * [listMasters] it never creates the private directory / `.nomedia` marker, so it is safe to call
     * speculatively (e.g. to decide whether to surface the develop card) without materialising empty dirs.
     */
    fun hasMasters(): Boolean {
        if (!masterDirectory.isDirectory) return false
        return masterDirectory.listFiles().orEmpty().any(::isValidMasterFile)
    }

    /** All intermediates in this lane, newest first. */
    fun listMasters(): List<RecordDevelopMasterClip> {
        ensureMasterDirectory()
        return masterDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter(::isValidMasterFile)
            .map { file ->
                RecordDevelopMasterClip(
                    file = file,
                    kind = kind,
                    createdAtEpochMs = file.lastModified(),
                    bytes = file.length()
                )
            }
            .sortedByDescending { clip -> clip.createdAtEpochMs }
            .toList()
    }

    /** Deletes one intermediate (and its sibling colour sidecar, if present). Returns true on success. */
    fun delete(clip: RecordDevelopMasterClip): Boolean {
        val sidecar = VideoDevelopColorSidecar.sidecarFileFor(clip.file)
        if (sidecar.exists()) runCatching { sidecar.delete() }
        return runCatching { clip.file.delete() }.getOrDefault(false)
    }

    private fun ensureMasterDirectory() {
        if (!masterDirectory.exists() && !masterDirectory.mkdirs()) {
            throw IllegalStateException(
                "Unable to create private record-develop master directory: ${masterDirectory.absolutePath}"
            )
        }
        require(masterDirectory.isDirectory) {
            "Private record-develop master path is not a directory: ${masterDirectory.absolutePath}"
        }
        val noMedia = File(masterDirectory, ".nomedia")
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
                .getOrElse { throwable ->
                    throw IllegalStateException("Unable to create record-develop .nomedia marker", throwable)
                }
        }
        require(noMedia.isFile) {
            "record-develop .nomedia marker is not a file: ${noMedia.absolutePath}"
        }
    }

    private fun isValidMasterFile(file: File): Boolean =
        file.isFile &&
            file.extension.equals(RecordDevelopContainerPolicy.EXTENSION, ignoreCase = true) &&
            file.length() > 0L
}
