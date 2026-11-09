from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SIMULATION = ROOT / "tools" / "simulation"
if str(SIMULATION) not in sys.path:
    sys.path.insert(0, str(SIMULATION))

from internet_corpus_engine_simulation import (  # noqa: E402
    CALIBRATED_NEUTRAL_LUMA_CEILING,
    CALIBRATED_NEUTRAL_LUMA_FLOOR,
    CALIBRATED_NEUTRAL_SATURATION_CEILING,
    EXPECTED_IMAGE_COUNT,
    ImageSignals,
    ProbeCounters,
    analyze_image,
    run_control_matrix,
    run_focus_stability_stress,
    run_temporal_response,
    run_white_balance_validity_stress,
    verify_default_all_mode_wiring,
    _estimate_white_balance,
    _srgb_to_linear,
)


class InternetCorpusEngineSimulationTest(unittest.TestCase):
    def test_pinned_training_selected_display_rgb_prior_and_neutral_cct(self) -> None:
        self.assertEqual(0.16, CALIBRATED_NEUTRAL_SATURATION_CEILING)
        self.assertEqual(0.01, CALIBRATED_NEUTRAL_LUMA_FLOOR)
        self.assertEqual(0.90, CALIBRATED_NEUTRAL_LUMA_CEILING)
        rgb = np.full((32, 32, 3), 0.5, dtype=np.float32)

        kelvin, tint, confidence, neutral = _estimate_white_balance(
            rgb,
            _srgb_to_linear(rgb),
        )

        self.assertIsNotNone(kelvin)
        self.assertGreaterEqual(kelvin, 6_300)
        self.assertLessEqual(kelvin, 6_700)
        self.assertEqual(0, tint)
        self.assertGreater(confidence, 0.8)
        self.assertTrue(np.all(neutral))

    def test_control_matrix_covers_every_mode_profile_and_fails_closed_without_wb_measurement(
        self,
    ) -> None:
        signal = ImageSignals(
            relative_path="train/probe.jpg",
            width=640,
            height=480,
            p50_luma=0.18,
            p995_luma=0.82,
            clipped_fraction=0.0,
            shadow_fraction=0.01,
            detail_score=0.08,
            noise_proxy=0.004,
            estimated_kelvin=5_600,
            estimated_tint=0,
            wb_confidence=0.9,
            focus_confidence=0.95,
            metered_iso=100,
            metered_exposure_ns=4_000_000,
            highlight_bias_stops=0.0,
        )

        report = run_control_matrix((signal,))

        self.assertEqual("PASS", report["status"])
        self.assertEqual(60, report["cases"])
        self.assertEqual(15, report["missing_wb_measurement_fail_closed_cases"])
        self.assertEqual(
            {"VIDEO", "PRO", "NDR", "LOG", "RAW"},
            set(report["all_modes"]),
        )

    def test_temporal_response_meets_profile_specific_limits(self) -> None:
        report = run_temporal_response()

        self.assertEqual("PASS", report["status"])
        for profile, observed in report["focus_worst_ms"].items():
            self.assertLessEqual(observed, report["focus_limits_ms"][profile])
        for profile, observed in report["software_ae_worst_ms"].items():
            self.assertLessEqual(observed, report["software_ae_limits_ms"][profile])
        for profile, observed in report["software_ae_recovery_worst_ms"].items():
            self.assertLessEqual(
                observed,
                report["software_ae_recovery_limits_ms"][profile],
            )
        self.assertEqual([0.25, 0.5, 2.0, 4.0], report["software_ae_scene_gains"])

    def test_automatic_white_balance_rejects_invalid_metadata_without_clamping(self) -> None:
        report = run_white_balance_validity_stress()

        self.assertEqual("PASS", report["status"])
        self.assertEqual(90, report["cases"])
        self.assertEqual(30, report["accepted_boundary_cases"])
        self.assertEqual(60, report["rejected_invalid_cases"])

    def test_focus_micro_jitter_is_held_while_meaningful_changes_still_respond(self) -> None:
        report = run_focus_stability_stress()

        self.assertEqual("PASS", report["status"])
        for per_mode in report["lens_commands_by_profile_and_mode"].values():
            self.assertTrue(all(commands == 0 for commands in per_mode.values()))
        for per_mode in report["meaningful_response_diopters_by_profile_and_mode"].values():
            self.assertTrue(all(response > 0.0 for response in per_mode.values()))

    def test_one_real_decode_generates_every_controlled_probe_family(self) -> None:
        rows, columns = 96, 128
        y, x = np.indices((rows, columns))
        checker = ((x // 8 + y // 8) & 1).astype(np.float32)
        rgb = np.stack(
            (
                0.15 + 0.75 * checker,
                0.10 + 0.65 * (x / max(1, columns - 1)),
                0.12 + 0.70 * (y / max(1, rows - 1)),
            ),
            axis=-1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            train = root / "train"
            train.mkdir()
            path = train / "000000000001.jpg"
            Image.fromarray(np.uint8(np.clip(rgb, 0.0, 1.0) * 255.0), mode="RGB").save(
                path,
                quality=95,
            )
            counters = ProbeCounters()

            signal = analyze_image(path, root, 128, counters)

        self.assertGreater(signal.detail_score, 0.0)
        self.assertEqual(7, counters.exposure_probes)
        self.assertEqual(6, counters.iso_noise_probes)
        self.assertEqual(6, counters.wb_probes)
        self.assertEqual(5, counters.focus_probes)
        self.assertEqual(4, counters.shutter_motion_probes)
        self.assertEqual(1, counters.iso_monotonic_images)
        self.assertEqual(1, counters.focus_monotonic_images)
        self.assertEqual(1, counters.motion_monotonic_images)

    def test_pinned_corpus_and_default_on_all_mode_source_contract(self) -> None:
        self.assertEqual(5_000, EXPECTED_IMAGE_COUNT)
        report = verify_default_all_mode_wiring(ROOT)
        self.assertEqual("PASS", report["status"])
        self.assertTrue(all(report["checks"].values()))


if __name__ == "__main__":
    unittest.main()
