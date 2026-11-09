from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image


HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from reference_scene_engine_simulation import (  # noqa: E402
    analyze_reference,
    run,
)


class ReferenceSceneEngineSimulationTest(unittest.TestCase):
    def _write_reference(self, directory: Path, name: str, array: np.ndarray) -> Path:
        path = directory / name
        Image.fromarray(array.astype(np.uint8), mode="RGB").save(path)
        return path

    def test_reports_baked_clipping_shadow_and_intrinsic_detail_imbalance(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            image = np.zeros((240, 360, 3), dtype=np.uint8)
            image[:, :120] = 2
            image[:, 120:240] = 110
            image[::2, 120:240] = 145
            image[:, 240:] = 255
            report = analyze_reference(self._write_reference(root, "stress.png", image))

        self.assertGreater(report.clipped_rgb_fraction, 0.30)
        self.assertGreater(report.deep_shadow_fraction, 0.30)
        self.assertTrue(report.intrinsic_focus_nonuniformity)
        self.assertEqual(9, len(report.crop_signals))

    def test_all_engine_matrix_and_partial_manual_pan_contract_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            x = np.linspace(0, 255, 360, dtype=np.uint8)
            gradient = np.tile(x, (240, 1))
            image = np.stack((gradient, np.flip(gradient, axis=1), gradient), axis=-1)
            path = self._write_reference(root, "pan.png", image)
            report = run([path])

        self.assertEqual("PASS", report["status"])
        matrix = report["checkpoints"]["wb_iso_shutter_ev_focus_all_modes_all_detail_profiles"]
        self.assertEqual(675, matrix["cases"])
        pan = report["checkpoints"]["partial_manual_pan_whiteout_regression"]
        self.assertEqual(27, pan["cases"])
        self.assertEqual(0, pan["coordinator_regressions"])
        self.assertEqual(27, pan["stale_meter_fail_closed_cases"])
        self.assertEqual(
            "STATEFUL_PREVIOUS_AE_OFF_OUTPUT_NO_FRESH_PLATFORM_METER_PER_CROP",
            pan["meter_model"],
        )


if __name__ == "__main__":
    unittest.main()
