#!/usr/bin/env python3
"""Deterministic host simulation for unified camera-control/NDR contracts.

The model mirrors the pure decisions in ``UnifiedVideoControlCoordinator`` and
exercises their invariants over an exhaustive, finite scenario matrix.  It also
models strict delivery admission, NDR packet fan-out and the diagnostic bitmap
overlay gate.  This is a host gate only: it cannot prove sensor behaviour,
physical 4K25 cadence, display HDR, recorded-file conformance or image quality.
"""

from __future__ import annotations

import itertools
import json
import math
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path
from typing import Iterable


class VideoMode(str, Enum):
    VIDEO = "VIDEO"
    PRO = "PRO"
    NDR = "NDR"
    LOG = "LOG"
    RAW = "RAW"


class DetailProfile(str, Enum):
    NATURAL_DETAIL = "NATURAL_DETAIL"
    FOCUSED_DETAIL = "FOCUSED_DETAIL"
    EXTREME_DETAIL = "EXTREME_DETAIL"


class LightingCase(str, Enum):
    DAYLIGHT = "DAYLIGHT"
    TUNGSTEN_LOW_LIGHT = "TUNGSTEN_LOW_LIGHT"
    BACKLIT_HIGHLIGHTS = "BACKLIT_HIGHLIGHTS"
    EXTREME_LOW_LIGHT = "EXTREME_LOW_LIGHT"


class MotionCase(str, Enum):
    STATIC = "STATIC"
    TRACKING = "TRACKING"
    LOW_CONFIDENCE_ERRATIC = "LOW_CONFIDENCE_ERRATIC"


class ManualCase(str, Enum):
    AUTO = "AUTO"
    EV_OVERRIDE = "EV_OVERRIDE"
    PARTIAL_MANUAL = "PARTIAL_MANUAL"
    FULL_MANUAL_OUT_OF_RANGE = "FULL_MANUAL_OUT_OF_RANGE"


class ExposureAuthority(str, Enum):
    PLATFORM_AE = "PLATFORM_AE"
    USER_ISO_PRIORITY = "USER_ISO_PRIORITY"
    USER_SHUTTER_PRIORITY = "USER_SHUTTER_PRIORITY"
    USER_SOFTWARE_ISO_PRIORITY = "USER_SOFTWARE_ISO_PRIORITY"
    USER_SOFTWARE_SHUTTER_PRIORITY = "USER_SOFTWARE_SHUTTER_PRIORITY"
    USER_MANUAL_SENSOR = "USER_MANUAL_SENSOR"


class WhiteBalanceAuthority(str, Enum):
    PLATFORM_AUTO = "PLATFORM_AUTO"
    USER_FIXED = "USER_FIXED"


class FocusAuthority(str, Enum):
    CONTINUOUS_AUTO = "CONTINUOUS_AUTO"
    AI_LENS_DISTANCE = "AI_LENS_DISTANCE"
    USER_MANUAL = "USER_MANUAL"


class Rejection(str, Enum):
    MANUAL_SENSOR_UNSUPPORTED = "MANUAL_SENSOR_UNSUPPORTED"
    FIXED_WHITE_BALANCE_UNSUPPORTED = "FIXED_WHITE_BALANCE_UNSUPPORTED"
    MANUAL_FOCUS_UNSUPPORTED = "MANUAL_FOCUS_UNSUPPORTED"
    INVALID_MANUAL_FOCUS = "INVALID_MANUAL_FOCUS"
    FULL_MANUAL_EXPOSURE_IGNORES_EV = "FULL_MANUAL_EXPOSURE_IGNORES_EV"
    PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE = "PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE"
    INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE = "INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE"
    INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE = "INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE"


@dataclass(frozen=True)
class SensorLimits:
    iso_lower: int = 50
    iso_upper: int = 3_200
    exposure_lower_ns: int = 125_000
    exposure_upper_ns: int = 100_000_000
    ev_lower: int = -6
    ev_upper: int = 6
    ev_step_stops: float = 0.5
    kelvin_lower: int = 2_000
    kelvin_upper: int = 10_000
    max_focus_diopters: float = 10.0
    supports_manual_sensor: bool = True
    supports_fixed_white_balance: bool = True
    supports_manual_focus: bool = True
    supports_iso_priority: bool = False
    supports_shutter_priority: bool = False
    supports_software_iso_priority: bool = False
    supports_software_shutter_priority: bool = False


@dataclass(frozen=True)
class ManualControls:
    iso: int | None = None
    exposure_time_ns: int | None = None
    ev_index: int = 0
    ev_override: bool = False
    kelvin: int | None = None
    tint: int = 0
    focus_diopters: float | None = None
    focus_override: bool = False


@dataclass(frozen=True)
class AutomaticSignals:
    metered_iso: int | None = None
    metered_exposure_time_ns: int | None = None
    face_bias_stops: float = 0.0
    face_confidence: float = 0.0
    highlight_bias_stops: float = 0.0
    ndr_bias_stops: float = 0.0
    measured_kelvin: int | None = None
    measured_tint: int = 0
    wb_confidence: float = 0.0
    awb_converged: bool = False
    ai_focus_diopters: float | None = None
    ai_focus_confidence: float = 0.0
    previous_ev_index: int | None = None
    previous_focus_diopters: float | None = None
    elapsed_seconds: float = 0.0
    software_iso_priority_exposure_time_ns: int | None = None
    software_shutter_priority_iso: int | None = None


@dataclass(frozen=True)
class ControlRequest:
    generation: int
    mode: VideoMode
    detail_profile: DetailProfile
    target_fps: int
    recording: bool
    limits: SensorLimits
    manual: ManualControls
    automatic: AutomaticSignals
    frame_exposure_budget_ns: int = 0


@dataclass(frozen=True)
class ExposureDecision:
    authority: ExposureAuthority
    iso: int | None
    exposure_time_ns: int | None
    ev_index: int
    adaptive_bias_stops: float
    manual_ev_stops: float
    frame_budget_ns: int


@dataclass(frozen=True)
class WhiteBalanceDecision:
    authority: WhiteBalanceAuthority
    fixed_kelvin: int | None
    fixed_tint: int | None
    request_auto_lock: bool
    stable_fallback_kelvin: int | None
    stable_fallback_tint: int


@dataclass(frozen=True)
class FocusDecision:
    authority: FocusAuthority
    focus_diopters: float | None
    apply_auto_focus_regions: bool
    confidence: float


@dataclass(frozen=True)
class ControlDecision:
    generation: int
    mode: VideoMode
    detail_profile: DetailProfile
    exposure: ExposureDecision
    white_balance: WhiteBalanceDecision
    focus: FocusDecision
    rejections: frozenset[Rejection]


@dataclass(frozen=True)
class ProfileTuning:
    focus_confidence_floor: float
    focus_slew_diopters_per_second: float
    focus_deadband_diopters: float
    wb_confidence_floor: float
    max_ev_decrease_steps: int
    max_ev_increase_steps: int


PROFILE_TUNING = {
    DetailProfile.NATURAL_DETAIL: ProfileTuning(0.72, 2.0, 0.012, 0.58, 1, 1),
    DetailProfile.FOCUSED_DETAIL: ProfileTuning(0.62, 3.0, 0.008, 0.62, 2, 1),
    DetailProfile.EXTREME_DETAIL: ProfileTuning(0.56, 5.0, 0.004, 0.68, 2, 1),
}

MODE_WB_FLOOR = {
    VideoMode.VIDEO: 0.55,
    VideoMode.PRO: 0.60,
    VideoMode.NDR: 0.70,
    VideoMode.LOG: 0.68,
    VideoMode.RAW: 0.72,
}

FOCUS_SCENE_CHANGE_CONFIDENCE_START = 0.82
FOCUS_SCENE_CHANGE_FULL_DELTA_DIOPTERS = 1.5
FOCUS_SCENE_CHANGE_MAX_MULTIPLIER = 3.5


def _clamp(value: float | int, lower: float | int, upper: float | int):
    return min(max(value, lower), upper)


def _round_kotlin(value: float) -> int:
    """Kotlin roundToInt behaviour for the non-negative values used here."""
    return math.floor(value + 0.5)


def _hysteretic_ev_index(
    current: int | None,
    base: int,
    bias_stops: float,
    step_stops: float,
    lower: int,
    upper: int,
    max_decrease: int,
    max_increase: int,
) -> int:
    base = int(_clamp(base, lower, upper))
    if not math.isfinite(bias_stops) or not math.isfinite(step_stops) or step_stops <= 0:
        return base
    position = float(_clamp(base + bias_stops / step_stops, lower, upper))
    nearest = int(_clamp(_round_kotlin(position), lower, upper))
    if current is None:
        return nearest
    current = int(_clamp(current, lower, upper))
    if nearest == current:
        return current
    if position < current:
        selected = nearest if position <= current - 0.55 else current
    elif position > current:
        selected = nearest if position >= current + 0.70 else current
    else:
        selected = current
    return int(_clamp(selected, max(lower, current - max_decrease), min(upper, current + max_increase)))


def _adaptive_bias(request: ControlRequest) -> float:
    auto = request.automatic
    face = (
        auto.face_bias_stops
        if math.isfinite(auto.face_bias_stops)
        and math.isfinite(auto.face_confidence)
        and auto.face_confidence >= 0.55
        else 0.0
    )
    highlight = _clamp(auto.highlight_bias_stops, -3.0, 0.0) if math.isfinite(auto.highlight_bias_stops) else 0.0
    ndr = _clamp(auto.ndr_bias_stops, -1.0, 0.0) if math.isfinite(auto.ndr_bias_stops) else 0.0
    protective = min(highlight, ndr)
    if request.mode in (VideoMode.VIDEO, VideoMode.PRO):
        candidate = protective if protective < 0 else face
    elif request.mode is VideoMode.LOG:
        candidate = protective if protective < 0 else min(face, 0.33)
    else:
        candidate = protective
    bounds = {
        VideoMode.VIDEO: (-1.0, 1.0),
        VideoMode.PRO: (-1.25, 0.75),
        VideoMode.LOG: (-1.25, 0.33),
        VideoMode.NDR: (-1.0, 0.0),
        VideoMode.RAW: (-1.0, 0.0),
    }[request.mode]
    return float(_clamp(candidate, *bounds))


def _focus_scene_change_multiplier(delta_diopters: float, confidence: float) -> float:
    confidence_strength = float(
        _clamp(
            (confidence - FOCUS_SCENE_CHANGE_CONFIDENCE_START)
            / (1.0 - FOCUS_SCENE_CHANGE_CONFIDENCE_START),
            0.0,
            1.0,
        )
    )
    distance_strength = float(
        _clamp(delta_diopters / FOCUS_SCENE_CHANGE_FULL_DELTA_DIOPTERS, 0.0, 1.0)
    )
    return 1.0 + (
        FOCUS_SCENE_CHANGE_MAX_MULTIPLIER - 1.0
    ) * confidence_strength * distance_strength


def resolve_controls(request: ControlRequest) -> ControlDecision:
    """Pure mirror of the production coordinator's ownership/bounding rules."""
    limits = request.limits
    tuning = PROFILE_TUNING[request.detail_profile]
    rejections: set[Rejection] = set()
    iso_lower, iso_upper = sorted((max(1, limits.iso_lower), max(1, limits.iso_upper)))
    exp_lower, exp_upper = sorted((max(1, limits.exposure_lower_ns), max(1, limits.exposure_upper_ns)))
    ev_lower, ev_upper = sorted((limits.ev_lower, limits.ev_upper))
    kelvin_lower, kelvin_upper = sorted((max(1, limits.kelvin_lower), max(1, limits.kelvin_upper)))
    fps = max(request.target_fps, 1)
    nominal_frame_ns = max(1, 1_000_000_000 // fps)
    requested_budget = min(request.frame_exposure_budget_ns, nominal_frame_ns) if request.frame_exposure_budget_ns > 0 else nominal_frame_ns
    frame_budget = int(_clamp(requested_budget, exp_lower, exp_upper))
    bounded_exp_upper = max(exp_lower, min(exp_upper, frame_budget))
    step = limits.ev_step_stops if math.isfinite(limits.ev_step_stops) and limits.ev_step_stops > 0 else 1.0

    manual_sensor_requested = request.manual.iso is not None or request.manual.exposure_time_ns is not None
    user_iso = int(_clamp(request.manual.iso, iso_lower, iso_upper)) if request.manual.iso is not None else None
    user_exp = (
        int(_clamp(request.manual.exposure_time_ns, exp_lower, bounded_exp_upper))
        if request.manual.exposure_time_ns is not None
        else None
    )
    user_ev_index = (
        int(_clamp(request.manual.ev_index, ev_lower, ev_upper))
        if request.manual.ev_override
        else 0
    )
    partial_manual = (user_iso is None) != (user_exp is None)
    software_exp_evidence = request.automatic.software_iso_priority_exposure_time_ns
    software_exp = (
        int(software_exp_evidence)
        if software_exp_evidence is not None
        and exp_lower <= software_exp_evidence <= bounded_exp_upper
        else None
    )
    software_iso_evidence = request.automatic.software_shutter_priority_iso
    software_iso = (
        int(software_iso_evidence)
        if software_iso_evidence is not None
        and iso_lower <= software_iso_evidence <= iso_upper
        else None
    )
    if (
        user_iso is not None
        and user_exp is None
        and limits.supports_software_iso_priority
        and software_exp_evidence is not None
        and software_exp is None
    ):
        rejections.add(Rejection.INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE)
    if (
        user_iso is None
        and user_exp is not None
        and limits.supports_software_shutter_priority
        and software_iso_evidence is not None
        and software_iso is None
    ):
        rejections.add(Rejection.INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE)

    if user_iso is not None and user_exp is None and limits.supports_iso_priority:
        exposure_decision = ExposureDecision(
            ExposureAuthority.USER_ISO_PRIORITY,
            user_iso,
            None,
            user_ev_index,
            0.0,
            user_ev_index * step,
            frame_budget,
        )
    elif user_iso is None and user_exp is not None and limits.supports_shutter_priority:
        exposure_decision = ExposureDecision(
            ExposureAuthority.USER_SHUTTER_PRIORITY,
            None,
            user_exp,
            user_ev_index,
            0.0,
            user_ev_index * step,
            frame_budget,
        )
    elif (
        user_iso is not None
        and user_exp is None
        and limits.supports_software_iso_priority
        and software_exp is not None
    ):
        exposure_decision = ExposureDecision(
            ExposureAuthority.USER_SOFTWARE_ISO_PRIORITY,
            user_iso,
            software_exp,
            0,
            0.0,
            user_ev_index * step,
            frame_budget,
        )
    elif (
        user_iso is None
        and user_exp is not None
        and limits.supports_software_shutter_priority
        and software_iso is not None
    ):
        exposure_decision = ExposureDecision(
            ExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY,
            software_iso,
            user_exp,
            0,
            0.0,
            user_ev_index * step,
            frame_budget,
        )
    elif manual_sensor_requested and limits.supports_manual_sensor and not partial_manual:
        assert user_iso is not None and user_exp is not None
        if request.manual.ev_override and request.manual.ev_index != 0:
            rejections.add(Rejection.FULL_MANUAL_EXPOSURE_IGNORES_EV)
        exposure_decision = ExposureDecision(
            ExposureAuthority.USER_MANUAL_SENSOR,
            user_iso,
            user_exp,
            0,
            0.0,
            0.0,
            frame_budget,
        )
    else:
        if partial_manual and limits.supports_manual_sensor:
            rejections.add(Rejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE)
        if manual_sensor_requested and not limits.supports_manual_sensor:
            rejections.add(Rejection.MANUAL_SENSOR_UNSUPPORTED)
        base_ev = int(_clamp(request.manual.ev_index, ev_lower, ev_upper))
        adaptive = 0.0 if request.manual.ev_override else _adaptive_bias(request)
        ev_index = base_ev if request.manual.ev_override else _hysteretic_ev_index(
            request.automatic.previous_ev_index,
            base_ev,
            adaptive,
            step,
            ev_lower,
            ev_upper,
            tuning.max_ev_decrease_steps,
            tuning.max_ev_increase_steps,
        )
        exposure_decision = ExposureDecision(
            ExposureAuthority.PLATFORM_AE,
            None,
            None,
            ev_index,
            adaptive,
            0.0,
            frame_budget,
        )

    if request.manual.kelvin is not None and limits.supports_fixed_white_balance:
        wb_decision = WhiteBalanceDecision(
            WhiteBalanceAuthority.USER_FIXED,
            int(_clamp(request.manual.kelvin, kelvin_lower, kelvin_upper)),
            int(_clamp(request.manual.tint, -150, 150)),
            False,
            None,
            0,
        )
    else:
        if request.manual.kelvin is not None:
            rejections.add(Rejection.FIXED_WHITE_BALANCE_UNSUPPORTED)
        confidence = _clamp(request.automatic.wb_confidence, 0.0, 1.0) if math.isfinite(request.automatic.wb_confidence) else 0.0
        measured_kelvin = (
            int(request.automatic.measured_kelvin)
            if request.automatic.measured_kelvin is not None
            and kelvin_lower <= request.automatic.measured_kelvin <= kelvin_upper
            else None
        )
        measured_tint = (
            int(request.automatic.measured_tint)
            if -150 <= request.automatic.measured_tint <= 150
            else None
        )
        stable = (
            request.automatic.awb_converged
            and confidence
            >= max(0.55, tuning.wb_confidence_floor, MODE_WB_FLOOR[request.mode])
            and measured_kelvin is not None
            and measured_tint is not None
        )
        fallback = measured_kelvin if stable else None
        wb_decision = WhiteBalanceDecision(
            WhiteBalanceAuthority.PLATFORM_AUTO,
            None,
            None,
            request.recording and stable,
            fallback,
            measured_tint if fallback is not None and measured_tint is not None else 0,
        )

    max_focus = limits.max_focus_diopters if math.isfinite(limits.max_focus_diopters) and limits.max_focus_diopters > 0 else None
    if request.manual.focus_override:
        focus = request.manual.focus_diopters
        if not limits.supports_manual_focus or max_focus is None:
            rejections.add(Rejection.MANUAL_FOCUS_UNSUPPORTED)
            focus_decision = FocusDecision(FocusAuthority.CONTINUOUS_AUTO, None, True, 0.0)
        elif focus is None or not math.isfinite(focus) or focus < 0:
            rejections.add(Rejection.INVALID_MANUAL_FOCUS)
            focus_decision = FocusDecision(FocusAuthority.CONTINUOUS_AUTO, None, True, 0.0)
        else:
            focus_decision = FocusDecision(FocusAuthority.USER_MANUAL, float(_clamp(focus, 0.0, max_focus)), False, 1.0)
    else:
        focus = request.automatic.ai_focus_diopters
        confidence = _clamp(request.automatic.ai_focus_confidence, 0.0, 1.0) if math.isfinite(request.automatic.ai_focus_confidence) else 0.0
        eligible = (
            limits.supports_manual_focus
            and max_focus is not None
            and focus is not None
            and math.isfinite(focus)
            and focus >= 0
            and confidence >= tuning.focus_confidence_floor
        )
        if not eligible:
            focus_decision = FocusDecision(FocusAuthority.CONTINUOUS_AUTO, None, True, 0.0)
        else:
            target = float(_clamp(focus, 0.0, max_focus))
            previous = request.automatic.previous_focus_diopters
            dt = request.automatic.elapsed_seconds if math.isfinite(request.automatic.elapsed_seconds) and request.automatic.elapsed_seconds > 0 else 0.0
            if previous is not None and math.isfinite(previous) and previous >= 0:
                previous = float(_clamp(previous, 0.0, max_focus))
                target_delta = target - previous
                if abs(target_delta) <= tuning.focus_deadband_diopters:
                    target = previous
                elif dt > 0:
                    mode_scale = 0.85 if request.mode in (VideoMode.NDR, VideoMode.LOG, VideoMode.RAW) else 1.0
                    max_delta = (
                        tuning.focus_slew_diopters_per_second
                        * mode_scale
                        * dt
                        * _focus_scene_change_multiplier(abs(target_delta), confidence)
                    )
                    target = previous + float(_clamp(target_delta, -max_delta, max_delta))
            focus_decision = FocusDecision(FocusAuthority.AI_LENS_DISTANCE, float(_clamp(target, 0.0, max_focus)), False, float(confidence))

    return ControlDecision(
        max(0, request.generation),
        request.mode,
        request.detail_profile,
        exposure_decision,
        wb_decision,
        focus_decision,
        frozenset(rejections),
    )


@dataclass(frozen=True)
class MatrixCase:
    mode: VideoMode
    detail_profile: DetailProfile
    lighting: LightingCase
    motion: MotionCase
    manual_case: ManualCase
    manual_sensor: bool
    fixed_white_balance: bool
    manual_focus: bool

    @property
    def capability_id(self) -> str:
        return f"sensor={int(self.manual_sensor)},wb={int(self.fixed_white_balance)},focus={int(self.manual_focus)}"


def _lighting_signals(case: LightingCase) -> AutomaticSignals:
    return {
        LightingCase.DAYLIGHT: AutomaticSignals(
            metered_iso=100,
            metered_exposure_time_ns=4_000_000,
            face_bias_stops=0.25,
            face_confidence=0.90,
            measured_kelvin=5_600,
            measured_tint=2,
            wb_confidence=0.95,
            awb_converged=True,
        ),
        LightingCase.TUNGSTEN_LOW_LIGHT: AutomaticSignals(
            metered_iso=1_600,
            metered_exposure_time_ns=20_000_000,
            face_bias_stops=0.50,
            face_confidence=0.80,
            measured_kelvin=3_200,
            measured_tint=-8,
            wb_confidence=0.88,
            awb_converged=True,
        ),
        LightingCase.BACKLIT_HIGHLIGHTS: AutomaticSignals(
            metered_iso=200,
            metered_exposure_time_ns=2_000_000,
            face_bias_stops=0.75,
            face_confidence=0.95,
            highlight_bias_stops=-1.30,
            ndr_bias_stops=-0.80,
            measured_kelvin=6_500,
            measured_tint=5,
            wb_confidence=0.90,
            awb_converged=True,
        ),
        LightingCase.EXTREME_LOW_LIGHT: AutomaticSignals(
            metered_iso=3_200,
            metered_exposure_time_ns=35_000_000,
            face_bias_stops=0.80,
            face_confidence=0.45,
            highlight_bias_stops=-0.20,
            ndr_bias_stops=-0.30,
            measured_kelvin=2_800,
            measured_tint=-18,
            wb_confidence=0.50,
            awb_converged=False,
        ),
    }[case]


def _with_motion(signals: AutomaticSignals, case: MotionCase) -> AutomaticSignals:
    focus, confidence, previous, dt = {
        MotionCase.STATIC: (1.0, 0.95, 1.0, 0.10),
        MotionCase.TRACKING: (5.0, 0.80, 1.0, 0.10),
        MotionCase.LOW_CONFIDENCE_ERRATIC: (8.0, 0.50, 1.0, 0.033),
    }[case]
    return AutomaticSignals(
        **{
            **asdict(signals),
            "ai_focus_diopters": focus,
            "ai_focus_confidence": confidence,
            "previous_ev_index": 0,
            "previous_focus_diopters": previous,
            "elapsed_seconds": dt,
        }
    )


def _manual_controls(case: ManualCase) -> ManualControls:
    return {
        ManualCase.AUTO: ManualControls(),
        ManualCase.EV_OVERRIDE: ManualControls(ev_index=4, ev_override=True),
        ManualCase.PARTIAL_MANUAL: ManualControls(
            iso=800,
            ev_index=-2,
            ev_override=True,
            kelvin=4_500,
            tint=20,
            focus_diopters=2.5,
            focus_override=True,
        ),
        ManualCase.FULL_MANUAL_OUT_OF_RANGE: ManualControls(
            iso=6_400,
            exposure_time_ns=80_000_000,
            ev_index=3,
            ev_override=True,
            kelvin=12_000,
            tint=200,
            focus_diopters=12.0,
            focus_override=True,
        ),
    }[case]


def control_matrix_cases() -> Iterable[MatrixCase]:
    capability_combinations = itertools.product((False, True), repeat=3)
    capabilities = tuple(capability_combinations)
    return (
        MatrixCase(mode, profile, lighting, motion, manual, *capability)
        for mode, profile, lighting, motion, manual, capability in itertools.product(
            tuple(VideoMode),
            tuple(DetailProfile),
            tuple(LightingCase),
            tuple(MotionCase),
            tuple(ManualCase),
            capabilities,
        )
    )


def request_for_case(case: MatrixCase) -> ControlRequest:
    return ControlRequest(
        generation=17,
        mode=case.mode,
        detail_profile=case.detail_profile,
        target_fps=25,
        recording=case.motion is not MotionCase.STATIC,
        limits=SensorLimits(
            supports_manual_sensor=case.manual_sensor,
            supports_fixed_white_balance=case.fixed_white_balance,
            supports_manual_focus=case.manual_focus,
        ),
        manual=_manual_controls(case.manual_case),
        automatic=_with_motion(_lighting_signals(case.lighting), case.motion),
    )


def validate_control_decision(request: ControlRequest, decision: ControlDecision) -> None:
    """Assert authority, range, frame-budget, WB and focus-slew invariants."""
    assert decision.generation >= 0
    assert decision.mode is request.mode
    assert decision.detail_profile is request.detail_profile
    limits = request.limits
    assert limits.exposure_lower_ns <= decision.exposure.frame_budget_ns <= min(
        limits.exposure_upper_ns, 1_000_000_000 // max(1, request.target_fps)
    )
    manual_sensor_requested = request.manual.iso is not None or request.manual.exposure_time_ns is not None
    partial_manual = (request.manual.iso is None) != (request.manual.exposure_time_ns is None)
    if decision.exposure.authority in (
        ExposureAuthority.USER_MANUAL_SENSOR,
        ExposureAuthority.USER_SOFTWARE_ISO_PRIORITY,
        ExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY,
    ):
        assert limits.iso_lower <= (decision.exposure.iso or 0) <= limits.iso_upper
        assert limits.exposure_lower_ns <= (decision.exposure.exposure_time_ns or 0) <= decision.exposure.frame_budget_ns
        assert decision.exposure.ev_index == 0
        assert decision.exposure.adaptive_bias_stops == 0.0
    elif decision.exposure.authority is ExposureAuthority.USER_ISO_PRIORITY:
        assert limits.supports_iso_priority
        assert limits.iso_lower <= (decision.exposure.iso or 0) <= limits.iso_upper
        assert decision.exposure.exposure_time_ns is None
    elif decision.exposure.authority is ExposureAuthority.USER_SHUTTER_PRIORITY:
        assert limits.supports_shutter_priority
        assert decision.exposure.iso is None
        assert limits.exposure_lower_ns <= (decision.exposure.exposure_time_ns or 0) <= decision.exposure.frame_budget_ns
    else:
        assert decision.exposure.authority is ExposureAuthority.PLATFORM_AE
        assert decision.exposure.iso is None and decision.exposure.exposure_time_ns is None
        assert limits.ev_lower <= decision.exposure.ev_index <= limits.ev_upper
        if manual_sensor_requested and not limits.supports_manual_sensor:
            assert Rejection.MANUAL_SENSOR_UNSUPPORTED in decision.rejections
        if partial_manual and limits.supports_manual_sensor:
            assert Rejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in decision.rejections
    if manual_sensor_requested and limits.supports_manual_sensor and not partial_manual:
        assert decision.exposure.authority is ExposureAuthority.USER_MANUAL_SENSOR
    if request.manual.ev_override and decision.exposure.authority is ExposureAuthority.PLATFORM_AE:
        assert decision.exposure.ev_index == int(_clamp(request.manual.ev_index, limits.ev_lower, limits.ev_upper))
        assert decision.exposure.adaptive_bias_stops == 0.0
    if request.manual.iso is not None and request.manual.exposure_time_ns is not None and limits.supports_manual_sensor:
        if request.manual.ev_override and request.manual.ev_index != 0:
            assert Rejection.FULL_MANUAL_EXPOSURE_IGNORES_EV in decision.rejections

    if request.manual.kelvin is not None and limits.supports_fixed_white_balance:
        assert decision.white_balance.authority is WhiteBalanceAuthority.USER_FIXED
        assert limits.kelvin_lower <= (decision.white_balance.fixed_kelvin or 0) <= limits.kelvin_upper
        assert -150 <= (decision.white_balance.fixed_tint or 0) <= 150
        assert not decision.white_balance.request_auto_lock
    else:
        assert decision.white_balance.authority is WhiteBalanceAuthority.PLATFORM_AUTO
        if request.manual.kelvin is not None:
            assert Rejection.FIXED_WHITE_BALANCE_UNSUPPORTED in decision.rejections
        if decision.white_balance.request_auto_lock:
            assert request.recording
            assert decision.white_balance.stable_fallback_kelvin is not None

    if request.manual.focus_override and limits.supports_manual_focus:
        assert decision.focus.authority is FocusAuthority.USER_MANUAL
        assert 0.0 <= (decision.focus.focus_diopters or 0.0) <= limits.max_focus_diopters
    elif request.manual.focus_override:
        assert decision.focus.authority is FocusAuthority.CONTINUOUS_AUTO
        assert Rejection.MANUAL_FOCUS_UNSUPPORTED in decision.rejections
    elif decision.focus.authority is FocusAuthority.AI_LENS_DISTANCE:
        assert limits.supports_manual_focus
        assert 0.0 <= (decision.focus.focus_diopters or 0.0) <= limits.max_focus_diopters
        previous = request.automatic.previous_focus_diopters
        if previous is not None and request.automatic.elapsed_seconds > 0:
            scale = 0.85 if request.mode in (VideoMode.NDR, VideoMode.LOG, VideoMode.RAW) else 1.0
            confidence = float(_clamp(request.automatic.ai_focus_confidence, 0.0, 1.0))
            target = float(request.automatic.ai_focus_diopters or 0.0)
            max_delta = (
                PROFILE_TUNING[request.detail_profile].focus_slew_diopters_per_second
                * scale
                * request.automatic.elapsed_seconds
                * _focus_scene_change_multiplier(abs(target - previous), confidence)
            )
            assert abs((decision.focus.focus_diopters or 0.0) - previous) <= max_delta + 1e-9
    else:
        assert decision.focus.apply_auto_focus_regions


def validate_partial_manual_metered_exposure_parity() -> int:
    """Partial manual must use a proven adaptive authority or fail closed to platform AE."""
    base = dict(
        generation=23,
        mode=VideoMode.VIDEO,
        detail_profile=DetailProfile.FOCUSED_DETAIL,
        target_fps=25,
        recording=True,
    )
    cases = (
        (
            ControlRequest(
                **base,
                limits=SensorLimits(supports_iso_priority=True),
                manual=ManualControls(iso=400, ev_index=2, ev_override=True),
                automatic=AutomaticSignals(),
            ),
            ExposureAuthority.USER_ISO_PRIORITY,
        ),
        (
            ControlRequest(
                **base,
                limits=SensorLimits(supports_software_iso_priority=True),
                manual=ManualControls(iso=400, ev_index=2, ev_override=True),
                automatic=AutomaticSignals(software_iso_priority_exposure_time_ns=14_000_000),
            ),
            ExposureAuthority.USER_SOFTWARE_ISO_PRIORITY,
        ),
        (
            ControlRequest(
                **base,
                limits=SensorLimits(),
                manual=ManualControls(iso=400, ev_override=True),
                automatic=AutomaticSignals(metered_exposure_time_ns=7_000_000),
            ),
            ExposureAuthority.PLATFORM_AE,
        ),
        (
            ControlRequest(
                **base,
                limits=SensorLimits(supports_software_shutter_priority=True),
                manual=ManualControls(exposure_time_ns=8_000_000, ev_override=True),
                automatic=AutomaticSignals(software_shutter_priority_iso=200),
            ),
            ExposureAuthority.USER_SOFTWARE_SHUTTER_PRIORITY,
        ),
    )
    for request, expected_authority in cases:
        decision = resolve_controls(request)
        validate_control_decision(request, decision)
        assert decision.exposure.authority is expected_authority
        if expected_authority is ExposureAuthority.PLATFORM_AE:
            assert Rejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE in decision.rejections
    return len(cases)


class SoftwareAeAxis(str, Enum):
    ISO = "ISO"
    SHUTTER = "SHUTTER"


@dataclass(frozen=True)
class SoftwareAeSample:
    average_luma: float
    highlight_clip_fraction: float
    red_highlight_clip_fraction: float
    timestamp_ms: int
    signal_domain: str = "PROCESSED_YUV"


@dataclass(frozen=True)
class SoftwareAeRequest:
    generation: int
    mode: VideoMode
    detail_profile: DetailProfile
    axis: SoftwareAeAxis
    fixed_iso: int | None = None
    fixed_exposure_ns: int | None = None
    requested_ev_stops: float = 0.0
    metered_iso: int | None = 400
    metered_exposure_ns: int | None = 10_000_000
    iso_range: tuple[int, int] = (50, 3_200)
    exposure_range_ns: tuple[int, int] = (125_000, 100_000_000)
    frame_budget_ns: int = 40_000_000


@dataclass(frozen=True)
class SoftwareAeDecision:
    ready: bool
    axis: SoftwareAeAxis
    iso: int | None
    exposure_ns: int | None
    target_luma: float
    measured_luma: float
    correction_stops: float
    changed: bool
    saturated: bool
    reason: str


@dataclass
class _SoftwareAeState:
    signature: tuple[object, ...]
    neutral_exposure_product: float
    neutral_target_luma: float
    target_luma: float
    iso: int
    exposure_ns: int
    last_sample_timestamp_ms: int
    last_adjustment_ms: int
    measured_luma: float
    correction_stops: float = 0.0
    saturated: bool = False


class SoftwareAePriorityModel:
    """Deterministic mirror of the app-owned complementary-axis controller."""

    MAX_SAMPLE_AGE_MS = 750
    MIN_ADJUSTMENT_INTERVAL_MS = 120
    TRANSIENT_ADJUSTMENT_INTERVAL_MS = 80
    TRANSIENT_ERROR_STOPS = 0.75

    def __init__(self) -> None:
        self.state: _SoftwareAeState | None = None

    def resolve(
        self,
        request: SoftwareAeRequest,
        sample: SoftwareAeSample | None,
        now_ms: int,
    ) -> SoftwareAeDecision:
        valid = (
            sample is not None
            and math.isfinite(sample.average_luma)
            and sample.average_luma >= 1e-4
            and sample.timestamp_ms > 0
            and now_ms >= sample.timestamp_ms
            and now_ms - sample.timestamp_ms <= self.MAX_SAMPLE_AGE_MS
        )
        if not valid:
            changed = self.state is not None
            self.state = None
            return self._unavailable(request.axis, changed, "METER_UNAVAILABLE_OR_STALE")
        assert sample is not None

        iso_lower, iso_upper = sorted((max(1, request.iso_range[0]), max(1, request.iso_range[1])))
        exp_lower, sensor_exp_upper = sorted(
            (max(1, request.exposure_range_ns[0]), max(1, request.exposure_range_ns[1]))
        )
        frame_budget = int(_clamp(request.frame_budget_ns, exp_lower, sensor_exp_upper))
        exp_upper = max(exp_lower, min(sensor_exp_upper, frame_budget))
        fixed_iso = (
            int(_clamp(request.fixed_iso, iso_lower, iso_upper))
            if request.fixed_iso is not None
            else None
        )
        fixed_exp = (
            int(_clamp(request.fixed_exposure_ns, exp_lower, exp_upper))
            if request.fixed_exposure_ns is not None
            else None
        )
        valid_axis = (
            request.axis is SoftwareAeAxis.ISO
            and fixed_iso is not None
            and request.fixed_exposure_ns is None
        ) or (
            request.axis is SoftwareAeAxis.SHUTTER
            and fixed_exp is not None
            and request.fixed_iso is None
        )
        if not valid_axis:
            changed = self.state is not None
            self.state = None
            return self._unavailable(request.axis, changed, "INVALID_FIXED_AXIS")

        ev_stops = (
            float(_clamp(request.requested_ev_stops, -6.0, 6.0))
            if math.isfinite(request.requested_ev_stops)
            else 0.0
        )
        signature = (
            max(0, request.generation),
            request.mode,
            request.detail_profile,
            request.axis,
            fixed_iso,
            fixed_exp,
            ev_stops,
            iso_lower,
            iso_upper,
            exp_lower,
            exp_upper,
            frame_budget,
            sample.signal_domain,
        )
        current = self.state
        context_changed = current is not None and (
            current.signature[0] != signature[0]
            or current.signature[1] != signature[1]
            or current.signature[-1] != signature[-1]
        )
        if context_changed and sample.timestamp_ms <= current.last_sample_timestamp_ms:
            return self._unavailable(request.axis, True, "METER_PREDATES_CONTROL_CONTEXT")

        if current is None or current.signature != signature:
            if request.metered_iso is None or request.metered_exposure_ns is None:
                return self._unavailable(request.axis, current is not None, "METERED_AXIS_MISSING")
            preserve_neutral = current is not None and not context_changed
            neutral_product = (
                current.neutral_exposure_product
                if preserve_neutral
                else float(request.metered_iso * request.metered_exposure_ns)
            )
            target_bounds = (
                (0.005, 0.55)
                if sample.signal_domain == "SENSOR_LINEAR_RAW"
                else (0.02, 0.90)
            )
            neutral_luma = (
                current.neutral_target_luma
                if preserve_neutral
                else float(_clamp(sample.average_luma, *target_bounds))
            )
            product = neutral_product * (2.0**ev_stops)
            if request.axis is SoftwareAeAxis.ISO:
                iso = fixed_iso or iso_lower
                exposure_ns = int(_clamp(_round_kotlin(product / iso), exp_lower, exp_upper))
            else:
                exposure_ns = fixed_exp or exp_lower
                iso = int(_clamp(_round_kotlin(product / exposure_ns), iso_lower, iso_upper))
            target_luma = float(_clamp(neutral_luma * (2.0**ev_stops), *target_bounds))
            self.state = _SoftwareAeState(
                signature=signature,
                neutral_exposure_product=neutral_product,
                neutral_target_luma=neutral_luma,
                target_luma=target_luma,
                iso=iso,
                exposure_ns=exposure_ns,
                last_sample_timestamp_ms=sample.timestamp_ms,
                last_adjustment_ms=now_ms,
                measured_luma=sample.average_luma,
            )
            return self._decision(True, "INITIALIZED_FROM_LIVE_AE")

        if sample.timestamp_ms <= current.last_sample_timestamp_ms:
            return self._decision(False, "WAITING_FOR_NEW_FRAME")
        error_stops = math.log2(current.target_luma / max(sample.average_luma, 1e-4))
        highlight_clip = (
            float(_clamp(sample.highlight_clip_fraction, 0.0, 1.0))
            if math.isfinite(sample.highlight_clip_fraction)
            else 0.0
        )
        red_highlight_clip = (
            float(_clamp(sample.red_highlight_clip_fraction, 0.0, 1.0))
            if math.isfinite(sample.red_highlight_clip_fraction)
            else 0.0
        )
        clip = max(highlight_clip, red_highlight_clip)
        if sample.average_luma >= 0.98 or clip >= 0.10:
            clip_stops = -2.0
        elif clip >= 0.04:
            clip_stops = -1.25
        elif clip >= 0.015:
            clip_stops = -0.80
        elif clip >= 0.004:
            clip_stops = -0.45
        else:
            clip_stops = math.inf
        requested = min(error_stops, clip_stops)
        required_interval = (
            self.TRANSIENT_ADJUSTMENT_INTERVAL_MS
            if abs(error_stops) >= self.TRANSIENT_ERROR_STOPS or clip_stops <= -0.80
            else self.MIN_ADJUSTMENT_INTERVAL_MS
        )
        if now_ms - current.last_adjustment_ms < required_interval:
            current.last_sample_timestamp_ms = sample.timestamp_ms
            current.measured_luma = sample.average_luma
            return self._decision(False, "SETTLING")

        attack_limit, steady_release_limit = {
            DetailProfile.NATURAL_DETAIL: (0.45, 0.16),
            DetailProfile.FOCUSED_DETAIL: (0.65, 0.22),
            DetailProfile.EXTREME_DETAIL: (0.85, 0.28),
        }[request.detail_profile]
        if sample.average_luma >= 0.98 or clip >= 0.10:
            attack_limit = 1.5
        large_dark_transition = requested >= self.TRANSIENT_ERROR_STOPS
        release_limit = (
            steady_release_limit * 2.0 if large_dark_transition else steady_release_limit
        )
        response = 0.72 if requested < 0 else (0.52 if large_dark_transition else 0.38)
        correction = float(_clamp(requested * response, -attack_limit, release_limit))
        current.last_sample_timestamp_ms = sample.timestamp_ms
        current.measured_luma = sample.average_luma
        current.correction_stops = correction
        if abs(correction) < 0.045:
            current.correction_stops = 0.0
            return self._decision(False, "WITHIN_DEADBAND")

        if request.axis is SoftwareAeAxis.ISO:
            next_exp = int(
                _clamp(_round_kotlin(current.exposure_ns * (2.0**correction)), exp_lower, exp_upper)
            )
            materially_changed = abs(next_exp - current.exposure_ns) >= max(
                50_000, current.exposure_ns * 0.012
            )
            next_iso = current.iso
        else:
            next_iso = int(
                _clamp(_round_kotlin(current.iso * (2.0**correction)), iso_lower, iso_upper)
            )
            materially_changed = abs(next_iso - current.iso) >= max(1, current.iso * 0.02)
            next_exp = current.exposure_ns
        if not materially_changed:
            return self._decision(False, "BELOW_REQUEST_QUANTUM")
        current.iso = next_iso
        current.exposure_ns = next_exp
        current.last_adjustment_ms = now_ms
        current.saturated = (
            next_exp in (exp_lower, exp_upper)
            if request.axis is SoftwareAeAxis.ISO
            else next_iso in (iso_lower, iso_upper)
        )
        return self._decision(
            True,
            "UPDATED_AT_AXIS_LIMIT" if current.saturated else "UPDATED_FROM_LIVE_LUMA",
        )

    def _decision(self, changed: bool, reason: str) -> SoftwareAeDecision:
        assert self.state is not None
        return SoftwareAeDecision(
            True,
            self.state.signature[3],
            self.state.iso,
            self.state.exposure_ns,
            self.state.target_luma,
            self.state.measured_luma,
            self.state.correction_stops,
            changed,
            self.state.saturated,
            reason,
        )

    @staticmethod
    def _unavailable(axis: SoftwareAeAxis, changed: bool, reason: str) -> SoftwareAeDecision:
        return SoftwareAeDecision(False, axis, None, None, 0.0, 0.0, 0.0, changed, False, reason)


def validate_software_ae_priority_state_machine() -> int:
    """Temporal contracts that a per-crop static coordinator matrix cannot exercise."""
    cases = 0

    iso_request = SoftwareAeRequest(
        generation=7,
        mode=VideoMode.NDR,
        detail_profile=DetailProfile.FOCUSED_DETAIL,
        axis=SoftwareAeAxis.ISO,
        fixed_iso=200,
    )
    controller = SoftwareAePriorityModel()
    initialized = controller.resolve(iso_request, SoftwareAeSample(0.25, 0.0, 0.0, 1_000), 1_000)
    assert initialized.ready and initialized.iso == 200 and initialized.exposure_ns == 20_000_000
    cases += 1

    clipped = controller.resolve(iso_request, SoftwareAeSample(0.10, 0.12, 0.0, 1_200), 1_200)
    assert clipped.ready and clipped.changed and clipped.correction_stops < 0
    assert (clipped.exposure_ns or 0) < 20_000_000
    cases += 1

    recovered = controller.resolve(iso_request, SoftwareAeSample(0.02, 0.0, 0.0, 1_400), 1_400)
    assert recovered.ready and recovered.changed and recovered.correction_stops > 0
    cases += 1

    stale = controller.resolve(
        iso_request,
        SoftwareAeSample(0.25, 0.0, 0.0, 1_400),
        1_400 + SoftwareAePriorityModel.MAX_SAMPLE_AGE_MS + 1,
    )
    assert not stale.ready and stale.reason == "METER_UNAVAILABLE_OR_STALE"
    cases += 1

    shutter = SoftwareAePriorityModel().resolve(
        SoftwareAeRequest(
            generation=7,
            mode=VideoMode.PRO,
            detail_profile=DetailProfile.FOCUSED_DETAIL,
            axis=SoftwareAeAxis.SHUTTER,
            fixed_exposure_ns=5_000_000,
        ),
        SoftwareAeSample(0.25, 0.0, 0.0, 1_000),
        1_000,
    )
    assert shutter.ready and shutter.iso == 800 and shutter.exposure_ns == 5_000_000
    cases += 1

    generation_controller = SoftwareAePriorityModel()
    frame = SoftwareAeSample(0.25, 0.0, 0.0, 1_000)
    assert generation_controller.resolve(iso_request, frame, 1_000).ready
    rejected = generation_controller.resolve(
        SoftwareAeRequest(**{**asdict(iso_request), "generation": 8}),
        frame,
        1_050,
    )
    assert not rejected.ready and rejected.reason == "METER_PREDATES_CONTROL_CONTEXT"
    cases += 1
    new_anchor = generation_controller.resolve(
        SoftwareAeRequest(
            **{
                **asdict(iso_request),
                "generation": 8,
                "metered_exposure_ns": 20_000_000,
            }
        ),
        SoftwareAeSample(0.30, 0.0, 0.0, 1_200),
        1_200,
    )
    assert new_anchor.ready and new_anchor.exposure_ns == 40_000_000
    assert abs(new_anchor.target_luma - 0.30) < 1e-9
    cases += 1

    ev_controller = SoftwareAePriorityModel()
    neutral = ev_controller.resolve(iso_request, frame, 1_000)
    plus_one = ev_controller.resolve(
        SoftwareAeRequest(**{**asdict(iso_request), "requested_ev_stops": 1.0}),
        SoftwareAeSample(0.25, 0.0, 0.0, 1_200),
        1_200,
    )
    restored = ev_controller.resolve(
        iso_request,
        SoftwareAeSample(0.50, 0.0, 0.0, 1_400),
        1_400,
    )
    assert neutral.exposure_ns == restored.exposure_ns == 20_000_000
    assert plus_one.exposure_ns == 40_000_000
    cases += 1

    profile_corrections: dict[DetailProfile, float] = {}
    for profile in DetailProfile:
        profile_controller = SoftwareAePriorityModel()
        profile_request = SoftwareAeRequest(**{**asdict(iso_request), "detail_profile": profile})
        profile_controller.resolve(profile_request, frame, 1_000)
        response = profile_controller.resolve(
            profile_request,
            SoftwareAeSample(0.05, 0.0, 0.0, 1_200),
            1_200,
        )
        profile_corrections[profile] = response.correction_stops
        cases += 1
    assert profile_corrections == {
        DetailProfile.NATURAL_DETAIL: 0.32,
        DetailProfile.FOCUSED_DETAIL: 0.44,
        DetailProfile.EXTREME_DETAIL: 0.56,
    }

    adversarial_controller = SoftwareAePriorityModel()
    adversarial_request = SoftwareAeRequest(
        **{**asdict(iso_request), "requested_ev_stops": float("nan")}
    )
    adversarial_initial = adversarial_controller.resolve(
        adversarial_request,
        SoftwareAeSample(2.0, float("nan"), float("nan"), 1_000),
        1_000,
    )
    assert adversarial_initial.ready and adversarial_initial.target_luma == 0.90
    assert adversarial_initial.exposure_ns == 20_000_000
    cases += 1
    deadband = adversarial_controller.resolve(
        adversarial_request,
        SoftwareAeSample(0.90, float("nan"), float("nan"), 1_200),
        1_200,
    )
    assert deadband.ready and deadband.reason == "WITHIN_DEADBAND"
    assert deadband.correction_stops == 0.0
    cases += 1

    saturation_controller = SoftwareAePriorityModel()
    saturation_request = SoftwareAeRequest(
        **{
            **asdict(iso_request),
            "fixed_iso": 400,
            "metered_exposure_ns": 30_000_000,
        }
    )
    saturation_controller.resolve(
        saturation_request,
        SoftwareAeSample(0.25, 0.0, 0.0, 1_000),
        1_000,
    )
    saturation = saturation_controller.resolve(
        saturation_request,
        SoftwareAeSample(0.001, 0.0, 0.0, 1_200),
        1_200,
    )
    saturation = saturation_controller.resolve(
        saturation_request,
        SoftwareAeSample(0.001, 0.0, 0.0, 1_400),
        1_400,
    )
    assert saturation.ready and saturation.saturated and saturation.exposure_ns == 40_000_000
    cases += 1
    return cases


@dataclass(frozen=True)
class TransportCapabilities:
    raw_capture: bool
    manual_sensor: bool
    camera_p010: bool
    gpu_ten_bit: bool
    encoder_main10: bool
    fp16_processing: bool


@dataclass(frozen=True)
class DeliveryAdmission:
    requested_mode: VideoMode
    admitted: bool
    failures: tuple[str, ...]


def resolve_delivery_admission(mode: VideoMode, caps: TransportCapabilities) -> DeliveryAdmission:
    failures: list[str] = []
    if mode is VideoMode.RAW:
        if not caps.raw_capture or not caps.manual_sensor:
            failures.append("RAW_CAPTURE_UNAVAILABLE")
    else:
        if not (caps.camera_p010 and caps.gpu_ten_bit and caps.encoder_main10):
            failures.append("TEN_BIT_TRANSPORT_UNAVAILABLE")
        if mode is VideoMode.NDR and not (caps.raw_capture and caps.manual_sensor and caps.fp16_processing):
            failures.append("SENSOR_LINEAR_NDR_UNAVAILABLE")
    return DeliveryAdmission(mode, not failures, tuple(failures))


def transport_capability_matrix() -> Iterable[TransportCapabilities]:
    return (TransportCapabilities(*values) for values in itertools.product((False, True), repeat=6))


@dataclass(frozen=True)
class PrecisionProof:
    mode: VideoMode
    source_bits: int
    processing_bits: int
    storage_bits: int
    encoder_bits: int | None

    def strict_ten_bit(self) -> bool:
        stages = [self.source_bits, self.processing_bits, self.storage_bits]
        if self.mode is not VideoMode.RAW:
            stages.append(self.encoder_bits or 0)
        return min(stages) >= 10 and (self.mode is VideoMode.RAW or self.encoder_bits == 10)


def precision_proofs() -> Iterable[tuple[PrecisionProof, bool]]:
    for mode in VideoMode:
        encoder = None if mode is VideoMode.RAW else 10
        yield PrecisionProof(mode, 10, 16, 10, encoder), True
        yield PrecisionProof(mode, 8, 16, 10, encoder), False
        yield PrecisionProof(mode, 10, 8, 10, encoder), False
        yield PrecisionProof(mode, 10, 16, 8, encoder), False
        if mode is not VideoMode.RAW:
            yield PrecisionProof(mode, 10, 16, 10, 8), False


class NdrMode(str, Enum):
    NDR10_PLUS = "NDR10_PLUS"
    NIVUKX_VISION = "NIVUKX_VISION"


@dataclass(frozen=True)
class NdrLiveProfile:
    sensor_diffuse_scene: float
    signal_gamma: float
    toe_width: float
    toe_amount: float
    highlight_desat: float
    shadow_midtone_lift: float
    midtone_contrast: float
    chroma_boost: float
    sky_chroma_bonus: float
    local_detail_strength: float
    local_detail_lower_stops: float
    local_detail_upper_stops: float


NDR_CANONICAL = {
    NdrMode.NDR10_PLUS: NdrLiveProfile(
        0.180, 0.859937, 0.238882, 0.024780, 0.149718, 0.167837,
        0.100943, 0.038361, 0.062392, 0.400000, -0.060000, 0.085000,
    ),
    NdrMode.NIVUKX_VISION: NdrLiveProfile(
        0.180, 0.800034, 0.289876, 0.031033, 0.128953, 0.232156,
        0.174135, 0.091565, 0.080000, 0.600000, -0.085000, 0.125000,
    ),
}

DETAIL_SCALE = {
    DetailProfile.NATURAL_DETAIL: (0.80, 0.85),
    DetailProfile.FOCUSED_DETAIL: (1.00, 1.00),
    DetailProfile.EXTREME_DETAIL: (1.12, 1.08),
}


# Keep profile construction named: field-order shortcuts can silently corrupt JNI packet parity.
def resolve_ndr_profile(mode: NdrMode, detail: DetailProfile) -> NdrLiveProfile:
    base = NDR_CANONICAL[mode]
    strength_scale, range_scale = DETAIL_SCALE[detail]
    return NdrLiveProfile(
        sensor_diffuse_scene=base.sensor_diffuse_scene,
        signal_gamma=base.signal_gamma,
        toe_width=base.toe_width,
        toe_amount=base.toe_amount,
        highlight_desat=base.highlight_desat,
        shadow_midtone_lift=base.shadow_midtone_lift,
        midtone_contrast=base.midtone_contrast,
        chroma_boost=base.chroma_boost,
        sky_chroma_bonus=base.sky_chroma_bonus,
        local_detail_strength=float(_clamp(base.local_detail_strength * strength_scale, 0.0, 0.75)),
        local_detail_lower_stops=float(_clamp(base.local_detail_lower_stops * range_scale, -0.20, 0.0)),
        local_detail_upper_stops=float(_clamp(base.local_detail_upper_stops * range_scale, 0.0, 0.20)),
    )


def ndr_packet_routes(mode: NdrMode, detail: DetailProfile) -> dict[str, NdrLiveProfile]:
    packet = resolve_ndr_profile(mode, detail)
    return {"RAW_MONITOR": packet, "VISIBLE_PREVIEW": packet, "RECORDING": packet}


def validate_ndr_routes() -> int:
    cases = 0
    tone_fields = tuple(NdrLiveProfile.__dataclass_fields__)[:-3]
    for mode, detail in itertools.product(tuple(NdrMode), tuple(DetailProfile)):
        routes = ndr_packet_routes(mode, detail)
        assert len(set(routes.values())) == 1
        packet = routes["RAW_MONITOR"]
        base = NDR_CANONICAL[mode]
        for field in tone_fields:
            assert getattr(packet, field) == getattr(base, field)
        assert 0.0 <= packet.local_detail_strength <= 0.75
        assert -0.20 <= packet.local_detail_lower_stops <= 0.0
        assert 0.0 <= packet.local_detail_upper_stops <= 0.20
        cases += len(routes)
    for mode in NdrMode:
        natural = resolve_ndr_profile(mode, DetailProfile.NATURAL_DETAIL)
        focused = resolve_ndr_profile(mode, DetailProfile.FOCUSED_DETAIL)
        extreme = resolve_ndr_profile(mode, DetailProfile.EXTREME_DETAIL)
        assert natural.local_detail_strength < focused.local_detail_strength < extreme.local_detail_strength
    return cases


def overlay_level(enabled: bool, clean_hud: bool, requested: float) -> float:
    if not enabled or clean_hud or not math.isfinite(requested):
        return 0.0
    return float(_clamp(requested, 0.0, 1.0))


def overlay_accepts_mask(enabled: bool, clean_hud: bool, width: int, height: int, payload: int) -> bool:
    if not enabled or clean_hud or width <= 0 or height <= 0:
        return False
    required = width * height
    return 1 <= required <= 2_147_483_647 and payload >= required


def overlay_should_draw(enabled: bool, clean_hud: bool, level: float, bitmap_present: bool) -> bool:
    return enabled and not clean_hud and math.isfinite(level) and level > 0.0 and bitmap_present


@dataclass
class OverlayState:
    enabled: bool = False
    clean_hud: bool = True
    bitmap_present: bool = False

    def set_enabled(self, enabled: bool) -> None:
        self.enabled = enabled
        if not enabled:
            self.bitmap_present = False

    def set_clean_hud(self, enabled: bool) -> None:
        self.clean_hud = enabled
        if enabled:
            self.bitmap_present = False

    def set_mask(self, width: int, height: int, payload: int) -> None:
        self.bitmap_present = overlay_accepts_mask(self.enabled, self.clean_hud, width, height, payload)

    def detach(self) -> None:
        self.bitmap_present = False


def validate_overlay_matrix() -> int:
    cases = 0
    levels = (float("nan"), float("-inf"), -1.0, 0.0, 0.5, 2.0, float("inf"))
    for enabled, clean, requested, bitmap in itertools.product((False, True), (False, True), levels, (False, True)):
        level = overlay_level(enabled, clean, requested)
        draw = overlay_should_draw(enabled, clean, level, bitmap)
        assert 0.0 <= level <= 1.0
        assert draw == (enabled and not clean and math.isfinite(requested) and requested > 0 and bitmap)
        cases += 1
    geometries = (
        (256, 144, 256 * 144, True),
        (256, 144, 256 * 144 - 1, False),
        (0, 144, 0, False),
        (-1, 144, 144, False),
        (2_147_483_647, 2, 2_147_483_647, False),
        (1, 1, -1, False),
    )
    for enabled, clean, geometry in itertools.product((False, True), (False, True), geometries):
        width, height, payload, geometrically_valid = geometry
        accepted = overlay_accepts_mask(enabled, clean, width, height, payload)
        assert accepted == (enabled and not clean and geometrically_valid)
        cases += 1
    state = OverlayState()
    state.set_mask(256, 144, 256 * 144)
    assert not state.bitmap_present
    cases += 1
    state.set_clean_hud(False)
    state.set_enabled(True)
    state.set_mask(256, 144, 256 * 144)
    assert state.bitmap_present
    cases += 1
    state.set_clean_hud(True)
    assert not state.bitmap_present
    cases += 1
    state.set_clean_hud(False)
    state.set_mask(256, 144, 256 * 144)
    state.detach()
    assert not state.bitmap_present
    cases += 1
    return cases


@dataclass(frozen=True)
class SourceContract:
    name: str
    passed: bool


def verify_source_contracts(repo_root: Path) -> tuple[SourceContract, ...]:
    coordinator = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/camera/UnifiedVideoControlCoordinator.kt").read_text(encoding="utf-8")
    camera_controller = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").read_text(encoding="utf-8")
    camera_view_model = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").read_text(encoding="utf-8")
    ndr = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/rendering/NdrLiveProfile.kt").read_text(encoding="utf-8")
    runtime = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").read_text(encoding="utf-8")
    overlay_policy = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/overlays/SubjectMaskPreviewVisualizationPolicy.kt").read_text(encoding="utf-8")
    overlay = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/overlays/UIOverlay.kt").read_text(encoding="utf-8")
    ndr_output = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewSdrOutputFlag.kt").read_text(encoding="utf-8")
    vulkan = (repo_root / "app/src/main/cpp/vulkan_bridge.cpp").read_text(encoding="utf-8")
    raw_monitor = (repo_root / "app/src/main/cpp/shaders/vulkan/raw_monitor_tonemap.comp").read_text(encoding="utf-8")
    encoder = (repo_root / "app/src/main/cpp/offscreen_egl_encoder_bridge.cpp").read_text(encoding="utf-8")
    mandatory = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/Mandatory10BitRecordingPolicy.kt").read_text(encoding="utf-8")
    recording = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").read_text(encoding="utf-8")
    tuning_literals = (
        "focusConfidenceFloor = 0.72f",
        "focusSlewDioptersPerSecond = 2f",
        "wbConfidenceFloor = 0.58f",
        "focusConfidenceFloor = 0.62f",
        "focusSlewDioptersPerSecond = 3f",
        "wbConfidenceFloor = 0.62f",
        "focusConfidenceFloor = 0.56f",
        "focusSlewDioptersPerSecond = 5f",
        "wbConfidenceFloor = 0.68f",
        "UnifiedVideoControlMode.NDR -> 0.70f",
        "UnifiedVideoControlMode.LOG -> 0.68f",
        "UnifiedVideoControlMode.RAW -> 0.72f",
    )
    return (
        SourceContract(
            "unified_modes_profiles_and_single_decision",
            all(f"    {mode.value}" in coordinator for mode in VideoMode)
            and all(f"VideoDetailProfile.{profile.value}" in coordinator for profile in DetailProfile)
            and "UnifiedVideoControlDecision(" in coordinator
            and all(literal in coordinator for literal in tuning_literals),
        ),
        SourceContract(
            "unified_coordinator_live_camera2_wiring",
            "val unifiedControlDecision = if (videoRenderContractActive)" in camera_controller
            and "resolveUnifiedVideoControlDecision(" in camera_controller
            and "UnifiedVideoControlCoordinator.resolve(" in camera_controller
            and "detailProfile = selectedVideoDetailProfile" in camera_controller
            and "val unifiedFocus = unifiedControlDecision?.focus" in camera_controller
            and "unifiedControlDecision.whiteBalance.fixedKelvin" in camera_controller
            and "UnifiedExposureAuthority.USER_MANUAL_SENSOR" in camera_controller
            and "builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION]" in camera_controller
            and "professionalControlsActive = professionalVideoControlsActive" in camera_controller
            and "camera.setProfessionalVideoControlsActive(" in camera_view_model
            and "_sdrQualityIntent.value == SdrQualityIntent.PRO" in camera_view_model
            and "intent == SdrQualityIntent.PRO || _manualControlsVisible.value" in camera_view_model,
        ),
        SourceContract(
            "partial_manual_requires_proven_adaptive_authority",
            "USER_ISO_PRIORITY" in coordinator
            and "USER_SHUTTER_PRIORITY" in coordinator
            and "USER_SOFTWARE_ISO_PRIORITY" in coordinator
            and "USER_SOFTWARE_SHUTTER_PRIORITY" in coordinator
            and "PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE" in coordinator
            and "unifiedControlDecision == null && cinematicExposurePlan.activateManualExposure"
            in camera_controller
            and "CONTROL_AE_AVAILABLE_PRIORITY_MODES" in camera_controller
            and "softwareAePriorityController" in camera_controller
            and "releaseStaleSoftwareAePriorityAuthority" in camera_controller,
        ),
        SourceContract(
            "ndr_canonical_packet_and_detail_identity",
            "NdrToneOperator.paramsFor(canonicalMode)" in ndr
            and "detailTuning(detailProfileId)" in ndr
            and "packet.enhancementUniforms.detailProfileId" in runtime
            and "liveProfile" in runtime,
        ),
        SourceContract(
            "ndr_native_monitor_preview_record_consumers",
            "push.sensorDiffuseScene = correction.sensorDiffuseScene" in vulkan
            and all(token in raw_monitor for token in ("vec4 toneProfile", "vec4 character", "vec4 detailProfile"))
            and all(
                token in encoder
                for token in (
                    "uniform vec4 uNdrToneProfile",
                    "uniform vec4 uNdrCharacterProfile",
                    "uniform vec4 uNdrDetailProfile",
                    "glUniform4fv(activeMainLoc.ndrToneProfile",
                    "glUniform4fv(activeMainLoc.ndrCharacterProfile",
                    "glUniform4fv(activeMainLoc.ndrDetailProfile",
                )
            ),
        ),
        SourceContract(
            "bitmap_overlay_fail_closed_and_purge",
            "DEFAULT_ENABLED: Boolean = false" in overlay_policy
            and "SubjectMaskPreviewVisualizationPolicy.shouldDraw(" in overlay
            and "clearSubjectMaskVisualization()" in overlay,
        ),
        SourceContract(
            "ndr_eight_bit_sdr_monitor_cannot_enable",
            "var enabled: Boolean = false" in ndr_output
            and "Ignored request to enable legacy 8-bit NDR monitor output" in ndr_output,
        ),
        SourceContract(
            "recording_strict_ten_bit_refuses_fallback",
            "NO fallback to 8-bit" in mandatory
            and "HEVCProfileMain10" in mandatory
            and "Strict 10-bit Main10 unavailable; refusing 8-bit fallback" in recording,
        ),
    )


def run_reference_matrix(repo_root: Path | None = None) -> dict[str, object]:
    repo_root = repo_root or Path(__file__).resolve().parents[2]
    control_count = 0
    for case in control_matrix_cases():
        request = request_for_case(case)
        validate_control_decision(request, resolve_controls(request))
        control_count += 1
    partial_manual_parity_count = validate_partial_manual_metered_exposure_parity()
    software_ae_state_count = validate_software_ae_priority_state_machine()

    admission_count = admitted_count = 0
    for mode, caps in itertools.product(tuple(VideoMode), tuple(transport_capability_matrix())):
        admission = resolve_delivery_admission(mode, caps)
        assert admission.requested_mode is mode
        expected = (
            caps.raw_capture and caps.manual_sensor
            if mode is VideoMode.RAW
            else caps.camera_p010
            and caps.gpu_ten_bit
            and caps.encoder_main10
            and (
                mode is not VideoMode.NDR
                or (caps.raw_capture and caps.manual_sensor and caps.fp16_processing)
            )
        )
        assert admission.admitted == expected
        admission_count += 1
        admitted_count += int(admission.admitted)

    precision_count = 0
    for proof, expected in precision_proofs():
        assert proof.strict_ten_bit() == expected
        precision_count += 1

    ndr_count = validate_ndr_routes()
    overlay_count = validate_overlay_matrix()
    source_contracts = verify_source_contracts(repo_root)
    assert all(contract.passed for contract in source_contracts)
    total = (
        control_count
        + partial_manual_parity_count
        + software_ae_state_count
        + admission_count
        + precision_count
        + ndr_count
        + overlay_count
        + len(source_contracts)
    )
    return {
        "status": "PASS",
        "scope": "DETERMINISTIC_HOST_CONTRACT_SIMULATION_ONLY",
        "total_cases": total,
        "checkpoints": {
            "unified_wb_iso_exposure_shutter_ev_focus": {
                "status": "PASS",
                "cases": control_count,
                "dimensions": "5 modes x 3 detail profiles x 4 lighting x 3 motion x 4 manual x 8 control-capability sets",
            },
            "partial_manual_adaptive_authority_matrix": {
                "status": "PASS",
                "cases": partial_manual_parity_count,
                "contract": "hardware/software priority only; stale or unavailable meter returns to platform AE",
            },
            "software_ae_priority_temporal_state_machine": {
                "status": "PASS",
                "cases": software_ae_state_count,
                "contract": "fixed axis, clip attack, dark recovery, stale release, cross-session rejection, neutral EV rebasing, profile response and saturation",
            },
            "strict_delivery_admission": {
                "status": "PASS",
                "cases": admission_count,
                "admitted": admitted_count,
                "rejected_fail_closed": admission_count - admitted_count,
            },
            "strict_precision_no_eight_bit_substitution": {"status": "PASS", "cases": precision_count},
            "ndr_packet_detail_preview_record_parity": {"status": "PASS", "cases": ndr_count},
            "bitmap_overlay_fail_closed": {"status": "PASS", "cases": overlay_count},
            "production_source_contracts": {
                "status": "PASS",
                "cases": len(source_contracts),
                "names": [contract.name for contract in source_contracts],
            },
        },
        "not_proven_without_device": [
            "physical 4096x3072/4K 25 fps cadence and zero dropped frames",
            "camera HAL, ISP, P010, GPU, encoder and thermal behaviour",
            "HDR display luminance/colour-volume correctness",
            "recorded-file bit-depth/profile conformance",
            "subjective cinema/IMAX image quality",
        ],
    }


if __name__ == "__main__":
    print(json.dumps(run_reference_matrix(), indent=2, sort_keys=True))
