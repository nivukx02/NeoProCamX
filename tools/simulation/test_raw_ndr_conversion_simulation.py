from pathlib import Path
import unittest

from raw_ndr_conversion_simulation import (
    DIFFUSE_WHITE_NITS,
    NDR10_PLUS,
    NIVUKX_VISION,
    conversion_work_estimate,
    monotonic,
    payload_identity,
    pq_encode_nits,
    production_source_contracts,
    quantize_p010_luma,
    raw_neutral_to_pq,
    raw_sdr_monitor,
)


ROOT = Path(__file__).resolve().parents[2]


class RawNdrConversionSimulationTest(unittest.TestCase):
    def test_neofusion_removes_per_frame_workspace_churn_and_second_output_write(self) -> None:
        legacy = conversion_work_estimate(frame_count=600, output_bytes=8_000_000_000, optimized=False)
        optimized = conversion_work_estimate(frame_count=600, output_bytes=8_000_000_000, optimized=True)
        self.assertEqual(legacy.output_write_bytes // 2, optimized.output_write_bytes)
        self.assertEqual(1200, legacy.kotlin_direct_buffer_allocations)
        self.assertEqual(2, optimized.kotlin_direct_buffer_allocations)
        self.assertEqual(600, legacy.native_readback_allocations)
        self.assertEqual(1, optimized.native_readback_allocations)

    def test_indoor_and_outdoor_raw_monitor_is_finite_viewable_sdr(self) -> None:
        samples = [0.0, 0.005, 0.018, 0.18, 0.82, 1.0, 4.0, 16.0]
        displayed = [raw_sdr_monitor(value) for value in samples]
        self.assertTrue(monotonic(displayed))
        self.assertEqual(0.0, displayed[0])
        self.assertGreater(displayed[3], 0.40)
        self.assertLessEqual(displayed[-1], 1.0)
        self.assertLess(displayed[-1], 1.0)  # highlight shoulder, not hard white clipping

    def test_both_profiles_anchor_diffuse_white_to_203_nits(self) -> None:
        expected = pq_encode_nits(DIFFUSE_WHITE_NITS)
        for profile in (NDR10_PLUS, NIVUKX_VISION):
            self.assertAlmostEqual(expected, raw_neutral_to_pq(0.18, profile), places=9)
            self.assertEqual(quantize_p010_luma(expected), quantize_p010_luma(raw_neutral_to_pq(0.18, profile)))

    def test_profiles_are_monotonic_bounded_and_distinct_in_highlights(self) -> None:
        scenes = [index / 100.0 for index in range(0, 1601)]
        ndr = [raw_neutral_to_pq(value, NDR10_PLUS) for value in scenes]
        nivukx = [raw_neutral_to_pq(value, NIVUKX_VISION) for value in scenes]
        self.assertTrue(monotonic(ndr))
        self.assertTrue(monotonic(nivukx))
        self.assertTrue(all(0.0 <= value <= 1.0 for value in ndr + nivukx))
        self.assertNotEqual(quantize_p010_luma(ndr[-1]), quantize_p010_luma(nivukx[-1]))

    def test_raw_payload_identity_is_unchanged(self) -> None:
        payload = bytes((index * 37) & 0xFF for index in range(8192))
        before, after = payload_identity(payload)
        self.assertEqual(before, after)

    def test_production_wiring_is_complete(self) -> None:
        contracts = production_source_contracts(ROOT)
        self.assertTrue(all(contracts.values()), contracts)


if __name__ == "__main__":
    unittest.main(verbosity=2)
