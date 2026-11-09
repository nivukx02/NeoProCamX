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

    @Test
    fun `scene tuning scales remain bounded and reach resolved pixel controls`() {
        val metrics = VideoNoiseMetrics(0.2f, 0.25f, 0.3f, 0.2f)
        val baseline = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.SDR, VideoDetailProfile.FOCUSED_DETAIL, metrics
        )
        val tuned = ConversionFrameQualityPolicy.resolve(
            GpuSignalFamily.SDR,
            VideoDetailProfile.FOCUSED_DETAIL,
            metrics,
            sharpnessScale = 0.7f,
            denoiseScale = 1.25f
        )
        assertTrue(tuned.sharpen < baseline.sharpen)
        assertTrue(tuned.denoise > baseline.denoise)
        assertTrue(tuned.sharpen in 0.12f..0.72f)
        assertTrue(tuned.denoise in 0.24f..0.88f)
    }
}
