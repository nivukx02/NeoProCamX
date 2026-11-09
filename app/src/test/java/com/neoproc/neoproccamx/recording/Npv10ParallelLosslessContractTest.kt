package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ownership/wiring guards; actual integer parity and timing are measured on the phone. */
class Npv10ParallelLosslessContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(root, path).readText()

    @Test fun originalSourcesRemainOwnedUntilEveryExistingHelperHasJoined() {
        val codec = source("cpp/npv10_lossless_chunk_codec.cpp")
            .substringAfter("EncodeResult compressP010PlanesParallelRangeLz4(")
            .substringBefore("EncodeResult compressP010PlanesRealtimeRangeLz4(")
        assertTrue(codec.indexOf("dispatch(&job, processParallelP010Chunks)") < codec.indexOf("std::memmove("))
        assertTrue(codec.contains("!job.successful.load("))
        assertTrue(codec.contains("outputOffset > sourceOffset"))
        assertTrue(codec.contains("!disjoint(y, requiredY, packet, capacity)"))
        assertTrue(codec.contains("::crc32_combine(packedCrc, part.packedCrc32"))
        assertTrue(codec.contains("::crc32_combine(bodyCrc, part.storedCrc32"))
        assertFalse(codec.contains("std::thread("))
    }

    @Test fun parallelPackingUsesUnchangedCaptureHelperAdmissionAndOriginalIntegerCodec() {
        val native = source("cpp/npv10_raw_native.cpp")
        assertTrue(native.contains("return executeWork(job, processor, false, kOrdinaryPackTargetDurationNanos)"))
        val codec = source("cpp/npv10_lossless_chunk_codec.cpp")
            .substringAfter("void processParallelP010Chunks(")
            .substringBefore("bool prepareP010LosslessChunkWorkspace()")
        assertTrue(codec.contains("packAndEncodeP010RangeBlock("))
        assertTrue(codec.contains("nextChunk.fetch_add(1u"))
        assertTrue(codec.contains("LZ4_compress_fast_extState("))
        assertTrue(codec.contains("std::memcpy(body, workspace.raw.data(), rawBytes)"))
        assertTrue(codec.contains("job.successful.store(false"))
    }

    @Test fun liveTwelveMegapixelPathPrewarmsAndJoinsWithoutRaisingTimingLimits() {
        val manager = source("java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt")
        assertTrue(manager.contains("config.width == 4096 && config.height == 3072 && !config.niv99ProcessingRequired"))
        assertTrue(manager.contains("check(Npv10FramePacker.prepareP010LosslessChunkExecutor())"))
        val native = source("cpp/npv10_raw_native.cpp")
            .substringAfter("Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010ImagePlanesToDirectMappedTenBitWithCrc(")
            .substringBefore("Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(")
        assertTrue(native.contains("compressP010PlanesParallelRangeLz4("))
        assertTrue(native.indexOf("compressP010PlanesParallelRangeLz4(") <
            native.indexOf("env->CallVoidMethod(onPlanesCopied, releaseSource)"))
        assertTrue(native.contains("std::chrono::steady_clock::now() - preparationStarted"))
    }
}
