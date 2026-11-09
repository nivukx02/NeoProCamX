# ColorValidationHarness + NeoGamutApexPipeline.processForOutput — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f12 |
| **Maps to checkpoint(s)** | CP-Q4 (validate the REAL color pipeline in the calibration harness — neutral-spread half DONE) |
| **Current working level** | ⚪ test-only-scaffold (full APEX chain + ΔE2000/Duv/skin-hue/frame-to-frame validation; referenced only by its own test) |
| **Feature rating (potential)** | 2/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · M |
| **Depends on** | s2-f02 (AcesColorEngine chain it exercises), s2-f10 (illuminant/CAM16 it validates), s8-f0x (ImageQualityMetrics ΔE2000/flicker producers), CP-Q1/Q3 (EliteVisionMeasurementHarness real-frame pattern) |

## 1. What this feature is
Two pieces. `NeoGamutApexPipeline.processForOutput` (`NeoGamutApexPipeline.kt:150`) is the **full
per-pixel APEX color chain**: NeoWideGamut → ACES AP1 (`acesColorEngine.neoWideGamutToAcesAp1`) →
AP1 gamut compression (`compressAp1Gamut`) → chroma-preserving tone map (`toneMapChromaPreserving`) →
back to NeoWideGamut → target gamut (Rec.2020/etc.) with compression (`:159-163`); the V1 path is a
direct `neoToTargetRgb` (`:156-157`). `ColorValidationHarness` (`ColorValidationHarness.kt:38`)
produces a `ColorValidationReport` with **mean/max ΔE2000, max neutral-Duv, skin-hue drift,
frame-to-frame Duv, a V1-bit-match-vs-legacy check, and the CP-Q4 sensor→ACEScg neutral-spread**
(`ColorValidationHarness.kt:95-105`, gated `≤ 0.02`). It is the *self-check* for the whole color
pipeline — the place "is the color science correct?" is answered numerically.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is **calibrated, instrumented color QC** — the discipline of a mastering house:
- A pro pipeline is validated against a **ColorChecker / known-spectra chart** with **CIEDE2000**
  acceptance thresholds per patch, **neutral-axis stability (Duv ≈ 0)** on the gray ramp, and
  **memory-color (skin/sky/foliage) hue bounds**. ACES conformance is proven by driving *real*
  transforms with reference inputs and asserting bounded error — not by eyeballing.
- **Temporal** QC matters too: frame-to-frame chromaticity stability (the flicker/chroma-crawl axis)
  so a master doesn't shimmer. IMAX DMR and DI conform run per-frame metric checks.
- The non-negotiable: the harness validates the **production transform**, decoded from **real pixels**,
  not a synthetic stand-in. (This is exactly the CP-Q1/CP-Q2/CP-Q3 lesson — measure the real path.)
Elite bar, measurable: (a) a neutral ramp through the real `processForOutput` keeps neutral-Duv ≤ 0.001
and ΔE2000 ≤ 1.0; (b) a ColorChecker through the real chain has mean ΔE2000 below a target and max
below a ceiling; (c) skin/sky/foliage hue drift within the configured bounds; (d) frame-to-frame Duv
on a static scene below a temporal threshold; (e) the harness consumes **real decoded golden frames**,
not only synthetic palettes.

## 3. Current state in NeoProCamX (verified)
- `processForOutput` is the real full chain (`NeoGamutApexPipeline.kt:150-164`); V1 is the direct path
  (`:156-157`). **Correct, complete code.**
- `ColorValidationHarness` builds a `ColorValidationReport` with all the metrics named above
  (`ColorValidationHarness.kt:95-105`), including the CP-Q4 `maxSensorToAcesNeutralSpread`
  (`:104, :114`) which drives the **real CP11 sensor→XYZ(D50)→ACEScg** path with two neutral-preserving
  DNG forward matrices across the dual-illuminant CCT range (`:108-118`).
- **No production caller — verified by Grep over `app/src/main`:** the only references are the
  definitions themselves plus the harness calling `processForOutput` internally
  (`ColorValidationHarness.kt:89`); the other matches are doc comments in `SnceColorPrimitives.kt:240`
  and `ImageQualityMetrics.kt:342`. Nothing in the running pixel path invokes either — they are
  exercised **only by their own test** (catalog §2 row 12). This is a measurement/QC scaffold, not a
  live transform.
- **CP-Q4 neutral-spread half is DONE** (roadmap, commit `491c522`); the remaining gap is that the
  harness validates against **synthetic palettes**, not **real decoded golden frames** — the same gap
  CP-Q1/CP-Q3 closed for `EliteVisionMeasurementHarness`.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Real-frame intake (CP-Q4 pattern):** the harness accepts decoded golden-frame buffers (the
   `EliteVisionMeasurementHarness.measure(GoldenFrames, baseline)` pattern from CP-Q1) and runs the
   real `processForOutput` over them, not only synthetic triples; plain-array inputs still run on
   synthetic goldens (JVM today, decoded frames later).
2. **Neutral preservation:** a neutral ramp through `processForOutput` yields neutral-Duv ≤ 0.001 and
   ΔE2000 ≤ 1.0; `maxSensorToAcesNeutralSpread` stays ≤ 0.02 (the existing CP-Q4 gate).
3. **ColorChecker bound:** a 24-patch chart through the real chain has mean ΔE2000 ≤ target and max
   ≤ ceiling; report fields populated from the real transform.
4. **Memory-color hue bounds:** skin/sky/foliage hue drift within `ApexColorConfig` bounds (the
   harness already computes skin-hue drift — extend to sky/foliage).
5. **Temporal stability:** frame-to-frame Duv (already in the report) and an opponent-space chroma-crawl
   check (reuse `ImageQualityMetrics.temporalChromaCrawl`, CP-Q6) below thresholds on a static sequence.
6. **No-downgrade / no-vendor:** this is a *measurement* path — it must not alter live pixels; no new
   model; real binary golden assets stay owner-captured (media gitignored, per CP-Q5), with committed
   deterministic synthetic goldens as code.
7. **CI-mandatory:** the harness runs in the JVM suite with no `@Ignore`/`Assume` (the CP-Q5
   discipline); a regression-lock pins the metrics to analytic constants.

## 5. Implementation prompt (hand this to the agent)
**Scope:** close the validation loop — make `ColorValidationHarness` run the **real**
`processForOutput` (and the CP11 sensor→ACEScg path it already drives) over **decoded golden frames**,
populate the report from real pixels, and make it CI-mandatory. **Non-goals:** do NOT wire either into
the live pixel path (it stays a QC scaffold — that's correct); do not change the color math; no new
model; do not commit binary golden media (owner-captured).

**Plan (CP-Q4 completion, mirroring CP-Q1/Q3):**
1. **GOLDEN-LOCK FIRST.** Pin today's `ColorValidationReport` fields (mean/max ΔE2000, neutral-Duv,
   skin-hue drift, frame-to-frame Duv, V1-bit-match, neutral-spread) to analytic constants — the
   regression lock (CP-Q5 pattern).
2. **Real-frame overload:** add a `validate(GoldenFrames, baseline)`-style entry that decodes frame
   buffers and runs `processForOutput` over them; plain-array inputs keep running synthetic goldens
   (JVM today). Reuse the `EliteVisionMeasurementHarness` decoded-frame plumbing.
3. **Populate from real pixels:** ColorChecker ΔE2000 (mean/max), neutral ramp Duv/ΔE2000, memory-color
   hue drift (extend to sky/foliage), frame-to-frame Duv + chroma-crawl from `ImageQualityMetrics`.
4. **Keep CP-Q4 neutral-spread** green (`maxSensorToAcesNeutralSpread ≤ 0.02`).
5. **CI-mandatory + no-binary-asset guard:** ensure the suite runs unconditionally; assert no binary
   golden media is committed (deterministic synthetic goldens are committed as code).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/color/ColorValidationHarness.kt`,
`NeoGamutApexPipeline.kt` (no math change), and the `quality/measure/` harness/metrics it reuses; tests
under `app/src/test/.../color/` and `.../quality/`.

**Gating:** measurement-only — zero live-pixel change by construction; no flag needed because it never
touches the render path. NO-VENDOR: classical metrics + already-shipped decode only.

## 6. Acceptance & verification
- **JVM:** regression-lock (metrics pinned), real-frame intake, ColorChecker ΔE2000, neutral
  ramp Duv/ΔE2000, memory-color hue, frame-to-frame Duv + chroma-crawl, and CP-Q4 neutral-spread
  (≤ 0.02) tests green; suite is CI-mandatory (no `@Ignore`/`Assume`).
- **No-binary-asset guard:** meta-test asserting no committed golden media; synthetic goldens as code.
- **No device step required** (measurement scaffold, no live-pixel path); but the real decoded-frame
  feed lets the owner later run it over captured vivo goldens.

## 7. Risks & no-downgrade guardrails
- **Accidentally altering live pixels:** the harness has no render-path caller (verified) and gains
  none — measurement-only by construction.
- **Synthesized PASS (honest-gate violation):** metrics computed from real decoded pixels; regression
  lock pins them; no heuristic stand-in (the CP-Q2 lesson).
- **Committing vendor/binary assets:** no-binary-asset guard; deterministic synthetic goldens only.
- **Metric drift hidden:** CI-mandatory + regression-lock trips on any weakening of the real path.

## 8. References
- Roadmap CP-Q4 (neutral-spread DONE `491c522`), CP-Q1/CP-Q3 (real-frame harness pattern), CP-Q5
  (CI-mandatory + synthetic-goldens), CP-Q6 (chroma-crawl): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Catalog §2 row 12 + §8 (ImageQualityMetrics): [`../FEATURE_CATALOG.md`](../FEATURE_CATALOG.md)
- Standard canon (ColorChecker / CIEDE2000 QC, neutral-Duv, memory-color bounds, temporal stability): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutApexPipeline.kt:150-164`; `ColorValidationHarness.kt:38, 89, 95-105, 108-118`
- Related prompts: s2-f02, s2-f10, s2-f11
