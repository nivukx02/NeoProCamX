package com.neoproc.neoproccamx.camera

import android.graphics.ImageFormat
import android.media.ImageReader

/** Exact buffer contract requested from the Camera2 P010 producer. */
internal data class Npv10P010ReaderTransportPlan(
    val dataSpace: Int,
    val dynamicRangeProfile: Long?,
    val authority: String
) {
    val authoritative: Boolean
        get() = dataSpace != DATA_SPACE_UNKNOWN

    companion object {
        const val DATA_SPACE_UNKNOWN = 0
    }
}

/**
 * Resolves an explicit limited-range YUV dataspace from the Camera2 profile that will own P010.
 * Unsupported HDR requests remain UNKNOWN so recording fails closed instead of relabelling SDR.
 */
internal object Npv10P010ReaderTransportPolicy {
    const val DATA_SPACE_BT709_LIMITED = 281_083_904
    const val DATA_SPACE_BT2020_PQ_LIMITED = 298_188_800
    const val DATA_SPACE_BT2020_HLG_LIMITED = 302_383_104

    fun resolve(
        mode: HdrDynamicRangeMode,
        support: HdrDynamicRangePolicy.Support
    ): Npv10P010ReaderTransportPlan {
        val profile = HdrDynamicRangePolicy.resolveProfilePure(
            mode = mode,
            support = support,
            role = HdrSurfaceRole.ENCODER
        )
        return when (mode) {
            HdrDynamicRangeMode.SDR -> Npv10P010ReaderTransportPlan(
                dataSpace = DATA_SPACE_BT709_LIMITED,
                dynamicRangeProfile = null,
                authority = "READER_BT709_LIMITED_STANDARD"
            )
            HdrDynamicRangeMode.HLG -> if (profile == HdrDynamicRangePolicy.PROFILE_HLG10) {
                Npv10P010ReaderTransportPlan(
                    dataSpace = DATA_SPACE_BT2020_HLG_LIMITED,
                    dynamicRangeProfile = profile,
                    authority = "READER_BT2020_HLG_LIMITED_PROFILE"
                )
            } else {
                unsupported("HLG10_PROFILE_UNAVAILABLE")
            }
            HdrDynamicRangeMode.HDR10,
            HdrDynamicRangeMode.HDR10_PLUS -> if (
                profile == HdrDynamicRangePolicy.PROFILE_HDR10 ||
                profile == HdrDynamicRangePolicy.PROFILE_HDR10_PLUS
            ) {
                Npv10P010ReaderTransportPlan(
                    dataSpace = DATA_SPACE_BT2020_PQ_LIMITED,
                    dynamicRangeProfile = profile,
                    authority = "READER_BT2020_PQ_LIMITED_PROFILE"
                )
            } else {
                unsupported("PQ_PROFILE_UNAVAILABLE")
            }
            HdrDynamicRangeMode.DOLBY_VISION -> unsupported("DOLBY_PROFILE_UNSUPPORTED")
        }
    }

    private fun unsupported(reason: String) = Npv10P010ReaderTransportPlan(
        dataSpace = Npv10P010ReaderTransportPlan.DATA_SPACE_UNKNOWN,
        dynamicRangeProfile = null,
        authority = reason
    )
}

/**
 * Creates the Camera2 P010 consumer without imposing a BufferQueue dataspace contract.
 *
 * Physical A/B evidence on the iQOO I2405/API 36 HAL proved that the legacy P010 reader delivers
 * full-resolution frames, while both an explicit SessionConfiguration SRGB colour space and an
 * ImageReader.Builder BT.709 dataspace cause the first capture to fail. The legacy constructor still
 * exposes real P010 hardware buffers and CPU-readable planes; [android.media.Image.dataSpace] is
 * consumed when the HAL supplies it and remains truthfully UNKNOWN when the HAL omits it.
 *
 * The requested [transport] is retained as a separate session-intent contract. It is never written
 * into Image metadata and therefore can never be mistaken for producer-owned pixel evidence.
 */
internal object Npv10P010ImageReaderFactory {
    const val P010_READER_CONTRACT = "LEGACY_P010_HAL_DEFAULT_DATASPACE"

    fun create(
        width: Int,
        height: Int,
        imageFormat: Int,
        maxImages: Int,
        transport: Npv10P010ReaderTransportPlan?
    ): ImageReader {
        if (imageFormat == ImageFormat.YCBCR_P010) {
            requireNotNull(transport) { "P010 reader requires a separate session transport plan" }
        }
        return ImageReader.newInstance(width, height, imageFormat, maxImages)
    }

    fun matches(
        reader: ImageReader?,
        imageFormat: Int,
        transport: Npv10P010ReaderTransportPlan?
    ): Boolean {
        if (reader == null) return false
        if (imageFormat == ImageFormat.YCBCR_P010 && transport == null) {
            return false
        }
        return reader.imageFormat == imageFormat
    }
}
