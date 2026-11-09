package com.neoproc.neoproccamx.ui.controls

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.neoproc.neoproccamx.core.NdrFeatureGate
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.cinema.CinemaFrameRateCatalog
import com.neoproc.neoproccamx.cinema.CinemaResolutionCatalog
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.RawVideoContractPolicy
import com.neoproc.neoproccamx.recording.RecordingDimensions
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.ui.overlays.GridMode
import com.neoproc.neoproccamx.ui.PreviewAspectRatio
import java.util.Locale
import kotlin.math.abs

internal data class SettingsChoice<T>(val label: String, val value: T)

internal object SettingsOptionCatalog {
    val bitrateStepsMbps: List<Int> =
        listOf(20, 35, 50, 80, 120, 160, 180, 200, 300, 450, 600, 800, 820, 1_000, 1_200, 1_600, 1_968, 2_000)

    fun lensChoices(hasFrontCamera: Boolean): List<SettingsChoice<Int>> = buildList {
        add(SettingsChoice("Rear", CameraCharacteristics.LENS_FACING_BACK))
        if (hasFrontCamera) {
            add(SettingsChoice("Front", CameraCharacteristics.LENS_FACING_FRONT))
        }
    }

    val flashChoices: List<SettingsChoice<FlashMode>> = listOf(
        SettingsChoice("OFF", FlashMode.OFF),
        SettingsChoice("AUTO", FlashMode.AUTO),
        SettingsChoice("ON", FlashMode.ON),
        SettingsChoice("TORCH", FlashMode.TORCH)
    )

    val previewAspectChoices: List<SettingsChoice<PreviewAspectRatio>> = listOf(
        SettingsChoice("4:3", PreviewAspectRatio.FOUR_THREE),
        SettingsChoice("16:9", PreviewAspectRatio.SIXTEEN_NINE)
    )

    private val preferredResolutionPresetNames = listOf(
        "8K",
        "4K",
        "2K",
        "1K"
    )

    private val standardResolutionDimensionChoices: List<Pair<String, RecordingDimensions>> =
        listOf(
            "8K" to RecordingDimensions(7680, 4320),
            "4K" to RecordingDimensions(3840, 2160),
            "2K" to RecordingDimensions(2048, 1080),
            "1K" to RecordingDimensions(1280, 720)
        )
    private val fullSensorFourThreeResolutionChoice =
        "4K 4:3" to RecordingDimensions(4096, 3072)

    private val standardResolutionChoices: List<SettingsChoice<Size>> =
        standardResolutionDimensionChoices.map { (label, dimensions) ->
            SettingsChoice(label, Size(dimensions.width, dimensions.height))
        }

    val resolutionChoices: List<SettingsChoice<Size>> =
        resolutionChoicesForMode(CinemaVideoMode.PRO)

    fun resolutionChoicesForMode(
        mode: CinemaVideoMode,
        supportedOutputDimensions: List<RecordingDimensions> = emptyList()
    ): List<SettingsChoice<Size>> =
        resolutionDimensionChoicesForMode(mode, supportedOutputDimensions).map { (label, dimensions) ->
            SettingsChoice(label, Size(dimensions.width, dimensions.height))
        }

    internal fun resolutionLabelsForMode(
        mode: CinemaVideoMode,
        supportedOutputDimensions: List<RecordingDimensions> = emptyList()
    ): List<String> =
        resolutionDimensionChoicesForMode(mode, supportedOutputDimensions).map { it.first }

    private fun resolutionDimensionChoicesForMode(
        mode: CinemaVideoMode,
        supportedOutputDimensions: List<RecordingDimensions>
    ): List<Pair<String, RecordingDimensions>> {
        if (supportedOutputDimensions.isEmpty()) return standardResolutionDimensionChoices

        val candidatesByTier = supportedOutputDimensions
            .map(::normalizedDimensions)
            .filter { dimensions -> dimensions.width > 0 && dimensions.height > 0 }
            .distinct()
            .mapNotNull { dimensions ->
                resolutionTierLabel(dimensions)?.let { label -> label to dimensions }
            }
            .groupBy({ it.first }, { it.second })

        val visibleChoices = buildList {
            standardResolutionDimensionChoices.forEach { choice ->
                add(choice)
                if (
                    choice.first == "4K" &&
                    candidatesByTier[fullSensorFourThreeResolutionChoice.first].orEmpty().isNotEmpty()
                ) {
                    add(fullSensorFourThreeResolutionChoice)
                }
            }
        }

        return visibleChoices.mapNotNull { (label, canonical) ->
            val candidates = candidatesByTier[label].orEmpty()
            if (candidates.isEmpty()) return@mapNotNull null
            val selected = candidates.firstOrNull { it == canonical }
                ?: candidates.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return@mapNotNull null
            label to selected
        }
    }

    private fun normalizedDimensions(dimensions: RecordingDimensions): RecordingDimensions =
        if (dimensions.width >= dimensions.height) dimensions
        else RecordingDimensions(dimensions.height, dimensions.width)

    private fun resolutionTierLabel(dimensions: RecordingDimensions): String? = when {
        dimensions == fullSensorFourThreeResolutionChoice.second -> fullSensorFourThreeResolutionChoice.first
        dimensions.width == 7680 || dimensions.width == 8192 -> "8K"
        dimensions.width == 3840 || dimensions.width == 4096 -> "4K"
        dimensions.width == 1920 || dimensions.width == 2048 -> "2K"
        dimensions.width == 1280 -> "1K"
        else -> null
    }

    fun selectedResolutionOrDefault(
        currentSize: Size,
        mode: CinemaVideoMode = CinemaVideoMode.PRO,
        supportedOutputDimensions: List<RecordingDimensions> = emptyList()
    ): Size =
        selectedResolutionDimensionsOrDefault(
            currentSize = RecordingDimensions(currentSize.width, currentSize.height),
            mode = mode,
            supportedOutputDimensions = supportedOutputDimensions
        ).let { selected -> Size(selected.width, selected.height) }

    internal fun selectedResolutionDimensionsOrDefault(
        currentSize: RecordingDimensions,
        mode: CinemaVideoMode = CinemaVideoMode.PRO,
        supportedOutputDimensions: List<RecordingDimensions> = emptyList()
    ): RecordingDimensions {
        val choices = resolutionDimensionChoicesForMode(mode, supportedOutputDimensions).map { it.second }
        return choices.firstOrNull { choice -> choice == currentSize }
            ?: choices.minByOrNull { choice ->
                abs(choice.width - currentSize.width) + abs(choice.height - currentSize.height)
            }
            ?: RecordingDimensions(3840, 2160)
    }

    private fun resolutionChoicesFromPresetNames(
        presetNames: List<String>
    ): List<SettingsChoice<Size>> =
        presetNames
            .mapNotNull { presetName ->
                CinemaResolutionCatalog.resolutions
                    .firstOrNull { it.name == presetName }
                    ?.let { preset ->
                        SettingsChoice(
                            preset.name.uppercase(Locale.US),
                            Size(preset.width, preset.height)
                        )
                    }
            }
            .ifEmpty {
                listOf(
                    SettingsChoice("4K UHD", Size(3840, 2160)),
                    SettingsChoice("2K", Size(2048, 1080)),
                    SettingsChoice("1K", Size(1280, 720))
                )
            }

    private val preferredFps = setOf(24, 25, 48, 50, 60)

    val fpsChoices: List<SettingsChoice<Int>> =
        CinemaFrameRateCatalog.frameRates
            .mapNotNull { preset ->
                val integralFps = preset.fps.toInt()
                if (abs(preset.fps - integralFps.toFloat()) < 0.001f && integralFps in preferredFps) {
                    SettingsChoice(integralFps.toString(), integralFps)
                } else {
                    null
                }
            }
            .distinctBy { it.value }
            .sortedBy { it.value }
            .ifEmpty {
                listOf(
                    SettingsChoice("24", 24),
                    SettingsChoice("25", 25),
                    SettingsChoice("60", 60)
                )
            }

    fun selectedFpsOrDefault(currentFps: Int): Int =
        fpsChoices.minByOrNull { abs(it.value - currentFps) }?.value ?: 25

    /**
     * ELITE — the highest framerate the device can record at a given resolution.
     *
     * 4K-class processed video is any route at or above the same 7 MP threshold used by the encoder
     * policy. The high-end contract targets 25 fps there; 2K / 1080p and below keep the high-cadence
     * options. The picker intentionally does not expose 30 fps.
     */
    private const val HIGH_RESOLUTION_PIXEL_THRESHOLD = 7_000_000L

    fun maxFpsForResolution(widthPx: Int, heightPx: Int): Int =
        if (widthPx.coerceAtLeast(0).toLong() * heightPx.coerceAtLeast(0).toLong() >=
            HIGH_RESOLUTION_PIXEL_THRESHOLD
        ) 25 else 60

    fun maxFpsForResolution(size: Size): Int = maxFpsForResolution(size.width, size.height)

    /** Frame-rate choices for a resolution — options above the resolution's cap are hidden. */
    fun fpsChoicesForResolution(widthPx: Int, heightPx: Int): List<SettingsChoice<Int>> {
        val cap = maxFpsForResolution(widthPx, heightPx)
        return fpsChoices.filter { it.value <= cap }.ifEmpty { fpsChoices }
    }

    fun fpsChoicesForResolution(size: Size): List<SettingsChoice<Int>> =
        fpsChoicesForResolution(size.width, size.height)

    /** The current fps clamped into the choices available for a resolution (nearest supported option). */
    fun selectedFpsForResolution(currentFps: Int, size: Size): Int {
        val capped = currentFps.coerceAtMost(maxFpsForResolution(size))
        return fpsChoicesForResolution(size).minByOrNull { abs(it.value - capped) }?.value
            ?: selectedFpsOrDefault(currentFps)
    }

    val videoCodecChoices: List<SettingsChoice<VideoCodecType>> = listOf(
        SettingsChoice("HEVC", VideoCodecType.HEVC)
    )

    val videoHdrChoices: List<SettingsChoice<String>>
        get() = listOf(
            SettingsChoice("OFF", "OFF"),
            SettingsChoice("NDR10+", "NDR10+"),
            SettingsChoice("NIVUKX VISION", "NIVUKXVISION")
        ).filter { NdrFeatureGate.enabled || (!it.value.contains("NDR") && !it.value.contains("NIVUKX")) }

    val bitrateModeChoices: List<SettingsChoice<VideoBitrateMode>> = listOf(
        SettingsChoice("AUTO", VideoBitrateMode.AUTO),
        SettingsChoice("CINEMA MASTER", VideoBitrateMode.CINEMA_MASTER),
        SettingsChoice("CUSTOM", VideoBitrateMode.CUSTOM)
    )

    val videoColorSpaceChoices: List<SettingsChoice<String>>
        get() = listOf(
            SettingsChoice("REC.709", "Rec.709"),
            SettingsChoice("REC.2020", "Rec.2020"),
            SettingsChoice("REC.2020 N-LOG", "Rec.2020-N-Log"),
            SettingsChoice("REC.2020 NDR10+", "Rec.2020-NDR10+"),
            SettingsChoice("REC.2020 NIVUKX", "Rec.2020-NIVUKX"),
            SettingsChoice("NEO WIDE GAMUT", "Neo Wide Gamut"),
            SettingsChoice("NEOGAMUT APEX", "NeoGamut APEX"),
            SettingsChoice("NEO ACES AP1", "Neo ACES AP1"),
            SettingsChoice("NEO ACES AP0", "Neo ACES AP0")
        ).filter { NdrFeatureGate.enabled || (!it.value.contains("NDR") && !it.value.contains("NIVUKX")) }

    val videoColorProfileChoices: List<SettingsChoice<String>>
        get() = (listOf(
            SettingsChoice("NATURAL", "Natural"),
            SettingsChoice("REC.709", "Rec.709"),
            SettingsChoice("REC.2020", "Rec.2020"),
            SettingsChoice("NEO ACES HIGH END", "Neo ACES High End"),
            SettingsChoice("NEO WIDE GAMUT", "Neo Wide Gamut"),
            SettingsChoice("NEOGAMUT APEX", "NeoGamut APEX"),
            SettingsChoice("NDR10+", "NDR10+"),
            SettingsChoice("NIVUKX VISION", "NIVUKX Vision")
        ) + NeoLogProfilePolicy.supportedLogFormats().map { profile ->
            SettingsChoice(profile.uppercase(Locale.US), profile)
        }).filter { NdrFeatureGate.enabled || (!it.value.contains("NDR") && !it.value.contains("NIVUKX") && !it.label.contains("NDR") && !it.label.contains("NIVUKX")) }

    val videoDetailProfileChoices: List<SettingsChoice<VideoDetailProfile>> = listOf(
        SettingsChoice("FOCUSED DETAIL", VideoDetailProfile.FOCUSED_DETAIL),
        SettingsChoice("NATURAL DETAIL", VideoDetailProfile.NATURAL_DETAIL),
        SettingsChoice("EXTREME DETAIL", VideoDetailProfile.EXTREME_DETAIL)
    )

    val gridChoices: List<SettingsChoice<GridMode>> = listOf(
        SettingsChoice("OFF", GridMode.NONE),
        SettingsChoice("3X3 (THIRDS)", GridMode.THIRDS),
        SettingsChoice("CENTER", GridMode.CENTER)
    )

    val stabilizationChoices: List<SettingsChoice<StabilizationMode>> = listOf(
        SettingsChoice("OFF", StabilizationMode.OFF),
        SettingsChoice("OIS", StabilizationMode.OIS),
        SettingsChoice("EIS", StabilizationMode.EIS),
        SettingsChoice("HYBRID", StabilizationMode.HYBRID)
    )

    val audioChoices: List<SettingsChoice<Int>> = listOf(
        SettingsChoice("CAM MIX / CAMCORDER", 0),
        SettingsChoice("BUILT-IN MIC", 1),
        SettingsChoice("UNPROCESSED 48K", 2),
        SettingsChoice("OFF", 3)
    )

    fun cinemaEngineChoices(rawSupported: Boolean = true): List<SettingsChoice<CinemaVideoMode>> =
        CinemaVideoMode.entries
            // LOG is convert-only (offline RAW -> NeoLog develop), never a live capture engine.
            .filter { mode -> mode != CinemaVideoMode.LOG }
            .filter { mode -> rawSupported || mode != CinemaVideoMode.RAW }
            .map { mode ->
                SettingsChoice(
                    when (mode) {
                        CinemaVideoMode.PRO -> "PRO (ORIGINAL)"
                        CinemaVideoMode.LOG -> "LOG (GRADING)"
                        CinemaVideoMode.RAW -> "RAW (SENSOR)"
                    },
                    mode
                )
            }

    val rawCompressionChoices: List<SettingsChoice<RawCompressionMode>> =
        RawCompressionMode.entries.map { mode ->
            SettingsChoice(rawCompressionLabel(mode), mode)
        }

    fun rawCompressionChoicesForMode(mode: CinemaVideoMode): List<SettingsChoice<RawCompressionMode>> =
        if (mode == CinemaVideoMode.RAW) {
            listOf(
                RawVideoContractPolicy.UNCOMPRESSED_12_BIT_MODE
            ).map { rawMode ->
                SettingsChoice(rawCompressionLabel(rawMode), rawMode)
            }
        } else {
            rawCompressionChoices
        }

    fun rawResolutionModeChoices(): List<SettingsChoice<RawCaptureResolutionMode>> =
        RawCaptureResolutionMode.entries.map { mode ->
            SettingsChoice(
                when (mode) {
                    RawCaptureResolutionMode.AUTO -> "AUTO (MAX SENSOR WHEN VIABLE)"
                    RawCaptureResolutionMode.MAXIMUM_SENSOR -> "MAX SENSOR (BURST)"
                    RawCaptureResolutionMode.FULL_ACTIVE_ARRAY -> "FULL ACTIVE ARRAY"
                },
                mode
            )
        }

    fun lutChoices(customLutAvailable: Boolean): List<SettingsChoice<String>> = buildList {
        addAll(ColorScience.BUILT_IN_LUTS.map { lut ->
            SettingsChoice(lut.uppercase(Locale.US), lut)
        })
        if (customLutAvailable) {
            add(SettingsChoice("CUSTOM", "Custom"))
        }
    }

    fun selectedVideoColorProfile(colorProfile: String): String = when {
        videoColorProfileChoices.any { it.value.equals(colorProfile, ignoreCase = true) } ->
            videoColorProfileChoices.first { it.value.equals(colorProfile, ignoreCase = true) }.value
        colorProfile.normalizedProfileKey().let { it == "nlogshadow" } -> "N-Log Shadow"
        colorProfile.normalizedProfileKey().let { it == "nloghighlight" } -> "N-Log Highlight"
        colorProfile.normalizedProfileKey().let { it == "nlogcinema" } -> "N-Log Cinema"
        colorProfile.normalizedProfileKey().let { it == "nlog" || it == "nlogbase" || it == "neoprocnlog" || it == "neopronlog" } -> "N-Log Base"
        colorProfile.normalizedProfileKey().let { it == "aces" || it == "acescg" || it == "acesap1" || it == "neoaces" || it == "neoacesap1" } -> "Neo ACES High End"
        colorProfile.normalizedProfileKey().let { it == "acesap0" || it == "neoacesap0" } -> "Neo ACES High End"
        colorProfile.normalizedProfileKey().let {
            it.contains("externallog") ||
                it.contains("externalcinemagamut") ||
                it.contains("cinemagamut") ||
                it.contains("curve")
        } -> "N-Log Cinema"
        colorProfile.startsWith("Neo", ignoreCase = true) -> "Neo Wide Gamut"
        colorProfile.startsWith("HLG", ignoreCase = true) -> "N-Log Highlight"
        colorProfile.startsWith("HDR10", ignoreCase = true) -> "N-Log Highlight"
        colorProfile.startsWith("Rec.2020", ignoreCase = true) -> "Rec.2020"
        colorProfile.startsWith("Rec.709", ignoreCase = true) -> "Rec.709"
        else -> "Natural"
    }

    private fun String.normalizedProfileKey(): String =
        trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

    fun selectedAudioSource(audioSource: AudioSrc): Int = when (audioSource) {
        AudioSrc.MIC -> 1
        AudioSrc.UNPROCESSED -> 2
        AudioSrc.NONE -> 3
        else -> 0
    }

    /**
     * User-facing label for a [RawCompressionMode].
     * All labels describe the actual NeoRadiance spectral pipeline output:
     * bit depth + relative compression aggressiveness.
     */
    private fun rawCompressionLabel(mode: RawCompressionMode): String = when (mode) {
        RawCompressionMode.OFF -> "OFF (UNCOMPRESSED 16-BIT)"
        RawCompressionMode.LOSSLESS ->
            "QUALITY-PRESERVING 16-BIT"
        RawCompressionMode.LOSSLESS_ENHANCED ->
            "QUALITY-PRESERVING+ 16-BIT"
        RawCompressionMode.VISUALLY_LOSSLESS ->
            "VISUALLY QUALITY-PRESERVING 16-BIT"
        RawCompressionMode.HIGH_QUALITY -> "HIGH QUALITY 14-BIT"
        RawCompressionMode.HIGH_COMPRESSION -> "UNCOMPRESSED 12-BIT RAW"
        RawCompressionMode.NEO_SPECTRAL_RAW -> "NEO SPECTRAL RAW 14-BIT"
        RawCompressionMode.NEO_PURE_RAW -> "NEO PURE RAW 14-BIT"
        RawCompressionMode.NEO_FINE_RAW -> "NEO FINE RAW 14-BIT"
        RawCompressionMode.NEO_DENSE_RAW -> "NEO DENSE RAW 16-BIT"
        RawCompressionMode.LOSSLESS_REALTIME -> "REALTIME LOSSLESS 16-BIT RAW"
    }
}
