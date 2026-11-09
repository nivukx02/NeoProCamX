from pathlib import Path
import unittest

from sensor_linear_ndr_tone_lut_simulation import (
    NDR10,
    NIVUKX,
    parity_report,
    production_contracts,
    small_angle_error,
)


ROOT = Path(__file__).resolve().parents[2]


class SensorLinearNdrToneLutSimulationTest(unittest.TestCase):
    def test_fp16_tone_and_pq_tables_stay_inside_one_10_bit_code(self) -> None:
        for profile in (NDR10, NIVUKX):
            with self.subTest(profile=profile):
                report = parity_report(profile)
                self.assertLessEqual(report["p99_code_error"], 0.5, report)
                self.assertLessEqual(report["max_code_error"], 1.0, report)

    def test_small_angle_hue_polynomial_is_below_fp32_noise_budget(self) -> None:
        sin_error, cos_error = small_angle_error()
        self.assertLess(sin_error, 2e-8)
        self.assertLess(cos_error, 2e-7)

    def test_production_wiring_retains_analytic_fallback_and_color_authority(self) -> None:
        contracts = production_contracts(ROOT)
        self.assertTrue(all(contracts.values()), contracts)


if __name__ == "__main__":
    unittest.main(verbosity=2)
