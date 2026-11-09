package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanNativeAtomicityContractTest {
    private val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()

    @Test
    fun `preview LUT replacement publishes a complete candidate and retires descriptors atomically`() {
        val body = nativeSource
            .substringAfter("bool createOrUpdatePreviewLut(")
            .substringBefore("void destroyNrliLut(")

        val candidate = body.indexOf("PreviewLutTexture candidate{}")
        val idle = body.indexOf("waitDeviceIdleSynchronized(state)")
        val publish = body.indexOf("state->previewLut = candidate")
        val rebind = body.indexOf("rebindPreviewLutDescriptors(state, &state->previewTarget")
        val retire = body.indexOf("destroyPreviewLutTexture(state, &retired)")

        assertTrue(candidate >= 0)
        assertTrue(idle > candidate)
        assertTrue(publish > idle)
        assertTrue(rebind > publish)
        assertTrue(retire > rebind)
        assertTrue(body.contains("destroyPreviewLutTexture(state, &candidate)"))
        assertFalse(body.contains("destroyPreviewLut(state)"))
    }

    @Test
    fun `preview LUT disable is an acknowledged identity transaction`() {
        val uploadBody = nativeSource
            .substringAfter("bool createOrUpdatePreviewLut(")
            .substringBefore("void destroyNrliLut(")
        val body = nativeSource
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdatePreviewLut(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateSubjectDetailMask(")

        assertTrue(uploadBody.contains("kStrictHighEndBuild && lutData != nullptr && lutSize > 1"))
        assertTrue(body.contains("const bool disableRequest = lutData == nullptr && size == 0"))
        assertTrue(body.contains("malformed enable/disable request"))
        assertTrue(body.contains("createOrUpdatePreviewLut(state, data, disableRequest ? 0 : size)"))
        assertTrue(body.contains("return JNI_FALSE"))
        assertTrue(body.contains("return JNI_TRUE"))
    }

    @Test
    fun `native finite checks remain effective under fast math`() {
        val helper = nativeSource
            .substringAfter("bool isIeee754Finite(float value) noexcept")
            .substringBefore("bool supportsFilteredLutFormat(")

        assertTrue(helper.contains("std::memcpy(&bits, &value, sizeof(bits))"))
        assertTrue(helper.contains("constexpr uint32_t kExponentMask = 0x7f800000u"))
        assertTrue(helper.contains("(bits & kExponentMask) != kExponentMask"))
        assertFalse(Regex("std::isfinite\\s*\\(").containsMatchIn(nativeSource))
    }

    @Test
    fun `HDR and ten bit display setters reject failed rebuilds`() {
        val hdrSetter = nativeSource
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetHdrDynamicRangeMode(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetPreferTenBitSdrDisplay(")
        val tenBitSetter = nativeSource
            .substringAfter("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetPreferTenBitSdrDisplay(")
            .substringBefore("Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateRenderConfig(")

        assertTrue(hdrSetter.contains("const bool previewAccepted"))
        assertTrue(hdrSetter.contains("const bool encoderAccepted"))
        assertTrue(hdrSetter.contains("if (previewAccepted && encoderAccepted) return JNI_TRUE"))
        assertTrue(hdrSetter.contains("return JNI_FALSE"))
        assertTrue(tenBitSetter.contains("if (rebuildPreviewTargetForDisplayFormatChange(state)) return JNI_TRUE"))
        assertTrue(tenBitSetter.contains("return JNI_FALSE"))
    }
}
