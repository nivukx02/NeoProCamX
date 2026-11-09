"""Host parity model for the sensor-linear NDR scalar tone/PQ acceleration.

The production shader retains measured color correction, hue/chroma gates, detail, denoise and
peak-volume compression analytically. Only deterministic scalar tone/rolloff and ST.2084 functions
are table-driven. This model applies the same piecewise-linear row mapping, FP16 high/residual
storage and linear filtering.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
from pathlib import Path
import random
import struct


LUT_SIZE = 2048
TONE_DOMAIN = 256.0
PQ_NITS_DOMAIN = 10000.0
DIFFUSE_NITS = 203.0
LUMA = (0.2627, 0.6780, 0.0593)
TONE_SEGMENT_BOUNDS = (
    0.0,
    1.0e-8,
    1.0e-7,
    1.0e-6,
    1.0e-5,
    1.0e-4,
    1.0e-3,
    1.0e-2,
    1.0e-1,
    1.0,
    16.0,
    TONE_DOMAIN,
)
PQ_SEGMENT_BOUNDS = (0.0, 0.1, 1.0, 10.0, 100.0, 1000.0, PQ_NITS_DOMAIN)


@dataclass(frozen=True)
class Profile:
    gamma: float
    toe_width: float
    toe_amount: float
    shadow_lift: float
    midtone_contrast: float
    measured_rolloff: float
    peak_nits: float


NDR10 = Profile(0.859937, 0.238882, 0.024780, 0.167837, 0.100943, 0.034662, 5000.0)
NIVUKX = Profile(0.800034, 0.289876, 0.031033, 0.232156, 0.174135, 0.050035, 10000.0)


def _half(value: float) -> float:
    return struct.unpack("e", struct.pack("e", value))[0]


def _smoothstep(low: float, high: float, value: float) -> float:
    t = min(max((value - low) / (high - low), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def tone_plan(scene_luma: float, profile: Profile) -> tuple[float, float, float]:
    x = max(scene_luma, 0.0)
    if x <= 1.0:
        mapped = x**profile.gamma
        mapped += _smoothstep(0.0, profile.toe_width, x) * profile.toe_amount * (1.0 - x)
    else:
        mapped = 1.0 + (profile.gamma - profile.toe_amount) * (x - 1.0)
    if 0.0 < mapped < 1.0:
        window = _smoothstep(0.025, 0.20, mapped) * (1.0 - _smoothstep(0.78, 1.20, mapped))
        mapped += profile.shadow_lift * math.sqrt(mapped) * (1.0 - mapped) * window
        window = _smoothstep(0.055, 0.24, mapped) * (1.0 - _smoothstep(0.76, 1.0, mapped))
        mapped += profile.midtone_contrast * (mapped - 0.5) * 4.0 * mapped * (1.0 - mapped) * window
        mapped = max(mapped, 0.0)
    rolled = mapped
    if mapped > 1.0 and profile.measured_rolloff > 0.0:
        excess = mapped - 1.0
        rolled = 1.0 + excess / (1.0 + 4.0 * profile.measured_rolloff * excess)
    raw_nits = rolled * DIFFUSE_NITS
    if raw_nits <= DIFFUSE_NITS:
        nits = raw_nits
    else:
        span = profile.peak_nits - DIFFUSE_NITS
        nits = DIFFUSE_NITS + span * (1.0 - math.exp(-(raw_nits - DIFFUSE_NITS) / span))
    return mapped, rolled, min(nits, profile.peak_nits)


def pq_from_nits(nits: float) -> float:
    y = min(max(nits / 10000.0, 0.0), 1.0)
    ym = y**0.1593017578125
    return ((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym)) ** 78.84375


def _high_residual(value: float) -> tuple[float, float]:
    high = _half(value)
    return high, _half(value - high)


def _sample_piecewise(
    rows: list[list[tuple[float, ...]]],
    bounds: tuple[float, ...],
    value: float,
) -> tuple[float, ...]:
    bounded = min(max(value, bounds[0]), bounds[-1])
    segment = len(rows) - 1
    for candidate in range(len(rows)):
        if bounded <= bounds[candidate + 1]:
            segment = candidate
            break
    low = bounds[segment]
    high = bounds[segment + 1]
    position = (bounded - low) / (high - low) * (LUT_SIZE - 1)
    left = min(int(math.floor(position)), LUT_SIZE - 2)
    fraction = min(max(position - left, 0.0), 1.0)
    return tuple(
        a + (b - a) * fraction
        for a, b in zip(rows[segment][left], rows[segment][left + 1])
    )


def build_tables(
    profile: Profile,
) -> tuple[list[list[tuple[float, ...]]], list[list[tuple[float, ...]]]]:
    tone: list[list[tuple[float, ...]]] = []
    pq: list[list[tuple[float, ...]]] = []
    for low, high in zip(TONE_SEGMENT_BOUNDS, TONE_SEGMENT_BOUNDS[1:]):
        row: list[tuple[float, ...]] = []
        for index in range(LUT_SIZE):
            scene = low + (high - low) * index / (LUT_SIZE - 1)
            mapped, rolled, nits = tone_plan(scene, profile)
            mapped_high, mapped_residual = _high_residual(mapped)
            nits_high, nits_residual = _high_residual(nits)
            row.append((
                mapped_high,
                mapped_residual,
                nits_high,
                nits_residual,
            ))
        tone.append(row)
    for segment in range(1, len(tone)):
        tone[segment][0] = tone[segment - 1][-1]
    tone[0][0] = (0.0, 0.0, 0.0, 0.0)
    for low, high in zip(PQ_SEGMENT_BOUNDS, PQ_SEGMENT_BOUNDS[1:]):
        row = []
        for index in range(LUT_SIZE):
            nits = low + (high - low) * index / (LUT_SIZE - 1)
            row.append(_high_residual(pq_from_nits(nits)))
        pq.append(row)
    for segment in range(1, len(pq)):
        pq[segment][0] = pq[segment - 1][-1]
    pq[0][0] = (0.0, 0.0)
    return tone, pq


def lut_tone_plan(
    scene_luma: float,
    profile: Profile,
    table: list[list[tuple[float, ...]]],
) -> tuple[float, float, float]:
    if scene_luma > TONE_DOMAIN:
        return tone_plan(scene_luma, profile)
    mapped_high, mapped_residual, nits_high, nits_residual = _sample_piecewise(
        table,
        TONE_SEGMENT_BOUNDS,
        scene_luma,
    )
    mapped = mapped_high + mapped_residual
    rolled = mapped
    if mapped > 1.0 and profile.measured_rolloff > 0.0:
        excess = mapped - 1.0
        rolled = 1.0 + excess / (1.0 + 4.0 * profile.measured_rolloff * excess)
    return mapped, rolled, nits_high + nits_residual


def lut_pq(nits: float, table: list[list[tuple[float, ...]]]) -> float:
    if nits > PQ_NITS_DOMAIN:
        return pq_from_nits(nits)
    high, residual = _sample_piecewise(table, PQ_SEGMENT_BOUNDS, nits)
    return high + residual


def _normalize_ratio(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    y = sum(channel * weight for channel, weight in zip(rgb, LUMA))
    return tuple(channel / y for channel in rgb)


def _peak_safe_codes(
    ratio: tuple[float, float, float],
    luminance_nits: float,
    peak_nits: float,
    encoder,
) -> tuple[float, float, float]:
    channels = [max(value * luminance_nits, 0.0) for value in ratio]
    high = max(channels)
    if high > peak_nits and high > luminance_nits + 1e-8:
        chroma_scale = min(max((peak_nits - luminance_nits) / (high - luminance_nits), 0.0), 1.0)
        channels = [
            min(max(luminance_nits + (value - luminance_nits) * chroma_scale, 0.0), peak_nits)
            for value in channels
        ]
    return tuple(encoder(value) for value in channels)


def parity_report(profile: Profile, sample_count: int = 20_000, seed: int = 0x4E4452) -> dict[str, float]:
    tone_table, pq_table = build_tables(profile)
    ratios = tuple(map(_normalize_ratio, (
        (1.0, 1.0, 1.0),
        (1.25, 0.92, 0.78),
        (0.58, 0.92, 1.42),
        (1.90, 0.42, 0.28),
        (0.30, 1.55, 0.40),
        (0.25, 0.48, 2.10),
    )))
    generator = random.Random(seed)
    errors: list[float] = []
    scenes = [0.0]
    for boundary in TONE_SEGMENT_BOUNDS:
        scenes.extend((
            math.nextafter(boundary, -math.inf) if boundary > 0.0 else 0.0,
            boundary,
            math.nextafter(boundary, math.inf),
        ))
    log2_max = math.log2(TONE_DOMAIN)
    scenes.extend(
        2.0 ** (-60.0 + (log2_max + 60.0) * index / 4096.0)
        for index in range(4097)
    )
    for index in range(sample_count):
        if index % 4 == 0:
            scene = generator.random() * 16.0
        else:
            scene = 2.0 ** generator.uniform(-60.0, log2_max)
        scenes.append(scene)
    for index, scene in enumerate(scenes):
        _, _, analytic_nits = tone_plan(scene, profile)
        _, _, accelerated_nits = lut_tone_plan(scene, profile, tone_table)
        ratio = ratios[index % len(ratios)]
        analytic = _peak_safe_codes(ratio, analytic_nits, profile.peak_nits, pq_from_nits)
        accelerated = _peak_safe_codes(
            ratio,
            accelerated_nits,
            profile.peak_nits,
            lambda nits: lut_pq(nits, pq_table),
        )
        errors.extend(abs(a - b) * 1023.0 for a, b in zip(analytic, accelerated))
    errors.sort()
    monotonic_violations = 0
    previous_quantized_code = -math.inf
    previous_float_code = -math.inf
    max_monotonic_backstep_code = 0.0
    for scene in sorted(set(scenes)):
        _, _, accelerated_nits = lut_tone_plan(scene, profile, tone_table)
        float_code = lut_pq(accelerated_nits, pq_table) * 1023.0
        quantized_code = round(float_code)
        if quantized_code < previous_quantized_code:
            monotonic_violations += 1
        max_monotonic_backstep_code = max(
            max_monotonic_backstep_code,
            previous_float_code - float_code,
        )
        previous_quantized_code = max(previous_quantized_code, quantized_code)
        previous_float_code = max(previous_float_code, float_code)
    return {
        "mean_code_error": sum(errors) / len(errors),
        "p99_code_error": errors[int(0.99 * (len(errors) - 1))],
        "max_code_error": errors[-1],
        "black_output_code": lut_pq(0.0, pq_table) * 1023.0,
        "monotonic_violations": float(monotonic_violations),
        "max_monotonic_backstep_code": max_monotonic_backstep_code,
    }


def small_angle_error() -> tuple[float, float]:
    max_sin = 0.0
    max_cos = 0.0
    for index in range(-12_000, 12_001):
        angle = math.radians(index / 1000.0)
        angle2 = angle * angle
        approx_cos = 1.0 - 0.5 * angle2 + angle2 * angle2 / 24.0
        approx_sin = angle * (1.0 - angle2 / 6.0 + angle2 * angle2 / 120.0)
        max_sin = max(max_sin, abs(approx_sin - math.sin(angle)))
        max_cos = max(max_cos, abs(approx_cos - math.cos(angle)))
    return max_sin, max_cos


def production_contracts(repo_root: Path) -> dict[str, bool]:
    native = (repo_root / "app/src/main/cpp/offscreen_egl_encoder_bridge.cpp").read_text(encoding="utf-8")
    return {
        "fp16_table": "GL_RGBA16F" in native and "kSensorNdrTonePqLutSize = 2048" in native,
        "piecewise_rows": "kSensorNdrTonePqLutRows" in native and
        "neoSensorSegmentedLutCoord" in native,
        "seventeen_rows": "NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0" in native,
        "absolute_high_residual": "mappedSignal[0]" in native and
        "absoluteNits[0]" in native and "sensorTone.r + sensorTone.g" in native,
        "exact_black": "data[0] = 0.0f" in native and "data[pqBlack] = 0.0f" in native,
        "no_dynamic_coordinate_divide": "(value - segment.y) * segment.z" in native,
        "no_fourth_root_shaper": "sqrt(sqrt(normalized))" not in native,
        "high_residual": "sensorLutHighResidual" in native and
        "encoded.r + encoded.g" in native,
        "sensor_only_gate": "sensorNdrTonePqLutReadyForFrame" in native,
        "bounded_domain_fallback": "sourceY <= NEO_SENSOR_TONE_DOMAIN" in native,
        "analytic_pq_fallback": "neoDelPqFromNits(peakSafe.r)" in native,
        "measured_color_retained": "neoApplyNdrMeasuredCorrection" in native,
        "warm_skin_retained": "moderateRed" in native and "bluePresent" in native,
        "telemetry": "sensorTonePqFrames=" in native and "sensorTonePqBuilds=" in native,
    }


if __name__ == "__main__":
    profiles = (
        ("NDR10+", NDR10),
        ("NIVUKX", NIVUKX),
    )
    failed = False
    for name, profile in profiles:
        report = parity_report(profile)
        print(name, report)
        failed = failed or report["max_code_error"] > 1.0
        failed = failed or report["black_output_code"] != 0.0
        failed = failed or report["monotonic_violations"] != 0.0
        failed = failed or report["max_monotonic_backstep_code"] > 0.25
    contracts = production_contracts(Path(__file__).resolve().parents[2])
    print("production_contracts", contracts)
    failed = failed or not all(contracts.values())
    if failed:
        raise SystemExit("sensor-linear NDR LUT simulation gate failed")
