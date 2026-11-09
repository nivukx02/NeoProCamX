from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.evidence_oracle import (
    CandidateIdentity,
    CheckpointEvidenceRecord,
    DeviceIdentity,
    EvidenceOracle,
    RecordingThresholds,
    analyze_ffprobe,
    analyze_runtime_log,
    verify_recording_wire,
)


SHA_A = "A" * 64
SHA_B = "B" * 64


class EvidenceOracleTest(unittest.TestCase):
    def test_physical_pass_requires_matching_candidate_and_real_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            evidence = workspace / "run" / "logcat.txt"
            evidence.parent.mkdir()
            evidence.write_text("device evidence\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-066",
                status="PASS",
                exact_test="microphone denied recording branch",
                candidate=CandidateIdentity(
                    SHA_A, SHA_A, SHA_B, "debug", "com.example", "1", "1"
                ),
                device=DeviceIdentity("SERIAL", "vivo", "I2405", "16", "36", "fp", "arm64-v8a"),
                measured_result="25.0 fps, video only",
                expected_threshold=">=23.75 fps, no audio",
                evidence_paths=("run/logcat.txt",),
                focused_validation="CP-066 focused flow",
                regression_validation="full device smoke",
                evidence_kind="physical_device",
                measurement_claims=("codec_fps",),
            )
            oracle = EvidenceOracle(workspace)
            result = oracle.validate(record)
            self.assertTrue(result.accepted, result.errors)
            output = oracle.seal(record, workspace / "sealed")
            self.assertTrue(output.is_file())
            self.assertEqual("neo-qa-hypergrid/evidence-record/v1", json.loads(output.read_text())["schema"])

    def test_host_simulation_cannot_claim_physical_fps(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            log = workspace / "simulation.txt"
            log.write_text("PASS\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-137",
                status="PASS",
                exact_test="digital twin",
                candidate=CandidateIdentity(
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "host",
                    "host",
                    "host",
                    "host",
                ),
                device=DeviceIdentity.host_only(),
                measured_result="modeled 25 fps",
                expected_threshold="25 fps",
                evidence_paths=("simulation.txt",),
                focused_validation="simulation",
                regression_validation="simulation suite",
                evidence_kind="simulation",
                measurement_claims=("displayed_fps",),
            )
            result = EvidenceOracle(workspace).validate(record)
            self.assertFalse(result.accepted)
            self.assertTrue(any("cannot prove physical" in error for error in result.errors))

    def test_physical_evidence_rejects_unavailable_identity_sentinels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            evidence = workspace / "device.txt"
            evidence.write_text("device evidence\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-066",
                status="PASS",
                exact_test="physical recording",
                candidate=CandidateIdentity(
                    "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "debug", "pkg", "1", "1"
                ),
                device=DeviceIdentity(
                    "UNAVAILABLE", "vivo", "I2405", "16", "36", "fp", "arm64-v8a"
                ),
                measured_result="recorded",
                expected_threshold="recording succeeds",
                evidence_paths=("device.txt",),
                focused_validation="focused",
                regression_validation="regression",
                evidence_kind="physical_device",
            )
            result = EvidenceOracle(workspace).validate(record)
            self.assertFalse(result.accepted)
            self.assertTrue(any("concrete SHA-256" in error for error in result.errors))
            self.assertTrue(any("device identity field is empty" in error for error in result.errors))

    def test_invalid_checkpoint_and_evidence_kind_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-999",
                status="PENDING",
                exact_test="",
                candidate=CandidateIdentity(
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "host",
                    "host",
                    "host",
                    "host",
                ),
                device=DeviceIdentity.host_only(),
                measured_result="",
                expected_threshold="",
                evidence_paths=(),
                remaining_blocker="not run",
                evidence_kind="invented",
            )
            result = EvidenceOracle(workspace).validate(record)
            self.assertFalse(result.accepted)
            self.assertIn("invalid checkpoint ID", result.errors)
            self.assertIn("invalid evidence kind: invented", result.errors)

    def test_failed_or_pending_records_require_truthful_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-220",
                status="PENDING",
                exact_test="",
                candidate=CandidateIdentity(
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "NOT_APPLICABLE",
                    "host",
                    "host",
                    "host",
                    "host",
                ),
                device=DeviceIdentity.host_only(),
                measured_result="",
                expected_threshold="",
                evidence_paths=(),
            )
            result = EvidenceOracle(workspace).validate(record)
            self.assertFalse(result.accepted)
            self.assertIn("PENDING record is missing its blocker/reason", result.errors)


class RecordingEvidenceTest(unittest.TestCase):
    def test_ffprobe_frames_prove_main10_cadence_and_p_frames(self) -> None:
        probe = {
            "streams": [
                {
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "profile": "Main 10",
                    "width": 3264,
                    "height": 2448,
                    "pix_fmt": "yuv420p10le",
                    "color_primaries": "bt2020",
                    "color_transfer": "smpte2084",
                    "color_space": "bt2020nc",
                    "avg_frame_rate": "25/1",
                    "bit_rate": "160000000",
                }
            ]
        }
        frame_probe = {
            "frames": [
                {
                    "best_effort_timestamp_time": f"{index * 0.04:.2f}",
                    "pict_type": "I" if index == 0 else "P",
                    "key_frame": 1 if index == 0 else 0,
                }
                for index in range(50)
            ]
        }
        metrics = analyze_ffprobe(probe, frame_probe)
        failures = verify_recording_wire(
            metrics,
            RecordingThresholds(
                width=3264,
                height=2448,
                minimum_bit_depth=10,
                target_fps=25,
                require_p_frames=True,
                require_audio=False,
                required_transfer="smpte2084",
                required_primaries="bt2020",
                required_profile_contains="Main 10",
                minimum_measured_bitrate_bps=150_000_000,
                maximum_measured_bitrate_bps=170_000_000,
            ),
        )
        self.assertEqual((), failures)
        self.assertAlmostEqual(25.0, metrics.measured_fps, places=4)
        self.assertEqual(49, metrics.p_frames)
        self.assertEqual("video_stream.bit_rate", metrics.measured_bitrate_source)

    def test_ffprobe_filters_audio_frames_and_parses_p010_depth(self) -> None:
        probe = {
            "streams": [
                {
                    "index": 0,
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "width": 1920,
                    "height": 1080,
                    "pix_fmt": "p010le",
                    "avg_frame_rate": "25/1",
                },
                {"index": 1, "codec_type": "audio", "codec_name": "aac"},
            ],
            "format": {"bit_rate": "999999999"},
        }
        video_frames = [
            {
                "media_type": "video",
                "stream_index": 0,
                "pts_time": str(index * 0.04),
                "pict_type": "I" if index == 0 else "P",
                "pkt_size": "1000",
            }
            for index in range(10)
        ]
        audio_frames = [
            {
                "media_type": "audio",
                "stream_index": 1,
                "pts_time": str(index * 0.02),
                "pkt_size": "200",
            }
            for index in range(20)
        ]
        metrics = analyze_ffprobe(probe, {"frames": video_frames + audio_frames})
        self.assertEqual(10, metrics.bit_depth)
        self.assertEqual(10, metrics.timestamped_frame_count)
        self.assertAlmostEqual(25.0, metrics.measured_fps, places=4)
        self.assertEqual(1, metrics.audio_tracks)
        self.assertEqual("video_frame_packet_sizes_over_pts_duration", metrics.measured_bitrate_source)
        self.assertLess(metrics.measured_bitrate_bps, 999_999_999)

    def test_excess_fps_is_not_accepted_as_target_cadence(self) -> None:
        probe = {
            "streams": [
                {
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "width": 1920,
                    "height": 1080,
                    "pix_fmt": "yuv420p10le",
                }
            ]
        }
        frames = {
            "frames": [
                {"pts_time": str(index * 0.02), "pict_type": "P"}
                for index in range(20)
            ]
        }
        failures = verify_recording_wire(
            analyze_ffprobe(probe, frames),
            RecordingThresholds(1920, 1080, 10, 25),
        )
        self.assertTrue(any("measured FPS" in failure and " > " in failure for failure in failures))

    def test_invalid_nonfinite_target_is_rejected(self) -> None:
        probe = {
            "streams": [
                {
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "width": 1920,
                    "height": 1080,
                    "pix_fmt": "yuv420p10le",
                }
            ]
        }
        with self.assertRaises(ValueError):
            verify_recording_wire(
                analyze_ffprobe(probe, {"frames": []}),
                RecordingThresholds(1920, 1080, 10, float("nan")),
            )

    def test_cp066_all_idr_120ms_output_fails(self) -> None:
        probe = {
            "streams": [
                {
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "width": 3264,
                    "height": 2448,
                    "pix_fmt": "yuv420p10le",
                    "avg_frame_rate": "25/1",
                }
            ]
        }
        frames = {
            "frames": [
                {"pts_time": str(index * 0.12), "pict_type": "I", "key_frame": 1}
                for index in range(43)
            ]
        }
        metrics = analyze_ffprobe(probe, frames)
        failures = verify_recording_wire(
            metrics,
            RecordingThresholds(3264, 2448, 10, 25, require_p_frames=True),
        )
        self.assertTrue(any("measured FPS" in failure for failure in failures))
        self.assertTrue(any("no P-frames" in failure for failure in failures))

    def test_stream_declared_fps_without_frame_timestamps_is_not_cadence_proof(self) -> None:
        probe = {
            "streams": [
                {
                    "codec_type": "video",
                    "codec_name": "hevc",
                    "width": 3264,
                    "height": 2448,
                    "pix_fmt": "yuv420p10le",
                    "avg_frame_rate": "25/1",
                }
            ]
        }
        metrics = analyze_ffprobe(probe)
        failures = verify_recording_wire(
            metrics,
            RecordingThresholds(3264, 2448, 10, 25),
        )
        self.assertTrue(any("timestamped frame evidence" in failure for failure in failures))

    def test_runtime_log_extracts_capacity_abort_and_crash(self) -> None:
        findings = analyze_runtime_log(
            "owned=14 limit=14 maxImages=14\n"
            "NDR_RECORD_INGRESS_FAILURE\n"
            "processingDroppedFrames=3\n"
            "encoderCadenceStatus=CADENCE_MISMATCH\n"
            "FATAL EXCEPTION: main\n"
        )
        self.assertEqual(14, findings.raw_owned_peak)
        self.assertEqual(1, findings.raw_capacity_abort_count)
        self.assertEqual(3, findings.dropped_frame_count)
        self.assertEqual(1, findings.crash_count)


if __name__ == "__main__":
    unittest.main()
