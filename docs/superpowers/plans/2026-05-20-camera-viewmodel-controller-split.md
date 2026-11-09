# Camera ViewModel Controller Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract mode, recording, preview, and settings responsibilities from `CameraViewModel` into focused controllers without changing external callers.

**Architecture:** `CameraViewModel` stays as the public Android ViewModel facade. Each controller receives a narrow host interface implemented by the facade and owns representative logic for its domain.

**Tech Stack:** Kotlin, Android ViewModel, StateFlow, existing Camera2/recording/rendering policies, Gradle unit tests.

---

### Task 1: Source Contract Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelControllerSplitContractTest.kt`

- [ ] Add tests that assert four controller files exist.
- [ ] Add tests that assert representative facade methods delegate to `modeController`, `recordingController`, `previewController`, and `settingsController`.
- [ ] Run the focused test and verify it fails before controllers exist.

### Task 2: Settings Controller

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] Add `CameraSettingsControllerHost`.
- [ ] Move monitoring and setting toggle behavior into `CameraSettingsController`.
- [ ] Delegate representative facade methods from `CameraViewModel`.
- [ ] Run settings and contract tests.

### Task 3: Preview Controller

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] Add `CameraPreviewControllerHost`.
- [ ] Move preview aspect entrypoints and preview source aspect calculation.
- [ ] Delegate representative facade methods from `CameraViewModel`.
- [ ] Run preview and contract tests.

### Task 4: Recording Controller

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraRecordingController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] Add `CameraRecordingControllerHost`.
- [ ] Move recording configuration entrypoints and public recording start/stop/toggle delegation.
- [ ] Delegate representative facade methods from `CameraViewModel`.
- [ ] Run recording and contract tests.

### Task 5: Mode Controller

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] Add `CameraModeControllerHost`.
- [ ] Move app/cinema/raw compression mode entrypoints into the mode controller.
- [ ] Delegate representative facade methods from `CameraViewModel`.
- [ ] Run mode and contract tests.

### Task 6: Full Verification

**Files:**
- No new source files.

- [ ] Run `:app:testDebugUnitTest`.
- [ ] Run `:app:lintDebug`.
- [ ] Run `:app:assembleDebug :app:assembleRelease`.
- [ ] Report remaining risk and exact verification output.
