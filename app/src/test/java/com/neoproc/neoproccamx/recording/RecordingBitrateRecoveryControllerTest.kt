package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingBitrateRecoveryControllerTest {
    private val target = 160_000_000

    private fun window(endUs: Long, bps: Int = 152_000_000) =
        RecordingBitrateRecoveryController.OutputWindow(51, 2_040_000L, endUs, bps)

    @Test fun stableCompleteWindowsLeaveWorkingEncoderUntouched() {
        val controller = RecordingBitrateRecoveryController()
        repeat(150) {
            val decision = controller.observe(target, window((it + 1) * 2_000_000L, target))
            assertEquals(RecordingBitrateRecoveryController.Action.NONE, decision.action)
            assertEquals(0, decision.attempt)
        }
    }

    @Test fun twoDistinctDeficitWindowsRequestOnlyTheIdenticalAdmittedBitrate() {
        val controller = RecordingBitrateRecoveryController()
        assertEquals(RecordingBitrateRecoveryController.Action.NONE,
            controller.observe(target, window(2_000_000L)).action)
        val decision = controller.observe(target, window(4_000_000L))
        assertEquals(RecordingBitrateRecoveryController.Action.REASSERT_SAME_TARGET, decision.action)
        assertEquals(target, decision.targetBitrateBps)
        assertEquals(152_000_000, decision.measuredBitrateBps)
        assertEquals(1, decision.attempt)
    }

    @Test fun sameAndOverlappingWindowsCannotCreateRetryStorms() {
        val controller = RecordingBitrateRecoveryController()
        controller.observe(target, window(2_000_000L))
        repeat(20_000) { index ->
            assertEquals(RecordingBitrateRecoveryController.Action.NONE,
                controller.observe(target, window(2_000_000L + index)).action)
        }
        assertEquals(1, controller.observe(target, window(4_000_000L)).attempt)
    }

    @Test fun staleRegressingOutputDoesNotBecomeFreshRecoveryEvidence() {
        val controller = RecordingBitrateRecoveryController()
        controller.observe(target, window(4_000_000L))
        for (end in listOf(-1L, 0L, 1_000_000L, 3_000_000L)) {
            assertEquals(RecordingBitrateRecoveryController.Action.NONE,
                controller.observe(target, window(end)).action)
        }
        assertEquals(1, controller.observe(target, window(6_000_000L)).attempt)
    }

    @Test fun initialShortOrMissingOutputCannotMutateCodec() {
        val controller = RecordingBitrateRecoveryController()
        val invalid = listOf(
            RecordingBitrateRecoveryController.OutputWindow.EMPTY,
            window(2_000_000L).copy(sampleCount = 1),
            window(2_000_000L).copy(durationUs = 1_999_999L),
            window(2_000_000L).copy(bitrateBps = 0),
            window(2_000_000L).copy(bitrateBps = -1)
        )
        invalid.forEach { assertEquals(RecordingBitrateRecoveryController.Action.NONE,
            controller.observe(target, it).action) }
        assertEquals(0, controller.observe(target, window(2_000_000L)).attempt)
    }

    @Test fun persistentlyUnhonouredRateIsReportedOnceAfterExactlyThreeAttempts() {
        val controller = RecordingBitrateRecoveryController()
        val decisions = (1..300).map { controller.observe(target, window(it * 2_000_000L)) }
        assertEquals(3, decisions.count {
            it.action == RecordingBitrateRecoveryController.Action.REASSERT_SAME_TARGET
        })
        assertEquals(1, decisions.count {
            it.action == RecordingBitrateRecoveryController.Action.REPORT_UNHONOURED_TARGET
        })
        assertTrue(decisions.all { it.targetBitrateBps == target && it.measuredBitrateBps == 152_000_000 })
        assertEquals(3, decisions.last().attempt)
    }

    @Test fun successfulWindowBreaksConsecutiveDeficitWithoutDiscardingRetryBudget() {
        val controller = RecordingBitrateRecoveryController()
        controller.observe(target, window(2_000_000L))
        controller.observe(target, window(4_000_000L))
        controller.observe(target, window(6_000_000L, target))
        assertEquals(RecordingBitrateRecoveryController.Action.NONE,
            controller.observe(target, window(8_000_000L)).action)
        assertEquals(2, controller.observe(target, window(10_000_000L)).attempt)
    }

    @Test fun onePercentBoundaryTriggersRecoveryBelowButNotAtTheBoundary() {
        val controller = RecordingBitrateRecoveryController()
        controller.observe(target, window(2_000_000L, 158_400_000))
        assertEquals(0, controller.observe(target, window(4_000_000L, 158_400_000)).attempt)
        controller.observe(target, window(6_000_000L, 158_399_999))
        assertEquals(RecordingBitrateRecoveryController.Action.REASSERT_SAME_TARGET,
            controller.observe(target, window(8_000_000L, 158_399_999)).action)
    }

    @Test fun changedUserTargetStartsIndependentObservationWithoutModifyingSelection() {
        val controller = RecordingBitrateRecoveryController()
        controller.observe(target, window(2_000_000L))
        controller.observe(target, window(4_000_000L))
        val newTarget = 120_000_000
        assertEquals(0, controller.observe(newTarget, window(6_000_000L, 100_000_000)).attempt)
        val decision = controller.observe(newTarget, window(8_000_000L, 100_000_000))
        assertEquals(newTarget, decision.targetBitrateBps)
        assertEquals(1, decision.attempt)
    }

    @Test fun resetPreventsOldSessionEvidenceFromInfluencingNewRecording() {
        val controller = RecordingBitrateRecoveryController()
        repeat(6) { controller.observe(target, window((it + 1) * 2_000_000L)) }
        controller.reset()
        assertEquals(0, controller.observe(target, window(2_000_000L)).attempt)
        assertEquals(1, controller.observe(target, window(4_000_000L)).attempt)
    }

    @Test fun maximumIntegerRateDoesNotOverflowRecoveryFloor() {
        val controller = RecordingBitrateRecoveryController()
        repeat(3) { assertEquals(RecordingBitrateRecoveryController.Action.NONE,
            controller.observe(Int.MAX_VALUE, window((it + 1) * 2_000_000L, Int.MAX_VALUE)).action) }
        controller.observe(Int.MAX_VALUE, window(8_000_000L, 1))
        assertEquals(RecordingBitrateRecoveryController.Action.REASSERT_SAME_TARGET,
            controller.observe(Int.MAX_VALUE, window(10_000_000L, 1)).action)
    }

    @Test fun managerWiresRecoveryAwayFromCaptureAndNeverChangesQualityParameters() {
        val source = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()
        val recovery = source.substringAfter("private fun maybeRecoverDirectEncoderBitrate(")
            .substringBefore("fun resume(")
        assertTrue(recovery.contains("drainSessionOwnership.isCurrent(drainSession)"))
        assertTrue(recovery.contains("videoCodecObj !== codec"))
        assertTrue(recovery.contains("MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target"))
        assertFalse(recovery.contains("PARAMETER_KEY_REQUEST_SYNC_FRAME"))
        assertFalse(recovery.contains("KEY_VIDEO_QP"))
        val ticker = source.substringAfter("private suspend fun tickDuration(").substringBefore("private fun releaseInternal(")
        assertTrue(ticker.contains("maybeRecoverDirectEncoderBitrate(drainSession)"))
        val release = source.substringAfter("private fun releaseInternal(").substringBefore("fun destroy(")
        assertTrue(release.contains("bitrateRecoveryController.reset()"))
        assertTrue(release.contains("activeDirectCbrRateRecovery = false"))
    }
}
