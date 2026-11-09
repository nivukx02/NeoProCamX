# EliteVisionMeasurementHarness → Tier-Omega Real-Metric Producer — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.
> **Honesty theme:** this is the missing production half of the measurement→gate loop. Close it with
> real decoded frames, or honestly retire — never let the gate consume synthesized metrics.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f06 |
| **Maps to checkpoint(s)** | CP-Q1 (measure from frames, done synthetic), CP-Q3 (project onto gate keys, done); propose CP-Q8 (real decoded-frame production caller) |
| **Current working level** | ⚪ test-only-scaffold (no production caller) |
| **Feature rating (potential)** | 4/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · L |
| **Depends on** | s8-f05 (ImageQualityMetrics), s8-f07 (RecordingQualityEvidencePolicy consumes Tier-Omega keys), s8-f08 (Omega capability truth) |

## 1. What this feature is
The bridge that turns the CP01 metric library into gate-consumable evidence: `measure(GoldenFrames,
baseline)` runs `ImageQualityMetrics` over decoded frame buffers and overwrites the pixel-measurable
fields (MTF50 + retention, luma SNR base/highISO, IRE noise σ, ColorChecker ΔE2000 mean/max, gray
a*/b*, temporal flicker), then `toTierOmegaMetadata()` projects them onto the exact `tierOmega*` key
set the recording gate consumes. It is the **producer** side of "measurement → quality gate".

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
On a real mastering pipeline, the QC gate is fed by **measured numbers from the actual delivered
frames**, not by a model or a hand-wave:
- DMR / mastering QC decodes the delivered master and measures resolution (MTF), noise (SNR), colour
  (ΔE2000), and temporal stability **on those exact pixels**, then compares against the spec.
- A certification that PASSes without measuring the delivered pixels is fraud — the whole point of
  the gate is that the evidence traces to real transport/bit-depth/pixels.
Elite here = the loop **closes in production**: a decoded frame from the recorded/exported master is
measured, projected onto the Tier-Omega keys, and the gate's verdict reflects reality. CP-Q3 already
wired the consumer side; what's missing is a production caller feeding it real decoded frames.

## 3. Current state in NeoProCamX (verified)
- File: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVisionMeasurementHarness.kt`.
- **Verified: no production caller.** `grep -rln EliteVisionMeasurementHarness app/src/main/` returns
  only the file itself — every other reference is in tests.
- CP-Q1 (commit `8613a27`): `measure()` runs CP01 metrics over decoded frame buffers and overwrites
  pixel-measurable fields; plain-array inputs run on **synthetic** goldens today (decoded frames
  "later"). Tests: good goldens pass `EliteVisionQualityContract`, degraded fail S4/S1/S6.
- CP-Q3 (commit `a07e5d7`): `toTierOmegaMetadata()` projects the measured struct onto the
  `tierOmega*` keys that `RecordingQualityEvidencePolicy` already threshold-checks (vs `MIN_MTF50_CY_PER_PX`).
- CP-Q5 (commit `10aaeab`): a regression lock pins measured SNR/IRE-noise/MTF50/dE2000/flicker to
  analytic constants — any weakening of the measurement path trips CI.
- **Net:** the producer is correct and tested but **dormant in production** — nothing feeds it real
  decoded master frames, so the live gate never sees real measured metrics.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Real decoded-frame feed (device-gated):** a production caller decodes N frames from the just-
   recorded / exported master (HEVC/NPV10) and passes the decoded buffers to `measure()` — replacing
   synthetic goldens on-device. Provable by a test using a deterministic decoded buffer fixture.
2. **Gate reflects reality:** the Tier-Omega metric keys consumed by `RecordingQualityEvidencePolicy`
   carry the *measured* MTF50/SNR/ΔE2000/flicker from the real frames; a degraded real frame drives
   the MTF50 key below `MIN_MTF50_CY_PER_PX` and the gate FAILs (test).
3. **No synthesis path to PASS:** the producer never emits a default/optimistic metric; if frames
   can't be decoded, the corresponding keys are absent/NaN and the gate treats them as unproven
   (NOT a pass) — host test asserts absent-decode → no PASS.
4. **Honest-retire alternative:** if the decoded-frame feed is infeasible on the target device, the
   harness is explicitly marked dormant/device-gated with the on-device check documented, and nothing
   claims it runs in production (no silent synthetic-as-real).
5. **No-downgrade default:** until the real feed is device-validated, the live gate's behaviour is
   unchanged (it already does not consume this producer); enabling the feed is owner-flipped on-device.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add a production caller that decodes real master frames and feeds `measure()`, closing the
loop into the gate — or, if infeasible, honestly mark dormant. **Non-goals:** do not change the
metric math (CP01) or the Tier-Omega key projection (CP-Q3); do not synthesize any metric; no dependency.

**Plan:**
1. **Golden-lock** current `measure()`/`toTierOmegaMetadata()` outputs (regression lock CP-Q5 stays green).
2. Add a decode step (`MediaCodec`/image-reader over the recorded/exported file) producing N decoded
   luma/colour buffers; feed them to `measure(decodedFrames, baseline)`.
3. Wire the projected `tierOmega*` keys into the `RecordingQualityEvidencePolicy` evidence on the
   record-finalize path, behind a flag defaulting OFF (no-downgrade); absent decode → keys NaN/absent.
4. Add device-gated note + the exact on-device check; if decode is infeasible, mark dormant honestly.

**Files:** `recording/EliteVisionMeasurementHarness.kt`, the record-finalize caller (likely
`recording/Npv10MovConverter.kt` / `RecordingManager` finalize), `recording/RecordingQualityEvidencePolicy.kt`
(consume keys). No ABI/push-constant change (CPU measurement).

**Tests:** `EliteVisionRealFrameFeedTest` (decoded fixture → measured keys), `GateConsumesMeasuredMtf50Test`
(degraded frame → MTF50 key < floor → FAIL), `AbsentDecodeNoPassTest` (no frames → keys unproven, no PASS).

**Gating:** real-feed flag default OFF; gate behaviour unchanged until owner enables device-side.

## 6. Acceptance & verification
- **JVM:** CP-Q1/CP-Q3/CP-Q5 locks green; the three new tests green.
- **Device (vivo):** record a clip → decode N frames → measured MTF50/SNR/ΔE2000 populate the gate
  keys; a deliberately soft clip FAILs; an absent/undecodable master yields unproven (never PASS).
- **Gate keys:** `tierOmegaMtf50CyPerPx` and the Tier-Omega required-metric set now carry *measured*
  values; if unproven they stay NOT_PROVEN (honest-gate contract).

## 7. Risks & no-downgrade guardrails
- **Synthesized-as-real:** absent-decode → unproven, never a defaulted pass (AbsentDecodeNoPassTest).
- **Decode cost on finalize:** runs off the hot record path, bounded N, flag-gated; no preview impact.
- **Silent dormancy mislabel:** if infeasible, explicitly dormant/device-gated — no false "runs live".

## 8. References
- Roadmap: CP-Q1, CP-Q3, CP-Q5 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md).
- Reference: DMR / mastering QC measuring delivered-master pixels (MTF/SNR/ΔE2000) against spec.
- Source: `recording/EliteVisionMeasurementHarness.kt` (no production caller — verified), `recording/RecordingQualityEvidencePolicy.kt`.
- Related prompts: s8-f05, s8-f07, s8-f08.
