# ADB Camera QA

Run the full camera QA harness against one USB-debuggable device:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1
```

The default run is invasive: it launches and force-stops the app, changes runtime permissions temporarily, clears the device-wide logcat buffer, captures photos, and exercises power/app switching. Video recording, storage-fill, and battery-override scenarios are separate opt-ins. Cleanup verifies target-process termination and restoration/removal of permissions, battery override, and run-specific fill artifacts. Clearing logcat is irreversible, and prior wake/keyguard/foreground state is not restored; use `SafeSmoke` when those mutations are unacceptable.

## Safe read-only smoke check

Use `SafeSmoke` to check ADB connectivity, package presence, configured preflight gates, and read-only camera metadata without changing device or app state:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1 `
  -Serial <device-id> `
  -SafeSmoke
```

`SafeSmoke` enables every invasive skip switch, sets both capture-loop counts to zero, disables all high-impact opt-ins, and avoids cleanup commands because no device mutation was started. Its deliberate scenario skips are optional, while connectivity, package, thermal, and storage failures remain fatal. It still creates the local report directory.

## Readiness preflight

Thermal and free-space gates are mandatory. They run after package detection and before the first device mutation. A failed gate stops the run without launching the app or performing cleanup mutations. Thermal status is also rechecked immediately before capture, recording, and stress groups.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1 `
  -ThermalReadyReadingCount 3 `
  -ThermalReadyIntervalSec 30 `
  -MinimumDataFreeMb 12288 `
  -MinimumSdcardFreeMb 12288
```

A thermal-ready sample requires `dumpsys thermalservice` to report status `0`. The default admission requires two status-0 readings 60 seconds apart and at least 12,288 MiB free on both `/data` and `/sdcard`. Lower or disabled storage gates are rejected because app launch/recording can prepare an 8-10 GiB physical reservation while retaining a 2 GiB system safety reserve.

## Scenario controls

| Switch | Effect |
| --- | --- |
| `-SafeSmoke` | Selects the centralized read-only smoke profile. |
| `-SkipGenericIntents` | Skips package-scoped generic camera intent launches. |
| `-SkipExplicitAppLaunch` | Skips the explicit main-activity launch. |
| `-SkipBaselinePhotos` | Skips the standard `PHOTO` capture. |
| `-SkipHdrCapture` | Skips the `HDR` capture. |
| `-SkipRawCapture` | Skips the `RAW` capture. |
| `-SkipFocus` | Skips the autofocus debug command. |
| `-SkipFacingSwitch` | Skips the front/back facing debug command. |
| `-SkipVideos` | Skips the standard video scenario. |
| `-SkipPerformanceVideo` | Skips the separate recording-time evidence probe. |
| `-SkipCameraWatch` | Skips the camera-service 3A watch. |
| `-SkipPermissionMutation` | Skips permission snapshot, priming, revoke, grant, and restoration. |
| `-SkipLogcatClear` | Preserves the device-wide logcat buffer; log collection remains available. |
| `-SkipCrashRecovery` | Skips target-app force-stop and relaunch. |
| `-SkipPowerAndAppSwitch` | Skips lock-screen key events and app switching. |
| `-SkipLowStorage` | Skips creation of the bounded storage fill file. |
| `-SkipLowBattery` | Skips the battery service override. |
| `-SkipIncomingCall` | Skips the optional incoming-call placeholder; the harness never places a call. |
| `-EnableLegacyMovVideoOracle` | Opts into the legacy public-MOV recording check. |
| `-EnablePerformanceVideoProbe` | Opts into the separate eight-second recording-time evidence probe. |
| `-EnableLowStorageScenario` | Opts into creation of the bounded, run-specific storage fill file. |
| `-EnableLowBatteryScenario` | Opts into the temporary battery-service override. |
| `-RequireThermalPreflight` | Compatibility switch; thermal preflight is mandatory and defaults on. Passing it as false is rejected. |

Useful value parameters include `-AdbPath`, `-Serial`, `-PackageName`, `-MainActivityComponent`, `-DebugReceiverComponent`, `-ReportRoot`, `-CaptureStressCount`, `-LongRunCaptureCount`, `-VideoRecordDurationSec`, `-LowStorageFillMb`, `-ThermalReadyReadingCount`, `-ThermalReadyIntervalSec`, `-MinimumDataFreeMb`, and `-MinimumSdcardFreeMb`.

Set `-CaptureStressCount 0` or `-LongRunCaptureCount 0` to skip the corresponding loop.

The opt-in storage-fill scenario is clamped to leave at least 2,048 MiB free even when a larger `-LowStorageFillMb` is requested. It records the exact `dd` PID, verifies process identity before termination, removes both run-specific files immediately after the dedicated capture, and checks that free space recovers before any later scenario. Top-level cleanup repeats this fence after an interruption.

## Video compatibility warning

The standard video step is `SKIP` by default. Its oracle expects a durable public `.mov` MediaStore artifact, while current NPV10 recording output is not compatible with that legacy contract. The harness does not dispatch a recording merely to generate a known false MOV failure.

Use `-EnableLegacyMovVideoOracle` only against a build that is known to publish the legacy MOV artifact:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1 `
  -EnableLegacyMovVideoOracle `
  -VideoRecordDurationSec 10
```

The recording-time performance probe is separate and disabled by default. `-EnablePerformanceVideoProbe` opts into a bounded clip plus `top`, `meminfo`, artifacts, and logcat evidence; it does not claim a performance pass because no threshold is configured. `LOG_VIDEO` is convert-only and is reported as optional/non-applicable.

## Verdicts and exit codes

Each result records whether it is required. Any `FAIL` makes the overall verdict `FAIL`. A required `SKIP` prevents unrelated passing steps from turning the category or overall verdict into `PASS`; optional evidence/non-applicable skips do not. Cleanup results are included in Stability.

The process exits `1` when any step fails, cleanup fails, or final report writing fails. It exits `2` when required coverage remains `SKIP`, and `0` only when all required coverage in the selected profile passes. SafeSmoke's deliberate invasive-scenario skips are optional.

Outputs are written under `tools/reports/camera_qa_<timestamp>/`:

- `summary.md`
- `summary.json`
- `session.log`
- `logs/`
- `media/`

Deep app automation uses the debug receiver, so install a compatible debug build for the full workflow. Unsupported or policy-blocked probes are reported truthfully as `SKIP`; a command dispatch alone is not treated as proof that camera behavior completed.
