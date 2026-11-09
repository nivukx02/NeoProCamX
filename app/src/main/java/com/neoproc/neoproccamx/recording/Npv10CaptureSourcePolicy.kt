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
        val availableP010 = p010Sizes.normalizedDistinct()
        val preferredP010 = resolveNativeP010TargetForSizes(selected, availableP010)
        if (preferredP010 == null) {
            val availableSizes = availableP010
                .joinToString(prefix = "[", postfix = "]") { "${it.width}x${it.height}" }
            throw IllegalStateException(
                "Native 10-bit P010 exact capture is unavailable for requested=" +
                    "${selected.width}x${selected.height}; available=$availableSizes. " +
                    "NPV10 refuses resolution substitution; refusing RAW or 8-bit fallback."
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

    /**
     * Resolves the exact native P010 stream that the packet pipeline will use. Camera cold-open uses
     * this same selector before creating ImageReaders, so its standby graph cannot start at a small
     * provisional analysis size and then rebuild when NPV10 discovers the real capture size.
     */
    internal fun resolveNativeP010TargetForSizes(
        selectedResolution: Npv10CaptureSize,
        p010Sizes: List<Npv10CaptureSize>
    ): Npv10CaptureSize? =
        p010Sizes.normalizedDistinct()
            .firstOrNull { it.matches(selectedResolution.normalize()) }

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

    private const val SOURCE_FORMAT_P010 = "YCBCR_P010"
}
