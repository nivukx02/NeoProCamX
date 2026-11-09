package com.neoproc.neoproccamx.recording

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

/** The sink whose capabilities must admit the selected camera stream. */
enum class RecordingSelectionBackend { MEDIA_CODEC, SENSOR_RAW }

data class ResolvedRecordingSelection(
    val requestedSize: Size,
    val sourceSize: Size,
    val size: Size,
    val codec: String,
    val cameraExactMatch: Boolean,
    val encoderExactMatch: Boolean,
    val upscaledToRequested: Boolean,
    val backend: RecordingSelectionBackend = RecordingSelectionBackend.MEDIA_CODEC
) {
    val requiresMediaEncoder: Boolean get() = backend == RecordingSelectionBackend.MEDIA_CODEC
    val exactOutputSupported: Boolean get() = cameraExactMatch &&
        (!requiresMediaEncoder || encoderExactMatch) && !upscaledToRequested &&
        requestedSize == sourceSize && requestedSize == size
}

internal data class ResolvedRecordingDimensionsSelection(
    val requestedSize: RecordingDimensions,
    val sourceSize: RecordingDimensions,
    val size: RecordingDimensions,
    val codec: String,
    val cameraExactMatch: Boolean,
    val encoderExactMatch: Boolean,
    val upscaledToRequested: Boolean,
    val backend: RecordingSelectionBackend = RecordingSelectionBackend.MEDIA_CODEC
) {
    val exactOutputSupported: Boolean get() = cameraExactMatch &&
        (backend == RecordingSelectionBackend.SENSOR_RAW || encoderExactMatch) &&
        !upscaledToRequested && requestedSize == sourceSize && requestedSize == size
}

/**
 * Ensures we only record at resolutions that are actually supported by both
 * the camera sensor/ISP and the media encoder. Faking 4K or 5K by upscaling
 * 1080p is strictly prohibited in High-End mode.
 */
internal object RecordingSelectionPolicy {
    private const val ASPECT_MATCH_TOLERANCE = 0.05f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /** RAW packets are not sent to MediaCodec. Only the actual selected HAL RAW tier may admit them. */
    fun resolveRaw(
        requestedSize: Size,
        selectedRawStreamSize: Size?,
        rawSupportedSizes: List<Size>,
        rawSupported: Boolean,
        explicitResolutionSelection: Boolean
    ): ResolvedRecordingSelection {
        val resolved = resolveRawDimensions(
            requestedSize.toRecordingDimensions(),
            selectedRawStreamSize?.toRecordingDimensions(),
            rawSupportedSizes.map { it.toRecordingDimensions() },
            rawSupported,
            explicitResolutionSelection
        )
        return ResolvedRecordingSelection(
            requestedSize = resolved.requestedSize.toAndroidSize(),
            sourceSize = resolved.sourceSize.toAndroidSize(),
            size = resolved.size.toAndroidSize(),
            codec = resolved.codec,
            cameraExactMatch = resolved.cameraExactMatch,
            encoderExactMatch = resolved.encoderExactMatch,
            upscaledToRequested = resolved.upscaledToRequested,
            backend = resolved.backend
        )
    }

    internal fun resolveRawDimensions(
        requestedSize: RecordingDimensions,
        selectedRawStreamSize: RecordingDimensions?,
        rawSupportedSizes: List<RecordingDimensions>,
        rawSupported: Boolean,
        explicitResolutionSelection: Boolean
    ): ResolvedRecordingDimensionsSelection {
        val selected = selectedRawStreamSize?.let(::normalizeForOrientation)
            ?.takeIf { it.width > 0 && it.height > 0 }
        val requested = if (!explicitResolutionSelection && selected != null) {
            selected
        } else {
            normalizeForOrientation(requestedSize)
        }
        val advertised = rawSupportedSizes.map(::normalizeForOrientation)
            .filter { it.width > 0 && it.height > 0 }
        val exact = rawSupported && selected != null && selected in advertised && requested == selected
        return ResolvedRecordingDimensionsSelection(
            requestedSize = requested,
            sourceSize = selected ?: requested,
            size = selected ?: requested,
            codec = "NPRAW",
            cameraExactMatch = exact,
            // Not applicable is deliberately not reported as successful HEVC admission.
            encoderExactMatch = false,
            upscaledToRequested = false,
            backend = RecordingSelectionBackend.SENSOR_RAW
        )
    }

    fun resolveSupportedSize(
        cameraSupported: Size,
        codec: String,
        fps: Int
    ): Size {
        if (HighEndQualityPolicy.isFullSensorForced()) {
            return normalizeForOrientation(cameraSupported)
        }
        // Enforce codec-specific maximums
        val maxLongEdge = when (codec) {
            "HEVC", "H265" -> 5120
            "AVC", "H264" -> if (fps > 30) 3840 else 4096
            else -> 3840
        }
        return capSizeToLongEdge(cameraSupported, maxLongEdge)
    }

    fun isStrictResolutionViolation(requested: Size, supported: Size): Boolean {
        if (!strictHighEndEnabled()) return false
        val requestedArea = requested.width.toLong() * requested.height.toLong()
        val supportedArea = supported.width.toLong() * supported.height.toLong()
        // Allow up to 10% discrepancy for aspect ratio letterboxing
        return requestedArea > (supportedArea * 1.1)
    }

    fun resolve(
        requestedSize: Size,
        fps: Int,
        cameraSupportedSizes: List<Size>,
        preferredCodec: String = "HEVC",
        resolveSupportedSize: (Size, String, Int) -> Size = ::resolveSupportedSize
    ): ResolvedRecordingSelection {
        val resolved = resolveDimensions(
            requestedSize = requestedSize.toRecordingDimensions(),
            fps = fps,
            cameraSupportedSizes = cameraSupportedSizes.map { it.toRecordingDimensions() },
            preferredCodec = preferredCodec,
            resolveSupportedSize = { size, codec, requestedFps ->
                resolveSupportedSize(size.toAndroidSize(), codec, requestedFps).toRecordingDimensions()
            }
        )
        return ResolvedRecordingSelection(
            requestedSize = resolved.requestedSize.toAndroidSize(),
            sourceSize = resolved.sourceSize.toAndroidSize(),
            size = resolved.size.toAndroidSize(),
            codec = resolved.codec,
            cameraExactMatch = resolved.cameraExactMatch,
            encoderExactMatch = resolved.encoderExactMatch,
            upscaledToRequested = resolved.upscaledToRequested
        )
    }

    internal fun resolveDimensions(
        requestedSize: RecordingDimensions,
        fps: Int,
        cameraSupportedSizes: List<RecordingDimensions>,
        preferredCodec: String = "HEVC",
        resolveSupportedSize: (RecordingDimensions, String, Int) -> RecordingDimensions
    ): ResolvedRecordingDimensionsSelection {
        val requested = normalizeForOrientation(requestedSize)
        val requestedAspect = aspectRatio(requested)
        val cameraSupported = selectCameraSourceDimensions(
            requested = requested,
            cameraSupportedSizes = cameraSupportedSizes
        )

        val effectivePreferredCodec = if (
            strictHighEndEnabled() &&
            cameraSupported.width >= 3840 &&
            fps >= 60
        ) {
            "HEVC" // Force HEVC for 4K60+
        } else {
            preferredCodec
        }
        val codecCandidates = codecCandidates(effectivePreferredCodec)

        var bestCodec = effectivePreferredCodec
        var bestSize = normalizeForOrientation(
            resolveSupportedSize(cameraSupported, effectivePreferredCodec, fps)
        )

        codecCandidates.forEach { codec ->
            val candidate = normalizeForOrientation(resolveSupportedSize(cameraSupported, codec, fps))
            if (sameDimensions(candidate, requested)) {
                return ResolvedRecordingDimensionsSelection(
                    requestedSize = requested,
                    sourceSize = cameraSupported,
                    size = candidate,
                    codec = codec,
                    cameraExactMatch = sameDimensions(cameraSupported, requested),
                    encoderExactMatch = true,
                    upscaledToRequested = false
                )
            }
            if (isBetterEncoderFallback(candidate, bestSize, requested, cameraSupported, requestedAspect)) {
                bestSize = candidate
                bestCodec = codec
            }
        }

        val upscaled = area(bestSize) > area(cameraSupported)

        return ResolvedRecordingDimensionsSelection(
            requestedSize = requested,
            sourceSize = cameraSupported,
            size = bestSize,
            codec = bestCodec,
            cameraExactMatch = sameDimensions(cameraSupported, requested),
            encoderExactMatch = sameDimensions(bestSize, requested),
            upscaledToRequested = upscaled
        )
    }

    private fun selectCameraSourceDimensions(
        requested: RecordingDimensions,
        cameraSupportedSizes: List<RecordingDimensions>
    ): RecordingDimensions {
        val supported = cameraSupportedSizes
            .map(::normalizeForOrientation)
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.width to it.height }
        if (supported.isEmpty()) return requested

        supported.firstOrNull { sameDimensions(it, requested) }?.let { return it }

        val requestedArea = area(requested)
        val aspectMatched = supported.filter {
            abs(aspectRatio(it) - aspectRatio(requested)) <= ASPECT_MATCH_TOLERANCE
        }
        aspectMatched
            .filter { area(it) <= requestedArea }
            .maxByOrNull(::area)
            ?.let { return it }
        aspectMatched
            .filter { area(it) > requestedArea }
            .minByOrNull(::area)
            ?.let { return it }

        supported
            .filter { area(it) <= requestedArea }
            .minWithOrNull(
                compareBy<RecordingDimensions> { abs(aspectRatio(it) - aspectRatio(requested)) }
                    .thenByDescending { area(it) }
            )
            ?.let { return it }

        return supported.minWithOrNull(
            compareBy<RecordingDimensions> { abs(aspectRatio(it) - aspectRatio(requested)) }
                .thenBy { area(it) }
        ) ?: requested
    }

    private fun codecCandidates(preferredCodec: String): List<String> {
        val normalized = when (preferredCodec.uppercase()) {
            "H264" -> "AVC"
            "H265" -> "HEVC"
            "AV01" -> "AV1"
            else -> preferredCodec.uppercase()
        }
        return buildList {
            add(normalized)
            if (normalized != "HEVC") add("HEVC")
            if (normalized != "AV1") add("AV1")
            if (normalized != "AVC") add("AVC")
        }.distinct()
    }

    private fun isBetterEncoderFallback(
        candidate: RecordingDimensions,
        current: RecordingDimensions,
        requested: RecordingDimensions,
        source: RecordingDimensions,
        requestedAspect: Float
    ): Boolean {
        if (sameDimensions(candidate, source) && !sameDimensions(current, source)) return true
        if (!sameDimensions(candidate, source) && sameDimensions(current, source)) return false

        val requestedArea = area(requested)
        val candidateArea = area(candidate)
        val currentArea = area(current)
        val candidateWithinRequest = candidateArea <= requestedArea
        val currentWithinRequest = currentArea <= requestedArea
        if (candidateWithinRequest != currentWithinRequest) return candidateWithinRequest

        val candidateAspectDelta = abs(aspectRatio(candidate) - requestedAspect)
        val currentAspectDelta = abs(aspectRatio(current) - requestedAspect)
        if (candidateAspectDelta != currentAspectDelta) return candidateAspectDelta < currentAspectDelta

        return if (candidateWithinRequest) {
            candidateArea > currentArea
        } else {
            candidateArea < currentArea
        }
    }

    private fun capSizeToLongEdge(size: Size, maxLongEdge: Int): Size {
        val normalized = normalizeForOrientation(size)
        val longEdge = maxOf(normalized.width, normalized.height)
        if (longEdge <= maxLongEdge) return normalized
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val scaledW = ((normalized.width * scale).toInt() / 2) * 2
        val scaledH = ((normalized.height * scale).toInt() / 2) * 2
        return Size(scaledW.coerceAtLeast(2), scaledH.coerceAtLeast(2))
    }

    fun normalizeForOrientation(size: Size): Size {
        return if (size.width >= size.height) size else Size(size.height, size.width)
    }

    private fun normalizeForOrientation(size: RecordingDimensions): RecordingDimensions {
        return if (size.width >= size.height) size else RecordingDimensions(size.height, size.width)
    }

    private fun area(size: Size): Long =
        size.width.toLong() * size.height.toLong()

    private fun area(size: RecordingDimensions): Long =
        size.width.toLong() * size.height.toLong()

    private fun aspectRatio(size: Size): Float =
        size.width.toFloat() / size.height.coerceAtLeast(1).toFloat()

    private fun aspectRatio(size: RecordingDimensions): Float =
        size.width.toFloat() / size.height.coerceAtLeast(1).toFloat()

    private fun sameDimensions(left: Size, right: Size): Boolean =
        left.width == right.width && left.height == right.height

    private fun sameDimensions(left: RecordingDimensions, right: RecordingDimensions): Boolean =
        left.width == right.width && left.height == right.height

    private fun Size.toRecordingDimensions(): RecordingDimensions =
        RecordingDimensions(width, height)

    private fun RecordingDimensions.toAndroidSize(): Size =
        Size(width, height)
}
