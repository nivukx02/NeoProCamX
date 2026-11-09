# EIS Warp + Trajectory Smoothing + Rolling-Shutter + OIS/EIS Fusion + Mode Policy — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract applies here
> and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f06 |
| **Maps to checkpoint(s)** | CP60 (gyro-EIS warp), CP61 (trajectory smoothing + crop), CP62 (rolling-shutter), CP63 (OIS/EIS fusion), CP64 (mode policy / horizon) — foundations DONE, GPU warp device-gated; CP65 (NPV10 export parity) |
| **Current working level** | 🟠 dormant (full pure-Kotlin stack, JVM-tested, no GPU EIS warp shader, zero production callers) |
| **Feature rating (potential)** | 2/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 (pinhole warp + one-pole SLERP smoother + per-scanline RS grid + OIS fusion + mode policy, all analytically tested) |
| **Risk · Size** | H · XL |
| **Depends on** | s3-f05 (gyro orientation is the input), s1-f04 (gyro/OIS telemetry capture) |

## 1. What this feature is
This is the full electronic-stabilization (gyro-EIS) back-end that turns the s3-f05 orientation path
into a per-frame image warp. `OrientationTrajectorySmoother` computes a smooth virtual-camera path
(causal one-pole SLERP) and the per-frame residual `raw⁻¹·smoothed`; `GyroEisWarp` maps that residual
through a small-angle pinhole model to a frame-centre translation + in-plane roll, clamped to a crop
margin; `RollingShutterGrid` extends this per-scanline (each row corrected against a reference row's
readout time) to remove jello/skew; `OisEisFusion` subtracts the motion the lens OIS already absorbed
so EIS doesn't double-correct; `StabilizationModePolicy` selects OFF / STANDARD (smoothed path) /
LOCKED (fixed reference). Together they are a complete stabilization brain — with no GPU warp to execute it.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is again **Google "Fused Video Stabilization"** plus the cinema rig bar:
- **Trajectory L1/low-pass optimization:** the raw orientation path is smoothed into a virtual-camera
  path (Google uses an L1-optimal path of constant/linear/parabolic segments; a causal one-pole
  low-pass is the real-time cousin). The per-frame correction is the residual between raw and smoothed.
  Measurable: **residual high-frequency jitter** cut by a large factor with **no visible
  lag/overshoot** on intentional pans.
- **Rolling-shutter rectification:** CMOS sensors read row-by-row, so motion *within* a frame skews
  verticals ("jello"). Correct EIS applies a *per-scanline* warp using the orientation at each row's
  readout time. Measurable: **skew error** (deviation of a vertical edge under constant yaw) reduced to
  near zero; the correction must be linear and centre-symmetric in row for constant angular velocity.
- **OIS/EIS fusion:** if the lens OIS already shifted the image, EIS must subtract that or it
  double-corrects and overshoots. Requires OIS sign/scale calibration.
- **Crop-margin budget:** EIS steals a fixed margin to shift within; corrections beyond the margin must
  clamp gracefully (no black border ever exposed). Cinema gimbals/Steadicam set the steadiness bar;
  IMAX framing must stay rock-steady. Measurable: **crop margin** held constant, **zero border reveal**.
- **Horizon-level / locked modes** need *absolute* attitude (gravity), not just integrated gyro.

## 3. Current state in NeoProCamX (verified)
- **Full stack built, JVM-tested, DORMANT.** In `com.neoproc.neoproccamx.stabilization`:
  - `GyroEisWarp.kt` (CP60, `e105117`) — small-angle pinhole rotation→`EisCorrection(dx,dy,rollRadians)`
    + crop-margin fit/clamp. Its own doc states it is "dormant: the device-gated step is choosing the
    smoothed reference orientation and **pushing the resulting per-scanline grid to the GPU**."
  - `OrientationTrajectorySmoother.kt` (CP61, `948fb13`) — causal one-pole SLERP, α=1−exp(−2π·fc·dt),
    smooth path + per-frame residual; JVM-tested (jitter-range shrink, tighter-cutoff-tracks-closer).
  - `RollingShutterGrid.kt` (CP62, `948fb13`) — `perRowCorrections` per-scanline vs a reference row via
    `rotationBetween` over each row's readout time; JVM-tested (zero-motion→zero; constant yaw→linear
    centre-symmetric skew monotonic in row).
  - `OisEisFusion.kt` (CP63, `948fb13`) — residual EIS = gyro correction − OIS-absorbed shift (roll
    OIS-independent); JVM-tested (full/partial/no OIS).
  - `StabilizationModePolicy.kt` (CP64, `c355f33`) — OFF / STANDARD / LOCKED → crop-clamped
    `EisCorrection`; 5 JVM tests (OFF→0; STANDARD→0 on-path; LOCKED→0 at ref; LOCKED clamps 0.2rad yaw
    into 24 px crop; LOCKED tracks absolute offset vs STANDARD path-deviation).
- **The break:** there is **no GPU EIS warp shader** for this path and **zero production callers** —
  the entire stack is pure-Kotlin math consumed only by its own tests. The hardware
  `CameraController.stabilizationMode` (`CameraController.kt:756, 1531-1540, 3384`) drives the *Camera2
  OIS / video-stabilization* request, NOT this custom gyro-EIS stack — they are independent.
- **Honest residuals (DEVICE-GATED, per roadmap):** gyro↔`SENSOR_TIMESTAMP` clock reconciliation (CP60),
  cutoff-vs-crop tuning + reference selection (CP61), real `SENSOR_ROLLING_SHUTTER_SKEW`/timestamp
  reconciliation (CP62), OIS sign/scale calibration (CP63), and **horizon-level deferred** — it needs
  ABSOLUTE roll from accelerometer/gravity fusion the gyro-only `CameraMotionTelemetry` lacks (CP64).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **GPU EIS warp shader (the missing keystone):** create a compute/vertex warp that consumes the
   per-scanline `RollingShutterGrid` correction (a sampled grid) and resamples the frame — bilinear+,
   crop-margin-clamped, **never revealing a black border**. Host test of the grid; validation-layer
   clean on the shader.
2. **Residual jitter reduction (device):** STANDARD mode cuts high-frequency angular jitter by a large
   measured factor vs the raw path, with no lag/overshoot on intentional pans (gyro-log replay metric).
3. **Skew error (device):** under constant yaw a vertical edge's skew is reduced to near zero; the
   per-row correction is linear + centre-symmetric (already host-proven; confirm on real skew).
4. **OIS no double-correction (device):** with OIS active, fused residual ≈ gyro − OIS-absorbed; a
   static-with-OIS scene shows no overshoot — requires the OIS sign/scale calibration.
5. **Mode correctness:** OFF = exact passthrough (the no-downgrade default), STANDARD = smoothed path,
   LOCKED = fixed reference; horizon-level flagged **DEVICE-GATED** pending gravity fusion.
6. **Export parity (CP65):** NPV10 export can replay the recorded motion to apply the same warp
   (deferred; depends on CP60/CP61).
7. **No-downgrade default:** the GPU warp is behind a flag/spec-constant defaulting to **OFF =
   identity resample = today's pixels bit-for-bit**; only the owner enables it device-side.
8. **Budget:** the warp is one resample pass; crop margin fixed; governor can drop to OFF under thermal.

## 5. Implementation prompt (hand this to the agent)
**Scope:** create the GPU EIS warp shader + the JNI push of the per-scanline warp grid, wire the
(s3-f05) orientation → smoother → RS-grid → OIS-fusion → mode-policy chain into it, all behind an
OFF-by-default gate.
**Non-goals:** do NOT change any stabilization-package math (all CP60-64-locked + tested); do NOT add
an absolute-roll/horizon sensor path (device-gated, gravity fusion — flag it, don't build it); do NOT
touch the hardware `CameraController.stabilizationMode` OIS path (independent); do NOT add a library
(no-vendor — pinhole warp + SLERP + per-row grid are classical).

**Plan:**
1. **Golden-first:** lock today's output — with EIS OFF the warp shader must be a bit-for-bit identity
   resample (golden capture parity). Add host tests pinning the grid the shader will consume.
2. **GPU warp shader:** create the EIS warp compute/vertex shader (bilinear+ resample, crop-margin
   clamp, never reveal border), generate its SPV header, register it in `vulkan_bridge.cpp`, and add a
   spec constant defaulting to identity/OFF.
3. **Grid JNI push:** push `RollingShutterGrid.perRowCorrections` (the per-scanline `EisCorrection`
   grid) from the s3-f05 orientation consumer through `StabilizationModePolicy` → `OisEisFusion` →
   `GyroEisWarp` into the shader, behind the OFF gate.
4. **Mode wiring:** expose OFF/STANDARD/LOCKED in UI; OFF default. Flag horizon-level as DEVICE-GATED
   (needs gravity fusion).
5. **Device notes:** clock reconciliation (CP60), cutoff-vs-crop tuning (CP61), real skew reconciliation
   (CP62), OIS sign/scale calibration (CP63) — each an explicit on-device step before enabling.

**Files:** new `*_eis_warp.comp` + generated SPV header; `app/src/main/cpp/vulkan_bridge.cpp` (pipeline,
descriptor, grid push, dispatch behind the gate); `rendering/VulkanBridge.kt` (JNI); the `stabilization/*`
classes (consume only); `camera/CameraController.kt` / pipeline (the grid producer from s3-f05); UI mode
toggle; new push-constant struct + Kotlin mirror.

**ABI:** the warp grid + crop-margin + mode become a **new** push constant / UBO — add a
`*PushConstantLayoutTest` (or grid-UBO layout test) asserting slot order + int/float typing, std140
alignment, in the SAME commit; pin the new shader-manifest hash. ABI is sacred.

**Gating:** GPU warp behind a spec constant / flag defaulting **OFF = identity resample** → today's
pixels unchanged bit-for-bit until the owner enables EIS device-side after the clock/OIS/skew calibrations.

## 6. Acceptance & verification
- **Unit/golden (JVM):** EIS-OFF identity golden (no-downgrade lock); host tests for the warp grid,
  smoother residual, RS per-row correction, OIS fusion, and mode policy (re-run the existing 26+ tests);
  new push-constant/UBO layout test.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on the warp pass; OFF gate
  produces an identity resample.
- **Device (vivo I2405 / Adreno):** (1) EIS OFF — output identical to today (parity capture); (2) handheld
  walk, STANDARD — residual jitter cut, no border reveal, no overshoot on pans; (3) constant yaw — skew
  near zero; (4) OIS active — no double-correction; (5) thermal soak — warp drops to OFF, no spike/kill;
  (6) clock-offset + OIS sign/scale calibrations recorded.
- **Gate keys:** if EIS contributes to the recorded master, the capability proves through
  `OmegaCapabilityGate` from real transport — never synthesize a pass; horizon-level lands `NOT_AVAILABLE`
  until gravity fusion exists.

## 7. Risks & no-downgrade guardrails
- **Black-border reveal:** crop-margin clamp in `GyroEisWarp` + a shader clamp → impossible to expose
  a border; EIS-OFF identity golden proves the default never warps.
- **Double-correction / overshoot:** `OisEisFusion` + device OIS calibration; OFF by default until calibrated.
- **Jello/skew worse-not-better:** per-scanline grid host-proven linear+centre-symmetric; real-skew
  reconciliation device-gated before enabling.
- **Phantom motion (bad clock):** s3-f05 clock reconciliation + static-scene zero-residual check gates activation.
- **Horizon tilt without gravity:** horizon-level flagged DEVICE-GATED / `NOT_AVAILABLE` — not shipped
  on gyro-only data.
- **Lag/thermal:** one resample pass, governor can force OFF; default path is identity (no added cost).

## 8. References
- Roadmap: CP60/CP61/CP62/CP63/CP64/CP65 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: Google "Fused Video Stabilization" (trajectory L1 optimization + rolling-shutter
  rectification + gyro-EIS); cinema gimbals/Steadicam + IMAX framing steadiness as the residual-jitter
  bar; rolling-shutter skew-error metric; crop-margin budget.
- Source: `stabilization/{GyroEisWarp,OrientationTrajectorySmoother,RollingShutterGrid,OisEisFusion,StabilizationModePolicy}.kt`
  (+ their tests); hardware OIS path (independent) `camera/CameraController.kt:756, 1531, 3384`.
- Related prompts: s3-f05, s1-f04
