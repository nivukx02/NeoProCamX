# Post-Video-Value Roadmap (s10) — Beyond ₹10,000

> **What this is.** The roadmap that follows the s9 video-quality program. s9
> ([`VIDEO_QUALITY_UPGRADE_PROMPT_WORTH_10K.md`](../../../VIDEO_QUALITY_UPGRADE_PROMPT_WORTH_10K.md)) makes
> the *footage* worth ₹10,000. This roadmap covers everything that **multiplies that value into a real,
> sellable, trustworthy product** and then raises the ceiling further.
>
> **Inherits the global contract** in
> [`docs/elite-prompts/_PROMPT_STANDARD.md`](../../elite-prompts/_PROMPT_STANDARD.md) in full — no
> downgrade, no-vendor, commit-per-feature, golden-first, ABI-sacred, honest-gates, device-gated ≠ skipped,
> no-lag. Where a habit conflicts with the standing order, the standing order wins.
>
> **Companion truth docs:** [`PARTIAL_AND_NONWORKING_FEATURES_REPORT.md`](../../../PARTIAL_AND_NONWORKING_FEATURES_REPORT.md) ·
> [`FEATURE_CATALOG.md`](../FEATURE_CATALOG.md) · [`PROJECT_ANALYSIS.md`](../PROJECT_ANALYSIS.md) ·
> beyond-IMAX plan: [`2026-06-25-beyond-imax-upgrade-program.md`](./2026-06-25-beyond-imax-upgrade-program.md).

---

## Strategy — the value ladder

Value = **(what a buyer sees) × (how many devices it works on) × (how much they trust it)**. s9 maximizes
the first factor. This roadmap maximizes the other two, then raises the ceiling. Order matters: **breadth
and trust before the dormant elite layer** — a beautiful feature nobody can run on their phone, or that
crashes, is worth nothing.

| Phase | Lever | Value effect | Price band it unlocks |
|---|---|---|---|
| **0** Execute s9 | Visible video quality | Baseline → premium footage | ₹10,000 (footage alone) |
| **A** Broaden devices | Addressable market ×N | Multiplies buyers | market value ×N |
| **B** Reliability & trust | Crash-free + honest | Protects the price | prevents refunds/1-stars |
| **C** Beyond-IMAX ceiling | Dormant elite → real | Raises the spec ceiling | halo / top tier |
| **D** Productization | GTM + licensing | Converts value → revenue | actually captured price |

---

## Status legend
`[ ]` todo · `[~]` in progress · `[x]` done+committed · risk L/M/H · size S/M/L/XL · `gated` = device-hardware-gated.
Checkpoint IDs `CP-S10-*` are **proposals**, not reserved — confirm before use.

---

## Phase 0 — Execute s9 (PREREQUISITE — do this first, do not skip to A)

A plan doesn't raise the price; a shipped + on-device-proven feature does. Run s9 in tier order and **bank
each gate with a saved artifact** before moving on.

- [ ] **CP-S10-00a** Execute s9 **Tier 1** — F01 throughput/stutter → F02 stabilization → F03 color/HDR
  default. Prove G1/G2/G3 on the vivo I2405, commit per feature. risk H · size L (this is the honest ₹5k floor).
- [ ] **CP-S10-00b** Execute s9 **Tier 2** — F04 depth/bokeh, F05 detail, F07 editor round-trip. Prove
  G4/G5/G7. risk H · size L (reaches ₹7–10k).
- [ ] **CP-S10-00c** Execute s9 **Tier 3** — F06 audio + F08–F14 value features. Prove G6 + upside. risk M · size L.
- [ ] **CP-S10-00d** Record the **demo reel** (60–90 s, one take, side-by-side vs stock camera). This is
  THE asset that argues the price. risk L · size S.

**Phase-0 done when:** s9 §3 release checklist (G1–G8) all green with artifacts + demo reel exists.

---

## Phase A — Broaden device support (biggest market-value multiplier)

Today the app is hard-tuned to the **vivo I2405**. A pro camera that only shines on one phone has a tiny
buyer pool. The architecture already has capability gates — generalize them.

- [ ] **CP-S10-A1** **Device capability matrix + probe harness.** A `DeviceProfile` registry that, at
  runtime, enumerates Camera2 caps (HW level, 10-bit/HLG/HDR10 dynamic-range profiles, RAW, encoder
  levels, max fps per resolution, AF regions, OIS) into a single source of truth. Pure-Kotlin, unit-tested
  against synthetic characteristics. risk M · size L.
- [ ] **CP-S10-A2** **Graceful per-device fallback.** Extend the existing capability gates so every
  unsupported mode (no 10-bit, no HDR10, no RAW, lower encoder level) resolves to an **honest downgrade
  path** — never a crash, never a silent fake. Reuse `OmegaCapabilityGate` verdicts. risk M · size L · dep A1.
- [ ] **CP-S10-A3** **`DeviceQuirks` layer.** Move the vivo-I2405-specific hacks (offscreen-EGL hard wall,
  P010 copy path, logcat suppression, 10-bit EGL unbindable — see report §5) behind a keyed quirks table so
  other devices don't inherit them and new quirks are additive. risk H · size L · dep A1.
- [ ] **CP-S10-A4** **Adaptive quality auto-selection.** At session start, pick the best mode the device
  actually *sustains* (proven from a short probe, not the spec sheet) and label it. Ties into s9-F01
  throughput telemetry. risk M · size M · dep A1, F01.
- [ ] **CP-S10-A5** **Reference-device test matrix.** Smoke coverage (launch → preview → record → save,
  no crash) on 3–5 tiers: a Pixel (clean Camera2), a Samsung (Exynos/Snapdragon quirks), a mid-range, plus
  vivo. Physical or Firebase Test Lab. risk L · size M · dep A2.
- [ ] **CP-S10-A6** **Per-device diagnostics (opt-in).** Extend the runtime health-monitor package
  (already in tree) with a device-capability report the owner can pull. risk L · size M · dep A1.

**Phase A done when:** the app launches, previews, records and saves **without crash on ≥ 4 device tiers**,
each honestly reporting its real capability set, with an auto-selected sustained mode.

---

## Phase B — Reliability & trust (protects the price)

Reviewers punish a crash harder than they reward a feature. A ₹10,000 app that force-closes is a refund.

- [ ] **CP-S10-B1** **Re-triage `BUG_REPORT.md`.** That doc is a stale static audit (only A1 marked fixed).
  Verify each item (A1–A3 races, B1 23 empty catches, B2–B4 leaks, C1 per-frame hashCode, D1–D3 logic, E1–E3
  native, F deprecations) against current source → close / fix / keep-with-repro. Produces a live bug ledger.
  risk L · size M.
- [ ] **CP-S10-B2** **Thread-safety hardening.** Fix the confirmed races (session-state volatility,
  config-generation token, AICameraPipeline torn reads) with concurrency/characterization tests that fail
  before, pass after. risk M · size M · dep B1.
- [ ] **CP-S10-B3** **Resource-leak audit.** Empty-catch elimination (log at minimum), `ImageReader` /
  `MediaCodec` surface / native Vulkan resource close-on-failure, with leak tests (allocate/free assertions).
  risk M · size M · dep B1.
- [ ] **CP-S10-B4** **Decompose `CameraViewModel`** (~10,183 lines / 102 StateFlows / 452 funcs). Continue
  the proven pattern: extract cohesive pure `*Policy` classes + group StateFlows into feature bundles; the
  VM becomes an orchestrator. Golden/characterization tests keep behavior identical. risk M · size XL.
- [ ] **CP-S10-B5** **Decompose `MainActivity`** (~3,191 lines) into feature controllers behind the same
  seams. risk M · size L · dep B4.
- [ ] **CP-S10-B6** **Soak + crash-free instrumentation.** A long-record soak (≥ 30 min continuous +
  rapid mode-switch loop) with memory-growth + no-crash assertions; opt-in crash telemetry. risk L · size M.

**Phase B done when:** confirmed races/leaks fixed with tests, soak passes with bounded memory, the two god
objects are materially smaller with the full suite still green, and `BUG_REPORT.md` is a live ledger.

---

## Phase C — Beyond-IMAX ceiling (turn the dormant elite layer real)

This is the device-gated wiring marathon already scoped in
[`2026-06-25-beyond-imax-upgrade-program.md`](./2026-06-25-beyond-imax-upgrade-program.md). It makes the
"10/10 spec sheet" **real** — but it's highest-effort / lowest-immediate-visible-return, so it comes **after**
breadth and trust. Each item raises a real metric on device, behind honest gates, no downgrade.

- [ ] **CP-S10-C1** **MFSR real N-frame ring** (beyond-IMAX CP12 STEP B/C/D + CP13). Replace the cleared-
  dummy bindings with a real RAW frame ring + per-frame-indexed flow (`sampler2DArray`) + JNI noise/gyro
  plumbing. UB already killed (STEP A landed); this is the visible-fusion device-gated remainder. risk H ·
  size XL · `gated`.
- [ ] **CP-S10-C2** **Multi-exposure HDR fusion activation** (CP31 dual-gain + CP32 photon). Build the
  compiled fusion shaders into real pipelines fed by a real LCG/HCG ring. risk H · size XL · `gated`.
- [ ] **CP-S10-C3** **Full SNCE perceptual pipeline to pixels** (CP41–CP4A). Beyond s9-F03's bounded
  widening: upload the 23-float scene-brain, scene-driven OCCS, tetrahedral NRLI, memory-color protection,
  semantic-region constancy — each bounded + hysteretic + golden-locked. risk H · size XL.
- [ ] **CP-S10-C4** **Live cinema-debayer / demosaic feed-through** (CP10c + CP14). The demosaic pipeline
  is dispatched-but-gated (s1-f08); wire the RAW R16_UINT import + AHD hard-gradient green + WB-before-
  demosaic ordering and validate on device. risk H · size XL · `gated`.
- [ ] **CP-S10-C5** **Advanced stabilization** — build on s9-F02 with trajectory optimization + live
  OIS/EIS fusion + rolling-shutter rectification tuned per device. risk H · size XL · `gated`.
- [ ] **CP-S10-C6** **Close the measurement→gate loop.** Give `EliteVisionMeasurementHarness` a production
  caller so the live Tier-Omega gate receives **real measured pixels** (MTF50/dE2000/flicker), not
  synthesized results. Turns the anti-fraud gate into a true quality certificate. risk M · size L.

**Phase C done when:** each activated path improves its target metric on device vs the s9 baseline, every
gate is honest (`NOT_PROVEN` when unproven), and no live output regressed.

---

## Phase D — Productization / go-to-market (convert value → captured revenue)

- [ ] **CP-S10-D1** **Honest capability badge + "Verified" screen.** Surface s9-F14: an in-app
  "Verified: 10-bit HEVC · HDR10 · 48 kHz · Gyro-EIS" badge derived ONLY from proven `OmegaCapabilityGate`
  results — the differentiator that says "we don't fake specs." risk L · size S · dep F14.
- [ ] **CP-S10-D2** **Store-listing assets.** The demo reel (Phase-0 CP-S10-00d), screenshots, and a
  feature list **mapped to real working-levels** (no marketing claim exceeds what the gate proves). risk L · size M.
- [ ] **CP-S10-D3** **Licensing / pricing tiers.** Free-trial → paid; feature-gate by license entitlement
  (never by faking capability). A "Pro/Master" tier maps to the all-intra master (F12) + cinematic bundle
  (F10). risk M · size M.
- [ ] **CP-S10-D4** **First-run onboarding.** Lead with the one-tap Cinematic bundle (F10) + look presets
  (F09) so the first 30 seconds are the "wow". risk L · size M.
- [ ] **CP-S10-D5** **Permissions / privacy / compliance pass.** Camera/mic/storage rationale, data honesty
  (no cloud, no vendor calls — a genuine selling point here), store policy compliance. risk L · size M.

**Phase D done when:** there is a shippable store build, a demo reel, honest marketing that matches
working-levels, and a working trial→paid funnel.

---

## Execution order (one line)

> **Phase 0 (execute s9, prove on device) → A (breadth) → B (trust) → D1–D2 (badge + listing to start
> selling) → C (raise the ceiling) → D3–D5 (monetize fully).**

Phases A and B can run in parallel with each other. Phase C is the long tail — start it only once the app
is broad and stable, because elite math on a crashing, single-device app captures no value. Every checkpoint
is gated, tested, committed, and reversible — **no downgrade, ever.**

---

## References
- s9 video-quality prompt: [`VIDEO_QUALITY_UPGRADE_PROMPT_WORTH_10K.md`](../../../VIDEO_QUALITY_UPGRADE_PROMPT_WORTH_10K.md)
- Global contract: [`_PROMPT_STANDARD.md`](../../elite-prompts/_PROMPT_STANDARD.md)
- Current-state truth: [`PARTIAL_AND_NONWORKING_FEATURES_REPORT.md`](../../../PARTIAL_AND_NONWORKING_FEATURES_REPORT.md),
  [`FEATURE_CATALOG.md`](../FEATURE_CATALOG.md), [`PROJECT_ANALYSIS.md`](../PROJECT_ANALYSIS.md)
- Dormant-elite checkpoints (Phase C detail): [`2026-06-25-beyond-imax-upgrade-program.md`](./2026-06-25-beyond-imax-upgrade-program.md)
