package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import java.io.File

/**
 * A recorded 12-bit RAW clip: its session directory plus the two artifacts the converter needs —
 * the NPRAW pixel stream and the develop-metadata sidecar.
 */
data class Raw12Clip(
    val sessionDir: File,
    val streamFile: File,
    val metaFile: File,
    val createdAtEpochMs: Long,
    val streamBytes: Long
) {
    val name: String get() = sessionDir.name
}

/**
 * Raw12MasterStore — enumerates recorded 12-bit RAW clips for the offline converter's clip list.
 *
 * A clip is a session directory (RawVideoManager writes `CamX/NEO_RADIANCE_RAW_<ts>/`) containing BOTH
 * `stream.npraw` (the NPRAW pixel stream) AND `develop.meta` (the sidecar). A directory missing either
 * is skipped, so a half-written / interrupted capture never appears as convertible. Pure java.io.File,
 * so it is unit-testable with a temp dir. NOTE: on Android Q+ scoped storage the recorded artifacts
 * live under MediaStore; discovery there is a device-runtime concern layered on top of this file scan
 * (the app supplies the resolved directory root).
 */
class Raw12MasterStore(private val rawRoot: File) {

    companion object {
        const val STREAM_NAME = "stream.npraw"
        val META_NAME: String get() = RawDevelopMetadataSidecar.FILE_NAME
    }

    fun listClips(): List<Raw12Clip> =
        rawRoot.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { resolveClip(it) }
            .sortedByDescending { it.createdAtEpochMs }
            .toList()

    /** Resolve a single clip from a session directory, or null if it lacks the stream or sidecar. */
    fun resolveClip(sessionDir: File): Raw12Clip? {
        val stream = File(sessionDir, STREAM_NAME)
        val meta = File(sessionDir, META_NAME)
        if (!stream.isFile || !meta.isFile) return null
        return Raw12Clip(
            sessionDir = sessionDir,
            streamFile = stream,
            metaFile = meta,
            createdAtEpochMs = stream.lastModified(),
            streamBytes = stream.length()
        )
    }
}
