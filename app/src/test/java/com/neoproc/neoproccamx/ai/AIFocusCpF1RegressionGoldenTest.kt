package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CP-F5 golden lock for CP-F1 (s5-f04). Replays a recorded subject-target stream through the
 * pure decision layer used by the coordinator and PINS the per-frame
 * accept / hold / shouldUpdateMetering / shouldTriggerAutofocus decisions as the golden. This is
 * the no-downgrade proof: the scene-cut / risk-gate extension defaults OFF
 * (AIAutoFocusCoordinator.SCENE_CUT_RISK_GATING_ENABLED == false), so these decisions — and thus
 * the live retrigger/hold/follow behavior — are unchanged until the owner enables the extension.
 */
class AIFocusCpF1RegressionGoldenTest {

    private data class Frame(
        val trackingId: Int,
        val subjectType: SubjectType,
        val confidence: Float,
        val cx: Float,
        val cy: Float,
        val nowMs: Long
    )

    private fun rectAround(cx: Float, cy: Float, half: Float = 0.08f) =
        RectF(cx - half, cy - half, cx + half, cy + half)

    private fun target(f: Frame) = SubjectMeteringTarget(
        focusX = f.cx,
        focusY = f.cy,
        subjectBounds = rectAround(f.cx, f.cy),
        subjectType = f.subjectType,
        category = SubjectCategory.HUMAN,
        trackingId = f.trackingId,
        confidence = f.confidence,
        focusDepthScore = 0.5f,
        hdrSuggested = false,
        lowLightSuggested = false
    )

    // A recorded stream: settle on a face, micro-jitter (must NOT pump), then a deliberate walk.
    private val stream = listOf(
        Frame(7, SubjectType.FACE, 0.90f, 0.500f, 0.500f, 1000L),
        Frame(7, SubjectType.FACE, 0.90f, 0.501f, 0.500f, 1033L),
        Frame(7, SubjectType.FACE, 0.90f, 0.500f, 0.501f, 1066L),
        Frame(7, SubjectType.FACE, 0.91f, 0.502f, 0.500f, 1099L),
        Frame(7, SubjectType.FACE, 0.90f, 0.540f, 0.520f, 1320L),
        Frame(7, SubjectType.FACE, 0.90f, 0.580f, 0.540f, 1560L),
        Frame(7, SubjectType.FACE, 0.12f, 0.585f, 0.545f, 1593L)
    )

    @Test
    fun cpF1DecisionStreamMatchesGolden() {
        var currentTarget: AIFloatRect? = null
        var lastUpdateMs = 0L
        var lastAcceptedTargetMs = 0L
        var lastTapAfMs = 0L
        var lastTrackId = -1
        var lastSubjectType: SubjectType? = null

        val decisions = ArrayList<String>(stream.size)
        for (f in stream) {
            val t = target(f)
            val incoming = AIFloatRect(
                t.subjectBounds.left, t.subjectBounds.top, t.subjectBounds.right, t.subjectBounds.bottom
            )
            val moved = if (currentTarget == null) 1f else {
                val dx = incoming.centerX() - currentTarget.centerX()
                val dy = incoming.centerY() - currentTarget.centerY()
                kotlin.math.sqrt(dx * dx + dy * dy)
            }
            val decision = AIAfTargetStabilityPolicy.resolveDecision(
                target = t,
                currentTarget = currentTarget,
                moved = moved,
                nowMs = f.nowMs,
                lastUpdateMs = lastUpdateMs,
                lastAcceptedTargetMs = lastAcceptedTargetMs,
                lastTapAfMs = lastTapAfMs,
                lastTrackId = lastTrackId,
                lastSubjectType = lastSubjectType,
                regionsChanged = true,
                moveThreshold = 0.005f,
                reacquireThreshold = 0.010f,
                updateIntervalMs = 8L,
                retriggerIntervalMs = 80L
            )
            decisions.add(
                "acc=${decision.acceptTarget},hold=${decision.holdExistingLock}" +
                    ",upd=${decision.shouldUpdateMetering},trig=${decision.shouldTriggerAutofocus}"
            )
            if (decision.acceptTarget) {
                currentTarget = incoming
                lastAcceptedTargetMs = f.nowMs
                lastUpdateMs = f.nowMs
                lastSubjectType = t.subjectType
                lastTrackId = t.trackingId
                if (decision.shouldTriggerAutofocus) lastTapAfMs = f.nowMs
            }
        }

        // GOLDEN: the recorded CP-F1 decision stream. Any drift here is a downgrade and must fail.
        val golden = listOf(
            "acc=true,hold=false,upd=true,trig=true",   // first acquire (currentTarget == null)
            "acc=true,hold=false,upd=true,trig=false",  // micro-jitter, follow not pump
            "acc=true,hold=false,upd=true,trig=false",  // micro-jitter
            "acc=true,hold=false,upd=true,trig=false",  // micro-jitter
            "acc=true,hold=false,upd=true,trig=false",  // gradual walk: metering follows; policy does not force a retrigger
            "acc=true,hold=false,upd=true,trig=false",  // continued gradual walk
            "acc=false,hold=true,upd=false,trig=false"  // confidence drop on same tracked subject => brief hold
        )
        assertEquals(golden, decisions)
    }
}
