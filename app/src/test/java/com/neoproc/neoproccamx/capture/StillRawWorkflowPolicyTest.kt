package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class StillRawWorkflowPolicyTest {

    @Test
    fun singleCaptureNativeRaw_usesSeparateDngCompanionTag() {
        val presentation = StillRawWorkflowPolicy.resolveSinglePresentation(
            StillCaptureCapturePolicy.SingleCaptureRawMode.NATIVE_RAW_COMPANION
        )

        assertEquals("IMG", presentation.primaryTag)
        assertEquals("RAW_DNG", presentation.rawCompanionTag)
        assertEquals("RAW photo", presentation.failureLabel)
    }

    @Test
    fun singleCaptureSoftwareFallback_isPresentedAsProcessedSourcePackageNotRaw() {
        val presentation = StillRawWorkflowPolicy.resolveSinglePresentation(
            StillCaptureCapturePolicy.SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK
        )

        assertEquals("IMG", presentation.primaryTag)
        assertEquals("SOURCE_PKG", presentation.rawCompanionTag)
        assertEquals("Photo", presentation.failureLabel)
    }

    @Test
    fun hdrPresentation_usesCinemaRawCompanionTagInStrictMode() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val presentation = StillRawWorkflowPolicy.resolveHdrPresentation(rawCompanionAfterPrimary = true)

            assertEquals("HDR_RAW_DNG_CINEMA", presentation.rawCompanionTag)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
