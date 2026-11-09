# Sensor → XYZ → ACEScg Color Matrices — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f09 |
| **Maps to checkpoint(s)** | CP11 (sensor→XYZ→ACEScg matrices; foundation DONE) — this prompt is *consume it in the live demosaic* |
| **Current working level** | 🟠 dormant (correct forward path built + tested; only a validation-harness caller) |
| **Feature rating (potential)** | 3/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 (math correct; latent inversion in the old path, documented) |
| **Risk · Size** | M · M |
| **Depends on** | s1-f07/s1-f08 (the demosaic that consumes the matrices), HAL forward-matrix read |

## 1. What this feature is
The colorimetric bridge that turns sensor-native RGB into a standard scene-linear color space. It
reads the DNG dual-illuminant forward matrices and calibration, interpolates them by correlated
color temperature (mired), applies the CAT16 chromatic-adaptation chain (D50→D55→NeoWideGamut→AP1),
and produces the sensor→XYZ→ACEScg (AP1) transform that the GPU demosaic should use. Without this,
a demosaiced RAW frame has no defined color — neutrals drift and saturated colors land wherever the
sensor's raw primaries happen to be.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Color correctness in cinema is a *characterized, illuminant-aware* transform:
- **ACES IDT (Input Device Transform):** every ARRI/RED/Sony body ships an IDT that maps sensor RGB
  → ACES AP0/AP1 scene-linear. It is derived from spectral sensitivity measurements, not eyeballed.
- **Dual-illuminant + CAT:** DNG carries `ForwardMatrix1/2` and `CalibrationIlluminant1/2` (e.g.
  StdA + D65); the renderer interpolates by the scene CCT and chromatically adapts (Bradford/CAT16)
  to the working white point — so a neutral reads neutral under any light.
- **Scene-linear working space:** AP1/ACEScg is wide enough that no saturated color clips during
  grading; the transform must be invertible and neutral-preserving.

Engineering targets translated: (1) **neutral preservation** — a white-balanced neutral maps to an
achromatic ACEScg value across the full dual-illuminant CCT range (ΔE2000 / a*b* spread bounded —
CP-Q4 gates this `<= 0.02`); (2) **mired-correct interpolation** between the two calibration
illuminants; (3) **direction correctness** — sensor→XYZ (forward) must not be confused with the
DNG `ColorMatrix` which is XYZ→sensor (its inverse); (4) the transform is the **same one the demosaic
uses on-device** (not a parallel harness-only copy).

## 3. Current state in NeoProCamX (verified)
- The CP11 forward path is built: `color/NeoGamutApexPipeline.kt` —
  `buildSensorToXyzD50FromForward` (mired-interpolated `SENSOR_FORWARD_MATRIX`) + `xyzD50ToAcesAp1Matrix`,
  with 4 neutral-preservation tests (roadmap CP11). HAL reads forward1/2 + calibration + illuminant
  CCTs (`HardwareAbstractionLayer.kt`).
- **The latent inversion (documented, dormant):** the older `buildSensorToAcesAp1Matrix`
  (`NeoGamutApexPipeline.kt:166`) reads `SENSOR_COLOR_TRANSFORM1/2` (lines 74/77) which is the DNG
  `ColorMatrix` = **XYZ→reference-sensor RGB, i.e. the INVERSE** of what sensor→XYZ needs — verified
  by the in-file comment at line 209 ("SENSOR_COLOR_TRANSFORM (DNG ColorMatrix) : XYZ → reference-
  sensor RGB : the INVERSE of …"). This path is **dormant** (no live caller) so it's not a live
  regression, but it must not be the one the demosaic wires to.
- **Caller status:** both matrix paths plus `processForOutput` (line 150) are referenced only by
  `color/ColorValidationHarness.kt` (validation/CP-Q4) — i.e. **no production/GPU caller** yet
  (FEATURE_CATALOG.md §1: "both have only a validation-harness caller").
- Net: the *correct* forward transform exists and is tested; the elite work is making the **GPU
  demosaic (s1-f07/f08) consume the forward path** and never the inverted `ColorMatrix` path.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Demosaic consumes the forward path:** the std140 UBO uploaded to the demosaic carries the
   `buildSensorToXyzD50FromForward` ∘ `xyzD50ToAcesAp1Matrix` product (not `SENSOR_COLOR_TRANSFORM`);
   a test asserts the uploaded matrix equals the forward-path product for a given metadata fixture.
2. **Neutral preservation across CCT:** white-balanced neutral → achromatic ACEScg; a*b* spread
   `<= 0.02` across the dual-illuminant CCT range (CP-Q4 `validateSensorToAcesNeutralSpread`,
   already gated).
3. **Mired interpolation correctness:** at intermediate CCTs the interpolated matrix matches the
   analytic mired-linear blend within tolerance.
4. **Inversion guard:** a test pins that the *inverse* `ColorMatrix` path is NOT what feeds the live
   demosaic (a lock-out so the latent inversion can't regress into the pixel path).
5. **No-downgrade default:** until the demosaic is device-validated (s1-f07/f08 are gated off), this
   transform changes no live pixel; it ships as the matrix the gated demosaic will use.
6. **Budget:** matrix build is per-mode/per-WB (not per-pixel); negligible cost, off the cold-start
   path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** wire the *correct forward* sensor→ACEScg transform into the GPU demosaic's std140 UBO and
lock it with tests; quarantine the inverted `ColorMatrix` path.
**Non-goals:** do not change the proven CP11 math; do not enable the demosaic itself (s1-f07/f08
gate); do not resurrect the inverted path; no new color library.

**Plan:**
1. **Golden-lock** the CP11 forward matrices for fixed metadata fixtures (extend the existing 4
   tests) so the math is provably unchanged.
2. **UBO mapping.** Add a pure mapper that produces the demosaic UBO matrix from the forward path;
   test it equals `buildSensorToXyzD50FromForward ∘ xyzD50ToAcesAp1Matrix`.
3. **Inversion lock-out.** Add a test asserting the demosaic UBO is NOT derived from
   `SENSOR_COLOR_TRANSFORM` (guards the documented inversion at `NeoGamutApexPipeline.kt:209`).
4. **Hand-off to s1-f07/f08.** Expose the matrix in the exact std140 form the demosaic UBO expects
   (column-major, alignment-correct).
5. **Device note.** DEVICE-GATED: validated jointly with the demosaic dispatch (neutral patch
   achromatic on the vivo).

**Files:** `color/NeoGamutApexPipeline.kt` (new UBO mapper), `color/ColorValidationHarness.kt`
(extend), the demosaic UBO struct shared with `vulkan_bridge.cpp` (s1-f07/f08).

**ABI:** the matrix enters the demosaic std140 UBO — extend `GpuPushConstantLayout` + layout test in
the consuming commit (s1-f07/f08); column-major alignment is not optional.

**Gating:** the transform is dormant until the demosaic is device-validated; no live pixel changes.

## 6. Acceptance & verification
- **Unit/golden (JVM):** CP11 forward-matrix goldens; UBO-mapper == forward-path product; CP-Q4
  neutral spread `<= 0.02`; mired-interpolation tolerance; inversion lock-out test.
- **Device (vivo I2405):** with the gated demosaic on, a neutral patch reads achromatic in ACEScg;
  a known colour reference lands within ΔE2000 tolerance.
- **Gate keys:** color claim (ACEScg transport) proves through `OmegaCapabilityGate`; CP-Q4 keeps
  the neutral-spread gate green.

## 7. Risks & no-downgrade guardrails
- **Inverted transform reaching pixels:** explicit inversion lock-out test → impossible to wire the
  `ColorMatrix` (XYZ→sensor) path into the demosaic.
- **Neutral drift:** CP-Q4 `<= 0.02` spread gate across CCT.
- **Live regression:** dormant until demosaic device-validated; CP11 math golden-locked.
- **ABI/alignment:** column-major std140 layout test in the consuming commit.

## 8. References
- Roadmap: CP11 (done), CP-Q4 (neutral-spread gate), consumed by CP10 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/05-neo-wide-gamut.md`](../technology_explanations/markdown/05-neo-wide-gamut.md), [`../technology_explanations/markdown/06-neogamut-cat16-adaptation.md`](../technology_explanations/markdown/06-neogamut-cat16-adaptation.md)
- Reference: ACES IDT; DNG `ForwardMatrix1/2` + `CalibrationIlluminant1/2`; CAT16/Bradford adaptation; AP1/ACEScg.
- Source: `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutApexPipeline.kt:74,77,150,166,209`, `app/src/main/java/com/neoproc/neoproccamx/color/ColorValidationHarness.kt`, `app/src/main/java/com/neoproc/neoproccamx/camera/HardwareAbstractionLayer.kt`
- Related prompts: s1-f07, s1-f08, s1-f03
