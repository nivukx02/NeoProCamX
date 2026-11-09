# Camera Contract Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a truthful camera capability contract that plans full-sensor readout, ISP-bypass routing, RAW/YUV/JPEG/encoder streams, and fallback behavior before broader project architecture cleanup.

**Architecture:** Phase 1 creates pure camera-planning policies that convert device capability inventory into a stream/readout contract. Phase 2 uses that stable contract to reduce CameraController/ViewModel coupling and document package boundaries without removing existing features.

**Tech Stack:** Kotlin, Android Camera2 model types, JUnit unit tests, existing Gradle Android test tasks.

---

### Task 1: Camera Contract Model And Fallback Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraCapabilityContract.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraCapabilityContractTest.kt`

- [x] **Step 1: Write failing tests**

Create tests that prove RAW is the only true ISP-bypass path, high-resolution YUV is a best-effort fallback, and encoder-only devices are never labeled full sensor.

- [x] **Step 2: Run the focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraCapabilityContractTest --no-daemon --console=plain`

Expected: compile failure because `CameraStreamPlanResolver` and model types do not exist.

Note: the focused `--tests` command returned `Access is denied.` in this Windows shell, so the
red/green checks used the full `:app:testDebugUnitTest` task.

- [x] **Step 3: Implement minimal contract layer**

Add capability inventory, ISP-bypass classification, readout tier, stream plan, and fallback-chain types. Implement `CameraStreamPlanResolver.resolve(...)`.

- [x] **Step 4: Run the focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraCapabilityContractTest --no-daemon --console=plain`

Expected: test pass.

### Task 2: Architecture Boundary Documentation

**Files:**
- Create: `docs/architecture/camera-contract.md`

- [x] **Step 1: Document the stable camera contract**

Document which packages own capability detection, stream planning, request application, rendering, recording, and UI coordination.

- [x] **Step 2: Verify docs do not claim impossible behavior**

Ensure the docs state that full sensor and ISP bypass are device-capability dependent and that non-RAW paths are best-effort.

### Task 3: Regression Verification

**Files:**
- Existing Gradle project files

- [x] **Step 1: Run unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`

- [x] **Step 2: Run debug build**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon --console=plain`

- [x] **Step 3: Report hardware boundary**

Run: `adb devices`. If no device is attached, report that camera runtime profiling was not possible.
