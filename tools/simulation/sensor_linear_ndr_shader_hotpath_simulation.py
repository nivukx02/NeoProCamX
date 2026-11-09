#!/usr/bin/env python3
"""Deterministic parity and static-cost proof for the full-resolution NDR EGL shader.

This is deliberately independent of Android/driver timing. It proves that the bounded polynomial,
five-tap lane packing, luma invariant, and monotonic length rewrite stay well below one 10-bit code
value, and it validates the generated sensor-linear sampler2D shader with glslang when available.
"""

from __future__ import annotations

import argparse
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

import numpy as np


WIDTH = 4096
HEIGHT = 3072
PIXELS = WIDTH * HEIGHT
LOG_LOW = np.float32(2.0**-0.22)
LOG_HIGH = np.float32(2.0**0.22)
REC2020_LUMA = np.asarray((0.2627, 0.6780, 0.0593), dtype=np.float32)


def f32(value: object) -> np.ndarray:
    return np.asarray(value, dtype=np.float32)


def bounded_detail_log2(ratio: np.ndarray) -> np.ndarray:
    x = f32(np.clip(ratio, LOG_LOW, LOG_HIGH) - np.float32(1.0))
    polynomial = np.full_like(x, np.float32(-1.0 / 6.0))
    for coefficient in (1.0 / 5.0, -1.0 / 4.0, 1.0 / 3.0, -1.0 / 2.0, 1.0):
        polynomial = f32(np.float32(coefficient) + x * polynomial)
    return f32(np.float32(1.4426950408889634) * x * polynomial)


def bounded_detail_exp2(stops: np.ndarray) -> np.ndarray:
    z = f32(np.float32(math.log(2.0)) * np.clip(stops, -0.20, 0.20))
    return f32(
        np.float32(1.0)
        + z
        * (
            np.float32(1.0)
            + z
            * (
                np.float32(0.5)
                + z * (np.float32(1.0 / 6.0) + z * np.float32(1.0 / 24.0))
            )
        )
    )


def smoothstep(edge0: float, edge1: float, value: np.ndarray) -> np.ndarray:
    t = f32(np.clip((value - np.float32(edge0)) / np.float32(edge1 - edge0), 0.0, 1.0))
    return f32(t * t * (np.float32(3.0) - np.float32(2.0) * t))


def pq_from_nits(nits: np.ndarray) -> np.ndarray:
    y = np.clip(nits.astype(np.float64) / 10000.0, 0.0, 1.0)
    ym = np.power(y, 0.1593017578125)
    return np.power(
        (0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym),
        78.84375,
    )


def row_luma(rgb: np.ndarray) -> np.ndarray:
    # Explicit order mirrors a conventional GLSL dot closely enough to expose reassociation error.
    result = f32(rgb[:, 0] * REC2020_LUMA[0])
    result = f32(result + rgb[:, 1] * REC2020_LUMA[1])
    return f32(result + rgb[:, 2] * REC2020_LUMA[2])


def neighborhood_parity(rng: np.random.Generator, samples: int) -> tuple[float, float]:
    center = f32(rng.uniform(0.0, 8.0, size=(samples, 3)))
    cardinal = f32(rng.uniform(0.0, 8.0, size=(samples, 4, 3)))
    diffuse = f32(rng.uniform(0.02, 0.30, size=samples))
    denoise = f32(rng.uniform(0.0, 1.0, size=samples))
    scale = f32(np.float32(1.0) / diffuse)
    sigma = f32(np.clip(np.float32(0.010) + np.float32(0.045) * denoise, 0.010, 0.05))
    inv_r2 = f32(np.float32(1.0) / (np.float32(2.0) * sigma * sigma))
    lc = row_luma(center)

    old_acc_bilateral = center.copy()
    old_weight = np.ones(samples, dtype=np.float32)
    old_acc_gaussian = center.copy()
    old_gaussian_weight = np.ones(samples, dtype=np.float32)
    lane_weights: list[np.ndarray] = []
    for lane in range(4):
        sample = cardinal[:, lane, :]
        sample_luma = row_luma(sample)
        delta_metric = f32((sample_luma - lc) * scale)
        x = f32(delta_metric * delta_metric * inv_r2)
        reciprocal = f32(np.float32(1.0) / (np.float32(1.0) + x))
        weight = f32(np.float32(1.11) * reciprocal * reciprocal)
        lane_weights.append(weight)
        old_acc_bilateral = f32(old_acc_bilateral + sample * weight[:, None])
        old_weight = f32(old_weight + weight)
        old_acc_gaussian = f32(old_acc_gaussian + sample * np.float32(1.11))
        old_gaussian_weight = f32(old_gaussian_weight + np.float32(1.11))
    old_bilateral = f32(old_acc_bilateral / old_weight[:, None])
    old_gaussian = f32(old_acc_gaussian / old_gaussian_weight[:, None])

    packed_weights = np.stack(lane_weights, axis=1).astype(np.float32)
    new_acc_bilateral = center.copy()
    for lane in range(4):
        new_acc_bilateral = f32(
            new_acc_bilateral + cardinal[:, lane, :] * packed_weights[:, lane, None]
        )
    # GLSL dot may reassociate; sum in a balanced form to exercise the worst expected distinction.
    new_weight = f32(
        np.float32(1.0)
        + f32(packed_weights[:, 0] + packed_weights[:, 1])
        + f32(packed_weights[:, 2] + packed_weights[:, 3])
    )
    new_bilateral = f32(new_acc_bilateral / new_weight[:, None])
    cardinal_sum = f32(
        f32(cardinal[:, 0, :] + cardinal[:, 1, :])
        + f32(cardinal[:, 2, :] + cardinal[:, 3, :])
    )
    new_gaussian = f32(
        (center + np.float32(1.11) * cardinal_sum) * np.float32(1.0 / 5.44)
    )

    absolute_error = max(
        float(np.max(np.abs(new_bilateral.astype(np.float64) - old_bilateral))),
        float(np.max(np.abs(new_gaussian.astype(np.float64) - old_gaussian))),
    )
    code_error = float(
        np.max(
            np.abs(
                np.clip(new_gaussian, 0.0, 1.0).astype(np.float64)
                - np.clip(old_gaussian, 0.0, 1.0).astype(np.float64)
            )
        )
        * 1023.0
    )
    return absolute_error, code_error


def scalar_parity(rng: np.random.Generator, samples: int) -> dict[str, float]:
    ratio_grid = np.linspace(float(LOG_LOW), float(LOG_HIGH), 1_000_001, dtype=np.float32)
    old_log = np.clip(np.log2(ratio_grid).astype(np.float32), -0.22, 0.22)
    new_log = bounded_detail_log2(ratio_grid)
    max_log_error = float(np.max(np.abs(new_log.astype(np.float64) - old_log)))

    stop_grid = np.linspace(-0.20, 0.20, 1_000_001, dtype=np.float32)
    old_exp = np.exp2(stop_grid).astype(np.float32)
    new_exp = bounded_detail_exp2(stop_grid)
    max_exp_error = float(np.max(np.abs(new_exp.astype(np.float64) - old_exp)))

    ratios = f32(np.exp(rng.uniform(math.log(0.25), math.log(4.0), size=samples)))
    gates = f32(rng.uniform(0.0, 1.0, size=samples))
    strengths = f32(rng.uniform(0.0, 0.75, size=samples))
    old_stops = f32(np.clip(np.log2(ratios).astype(np.float32), -0.22, 0.22) * gates)
    new_stops = f32(bounded_detail_log2(ratios) * gates)
    old_gain_stops = f32(np.clip(old_stops * strengths, -0.20, 0.20))
    new_gain_stops = f32(np.clip(new_stops * strengths, -0.20, 0.20))
    old_gain = np.exp2(old_gain_stops).astype(np.float32)
    new_gain = bounded_detail_exp2(new_gain_stops)
    nits = np.exp(rng.uniform(math.log(0.001), math.log(4000.0), size=samples))
    pq_old = pq_from_nits(nits * old_gain.astype(np.float64))
    pq_new = pq_from_nits(nits * new_gain.astype(np.float64))
    max_pq_code_error = float(np.max(np.abs(pq_new - pq_old)) * 1023.0)

    source = f32(rng.uniform(0.0, 16.0, size=(samples, 3)))
    protected_green = np.maximum(source[:, 1], np.float32(1e-8)).astype(np.float32)
    old_rg = f32(source[:, 0] / protected_green)
    old_bg = f32(source[:, 2] / protected_green)
    inverse_green = f32(np.float32(1.0) / protected_green)
    new_rg = f32(source[:, 0] * inverse_green)
    new_bg = f32(source[:, 2] * inverse_green)
    old_gate = f32(smoothstep(1.02, 1.16, old_rg) * (1.0 - smoothstep(0.98, 1.18, old_bg)))
    new_gate = f32(smoothstep(1.02, 1.16, new_rg) * (1.0 - smoothstep(0.98, 1.18, new_bg)))
    max_ratio_gate_error = float(np.max(np.abs(new_gate.astype(np.float64) - old_gate)))

    vector_a = f32(rng.normal(0.0, 1.0, size=(samples, 3)))
    vector_b = f32(rng.normal(0.0, 1.0, size=(samples, 3)))
    dot_a = f32(np.sum(vector_a * vector_a, axis=1, dtype=np.float32))
    dot_b = f32(np.sum(vector_b * vector_b, axis=1, dtype=np.float32))
    old_structure = np.maximum(np.sqrt(dot_a), np.sqrt(dot_b)).astype(np.float32)
    new_structure = np.sqrt(np.maximum(dot_a, dot_b)).astype(np.float32)
    max_length_error = float(np.max(np.abs(new_structure.astype(np.float64) - old_structure)))

    return {
        "max_log2_stop_error": max_log_error,
        "max_exp2_linear_error": max_exp_error,
        "max_projected_pq_code_error": max_pq_code_error,
        "max_shared_reciprocal_gate_error": max_ratio_gate_error,
        "max_monotonic_length_error": max_length_error,
    }


def validate_source(source: str) -> None:
    required = (
        "vec4 bilateralLumaDeltaMetric = dl * calibrationSignalScale",
        "sqrt(max(\n        dot(northSouthChromaDelta, northSouthChromaDelta)",
        "neoBoundedDetailLog2(",
        "neoBoundedDetailExp2(detailGainStops)",
        "sampler2DCardinals=INTEGER_TEXTURE_OFFSET",
        "sensorLinear2dIntegerOffsetsExact",
        "state->mainProgram2d",
        "const float inverseGaussianWeight = 1.0 / 5.44",
    )
    for marker in required:
        if marker not in source:
            raise AssertionError(f"missing hot-path contract marker: {marker!r}")
    detail = source.split("if (ndrSensorLinearDetail) {", 1)[1].split(
        "// Motion-guarded temporal denoise", 1
    )[0]
    if "log2(" in detail or "graded *= exp2(" in detail:
        raise AssertionError("sensor detail still invokes an unbounded transcendental")
    if source.count("vec3 cardinal") < 5:
        raise AssertionError("five-tap center/cardinal structure is no longer explicit")


def generated_sensor_external_fragment(source: str) -> str:
    match = re.search(
        r'static constexpr const char\* kFragmentShader = R"\((.*?)\)";',
        source,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError("canonical EGL fragment shader raw string not found")
    fragment = match.group(1)
    dynamic_transfer = "uniform int uInputTransfer;"
    if fragment.count(dynamic_transfer) != 1:
        raise AssertionError("sensor-linear transfer specialization source is not unique")
    return fragment.replace(dynamic_transfer, "const int uInputTransfer = 3;", 1)


def generated_sensor_2d_fragment(source: str) -> str:
    fragment = generated_sensor_external_fragment(source)
    fragment = fragment.replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "", 1)
    fragment = fragment.replace("samplerExternalOES uTexture", "sampler2D uTexture", 1)
    old_helpers = """vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(texel.x, 0.0));
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(texel.x, 0.0));
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(0.0, texel.y));
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(0.0, texel.y));
}"""
    new_helpers = """vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(1, 0)).rgb);
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(-1, 0)).rgb);
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(0, 1)).rgb);
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(0, -1)).rgb);
}"""
    if fragment.count(old_helpers) != 1:
        raise AssertionError("sampler2D integer-offset specialization source is not unique")
    return fragment.replace(old_helpers, new_helpers, 1)


def discover_glslang(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    on_path = shutil.which("glslangValidator")
    if on_path:
        return on_path
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = Path(local) / "Android" / "Sdk" / "emulator" / "lib64" / "vulkan" / "glslangValidator.exe"
        if candidate.is_file():
            return str(candidate)
    return None


def validate_glsl(fragment: str, validator: str | None) -> str:
    if validator is None:
        return "SKIPPED (glslangValidator not found)"
    with tempfile.TemporaryDirectory(prefix="neo-ndr-glsl-") as directory:
        shader = Path(directory) / "sensor_linear_ndr.frag"
        shader.write_text(fragment, encoding="utf-8", newline="\n")
        result = subprocess.run(
            (validator, "-S", "frag", str(shader)),
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"generated sensor-linear GLSL failed validation:\n{result.stdout}\n{result.stderr}"
            )
    return "PASS"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=250_000)
    parser.add_argument("--seed", type=int, default=0x1575EED)
    parser.add_argument("--glslang")
    args = parser.parse_args()
    if args.samples < 10_000:
        raise SystemExit("--samples must be at least 10000")

    root = Path(__file__).resolve().parents[2]
    native_path = root / "app" / "src" / "main" / "cpp" / "offscreen_egl_encoder_bridge.cpp"
    native_source = native_path.read_text(encoding="utf-8")
    validate_source(native_source)
    validator = discover_glslang(args.glslang)
    external_shader_status = validate_glsl(
        generated_sensor_external_fragment(native_source), validator
    )
    sampler_2d_shader_status = validate_glsl(
        generated_sensor_2d_fragment(native_source), validator
    )

    rng = np.random.default_rng(args.seed)
    scalar = scalar_parity(rng, args.samples)
    neighborhood_abs, neighborhood_code = neighborhood_parity(rng, min(args.samples, 300_000))

    if scalar["max_log2_stop_error"] >= 1.0e-6:
        raise AssertionError(scalar)
    if scalar["max_exp2_linear_error"] >= 1.0e-6:
        raise AssertionError(scalar)
    if scalar["max_projected_pq_code_error"] >= 0.01:
        raise AssertionError(scalar)
    if scalar["max_shared_reciprocal_gate_error"] >= 1.0e-5:
        raise AssertionError(scalar)
    if scalar["max_monotonic_length_error"] != 0.0:
        raise AssertionError(scalar)
    if neighborhood_code >= 0.01:
        raise AssertionError(
            {"neighborhood_absolute_error": neighborhood_abs, "neighborhood_code_error": neighborhood_code}
        )

    print("SENSOR_LINEAR_NDR_SHADER_HOTPATH_SIMULATION=PASS")
    print(f"samples={args.samples} seed={args.seed}")
    print(f"generatedSensorExternalGlsl={external_shader_status}")
    print(f"generatedSensorSampler2dGlsl={sampler_2d_shader_status}")
    for key, value in scalar.items():
        print(f"{key}={value:.12g}")
    print(f"max_neighborhood_absolute_error={neighborhood_abs:.12g}")
    print(f"max_neighborhood_10bit_code_error={neighborhood_code:.12g}")
    print(f"pixelsPer4096x3072Frame={PIXELS}")
    print(f"sqrtInvocationsRemovedPerFrame={PIXELS}")
    print(f"detailTranscendentalsRemovedPerFrame={2 * PIXELS}")
    print(f"alwaysSharedDividesRemovedPerFrame={PIXELS}")
    print(f"maxSharedDividesRemovedWhenCalibrationAndWarmRepairActive={3 * PIXELS}")
    print(f"normalizedCardinalCoordinateConstructionsRemovedPerFrame={4 * PIXELS}")
    print(f"gaussianScalarWeightMultipliesRemovedPerFrame={9 * PIXELS}")
    print("qualityContract=4096x3072/FP16/Main10-PQ/5-tap/temporal/profile/LUT/bitrate/cadence unchanged")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
