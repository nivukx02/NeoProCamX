#!/usr/bin/env python3
"""Deterministic legacy-coordinate vs direct-20x20-tile demosaic simulation.

This is deliberately dependency-free so it can run before device validation. It exercises every pixel
(including clamped borders) for every Bayer pattern with randomized RAW/color/recovery metadata.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from typing import Callable, Sequence


WORKGROUP = 16
GUARD = 2
TILE = WORKGROUP + 2 * GUARD
SEED = 0x4E445230


@dataclass(frozen=True)
class Params:
    pattern: int
    white_level: int
    black: tuple[float, float, float, float]
    wb: tuple[float, float, float, float]
    lens: tuple[tuple[float, float, float, float], ...]
    highlight: tuple[float, float, float, float]
    sensor_to_xyz: tuple[float, ...]
    xyz_to_acescg: tuple[float, ...]


def clamp_int(value: int, low: int, high: int) -> int:
    return min(max(value, low), high)


def clamp_float(value: float, low: float, high: float) -> float:
    return min(max(value, low), high)


def bayer_channel(x: int, y: int, pattern: int) -> int:
    safe_pattern = pattern if 0 <= pattern <= 3 else 3
    return ((((y & 1) << 1) | (x & 1)) ^ safe_pattern)


def calibrate(
    raw: Sequence[int], width: int, height: int, params: Params, x: int, y: int
) -> float:
    cx = clamp_int(x, 0, width - 1)
    cy = clamp_int(y, 0, height - 1)
    channel = bayer_channel(cx, cy, params.pattern)
    black = params.black[channel]
    normalized = clamp_float(
        (float(raw[cy * width + cx]) - black) /
        max(float(params.white_level) - black, 1.0),
        0.0,
        1.0,
    )
    return normalized * params.wb[channel]


def demosaic_with_sampler(sample: Callable[[int, int], float], x: int, y: int, channel: int) -> tuple[float, float, float]:
    center = sample(x, y)
    xm1 = sample(x - 1, y)
    xp1 = sample(x + 1, y)
    ym1 = sample(x, y - 1)
    yp1 = sample(x, y + 1)
    if channel in (1, 2):
        green_on_red_row = channel == 1
        red = 0.5 * (xm1 + xp1) if green_on_red_row else 0.5 * (ym1 + yp1)
        blue = 0.5 * (ym1 + yp1) if green_on_red_row else 0.5 * (xm1 + xp1)
        return max(red, 0.0), max(center, 0.0), max(blue, 0.0)

    xm2 = sample(x - 2, y)
    xp2 = sample(x + 2, y)
    ym2 = sample(x, y - 2)
    yp2 = sample(x, y + 2)
    horizontal = abs((xm2 + 2.0 * center + xp2) - (2.0 * xm1 + 2.0 * xp1))
    vertical = abs((ym2 + 2.0 * center + yp2) - (2.0 * ym1 + 2.0 * yp1))
    gh = 0.5 * (xm1 + xp1) + 0.25 * (2.0 * center - xm2 - xp2)
    gv = 0.5 * (ym1 + yp1) + 0.25 * (2.0 * center - ym2 - yp2)
    ga = 0.25 * (xm1 + xp1 + ym1 + yp1) + 0.125 * (
        4.0 * center - xm2 - xp2 - ym2 - yp2
    )
    green = gh if horizontal < vertical else gv if vertical < horizontal else ga
    diagonal = 0.25 * (
        sample(x - 1, y - 1) + sample(x + 1, y - 1) +
        sample(x - 1, y + 1) + sample(x + 1, y + 1)
    ) + (center - green) * 0.5
    red = center if channel == 0 else diagonal
    blue = center if channel == 3 else diagonal
    return max(red, 0.0), max(green, 0.0), max(blue, 0.0)


def mix(a: Sequence[float], b: Sequence[float], weight: float) -> tuple[float, ...]:
    return tuple(a[i] * (1.0 - weight) + b[i] * weight for i in range(len(a)))


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = clamp_float((value - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def lens_gain(params: Params, width: int, height: int, x: int, y: int) -> tuple[float, float, float]:
    grid_x = clamp_float(x / max(width - 1, 1), 0.0, 1.0) * 2.0
    grid_y = clamp_float(y / max(height - 1, 1), 0.0, 1.0) * 2.0
    base_x = min(math.floor(grid_x), 1)
    base_y = min(math.floor(grid_y), 1)
    blend_x = grid_x - math.floor(grid_x)
    blend_y = grid_y - math.floor(grid_y)
    i00 = base_y * 3 + base_x
    top = mix(params.lens[i00], params.lens[i00 + 1], blend_x)
    bottom = mix(params.lens[i00 + 3], params.lens[i00 + 4], blend_x)
    gain = tuple(clamp_float(v, 0.25, 8.0) for v in mix(top, bottom, blend_y))
    return gain[0], 0.5 * (gain[1] + gain[2]), gain[3]


def recover_highlights(sensor_rgb: Sequence[float], params: Params) -> tuple[float, float, float]:
    threshold_input, strength_input, shoulder_enabled, recovery_enabled = params.highlight
    if recovery_enabled < 0.5 or strength_input <= 0.0:
        return tuple(sensor_rgb)  # type: ignore[return-value]
    threshold = clamp_float(threshold_input, 0.50, 0.9999)
    strength = clamp_float(strength_input, 0.0, 1.0)
    wb_rgb = (max(params.wb[0], 0.20), max(0.5 * (params.wb[1] + params.wb[2]), 0.20), max(params.wb[3], 0.20))
    pre_wb = [max(sensor_rgb[i] / wb_rgb[i], 0.0) for i in range(3)]
    clip_start = max(threshold, 0.985)
    clipped = [smoothstep(clip_start, 1.0, value) for value in pre_wb]
    support = (max(pre_wb[1], pre_wb[2]), max(pre_wb[0], pre_wb[2]), max(pre_wb[0], pre_wb[1]))
    support_edge1 = max(threshold * 0.90, threshold * 0.60 + 1e-4)
    supported_clip = [
        clipped[i] * smoothstep(threshold * 0.60, support_edge1, support[i]) for i in range(3)
    ]
    reliable = [1.0 - value for value in clipped]
    reliable_weight = sum(reliable)
    if 0.25 < reliable_weight < 2.999:
        neutral = sum(pre_wb[i] * reliable[i] for i in range(3)) / reliable_weight
        plausible = [min(value, neutral) for value in pre_wb]
        pre_wb = [
            pre_wb[i] * (1.0 - supported_clip[i] * strength) +
            plausible[i] * supported_clip[i] * strength
            for i in range(3)
        ]
    if shoulder_enabled > 0.5:
        peak = max(pre_wb)
        if peak > threshold:
            span = max(1.0 - threshold, 1e-4)
            delta = peak - threshold
            compressed_peak = threshold + delta / (1.0 + strength * delta / span)
            shoulder_weight = strength * smoothstep(threshold, 1.0, peak)
            scale = (1.0 - shoulder_weight) + compressed_peak / max(peak, 1e-6) * shoulder_weight
            pre_wb = [value * scale for value in pre_wb]
    return tuple(max(pre_wb[i] * wb_rgb[i], 0.0) for i in range(3))  # type: ignore[return-value]


def matrix3(matrix: Sequence[float], value: Sequence[float]) -> tuple[float, float, float]:
    return tuple(
        matrix[row * 3] * value[0] + matrix[row * 3 + 1] * value[1] + matrix[row * 3 + 2] * value[2]
        for row in range(3)
    )  # type: ignore[return-value]


def finish_pixel(sensor_rgb: Sequence[float], params: Params, width: int, height: int, x: int, y: int) -> tuple[float, float, float]:
    gain = lens_gain(params, width, height, x, y)
    recovered = recover_highlights(tuple(sensor_rgb[i] * gain[i] for i in range(3)), params)
    xyz = matrix3(params.sensor_to_xyz, recovered)
    return tuple(max(v, 0.0) for v in matrix3(params.xyz_to_acescg, xyz))  # type: ignore[return-value]


def legacy_output(raw: Sequence[int], width: int, height: int, params: Params) -> list[float]:
    output: list[float] = []
    sampler = lambda x, y: calibrate(raw, width, height, params, x, y)
    for y in range(height):
        for x in range(width):
            sensor = demosaic_with_sampler(sampler, x, y, bayer_channel(x, y, params.pattern))
            output.extend(finish_pixel(sensor, params, width, height, x, y))
    return output


def direct_tile_output(raw: Sequence[int], width: int, height: int, params: Params) -> list[float]:
    output = [0.0] * (width * height * 3)
    for group_y in range((height + WORKGROUP - 1) // WORKGROUP):
        for group_x in range((width + WORKGROUP - 1) // WORKGROUP):
            base_x = group_x * WORKGROUP - GUARD
            base_y = group_y * WORKGROUP - GUARD
            tile = [0.0] * (TILE * TILE)
            for tile_y in range(TILE):
                for tile_x in range(TILE):
                    source_x = clamp_int(base_x + tile_x, 0, width - 1)
                    source_y = clamp_int(base_y + tile_y, 0, height - 1)
                    tile[tile_y * TILE + tile_x] = calibrate(
                        raw, width, height, params, source_x, source_y
                    )
            for local_y in range(WORKGROUP):
                for local_x in range(WORKGROUP):
                    x = group_x * WORKGROUP + local_x
                    y = group_y * WORKGROUP + local_y
                    if x >= width or y >= height:
                        continue
                    center_x = local_x + GUARD
                    center_y = local_y + GUARD
                    sampler = lambda sx, sy, cx=center_x, cy=center_y: tile[
                        (cy + sy - y) * TILE + (cx + sx - x)
                    ]
                    sensor = demosaic_with_sampler(sampler, x, y, bayer_channel(x, y, params.pattern))
                    finished = finish_pixel(sensor, params, width, height, x, y)
                    offset = (y * width + x) * 3
                    output[offset:offset + 3] = finished
    return output


def random_matrix(rng: random.Random) -> tuple[float, ...]:
    return tuple(
        (rng.uniform(0.82, 1.18) if row == col else rng.uniform(-0.09, 0.09))
        for row in range(3) for col in range(3)
    )


def run_simulation() -> tuple[int, int, float]:
    rng = random.Random(SEED)
    for invalid_pattern in (-2**31, -1, 4, 99, 2**31 - 1):
        for y in range(2):
            for x in range(2):
                expected_bggr = (((y & 1) << 1) | (x & 1)) ^ 3
                assert bayer_channel(x, y, invalid_pattern) == expected_bggr
    dimensions = ((1, 1), (7, 5), (17, 19), (31, 18), (33, 35))
    cases = 0
    pixels = 0
    maximum_error = 0.0
    for pattern in range(4):
        for width, height in dimensions:
            white_level = rng.choice((1023, 4095, 16383))
            black = tuple(rng.uniform(0.0, white_level * 0.08) for _ in range(4))
            wb = tuple(rng.uniform(0.55, 3.20) for _ in range(4))
            lens = tuple(tuple(rng.uniform(0.40, 2.60) for _ in range(4)) for _ in range(9))
            highlight = (rng.uniform(0.50, 0.999), rng.uniform(0.0, 1.0), float(rng.randrange(2)), float(rng.randrange(2)))
            params = Params(pattern, white_level, black, wb, lens, highlight, random_matrix(rng), random_matrix(rng))
            raw = [rng.randrange(0, min(65536, white_level + white_level // 4 + 1)) for _ in range(width * height)]
            legacy = legacy_output(raw, width, height, params)
            direct = direct_tile_output(raw, width, height, params)
            for expected, actual in zip(legacy, direct):
                maximum_error = max(maximum_error, abs(expected - actual))
            cases += 1
            pixels += width * height
    if maximum_error != 0.0:
        raise AssertionError(f"direct tile numerical mismatch: max_abs_error={maximum_error:.17g}")
    return cases, pixels, maximum_error


if __name__ == "__main__":
    completed_cases, completed_pixels, max_error = run_simulation()
    print(
        "RAW_DEMOSAIC_DIRECT_TILE_SIMULATION "
        f"seed=0x{SEED:08x} cases={completed_cases} pixels={completed_pixels} "
        f"max_abs_error={max_error:.3e} PASS"
    )
