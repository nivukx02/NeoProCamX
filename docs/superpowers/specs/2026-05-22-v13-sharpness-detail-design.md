# V13 Sharpness Detail Upgrade Design

## Goal

Upgrade visible sharpness and fine detail in normal preview, normal recording, and NPV10 MOV export without fake resolution claims, global oversharpening, halo edges, or sharpening RGB/chroma noise.

## Design

V13 adds a measured detail layer on top of the existing V12 quality gate. The policy computes separate floors for edge micro-contrast, fine texture detail, halo-safe sharpening lift, and preview/recording detail parity. These values only rise when clean-detail evidence, texture-edge confidence, chroma-edge separation, and sensor noise gates allow it.

Preview and recording continue to use `SceneRenderProfileEngine` as the shared runtime path. V13 raises the detail floor in clean scenes and keeps noisy scenes bounded by the existing halo-safe sharpening ceiling.

NPV10 export keeps the private master unchanged. The MOV conversion path applies stronger guarded P010 luma detail lift only during export and records certificate flags for the V13 detail pass.

## Files

- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`

## Acceptance

- Clean detailed scenes get higher measured detail and sharpening floors.
- Noisy low-light scenes keep conservative sharpening ceilings.
- Preview and recording both receive the V13 detail lift through the shared render profile.
- NPV10 MOV export lifts real edges more strongly while flat texture remains unchanged.
- NPV10 private masters remain untouched.
- Focused tests and full debug build pass.
