package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry

/**
 * AllIntraHevcPolicy — Configures capability-validated HEVC High-Profile All-Intra encoding.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * "ALL-INTRA" EXPLAINED
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Standard video encoding uses Group-of-Pictures (GOP): I-frame (keyframe) followed by
 * P- and B-frames that reference previous frames.  All-Intra means *every* frame is an
 * I-frame.  Benefits:
 *
 *   - Frame-accurate seeking and cutting (no dependency chain).
 *   - Maximum resilience to corruption (one bad frame doesn't trash the GOP).
 *   - Easier colour grading in post (no inter-frame compression artefacts).
 *
 * Cost: ~5–10× file size vs. typical IPB encoding.  Use only when storage is abundant
 * and editing flexibility is paramount (cinema production).
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * BITRATE TIERS
 * ═════════════════════════════════════════════════════════════════════════════════════
 *   CINEMA_MASTER    180 Mbps  — legacy cinema-master target, not a hardware ceiling
 *   PRO_ALL_INTRA    120 Mbps  — high-end mobile all-intra request
 *   STANDARD         100 Mbps  — 4K 10-bit high-detail acquisition
 *   ECONOMY           80 Mbps  — 1080p/2K high-quality proxy
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * HARDWARE SUPPORT
 * ═════════════════════════════════════════════════════════════════════════════════════
 * All-Intra HEVC requires the encoder to support:
 *   - Profile: Main, Main10, or Main 4:2:2 10-bit (high tier)
 *   - Level: 5.1 or higher for 4K
 *   - Bitrate mode: CBR (required for All-Intra to avoid VBR oscillation)
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FAIL-SAFE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * If the encoder does not advertise All-Intra capability, the policy returns null
 * and the caller should fall back to standard IPB encoding with smart I-frame placement.
 */
object AllIntraHevcPolicy {

    private const val TAG = "AllIntraHevcPolicy"

    /** Maximum bitrate for flagship HEVC All-Intra tier (bits per second). */
    const val BITRATE_CINEMA_MASTER = 180_000_000

    const val BITRATE_PRO_ALL_INTRA = 120_000_000
    const val BITRATE_STANDARD = 100_000_000
    const val BITRATE_ECONOMY = 80_000_000

    data class AllIntraConfig(
        val mime: String = "video/hevc",
        val bitrate: Int,
        val profile: Int,
        val level: Int?,
        val frameRate: Int,
        val iFrameIntervalSeconds: Float = 1f,  // All-Intra: every frame is I-frame
        val colorStandard: Int = MediaFormat.COLOR_STANDARD_BT709,
        val colorTransfer: Int = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        val colorRange: Int = MediaFormat.COLOR_RANGE_FULL,
        val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    )

    /**
     * Probes the device's HEVC encoder for All-Intra capability.
     * @return [AllIntraConfig] if supported, null otherwise.
     */
    fun probe(
        width: Int,
        height: Int,
        frameRate: Int,
        tenBit: Boolean = false,
        preferredBitrate: Int = BITRATE_PRO_ALL_INTRA,
        codecInfo: MediaCodecInfo? = null
    ): AllIntraConfig? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "All-Intra not supported: API < 21")
            return null
        }

        val selectedCodecInfo = codecInfo ?: try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info ->
                    info.isEncoder && info.supportedTypes.contains("video/hevc")
                }
        } catch (t: Throwable) {
            Log.w(TAG, "Codec enumeration failed", t)
            return null
        } ?: run {
            Log.d(TAG, "No HEVC encoder found")
            return null
        }

        val capabilities = try {
            selectedCodecInfo.getCapabilitiesForType("video/hevc")
        } catch (t: Throwable) {
            Log.w(TAG, "Capabilities query failed", t)
            return null
        }

        // Check profile/level support
        val profileLevels = capabilities.profileLevels
        if (profileLevels == null || profileLevels.isEmpty()) {
            Log.d(TAG, "No profile levels available")
            return null
        }
        
        val profileLevel = profileLevels.firstOrNull { pl ->
            if (tenBit) {
                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            } else {
                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            }
        } ?: run {
            Log.d(TAG, "HEVC ${if (tenBit) "Main10" else "Main"} profile not supported")
            return null
        }

        // Bitrate mode check
        val encoderCaps = capabilities.encoderCapabilities
        if (encoderCaps == null || !encoderCaps.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )) {
            Log.d(TAG, "CBR mode not supported (required for All-Intra)")
            return null
        }

        // Calculate required level
        val requiredLevel = computeLevel(width, height, frameRate)
        if (profileLevel.level < requiredLevel) {
            Log.d(TAG, "HEVC level ${profileLevel.level} < required $requiredLevel")
            return null
        }

        val videoCapabilities = capabilities.videoCapabilities ?: run {
            Log.d(TAG, "No video capabilities available")
            return null
        }
        val sizeAndRateSupported = runCatching {
            videoCapabilities.areSizeAndRateSupported(width, height, frameRate.toDouble())
        }.getOrDefault(false)
        if (!sizeAndRateSupported) {
            Log.d(TAG, "HEVC encoder does not support ${width}x${height}@${frameRate}fps")
            return null
        }
        val bitrateRange = videoCapabilities.bitrateRange
        val effectiveBitrate = resolveEffectiveBitrate(
            preferredBitrate = preferredBitrate,
            capabilityLowerBound = bitrateRange.lower,
            capabilityUpperBound = bitrateRange.upper
        )

        RuntimeStageTelemetry.recordDuration(
            tag = TAG,
            stage = "probe",
            durationMs = 1L,
            slowThresholdMs = 5L
        )

        return AllIntraConfig(
            bitrate = effectiveBitrate,
            profile = profileLevel.profile,
            level = profileLevel.level,
            frameRate = frameRate
        )
    }

    /**
     * Applies All-Intra configuration to a [MediaFormat].
     * Returns true if the format was successfully configured.
     *
     * All-Intra signalling truthfulness:
     * Per Android documentation for [MediaFormat.KEY_I_FRAME_INTERVAL]:
     *   * value `0`  → every frame is an I-frame (this is the all-intra contract);
     *   * value `>= 1` → seconds between key frames (e.g. 1 ≈ 1-second GOP at FPS frames per GOP);
     *   * value `< 0` → only the first frame is a key frame.
     *
     * Furthermore, since API level 25 (`N_MR1`), `KEY_I_FRAME_INTERVAL` is
     * float-typed; calling `setInteger` on this key on newer platforms writes
     * the wrong typed slot and the encoder may silently fall back to a
     * default GOP. We gate the API level explicitly here and write the value
     * `0` (or `0f`) so the encoder honestly receives an all-intra request.
     */
    internal fun applyToFormat(
        format: MediaFormat,
        config: AllIntraConfig,
        colorEncoding: RecordingColorEncodingConfig? = null
    ): Boolean {
        return try {
            val resolvedColorStandard = colorEncoding?.colorStandard ?: config.colorStandard
            val resolvedColorTransfer = colorEncoding?.colorTransfer ?: config.colorTransfer
            val resolvedColorRange = colorEncoding?.colorRange ?: config.colorRange
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            // ─── ALL-INTRA SIGNAL ──────────────────────────────────────────
            // The all-intra contract is `KEY_I_FRAME_INTERVAL = 0`, NOT 1.
            // A value of 1 means "1-second GOP" (≈ FPS frames between
            // keyframes); the previous code emitted that and silently
            // produced standard IPB streams while claiming all-intra.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                format.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0f)
            } else {
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, resolvedColorStandard)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, resolvedColorTransfer)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, resolvedColorRange)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode)
            format.setInteger(MediaFormat.KEY_PROFILE, config.profile)
            if (config.level != null) {
                format.setInteger(MediaFormat.KEY_LEVEL, config.level)
            }
            Log.i(
                TAG,
                "Configured All-Intra HEVC: ${config.bitrate / 1_000_000}Mbps " +
                    "profile=${config.profile} colorStandard=$resolvedColorStandard " +
                    "colorTransfer=$resolvedColorTransfer colorRange=$resolvedColorRange"
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply All-Intra config", t)
            false
        }
    }

    internal fun resolveEffectiveBitrate(
        preferredBitrate: Int,
        capabilityLowerBound: Int,
        capabilityUpperBound: Int
    ): Int {
        val validatedLower = capabilityLowerBound.coerceAtLeast(1)
        val validatedUpper = capabilityUpperBound.coerceAtLeast(validatedLower)
        val policyFloor = BITRATE_ECONOMY
            .coerceAtMost(validatedUpper)
            .coerceAtLeast(validatedLower)
        return preferredBitrate.coerceIn(policyFloor, validatedUpper)
    }

    internal fun computeLevel(width: Int, height: Int, fps: Int): Int {
        // HEVC level calculation (simplified)
        // Level 5: 4K@30, Level 5.1: 4K@60, Level 6.1: 8K@60
        // Use Long: 8K@60 luma rate (8640*4860*60 ≈ 2.52e9) overflows Int and wraps negative,
        // which previously collapsed to the lowest level and defeated the capability gate.
        val rate = width.toLong() * height.toLong() * fps.coerceAtLeast(1).toLong()
        return when {
            rate > 3840L * 2160L * 60L -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61
            rate > 3840L * 2160L * 30L -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51
            else -> MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5
        }
    }

    // ── s6-f03: AV1 all-intra parity (CP70), HEVC Main12 reach (CP71), cap-tracking mastering ──
    // bitrate. All ADD-ONLY: the live probe()/applyToFormat()/resolveEffectiveBitrate()/computeLevel()
    // paths and AllIntraConfig defaults are untouched, so an unrequested recording is byte-identical.

    /**
     * AV1 all-intra level by luma sample rate, mirroring
     * `RecordingVideoEncoderPlanResolver.resolveAv1Level` so the all-intra config picks the same
     * conformant AV1 level the plan resolver would. Pure Long math (no Android runtime call).
     */
    internal fun computeAv1Level(width: Int, height: Int, fps: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val rate = pixels * fps.coerceAtLeast(1).toLong()
        return when {
            rate > 7680L * 4320L * 60L -> MediaCodecInfo.CodecProfileLevel.AV1Level7
            rate > 7680L * 4320L * 30L -> MediaCodecInfo.CodecProfileLevel.AV1Level62
            pixels >= 7680L * 4320L -> MediaCodecInfo.CodecProfileLevel.AV1Level6
            rate > 3840L * 2160L * 30L -> MediaCodecInfo.CodecProfileLevel.AV1Level51
            pixels >= 3840L * 2160L -> MediaCodecInfo.CodecProfileLevel.AV1Level5
            rate > 1920L * 1080L * 30L -> MediaCodecInfo.CodecProfileLevel.AV1Level41
            pixels >= 1920L * 1080L -> MediaCodecInfo.CodecProfileLevel.AV1Level4
            else -> MediaCodecInfo.CodecProfileLevel.AV1Level31
        }
    }

    /**
     * Pure HEVC all-intra profile selector (CP71). Targets Main12 ONLY when a 12-bit request is
     * paired with a real advertised `hevcMain12Profile`; otherwise it falls back honestly to Main10
     * (10-bit) or Main (8-bit). Never claims 12-bit without an advertised profile.
     */
    fun resolveAllIntraHevcProfile(
        tenBit: Boolean,
        twelveBit: Boolean,
        hevcMain12Profile: Int?
    ): Int =
        when {
            twelveBit && hevcMain12Profile != null -> hevcMain12Profile
            tenBit -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        }

    /**
     * Pure AV1 all-intra config builder (CP70). Returns the intended AV1 all-intra
     * [AllIntraConfig] (CBR, mime video/av01, conformant AV1 level) or null for an honest IPB
     * fallback. Honest by construction:
     *  - a 10-bit request without an advertised `av1Main10Profile` returns null (no silent 8-bit downgrade);
     *  - an 8-bit request without an advertised `av1MainProfile` returns null;
     *  - a 12-bit request returns null — AV1 Main is 8/10-bit only, so 12-bit AV1 is NOT_AVAILABLE.
     * Device-level support validation (the encoder truly honoring CBR/level/size + the bitstream
     * being P/B-slice-free) is the caller's DEVICE-GATED step, like [probe]. The bitrate must still
     * be funnelled through [resolveEffectiveBitrate] for the device-cap clamp.
     */
    fun allIntraAv1Config(
        width: Int,
        height: Int,
        frameRate: Int,
        tenBit: Boolean = false,
        twelveBit: Boolean = false,
        preferredBitrate: Int = BITRATE_PRO_ALL_INTRA,
        av1MainProfile: Int? = null,
        av1Main10Profile: Int? = null
    ): AllIntraConfig? {
        if (twelveBit) return null
        val profile = if (tenBit) {
            av1Main10Profile ?: return null
        } else {
            av1MainProfile ?: return null
        }
        return AllIntraConfig(
            mime = "video/av01",
            bitrate = preferredBitrate,
            profile = profile,
            level = computeAv1Level(width, height, frameRate),
            frameRate = frameRate,
            bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )
    }

    /**
     * Cap-tracking mastering bitrate (CP70/CP71). When `climbTowardFloor` is enabled (NEW opt-in,
     * default off), lets the requested tier climb toward the live `HighEndQualityPolicy`
     * mastering floor (`strictFloorBps`); otherwise returns the requested tier unchanged
     * (default == today). Saturates at `Int.MAX_VALUE` for floors beyond Int range (never wraps
     * negative). Over-claim is impossible: the result is still clamped to `BitrateRange.upper` by
     * [resolveEffectiveBitrate].
     */
    fun masteringPreferredBitrate(
        requestedTierBps: Int,
        strictFloorBps: Long,
        climbTowardFloor: Boolean
    ): Int {
        if (!climbTowardFloor) return requestedTierBps
        val climbed = maxOf(requestedTierBps.toLong(), strictFloorBps.coerceAtLeast(0L))
        return climbed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
