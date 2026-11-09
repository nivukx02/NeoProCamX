#!/usr/bin/env python3
"""Host-side settings source audit and deterministic contract-state simulation.

The source audit checks that each listed setting has a UI callback, typed metadata, a ViewModel entry
point, declared persistence ownership, static runtime-effect evidence, and no known blocking primitive
in its entry point. The state model then applies every declared option value and verifies state mutation,
persistence projection, and effect-channel dispatch for each transition.

This module does not execute Kotlin, Android, Camera2, a codec, a GPU, or a sensor. It cannot measure
activation latency or prove physical-device behavior; those remain explicit ADB/device gates.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Iterable


class ActivationClass(str, Enum):
    IMMEDIATE_STATE = "IMMEDIATE_STATE"
    CAMERA_REQUEST = "CAMERA_REQUEST"
    SESSION_RECONFIGURATION = "SESSION_RECONFIGURATION"
    NEXT_CAPTURE = "NEXT_CAPTURE"
    NEXT_RECORDING = "NEXT_RECORDING"


@dataclass(frozen=True)
class Evidence:
    relative_path: str
    token: str


@dataclass(frozen=True)
class SettingContract:
    setting_id: str
    title: str
    section: str
    option_type: str
    values: tuple[str, ...]
    handler: str
    ui_token: str
    activation: ActivationClass
    preference_keys: tuple[str, ...]
    runtime_evidence: tuple[Evidence, ...]
    conditional: bool = False
    max_dispatch_latency_ms: int = 1_000


@dataclass(frozen=True)
class DispatchEvent:
    sequence: int
    setting_id: str
    value: str
    activation: ActivationClass


@dataclass(frozen=True)
class TransitionResult:
    sequence: int
    setting_id: str
    value: str
    previous_value: str | None
    state_changed: bool
    preference_updates: tuple[tuple[str, object], ...]
    dispatch_event: DispatchEvent


def _project_preference_value(setting: SettingContract, key: str, value: str) -> object:
    """Project UI labels into the semantic values owned by the declared preference keys."""
    if setting.setting_id == "video.manualBitrate":
        return "CUSTOM" if key == "KEY_VIDEO_BITRATE_MODE" else int(value) * 1_000_000
    if setting.setting_id == "video.rawEnabled":
        if key == "KEY_RAW_ENABLED":
            return value == "true"
        if key == "KEY_VIDEO_MODE":
            return "RAW" if value == "true" else "PRO_OR_LOG_DEPENDING_ON_LOG_STATE"
    if key in {"KEY_VIDEO_COLOR_SPACE_MANUAL", "KEY_VIDEO_COLOR_SCIENCE_MANUAL"}:
        return True
    if key == "KEY_CUSTOM_RECORDING_NAME_ENABLED":
        return bool(value.strip())
    if setting.option_type == "BOOLEAN":
        return value == "true"
    return value


class SettingsStateModel:
    """Deterministic host model of declared setting state, persistence, and dispatch semantics."""

    def __init__(self) -> None:
        self.state: dict[str, str] = {}
        self.preferences: dict[str, object] = {}
        self.dispatch_queues: dict[ActivationClass, list[DispatchEvent]] = {
            activation: [] for activation in ActivationClass
        }
        self.transitions: list[TransitionResult] = []

    def apply(self, setting: SettingContract, value: str) -> TransitionResult:
        if value not in setting.values:
            raise ValueError(f"{setting.setting_id} rejects undeclared value {value!r}")

        previous = self.state.get(setting.setting_id)
        self.state[setting.setting_id] = value
        preference_updates = tuple(
            (key, _project_preference_value(setting, key, value))
            for key in setting.preference_keys
        )
        for key, projected_value in preference_updates:
            self.preferences[key] = projected_value

        event = DispatchEvent(
            sequence=len(self.transitions) + 1,
            setting_id=setting.setting_id,
            value=value,
            activation=setting.activation,
        )
        self.dispatch_queues[setting.activation].append(event)
        result = TransitionResult(
            sequence=event.sequence,
            setting_id=setting.setting_id,
            value=value,
            previous_value=previous,
            state_changed=previous != value,
            preference_updates=preference_updates,
            dispatch_event=event,
        )
        self.transitions.append(result)
        return result


def ev(path: str, token: str) -> Evidence:
    return Evidence(path, token)


VM = "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
SETTINGS_CONTROLLER = "app/src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt"
CAMERA_CONTROLLER = "app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
SECTION_FACTORY = "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
OPTION_CATALOG = "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt"
OPTION_META = "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionMeta.kt"
PREFERENCES = "app/src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt"


SETTINGS: tuple[SettingContract, ...] = (
    SettingContract(
        "camera.previewAspect", "Preview Aspect", "CAMERA", "ENUM",
        ("SQUARE", "FOUR_THREE", "SIXTEEN_NINE"), "setPreviewAspectRatio",
        "onSelect = viewModel::setPreviewAspectRatio", ActivationClass.IMMEDIATE_STATE,
        ("KEY_PREVIEW_ASPECT",),
        (ev(VM, "syncPreviewAspectPresentationTargets()"),
         ev(VM, "prefs.previewAspectRatio = resolvedAspect")),
    ),
    SettingContract(
        "camera.photoQuality", "Photo Quality", "CAMERA", "BOOLEAN",
        ("true", "false"), "setMaximumPhotoResolutionEnabled",
        "onSelect = viewModel::setMaximumPhotoResolutionEnabled", ActivationClass.NEXT_CAPTURE,
        ("KEY_MAX_PHOTO_RESOLUTION",),
        (ev(VM, "prefs.maxPhotoResolutionEnabled = enabled"),
         ev(VM, "camera.maximumResolutionStillEnabled = enabled")),
    ),
    SettingContract(
        "camera.lens", "Lens", "CAMERA", "ENUM", ("BACK", "FRONT"), "switchFacing",
        "viewModel.switchFacing()", ActivationClass.SESSION_RECONFIGURATION, (),
        (ev(VM, "CameraFacingSwitchPolicy.buildPlan"), ev(VM, "camera.switchFacing()"),
         ev(VM, "runOnMain { openCamera() }")),
    ),
    SettingContract(
        "camera.flash", "Flash", "CAMERA", "ENUM", ("OFF", "AUTO", "ON", "TORCH"),
        "setFlash", "onSelect = viewModel::setFlash", ActivationClass.CAMERA_REQUEST, (),
        (ev(VM, "fun setFlash(f: FlashMode)  = camera.setFlash(f)"),
         ev(CAMERA_CONTROLLER, "override fun setFlash(mode: FlashMode)")),
    ),
    SettingContract(
        "camera.aiMotionCompensation", "AI Motion Compensation", "CAMERA", "BOOLEAN",
        ("true", "false"), "setAiMotionCompensationEnabled",
        "onToggle = viewModel::setAiMotionCompensationEnabled", ActivationClass.IMMEDIATE_STATE,
        ("KEY_AI_MOTION_COMPENSATION",),
        (ev(SETTINGS_CONTROLLER, "pipelineManager.setAiMotionCompensationEnabled(enabled)"),
         ev("app/src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt",
            "_aiMotionCompensationEnabled.value = enabled"),
         ev(VM, "pipeline.aiMotionCompensationEnabled"),
         ev(VM, "aiMotionCompensationEnabled = values[16] as? Boolean ?: false")),
    ),
    SettingContract(
        "camera.noiseReduction", "Noise Reduction", "CAMERA", "BOOLEAN", ("true", "false"),
        "setNoiseReductionEnabled", "onToggle = viewModel::setNoiseReductionEnabled",
        ActivationClass.CAMERA_REQUEST, ("KEY_NOISE_REDUCTION",),
        (ev(SETTINGS_CONTROLLER, "cameraController.setNoiseReductionEnabled(enabled)"),
         ev(CAMERA_CONTROLLER, "noiseReductionEnabled = enabled"),
         ev("app/src/main/java/com/neoproc/neoproccamx/camera/CameraCineProcessingPolicy.kt",
            "noiseReductionMode = if (noiseReductionEnabled)")),
    ),
    SettingContract(
        "video.resolution", "Resolution", "VIDEO", "ENUM", ("8K", "4K", "4K 4:3", "1K"),
        "setVideoResolution", "viewModel.setVideoResolution(size)",
        ActivationClass.SESSION_RECONFIGURATION, ("KEY_VIDEO_RESOLUTION",),
        (ev(VM, "prefs.videoSize = resolvedSelection.requestedSize"),
         ev(VM, "refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)")),
    ),
    SettingContract(
        "video.frameRate", "Frame Rate", "VIDEO", "ENUM", ("24", "25", "30", "48", "50", "60"),
        "setFPS", "onSelect = viewModel::setFPS", ActivationClass.SESSION_RECONFIGURATION,
        ("KEY_VIDEO_FPS",),
        (ev(VM, "prefs.videoFps = frameRatePlan.encoderFps"),
         ev(VM, "syncActivePreviewFrameRate()")),
    ),
    SettingContract(
        "video.codec", "Codec", "VIDEO", "ENUM", ("HEVC",), "setVideoCodec",
        "onSelect = viewModel::setVideoCodec", ActivationClass.SESSION_RECONFIGURATION,
        ("KEY_VIDEO_CODEC",),
        (ev(VM, "prefs.videoCodec = recording.codec.name"),
         ev(VM, "selectedVideoSize?.let { setVideoResolution(it) }")),
    ),
    SettingContract(
        "video.hdrMode", "XDR Mode", "VIDEO", "ENUM", ("OFF", "NDR10+", "NIVUKXVISION"),
        "setVideoHdrMode", "onSelect = viewModel::setVideoHdrMode",
        ActivationClass.SESSION_RECONFIGURATION, ("KEY_VIDEO_HDR_MODE",),
        (ev(VM, "prefs.videoHdrMode = normalized"),
         ev(VM, "pushVulkanPreviewRenderConfig(force = true)"),
         ev(VM, "refreshSessionForModeSwitchIfIdle(")), conditional=True,
    ),
    SettingContract(
        "video.bitrateMode", "Bitrate Mode", "VIDEO", "ENUM", ("AUTO", "CINEMA_MASTER", "CUSTOM"),
        "setVideoBitrateMode", "onSelect = viewModel::setVideoBitrateMode",
        ActivationClass.NEXT_RECORDING, ("KEY_VIDEO_BITRATE_MODE",),
        (ev(VM, "prefs.videoBitrateMode = mode"), ev(VM, "syncRecordingBitrateMode(mode)")),
    ),
    SettingContract(
        "video.manualBitrate", "Manual Bitrate", "VIDEO", "INT_RANGE",
        ("20", "35", "50", "80", "120", "160", "180", "200", "240", "300", "450",
         "600", "800", "820", "1000", "1200", "1600", "1968", "2000"),
        "setVideoBitrate", "viewModel.setVideoBitrate(mbps * 1_000_000)",
        ActivationClass.NEXT_RECORDING, ("KEY_VIDEO_BITRATE", "KEY_VIDEO_BITRATE_MODE"),
        (ev(VM, "prefs.videoBitrateMode = VideoBitrateMode.CUSTOM"),
         ev(VM, "applyVideoBitrateValue(bps)")),
    ),
    SettingContract(
        "video.rawEnabled", "Video RAW", "VIDEO", "BOOLEAN", ("true", "false"), "setRawEnabled",
        "onToggle = viewModel::setRawEnabled", ActivationClass.SESSION_RECONFIGURATION,
        ("KEY_RAW_ENABLED", "KEY_VIDEO_MODE"),
        (ev(VM, "if (applyCinemaModeSelection(modeTarget))"),
         ev(VM, "modeController.setCinemaVideoMode(modeTarget)")), conditional=True,
    ),
    SettingContract(
        "video.cinemaMode", "Video Format Engine", "VIDEO", "ENUM", ("PRO", "RAW"),
        "setCinemaVideoMode", "onSelect = viewModel::setCinemaVideoMode",
        ActivationClass.SESSION_RECONFIGURATION, ("KEY_VIDEO_MODE",),
        (ev(VM, "resolveCinemaModeAdmission("), ev(VM, "applyCinemaModeSelection(admittedMode)")),
    ),
    SettingContract(
        "video.rawResolution", "RAW Resolution", "VIDEO", "ENUM",
        ("AUTO", "MAXIMUM_SENSOR", "FULL_ACTIVE_ARRAY"), "setRawResolutionMode",
        "onSelect = viewModel::setRawResolutionMode", ActivationClass.NEXT_RECORDING,
        ("KEY_RAW_RESOLUTION_MODE",),
        (ev(VM, "_rawResolutionMode.value = mode"), ev(VM, "prefs.rawResolutionMode = mode")),
        conditional=True,
    ),
    SettingContract(
        "video.rawCompression", "RAW Compression", "VIDEO", "ENUM", ("HIGH_COMPRESSION",),
        "setRawVideoCompressionMode", "onSelect = viewModel::setRawVideoCompressionMode",
        ActivationClass.NEXT_RECORDING, ("KEY_RAW_VIDEO_COMPRESSION",),
        (ev(VM, "rawVideoManager.compressionMode = resolvedMode"),
         ev(VM, "prefs.rawVideoCompression = resolvedMode.name")), conditional=True,
    ),
    SettingContract(
        "videoColor.colorSpace", "Color Space", "VIDEO_COLOR", "ENUM",
        ("Rec.709", "Rec.2020", "Rec.2020-N-Log", "Rec.2020-NDR10+", "Rec.2020-NIVUKX",
         "Neo Wide Gamut", "NeoGamut APEX", "Neo ACES AP1", "Neo ACES AP0"),
        "setVideoColorSpace", "onSelect = viewModel::setVideoColorSpace",
        ActivationClass.SESSION_RECONFIGURATION,
        ("KEY_VIDEO_COLOR_SPACE", "KEY_VIDEO_COLOR_SPACE_MANUAL"),
        (ev(VM, "prefs.setManualVideoColorSpace(effectiveColorSpace)"),
         ev(VM, "gpuRecording.videoColorSpace = effectiveColorSpace"),
         ev(VM, "refreshPreviewPipelineIfNeeded(forceSessionRefresh = true)")),
    ),
    SettingContract(
        "videoColor.colorScience", "Color Science", "VIDEO_COLOR", "ENUM",
        ("Natural", "Rec.709", "Rec.2020", "Neo ACES High End", "Neo Wide Gamut", "NeoGamut APEX",
         "NDR10+", "NIVUKX Vision", "N-Log Base", "N-Log Shadow", "N-Log Highlight", "N-Log Cinema"),
        "setColorProfile", "onSelect = viewModel::setColorProfile", ActivationClass.IMMEDIATE_STATE,
        ("KEY_COLOR_PROFILE", "KEY_VIDEO_COLOR_SCIENCE_MANUAL"),
        (ev(VM, "prefs.setManualVideoColorScience(normalized)"),
         ev(VM, "colorScience.loadBuiltInProfile(normalized)"),
         ev(VM, "pushVulkanPreviewRenderConfig(force = true)")),
    ),
    SettingContract(
        "videoColor.detailProfile", "Detail Profile", "VIDEO_COLOR", "ENUM",
        ("FOCUSED_DETAIL", "NATURAL_DETAIL", "EXTREME_DETAIL"), "setVideoDetailProfile",
        "onSelect = viewModel::setVideoDetailProfile", ActivationClass.CAMERA_REQUEST,
        ("KEY_VIDEO_DETAIL_PROFILE",),
        (ev(SETTINGS_CONTROLLER, "prefs.videoDetailProfile = profile"),
         ev(VM, "camera.setVideoDetailProfile(profile)"),
         ev(VM, "pushVulkanPreviewRenderConfig(force = true)")),
    ),
    SettingContract(
        "focus.eyeAutofocus", "Eye Autofocus", "FOCUS", "BOOLEAN", ("true", "false"),
        "setEyeAutofocusEnabled", "onToggle = viewModel::setEyeAutofocusEnabled",
        ActivationClass.CAMERA_REQUEST, ("KEY_EYE_AF",),
        (ev(SETTINGS_CONTROLLER, "prefs.eyeAutofocusEnabled = enabled"),
         ev(VM, "applyAiTrackingPreferences()"),
         ev("app/src/main/java/com/neoproc/neoproccamx/ui/RealtimeAiLoadPolicy.kt",
            "eyeTrackingEnabled = eyeTrackingEnabled")),
    ),
    SettingContract(
        "system.gridMode", "Grid Type", "SYSTEM", "ENUM", ("NONE", "THIRDS", "CENTER"),
        "setGridMode", "onSelect = viewModel::setGridMode", ActivationClass.IMMEDIATE_STATE,
        ("KEY_GRID_MODE",),
        (ev(SETTINGS_CONTROLLER, "prefs.gridMode = mode.name"),
         ev(SETTINGS_CONTROLLER, "it.copy(gridMode = mode)"), ev(VM, "_gridMode.value = state.gridMode")),
    ),
    SettingContract(
        "system.framingGuides", "Framing Guides", "SYSTEM", "BOOLEAN", ("true", "false"),
        "setSafeAreaGuidesEnabled", "onToggle = viewModel::setSafeAreaGuidesEnabled",
        ActivationClass.IMMEDIATE_STATE, ("KEY_SAFE_AREA_GUIDES",),
        (ev(VM, "prefs.safeAreaGuidesEnabled = enabled"), ev(VM, "_safeAreaGuides.value = enabled")),
    ),
    SettingContract(
        "system.stabilization", "Stabilization", "SYSTEM", "ENUM", ("OIS", "EIS", "HYBRID", "OFF"),
        "setStabilizationMode", "viewModel.setStabilizationMode(mode)",
        ActivationClass.CAMERA_REQUEST, ("KEY_STABILIZATION_MODE",),
        (ev(VM, "prefs.stabilizationMode = mode"), ev(VM, "camera.setStabilizationMode(mode)"),
         ev(CAMERA_CONTROLLER, "stabilizationMode = mode")),
    ),
    SettingContract(
        "system.audio", "Audio", "SYSTEM", "ENUM", ("CAMCORDER", "BUILTIN_MIC", "UNPROCESSED", "NONE"),
        "setAudioSourceIndex", "onSelect = viewModel::setAudioSourceIndex",
        ActivationClass.NEXT_RECORDING, ("KEY_AUDIO_SOURCE",),
        (ev(VM, "setAudioSrc(CameraPreferenceValuePolicy.audioSourceFromIndex(idx))"),
         ev(VM, "recording.audioSource = resolvedSource"), ev(VM, "prefs.audioSource = when")),
    ),
    SettingContract(
        "system.customRecordingNameEnabled", "Custom Recording Name", "SYSTEM", "BOOLEAN",
        ("true", "false"), "setCustomRecordingNameEnabled",
        "viewModel.setCustomRecordingNameEnabled(enabled)", ActivationClass.NEXT_RECORDING,
        ("KEY_CUSTOM_RECORDING_NAME_ENABLED",),
        (ev(VM, "prefs.customRecordingNameEnabled = enabled"),),
    ),
    SettingContract(
        "system.customRecordingName", "Recording Name", "SYSTEM", "TEXT", ("", "Cinema Take", "x" * 64),
        "setCustomRecordingName", "onValue = viewModel::setCustomRecordingName",
        ActivationClass.NEXT_RECORDING,
        ("KEY_CUSTOM_RECORDING_NAME", "KEY_CUSTOM_RECORDING_NAME_ENABLED"),
        (ev(VM, "RecordingFileNamePolicy.sanitizeCustomName(name)"),
         ev(VM, "prefs.customRecordingName = sanitized"),
         ev(VM, "prefs.customRecordingNameEnabled = sanitized.isNotEmpty()")), conditional=True,
    ),
)


CONVERTER_ENDPOINTS: tuple[Evidence, ...] = (
    ev(SECTION_FACTORY, "viewModel.convertRawClipToMov"),
    ev(SECTION_FACTORY, "viewModel.convertRawClipToCinemaDng"),
    ev(SECTION_FACTORY, "viewModel.deleteRawClip"),
    ev(SECTION_FACTORY, "viewModel.convertNpv10MasterToMov"),
    ev(SECTION_FACTORY, "viewModel.deleteNpv10Master"),
    ev(SECTION_FACTORY, "viewModel.convertRecordDevelopMaster"),
    ev(SECTION_FACTORY, "viewModel.deleteRecordDevelopMaster"),
)

SETTINGS_ACTION_ENDPOINTS: tuple[Evidence, ...] = (
    ev(SECTION_FACTORY, "onClick = openLutImporter"),
)

MINIMUM_CONVERTER_ACTION_CHOICE_SITES = 17
MINIMUM_CONVERTER_EXPANDED_BRANCHES = 27
EXPECTED_CONVERTER_THUMBNAIL_BINDINGS = 4


BLOCKING_TOKENS = (
    "Thread.sleep(",
    "runBlocking",
    "CountDownLatch",
    "awaitTermination(",
    "Future.get(",
    ".join()",
)


@dataclass(frozen=True)
class MetaEntry:
    setting_id: str
    title: str
    section: str
    option_type: str
    default_label: str
    handler: str
    activation: str
    preference_keys: tuple[str, ...]
    allowed_values: tuple[str, ...]
    max_dispatch_latency_ms: int


@dataclass(frozen=True)
class UiControlSite:
    title: str
    kind: str
    source: str


def _read(root: Path, relative_path: str) -> str:
    return (root / relative_path).read_text(encoding="utf-8")


def _balanced_call_segments(source: str, marker: str) -> Iterable[str]:
    """Yield balanced marker(...) calls with Kotlin strings and comments lexically masked."""
    cursor = 0
    while True:
        start = source.find(marker, cursor)
        if start < 0:
            return
        open_index = start + len(marker) - 1
        depth = 0
        quote: str | None = None
        escaped = False
        line_comment = False
        block_comment_depth = 0
        index = open_index
        while index < len(source):
            char = source[index]
            if line_comment:
                if char in "\r\n":
                    line_comment = False
                index += 1
                continue
            if block_comment_depth > 0:
                if source.startswith("/*", index):
                    block_comment_depth += 1
                    index += 2
                elif source.startswith("*/", index):
                    block_comment_depth -= 1
                    index += 2
                else:
                    index += 1
                continue
            if quote == '"""':
                if source.startswith('"""', index):
                    quote = None
                    index += 3
                else:
                    index += 1
                continue
            if quote is not None:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
                index += 1
                continue
            if source.startswith("//", index):
                line_comment = True
                index += 2
            elif source.startswith("/*", index):
                block_comment_depth = 1
                index += 2
            elif source.startswith('"""', index):
                quote = '"""'
                index += 3
            elif char in ('"', "'"):
                quote = char
                index += 1
            elif char == "(":
                depth += 1
                index += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    yield source[start:index + 1]
                    cursor = index + 1
                    break
                index += 1
            else:
                index += 1
        else:
            raise ValueError(f"unbalanced Kotlin call beginning at {start}")


def parse_bitrate_step_labels(source: str) -> tuple[str, ...]:
    match = re.search(
        r"bitrateStepsMbps:\s*List<Int>\s*=\s*listOf\((.*?)\)",
        source,
        re.DOTALL,
    )
    if match is None:
        raise ValueError("SettingsOptionCatalog is missing bitrateStepsMbps")
    return tuple(
        str(int(token.replace("_", "")))
        for token in re.findall(r"\b[0-9][0-9_]*\b", match.group(1))
    )


def parse_meta_catalog(
    source: str,
    option_catalog_source: str | None = None,
) -> dict[str, MetaEntry]:
    entries: dict[str, MetaEntry] = {}
    for block in _balanced_call_segments(source, "SettingsOptionMeta("):
        setting_match = re.search(r'\bid\s*=\s*"([^"]+)"', block)
        if setting_match is None:
            continue
        setting_id = setting_match.group(1)

        def required(pattern: str, name: str) -> str:
            match = re.search(pattern, block, re.DOTALL)
            if match is None:
                raise ValueError(f"{setting_id} is missing {name}")
            return match.group(1)

        allowed_match = re.search(r"allowedLabels\s*=\s*listOf\((.*?)\)", block, re.DOTALL)
        if allowed_match:
            allowed = tuple(re.findall(r'"([^"]*)"', allowed_match.group(1)))
        elif "allowedLabels = BOOLEAN_LABELS" in block:
            allowed = ("true", "false")
        elif "allowedLabels = SettingsOptionCatalog.bitrateStepLabels" in block:
            if option_catalog_source is None:
                raise ValueError("bitrate step metadata requires SettingsOptionCatalog source")
            allowed = parse_bitrate_step_labels(option_catalog_source)
        else:
            allowed = ()
        deadline_match = re.search(r"maxDispatchLatencyMs\s*=\s*([0-9_]+)", block)
        deadline = int(deadline_match.group(1).replace("_", "")) if deadline_match else 1_000
        entry = MetaEntry(
            setting_id=setting_id,
            title=required(r'\btitle\s*=\s*"([^"]+)"', "title"),
            section=required(r"section\s*=\s*SettingsSectionKey\.([A-Z_]+)", "section"),
            option_type=required(r"type\s*=\s*SettingsOptionType\.([A-Z_]+)", "type"),
            default_label=required(r'\bdefaultLabel\s*=\s*"([^"]*)"', "default"),
            handler=required(r'handlerName\s*=\s*"([^"]+)"', "handler"),
            activation=required(r"activationClass\s*=\s*SettingsActivationClass\.([A-Z_]+)", "activation"),
            preference_keys=tuple(re.findall(r"PreferencesManager\.(KEY_[A-Z0-9_]+)", block)),
            allowed_values=allowed,
            max_dispatch_latency_ms=deadline,
        )
        if setting_id in entries:
            raise ValueError(f"duplicate settings metadata id {setting_id}")
        entries[setting_id] = entry
    return entries


def extract_kotlin_function(source: str, function_name: str) -> str:
    """Extract one class-level Kotlin function through the next peer declaration."""
    pattern = re.compile(
        rf"(?m)^    (?:(?:public|private|internal|protected|override|suspend|inline|operator|open)\s+)*"
        rf"fun\s+{re.escape(function_name)}\s*\("
    )
    match = pattern.search(source)
    if match is None:
        raise ValueError(f"missing CameraViewModel handler {function_name}")
    next_declaration = re.compile(
        r"(?m)^    (?:(?:public|private|internal|protected|override|suspend|inline|operator|open)\s+)*"
        r"(?:fun|val|var|class|object|interface)\s+"
    ).search(source, match.end())
    return source[match.start(): next_declaration.start() if next_declaration else len(source)]


def parse_interactive_ui_controls(
    source: str,
    preference_source: str,
) -> tuple[UiControlSite, ...]:
    """Discover every potential typed control directly from SettingsSectionFactory call sites."""
    sites: list[UiControlSite] = []
    markers = {
        "selector": "uiFactory.selectorRow(",
        "toggle": "uiFactory.toggleRow(",
        "text": "uiFactory.textInputRow(",
    }
    enhancement_hidden = "AI_ENHANCEMENT_SETTINGS_ENABLED = false" in preference_source
    for kind, marker in markers.items():
        for block in _balanced_call_segments(source, marker):
            title_match = re.search(r'\btitle\s*=\s*"([^"]+)"', block)
            if title_match is None:
                raise ValueError(f"{kind} control is missing a literal title")
            title = title_match.group(1)
            if title == "AI Enhancement" and enhancement_hidden:
                continue
            sites.append(UiControlSite(title=title, kind=kind, source=block))

    manual_title = 'title = "Manual Bitrate"'
    manual_start = source.find(manual_title)
    manual_end_token = "bitrateCard.addView(bitrateSeekBar)"
    manual_end = source.find(manual_end_token, manual_start)
    if manual_start < 0 or manual_end < 0:
        raise ValueError("manual bitrate SeekBar control is missing")
    sites.append(
        UiControlSite(
            title="Manual Bitrate",
            kind="discrete_seekbar",
            source=source[manual_start:manual_end + len(manual_end_token)],
        )
    )
    return tuple(sites)


def inspect_converter_action_structure(source: str) -> dict[str, int]:
    """Count static action sites and the branches generated by their four mapped option lists."""
    choice_sites = source.count("SettingsUiFactory.SettingsActionChoice(")
    mapped_lists = (
        ("chooseRawLogLook", "val looks = listOf(", " to "),
        (
            "chooseRawLogFormat",
            "val formats = listOf(",
            "RawToNeoLogMovConverter.OutputFormat.",
        ),
        (
            "chooseNpv10Format",
            "val formats = listOf(",
            "Npv10MovConverter.OutputFormat.",
        ),
        (
            "chooseRecordDevelopFormat",
            "val formats = listOf(",
            "RecordDevelopDeliveryFormat.",
        ),
    )
    expanded_mapped_branches = 0
    for function_name, marker, entry_token in mapped_lists:
        function_source = extract_kotlin_function(source, function_name)
        try:
            list_source = next(iter(_balanced_call_segments(function_source, marker)))
        except StopIteration as error:
            raise ValueError(f"{function_name} is missing its mapped action list") from error
        entries = list_source.count(entry_token)
        if entries <= 0:
            raise ValueError(f"{function_name} has no mapped action entries")
        expanded_mapped_branches += entries

    mapped_constructor_sites = len(mapped_lists)
    expanded_branches = choice_sites - mapped_constructor_sites + expanded_mapped_branches
    return {
        "action_choice_call_sites": choice_sites,
        "expanded_action_branches": expanded_branches,
        "thumbnail_action_bindings": source.count("onThumbnailClick ="),
    }


def run_settings_activation_audit(root: Path) -> dict[str, object]:
    root = root.resolve()
    texts: dict[str, str] = {}

    def source(path: str) -> str:
        if path not in texts:
            texts[path] = _read(root, path)
        return texts[path]

    violations: list[str] = []
    meta_entries = parse_meta_catalog(source(OPTION_META), source(OPTION_CATALOG))
    expected_ids = {setting.setting_id for setting in SETTINGS}
    actual_ids = set(meta_entries)
    if expected_ids != actual_ids:
        violations.append(
            f"metadata ids differ: missing={sorted(expected_ids - actual_ids)} extra={sorted(actual_ids - expected_ids)}"
        )

    ui_source = source(SECTION_FACTORY)
    vm_source = source(VM)
    preference_source = source(PREFERENCES)
    try:
        ui_control_sites = parse_interactive_ui_controls(ui_source, preference_source)
    except ValueError as error:
        violations.append(str(error))
        ui_control_sites = ()
    ui_controls_by_title: dict[str, UiControlSite] = {}
    for site in ui_control_sites:
        if site.title in ui_controls_by_title:
            violations.append(f"duplicate interactive UI title {site.title!r}")
        else:
            ui_controls_by_title[site.title] = site
    expected_titles = {setting.title for setting in SETTINGS}
    actual_titles = set(ui_controls_by_title)
    if expected_titles != actual_titles:
        violations.append(
            f"interactive UI titles differ: missing={sorted(expected_titles - actual_titles)} "
            f"extra={sorted(actual_titles - expected_titles)}"
        )
    try:
        converter_structure = inspect_converter_action_structure(ui_source)
    except ValueError as error:
        violations.append(str(error))
        converter_structure = {
            "action_choice_call_sites": 0,
            "expanded_action_branches": 0,
            "thumbnail_action_bindings": 0,
        }
    # Converter actions are additive. Exact historical totals made the host gate fail whenever a
    # legitimate new conversion route was added, even though every required endpoint remained wired.
    # Guard the proven baseline as a floor, keep the four thumbnail bindings exact, and let endpoint
    # token checks below establish that the required production actions are still present.
    converter_structure_floor = {
        "action_choice_call_sites": MINIMUM_CONVERTER_ACTION_CHOICE_SITES,
        "expanded_action_branches": MINIMUM_CONVERTER_EXPANDED_BRANCHES,
        "thumbnail_action_bindings": EXPECTED_CONVERTER_THUMBNAIL_BINDINGS,
    }
    converter_structure_regressed = (
        converter_structure["action_choice_call_sites"] < MINIMUM_CONVERTER_ACTION_CHOICE_SITES
        or converter_structure["expanded_action_branches"] < MINIMUM_CONVERTER_EXPANDED_BRANCHES
        or converter_structure["thumbnail_action_bindings"]
        != EXPECTED_CONVERTER_THUMBNAIL_BINDINGS
    )
    if converter_structure_regressed:
        violations.append(
            f"converter action structure floor={converter_structure_floor} "
            f"actual={converter_structure}"
        )
    results: list[dict[str, object]] = []
    model = SettingsStateModel()
    modeled_state_assignments = 0
    modeled_preference_projections = 0
    modeled_effect_routes = 0

    for setting in SETTINGS:
        local_violations: list[str] = []
        meta = meta_entries.get(setting.setting_id)
        if meta is None:
            local_violations.append("missing typed metadata")
        else:
            comparisons = {
                "title": (setting.title, meta.title),
                "section": (setting.section, meta.section),
                "type": (setting.option_type, meta.option_type),
                "handler": (setting.handler, meta.handler),
                "activation": (setting.activation.value, meta.activation),
                "preference keys": (setting.preference_keys, meta.preference_keys),
                "dispatch deadline": (setting.max_dispatch_latency_ms, meta.max_dispatch_latency_ms),
            }
            for field, (expected, actual) in comparisons.items():
                if expected != actual:
                    local_violations.append(f"{field} expected={expected!r} actual={actual!r}")
            if meta.max_dispatch_latency_ms > 1_000:
                local_violations.append("metadata dispatch deadline exceeds one second")
            if setting.option_type in {"ENUM", "BOOLEAN", "INT_RANGE"} and meta.allowed_values != setting.values:
                local_violations.append(
                    f"allowed values expected={setting.values!r} actual={meta.allowed_values!r}"
                )
            if meta.allowed_values and meta.default_label not in meta.allowed_values:
                local_violations.append(
                    f"default {meta.default_label!r} is absent from allowed values"
                )

        ui_site = ui_controls_by_title.get(setting.title)
        if ui_site is None:
            local_violations.append("interactive UI control site is missing")
        elif setting.ui_token not in ui_site.source:
            local_violations.append("UI control is not connected to its declared callback")

        try:
            handler_body = extract_kotlin_function(vm_source, setting.handler)
        except ValueError as error:
            local_violations.append(str(error))
            handler_body = ""
        for token in BLOCKING_TOKENS:
            if token in handler_body:
                local_violations.append(f"blocking token in handler: {token}")

        for key in setting.preference_keys:
            if not re.search(rf"const val\s+{re.escape(key)}\s*=", preference_source):
                local_violations.append(f"undefined preference key {key}")

        for evidence in setting.runtime_evidence:
            if evidence.token not in source(evidence.relative_path):
                local_violations.append(
                    f"missing static runtime-contract evidence {evidence.relative_path}: {evidence.token}"
                )

        setting_transitions: list[TransitionResult] = []
        for value in setting.values:
            try:
                transition = model.apply(setting, value)
            except ValueError as error:
                local_violations.append(str(error))
                continue
            setting_transitions.append(transition)
            if model.state.get(setting.setting_id) != value:
                local_violations.append(f"application of {value!r} was not retained in modeled state")
            else:
                modeled_state_assignments += 1

            expected_updates = tuple(
                (key, _project_preference_value(setting, key, value))
                for key in setting.preference_keys
            )
            if transition.preference_updates != expected_updates:
                local_violations.append(
                    f"transition to {value!r} projected incorrect persistence updates"
                )
            for key, expected_value in expected_updates:
                if model.preferences.get(key) != expected_value:
                    local_violations.append(
                        f"application of {value!r} did not retain modeled persistence key {key}"
                    )
                else:
                    modeled_preference_projections += 1

            queue = model.dispatch_queues[setting.activation]
            if not queue or queue[-1] != transition.dispatch_event:
                local_violations.append(
                    f"application of {value!r} did not route through {setting.activation.value}"
                )
            else:
                modeled_effect_routes += 1

        results.append(
            {
                "id": setting.setting_id,
                "title": setting.title,
                "section": setting.section,
                "handler": setting.handler,
                "activation": setting.activation.value,
                "conditional": setting.conditional,
                "modeled_option_value_applications": len(setting_transitions),
                "modeled_state_assignments": len(setting_transitions),
                "modeled_preference_projections": sum(
                    len(transition.preference_updates) for transition in setting_transitions
                ),
                "modeled_effect_routes": len(setting_transitions),
                "declared_max_dispatch_latency_ms": setting.max_dispatch_latency_ms,
                "status": "PASS" if not local_violations else "FAIL",
                "violations": local_violations,
            }
        )
        violations.extend(f"{setting.setting_id}: {item}" for item in local_violations)

    expected_transitions = sum(len(setting.values) for setting in SETTINGS)
    expected_preference_writes = sum(
        len(setting.preference_keys) * len(setting.values) for setting in SETTINGS
    )
    if len(model.transitions) != expected_transitions:
        violations.append(
            f"state model applied {len(model.transitions)} of {expected_transitions} declared transitions"
        )
    if [transition.sequence for transition in model.transitions] != list(
        range(1, len(model.transitions) + 1)
    ):
        violations.append("state-model transition sequence is not contiguous")
    if modeled_state_assignments != expected_transitions:
        violations.append(
            f"modeled {modeled_state_assignments} of {expected_transitions} state assignments"
        )
    if modeled_preference_projections != expected_preference_writes:
        violations.append(
            f"modeled {modeled_preference_projections} of {expected_preference_writes} persistence projections"
        )
    if modeled_effect_routes != expected_transitions:
        violations.append(
            f"modeled {modeled_effect_routes} of {expected_transitions} effect routes"
        )
    dispatched_events = sum(len(queue) for queue in model.dispatch_queues.values())
    if dispatched_events != len(model.transitions):
        violations.append(
            f"state model emitted {dispatched_events} events for {len(model.transitions)} transitions"
        )

    for evidence in CONVERTER_ENDPOINTS:
        if evidence.token not in source(evidence.relative_path):
            violations.append(f"missing converter endpoint token: {evidence.token}")
    for evidence in SETTINGS_ACTION_ENDPOINTS:
        if evidence.token not in source(evidence.relative_path):
            violations.append(f"missing settings action endpoint token: {evidence.token}")
    if "Thread {" not in ui_source or "CONVERTER_SCAN_EXECUTOR.execute" not in ui_source:
        violations.append("converter scan/work is not proven off the UI thread")

    if "AI_ENHANCEMENT_SETTINGS_ENABLED = false" not in preference_source:
        violations.append("hidden AI Enhancement product gate is not explicitly false")
    if any(setting.setting_id == "camera.aiEnhancement" for setting in SETTINGS):
        violations.append("hidden AI Enhancement must not be advertised as a listed setting")
    if "240" not in re.search(
        r"bitrateStepsMbps: List<Int>\s*=\s*listOf\((.*?)\)", source(OPTION_CATALOG), re.DOTALL
    ).group(1).replace("_", ""):
        violations.append("240 Mbps production default is absent from the manual bitrate slider")

    return {
        "status": "PASS" if not violations else "FAIL",
        "host_scope": (
            "static Kotlin source contracts plus deterministic contract-state transitions; "
            "Kotlin and Android runtime are not executed"
        ),
        "timing_evidence": "NOT_MEASURED_ON_HOST",
        "device_scope_not_proven": [
            "Kotlin/Android runtime callback execution and end-to-end setting activation latency",
            "Camera2/HAL acknowledgement latency",
            "GPU/ISP/codec output and image quality",
            "real frame pacing, thermal behavior, and dropped frames",
            "physical camera capability support",
        ],
        "settings_checked": len(SETTINGS),
        "interactive_ui_control_sites_discovered": len(ui_control_sites),
        "modeled_option_value_applications": len(model.transitions),
        "modeled_state_assignments": modeled_state_assignments,
        "modeled_preference_projections": modeled_preference_projections,
        "modeled_effect_routes": modeled_effect_routes,
        "modeled_effect_route_counts": {
            activation.value: len(model.dispatch_queues[activation])
            for activation in ActivationClass
        },
        "converter_endpoint_tokens_checked": len(CONVERTER_ENDPOINTS),
        "settings_action_endpoint_tokens_checked": len(SETTINGS_ACTION_ENDPOINTS),
        "converter_action_choice_call_sites": converter_structure["action_choice_call_sites"],
        "converter_expanded_action_branches": converter_structure["expanded_action_branches"],
        "converter_thumbnail_action_bindings": converter_structure["thumbnail_action_bindings"],
        "maximum_declared_app_dispatch_deadline_ms": max(
            setting.max_dispatch_latency_ms for setting in SETTINGS
        ),
        "settings": results,
        "violations": violations,
    }


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    report = run_settings_activation_audit(root)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
