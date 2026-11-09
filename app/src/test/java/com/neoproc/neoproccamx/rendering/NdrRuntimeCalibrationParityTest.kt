package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NdrToneOperator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the generated Python calibration asset to every Kotlin/native live consumer. */
class NdrRuntimeCalibrationParityTest {
    private val json = File(
        "src/main/assets/calibration/ndr_nivukx_runtime_calibration.json"
    ).readText()

    private fun objectBody(name: String): String {
        val nameIndex = json.indexOf("\"$name\"")
        require(nameIndex >= 0) { "JSON object $name missing" }
        val open = json.indexOf('{', nameIndex)
        require(open >= 0) { "JSON object $name has no body" }
        var depth = 0
        for (index in open until json.length) {
            when (json[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return json.substring(open + 1, index)
                }
            }
        }
        error("JSON object $name is not closed")
    }

    private fun number(body: String, field: String): Double =
        Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?: error("JSON number $field missing")

    private fun assertProfile(mode: String, params: NdrToneOperator.Params) {
        val body = objectBody(mode)
        assertEquals(params.signalGamma, number(body, "signal_gamma"), 5e-7)
        assertEquals(params.toeWidth, number(body, "toe_width"), 5e-7)
        assertEquals(params.toeAmount, number(body, "toe_amount"), 5e-7)
        assertEquals(params.peakNits, number(body, "peak_nits"), 5e-7)
        assertEquals(params.highlightDesat, number(body, "highlight_desat"), 5e-7)
        assertEquals(params.shadowMidtoneLift, number(body, "shadow_midtone_lift"), 5e-7)
        assertEquals(params.midtoneContrast, number(body, "midtone_contrast"), 5e-7)
        assertEquals(params.chromaBoost, number(body, "chroma_boost"), 5e-7)
        assertEquals(params.skyChromaBonus, number(body, "sky_chroma_bonus"), 5e-7)
        assertEquals(params.localDetailStrength, number(body, "local_detail_strength"), 5e-7)
        assertEquals(params.localDetailLowerStops, number(body, "local_detail_lower_stops"), 5e-7)
        assertEquals(params.localDetailUpperStops, number(body, "local_detail_upper_stops"), 5e-7)
    }

    @Test
    fun `generated calibration exactly matches canonical runtime profiles`() {
        assertTrue(json.contains("\"calibration_version\": \"${NdrToneOperator.CALIBRATION_VERSION}\""))
        assertEquals(
            NdrToneOperator.SENSOR_DIFFUSE_SCENE,
            number(json, "sensor_diffuse_scene"),
            5e-7
        )
        assertProfile("ndr10_plus", NdrToneOperator.ndr10Plus())
        assertProfile("nivukx_vision", NdrToneOperator.nivukxVision())
    }

    @Test
    fun `generated rolloff calibration reaches measured correction policy`() {
        val ndr = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")
        val nivukx = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")
        assertEquals(
            number(objectBody("ndr10_plus"), "highlight_rolloff_bias").toFloat(),
            ndr.highlightRolloffBias,
            5e-7f
        )
        assertEquals(
            number(objectBody("nivukx_vision"), "highlight_rolloff_bias").toFloat(),
            nivukx.highlightRolloffBias,
            5e-7f
        )
    }

    @Test
    fun `native consumers admit calibrated sensor reference without legacy clamp`() {
        val monitor = File("src/main/cpp/shaders/vulkan/raw_monitor_tonemap.comp").readText()
        val vulkan = File("src/main/cpp/vulkan_bridge.cpp").readText()
        val encoder = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()

        assertTrue(monitor.contains("clamp(params.toneProfile.x, 0.02, 0.30)"))
        assertTrue(vulkan.contains("correction.sensorDiffuseScene, 0.02f, 0.30f"))
        assertTrue(encoder.contains("clamp(uNdrToneProfile.x, 0.02, 0.30)"))
        assertTrue(vulkan.contains("sensorDiffuseScene = 0.180f"))
        assertTrue(encoder.contains("ndrToneProfile[4] = {0.180f"))
        assertFalse(monitor.contains("clamp(params.toneProfile.x, 0.08, 0.30)"))
        assertFalse(vulkan.contains("correction.sensorDiffuseScene, 0.08f, 0.30f"))
        assertFalse(encoder.contains("clamp(uNdrToneProfile.x, 0.08, 0.30)"))
    }

    @Test
    fun `calibration preserves resolution fps bitrate depth and features`() {
        val guards = objectBody("quality_guards")
        listOf(
            "resolution_changed",
            "fps_changed",
            "bitrate_changed",
            "bit_depth_changed",
            "features_removed"
        ).forEach { field -> assertTrue(guards.contains("\"$field\": false")) }
        assertTrue(guards.contains("\"tone_curve_monotonic\": true"))
        assertTrue(guards.contains("\"profiles_are_distinct\": true"))
        assertTrue(guards.contains("\"sensor_reference_is_linear_not_screen_fitted\": true"))
        assertTrue(guards.contains("\"dark_room_noise_floor_preserved\": true"))
    }
}
