# NeoProCamX Privacy Policy

Effective date: 5 August 2026

NeoProCamX is a camera application. It processes camera frames, microphone audio, motion-sensor readings, and on-device diagnostics only to provide capture, stabilization, preview, recording, recovery, and reliability features selected by the user.

## Data handled by the app

- Camera frames and microphone audio are processed on the device and written to user-selected/app-created media outputs.
- Gyroscope and accelerometer samples may be processed on the device for stabilization and motion analysis.
- App-private diagnostics may contain device/app metadata, performance measurements, feature events, and error details. They are stored in the app's private storage. The current production configuration supplies no telemetry endpoint or upload consent, so these batches are not transmitted.
- Crash diagnostics are stored in app-specific internal/external files for user-initiated support collection. They are not automatically uploaded.
- Google Play Integrity is disabled unless a release operator configures a Cloud project. If enabled, Google processes integrity requests under its own terms and privacy policy.
- On-device Google/Android ML libraries may process image data to provide detection and labeling features. NeoProCamX does not intentionally send captured photos or videos to an application-operated server.

## Permissions

The app may request camera, microphone, notification, motion-sensor, vibration, wake-lock, and foreground-service access. Permissions are used only for the corresponding camera/recording feature. Media is saved through Android scoped storage; the app does not request all-files access.

## Sharing and retention

NeoProCamX does not sell personal data. Captures remain under the user's control and are shared only when the user invokes Android sharing or another explicit export action. App-private diagnostics remain until app data is cleared or the app is uninstalled. Successfully uploaded diagnostic batches may be removed only if a future release adds an endpoint and the user explicitly consents.

## Security and user choices

The app uses Android app-private storage, scoped media storage, release signing, and platform security APIs. Users can revoke permissions in Android Settings and can delete captures, clear app data, or uninstall the app.

## Publisher contact

Before public distribution, the publisher must replace this section with its legal name, support email, jurisdiction/address where required, and the public URL hosting this exact policy. Legal review is required before store submission.
