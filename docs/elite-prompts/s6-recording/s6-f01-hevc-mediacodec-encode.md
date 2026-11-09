# HEVC MediaCodec Encode (RecordingManager, MOV-only) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated, honest
> gates) applies here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f01 |
| **Maps to checkpoint(s)** | hardening (no new CP — protect-and-tighten existing live encoder) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8.5/10 |
| **Code-quality rating (now)** | 8.5/10 |
| **Risk · Size** | M · L |
| **Depends on** | s6-f02 (plan resolver), s6-f07 (color-metadata rewriter), s6-f10 (persistent surface), s6-f09 (quality gate) |

## 1. What this feature is
This is the live recording backbone: `RecordingManager` drives a `MediaCodec` HEVC encoder fed by
the GPU encoder Surface (parity with preview), drains encoded frames into a `MediaMuxer` configured
**MOV-only** (QuickTime backend; MP4 is hard-disabled), keeps A/V in sync, and supports surface +
ByteBuffer input modes. It is the path that produces every shipping deliverable file. Everything
"elite" in subsystem 6 either feeds this encoder (plan resolver, all-intra) or audits its output
(metadata rewriter, quality gate). This prompt is about **hardening** the encoder itself so it never
silently degrades the transport, never drops a frame, and never mislabels bit-depth.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema acquisition cameras (ARRI ALEXA 35 → ProRes 4444 XQ / ARRIRAW, RED → REDCODE RAW, Sony
VENICE 2 → X-OCN, Blackmagic URSA → Blackmagic RAW) record **intra-frame or visually-lossless
wavelet** codecs with deep bit-depth and 4:2:2/4:4:4 chroma — no Long-GOP inter-prediction in the
camera negative, because every frame must be an independent, gradeable master. Apple ProRes 422 HQ
sits at ~220 Mbps for 1080p25 and ProRes 4444 XQ above 500 Mbps; these set the bitrate-floor
expectation. The deliverable side (DCI/IMAX) demands 10/12-bit, accurate PQ/HLG transport metadata
(SMPTE ST 2086 mastering display, MaxCLL/MaxFALL), and **zero dropped frames**. Translated to a
phone HEVC encoder, the elite bar is: honest 10-bit Main10 transport when requested (no 8-bit
upscaled-to-10 fakery), a bitrate **floor** that tracks the HighEndQualityPolicy table (4K = 820
Mbps, 1080p = 340 Mbps), frame-drop count = 0 under sustained record, and color VUI/SEI that
matches the actual pixels (handed to s6-f07). Long-GOP is acceptable for the consumer deliverable,
but all-intra (s6-f03) is the mastering target.

## 3. Current state in NeoProCamX (verified)
- `RecordingManager.kt` (4843 lines) — `MediaCodec`/`MediaCodecInfo`/`MediaCodecList` imported
  (`:8-10`); class doc confirms "GPU-backed video capture through `MediaCodec`" (`:64`); video
  draining handled by MediaCodec, audio on a dedicated IO thread (`:73`).
- Default codec `videoCodec = GpuVideoCodec.HEVC` (`:612`); `videoCodecObj: MediaCodec?` (`:695`),
  `audioCodecObj` (`:696`). AAC LC audio profile (`:285`).
- All-intra hook: doc at `:654` describes every-frame-I-frame mode, hardware-honoured.
- Adaptive bitrate from dequeued buffers surfaced to UI (`:662`).
- Persistent input surface pre-arm: `MediaCodec.createPersistentInputSurface()` (`:849`), gated by
  `persistentInputSurfaceAllowed`/`persistentInputSurfaceRejected` (`:733-735, 840-903`) — see
  s6-f10.
- MOV-only / MP4-disabled and Main/Main10 selection are real (catalog §6, feat 8.5/quality 8.5).
- **Status: genuinely live and high-quality.** The honesty risk is not that it's dormant — it's
  that bitrate/profile/level decisions live in s6-f02 and a wrong fallback there could silently
  ship an 8-bit or under-bitrate file. This prompt hardens the encoder's *own* guarantees and the
  read-back proof that what was requested is what was muxed.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Honest bit-depth transport:** when Main10 is requested, the muxed track's actual profile is
   Main10 (HEVCProfileMain10), not Main upscaled. Provable by parsing the muxed SPS/format
   (`KEY_PROFILE`) back after start; mislabel ⇒ `NOT_PROVEN` through `OmegaCapabilityGate`, never a
   synthesized pass.
2. **Bitrate floor enforced:** configured `KEY_BIT_RATE` ≥ the HighEndQualityPolicy floor for the
   resolution/fps (4K ≥ 820 Mbps, 1080p ≥ 340 Mbps), or an explicit honest-fallback record when the
   encoder caps below floor (no silent clamp masquerading as elite).
3. **Zero dropped frames:** sustained record produces output-frame-count == input-frame-count
   (within the muxer's documented final-flush); frame-drop counter == 0. Measurable on device.
4. **MOV-only invariant:** muxer container is always QuickTime; an attempt to select MP4 fails
   closed (test asserts MP4 path is unreachable).
5. **A/V sync bound:** audio/video PTS drift stays within ±1 frame over a 10-min soak.
6. **Color metadata correctness:** the track carries the VUI primaries/transfer/matrix matching the
   active ODT (handed to s6-f07); no Rec.709-tagged HLG file.
7. **No-downgrade default:** all hardening is assertion/read-back/gate-only — today's encoder
   configuration and output bytes are unchanged unless a fallback was already silently wrong, in
   which case the change is to FAIL honestly, not to alter a good path.
8. **Budget:** encoder drain stays off the cold-start path; no added per-frame CPU on the GPU-surface
   path (read-back audit runs once at start / on stop, not per frame).

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the live HEVC encoder's transport-truth guarantees (bit-depth, bitrate floor,
frame-drop=0, MOV-only, A/V sync) with read-back proofs and honest fallbacks. **Non-goals:** do not
change the GPU encoder-surface render (parity is owned elsewhere); do not re-enable MP4; do not
touch the plan-resolver math (s6-f02) beyond consuming it; do not add a model/library.

**Plan:**
1. **Golden-lock first.** Add a characterization test capturing today's `MediaFormat` for a
   representative set (1080p/4K, Main/Main10, CBR/VBR/CQ) so any configuration change is visible.
2. **Read-back proof at start.** After `MediaCodec.configure`+`start`, read the *output* format and
   assert profile/level/bitrate/color keys match the request; surface a structured
   `EncoderTransportEvidence` (consumed by s6-f09). On mismatch, mark `NOT_PROVEN` — never coerce.
3. **Bitrate-floor guard.** Compare configured bitrate against the HighEndQualityPolicy floor; if
   the encoder caps below, record an honest "below-floor" evidence flag rather than claiming elite.
4. **Frame-drop accounting.** Instrument input vs muxed frame counts; expose a counter; assert == 0
   in the device soak. No behavioral change to drain — accounting only.
5. **MOV-only fail-closed test.** Assert the muxer is constructed QuickTime-only and the MP4 branch
   is unreachable.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt` (read-back
audit, counters), a new `EncoderTransportEvidence` data holder (or extend the existing evidence
struct consumed by `RecordingQualityEvidencePolicy`).

**ABI:** none (no GPU push-constants touched). If new evidence keys are added, they are string-map
keys consumed by the gate, not byte-layout fields.

**Gating:** purely additive audit. Default path bytes unchanged; the only new *behavior* is honest
FAIL/`NOT_PROVEN` where a fallback was previously silent.

## 6. Acceptance & verification
- **Unit/golden (JVM):** MediaFormat characterization test green; bitrate-floor mapping test;
  MOV-only fail-closed test; profile-mismatch ⇒ NOT_PROVEN test.
- **Gate keys:** evidence feeds `RecordingQualityEvidencePolicy` (s6-f09) — bit-depth-truth,
  bitrate-floor, parity signatures; capability proven via `OmegaCapabilityGate` from real transport.
- **Device (vivo I2405 / Adreno):** (1) record Main10 4K, parse track → profile == Main10, bitrate
  ≥ floor; (2) 10-min soak → frame-drop == 0, A/V drift ≤ 1 frame; (3) verify .mov container, no
  .mp4 ever produced.

## 7. Risks & no-downgrade guardrails
- **Silent 8→10 mislabel:** read-back profile assert + NOT_PROVEN gate → impossible to ship a faked
  Main10.
- **Under-bitrate "elite":** floor guard records honest below-floor flag → no false elite claim.
- **Frame drops under thermal:** accounting + soak gate; drain unchanged, governor (s8/CP91) handles
  load. No new per-frame work to cause its own drops.
- **A/V desync:** PTS-drift assertion bounds it.
- **Container regression:** MOV-only fail-closed test makes MP4 unreachable by construction.

## 8. References
- Roadmap: Phase 7 (CP70-73) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/14-imax-strict-quality-profile.md`](../technology_explanations/markdown/14-imax-strict-quality-profile.md), [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md)
- Reference: Apple ProRes bitrate tiers; DCI/IMAX 10/12-bit deliverable specs; SMPTE ST 2086 / MaxCLL/MaxFALL.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt:8, 64, 285, 612, 654, 695, 849`
- Related prompts: s6-f02, s6-f03, s6-f07, s6-f09, s6-f10
