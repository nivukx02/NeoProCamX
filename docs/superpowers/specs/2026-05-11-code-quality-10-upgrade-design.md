# Code Quality 10 Upgrade Design

Date: 2026-05-11

## Goal

Raise NeoProCamX from the current estimated 7.6/10 code-quality level toward a high-end maintainability baseline without intentionally changing camera behavior, recording behavior, or UI workflows in this milestone.

The outcome should make future camera-core and product-quality work safer: smaller ownership boundaries, cleaner repository state, truthful hardware language, fewer legacy aliases, and stronger verification around extracted behavior.

## Approved Strategy

Milestone 1 is code-quality first.

Implementation order:

1. Repository hygiene and artifact policy.
2. Hardware-claim truthfulness audit.
3. Deprecated alias and compatibility cleanup.
4. `CameraViewModel` responsibility split.
5. `MainActivity` responsibility split.
6. Verification gates and regression checks.

This milestone is not a visual redesign and not a camera algorithm upgrade. It prepares the codebase for those passes.

## Current Weak Parts

Observed weak areas:

- `CameraViewModel.kt` is about 5,664 lines and owns too many orchestration roles.
- `MainActivity.kt` is about 2,809 lines and mixes UI wiring, debug automation, permission flow, gesture handling, status rendering, and lifecycle recovery.
- `RecordingManager.kt`, `CameraController.kt`, `ColorScience.kt`, and several AI/pipeline files remain large enough to need targeted passes in future milestones.
- The repository has generated or local artifacts at the root and in repeated `test-results*` directories.
- Some names, comments, and docs use stronger wording than Android Camera2, vendor HALs, encoders, and thermal conditions can guarantee.
- Some deprecated aliases and old compatibility surfaces remain in production packages.

## Non-Goals

- Do not remove existing user-facing features.
- Do not change camera request behavior, recording selection, mode defaults, UI layout, or debug command semantics unless a test proves the current behavior is broken.
- Do not fake unsupported 8K, RAW, 10-bit, 12-bit, HDR, ISP-bypass, zero-copy, or bit-perfect behavior.
- Do not perform a broad package rewrite.
- Do not delete artifacts unless they are clearly generated and covered by the repository hygiene policy.

## Repository Hygiene

The repository should separate source-controlled assets from generated artifacts.

Required behavior:

- Update `.gitignore` to cover local IDE/cache folders, Node dependencies, Gradle/Kotlin cache folders, build outputs, screenshots, logs, temporary reports, and `test-results*` folders.
- Keep source, Gradle files, docs, tests, native code, and intentional fixtures visible to Git.
- Create a short artifact policy in docs that names where validation outputs belong and which outputs should not be committed.
- Do not delete files that may be intentional source, fixtures, keystore material, or user-owned notes without explicit review.

Success rule: `git status --short` should become readable, with source changes separated from generated noise.

## Truthful Hardware Language

All high-end language must distinguish between requested, exposed, verified, and fallback behavior.

Required behavior:

- Replace absolute wording such as "guaranteed", "complete ISP bypass", "bit-perfect", "true zero-copy", "cinema-grade", or "hardware-native" when the code cannot prove it for the active device/session.
- Prefer wording such as "requested", "device-reported", "supported when exposed", "best-effort", "verified for this session", or "fallback applied".
- Keep ambitious product positioning where appropriate, but make implementation comments and diagnostics factual.
- Add tests for pure formatter/policy code when user-visible diagnostics are changed.

Success rule: comments, docs, diagnostics, and status strings no longer promise behavior that hardware and runtime validation cannot guarantee.

## Deprecated Alias Cleanup

Legacy compatibility names should not remain scattered through production code.

Required behavior:

- Inventory `@Deprecated`, `typealias`, old wrapper names, and compatibility aliases.
- Remove aliases that have no references.
- For aliases still used by tests or transitional code, move or consolidate them into a clearly named compatibility surface.
- Keep any remaining deprecation annotation explicit about the replacement and removal path.

Success rule: there is one intentional place to understand compatibility debt, and unused aliases are gone.

## CameraViewModel Split

`CameraViewModel` should become a coordinator of smaller, testable collaborators instead of a container for every camera-screen behavior.

Target extraction order:

1. Startup and resume orchestration.
2. Preview surface and native preview handoff.
3. Manual control dispatch and throttling.
4. Recording action coordination.
5. Feature state persistence and state restoration.
6. Debug and validation command handling if it still lives in the ViewModel.

Boundary rules:

- Extract pure decision logic first, then lifecycle-bound collaborators.
- Keep the public ViewModel API stable for `MainActivity` during this milestone.
- Each extracted unit must have a narrow constructor and a single responsibility.
- Tests should cover extracted decision logic before wiring changes.
- Avoid moving code only to reduce line count; each extraction must reduce responsibility coupling.

Success rule: `CameraViewModel` is materially smaller, easier to scan, and delegates named responsibilities without behavior changes.

## MainActivity Split

`MainActivity` should keep Android lifecycle ownership and high-level UI binding, while focused controllers own repeated workflows.

Target extraction order:

1. Debug command parsing, queuing, and execution scheduling.
2. Permission request and permission result handling.
3. Gallery thumbnail loading and display update.
4. Mode rail selection and active-mode rendering.
5. Focus, zoom, gesture, and HUD transient behavior.
6. Camera status text rendering where pure formatting can be tested.

Boundary rules:

- Keep ViewBinding ownership clear and avoid long-lived references that outlive the Activity.
- Controllers must expose small methods called from lifecycle or event callbacks.
- Pure policy functions should live outside Android view classes and have unit tests.
- Activity-level behavior should remain observable by existing instrumentation/debug flows.

Success rule: `MainActivity` is materially smaller and delegates debug/UI workflows to named collaborators without changing user workflows.

## Error Handling And Diagnostics

Cleanup should improve clarity without hiding failures.

Required behavior:

- Keep fallback and warning messages factual.
- Preserve existing logging that helps diagnose camera open, session rebuild, recording start/stop, and fallback events.
- When claims are softened, preserve enough context for users and logs to understand what was requested and what actually happened.
- Do not silence exceptions unless the existing code already handles that path and the replacement preserves equivalent logging.

Success rule: diagnostics become more accurate, not quieter.

## Testing And Verification

Each code extraction must be protected by focused tests where practical.

Required checks for implementation:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

When a device is attached, also run a debug smoke flow:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -c
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -d -b crash
```

Success rule: code-quality changes do not regress build, unit tests, lint, APK generation, or basic app launch.

## Completion Criteria

Milestone 1 is complete when:

- Generated artifact clutter is ignored or documented.
- Hardware claims are factual and capability-based.
- Unused deprecated aliases are removed and remaining compatibility debt is isolated.
- `CameraViewModel` and `MainActivity` are split into focused collaborators with stable public behavior.
- Focused tests exist for extracted pure policies.
- Unit tests, lint, and debug APK build complete successfully.
- Any unavailable device validation is explicitly reported rather than assumed.

## Next Milestones

After this milestone:

1. Camera Core 10 pass: stream planning, recording fallback truth, Camera2 capability reporting, encoder validation, and performance profiling.
2. Product Quality 10 pass: UI polish, interaction consistency, validation screenshots, and device-tested workflow quality.
