# Artifact Policy

NeoProCamX keeps source, build configuration, tests, docs, native code, and intentional fixtures in Git.

Generated validation output does not belong in Git:

- `test-results*/`
- screenshots and camera sample JPG/PNG files at the repository root
- logcat dumps and temporary logs
- APK build output
- local IDE/cache folders
- dependency folders such as `node_modules/`
- generated reports and logs under `tools/`

Validation artifacts should be written under a timestamped `test-results-YYYYMMDD-*` folder during a run, reviewed for evidence, then left untracked. If an artifact becomes a permanent fixture, move it into a named fixture directory and document why it is source-controlled.

Reusable scripts under `tools/` are source-controlled. Generated report/check folders and temporary logs under `tools/` are not source-controlled.
