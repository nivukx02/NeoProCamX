package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AF-SD (F08) — end-to-end no-downgrade + ON-path lock through FocusEngine.selectSubjectAwareness
 * with a single PET/ANIMAL object (no faces/pose), so the object candidate is primary.
 *
 * Under the mockable android.jar, RectF.centerX()/width() return 0, so the tracker's predicted
 * centre collapses to (0,0) — that is the OFF golden. The ON anchor is computed by
 * [AnimalEyeFocusTargetPolicy] from the REAL left/top/right/bottom fields, so it is deterministic.
 */
class AnimalEyeAfGoldenLockTest {

    @After
    fun reset() = AnimalEyeAfFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    // category=PET mirrors production: ObjectTracker.objectCategoryForClass(17) == PET always
    // populates it, so this fixture is valid whether HeadSubjectFlag reads obj.category (ON) or the
    // legacy className map (OFF). Without it the synthetic object defaults to OBJECT and the now-ON
    // HeadSubjectFlag categorization would (only in-test) suppress the PET/ANIMAL AnimalEyeAf gate.
    private fun dog(vx: Float) = TrackedObject(
        trackingId = 5,
        boundingBox = rect(0.2f, 0.3f, 0.8f, 0.6f),
        confidence = 0.9f,
        classId = 17,
        className = "dog",
        category = SubjectCategory.PET,
        velocityX = vx,
        velocityY = 0f,
        timestampMs = 1_000L
    )

    private fun run() = FocusEngine().selectSubjectAwareness(
        faces = emptyList(),
        pose = null,
        objects = listOf(dog(vx = 0.02f)),
        segmentation = null,
        depthData = null,
        sceneType = SceneType.GENERAL,
        timestampMs = 1_000L
    )

    @Test
    fun flagOff_focusesBboxCentre_bitForBit() {
        AnimalEyeAfFlag.set(false)
        val r = run()
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.OBJECT, r.primarySubject!!.subjectType)
        assertEquals(SubjectCategory.PET, r.primarySubject.category)
        assertNotNull(r.focusPoint)
        // RectF mock => predicted centre collapses to (0,0).
        assertEquals(0f, r.focusPoint!!.focusX, 1e-4f)
        assertEquals(0f, r.focusPoint.focusY, 1e-4f)
        assertEquals(SubjectType.OBJECT, r.focusPoint.subjectType)
    }

    @Test
    fun flagOn_focusesAnimalHeadEye_categoryUnchanged() {
        AnimalEyeAfFlag.set(true)
        val r = run()
        assertNotNull(r.primarySubject)
        // Focus POINT snaps to the head/eye; category (ranking tier) stays PET.
        assertEquals(SubjectType.EYE, r.primarySubject!!.subjectType)
        assertEquals(SubjectCategory.PET, r.primarySubject.category)
        assertNotNull(r.focusPoint)
        assertEquals(SubjectType.EYE, r.focusPoint!!.subjectType)
        assertEquals(0.8f - 0.6f * 0.18f, r.focusPoint.focusX, 1e-3f) // 0.692 upper-front-right
        assertEquals(0.3f + 0.3f * 0.28f, r.focusPoint.focusY, 1e-3f) // 0.384 head band
    }
}
