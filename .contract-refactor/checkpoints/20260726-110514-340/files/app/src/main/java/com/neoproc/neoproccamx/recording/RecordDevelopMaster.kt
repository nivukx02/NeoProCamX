package com.neoproc.neoproccamx.recording

import java.io.File

/**
 * Which offline develop lane a light intermediate belongs to. Selects the private master directory,
 * the file-name prefix, and (downstream) which Settings converter card lists the clip.
 *
 *  - [SDR]: flat Rec.709/Rec.2020 SDR intermediate → SDR-Convert develop card.
 *  - [NDR]: HLG-coherent HDR intermediate → NDR-Convert develop card (PQ tone-map applied offline).
 */
enum class RecordDevelopMasterKind(internal val dirName: String, internal val filePrefix: String) {
    SDR(dirName = "CamXDevelopSDR", filePrefix = "DEVSDR"),
    NDR(dirName = "CamXDevelopNDR", filePrefix = "DEVNDR")
}

/**
 * The minimal per-clip facts the master file-name builder needs. The develop-critical COLOUR facts
 * (WB gains, working matrix, adapting luminance, transfer, CCT/tint) live in the sibling
 * [VideoDevelopColorSidecar]; this config only shapes the file name and directory.
 */
data class RecordDevelopSessionConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Capture mode label, e.g. "video" / "pro" / "ndr" (sanitised into the name). */
    val mode: String,
    /** Colour-profile label, e.g. "rec709" / "hlg" (sanitised into the name). */
    val colorProfile: String,
    val startedAtEpochMs: Long,
    val validationLabel: String = ""
)

/**
 * A discovered light-record intermediate on private storage, as surfaced to the offline develop cards.
 * Mirrors [Npv10MasterClip] so the converter/list UI can treat both uniformly.
 */
data class RecordDevelopMasterClip(
    val file: File,
    val kind: RecordDevelopMasterKind,
    val createdAtEpochMs: Long,
    val bytes: Long
)
