# Settings System (6 sections) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f03 |
| **Maps to checkpoint(s)** | new — propose CP-U3 (settings integrity + capability-aware gating) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · S |
| **Depends on** | s8-f04 (settings mutate ViewModel state), s8-f08 (capability-aware option enablement) |

## 1. What this feature is
The user-facing configuration surface: six sections — CAMERA, RECORD, COLOR, FOCUS, MONITORING,
SYSTEM — built by a section factory + option catalog + UI factory, exposing real toggles
(AI Enhancement, AI Motion Compensation, Noise Reduction, Resolution, Frame Rate) that drive the
live pipeline.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro bodies (ARRI ALEXA menu, RED DSMC2, Sony VENICE) treat settings as a **typed, validated,
capability-gated state machine**:
- Options that the hardware/codec can't satisfy are **disabled and explained** (greyed with a reason),
  never silently ignored — e.g. 10-bit unavailable on a codec is shown as unavailable, not falsely on.
- Settings are **persisted and round-trip** across power cycles; a "reset to defaults" restores a
  known-good baseline.
- Mutually-exclusive / dependent options enforce **invariants** (e.g. RAW + a given fps combo),
  surfaced before the user can pick an illegal combination.
- No setting can put the camera into an unprovable/false state (this is the honesty bar shared with
  the Omega capability gate).

## 3. Current state in NeoProCamX (verified)
- Section factory: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt`.
- Option catalog: `.../ui/controls/SettingsOptionCatalog.kt`.
- Resolver: `.../ui/controls/SettingsSectionResolver.kt`. UI factory: `.../ui/controls/SettingsUiFactory.kt`.
- Per FEATURE_CATALOG §8: six sections, "real toggles (AI Enhancement, AI Motion Compensation,
  Noise Reduction, Resolution, Frame Rate)." Live and default-on.
- **Gap (verify on read):** options are likely presented regardless of device capability (no
  capability-aware disable-with-reason); dependency invariants between sections may be implicit;
  persistence round-trip not asserted by a test.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Capability-aware enablement:** an option whose capability cannot be proven (10-bit, RAW, NPV10,
   a given resolution/fps) is rendered disabled with a reason string sourced from the capability
   truth (s8-f08). Host test: given a capability report with X unavailable, the catalog marks X
   disabled+reasoned.
2. **Typed validation:** the option catalog is exhaustively typed; a meta-test asserts every option
   has a type, default, valid range/enum, and a section. No untyped/free-string toggles.
3. **Dependency invariants:** illegal combinations are rejected by a pure resolver function; a test
   enumerates the known mutually-exclusive pairs and asserts rejection before commit.
4. **Persistence round-trip:** write settings → serialize → deserialize yields the identical typed
   state (host test on the serializer).
5. **No-downgrade default:** the six sections, their current options, and default values are unchanged;
   new behaviour (disable-with-reason, invariant rejection) only restricts illegal states, never
   alters a legal current selection.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make settings typed, capability-aware, invariant-enforcing, and round-trip-locked.
**Non-goals:** no new section; no change to how a legal setting drives the pipeline; no dependency.

**Plan:**
1. **Golden-lock** the current section/option catalog with a characterization test (section→options map).
2. Add a capability-aware enablement layer that consults the Omega capability report (s8-f08) to
   disable+explain unprovable options; default capability = today's behaviour where a report is absent.
3. Add the dependency-invariant resolver + persistence round-trip serializer with tests.

**Files:** `SettingsOptionCatalog.kt`, `SettingsSectionFactory.kt`, `SettingsSectionResolver.kt`,
`SettingsUiFactory.kt`. No ABI/push-constant change.

**Tests:** `SettingsCatalogTypingMetaTest` (every option typed/defaulted/ranged), `SettingsCapabilityGatingTest`
(unavailable capability → disabled+reason), `SettingsInvariantResolverTest`, `SettingsPersistenceRoundTripTest`.

**Gating:** capability gating defaults to "available" when no report present (today's behaviour);
purely restricts proven-impossible options.

## 6. Acceptance & verification
- **JVM:** the four tests green; section/option characterization unchanged for default.
- **Device (vivo):** on a device lacking 10-bit/RAW, those options show disabled+reason; settings
  persist across app restart; no legal selection changes behaviour.
- **Gate keys:** option enablement consults `OmegaCapabilityGate` truth; never asserts a PASS itself.

## 7. Risks & no-downgrade guardrails
- **False capability:** enablement reads the honest capability report — can't offer unprovable modes.
- **Lost settings:** round-trip serializer test guards persistence.
- **Illegal combos:** invariant resolver rejects before commit; legal current states untouched.

## 8. References
- Reference: ARRI/RED/Sony capability-gated menu design (disable-with-reason, persisted state, invariants).
- Source: `ui/controls/{SettingsSectionFactory,SettingsOptionCatalog,SettingsSectionResolver,SettingsUiFactory}.kt`.
- Related prompts: s8-f04, s8-f08.
