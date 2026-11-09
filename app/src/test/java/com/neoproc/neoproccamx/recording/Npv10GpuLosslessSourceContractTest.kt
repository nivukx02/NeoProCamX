package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring guard only; actual shader arithmetic/driver behavior require the Android GPU tests. */
class Npv10GpuLosslessSourceContractTest {
    private fun source(name: String): String {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return File(root, name).readText()
    }

    @Test fun integerShaderCannotIntroduceTextureFilteringOrColourTransforms() {
        val shader = source("cpp/npv10_gpu_range_shader.h").substringAfter("R\"glsl(")
        assertFalse(shader.contains("sampler"))
        assertFalse(shader.contains("texture("))
        assertFalse(shader.contains("float"))
        assertTrue(shader.contains("0x003f003fu"))
        assertTrue(shader.contains("frameToken ^ block"))
        assertTrue(shader.contains("if (firstBlock >= blockCount) return"))
        assertTrue(shader.contains("if (block < blockCount)"))
        assertTrue(shader.contains("index < inputWords"))
        assertTrue(shader.contains("index < outputWords"))
        assertFalse("Residual stream must not reintroduce per-word integer division", shader.contains(" / bits"))
    }

    @Test fun originalCpuChecksumAndGpuCompletionRemainMandatory() {
        val encoder = source("cpp/npv10_gpu_range_encoder.cpp")
        assertTrue(encoder.contains("packOriginal(input, packet, capacity, &sourceCrc)"))
        assertTrue(encoder.contains("!completed || !packed"))
        assertTrue(encoder.contains("glClientWaitSync("))
        assertTrue(encoder.contains("capacity, sourceCrc, acceleration, blocks"))
        assertTrue(encoder.contains("target->totalNs = elapsed(start)"))
        assertTrue(encoder.indexOf("const auto start = Clock::now()") < encoder.indexOf("CompleteTiming"))
        assertTrue(encoder.contains("if (!current.restore()) return {}"))
        assertTrue(encoder.contains("if (poisoned_ != nullptr) *poisoned_ = true"))
    }

    @Test fun compactionReusesTheFencedUploadWithoutChangingWireCodes() {
        val shader = source("cpp/npv10_gpu_range_compact_shader.h").substringAfter("R\"glsl(")
        assertFalse(shader.contains("float"))
        assertFalse(shader.contains("sampler"))
        assertTrue(shader.contains("frameToken ^ absolute"))
        assertTrue(shader.contains("frameToken ^ chunk"))
        assertTrue(shader.contains("rangeBytes < rawBytes"))
        assertTrue(shader.contains("if (!fits) return"))
        val encoder = source("cpp/npv10_gpu_range_encoder.cpp")
        assertTrue(encoder.contains("buffers_[2]"))
        assertTrue(encoder.contains("compactBytesFor(samples)"))
        assertTrue(encoder.contains("kP010RangeCompactShader"))
    }

    @Test fun supportedAbisBuildTheImplementationWithoutChangingLiveRoute() {
        assertTrue(source("cpp/CMakeLists.txt").contains("npv10_gpu_range_encoder.cpp"))
        val wrapper = source("java/com/neoproc/neoproccamx/recording/Npv10GpuLosslessEncoder.kt")
        assertTrue(wrapper.contains("!output.isReadOnly"))
        val manager = source("java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt")
        assertFalse("Unmeasured GPU transport must not be selected automatically", manager.contains("Npv10GpuLosslessEncoder.encode("))
    }
}
