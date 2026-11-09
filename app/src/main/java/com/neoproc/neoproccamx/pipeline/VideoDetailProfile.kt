package com.neoproc.neoproccamx.pipeline

enum class VideoDetailProfile(val displayName: String) {
    FOCUSED_DETAIL("Focused Detail"),
    NATURAL_DETAIL("Natural Detail"),
    EXTREME_DETAIL("Extreme Detail");

    companion object {
        fun fromPreference(value: String?): VideoDetailProfile =
            entries.firstOrNull { profile ->
                profile.name.equals(value, ignoreCase = true) ||
                    profile.displayName.equals(value, ignoreCase = true)
            } ?: FOCUSED_DETAIL
    }
}
