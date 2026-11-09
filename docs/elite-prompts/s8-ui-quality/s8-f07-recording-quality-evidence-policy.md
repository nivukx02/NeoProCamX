# RecordingQualityEvidencePolicy (Tier-Omega / strict 10-bit gate) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f07 |
| **Maps to checkpoint(s)** | CP-Q3 (Tier-Omega metric keys, done); propose CP-Q9 (measured-evidence hardening) |
| **Current working level** | 🔵 live-optional (engaged on the strict/Tier-Omega record path) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · L |
| **Depends on** | s8-f06 (real-metric producer feeds Tier-Omega keys), s8-f08 (Omega capability truth) |

## 1. What this feature is
The blocking recording-quality certificate: a 1,401-line gate that issues PASS/FAIL over proof,
parity, frame, mode-truth, probe, runtime, tierOmega and omega groups — enforcing per-mode
HDR/LOG/RAW/NPV10 transport truth, a bitrate floor + manual-cap, and preview=record=export signature
parity. It is the last line that decides whether a recorded master meets its claimed spec.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A delivery/QC gate on a mastering pipeline:
- Verifies the **container/transport truth** — claimed bit-depth (10/12-bit), colour primaries/transfer
  (BT.2100 PQ/HLG, S-Log/N-Log), and codec profile are what's actually in the bitstream, not a label.
- Verifies **bitrate / data-rate floors** for the claimed quality tier (all-intra mastering rates).
- Verifies **signature parity** — what the operator monitored (preview) equals what's recorded equals
  what's exported (WYSIWYG), the cardinal sin being a monitor that lies about the master.
- **Refuses to PASS on absence of evidence** — an unmeasurable claim is FAIL/NOT_PROVEN, never an
  optimistic pass. This is the honest-gate contract.
Elite = every group's verdict traces to **real transport/bit-depth/measured pixels**, with the
tierOmega group fed by the real-metric producer (s8-f06), not synthetic or heuristic values.

## 3. Current state in NeoProCamX (verified)
- File: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt` —
  **1,401 lines** (verified `wc -l` = 1401; catalog rounds to 1402).
- Per FEATURE_CATALOG §8: groups = proof/parity/frame/mode-truth/probe/runtime/tierOmega/omega;
  per-mode HDR/LOG/RAW/NPV10 transport truth; bitrate-floor + manual-cap; preview=record=export
  signature parity. Live on the strict/Tier-Omega record path.
- CP-Q3: the consumer side is wired — `tierOmegaMtf50CyPerPx` is in `TIER_OMEGA_REQUIRED_METRIC_KEYS`
  and threshold-checked vs `MIN_MTF50_CY_PER_PX`. The **producer** that fills those keys with measured
  values is s8-f06, which currently has no production caller.
- **Honesty status:** the transport/parity/mode-truth groups are genuinely evidence-based; the
  tierOmega metric group is *ready* to consume real measured metrics but, until s8-f06's real
  decoded-frame feed lands, those keys are populated only in tests.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No-evidence ⇒ no-PASS (lock):** for every group, absence of the required evidence yields FAIL /
   NOT_PROVEN — a test enumerates each required key and asserts that omitting it cannot PASS.
2. **Measured tierOmega keys:** the tierOmega metric group consumes the s8-f06 producer's measured
   MTF50/SNR/ΔE2000/flicker; a degraded real frame drives MTF50 below `MIN_MTF50_CY_PER_PX` → FAIL (test).
3. **Transport truth from the bitstream:** HDR/LOG/RAW/NPV10 bit-depth/primaries/transfer claims are
   validated against the actual container/SPS-VUI/T.35, not a flag — host test with crafted metadata.
4. **Parity enforced:** preview=record=export signature mismatch → FAIL; a test feeds mismatched
   signatures and asserts FAIL.
5. **Bitrate floor + manual cap:** below-floor bitrate FAILs; manual cap honoured; tested across tiers.
6. **No-downgrade default:** the gate's current verdicts on today's record paths are unchanged; new
   measured-evidence consumption only makes the gate *stricter* (it can newly FAIL a previously-
   unmeasured claim) and is enabled with the s8-f06 feed flag.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the gate so every group's verdict is evidence-traceable and the tierOmega group
consumes real measured metrics; lock "no-evidence ⇒ no-PASS". **Non-goals:** do not relax any
existing FAIL condition; do not synthesize any metric; do not change the transport-truth parsers'
correctness; no dependency.

**Plan:**
1. **Golden-lock** the current verdicts on representative record configs (per-mode characterization).
2. Add the "every required key absent ⇒ cannot PASS" enumerated test if not already exhaustive.
3. Consume the s8-f06 measured tierOmega keys (behind the same OFF-default feed flag); ensure absent
   measurement leaves keys unproven, not defaulted.
4. Strengthen transport-truth assertions against crafted bitstream metadata fixtures.

**Files:** `recording/RecordingQualityEvidencePolicy.kt`; fixtures for crafted SPS-VUI/T.35/NPV10
metadata. No ABI/push-constant change (gate is CPU-side).

**Tests:** `GateNoEvidenceNoPassEnumeratedTest`, `GateMeasuredTierOmegaTest` (degraded → FAIL),
`GateTransportTruthFixtureTest` (crafted metadata), `GateParityMismatchFailTest`, `GateBitrateFloorTest`.

**Gating:** measured-evidence consumption behind the s8-f06 feed flag (default OFF); existing verdicts
unchanged until enabled.

## 6. Acceptance & verification
- **JVM:** per-mode verdict characterization unchanged for default; all new tests green.
- **Device (vivo):** record HDR/LOG/RAW/NPV10 clips → gate PASS only when transport truly matches the
  claim; soft/under-bitrate clip FAILs; parity break FAILs.
- **Gate keys:** tierOmega keys reflect measured values (via s8-f06); unproven ⇒ NOT_PROVEN, never PASS.

## 7. Risks & no-downgrade guardrails
- **Synthesized PASS:** no-evidence ⇒ no-PASS enumerated lock makes a fabricated pass impossible.
- **Stricter gate breaking valid records:** new strictness only triggers on genuinely-unproven claims;
  per-mode characterization guards real valid paths.
- **Transport mis-parse:** crafted-metadata fixtures pin the parsers.

## 8. References
- Roadmap: CP-Q3 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md).
- Reference: mastering/QC delivery gate (transport-truth bit-depth/primaries/transfer, bitrate floors, WYSIWYG parity, no-evidence-no-pass).
- Source: `recording/RecordingQualityEvidencePolicy.kt` (1,401 lines), `recording/EliteVisionMeasurementHarness.kt`.
- Related prompts: s8-f06, s8-f08.
