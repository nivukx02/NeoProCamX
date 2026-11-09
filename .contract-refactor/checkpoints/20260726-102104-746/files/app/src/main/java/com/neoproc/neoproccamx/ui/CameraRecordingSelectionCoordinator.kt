package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import com.neoproc.neoproccamx.recording.RecordingCapabilityPolicy
import com.neoproc.neoproccamx.recording.RecordingSelectionPolicy
import com.neoproc.neoproccamx.recording.ResolvedRecordingSelection
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CameraRecordingSelectionCoordinator — cinema-oriented recording configuration resolver.
 *
 * Produces an authoritative [ResolvedRecordingSelection] for the active recording
 * session by combining:
 *
 *  1. User-requested resolution and codec preferences
 *  2. Hardware capability constraints (supported sizes, codec tiers)
 *  3. Cinema mode restrictions (RAW, LOG, HDR, 10-bit)
 *  4. Aspect ratio preservation from the active preview framing
 *  5. Strict high-end policy overrides
 *
 * The coordinator is stateless; all inputs are provided per-call.
 * This ensures safe use across ViewModel state updates and configuration changes.
 */
object CameraRecordingSelectionCoordinator {

    internal data class MaximumSensorPreferredDimensions(
        val width: Int,
        val height: Int
    ) {
        fun toSize(): Size = Size(width, height)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Maximum long edge permitted for RAW video to prevent encoder stalls. */
    private const val RAW_MODE_MAX_LONG_EDGE = 16384

    /** Minimum long edge for high-end mode (prevents quality regression). */
    private const val HIGH_END_MIN_LONG_EDGE = 1280

    /** Target aspect ratio for standard 16:9 cinema delivery. */
    private const val ASPECT_16_9 = 16f / 9f

    /** Real-time high-end encoded video budget: full 4:3 4K is sustained by the 25 fps processed tier. */
    private const val LIVE_VIDEO_SUSTAINABLE_MAX_LONG_EDGE = 4096
    private const val LIVE_VIDEO_SUSTAINABLE_MAX_AREA = 4096L * 3072L

    /** Tolerance for aspect ratio matching (within 5%). */
    private const val ASPECT_MATCH_TOLERANCE = 0.05f
    private const val SELECTED_ASPECT_MATCH_TOLERANCE = 0.02f

    // ── Core Resolution ───────────────────────────────────────────────────────

    /**
     * Resolves the optimal [ResolvedRecordingSelection] from the given request.
     *
     * Resolution pipeline:
     *  1. Normalize the preferred size (landscape orientation).
     *  2. Detect RAW/LOG/HDR mode requirements.
     *  3. Apply mode-specific size constraints.
     *  4. Build a codec candidate priority list.
     *  5. Delegate to [RecordingSelectionPolicy] for final matching.
     *
     * @param request Full recording selection context.
     */
    internal fun resolve(request: CameraRecordingSelectionRequest): ResolvedRecordingSelection {
        val normalizedPreferred = request.normalizeRecordingRequest(request.preferred)
        val rawRequested = request.rawEnabled || request.cinemaMode == CinemaVideoMode.RAW
        val logEnabled = request.cinemaMode == CinemaVideoMode.LOG
        val rawModeActive = rawRequested && request.rawSupported
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

        // Resolve available sizes for the mode
        val rawLimitedSizes = if (rawModeActive) {
            rawModeAvailableSizes(request.availableSizes)
        } else emptyList()

        val modeAwareAvailableSizes = when {
            rawModeActive && rawLimitedSizes.isNotEmpty() -> rawLimitedSizes
            // Full-sensor readout is resolved later by RecordingSourceResolutionPolicy.
            // The recording selector must stay anchored to stream-backed video outputs
            // and never synthesize a camera record size from pixel-array metadata.
            strictHighEnd -> filterHighEndSizes(request.availableSizes)
            else -> request.availableSizes
        }

        val maximumSensorMode = shouldUseMaximumSensorPreferredSize(
            strictHighEnd = strictHighEnd,
            uiMode = request.uiMode,
            rawModeActive = rawModeActive
        )

        val aspectAwarePreferred = resolveSelectedAspectPreferredSize(
            preferred = normalizedPreferred,
            availableSizes = modeAwareAvailableSizes,
            selectedOutputAspectRatio = request.selectedOutputAspectRatio,
            uiMode = request.uiMode,
            rawModeActive = rawModeActive
        )

        val modeAwarePreferred = when {
            maximumSensorMode -> maximumSensorPreferredSize(
                requested = aspectAwarePreferred,
                availableSizes = modeAwareAvailableSizes,
                pixelArraySize = request.pixelArraySize,
                maxRawSize = request.maxRawSize,
                fallbackSensorSize = request.fallbackSensorSize,
                favorFullSensor = rawModeActive
            )
            rawModeActive -> clampPreferredForRawMode(aspectAwarePreferred, rawLimitedSizes)
            strictHighEnd -> capPreferredForSustainableLiveVideo(
                preferred = enforceHighEndMinimum(aspectAwarePreferred),
                availableSizes = modeAwareAvailableSizes
            )
            else -> aspectAwarePreferred
        }

        // Resolve session plan (codec/capability tier)
        val sessionPlan = RecordingCapabilityPolicy.resolveSessionPlan(
            requestedCodec = RecordingCapabilityPolicy.toVideoCodecType(request.preferredCodec),
            av1Supported = request.av1Supported,
            hdrRequested = request.hdrRequested,
            rawRequested = rawRequested,
            rawSupported = request.rawSupported,
            logEnabled = logEnabled,
            tenBitRequested = request.tenBitRequested
        )

        // Build codec fallback chain
        val codecCandidates = buildCodecCandidates(
            preferredCodec = sessionPlan.codec,
            av1Supported = request.av1Supported,
            hdrRequested = request.hdrRequested,
            logEnabled = logEnabled,
            rawRequested = rawRequested,
            strictHighEnd = strictHighEnd,
            allowAvcFallback = !request.hdrRequested &&
                !request.tenBitRequested &&
                !rawRequested &&
                !logEnabled
        )

        // Validate the effective size list
        val effectiveSizes = modeAwareAvailableSizes.ifEmpty {
            listOf(modeAwarePreferred)
        }

        // Validate minimum size in high-end mode
        val finalPreferred = if (strictHighEnd && !rawModeActive) {
            ensureMinimumQuality(modeAwarePreferred, effectiveSizes)
        } else {
            modeAwarePreferred
        }

        return RecordingSelectionPolicy.resolve(
            requestedSize = finalPreferred,
            fps = request.fps.coerceAtLeast(1),
            cameraSupportedSizes = effectiveSizes,
            preferredCodec = sessionPlan.codec.name,
            resolveSupportedSize = { size, codec, fps ->
                request.resolveSupportedSize(size, codec.toGpuVideoCodec(), fps)
            }
        )
    }

    // ── Size Management ───────────────────────────────────────────────────────

    /**
     * Filters available sizes to those compatible with RAW video recording.
     * Sorted by area (descending) to prefer highest available RAW resolution.
     */
    private fun rawModeAvailableSizes(availableSizes: List<Size>): List<Size> =
        availableSizes
            .map(RecordingSelectionPolicy::normalizeForOrientation)
            .distinctBy { "${it.width}x${it.height}" }
            .filter { size -> size.width <= RAW_MODE_MAX_LONG_EDGE }
            .sortedByDescending { size -> size.width.toLong() * size.height.toLong() }

    private fun resolveSelectedAspectPreferredSize(
        preferred: Size,
        availableSizes: List<Size>,
        selectedOutputAspectRatio: Float?,
        uiMode: AppMode,
        rawModeActive: Boolean
    ): Size = resolveSelectedAspectPreferredDimensions(
        preferredWidth = preferred.width,
        preferredHeight = preferred.height,
        availableSizes = availableSizes.map { MaximumSensorPreferredDimensions(it.width, it.height) },
        selectedOutputAspectRatio = selectedOutputAspectRatio,
        uiMode = uiMode,
        rawModeActive = rawModeActive
    ).toSize()

    internal fun resolveSelectedAspectPreferredDimensions(
        preferredWidth: Int,
        preferredHeight: Int,
        availableSizes: List<MaximumSensorPreferredDimensions>,
        selectedOutputAspectRatio: Float?,
        uiMode: AppMode,
        rawModeActive: Boolean
    ): MaximumSensorPreferredDimensions {
        val preferred = normalizeDimensions(MaximumSensorPreferredDimensions(preferredWidth, preferredHeight))
        val targetAspect = selectedOutputAspectRatio
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let(SensorCropEngine::normalizeLongShortRatio)
            ?: return preferred
        if (uiMode != AppMode.VIDEO || rawModeActive) return preferred
        val preferredAspect = preferred.width.toFloat() / preferred.height.coerceAtLeast(1).toFloat()
        if (abs(preferredAspect - targetAspect) <= SELECTED_ASPECT_MATCH_TOLERANCE) return preferred

        val preferredArea = preferred.width.toLong() * preferred.height.toLong()
        return availableSizes
            .map(::normalizeDimensions)
            .distinctBy { "${it.width}x${it.height}" }
            .filter { size ->
                val aspect = size.width.toFloat() / size.height.coerceAtLeast(1).toFloat()
                abs(aspect - targetAspect) <= SELECTED_ASPECT_MATCH_TOLERANCE
            }
            .minWithOrNull(
                compareBy<MaximumSensorPreferredDimensions> { size ->
                    val area = size.width.toLong() * size.height.toLong()
                    abs(area - preferredArea)
                }.thenByDescending { size ->
                    size.width.toLong() * size.height.toLong()
                }
            )
            ?: preferred
    }

    /**
     * Filters available sizes to those meeting the high-end minimum quality floor.
     */
    private fun filterHighEndSizes(availableSizes: List<Size>): List<Size> {
        val filtered = availableSizes.filter { size ->
            maxOf(size.width, size.height) >= HIGH_END_MIN_LONG_EDGE
        }
        return filtered.ifEmpty { availableSizes }
    }

    private fun capPreferredForSustainableLiveVideo(
        preferred: Size,
        availableSizes: List<Size>
    ): Size = capPreferredForSustainableLiveVideoDimensions(
        preferred = MaximumSensorPreferredDimensions(preferred.width, preferred.height),
        availableSizes = availableSizes.map { MaximumSensorPreferredDimensions(it.width, it.height) }
    ).toSize()

    internal fun capPreferredForSustainableLiveVideoDimensions(
        preferred: MaximumSensorPreferredDimensions,
        availableSizes: List<MaximumSensorPreferredDimensions>
    ): MaximumSensorPreferredDimensions {
        val normalizedPreferred = normalizeDimensions(preferred)
        if (isSustainableLiveVideoDimensions(normalizedPreferred)) return normalizedPreferred

        val targetAspect =
            normalizedPreferred.width.toFloat() / normalizedPreferred.height.coerceAtLeast(1).toFloat()
        val candidates = availableSizes
            .map(::normalizeDimensions)
            .distinctBy { "${it.width}x${it.height}" }
            .filter(::isSustainableLiveVideoDimensions)
        if (candidates.isEmpty()) return normalizedPreferred

        val aspectMatched = candidates.filter { size ->
            val aspect = size.width.toFloat() / size.height.coerceAtLeast(1).toFloat()
            abs(aspect - targetAspect) <= ASPECT_MATCH_TOLERANCE
        }
        val candidatePool = aspectMatched.ifEmpty { candidates }
        return candidatePool.maxWithOrNull(
            compareBy<MaximumSensorPreferredDimensions> { size -> size.width.toLong() * size.height.toLong() }
                .thenBy { size -> size.width }
        ) ?: normalizedPreferred
    }

    private fun isSustainableLiveVideoDimensions(size: MaximumSensorPreferredDimensions): Boolean {
        val normalized = normalizeDimensions(size)
        return normalized.width <= LIVE_VIDEO_SUSTAINABLE_MAX_LONG_EDGE &&
            normalized.width.toLong() * normalized.height.toLong() <= LIVE_VIDEO_SUSTAINABLE_MAX_AREA
    }

    private fun maximumSensorPreferredSize(
        requested: Size,
        availableSizes: List<Size>,
        pixelArraySize: Size?,
        maxRawSize: Size?,
        fallbackSensorSize: Size?,
        favorFullSensor: Boolean
    ): Size = resolveMaximumSensorPreferredDimensions(
        requestedWidth = requested.width,
        requestedHeight = requested.height,
        availableSizes = availableSizes.map { MaximumSensorPreferredDimensions(it.width, it.height) },
        pixelArrayWidth = pixelArraySize?.width,
        pixelArrayHeight = pixelArraySize?.height,
        maxRawWidth = maxRawSize?.width,
        maxRawHeight = maxRawSize?.height,
        fallbackSensorWidth = fallbackSensorSize?.width,
        fallbackSensorHeight = fallbackSensorSize?.height,
        favorFullSensor = favorFullSensor
    ).toSize()

    internal fun shouldUseMaximumSensorPreferredSize(
        strictHighEnd: Boolean,
        uiMode: AppMode,
        rawModeActive: Boolean
    ): Boolean =
        strictHighEnd && uiMode == AppMode.VIDEO && rawModeActive

    internal fun resolveMaximumSensorPreferredDimensions(
        requestedWidth: Int,
        requestedHeight: Int,
        availableSizes: List<MaximumSensorPreferredDimensions>,
        pixelArrayWidth: Int?,
        pixelArrayHeight: Int?,
        maxRawWidth: Int?,
        maxRawHeight: Int?,
        fallbackSensorWidth: Int?,
        fallbackSensorHeight: Int?,
        favorFullSensor: Boolean
    ): MaximumSensorPreferredDimensions {
        val normalizedRequested = normalizeDimensions(
            MaximumSensorPreferredDimensions(requestedWidth, requestedHeight)
        )
        val normalizedAvailable = availableSizes
            .map(::normalizeDimensions)
            .distinctBy { "${it.width}x${it.height}" }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
        val sensorBacking = largestSensorBacking(
            pixelArrayWidth = pixelArrayWidth,
            pixelArrayHeight = pixelArrayHeight,
            maxRawWidth = maxRawWidth,
            maxRawHeight = maxRawHeight,
            fallbackSensorWidth = fallbackSensorWidth,
            fallbackSensorHeight = fallbackSensorHeight,
            requested = normalizedRequested
        )
        if (normalizedAvailable.isEmpty()) {
            return if (favorFullSensor) {
                sensorBacking
            } else {
                cropSensorBackingToRequestedAspect(sensorBacking, normalizedRequested)
            }
        }
        if (favorFullSensor) {
            return sensorBacking
        }
        val requestedAspect = normalizedRequested.width.toFloat() / normalizedRequested.height.coerceAtLeast(1).toFloat()
        val aspectMatched = normalizedAvailable.filter { size ->
            val aspect = size.width.toFloat() / size.height.coerceAtLeast(1).toFloat()
            abs(aspect - requestedAspect) <= ASPECT_MATCH_TOLERANCE
        }
        return aspectMatched.firstOrNull() ?: normalizedAvailable.first()
    }

    private fun largestSensorBacking(
        pixelArrayWidth: Int?,
        pixelArrayHeight: Int?,
        maxRawWidth: Int?,
        maxRawHeight: Int?,
        fallbackSensorWidth: Int?,
        fallbackSensorHeight: Int?,
        requested: MaximumSensorPreferredDimensions
    ): MaximumSensorPreferredDimensions =
        buildList {
            if (pixelArrayWidth != null && pixelArrayHeight != null) {
                add(MaximumSensorPreferredDimensions(pixelArrayWidth, pixelArrayHeight))
            }
            if (maxRawWidth != null && maxRawHeight != null) {
                add(MaximumSensorPreferredDimensions(maxRawWidth, maxRawHeight))
            }
            if (fallbackSensorWidth != null && fallbackSensorHeight != null) {
                add(MaximumSensorPreferredDimensions(fallbackSensorWidth, fallbackSensorHeight))
            }
            add(requested)
        }
            .map(::normalizeDimensions)
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: normalizeDimensions(requested)

    private fun cropSensorBackingToRequestedAspect(
        sensorBacking: MaximumSensorPreferredDimensions,
        requested: MaximumSensorPreferredDimensions
    ): MaximumSensorPreferredDimensions {
        val sensor = normalizeDimensions(sensorBacking)
        val normalizedRequested = normalizeDimensions(requested)
        val requestedAspect = normalizedRequested.width.toFloat() / normalizedRequested.height.coerceAtLeast(1).toFloat()
        val sensorAspect = sensor.width.toFloat() / sensor.height.coerceAtLeast(1).toFloat()
        return if (sensorAspect >= requestedAspect) {
            val width = (sensor.height * requestedAspect).roundToInt().coerceAtLeast(1)
            MaximumSensorPreferredDimensions(width.coerceAtMost(sensor.width), sensor.height)
        } else {
            val height = (sensor.width / requestedAspect).roundToInt().coerceAtLeast(1)
            MaximumSensorPreferredDimensions(sensor.width, height.coerceAtMost(sensor.height))
        }
    }

    private fun String.toGpuVideoCodec(): GpuVideoCodec = when (uppercase()) {
        "AV1", "AV01" -> GpuVideoCodec.AV1
        "AVC", "H264" -> GpuVideoCodec.AVC
        else -> GpuVideoCodec.HEVC
    }

    private fun normalizeDimensions(
        size: MaximumSensorPreferredDimensions
    ): MaximumSensorPreferredDimensions =
        if (size.width >= size.height) size else MaximumSensorPreferredDimensions(size.height, size.width)

    /**
     * Enforces a minimum quality floor in high-end mode.
     * If the preferred size is below the minimum, returns the smallest
     * available size that meets the floor, matching the preferred aspect.
     */
    private fun enforceHighEndMinimum(preferred: Size): Size {
        val longEdge = maxOf(preferred.width, preferred.height)
        if (longEdge >= HIGH_END_MIN_LONG_EDGE) return preferred
        val targetAspect = preferred.width.toFloat() / preferred.height.coerceAtLeast(1).toFloat()
        val shortEdge = (HIGH_END_MIN_LONG_EDGE / targetAspect).roundToInt().coerceAtLeast(1)
        return Size(HIGH_END_MIN_LONG_EDGE, shortEdge)
    }

    /**
     * Clamps the preferred size to comply with RAW mode constraints,
     * preserving the aspect ratio as closely as possible.
     */
    private fun clampPreferredForRawMode(
        preferred: Size,
        rawModeSizes: List<Size>
    ): Size {
        val normalized = RecordingSelectionPolicy.normalizeForOrientation(preferred)
        if (normalized.width <= RAW_MODE_MAX_LONG_EDGE) return normalized
        if (rawModeSizes.isNotEmpty()) {
            val targetAspect = normalized.width.toFloat() / normalized.height.coerceAtLeast(1).toFloat()
            return rawModeSizes.minWithOrNull(
                compareBy<Size> { candidate ->
                    abs((candidate.width.toFloat() / candidate.height.coerceAtLeast(1).toFloat()) - targetAspect)
                }.thenByDescending { candidate ->
                    candidate.width.toLong() * candidate.height.toLong()
                }
            ) ?: rawModeSizes.first()
        }
        val scaledShortEdge = (
            normalized.height.toFloat() *
                (RAW_MODE_MAX_LONG_EDGE.toFloat() / normalized.width.toFloat())
            ).roundToInt().coerceAtLeast(1)
        return Size(RAW_MODE_MAX_LONG_EDGE, scaledShortEdge)
    }

    /**
     * Ensures the selected size meets the high-end minimum quality floor
     * by upgrading to the smallest available size that satisfies the floor.
     */
    private fun ensureMinimumQuality(preferred: Size, available: List<Size>): Size {
        val preferredArea = preferred.width.toLong() * preferred.height.toLong()
        val minArea = HIGH_END_MIN_LONG_EDGE.toLong() * (HIGH_END_MIN_LONG_EDGE * 9 / 16).toLong()
        if (preferredArea >= minArea) return preferred
        val targetAspect = preferred.width.toFloat() / preferred.height.coerceAtLeast(1).toFloat()
        return available
            .filter { maxOf(it.width, it.height) >= HIGH_END_MIN_LONG_EDGE }
            .minByOrNull { size ->
                abs(size.width.toFloat() / size.height.coerceAtLeast(1).toFloat() - targetAspect)
            } ?: preferred
    }

    // ── Codec Chain ───────────────────────────────────────────────────────────

    /**
     * Builds a prioritized codec candidate list for the recording session.
     *
     * Priority rules:
     *  1. HDR/LOG modes require HEVC or AV1 (10-bit containers).
     *  2. AV1 is preferred in strict high-end mode if supported.
     *  3. HEVC is the universal fallback for cinema modes.
     *  4. AVC is only included for non-cinema, non-HDR recordings.
     *
     * @param preferredCodec    Codec selected by [RecordingCapabilityPolicy].
     * @param av1Supported      Whether the device supports AV1 hardware encoding.
     * @param hdrRequested      Whether HDR mode is active.
     * @param logEnabled        Whether LOG mode is active.
     * @param rawRequested      Whether RAW mode is active.
     * @param strictHighEnd     Whether strict high-end pipeline is active.
     * @param allowAvcFallback  Whether AVC is a valid fallback for this session.
     */
    private fun buildCodecCandidates(
        preferredCodec: GpuVideoCodec,
        av1Supported: Boolean,
        hdrRequested: Boolean,
        logEnabled: Boolean,
        rawRequested: Boolean,
        strictHighEnd: Boolean,
        allowAvcFallback: Boolean
    ): List<GpuVideoCodec> = buildList {
        // Preferred codec is always first
        add(preferredCodec)

        // In high-end mode, prefer AV1 if available and the preferred is not already AV1
        if (strictHighEnd && av1Supported && preferredCodec != GpuVideoCodec.AV1) {
            add(GpuVideoCodec.AV1)
        }

        // HEVC is mandatory for HDR/LOG/RAW (10-bit container requirement)
        if (preferredCodec != GpuVideoCodec.HEVC) {
            add(GpuVideoCodec.HEVC)
        }

        // AV1 as a secondary option if supported
        if (av1Supported && !contains(GpuVideoCodec.AV1)) {
            add(GpuVideoCodec.AV1)
        }

        // AVC only for standard non-cinema recordings
        if (allowAvcFallback && !hdrRequested && !logEnabled && !rawRequested && !strictHighEnd) {
            add(GpuVideoCodec.AVC)
        }
    }.distinct()
}

// ── Request Model ─────────────────────────────────────────────────────────────

/**
 * Full context for a recording selection resolution request.
 *
 * Contains all inputs needed to produce an optimal [ResolvedRecordingSelection]
 * without requiring access to any camera or ViewModel state.
 */
data class CameraRecordingSelectionRequest(
    /** User-requested recording resolution. */
    val preferred: Size,
    /** Preferred codec from user/settings selection. */
    val preferredCodec: GpuVideoCodec = GpuVideoCodec.HEVC,
    /** Camera2-reported list of supported output sizes. */
    val availableSizes: List<Size> = emptyList(),
    /** Full pixel array size reported by Camera2 characteristics. */
    val pixelArraySize: Size? = null,
    /** Maximum RAW output size (from SCALER_STREAM_CONFIGURATION_MAP). */
    val maxRawSize: Size? = null,
    /** Fallback sensor size derived from lens characteristics. */
    val fallbackSensorSize: Size? = null,
    /** Current UI app mode (VIDEO, PHOTO, etc.). */
    val uiMode: AppMode = AppMode.VIDEO,
    /** User-selected delivery/framing aspect ratio, normalized long-edge/short-edge. */
    val selectedOutputAspectRatio: Float? = null,
    /** Active cinema sub-mode (PRO, LOG, RAW, HDR). */
    val cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
    /** Whether RAW video recording is requested. */
    val rawEnabled: Boolean = false,
    /** Whether the device supports RAW video output. */
    val rawSupported: Boolean = true,
    /** Whether the device's encoder supports AV1. */
    val av1Supported: Boolean = false,
    /** Whether HDR (10-bit / HDR10) recording is requested. */
    val hdrRequested: Boolean = false,
    /** Whether 10-bit color is requested (may be satisfied by HEVC or AV1). */
    val tenBitRequested: Boolean = false,
    /** Target frame rate for the recording session. */
    val fps: Int = 25,
    /**
     * Lambda that normalizes a requested size to the camera's orientation
     * coordinate space (always landscape-first for Camera2).
     */
    val normalizeRecordingRequest: (Size) -> Size = { it },
    /**
     * Lambda that validates a candidate size against the codec/fps combination,
     * falling back to the input size if no exact match is found.
     */
    val resolveSupportedSize: (Size, GpuVideoCodec, Int) -> Size = { size, _, _ -> size }
)
