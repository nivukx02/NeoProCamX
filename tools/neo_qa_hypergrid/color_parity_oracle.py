"""Objective visible-colour evidence for the LQ preview/recording parity gate.

The oracle measures files captured from a physical run.  It deliberately does not
claim sensor accuracy from generated images or infer camera quality from source
configuration.  A caller must supply matching controlled-scene regions for the
preview screenshot and a decoded recording frame.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np
from PIL import Image


@dataclass(frozen=True)
class VisibleColourThresholds:
    # A controlled chart contains neutral/grey patches by design, so the median of all pixels can be
    # zero even with correct colour.  The 90th percentile measures the colour-bearing patches while
    # remaining insensitive to a handful of saturated UI pixels.
    minimum_saturation_p90: float = 0.10
    minimum_tonal_span: float = 0.20
    maximum_black_clip_fraction: float = 0.02
    maximum_white_clip_fraction: float = 0.02
    maximum_neutral_channel_spread: float = 0.045
    maximum_center_edge_chromaticity_delta: float = 0.045
    minimum_preview_record_saturation_ratio: float = 0.75
    maximum_preview_record_saturation_ratio: float = 1.33
    minimum_preview_record_tonal_span_ratio: float = 0.70
    maximum_preview_record_tonal_span_ratio: float = 1.30
    maximum_mean_colour_patch_delta_e76: float = 10.0

    @classmethod
    def from_mapping(cls, values: Mapping[str, Any] | None) -> "VisibleColourThresholds":
        if not values:
            return cls()
        known = {field.name for field in cls.__dataclass_fields__.values()}
        unknown = sorted(set(values) - known)
        if unknown:
            raise ValueError("unknown colour threshold(s): " + ", ".join(unknown))
        return cls(**{key: float(value) for key, value in values.items()})


@dataclass(frozen=True)
class VisibleColourMetrics:
    width: int
    height: int
    median_saturation: float
    saturation_p90: float
    luma_p05: float
    luma_p95: float
    tonal_span: float
    black_clip_fraction: float
    white_clip_fraction: float
    neutral_channel_spread: float | None
    center_edge_chromaticity_delta: float


@dataclass(frozen=True)
class VisibleColourParityReport:
    preview: VisibleColourMetrics
    recorded: VisibleColourMetrics
    saturation_ratio: float
    tonal_span_ratio: float
    mean_colour_patch_delta_e76: float | None
    failures: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.failures


def _validate_normalized_rect(rect: Sequence[float], label: str) -> tuple[float, float, float, float]:
    if len(rect) != 4:
        raise ValueError(f"{label} must contain x, y, width and height")
    x, y, width, height = (float(value) for value in rect)
    if not all(math.isfinite(value) for value in (x, y, width, height)):
        raise ValueError(f"{label} contains a non-finite value")
    if x < 0.0 or y < 0.0 or width <= 0.0 or height <= 0.0:
        raise ValueError(f"{label} must be positive and inside normalized image space")
    if x + width > 1.0 or y + height > 1.0:
        raise ValueError(f"{label} extends outside normalized image space")
    return x, y, width, height


def _load_rgb(path: Path) -> np.ndarray:
    if not path.is_file():
        raise FileNotFoundError(path)
    with Image.open(path) as image:
        rgb = np.asarray(image.convert("RGB"), dtype=np.float64) / 255.0
    if rgb.ndim != 3 or rgb.shape[2] != 3 or rgb.shape[0] < 2 or rgb.shape[1] < 2:
        raise ValueError(f"unsupported image dimensions: {rgb.shape}")
    return rgb


def _crop(image: np.ndarray, rect: Sequence[float], label: str) -> np.ndarray:
    x, y, width, height = _validate_normalized_rect(rect, label)
    image_height, image_width = image.shape[:2]
    left = min(image_width - 1, int(math.floor(x * image_width)))
    top = min(image_height - 1, int(math.floor(y * image_height)))
    right = min(image_width, max(left + 1, int(math.ceil((x + width) * image_width))))
    bottom = min(image_height, max(top + 1, int(math.ceil((y + height) * image_height))))
    return image[top:bottom, left:right]


def _chromaticity(rgb: np.ndarray) -> np.ndarray:
    mean = np.mean(rgb.reshape(-1, 3), axis=0)
    total = float(np.sum(mean))
    if total <= 1e-9:
        return np.full(3, 1.0 / 3.0, dtype=np.float64)
    return mean / total


def _srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    return np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)


def _rgb_mean_to_lab(rgb: np.ndarray) -> np.ndarray:
    mean = np.mean(rgb.reshape(-1, 3), axis=0)
    linear = _srgb_to_linear(mean)
    xyz = np.array(
        [
            0.4124564 * linear[0] + 0.3575761 * linear[1] + 0.1804375 * linear[2],
            0.2126729 * linear[0] + 0.7151522 * linear[1] + 0.0721750 * linear[2],
            0.0193339 * linear[0] + 0.1191920 * linear[1] + 0.9503041 * linear[2],
        ],
        dtype=np.float64,
    )
    scaled = xyz / np.array([0.95047, 1.0, 1.08883], dtype=np.float64)
    delta = 6.0 / 29.0
    transformed = np.where(
        scaled > delta**3,
        np.cbrt(scaled),
        scaled / (3.0 * delta * delta) + 4.0 / 29.0,
    )
    return np.array(
        [
            116.0 * transformed[1] - 16.0,
            500.0 * (transformed[0] - transformed[1]),
            200.0 * (transformed[1] - transformed[2]),
        ],
        dtype=np.float64,
    )


def _neutral_spread(image: np.ndarray, rois: Sequence[Sequence[float]]) -> float | None:
    if not rois:
        return None
    spreads: list[float] = []
    for index, rect in enumerate(rois):
        chroma = _chromaticity(_crop(image, rect, f"neutral_rois[{index}]"))
        spreads.append(float(np.max(chroma) - np.min(chroma)))
    return float(np.max(spreads))


def measure_visible_colour(
    image: np.ndarray,
    analysis_roi: Sequence[float],
    neutral_rois: Sequence[Sequence[float]] = (),
) -> VisibleColourMetrics:
    region = _crop(image, analysis_roi, "analysis_roi")
    maximum = np.max(region, axis=2)
    minimum = np.min(region, axis=2)
    saturation = np.divide(
        maximum - minimum,
        maximum,
        out=np.zeros_like(maximum),
        where=maximum > 1e-6,
    )
    luma = 0.2126 * region[:, :, 0] + 0.7152 * region[:, :, 1] + 0.0722 * region[:, :, 2]
    luma_p05, luma_p95 = (float(value) for value in np.percentile(luma, (5.0, 95.0)))

    height, width = region.shape[:2]
    center = region[
        int(height * 0.25) : max(int(height * 0.75), int(height * 0.25) + 1),
        int(width * 0.25) : max(int(width * 0.75), int(width * 0.25) + 1),
    ]
    edge_mask = np.ones((height, width), dtype=bool)
    edge_mask[
        int(height * 0.25) : max(int(height * 0.75), int(height * 0.25) + 1),
        int(width * 0.25) : max(int(width * 0.75), int(width * 0.25) + 1),
    ] = False
    edge = region[edge_mask]
    center_edge_delta = float(np.linalg.norm(_chromaticity(center) - _chromaticity(edge)))

    return VisibleColourMetrics(
        width=width,
        height=height,
        median_saturation=float(np.median(saturation)),
        saturation_p90=float(np.percentile(saturation, 90.0)),
        luma_p05=luma_p05,
        luma_p95=luma_p95,
        tonal_span=luma_p95 - luma_p05,
        black_clip_fraction=float(np.mean(maximum <= (1.0 / 255.0))),
        white_clip_fraction=float(np.mean(minimum >= (254.0 / 255.0))),
        neutral_channel_spread=_neutral_spread(image, neutral_rois),
        center_edge_chromaticity_delta=center_edge_delta,
    )


def _safe_ratio(numerator: float, denominator: float) -> float:
    if denominator <= 1e-9:
        return math.inf if numerator > 1e-9 else 1.0
    return numerator / denominator


def analyze_visible_colour_parity(
    preview_path: Path,
    recorded_path: Path,
    preview_analysis_roi: Sequence[float],
    recorded_analysis_roi: Sequence[float],
    preview_neutral_rois: Sequence[Sequence[float]] = (),
    recorded_neutral_rois: Sequence[Sequence[float]] = (),
    preview_colour_rois: Sequence[Sequence[float]] = (),
    recorded_colour_rois: Sequence[Sequence[float]] = (),
    thresholds: VisibleColourThresholds = VisibleColourThresholds(),
) -> VisibleColourParityReport:
    if len(preview_colour_rois) != len(recorded_colour_rois):
        raise ValueError("preview and recorded colour ROI counts must match")
    preview_image = _load_rgb(preview_path)
    recorded_image = _load_rgb(recorded_path)
    preview = measure_visible_colour(preview_image, preview_analysis_roi, preview_neutral_rois)
    recorded = measure_visible_colour(recorded_image, recorded_analysis_roi, recorded_neutral_rois)
    saturation_ratio = _safe_ratio(recorded.saturation_p90, preview.saturation_p90)
    tonal_span_ratio = _safe_ratio(recorded.tonal_span, preview.tonal_span)

    patch_deltas = [
        float(
            np.linalg.norm(
                _rgb_mean_to_lab(_crop(preview_image, preview_rect, f"preview_colour_rois[{index}]"))
                - _rgb_mean_to_lab(_crop(recorded_image, recorded_rect, f"recorded_colour_rois[{index}]"))
            )
        )
        for index, (preview_rect, recorded_rect) in enumerate(
            zip(preview_colour_rois, recorded_colour_rois, strict=True)
        )
    ]
    mean_patch_delta = float(np.mean(patch_deltas)) if patch_deltas else None

    failures: list[str] = []
    for label, metrics in (("preview", preview), ("recorded", recorded)):
        if metrics.saturation_p90 < thresholds.minimum_saturation_p90:
            failures.append(
                f"{label} saturation p90 {metrics.saturation_p90:.6f} < "
                f"{thresholds.minimum_saturation_p90:.6f}"
            )
        if metrics.tonal_span < thresholds.minimum_tonal_span:
            failures.append(
                f"{label} tonal span {metrics.tonal_span:.6f} < "
                f"{thresholds.minimum_tonal_span:.6f}"
            )
        if metrics.black_clip_fraction > thresholds.maximum_black_clip_fraction:
            failures.append(
                f"{label} black clipping {metrics.black_clip_fraction:.6f} > "
                f"{thresholds.maximum_black_clip_fraction:.6f}"
            )
        if metrics.white_clip_fraction > thresholds.maximum_white_clip_fraction:
            failures.append(
                f"{label} white clipping {metrics.white_clip_fraction:.6f} > "
                f"{thresholds.maximum_white_clip_fraction:.6f}"
            )
        if (
            metrics.neutral_channel_spread is not None
            and metrics.neutral_channel_spread > thresholds.maximum_neutral_channel_spread
        ):
            failures.append(
                f"{label} neutral channel spread {metrics.neutral_channel_spread:.6f} > "
                f"{thresholds.maximum_neutral_channel_spread:.6f}"
            )
        if (
            metrics.center_edge_chromaticity_delta
            > thresholds.maximum_center_edge_chromaticity_delta
        ):
            failures.append(
                f"{label} center/edge chromaticity delta "
                f"{metrics.center_edge_chromaticity_delta:.6f} > "
                f"{thresholds.maximum_center_edge_chromaticity_delta:.6f}"
            )

    if not (
        thresholds.minimum_preview_record_saturation_ratio
        <= saturation_ratio
        <= thresholds.maximum_preview_record_saturation_ratio
    ):
        failures.append(f"preview/recorded saturation ratio out of range: {saturation_ratio:.6f}")
    if not (
        thresholds.minimum_preview_record_tonal_span_ratio
        <= tonal_span_ratio
        <= thresholds.maximum_preview_record_tonal_span_ratio
    ):
        failures.append(f"preview/recorded tonal-span ratio out of range: {tonal_span_ratio:.6f}")
    if (
        mean_patch_delta is not None
        and mean_patch_delta > thresholds.maximum_mean_colour_patch_delta_e76
    ):
        failures.append(
            f"mean preview/recorded colour-patch deltaE76 {mean_patch_delta:.6f} > "
            f"{thresholds.maximum_mean_colour_patch_delta_e76:.6f}"
        )

    return VisibleColourParityReport(
        preview=preview,
        recorded=recorded,
        saturation_ratio=saturation_ratio,
        tonal_span_ratio=tonal_span_ratio,
        mean_colour_patch_delta_e76=mean_patch_delta,
        failures=tuple(failures),
    )


def _atomic_write_json(path: Path, payload: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as target:
            json.dump(payload, target, indent=2, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def analyze_manifest(manifest_path: Path) -> VisibleColourParityReport:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    base = manifest_path.resolve().parent

    def resolve_image(raw: str) -> Path:
        path = Path(raw)
        return path if path.is_absolute() else base / path

    preview = manifest["preview"]
    recorded = manifest["recorded"]
    return analyze_visible_colour_parity(
        preview_path=resolve_image(preview["path"]),
        recorded_path=resolve_image(recorded["path"]),
        preview_analysis_roi=preview["analysis_roi"],
        recorded_analysis_roi=recorded["analysis_roi"],
        preview_neutral_rois=preview.get("neutral_rois", ()),
        recorded_neutral_rois=recorded.get("neutral_rois", ()),
        preview_colour_rois=preview.get("colour_rois", ()),
        recorded_colour_rois=recorded.get("colour_rois", ()),
        thresholds=VisibleColourThresholds.from_mapping(manifest.get("thresholds")),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args(argv)
    report = analyze_manifest(arguments.manifest)
    _atomic_write_json(
        arguments.output,
        {
            "schema": "neo-qa-hypergrid/visible-colour-parity/v1",
            "status": "PASS" if report.passed else "FAIL",
            **asdict(report),
        },
    )
    return 0 if report.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
