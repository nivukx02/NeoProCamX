package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10CaptureSourcePolicyTest {

    @Test
    fun resolvePrefersNativeP010AtSelectedResolutionWhenAvailable() {
        val selected = Npv10CaptureSize(3840, 2160)

        val plan = Npv10CaptureSourcePolicy.resolveForSizes(
            selectedResolution = selected,
            p010Sizes = listOf(Npv10CaptureSize(1920, 1080), selected),
            yuv420Sizes = listOf(Npv10CaptureSize(3840, 2160)),
            requireTenBit = true
        )

        assertSizeEquals(selected, plan.size)
        assertEquals(ImageFormat.YCBCR_P010, plan.imageFormat)
        assertEquals(10, plan.sourceBitDepth)
        assertEquals("YCBCR_P010", plan.sourceFormat)
        assertEquals(Npv10PayloadFormat.P010_LE, plan.payloadFormat)
        assertTrue(plan.exactSelectedResolution)
        assertTrue(plan.nativeTenBitCapture)
    }

    @Test
    fun resolveRejectsEightBitFallbackWhenNativeP010MasterWasRequested() {
        val selected = Npv10CaptureSize(4096, 2160)

        val failure = runCatching {
            Npv10CaptureSourcePolicy.resolveForSizes(
                selectedResolution = selected,
                p010Sizes = emptyList(),
                yuv420Sizes = listOf(Npv10CaptureSize(1920, 1080), selected),
                requireTenBit = true
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("Native 10-bit P010"))
    }

    @Test
    fun resolvePrefersNativeP010EvenWhenTenBitNotStrictlyRequiredWhenP010Available() {
        val selected = Npv10CaptureSize(3840, 2160)

        val plan = Npv10CaptureSourcePolicy.resolveForSizes(
            selectedResolution = selected,
            p010Sizes = listOf(Npv10CaptureSize(1920, 1080), selected),
            yuv420Sizes = listOf(selected),
            requireTenBit = false
        )

        // Even with requireTenBit=false, an available native 10-bit P010 source must win
        // over the 8-bit expanded master — never silently drop precision.
        assertSizeEquals(selected, plan.size)
        assertEquals(ImageFormat.YCBCR_P010, plan.imageFormat)
        assertEquals(10, plan.sourceBitDepth)
        assertEquals(Npv10PayloadFormat.P010_LE, plan.payloadFormat)
        assertTrue(plan.nativeTenBitCapture)
        assertTrue(plan.exactSelectedResolution)
    }

    @Test
    fun resolveRejectsEightBitFallbackEvenWhenTenBitNotStrictlyRequired() {
        val failure = runCatching {
            Npv10CaptureSourcePolicy.resolveForSizes(
                selectedResolution = Npv10CaptureSize(1920, 1080),
                p010Sizes = emptyList(),
                yuv420Sizes = listOf(Npv10CaptureSize(1920, 1080)),
                requireTenBit = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("refusing RAW or 8-bit fallback"))
    }

    @Test
    fun resolveRawModeIsRejectedBecauseNpv10IsProcessedP010Only() {
        val selected = Npv10CaptureSize(4080, 3060)
        val maxRaw = Npv10CaptureSize(8160, 6120)

        val failure = runCatching {
            Npv10CaptureSourcePolicy.resolveForSizes(
                selectedResolution = selected,
                p010Sizes = listOf(selected),
                yuv420Sizes = listOf(selected),
                rawSensorSizes = listOf(selected),
                rawPrivateSizes = listOf(maxRaw),
                maximumResolutionRawSensorSizes = listOf(maxRaw),
                requireTenBit = true,
                rawModeActive = true,
                supportsMaximumResolutionPixelMode = true
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("custom lossless packed 10-bit P010 data only"))
    }

    @Test
    fun nativeP010SelectionIsConnectedToTheCameraReaderAndFrameSnapshotPath() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val controller = File(sourceRoot, "camera/CameraController.kt").readText()
        val pipeline = File(sourceRoot, "pipeline/FramePipelineManager.kt").readText()
        val yuvTools = File(sourceRoot, "ai/Yuv420ImageTools.kt").readText()
        val viewModel = File(sourceRoot, "ui/CameraViewModel.kt").readText()
        val sourcePolicy = File(sourceRoot, "recording/Npv10CaptureSourcePolicy.kt").readText()

        assertTrue(controller.contains("npv10AnalysisImageFormat"))
        assertTrue(controller.contains("ImageFormat.YCBCR_P010"))
        assertTrue(pipeline.contains("Yuv420ImageTools.snapshot(image)"))
        assertTrue(yuvTools.contains("snapshotP010"))
        assertTrue(yuvTools.contains("ImageFormat.YCBCR_P010"))
        assertTrue(yuvTools.contains("bitDepth = 10"))
        assertTrue(viewModel.contains("_cinemaVideoMode.value != CinemaVideoMode.RAW"))
        assertFalse(viewModel.contains("setNpv10RawFrameSink"))
        assertFalse(sourcePolicy.contains("ImageFormat.RAW_SENSOR"))
        assertFalse(sourcePolicy.contains("ImageFormat.RAW_PRIVATE"))
    }

    @Test
    fun npv10ManagerCopiesP010ImageBeforeAsyncWriterThread() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val manager = File(sourceRoot, "recording/Npv10RecordingManager.kt").readText()
        val enqueueBody = manager
            .substringAfter("fun enqueueP010Image(image: Image): Boolean")
            .substringBefore("private fun copyP010ImageForAsyncWrite")
        val copyIndex = enqueueBody.indexOf("copyP010ImageForAsyncWrite(image, sessionConfig)")
        val executeIndex = enqueueBody.indexOf("writerExecutor.execute")

        assertTrue(copyIndex >= 0)
        assertTrue(executeIndex >= 0)
        assertTrue(copyIndex < executeIndex)
        assertTrue(enqueueBody.contains("writeCopiedP010Frame(copiedFrame)"))
        assertFalse(enqueueBody.contains("writeP010Image(image)"))

        val copyBody = manager
            .substringAfter("private fun copyP010ImageForAsyncWrite(")
            .substringBefore("private fun validateRuntimeP010Image")
        assertTrue(copyBody.contains("copyP010ImageIntoDirectBufferWithResult(image, payloadBuffer)"))
        assertTrue(copyBody.contains("finally"))
        assertTrue(copyBody.contains("closeImageSafely(image)"))
    }

    private fun assertSizeEquals(expected: Npv10CaptureSize, actual: Npv10CaptureSize) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
    }
}
