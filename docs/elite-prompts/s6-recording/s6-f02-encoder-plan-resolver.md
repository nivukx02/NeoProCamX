# RecordingVideoEncoderPlanResolver (profile/level/bitrate/I-frame) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f02 |
| **Maps to checkpoint(s)** | hardening (feeds CP70 AV1 parity, CP71 Main12 reach) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | M · M |
| **Depends on** | s6-f01 (encoder), s6-f03 (all-intra), s6-f09 (gate) |

## 1. What this feature is
`RecordingVideoEncoderPlanResolver` is the pure decision layer that turns a record request
(resolution, fps, codec, HDR/log profile, bit-depth intent) into a concrete encoder plan: HEVC
**Main10 / Main / Main12** profile, the correct **level** computed from luma picture size and
samples/sec, rate-control mode (CBR/VBR/CQ), the target bitrate (tanh-shaped content-adaptive), and
the I-frame interval (including `0` = all-intra). It is the brain feeding `RecordingManager`'s
`MediaCodec.configure`. Because it decides bit-depth gating with simulated-fallback detection, it is
the single most important honesty surface for "is this really 10-bit?".

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional recorders pick a codec *family* per deliverable: an intra-only mastering codec (ProRes
4444 XQ, X-OCN XT, REDCODE at a low compression ratio) for the negative, versus a Long-GOP delivery
codec for distribution. Level selection in H.265/HEVC is mechanical and standardized: the level is
the smallest one whose `MaxLumaPS` (luma picture size) and `MaxLumaSR` (luma samples/sec =
width·height·fps) bounds both exceed the stream — e.g. 4K60 demands Level 5.1+; 8K demands Level 6.x.
Bit-depth is a hard profile boundary: Main = 8-bit, Main10 = 10-bit, Main12 (Range Extensions) =
12-bit 4:2:2/4:4:4. The elite bar for a resolver: **never** select a profile the device can't truly
honor, **never** map an 8-bit-only encoder to a "10-bit" claim (the simulated-fallback trap), pick
the minimal conformant level (over-claiming a level wastes nothing but lying about it fails
conformance), and hold a bitrate floor matching the mastering target (820 Mbps @ 4K per
HighEndQualityPolicy). AV1 parity (CP70) and Main12 reach (CP71) extend the same conformance logic.

## 3. Current state in NeoProCamX (verified)
- `RecordingVideoEncoderPlanResolver.kt` (798 lines) — catalog §6: Main10/Main/Main12 resolution;
  HEVC/AVC/AV1 **level by pixels + samples/sec**; tiered quality; I-frame interval (incl.
  all-intra=0); **10-bit gating with simulated-fallback detection**; tanh adaptive bitrate.
- This is the highest code-quality unit in subsystem 6 (quality 9). It is genuinely live and feeds
  `RecordingManager` (s6-f01).
- The honesty keystone: its **simulated-fallback detection** is what prevents an 8-bit encoder from
  being labeled 10-bit. This must remain authoritative and be the value `OmegaCapabilityGate`
  consumes — not a parallel heuristic.
- Hardening targets (not bugs, but reach): AV1 all-intra parity is not yet present (CP70); Main12
  reach in the all-intra probe is incomplete (CP71); level tables should be audited against the
  HEVC spec `MaxLumaPS`/`MaxLumaSR` constants for 8K/high-fps corners.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Conformant level selection:** for a matrix of (resolution, fps), the resolved HEVC level is the
   minimal one satisfying both `MaxLumaPS` and `MaxLumaSR`; locked by a table-driven unit test
   against the published HEVC level constants. Same for AV1 levels (CP70) and AVC.
2. **Bit-depth truth:** Main10 is resolved only when the device truly supports 10-bit encode;
   simulated-fallback detection forces an honest 8-bit-with-flag result otherwise. Test: a device
   profile lacking real 10-bit ⇒ plan reports `tenBitSimulatedFallback = true`, never silent
   Main10.
3. **Main12 reach (CP71):** when the encoder advertises Main12 / Range-Extensions, the resolver can
   target 12-bit; otherwise it falls back to Main10 honestly. Gated, default unchanged.
4. **AV1 all-intra parity (CP70):** AV1 path resolves all-intra (keyframe-every-frame) and a
   conformant level, mirroring the HEVC all-intra plan.
5. **Bitrate floor:** resolved bitrate ≥ HighEndQualityPolicy floor for the tier (or honest
   below-floor flag); tanh adaptive curve stays monotonic and bounded.
6. **Pure & deterministic:** resolver is a pure function of its inputs (no I/O, no device call inside
   the math) so every branch is unit-testable; device caps come in as a passed-in capability struct.
7. **No-downgrade default:** existing HEVC plans for existing inputs resolve **bit-identically**;
   new AV1/Main12 branches activate only for new codec/profile requests.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden level/bit-depth conformance and extend the resolver to AV1 all-intra (CP70) and
Main12 reach (CP71), all behind requests that don't exist today. **Non-goals:** do not move the
decision into `RecordingManager`; do not call MediaCodec from inside the pure math; do not weaken
simulated-fallback detection.

**Plan:**
1. **Golden-lock first.** Add a characterization test pinning today's resolved plan for a matrix of
   existing (res, fps, codec=HEVC, profile) inputs — proves no existing plan changes.
2. **Level-table audit.** Encode the HEVC `MaxLumaPS`/`MaxLumaSR` constants explicitly; assert the
   resolver picks the minimal conformant level across the matrix incl. 4K60 / 8K30 / high-fps.
3. **AV1 all-intra (CP70).** Add AV1 level selection + all-intra (I-frame interval 0 / keyframe
   every frame) mirroring HEVC; new code path, default codec unchanged.
4. **Main12 reach (CP71).** When caps advertise Main12 / Range Extensions, allow 12-bit target;
   else honest Main10 fallback. Behind a profile request that defaults off.
5. **Bit-depth honesty test.** Capability lacking real 10-bit ⇒ `tenBitSimulatedFallback = true`.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoEncoderPlanResolver.kt`;
the capability struct it consumes; the all-intra probe shared with s6-f03 (`AllIntraHevcPolicy`).

**ABI:** none (pure Kotlin decision layer).

**Gating:** AV1 / Main12 only resolve for codec/profile requests that don't exist in the default UI
today; existing HEVC requests resolve identically (locked by the golden).

## 6. Acceptance & verification
- **Unit/golden (JVM):** existing-plan characterization green; level-conformance matrix test;
  bit-depth simulated-fallback test; AV1 all-intra plan test; Main12-reach + honest-fallback test;
  tanh-bitrate monotonicity/floor test.
- **Gate keys:** plan output is the source of truth for bit-depth and bitrate evidence in
  `RecordingQualityEvidencePolicy` (s6-f09) / `OmegaCapabilityGate`.
- **Device (vivo):** resolve + record HEVC Main10 4K → muxed profile/level match plan; if AV1
  enabled, record AV1 all-intra and confirm keyframe-every-frame in the track.

## 7. Risks & no-downgrade guardrails
- **Faked 10/12-bit:** simulated-fallback detection + honest flag → no false claim reaches the gate.
- **Non-conformant level:** explicit spec-constant tables + minimal-level test → no over/under-level.
- **Existing-plan drift:** golden characterization lock → HEVC defaults bit-identical.
- **AV1/Main12 regressions:** new branches gated to new requests; default codec untouched.

## 8. References
- Roadmap: CP70 (AV1 parity), CP71 (Main12 reach) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/14-imax-strict-quality-profile.md`](../technology_explanations/markdown/14-imax-strict-quality-profile.md)
- Reference: HEVC level table (`MaxLumaPS`/`MaxLumaSR`); Main/Main10/Main12 profile boundaries; AV1 levels; ProRes/X-OCN mastering bitrates.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoEncoderPlanResolver.kt` (798 lines), `AllIntraHevcPolicy.kt`
- Related prompts: s6-f01, s6-f03, s6-f09
