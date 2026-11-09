# Preview Dock and Grid Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align all camera docks and overlays to the same aspect-accurate preview rectangle.

**Architecture:** Reuse `PreviewContentBoundsResolver` in overlay geometry, then feed those resolved edges into a pure dock-layout policy. `MainActivity` applies the resulting translations after layout without changing dock contents or visual styling.

**Tech Stack:** Kotlin, Android Views/ConstraintLayout, JUnit 4, Gradle.

---

### Task 1: Reproduce the 4:3 grid mismatch

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/overlays/FocusOverlayGeometryTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/overlays/FocusOverlayGeometry.kt`

- [ ] Add a test asserting that a portrait `3:4` preview in a `1000x2000` viewport resolves to `top=186.6667` and `bottom=1520`, matching the renderer's upward-biased bounds.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.overlays.FocusOverlayGeometryTest" --console=plain` and confirm the centered overlay math fails the new assertion.
- [ ] Replace duplicated fitted-rectangle math with `PreviewContentBoundsResolver.resolve(..., fillViewport=false)` while preserving full-viewport behavior when fill mode is active or no aspect is available.
- [ ] Rerun the focused test and confirm it passes.

### Task 2: Define preview-anchored dock placement

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraDockLayoutPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraDockLayoutPolicy.kt`

- [ ] Add tests for the approved `8dp / 24dp / 8dp` anchor spacing and for bottom-overflow correction that moves the lower two docks together.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.CameraDockLayoutPolicyTest" --console=plain` and confirm the missing anchor-plan API fails.
- [ ] Add a pure `resolveAnchoredTranslations` function returning aspect, mode, and shutter translations from preview and measured view bounds.
- [ ] Rerun the focused policy test and confirm it passes.

### Task 3: Apply the dock plan

**Files:**
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] Add named dimensions for the three approved edge gaps.
- [ ] In `syncOverlayPreviewArea`, resolve bounds through `PreviewContentBoundsResolver`, schedule placement after layout, and apply policy translations to `topStatusPanel`, `modeGroup`, and `bottomBar`.
- [ ] Preserve the existing capture-row behavior and reschedule anchoring after control visibility changes.
- [ ] Run both focused test classes.

### Task 4: Verify the integrated UI

**Files:**
- Output: `qa-artifacts/neoprocamx-ui-dock-grid-aligned.png`

- [ ] Run `gradlew.bat :app:testDebugUnitTest --console=plain` and confirm zero failures.
- [ ] Run `gradlew.bat :app:assembleDebug --console=plain` and confirm a successful build.
- [ ] Install and launch the debug APK on the connected device.
- [ ] Capture a screenshot and verify the grid ends at the 4:3 frame and the three docks match the approved hierarchy.
