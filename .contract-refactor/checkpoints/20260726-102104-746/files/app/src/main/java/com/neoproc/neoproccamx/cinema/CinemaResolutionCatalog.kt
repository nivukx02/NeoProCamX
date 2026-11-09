package com.neoproc.neoproccamx.cinema

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * CinemaResolutionCatalog — Professional cinema resolution registry.
 *
 * Defines the complete resolution matrix used by NeoProCamX, covering:
 * - Theatrical digital cinema (DCI) standards
 * - Consumer HDR delivery (UHD, 4K)
 * - Professional streaming and broadcast formats
 * - Anamorphic and widescreen origination formats
 *
 * Each entry carries sensor coverage information, recommended bit depths,
 * and HDR/LOG mode compatibility metadata. All resolutions follow
 * SMPTE DC28, ISO 12234-2 (RAW), and ITU-R BT.2020 specifications.
 */
object CinemaResolutionCatalog {

    // ── Resolution Registry ───────────────────────────────────────────────────

    val resolutions: List<CinemaResolution> = buildList {

        // ── Native / Full Sensor ─────────────────────────────────────────────
        add(CinemaResolution(
            name = "NATIVE SENSOR",
            displayName = "Native",
            size = Size(0, 0), // 0,0 sentinel = full sensor readout
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "NATIVE",
            bitDepth = 10,
            isSensorNative = true,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(25, 24),
            deliveryFormats = listOf("RAW", "HEVC_10BIT", "YUV_420_888"),
            standardReference = "ISO 12234-2",
            description = "Full sensor readout — maximum spatial information"
        ))

        // ── Sensor Max Open Gate (8192x6144) ───────────────────────────────
        add(CinemaResolution(
            name = "50MP NATIVE",
            displayName = "Sensor Max 8192x6144",
            size = Size(8192, 6144),
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "4:3",
            bitDepth = 10,
            isSensorNative = true,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(24, 25),
            deliveryFormats = listOf("RAW", "HEVC_10BIT"),
            standardReference = "ISO 12234-2",
            description = "Sensor-native open gate 8192x6144 — zero downscale"
        ))

        // ── Sensor Max Open Gate (8000x6000) ───────────────────────────────
        add(CinemaResolution(
            name = "48MP NATIVE",
            displayName = "Sensor Max 8000x6000",
            size = Size(8000, 6000),
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "4:3",
            bitDepth = 10,
            isSensorNative = true,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(24, 25),
            deliveryFormats = listOf("RAW", "HEVC_10BIT"),
            standardReference = "ISO 12234-2",
            description = "Sensor-native open gate 8000x6000 — zero downscale"
        ))

        // ── 8K UHD ────────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "8K",
            displayName = "8K UHD",
            size = Size(7680, 4320),
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "16:9",
            bitDepth = 10,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(24, 25),
            deliveryFormats = listOf("HEVC_10BIT", "AV1_10BIT", "RAW"),
            standardReference = "SMPTE ST 2036-1 / ITU-R BT.2020",
            description = "8K UHD — ultimate consumer delivery format"
        ))

        // ── 6K Cinema ─────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "6K",
            displayName = "6K Cinema",
            size = Size(5760, 4320),
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "4:3",
            bitDepth = 10,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(24, 25),
            deliveryFormats = listOf("HEVC_10BIT", "RAW"),
            standardReference = "RED DSMC2",
            description = "6K cinema — professional origination format"
        ))
        add(CinemaResolution(
            name = "5K",
            displayName = "5K",
            size = Size(5120, 2880),
            tier = ResolutionTier.ULTRA,
            aspectRatioLabel = "16:9",
            bitDepth = 10,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(25, 24),
            deliveryFormats = listOf("HEVC_10BIT", "RAW"),
            standardReference = "SMPTE ST 2036-1",
            description = "5K — optimal for 4K downscaled output with headroom"
        ))

        // ── 4K DCI (Digital Cinema) ───────────────────────────────────────────
        add(CinemaResolution(
            name = "4K DCI",
            displayName = "4K DCI",
            size = Size(4096, 2160),
            tier = ResolutionTier.CINEMA,
            aspectRatioLabel = "1.90:1",
            bitDepth = 12,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(25, 24, 48, 60),
            deliveryFormats = listOf("HEVC_12BIT", "HEVC_10BIT", "RAW"),
            standardReference = "SMPTE DC28 / ISDCF",
            description = "DCI 4K — theatrical digital cinema standard"
        ))

        // ── 4K UHD ───────────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "4K",
            displayName = "4K UHD",
            size = Size(3840, 2160),
            tier = ResolutionTier.CINEMA,
            aspectRatioLabel = "16:9",
            bitDepth = 10,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(25, 24, 50, 60),
            deliveryFormats = listOf("HEVC_10BIT", "AV1_10BIT", "RAW"),
            standardReference = "ITU-R BT.2020 / SMPTE ST 2036-1",
            description = "4K UHD — consumer and streaming delivery standard"
        ))

        // ── 4K Anamorphic (3:2 full-frame) ───────────────────────────────────
        add(CinemaResolution(
            name = "4K FULL",
            displayName = "4K Full Frame",
            size = Size(4096, 3072),
            tier = ResolutionTier.CINEMA,
            aspectRatioLabel = "4:3",
            bitDepth = 10,
            supportsRaw = true,
            supportsLog = true,
            supportsHdr = false,
            supports10Bit = true,
            maxFpsAtTier = listOf(24, 25),
            deliveryFormats = listOf("HEVC_10BIT", "RAW"),
            standardReference = "ISO 12234-2",
            description = "4K full-frame — for anamorphic lens workflows"
        ))

        // ── 2.7K (DSLR-style) ────────────────────────────────────────────────
        add(CinemaResolution(
            name = "2.7K",
            displayName = "2.7K",
            size = Size(2704, 1520),
            tier = ResolutionTier.PROFESSIONAL,
            aspectRatioLabel = "16:9",
            bitDepth = 10,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(60, 50, 25, 24),
            deliveryFormats = listOf("HEVC_10BIT", "AVC"),
            standardReference = "SMPTE 296M",
            description = "2.7K — balanced resolution and frame rate capability"
        ))

        // ── 2K DCI ────────────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "2K",
            displayName = "2K DCI",
            size = Size(2048, 1080),
            tier = ResolutionTier.PROFESSIONAL,
            aspectRatioLabel = "1.90:1",
            bitDepth = 10,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(60, 50, 25, 24, 120),
            deliveryFormats = listOf("HEVC_10BIT", "AVC"),
            standardReference = "SMPTE DC28",
            description = "2K DCI — theatrical standard for DCP creation"
        ))

        // ── 1080p Full HD ─────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "1080p",
            displayName = "1080p FHD",
            size = Size(1920, 1080),
            tier = ResolutionTier.STANDARD,
            aspectRatioLabel = "16:9",
            bitDepth = 8,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(120, 60, 50, 25, 24),
            deliveryFormats = listOf("HEVC_10BIT", "HEVC", "AVC"),
            standardReference = "SMPTE 274M",
            description = "1080p FHD — universal broadcast and streaming format"
        ))

        // ── 1K / 720p ────────────────────────────────────────────────────────
        add(CinemaResolution(
            name = "1K",
            displayName = "1K HD",
            size = Size(1280, 720),
            tier = ResolutionTier.STANDARD,
            aspectRatioLabel = "16:9",
            bitDepth = 8,
            supportsLog = false,
            supportsHdr = false,
            supports10Bit = false,
            maxFpsAtTier = listOf(240, 120, 60, 50, 25),
            deliveryFormats = listOf("HEVC", "AVC"),
            standardReference = "SMPTE 296M",
            description = "1K HD — high frame rate capture and proxy"
        ))

        // ── Vertical / Social Media Formats ──────────────────────────────────
        add(CinemaResolution(
            name = "4K VERTICAL",
            displayName = "4K Vertical",
            size = Size(2160, 3840),
            tier = ResolutionTier.CINEMA,
            aspectRatioLabel = "9:16",
            bitDepth = 10,
            supportsLog = true,
            supportsHdr = true,
            supports10Bit = true,
            maxFpsAtTier = listOf(25, 24),
            deliveryFormats = listOf("HEVC_10BIT"),
            standardReference = "IAB Tech Lab VAST",
            description = "4K vertical — native for short-form / Reels / TikTok"
        ))
        add(CinemaResolution(
            name = "1080p VERTICAL",
            displayName = "1080p Vertical",
            size = Size(1080, 1920),
            tier = ResolutionTier.STANDARD,
            aspectRatioLabel = "9:16",
            bitDepth = 8,
            supportsLog = false,
            supportsHdr = false,
            supports10Bit = false,
            maxFpsAtTier = listOf(60, 25),
            deliveryFormats = listOf("HEVC", "AVC"),
            standardReference = "IAB Tech Lab VAST",
            description = "1080p vertical — social media and story delivery"
        ))
    }

    // ── Indexes ───────────────────────────────────────────────────────────────

    private val byName: Map<String, CinemaResolution> by lazy {
        resolutions.associateBy { it.name }
    }

    private val bySize: Map<Pair<Int, Int>, CinemaResolution> by lazy {
        resolutions.filter { it.size != Size(0, 0) }
            .associateBy { Pair(it.size.width, it.size.height) }
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    fun getResolution(name: String): CinemaResolution? = byName[name]

    fun forResolution(width: Int, height: Int): CinemaResolution? =
        bySize[Pair(width, height)]

    /** All resolutions supporting <= maxMegapixels, including native sensor. */
    fun getResolutionsForMegapixels(maxMegapixels: Float): List<CinemaResolution> =
        resolutions.filter { it.megapixels <= maxMegapixels || it.isSensorNative }

    /** All resolutions supporting LOG modes. */
    fun getLogCapableResolutions(): List<CinemaResolution> =
        resolutions.filter { it.supportsLog }

    /** All resolutions supporting HDR modes. */
    fun getHdrCapableResolutions(): List<CinemaResolution> =
        resolutions.filter { it.supportsHdr }

    /** All resolutions supporting RAW capture. */
    fun getRawCapableResolutions(): List<CinemaResolution> =
        resolutions.filter { it.supportsRaw }

    /** All resolutions supporting 10-bit color depth. */
    fun get10BitResolutions(): List<CinemaResolution> =
        resolutions.filter { it.supports10Bit }

    /** Highest-quality non-native resolution. */
    fun getHighestResolution(): CinemaResolution =
        resolutions.filter { !it.isSensorNative && it.size != Size(0, 0) }
            .maxByOrNull { it.megapixels }
            ?: resolutions.first()

    /** Returns only resolutions within the device's current capability tier. */
    fun getResolutionsForTier(tier: ResolutionTier): List<CinemaResolution> =
        resolutions.filter { it.tier <= tier }

    /** Resolutions appropriate for the strict high-end mode. */
    fun getHighEndResolutions(): List<CinemaResolution> {
        return if (HighEndQualityPolicy.strictHighEndOnly) {
            resolutions.filter { it.tier <= ResolutionTier.CINEMA }
        } else {
            resolutions.filter { it.tier <= ResolutionTier.PROFESSIONAL }
        }
    }

    /**
     * Finds the closest matching resolution for a given long edge.
     */
    fun forLongEdge(longEdge: Int): CinemaResolution? =
        resolutions.filter { !it.isSensorNative }
            .minByOrNull { kotlin.math.abs(it.size.width - longEdge) }
}

// ── Resolution Tier ───────────────────────────────────────────────────────────

/**
 * Ordinal-based tier: ULTRA > CINEMA > PROFESSIONAL > STANDARD
 * Used to filter resolutions by device capability.
 */
enum class ResolutionTier {
    STANDARD,
    PROFESSIONAL,
    CINEMA,
    ULTRA
}

// ── Resolution Data Model ─────────────────────────────────────────────────────

data class CinemaResolution(
    /** Internal catalog identifier (e.g. "4K DCI"). */
    val name: String,
    /** Human-readable label shown in the UI (e.g. "4K DCI"). */
    val displayName: String,
    /** Pixel dimensions. Size(0,0) indicates full native sensor readout. */
    val size: Size,
    /** Quality tier for capability filtering. */
    val tier: ResolutionTier,
    /** Aspect ratio label (e.g. "16:9", "1.90:1"). */
    val aspectRatioLabel: String,
    /** Native bit depth for this format. */
    val bitDepth: Int,
    /** Whether this represents a native sensor readout. */
    val isSensorNative: Boolean = false,
    /** Whether RAW capture is supported. */
    val supportsRaw: Boolean = false,
    /** Whether LOG color profiles are supported. */
    val supportsLog: Boolean = false,
    /** Whether HDR modes are supported. */
    val supportsHdr: Boolean = false,
    /** Whether 10-bit color is supported. */
    val supports10Bit: Boolean = false,
    /** Maximum supported frame rates (ordered by preference). */
    val maxFpsAtTier: List<Int> = listOf(25),
    /** Available delivery codec/format combinations. */
    val deliveryFormats: List<String> = listOf("AVC"),
    /** Industry standard reference. */
    val standardReference: String = "",
    /** Human-readable description of this format's use case. */
    val description: String = ""
) {
    val width: Int get() = size.width
    val height: Int get() = size.height

    val megapixels: Float
        get() = if (isSensorNative) Float.MAX_VALUE
        else (size.width.toLong() * size.height.toLong()) / 1_000_000f

    val aspectRatio: Float
        get() = if (size.width > 0 && size.height > 0) size.width.toFloat() / size.height else 1.777f

    /** Returns max fps supported at this resolution. */
    val maxFps: Int get() = maxFpsAtTier.maxOrNull() ?: 25

    /** Whether this is a vertical/portrait format. */
    val isVertical: Boolean get() = size.height > size.width

    /** Primary delivery format — first entry from [deliveryFormats]. */
    val deliveryFormat: String get() = deliveryFormats.firstOrNull() ?: "AVC"

    /** Returns a short human-readable summary. */
    val shortSummary: String
        get() = if (isSensorNative) "NATIVE" else "${size.width}×${size.height} (${megapixels.toInt()}MP)"
}
