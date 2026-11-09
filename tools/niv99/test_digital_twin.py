from __future__ import annotations

import unittest

import digital_twin as niv99


class Niv99DigitalTwinTest(unittest.TestCase):
    def test_noise_variance_is_monotonic_and_channel_calibrated(self) -> None:
        model = niv99.NoiseModel((0.002, 0.001, 0.003), (1e-5, 2e-5, 3e-5))
        for channel in range(3):
            values = [model.variance(channel, step / 100.0) for step in range(101)]
            self.assertTrue(all(values[index] <= values[index + 1] for index in range(100)))
        self.assertGreater(model.variance(2, 0.5), model.variance(1, 0.5))

    def test_manual_and_lock_authority_override_automatic_estimate(self) -> None:
        estimate = niv99.IlluminantEstimate(1.4, 0.7, 0.71, 1.0, 1.43, 0.9, 4, 5)
        manual = niv99.resolve_white_balance(
            niv99.WhiteBalanceIntent(manual_gains=(1.2, 1.0, 0.8), locked_gains=(1.8, 1.0, 0.6)),
            estimate,
        )
        self.assertEqual("MANUAL", manual.authority)
        self.assertEqual((1.2, 1.0, 0.8), manual.gains)
        locked = niv99.resolve_white_balance(
            niv99.WhiteBalanceIntent(locked_gains=(1.1, 1.0, 0.9)),
            estimate,
        )
        self.assertEqual("AWB_LOCK", locked.authority)
        self.assertEqual((1.1, 1.0, 0.9), locked.gains)

    def test_noise_generation_is_seed_deterministic_and_source_is_immutable(self) -> None:
        source = niv99.make_reference_scene(24, 16)
        before = source.checksum()
        model = niv99.NoiseModel((0.003, 0.002, 0.004), (1e-5, 1e-5, 2e-5))
        first = niv99.add_heteroscedastic_noise(source, model, 99)
        second = niv99.add_heteroscedastic_noise(source, model, 99)
        third = niv99.add_heteroscedastic_noise(source, model, 100)
        self.assertEqual(first.data, second.data)
        self.assertNotEqual(first.data, third.data)
        self.assertEqual(before, source.checksum())

    def test_robust_white_balance_reduces_neutral_chromaticity_error(self) -> None:
        reference = niv99.make_reference_scene(48, 32)
        cast = niv99.apply_illuminant(reference, (1.35, 1.0, 0.68))
        model = niv99.NoiseModel((0.0012, 0.0010, 0.0014), (3e-6, 3e-6, 4e-6))
        noisy = niv99.add_heteroscedastic_noise(cast, model, 29)
        columns, rows, statistics = niv99.tile_statistics(noisy, model)
        estimate = niv99.estimate_illuminant(statistics, (1.2, 1.0, 0.8))
        global_result = niv99.resolve_white_balance(niv99.WhiteBalanceIntent(), estimate)
        field = niv99.estimate_local_illuminants(columns, rows, statistics, global_result)
        corrected = niv99.apply_local_white_balance(noisy, field, global_result)
        self.assertLess(
            niv99.chromaticity_error(corrected, reference),
            niv99.chromaticity_error(noisy, reference),
        )
        self.assertGreater(estimate.accepted_candidates, 1)

    def test_local_illuminant_field_is_bounded_and_complete(self) -> None:
        reference = niv99.make_reference_scene(40, 24)
        mixed = niv99.apply_illuminant(reference, (1.5, 1.0, 0.6), (0.8, 1.0, 1.3))
        model = niv99.NoiseModel((0.002, 0.0018, 0.0022), (1e-5, 1e-5, 1e-5))
        columns, rows, statistics = niv99.tile_statistics(mixed, model)
        estimate = niv99.estimate_illuminant(statistics)
        global_result = niv99.resolve_white_balance(niv99.WhiteBalanceIntent(), estimate)
        field = niv99.estimate_local_illuminants(columns, rows, statistics, global_result)
        self.assertEqual(columns * rows, len(field.gains))
        self.assertTrue(all(0.5 <= gain <= 2.0 for rgb in field.gains for gain in rgb))
        self.assertTrue(all(0.0 <= confidence <= 1.0 for confidence in field.confidence))

    def test_scene_cut_rejects_every_history_sample(self) -> None:
        current = niv99.make_reference_scene(24, 16)
        unrelated = niv99.apply_illuminant(current, (1.7, 0.7, 0.5))
        noise = niv99.NoiseModel((0.002, 0.002, 0.002), (1e-5, 1e-5, 1e-5))
        fused = niv99.temporal_fuse(current, unrelated, noise, 0.0, 0.0, 0.0, 0.8, scene_cut=True)
        self.assertEqual(current.data, fused.data)
        self.assertIsNot(current.data, fused.data)

    def test_correct_motion_alignment_beats_wrong_flow(self) -> None:
        reference = niv99.make_reference_scene(40, 24)
        noise = niv99.NoiseModel((0.003, 0.0025, 0.0035), (2e-5, 2e-5, 2e-5))
        current = niv99.add_heteroscedastic_noise(reference, noise, 7)
        shifted = niv99.shift_image(reference, 1.25, 0.0)
        history = niv99.add_heteroscedastic_noise(shifted, noise, 8)
        aligned = niv99.temporal_fuse(current, history, noise, 1.25, 0.0, 0.03, 0.65)
        wrong = niv99.temporal_fuse(current, history, noise, 0.0, 0.0, 0.03, 0.65)
        self.assertLess(
            niv99.mean_squared_error(reference, aligned),
            niv99.mean_squared_error(reference, wrong),
        )

    def test_profile_changes_filter_strength_without_changing_dimensions(self) -> None:
        focused = niv99.profile_control("Focused", 0.8, 0.4)
        natural = niv99.profile_control("Natural", 0.8, 0.4)
        extreme = niv99.profile_control("Extreme", 0.8, 0.4)
        self.assertGreater(focused.luma_strength, natural.luma_strength)
        self.assertGreater(natural.luma_strength, extreme.luma_strength)
        self.assertGreater(extreme.sharpening, focused.sharpening)
        source = niv99.make_reference_scene(25, 17)
        model = niv99.NoiseModel((0.002, 0.002, 0.002), (1e-5, 1e-5, 1e-5))
        output = niv99.spatial_denoise(source, model, "Extreme")
        self.assertEqual((source.width, source.height), (output.width, output.height))

    def test_reconstruction_is_halo_bounded(self) -> None:
        source = niv99.make_reference_scene(48, 32)
        reconstructed = niv99.reconstruct(source, 0.52, 0.001, halo_limit=0.015)
        self.assertLessEqual(niv99.edge_overshoot(source, reconstructed), 0.012)
        self.assertEqual((source.width, source.height), (reconstructed.width, reconstructed.height))

    def test_representative_static_and_motion_scenarios_improve_measured_noise(self) -> None:
        for scenario in (niv99.STANDARD_SCENARIOS[0], niv99.STANDARD_SCENARIOS[6]):
            _, metrics, _, _ = niv99.run_scenario(scenario)
            self.assertGreater(metrics.luma_snr_improvement_db, 0.0, scenario.name)
            self.assertGreater(metrics.chroma_snr_improvement_db, 0.0, scenario.name)
            self.assertGreaterEqual(metrics.gradient_retention, 0.90, scenario.name)
            self.assertLessEqual(metrics.clipping_increase, 0.001, scenario.name)
            self.assertGreater(metrics.ssim, 0.98, scenario.name)

    def test_scenario_suite_is_bit_deterministic(self) -> None:
        scenarios = niv99.STANDARD_SCENARIOS[:2]
        first = niv99.summarize_scenarios(scenarios)
        second = niv99.summarize_scenarios(scenarios)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
