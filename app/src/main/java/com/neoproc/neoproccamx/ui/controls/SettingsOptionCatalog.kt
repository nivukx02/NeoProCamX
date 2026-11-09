package com.neoproc.neoproccamx.ui.controls

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.neoproc.neoproccamx.core.NdrFeatureGate
import com.neoproc.neoproccamx.camera.FlashMode
import com.neoproc.neoproccamx.camera.CameraVideoStabilizationRequestPolicy
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

/**
 * Product recording-cadence contract shared by the Settings UI and the fail-closed mutation gate.
 *
 * 4K-or-higher capture is intentionally limited to the verified 25 fps cinema tier. Keeping the
 * rule in one pure authority prevents a stale preference, debug command, or settings rebuild from
 * exposing a cadence that the production recorder would later have to reduce silently.
 */
internal object RecordingResolutionFpsContract {
    const val FOUR_K_MAX_FPS = 25
    const val STANDARD_MAX_FPS = 30
    private const val FOUR_K_LONG_EDGE = 3_840

    fun isFourKOrHigher(widthPx: Int, heightPx: Int): Boolean {
        val longEdge = maxOf(widthPx, heightPx).coerceAtLeast(0)
        // The settings catalog classifies every 3840/4096-wide sensor crop as 4K, including cinema
        // scope formats such as 4096x1716. Use that same long-edge authority here so a cropped 4K
        // stream can never regain the lower-resolution 30 fps choice.
        return longEdge >= FOUR_K_LONG_EDGE
    }

    fun maxFps(widthPx: Int, heightPx: Int): Int =
        if (isFourKOrHigher(widthPx, heightPx)) FOUR_K_MAX_FPS else STANDARD_MAX_FPS

    fun accepts(widthPx: Int, heightPx: Int, fps: Int): Boolean =
        if (isFourKOrHigher(widthPx, heightPx)) {
            fps == FOUR_K_MAX_FPS
        } else {
            fps == FOUR_K_MAX_FPS || fps == STANDARD_MAX_FPS
        }
}

internal object SettingsOptionCatalog {
    val bitrateStepsMbps: List<Int> =
        listOf(20, 35, 50, 80, 120, 160, 180, 200, 240, 300, 450, 600, 800, 820, 1_000, 1_200, 1_600, 1_968, 2_000)
    val bitrateStepLabels: List<String> = bitrateStepsMbps.map(Int::toString)

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

    val photoQualityChoices: List<SettingsChoice<Boolean>> = listOf(
        SettingsChoice("HIGH QUALITY · FAST", false),
        SettingsChoice("MAXIMUM SENSOR DETAIL", true)
    )

    private val preferredResolutionPresetNames = listOf(
        "4K"
    )

    private val standardResolutionDimensionChoices: List<Pair<String, RecordingDimensions>> =
        listOf(
            "4K" to RecordingDimensions(3840, 2160)
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
                    SettingsChoice("4K UHD", Size(3840, 2160))
                )
            }

    /** Product-visible recording cadences. Legacy/internal rates remain parseable but are not offered. */
    private val preferredFps = setOf(25, 30)

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
                    SettingsChoice("25", 25),
                    SettingsChoice("30", 30)
                )
            }

    fun selectedFpsOrDefault(currentFps: Int): Int =
        fpsChoices.minByOrNull { abs(it.value - currentFps) }?.value ?: 25

    /**
     * ELITE — the highest framerate the device can record at a given resolution.
     *
     * Cadence intent is independent of resolution. Exact Camera2/codec validation occurs while the
     * recording graph is prepared; an unsupported pair is rejected with a hardware reason instead of
     * being silently converted to another frame rate.
     */
    fun maxFpsForResolution(widthPx: Int, heightPx: Int): Int =
        RecordingResolutionFpsContract.maxFps(widthPx, heightPx)

    fun maxFpsForResolution(size: Size): Int = maxFpsForResolution(size.width, size.height)

    /** Frame-rate choices for a resolution — options above the resolution's cap are hidden. */
    fun fpsChoicesForResolution(widthPx: Int, heightPx: Int): List<SettingsChoice<Int>> {
        val maximumFps = maxFpsForResolution(widthPx, heightPx)
        return fpsChoices.filter { choice -> choice.value <= maximumFps }
    }

    fun fpsChoicesForResolution(size: Size): List<SettingsChoice<Int>> =
        fpsChoicesForResolution(size.width, size.height)

    /** The current fps normalized into the choices available for a resolution (nearest supported option). */
    fun selectedFpsForResolution(currentFps: Int, widthPx: Int, heightPx: Int): Int {
        return fpsChoicesForResolution(widthPx, heightPx)
            .minByOrNull { abs(it.value - currentFps) }
            ?.value
            ?: selectedFpsOrDefault(currentFps)
    }

    fun selectedFpsForResolution(currentFps: Int, size: Size): Int =
        selectedFpsForResolution(currentFps, size.width, size.height)

    val videoCodecChoices: List<SettingsChoice<VideoCodecType>> = listOf(
        SettingsChoice("HEVC", VideoCodecType.HEVC)
    )

    val videoHdrChoices: List<SettingsChoice<String>>
        get() = listOf(
            SettingsChoice("OFF", "OFF"),
            SettingsChoice("XDR10+", "NDR10+"),
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
            SettingsChoice("REC.2020 XDR10+", "Rec.2020-NDR10+"),
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
            SettingsChoice("XDR10+", "NDR10+"),
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
        SettingsChoice("AUTO (BEST AVAILABLE)", StabilizationMode.HYBRID)
    )

    fun stabilizationChoicesForCapabilities(
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        rawVideoCaptureEnabled: Boolean = false
    ): List<SettingsChoice<StabilizationMode>> = stabilizationChoices.filter { choice ->
        if (rawVideoCaptureEnabled) return@filter choice.value == StabilizationMode.OFF
        when (choice.value) {
            StabilizationMode.OFF -> true
            StabilizationMode.OIS -> supportsOis
            StabilizationMode.EIS -> supportsVideoStabilization
            StabilizationMode.HYBRID -> supportsOis && supportsVideoStabilization
        }
    }

    fun selectedStabilizationForCapabilities(
        requestedMode: StabilizationMode,
        supportsOis: Boolean,
        supportsVideoStabilization: Boolean,
        rawVideoCaptureEnabled: Boolean = false,
        supportsPreviewStabilization: Boolean = false
    ): StabilizationMode {
        val choices = stabilizationChoicesForCapabilities(
            supportsOis = supportsOis,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled
        )
        return CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = requestedMode,
            supportsOis = supportsOis,
            supportsVideoStabilization = supportsVideoStabilization,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            supportsPreviewStabilization = supportsPreviewStabilization
        ).resolvedMode.takeIf { resolved -> choices.any { it.value == resolved } }
            ?: StabilizationMode.OFF
    }

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
