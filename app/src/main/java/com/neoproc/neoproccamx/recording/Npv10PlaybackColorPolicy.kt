package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Resolves the immutable colour meaning carried by an NPV10 header and produces an SDR monitor
 * image without changing the private master. The previous player always decoded as limited-range
 * BT.709, which made BT.2020/PQ masters dark and NeoLog masters look like unviewed log.
 */
internal object Npv10PlaybackColorPolicy {

    internal enum class Transfer {
        SDR,
        NEO_LOG,
        HLG,
        PQ
    }

    internal enum class Primaries {
        BT709,
        BT2020,
        ACES_AP1
    }

    internal enum class Matrix {
        BT709,
        BT2020
    }

    internal data class Plan(
        val transfer: Transfer,
        val primaries: Primaries,
        val matrix: Matrix,
        val fullRange: Boolean,
        val neoLogVariant: NeoLogCurve.Variant,
        val directBt709Sdr: Boolean,
        val pixelAuthorityPresent: Boolean,
        val pixelAuthorityValid: Boolean,
        val pixelAuthorityReason: String
    )

    private data class PixelAuthorityResolution(
        val present: Boolean,
        val valid: Boolean,
        val transfer: Transfer = Transfer.SDR,
        val primaries: Primaries = Primaries.BT709,
        val matrix: Matrix = Matrix.BT709,
        val fullRange: Boolean? = null,
        val reason: String = "NO_PIXEL_AUTHORITY"
    )

    private val PIXEL_ENCODING_KEYS = arrayOf(
        "pixeltransferencoding",
        "payloadtransferencoding",
        "capturedpixeltransfer"
    )
    private val PIXEL_APPLIED_KEYS = arrayOf(
        "pixeltransferapplied",
        "payloadtransferapplied",
        "capturedpixeltransferapplied"
    )
    private val PIXEL_AUTHORITY_KEYS = arrayOf(
        "pixeltransferauthority",
        "payloadtransferauthority",
        "capturedpixeltransferauthority"
    )
    private val PIXEL_PRIMARIES_KEYS = arrayOf(
        "pixelcolorprimaries",
        "pixelprimaries",
        "payloadcolorprimaries"
    )
    private val PIXEL_MATRIX_KEYS = arrayOf(
        "pixelcolormatrix",
        "pixelmatrix",
        "payloadcolormatrix"
    )
    private val PIXEL_RANGE_KEYS = arrayOf(
        "pixelcolorrange",
        "pixelrange",
        "payloadcolorrange"
    )
    private val PIXEL_COLOR_APPLIED_KEYS = arrayOf(
        "pixelcolorapplied",
        "payloadcolorapplied"
    )
    private val PIXEL_COLOR_AUTHORITY_KEYS = arrayOf(
        "pixelcolorauthority",
        "payloadcolorauthority"
    )

    fun resolve(header: Npv10FileHeader): Plan {
        val metadata = header.metadataValues
        val pixelAuthority = resolvePixelAuthority(metadata)
        val transferText = firstValue(
            metadata,
            "transfer",
            "selectedcolortransfer",
            "selectedcontainertransfer"
        )
        val profileText = listOfNotNull(
            metadata["normalizedlogversion"],
            metadata["selectedcolorscience"],
            metadata["colorscience"],
            metadata["colorprofile"],
            metadata["selectedprofile"]
        ).joinToString(" ")
        // New masters separate requested display intent from the encoding already carried by the
        // stored P010 pixels. The latter is the only valid decode authority. Legacy masters did not
        // have that evidence, so they retain the original intent-based interpretation unchanged.
        val transfer = when {
            !pixelAuthority.present -> classifyTransfer(transferText, profileText)
            pixelAuthority.valid -> pixelAuthority.transfer
            else -> Transfer.SDR
        }

        val primariesText = firstValue(
            metadata,
            "primaries",
            "selectedcontainerprimaries",
            "selectedcolorprimaries",
            "colorspace",
            "selectedcolorspace"
        )
        val primaries = when {
            pixelAuthority.present && pixelAuthority.valid -> pixelAuthority.primaries
            pixelAuthority.present -> Primaries.BT709
            primariesText.contains("aces ap1") || primariesText.contains("acescg") ->
                Primaries.ACES_AP1
            primariesText.contains("2020") || transfer == Transfer.PQ || transfer == Transfer.HLG ->
                Primaries.BT2020
            else -> Primaries.BT709
        }

        val matrixText = firstValue(
            metadata,
            "matrix",
            "selectedcontainermatrix",
            "selectedcolormatrix"
        )
        val matrix = when {
            pixelAuthority.present && pixelAuthority.valid -> pixelAuthority.matrix
            pixelAuthority.present -> Matrix.BT709
            matrixText.contains("2020") -> Matrix.BT2020
            matrixText.contains("709") -> Matrix.BT709
            primaries == Primaries.BT2020 || transfer == Transfer.PQ || transfer == Transfer.HLG ->
                Matrix.BT2020
            else -> Matrix.BT709
        }
        val rangeText = firstValue(
            metadata,
            "range",
            "nivukxdrrange",
            "selectedcontainerrange",
            "colorrange"
        )
        val resolvedFullRange = when {
            pixelAuthority.present && !pixelAuthority.valid -> false
            pixelAuthority.valid && pixelAuthority.fullRange != null -> pixelAuthority.fullRange
            else -> rangeText.contains("full") && !rangeText.contains("limited")
        }
        val variant = when {
            profileText.contains("shadow") -> NeoLogCurve.Variant.SHADOW
            profileText.contains("highlight") -> NeoLogCurve.Variant.HIGHLIGHT
            profileText.contains("cinema") -> NeoLogCurve.Variant.CINEMA
            else -> NeoLogCurve.Variant.BASE
        }
        return Plan(
            transfer = transfer,
            primaries = primaries,
            matrix = matrix,
            fullRange = resolvedFullRange,
            neoLogVariant = variant,
            directBt709Sdr = transfer == Transfer.SDR &&
                primaries == Primaries.BT709 &&
                matrix == Matrix.BT709 &&
                !resolvedFullRange,
            pixelAuthorityPresent = pixelAuthority.present,
            pixelAuthorityValid = pixelAuthority.valid,
            pixelAuthorityReason = pixelAuthority.reason
        )
    }

    /**
     * Resolves the meaning of pixels that are already stored in the master. Any partial, disabled,
     * unknown, or internally contradictory authority packet fails closed to limited-range BT.709
     * instead of falling back to a requested HDR look and mis-decoding SDR carrier pixels as PQ.
     */
    private fun resolvePixelAuthority(metadata: Map<String, String>): PixelAuthorityResolution {
        val gamutPacketPresent = PIXEL_PRIMARIES_KEYS.any(metadata::containsKey) ||
            PIXEL_MATRIX_KEYS.any(metadata::containsKey) ||
            PIXEL_RANGE_KEYS.any(metadata::containsKey) ||
            PIXEL_COLOR_APPLIED_KEYS.any(metadata::containsKey) ||
            PIXEL_COLOR_AUTHORITY_KEYS.any(metadata::containsKey)
        val present = PIXEL_ENCODING_KEYS.any(metadata::containsKey) ||
            PIXEL_APPLIED_KEYS.any(metadata::containsKey) ||
            PIXEL_AUTHORITY_KEYS.any(metadata::containsKey) || gamutPacketPresent
        if (!present) return PixelAuthorityResolution(present = false, valid = false)

        val aliasConflict = when {
            aliasValuesConflict(metadata, PIXEL_ENCODING_KEYS) {
                classifyPixelTransfer(it)?.name ?: "INVALID:${normalizedToken(it)}"
            } -> "CONFLICTING_PIXEL_TRANSFER_ALIASES"
            aliasValuesConflict(metadata, PIXEL_APPLIED_KEYS, ::normalizedToken) ->
                "CONFLICTING_PIXEL_TRANSFER_APPLIED_ALIASES"
            aliasValuesConflict(metadata, PIXEL_AUTHORITY_KEYS, ::normalizedToken) ->
                "CONFLICTING_PIXEL_TRANSFER_AUTHORITY_ALIASES"
            aliasValuesConflict(metadata, PIXEL_PRIMARIES_KEYS) {
                classifyPixelPrimaries(it)?.name ?: "INVALID:${normalizedToken(it)}"
            } -> "CONFLICTING_PIXEL_PRIMARIES_ALIASES"
            aliasValuesConflict(metadata, PIXEL_MATRIX_KEYS) {
                classifyPixelMatrix(it)?.name ?: "INVALID:${normalizedToken(it)}"
            } -> "CONFLICTING_PIXEL_MATRIX_ALIASES"
            aliasValuesConflict(metadata, PIXEL_RANGE_KEYS) {
                classifyPixelRange(it)?.toString() ?: "INVALID:${normalizedToken(it)}"
            } -> "CONFLICTING_PIXEL_RANGE_ALIASES"
            aliasValuesConflict(metadata, PIXEL_COLOR_APPLIED_KEYS, ::normalizedToken) ->
                "CONFLICTING_PIXEL_COLOR_APPLIED_ALIASES"
            aliasValuesConflict(metadata, PIXEL_COLOR_AUTHORITY_KEYS, ::normalizedToken) ->
                "CONFLICTING_PIXEL_COLOR_AUTHORITY_ALIASES"
            else -> null
        }
        if (aliasConflict != null) return invalidPixelAuthority(aliasConflict)

        val encodingText = firstValueFromKeys(metadata, PIXEL_ENCODING_KEYS)
        val appliedText = firstValueFromKeys(metadata, PIXEL_APPLIED_KEYS)
        val authorityDeclared = PIXEL_AUTHORITY_KEYS.any(metadata::containsKey)
        val authorityText = firstValueFromKeys(metadata, PIXEL_AUTHORITY_KEYS)
        val transfer = classifyPixelTransfer(encodingText)
        val normalizedAuthority = authorityText.filter(Char::isLetterOrDigit).lowercase(Locale.US)
        val authorityMalformed = !authorityDeclared || normalizedAuthority.isBlank()
        val legacyAuthorityConflicts = when {
            transfer == null -> false
            normalizedAuthority.contains("camera2standardprofile") -> transfer != Transfer.SDR
            normalizedAuthority.contains("camera2dynamicrangeprofile") -> transfer == Transfer.SDR
            else -> false
        }
        if (!appliedText.equals("true", ignoreCase = true) ||
            transfer == null ||
            authorityMalformed ||
            legacyAuthorityConflicts
        ) {
            val reason = when {
                !appliedText.equals("true", ignoreCase = true) -> "PIXEL_TRANSFER_NOT_APPLIED"
                transfer == null -> "UNKNOWN_PIXEL_TRANSFER"
                authorityMalformed -> "MISSING_PIXEL_TRANSFER_AUTHORITY"
                else -> "PIXEL_TRANSFER_AUTHORITY_CONFLICT"
            }
            return PixelAuthorityResolution(present = true, valid = false, reason = reason)
        }

        if (gamutPacketPresent) {
            return resolveCompletePixelColorAuthority(
                metadata = metadata,
                transfer = transfer,
                normalizedTransferAuthority = normalizedAuthority
            )
        }

        // Compatibility for masters written before pixel primaries/matrix/range were introduced.
        // STANDARD + BT.709 remains exactly BT.709; HDR profiles retain their former BT.2020 decode.
        val hdrCarrier = transfer == Transfer.PQ || transfer == Transfer.HLG
        return PixelAuthorityResolution(
            present = true,
            valid = true,
            transfer = transfer,
            primaries = if (hdrCarrier) Primaries.BT2020 else Primaries.BT709,
            matrix = if (hdrCarrier) Matrix.BT2020 else Matrix.BT709,
            reason = "LEGACY_PIXEL_TRANSFER_AUTHORITY"
        )
    }

    private fun resolveCompletePixelColorAuthority(
        metadata: Map<String, String>,
        transfer: Transfer,
        normalizedTransferAuthority: String
    ): PixelAuthorityResolution {
        val primariesText = firstValueFromKeys(metadata, PIXEL_PRIMARIES_KEYS)
        val matrixText = firstValueFromKeys(metadata, PIXEL_MATRIX_KEYS)
        val rangeText = firstValueFromKeys(metadata, PIXEL_RANGE_KEYS)
        val colorAppliedText = firstValueFromKeys(metadata, PIXEL_COLOR_APPLIED_KEYS)
        val colorAuthorityText = firstValueFromKeys(metadata, PIXEL_COLOR_AUTHORITY_KEYS)
        val normalizedColorAuthority =
            colorAuthorityText.filter(Char::isLetterOrDigit).lowercase(Locale.US)

        if (!colorAppliedText.equals("true", ignoreCase = true)) {
            return invalidPixelAuthority("PIXEL_COLOR_NOT_APPLIED")
        }
        if (normalizedColorAuthority.isBlank() ||
            normalizedColorAuthority != normalizedTransferAuthority
        ) {
            return invalidPixelAuthority("PIXEL_COLOR_AUTHORITY_MISMATCH")
        }
        val verifiedProducerAuthority = normalizedColorAuthority == "imagedataspace" ||
            normalizedColorAuthority == "bufferdataspace" ||
            normalizedColorAuthority == "camera2imagedataspace" ||
            normalizedColorAuthority == "testverifiedsource"
        if (!verifiedProducerAuthority) {
            return invalidPixelAuthority("UNVERIFIED_PIXEL_COLOR_AUTHORITY")
        }

        val primaries = classifyPixelPrimaries(primariesText)
            ?: return invalidPixelAuthority("UNKNOWN_PIXEL_PRIMARIES")
        val matrix = classifyPixelMatrix(matrixText)
            ?: return invalidPixelAuthority("UNKNOWN_PIXEL_MATRIX")
        val fullRange = classifyPixelRange(rangeText)
            ?: return invalidPixelAuthority("UNKNOWN_PIXEL_RANGE")
        val internallyConsistent = when (transfer) {
            Transfer.PQ,
            Transfer.HLG -> primaries == Primaries.BT2020 && matrix == Matrix.BT2020
            Transfer.SDR -> (primaries == Primaries.BT709 && matrix == Matrix.BT709) ||
                (primaries == Primaries.BT2020 && matrix == Matrix.BT2020)
            Transfer.NEO_LOG -> false
        }
        if (!internallyConsistent) {
            return invalidPixelAuthority("PIXEL_TRANSFER_GAMUT_MATRIX_CONFLICT")
        }
        return PixelAuthorityResolution(
            present = true,
            valid = true,
            transfer = transfer,
            primaries = primaries,
            matrix = matrix,
            fullRange = fullRange,
            reason = "VERIFIED_COMPLETE_PIXEL_COLOR_AUTHORITY"
        )
    }

    private fun invalidPixelAuthority(reason: String): PixelAuthorityResolution =
        PixelAuthorityResolution(present = true, valid = false, reason = reason)

    private fun classifyPixelPrimaries(value: String): Primaries? {
        val normalized = value.filter(Char::isLetterOrDigit).lowercase(Locale.US)
        return when (normalized) {
            "bt709", "rec709" -> Primaries.BT709
            "bt2020", "rec2020" -> Primaries.BT2020
            else -> null
        }
    }

    private fun classifyPixelMatrix(value: String): Matrix? {
        val normalized = value.filter(Char::isLetterOrDigit).lowercase(Locale.US)
        return when (normalized) {
            "bt709", "rec709" -> Matrix.BT709
            "bt2020", "rec2020", "bt2020ncl", "rec2020ncl" -> Matrix.BT2020
            else -> null
        }
    }

    private fun classifyPixelRange(value: String): Boolean? {
        val normalized = value.filter(Char::isLetterOrDigit).lowercase(Locale.US)
        return when (normalized) {
            "full", "fullrange" -> true
            "limited", "limitedrange", "video", "videorange" -> false
            else -> null
        }
    }

    private fun classifyPixelTransfer(value: String): Transfer? {
        val normalized = value.filter(Char::isLetterOrDigit).lowercase(Locale.US)
        return when (normalized) {
            "bt709", "rec709", "sdr", "bt709oetf", "rec709oetf" -> Transfer.SDR
            "hlg", "bt2100hlg", "hlgbt2100", "aribstdb67", "aribstdb67hlg" -> Transfer.HLG
            "pq", "st2084", "st2084pq", "pqst2084", "smpte2084", "smptest2084",
            "bt2100pq" -> Transfer.PQ
            else -> null
        }
    }

    private fun aliasValuesConflict(
        metadata: Map<String, String>,
        keys: Array<String>,
        normalize: (String) -> String
    ): Boolean = keys.mapNotNull { key ->
        metadata[key]?.takeIf(String::isNotBlank)?.let(normalize)
    }.distinct().size > 1

    private fun normalizedToken(value: String): String =
        value.filter(Char::isLetterOrDigit).lowercase(Locale.US)

    private fun firstValueFromKeys(metadata: Map<String, String>, keys: Array<String>): String =
        keys.firstNotNullOfOrNull { key -> metadata[key]?.takeIf { value -> value.isNotBlank() } }
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()

    fun yuvToDisplayArgb(plan: Plan, y10: Int, cb10: Int, cr10: Int): Int {
        val yCode = y10.coerceIn(0, 1023)
        val cbCode = cb10.coerceIn(0, 1023)
        val crCode = cr10.coerceIn(0, 1023)
        val y = if (plan.fullRange) yCode / 1023f else (yCode - 64) / 876f
        val cb = if (plan.fullRange) (cbCode - 512) / 1023f else (cbCode - 512) / 896f
        val cr = if (plan.fullRange) (crCode - 512) / 1023f else (crCode - 512) / 896f
        val encodedRed: Float
        val encodedGreen: Float
        val encodedBlue: Float
        when (plan.matrix) {
            Matrix.BT709 -> {
                encodedRed = (y + 1.5748f * cr).coerceIn(0f, 1f)
                encodedGreen = (y - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f)
                encodedBlue = (y + 1.8556f * cb).coerceIn(0f, 1f)
            }
            Matrix.BT2020 -> {
                encodedRed = (y + 1.4746f * cr).coerceIn(0f, 1f)
                encodedGreen = (y - 0.164553f * cb - 0.571353f * cr).coerceIn(0f, 1f)
                encodedBlue = (y + 1.8814f * cb).coerceIn(0f, 1f)
            }
        }
        if (plan.transfer == Transfer.SDR && plan.primaries == Primaries.BT709) {
            return argb(encodedRed, encodedGreen, encodedBlue)
        }

        var linearRed: Float
        var linearGreen: Float
        var linearBlue: Float
        when (plan.transfer) {
            Transfer.PQ -> {
                linearRed = lookup(PQ_TO_DIFFUSE_LINEAR, encodedRed)
                linearGreen = lookup(PQ_TO_DIFFUSE_LINEAR, encodedGreen)
                linearBlue = lookup(PQ_TO_DIFFUSE_LINEAR, encodedBlue)
            }
            Transfer.HLG -> {
                linearRed = lookup(HLG_TO_DIFFUSE_LINEAR, encodedRed)
                linearGreen = lookup(HLG_TO_DIFFUSE_LINEAR, encodedGreen)
                linearBlue = lookup(HLG_TO_DIFFUSE_LINEAR, encodedBlue)
            }
            Transfer.NEO_LOG -> {
                val lut = neoLogDecodeLut(plan.neoLogVariant)
                linearRed = lookup(lut, encodedRed)
                linearGreen = lookup(lut, encodedGreen)
                linearBlue = lookup(lut, encodedBlue)
            }
            Transfer.SDR -> {
                linearRed = lookup(REC709_TO_LINEAR, encodedRed)
                linearGreen = lookup(REC709_TO_LINEAR, encodedGreen)
                linearBlue = lookup(REC709_TO_LINEAR, encodedBlue)
            }
        }

        if (plan.transfer == Transfer.PQ || plan.transfer == Transfer.HLG) {
            val luminance = when (plan.primaries) {
                Primaries.BT709 -> linearRed * 0.2126f + linearGreen * 0.7152f + linearBlue * 0.0722f
                Primaries.BT2020 -> linearRed * 0.2627f + linearGreen * 0.6780f + linearBlue * 0.0593f
                Primaries.ACES_AP1 -> linearRed * 0.2722f + linearGreen * 0.6741f + linearBlue * 0.0537f
            }.coerceAtLeast(1.0e-8f)
            val monitorLuminance = if (plan.transfer == Transfer.PQ) {
                sdrMonitorLuminance(luminance)
            } else {
                neutralHlgMonitorLuminance(luminance)
            }
            val scale = monitorLuminance / luminance
            linearRed *= scale
            linearGreen *= scale
            linearBlue *= scale
        }

        val rec709Red: Float
        val rec709Green: Float
        val rec709Blue: Float
        when (plan.primaries) {
            Primaries.BT709 -> {
                rec709Red = linearRed
                rec709Green = linearGreen
                rec709Blue = linearBlue
            }
            Primaries.BT2020 -> {
                rec709Red = 1.660491f * linearRed - 0.587641f * linearGreen - 0.072850f * linearBlue
                rec709Green = -0.124550f * linearRed + 1.132900f * linearGreen - 0.008349f * linearBlue
                rec709Blue = -0.018151f * linearRed - 0.100579f * linearGreen + 1.118730f * linearBlue
            }
            Primaries.ACES_AP1 -> {
                rec709Red = 1.70505f * linearRed - 0.62179f * linearGreen - 0.08326f * linearBlue
                rec709Green = -0.13026f * linearRed + 1.14080f * linearGreen - 0.01055f * linearBlue
                rec709Blue = -0.02400f * linearRed - 0.12897f * linearGreen + 1.15297f * linearBlue
            }
        }
        return gamutCompressedArgb(
            redInput = rec709Red,
            greenInput = rec709Green,
            blueInput = rec709Blue,
            protectHdrHeadroom = plan.transfer == Transfer.PQ
        )
    }

    private fun classifyTransfer(transferText: String, profileText: String): Transfer {
        val combined = "$transferText $profileText"
        return when {
            transferText.contains("2084") || transferText.contains("pq") -> Transfer.PQ
            transferText.contains("hlg") -> Transfer.HLG
            transferText.contains("log") || combined.contains("n-log") ||
                combined.contains("nlog") || combined.contains("neolog") -> Transfer.NEO_LOG
            else -> Transfer.SDR
        }
    }

    private fun firstValue(metadata: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> metadata[key]?.takeIf { value -> value.isNotBlank() } }
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()

    /** Mirrors the calibrated NDR/NIVUKX SDR monitor shoulder used by the live preview. */
    private fun sdrMonitorLuminance(signal: Float): Float {
        val x = signal.coerceAtLeast(0f)
        if (x <= 1f) return x * SDR_MONITOR_DIFFUSE_LINEAR
        val headroom = 1f - SDR_MONITOR_DIFFUSE_LINEAR
        val shoulderLength = headroom / SDR_MONITOR_DIFFUSE_LINEAR
        val excess = x - 1f
        return (SDR_MONITOR_DIFFUSE_LINEAR + headroom * (excess / (excess + shoulderLength)))
            .coerceIn(0f, 1f)
    }

    private fun neutralHlgMonitorLuminance(signal: Float): Float {
        val x = signal.coerceAtLeast(0f)
        if (x <= HLG_MONITOR_KNEE) return x
        val hdrSpan = (1_000f / PQ_DIFFUSE_NITS - HLG_MONITOR_KNEE).coerceAtLeast(0.25f)
        return (HLG_MONITOR_KNEE + (1f - HLG_MONITOR_KNEE) *
            (1f - exp((-4f * (x - HLG_MONITOR_KNEE) / hdrSpan).toDouble()).toFloat()))
            .coerceIn(0f, 1f)
    }

    /** Hue-preserving BT.709 gamut compression; no per-channel highlight clipping. */
    private fun gamutCompressedArgb(
        redInput: Float,
        greenInput: Float,
        blueInput: Float,
        protectHdrHeadroom: Boolean
    ): Int {
        var red = redInput
        var green = greenInput
        var blue = blueInput
        var luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f).coerceAtLeast(0f)
        val low = min(red, min(green, blue))
        if (low < 0f) {
            val factor = (luminance / (luminance - low)).coerceIn(0f, 1f)
            red = luminance + (red - luminance) * factor
            green = luminance + (green - luminance) * factor
            blue = luminance + (blue - luminance) * factor
        }
        luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f).coerceIn(0f, 1f)
        val high = max(red, max(green, blue))
        val upperCeiling = if (protectHdrHeadroom) {
            lerp(0.985f, 0.965f, smoothstep(0.65f, 0.98f, luminance))
        } else {
            1f
        }
        val targetHigh = max(luminance, upperCeiling)
        if (high > targetHigh) {
            val factor = ((targetHigh - luminance) / max(high - luminance, 1.0e-8f))
                .coerceIn(0f, 1f)
            red = luminance + (red - luminance) * factor
            green = luminance + (green - luminance) * factor
            blue = luminance + (blue - luminance) * factor
        }
        return argbLinear(
            red.coerceIn(0f, 1f),
            green.coerceIn(0f, 1f),
            blue.coerceIn(0f, 1f)
        )
    }

    private fun argb(red: Float, green: Float, blue: Float): Int {
        val r8 = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g8 = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b8 = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }

    private fun argbLinear(red: Float, green: Float, blue: Float): Int =
        (0xFF shl 24) or
            (SRGB_8[linearIndex(red)] shl 16) or
            (SRGB_8[linearIndex(green)] shl 8) or
            SRGB_8[linearIndex(blue)]

    private fun linearIndex(value: Float): Int =
        (value.coerceIn(0f, 1f) * SRGB_LUT_LAST + 0.5f).toInt().coerceIn(0, SRGB_LUT_LAST)

    private fun lookup(table: FloatArray, value: Float): Float {
        val position = value.coerceIn(0f, 1f) * TRANSFER_LUT_LAST
        val low = position.toInt().coerceIn(0, TRANSFER_LUT_LAST)
        if (low >= TRANSFER_LUT_LAST) return table[TRANSFER_LUT_LAST]
        val fraction = position - low
        return table[low] + (table[low + 1] - table[low]) * fraction
    }

    private fun neoLogDecodeLut(variant: NeoLogCurve.Variant): FloatArray = when (variant) {
        NeoLogCurve.Variant.BASE -> NEO_LOG_BASE_TO_LINEAR
        NeoLogCurve.Variant.SHADOW -> NEO_LOG_SHADOW_TO_LINEAR
        NeoLogCurve.Variant.HIGHLIGHT -> NEO_LOG_HIGHLIGHT_TO_LINEAR
        NeoLogCurve.Variant.CINEMA -> NEO_LOG_CINEMA_TO_LINEAR
    }

    private fun neoLogLut(variant: NeoLogCurve.Variant): FloatArray =
        FloatArray(TRANSFER_LUT_SIZE) { index ->
            NeoLogCurve.decode(index.toFloat() / TRANSFER_LUT_LAST, variant)
        }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    private const val TRANSFER_LUT_LAST = 4_096
    private const val TRANSFER_LUT_SIZE = TRANSFER_LUT_LAST + 1
    private const val SRGB_LUT_LAST = 16_384
    private const val PQ_DIFFUSE_NITS = 203f
    private const val HLG_DIFFUSE_SCENE = 0.264967f
    private const val HLG_MONITOR_KNEE = 0.75f
    private const val SDR_MONITOR_DIFFUSE_LINEAR = 0.60382734f

    private val REC709_TO_LINEAR = FloatArray(TRANSFER_LUT_SIZE) { index ->
        val encoded = index.toFloat() / TRANSFER_LUT_LAST
        if (encoded < 0.081f) encoded / 4.5f
        else ((encoded + 0.099f) / 1.099f).toDouble().pow(1.0 / 0.45).toFloat()
    }
    private val PQ_TO_DIFFUSE_LINEAR = FloatArray(TRANSFER_LUT_SIZE) { index ->
        val encoded = index.toDouble() / TRANSFER_LUT_LAST
        val power = encoded.pow(1.0 / 78.84375)
        val numerator = max(power - 0.8359375, 0.0)
        val denominator = max(18.8515625 - 18.6875 * power, 1.0e-12)
        (numerator / denominator).pow(1.0 / 0.1593017578125).toFloat() *
            (10_000f / PQ_DIFFUSE_NITS)
    }
    private val HLG_TO_DIFFUSE_LINEAR = FloatArray(TRANSFER_LUT_SIZE) { index ->
        val encoded = index.toFloat() / TRANSFER_LUT_LAST
        val scene = if (encoded <= 0.5f) {
            encoded * encoded / 3f
        } else {
            ((exp(((encoded - 0.55991073f) / 0.17883277f).toDouble()).toFloat() + 0.28466892f) / 12f)
        }
        scene / HLG_DIFFUSE_SCENE
    }
    private val NEO_LOG_BASE_TO_LINEAR by lazy(LazyThreadSafetyMode.PUBLICATION) {
        neoLogLut(NeoLogCurve.Variant.BASE)
    }
    private val NEO_LOG_SHADOW_TO_LINEAR by lazy(LazyThreadSafetyMode.PUBLICATION) {
        neoLogLut(NeoLogCurve.Variant.SHADOW)
    }
    private val NEO_LOG_HIGHLIGHT_TO_LINEAR by lazy(LazyThreadSafetyMode.PUBLICATION) {
        neoLogLut(NeoLogCurve.Variant.HIGHLIGHT)
    }
    private val NEO_LOG_CINEMA_TO_LINEAR by lazy(LazyThreadSafetyMode.PUBLICATION) {
        neoLogLut(NeoLogCurve.Variant.CINEMA)
    }
    private val SRGB_8 = IntArray(SRGB_LUT_LAST + 1) { index ->
        val linear = index.toDouble() / SRGB_LUT_LAST
        val encoded = if (linear <= 0.0031308) {
            12.92 * linear
        } else {
            1.055 * linear.pow(1.0 / 2.4) - 0.055
        }
        (encoded.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    }

}
