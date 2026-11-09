package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrNivukxMeasuredShaderContractTest {
    private val shader by lazy {
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
    }

    @Test
    fun measuredSkinGuardDoesNotCoolSkinByBoostingBlue() {
        val guard = shaderFunction("ndrMeasuredSkinHueGuard")

        assertTrue(guard.contains("bluePressure"))
        assertTrue(guard.contains("skinMemory"))
        assertTrue(guard.contains("redSkinBackstop"))
        assertTrue(guard.contains("redPlatePressure"))
        assertTrue(guard.contains("redSkinHardCeiling"))
        assertTrue(guard.contains("coolSkinPressure"))
        assertTrue(guard.contains("skinWarmthFloor"))
        assertTrue(guard.contains("skinBlueFloor"))
        assertTrue(guard.contains("skinBlueCeiling"))
        assertTrue(guard.contains("skinLumaTrim"))
        assertTrue(guard.contains("max(max(skinToneWeight(source), skinMemory * 0.86), redSkinBackstop * 0.78)"))
        assertTrue(guard.contains("min(corrected.r, redSkinHardCeiling)"))
        assertTrue(guard.contains("strength * coolSkinPressure * 0.30 * (1.0 - redSkinBackstop)"))
        assertTrue(guard.contains("clamp(corrected.b, skinBlueFloor, skinBlueCeiling)"))
        assertTrue(guard.contains("clamp(y / correctedY, 0.90, 1.035)"))
        assertFalse(guard.contains("max(corrected.b, corrected.g * 0.84)"))
    }

    @Test
    fun measuredSkyGuardHandlesWarmSkyAndPreservesLuma() {
        val guard = shaderFunction("ndrMeasuredSkyHueGuard")

        assertTrue(guard.contains("warmSkyWindow"))
        assertTrue(guard.contains("warmHighlightSky"))
        assertTrue(guard.contains("skyHueReject"))
        assertTrue(guard.contains("skyBlueDeficit"))
        assertTrue(guard.contains("skyExposureTrim"))
        assertTrue(guard.contains("skyCandidate"))
        assertTrue(guard.contains("skyBlueTarget"))
        assertTrue(guard.contains("postSkyBlueFloor"))
        assertTrue(guard.contains("skyLumaTrim"))
        assertTrue(guard.contains("nivukxVision ? 0.82 : 0.76"))
        assertTrue(guard.contains("nivukxVision ? 0.97 : 0.92"))
        assertFalse(guard.contains("corrected * (y / correctedY)"))
    }

    @Test
    fun redGuardDoesNotTurnSaturatedRedIntoMagentaBlue() {
        val guard = shaderFunction("ndrRedChannelOverflowGuard")

        assertTrue(guard.contains("redObject"))
        assertTrue(guard.contains("redObjectTrim"))
        assertTrue(guard.contains("skinBlueFloor"))
        assertTrue(guard.contains("objectRedCeiling"))
        assertTrue(guard.contains("redObjectLumaCeiling"))
        assertTrue(guard.contains("min(target.b, target.g * 0.86)"))
        assertTrue(guard.contains("clamp(y / targetY, 0.90, 1.02)"))
        assertTrue(guard.contains("mix(1.0, 0.992, redObject * guard)"))
        assertFalse(guard.contains("target.g * 0.80), guard * 0.46"))
    }

    @Test
    fun redPlateRecoveryRebuildsCrushedGreenBlueBeforeOutput() {
        val guard = shaderFunction("ndrRedPlateRecoveryGuard")
        val finalGuard = shaderFunction("ndrMeasuredFinalColorGuard")

        assertTrue(guard.contains("crushedCompanions"))
        assertTrue(guard.contains("redPlate"))
        assertTrue(guard.contains("skinPlateAuthority"))
        assertTrue(guard.contains("float greenFloor = y * (nivukxVision ? 0.82 : 0.88)"))
        assertTrue(guard.contains("float blueFloor = y * (nivukxVision ? 0.50 : 0.56)"))
        assertTrue(guard.contains("max(max(recovered.b, blueFloor), recovered.g * (nivukxVision ? 0.50 : 0.56))"))
        assertTrue(guard.contains("recovered.r = mix(recovered.r, min(recovered.r, redCeiling), strength)"))
        assertTrue(guard.contains("clamp(y / recoveredY, 0.88, 1.04)"))
        assertTrue(finalGuard.contains("ndrRedPlateRecoveryGuard"))
        assertTrue(finalGuard.indexOf("ndrRedPlateRecoveryGuard") < finalGuard.indexOf("ndrRedChannelOverflowGuard"))
        assertTrue(finalGuard.lastIndexOf("ndrRedPlateRecoveryGuard") > finalGuard.indexOf("ndrRedChannelOverflowGuard"))
    }

    @Test
    fun measuredHighlightGuardCoolsNeutralSkyHighlightsWithoutTouchingWarmSkin() {
        val guard = shaderFunction("ndrMeasuredHighlightBalanceGuard")
        val finalGuard = shaderFunction("ndrMeasuredFinalColorGuard")

        assertTrue(guard.contains("highlightNeutral"))
        assertTrue(guard.contains("blueDeficit"))
        assertTrue(guard.contains("cloudNeutralWindow"))
        assertTrue(guard.contains("warmPreserve"))
        assertTrue(guard.contains("highlightLumaTrim"))
        assertTrue(guard.contains("max(max(corrected.r, corrected.g), corrected.b)"))
        assertTrue(finalGuard.contains("ndrMeasuredHighlightBalanceGuard"))
    }

    @Test
    fun measuredUniformCorrectionPayloadReachesLocalizedSkySkinAndHighlightGuard() {
        val guard = shaderFunction("ndrMeasuredUniformCalibrationGuard")
        val finalGuard = shaderFunction("ndrMeasuredFinalColorGuard")

        assertTrue(shader.contains("vec4 uNeoMeasuredCorrectionParams"))
        assertTrue(guard.contains("uNeoMeasuredCorrectionParams.x"))
        assertTrue(guard.contains("uNeoMeasuredCorrectionParams.y"))
        assertTrue(guard.contains("uNeoMeasuredCorrectionParams.z"))
        assertTrue(guard.contains("uNeoMeasuredCorrectionParams.w"))
        assertTrue(guard.contains("warmSkinWindow"))
        assertTrue(guard.contains("warmSkyRepair"))
        assertTrue(guard.contains("neutralHighlight"))
        assertTrue(guard.contains("skinAuthority"))
        assertTrue(guard.contains("skyAuthority"))
        assertTrue(finalGuard.contains("ndrMeasuredUniformCalibrationGuard"))
    }

    @Test
    fun finalMeasuredGuardRunsAfterToneKnee() {
        val finalGuard = shaderFunction("ndrMeasuredFinalColorGuard")
        val source = shader

        assertTrue(finalGuard.contains("ndrMeasuredSkyHueGuard"))
        assertTrue(finalGuard.contains("ndrMeasuredSkinHueGuard"))
        assertTrue(finalGuard.contains("ndrRedChannelOverflowGuard"))
        assertTrue(finalGuard.contains("ndrMeasuredUniformCalibrationGuard"))
        assertTrue(finalGuard.indexOf("ndrMeasuredSkyHueGuard") > finalGuard.indexOf("ndrRedChannelOverflowGuard"))
        assertTrue(source.contains("mapped = ndrMeasuredFinalColorGuard(mapped, nivukxVision);"))
        assertTrue(source.contains("linear = ndrMeasuredFinalColorGuard(linear, nivukxVision);"))
    }

    @Test
    fun ndrDisplayColorVolumeFitCompressesOvershootWithoutPlainClipping() {
        val fit = shaderFunction("ndrDisplayColorVolumeFit")
        val rawTransform = shaderFunction("rawLinearToDisplayNdrTransform")

        assertTrue(fit.contains("float white = nivukxVision ? 0.945 : 0.965"))
        assertTrue(fit.contains("float scale = clamp((white - y) / max(peak - y, 1e-5), 0.0, 1.0)"))
        assertTrue(fit.contains("fitted = vec3(y) + (fitted - vec3(y)) * scale"))
        assertTrue(fit.contains("float chromaPressure = smoothstep(0.28, 0.72, spanRatio)"))
        assertTrue(fit.contains("float fringePressure"))
        assertTrue(fit.contains("fitted = vec3(y) + (fitted - vec3(y)) * (1.0 - fitStrength)"))
        assertTrue(fit.contains("return clamp(fitted * clamp(y / fittedY, 0.88, 1.03), 0.0, white)"))
        assertTrue(rawTransform.contains("return ndrDisplayColorVolumeFit(guarded, nivukxVision);"))
    }

    @Test
    fun realtimeNdrDeliveryReRunsFinalGuardAfterSharpenAndSubjectDetail() {
        val finish = shader.substring(
            shader.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
            shader.indexOf("void main()")
        )

        val firstGuard = finish.indexOf("linear = ndrMeasuredFinalColorGuard(linear, nivukxVision);")
        val sharpen = finish.indexOf("linear = edgeAwareSharpen(uv, linear, false, true, 1.0, 1.0, finishSharpen, false)")
        val subjectDetail = finish.indexOf("linear = applySubjectGatedDetail(uv, linear, false, true, 1.0, 1.0, false)")
        val postDetailGuard = finish.indexOf(
            "linear = ndrMeasuredFinalColorGuard(linear, nivukxVision);",
            subjectDetail
        )
        val postDetailFit = finish.indexOf("linear = ndrDisplayColorVolumeFit(linear, nivukxVision);", postDetailGuard)

        assertTrue(firstGuard >= 0)
        assertTrue(sharpen > firstGuard)
        assertTrue(subjectDetail > sharpen)
        assertTrue(postDetailGuard > subjectDetail)
        assertTrue(postDetailFit > postDetailGuard)
        assertTrue(finish.contains("return clamp(linear, 0.0, nivukxVision ? 0.945 : 0.965);"))
    }

    @Test
    fun ndrMonitorReRunsMeasuredGuardAndVolumeFitAfterSharpenAndSubjectDetail() {
        val main = shader.substring(shader.indexOf("void main()"))
        val monitorBranch = main.substring(
            main.indexOf("if (hlgMonitor || pqMonitor) {"),
            main.indexOf("if (logMonitor)")
        )

        val ndrToneMap = monitorBranch.indexOf("linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionMonitor, monitorHighlight, shadowLift + 0.015);")
        val postKneeGuard = monitorBranch.indexOf("linear = ndrMeasuredFinalColorGuard(linear, nivukxVisionMonitor);", ndrToneMap)
        val postKneeFit = monitorBranch.indexOf("linear = ndrDisplayColorVolumeFit(linear, nivukxVisionMonitor);", postKneeGuard)
        val sharpen = monitorBranch.indexOf("linear = edgeAwareSharpen(transformed, linear, hlgMonitor, pqMonitor, contrast, saturation, monitorSharpening, false);")
        val subjectDetail = monitorBranch.indexOf("linear = applySubjectGatedDetail(transformed, linear, hlgMonitor, pqMonitor, contrast, saturation, false);")
        val postDetailGuard = monitorBranch.indexOf("linear = ndrMeasuredFinalColorGuard(linear, nivukxVisionMonitor);", subjectDetail)
        val postDetailFit = monitorBranch.indexOf("linear = ndrDisplayColorVolumeFit(linear, nivukxVisionMonitor);", postDetailGuard)

        assertTrue(ndrToneMap >= 0)
        assertTrue(postKneeGuard > ndrToneMap)
        assertTrue(postKneeFit > postKneeGuard)
        assertTrue(sharpen > postKneeFit)
        assertTrue(subjectDetail > sharpen)
        assertTrue(postDetailGuard > subjectDetail)
        assertTrue(postDetailFit > postDetailGuard)
    }

    @Test
    fun ndrMonitorAndDeliveryBypassPreHdrCreativeGrade() {
        val main = shader.substring(shader.indexOf("void main()"))
        val monitorBranch = main.substring(
            main.indexOf("if (!truthful) {"),
            main.indexOf("// ═══════════════════════════════════════════════════════════════════════\n    // TRUTHFUL DELIVERY PATH")
        )
        val deliveryBranch = main.substring(
            main.indexOf("// TRUTHFUL DELIVERY PATH"),
            main.indexOf("vec3 linear = hlgDelivery")
        )

        assertTrue(monitorBranch.contains("if (!neoNdrMonitor) {"))
        assertTrue(monitorBranch.contains("color = applyContrastAndSaturation(color, contrast, saturation);"))
        assertTrue(monitorBranch.contains("color *= uGrade1.rgb;"))
        assertTrue(monitorBranch.contains("color += uGrade2.rgb * 0.15;"))
        assertTrue(monitorBranch.contains("} else {\n            color = clamp(color, 0.0, 1.0);\n        }"))

        assertTrue(deliveryBranch.contains("if (!neoNdrDelivery) {"))
        assertTrue(deliveryBranch.contains("color = applyContrastAndSaturation(color, contrast, saturation);"))
        assertTrue(deliveryBranch.contains("color *= uGrade1.rgb;"))
        assertTrue(deliveryBranch.contains("color += uGrade2.rgb * 0.15;"))
        assertTrue(deliveryBranch.contains("} else {\n        color = clamp(color, 0.0, 1.0);\n    }"))
    }

    private fun shaderFunction(name: String): String {
        val start = shader.indexOf("vec3 $name")
        assertTrue("$name not found", start >= 0)
        val end = shader.indexOf("\n}\n\nvec3", start)
        assertTrue("$name end not found", end > start)
        return shader.substring(start, end)
    }
}
