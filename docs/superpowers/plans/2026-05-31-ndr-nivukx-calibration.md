# NDR NIVUKX Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic calibration chart and analyzer contract for measuring NDR10+ and NIVUKX Vision tone/color errors before applying correction.

**Architecture:** The calibration chart is a project-owned PNG asset with matching Kotlin metadata. The analyzer consumes measured per-patch RGB values from a captured frame and emits a bounded correction profile for red overflow, highlight rolloff, shadow lift, skin hue, sky hue, and saturation.

**Tech Stack:** Kotlin/JUnit for analyzer contracts, Java AWT generator for deterministic PNG/JSON deliverables, Android assets for the chart/reference files.

---

### Task 1: Calibration Metadata And Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/color/NdrNivukxCalibrationAnalyzerTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/color/NdrNivukxCalibrationTarget.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/color/NdrNivukxCalibrationAnalyzer.kt`

- [ ] Write failing tests that require at least 36 uniquely named patches, required patch roles, bounded correction output, and red-overflow/highlight/skin/sky detection.
- [ ] Run targeted tests and confirm unresolved production types fail.
- [ ] Implement metadata and analyzer with deterministic, bounded math.
- [ ] Re-run targeted tests and confirm pass.

### Task 2: Calibration Asset Generator

**Files:**
- Create: `tools/calibration/GenerateNdrNivukxCalibrationTarget.java`
- Generate: `app/src/main/assets/calibration/ndr_nivukx_calibration_target.png`
- Generate: `app/src/main/assets/calibration/ndr_nivukx_calibration_reference.json`
- Generate: `deliverables/ndr_nivukx_calibration_capture_instructions.md`

- [ ] Add a Java generator that draws the chart from the same reference patch ids and colors.
- [ ] Generate PNG and JSON assets.
- [ ] Verify assets exist and JSON contains required patch ids.

### Task 3: Verification

**Files:**
- Test: `app/src/test/java/com/neoproc/neoproccamx/color/NdrNivukxCalibrationAnalyzerTest.kt`

- [ ] Run targeted unit test.
- [ ] Run `:app:assembleDebug`.
- [ ] Report the exact chart path and capture instructions.
