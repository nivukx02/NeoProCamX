from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

from tools.neo_qa_hypergrid.color_parity_oracle import (
    VisibleColourThresholds,
    analyze_visible_colour_parity,
)


class VisibleColourParityOracleTest(unittest.TestCase):
    @staticmethod
    def _chart(path: Path, saturation: float = 1.0, pink_center: bool = False) -> None:
        height, width = 120, 160
        x = np.linspace(0.08, 0.92, width, dtype=np.float64)
        luma = np.broadcast_to(x, (height, width))
        image = np.stack((luma, luma, luma), axis=2)
        colours = (
            (0.78, 0.18, 0.16),
            (0.14, 0.62, 0.24),
            (0.12, 0.28, 0.80),
        )
        for index, colour in enumerate(colours):
            left = 8 + index * 51
            patch = np.array(colour)
            neutral = np.full(3, np.mean(patch))
            image[18:54, left : left + 38] = neutral + (patch - neutral) * saturation
        image[72:102, 58:102] = 0.48
        if pink_center:
            image[30:90, 48:112, 0] += 0.16
            image[30:90, 48:112, 2] += 0.10
        Image.fromarray(np.uint8(np.clip(image, 0.0, 1.0) * 255.0), "RGB").save(path)

    def test_matching_visible_chart_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preview = root / "preview.png"
            recorded = root / "recorded.png"
            self._chart(preview)
            self._chart(recorded, saturation=0.98)
            report = analyze_visible_colour_parity(
                preview,
                recorded,
                (0.0, 0.0, 1.0, 1.0),
                (0.0, 0.0, 1.0, 1.0),
                preview_neutral_rois=((0.36, 0.60, 0.28, 0.25),),
                recorded_neutral_rois=((0.36, 0.60, 0.28, 0.25),),
                preview_colour_rois=(
                    (0.05, 0.15, 0.24, 0.30),
                    (0.37, 0.15, 0.24, 0.30),
                    (0.69, 0.15, 0.24, 0.30),
                ),
                recorded_colour_rois=(
                    (0.05, 0.15, 0.24, 0.30),
                    (0.37, 0.15, 0.24, 0.30),
                    (0.69, 0.15, 0.24, 0.30),
                ),
                thresholds=VisibleColourThresholds(
                    maximum_center_edge_chromaticity_delta=0.10,
                ),
            )
            self.assertTrue(report.passed, report.failures)

    def test_flat_recording_and_pink_center_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preview = root / "preview.png"
            recorded = root / "recorded.png"
            self._chart(preview, pink_center=True)
            self._chart(recorded, saturation=0.05)
            report = analyze_visible_colour_parity(
                preview,
                recorded,
                (0.0, 0.0, 1.0, 1.0),
                (0.0, 0.0, 1.0, 1.0),
                preview_neutral_rois=((0.36, 0.60, 0.28, 0.25),),
                recorded_neutral_rois=((0.36, 0.60, 0.28, 0.25),),
                thresholds=VisibleColourThresholds(
                    maximum_center_edge_chromaticity_delta=0.005,
                ),
            )
            self.assertFalse(report.passed)
            self.assertTrue(any("saturation" in failure for failure in report.failures))
            self.assertTrue(any("center/edge" in failure for failure in report.failures))

    def test_mismatched_patch_counts_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            preview = root / "preview.png"
            recorded = root / "recorded.png"
            self._chart(preview)
            self._chart(recorded)
            with self.assertRaisesRegex(ValueError, "ROI counts must match"):
                analyze_visible_colour_parity(
                    preview,
                    recorded,
                    (0.0, 0.0, 1.0, 1.0),
                    (0.0, 0.0, 1.0, 1.0),
                    preview_colour_rois=((0.0, 0.0, 0.1, 0.1),),
                )


if __name__ == "__main__":
    unittest.main()
