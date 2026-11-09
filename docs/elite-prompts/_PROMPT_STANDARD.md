# Elite Upgrade Prompt — Authoring Standard & Global Contract

> This file defines the **shape** of every per-feature prompt in `docs/elite-prompts/`, and the
> **global constraints** that EVERY prompt silently inherits. Each feature prompt is a
> self-contained brief you can hand to an implementation agent (or future-you) to raise ONE
> feature to elite cinema/IMAX tier. Read this once; then each prompt only states what's
> *specific* to that feature.

Companion documents:
- Audit & ratings: [`../PROJECT_ANALYSIS.md`](../PROJECT_ANALYSIS.md)
- Full feature inventory: [`../FEATURE_CATALOG.md`](../FEATURE_CATALOG.md)
- Checkpoint roadmap (CP IDs): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech explanations (NDR10, NIVUKX, N-Log, NeoGamut, NPV10…): [`../technology_explanations/markdown/`](../technology_explanations/markdown/)

---

## 0. The standing order (applies to ALL prompts — do not restate, do not violate)

These are **hard constraints**. A prompt that conflicts with one of these is wrong; obey the
constraint, not the prompt.

1. **NO DOWNGRADE, EVER.** No existing live behavior may regress. Every feature that runs today
   must run at least as well after the change. Net-new capability is a *superset*: gate new paths
   behind a flag/specialization-constant whose default reproduces today's output bit-for-bit until
   the owner enables it device-side. (This is exactly how CP03/CP52/CP53 were landed.)
2. **NO-VENDOR RULE.** No new third-party/OSS models, no new heavy runtime dependencies, no
   cloud calls. Allowed: classical math/DSP, the **already-shipped** models only —
   MediaPipe (`FaceLandmarker`, `PoseLandmarker`, `ImageSegmenter`), MiDaS v2.1-small TFLite,
   EfficientDet-Lite0, ML Kit ImageLabeling — and your own Kotlin/GLSL/C++ algorithms. If a
   prompt seems to *need* a new model, that is a deferred/device-gated note, not an action.
3. **COMMIT PER FEATURE.** One scoped git commit per checkpoint, only after **build green +
   unit tests green**. Message style: `feat(<area>): <what> [CPxx]` ending with the repo's
   `Co-Authored-By` trailer. Never bundle two features in one commit.
4. **GOLDEN-FIRST FOR LIVE-PATH CHANGES.** Any change that can alter live pixels MUST have a
   golden/characterization test *locking the current output first* (Phase-0 discipline). The
   test proves the default path is unchanged; only then is the new path added behind its gate.
5. **ABI IS SACRED.** GPU push-constant / UBO byte layouts are contract-locked
   (`GpuPushConstantLayout` + `*PushConstantLayoutTest`). If you add a field, extend the layout
   test in the same commit and re-pin any shader-manifest hashes. std140 alignment is not
   optional.
6. **HONEST GATES.** Never synthesize a PASS. If a capability can't be proven from real
   transport/bit-depth/pixels, it lands `NOT_PROVEN`/`NOT_AVAILABLE` (the `OmegaCapabilityGate`
   contract). Measurement must come from real pixels, not heuristics, wherever a real-pixel
   producer exists.
7. **DEVICE-GATED ≠ SKIPPED.** Work that can only be validated on the vivo I2405 / Adreno
   (Android 16) is implemented up to the JVM/native-build boundary, fully unit-tested in
   isolation, left **dormant** (zero live change), and flagged `DEVICE-GATED` with the exact
   on-device check the owner must run. Do not fake device validation.
8. **NO LAG REGRESSION.** Every per-frame addition states its budget (ms/frame target) and its
   backoff/thermal behavior. Preview must stay real-time; heavy compute defers off the
   cold-start path (the deferred-compile pattern) and respects the adaptive load governor.

### Build & test commands (canonical)
```bash
# JVM unit/contract/golden suite (use the bundled JBR 21, NOT a PATH java):
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
# Native (Vulkan/C++) build:
./gradlew :app:externalNativeBuildDebug -Pandroid.ndkPath="<sdk>/ndk/29.0.14206865"
# Full re-run when in doubt:
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --rerun-tasks
```
Baseline to protect: **CP00 = `3f66245`** (537 suites / 3115 tests / 0 failures, growing).

---

## 1. Required section template (every per-feature prompt uses exactly these headings)

```markdown
# <Feature name> — Elite Upgrade Prompt

| Field | Value |
|---|---|
| **Subsystem** | <1–8 name> |
| **Prompt ID** | s<N>-f<NN> |
| **Maps to checkpoint(s)** | CPxx (or "new — propose CPxx") |
| **Current working level** | 🟢/🔵/🟡/🟠/⚪ |
| **Feature rating (potential)** | x/10 |
| **Code-quality rating (now)** | x/10 |
| **Risk · Size** | L/M/H · S/M/L/XL |
| **Depends on** | <other prompt IDs / CP IDs> |

## 1. What this feature is
One tight paragraph: the job this feature does in the pixel/▶ path.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Concrete, named reference behavior from the professional world — what "elite" actually means
here, in measurable terms. Cite the real systems (see §2 of this standard for the canon).
Translate their behavior into the spec we must hit. No marketing; engineering targets.

## 3. Current state in NeoProCamX (verified)
What exists today, with `file:line`. What is live, what is dormant/dummy-fed/test-only, and the
EXACT reason it doesn't reach pixels (e.g. "dispatched on dummy array; real seed in
`unusedRealtimeComputeDiagnostics()`, zero callers — vulkan_bridge.cpp:6105"). Be specific and
truthful — verify against source, do not trust the catalog blindly.

## 4. Elite target spec (the definition of "10000% best", made testable)
Numbered, measurable acceptance criteria. Each must be checkable by a unit/golden test, a
metric (MTF50, ΔE2000, SNR dB, EPE, flicker σ…), or a named device check. "Looks good" is
banned. Include the ms/frame budget and the no-downgrade default.

## 5. Implementation prompt (hand this to the agent)
The actual work order. Self-contained. Includes:
- Scope & non-goals (what NOT to touch).
- Step-by-step plan (golden-lock → implement-behind-gate → test → device-note).
- Files to create/modify (paths).
- ABI/push-constant/std140 care, shader-manifest re-pin if applicable.
- Tests to add (names + what they assert).
- The no-downgrade default and how the new path is gated.

## 6. Acceptance & verification
- Unit/golden tests that must be green.
- Quality gate keys touched (Tier-Omega / Omega capability), if any.
- Device-validation checklist (vivo) — explicit, reproducible steps.

## 7. Risks & no-downgrade guardrails
Failure modes (UB, black-out, banding, grain amplification, thermal, lag) and the specific
guard that makes each impossible-by-construction.

## 8. References
Links to the tech-explanation doc(s), the relevant CP entry, related prompt IDs, and the
source files.
```

---

## 2. The cinema/IMAX reference canon (cite these, not vibes)

When writing §2 "How high-end cameras do this", ground the bar in real systems and standards:

- **Cameras:** ARRI ALEXA 35 (ALEV 4 sensor, 17 stops, ARRI Textures, LogC4), RED V-RAPTOR /
  KOMODO (REDCODE RAW wavelet, IPP2 pipeline), Sony VENICE 2 (dual-base-ISO 800/3200, X-OCN,
  S-Gamut3.Cine/S-Log3), Blackmagic URSA (Blackmagic RAW, Gen 5 Color Science), Panavision
  DXL2, Canon C-series (Cinema RAW Light).
- **IMAX:** 15-perf 65mm film (≈18K-equivalent resolving power, huge over-sampling → grainless
  downsample), IMAX with Laser (dual-4K laser projection, Rec.2020-class gamut, ~1,000,000:1
  contrast), DMR (Digital Media Remastering — per-frame grain/sharpness/contrast reconstruction).
- **Color / mastering standards:** ACES (AP0/AP1, IDT→RRT→ODT, ACEScct), Dolby Vision / PQ
  (SMPTE ST 2084), HLG (BT.2100), BT.2020 gamut, CIECAM/CAM16 appearance, Oklab, sRGB/Rec.709.
- **Imaging science:** demosaic (AHD, Hamilton-Adams, LMMSE, RCD), multi-frame super-res
  (Google "Handheld Multi-frame SR" / Wronski 2019, steering-kernel regression), HDR fusion
  (Debevec, exposure-bracket merge, dual-gain/dual-conversion-gain sensors), temporal denoise
  (motion-compensated, VST/Anscombe), optical flow (Horn-Schunck, Lucas-Kanade, TV-L1),
  stabilization (gyro-EIS, rolling-shutter rectification, trajectory optimization à la Google
  "Fused Video Stabilization"), tone mapping (ACES RRT, Hable, Reinhard, BT.2390 EETF).
- **Objective metrics (how "best" is proven):** ISO-12233 slanted-edge MTF50, CIEDE2000,
  flat-field SNR(dB), end-point-error (EPE) for flow, temporal flicker σ, banding/posterization
  code-delta, opponent-space chroma-crawl.

The target is **physically honest**: the sensor/optics floor of a phone is fixed. "Beyond IMAX"
means maximizing *information density per frame* (multi-frame fusion, 12-bit RAW, all-intra
10-bit, motion-compensated temporal, perceptually-correct color) up to that floor — never faking
resolution or claiming what the silicon can't deliver.

---

## 3. Status legend (shared with the catalog)
- 🟢 **live-default-on** · 🔵 **live-optional** · 🟡 **partial** · 🟠 **dormant** · ⚪ **test-only-scaffold**

## 4. How to execute a prompt
1. Read this standard + the one feature prompt.
2. Confirm the §3 "current state" against live source (it may have moved since authoring).
3. Write/extend the golden lock FIRST if the live path is touched.
4. Implement behind the no-downgrade gate.
5. Build green → unit tests green → commit with the CP ID.
6. Record the device-validation step for the owner; mark the CP entry `[~]` (foundation) or
   `[x]` (done) in the roadmap.
