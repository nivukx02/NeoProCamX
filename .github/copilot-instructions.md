# Copilot Instructions for NeoProCamX

## Build, test, and lint commands

Use `./gradlew` on macOS/Linux and `.\gradlew.bat` on Windows.

```bash
# verification gates (custom tasks in gradle/build-health.gradle)
./gradlew qualityGateFast
./gradlew qualityGateDebug
./gradlew qualityGateStrict

# unit tests
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.neoproc.neoproccamx.ai.AIHdrMathTest"
./gradlew testDebugUnitTest --tests "com.neoproc.neoproccamx.ai.AIHdrMathTest.acesCurve_mapsInputIntoDisplayRange"

# instrumentation
./gradlew connectedDebugAndroidTest

# lint
./gradlew lint
./gradlew lintDebug
./gradlew lintRelease
./gradlew updateLintBaselineDebug

# builds / packaging
./gradlew assembleDebug
./gradlew packageFlowDebug
./gradlew packageFlowRelease
```

ADB device QA harness (from `tools/README_ADB_CAMERA_QA.md`):

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1
powershell -ExecutionPolicy Bypass -File .\tools\adb_camera_qa.ps1 `
  -Serial <device-id> `
  -CaptureStressCount 20 `
  -LongRunCaptureCount 100 `
  -VideoRecordDurationSec 10
```

## High-level architecture

### Control plane

`ui/MainActivity.kt` is mostly UI/event wiring. `ui/CameraViewModel.kt` is the orchestrator and owns camera, AI, rendering, still capture, RAW video, and recording subsystems. Most cross-subsystem behavior changes should be centered in `CameraViewModel`, not `MainActivity`.

### Live frame pipeline

```text
CameraController (Camera2 + ImageReaders on bgHandler)
  -> FramePipelineManager.enqueue/enqueueHardware
      -> scene stats (histogram/waveform/zebra/peaking) + capture recommendation
      -> AICameraPipeline (stage executors + stale-result fallback)
      -> VulkanRuntimeManager (AHardwareBuffer import via VulkanBridge JNI)
          -> CameraVulkanPreviewView preview
          -> optional encoder surface during recording
```

Two preview paths are managed in `CameraViewModel.bindPreviewSurface()`:
1. Native Vulkan visible preview (`vulkanRuntime.setPreviewSurface`) with watchdog health checks (`VulkanVisiblePreviewPolicy`).
2. Direct Camera2 surface fallback (`camera.setPreviewSurface`) when native import is unhealthy or unavailable.

### Capture and recording paths

- **Still capture** (`capture/StillCapture.kt`) coordinates JPEG/RAW/HDR capture sessions via `StillCaptureSessionCoordinator`, `StillCaptureImagePipeline`, and `StillCaptureOutputStore`.
- **Video recording** (`recording/RecordingManager.kt`) follows `prepare() -> encoder Surface -> camera.setRecordSurface() -> refreshOutputsAndSession() -> start()`.
- **RAW video mode** (`pipeline/RawVideoManager.kt`) hooks into `CameraController.onRawReady`; it does not create a second camera session. Frames are queued and written on a dedicated writer thread.

### Native/GPU layer

- `rendering/VulkanBridge.kt` bridges into `app/src/main/cpp/vulkan_bridge.cpp`.
- HDR bitmap merge is implemented in `app/src/main/cpp/hdr_merge_native.cpp`.
- Vulkan shaders live in `app/src/main/cpp/shaders/vulkan/`.
- Runtime uses prebuilt SPIR-V headers in `app/src/main/cpp/generated/*_spv.h`; keep shader source and generated artifacts in sync when changing shaders.
- Native build is pinned to **NDK 25.1.8937393** and **CMake 3.22.1** (`app/build.gradle` and `app/src/main/cpp/CMakeLists.txt`).

## Key conventions

- **Policy / Math / Resolver split is intentional**:
  - `*Policy.kt`: decision rules from state/config (no Android side effects).
  - `*Math.kt`: pure computation helpers.
  - `*Resolver.kt`: mapping/selection logic for runtime plans.
  This split is heavily unit-tested and should be preserved.

- **Camera callback contracts are extension points**: hook new frame consumers through `CameraController` callbacks (`onYuvFrame`, `onHardwareFrame`, `onJpegReady`, `onRawReady`) instead of creating extra `ImageReader`/session plumbing in other modules.

- **Frame acquisition and ordering conventions**:
  - Preview/analysis flows use `acquireLatestImage()` to drop stale frames.
  - HDR bracket assembly uses `acquireNextImage()` in still capture to preserve burst ordering.
  - Current reader depths in `CameraController`: JPEG=8, RAW=8, YUV=5, HardwareImport=5.

- **Runtime load adaptation is built-in**:
  - `FrameImportRoutingPolicy` controls frame skipping and AI stride multipliers from processing-time signals.
  - `AICameraRuntimePolicy` adjusts AI analysis dimensions/strides/timeouts from preview FPS and recording state.
  Tune these policies first for performance work.

- **Preview/recording parity depends on shared geometry + color pipeline**: keep updates coordinated across `CameraViewModel` geometry sync, preview/analysis size helpers, `ColorScience`, and `VulkanRuntimeManager` LUT/render-config sync.

- **Debug automation is first-class**:
  - `ui/DebugCommandReceiver.kt` handles `com.neoproc.neoproccamx.DEBUG_COMMAND` in debug builds.
  - `tools/adb_camera_qa.ps1` depends on that receiver for automated capture/recording scenarios.

- **Test layout mirrors production packages** (`app/src/test/java/com/neoproc/neoproccamx/...`) and uses JUnit4 with Android mock defaults (`returnDefaultValues = true` in `app/build.gradle`).

## MCP server setup for this repo

- **GitHub MCP** is bundled with Copilot CLI; verify it with `/mcp`.
- **Android ADB/device MCP** should be added in Copilot CLI with `/mcp add` using your installed ADB MCP server binary.
  - Recommended server name: `android-adb`
  - Set required environment values in the MCP server config: `ANDROID_HOME` and `ANDROID_SDK_ROOT`
  - Keep MCP config out of this repository (Copilot stores it in user config, not project files)
