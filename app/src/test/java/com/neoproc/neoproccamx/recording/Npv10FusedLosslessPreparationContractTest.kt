package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10FusedLosslessPreparationContractTest {
    private val cpp = listOf(File("src/main/cpp"), File("app/src/main/cpp")).first { it.isDirectory }

    @Test fun `fused encoding remains bounded lossless with original CRC and LZ4 escape`() {
        val source = File(cpp, "npv10_lossless_chunk_codec.cpp").readText()
            .substringAfter("EncodeResult compressP010PlanesRealtimeRangeLz4(")
            .substringBefore("EncodeResult framePackedTenBitRealtimeInPlace(")
        assertTrue(source.contains("workspace.prepare(packedBytesForSamples(kChunkSamples))"))
        assertTrue(source.contains("hasWorthwhileSplitLz4Savings"))
        assertTrue(source.contains("LZ4_compress_fast_extState"))
        assertTrue(source.contains("std::memcpy(packet + bodyOffset, workspace.raw.data(), sourceBytes)"))
        assertTrue(source.contains("packedCrc = ::crc32(packedCrc, workspace.raw.data()"))
        assertTrue(source.contains("result.storedCrc32 ="))
        assertTrue(source.contains("!disjoint(y, requiredY, packet, capacity)"))
        assertTrue(source.contains("yCapacity < requiredY"))
        assertFalse(source.contains("resize(samples"))
    }

    @Test fun `prepared completion rejects route and extent mismatch instead of reencoding`() {
        val source = File(cpp, "npv10_dmc_native.cpp").readText()
        val finish = source.substringAfter("bool finishProcessing(").substringBefore("bool commitFrame(")
        assertTrue(finish.contains("if (alreadyEncoded != nullptr || fusedPreparation)"))
        assertTrue(finish.contains("encoded = fusedPreparation && chunkedLosslessCompression_"))
        assertTrue(finish.contains("alreadyEncoded->packedCrc32 == packedCrc32"))
        assertTrue(finish.contains("alreadyEncoded->storedBytes <= payloadCapacityBytes_"))
        assertTrue(finish.contains("alreadyEncoded->rawChunks == chunks"))
        assertTrue(finish.contains("stateUpdated = retireSlot(slot, frameIndex)"))
        assertTrue(finish.contains("slot.state = SlotState::Prepared"))
        assertTrue(finish.contains("processorStateChanged_.notify_all()"))
    }
}
