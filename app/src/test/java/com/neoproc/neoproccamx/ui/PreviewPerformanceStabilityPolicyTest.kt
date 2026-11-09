package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPerformanceStabilityPolicyTest {

    @Test
    fun `idle video low fps remains eligible for hysteretic recovery retune`() {
        var state = PreviewPerformanceState()
        var decision: PreviewPerformanceDecision? = null
        repeat(3) { index ->
            decision = PreviewPerformanceStabilityPolicy.evaluate(
                state = state,
                measuredPreviewFps = 45f,
                requestedPreviewFps = 60,
                videoMode = true,
                recordingActive = false,
                requestedOutputLongEdge = 3840,
                thermalGuardActive = false,
                nowElapsedMs = 5_000L + index * 4_000L
            )
            state = requireNotNull(decision).state
        }
        assertEquals(PreviewPerformanceProfile.STABILIZED, state.profile)
        assertTrue(requireNotNull(decision).shouldRefreshPreviewPipeline)
    }

    @Test
    fun `recording low fps enters stabilized profile after sustained samples`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(),
            measuredPreviewFps = 50f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 49f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 10_000L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 48f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 15_000L
        )

        assertEquals(PreviewPerformanceProfile.STABILIZED, third.state.profile)
        assertEquals(1920, third.limits.previewMaxLongEdge)
        assertEquals(1920 * 1080, third.limits.previewMaxArea)
        assertEquals(640, third.limits.analysisMaxLongEdge)
        assertTrue(second.shouldRefreshPreviewPipeline)
        assertFalse(third.shouldRefreshPreviewPipeline)
    }

    @Test
    fun `recording low fps enters stabilized profile after two video samples`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(),
            measuredPreviewFps = 50f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 49f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 10_000L
        )

        assertEquals(PreviewPerformanceProfile.STABILIZED, second.state.profile)
        assertEquals(1920, second.limits.previewMaxLongEdge)
        assertEquals(640, second.limits.analysisMaxLongEdge)
        assertTrue(second.shouldRefreshPreviewPipeline)
    }

    @Test
    fun `default high resolution recording uses lighter analysis proxy without lowering visible grade`() {
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 4096
        )

        assertNull(limits.previewMaxLongEdge)
        assertNull(limits.previewMaxArea)
        assertEquals(1280, limits.analysisMaxLongEdge)
        assertFalse(limits.highLoadPreviewMode)
    }

    @Test
    fun `stabilized severe recording lag escalates to aggressive after two video samples`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(
                profile = PreviewPerformanceProfile.STABILIZED,
                lastProfileChangeElapsedMs = 1_000L
            ),
            measuredPreviewFps = 41f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 40f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680,
            thermalGuardActive = false,
            nowElapsedMs = 9_000L
        )

        assertEquals(PreviewPerformanceProfile.AGGRESSIVE, second.state.profile)
        assertEquals(1600, second.limits.previewMaxLongEdge)
        assertEquals(576, second.limits.analysisMaxLongEdge)
        assertTrue(second.shouldRefreshPreviewPipeline)
    }

    @Test
    fun `ultra high resolution severe low fps escalates from stabilized to aggressive`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(
                profile = PreviewPerformanceProfile.STABILIZED,
                lastProfileChangeElapsedMs = 1_000L
            ),
            measuredPreviewFps = 41f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 40f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680,
            thermalGuardActive = false,
            nowElapsedMs = 9_000L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 39f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680,
            thermalGuardActive = false,
            nowElapsedMs = 13_000L
        )

        assertEquals(PreviewPerformanceProfile.AGGRESSIVE, third.state.profile)
        assertEquals(1600, third.limits.previewMaxLongEdge)
        assertEquals(1600 * 900, third.limits.previewMaxArea)
        assertEquals(576, third.limits.analysisMaxLongEdge)
    }

    @Test
    fun `recording low fps does not jump directly to aggressive profile`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(),
            measuredPreviewFps = 49f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 48f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 9_000L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 47f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = false,
            nowElapsedMs = 13_000L
        )

        assertEquals(PreviewPerformanceProfile.STABILIZED, third.state.profile)
        assertTrue(second.shouldRefreshPreviewPipeline)
        assertFalse(third.shouldRefreshPreviewPipeline)
        assertEquals(1920, third.limits.previewMaxLongEdge)
        assertEquals(640, third.limits.analysisMaxLongEdge)
    }

    @Test
    fun `healthy fps recovers aggressive profile in two deliberate steps for active high resolution recording`() {
        val state = PreviewPerformanceState(
            profile = PreviewPerformanceProfile.AGGRESSIVE,
            lastProfileChangeElapsedMs = 1_000L
        )
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = state,
            measuredPreviewFps = 59f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 59.5f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 9_000L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 60f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 13_000L
        )
        val fourth = PreviewPerformanceStabilityPolicy.evaluate(
            state = third.state,
            measuredPreviewFps = 60f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 17_000L
        )
        val fifth = PreviewPerformanceStabilityPolicy.evaluate(
            state = fourth.state,
            measuredPreviewFps = 60f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 21_000L
        )
        val sixth = PreviewPerformanceStabilityPolicy.evaluate(
            state = fifth.state,
            measuredPreviewFps = 60f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 25_000L
        )
        val seventh = PreviewPerformanceStabilityPolicy.evaluate(
            state = sixth.state,
            measuredPreviewFps = 60f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 5760,
            thermalGuardActive = false,
            nowElapsedMs = 29_000L
        )

        assertEquals(PreviewPerformanceProfile.DEFAULT, seventh.state.profile)
        assertNull(seventh.limits.previewMaxLongEdge)
        assertEquals(1280, seventh.limits.analysisMaxLongEdge)
        assertFalse(seventh.limits.highLoadPreviewMode)
    }

    @Test
    fun `default limits keep preview uncapped and full grade for 4k video`() {
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = true,
            recordingActive = false,
            requestedOutputLongEdge = 3840
        )

        assertNull(limits.previewMaxLongEdge)
        assertNull(limits.previewMaxArea)
        assertNull(limits.analysisMaxLongEdge)
        assertFalse(limits.highLoadPreviewMode)
    }

    @Test
    fun `default recording limits keep display resolved preview until measured degradation`() {
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 4096
        )

        assertNull(limits.previewMaxLongEdge)
        assertNull(limits.previewMaxArea)
        assertEquals(1280, limits.analysisMaxLongEdge)
        assertFalse(limits.highLoadPreviewMode)
    }

    @Test
    fun `default normal resolution recording keeps visible preview out of high load grade`() {
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 1920
        )

        assertNull(limits.previewMaxLongEdge)
        assertNull(limits.previewMaxArea)
        assertEquals(1280, limits.analysisMaxLongEdge)
        assertFalse(limits.highLoadPreviewMode)
    }

    @Test
    fun `default recording limits leave ultra preview display resolved while retaining analysis proxy`() {
        val limits = PreviewPerformanceStabilityPolicy.resolveLimits(
            profile = PreviewPerformanceProfile.DEFAULT,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 7680
        )

        assertNull(limits.previewMaxLongEdge)
        assertNull(limits.previewMaxArea)
        assertEquals(1280, limits.analysisMaxLongEdge)
    }

    @Test
    fun `thermal guard still allows profile escalation when preview fps drops`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(),
            measuredPreviewFps = 50f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = false,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = true,
            nowElapsedMs = 1_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 49f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = false,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = true,
            nowElapsedMs = 1_500L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 48f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = false,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = true,
            nowElapsedMs = 2_000L
        )
        val fourth = PreviewPerformanceStabilityPolicy.evaluate(
            state = third.state,
            measuredPreviewFps = 47f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = false,
            requestedOutputLongEdge = 3840,
            thermalGuardActive = true,
            nowElapsedMs = 2_500L
        )

        assertEquals(PreviewPerformanceProfile.STABILIZED, fourth.state.profile)
        assertEquals(1920, fourth.limits.previewMaxLongEdge)
        assertTrue(third.shouldRefreshPreviewPipeline)
        assertFalse(fourth.shouldRefreshPreviewPipeline)
    }

    @Test
    fun `video recording steps into stabilized before aggressive to avoid profile bouncing`() {
        val first = PreviewPerformanceStabilityPolicy.evaluate(
            state = PreviewPerformanceState(),
            measuredPreviewFps = 42f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 4096,
            thermalGuardActive = false,
            nowElapsedMs = 5_000L
        )
        val second = PreviewPerformanceStabilityPolicy.evaluate(
            state = first.state,
            measuredPreviewFps = 41f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 4096,
            thermalGuardActive = false,
            nowElapsedMs = 8_000L
        )
        val third = PreviewPerformanceStabilityPolicy.evaluate(
            state = second.state,
            measuredPreviewFps = 40f,
            requestedPreviewFps = 60,
            videoMode = true,
            recordingActive = true,
            requestedOutputLongEdge = 4096,
            thermalGuardActive = false,
            nowElapsedMs = 12_000L
        )

        assertEquals(PreviewPerformanceProfile.STABILIZED, third.state.profile)
        assertTrue(second.shouldRefreshPreviewPipeline)
        assertFalse(third.shouldRefreshPreviewPipeline)
    }
}
