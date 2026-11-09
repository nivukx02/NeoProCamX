# VulkanColorScienceUniforms GPU Upload (52-float matrices) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f03 |
| **Maps to checkpoint(s)** | new — propose CP-C3 (ABI/parity hardening) |
| **Current working level** | 🟢 live-default-on (uploaded as preview + encoder color-science every render) |
| **Feature rating (potential)** | 8/10 now → 8/10 target (hardening) |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f01 (LUT), s2-f02 (engine params), s5-f0x (encoder parity / VulkanPreviewRecordingParityPolicy) |

## 1. What this feature is
`VulkanColorScienceUniforms.kt` packs the GPU color-science block — ACEScg↔Oklab `mat4`s, HDR
knee/shoulder/volume-compression terms, NeoGamut V1/V2-APEX mode, and measured skin/sky corrections —
into a **52-float** array (`FLOAT_COUNT = 52`, `VulkanColorScienceUniforms.kt:33`). The bridge uploads
it for **both** the preview and encoder surfaces every render, with a parity policy
(`VulkanColorScienceUniformPolicy.kt`, `VulkanPreviewRecordingParityPolicy.kt`) enforcing that what
the viewer sees equals what gets recorded. This is the std140 contract surface where Kotlin color
math becomes GPU constants.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro pipelines treat the **transform block as a versioned, byte-exact contract**:
- ACES CTL/CLF transforms are fully specified bit-layouts; a DI pipeline that drifts between the
  monitoring transform and the render transform is a defect — **monitor LUT == render LUT** is sacred
  (this is the "what you see is what you master" guarantee on an ARRI/RED/Sony set).
- Color matrices are carried at **full float precision** in a defined working space (ACEScg AP1),
  with the Oklab/CAM appearance transforms kept exact (Ottosson's published Oklab LMS `mat3`s).
- The reference bar is **parity + precision + stability**: preview, record, and the conform all use
  the identical transform; rounding is controlled; the matrices are the published canonical values
  (no transposition, no row/column swap), provable by inverting and getting identity.
Elite bar, measurable: (a) ACEScg↔Oklab matrices round-trip to identity within 1e-5; (b) the 52-float
byte layout matches the GLSL UBO std140 layout exactly (offset/stride asserted); (c) preview uniforms
== encoder uniforms under the parity policy (signature equality); (d) no NaN/Inf ever reaches the
block (sanitized before upload).

## 3. Current state in NeoProCamX (verified)
- `FLOAT_COUNT = 52` confirmed (`VulkanColorScienceUniforms.kt:33`). The bridge requires both
  `previewColorScience` and `encoderColorScience` to be exactly 52 floats
  (`VulkanColorScienceUniformPolicy.kt:394-398`). **Live, default-on, both surfaces.**
- Separate enhancement-uniform block exists at 44 floats (`ENHANCEMENT_UNIFORM_FLOAT_COUNT = 44`,
  `:304`) — distinct contract, not this one; do not conflate.
- Parity policy classes present: `VulkanColorScienceUniformPolicy.kt`,
  `VulkanPreviewRecordingParityPolicy.kt`. Uploaded every render (catalog §2 row 3).
- **Open hardening question:** is the 52-float order/typing locked by a `*PushConstantLayoutTest` /
  std140 manifest the way the Argus push constants are (CP02, `ArgusPushConstantLayoutTest`)? If not,
  this block is a contract surface without its guard — exactly the ABI risk §0.5 warns about.
- Matrices' canonical-ness (round-trip-to-identity) is not asserted by a test today.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Layout lock:** a `ColorScienceUniformLayoutTest` asserts the 52-float slot order + int/float
   typing matches the GLSL UBO field-for-field (the CP02 pattern, extended to this block). std140
   alignment/stride verified for every `mat4`/`vec4`.
2. **Matrix correctness:** ACEScg→Oklab→ACEScg round-trips to identity within 1e-5; the matrices equal
   the canonical published values (no transpose/swap) within 1e-5.
3. **Parity:** preview color-science array == encoder color-science array under the parity policy for a
   representative mode set (signature equality test).
4. **Sanitization:** any NaN/Inf in an input parameter is clamped/replaced before pack; a test feeds
   poisoned inputs and asserts a finite 52-float result.
5. **No-downgrade default:** the packed bytes for every shipped mode are byte-identical to today
   (golden-lock the 52-float vector per mode).
6. **Budget:** pack is O(1) per render, no allocation churn on the hot path (assert no per-frame
   array allocation beyond the reused buffer).

## 5. Implementation prompt (hand this to the agent)
**Scope:** lock the 52-float ABI + matrix correctness + parity + sanitization with tests; no behavior
change. **Non-goals:** do not change any matrix value or knee term; do not alter the 44-float
enhancement block; no new fields unless a dependent feature (s2-f06 23-float scene block) requires one
— and that is its own commit.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Per-mode characterization freezing the exact 52-float vector (preview +
   encoder) for every shipped profile — proves any later edit is intentional.
2. **Layout test** (§4.1): parse the GLSL UBO block (reuse `GpuPushConstantLayout`-style parsing) and
   assert slot order + std140 offsets vs the Kotlin packer.
3. **Matrix round-trip + canonical-value tests** (§4.2).
4. **Parity test** (§4.3) over modes; **sanitization test** (§4.4) with NaN/Inf inputs.
5. If a new field is ever added (e.g. by s2-f06), extend the layout test + re-pin the manifest hash in
   the SAME commit (§0.5).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniforms.kt`,
`VulkanColorScienceUniformPolicy.kt`, `VulkanPreviewRecordingParityPolicy.kt`; the matching GLSL UBO;
tests under `app/src/test/.../rendering/`.

**ABI:** this IS the ABI surface. No field add in this commit; the layout test becomes the guard.

**Gating:** pure hardening — zero behavior change; the golden-lock is the no-downgrade proof.

## 6. Acceptance & verification
- **JVM:** layout, matrix round-trip, parity, sanitization, and per-mode golden tests green.
- **ABI/manifest:** if any field moves later, `:app:externalNativeBuildDebug` green + manifest hash
  re-pinned in the same commit.
- **Device (vivo):** preview and recorded clip color-match (parity) on a ColorChecker; no NaN-induced
  black/garbage frame under extreme WB. Owner confirms.

## 7. Risks & no-downgrade guardrails
- **ABI drift (std140):** layout test makes any offset/typing mismatch a CI failure.
- **Transposed matrix:** round-trip-to-identity test catches row/column swaps.
- **Preview≠record:** parity test + policy enforce signature equality.
- **NaN black-out:** sanitization clamp before upload; poisoned-input test.

## 8. References
- Catalog §2 row 3: [`../FEATURE_CATALOG.md`](../FEATURE_CATALOG.md)
- Roadmap CP02 (push-constant ABI lock pattern): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Standard canon (ACES CLF, Oklab matrices, monitor==render parity): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniforms.kt:33`; `VulkanColorScienceUniformPolicy.kt:304, 394-398`
- Related prompts: s2-f01, s2-f02, s2-f06
