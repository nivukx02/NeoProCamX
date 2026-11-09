#!/usr/bin/env python3
"""Fit the canonical NeoProCamX NDR10+ and NIVUKX Vision runtime profiles.

This is the calibration authority for the project-owned sensor-linear path:

    RAW sensor -> ACEScg FP16 -> NdrToneOperator -> SDR monitor / PQ recording

It intentionally does not model or use HLG, Dolby Vision, or a vendor look.  The
fit combines the packaged 48-patch colour target, deterministic tone/detail
objectives, and measured same-scene preview observations.  The resulting JSON
is checked against ``NdrToneOperator.kt`` by the JVM contract tests.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy.optimize import least_squares


ROOT = Path(__file__).resolve().parents[2]
REFERENCE = ROOT / "app/src/main/assets/calibration/ndr_nivukx_calibration_reference.json"
DEFAULT_OUTPUT = ROOT / "app/src/main/assets/calibration/ndr_nivukx_runtime_calibration.json"
KOTLIN_OPERATOR = ROOT / "core-math/src/main/kotlin/com/neoproc/neoproccamx/color/NdrToneOperator.kt"
KOTLIN_CORRECTION_POLICY = ROOT / (
    "app/src/main/java/com/neoproc/neoproccamx/rendering/"
    "NdrNivukxMeasuredCorrectionPolicy.kt"
)

CALIBRATION_VERSION = "NDR-NIVUKX-PY-CAL-v4"
EPS = 1.0e-9
MONITOR_DIFFUSE_LINEAR = 0.60382734  # inverse-sRGB(0.80)
SENSOR_DIFFUSE_SCENE = 0.180
BT2020_LUMA = np.array([0.2627, 0.6780, 0.0593], dtype=np.float64)
REC709_TO_REC2020 = np.array(
    [
        [0.627404, 0.329283, 0.043313],
        [0.069097, 0.919540, 0.011362],
        [0.016392, 0.088013, 0.895595],
    ],
    dtype=np.float64,
)


@dataclass(frozen=True)
class ToneParams:
    signal_gamma: float
    toe_width: float
    toe_amount: float
    shadow_midtone_lift: float
    midtone_contrast: float
    highlight_rolloff_bias: float


@dataclass(frozen=True)
class ColorParams:
    highlight_desat: float
    chroma_boost: float
    sky_chroma_bonus: float


@dataclass(frozen=True)
class DetailParams:
    local_detail_strength: float
    local_detail_lower_stops: float
    local_detail_upper_stops: float


@dataclass(frozen=True)
class RuntimeProfile:
    signal_gamma: float
    toe_width: float
    toe_amount: float
    peak_nits: float
    highlight_desat: float
    shadow_midtone_lift: float
    midtone_contrast: float
    chroma_boost: float
    sky_chroma_bonus: float
    local_detail_strength: float
    local_detail_lower_stops: float
    local_detail_upper_stops: float
    highlight_rolloff_bias: float


# Sensor-domain fixtures.  The dark/indoor observations are exact normalized
# RAW statistics captured after the strict RAW -> ACEScg route was restored on
# the Vivo I2405.  The middle-grey and diffuse-white anchors are exposure-linear
# calibration anchors, not measurements copied from a rendered UI screenshot.
# This distinction is critical: v3 inverted pre-restoration screenshots and
# incorrectly made RAW code 0.037 diffuse white, lifting the dark-room noise
# floor by about 4.86x before either NDR profile was applied.
SENSOR_EXPOSURE_FIXTURES = (
    {
        "source": "Vivo I2405 strict RAW dark-room capture 2026-08-10",
        "role": "dark_room_average",
        "raw_scene_luma": 0.002,
    },
    {
        "source": "Vivo I2405 strict RAW indoor capture 2026-08-09",
        "role": "indoor_scene_average",
        "raw_scene_luma": 0.037,
    },
    {
        "source": "linear exposure anchor",
        "role": "metered_middle_gray",
        "raw_scene_luma": SENSOR_DIFFUSE_SCENE * 0.18,
    },
    {
        "source": "linear exposure anchor",
        "role": "diffuse_white",
        "raw_scene_luma": SENSOR_DIFFUSE_SCENE,
    },
    {
        "source": "linear exposure anchor",
        "role": "specular_headroom",
        "raw_scene_luma": 0.60,
    },
)


SCENE_ANCHORS = np.array(
    [0.005, 0.010, 0.020, 0.050, 0.100, 0.180, 0.300, 0.500, 0.750, 1.000, 2.000, 4.000, 8.000],
    dtype=np.float64,
)

# Output targets are sRGB monitor code values.  Both profiles keep exact black
# and the common 0.80 diffuse-white anchor.  NIVUKX deliberately has a stronger
# shadow/midtone character, while NDR10+ remains the balanced reference.
TONE_TARGETS_SRGB = {
    "ndr10_plus": np.array(
        [0.073, 0.110, 0.157, 0.255, 0.357, 0.457, 0.547, 0.650, 0.738, 0.800, 0.914, 0.953, 0.970],
        dtype=np.float64,
    ),
    "nivukx_vision": np.array(
        [0.091, 0.130, 0.181, 0.284, 0.386, 0.488, 0.580, 0.677, 0.750, 0.800, 0.908, 0.947, 0.964],
        dtype=np.float64,
    ),
}

COLOR_RETENTION_TARGETS = {
    "ndr10_plus": {
        "SHADOW": 1.04,
        "SKIN": 1.00,
        "SKY": 1.10,
        "FOLIAGE": 1.08,
        "SATURATED": 1.06,
        "HIGHLIGHT": 0.88,
        "TEXTURE": 1.02,
    },
    "nivukx_vision": {
        "SHADOW": 1.08,
        "SKIN": 1.02,
        "SKY": 1.18,
        "FOLIAGE": 1.14,
        "SATURATED": 1.12,
        "HIGHLIGHT": 0.92,
        "TEXTURE": 1.05,
    },
}

ROLE_SCENE_LUMA = {
    "NEUTRAL": 0.32,
    "SHADOW": 0.08,
    "HIGHLIGHT": 0.92,
    "SKIN": 0.45,
    "SKY": 0.55,
    "FOLIAGE": 0.35,
    "TEXTURE": 0.40,
    "SATURATED": 0.45,
}

DETAIL_TARGETS = {
    "ndr10_plus": DetailParams(0.40, -0.060, 0.085),
    "nivukx_vision": DetailParams(0.60, -0.085, 0.125),
}

PEAK_NITS = {"ndr10_plus": 5000.0, "nivukx_vision": 10000.0}

def clamp(x, lo=0.0, hi=1.0):
    return np.clip(np.asarray(x, dtype=np.float64), lo, hi)


def smoothstep(edge0: float, edge1: float, x):
    t = clamp((np.asarray(x, dtype=np.float64) - edge0) / max(edge1 - edge0, EPS))
    return t * t * (3.0 - 2.0 * t)


def srgb_to_linear(value):
    x = clamp(value)
    return np.where(x <= 0.04045, x / 12.92, np.power((x + 0.055) / 1.055, 2.4))


def linear_to_srgb(value):
    x = clamp(value)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(x, 1.0 / 2.4) - 0.055)


def luma2020(rgb):
    return np.tensordot(np.asarray(rgb, dtype=np.float64), BT2020_LUMA, axes=([-1], [0]))


def signal_shape(scene_norm, p: ToneParams):
    x = np.asarray(scene_norm, dtype=np.float64)
    below = np.power(np.maximum(x, 0.0), p.signal_gamma)
    below += smoothstep(0.0, p.toe_width, x) * p.toe_amount * (1.0 - x)
    above = 1.0 + (p.signal_gamma - p.toe_amount) * (x - 1.0)
    return np.where(x <= 0.0, 0.0, np.where(x <= 1.0, below, above))


def black_preserving_midtone_lift(signal, p: ToneParams):
    x = np.maximum(np.asarray(signal, dtype=np.float64), 0.0)
    window = smoothstep(0.025, 0.20, x) * (1.0 - smoothstep(0.78, 1.20, x))
    lifted = x + p.shadow_midtone_lift * np.sqrt(x) * (1.0 - x) * window
    return np.where((x > 0.0) & (x < 1.0), lifted, x)


def profile_midtone_contrast(signal, p: ToneParams):
    x = np.maximum(np.asarray(signal, dtype=np.float64), 0.0)
    window = smoothstep(0.055, 0.24, x) * (1.0 - smoothstep(0.76, 1.0, x))
    signed = (x - 0.50) * 4.0 * x * (1.0 - x)
    contrasted = np.maximum(x + p.midtone_contrast * signed * window, 0.0)
    return np.where((x > 0.0) & (x < 1.0), contrasted, x)


def apply_highlight_rolloff(signal, p: ToneParams):
    x = np.maximum(np.asarray(signal, dtype=np.float64), 0.0)
    excess = np.maximum(x - 1.0, 0.0)
    rolled = 1.0 + excess / (1.0 + 4.0 * p.highlight_rolloff_bias * excess)
    return np.where(x > 1.0, rolled, x)


def tone_signal(scene_norm, p: ToneParams):
    shaped = signal_shape(scene_norm, p)
    shaped = black_preserving_midtone_lift(shaped, p)
    shaped = profile_midtone_contrast(shaped, p)
    return apply_highlight_rolloff(shaped, p)


def monitor_linear_from_signal(signal):
    x = np.maximum(np.asarray(signal, dtype=np.float64), 0.0)
    headroom = 1.0 - MONITOR_DIFFUSE_LINEAR
    shoulder_length = headroom / MONITOR_DIFFUSE_LINEAR
    excess = np.maximum(x - 1.0, 0.0)
    above = MONITOR_DIFFUSE_LINEAR + headroom * excess / np.maximum(excess + shoulder_length, EPS)
    return np.where(x <= 1.0, x * MONITOR_DIFFUSE_LINEAR, np.minimum(above, 1.0))


def monitor_srgb(scene_norm, p: ToneParams):
    return linear_to_srgb(monitor_linear_from_signal(tone_signal(scene_norm, p)))


def tone_from_vector(vector) -> ToneParams:
    return ToneParams(*[float(v) for v in vector])


def fit_tone(mode: str) -> ToneParams:
    target = TONE_TARGETS_SRGB[mode]
    initial = (
        np.array([0.86, 0.24, 0.024, 0.17, 0.10, 0.035])
        if mode == "ndr10_plus"
        else np.array([0.80, 0.28, 0.032, 0.23, 0.18, 0.050])
    )
    lower = np.array([0.62, 0.08, 0.002, 0.02, 0.02, 0.005])
    upper = np.array([0.98, 0.48, 0.060, 0.32, 0.28, 0.090])

    def residual(vector):
        p = tone_from_vector(vector)
        rendered = monitor_srgb(SCENE_ANCHORS, p)
        # Shadows, diffuse-white neighbourhood, and highlights receive equal
        # perceptual authority instead of letting the numerous midtones dominate.
        weights = np.array([1.2, 1.2, 1.2, 1.1, 1.0, 1.0, 1.0, 1.0, 1.1, 2.0, 1.2, 1.2, 1.1])
        return (rendered - target) * weights

    solved = least_squares(residual, initial, bounds=(lower, upper), xtol=1e-13, ftol=1e-13, gtol=1e-13)
    if not solved.success:
        raise RuntimeError(f"Tone fit failed for {mode}: {solved.message}")
    return tone_from_vector(solved.x)


def canonical_role(role: str) -> str:
    if role.startswith("SATURATED_"):
        return "SATURATED"
    return role


def load_color_samples(reference_path: Path):
    reference = json.loads(reference_path.read_text(encoding="utf-8"))
    samples = []
    for patch in reference["patches"]:
        role = canonical_role(patch["role"])
        srgb = np.asarray(patch["reference_srgb"], dtype=np.float64)
        rgb2020 = srgb_to_linear(srgb) @ REC709_TO_REC2020.T
        source_y = max(float(luma2020(rgb2020)), EPS)
        target_y = ROLE_SCENE_LUMA.get(role, 0.40)
        scene_rgb = np.maximum(rgb2020 * (target_y / source_y), 0.0)
        samples.append((patch["id"], role, scene_rgb))
    return reference, samples


def color_retention(scene_rgb, tone: ToneParams, color: ColorParams):
    source = np.maximum(np.asarray(scene_rgb, dtype=np.float64), 0.0)
    y = max(float(luma2020(source)), EPS)
    mapped_y = float(profile_midtone_contrast(black_preserving_midtone_lift(signal_shape(y, tone), tone), tone))
    base = source * (mapped_y / y)
    peak = float(np.max(base))
    floor = float(np.min(base))
    relative_chroma = (peak - floor) / max(mapped_y, 0.04)
    confidence = float(smoothstep(0.035, 0.22, relative_chroma))
    safe_green = max(float(source[1]), EPS)
    rg = float(source[0]) / safe_green
    bg = float(source[2]) / safe_green
    skin = float(smoothstep(1.02, 1.16, rg)) * (1.0 - float(smoothstep(1.58, 2.10, rg))) * (
        1.0 - float(smoothstep(0.98, 1.18, bg))
    )
    sky = float(smoothstep(1.03, 1.22, bg)) * (1.0 - float(smoothstep(1.00, 1.22, rg)))
    tonal = float(smoothstep(0.035, 0.16, mapped_y)) * (1.0 - float(smoothstep(0.88, 1.65, mapped_y)))
    skin_protection = 1.0 + (0.45 - 1.0) * skin
    gain = 1.0 + (color.chroma_boost + color.sky_chroma_bonus * sky) * confidence * tonal * skin_protection
    after_boost = mapped_y + (base - mapped_y) * gain
    desat = color.highlight_desat * float(smoothstep(0.55, 1.0, mapped_y))
    output = mapped_y + (after_boost - mapped_y) * (1.0 - desat)
    input_chroma = float(np.linalg.norm(base - mapped_y))
    output_chroma = float(np.linalg.norm(output - mapped_y))
    return output_chroma / max(input_chroma, EPS)


def fit_color(mode: str, tone: ToneParams, samples) -> ColorParams:
    initial = np.array([0.15, 0.085, 0.025]) if mode == "ndr10_plus" else np.array([0.11, 0.18, 0.050])
    lower = np.array([0.06, 0.02, 0.0])
    upper = np.array([0.24, 0.24, 0.08])
    targets = COLOR_RETENTION_TARGETS[mode]

    def residual(vector):
        p = ColorParams(*[float(v) for v in vector])
        rows = []
        for _, role, scene_rgb in samples:
            if role not in targets:
                continue
            rendered = color_retention(scene_rgb, tone, p)
            role_weight = 1.5 if role in {"SKIN", "SKY", "HIGHLIGHT"} else 1.0
            rows.append((rendered - targets[role]) * role_weight)
        return np.asarray(rows, dtype=np.float64)

    solved = least_squares(residual, initial, bounds=(lower, upper), xtol=1e-13, ftol=1e-13, gtol=1e-13)
    if not solved.success:
        raise RuntimeError(f"Colour fit failed for {mode}: {solved.message}")
    return ColorParams(*[float(v) for v in solved.x])


def calibrate_sensor_diffuse_scene(calibrated_ndr: ToneParams):
    evidence = []
    for observation in SENSOR_EXPOSURE_FIXTURES:
        scene_norm = observation["raw_scene_luma"] / SENSOR_DIFFUSE_SCENE
        monitor_code = float(monitor_srgb(scene_norm, calibrated_ndr))
        evidence.append(
            {
                **observation,
                "scene_normalized_luma": round(scene_norm, 9),
                "ndr10_monitor_srgb": round(monitor_code, 9),
            }
        )

    by_role = {row["role"]: row["ndr10_monitor_srgb"] for row in evidence}
    if by_role["dark_room_average"] >= 0.20:
        raise RuntimeError("Dark-room RAW noise floor is still being promoted into a visible midtone")
    if abs(by_role["diffuse_white"] - 0.80) > 1.0e-6:
        raise RuntimeError("Sensor diffuse white no longer maps to the calibrated 0.80 monitor anchor")
    if not (
        by_role["dark_room_average"]
        < by_role["metered_middle_gray"]
        < by_role["diffuse_white"]
        < by_role["specular_headroom"]
    ):
        raise RuntimeError("Sensor-domain exposure anchors are not monotonic")
    return SENSOR_DIFFUSE_SCENE, evidence


def rounded_profile(tone: ToneParams, color: ColorParams, detail: DetailParams, peak_nits: float) -> RuntimeProfile:
    # Six decimal places is well below FP16/10-bit output precision while still
    # preserving deterministic CPU/GPU parity.
    r = lambda value: round(float(value), 6)
    return RuntimeProfile(
        signal_gamma=r(tone.signal_gamma),
        toe_width=r(tone.toe_width),
        toe_amount=r(tone.toe_amount),
        peak_nits=float(peak_nits),
        highlight_desat=r(color.highlight_desat),
        shadow_midtone_lift=r(tone.shadow_midtone_lift),
        midtone_contrast=r(tone.midtone_contrast),
        chroma_boost=r(color.chroma_boost),
        sky_chroma_bonus=r(color.sky_chroma_bonus),
        local_detail_strength=r(detail.local_detail_strength),
        local_detail_lower_stops=r(detail.local_detail_lower_stops),
        local_detail_upper_stops=r(detail.local_detail_upper_stops),
        highlight_rolloff_bias=r(tone.highlight_rolloff_bias),
    )


def runtime_tone(profile: RuntimeProfile) -> ToneParams:
    return ToneParams(
        profile.signal_gamma,
        profile.toe_width,
        profile.toe_amount,
        profile.shadow_midtone_lift,
        profile.midtone_contrast,
        profile.highlight_rolloff_bias,
    )


def validate_profiles(profiles, samples):
    diagnostics = {}
    dense_scene = np.concatenate(([0.0], np.geomspace(1.0e-5, 32.0, 8192)))
    for mode, profile in profiles.items():
        tone = runtime_tone(profile)
        signal = tone_signal(dense_scene, tone)
        if np.any(np.diff(signal) < -1.0e-9):
            raise RuntimeError(f"{mode} tone curve is non-monotonic")
        if abs(float(signal[0])) > 1.0e-12:
            raise RuntimeError(f"{mode} no longer anchors exact black")
        if abs(float(tone_signal(1.0, tone)) - 1.0) > 1.0e-9:
            raise RuntimeError(f"{mode} no longer anchors diffuse white")

        color = ColorParams(profile.highlight_desat, profile.chroma_boost, profile.sky_chroma_bonus)
        by_role = {}
        for _, role, rgb in samples:
            by_role.setdefault(role, []).append(color_retention(rgb, tone, color))
        diagnostics[mode] = {
            "tone_rmse_srgb": round(
                float(np.sqrt(np.mean(np.square(monitor_srgb(SCENE_ANCHORS, tone) - TONE_TARGETS_SRGB[mode])))),
                8,
            ),
            "black_output": float(tone_signal(0.0, tone)),
            "diffuse_white_signal": float(tone_signal(1.0, tone)),
            "minimum_dense_slope": round(float(np.min(np.diff(signal))), 12),
            "role_mean_chroma_retention": {
                role: round(float(np.mean(values)), 6) for role, values in sorted(by_role.items())
            },
        }

    ndr = profiles["ndr10_plus"]
    niv = profiles["nivukx_vision"]
    ndr_mid = monitor_srgb(SCENE_ANCHORS[:9], runtime_tone(ndr))
    niv_mid = monitor_srgb(SCENE_ANCHORS[:9], runtime_tone(niv))
    tone_separation = float(np.mean(np.abs(niv_mid - ndr_mid)))
    if tone_separation < 0.012:
        raise RuntimeError(f"Mode tone separation is too weak: {tone_separation}")
    if not (niv.chroma_boost > ndr.chroma_boost and niv.sky_chroma_bonus > ndr.sky_chroma_bonus):
        raise RuntimeError("NIVUKX colour character is not stronger than NDR10+")
    if not (niv.local_detail_strength > ndr.local_detail_strength and niv.peak_nits > ndr.peak_nits):
        raise RuntimeError("NIVUKX detail/peak character is not distinct")
    diagnostics["mode_separation"] = {
        "mean_shadow_midtone_srgb_delta": round(tone_separation, 8),
        "chroma_boost_delta": round(niv.chroma_boost - ndr.chroma_boost, 6),
        "sky_chroma_bonus_delta": round(niv.sky_chroma_bonus - ndr.sky_chroma_bonus, 6),
        "local_detail_strength_delta": round(niv.local_detail_strength - ndr.local_detail_strength, 6),
        "peak_nits_delta": round(niv.peak_nits - ndr.peak_nits, 3),
    }
    return diagnostics


def kotlin_number(text: str, function_name: str, field_name: str) -> float:
    function_match = re.search(
        rf"fun\s+{re.escape(function_name)}\([^)]*\)\s*:\s*Params\s*=\s*Params\((.*?)\n\s*\)",
        text,
        flags=re.DOTALL,
    )
    if not function_match:
        raise RuntimeError(f"Cannot find Kotlin profile function {function_name}")
    value_match = re.search(rf"{re.escape(field_name)}\s*=\s*(-?[0-9]+(?:\.[0-9]+)?)", function_match.group(1))
    if not value_match:
        raise RuntimeError(f"Cannot find Kotlin field {field_name} in {function_name}")
    return float(value_match.group(1))


def kotlin_default_peak(text: str, function_name: str) -> float:
    value_match = re.search(
        rf"fun\s+{re.escape(function_name)}\(peakNits:\s*Double\s*=\s*"
        rf"([A-Z][A-Z0-9_]*|-?[0-9][0-9_]*(?:\.[0-9_]+)?)\)",
        text,
    )
    if not value_match:
        raise RuntimeError(f"Cannot find Kotlin default peak for {function_name}")
    token = value_match.group(1)
    if token[0].isdigit() or token[0] == "-":
        return float(token.replace("_", ""))
    constant_match = re.search(
        rf"const\s+val\s+{re.escape(token)}\s*=\s*(-?[0-9][0-9_]*(?:\.[0-9_]+)?)",
        text,
    )
    if not constant_match:
        raise RuntimeError(f"Cannot resolve Kotlin peak constant {token}")
    return float(constant_match.group(1).replace("_", ""))


def kotlin_policy_number(text: str, profile_name: str, field_name: str) -> float:
    profile_match = re.search(
        rf"private\s+val\s+{re.escape(profile_name)}\s*=\s*"
        rf"NdrNivukxMeasuredCorrection\((.*?)\n\s*\)",
        text,
        flags=re.DOTALL,
    )
    if not profile_match:
        raise RuntimeError(f"Cannot find measured-correction profile {profile_name}")
    value_match = re.search(
        rf"{re.escape(field_name)}\s*=\s*(-?[0-9]+(?:\.[0-9]+)?)f?",
        profile_match.group(1),
    )
    if not value_match:
        raise RuntimeError(f"Cannot find Kotlin policy field {field_name} in {profile_name}")
    return float(value_match.group(1))


def check_kotlin(output, kotlin_path: Path):
    text = kotlin_path.read_text(encoding="utf-8")
    version_match = re.search(r'CALIBRATION_VERSION\s*=\s*"([^"]+)"', text)
    if not version_match or version_match.group(1) != output["calibration_version"]:
        raise RuntimeError("Kotlin calibration version does not match generated JSON")
    sensor_match = re.search(r"SENSOR_DIFFUSE_SCENE\s*=\s*([0-9]+(?:\.[0-9]+)?)", text)
    if not sensor_match or abs(float(sensor_match.group(1)) - output["sensor_diffuse_scene"]) > 5.0e-7:
        raise RuntimeError("Kotlin sensor diffuse reference does not match generated JSON")
    mapping = {
        "signal_gamma": "signalGamma",
        "toe_width": "toeWidth",
        "toe_amount": "toeAmount",
        "highlight_desat": "highlightDesat",
        "shadow_midtone_lift": "shadowMidtoneLift",
        "midtone_contrast": "midtoneContrast",
        "chroma_boost": "chromaBoost",
        "sky_chroma_bonus": "skyChromaBonus",
        "local_detail_strength": "localDetailStrength",
        "local_detail_lower_stops": "localDetailLowerStops",
        "local_detail_upper_stops": "localDetailUpperStops",
    }
    mode_mapping = (
        ("ndr10_plus", "ndr10Plus", "NDR10"),
        ("nivukx_vision", "nivukxVision", "NIVUKX"),
    )
    correction_text = KOTLIN_CORRECTION_POLICY.read_text(encoding="utf-8")
    for mode, function_name, correction_name in mode_mapping:
        for json_name, kotlin_name in mapping.items():
            actual = kotlin_number(text, function_name, kotlin_name)
            expected = output["profiles"][mode][json_name]
            if abs(actual - expected) > 5.0e-7:
                raise RuntimeError(f"Kotlin drift: {mode}.{kotlin_name}={actual}, generated={expected}")
        actual_peak = kotlin_default_peak(text, function_name)
        expected_peak = output["profiles"][mode]["peak_nits"]
        if abs(actual_peak - expected_peak) > 5.0e-7:
            raise RuntimeError(
                f"Kotlin drift: {mode}.peakNits={actual_peak}, generated={expected_peak}"
            )
        actual_rolloff = kotlin_policy_number(
            correction_text,
            correction_name,
            "highlightRolloffBias",
        )
        expected_rolloff = output["profiles"][mode]["highlight_rolloff_bias"]
        if abs(actual_rolloff - expected_rolloff) > 5.0e-7:
            raise RuntimeError(
                f"Kotlin drift: {mode}.highlightRolloffBias={actual_rolloff}, "
                f"generated={expected_rolloff}"
            )


def calibrate(reference_path: Path):
    reference, samples = load_color_samples(reference_path)
    fitted_tones = {mode: fit_tone(mode) for mode in ("ndr10_plus", "nivukx_vision")}
    fitted_colors = {
        mode: fit_color(mode, fitted_tones[mode], samples) for mode in ("ndr10_plus", "nivukx_vision")
    }
    profiles = {
        mode: rounded_profile(fitted_tones[mode], fitted_colors[mode], DETAIL_TARGETS[mode], PEAK_NITS[mode])
        for mode in ("ndr10_plus", "nivukx_vision")
    }
    sensor_diffuse, exposure_evidence = calibrate_sensor_diffuse_scene(runtime_tone(profiles["ndr10_plus"]))
    diagnostics = validate_profiles(profiles, samples)
    return {
        "calibration_version": CALIBRATION_VERSION,
        "generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "technology": "NeoProCamX NDR10+ / NIVUKX Vision",
        "scope": "project-owned sensor-linear ACEScg runtime profiles",
        "input_transfer": "LINEAR_ACESCG_FP16_OR_NEO_SCENE_LOG10",
        "excluded_technology": ["HLG input", "Dolby Vision", "vendor look or vendor tone curve"],
        "reference_version": reference["version"],
        "reference_patch_count": len(reference["patches"]),
        "sensor_diffuse_scene": sensor_diffuse,
        "profiles": {mode: asdict(profile) for mode, profile in profiles.items()},
        "sensor_exposure_evidence": exposure_evidence,
        "diagnostics": diagnostics,
        "quality_guards": {
            "resolution_changed": False,
            "fps_changed": False,
            "bitrate_changed": False,
            "bit_depth_changed": False,
            "features_removed": False,
            "tone_curve_monotonic": True,
            "exact_black_anchor": True,
            "exact_diffuse_white_anchor": True,
            "profiles_are_distinct": True,
            "sensor_reference_is_linear_not_screen_fitted": True,
            "dark_room_noise_floor_preserved": True,
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, default=REFERENCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check-kotlin", action="store_true")
    args = parser.parse_args()

    result = calibrate(args.reference)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.check_kotlin:
        check_kotlin(result, KOTLIN_OPERATOR)
    print(f"Wrote {args.output}")
    print(f"sensor_diffuse_scene={result['sensor_diffuse_scene']:.3f}")
    for mode, profile in result["profiles"].items():
        print(
            f"{mode}: gamma={profile['signal_gamma']:.6f} toe={profile['toe_width']:.6f}/"
            f"{profile['toe_amount']:.6f} desat={profile['highlight_desat']:.6f} "
            f"chroma={profile['chroma_boost']:.6f} detail={profile['local_detail_strength']:.3f}"
        )
    print(json.dumps(result["diagnostics"]["mode_separation"], indent=2))


if __name__ == "__main__":
    main()
