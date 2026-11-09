# Code Quality 10 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved code-quality-first milestone so NeoProCamX has cleaner repository state, truthful hardware language, fewer legacy aliases, and smaller UI/ViewModel ownership boundaries without intentional behavior changes.

**Architecture:** Treat this as a no-behavior-change refactor. Start with repository and language guardrails, then remove compatibility debt, then extract low-risk pure policies and workflow controllers from `CameraViewModel` and `MainActivity` while keeping their public APIs stable.

**Tech Stack:** Kotlin, Android ViewBinding, Android Camera2-facing app code, JUnit4 unit tests, Gradle Android tasks, PowerShell quality scripts.

---

## Scope Check

This plan implements only Milestone 1 from `docs/superpowers/specs/2026-05-11-code-quality-10-upgrade-design.md`. Camera-core performance work and product/UI polish remain separate future milestones. This milestone is still one coherent implementation unit because the repo hygiene, claim audit, alias cleanup, and first extractions all protect later camera work and share the same no-behavior-change verification gate.

## File Structure

- Modify: `.gitignore` - ignore generated/local artifacts that currently clutter `git status`.
- Create: `docs/architecture/artifact-policy.md` - short source-vs-artifact policy for validation output.
- Create: `docs/architecture/hardware-capability-language.md` - wording rules for hardware capability claims.
- Create: `tools/quality/assert-truthful-language.ps1` - scanner for over-strong comments and string literals.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt` - replace old `Video*` LOG aliases with `Neo*` names and remove `ToneCurveChannel`.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/VideoLogProfilePolicy.kt` - remove deprecated `Video*` typealiases after callers move to `Neo*` names.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt` - replace imports and usages of deprecated LOG aliases.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt` - replace deprecated LOG alias usage.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt` - remove unused deprecated `setPreviewTransform`.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelHost.kt` - remove `CameraRecordingSelection` typealias.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt` - use direct recording-selection type, extract manual dispatch and startup job coordination.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt` - extract debug parsing and permission policy usage.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/ToneCurveView.kt` - use `ColorChannel` directly.
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicy.kt` - pure zoom request clamping.
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt` - owns manual-control background thread.
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraStartupCoordinator.kt` - owns startup job scheduling.
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/DebugCommandParser.kt` - pure parser for debug command extras.
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicy.kt` - pure permission list/result policy.
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/DebugCommandParserTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicyTest.kt`
- Modify tests using old LOG aliases:
  - `app/src/test/java/com/neoproc/neoproccamx/color/VideoLogProfilePolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryModeTest.kt`

### Task 1: Repository Hygiene And Artifact Policy

**Files:**
- Modify: `.gitignore`
- Create: `docs/architecture/artifact-policy.md`

- [ ] **Step 1: Verify current ignore gaps**

Run:

```powershell
git check-ignore -v node_modules .idea .kotlin .superpowers test-results test-results-20260511-raw-writer-fix-focused local.properties
```

Expected: at least one of those paths is not reported, proving current ignore coverage is incomplete.

- [ ] **Step 2: Update `.gitignore`**

Replace `.gitignore` with this content:

```gitignore
/.gradle/
/.gradle-*/
/.android-*/
/.android-home/
/.home-user/
/.sandbox-home/
/.codex-tmp/
/.codex-artifacts/
/.idea/
/.kotlin/
/.superpowers/

/build/
/app/build/
/*/build/

/node_modules/

/test-results/
/test-results*/
/validation-artifacts/
/*.log
/*.mp4
/*.jpg
/*.jpeg
/*.png
/*.txt
/*.xml
/*.zip
/*.tar.gz
/*.tgz
/*.tflite
/fastdepth_extract/
/fastdepth_extract_py/

# Local Android/Gradle machine config
/local.properties
/analytics.settings

# Signing - keep keystore and credentials out of source control
/keystore/
keystore.properties
*.jks
*.keystore

.aider*
```

- [ ] **Step 3: Write artifact policy doc**

Create `docs/architecture/artifact-policy.md`:

```markdown
# Artifact Policy

NeoProCamX keeps source, build configuration, tests, docs, native code, and intentional fixtures in Git.

Generated validation output does not belong in Git:

- `test-results*/`
- screenshots and camera sample JPG/PNG files at the repository root
- logcat dumps and temporary logs
- APK build output
- local IDE/cache folders
- dependency folders such as `node_modules/`

Validation artifacts should be written under a timestamped `test-results-YYYYMMDD-*` folder during a run, reviewed for evidence, then left untracked. If an artifact becomes a permanent fixture, move it into a named fixture directory and document why it is source-controlled.
```

- [ ] **Step 4: Verify ignore coverage**

Run:

```powershell
git check-ignore -v node_modules .idea .kotlin .superpowers test-results test-results-20260511-raw-writer-fix-focused local.properties analytics.settings
```

Expected: every listed path is reported with a matching `.gitignore` rule.

- [ ] **Step 5: Commit repo hygiene**

Run:

```powershell
git add .gitignore docs/architecture/artifact-policy.md
git commit -m "chore: document artifact hygiene"
```

### Task 2: Truthful Hardware Language Guard

**Files:**
- Create: `docs/architecture/hardware-capability-language.md`
- Create: `tools/quality/assert-truthful-language.ps1`
- Modify comments and user-visible strings in the production files reported by the scanner.

- [ ] **Step 1: Add hardware capability wording policy**

Create `docs/architecture/hardware-capability-language.md`:

```markdown
# Hardware Capability Language

NeoProCamX can request high-end camera, encoder, Vulkan, RAW, HDR, and processing paths. Android devices decide which paths are exposed and stable for a specific session.

Use these words for implementation comments, diagnostics, and docs:

- `requested` when the app asks Camera2, MediaCodec, Vulkan, or native code for a capability.
- `device-reported` when the capability comes from Camera2 characteristics, MediaCodec capabilities, Vulkan support, or runtime probes.
- `verified for this session` when the current session actually configured and ran with the capability.
- `fallback applied` when the app continues through a lower capability path.
- `best-effort` when the app improves quality but cannot prove full hardware behavior.

Avoid absolute claims unless a runtime check proves them for the active device/session:

- `guaranteed`
- `complete ISP bypass`
- `bit-perfect`
- `true zero-copy`
- `cinema-grade`
- `hardware-native`
- `lossless`

Product-facing language may remain ambitious. Engineering comments and diagnostics must remain factual.
```

- [ ] **Step 2: Add scanner**

Create `tools/quality/assert-truthful-language.ps1`:

```powershell
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sourceRoot = Join-Path $root 'app/src/main/java'
$forbidden = @(
    'guaranteed',
    'complete ISP bypass',
    'bit-perfect',
    'true zero-copy',
    'cinema-grade',
    'hardware-native',
    'lossless'
)

$violations = New-Object System.Collections.Generic.List[string]
$files = Get-ChildItem -Path $sourceRoot -Recurse -Include *.kt

foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1)
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber += 1
        $isComment = $line -match '^\s*(//|/\*|\*)'
        $hasQuotedText = $line -match '"'
        if (-not ($isComment -or $hasQuotedText)) {
            continue
        }
        foreach ($term in $forbidden) {
            if ($line.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $violations.Add("${relative}:${lineNumber}: ${term}: ${line.Trim()}")
            }
        }
    }
}

if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'Truthful hardware language scan passed.'
```

- [ ] **Step 3: Run scanner to establish RED**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools/quality/assert-truthful-language.ps1
```

Expected: FAIL with reported comments or strings containing over-strong language.

- [ ] **Step 4: Replace over-strong wording**

Edit only reported comments and user-visible strings. Use these replacements:

```text
guaranteed -> verified when supported
complete ISP bypass -> requested ISP processing disablement
bit-perfect -> high-bitrate quality-preserving
true zero-copy -> zero-copy candidate
cinema-grade -> cinema-oriented
hardware-native -> hardware-supported when exposed
lossless -> quality-preserving
```

Keep class/file names unchanged in this task unless the scanner reports a comment or quoted string. Rename identifiers only in Task 3.

- [ ] **Step 5: Verify scanner passes**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools/quality/assert-truthful-language.ps1
```

Expected: PASS with `Truthful hardware language scan passed.`

- [ ] **Step 6: Commit truthfulness guard**

Run:

```powershell
git add docs/architecture/hardware-capability-language.md tools/quality/assert-truthful-language.ps1 app/src/main/java
git commit -m "chore: enforce truthful hardware wording"
```

### Task 3: Deprecated Alias Cleanup

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/VideoLogProfilePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelHost.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/ToneCurveView.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/color/VideoLogProfilePolicyTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryModeTest.kt`

- [ ] **Step 1: Verify current alias debt**

Run:

```powershell
Get-ChildItem -Path app\src -Recurse -Include *.kt |
    Select-String -Pattern '@Deprecated|typealias|VideoLogCurve|VideoLogLookProfile|VideoTenBitDitherMode|VideoTenBitRenderPath|VideoTenBitHardwareSupport|VideoLogProfilePolicy|ToneCurveChannel|CameraRecordingSelection|setPreviewTransform' |
    Select-Object Path,LineNumber,Line
```

Expected: reports deprecated aliases in `VideoLogProfilePolicy.kt`, `ColorScience.kt`, `CameraViewModelHost.kt`, and unused `CameraVulkanPreviewView.setPreviewTransform`.

- [ ] **Step 2: Update LOG profile references**

Replace production and test references:

```text
VideoLogCurve -> NeoLogCurve
VideoLogLookProfile -> NeoLogLookProfile
VideoTenBitDitherMode -> NeoTenBitDitherMode
VideoTenBitRenderPath -> NeoTenBitRenderPath
VideoTenBitHardwareSupport -> NeoTenBitHardwareSupport
VideoLogProfilePolicy -> NeoLogProfilePolicy
```

In `ColorScience.kt`, update imports/usages and keep public behavior unchanged. In `SceneRenderProfileEngine.kt`, `RecordingManager.kt`, and the three listed test files, update imports/usages to the same `Neo*` symbols.

- [ ] **Step 3: Remove LOG typealiases**

Delete these declarations from `VideoLogProfilePolicy.kt`:

```kotlin
@Deprecated("Use NeoLogCurve", ReplaceWith("NeoLogCurve"))
internal typealias VideoLogCurve = NeoLogCurve

@Deprecated("Use NeoLogLookProfile", ReplaceWith("NeoLogLookProfile"))
internal typealias VideoLogLookProfile = NeoLogLookProfile

@Deprecated("Use NeoTenBitDitherMode", ReplaceWith("NeoTenBitDitherMode"))
internal typealias VideoTenBitDitherMode = NeoTenBitDitherMode

@Deprecated("Use NeoTenBitRenderPath", ReplaceWith("NeoTenBitRenderPath"))
internal typealias VideoTenBitRenderPath = NeoTenBitRenderPath

@Deprecated("Use NeoTenBitHardwareSupport", ReplaceWith("NeoTenBitHardwareSupport"))
internal typealias VideoTenBitHardwareSupport = NeoTenBitHardwareSupport

@Deprecated("Use NeoLogProfilePolicy", ReplaceWith("NeoLogProfilePolicy"))
internal typealias VideoLogProfilePolicy = NeoLogProfilePolicy
```

- [ ] **Step 4: Remove recording-selection typealias**

In `CameraViewModelHost.kt`, remove:

```kotlin
typealias CameraRecordingSelection = ResolvedRecordingSelection
```

Change:

```kotlin
val lastRecordingSelection: CameraRecordingSelection?
```

to:

```kotlin
val lastRecordingSelection: ResolvedRecordingSelection?
```

In `CameraViewModel.kt`, change the host implementation property to:

```kotlin
override val lastRecordingSelection: ResolvedRecordingSelection?
    get() = this@CameraViewModel.lastRecordingSelection
```

- [ ] **Step 5: Remove tone-curve alias**

In `ColorScience.kt`, remove:

```kotlin
/** Alias for ColorChannel used by ToneCurveView. */
typealias ToneCurveChannel = ColorChannel

object ToneCurveChannelValues {
    val ALL   = ColorChannel.ALL
    val RED   = ColorChannel.R
    val GREEN = ColorChannel.G
    val BLUE  = ColorChannel.B
}
```

In `ToneCurveView.kt`, replace `ToneCurveChannel` with `ColorChannel` and update the import to:

```kotlin
import com.neoproc.neoproccamx.color.ColorChannel
```

- [ ] **Step 6: Remove unused preview transform alias**

In `CameraVulkanPreviewView.kt`, delete:

```kotlin
@Deprecated("Use setStreamGeometry(width, height, sensorOrientation) instead")
fun setPreviewTransform(width: Int, height: Int, sensorOrientation: Int) =
    setStreamGeometry(width, height, sensorOrientation)
```

- [ ] **Step 7: Verify aliases are gone**

Run:

```powershell
Get-ChildItem -Path app\src\main\java -Recurse -Include *.kt |
    Select-String -Pattern '@Deprecated|typealias|VideoLogCurve|VideoLogLookProfile|VideoTenBitDitherMode|VideoTenBitRenderPath|VideoTenBitHardwareSupport|VideoLogProfilePolicy|ToneCurveChannel|CameraRecordingSelection'
```

Expected: no matches in production Kotlin files.

- [ ] **Step 8: Run focused tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.color.VideoLogProfilePolicyTest --tests com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest --tests com.neoproc.neoproccamx.rendering.VulkanToneDeliveryModeTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 9: Commit alias cleanup**

Run:

```powershell
git add app/src/main/java app/src/test/java
git commit -m "refactor: remove deprecated compatibility aliases"
```

### Task 4: CameraViewModel Manual Dispatch Extraction

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicyTest.kt`

- [ ] **Step 1: Write failing zoom policy tests**

Create `CameraZoomRequestPolicyTest.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomRequestPolicyTest {

    @Test
    fun resolve_clampsRequestedZoomToHardwareRange() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 12.0f,
            minZoom = 1.0f,
            maxZoom = 8.0f,
            beyondImaxActive = false
        )

        assertEquals(8.0f, result, 0.0001f)
    }

    @Test
    fun resolve_forcesOneTimesZoomWhenBeyondImaxIsActive() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 3.5f,
            minZoom = 1.0f,
            maxZoom = 8.0f,
            beyondImaxActive = true
        )

        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun resolve_handlesInvalidHardwareRangeByReturningRequestedZoom() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 2.0f,
            minZoom = 4.0f,
            maxZoom = 1.0f,
            beyondImaxActive = false
        )

        assertEquals(2.0f, result, 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraZoomRequestPolicyTest --no-daemon --console=plain
```

Expected: FAIL because `CameraZoomRequestPolicy` does not exist.

- [ ] **Step 3: Implement zoom policy**

Create `CameraZoomRequestPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

internal object CameraZoomRequestPolicy {
    fun resolve(
        requestedZoom: Float,
        minZoom: Float,
        maxZoom: Float,
        beyondImaxActive: Boolean
    ): Float {
        if (minZoom > maxZoom) return requestedZoom
        val effectiveRequest = if (beyondImaxActive) 1f else requestedZoom
        return effectiveRequest.coerceIn(minZoom, maxZoom)
    }
}
```

- [ ] **Step 4: Implement manual dispatcher**

Create `CameraManualControlDispatcher.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

internal class CameraManualControlDispatcher(
    threadName: String = "CamX-ManualControls"
) {
    private val threadDelegate = lazy(LazyThreadSafetyMode.NONE) {
        HandlerThread(threadName, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val handlerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        Handler(threadDelegate.value.looper)
    }
    private var latestZoomRunnable: Runnable? = null

    fun post(action: () -> Unit) {
        handlerDelegate.value.post(action)
    }

    fun postLatestZoom(action: () -> Unit) {
        latestZoomRunnable?.let { handlerDelegate.value.removeCallbacks(it) }
        val runnable = Runnable(action)
        latestZoomRunnable = runnable
        handlerDelegate.value.post(runnable)
    }

    fun shutdown() {
        if (handlerDelegate.isInitialized()) {
            handlerDelegate.value.removeCallbacksAndMessages(null)
        }
        if (threadDelegate.isInitialized()) {
            threadDelegate.value.quitSafely()
        }
        latestZoomRunnable = null
    }
}
```

- [ ] **Step 5: Wire CameraViewModel to dispatcher**

In `CameraViewModel.kt`, remove these fields:

```kotlin
private val manualControlThreadDelegate = lazy(LazyThreadSafetyMode.NONE) {
    HandlerThread(
        "CamX-ManualControls",
        Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
}
private val manualControlThread: HandlerThread
    get() = manualControlThreadDelegate.value
private val manualControlHandlerDelegate = lazy(LazyThreadSafetyMode.NONE) {
    Handler(manualControlThread.looper)
}
private val manualControlHandler: Handler
    get() = manualControlHandlerDelegate.value
private val zoomDispatchRunnable = Runnable {
    while (true) {
        val target = pendingZoomRatio ?: break
        pendingZoomRatio = null
        camera.setZoom(target)
        if (pendingZoomRatio == null) break
    }
}
```

Add:

```kotlin
private val manualControlDispatcher = CameraManualControlDispatcher()
```

Change `dispatchManualControlUpdate` to:

```kotlin
private fun dispatchManualControlUpdate(action: () -> Unit) {
    manualControlDispatcher.post(action)
}
```

Change `setZoom` to:

```kotlin
fun setZoom(r: Float) {
    val clamped = CameraZoomRequestPolicy.resolve(
        requestedZoom = r,
        minZoom = camera.minZoom,
        maxZoom = camera.maxZoom,
        beyondImaxActive = isBeyondImaxActive()
    )
    pendingZoomRatio = clamped
    manualControlDispatcher.postLatestZoom {
        val target = pendingZoomRatio ?: return@postLatestZoom
        pendingZoomRatio = null
        camera.setZoom(target)
    }
}
```

In `onCleared`, replace manual handler/thread shutdown with:

```kotlin
manualControlDispatcher.shutdown()
```

- [ ] **Step 6: Verify focused tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraZoomRequestPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 7: Compile Kotlin**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 8: Commit manual dispatch extraction**

Run:

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/ui/CameraZoomRequestPolicyTest.kt
git commit -m "refactor: extract camera manual dispatch policy"
```

### Task 5: MainActivity Debug Command Parser Extraction

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/DebugCommandParser.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/DebugCommandParserTest.kt`

- [ ] **Step 1: Write failing parser tests**

Create `DebugCommandParserTest.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugCommandParserTest {

    @Test
    fun parse_appliesHdrPresetDefaults() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "HDR",
                durationMs = "2500"
            )
        )

        requireNotNull(result)
        assertEquals(CameraModePreset.HDR, result.preset)
        assertEquals(AppMode.VIDEO, result.mode)
        assertEquals("HDR10", result.videoHdrMode)
        assertEquals("Rec.2020", result.videoColorSpace)
        assertEquals(CinemaVideoMode.PRO, result.videoMode)
        assertEquals(false, result.raw)
        assertEquals(false, result.log)
    }

    @Test
    fun parse_clampsDurationFromString() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "PRO",
                durationMs = "999999"
            )
        )

        requireNotNull(result)
        assertEquals(120_000L, result.durationMs)
    }

    @Test
    fun parse_parsesResolutionAndCompression() {
        val result = DebugCommandParser.parse(
            DebugCommandExtras(
                action = "record_clip",
                preset = "RAW",
                videoResolution = "4096x3072",
                rawCompression = "HIGH_COMPRESSION"
            )
        )

        requireNotNull(result)
        assertEquals(Size(4096, 3072), result.videoResolution)
        assertEquals(RawCompressionMode.HIGH_COMPRESSION, result.rawCompression)
    }

    @Test
    fun parse_returnsNullWhenNoDebugExtrasArePresent() {
        val result = DebugCommandParser.parse(DebugCommandExtras())

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.DebugCommandParserTest --no-daemon --console=plain
```

Expected: FAIL because `DebugCommandParser` and `DebugCommandExtras` do not exist.

- [ ] **Step 3: Implement parser**

Create `DebugCommandParser.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import java.util.Locale

internal data class DebugCommandExtras(
    val mode: String? = null,
    val preset: String? = null,
    val action: String? = null,
    val durationMs: String? = null,
    val raw: Boolean? = null,
    val log: Boolean? = null,
    val aspect: String? = null,
    val colorProfile: String? = null,
    val lutEnabled: Boolean? = null,
    val lutProfile: String? = null,
    val validationLabel: String? = null,
    val videoColorSpace: String? = null,
    val videoHdrMode: String? = null,
    val videoResolution: String? = null,
    val videoMode: String? = null,
    val rawCompression: String? = null
) {
    fun hasAnyValue(): Boolean =
        listOf(
            mode,
            preset,
            action,
            durationMs,
            aspect,
            colorProfile,
            lutProfile,
            validationLabel,
            videoColorSpace,
            videoHdrMode,
            videoResolution,
            videoMode,
            rawCompression
        ).any { !it.isNullOrBlank() } || raw != null || log != null || lutEnabled != null
}

internal data class ParsedDebugCommand(
    val mode: AppMode?,
    val preset: CameraModePreset?,
    val action: String?,
    val durationMs: Long,
    val raw: Boolean?,
    val log: Boolean?,
    val aspect: PreviewAspectRatio?,
    val colorProfile: String?,
    val lutEnabled: Boolean?,
    val lutProfile: String?,
    val validationLabel: String?,
    val videoColorSpace: String?,
    val videoHdrMode: String?,
    val videoResolution: Size?,
    val videoMode: CinemaVideoMode?,
    val rawCompression: RawCompressionMode?
)

internal object DebugCommandParser {
    private const val DEFAULT_DEBUG_DURATION_MS = 2500L
    private const val MIN_DEBUG_DURATION_MS = 400L
    private const val MAX_DEBUG_DURATION_MS = 120_000L

    fun parse(extras: DebugCommandExtras): ParsedDebugCommand? {
        if (!extras.hasAnyValue()) return null
        val preset = parsePreset(extras.preset)
        val initial = ParsedDebugCommand(
            mode = parseMode(extras.mode),
            preset = preset,
            action = extras.action?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() },
            durationMs = parseDurationMs(extras.durationMs),
            raw = extras.raw,
            log = extras.log,
            aspect = extras.aspect?.let(::parseAspect),
            colorProfile = extras.colorProfile?.trim()?.takeIf { it.isNotEmpty() },
            lutEnabled = extras.lutEnabled,
            lutProfile = extras.lutProfile?.trim()?.takeIf { it.isNotEmpty() },
            validationLabel = extras.validationLabel?.trim()?.takeIf { it.isNotEmpty() },
            videoColorSpace = extras.videoColorSpace?.trim()?.takeIf { it.isNotEmpty() },
            videoHdrMode = extras.videoHdrMode?.trim()?.takeIf { it.isNotEmpty() },
            videoResolution = extras.videoResolution?.let(::parseSize),
            videoMode = extras.videoMode?.let { CinemaVideoMode.fromName(it) },
            rawCompression = extras.rawCompression?.let { RawCompressionMode.fromName(it) }
        )
        return applyPresetDefaults(initial)
    }

    private fun parseMode(value: String?): AppMode? =
        when (value?.trim()?.uppercase(Locale.US)) {
            "VIDEO" -> AppMode.VIDEO
            "PRO" -> AppMode.PRO
            else -> null
        }

    private fun parseDurationMs(value: String?): Long =
        (value?.trim()?.toLongOrNull() ?: DEFAULT_DEBUG_DURATION_MS)
            .coerceIn(MIN_DEBUG_DURATION_MS, MAX_DEBUG_DURATION_MS)

    private fun parseAspect(value: String): PreviewAspectRatio? =
        when (value.trim().uppercase(Locale.US)) {
            "AUTO" -> PreviewAspectRatio.AUTO
            "16:9", "SIXTEEN_NINE" -> PreviewAspectRatio.SIXTEEN_NINE
            "4:3", "FOUR_THREE" -> PreviewAspectRatio.FOUR_THREE
            "1:1", "SQUARE" -> PreviewAspectRatio.SQUARE
            else -> null
        }

    private fun parsePreset(value: String?): CameraModePreset? =
        when (value?.trim()?.uppercase(Locale.US)) {
            "VIDEO" -> CameraModePreset.VIDEO
            "PRO" -> CameraModePreset.PRO
            "HDR" -> CameraModePreset.HDR
            "LOG", "LOG_VIDEO" -> CameraModePreset.LOG_VIDEO
            "RAW", "RAW_MODE" -> CameraModePreset.RAW_MODE
            else -> null
        }

    private fun parseSize(value: String): Size? {
        val normalized = value.trim().lowercase(Locale.US)
        val parts = normalized.split("x")
        if (parts.size != 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return Size(width, height)
    }

    private fun applyPresetDefaults(command: ParsedDebugCommand): ParsedDebugCommand =
        when (command.preset) {
            CameraModePreset.VIDEO -> command.copy(
                mode = command.mode ?: AppMode.VIDEO,
                raw = command.raw ?: false,
                log = command.log ?: false,
                videoHdrMode = command.videoHdrMode ?: "OFF",
                videoColorSpace = command.videoColorSpace ?: "Rec.709",
                videoMode = command.videoMode ?: CinemaVideoMode.PRO,
                colorProfile = command.colorProfile ?: "Natural"
            )
            CameraModePreset.PRO -> command.copy(
                mode = command.mode ?: AppMode.VIDEO,
                raw = command.raw ?: false,
                log = command.log ?: false,
                videoHdrMode = command.videoHdrMode ?: "OFF",
                videoColorSpace = command.videoColorSpace ?: "Rec.709",
                videoMode = command.videoMode ?: CinemaVideoMode.PRO,
                colorProfile = command.colorProfile ?: "Natural"
            )
            CameraModePreset.HDR -> command.copy(
                mode = AppMode.VIDEO,
                raw = command.raw ?: false,
                log = command.log ?: false,
                videoHdrMode = command.videoHdrMode ?: "HDR10",
                videoColorSpace = command.videoColorSpace ?: "Rec.2020",
                videoMode = command.videoMode ?: CinemaVideoMode.PRO,
                colorProfile = command.colorProfile ?: "Natural"
            )
            CameraModePreset.LOG_VIDEO -> command.copy(
                mode = command.mode ?: AppMode.VIDEO,
                raw = command.raw ?: false,
                log = command.log ?: true,
                videoHdrMode = command.videoHdrMode ?: "OFF",
                videoColorSpace = command.videoColorSpace ?: "Rec.2020",
                videoMode = command.videoMode ?: CinemaVideoMode.LOG,
                colorProfile = command.colorProfile ?: "Neo Flex Curve"
            )
            CameraModePreset.RAW_MODE -> command.copy(
                mode = AppMode.VIDEO,
                raw = command.raw ?: true,
                log = command.log ?: false,
                videoHdrMode = command.videoHdrMode ?: "OFF",
                videoColorSpace = command.videoColorSpace ?: "Rec.2020",
                videoMode = command.videoMode ?: CinemaVideoMode.RAW,
                colorProfile = command.colorProfile ?: "Natural"
            )
            null -> command
        }
}
```

- [ ] **Step 4: Wire MainActivity to parser**

In `MainActivity.readDebugIntent`, build `DebugCommandExtras` from the `Intent`, call `DebugCommandParser.parse`, then assign the returned values:

```kotlin
val parsed = DebugCommandParser.parse(
    DebugCommandExtras(
        mode = intent.getStringExtra(EXTRA_DEBUG_MODE),
        preset = intent.getStringExtra(EXTRA_DEBUG_PRESET),
        action = intent.getStringExtra(EXTRA_DEBUG_ACTION),
        durationMs = intent.getStringExtra(EXTRA_DEBUG_DURATION_MS)
            ?: intent.extras?.get(EXTRA_DEBUG_DURATION_MS)?.toString(),
        raw = intent.extras?.takeIf { it.containsKey(EXTRA_DEBUG_RAW) }?.getBoolean(EXTRA_DEBUG_RAW),
        log = intent.extras?.takeIf { it.containsKey(EXTRA_DEBUG_LOG) }?.getBoolean(EXTRA_DEBUG_LOG),
        aspect = intent.getStringExtra(EXTRA_DEBUG_ASPECT),
        colorProfile = intent.getStringExtra(EXTRA_DEBUG_COLOR_PROFILE)?.let(::normalizeDebugColorProfile),
        lutEnabled = intent.extras?.takeIf { it.containsKey(EXTRA_DEBUG_LUT_ENABLED) }?.getBoolean(EXTRA_DEBUG_LUT_ENABLED),
        lutProfile = intent.getStringExtra(EXTRA_DEBUG_LUT_PROFILE)?.let(::normalizeDebugLutProfile),
        validationLabel = intent.getStringExtra(EXTRA_DEBUG_VALIDATION_LABEL),
        videoColorSpace = intent.getStringExtra(EXTRA_DEBUG_VIDEO_COLOR_SPACE),
        videoHdrMode = intent.getStringExtra(EXTRA_DEBUG_VIDEO_HDR_MODE),
        videoResolution = intent.getStringExtra(EXTRA_DEBUG_VIDEO_RESOLUTION),
        videoMode = intent.getStringExtra(EXTRA_DEBUG_VIDEO_MODE),
        rawCompression = intent.getStringExtra(EXTRA_DEBUG_RAW_COMPRESSION)
    )
)
if (parsed == null) {
    clearPendingDebugCommandState()
    Log.i(TAG, "readDebugIntent ignored intent without debug extras=$intent")
    return
}
pendingDebugMode = parsed.mode
pendingDebugPreset = parsed.preset
pendingDebugAction = parsed.action
pendingDebugDurationMs = parsed.durationMs
pendingDebugRaw = parsed.raw
pendingDebugLog = parsed.log
pendingDebugAspect = parsed.aspect
pendingDebugColorProfile = parsed.colorProfile
pendingDebugLutEnabled = parsed.lutEnabled
pendingDebugLutProfile = parsed.lutProfile
pendingDebugValidationLabel = parsed.validationLabel
pendingDebugVideoColorSpace = parsed.videoColorSpace
pendingDebugVideoHdrMode = parsed.videoHdrMode
pendingDebugVideoResolution = parsed.videoResolution
pendingDebugVideoMode = parsed.videoMode
pendingDebugRawCompression = parsed.rawCompression
```

Delete `parseDebugDurationMs`, `parseDebugAspect`, `parseDebugPreset`, `parseDebugSize`, and `normalizePendingDebugPresetDefaults` from `MainActivity` after the new parser is wired.

- [ ] **Step 5: Verify parser tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.DebugCommandParserTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 6: Compile Kotlin**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit debug parser extraction**

Run:

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/DebugCommandParser.kt app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt app/src/test/java/com/neoproc/neoproccamx/ui/DebugCommandParserTest.kt
git commit -m "refactor: extract debug command parser"
```

### Task 6: MainActivity Permission Policy Extraction

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicyTest.kt`

- [ ] **Step 1: Write failing permission policy tests**

Create `CameraPermissionPolicyTest.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionPolicyTest {

    @Test
    fun requiredPermissions_includesWriteExternalStorageOnAndroidPAndBelow() {
        val result = CameraPermissionPolicy.requiredPermissions(Build.VERSION_CODES.P)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            result
        )
    }

    @Test
    fun requiredPermissions_excludesWriteExternalStorageOnAndroidQAndAbove() {
        val result = CameraPermissionPolicy.requiredPermissions(Build.VERSION_CODES.Q)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ),
            result
        )
    }

    @Test
    fun isCameraGranted_acceptsResultMapOrExistingPermission() {
        assertTrue(
            CameraPermissionPolicy.isCameraGranted(
                result = mapOf(Manifest.permission.CAMERA to true),
                hasCameraPermission = false
            )
        )
        assertTrue(
            CameraPermissionPolicy.isCameraGranted(
                result = emptyMap(),
                hasCameraPermission = true
            )
        )
        assertFalse(
            CameraPermissionPolicy.isCameraGranted(
                result = emptyMap(),
                hasCameraPermission = false
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraPermissionPolicyTest --no-daemon --console=plain
```

Expected: FAIL because `CameraPermissionPolicy` does not exist.

- [ ] **Step 3: Implement permission policy**

Create `CameraPermissionPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.Build

internal object CameraPermissionPolicy {
    fun requiredPermissions(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun isCameraGranted(
        result: Map<String, Boolean>,
        hasCameraPermission: Boolean
    ): Boolean =
        result[Manifest.permission.CAMERA] == true || hasCameraPermission
}
```

- [ ] **Step 4: Wire MainActivity permission flow**

In the permission launcher callback, replace direct camera-granted logic with:

```kotlin
val cameraGranted = CameraPermissionPolicy.isCameraGranted(
    result = result,
    hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
)
```

Replace `requestPermissions()` with:

```kotlin
private fun requestPermissions() {
    permissionLauncher.launch(
        CameraPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT).toTypedArray()
    )
}
```

- [ ] **Step 5: Verify permission tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraPermissionPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 6: Compile Kotlin**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit permission extraction**

Run:

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt app/src/test/java/com/neoproc/neoproccamx/ui/CameraPermissionPolicyTest.kt
git commit -m "refactor: extract camera permission policy"
```

### Task 7: CameraViewModel Startup Job Coordinator

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraStartupCoordinator.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] **Step 1: Create startup coordinator**

Create `CameraStartupCoordinator.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.core.StartupSmoothnessPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface CameraStartupActions {
    fun startRecordingCapabilityWarmup()
    fun selectCamera()
    fun applyStartupPreferences()
    fun completeStartupFastPath()
    fun releaseStartupNativePreviewGate()
    fun refreshPreviewPipelineIfNeeded()
    fun applyAiTrackingPreferences()
    fun scheduleAiWarmUp(delayMs: Long, allowDuringStartupAiRamp: Boolean)
}

internal class CameraStartupCoordinator(
    private val scope: CoroutineScope,
    private val cameraState: StateFlow<CameraState>,
    private val actions: CameraStartupActions
) {
    private var startupBootstrapJob: Job? = null
    private var startupOpenAwaitJob: Job? = null
    private var startupNativePreviewReleaseJob: Job? = null
    private var startupAiRampJob: Job? = null

    fun startBootstrap() {
        if (startupBootstrapJob != null) return
        actions.startRecordingCapabilityWarmup()
        startupBootstrapJob = scope.launch(Dispatchers.Default) {
            actions.selectCamera()
            actions.applyStartupPreferences()
        }
    }

    fun scheduleOpenAwait() {
        startupOpenAwaitJob?.cancel()
        startupOpenAwaitJob = scope.launch {
            withTimeoutOrNull(StartupSmoothnessPolicy.featureGateTimeoutMs()) {
                cameraState.first { state ->
                    state == CameraState.Streaming ||
                        state == CameraState.Recording ||
                        state is CameraState.Error
                }
            }
            withContext(Dispatchers.Main.immediate) {
                actions.completeStartupFastPath()
            }
        }
    }

    fun scheduleNativePreviewRelease() {
        startupNativePreviewReleaseJob?.cancel()
        startupNativePreviewReleaseJob = scope.launch {
            delay(StartupSmoothnessPolicy.nativePreviewReleaseDelayMs())
            actions.releaseStartupNativePreviewGate()
            withContext(Dispatchers.Main.immediate) {
                actions.refreshPreviewPipelineIfNeeded()
            }
        }
    }

    fun scheduleAiRamp(delayMs: Long) {
        startupAiRampJob?.cancel()
        startupAiRampJob = scope.launch {
            delay(delayMs)
            actions.applyAiTrackingPreferences()
            actions.scheduleAiWarmUp(delayMs = 0L, allowDuringStartupAiRamp = false)
        }
    }

    fun cancelAll() {
        startupBootstrapJob?.cancel()
        startupOpenAwaitJob?.cancel()
        startupNativePreviewReleaseJob?.cancel()
        startupAiRampJob?.cancel()
        startupBootstrapJob = null
        startupOpenAwaitJob = null
        startupNativePreviewReleaseJob = null
        startupAiRampJob = null
    }
}
```

- [ ] **Step 2: Wire coordinator in CameraViewModel**

In `CameraViewModel.kt`, add this field after the `cameraState` flow is declared:

```kotlin
private val startupCoordinator = CameraStartupCoordinator(
    scope = viewModelScope,
    cameraState = cameraState,
    actions = object : CameraStartupActions {
        override fun startRecordingCapabilityWarmup() = this@CameraViewModel.startRecordingCapabilityWarmup()
        override fun selectCamera() = camera.selectCamera()
        override fun applyStartupPreferences() = this@CameraViewModel.applyStartupPreferences()
        override fun completeStartupFastPath() = this@CameraViewModel.completeStartupFastPath()
        override fun releaseStartupNativePreviewGate() {
            startupDirectPreviewActive = false
        }
        override fun refreshPreviewPipelineIfNeeded() {
            if (previewSurface == null) return
            if (
                cameraState.value == CameraState.Open ||
                    cameraState.value == CameraState.Streaming
            ) {
                this@CameraViewModel.refreshPreviewPipelineIfNeeded()
            }
        }
        override fun applyAiTrackingPreferences() {
            this@CameraViewModel.applyAiTrackingPreferences()
        }
        override fun scheduleAiWarmUp(delayMs: Long, allowDuringStartupAiRamp: Boolean) {
            this@CameraViewModel.scheduleAiWarmUp(
                delayMs = delayMs,
                allowDuringStartupAiRamp = allowDuringStartupAiRamp
            )
        }
    }
)
```

Change `startStartupBootstrap()` to:

```kotlin
private fun startStartupBootstrap() {
    startupCoordinator.startBootstrap()
}
```

Change `scheduleStartupOpenAwait()` to:

```kotlin
private fun scheduleStartupOpenAwait() {
    startupCoordinator.scheduleOpenAwait()
}
```

Change `scheduleStartupNativePreviewRelease()` to:

```kotlin
private fun scheduleStartupNativePreviewRelease() {
    startupCoordinator.scheduleNativePreviewRelease()
}
```

Inside `completeStartupFastPath()`, replace this direct job block:

```kotlin
startupAiRampJob = viewModelScope.launch {
    delay(resolveStartupAiRampDelayMs())
    if (startupFeatureGateActive) return@launch
    startupAiRampActive = false
    applyAiTrackingPreferences()
    scheduleAiWarmUp(delayMs = 0L)
}
```

with:

```kotlin
startupCoordinator.scheduleAiRamp(resolveStartupAiRampDelayMs())
```

In `onCleared`, replace startup job cancels with:

```kotlin
startupCoordinator.cancelAll()
```

Remove the old `startupBootstrapJob`, `startupOpenAwaitJob`, `startupNativePreviewReleaseJob`, and `startupAiRampJob` fields after the coordinator is wired.

- [ ] **Step 3: Compile Kotlin**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 4: Commit startup extraction**

Run:

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/CameraStartupCoordinator.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt
git commit -m "refactor: extract camera startup coordinator"
```

### Task 8: Final Verification

**Files:**
- Existing Gradle project files
- Existing app source files

- [ ] **Step 1: Run truthful language scan**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools/quality/assert-truthful-language.ps1
```

Expected: PASS.

- [ ] **Step 2: Run focused new tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraZoomRequestPolicyTest --tests com.neoproc.neoproccamx.ui.DebugCommandParserTest --tests com.neoproc.neoproccamx.ui.CameraPermissionPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 3: Run all unit tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 4: Run lint**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
```

Expected: task completes. Existing baseline warnings may be reported, but no new fatal lint failure should block the build.

- [ ] **Step 5: Build debug APK**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Device smoke test if device is attached**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
```

If device `10BF8M2NDX003P3` is attached, run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -c
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -d -b crash
```

Expected: app launches and crash buffer does not contain a new `com.neoproc.neoproccamx` crash from the launch.

- [ ] **Step 7: Inspect file size progress**

Run:

```powershell
Get-ChildItem -Path app\src\main\java\com\neoproc\neoproccamx\ui -File -Filter *.kt |
    ForEach-Object {
        [PSCustomObject]@{
            Lines = (Get-Content $_.FullName | Measure-Object -Line).Lines
            Name = $_.Name
        }
    } |
    Sort-Object Lines -Descending |
    Select-Object -First 10
```

Expected: `CameraViewModel.kt` and `MainActivity.kt` are smaller than the baseline observed in the spec, and extracted responsibility files are visible.

- [ ] **Step 8: Commit final verification note if verification artifacts are documented**

If verification output needs to be preserved, add a short markdown note under `docs/architecture/` or `docs/superpowers/` instead of committing generated logs:

```powershell
git status --short
```

Expected: generated `test-results*`, screenshots, logs, and build outputs are ignored. Source/docs/test changes are the only visible Git changes.
