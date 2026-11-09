# SNCE 23-float Scene-Brain (SnceFrameConstants) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f06 |
| **Maps to checkpoint(s)** | CP41 (upload SNCE 23-float frame block to GPU; scene-driven OCCS) |
| **Current working level** | 🟠 dormant (packed "for upload to the Vulkan colour pipeline" — never uploaded) |
| **Feature rating (potential)** | 3/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · L |
| **Depends on** | s2-f10 (illuminant + CAM16 that fill the block), s2-f03 (GPU uniform ABI discipline), s2-f07 (OCCS, the first consumer of scene-driven params) |

## 1. What this feature is
`SnceFrameConstants` (defined in `SnceV2Engine.kt:9`) is the per-frame "scene-brain": the engine runs
IMU prior → 9-method illuminant (s2-f10) → adaptive EWMA → CAM16 viewing conditions and packs the
result into **23 floats** in a documented order "for upload to the Vulkan colour pipeline"
(`SnceV2Engine.kt:7, 50`). It also carries a real frame-to-frame scene-change score (CP42 Track A,
done). This block is the bridge that *would* let the GPU passes (OCCS s2-f07, NRLI s2-f08) be driven by
the actual scene instead of compile-time constants. Today it is computed (`SnceV2Engine.kt:147`) and
then **never uploaded**.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
This is the **scene-adaptive metadata** layer — the equivalent of:
- **Dolby Vision dynamic metadata (per-frame/per-scene L1-L4 analysis)** driving the display mapping
  frame-by-frame, and **HDR10+ dynamic tone-mapping metadata** — both are compact per-frame parameter
  blocks (min/max/avg luminance, tone-curve anchors) that steer downstream color/tone in real time.
- A cinema ISP's **3A + scene statistics** (illuminant estimate, adaptation state, viewing-condition
  surround) feeding the color rendering transform every frame, so adaptation is correct and stable.
The elite bar is not the block itself but its **honest, byte-exact, std140 upload** and its use to
*drive* the perceptual passes: the 23 floats must map field-for-field to a GPU UBO, round-trip without
loss, and change GPU behavior only within the engine's bounded ceiling (no look swing).
Measurable: (a) pack/unpack round-trips all 23 fields within 1e-6; (b) the GPU UBO byte layout matches
the Kotlin packer (std140); (c) with default (neutral) scene state, the uploaded block yields
**bit-for-bit today's output** (the dormant default); (d) a scene change moves the documented fields
monotonically.

## 3. Current state in NeoProCamX (verified)
- `SnceFrameConstants` declared in `SnceV2Engine.kt:9`; doc string "Packs to 23 floats in the
  documented order for upload to the Vulkan colour pipeline" (`SnceV2Engine.kt:7`); the per-frame chain
  IMU→illuminant→EWMA→CAM16 is at `SnceV2Engine.kt:50`; constructed at `SnceV2Engine.kt:147`.
- **Never uploaded.** No GPU UBO consumes it; the live OCCS pass uses compile-time constants
  (`iGain = chromaGain = 0.10f`, `vulkan_bridge.cpp:215-216`) and NRLI uses an identity LUT at strength
  0 — neither is driven by this block. (Catalog §2 row 6: "Packed … never uploaded".)
- Scene-change half (CP42 Track A) is **done and live** (memory: `SnceV2Engine.processFrame` computes a
  real Stage-09 scene-change score, default-on, bounded by the ±5%/±6% clamp). The IMU-pitch half is
  device-gated (no absolute-pitch source captured yet).
- So CP41 = give this block a real, byte-exact GPU UBO and let OCCS (s2-f07) read it — behind a gate
  that defaults to today's constant behavior.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Round-trip fidelity:** pack→unpack of all 23 fields is loss-free within 1e-6; field order matches
   the documented order (a test enumerates the 23 slots).
2. **std140 UBO ABI:** a new color-scene UBO's byte layout matches the Kotlin packer field-for-field
   (offsets/strides asserted, CP02 pattern); extend the layout test + re-pin the shader manifest in the
   same commit (§0.5).
3. **No-downgrade default:** with the gate off (or scene state = neutral default), the GPU passes
   behave **bit-for-bit as today** (OCCS at 0.10 forward + zero-gain copy-back, NRLI identity). The
   upload exists but does not change pixels until the owner enables scene-driving on-device.
4. **Scene-driving correctness:** when enabled, the documented fields drive OCCS gains (s2-f07) within
   the engine's bounded range; a scene-change spike moves the relevant field monotonically; the visible
   effect stays inside the ±0.05 WB / ±0.06 sat-equivalent ceiling for the WB/sat-linked fields.
5. **Budget (ms):** upload is O(1) per frame (23 floats); no new heavy compute (the block is already
   computed). Off the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** define a byte-exact GPU UBO for the 23-float block, upload it per frame, and route it to the
OCCS pass (s2-f07) behind a no-downgrade gate that defaults to today's constant behavior. **Non-goals:**
do not change the engine math; do not wake the IMU-pitch half (device-gated); do not widen any
clamp; no new model.

**Plan (CP41):**
1. **GOLDEN-LOCK FIRST.** Characterization test freezing today's OCCS/NRLI GPU behavior (dispatch
   params) so the upload-but-default path is provably bit-for-bit unchanged.
2. **Define the color-scene UBO** mirroring the 23-float order; add a `SnceFrameConstants → UBO` packer
   with a round-trip test (§4.1) and a layout test vs the GLSL (§4.2).
3. **Upload per frame** in `vulkan_bridge.cpp` (reuse the existing uniform-upload plumbing, s2-f03
   discipline). Gate the *use* behind a specialization constant defaulting to "ignore block → use
   today's constants".
4. **Route to OCCS** (s2-f07): when the gate is on, derive OCCS `iGain`/`chromaGain` from the scene
   block instead of the 0.10 constant — within the bounded range.
5. **Extend the ABI layout test + re-pin the shader manifest** in the same commit.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/snce/SnceV2Engine.kt` (packer),
`app/src/main/cpp/vulkan_bridge.cpp` (UBO + upload + gate), the OCCS GLSL
(`app/src/main/cpp/shaders/vulkan/occs_opponent.comp`), the layout test + manifest pin; tests under
`app/src/test/.../snce/` and `.../rendering/`.

**ABI:** new UBO — std140 field-for-field, layout test mandatory, manifest re-pin in the same commit.

**Gating:** specialization constant defaults to "constants as today"; scene-driving only activates when
the owner flips it on-device.

## 6. Acceptance & verification
- **JVM:** 23-field round-trip + layout test green; default-behavior characterization unchanged.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer reports zero uninitialized-UBO
  reads on the new block; manifest hashes re-pinned.
- **Device (vivo):** gate off → preview/record bit-match today; gate on → scene change visibly steers
  OCCS within the bounded range, no look swing, no flicker. Owner confirms.

## 7. Risks & no-downgrade guardrails
- **Look swing on scene-driving:** bounded gain range + the ±0.05/±0.06-equivalent ceiling; gated off
  by default.
- **ABI/std140 drift:** layout test + manifest re-pin in the same commit.
- **Uninitialized UBO (UB):** deterministic init + validation-layer assertion (the MFSR-style guard).
- **Lag:** 23-float upload is O(1); block already computed; off cold-start.

## 8. References
- Roadmap CP41 (this), CP42 (scene-change feed, Track A done), CP46 (mesopic OCCS consumes it): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: SNCE v2 engine (14-stage, canonical CAM16, not yet GPU-wired)
- Standard canon (Dolby Vision / HDR10+ dynamic metadata, scene-adaptive ISP stats): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/snce/SnceV2Engine.kt:7, 9, 50, 147`; `app/src/main/cpp/vulkan_bridge.cpp:215-216`
- Related prompts: s2-f03, s2-f07, s2-f08, s2-f10
