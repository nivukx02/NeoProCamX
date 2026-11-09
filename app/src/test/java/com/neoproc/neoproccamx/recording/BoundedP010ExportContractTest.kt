package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BoundedP010ExportContractTest {
    @Test
    fun productionP010RouteUsesReusableDirectBuffersWithoutFrameChannels() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt"
        ).readText()
        val route = source.substringAfter(
            "useGpuSequential && header.payloadFormat == Npv10PayloadFormat.P010_LE"
        ).substringBefore("} else if (useGpuSequential)")
        val direct = source.substringAfter("private fun runBoundedDirectGpuExport(")
            .substringBefore("private fun runTripleBufferedExport(")
        val nativeHotPath = direct
            .substringAfter("val gpuEvidence = if (gpuLaneHealthy)")
            .substringBefore("if (gpuEvidence != null)")

        assertTrue(route.contains("runBoundedDirectGpuExport"))
        assertTrue(direct.contains("readNextFrameDirect"))
        assertTrue(direct.contains("Npv10NativeFrameBuffer.allocate"))
        assertTrue(direct.contains("currentIndex xor 1"))
        assertTrue(direct.contains("Npv10ExportGpuAccelerator.enhanceP010Direct("))
        assertTrue(direct.contains("writeSampleBuffer"))
        assertTrue(direct.contains("encodeFrameDirect"))
        assertTrue(direct.contains("nativeBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)"))
        assertFalse(direct.contains("Channel<"))
        assertFalse(direct.contains("reader.readNextFrame()"))
        assertFalse(nativeHotPath.contains("ByteArray("))
        assertTrue(direct.contains("val sourcePayload = current.copyFrameBytes(decodedBytes)"))
        assertTrue(direct.contains("gpuLaneHealthy = false"))
        assertFalse(direct.contains("ByteBuffer.allocateDirect"))
    }

    @Test
    fun largeExportBuffersUseExplicitlyReleasedNativeMappings() {
        val owner = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10NativeFrameBuffer.kt"
        ).readText()
        val native = File("src/main/cpp/npv10_raw_native.cpp").readText()

        assertTrue(owner.contains("@Keep"))
        assertTrue(owner.contains("nativeAllocate(capacity.toLong())"))
        assertTrue(owner.contains("nativeRelease(buffer)"))
        assertTrue(native.contains("Npv10NativeFrameBufferAllocator_nativeAllocate"))
        assertTrue(native.contains("MAP_PRIVATE | MAP_ANONYMOUS"))
        assertTrue(native.contains("NewDirectByteBuffer(address, capacity)"))
        assertTrue(native.contains("Npv10NativeFrameBufferAllocator_nativeRelease"))
        assertTrue(native.contains("munmap(address, static_cast<size_t>(capacity))"))
    }

    @Test
    fun everyHevcInputPathQueuesAllPicturesBeforeSeparateEos() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopHevcMovEncoder.kt"
        ).readText()
        val direct = source.substringAfter("fun encodeFrameDirect(")
            .substringBefore("private fun queueFrame(")
        val directSeal = source.substringAfter("fun sealDirectInput()")
            .substringBefore("private fun queueDirectFrame(")
        val bufferedQueue = source.substringAfter("private fun queueFrame(")
            .substringBefore("fun finish(")
        val inputAdmission = source.substringAfter("private fun awaitInputSlot(")
            .substringBefore("private fun strictInputTransportFailure(")
        val finish = source.substringAfter("fun finish(")
            .substringBefore("override fun close()")
        val emptyEos = source.substringAfter("private fun queueEmptyEndOfStream(")
            .substringBefore("private fun setTrackEndPresentationTime(")

        assertTrue(direct.contains("require(source.isDirect)"))
        assertTrue(direct.contains("queueDirectFrame(source, presentationTimeUs)"))
        assertTrue(direct.contains("directInputSealed = true"))
        assertTrue(directSeal.contains("check(directInputMode)"))
        assertTrue(directSeal.contains("directInputSealed = true"))
        assertFalse(direct.contains("MediaCodec.BUFFER_FLAG_END_OF_STREAM"))
        assertTrue(direct.contains("inputSlot.buffer.put(p010.duplicate())"))
        assertTrue(inputAdmission.contains("input.clear()"))
        assertTrue(inputAdmission.contains("input.capacity() < payloadBytes"))
        assertFalse(direct.contains("ByteArray("))
        assertFalse(bufferedQueue.contains("MediaCodec.BUFFER_FLAG_END_OF_STREAM"))
        assertTrue(finish.contains("queueFrame(finalFrame)"))
        assertTrue(finish.contains("queueEmptyEndOfStream(endPresentationTimeUs)"))
        assertTrue(emptyEos.contains("MediaCodec.BUFFER_FLAG_END_OF_STREAM"))
        assertTrue(emptyEos.contains("drain(endOfStream = true)"))
    }
}
