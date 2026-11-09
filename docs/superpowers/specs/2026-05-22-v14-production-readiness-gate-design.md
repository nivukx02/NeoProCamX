# V14 Production Readiness Gate Design

## Goal

Make production readiness measurable instead of label-based. The app is production-ready only when required build, test, APK, device, launch, and feature evidence passes. Hardware-specific behavior that was not exercised is reported as `NOT_PROVEN`, not hidden as a pass.

## Scope

V14 adds a quality gate policy and a local runner. It does not claim zero bugs by wording alone. It turns "zero errors and warnings" into a fresh build/test command with `--warning-mode=fail`, whitespace validation, APK validation, ADB install, app launch, and foreground activity verification.

## Architecture

- `ProductionReadinessGatePolicy` evaluates required feature checks into `PASS`, `FAIL`, or `NOT_PROVEN`.
- `run-v14-production-gate.ps1` executes the real commands and writes a report under `qa-artifacts/v14-production-readiness`.
- Unit tests validate that failed or unproven required features cannot be reported as production-ready.

## Required Gates

- Gradle unit tests and debug build pass with warning mode fail.
- `git diff --check` passes.
- Debug APK exists and is non-empty.
- ADB device is connected.
- APK installs successfully.
- `MainActivity` launches and is the resumed foreground activity.
- Critical feature categories have evidence rows: preview, recording, HDR/NDR/NIVUKX, Log/N-Log, RAW, NPV10 master, NPV10 MOV export, gallery publish, settings, mode switching, color selection.

## Acceptance

- Required `FAIL` results make the final result `FAIL`.
- Required `NOT_PROVEN` results make the final result `NOT_PROVEN`.
- Only all required `PASS` results produce `PASS`.
- The runner generates a readable report file with exact command outcomes.
