"""Host controller validation; these assertions are not physical recording evidence."""
from pathlib import Path
import unittest

from tools.neo_qa_hypergrid.run_lq001_cinema_profile import (
    PACKAGE, diagnostic_case, profile_command, recording_command, streaming_trace_config,
    thermal_admitted, validate_request,
)


class CinemaProfileControllerTest(unittest.TestCase):
    adb = Path("sdk/platform-tools/adb.exe")
    sha = "a" * 64

    def test_preserves_previous_cpu_diagnostic_defaults(self):
        validate_request(self.adb, self.sha, "simpleperf", 60, 45)
        command = profile_command(self.adb, "serial", "simpleperf", "/data/local/tmp/a", 45)
        self.assertIn("cpu-clock", command)
        self.assertEqual(command[-2:], ["--duration", "45"])

    def test_sdk_adb_is_mandatory(self):
        for adb in (Path("adb.exe"), Path("bin/adb.exe"), Path("sdk/platform-tools/other.exe")):
            with self.subTest(adb=adb), self.assertRaises(ValueError):
                validate_request(adb, self.sha, "simpleperf", 60, 45)

    def test_rejects_missing_or_malformed_candidate(self):
        for sha in ("", "a" * 63, "x" * 64, "a" * 65):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                validate_request(self.adb, sha, "simpleperf", 60, 45)

    def test_rejects_unknown_capture_kind(self):
        with self.assertRaises(ValueError):
            validate_request(self.adb, self.sha, "unknown", 60, 45)
        with self.assertRaises(ValueError):
            profile_command(self.adb, "serial", "unknown", "trace", 45)

    def test_rejects_unbounded_or_overlapping_duration(self):
        for record, capture in ((0, 0), (59, 40), (301, 45), (300, 296), (60, 60), (300, 14)):
            with self.subTest(record=record, capture=capture), self.assertRaises(ValueError):
                validate_request(self.adb, self.sha, "perfetto", record, capture)

    def test_five_minute_trace_request_is_bounded(self):
        validate_request(self.adb, self.sha, "perfetto", 300, 295)

    def test_recording_quality_parameters_are_unchanged(self):
        command = recording_command(self.adb, "device", Path("evidence"), 300)
        for key, value in (("-Mode", "VIDEO"), ("-Pipeline", "CINEMA"),
                           ("-DetailProfile", "NATURAL_DETAIL"), ("-Resolution", "3264x2448"),
                           ("-Aspect", "4:3"), ("-Fps", "25"), ("-DurationSeconds", "300")):
            self.assertEqual(command[command.index(key) + 1], value)

    def test_direct_cadence_failure_can_be_profiled_without_cinema_substitution(self):
        command = recording_command(self.adb, "device", Path("evidence"), 300,
                                    "VIDEO/DIRECT/NATURAL_DETAIL/3264x2448")
        for key, value in (("-Mode", "VIDEO"), ("-Pipeline", "DIRECT"),
                           ("-DetailProfile", "NATURAL_DETAIL"), ("-Resolution", "3264x2448"),
                           ("-Aspect", "4:3"), ("-Fps", "25"), ("-DurationSeconds", "300")):
            self.assertEqual(command[command.index(key) + 1], value)

    def test_all_processed_matrix_cases_keep_mode_profile_geometry_and_cadence(self):
        for mode in ("VIDEO", "PRO"):
            for pipeline in ("DIRECT", "CINEMA"):
                for profile in ("NATURAL_DETAIL", "FOCUSED_DETAIL", "EXTREME_DETAIL"):
                    for resolution in ("3264x2448", "4096x3072", "3840x2160"):
                        case = "/".join((mode, pipeline, profile, resolution))
                        with self.subTest(case=case):
                            self.assertEqual(diagnostic_case(case), (mode, pipeline, profile, resolution))
                            command = recording_command(self.adb, "device", Path("evidence"), 300, case)
                            for key, value in (("-Mode", mode), ("-Pipeline", pipeline),
                                               ("-DetailProfile", profile), ("-Resolution", resolution),
                                               ("-Aspect", "16:9" if resolution == "3840x2160" else "4:3"),
                                               ("-Fps", "25"), ("-DurationSeconds", "300")):
                                self.assertEqual(command[command.index(key) + 1], value)

    def test_raw_and_unknown_cases_fail_before_any_device_operation(self):
        for case in ("RAW/CINEMA/NATURAL_DETAIL/4096x3072", "RAW/DIRECT/NATURAL_DETAIL/4096x3072",
                     "VIDEO/DIRECT/LOW_DETAIL/3264x2448", "VIDEO/DIRECT/NATURAL_DETAIL/1920x1080"):
            with self.subTest(case=case), self.assertRaises(ValueError):
                recording_command(self.adb, "device", Path("evidence"), 300, case)

    def test_arguments_with_spaces_remain_single_values(self):
        command = recording_command(Path("SDK path/platform-tools/adb.exe"), "device",
                                    Path("evidence with spaces"), 60)
        self.assertEqual(command[command.index("-AdbPath") + 1], str(Path("SDK path/platform-tools/adb.exe")))
        self.assertEqual(command[-1], str(Path("evidence with spaces")))

    def test_perfetto_requests_scheduler_and_app_trace_without_privilege_override(self):
        command = profile_command(self.adb, "device", "perfetto", "/data/misc/perfetto-traces/a", 285)
        self.assertIn("sched", command)
        self.assertEqual(command[command.index("--app") + 1], PACKAGE)
        self.assertEqual(command[command.index("-t") + 1], "285s")
        self.assertEqual(command[command.index("-b") + 1], "64mb")
        self.assertNotIn("setprop", command)
        self.assertNotIn("su", command)

    def test_missing_live_thermal_evidence_blocks(self):
        self.assertFalse(thermal_admitted("Thermal Status: 4"))
        self.assertFalse(thermal_admitted("Current temperatures from HAL:\nTemperature{mStatus=2}"))
        self.assertFalse(thermal_admitted("Thermal Status: 2\nCurrent temperatures from HAL:\n"))

    def test_emergency_or_shutdown_is_never_admitted(self):
        for status, current in ((5, 4), (4, 5), (6, 0), (0, 6)):
            with self.subTest(status=status, current=current):
                self.assertFalse(thermal_admitted(
                    f"Thermal Status: {status}\nCurrent temperatures from HAL:\nTemperature{{mStatus={current}}}"))

    def test_admission_uses_current_not_cached_hal_states(self):
        self.assertTrue(thermal_admitted(
            "Thermal Status: 3\nCached temperatures:\nTemperature{mStatus=5}\n"
            "Current temperatures from HAL:\nTemperature{mStatus=4}\nCurrent cooling devices from HAL:\n"))

    def test_streaming_trace_preserves_bounded_project_sources_and_exact_duration(self):
        for seconds in (1, 45, 295):
            with self.subTest(seconds=seconds):
                config = streaming_trace_config(seconds)
                self.assertIn(f"duration_ms: {seconds * 1000}\n", config)
                self.assertEqual(config.count("duration_ms:"), 1)
                self.assertIn("write_into_file: true", config)
                self.assertIn("file_write_period_ms: 10000", config)
                self.assertIn("max_file_size_bytes: 536870912", config)
                self.assertIn("size_kb: 32768", config)
                self.assertIn("sched/sched_switch", config)
                self.assertIn("block/block_rq_complete", config)
                self.assertIn(PACKAGE, config)

    def test_streaming_duration_rejects_zero_unbounded_and_non_integer_values(self):
        for duration in (0, -1, 296, 300, True, 1.5, "45"):
            with self.subTest(duration=duration), self.assertRaises(ValueError):
                streaming_trace_config(duration)

    def test_streaming_capture_uses_config_not_conflicting_duration_flags(self):
        config = "/data/local/tmp/lq001-cinema-20260908.pftrace.pbtxt"
        command = profile_command(self.adb, "device", "perfetto", "/data/misc/perfetto-traces/a", 295,
                                  config_path=config)
        self.assertEqual(command[-6:], ["perfetto", "--txt", "-c", config, "-o",
                                      "/data/misc/perfetto-traces/a"])
        self.assertNotIn("-t", command)
        self.assertNotIn("-b", command)
        self.assertNotIn("su", command)

    def test_streaming_config_cannot_escape_owned_paths_or_change_cpu_sampling(self):
        for config in ("/etc/test.pbtxt", "/data/local/tmp/lq001-../bad.pbtxt",
                       "/data/local/tmp/lq001-a.pbtxt;reboot",
                       "/data/misc/perfetto-configs/lq001-../bad.pbtxt", ""):
            with self.subTest(config=config), self.assertRaises(ValueError):
                profile_command(self.adb, "device", "perfetto", "trace", 45, config_path=config)
        with self.assertRaises(ValueError):
            profile_command(self.adb, "device", "simpleperf", "trace", 45,
                            config_path="/data/local/tmp/lq001-a.pbtxt")

    def test_android_perfetto_owned_config_directory_is_supported_without_permission_override(self):
        config = "/data/misc/perfetto-configs/lq001-cinema-20260908.pftrace.pbtxt"
        command = profile_command(self.adb, "device", "perfetto", "trace", 295, config_path=config)
        self.assertEqual(command[command.index("-c") + 1], config)
        self.assertNotIn("chmod", command)
        self.assertNotIn("su", command)
        source = Path("tools/neo_qa_hypergrid/run_lq001_cinema_profile.py").read_text(encoding="utf-8")
        self.assertIn('remote_config = "/data/misc/perfetto-configs/"', source)


if __name__ == "__main__":
    unittest.main()
