package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceEncoderReadinessPolicyTest {
    @Test
    fun strictReadinessAcceptsOnlyHevcMain10Bt2020Pq() {
        val valid = SurfaceEncoderReadinessPolicy.OutputFormatEvidence(
            mime = "video/hevc",
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084
        )
        assertTrue(SurfaceEncoderReadinessPolicy.verifyStrictHevcMain10Pq(valid).accepted)
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyStrictHevcMain10Pq(
                valid.copy(profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            ).accepted
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyStrictHevcMain10Pq(
                valid.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_HLG)
            ).accepted
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyStrictHevcMain10Pq(
                valid.copy(colorStandard = MediaFormat.COLOR_STANDARD_BT709)
            ).accepted
        )
    }

    @Test
    fun configuredReadinessAcceptsSdrMain10WithoutRelabelingItAsPq() {
        val sdr = SurfaceEncoderReadinessPolicy.OutputFormatEvidence(
            mime = "video/hevc",
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertTrue(
            SurfaceEncoderReadinessPolicy.verifyConfiguredHevcMain10(
                evidence = sdr,
                expectedColorStandard = MediaFormat.COLOR_STANDARD_BT709,
                expectedColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ).accepted
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyConfiguredHevcMain10(
                evidence = sdr.copy(profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
                expectedColorStandard = MediaFormat.COLOR_STANDARD_BT709,
                expectedColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ).accepted
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyConfiguredHevcMain10(
                evidence = sdr.copy(colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084),
                expectedColorStandard = MediaFormat.COLOR_STANDARD_BT709,
                expectedColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            ).accepted
        )
    }

    @Test
    fun readinessRequiresBothFormatAndDiscardedSample() {
        assertFalse(SurfaceEncoderReadinessPolicy.readinessProven(false, false))
        assertFalse(SurfaceEncoderReadinessPolicy.readinessProven(true, false))
        assertFalse(SurfaceEncoderReadinessPolicy.readinessProven(false, true))
        assertTrue(SurfaceEncoderReadinessPolicy.readinessProven(true, true))
        assertTrue(
            SurfaceEncoderReadinessPolicy.shouldDiscardOutput(
                SurfaceEncoderReadinessPolicy.Phase.WARMING
            )
        )
        assertTrue(
            SurfaceEncoderReadinessPolicy.shouldDiscardOutput(
                SurfaceEncoderReadinessPolicy.Phase.READY
            )
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.shouldDiscardOutput(
                SurfaceEncoderReadinessPolicy.Phase.CLIP_ACTIVE
            )
        )
    }

    @Test
    fun firstLiveClipSampleMustBeRandomAccessAfterPreRoll() {
        assertTrue(
            SurfaceEncoderReadinessPolicy.verifyFirstClipSample(
                keyFrameRequired = true,
                bufferFlags = MediaCodec.BUFFER_FLAG_KEY_FRAME
            ).accepted
        )
        assertFalse(
            SurfaceEncoderReadinessPolicy.verifyFirstClipSample(
                keyFrameRequired = true,
                bufferFlags = 0
            ).accepted
        )
        assertTrue(
            SurfaceEncoderReadinessPolicy.verifyFirstClipSample(
                keyFrameRequired = false,
                bufferFlags = 0
            ).accepted
        )
    }

    @Test
    fun nativeReadinessFrameHasNoRawInputOrClipAccounting() {
        val source = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val body = source.substringAfter(
            "OffscreenAhbEglEncoderBridge_nativeSubmitEncoderReadinessFrame"
        ).substringBefore(
            "OffscreenAhbEglEncoderBridge_nativeSubmitPregradedBt2020Pq"
        )
        assertTrue(body.contains("glClearColor(0.0f, 0.0f, 0.0f, 1.0f)"))
        assertTrue(body.contains("eglSwapBuffers"))
        assertFalse(body.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertFalse(body.contains("++state->submittedFrames"))
        assertFalse(body.contains("historyValid = true"))
    }

    @Test
    fun readinessAndLiveFramesShareOneExplicitMonotonicTimeline() {
        val runtime = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        val readinessRoute = runtime.substringAfter(
            "fun submitOffscreenAhbEglEncoderReadinessFrame(): Boolean"
        ).substringBefore("fun setRawSceneLogSubmission")
        val native = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
        val readinessNative = native.substringAfter(
            "OffscreenAhbEglEncoderBridge_nativeSubmitEncoderReadinessFrame"
        ).substringBefore(
            "OffscreenAhbEglEncoderBridge_nativeSubmitPregradedBt2020Pq"
        )
        val liveNative = native.substringAfter(
            "OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer"
        )

        assertTrue(readinessRoute.contains("System.nanoTime().coerceAtLeast(1L)"))
        assertTrue(readinessRoute.contains("continueAfterReadiness("))
        assertFalse(readinessRoute.contains("submitEncoderReadinessFrame(1L)"))
        assertTrue(readinessNative.contains("jlong presentationTimeNs"))
        assertTrue(
            readinessNative.contains(
                "state->lastPresentationTimeNs = static_cast<int64_t>(presentationTimeNs)"
            )
        )
        assertTrue(liveNative.contains("presentationTimeNs <= state->lastPresentationTimeNs"))
        assertTrue(liveNative.contains("++state->presentationTimelineRegressions"))
    }

    @Test
    fun everyOffscreenRouteCommitsReadinessBeforeLiveIngress() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val route = source.substringAfter("beginSurfaceEncoderReadinessPreRoll()")
            .substringBefore("Offscreen AHB EGL encoder recording started")
        val submit = route.indexOf("submitOffscreenAhbEglEncoderReadinessFrame()")
        val await = route.indexOf("awaitSurfaceEncoderReadiness()")
        val commit = route.indexOf("commitSurfaceEncoderReadiness()")
        val liveGate = route.indexOf("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(true)")
        val firstProcessed = route.indexOf("awaitFirstProcessedGpuRecordingFrame(")
        val keyFrameProof = route.indexOf("awaitSurfaceEncoderClipKeyFrame()")
        val clipClock = route.indexOf("markGpuRecordingReadyForClipTiming()")
        val ingress = route.indexOf("sensorLinearNdrRecordingLifecycle.startIngress()")
        assertTrue(submit >= 0)
        assertTrue(await > submit)
        assertTrue(commit > await)
        assertTrue(ingress > commit)
        assertFalse(route.substring(0, commit).contains("gpuRecording.start()"))
        assertTrue(liveGate > commit)
        assertTrue(firstProcessed > liveGate)
        assertTrue(keyFrameProof > firstProcessed)
        assertTrue(clipClock > keyFrameProof)
    }

    @Test
    fun clipCommitWaitsForAQueuedIdrWithoutPublishingLeadingInterFrames() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        ).readText()
        val commit = source.substringAfter("fun commitSurfaceEncoderReadiness(): Boolean")
            .substringBefore("private fun captureSurfaceEncoderReadinessFormat")
        val drain = source.substringAfter("private fun drainVideoEncoder(")
            .substringBefore("private fun drainBlockModelVideoEncoder(")

        assertTrue(commit.contains("check(requestVideoSyncFrame("))
        assertTrue(commit.contains("surfaceEncoderClipKeyFrameRequired.set(true)"))
        assertTrue(drain.contains("verifyFirstClipSample("))
        assertTrue(drain.contains("POST_READINESS_IDR_WAIT"))
        assertTrue(drain.contains("post-readiness-first-live-retry"))
        assertTrue(drain.contains("surfaceEncoderClipKeyFrameLatch.countDown()"))
        assertTrue(drain.contains("surfaceEncoderClipKeyFrameRequired.set(false)"))
    }
}
