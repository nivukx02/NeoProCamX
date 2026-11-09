package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanNativeColorUniformContractTest {
    private val nativeSource =
        File("src/main/cpp/vulkan_bridge.cpp").readText()
    private val shaderSource =
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

    @Test
    fun `native render bridge retains independent preview and encoder color packets`() {
        assertTrue(nativeSource.contains("kColorScienceUniformFloatCount = 52"))
        assertTrue(nativeSource.contains("previewColorScience"))
        assertTrue(nativeSource.contains("encoderColorScience"))
        assertTrue(nativeSource.contains("uniforms.acesCgToOklabReference"))
        assertTrue(nativeSource.contains("uniforms.oklabReferenceToAcesCg"))
        assertTrue(nativeSource.contains("uniforms.neoHdrParams"))
        assertTrue(nativeSource.contains("uniforms.neoModeParams"))
        assertTrue(nativeSource.contains("uniforms.neoMeasuredCorrectionParams"))
        assertTrue(nativeSource.contains("uniforms.neoIdentityParams"))
        assertTrue(nativeSource.contains("uniforms.neoMeasuredRgbScaleParams"))
        assertTrue(nativeSource.contains("colorScience.data() + 40"))
        assertTrue(nativeSource.contains("colorScience.data() + 44"))
        assertTrue(nativeSource.contains("colorScience.data() + 48"))
    }

    @Test
    fun `native render config validates every JNI packet before state mutation`() {
        assertTrue(nativeSource.contains("env->GetArrayLength(previewTransform) != 16"))
        assertTrue(nativeSource.contains("env->GetArrayLength(encoderTransform) != 16"))
        assertTrue(nativeSource.contains("env->GetArrayLength(colorGain) != 4"))
        assertTrue(nativeSource.contains("env->GetArrayLength(previewGrade) != 16"))
        assertTrue(nativeSource.contains("env->GetArrayLength(encoderGrade) != 16"))
        assertTrue(nativeSource.contains("render config contains non-finite value"))
    }

    @Test
    fun `preview uniform ABI and extreme detail profile are locked`() {
        assertTrue(nativeSource.contains("static_assert(sizeof(PreviewUniforms) == 400"))
        assertTrue(nativeSource.contains("offsetof(PreviewUniforms, neoIdentityParams) == 352"))
        assertTrue(nativeSource.contains("offsetof(PreviewUniforms, neoMeasuredRgbScaleParams) == 368"))
        assertTrue(nativeSource.contains("offsetof(PreviewUniforms, neoGamutCuspParams) == 384"))
        assertTrue(
            nativeSource.contains(
                "uniforms.subjectDetail[0] = std::clamp(state->enhancementUniforms.values[40], 0.0f, 3.0f)"
            )
        )
        assertTrue(shaderSource.contains("uSubjectDetail.x > 2.5 && uSubjectDetail.x < 3.5"))
    }

    @Test
    fun `preview LUT JNI path validates dimensions length and finite values`() {
        assertTrue(nativeSource.contains("size > kMaximumPreviewLutSize"))
        assertTrue(nativeSource.contains("length != static_cast<jsize>(expectedLength)"))
        assertTrue(nativeSource.contains("preview LUT rejected: invalid float count"))
        assertTrue(nativeSource.contains("preview LUT rejected: non-finite value"))
    }

    @Test
    fun `color uniforms append after existing subject detail ABI slots`() {
        assertTrue(shaderSource.contains("vec4 uSubjectDetail"))
        assertTrue(shaderSource.contains("mat4 uAcesCgToOklabReference"))
        assertTrue(shaderSource.contains("mat4 uOklabReferenceToAcesCg"))
        assertTrue(shaderSource.contains("vec4 uNeoHdrParams"))
        assertTrue(shaderSource.contains("vec4 uNeoModeParams"))
        assertTrue(shaderSource.contains("float neoAcesAp0 = uNeoModeParams.w >= 4.0 ? 1.0 : 0.0"))
        assertTrue(shaderSource.contains("vec4 uNeoMeasuredCorrectionParams"))
        assertTrue(shaderSource.contains("vec4 uNeoIdentityParams"))
        assertTrue(shaderSource.contains("vec4 uNeoMeasuredRgbScaleParams"))
        assertTrue(shaderSource.contains("float neoColorSpaceId = uNeoIdentityParams.x"))
        assertTrue(shaderSource.contains("float neoColorScienceId = uNeoIdentityParams.y"))
        assertTrue(shaderSource.contains("float neoActiveTransportId = uNeoIdentityParams.z"))
        assertTrue(shaderSource.indexOf("vec4 uSubjectDetail") < shaderSource.indexOf("mat4 uAcesCgToOklabReference"))
        assertTrue(shaderSource.indexOf("vec4 uNeoModeParams") < shaderSource.indexOf("vec4 uNeoMeasuredCorrectionParams"))
        assertTrue(shaderSource.indexOf("vec4 uNeoMeasuredCorrectionParams") < shaderSource.indexOf("vec4 uNeoIdentityParams"))
        assertTrue(shaderSource.indexOf("vec4 uNeoIdentityParams") < shaderSource.indexOf("vec4 uNeoMeasuredRgbScaleParams"))
    }
}
