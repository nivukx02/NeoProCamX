# OmegaCapabilityGate (anti-fraud capability truth gate) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.
> This gate **is** the honest-gate contract (§0.6) made code. The job here is to keep it incorruptible.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f08 |
| **Maps to checkpoint(s)** | new — propose CP-Q10 (capability-truth hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 9/10 |
| **Code-quality rating (now)** | 9/10 (the standout honesty feature) |
| **Risk · Size** | M · M |
| **Depends on** | s8-f07 (recording gate consults capability truth), s8-f02/s8-f03 (UI claims gated by it) |

## 1. What this feature is
The anti-fraud capability truth gate: it validates SDR/NDR/NIVUKX/N-Log/RAW/NPV10/Vulkan/manual-
bitrate against *real* transport + bit-depth, and blocks external/licensed/unproven claims as
`NOT_AVAILABLE` rather than faking a pass. It is the single source of truth for "what this build can
actually deliver", consumed by the UI and the recording gate.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Trustworthy systems never advertise a capability they can't deliver:
- A camera that can't write true 10-bit reports SDR/8-bit — it does not mislabel. ARRI/RED/Sony spec
  sheets are conservative and **provable**; a "RAW" indicator means the bitstream is RAW.
- Provenance / chain-of-custody (forensic media authentication, CAI/C2PA-style) is asserted only when
  cryptographically established — otherwise it's "unverified", never silently "verified".
- The honest default for any unprovable claim is **unavailable / not-proven**, full stop.
Elite = the gate is **incorruptible by construction**: there is no code path that turns an unproven
claim into available, and every "available" verdict traces to a real transport/bit-depth check.

## 3. Current state in NeoProCamX (verified)
- File: `app/src/main/java/com/neoproc/neoproccamx/quality/OmegaCapabilityGate.kt`.
- **Verified honest defaults:** `statusById[id] ?: NOT_AVAILABLE` (line 127), blocking-failure path →
  `NOT_AVAILABLE` (line 132), `UNPROVEN_FORENSIC_PROVENANCE -> NOT_AVAILABLE` (line 166), and
  multiple guard returns of `NOT_AVAILABLE` (lines 205/221/232). The default for the unknown/unproven
  is correctly the *unavailable* state.
- `evaluate(input)` (line 113) produces an `OmegaQualityTruthReport`; `NOT_AVAILABLE` is the enum's
  fail-safe value. This is exactly the pattern §0.6 mandates and that s8-f09's `allPassingResults()`
  violates.
- Per FEATURE_CATALOG §8: feat 8 / quality 9 — the standout honesty feature. Live and default-on.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No path from unproven→available:** a meta/property test enumerates every capability id and asserts
   that with empty/insufficient evidence the verdict is `NOT_AVAILABLE` — there is no input that
   yields "available" without the corresponding real evidence.
2. **Fail-closed default:** any unmapped/new capability id defaults to `NOT_AVAILABLE` (already true at
   line 127) — a test adds a synthetic id and asserts it is unavailable by default.
3. **Evidence traceability:** each "available" verdict cites the transport/bit-depth fact that proved
   it; a test asserts an available verdict cannot exist without its evidence field populated.
4. **Single source of truth:** the recording gate (s8-f07) and UI (s8-f02/s8-f03) consult this gate
   for capability claims; a test asserts the recording gate's RAW/10-bit/NPV10 claims are gated by it.
5. **Incorruptible under refactor:** a characterization test pins the verdict for a fixed evidence
   matrix so any future change that loosens the gate trips CI.
6. **No-downgrade default:** the gate's current verdicts on real inputs are byte-identical; hardening
   only adds tests + traceability, never relaxes a NOT_AVAILABLE.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden and prove the gate's incorruptibility (fail-closed, no unproven→available, evidence
traceability), and confirm it is the single capability source for UI + recording gate. **Non-goals:**
do not relax any NOT_AVAILABLE; do not add a capability the silicon can't prove; no dependency.

**Plan:**
1. **Characterization-lock** the verdict for a fixed evidence matrix (every id × proven/unproven).
2. Add the enumerated "empty evidence ⇒ NOT_AVAILABLE for all ids" property test and the synthetic-id
   fail-closed test.
3. Add an evidence-traceability assertion (available ⇒ evidence field present).
4. Verify (and test) that s8-f07 + the UI consult this gate for capability claims; add shims if any
   claim currently bypasses it.

**Files:** `quality/OmegaCapabilityGate.kt` (mostly tests); the recording gate / UI call-sites that
must route through it. No ABI/push-constant change.

**Tests:** `OmegaGateFailClosedPropertyTest` (empty evidence → all NOT_AVAILABLE),
`OmegaGateNoUnprovenToAvailableTest`, `OmegaGateEvidenceTraceabilityTest`,
`OmegaGateVerdictCharacterizationTest` (fixed evidence matrix snapshot).

**Gating:** none — hardening is additive and verdict-preserving; the characterization snapshot is the lock.

## 6. Acceptance & verification
- **JVM:** all new tests green; the verdict characterization snapshot unchanged for the current matrix.
- **Device (vivo):** on a device lacking true 10-bit/RAW/NPV10, those capabilities report NOT_AVAILABLE
  and the UI/recording gate refuse to claim them.
- **Gate keys:** this gate IS the capability truth source; **false-pass count must be 0** — proven by
  the fail-closed property test.

## 7. Risks & no-downgrade guardrails
- **Corruption via refactor:** characterization snapshot + fail-closed property test trip CI on any loosening.
- **Bypassed claim:** call-site audit + test that UI/recording gate route through this gate.
- **New capability added unsafely:** unmapped id defaults NOT_AVAILABLE (line 127) — fail-closed by construction.

## 8. References
- Reference: conservative provable spec-sheet capability claims (ARRI/RED/Sony); CAI/C2PA-style provenance "unverified-by-default"; fail-closed security gating.
- Standard: honest-gate contract §0.6 in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md).
- Source: `quality/OmegaCapabilityGate.kt` (NOT_AVAILABLE defaults at lines 9, 83, 88, 127, 132, 166, 205, 221, 232).
- Related prompts: s8-f02, s8-f03, s8-f07, s8-f09 (the anti-pattern this gate forbids).
