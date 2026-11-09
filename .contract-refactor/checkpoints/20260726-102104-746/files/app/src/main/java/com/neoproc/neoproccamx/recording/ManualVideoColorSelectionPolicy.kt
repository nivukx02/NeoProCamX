package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import java.util.Locale

internal data class ManualVideoColorSelectionRequest(
    val colorSpace: String?,
    val colorScience: String?,
    val colorSpaceSelectedByUser: Boolean,
    val colorScienceSelectedByUser: Boolean,
    val savedColorSpacePreference: Boolean = false,
    val savedColorSciencePreference: Boolean = false
)

internal data class ManualVideoColorSelectionPlan(
    val readyForRecording: Boolean,
    val selectedColorSpace: String,
    val selectedColorScience: String,
    val colorSelectionSource: String,
    val manualColorSelectionRequired: Boolean,
    val failureReasons: List<String>,
    val colorProfile: ManualVideoColorProfile
) {
    fun toMetadata(): Map<String, String> = buildMap {
        put("selectedColorSpace", selectedColorSpace)
        put("selectedColorScience", selectedColorScience)
        put("colorSelectionSource", colorSelectionSource)
        put("manualColorSelectionScope", "ALL_VIDEO_PIPELINES_USER_SELECTABLE")
        put("manualColorSelectionRequired", manualColorSelectionRequired.toString())
        put("manualColorSelectionReady", readyForRecording.toString())
        put("manualColorSelectionFailures", failureReasons.joinToString(separator = "; "))
        putAll(colorProfile.toMetadata())
    }

    fun requireReadyForRecording() {
        require(readyForRecording) {
            failureReasons.joinToString(separator = "; ").ifBlank {
                "Manual video color space and color science selection is required"
            }
        }
    }
}

internal data class ManualVideoColorProfile(
    val canonicalColorSpace: String,
    val canonicalColorScience: String,
    val family: String,
    val selectedPrimaries: String,
    val whitePoint: String,
    val transfer: String,
    val matrix: String,
    val workingGamut: String,
    val containerPrimaries: String,
    val containerTransfer: String,
    val containerMatrix: String,
    val recommendedTransport: String,
    val neoProfile: String,
    val wideGamut: Boolean,
    val sceneLinear: Boolean,
    val vendorManaged: Boolean = false
) {
    fun toMetadata(): Map<String, String> = mapOf(
        "selectedColorCanonicalFamily" to family,
        "selectedColorPrimaries" to selectedPrimaries,
        "selectedColorWhitePoint" to whitePoint,
        "selectedColorTransfer" to transfer,
        "selectedColorMatrix" to matrix,
        "selectedColorWorkingGamut" to workingGamut,
        "selectedContainerPrimaries" to containerPrimaries,
        "selectedContainerTransfer" to containerTransfer,
        "selectedContainerMatrix" to containerMatrix,
        "selectedRecommendedTransport" to recommendedTransport,
        "selectedNeoProfile" to neoProfile,
        "selectedWideGamut" to wideGamut.toString(),
        "selectedSceneLinear" to sceneLinear.toString(),
        "vendorManagedColorScience" to vendorManaged.toString()
    )
}

internal object ManualVideoColorSelectionPolicy {
    const val USER_MANUAL = "USER_MANUAL"
    const val SAVED_USER_PREFERENCE = "SAVED_USER_PREFERENCE"
    const val MIXED_USER_AND_SAVED = "MIXED_USER_AND_SAVED"
    const val UNSELECTED = "UNSELECTED"

    fun resolve(request: ManualVideoColorSelectionRequest): ManualVideoColorSelectionPlan {
        val colorSpace = normalizeColorSpace(request.colorSpace)
        val colorScience = normalizeColorScience(request.colorScience)
        val colorSpaceApproved = request.colorSpaceSelectedByUser || request.savedColorSpacePreference
        val colorScienceApproved = request.colorScienceSelectedByUser || request.savedColorSciencePreference
        val failures = buildList {
            if (!colorSpaceApproved) {
                add("video color space must be selected manually by the user or loaded from a saved user preference")
            }
            if (!colorScienceApproved) {
                add("video color science must be selected manually by the user or loaded from a saved user preference")
            }
            if (colorSpace == UNSELECTED) {
                add("video color space must not be blank or unselected")
            } else if (!isSupportedManualColorSpace(colorSpace)) {
                add("video color space '$colorSpace' is not a supported manual recording color space")
            }
            if (colorScience == UNSELECTED) {
                add("video color science must not be blank or unselected")
            }
        }
        val source = when {
            failures.isNotEmpty() -> UNSELECTED
            request.colorSpaceSelectedByUser && request.colorScienceSelectedByUser -> USER_MANUAL
            request.savedColorSpacePreference && request.savedColorSciencePreference -> SAVED_USER_PREFERENCE
            else -> MIXED_USER_AND_SAVED
        }
        return ManualVideoColorSelectionPlan(
            readyForRecording = failures.isEmpty(),
            selectedColorSpace = colorSpace,
            selectedColorScience = colorScience,
            colorSelectionSource = source,
            manualColorSelectionRequired = true,
            failureReasons = failures,
            colorProfile = resolveColorProfile(colorSpace, colorScience)
        )
    }

    fun isApprovedSelectionSource(source: String): Boolean =
        when (source.trim().uppercase(Locale.US)) {
            USER_MANUAL,
            SAVED_USER_PREFERENCE,
            MIXED_USER_AND_SAVED -> true
            else -> false
        }

    fun normalizeColorSpace(value: String?): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.isBlank() || trimmed.equals(UNSELECTED, ignoreCase = true)) return UNSELECTED
        return when (trimmed.normalizedKey()) {
            "rec709", "bt709", "sdr", "normalsdr", "prosdr", "sdrvideo" -> "Rec.709"
            "rec2020", "bt2020" -> "Rec.2020"
            "rec2100", "bt2100" -> "Rec.2020"
            "rec2020nlog", "bt2020nlog", "rec2020log", "bt2020log", "nlog", "log", "neolog" ->
                "Rec.2020-N-Log"
            "rec2020ndr10", "bt2020ndr10", "ndr10", "ndr10plus" -> "Rec.2020-NDR10+"
            "rec2020nivukx", "bt2020nivukx", "rec2020nivukxvision", "bt2020nivukxvision",
            "nivukx", "nivukxvision" ->
                "Rec.2020-NIVUKX"
            "rec2020apex", "bt2020apex", "rec2020nivukxapex", "bt2020nivukxapex" ->
                "Rec.2020-NIVUKX-APEX"
            "neogamutapex" -> "NeoGamut APEX"
            "neowidegamut", "neocinemagamut" -> "Neo Wide Gamut"
            "neoaces", "neoacesap1", "neoacescg", "aces", "acescg", "acesap1" -> "Neo ACES AP1"
            "neoacesap0", "acesap0" -> "Neo ACES AP0"
            "displayp3", "p3", "p3d65",
            "externalwidegamut", "externalcinemagamut", "widegamutrgb" -> "Neo Wide Gamut"
            "raw", "nraw", "neoraw", "sensorraw", "sensornative", "rawsensor" -> "RAW"
            else -> trimmed
        }
    }

    fun normalizeColorScience(value: String?): String {
        val trimmed = value.orEmpty().trim()
        if (trimmed.isBlank() || trimmed.equals(UNSELECTED, ignoreCase = true)) return UNSELECTED
        return NeoLogProfilePolicy.canonicalSupportedLogFormat(trimmed)
            ?: when (trimmed.normalizedKey()) {
                "natural" -> "Natural"
                "standard", "sdr", "normalsdr", "prosdr", "sdrvideo",
                "rec709", "rec709standard", "bt709standard" -> "Rec.709 Standard"
                "displayp3", "p3", "p3d65" -> "Neo Wide Gamut"
                "neogamut", "neogamutapex" -> "NeoGamut APEX"
                "rec2020", "bt2020" -> "Rec.2020"
                "neoaces", "neoaceshighend", "neoacesap0", "neoacesap1",
                "aces", "acesap0", "acescg", "acesap1" -> "Neo ACES High End"
                "ndr10" -> "NDR10+"
                "nivukx", "nivukxvision" -> "NIVUKX Vision"
                "apex", "apexsharp", "nivukxapexsharp" -> "NIVUKX APEX SHARP"
                "apexzero", "nivukxapexzero" -> "NIVUKX APEX ZERO"
                "apexcinema", "nivukxapexcinema" -> "NIVUKX APEX CINEMA"
                "raw", "nraw", "neoraw", "rawsensor", "sensorraw", "sensornative" -> "RAW Sensor Original"
                else -> trimmed
            }
    }

    fun resolveColorProfile(colorSpace: String?, colorScience: String?): ManualVideoColorProfile {
        val canonicalSpace = normalizeColorSpace(colorSpace)
        val canonicalScience = normalizeColorScience(colorScience)
        val key = listOf(canonicalSpace, canonicalScience).joinToString(separator = " ").normalizedKey()
        return when {
            canonicalSpace == UNSELECTED -> rec709Profile(canonicalSpace, canonicalScience)
            canonicalSpace == "RAW" -> rawSensorProfile(canonicalScience)
            key.contains("apex") -> bt2100Profile(
                canonicalSpace = canonicalSpace.takeUnless { it == UNSELECTED } ?: "Rec.2020-NIVUKX-APEX",
                canonicalScience = canonicalScience,
                family = "NIVUKX_APEX",
                neoProfile = canonicalScience.takeIf { it.startsWith("NIVUKX APEX") } ?: "NIVUKX APEX"
            )
            key.contains("nivukx") -> bt2100Profile(
                canonicalSpace = "Rec.2020-NIVUKX",
                canonicalScience = canonicalScience,
                family = "NIVUKX_VISION",
                neoProfile = "NIVUKX Vision"
            )
            key.contains("ndr10") -> bt2100Profile(
                canonicalSpace = "Rec.2020-NDR10+",
                canonicalScience = canonicalScience,
                family = "NDR10_PLUS",
                neoProfile = "NDR10+"
            )
            canonicalSpace == "Neo ACES AP0" -> ManualVideoColorProfile(
                canonicalColorSpace = canonicalSpace,
                canonicalColorScience = canonicalScience,
                family = "NEO_ACES_AP0",
                selectedPrimaries = "ACES AP0",
                whitePoint = "D60",
                transfer = "Neo ACES archive-linear high-end",
                matrix = "ACES AP0 to CIE XYZ",
                workingGamut = "ACES2065-1",
                containerPrimaries = "BT.2020",
                containerTransfer = "SDR_VIDEO",
                containerMatrix = "BT.2020-NCL",
                recommendedTransport = "BT2020_SDR",
                neoProfile = "Neo ACES AP0",
                wideGamut = true,
                sceneLinear = true
            )
            canonicalSpace == "Neo ACES AP1" -> ManualVideoColorProfile(
                canonicalColorSpace = canonicalSpace,
                canonicalColorScience = canonicalScience,
                family = "NEO_ACES_AP1",
                selectedPrimaries = "ACES AP1",
                whitePoint = "D60",
                transfer = "Neo ACES scene-linear high-end",
                matrix = "ACES AP1 to CIE XYZ",
                workingGamut = "ACEScg",
                containerPrimaries = "BT.2020",
                containerTransfer = "SDR_VIDEO",
                containerMatrix = "BT.2020-NCL",
                recommendedTransport = "BT2020_SDR",
                neoProfile = "Neo ACES AP1",
                wideGamut = true,
                sceneLinear = true
            )
            canonicalSpace == "Neo Wide Gamut" -> ManualVideoColorProfile(
                canonicalColorSpace = canonicalSpace,
                canonicalColorScience = canonicalScience,
                family = "NEO_WIDE_GAMUT",
                selectedPrimaries = "Neo WG2",
                whitePoint = "D55",
                transfer = "Scene-linear Neo WG2 / N-Log v3.0",
                matrix = "Neo WG2 D55-normalized to CIE XYZ",
                workingGamut = "Neo Wide Gamut",
                containerPrimaries = "BT.2020",
                containerTransfer = "SDR_VIDEO",
                containerMatrix = "BT.2020-NCL",
                recommendedTransport = "BT2020_SDR",
                neoProfile = "Neo Wide Gamut",
                wideGamut = true,
                sceneLinear = true
            )
            canonicalSpace.contains("2020", ignoreCase = true) ||
                canonicalSpace.contains("2100", ignoreCase = true) -> ManualVideoColorProfile(
                canonicalColorSpace = canonicalSpace,
                canonicalColorScience = canonicalScience,
                family = "REC2020",
                selectedPrimaries = "BT.2020",
                whitePoint = "D65",
                transfer = "SDR_VIDEO",
                matrix = "BT.2020-NCL",
                workingGamut = "BT.2020",
                containerPrimaries = "BT.2020",
                containerTransfer = "SDR_VIDEO",
                containerMatrix = "BT.2020-NCL",
                recommendedTransport = "BT2020_SDR",
                neoProfile = canonicalSpace,
                wideGamut = true,
                sceneLinear = false
            )
            else -> rec709Profile(canonicalSpace, canonicalScience)
        }
    }

    fun isSupportedManualColorSpace(value: String?): Boolean =
        normalizeColorSpace(value) in setOf(
            "Rec.709",
            "Rec.2020",
            "Rec.2020-N-Log",
            "Rec.2020-NDR10+",
            "Rec.2020-NIVUKX",
            "Rec.2020-NIVUKX-APEX",
            "NeoGamut APEX",
            "Neo Wide Gamut",
            "Neo ACES AP1",
            "Neo ACES AP0",
            "RAW"
        )

    private fun String.normalizedKey(): String =
        lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

    private fun rec709Profile(
        canonicalSpace: String,
        canonicalScience: String
    ): ManualVideoColorProfile =
        ManualVideoColorProfile(
            canonicalColorSpace = canonicalSpace.takeUnless { it == UNSELECTED } ?: "Rec.709",
            canonicalColorScience = canonicalScience.takeUnless { it == UNSELECTED } ?: "Rec.709 Standard",
            family = "REC709",
            selectedPrimaries = "BT.709",
            whitePoint = "D65",
            transfer = "BT.1886/sRGB",
            matrix = "BT.709",
            workingGamut = "BT.709",
            containerPrimaries = "BT.709",
            containerTransfer = "SDR_VIDEO",
            containerMatrix = "BT.709",
            recommendedTransport = "REC709_SDR",
            neoProfile = "Rec.709",
            wideGamut = false,
            sceneLinear = false
        )

    private fun rawSensorProfile(canonicalScience: String): ManualVideoColorProfile =
        ManualVideoColorProfile(
            canonicalColorSpace = "RAW",
            canonicalColorScience = canonicalScience.takeUnless { it == UNSELECTED } ?: "RAW Sensor Original",
            family = "RAW_SENSOR",
            selectedPrimaries = "Sensor Native",
            whitePoint = "Sensor Native",
            transfer = "Linear Sensor",
            matrix = "Sensor CFA",
            workingGamut = "Sensor Native",
            containerPrimaries = "RAW",
            containerTransfer = "LINEAR_SENSOR",
            containerMatrix = "Sensor CFA",
            recommendedTransport = "RAW_SENSOR_UNCOMPRESSED",
            neoProfile = "RAW Sensor Original",
            wideGamut = true,
            sceneLinear = true
        )

    private fun bt2100Profile(
        canonicalSpace: String,
        canonicalScience: String,
        family: String,
        neoProfile: String
    ): ManualVideoColorProfile =
        ManualVideoColorProfile(
            canonicalColorSpace = canonicalSpace,
            canonicalColorScience = canonicalScience,
            family = family,
            selectedPrimaries = "BT.2020",
            whitePoint = "D65",
            transfer = "ST2084/PQ",
            matrix = "BT.2020-NCL",
            workingGamut = "BT.2020",
            containerPrimaries = "BT.2020",
            containerTransfer = "ST2084/PQ",
            containerMatrix = "BT.2020-NCL",
            recommendedTransport = "BT.2100_PQ_MAIN10",
            neoProfile = neoProfile,
            wideGamut = true,
            sceneLinear = false
        )
}
