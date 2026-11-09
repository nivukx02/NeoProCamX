# HevcColorMetadataRewriter (SPS VUI + T.35 SEI) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f07 |
| **Maps to checkpoint(s)** | hardening (transport-metadata correctness for s6-f01/f05) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8.5/10 |
| **Risk · Size** | M · S |
| **Depends on** | s6-f01 (encoder), s6-f05 (export), s6-f09 (gate) |

## 1. What this feature is
`HevcColorMetadataRewriter` operates on the HEVC bitstream to make its color signaling truthful: it
rewrites the SPS **VUI** color description (colour_primaries / transfer_characteristics /
matrix_coefficients) so the muxed track declares the actual gamut/transfer, and it builds + injects
an ITU-T **T.35** prefix-SEI (user-registered) carrying extended/HDR metadata. It handles Annex-B
NAL splitting and the RBSP↔EBSP emulation-prevention-byte round-trip correctly. This is what stops a
PQ/HLG file being tagged Rec.709 — the difference between a player rendering HDR correctly or
crushing it.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Correct color signaling is mandatory in professional deliverables. HDR masters carry the SMPTE ST
2084 (PQ) or BT.2100 (HLG) transfer, BT.2020 primaries, the SMPTE ST 2086 mastering-display
metadata, and MaxCLL/MaxFALL content-light-level metadata — and Dolby Vision adds RPU metadata via
ITU-T T.35 SEI. A DCDM (Digital Cinema Distribution Master) is XYZ with explicit gamma. The whole
point is that the *signaling matches the pixels* so any conformant decoder reconstructs the intended
image. The elite bar for the rewriter: VUI fields must exactly match the active ODT (a PQ/BT.2020
HDR clip is tagged transfer=16/primaries=9/matrix=9, never 709), the T.35 SEI must be byte-correct
(valid emulation-prevention, parseable by a reference decoder), and the metadata must round-trip
(write→parse→identical). Wrong metadata is a silent, ship-breaking lie about the image.

## 3. Current state in NeoProCamX (verified)
- `HevcColorMetadataRewriter.kt` (531 lines) — `object`; VUI struct fields
  `transferCharacteristics` / `matrixCoefficients` (+ primaries) (`:8-9`).
- Correct HEVC NAL handling: `NAL_TYPE_SPS = 33` (`:13`), `NAL_TYPE_PREFIX_SEI = 39` (`:14`),
  `SEI_PAYLOAD_TYPE_USER_REGISTERED_ITU_T_T35 = 4` (`:15`).
- `rewriteAnnexBColorMetadata(...)` splits Annex-B, rewrites SPS VUI (`:17-28`);
  `firstAnnexBSpsColorMetadata(...)` parses back the SPS color metadata (`:41-43`) — the round-trip
  read path.
- T.35 SEI build: `require(t35Payload.isNotEmpty())` (`:48`), writes the SEI payload-type field
  (`:50`), prepends the prefix-SEI NAL header `(NAL_TYPE_PREFIX_SEI shl 1)` and runs `rbspToEbsp`
  emulation-byte insertion (`:54`) — correct RBSP↔EBSP handling.
- SEI presence/count helpers (`:77-83`). This is genuinely correct, well-structured bitstream code
  (quality 8.5). Hardening targets: prove the VUI it writes *matches the active ODT* end-to-end (not
  just that the rewrite is well-formed), and exercise it against the live encoder/export outputs.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **VUI matches the active ODT:** for each color mode (SDR Rec.709, HLG, PQ/HDR10 BT.2020), the
   rewritten VUI primaries/transfer/matrix equal the spec values for that ODT (e.g. PQ ⇒
   transfer=16, primaries=9, matrix=9). Table-driven unit test per mode.
2. **Bit-exact round-trip:** `firstAnnexBSpsColorMetadata(rewriteAnnexBColorMetadata(x)) == intended`
   for a corpus of real SPS NALs; rewrite is idempotent. Test.
3. **Valid T.35 SEI:** built SEI parses as a well-formed user-registered T.35 prefix-SEI with correct
   payload size and emulation-prevention; RBSP↔EBSP is reversible (`ebspToRbsp(rbspToEbsp(x))==x`).
   Test on payloads containing `00 00 03` sequences.
4. **No corruption of other NALs:** rewriting touches only SPS/SEI; VPS/PPS/slice NALs pass through
   byte-identical. Test.
5. **End-to-end signaling truth:** the muxed track from `RecordingManager` (s6-f01) and the export
   from `Npv10MovConverter` (s6-f05) carry VUI/SEI matching the pixels; a decoder reads the intended
   transfer. Device check.
6. **No-downgrade default:** the rewriter only corrects metadata to match the *already-chosen* ODT;
   it never changes pixels. Existing SDR/HLG tagging is golden-locked unchanged.

## 5. Implementation prompt (hand this to the agent)
**Scope:** prove and lock that the VUI/SEI written matches the active ODT end-to-end, with a
round-trip + emulation-byte test corpus. **Non-goals:** do not change pixels; do not invent new HDR
metadata semantics; do not add a library.

**Plan:**
1. **Golden-lock first.** Pin today's rewritten VUI for the existing SDR/HLG modes on a real SPS NAL
   corpus.
2. **ODT-mapping table.** Encode the per-mode VUI mapping (SDR/HLG/PQ → primaries/transfer/matrix)
   and assert the rewriter produces it for each.
3. **Round-trip + idempotence tests.** rewrite→parse==intended; double-rewrite==single.
4. **Emulation-byte corpus.** Test RBSP↔EBSP reversibility on payloads with `00 00 03` runs; assert
   T.35 SEI parses as well-formed.
5. **Pass-through guard.** Assert non-SPS/SEI NALs are byte-identical after rewrite.
6. **End-to-end wiring check.** Confirm both the live encoder mux and the export apply the rewriter
   for HDR/HLG modes (consume, don't reimplement).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/HevcColorMetadataRewriter.kt`; its
callers in `RecordingManager.kt` (s6-f01) and `Npv10MovConverter.kt` (s6-f05).

**ABI:** none (bitstream byte manipulation; no GPU push-constants).

**Gating:** metadata-only; pixels untouched. Existing tagging golden-locked; the only new behavior is
*more correct* signaling where a mode was previously mistagged (which is a fix, not a downgrade).

## 6. Acceptance & verification
- **Unit/golden (JVM):** per-mode VUI-mapping test; rewrite→parse round-trip + idempotence;
  RBSP↔EBSP reversibility on `00 00 03` corpus; T.35 SEI well-formed test; non-SPS/SEI pass-through
  byte-identical test.
- **Gate keys:** VUI/SEI truth feeds the color-transport evidence in `RecordingQualityEvidencePolicy`
  (s6-f09); BT.2100/HDR transport proven via `OmegaCapabilityGate`.
- **Device (vivo):** record + export an HLG and a PQ clip → a reference parser reads transfer/
  primaries/matrix matching the mode; player renders HDR correctly (no 709-crush).

## 7. Risks & no-downgrade guardrails
- **Mistagged transfer (709-on-HDR):** per-mode VUI mapping table + end-to-end device check → caught.
- **SEI corruption:** RBSP↔EBSP reversibility corpus + well-formed-parse test → no malformed SEI.
- **Collateral NAL damage:** pass-through byte-identity test → only SPS/SEI touched.
- **Pixel change:** rewriter is metadata-only by construction; goldens lock existing tagging.

## 8. References
- Roadmap: Phase 7 encode-fidelity in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md)
- Reference: HEVC VUI (colour_primaries/transfer_characteristics/matrix_coefficients), SMPTE ST 2084 (PQ), BT.2100 (HLG), SMPTE ST 2086, MaxCLL/MaxFALL, ITU-T T.35 SEI, Dolby Vision RPU.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/HevcColorMetadataRewriter.kt:8, 13, 14, 15, 17, 41, 48, 54, 77`
- Related prompts: s6-f01, s6-f05, s6-f09
