package com.neoproc.neoproccamx.recording

import java.io.File
import java.util.Locale

/**
 * Private-storage layout + file-naming for the record-then-develop LIGHT intermediates, modelled on
 * [Npv10ContainerPolicy]. The intermediate is a REAL HEVC Main10 `.mov` (no new container), so the
 * extension is `mov` and each develop lane ([RecordDevelopMasterKind]) gets its own private directory,
 * keyed off the app filesDir with a `.nomedia` marker so the intermediates never surface in the gallery.
 *
 * File name: `<PREFIX>_<startedAtEpochMs>[_<label>]_<mode>_<profile>_<WxH>_<fps>fps.mov`
 * e.g. `DEVSDR_1783800000000_video_rec709_4096x3072_30fps.mov`.
 */
object RecordDevelopContainerPolicy {

    const val EXTENSION = "mov"

    fun masterDirectory(privateRoot: File, kind: RecordDevelopMasterKind): File =
        File(privateRoot, kind.dirName)

    fun buildMasterFileName(kind: RecordDevelopMasterKind, config: RecordDevelopSessionConfig): String {
        val mode = sanitize(config.mode)
        val profile = sanitize(config.colorProfile).takeIf { it.isNotEmpty() } ?: "profile"
        val label = sanitizeValidationLabel(config.validationLabel).takeIf { it.isNotEmpty() }
        val labelSegment = label?.let { "_$it" } ?: ""
        return "${kind.filePrefix}_${config.startedAtEpochMs}${labelSegment}_${mode}_${profile}_" +
            "${config.width}x${config.height}_${config.fps}fps.$EXTENSION"
    }

    private fun sanitize(value: String): String =
        value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "video" }

    private fun sanitizeValidationLabel(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9_.-]+"), "_")
            .trim('_', '.', '-')
            .take(120)
}
