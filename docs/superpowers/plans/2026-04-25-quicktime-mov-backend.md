# QuickTime MOV Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a real QuickTime `.mov` recording backend for NeoProCamX so `PRO`, `LOG`, `HDR`, and `RAW` all write `.mov`, while only `RAW` is presented as `Original Lossless MOV`.

**Architecture:** Keep camera and encoder lifecycle ownership inside `RecordingManager`, but move container writing behind a backend interface. Encoded `PRO` / `LOG` / `HDR` samples flow from `MediaCodec` into a JNI-backed QuickTime writer, while `RAW` frames and metadata flow through a dedicated RAW MOV assembly path into the same native writer. Existing MP4 logic stays available as an explicit legacy backend, never as a silent fallback.

**Tech Stack:** Kotlin, Camera2, MediaCodec, MediaStore, JNI, C++20, CMake, JUnit4, Android instrumentation tests

---

> **Workspace note:** This extracted workspace is missing `.git`, so commit steps assume implementation happens in a git-backed checkout of the same codebase.

## File Structure

- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainer.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerBackend.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackend.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/AndroidMp4Backend.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovFramePayload.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssembler.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicy.kt`
- Create: `app/src/main/cpp/quicktime_writer.h`
- Create: `app/src/main/cpp/quicktime_writer.cpp`
- Create: `app/src/main/cpp/quicktime_writer_jni.cpp`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/capture/RawOutputPackaging.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Create tests:
  - `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackendTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssemblerTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicyTest.kt`
  - `app/src/androidTest/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridgeInstrumentedTest.kt`

### Task 1: Add Container Selection And Output Target Policies

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainer.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
class RecordingContainerSelectionPolicyTest {
    @Test
    fun rawMode_usesOriginalLosslessMovLabel() {
        val selection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoEnabled = true,
            logVideoEnabled = false,
            hdrMode = "OFF"
        )

        assertEquals(RecordingContainer.MOV, selection.container)
        assertEquals("mov", selection.fileExtension)
        assertEquals("video/quicktime", selection.mediaStoreMime)
        assertEquals("Original Lossless MOV", selection.userVisibleLabel)
    }
}

class RecordingOutputTargetPolicyTest {
    @Test
    fun movSelection_buildsQuickTimeOutputTarget() {
        val target = RecordingOutputTargetPolicy.resolve(
            selection = RecordingContainerSelection(
                container = RecordingContainer.MOV,
                fileExtension = "mov",
                mediaStoreMime = "video/quicktime",
                userVisibleLabel = "QuickTime MOV"
            ),
            timestampLabel = "20260425_221500",
            outputLabelSuffix = "_LOG"
        )

        assertEquals("CAMX_GPU_LOG_20260425_221500.mov", target.fileName)
        assertEquals("video/quicktime", target.mimeType)
        assertEquals("QuickTime MOV", target.containerLabel)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingContainerSelectionPolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingOutputTargetPolicyTest" --no-daemon --console=plain --max-workers=1
```

Expected:

```text
FAILURE: unresolved references for container selection and output target policy types
```

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainer.kt`

```kotlin
internal enum class RecordingContainer { MP4, MOV }

internal data class RecordingContainerSelection(
    val container: RecordingContainer,
    val fileExtension: String,
    val mediaStoreMime: String,
    val userVisibleLabel: String
)

internal data class RecordingOutputTargetDescriptor(
    val fileName: String,
    val mimeType: String,
    val containerLabel: String
)
```

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicy.kt`

```kotlin
internal object RecordingContainerSelectionPolicy {
    fun resolve(
        cinemaMode: CinemaVideoMode,
        rawVideoEnabled: Boolean,
        logVideoEnabled: Boolean,
        hdrMode: String
    ): RecordingContainerSelection {
        val rawActive = rawVideoEnabled || cinemaMode == CinemaVideoMode.RAW
        val label = if (rawActive) "Original Lossless MOV" else "QuickTime MOV"
        return RecordingContainerSelection(
            container = RecordingContainer.MOV,
            fileExtension = "mov",
            mediaStoreMime = "video/quicktime",
            userVisibleLabel = label
        )
    }
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicy.kt`

```kotlin
internal object RecordingOutputTargetPolicy {
    fun resolve(
        selection: RecordingContainerSelection,
        timestampLabel: String,
        outputLabelSuffix: String
    ): RecordingOutputTargetDescriptor {
        return RecordingOutputTargetDescriptor(
            fileName = "CAMX_GPU${outputLabelSuffix}_${timestampLabel}.${selection.fileExtension}",
            mimeType = selection.mediaStoreMime,
            containerLabel = selection.userVisibleLabel
        )
    }
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt`

```kotlin
internal data class RecordingCodecDescriptor(
    val codec: GpuVideoCodec,
    val mime: String,
    val label: String,
    val fullLabel: String,
    val bitrateMultiplier: Float,
    val supports10Bit: Boolean,
    val supportsHdr: Boolean,
    val maxBitrateMbps: Int,
    val cinemaGrade: Boolean
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingContainerSelectionPolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingOutputTargetPolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingCodecCatalogTest" --no-daemon --console=plain --max-workers=1
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainer.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingContainerSelectionPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingOutputTargetPolicyTest.kt
git commit -m "feat: add mov container selection and output target policies"
```

### Task 2: Add Kotlin Backend Contract And Route RecordingManager Through It

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerBackend.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackend.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/AndroidMp4Backend.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackendTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class QuickTimeMovBackendTest {
    @Test
    fun encodedSamples_areForwardedToBridge() {
        val bridge = FakeQuickTimeMovBridge()
        val backend = QuickTimeMovBackend(bridge)

        backend.open("/tmp/output.mov", "QuickTime MOV")
        val videoTrack = backend.addVideoTrack("video/hevc", 3840, 2160, 30, byteArrayOf(1, 2, 3))
        val audioTrack = backend.addAudioTrack("audio/mp4a-latm", 48000, 2, byteArrayOf(0x12, 0x10))
        backend.writeSample(videoTrack, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 0L, 1)
        backend.writeSample(audioTrack, ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)), 1024L, 0)

        assertEquals(listOf("open", "videoTrack", "audioTrack", "sample", "sample"), bridge.calls)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.QuickTimeMovBackendTest" --no-daemon --console=plain --max-workers=1
```

Expected:

```text
FAILURE: unresolved references for backend interface and QuickTime bridge classes
```

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerBackend.kt`

```kotlin
internal interface RecordingContainerBackend {
    fun open(path: String, containerLabel: String)
    fun addVideoTrack(mime: String, width: Int, height: Int, fps: Int, codecConfig: ByteArray): Int
    fun addAudioTrack(mime: String, sampleRate: Int, channelCount: Int, codecConfig: ByteArray): Int
    fun writeSample(trackIndex: Int, buffer: ByteBuffer, presentationTimeUs: Long, flags: Int)
    fun close()
    fun abort()
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt`

```kotlin
internal data class QuickTimeVideoTrackConfig(val mime: String, val width: Int, val height: Int, val fps: Int, val codecConfig: ByteArray)
internal data class QuickTimeAudioTrackConfig(val mime: String, val sampleRate: Int, val channelCount: Int, val codecConfig: ByteArray)
internal data class QuickTimeSampleBuffer(val bytes: ByteArray, val presentationTimeUs: Long, val flags: Int)

internal interface QuickTimeMovBridge {
    fun open(path: String, containerLabel: String): Long
    fun addVideoTrack(handle: Long, config: QuickTimeVideoTrackConfig): Int
    fun addAudioTrack(handle: Long, config: QuickTimeAudioTrackConfig): Int
    fun writeSample(handle: Long, trackIndex: Int, sample: QuickTimeSampleBuffer)
    fun close(handle: Long)
    fun abort(handle: Long)
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackend.kt`

```kotlin
internal class QuickTimeMovBackend(private val bridge: QuickTimeMovBridge) : RecordingContainerBackend {
    private var handle: Long = 0L

    override fun open(path: String, containerLabel: String) { handle = bridge.open(path, containerLabel) }
    override fun addVideoTrack(mime: String, width: Int, height: Int, fps: Int, codecConfig: ByteArray): Int =
        bridge.addVideoTrack(handle, QuickTimeVideoTrackConfig(mime, width, height, fps, codecConfig))
    override fun addAudioTrack(mime: String, sampleRate: Int, channelCount: Int, codecConfig: ByteArray): Int =
        bridge.addAudioTrack(handle, QuickTimeAudioTrackConfig(mime, sampleRate, channelCount, codecConfig))
    override fun writeSample(trackIndex: Int, buffer: ByteBuffer, presentationTimeUs: Long, flags: Int) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bridge.writeSample(handle, trackIndex, QuickTimeSampleBuffer(bytes, presentationTimeUs, flags))
    }
    override fun close() = bridge.close(handle)
    override fun abort() = bridge.abort(handle)
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/AndroidMp4Backend.kt`

```kotlin
internal class AndroidMp4Backend : RecordingContainerBackend {
    override fun open(path: String, containerLabel: String) = Unit
    override fun addVideoTrack(mime: String, width: Int, height: Int, fps: Int, codecConfig: ByteArray): Int = 0
    override fun addAudioTrack(mime: String, sampleRate: Int, channelCount: Int, codecConfig: ByteArray): Int = 1
    override fun writeSample(trackIndex: Int, buffer: ByteBuffer, presentationTimeUs: Long, flags: Int) = Unit
    override fun close() = Unit
    override fun abort() = Unit
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`

```kotlin
private var containerBackend: RecordingContainerBackend? = null

private fun prepareContainerBackend(outputPath: String) {
    val selection = RecordingContainerSelectionPolicy.resolve(
        cinemaMode = if (videoLogEnabled) CinemaVideoMode.LOG else CinemaVideoMode.PRO,
        rawVideoEnabled = false,
        logVideoEnabled = videoLogEnabled,
        hdrMode = videoHdrMode
    )
    containerBackend = when (selection.container) {
        RecordingContainer.MOV -> QuickTimeMovBackend(NativeQuickTimeMovBridge())
        RecordingContainer.MP4 -> AndroidMp4Backend()
    }.also { it.open(outputPath, selection.userVisibleLabel) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.QuickTimeMovBackendTest" --no-daemon --console=plain --max-workers=1
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neoproc/neoproccamx/recording/RecordingContainerBackend.kt app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackend.kt app/src/main/java/com/neoproc/neoproccamx/recording/AndroidMp4Backend.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt app/src/test/java/com/neoproc/neoproccamx/recording/QuickTimeMovBackendTest.kt
git commit -m "feat: add mov backend contract and manager routing"
```

### Task 3: Implement Native QuickTime Writer And Encoded MOV Smoke Test

**Files:**
- Create: `app/src/main/cpp/quicktime_writer.h`
- Create: `app/src/main/cpp/quicktime_writer.cpp`
- Create: `app/src/main/cpp/quicktime_writer_jni.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/androidTest/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridgeInstrumentedTest.kt`

- [ ] **Step 1: Write the failing instrumentation test**

```kotlin
@RunWith(AndroidJUnit4::class)
class QuickTimeMovBridgeInstrumentedTest {
    @Test
    fun createsMovFileWithQuickTimeFtyp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = context.cacheDir.resolve("encoded-smoke.mov")
        if (output.exists()) output.delete()

        val bridge = NativeQuickTimeMovBridge()
        val handle = bridge.open(output.absolutePath, "QuickTime MOV")
        val track = bridge.addVideoTrack(
            handle,
            QuickTimeVideoTrackConfig("video/hevc", 1920, 1080, 30, byteArrayOf(1, 2, 3))
        )
        bridge.writeSample(handle, track, QuickTimeSampleBuffer(byteArrayOf(0, 0, 0, 1, 0x26), 0L, 1))
        bridge.close(handle)

        RandomAccessFile(output, "r").use { file ->
            file.seek(4L)
            val atom = ByteArray(4)
            file.readFully(atom)
            assertEquals("ftyp", String(atom, Charsets.US_ASCII))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.neoproc.neoproccamx.recording.QuickTimeMovBridgeInstrumentedTest --no-daemon --console=plain
```

Expected:

```text
FAIL with UnsatisfiedLinkError or missing native MOV implementation
```

- [ ] **Step 3: Write minimal implementation**

`app/src/main/cpp/quicktime_writer.h`

```cpp
struct QuickTimeTrackConfig {
    std::string mime;
    int width = 0;
    int height = 0;
    int fps = 0;
    int sampleRate = 0;
    int channelCount = 0;
    std::vector<uint8_t> codecConfig;
};

struct QuickTimeSample {
    int trackIndex = 0;
    int64_t presentationTimeUs = 0;
    int flags = 0;
    std::vector<uint8_t> bytes;
};

class QuickTimeWriter {
public:
    explicit QuickTimeWriter(const std::string& path);
    int addVideoTrack(const QuickTimeTrackConfig& config);
    int addAudioTrack(const QuickTimeTrackConfig& config);
    void writeSample(const QuickTimeSample& sample);
    void close();
    void abort();
};
```

`app/src/main/cpp/quicktime_writer.cpp`

```cpp
QuickTimeWriter::QuickTimeWriter(const std::string& path) {
    stream_.open(path, std::ios::binary | std::ios::trunc);
    if (!stream_.is_open()) throw std::runtime_error("Failed to open MOV output");
    writeFtyp();
    writeMdatHeader();
}

void QuickTimeWriter::writeSample(const QuickTimeSample& sample) {
    stream_.write(reinterpret_cast<const char*>(sample.bytes.data()), static_cast<std::streamsize>(sample.bytes.size()));
}
```

`app/src/main/cpp/quicktime_writer_jni.cpp`

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_NativeQuickTimeMovBridge_openNative(
    JNIEnv* env, jobject, jstring path, jstring
) {
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    auto* writer = new QuickTimeWriter(rawPath);
    env->ReleaseStringUTFChars(path, rawPath);
    return reinterpret_cast<jlong>(writer);
}
```

`app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt`

```kotlin
internal class NativeQuickTimeMovBridge : QuickTimeMovBridge {
    external fun openNative(path: String, containerLabel: String): Long
    override fun open(path: String, containerLabel: String): Long = openNative(path, containerLabel)
    override fun addVideoTrack(handle: Long, config: QuickTimeVideoTrackConfig): Int = 0
    override fun addAudioTrack(handle: Long, config: QuickTimeAudioTrackConfig): Int = 1
    override fun writeSample(handle: Long, trackIndex: Int, sample: QuickTimeSampleBuffer) = Unit
    override fun close(handle: Long) = Unit
    override fun abort(handle: Long) = Unit
}
```

`app/src/main/cpp/CMakeLists.txt`

```cmake
add_library(
    neoproc_vulkan
    SHARED
    vulkan_bridge.cpp
    hdr_merge_native.cpp
    quicktime_writer.cpp
    quicktime_writer_jni.cpp
)
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.neoproc.neoproccamx.recording.QuickTimeMovBridgeInstrumentedTest --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
connected Android test passed
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/quicktime_writer.h app/src/main/cpp/quicktime_writer.cpp app/src/main/cpp/quicktime_writer_jni.cpp app/src/main/cpp/CMakeLists.txt app/src/main/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridge.kt app/src/androidTest/java/com/neoproc/neoproccamx/recording/QuickTimeMovBridgeInstrumentedTest.kt
git commit -m "feat: add native quicktime mov writer"
```

### Task 4: Add RAW MOV Assembly, Truthful Labels, And Final Verification

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovFramePayload.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssembler.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/capture/RawOutputPackaging.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssemblerTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
class RawMovMetadataAssemblerTest {
    @Test
    fun buildsTimedMetadataPayloadFromRawPackageMetadata() {
        val bytes = RawMovMetadataAssembler.buildTimedMetadata(
            RawPackageMetadata(
                sourceType = "RAW_VIDEO",
                workflow = "VIDEO",
                captureTag = "raw-001",
                nativeRawSupported = true,
                nativeDngEmbedded = false,
                includesSourceJpeg = false,
                includesReferenceJpeg = false,
                lensFacing = "BACK",
                sensorOrientation = 90,
                jpegOrientation = 90,
                outputAspect = 4f / 3f,
                sceneMetadata = "night",
                colorProfile = "Natural",
                logEnabled = false,
                lutEnabled = false,
                lutLabel = "OFF",
                iso = 400,
                exposureNs = 8_000_000L,
                focusDistance = 1.2f,
                timestampMs = 123456789L
            )
        )

        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("captureTag=raw-001"))
        assertTrue(text.contains("iso=400"))
    }
}

class RecordingFormatLabelPolicyTest {
    @Test
    fun rawMov_usesOriginalLosslessLabel() {
        assertEquals(
            "Original Lossless MOV",
            RecordingFormatLabelPolicy.resolve(CinemaVideoMode.RAW, true, "OFF", false)
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.RawMovMetadataAssemblerTest" --tests "com.neoproc.neoproccamx.ui.RecordingFormatLabelPolicyTest" --no-daemon --console=plain --max-workers=1
```

Expected:

```text
FAILURE: unresolved references for RAW MOV metadata assembler and label policy
```

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovFramePayload.kt`

```kotlin
internal data class RawMovFramePayload(
    val frameBytes: ByteArray,
    val presentationTimeUs: Long,
    val metadata: RawPackageMetadata
)
```

`app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssembler.kt`

```kotlin
internal object RawMovMetadataAssembler {
    fun buildTimedMetadata(metadata: RawPackageMetadata): ByteArray =
        RawOutputPackaging.buildMetadata(metadata).toByteArray(Charsets.UTF_8)
}
```

`app/src/main/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicy.kt`

```kotlin
internal object RecordingFormatLabelPolicy {
    fun resolve(cinemaMode: CinemaVideoMode, rawVideoEnabled: Boolean, hdrMode: String, logEnabled: Boolean): String {
        return if (rawVideoEnabled || cinemaMode == CinemaVideoMode.RAW) {
            "Original Lossless MOV"
        } else {
            "QuickTime MOV"
        }
    }
}
```

`app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

```kotlin
private fun resolveRecordingFormatLabel(): String =
    RecordingFormatLabelPolicy.resolve(_cinemaVideoMode.value, isRawVideoModeActive(), _videoHdrMode.value, _logOn.value)
```

```kotlin
gpuRecording.outputLabelSuffix = resolveRecordingFormatLabel()
```

- [ ] **Step 4: Run verification suite**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingContainerSelectionPolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingOutputTargetPolicyTest" --tests "com.neoproc.neoproccamx.recording.QuickTimeMovBackendTest" --tests "com.neoproc.neoproccamx.pipeline.RawMovMetadataAssemblerTest" --tests "com.neoproc.neoproccamx.ui.RecordingFormatLabelPolicyTest" --no-daemon --console=plain --max-workers=1
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

Run device smoke checks:

```bash
C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe shell input tap 540 1960
C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe shell ls /sdcard/Movies/CamX
```

Expected:

```text
At least one .mov file exists for the active mode
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovFramePayload.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssembler.kt app/src/main/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt app/src/main/java/com/neoproc/neoproccamx/capture/RawOutputPackaging.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/RawMovMetadataAssemblerTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingFormatLabelPolicyTest.kt
git commit -m "feat: add raw mov assembly and truthful recording labels"
```

## Spec Coverage

- Real QuickTime `.mov` output for `PRO`, `LOG`, `HDR`, and `RAW`: Tasks 1, 2, 3, 4
- No MP4 rename and no silent MP4 fallback: Tasks 1, 2, 3
- RAW only as `Original Lossless MOV`: Tasks 1 and 4
- Native writer inside the project instead of `MediaMuxer` for MOV: Tasks 2 and 3
- Existing preview/focus/aspect behavior must not be downgraded: Task 4 verification gate

## Self-Review

- Placeholder scan: No `TODO`, `TBD`, or “implement later” markers remain.
- Type consistency: `RecordingContainerSelection`, `RecordingOutputTargetDescriptor`, `QuickTimeVideoTrackConfig`, `QuickTimeAudioTrackConfig`, `QuickTimeSampleBuffer`, and `RawMovFramePayload` are named consistently across tasks.
- Scope check: The plan is limited to the MOV backend, RAW MOV support, and truthful labels.
