# Phase 6 Device Recording Matrix Plan

## Steps

1. Add tests for command rendering, Phase 6 validation labels, metadata validation, and log classification.
2. Implement the command, metadata, and log-classification policies.
3. Add a PowerShell device runner for the 19-row matrix.
4. Run focused tests, then full debug unit tests, assemble, and lint.
5. Install and launch the debug APK on the connected phone.
6. Run the Phase 6 runner against the connected phone.
7. Review the CSV/log output and classify any failures as app defects or hardware/platform limits.
8. Commit the implementation.

## Acceptance Criteria

- Every matrix row renders a complete adb command.
- Phase 6 labels are unique and start with `phase6_`.
- Metadata validation fails wrong delivery/container cases and warns on hardware resolution/FPS fallback.
- Known vendor/platform warnings are non-blocking.
- The app builds and lint reports no errors or warnings.
- The device runner produces per-row artifacts and a summary file.
