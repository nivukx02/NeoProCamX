package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NdrToneOperator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrLiveProfileParityContractTest {
    @Test
    fun `live packets are generated from host golden profiles`() {
        val pairs = listOf(
            NdrNivukxMeasuredMode.NDR10_PLUS to NdrToneOperator.Mode.NDR10_PLUS,
            NdrNivukxMeasuredMode.NIVUKX_VISION to NdrToneOperator.Mode.NIVUKX_VISION
        )
        pairs.forEach { (liveMode, goldenMode) ->
            val live = NdrLiveProfilePolicy.resolve(liveMode)
            val golden = NdrToneOperator.paramsFor(goldenMode)
            assertEquals(NdrToneOperator.SENSOR_DIFFUSE_SCENE.toFloat(), live.sensorDiffuseScene, 0f)
            assertEquals(golden.signalGamma.toFloat(), live.signalGamma, 0f)
            assertEquals(golden.toeWidth.toFloat(), live.toeWidth, 0f)
            assertEquals(golden.toeAmount.toFloat(), live.toeAmount, 0f)
            assertEquals(golden.highlightDesat.toFloat(), live.highlightDesat, 0f)
            assertEquals(golden.shadowMidtoneLift.toFloat(), live.shadowMidtoneLift, 0f)
            assertEquals(golden.midtoneContrast.toFloat(), live.midtoneContrast, 0f)
            assertEquals(golden.chromaBoost.toFloat(), live.chromaBoost, 0f)
            assertEquals(golden.skyChromaBonus.toFloat(), live.skyChromaBonus, 0f)
            assertEquals(golden.localDetailStrength.toFloat(), live.localDetailStrength, 0f)
            assertEquals(golden.localDetailLowerStops.toFloat(), live.localDetailLowerStops, 0f)
            assertEquals(golden.localDetailUpperStops.toFloat(), live.localDetailUpperStops, 0f)
        }
    }

    @Test
    fun `preview and recording consume the same canonical packet`() {
        val vulkan = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val monitor = File("src/main/cpp/shaders/vulkan/raw_monitor_tonemap.comp").readText()
        val encoder = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()

        assertTrue(vulkan.contains("push.sensorDiffuseScene = correction.sensorDiffuseScene"))
        assertTrue(monitor.contains("vec4 toneProfile"))
        assertTrue(monitor.contains("vec4 character"))
        assertTrue(monitor.contains("vec4 detailProfile"))
        assertTrue(encoder.contains("uniform vec4 uNdrToneProfile"))
        assertTrue(encoder.contains("uniform vec4 uNdrCharacterProfile"))
        assertTrue(encoder.contains("uniform vec4 uNdrDetailProfile"))
        assertTrue(encoder.contains("glUniform4fv(activeMainLoc.ndrToneProfile"))
        assertTrue(encoder.contains("glUniform4fv(activeMainLoc.ndrCharacterProfile"))
        assertTrue(encoder.contains("glUniform4fv(activeMainLoc.ndrDetailProfile"))
    }

    @Test
    fun `all detail profiles preserve ndr tone and color while scaling bounded local detail`() {
        listOf(NdrNivukxMeasuredMode.NDR10_PLUS, NdrNivukxMeasuredMode.NIVUKX_VISION)
            .forEach { mode ->
                val focused = NdrLiveProfilePolicy.resolve(mode, detailProfileId = 1f)
                val natural = NdrLiveProfilePolicy.resolve(mode, detailProfileId = 2f)
                val extreme = NdrLiveProfilePolicy.resolve(mode, detailProfileId = 3f)

                assertTrue(natural.localDetailStrength < focused.localDetailStrength)
                assertTrue(focused.localDetailStrength < extreme.localDetailStrength)
                assertTrue(extreme.localDetailStrength <= 0.75f)
                assertTrue(extreme.localDetailLowerStops >= -0.20f)
                assertTrue(extreme.localDetailUpperStops <= 0.20f)

                listOf(natural, extreme).forEach { profile ->
                    assertEquals(focused.sensorDiffuseScene, profile.sensorDiffuseScene, 0f)
                    assertEquals(focused.signalGamma, profile.signalGamma, 0f)
                    assertEquals(focused.toeWidth, profile.toeWidth, 0f)
                    assertEquals(focused.toeAmount, profile.toeAmount, 0f)
                    assertEquals(focused.highlightDesat, profile.highlightDesat, 0f)
                    assertEquals(focused.chromaBoost, profile.chromaBoost, 0f)
                }
            }
    }
}
