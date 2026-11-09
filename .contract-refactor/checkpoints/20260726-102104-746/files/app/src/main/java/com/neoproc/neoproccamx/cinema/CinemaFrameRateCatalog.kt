package com.neoproc.neoproccamx.cinema

/**
 * CinemaFrameRateCatalog — Professional cinema-oriented frame rate registry.
 *
 * Contains the complete set of frame rates used in professional cinema and
 * broadcast production, including pulldown variants, shutter angle relationships,
 * and compatibility matrices for HDR and LOG recording modes.
 *
 * All frame rates are validated against industry standards:
 * - SMPTE 274M (1080p), SMPTE 296M (720p), SMPTE ST 2036-1 (4K UHD)
 * - IEC 61966-2-4 (sRGB, xvYCC), ITU-R BT.2020, ITU-R BT.709
 */
object CinemaFrameRateCatalog {

    // ── Standard Shutter Angles ───────────────────────────────────────────────

    /** Industry-standard shutter angles (degrees) */
    val standardShutterAngles = intArrayOf(15, 22, 30, 45, 60, 90, 120, 144, 172, 180, 270, 288, 360)

    /** Cinema-preferred shutter angles */
    val cinemaShutterAngles = intArrayOf(172, 180)

    // ── Frame Rate Registry ───────────────────────────────────────────────────

    val frameRates: List<CinemaFrameRate> = buildList {
        // ── Film / Cinema Standards ──────────────────────────────────────────
        add(CinemaFrameRate(
            fps = 23.976,
            displayName = "23.976p",
            exactFraction = Pair(24000, 1001),
            isCinemaStandard = true,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(23.976, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020", "P3-D65"),
            compatibleHdrModes = listOf("OFF", "NDR10+", "NIVUKX Vision"),
            smpteName = "SMPTE 274M-2008",
            description = "NTSC-derived 24p — universal cinema delivery format"
        ))
        add(CinemaFrameRate(
            fps = 24.0,
            displayName = "24p",
            exactFraction = Pair(24, 1),
            isCinemaStandard = true,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(24.0, 172.8),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020", "P3-D65", "ACES"),
            compatibleHdrModes = listOf("OFF", "NDR10+", "NIVUKX Vision"),
            smpteName = "SMPTE 274M-2008",
            description = "True 24 fps — the universal cinematic frame rate"
        ))
        add(CinemaFrameRate(
            fps = 25.0,
            displayName = "25p",
            exactFraction = Pair(25, 1),
            isCinemaStandard = true,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(25.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020", "P3-D65"),
            compatibleHdrModes = listOf("OFF", "NDR10+", "NIVUKX Vision"),
            smpteName = "SMPTE 296M",
            description = "PAL/EBU broadcast standard — ideal for European delivery"
        ))
        add(CinemaFrameRate(
            fps = 29.97,
            displayName = "29.97p",
            exactFraction = Pair(30000, 1001),
            isCinemaStandard = true,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(29.97, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020", "P3-D65"),
            compatibleHdrModes = listOf("OFF", "NDR10+", "NIVUKX Vision"),
            smpteName = "SMPTE 274M-2008",
            description = "NTSC broadcast standard — North American delivery"
        ))
        add(CinemaFrameRate(
            fps = 30.0,
            displayName = "30p",
            exactFraction = Pair(30, 1),
            isCinemaStandard = true,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(30.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020", "P3-D65"),
            compatibleHdrModes = listOf("OFF", "NDR10+", "NIVUKX Vision"),
            smpteName = "SMPTE 274M-2008",
            description = "Integer 30p — clean for streaming and online delivery"
        ))
        // ── High Frame Rate ──────────────────────────────────────────────────
        add(CinemaFrameRate(
            fps = 47.952,
            displayName = "47.952p",
            exactFraction = Pair(48000, 1001),
            isCinemaStandard = false,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(47.952, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020"),
            compatibleHdrModes = listOf("OFF", "NDR10+"),
            smpteName = "SMPTE ST 2036-1",
            description = "HFR cinematic — Hobbit/Cameron-style 2:1 slow pulldown"
        ))
        add(CinemaFrameRate(
            fps = 48.0,
            displayName = "48p",
            exactFraction = Pair(48, 1),
            isCinemaStandard = false,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(48.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020"),
            compatibleHdrModes = listOf("OFF", "NDR10+"),
            smpteName = "SMPTE ST 2036-1",
            description = "HFR cinema — James Cameron Avatar standard"
        ))
        add(CinemaFrameRate(
            fps = 50.0,
            displayName = "50p",
            exactFraction = Pair(50, 1),
            isCinemaStandard = false,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(50.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020"),
            compatibleHdrModes = listOf("OFF", "NDR10+"),
            smpteName = "SMPTE 296M",
            description = "PAL high frame rate — 2× slow to 25p or direct delivery"
        ))
        add(CinemaFrameRate(
            fps = 59.94,
            displayName = "59.94p",
            exactFraction = Pair(60000, 1001),
            isCinemaStandard = false,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(59.94, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020"),
            compatibleHdrModes = listOf("OFF", "NDR10+"),
            smpteName = "SMPTE 274M-2008",
            description = "NTSC high frame rate — 2× slow to 29.97p"
        ))
        add(CinemaFrameRate(
            fps = 60.0,
            displayName = "60p",
            exactFraction = Pair(60, 1),
            isCinemaStandard = false,
            is4KCapable = true,
            isSlowMotion = false,
            standardShutterNs = shutterNs(60.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709", "Rec.2020"),
            compatibleHdrModes = listOf("OFF", "NDR10+"),
            smpteName = "SMPTE 274M-2008",
            description = "Integer 60p — crisp sports and action"
        ))
        // ── Slow Motion ──────────────────────────────────────────────────────
        add(CinemaFrameRate(
            fps = 120.0,
            displayName = "120p",
            exactFraction = Pair(120, 1),
            isCinemaStandard = false,
            is4KCapable = false,
            isSlowMotion = true,
            slowMotionTargetFps = 24.0,
            slowMotionMultiplier = 5,
            standardShutterNs = shutterNs(120.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709"),
            compatibleHdrModes = listOf("OFF"),
            smpteName = "SMPTE RP 211",
            description = "5× slow at 24p — high-impact slow motion"
        ))
        add(CinemaFrameRate(
            fps = 240.0,
            displayName = "240p",
            exactFraction = Pair(240, 1),
            isCinemaStandard = false,
            is4KCapable = false,
            isSlowMotion = true,
            slowMotionTargetFps = 24.0,
            slowMotionMultiplier = 10,
            standardShutterNs = shutterNs(240.0, 180.0),
            compatibleColorSpaces = listOf("Rec.709"),
            compatibleHdrModes = listOf("OFF"),
            smpteName = "SMPTE RP 211",
            description = "10× slow at 24p — extreme slow motion"
        ))
    }

    // ── Index by FPS ──────────────────────────────────────────────────────────

    private val byFps: Map<Double, CinemaFrameRate> by lazy {
        frameRates.associateBy { it.fps }
    }

    private val byDisplayName: Map<String, CinemaFrameRate> by lazy {
        frameRates.associateBy { it.displayName }
    }

    // ── Query API ─────────────────────────────────────────────────────────────

    /** Look up a frame rate by exact fps value. */
    fun getFrameRate(fps: Double): CinemaFrameRate? = byFps[fps]

    /** Look up by display name (e.g. "24p", "23.976p"). */
    fun getFrameRateByName(displayName: String): CinemaFrameRate? = byDisplayName[displayName]

    /** All cinema-standard frame rates (suitable for theatrical delivery). */
    fun getCinemaFrameRates(): List<CinemaFrameRate> = frameRates.filter { it.isCinemaStandard }

    /** All frame rates capable of 4K UHD or higher capture. */
    fun get4KCapableFrameRates(): List<CinemaFrameRate> = frameRates.filter { it.is4KCapable }

    /** All slow-motion frame rates. */
    fun getSlowMotionFrameRates(): List<CinemaFrameRate> = frameRates.filter { it.isSlowMotion }

    /** Frame rates compatible with a given HDR mode. */
    fun getFrameRatesForHdrMode(hdrMode: String): List<CinemaFrameRate> =
        frameRates.filter { hdrMode in it.compatibleHdrModes }

    /** Frame rates compatible with a given color space. */
    fun getFrameRatesForColorSpace(colorSpace: String): List<CinemaFrameRate> =
        frameRates.filter { colorSpace in it.compatibleColorSpaces }

    /** Returns all frame rates. */
    fun getAllFrameRates(): List<CinemaFrameRate> = frameRates

    /**
     * Returns the nearest catalog entry to a given fps,
     * within an optional tolerance (default ±0.1%).
     */
    fun nearest(fps: Double, tolerancePct: Double = 0.1): CinemaFrameRate? {
        val tolerance = fps * tolerancePct / 100.0
        return frameRates.minByOrNull { kotlin.math.abs(it.fps - fps) }
            ?.takeIf { kotlin.math.abs(it.fps - fps) <= tolerance }
    }

    /**
     * Calculates the ideal shutter speed (exposure time in nanoseconds)
     * for a given frame rate and shutter angle, following the 180° rule.
     * @param fps Frame rate in frames per second.
     * @param angleDegrees Shutter angle in degrees (default 180°).
     */
    fun shutterNs(fps: Double, angleDegrees: Double = 180.0): Long {
        if (fps <= 0.0 || angleDegrees <= 0.0) return 0L
        return ((angleDegrees / 360.0) / fps * 1_000_000_000.0).toLong()
    }

    /**
     * Returns all valid shutter speeds in nanoseconds for a given frame rate,
     * using the catalog of standard shutter angles.
     */
    fun validShutterSpeedsNs(fps: Double): List<Long> {
        if (fps <= 0.0) return emptyList()
        return standardShutterAngles.map { angle ->
            shutterNs(fps, angle.toDouble())
        }.filter { it > 0L }
    }
}

// ── Data Model ────────────────────────────────────────────────────────────────

/**
 * A professional cinema frame rate descriptor with full metadata for
 * pipeline configuration, HDR compatibility, and shutter science.
 */
data class CinemaFrameRate(
    /** Exact frames per second (may be fractional, e.g. 23.976). */
    val fps: Double,
    /** Human-readable display name (e.g. "23.976p"). */
    val displayName: String,
    /** Exact rational representation (numerator, denominator). */
    val exactFraction: Pair<Int, Int> = Pair(fps.toInt(), 1),
    /** Whether this frame rate is a recognized cinema delivery standard. */
    val isCinemaStandard: Boolean,
    /** Whether 4K UHD or higher capture is possible at this frame rate. */
    val is4KCapable: Boolean = true,
    /** Whether this is a slow-motion capture rate. */
    val isSlowMotion: Boolean = false,
    /** Target playback fps for slow-motion modes. */
    val slowMotionTargetFps: Double = fps,
    /** Integer slow-motion multiplier (e.g. 5× for 120p→24p). */
    val slowMotionMultiplier: Int = 1,
    /** Ideal shutter duration in nanoseconds at the standard shutter angle. */
    val standardShutterNs: Long = CinemaFrameRateCatalog.shutterNs(fps, 180.0),
    /** Compatible color spaces for this frame rate. */
    val compatibleColorSpaces: List<String> = listOf("Rec.709", "Rec.2020"),
    /** Compatible HDR modes for this frame rate. */
    val compatibleHdrModes: List<String> = listOf("OFF"),
    /** SMPTE standard reference. */
    val smpteName: String = "",
    /** Human-readable description. */
    val description: String = ""
) {
    /** Integer fps rounded from the exact double value (e.g. 23 for 23.976). */
    val nominalFps: Int get() = fps.toInt()

    /** Whether this frame rate is a fractional NTSC variant. */
    val isNtscVariant: Boolean get() = exactFraction.second == 1001

    /** Returns ideal shutter for the 180° rule in seconds. */
    val halfShutterSeconds: Double get() = 1.0 / (2.0 * fps.coerceAtLeast(1.0))

    /** Returns the exact fps as a formatted fraction string. */
    val fractionString: String get() = "${exactFraction.first}/${exactFraction.second}"
}
