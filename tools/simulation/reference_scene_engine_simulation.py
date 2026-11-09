"""Image-domain stress simulation for NeoProCamX camera-control engines.

This host tool consumes display-referred RGB reference images and derives deterministic
scene signals for the unified WB/ISO/shutter/EV/focus model.  It is intentionally honest:
an 8-bit PNG cannot prove RAW sensor response, lens MTF, autofocus accuracy, HDR display
luminance, or cinema/IMAX equivalence.  It can expose clipping, pan-dependent metering
instability, intrinsic source blur, and control-authority regressions before device QA.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
from PIL import Image

from unified_camera_engine_simulation import (
    AutomaticSignals,
    ControlRequest,
    DetailProfile,
    ExposureAuthority,
    FocusAuthority,
    ManualControls,
    Rejection,
    SensorLimits,
    VideoMode,
    WhiteBalanceAuthority,
    resolve_controls,
    validate_control_decision,
)


@dataclass(frozen=True)
class CropSignals:
    name: str
    p50_linear_luma: float
    p995_linear_luma: float
    clipped_rgb_fraction: float
    deep_shadow_fraction: float
    metered_iso: int
    metered_exposure_ns: int
    highlight_bias_stops: float
    estimated_kelvin: int
    estimated_tint: int
    wb_confidence: float
    focus_confidence: float
    detail_score: float


@dataclass(frozen=True)
class ReferenceImageReport:
    path: str
    width: int
    height: int
    clipped_rgb_fraction: float
    deep_shadow_fraction: float
    p001_linear_luma: float
    p50_linear_luma: float
    p999_linear_luma: float
    encoded_dynamic_range_stops: float
    estimated_kelvin: int
    estimated_tint: int
    wb_confidence: float
    detail_grid: tuple[tuple[float, ...], ...]
    center_to_outer_detail_ratio: float
    intrinsic_focus_nonuniformity: bool
    crop_signals: tuple[CropSignals, ...]


def _srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    return np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)


def _linear_luma(linear_rgb: np.ndarray) -> np.ndarray:
    return (
        linear_rgb[..., 0] * 0.2126
        + linear_rgb[..., 1] * 0.7152
        + linear_rgb[..., 2] * 0.0722
    )


def _perceptual_luma(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722


def _load_rgb(path: Path, maximum_dimension: int = 1600) -> tuple[np.ndarray, int, int]:
    with Image.open(path) as image:
        image = image.convert("RGB")
        width, height = image.size
        scale = min(1.0, maximum_dimension / max(width, height))
        if scale < 1.0:
            image = image.resize(
                (max(1, round(width * scale)), max(1, round(height * scale))),
                Image.Resampling.LANCZOS,
            )
        rgb = np.asarray(image, dtype=np.float32) / 255.0
    return rgb, width, height


def _detail_score(perceptual_luma: np.ndarray) -> float:
    if perceptual_luma.shape[0] < 3 or perceptual_luma.shape[1] < 3:
        return 0.0
    center = perceptual_luma[1:-1, 1:-1]
    laplacian = (
        4.0 * center
        - perceptual_luma[:-2, 1:-1]
        - perceptual_luma[2:, 1:-1]
        - perceptual_luma[1:-1, :-2]
        - perceptual_luma[1:-1, 2:]
    )
    # The robust percentile is less brightness/content-dependent than raw variance and
    # is not dominated by one clipped neon edge.
    return float(np.percentile(np.abs(laplacian), 85.0))


def _detail_grid(rgb: np.ndarray, rows: int = 3, columns: int = 4) -> tuple[tuple[float, ...], ...]:
    luma = _perceptual_luma(rgb)
    height, width = luma.shape
    values: list[tuple[float, ...]] = []
    for row in range(rows):
        y0, y1 = round(row * height / rows), round((row + 1) * height / rows)
        row_values = []
        for column in range(columns):
            x0, x1 = round(column * width / columns), round((column + 1) * width / columns)
            row_values.append(_detail_score(luma[y0:y1, x0:x1]))
        values.append(tuple(row_values))
    return tuple(values)


def _estimate_white_balance(rgb: np.ndarray, linear_rgb: np.ndarray) -> tuple[int, int, float]:
    maximum = np.max(rgb, axis=-1)
    minimum = np.min(rgb, axis=-1)
    saturation = (maximum - minimum) / np.maximum(maximum, 1e-6)
    luma = _linear_luma(linear_rgb)
    candidate = (saturation <= 0.10) & (luma >= 0.025) & (luma <= 0.85)
    count = int(np.count_nonzero(candidate))
    minimum_count = max(64, int(candidate.size * 0.001))
    if count < minimum_count:
        return 5_600, 0, 0.0

    neutral = np.median(linear_rgb[candidate], axis=0)
    # Display RGB -> XYZ (D65). McCamy CCT is diagnostic only for these artistically
    # graded PNGs; it is not a replacement for Camera2 RAW calibration metadata.
    xyz = np.array(
        [
            0.4124564 * neutral[0] + 0.3575761 * neutral[1] + 0.1804375 * neutral[2],
            0.2126729 * neutral[0] + 0.7151522 * neutral[1] + 0.0721750 * neutral[2],
            0.0193339 * neutral[0] + 0.1191920 * neutral[1] + 0.9503041 * neutral[2],
        ],
        dtype=np.float64,
    )
    denominator = float(np.sum(xyz))
    if denominator <= 1e-9:
        return 5_600, 0, 0.0
    x, y = float(xyz[0] / denominator), float(xyz[1] / denominator)
    n = (x - 0.3320) / max(1e-6, 0.1858 - y)
    cct = 449.0 * n**3 + 3525.0 * n**2 + 6823.3 * n + 5520.33
    kelvin = int(round(float(np.clip(cct, 2_000.0, 10_000.0))))
    green = float(neutral[1])
    magenta_axis = green - 0.5 * float(neutral[0] + neutral[2])
    tint = int(round(np.clip(magenta_axis / max(green, 1e-6) * 150.0, -150.0, 150.0)))
    population_confidence = min(1.0, count / max(1.0, candidate.size * 0.08))
    chroma_confidence = 1.0 - float(np.median(saturation[candidate]) / 0.10)
    confidence = float(np.clip(population_confidence * chroma_confidence, 0.0, 1.0))
    return kelvin, tint, confidence


def _metered_sensor_values(p50_luma: float, target_fps: int = 25) -> tuple[int, int]:
    # Relative host model anchored at ISO100, 4 ms for an 18% midtone. It does not
    # claim device calibration; it creates coherent automatic inputs for authority tests.
    required_exposure_ns = 4_000_000.0 * 0.18 / max(p50_luma, 1e-5)
    frame_budget_ns = 1_000_000_000 // max(1, target_fps)
    exposure_ns = int(np.clip(required_exposure_ns, 125_000.0, frame_budget_ns))
    residual_gain = required_exposure_ns / max(1.0, exposure_ns)
    iso = int(np.clip(round(100.0 * residual_gain), 50, 3_200))
    return iso, exposure_ns


def _crop_windows(rgb: np.ndarray, crop_ratio: float = 0.62) -> Iterable[tuple[str, np.ndarray]]:
    height, width = rgb.shape[:2]
    crop_width = max(4, round(width * crop_ratio))
    crop_height = max(4, round(height * crop_ratio))
    for row, y_fraction in enumerate((0.0, 0.5, 1.0)):
        y0 = round((height - crop_height) * y_fraction)
        for column, x_fraction in enumerate((0.0, 0.5, 1.0)):
            x0 = round((width - crop_width) * x_fraction)
            yield f"r{row}c{column}", rgb[y0 : y0 + crop_height, x0 : x0 + crop_width]


def _crop_signals(name: str, rgb: np.ndarray) -> CropSignals:
    linear = _srgb_to_linear(rgb)
    luma = _linear_luma(linear)
    p50 = float(np.percentile(luma, 50.0))
    p995 = float(np.percentile(luma, 99.5))
    metered_iso, metered_exposure_ns = _metered_sensor_values(p50)
    highlight_bias = min(0.0, math.log2(0.94 / max(p995, 1e-6)))
    kelvin, tint, wb_confidence = _estimate_white_balance(rgb, linear)
    detail = _detail_score(_perceptual_luma(rgb))
    focus_confidence = float(np.clip(detail / 0.055, 0.0, 1.0))
    return CropSignals(
        name=name,
        p50_linear_luma=p50,
        p995_linear_luma=p995,
        clipped_rgb_fraction=float(np.mean(np.any(rgb >= (254.5 / 255.0), axis=-1))),
        deep_shadow_fraction=float(np.mean(luma <= 0.005)),
        metered_iso=metered_iso,
        metered_exposure_ns=metered_exposure_ns,
        highlight_bias_stops=float(np.clip(highlight_bias, -3.0, 0.0)),
        estimated_kelvin=kelvin,
        estimated_tint=tint,
        wb_confidence=wb_confidence,
        focus_confidence=focus_confidence,
        detail_score=detail,
    )


def analyze_reference(path: Path) -> ReferenceImageReport:
    rgb, width, height = _load_rgb(path)
    linear = _srgb_to_linear(rgb)
    luma = _linear_luma(linear)
    grid = _detail_grid(rgb)
    flattened = np.asarray(grid, dtype=np.float64)
    center = float(np.median(flattened[1:2, 1:3]))
    outer_mask = np.ones_like(flattened, dtype=bool)
    outer_mask[1:2, 1:3] = False
    outer = float(np.median(flattened[outer_mask]))
    ratio = center / max(outer, 1e-9)
    p001 = float(np.percentile(luma, 0.1))
    p999 = float(np.percentile(luma, 99.9))
    dynamic_range = math.log2(max(p999, 1e-6) / max(p001, 1e-6))
    kelvin, tint, wb_confidence = _estimate_white_balance(rgb, linear)
    crops = tuple(_crop_signals(name, crop) for name, crop in _crop_windows(rgb))
    return ReferenceImageReport(
        path=str(path.resolve()),
        width=width,
        height=height,
        clipped_rgb_fraction=float(np.mean(np.any(rgb >= (254.5 / 255.0), axis=-1))),
        deep_shadow_fraction=float(np.mean(luma <= 0.005)),
        p001_linear_luma=p001,
        p50_linear_luma=float(np.percentile(luma, 50.0)),
        p999_linear_luma=p999,
        encoded_dynamic_range_stops=dynamic_range,
        estimated_kelvin=kelvin,
        estimated_tint=tint,
        wb_confidence=wb_confidence,
        detail_grid=grid,
        center_to_outer_detail_ratio=ratio,
        intrinsic_focus_nonuniformity=ratio > 1.35 or ratio < (1.0 / 1.35),
        crop_signals=crops,
    )


def _request_for_crop(
    crop: CropSignals,
    mode: VideoMode,
    profile: DetailProfile,
    manual: ManualControls,
    *,
    software_priority_ready: bool = True,
    software_iso_exposure_ns: int | None = None,
    software_shutter_iso: int | None = None,
) -> ControlRequest:
    ev_stops = manual.ev_index * 0.5 if manual.ev_override else 0.0
    metered_product = crop.metered_iso * crop.metered_exposure_ns * (2.0**ev_stops)
    if software_priority_ready and manual.iso is not None and manual.exposure_time_ns is None:
        software_iso_exposure_ns = software_iso_exposure_ns or int(
            np.clip(round(metered_product / max(1, manual.iso)), 125_000, 40_000_000)
        )
    if software_priority_ready and manual.iso is None and manual.exposure_time_ns is not None:
        software_shutter_iso = software_shutter_iso or int(
            np.clip(round(metered_product / max(1, manual.exposure_time_ns)), 50, 3_200)
        )
    return ControlRequest(
        generation=31,
        mode=mode,
        detail_profile=profile,
        target_fps=25,
        recording=True,
        limits=SensorLimits(
            supports_software_iso_priority=(
                software_priority_ready
                and manual.iso is not None
                and manual.exposure_time_ns is None
            ),
            supports_software_shutter_priority=(
                software_priority_ready
                and manual.iso is None
                and manual.exposure_time_ns is not None
            ),
        ),
        manual=manual,
        automatic=AutomaticSignals(
            metered_iso=crop.metered_iso,
            metered_exposure_time_ns=crop.metered_exposure_ns,
            highlight_bias_stops=crop.highlight_bias_stops,
            ndr_bias_stops=min(0.0, crop.highlight_bias_stops * 0.8),
            measured_kelvin=crop.estimated_kelvin,
            measured_tint=crop.estimated_tint,
            wb_confidence=crop.wb_confidence,
            awb_converged=crop.wb_confidence >= 0.55,
            ai_focus_diopters=1.5,
            ai_focus_confidence=crop.focus_confidence,
            previous_ev_index=0,
            previous_focus_diopters=1.5,
            elapsed_seconds=0.04,
            software_iso_priority_exposure_time_ns=software_iso_exposure_ns,
            software_shutter_priority_iso=software_shutter_iso,
        ),
    )


def run_engine_matrix(reports: Sequence[ReferenceImageReport]) -> dict[str, object]:
    manual_cases = (
        ManualControls(),
        ManualControls(iso=400, ev_override=True),
        ManualControls(exposure_time_ns=8_000_000, ev_override=True),
        ManualControls(kelvin=5_600, tint=0),
        ManualControls(focus_diopters=1.5, focus_override=True),
    )
    cases = 0
    authority_counts = {
        "exposure": {authority.value: 0 for authority in ExposureAuthority},
        "white_balance": {authority.value: 0 for authority in WhiteBalanceAuthority},
        "focus": {authority.value: 0 for authority in FocusAuthority},
    }
    for report in reports:
        for crop in report.crop_signals:
            for mode in VideoMode:
                for profile in DetailProfile:
                    for manual in manual_cases:
                        request = _request_for_crop(crop, mode, profile, manual)
                        decision = resolve_controls(request)
                        validate_control_decision(request, decision)
                        authority_counts["exposure"][decision.exposure.authority.value] += 1
                        authority_counts["white_balance"][decision.white_balance.authority.value] += 1
                        authority_counts["focus"][decision.focus.authority.value] += 1
                        cases += 1
    return {"status": "PASS", "cases": cases, "authority_counts": authority_counts}


def _predicted_software_meter(
    crop: CropSignals,
    fixed_iso: int,
    exposure_ns: int,
) -> tuple[float, float]:
    """Predict luma/clip feedback from the current AE-OFF pair, not a new platform-AE solve."""
    reference_product = max(1.0, crop.metered_iso * crop.metered_exposure_ns)
    gain = fixed_iso * exposure_ns / reference_product
    average_luma = float(np.clip(0.18 * gain, 1e-5, 4.0))
    scene_highlight_ratio = crop.p995_linear_luma / max(crop.p50_linear_luma, 1e-5)
    predicted_highlight = 0.18 * scene_highlight_ratio * gain
    synthetic_clip = float(np.clip((predicted_highlight - 0.94) / 0.06, 0.0, 1.0) * 0.10)
    return average_luma, max(crop.clipped_rgb_fraction, synthetic_clip)


def _software_iso_priority_step(
    crop: CropSignals,
    profile: DetailProfile,
    fixed_iso: int,
    exposure_ns: int,
    target_luma: float = 0.18,
) -> int:
    average_luma, clip_fraction = _predicted_software_meter(
        crop,
        fixed_iso,
        exposure_ns,
    )
    luma_error_stops = math.log2(target_luma / max(average_luma, 1e-4))
    if average_luma >= 0.98 or clip_fraction >= 0.10:
        clip_stops = -2.0
    elif clip_fraction >= 0.04:
        clip_stops = -1.25
    elif clip_fraction >= 0.015:
        clip_stops = -0.80
    elif clip_fraction >= 0.004:
        clip_stops = -0.45
    else:
        clip_stops = math.inf
    requested_stops = min(luma_error_stops, clip_stops)
    attack_limit, release_limit = {
        DetailProfile.NATURAL_DETAIL: (0.45, 0.16),
        DetailProfile.FOCUSED_DETAIL: (0.65, 0.22),
        DetailProfile.EXTREME_DETAIL: (0.85, 0.28),
    }[profile]
    bright_emergency = average_luma >= 0.98 or clip_fraction >= 0.10
    attack = 1.5 if bright_emergency else attack_limit
    response = 0.72 if requested_stops < 0.0 else 0.38
    correction = float(np.clip(requested_stops * response, -attack, release_limit))
    if abs(correction) < 0.045:
        return exposure_ns
    return int(
        np.clip(
            round(exposure_ns * (2.0**correction)),
            125_000,
            40_000_000,
        )
    )


def run_pan_whiteout_regression(reports: Sequence[ReferenceImageReport]) -> dict[str, object]:
    cases = 0
    legacy_risk_cases = 0
    coordinator_regressions = 0
    worst_legacy_gain = 1.0
    worst_settled_luma_error_stops = 0.0
    stale_meter_fail_closed_cases = 0
    fixed_iso = 400
    for report in reports:
        first_crop = report.crop_signals[0]
        initial_product = first_crop.metered_iso * first_crop.metered_exposure_ns
        for profile in DetailProfile:
            # This is the only platform-AE initialization in the pan. Every following crop is
            # controlled from predicted live luma generated by the previous AE-OFF request pair.
            exposure_ns = int(np.clip(round(initial_product / fixed_iso), 125_000, 40_000_000))
            for crop in report.crop_signals:
                for _ in range(8):
                    exposure_ns = _software_iso_priority_step(
                        crop,
                        profile,
                        fixed_iso,
                        exposure_ns,
                    )
                    request = _request_for_crop(
                        crop,
                        VideoMode.PRO,
                        profile,
                        ManualControls(iso=fixed_iso, ev_override=True),
                        software_priority_ready=True,
                        software_iso_exposure_ns=exposure_ns,
                    )
                    decision = resolve_controls(request)
                    validate_control_decision(request, decision)
                    coordinator_regressions += int(
                        decision.exposure.authority
                        is not ExposureAuthority.USER_SOFTWARE_ISO_PRIORITY
                    )
                    coordinator_regressions += int(decision.exposure.iso != fixed_iso)
                    coordinator_regressions += int(
                        decision.exposure.exposure_time_ns != exposure_ns
                    )

                settled_luma, _ = _predicted_software_meter(
                    crop,
                    fixed_iso,
                    exposure_ns,
                )
                settled_error = abs(math.log2(0.18 / max(settled_luma, 1e-4)))
                worst_settled_luma_error_stops = max(
                    worst_settled_luma_error_stops,
                    settled_error,
                )

                # When the timestamped meter expires, the exact same partial control must not use
                # the crop's newly solved platform values as if AE were still active. It relinquishes
                # AE-OFF ownership and returns to platform AE.
                stale_request = _request_for_crop(
                    crop,
                    VideoMode.PRO,
                    profile,
                    ManualControls(iso=fixed_iso, ev_override=True),
                    software_priority_ready=False,
                )
                stale_decision = resolve_controls(stale_request)
                stale_ok = (
                    stale_decision.exposure.authority is ExposureAuthority.PLATFORM_AE
                    and Rejection.PARTIAL_MANUAL_ADAPTIVE_AUTHORITY_UNAVAILABLE
                    in stale_decision.rejections
                )
                stale_meter_fail_closed_cases += int(stale_ok)
                coordinator_regressions += int(not stale_ok)

                reference_product = max(1.0, crop.metered_iso * crop.metered_exposure_ns)
                legacy_gain = fixed_iso * 20_000_000 / reference_product
                worst_legacy_gain = max(worst_legacy_gain, legacy_gain)
                legacy_highlight = (
                    0.18
                    * crop.p995_linear_luma
                    / max(crop.p50_linear_luma, 1e-5)
                    * legacy_gain
                )
                legacy_risk_cases += int(legacy_gain >= 2.0 and legacy_highlight >= 1.0)
                cases += 1
    return {
        "status": "PASS" if coordinator_regressions == 0 else "FAIL",
        "cases": cases,
        "controller_iterations_per_crop": 8,
        "meter_model": "STATEFUL_PREVIOUS_AE_OFF_OUTPUT_NO_FRESH_PLATFORM_METER_PER_CROP",
        "legacy_whiteout_risk_cases": legacy_risk_cases,
        "coordinator_regressions": coordinator_regressions,
        "stale_meter_fail_closed_cases": stale_meter_fail_closed_cases,
        "worst_settled_luma_error_stops": worst_settled_luma_error_stops,
        "worst_legacy_relative_gain": worst_legacy_gain,
    }


def run(paths: Sequence[Path]) -> dict[str, object]:
    reports = tuple(analyze_reference(path) for path in paths)
    engine_matrix = run_engine_matrix(reports)
    pan_regression = run_pan_whiteout_regression(reports)
    return {
        "status": "PASS" if engine_matrix["status"] == pan_regression["status"] == "PASS" else "FAIL",
        "scope": "DISPLAY_RGB_HOST_STRESS_SIMULATION_ONLY",
        "references": [asdict(report) for report in reports],
        "checkpoints": {
            "wb_iso_shutter_ev_focus_all_modes_all_detail_profiles": engine_matrix,
            "partial_manual_pan_whiteout_regression": pan_regression,
        },
        "limitations": [
            "Generated 8-bit PNG references contain baked clipping, blur, grading and depth of field.",
            "Host simulation cannot prove physical lens focus, sensor response, Camera2 HAL compliance or frame cadence.",
            "Subjective cinema/IMAX equivalence requires controlled charts, calibrated lighting and optical measurements.",
        ],
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    missing = [str(path) for path in args.images if not path.is_file()]
    if missing:
        raise FileNotFoundError(f"Missing reference images: {missing}")
    report = run(args.images)
    payload = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
