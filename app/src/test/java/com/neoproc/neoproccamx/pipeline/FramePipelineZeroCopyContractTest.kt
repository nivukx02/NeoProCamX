package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramePipelineZeroCopyContractTest {

    @Test
    fun `hardware import path submits hardware buffer frames without plane reads`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt")
            .readText()
        val body = source.substringAfter("fun enqueueHardware(reader: ImageReader)")
            .substringBefore("private fun submitImageToVulkan")

        assertTrue(body.contains("submitImageToVulkan(image"))
        assertFalse(body.contains("image.planes"))
        assertFalse(body.contains("snapshotLumaPlane"))
        assertFalse(body.contains("snapshotRawYuvFrame"))
        assertFalse(body.contains("ByteArray"))
    }

    @Test
    fun `hardware import callback schedules vulkan drain off camera thread`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt")
            .readText()
        val enqueueBody = source.substringAfter("fun enqueueHardware(reader: ImageReader)")
            .substringBefore("private fun scheduleHardwareDispatchDrain")

        assertTrue(source.contains("hardwareDispatchExecutor"))
        assertTrue(enqueueBody.contains("scheduleHardwareDispatchDrain()"))
        assertTrue(enqueueBody.contains("FramePipelineImageDrainPolicy.drainAvailable"))
        assertTrue(enqueueBody.contains("reader.acquireNextImage()"))
        assertTrue(enqueueBody.contains("pendingHardwareReader.set(reader)"))
        assertTrue(source.contains("private fun drainPendingHardwareReaderIfCapacity()"))
        assertTrue(source.contains("private fun pendingHardwareReaderHasAcquireCapacity()"))
        assertTrue(source.contains("drainHardwareReader(reader, deferred = true)"))
        assertFalse(enqueueBody.contains("reader.acquireLatestImage()"))
        assertFalse(enqueueBody.contains("drainHardwareDispatchQueue()"))
    }

    @Test
    fun `large memory thermal stall vault absorbs measured producer stall without exceeding reader budget`() {
        assertEquals(54, RecordPipelineDepthFlag.DEEP_HARDWARE_IMPORT_CAPACITY)
        assertEquals(55, com.neoproc.neoproccamx.camera.CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010)
        assertTrue(
            RecordPipelineDepthFlag.DEEP_HARDWARE_IMPORT_CAPACITY <
                com.neoproc.neoproccamx.camera.CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010
        )
        assertEquals(
            1_000_000_000L / 25L,
            RecordPipelineDepthFlag.HARDWARE_IMPORT_RECORD_GUARD_NS
        )
        assertEquals(2, RecordPipelineDepthFlag.HARDWARE_IMPORT_READER_RESERVED_IMAGES)
        assertEquals(2, RecordPipelineDepthFlag.HARDWARE_IMPORT_RECORD_DRAIN_PER_CALLBACK)
        assertEquals(53, RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(55))
        assertTrue(
            RecordPipelineDepthFlag.hardwareImportReaderHighWatermark(55) *
                RecordPipelineDepthFlag.HARDWARE_IMPORT_RECORD_GUARD_NS >= 2_120_000_000L
        )
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt")
            .readText()
        assertTrue(
            source.contains(
                "MAX_HARDWARE_IMPORT_DRAIN_IMAGES =\n" +
                    "            CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010"
            )
        )
    }

    @Test
    fun `native strict recording import cache and sync ring use eight slots`() {
        val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()

        assertTrue(nativeSource.contains("kStrictRecordingFramesInFlight = 8u"))
        assertTrue(nativeSource.contains("kImportedFrameCacheLimit = kStrictHighEndBuild ? 8u : 4u"))
    }

    @Test
    fun `camera hardware import reader uses requested gpu sampled source format`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
            .readText()
        val readerBody = source.substringAfter("hardwareImportReader = ImageReader.newInstance")
            .substringBefore(".apply { setOnImageAvailableListener")

        assertTrue(readerBody.contains("hardwareImportFormat"))
        assertTrue(source.contains("private fun hardwareImportImageFormat(): Int"))
        assertTrue(source.contains("ImageFormat.YCBCR_P010"))
        assertTrue(source.contains("ImageFormat.PRIVATE"))
        assertTrue(readerBody.contains("hardwareImportUsageFlags()"))
        assertTrue(readerBody.contains("hardwareDepth"))
        assertTrue(source.contains("HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE"))
        assertTrue(source.contains("HardwareBuffer.USAGE_VIDEO_ENCODE"))
        assertFalse(source.contains("HardwareBuffer.USAGE_GPU_COLOR_OUTPUT"))
    }

    @Test
    fun `zero copy verifier recommends sampled camera buffers without write usage`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/HardwareBufferZeroCopyVerifier.kt")
            .readText()
        val requiredUsageBlock = source.substringAfter("private val REQUIRED_USAGE_FLAGS =")
            .substringBefore("data class VerificationResult")

        assertTrue(requiredUsageBlock.contains("HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE"))
        assertTrue(requiredUsageBlock.contains("HardwareBuffer.USAGE_VIDEO_ENCODE"))
        assertFalse(requiredUsageBlock.contains("HardwareBuffer.USAGE_GPU_COLOR_OUTPUT"))
    }

    @Test
    fun `processed recording parity enables realtime vulkan encoder feed when runtime is stable`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt")
            .readText()
        val callBody = source.substringAfter("RecordingPreviewContinuityPolicy.resolveFeedPath(")
            .substringBefore(")")

        assertTrue(callBody.contains("preferInternalHardwareImport"))
        assertTrue(source.contains("realtimeVulkanFeedAllowed"))
        assertTrue(source.contains("REALTIME_VULKAN_ENCODER_FEED_ENABLED = true"))
        assertTrue(source.contains("val internalHardwareImportStable = vulkanRuntimeSupported && !rawRequested"))
        assertTrue(callBody.contains("internalHardwareImportStable = internalHardwareImportStable"))
    }

    @Test
    fun `scene analysis latest frame carries y u and v planes for chroma noise decisions`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt")
            .readText()
        val sceneRefreshBody = source.substringAfter("if (shouldRefreshSceneStats) {")
            .substringBefore("// Adaptive peaking")
        val analysisHelperBody = source.substringAfter("private fun snapshotAnalysisRawFrame(")
            .substringBefore("private fun copyAnalysisChromaSample(")

        assertTrue(sceneRefreshBody.contains("analysisRawFrame"))
        assertTrue(sceneRefreshBody.contains("snapshotAnalysisRawFrame(image, snapshot, timestampNs)"))
        assertTrue(analysisHelperBody.contains("snapshotAnalysisChromaSource("))
        assertTrue(source.contains("Yuv420ImageTools.snapshotScaledChromaOnly("))
        assertFalse(analysisHelperBody.contains("snapshotRawYuvFrame(image, timestampNs)"))
        assertTrue(analysisHelperBody.contains("uBytes = u"))
        assertTrue(analysisHelperBody.contains("vBytes = v"))
        assertFalse(sceneRefreshBody.contains("uBytes = EMPTY_BYTES"))
        assertFalse(sceneRefreshBody.contains("vBytes = EMPTY_BYTES"))
    }
}
