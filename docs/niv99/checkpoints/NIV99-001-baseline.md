# NIV99-001 — Baseline and source fingerprint

- Captured: 2026-08-28T09:07:38.0736689+05:30
- Branch: `checkpoint/elite-color-stability`
- HEAD: `58cff7999ba640d25345f689e25b6dd43bfa7ec0`
- Working tree at capture: 563 modified, 11 deleted, 618 untracked paths
- Status: baseline recorded; no quality or performance superiority claim established

The hashes below fingerprint the working-copy inputs inspected before NIV99 edits. They intentionally
describe the dirty working copy rather than pretending that `HEAD` alone is the active baseline.

| SHA-256 | Working-copy path |
|---|---|
| `791c0e14f2cb77bef1608cfa95e809367ae4e89ac6a7761b2e3bcec63d50a817` | `CameraAwbConfidencePolicy.kt` |
| `5611023758b2060b45962ba70dde85b96abf0e5986ee45fe130d8d8351ed9139` | `CinemaAutoWhiteBalanceRequestPolicy.kt` |
| `112c4b84ec311401e7cadc05f132b9c09a639d87739594fba9ec2016ed5ebb14` | `CinemaAutoWhiteBalanceRegionPolicy.kt` |
| `2d5fd49f74899838d7043a535715e434e413024857c6533458d714ebb32362be` | `CameraSensorNoiseModelPolicy.kt` |
| `bb75cead29718a04f384efae6aa3a6809ef7698004fb2e0e909781381ad88a07` | `DngWhiteBalanceMath.kt` |
| `ebbc8bdca3989863ec7254d13d197cfafc61fa02d5c8a7203ad1329dfe03516f` | `VideoColorTemporalStabilityPolicy.kt` |
| `c47659f465e3c37fb44e687243f7bdeb2e14aa879ce6af36c6e95c61f4f615ce` | `RawPreviewColorMetadataStabilizer.kt` |
| `a6d8a04689bd30a9c5fdba95e60135ce6e5df809ae1d27990306daa1756d7dfa` | `VideoNoiseReductionPolicy.kt` |
| `8587e6b689235b0c4ff52ddadb1f406d3e426bcd2862c20c385da10382b3643e` | `CinematicNoiseReductionPass.kt` |
| `ddf20794270b7b7e968b94d571cc6c1fee5413eef61cec2d668e96bd54cf9c27` | `AiNoiseReductionPolicy.kt` |
| `fe67addd0c7f1856a3c8230aaa3effb844f64057022eed29c44de24ca21542d5` | `NeuralDenoiseEngine.kt` |
| `797d2d1ff9c37b119f5ba16d806da8ba2d1b3e299beb3bf606f43a8fa7c9c9ea` | `TemporalFrameMetadata.kt` |
| `bca817d6a42ab3b28a2d484fe8afd6b2d23741864a6188920ea51a24a90b6bf5` | `ChronosTemporalPolicy.kt` |
| `35d9c2181784a5c82b5bda505f44071f4739d5af21bbbcaac0eed9cc48ca4ebe` | `vulkan_bridge.cpp` |
| `438caaf1ce78193f8774accb091531085bd736227d4c06938b57e89bf1aeeb25` | `offscreen_egl_encoder_bridge.cpp` |
| `52193015b7fe783838a0f25ccb92137c06cf7bc033974309f2c8371563e7b9c4` | `shader_manifest.json` |

## Baseline conclusions

1. Existing RAW recording ownership and bounded queues must remain authoritative.
2. Existing manual WB and AWB-lock resolution must remain above NIV99 automatic authority.
3. Existing generated SPIR-V files are never direct edit targets.
4. Baseline quality/performance measurements are not present for NIV99; superiority is unproven.
5. Physical-device and vendor comparisons remain blocked until host gates pass and a controlled device rig is available.

