# Vulkan Handoff And Encoding Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make processed GPU video runtime-verifiable by repairing preview surface ownership and aligning bitrate and bit-depth decisions with hardware evidence.

**Architecture:** Processed encoded modes keep the required Vulkan encoder route already implemented; RAW and NPV10 masters remain direct truthful captures. This increment waits for Camera2 session release before acquiring a `SurfaceView` from Vulkan, separates ordinary and Cinema Master bitrate envelopes at the final encoder boundary, and bases bit-depth claims on advertised Android transport or RAW12 output formats.

**Tech Stack:** Kotlin, Android Camera2, Vulkan/ANativeWindow, MediaCodec, JUnit4, Gradle, adb/logcat/ffprobe.

---

## Shared Worktree Constraint

The active `master` checkout contains the in-progress camera rewrite and relevant
uncommitted files from earlier approved work. The user approved layering this
fix onto that shared state; do not revert unrelated changes.

### Task 1: Wait For Camera2 Surface Release Before Vulkan Acquisition

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Add a failing source-contract test**

Require `CameraController.refreshOutputsAndSession` to accept
`onPreviousSessionClosed`, store callbacks for the previous session, and run
them only from `CameraCaptureSession.StateCallback.onClosed`. Require
`CameraViewModel` to request this release callback before calling
`bindPreviewSurface` for the native Vulkan handoff.

- [ ] **Step 2: Run the focused test and confirm the missing release contract fails**

Run:
`.\gradlew.bat :app:testDebugUnitTest --tests '*RecordingStartSessionRefreshContractTest' --no-daemon --console=plain`

- [ ] **Step 3: Implement the release callback lifecycle**

Add an identity-keyed callback map on the camera thread. When a replacement
session is configured, register the release callback before closing the prior
session; its `onClosed` removes and invokes the callback. In the ViewModel,
perform Vulkan binding from that callback rather than the earlier
`onConfigured` callback, and rebuild direct preview if Vulkan binding fails.

- [ ] **Step 4: Run the focused test to green**

### Task 2: Apply Truthful Sustainable Bitrate Tiers

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingUserBitratePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingUserBitratePolicyTest.kt`

- [ ] **Step 1: Add failing tests for ordinary and master ceilings**

Ordinary recording must clamp to `30/80/150 Mbps` at 1080p/4K/8K.
Cinema Master must clamp to `45/100/180 Mbps`. Encoder capability remains the
final upper bound, and an impossible configured floor may not exceed that
upper bound.

- [ ] **Step 2: Run the policy test and confirm ordinary mode currently fails**

- [ ] **Step 3: Add an explicit `cinemaMasteringEnabled` input and wire it from `RecordingManager`**

Compute the resolution ceiling from the active bitrate mode, clamp all floors
inside the effective upper limit, and lower the manager's legacy minimum floor
to sustainable ordinary floors so runtime ABR and thermal handling do not
demand a bitrate above the ordinary envelope.

- [ ] **Step 4: Run focused bitrate tests to green**

### Task 3: Replace Bit-Depth Guessing With Advertised Evidence

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/BitDepthPrecisionPolicy.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/camera/BitDepthPrecisionValidationPolicyTest.kt`

- [ ] **Step 1: Add failing pure-policy tests**

Require ten-bit color support only when `HdrDynamicRangePolicy.Support`
contains HLG10, HDR10, or HDR10+, and require twelve-bit RAW only when RAW
capability and an advertised `RAW12` output format are both present.

- [ ] **Step 2: Run the focused test and confirm the evidence policy is absent**

- [ ] **Step 3: Implement the pure evidence policy and route hardware checks through it**

Remove the tone-map and ultra-high-resolution heuristics from bit-depth
claims. Use `HdrDynamicRangePolicy.detectSupport(characteristics)` for
processed ten-bit transport and `StreamConfigurationMap.outputFormats` plus
RAW capability for RAW12 evidence.

- [ ] **Step 4: Run focused tests to green**

### Task 4: Verify And Deploy

**Files:**
- No additional production files unless verification exposes a specific defect.

- [ ] **Step 1: Run all unit tests and assemble the debug APK**

Run:
`.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain`

- [ ] **Step 2: Install and launch on the connected phone**

Use `adb install -r`, launch `com.neoproc.neoproccamx/.ui.MainActivity`, and
capture logcat.

- [ ] **Step 3: Record processed-mode evidence when the phone is connected**

Require logs showing the internal hardware-import encoder route and imported
frame count above zero. Pull a produced clip and run `ffprobe` to check actual
resolution, codec, transfer/color metadata, and measured bitrate against the
active ordinary or Cinema Master ceiling.

- [ ] **Step 4: Report truthfully**

Do not claim zero sensor noise, camera-system superiority, or completed
device validation without the corresponding recording and metadata evidence.
