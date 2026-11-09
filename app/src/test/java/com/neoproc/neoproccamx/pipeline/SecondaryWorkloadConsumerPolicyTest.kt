package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondaryWorkloadConsumerPolicyTest {

    @Test
    fun `recording retains full hardware import protection`() {
        val limit = SecondaryWorkloadConsumerPolicy.hardwareImportInFlightLimit(
            hardwareHighWatermark = 12,
            activeSubmitCount = 1,
            recordingActive = true,
            decision = decision(previewQueueLimit = 1)
        )

        assertEquals(12, limit)
    }

    @Test
    fun `idle preview applies adaptive queued frame limit`() {
        val limit = SecondaryWorkloadConsumerPolicy.hardwareImportInFlightLimit(
            hardwareHighWatermark = 12,
            activeSubmitCount = 1,
            recordingActive = false,
            decision = decision(previewQueueLimit = 2)
        )

        assertEquals(3, limit)
    }

    @Test
    fun `latest frame preference tightens preview age to one sensor interval`() {
        val relaxed = SecondaryWorkloadConsumerPolicy.previewMaxFrameAgeNs(
            targetFps = 50f,
            decision = decision(preferLatestPreviewFrame = false)
        )
        val latest = SecondaryWorkloadConsumerPolicy.previewMaxFrameAgeNs(
            targetFps = 50f,
            decision = decision(preferLatestPreviewFrame = true)
        )

        assertEquals(40_000_000L, relaxed)
        assertEquals(20_000_000L, latest)
    }

    @Test
    fun `analytics adaptation expands scene stride without undoing stronger routing backoff`() {
        assertEquals(
            4L,
            SecondaryWorkloadConsumerPolicy.analyticsStrideMultiplier(
                existingMultiplier = 2L,
                decision = decision(analyticsCadenceDivisor = 4)
            )
        )
        assertEquals(
            6L,
            SecondaryWorkloadConsumerPolicy.analyticsStrideMultiplier(
                existingMultiplier = 6L,
                decision = decision(analyticsCadenceDivisor = 4)
            )
        )
    }

    @Test
    fun `secondary cadence hint cannot rewrite sensor deadline fps`() {
        val pipeline = FramePipelineManager()
        try {
            val initialSensorFps = pipeline.sensorPipelineDiagnostics().targetFps

            pipeline.setRuntimePerformanceHint(previewFps = 5f, recording = true)

            assertEquals(initialSensorFps, pipeline.sensorPipelineDiagnostics().targetFps, 0.001)

            pipeline.setSensorTargetFps(60f)

            assertEquals(60.0, pipeline.sensorPipelineDiagnostics().targetFps, 0.001)
        } finally {
            pipeline.destroy()
        }
    }

    private fun decision(
        analyticsCadenceDivisor: Int = 1,
        previewQueueLimit: Int = 4,
        preferLatestPreviewFrame: Boolean = false
    ) = PipelineAdaptationDecision(
        level = SecondaryDegradationLevel.FULL,
        aiCadenceDivisor = 1,
        analyticsCadenceDivisor = analyticsCadenceDivisor,
        vfxCadenceDivisor = 1,
        previewQueueLimit = previewQueueLimit,
        aiQueueLimit = 3,
        vfxQueueLimit = 2,
        simplifyPreviewEffects = false,
        reducePreviewResolution = false,
        preferLatestPreviewFrame = preferLatestPreviewFrame,
        deferBackgroundVfx = false,
        reasons = setOf("test")
    )
}
