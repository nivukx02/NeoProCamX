#!/usr/bin/env python3
"""Offline NDR10+/NIVUKX Vision tone simulator for the 144-patch chart.

This tool is intentionally a CPU approximation of the realtime shader path.
It is meant to isolate tone/color math on deterministic PNG inputs; it does
not model camera sensor metadata, AWB, local texture sampling, or encoder tags.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable

import cv2
import numpy as np


ROOT = Path(__file__).resolve().parents[2]
CALIBRATION_DIR = ROOT / "app/src/main/assets/calibration/ndr_nivukx_144"
REFERENCE_JSON = CALIBRATION_DIR / "ndr_nivukx_144_reference.json"
DEFAULT_ARTIFACT_ROOT = ROOT / "artifacts"

EPS = 1.0e-6
REC709_LUMA = np.array([0.2126, 0.7152, 0.0722], dtype=np.float64)
REC2020_LUMA = np.array([0.2627, 0.6780, 0.0593], dtype=np.float64)
HELIOS_DIFFUSE_WHITE_NITS = 203.0
HELIOS_PEAK_NITS = 1000.0


@dataclass(frozen=True)
class ModeConfig:
    mode: str
    nivukx: bool
    highlight_compression: float
    shadow_lift: float
    planned_chroma_denoise: float
    measured_noise: float


def clamp(x, lo=0.0, hi=1.0):
    return np.clip(x, lo, hi)


def mix(a, b, t):
    return np.asarray(a) * (1.0 - np.asarray(t)) + np.asarray(b) * np.asarray(t)


def smoothstep(edge0: float, edge1: float, x):
    t = clamp((np.asarray(x) - edge0) / max(edge1 - edge0, EPS), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def safe_div(a, b):
    return np.asarray(a) / np.maximum(np.asarray(b), EPS)


def srgb_to_linear(srgb):
    srgb = clamp(np.asarray(srgb, dtype=np.float64), 0.0, 1.0)
    return np.where(
        srgb <= 0.04045,
        srgb / 12.92,
        np.power((srgb + 0.055) / 1.055, 2.4),
    )


def linear_to_srgb(linear):
    linear = clamp(np.asarray(linear, dtype=np.float64), 0.0, 1.0)
    return np.where(
        linear <= 0.0031308,
        linear * 12.92,
        1.055 * np.power(linear, 1.0 / 2.4) - 0.055,
    )


def luminance(rgb):
    return np.tensordot(np.asarray(rgb), REC709_LUMA, axes=([-1], [0]))


def ndr_scene_luma(rgb):
    return np.tensordot(np.asarray(rgb), REC2020_LUMA, axes=([-1], [0]))


def bt2100_luma(rgb):
    return ndr_scene_luma(rgb)


def max_channel(rgb):
    return np.max(rgb, axis=-1)


def min_channel(rgb):
    return np.min(rgb, axis=-1)


def expand_scalar(x):
    return np.expand_dims(x, axis=-1)


def skin_tone_weight(linear_color):
    display = clamp(linear_to_srgb(clamp(linear_color, 0.0, 1.0)), 0.0, 1.0)
    r = display[..., 0]
    g = display[..., 1]
    b = display[..., 2]
    rg = r - g
    gb = g - b
    luma = luminance(linear_color)
    warm_hue = smoothstep(0.012, 0.15, rg) * (1.0 - smoothstep(0.34, 0.68, rg))
    red_dominance = smoothstep(-0.015, 0.13, gb) * (1.0 - smoothstep(0.30, 0.62, gb))
    exposure_window = smoothstep(0.08, 0.26, luma) * (1.0 - smoothstep(1.05, 1.45, luma))
    saturation_window = smoothstep(0.035, 0.34, max_channel(display) - min_channel(display))
    return clamp(warm_hue * red_dominance * exposure_window * saturation_window, 0.0, 1.0)


def ndr_raw_channel_normalize(rgb):
    source = np.maximum(rgb, 0.0)
    source_y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    response = source / np.array([1.18, 1.0, 0.96], dtype=np.float64)
    matrix = np.array(
        [
            [1.000, -0.010, -0.004],
            [-0.015, 1.000, -0.010],
            [-0.008, -0.006, 1.000],
        ],
        dtype=np.float64,
    )
    response = np.maximum(response @ matrix.T, 0.0)
    balanced_y = np.maximum(ndr_scene_luma(response), 1.0e-5)
    response *= expand_scalar(source_y / balanced_y)

    red_green_ratio = safe_div(response[..., 0], response[..., 1])
    red_overflow = (
        smoothstep(1.30, 1.86, red_green_ratio)
        * smoothstep(0.10, 1.20, source_y)
        * (1.0 - smoothstep(5.20, 12.0, source_y))
    )
    response[..., 0] = mix(
        response[..., 0],
        np.minimum(response[..., 0], response[..., 1] * 1.30),
        red_overflow,
    )
    response[..., 2] = mix(
        response[..., 2],
        np.maximum(response[..., 2], response[..., 1] * 0.78),
        red_overflow * 0.72,
    )
    corrected_y = np.maximum(ndr_scene_luma(response), 1.0e-5)
    return np.maximum(response * expand_scalar(source_y / corrected_y), 0.0)


def filmic_log_luminance_map(luma_value, alpha):
    a = max(alpha, 0.1)
    return np.log1p(a * np.maximum(luma_value, 0.0)) / math.log1p(a)


def raw_domain_highlight_reconstruct(rgb):
    source = np.maximum(rgb, 0.0)
    peak = max_channel(source)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    clipped = smoothstep(0.96, 1.18, peak)
    neutral = expand_scalar(y)
    preserved = neutral + (source - neutral) * 0.72
    return mix(source, preserved, expand_scalar(clipped * 0.38))


def ndr_sky_highlight_chroma_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    peak = max_channel(source)
    cyan_blue = (
        smoothstep(0.92, 1.42, safe_div(source[..., 2], source[..., 0]))
        * smoothstep(0.82, 1.34, safe_div(source[..., 1], source[..., 0]))
    )
    bright_sky = smoothstep(0.48, 0.92, y) * smoothstep(0.74, 1.08, peak)
    near_clip = smoothstep(0.88, 1.08, peak)
    guard = clamp(cyan_blue * bright_sky * (0.52 if nivukx_vision else 0.44), 0.0, 0.56)
    guarded = mix(
        source,
        expand_scalar(y) + (source - expand_scalar(y)) * expand_scalar(mix(0.78, 0.58, near_clip)),
        expand_scalar(guard),
    )
    guarded[..., 1] = mix(
        guarded[..., 1],
        np.minimum(guarded[..., 1], np.maximum(guarded[..., 2] * 0.98, y * 0.92)),
        guard * 0.28,
    )
    guarded_y = np.maximum(ndr_scene_luma(guarded), 1.0e-5)
    return np.maximum(guarded * expand_scalar(y / guarded_y), 0.0)


def ndr_cyan_green_highlight_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    cool_over_red = safe_div(np.maximum(source[..., 1], source[..., 2]), source[..., 0])
    highlight = smoothstep(0.56, 0.96, y)
    cyan_shift = smoothstep(1.18, 2.90, cool_over_red)
    guard = clamp(highlight * cyan_shift * (0.58 if nivukx_vision else 0.48), 0.0, 0.64)
    guarded = source.copy()
    guarded[..., 0] += (y - guarded[..., 0]) * guard * 0.76
    guarded[..., 1] = y + (guarded[..., 1] - y) * (1.0 - guard * 0.44)
    guarded[..., 2] = y + (guarded[..., 2] - y) * (1.0 - guard * 0.54)
    guarded_y = np.maximum(ndr_scene_luma(guarded), 1.0e-5)
    return np.maximum(guarded * expand_scalar(y / guarded_y), 0.0)


def ndr_white_balance_recovery(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    red_cast = smoothstep(1.36, 2.10, safe_div(source[..., 0], source[..., 1]))
    cyan_cast = smoothstep(1.42, 3.20, safe_div(np.maximum(source[..., 1], source[..., 2]), source[..., 0]))
    highlight = smoothstep(0.58, 0.92 if nivukx_vision else 0.96, y)
    warm_memory_preserve = (
        smoothstep(1.04, 1.38, safe_div(source[..., 0], source[..., 1]))
        * (1.0 - smoothstep(1.76, 2.12, safe_div(source[..., 0], source[..., 1])))
        * smoothstep(0.50, 0.92, safe_div(source[..., 2], source[..., 1]))
        * (1.0 - smoothstep(1.08, 1.34, safe_div(source[..., 2], source[..., 1])))
    )
    cast_guard = clamp(
        (red_cast + cyan_cast)
        * highlight
        * (0.18 if nivukx_vision else 0.14)
        * (1.0 - warm_memory_preserve * (0.82 if nivukx_vision else 0.74)),
        0.0,
        0.28,
    )
    recovered = mix(source, expand_scalar(y), expand_scalar(cast_guard))
    recovered_y = np.maximum(ndr_scene_luma(recovered), 1.0e-5)
    return np.maximum(recovered * expand_scalar(y / recovered_y), 0.0)


def ndr_red_channel_overflow_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    peak = max_channel(source)
    red_green_ratio = safe_div(source[..., 0], source[..., 1])
    red_luma_ratio = safe_div(source[..., 0], y)
    red_blue_ratio = safe_div(source[..., 0], source[..., 2])
    red_over_green = smoothstep(1.10, 1.42, red_green_ratio)
    red_over_luma = smoothstep(1.16, 1.55, red_luma_ratio)
    clipped_red = smoothstep(0.72, 1.04, source[..., 0]) * smoothstep(1.12, 1.48, red_green_ratio)
    mid_high_exposure = smoothstep(0.035, 0.20, y) * (1.0 - smoothstep(1.18, 1.72, y))
    highlight_pressure = smoothstep(0.54, 1.02, np.maximum(y, peak * 0.72))
    skin = skin_tone_weight(source)
    red_object = (
        smoothstep(1.22, 1.72, red_green_ratio)
        * smoothstep(1.08, 1.60, red_blue_ratio)
        * (1.0 - skin)
    )
    guard = clamp(
        (
            np.maximum(red_over_green, red_over_luma)
            * mid_high_exposure
            * (0.52 + highlight_pressure * 0.34)
            + clipped_red * 0.22
            + red_object * 0.18
        )
        * (1.12 if nivukx_vision else 1.0),
        0.0,
        0.82 if nivukx_vision else 0.74,
    )
    target = source.copy()
    red_ceiling = mix(
        np.maximum(target[..., 1] * 1.22, y * 1.08),
        np.maximum(target[..., 1] * 1.13, y * 1.04),
        skin,
    )
    red_object_luma_ceiling = y * (1.24 if nivukx_vision else 1.28)
    object_red_ceiling = np.maximum(target[..., 1] * (1.42 if nivukx_vision else 1.48), red_object_luma_ceiling)
    red_ceiling = mix(red_ceiling, object_red_ceiling, red_object)
    red_trim_strength = np.minimum(1.0, guard * mix(1.0, 0.52, red_object))
    target[..., 0] = mix(target[..., 0], np.minimum(target[..., 0], red_ceiling), red_trim_strength)
    target[..., 1] = mix(target[..., 1], np.maximum(target[..., 1], y * 0.84), guard * 0.12 * skin)
    skin_blue_floor = target[..., 1] * 0.70
    skin_blue_ceiling = target[..., 1] * 0.90
    target[..., 2] = mix(target[..., 2], clamp(target[..., 2], skin_blue_floor, skin_blue_ceiling), guard * skin * 0.42)
    target[..., 2] = mix(target[..., 2], np.minimum(target[..., 2], target[..., 1] * 0.86), guard * red_object * 0.34)
    target_y = np.maximum(ndr_scene_luma(target), 1.0e-5)
    luma_restore = clamp(y / target_y, 0.90, 1.02)
    red_object_trim = mix(1.0, 0.992, red_object * guard)
    return np.maximum(target * expand_scalar(luma_restore * red_object_trim), 0.0)


def ndr_skin_tone_protect(rgb):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    red_green_ratio = safe_div(source[..., 0], source[..., 1])
    blue_green_ratio = safe_div(source[..., 2], source[..., 1])
    warm_skin_window = (
        smoothstep(0.10, 0.68, y)
        * (1.0 - smoothstep(0.92, 1.08, y))
        * smoothstep(1.14, 1.56, red_green_ratio)
        * (1.0 - smoothstep(1.82, 2.42, red_green_ratio))
    )
    protected = source.copy()
    protected[..., 0] = mix(protected[..., 0], np.minimum(protected[..., 0], protected[..., 1] * 1.30), warm_skin_window * 0.84)
    protected[..., 2] = mix(
        protected[..., 2],
        np.maximum(protected[..., 2], protected[..., 1] * 0.78),
        warm_skin_window * smoothstep(0.0, 0.78, 0.78 - blue_green_ratio),
    )
    protected_y = np.maximum(ndr_scene_luma(protected), 1.0e-5)
    return np.maximum(protected * expand_scalar(y / protected_y), 0.0)


def ndr_measured_skin_hue_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    red_green_ratio = safe_div(source[..., 0], source[..., 1])
    blue_green_ratio = safe_div(source[..., 2], source[..., 1])
    skin_memory = (
        smoothstep(1.04, 1.22, red_green_ratio)
        * (1.0 - smoothstep(1.64, 2.02, red_green_ratio))
        * smoothstep(0.42, 0.82, blue_green_ratio)
        * (1.0 - smoothstep(1.04, 1.22, blue_green_ratio))
        * smoothstep(0.08, 0.24, y)
        * (1.0 - smoothstep(0.96, 1.30, y))
    )
    skin = np.maximum(skin_tone_weight(source), skin_memory * 0.72)
    red_pressure = smoothstep(1.16, 1.54, red_green_ratio)
    blue_pressure = smoothstep(0.96, 1.22, blue_green_ratio)
    cool_skin_pressure = smoothstep(0.78, 1.04, blue_green_ratio)
    strength = clamp(
        skin * np.maximum(red_pressure * 0.72, np.maximum(blue_pressure, cool_skin_pressure * 0.86)) * (0.30 if nivukx_vision else 0.32),
        0.0,
        0.36,
    )
    corrected = source.copy()
    corrected[..., 0] = mix(
        corrected[..., 0],
        np.minimum(corrected[..., 0], corrected[..., 1] * (1.17 if nivukx_vision else 1.13)),
        strength * np.maximum(0.48, red_pressure),
    )
    skin_warmth_floor = corrected[..., 1] * (1.10 if nivukx_vision else 1.12)
    corrected[..., 0] = mix(corrected[..., 0], np.maximum(corrected[..., 0], skin_warmth_floor), strength * cool_skin_pressure * 0.38)
    skin_blue_floor = corrected[..., 1] * (0.70 if nivukx_vision else 0.66)
    skin_blue_ceiling = corrected[..., 1] * (0.90 if nivukx_vision else 0.88)
    corrected[..., 2] = mix(
        corrected[..., 2],
        clamp(corrected[..., 2], skin_blue_floor, skin_blue_ceiling),
        strength * mix(0.38, 0.92, np.maximum(blue_pressure, cool_skin_pressure)),
    )
    corrected_y = np.maximum(ndr_scene_luma(corrected), 1.0e-5)
    luma_restore = clamp(y / corrected_y, 0.90, 1.035)
    skin_luma_trim = mix(1.0, 0.992 if nivukx_vision else 0.972, skin * smoothstep(0.24, 0.90, y))
    return np.maximum(corrected * expand_scalar(luma_restore * skin_luma_trim), 0.0)


def ndr_measured_sky_hue_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    peak = max_channel(source)
    blue_dominance = smoothstep(1.08, 2.20, safe_div(source[..., 2], source[..., 0]))
    cyan_green_pressure = smoothstep(0.70, 1.08, safe_div(source[..., 1], source[..., 2]))
    green_red_ratio = safe_div(source[..., 1], source[..., 0])
    green_red_balance = smoothstep(0.72, 1.12, green_red_ratio) * (1.0 - smoothstep(1.38, 1.78, green_red_ratio))
    blue_presence = smoothstep(0.34, 0.78, safe_div(source[..., 2], source[..., 1]))
    red_not_dominant = 1.0 - smoothstep(1.16, 1.48, safe_div(source[..., 0], source[..., 1]))
    sky_hue_reject = np.maximum(
        skin_tone_weight(source),
        smoothstep(1.08, 1.34, safe_div(source[..., 0], source[..., 1]))
        * smoothstep(1.02, 1.28, safe_div(source[..., 0], source[..., 2])),
    )
    sky_blue_deficit = smoothstep(-0.035, 0.085, np.maximum(source[..., 0], source[..., 1]) - source[..., 2])
    warm_sky_window = green_red_balance * blue_presence * red_not_dominant * (1.0 - sky_hue_reject)
    warm_highlight_sky = (
        smoothstep(0.42, 0.92, y)
        * smoothstep(0.58, 1.04, safe_div(source[..., 1], source[..., 0]))
        * smoothstep(0.32, 0.72, safe_div(source[..., 2], np.maximum(source[..., 0], source[..., 1])))
        * (1.0 - smoothstep(1.24, 1.56, safe_div(source[..., 0], source[..., 1])))
        * (1.0 - sky_hue_reject)
    )
    sky_candidate = np.maximum(np.maximum(blue_dominance * cyan_green_pressure, warm_sky_window * 0.92), warm_highlight_sky) * mix(
        0.88, 1.24, sky_blue_deficit
    )
    exposure_window = smoothstep(0.12, 0.84, y) * (1.0 - smoothstep(1.03, 1.36, peak))
    strength = clamp(
        sky_candidate * exposure_window * (0.34 if nivukx_vision else 0.38),
        0.0,
        0.42 if nivukx_vision else 0.54,
    )
    corrected = source.copy()
    sky_blue_target = np.maximum(
        np.maximum(corrected[..., 2], y * (1.08 if nivukx_vision else 1.10)),
        np.maximum(corrected[..., 0], corrected[..., 1]) * (1.12 if nivukx_vision else 1.14),
    )
    corrected[..., 2] = mix(corrected[..., 2], sky_blue_target, strength * (0.72 if nivukx_vision else 0.82))
    corrected[..., 1] = mix(
        corrected[..., 1],
        np.minimum(corrected[..., 1], sky_blue_target * (0.66 if nivukx_vision else 0.58) + y * (0.20 if nivukx_vision else 0.16)),
        strength * (0.96 if nivukx_vision else 1.08),
    )
    corrected[..., 0] = mix(
        corrected[..., 0],
        np.minimum(corrected[..., 0], y * (0.56 if nivukx_vision else 0.50)),
        strength * (0.92 if nivukx_vision else 1.04),
    )
    corrected_y = np.maximum(ndr_scene_luma(corrected), 1.0e-5)
    luma_trim = clamp(y / corrected_y, 0.82 if nivukx_vision else 0.76, 0.97 if nivukx_vision else 0.92)
    sky_exposure_trim = sky_candidate * exposure_window * smoothstep(0.34, 0.86, y) * mix(0.72, 1.0, sky_blue_deficit)
    sky_luma_trim = mix(1.0, 0.750 if nivukx_vision else 0.845, sky_exposure_trim)
    result = np.maximum(corrected * expand_scalar(luma_trim * sky_luma_trim), 0.0)
    result_y = np.maximum(ndr_scene_luma(result), 1.0e-5)
    post_sky_blue_floor = np.maximum(
        result_y * (1.11 if nivukx_vision else 1.16),
        np.maximum(result[..., 0], result[..., 1]) * (1.13 if nivukx_vision else 1.18),
    )
    result[..., 2] = mix(result[..., 2], np.maximum(result[..., 2], post_sky_blue_floor), strength * (0.62 if nivukx_vision else 0.66))
    result[..., 0] = mix(result[..., 0], np.minimum(result[..., 0], result_y * (0.60 if nivukx_vision else 0.55)), strength * (0.46 if nivukx_vision else 0.52))
    result[..., 1] = mix(result[..., 1], np.minimum(result[..., 1], result[..., 2] * (0.70 if nivukx_vision else 0.64)), strength * (0.38 if nivukx_vision else 0.44))
    sky_channel_lock = clamp(sky_candidate * exposure_window * (0.72 if nivukx_vision else 0.60), 0.0, 0.72 if nivukx_vision else 0.64)
    result[..., 0] = mix(
        result[..., 0],
        np.minimum(result[..., 0], result[..., 2] * (0.80 if nivukx_vision else 0.76) + result_y * (0.05 if nivukx_vision else 0.04)),
        sky_channel_lock,
    )
    result[..., 1] = mix(
        result[..., 1],
        np.minimum(result[..., 1], result[..., 2] * (0.88 if nivukx_vision else 0.82) + result_y * (0.05 if nivukx_vision else 0.04)),
        sky_channel_lock * 0.72,
    )
    post_sky_hue = (
        smoothstep(1.04, 1.36, safe_div(result[..., 2], result[..., 0]))
        * smoothstep(0.70, 1.06, safe_div(result[..., 1], result[..., 2]))
        * smoothstep(0.16, 0.92, result_y)
        * (1.0 - skin_tone_weight(result))
    )
    result[..., 0] = mix(result[..., 0], np.minimum(result[..., 0], result[..., 2] * 0.68), post_sky_hue * (0.60 if nivukx_vision else 0.50))
    result[..., 1] = mix(result[..., 1], np.minimum(result[..., 1], result[..., 2] * (0.78 if nivukx_vision else 0.80)), post_sky_hue * (0.70 if nivukx_vision else 0.56))
    final_y = np.maximum(ndr_scene_luma(result), 1.0e-5)
    return np.maximum(result * expand_scalar(clamp(y / final_y, 0.84, 1.02)), 0.0)


def ndr_measured_highlight_balance_guard(rgb, nivukx_vision):
    source = np.maximum(rgb, 0.0)
    y = np.maximum(ndr_scene_luma(source), 1.0e-5)
    peak = max_channel(source)
    chroma = peak - min_channel(source)
    blue_deficit = smoothstep(0.015, 0.12, np.maximum(source[..., 0], source[..., 1]) - source[..., 2])
    warm_preserve = smoothstep(1.04, 1.24, safe_div(source[..., 0], source[..., 1])) * smoothstep(0.08, 0.28, source[..., 0] - source[..., 2])
    cloud_neutral_window = 1.0 - smoothstep(0.34, 0.58, chroma)
    highlight_neutral = (
        smoothstep(0.50, 0.90, y)
        * cloud_neutral_window
        * blue_deficit
        * (1.0 - warm_preserve)
        * (1.0 - skin_tone_weight(source))
    )
    strength = clamp(highlight_neutral * (0.36 if nivukx_vision else 0.42), 0.0, 0.30 if nivukx_vision else 0.38)
    corrected = source.copy()
    blue_lift_target = max_channel(corrected) * (1.02 if nivukx_vision else 1.04)
    corrected[..., 2] = mix(corrected[..., 2], np.maximum(corrected[..., 2], blue_lift_target), strength * 0.82)
    corrected[..., 0] = mix(corrected[..., 0], np.minimum(corrected[..., 0], y * (0.90 if nivukx_vision else 0.84)), strength * 0.68)
    corrected[..., 1] = mix(corrected[..., 1], np.minimum(corrected[..., 1], y * (0.96 if nivukx_vision else 0.90)), strength * 0.54)
    corrected_y = np.maximum(ndr_scene_luma(corrected), 1.0e-5)
    highlight_luma_trim = mix(1.0, 0.970 if nivukx_vision else 0.935, strength)
    return np.maximum(corrected * expand_scalar(clamp(y / corrected_y, 0.78, 0.96) * highlight_luma_trim), 0.0)


def ndr_measured_final_color_guard(rgb, nivukx_vision):
    balanced = ndr_measured_highlight_balance_guard(rgb, nivukx_vision)
    balanced = ndr_measured_skin_hue_guard(balanced, nivukx_vision)
    balanced = ndr_red_channel_overflow_guard(balanced, nivukx_vision)
    balanced = ndr_measured_sky_hue_guard(balanced, nivukx_vision)
    return balanced


def neo_radiance_helios_shadow_lift(x):
    x = np.maximum(x, 0.0)
    lifted = np.power(np.maximum(x / 0.08, 0.0), 0.65 + 0.25 * 0.40) * 0.08
    blend = smoothstep(0.06, 0.10, x)
    return mix(lifted, x, blend)


def neo_radiance_helios_cascade_luma(x, contrast=0.35, shoulder_soft=0.25, strength=0.40):
    x = np.maximum(x, 0.0)
    p = np.log1p(3.5 * x)
    spine = np.tanh((p - 0.65) * (2.2 + 1.8 * clamp(contrast, 0.0, 1.0)))
    normalized = (spine + 1.0) * 0.5
    over = np.maximum(x - 1.0, 0.0)
    shoulder = over * (1.0 - np.exp(-over / (1.2 + 2.0 * clamp(shoulder_soft, 0.0, 1.0))))
    folded = normalized + shoulder
    return mix(x, folded, clamp(strength, 0.0, 1.0))


def neo_radiance_helios_map_cascade_to_nits(folded):
    nits = np.maximum(folded, 0.0) * HELIOS_DIFFUSE_WHITE_NITS
    value_range = HELIOS_PEAK_NITS - HELIOS_DIFFUSE_WHITE_NITS
    return np.where(
        nits <= HELIOS_DIFFUSE_WHITE_NITS,
        nits,
        np.minimum(HELIOS_PEAK_NITS, HELIOS_DIFFUSE_WHITE_NITS + value_range * (1.0 - np.exp(-(nits - HELIOS_DIFFUSE_WHITE_NITS) / value_range))),
    )


def neo_radiance_helios_cascade_tone(rgb):
    y = np.maximum(ndr_scene_luma(rgb), EPS)
    lifted = neo_radiance_helios_shadow_lift(y)
    folded = neo_radiance_helios_cascade_luma(lifted, 0.35, 0.25, 0.40)
    mapped_y = neo_radiance_helios_map_cascade_to_nits(folded) / HELIOS_DIFFUSE_WHITE_NITS
    return rgb * expand_scalar(mapped_y / y)


def ndr_exposure_masks(luma_value, local_luma, peak_value):
    y = clamp(luma_value, 0.0, 1.5)
    local_y = clamp(local_luma, 0.0, 1.5)
    peak = clamp(peak_value, 0.0, 2.0)
    shadow = (1.0 - smoothstep(0.045, 0.28, y)) * (1.0 - smoothstep(0.12, 0.42, local_y))
    highlight = smoothstep(0.56, 0.98, np.maximum(y, peak * 0.82))
    midtone = clamp(1.0 - shadow * 0.82 - highlight * 0.78, 0.0, 1.0)
    return np.stack([shadow, midtone, highlight], axis=-1)


def neo_high_end_knee_curve(luma, knee, shoulder, strength):
    x = np.maximum(luma, 0.0)
    safe_knee = clamp(knee, 0.18, 0.92)
    safe_shoulder = max(min(shoulder, 1.0), safe_knee + 0.02)
    safe_strength = max(strength, 0.05)
    span = max(safe_shoulder - safe_knee, 1.0e-5)
    normalized = np.maximum((x - safe_knee) / span, 0.0)
    normalized_mapped = np.tanh(safe_strength * normalized) / max(math.tanh(safe_strength), 1.0e-5)
    mapped = clamp(safe_knee + span * normalized_mapped, 0.0, safe_shoulder)
    return np.where(x <= safe_knee, x, mapped)


def resolve_neo_high_end_knee_params(nivukx_vision, highlight_compression):
    if nivukx_vision:
        params = [0.54, 1.00, 2.25, 1.00]
    else:
        params = [0.58, 1.00, 2.05, 1.00]
    user_compression = clamp(highlight_compression, 0.0, 1.0)
    params[0] = float(clamp(params[0] - user_compression * mix(0.035, 0.085, params[3]), 0.42, 0.78))
    params[2] = float(clamp(params[2] + user_compression * mix(0.28, 0.72, params[3]), 1.12, 2.90))
    return params


def apply_neo_high_end_knee_curve(color, knee_params):
    source = np.maximum(color, 0.0)
    source_y = np.maximum(bt2100_luma(source), 1.0e-6)
    mapped_y = neo_high_end_knee_curve(source_y, knee_params[0], knee_params[1], knee_params[2])
    mapped = source * expand_scalar(mapped_y / source_y)
    highlight_weight = smoothstep(knee_params[0], knee_params[1], source_y)
    mapped_luma = np.maximum(bt2100_luma(mapped), 1.0e-6)
    chroma_keep = 1.0 - clamp(highlight_weight * knee_params[3] * 0.12, 0.0, 0.22)
    mapped = expand_scalar(mapped_luma) + (mapped - expand_scalar(mapped_luma)) * expand_scalar(chroma_keep)
    return clamp(mapped, 0.0, knee_params[1])


def bt2100_skin_tone_red_guard(linear_color, hlg_like=False, pq_like=True):
    if not (hlg_like or pq_like):
        return linear_color
    guarded = clamp(linear_color, 0.0, 1.0)
    y = clamp(bt2100_luma(guarded), 0.0, 1.0)
    red_green_ratio = safe_div(guarded[..., 0], guarded[..., 1])
    red_excess = smoothstep(1.24, 1.66, red_green_ratio)
    skin = skin_tone_weight(guarded)
    midtone_skin_window = smoothstep(0.08, 0.30, y) * (1.0 - smoothstep(0.82, 0.98, y))
    guard_strength = clamp(skin * red_excess * midtone_skin_window * (0.38 if pq_like else 0.26), 0.0, 0.38)
    adjusted = guarded.copy()
    target_red = np.minimum(adjusted[..., 0], adjusted[..., 1] * 1.30)
    adjusted[..., 0] = mix(adjusted[..., 0], target_red, guard_strength)
    adjusted[..., 2] = mix(adjusted[..., 2], np.maximum(adjusted[..., 2], adjusted[..., 1] * 0.78), guard_strength * 0.52)
    adjusted_y = np.maximum(bt2100_luma(adjusted), 1.0e-5)
    adjusted *= expand_scalar(y / adjusted_y)
    return clamp(adjusted, 0.0, 1.0)


def finish_realtime_computed_ndr_delivery(linear, config: ModeConfig):
    nivukx = config.nivukx
    linear = ndr_raw_channel_normalize(np.maximum(linear, 0.0))
    linear = raw_domain_highlight_reconstruct(linear)
    linear = ndr_red_channel_overflow_guard(linear, nivukx)
    linear = neo_radiance_helios_cascade_tone(linear)

    source_y = np.maximum(ndr_scene_luma(linear), 1.0e-5)
    peak = max_channel(linear)
    exposure_masks = ndr_exposure_masks(source_y, source_y, peak)
    white = 0.945 if nivukx else 0.965
    knee = clamp((0.56 if nivukx else 0.60) - config.highlight_compression * 0.08, 0.44, 0.74)
    shoulder_strength = (2.05 if nivukx else 1.78) + config.highlight_compression * 0.82
    log_mapped_y = filmic_log_luminance_map(source_y, 3.8 if nivukx else 3.2)
    shoulder_y = np.where(
        source_y <= knee,
        source_y,
        knee + (white - knee) * np.tanh(shoulder_strength * (source_y - knee) / max(white - knee, 1.0e-5)),
    )
    shoulder_y = mix(shoulder_y, np.minimum(shoulder_y, log_mapped_y * white), exposure_masks[..., 2] * 0.26)
    lifted_shadow_y = source_y + config.shadow_lift * 0.20 * exposure_masks[..., 0] * (1.0 - source_y)
    mapped_y = clamp(np.maximum(lifted_shadow_y, mix(source_y, shoulder_y, exposure_masks[..., 2])), 0.0, white)
    linear *= expand_scalar(mapped_y / source_y)

    linear = ndr_sky_highlight_chroma_guard(linear, nivukx)
    linear = ndr_cyan_green_highlight_guard(linear, nivukx)
    linear = ndr_white_balance_recovery(linear, nivukx)
    linear = ndr_skin_tone_protect(linear)
    linear = ndr_measured_skin_hue_guard(linear, nivukx)
    linear = ndr_measured_sky_hue_guard(linear, nivukx)
    linear = apply_neo_high_end_knee_curve(linear, resolve_neo_high_end_knee_params(nivukx, config.highlight_compression))
    linear = ndr_red_channel_overflow_guard(linear, nivukx)
    linear = bt2100_skin_tone_red_guard(linear, False, True)
    linear = ndr_measured_final_color_guard(linear, nivukx)

    finish_chroma = clamp(config.planned_chroma_denoise * 0.40 + config.measured_noise * 0.10, 0.10, 0.46)
    finish_dark = clamp(config.planned_chroma_denoise * 0.24 + config.measured_noise * 0.12, 0.08, 0.34)
    luma = np.maximum(ndr_scene_luma(linear), 1.0e-5)
    chroma = linear - expand_scalar(luma)
    shadow_gate = 1.0 - smoothstep(0.08, 0.65, luma)
    highlight_protect = 1.0 - smoothstep(0.82, 0.98, luma)
    chroma_scale = 1.0 - clamp(finish_chroma * (0.32 + shadow_gate * 0.36) * highlight_protect, 0.0, 0.42)
    floor_lift = finish_dark * shadow_gate * (1.0 - smoothstep(0.001, 0.075, np.linalg.norm(chroma, axis=-1))) * 0.08
    linear = np.maximum(expand_scalar(luma + floor_lift) + chroma * expand_scalar(chroma_scale), 0.0)
    return clamp(linear, 0.0, 1.0)


def simulate_ndr_nivukx(linear, mode):
    if mode == "NDR10":
        config = ModeConfig(
            mode=mode,
            nivukx=False,
            highlight_compression=0.62,
            shadow_lift=0.18,
            planned_chroma_denoise=0.20,
            measured_noise=0.06,
        )
    elif mode == "NIVUKX":
        config = ModeConfig(
            mode=mode,
            nivukx=True,
            highlight_compression=0.70,
            shadow_lift=0.20,
            planned_chroma_denoise=0.22,
            measured_noise=0.06,
        )
    else:
        raise ValueError(f"Unsupported mode: {mode}")
    return finish_realtime_computed_ndr_delivery(linear, config)


def resize_for_offline_analysis(rgb, max_edge: int):
    if max_edge <= 0:
        return rgb
    h, w = rgb.shape[:2]
    long_edge = max(h, w)
    if long_edge <= max_edge:
        return rgb
    scale = max_edge / float(long_edge)
    new_size = (max(1, int(round(w * scale))), max(1, int(round(h * scale))))
    return cv2.resize(rgb, new_size, interpolation=cv2.INTER_AREA)


def read_rgb_png(path: Path):
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise FileNotFoundError(path)
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.float64) / 255.0


def write_rgb_png(path: Path, rgb):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.rint(clamp(rgb, 0.0, 1.0) * 255.0).astype(np.uint8)
    cv2.imwrite(str(path), cv2.cvtColor(data, cv2.COLOR_RGB2BGR))


def parse_pages(raw: str | None, max_page: int):
    if raw is None or raw.strip().lower() == "all":
        return set(range(1, max_page + 1))
    pages = set()
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo, hi = [int(x.strip()) for x in part.split("-", 1)]
            pages.update(range(lo, hi + 1))
        else:
            pages.add(int(part))
    return {page for page in pages if 1 <= page <= max_page}


def patch_bounds_pixels(bounds, width, height):
    x0 = int(round(bounds[0] * width))
    y0 = int(round(bounds[1] * height))
    x1 = int(round(bounds[2] * width))
    y1 = int(round(bounds[3] * height))
    return max(0, x0), max(0, y0), min(width, x1), min(height, y1)


def hue_degrees(rgb):
    r, g, b = [float(x) for x in rgb]
    mx = max(r, g, b)
    mn = min(r, g, b)
    delta = mx - mn
    if delta < 1.0e-7:
        return 0.0
    if mx == r:
        hue = ((g - b) / delta) % 6.0
    elif mx == g:
        hue = ((b - r) / delta) + 2.0
    else:
        hue = ((r - g) / delta) + 4.0
    return (hue * 60.0) % 360.0


def hue_delta_degrees(a, b):
    delta = abs(hue_degrees(a) - hue_degrees(b)) % 360.0
    return min(delta, 360.0 - delta)


def chroma_display(rgb):
    return float(max(rgb) - min(rgb))


def sample_patch_mean(rgb, bounds):
    h, w = rgb.shape[:2]
    x0, y0, x1, y1 = patch_bounds_pixels(bounds, w, h)
    sample = rgb[y0:y1, x0:x1]
    if sample.size == 0:
        return np.zeros(3, dtype=np.float64)
    return sample.reshape(-1, 3).mean(axis=0)


def patch_flags(role: str, before: np.ndarray, after: np.ndarray):
    delta = after - before
    flags: list[str] = []
    if role == "SKY":
        if delta[0] > 0.015 or delta[1] > 0.020 or delta[2] < -0.015:
            flags.append("sky_red_green_high_or_blue_low")
        if after[2] < max(after[0], after[1]) * 1.04:
            flags.append("sky_blue_not_dominant")
    if role == "SKIN":
        if delta[0] > 0.020 and delta[0] > delta[1] + 0.010:
            flags.append("skin_red_push")
        if hue_delta_degrees(before, after) > 5.0:
            flags.append("skin_hue_shift_gt_5deg")
    if role in {"SATURATED_RED", "SATURATED_ORANGE"}:
        if after[0] > before[0] + 0.020 and after[0] > max(after[1], after[2]) * 1.40:
            flags.append("warm_color_red_overpush")
    if role == "HIGHLIGHT":
        if abs(float(delta[0] - delta[2])) > 0.035 or abs(float(delta[1] - delta[2])) > 0.035:
            flags.append("highlight_channel_imbalance")
        if max(after) >= 0.995 and chroma_display(after) > 0.040:
            flags.append("near_clip_colored_highlight")
    return flags


def analyze_page(reference, page_file: str, mode: str, before_srgb, after_srgb):
    page_patches = [p for p in reference["patches"] if p["file"] == page_file]
    patch_rows = []
    for patch in page_patches:
        before = sample_patch_mean(before_srgb, patch["sample_bounds_normalized"])
        after = sample_patch_mean(after_srgb, patch["sample_bounds_normalized"])
        delta = after - before
        before_luma = float(np.dot(before, REC709_LUMA))
        after_luma = float(np.dot(after, REC709_LUMA))
        patch_rows.append(
            {
                "id": patch["id"],
                "role": patch["role"],
                "page": patch["page"],
                "mode": mode,
                "before_srgb": [round(float(x), 6) for x in before],
                "after_srgb": [round(float(x), 6) for x in after],
                "delta_rgb": [round(float(x), 6) for x in delta],
                "red_delta": round(float(delta[0]), 6),
                "green_delta": round(float(delta[1]), 6),
                "blue_delta": round(float(delta[2]), 6),
                "luma_delta": round(float(after_luma - before_luma), 6),
                "chroma_delta": round(chroma_display(after) - chroma_display(before), 6),
                "hue_delta_deg": round(hue_delta_degrees(before, after), 4),
                "flags": patch_flags(patch["role"], before, after),
            }
        )
    return patch_rows


def aggregate_role_stats(patch_rows):
    grouped: dict[str, list[dict]] = {}
    for row in patch_rows:
        grouped.setdefault(row["role"], []).append(row)
    stats = {}
    for role, rows in grouped.items():
        stats[role] = {
            "count": len(rows),
            "mean_red_delta": round(float(np.mean([r["red_delta"] for r in rows])), 6),
            "mean_green_delta": round(float(np.mean([r["green_delta"] for r in rows])), 6),
            "mean_blue_delta": round(float(np.mean([r["blue_delta"] for r in rows])), 6),
            "mean_luma_delta": round(float(np.mean([r["luma_delta"] for r in rows])), 6),
            "mean_chroma_delta": round(float(np.mean([r["chroma_delta"] for r in rows])), 6),
            "mean_hue_delta_deg": round(float(np.mean([r["hue_delta_deg"] for r in rows])), 4),
            "flag_counts": {},
        }
        for row in rows:
            for flag in row["flags"]:
                stats[role]["flag_counts"][flag] = stats[role]["flag_counts"].get(flag, 0) + 1
    return stats


def mode_recommendations(mode: str, role_stats: dict):
    recommendations = []
    sky = role_stats.get("SKY")
    skin = role_stats.get("SKIN")
    highlight = role_stats.get("HIGHLIGHT")
    if sky and (sky["mean_red_delta"] > 0.010 or sky["mean_green_delta"] > 0.015 or sky["mean_blue_delta"] < -0.010):
        recommendations.append(
            f"{mode}: sky role still trends warm/cyan in offline tone math; tighten sky hue guard or reduce highlight neutral trim before live-camera retest."
        )
    if skin and (skin["mean_red_delta"] > 0.015 or skin["mean_hue_delta_deg"] > 4.0):
        recommendations.append(
            f"{mode}: skin role shifts red/hue in offline tone math; strengthen measured skin hue guard after knee curve."
        )
    if highlight and highlight["flag_counts"].get("highlight_channel_imbalance", 0) > 0:
        recommendations.append(
            f"{mode}: highlight patches show channel imbalance; keep highlight balance guard active before final color guard."
        )
    if not recommendations:
        recommendations.append(f"{mode}: offline patch tone math does not show a broad sky/skin/red regression on selected pages.")
    return recommendations


def write_summary(out_dir: Path, report: dict):
    lines = [
        "# NDR/NIVUKX Offline Tone Delta Summary",
        "",
        f"Generated: {report['generated_at']}",
        "",
        "This is a CPU approximation of the realtime shader tone path applied to calibration PNGs.",
        "It isolates tone math only; it does not include camera sensor AWB, local texture sampling, device display conversion, or encoder metadata.",
        "",
    ]
    for mode, stats in report["role_summary"].items():
        lines.append(f"## {mode}")
        for role in sorted(stats):
            row = stats[role]
            lines.append(
                f"- {role}: dR={row['mean_red_delta']:+.4f}, dG={row['mean_green_delta']:+.4f}, "
                f"dB={row['mean_blue_delta']:+.4f}, dY={row['mean_luma_delta']:+.4f}, "
                f"hue={row['mean_hue_delta_deg']:.2f} deg, flags={row['flag_counts']}"
            )
        lines.append("")
        lines.extend(f"- {item}" for item in report["recommendations"][mode])
        lines.append("")
    (out_dir / "tone_delta_summary.md").write_text("\n".join(lines), encoding="utf-8")


def relative_to_root(path: Path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def process(reference, pages: Iterable[int], out_dir: Path, max_edge: int, calibration_dir: Path, reference_path: Path):
    page_files = {page["page_index"]: page["file"] for page in reference["pages"]}
    all_patch_rows: dict[str, list[dict]] = {"NDR10": [], "NIVUKX": []}
    modes = ["NDR10", "NIVUKX"]
    processed_files = []

    for page in sorted(pages):
        page_file = page_files[page]
        source_srgb = resize_for_offline_analysis(read_rgb_png(calibration_dir / page_file), max_edge)
        source_linear = srgb_to_linear(source_srgb)
        for mode in modes:
            processed_linear = simulate_ndr_nivukx(source_linear, mode)
            processed_srgb = linear_to_srgb(processed_linear)
            output_file = out_dir / mode / f"{Path(page_file).stem}_{mode}.png"
            write_rgb_png(output_file, processed_srgb)
            processed_files.append(str(output_file.relative_to(out_dir)))
            all_patch_rows[mode].extend(analyze_page(reference, page_file, mode, source_srgb, processed_srgb))

    role_summary = {mode: aggregate_role_stats(rows) for mode, rows in all_patch_rows.items()}
    report = {
        "version": "NDR_NIVUKX_OFFLINE_TONE_SIMULATOR_V1",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "reference": relative_to_root(reference_path),
        "pages": sorted(pages),
        "analysis_max_edge": max_edge,
        "processed_files": processed_files,
        "truth_boundary": {
            "models": "CPU vector approximation of selected ycbcr_preview.frag NDR/NIVUKX color guards.",
            "excludes": [
                "Camera sensor response and AWB metadata",
                "Vulkan texture-neighbor local sampling",
                "Recording encoder color metadata",
                "Device display color management",
            ],
            "use": "Compare original calibration PNG against tone-processed PNG to isolate pure tone-math color drift.",
        },
        "patch_rows": all_patch_rows,
        "role_summary": role_summary,
        "recommendations": {mode: mode_recommendations(mode, role_summary[mode]) for mode in modes},
    }
    (out_dir / "tone_delta_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    write_summary(out_dir, report)
    return report


def main():
    parser = argparse.ArgumentParser(description="Apply offline NDR10+/NIVUKX tone settings to the 144-patch calibration PNGs.")
    parser.add_argument("--reference", type=Path, default=REFERENCE_JSON)
    parser.add_argument("--pages", default="all", help="Comma/range page list, for example 1,4,5,7,11 or all.")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument(
        "--max-edge",
        type=int,
        default=1440,
        help="Downscale long edge before tone simulation; use 0 for original resolution.",
    )
    args = parser.parse_args()

    reference = json.loads(args.reference.read_text(encoding="utf-8"))
    max_page = int(reference["canvas"]["page_count"])
    pages = parse_pages(args.pages, max_page)
    if not pages:
        raise SystemExit("No calibration pages selected.")

    out_dir = args.out
    if out_dir is None:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out_dir = DEFAULT_ARTIFACT_ROOT / f"offline-ndr-nivukx-tone-sim-{stamp}"
    out_dir.mkdir(parents=True, exist_ok=True)

    report = process(reference, pages, out_dir, args.max_edge, args.reference.parent, args.reference)
    report_path = out_dir / "tone_delta_report.json"
    print(f"Wrote {report_path}")
    for mode in ("NDR10", "NIVUKX"):
        print(f"{mode}:")
        for item in report["recommendations"][mode]:
            print(f"  - {item}")


if __name__ == "__main__":
    main()
