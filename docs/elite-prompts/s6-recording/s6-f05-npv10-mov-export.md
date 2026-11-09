# NPV10 → MOV CPU/P010 Export (Npv10MovConverter) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f05 |
| **Maps to checkpoint(s)** | CP72 (CPU P010 encode fidelity), CP73 (Vulkan 1.1 + dedicated transfer queue, remove per-frame memcpy), CP80 (real motion), CP81 (unify denoise math) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6.5/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | H · L |
| **Depends on** | s6-f04 (NPV10 master), s6-f06 (parity), s3-f01/f02/f03 (Argus/Chronos/Riven), s4 (nivukx denoise), s6-f07 (metadata) |

## 1. What this feature is
`Npv10MovConverter` turns the NPV10 private master into a deliverable MOV: a triple-buffered
reader→enhance→writer pipeline that repacks NPV10 P10 to P010, applies CPU/native export
enhancement (its own SNCE nudge session, denoise, motion), writes a QuickTime-tagged MOV with a
focus-map sidecar + T.35 SEI, and runs a SHA-256 immutability audit. It is the path that makes the
opt-in master usable. Its elite gaps are real-pixel-fidelity and performance: it copies every frame,
runs a *separate* enhancement math from the live GPU kernel, and uses synthetic motion.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional transcode/conform (DaVinci Resolve render, ARRI/RED IPP2 export, Baselight) treats the
master→deliverable step as **bit-disciplined**: 10/12-bit is preserved end-to-end with no hidden
8-bit hop, the debayer/denoise/grade math used in the conform is the *same* math as the monitoring
path (so the render matches what the colorist saw — true round-trip), and transport metadata (PQ/HLG
transfer, BT.2020 primaries, SMPTE ST 2086 mastering display, MaxCLL/MaxFALL, T.35 SEI) is written
to spec. Performance-wise, a render farm uses GPU with zero-copy DMA — no per-frame host memcpy. The
elite bar here: P010 export with a QP/CQ floor that keeps ΔE2000 transport parity within tolerance
of the master, **identical** enhancement math to the live kernel (no parallel reimplementation —
CP81), **real** motion (Argus/Chronos/Riven) instead of synthetic flow (CP80), a dedicated Vulkan
transfer queue with **zero per-frame memcpy** (CP73), and a content-addressed immutability audit.

## 3. Current state in NeoProCamX (verified)
- `Npv10MovConverter.kt` (2384 lines) — `validateMovConvertible(header)` guards RAW12/14 before the
  frame loop (`:73, 486`); `payloadForMovSample(header, ...)` re-guards + repacks (`:312, 519`).
- Triple-buffered coroutine pipeline: reader / gpu(enhance) / writer dispatchers (`:300-330`).
- **Per-frame copy (CP73 target):** the reader does `directBuffer.put(sourcePayload)` for every
  frame (`:312-322`) — a host memcpy into a pooled direct buffer per frame, the exact overhead CP73
  removes (Vulkan 1.1 + dedicated transfer queue, zero-copy).
- **Parallel enhancement math (CP81/CP82 target):** the export builds its **own**
  `com.neoproc.neoproccamx.snce.SnceLiveColorController()` session (`:334`) and runs export-side
  denoise/motion separate from the live GPU `nivukx_denoise.comp` kernel — a *reimplementation*,
  not the live math. This is the WYSIWYG break that s6-f06 owns and CP81 unifies.
- **Synthetic motion (CP80 target):** export motion uses synthetic flow, not the live Argus/Riven/
  Chronos shaders (roadmap CP80: "real Argus/Riven/Chronos instead of synthetic flow").
- Native Vulkan accelerator (`Npv10ExportGpuAccelerator`) with CPU fallback; QuickTime color-atom
  tagging; focus-map sidecar + T.35 SEI; SHA-256 immutability audit (catalog §6).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No per-frame host memcpy (CP73):** export reads NPV10 frames into Vulkan-mappable / imported
   memory on a dedicated transfer queue; the `directBuffer.put` per-frame copy is eliminated.
   Provable by a profile showing zero per-frame host copy on the accelerated path + a frame-time
   budget check.
2. **P010 encode fidelity (CP72):** the encoder uses a QP floor / CQ fallback so the exported MOV's
   decoded luma matches the master within a bounded ΔE2000 and a read-back audit; no silent
   low-bitrate degrade. Test the QP-floor / CQ-fallback decision.
3. **ΔE2000 transport parity:** a decoded export frame vs the NPV10 master frame (same look applied)
   stays within a fixed ΔE2000 tolerance on a ColorChecker; measured by `ImageQualityMetrics`.
4. **Unified denoise math (CP81):** export denoise produces the same result as the live
   `nivukx_denoise.comp` kernel for the same input within tolerance (shared coefficients / shader),
   not a separate CPU reimplementation. Golden test pins export-vs-live output.
5. **Real motion (CP80):** export motion uses the live Argus flow + Chronos warp + Riven deblur, not
   synthetic flow. (Heavy/XL — foundation here, device-gated activation.)
6. **Bit-depth + RAW guard intact:** RAW12/14 still blocked before any enhance (the
   `validateMovConvertible` + `payloadForMovSample` double guard stays); P010 stays 10-bit through.
7. **Immutability audit:** SHA-256 of the master is verified before export and the deliverable's
   provenance is recorded; tamper ⇒ FAIL.
8. **No-downgrade default:** existing export output is golden-locked; the memcpy-removal,
   denoise-unification, and real-motion paths are added behind gates whose default reproduces today's
   export bytes until device-validated. Budget: export is offline/background, but per-frame time must
   not regress.

## 5. Implementation prompt (hand this to the agent)
**Scope:** raise export to real-pixel + performance parity — remove per-frame memcpy (CP73), unify
denoise with the live kernel (CP81), feed real motion (CP80), harden P010 fidelity (CP72) — each
behind a no-downgrade gate. **Non-goals:** do not change the deliverable encoder (s6-f01); do not
remove the RAW12/14 guard; do not add a library.

**Plan (golden-lock → gate → device-note, one CP per commit):**
1. **Golden-lock first.** Pin today's export output for a known NPV10 master (decoded-frame hash /
   ΔE2000 vs master) so every change is proven no-downgrade against it.
2. **CP72 — P010 fidelity.** Add QP-floor / CQ-fallback to the export encoder; add a read-back audit
   (decode the MOV, compare to master). Test the rate-control decision + parity tolerance.
3. **CP73 — kill per-frame memcpy.** Route frames through the Vulkan transfer queue (Vulkan 1.1,
   dedicated queue, imported/mappable memory), removing `directBuffer.put` per frame on the
   accelerated path. Native build green; CPU fallback unchanged; gate the new path.
4. **CP81 — unify denoise.** Replace the export's separate denoise with the live nivukx kernel
   coefficients/shader; golden test export-vs-live equality within tolerance.
5. **CP80 — real motion (foundation).** Wire Argus flow / Chronos warp / Riven deblur into export in
   place of synthetic flow; device-gated, foundation + tests only.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`,
`Npv10ExportGpuAccelerator.kt`, `Npv10FramePacker.kt`, `Npv10IntegrityValidator.kt`; the native
Vulkan export path (`app/src/main/cpp/...`); shared nivukx kernel; Argus/Chronos/Riven shaders.

**ABI:** if export push-constants change to match the live nivukx/motion kernels, extend
`GpuPushConstantLayout` + the relevant layout test in the SAME commit and re-pin shader-manifest
hashes (CP81/CP80).

**Gating:** each new path (transfer-queue, unified-denoise, real-motion) behind a flag/spec-constant
whose default reproduces today's export bytes (golden-locked) until device-validated.

## 6. Acceptance & verification
- **Unit/golden (JVM):** export-output characterization lock; QP-floor/CQ-fallback decision test;
  RAW12/14-guard still raises; export-vs-live denoise equality golden; ΔE2000 transport-parity test.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on the export transfer
  queue.
- **Gate keys:** export evidence (bit-depth, parity signature, immutability hash) feeds
  `RecordingQualityEvidencePolicy` (s6-f09); zero-copy/GPU claims proven via `OmegaCapabilityGate`,
  never synthesized.
- **Device (vivo):** export an NPV10 master → (1) decoded MOV ΔE2000 vs master within tolerance;
  (2) profiler shows no per-frame host memcpy on accelerated path, frame-time not regressed;
  (3) integrity audit PASS; (4) CPU fallback still correct.

## 7. Risks & no-downgrade guardrails
- **Bit-depth loss in transcode:** QP floor + P010 read-back parity audit → bounded, measured.
- **Parallel-math drift (the core WYSIWYG sin):** CP81 unifies on the live kernel + export-vs-live
  golden → no divergence by construction.
- **Synthetic-motion artifacts:** CP80 real motion, device-gated, golden-locked default.
- **Transfer-queue UB / black frame:** validation-layer clean + CPU fallback retained + gated default.
- **RAW mislabel:** double guard (`validateMovConvertible` + `payloadForMovSample`) stays.
- **Export-time blow-up:** background/offline; per-frame budget asserted not to regress.

## 8. References
- Roadmap: CP72/CP73 (Phase 7), CP80/CP81/CP82 (Phase 8), CP04 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/08-npv10-master-container.md`](../technology_explanations/markdown/08-npv10-master-container.md), [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md)
- Reference: Resolve/IPP2/Baselight master→deliverable conform; zero-copy GPU DMA; PQ/HLG + ST 2086 + T.35 SEI transport.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt:73, 300, 312, 334, 486, 519`
- Related prompts: s6-f04, s6-f06, s6-f07, s6-f09, s3-f01, s3-f02, s3-f03
