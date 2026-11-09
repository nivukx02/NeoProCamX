# EliteVisionQualityContract + EliteVisionMeasurementHarness (A1-A15/S1-S10) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f08 |
| **Maps to checkpoint(s)** | CP-Q1 (real-pixel measurement → done as foundation), wire into the gate as a production caller |
| **Current working level** | ⚪ test-only-scaffold |
| **Feature rating (potential)** | 3/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s6-f09 (gate), s6-f06 (parity), s8 ImageQualityMetrics (CP01), s6-f04/f05 (master/export frames) |

## 1. What this feature is
`EliteVisionQualityContract` is a 25-criterion (A1-A15 appearance / S1-S10 signal) pass/fail
evaluator over an `EliteVisionQualityMeasurements` struct; `EliteVisionMeasurementHarness` is the
producer that fills the *pixel-measurable* fields of that struct from real decoded frames (MTF50,
luma SNR, IRE noise σ, ColorChecker ΔE2000, gray a*/b*, temporal flicker) via `ImageQualityMetrics`,
and projects them onto the `tierOmega*` evidence keys the recording gate consumes. The criteria,
thresholds, math, and the gate keys all exist and are well-tested. The single missing piece: **there
is no production caller** — the harness runs only under its own JVM tests on synthetic goldens, so
real measured pixels never actually reach the live gate.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional QC is objective and automated. Broadcast/cinema deliverables pass through tools like
Tektronix/Telestream QC, IMF/Photon validation, and IMAX DMR QC that *measure* the actual frames:
ISO-12233 slanted-edge MTF50 for resolution, flat-field SNR(dB), CIEDE2000 against a reference
ColorChecker, banding/posterization code-delta, temporal flicker σ, and chroma-crawl — then
pass/fail against a spec, blocking delivery on failure. The measurement is run on the *real decoded
master*, not a model. The elite bar here is exactly that loop closed: the 25 criteria evaluated on
real decoded record/export frames, the measured values feeding the blocking gate, and an honest
`NOT_PROVEN` when a frame producer isn't available — never a fixture standing in for a measurement.

## 3. Current state in NeoProCamX (verified)
- `EliteVisionQualityContract.kt` (162 lines) — `EliteVisionQualityMeasurements` struct with the
  full criterion field set: appearance (hue/skin/sky/look-round-trip/gamut/lightness ΔE) `:7-13`,
  signal (noise σ IRE base/3200, MTF50 retention, acutance, halo, banding) `:14-19`, transport
  truth booleans (tmb signature, fallback honesty, N-Log round-trip, deterministic hash) `:21-24`,
  plus the CP-Q1 measured fields (SNR, flicker, MTF50 cy/px, ColorChecker ΔE2000, gray a*/b*,
  dead/hot pixels, parity) `:25-40`.
- `EliteVisionMeasurementHarness.kt` (139 lines) — `internal object`; `measure(GoldenFrames,
  baseline)` runs `ImageQualityMetrics.slantedEdgeMtf50` / `flatFieldSnrDb` / `colorCheckerDeltaE2000`
  / `temporalFlickerStd` over real pixel arrays and overwrites the measurable fields (`:50-91`);
  `toTierOmegaMetadata(...)` projects them onto the exact `tierOmega*` keys the gate consumes
  (`:102-121`). Banding intentionally NOT mapped (code-delta vs flat-run mismatch, doc `:19-22`).
- **The honest gap — no production caller:** grep for `EliteVisionMeasurementHarness` across
  `app/src` returns exactly two files — the harness itself and
  `app/src/test/.../EliteVisionMeasurementHarnessTest.kt`. **Zero production callers.** The gate
  consumes `tierOmega*` keys (CP-Q3 wired the consumer + producer projection), but nothing in the
  live recording flow ever *calls* `measure(...)` on real decoded frames — today's inputs are
  synthetic goldens in tests. So the real-pixel measurement loop is built but **not closed in
  production** (catalog §6/§8 confirm "no production caller").

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Production caller exists:** a live recording-flow component invokes
   `EliteVisionMeasurementHarness.measure(...)` on **real decoded** record/export frames (from
   s6-f04/f05) and feeds `toTierOmegaMetadata(...)` into `RecordingQualityEvidencePolicy` (s6-f09).
   Provable by a grep showing a non-test caller + a test exercising that caller end-to-end.
2. **Real frames, not fixtures:** the `GoldenFrames` fed in production come from decoded camera/
   master/export buffers, not hardcoded arrays. The JVM tests may still use deterministic synthetic
   goldens (CP-Q5), but the production path decodes real frames.
3. **All 25 criteria evaluated:** A1-A15 / S1-S10 each produce a pass/fail with measured + threshold
   surfaced; the pixel-measurable subset is real-measured, the rest carried from their owning
   subsystem with honest provenance (per the class doc).
4. **Honest NOT_PROVEN:** if a real-frame producer is unavailable for a criterion, the gate records
   `NOT_PROVEN` for it — never a synthesized PASS (OmegaCapabilityGate contract).
5. **Determinism lock:** measured values are deterministic for a fixed input (CP-Q5 regression lock)
   so the gate is reproducible in CI.
6. **No-downgrade default:** wiring the harness in is **additive evidence** — it can only block
   (FAIL/NOT_PROVEN) a claim, never alter pixels. Until device frame-decode is validated, the
   production caller runs on the deterministic synthetic path so no live behavior changes; the
   real-decoded-frame feed activates behind the device-gated flag.
7. **Budget:** measurement runs at start/stop or on a sampled frame, off the per-frame hot path; no
   preview/record real-time regression.

## 5. Implementation prompt (hand this to the agent)
**Scope:** close the loop — add the production caller that runs `measure(...)` on real decoded frames
and feeds the measured `tierOmega*` evidence into the live recording gate. **Non-goals:** do not
change the criterion math/thresholds; do not alter pixels; do not add a library; do not synthesize
any PASS.

**Plan:**
1. **Golden-lock first.** Pin today's `measure(...)` outputs on the committed deterministic synthetic
   goldens (CP-Q5 regression lock already exists — extend it to cover the new caller path).
2. **Frame decode source.** Add a decoded-frame provider that yields `GoldenFrames` from the live
   record/export output (sampled key frames + a flat field + a ColorChecker capture where available).
   Behind a device-gated flag; default = the deterministic synthetic path (no live change).
3. **Production caller.** In the recording-completion / export-completion flow, call
   `EliteVisionMeasurementHarness.measure(frames, baseline)` then `toTierOmegaMetadata(...)`, and
   pass the map into `RecordingQualityEvidencePolicy` (s6-f09).
4. **Honest provenance.** Mark criteria whose producer is unavailable `NOT_PROVEN`; never fill a
   fixture in place of a measurement in production.
5. **Tests.** A non-test caller exists; an end-to-end test feeds decoded-frame buffers → gate sees
   measured MTF50/SNR/ΔE2000; a degraded frame drives the relevant criterion to FAIL.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVisionMeasurementHarness.kt`
(make `measure` reachable from production), a new decoded-frame provider, the recording/export
completion site (`RecordingManager.kt` / `Npv10MovConverter.kt`), `RecordingQualityEvidencePolicy.kt`.

**ABI:** none (CPU measurement + string-map evidence keys).

**Gating:** the real-decoded-frame feed is device-gated (default = deterministic synthetic path), so
no live pixels or default behavior change; the only new effect is honest evidence reaching the gate.

## 6. Acceptance & verification
- **Unit/golden (JVM):** CP-Q5 regression lock extended; end-to-end caller test (decoded buffers →
  `toTierOmegaMetadata` → gate keys present/formatted); degraded-frame ⇒ criterion FAIL; unavailable
  producer ⇒ NOT_PROVEN.
- **Gate keys:** `tierOmegaMtf50CyPerPx`, `tierOmegaLumaSnr*`, `tierOmegaDeltaE2000*`,
  `tierOmegaTemporalFlickerPercent`, etc. are populated from real measurement in
  `RecordingQualityEvidencePolicy` (s6-f09); proven via `OmegaCapabilityGate`.
- **Device (vivo):** record a clip → gate certificate carries measured MTF50/SNR/ΔE2000 from real
  decoded frames; deliberately degrade (heavy compression) → gate FAILs the relevant criterion;
  flag OFF leaves behavior unchanged.

## 7. Risks & no-downgrade guardrails
- **Fixture-as-measurement (the core sin):** production caller decodes real frames; unavailable ⇒
  NOT_PROVEN, never a synthesized fixture PASS.
- **Pixel change:** harness is measurement-only by construction.
- **Non-determinism in CI:** CP-Q5 regression lock pins synthetic-golden outputs.
- **Real-time regression:** runs at start/stop/sampled frame, off the hot path; device-gated feed
  defaults to synthetic.

## 8. References
- Roadmap: CP-Q1/CP-Q3/CP-Q5 (real-pixel quality-gate wiring) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md)
- Reference: Tektronix/Telestream QC, IMF/Photon validation, IMAX DMR QC; ISO-12233 MTF50, CIEDE2000, flat-field SNR, temporal flicker σ.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVisionMeasurementHarness.kt:50, 102` (only callers: itself + `EliteVisionMeasurementHarnessTest.kt`); `EliteVisionQualityContract.kt:6, 25`
- Related prompts: s6-f06, s6-f09, and CP01 `ImageQualityMetrics`
