package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingP010ImageIngressTelemetryTest {

    @Test
    fun admissionCapacityCoversObservedTransientStallAndRemainsBounded() {
        assertEquals(27, RecordingManager.resolveP010ImageInFlightCapacity(fps = 24))
        assertEquals(32, RecordingManager.resolveP010ImageInFlightCapacity(fps = 30))
        assertEquals(32, RecordingManager.resolveP010ImageInFlightCapacity(fps = 60))
        assertEquals(32, RecordingManager.resolveP010ImageInFlightCapacity(fps = 120))
    }

    @Test
    fun thirtyFpsAdmissionAbsorbsStrictReaderBurstAndTruthfullyRejectsOverflow() {
        val capacity = RecordingManager.resolveP010ImageInFlightCapacity(fps = 30)
        val telemetry = RecordingManager.P010ImageIngressTelemetry(
            maxInFlightProvider = { capacity }
        )

        repeat(32) {
            assertTrue(telemetry.tryAcceptCallback())
        }
        assertFalse(telemetry.tryAcceptCallback())

        repeat(32) {
            telemetry.recordQueued()
            telemetry.release()
        }

        val snapshot = telemetry.snapshot(encodedFrames = 32)
        assertEquals(32, snapshot.admissionCapacity)
        assertEquals(32, snapshot.peakInFlightFrames)
        assertEquals(33L, snapshot.callbackFrames)
        assertEquals(32L, snapshot.acceptedFrames)
        assertEquals(1L, snapshot.admissionRejectedFrames)
        assertEquals(32L, snapshot.queuedFrames)
        assertEquals(0L, snapshot.callbackAccountingGap)
        assertEquals(0L, snapshot.acceptedAccountingGap)
    }

    @Test
    fun cleanP010SourceAlignedCadenceUsesQueuedFrameCountForCertificateExpectation() {
        val snapshot = RecordingManager.P010ImageIngressSnapshot(
            callbackFrames = 333,
            acceptedFrames = 333,
            queuedFrames = 333,
            encodedFrames = 333,
            admissionRejectedFrames = 0,
            processingDroppedFrames = 0,
            inFlightFrames = 0
        )

        assertEquals(
            333L,
            RecordingManager.expectedP010FrameCountForEvidence(
                nominalExpectedFrameCount = 340,
                ingressSnapshot = snapshot
            )
        )
        assertEquals(0L, RecordingManager.p010DroppedFramesForEvidence(snapshot))
    }

    @Test
    fun p010AdmissionDropsRemainBlockingDroppedFrameEvidence() {
        val snapshot = RecordingManager.P010ImageIngressSnapshot(
            callbackFrames = 368,
            acceptedFrames = 364,
            queuedFrames = 364,
            encodedFrames = 364,
            admissionRejectedFrames = 4,
            processingDroppedFrames = 0,
            inFlightFrames = 0
        )

        assertEquals(
            340L,
            RecordingManager.expectedP010FrameCountForEvidence(
                nominalExpectedFrameCount = 340,
                ingressSnapshot = snapshot
            )
        )
        assertEquals(4L, RecordingManager.p010DroppedFramesForEvidence(snapshot))
    }

    @Test
    fun starvedP010SourceStillRequiresNominalCertificateFrameCount() {
        val snapshot = RecordingManager.P010ImageIngressSnapshot(
            callbackFrames = 2,
            acceptedFrames = 2,
            queuedFrames = 2,
            encodedFrames = 2,
            admissionRejectedFrames = 0,
            processingDroppedFrames = 0,
            inFlightFrames = 0
        )

        assertEquals(
            340L,
            RecordingManager.expectedP010FrameCountForEvidence(
                nominalExpectedFrameCount = 340,
                ingressSnapshot = snapshot
            )
        )
    }

    @Test
    fun copyFreeLinearBlockAdmissionRequiresP010AndCodecCompatibility() {
        assertTrue(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 36,
                p010Input = true,
                codecCopyFreeCompatible = true,
                codecName = "c2.android.hevc.encoder"
            )
        )
        assertFalse(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 29,
                p010Input = true,
                codecCopyFreeCompatible = true,
                codecName = "c2.android.hevc.encoder"
            )
        )
        assertFalse(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 36,
                p010Input = false,
                codecCopyFreeCompatible = true,
                codecName = "c2.android.hevc.encoder"
            )
        )
        assertFalse(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 36,
                p010Input = true,
                codecCopyFreeCompatible = false,
                codecName = "c2.android.hevc.encoder"
            )
        )
    }

    @Test
    fun qtiHevcEncoderUsesLegacyP010DespiteCopyFreeClaim() {
        assertFalse(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 36,
                p010Input = true,
                codecCopyFreeCompatible = true,
                codecName = "c2.qti.hevc.encoder"
            )
        )
        assertFalse(
            RecordingManager.shouldUseP010LinearBlockAdmission(
                sdkInt = 36,
                p010Input = true,
                codecCopyFreeCompatible = true,
                codecName = "C2.QTI.HEVC.ENCODER.SECURE"
            )
        )
    }

    @Test
    fun blockModelFailureLatchRejectsFurtherQueueAndResetsPerSession() {
        val failureState = RecordingManager.P010BlockModelFailureState()

        assertTrue(failureState.canQueue())
        assertTrue(failureState.markFailed())
        assertFalse(failureState.markFailed())
        assertFalse(failureState.canQueue())

        failureState.reset()
        assertTrue(failureState.canQueue())
    }

    @Test
    fun sustainedSourceCanContinueQueueingWithoutRetainingCameraImages() {
        val telemetry = RecordingManager.P010ImageIngressTelemetry(
            maxInFlightProvider = { 2 }
        )

        repeat(247) {
            assertTrue(telemetry.tryAcceptCallback())
            telemetry.recordQueued()
            telemetry.release()
        }

        val snapshot = telemetry.snapshot(encodedFrames = 247)
        assertEquals(247L, snapshot.callbackFrames)
        assertEquals(247L, snapshot.acceptedFrames)
        assertEquals(247L, snapshot.queuedFrames)
        assertEquals(247L, snapshot.encodedFrames)
        assertEquals(0L, snapshot.admissionRejectedFrames)
        assertEquals(0L, snapshot.processingDroppedFrames)
        assertEquals(0, snapshot.inFlightFrames)
        assertEquals(
            RecordingManager.P010IngressHealth.FLOWING,
            snapshot.health(minSustainedCallbacks = 30)
        )
    }

    @Test
    fun saturationIsVisibleAndRecoversAfterAnOwnedImageCloses() {
        val telemetry = RecordingManager.P010ImageIngressTelemetry(
            maxInFlightProvider = { 2 }
        )

        assertTrue(telemetry.tryAcceptCallback())
        assertTrue(telemetry.tryAcceptCallback())
        repeat(245) {
            assertFalse(telemetry.tryAcceptCallback())
        }

        telemetry.recordQueued()
        telemetry.release()
        assertTrue(telemetry.tryAcceptCallback())
        telemetry.recordProcessingDrop()
        telemetry.release()
        telemetry.recordQueued()
        telemetry.release()

        val snapshot = telemetry.snapshot(encodedFrames = 2)
        assertEquals(248L, snapshot.callbackFrames)
        assertEquals(3L, snapshot.acceptedFrames)
        assertEquals(245L, snapshot.admissionRejectedFrames)
        assertEquals(2L, snapshot.queuedFrames)
        assertEquals(1L, snapshot.processingDroppedFrames)
        assertEquals(0L, snapshot.callbackAccountingGap)
        assertEquals(0L, snapshot.acceptedAccountingGap)
        assertEquals(
            RecordingManager.P010IngressHealth.BACKPRESSURE_VISIBLE,
            snapshot.health(minSustainedCallbacks = 30)
        )
    }

    @Test
    fun sustainedCallbacksWithOneQueuedFrameCannotBeReportedAsHealthy() {
        val snapshot = RecordingManager.P010ImageIngressSnapshot(
            callbackFrames = 247,
            acceptedFrames = 247,
            queuedFrames = 1,
            encodedFrames = 1,
            admissionRejectedFrames = 0,
            processingDroppedFrames = 0,
            inFlightFrames = 0
        )

        assertEquals(
            RecordingManager.P010IngressHealth.QUEUE_STALLED,
            snapshot.health(minSustainedCallbacks = 30)
        )
        assertEquals(246L, snapshot.acceptedAccountingGap)
    }

    @Test
    fun copyAndCodecWaitStagesRemainSeparatelyTruthful() {
        val telemetry = RecordingManager.P010ImageIngressTelemetry(
            maxInFlightProvider = { 2 }
        )

        assertTrue(telemetry.tryAcceptCallback())
        telemetry.recordCodecInputWait(durationNs = 4_000_000L)
        telemetry.recordCopy(
            path = Npv10FramePacker.P010CopyPath.NATIVE_DIRECT,
            durationNs = 6_000_000L
        )
        telemetry.recordQueued()
        telemetry.release()

        assertTrue(telemetry.tryAcceptCallback())
        telemetry.recordCodecInputWait(durationNs = 10_000_000L)
        telemetry.recordCopy(
            path = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER,
            durationNs = 8_000_000L
        )
        telemetry.recordQueued()
        telemetry.release()

        val snapshot = telemetry.snapshot(encodedFrames = 2)
        assertEquals(1L, snapshot.nativeDirectCopies)
        assertEquals(1L, snapshot.directBufferCopies)
        assertEquals(0L, snapshot.copyFailures)
        assertEquals(7_000L, snapshot.averageCopyUs)
        assertEquals(8_000L, snapshot.maxCopyUs)
        assertEquals(7_000L, snapshot.averageCodecInputWaitUs)
        assertEquals(10_000L, snapshot.maxCodecInputWaitUs)
    }

    @Test
    fun p010EvidenceDurationUsesSourceCallbackIntervalWithoutHidingRejectedFrames() {
        val telemetry = RecordingManager.P010ImageIngressTelemetry(
            maxInFlightProvider = { 2 }
        )

        repeat(311) { index ->
            val accepted = telemetry.tryAcceptCallback(
                sourceTimestampNs =
                    2_000_000_000L + index * 10_000_000_000L / 310L
            )
            if (index < 215) {
                assertTrue(accepted)
                telemetry.release()
            } else if (index < 217) {
                assertTrue(accepted)
            } else {
                assertFalse(accepted)
            }
        }
        telemetry.release()
        telemetry.release()

        val snapshot = telemetry.snapshot(encodedFrames = 217)
        assertEquals(311L, snapshot.callbackFrames)
        assertEquals(217L, snapshot.acceptedFrames)
        assertEquals(94L, snapshot.admissionRejectedFrames)
        assertEquals(
            10_033_333L,
            RecordingManager.effectiveP010EvidenceDurationUs(
                wallActiveDurationUs = 12_000_000L,
                fps = 30,
                ingressSnapshot = snapshot
            )
        )
    }
}
