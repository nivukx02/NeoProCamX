package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.os.Build

/**
 * Keeps realtime surface encoding under one authoritative rate controller.
 *
 * A bitrate target and a hard quantizer ceiling are independent quality controllers. Some
 * Qualcomm encoders satisfy the quantizer ceiling first and exceed both `bitrate` and
 * `max-bitrate` by several times, which backpressures the input surface and loses capture cadence.
 * Strict realtime delivery therefore uses capability-bounded CBR first and never combines its
 * bitrate contract with a QP window. VBR remains a compatibility fallback, but the same single
 * controller rule applies so fallback cannot silently become near-lossless/CQ encoding.
 */
internal object RecordingRealtimeBitrateContractPolicy {
    fun preferredDirectGalleryModes(sdkInt: Int): List<Int> =
        if (sdkInt >= Build.VERSION_CODES.LOLLIPOP) {
            listOf(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        } else {
            emptyList()
        }

    fun permitsIndependentQuantizerWindow(strictBitrateTarget: Boolean): Boolean =
        !strictBitrateTarget
}
