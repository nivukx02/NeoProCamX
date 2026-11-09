# NPV10 Uncompressed Private Master (Npv10RecordingManager) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f04 |
| **Maps to checkpoint(s)** | hardening (the archival-master trust layer; feeds s6-f05 export, s6-f06 parity) |
| **Current working level** | 🔵 live-optional (flag defaults **OFF**) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8.5/10 |
| **Risk · Size** | M · L |
| **Depends on** | s6-f05 (MOV export), s6-f06 (parity), s6-f09 (gate), s1-f01 (RAW/NPRAW) |

## 1. What this feature is
`Npv10RecordingManager` writes the project-owned **NPV10** private master: a high-bit-depth,
effectively-uncompressed local container (P010 / planar YUV420P10 / packed RAW12/14 payloads) meant
to be the trustworthy archival negative the deliverable MOV is derived from. It runs a preflight
quality gate, enforces a **no-dropped-frame** strict policy (`FAIL_STRICT_MASTER` backpressure —
refuse to emit a master with a hole rather than silently drop), supports pause/resume, and emits
device-evidence + certificate sidecars. Because it's the master, its job is integrity, not
real-time grade. It currently ships **gated behind a flag that defaults OFF**.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The camera negative is the most-protected artifact in the pipeline. ARRIRAW, REDCODE RAW, X-OCN and
Blackmagic RAW are written with explicit per-clip metadata, checksums, and a hard guarantee that no
frame is silently dropped — a recorder that lost a frame would flag the take, not paper over it.
ProRes RAW and uncompressed DPX/EXR sequences carry exact bit-depth and a 1:1 relationship to sensor
readout. IMAX masters preserve every photon's worth of information for DMR remastering. The elite
bar for an archival master: **bit-exact** capture-to-disk (no lossy step the metadata doesn't
declare), **zero dropped frames or fail the take honestly**, explicit `payloadFormat` + bit-depth
evidence so an 8-bit fallback can never be mislabeled native-10-bit (the NPV10 truth boundary), a
content-addressed integrity audit (SHA-256), and enough metadata to reconstruct color exactly in
post. It does not need to be real-time-graded — it needs to be *true*.

## 3. Current state in NeoProCamX (verified)
- `Npv10RecordingManager.kt` (444 lines) — preflight via `Npv10QualityGate.preflight(...)` with a
  hard `require(preflight.auditStatus == Npv10AuditStatus.PASS)` before any write (`:68-74`).
- Strict no-drop policy: `Npv10FrameBackpressureAction.FAIL_STRICT_MASTER` (`:22, 28, 114`); on
  backlog overflow it increments `droppedFrames` and **throws** "refusing dropped-frame output"
  (`:117-123`) — honest fail, not silent drop.
- `droppedFrames` accounted (`AtomicInteger`, `:45`) and reported in the stop evidence (`:184-209,
  240, 298`).
- Capture source is `Npv10CaptureSourcePolicy` → `ImageFormat.YCBCR_P010` (10-bit) or
  `YUV_420_888` (`Npv10CaptureSourcePolicy.kt:112-118`), with `nativeTenBitCapture` and explicit
  `payloadFormat` truth flags (`:13, 31`) — this is the anti-mislabel keystone.
- Per tech doc 08: `MAGIC='NPV10'`, P010 sample convention `word16[15:6]=code10[9:0]`, separate
  payloadFormat + bit-depth evidence "to prevent false native-10-bit claims".
- **Honesty caveat (carried into s6-f06):** the NPV10 master snapshots **sensor/ISP YUV** off a
  dedicated ImageReader — it is *not* the GPU-processed look stack. So it is a faithful *sensor*
  master, but it is **not pixel-WYSIWYG with preview/record** by design. That parity problem is
  s6-f06's; here the master's own *integrity* is the target.
- Ships gated OFF (catalog §6: "gated behind a flag that defaults OFF").

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Bit-exact write:** the bytes written equal the captured payload for the declared
   `payloadFormat` (no undeclared transform); P010 packing matches `word16[15:6]=code10[9:0]`.
   Locked by a round-trip writer→reader test.
2. **Zero dropped frames or honest FAIL:** under backpressure the manager throws/fails the master
   (`FAIL_STRICT_MASTER`), never emits a master with a missing frame; `droppedFrames` in evidence is
   the truth. Test the overflow path raises.
3. **Bit-depth truth:** `nativeTenBitCapture` / `payloadFormat` reflect the *actual* source format;
   an 8-bit-expanded source is labeled `YUV420_8BIT_EXPANDED`, never P010-native. Test both.
4. **Preflight is blocking:** write cannot start unless `Npv10QualityGate.preflight` returns PASS
   (storage/bitrate/resolution). Test the require() blocks on a FAIL preflight.
5. **Integrity audit:** the master + sidecars carry a SHA-256 the export (s6-f05) re-verifies;
   tamper ⇒ audit FAIL. Test the hash round-trip.
6. **Evidence completeness:** stop emits a certificate sidecar with frames/dropped/payloadBytes/
   bit-depth/tier consumed by `RecordingQualityEvidencePolicy` (s6-f09).
7. **No-downgrade default:** flag stays **OFF** by default — zero change to the shipping deliverable
   path until the owner enables NPV10 on-device. When ON, the deliverable still comes from the live
   encoder; NPV10 is the *additional* master.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make the NPV10 master *provably* trustworthy — bit-exact, no-silent-drop, bit-depth-honest,
integrity-audited — without flipping its default on. **Non-goals:** do not solve pixel-WYSIWYG here
(that's s6-f06); do not change the deliverable encoder; do not add a library.

**Plan:**
1. **Golden-lock first.** Round-trip characterization: write a known multi-frame payload via
   `Npv10FileWriter`, read it back via `Npv10FileReader`, assert byte-identity per payloadFormat.
2. **No-drop proof.** Force backlog overflow in a test and assert `FAIL_STRICT_MASTER` raises and no
   partial master is published; assert `droppedFrames` is surfaced.
3. **Bit-depth honesty.** Drive `Npv10CaptureSourcePolicy.resolve` with P010-available vs only-YUV420
   inputs; assert `nativeTenBitCapture`/`payloadFormat` are truthful in each.
4. **Preflight blocking.** Assert a FAIL preflight blocks the write via the existing `require`.
5. **Integrity audit.** Verify the SHA-256 sidecar round-trips and a tampered frame fails the
   `Npv10IntegrityValidator` audit (shared with s6-f05).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt`,
`Npv10FileWriter.kt`, `Npv10FileReader.kt`, `Npv10IntegrityValidator.kt`, `Npv10QualityGate.kt`,
`Npv10CaptureSourcePolicy.kt`.

**ABI:** none (CPU/file path; no GPU push-constants).

**Gating:** the NPV10 enable flag stays OFF by default — no live deliverable change. All work is
proof/integrity hardening of the opt-in master.

## 6. Acceptance & verification
- **Unit/golden (JVM):** writer↔reader byte round-trip per payloadFormat; FAIL_STRICT overflow
  raises; bit-depth-truth resolve test; preflight-blocking test; SHA-256 tamper-detection test.
- **Gate keys:** master evidence (frames/dropped/bit-depth/payloadFormat/hash) feeds
  `RecordingQualityEvidencePolicy` (s6-f09); NPV10 native-10-bit truth proven via `OmegaCapabilityGate`.
- **Device (vivo):** enable NPV10 flag, record a clip → master file present, droppedFrames==0,
  integrity audit PASS, payloadFormat==P010_LE on a 10-bit-capable session; preview/record path
  unchanged with flag OFF.

## 7. Risks & no-downgrade guardrails
- **Silent dropped frame in the "master":** FAIL_STRICT_MASTER throw → impossible to publish a holed
  master.
- **8-bit mislabeled 10-bit:** explicit payloadFormat/nativeTenBitCapture truth flags + test → no
  false native-10-bit.
- **Corruption undetected:** SHA-256 audit on master + sidecars.
- **Storage exhaustion mid-take:** preflight `minimumFreeBytes` blocks start; backpressure fails
  honestly.
- **Deliverable regression:** flag OFF by default → zero change to shipping output.

## 8. References
- Roadmap: Phase 7/8 + CP04 (RAW12/14 enhance-bypass guard) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/08-npv10-master-container.md`](../technology_explanations/markdown/08-npv10-master-container.md), [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md)
- Reference: ARRIRAW / REDCODE RAW / X-OCN / ProRes RAW negative integrity; uncompressed DPX/EXR; content-addressed checksums.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt:22, 28, 45, 68, 114, 117, 184`; `Npv10CaptureSourcePolicy.kt:13, 112`
- Related prompts: s6-f05, s6-f06, s6-f09, s1-f01
