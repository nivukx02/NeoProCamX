"""Measure the visible preview aperture in sealed LQ-022 device screenshots.

The camera preview is surrounded by exact-black UI space. Camera sensor noise keeps the
rendered aperture non-black even for the dark validation scene, so a row-coverage threshold
provides a deterministic boundary oracle without trusting the Android view bounds.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


EXPECTED_PORTRAIT_ASPECTS = {
    "square": 1.0,
    "four-three": 3.0 / 4.0,
    "sixteen-nine": 9.0 / 16.0,
    "auto": 3.0 / 4.0,
}
MODES = ("video", "pro", "raw")


def longest_contiguous_run(indices: np.ndarray) -> tuple[int, int]:
    if indices.size == 0:
        raise ValueError("screenshot has no measurable preview rows")
    start = previous = int(indices[0])
    runs: list[tuple[int, int]] = []
    for raw_index in indices[1:]:
        index = int(raw_index)
        if index != previous + 1:
            runs.append((start, previous))
            start = index
        previous = index
    runs.append((start, previous))
    return max(runs, key=lambda run: run[1] - run[0])


def measure(path: Path, expected_aspect: float) -> dict[str, object]:
    pixels = np.asarray(Image.open(path).convert("RGB"))
    active_pixel = pixels.max(axis=2) > 2
    active_row_coverage = active_pixel.mean(axis=1)
    first_row, last_row = longest_contiguous_run(
        np.flatnonzero(active_row_coverage > 0.10)
    )
    aperture_height = last_row - first_row + 1
    aperture_width = int(pixels.shape[1])
    measured_aspect = aperture_width / aperture_height
    absolute_error = abs(measured_aspect - expected_aspect)
    return {
        "file": path.name,
        "screenshot_width_px": aperture_width,
        "screenshot_height_px": int(pixels.shape[0]),
        "visible_preview_top_px": first_row,
        "visible_preview_bottom_exclusive_px": last_row + 1,
        "visible_preview_width_px": aperture_width,
        "visible_preview_height_px": aperture_height,
        "expected_portrait_aspect": expected_aspect,
        "measured_portrait_aspect": measured_aspect,
        "absolute_aspect_error": absolute_error,
        "pass": absolute_error <= 0.001,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("screenshots", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    results: list[dict[str, object]] = []
    for mode in MODES:
        for aspect_name, expected in EXPECTED_PORTRAIT_ASPECTS.items():
            path = args.screenshots / f"{mode}-{aspect_name}.png"
            if not path.is_file():
                raise FileNotFoundError(path)
            result = measure(path, expected)
            result["mode"] = mode.upper()
            result["aspect_case"] = aspect_name
            results.append(result)

    report = {
        "oracle": "LQ22_VISIBLE_PREVIEW_APERTURE_V1",
        "method": {
            "pixel_active_when_max_channel_gt": 2,
            "row_active_when_fraction_gt": 0.10,
            "boundary": "longest_contiguous_active_row_run",
            "aspect_tolerance": 0.001,
        },
        "passed": all(bool(result["pass"]) for result in results),
        "passed_cases": sum(bool(result["pass"]) for result in results),
        "total_cases": len(results),
        "results": results,
    }
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output is not None:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
