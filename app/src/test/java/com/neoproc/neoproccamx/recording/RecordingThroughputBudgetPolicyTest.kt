package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingThroughputBudgetPolicyTest {

    private fun snapshot(
        callbackFrames: Long,
        acceptedFrames: Long = callbackFrames,
        queuedFrames: Long = acceptedFrames,
        encodedFrames: Long = acceptedFrames,
        admissionRejectedFrames: Long = 0,
        processingDroppedFrames: Long = 0,
        nativeDirectCopies: Long = 0,
        directBufferCopies: Long = 0,
        copyFailures: Long = 0,
        averageCopyUs: Long = 0,
        maxCopyUs: Long = averageCopyUs
    ) = RecordingManager.P010ImageIngressSnapshot(
        callbackFrames = callbackFrames,
        acceptedFrames = acceptedFrames,
        queuedFrames = queuedFrames,
        encodedFrames = encodedFrames,
        admissionRejectedFrames = admissionRejectedFrames,
        processingDroppedFrames = processingDroppedFrames,
        inFlightFrames = 0,
        nativeDirectCopies = nativeDirectCopies,
        directBufferCopies = directBufferCopies,
        copyFailures = copyFailures,
        averageCopyUs = averageCopyUs,
        maxCopyUs = maxCopyUs
    )

    @Test
    fun cleanSustainedNativeSessionSustainsRequestedFps() {
        // 900 frames at 30fps, native copies well under the 33_333us cadence, zero drops.
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 900,
                nativeDirectCopies = 900,
                averageCopyUs = 6_000,
                maxCopyUs = 9_000
            ),
            requestedFps = 30
        )

        assertEquals(0L, verdict.droppedFrames)
        assertEquals(0.0, verdict.dropRatio, 0.0)
        assertTrue(verdict.dropRatioWithinTarget)
        assertEquals(33_333L, verdict.frameTimeBudgetUs)
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.WITHIN_BUDGET,
            verdict.copyBudgetVerdict
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyPathDiagnosis.NATIVE_HEALTHY,
            verdict.copyPathDiagnosis
        )
        assertEquals(30, verdict.sustainableFps)
        assertTrue(verdict.sustainsRequestedFps)
        assertEquals("Sustained 30fps within copy budget", verdict.honestCapLabel)
    }

    @Test
    fun realDeviceCopyBoundSessionIsCappedHonestlyNotSilentlyDropped() {
        // Reproduces the live _diag/camera_log_fps.txt reading: native path DID load
        // (nativeCopies=54, directCopies=0) but the readout averages ~331ms/frame — copy-bound.
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 1_800,
                acceptedFrames = 54,
                admissionRejectedFrames = 1_746,
                nativeDirectCopies = 54,
                averageCopyUs = 331_072,
                maxCopyUs = 402_000
            ),
            requestedFps = 30
        )

        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.COPY_BOUND,
            verdict.copyBudgetVerdict
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyPathDiagnosis.NATIVE_SLOW_READOUT,
            verdict.copyPathDiagnosis
        )
        // 1_000_000 / 331_072 = 3.02 -> honest 3fps cap, never the requested 30.
        assertEquals(3, verdict.sustainableFps)
        assertFalse(verdict.sustainsRequestedFps)
        assertFalse(verdict.dropRatioWithinTarget)
        assertTrue(verdict.honestCapLabel.contains("Capped to ~3fps (requested 30fps)"))
        assertTrue(verdict.honestCapLabel.contains("copy-bound sensor readout"))
        assertTrue(verdict.honestCapLabel.contains("331ms/frame"))
    }

    @Test
    fun nativeLibraryUnavailableFallsBackToDirectBufferAndSaysSo() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 600,
                acceptedFrames = 40,
                admissionRejectedFrames = 560,
                nativeDirectCopies = 0,
                directBufferCopies = 40,
                averageCopyUs = 120_000
            ),
            requestedFps = 30
        )

        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.COPY_BOUND,
            verdict.copyBudgetVerdict
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyPathDiagnosis.NATIVE_UNAVAILABLE_FALLBACK,
            verdict.copyPathDiagnosis
        )
        assertTrue(
            verdict.honestCapLabel.contains("native copy unavailable, using slow JVM fallback")
        )
    }

    @Test
    fun outrightCopyFailuresAreDiagnosedAsCorrectnessFaultNotJustSlowness() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 300,
                acceptedFrames = 10,
                admissionRejectedFrames = 290,
                nativeDirectCopies = 2,
                directBufferCopies = 1,
                copyFailures = 12,
                averageCopyUs = 40_000
            ),
            requestedFps = 30
        )

        assertEquals(
            RecordingThroughputBudgetPolicy.CopyPathDiagnosis.COPY_FAILURES_PRESENT,
            verdict.copyPathDiagnosis
        )
        assertTrue(verdict.honestCapLabel.contains("P010 copy failures"))
    }

    @Test
    fun noCopiesYetReportsNotMeasuredWithoutFabricatingAPass() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 0),
            requestedFps = 30
        )

        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.NO_COPY_DATA,
            verdict.copyBudgetVerdict
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyPathDiagnosis.NO_COPY_DATA,
            verdict.copyPathDiagnosis
        )
        assertEquals("Throughput not yet measured", verdict.honestCapLabel)
        // NO_COPY_DATA must not be reported as a healthy "sustains" pass.
        assertFalse(verdict.sustainsRequestedFps)
    }

    @Test
    fun dropRatioTargetBoundaryIsStrictlyUnderOnePercent() {
        // Exactly 1% must NOT pass (G1 requires strictly < 1%).
        val atOnePercent = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 100,
                acceptedFrames = 99,
                admissionRejectedFrames = 1,
                nativeDirectCopies = 99,
                averageCopyUs = 5_000
            ),
            requestedFps = 30
        )
        assertEquals(0.01, atOnePercent.dropRatio, 1e-9)
        assertFalse(atOnePercent.dropRatioWithinTarget)

        // Just under 1% passes.
        val underOnePercent = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 1_000,
                acceptedFrames = 991,
                admissionRejectedFrames = 9,
                nativeDirectCopies = 991,
                averageCopyUs = 5_000
            ),
            requestedFps = 30
        )
        assertTrue(underOnePercent.dropRatio < 0.01)
        assertTrue(underOnePercent.dropRatioWithinTarget)
        // Within budget + within target => sustains.
        assertTrue(underOnePercent.sustainsRequestedFps)
    }

    @Test
    fun dropRatioSumsAdmissionRejectsProcessingDropsAndCopyFailures() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 400,
                acceptedFrames = 380,
                admissionRejectedFrames = 12,
                processingDroppedFrames = 5,
                nativeDirectCopies = 377,
                copyFailures = 3,
                averageCopyUs = 5_000
            ),
            requestedFps = 30
        )
        // (12 + 5 + 3) / 400 = 0.05
        assertEquals(20L, verdict.droppedFrames)
        assertEquals(0.05, verdict.dropRatio, 1e-9)
        assertFalse(verdict.dropRatioWithinTarget)
    }

    @Test
    fun sixtyFpsBudgetIsTighterThanThirty() {
        // 20ms/frame copy: fine at 30fps (33.3ms budget), copy-bound at 60fps (16.6ms budget).
        val at30 = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 100, nativeDirectCopies = 100, averageCopyUs = 20_000),
            requestedFps = 30
        )
        val at60 = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 100, nativeDirectCopies = 100, averageCopyUs = 20_000),
            requestedFps = 60
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.WITHIN_BUDGET,
            at30.copyBudgetVerdict
        )
        assertEquals(
            RecordingThroughputBudgetPolicy.CopyBudgetVerdict.COPY_BOUND,
            at60.copyBudgetVerdict
        )
        assertEquals(16_666L, at60.frameTimeBudgetUs)
    }

    @Test
    fun degenerateFpsIsGuardedAgainstDivideByZero() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 10, nativeDirectCopies = 10, averageCopyUs = 5_000),
            requestedFps = 0
        )
        assertEquals(1, verdict.requestedFps)
        assertEquals(1_000_000L, verdict.frameTimeBudgetUs)
        assertTrue(verdict.sustainableFps >= 1)
    }

    @Test
    fun sustainableFpsNeverExceedsRequestedAndNeverBelowOne() {
        val fast = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 100, nativeDirectCopies = 100, averageCopyUs = 500),
            requestedFps = 24
        )
        assertEquals(24, fast.sustainableFps)

        val glacial = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(callbackFrames = 100, nativeDirectCopies = 100, averageCopyUs = 5_000_000),
            requestedFps = 30
        )
        assertEquals(1, glacial.sustainableFps)
    }

    @Test
    fun evidenceMetadataExposesTheHonestThroughputVerdict() {
        val verdict = RecordingThroughputBudgetPolicy.evaluate(
            snapshot(
                callbackFrames = 1_800,
                acceptedFrames = 54,
                admissionRejectedFrames = 1_746,
                nativeDirectCopies = 54,
                averageCopyUs = 331_072
            ),
            requestedFps = 30
        )
        val md = verdict.toEvidenceMetadata()

        assertEquals("true", md["byteBufferP010ThroughputBudgetPolicy"])
        assertEquals("30", md["byteBufferP010RequestedFps"])
        assertEquals("1800", md["byteBufferP010DeliveredFrames"])
        assertEquals("1746", md["byteBufferP010DroppedFrames"])
        assertEquals("COPY_BOUND", md["byteBufferP010CopyBudgetVerdict"])
        assertEquals("NATIVE_SLOW_READOUT", md["byteBufferP010CopyPathDiagnosis"])
        assertEquals("3", md["byteBufferP010SustainableFps"])
        assertEquals("false", md["byteBufferP010SustainsRequestedFps"])
        assertEquals("false", md["byteBufferP010DropRatioWithinTarget"])
        assertTrue(md.getValue("byteBufferP010ThroughputHonestCap").contains("Capped to ~3fps"))
    }

    @Test
    fun recordingManagerWiresTheThroughputVerdictIntoLiveEvidence() {
        // No-downgrade + not-dead-code lock: the honest throughput verdict must actually reach the
        // live P010 record-evidence metadata, not sit as an unused policy.
        val managerSource =
            File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()
        assertTrue(managerSource.contains("RecordingThroughputBudgetPolicy.evaluate("))
        assertTrue(managerSource.contains(".toEvidenceMetadata()"))
    }
}
