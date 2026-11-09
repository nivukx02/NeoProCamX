package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.VulkanNativeRenderCommitAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRenderPublicationPolicyTest {
    @Test
    fun `accepted native LUT publishes its exact identity`() {
        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = true,
            desiredSize = 33,
            desiredContentHash = 0x1234,
            renderProfileGeneration = 19L,
            nativeUploadAccepted = true
        )

        assertTrue(result.snapshot.enabled)
        assertEquals(33, result.snapshot.size)
        assertEquals(0x1234, result.snapshot.contentHash)
        assertEquals(19L, result.snapshot.renderProfileGeneration)
        assertFalse(result.softwareFallbackRequired)
    }

    @Test
    fun `rejected native LUT cannot advertise enabled identity`() {
        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = true,
            desiredSize = 65,
            desiredContentHash = 0x5678,
            renderProfileGeneration = 23L,
            nativeUploadAccepted = false
        )

        assertFalse(result.snapshot.enabled)
        assertEquals(0, result.snapshot.size)
        assertEquals(0, result.snapshot.contentHash)
        assertEquals(23L, result.snapshot.renderProfileGeneration)
        assertTrue(result.softwareFallbackRequired)
    }

    @Test
    fun `disabled LUT remains disabled without a false fallback warning`() {
        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = false,
            desiredSize = 0,
            desiredContentHash = 0,
            renderProfileGeneration = 29L,
            nativeUploadAccepted = false
        )

        assertFalse(result.snapshot.enabled)
        assertEquals(0, result.snapshot.size)
        assertFalse(result.softwareFallbackRequired)
    }

    @Test
    fun `rejected native disable preserves the previously accepted identity`() {
        val previous = NativePreviewLutSnapshot(
            enabled = true,
            size = 33,
            contentHash = 0x1357,
            renderProfileGeneration = 31L
        )

        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = false,
            desiredSize = 0,
            desiredContentHash = 0,
            renderProfileGeneration = 32L,
            nativeUploadAccepted = false,
            previousNativeSnapshot = previous
        )

        assertTrue(result.snapshot.enabled)
        assertEquals(previous.size, result.snapshot.size)
        assertEquals(previous.contentHash, result.snapshot.contentHash)
        assertEquals(32L, result.snapshot.renderProfileGeneration)
        assertFalse(result.softwareFallbackRequired)
    }

    @Test
    fun `failed replacement and failed native clear preserve identity without double ownership`() {
        val previous = NativePreviewLutSnapshot(
            enabled = true,
            size = 33,
            contentHash = 0x2468,
            renderProfileGeneration = 37L
        )

        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = true,
            desiredSize = 65,
            desiredContentHash = 0x9753,
            renderProfileGeneration = 38L,
            nativeUploadAccepted = false,
            previousNativeSnapshot = previous,
            nativeDisableAcceptedAfterFailure = false
        )

        assertTrue(result.snapshot.enabled)
        assertEquals(previous.size, result.snapshot.size)
        assertEquals(previous.contentHash, result.snapshot.contentHash)
        assertEquals(38L, result.snapshot.renderProfileGeneration)
        assertFalse(result.softwareFallbackRequired)
    }

    @Test
    fun `failed replacement after confirmed native clear permits software ownership`() {
        val previous = NativePreviewLutSnapshot(
            enabled = true,
            size = 33,
            contentHash = 0x369C,
            renderProfileGeneration = 41L
        )

        val result = NativePreviewLutPublicationPolicy.resolve(
            desiredEnabled = true,
            desiredSize = 65,
            desiredContentHash = 0x147A,
            renderProfileGeneration = 42L,
            nativeUploadAccepted = false,
            previousNativeSnapshot = previous,
            nativeDisableAcceptedAfterFailure = true
        )

        assertFalse(result.snapshot.enabled)
        assertEquals(0, result.snapshot.size)
        assertEquals(0, result.snapshot.contentHash)
        assertEquals(42L, result.snapshot.renderProfileGeneration)
        assertTrue(result.softwareFallbackRequired)
    }

    @Test
    fun `native render publication requires every stage`() {
        fun admission(
            hdr: Boolean = true,
            tenBit: Boolean = true,
            renderConfig: Boolean = true,
            subjectMask: Boolean = true,
            correction: Boolean = true,
            enhancement: Boolean = true
        ) = VulkanNativeRenderCommitAdmission(
            hdrDynamicRangeAccepted = hdr,
            tenBitSdrDisplayPreferenceAccepted = tenBit,
            renderConfigAccepted = renderConfig,
            subjectDetailMaskAccepted = subjectMask,
            correctionAccepted = correction,
            enhancementUniformsAccepted = enhancement
        )

        assertTrue(
            admission().accepted
        )
        listOf(
            admission(hdr = false),
            admission(tenBit = false),
            admission(renderConfig = false),
            admission(subjectMask = false),
            admission(correction = false),
            admission(enhancement = false)
        ).forEach { assertFalse(it.accepted) }
    }

    @Test
    fun `native render rejection identifies every failed stage`() {
        val result = VulkanNativeRenderCommitAdmission(
            hdrDynamicRangeAccepted = false,
            tenBitSdrDisplayPreferenceAccepted = false,
            renderConfigAccepted = false,
            subjectDetailMaskAccepted = false,
            correctionAccepted = false,
            enhancementUniformsAccepted = false
        )

        assertEquals(
            "hdr-dynamic-range,ten-bit-sdr-display,render-config,subject-detail-mask," +
                "ndr-correction,enhancement-uniforms",
            result.rejectedStages()
        )
    }
}
