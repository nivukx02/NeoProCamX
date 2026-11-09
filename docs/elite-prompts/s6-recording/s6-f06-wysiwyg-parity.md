# WYSIWYG / Preview-Record-Export Parity — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f06 |
| **Maps to checkpoint(s)** | CP82 (tri-surface parity render-graph lock CI gate), dep CP80/CP81 |
| **Current working level** | 🟡 partial |
| **Feature rating (potential)** | 5.5/10 |
| **Code-quality rating (now)** | 7.5/10 |
| **Risk · Size** | H · XL |
| **Depends on** | s6-f05 (export), s6-f04 (NPV10 master), s6-f01 (encoder), s6-f08 (measurement harness), s6-f09 (gate) |

## 1. What this feature is
WYSIWYG parity is the guarantee that the three processing surfaces — **preview**, **recorded
deliverable**, and **NPV10-derived export** — produce the *same pixels* for the same scene and
settings. The recorded deliverable already shares the GPU encoder surface with preview (true
transport parity). The problem is the **NPV10 master + its export**: the master snapshots raw
sensor/ISP YUV that *bypasses the GPU look stack*, and the export re-applies the look on a
**separate CPU/native path**. So "parity" today is transport parity (same container/bit-depth/
metadata), **not** pixel parity (same actual colors). This prompt is the big theme: make it *true
pixel parity*, not just signature parity.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The professional contract is "what you see is what you get": the on-set monitor LUT/CDL, the
recorded image, and the conformed deliverable all reference the **same color transform**. ARRI's
LogC4→display path, RED's IPP2, Sony's MLUT-on-VENICE, and ACES (single canonical IDT→RRT→ODT)
exist precisely so the look is *computed once* and applied identically everywhere — the monitor is
trusted because the math is shared. A render in Resolve matches the timeline viewer because the
node graph is the same code. The elite bar: a **single source-of-truth look transform** evaluated by
preview, record, and export; pixel parity measured as ΔE2000 ≤ a tight tolerance on a ColorChecker
between any two surfaces; and a CI gate that *locks the render graph* so the three surfaces can never
silently diverge (CP82). Transport parity (already done) is necessary but not sufficient — IMAX-tier
means the negative and the monitor agree on every pixel.

## 3. Current state in NeoProCamX (verified)
- **Record == preview (transport + pixels):** the record path is the GPU encoder surface, parity
  with preview; export color transport is bound to the live ODT (catalog §6: "Record path = GPU
  encoder surface (parity with preview)").
- **NPV10 master breaks pixel parity by design:** `Npv10CaptureSourcePolicy.resolve` selects an
  `ImageFormat.YCBCR_P010` / `YUV_420_888` ImageReader source (`Npv10CaptureSourcePolicy.kt:112-118`)
  — i.e. **sensor/ISP YUV, NOT the GPU-processed look stack**. The master is a faithful *sensor*
  capture, not the previewed image (catalog §6: "NPV10 master = raw sensor YUV snapshot (NOT
  GPU-processed)").
- **Export re-applies look on a separate path:** `Npv10MovConverter` builds its own
  `snce.SnceLiveColorController()` session (`Npv10MovConverter.kt:334`) and runs export-side
  denoise/motion — a **parallel reimplementation** of the live GPU look, with synthetic motion
  (CP80) and non-unified denoise (CP81). So even after export, the pixels are a *re-derivation*, not
  the live result.
- **Net honesty statement:** today's "WYSIWYG" for NPV10 is **transport parity, not pixel parity**.
  The look applied to the export is computed by different code than preview/record, so it can drift.
  `previewRecordExportParity` is currently a signature/boolean evidence flag (see
  `EliteVisionMeasurementHarness.toTierOmegaMetadata` key `tierOmegaPreviewRecordExportParity`), not
  a measured ΔE2000.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Single source-of-truth look:** the look transform (ACEScg grade / NDR10 / NeoGamut / SNCE
   nudge) is defined once and consumed by preview, record, and export — export does **not** carry a
   second implementation. Provable by a contract test that the export look path references the live
   coefficients/kernel (CP81), not a fork.
2. **Measured pixel parity (not signature):** ΔE2000 between (a) a decoded record frame, (b) a
   decoded export frame, and (c) the preview output for the same synthetic input ≤ a tight tolerance
   on a ColorChecker; measured by `ImageQualityMetrics.colorCheckerDeltaE2000` via s6-f08, not a
   boolean. The `previewRecordExportParity` evidence becomes a *measured* value.
3. **NPV10 look-reconciliation:** the NPV10-master-derived export reproduces the live look within the
   same tolerance — either by carrying the look transform forward exactly, or by snapshotting enough
   state to re-apply it identically (no synthetic/forked math).
4. **Tri-surface render-graph lock (CP82):** a CI gate pins the preview/record/export render graph
   signatures; a change to any one without the others fails CI by construction.
5. **No-downgrade default:** record==preview parity (already shipping) is untouched; the new
   NPV10/export pixel-parity path is gated so that until device-validated, export bytes reproduce
   today's output (golden-locked). The flag default leaves the deliverable unchanged.
6. **Budget:** preview stays real-time; the parity work for NPV10/export is offline; the
   render-graph-lock CI gate is build-time only.

## 5. Implementation prompt (hand this to the agent)
**Scope:** turn NPV10/export "transport parity" into measured *pixel* parity, and lock all three
surfaces against silent divergence with a CI gate. **Non-goals:** do not change preview or the
record encoder surface (already in parity); do not add a library; do not solve the export
memcpy/perf (that's s6-f05/CP73).

**Plan (depends on CP80/CP81 landing first):**
1. **Golden-lock first.** Capture today's preview/record/export outputs for a synthetic ColorChecker
   input as committed goldens (deterministic, code-defined). This pins the no-downgrade baseline.
2. **Single-look contract.** Refactor so the look transform is one definition; export consumes the
   live coefficients/kernel (depends on CP81 denoise unification + CP80 motion). Add a contract test
   that export references the live source, not a fork.
3. **Measured-parity metric.** Add a tri-surface ΔE2000 measurement (via s6-f08
   `EliteVisionMeasurementHarness` + `ImageQualityMetrics`) and make `previewRecordExportParity` a
   *measured* gate value, not a boolean (s6-f09).
4. **NPV10 look reconciliation.** Carry the look transform/state forward so the master-derived
   export reproduces the live look within tolerance.
5. **CP82 — render-graph lock CI gate.** Pin preview/record/export render-graph signatures; CI fails
   if they diverge. Build-time gate, no runtime change.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt` (consume live
look), `Npv10CaptureSourcePolicy.kt` (look-state carry), `EliteVisionMeasurementHarness.kt` +
`RecordingQualityEvidencePolicy.kt` (measured parity), the shared look definition (ColorScience /
SNCE / nivukx), a new tri-surface parity CI test.

**ABI:** if the export look path is unified onto the live push-constants, extend
`GpuPushConstantLayout` + layout test and re-pin shader-manifest hashes in the same commit (CP81/82).

**Gating:** NPV10/export pixel-parity path behind a flag whose default reproduces today's export
bytes (golden-locked); record==preview path untouched; the CI gate is non-runtime.

## 6. Acceptance & verification
- **Unit/golden (JVM):** preview/record/export ColorChecker goldens locked; single-look contract
  test (export references live source, not fork); tri-surface ΔE2000 ≤ tolerance test;
  `previewRecordExportParity` is a measured value test; CP82 render-graph-signature lock test.
- **Gate keys:** `tierOmegaPreviewRecordExportParity` becomes measurement-backed in
  `RecordingQualityEvidencePolicy` (s6-f09); honest `NOT_PROVEN` when parity can't be measured from
  real pixels (`OmegaCapabilityGate`).
- **Device (vivo):** shoot a ColorChecker → decode record + export, compare to preview readback;
  ΔE2000 within tolerance; flag OFF leaves deliverable unchanged.

## 7. Risks & no-downgrade guardrails
- **Look drift between surfaces:** single source-of-truth + export-references-live contract +
  measured ΔE2000 gate → divergence is caught, not assumed-away.
- **Faked parity (boolean PASS):** parity becomes a measured value; unmeasurable ⇒ NOT_PROVEN, never
  synthesized.
- **Silent render-graph divergence:** CP82 CI lock fails the build on drift.
- **Deliverable regression:** record==preview untouched; NPV10/export path gated + golden-locked.

## 8. References
- Roadmap: CP80/CP81/CP82 (Phase 8) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/15-elite-video-quality-contract.md`](../technology_explanations/markdown/15-elite-video-quality-contract.md), [`../technology_explanations/markdown/08-npv10-master-container.md`](../technology_explanations/markdown/08-npv10-master-container.md)
- Reference: ACES single-transform IDT→RRT→ODT; ARRI LogC4 monitor path; RED IPP2; on-set MLUT/CDL = render parity.
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10CaptureSourcePolicy.kt:112`; `Npv10MovConverter.kt:334`
- Related prompts: s6-f04, s6-f05, s6-f08, s6-f09, s1-f09 (sensor→ACEScg)
