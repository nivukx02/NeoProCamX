# Tier-Omega Quality Gate (RecordingQualityEvidencePolicy) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f09 |
| **Maps to checkpoint(s)** | CP-Q3 (MTF50 → Tier-Omega keys, done), hardening + real-measurement consumption |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6/10 |
| **Code-quality rating (now)** | 8.5/10 |
| **Risk · Size** | M · L |
| **Depends on** | s6-f08 (measurement producer), s6-f01/f02/f03 (encoder evidence), s6-f04/f05 (master/export), OmegaCapabilityGate |

## 1. What this feature is
`RecordingQualityEvidencePolicy` is the 1402-line blocking quality gate that issues a PASS/FAIL/
`NOT_PROVEN` certificate for a recording. It validates proof / parity / frame / mode-truth / probe /
runtime / Tier-Omega / Omega evidence groups: per-mode HDR/LOG/RAW/NPV10 transport truth, bitrate
floor + manual cap, preview=record=export signature parity, GPU/zero-copy claim validation, and the
`tierOmega*` metric thresholds (MTF50, SNR, ΔE2000…). It is the honesty backstop — its entire design
is to refuse to certify a claim it can't prove from real transport/bit-depth/pixels.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A deliverable doesn't ship until it passes an objective gate: IMF/Photon validation, IMAX DMR QC,
broadcast QC (Tektronix/Telestream) measure the *actual master* and **block** on any out-of-spec
metric — wrong transfer, under-bitrate, banding, dropped frame, color out of tolerance. Critically,
these gates measure real pixels/transport, never trust an asserted property. The elite bar for
NeoProCamX's gate: every certified claim (10-bit, HDR transport, NPV10 native, GPU/zero-copy,
bitrate floor, MTF50/SNR/ΔE2000 thresholds) is backed by a *real measurement or real transport
probe*; anything else lands `NOT_PROVEN`, not PASS. The gate is the contract that "elite" is earned,
not declared.

## 3. Current state in NeoProCamX (verified)
- `RecordingQualityEvidencePolicy.kt` (1401 lines) — `internal object` (`:171`).
- Tri-state result: `PASS` / `FAIL` / `NOT_PROVEN` (`:13-15`).
- Honest `NOT_PROVEN` reasons are first-class: `NPV10_NATIVE_10BIT_NOT_PROVEN` (`:29`),
  `GPU_PROCESSING_NOT_PROVEN` (`:44`), `PROCESSED_RECORDING_NOT_PROVEN` (`:45`),
  `ZERO_COPY_CLAIM_NOT_PROVEN` (`:46`), `REALTIME_PERFORMANCE_NOT_PROVEN` (`:47`),
  `TIER_OMEGA_METRICS_NOT_PROVEN` (`:54`), `TIER_OMEGA_METRIC_THRESHOLD_FAILED` (`:55`),
  `BITRATE_MEASUREMENT_NOT_PROVEN` (`:56`).
- Default posture is honest: `"qualityEvidenceStatus" to
  "NOT_PROVEN_WITHOUT_RECORDED_FILE_PROBE_AND_DEVICE_LOGS"` (`:200`) — without a real probe it does
  NOT claim PASS.
- CP-Q3 wired the consumer: `tierOmegaMtf50CyPerPx` is in `TIER_OMEGA_REQUIRED_METRIC_KEYS`,
  threshold-checked vs `MIN_MTF50_CY_PER_PX`; the producer projection
  (`EliteVisionMeasurementHarness.toTierOmegaMetadata`) exists. **But** (see s6-f08) nothing in
  production *calls* the producer on real frames — so the Tier-Omega metric keys, in the live flow,
  are populated from whatever evidence map the caller passes, not a closed real-pixel loop.
- This is the strongest honesty surface in subsystem 6 (quality 8.5). Hardening = ensure every key
  it threshold-checks is fed by a real producer (s6-f08), and that no path can pass an unproven
  claim.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Real-measurement consumption:** every `tierOmega*` metric the gate threshold-checks is sourced
   from `EliteVisionMeasurementHarness.toTierOmegaMetadata` over real decoded frames (s6-f08) in the
   live flow; a missing/synthetic source ⇒ `TIER_OMEGA_METRICS_NOT_PROVEN`, never PASS. Test the
   real vs missing source branches.
2. **Bit-depth/transport truth:** NPV10 native-10-bit, HDR/LOG/RAW transport, GPU/zero-copy claims
   each require their real evidence or land the corresponding `*_NOT_PROVEN`. Test each branch.
3. **Bitrate floor + manual cap:** certificate FAILs/NOT_PROVENs when measured bitrate is below the
   HighEndQualityPolicy floor (s6-f01/f02 evidence) or exceeds a manual cap dishonestly.
4. **Parity signature:** preview=record=export parity is checked; once s6-f06 makes it a *measured*
   ΔE2000, the gate consumes the measured value, not just a boolean.
5. **No synthesized PASS:** there is no code path that turns an unproven claim into PASS; default
   without a probe is `NOT_PROVEN` (already true at `:200` — lock it with a test).
6. **Deterministic & reproducible:** for fixed evidence the certificate is deterministic (CI-lockable).
7. **No-downgrade default:** the gate is evidence-only — it blocks/annotates, never alters pixels.
   Tightening it can only convert a previously-silent over-claim into an honest FAIL/NOT_PROVEN,
   which is the intended behavior, not a downgrade of any *correct* path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** ensure the gate consumes **real** measured evidence (s6-f08) for every threshold it
checks, and that no branch can synthesize a PASS. **Non-goals:** do not relax any threshold; do not
alter pixels; do not add a library.

**Plan:**
1. **Golden-lock first.** Pin today's certificate for a representative evidence corpus (SDR/HDR/LOG/
   NPV10) so behavior is frozen before tightening.
2. **Real-source assertion.** When `tierOmega*` keys are present, assert their provenance is the
   real harness producer (s6-f08); when absent/synthetic ⇒ `TIER_OMEGA_METRICS_NOT_PROVEN`. Test.
3. **Branch coverage.** Add/extend tests for each `*_NOT_PROVEN` reason: NPV10 native-10-bit,
   GPU/zero-copy, realtime, bitrate measurement, processed-recording — proving the honest path.
4. **Bitrate-floor gate.** Wire the s6-f01/f02 bitrate-floor evidence; FAIL/NOT_PROVEN below floor.
5. **No-PASS-without-probe lock.** Test that the default (no probe) certificate is NOT_PROVEN.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`;
its evidence inputs from `RecordingManager.kt` / `Npv10MovConverter.kt` /
`EliteVisionMeasurementHarness.kt`; `OmegaCapabilityGate` (consumed, not modified).

**ABI:** none (evidence string-map + enums).

**Gating:** evidence-only; pixels untouched. Tightening converts silent over-claims to honest
FAIL/NOT_PROVEN; the golden lock proves no *correct* certificate changes.

## 6. Acceptance & verification
- **Unit/golden (JVM):** certificate characterization lock; real-vs-missing Tier-Omega source test;
  each `*_NOT_PROVEN` branch test; bitrate-floor FAIL test; no-probe ⇒ NOT_PROVEN test.
- **Gate keys:** all `tierOmega*` keys + transport-truth flags; cross-checked against
  `OmegaCapabilityGate` (claims it can't prove ⇒ NOT_AVAILABLE).
- **Device (vivo):** record clips per mode (SDR/HDR/LOG/NPV10) → certificate PASSes only when real
  evidence supports it; deliberately under-bitrate / mistag ⇒ FAIL/NOT_PROVEN; flag/behavior of the
  recording path unchanged.

## 7. Risks & no-downgrade guardrails
- **Synthesized PASS:** no PASS-without-probe path + default NOT_PROVEN lock (`:200`) → honest by
  construction.
- **Fixture-fed metrics:** real-source assertion + s6-f08 producer wiring → metrics come from real
  pixels or NOT_PROVEN.
- **Over-claimed bit-depth/bitrate:** per-mode transport truth + bitrate-floor gate.
- **False parity:** consumes measured ΔE2000 once s6-f06 lands; boolean-only ⇒ flagged.
- **Certificate drift:** golden lock + determinism test.

## 8. References
- Roadmap: CP-Q1/CP-Q3 (real-pixel gate wiring) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md), [`../technology_explanations/markdown/14-imax-strict-quality-profile.md`](../technology_explanations/markdown/14-imax-strict-quality-profile.md)
- Reference: IMF/Photon validation, IMAX DMR QC, broadcast QC — measure-and-block gates.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt:13, 29, 44, 54, 171, 200`
- Related prompts: s6-f01, s6-f02, s6-f03, s6-f04, s6-f05, s6-f06, s6-f08; subsystem-8 `OmegaCapabilityGate`
