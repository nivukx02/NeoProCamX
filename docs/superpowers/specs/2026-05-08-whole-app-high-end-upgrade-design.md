# Whole App High-End Upgrade Design

Date: 2026-05-08

## Goal

Upgrade NeoProCamX across the full app while preserving existing features. The target is a high-end Experimental Pro camera build with the strongest quality and performance the device exposes, plus truthful fallback when Android Camera2, encoder, HAL, or thermal limits block a requested capability.

The quality goal is maximum mobile image quality. The app will pursue a clean, sharp, cinema-grade look, but it must not claim impossible hardware behavior such as true 8K recording or complete ISP bypass when the device does not expose those paths.

## Approved Strategy

Implementation order:

1. Phased high-end rebuild.
2. Major architecture cleanup after the camera contracts are stable.
3. Whole-app integration pass protected by tests, deployment, and performance profiling.

The app release posture is High-End Experimental. The architecture must support a dual tier:

- Experimental Pro path for high-end devices and aggressive capability attempts.
- Stable fallback path for devices or sessions that reject the experimental path.

## Phase 1: Camera Pipeline

The camera pipeline attempts maximum capability first and falls back safely.

Required behavior:

- Try maximum exposed stream paths first: RAW, YUV, PRIVATE, encoder, maximum-resolution stream maps, and safe vendor-detectable paths.
- Prefer 8K or full-sensor only when a usable stream/session and encoder path are exposed.
- Apply all supported Camera2 ISP-bypass request keys:
  - noise reduction off
  - edge off
  - hot pixel off
  - shading off
  - color aberration off
  - contrast tonemap curve
  - distortion correction off when available
- Report unsupported ISP-bypass keys instead of claiming complete bypass.
- Show an on-screen warning when 8K, full sensor, RAW, or ISP-bypass requests fall back.
- Record diagnostics for requested path, actual path, stream size, bit depth, codec, HDR metadata, ISP-bypass key coverage, and fallback reason.

Fallback timing:

- Hard failure: immediate fallback.
- Severe lag or session instability: fallback after 3 seconds.
- Unstable FPS or encoder pressure: fallback after 10 seconds.
- Thermal degradation: fallback after 30 seconds.

Success rule: experimental capability is tried first, but recording and capture must still finish through fallback.

## Phase 2: HDR, Noise, Sharpness, And Color

Image quality is adaptive and scene-aware.

Required behavior:

- HDR requests force correct HDR color metadata even if stale SDR or Rec.709 state is present.
- Preview identity is mode-correct:
  - LOG mode shows LOG preview.
  - HDR mode shows HDR preview.
  - RAW mode shows RAW-oriented preview.
  - PRO/normal mode shows normal preview unless the user selects another mode.
- 10-bit is the default when the encoder and dynamic range path support it.
- 12-bit RAW is requested only when the actual RAW format and device capability support it.
- Low light and HDR shadows prioritize RGB/chroma noise suppression.
- Bright scenes prioritize sharp detail.
- Mixed scenes use automatic balanced tuning.
- Use temporal and chroma-aware denoise where the current pipeline supports it.
- Use edge-aware sharpening so cleanup does not become blurry.

Success rule: reduce visible HDR RGB/chroma noise and color mismatch without inventing unsupported bit depth, RAW, or HDR behavior.

## Phase 3: Performance And Stability

Performance is a release gate.

Required behavior:

- Fix blocking problems first: crashes, freezes, recording failure, camera disconnect, and encoder failure.
- Fix visible problems next: preview jank, dropped frames, slow startup, recording start lag, and thermal warnings.
- Keep heavy image processing off the UI thread.
- Keep the app responsive while recording and while switching modes.
- Use adaptive fallback when Experimental Pro causes lag, heat, encoder pressure, or unstable FPS.
- No phase is complete until build, tests, install, device validation, and performance profiling are acceptable.

Success rule: high-end paths are allowed only if profiling shows they stay smooth enough on the target device.

## Phase 4: PRO Controller UI

The main camera screen is redesigned around the provided reference image.

Required behavior:

- PRO mode shows a reference-style manual controller:
  - one selected control uses a large horizontal scale
  - bottom row buttons switch ISO, shutter, WB, AF, and EV
  - active control is highlighted
  - current value is clearly shown near the scale
- Manual controls are directly visible only in PRO mode.
- Non-PRO modes keep a cleaner UI, but manual controls remain available behind a panel.
- RAW, LOG, HDR, PRO, 10-bit, 12-bit, codec, resolution, and FPS move to pro settings or status areas instead of crowding the manual controller.
- UI must avoid overlap, clipped text, and camera preview lag.

Success rule: PRO mode feels like the reference controller while other modes remain clean and usable.

## Phase 5: Architecture Cleanup

Architecture cleanup is major but phased after camera behavior is stable.

Target boundaries:

- `camera-contract`: capabilities, stream planning, ISP-bypass truth, fallback reasons.
- `recording`: encoder setup, dynamic range, bit depth, muxing, and audio/video sync.
- `image-quality`: HDR color, denoise, sharpness, and scene profile decisions.
- `performance`: profiling, thermal policy, fallback timing, jank, and encoder pressure signals.
- `ui-controller`: PRO controller state, manual control mapping, and mode panels.
- `diagnostics`: user-visible warnings, debug logs, and validation summaries.

Dependency direction:

- UI calls feature contracts.
- Camera and recording do not depend on UI.
- Diagnostics observe outcomes instead of controlling features.
- Experimental paths must always have stable fallback paths.

Success rule: cleaner structure, fewer oversized files, testable contracts, and no feature removed.

## Phase 6: Whole-App Integration Pass

The final pass ties the app together after subsystem work is stable.

Required behavior:

- Remove duplicated or conflicting feature logic.
- Verify all modes: PRO, LOG, HDR, RAW, normal video, and photo.
- Verify all preview identities: LOG in LOG, HDR in HDR, RAW-oriented in RAW, normal in PRO/normal.
- Verify default metering/focus/exposure behavior and manual panel access outside PRO.
- Verify diagnostics are visible and understandable when fallback happens.
- Verify no existing feature is intentionally removed.

Success rule: the whole app is coherent, deployable, and validated by device tests and profiling logs.

## Test Checklist

Each implementation phase must run at least the relevant subset of:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Focused tests must be added or updated for:

- stream planning and fallback rules
- RAW/full-sensor selection
- ISP-bypass coverage reporting
- HDR color metadata selection
- HDR noise/sharpness profile selection
- Experimental Pro fallback timing
- PRO controller state and visibility
- diagnostics message generation

## Deployment Checklist

After a successful APK build:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -c
```

Minimum debug validation flows:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity -a com.neoproc.neoproccamx.DEBUG_COMMAND --es debug_preset RAW_MODE --es debug_action record_clip --ei debug_duration_ms 2500 --es debug_video_mode RAW --ez debug_raw true --es debug_validation_label full_sensor_raw_validation

& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity -a com.neoproc.neoproccamx.DEBUG_COMMAND --es debug_preset PRO --es debug_action record_clip --ei debug_duration_ms 2500 --es debug_video_hdr_mode HLG --es debug_video_color_space Rec.709 --es debug_validation_label hdr_color_noise_validation
```

Crash check:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -d -b crash
```

## Profiling Checklist

Each phase must collect and review:

- startup timing
- camera open/session configure timing
- recording prepare/start/stop timing
- preview frame stability
- dropped frames or jank
- encoder pressure
- memory usage
- thermal state
- fallback events
- crash buffer

Performance findings are handled in this order:

1. Fix crashes, freezes, camera disconnects, and recording failures.
2. Fix visible jank, dropped frames, startup lag, recording lag, and thermal warnings.
3. Treat unacceptable profiling evidence as a phase blocker.

## User-Visible Diagnostics

Warnings must be direct and factual. Examples:

- "8K requested, but camera/encoder exposed 4096x3072. Using stable 4K path."
- "Complete ISP bypass unavailable: distortion correction off is not exposed by this HAL."
- "Experimental Pro path unstable. Recording continued using stable fallback."
- "12-bit RAW unavailable on this camera. RAW capture uses supported bit depth."

## Non-Goals

- Do not fake unsupported 8K, RAW, HDR, 10-bit, 12-bit, or ISP-bypass behavior.
- Do not remove existing features.
- Do not hide hardware fallback from diagnostics.
- Do not perform an unsafe blind rewrite without phase validation.

## Approval Gate

Implementation can start only after this design is reviewed and approved. The next step after approval is a detailed implementation plan.
