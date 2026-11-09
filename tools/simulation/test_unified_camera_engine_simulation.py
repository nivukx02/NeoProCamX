from __future__ import annotations

import itertools
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from unified_camera_engine_simulation import (  # noqa: E402
    AutomaticSignals,
    ControlRequest,
    DetailProfile,
    ExposureAuthority,
    FocusAuthority,
    LightingCase,
    ManualCase,
    ManualControls,
    MatrixCase,
    MotionCase,
    NdrMode,
    Rejection,
    SensorLimits,
    TransportCapabilities,
    VideoMode,
    WhiteBalanceAuthority,
    control_matrix_cases,
    ndr_packet_routes,
    overlay_accepts_mask,
    overlay_level,
    overlay_should_draw,
    precision_proofs,
    request_for_case,
    resolve_controls,
    resolve_delivery_admission,
    resolve_ndr_profile,
    run_reference_matrix,
    transport_capability_matrix,
    validate_control_decision,
    validate_ndr_routes,
    validate_overlay_matrix,
    validate_software_ae_priority_state_machine,
    verify_source_contracts,
)


class UnifiedCameraEngineSimulationTest(unittest.TestCase):
    def test_control_matrix_is_complete_and_every_decision_satisfies_invariants(self) -> None:
        cases = list(control_matrix_cases())
        self.assertEqual(5_760, len(cases))
        signatures = {
            (
                case.mode,
                case.detail_profile,
                case.lighting,
                case.motion,
                case.manual_case,
                case.manual_sensor,
                case.fixed_white_balance,
                case.manual_focus,
            )
            for case in cases
        }
        self.assertEqual(len(cases), len(signatures))
        for case in cases:
            request = request_for_case(case)
            validate_control_decision(request, resolve_controls(request))

    def test_all_modes_and_detail_profiles_share_the_unified_engine(self) -> None:
        for mode, profile in itertools.product(tuple(VideoMode), tuple(DetailProfile)):
            case = MatrixCase(
                mode,
                profile,
                LightingCase.DAYLIGHT,
                MotionCase.TRACKING,
                ManualCase.AUTO,
                True,
                True,
                True,
            )
            decision = resolve_controls(request_for_case(case))
            self.assertEqual(mode, decision.mode)
            self.assertEqual(profile, decision.detail_profile)
            self.assertIn(decision.exposure.authority, tuple(ExposureAuthority))
            self.assertIn(decision.white_balance.authority, tuple(WhiteBalanceAuthority))
            self.assertIn(decision.focus.authority, tuple(FocusAuthority))

    def test_manual_authority_is_preserved_bounded_and_honest_about_unsupported_controls(self) -> None:
        supported = MatrixCase(
            VideoMode.NDR,
            DetailProfile.EXTREME_DETAIL,
            LightingCase.BACKLIT_HIGHLIGHTS,
            MotionCase.TRACKING,
            ManualCase.FULL_MANUAL_OUT_OF_RANGE,
            True,
            True,
            True,
        )
        decision = resolve_controls(request_for_case(supported))
        self.assertEqual(ExposureAuthority.USER_MANUAL_SENSOR, decision.exposure.authority)
        self.assertEqual(3_200, decision.exposure.iso)
        self.assertEqual(40_000_000, decision.exposure.exposure_time_ns)
        self.assertEqual(WhiteBalanceAuthority.USER_FIXED, decision.white_balance.authority)
        self.assertEqual(10_000, decision.white_balance.fixed_kelvin)
        self.assertEqual(150, decision.white_balance.fixed_tint)
        self.assertEqual(FocusAuthority.USER_MANUAL, decision.focus.authority)
        self.assertEqual(10.0, decision.focus.focus_diopters)
        self.assertIn(Rejection.FULL_MANUAL_EXPOSURE_IGNORES_EV, decision.rejections)

        unsupported = resolve_controls(
            request_for_case(
                MatrixCase(
                    VideoMode.NDR,
                    DetailProfile.EXTREME_DETAIL,
                    LightingCase.BACKLIT_HIGHLIGHTS,
                    MotionCase.TRACKING,
                    ManualCase.PARTIAL_MANUAL,
                    False,
                    False,
                    False,
                )
            )
        )
        self.assertEqual(ExposureAuthority.PLATFORM_AE, unsupported.exposure.authority)
        self.assertEqual(WhiteBalanceAuthority.PLATFORM_AUTO, unsupported.white_balance.authority)
        self.assertEqual(FocusAuthority.CONTINUOUS_AUTO, unsupported.focus.authority)
        self.assertTrue(
            {
                Rejection.MANUAL_SENSOR_UNSUPPORTED,
                Rejection.FIXED_WHITE_BALANCE_UNSUPPORTED,
                Rejection.MANUAL_FOCUS_UNSUPPORTED,
            }.issubset(unsupported.rejections)
        )

    def test_focus_profile_character_is_bounded_and_ndr_uses_anti_hunt_slew(self) -> None:
        results = {}
        for mode, profile in itertools.product((VideoMode.VIDEO, VideoMode.NDR), tuple(DetailProfile)):
            case = MatrixCase(
                mode,
                profile,
                LightingCase.DAYLIGHT,
                MotionCase.TRACKING,
                ManualCase.AUTO,
                True,
                True,
                True,
            )
            results[mode, profile] = resolve_controls(request_for_case(case)).focus.focus_diopters
        for profile in DetailProfile:
            self.assertLess(results[VideoMode.NDR, profile], results[VideoMode.VIDEO, profile])
        self.assertLess(
            results[VideoMode.VIDEO, DetailProfile.NATURAL_DETAIL],
            results[VideoMode.VIDEO, DetailProfile.FOCUSED_DETAIL],
        )
        self.assertLess(
            results[VideoMode.VIDEO, DetailProfile.FOCUSED_DETAIL],
            results[VideoMode.VIDEO, DetailProfile.EXTREME_DETAIL],
        )

    def test_focus_deadband_survives_unavailable_timing_without_hiding_real_changes(self) -> None:
        for mode, profile, elapsed in itertools.product(
            tuple(VideoMode),
            tuple(DetailProfile),
            (0.0, -0.1, float("nan"), float("inf")),
        ):
            def resolve_focus(target: float) -> float | None:
                request = ControlRequest(
                    generation=86,
                    mode=mode,
                    detail_profile=profile,
                    target_fps=25,
                    recording=True,
                    limits=SensorLimits(),
                    manual=ManualControls(),
                    automatic=AutomaticSignals(
                        ai_focus_diopters=target,
                        ai_focus_confidence=1.0,
                        previous_focus_diopters=2.0,
                        elapsed_seconds=elapsed,
                    ),
                )
                return resolve_controls(request).focus.focus_diopters

            self.assertEqual(2.0, resolve_focus(2.003))
            self.assertEqual(2.05, resolve_focus(2.05))

    def test_software_ae_priority_temporal_contracts(self) -> None:
        self.assertEqual(14, validate_software_ae_priority_state_machine())

    def test_invalid_software_priority_evidence_fails_closed_without_boundary_clamping(self) -> None:
        iso_priority = resolve_controls(
            ControlRequest(
                generation=93,
                mode=VideoMode.VIDEO,
                detail_profile=DetailProfile.NATURAL_DETAIL,
                target_fps=25,
                recording=True,
                limits=SensorLimits(supports_software_iso_priority=True),
                manual=ManualControls(iso=400),
                automatic=AutomaticSignals(
                    software_iso_priority_exposure_time_ns=10**18,
                ),
            )
        )
        self.assertEqual(ExposureAuthority.PLATFORM_AE, iso_priority.exposure.authority)
        self.assertIn(
            Rejection.INVALID_SOFTWARE_ISO_PRIORITY_EVIDENCE,
            iso_priority.rejections,
        )

        shutter_priority = resolve_controls(
            ControlRequest(
                generation=94,
                mode=VideoMode.PRO,
                detail_profile=DetailProfile.FOCUSED_DETAIL,
                target_fps=25,
                recording=True,
                limits=SensorLimits(supports_software_shutter_priority=True),
                manual=ManualControls(exposure_time_ns=10_000_000),
                automatic=AutomaticSignals(software_shutter_priority_iso=10**9),
            )
        )
        self.assertEqual(ExposureAuthority.PLATFORM_AE, shutter_priority.exposure.authority)
        self.assertIn(
            Rejection.INVALID_SOFTWARE_SHUTTER_PRIORITY_EVIDENCE,
            shutter_priority.rejections,
        )

    def test_transport_capabilities_are_exhaustive_and_never_substitute_a_mode(self) -> None:
        capabilities = tuple(transport_capability_matrix())
        self.assertEqual(64, len(capabilities))
        count = 0
        for mode, caps in itertools.product(tuple(VideoMode), capabilities):
            admission = resolve_delivery_admission(mode, caps)
            self.assertEqual(mode, admission.requested_mode)
            if admission.admitted:
                self.assertEqual((), admission.failures)
            else:
                self.assertTrue(admission.failures)
            count += 1
        self.assertEqual(320, count)

    def test_missing_p010_gpu_or_main10_rejects_every_processed_mode(self) -> None:
        full = TransportCapabilities(True, True, True, True, True, True)
        for field in ("camera_p010", "gpu_ten_bit", "encoder_main10"):
            values = {**full.__dict__, field: False}
            caps = TransportCapabilities(**values)
            for mode in (VideoMode.VIDEO, VideoMode.PRO, VideoMode.NDR, VideoMode.LOG):
                admission = resolve_delivery_admission(mode, caps)
                self.assertFalse(admission.admitted)
                self.assertIn("TEN_BIT_TRANSPORT_UNAVAILABLE", admission.failures)

    def test_every_eight_bit_stage_fails_the_precision_proof(self) -> None:
        proofs = list(precision_proofs())
        self.assertEqual(24, len(proofs))
        for proof, expected in proofs:
            self.assertEqual(expected, proof.strict_ten_bit())
            if 8 in (proof.source_bits, proof.processing_bits, proof.storage_bits, proof.encoder_bits):
                self.assertFalse(proof.strict_ten_bit())

    def test_ndr_detail_changes_only_bounded_detail_and_all_sinks_receive_same_packet(self) -> None:
        self.assertEqual(18, validate_ndr_routes())
        for mode in NdrMode:
            routes = {
                profile: ndr_packet_routes(mode, profile)
                for profile in DetailProfile
            }
            for profile, sinks in routes.items():
                self.assertIs(sinks["RAW_MONITOR"], sinks["VISIBLE_PREVIEW"])
                self.assertIs(sinks["VISIBLE_PREVIEW"], sinks["RECORDING"])
                self.assertEqual(resolve_ndr_profile(mode, profile), sinks["RECORDING"])
            natural = routes[DetailProfile.NATURAL_DETAIL]["RECORDING"]
            extreme = routes[DetailProfile.EXTREME_DETAIL]["RECORDING"]
            self.assertEqual(natural.signal_gamma, extreme.signal_gamma)
            self.assertEqual(natural.chroma_boost, extreme.chroma_boost)
            self.assertNotEqual(natural.local_detail_strength, extreme.local_detail_strength)

    def test_bitmap_overlay_gate_is_fail_closed_for_levels_payloads_and_lifecycle(self) -> None:
        self.assertEqual(84, validate_overlay_matrix())
        self.assertEqual(0.0, overlay_level(False, False, 1.0))
        self.assertEqual(0.0, overlay_level(True, True, 1.0))
        self.assertFalse(overlay_accepts_mask(True, False, 256, 144, 256 * 144 - 1))
        self.assertFalse(overlay_should_draw(True, False, 1.0, False))
        self.assertTrue(overlay_should_draw(True, False, 1.0, True))

    def test_python_model_is_anchored_to_production_source_contracts(self) -> None:
        contracts = verify_source_contracts(ROOT)
        self.assertEqual(8, len(contracts))
        self.assertTrue(all(contract.passed for contract in contracts), contracts)

    def test_report_counts_every_checkpoint_and_does_not_claim_device_quality(self) -> None:
        report = run_reference_matrix(ROOT)
        self.assertEqual("PASS", report["status"])
        self.assertEqual("DETERMINISTIC_HOST_CONTRACT_SIMULATION_ONLY", report["scope"])
        self.assertEqual(6_232, report["total_cases"])
        self.assertIn("subjective cinema/IMAX image quality", report["not_proven_without_device"])
        self.assertIn("physical 4096x3072/4K 25 fps cadence and zero dropped frames", report["not_proven_without_device"])


if __name__ == "__main__":
    unittest.main()
