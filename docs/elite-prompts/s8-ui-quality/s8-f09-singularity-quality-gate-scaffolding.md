# Singularity Quality-Gate Scaffolding (QG01-16, FrameGraphRegistry, ProductionReadinessGate, AssetManifestValidator) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.
> **Honesty theme — this is the anti-pattern.** `allPassingResults()` synthesizes 16 PASS verdicts
> with zero measurement. That is exactly what the OmegaCapabilityGate contract (§0.6) forbids. Elite
> here = replace synthesis with real measurement, or honestly retire. No middle ground.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f09 |
| **Maps to checkpoint(s)** | new — propose CP-Q11 (de-synthesize or retire) |
| **Current working level** | ⚪ test-only-scaffold (synthesizes PASS; sub-components caller-less) |
| **Feature rating (potential)** | 4/10 now → 6/10 target (or honestly retire) |
| **Code-quality rating (now)** | 6/10 |
| **Risk · Size** | M · M |
| **Depends on** | s8-f05 (real metrics), s8-f06 (real-frame producer), s8-f08 (honest-gate contract) |

## 1. What this feature is
A 16-gate quality scaffold (QG01–QG16) plus a `FrameGraphRegistry`, a `ProductionReadinessGatePolicy`
and a `SingularityAssetManifestValidator`. It is meant to be a comprehensive release-readiness gate.
In its current form its central convenience, `allPassingResults()`, **manufactures** 16 PASS results
with no underlying measurement — and the two satellite policies have no live callers.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A release-readiness / QC gate on a real pipeline:
- Each gate result is **earned** by a measurement or a verified fact — a PASS means something was
  checked. A function that returns "all PASS" by construction is not a gate; it is decoration, and on
  a delivery pipeline it would be a fireable QC fraud.
- The honest forms are: (a) measure and PASS/FAIL on the result, or (b) if you can't measure, report
  NOT_PROVEN — never synthesize success (the OmegaCapabilityGate model, s8-f08).
- Dead validators with no caller are either wired into CI against real assets or removed — unused QC
  code rots and lies about coverage.
Elite = every QG result traces to a real measurement (CP01/s8-f05 metrics, s8-f06 decoded frames, or a
verified transport/asset fact), or the scaffold is retired with an honest note.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/quality/SingularityQualityGate.kt:175` —
  `allPassingResults()` (verified): it `mapIndexed` over `SingularityQualityGateId.entries` and returns
  `SingularityQualityGateResult.pass(...)` for **every** gate, with a synthetic `evidenceReference`
  string and a fabricated `responsibleStageId` (`247 + index…`). **No measurement is performed.** This
  is the synthesized-PASS anti-pattern §0.6 forbids.
- `allPassingResults()` callers (verified, non-test): only `SingularityQualityGate.kt` itself.
- `app/src/main/java/com/neoproc/neoproccamx/quality/ProductionReadinessGatePolicy.kt` — **zero
  live callers** (verified: `grep -rln` returns only the file itself).
- `app/src/main/java/com/neoproc/neoproccamx/quality/SingularityAssetManifestValidator.kt` — **zero
  live callers** beyond self (verified).
- `SingularityFrameGraphRegistry.kt` exists; the gate does validate the threshold asset enumerates
  QG01..QG16 (`SingularityQualityGate.kt:160-172`) — that part is a real check.
- Per FEATURE_CATALOG §8: feat 4 / quality 6 — scaffold.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Delete or quarantine `allPassingResults()`:** no production code may obtain a Singularity PASS
   without measurement. A test asserts `allPassingResults()` is either removed, or `@VisibleForTesting`
   and unreachable from any production call graph (source-hygiene meta-test).
2. **Real per-gate measurement (if kept):** each QG that maps to a measurable property (resolution,
   noise, colour, transport) gets its verdict from s8-f05 metrics / s8-f06 decoded frames / a verified
   transport fact; unmeasurable gates report NOT_PROVEN, never PASS — host test per gate.
3. **No-evidence ⇒ no-PASS:** a test feeds empty evidence and asserts the gate cannot return all-PASS.
4. **Satellite policies — wire or retire:** `ProductionReadinessGatePolicy` and
   `SingularityAssetManifestValidator` are either given a real CI caller validating committed assets,
   or removed; a meta-test asserts no dead caller-less quality policy remains in the package.
5. **No-downgrade default:** nothing in the live app currently depends on a synthesized PASS (verified
   — only self-references), so de-synthesizing cannot regress live behaviour; if any caller is found,
   it is migrated to the measured path in the same commit.

## 5. Implementation prompt (hand this to the agent)
**Scope:** eliminate the synthesized-PASS path and either wire the scaffold to real measurement or
honestly retire it + its caller-less satellites. **Non-goals:** do not invent a measurement that
isn't real; do not leave a half-measured gate that still PASSes by default; no dependency.

**Plan:**
1. **Audit** the full call graph of `allPassingResults()`, `ProductionReadinessGatePolicy`,
   `SingularityAssetManifestValidator` (confirm caller-less; verified at authoring time).
2. **Decision per component:**
   - QG gates with a measurable mapping → consume s8-f05/s8-f06 measured values; PASS/FAIL on result.
   - QG gates without a real measure → NOT_PROVEN (s8-f08 model), never synthesized PASS.
   - `allPassingResults()` → delete, or mark `@VisibleForTesting` + assert unreachable from production.
   - `ProductionReadinessGatePolicy` / `SingularityAssetManifestValidator` → wire to a CI asset check
     or remove.
3. Add the source-hygiene meta-test forbidding synthesized PASS and dead caller-less quality policies.

**Files:** `quality/SingularityQualityGate.kt`, `quality/ProductionReadinessGatePolicy.kt`,
`quality/SingularityAssetManifestValidator.kt`, `quality/SingularityFrameGraphRegistry.kt`. No ABI change.

**Tests:** `NoSynthesizedPassMetaTest`, `SingularityGateNoEvidenceNoPassTest`, per-gate measured-verdict
tests for the measurable QGs, `NoDeadQualityPolicyMetaTest`.

**Gating:** none required (no live dependency on the synthesized path — verified); if a caller surfaces,
migrate it in the same commit. Honest-retire is an acceptable terminal outcome with a documented note.

## 6. Acceptance & verification
- **JVM:** the meta-tests + per-gate measured tests green; `allPassingResults()` no longer reachable
  from production (or removed).
- **Device (vivo):** not directly device-gated; if any measurable QG is wired to s8-f06, it inherits
  that device check.
- **Gate keys:** **false-pass count = 0** for the Singularity gate — no PASS without measurement.

## 7. Risks & no-downgrade guardrails
- **Lingering synthesized PASS:** `NoSynthesizedPassMetaTest` forbids it in CI.
- **Removing something live:** call-graph audit first; no live dependency found at authoring (verified).
- **Half-measured gate PASSing:** unmeasurable gates report NOT_PROVEN, never PASS (s8-f08 contract).

## 8. References
- Standard: honest-gate contract §0.6 in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) — the exact rule this scaffold violates.
- Reference: release-readiness QC where every PASS is earned by measurement; CAI/C2PA "no claim without proof"; the OmegaCapabilityGate (s8-f08) as the honest counter-example.
- Source: `quality/SingularityQualityGate.kt:160-172` (real asset check), `:175` (`allPassingResults()` synthesis), `quality/ProductionReadinessGatePolicy.kt` (zero callers), `quality/SingularityAssetManifestValidator.kt` (zero callers).
- Related prompts: s8-f05, s8-f06, s8-f08.
