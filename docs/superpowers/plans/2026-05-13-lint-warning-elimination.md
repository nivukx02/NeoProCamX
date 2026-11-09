# Lint Warning Elimination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Android lint output from 272 warnings to a clean report while preserving camera behavior and build stability.

**Architecture:** Fix concrete app issues in source/resources first, then configure lint only for advisory checks that are not app defects. Avoid deleting resources or upgrading major build dependencies in a dirty tree unless verification proves the change safe.

**Tech Stack:** Android Gradle Plugin, Android lint, Kotlin, Android resources, Gradle Groovy build files.

---

### Task 1: Safe Lint Policy

**Files:**
- Modify: `app/build.gradle`

- [ ] Add a targeted lint policy for advisory/non-runtime checks: dependency freshness, obsolete SDK guards in a minSdk 29 app, unused resource advisory during the resource rebuild, launcher icon aesthetic checks, high-sampling-rate permission review notice, and ChromeOS x86_64 support for an arm64-only camera app.
- [ ] Keep `abortOnError true`, XML/TXT/HTML reports, and build failure on real lint errors.
- [ ] Run `.\gradlew.bat :app:lintDebug --no-daemon --console=plain`.

### Task 2: Real Resource/Layout Fixes

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `app/src/main/res/drawable/ic_launcher_monochrome.xml`

- [ ] Remove root overdraw from `activity_main.xml` if the theme already paints the background.
- [ ] Add a monochrome adaptive icon layer.
- [ ] Run lint again.

### Task 3: Vector Performance

**Files:**
- Modify: `app/build.gradle`

- [ ] Treat the single long vector path as a low-risk advisory unless replacing the icon is necessary for lint cleanliness.
- [ ] Prefer a targeted lint exclusion over changing icon geometry without visual review.

### Task 4: Verification

**Commands:**
- `.\gradlew.bat :app:lintDebug --no-daemon --console=plain`
- `.\gradlew.bat build --no-daemon --console=plain`
- `adb devices`

- [ ] Confirm lint reports zero warnings or document any remaining warnings with exact IDs.
- [ ] Confirm full build succeeds.
- [ ] Confirm deploy target availability; install only if a device/emulator is attached.
