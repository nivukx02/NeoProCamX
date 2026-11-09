# Production release checklist

## Automated gate

- Run `testDebugUnitTest lintRelease assembleDebug assembleRelease bundleRelease --warning-mode all --console=plain` with JDK 21, Android 36.1, Build Tools 36.1.0, NDK 29.0.14206865, and CMake 3.22.1.
- Confirm every unit-test XML reports zero failures, zero errors, and zero skipped tests.
- Confirm release lint XML contains zero issues and that warnings remain errors with no baseline.
- Archive debug/release APKs, the release AAB, R8 mapping, and native symbols.

## Signing and integrity

- Supply the release upload keystore and four `NEOPROCAMX_KEY*` environment values from the secret store; never commit them.
- Supply the real upload certificate SHA-256 and Google Play app-signing SHA-256.
- Supply and verify the Play Integrity Cloud project number if Play Integrity is enabled.
- Verify the signed APK/AAB certificate and install the Play-re-signed internal-track build on a clean device.

## Store and legal

- Increment `versionCode` and set the public `versionName`.
- Obtain legal approval for `PRIVACY_POLICY.md`, replace its contact placeholder, and host it at a stable HTTPS URL.
- Regenerate the full transitive third-party license/notice inventory.
- Complete Google Play Data safety, permissions declarations, foreground-service declaration, content rating, and target-audience forms truthfully.

## Device qualification

- Test cold start, permission denial/recovery, photo capture, video start/stop, app background/foreground, interrupted recording recovery, low storage, thermal pressure, and camera disconnect.
- Run focused frame/jank and memory captures on every supported device tier; retain artifacts with build ID, device model, OS build, and exact flow.
- Verify output metadata, playable media, orientation, audio sync, HDR/SDR compatibility, and gallery publication.
- Run a 30-minute recording soak and repeated mode-switch/record cycles without ANRs, crashes, leaked foreground services, or growing memory.
