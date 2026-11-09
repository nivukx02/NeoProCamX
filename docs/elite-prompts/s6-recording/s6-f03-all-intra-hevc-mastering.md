# All-Intra HEVC Mastering (AllIntraHevcPolicy) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f03 |
| **Maps to checkpoint(s)** | CP70 (AV1 all-intra parity), CP71 (Main12 reach in all-intra probe) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 7.5/10 |
| **Code-quality rating (now)** | 8.5/10 |
| **Risk · Size** | M · M |
| **Depends on** | s6-f02 (plan resolver), s6-f01 (encoder), s6-f09 (gate) |

## 1. What this feature is
`AllIntraHevcPolicy` produces a capability-validated all-intra HEVC encoder config: every frame is
an I-frame (`KEY_I_FRAME_INTERVAL = 0`), the bitrate is clamped to real encoder caps, and CBR is
forced (all-intra needs CBR to avoid VBR oscillation between independent keyframes). It probes the
device's `MediaCodecInfo` for advertised all-intra / profile / level support and returns `null` for
an **honest IPB fallback** when the encoder can't truly deliver. This is the mastering-grade record
mode: frame-accurate cuts, no inter-frame compression artifacts to fight in grade.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The camera negative is intra-only by design. ProRes (all variants), ARRIRAW, REDCODE RAW, X-OCN and
Blackmagic RAW all store each frame independently so an editor can cut, grade, and conform on any
frame without decoding a GOP. ProRes 422 HQ ≈ 220 Mbps and ProRes 4444 XQ > 500 Mbps at 1080p set
the intra-frame bitrate expectation; the cost (5–10× a Long-GOP file) is accepted because
mastering fidelity and editorial flexibility are paramount. The elite bar for an all-intra HEVC
policy on a phone: every frame truly an I-frame (verifiable in the bitstream — no P/B NAL units),
CBR locked, a bitrate floor in the mastering tier (the policy's CINEMA_MASTER 180 Mbps / PRO 120
Mbps tiers, ideally climbing toward the HighEndQualityPolicy 4K floor of 820 Mbps where the encoder
allows), correct Main10/Main12 profile, and — when extended — AV1 all-intra parity so the codec
choice doesn't force a fidelity downgrade.

## 3. Current state in NeoProCamX (verified)
- `AllIntraHevcPolicy.kt` (264 lines) — `object`; imports real `MediaCodecInfo` / `MediaCodecList`
  / `MediaFormat` (`:3-5`); doc explains all-intra = every frame I-frame, ~5-10× file size (`:14-25`).
- Bitrate tiers as constants: `BITRATE_CINEMA_MASTER = 180_000_000` (`:54`), `PRO_ALL_INTRA`
  120 Mbps (`:56`), `STANDARD` 100 Mbps (`:57`), `ECONOMY` 80 Mbps (`:58`).
- Hardware requirements documented: Main/Main10/Main 4:2:2-10 high tier, Level 5.1+ for 4K, CBR
  required (`:38-42`).
- **Honest fail-safe is real:** "If the encoder does not advertise All-Intra capability, the policy
  returns null and the caller falls back to standard IPB" (`:44-48`) — this is the no-fake contract.
- **Reach gaps:** the policy is HEVC-only — no AV1 all-intra path (CP70); the probe does not yet
  reach Main12 / Range-Extensions 12-bit even where advertised (CP71); the 180 Mbps cinema-master
  tier is below the HighEndQualityPolicy 4K mastering floor (820 Mbps) and is self-documented as
  "not a hardware ceiling" (`:30`) — elite means climbing toward device-real headroom.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **True all-intra in the bitstream:** a recorded all-intra clip contains only IDR/I NAL units —
   zero P/B slices. Verifiable by parsing the muxed stream (device check) and by a unit test on the
   config asserting `KEY_I_FRAME_INTERVAL == 0` and CBR.
2. **Capability-honest:** the policy returns a config **only** when the encoder advertises the
   required profile/level/all-intra; otherwise `null` → IPB fallback. Test both branches.
3. **Bitrate clamped to real caps:** requested tier is clamped to the encoder's advertised
   `BitrateRange.upper`; no config that the encoder will silently reject. Test the clamp.
4. **AV1 all-intra parity (CP70):** an AV1 all-intra config path mirrors the HEVC one (keyframe
   every frame, level-correct), behind a codec request that defaults off.
5. **Main12 reach (CP71):** when Main12 / Range Extensions advertised, the probe can target 12-bit
   all-intra; else honest Main10. Gated.
6. **Mastering-tier bitrate:** all-intra tier climbs toward the HighEndQualityPolicy floor where the
   encoder permits (no artificial 180 Mbps ceiling when the device offers more) — recorded as
   honest evidence, never an over-claim beyond device caps.
7. **No-downgrade default:** existing HEVC all-intra configs for existing requests are unchanged
   (golden-locked); AV1 / Main12 / higher-tier bitrate are new opt-in paths.

## 5. Implementation prompt (hand this to the agent)
**Scope:** extend the all-intra probe to AV1 (CP70) and Main12 reach (CP71), and let the bitrate
tier track real device caps instead of a fixed ceiling — all capability-honest. **Non-goals:** do
not remove the null→IPB fail-safe; do not force all-intra by default; do not add a library.

**Plan:**
1. **Golden-lock first.** Pin today's `AllIntraConfig` (interval, CBR, bitrate, profile) for the
   existing tiers/devices via a characterization test — proves no existing config changes.
2. **Bitstream truth test.** Assert config implies I-only (`KEY_I_FRAME_INTERVAL == 0`, CBR); add a
   device-check step that parses the muxed stream for absence of P/B slices.
3. **AV1 all-intra (CP70).** Add `allIntraAv1Config()` mirroring HEVC: probe AV1 caps, keyframe
   every frame, level-correct, CBR; return null → IPB if unsupported. Behind AV1 request.
4. **Main12 reach (CP71).** When Main12 advertised, allow a 12-bit all-intra target; honest Main10
   fallback otherwise.
5. **Cap-tracking bitrate.** Clamp to `BitrateRange.upper`; let the tier climb toward the
   HighEndQualityPolicy floor where headroom exists; record honest evidence of the achieved bitrate.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/AllIntraHevcPolicy.kt`; shared probe
with `RecordingVideoEncoderPlanResolver.kt` (s6-f02); consumed by `RecordingManager.kt` (s6-f01).

**ABI:** none.

**Gating:** AV1/Main12/higher-bitrate behind requests that don't exist today; existing HEVC
all-intra path golden-locked unchanged.

## 6. Acceptance & verification
- **Unit/golden (JVM):** existing-config characterization green; all-intra ⇒ interval-0+CBR test;
  null→IPB fallback test for unsupported caps; bitrate-clamp test; AV1 all-intra config test;
  Main12 reach + honest fallback test.
- **Gate keys:** all-intra evidence (I-only, bitrate achieved) reaches `RecordingQualityEvidencePolicy`
  (s6-f09); capability proven via `OmegaCapabilityGate`.
- **Device (vivo):** record all-intra 4K Main10 → bitstream is I-only, CBR, profile==Main10; if AV1
  enabled → AV1 all-intra I-only; confirm no IPB frames slipped in.

## 7. Risks & no-downgrade guardrails
- **Pseudo-all-intra (encoder ignores interval=0):** bitstream P/B-slice device check → caught, not
  claimed.
- **Encoder-reject config:** clamp to advertised caps → no silent rejection.
- **Over-claimed bitrate:** clamp to device cap + honest achieved-bitrate evidence → no false elite.
- **Default regression:** golden lock on existing tiers; AV1/Main12 gated to new requests.
- **Storage blow-up:** all-intra stays opt-in (it's 🔵 live-optional), file-size cost documented.

## 8. References
- Roadmap: CP70 (AV1 parity), CP71 (Main12 reach) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/14-imax-strict-quality-profile.md`](../technology_explanations/markdown/14-imax-strict-quality-profile.md)
- Reference: ProRes / ARRIRAW / REDCODE / X-OCN intra-frame mastering; HEVC Main12 Range Extensions; AV1 all-intra.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/AllIntraHevcPolicy.kt:14, 30, 38, 44, 54-58`
- Related prompts: s6-f01, s6-f02, s6-f09
