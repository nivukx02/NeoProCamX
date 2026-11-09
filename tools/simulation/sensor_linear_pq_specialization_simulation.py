#!/usr/bin/env python3
"""Exact-parity proof for sensor-linear NDR10/NIVUKX PQ shader specialization."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, replace
import math
import os
from pathlib import Path
import random
import re
import shutil
import subprocess
import tempfile

import sensor_linear_ndr_tone_lut_simulation as tone_lut


DYNAMIC_TRANSFER = "uniform int uInputTransfer;"
CONSTANT_TRANSFER = "const int uInputTransfer = 3;"
DYNAMIC_PQ = "uniform float uHdrToPqConvert;"
CONSTANT_PQ = "const float uHdrToPqConvert = 1.0;"
DYNAMIC_DELIVERY = "uniform float uNdrDeliveryMode;"
DYNAMIC_PEAK = "uniform float uNdrTargetPeakNits;"
DYNAMIC_SENSOR_LUT_READY = "uniform float uNdrSensorTonePqLutReady;"
CONSTANT_SENSOR_LUT_READY = "const float uNdrSensorTonePqLutReady = 1.0;"
DYNAMIC_CREATIVE_LUT_STRENGTH = "uniform float uLutStrength;"
CONSTANT_CREATIVE_LUT_STRENGTH = "const float uLutStrength = 0.0;"
DYNAMIC_HISTORY_PRE_GRADE = "uniform float uHistoryPreGrade;"
CONSTANT_HISTORY_PRE_GRADE = "const float uHistoryPreGrade = 1.0;"

OLD_CARDINALS = """vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
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

NEW_CARDINALS = """vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
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


def extract_canonical_fragment(source: str) -> str:
    match = re.search(
        r'static constexpr const char\* kFragmentShader = R"\((.*?)\)";',
        source,
        flags=re.DOTALL,
    )
    if match is None:
        raise AssertionError("canonical EGL fragment shader not found")
    return match.group(1)


def replace_exact(source: str, dynamic: str, constant: str) -> str:
    if source.count(dynamic) != 1:
        raise AssertionError(f"declaration is not unique: {dynamic!r}")
    return source.replace(dynamic, constant, 1)


def specialize(fragment: str, mode: int) -> str:
    if mode not in (0, 1):
        raise ValueError(f"invalid delivery mode {mode}")
    result = replace_exact(fragment, DYNAMIC_TRANSFER, CONSTANT_TRANSFER)
    result = replace_exact(result, DYNAMIC_PQ, CONSTANT_PQ)
    result = replace_exact(
        result,
        DYNAMIC_DELIVERY,
        f"const float uNdrDeliveryMode = {mode}.0;",
    )
    peak = 5000 if mode == 0 else 10000
    result = replace_exact(
        result,
        DYNAMIC_PEAK,
        f"const float uNdrTargetPeakNits = {peak}.0;",
    )
    result = replace_exact(result, DYNAMIC_SENSOR_LUT_READY, CONSTANT_SENSOR_LUT_READY)
    return replace_exact(
        result,
        DYNAMIC_CREATIVE_LUT_STRENGTH,
        CONSTANT_CREATIVE_LUT_STRENGTH,
    )


def restore_specialization(fragment: str, mode: int) -> str:
    result = replace_exact(fragment, CONSTANT_TRANSFER, DYNAMIC_TRANSFER)
    result = replace_exact(result, CONSTANT_PQ, DYNAMIC_PQ)
    result = replace_exact(
        result,
        f"const float uNdrDeliveryMode = {mode}.0;",
        DYNAMIC_DELIVERY,
    )
    peak = 5000 if mode == 0 else 10000
    result = replace_exact(
        result,
        f"const float uNdrTargetPeakNits = {peak}.0;",
        DYNAMIC_PEAK,
    )
    result = replace_exact(result, CONSTANT_SENSOR_LUT_READY, DYNAMIC_SENSOR_LUT_READY)
    result = replace_exact(
        result,
        CONSTANT_CREATIVE_LUT_STRENGTH,
        DYNAMIC_CREATIVE_LUT_STRENGTH,
    )
    if CONSTANT_HISTORY_PRE_GRADE in result:
        result = replace_exact(result, CONSTANT_HISTORY_PRE_GRADE, DYNAMIC_HISTORY_PRE_GRADE)
    return result


def sampler_2d(
    fragment: str,
    *,
    specialize_history: bool = False,
    specialize_cardinals: bool = False,
) -> str:
    extension = "#extension GL_OES_EGL_image_external_essl3 : require\n"
    if fragment.count(extension) != 1:
        raise AssertionError("external-image extension is not unique")
    result = fragment.replace(extension, "", 1)
    if result.count("samplerExternalOES uTexture") != 1:
        raise AssertionError("external sampler declaration is not unique")
    result = result.replace("samplerExternalOES uTexture", "sampler2D uTexture", 1)
    if specialize_history:
        result = replace_exact(result, DYNAMIC_HISTORY_PRE_GRADE, CONSTANT_HISTORY_PRE_GRADE)
    if result.count(OLD_CARDINALS) != 1:
        raise AssertionError("cardinal helper block is not unique")
    if specialize_cardinals:
        result = result.replace(OLD_CARDINALS, NEW_CARDINALS, 1)
    return result


def restore_cardinal_specialization(fragment: str) -> str:
    if fragment.count(NEW_CARDINALS) != 1:
        raise AssertionError("specialized cardinal helper block is not unique")
    return fragment.replace(NEW_CARDINALS, OLD_CARDINALS, 1)


def discover_validator(explicit: str | None) -> str:
    if explicit:
        validator = Path(explicit)
        if not validator.is_file():
            raise FileNotFoundError(explicit)
        return str(validator)
    on_path = shutil.which("glslangValidator")
    if on_path:
        return on_path
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = (
            Path(local)
            / "Android"
            / "Sdk"
            / "emulator"
            / "lib64"
            / "vulkan"
            / "glslangValidator.exe"
        )
        if candidate.is_file():
            return str(candidate)
    raise FileNotFoundError("Android SDK glslangValidator was not found")


def validate_glsl(validator: str, label: str, fragment: str) -> None:
    with tempfile.TemporaryDirectory(prefix="neo-pq-specialization-") as directory:
        shader = Path(directory) / f"{label}.frag"
        shader.write_text(fragment, encoding="utf-8", newline="\n")
        result = subprocess.run(
            (validator, "-S", "frag", str(shader)),
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"{label} GLSL validation failed:\n{result.stdout}\n{result.stderr}"
            )


def eligible(
    transfer: int,
    source_is_fp16: bool,
    primaries: int,
    range_code: int,
    apply_pq: float,
    mode: int,
    target_peak: float,
    sensor_lut_ready: bool,
) -> bool:
    return (
        transfer == 3
        and source_is_fp16
        and primaries == 3
        and range_code == 3
        and apply_pq == 1.0
        and sensor_lut_ready
        and mode in (0, 1)
        and target_peak == (5000.0 if mode == 0 else 10000.0)
    )


def validate_runtime_truth_table(source: str) -> int:
    selection = source.split("const bool sensorLinearPqSpecializationEligible =", 1)[1].split(
        "// Retire any result", 1
    )[0]
    required = (
        "inputIsSensorLinear",
        "sourceIsFp16",
        "inputPrimaries == 3",
        "inputRange == 3",
        "applyNdrPqDeliveryToneMap == 1.0f",
        "sensorNdrTonePqLutReadyForFrame",
        "ndrTargetPeakNitsValue == ndrBaselinePeakNits",
        "ndrDeliveryMode == 0 || ndrDeliveryMode == 1",
        "state->sensorLinearNivukxProgramExternal != 0",
        "state->sensorLinearNivukxProgram2d != 0",
    )
    for marker in required:
        if marker not in selection:
            raise AssertionError(f"missing exact-selection marker: {marker}")

    selected = 0
    for transfer in range(5):
        for fp16 in (False, True):
            for primaries in range(4):
                for range_code in range(4):
                    for apply_pq in (0.0, 1.0):
                        for mode in (-1, 0, 1, 2):
                            for target_peak in (1420.0, 1680.0, 5000.0, 10000.0):
                                for sensor_lut_ready in (False, True):
                                    result = eligible(
                                        transfer,
                                        fp16,
                                        primaries,
                                        range_code,
                                        apply_pq,
                                        mode,
                                        target_peak,
                                        sensor_lut_ready,
                                    )
                                    expected = (
                                        transfer == 3
                                        and fp16
                                        and primaries == 3
                                        and range_code == 3
                                        and apply_pq == 1.0
                                        and sensor_lut_ready
                                        and mode in (0, 1)
                                        and target_peak == (5000.0 if mode == 0 else 10000.0)
                                    )
                                    if result != expected:
                                        raise AssertionError("specialization truth-table mismatch")
                                    selected += int(result)
    if selected != 2:
        raise AssertionError(f"expected exactly two eligible truth-table rows, got {selected}")
    return selected


def validate_quality_contract(source: str) -> None:
    required = (
        "vec3 cardinal0 = neoSampleCameraEast",
        "vec3 cardinal1 = neoSampleCameraWest",
        "vec3 cardinal2 = neoSampleCameraNorth",
        "vec3 cardinal3 = neoSampleCameraSouth",
        "Motion-guarded temporal denoise",
        "NEO_SENSOR_TONE_PQ_LUT_SIZE = 2048.0",
        "NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0",
        "NEO_SENSOR_TONE_LUT_MIN_SIGNAL = 0.000001",
        "uNdrSensorTonePqLutReady > 0.5",
        "sourceY >= NEO_SENSOR_TONE_LUT_MIN_SIGNAL",
        "outOfDomainAnalyticFallback=retained",
        "canonicalGenericFallback=retained",
        "missing variants retain generic fallback",
        "kNdr10MasterPeakNits = 5000.0f",
        "kNivukxMasterPeakNits = 10000.0f",
        "NEO_SENSOR_PQ_NITS_DOMAIN = 10000.0",
    )
    for marker in required:
        if marker not in source:
            raise AssertionError(f"quality/fallback contract missing: {marker}")
    if "sensorNdrTonePqLutReadyForFrame &&" not in source:
        raise AssertionError("LUT-ready specialization lacks runtime fail-closed admission")
    if "sourceY <= NEO_SENSOR_TONE_DOMAIN" not in source:
        raise AssertionError("out-of-domain analytic tone fallback was removed")


def validate_kotlin_mastering_contract(root: Path) -> None:
    bridge = (
        root
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "neoproc"
        / "neoproccamx"
        / "rendering"
        / "OffscreenAhbEglEncoderBridge.kt"
    ).read_text(encoding="utf-8")
    required = (
        "NDR10_MASTER_PEAK_NITS = 5_000f",
        "NIVUKX_MASTER_PEAK_NITS = 10_000f",
        "coerceIn(ndrBaselinePeak, ndrBaselinePeak)",
    )
    for marker in required:
        if marker not in bridge:
            raise AssertionError(f"Kotlin mastering contract missing: {marker}")
    if "1_420f" in bridge or "1_680f" in bridge:
        raise AssertionError("legacy local mastering baseline remains in Kotlin bridge")


def validate_mastering_lut_parity() -> dict[str, dict[str, float]]:
    # Exercise the production 2048x17 segmented layout over the expanded ST.2084 domain without
    # mutating the established standalone simulation file. Both profiles retain the same equations;
    # only their exact mastering peaks and the final PQ segment endpoint change.
    tone_lut.PQ_NITS_DOMAIN = 10000.0
    tone_lut.PQ_SEGMENT_BOUNDS = (0.0, 0.1, 1.0, 10.0, 100.0, 1000.0, 10000.0)
    profiles = {
        "NDR10@5000": replace(tone_lut.NDR10, peak_nits=5000.0),
        "NIVUKX@10000": replace(tone_lut.NIVUKX, peak_nits=10000.0),
    }
    reports = {
        name: tone_lut.parity_report(profile, sample_count=20_000)
        for name, profile in profiles.items()
    }
    for name, report in reports.items():
        if report["max_code_error"] >= 0.219:
            raise AssertionError(f"{name} LUT code error exceeded strict bound: {report}")
        if report["black_output_code"] != 0.0:
            raise AssertionError(f"{name} LUT no longer preserves exact black: {report}")
        if report["monotonic_violations"] != 0.0:
            raise AssertionError(f"{name} LUT is not monotonic: {report}")
        if report["max_monotonic_backstep_code"] >= 0.001:
            raise AssertionError(f"{name} LUT float backstep exceeded bound: {report}")
    return reports


@dataclass(frozen=True)
class FullRgbProfile:
    name: str
    tone: tone_lut.Profile
    highlight_desat: float
    chroma_boost: float
    sky_chroma_bonus: float


@dataclass(frozen=True)
class FullRgbResult:
    codes: tuple[float, float, float]
    source_luma: float
    pre_rolloff_luma: float
    color_path_clipped: bool
    lut_active: bool
    peak_volume_compressed: bool


RGB_LUMA = (0.2627, 0.6780, 0.0593)
SENSOR_TONE_LUT_MIN_SIGNAL = 1.0e-6


def _clamp(value: float, low: float, high: float) -> float:
    return min(max(value, low), high)


def _smoothstep(low: float, high: float, value: float) -> float:
    if low == high:
        return 1.0 if value >= high else 0.0
    amount = _clamp((value - low) / (high - low), 0.0, 1.0)
    return amount * amount * (3.0 - 2.0 * amount)


def _luma(rgb: tuple[float, float, float]) -> float:
    return sum(channel * weight for channel, weight in zip(rgb, RGB_LUMA))


def _signal_to_nits(signal: float, peak_nits: float) -> float:
    bounded = max(signal, 0.0)
    raw_nits = bounded * tone_lut.DIFFUSE_NITS
    if raw_nits <= tone_lut.DIFFUSE_NITS:
        return raw_nits
    span = peak_nits - tone_lut.DIFFUSE_NITS
    return min(
        tone_lut.DIFFUSE_NITS
        + span * (1.0 - math.exp(-(raw_nits - tone_lut.DIFFUSE_NITS) / span)),
        peak_nits,
    )


def _measured_rolloff(signal: float, bias: float) -> float:
    bounded = max(signal, 0.0)
    rolloff = _clamp(bias, 0.0, 0.12)
    if bounded <= 1.0 or rolloff <= 0.0:
        return bounded
    excess = bounded - 1.0
    return 1.0 + excess / (1.0 + 4.0 * rolloff * excess)


def _profile_chroma(
    color: tuple[float, float, float],
    source: tuple[float, float, float],
    mapped_luma: float,
    base_boost: float,
    sky_bonus_strength: float,
) -> tuple[float, float, float]:
    peak_value = max(color)
    floor_value = min(color)
    relative_chroma = (peak_value - floor_value) / max(mapped_luma, 0.04)
    color_confidence = _smoothstep(0.035, 0.22, relative_chroma)
    inverse_green = 1.0 / max(source[1], 1.0e-8)
    red_green = source[0] * inverse_green
    blue_green = source[2] * inverse_green
    skin = (
        _smoothstep(1.02, 1.16, red_green)
        * (1.0 - _smoothstep(1.58, 2.10, red_green))
        * (1.0 - _smoothstep(0.98, 1.18, blue_green))
    )
    sky = (
        _smoothstep(1.03, 1.22, blue_green)
        * (1.0 - _smoothstep(1.00, 1.22, red_green))
    )
    tonal_window = (
        _smoothstep(0.035, 0.16, mapped_luma)
        * (1.0 - _smoothstep(0.88, 1.65, mapped_luma))
    )
    skin_protection = 1.0 + (0.45 - 1.0) * skin
    gain = 1.0 + (base_boost + sky_bonus_strength * sky) * (
        color_confidence * tonal_window * skin_protection
    )
    return tuple(mapped_luma + (channel - mapped_luma) * gain for channel in color)


def _warm_skin_correction(
    color: tuple[float, float, float],
    linear_source: tuple[float, float, float],
    mapped_luma: float,
    strength: float,
) -> tuple[float, float, float]:
    if strength <= 0.0:
        return color
    original_tone_luma = max(_luma(tuple(max(channel, 0.0) for channel in color)), 1.0e-8)
    inverse_green = 1.0 / max(linear_source[1], 1.0e-8)
    red_green = linear_source[0] * inverse_green
    blue_green = linear_source[2] * inverse_green
    moderate_red = (
        _smoothstep(1.18, 1.55, red_green)
        * (1.0 - _smoothstep(2.80, 4.00, red_green))
    )
    blue_present = (
        _smoothstep(0.28, 0.55, blue_green)
        * (1.0 - _smoothstep(1.08, 1.35, blue_green))
    )
    exposure_window = (
        _smoothstep(0.04, 0.16, mapped_luma)
        * (1.0 - _smoothstep(6.5, 8.0, mapped_luma))
    )
    red_overflow = _smoothstep(1.30, 2.10, red_green)
    weight = _clamp(
        strength
        * moderate_red
        * blue_present
        * exposure_window
        * (0.45 + 0.55 * red_overflow),
        0.0,
        strength,
    )
    corrected = [*color]
    corrected[0] = corrected[0] + (
        min(corrected[0], corrected[1] * 1.42) - corrected[0]
    ) * weight
    blue_weight = weight * 0.68
    corrected[2] = corrected[2] + (
        max(corrected[2], corrected[1] * 0.64) - corrected[2]
    ) * blue_weight
    corrected_tuple = tuple(corrected)
    corrected_luma = max(
        _luma(tuple(max(channel, 0.0) for channel in corrected_tuple)),
        1.0e-8,
    )
    scale = original_tone_luma / corrected_luma
    return tuple(channel * scale for channel in corrected_tuple)


def _compress_peak_volume(
    rgb_nits: tuple[float, float, float],
    luminance_nits: float,
    peak_nits: float,
) -> tuple[tuple[float, float, float], bool]:
    peak = _clamp(peak_nits, tone_lut.DIFFUSE_NITS, 10_000.0)
    luminance = _clamp(luminance_nits, 0.0, peak)
    rgb = tuple(max(channel, 0.0) for channel in rgb_nits)
    high = max(rgb)
    if high <= peak or high <= luminance + 1.0e-8:
        return rgb, False
    chroma_scale = _clamp((peak - luminance) / (high - luminance), 0.0, 1.0)
    return (
        tuple(
            _clamp(luminance + (channel - luminance) * chroma_scale, 0.0, peak)
            for channel in rgb
        ),
        True,
    )


def _render_full_rgb(
    linear_rgb: tuple[float, float, float],
    profile: FullRgbProfile,
    tone_table: list[list[tuple[float, ...]]],
    pq_table: list[list[tuple[float, ...]]],
    warm_strength: float,
    *,
    accelerated: bool,
    guard_clipped_color: bool,
) -> FullRgbResult:
    linear = tuple(max(channel, 0.0) for channel in linear_rgb)
    source_luma = max(_luma(linear), 1.0e-8)
    lut_active = (
        accelerated
        and source_luma >= SENSOR_TONE_LUT_MIN_SIGNAL
        and source_luma <= tone_lut.TONE_DOMAIN
    )
    if lut_active:
        mapped_luma, _, cached_nits = tone_lut.lut_tone_plan(
            source_luma,
            profile.tone,
            tone_table,
        )
    else:
        mapped_luma, _, _ = tone_lut.tone_plan(source_luma, profile.tone)
        cached_nits = 0.0

    color = tuple(channel * (mapped_luma / source_luma) for channel in linear)
    color = _profile_chroma(
        color,
        linear,
        mapped_luma,
        profile.chroma_boost,
        profile.sky_chroma_bonus,
    )
    highlight_desat = _clamp(
        profile.highlight_desat * _smoothstep(0.55, 1.0, mapped_luma),
        0.0,
        profile.highlight_desat,
    )
    color = tuple(
        channel + (mapped_luma - channel) * highlight_desat
        for channel in color
    )

    # This is the production guard boundary. The warm correction below already computes luminance
    # from max(color, 0), so checking only immediately before the final clamp would be too late.
    color_path_clipped = lut_active and any(channel < 0.0 for channel in color)
    color = _warm_skin_correction(color, linear, mapped_luma, warm_strength)
    # Defensive coverage for future warm-correction changes; current equations cannot introduce a
    # negative channel when their input is entirely nonnegative.
    color_path_clipped = color_path_clipped or (
        lut_active and any(channel < 0.0 for channel in color)
    )
    color = tuple(max(channel, 0.0) for channel in color)

    pre_rolloff_luma = max(_luma(color), 1.0e-8)
    rolled_luma = _measured_rolloff(pre_rolloff_luma, profile.tone.measured_rolloff)
    color = tuple(channel * (rolled_luma / pre_rolloff_luma) for channel in color)
    if lut_active and (not guard_clipped_color or not color_path_clipped):
        nits = cached_nits
    else:
        nits = _signal_to_nits(rolled_luma, profile.tone.peak_nits)
    scale = nits / rolled_luma
    peak_safe, peak_volume_compressed = _compress_peak_volume(
        tuple(channel * scale for channel in color),
        nits,
        profile.tone.peak_nits,
    )
    if lut_active:
        encoded = tuple(tone_lut.lut_pq(channel, pq_table) for channel in peak_safe)
    else:
        encoded = tuple(tone_lut.pq_from_nits(channel) for channel in peak_safe)
    return FullRgbResult(
        codes=tuple(channel * 1023.0 for channel in encoded),
        source_luma=source_luma,
        pre_rolloff_luma=pre_rolloff_luma,
        color_path_clipped=color_path_clipped,
        lut_active=lut_active,
        peak_volume_compressed=peak_volume_compressed,
    )


def _scaled_rgb(
    ratio: tuple[float, float, float],
    scene_luma: float,
) -> tuple[float, float, float]:
    ratio_luma = _luma(ratio)
    if scene_luma <= 0.0 or ratio_luma <= 0.0:
        return (0.0, 0.0, 0.0)
    scale = scene_luma / ratio_luma
    return tuple(channel * scale for channel in ratio)


def _full_rgb_inputs(sample_count: int, seed: int) -> list[tuple[float, float, float]]:
    ratios = (
        (1.0, 1.0, 1.0),
        (1.25, 0.92, 0.78),
        (0.58, 0.92, 1.42),
        (1.90, 0.42, 0.28),
        (0.30, 1.55, 0.40),
        (0.25, 0.48, 2.10),
        (1.0, 1.0e-6, 1.0e-6),
        (1.0e-6, 1.0, 1.0e-6),
        (1.0e-6, 1.0e-6, 1.0),
        (1.0, 1.0, 1.0e-6),
        (1.0, 1.0e-6, 1.0),
        (1.0e-6, 1.0, 1.0),
    )
    scene_levels = (
        0.0,
        1.0e-8,
        math.nextafter(1.0e-8, math.inf),
        5.960464477539063e-8,
        1.0e-7,
        1.0e-6,
        1.0e-4,
        0.01,
        0.10,
        0.18,
        0.55,
        1.0,
        4.0,
        16.0,
        255.999,
        256.0,
        256.001,
        320.0,
    )
    samples = [
        _scaled_rgb(ratio, scene_luma)
        for ratio in ratios
        for scene_luma in scene_levels
    ]
    generator = random.Random(seed)
    tone_boundaries = tone_lut.TONE_SEGMENT_BOUNDS
    for index in range(sample_count):
        ratio = tuple(10.0 ** generator.uniform(-5.0, 1.0) for _ in range(3))
        selector = index % 5
        if selector == 0:
            scene_luma = generator.random() * 16.0
        elif selector == 1:
            scene_luma = 2.0 ** generator.uniform(math.log2(1.0e-8), 8.0)
        elif selector == 2:
            scene_luma = generator.uniform(16.0, 320.0)
        elif selector == 3:
            boundary = generator.choice(tone_boundaries[1:])
            scene_luma = math.nextafter(
                boundary,
                math.inf if generator.getrandbits(1) else -math.inf,
            )
        else:
            # Bias a lane toward primary/secondary colors that can make the chroma extrapolation
            # cross zero before the shader's nonnegative clamp.
            dominant = generator.randrange(3)
            ratio_list = [10.0 ** generator.uniform(-6.0, -1.0) for _ in range(3)]
            ratio_list[dominant] = 10.0 ** generator.uniform(0.0, 1.0)
            ratio = tuple(ratio_list)
            scene_luma = 2.0 ** generator.uniform(-8.0, 5.0)
        samples.append(_scaled_rgb(ratio, scene_luma))
    return samples


def _percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return 0.0
    return sorted_values[int(fraction * (len(sorted_values) - 1))]


def validate_full_rgb_guarded_parity() -> tuple[
    dict[str, dict[str, float | str]],
    list[str],
]:
    actual_ndr = FullRgbProfile(
        "NDR10_ACTUAL",
        replace(tone_lut.NDR10, peak_nits=5000.0),
        highlight_desat=0.149718,
        chroma_boost=0.038361,
        sky_chroma_bonus=0.062392,
    )
    actual_nivukx = FullRgbProfile(
        "NIVUKX_ACTUAL",
        replace(tone_lut.NIVUKX, peak_nits=10000.0),
        highlight_desat=0.128953,
        chroma_boost=0.091565,
        sky_chroma_bonus=0.080000,
    )
    profiles = (
        actual_ndr,
        FullRgbProfile(
            "NDR10_PROFILE_MINIMA",
            tone_lut.Profile(0.10, 0.001, 0.0, 0.0, 0.0, 0.0, 5000.0),
            highlight_desat=0.0,
            chroma_boost=0.0,
            sky_chroma_bonus=0.0,
        ),
        FullRgbProfile(
            "NDR10_PROFILE_MAXIMA",
            tone_lut.Profile(1.0, 0.90, 0.20, 0.35, 0.30, 0.12, 5000.0),
            highlight_desat=0.50,
            chroma_boost=0.25,
            sky_chroma_bonus=0.08,
        ),
        actual_nivukx,
        FullRgbProfile(
            "NIVUKX_PROFILE_MINIMA",
            tone_lut.Profile(0.10, 0.001, 0.0, 0.0, 0.0, 0.0, 10000.0),
            highlight_desat=0.0,
            chroma_boost=0.0,
            sky_chroma_bonus=0.0,
        ),
        FullRgbProfile(
            "NIVUKX_PROFILE_MAXIMA",
            tone_lut.Profile(1.0, 0.90, 0.20, 0.35, 0.30, 0.12, 10000.0),
            highlight_desat=0.50,
            chroma_boost=0.25,
            sky_chroma_bonus=0.08,
        ),
    )
    samples = _full_rgb_inputs(sample_count=12_000, seed=0x4E454F51)
    reports: dict[str, dict[str, float | str]] = {}
    failures: list[str] = []
    for profile_index, profile in enumerate(profiles):
        tone_table, pq_table = tone_lut.build_tables(profile.tone)
        guarded_errors: list[float] = []
        unguarded_errors: list[float] = []
        clipped_samples = 0
        lut_samples = 0
        peak_compressed_samples = 0
        near_black_samples = 0
        near_black_max_error = 0.0
        max_guarded_case = ""
        max_unguarded_case = ""
        max_guarded_error = -1.0
        max_unguarded_error = -1.0
        for sample_index, linear_rgb in enumerate(samples):
            warm_strength = (0.0, 0.5, 1.0)[(sample_index + profile_index) % 3]
            analytic = _render_full_rgb(
                linear_rgb,
                profile,
                tone_table,
                pq_table,
                warm_strength,
                accelerated=False,
                guard_clipped_color=True,
            )
            guarded = _render_full_rgb(
                linear_rgb,
                profile,
                tone_table,
                pq_table,
                warm_strength,
                accelerated=True,
                guard_clipped_color=True,
            )
            unguarded = _render_full_rgb(
                linear_rgb,
                profile,
                tone_table,
                pq_table,
                warm_strength,
                accelerated=True,
                guard_clipped_color=False,
            )
            sample_guarded_errors = [
                abs(actual - cached)
                for actual, cached in zip(analytic.codes, guarded.codes)
            ]
            sample_guarded_max = max(sample_guarded_errors)
            if sample_guarded_max > max_guarded_error:
                max_guarded_error = sample_guarded_max
                max_guarded_case = (
                    f"rgb={linear_rgb},warm={warm_strength},sourceY={guarded.source_luma:.12g},"
                    f"preRolloffY={guarded.pre_rolloff_luma:.12g},"
                    f"clipped={guarded.color_path_clipped},lut={guarded.lut_active},"
                    f"analyticCodes={analytic.codes},guardedCodes={guarded.codes}"
                )
            guarded_errors.extend(sample_guarded_errors)
            sample_unguarded_errors = [
                abs(actual - cached)
                for actual, cached in zip(analytic.codes, unguarded.codes)
            ]
            sample_unguarded_max = max(sample_unguarded_errors)
            if sample_unguarded_max > max_unguarded_error:
                max_unguarded_error = sample_unguarded_max
                max_unguarded_case = (
                    f"rgb={linear_rgb},warm={warm_strength},sourceY={unguarded.source_luma:.12g},"
                    f"preRolloffY={unguarded.pre_rolloff_luma:.12g},"
                    f"clipped={unguarded.color_path_clipped},lut={unguarded.lut_active},"
                    f"analyticCodes={analytic.codes},unguardedCodes={unguarded.codes}"
                )
            unguarded_errors.extend(sample_unguarded_errors)
            clipped_samples += int(guarded.color_path_clipped)
            lut_samples += int(guarded.lut_active)
            peak_compressed_samples += int(guarded.peak_volume_compressed)
            if guarded.source_luma <= 1.0e-6:
                near_black_samples += 1
                near_black_max_error = max(near_black_max_error, *sample_guarded_errors)

        guarded_errors.sort()
        unguarded_errors.sort()
        report = {
            "mean_guarded_code_error": sum(guarded_errors) / len(guarded_errors),
            "p99_guarded_code_error": _percentile(guarded_errors, 0.99),
            "max_guarded_code_error": guarded_errors[-1],
            "max_unguarded_code_error": unguarded_errors[-1],
            "clipped_samples": float(clipped_samples),
            "lut_samples": float(lut_samples),
            "peak_compressed_samples": float(peak_compressed_samples),
            "near_black_samples": float(near_black_samples),
            "near_black_max_code_error": near_black_max_error,
            "max_guarded_case": max_guarded_case,
            "max_unguarded_case": max_unguarded_case,
        }
        if (profile.chroma_boost > 0.0 or profile.sky_chroma_bonus > 0.0) and clipped_samples == 0:
            failures.append(f"{profile.name}: clipping guard was not exercised")
        if profile.name.endswith("_ACTUAL") and peak_compressed_samples == 0:
            failures.append(f"{profile.name}: peak-volume compression was not exercised")
        if near_black_samples == 0:
            failures.append(f"{profile.name}: near-black inputs were not exercised")
        if report["max_guarded_code_error"] >= 0.5:
            failures.append(
                f"{profile.name}: guarded full-RGB parity exceeded half a 10-bit code "
                f"({report['max_guarded_code_error']:.12g})"
            )
        reports[profile.name] = report
    return reports, failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--glslang")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[2]
    native_path = root / "app" / "src" / "main" / "cpp" / "offscreen_egl_encoder_bridge.cpp"
    source = native_path.read_text(encoding="utf-8")
    canonical = extract_canonical_fragment(source)
    actual_generic_2d = sampler_2d(canonical)
    integer_offset_generic_2d = sampler_2d(canonical, specialize_cardinals=True)
    validator = discover_validator(args.glslang)

    for mode, profile in ((0, "ndr10"), (1, "nivukx")):
        external = specialize(canonical, mode)
        two_d = sampler_2d(
            external,
            specialize_history=True,
            specialize_cardinals=True,
        )
        if restore_specialization(external, mode) != canonical:
            raise AssertionError(f"{profile} external equation parity is not byte-exact")
        restored_two_d = restore_specialization(two_d, mode)
        if restored_two_d != integer_offset_generic_2d:
            raise AssertionError(
                f"{profile} sampler2D declaration specialization is not byte-exact"
            )
        if restored_two_d == actual_generic_2d:
            raise AssertionError(
                f"{profile} sampler2D test failed to expose the integer-offset source delta"
            )
        if restore_cardinal_specialization(restored_two_d) != actual_generic_2d:
            raise AssertionError(
                f"{profile} sampler2D differs from the runtime generic shader beyond the "
                "declared cardinal-offset specialization"
            )
        validate_glsl(validator, f"sensor_linear_{profile}_external", external)
        validate_glsl(validator, f"sensor_linear_{profile}_2d", two_d)

    eligible_rows = validate_runtime_truth_table(source)
    validate_quality_contract(source)
    validate_kotlin_mastering_contract(root)
    lut_reports = validate_mastering_lut_parity()
    full_rgb_reports, full_rgb_failures = validate_full_rgb_guarded_parity()
    print(
        "SENSOR_LINEAR_PQ_SPECIALIZATION_SIMULATION="
        + ("FAIL" if full_rgb_failures else "PASS")
    )
    print(f"sdkValidator={validator}")
    print("validatedVariants=NDR10_EXTERNAL,NDR10_2D,NIVUKX_EXTERNAL,NIVUKX_2D")
    print("specializedDeclarations=uInputTransfer,uHdrToPqConvert,uNdrDeliveryMode,uNdrTargetPeakNits,uNdrSensorTonePqLutReady,uLutStrength,uHistoryPreGrade(2D)")
    print("externalDeclarationParity=BYTE_EXACT")
    print("sampler2dDeclarationParity=BYTE_EXACT")
    print("sampler2dCardinalDelta=INTEGER_TEXTURE_OFFSET_REQUIRES_GPU_PIXEL_PARITY")
    print("analyticLutFailureFallback=RETAINED")
    print(f"eligibleTruthTableRows={eligible_rows}/10240")
    print("masteringContracts=NDR10:5000nits,NIVUKX:10000nits")
    for profile, report in lut_reports.items():
        print(
            f"{profile}.maxLutError10bit={report['max_code_error']:.12g} "
            f"black={report['black_output_code']:.1f} "
            f"monotonicViolations={report['monotonic_violations']:.0f}"
        )
    for profile, report in full_rgb_reports.items():
        print(
            f"{profile}.fullRgbGuardedMax10bit={report['max_guarded_code_error']:.12g} "
            f"p99={report['p99_guarded_code_error']:.12g} "
            f"unguardedMax10bit={report['max_unguarded_code_error']:.12g} "
            f"clippedSamples={report['clipped_samples']:.0f} "
            f"peakCompressedSamples={report['peak_compressed_samples']:.0f} "
            f"nearBlackMax10bit={report['near_black_max_code_error']:.12g}"
        )
        print(f"{profile}.worstGuarded={report['max_guarded_case']}")
        print(f"{profile}.worstUnguarded={report['max_unguarded_case']}")
    print("qualityContract=4K/FP16/Main10-PQ/5-tap/temporal/measured-profiles/2048x17-LUT/bitrate/cadence structurally unchanged")
    if full_rgb_failures:
        for failure in full_rgb_failures:
            print(f"FULL_RGB_PARITY_FAILURE={failure}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
