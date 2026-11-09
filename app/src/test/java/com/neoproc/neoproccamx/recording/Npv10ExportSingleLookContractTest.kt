package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s6-f06 — single-source-of-truth look contract. Locks that the SDR/PRO export profiles reference
 * the shared live look constants (NormalSdrVideoQualityPolicy.EXPORT_*) — a silent fork breaks this
 * test — and pins the DEFAULT/NDR10_PLUS/NIVUKX_VISION profiles as still-forked literals, naming the
 * CP81 de-fork as the device-gated follow-up that makes export reference the live source.
 */
class Npv10ExportSingleLookContractTest {

    private val converterSource: String = listOf(
        "src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt",
        "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt"
    ).map(::File).firstOrNull { it.exists() }!!.readText()

    @Test
    fun sdrAndProExportProfilesReferenceSharedLiveLookConstants() {
        // SDR_VIDEO is built entirely from the shared NormalSdrVideoQualityPolicy.EXPORT_* source.
        assertTrue(
            converterSource.contains("chromaSuppression = NormalSdrVideoQualityPolicy.EXPORT_CHROMA_SUPPRESSION")
        )
        assertTrue(
            converterSource.contains("fineTextureDetailGain = NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_DETAIL_GAIN")
        )
        // PRO_SDR_VIDEO is a copy() that overrides only via shared EXPORT_PRO_* constants.
        assertTrue(
            converterSource.contains("fineTextureDetailGain = NormalSdrVideoQualityPolicy.EXPORT_PRO_FINE_TEXTURE_DETAIL_GAIN")
        )
    }

    @Test
    fun forkedExportProfilesArePinnedPendingCp81Unification() {
        // NIVUKX_VISION is still a fork (literal coefficients, no shared live-look reference).
        // CP81 de-fork (export references the live nivukx coefficients) is the device-gated follow-up;
        // until then this pins the current fork so the de-fork is a visible, reviewable diff.
        val nivukxBlock = converterSource
            .substringAfter("val NIVUKX_VISION = Npv10ExportEnhancementProfile(")
            .take(700)
        assertTrue(nivukxBlock.contains("chromaSuppression = 0.002"))
        assertFalse(
            "NIVUKX export look is forked today (CP81 de-fork pending) — it must not yet reference the shared policy",
            nivukxBlock.contains("NormalSdrVideoQualityPolicy")
        )
    }
}
