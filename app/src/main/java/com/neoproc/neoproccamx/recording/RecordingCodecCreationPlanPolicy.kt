package com.neoproc.neoproccamx.recording

internal object RecordingCodecCreationPlanPolicy {
    fun orderedCodecNames(
        selectedCodecName: String?,
        selectedCreateByName: Boolean,
        rankedCandidateNames: List<String>
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        if (selectedCreateByName) {
            selectedCodecName
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(ordered::add)
        }
        rankedCandidateNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(ordered::add)
        return ordered.toList()
    }
}
