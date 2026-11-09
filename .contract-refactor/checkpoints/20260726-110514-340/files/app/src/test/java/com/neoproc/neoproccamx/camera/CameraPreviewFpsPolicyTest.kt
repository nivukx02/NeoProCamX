package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraPreviewFpsPolicyTest {

    @Test
    fun `preview fps policy prefers fixed 60 over wider enclosing range`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(
                CameraIntRange(30, 60),
                CameraIntRange(60, 60),
                CameraIntRange(60, 120)
            )
        )

        assertEquals(CameraIntRange(60, 60), plan.resolvedRange)
        assertEquals(60, plan.appliedPreviewFps)
        assertEquals(60, plan.exposureBudgetFps)
    }

    @Test
    fun `preview fps policy keeps 60 exposure budget when only 30-60 exists`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(30, 60))
        )

        assertEquals(CameraIntRange(30, 60), plan.resolvedRange)
        assertEquals(60, plan.appliedPreviewFps)
        assertEquals(60, plan.exposureBudgetFps)
    }

    @Test
    fun `preview fps policy prefers higher floor when multiple ranges include target`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(
                CameraIntRange(24, 60),
                CameraIntRange(30, 120)
            )
        )

        assertEquals(CameraIntRange(30, 120), plan.resolvedRange)
        assertEquals(60, plan.appliedPreviewFps)
        assertEquals(60, plan.exposureBudgetFps)
    }

    @Test
    fun `preview fps policy normalizes milli-fps device ranges`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(15_000, 30_000))
        )

        assertEquals(CameraIntRange(15_000, 30_000), plan.resolvedRange)
        assertEquals(30, plan.appliedPreviewFps)
        assertEquals(30, plan.exposureBudgetFps)
    }

    @Test
    fun `preview fps policy falls back to device ceiling when 60 is unavailable`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = listOf(CameraIntRange(24, 30))
        )

        assertEquals(CameraIntRange(24, 30), plan.resolvedRange)
        assertEquals(30, plan.appliedPreviewFps)
        assertEquals(30, plan.exposureBudgetFps)
    }

    @Test
    fun `preview fps policy handles empty fps list`() {
        val plan = CameraPreviewFpsPolicy.resolve(
            requestedFps = 60,
            availableRanges = emptyList()
        )

        assertNull(plan.resolvedRange)
        assertEquals(60, plan.appliedPreviewFps)
    }

    @Test
    fun `preview slow motion path is capped at 60fps by the video contract`() {
        val plan = CameraPreviewFpsPolicy.resolveForSlowMotion(
            targetFps = 120,
            availableRanges = listOf(CameraIntRange(60, 120))
        )

        assertEquals(60, plan.requestedPreviewFps)
        assertEquals(60, plan.appliedPreviewFps)
        assertEquals(CinemaFrameRate.CINEMA_60, plan.cinemaFrameRate)
    }

    @Test
    fun `strict high end honors selected lower cinema frame rates`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val plan = CameraPreviewFpsPolicy.resolve(
                requestedFps = 24,
                availableRanges = listOf(
                    CameraIntRange(24, 24),
                    CameraIntRange(30, 30),
                    CameraIntRange(60, 60)
                )
            )

            assertEquals(24, plan.requestedPreviewFps)
            assertEquals(CameraIntRange(24, 24), plan.resolvedRange)
            assertEquals(24, plan.appliedPreviewFps)
            assertEquals(FrameRateStability.LOCKED, plan.stability)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
