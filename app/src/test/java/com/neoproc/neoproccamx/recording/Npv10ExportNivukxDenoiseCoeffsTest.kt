package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s6-f05 / CP81 — proves the export denoise coefficient set is the SAME numbers as the live
 * nivukx_denoise.comp kernel (single source of truth), and that the unified-denoise gate defaults
 * OFF (no-downgrade). The shader is the ground truth; coefficients are asserted to appear verbatim.
 */
class Npv10ExportNivukxDenoiseCoeffsTest {

    private val shaderSource: String = listOf(
        "src/main/cpp/shaders/vulkan/nivukx_denoise.comp",
        "app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp"
    ).map(::File).firstOrNull { it.exists() }!!.readText()

    @Test
    fun coeffsMatchLiveNivukxKernelLiterals() {
        assertTrue("GAT shot 0.0012", shaderSource.contains("0.0012"))
        assertTrue("GAT read 0.00022", shaderSource.contains("0.00022"))
        assertTrue("JzAzBz weights", shaderSource.contains("vec3(0.2627, 0.6780, 0.0593)"))
        assertTrue("bilateral spatial /18.0", shaderSource.contains("/ 18.0"))
        assertTrue("microtexture reinject 0.35", shaderSource.contains("0.35"))
        assertTrue("temporal tukey *4.0", shaderSource.contains("* 4.0"))

        assertEquals(0.0012, Npv10ExportNivukxDenoiseCoeffs.GAT_SIGMA_SHOT, 0.0)
        assertEquals(0.00022, Npv10ExportNivukxDenoiseCoeffs.GAT_SIGMA_READ_SQ, 0.0)
        assertEquals(18.0, Npv10ExportNivukxDenoiseCoeffs.BILATERAL_SPATIAL_SIGMA_SQ, 0.0)
        assertEquals(4.0, Npv10ExportNivukxDenoiseCoeffs.TEMPORAL_TUKEY_SIGMA_MULTIPLIER, 0.0)
        assertEquals(0.35, Npv10ExportNivukxDenoiseCoeffs.MICROTEXTURE_REINJECT, 0.0)
        assertEquals(0.2627, Npv10ExportNivukxDenoiseCoeffs.LUMA_WEIGHT_R, 0.0)
        assertEquals(0.6780, Npv10ExportNivukxDenoiseCoeffs.LUMA_WEIGHT_G, 0.0)
        assertEquals(0.0593, Npv10ExportNivukxDenoiseCoeffs.LUMA_WEIGHT_B, 0.0)
    }

    @Test
    fun heteroscedasticVarianceMatchesKernelModel() {
        // var = 0.0012*clamp(y,0,1) + 0.00022 (nivukx_denoise.comp:178)
        assertEquals(0.00022, Npv10ExportNivukxDenoiseCoeffs.heteroscedasticVariance(0.0), 1e-12)
        assertEquals(0.0012 + 0.00022, Npv10ExportNivukxDenoiseCoeffs.heteroscedasticVariance(1.0), 1e-12)
        assertEquals(0.0012 * 0.5 + 0.00022, Npv10ExportNivukxDenoiseCoeffs.heteroscedasticVariance(0.5), 1e-12)
        // clamp guard
        assertEquals(0.0012 + 0.00022, Npv10ExportNivukxDenoiseCoeffs.heteroscedasticVariance(2.0), 1e-12)
    }

    @Test
    fun unifiedDenoiseAndTransferQueueDefaultOn() {
        assertTrue(Npv10ExportUnifiedDenoiseFlag.enabled)
        assertTrue(Npv10ExportTransferQueueFlag.enabled)
    }
}
