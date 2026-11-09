package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class RecordingCodecSelectionCandidate(
    val name: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    val supportsRequestedFormat: Boolean = true,
    val createByName: Boolean = true
)

internal object RecordingCodecSelectionPolicy {
    fun rank(
        candidates: List<RecordingCodecSelectionCandidate>
    ): List<RecordingCodecSelectionCandidate> =
        candidates
            .map { candidate ->
                candidate.copy(createByName = candidate.name.isNotBlank())
            }
            .sortedWith(
                compareByDescending<RecordingCodecSelectionCandidate> { score(it) }
                    .thenBy { it.name.lowercase(Locale.US) }
            )

    fun describe(candidate: RecordingCodecSelectionCandidate): String =
        "hw=${candidate.hardwareAccelerated} software=${candidate.softwareOnly} " +
            "vendor=${candidate.vendor} format=${candidate.supportsRequestedFormat} " +
            "codec2=${candidate.isCodec2()}"

    private fun score(candidate: RecordingCodecSelectionCandidate): Int {
        var value = 0
        value += if (candidate.supportsRequestedFormat) 10_000 else -10_000
        value += when {
            candidate.hardwareAccelerated && !candidate.softwareOnly -> 4_000
            !candidate.softwareOnly -> 1_000
            else -> -4_000
        }
        if (candidate.vendor) value += 1_500
        value += codecFamilyScore(candidate.name)
        return value
    }

    private fun codecFamilyScore(name: String): Int {
        val normalized = name.lowercase(Locale.US)
        return when {
            normalized.startsWith("omx.google.") -> -1_500
            normalized.startsWith("c2.android.") -> -1_500
            normalized.startsWith("c2.qti.") || normalized.startsWith("c2.qcom.") -> 750
            normalized.startsWith("c2.") -> 500
            normalized.startsWith("omx.qti.") || normalized.startsWith("omx.qcom.") -> 100
            normalized.startsWith("omx.") -> 0
            else -> 0
        }
    }

    private fun RecordingCodecSelectionCandidate.isCodec2(): Boolean =
        name.startsWith("c2.", ignoreCase = true)
}
