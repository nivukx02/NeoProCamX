package com.neoproc.neoproccamx.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves the base file name (no container extension, no debug validation suffix) for a saved
 * recording clip.
 *
 * DEFAULT: `"CamX ddMMyyyy"` — e.g. `"CamX 15072026"` for 15 July 2026. This is date-only (no time),
 * so two recordings on the same day resolve to the same base name; that is safe because the app
 * targets minSdk 29+ and always writes through MediaStore, which auto-uniquifies a colliding
 * DISPLAY_NAME (`"CamX 15072026 (1).mov"`). No recording is ever overwritten.
 *
 * ONE-SHOT CUSTOM NAME: when the user turns on the "Custom Recording Name" setting and types a name,
 * that sanitized name becomes the base name for the NEXT recording only. CameraViewModel clears it
 * back to the default after a successful save (user-requested auto-reset), so the custom name applies
 * to exactly one clip.
 *
 * Pure/JVM-testable: no Android dependencies, so the naming contract is locked by unit tests.
 */
object RecordingFileNamePolicy {

    /** Leading token of the default name; a space separates it from the date. */
    const val DEFAULT_PREFIX = "CamX"

    /** Day-Month-Year (NOT `mm`, which is minutes) so "15072026" == 15 July 2026. */
    const val DATE_PATTERN = "ddMMyyyy"

    /**
     * Length cap for a user-typed custom name. Keeps the full `base + suffix + ".mov"` well under the
     * 255-byte filesystem limit even with a long debug validation suffix appended.
     */
    const val MAX_CUSTOM_NAME_LENGTH = 64

    /** The default base name for the given [dateLabel], e.g. `"CamX 15072026"`. */
    fun defaultBaseName(dateLabel: String): String = "$DEFAULT_PREFIX $dateLabel"

    /** The `ddMMyyyy` label for [date] (US locale so the digits are stable across devices). */
    fun dateLabel(date: Date): String = SimpleDateFormat(DATE_PATTERN, Locale.US).format(date)

    /** Convenience: the full default name for [date] (`"CamX 15072026"`). */
    fun defaultBaseName(date: Date): String = defaultBaseName(dateLabel(date))

    /**
     * Sanitize a user-typed name into a safe file-name base:
     *  - keep letters, digits, spaces, `.`, `_`, `-`;
     *  - collapse any run of other characters to a single `_`;
     *  - collapse runs of whitespace to a single space;
     *  - strip leading/trailing separators;
     *  - cap the length at [MAX_CUSTOM_NAME_LENGTH].
     *
     * Returns `""` when nothing usable remains (the caller then falls back to the default name).
     */
    fun sanitizeCustomName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val cleaned = trimmed
            .replace(Regex("[^A-Za-z0-9 ._-]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('_', '.', '-', ' ')
        if (cleaned.isEmpty()) return ""
        return cleaned.take(MAX_CUSTOM_NAME_LENGTH).trim('_', '.', '-', ' ')
    }

    /**
     * The base name to use for a recording: the sanitized [customName] when it yields a non-blank
     * result, otherwise the `"CamX ddMMyyyy"` default derived from [dateLabel].
     */
    fun resolveBaseName(customName: String?, dateLabel: String): String {
        val custom = sanitizeCustomName(customName)
        return if (custom.isNotEmpty()) custom else defaultBaseName(dateLabel)
    }
}
