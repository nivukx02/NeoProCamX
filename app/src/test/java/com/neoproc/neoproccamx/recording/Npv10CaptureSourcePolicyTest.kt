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
    fun coldOpenStandbyRejectsTheSameNonExactResolutionAsNpv10Capture() {
        val selected = Npv10CaptureSize(7680, 6144)
        val exact = Npv10CaptureSourcePolicy.resolveNativeP010TargetForSizes(
            selectedResolution = selected,
            p010Sizes = listOf(
                Npv10CaptureSize(352, 288),
                Npv10CaptureSize(4096, 3072)
            )
        )

        assertEquals(null, exact)
    }

    @Test
    fun resolveRejectsNearbyP010AndReportsRequestedAndAvailableSizes() {
        val failure = runCatching {
            Npv10CaptureSourcePolicy.resolveForSizes(
                selectedResolution = Npv10CaptureSize(7680, 6144),
                p010Sizes = listOf(
                    Npv10CaptureSize(352, 288),
                    Npv10CaptureSize(4096, 3072)
                ),
                yuv420Sizes = listOf(Npv10CaptureSize(7680, 6144)),
                requireTenBit = true
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("requested=7680x6144"))
        assertTrue(failure?.message.orEmpty().contains("available=[352x288, 4096x3072]"))
        assertTrue(failure?.message.orEmpty().contains("refuses resolution substitution"))
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
    fun idlePrearmRejectsUnsupportedExactP010WithoutCrashingModeSelectionOrFallingBack() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val viewModel = File(sourceRoot, "ui/CameraViewModel.kt").readText()
        val prearm = viewModel
            .substringAfter("private fun requestNpv10CapturePrearm(forceSessionRefresh: Boolean)")
            .substringBefore("private fun primeNpv10CaptureGraphForOpen()")

        val resolveIndex = prearm.indexOf("resolveNpv10CaptureSourcePlanForStableStandby(requestedSize)")
        val catchIndex = prearm.indexOf("catch (error: RuntimeException)")
        val disableIndex = prearm.indexOf("camera.setNpv10TenBitCaptureEnabled(false)")
        val returnIndex = prearm.indexOf("return", disableIndex)

        assertTrue(resolveIndex >= 0)
        assertTrue(catchIndex > resolveIndex)
        assertTrue(disableIndex > catchIndex)
        assertTrue(returnIndex > disableIndex)
        assertTrue(prearm.contains("npv10CapturePrearmedSignature = null"))
        assertTrue(prearm.contains("npv10CaptureRefreshSignature = null"))
        assertTrue(prearm.contains("no resolution or 8-bit fallback was used"))
    }

    @Test
    fun npv10ManagerTransfersImageToBoundedPackingWorkersBeforeOrderedDurableWrite() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val manager = File(sourceRoot, "recording/Npv10RecordingManager.kt").readText()
        val enqueueBody = manager
            .substringAfter("internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission")
            .substringBefore("private fun publishPreparedP010Commit")
        val publishBody = manager
            .substringAfter("private fun publishPreparedP010Commit")
            .substringBefore("private fun commitPreparedP010InOrder")
        val commitBody = manager
            .substringAfter("private fun commitPreparedP010InOrder")
            .substringBefore("private fun retireRejectedPreparedP010Commit")
        val workerIndex = enqueueBody.indexOf("p010PackingExecutor.execute")
        val packIndex = enqueueBody.indexOf("prepareP010ImageForAsyncWrite(")
        val publishIndex = enqueueBody.indexOf("publishPreparedP010Commit(requireNotNull(completion))")

        assertTrue(workerIndex >= 0)
        assertFalse(enqueueBody.contains("dmcP010PackingExecutor"))
        assertTrue(packIndex > workerIndex)
        assertTrue(publishIndex > packIndex)
        assertTrue(enqueueBody.contains("acquirePendingFrameSlot(frameSlots)"))
        assertTrue(manager.contains("frameSlots.tryAcquire()"))
        assertFalse(enqueueBody.contains("writerExecutor.execute"))
        assertTrue(publishBody.contains("writerExecutor.execute"))
        assertTrue(commitBody.contains("writePreparedP010Packet"))
        assertFalse(enqueueBody.contains("writeP010Image(image)"))
        assertTrue(manager.contains("packP010ImageToPackedTenBitBufferWithCrcResult"))
        assertTrue(manager.contains("copyP010ImageIntoDirectBufferWithResult"))
        assertTrue(manager.contains("Npv10PreparedP010CrcPath.CAMERA_PLANE_FUSED_NATIVE"))
        assertTrue(manager.contains("Npv10PreparedP010CrcPath.CANONICAL_PACKET_NATIVE"))
        assertTrue(manager.contains("Npv10PreparedP010CrcPath.JAVA_PACKET_FALLBACK"))
        assertTrue(manager.contains("if (!imageClosed) closeImageSafely(image)"))
        val prepareBody = manager
            .substringAfter("private fun prepareP010ImageForAsyncWrite")
            .substringBefore("private fun validateRuntimeP010Image")
        val niv99CanonicalFallback = prepareBody.substringAfter("if (!processed) {")
        assertTrue(
            niv99CanonicalFallback.indexOf("copyP010ImageIntoCanonicalDirectBuffer(") <
                niv99CanonicalFallback.indexOf("closeImageSafely(image)")
        )
        assertTrue(
            niv99CanonicalFallback.indexOf("closeImageSafely(image)") <
                niv99CanonicalFallback.indexOf("processor.processCanonicalAndPack(")
        )
        assertTrue(manager.contains("primeP010PacketBufferPool(config)"))
    }

    private fun assertSizeEquals(expected: Npv10CaptureSize, actual: Npv10CaptureSize) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
    }
}
