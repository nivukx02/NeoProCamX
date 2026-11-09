package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** These source contracts protect ownership ordering; hardware tests prove the JNI boundary. */
class Npv10EarlyImageReleaseContractTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val native = File(main, "cpp/npv10_raw_native.cpp").readText()
        .substringAfter("Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010ImagePlanesToDirectMappedTenBitWithCrc(")
        .substringBefore("Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(")
    private val manager = File(main,
        "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()

    @Test
    fun callbackRunsAfterAllPlaneReadsAndBeforeEitherPublicationRoute() {
        val packed = native.indexOf("const bool packed = target.fusedLosslessPreparation")
        val fused = native.indexOf("compressP010PlanesParallelRangeLz4(")
        val release = native.indexOf("env->CallVoidMethod(onPlanesCopied, releaseSource)")
        val finish = native.indexOf("finishDirectMappedPayloadProcessing(")
        val finishEncoded = native.indexOf("finishDirectMappedEncodedPayloadProcessing(")
        assertTrue(packed >= 0 && release > packed && finish > release)
        assertTrue(fused >= 0 && fused < packed && finishEncoded > release)
        assertTrue(native.contains("if (packed && releaseSource != nullptr)"))
        assertFalse(native.substring(release).contains("packStandardP010PlanesToTenBit("))
        assertFalse(native.substring(release).contains("compressP010PlanesRealtimeRangeLz4("))
        assertFalse(native.substring(release).contains("compressP010PlanesParallelRangeLz4("))
        assertTrue(native.contains("niv99P010RowExecutor().executeLosslessChunks(job, processor)"))
        assertTrue(native.contains("width == 4096 && height == 3072"))
        assertTrue(native.contains("yPixelStride == 2 && uvPixelStride == 4"))
    }

    @Test
    fun callbackFailureRetiresProcessingWithoutCompressionOrPublication() {
        assertTrue(native.contains("sourceReleaseSucceeded = !env->ExceptionCheck()"))
        assertTrue(native.contains("packed && sourceReleaseSucceeded,"))
        assertTrue(native.contains("packed && sourceReleaseSucceeded && finished"))
        assertTrue(native.indexOf("GetMethodID(callbackClass") <
            native.indexOf("acquireDirectMappedPayload("))
    }

    @Test
    fun liveCameraOwnerClosesExactlyOnceAndKeepsTerminalFallbackCleanup() {
        val prepare = manager.substringAfter("private fun prepareP010ImageForAsyncWrite(")
            .substringBefore("private fun validateRuntimeP010Image(")
        val callback = prepare.substringAfter("onPlanesCopied = Runnable {")
            .substringBefore("requireNotNull(preparedPayload)")
        assertTrue(callback.contains("check(!imageClosed)"))
        assertTrue(callback.indexOf("imageClosed = true") < callback.indexOf("image.close()"))
        assertTrue(callback.contains("recordEarlyP010ImageRelease("))
        assertTrue(prepare.contains("if (!imageClosed) closeImageSafely(image)"))
        assertTrue(prepare.contains("activeWriter.abortDirectMappedFrame(sequence)"))
    }

    @Test
    fun legacyCallersAndFailedNativeOwnershipRemainObservable() {
        val packer = File(main,
            "java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt").readText()
        assertTrue(packer.contains("onPlanesCopied: Runnable? = null"))
        assertTrue(packer.contains("if (onPlanesCopied != null) throw failure"))
        assertTrue(manager.contains("npv10EarlyReleasedCameraImages"))
        assertTrue(manager.contains("npv10EarlyReleaseWorkerHoldMaximumMs"))
        assertTrue(manager.split("earlyReleasedP010Images.set(0L)").size == 3)
    }
}
