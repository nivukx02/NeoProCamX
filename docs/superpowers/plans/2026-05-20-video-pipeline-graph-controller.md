# Video Pipeline Graph Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Phase 1 `VideoPipelineGraphController` as the single top-level video graph contract and wire it into safe preview/recording decisions.

**Architecture:** Keep the existing `VideoModePipelineContractPolicy` as the lower-level HDR/LOG/RAW delivery resolver. Add a higher-level graph controller that combines delivery identity, dimensions, FPS, encoder metadata identity, denoise/sharpness profile identities, recording-grade preview, and stable graph signatures. Wire `CameraViewModel` to consume this graph for render-quality hints, recording color sync, and QA logging.

**Tech Stack:** Kotlin, Android MediaFormat constants, JUnit4, existing Camera2/MediaCodec/Vulkan app architecture.

---

### Task 1: Add Graph Contract Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/VideoPipelineGraphControllerTest.kt`

- [ ] **Step 1: Write failing tests**

Create tests for:

```kotlin
class VideoPipelineGraphControllerTest {
    @Test fun `normal video resolves sdr graph and recording grade preview`() { ... }
    @Test fun `hdr graph disables log identity and uses hdr encoder metadata`() { ... }
    @Test fun `log graph does not resolve as hdr`() { ... }
    @Test fun `raw graph disables hdr and log delivery identities`() { ... }
    @Test fun `graph signature changes for mode resolution fps and delivery changes`() { ... }
    @Test fun `graph signature ignores display only monitoring fields`() { ... }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.VideoPipelineGraphControllerTest
```

Expected: compile failure because `VideoPipelineGraphController` is not defined.

### Task 2: Implement Graph Controller

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoPipelineGraphController.kt`

- [ ] **Step 1: Add enums and request/contract data classes**

Create:

```kotlin
internal enum class VideoDeliveryKind { SDR, HDR, LOG, RAW }
internal enum class VideoDenoiseProfile { ENCODED_VIDEO_STRICT, HDR_CLEAN, LOG_DETAIL, RAW_TRUTHFUL }
internal enum class VideoSharpnessProfile { NATURAL_DETAIL, HDR_HIGHLIGHT_SAFE, LOG_EDGE_PRESERVE, RAW_MONITORING }
internal enum class VideoCameraRequestFamily { IDLE_PREVIEW, VIDEO_PREVIEW_RECORDING_PARITY, RAW_SENSOR_TRUTHFUL }
```

Create `VideoPipelineGraphRequest` and `VideoPipelineContract`.

- [ ] **Step 2: Implement resolver**

Use `VideoFormatDeliveryPolicy.resolve(...)` and map:

- RAW -> `VideoDeliveryKind.RAW`
- LOG -> `VideoDeliveryKind.LOG`
- HDR -> `VideoDeliveryKind.HDR`
- otherwise -> `VideoDeliveryKind.SDR`

Set MediaCodec metadata:

- SDR/LOG/RAW monitoring output -> Rec.709 + SDR transfer unless lower policy declares HDR
- HDR HLG -> BT.2020 + HLG
- HDR PQ -> BT.2020 + ST2084

- [ ] **Step 3: Build graph signature**

Signature must include only session/recording-affecting fields:

```kotlin
mode|cinema|delivery|hdr|log|raw|tenBit|previewWxH|encoderWxH|fps|colorStandard|colorTransfer|colorRange|cameraRequestFamily|denoise|sharpness|pipelineContract.sessionSignature
```

Do not include display-only monitoring label.

- [ ] **Step 4: Run tests and verify GREEN**

Run the focused graph tests.

### Task 3: Wire Graph Into CameraViewModel

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] **Step 1: Add helper to build active graph contract**

Add a private helper:

```kotlin
private fun activeVideoPipelineContract(): VideoPipelineContract =
    VideoPipelineGraphController.resolve(...)
```

Use current mode, cinema mode, RAW active state, LOG flag, HDR mode, color space, preview/encoder dimensions, FPS, and recording state.

- [ ] **Step 2: Feed render-quality hint from graph**

Replace direct `VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(...)` call with `activeVideoPipelineContract().recordingGradePreview`.

- [ ] **Step 3: Feed recording color identity from graph**

In `syncEffectiveRecordingColorProfile`, use graph delivery identity to guarantee:

- LOG sets `videoHdrMode = OFF`
- RAW sets `videoHdrMode = OFF`
- normal SDR sets `videoHdrMode = OFF`
- HDR uses actual recording transport from `VideoHdrRecordingTransportPolicy`

- [ ] **Step 4: Add graph signature logs**

At recording color sync and recording start, log:

```text
Video graph contract=<signature>
```

### Task 4: Verification

**Files:**
- Test: all changed tests

- [ ] **Step 1: Run focused tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.VideoPipelineGraphControllerTest --tests com.neoproc.neoproccamx.ui.VideoRenderProfileQualityHintPolicyTest --tests com.neoproc.neoproccamx.ui.VideoModePipelineContractPolicyTest
```

- [ ] **Step 2: Run full unit tests and assemble**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 3: Run lint**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:lintDebug
```

---

## Self-Review

Spec coverage:

- Top-level graph controller: covered by Tasks 1-2.
- Preview/recording parity identity: covered by Task 3.
- HDR/LOG/RAW separation: covered by tests and Task 3.
- QA graph signature: covered by Task 3.

No placeholders are intentionally left. Device QA is a later step after this code-level Phase 1 is green.
