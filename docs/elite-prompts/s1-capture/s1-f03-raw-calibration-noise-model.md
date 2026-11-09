# RAW Sensor Calibration & Noise-Model Policies — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f03 |
| **Maps to checkpoint(s)** | feeds **CP12** (MFSR noise model) and **CP15** (dark-current / hot-pixel maps for demosaic) |
| **Current working level** | 🔵 live-optional (RAW descriptor built when RAW captured) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s1-f01 (RAW serialization), s1-f05 (session); consumed by s1-f06 (MFSR), s1-f07/f08 (demosaic) |

## 1. What this feature is
The calibration layer that turns raw photosite counts into physically-meaningful linear values and
describes the sensor's noise statistically. It resolves black level (dynamic per-frame metadata ›
static `BLACK_LEVEL_PATTERN` › scaled fallback), clamps to white level, and reads the shot/read
noise coefficients from `SENSOR_NOISE_PROFILE` into the RAW buffer descriptor. Everything
downstream that needs to know "how confident am I in this sample" — MFSR's heteroscedastic veto,
temporal denoise strength, demosaic edge thresholds — depends on these numbers being correct and
actually reaching the consumer.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema sensors are *characterized*, not guessed:
- **Per-ISO calibration:** ARRI/RED/Sony measure black level, fixed-pattern noise, dark current,
  photo-response non-uniformity (PRNU) and the read/shot split per gain stage. The signal-dependent
  noise model `σ²(signal) = σ_read² + σ_shot²·signal` is the textbook (Poisson photon shot +
  Gaussian read) form a colorist's NR tools assume.
- **Dark-frame / hot-pixel maps:** professional bodies subtract a measured dark frame and carry a
  defect map so a hot pixel never seeds demosaic interpolation as if it were signal (this is the
  CP15 dependency).
- **DNG `NoiseProfile`** standardizes the `(scale, offset)` per channel so any tool denoises to the
  real floor, not a constant — exactly the values this feature must surface.

Engineering targets translated: (1) **black level correct to ±1 code** vs the metadata-reported
value, with the dynamic › static › fallback precedence proven by tests; (2) **noise model matches
the measured curve** — at a few signal levels, the predicted σ is within tolerance of the
`SENSOR_NOISE_PROFILE` `(S,O)` coefficients; (3) the model **actually reaches** MFSR `noiseS/noiseO`
and the denoise/demosaic thresholds (not stranded at 0 as the MFSR audit found); (4) a defect/
dark-current map is available to demosaic (CP15) so hot pixels are rejected, measurable as no
isolated hot speckles in a dark-frame golden.

## 3. Current state in NeoProCamX (verified)
- The calibration/noise consumer lives in `pipeline/RawVideoManager.kt` (references
  `SENSOR_NOISE_PROFILE`) and the policy in `camera/CameraSensorNoiseModelPolicy.kt` (verified by
  grep for the `SENSOR_NOISE_PROFILE`/`BLACK_LEVEL_PATTERN` cluster). HAL exposes the metadata via
  `camera/HardwareAbstractionLayer.kt`.
- Catalog rates this 🔵 feat 7 / quality 8: black-level precedence (dynamic › static › scaled
  fallback), white-level clamp, shot/read coefficients from `SENSOR_NOISE_PROFILE` into the RAW
  buffer descriptor (FEATURE_CATALOG.md §1).
- **The load-bearing gap:** the coefficients are read into the descriptor but the **downstream MFSR
  consumer runs with `noiseS=noiseO=0`** (s1-f06 / CP12 audit — `sigmaThresh=0` then rejects every
  non-reference frame). So the calibration is *computed but not plumbed* into the stage that needs
  it. There is **no dark-current / hot-pixel map** yet (CP15 is unstarted). This feature's elite
  work is *plumbing correctness + adding the defect map*, not new math.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Black-level precedence proven:** a test drives all three sources (dynamic metadata, static
   `BLACK_LEVEL_PATTERN`, scaled fallback) and asserts the correct one wins and the subtraction is
   exact to ±1 code; white-level clamp never overflows.
2. **Noise-model correctness:** given `SENSOR_NOISE_PROFILE` `(S,O)` per channel, the predicted
   `σ²(signal)` matches `O + S·signal` within tolerance at sampled signal levels (unit test).
3. **Plumbing reaches consumers:** a test proves the same `(S,O)` that the descriptor holds is the
   value handed to MFSR `noiseS/noiseO` and to the denoise/demosaic threshold inputs — i.e. they
   are never 0 when a real profile exists (closes the CP12 "noise=0" defect at the source).
4. **Defect / dark-current map (CP15):** build a per-sensor hot-pixel/dark-current map (from a
   captured dark frame or `HOT_PIXEL` info) consumable by demosaic; a dark-frame golden shows hot
   pixels rejected (no isolated speckle survives interpolation).
5. **No-downgrade default:** if no real profile/defect map is available, behavior falls back to
   today's exactly (identity defect map, today's noise handling); RAW output bytes unchanged.
6. **Budget:** calibration is per-frame metadata math (negligible); defect-map build is a one-time
   per-sensor cost, cached, off the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make calibration/noise *correctly reach* every consumer; add a defect/dark-current map.
**Non-goals:** do not change the RAW serialization format (s1-f01); do not wire MFSR's GPU path
itself (that's s1-f06/CP12) — only guarantee the *values* are correct at the boundary; no new model.

**Plan:**
1. **Golden-lock** the current black-level/white-clamp output and the noise coefficients in the
   descriptor for a fixed metadata fixture.
2. **Audit the plumbing.** Trace `SENSOR_NOISE_PROFILE` from `HardwareAbstractionLayer.kt` →
   `CameraSensorNoiseModelPolicy.kt` → `RawVideoManager.kt` descriptor → the MFSR/denoise inputs.
   Add a boundary test that fails if the value arrives as 0 when a profile exists.
3. **Defect map (CP15 foundation).** New `camera/SensorDefectMapPolicy.kt`: build a hot-pixel /
   dark-current map (pure Kotlin), defaulting to identity when no map is available. Expose it for
   demosaic (s1-f07/f08) to consume.
4. **Device note.** DEVICE-GATED: capture a dark frame on the vivo, derive the defect map,
   validate hot-pixel rejection; confirm the measured noise curve matches the profile.

**Files:** `camera/CameraSensorNoiseModelPolicy.kt`, `pipeline/RawVideoManager.kt`,
`camera/HardwareAbstractionLayer.kt`; new `camera/SensorDefectMapPolicy.kt`.

**ABI:** if noise/defect data later moves into a GPU push-constant/UBO, extend
`GpuPushConstantLayout` + its layout test in that commit (deferred to the consuming prompt).

**Gating:** identity defect map + today's noise handling are the defaults; real profile/map only
engage when present (device-gated).

## 6. Acceptance & verification
- **Unit/golden (JVM):** black-level precedence + clamp exactness; `σ²=O+S·signal` fit tolerance;
  the boundary test proving non-zero `(S,O)` reach the consumer; defect-map identity-default test.
- **Device (vivo I2405):** dark-frame capture → defect map → no surviving hot speckle in a demosaic
  dark-frame render; measured flat-field noise σ matches the profile within tolerance.
- **Gate keys:** noise/calibration feeds the MFSR/denoise SNR claims that flow through
  `OmegaCapabilityGate` — values must be real, never synthesized.

## 7. Risks & no-downgrade guardrails
- **Stranded coefficients (the MFSR `noise=0` bug):** boundary test fails if a real profile arrives
  as 0 — impossible to ship the stranded state silently.
- **Hot-pixel seeding:** defect map rejects defects before interpolation; identity default means no
  change where no map exists.
- **Black-clip / overflow:** precedence + white-clamp tests bound the subtraction.

## 8. References
- Roadmap: CP12 (consumer), CP15 (defect/dark-current maps) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md`](../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md), [`../technology_explanations/markdown/11-pixel-reality.md`](../technology_explanations/markdown/11-pixel-reality.md)
- Reference: DNG `NoiseProfile`; Poisson-shot + Gaussian-read sensor noise model; per-ISO cinema sensor characterization.
- Source: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraSensorNoiseModelPolicy.kt`, `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt`, `app/src/main/java/com/neoproc/neoproccamx/camera/HardwareAbstractionLayer.kt`
- Related prompts: s1-f01, s1-f05, s1-f06, s1-f07, s1-f08
