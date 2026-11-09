package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawDemosaicCompletionHandoffTest {
    @Test
    fun deferredCompletionRetainsOriginalRenderAndCallbackIdentity() {
        val handoff = RawDemosaicCompletionHandoff()
        val delivered = mutableListOf<String>()
        val original = frame(epoch = 30L, timestamp = 30_000L).copy(
            renderProfileIdentity = NdrRenderProfileIdentity(7L, 1L),
            idleCompletionRoute = RawDemosaicCompletionRoute { delivered += "original" }
        )
        val newer = frame(epoch = 30L, timestamp = 30_040L).copy(
            renderProfileIdentity = NdrRenderProfileIdentity(7L, 2L),
            idleCompletionRoute = RawDemosaicCompletionRoute { delivered += "newer" }
        )

        handoff.afterDispatch(
            frame = original,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )
        val deferred = requireNotNull(
            handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE).completedFrame
        )

        assertEquals(original, deferred)
        assertFalse(deferred.renderProfileIdentity == newer.renderProfileIdentity)
        requireNotNull(deferred.idleCompletionRoute).publish(deferred)
        assertEquals(listOf("original"), delivered)
        assertTrue(handoff.acknowledgeCompleted(deferred))

        handoff.afterDispatch(
            frame = newer,
            completedSynchronously = true,
            pollAfterDeadline = RawDemosaicCompletionPoll.IDLE
        )
        val current = requireNotNull(handoff.completedIdentity())
        requireNotNull(current.idleCompletionRoute).publish(current)
        assertEquals(listOf("original", "newer"), delivered)
    }

    @Test
    fun deadlineCompletionIsPublishedBeforeAnotherSharedOutputDispatch() {
        val handoff = RawDemosaicCompletionHandoff()
        val original = frame(epoch = 7L, timestamp = 700L)

        assertTrue(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
        assertNull(
            handoff.afterDispatch(
                frame = original,
                completedSynchronously = false,
                pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
            )
        )

        val stillPending = handoff.beforeDispatch(RawDemosaicCompletionPoll.PENDING)
        assertFalse(stillPending.shouldDispatchCurrent)
        assertNull(stillPending.completedFrame)

        val completed = handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE)
        assertTrue(completed.shouldDispatchCurrent)
        assertEquals(original, completed.completedFrame)
        assertEquals(original, handoff.completedIdentity())
        assertTrue(handoff.acknowledgeCompleted(original))

        assertTrue(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
    }

    @Test
    fun backgroundResumeCannotRelabelOldPixelsWithCurrentEpochOrTimestamp() {
        val handoff = RawDemosaicCompletionHandoff()
        val beforeBackground = frame(epoch = 3L, timestamp = 3_000L)
        val afterResume = frame(epoch = 4L, timestamp = 4_000L)

        handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE)
        handoff.afterDispatch(
            frame = beforeBackground,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        val completionOnResume = handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE)
        assertEquals(beforeBackground, completionOnResume.completedFrame)
        assertTrue(completionOnResume.shouldDispatchCurrent)
        assertFalse(completionOnResume.completedFrame == afterResume)
        assertTrue(handoff.acknowledgeCompleted(beforeBackground))
    }

    @Test
    fun synchronousCompletionKeepsCurrentIdentity() {
        val handoff = RawDemosaicCompletionHandoff()
        val current = frame(epoch = 11L, timestamp = 11_000L)

        val completed = handoff.afterDispatch(
            frame = current,
            completedSynchronously = true,
            pollAfterDeadline = RawDemosaicCompletionPoll.IDLE
        )

        assertEquals(current, completed)
        assertEquals(current, handoff.completedIdentity())
        assertTrue(handoff.acknowledgeCompleted(current))
    }

    @Test
    fun nativeFailureQuarantinesPendingIdentityUntilNativeRetirementProof() {
        val handoff = RawDemosaicCompletionHandoff()
        val pending = frame(epoch = 5L, timestamp = 5_000L)
        handoff.afterDispatch(
            frame = pending,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        assertFalse(handoff.beforeDispatch(RawDemosaicCompletionPoll.FAILED).shouldDispatchCurrent)
        val quarantine = requireNotNull(handoff.quarantinedOwnership())
        assertEquals(
            RawDemosaicOwnershipQuarantineReason.NATIVE_COMPLETION_FAILED,
            quarantine.reason
        )
        assertEquals(pending, quarantine.pendingIdentity)
        assertEquals(pending, handoff.pendingIdentity())
        assertFalse(handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE).shouldDispatchCurrent)
        assertFalse(handoff.resetIfUnowned())

        handoff.retireAfterNativeRetirementProof()
        assertFalse(handoff.hasOutstandingIdentity())
        assertTrue(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
    }

    @Test
    fun retirementCountMismatchQuarantinesCompletedLineageAndPreventsPublication() {
        val handoff = RawDemosaicCompletionHandoff()
        val first = frame(epoch = 6L, timestamp = 6_000L)
        val follower = frame(epoch = 6L, timestamp = 6_040L)
        handoff.afterDispatch(first, true, RawDemosaicCompletionPoll.IDLE)
        handoff.afterDispatch(follower, true, RawDemosaicCompletionPoll.IDLE)

        val quarantine = handoff.quarantine(
            RawDemosaicOwnershipQuarantineReason.NATIVE_RETIREMENT_COUNT_MISMATCH
        )

        assertEquals(listOf(first, follower), quarantine.completedIdentities)
        assertEquals(2, handoff.completedIdentityCount())
        assertNull(handoff.completedIdentity())
        assertFalse(handoff.acknowledgeCompleted(first))
        assertFalse(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
        assertTrue(handoff.ownsIdentity(first))
        assertTrue(handoff.ownsIdentity(follower))
    }

    @Test
    fun failedPollImmediatelyAfterDispatchRetainsAttemptedFrameIdentity() {
        val handoff = RawDemosaicCompletionHandoff()
        val attempted = frame(epoch = 8L, timestamp = 8_000L)

        assertNull(
            handoff.afterDispatch(
                frame = attempted,
                completedSynchronously = false,
                pollAfterDeadline = RawDemosaicCompletionPoll.FAILED
            )
        )

        val quarantine = requireNotNull(handoff.quarantinedOwnership())
        assertEquals(attempted, quarantine.pendingIdentity)
        assertEquals(attempted, handoff.pendingIdentity())
        assertFalse(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
    }

    @Test
    fun repeatedCompletionPublishAndDispatchDoesNotHalveCadence() {
        val handoff = RawDemosaicCompletionHandoff()
        var dispatched = 0
        var published = 0

        assertTrue(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
        dispatched += 1
        handoff.afterDispatch(
            frame = frame(epoch = 9L, timestamp = 1L),
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        for (timestamp in 2L..60L) {
            val decision = handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE)
            assertTrue(decision.shouldDispatchCurrent)
            assertTrue(decision.completedFrame != null)
            assertTrue(handoff.acknowledgeCompleted(decision.completedFrame!!))
            published += 1
            dispatched += 1
            handoff.afterDispatch(
                frame = frame(epoch = 9L, timestamp = timestamp),
                completedSynchronously = false,
                pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
            )
        }

        assertEquals(60, dispatched)
        assertEquals(59, published)
    }

    @Test
    fun recorderCanQueueNextDispatchWhileOneCompletedLaneIsOwned() {
        val handoff = RawDemosaicCompletionHandoff()
        val first = frame(epoch = 21L, timestamp = 21_000L)
        val second = frame(epoch = 21L, timestamp = 21_040L)

        handoff.afterDispatch(
            frame = first,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )
        val completion = handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE)
        assertEquals(first, completion.completedFrame)
        assertTrue(completion.shouldDispatchCurrent)
        assertTrue(completion.canOverlapDispatchBeforeAcknowledgement)

        assertNull(
            handoff.afterDispatch(
                frame = second,
                completedSynchronously = false,
                pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
            )
        )
        assertEquals(first, handoff.completedIdentity())
        assertEquals(second, handoff.pendingIdentity())
        assertTrue(handoff.acknowledgeCompleted(first))
        assertEquals(second, handoff.pendingIdentity())
    }

    @Test
    fun synchronousFollowerIsPromotedOnlyAfterOlderLaneAcknowledgement() {
        val handoff = RawDemosaicCompletionHandoff()
        val first = frame(epoch = 22L, timestamp = 22_000L)
        val second = frame(epoch = 22L, timestamp = 22_040L)

        handoff.afterDispatch(
            frame = first,
            completedSynchronously = true,
            pollAfterDeadline = RawDemosaicCompletionPoll.IDLE
        )
        assertNull(
            handoff.afterDispatch(
                frame = second,
                completedSynchronously = true,
                pollAfterDeadline = RawDemosaicCompletionPoll.IDLE
            )
        )
        assertEquals(2, handoff.completedIdentityCount())
        assertEquals(first, handoff.completedIdentity())
        assertTrue(handoff.acknowledgeCompleted(first))
        assertEquals(second, handoff.completedIdentity())
        assertTrue(handoff.acknowledgeCompleted(second))
        assertFalse(handoff.hasOutstandingIdentity())
    }

    @Test
    fun completedIdentitySurvivesFailedPublicationAndLaterIdlePoll() {
        val handoff = RawDemosaicCompletionHandoff()
        val original = frame(epoch = 13L, timestamp = 13_000L)
        handoff.afterDispatch(
            frame = original,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        val first = handoff.beforeDispatch(RawDemosaicCompletionPoll.COMPLETE)
        assertEquals(original, first.completedFrame)
        assertEquals(original, handoff.completedIdentity())

        val retry = handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE)
        assertEquals(original, retry.completedFrame)
        assertEquals(original, handoff.completedIdentity())
        assertFalse(handoff.acknowledgeCompleted(frame(epoch = 13L, timestamp = 13_001L)))
        assertEquals(original, handoff.completedIdentity())
        assertTrue(handoff.acknowledgeCompleted(original))
        assertNull(handoff.completedIdentity())
    }

    @Test
    fun nativeCompleteObservationIsFrozenBeforeEncoderRetry() {
        val handoff = RawDemosaicCompletionHandoff()
        val original = frame(epoch = 15L, timestamp = 15_000L)
        handoff.afterDispatch(
            frame = original,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        val firstObservationNs = 4_200_000_000L
        val first = handoff.beforeDispatch(
            poll = RawDemosaicCompletionPoll.COMPLETE,
            completionObservedElapsedRealtimeNs = firstObservationNs
        )
        assertEquals(firstObservationNs, first.completedFrame!!.demosaicCompletionObservedElapsedRealtimeNs)

        // Encoder rejection leaves the completed identity owned. Neither an IDLE retry nor an
        // accidental duplicate COMPLETE observation may inflate the native GPU duration.
        val idleRetry = handoff.beforeDispatch(
            poll = RawDemosaicCompletionPoll.IDLE,
            completionObservedElapsedRealtimeNs = firstObservationNs + 8_000_000_000L
        )
        assertEquals(firstObservationNs, idleRetry.completedFrame!!.demosaicCompletionObservedElapsedRealtimeNs)
        val duplicateComplete = handoff.beforeDispatch(
            poll = RawDemosaicCompletionPoll.COMPLETE,
            completionObservedElapsedRealtimeNs = firstObservationNs + 9_000_000_000L
        )
        val duplicateCompletedFrame = requireNotNull(duplicateComplete.completedFrame)
        assertEquals(
            firstObservationNs,
            duplicateCompletedFrame.demosaicCompletionObservedElapsedRealtimeNs
        )
        assertTrue(handoff.acknowledgeCompleted(duplicateCompletedFrame))
    }

    @Test
    fun completionObservedAtDeadlinePollIsCapturedExactlyOnce() {
        val handoff = RawDemosaicCompletionHandoff()
        val original = frame(epoch = 16L, timestamp = 16_000L)
        val observationNs = 7_500_000_000L

        val completed = handoff.afterDispatch(
            frame = original,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.COMPLETE,
            completionObservedElapsedRealtimeNs = observationNs
        )

        assertEquals(observationNs, completed!!.demosaicCompletionObservedElapsedRealtimeNs)
        assertFalse(completed.observeDemosaicCompletion(observationNs + 1L))
        assertEquals(observationNs, completed.demosaicCompletionObservedElapsedRealtimeNs)
    }

    @Test
    fun pendingIdentityInspectionIsNonDestructiveAndBlocksLostIdleTransition() {
        val handoff = RawDemosaicCompletionHandoff()
        val original = frame(epoch = 17L, timestamp = 17_000L)
        handoff.afterDispatch(
            frame = original,
            completedSynchronously = false,
            pollAfterDeadline = RawDemosaicCompletionPoll.PENDING
        )

        assertEquals(original, handoff.pendingIdentity())
        assertEquals(original, handoff.pendingIdentity())
        assertTrue(handoff.hasOutstandingIdentity())
        assertFalse(handoff.beforeDispatch(RawDemosaicCompletionPoll.IDLE).shouldDispatchCurrent)
        assertEquals(original, handoff.pendingIdentity())
    }

    @Test
    fun nativeDeferredCompletionLoggingIsBounded() {
        val nativeBridge = File("src/main/cpp/vulkan_bridge.cpp").readText()
        assertTrue(nativeBridge.contains("kRawDeferredCompletionLogInterval = 120u"))
        assertTrue(nativeBridge.contains("deferredCount == 1u ||"))
        assertTrue(nativeBridge.contains("deferredCount % kRawDeferredCompletionLogInterval == 0u"))
        assertEquals(1, "RAW preview GPU completion deferred".toRegex().findAll(nativeBridge).count())
    }

    private fun frame(epoch: Long, timestamp: Long) = RawDemosaicFrameIdentity(
        presentationEpoch = epoch,
        sensorTimestampNs = timestamp,
        width = 4096,
        height = 3072
    )
}
