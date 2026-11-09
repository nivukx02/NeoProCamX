package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionFrameQualityPolicyTest {
    @Test
    fun `all detail profiles remain record-develop quality choices`() {
        val clean = VideoNoiseMetrics.EMPTY
        val natural = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.SDR, VideoDetailProfile.NATURAL_DETAIL, clean
        )
        val focused = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.SDR, VideoDetailProfile.FOCUSED_DETAIL, clean
        )
        val extreme = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.SDR, VideoDetailProfile.EXTREME_DETAIL, clean
        )
        assertTrue(natural.sharpen < focused.sharpen)
        assertTrue(focused.sharpen < extreme.sharpen)
    }

    @Test
    fun `noisy unstable frame receives more denoise and less sharpen`() {
        val clean = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.HDR, VideoDetailProfile.FOCUSED_DETAIL, VideoNoiseMetrics.EMPTY
        )
        val noisy = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.HDR,
            VideoDetailProfile.FOCUSED_DETAIL,
            VideoNoiseMetrics(0.8f, 0.7f, 0.9f, 0.8f)
        )
        assertTrue(noisy.denoise > clean.denoise)
        assertTrue(noisy.sharpen < clean.sharpen)
    }
}
