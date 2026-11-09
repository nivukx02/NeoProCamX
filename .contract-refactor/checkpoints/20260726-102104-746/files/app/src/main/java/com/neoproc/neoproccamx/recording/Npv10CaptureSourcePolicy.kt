package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.util.Size

data class Npv10CaptureSourcePlan(
    val size: Size,
    val imageFormat: Int,
    val sourceBitDepth: Int,
    val sourceFormat: String,
    val payloadFormat: Npv10PayloadFormat,
    val nativeTenBitCapture: Boolean,
    val exactSelectedResolution: Boolean,
    val rawSensorCapture: Boolean = false,
    val sensorPixelModeMaximumResolution: Boolean = false,
    val metadataSidecarKeys: List<String> = emptyList()
)

data class Npv10CaptureSize(
    val width: Int,
    val height: Int
)

data class Npv10CaptureSourceDecision(
    val size: Npv10CaptureSize,
    val imageFormat: Int,
    val sourceBitDepth: Int,
    val sourceFormat: String,
    val payloadFormat: Npv10PayloadFormat,
    val nativeTenBitCapture: Boolean,
    val exactSelectedResolution: Boolean,
    val rawSensorCapture: Boolean = false,
    val sensorPixelModeMaximumResolution: Boolean = false,
    val metadataSidecarKeys: List<String> = emptyList()
)

object Npv10CaptureSourcePolicy {
    fun resolve(
        selectedResolution: Size,
        p010Sizes: List<Size>,
        yuv420Sizes: List<Size>,
        requireTenBit: Boolean,
        rawSensorSizes: List<Size> = emptyList(),
        rawPrivateSizes: List<Size> = emptyList(),
        maximumResolutionRawSensorSizes: List<Size> = emptyList(),
        rawModeActive: Boolean = false,
        supportsMaximumResolutionPixelMode: Boolean = false
    ): Npv10CaptureSourcePlan {
        val decision = resolveForSizes(
            selectedResolution = selectedResolution.toCaptureSize(),
            p010Sizes = p010Sizes.map { it.toCaptureSize() },
            yuv420Sizes = yuv420Sizes.map { it.toCaptureSize() },
            requireTenBit = requireTenBit,
            rawSensorSizes = rawSensorSizes.map { it.toCaptureSize() },
            rawPrivateSizes = rawPrivateSizes.map { it.toCaptureSize() },
            maximumResolutionRawSensorSizes = maximumResolutionRawSensorSizes.map { it.toCaptureSize() },
            rawModeActive = rawModeActive,
            supportsMaximumResolutionPixelMode = supportsMaximumResolutionPixelMode
        )
        return Npv10CaptureSourcePlan(
            size = Size(decision.size.width, decision.size.height),
            imageFormat = decision.imageFormat,
            sourceBitDepth = decision.sourceBitDepth,
            sourceFormat = decision.sourceFormat,
            payloadFormat = decision.payloadFormat,
            nativeTenBitCapture = decision.nativeTenBitCapture,
            exactSelectedResolution = decision.exactSelectedResolution,
            rawSensorCapture = decision.rawSensorCapture,
            sensorPixelModeMaximumResolution = decision.sensorPixelModeMaximumResolution,
            metadataSidecarKeys = decision.metadataSidecarKeys
        )
    }

    @SuppressLint("InlinedApi")
    internal fun resolveForSizes(
        selectedResolution: Npv10CaptureSize,
        p010Sizes: List<Npv10CaptureSize>,
        yuv420Sizes: List<Npv10CaptureSize>,
        requireTenBit: Boolean,
        rawSensorSizes: List<Npv10CaptureSize> = emptyList(),
        rawPrivateSizes: List<Npv10CaptureSize> = emptyList(),
        maximumResolutionRawSensorSizes: List<Npv10CaptureSize> = emptyList(),
        rawModeActive: Boolean = false,
        supportsMaximumResolutionPixelMode: Boolean = false
    ): Npv10CaptureSourceDecision {
        val selected = selectedResolution.normalize()
        if (rawModeActive) {
            throw IllegalStateException(
                "NPV10 records custom lossless packed 10-bit P010 data only; use the NPRAW packet pipeline for RAW capture."
            )
        }
        val p010 = p010Sizes.normalizedDistinct()
        val preferredP010 = p010.chooseFor(selected)
        if (preferredP010 == null) {
            throw IllegalStateException(
                "Native 10-bit P010 capture is unavailable for NPV10; refusing RAW or 8-bit fallback."
            )
        }
        return Npv10CaptureSourceDecision(
            size = preferredP010,
            imageFormat = ImageFormat.YCBCR_P010,
            sourceBitDepth = 10,
            sourceFormat = SOURCE_FORMAT_P010,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            nativeTenBitCapture = true,
            exactSelectedResolution = preferredP010.matches(selected)
        )
    }

    private fun List<Npv10CaptureSize>.chooseFor(selected: Npv10CaptureSize): Npv10CaptureSize? {
        firstOrNull { it.matches(selected) }?.let { return it }
        val selectedAspect = selected.aspect()
        return filter { it.area() <= selected.area() && kotlin.math.abs(it.aspect() - selectedAspect) < 0.015f }
            .maxByOrNull { it.area() }
            ?: filter { kotlin.math.abs(it.aspect() - selectedAspect) < 0.015f }
                .minByOrNull { kotlin.math.abs(it.area() - selected.area()) }
            ?: maxByOrNull { it.area() }
    }

    private fun List<Npv10CaptureSize>.normalizedDistinct(): List<Npv10CaptureSize> =
        map { it.normalize() }
            .distinctBy { "${it.width}x${it.height}" }
            .filter { it.width > 0 && it.height > 0 }

    private fun Size.toCaptureSize(): Npv10CaptureSize =
        Npv10CaptureSize(width = width, height = height)

    private fun Npv10CaptureSize.normalize(): Npv10CaptureSize =
        if (width >= height) this else Npv10CaptureSize(height, width)

    private fun Npv10CaptureSize.matches(other: Npv10CaptureSize): Boolean =
        normalize().width == other.normalize().width && normalize().height == other.normalize().height

    private fun Npv10CaptureSize.aspect(): Float =
        width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1).toFloat()

    private fun Npv10CaptureSize.area(): Long =
        width.toLong() * height.toLong()

    private const val SOURCE_FORMAT_P010 = "YCBCR_P010"
}
