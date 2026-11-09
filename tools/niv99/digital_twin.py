"""Deterministic, dependency-free NIV99 quality and algorithm reference.

The model is deliberately small enough for host CI while retaining the production invariants that
matter: linear-light statistics, heteroscedastic noise, robust illuminant fusion, local mixed-light
rendering, motion/occlusion rejection, conservative luma filtering, stronger chroma filtering,
bounded reconstruction, and quantitative non-downgrade metrics.

It is not a substitute for executing SPIR-V or for physical chart captures. Results produced here
must therefore be labelled simulation evidence rather than device evidence.
"""

from __future__ import annotations

import argparse
import json
import math
import random
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Optional, Sequence, Tuple


Rgb = Tuple[float, float, float]
LUMA = (0.2126, 0.7152, 0.0722)
EPSILON = 1.0e-12


def clamp(value: float, lower: float = 0.0, upper: float = 1.0) -> float:
    return min(max(value, lower), upper)


def smoothstep(lower: float, upper: float, value: float) -> float:
    if not math.isfinite(value) or upper <= lower:
        return 0.0
    position = clamp((value - lower) / (upper - lower))
    return position * position * (3.0 - 2.0 * position)


def luminance(rgb: Rgb) -> float:
    return rgb[0] * LUMA[0] + rgb[1] * LUMA[1] + rgb[2] * LUMA[2]


def add_rgb(left: Rgb, right: Rgb) -> Rgb:
    return left[0] + right[0], left[1] + right[1], left[2] + right[2]


def scale_rgb(value: Rgb, scale: float) -> Rgb:
    return value[0] * scale, value[1] * scale, value[2] * scale


def mix_rgb(left: Rgb, right: Rgb, amount: float) -> Rgb:
    weight = clamp(amount)
    return tuple(left[index] + (right[index] - left[index]) * weight for index in range(3))  # type: ignore[return-value]


@dataclass
class LinearImage:
    width: int
    height: int
    data: List[float]

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError("image dimensions must be positive")
        if len(self.data) != self.width * self.height * 3:
            raise ValueError("RGB image storage has the wrong size")
        if not all(math.isfinite(value) for value in self.data):
            raise ValueError("image contains non-finite samples")

    @classmethod
    def empty(cls, width: int, height: int) -> "LinearImage":
        return cls(width, height, [0.0] * (width * height * 3))

    def copy(self) -> "LinearImage":
        return LinearImage(self.width, self.height, self.data.copy())

    def index(self, x: int, y: int) -> int:
        bounded_x = min(max(x, 0), self.width - 1)
        bounded_y = min(max(y, 0), self.height - 1)
        return (bounded_y * self.width + bounded_x) * 3

    def get(self, x: int, y: int) -> Rgb:
        index = self.index(x, y)
        return self.data[index], self.data[index + 1], self.data[index + 2]

    def set(self, x: int, y: int, value: Rgb) -> None:
        if x < 0 or x >= self.width or y < 0 or y >= self.height:
            raise IndexError("pixel coordinate outside image")
        index = (y * self.width + x) * 3
        self.data[index] = value[0]
        self.data[index + 1] = value[1]
        self.data[index + 2] = value[2]

    def bilinear(self, x: float, y: float) -> Rgb:
        bounded_x = clamp(x, 0.0, float(self.width - 1))
        bounded_y = clamp(y, 0.0, float(self.height - 1))
        x0 = int(math.floor(bounded_x))
        y0 = int(math.floor(bounded_y))
        x1 = min(x0 + 1, self.width - 1)
        y1 = min(y0 + 1, self.height - 1)
        tx = bounded_x - x0
        ty = bounded_y - y0
        upper = mix_rgb(self.get(x0, y0), self.get(x1, y0), tx)
        lower = mix_rgb(self.get(x0, y1), self.get(x1, y1), tx)
        return mix_rgb(upper, lower, ty)

    def checksum(self) -> str:
        # Stable non-cryptographic identity used only to prove the simulated RAW source was untouched.
        accumulator = 0xCBF29CE484222325
        for value in self.data:
            quantized = int(round(clamp(value) * 65535.0))
            accumulator ^= quantized & 0xFF
            accumulator = (accumulator * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
            accumulator ^= (quantized >> 8) & 0xFF
            accumulator = (accumulator * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
        return f"{accumulator:016x}"


@dataclass(frozen=True)
class NoiseModel:
    shot: Rgb
    read: Rgb

    def variance(self, channel: int, signal: float) -> float:
        return max(self.shot[channel] * max(signal, 0.0) + self.read[channel], EPSILON)

    def sigma(self, channel: int, signal: float) -> float:
        return math.sqrt(self.variance(channel, signal))


@dataclass(frozen=True)
class TileStatistics:
    mean: Rgb
    variance: Rgb
    gradient: Rgb
    reliability: float
    clipped_fraction: float
    shadow_fraction: float
    red_over_green: float
    blue_over_green: float
    neutral_confidence: float
    samples: int


@dataclass(frozen=True)
class IlluminantEstimate:
    red_response: float
    blue_response: float
    red_gain: float
    green_gain: float
    blue_gain: float
    confidence: float
    accepted_candidates: int
    candidate_count: int


@dataclass(frozen=True)
class WhiteBalanceIntent:
    manual_gains: Optional[Rgb] = None
    locked_gains: Optional[Rgb] = None


@dataclass(frozen=True)
class WhiteBalanceResult:
    gains: Rgb
    authority: str
    confidence: float


@dataclass(frozen=True)
class LocalIlluminantField:
    columns: int
    rows: int
    gains: Tuple[Rgb, ...]
    confidence: Tuple[float, ...]


@dataclass(frozen=True)
class TileControl:
    raw_strength: float
    luma_strength: float
    chroma_strength: float
    temporal_strength: float
    edge_protection: float
    texture_protection: float
    skin_protection: float
    sharpening: float
    local_gains: Rgb = (1.0, 1.0, 1.0)
    local_confidence: float = 0.0


@dataclass(frozen=True)
class QualityMetrics:
    psnr_db: float
    ssim: float
    luma_snr_improvement_db: float
    chroma_snr_improvement_db: float
    gradient_retention: float
    clipping_increase: float
    edge_overshoot: float
    tile_boundary_discontinuity: float


@dataclass(frozen=True)
class Scenario:
    name: str
    illuminant_left: Rgb
    illuminant_right: Rgb
    iso: int
    motion_x: float = 0.0
    scene_cut: bool = False


def make_reference_scene(width: int = 64, height: int = 48) -> LinearImage:
    image = LinearImage.empty(width, height)
    for y in range(height):
        for x in range(width):
            normalized_x = x / max(width - 1, 1)
            normalized_y = y / max(height - 1, 1)
            base = 0.025 + 0.42 * normalized_y
            rgb: Rgb = (base, base, base)
            if width // 16 <= x < width * 5 // 16 and height // 8 <= y < height * 3 // 8:
                patch = 0.12 + 0.58 * ((x - width // 16) / max(width // 4, 1))
                rgb = (patch, patch, patch)
            elif width * 9 // 16 <= x < width * 15 // 16 and height // 8 <= y < height * 3 // 8:
                skin_texture = 0.018 * math.sin(x * 1.7) * math.cos(y * 1.3)
                rgb = (0.48 + skin_texture, 0.27 + skin_texture, 0.18 + skin_texture * 0.7)
            elif y >= height // 2 and x < width // 3:
                detail = 0.045 if ((x // 2 + y // 2) & 1) == 0 else -0.045
                rgb = (0.10 + detail * 0.5, 0.31 + detail, 0.08 + detail * 0.4)
            elif y >= height // 2 and width // 3 <= x < width * 2 // 3:
                detail = 0.055 * math.sin(x * math.pi * 0.72)
                rgb = (0.24 + detail, 0.24 + detail, 0.24 + detail)
            elif y >= height // 2:
                edge = 0.74 if x >= width * 5 // 6 else 0.08
                rgb = (edge, edge, edge)
            image.set(x, y, tuple(clamp(value) for value in rgb))
    return image


def apply_illuminant(
    reference: LinearImage,
    left_response: Rgb,
    right_response: Optional[Rgb] = None,
) -> LinearImage:
    right = right_response or left_response
    output = LinearImage.empty(reference.width, reference.height)
    for y in range(reference.height):
        for x in range(reference.width):
            blend = smoothstep(0.35, 0.65, x / max(reference.width - 1, 1))
            response = mix_rgb(left_response, right, blend)
            source = reference.get(x, y)
            output.set(x, y, tuple(clamp(source[c] * response[c]) for c in range(3)))
    return output


def shift_image(image: LinearImage, displacement_x: float, displacement_y: float) -> LinearImage:
    """Translate content so sampling output at p + displacement reconstructs input at p."""
    output = LinearImage.empty(image.width, image.height)
    for y in range(image.height):
        for x in range(image.width):
            output.set(x, y, image.bilinear(x - displacement_x, y - displacement_y))
    return output


def add_heteroscedastic_noise(image: LinearImage, model: NoiseModel, seed: int) -> LinearImage:
    random_source = random.Random(seed)
    output = LinearImage.empty(image.width, image.height)
    for y in range(image.height):
        for x in range(image.width):
            source = image.get(x, y)
            noisy = tuple(
                clamp(source[channel] + random_source.gauss(0.0, model.sigma(channel, source[channel])))
                for channel in range(3)
            )
            output.set(x, y, noisy)
    return output


def tile_statistics(
    image: LinearImage,
    noise: NoiseModel,
    tile_width: int = 8,
    tile_height: int = 8,
) -> Tuple[int, int, List[TileStatistics]]:
    if tile_width <= 0 or tile_height <= 0:
        raise ValueError("tile dimensions must be positive")
    columns = (image.width + tile_width - 1) // tile_width
    rows = (image.height + tile_height - 1) // tile_height
    output: List[TileStatistics] = []
    for tile_y in range(rows):
        for tile_x in range(columns):
            begin_x = tile_x * tile_width
            begin_y = tile_y * tile_height
            end_x = min(begin_x + tile_width, image.width)
            end_y = min(begin_y + tile_height, image.height)
            sums = [0.0, 0.0, 0.0]
            squares = [0.0, 0.0, 0.0]
            gradients = [0.0, 0.0, 0.0]
            weights = [0.0, 0.0, 0.0]
            reliability_sum = 0.0
            clipped = 0
            shadows = 0
            samples = 0
            for y in range(begin_y, end_y):
                for x in range(begin_x, end_x):
                    pixel = image.get(x, y)
                    pixel_reliability = 0.0
                    for channel in range(3):
                        signal = pixel[channel]
                        sigma = noise.sigma(channel, signal)
                        shadow_gate = smoothstep(0.0075, 0.015, signal)
                        highlight_gate = 1.0 - smoothstep(0.98, 1.0, signal)
                        noise_reliability = signal / max(signal + 4.0 * sigma, 1.0e-7)
                        weight = shadow_gate * highlight_gate * noise_reliability
                        sums[channel] += signal * weight
                        squares[channel] += signal * signal * weight
                        weights[channel] += weight
                        gradients[channel] += 0.25 * (
                            abs(image.get(x + 1, y)[channel] - image.get(x - 1, y)[channel]) +
                            abs(image.get(x, y + 1)[channel] - image.get(x, y - 1)[channel])
                        )
                        pixel_reliability += noise_reliability / 3.0
                    reliability_sum += pixel_reliability
                    clipped += int(max(pixel) >= 0.98)
                    shadows += int(luminance(pixel) <= 0.015)
                    samples += 1
            mean = tuple(sums[c] / max(weights[c], 1.0e-9) for c in range(3))
            variance = tuple(max(squares[c] / max(weights[c], 1.0e-9) - mean[c] ** 2, 0.0) for c in range(3))
            gradient = tuple(gradients[c] / max(samples, 1) for c in range(3))
            red_green = mean[0] / max(mean[1], 1.0e-7)
            blue_green = mean[2] / max(mean[1], 1.0e-7)
            chroma_distance = max(abs(math.log(max(red_green, 1.0e-7))), abs(math.log(max(blue_green, 1.0e-7))))
            coverage = min(weights) / max(samples / 3.0, 1.0)
            reliability = clamp(reliability_sum / max(samples, 1) * smoothstep(0.05, 0.5, coverage))
            output.append(
                TileStatistics(
                    mean=mean,
                    variance=variance,
                    gradient=gradient,
                    reliability=reliability,
                    clipped_fraction=clipped / max(samples, 1),
                    shadow_fraction=shadows / max(samples, 1),
                    red_over_green=red_green,
                    blue_over_green=blue_green,
                    neutral_confidence=math.exp(-8.0 * chroma_distance) * reliability,
                    samples=samples,
                )
            )
    return columns, rows, output


def weighted_response(tiles: Sequence[TileStatistics], power: float) -> Optional[Tuple[float, float, float]]:
    red = green = blue = total = 0.0
    used = 0
    for tile in tiles:
        weight = tile.reliability * (1.0 - tile.clipped_fraction)
        if weight <= 1.0e-6:
            continue
        red += tile.mean[0] ** power * weight
        green += tile.mean[1] ** power * weight
        blue += tile.mean[2] ** power * weight
        total += weight
        used += 1
    if total <= 0.0 or green <= 0.0:
        return None
    inverse = 1.0 / power
    return (red / total) ** inverse / ((green / total) ** inverse), (blue / total) ** inverse / ((green / total) ** inverse), used / max(len(tiles), 1)


def median(values: Sequence[float]) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("median requires at least one value")
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) & 1 else 0.5 * (ordered[middle - 1] + ordered[middle])


def estimate_illuminant(
    tiles: Sequence[TileStatistics],
    prior_response: Rgb = (1.0, 1.0, 1.0),
) -> IlluminantEstimate:
    candidates: List[Tuple[float, float, float]] = []
    gray_world = weighted_response(tiles, 1.0)
    shades = weighted_response(tiles, 6.0)
    if gray_world is not None:
        candidates.append((gray_world[0], gray_world[1], 0.82 * gray_world[2]))
    if shades is not None:
        candidates.append((shades[0], shades[1], 0.72 * shades[2]))
    neutral = [tile for tile in tiles if tile.neutral_confidence * tile.reliability > 0.015]
    if neutral:
        candidates.append((
            math.exp(median([math.log(max(tile.red_over_green, 1.0e-7)) for tile in neutral])),
            math.exp(median([math.log(max(tile.blue_over_green, 1.0e-7)) for tile in neutral])),
            min(1.0, len(neutral) / 6.0),
        ))
    edge_weight = sum(tile.reliability * sum(tile.gradient) for tile in tiles)
    if edge_weight > 1.0e-7:
        red_edge = sum(tile.gradient[0] * tile.reliability for tile in tiles)
        green_edge = sum(tile.gradient[1] * tile.reliability for tile in tiles)
        blue_edge = sum(tile.gradient[2] * tile.reliability for tile in tiles)
        if green_edge > 1.0e-8:
            candidates.append((red_edge / green_edge, blue_edge / green_edge, 0.55))
    candidates.append((prior_response[0] / max(prior_response[1], EPSILON),
        prior_response[2] / max(prior_response[1], EPSILON), 0.18))
    candidates = [candidate for candidate in candidates if candidate[0] > 0.0 and candidate[1] > 0.0]
    if not candidates:
        raise ValueError("no illuminant candidates")
    logs = [(math.log(candidate[0]), math.log(candidate[1]), candidate[2]) for candidate in candidates]
    best = max(
        range(len(logs)),
        key=lambda candidate: sum(
            other[2]
            for other in logs
            if math.hypot(other[0] - logs[candidate][0], other[1] - logs[candidate][1]) <= 0.28
        ),
    )
    center_red, center_blue = logs[best][0], logs[best][1]
    accepted = 0
    residual = 1.0
    for _ in range(4):
        weighted_red = weighted_blue = weight_sum = residual_sum = 0.0
        accepted = 0
        for red, blue, confidence in logs:
            distance = math.hypot(red - center_red, blue - center_blue)
            ratio = distance / 0.42
            robust = (1.0 - ratio * ratio) ** 2 if ratio < 1.0 else 0.0
            weight = confidence * robust
            if distance <= 0.28 and weight > 0.0:
                accepted += 1
            weighted_red += red * weight
            weighted_blue += blue * weight
            residual_sum += distance * weight
            weight_sum += weight
        if weight_sum > EPSILON:
            center_red = weighted_red / weight_sum
            center_blue = weighted_blue / weight_sum
            residual = residual_sum / weight_sum
    red_response = math.exp(center_red)
    blue_response = math.exp(center_blue)
    agreement = 1.0 - smoothstep(0.0, 0.30, residual)
    confidence = clamp(0.55 * accepted / len(candidates) + 0.45 * agreement)
    return IlluminantEstimate(
        red_response=red_response,
        blue_response=blue_response,
        red_gain=clamp(1.0 / red_response, 0.125, 8.0),
        green_gain=1.0,
        blue_gain=clamp(1.0 / blue_response, 0.125, 8.0),
        confidence=confidence,
        accepted_candidates=accepted,
        candidate_count=len(candidates),
    )


def resolve_white_balance(intent: WhiteBalanceIntent, automatic: IlluminantEstimate) -> WhiteBalanceResult:
    if intent.manual_gains is not None:
        if any(not math.isfinite(gain) or gain <= 0.0 for gain in intent.manual_gains):
            raise ValueError("manual gains are invalid")
        return WhiteBalanceResult(intent.manual_gains, "MANUAL", 1.0)
    if intent.locked_gains is not None:
        if any(not math.isfinite(gain) or gain <= 0.0 for gain in intent.locked_gains):
            raise ValueError("locked gains are invalid")
        return WhiteBalanceResult(intent.locked_gains, "AWB_LOCK", 1.0)
    return WhiteBalanceResult(
        (automatic.red_gain, automatic.green_gain, automatic.blue_gain),
        "NIV99_AUTO",
        automatic.confidence,
    )


def estimate_local_illuminants(
    columns: int,
    rows: int,
    tiles: Sequence[TileStatistics],
    global_result: WhiteBalanceResult,
) -> LocalIlluminantField:
    if columns <= 0 or rows <= 0 or len(tiles) != columns * rows:
        raise ValueError("local illuminant grid mismatch")
    gains: List[Rgb] = [global_result.gains for _ in tiles]
    confidence: List[float] = [0.0 for _ in tiles]
    for index, tile in enumerate(tiles):
        evidence = (
            tile.neutral_confidence * tile.reliability *
            (1.0 - tile.clipped_fraction) * (1.0 - tile.shadow_fraction)
        )
        if evidence < 0.01:
            continue
        gains[index] = (
            clamp(1.0 / max(tile.red_over_green, 1.0e-6), 0.5, 2.0),
            1.0,
            clamp(1.0 / max(tile.blue_over_green, 1.0e-6), 0.5, 2.0),
        )
        confidence[index] = clamp(evidence * 2.0)
    for _ in range(4):
        next_gains = gains.copy()
        next_confidence = confidence.copy()
        for tile_y in range(rows):
            for tile_x in range(columns):
                index = tile_y * columns + tile_x
                red_log = math.log(global_result.gains[0]) * 0.08
                blue_log = math.log(global_result.gains[2]) * 0.08
                total = 0.08
                for offset_y in (-1, 0, 1):
                    for offset_x in (-1, 0, 1):
                        neighbour_x = min(max(tile_x + offset_x, 0), columns - 1)
                        neighbour_y = min(max(tile_y + offset_y, 0), rows - 1)
                        neighbour = neighbour_y * columns + neighbour_x
                        spatial = 1.0 if offset_x == 0 and offset_y == 0 else 0.55
                        weight = max(confidence[neighbour], 0.025) * spatial
                        red_log += math.log(max(gains[neighbour][0], 1.0e-6)) * weight
                        blue_log += math.log(max(gains[neighbour][2], 1.0e-6)) * weight
                        total += weight
                next_gains[index] = (
                    clamp(math.exp(red_log / total), 0.5, 2.0),
                    1.0,
                    clamp(math.exp(blue_log / total), 0.5, 2.0),
                )
                next_confidence[index] = clamp(max(confidence[index], total / 5.0))
        gains = next_gains
        confidence = next_confidence
    return LocalIlluminantField(columns, rows, tuple(gains), tuple(confidence))


def apply_local_white_balance(
    image: LinearImage,
    field: LocalIlluminantField,
    global_result: WhiteBalanceResult,
) -> LinearImage:
    output = LinearImage.empty(image.width, image.height)
    tile_width = image.width / field.columns
    tile_height = image.height / field.rows

    def field_value(tile_x: int, tile_y: int) -> Tuple[Rgb, float]:
        bounded_x = min(max(tile_x, 0), field.columns - 1)
        bounded_y = min(max(tile_y, 0), field.rows - 1)
        index = bounded_y * field.columns + bounded_x
        return field.gains[index], field.confidence[index]

    for y in range(image.height):
        for x in range(image.width):
            tile_position_x = (x + 0.5) / tile_width - 0.5
            tile_position_y = (y + 0.5) / tile_height - 0.5
            base_x = math.floor(tile_position_x)
            base_y = math.floor(tile_position_y)
            blend_x = tile_position_x - base_x
            blend_y = tile_position_y - base_y
            gain00, confidence00 = field_value(base_x, base_y)
            gain10, confidence10 = field_value(base_x + 1, base_y)
            gain01, confidence01 = field_value(base_x, base_y + 1)
            gain11, confidence11 = field_value(base_x + 1, base_y + 1)
            upper_gain = mix_rgb(gain00, gain10, blend_x)
            lower_gain = mix_rgb(gain01, gain11, blend_x)
            local_gain = mix_rgb(upper_gain, lower_gain, blend_y)
            upper_confidence = confidence00 + (confidence10 - confidence00) * blend_x
            lower_confidence = confidence01 + (confidence11 - confidence01) * blend_x
            confidence_value = clamp(upper_confidence +
                (lower_confidence - upper_confidence) * blend_y)
            gain = mix_rgb(global_result.gains, local_gain, confidence_value)
            source = image.get(x, y)
            corrected = tuple(max(source[channel] * gain[channel], 0.0) for channel in range(3))
            peak = max(corrected)
            if peak > 1.0 and max(source) <= 1.0:
                corrected = scale_rgb(corrected, 1.0 / peak)
            output.set(x, y, tuple(clamp(value) for value in corrected))
    return output


def apply_white_balance(image: LinearImage, gains: Rgb, preserve_highlights: bool = True) -> LinearImage:
    output = LinearImage.empty(image.width, image.height)
    for y in range(image.height):
        for x in range(image.width):
            source = image.get(x, y)
            corrected = tuple(max(0.0, source[channel] * gains[channel]) for channel in range(3))
            peak = max(corrected)
            if preserve_highlights and peak > 1.0 and max(source) <= 1.0:
                corrected = scale_rgb(corrected, 1.0 / peak)
            output.set(x, y, tuple(clamp(value) for value in corrected))
    return output


def profile_control(profile: str, noise_pressure: float, detail: float) -> TileControl:
    tuning = {
        "Focused": (0.76, 0.58, 0.88, 0.42),
        "Natural": (0.68, 0.48, 0.84, 0.30),
        "Extreme": (0.62, 0.42, 0.80, 0.52),
    }
    if profile not in tuning:
        raise ValueError(f"unsupported detail profile: {profile}")
    raw, luma_strength, chroma, sharpen = tuning[profile]
    guard = clamp(detail)
    return TileControl(
        raw_strength=clamp(raw * noise_pressure * (1.0 - 0.72 * guard)),
        luma_strength=clamp(luma_strength * noise_pressure * (1.0 - 0.82 * guard)),
        chroma_strength=clamp(chroma * noise_pressure * (1.0 - 0.42 * guard)),
        temporal_strength=0.62 if profile == "Natural" else (0.72 if profile == "Focused" else 0.50),
        edge_protection=guard,
        texture_protection=guard,
        skin_protection=0.0,
        sharpening=sharpen * guard,
    )


def spatial_denoise(image: LinearImage, noise: NoiseModel, profile: str = "Natural") -> LinearImage:
    output = LinearImage.empty(image.width, image.height)
    for y in range(image.height):
        for x in range(image.width):
            center = image.get(x, y)
            center_luma = luminance(center)
            horizontal = abs(luminance(image.get(x + 1, y)) - luminance(image.get(x - 1, y))) * 0.5
            vertical = abs(luminance(image.get(x, y + 1)) - luminance(image.get(x, y - 1))) * 0.5
            edge = smoothstep(0.004, 0.08, max(horizontal, vertical))
            sigma = math.sqrt(sum(noise.variance(channel, center[channel]) for channel in range(3)) / 3.0)
            noise_pressure = smoothstep(0.015, 0.28, sigma / max(center_luma, 1.0e-5))
            control = profile_control(profile, noise_pressure, edge)
            weighted_luma = center_luma * 2.0
            weighted_chroma = [
                (center[0] - center_luma) * 2.0,
                (center[1] - center_luma) * 2.0,
                (center[2] - center_luma) * 2.0,
            ]
            luma_weight_sum = 2.0
            chroma_weight_sum = 2.0
            for offset_y in range(-2, 3):
                for offset_x in range(-2, 3):
                    if offset_x == 0 and offset_y == 0:
                        continue
                    sample = image.get(x + offset_x, y + offset_y)
                    sample_luma = luminance(sample)
                    distance_squared = offset_x * offset_x + offset_y * offset_y
                    spatial_weight = math.exp(-0.34 * distance_squared)
                    luma_residual = abs(sample_luma - center_luma)
                    luma_range = math.exp(-0.5 * (luma_residual / max(2.5 * sigma, 0.008)) ** 2)
                    luma_weight = spatial_weight * luma_range
                    chroma_residual = math.sqrt(sum(
                        ((sample[channel] - sample_luma) - (center[channel] - center_luma)) ** 2
                        for channel in range(3)
                    ))
                    chroma_range = math.exp(-0.5 * (chroma_residual / max(4.0 * sigma, 0.012)) ** 2)
                    chroma_weight = spatial_weight * chroma_range
                    weighted_luma += sample_luma * luma_weight
                    luma_weight_sum += luma_weight
                    for channel in range(3):
                        weighted_chroma[channel] += (sample[channel] - sample_luma) * chroma_weight
                    chroma_weight_sum += chroma_weight
            filtered_luma = weighted_luma / max(luma_weight_sum, EPSILON)
            filtered_chroma = [value / max(chroma_weight_sum, EPSILON) for value in weighted_chroma]
            output_luma = center_luma + (filtered_luma - center_luma) * control.luma_strength
            value = tuple(
                output_luma + (
                    center[channel] - center_luma +
                    (filtered_chroma[channel] - (center[channel] - center_luma)) * control.chroma_strength
                )
                for channel in range(3)
            )
            output.set(x, y, tuple(clamp(component) for component in value))
    return output


def temporal_fuse(
    current: LinearImage,
    history: LinearImage,
    noise: NoiseModel,
    flow_x: float,
    flow_y: float,
    forward_backward_error: float,
    strength: float,
    scene_cut: bool = False,
) -> LinearImage:
    if current.width != history.width or current.height != history.height:
        raise ValueError("history dimensions differ")
    if scene_cut:
        return current.copy()
    output = LinearImage.empty(current.width, current.height)
    flow_magnitude = math.hypot(flow_x, flow_y)
    consistency_threshold = 0.35 + 0.08 * flow_magnitude
    consistency = 1.0 - smoothstep(consistency_threshold, consistency_threshold * 3.0, forward_backward_error)
    for y in range(current.height):
        for x in range(current.width):
            current_rgb = current.get(x, y)
            history_x = x + flow_x
            history_y = y + flow_y
            if history_x < 0.0 or history_y < 0.0 or history_x > current.width - 1 or history_y > current.height - 1:
                output.set(x, y, current_rgb)
                continue
            history_rgb = history.bilinear(history_x, history_y)
            current_luma = luminance(current_rgb)
            history_luma = luminance(history_rgb)
            variance = sum(noise.variance(channel, max(current_rgb[channel], history_rgb[channel])) for channel in range(3)) / 3.0
            residual = abs(current_luma - history_luma) / max(math.sqrt(2.0 * variance) * 3.0, 1.0e-5)
            residual_confidence = math.exp(-0.5 * min(residual * residual, 32.0))
            neighbours = [current.get(x + dx, y + dy) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]
            minimum = tuple(min(pixel[channel] for pixel in neighbours) for channel in range(3))
            maximum = tuple(max(pixel[channel] for pixel in neighbours) for channel in range(3))
            allowance = tuple(math.sqrt(noise.variance(channel, current_rgb[channel])) * 2.5 for channel in range(3))
            bounded_history = tuple(
                clamp(history_rgb[channel], minimum[channel] - allowance[channel], maximum[channel] + allowance[channel])
                for channel in range(3)
            )
            common = clamp(strength) * consistency * residual_confidence
            luma_weight = min(common, 0.72)
            chroma_weight = min(common * 1.25, 0.84)
            bounded_luma = luminance(bounded_history)
            output_luma = current_luma + (bounded_luma - current_luma) * luma_weight
            fused = tuple(
                output_luma + (
                    (current_rgb[channel] - current_luma) +
                    ((bounded_history[channel] - bounded_luma) -
                        (current_rgb[channel] - current_luma)) * chroma_weight
                )
                for channel in range(3)
            )
            output.set(x, y, tuple(clamp(component) for component in fused))
    return output


def reconstruct(image: LinearImage, strength: float, noise_sigma: float, halo_limit: float = 0.015) -> LinearImage:
    output = LinearImage.empty(image.width, image.height)
    for y in range(image.height):
        for x in range(image.width):
            center = image.get(x, y)
            center_luma = luminance(center)
            neighbours = [image.get(x + dx, y + dy) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]
            neighbour_lumas = [luminance(pixel) for pixel in neighbours]
            local_mean = sum(neighbour_lumas) / len(neighbour_lumas)
            residual = center_luma - local_mean
            noise_gate = smoothstep(noise_sigma, noise_sigma * 3.5 + 1.0e-6, abs(residual))
            gradient = max(neighbour_lumas) - min(neighbour_lumas)
            structure_gate = smoothstep(0.004, 0.08, gradient)
            admitted = clamp(strength, 0.0, 0.52) * noise_gate * structure_gate
            target_luma = max(center_luma + residual * admitted, 0.0)
            candidate = scale_rgb(center, target_luma / max(center_luma, 1.0e-6))
            minimum = tuple(min(pixel[channel] for pixel in neighbours) for channel in range(3))
            maximum = tuple(max(pixel[channel] for pixel in neighbours) for channel in range(3))
            local_range = max(maximum[channel] - minimum[channel] for channel in range(3))
            allowance = clamp(halo_limit, 0.0, 0.10) * local_range
            output.set(x, y, tuple(
                clamp(candidate[channel], max(0.0, minimum[channel] - allowance), maximum[channel] + allowance)
                for channel in range(3)
            ))
    return output


def mean_squared_error(reference: LinearImage, candidate: LinearImage, channel: Optional[int] = None) -> float:
    if reference.width != candidate.width or reference.height != candidate.height:
        raise ValueError("metric image dimensions differ")
    if channel is None:
        errors = [(luminance(candidate.get(x, y)) - luminance(reference.get(x, y))) ** 2
                  for y in range(reference.height) for x in range(reference.width)]
    else:
        errors = [(candidate.get(x, y)[channel] - reference.get(x, y)[channel]) ** 2
                  for y in range(reference.height) for x in range(reference.width)]
    return sum(errors) / max(len(errors), 1)


def global_ssim(reference: LinearImage, candidate: LinearImage) -> float:
    reference_values = [luminance(reference.get(x, y)) for y in range(reference.height) for x in range(reference.width)]
    candidate_values = [luminance(candidate.get(x, y)) for y in range(candidate.height) for x in range(candidate.width)]
    reference_mean = sum(reference_values) / len(reference_values)
    candidate_mean = sum(candidate_values) / len(candidate_values)
    denominator = max(len(reference_values) - 1, 1)
    reference_variance = sum((value - reference_mean) ** 2 for value in reference_values) / denominator
    candidate_variance = sum((value - candidate_mean) ** 2 for value in candidate_values) / denominator
    covariance = sum(
        (reference_values[index] - reference_mean) * (candidate_values[index] - candidate_mean)
        for index in range(len(reference_values))
    ) / denominator
    c1 = 0.01 ** 2
    c2 = 0.03 ** 2
    return clamp(
        ((2.0 * reference_mean * candidate_mean + c1) * (2.0 * covariance + c2)) /
        max((reference_mean ** 2 + candidate_mean ** 2 + c1) *
            (reference_variance + candidate_variance + c2), EPSILON),
        -1.0,
        1.0,
    )


def gradient_energy(image: LinearImage) -> float:
    energy = 0.0
    for y in range(1, image.height - 1):
        for x in range(1, image.width - 1):
            horizontal = luminance(image.get(x + 1, y)) - luminance(image.get(x - 1, y))
            vertical = luminance(image.get(x, y + 1)) - luminance(image.get(x, y - 1))
            energy += horizontal * horizontal + vertical * vertical
    return energy


def clipping_fraction(image: LinearImage, threshold: float = 0.999) -> float:
    clipped = sum(1 for y in range(image.height) for x in range(image.width) if max(image.get(x, y)) >= threshold)
    return clipped / (image.width * image.height)


def edge_overshoot(reference: LinearImage, candidate: LinearImage) -> float:
    maximum = 0.0
    for y in range(1, reference.height - 1):
        for x in range(1, reference.width - 1):
            neighbours = [
                luminance(reference.get(x + offset_x, y + offset_y))
                for offset_y in (-1, 0, 1)
                for offset_x in (-1, 0, 1)
            ]
            value = luminance(candidate.get(x, y))
            maximum = max(maximum, value - max(neighbours), min(neighbours) - value)
    return max(maximum, 0.0)


def tile_boundary_discontinuity(reference: LinearImage, candidate: LinearImage, tile_width: int, tile_height: int) -> float:
    differences: List[float] = []
    for x in range(tile_width, reference.width, tile_width):
        for y in range(reference.height):
            candidate_jump = luminance(candidate.get(x, y)) - luminance(candidate.get(x - 1, y))
            reference_jump = luminance(reference.get(x, y)) - luminance(reference.get(x - 1, y))
            differences.append(abs(candidate_jump - reference_jump))
    for y in range(tile_height, reference.height, tile_height):
        for x in range(reference.width):
            candidate_jump = luminance(candidate.get(x, y)) - luminance(candidate.get(x, y - 1))
            reference_jump = luminance(reference.get(x, y)) - luminance(reference.get(x, y - 1))
            differences.append(abs(candidate_jump - reference_jump))
    return sum(differences) / max(len(differences), 1)


def evaluate_quality(reference: LinearImage, noisy: LinearImage, candidate: LinearImage) -> QualityMetrics:
    noisy_luma_mse = mean_squared_error(reference, noisy)
    candidate_luma_mse = mean_squared_error(reference, candidate)
    noisy_chroma_mse = sum(mean_squared_error(reference, noisy, channel) for channel in (0, 2)) * 0.5
    candidate_chroma_mse = sum(mean_squared_error(reference, candidate, channel) for channel in (0, 2)) * 0.5
    psnr = 120.0 if candidate_luma_mse <= EPSILON else 10.0 * math.log10(1.0 / candidate_luma_mse)
    luma_improvement = 10.0 * math.log10(max(noisy_luma_mse, EPSILON) / max(candidate_luma_mse, EPSILON))
    chroma_improvement = 10.0 * math.log10(max(noisy_chroma_mse, EPSILON) / max(candidate_chroma_mse, EPSILON))
    reference_gradient = gradient_energy(reference)
    candidate_gradient = gradient_energy(candidate)
    return QualityMetrics(
        psnr_db=psnr,
        ssim=global_ssim(reference, candidate),
        luma_snr_improvement_db=luma_improvement,
        chroma_snr_improvement_db=chroma_improvement,
        gradient_retention=math.sqrt(candidate_gradient / max(reference_gradient, EPSILON)),
        clipping_increase=max(clipping_fraction(candidate) - clipping_fraction(noisy), 0.0),
        edge_overshoot=edge_overshoot(reference, candidate),
        tile_boundary_discontinuity=tile_boundary_discontinuity(reference, candidate, 8, 8),
    )


def chromaticity_error(image: LinearImage, reference: LinearImage) -> float:
    error = 0.0
    count = 0
    for y in range(image.height):
        for x in range(image.width):
            target = reference.get(x, y)
            if max(target) - min(target) > 0.01:
                continue
            measured = image.get(x, y)
            measured_green = max(measured[1], 1.0e-7)
            target_green = max(target[1], 1.0e-7)
            error += math.hypot(
                math.log(max(measured[0] / measured_green, 1.0e-7)) -
                    math.log(max(target[0] / target_green, 1.0e-7)),
                math.log(max(measured[2] / measured_green, 1.0e-7)) -
                    math.log(max(target[2] / target_green, 1.0e-7)),
            )
            count += 1
    return error / max(count, 1)


def run_scenario(scenario: Scenario, seed: int = 99) -> Tuple[WhiteBalanceResult, QualityMetrics, float, str]:
    reference = make_reference_scene()
    sensor = apply_illuminant(reference, scenario.illuminant_left, scenario.illuminant_right)
    source_checksum = sensor.checksum()
    iso_scale = max(scenario.iso / 100.0, 1.0)
    noise = NoiseModel(
        shot=(0.00065 * iso_scale, 0.00055 * iso_scale, 0.00078 * iso_scale),
        read=(1.2e-6 * iso_scale ** 1.7, 1.0e-6 * iso_scale ** 1.7, 1.5e-6 * iso_scale ** 1.7),
    )
    noisy = add_heteroscedastic_noise(sensor, noise, seed)
    columns, rows, statistics = tile_statistics(noisy, noise)
    estimate = estimate_illuminant(statistics, scenario.illuminant_left)
    white_balance = resolve_white_balance(WhiteBalanceIntent(), estimate)
    local_field = estimate_local_illuminants(columns, rows, statistics, white_balance)
    balanced = apply_local_white_balance(noisy, local_field, white_balance)
    spatial = spatial_denoise(balanced, noise, "Natural")
    if scenario.scene_cut:
        temporal = temporal_fuse(spatial, reference, noise, 0.0, 0.0, 0.0, 0.65, scene_cut=True)
    else:
        history_sensor = shift_image(sensor, scenario.motion_x, 0.0)
        history_noisy = add_heteroscedastic_noise(history_sensor, noise, seed + 1)
        history = spatial_denoise(
            apply_local_white_balance(history_noisy, local_field, white_balance),
            noise,
            "Natural",
        )
        temporal = temporal_fuse(
            spatial,
            history,
            noise,
            scenario.motion_x,
            0.0,
            abs(scenario.motion_x) * 0.05,
            0.62,
        )
    candidate = reconstruct(temporal, 0.08, math.sqrt(sum(noise.read) / 3.0))
    metrics = evaluate_quality(reference, balanced, candidate)
    wb_error = chromaticity_error(candidate, reference)
    if sensor.checksum() != source_checksum:
        raise AssertionError("simulated RAW source was mutated")
    return white_balance, metrics, wb_error, source_checksum


STANDARD_SCENARIOS: Tuple[Scenario, ...] = (
    Scenario("D65", (1.00, 1.00, 1.00), (1.00, 1.00, 1.00), 100),
    Scenario("D50", (1.14, 1.00, 0.86), (1.14, 1.00, 0.86), 200),
    Scenario("Tungsten", (1.62, 1.00, 0.54), (1.62, 1.00, 0.54), 800),
    Scenario("Fluorescent", (0.92, 1.08, 0.83), (0.92, 1.08, 0.83), 400),
    Scenario("LowCriLed", (1.30, 0.91, 0.67), (1.30, 0.91, 0.67), 1600),
    Scenario("MixedWindow", (1.55, 1.00, 0.58), (0.82, 1.00, 1.24), 800),
    Scenario("Motion", (1.08, 1.00, 0.90), (1.08, 1.00, 0.90), 800, motion_x=1.25),
    Scenario("SceneCut", (1.08, 1.00, 0.90), (1.08, 1.00, 0.90), 800, scene_cut=True),
)


def summarize_scenarios(scenarios: Iterable[Scenario] = STANDARD_SCENARIOS) -> List[dict]:
    results: List[dict] = []
    for index, scenario in enumerate(scenarios):
        white_balance, metrics, wb_error, checksum = run_scenario(scenario, seed=99 + index)
        results.append({
            "scenario": scenario.name,
            "authority": white_balance.authority,
            "wbConfidence": white_balance.confidence,
            "neutralLogChromaError": wb_error,
            "psnrDb": metrics.psnr_db,
            "ssim": metrics.ssim,
            "lumaSnrImprovementDb": metrics.luma_snr_improvement_db,
            "chromaSnrImprovementDb": metrics.chroma_snr_improvement_db,
            "gradientRetention": metrics.gradient_retention,
            "clippingIncrease": metrics.clipping_increase,
            "edgeOvershoot": metrics.edge_overshoot,
            "tileBoundaryDiscontinuity": metrics.tile_boundary_discontinuity,
            "sourceChecksum": checksum,
        })
    return results


def evidence_document() -> dict:
    scenarios = summarize_scenarios()
    return {
        "schema": "NIV99_DIGITAL_TWIN_EVIDENCE_V1",
        "evidenceClass": "DETERMINISTIC_SIMULATION_ONLY",
        "scenarioCount": len(scenarios),
        "targets": {
            "maximumClippingIncrease": 0.001,
            "minimumGradientRetention": 0.90,
            "minimumLumaSnrImprovementDb": 0.0,
            "minimumChromaSnrImprovementDb": 0.0,
        },
        "aggregate": {
            "allClippingTargetsPass": all(item["clippingIncrease"] <= 0.001 for item in scenarios),
            "allGradientTargetsPass": all(item["gradientRetention"] >= 0.90 for item in scenarios),
            "allLumaNoiseTargetsPass": all(item["lumaSnrImprovementDb"] > 0.0 for item in scenarios),
            "allChromaNoiseTargetsPass": all(item["chromaSnrImprovementDb"] > 0.0 for item in scenarios),
            "minimumPsnrDb": min(item["psnrDb"] for item in scenarios),
            "minimumSsim": min(item["ssim"] for item in scenarios),
            "maximumNeutralLogChromaError": max(item["neutralLogChromaError"] for item in scenarios),
            "maximumTileBoundaryDiscontinuity": max(item["tileBoundaryDiscontinuity"] for item in scenarios),
        },
        "scenarios": scenarios,
        "claims": {
            "physicalDeviceValidated": False,
            "spirvExecutionValidated": False,
            "vendorSuperiorityProven": False,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic NIV99 digital-twin scenarios")
    parser.add_argument("--output", type=Path, help="optional JSON evidence destination")
    arguments = parser.parse_args()
    document = evidence_document()
    encoded = json.dumps(document, indent=2, sort_keys=True) + "\n"
    if arguments.output is None:
        print(encoded, end="")
    else:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = arguments.output.with_suffix(arguments.output.suffix + ".tmp")
        temporary.write_text(encoded, encoding="utf-8", newline="\n")
        temporary.replace(arguments.output)
        print(arguments.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
