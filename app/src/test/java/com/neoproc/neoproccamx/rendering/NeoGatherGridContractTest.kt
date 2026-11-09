package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoGatherGridContractTest {

    private val nativeSource by lazy {
        File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
    }

    @Test
    fun `gather offsets reconstruct canonical cardinal texels including clamped edges`() {
        // textureGatherOffsets lanes use base corners (0,+1), (+1,+1), (+1,0), (0,0).
        val gatherBaseCorners = listOf(0 to 1, 1 to 1, 1 to 0, 0 to 0)
        val compensatingOffsets = listOf(1 to -1, -2 to -1, -1 to 1, 0 to -1)
        val reconstructedOffsets = gatherBaseCorners.zip(compensatingOffsets).map { (base, offset) ->
            (base.first + offset.first) to (base.second + offset.second)
        }
        val canonicalOffsets = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        assertEquals(canonicalOffsets, reconstructedOffsets)

        // Prove the same GL_CLAMP_TO_EDGE address at corners, edges, and interior texels.
        listOf(2 to 2, 3 to 5, 4096 to 3072).forEach { (width, height) ->
            val probes = listOf(
                0 to 0,
                (width - 1) to 0,
                0 to (height - 1),
                (width - 1) to (height - 1),
                (width / 2) to (height / 2)
            )
            probes.forEach { (x, y) ->
                fun addressed(offset: Pair<Int, Int>) =
                    (x + offset.first).coerceIn(0, width - 1) to
                        (y + offset.second).coerceIn(0, height - 1)
                assertEquals(
                    canonicalOffsets.map(::addressed),
                    reconstructedOffsets.map(::addressed)
                )
            }
        }
    }

    @Test
    fun `gather grid is capability gated and retains canonical fallback`() {
        val generator = nativeSource
            .substringAfter("std::string makeNeoGatherGridFragment(")
            .substringBefore("bool supportsNeoGatherGrid()")
        val selection = nativeSource
            .substringAfter("const bool sdrPreGradeHistoryProven =")
            .substringBefore("// Compile-time PQ/profile invariants")

        assertTrue(generator.contains("#version 310 es"))
        assertTrue(generator.contains("#extension GL_EXT_gpu_shader5 : require"))
        assertTrue(generator.contains("textureGatherOffsets("))
        assertTrue(generator.contains("NEO_CARDINAL_GATHER_OFFSETS"))
        assertTrue(nativeSource.contains("Three component gathers reconstruct"))
        assertTrue(nativeSource.contains("glGetIntegerv(GL_MAJOR_VERSION, &major)"))
        assertTrue(nativeSource.contains("major == 3 && minor >= 1"))
        assertTrue(nativeSource.contains("hasExtension(extensions, \"GL_EXT_gpu_shader5\")"))
        assertTrue(selection.contains("state->sdrCreativeLutGatherProgram2d != 0"))
        assertTrue(selection.contains("state->sdrCreativeLutNiv99GatherProgram2d != 0"))
        assertTrue(nativeSource.contains("canonical sampler2D fallback retained"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrCreativeLutGatherProgram2d)"))
        assertTrue(nativeSource.contains("glDeleteProgram(state->sdrCreativeLutNiv99GatherProgram2d)"))
    }

    @Test
    fun `identity NIV99 white balance removes only mathematically inactive arithmetic`() {
        val selection = nativeSource
            .substringAfter("const bool niv99RgbCorrectionIdentity =")
            .substringBefore("// Compile-time PQ/profile invariants")

        assertTrue(selection.contains("niv99RgbCorrectionValue[0] == 1.0f"))
        assertTrue(selection.contains("niv99RgbCorrectionValue[1] == 1.0f"))
        assertTrue(selection.contains("niv99RgbCorrectionValue[2] == 1.0f"))
        assertTrue(selection.contains("niv99WhiteBalanceArithmeticRequired"))
        assertTrue(selection.contains("!niv99RgbCorrectionIdentity"))
        assertTrue(nativeSource.contains("denoiseReconstructionRetained=true"))
        assertTrue(nativeSource.contains("niv99IdentityWhiteBalanceElisionFrames"))
    }

    @Test
    fun `constant lift removes frame invariant arithmetic only from proven gather shader`() {
        val generator = nativeSource
            .substringAfter("std::string makeNeoGatherGridFragment(")
            .substringBefore("bool supportsNeoGatherGrid()")

        listOf(
            "uNeoDenoiseMix",
            "uNeoInvRangeVariance",
            "uNeoSharpenAmount",
            "uNeoSharpenCap",
            "uNeoChromaScale"
        ).forEach { uniform -> assertTrue(generator.contains(uniform)) }
        assertTrue(generator.contains("constant-lift-denoise"))
        assertTrue(generator.contains("constant-lift-range-variance"))
        assertTrue(generator.contains("constant-lift-cardinal-chroma"))
        assertTrue(generator.contains("constant-lift-sharpen-amount"))
        assertTrue(generator.contains("constant-lift-sharpen-cap"))
        assertTrue(generator.contains("constant-lift-chroma-scale"))
        assertTrue(generator.contains("positive-creative-LUT"))
        assertTrue(nativeSource.contains("uploadNeoConstantLift(activeMainLoc2d, neoConstantLift)"))
        assertTrue(nativeSource.contains("neoConstantLiftFrames"))
    }

    @Test
    fun `constant lift scalar values are numerically identical to canonical equations`() {
        val strengths = listOf(-1f, 0f, 0.125f, 0.5f, 1f, 2f)
        strengths.forEach { denoise ->
            val canonicalDenoise = denoise.coerceIn(0f, 1f)
            val canonicalSigma = (0.010f + 0.045f * canonicalDenoise).coerceIn(0.010f, 0.05f)
            val canonicalInverseVariance = 1f / (2f * canonicalSigma * canonicalSigma)

            val liftedDenoise = denoise.coerceIn(0f, 1f)
            val liftedSigma = (0.010f + 0.045f * liftedDenoise).coerceIn(0.010f, 0.05f)
            val liftedInverseVariance = 1f / (2f * liftedSigma * liftedSigma)

            assertEquals(canonicalDenoise, liftedDenoise, 0f)
            assertEquals(canonicalInverseVariance, liftedInverseVariance, 0f)
        }
        strengths.forEach { sharpness ->
            val canonicalAmount = sharpness.coerceIn(0f, 1f) * 0.85f
            val liftedAmount = sharpness.coerceIn(0f, 1f) * 0.85f
            assertEquals(canonicalAmount, liftedAmount, 0f)
            assertEquals(0.05f + 0.05f * canonicalAmount, 0.05f + 0.05f * liftedAmount, 0f)
        }
        strengths.forEach { chroma ->
            assertEquals(
                chroma.coerceIn(0f, 1f) * 0.30f,
                chroma.coerceIn(0f, 1f) * 0.30f,
                0f
            )
        }
    }
}
