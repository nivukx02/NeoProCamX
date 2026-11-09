# Focused And Natural Detail Video Profiles Design

**Date:** 2026-05-27  
**Scope:** Preview, encoded recording, and NPV10-derived export quality control for Video, Pro, NDR, and N-Log workflows.

## Goal

Provide two truthful high-quality detail profiles:

- `Focused Detail`: stronger edge-aware detail enhancement restricted to a reliable tap-focused or AI-tracked subject, whether the subject is near or distant.
- `Natural Detail`: noise cleanup and color delivery without added sharpening enhancement.

Both profiles target stable, clean imagery with reduced visible moving RGB/chroma noise, retained optical texture, and no false claims about zero physical sensor noise, fake resolution, or unverified processing of saved recordings.

## Confirmed Constraints

The current source already contains the active shader detail constants requested in earlier quality work:

- Heteroscedastic noise estimate coefficients `0.0012` and `0.00022`.
- Motion-preserving detail blending beginning at `0.85`.
- Halo-guarded detail amplification using `4.85`.
- Resolution-aware bitrate tiers and manual bitrate behavior.

Real-device validation on the connected handset established:

- A real HEVC UHD `3840x2160` clip can be recorded through the Camera2 preview-parity recording path.
- The forced Vulkan internal hardware-import encoder path delivered zero frames on this handset.
- Therefore custom GPU enhancement may be certified for a saved encoded clip only after the runtime proves imported frames reached a bound encoder surface and produced a valid file.

This design does not bypass that qualification.

## User-Visible Behavior

### Detail Profile Setting

Add a user-selectable video detail profile with two values:

| Profile | Intended Output |
| --- | --- |
| `Focused Detail` | Clean frame-wide denoise plus higher bounded subject-detail enhancement when a reliable focused-subject mask exists. |
| `Natural Detail` | Clean frame-wide denoise with no additive sharpening enhancement. |

The selected detail profile applies consistently to preview monitoring, supported GPU-processed recording, and enhanced NPV10 MOV export. RAW and private NPV10 master payloads remain unchanged.

### No Distance Restriction

`Focused Detail` applies to the selected or AI-tracked in-focus subject at both short and long focus distances. There is no `15 cm` cutoff and no long-distance disable rule.

## Pipeline Architecture

### Shared Quality Intent

The quality decision layer resolves one profile intent for preview and recording:

```text
Selected mode + selected color science + selected detail profile
    -> denoise/detail policy
    -> preview render contract
    -> recording render contract
    -> quality metadata/evidence
```

Preview and recording must not report the same enhanced output unless the same effective processing route ran on the frames delivered to each output.

### Frame Processing Order

For Vulkan-processed preview, qualified GPU-processed encoded video, and enhanced NPV10 derived export:

```text
Source and color-format validation
-> motion-safe luma/chroma cleanup
-> shadow RGB/chroma stability suppression
-> subject mask and focus-confidence resolution
-> edge/texture/noise/skin/halo/alias confidence calculation
-> profile-specific detail stage
-> selected color science / transfer delivery
-> output encoding or preview presentation
```

### Recording Route Qualification

For Video, Pro, NDR, and N-Log encoded recordings:

- Prefer the GPU-to-encoder route only when its runtime prerequisites are available.
- Validate bound encoder surface, imported frame count, timestamp progress, saved output, actual resolution, FPS stability, and output metadata.
- If validation fails or the device has not qualified, continue using the stable Camera2 parity recording route.
- Metadata must state whether `Focused Detail` or `Natural Detail` actually executed on saved encoded frames, or whether the clip used Camera2/ISP fallback.

The app must not force `INTERNAL_HARDWARE_IMPORT_ENCODER` merely because Vulkan runtime initialization succeeded.

## Focused Detail Profile

### Subject Scope

The strongest detail lift is permitted only inside a confident subject region produced by:

- user tap-focus tracking region; or
- AI-tracked subject region with adequate tracking confidence and active focus association.

Subject distance does not alter eligibility. A focused distant subject is treated the same as a focused nearby subject when confidence, motion, and texture evidence are comparable.

### Strength Rules

Inside the confident subject region:

- Apply detail recovery only after denoise.
- Enhance luma-supported edge and texture components, not chroma speckle.
- Gate gain using edge confidence, texture confidence, focus confidence, motion confidence, shadow noise, skin texture, halo risk, and moire/alias risk.
- Suppress gain in flat noisy shadows, specular transitions, unstable moving edges, and skin regions that would become plastic or outlined.

Outside the confident subject region:

- Use normal frame-wide clean denoise and conservative non-boosted detail protection.
- Do not apply the strongest subject lift to background textures.

### Missing Subject Evidence

If no reliable tap-focused or AI-tracked subject mask is available, `Focused Detail` falls back to bounded normal detail processing and reports `subjectDetailApplied=false`. It must not infer a whole-frame subject or claim selective enhancement.

## Natural Detail Profile

`Natural Detail` disables additive sharpening enhancement:

- Continue frame-wide luma/chroma cleanup and RGB stability suppression.
- Preserve captured texture using denoise guards and motion handling.
- Do not run subject-selective detail lift, global sharpening lift, or artificial micro-contrast sharpening.
- Retain the selected color profile and correct transfer/metadata handling.

This mode exists for users who prefer unsharpened output for grading or natural optical texture.

## Noise And Detail Truth

Physical sensor noise cannot be reduced to mathematical zero while retaining all real signal. The quality target is:

- no visible dancing RGB/chroma speckles in validated test scenes;
- reduced flat-region chroma and luma variance;
- stable shadows and color;
- no oily skin smoothing;
- no halo edges or emphasized noisy grain.

Results must be reported from fixed-scene device comparisons, not from mode labels.

## RAW And NPV10 Policy

- RAW master payloads are sensor truth and are not denoised or sharpened in-place.
- Private NPV10 master payloads remain unmodified and truthfully labelled for their source format and bit depth.
- NPV10 manual MOV conversion may apply the selected detail profile to the derived export only.
- Export metadata states selected profile, whether subject detail executed, whether a valid subject mask existed, and that the private master remains unchanged.

## Metadata And Runtime Evidence

Each processed output or export records:

- `selectedDetailProfile=FOCUSED_DETAIL|NATURAL_DETAIL`
- `subjectDetailRequested=true|false`
- `subjectMaskSource=TAP_FOCUS|AI_TRACKED|NONE`
- `subjectMaskConfidence`
- `subjectDetailApplied=true|false`
- `detailProcessingRoute=VULKAN_PREVIEW|GPU_ENCODER_VALIDATED|NPV10_EXPORT|CAMERA2_ISP_FALLBACK`
- `customDenoiseApplied=true|false`
- `customSharpnessApplied=true|false`
- `recordingGpuFrameImportValidated=true|false`
- existing mode, transport, color, bit depth, bitrate, resolution, FPS, and NPV10 source-truth evidence.

When Camera2 fallback is used for a saved encoded clip, `customSharpnessApplied=false` regardless of the user's selected profile.

## Testing And Device Validation

### Unit And Contract Tests

Add tests proving:

- `Focused Detail` resolves the same maximum permitted subject boost for otherwise equivalent near and distant focused subjects.
- The strongest lift is not applied outside the selected/tracked subject mask.
- Missing or low-confidence subject evidence falls back without claiming subject enhancement.
- `Natural Detail` resolves zero additive sharpening.
- Noisy shadows and unstable motion reduce detail gain while preserving chroma cleanup.
- RAW and private NPV10 masters remain unmodified.
- GPU-recording detail claims require validated imported frames.
- Camera2 fallback never reports custom sharpening on a saved clip.

### Build And Device Evidence

Run unit tests, assemble, and lint, then deploy to the attached Android device. Capture:

- one near focused-subject clip using `Focused Detail`;
- one distant focused-subject clip using `Focused Detail`;
- one clip using `Natural Detail`;
- logs proving active recording route and whether GPU imported frames were validated;
- file metadata proving actual codec, dimensions, FPS, color/transfer mode, and bitrate;
- side-by-side frame analysis for RGB/chroma stability, subject texture, and halo artifacts.

Only clips that prove the GPU encoder route ran may be rated for custom recorded-frame enhancement. Camera2 fallback clips remain useful recordings but cannot prove the selected GPU detail profile affected saved pixels.

## Acceptance Criteria

- Users can select `Focused Detail` or `Natural Detail`.
- `Focused Detail` supports both near and distant focused subjects without a distance cutoff.
- Strong sharpening is subject-restricted and evidence-gated, not global.
- `Natural Detail` performs no additive sharpening enhancement.
- RAW and NPV10 private masters remain truthful and unchanged.
- Saved encoded recordings remain reliable through stable fallback until GPU encoder delivery is proven.
- Quality reporting is based on runtime and file evidence rather than unsupported absolute claims.
