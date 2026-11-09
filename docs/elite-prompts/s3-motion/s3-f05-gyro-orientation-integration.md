# Gyro Orientation Integration — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract applies here
> and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f05 |
| **Maps to checkpoint(s)** | CP60 (wire real gyro telemetry into orientation fusion — foundation DONE, GPU push device-gated) |
| **Current working level** | 🟠 dormant (pure-Kotlin math, JVM-tested, zero production callers) |
| **Feature rating (potential)** | 2/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 (Hamilton quaternion + trapezoidal integration, analytically validated) |
| **Risk · Size** | H · XL |
| **Depends on** | s3-f06 (EIS warp consumes the orientation), s1-f04 (gyro telemetry capture) |

## 1. What this feature is
This is the orientation-fusion keystone for gyro-EIS stabilization: it integrates the device's
angular-velocity stream into an absolute-since-start rotation, so any later stage can ask "how was
the camera oriented at frame N versus frame M?". `Quaternion` is a Hamilton unit quaternion
(slerp / rotation-vector / axis-angle / exp-map). `GyroOrientationIntegrator` does trapezoidal
integration of the *existing live* `CameraMotionTelemetry` gyro stream and exposes a windowed
`rotationBetween()` with interpolated exposure-window endpoints — the exact rotation that occurred
across a frame's exposure, which is what an EIS warp (s3-f06) and rolling-shutter correction need.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is **Google's "Fused Video Stabilization"** (Pixel) and the cinema steadiness bar of
gimbals/Steadicam:
- **Gyro integration into an orientation path** is the front-end of every gyro-EIS system: a
  high-rate gyro (≥200 Hz) is integrated to a per-frame orientation, then a *smoothed virtual-camera
  path* is computed and the per-frame residual (`raw⁻¹·smoothed`) drives the warp. Quaternion
  integration (not Euler) is mandatory to avoid gimbal lock and drift artifacts.
- **Exposure-window reconciliation** is the make-or-break detail: the gyro clock and the camera
  `SENSOR_TIMESTAMP` are different clock domains, and each frame integrates motion over its *exposure
  window* (and per scanline for rolling shutter). Getting this wrong skews the whole correction. The
  bar: gyro samples interpolated to the exact exposure-start/end timestamps of each frame.
- **Cinema steadiness bar:** a 3-axis gimbal / Steadicam removes hand tremor to sub-pixel residual
  jitter; IMAX presentation demands rock-steady framing. The measurable target for EIS is **residual
  angular jitter** (the high-frequency component the smoother removes) reduced by a large factor vs
  the raw path, with no low-frequency lag/overshoot introduced.
- The orientation layer itself is judged by **integration accuracy**: integrating a known synthetic
  angular-velocity profile must reproduce the analytic rotation within a tight tolerance.

## 3. Current state in NeoProCamX (verified)
- **Built, JVM-tested, and DORMANT (CP60 foundation, commit `e105117`).** The new
  `com.neoproc.neoproccamx.stabilization` package contains `Quaternion.kt`,
  `GyroOrientationIntegrator.kt` (and the downstream `GyroEisWarp`/`OrientationTrajectorySmoother`/
  `RollingShutterGrid`/`OisEisFusion`/`StabilizationModePolicy` — see s3-f06). 16 JVM tests validate
  the integrator against analytic rotations, all green.
- **The gyro stream IS live-fed.** `CameraMotionTelemetry` (the `TYPE_GYROSCOPE` listener at
  `SENSOR_DELAY_FASTEST` into a 1024-deep ring) is referenced by `CameraController.kt` and
  `CameraViewModel.kt` — gyro is sampled in production. `GyroOrientationIntegrator` is designed to
  consume exactly this stream.
- **But it dead-ends.** The integrator and `Quaternion` are referenced **only** by other
  `stabilization/*` classes and their tests — **zero production callers** integrate the stream into
  an orientation that reaches any pixel. The audit confirmed the gyro ring is live-fed but the CPU
  consumer is dead-ended; this foundation is the missing orientation-fusion keystone.
- **The honest residuals (DEVICE-GATED, from the roadmap):** gyro↔`SENSOR_TIMESTAMP` exposure-window
  reconciliation (clock-domain alignment) is not yet validated on real hardware; absolute roll for a
  horizon-level mode needs accelerometer/gravity fusion the gyro-only `CameraMotionTelemetry` lacks
  (see s3-f06).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Integration accuracy (host):** integrating a synthetic constant/ramped angular-velocity profile
   reproduces the analytic quaternion within a stated tolerance (extend the existing 16-test suite
   with a long-duration drift bound).
2. **Exposure-window rotation (host):** `rotationBetween(exposureStart, exposureEnd)` linearly
   interpolates the gyro samples to the exact endpoints; a known profile yields the analytic
   mid-exposure rotation within tolerance.
3. **Clock-domain reconciliation (DEVICE-GATED):** gyro sample timestamps are aligned to camera
   `SENSOR_TIMESTAMP` (same monotonic clock or a measured offset) so each frame integrates over its
   true exposure window. On-device check: a measured-offset calibration + a static-scene zero-residual
   test (no phantom motion when the camera is still).
4. **Live wiring without effect:** a production caller integrates the live `CameraMotionTelemetry`
   stream each frame and exposes the per-frame `rotationBetween` to s3-f06, but **the result is not
   applied to any pixel** until s3-f06's GPU warp is device-enabled — so this prompt is zero-pixel-change.
5. **No-downgrade default:** the integrator runs (cheap scalar math) but its output drives nothing
   visible; today's preview/record/export pixels are untouched.
6. **Budget:** integration is O(samples-per-frame) scalar math off the GPU hot path; bounded ring.

## 5. Implementation prompt (hand this to the agent)
**Scope:** stand up a production consumer that integrates the live gyro stream into a per-frame
orientation + exposure-window rotation, and reconcile the gyro/camera clock domains — *without*
applying any correction to pixels (that is s3-f06, gated separately).
**Non-goals:** do NOT change the `Quaternion`/`GyroOrientationIntegrator` math (CP60-locked, tested);
do NOT add a GPU warp here (s3-f06); do NOT add an absolute-roll/horizon sensor here (device-gated,
s3-f06); do NOT add a sensor-fusion library (no-vendor — trapezoidal quaternion integration is classical).

**Plan:**
1. **Golden-first:** extend the integrator tests with a long-duration drift bound and an
   exposure-window interpolation case before any wiring.
2. **Live consumer:** add a production path (in `CameraController`/pipeline) that pulls the live
   `CameraMotionTelemetry` ring each frame, integrates it, and publishes the per-frame `rotationBetween`
   over the frame's exposure window — but routes it only to s3-f06's (still-dormant) warp input.
3. **Clock reconciliation (DEVICE-GATED):** align gyro timestamps to `SENSOR_TIMESTAMP`; record the
   on-device calibration + static-scene zero-residual check. Until validated, the consumer computes
   the orientation but the warp stays off.
4. **Device note:** the exact on-device clock-offset measurement + static-scene zero-motion check.

**Files:** `stabilization/Quaternion.kt`, `stabilization/GyroOrientationIntegrator.kt` (consume only),
`camera/CameraController.kt` / the pipeline (the new live consumer + telemetry tap),
`camera/CameraMotionTelemetry` source (timestamp exposure), the integrator tests.

**ABI:** no GPU push constant here (this prompt produces an *orientation*, not a warp grid — the grid
push is s3-f06). If a small JNI handshake is added to hand the orientation to native staging, keep it
behind the s3-f06 gate and add no live shader read.

**Gating:** the consumer runs but its output is wired only to the dormant s3-f06 warp (OFF by default)
→ zero pixel change until the owner enables EIS device-side.

## 6. Acceptance & verification
- **Unit/golden (JVM):** extended integrator suite (drift bound, exposure-window interpolation,
  analytic-rotation reproduction); a test proving the live consumer publishes a rotation but never
  triggers a visible warp while EIS is off.
- **Native:** if a JNI staging handshake is added, `:app:externalNativeBuildDebug` green; no live
  shader reads the orientation.
- **Device (vivo I2405 / Adreno):** (1) static camera — integrated orientation drift bounded, the
  per-frame `rotationBetween` near-identity (no phantom motion); (2) measured gyro↔SENSOR_TIMESTAMP
  clock offset recorded; (3) known rotation (turntable / controlled pan) — integrated angle matches
  within tolerance.

## 7. Risks & no-downgrade guardrails
- **Clock-domain misalignment (the headline risk):** device-gated calibration + static-scene
  zero-residual check before s3-f06 is ever enabled → no phantom-motion correction ships.
- **Integration drift:** quaternion (not Euler) integration + drift-bound test; the integrator is
  re-referenced per smoothing window, not allowed to accumulate unbounded.
- **Accidental pixel change:** output wired only to the dormant s3-f06 warp; a test asserts no visible
  warp fires while EIS is off.
- **Lag/thermal:** scalar integration off the GPU hot path; bounded ring buffer.

## 8. References
- Roadmap: CP60 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: Google "Fused Video Stabilization" (gyro integration → smoothed path → residual warp);
  quaternion integration (avoid gimbal lock); gimbal/Steadicam + IMAX steadiness as the jitter bar.
- Source: `stabilization/Quaternion.kt`, `stabilization/GyroOrientationIntegrator.kt`;
  `CameraMotionTelemetry` (gyro ring) consumed by `camera/CameraController.kt`, `ui/CameraViewModel.kt`.
- Related prompts: s3-f06, s1-f04
