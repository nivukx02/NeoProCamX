# RAW_SENSOR Pipeline Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade RAW mode so it truthfully uses the strongest exposed Camera2 `RAW_SENSOR` path without fake quality claims or unsafe memory pressure.

**Architecture:** Add small pure policies for RAW_SENSOR quality classification, RAW ImageReader depth, and still-capture target admission. Wire those policies into the existing `CameraStreamPlanResolver` and `CameraController` instead of adding hidden state or UI-only labels.

**Tech Stack:** Kotlin, Android Camera2, `ImageReader`, existing Gradle/JUnit test setup.

---

### Task 1: RAW_SENSOR Truth Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/RawSensorPipelinePolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/RawSensorPipelinePolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraCapabilityContract.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraCapabilityContractTest.kt`

- [ ] Write failing tests for true original RAW, limited ISP RAW, and cropped/binned RAW.
- [ ] Implement `RawSensorPipelinePolicy.resolve`.
- [ ] Route RAW mode planning through the policy so `TRUE_RAW_SENSOR` is only used for full-sensor RAW with manual sensor control and complete ISP-off support.

### Task 2: RAW Reader Depth Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/RawSensorReaderDepthPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/RawSensorReaderDepthPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`

- [ ] Write failing tests for 12MP recording, ultra-high-res recording, and preview/still companion depth.
- [ ] Implement adaptive depth from RAW frame byte cost.
- [ ] Use adaptive depth when constructing the RAW `ImageReader`.

### Task 3: RAW Still Target Admission

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/RawStillCaptureTargetPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/RawStillCaptureTargetPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`

- [ ] Write failing tests proving RAW still requests do not silently fall back to JPEG-only.
- [ ] Implement target admission policy.
- [ ] Use it in single still and still burst capture.

### Task 4: Verification

**Files:**
- No new files.

- [ ] Run focused RAW tests.
- [ ] Run full debug unit tests.
- [ ] Run `:app:assembleDebug`.
- [ ] Run `:app:lintDebug`.
- [ ] Install debug APK to the connected device.
- [ ] Launch the app and inspect Camera2 logs for RAW policy diagnostics and crashes.
