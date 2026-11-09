package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarPolicy
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarReader
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.Npv10FileReader
import com.neoproc.neoproccamx.recording.Npv10RecordingManager
import com.neoproc.neoproccamx.recording.Npv10VerifiedMasterPublication
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual camera/visible-surface recovery under an explicitly injected failure, not an FPS PASS. */
@RunWith(AndroidJUnit4::class)
class Npv10FailedStopPreviewInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun failedCinemaWriter_restoresVisibleFramesWhileAuditRetainsRecordingOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val masterDirectory = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val priorMasters = masterDirectory.listFiles().orEmpty().map(File::getName).toSet()
        val auditEntered = CountDownLatch(1)
        val releaseAudit = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { vm = it.vm }
            awaitCondition(30_000L, "Initial camera preview did not stream") {
                vm.cameraState.value == CameraState.Streaming &&
                    !vm.camera.sessionRefreshPending.get()
            }
            scenario.onActivity {
                vm.setMode(AppMode.VIDEO)
                vm.setRawEnabled(false)
                vm.setLogEnabled(false)
                vm.setCinemaVideoMode(CinemaVideoMode.PRO)
                vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                vm.setVideoHdrMode("OFF")
                vm.setVideoColorSpace("Rec.709")
                vm.setColorProfile("Natural")
                vm.setLutEnabled(false)
                vm.setTenBitRecordingEnabled(true)
                vm.setVideoDetailProfile(VideoDetailProfile.NATURAL_DETAIL)
                vm.setSdrConversionPipelineEnabled(true)
                vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                vm.setVideoResolution(Size(3264, 2448))
                vm.setFPS(25)
                vm.setDebugValidationLabel("lq001_injected_writer_failure_preview_recovery")
            }
            awaitCondition(30_000L, "Requested Cinema 3264x2448/25 selection did not settle") {
                vm.cameraState.value == CameraState.Streaming &&
                    !vm.camera.sessionRefreshPending.get() &&
                    vm.gpuRecording.videoWidth == 3264 && vm.gpuRecording.videoHeight == 2448 &&
                    vm.recording.fps == 25
            }
            val sequenceBeforeStart = vm.previewDisplayPresentSnapshot().latestSequence
            awaitCondition(10_000L, "No fresh visible frames before recording") {
                vm.previewDisplayPresentSnapshot().latestSequence >= sequenceBeforeStart + 10L
            }
            // Test-only reflection reaches the existing failure entry point. No production failure
            // switch, synthetic camera frame, modified pixel, or false durability proof is added.
            val recorder = CameraViewModel::class.java.getDeclaredField("npv10Recording")
                .apply { isAccessible = true }.get(vm) as Npv10RecordingManager
            val originalMetadataProvider = recorder.runtimeEvidenceMetadataProvider
            try {
                scenario.onActivity { vm.startRecording() }
                awaitCondition(45_000L, "Cinema recording never reached durable first-frame admission") {
                    vm.gpuRecordState.value is GpuRecordingState.Recording &&
                        recorder.hasActiveSession() && vm.recDuration.value >= 3_000L
                }
                recorder.runtimeEvidenceMetadataProvider = {
                    auditEntered.countDown()
                    check(releaseAudit.await(30L, TimeUnit.SECONDS)) {
                        "Test audit hold was not released"
                    }
                    originalMetadataProvider?.invoke().orEmpty()
                }
                val injectedAt = SystemClock.elapsedRealtime()
                assertTrue(recorder.failInputContractViolation(
                    "LQ001_CONTROLLED_FAILURE: verify drained preview recovery before full audit"
                ))
                assertTrue("Failed master never entered real evidence finalization",
                    auditEntered.await(90L, TimeUnit.SECONDS))
                assertTrue("Finalization owner was retired before the held audit",
                    recorder.hasActiveSession())
                val sequenceAtAudit = vm.previewDisplayPresentSnapshot().latestSequence
                awaitCondition(5_000L, "Visible preview stayed frozen while the audit held ownership") {
                    vm.previewDisplayPresentSnapshot().latestSequence >= sequenceAtAudit + 25L
                }
                val recovered = vm.previewDisplayPresentSnapshot()
                Log.i("Lq001FailedStopRecovery",
                    "PHYSICAL_PREVIEW_RECOVERED injected=true auditHeld=true " +
                        "visibleFrames=${recovered.latestSequence - sequenceAtAudit} " +
                        "elapsedSinceInjectionMs=${SystemClock.elapsedRealtime() - injectedAt} " +
                        "generation=${recovered.currentSessionGeneration} " +
                        "source=3264x2448/25/Main10 publicationAllowed=false")
                assertTrue("Failed master lost its finalization owner during preview recovery",
                    recorder.hasActiveSession())
            } finally {
                releaseAudit.countDown()
                if (recorder.hasActiveSession()) scenario.onActivity { vm.stopRecording() }
                try {
                    awaitCondition(120_000L, "Failed recording did not retire after releasing its audit") {
                        !recorder.hasActiveSession()
                    }
                } finally {
                    recorder.runtimeEvidenceMetadataProvider = originalMetadataProvider
                }
            }
            val newMasters = masterDirectory.listFiles().orEmpty().filter {
                it.name !in priorMasters && it.extension.equals("npv10", ignoreCase = true)
            }
            assertEquals("Expected exactly one retained, failed diagnostic master", 1, newMasters.size)
            assertTrue("The actual recorded pixels were not retained", newMasters.single().length() > 0L)
            assertFalse("Injected failure was incorrectly published as a verified master",
                Npv10VerifiedMasterPublication.isVerified(newMasters.single()))
            // A failed video boundary cannot certify A/V sync, but it must not erase otherwise
            // valid microphone data while retaining the original video for private recovery.
            val failedMaster = newMasters.single()
            assertTrue("Writer failure discarded captured AAC from the retained private master",
                Npv10AudioSidecarPolicy.sidecarFor(failedMaster).isFile)
            assertFalse("Recovery left the AAC writer's temporary file open",
                Npv10AudioSidecarPolicy.temporarySidecarFor(failedMaster).exists())
            val firstVideoTimestamp = Npv10FileReader(failedMaster).use {
                requireNotNull(it.readNextFrame()).timestampNs
            }
            Npv10AudioSidecarReader(failedMaster).use { reader ->
                assertEquals("Recovered AAC must keep the real camera timeline origin",
                    firstVideoTimestamp, reader.header.videoOriginSensorTimestampNs)
                assertEquals(48_000, reader.header.sampleRate)
                assertEquals(2, reader.header.channelCount)
                assertEquals(256_000, reader.header.bitRate)
                var packets = 0L
                while (reader.readNextPacket() != null) packets++
                val audit = reader.requireAudit()
                assertTrue("No real captured AAC packets were retained", packets > 0L)
                assertEquals(packets, audit.packetCount)
                assertTrue(audit.crcValidated && audit.monotonicPts)
                Log.i("Lq001FailedStopRecovery",
                    "PHYSICAL_FAILED_AUDIO_RECOVERED master=${failedMaster.name} " +
                        "packets=$packets bytes=${audit.payloadBytes} " +
                        "originNs=${reader.header.videoOriginSensorTimestampNs} " +
                        "publicationAllowed=false avSyncCertified=false")
            }
        }
    }

    private fun awaitCondition(timeoutMs: Long, message: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, ready())
    }
}
