# Focus Peaking & Overlays (UI) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f09 |
| **Maps to checkpoint(s)** | new — propose CP-F10 (peaking consolidation + standard) |
| **Current working level** | 🟡 partial (live GL/Vulkan peaking works; standalone CPU `FocusPeakingOverlay` View is dead) |
| **Feature rating (potential)** | 5/10 now → 6/10 target |
| **Code-quality rating (now)** | 6/10 (live path good; a dead duplicate View invites bit-rot) |
| **Risk · Size** | L · M |
| **Depends on** | s5-f03 (subject mask), s5-f06 (segmentation mask), s5-f07 (peaking intensity) |

## 1. What this feature is
Focus peaking highlights the in-focus (high-spatial-frequency) regions of the preview so the
operator can judge critical focus. NeoProCamX runs this **live in the GPU preview path** (Sobel-
class edge detection in the Vulkan/GL preview render, gated by a user setting), optionally masked
to the AI subject / segmentation region so only the subject's edges peak. Separately, there is a
**standalone `FocusPeakingOverlay` Android `View`** that does CPU Sobel edge detection and draws
yellow strokes — this is a dead duplicate with no production caller. The job here is to
**consolidate**: keep the live GPU path canonical, retire/repurpose the dead View, and lock the
peaking behavior to a defined standard.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Peaking is a focus-assist standard on every pro monitor/camera (ARRI, RED, Sony, SmallHD, Atomos):
(1) **selectable color + intensity** (red/blue/yellow/white) so peaking is visible against any
scene; (2) **threshold control** — sensitivity to edge strength is adjustable; (3) **frequency-
based, not luma-based** — it keys on local high-frequency detail (the sharp-edge signal), not
brightness; (4) **non-destructive** — peaking is an overlay; it never alters the recorded image.
Elite phones add subject-masked peaking (only the tracked subject peaks). Translate to targets:
**peaking keys on edge magnitude above a configurable threshold, renders in a selectable
color/intensity, never touches recorded pixels, and (when enabled) is confined to the subject
mask.**

## 3. Current state in NeoProCamX (verified)
- **Live GPU peaking** is wired through the preview render path: peaking setting flows
  `PreferencesManager` → `CameraViewModel` → `CameraVulkanPreviewView.kt` /
  `FramePipelineManager.kt`, drawing Sobel-class edges in the GL/Vulkan preview; subject masking
  uses the AI mask (segmentation, s5-f06). It is overlay-only (does not alter recorded pixels).
- **Dead duplicate:** `ui/overlays/FocusPeakingOverlay.kt` is a CPU-Sobel Android `View`
  (`FocusPeakingOverlay.kt:17`, "Uses Sobel edge detection… overlays colored edges") referenced
  **only by itself** — no layout inflation, no caller. It is bit-rot waiting to happen
  (it imports `FrameGeometry` and reimplements peaking on CPU).
- Overlay geometry / animation / reticle policies live in `ui/overlays/FocusOverlay*.kt` and are
  used by the live reticle UI.
- `CameraFocusControl.calculateFocusPeakingIntensity` (s5-f07, `CameraFocusControl.kt:499`) is the
  unwired intensity-policy primitive that *should* feed the live peaking strength.
- Status: **🟡 partial.** Consolidation prompt.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Single source of truth:** the dead `FocusPeakingOverlay` CPU View is either deleted or
   demoted to a documented non-production helper; the live GPU path is the only peaking that runs.
   A test/grep asserts no production caller resurrects the CPU duplicate.
2. **Selectable color + intensity + threshold:** the live peaking exposes color, intensity, and
   edge threshold; intensity is driven by `calculateFocusPeakingIntensity` (s5-f07) within bounds;
   parameter test asserts the mapping.
3. **Edge-magnitude keying:** peaking activates on Sobel/edge magnitude above the threshold, not on
   luma; host port of the keying logic tested on a synthetic edge fixture.
4. **Non-destructive:** peaking is overlay-only — recorded/encoded pixels are bit-for-bit identical
   peaking-on vs off. Golden on the record/export path proves it.
5. **Subject-mask confinement:** when subject-masked peaking is enabled, peaking is confined to the
   AI mask region; test asserts no peaking outside the mask.
6. **No-downgrade default:** the current live peaking behavior + default setting are unchanged;
   consolidation must not alter the live overlay output (golden-lock the preview-overlay path or
   characterize it). Removing the dead View changes nothing observable.
7. **Budget:** no new per-frame cost (the live path already runs in the preview shader); deleting
   the CPU View *reduces* potential cost.

## 5. Implementation prompt (hand this to the agent)
**Scope:** consolidate peaking onto the live GPU path; retire the dead CPU `FocusPeakingOverlay`
View; wire `calculateFocusPeakingIntensity` to the live peaking strength; lock color/intensity/
threshold + non-destructiveness + mask confinement. **Non-goals:** do not rewrite the GPU peaking
shader math; do not change the default peaking setting; do not touch the reticle overlays.

**Plan:**
1. **Characterize/golden-lock first.** Pin the live peaking overlay output + the record/export
   path (peaking is non-destructive) as goldens.
2. Delete (or demote with a clear "not production" doc + test guard) `FocusPeakingOverlay.kt`;
   confirm zero callers first.
3. Route `CameraFocusControl.calculateFocusPeakingIntensity` into the live peaking strength
   parameter (bounded), defaulting to today's value.
4. Add color/intensity/threshold mapping test, edge-keying test, non-destructive record golden,
   subject-mask confinement test.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/ui/overlays/FocusPeakingOverlay.kt` (delete/
demote), `rendering/CameraVulkanPreviewView.kt` + `pipeline/FramePipelineManager.kt` (intensity
wiring, read-mostly), `camera/CameraFocusControl.kt` (read-only), new tests.

**ABI:** if a peaking-intensity push constant changes, extend `GpuPushConstantLayout` + its layout
test in the same commit and re-pin the shader-manifest hash; otherwise none.

**Gating:** the live default is unchanged; intensity wiring defaults to today's value; deleting the
dead View is observably a no-op (no callers).

## 6. Acceptance & verification
- **Unit/golden (JVM):** color/intensity/threshold mapping; edge-keying; non-destructive record/
  export golden (peaking-on == peaking-off recorded pixels); subject-mask confinement; no-caller
  guard for the removed CPU View; layout-test re-pin if a push field changed.
- **Device (vivo I2405 / Adreno):** toggle peaking — edges highlight in the selected color, respect
  the threshold, confine to the subject mask when enabled, and the recorded clip is unaffected.

## 7. Risks & no-downgrade guardrails
- **Live peaking regression from consolidation:** golden-lock the live overlay before touching it.
- **Peaking leaks into recorded pixels:** non-destructive record/export golden.
- **Resurrecting the dead CPU path:** delete or guard with a no-caller test.
- **Lag:** live path already in the preview shader; no new per-frame cost; CPU View removal reduces
  risk.

## 8. References
- Roadmap: propose CP-F10 in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: ARRI / RED / SmallHD / Atomos focus-peaking standards (color/intensity/threshold,
  frequency-keyed, non-destructive).
- Source: `ui/overlays/FocusPeakingOverlay.kt:17` (dead CPU View), `rendering/CameraVulkanPreviewView.kt`,
  `pipeline/FramePipelineManager.kt`, `camera/CameraFocusControl.kt:499`
- Related prompts: s5-f06, s5-f03, s5-f07
