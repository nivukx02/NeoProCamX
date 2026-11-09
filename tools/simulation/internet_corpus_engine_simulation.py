#!/usr/bin/env python3
"""Licensed 5,000-image stress benchmark for NeoProCamX video-control engines.

The source photographs are never shipped in the APK or committed to Git.  The pinned corpus is a
deterministic 5,000-image snapshot of ``pictograph/coco-5k`` (CC BY 4.0).  Every source is decoded and
used to build controlled, in-memory exposure/EV, ISO-noise, white-balance, defocus and motion/shutter
probes.  Those measurements then drive the pure Python mirror of the production unified video
coordinator for VIDEO, PRO, NDR, LOG and RAW and all three detail profiles.

This is a host control/stability test.  It cannot certify optics, Camera2 HAL behavior, physical
sensor noise, HDR display output, recording cadence, or comparisons to cinema/scientific cameras.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
from PIL import Image, ImageFilter

from unified_camera_engine_simulation import (
    AutomaticSignals,
    ControlRequest,
    DetailProfile,
    ExposureAuthority,
    FocusAuthority,
    ManualControls,
    SensorLimits,
    SoftwareAeAxis,
    SoftwareAePriorityModel,
    SoftwareAeRequest,
    SoftwareAeSample,
    VideoMode,
    WhiteBalanceAuthority,
    resolve_controls,
    validate_control_decision,
)


DATASET_ID = "pictograph/coco-5k"
DATASET_REVISION = "618089714878d74dc665a85a39bf160d14fd8209"
DATASET_LICENSE = "CC-BY-4.0"
EXPECTED_IMAGE_COUNT = 5_000
MINIMUM_IMAGE_COUNT = 1_000
SELECTED_GLOBS = ("train/*.jpg",)
EXPOSURE_STOPS = (-4.0, -2.0, -1.0, 0.0, 1.0, 2.0, 4.0)
ISO_LEVELS = (50, 100, 400, 800, 1_600, 3_200)
WB_CASTS = {
    "tungsten_2500k": (1.55, 1.00, 0.55),
    "warm_3200k": (1.28, 1.00, 0.76),
    "daylight_5600k": (1.00, 1.00, 1.00),
    "shade_7500k": (0.82, 1.00, 1.23),
    "green_tint": (0.92, 1.22, 0.92),
    "magenta_tint": (1.12, 0.82, 1.12),
}
DEFOCUS_RADII = (0.0, 0.8, 1.8, 3.5, 6.0)
MOTION_LENGTHS = (1, 3, 7, 15)
FRAME_INTERVAL_SECONDS = 1.0 / 25.0
# Deterministically selected by tools/calibration/calibrate_universal_camera_controls.py on the
# pinned corpus. These are display-RGB scene priors only; they are never Camera2 sensor matrices.
CALIBRATED_NEUTRAL_SATURATION_CEILING = 0.16
CALIBRATED_NEUTRAL_LUMA_FLOOR = 0.01
CALIBRATED_NEUTRAL_LUMA_CEILING = 0.90


@dataclass(frozen=True)
class ImageSignals:
    relative_path: str
    width: int
    height: int
    p50_luma: float
    p995_luma: float
    clipped_fraction: float
    shadow_fraction: float
    detail_score: float
    noise_proxy: float
    estimated_kelvin: int | None
    estimated_tint: int
    wb_confidence: float
    focus_confidence: float
    metered_iso: int
    metered_exposure_ns: int
    highlight_bias_stops: float


@dataclass
class ProbeCounters:
    decoded_images: int = 0
    invalid_images: int = 0
    exposure_probes: int = 0
    iso_noise_probes: int = 0
    wb_probes: int = 0
    focus_probes: int = 0
    shutter_motion_probes: int = 0
    exposure_deep_shadow_cases: int = 0
    exposure_highlight_clip_cases: int = 0
    iso_monotonic_images: int = 0
    wb_improved_probes: int = 0
    focus_monotonic_images: int = 0
    motion_monotonic_images: int = 0


def discover_images(corpus_root: Path) -> tuple[Path, ...]:
    images: set[Path] = set()
    for pattern in SELECTED_GLOBS:
        images.update(corpus_root.glob(pattern))
    return tuple(sorted(path for path in images if path.is_file()))


def _srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    return np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)


def _linear_luma(linear_rgb: np.ndarray) -> np.ndarray:
    return (
        linear_rgb[..., 0] * 0.2126
        + linear_rgb[..., 1] * 0.7152
        + linear_rgb[..., 2] * 0.0722
    )


def _detail_score(luma: np.ndarray) -> float:
    if min(luma.shape) < 3:
        return 0.0
    center = luma[1:-1, 1:-1]
    laplacian = (
        4.0 * center
        - luma[:-2, 1:-1]
        - luma[2:, 1:-1]
        - luma[1:-1, :-2]
        - luma[1:-1, 2:]
    )
    return float(np.percentile(np.abs(laplacian), 85.0))


def _noise_proxy(luma: np.ndarray) -> float:
    if min(luma.shape) < 3:
        return 0.0
    horizontal = luma[:, 1:] - luma[:, :-1]
    vertical = luma[1:, :] - luma[:-1, :]
    return float(
        0.5
        * (
            np.percentile(np.abs(horizontal), 35.0)
            + np.percentile(np.abs(vertical), 35.0)
        )
    )


def _load_thumbnail(path: Path, maximum_dimension: int) -> tuple[np.ndarray, int, int]:
    with Image.open(path) as image:
        image.load()
        image = image.convert("RGB")
        width, height = image.size
        image.thumbnail((maximum_dimension, maximum_dimension), Image.Resampling.LANCZOS)
        rgb = np.asarray(image, dtype=np.float32) / 255.0
    if rgb.ndim != 3 or rgb.shape[-1] != 3 or min(rgb.shape[:2]) < 8:
        raise ValueError(f"Unsupported decoded geometry {rgb.shape}")
    return rgb, width, height


def _estimate_white_balance(
    rgb: np.ndarray, linear_rgb: np.ndarray
) -> tuple[int | None, int, float, np.ndarray]:
    maximum = np.max(rgb, axis=-1)
    minimum = np.min(rgb, axis=-1)
    saturation = (maximum - minimum) / np.maximum(maximum, 1e-6)
    luma = _linear_luma(linear_rgb)
    neutral = (
        (saturation <= CALIBRATED_NEUTRAL_SATURATION_CEILING)
        & (luma >= CALIBRATED_NEUTRAL_LUMA_FLOOR)
        & (luma <= CALIBRATED_NEUTRAL_LUMA_CEILING)
        & (maximum < (254.5 / 255.0))
    )
    count = int(np.count_nonzero(neutral))
    minimum_count = max(24, int(neutral.size * 0.002))
    if count < minimum_count:
        return None, 0, 0.0, neutral

    channels = np.median(linear_rgb[neutral], axis=0).astype(np.float64)
    big_x = 0.4124564 * channels[0] + 0.3575761 * channels[1] + 0.1804375 * channels[2]
    big_y = 0.2126729 * channels[0] + 0.7151522 * channels[1] + 0.0721750 * channels[2]
    big_z = 0.0193339 * channels[0] + 0.1191920 * channels[1] + 0.9503041 * channels[2]
    xyz_sum = float(big_x + big_y + big_z)
    if xyz_sum <= 1e-9:
        return None, 0, 0.0, neutral
    # CIE xy normalizes by X+Y+Z, not by the camera-RGB channel sum. The previous denominator
    # produced a systematic CCT error even for a perfectly neutral D65 sample.
    x = float(big_x / xyz_sum)
    y = float(big_y / xyz_sum)
    denominator = 0.1858 - y
    if abs(denominator) < 1e-6:
        return None, 0, 0.0, neutral
    n = (x - 0.3320) / denominator
    cct = 449.0 * n**3 + 3525.0 * n**2 + 6823.3 * n + 5520.33
    kelvin = int(round(float(np.clip(cct, 2_000.0, 10_000.0))))
    green = float(channels[1])
    tint = int(
        round(
            np.clip(
                (green - 0.5 * float(channels[0] + channels[2]))
                / max(green, 1e-6)
                * 150.0,
                -150.0,
                150.0,
            )
        )
    )
    population = min(1.0, count / max(1.0, neutral.size * 0.08))
    chroma = 1.0 - float(
        np.median(saturation[neutral]) / CALIBRATED_NEUTRAL_SATURATION_CEILING
    )
    # Mixed illuminants often produce spatially incompatible neutral estimates. Compare robust
    # per-tile log chromaticities and lower confidence without changing the selected white point.
    rows, columns = neutral.shape
    global_log = np.log(np.maximum(channels, 1e-8))
    global_log -= float(np.mean(global_log))
    distances: list[float] = []
    populated_tiles = 0
    for row in range(0, rows, max(1, rows // 4)):
        for column in range(0, columns, max(1, columns // 4)):
            row_end = min(rows, row + max(1, rows // 4))
            column_end = min(columns, column + max(1, columns // 4))
            tile_mask = neutral[row:row_end, column:column_end]
            if np.count_nonzero(tile_mask) < 8:
                continue
            tile_values = linear_rgb[row:row_end, column:column_end][tile_mask]
            tile_channels = np.maximum(np.median(tile_values, axis=0), 1e-8)
            tile_log = np.log(tile_channels)
            tile_log -= float(np.mean(tile_log))
            distances.append(float(np.linalg.norm(tile_log - global_log)))
            populated_tiles += 1
    spatial_dispersion = float(np.median(distances)) if distances else 1.0
    spatial_agreement = math.exp(-spatial_dispersion / 0.10)
    spatial_coverage = min(1.0, populated_tiles / 8.0)
    confidence = float(
        np.clip(
            population**0.35
            * max(0.0, chroma) ** 0.25
            * spatial_agreement**0.25
            * spatial_coverage**0.15,
            0.0,
            1.0,
        )
    )
    return kelvin, tint, confidence, neutral


def _metered_values(p50_luma: float) -> tuple[int, int]:
    required_ns = 4_000_000.0 * 0.18 / max(p50_luma, 1e-5)
    exposure_ns = int(np.clip(required_ns, 125_000.0, 40_000_000.0))
    residual = required_ns / max(1.0, exposure_ns)
    iso = int(np.clip(round(100.0 * residual), 50, 3_200))
    return iso, exposure_ns


def _box_blur_horizontal(luma: np.ndarray, length: int) -> np.ndarray:
    if length <= 1:
        return luma
    left = length // 2
    right = length - 1 - left
    padded = np.pad(luma, ((0, 0), (left, right)), mode="edge")
    prefix = np.pad(np.cumsum(padded, axis=1, dtype=np.float64), ((0, 0), (1, 0)))
    return ((prefix[:, length:] - prefix[:, :-length]) / float(length)).astype(np.float32)


def _channel_chroma_error(values: np.ndarray) -> float:
    medians = np.median(values, axis=0).astype(np.float64)
    return float(np.std(medians) / max(float(np.mean(medians)), 1e-8))


def _white_balance_probe(
    linear_rgb: np.ndarray, neutral_mask: np.ndarray, gains: Sequence[float]
) -> tuple[float, float] | None:
    coordinates = np.indices(neutral_mask.shape)
    calibration = neutral_mask & (((coordinates[0] + coordinates[1]) & 1) == 0)
    validation = neutral_mask & ~calibration
    if np.count_nonzero(calibration) < 12 or np.count_nonzero(validation) < 12:
        return None
    cast = np.clip(linear_rgb * np.asarray(gains, dtype=np.float32), 0.0, 1.0)
    before = _channel_chroma_error(cast[validation])
    calibration_medians = np.median(cast[calibration], axis=0).astype(np.float64)
    geometric_mean = float(np.prod(np.maximum(calibration_medians, 1e-8)) ** (1.0 / 3.0))
    correction = geometric_mean / np.maximum(calibration_medians, 1e-8)
    corrected = np.clip(cast[validation] * correction, 0.0, 1.0)
    return before, _channel_chroma_error(corrected)


def _iso_noise_curve(
    linear_luma: np.ndarray, seed: int
) -> tuple[float, ...]:
    sample = linear_luma
    if max(sample.shape) > 96:
        image = Image.fromarray(np.uint8(np.clip(sample, 0.0, 1.0) * 255.0), mode="L")
        image.thumbnail((96, 96), Image.Resampling.BILINEAR)
        sample = np.asarray(image, dtype=np.float32) / 255.0
    rng = np.random.default_rng(seed)
    results: list[float] = []
    for iso in ISO_LEVELS:
        relative_light = 100.0 / float(iso)
        full_well = 12_000.0 * relative_light
        electrons = np.maximum(sample, 0.0) * full_well
        shot = rng.poisson(electrons).astype(np.float64) / max(full_well, 1.0)
        read_sigma = (1.6 + 0.35 * math.sqrt(iso / 100.0)) / max(full_well, 1.0)
        noisy = np.clip(shot + rng.normal(0.0, read_sigma, sample.shape), 0.0, 1.0)
        results.append(float(np.sqrt(np.mean((noisy - sample) ** 2))))
    return tuple(results)


def analyze_image(
    path: Path,
    corpus_root: Path,
    maximum_dimension: int,
    probes: ProbeCounters,
) -> ImageSignals:
    rgb, width, height = _load_thumbnail(path, maximum_dimension)
    linear = _srgb_to_linear(rgb)
    luma = _linear_luma(linear)
    p50 = float(np.percentile(luma, 50.0))
    p995 = float(np.percentile(luma, 99.5))
    detail = _detail_score(luma)
    noise = _noise_proxy(luma)
    kelvin, tint, wb_confidence, neutral_mask = _estimate_white_balance(rgb, linear)
    focus_confidence = float(np.clip(detail / 0.055, 0.0, 1.0))
    iso, exposure_ns = _metered_values(p50)
    highlight_bias = float(np.clip(min(0.0, math.log2(0.94 / max(p995, 1e-6))), -3.0, 0.0))

    for stops in EXPOSURE_STOPS:
        exposed = np.clip(luma * (2.0**stops), 0.0, 1.0)
        probes.exposure_probes += 1
        probes.exposure_deep_shadow_cases += int(float(np.mean(exposed <= 0.005)) >= 0.25)
        probes.exposure_highlight_clip_cases += int(float(np.mean(exposed >= 0.999)) >= 0.01)

    seed = int.from_bytes(hashlib.sha256(str(path).encode("utf-8")).digest()[:8], "big")
    noise_curve = _iso_noise_curve(luma, seed)
    probes.iso_noise_probes += len(noise_curve)
    probes.iso_monotonic_images += int(
        all(later + 1e-7 >= earlier for earlier, later in zip(noise_curve, noise_curve[1:]))
    )

    for gains in WB_CASTS.values():
        result = _white_balance_probe(linear, neutral_mask, gains)
        probes.wb_probes += 1
        if result is not None:
            before, after = result
            probes.wb_improved_probes += int(after <= before + 1e-6)

    luma_image = Image.fromarray(np.uint8(np.clip(luma, 0.0, 1.0) * 255.0), mode="L")
    defocus_scores = []
    for radius in DEFOCUS_RADII:
        blurred = luma_image if radius == 0.0 else luma_image.filter(ImageFilter.GaussianBlur(radius))
        defocus_scores.append(_detail_score(np.asarray(blurred, dtype=np.float32) / 255.0))
        probes.focus_probes += 1
    probes.focus_monotonic_images += int(
        all(later <= earlier + 0.002 for earlier, later in zip(defocus_scores, defocus_scores[1:]))
    )

    motion_scores = []
    for length in MOTION_LENGTHS:
        motion_scores.append(_detail_score(_box_blur_horizontal(luma, length)))
        probes.shutter_motion_probes += 1
    probes.motion_monotonic_images += int(
        all(later <= earlier + 0.002 for earlier, later in zip(motion_scores, motion_scores[1:]))
    )

    probes.decoded_images += 1
    return ImageSignals(
        relative_path=path.relative_to(corpus_root).as_posix(),
        width=width,
        height=height,
        p50_luma=p50,
        p995_luma=p995,
        clipped_fraction=float(np.mean(np.any(rgb >= (254.5 / 255.0), axis=-1))),
        shadow_fraction=float(np.mean(luma <= 0.005)),
        detail_score=detail,
        noise_proxy=noise,
        estimated_kelvin=kelvin,
        estimated_tint=tint,
        wb_confidence=wb_confidence,
        focus_confidence=focus_confidence,
        metered_iso=iso,
        metered_exposure_ns=exposure_ns,
        highlight_bias_stops=highlight_bias,
    )


def _automatic_signals(signals: ImageSignals) -> AutomaticSignals:
    product = signals.metered_iso * signals.metered_exposure_ns
    software_exposure = int(np.clip(round(product / 400.0), 125_000, 40_000_000))
    software_iso = int(np.clip(round(product / 8_000_000.0), 50, 3_200))
    return AutomaticSignals(
        metered_iso=signals.metered_iso,
        metered_exposure_time_ns=signals.metered_exposure_ns,
        highlight_bias_stops=signals.highlight_bias_stops,
        ndr_bias_stops=min(0.0, signals.highlight_bias_stops * 0.8),
        measured_kelvin=signals.estimated_kelvin,
        measured_tint=signals.estimated_tint,
        wb_confidence=signals.wb_confidence,
        awb_converged=signals.wb_confidence >= 0.55,
        ai_focus_diopters=float(np.clip(0.25 + 5.0 * (1.0 - signals.focus_confidence), 0.0, 10.0)),
        ai_focus_confidence=signals.focus_confidence,
        previous_ev_index=0,
        previous_focus_diopters=1.0,
        elapsed_seconds=FRAME_INTERVAL_SECONDS,
        software_iso_priority_exposure_time_ns=software_exposure,
        software_shutter_priority_iso=software_iso,
    )


def run_control_matrix(signals: Sequence[ImageSignals]) -> dict[str, object]:
    failures: Counter[str] = Counter()
    authorities: dict[str, Counter[str]] = {
        "exposure": Counter(),
        "white_balance": Counter(),
        "focus": Counter(),
    }
    cases = 0
    limits = SensorLimits(
        supports_software_iso_priority=True,
        supports_software_shutter_priority=True,
    )
    manual_cases = (
        ManualControls(),
        ManualControls(iso=400, ev_index=1, ev_override=True),
        ManualControls(exposure_time_ns=8_000_000, ev_index=-1, ev_override=True),
        ManualControls(kelvin=5_600, tint=12, focus_diopters=1.5, focus_override=True),
    )
    for image in signals:
        automatic = _automatic_signals(image)
        for mode in VideoMode:
            for profile in DetailProfile:
                for manual in manual_cases:
                    request = ControlRequest(
                        generation=71,
                        mode=mode,
                        detail_profile=profile,
                        target_fps=25,
                        recording=True,
                        limits=limits,
                        manual=manual,
                        automatic=automatic,
                        frame_exposure_budget_ns=40_000_000,
                    )
                    try:
                        decision = resolve_controls(request)
                        validate_control_decision(request, decision)
                    except AssertionError:
                        failures["decision_invariant"] += 1
                    else:
                        authorities["exposure"][decision.exposure.authority.value] += 1
                        authorities["white_balance"][decision.white_balance.authority.value] += 1
                        authorities["focus"][decision.focus.authority.value] += 1
                    cases += 1

    # A confidence bit without an actual white-point measurement must never authorize a recording
    # lock: an AWB_OFF fallback would otherwise jump to an unrelated default Kelvin value.
    missing_measurement_rejections = 0
    for mode in VideoMode:
        for profile in DetailProfile:
            request = ControlRequest(
                generation=72,
                mode=mode,
                detail_profile=profile,
                target_fps=25,
                recording=True,
                limits=limits,
                manual=ManualControls(),
                automatic=AutomaticSignals(
                    wb_confidence=1.0,
                    awb_converged=True,
                    measured_kelvin=None,
                ),
            )
            decision = resolve_controls(request)
            if decision.white_balance.request_auto_lock:
                failures["wb_lock_without_white_point"] += 1
            else:
                missing_measurement_rejections += 1

    return {
        "status": "PASS" if not failures else "FAIL",
        "cases": cases,
        "all_modes": [mode.value for mode in VideoMode],
        "all_detail_profiles": [profile.value for profile in DetailProfile],
        "authority_counts": {
            group: dict(sorted(counts.items())) for group, counts in authorities.items()
        },
        "missing_wb_measurement_fail_closed_cases": missing_measurement_rejections,
        "failures": dict(sorted(failures.items())),
    }


def run_white_balance_validity_stress() -> dict[str, object]:
    """Prove automatic WB locks only a physically representable measured white point."""
    limits = SensorLimits(kelvin_lower=2_000, kelvin_upper=10_000)
    cases = (
        ("kelvin_below_sensor_range", 1_999, 0, False),
        ("kelvin_lower_boundary", 2_000, -150, True),
        ("kelvin_upper_boundary", 10_000, 150, True),
        ("kelvin_above_sensor_range", 10_001, 0, False),
        ("tint_below_supported_range", 5_600, -151, False),
        ("tint_above_supported_range", 5_600, 151, False),
    )
    failures: Counter[str] = Counter()
    accepted_boundary_cases = 0
    rejected_invalid_cases = 0
    total = 0
    for mode in VideoMode:
        for profile in DetailProfile:
            for name, kelvin, tint, expected_valid in cases:
                request = ControlRequest(
                    generation=74,
                    mode=mode,
                    detail_profile=profile,
                    target_fps=25,
                    recording=True,
                    limits=limits,
                    manual=ManualControls(),
                    automatic=AutomaticSignals(
                        measured_kelvin=kelvin,
                        measured_tint=tint,
                        wb_confidence=1.0,
                        awb_converged=True,
                    ),
                )
                decision = resolve_controls(request).white_balance
                if expected_valid:
                    valid = (
                        decision.request_auto_lock
                        and decision.stable_fallback_kelvin == kelvin
                        and decision.stable_fallback_tint == tint
                    )
                    accepted_boundary_cases += int(valid)
                else:
                    valid = (
                        not decision.request_auto_lock
                        and decision.stable_fallback_kelvin is None
                        and decision.stable_fallback_tint == 0
                    )
                    rejected_invalid_cases += int(valid)
                if not valid:
                    failures[name] += 1
                total += 1
    return {
        "status": "PASS" if not failures else "FAIL",
        "cases": total,
        "accepted_boundary_cases": accepted_boundary_cases,
        "rejected_invalid_cases": rejected_invalid_cases,
        "failures": dict(sorted(failures.items())),
    }


def focus_settling_time_ms(mode: VideoMode, profile: DetailProfile) -> int | None:
    current = 0.25
    target = 5.0
    for frame in range(1, 251):
        request = ControlRequest(
            generation=83,
            mode=mode,
            detail_profile=profile,
            target_fps=25,
            recording=True,
            limits=SensorLimits(),
            manual=ManualControls(),
            automatic=AutomaticSignals(
                ai_focus_diopters=target,
                ai_focus_confidence=1.0,
                previous_focus_diopters=current,
                elapsed_seconds=FRAME_INTERVAL_SECONDS,
            ),
        )
        decision = resolve_controls(request)
        if decision.focus.authority is not FocusAuthority.AI_LENS_DISTANCE:
            return None
        current = float(decision.focus.focus_diopters)
        if abs(target - current) <= 0.08:
            return frame * 40
    return None


def run_focus_stability_stress() -> dict[str, object]:
    """Reject sub-command lens jitter without suppressing a real subject-distance change."""
    jitter_offsets = (0.003, -0.003, 0.002, -0.002) * 8
    jitter_commands: dict[str, dict[str, int]] = defaultdict(dict)
    meaningful_responses: dict[str, dict[str, float]] = defaultdict(dict)
    failures: Counter[str] = Counter()
    for mode in VideoMode:
        for profile in DetailProfile:
            previous = 2.0
            commands = 0
            for offset in jitter_offsets:
                request = ControlRequest(
                    generation=84,
                    mode=mode,
                    detail_profile=profile,
                    target_fps=25,
                    recording=True,
                    limits=SensorLimits(),
                    manual=ManualControls(),
                    automatic=AutomaticSignals(
                        ai_focus_diopters=2.0 + offset,
                        ai_focus_confidence=1.0,
                        previous_focus_diopters=previous,
                        elapsed_seconds=FRAME_INTERVAL_SECONDS,
                    ),
                )
                decision = resolve_controls(request)
                if decision.focus.authority is not FocusAuthority.AI_LENS_DISTANCE:
                    failures["focus_authority_lost"] += 1
                    break
                resolved = float(decision.focus.focus_diopters)
                commands += int(abs(resolved - previous) > 1e-7)
                previous = resolved
            jitter_commands[profile.value][mode.value] = commands
            if commands != 0:
                failures[f"micro_jitter_{profile.value}_{mode.value}"] += commands

            request = ControlRequest(
                generation=85,
                mode=mode,
                detail_profile=profile,
                target_fps=25,
                recording=True,
                limits=SensorLimits(),
                manual=ManualControls(),
                automatic=AutomaticSignals(
                    ai_focus_diopters=2.05,
                    ai_focus_confidence=1.0,
                    previous_focus_diopters=2.0,
                    elapsed_seconds=FRAME_INTERVAL_SECONDS,
                ),
            )
            decision = resolve_controls(request)
            response = (
                float(decision.focus.focus_diopters) - 2.0
                if decision.focus.focus_diopters is not None
                else 0.0
            )
            meaningful_responses[profile.value][mode.value] = response
            if decision.focus.authority is not FocusAuthority.AI_LENS_DISTANCE or response <= 0.0:
                failures[f"meaningful_change_{profile.value}_{mode.value}"] += 1
    return {
        "status": "PASS" if not failures else "FAIL",
        "jitter_samples_per_case": len(jitter_offsets),
        "lens_commands_by_profile_and_mode": dict(jitter_commands),
        "meaningful_response_diopters_by_profile_and_mode": dict(meaningful_responses),
        "failures": dict(sorted(failures.items())),
    }


def _plant_sample(
    mode: VideoMode,
    scene_gain: float,
    exposure_product_gain: float,
    timestamp_ms: int,
) -> SoftwareAeSample:
    luma = float(np.clip(0.18 * scene_gain * exposure_product_gain, 1e-4, 2.0))
    highlight = 0.72 * scene_gain * exposure_product_gain
    clip = float(np.clip((highlight - 0.98) / 0.35, 0.0, 1.0))
    return SoftwareAeSample(
        average_luma=luma,
        highlight_clip_fraction=clip,
        red_highlight_clip_fraction=clip * 0.65,
        timestamp_ms=timestamp_ms,
        signal_domain=(
            "SENSOR_LINEAR_RAW" if mode in (VideoMode.NDR, VideoMode.RAW) else "PROCESSED_YUV"
        ),
    )


def software_ae_settling_time_ms(
    mode: VideoMode,
    profile: DetailProfile,
    axis: SoftwareAeAxis,
    scene_gain: float,
) -> int | None:
    model = SoftwareAePriorityModel()
    request = SoftwareAeRequest(
        generation=91,
        mode=mode,
        detail_profile=profile,
        axis=axis,
        fixed_iso=400 if axis is SoftwareAeAxis.ISO else None,
        fixed_exposure_ns=10_000_000 if axis is SoftwareAeAxis.SHUTTER else None,
        metered_iso=400,
        metered_exposure_ns=10_000_000,
        iso_range=(50, 12_800),
        exposure_range_ns=(125_000, 100_000_000),
        frame_budget_ns=40_000_000,
    )
    initial_ms = 1_000
    initial = model.resolve(
        request,
        _plant_sample(mode, 1.0, 1.0, initial_ms),
        initial_ms,
    )
    if not initial.ready or initial.iso is None or initial.exposure_ns is None:
        return None
    neutral_product = 400.0 * 10_000_000.0
    decision = initial
    stable_frames = 0
    for frame in range(1, 126):
        timestamp_ms = initial_ms + frame * 40
        product = float((decision.iso or 1) * (decision.exposure_ns or 1))
        gain = product / neutral_product
        sample = _plant_sample(mode, scene_gain, gain, timestamp_ms)
        decision = model.resolve(request, sample, timestamp_ms)
        if not decision.ready or decision.iso is None or decision.exposure_ns is None:
            return None
        error_stops = abs(math.log2(max(sample.average_luma, 1e-4) / 0.18))
        safe_clip = sample.highlight_clip_fraction < 0.01
        stable_frames = stable_frames + 1 if error_stops <= 0.12 and safe_clip else 0
        if stable_frames >= 3:
            return frame * 40
    return None


def software_ae_recovery_time_ms(
    mode: VideoMode,
    profile: DetailProfile,
    axis: SoftwareAeAxis,
    first_scene_gain: float,
    second_scene_gain: float,
) -> int | None:
    """Measure recovery after a hard lighting reversal without resetting controller state."""
    model = SoftwareAePriorityModel()
    request = SoftwareAeRequest(
        generation=92,
        mode=mode,
        detail_profile=profile,
        axis=axis,
        fixed_iso=400 if axis is SoftwareAeAxis.ISO else None,
        fixed_exposure_ns=10_000_000 if axis is SoftwareAeAxis.SHUTTER else None,
        metered_iso=400,
        metered_exposure_ns=10_000_000,
        iso_range=(50, 12_800),
        exposure_range_ns=(125_000, 100_000_000),
        frame_budget_ns=40_000_000,
    )
    initial_ms = 1_000
    decision = model.resolve(request, _plant_sample(mode, 1.0, 1.0, initial_ms), initial_ms)
    if not decision.ready or decision.iso is None or decision.exposure_ns is None:
        return None
    neutral_product = 400.0 * 10_000_000.0
    timestamp_ms = initial_ms
    stable_frames = 0
    for _ in range(125):
        timestamp_ms += 40
        product = float((decision.iso or 1) * (decision.exposure_ns or 1))
        sample = _plant_sample(mode, first_scene_gain, product / neutral_product, timestamp_ms)
        decision = model.resolve(request, sample, timestamp_ms)
        if not decision.ready or decision.iso is None or decision.exposure_ns is None:
            return None
        error_stops = abs(math.log2(max(sample.average_luma, 1e-4) / 0.18))
        stable_frames = (
            stable_frames + 1
            if error_stops <= 0.12 and sample.highlight_clip_fraction < 0.01
            else 0
        )
        if stable_frames >= 3:
            break
    else:
        return None

    stable_frames = 0
    for recovery_frame in range(1, 126):
        timestamp_ms += 40
        product = float((decision.iso or 1) * (decision.exposure_ns or 1))
        sample = _plant_sample(mode, second_scene_gain, product / neutral_product, timestamp_ms)
        decision = model.resolve(request, sample, timestamp_ms)
        if not decision.ready or decision.iso is None or decision.exposure_ns is None:
            return None
        error_stops = abs(math.log2(max(sample.average_luma, 1e-4) / 0.18))
        stable_frames = (
            stable_frames + 1
            if error_stops <= 0.12 and sample.highlight_clip_fraction < 0.01
            else 0
        )
        if stable_frames >= 3:
            return recovery_frame * 40
    return None


def run_temporal_response() -> dict[str, object]:
    focus_times: dict[str, dict[str, int | None]] = defaultdict(dict)
    ae_times: dict[str, list[int | None]] = defaultdict(list)
    ae_recovery_times: dict[str, list[int | None]] = defaultdict(list)
    for mode in VideoMode:
        for profile in DetailProfile:
            focus_times[profile.value][mode.value] = focus_settling_time_ms(mode, profile)
            for axis in SoftwareAeAxis:
                for scene_gain in (0.25, 0.5, 2.0, 4.0):
                    ae_times[profile.value].append(
                        software_ae_settling_time_ms(mode, profile, axis, scene_gain)
                    )
                for first_gain, second_gain in ((0.25, 4.0), (4.0, 0.25)):
                    ae_recovery_times[profile.value].append(
                        software_ae_recovery_time_ms(
                            mode,
                            profile,
                            axis,
                            first_gain,
                            second_gain,
                        )
                    )

    focus_limits = {
        DetailProfile.NATURAL_DETAIL.value: 2_400,
        DetailProfile.FOCUSED_DETAIL.value: 1_400,
        DetailProfile.EXTREME_DETAIL.value: 900,
    }
    ae_limits = {
        DetailProfile.NATURAL_DETAIL.value: 1_400,
        DetailProfile.FOCUSED_DETAIL.value: 1_100,
        DetailProfile.EXTREME_DETAIL.value: 900,
    }
    ae_recovery_limits = {
        DetailProfile.NATURAL_DETAIL.value: 1_600,
        DetailProfile.FOCUSED_DETAIL.value: 1_300,
        DetailProfile.EXTREME_DETAIL.value: 1_100,
    }
    failures: dict[str, object] = {}
    focus_worst: dict[str, int | None] = {}
    ae_worst: dict[str, int | None] = {}
    ae_recovery_worst: dict[str, int | None] = {}
    for profile in DetailProfile:
        key = profile.value
        focus_values = tuple(focus_times[key].values())
        ae_values = tuple(ae_times[key])
        focus_worst[key] = max(value for value in focus_values if value is not None) if all(
            value is not None for value in focus_values
        ) else None
        ae_worst[key] = max(value for value in ae_values if value is not None) if all(
            value is not None for value in ae_values
        ) else None
        recovery_values = tuple(ae_recovery_times[key])
        ae_recovery_worst[key] = (
            max(value for value in recovery_values if value is not None)
            if all(value is not None for value in recovery_values)
            else None
        )
        if focus_worst[key] is None or focus_worst[key] > focus_limits[key]:
            failures[f"focus_{key}"] = {
                "observed_ms": focus_worst[key],
                "limit_ms": focus_limits[key],
            }
        if ae_worst[key] is None or ae_worst[key] > ae_limits[key]:
            failures[f"software_ae_{key}"] = {
                "observed_ms": ae_worst[key],
                "limit_ms": ae_limits[key],
            }
        if (
            ae_recovery_worst[key] is None
            or ae_recovery_worst[key] > ae_recovery_limits[key]
        ):
            failures[f"software_ae_recovery_{key}"] = {
                "observed_ms": ae_recovery_worst[key],
                "limit_ms": ae_recovery_limits[key],
            }
    return {
        "status": "PASS" if not failures else "FAIL",
        "focus_settling_ms_by_profile_and_mode": dict(focus_times),
        "focus_worst_ms": focus_worst,
        "software_ae_worst_ms": ae_worst,
        "software_ae_recovery_worst_ms": ae_recovery_worst,
        "software_ae_scene_gains": [0.25, 0.5, 2.0, 4.0],
        "focus_limits_ms": focus_limits,
        "software_ae_limits_ms": ae_limits,
        "software_ae_recovery_limits_ms": ae_recovery_limits,
        "failures": failures,
    }


def _quantiles(values: Iterable[float]) -> dict[str, float]:
    array = np.asarray(tuple(values), dtype=np.float64)
    return {
        "p05": float(np.percentile(array, 5.0)),
        "p50": float(np.percentile(array, 50.0)),
        "p95": float(np.percentile(array, 95.0)),
    }


def _corpus_digest(paths: Sequence[Path], corpus_root: Path) -> str:
    aggregate = hashlib.sha256()
    for path in paths:
        file_hash = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                file_hash.update(chunk)
        aggregate.update(path.relative_to(corpus_root).as_posix().encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(file_hash.digest())
    return aggregate.hexdigest().upper()


def verify_default_all_mode_wiring(repo_root: Path) -> dict[str, object]:
    controller = (
        repo_root
        / "app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
    ).read_text(encoding="utf-8")
    coordinator = (
        repo_root
        / "app/src/main/java/com/neoproc/neoproccamx/camera/UnifiedVideoControlCoordinator.kt"
    ).read_text(encoding="utf-8")
    checks = {
        "default_on_for_live_video_contract": (
            "val unifiedControlDecision = if (videoRenderContractActive)" in controller
            and "UnifiedVideoControlCoordinator.resolve(" in controller
        ),
        "video_mode_present": "    VIDEO," in coordinator,
        "pro_mode_present": "    PRO," in coordinator,
        "ndr_mode_present": "    NDR," in coordinator,
        "log_mode_present": "    LOG," in coordinator,
        "raw_mode_present": "    RAW;" in coordinator,
        "three_detail_profiles_wired": all(
            f"VideoDetailProfile.{profile.value}" in coordinator for profile in DetailProfile
        ),
    }
    return {
        "status": "PASS" if all(checks.values()) else "FAIL",
        "checks": checks,
    }


def run(
    corpus_root: Path,
    repo_root: Path,
    maximum_dimension: int = 160,
    maximum_images: int | None = None,
) -> dict[str, object]:
    images = discover_images(corpus_root)
    if maximum_images is not None:
        images = images[: max(0, maximum_images)]
    probes = ProbeCounters()
    signals: list[ImageSignals] = []
    errors: list[str] = []
    for path in images:
        try:
            signals.append(analyze_image(path, corpus_root, maximum_dimension, probes))
        except (OSError, ValueError) as exc:
            probes.invalid_images += 1
            if len(errors) < 20:
                errors.append(f"{path.name}: {exc}")

    full_corpus_run = maximum_images is None
    wb_evaluable = sum(
        1
        for signal in signals
        if signal.estimated_kelvin is not None and signal.wb_confidence > 0.0
    )
    image_evidence = {
        "probe_counts": asdict(probes),
        "image_signal_quantiles": {
            "p50_linear_luma": _quantiles(signal.p50_luma for signal in signals),
            "clipped_fraction": _quantiles(signal.clipped_fraction for signal in signals),
            "shadow_fraction": _quantiles(signal.shadow_fraction for signal in signals),
            "detail_score": _quantiles(signal.detail_score for signal in signals),
            "noise_proxy": _quantiles(signal.noise_proxy for signal in signals),
        },
        "white_balance_evaluable_images": wb_evaluable,
        "iso_noise_monotonic_fraction": probes.iso_monotonic_images / max(1, probes.decoded_images),
        "focus_blur_monotonic_fraction": probes.focus_monotonic_images / max(1, probes.decoded_images),
        "motion_blur_monotonic_fraction": probes.motion_monotonic_images / max(1, probes.decoded_images),
        "wb_correction_improved_fraction": probes.wb_improved_probes / max(1, probes.wb_probes),
        "decode_errors": errors,
    }
    control_matrix = run_control_matrix(signals)
    white_balance_validity = run_white_balance_validity_stress()
    focus_stability = run_focus_stability_stress()
    temporal = run_temporal_response()
    wiring = verify_default_all_mode_wiring(repo_root)
    failures: dict[str, object] = {}
    if full_corpus_run and len(images) < MINIMUM_IMAGE_COUNT:
        failures["minimum_image_count"] = len(images)
    if full_corpus_run and len(images) != EXPECTED_IMAGE_COUNT:
        failures["pinned_subset_image_count"] = {
            "expected": EXPECTED_IMAGE_COUNT,
            "actual": len(images),
        }
    if probes.invalid_images:
        failures["decode_failures"] = probes.invalid_images
    if probes.iso_monotonic_images / max(1, probes.decoded_images) < 0.99:
        failures["iso_noise_monotonicity"] = image_evidence["iso_noise_monotonic_fraction"]
    if probes.focus_monotonic_images / max(1, probes.decoded_images) < 0.99:
        failures["focus_blur_monotonicity"] = image_evidence["focus_blur_monotonic_fraction"]
    if probes.motion_monotonic_images / max(1, probes.decoded_images) < 0.97:
        failures["motion_blur_monotonicity"] = image_evidence["motion_blur_monotonic_fraction"]
    if wb_evaluable / max(1, probes.decoded_images) < 0.97:
        failures["white_balance_evaluable_fraction"] = wb_evaluable / max(
            1, probes.decoded_images
        )
    if image_evidence["wb_correction_improved_fraction"] < 0.975:
        failures["white_balance_correction_improvement"] = image_evidence[
            "wb_correction_improved_fraction"
        ]
    if control_matrix["status"] != "PASS":
        failures["control_matrix"] = control_matrix["failures"]
    if white_balance_validity["status"] != "PASS":
        failures["white_balance_validity"] = white_balance_validity["failures"]
    if focus_stability["status"] != "PASS":
        failures["focus_stability"] = focus_stability["failures"]
    if temporal["status"] != "PASS":
        failures["temporal_response"] = temporal["failures"]
    if wiring["status"] != "PASS":
        failures["default_all_mode_wiring"] = wiring["checks"]

    return {
        "status": "PASS" if not failures else "FAIL",
        "scope": "LICENSED_DISPLAY_RGB_HOST_CONTROL_AND_STABILITY_SIMULATION",
        "dataset": {
            "id": DATASET_ID,
            "revision": DATASET_REVISION,
            "license": DATASET_LICENSE,
            "selected_globs": list(SELECTED_GLOBS),
            "image_count": len(images),
            "decoded_image_count": len(signals),
            "total_image_bytes": sum(path.stat().st_size for path in images),
            "aggregate_sha256": _corpus_digest(images, corpus_root),
        },
        "checkpoints": {
            "image_driven_exposure_ev_iso_wb_focus_shutter": image_evidence,
            "all_modes_all_detail_profiles_control_matrix": control_matrix,
            "automatic_white_balance_validity": white_balance_validity,
            "focus_micro_jitter_and_meaningful_response": focus_stability,
            "temporal_focus_and_software_ae_response": temporal,
            "default_on_all_video_mode_source_wiring": wiring,
        },
        "calibration": {
            "neutral_saturation_ceiling": CALIBRATED_NEUTRAL_SATURATION_CEILING,
            "neutral_luma_floor": CALIBRATED_NEUTRAL_LUMA_FLOOR,
            "neutral_luma_ceiling": CALIBRATED_NEUTRAL_LUMA_CEILING,
            "source": "tools/calibration/calibrate_universal_camera_controls.py",
            "domain": "DISPLAY_RGB_SCENE_PRIOR_NOT_SENSOR_MATRIX",
        },
        "failures": failures,
        "limitations": [
            "Downloaded JPEGs contain baked ISP processing and do not reproduce RAW sensor data.",
            "Synthetic variants validate control response and numerical stability, not physical optics or sensor calibration.",
            "A host simulation cannot prove device Camera2/HAL support, lens motion, 10-bit transport, HDR display output, thermal behavior, or frame cadence.",
            "IMAX, cinema-camera, and space-telescope superiority cannot be established by this benchmark.",
        ],
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--maximum-dimension", type=int, default=160)
    parser.add_argument("--maximum-images", type=int)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    report = run(
        corpus_root=args.corpus.resolve(),
        repo_root=args.repo_root.resolve(),
        maximum_dimension=max(64, args.maximum_dimension),
        maximum_images=args.maximum_images,
    )
    payload = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
