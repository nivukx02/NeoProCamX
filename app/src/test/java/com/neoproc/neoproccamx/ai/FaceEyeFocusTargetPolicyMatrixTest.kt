package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f03 / CP-F4 — eye/face anchor MATRIX golden lock.
 *
 * Characterisation lock for [FaceEyeFocusTargetPolicy.resolve] across the open /
 * closed / occluded / extreme-pose matrix. These goldens pin TODAY's anchor rules so
 * any future tuning of the eye-vs-face decision is gated behind a regression failure.
 *
 * Note on the JVM test runtime: the mockable `android.jar` (`returnDefaultValues=true`)
 * makes `RectF.centerX()/width()/height()/contains()` return 0/false, but the public
 * `left/top/right/bottom` fields are real. [FaceEyeFocusTargetPolicy] only reads those
 * fields directly, so its anchor math is exact here. (Eye coordinates come straight from
 * the [EyePoint], bypassing RectF entirely.)
 */
class FaceEyeFocusTargetPolicyMatrixTest {

    // HeadSubjectFlag (F10) is default-ON (activated 2026-07-03). Restore that default after each
    // case; only the extreme-yaw case below pins it OFF to isolate the eye-scoring degrade (the
    // HEAD-override on-path is covered by HeadSubjectGoldenLockTest / HeadSubjectOnPathTest).
    @After
    fun restoreHeadSubjectDefault() = HeadSubjectFlag.set(true)

    // ── Open + unoccluded eye -> NEAR-EYE landmark (not the face centroid) ───────────

    @Test
    fun `open unoccluded eye anchors on the near-eye landmark not the face centroid`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.93f, isOpen = true),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.95f, isOpen = true)
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        assertEquals(SubjectType.EYE, target!!.subjectType)
        // Higher-confidence (right) eye wins; anchor is the eye landmark, NOT the
        // upper-face anchor (0.52, 0.416).
        assertEquals(0.59f, target.focusX, 0.0001f)
        assertEquals(0.360f, target.focusY, 0.0001f)
        assertTrue(target.focusY < 0.416f)
    }

    // ── Closed eyes -> degrade to FACE upper anchor ──────────────────────────────────

    @Test
    fun `both eyes closed degrade to upper-face anchor`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.95f, isOpen = false),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.95f, isOpen = false)
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        assertEquals(SubjectType.FACE, target!!.subjectType)
        assertEquals(0.52f, target.focusX, 0.0001f)
        assertEquals(0.416f, target.focusY, 0.0001f)
    }

    // ── Heavy occlusion (>= gate) -> FACE; just BELOW gate with open eyes -> EYE ──────

    @Test
    fun `occlusion at gate rejects eye lock and keeps face anchor`() {
        val gated = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.95f, isOpen = true),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.96f, isOpen = true),
                occlusionLevel = 0.72f
            ),
            eyePriorityEnabled = true
        )
        assertNotNull(gated)
        assertEquals(SubjectType.FACE, gated!!.subjectType)
        assertEquals(0.52f, gated.focusX, 0.0001f)
        assertEquals(0.416f, gated.focusY, 0.0001f)
    }

    @Test
    fun `occlusion just below the gate with strong open eyes still locks the eye`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.95f, isOpen = true),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.96f, isOpen = true),
                occlusionLevel = 0.71f
            ),
            eyePriorityEnabled = true
        )
        assertNotNull(target)
        assertEquals(SubjectType.EYE, target!!.subjectType)
        assertEquals(0.59f, target.focusX, 0.0001f)
    }

    // ── Extreme head pose + weak eyes (occlusion BELOW gate) -> FACE ──────────────────

    @Test
    fun `extreme yaw with weak eye confidence degrades to face anchor`() {
        // Isolate the eye-scoring degrade: with F10 pinned OFF the fall-through is FACE. (Under the
        // default-ON F10 this same input yields HEAD via faceTarget.copy(subjectType=HEAD), which is
        // asserted in HeadSubjectGoldenLockTest — same anchor coords, only the type label differs.)
        HeadSubjectFlag.set(false)
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                // Profile view: far (left) eye barely visible, near (right) eye partial.
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.16f, isOpen = true),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.18f, isOpen = true),
                occlusionLevel = 0.20f,
                confidence = 0.55f,
                headPoseYaw = 75f
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        // Both eye scores fall below MIN_EYE_SCORE under the pose penalty -> FACE.
        assertEquals(SubjectType.FACE, target!!.subjectType)
        assertEquals(0.52f, target.focusX, 0.0001f)
        assertEquals(0.416f, target.focusY, 0.0001f)
    }

    // ── Determinism: identical inputs -> identical target (pure function) ─────────────

    @Test
    fun `resolve is deterministic for identical inputs`() {
        fun once() = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.41f, y = 0.355f, confidence = 0.93f, isOpen = true),
                rightEye = EyePoint(x = 0.59f, y = 0.360f, confidence = 0.95f, isOpen = true)
            ),
            eyePriorityEnabled = true
        )

        val a = once()
        val b = once()
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!.subjectType, b!!.subjectType)
        assertEquals(a.focusX, b.focusX, 0f)
        assertEquals(a.focusY, b.focusY, 0f)
        assertEquals(a.confidence, b.confidence, 0f)
    }

    private fun trackedFace(
        leftEye: EyePoint?,
        rightEye: EyePoint?,
        occlusionLevel: Float = 0.10f,
        confidence: Float = 0.9f,
        headPoseYaw: Float = 0f
    ): TrackedFace =
        TrackedFace(
            trackingId = 7,
            boundingBox = rectF(0.27f, 0.20f, 0.77f, 0.80f),
            leftEye = leftEye,
            rightEye = rightEye,
            confidence = confidence,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 1_000L,
            predictedX = 0.52f,
            predictedY = 0.50f,
            occlusionLevel = occlusionLevel,
            headPoseYaw = headPoseYaw
        )

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
