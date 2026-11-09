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
    fun finalMeasuredGuardRemainsInLegacyRawTransformButNotCanonicalLiveFinish() {
        val finalGuard = shaderFunction("ndrMeasuredFinalColorGuard")
        val source = shader
        val canonicalFinish = shader.substring(
            shader.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
            shader.indexOf("void main()")
        )

        assertTrue(finalGuard.contains("ndrMeasuredSkyHueGuard"))
        assertTrue(finalGuard.contains("ndrMeasuredSkinHueGuard"))
        assertTrue(finalGuard.contains("ndrRedChannelOverflowGuard"))
        assertTrue(finalGuard.contains("ndrMeasuredUniformCalibrationGuard"))
        assertTrue(finalGuard.indexOf("ndrMeasuredSkyHueGuard") > finalGuard.indexOf("ndrRedChannelOverflowGuard"))
        assertTrue(source.contains("mapped = ndrMeasuredFinalColorGuard(mapped, nivukxVision);"))
        assertFalse(canonicalFinish.contains("ndrMeasuredFinalColorGuard"))
    }

    @Test
    fun ndrDisplayColorVolumeFitCompressesOvershootWithoutPlainClipping() {
        val fit = shaderFunction("ndrDisplayColorVolumeFit")
        val rawTransform = shaderFunction("rawLinearToDisplayNdrTransform")

        assertTrue(fit.contains("const float white = 1.0"))
        assertTrue(fit.contains("float scale = clamp((white - y) / max(peak - y, 1e-5), 0.0, 1.0)"))
        assertTrue(fit.contains("fitted = vec3(y) + (fitted - vec3(y)) * scale"))
        assertFalse(fit.contains("chromaPressure"))
        assertFalse(fit.contains("fitStrength"))
        assertTrue(fit.contains("return clamp(fitted, 0.0, white)"))
        assertTrue(rawTransform.contains("return ndrDisplayColorVolumeFit(source, nivukxVision);"))
    }

    @Test
    fun realtimeNdrDeliveryUsesOneToneColorAndGamutPassAfterComputeDetail() {
        val finish = shader.substring(
            shader.indexOf("vec3 finishRealtimeComputedNdrDelivery"),
            shader.indexOf("void main()")
        )

        val tone = finish.indexOf("ndrProfileSignalShape")
        val lift = finish.indexOf("ndrBlackPreservingMidtoneLift")
        val contrast = finish.indexOf("ndrProfileMidtoneContrast")
        val detail = finish.indexOf("ndrProfileLocalDetailStops")
        val character = finish.indexOf("ndrProfileColorCharacter")

        assertTrue(tone >= 0)
        assertTrue(lift > tone)
        assertTrue(contrast > lift)
        assertTrue(detail > contrast)
        assertTrue(character > detail)
        assertFalse(finish.contains("strictRealtimeClean"))
        assertFalse(finish.contains("edgeAwareSharpen("))
        assertFalse(finish.contains("applySubjectGatedDetail("))
        assertFalse(finish.contains("ndrMeasuredFinalColorGuard"))
        assertFalse(finish.contains("ndrDisplayColorVolumeFit"))
        assertFalse(finish.contains("return clamp("))
        assertTrue(finish.contains("return max(mapped, vec3(0.0));"))
    }

    @Test
    fun ndrMonitorUsesTheSameSinglePassProfileFinishAndReturnsBeforeGenericHdrStack() {
        val main = shader.substring(shader.indexOf("void main()"))
        val monitorBranch = main.substring(
            main.indexOf("if (!truthful) {"),
            main.indexOf("// Decode camera transfer function before linear processing.")
        )

        val directFinish = monitorBranch.indexOf("ndrMonitorLinear = finishRealtimeComputedNdrDelivery(")
        val returnIndex = monitorBranch.indexOf("return;", directFinish)
        val creativeGrade = monitorBranch.indexOf("color = applyContrastAndSaturation")

        assertTrue(directFinish >= 0)
        assertTrue(returnIndex > directFinish)
        assertTrue(creativeGrade > returnIndex)
        assertTrue(monitorBranch.contains("ndrSignalToSdrMonitor("))
        assertFalse(monitorBranch.substring(0, returnIndex).contains("naturalDynamicRangeToneMap("))
        assertFalse(monitorBranch.substring(0, returnIndex).contains("edgeAwareSharpen("))
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

        assertTrue(monitorBranch.contains("if (neoNdrMonitor) {"))
        assertTrue(monitorBranch.contains("ndrMonitorLinear = finishRealtimeComputedNdrDelivery("))
        assertTrue(monitorBranch.contains("color = applyContrastAndSaturation(color, contrast, saturation);"))
        assertTrue(monitorBranch.contains("color *= uGrade1.rgb;"))
        assertTrue(monitorBranch.contains("color += uGrade2.rgb * 0.15;"))
        assertTrue(monitorBranch.indexOf("return;") < monitorBranch.indexOf("color = applyContrastAndSaturation"))

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
