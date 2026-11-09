# Test-Coverage Net + Build/Test Infra — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f10 |
| **Maps to checkpoint(s)** | new — propose CP-CI1 (device-instrumented golden capture + CI for real-pixel goldens) |
| **Current working level** | 🟢 live-default-on (the test net itself) |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · L |
| **Depends on** | s8-f05 (metrics), s8-f06 (real-frame producer), all golden-locked features |

## 1. What this feature is
The verification backbone: a pure-JVM (no Robolectric) test net — 562 test files vs 625 main; 122 UI
+ 107 quality + 96 recording + 88 camera + 72 rendering; 95 contract/golden/characterization tests
that freeze per-mode RGB via reflection; plus source-hygiene meta-tests. It is what makes the
no-downgrade / golden-first contract enforceable.

## 2. How high-end engineering does this  ← THE REFERENCE BAR
The reference is mature CI/test practice for an imaging pipeline:
- **Golden / characterization testing** locks output bit-for-bit so any regression is a red build
  (the Phase-0 discipline this project already follows).
- **Real-pixel goldens** — the highest-value goldens are captured from the actual device sensor/encoder
  on a known chart (ISO-12233, ColorChecker), committed (or hash-pinned), and checked in CI so the
  pipeline is validated against *real* frames, not only synthetic ones.
- **Quality gating in CI** — MTF50/SNR/ΔE2000 thresholds enforced on every change; a metric regression
  fails the build (continuous QC, the s8-f05/s8-f07 loop).
- **Hermetic, fast, deterministic** test execution; flaky tests are quarantined, not tolerated.
Elite = the existing strong JVM net is extended with **device-instrumented golden capture** and CI
that runs the real-pixel goldens, closing the gap between synthetic-golden coverage and real silicon.

## 3. Current state in NeoProCamX (verified)
- Per FEATURE_CATALOG §8 and `_PROMPT_STANDARD.md`: pure-JVM suite, baseline CP00 = `3f66245`
  (537 suites / 3115 tests / 0 failures, growing); canonical build/test commands use the bundled JBR 21.
- 95 contract/golden/characterization tests freeze per-mode RGB via reflection; source-hygiene
  meta-tests guard structure (e.g. the s8-f04 god-object size meta-test, s8-f09 no-synthesized-PASS
  meta-test proposed here build on this).
- CP-Q5 residual (verified, roadmap line 119): **real binary golden-frame assets stay owner-captured
  (media gitignored)**; only deterministic synthetic goldens are committed as code. This is the gap —
  the real-pixel goldens are not in CI.
- The metrics + producer that would feed real-pixel goldens exist (s8-f05 `ImageQualityMetrics`,
  s8-f06 `EliteVisionMeasurementHarness`) but the decoded-frame production feed is dormant (s8-f06).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Device-instrumented golden capture (new):** a documented, reproducible on-device procedure
   captures real frames from known charts (ISO-12233, ColorChecker, flat grey) and produces golden
   assets + their content hashes. The procedure is scripted and deterministic (same chart → same hash
   band).
2. **Real-pixel goldens in CI:** committed golden *hashes* (assets hash-pinned, media may stay
   gitignored) are validated in CI via the s8-f05 metrics — a metric drift outside tolerance fails the
   build. Golden coverage % for live pixel paths is tracked and trends up.
3. **No flaky tests:** the suite stays deterministic (no Robolectric, no time/randomness dependence);
   a meta-test or CI rule quarantines any non-deterministic test.
4. **Coverage non-regression:** a meta-test asserts test-file/contract-golden counts are
   non-decreasing relative to a pinned baseline (562/95) so coverage cannot silently shrink.
5. **No-downgrade default:** the existing JVM suite and its run command are unchanged; device-golden
   capture + the CI hash check are additive and do not slow the core JVM suite.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add the device-instrumented golden capture procedure + CI validation of real-pixel golden
hashes via the metrics harness, and lock coverage non-regression. **Non-goals:** do not introduce
Robolectric or instrumentation into the core JVM suite; do not commit large binary media if the repo
policy gitignores it (commit hashes/manifests instead); no dependency.

**Plan:**
1. Script the on-device capture (chart list, exposure, the exact `gradlew`/adb steps) producing golden
   frame buffers + a hash/metric manifest.
2. Add a CI step that, given the captured goldens (or their committed hashes/manifest), runs s8-f05
   metrics and asserts MTF50/SNR/ΔE2000 within tolerance bands — fail the build on drift.
3. Add the coverage non-regression meta-test (counts ≥ pinned baseline).
4. Document the owner-run capture procedure (device-gated) in the roadmap CP entry.

**Files:** a new `golden/` capture manifest + hashes, a CI metric-check test, a coverage-count
meta-test. No app-code ABI change.

**Tests:** `RealPixelGoldenMetricCheckTest` (golden/manifest → metrics within tolerance),
`CoverageNonRegressionMetaTest` (file/golden counts ≥ baseline), determinism meta-rule.

**Gating:** device-golden capture is device-gated (owner runs it); CI uses committed hashes/manifest
so the core JVM suite stays fast and hermetic.

## 6. Acceptance & verification
- **JVM/CI:** existing suite green (CP00 baseline protected); new metric-check + coverage meta-tests
  green; build fails on a deliberately-drifted golden hash.
- **Device (vivo I2405 / Adreno):** owner runs the documented capture on the known charts; the produced
  golden hashes/metrics match the committed manifest within tolerance.
- **Gate keys:** real-pixel golden metrics feed the same s8-f05/s8-f07 honest-gate loop; no PASS is
  synthesized.

## 7. Risks & no-downgrade guardrails
- **Slow/flaky CI:** core JVM suite unchanged; device capture is owner-run, CI uses hashes only.
- **Coverage erosion:** non-regression meta-test fails the build if counts drop.
- **Golden rot:** hash-pinned manifest + metric tolerance bands catch silent golden drift.

## 8. References
- Standard: build/test commands + CP00 baseline `3f66245` in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md).
- Roadmap: CP-Q5 residual (owner-captured binary goldens) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md).
- Reference: golden/characterization testing, real-pixel goldens from ISO-12233/ColorChecker captures, CI quality-gating (MTF50/SNR/ΔE2000 thresholds), hermetic deterministic test execution.
- Source: the `app/src/test/...` net (562 files / 95 contract-golden); s8-f05 `quality/measure/ImageQualityMetrics.kt`; s8-f06 `recording/EliteVisionMeasurementHarness.kt`.
- Related prompts: s8-f05, s8-f06, s8-f04, s8-f09.
