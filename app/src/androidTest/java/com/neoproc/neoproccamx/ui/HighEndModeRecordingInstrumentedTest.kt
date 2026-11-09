package com.neoproc.neoproccamx.ui

import android.Manifest
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarPolicy
import com.neoproc.neoproccamx.recording.Npv10AudioSidecarReader
import com.neoproc.neoproccamx.recording.Npv10AudioVideoDurationPolicy
import com.neoproc.neoproccamx.recording.Npv10ExportCadencePolicy
import com.neoproc.neoproccamx.recording.Npv10FileReader
import com.neoproc.neoproccamx.recording.Npv10MasterClip
import com.neoproc.neoproccamx.recording.Npv10MovConverter
import com.neoproc.neoproccamx.recording.Npv10MovPublishPolicy
import com.neoproc.neoproccamx.recording.Npv10PacketCodec
import com.neoproc.neoproccamx.recording.Npv10PacketCodecEngine
import com.neoproc.neoproccamx.recording.Npv10PayloadFormat
import com.neoproc.neoproccamx.recording.Npv10VerifiedMasterPublication
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.RecordDevelopHevcMovEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HighEndModeRecordingInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    /** Real Camera2/codec fault injection, not an endurance or physical-quality PASS. */
    @Test
    fun directFatalWorkerFailure_afterActivityRecreation_cleansUpAndAllowsNewRecording() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val label = "lq001_fatal_cleanup_${SystemClock.elapsedRealtime()}"
        val reason = "LQ001_INJECTED_AUDIO_HORIZON_FATAL"
        fun rowsForLabel(value: String): Int = requireNotNull(context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("%$value%"),
            null
        )).use { it.count }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { vm = it.vm }
            awaitCameraStreaming(vm)
            val originalCinemaPipeline = vm.sdrConversionPipelineEnabled.value
            try {
                scenario.onActivity {
                    configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                    vm.setSdrConversionPipelineEnabled(false)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    vm.setVideoDetailProfile(VideoDetailProfile.EXTREME_DETAIL)
                    vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                    vm.setVideoResolution(Size(4096, 3072))
                    vm.setFPS(25)
                    vm.setDebugValidationLabel(label)
                }
                awaitCondition(30_000L, "Exact Pro/Direct/Extreme/4096x3072/25 settings did not settle") {
                    vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                        !vm.sdrConversionPipelineEnabled.value &&
                        !vm.usesNpv10CapturePipelineForCurrentMode() &&
                        vm.gpuRecording.videoWidth == 4096 && vm.gpuRecording.videoHeight == 3072 &&
                        vm.gpuRecording.videoFps == 25 &&
                        vm.gpuRecording.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
                }
                awaitFreshCameraSession(scenario, vm, label)
                scenario.onActivity { vm.startRecording() }
                awaitRecording(vm, timeoutMs = 45_000L)
                SystemClock.sleep(3_000L)
                scenario.recreate()
                scenario.onActivity { assertTrue("Recording owner was replaced on recreation", it.vm === vm) }
                awaitRecording(vm)
                val failureAtMs = SystemClock.elapsedRealtime()
                // Instrumentation thread models the worker caller; no shutter stop is issued here.
                vm.gpuRecording.reportFatalPipelineFailure(reason)
                vm.gpuRecording.reportFatalPipelineFailure("LQ001_SECONDARY_FAILURE_MUST_NOT_REPLACE_ROOT")
                awaitRecordingCleanup(vm, timeoutMs = 30_000L)
                assertEquals("Fatal clip was published or left a pending MediaStore row", 0, rowsForLabel(label))
                assertTrue("First fatal cause was lost", vm.gpuRecording.fatalPipelineFailures.value?.message == reason)
                Log.i("LQ001FatalCleanup", "FAULT_CLEANUP elapsedMs=${SystemClock.elapsedRealtime() - failureAtMs} " +
                    "reason=$reason failedRows=0 transport=4096x3072/25/Main10 faultInjected=true")

                val restartLabel = "${label}_recovery"
                awaitCameraStreaming(vm)
                scenario.onActivity {
                    vm.setDebugValidationLabel(restartLabel)
                    vm.startRecording()
                }
                awaitRecording(vm, timeoutMs = 45_000L)
                assertEquals("Previous session poisoned the new recording", null,
                    vm.gpuRecording.fatalPipelineFailures.value)
                SystemClock.sleep(3_000L)
                scenario.onActivity { vm.stopRecording() }
                awaitRecordingFinalized(vm, timeoutMs = 45_000L)
                assertEquals("Recovery must publish exactly one new recording", 1, rowsForLabel(restartLabel))
                Log.i("LQ001FatalCleanup", "RECOVERY_RECORDING_PUBLISHED label=$restartLabel")
            } finally {
                scenario.onActivity { vm.stopRecording() }
                awaitRecordingCleanup(vm, timeoutMs = 45_000L)
                scenario.onActivity { vm.setSdrConversionPipelineEnabled(originalCinemaPipeline) }
            }
        }
    }

    @Test
    fun npv10Capture_storesSynchronizedAacAndConversionPreservesIt() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)
            scenario.onActivity {
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                vm.recording.audioSource = AudioSrc.UNPROCESSED
                vm.setVideoResolution(Size(1920, 1080))
                vm.setFPS(25)
                vm.setDebugValidationLabel("lq028_av_sync_1080p25")
            }
            awaitFreshCameraSession(scenario, vm, "LQ028_AV_SYNC")
            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm)
            SystemClock.sleep(3_000L)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm)

            val master = awaitNewVerifiedMaster(masterDir, mastersBefore)
            var masterWidth = 0
            var masterHeight = 0
            var masterFps = 0
            var masterFrames = 0L
            var masterOrientationDegrees = 0
            val videoDurationUs = Npv10FileReader(master).use { reader ->
                assertEquals("true", reader.header.metadataValues["audiostoredinnpv10bundle"])
                masterWidth = reader.header.width
                masterHeight = reader.header.height
                masterFps = reader.header.fps
                masterOrientationDegrees = reader.header.orientationHintDegrees
                masterFrames = reader.countFramesForPlayback().toLong()
                Npv10ExportCadencePolicy.endPresentationTimeUs(masterFrames, masterFps)
            }
            assertTrue(
                "Synchronized NPV10 AAC sidecar was not published",
                Npv10AudioSidecarPolicy.sidecarFor(master).isFile
            )
            var audioPackets = 0L
            val audioAudit = Npv10AudioSidecarReader(master).use { reader ->
                while (reader.readNextPacket() != null) audioPackets++
                reader.requireAudit()
            }
            assertTrue("NPV10 AAC sidecar has no packets", audioPackets > 0L)
            assertTrue("NPV10 AAC timestamps are not monotonic", audioAudit.monotonicPts)
            assertTrue("NPV10 AAC packet CRC validation failed", audioAudit.crcValidated)
            assertTrue(
                "NPV10 audio/video duration delta exceeded 500 ms: " +
                    "audio=${audioAudit.finalPresentationTimeUs} video=$videoDurationUs",
                kotlin.math.abs(audioAudit.finalPresentationTimeUs - videoDurationUs) <= 500_000L
            )

            val evidenceDir = requireNotNull(context.getExternalFilesDir("lq028")) {
                "External LQ-028 evidence directory is unavailable"
            }.apply { check(isDirectory || mkdirs()) }
            val output = File(evidenceDir, "lq028-audio-preservation-r210.mov")
            val report = File(evidenceDir, "lq028-audio-preservation-report.txt")
            check(!output.exists() || output.delete()) {
                "Unable to replace stale LQ-028 retained MOV evidence"
            }
            check(!report.exists() || report.delete()) {
                "Unable to replace stale LQ-028 retained report"
            }
            var conversionPassed = false
            try {
                val conversion = Npv10MovConverter.convertWithAudit(
                    input = master,
                    output = output,
                    outputFormat = Npv10MovConverter.OutputFormat.R210_444
                )
                assertEquals(com.neoproc.neoproccamx.recording.Npv10AuditStatus.PASS, conversion.auditStatus)
                assertTrue("Conversion lost required audio", conversion.audioExpected && conversion.audioPresent)
                assertEquals(audioPackets, conversion.audioPacketCount)
                assertTrue(conversion.audioMonotonicPts)
                assertTrue(conversion.audioCrcValidated)
                assertTrue("Converted MOV has no mp4a sample entry", fileContainsAscii(output, "mp4a"))
                assertTrue("Converted MOV has no nclx colour atom", fileContainsAscii(output, "nclx"))
                assertTrue("Converted MOV has no track header", fileContainsAscii(output, "tkhd"))
                assertTrue("Converted MOV frame count changed", conversion.frameCount == masterFrames)
                val expectedVideoPayloadBytes = Math.multiplyExact(
                    Math.multiplyExact(masterWidth.toLong(), masterHeight.toLong()),
                    Math.multiplyExact(Int.SIZE_BYTES.toLong(), masterFrames)
                )
                assertTrue(
                    "R210 MOV is smaller than its exact uncompressed video payload: " +
                        "output=${output.length()} expectedVideoPayload=$expectedVideoPayloadBytes",
                    output.length() >= expectedVideoPayloadBytes
                )
                val measuredContainerBitrateBps = if (videoDurationUs > 0L) {
                    output.length().toDouble() * 8.0 * 1_000_000.0 / videoDurationUs.toDouble()
                } else {
                    0.0
                }
                val fixedR210VideoBitrateBps = Math.multiplyExact(
                    Math.multiplyExact(masterWidth.toLong(), masterHeight.toLong()),
                    Math.multiplyExact(Int.SIZE_BYTES.toLong() * 8L, masterFps.toLong())
                )
                assertTrue(
                    "Measured R210 container bitrate fell below its fixed uncompressed payload rate: " +
                        "measured=$measuredContainerBitrateBps fixed=$fixedR210VideoBitrateBps",
                    measuredContainerBitrateBps >= fixedR210VideoBitrateBps.toDouble()
                )
                val audioVideoDurationDeltaUs = kotlin.math.abs(
                    audioAudit.finalPresentationTimeUs - videoDurationUs
                )
                report.writeText(
                    buildString {
                        appendLine("status=PASS")
                        appendLine("masterName=${master.name}")
                        appendLine("masterWidth=$masterWidth")
                        appendLine("masterHeight=$masterHeight")
                        appendLine("masterFps=$masterFps")
                        appendLine("masterFrames=$masterFrames")
                        appendLine("masterOrientationDegrees=$masterOrientationDegrees")
                        appendLine("videoDurationUs=$videoDurationUs")
                        appendLine("audioPacketCount=$audioPackets")
                        appendLine("audioFirstPresentationTimeUs=${audioAudit.firstPresentationTimeUs}")
                        appendLine("audioFinalPresentationTimeUs=${audioAudit.finalPresentationTimeUs}")
                        appendLine("audioVideoDurationDeltaUs=$audioVideoDurationDeltaUs")
                        appendLine("audioMonotonicPts=${audioAudit.monotonicPts}")
                        appendLine("audioCrcValidated=${audioAudit.crcValidated}")
                        appendLine("outputName=${output.name}")
                        appendLine("outputBytes=${output.length()}")
                        appendLine("sampleEntry=${conversion.sampleEntry}")
                        appendLine("conversionFrameCount=${conversion.frameCount}")
                        appendLine("validQuickTimeAtoms=${conversion.validQuickTimeAtoms}")
                        appendLine("fixedR210VideoBitrateBps=$fixedR210VideoBitrateBps")
                        appendLine("measuredContainerBitrateBps=${measuredContainerBitrateBps.toLong()}")
                        appendLine("rateControl=FIXED_UNCOMPRESSED_R210")
                        append(conversion.toCertificateText())
                    }
                )
                Log.i(
                    "Lq028Evidence",
                    "retained=${output.absolutePath} frames=$masterFrames " +
                        "audioPackets=$audioPackets avDeltaUs=$audioVideoDurationDeltaUs " +
                        "containerBitrateBps=${measuredContainerBitrateBps.toLong()} " +
                        "orientation=$masterOrientationDegrees"
                )
                conversionPassed = true
            } finally {
                if (!conversionPassed) {
                    output.delete()
                    report.delete()
                }
            }
        }
    }

    @Test
    fun explicitWidescreenSelection_ignoresStaleFourThreeAspectAndRecordsAt25Fps() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)
            scenario.onActivity {
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                vm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                vm.setVideoResolution(Size(1920, 1080))
                vm.setFPS(25)
                vm.setDebugValidationLabel("qa_explicit_1080p25")
            }
            awaitCondition(20_000L, "Explicit 1920x1080 selection was replaced by stale 4:3 state") {
                vm.gpuRecording.videoWidth == 1920 && vm.gpuRecording.videoHeight == 1080
            }
            awaitFreshCameraSession(scenario, vm, "EXPLICIT_1080P25")
            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm)
            SystemClock.sleep(3_000L)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm)

            val master = awaitNewVerifiedMaster(masterDir, mastersBefore)
            Npv10FileReader(master).use { reader ->
                assertEquals(1920, reader.header.width)
                assertEquals(1080, reader.header.height)
                assertEquals(25, reader.header.fps)
                val cadence = measureVerifiedContentCadence(master, reader)
                assertTrue(
                    "1080p25 master contains too few content frames: " +
                        "${cadence.contentFrameCount} content/${cadence.totalFrameCount} total",
                    cadence.contentFrameCount >= MIN_CONTENT_FRAMES
                )
                assertTrue(
                    "Camera cadence was not locked to 25 fps: ${cadence.measuredFps}",
                    cadence.measuredFps in 24.0..26.0
                )
            }
        }
    }

    @Test
    fun fullFourKSelection_recordsNativeTenBitMasterAt25Fps() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)

            lateinit var selectedSize: Size
            scenario.onActivity {
                selectedSize = vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 4096 && size.height == 3072
                } ?: vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 3840 && size.height == 2160
                } ?: throw AssertionError(
                    "Device exposes no full 4K recording size: ${vm.camera.videoSizes.joinToString()}"
                )
                vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                vm.setPreviewAspectRatio(
                    if (selectedSize.width * 3 == selectedSize.height * 4) {
                        PreviewAspectRatio.FOUR_THREE
                    } else {
                        PreviewAspectRatio.SIXTEEN_NINE
                    }
                )
                vm.setVideoResolution(selectedSize)
                vm.setFPS(25)
                vm.setDebugValidationLabel("qa_full_4k25")
            }
            awaitCondition(30_000L, "Full 4K selection did not settle") {
                vm.gpuRecording.videoWidth == selectedSize.width &&
                    vm.gpuRecording.videoHeight == selectedSize.height
            }
            awaitFreshCameraSession(scenario, vm, "FULL_4K25")
            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm, timeoutMs = 45_000L)
            SystemClock.sleep(3_000L)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm, timeoutMs = 45_000L)

            val master = awaitNewVerifiedMaster(masterDir, mastersBefore)
            Npv10FileReader(master).use { reader ->
                assertEquals(selectedSize.width, reader.header.width)
                assertEquals(selectedSize.height, reader.header.height)
                assertEquals(25, reader.header.fps)
                assertTrue("Full 4K source fell below 10-bit", reader.header.sourceBitDepth >= 10)
                assertEquals(10, reader.header.storageBitDepth)
                assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
                assertEquals("normal_sdr", reader.header.metadataValues["recordingmode"])

                val cadence = measureVerifiedContentCadence(master, reader)
                assertTrue(
                    "Full 4K master contains too few content frames: " +
                        "${cadence.contentFrameCount} content/${cadence.totalFrameCount} total",
                    cadence.contentFrameCount >= MIN_CONTENT_FRAMES
                )
                assertTrue(
                    "Full 4K camera cadence was not locked to 25 fps: ${cadence.measuredFps}",
                    cadence.measuredFps in 24.0..26.0
                )
                assertTrue(
                    "Full 4K Video maximum timestamp gap " +
                        "${cadence.maxContentGapNs / 1_000_000.0}ms indicates a missing frame",
                    cadence.maxContentGapNs <= MAX_PROCESSED_FRAME_GAP_NS
                )
            }
        }
    }

    @Test
    fun proFullFourKSelection_recordsNativeTenBitMasterAt25Fps() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)

            lateinit var selectedSize: Size
            scenario.onActivity {
                selectedSize = vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 4096 && size.height == 3072
                } ?: vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 3840 && size.height == 2160
                } ?: throw AssertionError(
                    "Device exposes no full 4K recording size: ${vm.camera.videoSizes.joinToString()}"
                )
                vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                vm.setPreviewAspectRatio(
                    if (selectedSize.width * 3 == selectedSize.height * 4) {
                        PreviewAspectRatio.FOUR_THREE
                    } else {
                        PreviewAspectRatio.SIXTEEN_NINE
                    }
                )
                vm.setVideoResolution(selectedSize)
                vm.setFPS(TARGET_RECORDING_FPS)
                vm.setVideoDetailProfile(VideoDetailProfile.FOCUSED_DETAIL)
                vm.setDebugValidationLabel("lq026_pro_full_4k25")
            }
            awaitCondition(30_000L, "Pro full 4K selection did not settle") {
                vm.gpuRecording.videoWidth == selectedSize.width &&
                    vm.gpuRecording.videoHeight == selectedSize.height
            }
            awaitFreshCameraSession(scenario, vm, "LQ026_PRO_FULL_4K25")
            scenario.onActivity { vm.startRecording() }
            awaitRecording(vm, timeoutMs = 45_000L)
            SystemClock.sleep(3_000L)
            scenario.onActivity { vm.stopRecording() }
            awaitNotRecording(vm, timeoutMs = 45_000L)

            val master = awaitNewVerifiedMaster(masterDir, mastersBefore)
            Npv10FileReader(master).use { reader ->
                assertEquals(selectedSize.width, reader.header.width)
                assertEquals(selectedSize.height, reader.header.height)
                assertEquals(TARGET_RECORDING_FPS, reader.header.fps)
                assertTrue("Pro full 4K source fell below 10-bit", reader.header.sourceBitDepth >= 10)
                assertEquals(10, reader.header.storageBitDepth)
                assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
                assertEquals("pro_sdr", reader.header.metadataValues["recordingmode"])

                val cadence = measureVerifiedContentCadence(master, reader)
                assertTrue(
                    "Pro full 4K master contains too few content frames: " +
                        "${cadence.contentFrameCount} content/${cadence.totalFrameCount} total",
                    cadence.contentFrameCount >= MIN_CONTENT_FRAMES
                )
                assertTrue(
                    "Pro full 4K camera cadence was not locked to 25 fps: ${cadence.measuredFps}",
                    cadence.measuredFps in 24.0..26.0
                )
                assertTrue(
                    "Pro full 4K maximum timestamp gap " +
                        "${cadence.maxContentGapNs / 1_000_000.0}ms indicates a missing frame",
                    cadence.maxContentGapNs <= MAX_PROCESSED_FRAME_GAP_NS
                )
            }
        }
    }

    /**
     * Device recovery/verification path: export the newest already-finalized private master through
     * the same CameraViewModel isolation, converter, audit, and MediaStore publication path used by
     * the settings UI. Keeping this separate from capture makes an export failure reproducible
     * without recording another multi-hundred-megabyte source clip.
     */
    @Test
    fun existingLatestNpv10Master_exportsHevcMain10ThroughProductionPath() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val publishedBefore = publishedNpv10MovCount(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)
            val clip = vm.listNpv10MastersForConversion()
                .maxByOrNull { it.file.lastModified() }
            assertNotNull("No finalized NPV10 master is available for export verification", clip)
            val latestClip = requireNotNull(clip)
            val capability = Npv10FileReader(latestClip.file).use { reader ->
                RecordDevelopHevcMovEncoder.strictCapabilityReport(
                    width = reader.header.width,
                    height = reader.header.height,
                    fps = reader.header.fps
                )
            }
            assumeTrue(
                "Latest finalized NPV10 master cannot be exported as strict HEVC Main10 on this " +
                    "device; hardware blocker: ${capability.rejectionMessage()}",
                capability.accepted
            )

            val displayName = vm.runWithExclusiveConversionResources {
                vm.convertNpv10MasterToMov(
                    latestClip,
                    Npv10MovConverter.OutputFormat.HEVC_MAIN10_420
                )
            }

            assertTrue(displayName.contains(Npv10MovConverter.OutputFormat.HEVC_MAIN10_420.fileSuffix))
            assertTrue(
                "Production HEVC export was not published",
                publishedNpv10MovCount(context) > publishedBefore
            )
            assertTrue("Export removed the private source master", latestClip.file.isFile)
        }
    }

    @Test
    fun normalProNdrAndRaw_recordCameraPacketsInBackgroundWithoutEightBitFallback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity ->
                vm = activity.vm
            }
            awaitCameraStreaming(vm)
            scenario.onActivity {
                val preferred = vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 1920 && size.height == 1080
                } ?: vm.camera.videoSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
                preferred?.let(vm::setVideoResolution)
                vm.setFPS(25)
            }
            awaitCameraStreaming(vm)

            recordProcessedMode(
                scenario,
                vm,
                "e2e_normal_sdr",
                "NORMAL_SDR",
                Npv10MovConverter.OutputFormat.R210_444
            ) {
                vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
            }
            recordProcessedMode(
                scenario,
                vm,
                "e2e_pro_sdr",
                "PRO_SDR",
                Npv10MovConverter.OutputFormat.V210_422
            ) {
                vm.setSdrQualityIntent(SdrQualityIntent.PRO)
                configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
            }
            recordSensorLinearNdrMode(scenario, vm)
            recordRawMode(scenario, vm)
        }
    }

    /**
     * Keeps RAW recording evidence independent from the sensor-linear HDR encoder path. A failure
     * in that separate path must not prevent CP-153/CP-160 from exercising, reopening, CRC-checking,
     * and measuring the native RAW packet stream produced by the physical camera.
     */
    @Test
    fun rawOnly_recordsNativePacketsAtSelected25FpsWithoutFallback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            awaitCameraStreaming(vm)
            scenario.onActivity {
                val preferred = vm.camera.videoSizes.firstOrNull { size ->
                    size.width == 4096 && size.height == 3072
                } ?: vm.camera.videoSizes.maxByOrNull { size ->
                    size.width.toLong() * size.height.toLong()
                } ?: throw AssertionError("Device exposes no RAW recording size")
                vm.setPreviewAspectRatio(
                    if (preferred.width * 3 == preferred.height * 4) {
                        PreviewAspectRatio.FOUR_THREE
                    } else {
                        PreviewAspectRatio.SIXTEEN_NINE
                    }
                )
                vm.setVideoResolution(preferred)
                vm.setFPS(TARGET_RECORDING_FPS)
            }
            recordRawMode(scenario, vm)
        }
    }

    @Test
    fun shutterRapidCancelAndRestart_stopCaptureWithoutWaitingForMovConversion() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity ->
                vm = activity.vm
            }
            var recordingMayBeActive = false
            var primaryFailure: Throwable? = null
            try {
                awaitCameraStreaming(vm)
                scenario.onActivity {
                    vm.setFPS(25)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                    vm.setDebugValidationLabel("e2e_shutter_lifecycle")
                }
                awaitProcessedModeReady(vm, "NORMAL_SDR")
                awaitFreshCameraSession(scenario, vm, "SHUTTER_LIFECYCLE")

                recordingMayBeActive = true
                scenario.onActivity { vm.startRecording() }
                awaitRecording(vm, timeoutMs = 10_000L)
                SystemClock.sleep(MIN_FIRST_CLIP_RECORDING_MS)

                val overlapRequestMs = SystemClock.elapsedRealtime()
                val dispatchStartedMs = SystemClock.elapsedRealtime()
                var synchronouslyReservedStopState: GpuRecordingState? = null
                scenario.onActivity {
                    // Intentionally do both operations in one Main-thread transaction. stopRecording()
                    // reserves the authoritative stop generation synchronously and dispatches its
                    // asynchronous stop/finalize owner; toggleRecording() must therefore retain one
                    // restart against that in-flight generation. Never wait for Idle here.
                    vm.stopRecording()
                    synchronouslyReservedStopState = vm.gpuRecordState.value
                    vm.toggleRecording()
                }
                val dispatchLatencyMs = SystemClock.elapsedRealtime() - dispatchStartedMs
                assertTrue(
                    "Stop plus overlapping restart dispatch blocked Main for ${dispatchLatencyMs}ms",
                    dispatchLatencyMs <= MAX_OVERLAP_DISPATCH_MS
                )
                assertTrue(
                    "stopRecording() did not synchronously publish its reserved stop generation: " +
                        synchronouslyReservedStopState,
                    synchronouslyReservedStopState is GpuRecordingState.Stopping ||
                        synchronouslyReservedStopState is GpuRecordingState.Finalizing
                )

                awaitCondition(
                    MAX_OVERLAP_RECOVERY_MS,
                    "Overlapping stop/finalize did not replay the retained recording start; " +
                        "state=${vm.gpuRecordState.value}"
                ) {
                    when (val state = vm.gpuRecordState.value) {
                        is GpuRecordingState.Error -> throw AssertionError(state.message)
                        is GpuRecordingState.Recording -> true
                        else -> false
                    }
                }
                val recoveryMs = SystemClock.elapsedRealtime() - overlapRequestMs
                assertTrue(
                    "Stop/finalize-to-restart recovery exceeded ${MAX_OVERLAP_RECOVERY_MS}ms: " +
                        "${recoveryMs}ms state=${vm.gpuRecordState.value}",
                    recoveryMs in 0..MAX_OVERLAP_RECOVERY_MS
                )

                awaitCondition(
                    MASTER_FINALIZATION_TIMEOUT_MS,
                    "First overlap clip was not durably verified before replacement recording"
                ) {
                    newVerifiedMasterNames(masterDir, mastersBefore).size >= 1
                }
                assertEquals(
                    "One stop generation must publish exactly one master before the replacement stop",
                    1,
                    newVerifiedMasterNames(masterDir, mastersBefore).size
                )

                SystemClock.sleep(MIN_RESTARTED_CLIP_RECORDING_MS)
                scenario.onActivity { vm.stopRecording() }
                awaitRecordingFinalized(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                recordingMayBeActive = false
                awaitCondition(
                    MASTER_FINALIZATION_TIMEOUT_MS,
                    "Replacement clip did not finish durable verified publication"
                ) {
                    newVerifiedMasterNames(masterDir, mastersBefore).size >= 2
                }
                SystemClock.sleep(POST_FINALIZATION_SETTLE_MS)
                assertEquals(
                    "Two recording generations must produce exactly two verified masters",
                    2,
                    newVerifiedMasterNames(masterDir, mastersBefore).size
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (recordingMayBeActive || vm.gpuRecordState.value.isRecordingLifecycleActive()) {
                    val cleanupFailure = runCatching {
                        scenario.onActivity { vm.stopRecording() }
                        awaitRecordingCleanup(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                    }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
            awaitCameraStreaming(vm, timeoutMs = 10_000L)
        }
    }

    /** CP-166: pause is a real capture boundary, not recorded programme time. */
    @Test
    fun recordingPauseAndResume_freezesDurationAndPublishesOneTenBitMaster() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            lateinit var selectedSize: Size
            scenario.onActivity { activity -> vm = activity.vm }
            var recordingMayBeActive = false
            var primaryFailure: Throwable? = null
            try {
                awaitCameraStreaming(vm)
                scenario.onActivity {
                    selectedSize = selectFullFourKSize(vm)
                    vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
                    configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                    vm.recording.audioSource = AudioSrc.UNPROCESSED
                    vm.setPreviewAspectRatio(aspectFor(selectedSize))
                    vm.setVideoResolution(selectedSize)
                    vm.setFPS(TARGET_RECORDING_FPS)
                    vm.setDebugValidationLabel("lq029_cp166_pause_resume")
                }
                awaitProcessedFourKSelection(vm, selectedSize, "CP-166")
                awaitFreshCameraSession(scenario, vm, "CP-166")

                recordingMayBeActive = true
                scenario.onActivity { vm.startRecording() }
                awaitRecording(vm, timeoutMs = 45_000L)
                awaitCondition(5_000L, "CP-166 active duration never advanced") {
                    vm.recDuration.value >= MIN_ACTIVE_DURATION_BEFORE_PAUSE_MS
                }

                scenario.onActivity { vm.pauseRecording() }
                awaitCondition(10_000L, "CP-166 recorder did not enter Paused") {
                    vm.gpuRecordState.value is GpuRecordingState.Paused
                }
                val durationAtPauseMs = vm.recDuration.value
                SystemClock.sleep(PAUSE_PROOF_WINDOW_MS)
                val durationAfterPauseWindowMs = vm.recDuration.value
                assertTrue(
                    "CP-166 duration advanced during pause: before=$durationAtPauseMs " +
                        "after=$durationAfterPauseWindowMs",
                    durationAfterPauseWindowMs in durationAtPauseMs..(
                        durationAtPauseMs + MAX_PAUSED_DURATION_DRIFT_MS
                    )
                )

                scenario.onActivity { vm.resumeRecording() }
                awaitRecording(vm, timeoutMs = 15_000L)
                val durationAtResumeMs = vm.recDuration.value
                assertTrue(
                    "CP-166 resume counted paused wall time: paused=$durationAtPauseMs " +
                        "resumed=$durationAtResumeMs",
                    durationAtResumeMs in durationAtPauseMs..(
                        durationAtPauseMs + MAX_RESUME_DURATION_DRIFT_MS
                    )
                )
                awaitCondition(5_000L, "CP-166 active duration did not continue after resume") {
                    vm.recDuration.value >= durationAtPauseMs + MIN_ACTIVE_DURATION_AFTER_RESUME_MS
                }

                scenario.onActivity { vm.stopRecording() }
                awaitRecordingFinalized(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                recordingMayBeActive = false
                val master = awaitNewVerifiedMaster(masterDir, mastersBefore)
                SystemClock.sleep(POST_FINALIZATION_SETTLE_MS)
                assertEquals(
                    "CP-166 pause/resume must finalize exactly one verified master",
                    1,
                    newVerifiedMasterNames(masterDir, mastersBefore).size
                )
                var videoFrameCount = 0L
                val videoDurationUs = Npv10FileReader(master).use { reader ->
                    assertEquals(selectedSize.width, reader.header.width)
                    assertEquals(selectedSize.height, reader.header.height)
                    assertEquals(TARGET_RECORDING_FPS, reader.header.fps)
                    assertTrue("CP-166 source fell below 10-bit", reader.header.sourceBitDepth >= 10)
                    assertEquals(10, reader.header.storageBitDepth)
                    assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
                    assertEquals(
                        "CP-166 master did not declare its required AAC bundle",
                        "true",
                        reader.header.metadataValues["audiostoredinnpv10bundle"]
                    )
                    videoFrameCount = reader.countFramesForPlayback().toLong()
                    Npv10ExportCadencePolicy.endPresentationTimeUs(
                        frameCount = videoFrameCount,
                        fps = reader.header.fps
                    )
                }
                assertTrue(
                    "CP-166 synchronized AAC sidecar was not published",
                    Npv10AudioSidecarPolicy.sidecarFor(master).isFile
                )
                var audioPacketCount = 0L
                val audioAudit = Npv10AudioSidecarReader(master).use { reader ->
                    while (reader.readNextPacket() != null) audioPacketCount++
                    reader.requireAudit()
                }
                assertTrue("CP-166 AAC sidecar has no packets", audioPacketCount > 0L)
                assertTrue("CP-166 AAC timestamps are not monotonic", audioAudit.monotonicPts)
                assertTrue("CP-166 AAC packet CRC validation failed", audioAudit.crcValidated)
                val audioVideoAudit = Npv10AudioVideoDurationPolicy.audit(
                    videoFrameCount = videoFrameCount,
                    videoFps = TARGET_RECORDING_FPS,
                    audioDurationUs = audioAudit.finalPresentationTimeUs
                )
                assertEquals(videoDurationUs, audioVideoAudit.videoDurationUs)
                assertTrue(
                    "CP-166 audio/video duration delta exceeded 500 ms: " +
                        "audio=${audioAudit.finalPresentationTimeUs} video=$videoDurationUs " +
                        "delta=${audioVideoAudit.absoluteDeltaUs}",
                    audioVideoAudit.passed
                )
                Log.i(
                    "Lq029Evidence",
                    "CP-166 pause/resume audioPackets=$audioPacketCount " +
                        "audioEndUs=${audioAudit.finalPresentationTimeUs} " +
                        "videoEndUs=$videoDurationUs deltaUs=${audioVideoAudit.absoluteDeltaUs} " +
                        "monotonic=${audioAudit.monotonicPts} crc=${audioAudit.crcValidated}"
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (recordingMayBeActive || vm.gpuRecordState.value.isRecordingLifecycleActive()) {
                    val cleanupFailure = runCatching {
                        scenario.onActivity { vm.stopRecording() }
                        awaitRecordingCleanup(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                    }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
            awaitCameraStreaming(vm, timeoutMs = 15_000L)
        }
    }

    /** CP-201/CP-213: repeated lifecycle + RAW boundary + 4K recording ownership stress. */
    @Test
    fun repeatedForegroundRawBoundaryAndRecordingStress_recoversWithoutStaleOwnership() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            lateinit var selectedSize: Size
            scenario.onActivity { activity ->
                vm = activity.vm
                selectedSize = selectFullFourKSize(vm)
            }
            var recordingMayBeActive = false
            var primaryFailure: Throwable? = null
            try {
                awaitCameraStreaming(vm)
                repeat(LIFECYCLE_STRESS_CYCLES) { cycle ->
                    scenario.moveToState(Lifecycle.State.CREATED)
                    SystemClock.sleep(BACKGROUND_RELEASE_WINDOW_MS)
                    scenario.onActivity { vm.camera.resetPreviewActivityTracking() }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    awaitCondition(15_000L, "CP-201 cycle=$cycle did not restore a fresh preview") {
                        vm.cameraState.value == com.neoproc.neoproccamx.camera.CameraState.Streaming &&
                            !vm.camera.sessionRefreshPending.get() &&
                            vm.camera.hasRecentPreviewActivity()
                    }

                    scenario.onActivity {
                        vm.setVideoHdrMode("OFF")
                        vm.setLogEnabled(false)
                        vm.setVideoColorSpace("Rec.2020")
                        vm.setColorProfile("Natural")
                        vm.setCinemaVideoMode(CinemaVideoMode.RAW)
                        vm.setRawEnabled(true)
                    }
                    awaitCondition(30_000L, "CP-213 cycle=$cycle RAW mode did not settle") {
                        vm.cinemaVideoMode.value == CinemaVideoMode.RAW && vm.rawOnFlow.value
                    }
                    awaitCameraStreaming(vm)

                    scenario.onActivity {
                        vm.setSdrQualityIntent(
                            if (cycle % 2 == 0) SdrQualityIntent.NORMAL else SdrQualityIntent.PRO
                        )
                        configureProcessed(vm, hdrMode = "OFF", colorSpace = "Rec.709")
                        vm.setPreviewAspectRatio(aspectFor(selectedSize))
                        vm.setVideoResolution(selectedSize)
                        vm.setFPS(TARGET_RECORDING_FPS)
                        vm.setDebugValidationLabel("lq029_cp213_cycle_${cycle + 1}")
                    }
                    awaitProcessedFourKSelection(vm, selectedSize, "CP-213 cycle=$cycle")
                    awaitFreshCameraSession(scenario, vm, "CP-213-$cycle")

                    recordingMayBeActive = true
                    scenario.onActivity { vm.startRecording() }
                    awaitRecording(vm, timeoutMs = 45_000L)
                    SystemClock.sleep(STRESS_RECORDING_WINDOW_MS)
                    scenario.onActivity { vm.stopRecording() }
                    awaitRecordingFinalized(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                    recordingMayBeActive = false
                    awaitCondition(
                        MASTER_FINALIZATION_TIMEOUT_MS,
                        "CP-213 cycle=$cycle did not publish its verified master"
                    ) {
                        newVerifiedMasterNames(masterDir, mastersBefore).size >= cycle + 1
                    }
                    assertEquals(
                        "CP-213 cycle=$cycle published an unexpected recording generation",
                        cycle + 1,
                        newVerifiedMasterNames(masterDir, mastersBefore).size
                    )
                    awaitCameraStreaming(vm, timeoutMs = 15_000L)
                }

                newVerifiedMasterNames(masterDir, mastersBefore).forEach { name ->
                    Npv10FileReader(File(masterDir, name)).use { reader ->
                        assertEquals(selectedSize.width, reader.header.width)
                        assertEquals(selectedSize.height, reader.header.height)
                        assertEquals(TARGET_RECORDING_FPS, reader.header.fps)
                        assertTrue("CP-213 $name fell below 10-bit", reader.header.sourceBitDepth >= 10)
                        assertEquals(10, reader.header.storageBitDepth)
                        assertEquals(Npv10PayloadFormat.P010_LE, reader.header.payloadFormat)
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (recordingMayBeActive || vm.gpuRecordState.value.isRecordingLifecycleActive()) {
                    val cleanupFailure = runCatching {
                        scenario.onActivity { vm.stopRecording() }
                        awaitRecordingCleanup(vm, MASTER_FINALIZATION_TIMEOUT_MS)
                    }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }
    }

    private fun selectFullFourKSize(vm: CameraViewModel): Size =
        vm.camera.videoSizes.firstOrNull { size -> size.width == 4096 && size.height == 3072 }
            ?: vm.camera.videoSizes.firstOrNull { size -> size.width == 3840 && size.height == 2160 }
            ?: throw AssertionError(
                "Device exposes no full 4K recording size: ${vm.camera.videoSizes.joinToString()}"
            )

    private fun aspectFor(size: Size): PreviewAspectRatio =
        if (size.width * 3 == size.height * 4) {
            PreviewAspectRatio.FOUR_THREE
        } else {
            PreviewAspectRatio.SIXTEEN_NINE
        }

    private fun awaitProcessedFourKSelection(vm: CameraViewModel, size: Size, label: String) {
        awaitProcessedModeReady(vm, "NORMAL_SDR")
        awaitCondition(30_000L, "$label full 4K selection did not settle") {
            vm.gpuRecording.videoWidth == size.width &&
                vm.gpuRecording.videoHeight == size.height &&
                vm.recording.fps == TARGET_RECORDING_FPS
        }
    }

    private fun configureProcessed(vm: CameraViewModel, hdrMode: String, colorSpace: String) {
        vm.setMode(AppMode.VIDEO)
        vm.setRawEnabled(false)
        vm.setLogEnabled(false)
        vm.setCinemaVideoMode(CinemaVideoMode.PRO)
        vm.setVideoColorSpace(colorSpace)
        vm.setColorProfile(if (hdrMode == "NDR10+") "NDR10+" else "Natural")
        vm.setVideoHdrMode(hdrMode)
        vm.setTenBitRecordingEnabled(true)
        vm.setLutEnabled(false)
    }

    private fun recordProcessedMode(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        label: String,
        expectedRecordingMode: String,
        outputFormat: Npv10MovConverter.OutputFormat,
        configure: () -> Unit
    ) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val publishedBefore = publishedNpv10MovCount(context)
        val masterDir = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val mastersBefore = masterDir.listFiles().orEmpty().map(File::getName).toSet()
        scenario.onActivity {
            configure()
            vm.setDebugValidationLabel(label)
        }
        awaitProcessedModeReady(vm, expectedRecordingMode)
        awaitFreshCameraSession(scenario, vm, label)
        scenario.onActivity { vm.startRecording() }
        awaitRecording(vm)
        val master = awaitNewMaster(masterDir, mastersBefore)
        Npv10FileReader(master).use { reader ->
            val header = reader.header
            assertTrue("$label source fell below 10-bit", header.sourceBitDepth >= 10)
            assertEquals(10, header.storageBitDepth)
            assertEquals(Npv10PayloadFormat.P010_LE, header.payloadFormat)
            assertEquals(Npv10PacketCodec.PACKED_P010_10_LE_V1, header.packetCodec)
            assertEquals(
                expectedRecordingMode.lowercase(Locale.US),
                header.metadataValues["recordingmode"]
            )
            assertEquals("true", header.metadataValues["packetstreamonly"])
            assertEquals("false", header.metadataValues["individualframefiles"])
            assertEquals("false", header.metadataValues["mediacodecused"])
        }
        proveBackgroundContinuation(scenario, vm, label)
        scenario.onActivity { vm.stopRecording() }
        awaitNotRecording(vm)
        var finalizedClip: Npv10MasterClip? = null
        awaitCondition(30_000L, "$label private master did not finish durable finalization") {
            finalizedClip = vm.listNpv10MastersForConversion().firstOrNull { clip ->
                clip.file.absolutePath == master.absolutePath && clip.bytes > 0L
            }
            finalizedClip != null
        }
        var displayName: String? = null
        awaitCondition(30_000L, "$label recording stop did not release conversion resources") {
            try {
                displayName = vm.runWithExclusiveConversionResources {
                    vm.convertNpv10MasterToMov(requireNotNull(finalizedClip), outputFormat)
                }
                true
            } catch (failure: IllegalStateException) {
                if (failure.message == "Stop recording before starting conversion") {
                    false
                } else {
                    throw failure
                }
            }
        }
        assertTrue(
            "$label export did not use ${outputFormat.fileSuffix}",
            requireNotNull(displayName).contains(outputFormat.fileSuffix)
        )
        assertTrue("$label conversion removed the private master", master.isFile && master.length() > 0L)
        awaitCondition(15_000L, "$label ${outputFormat.sampleEntry} MOV was not published after conversion") {
            publishedNpv10MovCount(context) > publishedBefore
        }
        awaitCameraStreaming(vm)
    }

    private fun recordRawMode(scenario: ActivityScenario<MainActivity>, vm: CameraViewModel) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val clipsBefore = vm.listRawClipsForConversion().map { it.name }.toSet()
        scenario.onActivity {
            vm.setDebugValidationLabel("e2e_raw")
            vm.setMode(AppMode.VIDEO)
            // Match the user-facing RAW preset transaction: retire an active HDR/XDR transport
            // before selecting the mutually exclusive sensor-original RAW master. Calling the RAW
            // setters first is correctly rejected by the production invariant when a persisted HDR
            // profile is active, leaving the test in PRO and preventing RAW from being exercised.
            vm.setVideoHdrMode("OFF")
            vm.setLogEnabled(false)
            vm.setVideoColorSpace("Rec.2020")
            vm.setColorProfile("Natural")
            vm.setCinemaVideoMode(CinemaVideoMode.RAW)
            vm.setRawEnabled(true)
            vm.setRawVideoCompressionMode(RawCompressionMode.LOSSLESS)
        }
        awaitCondition(30_000L, "RAW mode selection did not settle") {
            vm.uiMode.value == AppMode.VIDEO &&
                vm.cinemaVideoMode.value == CinemaVideoMode.RAW &&
                vm.rawOnFlow.value &&
                vm.videoHdrMode.value == "OFF"
        }
        awaitFreshCameraSession(scenario, vm, "RAW")
        scenario.onActivity { vm.startRecording() }
        awaitRecording(vm, timeoutMs = 45_000L)
        SystemClock.sleep(RAW_CADENCE_CAPTURE_MS)
        proveBackgroundContinuation(scenario, vm, "RAW")
        scenario.onActivity { vm.stopRecording() }
        awaitNotRecording(vm, timeoutMs = 45_000L)
        var newClip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef? = null
        awaitCondition(30_000L, "RAW NPRAW stream and develop metadata were not published") {
            newClip = vm.listRawClipsForConversion().firstOrNull { it.name !in clipsBefore }
            newClip != null
        }
        val clip = requireNotNull(newClip)
        assertTrue("RAW packet stream is empty", clip.streamBytes > 0L)
        val firstFrame = context.contentResolver.openInputStream(clip.streamUri)?.buffered()?.use {
            NprawFrameCodec.readFrame(it)
        }
        assertNotNull("RAW packet stream contains no decodable frame", firstFrame)
        val metadata = context.contentResolver.openInputStream(clip.metaUri)?.use {
            RawDevelopMetadataSidecar.decode(it.readBytes())
        }
        assertNotNull("RAW develop metadata is missing", metadata)
        val requiredMetadata = requireNotNull(metadata)
        assertTrue("RAW storage depth fell below 10-bit", requiredMetadata.storageBitDepth >= 10)
        assertEquals(
            "RAW metadata did not retain selected 25 fps",
            TARGET_RECORDING_FPS,
            requiredMetadata.fps
        )
        val cadence = inspectRawPacketCadence(context, clip)
        assertTrue(
            "RAW stream contains too few physical frames: ${cadence.frameCount}",
            cadence.frameCount >= MIN_RAW_CONTENT_FRAMES
        )
        assertTrue(
            "RAW stream cadence was not stable 25 fps: ${cadence.measuredFps}",
            cadence.measuredFps in 24.0..26.0
        )
        assertTrue(
            "RAW maximum timestamp gap ${cadence.maxGapNs / 1_000_000.0}ms indicates a missing frame",
            cadence.maxGapNs <= MAX_RAW_FRAME_GAP_NS
        )
    }

    private data class RawPacketCadence(
        val frameCount: Int,
        val measuredFps: Double,
        val maxGapNs: Long
    )

    private fun inspectRawPacketCadence(
        context: android.content.Context,
        clip: com.neoproc.neoproccamx.recording.RawClipMediaStoreDiscovery.RawClipRef
    ): RawPacketCadence {
        var frameCount = 0
        var firstTimestampNs = 0L
        var previousTimestampNs = 0L
        var lastTimestampNs = 0L
        var maxGapNs = 0L
        requireNotNull(context.contentResolver.openInputStream(clip.streamUri)) {
            "RAW packet stream cannot be reopened: ${clip.streamUri}"
        }.buffered().use { input ->
            while (true) {
                val packet = NprawFrameCodec.readRawFrameBytes(input) ?: break
                NprawFrameCodec.validateEncodedFrameContainer(packet)
                check(packet.size >= NPRAW_TIMESTAMP_OFFSET_BYTES + Long.SIZE_BYTES) {
                    "RAW packet header is truncated: ${packet.size} bytes"
                }
                val timestampNs = ByteBuffer.wrap(packet)
                    .order(ByteOrder.BIG_ENDIAN)
                    .getLong(NPRAW_TIMESTAMP_OFFSET_BYTES)
                assertTrue("RAW frame has a non-positive timestamp", timestampNs > 0L)
                if (frameCount == 0) {
                    firstTimestampNs = timestampNs
                } else {
                    val gapNs = timestampNs - previousTimestampNs
                    assertTrue(
                        "RAW timestamps are not strictly increasing at frame $frameCount",
                        gapNs > 0L
                    )
                    if (gapNs > maxGapNs) maxGapNs = gapNs
                }
                previousTimestampNs = timestampNs
                lastTimestampNs = timestampNs
                frameCount += 1
            }
        }
        check(frameCount >= 2 && lastTimestampNs > firstTimestampNs) {
            "RAW packet stream has insufficient cadence evidence: frames=$frameCount"
        }
        val durationNs = lastTimestampNs - firstTimestampNs
        return RawPacketCadence(
            frameCount = frameCount,
            measuredFps = (frameCount - 1).toDouble() * 1_000_000_000.0 / durationNs.toDouble(),
            maxGapNs = maxGapNs
        )
    }

    private fun recordSensorLinearNdrMode(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel
    ) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val label = "e2e_ndr10_raw_engine"
        scenario.onActivity {
            vm.setSdrQualityIntent(SdrQualityIntent.PRO)
            configureProcessed(vm, hdrMode = "NDR10+", colorSpace = "Rec.2020")
            vm.setDebugValidationLabel(label)
        }
        awaitProcessedModeReady(vm, "NDR10+")
        assertTrue(
            "NDR incorrectly selected the vendor Camera2 P010/NPV10 master",
            !vm.usesNpv10CapturePipelineForCurrentMode()
        )
        awaitFreshCameraSession(scenario, vm, "NDR10_RAW_ENGINE")
        scenario.onActivity { vm.startRecording() }
        awaitRecording(vm, timeoutMs = 45_000L)
        assertEquals(
            "NDR did not activate the project-owned GPU recording transport",
            "AHB_GPU_COMPATIBILITY",
            vm.activeRecordingTransportHudLabel()
        )
        SystemClock.sleep(2_000L)
        proveBackgroundContinuation(scenario, vm, "NDR10_RAW_ENGINE")
        scenario.onActivity { vm.stopRecording() }
        awaitRecordingFinalized(vm, timeoutMs = 45_000L)
        val outputPath = requireNotNull(vm.gpuRecording.lastOutputPath) {
            "NDR Main10 recording produced no finalized output path"
        }
        inspectSensorLinearNdrMain10Clip(context, outputPath, label)
        awaitCameraStreaming(vm)
    }

    private fun inspectSensorLinearNdrMain10Clip(
        context: android.content.Context,
        outputPath: String,
        validationLabel: String
    ) {
        val extractor = MediaExtractor()
        val uri = outputPath.takeIf { it.startsWith("content://") }?.let(Uri::parse)
        var descriptor: android.os.ParcelFileDescriptor? = null
        try {
            if (uri != null) {
                val openedDescriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
                    "NDR MediaStore output cannot be opened: $uri"
                }
                descriptor = openedDescriptor
                extractor.setDataSource(openedDescriptor.fileDescriptor)
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
                requireNotNull(context.contentResolver.query(uri, projection, null, null, null)) {
                    "NDR MediaStore row query returned no cursor: $uri"
                }.use { cursor ->
                    assertTrue("NDR MediaStore row is missing", cursor.moveToFirst())
                    val name = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    )
                    val size = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    )
                    val pending = cursor.getInt(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    )
                    val path = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    )
                    assertTrue("NDR validation label missing from output name: $name", name.contains(validationLabel))
                    assertTrue("NDR Main10 output is empty", size > 0L)
                    assertEquals("NDR output remained pending", 0, pending)
                    assertTrue("NDR output escaped the camera library: $path", path.trimEnd('/') == "DCIM/CamX")
                }
            } else {
                val file = File(outputPath)
                assertTrue("NDR output file is missing: $outputPath", file.isFile && file.length() > 0L)
                assertTrue(
                    "NDR validation label missing from output name: ${file.name}",
                    file.name.contains(validationLabel)
                )
                extractor.setDataSource(outputPath)
            }

            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw AssertionError("NDR output contains no video track")
            val format = extractor.getTrackFormat(videoTrack)
            assertEquals("video/hevc", format.getString(MediaFormat.KEY_MIME))
            val profile = format.getInteger(MediaFormat.KEY_PROFILE)
            assertTrue(
                "NDR encoder profile fell below Main10: $profile",
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            )
            assertEquals(
                "NDR output lost BT.2020 primaries",
                MediaFormat.COLOR_STANDARD_BT2020,
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            )
            assertEquals(
                "NDR output lost ST.2084/PQ transfer",
                MediaFormat.COLOR_TRANSFER_ST2084,
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            )
            assertTrue(
                "NDR output does not declare its selected frame rate",
                format.containsKey(MediaFormat.KEY_FRAME_RATE)
            )
            assertEquals(
                "NDR output did not retain selected 25 fps metadata",
                TARGET_RECORDING_FPS,
                format.getInteger(MediaFormat.KEY_FRAME_RATE)
            )

            extractor.selectTrack(videoTrack)
            var frames = 0
            var firstPtsUs = -1L
            var lastPtsUs = -1L
            var previousPtsUs = -1L
            var maxGapUs = 0L
            while (extractor.sampleTime >= 0L) {
                val ptsUs = extractor.sampleTime
                if (firstPtsUs < 0L) firstPtsUs = ptsUs
                if (previousPtsUs >= 0L) {
                    val gapUs = ptsUs - previousPtsUs
                    assertTrue(
                        "NDR PTS is not strictly increasing at frame=$frames: " +
                            "previous=$previousPtsUs current=$ptsUs",
                        gapUs > 0L
                    )
                    if (gapUs > maxGapUs) maxGapUs = gapUs
                }
                previousPtsUs = ptsUs
                lastPtsUs = ptsUs
                frames += 1
                if (!extractor.advance()) break
            }
            assertTrue("NDR Main10 output contains too few frames: $frames", frames >= 40)
            val durationSeconds = (lastPtsUs - firstPtsUs).toDouble() / 1_000_000.0
            assertTrue("NDR Main10 output has no positive media duration", durationSeconds > 0.0)
            val measuredFps = (frames - 1).toDouble() / durationSeconds
            assertTrue(
                "NDR Main10 cadence was not stable 25 fps: frames=$frames fps=$measuredFps",
                measuredFps in 24.0..26.0
            )
            assertTrue(
                "NDR Main10 maximum PTS gap ${maxGapUs}us indicates a dropped frame",
                maxGapUs <= MAX_NDR_FRAME_GAP_US
            )
        } finally {
            extractor.release()
            descriptor?.close()
        }
    }

    private fun proveBackgroundContinuation(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        label: String
    ) {
        val durationBefore = vm.recDuration.value
        scenario.moveToState(Lifecycle.State.STARTED)
        SystemClock.sleep(1_000L)
        assertTrue("$label recording stopped in background: ${vm.gpuRecordState.value}",
            vm.gpuRecordState.value is GpuRecordingState.Recording)
        assertTrue("$label background duration did not advance",
            vm.recDuration.value > durationBefore)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun awaitCameraStreaming(vm: CameraViewModel, timeoutMs: Long = 30_000L) =
        awaitCondition(timeoutMs, "Camera did not return to streaming: ${vm.cameraState.value}") {
            vm.cameraState.value == com.neoproc.neoproccamx.camera.CameraState.Streaming
        }

    private fun awaitProcessedModeReady(vm: CameraViewModel, expectedRecordingMode: String) {
        val expectedHdrMode = if (expectedRecordingMode == "NDR10+") "NDR10+" else "OFF"
        val expectedColorSpace = if (expectedRecordingMode == "NDR10+") "Rec.2020" else "Rec.709"
        val expectedColorScience = if (expectedRecordingMode == "NDR10+") "NDR10+" else "Natural"
        awaitCondition(30_000L, "$expectedRecordingMode mode selection did not settle") {
            vm.uiMode.value == AppMode.VIDEO &&
                vm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                !vm.rawOnFlow.value &&
                !vm.logEnabledFlow.value &&
                vm.videoHdrMode.value == expectedHdrMode &&
                vm.videoColorSpace.value == expectedColorSpace &&
                vm.colorProfileFlow.value == expectedColorScience &&
                vm.uncompressedCinemaPipelineEnabled.value
        }
    }

    private fun awaitFreshCameraSession(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel,
        label: String
    ) {
        awaitCameraStreaming(vm)
        awaitCondition(30_000L, "$label previous camera session rebuild did not settle") {
            !vm.camera.sessionRefreshPending.get()
        }
        val refreshed = CountDownLatch(1)
        scenario.onActivity {
            vm.camera.resetPreviewActivityTracking()
            vm.camera.refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true
            ) { refreshed.countDown() }
        }
        assertTrue(
            "$label camera session rebuild did not complete",
            refreshed.await(30L, TimeUnit.SECONDS)
        )
        awaitCondition(10_000L, "$label camera session produced no fresh preview data") {
            vm.cameraState.value == com.neoproc.neoproccamx.camera.CameraState.Streaming &&
                vm.camera.hasRecentPreviewActivity()
        }
    }

    private fun awaitRecording(vm: CameraViewModel, timeoutMs: Long = 30_000L) =
        awaitCondition(timeoutMs, "Recording did not start: ${vm.gpuRecordState.value}") {
            // CameraViewModel keeps the public state at Starting until the first camera packet has
            // been persisted. Waiting for duration > 0 adds a full timer tick to shutter latency.
            vm.gpuRecordState.value is GpuRecordingState.Recording
        }

    private fun awaitNotRecording(vm: CameraViewModel, timeoutMs: Long = 120_000L) =
        awaitCondition(timeoutMs, "Recording did not stop: ${vm.gpuRecordState.value}") {
            when (vm.gpuRecordState.value) {
                is GpuRecordingState.Starting,
                is GpuRecordingState.Recording,
                is GpuRecordingState.Paused,
                is GpuRecordingState.Stopping,
                is GpuRecordingState.Finalizing -> false
                is GpuRecordingState.Error -> throw AssertionError(
                    (vm.gpuRecordState.value as GpuRecordingState.Error).message
                )
                else -> true
            }
        }

    private fun awaitNewMaster(directory: File, existing: Set<String>): File {
        var found: File? = null
        awaitCondition(15_000L, "NPV10 private master header was not initialized") {
            found = directory.listFiles().orEmpty().firstOrNull { candidate ->
                candidate.name !in existing && runCatching {
                    Npv10FileReader(candidate).use { reader ->
                        reader.header.width > 0 && reader.header.height > 0
                    }
                }.getOrDefault(false)
            }
            found != null
        }
        return requireNotNull(found)
    }

    private fun awaitNewVerifiedMaster(directory: File, existing: Set<String>): File {
        var found: File? = null
        awaitCondition(30_000L, "NPV10 private master did not finish durable publication") {
            found = directory.listFiles().orEmpty().firstOrNull { candidate ->
                candidate.name !in existing &&
                    candidate.extension.equals("npv10", ignoreCase = true) &&
                    Npv10VerifiedMasterPublication.isVerified(candidate)
            }
            found != null
        }
        return requireNotNull(found)
    }

    private fun newVerifiedMasterNames(directory: File, existing: Set<String>): Set<String> =
        directory.listFiles().orEmpty()
            .asSequence()
            .filter { candidate -> candidate.name !in existing }
            .filter { candidate -> candidate.extension.equals("npv10", ignoreCase = true) }
            .filter { candidate ->
                runCatching { Npv10VerifiedMasterPublication.isVerified(candidate) }.getOrDefault(false)
            }
            .map(File::getName)
            .toSet()

    private fun awaitRecordingFinalized(vm: CameraViewModel, timeoutMs: Long) =
        awaitCondition(timeoutMs, "Recording did not finish durable finalization: ${vm.gpuRecordState.value}") {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                is GpuRecordingState.Idle,
                is GpuRecordingState.Ready ->
                    vm.activeRecordingTransportHudLabel() == null &&
                        !vm.isRecordingReadyForDebugClipTiming()
                else -> false
            }
        }

    private fun awaitRecordingCleanup(vm: CameraViewModel, timeoutMs: Long) =
        awaitCondition(timeoutMs, "Recording cleanup did not release its backend: ${vm.gpuRecordState.value}") {
            when (vm.gpuRecordState.value) {
                is GpuRecordingState.Idle,
                is GpuRecordingState.Ready,
                is GpuRecordingState.Error ->
                    vm.activeRecordingTransportHudLabel() == null &&
                        !vm.isRecordingReadyForDebugClipTiming()
                else -> false
            }
        }

    private fun GpuRecordingState.isRecordingLifecycleActive(): Boolean =
        this is GpuRecordingState.Starting ||
            this is GpuRecordingState.Recording ||
            this is GpuRecordingState.Paused ||
            this is GpuRecordingState.Stopping ||
            this is GpuRecordingState.Finalizing

    private data class VerifiedContentCadence(
        val totalFrameCount: Int,
        val contentFrameCount: Int,
        val measuredFps: Double,
        val maxContentGapNs: Long
    )

    private fun measureVerifiedContentCadence(
        master: File,
        reader: Npv10FileReader
    ): VerifiedContentCadence {
        val terminalBoundary = requireNotNull(
            Npv10VerifiedMasterPublication.terminalCadenceBoundary(master)
        ) { "Verified NPV10 master has no exact terminal cadence boundary" }
        var count = 0
        var firstTimestampNs = 0L
        var penultimateTimestampNs = 0L
        var lastTimestampNs = 0L
        val packetGapsNs = ArrayList<Long>()
        val storedPacketBuffer = allocateStoredPacketBuffer(reader)
        while (true) {
            // This test proves packet durability and camera cadence, not full-frame develop.
            // CRC-audit the stored packet in one reusable direct buffer so a live 4K camera
            // session cannot be combined with two full-frame Java arrays merely to read timestamps.
            val frame = reader.readNextStoredFrameInto(storedPacketBuffer) ?: break
            if (count == 0) firstTimestampNs = frame.timestampNs
            if (count > 0) {
                val gapNs = frame.timestampNs - lastTimestampNs
                check(gapNs > 0L) {
                    "NPV10 content timestamps are not strictly increasing at frame=$count"
                }
                packetGapsNs += gapNs
            }
            penultimateTimestampNs = lastTimestampNs
            lastTimestampNs = frame.timestampNs
            count += 1
        }
        check(count >= 3) { "Verified NPV10 master has too few frames for cadence analysis: $count" }
        check(lastTimestampNs == terminalBoundary.timestampNs) {
            "NPV10 terminal marker mismatch: packet=$lastTimestampNs marker=${terminalBoundary.timestampNs}"
        }
        check(firstTimestampNs > 0L && penultimateTimestampNs > firstTimestampNs) {
            "NPV10 content timestamp range is invalid: first=$firstTimestampNs last=$penultimateTimestampNs"
        }

        // The final packet is the exact one-shot Camera2 stop marker. Its incoming control interval
        // begins only after stopRepeating and is not recorded-content time. The v4 verified marker
        // proves this classification durably; no heuristic gap trimming is allowed here.
        val contentFrameCount = count - 1
        val maxContentGapNs = packetGapsNs.dropLast(1).maxOrNull() ?: 0L
        val contentDurationSeconds =
            (penultimateTimestampNs - firstTimestampNs).toDouble() / 1_000_000_000.0
        return VerifiedContentCadence(
            totalFrameCount = count,
            contentFrameCount = contentFrameCount,
            measuredFps = (contentFrameCount - 1).toDouble() / contentDurationSeconds,
            maxContentGapNs = maxContentGapNs
        )
    }

    private fun publishedNpv10MovCount(context: android.content.Context): Int {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Video.Media.IS_PENDING} = 0"
        val args = arrayOf("${Npv10MovPublishPolicy.RELATIVE_PATH}%")
        return context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { it.count } ?: 0
    }

    private fun allocateStoredPacketBuffer(reader: Npv10FileReader): ByteBuffer {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            reader.header.width,
            reader.header.height,
            reader.header.payloadFormat
        )
        val storedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            reader.header.packetCodec
        )
        require(storedBytes in 1L..Int.MAX_VALUE.toLong()) {
            "NPV10 stored packet exceeds the direct audit-buffer limit: $storedBytes"
        }
        return ByteBuffer.allocateDirect(storedBytes.toInt())
    }

    private fun awaitCondition(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }

    private fun fileContainsAscii(file: File, marker: String): Boolean {
        val target = marker.toByteArray(Charsets.US_ASCII)
        var matched = 0
        file.inputStream().buffered(64 * 1024).use { input ->
            while (true) {
                val next = input.read()
                if (next < 0) return false
                val byte = next.toByte()
                matched = if (byte == target[matched]) {
                    matched + 1
                } else if (byte == target[0]) {
                    1
                } else {
                    0
                }
                if (matched == target.size) return true
            }
        }
    }

    private companion object {
        const val MIN_FIRST_CLIP_RECORDING_MS = 750L
        const val MIN_RESTARTED_CLIP_RECORDING_MS = 1_000L
        const val MAX_OVERLAP_DISPATCH_MS = 1_000L
        const val MAX_OVERLAP_RECOVERY_MS = 20_000L
        const val MASTER_FINALIZATION_TIMEOUT_MS = 30_000L
        const val POST_FINALIZATION_SETTLE_MS = 500L
        const val MIN_CONTENT_FRAMES = 60
        const val RAW_CADENCE_CAPTURE_MS = 2_000L
        const val MIN_RAW_CONTENT_FRAMES = 60
        const val MAX_RAW_FRAME_GAP_NS = 60_000_000L
        const val MAX_PROCESSED_FRAME_GAP_NS = 60_000_000L
        const val NPRAW_TIMESTAMP_OFFSET_BYTES = 26
        const val TARGET_RECORDING_FPS = 25
        const val MAX_NDR_FRAME_GAP_US = 60_000L
        const val MIN_ACTIVE_DURATION_BEFORE_PAUSE_MS = 1_000L
        const val PAUSE_PROOF_WINDOW_MS = 1_500L
        const val MAX_PAUSED_DURATION_DRIFT_MS = 80L
        const val MAX_RESUME_DURATION_DRIFT_MS = 250L
        const val MIN_ACTIVE_DURATION_AFTER_RESUME_MS = 1_000L
        const val BACKGROUND_RELEASE_WINDOW_MS = 500L
        // Three seconds supplies at least 60 physical frames at the 25 fps floor after startup,
        // allowing CP-213 to inspect a representative cadence window without relaxing its
        // zero-missing-frame requirement.
        const val STRESS_RECORDING_WINDOW_MS = 3_000L
        const val LIFECYCLE_STRESS_CYCLES = 3
    }
}
