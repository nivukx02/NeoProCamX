package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIAfTargetStabilityPolicyTest {

    @Test
    fun firstConfidentEyeTarget_isAcceptedAndTriggeredImmediately() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.82f, trackingId = 42, subjectType = SubjectType.EYE),
            currentTarget = null,
            moved = 1f,
            nowMs = 1_000L,
            lastUpdateMs = 0L,
            lastAcceptedTargetMs = 0L,
            lastTapAfMs = 0L,
            lastTrackId = -1,
            lastSubjectType = null,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertTrue(decision.acceptTarget)
        assertTrue(decision.hardReacquire)
        assertTrue(decision.shouldUpdateMetering)
        assertTrue(decision.shouldTriggerAutofocus)
    }

    @Test
    fun weakNewSubject_isRejectedInsteadOfForcingNewLock() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.32f, trackingId = 12),
            currentTarget = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            moved = 0.02f,
            nowMs = 1_000L,
            lastUpdateMs = 900L,
            lastAcceptedTargetMs = 900L,
            lastTapAfMs = 700L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertFalse(decision.acceptTarget)
        assertFalse(decision.holdExistingLock)
    }

    @Test
    fun weakExistingSubject_holdsCurrentLockBriefly() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.20f, trackingId = 7),
            currentTarget = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            moved = 0.005f,
            nowMs = 1_000L,
            lastUpdateMs = 980L,
            lastAcceptedTargetMs = 820L,
            lastTapAfMs = 600L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = false,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertFalse(decision.acceptTarget)
        assertTrue(decision.holdExistingLock)
    }

    @Test
    fun confidentSubjectChange_triggersHardReacquire() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.82f, trackingId = 11, subjectType = SubjectType.EYE),
            currentTarget = AIFloatRect(0.20f, 0.20f, 0.40f, 0.50f),
            moved = 0.08f,
            nowMs = 1_000L,
            lastUpdateMs = 850L,
            lastAcceptedTargetMs = 850L,
            lastTapAfMs = 700L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertTrue(decision.acceptTarget)
        assertTrue(decision.hardReacquire)
        assertTrue(decision.shouldTriggerAutofocus)
    }

    @Test
    fun confidentEyeReacquire_acceptsStableEnoughTargetDespiteHigherMotion() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.70f, trackingId = 15, subjectType = SubjectType.EYE),
            currentTarget = AIFloatRect(0.18f, 0.18f, 0.36f, 0.46f),
            moved = 0.14f,
            nowMs = 1_200L,
            lastUpdateMs = 1_020L,
            lastAcceptedTargetMs = 1_020L,
            lastTapAfMs = 920L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertTrue(decision.acceptTarget)
        assertTrue(decision.hardReacquire)
        assertTrue(decision.shouldTriggerAutofocus)
    }

    @Test
    fun sameTrackedSubject_moderateMotionUpdatesMeteringWithoutHardReacquire() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.86f, trackingId = 7, subjectType = SubjectType.FACE),
            currentTarget = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            moved = 0.05f,
            nowMs = 1_400L,
            lastUpdateMs = 1_260L,
            lastAcceptedTargetMs = 1_260L,
            lastTapAfMs = 900L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertTrue(decision.acceptTarget)
        assertFalse(decision.hardReacquire)
        assertTrue(decision.shouldUpdateMetering)
        assertFalse(decision.shouldTriggerAutofocus)
    }

    @Test
    fun sameTrackedSubject_largeMotionTriggersHardReacquire() {
        val decision = AIAfTargetStabilityPolicy.resolveDecision(
            target = target(confidence = 0.90f, trackingId = 7, subjectType = SubjectType.FACE),
            currentTarget = AIFloatRect(0.40f, 0.30f, 0.60f, 0.70f),
            moved = 0.075f,
            nowMs = 1_600L,
            lastUpdateMs = 1_420L,
            lastAcceptedTargetMs = 1_420L,
            lastTapAfMs = 1_180L,
            lastTrackId = 7,
            lastSubjectType = SubjectType.FACE,
            regionsChanged = true,
            moveThreshold = 0.015f,
            reacquireThreshold = 0.030f,
            updateIntervalMs = 28L,
            retriggerIntervalMs = 180L
        )

        assertTrue(decision.acceptTarget)
        assertTrue(decision.hardReacquire)
        assertTrue(decision.shouldTriggerAutofocus)
    }

    private fun target(
        confidence: Float,
        trackingId: Int,
        subjectType: SubjectType = SubjectType.FACE
    ) = SubjectMeteringTarget(
        focusX = 0.5f,
        focusY = 0.45f,
        subjectBounds = RectF(0.4f, 0.3f, 0.6f, 0.7f),
        subjectType = subjectType,
        category = SubjectCategory.HUMAN,
        trackingId = trackingId,
        confidence = confidence,
        focusDepthScore = 0.4f,
        hdrSuggested = false,
        lowLightSuggested = false
    )
}
