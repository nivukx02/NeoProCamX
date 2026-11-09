from __future__ import annotations

import math
import sys
import unittest
from dataclasses import replace
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from neolog_elite_conversion_simulation import (  # noqa: E402
    CHROMA_SPAN,
    CHROMA_ZERO,
    DITHER_LSB,
    EliteEvidence,
    EvidenceRejected,
    Frame,
    KR,
    PackingFormat,
    QuantizationTrace,
    audit_evidence,
    dither_lsb,
    enforce_evidence,
    final_quantize_10,
    pack_p010,
    pack_v210,
    production_source_contracts,
    reference_evidence,
    reference_frames,
    rgb_to_ycbcr_continuous,
    run_reference_simulation,
    simulate_clip,
    tpdf_unit,
)


class NeoLogEliteConversionMathTest(unittest.TestCase):
    def test_rgb_to_ycbcr_remains_continuous_until_the_final_quantizer(self) -> None:
        rgb = (0.1234567, 0.4567891, 0.9876543)
        ycbcr = rgb_to_ycbcr_continuous(rgb)
        expected_luma = KR * rgb[0] + (1.0 - KR - 0.0593) * rgb[1] + 0.0593 * rgb[2]
        prequantized = tuple(round(channel * 1023.0) / 1023.0 for channel in rgb)
        prequantized_ycbcr = rgb_to_ycbcr_continuous(prequantized)

        self.assertAlmostEqual(expected_luma, ycbcr[0], places=15)
        self.assertGreater(abs(ycbcr[0] - prequantized_ycbcr[0]), 1e-8)
        perturbed = rgb_to_ycbcr_continuous((rgb[0] + 1e-7, rgb[1], rgb[2]))
        self.assertAlmostEqual(KR * 1e-7, perturbed[0] - ycbcr[0], places=14)

    def test_tpdf_is_repeatable_triangular_and_strictly_bounded_to_half_lsb(self) -> None:
        first = [dither_lsb(index) for index in range(20_000)]
        second = [dither_lsb(index) for index in range(20_000)]

        self.assertEqual(first, second)
        self.assertTrue(all(-DITHER_LSB < value < DITHER_LSB for value in first))
        self.assertLess(min(first), -0.45)
        self.assertGreater(max(first), 0.45)
        self.assertAlmostEqual(0.0, sum(first) / len(first), delta=0.01)
        self.assertTrue(all(-1.0 < tpdf_unit(index) < 1.0 for index in range(20_000)))

    def test_p010_has_no_pre_matrix_quantization_and_one_final_call_per_component(self) -> None:
        frame = reference_frames(count=1, width=5, height=3)[0]
        packed = pack_p010(frame)
        chroma_samples = ((frame.width + 1) // 2) * ((frame.height + 1) // 2)

        self.assertEqual(frame.width * frame.height, len(packed.luma_codes))
        self.assertEqual(chroma_samples, len(packed.chroma_codes))
        self.assertEqual(0, packed.pre_matrix_quantizations)
        self.assertEqual(1, packed.final_quantization_boundaries)
        self.assertEqual(frame.width * frame.height + 2 * chroma_samples, packed.final_quantizations)
        self.assertTrue(packed.single_final_quantization_proven)
        self.assertTrue(all(0 <= code <= 1023 for code in packed.luma_codes))
        self.assertTrue(
            all(0 <= code <= 1023 for pair in packed.chroma_codes for code in pair)
        )

    def test_v210_averages_saturated_color_across_real_pairs_and_not_even_pixel_only(self) -> None:
        red = (1.0, 0.0, 0.0)
        blue = (0.0, 0.0, 1.0)
        green = (0.0, 1.0, 0.0)
        frame = Frame(index=0, width=3, height=1, rgb=(red, blue, green))
        packed = pack_v210(frame)
        red_ycbcr = rgb_to_ycbcr_continuous(red)
        blue_ycbcr = rgb_to_ycbcr_continuous(blue)
        green_ycbcr = rgb_to_ycbcr_continuous(green)
        average_cb = (red_ycbcr[1] + blue_ycbcr[1]) / 2.0
        average_cr = (red_ycbcr[2] + blue_ycbcr[2]) / 2.0
        first_dither_index = frame.width * frame.height
        expected_pair = (
            final_quantize_10(
                average_cb * CHROMA_SPAN + CHROMA_ZERO,
                first_dither_index,
                QuantizationTrace(),
            ),
            final_quantize_10(
                average_cr * CHROMA_SPAN + CHROMA_ZERO,
                first_dither_index + 1,
                QuantizationTrace(),
            ),
        )
        even_only_cb = final_quantize_10(
            red_ycbcr[1] * CHROMA_SPAN + CHROMA_ZERO,
            first_dither_index,
            QuantizationTrace(),
        )
        odd_edge_dither_index = first_dither_index + 2
        expected_odd_edge = (
            final_quantize_10(
                green_ycbcr[1] * CHROMA_SPAN + CHROMA_ZERO,
                odd_edge_dither_index,
                QuantizationTrace(),
            ),
            final_quantize_10(
                green_ycbcr[2] * CHROMA_SPAN + CHROMA_ZERO,
                odd_edge_dither_index + 1,
                QuantizationTrace(),
            ),
        )

        self.assertEqual(expected_pair, packed.chroma_codes[0])
        self.assertNotEqual(even_only_cb, packed.chroma_codes[0][0])
        self.assertEqual(expected_odd_edge, packed.chroma_codes[1])
        self.assertTrue(packed.single_final_quantization_proven)

    def test_packers_are_byte_code_deterministic_for_identical_frames(self) -> None:
        frame = reference_frames(count=1, width=9, height=7)[0]
        self.assertEqual(pack_p010(frame), pack_p010(frame))
        self.assertEqual(pack_v210(frame), pack_v210(frame))

    def test_final_quantizer_rejects_multiple_boundary_evidence(self) -> None:
        trace = QuantizationTrace(final_boundary_count=2)
        with self.assertRaisesRegex(ValueError, "exactly one final quantization boundary"):
            final_quantize_10(512.25, 0, trace)


class NeoLogEliteConversionLifecycleTest(unittest.TestCase):
    def test_all_frames_order_geometry_and_luma_samples_are_preserved(self) -> None:
        frames = reference_frames(count=17, width=7, height=5)
        for packing in PackingFormat:
            with self.subTest(packing=packing):
                clip = simulate_clip(frames, packing)
                self.assertTrue(clip.frame_count_preserved)
                self.assertTrue(clip.frame_order_preserved)
                self.assertTrue(clip.geometry_preserved)
                self.assertTrue(clip.every_frame_single_quantized)
                self.assertEqual(
                    sum(frame.width * frame.height for frame in frames),
                    sum(len(frame.luma_codes) for frame in clip.output_frames),
                )

    def test_frame_constructor_rejects_invalid_or_non_finite_geometry_payloads(self) -> None:
        with self.assertRaises(ValueError):
            Frame(index=0, width=2, height=2, rgb=((0.0, 0.0, 0.0),))
        with self.assertRaises(ValueError):
            Frame(index=0, width=0, height=2, rgb=())
        with self.assertRaises(ValueError):
            Frame(index=0, width=1, height=1, rgb=((math.nan, 0.0, 0.0),))


class EliteEvidenceFailClosedTest(unittest.TestCase):
    def setUp(self) -> None:
        clip = simulate_clip(reference_frames(count=5), PackingFormat.V210_422)
        self.baseline = reference_evidence(clip)

    def test_complete_evidence_passes(self) -> None:
        self.assertEqual((), audit_evidence(self.baseline))
        self.assertEqual(self.baseline, enforce_evidence(self.baseline))

    def test_every_quality_or_integrity_regression_is_rejected(self) -> None:
        regressions: dict[str, EliteEvidence] = {
            "geometry": replace(self.baseline, output_width=self.baseline.output_width - 1),
            "fps": replace(self.baseline, output_fps=24),
            "frame_count": replace(self.baseline, output_frame_count=4),
            "unpaired_frame_evidence": replace(self.baseline, output_frame_count=None),
            "eight_bit_output": replace(self.baseline, output_bit_depth=8),
            "low_precision": replace(self.baseline, working_precision_bits=16),
            "unknown_linearity": replace(self.baseline, working_signal_linear=None),
            "double_tone_map": replace(self.baseline, tone_map_applications=2),
            "double_quantization": replace(self.baseline, final_quantization_applications=2),
            "missing_output_transfer": replace(self.baseline, output_transfer_applications=0),
            "missing_metadata_proof": replace(self.baseline, metadata_preserved=None),
            "bad_color_evidence": replace(self.baseline, color_evidence_consistent=False),
            "eight_bit_fallback": replace(self.baseline, used_eight_bit_fallback=True),
            "quality_for_speed": replace(self.baseline, quality_reduced_for_speed=True),
            "destructive_fallback": replace(self.baseline, destructive_fallback_used=True),
            "invented_uncompressed_bitrate": replace(self.baseline, bitrate_evidence_present=True),
            "compressed_without_bitrate": replace(self.baseline, compressed_output=True),
            "compressed_below_floor": replace(
                self.baseline,
                compressed_output=True,
                bitrate_evidence_present=True,
                bitrate_floor_satisfied=False,
            ),
        }
        for name, evidence in regressions.items():
            with self.subTest(regression=name):
                self.assertTrue(audit_evidence(evidence), name)
                with self.assertRaises(EvidenceRejected):
                    enforce_evidence(evidence)


class NeoLogEliteProductionContractTest(unittest.TestCase):
    def test_kotlin_source_keeps_the_proven_conversion_and_fail_closed_contracts(self) -> None:
        contracts = production_source_contracts(ROOT)
        self.assertTrue(all(contracts.values()), contracts)

    def test_reference_report_is_explicitly_scoped_and_passes(self) -> None:
        report = run_reference_simulation(ROOT)
        self.assertEqual("PASS", report["status"])
        self.assertIn("host math", report["scope"])
        self.assertTrue(all(report["source_contracts"].values()))
        self.assertEqual([], report["baseline_evidence_rejections"])
        self.assertLess(report["tpdf_sample_bounds_lsb"][0], 0.0)
        self.assertGreater(report["tpdf_sample_bounds_lsb"][1], 0.0)
        self.assertTrue(any("No Camera2" in item for item in report["limitations"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
