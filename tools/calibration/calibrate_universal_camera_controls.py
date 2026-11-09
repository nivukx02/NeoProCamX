#!/usr/bin/env python3
"""Derive bounded display-domain scene priors for NeoProCamX control validation.

The input corpus contains rendered JPEGs, not RAW sensor samples or measured illuminants.  This
tool therefore never generates Camera2 color matrices, sensor gains, or device overrides.  It uses
the licensed corpus to choose conservative neutral-pixel gates and to validate white-balance,
exposure, ISO/noise, focus, and shutter probes on a deterministic train/holdout split.  Physical
camera calibration remains owned by Camera2/DNG metadata in the Android application.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

import numpy as np


REPO_ROOT = Path(__file__).resolve().parents[2]
SIMULATION_ROOT = REPO_ROOT / "tools" / "simulation"
if str(SIMULATION_ROOT) not in sys.path:
    sys.path.insert(0, str(SIMULATION_ROOT))

from internet_corpus_engine_simulation import (  # noqa: E402
    DATASET_ID,
    DATASET_LICENSE,
    DATASET_REVISION,
    EXPECTED_IMAGE_COUNT,
    WB_CASTS,
    _channel_chroma_error,
    _linear_luma,
    _load_thumbnail,
    _srgb_to_linear,
    _white_balance_probe,
    discover_images,
)


TRAIN_BUCKETS = 8
TOTAL_BUCKETS = 10
SATURATION_CANDIDATES = (0.10, 0.13, 0.16, 0.19)
LUMA_WINDOWS = ((0.01, 0.90), (0.02, 0.82), (0.03, 0.75))


@dataclass(frozen=True)
class Candidate:
    saturation_ceiling: float
    luma_floor: float
    luma_ceiling: float


@dataclass
class CandidateAccumulator:
    images: int = 0
    evaluable_images: int = 0
    probes: int = 0
    evaluable_probes: int = 0
    improved_probes: int = 0
    residual_ratio_sum: float = 0.0
    residual_ratio_count: int = 0
    baseline_chroma_sum: float = 0.0
    spatial_disagreement_sum: float = 0.0

    def report(self) -> dict[str, float | int]:
        coverage = self.evaluable_probes / max(1, self.probes)
        improvement = self.improved_probes / max(1, self.evaluable_probes)
        mean_residual_ratio = self.residual_ratio_sum / max(1, self.residual_ratio_count)
        mean_baseline_chroma = self.baseline_chroma_sum / max(1, self.evaluable_images)
        mean_spatial_disagreement = self.spatial_disagreement_sum / max(
            1, self.evaluable_images
        )
        # Quality-first selection.  Coverage prevents a tiny easy subset from winning; the two
        # penalties reject gates that call strongly colored or spatially inconsistent pixels neutral.
        objective = (
            coverage * improvement
            - 0.45 * mean_baseline_chroma
            - 0.25 * mean_spatial_disagreement
            - 0.10 * mean_residual_ratio
        )
        return {
            "images": self.images,
            "evaluable_images": self.evaluable_images,
            "probes": self.probes,
            "evaluable_probes": self.evaluable_probes,
            "coverage": coverage,
            "improved_fraction": improvement,
            "mean_residual_ratio": mean_residual_ratio,
            "mean_baseline_chroma": mean_baseline_chroma,
            "mean_spatial_disagreement": mean_spatial_disagreement,
            "objective": objective,
        }


def _split(relative_path: str) -> str:
    bucket = int.from_bytes(
        hashlib.sha256(relative_path.encode("utf-8")).digest()[:8], "big"
    ) % TOTAL_BUCKETS
    return "train" if bucket < TRAIN_BUCKETS else "holdout"


def _mask(
    saturation: np.ndarray,
    luma: np.ndarray,
    maximum: np.ndarray,
    candidate: Candidate,
) -> np.ndarray:
    return (
        (saturation <= candidate.saturation_ceiling)
        & (luma >= candidate.luma_floor)
        & (luma <= candidate.luma_ceiling)
        & (maximum < (254.5 / 255.0))
    )


def _spatial_disagreement(linear: np.ndarray, neutral: np.ndarray) -> float:
    rows, columns = neutral.shape
    global_values = linear[neutral]
    if len(global_values) < 24:
        return 1.0
    global_median = np.maximum(np.median(global_values, axis=0), 1e-8)
    global_log = np.log(global_median / np.exp(np.mean(np.log(global_median))))
    distances: list[float] = []
    for row in range(0, rows, max(1, rows // 4)):
        for column in range(0, columns, max(1, columns // 4)):
            tile = neutral[
                row : min(rows, row + max(1, rows // 4)),
                column : min(columns, column + max(1, columns // 4)),
            ]
            if np.count_nonzero(tile) < 8:
                continue
            values = linear[
                row : min(rows, row + max(1, rows // 4)),
                column : min(columns, column + max(1, columns // 4)),
            ][tile]
            median = np.maximum(np.median(values, axis=0), 1e-8)
            tile_log = np.log(median / np.exp(np.mean(np.log(median))))
            distances.append(float(np.linalg.norm(tile_log - global_log)))
    return float(np.median(distances)) if distances else 1.0


def _accumulate(
    accumulator: CandidateAccumulator,
    linear: np.ndarray,
    neutral: np.ndarray,
) -> None:
    accumulator.images += 1
    accumulator.probes += len(WB_CASTS)
    minimum_count = max(24, int(neutral.size * 0.002))
    if np.count_nonzero(neutral) < minimum_count:
        return
    accumulator.evaluable_images += 1
    accumulator.baseline_chroma_sum += _channel_chroma_error(linear[neutral])
    accumulator.spatial_disagreement_sum += _spatial_disagreement(linear, neutral)
    for gains in WB_CASTS.values():
        result = _white_balance_probe(linear, neutral, gains)
        if result is None:
            continue
        before, after = result
        accumulator.evaluable_probes += 1
        accumulator.improved_probes += int(after <= before + 1e-6)
        if before > 1e-9:
            accumulator.residual_ratio_sum += after / before
            accumulator.residual_ratio_count += 1


def _candidate_key(candidate: Candidate) -> str:
    return (
        f"sat={candidate.saturation_ceiling:.2f},"
        f"luma={candidate.luma_floor:.2f}..{candidate.luma_ceiling:.2f}"
    )


def _corpus_digest(paths: Iterable[Path], corpus_root: Path) -> str:
    aggregate = hashlib.sha256()
    for path in paths:
        aggregate.update(path.relative_to(corpus_root).as_posix().encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(str(path.stat().st_size).encode("ascii"))
        aggregate.update(b"\n")
    return aggregate.hexdigest().upper()


def calibrate(corpus_root: Path, maximum_dimension: int = 96) -> dict[str, object]:
    paths = discover_images(corpus_root)
    candidates = tuple(
        Candidate(saturation, luma_floor, luma_ceiling)
        for saturation in SATURATION_CANDIDATES
        for luma_floor, luma_ceiling in LUMA_WINDOWS
    )
    accumulators = {
        split: {candidate: CandidateAccumulator() for candidate in candidates}
        for split in ("train", "holdout")
    }
    decode_errors: list[str] = []
    for path in paths:
        try:
            rgb, _, _ = _load_thumbnail(path, maximum_dimension)
        except (OSError, ValueError) as exc:
            if len(decode_errors) < 20:
                decode_errors.append(f"{path.name}: {exc}")
            continue
        linear = _srgb_to_linear(rgb)
        luma = _linear_luma(linear)
        maximum = np.max(rgb, axis=-1)
        minimum = np.min(rgb, axis=-1)
        saturation = (maximum - minimum) / np.maximum(maximum, 1e-6)
        split = _split(path.relative_to(corpus_root).as_posix())
        for candidate in candidates:
            neutral = _mask(saturation, luma, maximum, candidate)
            _accumulate(accumulators[split][candidate], linear, neutral)

    train_reports = {
        _candidate_key(candidate): accumulators["train"][candidate].report()
        for candidate in candidates
    }
    selected = max(
        candidates,
        key=lambda candidate: (
            train_reports[_candidate_key(candidate)]["objective"],
            -candidate.saturation_ceiling,
        ),
    )
    holdout_report = accumulators["holdout"][selected].report()
    selected_train = train_reports[_candidate_key(selected)]
    status = "PASS"
    failures: dict[str, object] = {}
    if len(paths) != EXPECTED_IMAGE_COUNT:
        failures["image_count"] = {"expected": EXPECTED_IMAGE_COUNT, "actual": len(paths)}
    if decode_errors:
        failures["decode_errors"] = decode_errors
    if holdout_report["coverage"] < 0.95:
        failures["holdout_coverage"] = holdout_report["coverage"]
    if holdout_report["improved_fraction"] < 0.98:
        failures["holdout_improved_fraction"] = holdout_report["improved_fraction"]
    if not math.isfinite(float(holdout_report["objective"])):
        failures["holdout_objective"] = holdout_report["objective"]
    if failures:
        status = "FAIL"
    return {
        "status": status,
        "scope": "DISPLAY_RGB_SCENE_PRIOR_AND_CONTROL_STRESS_CALIBRATION",
        "dataset": {
            "id": DATASET_ID,
            "revision": DATASET_REVISION,
            "license": DATASET_LICENSE,
            "image_count": len(paths),
            "size_manifest_sha256": _corpus_digest(paths, corpus_root),
            "split": "SHA256(path) modulo 10; buckets 0..7 train, 8..9 holdout",
        },
        "selected_display_rgb_prior": asdict(selected),
        "train": selected_train,
        "holdout": holdout_report,
        "all_train_candidates": train_reports,
        "failures": failures,
        "integration_contract": {
            "camera2_dng_sensor_calibration_overridden": False,
            "manual_wb_iso_shutter_authority_overridden": False,
            "recording_quality_or_fps_reduced": False,
            "use": [
                "host WB estimator stress",
                "mixed-light confidence validation",
                "exposure/ISO/noise/focus/shutter control regression",
            ],
        },
        "limitations": [
            "JPEGs contain unknown ISP white balance, tone mapping, color matrices, grading, and compression.",
            "The selected values are display-domain scene priors, not physical sensor calibration.",
            "Per-device Camera2/DNG matrices and on-device chart measurements remain authoritative.",
        ],
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--maximum-dimension", type=int, default=96)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    report = calibrate(args.corpus.resolve(), max(64, args.maximum_dimension))
    payload = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
