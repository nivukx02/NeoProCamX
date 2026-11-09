# V14 Production Readiness Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a measurable production-readiness gate for build, warnings, APK, ADB launch, and critical camera features.

**Architecture:** A small Kotlin policy models required checks and truthful outcomes. A PowerShell runner executes Gradle, diff, APK, and ADB evidence, then writes a report. Tests prove failed and unproven checks cannot be mislabeled production-ready.

**Tech Stack:** Kotlin, JUnit, Gradle, PowerShell, ADB.

---

### Task 1: Add Gate Policy Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/quality/ProductionReadinessGatePolicyTest.kt`

- [ ] Add tests for all-pass, required-fail, required-not-proven, optional-not-proven, and default critical feature coverage.
- [ ] Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.quality.ProductionReadinessGatePolicyTest" --no-daemon --no-configuration-cache --console=plain
```

Expected before implementation: compile failure because the policy does not exist.

### Task 2: Implement Gate Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/quality/ProductionReadinessGatePolicy.kt`

- [ ] Add `ProductionGateStatus`, `ProductionGateCategory`, `ProductionGateCheck`, and `ProductionReadinessReport`.
- [ ] Add `ProductionReadinessGatePolicy.evaluate`.
- [ ] Add `ProductionReadinessGatePolicy.defaultRequiredChecks`.
- [ ] Run the focused policy test until it passes.

### Task 3: Add Runner

**Files:**
- Create: `tools/quality/run-v14-production-gate.ps1`

- [ ] Run Gradle with `--warning-mode=fail`.
- [ ] Run `git diff --check`.
- [ ] Validate APK file exists and has bytes.
- [ ] Detect ADB device.
- [ ] Install APK when a device is connected.
- [ ] Launch `com.neoproc.neoproccamx/.ui.MainActivity`.
- [ ] Verify resumed foreground activity.
- [ ] Write `production-readiness-report.md` and `production-readiness-report.json`.

### Task 4: Verify

**Files:**
- No code changes.

- [ ] Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode=fail --no-daemon --no-configuration-cache --console=plain
```

- [ ] Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\quality\run-v14-production-gate.ps1 -AdbPath "C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe"
```

Expected: the runner prints the final status and writes reports. If no device is connected, the final status is `NOT_PROVEN`.
