from __future__ import annotations

import unittest
import tempfile
from pathlib import Path

from tools.neo_qa_hypergrid.quality_guard import QualitySnapshot, compare_quality


def baseline() -> QualitySnapshot:
    return QualitySnapshot(
        width=3264,
        height=2448,
        target_fps=25,
        bitrate_bps=160_000_000,
        bit_depth=10,
        codec_profile_rank=10,
        color_primaries="BT2020",
        color_transfer="PQ",
        raw_sensor_fidelity=True,
        metadata_fields=frozenset({"orientation", "colour", "hdr"}),
        audio_bitrate_bps=320_000,
        audio_sample_rate_hz=48_000,
        audio_channels=2,
        detail_profiles=frozenset({"BALANCED", "FOCUSED", "NATURAL"}),
        conversion_quality_rank=100,
        recording_features=frozenset({"VIDEO", "PRO", "RAW"}),
        security_controls=frozenset({"KEYSTORE", "TAMPER", "PINNING"}),
        prediction_structure="ALL_INTRA",
    )


class QualityGuardTest(unittest.TestCase):
    def test_any_quality_or_security_reduction_fails(self) -> None:
        value = baseline()
        downgraded = QualitySnapshot(
            **{
                **value.__dict__,
                "bit_depth": 8,
                "security_controls": frozenset({"KEYSTORE"}),
            }
        )
        verdict = compare_quality(value, downgraded)
        self.assertFalse(verdict.accepted)
        self.assertEqual({"bit_depth", "security_controls"}, {item.field for item in verdict.violations})

    def test_sustainable_prediction_change_is_allowed_when_quality_is_preserved(self) -> None:
        value = baseline()
        candidate = QualitySnapshot(**{**value.__dict__, "prediction_structure": "IP_GOP_1S"})
        verdict = compare_quality(
            value,
            candidate,
            prediction_change_reason="sustainable_realtime",
        )
        self.assertTrue(verdict.accepted)
        self.assertTrue(verdict.prediction_structure_exception_used)

    def test_prediction_change_cannot_hide_bitrate_reduction(self) -> None:
        value = baseline()
        candidate = QualitySnapshot(
            **{
                **value.__dict__,
                "prediction_structure": "IP_GOP_1S",
                "bitrate_bps": 80_000_000,
            }
        )
        verdict = compare_quality(
            value,
            candidate,
            prediction_change_reason="sustainable_realtime",
        )
        self.assertFalse(verdict.accepted)
        self.assertIn("bitrate_bps", {item.field for item in verdict.violations})

    def test_nonfinite_numeric_value_cannot_bypass_floor_comparison(self) -> None:
        value = baseline()
        candidate = QualitySnapshot(**{**value.__dict__, "target_fps": float("nan")})
        verdict = compare_quality(value, candidate)
        self.assertFalse(verdict.accepted)
        self.assertIn("target_fps", {item.field for item in verdict.violations})

    def test_nonfinite_json_is_rejected_at_input_boundary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"target_fps": NaN}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-finite JSON"):
                QualitySnapshot.from_json(path)


if __name__ == "__main__":
    unittest.main()
