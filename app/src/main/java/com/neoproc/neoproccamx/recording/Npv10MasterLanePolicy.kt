package com.neoproc.neoproccamx.recording

import java.io.File
import java.util.Locale

/** Routes custom packed-10 masters into the user-facing SDR or NDR develop lane. */
internal object Npv10MasterLanePolicy {
    fun resolve(file: File): RecordDevelopMasterKind = runCatching {
        Npv10FileReader(file).use { reader -> resolve(reader.header.metadataValues, file.name) }
    }.getOrElse { resolve(emptyMap(), file.name) }

    fun resolve(metadata: Map<String, String>, fileName: String = ""): RecordDevelopMasterKind {
        val values = metadata.entries.associate { (key, value) ->
            key.lowercase(Locale.US) to value.trim().lowercase(Locale.US)
        }
        val hdrMode = values["hdrmode"].orEmpty()
        val hdr = values["hdrenabled"] == "true" ||
            values["signalfamily"] == "hdr" ||
            values["transfer"] in setOf("hlg", "pq", "st.2084", "smpte2084") ||
            hdrMode.isNotBlank() && hdrMode != "off" ||
            listOf(values["dynamicrange"], values["transport"], values["colorprofile"], fileName)
                .filterNotNull()
                .any { label ->
                    val normalized = label.lowercase(Locale.US)
                    normalized.contains("ndr") || normalized.contains("hdr") ||
                        normalized.contains("hlg") || normalized.contains("pq")
                }
        return if (hdr) RecordDevelopMasterKind.NDR else RecordDevelopMasterKind.SDR
    }
}
