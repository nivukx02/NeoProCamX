# ImageQualityMetrics Measurement Harness (MTF50 / SNR / dE2000 / banding / flicker / chroma-crawl) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f05 |
| **Maps to checkpoint(s)** | CP01 (harness, done), CP-Q2/CP-Q6 (live SNR + chroma-crawl); propose CP-Q7 (more live telemetry) |
| **Current working level** | 🟡 partial (only the live-frame SNR primitive runs in production) |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | M · M |
| **Depends on** | s8-f06 (real-metric producer consumes this), s8-f08 (gate consumes derived metrics) |

## 1. What this feature is
The objective image-quality measurement library: ISO-12233 slanted-edge MTF50 (ESF→LSF→Hamming→DFT),
flattest-tile SNR(dB), full CIEDE2000, ramp banding/posterization, temporal flicker σ, and
opponent-space temporal chroma-crawl. It is the *measurement* layer that lets every other quality
claim be proven from real pixels instead of asserted.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
This is the test-and-measurement bench that camera makers and labs use to *certify* image quality:
- **ISO-12233** slanted-edge MTF (resolution) on a standardized chart — MTF50 in cy/px is the
  industry resolution number.
- **CIEDE2000 (ΔE2000)** against a ColorChecker / IEC reference for colour accuracy (mastering-grade
  targets aim ΔE2000 mean ≲ 2).
- **Flat-field SNR(dB)** from a uniform grey patch for noise; **temporal flicker σ** and
  **chroma-crawl** for motion stability — the same metrics ARRI/Sony publish in sensor white-papers.
- The bench is **calibrated against analytic ground truth** (e.g. a synthetic Gaussian edge has a
  known MTF50) so the measurement itself is trusted.
Elite = these lab metrics run not only on the bench but, where cheap enough, **on live frames** as
production telemetry feeding the honest gates.

## 3. Current state in NeoProCamX (verified)
- Library: `app/src/main/java/com/neoproc/neoproccamx/quality/measure/ImageQualityMetrics.kt`
  (CP01, 11 tests green; MTF50 recovers the Gaussian analytic value within tolerance — roadmap CP01).
- **Live today:** the SNR primitive only. `frameLumaSnrDb`/`frameLumaSnrDbFromY` (CP-Q2) runs off the
  live Y plane in `apex/SceneAnalyzer.kt` → `SceneMetrics.measuredLumaSnrDb`, consumed by
  `apex/ApexProfile.kt`/ApexQualityMeasurement (verified callers: `apex/ApexProfile.kt`,
  `apex/SceneAnalyzer.kt`).
- `temporalChromaCrawl` added (CP-Q6) — opponent-space, luma-flicker-immune; currently test-side.
- **Test-only:** MTF50, ΔE2000, ramp banding, temporal flicker run only in tests/goldens and through
  the (caller-less) `EliteVisionMeasurementHarness` (s8-f06). Per FEATURE_CATALOG §8: "Only the
  live-frame SNR primitive runs in production."
- **Honesty note:** the measurement→gate loop closes only in tests; the real-pixel producer that
  would feed the live gate (s8-f06) has no production caller.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Analytic ground-truth locks (keep):** MTF50 recovers a synthetic Gaussian edge's analytic value
   within tol; SNR matches a known-noise field within tol; ΔE2000 matches reference pairs — all
   already asserted, regression-locked (CP-Q5). Keep these as the trust anchor.
2. **Expanded live telemetry (new):** at least one more cheap metric promoted to live frames behind a
   budget — candidate: live banding/posterization code-delta on the preview histogram, or live
   temporal flicker σ over the recent frame window — with a ms/frame budget and governor backoff.
3. **Budget discipline:** any live metric states its per-frame cost and defers under load/thermal; the
   live SNR path's cost is documented and unchanged.
4. **Shared core:** live and bench metrics share the same inline core (as CP-Q2 did for SNR) so a
   live number and a bench number for the same input agree within tol — host test asserts agreement.
5. **No-downgrade default:** the live SNR path and all bench tests are unchanged; new live telemetry
   is additive and off the critical path (computed on the analysis stride, not the render thread).

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the harness and promote one more metric to live telemetry, sharing the bench core.
**Non-goals:** do not change the analytic-locked math; do not move heavy metrics (MTF50/full ΔE2000)
onto the render thread; no dependency.

**Plan:**
1. Pick the cheapest high-value metric to promote (banding code-delta or windowed flicker σ);
   factor a shared inline core (live + bench agree), mirroring the CP-Q2 SNR pattern.
2. Wire it into the analysis stride (`SceneAnalyzer`) behind a budget + governor backoff; expose it on
   `SceneMetrics` (default NaN, like `measuredLumaSnrDb`).
3. Add live↔bench agreement test + the live-path measurement test.

**Files:** `quality/measure/ImageQualityMetrics.kt`, `apex/SceneAnalyzer.kt`, `apex/ApexProfile.kt`
(or the relevant `SceneMetrics` carrier). No ABI/push-constant change (CPU measurement).

**Tests:** `LiveMetricSharedCoreAgreementTest`, `SceneAnalyzerLiveMetricTest`, and the existing CP01/
CP-Q5 regression locks must stay green.

**Gating:** new live metric defaults to NaN/inert until enabled; never blocks the analysis stride.

## 6. Acceptance & verification
- **JVM:** CP01 + CP-Q5 regression locks green; new live↔bench agreement test green.
- **Device (vivo):** the promoted live metric reports plausible values on a known chart; analysis
  stride cost stays within budget (profiler); no preview frame-time spike.
- **Gate keys:** measured metrics feed the gate only via the s8-f06 producer / Tier-Omega keys — this
  library never asserts a PASS itself.

## 7. Risks & no-downgrade guardrails
- **Live-metric cost spike:** runs on the analysis stride with governor backoff; documented budget.
- **Live/bench divergence:** shared inline core + agreement test makes divergence a test failure.
- **Over-claiming:** metrics are inputs to honest gates, never a gate verdict on their own.

## 8. References
- Reference: ISO-12233 slanted-edge MTF50; CIEDE2000; flat-field SNR(dB); temporal flicker σ / chroma-crawl (sensor white-paper metrics).
- Roadmap: CP01, CP-Q2, CP-Q5, CP-Q6 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md).
- Source: `quality/measure/ImageQualityMetrics.kt`, `apex/SceneAnalyzer.kt`, `apex/ApexProfile.kt`.
- Related prompts: s8-f06, s8-f08.
