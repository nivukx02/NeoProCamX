# OIS / Gyro Motion Telemetry Capture — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f04 |
| **Maps to checkpoint(s)** | CP60 (wire real gyro/OIS telemetry into the GPU warp — true gyro-EIS) |
| **Current working level** | 🟡 partial (telemetry captured live, consumers dormant) |
| **Feature rating (potential)** | 5/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · L |
| **Depends on** | s1-f05 (session/timestamps); feeds s1-f06 (MFSR global-motion term), subsystem-3 gyro-EIS (CP60-64) |

## 1. What this feature is
The motion-sensing layer: a real `TYPE_GYROSCOPE` listener at `SENSOR_DELAY_FASTEST` feeding a
1024-deep ring buffer of timestamped angular-velocity samples, plus `STATISTICS_OIS` sample reads
and `LENS_OPTICAL_STABILIZATION_MODE` handling. This telemetry is the ground truth of *global*
camera motion — what the gyro-EIS warp needs to cancel handshake, what rolling-shutter rectification
needs per-scanline, and what MFSR needs as its global-motion alignment term. It is captured well
but, today, largely unconsumed.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Motion telemetry in pro systems is *clock-locked to the exposure*:
- **Google "Fused Video Stabilization"** (the canonical phone-EIS reference) integrates a
  high-rate gyro stream into an orientation trajectory, reconciles gyro samples to each frame's
  exposure window via the sensor timestamp, and drives a per-scanline rolling-shutter-aware warp.
- **OIS-aware fusion:** professional bodies read the lens's OIS displacement and *subtract* the
  motion the OIS already absorbed, so EIS doesn't double-correct.
- **Rolling-shutter rectification** requires the per-row readout time and the gyro orientation at
  each row — skew is a function of angular velocity × readout time.

The hard, measurable requirements are about *time*, not just samples: (1) **clock-domain
reconciliation** — gyro sample timestamps and `SENSOR_TIMESTAMP` share a base, so the orientation
at exposure-start/mid/end is interpolatable to sub-millisecond; (2) **no gaps** in the ring at
`FASTEST` rate over a recording; (3) **OIS sign/scale calibrated** so subtracting OIS motion
reduces, never adds, residual; (4) trajectory integration accuracy bounded vs an analytic rotation.

## 3. Current state in NeoProCamX (verified)
- Real gyro listener + ring buffer and OIS/`LENS_OPTICAL_STABILIZATION_MODE` handling live in
  `camera/CameraController.kt` / `camera/HardwareAbstractionLayer.kt` (verified by grep for
  `TYPE_GYROSCOPE`/`STATISTICS_OIS`/`LENS_OPTICAL_STABILIZATION`/`SENSOR_DELAY_FASTEST`); stabilization
  request policy in `camera/CameraVideoStabilizationRequestPolicy.kt`.
- Catalog rates this 🟡 feat 5 / quality 7: real `TYPE_GYROSCOPE` at `FASTEST` into a 1024-deep
  ring, `STATISTICS_OIS` + `LENS_OPTICAL_STABILIZATION_MODE`; **"captured but largely unconsumed —
  stabilization/MFSR consumers are dormant"** (FEATURE_CATALOG.md §1).
- **Consumer state:** CP60 foundation (`stabilization` package — `Quaternion`,
  `GyroOrientationIntegrator`, `GyroEisWarp`) is built and JVM-tested but DORMANT and not GPU-wired
  (roadmap CP60, commit `e105117`); MFSR's gyro global-motion term is dormant (s1-f06). So the
  *capture* is solid; the missing piece is the **timestamp-reconciled hand-off** to those consumers.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Clock-domain reconciliation:** a policy maps gyro sample timestamps to the camera
   `SENSOR_TIMESTAMP` base and interpolates orientation at exposure-window endpoints; tested with
   synthetic clocks for sub-ms endpoint error.
2. **Gap-free ring at rate:** over a synthetic `FASTEST`-rate stream, the 1024-deep ring exposes a
   contiguous time-ordered window with no dropped intervals; a test asserts monotonic timestamps
   and bounded inter-sample gap.
3. **Integration accuracy:** `GyroOrientationIntegrator.rotationBetween(t0,t1)` matches an analytic
   constant-rate rotation within tolerance (already partly covered by CP60's 16 tests — extend to
   the exposure-window endpoints).
4. **OIS fusion correctness:** subtracting the `STATISTICS_OIS`-reported displacement reduces
   residual motion (sign/scale calibrated); tested with full/partial/no-OIS fixtures (CP63
   foundation already asserts this — link the real telemetry source).
5. **MFSR global-motion term:** the integrated global rotation is exposed in the exact form MFSR
   (s1-f06) consumes for its per-frame alignment; a boundary test proves the value is non-zero and
   correctly scaled when the device moves.
6. **No-downgrade default:** all consumption is dormant/gated; capturing telemetry does not change
   any live pixel. The GPU warp push is DEVICE-GATED (clock-domain validation on hardware).
7. **Budget:** the listener runs on its own sensor callback; reconciliation is O(window) per frame,
   off the per-pixel path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add timestamp-reconciled, gap-free telemetry hand-off ready for the EIS/MFSR consumers;
keep everything dormant.
**Non-goals:** do not GPU-wire the EIS warp (CP60 device track); do not change live stabilization
behavior; no new sensor model or library.

**Plan:**
1. **Golden-lock** the current capture behavior (ring depth, sample handling) so capture is
   provably unchanged.
2. **Reconciliation policy.** New `stabilization/GyroExposureWindowReconciler.kt`: given the gyro
   ring + a frame's `SENSOR_TIMESTAMP` + exposure duration, return interpolated orientation at
   start/mid/end. Pure Kotlin, fully unit-tested with synthetic clocks.
3. **Ring health.** Add a contiguity/monotonicity assertion path over the ring (test-only invariant
   producer; no live behavior change).
4. **Consumer hand-offs.** Provide the MFSR global-motion term (s1-f06 form) and the
   `GyroOrientationIntegrator` feed for CP60 — both behind dormant flags.
5. **Device note.** DEVICE-GATED: validate gyro↔`SENSOR_TIMESTAMP` clock alignment and OIS sign/scale
   on the vivo before any consumer goes live.

**Files:** `camera/CameraController.kt` (telemetry source hand-off), `camera/HardwareAbstractionLayer.kt`;
new `stabilization/GyroExposureWindowReconciler.kt`; reuse existing `stabilization/*` (CP60).

**ABI:** no GPU push-constant change in this prompt (the warp-grid push is CP60's, device-gated).

**Gating:** all consumers dormant/flagged off; capture behavior unchanged (no-downgrade).

## 6. Acceptance & verification
- **Unit/golden (JVM):** exposure-window interpolation sub-ms error; ring monotonicity/gap bound;
  integration vs analytic rotation; OIS-subtraction residual reduction (full/partial/no OIS);
  MFSR-term non-zero-and-scaled-on-motion boundary test.
- **Device (vivo I2405):** confirm gyro↔camera timestamp share a base (log both, check drift);
  OIS sign/scale calibration; trajectory matches a known controlled rotation.
- **Gate keys:** none change live until CP60 device-validates; gyro-EIS capability proves through
  `OmegaCapabilityGate` only from real warp transport.

## 7. Risks & no-downgrade guardrails
- **Clock mismatch → over/under-correction:** reconciliation tested in synthetic time + DEVICE-GATED
  hardware validation before any live use.
- **OIS double-correction:** OIS subtraction with calibrated sign/scale, residual-reduction test.
- **Live regression:** all consumption dormant/flagged; capture golden locks current behavior.
- **Lag:** reconciliation is bounded per-frame, off the pixel path.

## 8. References
- Roadmap: CP60/CP61/CP62/CP63/CP64 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/11-pixel-reality.md`](../technology_explanations/markdown/11-pixel-reality.md)
- Reference: Google "Fused Video Stabilization" (gyro trajectory + rolling-shutter + OIS-aware EIS).
- Source: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`, `app/src/main/java/com/neoproc/neoproccamx/camera/HardwareAbstractionLayer.kt`, `app/src/main/java/com/neoproc/neoproccamx/camera/CameraVideoStabilizationRequestPolicy.kt`
- Related prompts: s1-f05, s1-f06, subsystem-3 gyro-EIS prompts
