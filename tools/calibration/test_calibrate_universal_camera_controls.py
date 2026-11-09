from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
CALIBRATION = ROOT / "tools" / "calibration"
if str(CALIBRATION) not in sys.path:
    sys.path.insert(0, str(CALIBRATION))

from calibrate_universal_camera_controls import (  # noqa: E402
    Candidate,
    _mask,
    _split,
)


class CalibrateUniversalCameraControlsTest(unittest.TestCase):
    def test_split_is_deterministic(self) -> None:
        path = "train/000000123456.jpg"
        self.assertEqual(_split(path), _split(path))
        self.assertIn(_split(path), {"train", "holdout"})

    def test_neutral_mask_rejects_clipping_and_respects_calibrated_window(self) -> None:
        candidate = Candidate(0.16, 0.01, 0.90)
        saturation = np.asarray([[0.0, 0.17], [0.1, 0.1]], dtype=np.float32)
        luma = np.asarray([[0.2, 0.2], [0.005, 0.2]], dtype=np.float32)
        maximum = np.asarray([[0.5, 0.5], [0.5, 1.0]], dtype=np.float32)

        mask = _mask(saturation, luma, maximum, candidate)

        np.testing.assert_array_equal(
            mask,
            np.asarray([[True, False], [False, False]]),
        )


if __name__ == "__main__":
    unittest.main()
