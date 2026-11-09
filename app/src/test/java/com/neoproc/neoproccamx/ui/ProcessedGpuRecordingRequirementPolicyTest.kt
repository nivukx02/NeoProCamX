package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedGpuRecordingRequirementPolicyTest {

    @Test
    fun `sensor linear offscreen recording uses successful encoder submissions as authority`() {
        val evidence = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
            genericFrameImportEnabled = false,
            genericEncoderSurfaceBound = true,
            sessionImportedFrames = 32L,
            offscreenPathActive = true,
            offscreenEncoderSurfaceBound = true,
            offscreenSubmittedFrames = 32L,
            offscreenPresentationTimeSet = true
        )

        assertTrue(evidence.frameImportEnabled)
        assertTrue(evidence.encoderSurfaceBound)
        assertTrue(evidence.importedFrames == 32L)
    }

    @Test
    fun `offscreen recording cannot borrow generic preview evidence`() {
        val noSubmission = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
            genericFrameImportEnabled = true,
            genericEncoderSurfaceBound = true,
            sessionImportedFrames = 120L,
            offscreenPathActive = true,
            offscreenEncoderSurfaceBound = true,
            offscreenSubmittedFrames = 0L,
            offscreenPresentationTimeSet = true
        )
        val noPresentationTimestamp = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
            genericFrameImportEnabled = true,
            genericEncoderSurfaceBound = true,
            sessionImportedFrames = 120L,
            offscreenPathActive = true,
            offscreenEncoderSurfaceBound = true,
            offscreenSubmittedFrames = 32L,
            offscreenPresentationTimeSet = false
        )
        val unboundSurface = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
            genericFrameImportEnabled = true,
            genericEncoderSurfaceBound = true,
            sessionImportedFrames = 120L,
            offscreenPathActive = true,
            offscreenEncoderSurfaceBound = false,
            offscreenSubmittedFrames = 32L,
            offscreenPresentationTimeSet = true
        )

        assertFalse(noSubmission.frameImportEnabled)
        assertFalse(noSubmission.encoderSurfaceBound)
        assertTrue(noSubmission.importedFrames == 0L)
        assertFalse(noPresentationTimestamp.frameImportEnabled)
        assertFalse(noPresentationTimestamp.encoderSurfaceBound)
        assertTrue(noPresentationTimestamp.importedFrames == 32L)
        assertFalse(unboundSurface.frameImportEnabled)
        assertFalse(unboundSurface.encoderSurfaceBound)
        assertTrue(unboundSurface.importedFrames == 32L)
    }

    @Test
    fun `generic processed route retains generic import evidence`() {
        val evidence = ProcessedGpuRecordingRequirementPolicy.resolveFrameEvidence(
            genericFrameImportEnabled = true,
            genericEncoderSurfaceBound = true,
            sessionImportedFrames = 7L,
            offscreenPathActive = false,
            offscreenEncoderSurfaceBound = false,
            offscreenSubmittedFrames = 0L,
            offscreenPresentationTimeSet = false
        )

        assertTrue(evidence.frameImportEnabled)
        assertTrue(evidence.encoderSurfaceBound)
        assertTrue(evidence.importedFrames == 7L)
    }

    @Test
    fun `processed modes allow stable preview parity and block direct camera2 fallback`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.SDR,
                feedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                vulkanRuntimeSupported = true
            ).allowed
        )
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.SDR,
                feedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
                vulkanRuntimeSupported = true
            ).allowed
        )
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.LOG,
                feedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
                vulkanRuntimeSupported = true
            ).allowed
        )
        assertFalse(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.HDR,
                feedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                vulkanRuntimeSupported = false
            ).allowed
        )
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.RAW,
                feedPath = RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE,
                vulkanRuntimeSupported = false
            ).allowed
        )
    }

    @Test
    fun `strict normal pro sdr blocks camera2 preview parity without processed gpu route`() {
        val decision = ProcessedGpuRecordingRequirementPolicy.canStart(
            deliveryKind = VideoDeliveryKind.SDR,
            feedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            vulkanRuntimeSupported = false,
            strictProcessedGpuRequired = true
        )

        assertFalse(decision.allowed)
        assertTrue(decision.failureMessage.orEmpty().contains("strict", ignoreCase = true))
        assertTrue(decision.failureMessage.orEmpty().contains("processed GPU"))
    }

    @Test
    fun `strict normal pro sdr allows p010 bytebuffer and validated gpu routes`() {
        listOf(
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER
        ).forEach { feedPath ->
            assertTrue(
                "feedPath=$feedPath",
                ProcessedGpuRecordingRequirementPolicy.canStart(
                    deliveryKind = VideoDeliveryKind.SDR,
                    feedPath = feedPath,
                    vulkanRuntimeSupported = true,
                    strictProcessedGpuRequired = true
                ).allowed
            )
        }
    }

    @Test
    fun `strict hybrid p010 requirement blocks non offscreen processed routes`() {
        listOf(
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
        ).forEach { feedPath ->
            val decision = ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.SDR,
                feedPath = feedPath,
                vulkanRuntimeSupported = true,
                strictHybridP010Required = true
            )

            assertFalse("feedPath=$feedPath", decision.allowed)
            assertTrue(decision.failureMessage.orEmpty().contains("P010"))
        }

        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.canStart(
                deliveryKind = VideoDeliveryKind.LOG,
                feedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
                vulkanRuntimeSupported = false,
                strictHybridP010Required = true
            ).allowed
        )
    }

    @Test
    fun `processed route is proven only after imported frames reach bound encoder`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.validateRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 1L
            ).validated
        )
        assertFalse(
            ProcessedGpuRecordingRequirementPolicy.validateRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = false,
                importedFrames = 1L
            ).validated
        )
        assertFalse(
            ProcessedGpuRecordingRequirementPolicy.validateRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 0L
            ).validated
        )
    }

    @Test
    fun `processed route requires sustained import fps before it is trusted for recording`() {
        val lowThroughput = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunning(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 4L,
            firstSuccessfulImportElapsedMs = 1_000L,
            lastSuccessfulImportElapsedMs = 2_500L,
            currentElapsedRealtimeMs = 2_500L,
            targetFps = 30
        )
        assertFalse(lowThroughput.validated)
        assertTrue(ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(lowThroughput.failureMessage))

        val underSelectedFps = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunning(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 34L,
            firstSuccessfulImportElapsedMs = 1_000L,
            lastSuccessfulImportElapsedMs = 2_500L,
            currentElapsedRealtimeMs = 2_500L,
            targetFps = 30
        )
        assertFalse(underSelectedFps.validated)
        assertTrue(ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(underSelectedFps.failureMessage))

        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.validateSustainedRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 45L,
                firstSuccessfulImportElapsedMs = 1_000L,
                lastSuccessfulImportElapsedMs = 2_500L,
                currentElapsedRealtimeMs = 2_500L,
                targetFps = 30
            ).validated
        )
    }

    @Test
    fun `processed route rejects burst frames that stall before sustained validation completes`() {
        val burstOnly = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunning(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 2L,
            firstSuccessfulImportElapsedMs = 1_000L,
            lastSuccessfulImportElapsedMs = 1_005L,
            currentElapsedRealtimeMs = 2_600L,
            targetFps = 30
        )

        assertFalse(burstOnly.validated)
        assertTrue(ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(burstOnly.failureMessage))
    }

    @Test
    fun `processed route validates target fps on rolling sustained window after egl warmup`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.validateSustainedRunningWindow(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 59L,
                importedFramesAtWindowStart = 5L,
                windowStartElapsedMs = 1_500L,
                lastSuccessfulImportElapsedMs = 3_500L,
                currentElapsedRealtimeMs = 3_500L,
                targetFps = 25
            ).validated
        )

        val slowWindow = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunningWindow(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 50L,
            importedFramesAtWindowStart = 5L,
            windowStartElapsedMs = 1_500L,
            lastSuccessfulImportElapsedMs = 3_500L,
            currentElapsedRealtimeMs = 3_500L,
            targetFps = 25
        )

        assertFalse(slowWindow.validated)
        assertTrue(ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(slowWindow.failureMessage))
    }

    @Test
    fun `processed route rolling sustained window does not validate before observation duration`() {
        val warmup = ProcessedGpuRecordingRequirementPolicy.validateSustainedRunningWindow(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 34L,
            importedFramesAtWindowStart = 5L,
            windowStartElapsedMs = 1_500L,
            lastSuccessfulImportElapsedMs = 2_500L,
            currentElapsedRealtimeMs = 2_500L,
            targetFps = 25
        )

        assertFalse(warmup.validated)
        assertFalse(ProcessedGpuRecordingRequirementPolicy.isThroughputFailure(warmup.failureMessage))
    }

    @Test
    fun `processed route startup accepts live hardware import continuity during warmup`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.validateStartupContinuity(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 19L,
                lastSuccessfulImportElapsedMs = 6_641_866L,
                currentElapsedRealtimeMs = 6_641_900L
            ).validated
        )
    }

    @Test
    fun `hardware import session wait leaves room for callback jitter after activity proof`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.INTERNAL_HARDWARE_IMPORT_SESSION_TIMEOUT_MS >=
                ProcessedGpuRecordingRequirementPolicy.HARDWARE_IMPORT_ACTIVITY_WAIT_MS * 4
        )
    }

    @Test
    fun `strict offscreen validation leaves room for codec and p010 source warmup`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.SUSTAINED_IMPORTED_FRAME_TIMEOUT_MS >=
                ProcessedGpuRecordingRequirementPolicy.SUSTAINED_IMPORT_OBSERVATION_MS * 5
        )
    }

    @Test
    fun `processed route startup accepts near target live import cadence`() {
        assertTrue(
            ProcessedGpuRecordingRequirementPolicy.validateNearTargetStartupRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 102L,
                firstSuccessfulImportElapsedMs = 303_742_276L,
                lastSuccessfulImportElapsedMs = 303_745_848L,
                currentElapsedRealtimeMs = 303_745_900L,
                targetFps = 30
            ).validated
        )

        assertFalse(
            ProcessedGpuRecordingRequirementPolicy.validateNearTargetStartupRunning(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 48L,
                firstSuccessfulImportElapsedMs = 303_742_276L,
                lastSuccessfulImportElapsedMs = 303_745_848L,
                currentElapsedRealtimeMs = 303_745_900L,
                targetFps = 30
            ).validated
        )
    }

    @Test
    fun `processed route startup rejects stale hardware import continuity`() {
        assertFalse(
            ProcessedGpuRecordingRequirementPolicy.validateStartupContinuity(
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                importedFrames = 19L,
                lastSuccessfulImportElapsedMs = 6_641_000L,
                currentElapsedRealtimeMs = 6_642_500L
            ).validated
        )
    }
}
