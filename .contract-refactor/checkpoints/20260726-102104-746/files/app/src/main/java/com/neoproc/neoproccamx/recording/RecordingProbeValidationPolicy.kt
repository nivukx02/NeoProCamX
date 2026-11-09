package com.neoproc.neoproccamx.recording

import java.util.Locale
import kotlin.math.roundToInt

internal data class RecordingProbeMetadata(
    val codec: String,
    val pixelFormat: String,
    val encodedBitDepth: Int,
    val primaries: String,
    val transfer: String,
    val matrix: String,
    val colorRange: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val rotationDegrees: Int = 0,
    val mirrored: Boolean = false
)

internal object RecordingProbeValidationPolicy {
    fun fromKeyValueText(text: String): RecordingProbeMetadata =
        fromFields(
            text.lineSequence()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    val separator = trimmed.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        trimmed.substring(0, separator).trim() to trimmed.substring(separator + 1).trim()
                    }
                }
                .toMap()
        )

    fun fromFields(fields: Map<String, String>): RecordingProbeMetadata {
        val normalized = fields.entries.associate { (key, value) ->
            key.lowercase(Locale.US).trim() to value.trim()
        }
        val pixelFormat = normalized.value("pix_fmt").lowercase(Locale.US)
        val colorSpace = normalized.value("color_space").lowercase(Locale.US)
        val colorTransfer = normalized.value("color_transfer").lowercase(Locale.US)
        val colorPrimaries = normalized.value("color_primaries").lowercase(Locale.US)
        return RecordingProbeMetadata(
            codec = normalized.value("codec_name").lowercase(Locale.US),
            pixelFormat = pixelFormat,
            encodedBitDepth = resolveBitDepth(normalized, pixelFormat),
            primaries = normalizePrimaries(colorPrimaries.ifBlank { colorSpace }),
            transfer = normalizeTransfer(colorTransfer),
            matrix = normalizeMatrix(colorSpace),
            colorRange = normalizeRange(normalized.value("color_range")),
            width = normalized.value("width").toIntOrNull() ?: 0,
            height = normalized.value("height").toIntOrNull() ?: 0,
            fps = parseFps(normalized.value("avg_frame_rate").ifBlank { normalized.value("r_frame_rate") }),
            bitrateBps = normalized.value("bit_rate").toLongOrNull()
                ?.coerceIn(0L, Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0,
            rotationDegrees = normalizeRotationDegrees(resolveRotationValue(normalized)),
            mirrored = resolveMirrored(normalized)
        )
    }

    private fun Map<String, String>.value(key: String): String = this[key].orEmpty()

    private fun resolveBitDepth(fields: Map<String, String>, pixelFormat: String): Int {
        val explicit = fields.value("bits_per_raw_sample").toIntOrNull()
            ?: fields.value("bits_per_sample").toIntOrNull()
        if (explicit != null && explicit > 0) return explicit
        return when {
            pixelFormat.contains("12") -> 12
            pixelFormat.contains("10") || pixelFormat.contains("p010") -> 10
            else -> 8
        }
    }

    private fun normalizePrimaries(value: String): String =
        when {
            value.contains("2020") -> "BT.2020"
            value.contains("p3") -> "Display P3"
            else -> "BT.709"
        }

    private fun normalizeTransfer(value: String): String =
        when {
            value.contains("2084") || value.contains("pq") || value.contains("smpte") -> "ST2084/PQ"
            value.contains("hlg") || value.contains("arib-std-b67") -> "HLG"
            value.contains("log") -> "N-Log"
            else -> "BT.709/OETF"
        }

    private fun normalizeMatrix(value: String): String =
        when {
            value.contains("2020") -> "BT.2020-NCL"
            else -> "BT.709"
        }

    private fun normalizeRange(value: String): String =
        when (value.lowercase(Locale.US)) {
            "pc", "full", "jpeg" -> "FULL"
            "tv", "limited", "mpeg" -> "LIMITED"
            else -> "UNKNOWN"
        }

    private fun parseFps(value: String): Int {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed == "0/0") return 0
        val parts = trimmed.split('/')
        return if (parts.size == 2) {
            val numerator = parts[0].toDoubleOrNull() ?: return 0
            val denominator = parts[1].toDoubleOrNull() ?: return 0
            if (denominator == 0.0) 0 else (numerator / denominator).roundToInt()
        } else {
            trimmed.toDoubleOrNull()?.roundToInt() ?: 0
        }
    }

    private fun resolveRotationValue(fields: Map<String, String>): String =
        fields["rotation"]
            ?: fields["rotate"]
            ?: fields.entries.firstOrNull { (key, _) ->
                key.endsWith(".rotation") || key.endsWith(".rotate")
            }?.value
            ?: ""

    private fun normalizeRotationDegrees(value: String): Int {
        val raw = value.trim().removeSuffix(".0").toIntOrNull() ?: return 0
        val normalized = ((raw % 360) + 360) % 360
        return when {
            normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            normalized < 315 -> 270
            else -> 0
        }
    }

    private fun resolveMirrored(fields: Map<String, String>): Boolean {
        val mirrorValue = fields.entries.firstOrNull { (key, _) ->
            key == "mirror" || key.endsWith(".mirror")
        }?.value.orEmpty()
        if (mirrorValue.equals("true", ignoreCase = true) || mirrorValue == "1") return true
        return fields.entries.any { (key, value) ->
            (key.contains("displaymatrix") || key.contains("display_matrix")) &&
                value.contains("mirror", ignoreCase = true)
        }
    }
}
