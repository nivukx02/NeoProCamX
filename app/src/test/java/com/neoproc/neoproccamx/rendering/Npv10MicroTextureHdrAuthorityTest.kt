package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract lock for the NPV10 export micro-texture authority fix (audit rank 18).
 *
 * The strict visible-clean HDR/LOG/RAW branch gated its extra micro-texture boost
 * on p010StrictNormalProSdrExportAuthority() > 1.0, but that function only returns
 * 0.0/1.0 — so the boost was permanently dead and HDR/NIVUKX exports silently
 * missed their intended detail recovery. It now uses the correct
 * p010StrictVisibleCleanExportAuthority(), CONSERVATIVELY bounded (no 10x-of-
 * localRange overshoot that would ring).
 */
class Npv10MicroTextureHdrAuthorityTest {

    private val microBody: String by lazy {
        File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
            .substringAfter("uint applyApexMicroTextureRecover(")
            .substringBefore("const int BLUE_NOISE_8X8")
    }

    @Test
    fun microTextureBoostUsesTheLiveVisibleCleanAuthority() {
        assertTrue(microBody.contains("bool strictVisibleCleanExport = p010StrictVisibleCleanExportAuthority() > 1.0;"))
        assertTrue(microBody.contains("(strictVisibleCleanExport ? 1.5 : 1.0)"))
    }

    @Test
    fun deadNormalAuthorityBoostIsGone() {
        // The permanently-false predicate must no longer gate the boost.
        assertFalse(microBody.contains("p010StrictNormalProSdrExportAuthority() > 1.0 ? 10.0"))
    }

    @Test
    fun hdrMicroLimitIsBoundedNotTenTimesLocalRange() {
        // Conservative bounded headroom for clean HDR, above the SDR band but well
        // below the 10x-of-localRange overshoot the audit warned against.
        assertTrue(microBody.contains("mix(0.30, 0.55, profileLift)"))
        assertFalse(microBody.contains("localRange * (p010StrictNormalProSdrExportAuthority() > 1.0 ? 10.0"))
    }
}
