# Recording Pipeline Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make recording session, encoder, and feed-path decisions consume the Phase 1 graph contract instead of raw UI flags.

**Architecture:** Add `RecordingPipelineParityPolicy` as the recording-side adapter for `VideoPipelineContract`. Wire `CameraViewModel` to use the resulting plan for session-plan inputs, recording selection, bitrate hints, prearm signatures, feed-path selection, and QA logs.

**Tech Stack:** Kotlin, Android unit tests, existing Camera2/MediaCodec/Vulkan project policies.

---

### Task 1: Add Recording Parity Policy Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt`

- [ ] **Step 1: Write failing tests**

Cover SDR, HDR-over-LOG, LOG, RAW, and unsupported RAW parity.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest
```

Expected: compile failure because `RecordingPipelineParityPolicy` is not defined.

### Task 2: Implement Recording Parity Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`

- [ ] **Step 1: Add plan data class**

The plan contains delivery kind, feed path, session-plan booleans, encoder identity fields, and parity signature.

- [ ] **Step 2: Implement resolver**

Use `VideoPipelineContract.deliveryKind` as the source of truth. RAW is active only when `rawSupported` is true; otherwise it falls back to SDR processed parity.

- [ ] **Step 3: Run tests and verify GREEN**

Run the focused policy test.

### Task 3: Wire CameraViewModel To Parity Plan

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] **Step 1: Add active parity helper**

Build `RecordingPipelineParityPolicy.resolve(...)` from `activeVideoPipelineContract()`.

- [ ] **Step 2: Replace session-plan raw/log/HDR inputs**

Use parity-plan fields anywhere recording prepares codec/bit-depth/session decisions.

- [ ] **Step 3: Replace prearm signature inputs**

Use effective parity identity so stale UI state cannot reuse an incompatible persistent input surface.

- [ ] **Step 4: Replace feed-path resolution**

Use the parity plan’s feed path at recording start.

- [ ] **Step 5: Add parity signature logs**

Log effective recording parity at prearm and recording start.

### Task 4: Verification

**Files:**
- Test changed policy and existing graph/path tests.

- [ ] **Step 1: Run focused tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest --tests com.neoproc.neoproccamx.ui.VideoPipelineGraphControllerTest --tests com.neoproc.neoproccamx.ui.RecordingPreviewContinuityPolicyTest --tests com.neoproc.neoproccamx.rendering.VulkanPreviewRecordingParityPolicyTest
```

- [ ] **Step 2: Run full unit tests and assemble**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 3: Run lint**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon --console=plain --max-workers=1 :app:lintDebug
```
