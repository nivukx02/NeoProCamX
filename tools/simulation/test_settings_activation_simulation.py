from __future__ import annotations

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from settings_activation_simulation import (  # noqa: E402
    ActivationClass,
    CONVERTER_ENDPOINTS,
    SETTINGS,
    SETTINGS_ACTION_ENDPOINTS,
    SettingsStateModel,
    extract_kotlin_function,
    parse_meta_catalog,
    run_settings_activation_audit,
)


class SettingsActivationSimulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = run_settings_activation_audit(ROOT)

    def test_all_twenty_six_interactive_settings_are_covered(self) -> None:
        self.assertEqual(26, len(SETTINGS))
        self.assertEqual(26, len({setting.setting_id for setting in SETTINGS}))
        self.assertEqual(26, self.report["settings_checked"])
        self.assertEqual(26, self.report["interactive_ui_control_sites_discovered"])

    def test_every_declared_option_value_is_applied_to_the_contract_model(self) -> None:
        expected = sum(len(setting.values) for setting in SETTINGS)
        expected_writes = sum(
            len(setting.preference_keys) * len(setting.values) for setting in SETTINGS
        )
        self.assertGreater(expected, len(SETTINGS))
        self.assertEqual(104, expected)
        self.assertEqual(expected, self.report["modeled_option_value_applications"])
        self.assertEqual(expected, self.report["modeled_state_assignments"])
        self.assertEqual(expected, self.report["modeled_effect_routes"])
        self.assertEqual(expected_writes, self.report["modeled_preference_projections"])
        self.assertEqual(
            expected,
            sum(self.report["modeled_effect_route_counts"].values()),
        )
        boolean_settings = [setting for setting in SETTINGS if setting.option_type == "BOOLEAN"]
        self.assertTrue(boolean_settings)
        self.assertTrue(all(setting.values == ("true", "false") for setting in boolean_settings))

    def test_transition_model_applies_values_and_rejects_undeclared_input(self) -> None:
        model = SettingsStateModel()
        for setting in SETTINGS:
            for value in setting.values:
                result = model.apply(setting, value)
                self.assertEqual(value, model.state[setting.setting_id])
                self.assertEqual(result.dispatch_event, model.dispatch_queues[setting.activation][-1])
                for key, projected_value in result.preference_updates:
                    self.assertEqual(projected_value, model.preferences[key])
        self.assertEqual(sum(len(setting.values) for setting in SETTINGS), len(model.transitions))
        with self.assertRaises(ValueError):
            model.apply(SETTINGS[0], "UNDECLARED")

    def test_all_production_contracts_pass(self) -> None:
        self.assertEqual([], self.report["violations"])
        self.assertEqual("PASS", self.report["status"])
        self.assertTrue(all(row["status"] == "PASS" for row in self.report["settings"]))

    def test_dispatch_deadline_is_a_declaration_not_synthetic_timing_evidence(self) -> None:
        for setting in SETTINGS:
            self.assertLessEqual(setting.max_dispatch_latency_ms, 1_000)
        self.assertEqual(1_000, self.report["maximum_declared_app_dispatch_deadline_ms"])
        self.assertEqual("NOT_MEASURED_ON_HOST", self.report["timing_evidence"])

    def test_every_activation_semantic_is_represented(self) -> None:
        self.assertEqual(set(ActivationClass), {setting.activation for setting in SETTINGS})
        preview_aspect = next(
            setting for setting in SETTINGS if setting.setting_id == "camera.previewAspect"
        )
        self.assertEqual(ActivationClass.IMMEDIATE_STATE, preview_aspect.activation)

    def test_converter_endpoint_tokens_are_covered_and_background_dispatched(self) -> None:
        self.assertEqual(7, len(CONVERTER_ENDPOINTS))
        self.assertEqual(7, self.report["converter_endpoint_tokens_checked"])
        self.assertGreaterEqual(
            self.report["converter_action_choice_call_sites"],
            len(CONVERTER_ENDPOINTS),
        )
        self.assertGreaterEqual(
            self.report["converter_expanded_action_branches"],
            self.report["converter_action_choice_call_sites"],
        )
        self.assertEqual(4, self.report["converter_thumbnail_action_bindings"])
        self.assertEqual(1, len(SETTINGS_ACTION_ENDPOINTS))
        self.assertEqual(1, self.report["settings_action_endpoint_tokens_checked"])

    def test_metadata_parser_matches_catalog(self) -> None:
        source = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionMeta.kt"
        ).read_text(encoding="utf-8")
        option_catalog = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt"
        ).read_text(encoding="utf-8")
        parsed = parse_meta_catalog(source, option_catalog)
        self.assertEqual({setting.setting_id for setting in SETTINGS}, set(parsed))

    def test_handler_extractor_handles_block_and_expression_bodies(self) -> None:
        source = (
            ROOT / "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("camera.setFlash(f)", extract_kotlin_function(source, "setFlash"))
        self.assertIn("syncActivePreviewFrameRate()", extract_kotlin_function(source, "setFPS"))

    def test_report_is_honest_about_device_only_evidence(self) -> None:
        limitations = " ".join(self.report["device_scope_not_proven"])
        self.assertIn("Camera2/HAL", limitations)
        self.assertIn("dropped frames", limitations)


if __name__ == "__main__":
    unittest.main(verbosity=2)
