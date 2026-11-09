package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectDetailEnhancementPolicyTest {
    @Test
    fun `natural detail keeps baseline but never adds focused mask sharpening`() {
        val plan = SubjectDetailEnhancementPolicy.resolve(
            validFocusedRequest(profile = VideoDetailProfile.NATURAL_DETAIL)
        )

        assertFalse(plan.subjectDetailRequested)
        assertFalse(plan.subjectDetailApplied)
        assertEquals(0f, plan.additiveDetailGain, 0.000001f)
        assertFalse(plan.naturalZeroSharpening)
        assertEquals("true", plan.toMetadata()["subjectMaskPresent"])
    }

    @Test
    fun `extreme detail is profile driven and does not require subject mask`() {
        val plan = SubjectDetailEnhancementPolicy.resolve(
            validFocusedRequest(profile = VideoDetailProfile.EXTREME_DETAIL)
        )

        assertFalse(plan.subjectDetailRequested)
        assertFalse(plan.subjectDetailApplied)
        assertEquals(0f, plan.additiveDetailGain, 0.000001f)
        assertFalse(plan.naturalZeroSharpening)
        assertEquals("EXTREME_DETAIL", plan.toMetadata().getValue("selectedDetailProfile"))
    }

    @Test
    fun `focused detail requires a current subject mask`() {
        val plan = SubjectDetailEnhancementPolicy.resolve(
            validFocusedRequest().copy(maskAvailable = false, maskTrackingId = null)
        )

        assertTrue(plan.subjectDetailRequested)
        assertFalse(plan.subjectDetailApplied)
        assertEquals(0f, plan.additiveDetailGain, 0.000001f)
    }

    @Test
    fun `focused detail applies bounded detail to a reliable subject mask`() {
        val plan = SubjectDetailEnhancementPolicy.resolve(validFocusedRequest())

        assertTrue(plan.subjectDetailApplied)
        assertTrue(plan.additiveDetailGain > 0f)
        assertTrue(plan.additiveDetailGain <= SubjectDetailEnhancementPolicy.MAX_ADDITIVE_DETAIL_GAIN)
        assertTrue(plan.maskFeather > 0f)
        assertTrue(plan.haloGuard > 0f)
    }

    @Test
    fun `motion noise and protected skin reduce subject gain instead of increasing it`() {
        val clean = SubjectDetailEnhancementPolicy.resolve(validFocusedRequest())
        val difficult = SubjectDetailEnhancementPolicy.resolve(
            validFocusedRequest().copy(
                motionScore = 0.90f,
                noiseFloor = 0.88f,
                skinToneConfidence = 0.92f
            )
        )

        assertTrue(difficult.subjectDetailApplied)
        assertTrue(difficult.additiveDetailGain < clean.additiveDetailGain)
        assertTrue(difficult.haloGuard >= clean.haloGuard)
        assertTrue(difficult.textureGuard >= clean.textureGuard)
    }

    @Test
    fun `focused detail decision is independent of object distance`() {
        val nearSubject = SubjectDetailEnhancementPolicy.resolve(validFocusedRequest())
        val distantSubject = SubjectDetailEnhancementPolicy.resolve(validFocusedRequest())

        assertEquals(nearSubject.additiveDetailGain, distantSubject.additiveDetailGain, 0.000001f)
        assertEquals(nearSubject.signature, distantSubject.signature)
    }

    @Test
    fun `strict imax subject detail uses bounded gain and confidence floor`() {
        val lowConfidence = SubjectDetailEnhancementPolicy.resolve(
            validFocusedRequest().copy(maskConfidence = 0.30f)
        )
        val highConfidence = SubjectDetailEnhancementPolicy.resolve(validFocusedRequest())

        assertEquals(ImaxStrictProfile.MAX_ADDITIVE_DETAIL_GAIN, SubjectDetailEnhancementPolicy.MAX_ADDITIVE_DETAIL_GAIN, 0.000001f)
        assertFalse(lowConfidence.subjectDetailApplied)
        assertEquals(0f, lowConfidence.additiveDetailGain, 0.000001f)
        assertTrue("gain=${highConfidence.additiveDetailGain}", highConfidence.additiveDetailGain > 9.0f)
        assertTrue("feather=${highConfidence.maskFeather}", highConfidence.maskFeather >= 0.14f)
        assertTrue("halo=${highConfidence.haloGuard}", highConfidence.haloGuard >= 0.68f)
        assertTrue("texture=${highConfidence.textureGuard}", highConfidence.textureGuard >= 0.64f)
    }

    private fun validFocusedRequest(
        profile: VideoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
    ): SubjectDetailEnhancementRequest =
        SubjectDetailEnhancementRequest(
            profile = profile,
            maskAvailable = true,
            maskTrackingId = 42,
            maskConfidence = 0.94f,
            motionScore = 0.08f,
            noiseFloor = 0.08f,
            skinToneConfidence = 0.10f
        )
}
