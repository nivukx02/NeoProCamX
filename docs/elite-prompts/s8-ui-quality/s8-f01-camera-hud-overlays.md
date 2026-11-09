# Camera HUD Overlays (histogram / waveform / zebra / peaking / false-color / grid / safe-area) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, honest-gates, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f01 |
| **Maps to checkpoint(s)** | new — propose CP-U1 (broadcast-monitor HUD hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · M |
| **Depends on** | s8-f02 (manual controls share the telemetry bus), s8-f05 (ImageQualityMetrics for false-color calibration) |

## 1. What this feature is
The on-screen monitoring layer the operator frames and exposes by: live histogram, RGB/luma waveform,
zebra clipping stripes, focus peaking, false-color exposure map, framing grid, safe-area guides,
face boxes and a thermal/REC indicator. Drawn on a Canvas/Compose overlay, fed by a vsync-gated
telemetry coalescer that samples live pipeline `StateFlow`s so the HUD never stalls the preview.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Broadcast and on-set monitors (SmallHD, Atomos Ninja/Shogun, TVLogic, Sony BVM) define the bar:
- **Waveform** in IRE/code-value units, selectable luma or parade RGB, with the 0–100 IRE graticule
  and a sub-line trace updated every field — operators trust it to the IRE.
- **Vectorscope** (75% colour-bar targets, skin-tone "I" line at ≈123°) for hue/saturation legality.
- **False colour** mapped to a *named, calibrated* exposure scale (e.g. SmallHD: purple < 2.5 IRE,
  blue ≈ shadow, green ≈ 18% grey 38–42 IRE, pink ≈ skin 52–56 IRE, yellow ≈ near-clip,
  red ≥ 100 IRE) — not arbitrary colours.
- **Zebra** at user-set IRE thresholds (70 for skin, 95–100 for clip), 1-pixel diagonal hatch.
- **Focus peaking** edge overlay with adjustable colour + sensitivity, computed on luma high-pass.
- All overlays are **non-destructive** (never burned into the recorded image) and **frame-locked**
  (the monitor field rate, no tearing).
Targets we must hit: false-color/zebra/waveform thresholds expressed in **IRE/code-value** and
verified against a synthetic ramp; overlay draw cost ≤ 2 ms/frame; zero impact on recorded pixels.

## 3. Current state in NeoProCamX (verified)
- Overlays live: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/HistogramView.kt`,
  `.../controls/WaveformView.kt`, `.../overlays/WaveformMonitor.kt`,
  `.../overlays/FalseColorOverlay.kt`, `.../overlays/ZebraExposureOverlay.kt`.
- Formatter / telemetry binding: `.../ui/CameraPreviewHudFormatter.kt` (per FEATURE_CATALOG §8 the
  HUD is fed by a "vsync-gated telemetry coalescer fed by live pipeline StateFlows").
- HDR histogram source on the GPU side: `app/src/main/cpp/shaders/hdr_metadata_histogram.comp`
  (+ generated `hdr_metadata_histogram_comp_spv.h`), validated by
  `.../rendering/HdrPosterizationHistogramValidator.kt`.
- **Gap (verify on read):** thresholds for zebra/false-color are likely UI-pixel (0–255 sRGB) rather
  than scene-referred IRE/code-value; no vectorscope; waveform graticule not in IRE units. These are
  hardening items, not breakage — the HUD is genuinely live and default-on.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **IRE-calibrated thresholds:** zebra and false-color operate on scene/code-value mapped to IRE;
   a JVM test feeds a known 0→100 IRE ramp and asserts each false-color band's boundary lands within
   ±1 IRE of the SmallHD-style scale, and zebra triggers exactly at the configured IRE.
2. **Waveform in IRE:** waveform/parade plots a 0–100 IRE graticule; ramp input produces a straight
   diagonal trace whose endpoints map to 0 and 100 IRE (unit-test on the sampling math).
3. **Vectorscope (new, opt-in):** 75% bars land on their target boxes; skin samples cluster on the
   I-line at ≈123° (host test on the polar mapping). Default off; no preview-cost when hidden.
4. **Non-destructive guarantee:** a golden recording test proves overlay-on vs overlay-off produce
   byte-identical recorded frames (overlays never reach the encoder surface).
5. **Budget:** overlay composite ≤ 2 ms/frame at preview resolution; coalescer drops to the vsync
   cadence under load (governor), never blocks the render thread.
6. **No-downgrade default:** current histogram/waveform/zebra/peaking/false-color visuals unchanged
   unless the operator enables the new IRE calibration / vectorscope; defaults reproduce today's HUD.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the existing HUD to broadcast-monitor grade (IRE calibration + vectorscope),
behind no-downgrade defaults. **Non-goals:** do not touch the encoder/record surface; do not change
the GPU histogram shader's ABI; do not add any library (no-vendor) — vectorscope is pure Canvas math.

**Plan:**
1. **Golden-lock** the current overlay rendering: add a characterization test rendering each overlay
   from a fixed telemetry snapshot to a bitmap hash; and a record-parity test proving overlays don't
   alter recorded pixels.
2. Introduce a scene→IRE mapping helper (pure Kotlin) shared by zebra/false-color/waveform; drive
   thresholds in IRE with the existing UI values as the default mapping (passthrough).
3. Add the calibrated false-color band table + vectorscope view (opt-in flag, default off).
4. Wire the coalescer to honour the adaptive governor cadence under thermal/load.

**Files:** the five overlay/view files above, `CameraPreviewHudFormatter.kt`, a new
`HudIreScale.kt` + `VectorscopeView.kt`. No push-constant / ABI change (HUD is CPU-side Canvas).

**Tests:** `HudIreScaleTest` (ramp→band boundaries ±1 IRE; zebra trigger IRE), `WaveformIreTest`
(graticule mapping), `VectorscopeMappingTest` (75% bars + I-line angle), `HudRecordParityTest`
(overlay-on == overlay-off recorded bytes).

**Gating:** IRE recalibration defaults to the current mapping; vectorscope flag default off.

## 6. Acceptance & verification
- **JVM:** the four tests above green; existing HUD characterization hashes unchanged for default mode.
- **Device (vivo):** waveform/false-color/zebra read correctly against a grey card + 18% chart; HUD
  draw stays ≤ 2 ms/frame in the on-device profiler; recording with HUD on shows no burned-in overlay.
- **Gate keys:** none — HUD is monitoring-only and must never feed a quality PASS.

## 7. Risks & no-downgrade guardrails
- **Burn-in into record:** record-parity golden makes overlay leakage impossible-by-construction.
- **Lag:** vsync-gated coalescer + governor cadence; vectorscope opt-in and off by default.
- **Mis-exposure misread:** IRE calibration validated against a synthetic ramp before shipping.

## 8. References
- Reference: SmallHD/Atomos false-color & waveform monitoring; SMPTE IRE scale; vectorscope 75%-bar + I-line (≈123°) skin reference.
- Tech: [`../technology_explanations/markdown/`](../technology_explanations/markdown/) (HDR histogram / posterization).
- Source: `ui/controls/HistogramView.kt`, `ui/controls/WaveformView.kt`, `ui/overlays/{WaveformMonitor,FalseColorOverlay,ZebraExposureOverlay}.kt`, `ui/CameraPreviewHudFormatter.kt`, `cpp/shaders/hdr_metadata_histogram.comp`, `rendering/HdrPosterizationHistogramValidator.kt`.
- Related prompts: s8-f02, s8-f05.
