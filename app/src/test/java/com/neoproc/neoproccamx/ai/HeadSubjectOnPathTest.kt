package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AF-SD (F10) — ON-path. With HeadSubjectFlag ON: an averted/profile face resolves to HEAD (priority
 * between FACE and BODY), a bird object is categorized ANIMAL (authoritative ObjectTracker category),
 * and a car stays VEHICLE (proving the fix does NOT drop the VEHICLE/NATURE branches).
 */
class HeadSubjectOnPathTest {

    @After
    fun reset() = HeadSubjectFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun avertedFace() = TrackedFace(
        trackingId = 3,
        boundingBox = rect(0.3f, 0.2f, 0.7f, 0.8f),
        leftEye = null,
        rightEye = null,
        confidence = 0.9f,
        velocityX = 0f,
        velocityY = 0f,
        timestampMs = 1_000L,
        headPoseYaw = 60f,
        occlusionLevel = 0f
    )

    private fun obj(id: Int, classId: Int, name: String, cat: SubjectCategory) = TrackedObject(
        trackingId = id,
        boundingBox = rect(0.3f, 0.3f, 0.6f, 0.6f),
        confidence = 0.9f,
        classId = classId,
        className = name,
        velocityX = 0f,
        velocityY = 0f,
        timestampMs = 1_000L,
        category = cat
    )

    private fun select(faces: List<TrackedFace>, objects: List<TrackedObject>) =
        FocusEngine().selectSubjectAwareness(
            faces = faces, pose = null, objects = objects,
            segmentation = null, depthData = null,
            sceneType = SceneType.GENERAL, timestampMs = 1_000L
        )

    @Test
    fun flagOn_avertedFaceResolvesToHead() {
        HeadSubjectFlag.set(true)
        val r = select(listOf(avertedFace()), emptyList())
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.HEAD, r.primarySubject!!.subjectType)
        assertEquals(SubjectType.HEAD, r.focusPoint!!.subjectType)
    }

    @Test
    fun flagOn_birdIsAnimal() {
        HeadSubjectFlag.set(true)
        val r = select(emptyList(), listOf(obj(9, 16, "bird", SubjectCategory.ANIMAL)))
        assertNotNull(r.primarySubject)
        assertEquals(SubjectCategory.ANIMAL, r.primarySubject!!.category)
    }

    @Test
    fun flagOn_carStaysVehicle_noRegression() {
        HeadSubjectFlag.set(true)
        val r = select(emptyList(), listOf(obj(4, 3, "car", SubjectCategory.VEHICLE)))
        assertNotNull(r.primarySubject)
        assertEquals(SubjectCategory.VEHICLE, r.primarySubject!!.category)
    }
}
