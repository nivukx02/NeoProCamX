package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Ndr10KneeCurveComputeShaderContractTest {
    private val shader = File("src/main/cpp/shaders/vulkan/ndr10_knee_curve.comp")
    private val bridgeSource by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun shaderDefinesRgba16fLinearInputAndToneMappedOutput() {
        val source = shader.readText()

        assertTrue(shader.isFile)
        assertTrue(source.contains("#version 450"))
        assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
        assertTrue(source.contains("layout(set = 0, binding = 0, rgba16f) readonly uniform image2D uInputLinearRgba16f;"))
        assertTrue(source.contains("layout(set = 0, binding = 1, rgba16f) writeonly uniform image2D uOutputToneMappedRgba16f;"))
        assertTrue(source.contains("uint width;"))
        assertTrue(source.contains("uint height;"))
        assertTrue(source.contains("float kneePoint;"))
        assertTrue(source.contains("float kneeHardness;"))
        assertTrue(source.contains("float maxCllNits;"))
        assertTrue(source.contains("const float DEFAULT_CINEMA_KNEE_POINT = 0.65;"))
        assertTrue(source.contains("const float DEFAULT_MAX_CLL_NITS = 1000.0;"))
    }

    @Test
    fun shaderAppliesIndependentAdaptiveSigmoidModifiedReinhardKnee() {
        val source = shader.readText()

        assertTrue(source.contains("adaptiveSigmoidKneeWeight"))
        assertTrue(source.contains("modifiedReinhardKnee"))
        assertTrue(source.contains("clamp(params.kneePoint > 0.0 ? params.kneePoint : DEFAULT_CINEMA_KNEE_POINT, 0.55, 0.75)"))
        assertTrue(source.contains("clamp(params.kneeHardness, 1.0, 3.0)"))
        assertTrue(source.contains("imageLoad(uInputLinearRgba16f, coord)"))
        assertTrue(source.contains("vec3 mapped = params.enabled <= 0.5"))
        // Hue-preserving application (audit rank 7 fix): the knee is driven from the
        // max RGB channel and all channels scaled equally, instead of per-channel.
        assertTrue(source.contains("float kneeDrive = max(max(exposed.r, exposed.g), exposed.b)"))
        assertTrue(source.contains("modifiedReinhardKnee(kneeDrive, kneePoint, kneeHardness, maxCllNits)"))
        assertTrue(source.contains(": exposed * kneeScale"))
        assertFalse(source.contains("modifiedReinhardKnee(exposed.r,"))
        assertTrue(source.contains("imageStore(uOutputToneMappedRgba16f, coord, vec4(mapped, source.a))"))
        assertFalse(source.contains("dot("))
        assertFalse(source.contains("vendorCertifiedHdr10Plus = true"))
        assertFalse(source.contains("vendorCertifiedDolbyVision = true"))
    }

    @Test
    fun shaderHasGeneratedSpirvArtifactAndNativeCapabilityReporting() {
        val header = File("src/main/cpp/generated/ndr10_knee_curve_comp_spv.h")
        val spv = File("src/main/cpp/generated/ndr10_knee_curve.comp.spv")
        val headerSource = header.readText()

        assertTrue(spv.isFile)
        assertTrue(spv.length() > 0L)
        assertTrue(headerSource.contains("kNdr10KneeCurveCompSpv"))
        assertTrue(headerSource.contains("kNdr10KneeCurveCompSpvCount"))
        assertTrue(headerSource.contains("kNdr10KneeCurveCompSpvSize"))
        assertTrue(bridgeSource.contains("#include \"generated/ndr10_knee_curve_comp_spv.h\""))
        assertTrue(bridgeSource.contains("ndr10KneeCurveShaderWords="))
        assertTrue(bridgeSource.contains("kNdr10KneeCurveCompSpvCount"))
        assertTrue(bridgeSource.contains("ndr10KneeCurve=adaptive-sigmoid-modified-reinhard-rgba16f"))
    }
}
