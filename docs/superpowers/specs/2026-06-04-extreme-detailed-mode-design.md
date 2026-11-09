# Extreme Detailed Mode Design

## Goal

Create an Extreme Detailed Mode that maximizes visible detail and cleanliness for the processed video path without changing RAW/RAW12 sensor masters or making unsupported "zero noise" or literal IMAX-camera claims.

The mode should make preview, processed recording, HDR/LOG/NDR processed modes, and derived NPV10/MOV export visibly sharper, cleaner, and more texture-rich while staying bounded by scene, motion, encoder, thermal, and artifact evidence.

## Scope

Extreme Detailed Mode applies to:

- Vulkan processed preview.
- Vulkan/import-backed processed recording when runtime evidence validates that path.
- Normal SDR, HDR/NDR, N-Log/LOG, and NIVUKX-style processed video modes.
- Derived NPV10/MOV/P010 export outputs.
- Metadata and certificates describing whether the extreme detail path was requested, applied, downgraded, or blocked.

Extreme Detailed Mode does not apply to:

- RAW/RAW12 private sensor master bytes.
- NPV10 private master payloads before derived export.
- Unsupported fake resolution labels.
- Unconditional claims of zero noise, device 10/12-bit support, GPU recording, or IMAX equivalence without evidence.

## Current Context

The project already contains high-end video-quality policy layers, Vulkan preview and processed-recording paths, NPV10/MOV export support, and quality evidence gates. Existing docs cover V13 sharpness/detail, V15-V17 evidence validation, and GPU detail/bitrate contracts.

The current active shader path is already aggressive. For example, the processed preview shader contains strong micro-detail amplification. The new mode must therefore improve the quality contract, not only raise constants. The implementation should add a named, testable profile that combines stronger denoise, guarded luma detail, encoder quality, export enhancement, and evidence logging.

## Architecture

Extreme Detailed Mode is a profile-driven extension of the existing pipeline:

1. The UI/settings layer exposes `EXTREME_DETAIL` as a selectable video detail profile, with debug/internal request support using the same profile value.
2. Camera/view-model selection forwards the profile into processed video quality policies.
3. `VideoNoiseReductionPolicy` raises luma/chroma cleanup for the profile before sharpening, with motion and skin/texture preservation gates.
4. `VideoDetailSharpeningPolicy` raises edge-aware luma micro-detail, reconstruction, and micro-contrast only when clean-detail confidence is sufficient.
5. `VideoImageQualityPipelinePolicy` expresses the mode in the end-to-end processed plan so preview, recording, and certificates agree.
6. The Vulkan shader consumes the profile through the existing render tuning path. If that path cannot distinguish detail profiles, add one explicit profile/intensity uniform rather than scattering profile-specific constants.
7. Recording quality policies raise bitrate/codec quality requests where supported, without overriding user-owned custom bitrate semantics or encoder capability clamps.
8. Derived export applies the strongest safe detail and dither pass to MOV/P010 outputs while leaving RAW/NPV10 private masters unmodified.

## Quality Behavior

Extreme Detailed Mode should favor visible detail and clean output over conservative naturalism, but it must remain artifact-aware.

In clean, bright, stable scenes:

- Raise edge-aware luma detail and micro-contrast.
- Increase fine texture preservation and reconstruction.
- Allow stronger local sharpening inside halo-safe overshoot bounds.
- Prefer higher quality encoder settings and higher bitrate floors where supported.

In low light, high noise, heavy motion, skin-dominant, or encoder-stressed scenes:

- Denoise before sharpening.
- Suppress chroma sharpening and RGB dancing noise.
- Reduce temporal smear risk by lowering temporal blend when motion confidence is poor.
- Clamp sharpening and detail reconstruction before halos, aliasing, or waxy texture appear.
- Record a downgrade reason instead of pretending the maximum path was applied.

## Components

### Detail Profile Contract

Add `EXTREME_DETAIL` to the existing detail-profile model rather than creating an unrelated mode system. The profile should be serializable in settings, visible in metadata, and carried through preview, recording, and export decisions.

### Policy Layer

Update the existing pure Kotlin policies first:

- `VideoDetailProfile`
- `VideoDetailSharpeningPolicy`
- `VideoNoiseReductionPolicy`
- `VideoImageQualityPipelinePolicy`
- `SubjectDetailEnhancementPolicy`, only where subject-aware detail masking already exists
- recording bitrate/encoder policy files, only for capability-gated quality requests
- NPV10/MOV export enhancement policy, only for derived outputs

Each policy should emit deterministic decisions that can be unit tested without a device.

### Vulkan Processed Path

The Vulkan path should remain a guarded processed path:

- Luma detail can be aggressive.
- Chroma detail must remain conservative.
- Flat areas should be denoised rather than sharpened.
- Edges should use local range and overshoot clamps.
- Motion confidence should preserve moving detail without temporal smear.
- Profile-specific tuning should be visible in shader contract tests.

### Recording and Export

Processed recording should request higher quality only when the encoder and runtime path support it. `CUSTOM` bitrate remains user-owned and capability-clamped. `AUTO` and `CINEMA_MASTER` may raise floors for Extreme Detailed Mode, but not beyond validated encoder bounds.

Derived NPV10/MOV export can apply heavier enhancement than real-time recording because it is not latency-bound. Private masters remain untouched, and export metadata must say that enhancement was applied to a derived output only.

## Error Handling and Downgrade Rules

Extreme Detailed Mode should fail soft, not fail silent:

- If GPU processed recording is not validated, record `detailProcessingPath=CAMERA2_FALLBACK` or equivalent.
- If encoder support is insufficient, clamp bitrate/codec decisions and record the clamp.
- If scene metrics indicate high artifact risk, reduce sharpening/reconstruction and record the downgrade reason.
- If RAW/NPV10 private master output is selected, keep the master unmodified and move enhancement to derived export only.
- If metadata or device evidence is missing, mark claims as `NOT_PROVEN` rather than `PASS`.

## Testing

Use test-first implementation for each behavior change.

Focused unit tests should cover:

- `EXTREME_DETAIL` profile serialization, selection, and display formatting.
- Higher clean-scene detail floors than existing Natural/Focused profiles.
- Stronger denoise-before-detail behavior in noisy scenes.
- Halo, chroma-noise, skin, motion, and alias gates preventing uncontrolled sharpening.
- Preview/recording/export metadata parity.
- User custom bitrate ownership remains unchanged.
- Encoder capability clamps still win over requested extreme quality.
- RAW/RAW12 and NPV10 private masters remain unmodified.
- Derived MOV/P010 export can receive heavier enhancement and dither.
- Shader contract strings/coefficients prove the active guarded path, not an unused helper.

Verification should include focused Gradle unit tests, shader/SPIR-V regeneration when shader code changes, debug APK assembly, and device evidence if an ADB target is available.

## Acceptance Criteria

- Extreme Detailed Mode is selectable or internally requestable as a distinct profile.
- Clean stable scenes produce a higher tested detail/reconstruction plan than current high-end profiles.
- Noisy or moving scenes increase cleanup and limit sharpening rather than amplifying noise.
- Processed preview, processed recording, and derived export all carry truthful metadata for the requested/applied profile.
- RAW/RAW12 and NPV10 private master bytes are not enhanced.
- Custom bitrate remains user controlled; automatic quality modes may raise floors only within encoder capability.
- Tests prove artifact gates and truth gates before production code changes are accepted.
- Final reporting avoids unsupported literal IMAX and zero-noise claims unless real captured evidence supports a narrower measured statement.
