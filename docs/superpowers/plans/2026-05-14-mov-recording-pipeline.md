# MOV Recording Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the MediaCodec recording save path into a focused, MOV-only premium pipeline with reliable MediaStore publish/cleanup and truthful cinema metadata.

**Architecture:** Keep `RecordingManager` as the public facade used by `CameraViewModel`, but extract MOV output contract, MediaStore target lifecycle, MOV backend session control, encoder `MediaFormat` construction, and stop-finalization budgets into focused units. New units are pure-policy-first where possible so behavior is covered by JVM unit tests before `RecordingManager` wiring changes.

**Tech Stack:** Kotlin, Android `MediaCodec`, `MediaFormat`, `MediaStore`, custom `QuickTimeMovBackend`, Gradle `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, adb smoke tests.

---

## File Structure

- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMovOutputContractPolicy.kt`
  - Resolves MOV-only selection, target descriptor, route, and metadata description.
- Create `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMovOutputContractPolicyTest.kt`
  - Locks MOV-only behavior for SDR, HDR, LOG, and RAW.
- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreOutputStore.kt`
  - Owns pending MediaStore/legacy file creation, publish, close, and failed cleanup.
- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreLifecyclePolicy.kt`
  - Pure policy for pending/publish/delete lifecycle commands.
- Create `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreLifecyclePolicyTest.kt`
  - Tests lifecycle decisions without Android framework side effects.
- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMovSessionController.kt`
  - Wraps `RecordingContainerBackend` with open, orientation, track, sample, close, abort.
- Create `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMovSessionControllerTest.kt`
  - Uses a fake backend to verify call ordering and abort/close behavior.
- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt`
  - Builds video `MediaFormat` and applies HDR metadata/all-intra outcomes from the already resolved recording plan.
- Create `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt`
  - Tests color keys, bitrate, profile, priority, HDR metadata mode, and all-intra result.
- Create `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingStopFinalizationPolicy.kt`
  - Pure stop wait-budget and finalization decisions.
- Create `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingStopFinalizationPolicyTest.kt`
  - Tests drain/publish/cleanup decisions.
- Modify `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
  - Delegate output target lifecycle, MOV backend lifecycle, encoder format construction, and stop budgets to new units.

---

### Task 1: MOV Output Contract

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMovOutputContractPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMovOutputContractPolicy.kt`

- [x] **Step 1: Write failing tests**

Test code:
```kotlin
package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMovOutputContractPolicyTest {
    @Test
    fun rawModeUsesOriginalQualityPreservingMovOnly() {
        val contract = RecordingMovOutputContractPolicy.resolve(
            timestampLabel = "20260514_101500",
            outputLabelSuffix = "_RAW",
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoEnabled = true,
            logVideoEnabled = false,
            hdrMode = "OFF",
            descriptionSnapshot = snapshot(dynamicRangeLabel = "RAW_SENSOR", bitDepthLabel = "12-bit RAW")
        )

        assertEquals(RecordingContainer.MOV, contract.selection.container)
        assertEquals("mov", contract.selection.fileExtension)
        assertEquals("video/quicktime", contract.selection.mediaStoreMime)
        assertEquals("Original Quality-Preserving MOV", contract.selection.userVisibleLabel)
        assertEquals("CAMX_GPU_RAW_20260514_101500.mov", contract.descriptor.fileName)
        assertEquals("DCIM/CamX", contract.route.relativePath)
        assertTrue(contract.description.contains("dynamicRange=RAW_SENSOR"))
        assertTrue(contract.description.contains("bitDepth=12-bit RAW"))
    }

    @Test
    fun hdrLogAndSdrRemainQuickTimeMovWithoutRawClaim() {
        val hdr = RecordingMovOutputContractPolicy.resolve(
            timestampLabel = "20260514_101501",
            outputLabelSuffix = "_HDR",
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoEnabled = false,
            logVideoEnabled = false,
            hdrMode = "HDR10",
            descriptionSnapshot = snapshot(dynamicRangeLabel = "HDR10 (10-bit)")
        )
        val log = RecordingMovOutputContractPolicy.resolve(
            timestampLabel = "20260514_101502",
            outputLabelSuffix = "_LOG",
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoEnabled = false,
            logVideoEnabled = true,
            hdrMode = "OFF",
            descriptionSnapshot = snapshot(logCurveLabel = "Neo Flex Curve")
        )
        val sdr = RecordingMovOutputContractPolicy.resolve(
            timestampLabel = "20260514_101503",
            outputLabelSuffix = "",
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoEnabled = false,
            logVideoEnabled = false,
            hdrMode = "OFF",
            descriptionSnapshot = snapshot()
        )

        assertEquals("QuickTime MOV", hdr.selection.userVisibleLabel)
        assertEquals("QuickTime MOV", log.selection.userVisibleLabel)
        assertEquals("QuickTime MOV", sdr.selection.userVisibleLabel)
        assertEquals("video/quicktime", hdr.descriptor.mimeType)
        assertEquals("video/quicktime", log.descriptor.mimeType)
        assertEquals("video/quicktime", sdr.descriptor.mimeType)
        assertTrue(hdr.description.contains("hdrIntent=HDR10"))
        assertTrue(log.description.contains("logCurve=Neo Flex Curve"))
    }

    private fun snapshot(
        bitDepthLabel: String = "Hardware 10-bit",
        dynamicRangeLabel: String = "SDR",
        logCurveLabel: String = "OFF"
    ) = RecordingOutputDescriptionSnapshot(
        videoWidth = 3840,
        videoHeight = 2160,
        videoFps = 30,
        codecLabel = "HEVC",
        bitDepthLabel = bitDepthLabel,
        colorSpace = "Rec.2020",
        hdrIntentLabel = "HDR10",
        dynamicRangeLabel = dynamicRangeLabel,
        logCurveLabel = logCurveLabel,
        qualityTierLabel = "CINEMA",
        cinemaMasteringEnabled = true,
        allIntraEnabled = false,
        gopSeconds = 1f,
        simulatedTenBitFallbackActive = false,
        bitrateBitsPerSecond = 300_000_000,
        sceneMetadataDescription = ""
    )
}
```

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingMovOutputContractPolicyTest
```

Expected: compile failure because `RecordingMovOutputContractPolicy` does not exist.

- [x] **Step 2: Implement policy**

Create:
```kotlin
package com.neoproc.neoproccamx.recording

internal data class RecordingMovOutputContract(
    val selection: RecordingContainerSelection,
    val descriptor: RecordingOutputTargetDescriptor,
    val route: RecordingOutputRoute,
    val description: String
)

internal object RecordingMovOutputContractPolicy {
    fun resolve(
        timestampLabel: String,
        outputLabelSuffix: String,
        cinemaMode: CinemaVideoMode,
        rawVideoEnabled: Boolean,
        logVideoEnabled: Boolean,
        hdrMode: String,
        descriptionSnapshot: RecordingOutputDescriptionSnapshot
    ): RecordingMovOutputContract {
        val selection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = cinemaMode,
            rawVideoEnabled = rawVideoEnabled,
            logVideoEnabled = logVideoEnabled,
            hdrMode = hdrMode
        )
        require(selection.container == RecordingContainer.MOV)
        val descriptor = RecordingOutputTargetPolicy.resolve(
            selection = selection,
            timestampLabel = timestampLabel,
            outputLabelSuffix = outputLabelSuffix
        )
        return RecordingMovOutputContract(
            selection = selection,
            descriptor = descriptor,
            route = RecordingOutputRouting.resolve(),
            description = RecordingOutputDescriptionPolicy.build(descriptionSnapshot)
        )
    }
}
```

- [x] **Step 3: Run tests**

Run the same focused test command. Expected: pass.

### Task 2: MediaStore Lifecycle Policy And Store

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreLifecyclePolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreLifecyclePolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreOutputStore.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`

- [x] **Step 1: Write failing lifecycle tests**

Test code:
```kotlin
package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMediaStoreLifecyclePolicyTest {
    @Test
    fun androidQAndAboveUsesPendingMediaStoreVideoRow() {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = 35,
            contract = contract()
        )

        assertEquals(RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO, plan.storageKind)
        assertTrue(plan.pending)
        assertEquals("video/quicktime", plan.mimeType)
        assertEquals("DCIM/CamX", plan.relativePath)
    }

    @Test
    fun legacyAndroidUsesPublicDcimFile() {
        val plan = RecordingMediaStoreLifecyclePolicy.resolveCreate(
            sdkInt = 28,
            contract = contract()
        )

        assertEquals(RecordingOutputStorageKind.LEGACY_PUBLIC_FILE, plan.storageKind)
        assertFalse(plan.pending)
        assertEquals("DCIM", plan.legacyRootDirectory)
        assertEquals("CamX", plan.legacySubdirectory)
    }

    @Test
    fun publishRequiresPendingMediaStoreTargetAndFinalDescription() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            pending = true,
            finalDescription = "video=3840x2160@30fps"
        )

        assertTrue(decision.closeDescriptorFirst)
        assertTrue(decision.publishMediaStore)
        assertFalse(decision.scanLegacyFile)
        assertEquals("video=3840x2160@30fps", decision.finalDescription)
    }

    @Test
    fun failedPendingMediaStoreTargetIsDeleted() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            hasUri = true,
            hasFile = false
        )

        assertTrue(decision.deleteMediaStoreUri)
        assertFalse(decision.deleteLegacyFile)
    }

    private fun contract() = RecordingMovOutputContract(
        selection = RecordingContainerSelection(
            container = RecordingContainer.MOV,
            fileExtension = "mov",
            mediaStoreMime = "video/quicktime",
            userVisibleLabel = "QuickTime MOV"
        ),
        descriptor = RecordingOutputTargetDescriptor(
            fileName = "CAMX_GPU_20260514_101500.mov",
            mimeType = "video/quicktime",
            containerLabel = "QuickTime MOV"
        ),
        route = RecordingOutputRoute(
            relativePath = "DCIM/CamX",
            legacyRootDirectory = "DCIM",
            legacySubdirectory = "CamX"
        ),
        description = "video=3840x2160@30fps"
    )
}
```

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingMediaStoreLifecyclePolicyTest
```

Expected: compile failure because lifecycle policy does not exist.

- [x] **Step 2: Implement lifecycle policy and output store**

Create a pure lifecycle policy:
```kotlin
internal enum class RecordingOutputStorageKind {
    MEDIASTORE_PENDING_VIDEO,
    LEGACY_PUBLIC_FILE
}

internal data class RecordingOutputCreatePlan(
    val storageKind: RecordingOutputStorageKind,
    val pending: Boolean,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val legacyRootDirectory: String,
    val legacySubdirectory: String,
    val description: String
)

internal data class RecordingOutputFinalizeDecision(
    val closeDescriptorFirst: Boolean,
    val publishMediaStore: Boolean,
    val scanLegacyFile: Boolean,
    val finalDescription: String
)

internal data class RecordingOutputFailureCleanupDecision(
    val deleteMediaStoreUri: Boolean,
    val deleteLegacyFile: Boolean
)
```

Create `RecordingMediaStoreOutputStore` with:
```kotlin
internal class RecordingMediaStoreOutputStore(private val context: Context) {
    fun create(contract: RecordingMovOutputContract): RecordingOutputTarget
    fun finalize(target: RecordingOutputTarget, finalDescription: String)
    fun cleanupFailure(target: RecordingOutputTarget?)
}
```

Move the existing MediaStore insert/finalize/delete and legacy file/scanner code from `RecordingManager` into this store. Keep the same Android Q+ pending behavior and legacy path behavior.

- [x] **Step 3: Run lifecycle tests**

Run the focused lifecycle test. Expected: pass.

### Task 3: MOV Session Controller

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingMovSessionControllerTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingMovSessionController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`

- [x] **Step 1: Write failing controller tests**

Test code:
```kotlin
package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RecordingMovSessionControllerTest {
    @Test
    fun openAppliesOrientationBeforeTracksAndSamples() {
        val backend = FakeBackend()
        val controller = RecordingMovSessionController(backend)

        controller.open("/proc/self/fd/42", "QuickTime MOV", orientationHintDegrees = 90)
        val videoTrack = controller.addVideoTrack("video/hevc", 3840, 2160, 30, byteArrayOf(1, 2))
        val audioTrack = controller.addAudioTrack("audio/mp4a-latm", 48_000, 2, byteArrayOf(0x12, 0x10))
        controller.writeSample(videoTrack, ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0L, 1)
        controller.writeSample(audioTrack, ByteBuffer.wrap(byteArrayOf(4, 5)), 1_024L, 0)
        controller.close()

        assertEquals(
            listOf("open", "orientation:90", "video", "audio", "sample:0", "sample:1", "close"),
            backend.calls
        )
    }

    @Test
    fun abortClosesBackendWithAbortOnly() {
        val backend = FakeBackend()
        val controller = RecordingMovSessionController(backend)

        controller.open("/tmp/out.mov", "QuickTime MOV", orientationHintDegrees = 0)
        controller.abort()

        assertEquals(listOf("open", "abort"), backend.calls)
    }

    private class FakeBackend : RecordingContainerBackend {
        val calls = mutableListOf<String>()
        override fun open(outputPath: String, containerLabel: String) { calls += "open" }
        override fun addVideoTrack(mime: String, width: Int, height: Int, fps: Int, codecConfig: ByteArray): Int {
            calls += "video"
            return 0
        }
        override fun addAudioTrack(mime: String, sampleRate: Int, channelCount: Int, codecConfig: ByteArray): Int {
            calls += "audio"
            return 1
        }
        override fun writeSample(trackIndex: Int, buffer: ByteBuffer, presentationTimeUs: Long, flags: Int) {
            calls += "sample:$trackIndex"
        }
        override fun close() { calls += "close" }
        override fun abort() { calls += "abort" }
        override fun setOrientationHint(degrees: Int) { calls += "orientation:$degrees" }
    }
}
```

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingMovSessionControllerTest
```

Expected: compile failure because controller does not exist.

- [x] **Step 2: Implement controller**

Create:
```kotlin
internal class RecordingMovSessionController(
    private val backend: RecordingContainerBackend
) {
    private var opened = false

    fun open(outputPath: String, containerLabel: String, orientationHintDegrees: Int) {
        backend.open(outputPath, containerLabel)
        opened = true
        if (orientationHintDegrees != 0) backend.setOrientationHint(orientationHintDegrees)
    }

    fun addVideoTrack(mime: String, width: Int, height: Int, fps: Int, codecConfig: ByteArray): Int =
        backend.addVideoTrack(mime, width, height, fps, codecConfig)

    fun addAudioTrack(mime: String, sampleRate: Int, channelCount: Int, codecConfig: ByteArray): Int =
        backend.addAudioTrack(mime, sampleRate, channelCount, codecConfig)

    fun writeSample(trackIndex: Int, buffer: ByteBuffer, presentationTimeUs: Long, flags: Int) {
        backend.writeSample(trackIndex, buffer, presentationTimeUs, flags)
    }

    fun close() {
        if (opened) backend.close()
        opened = false
    }

    fun abort() {
        if (opened) backend.abort()
        opened = false
    }
}
```

- [x] **Step 3: Run controller tests**

Run the focused controller test. Expected: pass.

### Task 4: Encoder Format Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`

- [x] **Step 1: Write failing format tests**

Test code:
```kotlin
package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEncoderFormatPolicyTest {
    @Test
    fun hdr10HevcFormatCarries10BitColorAndHdrMetadataMode() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                ),
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            )
        )

        assertEquals("video/hevc", result.format.getString(MediaFormat.KEY_MIME))
        assertEquals(3840, result.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(2160, result.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(300_000_000, result.format.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, result.format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_TRANSFER_ST2084, result.format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HDR10, result.staticMetadataMode)
    }

    @Test
    fun hlgFormatDoesNotAttachHdr10StaticMetadata() {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            request = request(
                colorEncoding = RecordingColorEncodingConfig(
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorRange = MediaFormat.COLOR_RANGE_FULL,
                    requiresTenBit = true,
                    main10Requested = true
                )
            )
        )

        assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.HLG, result.staticMetadataMode)
    }

    @Test
    fun allIntraResultUpdatesBitrateAndIntervalOnlyWhenApplied() {
        val result = RecordingEncoderFormatPolicy.resolveAllIntraResult(
            requested = true,
            mime = "video/hevc",
            fps = 30,
            videoPlanUseTenBit = true,
            probed = AllIntraHevcConfig(
                bitrate = 500_000_000,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5
            ),
            clampedBitrate = 450_000_000,
            applyResult = true
        )

        assertTrue(result.active)
        assertEquals(450_000_000, result.outputBitrate)
        assertEquals(1f / 30f, result.iFrameIntervalSeconds, 0.0001f)
    }

    @Test
    fun allIntraUnsupportedRemainsHonestFallback() {
        val result = RecordingEncoderFormatPolicy.resolveAllIntraResult(
            requested = true,
            mime = "video/hevc",
            fps = 30,
            videoPlanUseTenBit = true,
            probed = null,
            clampedBitrate = 300_000_000,
            applyResult = false
        )

        assertFalse(result.active)
        assertEquals(300_000_000, result.outputBitrate)
    }

    private fun request(
        colorEncoding: RecordingColorEncodingConfig = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_FULL,
            requiresTenBit = false,
            main10Requested = false
        ),
        profile: Int? = null
    ) = RecordingEncoderFormatRequest(
        mime = "video/hevc",
        width = 3840,
        height = 2160,
        fps = 30,
        bitrate = 300_000_000,
        iFrameIntervalSeconds = 1f,
        colorEncoding = colorEncoding,
        bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        encoderComplexity = 10,
        operatingRate = 90,
        profile = profile,
        sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        hdr10PlusRequested = false
    )
}
```

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingEncoderFormatPolicyTest
```

Expected: compile failure because encoder format policy does not exist.

- [x] **Step 2: Implement encoder format policy**

Create:
```kotlin
internal data class RecordingEncoderFormatRequest(...)
internal data class RecordingEncoderFormatResult(
    val format: MediaFormat,
    val staticMetadataMode: HdrStaticMetadataPolicy.StaticMetadataMode
)
internal data class RecordingAllIntraApplyResult(
    val active: Boolean,
    val outputBitrate: Int,
    val iFrameIntervalSeconds: Float,
    val qualityTierLabel: String?
)
```

Move the current `MediaFormat.createVideoFormat(...).apply { ... }` logic from `RecordingManager.prepare()` into `RecordingEncoderFormatPolicy.buildVideoFormat(...)`. Keep Android SDK guards and existing keys. Leave hardware probing in `RecordingManager`; the policy receives the chosen complexity, bitrate mode, profile, operating rate, and color encoding.

- [x] **Step 3: Run encoder format tests**

Run the focused format test. Expected: pass.

### Task 5: Stop Finalization Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingStopFinalizationPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingStopFinalizationPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`

- [x] **Step 1: Write failing stop policy tests**

Test code:
```kotlin
package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopFinalizationPolicyTest {
    @Test
    fun closeBackendOnlyWhenMuxerStartedAndTrackExists() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 0,
            writerActive = false
        )

        assertTrue(decision.closeBackend)
        assertFalse(decision.abortBackend)
    }

    @Test
    fun abortBackendWhenMuxerNeverStarted() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = false,
            videoTrackIndex = -1,
            pendingSamples = 0,
            writerActive = false
        )

        assertFalse(decision.closeBackend)
        assertTrue(decision.abortBackend)
    }

    @Test
    fun pendingSamplesRequireWriterWaitBeforeClose() {
        val decision = RecordingStopFinalizationPolicy.resolveCloseDecision(
            muxerStarted = true,
            videoTrackIndex = 0,
            pendingSamples = 4,
            writerActive = true
        )

        assertTrue(decision.waitForWriter)
        assertFalse(decision.closeBackend)
    }
}
```

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingStopFinalizationPolicyTest
```

Expected: compile failure because stop policy does not exist.

- [x] **Step 2: Implement stop finalization policy**

Create:
```kotlin
internal data class RecordingStopCloseDecision(
    val waitForWriter: Boolean,
    val closeBackend: Boolean,
    val abortBackend: Boolean
)

internal object RecordingStopFinalizationPolicy {
    const val TRACK_WAIT_BUDGET_MS = 1_000L
    const val CODEC_DRAIN_BUDGET_MS = 1_000L
    const val MUXER_QUEUE_DRAIN_BUDGET_MS = 1_000L
    const val WRITER_COMPLETION_BUDGET_MS = 2_000L

    fun resolveCloseDecision(
        muxerStarted: Boolean,
        videoTrackIndex: Int,
        pendingSamples: Int,
        writerActive: Boolean
    ): RecordingStopCloseDecision {
        if (pendingSamples > 0 || writerActive) {
            return RecordingStopCloseDecision(waitForWriter = true, closeBackend = false, abortBackend = false)
        }
        return if (muxerStarted && videoTrackIndex >= 0) {
            RecordingStopCloseDecision(waitForWriter = false, closeBackend = true, abortBackend = false)
        } else {
            RecordingStopCloseDecision(waitForWriter = false, closeBackend = false, abortBackend = true)
        }
    }
}
```

Update `RecordingManager.stop()` to use the budget constants and close decision instead of inline literals.

- [x] **Step 3: Run stop policy tests**

Run the focused stop policy test. Expected: pass.

### Task 6: Wire RecordingManager

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: focused recording tests and full unit suite.

- [x] **Step 1: Replace inline output target creation**

Use:
```kotlin
private val outputStore = RecordingMediaStoreOutputStore(context)
```

Replace `createOutputTarget()`, `finalizeOutputTarget(...)`, and `cleanupFailedOutputTarget()` internals with calls to output store. Keep method names as compatibility wrappers for smaller diff:
```kotlin
private fun createOutputTarget(): RecordingOutputTarget {
    val contract = RecordingMovOutputContractPolicy.resolve(...)
    return outputStore.create(contract)
}

private fun finalizeOutputTarget(finalDescription: String) {
    outputStore.finalize(outputTarget, finalDescription)
    outputTarget = null
}

private fun cleanupFailedOutputTarget() {
    outputStore.cleanupFailure(outputTarget)
    outputTarget = null
}
```

- [x] **Step 2: Replace inline MOV backend use**

Replace `containerBackend` direct calls with `movSessionController`:
```kotlin
private var movSessionController: RecordingMovSessionController? = null
```

Open:
```kotlin
movSessionController = RecordingMovSessionController(createContainerBackend(target.selection)).also {
    it.open(target.writablePath(), target.descriptor.containerLabel, orientationHintDegrees)
}
```

Track/sample methods delegate to the controller.

- [x] **Step 3: Replace inline video MediaFormat build**

After `videoPlan`, `encoderComplexity`, and `bitrateMode` are resolved, call `RecordingEncoderFormatPolicy.buildVideoFormat(...)`. Preserve existing all-intra probing but apply the result through the policy and keep `allIntraActiveInternal`, `activeIFrameIntervalSeconds`, `activeOutputBitrate`, and `activeQualityTierLabel` truthful.

- [x] **Step 4: Run focused recording tests**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.RecordingMovOutputContractPolicyTest --tests com.neoproc.neoproccamx.recording.RecordingMediaStoreLifecyclePolicyTest --tests com.neoproc.neoproccamx.recording.RecordingMovSessionControllerTest --tests com.neoproc.neoproccamx.recording.RecordingEncoderFormatPolicyTest --tests com.neoproc.neoproccamx.recording.RecordingStopFinalizationPolicyTest --tests com.neoproc.neoproccamx.recording.RecordingContainerSelectionPolicyTest --tests com.neoproc.neoproccamx.recording.RecordingOutputRoutingTest --tests com.neoproc.neoproccamx.recording.RecordingOutputTargetPolicyTest --tests com.neoproc.neoproccamx.recording.QuickTimeMovBackendTest
```

Expected: all selected tests pass.

### Task 7: Full Verification And Device Smoke

**Files:**
- No new production files.

- [x] **Step 1: Full unit tests**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest
```

Expected: build successful.

- [x] **Step 2: Build and lint**

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:lintDebug
```

Expected: both commands exit 0.

- [ ] **Step 3: Install, launch, and short recording smoke**

Blocked on 2026-05-14: `adb devices` reports no connected targets after restarting
the ADB daemon, so install/launch/recording smoke cannot run until a phone or
emulator is connected.

Run:
```powershell
$adb='C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
& $adb logcat -c
& $adb shell am force-stop com.neoproc.neoproccamx
& $adb shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
Start-Sleep -Seconds 4
& $adb shell input tap 540 2200
Start-Sleep -Seconds 4
& $adb shell input tap 540 2200
Start-Sleep -Seconds 4
& $adb logcat -d -t 600 | Select-String -Pattern 'Recording saved|Saved to MediaStore|FATAL EXCEPTION|AndroidRuntime|RecordingManager'
```

Expected: no fatal crash, recording starts and stops, logs show `.mov` saved/published or a hardware-denied recording error that cleans up partial output.

---

## Self-Review

- Spec coverage: MOV-only output, MediaCodec format quality, MediaStore pending publish/cleanup, MOV backend lifecycle, stop finalization, truthful fallback metadata, tests, build/lint/device smoke are covered.
- Placeholder scan: No TBD/TODO placeholders are present.
- Type consistency: All new types are introduced before use and remain in `com.neoproc.neoproccamx.recording`.
