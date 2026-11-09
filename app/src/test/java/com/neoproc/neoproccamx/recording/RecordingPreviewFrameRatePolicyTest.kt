package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPreviewFrameRatePolicyTest {

    private inline fun withHighEndQuality(enabled: Boolean, block: () -> Unit) {
        val previous = HighEndQualityPolicy.enabled
        HighEndQualityPolicy.enabled = enabled
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previous
        }
    }

    @Test
    fun `high-end standard recording keeps 60fps when requested and supported`() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingPreviewFrameRatePolicy.resolve(
                selection = null,
                requestedFps = 60
            )
            assertEquals(60, plan.previewFps)
            assertEquals(60, plan.encoderFps)
        }
    }

    @Test
    fun `standard recording preserves 24fps cinema baseline`() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingPreviewFrameRatePolicy.resolve(
                selection = null,
                requestedFps = 24
            )
            assertEquals(24, plan.previewFps)
            assertEquals(24, plan.encoderFps)
        }
    }

    @Test
    fun `standard recording preserves requested 30fps broadcast cadence`() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingPreviewFrameRatePolicy.resolve(
                selection = null,
                requestedFps = 30
            )
            assertEquals(30, plan.previewFps)
            assertEquals(30, plan.encoderFps)
        }
    }

    @Test
    fun `standard recording snaps non-cinema fps to nearest baseline`() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingPreviewFrameRatePolicy.resolve(
                selection = null,
                requestedFps = 26
            )
            assertEquals(25, plan.previewFps)
            assertEquals(25, plan.encoderFps)
        }
    }

    @Test
    fun `ultra high resolution encoder caps to 25 in non high-end mode`() {
        withHighEndQuality(enabled = false) {
            val encoderFps = RecordingPreviewFrameRatePolicy.resolveEncoderFpsForNormalizedWidth(
                normalizedMaxWidth = 7680,
                requestedFps = 60
            )
            assertEquals(25, encoderFps)
        }
    }

    @Test
    fun `strict high-end ultra resolution obeys product 25fps ceiling`() {
        withHighEndQuality(enabled = true) {
            val encoderFps = RecordingPreviewFrameRatePolicy.resolveEncoderFpsForNormalizedWidth(
                normalizedMaxWidth = 7680,
                requestedFps = 60
            )
            assertEquals(25, encoderFps)
        }
    }

    @Test
    fun `strict high-end 4k recording rejects downstream cadence above product ceiling`() {
        withHighEndQuality(enabled = true) {
            val encoderFps = RecordingPreviewFrameRatePolicy.resolveEncoderFpsForNormalizedWidth(
                normalizedMaxWidth = 3840,
                requestedFps = 60
            )
            assertEquals(25, encoderFps)
        }
    }

    @Test
    fun `strict high-end 4k recording cannot bypass the 25fps product ceiling`() {
        withHighEndQuality(enabled = true) {
            val encoderFps = RecordingPreviewFrameRatePolicy.resolveEncoderFpsForNormalizedWidth(
                normalizedMaxWidth = 3840,
                requestedFps = 30
            )
            assertEquals(25, encoderFps)
        }
    }

    @Test
    fun `4k recording preserves explicit 24fps`() {
        withHighEndQuality(enabled = true) {
            val encoderFps = RecordingPreviewFrameRatePolicy.resolveEncoderFpsForNormalizedWidth(
                normalizedMaxWidth = 4096,
                requestedFps = 24
            )
            assertEquals(24, encoderFps)
        }
    }

    @Test
    fun `recording contract caps slow motion requests at 60fps`() {
        withHighEndQuality(enabled = true) {
            val plan = RecordingPreviewFrameRatePolicy.resolve(
                selection = null,
                requestedFps = 120
            )
            assertEquals(60, plan.previewFps)
            assertEquals(60, plan.encoderFps)
        }
    }

    @Test
    fun `supported cinema snap promotes 58fps to 60fps in high-end mode`() {
        withHighEndQuality(enabled = true) {
            val snapped = RecordingPreviewFrameRatePolicy.snapToSupportedCinemaFps(
                requestedFps = 58,
                minFps = 24,
                maxFps = 60
            )
            assertEquals(60, snapped)
        }
    }

    @Test
    fun `supported cinema snap keeps raw fps when high-end is disabled`() {
        withHighEndQuality(enabled = false) {
            val snapped = RecordingPreviewFrameRatePolicy.snapToSupportedCinemaFps(
                requestedFps = 58,
                minFps = 24,
                maxFps = 60
            )
            assertEquals(58, snapped)
        }
    }

    @Test
    fun `supported cinema snap falls back to clamped request when no cinema target fits`() {
        withHighEndQuality(enabled = true) {
            val snapped = RecordingPreviewFrameRatePolicy.snapToSupportedCinemaFps(
                requestedFps = 58,
                minFps = 55,
                maxFps = 59
            )
            assertEquals(58, snapped)
        }
    }
}
