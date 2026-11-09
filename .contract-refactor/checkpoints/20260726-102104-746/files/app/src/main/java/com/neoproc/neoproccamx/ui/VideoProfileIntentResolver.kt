package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.camera.toAndroidSize
import com.neoproc.neoproccamx.camera.toCameraStreamSize
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

/**
 * VideoProfileIntentResolver — cinema-oriented video profile intent resolver.
 *
 * Translates high-level user intent (mode, resolution, FPS, codec preference)
 * into a concrete [ResolvedVideoProfileIntent] that can be applied to the
 * camera session and recording pipeline.
 *
 * The resolver handles:
 *  - Resolution validation against device capabilities
 *  - Codec preference (HEVC/AV1/AVC) based on mode requirements
 *  - Frame rate compatibility with HDR/LOG/RAW constraints
 *  - 10-bit output availability
 *  - LOG profile selection by cinema mode
 *  - RAW video routing for CinemaDNG workflows
 *
 * Calling this resolver is safe from any thread (pure functions, no state).
 */
class VideoProfileIntentResolver {

    // ── Primary Resolution API ────────────────────────────────────────────────

    /**
     * Resolves a [ResolvedVideoProfileIntent] from the full user intent context.
     *
     * @param preferred              Explicit intent override (used as-is if provided).
     * @param availableSizes         Camera2-reported supported output sizes.
     * @param pixelArraySize         Full sensor pixel array size.
     * @param maxRawSize             Maximum RAW output size.
     * @param fallbackSensorSize     Derived fallback sensor size.
     * @param uiMode                 Current UI app mode string.
     * @param cinemaMode             Active cinema sub-mode enum.
     * @param rawEnabled             Whether RAW video is explicitly requested.
     * @param rawSupported           Whether the device supports RAW video.
     * @param av1Supported           Whether AV1 encoding is supported.
     * @param hdrRequested           Whether HDR output is requested.
     * @param tenBitRequested        Whether 10-bit output is requested.
     * @param fps                    Target frame rate.
     * @param normalizeRecordingRequest Lambda to normalize size to landscape orientation.
     * @param resolveSupportedSize   Lambda to validate size against codec constraints.
     */
    fun resolve(
        preferred: ResolvedVideoProfileIntent? = null,
        availableSizes: List<Size> = emptyList(),
        pixelArraySize: Size = Size(1920, 1080),
        maxRawSize: Size = Size(4032, 3024),
        fallbackSensorSize: Size = Size(1920, 1080),
        uiMode: String = "VIDEO",
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawEnabled: Boolean = false,
        rawSupported: Boolean = true,
        av1Supported: Boolean = false,
        hdrRequested: Boolean = false,
        requestedHdrMode: String = "HDR10",
        tenBitRequested: Boolean = false,
        fps: Int = 25,
        normalizeRecordingRequest: (Size) -> Size = { it },
        resolveSupportedSize: (Size) -> Size = { it }
    ): ResolvedVideoProfileIntent {
        // If an explicit override is provided, validate it and return
        if (preferred != null) {
            return validateAndEnrich(preferred, fps, hdrRequested, tenBitRequested, av1Supported)
        }

        // Determine the target resolution
        val targetSize = resolveTargetSize(
            cinemaMode = cinemaMode,
            rawEnabled = rawEnabled,
            rawSupported = rawSupported,
            availableSizes = availableSizes,
            pixelArraySize = pixelArraySize,
            maxRawSize = maxRawSize,
            fallbackSensorSize = fallbackSensorSize,
            fps = fps,
            normalizeRecordingRequest = normalizeRecordingRequest,
            resolveSupportedSize = resolveSupportedSize
        )

        val effectiveRawEnabled = rawEnabled && rawSupported
        val effective10Bit = resolveEncodedTenBitIntent(
            cinemaMode = cinemaMode,
            rawEnabled = effectiveRawEnabled,
            hdrRequested = hdrRequested,
            tenBitRequested = tenBitRequested
        )

        // Determine the preferred codec for this mode
        val codec = resolveCodec(
            cinemaMode = cinemaMode,
            rawEnabled = effectiveRawEnabled,
            hdrRequested = hdrRequested,
            tenBitRequested = effective10Bit,
            av1Supported = av1Supported
        )

        // Determine effective fps (some modes cap fps)
        val effectiveFps = resolveEffectiveFps(
            requestedFps = fps,
            cinemaMode = cinemaMode,
            rawEnabled = rawEnabled,
            hdrRequested = hdrRequested
        )

        // Determine HDR mode
        val effectiveHdrMode = when {
            effectiveRawEnabled -> "OFF"
            cinemaMode == CinemaVideoMode.LOG -> "OFF"
            hdrRequested -> RecordingHdrVideoLookPolicy
                .normalizeVideoHdrMode(requestedHdrMode)
                .takeUnless { it == "OFF" }
                ?: "OFF"
            else -> "OFF"
        }

        return ResolvedVideoProfileIntent(
            targetSize = targetSize,
            codec = codec,
            fps = effectiveFps,
            is10Bit = effective10Bit,
            hdrMode = effectiveHdrMode,
            logEnabled = cinemaMode == CinemaVideoMode.LOG,
            rawEnabled = effectiveRawEnabled,
            colorSpace = resolveColorSpace(cinemaMode, effectiveHdrMode, effective10Bit)
        )
    }

    // ── Private Resolution Helpers ────────────────────────────────────────────

    /**
     * Validates an explicitly provided intent and enriches it
     * with derived fields that may have been left at defaults.
     */
    private fun validateAndEnrich(
        intent: ResolvedVideoProfileIntent,
        fps: Int,
        hdrRequested: Boolean,
        tenBitRequested: Boolean,
        av1Supported: Boolean
    ): ResolvedVideoProfileIntent {
        val effectiveFps = intent.fps.takeIf { it > 0 } ?: fps
        val effective10Bit = intent.is10Bit || tenBitRequested || hdrRequested || !intent.rawEnabled
        return intent.copy(fps = effectiveFps, is10Bit = effective10Bit)
    }

    private fun resolveEncodedTenBitIntent(
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        hdrRequested: Boolean,
        tenBitRequested: Boolean
    ): Boolean =
        when {
            rawEnabled || cinemaMode == CinemaVideoMode.RAW -> false
            hdrRequested || tenBitRequested -> true
            cinemaMode == CinemaVideoMode.LOG -> true
            else -> true
        }

    /**
     * Resolves the target recording resolution for the given cinema mode.
     */
    private fun resolveTargetSize(
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        rawSupported: Boolean,
        availableSizes: List<Size>,
        pixelArraySize: Size,
        maxRawSize: Size,
        fallbackSensorSize: Size,
        fps: Int,
        normalizeRecordingRequest: (Size) -> Size,
        resolveSupportedSize: (Size) -> Size
    ): Size {
        val preferred = VideoProfileTargetSizePolicy.resolveTargetStreamSize(
            availableSizes = availableSizes.map { it.toCameraStreamSize() },
            pixelArraySize = pixelArraySize.toCameraStreamSize(),
            maxRawSize = maxRawSize.toCameraStreamSize(),
            fallbackSensorSize = fallbackSensorSize.toCameraStreamSize(),
            rawEnabled = rawEnabled,
            rawSupported = rawSupported
        ).toAndroidSize()
        return normalizeRecordingRequest(resolveSupportedSize(preferred))
    }

    /**
     * Resolves the preferred codec for the given cinema configuration.
     */
    private fun resolveCodec(
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        hdrRequested: Boolean,
        tenBitRequested: Boolean,
        av1Supported: Boolean
    ): GpuVideoCodec = when {
        rawEnabled -> GpuVideoCodec.HEVC           // RAW: HEVC wrapper
        hdrRequested -> {
            if (av1Supported) GpuVideoCodec.AV1 else GpuVideoCodec.HEVC
        }
        tenBitRequested -> GpuVideoCodec.HEVC      // 10-bit: HEVC minimum
        cinemaMode == CinemaVideoMode.LOG -> GpuVideoCodec.HEVC
        av1Supported -> GpuVideoCodec.AV1          // Prefer AV1 for efficiency
        else -> GpuVideoCodec.HEVC
    }

    /**
     * Resolves effective fps, applying caps for modes with limited frame rate support.
     */
    private fun resolveEffectiveFps(
        requestedFps: Int,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        hdrRequested: Boolean
    ): Int {
        // RAW video cap at 60fps
        if (rawEnabled) return requestedFps.coerceAtMost(60)
        // HDR10 cap at 60fps
        if (hdrRequested && requestedFps > 60) return 60
        return requestedFps.coerceAtLeast(1)
    }

    /**
     * Resolves the delivery color space for the given configuration.
     */
    private fun resolveColorSpace(
        cinemaMode: CinemaVideoMode,
        hdrMode: String,
        tenBit: Boolean
    ): String = when {
        cinemaMode == CinemaVideoMode.RAW -> "RAW"
        cinemaMode == CinemaVideoMode.LOG -> "Rec.2020"
        hdrMode == "HLG" -> "Rec.2020-HLG"
        hdrMode == "NDR10+" -> "Rec.2020-NDR10+"
        hdrMode == "NIVUKXVISION" -> "Rec.2020-NIVUKX"
        hdrMode == "APEX_SHARP" ||
            hdrMode == "APEX_ZERO" ||
            hdrMode == "APEX_CINEMA" -> "Rec.2020-NIVUKX-APEX"
        hdrMode != "OFF" -> "Rec.2020-PQ"
        else -> "Rec.709"
    }
}

// ── Resolved Video Profile Intent Model ────────────────────────────────────────

/**
 * Concrete resolved video profile intent from user configuration.
 * All fields are validated and ready for direct application to the encoder.
 */
data class ResolvedVideoProfileIntent(
    /** Target encoder output resolution. */
    val targetSize: Size = Size(1920, 1080),
    /** Preferred encoder codec. */
    val codec: GpuVideoCodec = GpuVideoCodec.HEVC,
    /** Target frame rate. */
    val fps: Int = 25,
    /** Whether 10-bit output is required. */
    val is10Bit: Boolean = false,
    /** HDR mode string (e.g. "HDR10", "HLG", "OFF"). */
    val hdrMode: String = "OFF",
    /** Whether LOG video encoding is active. */
    val logEnabled: Boolean = false,
    /** Whether RAW video capture is active. */
    val rawEnabled: Boolean = false,
    /** Delivery color space (e.g. "Rec.709", "Rec.2020-PQ"). */
    val colorSpace: String = "Rec.709"
) {
    val isHdrActive: Boolean get() = hdrMode != "OFF"
    val isCinemaModeActive: Boolean get() = logEnabled || rawEnabled || isHdrActive
    val megapixels: Float
        get() = (targetSize.width.toLong() * targetSize.height.toLong()) / 1_000_000f

    companion object {
        /** Default standard-quality profile. */
        val standard: ResolvedVideoProfileIntent get() = ResolvedVideoProfileIntent()

        /** Default 4K high-end profile. */
        val highEnd4K: ResolvedVideoProfileIntent get() = ResolvedVideoProfileIntent(
            targetSize = Size(3840, 2160),
            codec = GpuVideoCodec.HEVC,
            fps = 25,
            is10Bit = true,
            colorSpace = "Rec.2020"
        )
    }
}
