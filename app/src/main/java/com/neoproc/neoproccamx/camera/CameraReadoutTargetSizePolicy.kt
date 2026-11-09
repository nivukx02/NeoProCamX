package com.neoproc.neoproccamx.camera

import android.util.Size

internal object CameraReadoutTargetSizePolicy {
    private const val ASPECT_MATCH_TOLERANCE = 0.015
    private const val SENSOR_NEAR_AREA_MULTIPLIER = 1.03
    private const val DELIVERY_NEAR_AREA_MULTIPLIER = 1.10

    private enum class SelectionIntent {
        SENSOR_READOUT,
        DELIVERY_MATCH,
        ANALYSIS
    }

    fun resolveYuvReaderSize(
        analysisTargetSize: Size?,
        recordingReadoutOverrideSize: Size?,
        recordingEncoderTargetSize: Size? = null,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        supportedYuvSizes: List<Size>,
        fallback: Size
    ): Size =
        resolveYuvReaderStreamSize(
            analysisTargetSize = analysisTargetSize?.toCameraStreamSize(),
            recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
            recordingEncoderTargetSize = recordingEncoderTargetSize?.toCameraStreamSize(),
            readoutSidecarMode = readoutSidecarMode,
            supportedYuvSizes = supportedYuvSizes.map { it.toCameraStreamSize() },
            fallback = fallback.toCameraStreamSize()
        ).toAndroidSize()

    fun resolveYuvReaderStreamSize(
        analysisTargetSize: CameraStreamSize?,
        recordingReadoutOverrideSize: CameraStreamSize?,
        recordingEncoderTargetSize: CameraStreamSize? = null,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        supportedYuvSizes: List<CameraStreamSize>,
        fallback: CameraStreamSize
    ): CameraStreamSize {
        val requested = when (readoutSidecarMode) {
            CameraReadoutSidecarMode.MAXIMUM -> recordingReadoutOverrideSize ?: analysisTargetSize
            CameraReadoutSidecarMode.ENCODER_MATCHED -> recordingEncoderTargetSize
                ?: analysisTargetSize
                ?: recordingReadoutOverrideSize
            CameraReadoutSidecarMode.DISABLED -> analysisTargetSize
        }
        val intent = when (readoutSidecarMode) {
            CameraReadoutSidecarMode.MAXIMUM -> SelectionIntent.SENSOR_READOUT
            CameraReadoutSidecarMode.ENCODER_MATCHED -> if (recordingEncoderTargetSize != null) {
                SelectionIntent.DELIVERY_MATCH
            } else {
                SelectionIntent.ANALYSIS
            }
            CameraReadoutSidecarMode.DISABLED -> SelectionIntent.ANALYSIS
        }
        return chooseSupportedSize(
            requested = requested,
            supportedSizes = supportedYuvSizes,
            fallback = fallback,
            intent = intent
        )
    }

    fun resolveRawReaderSize(
        recordingReadoutOverrideSize: Size?,
        supportedRawSizes: List<Size>,
        fallback: Size
    ): Size =
        resolveRawReaderStreamSize(
            recordingReadoutOverrideSize = recordingReadoutOverrideSize?.toCameraStreamSize(),
            supportedRawSizes = supportedRawSizes.map { it.toCameraStreamSize() },
            fallback = fallback.toCameraStreamSize()
        ).toAndroidSize()

    fun resolveRawReaderStreamSize(
        recordingReadoutOverrideSize: CameraStreamSize?,
        supportedRawSizes: List<CameraStreamSize>,
        fallback: CameraStreamSize
    ): CameraStreamSize =
        chooseSupportedSize(
            requested = recordingReadoutOverrideSize,
            supportedSizes = supportedRawSizes,
            fallback = fallback,
            intent = SelectionIntent.SENSOR_READOUT
        )

    private fun chooseSupportedSize(
        requested: CameraStreamSize?,
        supportedSizes: List<CameraStreamSize>,
        fallback: CameraStreamSize,
        intent: SelectionIntent
    ): CameraStreamSize {
        val supported = supportedSizes
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.key }
        if (supported.isEmpty()) return fallback
        if (requested == null) return supported.largestByArea() ?: fallback

        supported.firstOrNull { it.sameDimensions(requested) }?.let { return it }

        return when (intent) {
            SelectionIntent.SENSOR_READOUT -> chooseSensorReadoutSize(
                requested = requested,
                supported = supported,
                fallback = fallback
            )
            SelectionIntent.DELIVERY_MATCH,
            SelectionIntent.ANALYSIS -> chooseDeliveryCompatibleSize(
                requested = requested,
                supported = supported,
                fallback = fallback
            )
        }
    }

    private fun chooseSensorReadoutSize(
        requested: CameraStreamSize,
        supported: List<CameraStreamSize>,
        fallback: CameraStreamSize
    ): CameraStreamSize {
        val requestedArea = requested.area
        val candidates = supported.filter {
            it.area <= requestedArea || it.isNearAreaAbove(requested, SENSOR_NEAR_AREA_MULTIPLIER)
        }
        return candidates
            .largestByArea()
            ?: supported.largestByArea()
            ?: fallback
    }

    private fun chooseDeliveryCompatibleSize(
        requested: CameraStreamSize,
        supported: List<CameraStreamSize>,
        fallback: CameraStreamSize
    ): CameraStreamSize {
        supported
            .filter { it.area <= requested.area && it.hasMatchingAspect(requested) }
            .largestByArea()
            ?.let { return it }

        supported
            .filter { it.isNearAreaAbove(requested, DELIVERY_NEAR_AREA_MULTIPLIER) && it.hasMatchingAspect(requested) }
            .smallestByArea()
            ?.let { return it }

        supported
            .filter { it.area <= requested.area }
            .minWithOrNull(
                compareBy<CameraStreamSize> { it.aspectDeltaFrom(requested) }
                    .thenByDescending { it.area }
            )
            ?.let { return it }

        return supported
            .minWithOrNull(
                compareBy<CameraStreamSize> { it.area }
                    .thenBy { it.aspectDeltaFrom(requested) }
            )
            ?: fallback
    }

    private fun List<CameraStreamSize>.largestByArea(): CameraStreamSize? =
        maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun List<CameraStreamSize>.smallestByArea(): CameraStreamSize? =
        minWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun CameraStreamSize.sameDimensions(other: CameraStreamSize): Boolean =
        (width == other.width && height == other.height) ||
            (width == other.height && height == other.width)

    private fun CameraStreamSize.isNearAreaAbove(
        requested: CameraStreamSize,
        multiplier: Double
    ): Boolean =
        area > requested.area &&
            area.toDouble() <= requested.area.toDouble() * multiplier

    private fun CameraStreamSize.hasMatchingAspect(other: CameraStreamSize): Boolean =
        aspectDeltaFrom(other) <= ASPECT_MATCH_TOLERANCE

    private fun CameraStreamSize.aspectDeltaFrom(other: CameraStreamSize): Double {
        val thisAspect = normalizedAspect()
        val otherAspect = other.normalizedAspect()
        return kotlin.math.abs(thisAspect - otherAspect)
    }

    private fun CameraStreamSize.normalizedAspect(): Double {
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        val shortEdge = minOf(width, height).coerceAtLeast(1)
        return longEdge.toDouble() / shortEdge.toDouble()
    }

    private val CameraStreamSize.key: String
        get() = "${width}x${height}"
}
