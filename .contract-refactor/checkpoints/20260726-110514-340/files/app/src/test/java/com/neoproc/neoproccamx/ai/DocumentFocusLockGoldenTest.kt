package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AF-SD (F11) — no-downgrade + ON-path for the DOCUMENT focus lock.
 * OFF: a DOCUMENT scene with no detections yields no primary/focus (bit-for-bit legacy).
 * ON: after STABILITY_FRAMES stable DOCUMENT frames with no real subject, a synthetic OBJECT target
 * engages; a real subject always wins; a non-DOCUMENT frame resets the debounce.
 */
class DocumentFocusLockGoldenTest {

    @After
    fun reset() = DocumentFocusLockFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun FocusEngine.doc(ts: Long) = selectSubjectAwareness(
        faces = emptyList(), pose = null, objects = emptyList(),
        segmentation = null, depthData = null,
        sceneType = SceneType.DOCUMENT, timestampMs = ts
    )

    private fun FocusEngine.scene(type: SceneType, ts: Long) = selectSubjectAwareness(
        faces = emptyList(), pose = null, objects = emptyList(),
        segmentation = null, depthData = null,
        sceneType = type, timestampMs = ts
    )

    @Test
    fun flagOff_documentSceneYieldsNoTarget() {
        DocumentFocusLockFlag.set(false)
        val e = FocusEngine()
        repeat(DocumentFocusLockFlag.STABILITY_FRAMES + 2) { i ->
            val r = e.doc(1_000L + i)
            assertNull(r.primarySubject)
            assertNull(r.focusPoint)
        }
    }

    @Test
    fun flagOn_engagesAfterStabilityFrames() {
        DocumentFocusLockFlag.set(true)
        val e = FocusEngine()
        for (i in 1 until DocumentFocusLockFlag.STABILITY_FRAMES) {
            assertNull("frame $i is pre-lock (debounce)", e.doc(1_000L + i).primarySubject)
        }
        val r = e.doc(2_000L)
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.OBJECT, r.primarySubject!!.subjectType)
        assertEquals(SubjectCategory.OBJECT, r.primarySubject.category)
        assertEquals(-910, r.primarySubject.trackingId)
        assertEquals(0.5f, r.primarySubject.focusX, 1e-4f)
        assertNotNull(r.focusPoint)
        assertEquals(0.5f, r.focusPoint!!.focusX, 1e-4f)
    }

    @Test
    fun flagOn_realSubjectAlwaysWins() {
        DocumentFocusLockFlag.set(true)
        val e = FocusEngine()
        val face = TrackedFace(
            trackingId = 1, boundingBox = rect(0.3f, 0.3f, 0.5f, 0.5f),
            leftEye = null, rightEye = null, confidence = 0.9f,
            velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
        )
        repeat(DocumentFocusLockFlag.STABILITY_FRAMES + 2) { i ->
            val r = e.selectSubjectAwareness(
                faces = listOf(face), pose = null, objects = emptyList(),
                segmentation = null, depthData = null,
                sceneType = SceneType.DOCUMENT, timestampMs = 1_000L + i
            )
            assertNotNull(r.primarySubject)
            assertEquals(1, r.primarySubject!!.trackingId) // the face, never the -910 document target
        }
    }

    @Test
    fun flagOn_nonDocumentFrameResetsDebounce() {
        DocumentFocusLockFlag.set(true)
        val e = FocusEngine()
        repeat(3) { i -> e.doc(1_000L + i) }
        e.scene(SceneType.GENERAL, 1_100L) // resets the counter
        // First DOCUMENT frame after the reset must NOT be locked yet.
        assertNull(e.doc(1_200L).primarySubject)
    }
}
