package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * s5-f03 / CP-F4 — subject-aware AF hardening GOLDEN LOCK.
 *
 * Characterisation tests that pin TODAY's subject selection, identity continuity,
 * switch-debounce hysteresis and predictive-lead behaviour of [FocusEngine] /
 * [TrackingMath]. No production constant is changed by this feature; these goldens
 * make any future tuning visible as a regression.
 *
 * JVM-runtime caveat (mockable `android.jar`, `returnDefaultValues=true`):
 * `RectF.centerX()/centerY()/width()/height()` return 0 and `contains()` returns false;
 * only the `left/top/right/bottom` fields are real. Consequences exercised here:
 *  - Face-bounding-box centre fed to the motion tracker is (0,0), so the engine's own
 *    end-to-end predicted lead is (0,0) and CANNOT be exercised through a face here —
 *    the predictive-lead primitive [TrackingMath.predictCenter] is tested directly
 *    instead (this is exactly what [FocusEngine] delegates to). The full face-driven
 *    lead is DEVICE-GATED (real RectF on the vivo I2405).
 *  - FACE anchors collapse to focusX = predictedX = 0f (predicted centre), focusY =
 *    top + height*0.36 via the real top/bottom fields. EYE anchors take their
 *    coordinates straight from the [EyePoint], so eye paths are exact.
 */
class FocusEngineSubjectHardeningGoldenTest {

    // This golden characterises FocusEngine's CORE identity-continuity / switch-debounce. The
    // additive AF-SD coverage producers are default-ON (2026-07-03), so pin the two that RE-SHAPE
    // the candidate set OFF here: DeadSubjectType (F09) would fold a >=2-face frame into one GROUP
    // candidate (overriding the identity hold), and HeadSubject (F10) would re-type an averted face.
    // Their own behaviour is covered by DeadSubjectTypeGoldenLockTest / HeadSubjectGoldenLockTest.
    // NOTE (device-validation item): GROUP-vs-identity-lock priority in the real all-flags-on path is
    // a genuine combined-behaviour question to confirm on-device.
    @Before
    fun isolateCoreSelection() {
        DeadSubjectTypeFlag.set(false)
        HeadSubjectFlag.set(false)
    }

    @After
    fun restoreDefaults() {
        DeadSubjectTypeFlag.set(true)
        HeadSubjectFlag.set(true)
    }

    // ── 1. Target-selection golden: EYE beats BODY/OBJECT by priority ────────────────

    @Test
    fun `multi-subject frame selects the near-eye of the highest-priority face`() {
        val result = FocusEngine().selectSubjectAwareness(
            faces = listOf(
                face(
                    id = 1,
                    box = rectF(0.30f, 0.20f, 0.72f, 0.76f),
                    leftEye = EyePoint(x = 0.41f, y = 0.35f, confidence = 0.92f, isOpen = true),
                    rightEye = EyePoint(x = 0.60f, y = 0.36f, confidence = 0.95f, isOpen = true),
                    confidence = 0.93f
                )
            ),
            pose = TrackedPose(
                trackingId = 50,
                boundingBox = rectF(0.10f, 0.10f, 0.90f, 0.95f),
                landmarks = emptyList(),
                confidence = 0.90f,
                velocityX = 0f,
                velocityY = 0f,
                timestampMs = 1_000L
            ),
            objects = listOf(
                TrackedObject(
                    trackingId = 80,
                    boundingBox = rectF(0.05f, 0.05f, 0.40f, 0.40f),
                    confidence = 0.90f,
                    classId = 1,
                    className = "car",
                    velocityX = 0f,
                    velocityY = 0f,
                    timestampMs = 1_000L
                )
            ),
            segmentation = null,
            depthData = null,
            sceneType = SceneType.PORTRAIT,
            timestampMs = 1_000L
        )

        assertNotNull(result.focusPoint)
        assertEquals(SubjectType.EYE, result.focusPoint!!.subjectType)
        assertEquals(1, result.focusPoint.trackingId)
        // Higher-confidence right eye, taken verbatim from the EyePoint (no smoothing on
        // the first frame).
        assertEquals(0.60f, result.focusPoint.focusX, 0.0001f)
        assertEquals(0.36f, result.focusPoint.focusY, 0.0001f)
        assertEquals(SubjectType.EYE, result.primarySubject!!.subjectType)
        assertEquals(1, result.primarySubject.trackingId)
    }

    // ── 2. Identity continuity: held against a higher-confidence competing face ──────

    @Test
    fun `locked subject is held when a higher-confidence competing face enters frame`() {
        val engine = FocusEngine()

        // Frame 1: only subject A (no usable eyes -> FACE), moderate confidence.
        val a1 = engine.selectSubjectAwareness(
            faces = listOf(face(id = 1, box = rectF(0.30f, 0.20f, 0.70f, 0.80f), confidence = 0.70f)),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_000L
        )
        assertEquals(1, a1.primarySubject!!.trackingId)

        // Frame 2: A persists; competitor B enters with HIGHER raw confidence.
        // Without continuity B would win (15.7125 > 15.525). With continuity A holds
        // (17.905 > 16.489).
        val f2 = engine.selectSubjectAwareness(
            faces = listOf(
                face(id = 1, box = rectF(0.30f, 0.20f, 0.70f, 0.80f), confidence = 0.70f),
                face(id = 2, box = rectF(0.32f, 0.05f, 0.68f, 0.35f), confidence = 0.95f)
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_033L
        )

        assertEquals("identity must be held through a competing face", 1, f2.primarySubject!!.trackingId)
        assertEquals(SubjectType.FACE, f2.primarySubject.subjectType)
    }

    // ── 2b. Identity held through an OCCLUDED (still detected) frame ──────────────────

    @Test
    fun `locked subject is held while occluded-but-detected with a competitor present`() {
        val engine = FocusEngine()

        engine.selectSubjectAwareness(
            faces = listOf(face(id = 1, box = rectF(0.30f, 0.20f, 0.70f, 0.80f), confidence = 0.80f)),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_000L
        )

        // A is heavily occluded (still detected, occlusion just below the eye-lock gate
        // so it stays a FACE) while competitor B is clean.
        val f2 = engine.selectSubjectAwareness(
            faces = listOf(
                face(id = 1, box = rectF(0.30f, 0.20f, 0.70f, 0.80f), confidence = 0.80f, occlusion = 0.65f),
                face(id = 2, box = rectF(0.32f, 0.05f, 0.68f, 0.35f), confidence = 0.80f)
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_033L
        )

        assertEquals(1, f2.primarySubject!!.trackingId)
    }

    // ── 2c. Characterise: a FULL detection gap resets the primary (current behaviour) ─

    @Test
    fun `empty detection frame clears the primary subject (locked current behaviour)`() {
        val engine = FocusEngine()

        engine.selectSubjectAwareness(
            faces = listOf(face(id = 1, box = rectF(0.30f, 0.20f, 0.70f, 0.80f), confidence = 0.80f)),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_000L
        )

        val gap = engine.selectSubjectAwareness(
            faces = emptyList(), pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_033L
        )

        // No candidates -> no primary / focus this frame. (Carrying identity across a
        // full detection gap is a future hardening item; locked here so any change is
        // golden-gated.)
        assertEquals(null, gap.primarySubject)
        assertEquals(null, gap.focusPoint)
    }

    // ── 3. Switch debounce: a near-target subject switch BLENDS (no per-frame snap) ───

    @Test
    fun `near-target subject switch blends instead of snapping`() {
        val engine = FocusEngine()

        // Frame 1: subject A, eye at (0.45, 0.40).
        val f1 = engine.selectSubjectAwareness(
            faces = listOf(
                face(
                    id = 1, box = rectF(0.30f, 0.20f, 0.90f, 0.80f),
                    rightEye = EyePoint(x = 0.45f, y = 0.40f, confidence = 0.95f, isOpen = true),
                    confidence = 0.95f
                )
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_000L
        )
        assertEquals(0.45f, f1.focusPoint!!.focusX, 0.0001f)

        // Frame 2: A gone, subject B's eye at (0.55, 0.40) -> switch, distance 0.10 <=
        // hard-cut 0.22 -> cinematic blend at alpha 0.35.
        val f2 = engine.selectSubjectAwareness(
            faces = listOf(
                face(
                    id = 2, box = rectF(0.30f, 0.20f, 0.90f, 0.80f),
                    rightEye = EyePoint(x = 0.55f, y = 0.40f, confidence = 0.95f, isOpen = true),
                    confidence = 0.95f
                )
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_033L
        )

        // 0.45 + (0.55 - 0.45) * 0.35 = 0.485 ; must NOT snap to 0.55.
        assertEquals(0.485f, f2.focusPoint!!.focusX, 0.0005f)
        assertNotEquals(0.55f, f2.focusPoint.focusX, 0.02f)
    }

    @Test
    fun `far-target subject switch hard-cuts to the new subject`() {
        val engine = FocusEngine()

        engine.selectSubjectAwareness(
            faces = listOf(
                face(
                    id = 1, box = rectF(0.10f, 0.20f, 0.95f, 0.80f),
                    rightEye = EyePoint(x = 0.20f, y = 0.40f, confidence = 0.95f, isOpen = true),
                    confidence = 0.95f
                )
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_000L
        )

        // New subject eye at (0.80, 0.40): distance 0.60 > hard-cut 0.22 -> snap.
        val f2 = engine.selectSubjectAwareness(
            faces = listOf(
                face(
                    id = 2, box = rectF(0.10f, 0.20f, 0.95f, 0.80f),
                    rightEye = EyePoint(x = 0.80f, y = 0.40f, confidence = 0.95f, isOpen = true),
                    confidence = 0.95f
                )
            ),
            pose = null, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.PORTRAIT, timestampMs = 1_033L
        )

        assertEquals(0.80f, f2.focusPoint!!.focusX, 0.0001f)
    }

    // ── 4. Predictive lead: prediction primitive leads a constant-velocity track ─────

    @Test
    fun `predictCenter leads a constant-velocity track in the velocity direction`() {
        // pos (0.40, 0.50), vx = +0.6/s, no acceleration.
        val state = MotionState(
            centerX = 0.40f,
            centerY = 0.50f,
            velocityX = 0.6f,
            velocityY = 0f,
            confidence = 1f,
            updatedAtMs = 0L
        )

        val lead = TrackingMath.predictCenter(state, leadTimeMs = 50L)

        // 0.40 + 0.6 * 0.05 = 0.43 ; y unchanged.
        assertEquals(0.43f, lead.first, 0.0001f)
        assertEquals(0.50f, lead.second, 0.0001f)
        assertTrue("predicted point must lead the last observation", lead.first > state.centerX)
    }

    @Test
    fun `zero-velocity track predicts no lead`() {
        val state = MotionState(
            centerX = 0.40f,
            centerY = 0.50f,
            velocityX = 0f,
            velocityY = 0f,
            confidence = 1f,
            updatedAtMs = 0L
        )

        val lead = TrackingMath.predictCenter(state, leadTimeMs = 50L)

        assertEquals(0.40f, lead.first, 0.0001f)
        assertEquals(0.50f, lead.second, 0.0001f)
    }

    // ── 5. Score determinism: identical inputs -> identical chosen target + score ────

    @Test
    fun `selection is deterministic across two independent engines`() {
        fun run(): SubjectAwarenessResult =
            FocusEngine().selectSubjectAwareness(
                faces = listOf(
                    face(
                        id = 3, box = rectF(0.30f, 0.20f, 0.72f, 0.76f),
                        leftEye = EyePoint(x = 0.41f, y = 0.35f, confidence = 0.92f, isOpen = true),
                        rightEye = EyePoint(x = 0.60f, y = 0.36f, confidence = 0.95f, isOpen = true),
                        confidence = 0.93f
                    )
                ),
                pose = null, objects = emptyList(),
                segmentation = null, depthData = null,
                sceneType = SceneType.PORTRAIT, timestampMs = 2_000L
            )

        val a = run()
        val b = run()
        assertNotNull(a.focusPoint)
        assertNotNull(b.focusPoint)
        assertEquals(a.focusPoint!!.subjectType, b.focusPoint!!.subjectType)
        assertEquals(a.focusPoint.trackingId, b.focusPoint.trackingId)
        assertEquals(a.focusPoint.focusX, b.focusPoint.focusX, 0f)
        assertEquals(a.focusPoint.focusY, b.focusPoint.focusY, 0f)
        assertEquals(a.focusPoint.confidence, b.focusPoint.confidence, 0f)
        assertEquals(a.primarySubject!!.confidence, b.primarySubject!!.confidence, 0f)
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private fun face(
        id: Int,
        box: RectF,
        leftEye: EyePoint? = null,
        rightEye: EyePoint? = null,
        confidence: Float = 0.9f,
        occlusion: Float = 0.05f
    ): TrackedFace =
        TrackedFace(
            trackingId = id,
            boundingBox = box,
            leftEye = leftEye,
            rightEye = rightEye,
            confidence = confidence,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 1_000L,
            occlusionLevel = occlusion
        )

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
